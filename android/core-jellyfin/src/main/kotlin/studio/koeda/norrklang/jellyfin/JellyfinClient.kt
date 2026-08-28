package studio.koeda.norrklang.jellyfin

import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import studio.koeda.norrklang.jellyfin.model.JellyfinAuthRequest
import studio.koeda.norrklang.jellyfin.model.JellyfinAuthResult
import studio.koeda.norrklang.jellyfin.model.JellyfinItem
import studio.koeda.norrklang.jellyfin.model.JellyfinItemsResult
import studio.koeda.norrklang.jellyfin.model.JellyfinPlaybackBody
import studio.koeda.norrklang.jellyfin.model.JellyfinPublicSystemInfo

/**
 * Coroutine-based client for one Jellyfin server. Jellyfin has no external
 * account service (unlike plex.tv), so this one client covers sign-in and
 * everything after.
 *
 * [baseUrl] is the normalized server URL without a trailing slash. [token]
 * is null only for the pre-auth calls ([authenticate], [publicSystemInfo]);
 * every other call requires it. [engine] exists for tests (Ktor MockEngine);
 * production shares one OkHttp engine across instances. [close] releases the
 * client's own resources but never the shared engine.
 */
class JellyfinClient(
    private val baseUrl: String,
    private val token: String?,
    private val clientInfo: JellyfinClientInfo,
    engine: HttpClientEngine = JellyfinHttp.sharedEngine,
) : AutoCloseable {

    private val http = JellyfinHttp.client(engine)

    /** The server's public identity — display name for labels; no auth needed. */
    suspend fun publicSystemInfo(): JellyfinPublicSystemInfo {
        val response = JellyfinHttp.request(baseUrl) {
            http.get("$baseUrl/System/Info/Public") { jellyfinHeaders(clientInfo) }
        }
        checkStatus(response)
        return parse(response)
    }

    /** Username/password sign-in; the result carries the access token and user id. */
    suspend fun authenticate(username: String, password: String): JellyfinAuthResult {
        val response = JellyfinHttp.request(baseUrl) {
            http.post("$baseUrl/Users/AuthenticateByName") {
                jellyfinHeaders(clientInfo)
                contentType(ContentType.Application.Json)
                setBody(JellyfinAuthRequest(username = username, pw = password))
            }
        }
        checkStatus(response)
        val result = parse<JellyfinAuthResult>(response)
        if (result.accessToken.isNullOrBlank() || result.user.id.isBlank()) {
            throw JellyfinException.ServerError(
                code = null,
                message = "Sign-in succeeded but no access token was returned",
            )
        }
        return result
    }

    /** Music (`CollectionType == "music"`) library views visible to the user. */
    suspend fun musicLibraries(userId: String): List<JellyfinItem> =
        itemsResult("/Users/$userId/Views").items.filter { it.collectionType == "music" }

    /**
     * Validates the token and the music library in one round-trip — the
     * sign-in and session-restore check.
     */
    suspend fun validateLibrary(userId: String, libraryId: String) {
        item(userId, libraryId)
    }

    /** One item by id. */
    suspend fun item(userId: String, itemId: String): JellyfinItem {
        val response = JellyfinHttp.request(baseUrl) {
            http.get("$baseUrl/Items/$itemId") {
                jellyfinHeaders(clientInfo, token)
                parameter("userId", userId)
            }
        }
        checkStatus(response)
        val item = parse<JellyfinItem>(response)
        if (item.id == null) throw JellyfinException.NotFound("Item $itemId not found")
        return item
    }

    /**
     * The generic item query. [params] carries the free-form filters
     * (`GenreIds`, `ArtistIds`, `AlbumArtistIds`, `Years`, `SearchTerm`, ...).
     * Returns the whole envelope so callers can read [JellyfinItemsResult
     * .totalRecordCount] — a `limit = 0` page is how counts are asked for.
     */
    suspend fun items(
        userId: String,
        parentId: String? = null,
        includeItemTypes: String? = null,
        recursive: Boolean = true,
        sortBy: String? = null,
        sortOrder: String? = null,
        filters: List<String> = emptyList(),
        params: List<Pair<String, String>> = emptyList(),
        startIndex: Int = 0,
        limit: Int? = null,
        fields: String = DEFAULT_FIELDS,
    ): JellyfinItemsResult =
        itemsResult("/Items") {
            parameter("userId", userId)
            parentId?.let { parameter("ParentId", it) }
            includeItemTypes?.let { parameter("IncludeItemTypes", it) }
            parameter("Recursive", recursive.toString())
            sortBy?.let { parameter("SortBy", it) }
            sortOrder?.let { parameter("SortOrder", it) }
            if (filters.isNotEmpty()) parameter("Filters", filters.joinToString(","))
            for ((key, value) in params) parameter(key, value)
            parameter("StartIndex", startIndex.toString())
            limit?.let { parameter("Limit", it.toString()) }
            parameter("Fields", fields)
        }

    /**
     * Album artists in the library — the artists the browse tree can
     * navigate into (performing-only artists would show empty album pages).
     */
    suspend fun albumArtists(
        userId: String,
        parentId: String,
        sortBy: String = "SortName",
        isFavorite: Boolean? = null,
        searchTerm: String? = null,
        limit: Int? = null,
    ): List<JellyfinItem> =
        itemsResult("/Artists/AlbumArtists") {
            parameter("userId", userId)
            parameter("ParentId", parentId)
            parameter("SortBy", sortBy)
            isFavorite?.let { parameter("IsFavorite", it.toString()) }
            searchTerm?.let { parameter("SearchTerm", it) }
            limit?.let { parameter("Limit", it.toString()) }
            parameter("Fields", DEFAULT_FIELDS)
        }.items

    /**
     * Items similar to [itemId], computed by the server from shared genres
     * and tags. Empty when the library's metadata gives it nothing to go on.
     */
    suspend fun similar(userId: String, itemId: String, limit: Int): List<JellyfinItem> =
        itemsResult("/Items/$itemId/Similar") {
            parameter("userId", userId)
            parameter("limit", limit.toString())
        }.items

    /** The library's music genres; each item's id is what `GenreIds=` accepts. */
    suspend fun genres(userId: String, parentId: String): List<JellyfinItem> =
        itemsResult("/MusicGenres") {
            parameter("userId", userId)
            parameter("ParentId", parentId)
        }.items

    /** The playlist's tracks in playlist order. */
    suspend fun playlistItems(userId: String, playlistId: String): List<JellyfinItem> =
        itemsResult("/Playlists/$playlistId/Items") {
            parameter("userId", userId)
        }.items

    /** Flips the per-user favorite flag. Idempotent on the server. */
    suspend fun setFavorite(userId: String, itemId: String, favorite: Boolean) {
        val path = "/Users/$userId/FavoriteItems/$itemId"
        val response = JellyfinHttp.request(baseUrl) {
            if (favorite) http.post("$baseUrl$path") { jellyfinHeaders(clientInfo, token) }
            else http.delete("$baseUrl$path") { jellyfinHeaders(clientInfo, token) }
        }
        checkStatus(response)
    }

    /**
     * Deterministically marks the item played (bumps play count) — unlike
     * session reporting, this does not depend on the server's own thresholds.
     */
    suspend fun markPlayed(userId: String, itemId: String) {
        val response = JellyfinHttp.request(baseUrl) {
            http.post("$baseUrl/Users/$userId/PlayedItems/$itemId") {
                jellyfinHeaders(clientInfo, token)
            }
        }
        checkStatus(response)
    }

    /** Now-playing report opening a play session on the server. */
    suspend fun reportPlaybackStart(body: JellyfinPlaybackBody) =
        reportPlayback("/Sessions/Playing", body)

    /** Periodic position/pause report for the open play session. */
    suspend fun reportPlaybackProgress(body: JellyfinPlaybackBody) =
        reportPlayback("/Sessions/Playing/Progress", body)

    /** Closes the play session; the server applies its played thresholds. */
    suspend fun reportPlaybackStopped(body: JellyfinPlaybackBody) =
        reportPlayback("/Sessions/Playing/Stopped", body)

    private suspend fun reportPlayback(path: String, body: JellyfinPlaybackBody) {
        val response = JellyfinHttp.request(baseUrl) {
            http.post("$baseUrl$path") {
                jellyfinHeaders(clientInfo, token)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        checkStatus(response)
    }

    /** GET returning a parsed items envelope. */
    private suspend fun itemsResult(
        path: String,
        params: HttpRequestBuilder.() -> Unit = {},
    ): JellyfinItemsResult {
        val response = JellyfinHttp.request(baseUrl) {
            http.get("$baseUrl$path") {
                jellyfinHeaders(clientInfo, token)
                params()
            }
        }
        checkStatus(response)
        return parse(response)
    }

    private suspend inline fun <reified T> parse(response: HttpResponse): T =
        try {
            response.body<T>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw JellyfinException.ServerError(
                code = null,
                message = "Unexpected response from server — is this a Jellyfin server?",
                cause = e,
            )
        }

    private fun checkStatus(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw JellyfinException.fromStatusCode(response.status.value, baseUrl)
        }
    }

    override fun close() {
        http.close()
    }

    companion object {
        /** Extra fields beyond the list defaults that the mappers read. */
        const val DEFAULT_FIELDS = "SortName,ChildCount"

        /** Jellyfin runtimes are in ticks: 1 tick = 100 ns. */
        const val TICKS_PER_MS = 10_000L
    }
}
