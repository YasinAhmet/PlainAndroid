package app.plain.maps

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * A small streaming JSON reader. The sync reads files far too big to load whole
 * (the İETT route shapes are 250 MB), and it must also run in plain JVM unit tests.
 * The caller drives the structure: beginObject / hasNext / nextName / … / endObject.
 */
class Json(input: InputStream) : Closeable {
    enum class Kind { OBJECT, ARRAY, STRING, NUMBER, BOOL, NULL, END }

    private val reader: Reader = InputStreamReader(input, Charsets.UTF_8)
    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var lim = 0
    private val sb = StringBuilder(64)

    private fun fill(): Boolean {
        lim = reader.read(buf, 0, buf.size)
        pos = 0
        if (lim <= 0) { lim = 0; return false }
        return true
    }

    private fun peekCh(): Int {
        if (pos >= lim && !fill()) return -1
        return buf[pos].code
    }

    private fun ws(): Int {
        while (true) {
            val c = peekCh()
            if (c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code) pos++ else return c
        }
    }

    private fun expect(ch: Char) {
        val c = ws()
        if (c != ch.code) throw IOException("JSON: expected '$ch' but found ${if (c < 0) "end" else "'" + c.toChar() + "'"}")
        pos++
    }

    fun beginObject() = expect('{')
    fun endObject() = expect('}')
    fun beginArray() = expect('[')
    fun endArray() = expect(']')

    /** True if another element/member follows (consumes the separating comma). */
    fun hasNext(): Boolean {
        var c = ws()
        if (c == ','.code) { pos++; c = ws() }
        return c != '}'.code && c != ']'.code && c != -1
    }

    fun peek(): Kind = when (ws()) {
        '{'.code -> Kind.OBJECT
        '['.code -> Kind.ARRAY
        '"'.code -> Kind.STRING
        't'.code, 'f'.code -> Kind.BOOL
        'n'.code -> Kind.NULL
        -1 -> Kind.END
        else -> Kind.NUMBER
    }

    fun nextName(): String {
        val s = nextString()
        expect(':')
        return s
    }

    /** A string value (numbers and literals are returned as their text). */
    fun nextString(): String {
        val c = ws()
        if (c != '"'.code) return literal()
        pos++
        sb.setLength(0)
        while (true) {
            if (pos >= lim && !fill()) throw IOException("JSON: unterminated string")
            val ch = buf[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= lim && !fill()) throw IOException("JSON: bad escape")
                    when (val e = buf[pos++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            var v = 0
                            repeat(4) {
                                if (pos >= lim && !fill()) throw IOException("JSON: bad \\u escape")
                                v = v * 16 + Character.digit(buf[pos++], 16)
                            }
                            sb.append(v.toChar())
                        }
                        else -> sb.append(e)
                    }
                }
                else -> sb.append(ch)
            }
        }
    }

    private fun literal(): String {
        sb.setLength(0)
        while (true) {
            val c = peekCh()
            if (c < 0 || c == ','.code || c == '}'.code || c == ']'.code || c == ':'.code ||
                c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code
            ) break
            sb.append(c.toChar())
            pos++
        }
        if (sb.isEmpty()) throw IOException("JSON: unexpected character")
        return sb.toString()
    }

    fun nextDouble(): Double {
        val s = nextString()
        return s.toDoubleOrNull() ?: throw IOException("JSON: not a number: $s")
    }

    fun nextLong(): Long {
        val s = nextString()
        return s.toLongOrNull() ?: s.toDoubleOrNull()?.toLong() ?: throw IOException("JSON: not a number: $s")
    }

    /** Reads null as null; anything else as text. */
    fun nextStringOrNull(): String? = if (peek() == Kind.NULL) { literal(); null } else nextString()

    fun skipValue() {
        when (peek()) {
            Kind.OBJECT -> { beginObject(); while (hasNext()) { nextName(); skipValue() }; endObject() }
            Kind.ARRAY -> { beginArray(); while (hasNext()) skipValue(); endArray() }
            Kind.STRING -> nextString()
            Kind.END -> throw IOException("JSON: unexpected end")
            else -> literal()
        }
    }

    /** Reads a flat object of simple values into a map (nested values are skipped). */
    fun readStringMap(): HashMap<String, String> {
        val m = HashMap<String, String>()
        beginObject()
        while (hasNext()) {
            val k = nextName()
            when (peek()) {
                Kind.OBJECT, Kind.ARRAY -> skipValue()
                Kind.NULL -> literal()
                else -> m[k] = nextString()
            }
        }
        endObject()
        return m
    }

    override fun close() = reader.close()
}
