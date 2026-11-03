package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame

/**
 * Shows the song a station is playing: ExoPlayer decodes the stream's ICY
 * "StreamTitle" into [IcyInfo] (or, for HLS, the segments' ID3 title and
 * artist frames), but the item's own metadata always wins in
 * the player's merged metadata — so the current item is re-issued with the
 * song. Same URI, so ExoPlayer updates it in place without a reload.
 *
 * The song goes on the title line and the station name on the artist line,
 * or the other way round with [stationFirst]: car hosts draw the title large
 * and truncated and the artist line small and in full, so a long
 * "artist - title" only fits beneath the station name.
 *
 * A station left for another queue item gets its own name back on both
 * lines, so the queue lists stations rather than the songs they last played.
 */
@OptIn(UnstableApi::class) // Metadata, IcyInfo, TextInformationFrame
internal class RadioNowPlayingListener(private val player: Player) : Player.Listener {

    /** Setting it re-lays out the station playing right now, not just the next song. */
    var stationFirst: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val item = player.currentMediaItem ?: return
            val song = radioSong(item)?.title ?: return
            relayout(player.currentMediaItemIndex, item, song)
        }

    override fun onMetadata(metadata: Metadata) {
        val item = player.currentMediaItem ?: return
        if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) return
        val song = streamSong(metadata) ?: return
        relayout(player.currentMediaItemIndex, item, song)
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        val index = oldPosition.mediaItemIndex
        if (index == newPosition.mediaItemIndex || index >= player.mediaItemCount) return
        val item = player.getMediaItemAt(index)
        // A replaced queue reuses the index for an unrelated item.
        if (item.mediaId != oldPosition.mediaItem?.mediaId) return
        if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) return
        relayout(index, item, song = null)
    }

    /** Both lines read the station name while no [song] is known. */
    private fun relayout(index: Int, item: MediaItem, song: String?) {
        val current = item.mediaMetadata
        val station = current.station?.toString() ?: return
        val (title, artist) = when {
            song == null -> station to station
            stationFirst -> station to song
            else -> song to station
        }
        if (current.title?.toString() == title && current.artist?.toString() == artist) return
        val metadata = current.buildUpon().setTitle(title).setArtist(artist).build()
        player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
    }
}

@OptIn(UnstableApi::class)
internal fun streamSong(metadata: Metadata): String? {
    val entries = (0 until metadata.length()).map(metadata::get)
    entries.filterIsInstance<IcyInfo>()
        .firstNotNullOfOrNull { it.title.nonBlank() }
        ?.let { return it }
    val frames = entries.filterIsInstance<TextInformationFrame>()
    fun frame(id: String) = frames.firstOrNull { it.id == id }?.values?.firstOrNull().nonBlank()
    val title = frame("TIT2") ?: return null
    return frame("TPE1")?.let { "$it - $title" } ?: title
}

private fun String?.nonBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
