package app.plain.notes

import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Times written inside a note, English or Turkish, so a note can be typed and timed in one go:
 * "in 20 min", "pasta 10 dk", "1h 30m", "yarım saat sonra", "iki buçuk saat" (a timer: from now), or "at 18:30",
 * "tomorrow 9", "yarın 9'da", "yarın sabah", "tonight", "friday 14:00", "3 Dec 10:00", "5 aralık", "15.12.2026",
 * "in 3 days", "3 gün sonra", "haftaya" (a date). Plus the labels the list shows.
 */
object When {
    /** [at]: when to remind; [timer]: counted from now ("in 20 min") rather than a clock time. */
    data class Hit(val at: Long, val timer: Boolean)

    // Turkish case endings on a unit count as the unit: "10 dakikaya", "2 saatte", "30 sn'de", "10 dk'da"
    private const val TR_H = "saat(?:'?t?e)?|sa"
    private const val TR_M = "dakika(?:'?[yd]a)?|dak|dk(?:'?[yd]a)?"
    private const val TR_S = "saniye(?:'?[yd]e)?|sn(?:'?[yd]e)?"
    private const val UNIT_H = "hours|hour|hrs|hr|h|$TR_H"
    private const val UNIT_M = "minutes|minute|mins|min|m|$TR_M"
    private const val UNIT_S = "seconds|second|secs|sec|s|$TR_S"
    private const val UNIT_D = "days|day|gün|gun"
    private const val UNIT_W = "weeks|week|hafta"
    /** Units a number written as a word can stand before ("five minutes", "iki saat"); no one-letter ones. */
    private const val WORD_UNITS = "hours|hour|hrs|hr|$TR_H|minutes|minute|mins|min|$TR_M|seconds|second|secs|sec|$TR_S|$UNIT_D|$UNIT_W"
    private const val END = "(?![\\p{L}\\d])"
    private const val START = "(?<![\\p{L}\\d:./])"
    /** "2 saat önce", "10 min ago": a time in the past, not a reminder. */
    private const val AGO = "(?!\\s+(?:ago|önce|once|evvel)$END)"
    private const val HALF = "buçuk|bucuk|and a half"

    private val REL = Regex("$START(\\d+(?:[.,]\\d+)?)\\s*($UNIT_H|$UNIT_M|$UNIT_S)$END$AGO")
    private val IS_H = Regex("^(?:$UNIT_H)$")
    private val IS_S = Regex("^(?:$UNIT_S)$")
    private val HALF_HOUR = Regex("${START}(half an hour|yar[ıi]m saat(?:'?t?e)?)$END$AGO")
    private val QUARTER_HOUR = Regex("${START}(?:a )?(quarter of an hour|quarter hour|[çc]eyrek saat(?:'?t?e)?)$END$AGO")
    private val AN_HOUR = Regex("${START}(an hour)$END$AGO")
    /** "in 3 days", "in a week"; "3 gün sonra", "2 weeks later". */
    private val IN_DAYS = Regex("${START}in\\s+(\\d+|an?)\\s*($UNIT_D|$UNIT_W)$END")
    private val DAYS_LATER = Regex("$START(\\d+|an?)\\s*($UNIT_D|$UNIT_W)\\s+(?:sonra|later|from now)$END")
    private val CLOCK = Regex("$START(\\d{1,2})[:](\\d{2})\\s*(am|pm)?$END")
    /** "at 5", "saat 16", "@ 18.30" — but not "1 saat 15 dk" (a length). */
    private val AT = Regex("${START}(?<!\\d\\s)(?:at|@|saat)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?$END(?!\\s*(?:$UNIT_M|$UNIT_S)$END)")
    private val AMPM = Regex("$START(\\d{1,2})\\s*(am|pm)$END")
    /** Turkish "at": "9'da", "3'te", "11'de", "9.30'da". */
    private val TR_AT = Regex("$START(\\d{1,2})(?:[:.](\\d{2}))?'(?:da|de|ta|te)$END")
    private val MORNING_H = Regex("${START}(?:sabah|morning)\\s+(\\d{1,2})$END")
    private val EVENING_H = Regex("${START}(?:akşam|aksam|evening|tonight|öğleden sonra|ogleden sonra|afternoon)\\s+(\\d{1,2})$END")
    private val NOON = Regex("$START(?:noon|midday|öğlen|oglen)$END")
    private val MIDNIGHT = Regex("$START(?:midnight|gece yarısı|gece yarisi)$END")
    private val NUM_DATE = Regex("$START(\\d{1,2})([./])(\\d{1,2})\\2(\\d{4}|\\d{2})$END")
    private val DAY_NUM_MONTH = Regex("$START(\\d{1,2})\\s+([\\p{L}]+)(?:'?[\\p{L}]*)(?:,?\\s+(\\d{4}))?$END")
    private val MONTH_DAY_NUM = Regex("$START([\\p{L}]+)\\s+(\\d{1,2})(?:,?\\s+(\\d{4}))?$END")
    private val SLASH_DATE = Regex("$START(\\d{1,2})/(\\d{1,2})$END")
    /** "tomorrow 9", "yarın 14", "friday 10": a day word followed by a bare hour. */
    private val DAY_THEN_HOUR = Regex("$START([\\p{L}]+)\\s+(\\d{1,2})$END")
    private val WORD = Regex("[\\p{L}]+")

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

