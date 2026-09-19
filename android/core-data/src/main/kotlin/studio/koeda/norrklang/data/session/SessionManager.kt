package studio.koeda.norrklang.data.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.diagnostics.Diagnostics
import studio.koeda.norrklang.data.repo.toMusicException
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.StoredAccount
import studio.koeda.norrklang.jellyfin.JellyfinAccount
import studio.koeda.norrklang.jellyfin.JellyfinClient
import studio.koeda.norrklang.jellyfin.JellyfinClientInfo
import studio.koeda.norrklang.jellyfin.JellyfinException
import studio.koeda.norrklang.plex.PlexAccount
import studio.koeda.norrklang.plex.PlexClientInfo
import studio.koeda.norrklang.plex.PlexException
import studio.koeda.norrklang.plex.PlexServerClient
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicException

/**
 * Owns the signed-in state and the live provider session.
 *
 * On process start, the stored account is restored optimistically (no blocking
 * ping) so the car UI gets a browse tree immediately; auth failures surface
 * per-request and flip the state to [SessionState.SignedOut].
 */
@Singleton
class SessionManager(
    private val settings: ServerSettingsRepository,
    private val scope: CoroutineScope,
    private val clientFactory: (SubsonicCredentials) -> SubsonicClient,
    private val plexClientFactory: (PlexAccount, PlexClientInfo) -> PlexServerClient =
        { account, info -> PlexServerClient(account.serverUri, account.token, info) },
    private val jellyfinClientFactory: (JellyfinAccount, JellyfinClientInfo) -> JellyfinClient =
        { account, info -> JellyfinClient(account.baseUrl, account.token, info) },
) {

    @Inject constructor(
        settings: ServerSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(settings, scope, { SubsonicClient(it) })

    sealed interface SessionState {
        data object Initializing : SessionState
        data object SignedOut : SessionState
        data class Connected(val session: ProviderSession) : SessionState
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Initializing)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        scope.launch {
            val restored = try {
                when (val stored = settings.currentAccount()) {
                    is StoredAccount.Subsonic -> connectedState(stored.credentials)
                    is StoredAccount.Plex -> SessionState.Connected(plexSession(stored.account))
                    is StoredAccount.Jellyfin ->
                        SessionState.Connected(jellyfinSession(stored.account))
                    null -> SessionState.SignedOut
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A restore failure degrades to signed out (the car then shows
                // the sign-in affordance). It must neither crash the process
                // nor die leaving the state Initializing — every browse call
                // awaits that state resolving.
                Diagnostics.record("session-restore", e)
                SessionState.SignedOut
            }
            // Only move out of Initializing if nothing else (e.g. a concurrent
            // sign-in) has already resolved the state.
            if (!_state.compareAndSet(SessionState.Initializing, restored)) {
                (restored as? SessionState.Connected)?.session?.close()
            }
        }
    }

    /**
     * Validates against the server with a ping, then persists and connects.
     *
     * Token auth is tried first. A server that cannot verify a salted token
     * (Nextcloud Music, LDAP-backed Subsonic) says so with error 41/42, and
     * only then is the password itself sent — and stored — instead.
     */
    suspend fun signIn(url: String, username: String, password: String): Result<Unit> {
        val credentials = try {
            SubsonicCredentials.fromInput(url, username, password)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        return try {
            try {
                connectSubsonic(credentials)
            } catch (e: SubsonicException.AuthFailed) {
                if (!e.isTokenAuthUnsupported) throw e
                connectSubsonic(credentials.withPasswordAuth(password))
            }
            Result.success(Unit)
        } catch (e: SubsonicException) {
            Result.failure(e.toMusicException())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Broader than SubsonicException: save() can fail in the Keystore
            // encrypt, and the sign-in form must render that as an error, not
            // crash the caller's scope.
            Result.failure(e)
        }
    }

    /** Pings, persists and activates [credentials]; the candidate session is closed on any failure. */
    private suspend fun connectSubsonic(credentials: SubsonicCredentials) {
        val candidate = connectedState(credentials)
        try {
            (candidate.session as SubsonicSession).client.ping()
            settings.save(credentials)
            replaceState(candidate)
        } catch (e: Throwable) {
            candidate.session.close()
            throw e
        }
    }

    /**
     * Validates the token and that the server has a music library, then
     * persists account + library selection atomically and connects. Replaces
     * any previous provider's sign-in (single active server).
     */
    suspend fun signInPlex(
        account: PlexAccount,
        excludedLibraryIds: Set<String> = emptySet(),
    ): Result<Unit> {
        val candidate = SessionState.Connected(plexSession(account))
        return try {
            (candidate.session as PlexSession).client.validateMusicLibraries()
            settings.savePlex(account, excludedLibraryIds)
            replaceState(candidate)
            Result.success(Unit)
        } catch (e: PlexException) {
            candidate.session.close()
            Result.failure(e.toMusicException())
        } catch (e: CancellationException) {
            candidate.session.close()
            throw e
        } catch (e: Exception) {
            // Broader than PlexException: save() can fail in the Keystore
            // encrypt, and the sign-in form must render that as an error, not
            // crash the caller's scope.
            candidate.session.close()
            Result.failure(e)
        }
    }

    /** See [signInPlex]. */
    suspend fun signInJellyfin(
        account: JellyfinAccount,
        excludedLibraryIds: Set<String> = emptySet(),
    ): Result<Unit> {
        val candidate = SessionState.Connected(jellyfinSession(account))
        return try {
            (candidate.session as JellyfinSession).client.validateMusicLibraries(account.userId)
            settings.saveJellyfin(account, excludedLibraryIds)
            replaceState(candidate)
            Result.success(Unit)
        } catch (e: JellyfinException) {
            candidate.session.close()
            Result.failure(e.toMusicException())
        } catch (e: CancellationException) {
            candidate.session.close()
            throw e
        } catch (e: Exception) {
            // Broader than JellyfinException: save() can fail in the Keystore
            // encrypt, and the sign-in form must render that as an error, not
            // crash the caller's scope.
            candidate.session.close()
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        settings.clearAccount()
        replaceState(SessionState.SignedOut)
    }

    /** Called by the data layer when the server rejects our stored credentials. */
    fun onAuthRejected(rejectedSession: ProviderSession) {
        val current = _state.value as? SessionState.Connected ?: return
        // A late response from an old account must not sign out its replacement.
        if (current.session === rejectedSession &&
            _state.compareAndSet(current, SessionState.SignedOut)
        ) {
            rejectedSession.close()
        }
    }

    fun connectedOrNull(): SessionState.Connected? = _state.value as? SessionState.Connected

    /** Swaps the state and closes the session the old state owned, if any. */
    private fun replaceState(newState: SessionState) {
        val previous = _state.getAndUpdate { newState }
        if (previous is SessionState.Connected && previous !== newState) {
            previous.session.close()
        }
    }

    private fun connectedState(credentials: SubsonicCredentials) =
        SessionState.Connected(SubsonicSession(credentials, clientFactory(credentials)))

    private suspend fun plexSession(account: PlexAccount): PlexSession {
        val info = PlexClientInfo(settings.plexClientId(), PlexClientInfo.DEFAULT_VERSION)
        return PlexSession(account, plexClientFactory(account, info), info)
    }

    private suspend fun jellyfinSession(account: JellyfinAccount): JellyfinSession {
        val info =
            JellyfinClientInfo(settings.jellyfinDeviceId(), JellyfinClientInfo.DEFAULT_VERSION)
        return JellyfinSession(account, jellyfinClientFactory(account, info), info.deviceId)
    }
}
