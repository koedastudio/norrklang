package studio.koeda.norrklang.plex

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import studio.koeda.norrklang.plex.model.PlexConnection

/**
 * Probes a server's candidate connections in parallel so the connection
 * picker can show reachability and latency. Each probe hits the cheap
 * unauthenticated-friendly `/identity` endpoint with its own timeout; an
 * unreachable connection reports a null latency instead of failing the batch.
 */
class ConnectionProber(
    private val clientInfo: PlexClientInfo,
    engine: HttpClientEngine = PlexHttp.sharedEngine,
) : AutoCloseable {

    private val http = PlexHttp.client(engine)

    data class ProbeResult(
        val connection: PlexConnection,
        /** Round-trip time, or null when the connection did not answer. */
        val latencyMs: Long?,
    )

    suspend fun probe(
        connections: List<PlexConnection>,
        token: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): List<ProbeResult> = coroutineScope {
        connections.map { connection ->
            async { ProbeResult(connection, latencyOrNull(connection, token, timeoutMs)) }
        }.awaitAll()
    }

    private suspend fun latencyOrNull(
        connection: PlexConnection,
        token: String,
        timeoutMs: Long,
    ): Long? {
        val start = TimeSource.Monotonic.markNow()
        return try {
            // Per-request Ktor timeout, not withTimeout: the latter runs on
            // the caller's (possibly virtual test) clock and can misfire.
            val response = http.get("${connection.uri}/identity") {
                timeout {
                    connectTimeoutMillis = timeoutMs
                    requestTimeoutMillis = timeoutMs
                }
                plexHeaders(clientInfo, token)
            }
            if (response.status.isSuccess()) {
                start.elapsedNow().inWholeMilliseconds
            } else {
                null
            }
        } catch (e: CancellationException) {
            // An outer cancel (scope torn down) must still propagate.
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        http.close()
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 3_000L
    }
}