    // numbers written as words, only right before a unit ("ten minutes", "on beş dakika", "yirmi beş dk")
    private val ONES = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
        "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15, "sixteen" to 16,
        "seventeen" to 17, "eighteen" to 18, "nineteen" to 19,
        "bir" to 1, "iki" to 2, "üç" to 3, "uc" to 3, "dört" to 4, "dort" to 4, "beş" to 5, "bes" to 5, "altı" to 6, "alti" to 6,
        "yedi" to 7, "sekiz" to 8, "dokuz" to 9,
    )
    private val TENS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50, "sixty" to 60, "ninety" to 90,
        "on" to 10, "yirmi" to 20, "otuz" to 30, "kırk" to 40, "kirk" to 40, "elli" to 50, "altmış" to 60, "altmis" to 60, "doksan" to 90,
    )
    private fun alt(keys: Collection<String>) = keys.sortedByDescending { it.length }.joinToString("|")
    private val NUM_WORD = Regex("$START(?:(${alt(TENS.keys)})(?:[\\s-]*(${alt(ONES.keys)}))?|(${alt(ONES.keys)}))" +
        "(?=(?:\\s+(?:$HALF))?\\s*(?:$WORD_UNITS)$END)")
    private val NUM_HALF = Regex("$START(\\d+)\\s+(?:$HALF)(?=\\s*(?:$UNIT_H|$UNIT_M|$UNIT_S)$END)")
    private val HOUR_AND_HALF = Regex("${START}an hour and a half$END")

    /** Day and month in a note ([y] null: the next time it comes round). [m] is 0-based. */
    private data class Day(val y: Int?, val m: Int, val d: Int)

    /** The month a word names; Turkish endings are allowed ("aralıkta", "kasımda", "mayısta"). */
    private fun month(w: String): Int? {
        MONTHS.indexOfFirst { w in it }.takeIf { it >= 0 }?.let { return it }
        for (suffix in listOf("ta", "da", "te", "de", "a", "e")) {
            if (w.length > suffix.length + 2 && w.endsWith(suffix)) MONTHS.indexOfFirst { w.dropLast(suffix.length) in it }.takeIf { it >= 0 }?.let { return it }
        }
        return null
    }

    /** Lower case without the Turkish-locale i trap; one kind of apostrophe and space. */
    private fun norm(s: String) = s.lowercase(Locale.ROOT).replace("i̇", "i")
        .replace('’', '\'').replace('‘', '\'').replace('´', '\'').replace('`', '\'')
        .replace('\u00A0', ' ').replace('\u202F', ' ')

    /** Numbers in words become digits, "2 buçuk saat" becomes "2.5 saat". */
    private fun prepare(s: String): String {
        var t = NUM_WORD.replace(s) { m ->
            val g = m.groupValues
            (if (g[3].isNotEmpty()) ONES[g[3]] ?: 0 else (TENS[g[1]] ?: 0) + (ONES[g[2]] ?: 0)).toString()
        }
        t = NUM_HALF.replace(t) { it.groupValues[1] + ".5" }
        t = HOUR_AND_HALF.replace(t, "1.5 hours")
        return t
    }

    /** The time a note's text asks for, or null. A time that has already passed today means tomorrow. */
    fun find(text: String, now: Long): Hit? {
        val s = prepare(norm(text))
        absolute(s, now)?.let { return if (it > now) Hit(it, false) else null }
        var ms = 0.0   // (a double: "99999999999 h" must not wrap round to a small number)
        REL.findAll(s).forEach { m ->
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@forEach
            ms += v * when {
                IS_H.matches(m.groupValues[2]) -> 3_600_000.0
                IS_S.matches(m.groupValues[2]) -> 1000.0
                else -> 60_000.0
            }
        }
        if (HALF_HOUR.containsMatchIn(s)) ms += 30 * 60_000.0
        else if (QUARTER_HOUR.containsMatchIn(s)) ms += 15 * 60_000.0
        else if (ms == 0.0 && AN_HOUR.containsMatchIn(s)) ms += 60 * 60_000.0
        return if (ms >= 1000.0 && ms <= 30 * 86_400_000.0) Hit(now + ms.toLong(), true) else null
    }

    /** "in 3 days", "2 hafta sonra", "in a week": days from today (1 year at most), else null. */
    private fun inDays(s: String): Int? {
        val m = IN_DAYS.find(s) ?: DAYS_LATER.find(s) ?: return null
        val n = m.groupValues[1].let { if (it == "a" || it == "an") 1 else it.toIntOrNull() } ?: return null
        val days = n.toLong() * (if (Regex("^(?:$UNIT_W)$").matches(m.groupValues[2])) 7 else 1)
        return days.takeIf { it in 1..366 }?.toInt()
    }

    private fun absolute(s: String, now: Long): Long? {
        val base = Calendar.getInstance().apply { timeInMillis = now }
        var dayOffset: Int? = null
        var date: Day? = null
        var hour: Int? = null
        var minute = 0
        var pm: Boolean? = null            // true pm, false am, null unknown
        var bare = false                   // an hour alone ("at 5"): could be am or pm; "9:15" is 24-hour
        val words = WORD.findAll(s).map { it.value }.toSet()
        fun has(vararg w: String) = w.any { it in words }
        val phrase = { p: String -> Regex("$START$p$END").containsMatchIn(s) }

        // ---- the day
        when {
            phrase("day after tomorrow") || phrase("öbür gün") || phrase("obur gun") || phrase("yarından sonra") || phrase("yarindan sonra") -> dayOffset = 2
            has("tomorrow", "yarın", "yarin") -> dayOffset = 1
            has("today", "bugün", "bugun", "tonight") || phrase("bu akşam") || phrase("bu aksam") || phrase("this evening") -> dayOffset = 0
        }
        if (dayOffset == null) dayOffset = inDays(s)
        if (dayOffset == null) for ((wd, names) in WEEKDAYS) if (names.any { it in words }) {
            var d = (wd - base.get(Calendar.DAY_OF_WEEK) + 7) % 7
            if (d == 0) d = 7
            dayOffset = d
        }
        if (dayOffset == null && (has("haftaya") || phrase("next week") || phrase("gelecek hafta") || phrase("önümüzdeki hafta") || phrase("onumuzdeki hafta"))) dayOffset = 7
        NUM_DATE.find(s)?.let { m ->
            val y = m.groupValues[4].toInt().let { if (it < 100) 2000 + it else it }
            val mo = m.groupValues[3].toInt()
            if (mo in 1..12) date = Day(y, mo - 1, m.groupValues[1].toInt())
        }
        if (date == null) DAY_NUM_MONTH.findAll(s).firstNotNullOfOrNull { m ->
            month(m.groupValues[2])?.let { Day(m.groupValues[3].toIntOrNull(), it, m.groupValues[1].toInt()) }
        }?.let { date = it }
        if (date == null) MONTH_DAY_NUM.findAll(s).firstNotNullOfOrNull { m ->
            month(m.groupValues[1])?.takeIf { m.groupValues[1].length >= 3 }?.let { Day(m.groupValues[3].toIntOrNull(), it, m.groupValues[2].toInt()) }
        }?.let { date = it }
        if (date == null) SLASH_DATE.find(s)?.let { m ->
            val d = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt()
            if (mo in 1..12) date = Day(null, mo - 1, d)
        }

        // ---- the time
        val evening = has("akşam", "aksam", "tonight", "evening", "afternoon") || phrase("öğleden sonra") || phrase("ogleden sonra")
        val morning = has("sabah", "morning")
        CLOCK.find(s)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toInt(); pm = ampm(m.groupValues[3]) }
        if (hour == null) AT.find(s)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toIntOrNull() ?: 0; pm = ampm(m.groupValues[3]); bare = m.groupValues[2].isEmpty() }
        if (hour == null) AMPM.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = ampm(m.groupValues[2]) }
        if (hour == null) TR_AT.find(s)?.let { m -> hour = m.groupValues[1].toInt(); minute = m.groupValues[2].toIntOrNull() ?: 0; bare = m.groupValues[2].isEmpty() }
        if (hour == null) MORNING_H.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = false }
        if (hour == null) EVENING_H.find(s)?.let { m -> hour = m.groupValues[1].toInt(); pm = true }
        if (hour == null && NOON.containsMatchIn(s)) hour = 12
        if (hour == null && MIDNIGHT.containsMatchIn(s)) hour = 24
        if (hour == null && (dayOffset != null || date != null)) DAY_THEN_HOUR.findAll(s).firstOrNull { m ->
            val w = m.groupValues[1]
            w in listOf("tomorrow", "yarın", "yarin", "today", "bugün", "bugun") || WEEKDAYS.values.any { w in it }
        }?.let { m -> hour = m.groupValues[2].toInt(); bare = true }
        if (pm == null && evening) pm = true
        if (pm == null && morning) pm = false

        val h0 = hour
        if (h0 == null && dayOffset == null && date == null && !evening && !morning) return null
        if (minute > 59) return null
        var extra = 0                      // "24:00": the midnight at the end of that day
        var h = when {
            h0 == null -> if (evening) 20 else 9
            h0 == 24 && minute == 0 -> { extra = 1; pm = null; bare = false; 0 }
            h0 > 23 -> return null
            else -> h0
        }
        if (h0 != null && extra == 0) {
            if (pm != null && h > 12) pm = null
            if (pm == true && h < 12) h += 12
            if (pm == false && h == 12) h = 0
            // a bare 1–5 on a named day ("tomorrow 3", "cuma 4'te") is the afternoon, not the middle of the night
            if (pm == null && bare && h in 1..5 && ((dayOffset ?: 0) >= 1 || date != null)) h += 12
        }

        val dt = date
        if (dt != null) {
            val thisYear = base.get(Calendar.YEAR)
            // this year's, else next year's (29 Feb: only in a leap year); a year written out is that year only
            for (y in if (dt.y != null) listOf(dt.y) else listOf(thisYear, thisYear + 1)) {
                if (y < thisYear || y > thisYear + 10) continue
                val c = Calendar.getInstance().apply { clear(); set(y, dt.m, 1) }
                if (dt.d !in 1..c.getActualMaximum(Calendar.DAY_OF_MONTH)) continue
                c.set(Calendar.DAY_OF_MONTH, dt.d)
                c.set(Calendar.HOUR_OF_DAY, h)
                c.set(Calendar.MINUTE, minute)
                if (extra > 0) c.add(Calendar.DAY_OF_YEAR, extra)
                if (c.timeInMillis > now) return c.timeInMillis
            }
            return null
        }
        // (the hour is set, not added: adding 12 h on a daylight-saving day would land an hour off)
        fun at(day: Int, hr: Int) = (base.clone() as Calendar).apply {
            add(Calendar.DAY_OF_YEAR, day)
            set(Calendar.HOUR_OF_DAY, hr); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val off = dayOffset
        if (off != null) {
            val t = at(off + extra, h)
            // "today 5" said at 14:00 means 17:00
            if (off == 0 && bare && pm == null && h in 1..11 && t <= now) return at(0, h + 12)
            return t
        }
        // a time with no day: the next time it comes round ("at 5" at 14:00 = 17:00; at 18:00 = 05:00 tomorrow)
        val today = at(extra, h)
        if (today > now) return today
        if (bare && pm == null && h in 1..11) at(0, h + 12).let { if (it > now) return it }
        return at(1 + extra, h)
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
            m / (24 * 60) == 1L -> tr("1 day", "1 gün")
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
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}
