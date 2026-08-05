package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.subsonic.SubsonicException

class BestOfMixesSessionTest {

    /** Fully scripted repository: every source is a map the test fills in. */
    private class FakeRepository : FakeMusicRepository() {
        val frequentPool = mutableListOf<Artist>()
        val recentPool = mutableListOf<Artist>()
        val topTracksByName = mutableMapOf<String, List<Track>>()
        val knownArtists = mutableMapOf<String, Artist>()
        val knownTracks = mutableMapOf<String, Track>()

        var poolFetches = 0
        val probedNames = mutableListOf<String>()
        var failPools = false

        override suspend fun mostPlayedArtists(size: Int): List<Artist> {
            poolFetches++
            if (failPools) throw SubsonicException.NetworkError(IOException("tunnel"))
            return frequentPool.toList()
        }

        override suspend fun recentlyPlayedArtists(size: Int): List<Artist> = recentPool.toList()

        override suspend fun topTracks(artistName: String, count: Int): List<Track> {
            probedNames += artistName
            return topTracksByName[artistName].orEmpty().take(count)
        }

        override suspend fun artist(id: String): ArtistDetail =
            knownArtists[id]?.let { ArtistDetail(it, albums = emptyList()) }
                ?: throw SubsonicException.NotFound("Artist $id not found")

        override suspend fun track(id: String): Track =
            knownTracks[id] ?: throw SubsonicException.NotFound("Song $id not found")
    }

    private val repository = FakeRepository()
    private val mixes = BestOfMixesSession(repository)

    /** Gives [artist] a popular-track list of [tracks] library songs. */
    private fun qualify(artist: Artist, tracks: Int = ArtistMixesSession.TOP_SONGS_COUNT) {
        repository.knownArtists[artist.id] = artist
        repository.topTracksByName[artist.name] =
            (0 until tracks).map { stubTrack("top-${artist.id}-$it", artistId = artist.id) }
    }

    @Test
    fun `tiles are the most played artists with popular tracks, capped at three`() = runTest {
        repository.frequentPool += (1..5).map { artist("f$it").also(::qualify) }

        assertTrue(mixes.refresh("fp"))
        assertEquals(listOf("f1", "f2", "f3"), mixes.currentMixes().map { it.artist.id })
        assertEquals(
            ArtistMixesSession.TOP_SONGS_COUNT,
            mixes.currentMixes().first().tracks.size,
        )
    }

    @Test
    fun `an artist with a thin top list is skipped for the next candidate`() = runTest {
        val thin = artist("thin")
        qualify(thin, tracks = BestOfMixesSession.MIN_TRACKS - 1)
        repository.frequentPool += listOf(thin) + (1..3).map { artist("fat$it").also(::qualify) }

        assertTrue(mixes.refresh("fp"))
        assertEquals(listOf("fat1", "fat2", "fat3"), mixes.currentMixes().map { it.artist.id })
    }

    @Test
    fun `recently played artists top up when the frequent pool runs short`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)
        // f1 appears in both pools; the dedupe must not probe it twice.
        repository.recentPool +=
            listOf(repository.frequentPool.first()) + (1..2).map { artist("r$it").also(::qualify) }

        assertTrue(mixes.refresh("fp"))
        assertEquals(listOf("f1", "r1", "r2"), mixes.currentMixes().map { it.artist.id })
        assertEquals(listOf("Artist f1", "Artist r1", "Artist r2"), repository.probedNames)
    }

    @Test
    fun `artwork falls back to the artist detail cover, then a track cover`() = runTest {
        val bare = artist("bare")
        qualify(bare)
        // The played-artists pools carry no artwork; the artist detail does.
        repository.knownArtists["bare"] = bare.copy(artworkUrl = "content://cover/bare")
        repository.frequentPool += bare

        assertTrue(mixes.refresh("fp"))
        assertEquals("content://cover/bare", mixes.currentMixes().single().artworkUrl)
    }

    @Test
    fun `server without lastfm data yields no tiles after three dead probes`() = runTest {
        repository.frequentPool += (1..10).map { artist("f$it") }

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentMixes())
        assertEquals(3, repository.probedNames.size)
    }

    @Test
    fun `probing stops after the probe budget even with live data`() = runTest {
        // Every candidate answers, but all below the minimum — the generation
        // must stop at MAX_PROBES rather than walk all 30 candidates.
        repository.frequentPool += (1..30).map { a ->
            artist("f$a").also { qualify(it, tracks = BestOfMixesSession.MIN_TRACKS - 1) }
        }

        assertFalse(mixes.refresh("fp"))
        assertEquals(BestOfMixesSession.MAX_PROBES, repository.probedNames.size)
    }

    @Test
    fun `refresh is a no-op for a fingerprint already generated`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)

        assertTrue(mixes.refresh("fp"))
        assertFalse(mixes.refresh("fp"))
        assertEquals(1, repository.poolFetches)
        assertTrue(mixes.refresh("fp-2"))
        assertEquals(2, repository.poolFetches)
    }

    @Test
    fun `a transient failure aborts without adopting and retries cleanly`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)
        repository.failPools = true

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentMixes())

        repository.failPools = false
        assertTrue(mixes.refresh("fp"))
        assertEquals(1, mixes.currentMixes().size)
    }

    @Test
    fun `queueTracks serves the section snapshot and rebuilds unknown artists`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)
        mixes.refresh("fp")
        assertEquals(mixes.currentMixes().single().tracks, mixes.queueTracks("f1"))

        // An artist from a previous drive's resumption id: rebuilt by name.
        val stranger = artist("elsewhere").also(::qualify)
        val rebuilt = mixes.queueTracks("elsewhere")
        assertEquals(ArtistMixesSession.TOP_SONGS_COUNT, rebuilt.size)
        assertTrue(rebuilt.all { it.artistId == stranger.id })
        // Adopted: served again without re-probing.
        val probes = repository.probedNames.size
        assertEquals(rebuilt, mixes.queueTracks("elsewhere"))
        assertEquals(probes, repository.probedNames.size)
    }

    @Test
    fun `resumeQueue puts the saved track first and adopts the queue`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)
        val saved = stubTrack("saved", artistId = "someone")
        repository.knownTracks["saved"] = saved

        val queue = mixes.resumeQueue("f1", "saved")
        assertEquals(saved, queue.first())
        assertEquals(ArtistMixesSession.TOP_SONGS_COUNT + 1, queue.size)
        assertEquals(queue, mixes.queueTracks("f1"))
    }

    @Test
    fun `clear drops the tiles`() = runTest {
        repository.frequentPool += artist("f1").also(::qualify)
        mixes.refresh("fp")

        mixes.clear()
        assertEquals(emptyList(), mixes.currentMixes())
    }

    private companion object {
        fun artist(id: String) = Artist(
            id = id,
            name = "Artist $id",
            albumCount = 0,
            artworkUrl = null,
        )
    }
}
