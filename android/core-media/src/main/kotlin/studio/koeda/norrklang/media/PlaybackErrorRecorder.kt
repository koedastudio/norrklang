package studio.koeda.norrklang.media

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import studio.koeda.norrklang.data.diagnostics.Diagnostics

/**
 * Records fatal player errors in the diagnostics log — without this,
 * "Source error" playback failures never reach the screen or QR report.
 */
internal class PlaybackErrorRecorder : Player.Listener {

    override fun onPlayerError(error: PlaybackException) {
        val cause = error.cause
        val causePart = cause
            ?.let { " — ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }
            .orEmpty()
        Diagnostics.record("playback", error.errorCodeName + causePart)
    }
}
