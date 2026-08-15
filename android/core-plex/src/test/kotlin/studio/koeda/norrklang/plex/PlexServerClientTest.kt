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
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PlexServerClientTest {

    private val clientInfo = PlexClientInfo(clientId = "test-client-id", version = "1.0")

    private var lastRequestUrl: String = ""
    private var lastRequestMethod: HttpMethod = HttpMethod.Get
    private var lastRequestHeaders: Map<String, String> = emptyMap()

    private fun clientReturning(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        PlexServerClient(
            baseUrl = "https://vault.example.com:32400",
            token = "srv-token",
            clientInfo = clientInfo,
            engine = MockEngine { request ->
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

    private fun container(inner: String) = """{"MediaContainer":{$inner}}"""

    @Test
    fun `musicSections keeps only artist-typed sections`() = runTest {
        val sections = clientReturning(
            container(
                """"Directory":[
                  {"key":"1","type":"movie","title":"Movies"},
                  {"key":"5","type":"artist","title":"Music"}
                ]""",
            ),
        ).musicSections()

        assertEquals(listOf("5"), sections.map { it.key })
        assertTrue("/library/sections" in lastRequestUrl)
        assertEquals("srv-token", lastRequestHeaders["X-Plex-Token"])
        assertEquals("test-client-id", lastRequestHeaders["X-Plex-Client-Identifier"])
        assertEquals("application/json", lastRequestHeaders["Accept"])
    }

    @Test
    fun `sectionItems sends type, sort, filters and paging params`() = runTest {
        val items = clientReturning(
            container(""""Metadata":[{"ratingKey":"101","title":"Abbey Road","type":"album"}]"""),
        ).sectionItems(
            sectionId = "5",
            type = PlexServerClient.ItemType.ALBUM,
            filters = listOf("year>=" to "1970", "genre" to "42"),
            sort = "addedAt:desc",
            start = 0,
            size = 50,
        )

        assertEquals("Abbey Road", items.single().title)
        assertTrue("/library/sections/5/all" in lastRequestUrl)
        assertTrue("type=9" in lastRequestUrl)
        assertTrue("sort=addedAt%3Adesc" in lastRequestUrl || "sort=addedAt:desc" in lastRequestUrl)
        assertTrue("year%3E%3D=1970" in lastRequestUrl)
        assertTrue("genre=42" in lastRequestUrl)
        assertTrue("X-Plex-Container-Start=0" in lastRequestUrl)
        assertTrue("X-Plex-Container-Size=50" in lastRequestUrl)
    }

    @Test
    fun `metadata parses media parts`() = runTest {
        val item = clientReturning(
            container(
                """"Metadata":[{
                  "ratingKey":"70","title":"Hit Single","type":"track",
                  "Media":[{"Part":[{"id":1,"key":"/library/parts/1/2/file.flac"}]}]
                }]""",
            ),
        ).metadata("70")

        assertEquals("Hit Single", item.title)
        assertEquals("/library/parts/1/2/file.flac", item.media.single().parts.single().key)
    }

    @Test
    fun `missing item maps to NotFound`() = runTest {
        val client = clientReturning(container(""""Metadata":[]"""))
        assertFailsWith<PlexException.NotFound> { client.metadata("nope") }
    }

    @Test
    fun `sectionItemCount asks for a zero-size page and returns totalSize`() = runTest {
        val count = clientReturning(
            container(""""size":0,"totalSize":1234"""),
        ).sectionItemCount("5", PlexServerClient.ItemType.ALBUM)

        assertEquals(1234, count)
        assertTrue("/library/sections/5/all" in lastRequestUrl)
        assertTrue("type=9" in lastRequestUrl)
        assertTrue("X-Plex-Container-Start=0" in lastRequestUrl)
        assertTrue("X-Plex-Container-Size=0" in lastRequestUrl)
    }

    @Test
    fun `sectionItemCount reads a missing totalSize as an empty section`() = runTest {
        val count = clientReturning(container(""""size":0"""))
            .sectionItemCount("5", PlexServerClient.ItemType.TRACK)

        assertEquals(0, count)
        assertTrue("type=10" in lastRequestUrl)
    }

    @Test
    fun `children returns the item's children in order`() = runTest {
        val tracks = clientReturning(
            container(
                """"Metadata":[
                  {"ratingKey":"71","title":"Track One","type":"track"},
                  {"ratingKey":"72","title":"Track Two","type":"track"}
                ]""",
            ),
        ).children("101")

        assertEquals(listOf("Track One", "Track Two"), tracks.map { it.title })
        assertTrue("/library/metadata/101/children" in lastRequestUrl)
    }

    @Test
    fun `playlists requests audio playlists only`() = runTest {
        val playlists = clientReturning(
            container(""""Metadata":[{"ratingKey":"9","title":"Road Trip","type":"playlist"}]"""),
        ).playlists()

        assertEquals("Road Trip", playlists.single().title)
        assertTrue("/playlists" in lastRequestUrl)
        assertTrue("playlistType=audio" in lastRequestUrl)
    }

    @Test
    fun `genres requests the album genre directory`() = runTest {
        val genres = clientReturning(
            container(""""Directory":[{"key":"42","title":"Jazz"}]"""),
        ).genres("5")

        assertEquals("Jazz", genres.single().title)
        assertTrue("/library/sections/5/genre" in lastRequestUrl)
        assertTrue("type=9" in lastRequestUrl)
    }

    @Test
    fun `rate uses PUT with the library identifier`() = runTest {
        clientReturning("").rate("101", 10)

        assertEquals(HttpMethod.Put, lastRequestMethod)
        assertTrue("/:/rate" in lastRequestUrl)
        assertTrue("key=101" in lastRequestUrl)
        assertTrue("rating=10" in lastRequestUrl)
        assertTrue("identifier=com.plexapp.plugins.library" in lastRequestUrl)
    }

    @Test
    fun `timeline reports state, position and duration`() = runTest {
        clientReturning("").timeline("70", state = "playing", timeMs = 61_000, durationMs = 180_000)

        assertEquals(HttpMethod.Get, lastRequestMethod)
        assertTrue("/:/timeline" in lastRequestUrl)
        assertTrue("ratingKey=70" in lastRequestUrl)
        assertTrue("key=%2Flibrary%2Fmetadata%2F70" in lastRequestUrl)
        assertTrue("state=playing" in lastRequestUrl)
        assertTrue("time=61000" in lastRequestUrl)
        assertTrue("duration=180000" in lastRequestUrl)
    }

    @Test
    fun `markPlayed hits the scrobble endpoint`() = runTest {
        clientReturning("").markPlayed("70")

        assertTrue("/:/scrobble" in lastRequestUrl)
        assertTrue("key=70" in lastRequestUrl)
        assertTrue("identifier=com.plexapp.plugins.library" in lastRequestUrl)
    }

    @Test
    fun `search returns hubs split by type`() = runTest {
        val hubs = clientReturning(
            container(
                """"Hub":[
                  {"type":"artist","Metadata":[{"ratingKey":"7","title":"The Artist"}]},
                  {"type":"album","Metadata":[]}
                ]""",
            ),
        ).search("5", "artist")

        assertEquals(listOf("artist", "album"), hubs.map { it.type })
        assertTrue("/hubs/search" in lastRequestUrl)
        assertTrue("query=artist" in lastRequestUrl)
        assertTrue("sectionId=5" in lastRequestUrl)
    }

    @Test
    fun `rejected token maps to AuthFailed`() = runTest {
        val client = clientReturning("", HttpStatusCode.Unauthorized)
        assertFailsWith<PlexException.AuthFailed> { client.musicSections() }
    }

    @Test
    fun `http 404 maps to NotFound`() = runTest {
        val client = clientReturning("", HttpStatusCode.NotFound)
        assertFailsWith<PlexException.NotFound> { client.validateSection("99") }
    }

    @Test
    fun `transport failure maps to NetworkError`() = runTest {
        val client = PlexServerClient(
            baseUrl = "https://vault.example.com:32400",
            token = "srv-token",
            clientInfo = clientInfo,
            engine = MockEngine { throw IOException("no route") },
        )
        assertFailsWith<PlexException.NetworkError> { client.musicSections() }
    }

    @Test
    fun `garbage body maps to ServerError`() = runTest {
        val client = clientReturning("<html>not a plex server</html>")
        assertFailsWith<PlexException.ServerError> { client.musicSections() }
    }
}
