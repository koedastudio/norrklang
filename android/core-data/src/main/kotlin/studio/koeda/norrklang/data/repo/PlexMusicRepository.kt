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
import studio.koeda.norrklang.data.session.PlexSession
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexServerClient.ItemType
import studio.koeda.norrklang.plex.PlexUrlBuilder
import studio.koeda.norrklang.plex.model.PlexMetadata

/**
 * [MusicRepository] backed by a Plex Media Server, browsing one music section.
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
    @AppPackageName private val packageName: String,
    @ApplicationScope scope: CoroutineScope,
) : MusicRepository {

    private val cache = TtlCache(ttlMillis = 5 * 60 * 1000L)

    init {
        // Cached entries embed authenticated stream URLs and must never
        // survive a sign-out or account switch (see SubsonicMusicRepository).
        scope.launch {
            sessionManager.state.drop(1).collect { cache.clear() }
        }
    }

    override suspend fun artists(): List<Artist> =
        cached("artists") { client, _, section ->
            client.sectionItems(section, ItemType.ARTIST, sort = "titleSort:asc")
                .mapNotNull { it.toArtistOrNull(sortGroup = it.sortBucket()) }
        }

    override suspend fun artist(id: String): ArtistDetail =
        cached("artist/$id") { client, _, _ ->
            val dto = client.metadata(id)
            ArtistDetail(
                artist = dto.toArtistOrNull()
                    ?: throw MusicException.NotFound("Artist $id has no rating key"),
                // Newest first, like the Subsonic side; unknown years sink.
                albums = client.children(id).mapNotNull { it.toAlbumOrNull() }
                    .sortedByDescending { it.year ?: Int.MIN_VALUE },
            )
        }

    override suspend fun albums(offset: Int, size: Int): List<Album> =
        cached("albums/$offset/$size") { client, _, section ->
            client.sectionItems(
                section,
                ItemType.ALBUM,
                sort = "titleSort:asc",
                start = offset,
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun recentlyAdded(size: Int): List<Album> =
        cached("recent/$size") { client, _, section ->
            client.sectionItems(section, ItemType.ALBUM, sort = "addedAt:desc", size = size)
                .mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun favoriteAlbums(size: Int): List<Album> =
        cached("favorites/$size") { client, _, section ->
            client.sectionItems(section, ItemType.ALBUM, filters = LOVED, size = size)
                .mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun favoriteTracks(): List<Track> =
        cached("favorite-tracks") { client, urls, section ->
            client.sectionItems(section, ItemType.TRACK, filters = LOVED)
                .mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun favoriteArtists(): List<Artist> =
        cached("favorite-artists") { client, _, section ->
            client.sectionItems(section, ItemType.ARTIST, filters = LOVED)
                .mapNotNull { it.toArtistOrNull() }
        }

    override suspend fun recentlyAddedTracks(size: Int): List<Track> =
        cached("recently-added-tracks/$size") { client, urls, section ->
            client.sectionItems(section, ItemType.TRACK, sort = "addedAt:desc", size = size)
                .mapNotNull { it.toTrackOrNull(urls) }
        }

    // Deliberately uncached: the random-mix snapshot (RandomMixSession) owns
    // list stability, and a TTL here would defeat its regeneration.
    override suspend fun randomTracks(size: Int): List<Track> =
        withSession { client, urls, section ->
            client.sectionItems(section, ItemType.TRACK, sort = "random", size = size)
                .mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun recentlyPlayedAlbums(size: Int): List<Album> =
        cached("recently-played-albums/$size") { client, _, section ->
            client.sectionItems(
                section,
                ItemType.ALBUM,
                filters = PLAYED,
                sort = "lastViewedAt:desc",
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun mostPlayedAlbums(size: Int): List<Album> =
        cached("most-played-albums/$size") { client, _, section ->
            client.sectionItems(
                section,
                ItemType.ALBUM,
                filters = PLAYED,
                sort = "viewCount:desc",
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun genres(): List<Genre> =
        cached("genres") { client, _, section ->
            // Plex's genre directory has no song counts, but the home tab's
            // genre mixes rank and threshold on them — count each genre's
            // tracks with a zero-size page (totalSize only, so the fan-out
            // is cheap; OkHttp's per-host cap keeps it polite). album.genre
            // matches the filter randomTracksByGenre draws mixes with.
            coroutineScope {
                client.genres(section).map { genre ->
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

    override suspend fun albumsByGenre(genre: String, size: Int): List<Album> =
        cached("albums-by-genre/$genre/$size") { client, _, section ->
            val id = genreId(genre) ?: return@cached emptyList()
            client.sectionItems(
                section,
                ItemType.ALBUM,
                filters = listOf("genre" to id),
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }

    override suspend fun albumsByYearRange(fromYear: Int, toYear: Int, size: Int): List<Album> =
        cached("albums-by-year/$fromYear-$toYear/$size") { client, _, section ->
            client.sectionItems(
                section,
                ItemType.ALBUM,
                filters = yearRange("year", fromYear, toYear),
                size = size,
            ).mapNotNull { it.toAlbumOrNull() }
        }

    // Uncached like randomTracks: CatalogMixesSession owns list stability.
    // Tracks rarely carry their own genre tags in Plex, so filter on the
    // album's genre instead.
    override suspend fun randomTracksByGenre(genre: String, size: Int): List<Track> =
        withSession { client, urls, section ->
            val id = genreId(genre) ?: return@withSession emptyList()
            client.sectionItems(
                section,
                ItemType.TRACK,
                filters = listOf("album.genre" to id),
                sort = "random",
                size = size,
            ).mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun randomTracksByYearRange(
        fromYear: Int,
        toYear: Int,
        size: Int,
    ): List<Track> =
        withSession { client, urls, section ->
            client.sectionItems(
                section,
                ItemType.TRACK,
                filters = yearRange("album.year", fromYear, toYear),
                sort = "random",
                size = size,
            ).mapNotNull { it.toTrackOrNull(urls) }
        }

    override suspend fun mostPlayedArtists(size: Int): List<Artist> =
        playedArtists("frequent-artists/$size", "viewCount:desc", size)

    override suspend fun recentlyPlayedArtists(size: Int): List<Artist> =
        playedArtists("recent-artists/$size", "lastViewedAt:desc", size)

    // "Sonically similar" needs Plex Pass + completed analysis, but the
    // related hub's "Similar Artists" is plain Plex music metadata — present
    // whenever the library's artists are matched. Items are in-library by
    // construction (the contract Subsonic reaches by filtering albumCount).
    override suspend fun similarArtists(artistId: String, count: Int): List<Artist> =
        cached("similar-artists/$artistId") { client, _, _ ->
            client.related(artistId)
                .flatMap { it.metadata }
                .filter { it.type == "artist" && it.ratingKey != artistId }
                .distinctBy { it.ratingKey }
                .mapNotNull { it.toArtistOrNull() }
        }.take(count)

    // Uncached like randomTracks: SimilarMixesSession owns list stability.
    // Empty when the artist has no similar artists — the "no similarity
    // data" signal the mix sessions' dead-server bail-out relies on.
    override suspend fun similarTracks(artistId: String, count: Int): List<Track> {
        val similar = similarArtists(artistId, SIMILAR_TRACK_ARTISTS)
        if (similar.isEmpty()) return emptyList()
        return withSession { client, urls, section ->
            // Comma-separated filter values are OR'd by Plex: one request
            // draws random tracks across the seed and all similar artists.
            val ids = (listOf(artistId) + similar.map { it.id }).joinToString(",")
            client.sectionItems(
                section,
                ItemType.TRACK,
                filters = listOf("artist.id" to ids),
                sort = "random",
                size = count,
            ).mapNotNull { it.toTrackOrNull(urls) }
        }
    }

    override suspend fun topTracks(artistName: String, count: Int): List<Track> =
        cached("top-songs/$artistName/$count") { client, urls, section ->
            // The contract keys by artist NAME (a Subsonic API quirk); resolve
            // to a rating key first.
            val artist = client.sectionItems(
                section,
                ItemType.ARTIST,
                filters = listOf("title" to artistName),
            ).firstOrNull { it.title.equals(artistName, ignoreCase = true) }
            val key = artist?.ratingKey ?: return@cached emptyList()

            suspend fun tier(filters: List<Pair<String, String>>, sort: String) =
                client.sectionItems(
                    section,
                    ItemType.TRACK,
                    filters = listOf("artist.id" to key) + filters,
                    sort = sort,
                    size = count,
                ).mapNotNull { it.toTrackOrNull(urls) }

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
    // never lags a local change. Cached as an id Set for cheap lookups.
    override suspend fun isFavoriteTrack(trackId: String): Boolean =
        trackId in cached("favorite-track-ids") { _, _, _ ->
            favoriteTracks().mapTo(HashSet()) { it.id }
        }

    override suspend fun setTrackFavorite(trackId: String, favorite: Boolean) =
        withSession { client, _, _ ->
            client.rate(trackId, if (favorite) LOVED_RATING else CLEAR_RATING)
            cache.clear()
        }

    override suspend fun setAlbumFavorite(albumId: String, favorite: Boolean) =
        withSession { client, _, _ ->
            client.rate(albumId, if (favorite) LOVED_RATING else CLEAR_RATING)
            // Albums carry their loved state; every cached album list is
            // stale the moment a rating changes.
            cache.clear()
        }

    override suspend fun album(id: String): AlbumDetail =
        cached("album/$id") { client, urls, _ ->
            val dto = client.metadata(id)
            AlbumDetail(
                album = dto.toAlbumOrNull()
                    ?: throw MusicException.NotFound("Album $id has no rating key"),
                tracks = client.children(id).mapNotNull { it.toTrackOrNull(urls) },
            )
        }

    override suspend fun playlists(): List<Playlist> =
        cached("playlists") { client, _, _ ->
            client.playlists().mapNotNull { it.toPlaylistOrNull() }
        }

    override suspend fun playlist(id: String): PlaylistDetail =
        cached("playlist/$id") { client, urls, _ ->
            val dto = client.metadata(id)
            PlaylistDetail(
                playlist = dto.toPlaylistOrNull()
                    ?: throw MusicException.NotFound("Playlist $id not found"),
                tracks = client.playlistItems(id).mapNotNull { it.toTrackOrNull(urls) },
            )
        }

    override suspend fun track(id: String): Track =
        cached("track/$id") { client, urls, _ ->
            client.metadata(id).toTrackOrNull(urls)
                ?: throw MusicException.NotFound("Track $id has no playable media")
        }

    // Cached so the host's onSearch → onGetSearchResult (paged) sequence hits
    // the server once per query, not once per page.
    override suspend fun search(query: String): SearchResults =
        cached("search/$query") { client, urls, section ->
            val hubs = client.search(section, query, limit = SEARCH_COUNT_PER_TYPE)
            fun hub(type: String) = hubs.filter { it.type == type }.flatMap { it.metadata }
            SearchResults(
                artists = hub("artist").mapNotNull { it.toArtistOrNull() },
                albums = hub("album").mapNotNull { it.toAlbumOrNull() },
                tracks = hub("track").mapNotNull { it.toTrackOrNull(urls) },
            )
        }

    override suspend fun scrobble(trackId: String, submission: Boolean) =
        withSession { client, _, _ ->
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
    ) = withSession { client, _, _ ->
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

    private suspend fun <T : Any> cached(
        key: String,
        loader: suspend (PlexServerClient, PlexUrlBuilder, String) -> T,
    ): T {
        val session = plexSession()
        val scopedKey = "${session.cacheFingerprint}/$key"
        // The loader uses the SAME session snapshot the key was computed from
        // (see SubsonicMusicRepository.cached).
        return cache.getOrLoad(scopedKey) {
            translatingErrors {
                loader(session.client, session.urlBuilder, session.account.sectionId)
            }
        }
    }

    private suspend fun <T> withSession(
        block: suspend (PlexServerClient, PlexUrlBuilder, String) -> T,
    ): T {
        val session = plexSession()
        return translatingErrors {
            block(session.client, session.urlBuilder, session.account.sectionId)
        }
    }

    /**
     * The [MusicException] boundary: nothing above core-data sees a
     * [PlexException]. Auth rejections also flip the session state.
     */
    private suspend fun <T> translatingErrors(block: suspend () -> T): T =
        try {
            block()
        } catch (e: PlexException) {
            if (e is PlexException.AuthFailed) sessionManager.onAuthRejected()
            throw e.toMusicException()
        }

    /**
     * Plex's only integer comparison operators are the strict `>>` and `<<`
     * (`year>=`-style keys are not filters at all — the server ignores them
     * and returns the whole section), so an inclusive [from]..[to] range is
     * widened by one on each side.
     */
    private fun yearRange(field: String, from: Int, to: Int): List<Pair<String, String>> =
        listOf("$field>>" to (from - 1).toString(), "$field<<" to (to + 1).toString())

    /** Case-insensitive genre name → filter id, resolved via the cached directory. */
    private suspend fun genreId(name: String): String? =
        cached("genre-ids") { client, _, section ->
            client.genres(section).associate { it.title.lowercase() to it.key }
        }[name.lowercase()]

    /** Artists behind the album list, order preserved, first occurrence wins. */
    private suspend fun playedArtists(key: String, sort: String, size: Int): List<Artist> =
        cached(key) { client, _, section ->
            client.sectionItems(section, ItemType.ALBUM, filters = PLAYED, sort = sort, size = size)
                .mapNotNull { album ->
                    val artistId = album.parentRatingKey ?: return@mapNotNull null
                    val artistName = album.parentTitle ?: return@mapNotNull null
                    Artist(id = artistId, name = artistName, albumCount = 0, artworkUrl = null)
                }
                .distinctBy { it.id }
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

    /** Null when the track has no part to stream — unplayable entries are dropped. */
    private fun PlexMetadata.toTrackOrNull(urls: PlexUrlBuilder): Track? {
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
            streamUrl = urls.partUrl(partKey),
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
    }
}
