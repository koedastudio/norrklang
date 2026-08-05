package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.subsonic.SubsonicException

class RandomMixSessionTest {

    /** Serves a distinct list per fetch (ids carry a generation counter). */
    private class FakeRepository : FakeMusicRepository() {
        var fetches = 0
        var knownTracks = mutableMapOf<String, Track>()

        override suspend fun randomTracks(size: Int): List<Track> {
            fetches++
            return List(size) { stubTrack("gen$fetches-tr$it") }
        }

        override suspend fun track(id: String): Track =
            knownTracks[id] ?: throw SubsonicException.NotFound("Song $id not found")
    }

    private val repository = FakeRepository()
    private var now = 0L
    private val mix = RandomMixSession(
        repository,
        clock = { now },
        size = 5,
        graceMillis = 60_000L,
    )

    @Test
    fun `browse within grace serves the same list without fetching`() = runTest {
        val first = mix.browseTracks()
        now += 30_000
        val second = mix.browseTracks()
        assertEquals(first, second)
        assertEquals(1, repository.fetches)
    }

    @Test
    fun `browse after the grace window regenerates`() = runTest {
        val first = mix.browseTracks()
        now += 61_000
        val second = mix.browseTracks()
        assertNotEquals(first, second)
        assertEquals(2, repository.fetches)
    }

    @Test
    fun `browse never regenerates while the mix is the play source`() = runTest {
        val first = mix.browseTracks()
        mix.isCurrentPlaySource = true
        now += 3_600_000
        assertEquals(first, mix.browseTracks())
        assertEquals(1, repository.fetches)
    }

    @Test
    fun `grace window slides - repeated browsing never regenerates`() = runTest {
        val first = mix.browseTracks()
        repeat(10) {
            now += 45_000
            assertEquals(first, mix.browseTracks())
        }
        assertEquals(1, repository.fetches)
    }

    @Test
    fun `queueTracks serves the browsed snapshot without fetching`() = runTest {
        val browsed = mix.browseTracks()
        now += 3_600_000 // even long after the grace window
        assertEquals(browsed, mix.queueTracks())
        assertEquals(1, repository.fetches)
    }

    @Test
    fun `resumeQueue puts the saved track first and adopts the queue`() = runTest {
        val saved = stubTrack("saved-tr")
        repository.knownTracks["saved-tr"] = saved

        val queue = mix.resumeQueue("saved-tr")
        assertEquals(saved, queue.first())
        assertEquals(6, queue.size) // saved + 5 fresh
        // Browsing afterwards shows exactly the resumed queue.
        assertEquals(queue, mix.browseTracks())
    }

    @Test
    fun `resumeQueue dedups a saved track the fresh mix already contains`() = runTest {
        // The fake's next generation will contain gen1-tr0..4.
        repository.knownTracks["gen1-tr2"] = stubTrack("gen1-tr2")

        val queue = mix.resumeQueue("gen1-tr2")
        assertEquals("gen1-tr2", queue.first().id)
        assertEquals(5, queue.size)
        assertEquals(1, queue.count { it.id == "gen1-tr2" })
    }

    @Test
    fun `resumeQueue with a deleted saved track yields just the fresh mix`() = runTest {
        val queue = mix.resumeQueue("gone")
        assertEquals(5, queue.size)
        assertTrue(queue.none { it.id == "gone" })
    }

    @Test
    fun `montageTracks generates a mix when none exists`() = runTest {
        val montage = mix.montageTracks()
        assertEquals(5, montage.size)
        assertEquals(1, repository.fetches)
        // A browse right after shows exactly what the tile rendered.
        assertEquals(montage, mix.browseTracks())
        assertEquals(1, repository.fetches)
    }

    @Test
    fun `montageTracks reuses the snapshot without extending the grace window`() = runTest {
        val first = mix.browseTracks()
        now += 50_000
        assertEquals(first, mix.montageTracks()) // no reshuffle, no fetch
        assertEquals(1, repository.fetches)
        // Grace still counts from the browse, not the montage render: a
        // browse past the original window regenerates.
        now += 20_000
        assertNotEquals(first, mix.browseTracks())
        assertEquals(2, repository.fetches)
    }

    @Test
    fun `clear empties the snapshot and forces regeneration`() = runTest {
        val first = mix.browseTracks()
        mix.clear()
        assertNotEquals(first, mix.browseTracks())
        assertEquals(2, repository.fetches)
    }

}
