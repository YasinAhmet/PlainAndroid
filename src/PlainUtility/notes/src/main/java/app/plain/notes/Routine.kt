package app.plain.notes

import app.plain.lang.tr
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * When a routine happens each day. [even] false: at [times] (minutes of the day). [even] true: [count] times spaced
 * evenly from [start] to [end] (an end before the start runs past midnight; the same start and end spread them over
 * the whole day). [days]: weekday bits, bit (Calendar.DAY_OF_WEEK - 1), so 1 = Sunday, 2 = Monday… 127 = every day.
 */
data class Plan(
    val even: Boolean = false,
    val times: List<Int> = listOf(9 * 60),
    val count: Int = 6,
    val start: Int = 8 * 60,
    val end: Int = 22 * 60,
    val days: Int = Sched.ALL_DAYS,
) {
    /** The day's times, sorted, minutes 0..1439. */
    val minutes: List<Int> get() = if (even) Sched.even(count, start, end) else times.map { Math.floorMod(it, 1440) }.distinct().sorted()

    /** "08:00 · 13:00", "6× 08:00–22:00", plus the days when not every day. */
    fun describe(): String {
        val m = minutes
        val what = when {
            m.isEmpty() -> tr("No times", "Saat yok")
            even && m.size > 1 -> "${m.size}× " + Stack.hhmm(start) + "–" + Stack.hhmm(end)
            m.size <= 4 -> m.joinToString(" · ") { Stack.hhmm(it) }
            else -> tr("${m.size} times: ", "${m.size} kez: ") + Stack.hhmm(m.first()) + "…" + Stack.hhmm(m.last())
        }
        return if ((days and Sched.ALL_DAYS) == Sched.ALL_DAYS) what else what + " · " + Sched.daysLabel(days)
    }

    fun json(o: JSONObject): JSONObject = o.put("kind", if (even) "even" else "times")
        .put("times", JSONArray().apply { times.forEach { put(it) } })
        .put("count", count).put("start", start).put("end", end).put("days", days)

    companion object {
        fun of(o: JSONObject): Plan {
            val a = o.optJSONArray("times")
            val times = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i -> a.optInt(i, -1).takeIf { it in 0..1439 } }
            return Plan(
                even = o.optString("kind") == "even",
                times = times,
                count = o.optInt("count", 6).coerceIn(1, Sched.MAX_COUNT),
                start = Math.floorMod(o.optInt("start", 8 * 60), 1440),
                end = Math.floorMod(o.optInt("end", 22 * 60), 1440),
                days = (o.optInt("days", Sched.ALL_DAYS) and Sched.ALL_DAYS).let { if (it == 0) Sched.ALL_DAYS else it },
            )
        }
    }
}

/**
 * A routine: [text] that comes round every day on its [plan]. [done]: the times (minutes) ticked off on day [doneDay]
 * (yyyymmdd); another day means nothing is done yet, so the ticks reset by themselves. [snoozeAt] > 0: a snoozed
 * reminder for occurrence [snoozeMin] of day [snoozeDay].
 */
data class Routine(
    val id: Long,
    val text: String,
    val plan: Plan = Plan(),
    val paused: Boolean = false,
    val created: Long = id,
    val doneDay: Int = 0,
    val done: Set<Int> = emptySet(),
    val snoozeAt: Long = 0,
    val snoozeDay: Int = 0,
    val snoozeMin: Int = -1,
) {
    val title: String get() = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: tr("Routine", "Rutin")

    fun doneOn(day: Int): Set<Int> = if (doneDay == day) done else emptySet()

    /** Occurrence [min] of day [day] ticked ([on]) or unticked. A tick for another day than [doneDay] starts that day afresh. */
    fun mark(day: Int, min: Int, on: Boolean): Routine {
        val cur = doneOn(day)
        val next = if (on) cur + min else cur - min
        return copy(doneDay = day, done = next)
    }

    fun json(): JSONObject = plan.json(JSONObject().put("id", id).put("text", text).put("created", created))
        .put("paused", paused).put("doneDay", doneDay).put("done", JSONArray().apply { done.sorted().forEach { put(it) } })
        .apply { if (snoozeAt > 0) put("snoozeAt", snoozeAt).put("snoozeDay", snoozeDay).put("snoozeMin", snoozeMin) }

    companion object {
        fun of(o: JSONObject): Routine {
            val d = o.optJSONArray("done")
            return Routine(
                id = o.getLong("id"),
                text = o.optString("text"),
                plan = Plan.of(o),
                paused = o.optBoolean("paused"),
                created = o.optLong("created", o.getLong("id")),
                doneDay = o.optInt("doneDay"),
                done = if (d == null) emptySet() else (0 until d.length()).mapNotNull { i -> d.optInt(i, -1).takeIf { it in 0..1439 } }.toSet(),
                snoozeAt = o.optLong("snoozeAt"),
                snoozeDay = o.optInt("snoozeDay"),
                snoozeMin = o.optInt("snoozeMin", -1),
            )
        }
    }
}

