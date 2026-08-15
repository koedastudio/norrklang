package studio.koeda.norrklang.data.session

import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexUrlBuilder
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicUrlBuilder

enum class MusicProvider { SUBSONIC, PLEX }

/**
 * The provider-neutral face of a signed-in server connection.
 *
 * Everything above core-data (service, ArtworkProvider, UI) sees only this
 * shape; the repositories downcast to their own provider's implementation for
 * the actual client.
 */
interface ProviderSession : AutoCloseable {
    val provider: MusicProvider

    /** The signed-in account, as shown in settings ("demo"). */
    val accountLabel: String

    /** The server, as shown in settings (base URL or Plex server name). */
    val serverLabel: String

    /**
     * Stable per-(server, account) key namespacing every cache: repository
     * TTL entries, artwork cache directories, and mix snapshots.
     */
    val cacheFingerprint: String

    /**
     * Fully-authenticated URL for an artwork id this provider handed out.
     * SECURITY: embeds the auth token — never log it.
     */
    fun artworkUrl(artworkId: String): String
}

class SubsonicSession(
    val credentials: SubsonicCredentials,
    val client: SubsonicClient,
    val urlBuilder: SubsonicUrlBuilder = SubsonicUrlBuilder(credentials),
) : ProviderSession {
    override val provider: MusicProvider get() = MusicProvider.SUBSONIC
    override val accountLabel: String get() = credentials.username
    override val serverLabel: String get() = credentials.baseUrl
    override val cacheFingerprint: String get() = credentials.cacheFingerprint
    override fun artworkUrl(artworkId: String): String = urlBuilder.coverArtUrl(artworkId)
    override fun close() = client.close()
}

class PlexSession(
    val account: PlexAccount,
    val client: PlexServerClient,
    val urlBuilder: PlexUrlBuilder = PlexUrlBuilder(account.serverUri, account.token),
) : ProviderSession {
    override val provider: MusicProvider get() = MusicProvider.PLEX
    override val accountLabel: String get() = account.username
    override val serverLabel: String get() = account.serverName
    override val cacheFingerprint: String get() = account.cacheFingerprint
    override fun artworkUrl(artworkId: String): String = urlBuilder.artworkUrl(artworkId)
    override fun close() = client.close()
}
