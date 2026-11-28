package app.plain.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.Random

class ClockTest {
    @Test fun countdownPauseResume() {
        var c = Countdown.start(60_000, 1_000)
        assertTrue(c.running)
        assertEquals(40_000, c.remaining(21_000))
        c = c.pause(21_000)
        assertTrue(c.paused)
        assertEquals(40_000, c.remaining(99_000))
        c = c.resume(100_000)
        assertEquals(140_000, c.endAt)
        assertEquals(0.5f, c.progress(110_000), 0.001f)
        c = c.add(60_000, 110_000)
        assertEquals(90_000, c.remaining(110_000))
        assertEquals(c, Countdown.decode(c.encode()))
    }

    @Test fun readyIsPausedAtFullLength() {
        val c = Countdown.ready(25 * 60_000L)
        assertTrue(c.paused)
        assertEquals(0f, c.progress(0), 0f)
        assertTrue(Countdown().idle)
        assertTrue(Countdown.decode("garbage").idle)
    }

    @Test fun pauseResumeEdges() {
        val c = Countdown.start(10_000, 0)
        // paused after its end (the alarm not delivered yet): still paused, not idle, a moment left
        val late = c.pause(15_000)
        assertTrue(late.paused)
        assertEquals(1, late.left)
        assertEquals(20_001, late.resume(20_000).endAt)
        // pause / resume / pause many times loses nothing
        var d = Countdown.start(600_000, 0)
        var t = 0L
        repeat(1000) { t += 100; d = d.pause(t); t += 5_000; d = d.resume(t) }
        assertEquals(600_000 - 1000 * 100L, d.remaining(t))
        // resume of a running one and pause of a paused one change nothing
        assertEquals(c, c.resume(5))
        assertEquals(late, late.pause(99))
        // +1 min while paused, and on an idle one (starts it)
        assertEquals(60_000 + 1, late.add(60_000, 0).left)
        assertEquals(Countdown.start(60_000, 7), Countdown().add(60_000, 7))
        assertEquals(0f, Countdown(0, 0, 0).progress(5), 0f)
        assertEquals(1f, Countdown.start(1000, 0).progress(5_000), 0f)
    }

    @Test fun decodesDamagedState() {
        for (bad in listOf(null, "", ",", "1,2", "1,2,3,4", "a,b,c", "9999999999999999999999,0,0", ";;", "  "))
            assertTrue("$bad", Countdown.decode(bad).idle)
        assertEquals(Countdown(5, 0, 10), Countdown.decode("5,0,10"))
        assertEquals(Pomo(), Pomo.decode(null))
        assertEquals(Pomo(), Pomo.decode("FOO,2"))
        assertEquals(Pomo(Phase.LONG, 0), Pomo.decode("LONG,x"))
        assertEquals(Pomo(Phase.SHORT, 3), Pomo.decode("SHORT,3,extra"))
        assertEquals(Stopwatch(), Stopwatch.decode(null))
        assertEquals(Stopwatch(), Stopwatch.decode("x;1,2"))
        assertEquals(Stopwatch(0, 500, listOf(1L, 3L)), Stopwatch.decode("0,500;1,junk,3"))
        assertEquals(Stopwatch(0, 500), Stopwatch.decode("0,500"))
    }

    @Test fun pomodoroCycle() {
        val cfg = PomoConfig(every = 3)
        var p = Pomo()
        val seen = ArrayList<Phase>()
        repeat(7) { seen += p.phase; p = p.next(cfg) }
        assertEquals(listOf(Phase.WORK, Phase.SHORT, Phase.WORK, Phase.SHORT, Phase.WORK, Phase.LONG, Phase.WORK), seen)
        assertEquals(Pomo(Phase.SHORT, 1), p)   // a new cycle started after the long break
        assertEquals(Pomo(Phase.SHORT, 2), Pomo.decode(Pomo(Phase.SHORT, 2).encode()))
        // a long break after every single session; a round left over from a longer cycle still ends in a long break
        assertEquals(Phase.LONG, Pomo().next(PomoConfig(every = 1)).phase)
        assertEquals(Phase.LONG, Pomo(Phase.WORK, 7).next(PomoConfig(every = 4)).phase)
        assertEquals(600 * 60_000L, PomoConfig(work = 600).ms(Phase.WORK))
    }

