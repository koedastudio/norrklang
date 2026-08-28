package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.di.AppPackageName
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
import studio.koeda.norrklang.data.session.JellyfinSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClient.Companion.TICKS_PER_MS
import studio.koeda.norrklang.jellyfin.JellyfinException
import studio.koeda.norrklang.jellyfin.JellyfinUrlBuilder
import studio.koeda.norrklang.jellyfin.model.JellyfinItem
import studio.koeda.norrklang.jellyfin.model.JellyfinPlaybackBody

/**
 * [MusicRepository] backed by a Jellyfin server, browsing one music library.
 *
 * Semantics mapping (see the Subsonic sibling for the reference behavior):
 *  - favorites ↔ the per-user `IsFavorite` flag (Jellyfin's heart)
 *  - played/most-played albums and artists are derived from played TRACKS:
 *    track playback does not update album-level UserData, and `IsPlayed` on
 *    a folder means "all children played" — unusable as a history signal
 *  - similar artists ↔ `/Items/{id}/Similar` (server-computed from shared
 *    genres/tags, in-library by construction); similar tracks are
 *    synthesized as random tracks across the seed and its similar artists
 *  - track ids ARE Jellyfin item ids; artwork ids ARE the item id owning
 *    the primary image
 */
@Singleton
class JellyfinMusicRepository @Inject constructor(
    private val sessionManager: SessionManager,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : MusicRepository {

    private val cache = TtlCache(ttlMillis = 5 * 60 * 1000L)

    // The open play session reported to /Sessions/Playing; reportPlayState is
    // state-only, so the repository tracks which item the start was sent for.
    @Volatile
    private var startedItemId: String? = null

    init {
        // Cached entries embed authenticated stream URLs and must never
        // survive a sign-out or account switch (see SubsonicMusicRepository).
        scope.launch {
            sessionManager.state.drop(1).collect { cache.clear() }
        }
    }

    override suspend fun artists(): List<Artist> =
        cached("artists") { client, _, account ->
            client.albumArtists(account.userId, account.libraryId)
                .mapNotNull { it.toArtistOrNull(sortGroup = it.sortBucket()) }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { client, _, account ->
            val dto = client.item(account.userId, id)
            ArtistDetail(
                artist = dto.toArtistOrNull()
                    ?: throw MusicException.NotFound("Artist $id has no id"),
                // Newest first, like the Subsonic side; unknown years sink.
                albums = client.items(
                    account.userId,
                    parentId = account.libraryId,
                    includeItemTypes = "MusicAlbum",
                    params = listOf("AlbumArtistIds" to id),
                ).items.mapNotNull { it.toAlbumOrNull() }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    override suspend fun albums(offset: Int, size: Int): List<Album> =
        cached("albums/$offset/$size") { client, _, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "MusicAlbum",
                sortBy = "SortName",
                startIndex = offset,
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        cached("recent/$size") { client, _, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "MusicAlbum",
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        cached("favorites/$size") { client, _, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "MusicAlbum",
                filters = IS_FAVORITE,
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun favoriteTracks(): List<Track> =
        cached("favorite-tracks") { client, urls, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                filters = IS_FAVORITE,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        cached("favorite-artists") { client, _, account ->
            client.albumArtists(account.userId, account.libraryId, isFavorite = true)
                .mapNotNull { it.toArtistOrNull() }
        }

    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        cached("recently-added-tracks/$size") { client, urls, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                sortBy = "DateCreated",
                sortOrder = "Descending",
                limit = size,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withSession { client, urls, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                sortBy = "Random",
                limit = size,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        playedAlbums("recently-played-albums/$size", "DatePlayed", size)

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        playedAlbums("most-played-albums/$size", "PlayCount", size)

    override suspend fun genres(): List<Genre> =
        cached("genres") { client, _, account ->
            // Jellyfin's genre list has no song counts, but the home tab's
            // genre mixes rank and threshold on them — count each genre's
            // tracks with a zero-size page (TotalRecordCount only, so the
            // fan-out is cheap; OkHttp's per-host cap keeps it polite).
            coroutineScope {
                client.genres(account.userId, account.libraryId).map { genre ->
                    async {
                        // One flaky probe degrades its genre to count 0
                        // instead of failing the whole genre list.
                        val count = try {
                            genre.id?.let { genreId ->
                                client.items(
                                    account.userId,
                                    parentId = account.libraryId,
                                    includeItemTypes = "Audio",
                                    params = listOf("GenreIds" to genreId),
                                    limit = 0,
                                ).totalRecordCount
                            } ?: 0
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: JellyfinException) {
                            0
                        }
                        Genre(genre.name, songCount = count)
                    }
                }.awaitAll()
            }
        }

    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        cached("albums-by-genre/$genre/$size") { client, _, account ->
            val id = genreId(genre) ?: return@cached emptyList()
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "MusicAlbum",
                params = listOf("GenreIds" to id),
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        cached("albums-by-year/$fromYear-$toYear/$size") { client, _, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "MusicAlbum",
                params = listOf("Years" to yearList(fromYear, toYear)),
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }

    // Uncached like randomTracks: CatalogMixesSession owns list stability.
    // Track-level genre works here — Jellyfin aggregates album genres from
    // the track tags, so tracks carry them.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withSession { client, urls, account ->
            val id = genreId(genre) ?: return@withSession emptyList()
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                params = listOf("GenreIds" to id),
                sortBy = "Random",
                limit = size,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withSession { client, urls, account ->
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                params = listOf("Years" to yearList(fromYear, toYear)),
                sortBy = "Random",
                limit = size,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", "PlayCount", size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", "DatePlayed", size)

    // Server-computed from shared genres/tags — present whenever the library
    // is tagged; in-library by construction (the contract Subsonic reaches by
    // filtering albumCount).
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        cached("similar-artists/$artistId") { client, _, account ->
            client.similar(account.userId, artistId, limit = SIMILAR_ARTIST_FETCH)
                .filter { it.type == "MusicArtist" && it.id != artistId }
                .distinctBy { it.id }
                .mapNotNull { it.toArtistOrNull() }
        }.take(count)

    // Uncached like randomTracks: SimilarMixesSession owns list stability.
    // Empty when the artist has no similar artists — the "no similarity
    // data" signal the mix sessions' dead-server bail-out relies on. NOT
    // InstantMix: that returns tracks even with zero similarity data, which
    // would defeat the empty-list contract.
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
        val similar = similarArtists(artistId, SIMILAR_TRACK_ARTISTS)
        if (similar.isEmpty()) return emptyList()
        return withSession { client, urls, account ->
            // Comma-separated ArtistIds are OR'd by Jellyfin: one request
            // draws random tracks across the seed and all similar artists.
            val ids = (listOf(artistId) + similar.map { it.id }).joinToString(",")
            client.items(
                account.userId,
                parentId = account.libraryId,
                includeItemTypes = "Audio",
                params = listOf("ArtistIds" to ids),
                sortBy = "Random",
                limit = count,
            ).items.mapNotNull { it.toTrackOrNull(urls) }
        }
    }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        cached("top-songs/$artistName/$count") { client, urls, account ->
            // The contract keys by artist NAME (a Subsonic API quirk); resolve
            // to an item id first.
            val artist = client
                .albumArtists(account.userId, account.libraryId, searchTerm = artistName)
                .firstOrNull { it.name.equals(artistName, ignoreCase = true) }
            val artistId = artist?.id ?: return@cached emptyList()

            suspend fun tier(filters: List<String>, sortBy: String?) =
                client.items(
                    account.userId,
                    parentId = account.libraryId,
                    includeItemTypes = "Audio",
                    filters = filters,
                    params = listOf("ArtistIds" to artistId),
                    sortBy = sortBy,
                    sortOrder = sortBy?.let { "Descending" },
                    limit = count,
                ).items.mapNotNull { it.toTrackOrNull(urls) }

            // "Best of" blends the account's signals, strongest first: the
            // user's favorites, then their own play counts. Jellyfin has no
            // global-popularity signal (nothing like Plex's ratingCount).
            // Later tiers are only fetched while the list runs short; both
            // empty means no data — the "hide the mix" signal upstream.
            val tiers = listOf(
                IS_FAVORITE to null,
                IS_PLAYED to "PlayCount",
            )
            val blended = LinkedHashMap<String, Track>()
            for ((filters, sortBy) in tiers) {
                if (blended.size >= count) break
                for (track in tier(filters, sortBy)) blended.putIfAbsent(track.id, track)
            }
            blended.values.take(count)
        }

    // Runs on every track transition (keeps the car's heart button current);
    // served from the TTL cache, which setTrackFavorite clears so the answer
    // never lags a local change. Cached as an id Set for cheap lookups.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { _, _, _ ->
            favoriteTracks().mapTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { client, _, account ->
            client.setFavorite(account.userId, trackId, favorite)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { client, _, account ->
            client.setFavorite(account.userId, albumId, favorite)
            // Albums carry their favorite state; every cached album list is
            // stale the moment the flag changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { client, urls, account ->
            val dto = client.item(account.userId, id)
            AlbumDetail(
                album = dto.toAlbumOrNull()
                    ?: throw MusicException.NotFound("Album $id has no id"),
                tracks = client.items(
                    account.userId,
                    parentId = id,
                    includeItemTypes = "Audio",
                    recursive = false,
                    sortBy = "ParentIndexNumber,IndexNumber,SortName",
                ).items.mapNotNull { it.toTrackOrNull(urls) },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { client, _, account ->
            // Playlists live outside the music library, so no ParentId here;
            // MediaType keeps video playlists out.
            client.items(
                account.userId,
                includeItemTypes = "Playlist",
                sortBy = "SortName",
            ).items.filter { it.mediaType == "Audio" }
                .mapNotNull { it.toPlaylistOrNull() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { client, urls, account ->
            val dto = client.item(account.userId, id)
            PlaylistDetail(
                playlist = dto.toPlaylistOrNull()
                    ?: throw MusicException.NotFound("Playlist $id not found"),
                tracks = client.playlistItems(account.userId, id)
                    .mapNotNull { it.toTrackOrNull(urls) },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { client, urls, account ->
            client.item(account.userId, id).toTrackOrNull(urls)
                ?: throw MusicException.NotFound("Track $id has no id")
        }

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        cached("search/$query") { client, urls, account ->
            coroutineScope {
                val artists = async {
                    client.albumArtists(
                        account.userId,
                        account.libraryId,
                        searchTerm = query,
                        limit = SEARCH_COUNT_PER_TYPE,
                    ).mapNotNull { it.toArtistOrNull() }
                }
                suspend fun items(type: String) = client.items(
                    account.userId,
                    parentId = account.libraryId,
                    includeItemTypes = type,
                    params = listOf("SearchTerm" to query),
                    limit = SEARCH_COUNT_PER_TYPE,
                ).items
                val albums = async { items("MusicAlbum").mapNotNull { it.toAlbumOrNull() } }
                val tracks = async { items("Audio").mapNotNull { it.toTrackOrNull(urls) } }
                SearchResults(
                    artists = artists.await(),
                    albums = albums.await(),
                    tracks = tracks.await(),
                )
            }
        }

    override suspend fun scrobble(trackId: String, submission: Boolean) =
        withSession { client, _, account ->
            if (submission) {
                // Deterministic mark-played: with session-less direct play the
                // app owns the "counts as played" threshold, not the server.
                client.markPlayed(account.userId, trackId)
            } else {
                sendPlaybackStart(client, trackId)
            }
        }

    override val playbackReportIntervalMs: Long = PROGRESS_INTERVAL_MS

    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
    ) = withSession { client, _, _ ->
        val body = JellyfinPlaybackBody(
            itemId = trackId,
            positionTicks = positionMs * TICKS_PER_MS,
            isPaused = state == PlayState.PAUSED,
        )
        when (state) {
            // The Sessions API is start/progress/stopped but this contract is
            // state-only: a report for an item the server was never told about
            // (play edge racing scrobble(false), or scrobbling disabled — the
            // settings filter suppresses both channels together) opens the
            // session first. PlaybackReporter serializes reports, so the
            // start/progress order holds.
            PlayState.PLAYING, PlayState.PAUSED -> {
                if (startedItemId != trackId) sendPlaybackStart(client, trackId)
                client.reportPlaybackProgress(body)
            }

            PlayState.STOPPED -> {
                client.reportPlaybackStopped(body)
                startedItemId = null
            }
        }
    }

    private suspend fun sendPlaybackStart(client: JellyfinClient, trackId: String) {
        client.reportPlaybackStart(JellyfinPlaybackBody(itemId = trackId, positionTicks = 0))
        startedItemId = trackId
    }

    override fun invalidateCache() = cache.clear()

    private fun jellyfinSession(): JellyfinSession =
        sessionManager.connectedOrNull()?.session as? JellyfinSession
            ?: throw MusicException.AuthFailed("Not signed in")

    private suspend fun <T : Any> cached(
        key: String,
        loader: suspend (JellyfinClient, JellyfinUrlBuilder, JellyfinAccount) -> T,
    ): T {
        val session = jellyfinSession()
        val scopedKey = "${session.cacheFingerprint}/$key"
        // The loader uses the SAME session snapshot the key was computed from
        // (see SubsonicMusicRepository.cached).
        return cache.getOrLoad(scopedKey) {
            translatingErrors {
                loader(session.client, session.urlBuilder, session.account)
            }
        }
    }

    private suspend fun <T> withSession(
        block: suspend (JellyfinClient, JellyfinUrlBuilder, JellyfinAccount) -> T,
    ): T {
        val session = jellyfinSession()
        return translatingErrors {
            block(session.client, session.urlBuilder, session.account)
        }
    }

    /**
     * The [MusicException] boundary: nothing above core-data sees a
     * [JellyfinException]. Auth rejections also flip the session state.
     */
    private suspend fun <T> translatingErrors(block: suspend () -> T): T =
        try {
            block()
        } catch (e: JellyfinException) {
            if (e is JellyfinException.AuthFailed) sessionManager.onAuthRejected()
            throw e.toMusicException()
        }

    /**
     * Jellyfin's only year filter is `Years` — an exact-match comma list on
     * ProductionYear (min/max bounds exist only for PremiereDate, which music
     * rarely carries). A decade range is just ten values.
     */
    private fun yearList(from: Int, to: Int): String =
        (from..to).joinToString(",")

    /** Case-insensitive genre name → GenreIds value, via the cached directory. */
    private suspend fun genreId(name: String): String? =
        cached("genre-ids") { client, _, account ->
            client.genres(account.userId, account.libraryId)
                .mapNotNull { g -> g.id?.let { g.name.lowercase() to it } }
                .toMap()
        }[name.lowercase()]

    /** The played tracks that back every played-albums/artists derivation. */
    private suspend fun playedTracks(
        client: JellyfinClient,
        account: JellyfinAccount,
        sortBy: String,
        size: Int,
    ): List<JellyfinItem> =
        client.items(
            account.userId,
            parentId = account.libraryId,
            includeItemTypes = "Audio",
            filters = IS_PLAYED,
            sortBy = sortBy,
            sortOrder = "Descending",
            // Overfetch: many played tracks share an album, and the distinct
            // pass below must still fill the requested page.
            limit = size * PLAYED_OVERFETCH,
        ).items

    /**
     * Albums synthesized from the played-track history, order preserved,
     * first occurrence wins. Track counts are unknown (0) — same policy as
     * the artist synthesis.
     */
    private suspend fun playedAlbums(key: String, sortBy: String, size: Int): List<Album> =
        cached(key) { client, _, account ->
            playedTracks(client, account, sortBy, size)
                .mapNotNull { track ->
                    val albumId = track.albumId ?: return@mapNotNull null
                    Album(
                        id = albumId,
                        title = track.album ?: return@mapNotNull null,
                        artistName = track.albumArtist
                            ?: track.albumArtists.firstOrNull()?.name,
                        artistId = track.albumArtists.firstOrNull()?.id,
                        year = track.productionYear,
                        trackCount = 0,
                        durationSec = 0,
                        artworkUrl = track.albumPrimaryImageTag?.let { artworkUri(albumId) },
                        isFavorite = false,
                    )
                }
                .distinctBy { it.id }
                .take(size)
        }

    /** Artists behind the played tracks, order preserved, first occurrence wins. */
    private suspend fun playedArtists(key: String, sortBy: String, size: Int): List<Artist> =
        cached(key) { client, _, account ->
            playedTracks(client, account, sortBy, size)
                .mapNotNull { track ->
                    val artist = track.albumArtists.firstOrNull() ?: return@mapNotNull null
                    Artist(id = artist.id, name = artist.name, albumCount = 0, artworkUrl = null)
                }
                .distinctBy { it.id }
                .take(size)
        }

    /**
     * Artwork is a content URI served by the in-app provider, never the
     * direct server URL — car hosts won't download remote URLs (see
     * [ArtworkContract]). Jellyfin item ids ride through as opaque ids.
     */
    private fun artworkUri(itemId: String): String =
        ArtworkContract.coverUri(packageName, itemId)

    private fun JellyfinItem.sortBucket(): String {
        val first = (sortName ?: name).trim().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    // Items without a primary image map to null artwork instead of a URL the
    // provider would 404 on. Tracks borrow the album's image, like every
    // other client.
    private fun JellyfinItem.artworkOrNull(): String? = when {
        imageTags.containsKey("Primary") -> id?.let { artworkUri(it) }
        albumPrimaryImageTag != null -> albumId?.let { artworkUri(it) }
        else -> null
    }

    // Entries without an id are not addressable (detail lookups would 404 on
    // an empty id), so the mappers drop them — same policy as the Plex side.
    private fun JellyfinItem.toArtistOrNull(sortGroup: String? = null): Artist? = Artist(
        id = id ?: return null,
        name = name,
        albumCount = childCount ?: 0,
        artworkUrl = artworkOrNull(),
        sortGroup = sortGroup,
    )

    private fun JellyfinItem.toAlbumOrNull(): Album? = Album(
        id = id ?: return null,
        title = name,
        artistName = albumArtist ?: albumArtists.firstOrNull()?.name,
        artistId = albumArtists.firstOrNull()?.id,
        year = productionYear,
        trackCount = childCount ?: 0,
        durationSec = ((runTimeTicks ?: 0L) / TICKS_PER_SEC).toInt(),
        artworkUrl = artworkOrNull(),
        isFavorite = userData?.isFavorite == true,
    )

    private fun JellyfinItem.toPlaylistOrNull(): Playlist? = Playlist(
        id = id ?: return null,
        name = name,
        trackCount = childCount ?: 0,
        durationSec = ((runTimeTicks ?: 0L) / TICKS_PER_SEC).toInt(),
        artworkUrl = artworkOrNull(),
    )

    /** Every Audio item is streamable via the stream endpoint — no part gate. */
    private fun JellyfinItem.toTrackOrNull(urls: JellyfinUrlBuilder): Track? {
        val id = id ?: return null
        return Track(
            id = id,
            title = name,
            artistName = artistItems.firstOrNull()?.name ?: albumArtist,
            artistId = artistItems.firstOrNull()?.id
                ?: albumArtists.firstOrNull()?.id,
            albumTitle = album,
            albumId = albumId,
            trackNumber = indexNumber,
            discNumber = parentIndexNumber,
            durationSec = runTimeTicks?.let { (it / TICKS_PER_SEC).toInt() },
            artworkUrl = artworkOrNull(),
            streamUrl = urls.streamUrl(id),
        )
    }

    private companion object {
        /** Runtime ticks (100 ns) per second. */
        const val TICKS_PER_SEC = 10_000_000L

        /** What jellyfin-web uses for progress reports while playing. */
        const val PROGRESS_INTERVAL_MS = 10_000L

        /**
         * Similar artists pooled into one similarTracks selection; with the
         * seed, matches SimilarMixesSession's 10-artist mix spread.
         */
        const val SIMILAR_TRACK_ARTISTS = 9

        /** One fetch serves every similarArtists(count<=this) via the cache. */
        const val SIMILAR_ARTIST_FETCH = 18

        /**
         * Played-track pages fetched per derived album/artist slot: many
         * played tracks share an album, and the distinct pass must still
         * fill the page.
         */
        const val PLAYED_OVERFETCH = 10

        /**
         * Paged hosts need something to page through; same cap as the
         * Subsonic side (SubsonicMusicRepository.SEARCH_COUNT_PER_TYPE).
         */
        const val SEARCH_COUNT_PER_TYPE = 50

        val IS_FAVORITE = listOf("IsFavorite")
        val IS_PLAYED = listOf("IsPlayed")
    }
}
