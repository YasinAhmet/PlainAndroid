package app.plain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WhenTest {
    /** Saturday 28 Nov 2026, 14:00 local time. */
    private val now = cal(2026, Calendar.NOVEMBER, 28, 14, 0)

    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    private fun at(text: String) = When.find(text, now)?.at

    @Test fun timers() {
        assertEquals(now + 20 * 60_000L, at("call back in 20 min"))
        assertEquals(now + 10 * 60_000L, at("makarna 10 dk"))
        assertEquals(now + 90 * 60_000L, at("1h 30m"))
        assertEquals(now + 2 * 3_600_000L, at("2 saat sonra çamaşır"))
        assertEquals(now + 30 * 60_000L, at("yarım saat sonra ara"))
        assertEquals(now + 60 * 60_000L, at("in an hour"))
        assertEquals(now + 45_000L, at("tea 45 sec"))
        assertEquals(now + 75 * 60_000L, at("1 saat 15 dk"))
        assertTrue(When.find("in 5 min", now)!!.timer)
    }

    @Test fun clockTimes() {
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 18, 30), at("meeting at 18:30"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 15), at("9:15 dentist"))            // passed today: tomorrow
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 17, 0), at("at 5"))                    // 5 at 14:00 = 17:00
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 19, 0), at("7pm"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 16, 0), at("saat 16"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 19, 0), at("akşam 7 yemek"))
        assertFalse(When.find("at 18:30", now)!!.timer)
    }

    @Test fun days() {
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("tomorrow"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("yarın sabah"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 14, 0), at("tomorrow 14"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 18, 30), at("YARIN 18:30 fatura"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 20, 0), at("tonight"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 20, 0), at("bu akşam"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 30, 9, 0), at("öbür gün"))
        assertEquals(cal(2026, Calendar.DECEMBER, 4, 10, 0), at("friday 10"))
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 9, 0), at("salı"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("saturday"))                // next week, not today
    }

    @Test fun dates() {
        assertEquals(cal(2026, Calendar.DECEMBER, 3, 10, 0), at("3 Dec 10:00"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("5 aralık"))
        assertEquals(cal(2026, Calendar.DECEMBER, 24, 18, 0), at("dec 24 at 6pm"))
        assertEquals(cal(2027, Calendar.JANUARY, 2, 9, 0), at("2/1"))
        assertEquals(cal(2027, Calendar.NOVEMBER, 1, 9, 0), at("1 november"))              // passed this year
    }

    @Test fun nothing() {
        assertNull(at("buy 2 kg apples"))
        assertNull(at("milk, eggs, bread"))
        assertNull(at("room 12"))
        assertNull(at("version 1.5"))
        assertNull(at("may I ask"))
        assertNull(at("today"))   // a day with no time and it's already today: nothing to remind
    }

    @Test fun labels() {
        assertEquals("in 25 min", When.left(now + 25 * 60_000L, now))
        assertEquals("in 2 h 5 min", When.left(now + 125 * 60_000L, now))
        assertEquals("5 min ago", When.left(now - 5 * 60_000L, now))
        assertEquals("now", When.left(now + 10_000L, now))
        assertEquals("0:12", When.duration(12_400))
        assertTrue(When.label(cal(2026, Calendar.NOVEMBER, 29, 9, 0), now).startsWith("tomorrow"))
    }
}
