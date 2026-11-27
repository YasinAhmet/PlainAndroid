package app.plain.clock

import app.plain.lang.tr
import java.util.Calendar

/**
 * A countdown that lives in timestamps only, so nothing has to tick while the app is closed:
 * running = [endAt] (wall-clock ms) > 0; paused = [left] > 0; idle otherwise. [total] is the full length (progress).
 */
data class Countdown(val endAt: Long = 0, val left: Long = 0, val total: Long = 0) {
    val running get() = endAt > 0
    val paused get() = endAt == 0L && left > 0
    val idle get() = !running && !paused

    fun remaining(now: Long): Long = if (running) (endAt - now).coerceAtLeast(0) else left

    /** 0..1 of [total] gone. */
    fun progress(now: Long): Float = if (total <= 0) 0f else (1f - remaining(now).toFloat() / total).coerceIn(0f, 1f)

    fun pause(now: Long) = if (running) copy(endAt = 0, left = remaining(now).coerceAtLeast(1)) else this
    fun resume(now: Long) = if (paused) copy(endAt = now + left, left = 0) else this
    fun add(ms: Long, now: Long) = when {
        running -> copy(endAt = endAt + ms, total = total + ms)
        paused -> copy(left = left + ms, total = total + ms)
        else -> start(ms, now)
    }

    fun encode() = "$endAt,$left,$total"

    companion object {
        fun start(ms: Long, now: Long) = Countdown(endAt = now + ms, left = 0, total = ms)
        /** Paused at the full length: shown, waiting for Start. */
        fun ready(ms: Long) = Countdown(endAt = 0, left = ms, total = ms)
        fun decode(s: String?): Countdown {
            val p = s?.split(',')?.mapNotNull { it.toLongOrNull() } ?: return Countdown()
            return if (p.size == 3) Countdown(p[0], p[1], p[2]) else Countdown()
        }
    }
}

enum class Phase { WORK, SHORT, LONG;
    val label: String get() = when (this) {
        WORK -> tr("Focus", "Odak")
        SHORT -> tr("Short break", "Kısa mola")
        LONG -> tr("Long break", "Uzun mola")
    }
}

/** Pomodoro lengths in minutes; a long break after every [every] focus sessions; [auto]: the next phase starts by itself. */
data class PomoConfig(val work: Int = 25, val short: Int = 5, val long: Int = 15, val every: Int = 4, val auto: Boolean = true) {
    fun minutes(p: Phase) = when (p) { Phase.WORK -> work; Phase.SHORT -> short; Phase.LONG -> long }
    fun ms(p: Phase) = minutes(p) * 60_000L
}

/** Where the pomodoro cycle stands: the phase and how many focus sessions of this cycle are done. */
data class Pomo(val phase: Phase = Phase.WORK, val round: Int = 0) {
    /** The phase after this one ends (a focus session that just ended counts towards [round]). */
    fun next(cfg: PomoConfig): Pomo = when (phase) {
        Phase.WORK -> {
            val r = round + 1
            if (r >= cfg.every) Pomo(Phase.LONG, r) else Pomo(Phase.SHORT, r)
        }
        Phase.SHORT -> Pomo(Phase.WORK, round)
        Phase.LONG -> Pomo(Phase.WORK, 0)
    }

    fun encode() = "${phase.name},$round"

    companion object {
        fun decode(s: String?): Pomo {
            val p = s?.split(',') ?: return Pomo()
            val ph = Phase.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return Pomo()
            return Pomo(ph, p.getOrNull(1)?.toIntOrNull() ?: 0)
        }
    }
}

/** A stopwatch from timestamps: [startAt] > 0 while running, [acc] = time banked before it; [laps] = lap totals. */
data class Stopwatch(val startAt: Long = 0, val acc: Long = 0, val laps: List<Long> = emptyList()) {
    val running get() = startAt > 0
    val idle get() = !running && acc == 0L
    fun elapsed(now: Long) = acc + if (running) (now - startAt).coerceAtLeast(0) else 0
    fun start(now: Long) = if (running) this else copy(startAt = now)
    fun pause(now: Long) = if (running) copy(startAt = 0, acc = elapsed(now)) else this
    fun lap(now: Long) = copy(laps = laps + elapsed(now))
    fun encode() = "$startAt,$acc;" + laps.joinToString(",")

    companion object {
        fun decode(s: String?): Stopwatch {
            if (s.isNullOrEmpty()) return Stopwatch()
            val parts = s.split(';', limit = 2)
            val h = parts[0].split(',').mapNotNull { it.toLongOrNull() }
            if (h.size != 2) return Stopwatch()
            return Stopwatch(h[0], h[1], parts.getOrElse(1) { "" }.split(',').mapNotNull { it.toLongOrNull() })
        }
    }
}

object Fmt {
    /** 25:00, 1:05:00 (rounded up, so a countdown shows 00:00 only at the very end). */
    fun countdown(ms: Long): String = hms((ms + 999) / 1000)

    /** 00:07.4: a stopwatch with tenths. */
    fun stopwatch(ms: Long): String = hms(ms / 1000) + "." + (ms % 1000) / 100

    fun hms(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
    }

    /** "5 min", "1 h 30 min", "45 s". */
    fun length(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts += tr("$h h", "$h sa")
        if (m > 0) parts += tr("$m min", "$m dk")
        if (s > 0 && h == 0L) parts += tr("$s s", "$s sn")
        return parts.joinToString(" ").ifEmpty { tr("0 s", "0 sn") }
    }

    private val UNIT = Regex("""(\d+(?:\.\d+)?)\s*(hours|hour|hrs|hr|h|saat|sa|minutes|minute|mins|min|m|dakika|dak|dk|seconds|second|secs|sec|s|saniye|sn)(?![a-zçğıöşü])""")

    /**
     * A typed timer length: "5" = 5 minutes, "1:30" = 1 min 30 s, "1:00:00" = 1 hour, "90s", "2h", "1h30m",
     * "10 dk", "2 saat". Null when it doesn't read as a length (or is zero / over 24 h).
     */
    fun parseLength(input: String): Long? {
        val s = input.trim().lowercase().replace(',', '.')
        if (s.isEmpty()) return null
        val ms: Long? = when {
            Regex("""\d+(\.\d+)?""").matches(s) -> (s.toDouble() * 60_000).toLong()
            Regex("""\d+:\d{1,2}(:\d{1,2})?""").matches(s) -> {
                val p = s.split(':').map { it.toLong() }
                if (p.size == 2) (p[0] * 60 + p[1]) * 1000 else (p[0] * 3600 + p[1] * 60 + p[2]) * 1000
            }
            else -> {
                val found = UNIT.findAll(s).toList()
                if (found.isEmpty() || UNIT.replace(s, "").isNotBlank()) null
                else found.sumOf { m ->
                    val v = m.groupValues[1].toDouble()
                    when (m.groupValues[2]) {
                        "hours", "hour", "hrs", "hr", "h", "saat", "sa" -> (v * 3_600_000).toLong()
                        "seconds", "second", "secs", "sec", "s", "saniye", "sn" -> (v * 1000).toLong()
                        else -> (v * 60_000).toLong()
                    }
                }
            }
        }
        return ms?.takeIf { it in 1000..86_400_000 }
    }

    /** "2026-11-28": the day the pomodoro count belongs to. */
    fun day(now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        return "%04d-%02d-%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }
}
