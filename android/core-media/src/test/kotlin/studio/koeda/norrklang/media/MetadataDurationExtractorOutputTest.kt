package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.extractor.ConstantBitrateSeekMap
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetadataDurationExtractorOutputTest {

    private class RecordingOutput : ExtractorOutput {
        var seekMap: SeekMap? = null
        override fun track(id: Int, type: Int): TrackOutput = error("unused")
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private val downstream = RecordingOutput()
    private val output = MetadataDurationExtractorOutput(downstream, durationUs = 215_000_000L)

    /**
     * What Mp3Extractor emits for a chunked CBR transcode under
     * FLAG_ENABLE_CONSTANT_BITRATE_SEEKING_ALWAYS: seekable, no length,
     * hence no duration.
     */
    private fun chunkedTranscodeSeekMap(): SeekMap = ConstantBitrateSeekMap(
        /* inputLength = */ C.LENGTH_UNSET.toLong(),
        /* firstFrameBytePosition = */ 0L,
        /* bitrate = */ 320_000,
        /* frameSize = */ 1044,
        /* allowSeeksIfLengthUnknown = */ true,
    )

    @Test
    fun `fills an unknown duration and keeps byte-mapped seeking`() {
        val original = chunkedTranscodeSeekMap()

        output.seekMap(original)

        val forwarded = assertNotNull(downstream.seekMap)
        // ProgressiveMediaPeriod's on-demand test is exactly this: a known
        // duration makes a load error resume from the current byte position
        // instead of reloading from zero.
        assertEquals(215_000_000L, forwarded.durationUs)
        assertTrue(forwarded.isSeekable)
        assertEquals(original.getSeekPoints(100_000_000L), forwarded.getSeekPoints(100_000_000L))
    }

    @Test
    fun `leaves a seek map that knows its own duration untouched`() {
        // Direct play: Content-Length known, so the CBR map has a duration.
        val original = ConstantBitrateSeekMap(
            /* inputLength = */ 7_200_000L,
            /* firstFrameBytePosition = */ 0L,
            /* bitrate = */ 320_000,
            /* frameSize = */ 1044,
        )

        output.seekMap(original)

        assertSame(original, downstream.seekMap)
    }

    @Test
    fun `gives an unseekable stream its duration without advertising seeking`() {
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))

        val forwarded = assertNotNull(downstream.seekMap)
        assertEquals(215_000_000L, forwarded.durationUs)
        assertFalse(forwarded.isSeekable)
    }
}
