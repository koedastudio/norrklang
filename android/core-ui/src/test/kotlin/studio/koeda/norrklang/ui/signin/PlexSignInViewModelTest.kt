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
import studio.koeda.norrklang.plex.ConnectionProber
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.plex.PlexTvClient
import studio.koeda.norrklang.subsonic.SubsonicClient

private class PassthroughCipher : CredentialCipher {
    override fun encrypt(plaintext: String) = "enc-test:$plaintext"
    override fun decrypt(stored: String) = stored.removePrefix("enc-test:")
    override fun isEncrypted(stored: String) = stored.startsWith("enc-test:")
}

/**
 * Drives the flow against MockEngines in real time — the poll interval is
 * injected small, and [awaitState] sleep-polls with a generous deadline.
 * (Virtual-clock testing doesn't fit here: HTTP hops resolve on real engine
 * threads, so the two clocks would have to be pumped in lockstep.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlexSignInViewModelTest {

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

    /** Polls answered "not yet" before checkPin reports the claim. */
    private var pollsUntilClaimed = 1

    /** checkPin answers 404 this many times first (expired-pin path). */
    private var expiredChecks = 0

    /** checkPin drops at the transport level (NetworkError) this many times first. */
    private var networkFailures = 0

    @Volatile
    private var pinsCreated = 0

    private fun plexTvEngine() = MockEngine { request ->
        val url = request.url.toString()
        val ok = { body: String ->
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        when {
            request.method.value == "POST" && "/api/v2/pins" in url -> {
                pinsCreated++
                ok("""{"id":$pinsCreated,"code":"COD$pinsCreated","authToken":null}""")
            }
            "/api/v2/pins/" in url -> when {
                networkFailures > 0 -> {
                    networkFailures--
                    throw IOException("simulated dropped poll")
                }
                expiredChecks > 0 -> {
                    expiredChecks--
                    respond("", HttpStatusCode.NotFound)
                }
                pollsUntilClaimed > 0 -> {
                    pollsUntilClaimed--
                    ok("""{"id":$pinsCreated,"code":"COD$pinsCreated","authToken":null}""")
                }
                else -> ok("""{"id":$pinsCreated,"code":"COD$pinsCreated","authToken":"tok"}""")
            }
            "/api/v2/user" in url -> ok("""{"username":"demo"}""")
            "/api/v2/resources" in url -> ok(
                """[{"name":"Vault","provides":"server","clientIdentifier":"m1","owned":true,
                    "accessToken":"srv-tok","connections":[
                      {"protocol":"https","address":"10.0.0.2","port":32400,
                       "uri":"https://local.example:32400","local":true,"relay":false},
                      {"protocol":"https","address":"1.2.3.4","port":443,
                       "uri":"https://relay.example:443","local":false,"relay":true}
                    ]}]""",
            )
            else -> ok("{}")
        }
    }

    private fun serverEngine() = MockEngine { request ->
        val url = request.url.toString()
        val body = when {
            "/library/sections" in url ->
                """{"MediaContainer":{"Directory":[{"key":"5","type":"artist","title":"Music"}]}}"""
            else -> "{}"
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun viewModel(): PlexSignInViewModel {
        val settings = ServerSettingsRepository(
            PreferenceDataStoreFactory.create(scope = ioScope) {
                File(tmp.root, "test.preferences_pb")
            },
            PassthroughCipher(),
        )
        val sessionManager = SessionManager(
            settings,
            ioScope,
            { creds -> SubsonicClient(creds, MockEngine { respond("{}") }) },
            { account, info ->
                PlexServerClient(account.serverUri, account.token, info, serverEngine())
            },
        )
        return PlexSignInViewModel(
            sessionManager,
            settings,
            { info -> PlexTvClient(info, plexTvEngine()) },
            { info -> ConnectionProber(info, serverEngine()) },
            { uri, token, info -> PlexServerClient(uri, token, info, serverEngine()) },
            pollIntervalMs = 50,
        )
    }

    private fun awaitState(vm: PlexSignInViewModel, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!predicate() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(predicate(), "Timed out waiting; state is ${vm.state}")
    }

    @Test
    fun `full flow reaches Done and signs the session in`() {
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.WaitingForLink }
        val waiting = assertIs<PlexSignInViewModel.UiState.WaitingForLink>(vm.state)
        assertEquals("COD1", waiting.code)
        assertEquals("https://plex.tv/link/?pin=COD1", waiting.linkUrl)

        // One unclaimed poll, then the claim; the single server is
        // auto-selected, landing on the connection picker.
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        val pick = assertIs<PlexSignInViewModel.UiState.PickConnection>(vm.state)
        assertEquals("Vault", pick.server.name)
        assertEquals(2, pick.probes.size)
        // Relay sinks below the reachable local connection.
        assertTrue(pick.probes.first().connection.local)

        vm.selectConnection(pick.server, pick.probes.first().connection)
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Done }
    }

    @Test
    fun `expired pin is recreated and the flow continues`() {
        expiredChecks = 1
        pollsUntilClaimed = 0
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickConnection }

        // The expired first pin forced a second createPin before the claim.
        assertEquals(2, pinsCreated)
    }

    @Test
    fun `transient poll failures under the limit do not abort the link`() {
        networkFailures = 4
        pollsUntilClaimed = 0
        val vm = viewModel()

        vm.start()
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }

        // The dropped polls neither surfaced an error nor re-minted the pin.
        assertEquals(1, pinsCreated)
    }

    @Test
    fun `sustained poll failures surface LINK_FAILED`() {
        networkFailures = 5
        pollsUntilClaimed = 0
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Error }
        val error = assertIs<PlexSignInViewModel.UiState.Error>(vm.state)
        assertEquals(PlexSignInViewModel.ErrorKind.LINK_FAILED, error.kind)
    }

    @Test
    fun `retry after a failed link restarts the flow`() {
        networkFailures = 5
        pollsUntilClaimed = 0
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Error }

        // Connectivity is back; retry mints a fresh pin and completes.
        vm.retry()
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        assertEquals(2, pinsCreated)
    }

    @Test
    fun `cancel stops polling and returns to idle`() {
        pollsUntilClaimed = Int.MAX_VALUE
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.WaitingForLink }

        vm.cancel()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Idle }

        // No further polling flips the state back.
        Thread.sleep(300)
        assertIs<PlexSignInViewModel.UiState.Idle>(vm.state)
    }
}
