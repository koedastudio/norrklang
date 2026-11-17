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
import studio.koeda.norrklang.data.model.ReleaseType
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository.Companion.SEARCH_COUNT_PER_TYPE
import studio.koeda.norrklang.data.repo.MusicRepository.Companion.SIMILAR_TRACK_ARTISTS
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.PlexSession
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient.ItemType
import studio.koeda.norrklang.plex.model.PlexDirectory
import studio.koeda.norrklang.plex.model.PlexHub
import studio.koeda.norrklang.plex.model.PlexMetadata

/**
 * [MusicRepository] backed by a Plex Media Server, browsing the selected
 * music sections (see [LibraryScope]): every section-bound listing is
 * fetched per section and merged, so one selected section costs exactly the
 * request it always did.
 *
 * Semantics mapping (see the Subsonic sibling for the reference behavior):
 *  - favorites ↔ `userRating == 10` ("loved" — what Plexamp's heart sets)
 *  - top tracks ↔ a blend of the user's own track ratings, Plex's global
 *    popularity (ratingCount — Plexamp's flame icons), and the account's
 *    play counts, strongest signal first
 *  - similar artists ↔ the artist's related hub (Plex-metadata "Similar
 *    Artists", in-library, no Plex Pass needed); similar tracks are
 *    synthesized as random tracks across the seed and its similar artists
 *  - track ids ARE Plex rating keys; artwork ids ARE Plex thumb paths
 */
