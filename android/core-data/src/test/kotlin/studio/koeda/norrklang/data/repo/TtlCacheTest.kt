package studio.koeda.norrklang.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TtlCacheTest {

    @Test
    fun `second read within ttl hits the cache`() = runTest {
        var now = 0L
        var loads = 0
        val cache = TtlCache(ttlMillis = 1000, clock = { now })

        repeat(3) { cache.getOrLoad("k") { loads++; "value" } }

        assertEquals(1, loads)
    }

    @Test
    fun `entry expires after ttl`() = runTest {
        var now = 0L
        var loads = 0
        val cache = TtlCache(ttlMillis = 1000, clock = { now })

        cache.getOrLoad("k") { loads++; "v1" }
        now = 1500
        val value = cache.getOrLoad("k") { loads++; "v2" }

        assertEquals(2, loads)
        assertEquals("v2", value)
    }

    @Test
    fun `clear drops all entries`() = runTest {
        var loads = 0
        val cache = TtlCache(ttlMillis = 10_000, clock = { 0L })

        cache.getOrLoad("k") { loads++; "v" }
        cache.clear()
        cache.getOrLoad("k") { loads++; "v" }

        assertEquals(2, loads)
    }

    @Test
    fun `keys are independent`() = runTest {
        val cache = TtlCache(ttlMillis = 10_000, clock = { 0L })
        assertEquals("a", cache.getOrLoad("ka") { "a" })
        assertEquals("b", cache.getOrLoad("kb") { "b" })
    }

    @Test
    fun `a load finishing after clear cannot restore stale favorite state`() = runTest {
        val cache = TtlCache(10_000)
        val response = CompletableDeferred<String>()
        val oldLoad = async { cache.getOrLoad("favorite") { response.await() } }
        runCurrent()
        cache.clear()
        response.complete("old")
        assertEquals("old", oldLoad.await())
        assertEquals("new", cache.getOrLoad("favorite") { "new" })
        assertEquals("new", cache.getOrLoad<String>("favorite") { error("must be cached") })
    }

    @Test
    fun `concurrent misses share one load and later misses can reload`() = runTest {
        var loads = 0
        val response = CompletableDeferred<String>()
        val cache = TtlCache(10_000)
        val readers = List(10) { async { cache.getOrLoad("k") { loads++; response.await() } } }
        runCurrent()
        response.complete("value")
        readers.forEach { assertEquals("value", it.await()) }
        assertEquals(1, loads)
        cache.clear()
        assertEquals("fresh", cache.getOrLoad("k") { "fresh" })
    }

    @Test
    fun `cancelled waiters do not prevent future loads`() = runTest {
        val cache = TtlCache(10_000)
        val response = CompletableDeferred<String>()
        val loading = async { cache.getOrLoad("k") { response.await() } }
        val waiting = async { cache.getOrLoad("k") { "unused" } }
        runCurrent()
        waiting.cancel()
        loading.cancel()
        runCurrent()
        assertEquals("fresh", cache.getOrLoad("k") { "fresh" })
    }
}
