package studio.koeda.norrklang.data.repo

import studio.koeda.norrklang.jellyfin.JellyfinException

/** Boundary translation: core-jellyfin errors → the app-wide neutral hierarchy. */
internal fun JellyfinException.toMusicException(): MusicException = when (this) {
    is JellyfinException.AuthFailed -> MusicException.AuthFailed(message.orEmpty())
    is JellyfinException.NotFound -> MusicException.NotFound(message.orEmpty())
    is JellyfinException.ServerError -> MusicException.ServerError(message.orEmpty(), this)
    is JellyfinException.NetworkError -> MusicException.NetworkError(cause ?: this)
}
