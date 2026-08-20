package studio.koeda.norrklang.subsonic

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.URLParserException
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json
import studio.koeda.norrklang.subsonic.model.AlbumID3
import studio.koeda.norrklang.subsonic.model.AlbumWithSongsID3
import studio.koeda.norrklang.subsonic.model.ArtistInfo2
import studio.koeda.norrklang.subsonic.model.ArtistWithAlbumsID3
import studio.koeda.norrklang.subsonic.model.Child
import studio.koeda.norrklang.subsonic.model.Genre
import studio.koeda.norrklang.subsonic.model.IndexID3
import studio.koeda.norrklang.subsonic.model.Playlist
import studio.koeda.norrklang.subsonic.model.PlaylistWithSongs
import studio.koeda.norrklang.subsonic.model.SearchResult3
import studio.koeda.norrklang.subsonic.model.Starred2
import studio.koeda.norrklang.subsonic.model.SubsonicEnvelope
import studio.koeda.norrklang.subsonic.model.SubsonicResponse

/**
 * Coroutine-based client for the Subsonic API v1.16.1 as served by Navidrome.
 *
 * [engine] exists for tests (Ktor MockEngine); production shares one OkHttp
 * engine across instances to avoid a thread pool per client. [close] releases
 * the client's own resources but never the shared engine.
 */