    @Test fun stopwatch() {
        var w = Stopwatch().start(1_000)
        w = w.lap(3_000).pause(5_000)
        assertEquals(4_000, w.elapsed(99_000))
        w = w.start(10_000)
        assertEquals(6_000, w.elapsed(12_000))
        assertEquals(listOf(2_000L), w.laps)
        assertEquals(w, Stopwatch.decode(w.encode()))
        assertEquals(Stopwatch(), Stopwatch.decode(Stopwatch().encode()))
    }

    @Test fun stopwatchRunningForDays() {
        val start = 1_800_000_000_000L
        val w = Stopwatch().start(start)
        val later = start + 5 * 86_400_000L + 3_723_400L   // 5 days 1:02:03.4
        assertEquals("121:02:03.4", Fmt.stopwatch(w.elapsed(later)))
        // a clock set back before it started never gives a negative time
        assertEquals(0, w.elapsed(start - 60_000))
        var many = w
        repeat(2000) { many = many.lap(start + it * 1000L) }
        assertEquals(many, Stopwatch.decode(many.encode()))
    }

    @Test fun clockChangesKeepTheRealTimeLeft() {
        val a = Anchor(wall = 1_000_000, elapsed = 50_000, boot = 7)
        assertEquals(a, Anchor.decode(a.encode()))
        assertNull(Anchor.decode("junk"))
        assertNull(Anchor.decode(null))
        // 10 s later by both clocks: no jump
        assertEquals(0L, a.jump(1_010_000, 60_000, 7))
        // the clock was set an hour forward
        assertEquals(3_600_000L, a.jump(1_010_000 + 3_600_000, 60_000, 7))
        // and back
        assertEquals(-3_600_000L, a.jump(1_010_000 - 3_600_000, 60_000, 7))
        // a restart in between: unknown
        assertNull(a.jump(1_010_000, 60_000, 8))
        assertNull(a.jump(1_010_000, 10_000, 7))

        // a 25 min timer, 10 min in, then the clock jumps 1 h forward: 15 min still left, not "ended"
        val cd = Countdown.start(25 * 60_000L, 0)
        val wallAfter = 10 * 60_000L + 3_600_000L
        assertEquals(0, cd.remaining(wallAfter))
        assertEquals(15 * 60_000L, cd.shift(3_600_000L).remaining(wallAfter))
        assertEquals(cd.total, cd.shift(3_600_000L).total)
        // paused and idle ones don't move
        assertEquals(cd.pause(5), cd.pause(5).shift(3_600_000L))
        assertEquals(Countdown(), Countdown().shift(-3_600_000L))
        // a stopwatch keeps what it counted
        val t0 = 1_800_000_000_000L
        val w = Stopwatch().start(t0)
        assertEquals(60_000, w.shift(-3_600_000L).elapsed(t0 + 60_000 - 3_600_000L))
        assertEquals(Stopwatch(0, 5), Stopwatch(0, 5).shift(99))
    }

    @Test fun formats() {
        assertEquals("25:00", Fmt.countdown(25 * 60_000L))
        assertEquals("00:01", Fmt.countdown(1))
        assertEquals("00:00", Fmt.countdown(0))
        assertEquals("1:00:00", Fmt.countdown(3_600_000))
        assertEquals("01:02.3", Fmt.stopwatch(62_345))
        assertEquals("1 h 30 min", Fmt.length(90 * 60_000L))
        assertEquals("45 s", Fmt.length(45_000))
        assertEquals("0 s", Fmt.length(0))
        assertEquals("24 h", Fmt.length(86_400_000))
        assertTrue(Fmt.hms(Long.MAX_VALUE / 1000).isNotEmpty())
    }

    @Test fun typedLengths() {
        assertEquals(5 * 60_000L, Fmt.parseLength("5"))
        assertEquals(90_000L, Fmt.parseLength("1:30"))
        assertEquals(3_600_000L, Fmt.parseLength("1:00:00"))
        assertEquals(80 * 60_000L, Fmt.parseLength("1h 20m"))
        assertEquals(90_000L, Fmt.parseLength("90s"))
        assertEquals(10 * 60_000L, Fmt.parseLength("10 dk"))
        assertEquals(2 * 3_600_000L, Fmt.parseLength("2 saat"))
        assertEquals(150_000L, Fmt.parseLength("2,5"))
        assertNull(Fmt.parseLength("soon"))
        assertNull(Fmt.parseLength("0"))
        assertNull(Fmt.parseLength("5 apples"))
    }

