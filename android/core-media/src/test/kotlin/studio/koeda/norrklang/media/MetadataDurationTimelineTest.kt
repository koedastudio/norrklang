package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetadataDurationTimelineTest {

    /** Emits one window shaped like the given source (duration + liveness). */
    private class FakeTimeline(
        private val durationUs: Long,
        private val live: Boolean,
        private val seekable: Boolean = true,
    ) : Timeline() {
        override fun getWindowCount() = 1
        override fun getWindow(
            windowIndex: Int,
            window: Window,
            defaultPositionProjectionUs: Long,
        ): Window {
            window.durationUs = durationUs
            window.liveConfiguration = if (live) MediaItem.LiveConfiguration.UNSET else null
            window.isDynamic = live
            window.isSeekable = seekable
            return window
        }

        override fun getPeriodCount() = 1
        override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean) = period
        override fun getIndexOfPeriod(uid: Any) = 0
        override fun getUidOfPeriod(periodIndex: Int): Any = "uid"
    }

    @Test
    fun `fills an unknown duration and clears the live flag`() {
        // The chunked-transcode shape: no duration, classified live.
        val timeline = MetadataDurationTimeline(
            FakeTimeline(C.TIME_UNSET, live = true),
            durationUs = 215_000_000L,
        )

        val window = timeline.getWindow(0, Timeline.Window())

        assertEquals(215_000_000L, window.durationUs)
        assertNull(window.liveConfiguration)
        assertFalse(window.isDynamic)
        assertFalse(window.isLive())
    }

    @Test
    fun `leaves a window that knows its own duration untouched`() {
        val timeline = MetadataDurationTimeline(
            FakeTimeline(180_000_000L, live = false),
            durationUs = 215_000_000L,
        )

        assertEquals(180_000_000L, timeline.getWindow(0, Timeline.Window()).durationUs)
    }

    @Test
    fun `clears the live flag even when the source already knows its duration`() {
        val timeline = MetadataDurationTimeline(
            FakeTimeline(214_980_000L, live = true),
            durationUs = 215_000_000L,
        )

        val window = timeline.getWindow(0, Timeline.Window())

        assertEquals(214_980_000L, window.durationUs)
        assertNull(window.liveConfiguration)
        assertFalse(window.isLive())
        assertFalse(window.isDynamic)
        assertTrue(window.isSeekable)
    }

    @Test
    fun `stays on demand when buffering completes and on subsequent refreshes`() {
        // ProgressiveMediaPeriod reports a duration at EOF but retains its
        // original live classification. The wrapper receives a new timeline
        // on each refresh, including after a seek or retry.
        val window = Timeline.Window()
        for (sourceDurationUs in listOf(C.TIME_UNSET, 214_980_000L, C.TIME_UNSET)) {
            val timeline = MetadataDurationTimeline(
                FakeTimeline(sourceDurationUs, live = true),
                durationUs = 215_000_000L,
            )

            timeline.getWindow(0, window)

            assertEquals(
                if (sourceDurationUs == C.TIME_UNSET) 215_000_000L else sourceDurationUs,
                window.durationUs,
            )
            assertFalse(window.isLive())
            assertFalse(window.isDynamic)
            assertTrue(window.isSeekable)
        }
    }

    @Test
    fun `does not advertise seeking if the underlying source cannot seek`() {
        val timeline = MetadataDurationTimeline(
            FakeTimeline(C.TIME_UNSET, live = true, seekable = false),
            durationUs = 215_000_000L,
        )

        assertFalse(timeline.getWindow(0, Timeline.Window()).isSeekable)
    }
}
