package app.plain.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun pomodoroCycle() {
        val cfg = PomoConfig(every = 3)
        var p = Pomo()
        val seen = ArrayList<Phase>()
        repeat(7) { seen += p.phase; p = p.next(cfg) }
        assertEquals(listOf(Phase.WORK, Phase.SHORT, Phase.WORK, Phase.SHORT, Phase.WORK, Phase.LONG, Phase.WORK), seen)
        assertEquals(Pomo(Phase.SHORT, 1), p)   // a new cycle started after the long break
        assertEquals(Pomo(Phase.SHORT, 2), Pomo.decode(Pomo(Phase.SHORT, 2).encode()))
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

    @Test fun formats() {
        assertEquals("25:00", Fmt.countdown(25 * 60_000L))
        assertEquals("00:01", Fmt.countdown(1))
        assertEquals("1:00:00", Fmt.countdown(3_600_000))
        assertEquals("01:02.3", Fmt.stopwatch(62_345))
        assertEquals("1 h 30 min", Fmt.length(90 * 60_000L))
        assertEquals("45 s", Fmt.length(45_000))
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
}
