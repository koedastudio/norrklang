package studio.koeda.norrklang.jellyfin

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.jellyfin.model.JellyfinPlaybackBody

class JellyfinClientTest {

    private val clientInfo = JellyfinClientInfo(deviceId = "test-device-id", version = "1.0")

    private var lastRequestUrl: String = ""
    private var lastRequestMethod: HttpMethod = HttpMethod.Get
    private var lastRequestHeaders: Map<String, String> = emptyMap()
    private var lastRequestBody: String = ""

    private fun clientReturning(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        token: String? = "srv-token",
    ) = JellyfinClient(
        baseUrl = "https://jf.example.com",
        token = token,
        clientInfo = clientInfo,
        engine = MockEngine { request ->
            record(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        },
    )

    private suspend fun record(request: HttpRequestData) {
        lastRequestUrl = request.url.toString()
        lastRequestMethod = request.method
        lastRequestHeaders = request.headers.entries()
            .associate { (key, values) -> key to values.joinToString(",") }
        lastRequestBody = String(request.body.toByteArray())
    }

    @Test
    fun `authenticate posts the credentials without a token in the header`() = runTest {
        val result = clientReturning(
            """{"User":{"Id":"u1","Name":"demo"},"AccessToken":"tok","ServerId":"s1"}""",
            token = null,
        ).authenticate("demo", "hunter2")

        assertEquals("tok", result.accessToken)
        assertEquals("u1", result.user.id)
        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/Users/AuthenticateByName" in lastRequestUrl)
        assertTrue(""""Username":"demo"""" in lastRequestBody)
        assertTrue(""""Pw":"hunter2"""" in lastRequestBody)
        val auth = lastRequestHeaders["Authorization"].orEmpty()
        assertTrue("""DeviceId="test-device-id"""" in auth)
        assertTrue("Token" !in auth)
        assertEquals("application/json", lastRequestHeaders["Accept"])
    }

    @Test
    fun `authenticate without a token in the response maps to ServerError`() = runTest {
        val client = clientReturning("""{"User":{"Id":"u1"}}""", token = null)
        assertFailsWith<JellyfinException.ServerError> { client.authenticate("demo", "") }
    }

    @Test
    fun `quickConnectEnabled reads the flag without a token`() = runTest {
        assertTrue(clientReturning("true", token = null).quickConnectEnabled())

        assertTrue("/QuickConnect/Enabled" in lastRequestUrl)
        assertTrue("Token" !in lastRequestHeaders["Authorization"].orEmpty())
    }

    @Test
    fun `quickConnectEnabled treats a missing endpoint as off`() = runTest {
        val client = clientReturning("", status = HttpStatusCode.NotFound, token = null)
        assertEquals(false, client.quickConnectEnabled())
    }

    @Test
    fun `initiateQuickConnect posts for a code without a token`() = runTest {
        val state = clientReturning(
            """{"Authenticated":false,"Secret":"s3cret","Code":"123456"}""",
            token = null,
        ).initiateQuickConnect()

        assertEquals("s3cret", state.secret)
        assertEquals("123456", state.code)
        assertEquals(false, state.authenticated)
        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/QuickConnect/Initiate" in lastRequestUrl)
        val auth = lastRequestHeaders["Authorization"].orEmpty()
        assertTrue("""DeviceId="test-device-id"""" in auth)
        assertTrue("Token" !in auth)
    }

    @Test
    fun `initiateQuickConnect without a code maps to ServerError`() = runTest {
        val client = clientReturning("""{"Authenticated":false}""", token = null)
        assertFailsWith<JellyfinException.ServerError> { client.initiateQuickConnect() }
    }

    @Test
    fun `quickConnectState polls by secret and maps an expired secret to NotFound`() = runTest {
        val state = clientReturning(
            """{"Authenticated":true,"Secret":"s3cret","Code":"123456"}""",
            token = null,
        ).quickConnectState("s3cret")

        assertTrue(state.authenticated)
        assertTrue("/QuickConnect/Connect?secret=s3cret" in lastRequestUrl)

        val expired = clientReturning("", status = HttpStatusCode.NotFound, token = null)
        assertFailsWith<JellyfinException.NotFound> { expired.quickConnectState("s3cret") }
    }

    @Test
    fun `authenticateWithQuickConnect posts the secret and parses the token`() = runTest {
        val result = clientReturning(
            """{"User":{"Id":"u1","Name":"demo"},"AccessToken":"tok"}""",
            token = null,
        ).authenticateWithQuickConnect("s3cret")

        assertEquals("tok", result.accessToken)
        assertEquals("u1", result.user.id)
        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/Users/AuthenticateWithQuickConnect" in lastRequestUrl)
        assertEquals("""{"Secret":"s3cret"}""", lastRequestBody)
        assertTrue("Token" !in lastRequestHeaders["Authorization"].orEmpty())
    }

    @Test
    fun `authenticateWithQuickConnect without a token in the response maps to ServerError`() = runTest {
        val client = clientReturning("""{"User":{"Id":"u1"}}""", token = null)
        assertFailsWith<JellyfinException.ServerError> { client.authenticateWithQuickConnect("s3cret") }
    }

    @Test
    fun `authenticated calls carry the token in the Authorization header`() = runTest {
        clientReturning("""{"Items":[]}""").items("u1")

        assertTrue("""Token="srv-token"""" in lastRequestHeaders["Authorization"].orEmpty())
    }

    @Test
    fun `items sends scope, sort, filter and paging params`() = runTest {
        val result = clientReturning(
            """{"Items":[{"Id":"101","Name":"Abbey Road","Type":"MusicAlbum"}]}""",
        ).items(
            userId = "u1",
            parentId = "lib1",
            includeItemTypes = JellyfinClient.ItemType.ALBUM,
            sortBy = "DateCreated",
            sortOrder = "Descending",
            filters = listOf("IsFavorite"),
            params = listOf("GenreIds" to "g42"),
            startIndex = 10,
            limit = 50,
        )

        assertEquals("Abbey Road", result.items.single().name)
        assertTrue("/Items?" in lastRequestUrl)
        assertTrue("userId=u1" in lastRequestUrl)
        assertTrue("ParentId=lib1" in lastRequestUrl)
        assertTrue("IncludeItemTypes=MusicAlbum" in lastRequestUrl)
        assertTrue("Recursive=true" in lastRequestUrl)
        assertTrue("SortBy=DateCreated" in lastRequestUrl)
        assertTrue("SortOrder=Descending" in lastRequestUrl)
        assertTrue("Filters=IsFavorite" in lastRequestUrl)
        assertTrue("GenreIds=g42" in lastRequestUrl)
        assertTrue("StartIndex=10" in lastRequestUrl)
        assertTrue("Limit=50" in lastRequestUrl)
    }

    @Test
    fun `items surfaces the total record count for zero-size pages`() = runTest {
        val result = clientReturning("""{"Items":[],"TotalRecordCount":1234}""")
            .items("u1", limit = 0)

        assertEquals(1234, result.totalRecordCount)
        assertTrue("Limit=0" in lastRequestUrl)
    }

    @Test
    fun `musicLibraries keeps only music views`() = runTest {
        val views = clientReturning(
            """{"Items":[
              {"Id":"v1","Name":"Movies","CollectionType":"movies"},
              {"Id":"v2","Name":"Music","CollectionType":"music"}
            ]}""",
        ).musicLibraries("u1")

        assertEquals(listOf("v2"), views.map { it.id })
        assertTrue("/Users/u1/Views" in lastRequestUrl)
    }

    @Test
    fun `validateMusicLibraries rejects a user without music views`() = runTest {
        val client = clientReturning(
            """{"Items":[{"Id":"v1","Name":"Movies","CollectionType":"movies"}]}""",
        )
        assertFailsWith<JellyfinException.NotFound> { client.validateMusicLibraries("u1") }
    }

    @Test
    fun `validateMusicLibraries returns the music views`() = runTest {
        val views = clientReturning(
            """{"Items":[
              {"Id":"v2","Name":"Music","CollectionType":"music"},
              {"Id":"v3","Name":"Audiobooks","CollectionType":"music"}
            ]}""",
        ).validateMusicLibraries("u1")
        assertEquals(listOf("v2", "v3"), views.map { it.id })
    }

    @Test
    fun `ancestors parses the bare array and finds the collection folder`() = runTest {
        val ancestors = clientReturning(
            """[{"Id":"7","Name":"Artist","Type":"MusicArtist"},
                {"Id":"lib2","Name":"Music","Type":"CollectionFolder","CollectionType":"music"},
                {"Id":"root","Name":"Media Folders","Type":"AggregateFolder"}]""",
        ).ancestors("u1", "70")

        assertTrue("/Items/70/Ancestors" in lastRequestUrl)
        assertTrue("userId=u1" in lastRequestUrl)
        assertEquals("lib2", ancestors.first { it.type == "CollectionFolder" }.id)
    }

    @Test
    fun `items requests DateCreated so recently-added merges can order`() = runTest {
        clientReturning("""{"Items":[]}""").items("u1", parentId = "lib1")
        assertTrue("DateCreated" in lastRequestUrl)
    }

    @Test
    fun `albumArtists sends favorite and search params`() = runTest {
        clientReturning("""{"Items":[]}""")
            .albumArtists("u1", "lib1", isFavorite = true, searchTerm = "abba", limit = 5)

        assertTrue("/Artists/AlbumArtists" in lastRequestUrl)
        assertTrue("ParentId=lib1" in lastRequestUrl)
        assertTrue("IsFavorite=true" in lastRequestUrl)
        assertTrue("SearchTerm=abba" in lastRequestUrl)
        assertTrue("Limit=5" in lastRequestUrl)
    }

    @Test
    fun `missing item maps to NotFound`() = runTest {
        val client = clientReturning("{}")
        assertFailsWith<JellyfinException.NotFound> { client.item("u1", "nope") }
    }

    @Test
    fun `setFavorite posts and unfavorite deletes`() = runTest {
        val client = clientReturning("{}")

        client.setFavorite("u1", "100", favorite = true)
        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/Users/u1/FavoriteItems/100" in lastRequestUrl)

        client.setFavorite("u1", "100", favorite = false)
        assertEquals(HttpMethod.Delete, lastRequestMethod)
        assertTrue("/Users/u1/FavoriteItems/100" in lastRequestUrl)
    }

    @Test
    fun `markPlayed posts to the played items endpoint`() = runTest {
        clientReturning("{}").markPlayed("u1", "100")

        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/Users/u1/PlayedItems/100" in lastRequestUrl)
    }

    @Test
    fun `playback reports serialize the session body`() = runTest {
        clientReturning("{}").reportPlaybackProgress(
            JellyfinPlaybackBody(itemId = "100", positionTicks = 610_000_000, isPaused = true),
        )

        assertEquals(HttpMethod.Post, lastRequestMethod)
        assertTrue("/Sessions/Playing/Progress" in lastRequestUrl)
        assertTrue(""""ItemId":"100"""" in lastRequestBody)
        assertTrue(""""PositionTicks":610000000""" in lastRequestBody)
        assertTrue(""""IsPaused":true""" in lastRequestBody)
        assertTrue(""""PlayMethod":"DirectPlay"""" in lastRequestBody)
    }

    @Test
    fun `rejected token maps to AuthFailed`() = runTest {
        val client = clientReturning("", HttpStatusCode.Unauthorized)
        assertFailsWith<JellyfinException.AuthFailed> { client.items("u1") }
    }

    @Test
    fun `non-json body maps to a recognizable ServerError`() = runTest {
        val client = clientReturning("<html>not jellyfin</html>")
        val e = assertFailsWith<JellyfinException.ServerError> { client.items("u1") }
        assertTrue("Jellyfin" in e.message.orEmpty())
    }
}
