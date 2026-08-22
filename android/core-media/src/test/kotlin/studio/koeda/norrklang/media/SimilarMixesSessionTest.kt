package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException

class SimilarMixesSessionTest {

    /** Fully scripted repository: every source is a map the test fills in. */
    private class FakeRepository : FakeMusicRepository() {
        val frequentPool = mutableListOf<Artist>()
        val recentPool = mutableListOf<Artist>()
        val similarArtistsById = mutableMapOf<String, List<Artist>>()
        val similarTracksById = mutableMapOf<String, List<Track>>()
        val topTracksByName = mutableMapOf<String, List<Track>>()
        val knownTracks = mutableMapOf<String, Track>()
        val knownArtists = mutableMapOf<String, Artist>()
        val albumsByArtist = mutableMapOf<String, List<Album>>()
        val albumTracks = mutableMapOf<String, List<Track>>()

        var poolFetches = 0
        val probedArtists = mutableListOf<String>()
        var failSimilarTracksFor: String? = null

        override suspend fun mostPlayedArtists(size: Int): List<Artist> {
            poolFetches++
            return frequentPool.toList()
        }

        override suspend fun recentlyPlayedArtists(size: Int): List<Artist> {
            poolFetches++
            return recentPool.toList()
        }

        override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
            similarArtistsById[artistId].orEmpty()

        override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
            if (artistId == failSimilarTracksFor) {
                throw MusicException.NetworkError(IOException("car left the garage"))
            }
            probedArtists += artistId
            return similarTracksById[artistId].orEmpty().take(count)
        }

        override suspend fun topTracks(artistName: String, count: Int): List<Track> =
            topTracksByName[artistName].orEmpty().take(count)

        override suspend fun track(id: String): Track =
            knownTracks[id] ?: throw MusicException.NotFound("Song $id not found")

        override suspend fun artist(id: String): ArtistDetail =
            knownArtists[id]?.let { ArtistDetail(it, albums = albumsByArtist[id].orEmpty()) }
                ?: throw MusicException.NotFound("Artist $id not found")

