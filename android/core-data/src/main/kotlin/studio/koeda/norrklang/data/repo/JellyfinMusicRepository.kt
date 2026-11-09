package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository.Companion.SEARCH_COUNT_PER_TYPE
import studio.koeda.norrklang.data.repo.MusicRepository.Companion.SIMILAR_TRACK_ARTISTS
import studio.koeda.norrklang.data.session.JellyfinSession
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClient.ItemType
import studio.koeda.norrklang.jellyfin.model.JellyfinItemsResult
import studio.koeda.norrklang.jellyfin.JellyfinClient.Companion.TICKS_PER_MS
import studio.koeda.norrklang.jellyfin.JellyfinException
import studio.koeda.norrklang.jellyfin.model.JellyfinItem
import studio.koeda.norrklang.jellyfin.model.JellyfinPlaybackBody

/**
 * [MusicRepository] backed by a Jellyfin server, browsing the selected music
 * libraries (see [LibraryScope]).
 *
 * Jellyfin scopes a query to one library via `ParentId`, so every browse
 * call fans out over the selected libraries and merges — also for "all":
 * omitting `ParentId` would pull stray audio from mixed/non-music views.
 * With one selected library the request is exactly the single-library one.
 *
 * Semantics mapping (see the Subsonic sibling for the reference behavior):
 *  - favorites ↔ the per-user `IsFavorite` flag (Jellyfin's heart)
 *  - played/most-played albums and artists are derived from played TRACKS:
 *    track playback does not update album-level UserData, and `IsPlayed` on
 *    a folder means "all children played" — unusable as a history signal
 *  - similar artists ↔ `/Items/{id}/Similar` (server-computed from shared
 *    genres/tags, server-wide, so filtered to the scoped artists); similar
 *    tracks are synthesized as random tracks across the seed and its similar
 *    artists
 *  - track ids ARE Jellyfin item ids; artwork ids ARE the item id owning
 *    the primary image
 */