    @Test fun typedLengthsEdgeCases() {
        assertEquals(90 * 60_000L, Fmt.parseLength("1,5 saat"))
        assertEquals(90 * 60_000L, Fmt.parseLength("1.5h"))
        assertEquals(90 * 60_000L, Fmt.parseLength("1h30m"))
        assertEquals(90 * 60_000L, Fmt.parseLength("1 sa 30 dk"))
        assertEquals(86_400_000L, Fmt.parseLength("24:00:00"))
        assertEquals(86_400_000L, Fmt.parseLength("1440"))
        assertEquals(10 * 60_000L, Fmt.parseLength(" 10\u00A0dk "))
        for (bad in listOf("", "   ", "-5", "0:00", "0:00:00", "24:00:01", "1441", "1h 30", "NaN", "Infinity", "1e5", ":", "1::2",
                "99999999999999999999", "99999999999999999999:00", "9999999999999999:00", "1:99999999999999999999:00",
                "99999999999999999999h", "2562047788015h 2562047788015h 2562047788015h", "0.0001"))
            assertNull("'$bad'", Fmt.parseLength(bad))
    }

    @Test fun typedLengthsInTurkishLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            // the Turkish lower case of I is ı: "MIN" used to become "mın" and fail
            assertEquals(5 * 60_000L, Fmt.parseLength("5 MIN"))
            assertEquals(80 * 60_000L, Fmt.parseLength("1H 20MIN"))
            assertEquals(30_000L, Fmt.parseLength("30 SECS"))
            assertEquals(10 * 60_000L, Fmt.parseLength("10 DAKİKA"))
            assertEquals(2 * 3_600_000L, Fmt.parseLength("2 SAAT"))
            assertEquals(90_000L, Fmt.parseLength("90 SN"))
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test fun dayKeyIsAlwaysWesternDigits() {
        val t = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 28, 10, 0) }.timeInMillis
        val saved = Locale.getDefault()
        try {
            for (l in listOf("ar-EG", "fa-IR", "tr-TR", "hi-IN-u-nu-deva")) {
                Locale.setDefault(Locale.forLanguageTag(l))
                assertEquals(l, "2026-11-28", Fmt.day(t))
            }
        } finally {
            Locale.setDefault(saved)
        }
        val newYear = Calendar.getInstance().apply { clear(); set(2027, Calendar.JANUARY, 1, 0, 0, 1) }.timeInMillis
        assertEquals("2027-01-01", Fmt.day(newYear))
        assertFalse(Fmt.day(newYear) == Fmt.day(newYear - 2000))
    }

    @Test fun randomTypedLengthsNeverThrow() {
        val pool = listOf("1", "9", "99999999999", ":", ".", ",", " ", "h", "m", "s", "dk", "saat", "sn", "min", "İ", "I", "-", "e", "0")
        val r = Random(3)
        repeat(5000) {
            val s = buildString { repeat(1 + r.nextInt(7)) { append(pool[r.nextInt(pool.size)]) } }
            val ms = Fmt.parseLength(s)
            if (ms != null) assertTrue("'$s' = $ms", ms in 1000..86_400_000)
        }
    }
    @Test fun tickIsFastWhileRunning() {
        // a running countdown/stopwatch redraws at least every 100 ms, whatever the time
        for (now in listOf(0L, 1L, 59_999L, 60_000L, 1_764_288_000_123L, -5L)) assertTrue(Tick.delay(true, now) in 1..100)
    }

    @Test fun tickWaitsForTheMinuteWhenIdle() {
        assertEquals(60_020, Tick.delay(false, 0))
        assertEquals(21, Tick.delay(false, 59_999))
        assertEquals(30_020, Tick.delay(false, 90_000))
        // never zero/negative, never longer than a minute (+ the small slack), also for odd clocks
        val r = Random(7)
        repeat(10_000) {
            val now = r.nextLong() / 2
            val d = Tick.delay(false, now)
            assertTrue("$now -> $d", d in 21..60_020)
            assertEquals(20, Math.floorMod(now + d, 60_000L))
        }
    }

    @Test fun countdownShowsEachSecondWithin100ms() {
        // drawn every 100 ms, a 3 s countdown passes through every whole second on screen
        val c = Countdown.start(3_000, 10_000)
        val seen = LinkedHashSet<String>()
        var t = 10_000L
        while (t <= 13_100) { seen += Fmt.countdown(c.remaining(t)); t += Tick.delay(c.running, t) }
        assertEquals(listOf("00:03", "00:02", "00:01", "00:00"), seen.toList())
    }
}
