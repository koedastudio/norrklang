package studio.koeda.norrklang.data.repo

import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val strings = ListSerializer(String.serializer())
    private var now = 0L

    /** A new "process": empty memory over the same disk folder. */
    private fun TestScope.process(): SnapshotCache = SnapshotCache(
        TtlCache(ttlMillis = 1000, clock = { now }),
        SnapshotStore(File(tmp.root, "browse"), backgroundScope, EmptyCoroutineContext),
        backgroundScope,
        clock = { now },
    )

    private suspend fun SnapshotCache.playlists(loader: suspend () -> List<String>) =
        get("fp", BrowseList.PLAYLISTS, "playlists", strings, loader)

    private fun TestScope.changesOf(cache: SnapshotCache): List<BrowseList> {
        val seen = mutableListOf<BrowseList>()
        backgroundScope.launch { cache.changes.collect { seen += it } }
        runCurrent()
        return seen
    }

    @Test
    fun `a new process answers from disk before the loader finishes`() = runTest {
        process().playlists { listOf("old") }
        runCurrent()
        val cache = process()
        val changes = changesOf(cache)
        val server = CompletableDeferred<List<String>>()

        assertEquals(listOf("old"), cache.playlists { server.await() })

        server.complete(listOf("new"))
        runCurrent()
        assertEquals(listOf(BrowseList.PLAYLISTS), changes)
        assertEquals(listOf("new"), cache.playlists { error("fresh in memory") })
        assertEquals(listOf("new"), process().playlists { server.await() })
    }

    @Test
    fun `an unchanged refresh is not announced`() = runTest {
        process().playlists { listOf("same") }
        runCurrent()
        val cache = process()
        val changes = changesOf(cache)

        cache.playlists { listOf("same") }
        runCurrent()

        assertEquals(emptyList(), changes)
    }

    @Test
    fun `concurrent stale reads share one refresh`() = runTest {
        process().playlists { listOf("old") }
        runCurrent()
        val cache = process()
        var loads = 0
        val server = CompletableDeferred<List<String>>()

        repeat(3) { cache.playlists { loads++; server.await() } }
        runCurrent()

        assertEquals(1, loads)
    }

    @Test
    fun `an expired entry is served stale and refreshed`() = runTest {
        val cache = process()
        cache.playlists { listOf("old") }
        now = 5_000

        assertEquals(listOf("old"), cache.playlists { listOf("new") })
        runCurrent()
        assertEquals(listOf("new"), cache.playlists { error("fresh in memory") })
    }

    @Test
    fun `a failed refresh keeps the stale value and waits before retrying`() = runTest {
        process().playlists { listOf("old") }
        runCurrent()
        val cache = process()
        var loads = 0
        val failing: suspend () -> List<String> = { loads++; throw MusicException.NetworkError(java.io.IOException("offline")) }

        assertEquals(listOf("old"), cache.playlists(failing))
        runCurrent()
        assertEquals(listOf("old"), cache.playlists(failing))
        runCurrent()
        assertEquals(1, loads)

        now += 31_000
        cache.playlists(failing)
        runCurrent()
        assertEquals(2, loads)
    }

    @Test
    fun `retryFailed re-runs a failed refresh`() = runTest {
        process().playlists { listOf("old") }
        runCurrent()
        val cache = process()
        val changes = changesOf(cache)
        var online = false
        val loader: suspend () -> List<String> = {
            if (online) listOf("new") else throw MusicException.NetworkError(java.io.IOException("offline"))
        }
        cache.playlists(loader)
        runCurrent()

        online = true
        cache.retryFailed()
        runCurrent()

        assertEquals(listOf(BrowseList.PLAYLISTS), changes)
    }

    @Test
    fun `nothing known loads like a plain cached call`() = runTest {
        val cache = process()

        assertFailsWith<MusicException.NetworkError> {
            cache.playlists { throw MusicException.NetworkError(java.io.IOException("offline")) }
        }
        assertEquals(listOf("live"), cache.playlists { listOf("live") })
    }

    @Test
    fun `a dropped list loads live, not from disk or memory`() = runTest {
        val cache = process()
        cache.playlists { listOf("old") }
        cache.get("fp", BrowseList.ARTISTS, "artists", strings) { listOf("artist") }
        runCurrent()

        cache.drop("fp", setOf(BrowseList.PLAYLISTS))
        // The repository clears the TTL cache alongside; expiry stands in for that here.
        now = 5_000

        assertFailsWith<MusicException.NetworkError> {
            cache.playlists { throw MusicException.NetworkError(java.io.IOException("offline")) }
        }
        runCurrent()
        assertFailsWith<MusicException.NetworkError> {
            process().playlists { throw MusicException.NetworkError(java.io.IOException("offline")) }
        }
        // The other list still answers from memory.
        assertEquals(
            listOf("artist"),
            cache.get("fp", BrowseList.ARTISTS, "artists", strings) { error("offline") },
        )
    }

    @Test
    fun `clear empties memory and disk`() = runTest {
        val cache = process()
        cache.playlists { listOf("old") }
        runCurrent()

        cache.clear()
        runCurrent()

        assertEquals(listOf("live"), process().playlists { listOf("live") })
    }
}
