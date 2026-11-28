package app.plain.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Schedule maths, time stacking, and the JSON of routines and notes (incl. old notes.json). */
class RoutineTest {
    private val savedTz = TimeZone.getDefault()
    @After fun restoreTz() = TimeZone.setDefault(savedTz)

    private fun cal(y: Int, m: Int, d: Int, h: Int, min: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, min, 0) }.timeInMillis

    private fun hm(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    /** Saturday 28 Nov 2026. */
    private val sat = cal(2026, Calendar.NOVEMBER, 28, 14, 0)

    // ---------------------------------------------------------------- even spacing

    @Test fun evenSpacing() {
        assertEquals(listOf(480, 648, 816, 984, 1152, 1320), Sched.even(6, 480, 1320))   // 6x 08:00-22:00
        assertEquals(listOf(480), Sched.even(1, 480, 1320))                                // N=1: the start
        assertEquals(listOf(480, 1320), Sched.even(2, 480, 1320))
        assertEquals(listOf(0, 360, 720, 1080), Sched.even(4, 0, 0))                     // same start/end: whole day
        assertEquals(listOf(0, 120, 1320), Sched.even(3, 1320, 120))                     // end before start: past midnight
        assertEquals(6, Sched.even(10, 480, 485).size)                                   // squeezed: no repeats
        assertEquals(Sched.MAX_COUNT, Sched.even(1000, 0, 1439).size)
        assertEquals(listOf(480), Sched.even(0, 480, 600))                               // count below 1 -> 1
        for (n in 1..Sched.MAX_COUNT) {
            val l = Sched.even(n, 300, 1400)
            assertEquals(l.sorted(), l); assertTrue(l.all { it in 0..1439 })
        }
    }

    // ---------------------------------------------------------------- next occurrence

    private fun r(plan: Plan, done: Set<Int> = emptySet(), day: Int = 0, paused: Boolean = false) =
        Routine(1, "x", plan, paused = paused, doneDay = day, done = done)

    @Test fun nextToday() {
        val n = Sched.next(r(Plan(times = listOf(600, 900))), sat)!!
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 15, 0), n.at)
        assertEquals(20261128, n.day)
        assertEquals(900, n.min)
    }

    @Test fun nextAcrossMidnight() {
        val late = cal(2026, Calendar.NOVEMBER, 28, 23, 30)
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 8, 0), Sched.next(r(Plan(times = listOf(480, 1200))), late)!!.at)
        // exactly at an occurrence: the next one, not the same
        val at8 = cal(2026, Calendar.NOVEMBER, 29, 8, 0)
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 20, 0), Sched.next(r(Plan(times = listOf(480, 1200))), at8)!!.at)
        // year end
        val nye = cal(2026, Calendar.DECEMBER, 31, 23, 59)
        assertEquals(20270101, Sched.next(r(Plan(times = listOf(0))), nye)!!.day)
    }

    @Test fun weekdays() {
        val mon = 1 shl (Calendar.MONDAY - 1)
        // Saturday afternoon -> Monday 09:00
        assertEquals(cal(2026, Calendar.NOVEMBER, 30, 9, 0), Sched.next(r(Plan(times = listOf(540), days = mon)), sat)!!.at)
        val weekdays = 0b0111110
        assertTrue(Sched.today(r(Plan(times = listOf(540), days = weekdays)), sat).isEmpty())
        assertEquals("Weekdays", Sched.daysLabel(weekdays))
        assertEquals("Every day", Sched.daysLabel(Sched.ALL_DAYS))
        assertEquals("Mon", Sched.daysLabel(mon))
        assertNull(Sched.next(r(Plan(times = listOf(540)), paused = true), sat))
        assertNull(Sched.next(r(Plan(times = emptyList())), sat))
    }

    @Test fun dstDays() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        // spring forward 29 Mar 2026: 02:00-03:00 doesn't exist
        val before = cal(2026, Calendar.MARCH, 29, 1, 59)
        val n = Sched.next(r(Plan(times = listOf(150, 600))), before)!!   // 02:30 is skipped -> 03:30
        assertEquals(3 * 60 + 30, hm(n.at))
        assertTrue(n.at > before)
        // hourly all day: strictly increasing through the jump, never stuck
        var t = cal(2026, Calendar.MARCH, 28, 23, 0)
        val hourly = r(Plan(even = true, count = 24, start = 0, end = 0))
        repeat(30) { val o = Sched.next(hourly, t)!!; assertTrue(o.at > t); t = o.at }
        // fall back 25 Oct 2026: still moves on
        t = cal(2026, Calendar.OCTOBER, 24, 23, 0)
        repeat(30) { val o = Sched.next(hourly, t)!!; assertTrue(o.at > t); t = o.at }
        // a daily 09:00 keeps 09:00 on both sides
        assertEquals(540, hm(Sched.next(r(Plan(times = listOf(540))), cal(2026, Calendar.OCTOBER, 24, 10, 0))!!.at))
        assertEquals(540, hm(Sched.next(r(Plan(times = listOf(540))), cal(2026, Calendar.OCTOBER, 25, 10, 0))!!.at))
    }

    @Test fun latestAndTarget() {
        val plan = Plan(times = listOf(480, 720, 1080))
        val rt = r(plan)
        assertEquals(720, Sched.latest(rt, sat)!!.min)
        assertEquals(1080, Sched.latest(rt, cal(2026, Calendar.NOVEMBER, 29, 7, 0))!!.min)   // yesterday's last
        assertEquals(20261128, Sched.latest(rt, cal(2026, Calendar.NOVEMBER, 29, 7, 0))!!.day)
        // target: latest due not done; then the next one early; then none
        assertEquals(720, Sched.target(rt, sat)!!.min)
        assertEquals(480, Sched.target(rt.mark(20261128, 720, true), sat)!!.min)
        val two = rt.mark(20261128, 720, true).mark(20261128, 480, true)
        assertEquals(1080, Sched.target(two, sat)!!.min)
        assertNull(Sched.target(two.mark(20261128, 1080, true), sat))
        // yesterday's ticks don't count today
        assertEquals(720, Sched.target(rt.copy(doneDay = 20261127, done = setOf(480, 720)), sat)!!.min)
        val m = rt.mark(20261127, 480, true).mark(20261128, 720, true)
        assertEquals(setOf(720), m.doneOn(20261128))
        assertTrue(m.doneOn(20261127).isEmpty())
    }

    @Test fun alarmPicksSnooze() {
        val rt = r(Plan(times = listOf(1080))).copy(snoozeAt = sat + 600_000, snoozeDay = 20261128, snoozeMin = 720)
        assertEquals(sat + 600_000, Routines.alarmAt(rt, sat))
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 18, 0), Routines.alarmAt(rt.copy(snoozeAt = 0), sat))
        assertEquals(0L, Routines.alarmAt(rt.copy(paused = true), sat))
    }

    // ---------------------------------------------------------------- stacking

    @Test fun stacking() {
        var at = 0L
        at = Stack.add(at, sat, Stack.HOUR, 1)
        at = Stack.add(at, sat, Stack.QUARTER, 1)
        at = Stack.add(at, sat, Stack.MINUTE, 1)
        assertEquals(sat + 76 * 60_000L, at)
        assertEquals("in 1 h 16 min", Stack.left(at, sat))
        at = Stack.add(at, sat, Stack.MINUTE, -1)
        assertEquals(sat + 75 * 60_000L, at)
        assertEquals(0L, Stack.add(sat + 60_000, sat, Stack.HOUR, -1))      // taking away more clears it
        assertEquals(0L, Stack.add(0, sat, Stack.HOUR, -1))                  // nothing set: nothing to take
        assertEquals(sat + 5 * 60_000L, Stack.add(sat - 1000, sat, Stack.FIVE, 1))   // a passed time counts from now
        assertEquals(cal(2026, Calendar.NOVEMBER, 29, 14, 0), Stack.add(0, sat, Stack.DAY, 1))
        assertEquals("1 d 1 h 16 min", Stack.span((1440 + 76) * 60_000L))
        assertEquals("0 min", Stack.span(0))
        // can't run away past a year
        var far = 0L
        repeat(400) { far = Stack.add(far, sat, Stack.DAY, 1) }
        assertTrue(far <= sat + Stack.MAX_AHEAD)
        // time of day wraps
        assertEquals(15, Stack.clock(1435, 20))
        assertEquals(1425, Stack.clock(5, -20))
        assertEquals("08:05", Stack.hhmm(485))
    }

    @Test fun stackAcrossDst() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        val evening = cal(2026, Calendar.MARCH, 28, 20, 0)
        assertEquals(20 * 60, hm(Stack.add(0, evening, Stack.DAY, 1)))   // +1 d keeps the time of day (23 h later)
    }

    // ---------------------------------------------------------------- JSON

    @Test fun routineRoundTrip() {
        val a = Routine(5, "Water\nhalf a litre", Plan(even = true, count = 6, start = 480, end = 1320, days = 0b0111110),
            paused = true, created = 4, doneDay = 20261128, done = setOf(480, 648), snoozeAt = 99, snoozeDay = 20261128, snoozeMin = 648)
        val b = Routine(6, "Stretch", Plan(times = listOf(420, 1260)))
        val back = Routines.parse(Routines.encode(listOf(a, b)))!!
        assertEquals(listOf(a, b), back)
        assertTrue(Routines.encode(listOf(a)).contains("\"version\":1"))
        // a broken entry is skipped, the rest kept; junk is null (the file is then moved aside, not overwritten)
        val l = Routines.parse("""{"version":1,"routines":[{"text":"no id"},{"id":7,"text":"ok","times":[60,9999]}]}""")!!
        assertEquals(1, l.size); assertEquals(listOf(60), l[0].plan.times)
        assertNull(Routines.parse("{not json"))
        assertEquals(Sched.ALL_DAYS, Routines.parse("""[{"id":1,"days":0}]""")!![0].plan.days)
    }

    @Test fun notesRoundTripAndOldFormat() {
        val n = Note(1, "Buy milk", created = 1, remindAt = 500, archived = 900)
        val v = Note(2, "", audio = "2.m4a", audioMs = 3000, created = 2)
        assertEquals(listOf(n, v), Notes.parse(Notes.encode(listOf(n, v))))
        assertTrue(Notes.encode(listOf(n, v)).trim().startsWith("["))   // still the plain array older versions read
        // an old file: no "archived"; a "done" flag moves it to the archive
        val old = """[{"id":10,"text":"a","created":10,"remindAt":0,"fired":false},
            {"id":11,"text":"b","created":11,"remindAt":2000,"fired":true},
            {"id":12,"text":"c","created":12,"done":true}, 7, {"text":"no id"}]"""
        val l = Notes.parse(old)!!
        assertEquals(3, l.size)
        assertEquals(0L, l[0].archived)
        assertEquals(0L, l[1].archived); assertTrue(l[1].fired)
        assertTrue(l[2].archived > 0)
        assertNull(Notes.parse("[{broken"))
        assertEquals(1, Notes.parse("""{"format":2,"notes":[{"id":3,"text":"x"}]}""")!!.size)
    }

    @Test fun reuseToday() {
        val now = sat   // 14:00
        assertEquals(0L, Notes.reuseAt(0, now))
        val was = cal(2026, Calendar.NOVEMBER, 2, 18, 30)
        assertEquals(cal(2026, Calendar.NOVEMBER, 28, 18, 30), Notes.reuseAt(was, now))
        assertEquals(0L, Notes.reuseAt(cal(2026, Calendar.NOVEMBER, 2, 9, 0), now))   // passed today: none
        assertNotNull(Notes.reuseAt(was, now))
    }

    @Test fun planDescribe() {
        assertEquals("08:00 · 13:00", Plan(times = listOf(780, 480)).describe())
        assertEquals("6× 08:00–22:00", Plan(even = true, count = 6, start = 480, end = 1320).describe())
        assertEquals("09:00 · Weekends", Plan(times = listOf(540), days = 0b1000001).describe())
    }
}
