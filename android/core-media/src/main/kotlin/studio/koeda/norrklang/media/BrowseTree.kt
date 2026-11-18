package studio.koeda.norrklang.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Genre
import studio.koeda.norrklang.data.model.RadioStation
import studio.koeda.norrklang.data.model.ReleaseType
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicException
import studio.koeda.norrklang.data.repo.MusicRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.RadioPlayStats
import studio.koeda.norrklang.media.MediaId.AlbumSource

/**
 * Maps browse requests from the car UI onto the active provider's music
 * library.
 *
 * Tree shape (AAOS guidance suggests max depth 5):
 * ```
 * root
 * ├── tab/home        → grid of square buttons in headed sections:
 * │                     "Quick play" (Favourites, New songs, Random songs
 * │                     → track lists; Random albums → plays one random
 * │                     album)
 * │                     "Radio" (the most listened stations → playable,
 * │                     present only once a station has been listened to)
 * │                     "Made for you" (Best of <artist>, Similar to <artist>
 * │                     → track lists, present only when the server has
 * │                     similarity data)
 * ├── tab/playlists   → playlist list → tracks
 * └── tab/library     → list in headed sections:
 *                       "Albums" (Recently added, Recently played,
 *                       Favourite albums → album grids;
 *                       Genres → genre list → Shuffle tile + album grid;
 *                       Decades → decade list → Shuffle tile + album grid;
 *                       All albums → album grid → tracks)
 *                       "Artists" (Favourite artists → artist list;
 *                       All artists → artist list (A–Z headers)
 *                       → artist view: releases in headed sections by
 *                       type, then similar artists (see ArtistSections)
 *                       → tracks)
 *                       "Radio" (Radio stations → playable station list;
 *                       present only when the server has stations)
 * ```
 *
 * When a non-paging host browses an artists/albums node whose flat list
 * would be truncated by media3's legacy bridge, a level of A–Z [Buckets]
 * folders is inserted under the node (see [artistListing]/[albumListing]) —
 * for artists that pushes the deepest level (tracks) two past the "depth 5"
 * the AAOS guidance suggests (one for the Library nesting, one for the
 * bucket folder), which hosts so far tolerate.
 */