        override suspend fun album(id: String): AlbumDetail =
            AlbumDetail(
                album = Album(
                    id = id,
                    title = "Album $id",
                    artistName = null,
                    artistId = null,
                    year = null,
                    trackCount = albumTracks[id].orEmpty().size,
                    durationSec = 0,
                    artworkUrl = null,
                ),
                tracks = albumTracks[id].orEmpty(),
            )
    }

    private val repository = FakeRepository()
    private val mixes = SimilarMixesSession(repository, random = Random(42))

    /**
     * Makes [seed] yield a mix of exactly `artists * perArtist` candidate
     * tracks through the similar-songs pool (each synthetic artist under the
     * per-artist cap), so 5×5 qualifies (25 ≥ 20) and 4×4 does not (16 < 20).
     */
    private fun qualify(seed: Artist, artists: Int = 5, perArtist: Int = 5) {
        repository.knownArtists[seed.id] = seed
        repository.similarTracksById[seed.id] = (0 until artists).flatMap { a ->
            (0 until perArtist).map { i -> stubTrack("${seed.id}-a$a-t$i", artistId = "${seed.id}-a$a") }
        }
    }

    @Test
    fun `splits two frequent plus one recent and dedupes artists in both pools`() = runTest {
        val f = (1..3).map { artist("f$it") }
        val r = listOf(f[0]) + (1..2).map { artist("r$it") } // f1 also most recent
        f.forEach { qualify(it) }
        r.forEach { qualify(it) }
        repository.frequentPool += f
        repository.recentPool += r

        assertTrue(mixes.refresh("fp"))
        val seeds = mixes.currentMixes().map { it.artist.id }
        assertEquals(3, seeds.size)
        // f1 counts once (frequent), so recent contributes r1.
        assertEquals(setOf("f1", "f2", "r1"), seeds.toSet())
    }

    @Test
    fun `excluded seeds are skipped in every pass`() = runTest {
        val f = (1..5).map { artist("f$it") }.onEach { qualify(it) }
        repository.frequentPool += f
        repository.recentPool += f // spillover pass sees them too
        val session = SimilarMixesSession(
            repository,
            random = Random(42),
            excludedSeedIds = { setOf("f1", "f3") },
        )

        assertTrue(session.refresh("fp"))
        val seeds = session.currentMixes().map { it.artist.id }
        assertEquals(setOf("f2", "f4", "f5"), seeds.toSet())
    }

    @Test
    fun `a seed below the track minimum is skipped for the next candidate`() = runTest {
        val thin = artist("thin")
        qualify(thin, artists = 4, perArtist = 4) // 16 < 20
        val fat = (1..3).map { artist("fat$it") }.onEach { qualify(it) }
        repository.frequentPool += listOf(thin) + fat

        assertTrue(mixes.refresh("fp"))
        val seeds = mixes.currentMixes().map { it.artist.id }.toSet()
        assertEquals(setOf("fat1", "fat2", "fat3"), seeds)
    }

    @Test
    fun `frequent leftovers spill over when the recent pool runs dry`() = runTest {
        val f = (1..3).map { artist("f$it") }.onEach { qualify(it) }
        repository.frequentPool += f

        assertTrue(mixes.refresh("fp"))
        val seeds = mixes.currentMixes().map { it.artist.id }.toSet()
        // 2 frequent + 0 recent + 1 frequent leftover.
        assertEquals(setOf("f1", "f2", "f3"), seeds)
    }

    @Test
    fun `the recent pool fills all remaining slots when frequent runs dry`() = runTest {
        val f = listOf(artist("f1").also { qualify(it) })
        val r = (1..3).map { artist("r$it") }.onEach { qualify(it) }
        repository.frequentPool += f
        repository.recentPool += r

        assertTrue(mixes.refresh("fp"))
        val seeds = mixes.currentMixes().map { it.artist.id }.toSet()
        // 1 frequent + 2 recent to reach the cap.
        assertEquals(setOf("f1", "r1", "r2"), seeds)
    }

    @Test
    fun `the section never exceeds MAX_MIXES however many candidates qualify`() = runTest {
        // The similar mixes share "Made for you" with the best-of tiles
        // (3 + 3); oversupply in both pools must not push past the cap.
        repository.frequentPool += (1..15).map { artist("f$it").also { a -> qualify(a) } }
        repository.recentPool += (1..15).map { artist("r$it").also { a -> qualify(a) } }

        assertTrue(mixes.refresh("fp"))
        assertEquals(SimilarMixesSession.MAX_MIXES, mixes.currentMixes().size)
    }

    @Test
    fun `mixes stay within the 20 to 50 track window`() = runTest {
        val rich = artist("rich")
        qualify(rich, artists = 20, perArtist = 5) // 100 candidates
        val lean = artist("lean")
        qualify(lean, artists = 4, perArtist = 5) // exactly 20
        repository.frequentPool += listOf(rich, lean)

        assertTrue(mixes.refresh("fp"))
        val byId = mixes.currentMixes().associateBy { it.artist.id }
        assertEquals(50, byId.getValue("rich").tracks.size)
        assertEquals(20, byId.getValue("lean").tracks.size)
    }

    @Test
    fun `blends top songs with similar songs under one per-artist cap`() = runTest {
        val seed = artist("seed", name = "Seed")
        val similar = listOf(artist("sa", name = "Alpha"), artist("sb", name = "Beta"))
        repository.similarArtistsById["seed"] = similar
        repository.knownArtists["seed"] = seed
        // 5 top songs each for the seed and both similar artists…
        for (a in listOf(seed) + similar) {
            repository.topTracksByName[a.name] =
                (0 until 5).map { stubTrack("top-${a.id}-$it", artistId = a.id) }
        }
        // …and a similar-songs pool with more by the same three plus others,
        // including a duplicate of a top track that must not appear twice.
        repository.similarTracksById["seed"] =
            listOf(stubTrack("top-seed-0", artistId = "seed")) +
                (listOf(seed) + similar).flatMap { a ->
                    (0 until 5).map { stubTrack("rnd-${a.id}-$it", artistId = a.id) }
                } +
                (0 until 3).flatMap { x ->
                    (0 until 5).map { stubTrack("x$x-t$it", artistId = "extra$x") }
                }
        repository.frequentPool += seed

        assertTrue(mixes.refresh("fp"))
        val tracks = mixes.currentMixes().single().tracks

        assertTrue(tracks.size in 20..50)
        assertEquals(tracks.size, tracks.distinctBy { it.id }.size, "duplicate track ids")
        val perArtist = tracks.groupBy { it.artistId }
        assertTrue(perArtist.values.all { it.size <= 5 }, "an artist exceeds the cap")
        for (a in listOf(seed) + similar) {
            val own = perArtist.getValue(a.id)
            val top = own.count { it.id.startsWith("top-") }
            assertTrue(top in 1..3, "expected 1–3 top songs for ${a.id}, got $top")
            assertTrue(own.any { it.id.startsWith("rnd-") }, "no random share for ${a.id}")
        }
    }

    @Test
    fun `enriches short similar-artist buckets from top songs and albums`() = runTest {
        // Realistic Navidrome shape: the similar-songs pool is all seed
        // tracks, so the similar artists' buckets must be filled from their
        // own top songs and albums.
        val seed = artist("seed", name = "Seed")
        val a1 = artist("a1", name = "Alpha") // full top list
        val a2 = artist("a2", name = "Beta") // short top list, has albums
        val a3 = artist("a3", name = "Gamma") // no top list, has albums
        repository.knownArtists["seed"] = seed
        repository.similarArtistsById["seed"] = listOf(a1, a2, a3)
        repository.similarTracksById["seed"] =
            (0 until 10).map { stubTrack("pool-seed-$it", artistId = "seed") }
        repository.topTracksByName["Seed"] =
            (0 until 5).map { stubTrack("top-seed-$it", artistId = "seed") }
        repository.topTracksByName["Alpha"] =
            (0 until 5).map { stubTrack("top-a1-$it", artistId = "a1") }
        repository.topTracksByName["Beta"] =
            (0 until 2).map { stubTrack("top-a2-$it", artistId = "a2") }
        for (a in listOf(a2, a3)) {
            repository.knownArtists[a.id] = a
            repository.albumsByArtist[a.id] = listOf(
                Album(
                    id = "${a.id}-al1",
                    title = "Album",
                    artistName = a.name,
                    artistId = a.id,
                    year = null,
                    trackCount = 6,
                    durationSec = 0,
                    artworkUrl = null,
                ),
            )
            repository.albumTracks["${a.id}-al1"] =
                (0 until 6).map { stubTrack("alb-${a.id}-$it", artistId = a.id) }
        }
        repository.frequentPool += seed

        assertTrue(mixes.refresh("fp"))
        val tracks = mixes.currentMixes().single().tracks
        // 4 artists × the full cap of 5 — exactly the 20-track minimum.
        assertEquals(20, tracks.size)
        val perArtist = tracks.groupBy { it.artistId }
        assertEquals(setOf("seed", "a1", "a2", "a3"), perArtist.keys)
        assertTrue(perArtist.values.all { it.size == 5 })
        // Beta: 2 top songs + 3 album tracks; Gamma: 5 album tracks.
        assertEquals(3, perArtist.getValue("a2").count { it.id.startsWith("alb-") })
        assertTrue(perArtist.getValue("a3").all { it.id.startsWith("alb-") })
    }

    @Test
    fun `no album enrichment without similar artists`() = runTest {
        // A seed with library albums but zero Last.fm data must stay dead —
        // padding it from its own albums would defeat the auto-hide.
        val seed = artist("f1")
        repository.knownArtists["f1"] = seed
        repository.albumsByArtist["f1"] = listOf(
            Album(
                id = "f1-al1",
                title = "Album",
                artistName = seed.name,
                artistId = "f1",
                year = null,
                trackCount = 30,
                durationSec = 0,
                artworkUrl = null,
            ),
        )
        repository.albumTracks["f1-al1"] =
            (0 until 30).map { stubTrack("alb-f1-$it", artistId = "f1") }
        repository.frequentPool += seed

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentMixes())
    }

    @Test
    fun `tracks with missing artist ids still count toward the artist cap`() = runTest {
        // Collaboration-album tracks can show the same artist name without an
        // artist id — they must share the named artist's bucket, not open a
        // second one that dodges the cap and the interleaver.
        val seed = artist("s")
        repository.knownArtists["s"] = seed
        repository.similarTracksById["s"] =
            (0 until 5).map { stubTrack("id-$it", artistId = "sg") } +
                (0 until 5).map { stubTrack("noid-$it", artistId = "sg").copy(artistId = null) } +
                (0 until 4).flatMap { a ->
                    (0 until 5).map { stubTrack("o$a-$it", artistId = "other$a") }
                }
        repository.frequentPool += seed

        assertTrue(mixes.refresh("fp"))
        val tracks = mixes.currentMixes().single().tracks
        assertEquals(5, tracks.count { it.artistName == "Artist sg" })
    }

    @Test
    fun `no two adjacent tracks share an artist`() = runTest {
        val seed = artist("seed")
        qualify(seed, artists = 6, perArtist = 5)
        repository.frequentPool += seed

        assertTrue(mixes.refresh("fp"))
        val tracks = mixes.currentMixes().single().tracks
        for (i in 1 until tracks.size) {
            assertNotEquals(
                tracks[i - 1].artistId,
                tracks[i].artistId,
                "adjacent tracks by ${tracks[i].artistId} at $i",
            )
        }
    }

    @Test
    fun `section order is random, not acceptance order`() = runTest {
        val f = (1..6).map { artist("f$it") }.onEach { qualify(it) }
        repository.frequentPool += f

        // The shared repository holds no per-session state, so two sessions
        // over it differ only by their Random seed.
        suspend fun orderWith(seed: Int): List<String> {
            val session = SimilarMixesSession(repository, random = Random(seed))
            session.refresh("fp")
            return session.currentMixes().map { it.artist.id }
        }

        val first = orderWith(seed = 1)
        val second = orderWith(seed = 2)
        assertEquals(first.toSet(), second.toSet())
        assertNotEquals(first, second)
    }

    @Test
    fun `refresh is a no-op for a fingerprint already generated`() = runTest {
        val seed = artist("f1").also { qualify(it) }
        repository.frequentPool += seed

        assertTrue(mixes.refresh("fp"))
        val first = mixes.currentMixes()
        val fetchesAfterFirst = repository.poolFetches

        assertFalse(mixes.refresh("fp"))
        assertEquals(first, mixes.currentMixes())
        assertEquals(fetchesAfterFirst, repository.poolFetches)
    }

    @Test
    fun `currentMixes never generates`() = runTest {
        assertEquals(emptyList(), mixes.currentMixes())
        assertEquals(0, repository.poolFetches)
    }

    @Test
    fun `server without lastfm data yields an empty hidden section`() = runTest {
        // Play history exists, but no similar/top data comes back for anyone.
        repository.frequentPool += (1..3).map { artist("f$it") }

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentMixes())
    }

    @Test
    fun `gives up after three dead probes instead of walking every candidate`() = runTest {
        // 20 played artists, none with any Last.fm data.
        repository.frequentPool += (1..10).map { artist("f$it") }
        repository.recentPool += (1..10).map { artist("r$it") }

        assertFalse(mixes.refresh("fp"))
        assertEquals(listOf("f1", "f2", "f3"), repository.probedArtists)
    }

    @Test
    fun `dead probes among live ones do not abort the section`() = runTest {
        // f1 has no Last.fm data at all; the rest qualify. One dead artist
        // must not trip the dead-server bail-out once anything was accepted.
        val live = (2..7).map { artist("f$it") }.onEach { qualify(it) }
        repository.frequentPool += listOf(artist("f1")) + live

        assertTrue(mixes.refresh("fp"))
        assertEquals(SimilarMixesSession.MAX_MIXES, mixes.currentMixes().size)
    }

    @Test
    fun `a transient failure aborts without adopting and retries cleanly`() = runTest {
        val f = (1..2).map { artist("f$it") }.onEach { qualify(it) }
        repository.frequentPool += f
        repository.failSimilarTracksFor = "f2"

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentMixes())

        repository.failSimilarTracksFor = null
        assertTrue(mixes.refresh("fp"))
        assertEquals(setOf("f1", "f2"), mixes.currentMixes().map { it.artist.id }.toSet())
    }

    @Test
    fun `a new fingerprint regenerates and clear drops everything`() = runTest {
        val seed = artist("f1").also { qualify(it) }
        repository.frequentPool += seed

        assertTrue(mixes.refresh("account-a"))
        val probesAfterFirst = repository.probedArtists.size
        assertTrue(mixes.refresh("account-b"))
        assertTrue(repository.probedArtists.size > probesAfterFirst)

        mixes.clear()
        assertEquals(emptyList(), mixes.currentMixes())
    }

    @Test
    fun `queueTracks serves the section snapshot verbatim`() = runTest {
        val seed = artist("f1").also { qualify(it) }
        repository.frequentPool += seed

        mixes.refresh("fp")
        val shown = mixes.currentMixes().single().tracks
        assertEquals(shown, mixes.queueTracks("f1"))
    }

    @Test
    fun `queueTracks rebuilds a mix this session does not know`() = runTest {
        val stranger = artist("elsewhere").also { qualify(it) }

        val rebuilt = mixes.queueTracks("elsewhere")
        assertTrue(rebuilt.size >= 20)
        // Adopted: the same queue comes back without regenerating.
        val probes = repository.probedArtists.size
        assertEquals(rebuilt, mixes.queueTracks("elsewhere"))
        assertEquals(probes, repository.probedArtists.size)
        assertTrue(rebuilt.all { it.artistId!!.startsWith(stranger.id) })
    }

    @Test
    fun `resumeQueue puts the saved track first and adopts the queue`() = runTest {
        artist("f1").also { qualify(it) }
        val saved = stubTrack("saved", artistId = "someone")
        repository.knownTracks["saved"] = saved

        val queue = mixes.resumeQueue("f1", "saved")
        assertEquals(saved, queue.first())
        assertTrue(queue.size >= 21) // saved + a full fresh mix
        assertEquals(1, queue.count { it.id == "saved" })
        // Browsing the tile afterwards shows exactly the resumed queue.
        assertEquals(queue, mixes.queueTracks("f1"))
    }

    @Test
    fun `resumeQueue dedups a saved track the fresh mix already contains`() = runTest {
        artist("f1").also { qualify(it) }
        val inMix = repository.similarTracksById.getValue("f1").first()
        repository.knownTracks[inMix.id] = inMix

        val queue = mixes.resumeQueue("f1", inMix.id)
        assertEquals(inMix.id, queue.first().id)
        assertEquals(1, queue.count { it.id == inMix.id })
    }

    @Test
    fun `resumeQueue with a deleted saved track yields just the fresh mix`() = runTest {
        artist("f1").also { qualify(it) }

        val queue = mixes.resumeQueue("f1", "gone")
        assertTrue(queue.size >= 20)
        assertTrue(queue.none { it.id == "gone" })
    }

    private companion object {
        fun artist(id: String, name: String = "Artist $id") = Artist(
            id = id,
            name = name,
            albumCount = 1,
            artworkUrl = null,
        )
    }
}
