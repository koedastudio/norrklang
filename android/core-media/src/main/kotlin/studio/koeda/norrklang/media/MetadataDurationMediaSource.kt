package studio.koeda.norrklang.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.BundledExtractorsAdapter
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaExtractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingSeekMap
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap

/**
 * Declares [androidx.media3.common.MediaMetadata.durationMs] to ExoPlayer
 * wherever a chunked server transcode leaves the duration unknown.
 *
 * A capped tier is a live transcode — no Content-Length, no seek table —
 * and ExoPlayer treats a progressive stream of unknown length *and* unknown
 * duration as LIVE (ProgressiveMediaPeriod: `isLive = !isLengthKnown &&
 * seekMap.getDurationUs() == TIME_UNSET`). Two consequences: the media3
 * legacy bridge strips ACTION_SEEK_TO and the car host hides the seek bar;
 * and a mid-track load error (proxy idle timeout, coverage gap) makes the
 * period play out its buffer and reload from byte 0 — the track restarts
 * (issue #9). The server metadata already knows the duration, so:
 *
 *  - the extractor's [SeekMap] gets it (period level): the period then
 *    classifies the stream as on-demand and resumes a failed load from its
 *    current byte position with a Range request;
 *  - the timeline window gets it too (source level), which keeps the seek
 *    bar even when the period never sees that seek map (ICY streams).
 *
 * Items without a known duration (radio) go through the default factory.
 */
@UnstableApi
internal class MetadataDurationMediaSourceFactory(
    private val dataSourceFactory: DataSource.Factory,
    private val extractorsFactory: ExtractorsFactory,
    private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
) : MediaSource.Factory {

    private val delegate = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
    private var drmSessionManagerProvider: DrmSessionManagerProvider? = null

    override fun getSupportedTypes(): IntArray = delegate.supportedTypes

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory = apply {
        drmSessionManagerProvider = provider
        delegate.setDrmSessionManagerProvider(provider)
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory = apply {
        loadErrorHandlingPolicy = policy
        delegate.setLoadErrorHandlingPolicy(policy)
    }

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val durationMs = mediaItem.mediaMetadata.durationMs
        if (durationMs == null || durationMs <= 0) return delegate.createMediaSource(mediaItem)
        val durationUs = durationMs * 1000
        val extractorFactory = ProgressiveMediaExtractor.Factory {
            MetadataDurationExtractor(BundledExtractorsAdapter(extractorsFactory), durationUs)
        }
        val progressive = ProgressiveMediaSource.Factory(dataSourceFactory, extractorFactory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        drmSessionManagerProvider?.let(progressive::setDrmSessionManagerProvider)
        return MetadataDurationMediaSource(progressive.createMediaSource(mediaItem), durationUs)
    }
}

/**
 * Hands the extractor a [MetadataDurationExtractorOutput]. Forwards every
 * method by hand: `by` delegation skips Java default methods.
 */
@UnstableApi
private class MetadataDurationExtractor(
    private val delegate: ProgressiveMediaExtractor,
    private val durationUs: Long,
) : ProgressiveMediaExtractor {

    override fun init(
        dataReader: DataReader,
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
        position: Long,
        length: Long,
        output: ExtractorOutput,
    ) = delegate.init(
        dataReader,
        uri,
        responseHeaders,
        position,
        length,
        MetadataDurationExtractorOutput(output, durationUs),
    )

    override fun release() = delegate.release()

    override fun disableSeekingOnMp3Streams() = delegate.disableSeekingOnMp3Streams()

    override fun getCurrentInputPosition(): Long = delegate.currentInputPosition

    override fun seek(position: Long, seekTimeUs: Long) = delegate.seek(position, seekTimeUs)

    override fun read(positionHolder: PositionHolder): Int = delegate.read(positionHolder)

    override fun getUnderlyingImplementationName(): String? =
        delegate.underlyingImplementationName
}

/**
 * Fills a seek map's unknown duration from the item metadata; seekability
 * and the byte mapping stay the extractor's own.
 */
@UnstableApi
internal class MetadataDurationExtractorOutput(
    output: ExtractorOutput,
    private val durationUs: Long,
) : ForwardingExtractorOutput(output) {

    override fun seekMap(seekMap: SeekMap) {
        super.seekMap(
            if (seekMap.durationUs == C.TIME_UNSET) {
                MetadataDurationSeekMap(seekMap, durationUs)
            } else {
                seekMap
            },
        )
    }
}

@UnstableApi
private class MetadataDurationSeekMap(
    seekMap: SeekMap,
    private val durationUs: Long,
) : ForwardingSeekMap(seekMap) {

    override fun getDurationUs(): Long = durationUs
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
        }
        window.liveConfiguration = null
        window.isDynamic = false
        return window
    }
}
