package app.plain.notes

import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Times written inside a note, English or Turkish, so a note can be typed and timed in one go:
 * "in 20 min", "pasta 10 dk", "1h 30m", "yarım saat sonra" (a timer: from now), or "at 18:30", "tomorrow 9",
 * "yarın sabah", "tonight", "friday 14:00", "3 Dec 10:00", "5 aralık" (a date). Plus the labels the list shows.
 */
object When {
    /** [at]: when to remind; [timer]: counted from now ("in 20 min") rather than a clock time. */
    data class Hit(val at: Long, val timer: Boolean)

    private const val UNIT_H = "hours|hour|hrs|hr|h|saat|sa"
    private const val UNIT_M = "minutes|minute|mins|min|m|dakika|dak|dk"
    private const val UNIT_S = "seconds|second|secs|sec|s|saniye|sn"
    private const val END = "(?![\\p{L}\\d])"
    private const val START = "(?<![\\p{L}\\d:./])"

    private val REL = Regex("$START(\\d+(?:[.,]\\d+)?)\\s*($UNIT_H|$UNIT_M|$UNIT_S)$END")
    private val HALF_HOUR = Regex("${START}(half an hour|yarım saat|yarim saat)$END")
    private val AN_HOUR = Regex("${START}(an hour|bir saat)$END")
    private val CLOCK = Regex("$START(\\d{1,2})[:](\\d{2})\\s*(am|pm)?$END")
    /** "at 5", "saat 16", "@ 18.30" — but not "1 saat 15 dk" (a length). */
    private val AT = Regex("${START}(?<!\\d\\s)(?:at|@|saat)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$END(?!\\s*(?:$UNIT_M|$UNIT_S)$END)")
    private val AMPM = Regex("$START(\\d{1,2})\\s*(am|pm)$END")
    private val MORNING_H = Regex("${START}(?:sabah|morning)\\s+(\\d{1,2})$END")
    private val EVENING_H = Regex("${START}(?:akşam|aksam|evening|tonight|öğleden sonra|ogleden sonra|afternoon)\\s+(\\d{1,2})$END")
    private val DAY_NUM_MONTH = Regex("$START(\\d{1,2})\\s+([\\p{L}]+)$END")
    private val MONTH_DAY_NUM = Regex("$START([\\p{L}]+)\\s+(\\d{1,2})$END")
    private val SLASH_DATE = Regex("$START(\\d{1,2})/(\\d{1,2})$END")
    /** "tomorrow 9", "yarın 14", "friday 10": a day word followed by a bare hour. */
    private val DAY_THEN_HOUR = Regex("$START([\\p{L}]+)\\s+(\\d{1,2})$END")

    private val MONTHS = listOf(
        listOf("january", "jan", "ocak"), listOf("february", "feb", "şubat", "subat"), listOf("march", "mar", "mart"),
        listOf("april", "apr", "nisan"), listOf("may", "mayıs", "mayis"), listOf("june", "jun", "haziran"),
        listOf("july", "jul", "temmuz"), listOf("august", "aug", "ağustos", "agustos"), listOf("september", "sep", "sept", "eylül", "eylul"),
        listOf("october", "oct", "ekim"), listOf("november", "nov", "kasım", "kasim"), listOf("december", "dec", "aralık", "aralik"),
    )
    /** Calendar.SUNDAY = 1 … SATURDAY = 7. */
    private val WEEKDAYS = mapOf(
        Calendar.MONDAY to listOf("monday", "pazartesi"), Calendar.TUESDAY to listOf("tuesday", "salı", "sali"),
        Calendar.WEDNESDAY to listOf("wednesday", "çarşamba", "carsamba"), Calendar.THURSDAY to listOf("thursday", "perşembe", "persembe"),
        Calendar.FRIDAY to listOf("friday", "cuma"), Calendar.SATURDAY to listOf("saturday", "cumartesi"), Calendar.SUNDAY to listOf("sunday", "pazar"),
    )

