package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track

class CatalogMixesTest {

    private class Repository : FakeMusicRepository() {
        val genreDraws = mutableListOf<String>()
        val decadeDraws = mutableListOf<Pair<Int, Int>>()

        override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> {
            genreDraws += genre
            return List(size) { stubTrack("$genre-${genreDraws.size}-$it") }
        }

        override suspend fun randomTracksByYearRange(fromYear: Int, toYear: Int, size: Int): List<Track> {
            decadeDraws += fromYear to toYear
            return List(size) { stubTrack("$fromYear-${decadeDraws.size}-$it") }
        }
    }

    @Test
    fun `a genre mix is a random draw of the genre, stable until cleared`() = runTest {
        val repository = Repository()
        val mixes = CatalogMixes(repository)
        val rock = MediaId.GenreMix("Rock")

        val first = mixes.queueTracks(rock)
        assertEquals(CATALOG_MIX_SIZE, first.size)
        assertEquals(first, mixes.queueTracks(rock))
        assertEquals(listOf("Rock"), repository.genreDraws)

        mixes.clear()
        mixes.queueTracks(rock)
        assertEquals(2, repository.genreDraws.size)
    }

    @Test
    fun `a decade mix draws from the decade's ten years`() = runTest {
        val repository = Repository()
        val mixes = CatalogMixes(repository)

        mixes.queueTracks(MediaId.DecadeMix(1980))
        assertEquals(listOf(1980 to 1989), repository.decadeDraws)
    }
}
