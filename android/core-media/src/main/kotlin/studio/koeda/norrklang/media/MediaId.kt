package studio.koeda.norrklang.media

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Typed media-id scheme — the contract between the browse tree, the session
 * callback and playback resumption. Kept free of Android imports so it stays
 * JVM-unit-testable.
 *
 * Encodings:
 * ```
 * root
 * tab/home | tab/library | tab/artists | tab/albums | tab/playlists | tab/radio
 * home/recently-added | home/favorite-albums | home/favorite-songs
 * home/recently-added-songs | home/random-mix | home/random-albums
 * home/recently-played | home/favorite-artists
 * library/genres | library/decades      (Library → genre / decade rows)
 * genre-albums/{urlEncodedName}         (a genre's album grid)
 * decade-albums/{startYear}             (a decade's album grid)
 * similar/{artistId}              ("Similar to <artist>" home mix)
 * bestof/{artistId}               ("Best of <artist>" home mix)
 * genre/{urlEncodedName}          (random genre mix — the grid's Shuffle tile, voice)
 * decade/{startYear}              (random decade mix — the grid's Shuffle tile)
 * artist-bucket/{key}            (A–Z folder of an oversized artists tab)
 * album-bucket/{key}             (A–Z folder of the oversized All albums grid)
 * genre-bucket/{urlEncodedName}/{key}   (A–Z folder of an oversized genre grid)
 * decade-bucket/{startYear}/{key}       (A–Z folder of an oversized decade grid)
 * artist/{id}
 * album/{id}
 * playlist/{id}
 * station/{id}                    (internet radio station — queued with all stations)
 * track/{id}
 * track/{id}|album/{albumId}      (track with queue context)
 * track/{id}|playlist/{plId}
 * track/{id}|home/favorite-songs
 * track/{id}|home/recently-added-songs
 * track/{id}|home/random-mix
 * track/{id}|home/random-albums
 * track/{id}|similar/{artistId}
 * track/{id}|bestof/{artistId}
 * track/{id}|genre/{urlEncodedName}
 * track/{id}|decade/{startYear}
 * radio/{artistId}                (queue-radio continuation, never browsable)
 * track/{id}|radio/{artistId}
 * ```
 * The context suffix lets "play one track from a list" rebuild the full
 * sibling queue in onSetMediaItems.
 */
sealed interface MediaId {

    /**
     * A browsable node or generated queue whose tracks can serve as a track's
     * queue context.
     */
    sealed interface Container : MediaId

    /**
     * A random mix drawn from the library catalog (a genre, a decade) rather
     * than personal listening; tracks built on demand by CatalogMixes.
     */
    sealed interface CatalogMix : Container

    /** Which albums an album grid (and its A–Z folders) lists. */
    sealed interface AlbumSource {
        data object All : AlbumSource
        data class Genre(val name: String) : AlbumSource
        data class Decade(val startYear: Int) : AlbumSource

        /** The random mix behind the grid's Shuffle tile; none for the whole library. */
        val mix: CatalogMix?
            get() = when (this) {
                All -> null
                is Genre -> GenreMix(name)
                is Decade -> DecadeMix(startYear)
            }
    }

    data object Root : MediaId
    data object TabHome : MediaId
    data object TabLibrary : MediaId
    data object TabArtists : MediaId
    data object TabAlbums : MediaId
    data object TabPlaylists : MediaId
    data object TabRadio : MediaId

    data object HomeRecentlyAdded : MediaId
    data object HomeFavoriteAlbums : MediaId
    data object HomeFavoriteArtists : MediaId
    data object HomeRecentlyPlayed : MediaId
    data object HomeFavoriteSongs : Container
    data object HomeRecentlyAddedSongs : Container
    data object HomeRandomMix : Container

    /** One random album in track order; autoplay appends the next (see QueueRadioListener). */
    data object HomeRandomAlbums : Container

    data object Genres : MediaId
    data object Decades : MediaId

    /** The album grid of one genre, headed by its Shuffle tile. */
    data class GenreAlbums(val name: String) : MediaId

    /** The album grid of one decade, e.g. `startYear = 1980` for the 80s. */
    data class DecadeAlbums(val startYear: Int) : MediaId

    /** A generated "Similar to <artist>" mix on the home tab. */
    data class HomeSimilar(val artistId: String) : Container

    /** A generated "Best of <artist>" mix on the home tab. */
    data class HomeBestOf(val artistId: String) : Container

    /** A random mix of one genre, keyed by the server's genre name. */
    data class GenreMix(val name: String) : CatalogMix

    /** A random mix of one decade, e.g. `startYear = 1980` for the 80s. */
    data class DecadeMix(val startYear: Int) : CatalogMix

    /** One A–Z folder of the All artists listing, for hosts that don't page ([Buckets]). */
    data class ArtistBucket(val key: String) : MediaId

    /** One A–Z folder of an oversized album grid, for hosts that don't page ([Buckets]). */
    data class AlbumBucket(val source: AlbumSource, val key: String) : MediaId

    data class Artist(val id: String) : MediaId
    data class Album(val id: String) : Container
    data class Playlist(val id: String) : Container

    /** An internet radio station: a live stream, queued with every station ([StationQueue]). */
    data class RadioStation(val id: String) : MediaId

    /**
     * The queue-radio tracks appended when a queue nears its end, seeded from
     * [seedArtistId]. A provenance/resume token only — never browsable.
     */
    data class SongRadio(val seedArtistId: String) : Container

