package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The single table behind the static browse tiles — browse id, artwork
 * key, title, icon, shape and cover source in one entry, so a tile cannot
 * drift apart between [BrowseTree] and [HomeButtonArtwork].
 *
 * QUICK_PLAY tiles render on the Home tab, ALBUMS, ARTISTS and RADIO tiles
 * on the Library tab. Declaration order is display order within each section.
 *
 * Entries with a [coverUrls] source render a cover collage; the Library
 * tiles deliberately declare none, so they always render as a plain
 * centered icon instead.
 */
internal enum class HomeTile(
    val mediaId: MediaId,
    /** The `home/<key>` path segment [ArtworkProvider] serves the image under. */
    val artworkKey: String,
    val titleRes: Int,
    val section: Section,
    val shape: Shape,
    /** Icon in the rendered tile's badge (or centered on icon-only tiles). */
    val iconRes: Int,
    /** Artwork URLs behind the tile — the collage source; none = icon-only. */
    val coverUrls: suspend (MusicRepository, RandomMixSession) -> List<String> =
        { _, _ -> emptyList() },
) {
    FAVORITE_SONGS(
        mediaId = MediaId.HomeFavoriteSongs,
        artworkKey = "favorite-songs",
        titleRes = R.string.browse_home_favorite_songs,
        section = Section.QUICK_PLAY,
        shape = Shape.TRACK_LIST,
        iconRes = R.drawable.ic_home_favorite_songs,
        coverUrls = { repository, _ ->
            repository.favoriteTracks().mapNotNull { it.artworkUrl }
        },
    ),
    NEW_SONGS(
        mediaId = MediaId.HomeRecentlyAddedSongs,
        artworkKey = "recently-added-songs",
        titleRes = R.string.browse_home_new_songs,
        section = Section.QUICK_PLAY,
        shape = Shape.TRACK_LIST,
        iconRes = R.drawable.ic_home_recently_added,
        // The newest albums' covers — the same covers the mix's leading
        // tracks carry, without the track flattening behind the browse list.
        coverUrls = { repository, _ ->
            repository.recentlyAdded().mapNotNull { it.artworkUrl }
        },
    ),
    RANDOM_SONGS(
        mediaId = MediaId.HomeRandomMix,
        artworkKey = "random-mix",
        titleRes = R.string.browse_home_random_songs,
        section = Section.QUICK_PLAY,
        shape = Shape.TRACK_LIST,
        iconRes = R.drawable.ic_home_random_mix,
        // Generates a mix on first render so the tile shows real covers;
        // afterwards mirrors the snapshot without reshuffling it (see
        // RandomMixSession.montageTracks).
        coverUrls = { _, randomMix -> randomMix.montageTracks().mapNotNull { it.artworkUrl } },
    ),
    RANDOM_ALBUMS(
        mediaId = MediaId.HomeRandomAlbums,
        artworkKey = "random-albums",
        titleRes = R.string.browse_home_random_albums,
        section = Section.QUICK_PLAY,
        shape = Shape.PLAYABLE,
        iconRes = R.drawable.ic_browse_albums,
        coverUrls = { repository, _ ->
            repository.randomAlbums(HomeButtonArtwork.COLLAGE_TILES).mapNotNull { it.artworkUrl }
        },
    ),
    RECENTLY_ADDED(
        mediaId = MediaId.HomeRecentlyAdded,
        artworkKey = "recently-added",
        titleRes = R.string.browse_home_recently_added_albums,
        section = Section.ALBUMS,
        shape = Shape.ALBUM_GRID,
        iconRes = R.drawable.ic_home_recently_added,
    ),
    RECENTLY_PLAYED(
        mediaId = MediaId.HomeRecentlyPlayed,
        artworkKey = "recently-played",
        titleRes = R.string.browse_home_recently_played,
        section = Section.ALBUMS,
        shape = Shape.ALBUM_GRID,
        iconRes = R.drawable.ic_home_recently_played,
    ),
    FAVORITE_ALBUMS(
        mediaId = MediaId.HomeFavoriteAlbums,
        artworkKey = "favorite-albums",
        titleRes = R.string.browse_home_favorite_albums,
        section = Section.ALBUMS,
        shape = Shape.ALBUM_GRID,
        iconRes = R.drawable.ic_home_favorite_albums,
    ),
    GENRES(
        mediaId = MediaId.Genres,
        artworkKey = "genres",
        titleRes = R.string.browse_library_genres,
        section = Section.ALBUMS,
        shape = Shape.GENRE_LIST,
        iconRes = R.drawable.ic_home_genre_mix,
    ),
    DECADES(
        mediaId = MediaId.Decades,
        artworkKey = "decades",
        titleRes = R.string.browse_library_decades,
        section = Section.ALBUMS,
        shape = Shape.DECADE_LIST,
        iconRes = R.drawable.ic_home_decade_mix,
    ),
    ALL_ALBUMS(
        mediaId = MediaId.TabAlbums,
        artworkKey = "all-albums",
        titleRes = R.string.browse_library_all_albums,
        section = Section.ALBUMS,
        shape = Shape.ALBUM_GRID,
        iconRes = R.drawable.ic_browse_albums,
    ),
    FAVORITE_ARTISTS(
        mediaId = MediaId.HomeFavoriteArtists,
        artworkKey = "favorite-artists",
        titleRes = R.string.browse_library_favorite_artists,
        section = Section.ARTISTS,
        shape = Shape.ARTIST_LIST,
        iconRes = R.drawable.ic_home_favorite_artists,
    ),
    ALL_ARTISTS(
        mediaId = MediaId.TabArtists,
        artworkKey = "all-artists",
        titleRes = R.string.browse_library_all_artists,
        section = Section.ARTISTS,
        shape = Shape.ARTIST_LIST,
        iconRes = R.drawable.ic_browse_artists,
    ),
    RADIO(
        mediaId = MediaId.TabRadio,
        artworkKey = "radio",
        titleRes = R.string.browse_library_radio_stations,
        section = Section.RADIO,
        shape = Shape.STATION_LIST,
        iconRes = R.drawable.ic_browse_radio,
    ),
    ;

    /** Which headed section a static tile belongs to (QUICK_PLAY on Home, the rest on Library). */
    enum class Section { QUICK_PLAY, ALBUMS, ARTISTS, RADIO }

    /** What the tile opens or plays — picks the browse-item builder in [BrowseTree]. */
    enum class Shape {
        TRACK_LIST,

        /** Plays on tap, nothing to browse. */
        PLAYABLE,
        ALBUM_GRID,
        ARTIST_LIST,
        STATION_LIST,
        GENRE_LIST,
        DECADE_LIST,
    }

    companion object {
        fun forKey(key: String): HomeTile? = entries.firstOrNull { it.artworkKey == key }

        fun forMediaId(id: MediaId): HomeTile? = entries.firstOrNull { it.mediaId == id }
    }
}

/**
 * The [HomeTile] counterpart for the dynamic mix tiles ("Made for you" and
 * the genre/decade Shuffle tiles): the badge icon per kind. [pathSegment] is
 * the middle segment of the tile's `home/<kind>/<key>` artwork path — the
 * key (seed artist id / genre name / decade start year) comes from the mix.
 */
internal enum class HomeMixKind(
    val pathSegment: String,
    val iconRes: Int,
) {
    BEST_OF(
        pathSegment = "best-of",
        iconRes = R.drawable.ic_home_best_of_mix,
    ),
    SIMILAR(
        pathSegment = "similar",
        iconRes = R.drawable.ic_home_similar_mix,
    ),
    GENRE(
        pathSegment = "genre",
        iconRes = R.drawable.ic_home_genre_mix,
    ),
    DECADE(
        pathSegment = "decade",
        iconRes = R.drawable.ic_home_decade_mix,
    ),
    ;

    companion object {
        fun forPath(segment: String): HomeMixKind? =
            entries.firstOrNull { it.pathSegment == segment }
    }
}
