package studio.koeda.norrklang.plex

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.plex.model.PlexConnection

class ConnectionProberTest {

    private val clientInfo = PlexClientInfo(clientId = "test-client-id", version = "1.0")

    private fun connection(uri: String, local: Boolean = false) =
        PlexConnection(address = "x", port = 32400, uri = uri, local = local)

    @Test
    fun `reachable connections report a latency, unreachable a null`() = runTest {
        val prober = ConnectionProber(
            clientInfo,
            MockEngine { request ->
                if ("good" in request.url.host) {
                    respond("{}", HttpStatusCode.OK)
                } else {
                    throw IOException("no route")
                }
            },
        )

        val results = prober.probe(
            listOf(connection("https://good.example.com:32400"), connection("https://bad.example.com:32400")),
            token = "srv-token",
        )

        assertEquals(2, results.size)
        assertNotNull(results[0].latencyMs)
        assertNull(results[1].latencyMs)
    }

    @Test
    fun `probes carry the token and client identity headers`() = runTest {
        var headers: Map<String, String> = emptyMap()
        var url = ""
        val prober = ConnectionProber(
            clientInfo,
            MockEngine { request ->
                url = request.url.toString()
                headers = request.headers.entries()
                    .associate { (key, values) -> key to values.joinToString(",") }
                respond("{}", HttpStatusCode.OK)
            },
        )

        prober.probe(listOf(connection("https://x.example.com:32400")), token = "srv-token")

        assertEquals("https://x.example.com:32400/identity", url)
        assertEquals("srv-token", headers["X-Plex-Token"])
        assertEquals("test-client-id", headers["X-Plex-Client-Identifier"])
    }

    @Test
    fun `non-success answers count as unreachable`() = runTest {
        val prober = ConnectionProber(
            clientInfo,
            MockEngine { respond("denied", HttpStatusCode.Unauthorized) },
        )
        val results = prober.probe(listOf(connection("https://x.example.com:32400")), "t")
        assertNull(results.single().latencyMs)
    }
}
