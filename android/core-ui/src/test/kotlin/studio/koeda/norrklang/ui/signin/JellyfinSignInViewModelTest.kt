package studio.koeda.norrklang.ui.signin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import studio.koeda.norrklang.data.session.SessionManager
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.ui.signin.SignInViewModel.ErrorKind
import studio.koeda.norrklang.ui.signin.SignInViewModel.UiState

private class PassthroughJellyfinCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

/**
 * Drives the form against a MockEngine in real time, like the Plex sibling —
 * [awaitState] sleep-polls with a generous deadline.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class JellyfinSignInViewModelTest {

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

    private var rejectAuth = false
    private var withMusicView = true
    private var withSecondMusicView = false
    private var dropRequests = false

    private val requests = mutableListOf<String>()

    private fun engine() = MockEngine { request ->
        val url = request.url.toString()
        requests.add("${request.method.value} $url")
        if (dropRequests) throw IOException("simulated outage")
        val ok = { body: String ->
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        when {
            "/Users/AuthenticateByName" in url ->
                if (rejectAuth) respond("", HttpStatusCode.Unauthorized)
                else ok("""{"User":{"Id":"u1","Name":"demo"},"AccessToken":"tok"}""")

            "/Users/u1/Views" in url ->
                if (withSecondMusicView) {
                    ok(
                        """{"Items":[{"Id":"lib1","Name":"Music","CollectionType":"music"},
                            {"Id":"lib2","Name":"Audiobooks","CollectionType":"music"}]}""",
                    )
                } else if (withMusicView) {
                    ok("""{"Items":[{"Id":"lib1","Name":"Music","CollectionType":"music"}]}""")
                } else {
                    ok("""{"Items":[{"Id":"v9","Name":"Movies","CollectionType":"movies"}]}""")
                }

            "/System/Info/Public" in url -> ok("""{"ServerName":"Vault"}""")
            "/Items/lib1" in url -> ok("""{"Id":"lib1","Name":"Music"}""")
            else -> ok("{}")
        }
    }

    private class TestEnv(
        val viewModel: JellyfinSignInViewModel,
        val sessionManager: SessionManager,
        val settings: ServerSettingsRepository,
    )

    private fun env(allowCleartext: Boolean = true): TestEnv {
        val settings = ServerSettingsRepository(
            PreferenceDataStoreFactory.create(scope = ioScope) {
                File(tmp.root, "test.preferences_pb")
            },
            PassthroughJellyfinCipher(),
        )
        val sessionManager = SessionManager(
            settings,
            ioScope,
            { creds -> SubsonicClient(creds, MockEngine { respond("{}") }) },
            jellyfinClientFactory = { account, info ->
                JellyfinClient(account.baseUrl, account.token, info, engine())
            },
        )
        val viewModel = JellyfinSignInViewModel(
            sessionManager,
            settings,
            { baseUrl, token, info -> JellyfinClient(baseUrl, token, info, engine()) },
            CleartextServerPolicy(allowed = allowCleartext),
        )
        return TestEnv(viewModel, sessionManager, settings)
    }

    private fun awaitPick(vm: JellyfinSignInViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.libraryPick == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(vm.libraryPick != null, "Timed out waiting for the picker; state is ${vm.state}")
    }

    private fun awaitSettled(vm: JellyfinSignInViewModel) {
        val deadline = System.currentTimeMillis() + 10_000
        while (
            (vm.state is UiState.Connecting || vm.state is UiState.Idle) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
        assertTrue(vm.state !is UiState.Connecting, "Timed out waiting; state is ${vm.state}")
    }

    @Test
    fun `missing server or username is rejected before any network call`() {
        val vm = env().viewModel

        vm.onUsernameChange("demo")
        vm.connect()

        val error = assertIs<UiState.Error>(vm.state)
        assertEquals(ErrorKind.MISSING_FIELDS, error.kind)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a plain http address is refused before any network call when cleartext is off`() {
        val vm = env(allowCleartext = false).viewModel

        vm.onServerUrlChange("http://jellyfin.local")
        vm.onUsernameChange("demo")
        vm.connect()

        val error = assertIs<UiState.Error>(vm.state)
        assertEquals(ErrorKind.CLEARTEXT, error.kind)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `happy path signs in, connects the session and clears the password`() {
        val env = env()
        val vm = env.viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.onPasswordChange("hunter2")
        vm.connect()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertEquals("", vm.password)
        val state = assertIs<SessionManager.SessionState.Connected>(env.sessionManager.state.value)
        assertEquals("Vault", state.session.serverLabel)
        assertEquals("demo", state.session.accountLabel)
    }

    @Test
    fun `blank password is allowed - jellyfin supports password-less users`() {
        val vm = env().viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.connect()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertTrue(requests.any { "/Users/AuthenticateByName" in it })
    }

    @Test
    fun `several music libraries show the picker and the choice is persisted`() {
        withSecondMusicView = true
        val env = env()
        val vm = env.viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.connect()
        awaitPick(vm)

        assertIs<UiState.Idle>(vm.state)
        assertEquals(setOf("lib1", "lib2"), vm.libraryPick?.selected)
        vm.toggleLibrary("lib1", false)
        // The last selected library cannot be deselected.
        vm.toggleLibrary("lib2", false)
        assertEquals(setOf("lib2"), vm.libraryPick?.selected)

        vm.confirmLibraries()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertNull(vm.libraryPick)
        assertEquals(setOf("lib1"), runBlocking { env.settings.excludedLibraryIds.first() })
        assertIs<SessionManager.SessionState.Connected>(env.sessionManager.state.value)
    }

    @Test
    fun `cancelling the picker returns to the form`() {
        withSecondMusicView = true
        val vm = env().viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.connect()
        awaitPick(vm)

        vm.cancelLibraryPick()

        assertNull(vm.libraryPick)
        assertIs<UiState.Idle>(vm.state)
        assertEquals("jf.example.com", vm.serverUrl)
    }

    @Test
    fun `rejected credentials surface as an auth error`() {
        rejectAuth = true
        val vm = env().viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.onPasswordChange("wrong")
        vm.connect()
        awaitSettled(vm)

        assertEquals(ErrorKind.AUTH, assertIs<UiState.Error>(vm.state).kind)
    }

    @Test
    fun `a server without a music library surfaces the dedicated error`() {
        withMusicView = false
        val vm = env().viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.connect()
        awaitSettled(vm)

        assertEquals(ErrorKind.NO_MUSIC_LIBRARY, assertIs<UiState.Error>(vm.state).kind)
    }

    @Test
    fun `an unreachable server surfaces as a network error`() {
        dropRequests = true
        val vm = env().viewModel

        vm.onServerUrlChange("jf.example.com")
        vm.onUsernameChange("demo")
        vm.connect()
        awaitSettled(vm)

        assertEquals(ErrorKind.NETWORK, assertIs<UiState.Error>(vm.state).kind)
    }
}
