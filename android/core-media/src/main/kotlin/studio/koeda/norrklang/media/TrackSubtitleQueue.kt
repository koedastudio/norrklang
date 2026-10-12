package studio.koeda.norrklang.media

import androidx.media3.common.Player
import studio.koeda.norrklang.data.settings.TrackSubtitle

/**
 * Re-applies [subtitle] to the library tracks already in the queue, so a
 * changed setting shows on the playing track at once. Same URIs: ExoPlayer
 * updates the items in place without a reload (as RadioNowPlayingListener).
 */
internal fun Player.applyTrackSubtitle(subtitle: TrackSubtitle) {
    for (index in 0 until mediaItemCount) {
        val item = getMediaItemAt(index)
        if (MediaId.parse(item.mediaId) !is MediaId.Track) continue
        val metadata = MediaItemFactory.withSubtitle(item.mediaMetadata, subtitle)
        if (metadata.subtitle == item.mediaMetadata.subtitle) continue
        replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
    }
}
