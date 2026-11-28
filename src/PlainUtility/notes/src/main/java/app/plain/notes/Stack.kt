package app.plain.notes

import app.plain.lang.tr
import java.util.Calendar

/**
 * Time stacking for the "Remind me" pickers: each tap of +1 d / +1 h / +15 m / +5 m / +1 m adds to the pending time,
 * holding takes the same away. Pure (tested in StackTest).
 */
object Stack {
    /** A step button: [minutes] (a day is a calendar day, so a DST night keeps the time of day). */
    class Step(val minutes: Int, val en: String, val trk: String) {
        val label: String get() = tr(en, trk)
    }

    val DAY = Step(1440, "+1 d", "+1 g")
    val HOUR = Step(60, "+1 h", "+1 sa")
    val QUARTER = Step(15, "+15 m", "+15 dk")
    val FIVE = Step(5, "+5 m", "+5 dk")
    val MINUTE = Step(1, "+1 m", "+1 dk")

    /** The reminder picker's row. */
    val REMIND = listOf(DAY, HOUR, QUARTER, FIVE, MINUTE)
    /** The time-of-day row (routines). */
    val CLOCK = listOf(HOUR, QUARTER, FIVE, MINUTE)

    /** At most this far ahead (a year): a stuck finger can't make a silly date. */
    const val MAX_AHEAD = 366L * 86_400_000L

    /**
     * [at] (0 = not set yet) moved by [step] ([sign] +1 adds, -1 takes away). Counts from [now] when nothing is set or
     * the set time has passed. 0 when the result isn't after [now] (taking away more than there was clears it).
     */
    fun add(at: Long, now: Long, step: Step, sign: Int): Long {
        val base = if (at > now) at else now
        if (sign < 0 && at <= now) return 0
        val t = if (step.minutes == 1440) Calendar.getInstance().apply { timeInMillis = base; add(Calendar.DAY_OF_YEAR, sign) }.timeInMillis
            else base + sign * step.minutes * 60_000L
        if (t > now + MAX_AHEAD) return base
        return if (t > now) t else 0
    }

    /** A time of day in minutes ([min]) moved by [step] minutes, wrapping round midnight. */
    fun clock(min: Int, step: Int): Int = Math.floorMod(min + step, 1440)

    /** "1 d 2 h 5 min", "45 min", "0 min": the gap to a stacked time. */
    fun span(ms: Long): String {
        val m = (ms.coerceAtLeast(0) + 59_999) / 60_000   // up, like When.left: 14 min 30 s is "15 min"
        val d = m / 1440
        val h = m % 1440 / 60
        val mm = m % 60
        val parts = ArrayList<String>()
        if (d > 0) parts += tr("$d d", "$d g")
        if (h > 0) parts += tr("$h h", "$h sa")
        if (mm > 0 || parts.isEmpty()) parts += tr("$mm min", "$mm dk")
        return parts.joinToString(" ")
    }

    /** "in 1 h 16 min" for a stacked time. */
    fun left(at: Long, now: Long): String = tr("in ", "") + span(at - now) + tr("", " sonra")

    /** "08:05" for a minute of the day. */
    fun hhmm(min: Int): String { val m = Math.floorMod(min, 1440); return "%02d:%02d".format(java.util.Locale.ROOT, m / 60, m % 60) }
}
