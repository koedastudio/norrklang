package studio.koeda.norrklang.data.session

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import studio.koeda.norrklang.data.di.ApplicationScope
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.subsonic.SubsonicClient
import studio.koeda.norrklang.subsonic.SubsonicCredentials
import studio.koeda.norrklang.subsonic.SubsonicException
import studio.koeda.norrklang.subsonic.SubsonicUrlBuilder

/**
 * Owns the signed-in state and the live [SubsonicClient].
 *
 * On process start, stored credentials are restored optimistically (no blocking
 * ping) so the car UI gets a browse tree immediately; auth failures surface
 * per-request and flip the state to [SessionState.SignedOut].
 */
@Singleton
class SessionManager internal constructor(
    private val settings: ServerSettingsRepository,
    private val scope: CoroutineScope,
    private val clientFactory: (SubsonicCredentials) -> SubsonicClient,
) {

    @Inject constructor(
        settings: ServerSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(settings, scope, { SubsonicClient(it) })

    sealed interface SessionState {
        data object Initializing : SessionState
        data object SignedOut : SessionState
        data class Connected(
            val client: SubsonicClient,
            val urlBuilder: SubsonicUrlBuilder,
            val credentials: SubsonicCredentials,
        ) : SessionState
    }

    private val _state = MutableStateFlow<SessionState>(SessionState.Initializing)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    init {
        scope.launch {
            val stored = settings.currentCredentials()
            val restored = if (stored != null) connectedState(stored) else SessionState.SignedOut
            // Only move out of Initializing if nothing else (e.g. a concurrent
            // sign-in) has already resolved the state.
            if (!_state.compareAndSet(SessionState.Initializing, restored)) {
                (restored as? SessionState.Connected)?.client?.close()
            }
        }
    }

    /** Validates against the server with a ping, then persists and connects. */
    suspend fun signIn(url: String, username: String, password: String): Result<Unit> {
        val credentials = try {
            SubsonicCredentials.fromInput(url, username, password)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        }
        val candidate = connectedState(credentials)
        return try {
            candidate.client.ping()
            settings.save(credentials)
            replaceState(candidate)
            Result.success(Unit)
        } catch (e: SubsonicException) {
            candidate.client.close()
            Result.failure(e)
        }
    }

    suspend fun signOut() {
        settings.clear()
        replaceState(SessionState.SignedOut)
    }

    /** Called by the data layer when the server rejects our stored credentials. */
    fun onAuthRejected() {
        if (_state.value is SessionState.Connected) {
            replaceState(SessionState.SignedOut)
        }
    }

    fun connectedOrNull(): SessionState.Connected? = _state.value as? SessionState.Connected

    /** Swaps the state and closes the client the old state owned, if any. */
    private fun replaceState(newState: SessionState) {
        val previous = _state.getAndUpdate { newState }
        if (previous is SessionState.Connected && previous !== newState) {
            previous.client.close()
        }
    }

    private fun connectedState(credentials: SubsonicCredentials) =
        SessionState.Connected(
            client = clientFactory(credentials),
            urlBuilder = SubsonicUrlBuilder(credentials),
            credentials = credentials,
        )
}
