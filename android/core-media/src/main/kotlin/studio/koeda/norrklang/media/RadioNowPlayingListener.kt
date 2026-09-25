package studio.koeda.norrklang.media

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.icy.IcyInfo

/**
 * Shows the song a station is playing: ExoPlayer decodes the stream's ICY
 * "StreamTitle" into [IcyInfo], but the item's own title always wins in
 * the player's merged metadata — so the current item is re-issued with the
 * ICY title. Same URI, so ExoPlayer updates it in place without a reload.
 */
@OptIn(UnstableApi::class) // Metadata, IcyInfo
internal class RadioNowPlayingListener(private val player: Player) : Player.Listener {

    override fun onMetadata(metadata: Metadata) {
        val item = player.currentMediaItem ?: return
        if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) return
        val title = (0 until metadata.length())
            .asSequence()
            .map(metadata::get)
            .filterIsInstance<IcyInfo>()
            .firstNotNullOfOrNull { it.title?.trim()?.takeIf(String::isNotEmpty) }
            ?: return
        if (item.mediaMetadata.title == title) return
        player.replaceMediaItem(player.currentMediaItemIndex, item.withTitle(title))
    }

    private fun MediaItem.withTitle(title: String): MediaItem =
        buildUpon().setMediaMetadata(mediaMetadata.buildUpon().setTitle(title).build()).build()
}