@Singleton
class PlexMusicRepository @Inject constructor(
    sessionManager: SessionManager,
    settings: ServerSettingsRepository,
    store: SnapshotStore,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : ProviderMusicRepository<PlexSession>(sessionManager, settings, store, scope) {

    override fun sessionOf(session: ProviderSession) = session as? PlexSession

    override fun translate(e: Exception) = (e as? PlexException)?.toMusicException()

    override suspend fun loadLibraries(session: PlexSession) =
        session.client.musicSections().map { MusicLibrary(it.key, it.title) }

    // The section list is the lightest authenticated call the server client has.
    override suspend fun ping() {
        withSession { it.client.musicSections() }
    }

    override suspend fun artists(): List<Artist> =
        persistedScoped(BrowseList.ARTISTS, "artists", BrowseListSerializers.artists) { session, scope ->
            scope.merged(BY_TITLE) { section ->
                session.client.sectionItems(section, ItemType.ARTIST, sort = "titleSort:asc")
            }.mapNotNull { (_, dto) -> dto.toArtistOrNull(sortGroup = dto.sortBucket()) }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { session ->
            val dto = session.client.metadata(id)
            val releaseTypes = releaseTypesByAlbum(relatedHubs(id))
            ArtistDetail(
                artist = dto.toArtistOrNull()
                    ?: throw MusicException.NotFound("Artist $id has no rating key"),
                // Newest first, like the Subsonic side; unknown years sink.
                albums = artistReleases(session, id, dto.librarySectionId)
                    .mapNotNull { it.toAlbumOrNull(releaseTypes[it.ratingKey] ?: ReleaseType.ALBUM) }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    // `/children` lists only "true" albums: Plex moved EPs, live albums,
    // compilations etc. to the artist's hubs, which are section queries on
    // artist.id. The same query here lists every release in one request.
    private suspend fun artistReleases(
        session: PlexSession,
        artistId: String,
        sectionId: Int?,
    ): List<PlexMetadata> {
        val section = sectionId?.toString() ?: return session.client.children(artistId)
        return session.client.sectionItems(section, ItemType.ALBUM, filters = listOf("artist.id" to artistId))
    }

    override suspend fun albums(offset: Int, size: Int): List<Album> =
        pagedAlbums("albums", scope(session()).selectedIds, offset, size)

    /**
     * One section keeps the server-paged request; several materialise one
     * merged sorted list and slice it, so paging and the unpaged host path
     * agree on order.
     */
    private suspend fun pagedAlbums(
        key: String,
        sections: List<String>,
        offset: Int,
        size: Int,
        filters: (String) -> List<Pair<String, String>> = { emptyList() },
    ): List<Album> = when (sections.size) {
        0 -> emptyList()
        1 -> scoped("$key/$offset/$size") { session, _ ->
            val section = sections.single()
            session.client.sectionItems(
                section,
                ItemType.ALBUM,
                filters = filters(section),
                sort = "titleSort:asc",
                start = offset,
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }
        else -> scoped("$key-all") { session, _ ->
            mergeTagged(sections, BY_TITLE) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = filters(section),
                    sort = "titleSort:asc",
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }
        }.drop(offset).take(size)
    }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        persistedScoped(BrowseList.RECENTLY_ADDED_ALBUMS, "recent/$size", BrowseListSerializers.albums) { session, scope ->
            scope.merged(BY_ADDED_DESC) { section ->
                session.client.sectionItems(section, ItemType.ALBUM, sort = "addedAt:desc", size = size)
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        persistedScoped(BrowseList.FAVORITE_ALBUMS, "favorites/$size", BrowseListSerializers.albums) { session, scope ->
            scope.merged(BY_TITLE) { section ->
                session.client.sectionItems(section, ItemType.ALBUM, filters = LOVED, size = size)
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteTracks(): List<Track> =
        persistedScoped(BrowseList.FAVORITE_TRACKS, "favorite-tracks", BrowseListSerializers.tracks) { session, scope ->
            scope.merged(BY_TITLE) { section ->
                session.client.sectionItems(section, ItemType.TRACK, filters = LOVED)
            }.mapNotNull { (section, dto) -> dto.toTrackOrNull(section) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        persistedScoped(BrowseList.FAVORITE_ARTISTS, "favorite-artists", BrowseListSerializers.artists) { session, scope ->
            scope.merged(BY_TITLE) { section ->
                session.client.sectionItems(section, ItemType.ARTIST, filters = LOVED)
            }.mapNotNull { (_, dto) -> dto.toArtistOrNull() }
        }

    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        persistedScoped(
            BrowseList.RECENTLY_ADDED_TRACKS,
            "recently-added-tracks/$size",
            BrowseListSerializers.tracks,
        ) { session, scope ->
            scope.merged(BY_ADDED_DESC) { section ->
                session.client.sectionItems(section, ItemType.TRACK, sort = "addedAt:desc", size = size)
            }.mapNotNull { (section, dto) -> dto.toTrackOrNull(section) }.take(size)
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration. Draws
    // are split across sections by track count so big sections dominate.
    override suspend fun randomTracks(size: Int): List<Track> =
        withScope { session, scope ->
            val sections = scope.selectedIds
            val shares = if (sections.size == 1) {
                listOf(size)
            } else {
                val counts = trackCounts()
                allocate(size, sections.map { counts[it] ?: 0 })
            }
            val shareOf = sections.zip(shares).toMap()
            drawAcross(sections) { section ->
                val share = shareOf.getValue(section)
                if (share == 0) return@drawAcross emptyList()
                session.client.sectionItems(section, ItemType.TRACK, sort = "random", size = share)
                    .mapNotNull { it.toTrackOrNull(section) }
            }
        }

    override suspend fun randomAlbums(size: Int): List<Album> =
        withScope { session, scope ->
            drawAcross(scope.selectedIds) { section ->
                session.client.sectionItems(section, ItemType.ALBUM, sort = "random", size = size)
                    .mapNotNull { it.toAlbumOrNull() }
            }.take(size)
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        persistedScoped(
            BrowseList.RECENTLY_PLAYED_ALBUMS,
            "recently-played-albums/$size",
            BrowseListSerializers.albums,
        ) { session, scope ->
            scope.merged(BY_LAST_PLAYED_DESC) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = PLAYED,
                    sort = "lastViewedAt:desc",
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        scoped("most-played-albums/$size") { session, scope ->
            scope.merged(BY_PLAYS_DESC) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = PLAYED,
                    sort = "viewCount:desc",
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun genres(): List<Genre> =
        scoped("genres") { session, scope ->
            // Plex's genre directory has no song counts, but the home tab's
            // genre mixes rank and threshold on them — count each genre's
            // tracks with a zero-size page (totalSize only, so the fan-out
            // is cheap; OkHttp's per-host cap keeps it polite). album.genre
            // matches the filter randomTracksByGenre draws mixes with.
            val directory = genreDirectory()
            val perSection = fanOut(scope.selectedIds) { section ->
                coroutineScope {
                    directory[section].orEmpty().map { genre ->
                        async {
                            // One flaky probe degrades its genre to count 0
                            // instead of failing the whole genre list.
                            val count = try {
                                session.client.sectionItemCount(
                                    section,
                                    ItemType.TRACK,
                                    filters = listOf("album.genre" to genre.key),
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: PlexException) {
                                0
                            }
                            Genre(genre.title, songCount = count)
                        }
                    }.awaitAll()
                }
            }
            // The same genre in several sections is one genre with summed counts.
            val merged = LinkedHashMap<String, Genre>()
            for ((_, genres) in perSection) {
                for (genre in genres) {
                    merged.merge(genre.name.lowercase(), genre) { a, b ->
                        Genre(a.name, a.songCount + b.songCount)
                    }
                }
            }
            merged.values.toList()
        }

    override suspend fun albumsByGenre(genre: String, offset: Int, size: Int): List<Album> {
        val ids = genreIds(genre)
        return pagedAlbums("albums-by-genre/$genre", ids.keys.toList(), offset, size) { section ->
            listOf("genre" to ids.getValue(section))
        }
    }

    override suspend fun albumsByYearRange(
        fromYear: Int,
        toYear: Int,
        offset: Int,
        size: Int,
    ): List<Album> =
        pagedAlbums("albums-by-year/$fromYear-$toYear", scope(session()).selectedIds, offset, size) {
            yearRange("year", fromYear, toYear)
        }

    // Uncached like randomTracks: CatalogMixes owns list stability.
    // Tracks rarely carry their own genre tags in Plex, so filter on the
    // album's genre instead.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withScope { session, _ ->
            val ids = genreIds(genre)
            if (ids.isEmpty()) return@withScope emptyList()
            drawAcross(ids.keys.toList()) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("album.genre" to ids.getValue(section)),
                    sort = "random",
                    size = size,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.take(size)
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withScope { session, scope ->
            drawAcross(scope.selectedIds) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = yearRange("album.year", fromYear, toYear),
                    sort = "random",
                    size = size,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.take(size)
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", "viewCount:desc", BY_PLAYS_DESC, size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", "lastViewedAt:desc", BY_LAST_PLAYED_DESC, size)

    /**
     * The artist's related hubs: "Similar Artists" plus Plex's own grouping of
     * the artist's releases ("Singles & EPs", "Compilations", …). One fetch
     * serves both the artist view and the similar-artists section.
     */
    private suspend fun relatedHubs(artistId: String): List<PlexHub> =
        cached("related/$artistId") { session -> session.client.related(artistId) }

    // Album listings carry no release-type tags; the typed release hubs are
    // where Plex says which albums are singles/EPs or compilations. Albums in
    // no typed hub (live, soundtrack, …) are plain albums.
    private fun releaseTypesByAlbum(hubs: List<PlexHub>): Map<String, ReleaseType> =
        hubs.flatMap { hub ->
            val type = when (hub.hubIdentifier) {
                "artist.albums.singles" -> ReleaseType.SINGLE_OR_EP
                "artist.albums.compilation" -> ReleaseType.COMPILATION
                else -> return@flatMap emptyList()
            }
            hub.metadata.mapNotNull { album -> album.ratingKey?.let { it to type } }
        }.toMap()

    // "Sonically similar" needs Plex Pass + completed analysis, but the
    // related hub's "Similar Artists" is plain Plex music metadata — present
    // whenever the library's artists are matched. Items are in-library by
    // construction (the contract Subsonic reaches by filtering albumCount);
    // the hub is server-wide, so hidden sections are filtered out here.
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        scoped("similar-artists/$artistId") { _, scope ->
            val selected = scope.selectedIds.toSet()
            relatedHubs(artistId)
                .flatMap { it.metadata }
                .filter { it.type == "artist" && it.ratingKey != artistId }
                .filter {
                    val sectionId = it.librarySectionId
                    scope.isAll || sectionId == null || sectionId.toString() in selected
                }
                .distinctBy { it.ratingKey }
                .mapNotNull { it.toArtistOrNull() }
        }.take(count)

    // Uncached like randomTracks: SimilarMixesSession owns list stability.
    // Empty when the artist has no similar artists — the "no similarity
    // data" signal the mix sessions' dead-server bail-out relies on.
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
        val similar = similarArtists(artistId, SIMILAR_TRACK_ARTISTS)
        if (similar.isEmpty()) return emptyList()
        return withScope { session, scope ->
            // Comma-separated filter values are OR'd by Plex: one request per
            // section draws random tracks across the seed and all similar artists.
            val ids = (listOf(artistId) + similar.map { it.id }).joinToString(",")
            drawAcross(scope.selectedIds) { section ->
                session.client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("artist.id" to ids),
                    sort = "random",
                    size = count,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.take(count)
        }
    }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        scoped("top-songs/$artistName/$count") { session, scope ->
            // The contract keys by artist NAME (a Subsonic API quirk); resolve
            // to a rating key first — the first section holding it wins.
            val match = fanOut(scope.selectedIds) { section ->
                session.client.sectionItems(section, ItemType.ARTIST, filters = listOf("title" to artistName))
            }.firstNotNullOfOrNull { (section, artists) ->
                artists.firstOrNull { it.title.equals(artistName, ignoreCase = true) }
                    ?.ratingKey?.let { section to it }
            } ?: return@scoped emptyList()
            val (section, key) = match

            suspend fun tier(filters: List<Pair<String, String>>, sort: String) =
                session.client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("artist.id" to key) + filters,
                    sort = sort,
                    size = count,
                ).mapNotNull { it.toTrackOrNull(section) }

            // "Best of" blends three signals, strongest first: tracks the
            // user rated 3+ stars (>>5 in half-star units — a LOW rating
            // must not promote a track), the global popularity behind
            // Plexamp's flame icons, and the account's own play counts.
            // Later tiers are only fetched while the list runs short.
            val tiers = listOf(
                listOf("userRating>>" to "5") to "userRating:desc",
                listOf("ratingCount>>" to "0") to "ratingCount:desc",
                PLAYED to "viewCount:desc",
            )
            val blended = LinkedHashMap<String, Track>()
            for ((filters, sort) in tiers) {
                if (blended.size >= count) break
                for (track in tier(filters, sort)) blended.putIfAbsent(track.id, track)
            }
            blended.values.take(count)
        }

    // Runs on every track transition (keeps the car's heart button current);
    // served from the TTL cache, which setTrackFavorite clears so the answer
    // never lags a local change. Unscoped on purpose: the heart must be right
    // for a playlist track from a hidden section.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in persisted(BrowseList.FAVORITE_TRACK_IDS, "favorite-track-ids", BrowseListSerializers.ids) { session ->
            fanOut(libraries().map { it.id }) { section ->
                session.client.sectionItems(section, ItemType.TRACK, filters = LOVED)
            }.flatMap { it.second }.mapNotNullTo(HashSet()) { it.ratingKey }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { session ->
            session.client.rate(trackId, if (favorite) LOVED_RATING else CLEAR_RATING)
            trackFavoriteChanged()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { session ->
            session.client.rate(albumId, if (favorite) LOVED_RATING else CLEAR_RATING)
            // Albums carry their loved state; every cached album list is
            // stale the moment a rating changes.
            albumFavoriteChanged()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { session ->
            val dto = session.client.metadata(id)
            AlbumDetail(
                album = dto.toAlbumOrNull()
                    ?: throw MusicException.NotFound("Album $id has no rating key"),
                tracks = session.client.children(id).mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        persisted(BrowseList.PLAYLISTS, "playlists", BrowseListSerializers.playlists) { session ->
            session.client.playlists().mapNotNull { it.toPlaylistOrNull() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { session ->
            val dto = session.client.metadata(id)
            PlaylistDetail(
                playlist = dto.toPlaylistOrNull()
                    ?: throw MusicException.NotFound("Playlist $id not found"),
                tracks = session.client.playlistItems(id).mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { session ->
            session.client.metadata(id).toTrackOrNull()
                ?: throw MusicException.NotFound("Track $id has no playable media")
        }

    // Metadata carries librarySectionID; candidates are informational here.
    override suspend fun trackLibraryId(trackId: String, candidates: Set<String>): String? =
        track(trackId).libraryId

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        scoped("search/$query") { session, scope ->
            val perSection = fanOut(scope.selectedIds) { section ->
                session.client.search(section, query, limit = SEARCH_COUNT_PER_TYPE)
            }
            // Round-robin keeps each section's relevance order; an item
            // reachable from several sections is listed once.
            fun hub(type: String) = interleave(
                perSection.map { (section, hubs) ->
                    hubs.filter { it.type == type }.flatMap { it.metadata }.map { section to it }
                },
            ).distinctBy { it.second.ratingKey }.take(SEARCH_COUNT_PER_TYPE)
            SearchResults(
                artists = hub("artist").mapNotNull { (_, dto) -> dto.toArtistOrNull() },
                albums = hub("album").mapNotNull { (_, dto) -> dto.toAlbumOrNull() },
                tracks = hub("track").mapNotNull { (section, dto) -> dto.toTrackOrNull(section) },
            )
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
                session.client.markPlayed(trackId)
            } else {
                session.client.timeline(trackId, state = "playing", timeMs = 0, durationMs = null)
            }
        }

    override val playbackReportIntervalMs: Long = TIMELINE_INTERVAL_MS

    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
        expectedSession: ProviderSession?,
    ) = withSession(expectedSession) { session ->
        val plexState = when (state) {
            PlayState.PLAYING -> "playing"
            PlayState.PAUSED -> "paused"
            PlayState.STOPPED -> "stopped"
        }
        session.client.timeline(trackId, plexState, positionMs, durationMs)
    }

    /** [mergeTagged] over the selected sections. */
    private suspend fun LibraryScope.merged(
        comparator: Comparator<in PlexMetadata>,
        fetch: suspend (String) -> List<PlexMetadata>,
    ): List<Pair<String, PlexMetadata>> = mergeTagged(selectedIds, comparator, fetch)

    /**
     * Plex's only integer comparison operators are the strict `>>` and `<<`
     * (`year>=`-style keys are not filters at all — the server ignores them
     * and returns the whole section), so an inclusive [from]..[to] range is
     * widened by one on each side.
     */
    private fun yearRange(field: String, from: Int, to: Int): List<Pair<String, String>> =
        listOf("$field>>" to (from - 1).toString(), "$field<<" to (to + 1).toString())

    /** Track count per selected section — the random-draw weights. */
    private suspend fun trackCounts(): Map<String, Int> =
        scoped("track-counts") { session, scope ->
            fanOut(scope.selectedIds) { section ->
                listOf(session.client.sectionItemCount(section, ItemType.TRACK))
            }.associate { (section, count) -> section to count.single() }
        }

    /** Each selected section's genre directory; genre keys are per section. */
    private suspend fun genreDirectory(): Map<String, List<PlexDirectory>> =
        scoped("genre-directory") { session, scope ->
            fanOut(scope.selectedIds) { section -> session.client.genres(section) }.toMap()
        }

    /** Case-insensitive genre name → filter id, for every selected section that has it. */
    private suspend fun genreIds(name: String): Map<String, String> =
        genreDirectory().mapNotNull { (section, genres) ->
            genres.firstOrNull { it.title.equals(name, ignoreCase = true) }?.let { section to it.key }
        }.toMap()

    /** Artists behind the album list, order preserved, first occurrence wins. */
    private suspend fun playedArtists(
        key: String,
        sort: String,
        comparator: Comparator<in PlexMetadata>,
        size: Int,
    ): List<Artist> =
        scoped(key) { session, scope ->
            scope.merged(comparator) { section ->
                session.client.sectionItems(section, ItemType.ALBUM, filters = PLAYED, sort = sort, size = size)
            }.mapNotNull { (_, album) ->
                val artistId = album.parentRatingKey ?: return@mapNotNull null
                val artistName = album.parentTitle ?: return@mapNotNull null
                Artist(id = artistId, name = artistName, albumCount = 0, artworkUrl = null)
            }.distinctBy { it.id }.take(size)
        }

    /**
     * Artwork is a content URI served by the in-app provider, never the
     * direct server URL — car hosts won't download remote URLs (see
     * [ArtworkContract]). Plex thumb paths ride through as opaque ids.
     */
    private fun artworkUri(thumbPath: String): String =
        ArtworkContract.coverUri(packageName, thumbPath)

    private fun PlexMetadata.sortBucket(): String {
        val first = (titleSort ?: title).trim().firstOrNull() ?: return "#"
        return if (first.isLetter()) first.uppercase() else "#"
    }

    private fun PlexMetadata.artworkOrNull(): String? =
        (thumb ?: parentThumb ?: grandparentThumb)?.let { artworkUri(it) }

    // Entries without a rating key are not addressable (detail lookups would
    // 404 on an empty id), so the mappers drop them — same policy as tracks
    // and playlists.
    private fun PlexMetadata.toArtistOrNull(sortGroup: String? = null): Artist? = Artist(
        id = ratingKey ?: return null,
        name = title,
        albumCount = childCount ?: 0,
        artworkUrl = artworkOrNull(),
        sortGroup = sortGroup,
    )

    private fun PlexMetadata.toAlbumOrNull(releaseType: ReleaseType = ReleaseType.ALBUM): Album? = Album(
        id = ratingKey ?: return null,
        title = title,
        artistName = parentTitle,
        artistId = parentRatingKey,
        year = year,
        trackCount = leafCount ?: 0,
        durationSec = ((duration ?: 0L) / 1000L).toInt(),
        artworkUrl = artworkOrNull(),
        isFavorite = userRating == LOVED_RATING.toDouble(),
        releaseType = releaseType,
    )

    private fun PlexMetadata.toPlaylistOrNull(): Playlist? = Playlist(
        id = ratingKey ?: return null,
        name = title,
        trackCount = leafCount ?: 0,
        durationSec = ((duration ?: 0L) / 1000L).toInt(),
        artworkUrl = (composite ?: thumb)?.let { artworkUri(it) },
    )

    /**
     * Null when the track has no part to stream — unplayable entries are
     * dropped. [sectionId] is the section the listing was asked for, the
     * library fallback when the item carries no librarySectionID.
     */
    private fun PlexMetadata.toTrackOrNull(sectionId: String? = null): Track? {
        val id = ratingKey ?: return null
        val partKey = media.firstOrNull()?.parts?.firstOrNull()?.key ?: return null
        return Track(
            id = id,
            title = title,
            artistName = grandparentTitle,
            artistId = grandparentRatingKey,
            albumTitle = parentTitle,
            albumId = parentRatingKey,
            trackNumber = index,
            discNumber = parentIndex,
            durationSec = duration?.let { (it / 1000L).toInt() },
            artworkUrl = artworkOrNull(),
            streamUrl = StreamRef(MusicProvider.PLEX, id, partKey).encode(),
            libraryId = librarySectionId?.toString() ?: sectionId,
        )
    }

    private companion object {
        /** Favorites mapping: userRating 10 is "loved" (Plexamp's heart). */
        const val LOVED_RATING = 10
        const val CLEAR_RATING = -1

        /** What Plex's own clients use for timeline pings while playing. */
        const val TIMELINE_INTERVAL_MS = 15_000L

        val LOVED = listOf("userRating" to "10")
        val PLAYED = listOf("viewCount>>" to "0")

        // Cross-section merge orders; a single section is never re-sorted.
        val BY_TITLE: Comparator<PlexMetadata> =
            compareBy(String.CASE_INSENSITIVE_ORDER) { it.titleSort ?: it.title }
        val BY_ADDED_DESC: Comparator<PlexMetadata> = compareByDescending { it.addedAt ?: 0L }
        val BY_LAST_PLAYED_DESC: Comparator<PlexMetadata> =
            compareByDescending { it.lastViewedAt ?: 0L }
        val BY_PLAYS_DESC: Comparator<PlexMetadata> = compareByDescending { it.viewCount ?: 0 }
    }
}
