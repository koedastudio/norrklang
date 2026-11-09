package studio.koeda.norrklang.media

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException

class MixQueuesTest {

    private class Repository : FakeMusicRepository() {
        val knownTracks = mutableMapOf<String, Track>()
        override suspend fun track(id: String): Track =
            knownTracks[id] ?: throw MusicException.NotFound("no $id")
    }

    @Test
    fun `a mix is built once and served unchanged afterwards`() = runTest {
        var builds = 0
        val queues = MixQueues<String>(Repository()) { key ->
            builds++
            List(3) { stubTrack("$key-$builds-$it") }
        }

        val first = queues.queueTracks("rock")
        assertEquals(first, queues.queueTracks("rock"))
        assertEquals(1, builds)
    }

    @Test
    fun `failed and empty builds are served empty and not kept`() = runTest {
        var attempts = 0
        val queues = MixQueues<String>(Repository()) {
            when (++attempts) {
                1 -> throw MusicException.NetworkError(IOException("tunnel"))
                2 -> emptyList()
                else -> listOf(stubTrack("tr-1"))
            }
        }

        assertEquals(emptyList(), queues.queueTracks("rock"))
        assertEquals(emptyList(), queues.queueTracks("rock"))
        assertEquals(listOf("tr-1"), queues.queueTracks("rock").map { it.id })
        assertEquals(3, attempts)
    }

    @Test
    fun `clear drops the lists and a build in flight across it is not adopted`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<Track>>()
        var builds = 0
        val queues = MixQueues<String>(Repository()) {
            builds++
            if (builds == 1) {
                started.complete(Unit)
                response.await()
            } else {
                listOf(stubTrack("fresh"))
            }
        }

        val stale = async { queues.queueTracks("rock") }
        started.await()
        queues.clear()
        response.complete(listOf(stubTrack("stale")))
        assertEquals(listOf("stale"), stale.await().map { it.id })

        assertEquals(listOf("fresh"), queues.queueTracks("rock").map { it.id })
    }

    @Test
    fun `replaceAll adopts the given lists without building`() = runTest {
        var builds = 0
        val queues = MixQueues<String>(Repository()) { builds++; emptyList() }

        queues.replaceAll(mapOf("rock" to listOf(stubTrack("tr-1"))))
        assertEquals(listOf("tr-1"), queues.queueTracks("rock").map { it.id })
        assertEquals(0, builds)
    }

    @Test
    fun `resume puts the saved track first and adopts the queue`() = runTest {
        val repository = Repository()
        val saved = stubTrack("saved")
        repository.knownTracks[saved.id] = saved
        val queues = MixQueues<String>(repository) { List(3) { stubTrack("tr-$it") } }

        val queue = queues.resumeQueue("rock", saved.id)
        assertEquals("saved", queue.first().id)
        assertEquals(4, queue.size)
        assertEquals(queue, queues.queueTracks("rock"))
    }
}
