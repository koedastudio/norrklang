package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import studio.koeda.norrklang.data.settings.TrackSubtitle

class RealArtistTest {

    @Test
    fun `first pass takes the artist field as built`() {
        assertEquals("Joe Jackson", MediaItemFactory.realArtist(null, "Joe Jackson"))
        assertNull(MediaItemFactory.realArtist(null, null))
    }

    @Test
    fun `a recorded artist wins over a composed artist field`() {
        assertEquals(
            "Joe Jackson",
            MediaItemFactory.realArtist("Joe Jackson", "Joe Jackson · Night And Day"),
        )
    }

    @Test
    fun `a recorded missing artist never falls back to the composed field`() {
        // First pass: no artist, so the album alone became the artist field.
        val field = TrackSubtitle.ARTIST_ALBUM.line(null, "Night And Day")
        assertEquals("Night And Day", field)

        // Second pass reads the recorded "" and composes the album alone again.
        val artist = MediaItemFactory.realArtist("", field)
        assertNull(artist)
        assertEquals("Night And Day", TrackSubtitle.ALBUM_ARTIST.line(artist, "Night And Day"))
        assertNull(TrackSubtitle.ARTIST.line(artist, "Night And Day"))
    }
}