class SubsonicClient(
    private val credentials: SubsonicCredentials,
    engine: HttpClientEngine = sharedEngine,
) : AutoCloseable {

    private val json = Json {
        ignoreUnknownKeys = true
        // Real-world libraries hit odd server output (explicit nulls for
        // untagged fields, numbers as strings). One such value must degrade
        // to a default, not fail the whole response — a failed parse takes
        // every list containing that item down with it.
        coerceInputValues = true
        isLenient = true
    }

    private val http = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        // OkHttp has no default call timeout, so a server trickling bytes
        // would hang a request forever — a real failure mode on in-car
        // cellular. JSON API only; streaming goes through ExoPlayer.
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 30_000
        }
    }

    /** Album list flavors of `getAlbumList2`. */
    enum class AlbumListType(val apiValue: String) {
        NEWEST("newest"),
        RECENT("recent"),
        FREQUENT("frequent"),
        RANDOM("random"),
        STARRED("starred"),
        ALPHABETICAL("alphabeticalByName"),
    }

    /** Validates connectivity and credentials. Throws [SubsonicException] on failure. */
    suspend fun ping() {
        call("ping.view")
    }

    /** The server's alphabetical artist index, bucket structure preserved. */
    suspend fun getArtists(): List<IndexID3> =
        call("getArtists.view").artists?.index.orEmpty()

    suspend fun getArtist(id: String): ArtistWithAlbumsID3 =
        call("getArtist.view", "id" to id).artist
            ?: throw SubsonicException.NotFound("Artist $id not found")

    suspend fun getAlbumList2(
        type: AlbumListType,
        size: Int = 100,
        offset: Int = 0,
    ): List<AlbumID3> =
        albumList2(
            "type" to type.apiValue,
            "size" to size.toString(),
            "offset" to offset.toString(),
        )

    /** Albums whose year falls in `[fromYear, toYear]` (inclusive). */
    suspend fun getAlbumList2ByYear(fromYear: Int, toYear: Int, size: Int = 100): List<AlbumID3> =
        albumList2(
            "type" to "byYear",
            "fromYear" to fromYear.toString(),
            "toYear" to toYear.toString(),
            "size" to size.toString(),
        )

    /** Albums tagged with [genre] (exact name as reported by [getGenres]). */
    suspend fun getAlbumList2ByGenre(genre: String, size: Int = 100): List<AlbumID3> =
        albumList2(
            "type" to "byGenre",
            "genre" to genre,
            "size" to size.toString(),
        )

    private suspend fun albumList2(vararg params: Pair<String, String>): List<AlbumID3> =
        call("getAlbumList2.view", params.asList()).albumList2?.album.orEmpty()

    suspend fun getAlbum(id: String): AlbumWithSongsID3 =
        call("getAlbum.view", "id" to id).album
            ?: throw SubsonicException.NotFound("Album $id not found")

    /** Everything the user has starred; empty lists when nothing is starred. */
    suspend fun getStarred2(): Starred2 =
        call("getStarred2.view").starred2 ?: Starred2()

    suspend fun getSong(id: String): Child =
        call("getSong.view", "id" to id).song
            ?: throw SubsonicException.NotFound("Song $id not found")

    /**
     * [size] random songs, optionally filtered by [genre] (exact name as
     * reported by [getGenres]) and/or a `[fromYear, toYear]` range; a fresh
     * selection every call.
     */
    suspend fun getRandomSongs(
        size: Int = 50,
        genre: String? = null,
        fromYear: Int? = null,
        toYear: Int? = null,
    ): List<Child> {
        val params = buildList {
            add("size" to size.toString())
            genre?.let { add("genre" to it) }
            fromYear?.let { add("fromYear" to it.toString()) }
            toYear?.let { add("toYear" to it.toString()) }
        }
        return call("getRandomSongs.view", params).randomSongs?.song.orEmpty()
    }

    /** All genres in the library with their song/album counts. */
    suspend fun getGenres(): List<Genre> =
        call("getGenres.view").genres?.genre.orEmpty()

    /**
     * Artist metadata including similar artists (Last.fm-backed). Empty
     * [ArtistInfo2] when the server has no Last.fm data for the artist.
     */
    suspend fun getArtistInfo2(id: String, count: Int = 20): ArtistInfo2 =
        call("getArtistInfo2.view", "id" to id, "count" to count.toString())
            .artistInfo2 ?: ArtistInfo2()

    /**
     * Up to [count] random library songs by the artist and artists similar to
     * it (Last.fm-backed); empty when the server has no Last.fm data.
     */
    suspend fun getSimilarSongs2(id: String, count: Int = 50): List<Child> =
        call("getSimilarSongs2.view", "id" to id, "count" to count.toString())
            .similarSongs2?.song.orEmpty()

    /**
     * The artist's most popular library songs (Last.fm-backed). Takes the
     * artist NAME, not an id — that's the Subsonic API, not a mistake here.
     */
    suspend fun getTopSongs(artistName: String, count: Int = 20): List<Child> =
        call("getTopSongs.view", "artist" to artistName, "count" to count.toString())
            .topSongs?.song.orEmpty()

    suspend fun getPlaylists(): List<Playlist> =
        call("getPlaylists.view").playlists?.playlist.orEmpty()

    suspend fun getPlaylist(id: String): PlaylistWithSongs =
        call("getPlaylist.view", "id" to id).playlist
            ?: throw SubsonicException.NotFound("Playlist $id not found")

    suspend fun search3(query: String, count: Int = 20): SearchResult3 =
        call(
            "search3.view",
            "query" to query,
            "artistCount" to count.toString(),
            "albumCount" to count.toString(),
            "songCount" to count.toString(),
        ).searchResult3 ?: SearchResult3()

    /** Stars ("favorites") a song. Idempotent on the server. */
    suspend fun star(id: String) {
        call("star.view", "id" to id)
    }

    /** Removes the star from a song. Idempotent on the server. */
    suspend fun unstar(id: String) {
        call("unstar.view", "id" to id)
    }

    /** Stars ("favorites") an album, by ID3 album id. Idempotent on the server. */
    suspend fun starAlbum(albumId: String) {
        call("star.view", "albumId" to albumId)
    }

    /** Removes the star from an album. Idempotent on the server. */
    suspend fun unstarAlbum(albumId: String) {
        call("unstar.view", "albumId" to albumId)
    }

    /**
     * Reports playback to the server. Navidrome does NOT scrobble on `stream`,
     * so this must be called explicitly: `submission=false` for "now playing",
     * `submission=true` once the track counts as played.
     */
    suspend fun scrobble(trackId: String, submission: Boolean) {
        call("scrobble.view", "id" to trackId, "submission" to submission.toString())
    }

    private suspend fun call(
        endpoint: String,
        vararg params: Pair<String, String>,
    ): SubsonicResponse = call(endpoint, params.asList())

    private suspend fun call(
        endpoint: String,
        params: List<Pair<String, String>>,
    ): SubsonicResponse {
        val response = try {
            http.get("${credentials.baseUrl}/rest/$endpoint") {
                for ((key, value) in credentials.authParams()) parameter(key, value)
                parameter("f", "json")
                for ((key, value) in params) parameter(key, value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw SubsonicException.NetworkError(e)
        } catch (e: URLParserException) {
            // Ktor rejects a malformed (user-typed) baseUrl before any I/O;
            // keep the single-SubsonicException contract on that path too.
            throw SubsonicException.NetworkError(e)
        } catch (e: IllegalArgumentException) {
            throw SubsonicException.NetworkError(e)
        }

        if (!response.status.isSuccess()) {
            throw SubsonicException.ServerError(
                code = null,
                message = "HTTP ${response.status.value} from ${credentials.baseUrl}",
            )
        }

        val body = try {
            response.body<SubsonicEnvelope>().response
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SubsonicException.ServerError(
                code = null,
                message = "Unexpected response from server — is this a Navidrome/Subsonic URL?",
                cause = e,
            )
        }

        if (body.status != "ok") {
            val error = body.error
            throw if (error != null) {
                SubsonicException.fromErrorCode(error.code, error.message)
            } else {
                SubsonicException.ServerError(null, "Server reported status '${body.status}'")
            }
        }
        return body
    }

    override fun close() {
        http.close()
    }

    private companion object {
        // Ktor never closes an externally-provided engine, so this lives for
        // the process lifetime no matter how many clients come and go.
        val sharedEngine: HttpClientEngine by lazy { OkHttp.create() }
    }
}
