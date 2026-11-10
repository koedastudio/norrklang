package studio.koeda.norrklang.ui.signin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.ui.signin.SignInErrorKind as ErrorKind
import studio.koeda.norrklang.ui.signin.SignInUiState as UiState

private class PassthroughSubsonicCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

@OptIn(ExperimentalCoroutinesApi::class)
class SubsonicSignInViewModelTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val mainThread = newSingleThreadContext("test-main")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        Dispatchers.setMain(mainThread)
    }

    @After
    fun tearDown() {
        ioScope.cancel()
        Dispatchers.resetMain()
        mainThread.close()
    }

    private var dropRequests = false

    private fun env(): Pair<SubsonicSignInViewModel, SessionManager> {
        val settings = ServerSettingsRepository(
            PreferenceDataStoreFactory.create(scope = ioScope) {
                File(tmp.root, "test.preferences_pb")
            },
            PassthroughSubsonicCipher(),
        )
        val sessionManager = SessionManager(settings, ioScope, { creds ->
            SubsonicClient(
                creds,
                MockEngine {
                    if (dropRequests) throw IOException("simulated outage")
                    respond(
                        """{"subsonic-response":{"status":"ok","version":"1.16.1"}}""",
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
        })
        // Cleartext refused, as in release builds: the demo must still sign in.
        return SubsonicSignInViewModel(sessionManager, CleartextServerPolicy(allowed = false)) to sessionManager
    }

    private fun awaitDemoSettled(vm: SubsonicSignInViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.demoState !is UiState.Error && vm.demoState !is UiState.Done &&
            System.currentTimeMillis() < deadline
        ) Thread.sleep(20)
    }

    @Test
    fun `try the demo signs in to the demo server without touching the form`() {
        val (vm, sessionManager) = env()

        vm.tryDemo()
        awaitDemoSettled(vm)

        assertIs<UiState.Done>(vm.demoState)
        val state = assertIs<SessionManager.SessionState.Connected>(sessionManager.state.value)
        assertEquals("demo", state.session.accountLabel)
        assertTrue("demo-music.norrklang.app" in state.session.serverLabel)
        assertEquals("", vm.serverUrl)
        assertIs<UiState.Idle>(vm.state)
    }

    @Test
    fun `a failed demo sign-in leaves the form's state alone and can be retried`() {
        val (vm, _) = env()
        dropRequests = true

        vm.tryDemo()
        awaitDemoSettled(vm)

        assertEquals(ErrorKind.NETWORK, assertIs<UiState.Error>(vm.demoState).kind)
        assertIs<UiState.Idle>(vm.state)

        dropRequests = false
        vm.tryDemo()
        awaitDemoSettled(vm)
        assertIs<UiState.Done>(vm.demoState)
    }
}
