package studio.koeda.norrklang.media

import androidx.media3.common.FlagSet
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Wraps the real player and, while signed out, reports an auth
 * [PlaybackException] as the player's error state.
 *
 * The car hosts are legacy `MediaControllerCompat` clients, and the player's
 * own error is the only channel Media3 persistently replicates into legacy
 * `PlaybackStateCompat` — session-level APIs (`sendError`,
 * `setPlaybackException`) are transient or Media3-controller-only and never
 * stick in the car UI. The player-level error makes the car render the
 * lasting "sign in" view (resolution intent in the exception's extras).
 */
@UnstableApi
internal class AuthGatePlayer(player: Player) : androidx.media3.common.ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArraySet<Player.Listener>()
    private var authError: PlaybackException? = null

    override fun addListener(listener: Player.Listener) {
        listeners.add(listener)
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
        super.removeListener(listener)
    }

    override fun getPlayerError(): PlaybackException? = authError ?: super.getPlayerError()

    override fun getPlaybackState(): Int =
        if (authError != null) Player.STATE_IDLE else super.getPlaybackState()

    fun setAuthError(error: PlaybackException?) {
        if (authError == error) return
        authError = error
        val events = Player.Events(
            FlagSet.Builder()
                .addAll(Player.EVENT_PLAYER_ERROR, Player.EVENT_PLAYBACK_STATE_CHANGED)
                .build(),
        )
        for (listener in listeners) {
            listener.onPlayerErrorChanged(playerError)
            playerError?.let(listener::onPlayerError)
            listener.onPlaybackStateChanged(playbackState)
            listener.onEvents(this, events)
        }
    }
}
