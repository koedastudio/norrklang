package studio.koeda.norrklang.subsonic.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * DTOs for the Subsonic API v1.16.1 (as implemented by Navidrome / OpenSubsonic).
 * Subsonic omits empty arrays entirely, so every list defaults to emptyList().
 *
 * Display-name fields default to "" (with coerceInputValues in the client's
 * Json, an explicit null coerces too): an untagged file must render blank,
 * not fail the parse of every response that includes it. Ids stay required —
 * an item without one is unusable anyway.
 */

@Serializable
internal data class SubsonicEnvelope(
    @SerialName("subsonic-response") val response: SubsonicResponse,
)

@Serializable
internal data class SubsonicResponse(
    val status: String,
    val version: String? = null,
    val error: SubsonicError? = null,
    val artists: ArtistsID3? = null,
    val artist: ArtistWithAlbumsID3? = null,
    val albumList2: AlbumList2? = null,
    val album: AlbumWithSongsID3? = null,
    val song: Child? = null,
    val playlists: Playlists? = null,
    val playlist: PlaylistWithSongs? = null,
    val searchResult3: SearchResult3? = null,
    val starred2: Starred2? = null,
    val randomSongs: Songs? = null,
    val genres: Genres? = null,
    val artistInfo2: ArtistInfo2? = null,
    val similarSongs2: Songs? = null,
    val topSongs: Songs? = null,
)

@Serializable
data class SubsonicError(val code: Int, val message: String? = null)

@Serializable
data class ArtistsID3(val index: List<IndexID3> = emptyList())

@Serializable
data class IndexID3(val name: String = "", val artist: List<ArtistID3> = emptyList())

@Serializable
data class ArtistID3(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val artistImageUrl: String? = null,
)

@Serializable
data class ArtistWithAlbumsID3(
    val id: String,
    val name: String = "",
    val coverArt: String? = null,
    val albumCount: Int = 0,
    val artistImageUrl: String? = null,
    val album: List<AlbumID3> = emptyList(),
)

@Serializable
data class AlbumList2(val album: List<AlbumID3> = emptyList())

@Serializable
data class AlbumID3(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val created: String? = null,
    val year: Int? = null,
    val genre: String? = null,
    /** ISO-8601 timestamp of when the album was starred; null when it isn't. */
    val starred: String? = null,
)

@Serializable
data class AlbumWithSongsID3(
    val id: String,
    val name: String = "",
    val artist: String? = null,
    val artistId: String? = null,
    val coverArt: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val year: Int? = null,
    val genre: String? = null,
    /** ISO-8601 timestamp of when the album was starred; null when it isn't. */
    val starred: String? = null,
    val song: List<Child> = emptyList(),
)

/** A single track ("child" in Subsonic terms). */
@Serializable
data class Child(
    val id: String,
    val title: String = "",
    val album: String? = null,
    val artist: String? = null,
    val albumId: String? = null,
    val artistId: String? = null,
    val track: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    val coverArt: String? = null,
    val size: Long? = null,
    val contentType: String? = null,
    val suffix: String? = null,
    val duration: Int? = null,
    val bitRate: Int? = null,
)

@Serializable
data class Songs(val song: List<Child> = emptyList())

@Serializable
data class Genres(val genre: List<Genre> = emptyList())

/** A genre as aggregated by the server; [value] is the display name. */
@Serializable
data class Genre(
    val value: String = "",
    val songCount: Int = 0,
    val albumCount: Int = 0,
)

/**
 * Last.fm-backed artist metadata. All fields are empty/null when the server
 * has no Last.fm integration. Similar artists that are not in the library
 * come back with a synthetic id and the [ArtistID3.albumCount] default of 0.
 */
@Serializable
data class ArtistInfo2(
    val biography: String? = null,
    val musicBrainzId: String? = null,
    val similarArtist: List<ArtistID3> = emptyList(),
)

@Serializable
data class Playlists(val playlist: List<Playlist> = emptyList())

@Serializable
data class Playlist(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val public: Boolean? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    /** ISO-8601 last-modified timestamp, e.g. `2026-07-01T18:04:00Z`. */
    val changed: String? = null,
)

@Serializable
data class PlaylistWithSongs(
    val id: String,
    val name: String = "",
    val comment: String? = null,
    val owner: String? = null,
    val songCount: Int = 0,
    val duration: Int = 0,
    val coverArt: String? = null,
    val entry: List<Child> = emptyList(),
)

@Serializable
data class Starred2(
    val artist: List<ArtistID3> = emptyList(),
    val album: List<AlbumID3> = emptyList(),
    val song: List<Child> = emptyList(),
)

@Serializable
data class SearchResult3(
    val artist: List<ArtistID3> = emptyList(),
    val album: List<AlbumID3> = emptyList(),
    val song: List<Child> = emptyList(),
)