    private fun month(w: String) = MONTHS.indexOfFirst { w in it }.takeIf { it >= 0 }

    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace("i̇", "i").replace('’', '\'')

    /** The time a note's text asks for, or null. A time that has already passed today means tomorrow. */
    fun find(text: String, now: Long): Hit? {
        val s = norm(text)
        absolute(s, now)?.let { return if (it > now) Hit(it, false) else null }
        var ms = 0L
        REL.findAll(s).forEach { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@forEach
            ms += when {
                Regex("^($UNIT_H)$").matches(m.groupValues[2]) -> (v * 3_600_000).toLong()
                Regex("^($UNIT_S)$").matches(m.groupValues[2]) -> (v * 1000).toLong()
                else -> (v * 60_000).toLong()
            }
        }
        if (HALF_HOUR.containsMatchIn(s)) ms += 30 * 60_000L
        else if (ms == 0L && AN_HOUR.containsMatchIn(s)) ms += 60 * 60_000L
        return if (ms in 1000..(30L * 86_400_000)) Hit(now + ms, true) else null
    }

    private fun absolute(s: String, now: Long): Long? {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        var dayOffset: Int? = null
        var date: Pair<Int, Int>? = null   // month (0-based), day
        var hour: Int? = null
        var minute = 0
        var pm: Boolean? = null            // true pm, false am, null unknown
        var bare = false                   // an hour alone ("at 5"): could be am or pm; "9:15" is 24-hour
        val words = Regex("[\\p{L}]+").findAll(s).map { it.value }.toSet()
        fun has(vararg w: String) = w.any { it in words }
        val phrase = { p: String -> Regex("$START$p$END").containsMatchIn(s) }

        // ---- the day
        when {
            phrase("day after tomorrow") || phrase("öbür gün") || phrase("obur gun") || phrase("yarından sonra") || phrase("yarindan sonra") -> dayOffset = 2
            has("tomorrow", "yarın", "yarin") -> dayOffset = 1
            has("today", "bugün", "bugun", "tonight") || phrase("bu akşam") || phrase("bu aksam") || phrase("this evening") -> dayOffset = 0
        }
        if (dayOffset == null) for ((wd, names) in WEEKDAYS) if (names.any { it in words }) {
            var d = (wd - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (d == 0) d = 7
            dayOffset = d
        }
        DAY_NUM_MONTH.findAll(s).firstNotNullOfOrNull { m -> month(m.groupValues[2])?.let { it to m.groupValues[1].toInt() } }?.let { date = it }
        if (date == null) MONTH_DAY_NUM.findAll(s).firstNotNullOfOrNull { m ->
            month(m.groupValues[1])?.takeIf { m.groupValues[1].length >= 3 }?.let { it to m.groupValues[2].toInt() }
        }?.let { date = it }
        if (date == null) SLASH_DATE.find(s)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (mo in 1..12) date = (mo - 1) to d
        }

        // ---- the time
        val evening = has("akşam", "aksam", "tonight", "evening", "afternoon") || phrase("öğleden sonra") || phrase("ogleden sonra")
        val morning = has("sabah", "morning")
        CLOCK.find(s)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt(); pm = ampm(m.groupValues[3]) }
        if (hour == null) AT.find(s)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toIntOrNull() ?: 0; pm = ampm(m.groupValues[3]); bare = m.groupValues[2].isEmpty() }
        if (hour == null) AMPM.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = ampm(m.groupValues[2]) }
        if (hour == null) MORNING_H.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = false }
        if (hour == null) EVENING_H.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = true }
        if (hour == null && (dayOffset != null || date != null)) DAY_THEN_HOUR.findAll(s).firstOrNull { m ->
            val w = m.groupValues[1]
            w in listOf("tomorrow", "yarın", "yarin", "today", "bugün", "bugun") || WEEKDAYS.values.any { w in it }
        }?.let { m -> hour = m.groupValues[2].toInt(); bare = true }
        if (pm == null && evening) pm = true
        if (pm == null && morning) pm = false

        val h0 = hour
        if (h0 == null && dayOffset == null && date == null && !evening && !morning) return null
        if (h0 != null && (h0 > 23 || minute > 59)) return null
        if (h0 != null && pm != null && h0 > 12) pm = null
        var h = h0 ?: if (evening) 20 else 9
        if (h0 != null && pm == true && h < 12) h += 12
        if (h0 != null && pm == false && h == 12) h = 0

        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val dt = date
        if (dt != null) {
            c.set(Calendar.MONTH, dt.first)
            if (dt.second !in 1..c.getActualMaximum(Calendar.DAY_OF_MONTH)) return null
            c.set(Calendar.DAY_OF_MONTH, dt.second)
            c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, minute)
            if (c.timeInMillis <= now) c.add(Calendar.YEAR, 1)
            return c.timeInMillis
        }
        c.set(Calendar.HOUR_OF_DAY, h); c.set(Calendar.MINUTE, minute)
        val off = dayOffset
        if (off != null) {
            c.add(Calendar.DAY_OF_YEAR, off)
            // "today 5" said at 14:00 means 17:00
            if (off == 0 && bare && pm == null && h < 12 && c.timeInMillis <= now) c.add(Calendar.HOUR_OF_DAY, 12)
            return c.timeInMillis
        }
        // a time with no day: the next time it comes round ("at 5" at 14:00 = 17:00; at 18:00 = 05:00 tomorrow)
        if (c.timeInMillis <= now && bare && pm == null && h < 12) {
            c.add(Calendar.HOUR_OF_DAY, 12)
            if (c.timeInMillis > now) return c.timeInMillis
            c.add(Calendar.HOUR_OF_DAY, -12)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    private fun ampm(s: String) = when (s) { "am" -> false; "pm" -> true; else -> null }

    // ------------------------------------------------------------------ labels

    private fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)

    /** "today 18:30", "tomorrow 09:00", "Fri 14:00" (this week), "3 Dec 10:00", "3 Dec 2027 10:00". */
    fun label(at: Long, now: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val n = Calendar.getInstance().apply { timeInMillis = now }
        val time = SimpleDateFormat("HH:mm", PlainLang.locale).format(Date(at))
        if (sameDay(c, n)) return tr("today $time", "bugün $time")
        n.add(Calendar.DAY_OF_YEAR, 1)
        if (sameDay(c, n)) return tr("tomorrow $time", "yarın $time")
        n.add(Calendar.DAY_OF_YEAR, -2)
        if (sameDay(c, n)) return tr("yesterday $time", "dün $time")
        val days = (at - now) / 86_400_000.0
        val pattern = when {
            days in 0.0..6.0 -> "EEE"
            c.get(Calendar.YEAR) == Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR) -> "d MMM"
            else -> "d MMM yyyy"
        }
        return SimpleDateFormat(pattern, PlainLang.locale).format(Date(at)) + " " + time
    }

    /** "in 25 min", "in 2 h 5 min", "in 3 days", "now", "5 min ago". */
    fun left(at: Long, now: Long): String {
        val d = at - now
        val m = (Math.abs(d) + 59_999) / 60_000
        val span = when {
            m < 60 -> tr("$m min", "$m dk")
            m < 24 * 60 -> if (m % 60 == 0L) tr("${m / 60} h", "${m / 60} sa") else tr("${m / 60} h ${m % 60} min", "${m / 60} sa ${m % 60} dk")
            else -> tr("${m / (24 * 60)} days", "${m / (24 * 60)} gün")
        }
        return when {
            Math.abs(d) < 30_000 -> tr("now", "şimdi")
            d > 0 -> tr("in $span", "$span sonra")
            else -> tr("$span ago", "$span önce")
        }
    }

    /** "0:12", "1:05:00": a recording's length. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