internal class BrowseTree(
    private val context: Context,
    private val repository: MusicRepository,
    private val randomMix: RandomMixSession,
    private val similarMixes: SimilarMixesSession,
    private val bestOfMixes: BestOfMixesSession,
    private val containers: ContainerTracks,
    /** Version segment for tile artwork URIs; changes with the library selection. */
    private val tileVersion: suspend () -> String = { "" },
    /** Local radio listening history, most listened first (see RadioPlayCounter). */
    private val radioPlayStats: suspend () -> List<RadioPlayStats> = { emptyList() },
) {

    /** Letter folders a host has opened; children() runs off the main thread. */
    private val browsedArtistBuckets = ConcurrentHashMap.newKeySet<String>()

    /**
     * The artist letter folders opened so far, to refresh when the artist
     * list reloads: a folder's id is its rule, so it outlives the list it
     * was cut from and the current partition cannot name them all.
     */
    fun browsedArtistBuckets(): List<MediaId> = browsedArtistBuckets.map(MediaId::ArtistBucket)

    private val quickPlayGroup = context.getString(R.string.browse_group_quick_play)
    private val madeForYouGroup = context.getString(R.string.browse_group_made_for_you)
    private val albumsGroup = context.getString(R.string.browse_group_albums)
    private val artistsGroup = context.getString(R.string.browse_group_artists)
    private val radioGroup = context.getString(R.string.browse_group_radio)
    private val releaseTypeGroups = mapOf(
        ReleaseType.ALBUM to context.getString(R.string.browse_artist_albums),
        ReleaseType.COMPILATION to context.getString(R.string.browse_artist_compilations),
        ReleaseType.SINGLE_OR_EP to context.getString(R.string.browse_artist_singles_eps),
    )
    private val similarArtistsGroup = context.getString(R.string.browse_artist_similar)
    private val stationSubtitle = context.getString(R.string.browse_radio_station_subtitle)
    private val tabHomeTitle = context.getString(R.string.browse_tab_home)
    private val tabLibraryTitle = context.getString(R.string.browse_tab_library)
    private val tabPlaylistsTitle = context.getString(R.string.browse_tab_playlists)

    private val tabHomeIcon = context.resourceUri(R.drawable.ic_browse_home).toString()
    private val tabLibraryIcon = context.resourceUri(R.drawable.ic_browse_library).toString()
    private val tabPlaylistsIcon = context.resourceUri(R.drawable.ic_browse_playlists).toString()

    val rootItem: MediaItem = MediaItemFactory.browsable(
        mediaId = MediaId.Root,
        title = "Norrklang",
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
    )

    /**
     * Children for [parentId], or null when the id is not browsable.
     *
     * Unpaged hosts send `pageSize == Int.MAX_VALUE` and get the complete
     * list — for albums that means walking the server's 500-item pages.
     * Artists/albums lists too long for one unpaged reply are served as
     * [Buckets] folders instead, so large libraries stay fully browsable.
     */
    suspend fun children(
        parentId: String,
        page: Int = 0,
        pageSize: Int = Int.MAX_VALUE,
    ): List<MediaItem>? =
        when (val id = MediaId.parse(parentId)) {
            MediaId.Root -> rootTabs()
            MediaId.TabHome -> homeButtons()
            MediaId.TabLibrary -> libraryChildren()
            MediaId.HomeRecentlyAdded ->
                albumItems(repository.recentlyAdded(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeFavoriteAlbums ->
                albumItems(repository.favoriteAlbums(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeRecentlyPlayed ->
                albumItems(repository.recentlyPlayedAlbums(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.Genres -> genreRows()
            MediaId.Decades -> decadeRows()
            is MediaId.GenreAlbums -> albumGrid(AlbumSource.Genre(id.name), page, pageSize)
            is MediaId.DecadeAlbums -> albumGrid(AlbumSource.Decade(id.startYear), page, pageSize)
            // Browsing the random mix may regenerate it (see RandomMixSession.browseTracks).
            MediaId.HomeRandomMix ->
                trackItems(randomMix.browseTracks(), MediaId.HomeRandomMix, page, pageSize)
            // Queue-radio provenance token, never a browse node.
            is MediaId.SongRadio -> null
            is MediaId.Container -> trackItems(containers.tracks(id), id, page, pageSize)
            MediaId.HomeFavoriteArtists ->
                Paging.slice(repository.favoriteArtists(), page, pageSize)
                    .map(MediaItemFactory::forArtist)
            MediaId.TabArtists -> artistListing(page, pageSize)
            MediaId.TabAlbums -> albumGrid(AlbumSource.All, page, pageSize)
            MediaId.TabPlaylists ->
                Paging.slice(repository.playlists(), page, pageSize).map(MediaItemFactory::forPlaylist)
            MediaId.TabRadio ->
                Paging.slice(repository.radioStations(), page, pageSize).map { stationItem(it) }
            is MediaId.ArtistBucket -> {
                browsedArtistBuckets += id.key
                Paging.slice(artistBucketMembers(id.key), page, pageSize)
                    // The folder's letter already says what a per-item A–Z
                    // header would — omit the redundant header.
                    .map { MediaItemFactory.forArtist(it, groupTitle = null) }
            }
            is MediaId.AlbumBucket ->
                Paging.slice(albumBucketMembers(id.source, id.key), page, pageSize)
                    .map(MediaItemFactory::forAlbum)
            is MediaId.Artist -> Paging.slice(artistView(id.id), page, pageSize)
            is MediaId.RadioStation, is MediaId.Track, null -> null
        }

    /**
     * The All artists listing. Paging hosts (none of the car hosts — they never pass
     * paging options) page the flat list; non-paging hosts get the flat list
     * only while it fits one reply, and [Buckets] folders beyond that.
     */
    private suspend fun artistListing(page: Int, pageSize: Int): List<MediaItem> {
        val artists = repository.artists()
        return when {
            Paging.isPaged(pageSize) ->
                Paging.slice(artists, page, pageSize).map(MediaItemFactory::forArtist)
            Buckets.needed(artists.size) -> artistBucketItems(artists)
            else -> artists.map(MediaItemFactory::forArtist)
        }
    }

    /** An album grid: a genre's or decade's Shuffle tile (first page only) ahead of its albums. */
    private suspend fun albumGrid(source: AlbumSource, page: Int, pageSize: Int): List<MediaItem> {
        val shuffle = source.mix?.takeIf { page == 0 }?.let { shuffleTile(it, tileVersion()) }
        return listOfNotNull(shuffle) + albumListing(source, page, pageSize)
    }

    /** [source]'s albums; bucket policy as in [artistListing]. */
    private suspend fun albumListing(source: AlbumSource, page: Int, pageSize: Int): List<MediaItem> {
        if (Paging.isPaged(pageSize)) {
            val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            return Paging.window(offset, pageSize, SERVER_PAGE_SIZE, albumsOf(source))
                .map(MediaItemFactory::forAlbum)
        }
        val albums = allAlbums(source)
        return if (Buckets.needed(albums.size)) {
            albumBucketItems(source, albums)
        } else {
            albums.map(MediaItemFactory::forAlbum)
        }
    }

    /** The complete album list of [source], walked in server pages (TTL-cached per page). */
    private suspend fun allAlbums(source: AlbumSource): List<Album> =
        Paging.window(offset = 0, size = null, chunkSize = SERVER_PAGE_SIZE, albumsOf(source))

    private fun albumsOf(source: AlbumSource): suspend (Int, Int) -> List<Album> =
        when (source) {
            AlbumSource.All -> repository::albums
            is AlbumSource.Genre -> { offset, size -> repository.albumsByGenre(source.name, offset, size) }
            is AlbumSource.Decade -> { offset, size ->
                repository.albumsByYearRange(source.startYear, source.startYear + 9, offset, size)
            }
        }

    /**
     * The artist's letter group: the server-provided index bucket when it is
     * a plain letter (Subsonic index name, Plex titleSort initial), else
     * derived from the name — so folders match the server's collation.
     */
    private fun artistLetter(artist: Artist): String =
        artist.sortGroup?.takeIf { it.length == 1 } ?: Buckets.letterKey(artist.name)

    private fun artistBucketItems(artists: List<Artist>): List<MediaItem> =
        Buckets.partition(artists, ::artistLetter, Artist::name)
            .map { artistBucketItem(it.key, it.label, it.items) }

    private fun artistBucketItem(key: String, label: String, members: List<Artist>): MediaItem =
        MediaItemFactory.browsable(
            mediaId = MediaId.ArtistBucket(key),
            title = label,
            artworkUrl = members.firstNotNullOfOrNull { it.artworkUrl },
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
            childrenStyle = MediaItemFactory.listChildrenExtras(),
        )

    private suspend fun artistBucketMembers(key: String): List<Artist> =
        Buckets.select(repository.artists(), key, ::artistLetter, Artist::name)

    private fun albumBucketItems(source: AlbumSource, albums: List<Album>): List<MediaItem> =
        Buckets.partition(albums, { Buckets.letterKey(it.title) }, Album::title)
            .map { albumBucketItem(source, it.key, it.label, it.items) }

    private fun albumBucketItem(
        source: AlbumSource,
        key: String,
        label: String,
        members: List<Album>,
    ): MediaItem =
        MediaItemFactory.browsable(
            mediaId = MediaId.AlbumBucket(source, key),
            title = label,
            artworkUrl = members.firstNotNullOfOrNull { it.artworkUrl },
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            // The A–Z folders render as list rows; each folder's albums
            // stay an artwork grid.
            childrenStyle = MediaItemFactory.asListItem(MediaItemFactory.gridChildrenExtras()),
        )

    private suspend fun albumBucketMembers(source: AlbumSource, key: String): List<Album> =
        Buckets.select(allAlbums(source), key, { Buckets.letterKey(it.title) }, Album::title)

    /** Genre rows A–Z; when too many for one unpaged reply, the biggest by song count. */
    private suspend fun genreRows(): List<MediaItem> =
        repository.genres()
            .filter { it.songCount > 0 }
            .sortedByDescending { it.songCount }
            .take(Buckets.MAX_UNPAGED_CHILDREN)
            .sortedBy { it.name }
            .map(::genreRow)

    private fun genreRow(genre: Genre): MediaItem = MediaItemFactory.browsable(
        mediaId = MediaId.GenreAlbums(genre.name),
        title = genre.name,
        subtitle = context.resources.getQuantityString(
            R.plurals.browse_song_count,
            genre.songCount,
            genre.songCount,
        ),
        mediaType = MediaMetadata.MEDIA_TYPE_GENRE,
        childrenStyle = MediaItemFactory.gridChildrenExtras(),
    )

    /** Every decade since [FIRST_DECADE] holding at least one album, oldest first. */
    private suspend fun decadeRows(): List<MediaItem> = coroutineScope {
        (FIRST_DECADE..currentYear() step 10).map { start ->
            async { start.takeIf { repository.albumsByYearRange(start, start + 9, 0, 1).isNotEmpty() } }
        }.awaitAll().filterNotNull().map(::decadeRow)
    }

    private fun decadeRow(startYear: Int): MediaItem = MediaItemFactory.browsable(
        mediaId = MediaId.DecadeAlbums(startYear),
        title = decadeTitle(startYear),
        mediaType = MediaMetadata.MEDIA_TYPE_YEAR,
        childrenStyle = MediaItemFactory.gridChildrenExtras(),
    )

    private fun decadeTitle(startYear: Int): String = context.getString(R.string.browse_decade, startYear)

    private fun currentYear(): Int = Calendar.getInstance().get(Calendar.YEAR)

    /** The playable tile heading a genre or decade grid: the catalog's random mix for it. */
    private fun shuffleTile(mix: MediaId.CatalogMix, version: String): MediaItem {
        val (kind, key, name) = when (mix) {
            is MediaId.GenreMix -> Triple(HomeMixKind.GENRE, mix.name, mix.name)
            is MediaId.DecadeMix ->
                Triple(HomeMixKind.DECADE, mix.startYear.toString(), decadeTitle(mix.startYear))
        }
        return MediaItemFactory.playable(
            mediaId = mix,
            title = context.getString(R.string.browse_shuffle, name),
            artworkUrl = mixCollageUri(kind, key, version),
            mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        )
    }

    /** One host page of [tracks] as browse items carrying [container] as queue context. */
    private fun trackItems(
        tracks: List<Track>,
        container: MediaId.Container,
        page: Int,
        pageSize: Int,
    ): List<MediaItem> =
        Paging.slice(tracks, page, pageSize).map { MediaItemFactory.forTrack(it, container) }

    /** One host page of [albums] as browse items. */
    private fun albumItems(albums: List<Album>, page: Int, pageSize: Int): List<MediaItem> =
        Paging.slice(albums, page, pageSize).map(MediaItemFactory::forAlbum)

    /**
     * An artist's view, sectioned per [ArtistSections]. Similar artists are
     * fetched alongside the releases; a server that can't answer just hides
     * that section.
     */
    private suspend fun artistView(artistId: String): List<MediaItem> = coroutineScope {
        val detail = async { repository.artist(artistId) }
        val similar = async { similarArtistsOrEmpty(artistId) }
        val view = ArtistSections.build(detail.await().albums, similar.await())
        view.sections.flatMap { sectionItems(it, view.labeled) }
    }

    private fun sectionItems(section: ArtistSections.Section, labeled: Boolean): List<MediaItem> =
        when (section) {
            is ArtistSections.Section.Releases -> {
                val group = releaseTypeGroups.getValue(section.type).takeIf { labeled }
                section.albums.map { MediaItemFactory.forAlbum(it, groupTitle = group) }
            }
            is ArtistSections.Section.SimilarArtists -> {
                val group = similarArtistsGroup.takeIf { labeled }
                section.artists.map { MediaItemFactory.forArtist(it, groupTitle = group) }
            }
        }

    private suspend fun similarArtistsOrEmpty(artistId: String): List<Artist> =
        emptyOnError { repository.similarArtists(artistId, ArtistSections.SIMILAR_ARTISTS_SHOWN) }

    /** A single item for `onGetItem`. */
    suspend fun item(mediaId: String): MediaItem? =
        when (val id = MediaId.parse(mediaId)) {
            MediaId.Root -> rootItem
            MediaId.TabHome -> homeTab()
            MediaId.HomeRecentlyAdded, MediaId.HomeFavoriteAlbums, MediaId.HomeFavoriteArtists,
            MediaId.HomeRecentlyPlayed, MediaId.HomeFavoriteSongs, MediaId.HomeRecentlyAddedSongs,
            MediaId.HomeRandomMix, MediaId.HomeRandomAlbums, MediaId.Genres, MediaId.Decades,
            MediaId.TabArtists, MediaId.TabAlbums, MediaId.TabRadio,
            -> HomeTile.forMediaId(id)?.let { staticTile(it, tileVersion()) }
            is MediaId.HomeSimilar ->
                similarMixes.mixFor(id.artistId)?.let { similarMixButton(it, tileVersion()) }
            is MediaId.HomeBestOf ->
                bestOfMixes.mixFor(id.artistId)?.let { bestOfMixButton(it, tileVersion()) }
            is MediaId.GenreAlbums -> repository.genres().firstOrNull { it.name == id.name }?.let(::genreRow)
            is MediaId.DecadeAlbums -> decadeRow(id.startYear)
            is MediaId.CatalogMix -> shuffleTile(id, tileVersion())
            MediaId.TabLibrary -> libraryTab()
            MediaId.TabPlaylists -> playlistsTab()
            is MediaId.RadioStation -> stationItem(repository.radioStation(id.id))
            is MediaId.ArtistBucket ->
                Buckets.labelFor(id.key)?.let { artistBucketItem(id.key, it, artistBucketMembers(id.key)) }
            is MediaId.AlbumBucket ->
                Buckets.labelFor(id.key)?.let {
                    albumBucketItem(id.source, id.key, it, albumBucketMembers(id.source, id.key))
                }
            is MediaId.Artist -> MediaItemFactory.forArtist(repository.artist(id.id).artist)
            is MediaId.Album -> MediaItemFactory.forAlbum(repository.album(id.id).album)
            is MediaId.Playlist -> MediaItemFactory.forPlaylist(repository.playlist(id.id).playlist)
            is MediaId.Track -> MediaItemFactory.forTrack(repository.track(id.id), id.container)
            is MediaId.SongRadio -> null
            null -> null
        }

    private fun rootTabs(): List<MediaItem> =
        listOf(homeTab(), playlistsTab(), libraryTab())

    /**
     * The Library tab's list in headed sections: the album collections and
     * the full album catalog under "Albums", the artist catalog under
     * "Artists", and the station list under "Radio" when the server has any.
     */
    private suspend fun libraryChildren(): List<MediaItem> {
        val version = tileVersion()
        return staticTiles(HomeTile.Section.ALBUMS, version) +
            staticTiles(HomeTile.Section.ARTISTS, version) +
            if (radioStationsOrEmpty().isNotEmpty()) {
                staticTiles(HomeTile.Section.RADIO, version)
            } else {
                emptyList()
            }
    }

    private suspend fun radioStationsOrEmpty(): List<RadioStation> =
        emptyOnError { repository.radioStations() }

    /** For the decorating sections: a server that can't answer hides them, not the node. */
    private suspend fun <T> emptyOnError(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (_: MusicException) {
            emptyList()
        }

    /**
     * The home tab's grid of square buttons in headed sections. Items sharing
     * a group title must be contiguous for hosts to render the headers.
     *
     * "Made for you" only peeks its sessions' snapshots — generation runs
     * in the background at service start (NorrklangMediaLibraryService),
     * which notifies this tab when tiles are ready. No snapshot, or a library
     * without the needed data, just means no section.
     */
    private suspend fun homeButtons(): List<MediaItem> {
        val version = tileVersion()
        return staticTiles(HomeTile.Section.QUICK_PLAY, version) +
            topStationButtons() +
            // The take()s pin the 3 + 6 tile budget at the display site too.
            bestOfMixes.currentMixes()
                .take(BestOfMixesSession.MAX_MIXES)
                .map { bestOfMixButton(it, version) } +
            similarMixes.currentMixes()
                .take(SimilarMixesSession.MAX_MIXES)
                .map { similarMixButton(it, version) }
    }

    /**
     * The home tab's "Radio" section: the [HOME_RADIO_TILES] most listened
     * stations (local history, see RadioPlayCounter) that still exist on the
     * server. No history, or no stations, means no section.
     */
    private suspend fun topStationButtons(): List<MediaItem> {
        val stats = radioPlayStats()
        if (stats.isEmpty()) return emptyList()
        val stations = radioStationsOrEmpty().associateBy { it.id }
        return stats.mapNotNull { stations[it.stationId] }
            .take(HOME_RADIO_TILES)
            .map { stationItem(it, radioGroup) }
    }

    /** A station as a browse row/tile; the image is composed by ArtworkProvider (station logo or glyph). */
    private fun stationItem(station: RadioStation, group: String? = null): MediaItem =
        MediaItemFactory.forStation(
            station,
            artworkUrl = stationArtwork(station),
            subtitle = stationSubtitle,
            groupTitle = group,
        )

    /** The station as handed to the player — the station name under the ICY song title. */
    fun playableStation(station: RadioStation): MediaItem = MediaItemFactory.forStation(
        station,
        artworkUrl = stationArtwork(station),
        artistLine = station.name,
    )

    private fun stationArtwork(station: RadioStation): String =
        ArtworkContract.stationUri(context.packageName, station.id)

    /** [section]'s static tiles in [HomeTile]'s declaration (= display) order. */
    private fun staticTiles(section: HomeTile.Section, version: String): List<MediaItem> =
        HomeTile.entries.filter { it.section == section }.map { staticTile(it, version) }

    /** The browse item for one static tile (generated collage artwork). */
    private fun staticTile(tile: HomeTile, version: String): MediaItem {
        val title = context.getString(tile.titleRes)
        // Collage served by ArtworkProvider — see HomeButtonArtwork.
        val artwork = ArtworkContract.homeUri(context.packageName, tile.artworkKey, version)
        return when (tile.shape) {
            HomeTile.Shape.TRACK_LIST -> trackListTile(tile.mediaId, title, artwork, quickPlayGroup)
            HomeTile.Shape.PLAYABLE ->
                MediaItemFactory.playable(tile.mediaId, title, artwork, MediaMetadata.MEDIA_TYPE_ALBUM, quickPlayGroup)
            HomeTile.Shape.ALBUM_GRID -> albumGridTile(tile.mediaId, title, artwork)
            HomeTile.Shape.GENRE_LIST ->
                listTile(tile.mediaId, title, artwork, MediaMetadata.MEDIA_TYPE_FOLDER_GENRES, albumsGroup)
            HomeTile.Shape.DECADE_LIST ->
                listTile(tile.mediaId, title, artwork, MediaMetadata.MEDIA_TYPE_FOLDER_YEARS, albumsGroup)
            HomeTile.Shape.ARTIST_LIST ->
                listTile(tile.mediaId, title, artwork, MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS, artistsGroup)
            HomeTile.Shape.STATION_LIST ->
                listTile(tile.mediaId, title, artwork, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS, radioGroup)
        }
    }

    /** A home tile opening a track list (the mixes and favourite songs), like an album or playlist. */
    private fun trackListTile(
        mediaId: MediaId,
        title: String,
        artworkUrl: String?,
        group: String,
    ): MediaItem = MediaItemFactory.browsable(
        mediaId = mediaId,
        title = title,
        artworkUrl = artworkUrl,
        mediaType = MediaMetadata.MEDIA_TYPE_PLAYLIST,
        childrenStyle = MediaItemFactory.listChildrenExtras(),
        groupTitle = group,
    )

    /** A Library tile in the "Albums" section opening an album grid. */
    private fun albumGridTile(mediaId: MediaId, title: String, artworkUrl: String): MediaItem =
        MediaItemFactory.browsable(
            mediaId = mediaId,
            title = title,
            artworkUrl = artworkUrl,
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            childrenStyle = MediaItemFactory.gridChildrenExtras(),
            groupTitle = albumsGroup,
        )

    /** A Library tile opening a list (artists, stations, genres, decades). */
    private fun listTile(
        mediaId: MediaId,
        title: String,
        artworkUrl: String,
        mediaType: Int,
        group: String,
    ): MediaItem =
        MediaItemFactory.browsable(
            mediaId = mediaId,
            title = title,
            artworkUrl = artworkUrl,
            mediaType = mediaType,
            childrenStyle = MediaItemFactory.listChildrenExtras(),
            groupTitle = group,
        )

    // Mix tile images served by ArtworkProvider — see HomeButtonArtwork.
    private fun similarMixButton(mix: ArtistMixesSession.Mix, version: String) = trackListTile(
        MediaId.HomeSimilar(mix.artist.id),
        context.getString(R.string.browse_home_similar_to, mix.artist.name),
        mixCollageUri(HomeMixKind.SIMILAR, mix.artist.id, version),
        madeForYouGroup,
    )

    private fun bestOfMixButton(mix: ArtistMixesSession.Mix, version: String) = trackListTile(
        MediaId.HomeBestOf(mix.artist.id),
        context.getString(R.string.browse_home_best_of, mix.artist.name),
        mixCollageUri(HomeMixKind.BEST_OF, mix.artist.id, version),
        madeForYouGroup,
    )

    private fun mixCollageUri(kind: HomeMixKind, key: String, version: String): String =
        ArtworkContract.homeMixUri(context.packageName, kind.pathSegment, key, version)

    private fun homeTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabHome,
        title = tabHomeTitle,
        artworkUrl = tabHomeIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        childrenStyle = MediaItemFactory.gridChildrenExtras(),
    )

    private fun libraryTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabLibrary,
        title = tabLibraryTitle,
        artworkUrl = tabLibraryIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_MIXED,
        childrenStyle = MediaItemFactory.listChildrenExtras(),
    )

    private fun playlistsTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabPlaylists,
        title = tabPlaylistsTitle,
        artworkUrl = tabPlaylistsIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS,
        childrenStyle = MediaItemFactory.listChildrenExtras(),
    )

    companion object {
        // Subsonic caps getAlbumList2 at 500 items per request.
        const val SERVER_PAGE_SIZE = 500

        /** Stations on the home tab's "Radio" section. */
        const val HOME_RADIO_TILES = 3

        /** Decades before this are folded away — car libraries rarely reach them. */
        const val FIRST_DECADE = 1950
    }
}
