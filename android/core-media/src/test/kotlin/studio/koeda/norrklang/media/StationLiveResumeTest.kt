package studio.koeda.norrklang.media

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.test.Test
import kotlin.test.assertEquals

class StationLiveResumeTest {

    private class ResumePlayer : FakePlayer() {
        var ready = false
        var seekable = false
        /** Player calls that would go live. */
        val calls = mutableListOf<String>()
        override fun getPlayWhenReady() = ready
        override fun isCurrentMediaItemSeekable() = seekable
        override fun seekToDefaultPosition() {
            calls += "seekToDefaultPosition"
        }
        override fun seekTo(positionMs: Long) {
            calls += "seekTo($positionMs)"
        }
        override fun stop() {
            calls += "stop"
        }
        override fun prepare() {
            calls += "prepare"
        }
    }

    private val player = ResumePlayer().apply {
        state = Player.STATE_READY
        positionMs = 90_000L
    }
    private var clock = 0L
    private val resume = StationLiveResume(player) { clock }

    private val station = item(MediaId.RadioStation("rs-1"))
    private val track = item(MediaId.Track("tr-1"))

    private fun item(id: MediaId) = MediaItem.Builder().setMediaId(id.encode()).build()

    private fun play(item: MediaItem) {
        player.currentItem = item
        player.ready = true
        resume.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
    }

    private fun setPlayWhenReady(value: Boolean) {
        player.ready = value
        resume.onPlayWhenReadyChanged(value, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
    }

    @Test
    fun `play after a long pause on a station seeks live`() {
        play(station)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        setPlayWhenReady(true)

        assertEquals(listOf("seekTo(0)"), player.calls)
    }

    @Test
    fun `a short pause resumes in place`() {
        play(station)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS - 1
        setPlayWhenReady(true)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `a track resumes where it was paused`() {
        play(track)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS * 10
        setPlayWhenReady(true)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `a station restored paused and never played reopens its stream`() {
        player.currentItem = station
        player.ready = false
        player.positionMs = 0L
        resume.onMediaItemTransition(station, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        setPlayWhenReady(true)

        assertEquals(listOf("stop", "prepare"), player.calls)
    }

    @Test
    fun `a seekable station, HLS, goes to its live edge`() {
        player.seekable = true
        play(station)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        setPlayWhenReady(true)

        assertEquals(listOf("seekToDefaultPosition"), player.calls)
    }

    @Test
    fun `a station skipped to while paused is a fresh connection`() {
        play(station)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        val other = item(MediaId.RadioStation("rs-2"))
        player.currentItem = other
        resume.onMediaItemTransition(other, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        setPlayWhenReady(true)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `an idle player is left to recovery`() {
        play(station)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        player.state = Player.STATE_IDLE
        setPlayWhenReady(true)

        assertEquals(emptyList(), player.calls)
    }

    @Test
    fun `a pause on a track then a station does not seek on play`() {
        play(track)
        setPlayWhenReady(false)
        clock += StationLiveResume.RESUME_LIVE_AFTER_MS
        player.currentItem = station
        resume.onMediaItemTransition(station, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        setPlayWhenReady(true)

        assertEquals(emptyList(), player.calls)
    }
}
