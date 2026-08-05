package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import studio.koeda.norrklang.data.settings.ServerSettingsRepository

/**
 * Persists "what was playing" for `onPlaybackResumption`; the full queue is
 * rebuilt from the media id's container context.
 *
 * Saves on transitions and pauses, every [SAVE_INTERVAL_MS] while playing
 * (a car can cut power without ever pausing), and once more from [saveNow]
 * at service shutdown.
 */
internal class ResumptionPersister(
    private val scope: CoroutineScope,
    private val settings: ServerSettingsRepository,
    private val player: Player,
) : Player.Listener {

    private var periodicSave: Job? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = save()

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        periodicSave?.cancel()
        periodicSave = null
        if (isPlaying) {
            periodicSave = scope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    save()
                }
            }
        } else {
            save()
        }
    }

    /** For service shutdown paths (onDestroy/onTaskRemoved). */
    fun saveNow() = save()

    private fun save() {
        val mediaId = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        // UNDISPATCHED is load-bearing: it runs the body synchronously into
        // withContext(NonCancellable) before saveNow() returns, so the
        // shutdown-path save is already shielded when the service scope's
        // cancel() lands — a dispatched launch would die queued, uninvoked.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                runCatching { settings.saveResumptionState(mediaId, position) }
            }
        }
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
