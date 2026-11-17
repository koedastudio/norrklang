package studio.koeda.norrklang.data.repo

import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Rule
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapshotStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val strings = ListSerializer(String.serializer())

    private fun TestScope.store() =
        SnapshotStore(File(tmp.root, "browse"), backgroundScope, EmptyCoroutineContext)

    private fun files(fingerprint: String): List<File> =
        File(tmp.root, "browse/$fingerprint").listFiles().orEmpty().toList()

    @Test
    fun `a written value reads back, also from a new instance`() = runTest {
        store().write("fp", "playlists", "playlists", strings, listOf("a", "b"))
        runCurrent()

        assertEquals(listOf("a", "b"), store().read("fp", "playlists", "playlists", strings))
        assertNull(store().read("fp", "playlists", "other-key", strings))
    }

    @Test
    fun `a truncated file reads as a miss and is deleted`() = runTest {
        val store = store()
        store.write("fp", "playlists", "playlists", strings, listOf("a"))
        runCurrent()
        val file = files("fp").single()
        file.writeText(file.readText().dropLast(4))

        assertNull(store.read("fp", "playlists", "playlists", strings))
        assertFalse(file.exists())
    }

    @Test
    fun `another schema version reads as a miss`() = runTest {
        val store = store()
        store.write("fp", "playlists", "playlists", strings, listOf("a"))
        runCurrent()
        val file = files("fp").single()
        file.writeText(file.readText().replace("\"v\":1", "\"v\":2"))

        assertNull(store.read("fp", "playlists", "playlists", strings))
    }

    @Test
    fun `using another account deletes the previous account's folder`() = runTest {
        val store = store()
        store.write("one", "playlists", "playlists", strings, listOf("a"))
        runCurrent()

        store.write("two", "playlists", "playlists", strings, listOf("b"))
        runCurrent()

        assertFalse(File(tmp.root, "browse/one").exists())
        assertEquals(listOf("b"), store.read("two", "playlists", "playlists", strings))
    }

    @Test
    fun `drop removes every key of the named lists only`() = runTest {
        val store = store()
        store.write("fp", "favorite-albums", "all/favorites/50", strings, listOf("a"))
        store.write("fp", "favorite-albums", "all/favorites/500", strings, listOf("b"))
        store.write("fp", "favorite-artists", "all/favorite-artists", strings, listOf("c"))
        runCurrent()

        store.drop("fp", setOf("favorite-albums"))

        assertNull(store.read("fp", "favorite-albums", "all/favorites/50", strings))
        assertNull(store.read("fp", "favorite-albums", "all/favorites/500", strings))
        assertEquals(listOf("c"), store.read("fp", "favorite-artists", "all/favorite-artists", strings))
    }

    @Test
    fun `the oldest files go once the account holds too many`() = runTest {
        val store = store()
        repeat(60) { i ->
            store.write("fp", "playlists", "key-$i", strings, listOf("v$i"))
            runCurrent()
        }

        assertTrue(files("fp").size <= 48)
        assertEquals(listOf("v59"), store.read("fp", "playlists", "key-59", strings))
    }

    @Test
    fun `an oversized value is not stored`() = runTest {
        val store = store()
        store.write("fp", "artists", "artists", strings, listOf("x".repeat(5 * 1024 * 1024)))
        runCurrent()

        assertTrue(files("fp").isEmpty())
    }

    @Test
    fun `a write queued before a purge is skipped`() = runTest {
        val store = store()
        store.write("fp", "playlists", "playlists", strings, listOf("a"))
        store.purge()
        runCurrent()

        assertFalse(File(tmp.root, "browse").exists())
    }
}
