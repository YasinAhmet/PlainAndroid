package app.plain.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The viewer's up / down fling, and page maths when items vanish under the grid. */
class GesturesTest {
    private val d = 2.75f   // the phone's density

    @Test fun verticalFling() {
        assertEquals(1, Fling.vertical(400f, 100f, 3000f, d))      // down: close
        assertEquals(-1, Fling.vertical(-400f, -100f, -3000f, d))  // up: details
        assertEquals(1, Fling.vertical(400f, 0f, 3000f, 1f))
    }

    @Test fun notAVerticalFling() {
        assertEquals(0, Fling.vertical(100f, 0f, 3000f, d))        // too short (under 96 dp)
        assertEquals(0, Fling.vertical(400f, 0f, 1000f, d))        // too slow (under 700 dp/s)
        assertEquals(0, Fling.vertical(400f, 2500f, 3000f, d))     // diagonal: sideways is next / previous
        assertEquals(0, Fling.vertical(400f, 0f, -3000f, d))       // speed against the stroke (end of a pan)
        assertEquals(0, Fling.vertical(0f, 0f, 3000f, d))          // no start point known
        assertEquals(0, Fling.vertical(0f, 0f, 0f, d))
    }

    @Test fun pagesStayInRangeWhileItemsVanish() {
        // the grid clamps its page after every reload / delete; whatever the counts, the clamped page's range
        // must lie inside the list and only the empty list shows an empty page
        val rnd = Random(7)
        repeat(20_000) {
            val per = rnd.nextInt(1, 61)
            var total = rnd.nextInt(0, 5000)
            var page = rnd.nextInt(0, 200)
            repeat(3) {
                val pages = Paging.pageCount(total, per)
                page = page.coerceIn(0, pages - 1)
                val r = Paging.range(page, per, total)
                assertTrue(r.first >= 0 && r.last < total || r.isEmpty())
                assertEquals(total == 0, r.isEmpty())
                total = (total - rnd.nextInt(0, 3 * per)).coerceAtLeast(0)   // a bulk delete
            }
        }
    }

    @Test fun hugeImagesDecodeAtScreenSize() {
        assertEquals(2436 to 1827, Paging.fitSize(5000, 3750, 2436))
        assertEquals(1827 to 2436, Paging.fitSize(3750, 5000, 2436))
        assertEquals(4096 to 3072, Paging.fitSize(12000, 9000, 4096))    // 108 MP zoomed in: 50 MB, not 108 MB
        assertEquals(1000 to 800, Paging.fitSize(1000, 800, 2436))       // never scaled up
        assertEquals(2436 to 2436, Paging.fitSize(2436, 2436, 2436))
        assertEquals(2436 to 1, Paging.fitSize(100_000, 1, 2436))       // a sliver keeps 1 px
        assertEquals(0 to 0, Paging.fitSize(0, 0, 2436))                 // unknown size: left to the decoder
        val rnd = Random(3)
        repeat(10_000) {
            val w = rnd.nextInt(1, 30_000); val h = rnd.nextInt(1, 30_000); val m = rnd.nextInt(256, 5000)
            val (a, b) = Paging.fitSize(w, h, m)
            assertTrue(maxOf(a, b) <= maxOf(m, maxOf(w, h).coerceAtMost(m)) && a >= 1 && b >= 1)
            if (maxOf(w, h) > m) assertEquals(m, maxOf(a, b))
        }
    }

    @Test fun pageOfKeepsFirstItemOnColumnChange() {
        // pinching from 4 to 6 columns: the first visible item stays on the page shown
        for (first in listOf(0, 23, 24, 25, 999, 10_000)) for (per in listOf(18, 24, 36, 48)) {
            val p = Paging.pageOf(first, per)
            assertTrue(first in Paging.range(p, per, first + 1))
            assertEquals(0, Paging.pageOf(first, 0))
        }
    }
}
