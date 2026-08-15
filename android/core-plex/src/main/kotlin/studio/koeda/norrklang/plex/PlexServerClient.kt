package studio.koeda.norrklang.plex

import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import studio.koeda.norrklang.plex.model.PlexDirectory
import studio.koeda.norrklang.plex.model.PlexEnvelope
import studio.koeda.norrklang.plex.model.PlexHub
import studio.koeda.norrklang.plex.model.PlexMediaContainer
import studio.koeda.norrklang.plex.model.PlexMetadata

/**
 * Coroutine-based client for one Plex Media Server.
 *
 * [baseUrl] is the chosen connection URI without a trailing slash. All
 * requests carry the account's server token plus the X-Plex-* client
 * identity. [engine] exists for tests (Ktor MockEngine); production shares
 * one OkHttp engine across instances. [close] releases the client's own
 * resources but never the shared engine.
 */
class PlexServerClient(
    private val baseUrl: String,
    private val token: String,
    private val clientInfo: PlexClientInfo,
    engine: HttpClientEngine = PlexHttp.sharedEngine,
) : AutoCloseable {

    private val http = PlexHttp.client(engine)

    /** Library item types as used by `/library/sections/{id}/all?type=`. */
    enum class ItemType(val apiValue: Int) {
        ARTIST(8),
        ALBUM(9),
        TRACK(10),
    }

    /** Music (`type == "artist"`) library sections on this server. */
    suspend fun musicSections(): List<PlexDirectory> =
        container("/library/sections").directory.filter { it.type == "artist" }

    /**
     * Validates the token and the music section in one round-trip — the
     * sign-in check.
     */
    suspend fun validateSection(sectionId: String) {
        container("/library/sections/$sectionId")
    }

    /**
     * Items of [type] in the section. [filters] are Plex filter params —
     * plain (`"genre" to id`) or operator-suffixed (`"year>=" to "1970"`,
     * scoped `"album.year<=" to "1979"`). [sort] is a Plex sort spec like
     * `"addedAt:desc"` or `"random"`.
     */
    suspend fun sectionItems(
        sectionId: String,
        type: ItemType,
        filters: List<Pair<String, String>> = emptyList(),
        sort: String? = null,
        start: Int = 0,
        size: Int? = null,
    ): List<PlexMetadata> =
        container("/library/sections/$sectionId/all") {
            parameter("type", type.apiValue.toString())
            sort?.let { parameter("sort", it) }
            for ((key, value) in filters) parameter(key, value)
            parameter("X-Plex-Container-Start", start.toString())
            size?.let { parameter("X-Plex-Container-Size", it.toString()) }
        }.metadata

    /** One item by rating key. */
    suspend fun metadata(ratingKey: String): PlexMetadata =
        container("/library/metadata/$ratingKey").metadata.firstOrNull()
            ?: throw PlexException.NotFound("Item $ratingKey not found")

    /** An item's children: an artist's albums or an album's tracks, in order. */
    suspend fun children(ratingKey: String): List<PlexMetadata> =
        container("/library/metadata/$ratingKey/children").metadata

    /**
     * The item's related-content hubs. For an artist this includes the
     * "Similar Artists" hub — in-library artists linked by Plex's own music
     * metadata (no Plex Pass or sonic analysis required).
     */
    suspend fun related(ratingKey: String): List<PlexHub> =
        container("/library/metadata/$ratingKey/related").hubs

    /**
     * How many items of [type] match [filters], via a zero-size page — the
     * server answers with just the container's totalSize.
     */
    suspend fun sectionItemCount(
        sectionId: String,
        type: ItemType,
        filters: List<Pair<String, String>> = emptyList(),
    ): Int =
        container("/library/sections/$sectionId/all") {
            parameter("type", type.apiValue.toString())
            for ((key, value) in filters) parameter(key, value)
            parameter("X-Plex-Container-Start", "0")
            parameter("X-Plex-Container-Size", "0")
            // The zero-size page carries no items, so totalSize is the only
            // meaningful answer; its absence reads as an empty section.
        }.totalSize ?: 0

    /**
     * The section's genre directory for [type]; each entry's key is the id
     * accepted by the `genre=` filter.
     */
    suspend fun genres(sectionId: String, type: ItemType = ItemType.ALBUM): List<PlexDirectory> =
        container("/library/sections/$sectionId/genre") {
            parameter("type", type.apiValue.toString())
        }.directory

    /** All audio playlists (including smart playlists). */
    suspend fun playlists(): List<PlexMetadata> =
        container("/playlists") {
            parameter("playlistType", "audio")
        }.metadata

    suspend fun playlistItems(ratingKey: String): List<PlexMetadata> =
        container("/playlists/$ratingKey/items").metadata

    /** Hub search scoped to the section; hubs are split by result type. */
    suspend fun search(sectionId: String, query: String, limit: Int = 50): List<PlexHub> =
        container("/hubs/search") {
            parameter("query", query)
            parameter("sectionId", sectionId)
            parameter("limit", limit.toString())
        }.hubs

    /**
     * Sets the item's user rating: 10 = "loved" (the favorites mapping),
     * -1 clears the rating. Idempotent on the server.
     */
    suspend fun rate(ratingKey: String, rating: Int) {
        command(HttpMethod.PUT, "/:/rate") {
            parameter("key", ratingKey)
            parameter("identifier", PLEX_LIBRARY_IDENTIFIER)
            parameter("rating", rating.toString())
        }
    }

    /**
     * Play-state report driving the server's now-playing, on-deck, and
     * (via its own thresholds) play counts. [timeMs]/[durationMs] in ms.
     */
    suspend fun timeline(ratingKey: String, state: String, timeMs: Long, durationMs: Long?) {
        command(HttpMethod.GET, "/:/timeline") {
            parameter("ratingKey", ratingKey)
            parameter("key", "/library/metadata/$ratingKey")
            parameter("identifier", PLEX_LIBRARY_IDENTIFIER)
            parameter("state", state)
            parameter("time", timeMs.toString())
            durationMs?.let { parameter("duration", it.toString()) }
        }
    }

    /**
     * Deterministically marks the item played (bumps view count) — unlike
     * timeline, this does not depend on the server's own play thresholds.
     */
    suspend fun markPlayed(ratingKey: String) {
        command(HttpMethod.GET, "/:/scrobble") {
            parameter("key", ratingKey)
            parameter("identifier", PLEX_LIBRARY_IDENTIFIER)
        }
    }

    private enum class HttpMethod { GET, PUT }

    /** GET returning a parsed MediaContainer envelope. */
    private suspend fun container(
        path: String,
        params: HttpRequestBuilder.() -> Unit = {},
    ): PlexMediaContainer {
        val response = PlexHttp.request(baseUrl) {
            http.get("$baseUrl$path") {
                plexHeaders(clientInfo, token)
                params()
            }
        }
        checkStatus(response)
        return try {
            response.body<PlexEnvelope>().mediaContainer
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw PlexException.ServerError(
                code = null,
                message = "Unexpected response from server — is this a Plex server?",
                cause = e,
            )
        }
    }

    /** Fire-and-check request whose response body we never parse. */
    private suspend fun command(
        method: HttpMethod,
        path: String,
        params: HttpRequestBuilder.() -> Unit,
    ) {
        val response = PlexHttp.request(baseUrl) {
            when (method) {
                HttpMethod.GET -> http.get("$baseUrl$path") {
                    plexHeaders(clientInfo, token)
                    params()
                }
                HttpMethod.PUT -> http.put("$baseUrl$path") {
                    plexHeaders(clientInfo, token)
                    params()
                }
            }
        }
        checkStatus(response)
    }

    private fun checkStatus(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            throw PlexException.fromStatusCode(response.status.value, baseUrl)
        }
    }

    override fun close() {
        http.close()
    }

    companion object {
        /** The identifier Plex expects on rate/timeline/scrobble commands. */
        const val PLEX_LIBRARY_IDENTIFIER = "com.plexapp.plugins.library"
    }
}
