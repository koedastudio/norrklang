package studio.koeda.norrklang.data.repo

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicException
import studio.koeda.norrklang.subsonic.SubsonicUrlBuilder
import studio.koeda.norrklang.subsonic.model.AlbumID3
import studio.koeda.norrklang.subsonic.model.ArtistID3
import studio.koeda.norrklang.subsonic.model.Child
import studio.koeda.norrklang.subsonic.model.Playlist as PlaylistDto

@Singleton
class DefaultMusicRepository @Inject constructor(
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    @ApplicationContext private val context: Context,
    @ApplicationScope scope: CoroutineScope,
) : MusicRepository {

    private val cache = TtlCache(ttlMillis = 5 * 60 * 1000L)

    init {
        // Cached entries embed authenticated stream URLs and must never
        // survive a sign-out or account switch. Keys are also namespaced by
        // the session fingerprint (see [cached]) so a missed clear can't
        // serve one account's library to another. drop(1) skips the
        // subscribe-time state — nothing to clear yet.
        scope.launch {
            sessionManager.state.drop(1).collect { cache.clear() }
        }
    }

    override suspend fun artists(): List<Artist> =
        cached("artists") { client, _ ->
            client.getArtists().flatMap { index ->
                index.artist.map { it.toDomain(sortGroup = index.name) }
            }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { client, _ ->
            val dto = client.getArtist(id)
            ArtistDetail(
                artist = Artist(
                    id = dto.id,
                    name = dto.name,
                    albumCount = dto.albumCount,
                    artworkUrl = dto.coverArt?.let { artworkUri(it) },
                ),
                // Newest first; the server sends oldest first. Albums without
                // a year sink to the end.
                albums = dto.album.map { it.toDomain() }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    override suspend fun albums(offset: Int, size: Int): List<Album> =
        cached("albums/$offset/$size") { client, _ ->
            client.getAlbumList2(SubsonicClient.AlbumListType.ALPHABETICAL, size, offset)
                .map { it.toDomain() }
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        cached("recent/$size") { client, _ ->
            client.getAlbumList2(SubsonicClient.AlbumListType.NEWEST, size)
                .map { it.toDomain() }
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        cached("favorites/$size") { client, _ ->
            client.getAlbumList2(SubsonicClient.AlbumListType.STARRED, size)
                .map { it.toDomain() }
        }

    override suspend fun favoriteTracks(): List<Track> =
        cached("favorite-tracks") { client, urls ->
            client.getStarred2().song.map { it.toDomain(urls) }
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withSession { client, urls ->
            client.getRandomSongs(size).map { it.toDomain(urls) }
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        cached("recently-played-albums/$size") { client, _ ->
            client.getAlbumList2(SubsonicClient.AlbumListType.RECENT, size)
                .map { it.toDomain() }
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        cached("most-played-albums/$size") { client, _ ->
            client.getAlbumList2(SubsonicClient.AlbumListType.FREQUENT, size)
                .map { it.toDomain() }
        }

    override suspend fun genres(): List<Genre> =
        cached("genres") { client, _ ->
            client.getGenres().map { Genre(it.value, it.songCount) }
        }

    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        cached("albums-by-genre/$genre/$size") { client, _ ->
            client.getAlbumList2ByGenre(genre, size).map { it.toDomain() }
        }

    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        cached("albums-by-year/$fromYear-$toYear/$size") { client, _ ->
            client.getAlbumList2ByYear(fromYear, toYear, size).map { it.toDomain() }
        }

    // Uncached like randomTracks: CatalogMixesSession owns list stability.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withSession { client, urls ->
            client.getRandomSongs(size, genre = genre).map { it.toDomain(urls) }
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withSession { client, urls ->
            client.getRandomSongs(size, fromYear = fromYear, toYear = toYear)
                .map { it.toDomain(urls) }
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", SubsonicClient.AlbumListType.FREQUENT, size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", SubsonicClient.AlbumListType.RECENT, size)

    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        cached("similar-artists/$artistId/$count") { client, _ ->
            emptyWhenMissing { client.getArtistInfo2(artistId, count).similarArtist }
                // Similar artists not in the library come back with a synthetic
                // id and no albums — useless as mix sources, drop them.
                .filter { it.albumCount > 0 }
                .map { it.toDomain() }
        }

    // Uncached like randomTracks: getSimilarSongs2 has a random component and
    // SimilarMixesSession owns list stability.
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
        withSession { client, urls ->
            emptyWhenMissing { client.getSimilarSongs2(artistId, count) }
                .map { it.toDomain(urls) }
        }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        cached("top-songs/$artistName/$count") { client, urls ->
            emptyWhenMissing { client.getTopSongs(artistName, count) }
                .map { it.toDomain(urls) }
        }

    // Runs on every track transition (keeps the car's heart button current);
    // served from the TTL cache, which setTrackFavorite clears so the answer
    // never lags a local change. Cached as an id Set for cheap lookups.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { _, _ ->
            favoriteTracks().mapTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { client, _ ->
            if (favorite) client.star(trackId) else client.unstar(trackId)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { client, _ ->
            if (favorite) client.starAlbum(albumId) else client.unstarAlbum(albumId)
            // Albums carry their starred state (see AlbumID3.starred), so every
            // cached album list is stale the moment a star changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { client, urls ->
            val dto = client.getAlbum(id)
            AlbumDetail(
                album = Album(
                    id = dto.id,
                    title = dto.name,
                    artistName = dto.artist,
                    artistId = dto.artistId,
                    year = dto.year,
                    trackCount = dto.songCount,
                    durationSec = dto.duration,
                    artworkUrl = dto.coverArt?.let { artworkUri(it) },
                    isFavorite = dto.starred != null,
                ),
                tracks = dto.song.map { it.toDomain(urls) },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { client, _ ->
            // `changed` is ISO-8601, so lexicographic order is chronological.
            client.getPlaylists()
                .sortedByDescending { it.changed.orEmpty() }
                .map { it.toDomain() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { client, urls ->
            val dto = client.getPlaylist(id)
            PlaylistDetail(
                playlist = Playlist(
                    id = dto.id,
                    name = dto.name,
                    trackCount = dto.songCount,
                    durationSec = dto.duration,
                    artworkUrl = dto.coverArt?.let { artworkUri(it) },
                ),
                tracks = dto.entry.map { it.toDomain(urls) },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { client, urls ->
            client.getSong(id).toDomain(urls)
        }

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        cached("search/$query") { client, urls ->
            val result = client.search3(query, count = SEARCH_COUNT_PER_TYPE)
            SearchResults(
                artists = result.artist.map { it.toDomain() },
                albums = result.album.map { it.toDomain() },
                tracks = result.song.map { it.toDomain(urls) },
            )
        }

    override suspend fun scrobble(trackId: String, submission: Boolean) =
        withSession { client, _ -> client.scrobble(trackId, submission) }

    override fun invalidateCache() = cache.clear()

    private companion object {
        // Per result type (artists/albums/songs); generous enough that paged
        // search hosts have something to page through.
        const val SEARCH_COUNT_PER_TYPE = 50
    }

    private suspend fun <T : Any> cached(
        key: String,
        loader: suspend (SubsonicClient, SubsonicUrlBuilder) -> T,
    ): T {
        val session = sessionManager.connectedOrNull()
            ?: throw SubsonicException.AuthFailed("Not signed in")
        // Stream URLs depend on the raw/transcode setting — keying by it makes
        // a toggle take effect immediately instead of after the TTL expires.
        val raw = streamOriginal()
        val scopedKey = "${session.credentials.cacheFingerprint}/raw=$raw/$key"
        // The loader uses the SAME session snapshot the key was computed from:
        // a re-read could store one account's data under another's fingerprint
        // if an account switch lands between the two reads.
        return cache.getOrLoad(scopedKey) {
            try {
                loader(session.client, session.urlBuilder.withStreamOriginal(raw))
            } catch (e: SubsonicException.AuthFailed) {
                sessionManager.onAuthRejected()
                throw e
            }
        }
    }

    private suspend fun <T> withSession(
        block: suspend (SubsonicClient, SubsonicUrlBuilder) -> T,
    ): T {
        val session = sessionManager.connectedOrNull()
            ?: throw SubsonicException.AuthFailed("Not signed in")
        return try {
            block(session.client, session.urlBuilder.withStreamOriginal(streamOriginal()))
        } catch (e: SubsonicException.AuthFailed) {
            sessionManager.onAuthRejected()
            throw e
        }
    }

    private suspend fun streamOriginal(): Boolean = settings.streamOriginal.first()

    /**
     * Last.fm-backed endpoints report "no data for this artist" as either an
     * ok-with-missing-payload or Subsonic error 70 depending on the Navidrome
     * version — normalise both to "empty". Auth/network/real server errors
     * still propagate: a transient failure must not be cached as "the server
     * has no Last.fm data".
     */
    private suspend fun <T> emptyWhenMissing(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: SubsonicException.NotFound) {
            emptyList()
        }

    /**
     * Each list's album artists, order preserved, first occurrence wins.
     * Album counts and artwork aren't in the album list; consumers that need
     * them resolve the artist separately.
     */
    private suspend fun playedArtists(
        key: String,
        type: SubsonicClient.AlbumListType,
        size: Int,
    ): List<Artist> =
        cached(key) { client, _ ->
            client.getAlbumList2(type, size)
                .mapNotNull { album ->
                    val artistId = album.artistId ?: return@mapNotNull null
                    val artistName = album.artist ?: return@mapNotNull null
                    Artist(id = artistId, name = artistName, albumCount = 0, artworkUrl = null)
                }
                .distinctBy { it.id }
        }

    /**
     * Artwork is a content URI served by the in-app provider, never the
     * direct server URL — car hosts won't download remote URLs (see
     * [ArtworkContract]).
     */
    private fun artworkUri(coverArtId: String): String =
        ArtworkContract.coverUri(context.packageName, coverArtId)

    private fun ArtistID3.toDomain(sortGroup: String? = null) = Artist(
        id = id,
        name = name,
        albumCount = albumCount,
        // No artistImageUrl fallback: car hosts only render our content://
        // URIs (see ArtworkContract), so a remote URL would show blank.
        artworkUrl = coverArt?.let { artworkUri(it) },
        sortGroup = sortGroup,
    )

    private fun AlbumID3.toDomain() = Album(
        id = id,
        title = name,
        artistName = artist,
        artistId = artistId,
        year = year,
        trackCount = songCount,
        durationSec = duration,
        artworkUrl = coverArt?.let { artworkUri(it) },
        isFavorite = starred != null,
    )

    private fun PlaylistDto.toDomain() = Playlist(
        id = id,
        name = name,
        trackCount = songCount,
        durationSec = duration,
        artworkUrl = coverArt?.let { artworkUri(it) },
    )

    private fun Child.toDomain(urls: SubsonicUrlBuilder) = Track(
        id = id,
        title = title,
        artistName = artist,
        artistId = artistId,
        albumTitle = album,
        albumId = albumId,
        trackNumber = track,
        discNumber = discNumber,
        durationSec = duration,
        artworkUrl = coverArt?.let { artworkUri(it) },
        streamUrl = urls.streamUrl(id),
    )
}
