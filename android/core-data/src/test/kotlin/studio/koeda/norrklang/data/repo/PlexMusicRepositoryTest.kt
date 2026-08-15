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
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials

private class PassthroughCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

/**
 * Exercises the Subsonic→Plex mapping through a URL-routed MockEngine —
 * one handler per Plex endpoint shape, requests recorded for asserting the
 * query construction.
 */
class PlexMusicRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val account = PlexAccount(
        serverUri = "https://vault.example.com:32400",
        serverName = "Vault",
        machineIdentifier = "m1",
        token = "plex-token",
        sectionId = "5",
        username = "demo",
    )

    private val requests = mutableListOf<String>()

    /** Maps a URL substring to the MediaContainer JSON answering it. */
    private val routes = mutableMapOf<String, String>()

    private var failWith: HttpStatusCode? = null

    private fun engine() = MockEngine { request ->
        val url = request.url.toString()
        requests.add(url)
        failWith?.let { return@MockEngine respond("", it) }
        val body = routes.entries.firstOrNull { (pattern, _) -> pattern in url }?.value
            ?: """{"MediaContainer":{}}"""
        respond(
            content = body,
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private fun TestEnv.repository() = repository

    private class TestEnv(
        val sessionManager: SessionManager,
        val repository: PlexMusicRepository,
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
            { acc, info -> PlexServerClient(acc.serverUri, acc.token, info, engine()) },
        )
        return TestEnv(
            sessionManager,
            PlexMusicRepository(sessionManager, "studio.koeda.norrklang", scope),
        )
    }

    private suspend fun TestEnv.signedIn(): PlexMusicRepository {
        sessionManager.signInPlex(account).getOrThrow()
        return repository
    }

    private fun track(ratingKey: String, title: String) =
        """{"ratingKey":"$ratingKey","type":"track","title":"$title",
            "grandparentTitle":"The Artist","grandparentRatingKey":"7",
            "parentTitle":"The Album","parentRatingKey":"70",
            "index":3,"parentIndex":1,"duration":215000,
            "thumb":"/library/metadata/$ratingKey/thumb/1",
            "Media":[{"Part":[{"id":1,"key":"/library/parts/$ratingKey/1/file.flac"}]}]}"""

    @Test
    fun `tracks map rating keys, metadata and authenticated stream urls`() = runTest {
        routes["type=10"] = """{"MediaContainer":{"Metadata":[${track("100", "Song")}]}}"""
        val repo = env(backgroundScope).signedIn()

        val tracks = repo.randomTracks(10)

        val t = tracks.single()
        assertEquals("100", t.id)
        assertEquals("The Artist", t.artistName)
        assertEquals("7", t.artistId)
        assertEquals("The Album", t.albumTitle)
        assertEquals(3, t.trackNumber)
        assertEquals(1, t.discNumber)
        assertEquals(215, t.durationSec)
        assertEquals(
            "https://vault.example.com:32400/library/parts/100/1/file.flac?X-Plex-Token=plex-token",
            t.streamUrl,
        )
        // Artwork is indirected through the in-app provider, thumb path encoded.
        assertTrue(t.artworkUrl!!.startsWith("content://studio.koeda.norrklang.artwork/cover/"))
        assertTrue("%2Flibrary%2Fmetadata%2F100%2Fthumb%2F1" in t.artworkUrl!!)
    }

    @Test
    fun `favorites round-trip through userRating 10`() = runTest {
        routes["userRating=10"] = """{"MediaContainer":{"Metadata":[${track("100", "Loved")}]}}"""
        val repo = env(backgroundScope).signedIn()

        assertTrue(repo.isFavoriteTrack("100"))
        assertTrue(!repo.isFavoriteTrack("999"))

        repo.setTrackFavorite("100", false)
        val rateUrl = requests.last { "/:/rate" in it }
        assertTrue("key=100" in rateUrl)
        assertTrue("rating=-1" in rateUrl)

        repo.setTrackFavorite("100", true)
        assertTrue("rating=10" in requests.last { "/:/rate" in it })
    }

    @Test
    fun `topTracks blends own ratings, global popularity and own plays in order`() = runTest {
        routes["type=8"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"7","type":"artist","title":"The Artist"}]}}"""
        routes["sort=userRating"] =
            """{"MediaContainer":{"Metadata":[${track("100", "Loved")}]}}"""
        routes["sort=ratingCount"] = """{"MediaContainer":{"Metadata":[
            ${track("100", "Loved")},${track("101", "Famous")}]}}"""
        routes["sort=viewCount"] =
            """{"MediaContainer":{"Metadata":[${track("102", "Worn")}]}}"""
        val repo = env(backgroundScope).signedIn()

        val tracks = repo.topTracks("The Artist", 5)

        // Rated first, then flames, then plays — the duplicate dropped.
        assertEquals(listOf("Loved", "Famous", "Worn"), tracks.map { it.title })
        val rated = requests.last { "sort=userRating" in it }
        // 3+ stars only, via the strict half-star operator (>5 = rating 6+).
        assertTrue("userRating%3E%3E=5" in rated)
        assertTrue("artist.id=7" in rated)
        val popular = requests.last { "sort=ratingCount" in it }
        assertTrue("ratingCount%3E%3E=0" in popular)
        val played = requests.last { "sort=viewCount" in it }
        assertTrue("viewCount%3E%3E=0" in played)
    }

    @Test
    fun `topTracks skips the weaker tiers once the count is filled`() = runTest {
        routes["type=8"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"7","type":"artist","title":"The Artist"}]}}"""
        routes["sort=userRating"] =
            """{"MediaContainer":{"Metadata":[${track("100", "Loved")}]}}"""
        val repo = env(backgroundScope).signedIn()

        assertEquals(listOf("Loved"), repo.topTracks("The Artist", 1).map { it.title })
        assertTrue(requests.none { "ratingCount" in it || "viewCount" in it })
    }

    @Test
    fun `topTracks for an unknown artist is empty, like the subsonic side`() = runTest {
        val repo = env(backgroundScope).signedIn()
        assertEquals(emptyList(), repo.topTracks("Nobody", 5))
    }

    @Test
    fun `genres carry real track counts probed with zero-size pages`() = runTest {
        routes["/genre"] = """{"MediaContainer":{"Directory":[
            {"key":"42","title":"Jazz"},{"key":"43","title":"Ambient"}]}}"""
        routes["album.genre=42"] = """{"MediaContainer":{"totalSize":123}}"""
        routes["album.genre=43"] = """{"MediaContainer":{"totalSize":7}}"""
        val repo = env(backgroundScope).signedIn()

        val genres = repo.genres().sortedByDescending { it.songCount }

        assertEquals(listOf("Jazz" to 123, "Ambient" to 7), genres.map { it.name to it.songCount })
        val probe = requests.last { "album.genre=42" in it }
        assertTrue("X-Plex-Container-Size=0" in probe)
    }

    @Test
    fun `albumsByGenre resolves the genre id from the directory`() = runTest {
        routes["/genre"] = """{"MediaContainer":{"Directory":[{"key":"42","title":"Jazz"}]}}"""
        routes["genre=42"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"70","type":"album","title":"Kind of Blue","parentTitle":"Miles",
             "parentRatingKey":"7","year":1959,"leafCount":5,"duration":2500000}]}}"""
        val repo = env(backgroundScope).signedIn()

        val albums = repo.albumsByGenre("jazz", 20)

        assertEquals("Kind of Blue", albums.single().title)
        assertEquals(2500, albums.single().durationSec)
    }

    // Plex's integer filters are the strict `>>`/`<<` operators riding in the
    // parameter NAME (`year>>=1949`); anything else is silently ignored and
    // the server answers with the whole section — which showed up as every
    // decade tile wearing the same artwork.
    @Test
    fun `albumsByYearRange uses strict plex operators widened by one`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.albumsByYearRange(1950, 1959, 10)

        val url = requests.last { "type=9" in it }
        assertTrue("year%3E%3E=1949" in url)
        assertTrue("year%3C%3C=1960" in url)
    }

    @Test
    fun `randomTracksByYearRange filters on the album year the same way`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.randomTracksByYearRange(1970, 1979, 25)

        val url = requests.last { "type=10" in it }
        assertTrue("album.year%3E%3E=1969" in url)
        assertTrue("album.year%3C%3C=1980" in url)
    }

    @Test
    fun `played rows filter on viewCount greater than zero`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.recentlyPlayedAlbums(10)

        assertTrue("viewCount%3E%3E=0" in requests.last { "type=9" in it })
    }

    @Test
    fun `similarArtists reads artist entries off the related hubs`() = runTest {
        routes["/library/metadata/7/related"] = """{"MediaContainer":{"Hub":[
            {"type":"artist","Metadata":[
                {"ratingKey":"8","type":"artist","title":"Kin"},
                {"ratingKey":"7","type":"artist","title":"The Artist"},
                {"ratingKey":"9","type":"artist","title":"Cousin"},
                {"ratingKey":"8","type":"artist","title":"Kin"}]},
            {"type":"album","Metadata":[
                {"ratingKey":"70","type":"album","title":"Not An Artist"}]}]}}"""
        val repo = env(backgroundScope).signedIn()

        // The seed itself and the duplicate are dropped; album hubs ignored.
        assertEquals(listOf("8", "9"), repo.similarArtists("7", 10).map { it.id })
        assertEquals(listOf("8"), repo.similarArtists("7", 1).map { it.id })
    }

    @Test
    fun `similarTracks draws random tracks across the seed and similar artists`() = runTest {
        routes["/library/metadata/7/related"] = """{"MediaContainer":{"Hub":[
            {"type":"artist","Metadata":[
                {"ratingKey":"8","type":"artist","title":"Kin"}]}]}}"""
        routes["type=10"] = """{"MediaContainer":{"Metadata":[${track("100", "Song")}]}}"""
        val repo = env(backgroundScope).signedIn()

        val tracks = repo.similarTracks("7", 25)

        assertEquals(listOf("100"), tracks.map { it.id })
        val url = requests.last { "type=10" in it }
        // One request, artist ids OR'd via the comma filter syntax.
        assertTrue("artist.id=7%2C8" in url)
        assertTrue("sort=random" in url)
    }

    @Test
    fun `similarTracks without similar artists is empty, like the subsonic side`() = runTest {
        val repo = env(backgroundScope).signedIn()

        assertEquals(emptyList(), repo.similarTracks("7", 25))
        // The "no similarity data" answer must not burn a track query.
        assertTrue(requests.none { "type=10" in it })
    }

    @Test
    fun `scrobble submission marks played, now-playing reports a timeline`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.scrobble("100", submission = false)
        assertTrue(requests.last().contains("/:/timeline"))
        assertTrue(requests.last().contains("state=playing"))

        repo.scrobble("100", submission = true)
        assertTrue(requests.last().contains("/:/scrobble"))
        assertTrue(requests.last().contains("key=100"))
    }

    @Test
    fun `reportPlayState sends a timeline ping with state and position`() = runTest {
        val repo = env(backgroundScope).signedIn()

        repo.reportPlayState("100", PlayState.PAUSED, positionMs = 61_000, durationMs = 180_000)

        val url = requests.last()
        assertTrue("/:/timeline" in url)
        assertTrue("ratingKey=100" in url)
        assertTrue("state=paused" in url)
        assertTrue("time=61000" in url)
        assertTrue("duration=180000" in url)
        assertEquals(15_000L, repo.playbackReportIntervalMs)
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

        assertFailsWith<MusicException.AuthFailed> { env.repository().artists() }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `repeated artists calls are served from the ttl cache`() = runTest {
        routes["type=8"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"7","type":"artist","title":"The Artist"}]}}"""
        val repo = env(backgroundScope).signedIn()

        assertEquals(repo.artists(), repo.artists())
        assertEquals(1, requests.count { "type=8" in it })
    }

    @Test
    fun `setTrackFavorite clears the cache so favorites refetch`() = runTest {
        routes["userRating=10"] = """{"MediaContainer":{"Metadata":[${track("100", "Loved")}]}}"""
        val repo = env(backgroundScope).signedIn()

        assertTrue(repo.isFavoriteTrack("100"))
        assertTrue(repo.isFavoriteTrack("100"))
        // Both lookups answered by the one cached favorites fetch.
        assertEquals(1, requests.count { "userRating=10" in it })

        repo.setTrackFavorite("100", false)

        assertTrue(repo.isFavoriteTrack("100"))
        // The mutation dropped the cache: the answer was refetched.
        assertEquals(2, requests.count { "userRating=10" in it })
    }

    @Test
    fun `a session change clears the cache`() = runTest {
        routes["type=8"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"7","type":"artist","title":"The Artist"}]}}"""
        val env = env(backgroundScope)
        val repo = env.signedIn()

        repo.artists()
        assertEquals(1, requests.count { "type=8" in it })

        env.sessionManager.signOut()
        env.sessionManager.signInPlex(account).getOrThrow()
        // Let the state collector run its clears before probing the cache.
        testScheduler.advanceUntilIdle()

        // Same account, same fingerprint — a hit unless the clear happened.
        repo.artists()
        assertEquals(2, requests.count { "type=8" in it })
    }

    @Test
    fun `missing playlist surfaces NotFound`() = runTest {
        val repo = env(backgroundScope).signedIn()

        // The default route answers an empty MediaContainer — no metadata.
        assertFailsWith<MusicException.NotFound> { repo.playlist("999") }
    }

    @Test
    fun `track without playable media surfaces NotFound`() = runTest {
        routes["/library/metadata/55"] = """{"MediaContainer":{"Metadata":[
            {"ratingKey":"55","type":"track","title":"Ghost"}]}}"""
        val repo = env(backgroundScope).signedIn()

        assertFailsWith<MusicException.NotFound> { repo.track("55") }
    }
}
