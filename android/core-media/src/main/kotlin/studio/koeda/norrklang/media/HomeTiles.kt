package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * The single table behind the static browse tiles — browse id, artwork
 * key, title, icon/accent and cover source in one entry, so a tile cannot
 * drift apart between [BrowseTree] and [HomeButtonArtwork].
 *
 * QUICK_PLAY tiles render on the Home tab, ALBUMS and ARTISTS tiles on the
 * Library tab. Declaration order is display order within each section; on
 * the Home tab the generated mix sections follow QUICK_PLAY (see
 * [BrowseTree.homeButtons]).
 *
 * Entries with a [coverUrls] source render a cover collage; the Library
 * tiles deliberately declare none, so they always get the accent-gradient
 * icon rendering instead.
 */
internal enum class HomeTile(
    val mediaId: MediaId,
    /** The `home/<key>` path segment [ArtworkProvider] serves the image under. */
    val artworkKey: String,
    val titleRes: Int,
    val section: Section,
    /** Icon in the rendered tile's badge (or centered on icon-only tiles). */
    val iconRes: Int,
    /** Gradient color behind icon-only tiles (and collage tiles still empty). */
    val accentColor: Int,
    /** Artwork URLs behind the tile — the collage source; none = icon-only. */
    val coverUrls: suspend (MusicRepository, RandomMixSession) -> List<String> =
        { _, _ -> emptyList() },
) {
    RANDOM_MIX(
        mediaId = MediaId.HomeRandomMix,
        artworkKey = "random-mix",
        titleRes = R.string.browse_home_random_mix,
        section = Section.QUICK_PLAY,
        iconRes = R.drawable.ic_home_random_mix,
        accentColor = 0xFF27AE60.toInt(),
        // Generates a mix on first render so the tile shows real covers;
        // afterwards mirrors the snapshot without reshuffling it (see
        // RandomMixSession.montageTracks).
        coverUrls = { _, randomMix -> randomMix.montageTracks().mapNotNull { it.artworkUrl } },
    ),
    FAVORITE_SONGS(
        mediaId = MediaId.HomeFavoriteSongs,
        artworkKey = "favorite-songs",
        titleRes = R.string.browse_home_favorite_songs,
        section = Section.QUICK_PLAY,
        iconRes = R.drawable.ic_home_favorite_songs,
        accentColor = 0xFF9B51E0.toInt(),
        coverUrls = { repository, _ ->
            repository.favoriteTracks().mapNotNull { it.artworkUrl }
        },
    ),
    RECENTLY_PLAYED(
        mediaId = MediaId.HomeRecentlyPlayed,
        artworkKey = "recently-played",
        titleRes = R.string.browse_home_recently_played,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_recently_played,
        accentColor = 0xFF1AA6A6.toInt(),
    ),
    FAVORITE_ALBUMS(
        mediaId = MediaId.HomeFavoriteAlbums,
        artworkKey = "favorite-albums",
        titleRes = R.string.browse_home_favorite_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_favorite_albums,
        accentColor = 0xFFE0526E.toInt(),
    ),
    RECENTLY_ADDED(
        mediaId = MediaId.HomeRecentlyAdded,
        artworkKey = "recently-added",
        titleRes = R.string.browse_home_new_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_recently_added,
        accentColor = 0xFF2F80ED.toInt(),
    ),
    MOST_PLAYED(
        mediaId = MediaId.HomeMostPlayed,
        artworkKey = "most-played",
        titleRes = R.string.browse_home_most_played,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_home_most_played,
        accentColor = 0xFFF2994A.toInt(),
    ),
    ALL_ALBUMS(
        mediaId = MediaId.TabAlbums,
        artworkKey = "all-albums",
        titleRes = R.string.browse_library_all_albums,
        section = Section.ALBUMS,
        iconRes = R.drawable.ic_browse_albums,
        accentColor = 0xFF7E57C2.toInt(),
    ),
    ALL_ARTISTS(
        mediaId = MediaId.TabArtists,
        artworkKey = "all-artists",
        titleRes = R.string.browse_library_all_artists,
        section = Section.ARTISTS,
        iconRes = R.drawable.ic_browse_artists,
        accentColor = 0xFF546E7A.toInt(),
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
 * The [HomeTile] counterpart for the dynamic "Genre mixes"/"Decade mixes"
 * tiles: collage badge icon and empty-fallback accent per kind. [pathSegment]
 * is the middle segment of the tile's `home/<kind>/<key>` artwork path —
 * the key (genre name / decade start year) comes from the mix snapshot.
 */
internal enum class CatalogMixKind(
    val pathSegment: String,
    val iconRes: Int,
    val accentColor: Int,
) {
    GENRE(
        pathSegment = "genre",
        iconRes = R.drawable.ic_home_genre_mix,
        accentColor = 0xFF56CCF2.toInt(),
    ),
    DECADE(
        pathSegment = "decade",
        iconRes = R.drawable.ic_home_decade_mix,
        accentColor = 0xFFF2C94C.toInt(),
    ),
    ;

    companion object {
        fun forPath(segment: String): CatalogMixKind? =
            entries.firstOrNull { it.pathSegment == segment }
    }
}
