package studio.koeda.norrklang.media

import android.content.ContextWrapper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

@OptIn(UnstableApi::class)
class PlaybackRecoveryListenerTest {

    private class ErrorPlayer(var error: PlaybackException?) : FakePlayer() {
        var prepares = 0
        override fun getPlayerError(): PlaybackException? = error
        override fun prepare() {
            prepares++
        }
    }

    private val notFound =
        PlaybackException(null, null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)

    @Test
    fun `skipping away from a failed item prepares the player`() = runTest {
        val player = ErrorPlayer(notFound)
        // Stubbed framework (returnDefaultValues): no ConnectivityManager.
        val listener = PlaybackRecoveryListener(ContextWrapper(null), this, player)

        listener.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(1, player.prepares)
    }

    @Test
    fun `other transitions and healthy skips are left alone`() = runTest {
        val failed = ErrorPlayer(notFound)
        PlaybackRecoveryListener(ContextWrapper(null), this, failed)
            .onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        val healthy = ErrorPlayer(null)
        PlaybackRecoveryListener(ContextWrapper(null), this, healthy)
            .onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)

        assertEquals(0, failed.prepares)
        assertEquals(0, healthy.prepares)
    }

    private val connectionFailed =
        PlaybackException(null, null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)

    @Test
    fun `a returning network retries even after the backoff attempts ran out`() = runTest {
        val player = ErrorPlayer(connectionFailed)
        val listener = PlaybackRecoveryListener(ContextWrapper(null), this, player)
        repeat(8) {
            listener.onPlayerError(connectionFailed)
            testScheduler.advanceUntilIdle()
        }
        val exhausted = player.prepares

        listener.onPlayerError(connectionFailed)
        testScheduler.advanceUntilIdle()
        assertEquals(exhausted, player.prepares)

        listener.networkReturned()
        assertEquals(exhausted + 1, player.prepares)

        // The backoff starts over.
        listener.onPlayerError(connectionFailed)
        testScheduler.advanceUntilIdle()
        assertEquals(exhausted + 2, player.prepares)
    }

    @Test
    fun `a returning network leaves a healthy player alone`() = runTest {
        val player = ErrorPlayer(null)

        PlaybackRecoveryListener(ContextWrapper(null), this, player).networkReturned()

        assertEquals(0, player.prepares)
    }
}
