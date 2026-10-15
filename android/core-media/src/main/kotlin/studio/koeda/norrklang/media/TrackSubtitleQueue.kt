package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import studio.koeda.norrklang.data.settings.TrackSubtitle

/**
 * Re-applies [subtitle] to the library tracks already in the queue, so a
 * changed setting shows on the playing track at once. Same URIs: ExoPlayer
 * updates the items in place without a reload (as RadioNowPlayingListener).
 *
 * One `replaceMediaItems` for the whole queue: every replacement is a
 * timeline change the session pushes to each controller as the full queue,
 * so per-item replacements would cost n IPCs of n items on a long playlist.
 * Nothing is replaced when no line changes, as on the first emission at
 * service start with the default.
 */
internal fun Player.applyTrackSubtitle(subtitle: TrackSubtitle) {
    val items = List(mediaItemCount, ::getMediaItemAt)
    val updated = items.withTrackSubtitle(subtitle) ?: return
    replaceMediaItems(0, items.size, updated)
}

/**
 * This queue with [subtitle] applied to its library tracks, or null when no
 * item's line changes. Unchanged items are the same instances.
 */
internal fun List<MediaItem>.withTrackSubtitle(subtitle: TrackSubtitle): List<MediaItem>? {
    var changed = false
    val updated = map { item ->
        if (MediaId.parse(item.mediaId) !is MediaId.Track) return@map item
        val metadata = MediaItemFactory.withSubtitle(item.mediaMetadata, subtitle)
        if (metadata.subtitle == item.mediaMetadata.subtitle) return@map item
        changed = true
        item.buildUpon().setMediaMetadata(metadata).build()
    }
    return if (changed) updated else null
}
