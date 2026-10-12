package studio.koeda.norrklang.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioTitleTest {

    @Test
    fun `song on top keeps the station on line two`() {
        assertNull(RadioTitle.SONG.displayTitle("Radio Veronica"))
        assertEquals("Radio Veronica", RadioTitle.SONG.lineTwo("Radio Veronica", "Toto - Africa"))
    }

    @Test
    fun `station on top moves the song to line two`() {
        assertEquals("Radio Veronica", RadioTitle.STATION.displayTitle("Radio Veronica"))
        assertEquals("Toto - Africa", RadioTitle.STATION.lineTwo("Radio Veronica", "Toto - Africa"))
    }

    @Test
    fun `station stands in until the stream names a song`() {
        assertEquals("Radio Veronica", RadioTitle.STATION.lineTwo("Radio Veronica", null))
    }

    @Test
    fun `storage values round-trip and unknown ones are rejected`() {
        RadioTitle.entries.forEach {
            assertEquals(it, RadioTitle.fromStorageValue(it.storageValue))
        }
        assertNull(RadioTitle.fromStorageValue("nope"))
    }
}
