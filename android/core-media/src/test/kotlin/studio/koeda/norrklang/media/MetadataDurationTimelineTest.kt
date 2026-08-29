package studio.koeda.norrklang.media

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class MetadataDurationTimelineTest {

    /** Emits one window shaped like the given source (duration + liveness). */
    private class FakeTimeline(
        private val durationUs: Long,
        private val live: Boolean,
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
            window.isSeekable = true
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
}
