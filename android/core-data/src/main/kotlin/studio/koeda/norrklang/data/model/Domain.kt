package studio.koeda.norrklang.data.model

/*
 * Domain models used by the media layer and UIs. DTO→domain mapping happens in
 * MusicRepository, where artwork/stream URLs are resolved via SubsonicUrlBuilder
 * so consumers never touch auth details.
 */

data class Artist(
    val id: String,
    val name: String,
    val albumCount: Int,
    val artworkUrl: String?,
    /**
     * The server index bucket ("A"…"#") this artist was listed under; null
     * when the artist came from a detail or search response with no index.
     */
    val sortGroup: String? = null,
)

data class ArtistDetail(
    val artist: Artist,
    val albums: List<Album>,
)

data class Album(
    val id: String,
    val title: String,
    val artistName: String?,
    val artistId: String?,
    val year: Int?,
    val trackCount: Int,
    val durationSec: Int,
    val artworkUrl: String?,
    /** Whether the album is starred ("favorited") on the server. */
    val isFavorite: Boolean = false,
)

data class AlbumDetail(
    val album: Album,
    val tracks: List<Track>,
)

data class Track(
    val id: String,
    val title: String,
    val artistName: String?,
    val artistId: String? = null,
    val albumTitle: String?,
    val albumId: String?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationSec: Int?,
    val artworkUrl: String?,
    val streamUrl: String,
)

/** A genre as aggregated by the server, with its library-wide song count. */
data class Genre(
    val name: String,
    val songCount: Int,
)

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val durationSec: Int,
    val artworkUrl: String?,
)

data class PlaylistDetail(
    val playlist: Playlist,
    val tracks: List<Track>,
)

data class SearchResults(
    val artists: List<Artist>,
    val albums: List<Album>,
    val tracks: List<Track>,
)
