package studio.koeda.norrklang.ui.signin

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.File
import java.io.IOException
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
import studio.koeda.norrklang.data.settings.StoredAccount
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

    /** Home profiles served by `/api/v2/home/users`; null answers 403 (no Home). */
    private var homeUsers: String? = null

    /** The PIN the switch endpoint accepts for the protected profile. */
    private var acceptedPin = "1234"

    /** Token (X-Plex-Token header) of the last `/api/v2/resources` call. */
    @Volatile
    private var resourcesToken: String? = null

    private val home = """{"id":1,"name":"Home","users":[
        {"id":10,"uuid":"u-admin","title":"Fredrik","admin":true,"restricted":false,"protected":false},
        {"id":11,"uuid":"u-kid","title":"Kid","admin":false,"restricted":true,"protected":false},
        {"id":12,"uuid":"u-teen","title":"Teen","admin":false,"restricted":true,"protected":true}
    ]}"""

    private fun plexTvEngine() = MockEngine { request ->
        val url = request.url.toString()
        val ok = { body: String ->
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        when {
            "/api/v2/home/users/" in url && url.endsWith("/switch") -> {
                val uuid = url.substringAfter("/home/users/").substringBefore("/switch")
                val pin = String(request.body.toByteArray()).substringAfter("pin=", "")
                if (uuid == "u-teen" && pin != acceptedPin) {
                    respond("""{"errors":[{"code":1041}]}""", HttpStatusCode.Forbidden)
                } else {
                    ok("""{"uuid":"$uuid","authToken":"$uuid-token"}""")
                }
            }
            "/api/v2/home/users" in url -> homeUsers?.let(ok)
                ?: respond("", HttpStatusCode.Forbidden)
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
            "/api/v2/resources" in url -> {
                resourcesToken = request.headers["X-Plex-Token"]
                ok(
                    """[{"name":"Vault","provides":"server","clientIdentifier":"m1","owned":true,
                    "accessToken":"srv-tok","connections":[
                      {"protocol":"https","address":"10.0.0.2","port":32400,
                       "uri":"https://local.example:32400","local":true,"relay":false},
                      {"protocol":"https","address":"1.2.3.4","port":443,
                       "uri":"https://relay.example:443","local":false,"relay":true}
                    ]}]""",
                )
            }
            else -> ok("{}")
        }
    }

    /** The server's music sections (key to title). */
    private var sections = listOf("5" to "Music")

    private fun serverEngine() = MockEngine { request ->
        val url = request.url.toString()
        val body = when {
            "/library/sections" in url -> sections.joinToString(
                prefix = """{"MediaContainer":{"Directory":[""",
                postfix = "]}}",
            ) { (key, title) -> """{"key":"$key","type":"artist","title":"$title"}""" }
            else -> "{}"
        }
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private lateinit var settings: ServerSettingsRepository

    private fun viewModel(pollTimeoutMs: Long = SIGN_IN_POLL_TIMEOUT_MS): PlexSignInViewModel {
        settings = ServerSettingsRepository(
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
            pollTimeoutMs = pollTimeoutMs,
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
    fun `several music sections land on the picker and the choice is persisted`() {
        sections = listOf("5" to "Music", "6" to "Audiobooks")
        val vm = viewModel()

        vm.start()
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        val pick = assertIs<PlexSignInViewModel.UiState.PickConnection>(vm.state)
        vm.selectConnection(pick.server, pick.probes.first().connection)
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickLibraries }

        val libraries = assertIs<PlexSignInViewModel.UiState.PickLibraries>(vm.state)
        assertEquals(listOf("Music", "Audiobooks"), libraries.libraries.map { it.name })
        assertEquals(setOf("5", "6"), libraries.selected)

        vm.toggleLibrary("6", false)
        assertEquals(setOf("5"), assertIs<PlexSignInViewModel.UiState.PickLibraries>(vm.state).selected)
        // The last selected library cannot be deselected.
        vm.toggleLibrary("5", false)
        assertEquals(setOf("5"), assertIs<PlexSignInViewModel.UiState.PickLibraries>(vm.state).selected)

        vm.confirmLibraries()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Done }
        assertEquals(setOf("6"), runBlocking { settings.excludedLibraryIds.first() })
    }

    @Test
    fun `a Plex Home lands on the profile picker and the admin pick skips the switch`() {
        homeUsers = home
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickUser }
        val pick = assertIs<PlexSignInViewModel.UiState.PickUser>(vm.state)
        assertEquals(listOf("Fredrik", "Kid", "Teen"), pick.users.map { it.title })

        vm.selectUser(pick.users.first { it.admin })
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        // Servers were fetched with the linked (admin) token, no switch in between.
        assertEquals("tok", resourcesToken)
        val connection = assertIs<PlexSignInViewModel.UiState.PickConnection>(vm.state)
        vm.selectConnection(connection.server, connection.probes.first().connection)
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Done }
        assertEquals("demo", plexUsername())
    }

    @Test
    fun `a managed profile is switched before the servers are fetched`() {
        homeUsers = home
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickUser }
        val pick = assertIs<PlexSignInViewModel.UiState.PickUser>(vm.state)

        vm.selectUser(pick.users.first { it.title == "Kid" })
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        // The profile's plex.tv token is exchanged for the server token via
        // /resources — PMS rejects the switch token itself.
        assertEquals("u-kid-token", resourcesToken)
        val connection = assertIs<PlexSignInViewModel.UiState.PickConnection>(vm.state)
        vm.selectConnection(connection.server, connection.probes.first().connection)
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Done }
        assertEquals("Kid", plexUsername())
    }

    @Test
    fun `a protected profile asks for its PIN and a wrong PIN stays on the page`() {
        homeUsers = home
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickUser }
        val pick = assertIs<PlexSignInViewModel.UiState.PickUser>(vm.state)

        vm.selectUser(pick.users.first { it.title == "Teen" })
        val entering = assertIs<PlexSignInViewModel.UiState.EnterPin>(vm.state)
        assertEquals("Teen", entering.user.title)
        assertFalse(entering.wrongPin)

        vm.submitPin("0000")
        awaitState(vm) { (vm.state as? PlexSignInViewModel.UiState.EnterPin)?.wrongPin == true }

        vm.submitPin("1234")
        awaitState(vm) {
            (vm.state as? PlexSignInViewModel.UiState.PickConnection)?.probing == false
        }
        assertEquals("u-teen-token", resourcesToken)
    }

    @Test
    fun `back from the PIN page returns to the profile list`() {
        homeUsers = home
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickUser }
        val pick = assertIs<PlexSignInViewModel.UiState.PickUser>(vm.state)

        vm.selectUser(pick.users.first { it.pinProtected })
        assertIs<PlexSignInViewModel.UiState.EnterPin>(vm.state)
        vm.cancelPin()
        assertEquals(pick, vm.state)
    }

    @Test
    fun `a Home with only the admin skips the picker`() {
        homeUsers = """{"users":[{"uuid":"u-admin","title":"Fredrik","admin":true}]}"""
        val vm = viewModel()

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.PickConnection }
    }

    private fun plexUsername(): String? = runBlocking {
        (settings.currentAccount() as? StoredAccount.Plex)?.account?.username
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
    fun `an unclaimed pin times out as LINK_EXPIRED and retry mints a fresh one`() {
        pollsUntilClaimed = Int.MAX_VALUE
        val vm = viewModel(pollTimeoutMs = 300)

        vm.start()
        awaitState(vm) { vm.state is PlexSignInViewModel.UiState.Error }
        val error = assertIs<PlexSignInViewModel.UiState.Error>(vm.state)
        assertEquals(PlexSignInViewModel.ErrorKind.LINK_EXPIRED, error.kind)

        pollsUntilClaimed = 0
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
