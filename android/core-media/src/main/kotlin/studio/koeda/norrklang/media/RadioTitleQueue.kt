package studio.koeda.norrklang.media

import androidx.media3.common.Player
import studio.koeda.norrklang.data.settings.RadioTitle

/**
 * Re-applies [radioTitle] to the stations already in the queue, so a changed
 * setting shows on the playing station at once. Same URIs: ExoPlayer updates
 * the items in place without a reload (as RadioNowPlayingListener).
 */
internal fun Player.applyRadioTitle(radioTitle: RadioTitle) {
    for (index in 0 until mediaItemCount) {
        val item = getMediaItemAt(index)
        if (MediaId.parse(item.mediaId) !is MediaId.RadioStation) continue
        val metadata = MediaItemFactory.withRadioTitle(item.mediaMetadata, radioTitle)
        if (metadata.displayTitle == item.mediaMetadata.displayTitle) continue
        replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
    }
}
