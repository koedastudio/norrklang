package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.di.AppPackageName
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.AlbumDetail
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.ArtistDetail
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.LibraryScope
import studio.koeda.norrklang.data.model.MusicLibrary
import studio.koeda.norrklang.data.model.Playlist
import studio.koeda.norrklang.data.model.PlaylistDetail
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.ReleaseType
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository.Companion.SEARCH_COUNT_PER_TYPE
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.session.SubsonicSession
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicException
import studio.koeda.norrklang.subsonic.model.AlbumID3
import studio.koeda.norrklang.subsonic.model.ArtistID3
import studio.koeda.norrklang.subsonic.model.Child
import studio.koeda.norrklang.subsonic.model.Playlist as PlaylistDto

/**
 * Subsonic/Navidrome backend. Browse, search and mix calls go through
 * [scoped]/[withScope], which pass the user's selected libraries as
 * `musicFolderId`; by-id lookups, playlists and favourites stay unscoped.
 */
@Singleton
class SubsonicMusicRepository @Inject constructor(
    sessionManager: SessionManager,
    settings: ServerSettingsRepository,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : ProviderMusicRepository<SubsonicSession>(sessionManager, settings, scope) {

    override fun sessionOf(session: ProviderSession) = session as? SubsonicSession

    override fun translate(e: Exception) = (e as? SubsonicException)?.toMusicException()

    override suspend fun loadLibraries(session: SubsonicSession) =
        session.client.getMusicFolders().map { MusicLibrary(it.id.toString(), it.name) }

    // Nothing excluded skips getMusicFolders entirely: today's exact requests
    // for everyone who never touched the setting (non-Navidrome servers too).
    override suspend fun scope(session: SubsonicSession): LibraryScope {
        val excluded = settings.excludedLibraryIds.first()
        if (excluded.isEmpty()) return LibraryScope.resolve(emptyList(), emptySet())
        return LibraryScope.resolve(librariesFor(session), excluded)
    }

    override suspend fun artists(): List<Artist> =
        scoped("artists") { session, scope ->
            session.client.getArtists(scope.folderIds).flatMap { index ->
                index.artist.map { it.toDomain(sortGroup = index.name) }
            }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { session ->
            val dto = session.client.getArtist(id)
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
        scoped("albums/$offset/$size") { session, scope ->
            session.client.getAlbumList2(
                SubsonicClient.AlbumListType.ALPHABETICAL,
                size,
                offset,
                musicFolderIds = scope.folderIds,
            ).map { it.toDomain() }
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        scoped("recent/$size") { session, scope ->
            session.client.getAlbumList2(SubsonicClient.AlbumListType.NEWEST, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        scoped("favorites/$size") { session, scope ->
            session.client.getAlbumList2(SubsonicClient.AlbumListType.STARRED, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun favoriteTracks(): List<Track> =
        scoped("favorite-tracks") { session, scope ->
            session.client.getStarred2(scope.folderIds).song.map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        scoped("favorite-artists") { session, scope ->
            session.client.getStarred2(scope.folderIds).artist.map { it.toDomain() }
        }

    // The Subsonic API has no "newest songs" list — flatten the newest albums
    // instead: newest album first, album track order within.
    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        scoped("recently-added-tracks/$size") { session, scope ->
            val albums = session.client.getAlbumList2(
                SubsonicClient.AlbumListType.NEWEST,
                size,
                musicFolderIds = scope.folderIds,
            )
            // Only the album prefix that can fill the list is fetched; the
            // maxOf guards a missing songCount from stalling the cut-off.
            var remaining = size
            val needed = albums.takeWhile { album ->
                (remaining > 0).also { remaining -= maxOf(album.songCount, 1) }
            }
            needed.chunked(RECENT_TRACKS_FETCH_CONCURRENCY)
                .flatMap { chunk ->
                    coroutineScope {
                        chunk.map { album -> async { session.client.getAlbum(album.id).song } }.awaitAll()
                    }.flatten()
                }
                .take(size)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withScope { session, scope ->
            session.client.getRandomSongs(size, musicFolderIds = scope.folderIds)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun randomAlbums(size: Int): List<Album> =
        withScope { session, scope ->
            session.client.getAlbumList2(SubsonicClient.AlbumListType.RANDOM, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        scoped("recently-played-albums/$size") { session, scope ->
            session.client.getAlbumList2(SubsonicClient.AlbumListType.RECENT, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        scoped("most-played-albums/$size") { session, scope ->
            session.client.getAlbumList2(SubsonicClient.AlbumListType.FREQUENT, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    // Unscoped: getGenres ignores musicFolderId, so counts include hidden
    // libraries (empty genre tiles are dropped downstream).
    override suspend fun genres(): List<Genre> =
        cached("genres") { session ->
            session.client.getGenres().map { Genre(it.value, it.songCount) }
        }

    override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): List<Album> =
        scoped("albums-by-genre/$genre/$offset/$size") { session, scope ->
            session.client.getAlbumList2ByGenre(genre, size, offset, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun albumsByYearRange(
        fromYear: Int,
        toYear: Int,
        offset: Int,
        size: Int,
    ): List<Album> =
        scoped("albums-by-year/$fromYear-$toYear/$offset/$size") { session, scope ->
            session.client.getAlbumList2ByYear(fromYear, toYear, size, offset, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    // Uncached like randomTracks: CatalogMixes owns list stability.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withScope { session, scope ->
            session.client.getRandomSongs(size, genre = genre, musicFolderIds = scope.folderIds)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withScope { session, scope ->
            session.client.getRandomSongs(
                size,
                fromYear = fromYear,
                toYear = toYear,
                musicFolderIds = scope.folderIds,
            ).map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", SubsonicClient.AlbumListType.FREQUENT, size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", SubsonicClient.AlbumListType.RECENT, size)

    // Fetches a canonical batch and trims outside the cache, like the Plex
    // side: one entry serves every count, and the in-library filter below
    // has headroom to still fill the request. getArtistInfo2 ignores
    // musicFolderId, so hidden libraries are filtered out here.
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        scoped("similar-artists/$artistId") { session, scope ->
            emptyWhenMissing {
                session.client.getArtistInfo2(artistId, SIMILAR_ARTISTS_FETCH).similarArtist
            }
                // Similar artists not in the library come back with a synthetic
                // id and no albums — useless as mix sources, drop them.
                .filter { it.albumCount > 0 }
                .let { artists -> if (scope.isAll) artists else artists.inScope(scope) { it.id } }
                .map { it.toDomain() }
        }.take(count)

    // Uncached like randomTracks: getSimilarSongs2 has a random component and
    // SimilarMixesSession owns list stability. Post-filtered like similarArtists.
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> =
        withScope { session, scope ->
            emptyWhenMissing { session.client.getSimilarSongs2(artistId, count) }
                .let { songs -> if (scope.isAll) songs else songs.inScope(scope) { it.artistId } }
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        cached("top-songs/$artistName/$count") { session ->
            emptyWhenMissing { session.client.getTopSongs(artistName, count) }
                .map { it.toDomain() }
        }

    // Runs on every track transition (keeps the car's heart button current);
    // served from the TTL cache, which setTrackFavorite clears so the answer
    // never lags a local change. Cached as an id Set for cheap lookups.
    // Deliberately unscoped: a playlist track from a hidden library still
    // needs the right heart.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { session ->
            session.client.getStarred2().song.mapTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { session ->
            if (favorite) session.client.star(trackId) else session.client.unstar(trackId)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { session ->
            if (favorite) session.client.starAlbum(albumId) else session.client.unstarAlbum(albumId)
            // Albums carry their starred state (see AlbumID3.starred), so every
            // cached album list is stale the moment a star changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { session ->
            val dto = session.client.getAlbum(id)
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
                tracks = dto.song.map { it.toDomain() },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { session ->
            // `changed` is ISO-8601, so lexicographic order is chronological.
            session.client.getPlaylists()
                .sortedByDescending { it.changed.orEmpty() }
                .map { it.toDomain() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { session ->
            val dto = session.client.getPlaylist(id)
            PlaylistDetail(
                playlist = Playlist(
                    id = dto.id,
                    name = dto.name,
                    trackCount = dto.songCount,
                    durationSec = dto.duration,
                    artworkUrl = dto.coverArt?.let { artworkUri(it) },
                ),
                tracks = dto.entry.map { it.toDomain() },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { session ->
            session.client.getSong(id).toDomain()
        }

    // Unscoped: stations belong to the server, not a library. Servers
    // predating the endpoint answer NotFound, which reads as "no stations".
    override suspend fun radioStations(): List<RadioStation> =
        cached("radio-stations") { session ->
            emptyWhenMissing { session.client.getInternetRadioStations() }.map {
                RadioStation(
                    id = it.id,
                    name = it.name,
                    streamUrl = it.streamUrl,
                    homePageUrl = it.homePageUrl,
                    artworkUrl = it.coverArt?.takeIf(String::isNotBlank)?.let { id -> artworkUri(id) },
                )
            }
        }

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        scoped("search/$query") { session, scope ->
            val result = session.client.search3(
                query,
                count = SEARCH_COUNT_PER_TYPE,
                musicFolderIds = scope.folderIds,
            )
            SearchResults(
                artists = result.artist.map { it.toDomain() },
                albums = result.album.map { it.toDomain() },
                tracks = result.song.map { it.toDomain(scope.singleLibraryId) },
            )
        }

    // Child carries no library id, so membership is probed per candidate:
    // artist index as a cheap negative filter, then an album search in that
    // library confirms. Both cached; null = unknown (scrobble allowed).
    override suspend fun trackLibraryId(trackId: String, candidates: Set<String>): String? {
        val song = track(trackId)
        val artistId = song.artistId ?: return null
        val albumId = song.albumId ?: return null
        val albumTitle = song.albumTitle ?: return null
        for (candidate in candidates) {
            if (artistId !in artistIdsIn(listOf(candidate))) continue
            val confirmed = cached("album-library/$albumId/$candidate") { session ->
                session.client.search3(
                    query = albumTitle,
                    artistCount = 0,
                    albumCount = LIBRARY_PROBE_ALBUM_COUNT,
                    songCount = 0,
                    musicFolderIds = listOf(candidate),
                ).album.any { it.id == albumId }
            }
            if (confirmed) return candidate
        }
        return null
    }

    override suspend fun scrobble(
        trackId: String,
        submission: Boolean,
        expectedSession: ProviderSession?,
    ) =
        withSession(expectedSession) { session -> session.client.scrobble(trackId, submission) }

    private companion object {
        /** Similar-artists batch fetched per seed; callers take what they need. */
        const val SIMILAR_ARTISTS_FETCH = 50

        /** Parallel getAlbum calls per batch when flattening the newest albums. */
        const val RECENT_TRACKS_FETCH_CONCURRENCY = 10

        /** Albums fetched per library when confirming a track's library by album title. */
        const val LIBRARY_PROBE_ALBUM_COUNT = 100
    }

    /** Empty means "all", which omits musicFolderId (see [SubsonicClient.getArtists]). */
    private val LibraryScope.folderIds: List<String>
        get() = if (isAll) emptyList() else selectedIds

    /** Tracks are stamped only when exactly one library is in scope. */
    private val LibraryScope.singleLibraryId: String?
        get() = selected.singleOrNull()?.id

    /** Artist ids in the given libraries, cached — the scope's set for `!isAll`, or one candidate. */
    private suspend fun artistIdsIn(folderIds: List<String>): Set<String> =
        cached("artist-ids/${folderIds.sorted().joinToString("+")}") { session ->
            session.client.getArtists(folderIds).flatMap { it.artist }.mapTo(HashSet()) { it.id }
        }

    private suspend fun <T> List<T>.inScope(scope: LibraryScope, artistId: (T) -> String?): List<T> {
        val ids = artistIdsIn(scope.selectedIds)
        return filter { artistId(it) in ids }
    }

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
        scoped(key) { session, scope ->
            session.client.getAlbumList2(type, size, musicFolderIds = scope.folderIds)
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
        ArtworkContract.coverUri(packageName, coverArtId)

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
        releaseType = ReleaseType.fromTags(releaseTypes, isCompilation),
    )

    private fun PlaylistDto.toDomain() = Playlist(
        id = id,
        name = name,
        trackCount = songCount,
        durationSec = duration,
        artworkUrl = coverArt?.let { artworkUri(it) },
    )

    private fun Child.toDomain(libraryId: String? = null) = Track(
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
        streamUrl = StreamRef(MusicProvider.SUBSONIC, id).encode(),
        libraryId = libraryId,
    )
}
