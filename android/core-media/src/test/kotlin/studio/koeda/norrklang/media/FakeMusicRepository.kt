package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Base fake for the session tests: every member fails as "unused", so each
 * test overrides only what it scripts and interface growth is a one-file
 * edit instead of breaking every fake.
 */
internal open class FakeMusicRepository : MusicRepository {
    override suspend fun artists(): List<Artist> = error("unused")
    override suspend fun artist(id: String): ArtistDetail = error("unused")
    override suspend fun albums(offset: Int, size: Int): List<Album> = error("unused")
    override suspend fun recentlyAdded(size: Int): List<Album> = error("unused")
    override suspend fun favoriteAlbums(size: Int): List<Album> = error("unused")
    override suspend fun favoriteTracks(): List<Track> = error("unused")
    override suspend fun randomTracks(size: Int): List<Track> = error("unused")
    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> = error("unused")
    override suspend fun mostPlayedAlbums(size: Int): List<Album> = error("unused")
    override suspend fun genres(): List<Genre> = error("unused")
    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> = error("unused")
    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        error("unused")
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        error("unused")
    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> = error("unused")
    override suspend fun mostPlayedArtists(size: Int): List<Artist> = error("unused")
    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> = error("unused")
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        error("unused")
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
        error("unused")
    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        error("unused")
    override suspend fun isFavoriteTrack(trackId: String): Boolean = error("unused")
    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) = error("unused")
    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) = error("unused")
    override suspend fun album(id: String): AlbumDetail = error("unused")
    override suspend fun playlists(): List<Playlist> = error("unused")
    override suspend fun playlist(id: String): PlaylistDetail = error("unused")
    override suspend fun track(id: String): Track = error("unused")
    override suspend fun search(query: String): SearchResults = error("unused")
    // Explicit Unit: an inferred Nothing return would block overriding.
    override suspend fun scrobble(trackId: String, submission: Boolean): Unit = error("unused")
    override fun invalidateCache() = error("unused")
}

/** A minimal track; [artistId] also derives the display name when present. */
internal fun stubTrack(id: String, artistId: String? = null) = Track(
    id = id,
    title = "Title $id",
    artistName = artistId?.let { "Artist $it" },
    artistId = artistId,
    albumTitle = null,
    albumId = null,
    trackNumber = null,
    discNumber = null,
    durationSec = null,
    artworkUrl = "content://app.artwork/cover/$id",
    streamUrl = "https://server/stream/$id",
)
