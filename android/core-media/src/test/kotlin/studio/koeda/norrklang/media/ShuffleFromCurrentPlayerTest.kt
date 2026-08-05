package studio.koeda.norrklang.media

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShuffleFromCurrentPlayerTest {

    @Test
    fun `current item plays first`() {
        repeat(20) { seed ->
            val order = shuffledIndicesStartingAt(10, firstIndex = 7, Random(seed))
            assertEquals(7, order.first())
        }
    }

    @Test
    fun `every item plays exactly once`() {
        repeat(20) { seed ->
            val order = shuffledIndicesStartingAt(10, firstIndex = 3, Random(seed))
            assertContentEquals((0 until 10).toList(), order.sorted())
        }
    }

    @Test
    fun `single item queue is the trivial order`() {
        assertContentEquals(intArrayOf(0), shuffledIndicesStartingAt(1, 0, Random(1)))
    }

    @Test
    fun `remaining items are actually permuted`() {
        // With 9 non-first items, 100 seeds producing the identical tail
        // would mean the random source isn't being applied.
        val tails = (0 until 100).map { seed ->
            shuffledIndicesStartingAt(10, firstIndex = 0, Random(seed)).drop(1)
        }
        assertEquals(true, tails.distinct().size > 1)
    }

    @Test
    fun `out of range first index is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            shuffledIndicesStartingAt(5, firstIndex = 5, Random(1))
        }
        assertFailsWith<IllegalArgumentException> {
            shuffledIndicesStartingAt(5, firstIndex = -1, Random(1))
        }
    }
}
