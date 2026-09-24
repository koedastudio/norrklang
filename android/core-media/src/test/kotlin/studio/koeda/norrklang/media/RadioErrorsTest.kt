package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RadioErrorsTest {

    private fun error(code: Int) = PlaybackException(null, null, code)

    @Test
    fun `station name comes from the station field, then the title`() {
        val byStation = MediaItem.Builder()
            .setMediaId(MediaId.RadioStation("rs-1").encode())
            .setMediaMetadata(MediaMetadata.Builder().setStation("P3").setTitle("Song").build())
            .build()
        val byTitle = MediaItem.Builder()
            .setMediaId(MediaId.RadioStation("rs-1").encode())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("P3").build())
            .build()
        assertEquals("P3", RadioErrors.stationName(byStation))
        assertEquals("P3", RadioErrors.stationName(byTitle))
    }

    @Test
    fun `tracks and empty players are not presented`() {
        val track = MediaItem.Builder()
            .setMediaId(MediaId.Track("tr-1").encode())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Song").build())
            .build()
        assertNull(RadioErrors.stationName(track))
        assertNull(RadioErrors.stationName(null))
    }

    @Test
    fun `error codes map to a station-level explanation`() {
        assertEquals(RadioErrors.Kind.UNREACHABLE, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)))
        assertEquals(RadioErrors.Kind.UNREACHABLE, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)))
        assertEquals(RadioErrors.Kind.UNREACHABLE, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)))
        assertEquals(RadioErrors.Kind.UNAVAILABLE, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)))
        assertEquals(RadioErrors.Kind.UNAVAILABLE, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)))
        assertEquals(RadioErrors.Kind.UNSUPPORTED, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)))
        assertEquals(RadioErrors.Kind.UNSUPPORTED, RadioErrors.kindOf(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)))
    }
}
