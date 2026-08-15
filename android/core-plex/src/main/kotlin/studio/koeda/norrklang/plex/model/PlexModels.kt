package studio.koeda.norrklang.plex.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Plex payloads are huge and unstable across server versions; every model
// keeps only the fields we read and relies on ignoreUnknownKeys.

// --- plex.tv /api/v2 (JSON with Accept: application/json) ---

@Serializable
data class PlexPin(
    val id: Long,
    val code: String,
    val authToken: String? = null,
    val expiresIn: Long? = null,
)

/** The linked account (`/api/v2/user`) — only the display identity is read. */
@Serializable
data class PlexUser(
    val username: String = "",
    val email: String? = null,
)

/** One entry of `/api/v2/resources` — a server, player, or other device. */
@Serializable
data class PlexResource(
    val name: String = "",
    val clientIdentifier: String = "",
    val provides: String = "",
    val owned: Boolean = false,
    /** Per-server access token for shared servers; null for owned ones on some accounts. */
    val accessToken: String? = null,
    val connections: List<PlexConnection> = emptyList(),
) {
    val isServer: Boolean get() = provides.split(",").contains("server")
}

@Serializable
data class PlexConnection(
    val protocol: String = "https",
    val address: String = "",
    val port: Int = 0,
    val uri: String = "",
    val local: Boolean = false,
    val relay: Boolean = false,
)

// --- PMS MediaContainer envelope (JSON with Accept: application/json) ---

@Serializable
data class PlexEnvelope(
    @SerialName("MediaContainer") val mediaContainer: PlexMediaContainer = PlexMediaContainer(),
)

@Serializable
data class PlexMediaContainer(
    val size: Int = 0,
    val totalSize: Int? = null,
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
    @SerialName("Directory") val directory: List<PlexDirectory> = emptyList(),
    @SerialName("Hub") val hubs: List<PlexHub> = emptyList(),
)

/** Library sections and filter directories (genres). */
@Serializable
data class PlexDirectory(
    val key: String = "",
    val title: String = "",
    val type: String? = null,
    /** Only present on filter directories (e.g. genre listings). */
    @SerialName("fastKey") val fastKey: String? = null,
)

@Serializable
data class PlexHub(
    val type: String = "",
    @SerialName("Metadata") val metadata: List<PlexMetadata> = emptyList(),
)

/** An artist, album, track, or playlist depending on [type]. */
@Serializable
data class PlexMetadata(
    val ratingKey: String? = null,
    val key: String? = null,
    val type: String? = null,
    val title: String = "",
    val titleSort: String? = null,
    val parentRatingKey: String? = null,
    val grandparentRatingKey: String? = null,
    val parentTitle: String? = null,
    val grandparentTitle: String? = null,
    /** Track number for tracks, album ordering for albums. */
    val index: Int? = null,
    /** Disc number for tracks. */
    val parentIndex: Int? = null,
    val year: Int? = null,
    val parentYear: Int? = null,
    val thumb: String? = null,
    val parentThumb: String? = null,
    val grandparentThumb: String? = null,
    /** Playlist cover collage path (playlists have no `thumb`). */
    val composite: String? = null,
    /** Milliseconds. */
    val duration: Long? = null,
    val addedAt: Long? = null,
    val viewCount: Int? = null,
    val lastViewedAt: Long? = null,
    val userRating: Double? = null,
    /** Track count for albums/playlists. */
    val leafCount: Int? = null,
    /** Album count for artists. */
    val childCount: Int? = null,
    val playlistType: String? = null,
    @SerialName("Genre") val genres: List<PlexTag> = emptyList(),
    @SerialName("Media") val media: List<PlexMedia> = emptyList(),
)

@Serializable
data class PlexTag(val tag: String = "")

@Serializable
data class PlexMedia(
    val id: Long? = null,
    @SerialName("Part") val parts: List<PlexPart> = emptyList(),
)

@Serializable
data class PlexPart(
    val id: Long? = null,
    /** Server-relative streaming path, e.g. `/library/parts/123/456/file.flac`. */
    val key: String? = null,
    val container: String? = null,
)
