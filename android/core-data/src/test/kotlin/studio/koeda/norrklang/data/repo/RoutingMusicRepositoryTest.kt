package studio.koeda.norrklang.data.repo

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

/**
 * Pins the provider routing: every call lands on the backend matching the
 * active session, and the signed-out answers match the per-provider
 * repositories (throw), except the lenient reporting pair (null / no-op).
 */
class RoutingMusicRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class PassthroughCipher : CredentialCipher {
        override fun encrypt(plaintext: String) = "enc-test:$plaintext"
        override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
        override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
    }

    private val account = PlexAccount(
        serverUri = "https://vault.example.com:32400",
        serverName = "Vault",
        machineIdentifier = "m1",
        token = "plex-token",
        sectionId = "5",
        username = "demo",
    )

    private val requests = mutableListOf<String>()

    private fun engine() = MockEngine { request ->
        requests.add(request.url.toString())
        respond(
            content = """{"MediaContainer":{}}""",
            headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
    }

    private class TestEnv(
        val sessionManager: SessionManager,
        val repository: RoutingMusicRepository,
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
        val repository = RoutingMusicRepository(
            sessionManager,
            SubsonicMusicRepository(sessionManager, settings, "studio.koeda.norrklang", scope),
            PlexMusicRepository(sessionManager, "studio.koeda.norrklang", scope),
        )
        return TestEnv(sessionManager, repository)
    }

    private suspend fun TestEnv.settled(): TestEnv {
        sessionManager.state.first { it !is SessionManager.SessionState.Initializing }
        return this
    }

    @Test
    fun `signed out throws AuthFailed without a network call`() = runTest {
        val env = env(backgroundScope).settled()

        assertFailsWith<MusicException.AuthFailed> { env.repository.artists() }
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `signed out reporting is lenient - null interval and silent no-op`() = runTest {
        val env = env(backgroundScope).settled()

        assertNull(env.repository.playbackReportIntervalMs)
        env.repository.reportPlayState("100", PlayState.PLAYING, positionMs = 0, durationMs = null)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `plex session routes calls to the plex backend`() = runTest {
        val env = env(backgroundScope)
        env.sessionManager.signInPlex(account).getOrThrow()

        env.repository.artists()

        // Landed on the Plex section, as an artist listing.
        val url = requests.last { "type=8" in it }
        assertEquals(true, "/library/sections/5/all" in url)
        assertEquals(15_000L, env.repository.playbackReportIntervalMs)

        env.repository.reportPlayState(
            "100",
            PlayState.PAUSED,
            positionMs = 61_000,
            durationMs = 180_000,
        )
        assertEquals(true, "/:/timeline" in requests.last())
    }

    @Test
    fun `invalidateCache clears the routed backend's cache`() = runTest {
        val env = env(backgroundScope)
        env.sessionManager.signInPlex(account).getOrThrow()

        env.repository.artists()
        env.repository.artists()
        assertEquals(1, requests.count { "type=8" in it })

        env.repository.invalidateCache()

        env.repository.artists()
        assertEquals(2, requests.count { "type=8" in it })
    }
}
