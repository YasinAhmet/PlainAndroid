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

    /** "06:40:00" (or past midnight, "25:10:00") to seconds; null if empty. */
    fun time(s: String): Int? {
        val t = s.trim()
        if (t.isEmpty()) return null
        val p = t.split(':')
        if (p.size < 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val sec = p.getOrNull(2)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60 + sec
    }

    /** Splits one CSV line (quotes are honoured, though the İETT files don't use them). */
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
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                !q && c == d -> { out.add(sb.toString()); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
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
                val line = r.readLine() ?: return null
                if (line.isBlank()) continue
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
    }

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
