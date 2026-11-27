package studio.koeda.norrklang.media

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/**
 * Plays a station live again after a pause, instead of from where it was
 * paused.
 *
 * ExoPlayer plays an ICY stream as progressive media: a pause keeps the
 * connection and the sample queue, so play after a pause continues with
 * the audio from the moment of the pause, minutes behind the broadcast,
 * until the buffered data (the 180 s buffer plus the socket's backlog) runs
 * out and the repeat-one reconnect takes over (#26). A station restored
 * paused at service start is in the same position: prepared at boot,
 * played later.
 *
 * So play after a pause of [RESUME_LIVE_AFTER_MS] or more goes live: an
 * HLS station seeks to its live edge, an ICY stream seeks anywhere, since a
 * progressive live period never seeks inside its buffer
 * (ProgressiveMediaPeriod.seekToUs) but drops what it has and reconnects,
 * and one that never played reopens its stream. Shorter pauses are left
 * alone: a reconnect costs the station's pre-roll, which is worse than a
 * few seconds' lag.
 */
internal class StationLiveResume(
    private val player: Player,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {

    /** When the current station stopped playing, or null while not paused on a station. */
    private var pausedSince: Long? = null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        // A new item is a fresh connection; a restored queue arrives paused.
        pausedSince = if (isStation(mediaItem) && !player.playWhenReady) now() else null
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        val station = isStation(player.currentMediaItem)
        if (!playWhenReady) {
            pausedSince = if (station) now() else null
            return
        }
        val since = pausedSince ?: return
        pausedSince = null
        // Idle means a failed load; PlaybackRecoveryListener prepares afresh.
        if (!station || player.playbackState == Player.STATE_IDLE) return
        if (now() - since < RESUME_LIVE_AFTER_MS) return
        goLive()
    }

    private fun goLive() {
        when {
            // A live HLS window keeps moving while paused, so its live edge is
            // never the current position.
            player.isCurrentMediaItemSeekable -> player.seekToDefaultPosition()
            // An unseekable window is an ICY stream: the period treats every
            // seek as one to 0 and reconnects.
            player.currentPosition > 0 -> player.seekTo(0)
            // Restored paused and never played: ExoPlayer drops a seek to the
            // current position, 0, so reopen the stream instead. stop() keeps
            // the queue and playWhenReady; prepare() connects afresh.
            else -> {
                player.stop()
                player.prepare()
            }
        }
    }

    private fun isStation(item: MediaItem?): Boolean =
        item?.mediaId?.let(MediaId::parse) is MediaId.RadioStation

    companion object {
        /** A pause at least this long resumes live. */
        const val RESUME_LIVE_AFTER_MS = 30_000L
    }
}
