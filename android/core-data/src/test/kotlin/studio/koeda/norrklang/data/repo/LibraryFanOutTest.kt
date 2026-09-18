package studio.koeda.norrklang.data.repo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LibraryFanOutTest {

    @Test
    fun `fanOut keeps id order and pairs results`() = runTest {
        val result = fanOut(listOf("b", "a")) { id -> listOf("$id-1", "$id-2") }
        assertEquals(listOf("b" to listOf("b-1", "b-2"), "a" to listOf("a-1", "a-2")), result)
    }

    @Test
    fun `mergeSorted returns a single list untouched`() {
        val only = listOf("zeta", "alpha")
        assertSame(only, mergeSorted(listOf(only), String.CASE_INSENSITIVE_ORDER))
    }

    @Test
    fun `mergeSorted merges several lists by comparator`() {
        val merged = mergeSorted(
            listOf(listOf("Abba", "Zed"), listOf("beck", "Yes")),
            String.CASE_INSENSITIVE_ORDER,
        )
        assertEquals(listOf("Abba", "beck", "Yes", "Zed"), merged)
    }

    @Test
    fun `interleave is round-robin with uneven lengths`() {
        assertEquals(
            listOf(1, 10, 2, 20, 3, 30, 40),
            interleave(listOf(listOf(1, 2, 3), listOf(10, 20, 30, 40))),
        )
    }

    @Test
    fun `allocate is proportional and sums to total`() {
        val shares = allocate(50, listOf(300, 100, 100))
        assertEquals(50, shares.sum())
        assertEquals(listOf(30, 10, 10), shares)
    }

    @Test
    fun `allocate gives every non-empty library at least one`() {
        val shares = allocate(10, listOf(1000, 1, 0))
        assertEquals(10, shares.sum())
        assertTrue(shares[1] >= 1)
        assertEquals(0, shares[2])
    }

    @Test
    fun `allocate splits evenly when counts are unknown`() {
        assertEquals(listOf(5, 5), allocate(10, listOf(0, 0)))
    }

    @Test
    fun `allocate with fewer items than libraries never exceeds total`() {
        val shares = allocate(2, listOf(100, 100, 100))
        assertEquals(2, shares.sum())
    }
}
