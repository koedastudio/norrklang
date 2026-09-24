package studio.koeda.norrklang.data.radio

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class SavedRadioSongsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = SavedRadioSongs(File(tmp.root, "radio/saved-songs.tsv"))

    @Test
    fun `toggle saves, then removes, and reports the new state`() = runTest {
        val store = store()
        assertTrue(store.toggle("Radio X", "Artist - Song", nowMs = 1_000))
        assertTrue(store.isSaved("Radio X", "Artist - Song"))
        assertEquals(
            listOf(SavedRadioSong(1_000, "Radio X", "Artist - Song")),
            store.songs.first(),
        )

        assertFalse(store.toggle("Radio X", "artist - song", nowMs = 2_000))
        assertFalse(store.isSaved("Radio X", "Artist - Song"))
        assertEquals(emptyList(), store.songs.first())
    }

    @Test
    fun `the same title on another station is a separate song`() = runTest {
        val store = store()
        store.toggle("Radio X", "Artist - Song", nowMs = 1_000)
        store.toggle("Radio Y", "Artist - Song", nowMs = 2_000)
        assertEquals(2, store.songs.first().size)
        assertTrue(store.isSaved("Radio Y", "Artist - Song"))
    }

    @Test
    fun `newest first, persisted across instances, and capped`() = runTest {
        val first = store()
        repeat(SavedRadioSongs.MAX_SONGS + 5) { i ->
            first.toggle("Radio X", "Song $i", nowMs = i.toLong())
        }
        val songs = store().songs.first()
        assertEquals(SavedRadioSongs.MAX_SONGS, songs.size)
        assertEquals("Song ${SavedRadioSongs.MAX_SONGS + 4}", songs.first().title)
        assertEquals("Song 5", songs.last().title)
    }

    @Test
    fun `remove drops one entry and clear drops all`() = runTest {
        val store = store()
        store.toggle("Radio X", "A", nowMs = 1)
        store.toggle("Radio X", "B", nowMs = 2)
        store.remove(SavedRadioSong(1, "Radio X", "A"))
        assertEquals(listOf("B"), store.songs.first().map { it.title })
        store.clear()
        assertEquals(emptyList(), store().songs.first())
    }

    @Test
    fun `tabs and line breaks in metadata can't corrupt the file`() = runTest {
        val store = store()
        store.toggle("Radio\tX", "Artist\n- Song  ", nowMs = 1)
        val song = store().songs.first().single()
        assertEquals("Radio X", song.stationName)
        assertEquals("Artist - Song", song.title)
    }

    @Test
    fun `a damaged line is skipped, the rest still load`() = runTest {
        val file = File(tmp.root, "radio/saved-songs.tsv").apply { parentFile.mkdirs() }
        file.writeText("not-a-number\tRadio\tSong\n7\tRadio\tKept\n8\tRadio\t\n")
        assertEquals(listOf("Kept"), SavedRadioSongs(file).songs.first().map { it.title })
    }
}
