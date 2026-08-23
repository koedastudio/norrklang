package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The single table behind the static browse tiles — browse id, artwork
 * key, title, icon and cover source in one entry, so a tile cannot
 * drift apart between [BrowseTree] and [HomeButtonArtwork].
 *
 * QUICK_PLAY tiles render on the Home tab, ALBUMS and ARTISTS tiles on the
 * Library tab. Declaration order is display order within each section; on
 * the Home tab the generated mix sections follow QUICK_PLAY (see
 * [BrowseTree.homeButtons]).
 *
 * Entries with a [coverUrls] source render a cover collage; the Library
 * tiles deliberately declare none, so they always get the dark icon
 * rendering instead.
 */
internal enum class HomeTile(
    val mediaId: MediaId,
    /** The `home/<key>` path segment [ArtworkProvider] serves the image under. */
    val artworkKey: String,
    val titleRes: Int,
    val section: Section,
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
        iconRes = R.drawable.ic_home_favorite_songs,
        coverUrls = { repository, _ ->
            repository.favoriteTracks().mapNotNull { it.artworkUrl }
        },
    ),
    RECENTLY_ADDED_SONGS(
        mediaId = MediaId.HomeRecentlyAddedSongs,
        artworkKey = "recently-added-songs",
        titleRes = R.string.browse_home_recently_added_songs,
        section = Section.QUICK_PLAY,
        iconRes = R.drawable.ic_home_recently_added,
        // The newest albums' covers — the same covers the mix's leading
        // tracks carry, without the track flattening behind the browse list.
        coverUrls = { repository, _ ->
            repository.recentlyAdded().mapNotNull { it.artworkUrl }
        },
    ),
    RANDOM_MIX(
        mediaId = MediaId.HomeRandomMix,
        artworkKey = "random-mix",
        titleRes = R.string.browse_home_random_mix,
        section = Section.QUICK_PLAY,
        iconRes = R.drawable.ic_home_random_mix,
        // Generates a mix on first render so the tile shows real covers;
        // afterwards mirrors the snapshot without reshuffling it (see
        // RandomMixSession.montageTracks).
        coverUrls = { _, randomMix -> randomMix.montageTracks().mapNotNull { it.artworkUrl } },
    ),
    RECENTLY_ADDED(
        mediaId = MediaId.HomeRecentlyAdded,
        artworkKey = "recently-added",
        titleRes = R.string.browse_home_recently_added_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_recently_added,
    ),
    RECENTLY_PLAYED(
        mediaId = MediaId.HomeRecentlyPlayed,
        artworkKey = "recently-played",
        titleRes = R.string.browse_home_recently_played,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_recently_played,
    ),
    MOST_PLAYED(
        mediaId = MediaId.HomeMostPlayed,
        artworkKey = "most-played",
        titleRes = R.string.browse_home_most_played,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_most_played,
    ),
    FAVORITE_ALBUMS(
        mediaId = MediaId.HomeFavoriteAlbums,
        artworkKey = "favorite-albums",
        titleRes = R.string.browse_home_favorite_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_favorite_albums,
    ),
    ALL_ALBUMS(
        mediaId = MediaId.TabAlbums,
        artworkKey = "all-albums",
        titleRes = R.string.browse_library_all_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_browse_albums,
    ),
    FAVORITE_ARTISTS(
        mediaId = MediaId.HomeFavoriteArtists,
        artworkKey = "favorite-artists",
        titleRes = R.string.browse_library_favorite_artists,
        section = Section.ARTISTS,
        iconRes = R.drawable.ic_home_favorite_artists,
    ),
    ALL_ARTISTS(
        mediaId = MediaId.TabArtists,
        artworkKey = "all-artists",
        titleRes = R.string.browse_library_all_artists,
        section = Section.ARTISTS,
        iconRes = R.drawable.ic_browse_artists,
    ),
    ;

    /**
     * Which headed section a static tile belongs to (QUICK_PLAY on the Home
     * tab, ALBUMS and ARTISTS on Library).
     */
    enum class Section { QUICK_PLAY, ALBUMS, ARTISTS }

    companion object {
        fun forKey(key: String): HomeTile? = entries.firstOrNull { it.artworkKey == key }

        fun forMediaId(id: MediaId): HomeTile? = entries.firstOrNull { it.mediaId == id }
    }
}

/**
 * The [HomeTile] counterpart for the dynamic mix tiles ("Made for you",
 * "Genre mixes", "Decade mixes"): the badge icon per kind. [pathSegment] is
 * the middle segment of the tile's `home/<kind>/<key>` artwork path — the
 * key (seed artist id / genre name / decade start year) comes from the mix
 * snapshot.
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
