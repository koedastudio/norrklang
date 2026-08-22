package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException

/**
 * Autoplay: when at most [TRIGGER_REMAINING] items follow the current one in
 * play order, appends a [QueueRadio] continuation so music keeps playing —
 * random tracks when the queue is the random mix, similar tracks (seeded by
 * the newest queue tracks' artist) everywhere else. Appended items carry
 * [MediaId.HomeRandomMix] or [MediaId.SongRadio] so resumption regenerates a
 * matching queue.
 *
 * An empty continuation marks the queue exhausted until a new queue is set,
 * so a server without similarity data costs one request per queue. Errors
 * are swallowed (the trigger geometry caps retries); everything is silent.
 */
internal class QueueRadioListener(
    private val scope: CoroutineScope,
    private val autoplayEnabled: suspend () -> Boolean,
    private val radio: QueueRadio,
    private val player: Player,
    // Injectable: the production defaults touch Bundle/Uri, which the JVM
    // unit tests cannot construct.
    private val seedOf: (MediaItem) -> String? = { item ->
        item.mediaMetadata.extras?.getString(MediaItemFactory.EXTRA_ARTIST_ID)
    },
    private val buildItem: (Track, MediaId.Container) -> MediaItem =
        MediaItemFactory::playableTrack,
) : Player.Listener {

    private var job: Job? = null
    private var exhausted = false

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {
            exhausted = false
        }
        maybeExtend()
    }

    /** Backstop for a fetch that lost the race with a short last track. */
    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_ENDED) maybeExtend()
    }

    private fun maybeExtend() {
        if (exhausted || job?.isActive == true) return
        if (player.mediaItemCount == 0 || player.mediaItemCount >= MAX_QUEUE_ITEMS) return
        if (playOrderRemaining() > TRIGGER_REMAINING) return
        val tailSignature = tailSignature()
        job = scope.launch {
            if (!autoplayEnabled()) return@launch
            val batch = try {
                when (val mode = continuationMode()) {
                    is Mode.Random ->
                        radio.randomContinuation(queueTrackIds())
                            .map { buildItem(it, MediaId.HomeRandomMix) }
                    is Mode.Similar ->
                        radio.similarContinuation(mode.seedArtistId, queueTrackIds())
                            .map { buildItem(it, MediaId.SongRadio(mode.seedArtistId)) }
                    null -> return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: MusicException) {
                // Offline or signed out — stay armed; the trigger geometry
                // caps retries at the queue's few remaining transitions.
                return@launch
            }
            // The user may have swapped the queue while the fetch ran; a
            // stale batch is dropped and the next transition re-triggers.
            if (tailSignature() != tailSignature) return@launch
            if (batch.isEmpty()) {
                exhausted = true
                return@launch
            }
            val firstAppended = player.mediaItemCount
            player.addMediaItems(batch)
            // Appending while ENDED does not restart playback on its own.
            if (player.playbackState == Player.STATE_ENDED && player.playWhenReady) {
                player.seekTo(firstAppended, 0)
            }
        }
    }

    /**
     * How many items follow the current one in play order, capped at
     * [TRIGGER_REMAINING] + 1 — correct under shuffle, and never below the
     * cap while a repeat mode loops the walk, so repeat disables radio.
     */
    private fun playOrderRemaining(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return TRIGGER_REMAINING + 1
        var index = player.currentMediaItemIndex
        var remaining = 0
        while (remaining <= TRIGGER_REMAINING) {
            index = timeline.getNextWindowIndex(
                index,
                player.repeatMode,
                player.shuffleModeEnabled,
            )
            if (index == C.INDEX_UNSET) break
            remaining++
        }
        return remaining
    }

    private sealed interface Mode {
        data object Random : Mode
        data class Similar(val seedArtistId: String) : Mode
    }

    /**
     * Null when no seed is discoverable — a queue of artist-id-less tracks
     * quietly never extends.
     */
    private fun continuationMode(): Mode? {
        val last = player.getMediaItemAt(player.mediaItemCount - 1)
        val container = (MediaId.parse(last.mediaId) as? MediaId.Track)?.container
        if (container == MediaId.HomeRandomMix) return Mode.Random
        // Seed from the frontier: the newest appended (or last browsed) tracks.
        for (i in player.mediaItemCount - 1 downTo maxOf(0, player.mediaItemCount - SEED_SCAN)) {
            seedOf(player.getMediaItemAt(i))?.let { return Mode.Similar(it) }
        }
        return null
    }

    private fun queueTrackIds(): Set<String> =
        buildSet {
            for (i in 0 until player.mediaItemCount) {
                val mediaId = player.getMediaItemAt(i).mediaId
                add((MediaId.parse(mediaId) as? MediaId.Track)?.id ?: mediaId)
            }
        }

    private fun tailSignature(): Pair<Int, String?> =
        player.mediaItemCount to
            player.mediaItemCount.takeIf { it > 0 }
                ?.let { player.getMediaItemAt(it - 1).mediaId }

    companion object {
        /** Extend one full track early so the seam stays gapless. */
        const val TRIGGER_REMAINING = 1

        /** How many frontier items to scan for a seed artist id. */
        const val SEED_SCAN = 5

        /** ~18 hours of music; keeps Media3's O(n) timeline copies cheap. */
        const val MAX_QUEUE_ITEMS = 300
    }
}
