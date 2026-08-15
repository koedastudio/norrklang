package studio.koeda.norrklang.data.repo

import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.Track

/** Playback state as reported to the server (see [MusicRepository.reportPlayState]). */
enum class PlayState { PLAYING, PAUSED, STOPPED }

/**
 * Access to the music library. All calls may throw [MusicException].
 */
interface MusicRepository {

    /**
     * Interval for periodic [PlayState.PLAYING] reports while a track plays,
     * or null when the provider wants no play-state reporting at all
     * (Subsonic — its `scrobble` calls carry everything the server needs).
     * Non-null (Plex) also enables the state-change reports.
     */
    val playbackReportIntervalMs: Long? get() = null

    /**
     * Provider-defined play-state report. Subsonic: no-op. Plex: `/:/timeline`
     * ping driving the server's now-playing, on-deck, and play history.
     * Never called when [playbackReportIntervalMs] is null.
     */
    suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
    ) {
    }
    suspend fun artists(): List<Artist>
    suspend fun artist(id: String): ArtistDetail
    suspend fun albums(offset: Int = 0, size: Int = 100): List<Album>
    suspend fun recentlyAdded(size: Int = 50): List<Album>

    /** Albums starred ("favorited") on the server. */
    suspend fun favoriteAlbums(size: Int = 50): List<Album>

    /** All tracks starred ("favorited") on the server. */
    suspend fun favoriteTracks(): List<Track>

    /** [size] random tracks; a fresh selection on every call (never cached). */
    suspend fun randomTracks(size: Int = 50): List<Track>

    /** Albums most recently played on this account, most recent first. */
    suspend fun recentlyPlayedAlbums(size: Int = 50): List<Album>

    /** Albums most frequently played on this account, most played first. */
    suspend fun mostPlayedAlbums(size: Int = 50): List<Album>

    /** All genres in the library with their song/album counts. */
    suspend fun genres(): List<Genre>

    /** Albums tagged with [genre] (exact name as reported by [genres]). */
    suspend fun albumsByGenre(genre: String, size: Int = 20): List<Album>

    /** Albums whose year falls in `[fromYear, toYear]` (inclusive). */
    suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int = 20): List<Album>

    /**
     * [size] random tracks tagged with [genre]; a fresh selection on every
     * call (never cached — CatalogMixesSession owns list stability).
     */
    suspend fun randomTracksByGenre(genre: String, size: Int = 50): List<Track>

    /**
     * [size] random tracks from `[fromYear, toYear]`; a fresh selection on
     * every call (never cached — CatalogMixesSession owns list stability).
     */
    suspend fun randomTracksByYearRange(fromYear: Int, toYear: Int, size: Int = 50): List<Track>

    /** Artists behind the most frequently played albums, most played first. */
    suspend fun mostPlayedArtists(size: Int = 30): List<Artist>

    /** Artists behind the most recently played albums, most recent first. */
    suspend fun recentlyPlayedArtists(size: Int = 30): List<Artist>

    /**
     * In-library artists similar to [artistId] (Subsonic: Last.fm; Plex: the
     * related-artists hub); empty when the server has no similarity data.
     */
    suspend fun similarArtists(artistId: String, count: Int = 10): List<Artist>

    /**
     * Random library tracks by [artistId] and artists similar to it; a fresh
     * selection on every call (never cached — SimilarMixesSession owns list
     * stability). Empty when the server has no similarity data.
     */
    suspend fun similarTracks(artistId: String, count: Int = 50): List<Track>

    /**
     * The artist's most popular library tracks (Last.fm-backed, keyed by
     * artist NAME per the Subsonic API); empty when the server has no data.
     */
    suspend fun topTracks(artistName: String, count: Int = 20): List<Track>

    /** Whether the track is starred on the server (served from cache). */
    suspend fun isFavoriteTrack(trackId: String): Boolean

    /** Stars or unstars the track on the server. */
    suspend fun setTrackFavorite(trackId: String, favorite: Boolean)

    /** Stars or unstars the album on the server. */
    suspend fun setAlbumFavorite(albumId: String, favorite: Boolean)
    suspend fun album(id: String): AlbumDetail

    /** All playlists, most recently modified first. */
    suspend fun playlists(): List<Playlist>
    suspend fun playlist(id: String): PlaylistDetail
    suspend fun track(id: String): Track
    suspend fun search(query: String): SearchResults
    suspend fun scrobble(trackId: String, submission: Boolean)
    fun invalidateCache()
}
