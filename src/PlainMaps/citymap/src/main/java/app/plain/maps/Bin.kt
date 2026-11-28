package app.plain.maps

import kotlin.math.roundToLong

/** Growable byte buffer with varints (all stored data uses these). */
class BytesOut(cap: Int = 1024) {
    var buf = ByteArray(cap)
        private set
    var size = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int) { ensure(1); buf[size++] = v.toByte() }

    /** Unsigned varint (v must be >= 0). */
    fun uv(v0: Long) {
        var v = v0
        ensure(10)
        while (v and 0x7FL.inv() != 0L) {
            buf[size++] = ((v and 0x7F) or 0x80).toByte()
            v = v ushr 7
        }
        buf[size++] = v.toByte()
    }

    fun uv(v: Int) = uv(v.toLong())

    /** Signed (zig-zag) varint. */
    fun sv(v: Long) = uv((v shl 1) xor (v shr 63))

    fun i32(v: Int) {
        ensure(4)
        buf[size++] = (v ushr 24).toByte(); buf[size++] = (v ushr 16).toByte()
        buf[size++] = (v ushr 8).toByte(); buf[size++] = v.toByte()
    }

    fun str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        uv(b.size)
        bytes(b, 0, b.size)
    }

    fun bytes(b: ByteArray, off: Int, len: Int) {
        ensure(len)
        System.arraycopy(b, off, buf, size, len)
        size += len
    }

    fun toArray(): ByteArray = buf.copyOf(size)
    fun clear() { size = 0 }
}

class BytesIn(private val b: ByteArray, var pos: Int = 0, private val end: Int = b.size) {
    fun more() = pos < end
    fun u8() = b[pos++].toInt() and 0xFF

    fun uv(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val x = b[pos++].toInt()
            r = r or ((x and 0x7F).toLong() shl s)
            if (x >= 0) return r
            s += 7
        }
    }

    fun ui() = uv().toInt()

    fun sv(): Long {
        val v = uv()
        return (v ushr 1) xor -(v and 1)
    }

    fun i32(): Int {
        val v = ((b[pos].toInt() and 0xFF) shl 24) or ((b[pos + 1].toInt() and 0xFF) shl 16) or
            ((b[pos + 2].toInt() and 0xFF) shl 8) or (b[pos + 3].toInt() and 0xFF)
        pos += 4
        return v
    }

    fun str(): String {
        val n = ui()
        val s = String(b, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }
}

/** Small blobs stored in the database. */
object Codec {
    /** Lines as lon/lat pairs, stored as 1e-6° deltas. Points that aren't coordinates (NaN, infinite) are left out. */
    fun shape(parts: List<DoubleArray>): ByteArray {
        val o = BytesOut(64 + parts.sumOf { it.size } * 3)
        o.uv(parts.size)
        for (p0 in parts) {
            // roundToLong() throws on NaN: one bad point from a feed must not fail a whole sync
            val p = if (p0.all { ok(it) }) p0 else p0.toList().chunked(2).filter { it.size == 2 && ok(it[0]) && ok(it[1]) }.flatten().toDoubleArray()
            val n = p.size / 2
            o.uv(n)
            var px = 0L; var py = 0L
            for (i in 0 until n) {
                val x = (p[2 * i] * 1e6).roundToLong(); val y = (p[2 * i + 1] * 1e6).roundToLong()
                o.sv(x - px); o.sv(y - py)
                px = x; py = y
            }
        }
        return o.toArray()
    }

    private fun ok(v: Double) = v.isFinite() && kotlin.math.abs(v) <= 1000.0

    fun shape(b: ByteArray): List<DoubleArray> {
        val r = BytesIn(b)
        val parts = ArrayList<DoubleArray>()
        repeat(r.ui()) {
            val n = r.ui()
            val a = DoubleArray(n * 2)
            var x = 0L; var y = 0L
            for (i in 0 until n) {
                x += r.sv(); y += r.sv()
                a[2 * i] = x / 1e6; a[2 * i + 1] = y / 1e6
            }
            parts.add(a)
        }
        return parts
    }

    fun ints(v: IntArray): ByteArray {
        val o = BytesOut(v.size * 3 + 4)
        o.uv(v.size)
        var p = 0L
        for (x in v) { o.sv(x - p); p = x.toLong() }
        return o.toArray()
    }

    fun ints(b: ByteArray): IntArray {
        val r = BytesIn(b)
        val n = r.ui()
        val a = IntArray(n)
        var p = 0L
        for (i in 0 until n) { p += r.sv(); a[i] = p.toInt() }
        return a
    }
}
