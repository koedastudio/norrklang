package studio.koeda.norrklang.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials

private class PassthroughJellyfinCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

/**
 * Exercises the Subsonic→Jellyfin mapping through a URL-routed MockEngine —
 * one handler per Jellyfin endpoint shape, requests recorded (as
 * "METHOD url") for asserting the query construction.
 */
class JellyfinMusicRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = JellyfinAccount(
        baseUrl = "https://jf.example.com",
        serverName = "Vault",
        userId = "u1",
        username = "demo",
        token = "jf-token",
        libraryId = "lib1",
    )

    private val requests = mutableListOf<String>()

    /** Maps a URL substring to the JSON answering it. */
    private val routes = mutableMapOf<String, String>()

    private var failWith: HttpStatusCode? = null

    private fun engine() = MockEngine { request ->
        val url = request.url.toString()
        requests.add("${request.method.value} $url")
        failWith?.let { return@MockEngine respond("", it) }
        val body = routes.entries.firstOrNull { (pattern, _) -> pattern in url }?.value
            // Parses as an id-less item AND an empty items envelope.
            ?: "{}"
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private class TestEnv(
        val sessionManager: SessionManager,
        val repository: JellyfinMusicRepository,
    )

    private fun env(scope: CoroutineScope): TestEnv {
        val settings = ServerSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(tmp.root, "test.preferences_pb")
            },
            PassthroughJellyfinCipher(),
        )
        val sessionManager = SessionManager(
            settings,
            scope,
            { creds: SubsonicCredentials -> SubsonicClient(creds, engine()) },
            jellyfinClientFactory = { acc, info ->
                JellyfinClient(acc.baseUrl, acc.token, info, engine())
            },
        )
        return TestEnv(
            sessionManager,
            JellyfinMusicRepository(sessionManager, "studio.koeda.norrklang", scope),
        )
    }

    private suspend fun TestEnv.signedIn(): JellyfinMusicRepository {
        routes["/Items/lib1"] = """{"Id":"lib1","Name":"Music"}"""
        sessionManager.signInJellyfin(account).getOrThrow()
        return repository
    }

    private fun track(id: String, name: String, albumId: String = "70") =
        """{"Id":"$id","Type":"Audio","Name":"$name",
            "ArtistItems":[{"Name":"Artist","Id":"7"}],
            "AlbumArtists":[{"Name":"Artist","Id":"7"}],
            "Album":"The Album","AlbumId":"$albumId",
            "IndexNumber":3,"ParentIndexNumber":1,"RunTimeTicks":2150000000,
            "AlbumPrimaryImageTag":"tag1"}"""

    private fun items(vararg entries: String) =
        """{"Items":[${entries.joinToString(",")}]}"""

    @Test
    fun `tracks map ids, metadata and authenticated stream urls`() = runTest {
        routes["SortBy=Random"] = items(track("100", "Song"))
        val repo = env(backgroundScope).signedIn()

        val t = repo.randomTracks(10).single()

        assertEquals("100", t.id)
        assertEquals("Artist", t.artistName)
        assertEquals("7", t.artistId)
        assertEquals("The Album", t.albumTitle)
        assertEquals("70", t.albumId)
        assertEquals(3, t.trackNumber)
        assertEquals(1, t.discNumber)
        assertEquals(215, t.durationSec)
        // Canonical ref, resolved to a real URL at load time (StreamUrlResolver).
        assertEquals("norrklang-stream://jellyfin?id=100", t.streamUrl)
        // Artwork is indirected through the in-app provider; tracks borrow
        // the album's primary image.
        assertEquals("content://studio.koeda.norrklang.artwork/cover/70", t.artworkUrl)
    }

    @Test
    fun `favorites round-trip through the favorite items endpoint`() = runTest {
        routes["Filters=IsFavorite"] = items(track("100", "Loved"))
        val repo = env(backgroundScope).signedIn()

        assertTrue(repo.isFavoriteTrack("100"))
        assertTrue(!repo.isFavoriteTrack("999"))

        repo.setTrackFavorite("100", false)
        assertTrue("DELETE https://jf.example.com/Users/u1/FavoriteItems/100" in requests.last())

        repo.setTrackFavorite("100", true)
        assertTrue("POST https://jf.example.com/Users/u1/FavoriteItems/100" in requests.last())
    }

    @Test
    fun `topTracks blends favorites and own plays in order`() = runTest {
        routes["SearchTerm=Artist"] = items("""{"Id":"7","Name":"Artist","Type":"MusicArtist"}""")
        routes["Filters=IsFavorite"] = items(track("100", "Loved"))
        routes["SortBy=PlayCount"] = items(track("100", "Loved"), track("102", "Worn"))
        val repo = env(backgroundScope).signedIn()

        val tracks = repo.topTracks("Artist", 5)

        // Favorites first, then plays — the duplicate dropped.
        assertEquals(listOf("Loved", "Worn"), tracks.map { it.title })
        val favorites = requests.last { "Filters=IsFavorite" in it }
        assertTrue("ArtistIds=7" in favorites)
        val played = requests.last { "SortBy=PlayCount" in it }
        assertTrue("Filters=IsPlayed" in played)
        assertTrue("SortOrder=Descending" in played)
    }

    @Test
    fun `topTracks skips the weaker tier once the count is filled`() = runTest {
        routes["SearchTerm=Artist"] = items("""{"Id":"7","Name":"Artist","Type":"MusicArtist"}""")
        routes["Filters=IsFavorite"] = items(track("100", "Loved"))
        val repo = env(backgroundScope).signedIn()

        assertEquals(listOf("Loved"), repo.topTracks("Artist", 1).map { it.title })
        assertTrue(requests.none { "SortBy=PlayCount" in it })
    }

    @Test
    fun `topTracks for an unknown artist is empty, like the subsonic side`() = runTest {
        val repo = env(backgroundScope).signedIn()
        assertEquals(emptyList(), repo.topTracks("Nobody", 5))
    }

    @Test
    fun `genres carry real track counts probed with zero-size pages`() = runTest {
        routes["/MusicGenres"] =
            items("""{"Id":"g42","Name":"Jazz"}""", """{"Id":"g43","Name":"Ambient"}""")
        routes["GenreIds=g42"] = """{"Items":[],"TotalRecordCount":123}"""
        routes["GenreIds=g43"] = """{"Items":[],"TotalRecordCount":7}"""
        val repo = env(backgroundScope).signedIn()

        val genres = repo.genres().sortedByDescending { it.songCount }

        assertEquals(listOf("Jazz" to 123, "Ambient" to 7), genres.map { it.name to it.songCount })
        val probe = requests.last { "GenreIds=g42" in it }
        assertTrue("Limit=0" in probe)
        assertTrue("IncludeItemTypes=Audio" in probe)
    }

    @Test
    fun `albumsByGenre resolves the genre id case-insensitively`() = runTest {
        routes["/MusicGenres"] = items("""{"Id":"g42","Name":"Jazz"}""")
        routes["IncludeItemTypes=MusicAlbum"] = items(
            """{"Id":"70","Type":"MusicAlbum","Name":"Kind of Blue",
                "AlbumArtists":[{"Name":"Miles","Id":"7"}],"ProductionYear":1959,
                "ChildCount":5,"RunTimeTicks":25000000000}""",
        )
        val repo = env(backgroundScope).signedIn()

        val albums = repo.albumsByGenre("jazz", 20)

        assertEquals("Kind of Blue", albums.single().title)
        assertEquals(2500, albums.single().durationSec)
        assertTrue("GenreIds=g42" in requests.last { "IncludeItemTypes=MusicAlbum" in it })
    }

    // Jellyfin's only year filter is the exact-match Years comma list; a
    // range is spelled out value by value.
    @Test
    fun `year ranges are spelled out as a Years list`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.albumsByYearRange(1950, 1959, 10)
        val albums = requests.last { "IncludeItemTypes=MusicAlbum" in it }
        assertTrue("Years=1950%2C1951" in albums)
        assertTrue("1959" in albums)

        repo.randomTracksByYearRange(1970, 1979, 25)
        val tracks = requests.last { "IncludeItemTypes=Audio" in it }
        assertTrue("Years=1970%2C" in tracks)
        assertTrue("SortBy=Random" in tracks)
    }

    @Test
    fun `played albums derive from played tracks, deduped by album`() = runTest {
        routes["Filters=IsPlayed"] = items(
            track("100", "Song A", albumId = "70"),
            track("101", "Song B", albumId = "70"),
            track("102", "Song C", albumId = "71"),
        )
        val repo = env(backgroundScope).signedIn()

        val recent = repo.recentlyPlayedAlbums(10)

        assertEquals(listOf("70", "71"), recent.map { it.id })
        assertEquals("The Album", recent.first().title)
        val url = requests.last { "Filters=IsPlayed" in it }
        assertTrue("SortBy=DatePlayed" in url)
        assertTrue("IncludeItemTypes=Audio" in url)

        val most = repo.mostPlayedAlbums(10)
        assertEquals(listOf("70", "71"), most.map { it.id })
        assertTrue("SortBy=PlayCount" in requests.last { "Filters=IsPlayed" in it })
    }

    @Test
    fun `played artists derive from played tracks, deduped by artist`() = runTest {
        routes["Filters=IsPlayed"] = items(
            track("100", "Song A"),
            track("101", "Song B"),
        )
        val repo = env(backgroundScope).signedIn()

        assertEquals(listOf("7"), repo.mostPlayedArtists(10).map { it.id })
    }

    @Test
    fun `similarArtists reads artist entries off the similar endpoint`() = runTest {
        routes["/Items/7/Similar"] = items(
            """{"Id":"8","Type":"MusicArtist","Name":"Kin"}""",
            """{"Id":"7","Type":"MusicArtist","Name":"Artist"}""",
            """{"Id":"9","Type":"MusicArtist","Name":"Cousin"}""",
            """{"Id":"8","Type":"MusicArtist","Name":"Kin"}""",
            """{"Id":"70","Type":"MusicAlbum","Name":"Not An Artist"}""",
        )
        val repo = env(backgroundScope).signedIn()

        // The seed itself and the duplicate are dropped; album entries ignored.
        assertEquals(listOf("8", "9"), repo.similarArtists("7", 10).map { it.id })
        assertEquals(listOf("8"), repo.similarArtists("7", 1).map { it.id })
    }

    @Test
    fun `similarTracks draws random tracks across the seed and similar artists`() = runTest {
        routes["/Items/7/Similar"] = items("""{"Id":"8","Type":"MusicArtist","Name":"Kin"}""")
        routes["SortBy=Random"] = items(track("100", "Song"))
        val repo = env(backgroundScope).signedIn()

        val tracks = repo.similarTracks("7", 25)

        assertEquals(listOf("100"), tracks.map { it.id })
        val url = requests.last { "SortBy=Random" in it }
        // One request, artist ids OR'd via the comma list.
        assertTrue("ArtistIds=7%2C8" in url)
    }

    @Test
    fun `similarTracks without similar artists is empty, like the subsonic side`() = runTest {
        val repo = env(backgroundScope).signedIn()

        assertEquals(emptyList(), repo.similarTracks("7", 25))
        // The "no similarity data" answer must not burn a track query.
        assertTrue(requests.none { "SortBy=Random" in it })
    }

    @Test
    fun `scrobble submission marks played, now-playing opens a session`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.scrobble("100", submission = false)
        assertTrue("POST https://jf.example.com/Sessions/Playing" in requests.last())

        repo.scrobble("100", submission = true)
        assertTrue("POST https://jf.example.com/Users/u1/PlayedItems/100" in requests.last())
    }

    @Test
    fun `reportPlayState opens the play session before the first progress`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.reportPlayState("100", PlayState.PAUSED, positionMs = 61_000, durationMs = 180_000)

        // Start first (the server was never told about this item), then the
        // progress report.
        val start = requests[requests.size - 2]
        assertEquals("POST https://jf.example.com/Sessions/Playing", start)
        assertTrue("/Sessions/Playing/Progress" in requests.last())

        repo.reportPlayState("100", PlayState.PLAYING, positionMs = 62_000, durationMs = 180_000)
        // Same item — no second start.
        assertTrue("/Sessions/Playing/Progress" in requests.last())
        assertEquals(1, requests.count { it.endsWith("/Sessions/Playing") })

        repo.reportPlayState("100", PlayState.STOPPED, positionMs = 63_000, durationMs = 180_000)
        assertTrue("/Sessions/Playing/Stopped" in requests.last())

        // The stop cleared the session: the next report re-opens it.
        repo.reportPlayState("100", PlayState.PLAYING, positionMs = 0, durationMs = 180_000)
        assertEquals(2, requests.count { it.endsWith("/Sessions/Playing") })

        assertEquals(10_000L, repo.playbackReportIntervalMs)
    }

    @Test
    fun `auth rejection translates to MusicException and signs out`() = runTest {
        val env = env(backgroundScope)
        val repo = env.signedIn()
        failWith = HttpStatusCode.Unauthorized

        assertFailsWith<MusicException.AuthFailed> { repo.artists() }
        assertIs<SessionManager.SessionState.SignedOut>(
            env.sessionManager.state.first { it !is SessionManager.SessionState.Initializing },
        )
    }

    @Test
    fun `signed out throws AuthFailed without a network call`() = runTest {
        val env = env(backgroundScope)
        env.sessionManager.state.first { it !is SessionManager.SessionState.Initializing }

        assertFailsWith<MusicException.AuthFailed> { env.repository.artists() }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `repeated artists calls are served from the ttl cache`() = runTest {
        routes["/Artists/AlbumArtists"] =
            items("""{"Id":"7","Name":"Artist","Type":"MusicArtist","ChildCount":2}""")
        val repo = env(backgroundScope).signedIn()

        assertEquals(repo.artists(), repo.artists())
        assertEquals(1, requests.count { "/Artists/AlbumArtists" in it })
        assertEquals(2, repo.artists().single().albumCount)
    }

    @Test
    fun `setTrackFavorite clears the cache so favorites refetch`() = runTest {
        routes["Filters=IsFavorite"] = items(track("100", "Loved"))
        val repo = env(backgroundScope).signedIn()

        assertTrue(repo.isFavoriteTrack("100"))
        assertTrue(repo.isFavoriteTrack("100"))
        // Both lookups answered by the one cached favorites fetch.
        assertEquals(1, requests.count { "Filters=IsFavorite" in it })

        repo.setTrackFavorite("100", false)

        assertTrue(repo.isFavoriteTrack("100"))
        // The mutation dropped the cache: the answer was refetched.
        assertEquals(2, requests.count { "Filters=IsFavorite" in it })
    }

    @Test
    fun `a session change clears the cache`() = runTest {
        routes["/Artists/AlbumArtists"] =
            items("""{"Id":"7","Name":"Artist","Type":"MusicArtist"}""")
        val env = env(backgroundScope)
        val repo = env.signedIn()

        repo.artists()
        assertEquals(1, requests.count { "/Artists/AlbumArtists" in it })

        env.sessionManager.signOut()
        env.sessionManager.signInJellyfin(account).getOrThrow()
        // Let the state collector run its clears before probing the cache.
        testScheduler.advanceUntilIdle()

        // Same account, same fingerprint — a hit unless the clear happened.
        repo.artists()
        assertEquals(2, requests.count { "/Artists/AlbumArtists" in it })
    }

    @Test
    fun `missing playlist surfaces NotFound`() = runTest {
        val repo = env(backgroundScope).signedIn()

        // The default route answers an id-less body — not addressable.
        assertFailsWith<MusicException.NotFound> { repo.playlist("999") }
    }
}
