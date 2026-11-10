package studio.koeda.norrklang.ui.signin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
import studio.koeda.norrklang.ui.signin.JellyfinSignInViewModel.Step
import studio.koeda.norrklang.ui.signin.SignInErrorKind as ErrorKind
import studio.koeda.norrklang.ui.signin.SignInUiState as UiState

private class PassthroughJellyfinCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

/**
 * Drives the pages against a MockEngine in real time, like the Plex sibling —
 * [awaitState] and [awaitStep] sleep-poll with a generous deadline.
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
    private var quickConnectEnabled = false

    /** `/QuickConnect/Connect` answers unapproved this many times before approving. */
    @Volatile
    private var approveAfterPolls = 2

    /** The first secret answers 404 on its first poll, like an expired request. */
    private var expireFirstSecret = false

    @Volatile
    private var initiateCount = 0

    @Volatile
    private var pollCount = 0

    private val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

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

            "/QuickConnect/Enabled" in url -> ok(quickConnectEnabled.toString())

            "/QuickConnect/Initiate" in url -> {
                val n = ++initiateCount
                ok("""{"Authenticated":false,"Secret":"s$n","Code":"${if (n == 1) "123456" else "654321"}"}""")
            }

            "/QuickConnect/Connect" in url -> {
                val n = ++pollCount
                if (expireFirstSecret && "secret=s1" in url) {
                    respond("", HttpStatusCode.NotFound)
                } else {
                    ok("""{"Authenticated":${n > approveAfterPolls},"Secret":"s1","Code":"123456"}""")
                }
            }

            "/Users/AuthenticateWithQuickConnect" in url ->
                ok("""{"User":{"Id":"u1","Name":"demo"},"AccessToken":"qc-tok"}""")

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
            pollIntervalMs = 50,
        )
        return TestEnv(viewModel, sessionManager, settings)
    }

    private fun awaitState(vm: JellyfinSignInViewModel, predicate: (UiState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate(vm.state) && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue(predicate(vm.state), "Timed out waiting; step is ${vm.step}, state is ${vm.state}")
    }

    /** Waits until the busy state resolves to an error or Done. */
    private fun awaitSettled(vm: JellyfinSignInViewModel) =
        awaitState(vm) { it is UiState.Error || it is UiState.Done }

    private inline fun <reified T : Step> awaitStep(vm: JellyfinSignInViewModel): T {
        val deadline = System.currentTimeMillis() + 10_000
        while (vm.step !is T && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return assertIs<T>(vm.step, "Timed out waiting for ${T::class.simpleName}; state is ${vm.state}")
    }

    private fun startWithServer(vm: JellyfinSignInViewModel) {
        vm.onServerUrlChange("jf.example.com")
        vm.continueWithServer()
    }

    private fun pollRequests() = requests.count { "/QuickConnect/Connect" in it }

    @Test
    fun `a blank server address is rejected before any network call`() {
        val vm = env().viewModel

        vm.continueWithServer()

        assertEquals(ErrorKind.MISSING_FIELDS, assertIs<UiState.Error>(vm.state).kind)
        assertEquals(Step.Server, vm.step)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `a plain http address is refused before any network call when cleartext is off`() {
        val vm = env(allowCleartext = false).viewModel

        vm.onServerUrlChange("http://jellyfin.local")
        vm.continueWithServer()

        assertEquals(ErrorKind.CLEARTEXT, assertIs<UiState.Error>(vm.state).kind)
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `an unreachable server errors on the server page`() {
        dropRequests = true
        val vm = env().viewModel

        startWithServer(vm)
        awaitSettled(vm)

        assertEquals(ErrorKind.NETWORK, assertIs<UiState.Error>(vm.state).kind)
        assertEquals(Step.Server, vm.step)
    }

    @Test
    fun `quick connect shows the code, then signs in once approved`() {
        quickConnectEnabled = true
        val env = env()
        val vm = env.viewModel

        startWithServer(vm)
        val qc = awaitStep<Step.QuickConnect>(vm)
        assertEquals("123456", qc.code)
        awaitState(vm) { it is UiState.Done }

        val state = assertIs<SessionManager.SessionState.Connected>(env.sessionManager.state.value)
        assertEquals("Vault", state.session.serverLabel)
        assertEquals("demo", state.session.accountLabel)
        assertTrue(requests.any { "/Users/AuthenticateWithQuickConnect" in it })
        assertFalse(requests.any { "/Users/AuthenticateByName" in it })
    }

    @Test
    fun `a server without quick connect goes straight to the password form`() {
        val env = env()
        val vm = env.viewModel

        startWithServer(vm)
        awaitStep<Step.Credentials>(vm)

        assertIs<UiState.Idle>(vm.state)
        assertEquals("Vault", vm.serverName)
        assertFalse(requests.any { "/QuickConnect/Initiate" in it })

        vm.onUsernameChange("demo")
        vm.onPasswordChange("hunter2")
        vm.connectWithCredentials()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertEquals("", vm.password)
        val state = assertIs<SessionManager.SessionState.Connected>(env.sessionManager.state.value)
        assertEquals("Vault", state.session.serverLabel)
        assertEquals("demo", state.session.accountLabel)
    }

    @Test
    fun `use password stops polling and shows the password form`() {
        quickConnectEnabled = true
        approveAfterPolls = Int.MAX_VALUE
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.QuickConnect>(vm)
        vm.usePassword()

        assertEquals(Step.Credentials, vm.step)
        assertIs<UiState.Idle>(vm.state)
        Thread.sleep(150)
        val polls = pollRequests()
        Thread.sleep(250)
        assertEquals(polls, pollRequests())
    }

    @Test
    fun `an expired request is replaced by a fresh code`() {
        quickConnectEnabled = true
        expireFirstSecret = true
        val vm = env().viewModel

        startWithServer(vm)
        val deadline = System.currentTimeMillis() + 10_000
        while ((vm.step as? Step.QuickConnect)?.code != "654321" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(Step.QuickConnect("654321"), vm.step)
        awaitState(vm) { it is UiState.Done }

        assertEquals(2, initiateCount)
    }

    @Test
    fun `a sustained poll outage surfaces as a network error and retry recovers`() {
        quickConnectEnabled = true
        approveAfterPolls = Int.MAX_VALUE
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.QuickConnect>(vm)
        dropRequests = true
        awaitSettled(vm)

        assertEquals(ErrorKind.NETWORK, assertIs<UiState.Error>(vm.state).kind)
        assertIs<Step.QuickConnect>(vm.step)

        dropRequests = false
        approveAfterPolls = pollCount
        vm.retry()
        awaitState(vm) { it is UiState.Done }
    }

    @Test
    fun `cancel stops polling and returns to the server page`() {
        quickConnectEnabled = true
        approveAfterPolls = Int.MAX_VALUE
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.QuickConnect>(vm)
        vm.cancel()

        assertEquals(Step.Server, vm.step)
        assertIs<UiState.Idle>(vm.state)
        assertEquals("jf.example.com", vm.serverUrl)
        Thread.sleep(150)
        val polls = pollRequests()
        Thread.sleep(250)
        assertEquals(polls, pollRequests())
    }

    @Test
    fun `rejected credentials surface as an auth error`() {
        rejectAuth = true
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.Credentials>(vm)
        vm.onUsernameChange("demo")
        vm.onPasswordChange("wrong")
        vm.connectWithCredentials()
        awaitSettled(vm)

        assertEquals(ErrorKind.AUTH, assertIs<UiState.Error>(vm.state).kind)
        assertEquals(Step.Credentials, vm.step)
    }

    @Test
    fun `a blank username is rejected but a blank password is allowed`() {
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.Credentials>(vm)
        vm.connectWithCredentials()
        assertEquals(ErrorKind.MISSING_FIELDS, assertIs<UiState.Error>(vm.state).kind)

        vm.onUsernameChange("demo")
        vm.connectWithCredentials()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertTrue(requests.any { "/Users/AuthenticateByName" in it })
    }

    @Test
    fun `several music libraries show the picker and the choice is persisted`() {
        quickConnectEnabled = true
        withSecondMusicView = true
        val env = env()
        val vm = env.viewModel

        startWithServer(vm)
        val pick = awaitStep<Step.Libraries>(vm)

        assertIs<UiState.Idle>(vm.state)
        assertEquals(setOf("lib1", "lib2"), pick.selected)
        vm.toggleLibrary("lib1", false)
        // The last selected library cannot be deselected.
        vm.toggleLibrary("lib2", false)
        assertEquals(setOf("lib2"), assertIs<Step.Libraries>(vm.step).selected)

        vm.confirmLibraries()
        awaitSettled(vm)

        assertIs<UiState.Done>(vm.state)
        assertEquals(setOf("lib1"), runBlocking { env.settings.excludedLibraryIds.first() })
        assertIs<SessionManager.SessionState.Connected>(env.sessionManager.state.value)
    }

    @Test
    fun `cancelling the picker returns to the server page`() {
        withSecondMusicView = true
        val vm = env().viewModel

        startWithServer(vm)
        awaitStep<Step.Credentials>(vm)
        vm.onUsernameChange("demo")
        vm.connectWithCredentials()
        awaitStep<Step.Libraries>(vm)

        vm.cancelLibraryPick()

        assertEquals(Step.Server, vm.step)
        assertIs<UiState.Idle>(vm.state)
        assertEquals("jf.example.com", vm.serverUrl)
    }

    @Test
    fun `a server without a music library surfaces the dedicated error on the quick connect page`() {
        quickConnectEnabled = true
        withMusicView = false
        val vm = env().viewModel

        startWithServer(vm)
        awaitSettled(vm)

        assertEquals(ErrorKind.NO_MUSIC_LIBRARY, assertIs<UiState.Error>(vm.state).kind)
        assertIs<Step.QuickConnect>(vm.step)
    }
}
