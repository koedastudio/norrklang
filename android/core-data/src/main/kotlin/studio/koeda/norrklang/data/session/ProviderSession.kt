package studio.koeda.norrklang.data.session

import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinUrlBuilder
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexClientInfo
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexUrlBuilder
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicUrlBuilder

enum class MusicProvider { SUBSONIC, PLEX, JELLYFIN }

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

    /**
     * Fully-authenticated stream URL for [ref], at the original quality or
     * capped at [maxKbps] via the provider's transcoder. Called by the
     * player's resolver on every load, so quality follows the network.
     * Throws [IllegalArgumentException] when [ref] lacks what this provider
     * needs (e.g. a Plex ref without its part key).
     * SECURITY: embeds the auth token — never log it.
     */
    fun streamUrl(ref: StreamRef, maxKbps: Int?): String
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
    override fun streamUrl(ref: StreamRef, maxKbps: Int?): String =
        urlBuilder.streamUrl(ref.trackId, maxKbps)
    override fun close() = client.close()
}

class PlexSession(
    val account: PlexAccount,
    val client: PlexServerClient,
    clientInfo: PlexClientInfo,
    val urlBuilder: PlexUrlBuilder = PlexUrlBuilder(account.serverUri, account.token, clientInfo),
) : ProviderSession {
    override val provider: MusicProvider get() = MusicProvider.PLEX
    override val accountLabel: String get() = account.username
    override val serverLabel: String get() = account.serverName
    override val cacheFingerprint: String get() = account.cacheFingerprint
    override fun artworkUrl(artworkId: String): String = urlBuilder.artworkUrl(artworkId)
    override fun streamUrl(ref: StreamRef, maxKbps: Int?): String {
        val partKey = requireNotNull(ref.plexPartKey) { "Plex stream ref without part key" }
        return urlBuilder.streamUrl(ref.trackId, partKey, maxKbps)
    }
    override fun close() = client.close()
}

class JellyfinSession(
    val account: JellyfinAccount,
    val client: JellyfinClient,
    deviceId: String,
    val urlBuilder: JellyfinUrlBuilder =
        JellyfinUrlBuilder(account.baseUrl, account.token, account.userId, deviceId),
) : ProviderSession {
    override val provider: MusicProvider get() = MusicProvider.JELLYFIN
    override val accountLabel: String get() = account.username
    override val serverLabel: String get() = account.serverName
    override val cacheFingerprint: String get() = account.cacheFingerprint
    override fun artworkUrl(artworkId: String): String = urlBuilder.artworkUrl(artworkId)
    override fun streamUrl(ref: StreamRef, maxKbps: Int?): String =
        urlBuilder.streamUrl(ref.trackId, maxKbps)
    override fun close() = client.close()
}
