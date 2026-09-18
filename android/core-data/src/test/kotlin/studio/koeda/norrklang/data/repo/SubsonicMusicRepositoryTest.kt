package studio.koeda.norrklang.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials

/**
 * Pins the library scoping on the Subsonic side through a URL-routed
 * MockEngine: which requests carry `musicFolderId`, which never do, and how
 * the scrobble-exclusion probe resolves a track's library.
 */
class SubsonicMusicRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class PassthroughCipher : CredentialCipher {
        override fun encrypt(plaintext: String) = "enc-test:$plaintext"
        override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
        override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
    }

    private val requests = mutableListOf<String>()

    /** Routes: every needle must appear in the URL; first match wins. */
    private val routes = mutableListOf<Pair<List<String>, String>>()

    private fun route(vararg needles: String, body: String) {
        routes.add(needles.toList() to body)
    }

    private fun ok(payload: String) = """{"subsonic-response":{"status":"ok",$payload}}"""

    private fun engine() = MockEngine { request ->
        val url = request.url.toString()
        requests.add(url)
        val body = routes.firstOrNull { (needles, _) -> needles.all { it in url } }?.second
            ?: """{"subsonic-response":{"status":"ok"}}"""
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private class TestEnv(
        val sessionManager: SessionManager,
        val settings: ServerSettingsRepository,
        val repository: SubsonicMusicRepository,
    )

    private fun env(scope: CoroutineScope): TestEnv {
        val settings = ServerSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "test.preferences_pb")
            },
            PassthroughCipher(),
        )
        val sessionManager = SessionManager(
            settings,
            scope,
            { creds: SubsonicCredentials -> SubsonicClient(creds, engine()) },
        )
        return TestEnv(
            sessionManager,
            settings,
            SubsonicMusicRepository(sessionManager, settings, "studio.koeda.norrklang", scope),
        )
    }

    private suspend fun TestEnv.signedIn(): TestEnv {
        sessionManager.signIn("https://music.example.com", "demo", "secret").getOrThrow()
        requests.clear()
        return this
    }

    private fun foldersRoute() = route(
        "getMusicFolders.view",
        body = ok(
            """"musicFolders":{"musicFolder":[
                {"id":1,"name":"Music"},{"id":2,"name":"Kids"},{"id":3,"name":"Multichannel"}]}""",
        ),
    )

    private fun folderIds(url: String): List<String> =
        Url(url).parameters.getAll("musicFolderId").orEmpty()

    private fun lastRequest(endpoint: String): String = requests.last { "/rest/$endpoint" in it }

    private fun song(id: String, artistId: String = "ar-1") =
        """{"id":"$id","title":"Song $id","artistId":"$artistId","albumId":"al-1","album":"Hunky Dory"}"""

    @Test
    fun `nothing excluded - no getMusicFolders round-trip and no musicFolderId`() = runTest {
        foldersRoute()
        val env = env(backgroundScope).signedIn()

        env.repository.artists()
        env.repository.randomTracks(5)

        assertTrue(requests.none { "getMusicFolders" in it })
        assertTrue(requests.none { "musicFolderId" in it })
        assertTrue(requests.any { "/rest/getArtists.view" in it })
    }

    @Test
    fun `excluding a library sends the remaining ids on every scoped list`() = runTest {
        foldersRoute()
        val env = env(backgroundScope).signedIn()
        env.settings.setLibraryExcluded("2", true)

        env.repository.artists()
        env.repository.recentlyAdded(10)
        env.repository.favoriteTracks()
        env.repository.randomTracks(10)
        env.repository.search("x")

        for (endpoint in listOf("getArtists", "getAlbumList2", "getStarred2", "getRandomSongs", "search3")) {
            assertEquals(listOf("1", "3"), folderIds(lastRequest("$endpoint.view")), endpoint)
        }
        assertEquals(1, requests.count { "getMusicFolders" in it })
    }

    @Test
    fun `toggling an exclusion changes the cache key`() = runTest {
        foldersRoute()
        val env = env(backgroundScope).signedIn()

        env.repository.artists()
        env.repository.artists()
        assertEquals(1, requests.count { "/rest/getArtists.view" in it })

        env.settings.setLibraryExcluded("2", true)
        env.repository.artists()
        assertEquals(2, requests.count { "/rest/getArtists.view" in it })
        assertEquals(listOf("1", "3"), folderIds(lastRequest("getArtists.view")))

        // Back to "all": the earlier unscoped entry is still fresh, so no request.
        env.settings.setLibraryExcluded("2", false)
        env.repository.artists()
        assertEquals(2, requests.count { "/rest/getArtists.view" in it })
    }

    @Test
    fun `isFavoriteTrack stays unscoped`() = runTest {
        foldersRoute()
        route("getStarred2.view", body = ok(""""starred2":{"song":[${song("tr-1")}]}"""))
        val env = env(backgroundScope).signedIn()
        env.settings.setLibraryExcluded("2", true)

        assertTrue(env.repository.isFavoriteTrack("tr-1"))
        assertTrue(!env.repository.isFavoriteTrack("tr-2"))

        assertEquals(emptyList(), folderIds(lastRequest("getStarred2.view")))
    }

    @Test
    fun `tracks carry libraryId only when exactly one library is in scope`() = runTest {
        foldersRoute()
        route("getRandomSongs.view", body = ok(""""randomSongs":{"song":[${song("tr-1")}]}"""))
        val env = env(backgroundScope).signedIn()

        assertNull(env.repository.randomTracks(5).single().libraryId)

        env.settings.setLibraryExcluded("2", true)
        assertNull(env.repository.randomTracks(5).single().libraryId)

        env.settings.setLibraryExcluded("3", true)
        assertEquals("1", env.repository.randomTracks(5).single().libraryId)
        assertEquals(listOf("1"), folderIds(lastRequest("getRandomSongs.view")))
    }

    @Test
    fun `similarTracks drops songs by artists outside the scope`() = runTest {
        foldersRoute()
        route(
            "getSimilarSongs2.view",
            body = ok(""""similarSongs2":{"song":[${song("tr-1", "ar-1")},${song("tr-2", "ar-9")}]}"""),
        )
        route(
            "getArtists.view",
            "musicFolderId=1",
            body = ok(""""artists":{"index":[{"name":"A","artist":[{"id":"ar-1","name":"A"}]}]}"""),
        )
        val env = env(backgroundScope).signedIn()

        assertEquals(listOf("tr-1", "tr-2"), env.repository.similarTracks("ar-1", 10).map { it.id })

        env.settings.setLibraryExcluded("2", true)
        env.settings.setLibraryExcluded("3", true)
        assertEquals(listOf("tr-1"), env.repository.similarTracks("ar-1", 10).map { it.id })
    }

    @Test
    fun `trackLibraryId skips the album probe when the artist is not in the library`() = runTest {
        route("getSong.view", body = ok(""""song":${song("tr-1")}"""))
        route(
            "getArtists.view",
            "musicFolderId=2",
            body = ok(""""artists":{"index":[{"name":"Z","artist":[{"id":"ar-9","name":"Z"}]}]}"""),
        )
        val env = env(backgroundScope).signedIn()

        assertNull(env.repository.trackLibraryId("tr-1", setOf("2")))
        assertTrue(requests.none { "search3" in it })
    }

    @Test
    fun `trackLibraryId confirms the library by album search and caches`() = runTest {
        route("getSong.view", body = ok(""""song":${song("tr-1")}"""))
        route(
            "getArtists.view",
            body = ok(""""artists":{"index":[{"name":"A","artist":[{"id":"ar-1","name":"A"}]}]}"""),
        )
        route(
            "search3.view",
            "musicFolderId=1",
            body = ok(""""searchResult3":{"album":[{"id":"al-7","name":"Other"},{"id":"al-1","name":"Hunky Dory"}]}"""),
        )
        route(
            "search3.view",
            "musicFolderId=3",
            body = ok(""""searchResult3":{"album":[{"id":"al-7","name":"Hunky Dory"}]}"""),
        )
        val env = env(backgroundScope).signedIn()

        // Library 3 has the artist but not this album; library 1 has both.
        assertEquals("1", env.repository.trackLibraryId("tr-1", setOf("3", "1")))
        val probe = lastRequest("search3.view")
        assertEquals(listOf("1"), folderIds(probe))
        assertEquals(listOf("Hunky Dory"), Url(probe).parameters.getAll("query"))
        assertEquals(listOf("0"), Url(probe).parameters.getAll("artistCount"))
        assertEquals(listOf("100"), Url(probe).parameters.getAll("albumCount"))
        assertEquals(listOf("0"), Url(probe).parameters.getAll("songCount"))

        assertNull(env.repository.trackLibraryId("tr-1", setOf("3")))

        val before = requests.size
        assertEquals("1", env.repository.trackLibraryId("tr-1", setOf("3", "1")))
        assertEquals(before, requests.size)
    }

    @Test
    fun `libraries maps folder id and name`() = runTest {
        foldersRoute()
        val env = env(backgroundScope).signedIn()

        assertEquals(
            listOf("1" to "Music", "2" to "Kids", "3" to "Multichannel"),
            env.repository.libraries().map { it.id to it.name },
        )
    }
}
