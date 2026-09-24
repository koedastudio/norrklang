package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class RadioPlayCounterTest {

    private class RadioPlayer : FakePlayer() {
        var playing = false
        override fun isPlaying(): Boolean = playing
    }

    private fun station(id: String) =
        MediaItem.Builder().setMediaId(MediaId.RadioStation(id).encode()).build()

    private fun track(id: String) =
        MediaItem.Builder().setMediaId(MediaId.Track(id).encode()).build()

    @Test
    fun `a station counts once after the listen threshold`() = runTest {
        val player = RadioPlayer()
        val recorded = mutableListOf<String>()
        var notified = 0
        val counter = RadioPlayCounter(this, player, { recorded += it }, { notified++ })

        player.currentItem = station("rs-1")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS - 1)
        runCurrent()
        assertEquals(emptyList(), recorded)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("rs-1"), recorded)
        assertEquals(1, notified)

        // A pause/resume on the same visit doesn't count again.
        player.playing = false
        counter.onIsPlayingChanged(false)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS + 1)
        runCurrent()
        assertEquals(listOf("rs-1"), recorded)
    }

    @Test
    fun `a re-issued item with the same media id is the same visit`() = runTest {
        val player = RadioPlayer()
        val recorded = mutableListOf<String>()
        val counter = RadioPlayCounter(this, player, { recorded += it })

        player.currentItem = station("rs-1")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS / 2)
        // The ICY title update re-issues the item (RadioNowPlayingListener).
        counter.onMediaItemTransition(station("rs-1"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS / 2 + 1)
        runCurrent()
        assertEquals(listOf("rs-1"), recorded)

        counter.onMediaItemTransition(station("rs-1"), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS + 1)
        runCurrent()
        assertEquals(listOf("rs-1"), recorded)
    }

    @Test
    fun `flipping stations before the threshold counts nothing`() = runTest {
        val player = RadioPlayer()
        val recorded = mutableListOf<String>()
        val counter = RadioPlayCounter(this, player, { recorded += it })

        player.currentItem = station("rs-1")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS / 2)

        // Already playing when the swap lands: the new visit arms from the transition.
        player.currentItem = station("rs-2")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS / 2 + 1)
        runCurrent()
        assertEquals(emptyList(), recorded)

        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS / 2)
        runCurrent()
        assertEquals(listOf("rs-2"), recorded)
    }

    @Test
    fun `pausing before the threshold resets the timer`() = runTest {
        val player = RadioPlayer()
        val recorded = mutableListOf<String>()
        val counter = RadioPlayCounter(this, player, { recorded += it })

        player.currentItem = station("rs-1")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS - 1)
        player.playing = false
        counter.onIsPlayingChanged(false)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS)
        runCurrent()
        assertEquals(emptyList(), recorded)
    }

    @Test
    fun `tracks are not counted`() = runTest {
        val player = RadioPlayer()
        val recorded = mutableListOf<String>()
        val counter = RadioPlayCounter(this, player, { recorded += it })

        player.currentItem = track("tr-1")
        counter.onMediaItemTransition(player.currentItem, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        player.playing = true
        counter.onIsPlayingChanged(true)
        advanceTimeBy(RadioPlayCounter.LISTEN_THRESHOLD_MS + 1)
        runCurrent()
        assertEquals(emptyList(), recorded)
    }
}
