package app.plain.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Random
import java.util.TimeZone

class WhenTest {
    /** Saturday 28 Nov 2026, 14:00 local time. */
    private val now = cal(2026, Calendar.NOVEMBER, 28, 14, 0)

    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    private fun at(text: String, from: Long = now) = When.find(text, from)?.at

    private fun minutes(text: String) = at(text)?.let { (it - now) / 60_000.0 }

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

    @Test fun turkishCaseEndingsOnUnits() {
        assertEquals(10.0, minutes("10 dakikaya geliyorum"))
        assertEquals(10.0, minutes("10 dakikada biter"))
        assertEquals(120.0, minutes("2 saatte hazır"))
        assertEquals(120.0, minutes("2 saate kadar"))
        assertEquals(0.5, minutes("30 sn'de"))
        assertEquals(10.0, minutes("10 dk'da çıkıyorum"))
        assertEquals(30.0, minutes("yarım saate dönerim"))
        assertNull(at("10 dakikalar"))   // not a case ending
    }

    @Test fun fractions() {
        assertEquals(90.0, minutes("1,5 saat"))
        assertEquals(90.0, minutes("1.5 h"))
        assertEquals(150.0, minutes("2 buçuk saat sonra"))
        assertEquals(150.0, minutes("iki buçuk saat"))
        assertEquals(90.0, minutes("bir buçuk saat sonra"))
        assertEquals(90.0, minutes("in an hour and a half"))
        assertEquals(150.0, minutes("2 and a half hours"))
        assertEquals(15.0, minutes("çeyrek saat sonra"))
        assertEquals(15.0, minutes("in a quarter of an hour"))
    }

    @Test fun numbersInWords() {
        assertEquals(5.0, minutes("in five minutes"))
        assertEquals(10.0, minutes("on dakika sonra"))
        assertEquals(15.0, minutes("on beş dakika"))
        assertEquals(15.0, minutes("onbeş dk"))
        assertEquals(25.0, minutes("yirmi beş dk"))
        assertEquals(45.0, minutes("KIRK BEŞ DAKİKA"))
        assertEquals(25.0, minutes("twenty-five minutes"))
        assertEquals(120.0, minutes("iki saat sonra"))
        assertEquals(60.0, minutes("bir saat sonra"))
        assertEquals(180.0, minutes("three hours"))
        assertEquals(1.0, minutes("one minute"))
        assertNull(at("turn on the oven"))
        assertNull(at("one of those days"))
    }

