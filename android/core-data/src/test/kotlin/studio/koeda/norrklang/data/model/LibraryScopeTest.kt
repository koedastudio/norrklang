package studio.koeda.norrklang.data.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryScopeTest {

    private val libs = listOf(
        MusicLibrary("1", "Music"),
        MusicLibrary("2", "Kids"),
        MusicLibrary("3", "Multichannel"),
    )

    @Test
    fun `no exclusions selects everything with the all key`() {
        val scope = LibraryScope.resolve(libs, emptySet())
        assertTrue(scope.isAll)
        assertEquals(listOf("1", "2", "3"), scope.selectedIds)
        assertEquals("all", scope.key)
    }

    @Test
    fun `subset key is sorted and independent of set order`() {
        val a = LibraryScope.resolve(libs, setOf("2"))
        assertFalse(a.isAll)
        assertEquals(listOf("1", "3"), a.selectedIds)
        assertEquals("1+3", a.key)
        assertEquals(a.key, LibraryScope.resolve(libs.reversed(), setOf("2")).key)
    }

    @Test
    fun `stale excluded ids are ignored`() {
        val scope = LibraryScope.resolve(libs, setOf("gone"))
        assertTrue(scope.isAll)
    }

    @Test
    fun `excluding everything falls back to all`() {
        val scope = LibraryScope.resolve(libs, setOf("1", "2", "3"))
        assertTrue(scope.isAll)
        assertEquals(3, scope.selected.size)
    }

    @Test
    fun `an empty library list is all`() {
        assertTrue(LibraryScope.resolve(emptyList(), setOf("1")).isAll)
    }

    @Test
    fun `exclusionKey is stable across set order`() {
        assertEquals("all", LibraryScope.exclusionKey(emptySet()))
        assertEquals("2+7", LibraryScope.exclusionKey(setOf("7", "2")))
        assertEquals(LibraryScope.exclusionKey(setOf("2", "7")), LibraryScope.exclusionKey(setOf("7", "2")))
    }
}
