package studio.koeda.norrklang.data.repo

import studio.koeda.norrklang.plex.PlexException

/** Boundary translation: core-plex errors → the app-wide neutral hierarchy. */
internal fun PlexException.toMusicException(): MusicException = when (this) {
    is PlexException.AuthFailed -> MusicException.AuthFailed(message.orEmpty())
    is PlexException.NotFound -> MusicException.NotFound(message.orEmpty())
    is PlexException.ServerError -> MusicException.ServerError(message.orEmpty(), this)
    is PlexException.NetworkError -> MusicException.NetworkError(cause ?: this)
}
