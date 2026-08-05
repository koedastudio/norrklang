package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.subsonic.SubsonicException

class CatalogMixesSessionTest {

    /** Fully scripted repository: every source is a map the test fills in. */
    private class FakeRepository : FakeMusicRepository() {
        val genreList = mutableListOf<Genre>()
        val albumsByGenreName = mutableMapOf<String, List<Album>>()
        val albumsByDecadeStart = mutableMapOf<Int, List<Album>>()
        val knownTracks = mutableMapOf<String, Track>()

        var genreFetches = 0
        var genreTrackBuilds = 0
        var decadeTrackBuilds = 0
        var failGenres = false

        override suspend fun genres(): List<Genre> {
            genreFetches++
            if (failGenres) throw SubsonicException.NetworkError(IOException("tunnel"))
            return genreList.toList()
        }

        override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
            albumsByGenreName[genre].orEmpty().take(size)

        override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
            albumsByDecadeStart[fromYear].orEmpty().take(size)

        override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> {
            genreTrackBuilds++
            return List(size) { stubTrack("$genre-$genreTrackBuilds-$it") }
        }

        override suspend fun randomTracksByYearRange(
            fromYear: Int,
            toYear: Int,
            size: Int,
        ): List<Track> {
            decadeTrackBuilds++
            return List(size) { stubTrack("$fromYear-$decadeTrackBuilds-$it") }
        }

        override suspend fun track(id: String): Track =
            knownTracks[id] ?: throw SubsonicException.NotFound("no $id")
    }

    private fun stubAlbum(id: String, artworkUrl: String? = "content://app.artwork/cover/$id") =
        Album(
            id = id,
            title = "Album $id",
            artistName = null,
            artistId = null,
            year = null,
            trackCount = 10,
            durationSec = 2400,
            artworkUrl = artworkUrl,
        )

    private fun genre(name: String, songCount: Int) = Genre(name, songCount)

    private fun repositoryWithCatalog() = FakeRepository().apply {
        genreList += listOf(
            genre("Rock", songCount = 500),
            genre("Pop", songCount = 300),
            genre("Jazz", songCount = 100),
        )
        for (g in genreList) albumsByGenreName[g.name] = listOf(stubAlbum("al-${g.name}"))
        albumsByDecadeStart[1980] = List(5) { stubAlbum("al-80s-$it") }
        albumsByDecadeStart[2000] = List(4) { stubAlbum("al-00s-$it") }
    }

    private fun session(repository: FakeRepository, year: Int = 2026) =
        CatalogMixesSession(repository, currentYear = { year })

    @Test
    fun `genre tiles are the biggest genres with artwork from their albums`() = runTest {
        val repository = repositoryWithCatalog()
        val mixes = session(repository)

        assertTrue(mixes.refresh("fp"))
        assertEquals(listOf("Rock", "Pop", "Jazz"), mixes.currentGenreMixes().map { it.name })
        assertEquals(
            "content://app.artwork/cover/al-Rock",
            mixes.currentGenreMixes().first().artworkUrl,
        )
    }

    @Test
    fun `small genres are filtered and tiles capped at MAX_GENRES`() = runTest {
        val repository = repositoryWithCatalog()
        repository.genreList += genre("Spoken Word", CatalogMixesSession.MIN_GENRE_SONGS - 1)
        repository.genreList += (1..10).map { genre("Filler $it", songCount = 50 + it) }
        val mixes = session(repository)

        mixes.refresh("fp")
        val names = mixes.currentGenreMixes().map { it.name }
        assertEquals(CatalogMixesSession.MAX_GENRES, names.size)
        assertFalse("Spoken Word" in names)
        // Biggest first: the three real genres outrank the filler.
        assertEquals(listOf("Rock", "Pop", "Jazz"), names.take(3))
    }

    @Test
    fun `decade tiles cover only decades with enough albums, oldest first`() = runTest {
        val repository = repositoryWithCatalog()
        // Two albums is below MIN_DECADE_ALBUMS — no 1990s tile.
        repository.albumsByDecadeStart[1990] = List(2) { stubAlbum("al-90s-$it") }
        val mixes = session(repository)

        mixes.refresh("fp")
        assertEquals(listOf(1980, 2000), mixes.currentDecadeMixes().map { it.startYear })
        assertEquals(
            "content://app.artwork/cover/al-80s-0",
            mixes.currentDecadeMixes().first().artworkUrl,
        )
    }

    @Test
    fun `refresh generates once per fingerprint and regenerates on account switch`() = runTest {
        val repository = repositoryWithCatalog()
        val mixes = session(repository)

        assertTrue(mixes.refresh("fp-1"))
        assertFalse(mixes.refresh("fp-1"))
        assertEquals(1, repository.genreFetches)
        assertTrue(mixes.refresh("fp-2"))
        assertEquals(2, repository.genreFetches)
    }

    @Test
    fun `a transient failure mid-generation is not adopted as empty`() = runTest {
        val repository = repositoryWithCatalog()
        repository.failGenres = true
        val mixes = session(repository)

        assertFalse(mixes.refresh("fp"))
        assertEquals(emptyList(), mixes.currentGenreMixes())

        // The next attempt (network back) generates for real.
        repository.failGenres = false
        assertTrue(mixes.refresh("fp"))
        assertEquals(3, mixes.currentGenreMixes().size)
    }

    @Test
    fun `mix track lists are stable until cleared`() = runTest {
        val repository = repositoryWithCatalog()
        val mixes = session(repository)
        mixes.refresh("fp")

        val rock = MediaId.HomeGenre("Rock")
        val first = mixes.queueTracks(rock)
        assertEquals(CatalogMixesSession.MIX_SIZE, first.size)
        assertEquals(first, mixes.queueTracks(rock))
        assertEquals(1, repository.genreTrackBuilds)

        val eighties = MediaId.HomeDecade(1980)
        assertEquals(mixes.queueTracks(eighties), mixes.queueTracks(eighties))
        assertEquals(1, repository.decadeTrackBuilds)

        mixes.clear()
        mixes.refresh("fp")
        mixes.queueTracks(rock)
        assertEquals(2, repository.genreTrackBuilds)
    }

    @Test
    fun `clear drops the tiles`() = runTest {
        val repository = repositoryWithCatalog()
        val mixes = session(repository)
        mixes.refresh("fp")

        mixes.clear()
        assertEquals(emptyList(), mixes.currentGenreMixes())
        assertEquals(emptyList(), mixes.currentDecadeMixes())
    }

    @Test
    fun `resume queue puts the saved track first and adopts it as the mix`() = runTest {
        val repository = repositoryWithCatalog()
        val saved = stubTrack("saved-1")
        repository.knownTracks[saved.id] = saved
        val mixes = session(repository)
        mixes.refresh("fp")

        val rock = MediaId.HomeGenre("Rock")
        val queue = mixes.resumeQueue(rock, saved.id)
        assertEquals(saved.id, queue.first().id)
        assertEquals(CatalogMixesSession.MIX_SIZE + 1, queue.size)
        // Browsing the tile afterwards shows the same queue.
        assertEquals(queue, mixes.queueTracks(rock))
    }

    @Test
    fun `resume queue for a mix unknown to the session builds it on demand`() = runTest {
        val repository = repositoryWithCatalog()
        repository.knownTracks["gone"] = stubTrack("gone")
        val mixes = session(repository)
        // No refresh: mimics resumption before (or without) tile generation.

        val queue = mixes.resumeQueue(MediaId.HomeDecade(1980), "gone")
        assertEquals("gone", queue.first().id)
        assertEquals(1, repository.decadeTrackBuilds)
    }
}
