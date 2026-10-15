package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import studio.koeda.norrklang.data.settings.TrackSubtitle

/** [FakePlayer] with a queue, recording each `replaceMediaItems` call. */
private class SubtitlePlayer(val items: List<MediaItem>) : FakePlayer() {

    val replacements = mutableListOf<Triple<Int, Int, List<MediaItem>>>()

    override fun getMediaItemCount() = items.size
    override fun getMediaItemAt(index: Int): MediaItem = items[index]
    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) {
        replacements += Triple(fromIndex, toIndex, mediaItems.toList())
    }
}

class TrackSubtitleQueueTest {

    private fun track(id: String, artist: String?, album: String?): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.Track(id, MediaId.Album("al-1")).encode())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle("Song $id")
                    .setArtist(artist)
                    .setSubtitle(artist)
                    .setAlbumTitle(album)
                    .build(),
            )
            .build()

    private fun station(id: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(MediaId.RadioStation(id).encode())
            .setMediaMetadata(MediaMetadata.Builder().setTitle("Station $id").build())
            .build()

    private val queue = listOf(
        track("tr-1", artist = "Joe Jackson", album = "Night And Day"),
        station("st-1"),
        track("tr-2", artist = "Joe Jackson", album = null),
    )

    @Test
    fun `the default on a fresh queue replaces nothing`() {
        val player = SubtitlePlayer(queue)
        player.applyTrackSubtitle(TrackSubtitle.ARTIST)
        assertTrue(player.replacements.isEmpty())
    }

    @Test
    fun `an empty queue replaces nothing`() {
        val player = SubtitlePlayer(emptyList())
        player.applyTrackSubtitle(TrackSubtitle.ARTIST_ALBUM)
        assertTrue(player.replacements.isEmpty())
    }

    @Test
    fun `a changed line replaces the whole queue in one call`() {
        val player = SubtitlePlayer(queue)
        player.applyTrackSubtitle(TrackSubtitle.ARTIST_ALBUM)

        assertEquals(1, player.replacements.size)
        val (from, to, items) = player.replacements.single()
        assertEquals(0, from)
        assertEquals(queue.size, to)
        assertEquals(queue.size, items.size)
        assertEquals("Joe Jackson · Night And Day", items[0].mediaMetadata.subtitle)
    }

    @Test
    fun `items whose line does not change are kept as they are`() {
        val updated = queue.withTrackSubtitle(TrackSubtitle.ARTIST_ALBUM)!!
        assertSame(queue[1], updated[1]) // not a library track
        assertSame(queue[2], updated[2]) // no album to compose with
    }
}
