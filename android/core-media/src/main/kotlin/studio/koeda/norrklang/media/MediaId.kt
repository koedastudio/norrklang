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
 * tab/home | tab/library | tab/artists | tab/albums | tab/playlists
 * home/recently-added | home/favorite-albums | home/favorite-songs
 * home/random-mix | home/recently-played | home/most-played
 * similar/{artistId}              ("Similar to <artist>" home mix)
 * bestof/{artistId}               ("Best of <artist>" home mix)
 * genre/{urlEncodedName}          (genre home mix)
 * decade/{startYear}              (decade home mix)
 * artist-bucket/{key}            (A–Z folder of an oversized artists tab)
 * album-bucket/{key}             (A–Z folder of an oversized albums tab)
 * artist/{id}
 * album/{id}
 * playlist/{id}
 * track/{id}
 * track/{id}|album/{albumId}      (track with queue context)
 * track/{id}|playlist/{plId}
 * track/{id}|home/favorite-songs
 * track/{id}|home/random-mix
 * track/{id}|similar/{artistId}
 * track/{id}|bestof/{artistId}
 * track/{id}|genre/{urlEncodedName}
 * track/{id}|decade/{startYear}
 * ```
 * The context suffix lets "play one track from a list" rebuild the full
 * sibling queue in onSetMediaItems.
 */
sealed interface MediaId {

    /** A browsable node whose tracks can serve as a track's queue context. */
    sealed interface Container : MediaId

    /**
     * A generated home mix derived from the library catalog (genres, decades)
     * rather than personal listening; owned by CatalogMixesSession.
     */
    sealed interface CatalogMix : Container

    data object Root : MediaId
    data object TabHome : MediaId
    data object TabLibrary : MediaId
    data object TabArtists : MediaId
    data object TabAlbums : MediaId
    data object TabPlaylists : MediaId

    data object HomeRecentlyAdded : MediaId
    data object HomeFavoriteAlbums : MediaId
    data object HomeRecentlyPlayed : MediaId
    data object HomeMostPlayed : MediaId
    data object HomeFavoriteSongs : Container
    data object HomeRandomMix : Container

    /** A generated "Similar to <artist>" mix on the home tab. */
    data class HomeSimilar(val artistId: String) : Container

    /** A generated "Best of <artist>" mix on the home tab. */
    data class HomeBestOf(val artistId: String) : Container

    /** A generated genre mix on the home tab, keyed by the server's genre name. */
    data class HomeGenre(val name: String) : CatalogMix

    /** A generated decade mix on the home tab, e.g. `startYear = 1980` for the 80s. */
    data class HomeDecade(val startYear: Int) : CatalogMix

    /** One A–Z folder of the All artists listing, for hosts that don't page ([Buckets]). */
    data class ArtistBucket(val key: String) : MediaId

    /** One A–Z folder of the All albums listing, for hosts that don't page ([Buckets]). */
    data class AlbumBucket(val key: String) : MediaId

    data class Artist(val id: String) : MediaId
    data class Album(val id: String) : Container
    data class Playlist(val id: String) : Container

    /** [container] is the [Album] or [Playlist] this track was browsed from, if any. */
    data class Track(val id: String, val container: Container? = null) : MediaId

    fun encode(): String = when (this) {
        Root -> "root"
        TabHome -> "tab/home"
        TabLibrary -> "tab/library"
        TabArtists -> "tab/artists"
        TabAlbums -> "tab/albums"
        TabPlaylists -> "tab/playlists"
        HomeRecentlyAdded -> "home/recently-added"
        HomeFavoriteAlbums -> "home/favorite-albums"
        HomeRecentlyPlayed -> "home/recently-played"
        HomeMostPlayed -> "home/most-played"
        HomeFavoriteSongs -> "home/favorite-songs"
        HomeRandomMix -> "home/random-mix"
        is HomeSimilar -> "similar/$artistId"
        is HomeBestOf -> "bestof/$artistId"
        // Genre names are free text — URL-encode so a '|' can never collide
        // with the context separator (server ids elsewhere are opaque-safe).
        is HomeGenre -> "genre/${URLEncoder.encode(name, "UTF-8")}"
        is HomeDecade -> "decade/$startYear"
        is ArtistBucket -> "artist-bucket/$key"
        is AlbumBucket -> "album-bucket/$key"
        is Artist -> "artist/$id"
        is Album -> "album/$id"
        is Playlist -> "playlist/$id"
        is Track -> buildString {
            append("track/").append(id)
            container?.let { append(CONTEXT_SEPARATOR).append(it.encode()) }
        }
    }

    companion object {
        private const val CONTEXT_SEPARATOR = '|'

        fun parse(encoded: String): MediaId? {
            when (encoded) {
                "root" -> return Root
                "tab/home" -> return TabHome
                "tab/library" -> return TabLibrary
                "tab/artists" -> return TabArtists
                "tab/albums" -> return TabAlbums
                "tab/playlists" -> return TabPlaylists
                "home/recently-added" -> return HomeRecentlyAdded
                "home/favorite-albums" -> return HomeFavoriteAlbums
                "home/recently-played" -> return HomeRecentlyPlayed
                "home/most-played" -> return HomeMostPlayed
                "home/favorite-songs" -> return HomeFavoriteSongs
                "home/random-mix" -> return HomeRandomMix
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
                "similar" -> HomeSimilar(id)
                "bestof" -> HomeBestOf(id)
                // decode throws on malformed escapes — that's garbage, not a crash.
                "genre" ->
                    runCatching { URLDecoder.decode(id, "UTF-8") }.getOrNull()?.let(::HomeGenre)
                "decade" -> id.toIntOrNull()?.let(::HomeDecade)
                // Any key parses; a stale or garbage key selects an empty
                // folder (Buckets.select), not an error.
                "artist-bucket" -> ArtistBucket(id)
                "album-bucket" -> AlbumBucket(id)
                "artist" -> Artist(id)
                "album" -> Album(id)
                "playlist" -> Playlist(id)
                "track" -> {
                    val container = context?.let { parse(it) as? Container ?: return null }
                    Track(id, container)
                }
                else -> null
            }
        }
    }
}
