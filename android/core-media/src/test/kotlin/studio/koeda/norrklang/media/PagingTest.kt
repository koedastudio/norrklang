package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class PagingTest {

    private val items = (0 until 10).toList()

    @Test
    fun `unpaged request returns everything`() {
        assertEquals(items, Paging.slice(items, page = 0, pageSize = Int.MAX_VALUE))
    }

    @Test
    fun `pages slice in order`() {
        assertEquals(listOf(0, 1, 2), Paging.slice(items, page = 0, pageSize = 3))
        assertEquals(listOf(3, 4, 5), Paging.slice(items, page = 1, pageSize = 3))
        assertEquals(listOf(9), Paging.slice(items, page = 3, pageSize = 3))
    }

    @Test
    fun `page beyond the end is empty`() {
        assertEquals(emptyList(), Paging.slice(items, page = 4, pageSize = 3))
        assertEquals(emptyList(), Paging.slice(items, page = Int.MAX_VALUE, pageSize = 500))
    }

    @Test
    fun `negative page is empty`() {
        assertEquals(emptyList(), Paging.slice(items, page = -1, pageSize = 3))
    }

    @Test
    fun `window fetches one chunk when the request fits`() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val result = Paging.window(offset = 40, size = 20, chunkSize = 500) { offset, size ->
            requests.add(offset to size)
            (offset until offset + size).toList()
        }
        assertEquals((40 until 60).toList(), result)
        assertEquals(listOf(40 to 20), requests)
    }

    @Test
    fun `window spans server pages for large requests`() = runBlocking {
        val requests = mutableListOf<Pair<Int, Int>>()
        val result = Paging.window(offset = 0, size = 1200, chunkSize = 500) { offset, size ->
            requests.add(offset to size)
            (offset until offset + size).toList()
        }
        assertEquals(1200, result.size)
        assertEquals(listOf(0 to 500, 500 to 500, 1000 to 200), requests)
    }

    @Test
    fun `window without size walks to the end of the library`() = runBlocking {
        val library = (0 until 1234).toList()
        val result = Paging.window(offset = 0, size = null, chunkSize = 500) { offset, size ->
            library.drop(offset).take(size)
        }
        assertEquals(library, result)
    }

    @Test
    fun `window stops on a short chunk`() = runBlocking {
        val library = (0 until 300).toList()
        val requests = mutableListOf<Pair<Int, Int>>()
        val result = Paging.window(offset = 0, size = 1000, chunkSize = 500) { offset, size ->
            requests.add(offset to size)
            library.drop(offset).take(size)
        }
        assertEquals(library, result)
        assertEquals(listOf(0 to 500), requests)
    }
}
