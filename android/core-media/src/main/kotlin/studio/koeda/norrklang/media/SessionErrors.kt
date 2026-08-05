package studio.koeda.norrklang.media

import android.app.PendingIntent
import android.content.Context
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants

@OptIn(UnstableApi::class)
internal object SessionErrors {

    /**
     * Persistent "Sign in to Navidrome" state, applied via
     * [AuthGatePlayer.setAuthError] while signed out (cleared with `null`
     * after sign-in). Player-level so it sticks as a lasting STATE_ERROR in
     * the legacy PlaybackStateCompat (see [AuthGatePlayer]); the `_COMPAT`
     * extras are what the car hosts read to render a tappable sign-in action
     * (parked only).
     */
    fun authenticationExpiredException(
        context: Context,
        signInIntent: PendingIntent,
    ): PlaybackException {
        val extras = Bundle().apply {
            putString(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT,
                context.getString(R.string.error_sign_in_action),
            )
            putParcelable(
                MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT,
                signInIntent,
            )
        }
        return PlaybackException(
            context.getString(R.string.error_sign_in_required),
            /* cause = */ null,
            PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
            extras,
        )
    }
}
