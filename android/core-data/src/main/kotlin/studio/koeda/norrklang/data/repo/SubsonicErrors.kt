package studio.koeda.norrklang.data.repo

import studio.koeda.norrklang.subsonic.SubsonicException

/** Boundary translation: core-subsonic errors → the app-wide neutral hierarchy. */
internal fun SubsonicException.toMusicException(): MusicException = when (this) {
    is SubsonicException.AuthFailed -> MusicException.AuthFailed(message.orEmpty())
    is SubsonicException.NotFound -> MusicException.NotFound(message.orEmpty())
    is SubsonicException.ServerError -> MusicException.ServerError(message.orEmpty(), this)
    is SubsonicException.NetworkError -> MusicException.NetworkError(cause ?: this)
}
