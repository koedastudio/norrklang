package studio.koeda.norrklang.data.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackSubtitleTest {

    @Test
    fun `artist choice composes nothing`() {
        assertNull(TrackSubtitle.ARTIST.line("Joe Jackson", "Night And Day"))
    }

    @Test
    fun `combined choices put artist and album in their order`() {
        assertEquals(
            "Joe Jackson · Night And Day",
            TrackSubtitle.ARTIST_ALBUM.line("Joe Jackson", "Night And Day"),
        )
        assertEquals(
            "Night And Day · Joe Jackson",
            TrackSubtitle.ALBUM_ARTIST.line("Joe Jackson", "Night And Day"),
        )
    }

    @Test
    fun `missing album falls back, missing artist leaves the album`() {
        assertNull(TrackSubtitle.ARTIST_ALBUM.line("Joe Jackson", null))
        assertNull(TrackSubtitle.ALBUM_ARTIST.line("Joe Jackson", " "))
        assertEquals("Night And Day", TrackSubtitle.ARTIST_ALBUM.line(null, "Night And Day"))
    }

    @Test
    fun `storage values round-trip and unknown ones are rejected`() {
        TrackSubtitle.entries.forEach {
            assertEquals(it, TrackSubtitle.fromStorageValue(it.storageValue))
        }
        assertNull(TrackSubtitle.fromStorageValue("nope"))
    }
}
