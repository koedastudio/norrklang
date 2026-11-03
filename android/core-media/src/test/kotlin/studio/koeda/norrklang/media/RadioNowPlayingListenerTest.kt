package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class RadioNowPlayingListenerTest {

    private class QueuePlayer(vararg items: MediaItem) : FakePlayer() {
        val items = items.toMutableList()
        var index = 0

        override fun getCurrentMediaItem(): MediaItem? = items.getOrNull(index)
        override fun getCurrentMediaItemIndex() = index
        override fun getMediaItemCount() = items.size
        override fun getMediaItemAt(index: Int): MediaItem = items[index]
        override fun replaceMediaItem(index: Int, mediaItem: MediaItem) {
            items[index] = mediaItem
        }

        val titles get() = items.map { it.mediaMetadata.title.toString() }

        /** Each item as the car draws it: "title / artist". */
        val lines get() = items.map { "${it.mediaMetadata.title} / ${it.mediaMetadata.artist}" }
    }

    /** As BrowseTree.playableStation hands it to the player: the name on both lines. */
    private fun station(id: String, name: String) = MediaItem.Builder()
        .setMediaId(MediaId.RadioStation(id).encode())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(name).setStation(name).setArtist(name).build())
        .build()

    private fun track(id: String, title: String) = MediaItem.Builder()
        .setMediaId(MediaId.Track(id).encode())
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    private fun icy(title: String) = Metadata(IcyInfo(ByteArray(0), title, null))

    private fun id3(id: String, value: String) = TextInformationFrame(id, null, listOf(value))

    private fun position(player: QueuePlayer, index: Int, item: MediaItem? = player.items[index]) =
        Player.PositionInfo(null, index, item, null, index, 0L, 0L, -1, -1)

    /** Moves [player] to [to] and reports the discontinuity the real player would. */
    private fun RadioNowPlayingListener.skip(player: QueuePlayer, to: Int) {
        val old = position(player, player.index)
        player.index = to
        onPositionDiscontinuity(old, position(player, to), Player.DISCONTINUITY_REASON_SEEK)
    }

    @Test
    fun `the stream's song replaces the current station's title`() {
        val player = QueuePlayer(station("rs-1", "KEXP"), station("rs-2", "FIP")).apply { index = 1 }
        RadioNowPlayingListener(player).onMetadata(icy(" Air - La femme d'argent "))

        assertEquals(listOf("KEXP / KEXP", "Air - La femme d'argent / FIP"), player.lines)
    }

    @Test
    fun `station first puts the song on the artist line instead`() {
        val player = QueuePlayer(station("rs-1", "KEXP"), station("rs-2", "FIP")).apply { index = 1 }
        val listener = RadioNowPlayingListener(player).apply { stationFirst = true }
        listener.onMetadata(icy("Air - La femme d'argent"))

        assertEquals(listOf("KEXP / KEXP", "FIP / Air - La femme d'argent"), player.lines)
    }

    @Test
    fun `flipping the layout re-lays out the song already playing`() {
        val player = QueuePlayer(station("rs-1", "KEXP"))
        val listener = RadioNowPlayingListener(player)
        listener.onMetadata(icy("Song on KEXP"))

        listener.stationFirst = true
        assertEquals(listOf("KEXP / Song on KEXP"), player.lines)

        listener.stationFirst = false
        assertEquals(listOf("Song on KEXP / KEXP"), player.lines)
    }

    @Test
    fun `the same song again is not re-issued`() {
        val player = QueuePlayer(station("rs-1", "KEXP"))
        val listener = RadioNowPlayingListener(player)
        listener.onMetadata(icy("Song on KEXP"))
        val issued = player.items[0]
        listener.onMetadata(icy("Song on KEXP"))

        assertSame(issued, player.items[0])
    }

    @Test
    fun `the heart reads the song from whichever line holds it`() {
        val player = QueuePlayer(station("rs-1", "KEXP"))
        val listener = RadioNowPlayingListener(player)
        assertNull(radioSong(player.items[0]))

        listener.onMetadata(icy("Song on KEXP"))
        assertEquals(RadioSong("KEXP", "Song on KEXP"), radioSong(player.items[0]))

        listener.stationFirst = true
        assertEquals(RadioSong("KEXP", "Song on KEXP"), radioSong(player.items[0]))
    }

    @Test
    fun `a track's title is never replaced`() {
        val player = QueuePlayer(track("tr-1", "Song"))
        RadioNowPlayingListener(player).onMetadata(icy("Not for this item"))

        assertEquals(listOf("Song"), player.titles)
    }

    @Test
    fun `a station left behind gets its own name back`() {
        val player = QueuePlayer(station("rs-1", "KEXP"), station("rs-2", "FIP"))
        val listener = RadioNowPlayingListener(player)
        listener.onMetadata(icy("Song on KEXP"))
        listener.skip(player, to = 1)

        assertEquals(listOf("KEXP / KEXP", "FIP / FIP"), player.lines)
    }

    @Test
    fun `a station left behind with station first gets its own name back too`() {
        val player = QueuePlayer(station("rs-1", "KEXP"), station("rs-2", "FIP"))
        val listener = RadioNowPlayingListener(player).apply { stationFirst = true }
        listener.onMetadata(icy("Song on KEXP"))
        listener.skip(player, to = 1)

        assertEquals(listOf("KEXP / KEXP", "FIP / FIP"), player.lines)
    }

    @Test
    fun `a replaced queue is not renamed by the old queue's position`() {
        val player = QueuePlayer(station("rs-1", "KEXP"), station("rs-2", "FIP"))
        val listener = RadioNowPlayingListener(player)
        listener.onMetadata(icy("Song on KEXP"))
        val old = position(player, 0)

        // Shorter queue: the old index is gone.
        player.items.clear()
        listener.onPositionDiscontinuity(old, position(player, 0, item = null), Player.DISCONTINUITY_REASON_REMOVE)

        // Same index, unrelated item.
        player.items += listOf(track("tr-1", "Song"), track("tr-2", "Other"))
        player.index = 1
        listener.onPositionDiscontinuity(old, position(player, 1), Player.DISCONTINUITY_REASON_REMOVE)

        assertEquals(listOf("Song", "Other"), player.titles)
    }

    @Test
    fun `an HLS stream's ID3 artist and title name the song`() {
        val player = QueuePlayer(station("rs-1", "KEXP"))
        RadioNowPlayingListener(player).onMetadata(Metadata(id3("TIT2", " La femme d'argent "), id3("TPE1", "Air")))

        assertEquals(listOf("Air - La femme d'argent / KEXP"), player.lines)
    }

    @Test
    fun `an ID3 title without an artist stands alone`() {
        assertEquals("Jingle", streamSong(Metadata(id3("TIT2", "Jingle"), id3("TPE1", " "))))
    }

    @Test
    fun `ID3 without a title leaves the station as is`() {
        val player = QueuePlayer(station("rs-1", "KEXP"))
        RadioNowPlayingListener(player).onMetadata(Metadata(id3("TPE1", "Air"), id3("TALB", "Moon Safari")))

        assertEquals(listOf("KEXP / KEXP"), player.lines)
    }

    @Test
    fun `ICY wins over ID3 when a stream sends both`() {
        val metadata = Metadata(id3("TIT2", "From ID3"), IcyInfo(ByteArray(0), "From ICY", null))

        assertEquals("From ICY", streamSong(metadata))
    }
}
