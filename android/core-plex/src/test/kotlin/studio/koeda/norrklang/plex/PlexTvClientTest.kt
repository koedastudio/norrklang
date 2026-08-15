package studio.koeda.norrklang.plex

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlexTvClientTest {

    private val clientInfo = PlexClientInfo(clientId = "test-client-id", version = "1.0")

    private var lastRequestUrl: String = ""
    private var lastRequestMethod: HttpMethod = HttpMethod.Get
    private var lastRequestHeaders: Map<String, String> = emptyMap()

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        PlexTvClient(
            clientInfo,
            MockEngine { request ->
                lastRequestUrl = request.url.toString()
                lastRequestMethod = request.method
                lastRequestHeaders = request.headers.entries()
                    .associate { (key, values) -> key to values.joinToString(",") }
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )

    @Test
    fun `createPin posts with client identity and parses id and code`() = runTest {
        val pin = clientReturning(
            """{"id":12345,"code":"ABCD","expiresIn":1799,"authToken":null}""",
        ).createPin()

        assertEquals(12345L, pin.id)
        assertEquals("ABCD", pin.code)
        assertNull(pin.authToken)
        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("plex.tv/api/v2/pins" in lastRequestUrl)
        // Regular pin, not strong: plex.tv/link only takes the 4-char code.
        assertTrue("strong" !in lastRequestUrl)
        assertEquals("test-client-id", lastRequestHeaders["X-Plex-Client-Identifier"])
        assertEquals("Norrklang", lastRequestHeaders["X-Plex-Product"])
        assertEquals("application/json", lastRequestHeaders["Accept"])
    }

    @Test
    fun `checkPin returns the token once claimed`() = runTest {
        val pin = clientReturning(
            """{"id":12345,"code":"ABCD","authToken":"the-token"}""",
        ).checkPin(12345L)

        assertEquals("the-token", pin.authToken)
        assertTrue("/api/v2/pins/12345" in lastRequestUrl)
    }

    @Test
    fun `expired pin maps to NotFound`() = runTest {
        val client = clientReturning("""{"errors":[{"code":1020}]}""", HttpStatusCode.NotFound)
        assertFailsWith<PlexException.NotFound> { client.checkPin(999L) }
    }

    @Test
    fun `user sends the token and parses the account identity`() = runTest {
        val user = clientReturning(
            """{"username":"demo","email":"demo@example.com"}""",
        ).user("account-token")

        assertEquals("demo", user.username)
        assertTrue("/api/v2/user" in lastRequestUrl)
        assertEquals("account-token", lastRequestHeaders["X-Plex-Token"])
    }

    @Test
    fun `servers filters to resources that provide server and keeps connections`() = runTest {
        val servers = clientReturning(
            """[
              {"name":"Living Room TV","provides":"player","clientIdentifier":"tv1"},
              {"name":"Vault","provides":"server","clientIdentifier":"m1","owned":true,
               "accessToken":"srv-token","connections":[
                 {"protocol":"https","address":"192.168.1.10","port":32400,
                  "uri":"https://192-168-1-10.x.plex.direct:32400","local":true,"relay":false},
                 {"protocol":"https","address":"1.2.3.4","port":32400,
                  "uri":"https://1-2-3-4.x.plex.direct:32400","local":false,"relay":false},
                 {"protocol":"https","address":"5.6.7.8","port":443,
                  "uri":"https://5-6-7-8.x.plex.direct:443","local":false,"relay":true}
               ]}
            ]""",
        ).servers("account-token")

        assertEquals(1, servers.size)
        val server = servers.single()
        assertEquals("Vault", server.name)
        assertEquals("srv-token", server.accessToken)
        assertEquals(3, server.connections.size)
        assertTrue(server.connections.first().local)
        assertTrue(server.connections.last().relay)
        assertTrue("includeHttps=1" in lastRequestUrl)
        assertTrue("includeRelay=1" in lastRequestUrl)
        assertEquals("account-token", lastRequestHeaders["X-Plex-Token"])
    }

    @Test
    fun `rejected token maps to AuthFailed`() = runTest {
        val client = clientReturning("", HttpStatusCode.Unauthorized)
        assertFailsWith<PlexException.AuthFailed> { client.servers("bad-token") }
    }

    @Test
    fun `transport failure maps to NetworkError`() = runTest {
        val client = PlexTvClient(clientInfo, MockEngine { throw IOException("no route") })
        assertFailsWith<PlexException.NetworkError> { client.createPin() }
    }

    @Test
    fun `garbage body maps to ServerError`() = runTest {
        val client = clientReturning("<html>not json</html>")
        assertFailsWith<PlexException.ServerError> { client.createPin() }
    }
}
