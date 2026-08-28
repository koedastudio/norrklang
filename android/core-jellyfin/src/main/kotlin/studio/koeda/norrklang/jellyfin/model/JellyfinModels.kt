package studio.koeda.norrklang.jellyfin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Jellyfin payloads (BaseItemDto) are huge; every model keeps only the fields
// we read and relies on ignoreUnknownKeys. The JSON is PascalCase, so every
// field carries a SerialName.

/** Response of `/Users/AuthenticateByName`. */
@Serializable
data class JellyfinAuthResult(
    @SerialName("User") val user: JellyfinUser = JellyfinUser(),
    @SerialName("AccessToken") val accessToken: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class JellyfinUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

/** Response of `/System/Info/Public` — the server's display name for labels. */
@Serializable
data class JellyfinPublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
)

/** The list envelope every Items-style endpoint answers with. */
@Serializable
data class JellyfinItemsResult(
    @SerialName("Items") val items: List<JellyfinItem> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int? = null,
)

/** An artist, album, track, playlist, genre, or library view depending on [type]. */
@Serializable
data class JellyfinItem(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String = "",
    @SerialName("SortName") val sortName: String? = null,
    /** MusicArtist | MusicAlbum | Audio | Playlist | MusicGenre | CollectionFolder. */
    @SerialName("Type") val type: String? = null,
    /** "music" on the library view we want. */
    @SerialName("CollectionType") val collectionType: String? = null,
    /** "Audio" on audio playlists and tracks. */
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("AlbumId") val albumId: String? = null,
    @SerialName("Album") val album: String? = null,
    @SerialName("AlbumArtist") val albumArtist: String? = null,
    @SerialName("AlbumArtists") val albumArtists: List<JellyfinNameId> = emptyList(),
    @SerialName("ArtistItems") val artistItems: List<JellyfinNameId> = emptyList(),
    /** Track number for tracks. */
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    /** Disc number for tracks. */
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    /** Runtime in ticks; 1 tick = 100 ns. */
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    /** Album count for artists, track count for albums (when the server sends it). */
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    /** Set on tracks whose album has a primary image. */
    @SerialName("AlbumPrimaryImageTag") val albumPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JellyfinUserData? = null,
)

@Serializable
data class JellyfinNameId(
    @SerialName("Name") val name: String = "",
    @SerialName("Id") val id: String = "",
)

/** Per-user state riding on items. */
@Serializable
data class JellyfinUserData(
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("PlayCount") val playCount: Int? = null,
    @SerialName("Played") val played: Boolean = false,
)

/** Body of `/Users/AuthenticateByName`. */
@Serializable
data class JellyfinAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

/** Body of the three `/Sessions/Playing*` reporting endpoints. */
@Serializable
data class JellyfinPlaybackBody(
    @SerialName("ItemId") val itemId: String,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean = false,
    @SerialName("PlayMethod") val playMethod: String = "DirectPlay",
    @SerialName("CanSeek") val canSeek: Boolean = true,
)