@Singleton
class JellyfinMusicRepository @Inject constructor(
    sessionManager: SessionManager,
    settings: ServerSettingsRepository,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : ProviderMusicRepository<JellyfinSession>(sessionManager, settings, scope) {

    // The open play session reported to /Sessions/Playing; reportPlayState is
    // state-only, so the repository tracks which item the start was sent for.
    @Volatile
    private var startedPlayback: Pair<JellyfinClient, String>? = null

    override fun sessionOf(session: ProviderSession) = session as? JellyfinSession

    override fun translate(e: Exception) = (e as? JellyfinException)?.toMusicException()

    override suspend fun loadLibraries(session: JellyfinSession) =
        session.client.musicLibraries(session.userId)
            .mapNotNull { view -> view.id?.let { MusicLibrary(it, view.name) } }

    override suspend fun artists(): List<Artist> =
        scoped("artists") { session, scope ->
            // Artists are server-wide entities: one spanning libraries comes
            // back from each, so dedupe by id after the merge.
            mergeSorted(scope.perLibrary { session.client.albumArtists(session.userId, it) }, BY_SORT_NAME)
                .distinctBy { it.id }
                .mapNotNull { it.toArtistOrNull(sortGroup = it.sortBucket()) }
        }

    override suspend fun artist(id: String): ArtistDetail =
        scoped("artist/$id") { session, scope ->
            val dto = session.client.item(session.userId, id)
            ArtistDetail(
                artist = dto.toArtistOrNull()
                    ?: throw MusicException.NotFound("Artist $id has no id"),
                // An artist can span libraries; newest first, like the
                // Subsonic side, unknown years sink.
                albums = scope.perLibrary { lib ->
                    session.items(
                        parentId = lib,
                        type = ItemType.ALBUM,
                        params = listOf("AlbumArtistIds" to id),
                    ).items
                }.flatten()
                    .mapNotNull { it.toAlbumOrNull() }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    override suspend fun albums(offset: Int, size: Int): List<Album> =
        pagedAlbums("albums", scope(session()).selectedIds, offset, size)

    /**
     * One library keeps the server-paged request; several materialise one
     * merged sorted list and slice it, so paging and the unpaged host path
     * agree on order.
     */
    private suspend fun pagedAlbums(
        key: String,
        libraries: List<String>,
        offset: Int,
        size: Int,
        params: (String) -> List<Pair<String, String>> = { emptyList() },
    ): List<Album> = when (libraries.size) {
        0 -> emptyList()
        1 -> scoped("$key/$offset/$size") { session, _ ->
            val lib = libraries.single()
            session.items(
                parentId = lib,
                type = ItemType.ALBUM,
                sortBy = "SortName",
                params = params(lib),
                startIndex = offset,
                limit = size,
            ).items.mapNotNull { it.toAlbumOrNull() }
        }
        else -> scoped("$key-all") { session, _ ->
            mergeSorted(
                fanOut(libraries) { lib ->
                    session.items(
                        parentId = lib,
                        type = ItemType.ALBUM,
                        sortBy = "SortName",
                        params = params(lib),
                    ).items
                }.map { it.second },
                BY_SORT_NAME,
            ).mapNotNull { it.toAlbumOrNull() }
        }.drop(offset).take(size)
    }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        scoped("recent/$size") { session, scope ->
            mergeSorted(
                scope.perLibrary { lib ->
                    session.items(
                        parentId = lib,
                        type = ItemType.ALBUM,
                        sortBy = "DateCreated",
                        descending = true,
                        limit = size,
                    ).items
                },
                BY_DATE_CREATED_DESC,
            ).mapNotNull { it.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        scoped("favorites/$size") { session, scope ->
            mergeSorted(
                scope.perLibrary { lib ->
                    session.items(
                        parentId = lib,
                        type = ItemType.ALBUM,
                        filters = IS_FAVORITE,
                        limit = size,
                    ).items
                },
                BY_SORT_NAME,
            ).mapNotNull { it.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteTracks(): List<Track> =
        scoped("favorite-tracks") { session, scope ->
            mergeTagged(scope.selectedIds, BY_NAME) { lib ->
                session.items(type = ItemType.AUDIO, parentId = lib, filters = IS_FAVORITE).items
            }.mapNotNull { (lib, item) -> item.toTrackOrNull(lib) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        scoped("favorite-artists") { session, scope ->
            mergeSorted(
                scope.perLibrary { session.client.albumArtists(session.userId, it, isFavorite = true) },
                BY_SORT_NAME,
            ).distinctBy { it.id }.mapNotNull { it.toArtistOrNull() }
        }

    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        scoped("recently-added-tracks/$size") { session, scope ->
            mergeTagged(scope.selectedIds, BY_DATE_CREATED_DESC) { lib ->
                session.items(
                    type = ItemType.AUDIO,
                    parentId = lib,
                    sortBy = "DateCreated",
                    descending = true,
                    limit = size,
                ).items
            }.mapNotNull { (lib, item) -> item.toTrackOrNull(lib) }.take(size)
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withScope { session, scope ->
            val ids = scope.selectedIds
            // Split the draw by library size so a small library does not
            // dominate the mix; one library needs no count probe.
            val shares = if (ids.size == 1) listOf(size) else {
                val counts = trackCounts()
                allocate(size, ids.map { counts[it] ?: 0 })
            }
            val shareOf = ids.zip(shares).toMap()
            drawAcross(ids) { lib ->
                val share = shareOf.getValue(lib)
                if (share == 0) return@drawAcross emptyList()
                session.items(
                    parentId = lib,
                    type = ItemType.AUDIO,
                    sortBy = "Random",
                    limit = share,
                ).items.mapNotNull { it.toTrackOrNull(lib) }
            }
        }

    override suspend fun randomAlbums(size: Int): List<Album> =
        withScope { session, scope ->
            drawAcross(scope.selectedIds) { lib ->
                session.items(parentId = lib, type = ItemType.ALBUM, sortBy = "Random", limit = size)
                    .items.mapNotNull { it.toAlbumOrNull() }
            }.take(size)
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        playedAlbums("recently-played-albums/$size", "DatePlayed", size)

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        playedAlbums("most-played-albums/$size", "PlayCount", size)

    override suspend fun genres(): List<Genre> =
        scoped("genres") { session, scope ->
            // Jellyfin's genre list has no song counts, but the home tab's
            // genre mixes rank and threshold on them — count each genre's
            // tracks with a zero-size page (TotalRecordCount only, so the
            // fan-out is cheap; OkHttp's per-host cap keeps it polite).
            scope.perLibrary { lib ->
                coroutineScope {
                    session.client.genres(session.userId, lib).map { genre ->
                        async {
                            // One flaky probe degrades its genre to count 0
                            // instead of failing the whole genre list.
                            val count = try {
                                genre.id?.let { genreId ->
                                    session.items(
                                        parentId = lib,
                                        type = ItemType.AUDIO,
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
            }.flatten()
                // The same genre in several libraries is one entry.
                .groupBy { it.name }
                .map { (name, entries) -> Genre(name, songCount = entries.sumOf { it.songCount }) }
        }

    override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): List<Album> {
        val ids = genreIds(genre)
        return pagedAlbums("albums-by-genre/$genre", ids.keys.toList(), offset, size) { lib ->
            listOf("GenreIds" to ids.getValue(lib))
        }
    }

    override suspend fun albumsByYearRange(
        fromYear: Int,
        toYear: Int,
        offset: Int,
        size: Int,
    ): List<Album> =
        pagedAlbums("albums-by-year/$fromYear-$toYear", scope(session()).selectedIds, offset, size) {
            listOf("Years" to yearList(fromYear, toYear))
        }

    // Uncached like randomTracks: CatalogMixes owns list stability.
    // Track-level genre works here — Jellyfin aggregates album genres from
    // the track tags, so tracks carry them.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withScope { session, _ ->
            val ids = genreIds(genre)
            if (ids.isEmpty()) return@withScope emptyList()
            drawAcross(ids.keys.toList()) { lib ->
                session.items(
                    parentId = lib,
                    type = ItemType.AUDIO,
                    params = listOf("GenreIds" to ids.getValue(lib)),
                    sortBy = "Random",
                    limit = size,
                ).items.mapNotNull { it.toTrackOrNull(lib) }
            }.take(size)
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withScope { session, scope ->
            drawAcross(scope.selectedIds) { lib ->
                session.items(
                    parentId = lib,
                    type = ItemType.AUDIO,
                    params = listOf("Years" to yearList(fromYear, toYear)),
                    sortBy = "Random",
                    limit = size,
                ).items.mapNotNull { it.toTrackOrNull(lib) }
            }.take(size)
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", "PlayCount", size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", "DatePlayed", size)

    // Server-computed from shared genres/tags — present whenever the library
    // is tagged. The endpoint is server-wide and its items carry no library,
    // so a partial scope is applied by filtering to the scoped artist ids.
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        scoped("similar-artists/$artistId") { session, scope ->
            val inScope = if (scope.isAll) null else scopedArtistIds()
            session.client.similar(session.userId, artistId, limit = SIMILAR_ARTIST_FETCH)
                .filter { it.type == "MusicArtist" && it.id != artistId }
                .filter { inScope == null || it.id in inScope }
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
        return withScope { session, scope ->
            // Comma-separated ArtistIds are OR'd by Jellyfin: one request per
            // library draws random tracks across the seed and all similar artists.
            val ids = (listOf(artistId) + similar.map { it.id }).joinToString(",")
            drawAcross(scope.selectedIds) { lib ->
                session.items(
                    parentId = lib,
                    type = ItemType.AUDIO,
                    params = listOf("ArtistIds" to ids),
                    sortBy = "Random",
                    limit = count,
                ).items.mapNotNull { it.toTrackOrNull(lib) }
            }.take(count)
        }
    }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        scoped("top-songs/$artistName/$count") { session, scope ->
            // The contract keys by artist NAME (a Subsonic API quirk); resolve
            // to an item id first — the first library that has it wins.
            val (libraryId, artistId) = fanOut(scope.selectedIds) { lib ->
                session.client.albumArtists(session.userId, lib, searchTerm = artistName)
                    .filter { it.name.equals(artistName, ignoreCase = true) }
                    .mapNotNull { it.id }
            }.firstNotNullOfOrNull { (lib, ids) -> ids.firstOrNull()?.let { lib to it } }
                ?: return@scoped emptyList()

            suspend fun tier(filters: List<String>, sortBy: String?) =
                session.items(
                    parentId = libraryId,
                    type = ItemType.AUDIO,
                    filters = filters,
                    params = listOf("ArtistIds" to artistId),
                    sortBy = sortBy,
                    descending = sortBy != null,
                    limit = count,
                ).items.mapNotNull { it.toTrackOrNull(libraryId) }

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
    // never lags a local change. Unscoped: the heart must be right for a
    // playlist track from a hidden library. Cached as an id Set.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { session ->
            session.items(
                type = ItemType.AUDIO,
                filters = IS_FAVORITE,
            ).items.mapNotNullTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { session ->
            session.client.setFavorite(session.userId, trackId, favorite)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { session ->
            session.client.setFavorite(session.userId, albumId, favorite)
            // Albums carry their favorite state; every cached album list is
            // stale the moment the flag changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { session ->
            val dto = session.client.item(session.userId, id)
            AlbumDetail(
                album = dto.toAlbumOrNull()
                    ?: throw MusicException.NotFound("Album $id has no id"),
                tracks = session.items(
                    parentId = id,
                    type = ItemType.AUDIO,
                    recursive = false,
                    sortBy = "ParentIndexNumber,IndexNumber,SortName",
                ).items.mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { session ->
            // Playlists live outside the music libraries, so no ParentId here;
            // MediaType keeps video playlists out.
            session.items(
                type = ItemType.PLAYLIST,
                sortBy = "SortName",
            ).items.filter { it.mediaType == "Audio" }
                .mapNotNull { it.toPlaylistOrNull() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { session ->
            val dto = session.client.item(session.userId, id)
            PlaylistDetail(
                playlist = dto.toPlaylistOrNull()
                    ?: throw MusicException.NotFound("Playlist $id not found"),
                tracks = session.client.playlistItems(session.userId, id)
                    .mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { session ->
            session.client.item(session.userId, id).toTrackOrNull()
                ?: throw MusicException.NotFound("Track $id has no id")
        }

    // Items carry no library id; the album's ancestors name the
    // CollectionFolder. Cached per album, an empty string meaning "unknown".
    override suspend fun trackLibraryId(trackId: String, candidates: Set<String>): String? {
        val itemId = track(trackId).albumId ?: trackId
        return cached("album-library/$itemId") { session ->
            session.client.ancestors(session.userId, itemId)
                .firstOrNull { it.type == "CollectionFolder" }?.id.orEmpty()
        }.ifEmpty { null }
    }

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        scoped("search/$query") { session, scope ->
            coroutineScope {
                suspend fun items(lib: String, type: ItemType) = session.items(
                    parentId = lib,
                    type = type,
                    params = listOf("SearchTerm" to query),
                    limit = SEARCH_COUNT_PER_TYPE,
                ).items
                val perLibrary = scope.selectedIds.map { lib ->
                    Triple(
                        async {
                            session.client.albumArtists(
                                session.userId,
                                lib,
                                searchTerm = query,
                                limit = SEARCH_COUNT_PER_TYPE,
                            ).mapNotNull { it.toArtistOrNull() }
                        },
                        async { items(lib, ItemType.ALBUM).mapNotNull { it.toAlbumOrNull() } },
                        async { items(lib, ItemType.AUDIO).mapNotNull { it.toTrackOrNull(lib) } },
                    )
                }
                // Round-robin keeps each library's relevance order; artists
                // are server-wide and can repeat across libraries.
                SearchResults(
                    artists = interleave(perLibrary.map { it.first.await() })
                        .distinctBy { it.id }.take(SEARCH_COUNT_PER_TYPE),
                    albums = interleave(perLibrary.map { it.second.await() })
                        .distinctBy { it.id }.take(SEARCH_COUNT_PER_TYPE),
                    tracks = interleave(perLibrary.map { it.third.await() })
                        .distinctBy { it.id }.take(SEARCH_COUNT_PER_TYPE),
                )
            }
        }

    override suspend fun scrobble(
        trackId: String,
        submission: Boolean,
        expectedSession: ProviderSession?,
    ) =
        withSession(expectedSession) { session ->
            if (submission) {
                // Deterministic mark-played: with session-less direct play the
                // app owns the "counts as played" threshold, not the server.
                session.client.markPlayed(session.userId, trackId)
            } else {
                sendPlaybackStart(session, trackId)
            }
        }

    override val playbackReportIntervalMs: Long = PROGRESS_INTERVAL_MS

    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
        expectedSession: ProviderSession?,
    ) = withSession(expectedSession) { session ->
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
                if (startedPlayback != (session.client to trackId)) sendPlaybackStart(session, trackId)
                session.client.reportPlaybackProgress(body)
            }

            PlayState.STOPPED -> {
                session.client.reportPlaybackStopped(body)
                startedPlayback = null
            }
        }
    }

    private suspend fun sendPlaybackStart(session: JellyfinSession, trackId: String) {
        session.client.reportPlaybackStart(JellyfinPlaybackBody(itemId = trackId, positionTicks = 0))
        startedPlayback = session.client to trackId
    }

    private val JellyfinSession.userId: String get() = account.userId

    /** [JellyfinClient.items] as the signed-in user; the whole result, for [JellyfinItemsResult.totalRecordCount]. */
    private suspend fun JellyfinSession.items(
        type: ItemType,
        parentId: String? = null,
        sortBy: String? = null,
        descending: Boolean = false,
        filters: List<String> = emptyList(),
        params: List<Pair<String, String>> = emptyList(),
        startIndex: Int = 0,
        limit: Int? = null,
        recursive: Boolean = true,
    ): JellyfinItemsResult = client.items(
        userId,
        parentId = parentId,
        includeItemTypes = type,
        recursive = recursive,
        sortBy = sortBy,
        sortOrder = if (descending) "Descending" else null,
        filters = filters,
        params = params,
        startIndex = startIndex,
        limit = limit,
    )

    /** Runs [fetch] per selected library, results in selection order. */
    private suspend fun <T> LibraryScope.perLibrary(fetch: suspend (String) -> List<T>): List<List<T>> =
        fanOut(selectedIds, fetch).map { it.second }

    /**
     * Jellyfin's only year filter is `Years` — an exact-match comma list on
     * ProductionYear (min/max bounds exist only for PremiereDate, which music
     * rarely carries). A decade range is just ten values.
     */
    private fun yearList(from: Int, to: Int): String =
        (from..to).joinToString(",")

    /** Per selected library, the number of tracks — the random-draw weights. */
    private suspend fun trackCounts(): Map<String, Int> =
        scoped("track-counts") { session, scope ->
            fanOut(scope.selectedIds) { lib ->
                listOf(
                    session.items(type = ItemType.AUDIO, parentId = lib, limit = 0)
                        .totalRecordCount ?: 0,
                )
            }.associate { (lib, count) -> lib to count.single() }
        }

    /** Ids of the scoped album artists, for filtering server-wide answers. */
    private suspend fun scopedArtistIds(): Set<String> =
        scoped("artist-ids") { _, _ -> artists().mapTo(HashSet()) { it.id } }

    /**
     * Case-insensitive genre name → GenreIds value per library that has the
     * genre (library id → genre id), via the cached directory.
     */
    private suspend fun genreIds(name: String): Map<String, String> =
        scoped("genre-ids") { session, scope ->
            val byName = LinkedHashMap<String, LinkedHashMap<String, String>>()
            for ((lib, genres) in fanOut(scope.selectedIds) { session.client.genres(session.userId, it) }) {
                for (genre in genres) {
                    val id = genre.id ?: continue
                    byName.getOrPut(genre.name.lowercase()) { LinkedHashMap() }[lib] = id
                }
            }
            byName
        }[name.lowercase()].orEmpty()

    /** The played tracks that back every played-albums/artists derivation. */
    private suspend fun playedTracks(
        session: JellyfinSession,
        scope: LibraryScope,
        sortBy: String,
        size: Int,
    ): List<JellyfinItem> =
        mergeSorted(
            scope.perLibrary { lib ->
                session.items(
                    parentId = lib,
                    type = ItemType.AUDIO,
                    filters = IS_PLAYED,
                    sortBy = sortBy,
                    descending = true,
                    // Overfetch: many played tracks share an album, and the
                    // distinct pass below must still fill the requested page.
                    limit = size * PLAYED_OVERFETCH,
                ).items
            },
            if (sortBy == "PlayCount") BY_PLAY_COUNT_DESC else BY_LAST_PLAYED_DESC,
        )

    /**
     * Albums synthesized from the played-track history, order preserved,
     * first occurrence wins. Track counts are unknown (0) — same policy as
     * the artist synthesis.
     */
    private suspend fun playedAlbums(key: String, sortBy: String, size: Int): List<Album> =
        scoped(key) { session, scope ->
            playedTracks(session, scope, sortBy, size)
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
        scoped(key) { session, scope ->
            playedTracks(session, scope, sortBy, size)
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

    /**
     * Every Audio item is streamable via the stream endpoint — no part gate.
     * [libraryId] is the `ParentId` the item was asked for under (items carry
     * none themselves); by-id paths leave it null.
     */
    private fun JellyfinItem.toTrackOrNull(libraryId: String? = null): Track? {
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
            streamUrl = StreamRef(MusicProvider.JELLYFIN, id).encode(),
            libraryId = libraryId,
        )
    }

    private companion object {
        /** Runtime ticks (100 ns) per second. */
        const val TICKS_PER_SEC = 10_000_000L

        /** What jellyfin-web uses for progress reports while playing. */
        const val PROGRESS_INTERVAL_MS = 10_000L

        /** One fetch serves every similarArtists(count<=this) via the cache. */
        const val SIMILAR_ARTIST_FETCH = 18

        /**
         * Played-track pages fetched per derived album/artist slot: many
         * played tracks share an album, and the distinct pass must still
         * fill the page.
         */
        const val PLAYED_OVERFETCH = 10

        val IS_FAVORITE = listOf("IsFavorite")
        val IS_PLAYED = listOf("IsPlayed")

        // Cross-library merge orders; each mirrors the per-library SortBy so
        // a single library's server order is unchanged (mergeSorted skips it).
        val BY_SORT_NAME: Comparator<JellyfinItem> =
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortName ?: it.name }
        val BY_NAME: Comparator<JellyfinItem> = compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        val BY_DATE_CREATED_DESC: Comparator<JellyfinItem> =
            compareByDescending { it.dateCreated.orEmpty() }
        val BY_LAST_PLAYED_DESC: Comparator<JellyfinItem> =
            compareByDescending { it.userData?.lastPlayedDate.orEmpty() }
        val BY_PLAY_COUNT_DESC: Comparator<JellyfinItem> =
            compareByDescending { it.userData?.playCount ?: 0 }
    }
}
