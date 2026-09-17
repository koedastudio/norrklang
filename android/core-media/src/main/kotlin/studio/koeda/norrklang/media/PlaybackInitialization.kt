package studio.koeda.norrklang.media

import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Retryable startup work for one account, owned by that account's coroutine scope. */
@UnstableApi
internal class PlaybackInitialization(
    private val scope: CoroutineScope,
    private val player: Player,
    private val loadQueue: suspend () -> MediaItemsWithStartPosition?,
    private val refreshHome: suspend () -> Unit,
) : Player.Listener {
    private var restoreJob: Job? = null
    private var homeJob: Job? = null
    private var restoreFinished = false
    private var queueRevision = 0L
    private var homeRequested = false
    private var restoreRequested = false

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
            queueRevision++
            restoreFinished = true // A user's queue choice ends eager restoration for this account.
        }
    }

    /** Called at sign-in and when connectivity returns. Concurrent triggers coalesce. */
    fun retry() {
        homeRequested = true
        if (homeJob?.isActive != true) homeJob = scope.launch {
            do {
                homeRequested = false
                refreshHome()
            } while (homeRequested)
        }
        restoreRequested = true
        if (restoreFinished || restoreJob?.isActive == true || player.mediaItemCount != 0) return
        restoreJob = scope.launch {
            do {
                restoreRequested = false
                val revision = queueRevision
                val queue = loadQueue()
                currentCoroutineContext().ensureActive()
                // Even selecting and then clearing a queue beats the old restore.
                if (queueRevision != revision || player.mediaItemCount != 0) return@launch
                if (queue != null) {
                    restoreFinished = true
                    player.setMediaItems(queue.mediaItems, queue.startIndex, queue.startPositionMs)
                    player.prepare()
                    return@launch
                }
            } while (restoreRequested)
        }
    }
}
