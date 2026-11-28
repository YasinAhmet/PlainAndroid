package app.plain.maps

import java.io.BufferedReader
import java.nio.charset.Charset

/** Text repairs for the İBB open-data files, CSV splitting and search normalisation. */
object Text {
    private val CP1252: Charset = Charset.forName("windows-1252")

    /**
     * Some İETT files are UTF-8 that was read as Windows-1252 and saved again as UTF-8
     * ("KADIKÃ–Y" for "KADIKÖY"). Turn such text back into what it was.
     */
    fun fixMojibake(s: String): String {
        if (s.none { it == 'Ã' || it == 'Ä' || it == 'Å' || it == 'Â' }) return s
        val out = java.io.ByteArrayOutputStream(s.length)
        val enc = CP1252.newEncoder()
        for (ch in s) {
            if (ch.code < 128) out.write(ch.code)
            else if (enc.canEncode(ch)) out.write(enc.encode(java.nio.CharBuffer.wrap(charArrayOf(ch))).get().toInt() and 0xFF)
            else if (ch.code < 256) out.write(ch.code)
            else return s // not the pattern we know
        }
        val b = out.toByteArray()
        val dec = Charsets.UTF_8.newDecoder()
        return try { dec.decode(java.nio.ByteBuffer.wrap(b)).toString() } catch (_: Exception) { s }
    }