/** The schedule maths, pure (RoutineTest). Times are the phone's local time; a day is a calendar day. */
object Sched {
    const val ALL_DAYS = 127
    const val MAX_COUNT = 48

    /** [count] times from [start] to [end] (minutes of the day), evenly; see [Plan]. Sorted, no repeats. */
    fun even(count: Int, start: Int, end: Int): List<Int> {
        val n = count.coerceIn(1, MAX_COUNT)
        val s = Math.floorMod(start, 1440)
        if (n == 1) return listOf(s)
        var span = Math.floorMod(end - start, 1440)
        val gaps = if (span == 0) { span = 1440; n } else n - 1
        return (0 until n).map { Math.floorMod(s + Math.round(it * span.toDouble() / gaps).toInt(), 1440) }.distinct().sorted()
    }

    fun dayKey(c: Calendar) = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    fun dayKey(t: Long) = dayKey(Calendar.getInstance().apply { timeInMillis = t })

    fun onDay(days: Int, c: Calendar) = (days and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1))) != 0

    /** Midnight of [t]'s day moved by [offset] days. */
    private fun dayStart(t: Long, offset: Int) = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, offset)
    }

    /** The moment of minute [min] on [day]'s date (a time skipped by a DST jump comes out an hour later). */
    fun at(day: Calendar, min: Int): Long = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, min / 60); set(Calendar.MINUTE, min % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** An occurrence: its moment, its day (yyyymmdd) and its minute of the day. */
    data class Occ(val at: Long, val day: Int, val min: Int)

    /** The first occurrence strictly after [after] (within a week and a day), or null (paused, no times). */
    fun next(r: Routine, after: Long): Occ? {
        if (r.paused) return null
        val mins = r.plan.minutes
        if (mins.isEmpty()) return null
        for (d in 0..8) {
            val day = dayStart(after, d)
            if (!onDay(r.plan.days, day)) continue
            for (m in mins) { val t = at(day, m); if (t > after) return Occ(t, dayKey(day), m) }
        }
        return null
    }

    /** The latest occurrence at or before [upTo] (today's or yesterday's), or null. */
    fun latest(r: Routine, upTo: Long): Occ? {
        val mins = r.plan.minutes
        if (r.paused || mins.isEmpty()) return null
        for (d in 0 downTo -1) {
            val day = dayStart(upTo, d)
            if (!onDay(r.plan.days, day)) continue
            for (m in mins.asReversed()) { val t = at(day, m); if (t <= upTo) return Occ(t, dayKey(day), m) }
        }
        return null
    }

    /** Today's occurrences (none on a day the routine skips). */
    fun today(r: Routine, now: Long): List<Occ> {
        val day = dayStart(now, 0)
        if (!onDay(r.plan.days, day)) return emptyList()
        val key = dayKey(day)
        return r.plan.minutes.map { Occ(at(day, it), key, it) }
    }

    /**
     * The occurrence a tap on the check ticks: the latest one already due and not done, else the next one today not
     * done (ticking early), else null (all done, or nothing today).
     */
    fun target(r: Routine, now: Long): Occ? {
        val list = today(r, now)
        if (list.isEmpty()) return null
        val done = r.doneOn(list.first().day)
        val open = list.filter { it.min !in done }
        return open.lastOrNull { it.at <= now } ?: open.firstOrNull()
    }

    /** "Mon Wed Fri", "Weekdays", "Weekends". */
    fun daysLabel(days: Int): String {
        val d = days and ALL_DAYS
        if (d == ALL_DAYS) return tr("Every day", "Her gün")
        if (d == 0b0111110) return tr("Weekdays", "Hafta içi")
        if (d == 0b1000001) return tr("Weekends", "Hafta sonu")
        return ORDER.filter { (d and (1 shl (it - 1))) != 0 }.joinToString(" ") { dayName(it) }
    }

    /** Monday first. */
    val ORDER = listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY)

    fun dayName(dow: Int): String = when (dow) {
        Calendar.MONDAY -> tr("Mon", "Pzt")
        Calendar.TUESDAY -> tr("Tue", "Sal")
        Calendar.WEDNESDAY -> tr("Wed", "Çar")
        Calendar.THURSDAY -> tr("Thu", "Per")
        Calendar.FRIDAY -> tr("Fri", "Cum")
        Calendar.SATURDAY -> tr("Sat", "Cmt")
        else -> tr("Sun", "Paz")
    }
}
