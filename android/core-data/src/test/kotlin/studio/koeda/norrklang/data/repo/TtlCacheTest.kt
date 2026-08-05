package studio.koeda.norrklang.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

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
}
