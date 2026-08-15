package studio.koeda.norrklang.media

import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import studio.koeda.norrklang.data.repo.PlayState
import studio.koeda.norrklang.data.settings.CredentialCipher
import studio.koeda.norrklang.data.settings.ServerSettingsRepository
import studio.koeda.norrklang.data.settings.ServerSettingsRepository.ScrobbleSettings

class PlaybackReporterTest {

    private val settings = ScrobbleSettings(
        enabled = true,
        excludedArtistIds = setOf("ar-bad"),
        excludedPlaylistIds = setOf("pl-sleep"),
    )

    private fun track(container: MediaId.Container? = null) = MediaId.Track("tr-1", container)

    @Test
    fun `disabled blocks every play`() {
        val off = settings.copy(enabled = false)
        assertFalse(off.allowsScrobble(track(), artistId = null))
        assertFalse(off.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-ok"))
    }

    @Test
    fun `excluded artist blocks plays in any context`() {
        assertFalse(settings.allowsScrobble(track(), artistId = "ar-bad"))
        assertFalse(settings.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-bad"))
        assertFalse(settings.allowsScrobble(track(MediaId.Playlist("pl-ok")), artistId = "ar-bad"))
    }

    @Test
    fun `excluded playlist blocks only plays started from that playlist`() {
        assertFalse(settings.allowsScrobble(track(MediaId.Playlist("pl-sleep")), artistId = "ar-ok"))
        // The same track from its album, another playlist, or with no context
        // still scrobbles — playlist exclusion is contextual.
        assertTrue(settings.allowsScrobble(track(MediaId.Album("al-1")), artistId = "ar-ok"))
        assertTrue(settings.allowsScrobble(track(MediaId.Playlist("pl-ok")), artistId = "ar-ok"))
        assertTrue(settings.allowsScrobble(track(), artistId = "ar-ok"))
    }

    @Test
    fun `unknown artist id is not treated as excluded`() {
        assertTrue(settings.allowsScrobble(track(), artistId = null))
        assertTrue(settings.allowsScrobble(track(MediaId.HomeRandomMix), artistId = null))
    }

    @Test
    fun `defaults allow everything`() {
        val defaults = ScrobbleSettings(enabled = true, emptySet(), emptySet())
        assertTrue(defaults.allowsScrobble(track(MediaId.Playlist("pl-sleep")), artistId = "ar-bad"))
    }
}

// ---------------------------------------------------------------------------
// Report pipeline: scrobbles + play-state reports through the serialized queue
// ---------------------------------------------------------------------------

/**
 * Records every repository-bound report in call order. [callDelayMs] > 0
 * makes each call suspend (virtual time), so ordering tests can prove the
 * queue stays serialized while the repository is slow.
 */
internal class RecordingMusicRepository(
    override val playbackReportIntervalMs: Long?,
    private val callDelayMs: Long = 0L,
) : FakeMusicRepository() {

    val calls = mutableListOf<String>()

    override suspend fun scrobble(trackId: String, submission: Boolean) {
        if (callDelayMs > 0) delay(callDelayMs)
        calls += "scrobble $trackId submission=$submission"
    }

    override suspend fun reportPlayState(
        trackId: String,
        state: PlayState,
        positionMs: Long,
        durationMs: Long?,
    ) {
        if (callDelayMs > 0) delay(callDelayMs)
        calls += "$state $trackId@$positionMs"
    }
}

/**
 * A real [ServerSettingsRepository] (it is final) over an empty in-memory
 * DataStore, so `scrobbleSettings` yields [ScrobbleSettings.DEFAULT] —
 * everything allowed. DataStore is an `implementation` dependency of
 * core-data and thus absent from this module's test compile classpath, so
 * the wiring is reflective; the classes are on the runtime classpath.
 */
internal fun defaultSettingsRepository(): ServerSettingsRepository {
    val dataStoreInterface = Class.forName("androidx.datastore.core.DataStore")
    val emptyPreferences = Class
        .forName("androidx.datastore.preferences.core.PreferencesFactory")
        .getMethod("createEmpty")
        .invoke(null)
    val dataFlow = flowOf(emptyPreferences)
    val dataStore = Proxy.newProxyInstance(
        dataStoreInterface.classLoader,
        arrayOf(dataStoreInterface),
    ) { _, method, _ ->
        if (method.name == "getData") dataFlow else error("unused: ${method.name}")
    }
    val cipher = object : CredentialCipher {
        override fun encrypt(plaintext: String): String = error("unused")
        override fun decrypt(stored: String): String? = error("unused")
        override fun isEncrypted(stored: String): Boolean = error("unused")
    }
    return ServerSettingsRepository::class.java
        .getDeclaredConstructor(dataStoreInterface, CredentialCipher::class.java)
        .newInstance(dataStore, cipher)
}

/**
 * The three members [PlaybackReporter] reads are settable; everything else
 * fails as "unused" (same convention as [FakeMusicRepository]).
 */
@OptIn(UnstableApi::class)
internal class FakePlayer : Player {

    var currentItem: MediaItem? = null
    var state: Int = Player.STATE_IDLE
    var positionMs: Long = 0L

    override fun getCurrentMediaItem(): MediaItem? = currentItem
    override fun getPlaybackState(): Int = state
    override fun getCurrentPosition(): Long = positionMs

    override fun getApplicationLooper(): Looper = error("unused")
    override fun addListener(listener: Player.Listener) = error("unused")
    override fun removeListener(listener: Player.Listener) = error("unused")
    override fun setMediaItems(mediaItems: MutableList<MediaItem>) = error("unused")
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) =
        error("unused")
    override fun setMediaItems(
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ) = error("unused")
    override fun setMediaItem(mediaItem: MediaItem) = error("unused")
    override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) = error("unused")
    override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) = error("unused")
    override fun addMediaItem(mediaItem: MediaItem) = error("unused")
    override fun addMediaItem(index: Int, mediaItem: MediaItem) = error("unused")
    override fun addMediaItems(mediaItems: MutableList<MediaItem>) = error("unused")
    override fun addMediaItems(index: Int, mediaItems: MutableList<MediaItem>) = error("unused")
    override fun moveMediaItem(currentIndex: Int, newIndex: Int) = error("unused")
    override fun moveMediaItems(fromIndex: Int, toIndex: Int, newIndex: Int) = error("unused")
    override fun replaceMediaItem(index: Int, mediaItem: MediaItem) = error("unused")
    override fun replaceMediaItems(
        fromIndex: Int,
        toIndex: Int,
        mediaItems: MutableList<MediaItem>,
    ) = error("unused")
    override fun removeMediaItem(index: Int) = error("unused")
    override fun removeMediaItems(fromIndex: Int, toIndex: Int) = error("unused")
    override fun clearMediaItems() = error("unused")
    override fun isCommandAvailable(command: Int): Boolean = error("unused")
    override fun canAdvertiseSession(): Boolean = error("unused")
    override fun getAvailableCommands(): Player.Commands = error("unused")
    override fun prepare() = error("unused")
    override fun getPlaybackSuppressionReason(): Int = error("unused")
    override fun isPlaying(): Boolean = error("unused")
    override fun getPlayerError(): PlaybackException? = error("unused")
    override fun play() = error("unused")
    override fun pause() = error("unused")
    override fun setPlayWhenReady(playWhenReady: Boolean) = error("unused")
    override fun getPlayWhenReady(): Boolean = error("unused")
    override fun setRepeatMode(repeatMode: Int) = error("unused")
    override fun getRepeatMode(): Int = error("unused")
    override fun setShuffleModeEnabled(shuffleModeEnabled: Boolean) = error("unused")
    override fun getShuffleModeEnabled(): Boolean = error("unused")
    override fun isLoading(): Boolean = error("unused")
    override fun seekToDefaultPosition() = error("unused")
    override fun seekToDefaultPosition(mediaItemIndex: Int) = error("unused")
    override fun seekTo(positionMs: Long) = error("unused")
    override fun seekTo(mediaItemIndex: Int, positionMs: Long) = error("unused")
    override fun getSeekBackIncrement(): Long = error("unused")
    override fun seekBack() = error("unused")
    override fun getSeekForwardIncrement(): Long = error("unused")
    override fun seekForward() = error("unused")
    override fun hasPreviousMediaItem(): Boolean = error("unused")
    override fun seekToPreviousMediaItem() = error("unused")
    override fun getMaxSeekToPreviousPosition(): Long = error("unused")
    override fun seekToPrevious() = error("unused")
    override fun hasNextMediaItem(): Boolean = error("unused")
    override fun seekToNextMediaItem() = error("unused")
    override fun seekToNext() = error("unused")
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = error("unused")
    override fun setPlaybackSpeed(speed: Float) = error("unused")
    override fun getPlaybackParameters(): PlaybackParameters = error("unused")
    override fun stop() = error("unused")
    override fun release() = error("unused")
    override fun getCurrentTracks(): Tracks = error("unused")
    override fun getTrackSelectionParameters(): TrackSelectionParameters = error("unused")
    override fun setTrackSelectionParameters(parameters: TrackSelectionParameters) =
        error("unused")
    override fun getMediaMetadata(): MediaMetadata = error("unused")
    override fun getPlaylistMetadata(): MediaMetadata = error("unused")
    override fun setPlaylistMetadata(mediaMetadata: MediaMetadata) = error("unused")
    override fun getCurrentManifest(): Any? = error("unused")
    override fun getCurrentTimeline(): Timeline = error("unused")
    override fun getCurrentPeriodIndex(): Int = error("unused")
    @Deprecated("unused")
    override fun getCurrentWindowIndex(): Int = error("unused")
    override fun getCurrentMediaItemIndex(): Int = error("unused")
    @Deprecated("unused")
    override fun getNextWindowIndex(): Int = error("unused")
    override fun getNextMediaItemIndex(): Int = error("unused")
    @Deprecated("unused")
    override fun getPreviousWindowIndex(): Int = error("unused")
    override fun getPreviousMediaItemIndex(): Int = error("unused")
    override fun getMediaItemCount(): Int = error("unused")
    override fun getMediaItemAt(index: Int): MediaItem = error("unused")
    override fun getDuration(): Long = error("unused")
    override fun getBufferedPosition(): Long = error("unused")
    override fun getBufferedPercentage(): Int = error("unused")
    override fun getTotalBufferedDuration(): Long = error("unused")
    @Deprecated("unused")
    override fun isCurrentWindowDynamic(): Boolean = error("unused")
    override fun isCurrentMediaItemDynamic(): Boolean = error("unused")
    @Deprecated("unused")
    override fun isCurrentWindowLive(): Boolean = error("unused")
    override fun isCurrentMediaItemLive(): Boolean = error("unused")
    override fun getCurrentLiveOffset(): Long = error("unused")
    @Deprecated("unused")
    override fun isCurrentWindowSeekable(): Boolean = error("unused")
    override fun isCurrentMediaItemSeekable(): Boolean = error("unused")
    override fun isPlayingAd(): Boolean = error("unused")
    override fun getCurrentAdGroupIndex(): Int = error("unused")
    override fun getCurrentAdIndexInAdGroup(): Int = error("unused")
    override fun getContentDuration(): Long = error("unused")
    override fun getContentPosition(): Long = error("unused")
    override fun getContentBufferedPosition(): Long = error("unused")
    override fun getAudioAttributes(): AudioAttributes = error("unused")
    override fun setVolume(volume: Float) = error("unused")
    override fun getVolume(): Float = error("unused")
    override fun mute() = error("unused")
    override fun unmute() = error("unused")
    override fun clearVideoSurface() = error("unused")
    override fun clearVideoSurface(surface: Surface?) = error("unused")
    override fun setVideoSurface(surface: Surface?) = error("unused")
    override fun setVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = error("unused")
    override fun clearVideoSurfaceHolder(surfaceHolder: SurfaceHolder?) = error("unused")
    override fun setVideoSurfaceView(surfaceView: SurfaceView?) = error("unused")
    override fun clearVideoSurfaceView(surfaceView: SurfaceView?) = error("unused")
    override fun setVideoTextureView(textureView: TextureView?) = error("unused")
    override fun clearVideoTextureView(textureView: TextureView?) = error("unused")
    override fun getVideoSize(): VideoSize = error("unused")
    override fun getSurfaceSize(): Size = error("unused")
    override fun getCurrentCues(): CueGroup = error("unused")
    override fun getDeviceInfo(): DeviceInfo = error("unused")
    override fun getDeviceVolume(): Int = error("unused")
    override fun isDeviceMuted(): Boolean = error("unused")
    @Deprecated("unused")
    override fun setDeviceVolume(volume: Int) = error("unused")
    override fun setDeviceVolume(volume: Int, flags: Int) = error("unused")
    @Deprecated("unused")
    override fun increaseDeviceVolume() = error("unused")
    override fun increaseDeviceVolume(flags: Int) = error("unused")
    @Deprecated("unused")
    override fun decreaseDeviceVolume() = error("unused")
    override fun decreaseDeviceVolume(flags: Int) = error("unused")
    @Deprecated("unused")
    override fun setDeviceMuted(muted: Boolean) = error("unused")
    override fun setDeviceMuted(muted: Boolean, flags: Int) = error("unused")
    override fun setAudioAttributes(audioAttributes: AudioAttributes, handleAudioFocus: Boolean) =
        error("unused")
}

