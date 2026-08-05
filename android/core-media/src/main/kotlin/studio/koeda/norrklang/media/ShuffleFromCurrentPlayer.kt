package studio.koeda.norrklang.media

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import kotlin.random.Random

/**
 * Makes shuffle mean "shuffle the rest of the queue from here".
 *
 * ExoPlayer's shuffle order is a fixed permutation created when the queue is
 * set; enabling shuffle later continues from wherever the current item sits
 * in it. Near the permutation's end, playback stops after a track or two
 * ("the shuffle button sometimes stops playback"), and items before that
 * spot silently never play.
 *
 * The wrapper installs a fresh permutation with the current item first
 * whenever the trap could be armed: shuffle flipping off→on (all controller
 * paths land in [setShuffleModeEnabled]) and a new queue set while shuffle
 * is on. Every not-yet-played item then plays exactly once.
 */
@UnstableApi
internal class ShuffleFromCurrentPlayer(
    private val exoPlayer: ExoPlayer,
    private val random: Random = Random.Default,
) : ForwardingPlayer(exoPlayer) {

    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) {
        if (shuffleModeEnabled && !exoPlayer.shuffleModeEnabled) reshuffleFromCurrent()
        super.setShuffleModeEnabled(shuffleModeEnabled)
    }

    override fun setMediaItems(mediaItems: List<MediaItem>) {
        super.setMediaItems(mediaItems)
        if (shuffleModeEnabled) reshuffleFromCurrent()
    }

    override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) {
        super.setMediaItems(mediaItems, resetPosition)
        if (shuffleModeEnabled) reshuffleFromCurrent()
    }

    override fun setMediaItems(
        mediaItems: List<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) {
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
        if (shuffleModeEnabled) reshuffleFromCurrent()
    }

    private fun reshuffleFromCurrent() {
        val count = exoPlayer.mediaItemCount
        if (count == 0) return
        exoPlayer.setShuffleOrder(
            DefaultShuffleOrder(
                shuffledIndicesStartingAt(count, exoPlayer.currentMediaItemIndex, random),
                // Seed for the order's own cloneAndInsert, used if items are
                // appended to the queue later.
                random.nextLong(),
            ),
        )
    }
}

/** A random permutation of `0 until size` starting at [firstIndex]. */
internal fun shuffledIndicesStartingAt(size: Int, firstIndex: Int, random: Random): IntArray {
    require(firstIndex in 0 until size) { "firstIndex $firstIndex outside 0 until $size" }
    val rest = (0 until size).filter { it != firstIndex }.shuffled(random)
    return intArrayOf(firstIndex, *rest.toIntArray())
}
