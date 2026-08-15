package studio.koeda.norrklang.plex

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.http.URLParserException
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.Json

internal object PlexHttp {
    // Ktor never closes an externally-provided engine, so this lives for the
    // process lifetime no matter how many clients come and go.
    val sharedEngine: HttpClientEngine by lazy { OkHttp.create() }

    val json = Json { ignoreUnknownKeys = true }

    fun client(engine: HttpClientEngine): HttpClient = HttpClient(engine) {
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

    /** Maps transport failures to [PlexException.NetworkError], keeping cancellation. */
    suspend inline fun request(source: String, block: () -> HttpResponse): HttpResponse =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw PlexException.NetworkError(e, source)
        } catch (e: URLParserException) {
            throw PlexException.NetworkError(e, source)
        } catch (e: IllegalArgumentException) {
            throw PlexException.NetworkError(e, source)
        }
}

/**
 * The header set every Plex request carries: JSON Accept plus the X-Plex-*
 * client identity, and the auth token when the call is authenticated.
 */
internal fun HttpRequestBuilder.plexHeaders(clientInfo: PlexClientInfo, token: String? = null) {
    header("Accept", "application/json")
    token?.let { header("X-Plex-Token", it) }
    for ((key, value) in clientInfo.headers()) header(key, value)
}
