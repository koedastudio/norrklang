package studio.koeda.norrklang.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import studio.koeda.norrklang.data.artwork.ArtworkContract
import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Maps browse requests from the car UI onto the Navidrome library.
 *
 * Tree shape (max depth 5 allowed by AAOS; we use 5):
 * ```
 * root
 * ├── tab/home        → grid of square buttons in headed sections:
 * │                     "Quick play" (Random mix, Favourites → track lists)
 * │                     "Made for you" (Best of <artist>, Similar to <artist>
 * │                     → track lists, present only when the server has
 * │                     similarity data)
 * │                     "Genre mixes" (biggest genres → track lists)
 * │                     "Decade mixes" (populated decades → track lists)
 * │                     "Browse" (Recently played, Most played, New albums,
 * │                     Favourite albums → album grids)
 * ├── tab/artists     → artist list (A–Z headers) → artist's albums → tracks
 * ├── tab/albums      → album grid  → tracks
 * └── tab/playlists   → playlist list → tracks
 * ```
 */
internal class BrowseTree(
    private val context: Context,
    private val repository: MusicRepository,
    private val randomMix: RandomMixSession,
    private val similarMixes: SimilarMixesSession,
    private val bestOfMixes: BestOfMixesSession,
    private val catalogMixes: CatalogMixesSession,
) {

    private val quickPlayGroup = context.getString(R.string.browse_group_quick_play)
    private val madeForYouGroup = context.getString(R.string.browse_group_made_for_you)
    private val genreMixesGroup = context.getString(R.string.browse_group_genre_mixes)
    private val decadeMixesGroup = context.getString(R.string.browse_group_decade_mixes)
    private val browseGroup = context.getString(R.string.browse_group_browse)

    private val tabHomeTitle = context.getString(R.string.browse_tab_home)
    private val tabArtistsTitle = context.getString(R.string.browse_tab_artists)
    private val tabAlbumsTitle = context.getString(R.string.browse_tab_albums)
    private val tabPlaylistsTitle = context.getString(R.string.browse_tab_playlists)

    private val tabHomeIcon = context.resourceUri(R.drawable.ic_browse_home).toString()
    private val tabArtistsIcon = context.resourceUri(R.drawable.ic_browse_artists).toString()
    private val tabAlbumsIcon = context.resourceUri(R.drawable.ic_browse_albums).toString()
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
     * list — for albums that means walking the server's 500-item pages, so
     * large libraries stay fully browsable.
     */
    suspend fun children(
        parentId: String,
        page: Int = 0,
        pageSize: Int = Int.MAX_VALUE,
    ): List<MediaItem>? =
        when (val id = MediaId.parse(parentId)) {
            MediaId.Root -> rootTabs()
            MediaId.TabHome -> homeButtons()
            MediaId.HomeRecentlyAdded ->
                albumItems(repository.recentlyAdded(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeFavoriteAlbums ->
                albumItems(repository.favoriteAlbums(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeRecentlyPlayed ->
                albumItems(repository.recentlyPlayedAlbums(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeMostPlayed ->
                albumItems(repository.mostPlayedAlbums(SERVER_PAGE_SIZE), page, pageSize)
            MediaId.HomeFavoriteSongs ->
                trackItems(repository.favoriteTracks(), MediaId.HomeFavoriteSongs, page, pageSize)
            MediaId.HomeRandomMix ->
                trackItems(randomMix.browseTracks(), MediaId.HomeRandomMix, page, pageSize)
            is MediaId.HomeSimilar ->
                trackItems(similarMixes.queueTracks(id.artistId), id, page, pageSize)
            is MediaId.HomeBestOf ->
                trackItems(bestOfMixes.queueTracks(id.artistId), id, page, pageSize)
            is MediaId.CatalogMix -> trackItems(catalogMixes.queueTracks(id), id, page, pageSize)
            MediaId.TabArtists ->
                Paging.slice(repository.artists(), page, pageSize).map(MediaItemFactory::forArtist)
            MediaId.TabAlbums -> albumsPage(page, pageSize).map(MediaItemFactory::forAlbum)
            MediaId.TabPlaylists ->
                Paging.slice(repository.playlists(), page, pageSize).map(MediaItemFactory::forPlaylist)
            is MediaId.Artist -> albumItems(repository.artist(id.id).albums, page, pageSize)
            is MediaId.Album -> trackItems(repository.album(id.id).tracks, id, page, pageSize)
            is MediaId.Playlist -> trackItems(repository.playlist(id.id).tracks, id, page, pageSize)
            is MediaId.Track, null -> null
        }

    private suspend fun albumsPage(page: Int, pageSize: Int) =
        if (Paging.isPaged(pageSize)) {
            val offset = (page.toLong() * pageSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            Paging.window(offset, pageSize, SERVER_PAGE_SIZE, repository::albums)
        } else {
            Paging.window(offset = 0, size = null, chunkSize = SERVER_PAGE_SIZE, repository::albums)
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

    /** A single item for `onGetItem`. */
    suspend fun item(mediaId: String): MediaItem? =
        when (val id = MediaId.parse(mediaId)) {
            MediaId.Root -> rootItem
            MediaId.TabHome -> homeTab()
            MediaId.HomeRecentlyAdded, MediaId.HomeFavoriteAlbums,
            MediaId.HomeRecentlyPlayed, MediaId.HomeMostPlayed,
            MediaId.HomeFavoriteSongs, MediaId.HomeRandomMix,
            -> HomeTile.forMediaId(id)?.let(::staticTile)
            is MediaId.HomeSimilar ->
                similarMixes.currentMixes()
                    .firstOrNull { it.artist.id == id.artistId }
                    ?.let(::similarMixButton)
            is MediaId.HomeBestOf ->
                bestOfMixes.currentMixes()
                    .firstOrNull { it.artist.id == id.artistId }
                    ?.let(::bestOfMixButton)
            is MediaId.HomeGenre ->
                catalogMixes.currentGenreMixes()
                    .firstOrNull { it.name == id.name }
                    ?.let(::genreMixButton)
            is MediaId.HomeDecade ->
                catalogMixes.currentDecadeMixes()
                    .firstOrNull { it.startYear == id.startYear }
                    ?.let(::decadeMixButton)
            MediaId.TabArtists -> artistsTab()
            MediaId.TabAlbums -> albumsTab()
            MediaId.TabPlaylists -> playlistsTab()
            is MediaId.Artist -> MediaItemFactory.forArtist(repository.artist(id.id).artist)
            is MediaId.Album -> MediaItemFactory.forAlbum(repository.album(id.id).album)
            is MediaId.Playlist -> MediaItemFactory.forPlaylist(repository.playlist(id.id).playlist)
            is MediaId.Track -> MediaItemFactory.forTrack(repository.track(id.id), id.container)
            null -> null
        }

    private fun rootTabs(): List<MediaItem> =
        listOf(homeTab(), artistsTab(), albumsTab(), playlistsTab())

    /**
     * The home tab's grid of square buttons in headed sections. Items sharing
     * a group title must be contiguous for hosts to render the headers.
     *
     * The mix sections only peek their sessions' snapshots — generation runs
     * in the background at service start (NorrklangMediaLibraryService),
     * which notifies this tab when tiles are ready. No snapshot, or a library
     * without the needed data, just means no section.
     */
    private suspend fun homeButtons(): List<MediaItem> =
        staticTiles(HomeTile.Section.QUICK_PLAY) +
            // The take()s pin the 3 + 3 tile budget at the display site too.
            bestOfMixes.currentMixes()
                .take(BestOfMixesSession.MAX_MIXES)
                .map(::bestOfMixButton) +
            similarMixes.currentMixes()
                .take(SimilarMixesSession.MAX_MIXES)
                .map(::similarMixButton) +
            catalogMixes.currentGenreMixes().map(::genreMixButton) +
            catalogMixes.currentDecadeMixes().map(::decadeMixButton) +
            staticTiles(HomeTile.Section.BROWSE)

    /** [section]'s static tiles in [HomeTile]'s declaration (= display) order. */
    private fun staticTiles(section: HomeTile.Section): List<MediaItem> =
        HomeTile.entries.filter { it.section == section }.map(::staticTile)

    /** The browse item for one static home tile (generated collage artwork). */
    private fun staticTile(tile: HomeTile): MediaItem {
        val title = context.getString(tile.titleRes)
        // Collage served by ArtworkProvider — see HomeButtonArtwork.
        val artwork = ArtworkContract.homeUri(context.packageName, tile.artworkKey)
        return when (tile.section) {
            HomeTile.Section.QUICK_PLAY ->
                trackListTile(tile.mediaId, title, artwork, quickPlayGroup)
            HomeTile.Section.BROWSE -> albumGridTile(tile.mediaId, title, artwork)
        }
    }

    /** A home tile opening a track list (the mixes and favourite songs). */
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

    /** A home tile in the "Browse" section opening an album grid. */
    private fun albumGridTile(mediaId: MediaId, title: String, artworkUrl: String): MediaItem =
        MediaItemFactory.browsable(
            mediaId = mediaId,
            title = title,
            artworkUrl = artworkUrl,
            mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
            childrenStyle = MediaItemFactory.gridChildrenExtras(),
            groupTitle = browseGroup,
        )

    private fun similarMixButton(mix: ArtistMixesSession.Mix) = trackListTile(
        MediaId.HomeSimilar(mix.artist.id),
        context.getString(R.string.browse_home_similar_to, mix.artist.name),
        mix.artworkUrl,
        madeForYouGroup,
    )

    private fun bestOfMixButton(mix: ArtistMixesSession.Mix) = trackListTile(
        MediaId.HomeBestOf(mix.artist.id),
        context.getString(R.string.browse_home_best_of, mix.artist.name),
        mix.artworkUrl,
        madeForYouGroup,
    )

    // Collages served by ArtworkProvider — see HomeButtonArtwork.
    private fun genreMixButton(mix: CatalogMixesSession.GenreMix) = trackListTile(
        MediaId.HomeGenre(mix.name),
        mix.name,
        mixCollageUri(CatalogMixKind.GENRE, mix.name),
        genreMixesGroup,
    )

    private fun decadeMixButton(mix: CatalogMixesSession.DecadeMix) = trackListTile(
        MediaId.HomeDecade(mix.startYear),
        context.getString(R.string.browse_home_decade_mix, mix.startYear),
        mixCollageUri(CatalogMixKind.DECADE, mix.startYear.toString()),
        decadeMixesGroup,
    )

    private fun mixCollageUri(kind: CatalogMixKind, key: String): String =
        ArtworkContract.homeMixUri(context.packageName, kind.pathSegment, key)

    private fun homeTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabHome,
        title = tabHomeTitle,
        artworkUrl = tabHomeIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        childrenStyle = MediaItemFactory.gridChildrenExtras(),
    )

    private fun artistsTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabArtists,
        title = tabArtistsTitle,
        artworkUrl = tabArtistsIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ARTISTS,
        childrenStyle = MediaItemFactory.listChildrenExtras(),
    )

    private fun albumsTab() = MediaItemFactory.browsable(
        mediaId = MediaId.TabAlbums,
        title = tabAlbumsTitle,
        artworkUrl = tabAlbumsIcon,
        mediaType = MediaMetadata.MEDIA_TYPE_FOLDER_ALBUMS,
        childrenStyle = MediaItemFactory.gridChildrenExtras(),
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
    }
}
