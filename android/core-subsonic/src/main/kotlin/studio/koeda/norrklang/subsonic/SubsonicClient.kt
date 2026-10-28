package studio.koeda.norrklang.subsonic

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
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
import studio.koeda.norrklang.subsonic.model.InternetRadioStation
import studio.koeda.norrklang.subsonic.model.MusicFolder
import studio.koeda.norrklang.subsonic.model.Playlist
import studio.koeda.norrklang.subsonic.model.PlaylistWithSongs
import studio.koeda.norrklang.subsonic.model.SearchResult3
import studio.koeda.norrklang.subsonic.model.Starred2
import studio.koeda.norrklang.subsonic.model.SubsonicEnvelope
import studio.koeda.norrklang.subsonic.model.SubsonicResponse

/**
 * Coroutine-based client for the Subsonic API v1.16.1 as served by Navidrome.
 *
 * [engineFactory] exists for tests (Ktor MockEngine). By default it comes
 * from [SubsonicEngineProvider], which the host app points at its shared
 * (optionally mTLS) OkHttp client, so no thread pool is created per client.
 * [close] releases the client's own resources but never an externally-provided
 * engine.
 */
class SubsonicClient(
    private val credentials: SubsonicCredentials,
    engineFactory: HttpClientEngineFactory<*> = SubsonicEngineProvider.engineFactory(credentials),
) : AutoCloseable {

    /** For tests: wraps a pre-built engine (e.g. Ktor's [io.ktor.client.engine.mock.MockEngine]). */
    constructor(credentials: SubsonicCredentials, engine: HttpClientEngine) : this(
        credentials,
        object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = engine
        },
    )

    private val json = Json {
        ignoreUnknownKeys = true
        // Real-world libraries hit odd server output (explicit nulls for
        // untagged fields, numbers as strings). One such value must degrade
        // to a default, not fail the whole response — a failed parse takes
        // every list containing that item down with it.
        coerceInputValues = true
        isLenient = true
    }

    private val http = HttpClient(engineFactory) {
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

    /** The libraries the user can access; one entry on servers without multi-library. */
    suspend fun getMusicFolders(): List<MusicFolder> =
        call("getMusicFolders.view").musicFolders?.musicFolder.orEmpty()

    /**
     * The server's alphabetical artist index, bucket structure preserved.
     * [musicFolderIds] scopes to those libraries (repeated `musicFolderId`,
     * as Navidrome parses it); empty means every accessible library.
     */
    suspend fun getArtists(musicFolderIds: List<String> = emptyList()): List<IndexID3> =
        call("getArtists.view", folderParams(musicFolderIds)).artists?.index.orEmpty()

    suspend fun getArtist(id: String): ArtistWithAlbumsID3 =
        call("getArtist.view", "id" to id).artist
            ?: throw SubsonicException.NotFound("Artist $id not found")

    suspend fun getAlbumList2(
        type: AlbumListType,
        size: Int = 100,
        offset: Int = 0,
        musicFolderIds: List<String> = emptyList(),
    ): List<AlbumID3> =
        albumList2(
            listOf(
                "type" to type.apiValue,
                "size" to size.toString(),
                "offset" to offset.toString(),
            ),
            musicFolderIds,
        )

    /** Albums whose year falls in `[fromYear, toYear]` (inclusive). */
    suspend fun getAlbumList2ByYear(
        fromYear: Int,
        toYear: Int,
        size: Int = 100,
        musicFolderIds: List<String> = emptyList(),
    ): List<AlbumID3> =
        albumList2(
            listOf(
                "type" to "byYear",
                "fromYear" to fromYear.toString(),
                "toYear" to toYear.toString(),
                "size" to size.toString(),
            ),
            musicFolderIds,
        )

    /** Albums tagged with [genre] (exact name as reported by [getGenres]). */
    suspend fun getAlbumList2ByGenre(
        genre: String,
        size: Int = 100,
        musicFolderIds: List<String> = emptyList(),
    ): List<AlbumID3> =
        albumList2(
            listOf(
                "type" to "byGenre",
                "genre" to genre,
                "size" to size.toString(),
            ),
            musicFolderIds,
        )

    private suspend fun albumList2(
        params: List<Pair<String, String>>,
        musicFolderIds: List<String>,
    ): List<AlbumID3> =
        call("getAlbumList2.view", params + folderParams(musicFolderIds)).albumList2?.album.orEmpty()

    /** Repeated `musicFolderId` params; nothing when [ids] is empty. */
    private fun folderParams(ids: List<String>): List<Pair<String, String>> =
        ids.map { "musicFolderId" to it }

    suspend fun getAlbum(id: String): AlbumWithSongsID3 =
        call("getAlbum.view", "id" to id).album
            ?: throw SubsonicException.NotFound("Album $id not found")

    /** Everything the user has starred; empty lists when nothing is starred. */
    suspend fun getStarred2(musicFolderIds: List<String> = emptyList()): Starred2 =
        call("getStarred2.view", folderParams(musicFolderIds)).starred2 ?: Starred2()

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
        musicFolderIds: List<String> = emptyList(),
    ): List<Child> {
        val params = buildList {
            add("size" to size.toString())
            genre?.let { add("genre" to it) }
            fromYear?.let { add("fromYear" to it.toString()) }
            toYear?.let { add("toYear" to it.toString()) }
            addAll(folderParams(musicFolderIds))
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

    /**
     * The internet radio stations configured on the server, server order.
     * Stations without a stream URL are unplayable and dropped here.
     */
    suspend fun getInternetRadioStations(): List<InternetRadioStation> =
        call("getInternetRadioStations.view")
            .internetRadioStations?.internetRadioStation.orEmpty()
            .filter { it.streamUrl.isNotBlank() }

    suspend fun getPlaylists(): List<Playlist> =
        call("getPlaylists.view").playlists?.playlist.orEmpty()

    suspend fun getPlaylist(id: String): PlaylistWithSongs =
        call("getPlaylist.view", "id" to id).playlist
            ?: throw SubsonicException.NotFound("Playlist $id not found")

    suspend fun search3(
        query: String,
        count: Int = 20,
        artistCount: Int = count,
        albumCount: Int = count,
        songCount: Int = count,
        musicFolderIds: List<String> = emptyList(),
    ): SearchResult3 =
        call(
            "search3.view",
            listOf(
                "query" to query,
                "artistCount" to artistCount.toString(),
                "albumCount" to albumCount.toString(),
                "songCount" to songCount.toString(),
            ) + folderParams(musicFolderIds),
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
        val url = "${credentials.baseUrl}/rest/$endpoint"
        SubsonicLog.log("GET $url")
        val start = System.currentTimeMillis()
        val response = try {
            http.get(url) {
                for ((key, value) in credentials.authParams()) parameter(key, value)
                parameter("f", "json")
                for ((key, value) in params) parameter(key, value)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            SubsonicLog.log("GET $url failed: ${causeChain(e)}")
            throw SubsonicException.NetworkError(e)
        } catch (e: URLParserException) {
            // Ktor rejects a malformed (user-typed) baseUrl before any I/O;
            // keep the single-SubsonicException contract on that path too.
            SubsonicLog.log("GET $url rejected before I/O: ${causeChain(e)}")
            throw SubsonicException.NetworkError(e)
        } catch (e: IllegalArgumentException) {
            SubsonicLog.log("GET $url rejected before I/O: ${causeChain(e)}")
            throw SubsonicException.NetworkError(e)
        }

        if (!response.status.isSuccess()) {
            SubsonicLog.log("GET $url -> HTTP ${response.status.value} in ${System.currentTimeMillis() - start}ms")
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
            SubsonicLog.log("GET $url -> unparseable body: ${causeChain(e)}")
            throw SubsonicException.ServerError(
                code = null,
                message = "Unexpected response from server — is this a Navidrome/Subsonic URL?",
                cause = e,
            )
        }

        if (body.status != "ok") {
            val error = body.error
            SubsonicLog.log("GET $url -> Subsonic error ${error?.code}: ${error?.message}")
            throw if (error != null) {
                SubsonicException.fromErrorCode(error.code, error.message)
            } else {
                SubsonicException.ServerError(null, "Server reported status '${body.status}'")
            }
        }
        SubsonicLog.log("GET $url -> ok in ${System.currentTimeMillis() - start}ms")
        return body
    }

    /** `Class: message` per cause level, deepest last — the TLS failure reason is usually at the bottom. */
    private fun causeChain(e: Throwable): String =
        generateSequence<Throwable>(e) { it.cause?.takeIf { c -> c !== it } }
            .take(4)
            .joinToString(" <- ") { it.javaClass.simpleName + it.message?.let { m -> ": $m" }.orEmpty() }

    override fun close() {
        http.close()
    }
}
