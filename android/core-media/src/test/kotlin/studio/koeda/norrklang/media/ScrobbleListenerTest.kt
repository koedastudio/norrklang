package studio.koeda.norrklang.media

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings

class ScrobbleListenerTest {

    private val settings = ScrobbleSettings(
        enabled = true,
        excludedArtistIds = setOf("ar-bad"),
        excludedPlaylistIds = setOf("pl-sleep"),
    )

    private fun track(container: MediaId.Container? = null) = MediaId.Track("tr-1", container)

    @Test
    fun `disabled blocks every play`() {
        val off = settings.copy(enabled = false)
        assertFalse(off.allowsScrobble(track(), artistId = null))
        assertFalse(off.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-ok"))
    }

    @Test
    fun `excluded artist blocks plays in any context`() {
        assertFalse(settings.allowsScrobble(track(), artistId = "ar-bad"))
        assertFalse(settings.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-bad"))
        assertFalse(settings.allowsScrobble(track(MediaId.Playlist("pl-ok")), artistId = "ar-bad"))
    }

    @Test
    fun `excluded playlist blocks only plays started from that playlist`() {
        assertFalse(settings.allowsScrobble(track(MediaId.Playlist("pl-sleep")), artistId = "ar-ok"))
        // The same track from its album, another playlist, or with no context
        // still scrobbles — playlist exclusion is contextual.
        assertTrue(settings.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-ok"))
        assertTrue(settings.allowsScrobble(track(MediaId.Playlist("pl-ok")), artistId = "ar-ok"))
        assertTrue(settings.allowsScrobble(track(), artistId = "ar-ok"))
    }

    @Test
    fun `unknown artist id is not treated as excluded`() {
        assertTrue(settings.allowsScrobble(track(), artistId = null))
        assertTrue(settings.allowsScrobble(track(MediaId.HomeRandomMix), artistId = null))
    }

    @Test
    fun `defaults allow everything`() {
        val defaults = ScrobbleSettings(enabled = true, emptySet(), emptySet())
        assertTrue(defaults.allowsScrobble(track(MediaId.Playlist("pl-sleep")), artistId = "ar-bad"))
    }
}
