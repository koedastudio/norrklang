package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.model.Track
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
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : MusicRepository {

    private val cache = TtlCache(ttlMillis = 5 * 60 * 1000L)

    init {
        // Explicit sign-out drops cached library data. Direct account switches
        // use different fingerprint keys, so they cannot reuse another account's
        // data. The TTL prunes those retired keys as new requests arrive.
        scope.launch {
            sessionManager.state.drop(1).collect { state ->
                // Connected caches are already keyed by account fingerprint.
                // Clearing on connect can invalidate that account's first load.
                if (state is SessionManager.SessionState.SignedOut) cache.clear()
            }
        }
    }

    override suspend fun libraries(): List<MusicLibrary> = librariesFor(subsonicSession())

    override suspend fun artists(): List<Artist> =
        scoped("artists") { client, scope ->
            client.getArtists(scope.folderIds).flatMap { index ->
                index.artist.map { it.toDomain(sortGroup = index.name) }
            }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { client ->
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
        scoped("albums/$offset/$size") { client, scope ->
            client.getAlbumList2(
                SubsonicClient.AlbumListType.ALPHABETICAL,
                size,
                offset,
                musicFolderIds = scope.folderIds,
            ).map { it.toDomain() }
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        scoped("recent/$size") { client, scope ->
            client.getAlbumList2(SubsonicClient.AlbumListType.NEWEST, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        scoped("favorites/$size") { client, scope ->
            client.getAlbumList2(SubsonicClient.AlbumListType.STARRED, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun favoriteTracks(): List<Track> =
        scoped("favorite-tracks") { client, scope ->
            client.getStarred2(scope.folderIds).song.map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        scoped("favorite-artists") { client, scope ->
            client.getStarred2(scope.folderIds).artist.map { it.toDomain() }
        }

    // The Subsonic API has no "newest songs" list — flatten the newest albums
    // instead: newest album first, album track order within.
    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        scoped("recently-added-tracks/$size") { client, scope ->
            val albums = client.getAlbumList2(
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
                        chunk.map { album -> async { client.getAlbum(album.id).song } }.awaitAll()
                    }.flatten()
                }
                .take(size)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withScope { client, scope ->
            client.getRandomSongs(size, musicFolderIds = scope.folderIds)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        scoped("recently-played-albums/$size") { client, scope ->
            client.getAlbumList2(SubsonicClient.AlbumListType.RECENT, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        scoped("most-played-albums/$size") { client, scope ->
            client.getAlbumList2(SubsonicClient.AlbumListType.FREQUENT, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    // Unscoped: getGenres ignores musicFolderId, so counts include hidden
    // libraries (empty genre tiles are dropped downstream).
    override suspend fun genres(): List<Genre> =
        cached("genres") { client ->
            client.getGenres().map { Genre(it.value, it.songCount) }
        }

    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        scoped("albums-by-genre/$genre/$size") { client, scope ->
            client.getAlbumList2ByGenre(genre, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        scoped("albums-by-year/$fromYear-$toYear/$size") { client, scope ->
            client.getAlbumList2ByYear(fromYear, toYear, size, musicFolderIds = scope.folderIds)
                .map { it.toDomain() }
        }

    // Uncached like randomTracks: CatalogMixesSession owns list stability.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withScope { client, scope ->
            client.getRandomSongs(size, genre = genre, musicFolderIds = scope.folderIds)
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withScope { client, scope ->
            client.getRandomSongs(
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
        scoped("similar-artists/$artistId") { client, scope ->
            emptyWhenMissing {
                client.getArtistInfo2(artistId, SIMILAR_ARTISTS_FETCH).similarArtist
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
        withScope { client, scope ->
            emptyWhenMissing { client.getSimilarSongs2(artistId, count) }
                .let { songs -> if (scope.isAll) songs else songs.inScope(scope) { it.artistId } }
                .map { it.toDomain(scope.singleLibraryId) }
        }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        cached("top-songs/$artistName/$count") { client ->
            emptyWhenMissing { client.getTopSongs(artistName, count) }
                .map { it.toDomain() }
        }

    // Runs on every track transition (keeps the car's heart button current);
    // served from the TTL cache, which setTrackFavorite clears so the answer
    // never lags a local change. Cached as an id Set for cheap lookups.
    // Deliberately unscoped: a playlist track from a hidden library still
    // needs the right heart.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { client ->
            client.getStarred2().song.mapTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { client ->
            if (favorite) client.star(trackId) else client.unstar(trackId)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { client ->
            if (favorite) client.starAlbum(albumId) else client.unstarAlbum(albumId)
            // Albums carry their starred state (see AlbumID3.starred), so every
            // cached album list is stale the moment a star changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { client ->
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
                tracks = dto.song.map { it.toDomain() },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { client ->
            // `changed` is ISO-8601, so lexicographic order is chronological.
            client.getPlaylists()
                .sortedByDescending { it.changed.orEmpty() }
                .map { it.toDomain() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { client ->
            val dto = client.getPlaylist(id)
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
        cached("track/$id") { client ->
            client.getSong(id).toDomain()
        }

    // Unscoped: stations belong to the server, not a library. Servers
    // predating the endpoint answer NotFound, which reads as "no stations".
    override suspend fun radioStations(): List<RadioStation> =
        cached("radio-stations") { client ->
            emptyWhenMissing { client.getInternetRadioStations() }.map {
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
        scoped("search/$query") { client, scope ->
            val result = client.search3(
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
            val confirmed = cached("album-library/$albumId/$candidate") { client ->
                client.search3(
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
        withSession(expectedSession) { client -> client.scrobble(trackId, submission) }

    override fun invalidateCache() = cache.clear()

    private companion object {
        // Per result type (artists/albums/songs); generous enough that paged
        // search hosts have something to page through.
        const val SEARCH_COUNT_PER_TYPE = 50

        /** Similar-artists batch fetched per seed; callers take what they need. */
        const val SIMILAR_ARTISTS_FETCH = 50

        /** Parallel getAlbum calls per batch when flattening the newest albums. */
        const val RECENT_TRACKS_FETCH_CONCURRENCY = 10

        /** Albums fetched per library when confirming a track's library by album title. */
        const val LIBRARY_PROBE_ALBUM_COUNT = 100
    }

    private fun subsonicSession(): SubsonicSession =
        sessionManager.connectedOrNull()?.session as? SubsonicSession
            ?: throw MusicException.AuthFailed("Not signed in")

    private suspend fun <T : Any> cached(
        key: String,
        loader: suspend (SubsonicClient) -> T,
    ): T {
        val session = subsonicSession()
        val scopedKey = "${session.cacheFingerprint}/$key"
        // The loader uses the SAME session snapshot the key was computed from:
        // a re-read could store one account's data under another's fingerprint
        // if an account switch lands between the two reads.
        return cache.getOrLoad(scopedKey) {
            translatingErrors(session) {
                loader(session.client)
            }
        }
    }

    private suspend fun <T> withSession(
        expectedSession: ProviderSession? = null,
        block: suspend (SubsonicClient) -> T,
    ): T {
        val session = subsonicSession()
        if (expectedSession != null && session !== expectedSession) {
            throw MusicException.AuthFailed("Playback account changed")
        }
        return translatingErrors(session) {
            block(session.client)
        }
    }

    /** Like [cached], with the library scope folded into the key — no explicit invalidation needed. */
    private suspend fun <T : Any> scoped(
        key: String,
        loader: suspend (SubsonicClient, LibraryScope) -> T,
    ): T {
        val session = subsonicSession()
        val scope = scope(session)
        return cache.getOrLoad("${session.cacheFingerprint}/${scope.key}/$key") {
            translatingErrors(session) {
                loader(session.client, scope)
            }
        }
    }

    /** Like [withSession], resolving the library scope for the uncached random/similar paths. */
    private suspend fun <T> withScope(
        expectedSession: ProviderSession? = null,
        block: suspend (SubsonicClient, LibraryScope) -> T,
    ): T {
        val session = subsonicSession()
        if (expectedSession != null && session !== expectedSession) {
            throw MusicException.AuthFailed("Playback account changed")
        }
        val scope = scope(session)
        return translatingErrors(session) {
            block(session.client, scope)
        }
    }

    // Nothing excluded skips getMusicFolders entirely: today's exact requests
    // for everyone who never touched the setting (non-Navidrome servers too).
    private suspend fun scope(session: SubsonicSession): LibraryScope {
        val excluded = settings.excludedLibraryIds.first()
        if (excluded.isEmpty()) return LibraryScope.resolve(emptyList(), emptySet())
        return LibraryScope.resolve(librariesFor(session), excluded)
    }

    private suspend fun librariesFor(session: SubsonicSession): List<MusicLibrary> =
        cache.getOrLoad("${session.cacheFingerprint}/libraries") {
            translatingErrors(session) {
                session.client.getMusicFolders().map { MusicLibrary(it.id.toString(), it.name) }
            }
        }

    /** Empty means "all", which omits musicFolderId (see [SubsonicClient.getArtists]). */
    private val LibraryScope.folderIds: List<String>
        get() = if (isAll) emptyList() else selectedIds

    /** Tracks are stamped only when exactly one library is in scope. */
    private val LibraryScope.singleLibraryId: String?
        get() = selected.singleOrNull()?.id

    /** Artist ids in the given libraries, cached — the scope's set for `!isAll`, or one candidate. */
    private suspend fun artistIdsIn(folderIds: List<String>): Set<String> =
        cached("artist-ids/${folderIds.sorted().joinToString("+")}") { client ->
            client.getArtists(folderIds).flatMap { it.artist }.mapTo(HashSet()) { it.id }
        }

    private suspend fun <T> List<T>.inScope(scope: LibraryScope, artistId: (T) -> String?): List<T> {
        val ids = artistIdsIn(scope.selectedIds)
        return filter { artistId(it) in ids }
    }

    /**
     * The [MusicException] boundary: nothing above core-data sees a
     * [SubsonicException]. Auth rejections also flip the session state.
     */
    private suspend fun <T> translatingErrors(session: SubsonicSession, block: suspend () -> T): T =
        try {
            block()
        } catch (e: SubsonicException) {
            if (e is SubsonicException.AuthFailed) sessionManager.onAuthRejected(session)
            throw e.toMusicException()
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
        scoped(key) { client, scope ->
            client.getAlbumList2(type, size, musicFolderIds = scope.folderIds)
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
