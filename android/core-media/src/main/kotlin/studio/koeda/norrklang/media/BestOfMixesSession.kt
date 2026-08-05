package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The "Best of <artist>" mixes in the "Made for you" home section: up to
 * [MAX_MIXES] tiles for the artists the user plays most, each opening that
 * artist's most popular library tracks ([MusicRepository.topTracks],
 * Last.fm-backed). Complements [SimilarMixesSession]'s outward exploration.
 *
 * Candidates: most-played artists, topped up with recently played. An artist
 * with fewer than [MIN_TRACKS] popular tracks is skipped for the next
 * candidate; at most [MAX_PROBES] candidates are probed, and the
 * [DEAD_PROBE_LIMIT] bail-out covers servers without Last.fm data.
 *
 * Snapshot/stability semantics live in [HomeMixesSession].
 */
internal class BestOfMixesSession(
    repository: MusicRepository,
) : ArtistMixesSession(repository) {

    override suspend fun generate(): List<Mix> {
        val (frequent, recent) = candidatePools()
        val candidates = (frequent + recent).distinctBy { it.id }
        val accepted = mutableListOf<Mix>()
        val deadProbes = DeadProbeTally()
        for (candidate in candidates.take(MAX_PROBES)) {
            if (accepted.size >= MAX_MIXES) break
            val tracks = repository.topTracks(candidate.name, TOP_SONGS_COUNT)
            if (tracks.isEmpty()) {
                if (deadProbes.recordDead(anythingAccepted = accepted.isNotEmpty())) {
                    return emptyList()
                }
                continue
            }
            deadProbes.recordLive()
            if (tracks.size < MIN_TRACKS) continue
            accepted += Mix(candidate, artworkFor(candidate, tracks), tracks)
        }
        return accepted
    }

    /** Rebuild for queueTracks/resumeQueue; the base serves failures as empty. */
    override suspend fun buildTracksFor(key: String): List<Track> =
        repository.topTracks(repository.artist(key).artist.name, TOP_SONGS_COUNT)

    companion object {
        /** 3 + 3 with [SimilarMixesSession.MAX_MIXES] keeps "Made for you" at six. */
        const val MAX_MIXES = 3

        /** A "best of" shorter than this reads as a gap, not a highlight reel. */
        const val MIN_TRACKS = 10

        const val MAX_PROBES = 10
    }
}
