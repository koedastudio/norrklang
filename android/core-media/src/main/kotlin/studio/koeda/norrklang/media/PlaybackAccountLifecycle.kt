package studio.koeda.norrklang.media

import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import studio.koeda.norrklang.data.session.ProviderSession
import studio.koeda.norrklang.data.session.SessionManager.SessionState

/**
 * Only one account may own the player. Account changes cancel and join the old
 * work before stopping its audio, clearing its queue and starting new work.
 * Called on the player's application thread.
 */
internal suspend fun followPlaybackAccounts(
    player: Player,
    states: Flow<SessionState>,
    signedOut: () -> Unit,
    connected: suspend CoroutineScope.(ProviderSession) -> Unit,
) {
    states.filter { it !is SessionState.Initializing }.collectLatest { state ->
        // stop() alone retains playWhenReady; restored queues must stay paused.
        player.pause()
        player.stop()
        player.clearMediaItems()
        when (state) {
            is SessionState.SignedOut -> signedOut()
            is SessionState.Connected -> coroutineScope { connected(state.session) }
            is SessionState.Initializing -> Unit
        }
    }
}