    /**
     * A coordinate in an İETT CSV. They are normally "41.0191700005564", but the published
     * files went through a spreadsheet that turned them into "410.191.700.005.564".
     * [lo]..[hi] is the plausible range for the city, which also decides where the point goes.
     */
    fun coord(raw: String, lo: Double, hi: Double): Double? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        s.replace(',', '.').toDoubleOrNull()?.let { if (it in lo..hi) return it }
        val digits = s.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val neg = s.startsWith("-")
        for (intLen in 1..3) {
            if (digits.length <= intLen) break
            val v = (digits.substring(0, intLen) + "." + digits.substring(intLen)).toDoubleOrNull() ?: continue
            val x = if (neg) -v else v
            if (x in lo..hi) return x
        }
        return null
    }

    /**
     * "06:40:00" (or past midnight, "25:10:00") to seconds; null if empty or not a time (minutes or seconds out of
     * range, negative or absurd hours: a garbled cell must not become a departure days away).
     */
    fun time(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val p = t.split(':')
        if (p.size < 2) return null
        val h = p[0].trim().toIntOrNull() ?: return null
        val m = p[1].trim().toIntOrNull() ?: return null
        val sec = p.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
        if (h !in 0..99 || m !in 0..59 || sec !in 0..60) return null
        return h * 3600 + m * 60 + sec
    }

    /**
     * Splits one CSV line. A field that starts with a quote (after blanks) is quoted: delimiters inside it are text and
     * "" is a quote. A stray quote inside an unquoted field ("12\" BUS") is plain text, so it can't swallow the
     * columns after it. The İETT files use no quotes at all (the fast path).
     */
    fun split(line: String, d: Char): MutableList<String> {
        val out = ArrayList<String>(12)
        if (line.indexOf('"') < 0) {
            var start = 0
            while (true) {
                val i = line.indexOf(d, start)
                if (i < 0) { out.add(line.substring(start)); break }
                out.add(line.substring(start, i))
                start = i + 1
            }
            return out
        }
        scan(line, d, out)
        return out
    }

    /** True when [line] ends inside a quoted field (the field goes on in the next line). */
    fun openQuote(line: String, d: Char): Boolean = line.indexOf('"') >= 0 && scan(line, d, null)

    /** The quote-aware splitter; returns true if the line ends inside a quoted field. */
    private fun scan(line: String, d: Char, out: MutableList<String>?): Boolean {
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') { sb.append('"'); i++ } else q = false
                } else sb.append(c)
            } else when {
                c == d -> { out?.add(sb.toString()); sb.setLength(0) }
                c == '"' && sb.isBlank() -> { sb.setLength(0); q = true }
                else -> sb.append(c)
            }
            i++
        }
        out?.add(sb.toString())
        return q
    }

    /**
     * A numeric character reference ("&#351;", "&#x15F;"): its text, or null when it is not a valid code point
     * (a long number must not throw, and emoji above U+FFFF need two chars).
     */
    fun entity(digits: String, radix: Int): String? {
        val v = digits.toIntOrNull(radix) ?: return null
        if (v <= 0 || v > 0x10FFFF || v in 0xD800..0xDFFF) return null
        return String(Character.toChars(v))
    }

    /**
     * A CSV file read by column name. Rows with more fields than the header (a stray
     * delimiter inside free text) have the surplus merged into [freeText] so the columns
     * after it still line up.
     */
    class Csv(private val r: BufferedReader, private val freeText: String? = null) {
        val header: List<String>
        val delim: Char
        private val idx = HashMap<String, Int>()

        init {
            val h = (r.readLine() ?: "").removePrefix("﻿")
            delim = if (h.count { it == ';' } > h.count { it == ',' }) ';' else ','
            header = split(h, delim).map { it.trim().lowercase() }.let { cols ->
                // a trailing delimiter makes an empty last column
                if (cols.isNotEmpty() && cols.last().isEmpty()) cols.dropLast(1) else cols
            }
            header.forEachIndexed { i, s -> idx[s] = i }
        }

        fun has(col: String) = idx.containsKey(col)
        fun col(name: String) = idx[name] ?: -1

        /** Next row, or null at the end. Values are trimmed and mojibake-repaired. */
        fun next(): List<String>? {
            while (true) {
                var line = r.readLine() ?: return null
                if (line.isBlank()) continue
                if (openQuote(line, delim)) line = continued(line)
                var f = split(line, delim)
                // drop a trailing empty field from a trailing delimiter
                if (f.size == header.size + 1 && f.last().isEmpty()) f = f.subList(0, f.size - 1)
                if (f.size > header.size && freeText != null) {
                    val at = idx[freeText]
                    if (at != null) {
                        val extra = f.size - header.size
                        val merged = f.subList(at, at + extra + 1).joinToString(delim.toString())
                        val g = ArrayList<String>(header.size)
                        g.addAll(f.subList(0, at)); g.add(merged); g.addAll(f.subList(at + extra + 1, f.size))
                        f = g
                    }
                }
                return f.map { fixMojibake(it.trim()) }
            }
        }

        fun get(row: List<String>, col: Int) = if (col in row.indices) row[col] else ""

        /**
         * A quoted field with line breaks in it: the row goes on until the quote closes. If it doesn't close within a
         * few lines (a stray quote, not a real field), the line stays alone and the lines after it are read as rows.
         */
        private fun continued(first: String): String {
            val sb = StringBuilder(first)
            try {
                r.mark(MARK)
                for (k in 0 until 20) {
                    val more = r.readLine() ?: break
                    sb.append('\n').append(more)
                    if (sb.length > MARK / 4) break
                    if (!openQuote(sb.toString(), delim)) return sb.toString()
                }
                r.reset()
            } catch (_: java.io.IOException) {
                // a line longer than the read-ahead: keep what was joined rather than fail the whole file
                return sb.toString()
            }
            return first
        }
    }

    private const val MARK = 1 shl 20

    /** Lower-case, Turkish letters folded to ASCII, punctuation to spaces: used for search. */
    fun norm(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when (ch) {
                'İ', 'I', 'ı', 'i', 'Î', 'î', 'Ì', 'í', 'ì' -> 'i'
                'Ş', 'ş' -> 's'
                'Ğ', 'ğ' -> 'g'
                'Ü', 'ü', 'Û', 'û' -> 'u'
                'Ö', 'ö' -> 'o'
                'Ç', 'ç' -> 'c'
                'Â', 'â', 'Ä', 'ä', 'á', 'à' -> 'a'
                'é', 'è', 'ê' -> 'e'
                else -> ch.lowercaseChar()
            }
            sb.append(if (c.isLetterOrDigit()) c else ' ')
        }
        return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** "KADIKÖY - KİRAZLITEPE" → "Kadıköy - Kirazlıtepe" (Turkish casing). */
    fun title(s: String): String {
        if (s.any { it.isLowerCase() }) return s
        val tr = java.util.Locale.forLanguageTag("tr")
        val sb = StringBuilder(s.length)
        var start = true
        for (ch in s) {
            if (ch.isLetter()) {
                sb.append(if (start) ch.toString().uppercase(tr) else ch.toString().lowercase(tr))
                start = false
            } else {
                sb.append(ch)
                start = ch == ' ' || ch == '-' || ch == '(' || ch == '/' || ch == '.'
            }
        }
        return sb.toString()
    }
}
