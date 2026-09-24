package studio.koeda.norrklang.data.radio

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import studio.koeda.norrklang.data.diagnostics.FragmentPayload

class SavedSongsExportTest {

    private fun song(i: Int) = SavedRadioSong(
        savedAtMs = 1_758_300_000_000L + i * 60_000L,
        stationName = "Station number $i with a longish name",
        title = "Some Artist $i - A Fairly Long Song Title Number $i (Radio Edit)",
    )

    @Test
    fun `empty list has no export`() {
        assertNull(SavedSongsExport.build(emptyList()))
    }

    @Test
    fun `a short list round-trips through the fragment`() {
        val songs = listOf(song(1), song(2))
        val export = SavedSongsExport.build(songs)!!
        assertEquals(2, export.count)
        assertTrue(export.url.startsWith(SavedSongsExport.SONGS_URL + "#1."))
        assertEquals(SavedSongsExport.compose(songs), decode(export.url))
    }

    @Test
    fun `long lists are trimmed from the oldest end until the URL fits`() {
        val songs = (1..300).map(::song)
        val export = SavedSongsExport.build(songs)!!
        assertTrue(export.url.length <= FragmentPayload.MAX_URL_LENGTH, "url ${export.url.length}")
        assertTrue(export.count in 1 until songs.size)
        assertEquals(SavedSongsExport.compose(songs.take(export.count)), decode(export.url))
    }

    @Test
    fun `lines carry UTC timestamps, station and raw title`() {
        val line = SavedSongsExport.compose(listOf(SavedRadioSong(0, "Radio X", "A - B")))
        assertEquals("1970-01-01T00:00Z\tRadio X\tA - B", line)
    }

    private fun decode(url: String): String {
        val payload = url.substringAfter("#1.")
        val bytes = Base64.getUrlDecoder().decode(payload)
        val inflater = Inflater(/* nowrap = */ true).apply { setInput(bytes) }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!inflater.finished()) {
            out.write(buffer, 0, inflater.inflate(buffer))
        }
        inflater.end()
        return out.toString(Charsets.UTF_8.name())
    }
}