    @Test fun pastIsNotAReminder() {
        assertNull(at("2 saat önce aradı"))
        assertNull(at("called 10 min ago"))
        assertNull(at("yarım saat önce"))
        assertNull(at("an hour ago"))
        assertEquals(10.0, minutes("10 min, not ago"))
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

    @Test fun turkishAt() {
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("yarın 9'da"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("YARIN 9'DA"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("yarın 9’da"))                // typographic apostrophe
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 21, 0), at("9'da toplantı"))            // 9 has passed: 21:00
        assertEquals(cal(2026, Calendar.DECEMBER, 4, 10, 0), at("cuma 10'da"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 15, 0), at("yarın 3'te dişçi"))         // not 03:00 at night
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 18, 30), at("saat 18.30'da"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 18, 30), at("18.30'da"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 20, 0), at("bu akşam 8'de"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 7, 0), at("sabah 7'de"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 11, 0), at("yarın saat 11'de"))
    }

    @Test fun ambiguousHours() {
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 15, 0), at("tomorrow 3"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 7, 0), at("tomorrow 7"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 3, 0), at("tomorrow at 3am"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 5, 0), at("yarın sabah 5"))
        assertEquals(cal(2026, Calendar.DECEMBER, 4, 16, 0), at("friday 4"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 13, 0), at("at 13"))                  // 24-hour, passed: tomorrow
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 21, 0), at("at 9"))
        val evening = cal(2026, Calendar.NOVEMBER, 28, 20, 0)
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 19, 0), at("7pm", evening))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 5, 0), at("at 5", evening))           // the next 5 o'clock
    }

    @Test fun noonMidnightAnd12() {
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 0, 0), at("12 am"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 12, 0), at("12 pm"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 0, 30), at("12:30 am"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 12, 5), at("12:05 pm"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 12, 0), at("at 12"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 0, 0), at("24:00"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 30, 0, 0), at("yarın 24:00"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 0, 0), at("midnight"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 0, 0), at("gece yarısı"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 12, 0), at("noon"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 12, 0), at("yarın öğlen"))
        assertNull(at("24:30"))
        assertNull(at("25:00"))
        assertNull(at("23:60"))
        assertNull(at("at 99"))
    }

    @Test fun caseAndDottedI() {
        assertEquals(10.0, minutes("İN 10 MİN"))
        assertEquals(10.0, minutes("IN 10 MIN"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 9, 0), at("TOMORROW 9"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 30, 9, 0), at("ÖBÜR GÜN"))
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 9, 0), at("SALI"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("5 ARALIK"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("5 Aralık"))
        assertEquals(10.0, minutes("10\u00A0dk"))   // a no-break space
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
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 9, 0), at("salı'ya"))
    }

    @Test fun daysFromNow() {
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 9, 0), at("in 3 days"))
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 9, 0), at("3 gün sonra"))
        assertEquals(cal(2026, Calendar.DECEMBER, 1, 18, 0), at("in 3 days at 18:00"))
        assertEquals(cal(2026, Calendar.NOVEMBER, 30, 10, 0), at("iki gün sonra saat 10"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("in a week"))
        assertEquals(cal(2026, Calendar.DECEMBER, 12, 9, 0), at("2 hafta sonra"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("haftaya"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("next week"))
        assertEquals(cal(2026, Calendar.DECEMBER, 29, 9, 0), at("in 31 days"))
        assertNull(at("3 days"))            // a length, not when
        assertNull(at("3 days ago"))
    }

    @Test fun hugeNumbersNeverOverflow() {
        assertNull(at("in 99999 days"))
        assertNull(at("in 99999999999999999999 days"))
        assertNull(at("99999999999999999999 h"))
        assertNull(at("9999999999 min"))
        assertNull(at("2562047788015 h 2562047788015 h 2562047788015 h"))
        assertNull(at("43201 min"))                       // just over 30 days
        assertEquals(43_200.0, minutes("43200 min"))      // exactly 30 days
        assertEquals(999.0, minutes("999 dk"))
        assertNull(at("1" + "0".repeat(400) + " sec"))
        assertNull(at("in 0 min"))
        assertNull(at("0.5 sec"))
    }

    @Test fun dates() {
        assertEquals(cal(2026, Calendar.DECEMBER, 3, 10, 0), at("3 Dec 10:00"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("5 aralık"))
        assertEquals(cal(2026, Calendar.DECEMBER, 5, 9, 0), at("5 aralık'ta"))
        assertEquals(cal(2027, Calendar.NOVEMBER, 3, 9, 0), at("3 kasımda"))
        assertEquals(cal(2026, Calendar.DECEMBER, 24, 18, 0), at("dec 24 at 6pm"))
        assertEquals(cal(2027, Calendar.JANUARY, 2, 9, 0), at("2/1"))
        assertEquals(cal(2027, Calendar.NOVEMBER, 1, 9, 0), at("1 november"))              // passed this year
        assertEquals(cal(2026, Calendar.DECEMBER, 15, 9, 0), at("15.12.2026"))
        assertEquals(cal(2026, Calendar.DECEMBER, 15, 18, 30), at("15.12.2026 18:30"))
        assertEquals(cal(2026, Calendar.DECEMBER, 15, 9, 0), at("15/12/26"))
        assertEquals(cal(2027, Calendar.DECEMBER, 5, 9, 0), at("5 aralık 2027"))
        assertEquals(cal(2027, Calendar.DECEMBER, 24, 9, 0), at("dec 24, 2027"))
        assertEquals(cal(2026, Calendar.DECEMBER, 3, 16, 0), at("3 Dec at 4"))               // a bare 4 on a date: 16:00
        assertNull(at("1.1.2025"))                                                         // the past
        assertNull(at("31 nisan"))                                                         // no such day
    }

    @Test fun endOfMonthAndYearAndLeapDays() {
        val nye = cal(2026, Calendar.DECEMBER, 31, 23, 0)
        assertEquals(cal(2027, Calendar.JANUARY, 1, 9, 0), at("tomorrow 9", nye))
        assertEquals(nye + 2 * 3_600_000L, at("in 2 h", nye))
        assertEquals(cal(2027, Calendar.JANUARY, 1, 9, 0), at("1 jan", nye))
        assertEquals(cal(2027, Calendar.DECEMBER, 31, 9, 0), at("31 dec", nye))
        assertEquals(cal(2027, Calendar.JANUARY, 1, 9, 0), at("friday", nye))
        assertEquals(cal(2027, Calendar.JANUARY, 1, 0, 0), at("midnight", nye))

        val jan31 = cal(2027, Calendar.JANUARY, 31, 10, 0)
        assertNull(at("30 şubat", jan31))                                     // was 2 March
        assertEquals(cal(2028, Calendar.FEBRUARY, 29, 9, 0), at("29 feb", jan31))   // the next 29 Feb
        assertEquals(cal(2027, Calendar.FEBRUARY, 15, 9, 0), at("15 şubat", jan31))
        assertEquals(cal(2027, Calendar.FEBRUARY, 1, 9, 0), at("tomorrow", jan31))
        assertEquals(cal(2027, Calendar.FEBRUARY, 28, 9, 0), at("28/2", jan31))

        assertEquals(cal(2028, Calendar.FEBRUARY, 29, 9, 0), at("29 şubat", cal(2028, Calendar.FEBRUARY, 1, 10, 0)))
        assertNull(at("29 feb", cal(2028, Calendar.MARCH, 1, 10, 0)))       // next one is 2032: over a year away
        assertEquals(cal(2028, Calendar.MARCH, 1, 9, 0), at("tomorrow", cal(2028, Calendar.FEBRUARY, 29, 10, 0)))
    }

    @Test fun daylightSavingDays() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            // 25 Oct 2026: 03:00 summer time becomes 02:00; "at 1" at noon is 13:00 the same day
            val fallBack = cal(2026, Calendar.OCTOBER, 25, 12, 0)
            assertEquals(cal(2026, Calendar.OCTOBER, 25, 13, 0), at("at 1", fallBack))
            assertEquals(cal(2026, Calendar.OCTOBER, 25, 17, 0), at("today 5", fallBack))
            assertEquals(cal(2026, Calendar.OCTOBER, 25, 9, 0), at("tomorrow 9", cal(2026, Calendar.OCTOBER, 24, 20, 0)))
            val night = cal(2026, Calendar.OCTOBER, 25, 1, 30)
            assertEquals(night + 2 * 3_600_000L, at("in 2 hours", night))   // a timer is real time
            // 29 Mar 2026: 02:00 becomes 03:00; 02:30 doesn't exist, but it still lands that morning
            val t = at("tomorrow 2:30", cal(2026, Calendar.MARCH, 28, 20, 0))!!
            val c = Calendar.getInstance().apply { timeInMillis = t }
            assertEquals(29, c.get(Calendar.DAY_OF_MONTH))
            assertTrue(c.get(Calendar.HOUR_OF_DAY) in 2..3)
            assertEquals(cal(2026, Calendar.MARCH, 29, 9, 0), at("tomorrow", cal(2026, Calendar.MARCH, 28, 20, 0)))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test fun nothing() {
        assertNull(at("buy 2 kg apples"))
        assertNull(at("milk, eggs, bread"))
        assertNull(at("room 12"))
        assertNull(at("version 1.5"))
        assertNull(at("may I ask"))
        assertNull(at("today"))   // a day with no time and it's already today: nothing to remind
        assertNull(at(""))
        assertNull(at("   "))
        assertNull(at("🙂 👍 ٣ ساعات"))
        assertNull(at("pazartesiye kadar"))   // unknown ending: left alone
    }

    @Test fun longAndRandomTextNeverThrows() {
        val big = buildString { repeat(4000) { append("not ").append(it % 97).append(" kalem, ") } }
        val t0 = System.nanoTime()
        When.find(big + " yarın 9'da", now)
        assertTrue("slow: ${(System.nanoTime() - t0) / 1_000_000} ms", System.nanoTime() - t0 < 3_000_000_000L)
        val pool = listOf("in", "at", "saat", "yarın", "9", "12", ":", "'da", "dk", "min", "h", "pm", "am", "buçuk", "on", "beş",
            "24", "99999999999", "/", ".", ",", " ", "gün", "sonra", "önce", "ago", "29", "şubat", "feb", "İ", "ı", "’", "-", "0")
        val r = Random(7)
        repeat(4000) {
            val s = buildString { repeat(1 + r.nextInt(8)) { append(pool[r.nextInt(pool.size)]); if (r.nextBoolean()) append(' ') } }
            val hit = When.find(s, now)
            if (hit != null) assertTrue("'$s' gave a time in the past", hit.at > now)
        }
    }

    @Test fun labels() {
        assertEquals("in 25 min", When.left(now + 25 * 60_000L, now))
        assertEquals("in 2 h 5 min", When.left(now + 125 * 60_000L, now))
        assertEquals("5 min ago", When.left(now - 5 * 60_000L, now))
        assertEquals("now", When.left(now + 10_000L, now))
        assertEquals("in 1 day", When.left(now + 25 * 3_600_000L, now))
        assertEquals("in 3 days", When.left(now + 3 * 86_400_000L, now))
        assertEquals("0:12", When.duration(12_400))
        assertEquals("0:00", When.duration(-5_000))
        assertEquals("1:05:00", When.duration(3_900_000))
        assertTrue(When.label(cal(2026, Calendar.NOVEMBER, 29, 9, 0), now).startsWith("tomorrow"))
        assertTrue(When.label(cal(2026, Calendar.NOVEMBER, 27, 9, 0), now).startsWith("yesterday"))
        assertTrue(When.label(cal(2027, Calendar.MARCH, 3, 9, 0), now).contains("2027"))
    }
}
