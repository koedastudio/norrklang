package studio.koeda.norrklang.media

import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi

/**
 * Rewrites a player error raised while a radio station is playing, or a
 * track's connection failure, into what the car should show — ExoPlayer's
 * own "Source error" tells the driver nothing. Code, cause and extras are kept, so recovery and diagnostics
 * still see the original failure.
 */
internal object RadioErrors {

    enum class Kind(@StringRes val message: Int) {
        /** Transient network trouble; PlaybackRecoveryListener keeps retrying. */
        UNREACHABLE(R.string.error_radio_unreachable),
        /** The station answered but refused or has no stream at that address. */
        UNAVAILABLE(R.string.error_radio_unavailable),
        /** The stream arrived but can't be parsed or decoded. */
        UNSUPPORTED(R.string.error_radio_unsupported),
    }

    @OptIn(UnstableApi::class) // 4-arg PlaybackException, extras
    fun present(context: Context, current: MediaItem?, error: PlaybackException): PlaybackException {
        val message = stationName(current)?.let { context.getString(kindOf(error).message, it) }
            // A track queued without signal: say so while recovery waits for the network.
            ?: if (isConnectionFailure(error)) context.getString(R.string.error_no_connection) else return error
        return PlaybackException(message, error.cause, error.errorCode, error.extras)
    }

    /** The server could not be reached at all, as opposed to answering with an error. */
    fun isConnectionFailure(error: PlaybackException): Boolean = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> true
        else -> false
    }

    /** The station [item] plays, or null when it is not a radio station. */
    fun stationName(item: MediaItem?): String? {
        if (item == null || MediaId.parse(item.mediaId) !is MediaId.RadioStation) return null
        return (item.mediaMetadata.station ?: item.mediaMetadata.title)?.toString()
    }

    fun kindOf(error: PlaybackException): Kind = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
        -> Kind.UNAVAILABLE
        // 3xxx = content parsing, 4xxx = decoding.
        in 3000..4999 -> Kind.UNSUPPORTED
        else -> Kind.UNREACHABLE
    }
}
