package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Feeds the home tab's "Radio" section: a station counts as listened to once
 * it has played [LISTEN_THRESHOLD_MS] without a pause, once per visit (a
 * transition to the same media id, e.g. an ICY title update, is no new visit).
 * The server keeps no radio history, so the count is stored locally.
 * The construction scope must be main-thread ([Player] reads).
 */
internal class RadioPlayCounter(
    private val scope: CoroutineScope,
    private val player: Player,
    private val record: suspend (stationId: String) -> Unit,
    /** Runs after a count lands, so the host can re-query the home tab. */
    private val onRecorded: () -> Unit = {},
) : Player.Listener {

    private var job: Job? = null
    private var counted = false
    private var visitMediaId: String? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (mediaItem?.mediaId == visitMediaId) return
        visitMediaId = mediaItem?.mediaId
        disarm()
        counted = false
        if (player.isPlaying) arm()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) arm() else disarm()
    }

    private fun arm() {
        if (counted || job?.isActive == true) return
        val stationId = (player.currentMediaItem?.mediaId?.let(MediaId::parse) as? MediaId.RadioStation)
            ?.id ?: return
        job = scope.launch {
            delay(LISTEN_THRESHOLD_MS)
            counted = true
            try {
                record(stationId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best effort: a failed write is a missed count, nothing more.
                return@launch
            }
            onRecorded()
        }
    }

    private fun disarm() {
        job?.cancel()
        job = null
    }

    companion object {
        /** Continuous playback before a station counts as listened to. */
        const val LISTEN_THRESHOLD_MS = 30_000L
    }
}
