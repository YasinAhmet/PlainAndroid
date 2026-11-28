package app.plain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture-intent extras, the storage limit for videos, and the preview swipe. */
class LimitsTest {
    private val mb = 1L shl 20

    @Test fun recordLimitFromStorage() {
        // free space unknown: no limit of our own, the caller's when it has one
        assertEquals(Long.MAX_VALUE, Sizes.recordLimit(0, Long.MAX_VALUE, Sizes.VIDEO_RESERVE))
        assertEquals(10 * mb, Sizes.recordLimit(10 * mb, Long.MAX_VALUE, Sizes.VIDEO_RESERVE))
        // the free space less the reserve
        assertEquals(36 * mb, Sizes.recordLimit(0, 100 * mb, 64 * mb))
        // the smaller of the caller's limit and the room left
        assertEquals(5 * mb, Sizes.recordLimit(5 * mb, 1024 * mb, 64 * mb))
        assertEquals(36 * mb, Sizes.recordLimit(500 * mb, 100 * mb, 64 * mb))
        // too little room: don't start
        assertEquals(0L, Sizes.recordLimit(0, 60 * mb, 64 * mb))
        assertEquals(0L, Sizes.recordLimit(0, 66 * mb, 64 * mb))
        assertEquals(0L, Sizes.recordLimit(5 * mb, 0, 64 * mb))
        // a negative caller limit counts as none
        assertEquals(36 * mb, Sizes.recordLimit(-1, 100 * mb, 64 * mb))
    }

    @Test fun durationLimitNeverOverflows() {
        assertEquals(0, Sizes.durationLimitMs(null))
        assertEquals(30_000, Sizes.durationLimitMs(30))
        assertEquals(30_000, Sizes.durationLimitMs(30L))          // some apps pass a long
        assertEquals(45_000, Sizes.durationLimitMs(" 45 "))
        assertEquals(1_000, Sizes.durationLimitMs(1.9))
        assertEquals(0, Sizes.durationLimitMs(-5))
        assertEquals(0, Sizes.durationLimitMs(0))
        assertEquals(0, Sizes.durationLimitMs("soon"))
        assertEquals(0, Sizes.durationLimitMs(Any()))
        // old code: 4_294_968 s * 1000 wrapped to 704 ms; a huge limit is "practically none" instead
        assertEquals(Int.MAX_VALUE, Sizes.durationLimitMs(4_294_968))
        assertEquals(Int.MAX_VALUE, Sizes.durationLimitMs(Int.MAX_VALUE))
        assertEquals(Int.MAX_VALUE, Sizes.durationLimitMs(Long.MAX_VALUE))
        assertEquals(2_147_482_000, Sizes.durationLimitMs(2_147_482))
        for (s in listOf(1, 59, 600, 3600, 86_400, 2_147_483, 2_147_484, 1_000_000_000)) assertTrue(Sizes.durationLimitMs(s) > 0)
    }

    @Test fun sideSwipeOnThePreview() {
        val min = 72f * 2.75f
        assertEquals(-1, Geo.sideSwipe(-300f, 20f, min))   // to the left: VIDEO
        assertEquals(1, Geo.sideSwipe(300f, -20f, min))    // to the right: PHOTO
        assertEquals(0, Geo.sideSwipe(150f, 0f, min))      // too short (a tap that slid)
        assertEquals(0, Geo.sideSwipe(-300f, 250f, min))   // more diagonal than sideways
        assertEquals(0, Geo.sideSwipe(0f, 600f, min))      // straight down
        assertEquals(1, Geo.sideSwipe(min, 0f, min))       // exactly the minimum counts
        assertEquals(0, Geo.sideSwipe(Float.NaN, 0f, min))
    }
}
