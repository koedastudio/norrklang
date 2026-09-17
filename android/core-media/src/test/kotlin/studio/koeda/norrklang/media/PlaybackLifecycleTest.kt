package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.session.SessionManager.SessionState

/** Tests the same account/initialization orchestration used by the media service. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@UnstableApi
class PlaybackLifecycleTest {
    private class QueuePlayer : FakePlayer() {
        val events = mutableListOf<String>()
        val listeners = mutableListOf<Player.Listener>()
        var items = listOf<MediaItem>()
        var prepared = false
        var playing = false
        var playRequested = false
        override fun getPlayWhenReady() = playRequested
        override fun pause() {
            events += "pause"
            playing = false
            playRequested = false
        }
        override fun getMediaItemCount() = items.size
        override fun addListener(listener: Player.Listener) { listeners += listener }
        override fun removeListener(listener: Player.Listener) { listeners -= listener }
        override fun stop() {
            events += "stop"
            playing = false
            prepared = false
        }
        override fun clearMediaItems() {
            events += "clear"
            setQueue(emptyList())
        }
        override fun prepare() { prepared = true }
        override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
            setQueue(mediaItems)
            currentItem = items.getOrNull(startIndex)
            positionMs = startPositionMs
        }
        fun setQueue(queue: List<MediaItem>) {
            items = queue
            listeners.toList().forEach {
                it.onTimelineChanged(Timeline.EMPTY, Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
            }
        }
    }

    private fun queue(id: String) = MediaItemsWithStartPosition(
        listOf(MediaItem.Builder().setMediaId(id).build()), 0, 12_000,
    )

    @Test
    fun `offline startup retries home and queue once connectivity returns`() = runTest {
        val player = QueuePlayer()
        var online = false
        var homeLoads = 0
        var queueLoads = 0
        val init = PlaybackInitialization(backgroundScope, player,
            loadQueue = { queueLoads++; if (online) queue("saved") else null },
            refreshHome = { homeLoads++ },
        )
        player.addListener(init)
        init.retry()
        runCurrent()
        assertEquals(0, player.items.size)
        online = true
        init.retry()
        runCurrent()
        assertEquals("saved", player.items.single().mediaId)
        assertEquals(12_000L, player.positionMs)
        assertTrue(player.prepared)
        init.retry()
        runCurrent()
        assertEquals(2, queueLoads)
        assertEquals(3, homeLoads)
    }

    @Test
    fun `a reconnect during an outstanding failed restore schedules another attempt`() = runTest {
        val player = QueuePlayer()
        val failedRequest = CompletableDeferred<Unit>()
        var loads = 0
        val init = PlaybackInitialization(backgroundScope, player,
            loadQueue = { if (++loads == 1) { failedRequest.await(); null } else queue("saved") },
            refreshHome = {},
        )
        player.addListener(init)
        init.retry()
        runCurrent()
        init.retry()
        failedRequest.complete(Unit)
        runCurrent()
        assertEquals("saved", player.items.single().mediaId)
        assertEquals(2, loads)
    }

    @Test
    fun `a manual queue change wins over an outstanding restore even if cleared again`() = runTest {
        val player = QueuePlayer()
        val response = CompletableDeferred<MediaItemsWithStartPosition?>()
        val init = PlaybackInitialization(backgroundScope, player, { response.await() }, {})
        player.addListener(init)
        init.retry()
        runCurrent()
        player.setQueue(queue("chosen").mediaItems)
        player.clearMediaItems()
        response.complete(queue("stale"))
        runCurrent()
        assertTrue(player.items.isEmpty())
        assertFalse(player.prepared)
    }

    @Test
    fun `sign-out cancels account work before stopping and clearing the player`() = runTest {
        val account = FakeProviderSession()
        val states = MutableStateFlow<SessionState>(SessionState.Connected(account))
        val player = QueuePlayer()
        val response = CompletableDeferred<MediaItemsWithStartPosition?>()
        backgroundScope.launch {
            followPlaybackAccounts(player, states, signedOut = { player.events += "signed out" }) {
                val init = PlaybackInitialization(this, player, { response.await() }, {})
                player.addListener(init)
                init.retry()
                try { awaitCancellation() } finally {
                    player.removeListener(init)
                    player.events += "cancel old account"
                }
            }
        }
        runCurrent()
        player.playing = true
        player.playRequested = true
        player.setQueue(queue("old").mediaItems)
        player.events.clear()
        states.value = SessionState.SignedOut
        runCurrent()
        response.complete(queue("late"))
        runCurrent()
        assertEquals(listOf("cancel old account", "pause", "stop", "clear", "signed out"), player.events)
        assertTrue(player.items.isEmpty())
        assertFalse(player.playing)
        assertFalse(player.playRequested)
        assertFalse(player.prepared)
    }

    @Test
    fun `a direct account switch clears the old queue before starting the new account`() = runTest {
        val first = FakeProviderSession()
        val second = FakeProviderSession()
        val states = MutableStateFlow<SessionState>(SessionState.Connected(first))
        val player = QueuePlayer()
        backgroundScope.launch {
            followPlaybackAccounts(player, states, signedOut = {}) { account ->
                assertTrue(player.items.isEmpty())
                player.setQueue(queue(if (account === first) "first" else "second").mediaItems)
                awaitCancellation()
            }
        }
        runCurrent()
        player.playRequested = true
        states.value = SessionState.Connected(second)
        runCurrent()
        assertEquals("second", player.items.single().mediaId)
        assertFalse(player.playRequested)
    }
}
