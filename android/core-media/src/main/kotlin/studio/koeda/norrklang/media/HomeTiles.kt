package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.repo.MusicRepository

// Fetch more than a collage's worth so deduping same-album tracks still
// fills all four tiles.
private const val COLLAGE_CANDIDATES = 12

/**
 * The single table behind the six static home-tab tiles — browse id, artwork
 * key, title, collage icon/accent and cover source in one entry, so a tile
 * cannot drift apart between [BrowseTree] and [HomeButtonArtwork].
 *
 * Declaration order is display order within each section; the generated mix
 * sections sit between QUICK_PLAY and BROWSE (see [BrowseTree.homeButtons]).
 */
internal enum class HomeTile(
    val mediaId: MediaId,
    /** The `home/<key>` path segment [ArtworkProvider] serves the image under. */
    val artworkKey: String,
    val titleRes: Int,
    val section: Section,
    /** Icon in the rendered tile's badge (or centered while empty). */
    val iconRes: Int,
    /** Fallback gradient color while the tile's section has no covers yet. */
    val accentColor: Int,
    /** Artwork URLs of the section's leading items — the collage source. */
    val coverUrls: suspend (MusicRepository, RandomMixSession) -> List<String>,
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
        section = Section.BROWSE,
        iconRes = R.drawable.ic_home_recently_played,
        accentColor = 0xFF1AA6A6.toInt(),
        coverUrls = { repository, _ ->
            repository.recentlyPlayedAlbums(COLLAGE_CANDIDATES).mapNotNull { it.artworkUrl }
        },
    ),
    MOST_PLAYED(
        mediaId = MediaId.HomeMostPlayed,
        artworkKey = "most-played",
        titleRes = R.string.browse_home_most_played,
        section = Section.BROWSE,
        iconRes = R.drawable.ic_home_most_played,
        accentColor = 0xFFF2994A.toInt(),
        coverUrls = { repository, _ ->
            repository.mostPlayedAlbums(COLLAGE_CANDIDATES).mapNotNull { it.artworkUrl }
        },
    ),
    RECENTLY_ADDED(
        mediaId = MediaId.HomeRecentlyAdded,
        artworkKey = "recently-added",
        titleRes = R.string.browse_home_new_albums,
        section = Section.BROWSE,
        iconRes = R.drawable.ic_home_recently_added,
        accentColor = 0xFF2F80ED.toInt(),
        coverUrls = { repository, _ ->
            repository.recentlyAdded(COLLAGE_CANDIDATES).mapNotNull { it.artworkUrl }
        },
    ),
    FAVORITE_ALBUMS(
        mediaId = MediaId.HomeFavoriteAlbums,
        artworkKey = "favorite-albums",
        titleRes = R.string.browse_home_favorite_albums,
        section = Section.BROWSE,
        iconRes = R.drawable.ic_home_favorite_albums,
        accentColor = 0xFFE0526E.toInt(),
        coverUrls = { repository, _ ->
            repository.favoriteAlbums(COLLAGE_CANDIDATES).mapNotNull { it.artworkUrl }
        },
    ),
    ;

    /** Which headed home-grid section a static tile belongs to. */
    enum class Section { QUICK_PLAY, BROWSE }

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