@OptIn(UnstableApi::class)
class PlaybackReporterReportingTest {

    private fun trackItem(id: String, durationMs: Long? = null): MediaItem = MediaItem.Builder()
        .setMediaId(MediaId.Track(id).encode())
        .setMediaMetadata(MediaMetadata.Builder().setDurationMs(durationMs).build())
        .build()

    private fun positionInfo(item: MediaItem, positionMs: Long) = Player.PositionInfo(
        /* windowUid = */ null,
        /* mediaItemIndex = */ 0,
        item,
        /* periodUid = */ null,
        /* periodIndex = */ 0,
        positionMs,
        /* contentPositionMs = */ positionMs,
        /* adGroupIndex = */ -1,
        /* adIndexInAdGroup = */ -1,
    )

    /**
     * Reporter under test on [TestScope.backgroundScope]: the queue consumer
     * and the ticker die with the test instead of hanging runTest.
     */
    private fun TestScope.reporter(
        repository: RecordingMusicRepository,
        player: FakePlayer,
    ) = PlaybackReporter(backgroundScope, repository, defaultSettingsRepository(), player)

    // Track ends naturally: STATE_ENDED arrives with no discontinuity.
    @Test
    fun `ended sends one stopped plus a submission and never paused`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = 15_000)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        // 200s track: submission threshold is min(100s, 4min) = 100s.
        player.currentItem = trackItem("tr-a", durationMs = 200_000)
        player.state = Player.STATE_READY
        reporter.onIsPlayingChanged(true)

        player.positionMs = 200_000
        player.state = Player.STATE_ENDED
        reporter.onPlaybackStateChanged(Player.STATE_ENDED)
        // The player also flips isPlaying at the end of the queue — already
        // STOPPED, so no PAUSED on top of it.
        reporter.onIsPlayingChanged(false)
        // A re-fired ENDED must not double-report.
        reporter.onPlaybackStateChanged(Player.STATE_ENDED)
        // advanceUntilIdle() stops when only backgroundScope tasks remain,
        // so the queue consumer is driven with runCurrent/advanceTimeBy.
        runCurrent()

        assertContentEquals(
            listOf(
                "PLAYING tr-a@0",
                "STOPPED tr-a@200000",
                "scrobble tr-a submission=true",
            ),
            repo.calls,
        )
    }

    // Explicit stop(): the timeline entry closes, but an aborted session is
    // not a finished play — no scrobble submission.
    @Test
    fun `idle sends stopped without a scrobble submission`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = 15_000)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        player.currentItem = trackItem("tr-a", durationMs = 200_000)
        player.state = Player.STATE_READY
        reporter.onIsPlayingChanged(true)

        // Played well past the submission threshold, then stop() — still no
        // submission: only ENDED/discontinuity finish a play.
        player.positionMs = 180_000
        player.state = Player.STATE_IDLE
        reporter.onPlaybackStateChanged(Player.STATE_IDLE)
        reporter.onIsPlayingChanged(false)
        runCurrent()

        assertContentEquals(
            listOf("PLAYING tr-a@0", "STOPPED tr-a@180000"),
            repo.calls,
        )
    }

    @Test
    fun `pause at ready sends paused but a buffering dip sends nothing`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = 15_000)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        player.currentItem = trackItem("tr-a", durationMs = 200_000)
        player.state = Player.STATE_READY
        reporter.onIsPlayingChanged(true)

        player.positionMs = 30_000
        reporter.onIsPlayingChanged(false) // genuine pause: still READY

        reporter.onIsPlayingChanged(true)
        player.state = Player.STATE_BUFFERING
        reporter.onIsPlayingChanged(false) // rebuffer dip: not a pause
        runCurrent()

        assertContentEquals(
            listOf("PLAYING tr-a@0", "PAUSED tr-a@30000", "PLAYING tr-a@30000"),
            repo.calls,
        )
    }

    @Test
    fun `ticker repeats playing every interval and dies on pause`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = 15_000)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        player.currentItem = trackItem("tr-a", durationMs = 600_000)
        player.state = Player.STATE_READY
        reporter.onIsPlayingChanged(true)

        // Each tick re-reads the player, so the reported position advances.
        player.positionMs = 15_000
        advanceTimeBy(15_000)
        runCurrent()
        player.positionMs = 30_000
        advanceTimeBy(15_000)
        runCurrent()

        player.positionMs = 42_000
        reporter.onIsPlayingChanged(false) // pause cancels the ticker
        advanceTimeBy(120_000)
        runCurrent()

        assertContentEquals(
            listOf(
                "PLAYING tr-a@0",
                "PLAYING tr-a@15000",
                "PLAYING tr-a@30000",
                "PAUSED tr-a@42000",
            ),
            repo.calls,
        )
    }

    // Subsonic: null interval disables the play-state channel entirely —
    // scrobbles still flow.
    @Test
    fun `null interval sends scrobbles but never a play state report`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = null)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        val item = trackItem("tr-a", durationMs = 200_000)
        player.currentItem = item
        player.state = Player.STATE_READY
        reporter.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        reporter.onIsPlayingChanged(true)
        advanceTimeBy(120_000) // no ticker either
        runCurrent()

        player.positionMs = 30_000
        reporter.onIsPlayingChanged(false) // pause at READY: still nothing
        reporter.onIsPlayingChanged(true)

        player.positionMs = 200_000
        player.state = Player.STATE_ENDED
        reporter.onPlaybackStateChanged(Player.STATE_ENDED)
        reporter.onIsPlayingChanged(false)
        runCurrent()

        assertContentEquals(
            listOf(
                "scrobble tr-a submission=false",
                "scrobble tr-a submission=true",
            ),
            repo.calls,
        )
    }

    // The queue is the ordering guarantee: even with a slow repository, the
    // finished track's STOPPED reaches the server before anything about the
    // next track — a late STOPPED overtaking PLAYING would corrupt on-deck.
    @Test
    fun `reports keep enqueue order when repository calls suspend`() = runTest {
        val repo = RecordingMusicRepository(playbackReportIntervalMs = 15_000, callDelayMs = 100)
        val player = FakePlayer()
        val reporter = reporter(repo, player)

        val itemA = trackItem("tr-a", durationMs = 200_000)
        val itemB = trackItem("tr-b", durationMs = 180_000)
        player.currentItem = itemA
        player.state = Player.STATE_READY
        reporter.onIsPlayingChanged(true)

        // Auto transition A -> B: STOPPED + submission for A, now-playing
        // scrobble for B, then the still-running ticker picks up B.
        reporter.onPositionDiscontinuity(
            positionInfo(itemA, 200_000),
            positionInfo(itemB, 0),
            Player.DISCONTINUITY_REASON_AUTO_TRANSITION,
        )
        player.currentItem = itemB
        player.positionMs = 0
        reporter.onMediaItemTransition(itemB, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        advanceTimeBy(15_000) // first tick after the transition
        runCurrent()
        player.state = Player.STATE_BUFFERING
        reporter.onIsPlayingChanged(false) // stop the ticker (reports nothing)
        advanceTimeBy(1_000) // drain the slow consumer
        runCurrent()

        assertContentEquals(
            listOf(
                "PLAYING tr-a@0",
                "STOPPED tr-a@200000",
                "scrobble tr-a submission=true",
                "scrobble tr-b submission=false",
                "PLAYING tr-b@0",
            ),
            repo.calls,
        )
    }
}
