package studio.koeda.norrklang.media

import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.subsonic.SubsonicException

/**
 * The "Similar to <artist>" mixes in the "Made for you" home section: up to
 * [MAX_MIXES] mixes seeded by the most-played and recently-played artists,
 * filled with library tracks by each seed and its Last.fm-similar artists.
 *
 * Recipe per mix: blend each queried artist's top songs with the server's
 * similar-songs selection, cap every artist (seed included) at
 * [PER_ARTIST_CAP], interleave so no artist clumps. Navidrome's
 * similar-songs answer skews toward one artist, so short buckets are topped
 * up from remaining top songs and the artist's own albums — without that,
 * personal libraries rarely reach [MIN_TRACKS]. Seeds below [MIN_TRACKS]
 * are skipped; if none qualify the section is empty and hidden.
 *
 * Snapshot/stability semantics live in [ArtistMixesSession].
 */
internal class SimilarMixesSession(
    repository: MusicRepository,
    private val random: Random = Random.Default,
) : ArtistMixesSession(repository) {

    override suspend fun generate(): List<Mix> {
        val accepted = mutableListOf<Mix>()
        val probed = mutableSetOf<String>()
        val deadProbes = DeadProbeTally()

        val (frequent, recent) = candidatePools()

        suspend fun acceptFrom(pool: List<Artist>, want: Int): Boolean {
            val before = accepted.size
            for (candidate in pool) {
                if (accepted.size - before >= want) break
                // Probed covers accepted seeds AND rejected ones, so the
                // spillover pass never re-fetches a candidate.
                if (!probed.add(candidate.id)) continue
                val attempt = buildMix(candidate)
                if (!attempt.serverHadTracks) {
                    if (deadProbes.recordDead(anythingAccepted = accepted.isNotEmpty())) {
                        return false
                    }
                    continue
                }
                deadProbes.recordLive()
                accepted += attempt.mix ?: continue
            }
            return true
        }

        if (!acceptFrom(frequent, FREQUENT_SHARE)) return emptyList()
        if (!acceptFrom(recent, MAX_MIXES - accepted.size)) return emptyList()
        // Spillover: when the recent pool couldn't fill the remainder (few
        // qualifying artists), the frequent pool's leftovers top the section up.
        acceptFrom(frequent + recent, MAX_MIXES - accepted.size)
        return accepted.shuffled(random)
    }

    /** Rebuild for queueTracks/resumeQueue; the base serves failures as empty. */
    override suspend fun buildTracksFor(key: String): List<Track> =
        buildMix(repository.artist(key).artist).mix?.tracks.orEmpty()

    /**
     * [mix] is null when the seed can't reach [MIN_TRACKS] tracks;
     * [serverHadTracks] false means the server offered nothing at all — the
     * "no Last.fm data for this artist" signal the dead-server bail-out uses.
     */
    private class Attempt(val mix: Mix?, val serverHadTracks: Boolean)

    /**
     * Tracks grouped per displayed artist, each bucket capped at
     * [PER_ARTIST_CAP], duplicate track ids dropped.
     */
    private class Buckets {
        private val byKey = LinkedHashMap<String, MutableList<Track>>()
        private val seenTrackIds = mutableSetOf<String>()

        /** [owner]'s key is the fallback for tracks that name no artist at all. */
        fun add(track: Track, owner: Artist? = null) {
            val key = artistKey(track) ?: owner?.let(::artistKey) ?: return
            if (track.id in seenTrackIds) return
            val bucket = byKey.getOrPut(key) { mutableListOf() }
            if (bucket.size < PER_ARTIST_CAP) {
                bucket += track
                seenTrackIds += track.id
            }
        }

        fun isShort(artist: Artist) = (byKey[artistKey(artist)]?.size ?: 0) < PER_ARTIST_CAP
        fun total() = byKey.values.sumOf { it.size }
        fun asMap(): Map<String, List<Track>> = byKey
    }

    private suspend fun buildMix(seed: Artist): Attempt {
        // Fetch twice as many as wanted: the repository filters out similar
        // artists that aren't in the library, and only in-library ones count.
        val similar = repository.similarArtists(seed.id, count = SIMILAR_ARTISTS * 2)
            .take(SIMILAR_ARTISTS)
        val queried = listOf(seed) + similar
        val buckets = Buckets()

        // Popular share: each queried artist opens its bucket with top songs.
        // Fetched at TOP_SONGS_COUNT (not the 3–5 bucketed) so the enrichment
        // pass has "rest of the top list" material and the response is
        // cache-shared with BestOfMixesSession.
        val topByArtist = coroutineScope {
            queried.map { artist ->
                async { artist to repository.topTracks(artist.name, TOP_SONGS_COUNT) }
            }.awaitAll().toMap()
        }
        for ((artist, top) in topByArtist) {
            top.take(TOP_PER_ARTIST).forEach { buckets.add(it, owner = artist) }
        }
        // Random share: tops queried buckets up to the cap and opens buckets
        // for the other similar artists the server picked.
        repository.similarTracks(seed.id, MAX_TRACKS).forEach { buckets.add(it) }
        // Enrichment: the similar-songs answer is usually near-single-artist;
        // fill short queried buckets from the rest of the top list, then
        // random tracks off the artist's own albums. Only when similar
        // artists exist — a seed without them can't reach [MIN_TRACKS], and
        // a server without Last.fm data must keep reading as dead.
        if (similar.isNotEmpty()) {
            for (artist in queried) {
                for (track in topByArtist.getValue(artist).drop(TOP_PER_ARTIST)) {
                    if (!buckets.isShort(artist)) break
                    buckets.add(track, owner = artist)
                }
                if (buckets.isShort(artist)) topUpFromAlbums(artist, buckets)
            }
        }

        val sourceTracks = buckets.total()
        if (sourceTracks < MIN_TRACKS) {
            return Attempt(mix = null, serverHadTracks = sourceTracks > 0)
        }
        val tracks = interleave(buckets.asMap(), limit = MAX_TRACKS)
        return Attempt(Mix(seed, artworkFor(seed, tracks), tracks), serverHadTracks = true)
    }

    private suspend fun topUpFromAlbums(artist: Artist, buckets: Buckets) {
        val albums = try {
            repository.artist(artist.id).albums
        } catch (_: SubsonicException.NotFound) {
            return
        }
        for (album in albums.shuffled(random).take(ALBUM_TOPUP_ALBUMS)) {
            if (!buckets.isShort(artist)) return
            val tracks = try {
                repository.album(album.id).tracks
            } catch (_: SubsonicException.NotFound) {
                continue
            }
            for (track in tracks.shuffled(random)) {
                if (!buckets.isShort(artist)) return
                buckets.add(track, owner = artist)
            }
        }
    }

    /**
     * Orders bucketed tracks so no artist clumps: draw from a largest bucket
     * whose artist differs from the last pick (random among ties). Greedy
     * avoids adjacent repeats whenever the largest bucket holds at most half
     * the remainder — guaranteed at the start by the cap and [MIN_TRACKS].
     * Stops at [limit].
     */
    private fun interleave(buckets: Map<String, List<Track>>, limit: Int): List<Track> {
        val remaining = buckets.entries.associateTo(LinkedHashMap()) { (key, tracks) ->
            key to ArrayDeque(tracks.shuffled(random))
        }
        val out = ArrayList<Track>(minOf(limit, buckets.values.sumOf { it.size }))
        var lastKey: String? = null
        val ties = ArrayList<String>()
        while (out.size < limit && remaining.isNotEmpty()) {
            var largest = -1
            ties.clear()
            for ((key, tracks) in remaining) {
                if (key == lastKey) continue
                if (tracks.size > largest) {
                    largest = tracks.size
                    ties.clear()
                    ties += key
                } else if (tracks.size == largest) {
                    ties += key
                }
            }
            // Only the last-picked artist's bucket left — a clump is unavoidable.
            val pick = if (ties.isEmpty()) lastKey!! else ties.random(random)
            val bucket = remaining.getValue(pick)
            out += bucket.removeFirst()
            if (bucket.isEmpty()) remaining.remove(pick)
            lastKey = pick
        }
        return out
    }

    companion object {
        /** 3 + 3 with [BestOfMixesSession.MAX_MIXES] keeps "Made for you" at six. */
        const val MAX_MIXES = 3

        /**
         * The most-played pool's guaranteed share; recently-played fills the
         * rest, and spillover lets either pool cover for the other.
         */
        const val FREQUENT_SHARE = 2

        const val MIN_TRACKS = 20
        const val MAX_TRACKS = 50
        const val PER_ARTIST_CAP = 5
        const val TOP_PER_ARTIST = 3

        /**
         * Seed + 9 similar = 10 artists × [PER_ARTIST_CAP] = the [MAX_TRACKS]
         * ceiling — a full mix uses the complete artist spread.
         */
        const val SIMILAR_ARTISTS = 9

        /** How many of an artist's albums the enrichment pass may fetch. */
        const val ALBUM_TOPUP_ALBUMS = 2
    }
}

/**
 * Grouping key for the per-artist cap and the interleaver. Keyed by displayed
 * artist name, not id: collaboration-album tracks can carry a different (or
 * no) artist id under the same visible name, and separate buckets would let
 * one visible artist exceed the cap. The id is a fallback for nameless tracks.
 */
private fun artistKey(track: Track): String? =
    track.artistName?.lowercase() ?: track.artistId

/** Same rule for a queried [Artist], whose name is always present. */
private fun artistKey(artist: Artist): String = artist.name.lowercase()
