package studio.koeda.norrklang.data.settings

import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.subsonic.SubsonicCredentials

/** The persisted sign-in, discriminated by provider. One at a time (single active server). */
sealed interface StoredAccount {
    data class Subsonic(val credentials: SubsonicCredentials) : StoredAccount
    data class Plex(val account: PlexAccount) : StoredAccount
}
