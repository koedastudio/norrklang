package studio.koeda.norrklang.media

import android.content.Context
import androidx.annotation.StringRes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException

/**
 * Rewrites a player error raised while a radio station is playing into what
 * the car should show — ExoPlayer's own "Source error" tells the driver
 * nothing. Code, cause and extras are kept, so recovery and diagnostics
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

    fun present(context: Context, current: MediaItem?, error: PlaybackException): PlaybackException {
        val station = stationName(current) ?: return error
        return PlaybackException(
            context.getString(kindOf(error).message, station),
            error.cause,
            error.errorCode,
            error.extras,
        )
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
