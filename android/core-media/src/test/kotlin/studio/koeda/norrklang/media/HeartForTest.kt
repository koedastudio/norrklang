package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

class HeartForTest {

    private fun station(name: String, title: String = name, artist: String = name) = MediaItem.Builder()
        .setMediaId(MediaId.RadioStation("rs-1").encode())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setStation(name).setArtist(artist).build())
        .build()

    private fun track(id: String) = MediaItem.Builder().setMediaId(MediaId.Track(id).encode()).build()

    private suspend fun heart(item: MediaItem?, saved: Boolean = false, favorite: Boolean = false) =
        heartFor(item, savedSong = { saved }, favoriteTrack = { favorite })

    @Test
    fun `a station that has not named a song gets no heart`() = runTest {
        assertEquals(Heart.None, heart(station("Radio Paradise")))
    }

    @Test
    fun `a station naming a song gets the saved-song heart`() = runTest {
        assertEquals(Heart.RadioSong(saved = true), heart(station("KEXP", title = "Artist - Song"), saved = true))
        assertEquals(Heart.RadioSong(saved = false), heart(station("KEXP", artist = "Artist - Song")))
    }

    @Test
    fun `a track gets the favorite heart for its own id`() = runTest {
        val heart = heartFor(
            track("tr-1"),
            savedSong = { fail("not a station") },
            favoriteTrack = { it == "tr-1" },
        )
        assertEquals(Heart.Track(favorite = true), heart)
    }

    @Test
    fun `nothing playing keeps an outline track heart`() = runTest {
        assertEquals(Heart.Track(favorite = false), heart(null))
    }
}
