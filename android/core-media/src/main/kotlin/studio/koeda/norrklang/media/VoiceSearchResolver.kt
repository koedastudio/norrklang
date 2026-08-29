package studio.koeda.norrklang.media

import studio.koeda.norrklang.data.model.Album
import studio.koeda.norrklang.data.model.Artist
import studio.koeda.norrklang.data.model.Track
import studio.koeda.norrklang.data.repo.MusicRepository

/**
 * Resolves an Assistant voice request ("Play <x>") into a playable queue.
 *
 * A voice request is one library search plus ranking: an exact title/name
 * match beats server result order, and the structured focus extras (artist/
 * album/title/playlist/genre) pick the section when the assistant provides
 * them. An empty query ("play some music") is a random-mix queue; no match
 * at all resolves to null.
 *
 * Kept free of Android imports (the Bundle parsing lives in
 * [LibrarySessionCallback]) so it stays JVM-unit-testable.
 */
internal class VoiceSearchResolver(
    private val repository: MusicRepository,
    private val containerTracks: suspend (MediaId.Container) -> List<Track>,
) {

    /** One voice request, pre-parsed from the legacy search extras. */
    data class Request(
        val query: String,
        val focus: Focus = Focus.Unstructured,
        val artist: String? = null,
        val album: String? = null,
        val title: String? = null,
        val playlist: String? = null,
        val genre: String? = null,
    )

    enum class Focus { Unstructured, Artist, Album, Title, Playlist, Genre }

    /**
     * The queue to play. A single-track result relies on queue radio for
     * continuation; [container] carries resumption context where one exists.
     */
    data class Queue(
        val tracks: List<Track>,
        val container: MediaId.Container? = null,
    )

    suspend fun resolve(request: Request): Queue? {
        when (request.focus) {
            Focus.Artist ->
                nameOrQuery(request.artist, request)?.let { artistQueue(it) }?.let { return it }
            Focus.Album ->
                nameOrQuery(request.album, request)
                    ?.let { albumQueue(it, request.artist) }?.let { return it }
            Focus.Playlist ->
                nameOrQuery(request.playlist, request)?.let { playlistQueue(it) }?.let { return it }
            Focus.Genre ->
                nameOrQuery(request.genre, request)?.let { genreQueue(it) }?.let { return it }
            // Title focus resolves through the generic search below, just with
            // the structured title as the term.
            Focus.Title, Focus.Unstructured -> Unit
        }
        val term = (if (request.focus == Focus.Title) request.title ?: request.query else request.query)
            .trim()
        // "Play some music" — nothing to match, so play the random mix.
        if (term.isEmpty()) return containerQueue(MediaId.HomeRandomMix)
        // Generic resolution (also the fallback when a focused lookup missed):
        // exact matches first, broad-to-specific only after that.
        val results = repository.search(term)
        val key = voiceKey(term)
        results.tracks.firstOrNull { voiceKey(it.title) == key }?.let { return Queue(listOf(it)) }
        results.artists.firstOrNull { voiceKey(it.name) == key }
            ?.let { bestOf(it) }?.let { return it }
        results.albums.firstOrNull { voiceKey(it.title) == key }
            ?.let { albumTracks(it) }?.let { return it }
        results.tracks.firstOrNull()?.let { return Queue(listOf(it)) }
        results.artists.firstOrNull()?.let { bestOf(it) }?.let { return it }
        results.albums.firstOrNull()?.let { albumTracks(it) }?.let { return it }
        return null
    }

    /** The structured extra when present, else the spoken query; null when both are blank. */
    private fun nameOrQuery(name: String?, request: Request): String? =
        (name ?: request.query).trim().ifEmpty { null }

    private suspend fun artistQueue(name: String): Queue? {
        val artists = repository.search(name).artists
        val key = voiceKey(name)
        val artist = artists.firstOrNull { voiceKey(it.name) == key } ?: artists.firstOrNull()
        return artist?.let { bestOf(it) }
    }

    /**
     * "Best of <artist>" when the server has top-track data; otherwise the
     * artist's albums in listing order, capped so an exhaustive discography
     * doesn't turn one voice command into dozens of album fetches.
     */
    private suspend fun bestOf(artist: Artist): Queue? {
        containerQueue(MediaId.HomeBestOf(artist.id))?.let { return it }
        val tracks = repository.artist(artist.id).albums
            .take(ARTIST_ALBUM_LIMIT)
            .flatMap { repository.album(it.id).tracks }
        return tracks.ifEmpty { null }?.let { Queue(it) }
    }

    private suspend fun albumQueue(name: String, artistHint: String?): Queue? {
        val albums = repository.search(name).albums
        val key = voiceKey(name)
        val artistKey = artistHint?.let(::voiceKey)
        val album = albums.firstOrNull {
            voiceKey(it.title) == key &&
                (artistKey == null || it.artistName?.let(::voiceKey) == artistKey)
        } ?: albums.firstOrNull { voiceKey(it.title) == key } ?: albums.firstOrNull()
        return album?.let { albumTracks(it) }
    }

    private suspend fun albumTracks(album: Album): Queue? {
        val container = MediaId.Album(album.id)
        return repository.album(album.id).tracks.ifEmpty { null }?.let { Queue(it, container) }
    }

    private suspend fun playlistQueue(name: String): Queue? {
        val key = voiceKey(name)
        val playlists = repository.playlists()
        val playlist = playlists.firstOrNull { voiceKey(it.name) == key }
            ?: playlists.firstOrNull { voiceKey(it.name).contains(key) }
            ?: return null
        val container = MediaId.Playlist(playlist.id)
        return repository.playlist(playlist.id).tracks.ifEmpty { null }?.let { Queue(it, container) }
    }

    private suspend fun genreQueue(name: String): Queue? {
        val key = voiceKey(name)
        val genre = repository.genres().firstOrNull { voiceKey(it.name) == key } ?: return null
        return containerQueue(MediaId.HomeGenre(genre.name))
    }

    private suspend fun containerQueue(container: MediaId.Container): Queue? =
        containerTracks(container).ifEmpty { null }?.let { Queue(it, container) }

    companion object {
        const val ARTIST_ALBUM_LIMIT = 5

        private val WHITESPACE = Regex("\\s+")

        /**
         * Speech-tolerant comparison key: case, surrounding/duplicate
         * whitespace and a leading "the" never break an exact match.
         */
        fun voiceKey(raw: String): String =
            raw.trim().lowercase().replace(WHITESPACE, " ").removePrefix("the ")
    }
}
