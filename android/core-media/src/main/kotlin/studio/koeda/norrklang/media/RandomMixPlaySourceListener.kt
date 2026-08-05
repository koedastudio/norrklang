package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Mirrors "is the random mix the current play source" into [RandomMixSession].
 *
 * [onMediaItemTransition] fires for every queue swap (taps, resumption
 * restore, clears via a null item), so the flag tracks all paths. Callbacks
 * arrive on the main thread; the session field is @Volatile for the browse
 * coroutines that read it.
 */
internal class RandomMixPlaySourceListener(
    private val randomMix: RandomMixSession,
) : Player.Listener {

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        val container =
            (mediaItem?.mediaId?.let(MediaId::parse) as? MediaId.Track)?.container
        randomMix.isCurrentPlaySource = container == MediaId.HomeRandomMix
    }
}
