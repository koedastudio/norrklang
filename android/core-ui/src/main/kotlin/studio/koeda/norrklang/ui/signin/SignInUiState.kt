package studio.koeda.norrklang.ui.signin

import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.jellyfin.JellyfinException

/** The credentials form's state, shared by the Subsonic and Jellyfin sign-ins. */
sealed interface SignInUiState {
    data object Idle : SignInUiState
    data object Connecting : SignInUiState
    data class Error(val kind: SignInErrorKind, val detail: String?) : SignInUiState
    data object Done : SignInUiState
}

/** Subsonic never emits [NO_MUSIC_LIBRARY]; [CODE_EXPIRED] is Jellyfin Quick Connect only. */
enum class SignInErrorKind {
    MISSING_FIELDS, CLEARTEXT, AUTH, NETWORK, NO_MUSIC_LIBRARY, CODE_EXPIRED, GENERIC,
}

/** A failed sign-in as the form shows it: the neutral or Jellyfin error, anything else generic. */
internal fun Throwable.toSignInError(): SignInUiState.Error = when (this) {
    is MusicException.AuthFailed, is JellyfinException.AuthFailed ->
        SignInUiState.Error(SignInErrorKind.AUTH, message)
    is MusicException.NetworkError, is JellyfinException.NetworkError ->
        SignInUiState.Error(SignInErrorKind.NETWORK, message)
    is SignInPollTimeoutException -> SignInUiState.Error(SignInErrorKind.CODE_EXPIRED, null)
    else -> SignInUiState.Error(SignInErrorKind.GENERIC, message)
}
