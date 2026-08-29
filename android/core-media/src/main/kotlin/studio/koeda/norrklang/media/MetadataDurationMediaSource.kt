package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Fills a prepared window's unknown duration from the item's
 * [androidx.media3.common.MediaMetadata.durationMs] and clears the live flag.
 *
 * A capped tier is a chunked server transcode — no length, no seek table —
 * which ExoPlayer classifies as a LIVE stream (ProgressiveMediaPeriod:
 * `isLive = !isLengthKnown && seekMap.getDurationUs() == TIME_UNSET`). The
 * media3 legacy bridge then reports position unknown and strips ACTION_SEEK_TO
 * for the car host, hiding the whole seek bar. The server metadata already
 * knows the real duration, so declare it: position and scrubbing work again
 * (byte-mapped via the CBR seek map — see the extractor flags in the service).
 */
@UnstableApi
internal class MetadataDurationMediaSourceFactory(
    private val delegate: MediaSource.Factory,
) : MediaSource.Factory {

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply { delegate.setDrmSessionManagerProvider(provider) }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply { delegate.setLoadErrorHandlingPolicy(policy) }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val source = delegate.createMediaSource(mediaItem)
        val durationMs = mediaItem.mediaMetadata.durationMs ?: return source
        if (durationMs <= 0) return source
        return MetadataDurationMediaSource(source, durationMs * 1000)
    }
}

@UnstableApi
private class MetadataDurationMediaSource(
    source: MediaSource,
    private val durationUs: Long,
) : WrappingMediaSource(source) {

    override fun onChildSourceInfoRefreshed(newTimeline: Timeline) {
        refreshSourceInfo(MetadataDurationTimeline(newTimeline, durationUs))
    }
}

@UnstableApi
internal class MetadataDurationTimeline(
    timeline: Timeline,
    private val durationUs: Long,
) : ForwardingTimeline(timeline) {

    override fun getWindow(
        windowIndex: Int,
        window: Timeline.Window,
        defaultPositionProjectionUs: Long,
    ): Timeline.Window {
        timeline.getWindow(windowIndex, window, defaultPositionProjectionUs)
        // Only fill the gap — a window that knows its own duration (direct
        // play, with the server's Content-Length) is the better truth.
        if (window.durationUs == C.TIME_UNSET) {
            window.durationUs = durationUs
            window.liveConfiguration = null
            window.isDynamic = false
        }
        return window
    }
}