    /** [container] is the [Album] or [Playlist] this track was browsed from, if any. */
    data class Track(val id: String, val container: Container? = null) : MediaId

    fun encode(): String = when (this) {
        Root -> "root"
        TabHome -> "tab/home"
        TabLibrary -> "tab/library"
        TabArtists -> "tab/artists"
        TabAlbums -> "tab/albums"
        TabPlaylists -> "tab/playlists"
        TabRadio -> "tab/radio"
        HomeRecentlyAdded -> "home/recently-added"
        HomeFavoriteAlbums -> "home/favorite-albums"
        HomeFavoriteArtists -> "home/favorite-artists"
        HomeRecentlyPlayed -> "home/recently-played"
        HomeFavoriteSongs -> "home/favorite-songs"
        HomeRecentlyAddedSongs -> "home/recently-added-songs"
        HomeRandomMix -> "home/random-mix"
        HomeRandomAlbums -> "home/random-albums"
        Genres -> "library/genres"
        Decades -> "library/decades"
        is GenreAlbums -> "genre-albums/${encodeName(name)}"
        is DecadeAlbums -> "decade-albums/$startYear"
        is HomeSimilar -> "similar/$artistId"
        is HomeBestOf -> "bestof/$artistId"
        is GenreMix -> "genre/${encodeName(name)}"
        is DecadeMix -> "decade/$startYear"
        is ArtistBucket -> "artist-bucket/$key"
        is AlbumBucket -> when (source) {
            AlbumSource.All -> "album-bucket/$key"
            is AlbumSource.Genre -> "genre-bucket/${encodeName(source.name)}/$key"
            is AlbumSource.Decade -> "decade-bucket/${source.startYear}/$key"
        }
        is Artist -> "artist/$id"
        is Album -> "album/$id"
        is Playlist -> "playlist/$id"
        is RadioStation -> "station/$id"
        is SongRadio -> "radio/$seedArtistId"
        is Track -> buildString {
            append("track/").append(id)
            container?.let { append(CONTEXT_SEPARATOR).append(it.encode()) }
        }
    }

    companion object {
        private const val CONTEXT_SEPARATOR = '|'

        /** Genre names are free text — URL-encoded so '|' and '/' can never collide with the separators. */
        private fun encodeName(name: String): String = URLEncoder.encode(name, "UTF-8")

        /** Null on a malformed escape — garbage, not a crash. */
        private fun decodeName(encoded: String): String? =
            runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrNull()

        fun parse(encoded: String): MediaId? {
            when (encoded) {
                "root" -> return Root
                "tab/home" -> return TabHome
                "tab/library" -> return TabLibrary
                "tab/artists" -> return TabArtists
                "tab/albums" -> return TabAlbums
                "tab/playlists" -> return TabPlaylists
                "tab/radio" -> return TabRadio
                "home/recently-added" -> return HomeRecentlyAdded
                "home/favorite-albums" -> return HomeFavoriteAlbums
                "home/favorite-artists" -> return HomeFavoriteArtists
                "home/recently-played" -> return HomeRecentlyPlayed
                "home/favorite-songs" -> return HomeFavoriteSongs
                "home/recently-added-songs" -> return HomeRecentlyAddedSongs
                "home/random-mix" -> return HomeRandomMix
                "home/random-albums" -> return HomeRandomAlbums
                "library/genres" -> return Genres
                "library/decades" -> return Decades
            }
            val (head, context) = encoded.split(CONTEXT_SEPARATOR, limit = 2)
                .let { it[0] to it.getOrNull(1) }
            val slash = head.indexOf('/')
            if (slash <= 0 || slash == head.length - 1) return null
            val type = head.substring(0, slash)
            val id = head.substring(slash + 1)
            // Only tracks carry a queue-context suffix; on anything else it's garbage.
            if (context != null && type != "track") return null
            return when (type) {
                "genre-albums" -> decodeName(id)?.let(::GenreAlbums)
                "decade-albums" -> id.toIntOrNull()?.let(::DecadeAlbums)
                "similar" -> HomeSimilar(id)
                "bestof" -> HomeBestOf(id)
                "genre" -> decodeName(id)?.let(::GenreMix)
                "decade" -> id.toIntOrNull()?.let(::DecadeMix)
                // Any key parses; a stale or garbage key selects an empty
                // folder (Buckets.select), not an error.
                "artist-bucket" -> ArtistBucket(id)
                "album-bucket" -> AlbumBucket(AlbumSource.All, id)
                "genre-bucket" -> scopedBucket(id) { decodeName(it)?.let(AlbumSource::Genre) }
                "decade-bucket" -> scopedBucket(id) { it.toIntOrNull()?.let(AlbumSource::Decade) }
                "artist" -> Artist(id)
                "album" -> Album(id)
                "playlist" -> Playlist(id)
                "station" -> RadioStation(id)
                "radio" -> SongRadio(id)
                "track" -> {
                    val container = context?.let { parse(it) as? Container ?: return null }
                    Track(id, container)
                }
                else -> null
            }
        }

        /** `{scope}/{key}` — the scope segment never contains '/' (URL-encoded name or a year). */
        private fun scopedBucket(id: String, source: (String) -> AlbumSource?): AlbumBucket? {
            val slash = id.indexOf('/')
            if (slash <= 0 || slash == id.length - 1) return null
            return source(id.substring(0, slash))?.let { AlbumBucket(it, id.substring(slash + 1)) }
        }
    }
}
