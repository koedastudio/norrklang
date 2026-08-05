package studio.koeda.norrklang.data.session

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials

private class PassthroughCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

class SessionManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val okBody = """{"subsonic-response":{"status":"ok","version":"1.16.1"}}"""
    private val authFailedBody =
        """{"subsonic-response":{"status":"failed","version":"1.16.1",
            "error":{"code":40,"message":"Wrong username or password"}}}"""

    /** Every client created by this factory answers all calls with [body]. */
    private fun clientFactory(body: String): (SubsonicCredentials) -> SubsonicClient = { creds ->
        SubsonicClient(
            creds,
            MockEngine {
                respond(
                    content = body,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
    }

    private fun dataStore(scope: CoroutineScope): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope) {
            File(tmp.root, "test.preferences_pb")
        }

    private fun settings(scope: CoroutineScope) =
        ServerSettingsRepository(dataStore(scope), PassthroughCipher())

    private suspend fun SessionManager.resolvedState(): SessionManager.SessionState =
        state.first { it !is SessionManager.SessionState.Initializing }

    @Test
    fun `starts signed out with no stored credentials`() = runTest {
        val manager = SessionManager(settings(backgroundScope), backgroundScope, clientFactory(okBody))
        assertIs<SessionManager.SessionState.SignedOut>(manager.resolvedState())
    }

    @Test
    fun `restores stored credentials on start`() = runTest {
        val settings = settings(backgroundScope)
        settings.save(SubsonicCredentials.fromInput("https://music.example.com", "demo", "secret"))

        val manager = SessionManager(settings, backgroundScope, clientFactory(okBody))

        val state = assertIs<SessionManager.SessionState.Connected>(manager.resolvedState())
        assertEquals("https://music.example.com", state.credentials.baseUrl)
        assertEquals("demo", state.credentials.username)
    }

    @Test
    fun `successful sign-in connects and persists`() = runTest {
        val settings = settings(backgroundScope)
        val manager = SessionManager(settings, backgroundScope, clientFactory(okBody))
        manager.resolvedState()

        val result = manager.signIn("https://music.example.com", "demo", "secret")

        assertTrue(result.isSuccess)
        assertIs<SessionManager.SessionState.Connected>(manager.state.value)
        assertNotNull(settings.currentCredentials())
    }

    @Test
    fun `rejected sign-in stays signed out and persists nothing`() = runTest {
        val settings = settings(backgroundScope)
        val manager = SessionManager(settings, backgroundScope, clientFactory(authFailedBody))
        manager.resolvedState()

        val result = manager.signIn("https://music.example.com", "demo", "wrong")

        assertTrue(result.isFailure)
        assertIs<SessionManager.SessionState.SignedOut>(manager.state.value)
        assertNull(settings.currentCredentials())
    }

    @Test
    fun `signing into another server replaces the session`() = runTest {
        val settings = settings(backgroundScope)
        val manager = SessionManager(settings, backgroundScope, clientFactory(okBody))
        manager.resolvedState()
        manager.signIn("https://one.example.com", "alice", "secret")

        manager.signIn("https://two.example.com", "bob", "hunter2")

        val state = assertIs<SessionManager.SessionState.Connected>(manager.state.value)
        assertEquals("https://two.example.com", state.credentials.baseUrl)
        assertEquals("bob", state.credentials.username)
        assertEquals("https://two.example.com", settings.currentCredentials()?.baseUrl)
    }

    @Test
    fun `sign-out clears stored credentials and state`() = runTest {
        val settings = settings(backgroundScope)
        val manager = SessionManager(settings, backgroundScope, clientFactory(okBody))
        manager.resolvedState()
        manager.signIn("https://music.example.com", "demo", "secret")

        manager.signOut()

        assertIs<SessionManager.SessionState.SignedOut>(manager.state.value)
        assertNull(settings.currentCredentials())
    }

    @Test
    fun `server-side auth rejection flips to signed out`() = runTest {
        val settings = settings(backgroundScope)
        val manager = SessionManager(settings, backgroundScope, clientFactory(okBody))
        manager.resolvedState()
        manager.signIn("https://music.example.com", "demo", "secret")

        manager.onAuthRejected()

        assertIs<SessionManager.SessionState.SignedOut>(manager.state.value)
    }
}
