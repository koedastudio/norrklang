package studio.koeda.norrklang.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Auto-recovers playback from transient network failures (garages, tunnels,
 * LTE re-attach). Once ExoPlayer's own load retries are exhausted it stops
 * with a fatal error; this listener presses "play" for the user:
 *
 *  - a recoverable network error schedules re-prepares with exponential
 *    backoff ([Player.prepare] alone resumes — queue, position and
 *    playWhenReady survive a source error)
 *  - a usable default network fires a retry immediately and resets the
 *    backoff
 *
 * Non-network errors (decoder, auth — see [AuthGatePlayer]) are left alone;
 * retrying can't fix them.
 */
@UnstableApi
internal class PlaybackRecoveryListener(
    context: Context,
    private val scope: CoroutineScope,
    private val player: Player,
) : Player.Listener {

    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private var retryJob: Job? = null
    private var attempts = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Callbacks arrive on a system thread; the player is main-thread
            // only (scope runs on Main.immediate).
            scope.launch {
                if (player.playerError?.isRecoverable() == true) {
                    attempts = 0
                    retryNow()
                }
            }
        }
    }

    init {
        // Registration can throw (e.g. TooManyRequestsException); recovery
        // then still works via backoff alone.
        runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
    }

    override fun onPlayerError(error: PlaybackException) {
        if (!error.isRecoverable() || attempts >= MAX_ATTEMPTS) return
        val delayMs = BASE_DELAY_MS shl attempts.coerceAtMost(MAX_BACKOFF_SHIFT)
        attempts++
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            retryNow()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) attempts = 0
    }

    /** Must be called when the service shuts down. */
    fun release() {
        retryJob?.cancel()
        retryJob = null
        runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
    }

    private fun retryNow() {
        if (player.playerError?.isRecoverable() != true) return
        player.prepare()
    }

    private fun PlaybackException.isRecoverable(): Boolean = when (errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> true
        // Server hiccups are worth retrying; client errors (404, auth) not.
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            (cause as? HttpDataSource.InvalidResponseCodeException)
                ?.responseCode?.let { it >= 500 || it == 429 } == true
        else -> false
    }

    private companion object {
        const val BASE_DELAY_MS = 2_000L
        const val MAX_BACKOFF_SHIFT = 4 // caps the delay at 2s << 4 = 32s
        const val MAX_ATTEMPTS = 6 // ~1.5 min total before waiting on connectivity
    }
}
