package studio.koeda.norrklang.data.repo

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
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
import studio.koeda.norrklang.data.model.SearchResults
import studio.koeda.norrklang.data.model.StreamRef
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.session.MusicProvider
import studio.koeda.norrklang.data.session.PlexSession
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexServerClient.ItemType
import studio.koeda.norrklang.plex.model.PlexDirectory
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
    private val sessionManager: SessionManager,
    private val settings: ServerSettingsRepository,
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : MusicRepository {

    private val cache = TtlCache(ttlMillis = 5 * 60 * 1000L)

    init {
        // Sign-out purges entries; fingerprint keys isolate direct account
        // switches (see SubsonicMusicRepository).
        scope.launch {
            sessionManager.state.drop(1).collect { state ->
                // Connected caches are already keyed by account fingerprint.
                // Clearing on connect can invalidate that account's first load.
                if (state is SessionManager.SessionState.SignedOut) cache.clear()
            }
        }
    }

    override suspend fun libraries(): List<MusicLibrary> = librariesFor(plexSession())

    override suspend fun artists(): List<Artist> =
        scoped("artists") { client, scope ->
            scope.merged(BY_TITLE) { section ->
                client.sectionItems(section, ItemType.ARTIST, sort = "titleSort:asc")
            }.mapNotNull { (_, dto) -> dto.toArtistOrNull(sortGroup = dto.sortBucket()) }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { client ->
            val dto = client.metadata(id)
            ArtistDetail(
                artist = dto.toArtistOrNull()
                    ?: throw MusicException.NotFound("Artist $id has no rating key"),
                // Newest first, like the Subsonic side; unknown years sink.
                albums = client.children(id).mapNotNull { it.toAlbumOrNull() }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    // One section keeps the server-paged request; several materialise one
    // merged sorted list (same memory order as the unpaged host path) and slice.
    override suspend fun albums(offset: Int, size: Int): List<Album> =
        if (currentScope().selectedIds.size == 1) {
            scoped("albums/$offset/$size") { client, scope ->
                client.sectionItems(
                    scope.selectedIds.single(),
                    ItemType.ALBUM,
                    sort = "titleSort:asc",
                    start = offset,
                    size = size,
                ).mapNotNull { it.toAlbumOrNull() }
            }
        } else {
            scoped("albums-all") { client, scope ->
                scope.merged(BY_TITLE) { section ->
                    client.sectionItems(section, ItemType.ALBUM, sort = "titleSort:asc")
                }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }
            }.drop(offset).take(size)
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        scoped("recent/$size") { client, scope ->
            scope.merged(BY_ADDED_DESC) { section ->
                client.sectionItems(section, ItemType.ALBUM, sort = "addedAt:desc", size = size)
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        scoped("favorites/$size") { client, scope ->
            scope.merged(BY_TITLE) { section ->
                client.sectionItems(section, ItemType.ALBUM, filters = LOVED, size = size)
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun favoriteTracks(): List<Track> =
        scoped("favorite-tracks") { client, scope ->
            scope.merged(BY_TITLE) { section ->
                client.sectionItems(section, ItemType.TRACK, filters = LOVED)
            }.mapNotNull { (section, dto) -> dto.toTrackOrNull(section) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        scoped("favorite-artists") { client, scope ->
            scope.merged(BY_TITLE) { section ->
                client.sectionItems(section, ItemType.ARTIST, filters = LOVED)
            }.mapNotNull { (_, dto) -> dto.toArtistOrNull() }
        }

    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        scoped("recently-added-tracks/$size") { client, scope ->
            scope.merged(BY_ADDED_DESC) { section ->
                client.sectionItems(section, ItemType.TRACK, sort = "addedAt:desc", size = size)
            }.mapNotNull { (section, dto) -> dto.toTrackOrNull(section) }.take(size)
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration. Draws
    // are split across sections by track count so big sections dominate.
    override suspend fun randomTracks(size: Int): List<Track> =
        withScope { client, scope ->
            val sections = scope.selectedIds
            val shares = if (sections.size == 1) {
                listOf(size)
            } else {
                val counts = trackCounts()
                allocate(size, sections.map { counts[it] ?: 0 })
            }
            fanOut(sections) { section ->
                val share = shares[sections.indexOf(section)]
                if (share == 0) return@fanOut emptyList()
                client.sectionItems(section, ItemType.TRACK, sort = "random", size = share)
                    .mapNotNull { it.toTrackOrNull(section) }
            }.flatMap { it.second }.shuffled()
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        scoped("recently-played-albums/$size") { client, scope ->
            scope.merged(BY_LAST_PLAYED_DESC) { section ->
                client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = PLAYED,
                    sort = "lastViewedAt:desc",
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        scoped("most-played-albums/$size") { client, scope ->
            scope.merged(BY_PLAYS_DESC) { section ->
                client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = PLAYED,
                    sort = "viewCount:desc",
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun genres(): List<Genre> =
        scoped("genres") { client, scope ->
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
                                client.sectionItemCount(
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

    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        scoped("albums-by-genre/$genre/$size") { client, _ ->
            val ids = genreIds(genre)
            if (ids.isEmpty()) return@scoped emptyList()
            merged(ids.keys.toList(), BY_TITLE) { section ->
                client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = listOf("genre" to ids.getValue(section)),
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        scoped("albums-by-year/$fromYear-$toYear/$size") { client, scope ->
            scope.merged(BY_TITLE) { section ->
                client.sectionItems(
                    section,
                    ItemType.ALBUM,
                    filters = yearRange("year", fromYear, toYear),
                    size = size,
                )
            }.mapNotNull { (_, dto) -> dto.toAlbumOrNull() }.take(size)
        }

    // Uncached like randomTracks: CatalogMixesSession owns list stability.
    // Tracks rarely carry their own genre tags in Plex, so filter on the
    // album's genre instead.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withScope { client, _ ->
            val ids = genreIds(genre)
            if (ids.isEmpty()) return@withScope emptyList()
            fanOut(ids.keys.toList()) { section ->
                client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("album.genre" to ids.getValue(section)),
                    sort = "random",
                    size = size,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.flatMap { it.second }.shuffled().take(size)
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withScope { client, scope ->
            fanOut(scope.selectedIds) { section ->
                client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = yearRange("album.year", fromYear, toYear),
                    sort = "random",
                    size = size,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.flatMap { it.second }.shuffled().take(size)
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", "viewCount:desc", BY_PLAYS_DESC, size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", "lastViewedAt:desc", BY_LAST_PLAYED_DESC, size)

    // "Sonically similar" needs Plex Pass + completed analysis, but the
    // related hub's "Similar Artists" is plain Plex music metadata — present
    // whenever the library's artists are matched. Items are in-library by
    // construction (the contract Subsonic reaches by filtering albumCount);
    // the hub is server-wide, so hidden sections are filtered out here.
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        scoped("similar-artists/$artistId") { client, scope ->
            val selected = scope.selectedIds.toSet()
            client.related(artistId)
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
        return withScope { client, scope ->
            // Comma-separated filter values are OR'd by Plex: one request per
            // section draws random tracks across the seed and all similar artists.
            val ids = (listOf(artistId) + similar.map { it.id }).joinToString(",")
            fanOut(scope.selectedIds) { section ->
                client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("artist.id" to ids),
                    sort = "random",
                    size = count,
                ).mapNotNull { it.toTrackOrNull(section) }
            }.flatMap { it.second }.shuffled().take(count)
        }
    }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        scoped("top-songs/$artistName/$count") { client, scope ->
            // The contract keys by artist NAME (a Subsonic API quirk); resolve
            // to a rating key first — the first section holding it wins.
            val match = fanOut(scope.selectedIds) { section ->
                client.sectionItems(section, ItemType.ARTIST, filters = listOf("title" to artistName))
            }.firstNotNullOfOrNull { (section, artists) ->
                artists.firstOrNull { it.title.equals(artistName, ignoreCase = true) }
                    ?.ratingKey?.let { section to it }
            } ?: return@scoped emptyList()
            val (section, key) = match

            suspend fun tier(filters: List<Pair<String, String>>, sort: String) =
                client.sectionItems(
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
        trackId in cached("favorite-track-ids") { client ->
            fanOut(libraries().map { it.id }) { section ->
                client.sectionItems(section, ItemType.TRACK, filters = LOVED)
            }.flatMap { it.second }.mapNotNullTo(HashSet()) { it.ratingKey }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { client ->
            client.rate(trackId, if (favorite) LOVED_RATING else CLEAR_RATING)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { client ->
            client.rate(albumId, if (favorite) LOVED_RATING else CLEAR_RATING)
            // Albums carry their loved state; every cached album list is
            // stale the moment a rating changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { client ->
            val dto = client.metadata(id)
            AlbumDetail(
                album = dto.toAlbumOrNull()
                    ?: throw MusicException.NotFound("Album $id has no rating key"),
                tracks = client.children(id).mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { client ->
            client.playlists().mapNotNull { it.toPlaylistOrNull() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { client ->
            val dto = client.metadata(id)
            PlaylistDetail(
                playlist = dto.toPlaylistOrNull()
                    ?: throw MusicException.NotFound("Playlist $id not found"),
                tracks = client.playlistItems(id).mapNotNull { it.toTrackOrNull() },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { client ->
            client.metadata(id).toTrackOrNull()
                ?: throw MusicException.NotFound("Track $id has no playable media")
        }

    // Metadata carries librarySectionID; candidates are informational here.
    override suspend fun trackLibraryId(trackId: String, candidates: Set<String>): String? =
        track(trackId).libraryId

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        scoped("search/$query") { client, scope ->
            val perSection = fanOut(scope.selectedIds) { section ->
                client.search(section, query, limit = SEARCH_COUNT_PER_TYPE)
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
        withSession(expectedSession) { client ->
            if (submission) {
                // Deterministic mark-played: with session-less direct play the
                // app owns the "counts as played" threshold, not the server.
                client.markPlayed(trackId)
            } else {
                client.timeline(trackId, state = "playing", timeMs = 0, durationMs = null)
            }
        }

    override val playbackReportIntervalMs: Long = TIMELINE_INTERVAL_MS

    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
        expectedSession: ProviderSession?,
    ) = withSession(expectedSession) { client ->
        val plexState = when (state) {
            PlayState.PLAYING -> "playing"
            PlayState.PAUSED -> "paused"
            PlayState.STOPPED -> "stopped"
        }
        client.timeline(trackId, plexState, positionMs, durationMs)
    }

    override fun invalidateCache() = cache.clear()

    private fun plexSession(): PlexSession =
        sessionManager.connectedOrNull()?.session as? PlexSession
            ?: throw MusicException.AuthFailed("Not signed in")

    /** The server's music sections (cached per account); also runs the one-shot legacy seed. */
    private suspend fun librariesFor(session: PlexSession): List<MusicLibrary> {
        val libraries = cache.getOrLoad("${session.cacheFingerprint}/libraries") {
            translatingErrors(session) {
                session.client.musicSections().map { MusicLibrary(it.key, it.title) }
            }
        }
        settings.migrateLegacyLibrarySelection(libraries.map { it.id })
        return libraries
    }

    private suspend fun scope(session: PlexSession): LibraryScope =
        LibraryScope.resolve(librariesFor(session), settings.excludedLibraryIds.first())

    private suspend fun currentScope(): LibraryScope = scope(plexSession())

    /** Unscoped cache: by-id lookups and anything that must ignore the selection. */
    private suspend fun <T : Any> cached(
        key: String,
        loader: suspend (PlexServerClient) -> T,
    ): T {
        val session = plexSession()
        // The loader uses the SAME session snapshot the key was computed from
        // (see SubsonicMusicRepository.cached).
        return cache.getOrLoad("${session.cacheFingerprint}/$key") {
            translatingErrors(session) { loader(session.client) }
        }
    }

    /** Selection-scoped cache: the scope key in the entry key replaces explicit invalidation. */
    private suspend fun <T : Any> scoped(
        key: String,
        loader: suspend (PlexServerClient, LibraryScope) -> T,
    ): T {
        val session = plexSession()
        val scope = scope(session)
        return cache.getOrLoad("${session.cacheFingerprint}/${scope.key}/$key") {
            translatingErrors(session) { loader(session.client, scope) }
        }
    }

    private suspend fun <T> withSession(
        expectedSession: ProviderSession? = null,
        block: suspend (PlexServerClient) -> T,
    ): T {
        val session = plexSession()
        if (expectedSession != null && session !== expectedSession) {
            throw MusicException.AuthFailed("Playback account changed")
        }
        return translatingErrors(session) { block(session.client) }
    }

    /** Uncached, selection-scoped work (random draws, similar tracks). */
    private suspend fun <T> withScope(block: suspend (PlexServerClient, LibraryScope) -> T): T {
        val session = plexSession()
        val scope = scope(session)
        return translatingErrors(session) { block(session.client, scope) }
    }

    /**
     * The [MusicException] boundary: nothing above core-data sees a
     * [PlexException]. Auth rejections also flip the session state.
     */
    private suspend fun <T> translatingErrors(session: PlexSession, block: suspend () -> T): T =
        try {
            block()
        } catch (e: PlexException) {
            if (e is PlexException.AuthFailed) sessionManager.onAuthRejected(session)
            throw e.toMusicException()
        }

    /** Fans [fetch] out over [sections] and sort-merges, each item tagged with its section. */
    private suspend fun merged(
        sections: List<String>,
        comparator: Comparator<in PlexMetadata>,
        fetch: suspend (String) -> List<PlexMetadata>,
    ): List<Pair<String, PlexMetadata>> =
        mergeSorted(
            fanOut(sections) { section -> fetch(section).map { section to it } }.map { it.second },
            compareBy(comparator) { it.second },
        )

    private suspend fun LibraryScope.merged(
        comparator: Comparator<in PlexMetadata>,
        fetch: suspend (String) -> List<PlexMetadata>,
    ): List<Pair<String, PlexMetadata>> = merged(selectedIds, comparator, fetch)

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
        scoped("track-counts") { client, scope ->
            fanOut(scope.selectedIds) { section ->
                listOf(client.sectionItemCount(section, ItemType.TRACK))
            }.associate { (section, count) -> section to count.single() }
        }

    /** Each selected section's genre directory; genre keys are per section. */
    private suspend fun genreDirectory(): Map<String, List<PlexDirectory>> =
        scoped("genre-directory") { client, scope ->
            fanOut(scope.selectedIds) { section -> client.genres(section) }.toMap()
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
        scoped(key) { client, scope ->
            scope.merged(comparator) { section ->
                client.sectionItems(section, ItemType.ALBUM, filters = PLAYED, sort = sort, size = size)
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

    private fun PlexMetadata.toAlbumOrNull(): Album? = Album(
        id = ratingKey ?: return null,
        title = title,
        artistName = parentTitle,
        artistId = parentRatingKey,
        year = year,
        trackCount = leafCount ?: 0,
        durationSec = ((duration ?: 0L) / 1000L).toInt(),
        artworkUrl = artworkOrNull(),
        isFavorite = userRating == LOVED_RATING.toDouble(),
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

        /**
         * Similar artists pooled into one similarTracks selection; with the
         * seed, matches SimilarMixesSession's 10-artist mix spread.
         */
        const val SIMILAR_TRACK_ARTISTS = 9

        /**
         * Paged hosts need something to page through; same cap as the
         * Subsonic side (SubsonicMusicRepository.SEARCH_COUNT_PER_TYPE).
         */
        const val SEARCH_COUNT_PER_TYPE = 50

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
