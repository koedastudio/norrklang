package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.SessionManager

/**
 * The [MusicRepository] the app binds: forwards every call to the backend
 * matching the active session's provider. Signed out → [MusicException.AuthFailed],
 * exactly like the per-provider repositories themselves.
 */
@Singleton
class RoutingMusicRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val subsonic: SubsonicMusicRepository,
    private val plex: PlexMusicRepository,
) : MusicRepository {

    private fun activeOrNull(): MusicRepository? =
        when (sessionManager.connectedOrNull()?.session?.provider) {
            MusicProvider.SUBSONIC -> subsonic
            MusicProvider.PLEX -> plex
            null -> null
        }

    private fun active(): MusicRepository =
        activeOrNull() ?: throw MusicException.AuthFailed("Not signed in")

    override suspend fun artists(): List<Artist> = active().artists()
    override suspend fun artist(id: String): ArtistDetail = active().artist(id)
    override suspend fun albums(offset: Int, size: Int): List<Album> =
        active().albums(offset, size)
    override suspend fun recentlyAdded(size: Int): List<Album> = active().recentlyAdded(size)
    override suspend fun favoriteAlbums(size: Int): List<Album> = active().favoriteAlbums(size)
    override suspend fun favoriteArtists(): List<Artist> = active().favoriteArtists()
    override suspend fun favoriteTracks(): List<Track> = active().favoriteTracks()
    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        active().recentlyAddedTracks(size)
    override suspend fun randomTracks(size: Int): List<Track> = active().randomTracks(size)
    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        active().recentlyPlayedAlbums(size)
    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        active().mostPlayedAlbums(size)
    override suspend fun genres(): List<Genre> = active().genres()
    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        active().albumsByGenre(genre, size)
    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        active().albumsByYearRange(fromYear, toYear, size)
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        active().randomTracksByGenre(genre, size)
    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> = active().randomTracksByYearRange(fromYear, toYear, size)
    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        active().mostPlayedArtists(size)
    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        active().recentlyPlayedArtists(size)
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        active().similarArtists(artistId, count)
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
        active().similarTracks(artistId, count)
    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        active().topTracks(artistName, count)
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        active().isFavoriteTrack(trackId)
    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        active().setTrackFavorite(trackId, favorite)
    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        active().setAlbumFavorite(albumId, favorite)
    override suspend fun album(id: String): AlbumDetail = active().album(id)
    override suspend fun playlists(): List<Playlist> = active().playlists()
    override suspend fun playlist(id: String): PlaylistDetail = active().playlist(id)
    override suspend fun track(id: String): Track = active().track(id)
    override suspend fun search(query: String): SearchResults = active().search(query)
    override suspend fun scrobble(trackId: String, submission: Boolean) =
        active().scrobble(trackId, submission)

    // Signed out answers null instead of throwing — reporting is optional.
    override val playbackReportIntervalMs: Long?
        get() = activeOrNull()?.playbackReportIntervalMs

    // Lenient like the interval property: a periodic report racing a
    // sign-out is dropped, not an error.
    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
    ) {
        activeOrNull()?.reportPlayState(trackId, state, positionMs, durationMs)
    }

    override fun invalidateCache() {
        subsonic.invalidateCache()
        plex.invalidateCache()
    }
}
