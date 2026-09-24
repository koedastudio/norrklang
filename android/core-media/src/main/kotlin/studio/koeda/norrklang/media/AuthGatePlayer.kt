package studio.koeda.norrklang.media

import androidx.media3.common.AudioAttributes
import androidx.media3.common.DeviceInfo
import androidx.media3.common.FlagSet
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Wraps the real player and shapes the error state the car sees: while
 * signed out it reports an auth [PlaybackException], and any real player
 * error passes through [presentError] (see [RadioErrors]) both in
 * [getPlayerError] and in listener callbacks.
 *
 * The car hosts are legacy `MediaControllerCompat` clients, and the player's
 * own error is the only channel Media3 persistently replicates into legacy
 * `PlaybackStateCompat` — session-level APIs (`sendError`,
 * `setPlaybackException`) are transient or Media3-controller-only and never
 * stick in the car UI. The player-level error makes the car render the
 * lasting "sign in" view (resolution intent in the exception's extras).
 */
@UnstableApi
internal class AuthGatePlayer(
    player: Player,
    private val presentError: (PlaybackException) -> PlaybackException = { it },
) : androidx.media3.common.ForwardingPlayer(player) {

    private val listeners = CopyOnWriteArrayList<PresentingListener>()
    private var authError: PlaybackException? = null

    override fun addListener(listener: Player.Listener) {
        val presenting = PresentingListener(listener)
        listeners.add(presenting)
        super.addListener(presenting)
    }

    override fun removeListener(listener: Player.Listener) {
        val presenting = listeners.firstOrNull { it.delegate === listener } ?: return
        listeners.remove(presenting)
        super.removeListener(presenting)
    }

    override fun getPlayerError(): PlaybackException? =
        authError ?: super.getPlayerError()?.let(presentError)

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
            listener.delegate.onPlayerErrorChanged(playerError)
            playerError?.let(listener.delegate::onPlayerError)
            listener.delegate.onPlaybackStateChanged(playbackState)
            listener.delegate.onEvents(this, events)
        }
    }

    /**
     * Hands [delegate] every callback unchanged except the presented errors.
     * Every method is forwarded by hand: Kotlin's `by` delegation skips the
     * interface's Java default methods, which silently drops the session's
     * state and metadata updates.
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    private inner class PresentingListener(val delegate: Player.Listener) : Player.Listener {
        override fun onPlayerError(error: PlaybackException) = delegate.onPlayerError(presentError(error))
        override fun onPlayerErrorChanged(error: PlaybackException?) =
            delegate.onPlayerErrorChanged(error?.let(presentError))

        override fun onEvents(player: Player, events: Player.Events) = delegate.onEvents(player, events)
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = delegate.onTimelineChanged(timeline, reason)
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
            delegate.onMediaItemTransition(mediaItem, reason)
        override fun onTracksChanged(tracks: Tracks) = delegate.onTracksChanged(tracks)
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) =
            delegate.onMediaMetadataChanged(mediaMetadata)
        override fun onPlaylistMetadataChanged(mediaMetadata: MediaMetadata) =
            delegate.onPlaylistMetadataChanged(mediaMetadata)
        override fun onIsLoadingChanged(isLoading: Boolean) = delegate.onIsLoadingChanged(isLoading)
        override fun onLoadingChanged(isLoading: Boolean) = delegate.onLoadingChanged(isLoading)
        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
            delegate.onAvailableCommandsChanged(availableCommands)
        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) =
            delegate.onTrackSelectionParametersChanged(parameters)
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) =
            delegate.onPlayerStateChanged(playWhenReady, playbackState)
        override fun onPlaybackStateChanged(playbackState: Int) = delegate.onPlaybackStateChanged(playbackState)
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
            delegate.onPlayWhenReadyChanged(playWhenReady, reason)
        override fun onPlaybackSuppressionReasonChanged(reason: Int) =
            delegate.onPlaybackSuppressionReasonChanged(reason)
        override fun onIsPlayingChanged(isPlaying: Boolean) = delegate.onIsPlayingChanged(isPlaying)
        override fun onRepeatModeChanged(repeatMode: Int) = delegate.onRepeatModeChanged(repeatMode)
        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) =
            delegate.onShuffleModeEnabledChanged(shuffleModeEnabled)
        override fun onPositionDiscontinuity(reason: Int) = delegate.onPositionDiscontinuity(reason)
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) = delegate.onPositionDiscontinuity(oldPosition, newPosition, reason)
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) =
            delegate.onPlaybackParametersChanged(playbackParameters)
        override fun onSeekBackIncrementChanged(seekBackIncrementMs: Long) =
            delegate.onSeekBackIncrementChanged(seekBackIncrementMs)
        override fun onSeekForwardIncrementChanged(seekForwardIncrementMs: Long) =
            delegate.onSeekForwardIncrementChanged(seekForwardIncrementMs)
        override fun onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs: Long) =
            delegate.onMaxSeekToPreviousPositionChanged(maxSeekToPreviousPositionMs)
        override fun onAudioSessionIdChanged(audioSessionId: Int) = delegate.onAudioSessionIdChanged(audioSessionId)
        override fun onAudioAttributesChanged(audioAttributes: AudioAttributes) =
            delegate.onAudioAttributesChanged(audioAttributes)
        override fun onVolumeChanged(volume: Float) = delegate.onVolumeChanged(volume)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) =
            delegate.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onDeviceInfoChanged(deviceInfo: DeviceInfo) = delegate.onDeviceInfoChanged(deviceInfo)
        override fun onDeviceVolumeChanged(volume: Int, muted: Boolean) = delegate.onDeviceVolumeChanged(volume, muted)
        override fun onVideoSizeChanged(videoSize: VideoSize) = delegate.onVideoSizeChanged(videoSize)
        override fun onSurfaceSizeChanged(width: Int, height: Int) = delegate.onSurfaceSizeChanged(width, height)
        override fun onRenderedFirstFrame() = delegate.onRenderedFirstFrame()
        override fun onCues(cues: List<Cue>) = delegate.onCues(cues)
        override fun onCues(cueGroup: CueGroup) = delegate.onCues(cueGroup)
        override fun onMetadata(metadata: Metadata) = delegate.onMetadata(metadata)
    }
}
