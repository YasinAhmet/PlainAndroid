package app.plain.maps

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Geometry on interleaved x,y DoubleArrays: simplify, clip lines and rings to rectangles. */
object Clip {

    /** Douglas–Peucker. Keeps the end points; closed rings keep their closing point. */
    fun simplify(xy: DoubleArray, tol: Double): DoubleArray {
        val n = xy.size / 2
        if (n <= 2 || tol <= 0) return xy
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = IntArray(2 * n + 4)
        var sp = 0
        stack[sp++] = 0; stack[sp++] = n - 1
        val t2 = tol * tol
        while (sp > 0) {
            val b = stack[--sp]; val a = stack[--sp]
            if (b <= a + 1) continue
            val ax = xy[2 * a]; val ay = xy[2 * a + 1]
            val bx = xy[2 * b]; val by = xy[2 * b + 1]
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var best = -1.0; var bi = -1
            for (i in a + 1 until b) {
                val px = xy[2 * i] - ax; val py = xy[2 * i + 1] - ay
                val d2 = if (len2 == 0.0) px * px + py * py else {
                    val t = ((px * dx + py * dy) / len2).coerceIn(0.0, 1.0)
                    val ex = px - t * dx; val ey = py - t * dy
                    ex * ex + ey * ey
                }
                if (d2 > best) { best = d2; bi = i }
            }
            if (best > t2) {
                keep[bi] = true
                stack[sp++] = a; stack[sp++] = bi
                stack[sp++] = bi; stack[sp++] = b
            }
        }
        var m = 0
        for (k in keep) if (k) m++
        val out = DoubleArray(m * 2)
        var j = 0
        for (i in 0 until n) if (keep[i]) { out[j++] = xy[2 * i]; out[j++] = xy[2 * i + 1] }
        return out
    }

    /** Signed area (shoelace); positive = counter-clockwise in a y-up system. */
    fun area(xy: DoubleArray): Double {
        val n = xy.size / 2
        var s = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            s += xy[2 * i] * xy[2 * j + 1] - xy[2 * j] * xy[2 * i + 1]
        }
        return s / 2
    }

    fun bounds(xy: DoubleArray): DoubleArray {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        var i = 0
        while (i < xy.size) {
            val x = xy[i]; val y = xy[i + 1]
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
            i += 2
        }
        return doubleArrayOf(x0, y0, x1, y1)
    }

    /** Pieces of a polyline inside the rectangle (Liang–Barsky per segment, joined where continuous). */
    fun clipLine(xy: DoubleArray, x0: Double, y0: Double, x1: Double, y1: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val n = xy.size / 2
        if (n < 2) return out
        var cur: DoubleList? = null
        for (i in 0 until n - 1) {
            val ax = xy[2 * i]; val ay = xy[2 * i + 1]; val bx = xy[2 * i + 2]; val by = xy[2 * i + 3]
            val dx = bx - ax; val dy = by - ay
            var t0 = 0.0; var t1 = 1.0
            var ok = true
            fun edge(p: Double, q: Double) {
                if (!ok) return
                if (p == 0.0) { if (q < 0) ok = false; return }
                val r = q / p
                if (p < 0) { if (r > t1) ok = false else if (r > t0) t0 = r }
                else { if (r < t0) ok = false else if (r < t1) t1 = r }
            }
            edge(-dx, ax - x0); edge(dx, x1 - ax); edge(-dy, ay - y0); edge(dy, y1 - ay)
            if (!ok) {
                cur?.let { if (it.size >= 4) out.add(it.toArray()) }
                cur = null
                continue
            }
            val sx = ax + t0 * dx; val sy = ay + t0 * dy
            val ex = ax + t1 * dx; val ey = ay + t1 * dy
            if (cur != null && t0 == 0.0) {
                cur.add(ex, ey)
            } else {
                cur?.let { if (it.size >= 4) out.add(it.toArray()) }
                cur = DoubleList(16).also { it.add(sx, sy); it.add(ex, ey) }
            }
            if (t1 < 1.0) {
                val c = cur
                if (c != null && c.size >= 4) out.add(c.toArray())
                cur = null
            }
        }
        cur?.let { if (it.size >= 4) out.add(it.toArray()) }
        return out
    }

    /** Sutherland–Hodgman: a ring clipped to the rectangle (null if nothing is left). */
    fun clipRing(xy: DoubleArray, x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray? {
        val b = bounds(xy)
        if (b[0] >= x0 && b[2] <= x1 && b[1] >= y0 && b[3] <= y1) return xy
        if (b[2] < x0 || b[0] > x1 || b[3] < y0 || b[1] > y1) return null
        var p = xy
        for (side in 0 until 4) {
            val n = p.size / 2
            if (n == 0) return null
            val o = DoubleList(p.size + 8)
            fun inside(x: Double, y: Double) = when (side) {
                0 -> x >= x0; 1 -> x <= x1; 2 -> y >= y0; else -> y <= y1
            }
            fun cross(ax: Double, ay: Double, bx: Double, by: Double) {
                val t = when (side) {
                    0 -> (x0 - ax) / (bx - ax); 1 -> (x1 - ax) / (bx - ax)
                    2 -> (y0 - ay) / (by - ay); else -> (y1 - ay) / (by - ay)
                }
                o.add(ax + t * (bx - ax), ay + t * (by - ay))
            }
            var px = p[2 * n - 2]; var py = p[2 * n - 1]
            var pin = inside(px, py)
            for (i in 0 until n) {
                val cx = p[2 * i]; val cy = p[2 * i + 1]
                val cin = inside(cx, cy)
                if (cin) {
                    if (!pin) cross(px, py, cx, cy)
                    o.add(cx, cy)
                } else if (pin) cross(px, py, cx, cy)
                px = cx; py = cy; pin = cin
            }
            p = o.toArray()
        }
        return if (p.size >= 6) p else null
    }

    /**
     * Cuts a polygon (rings filled even-odd) into the cells of grid [z] (cells are 1/2^z of the
     * normalised world). Works top-down, so a huge polygon is never clipped against every cell.
     * [pad] grows each cell by that fraction of its size.
     */
    fun splitPolygon(rings: List<DoubleArray>, z: Int, pad: Double, out: (Int, Int, List<DoubleArray>) -> Unit) {
        if (rings.isEmpty()) return
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (r in rings) { val b = bounds(r); x0 = min(x0, b[0]); y0 = min(y0, b[1]); x1 = max(x1, b[2]); y1 = max(y1, b[3]) }
        // the deepest level where the whole polygon sits in one cell
        var sz = 0
        while (sz < z) {
            val n = (1 shl (sz + 1)).toDouble()
            if (kotlin.math.floor(x0 * n) != kotlin.math.floor(x1 * n) || kotlin.math.floor(y0 * n) != kotlin.math.floor(y1 * n)) break
            sz++
        }
        val n = (1 shl sz).toDouble()
        split(rings, sz, kotlin.math.floor(x0 * n).toInt(), kotlin.math.floor(y0 * n).toInt(), z, pad, out)
    }

    private fun split(rings: List<DoubleArray>, cz: Int, cx: Int, cy: Int, z: Int, pad: Double, out: (Int, Int, List<DoubleArray>) -> Unit) {
        if (cz == z) { out(cx, cy, rings); return }
        val n = (1 shl (cz + 1)).toDouble()
        for (dy in 0..1) for (dx in 0..1) {
            val x = cx * 2 + dx; val y = cy * 2 + dy
            // children are padded by the final cell's pad, so the last level gets exactly that
            val p = pad / (1 shl z)
            val kids = rings.mapNotNull { clipRing(it, x / n - p, y / n - p, (x + 1) / n + p, (y + 1) / n + p) }
            if (kids.isNotEmpty()) split(kids, cz + 1, x, y, z, pad, out)
        }
    }

    fun samePoint(xy: DoubleArray, i: Int, j: Int) = xy[2 * i] == xy[2 * j] && xy[2 * i + 1] == xy[2 * j + 1]

    fun closed(xy: DoubleArray) = xy.size >= 8 && abs(xy[0] - xy[xy.size - 2]) < 1e-12 && abs(xy[1] - xy[xy.size - 1]) < 1e-12
}

/** A growable list of doubles. */
class DoubleList(cap: Int = 16) {
    private var a = DoubleArray(cap)
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(a.size * 2 + 2)
        a[size++] = v
    }

    fun add(x: Double, y: Double) { add(x); add(y) }
    operator fun get(i: Int) = a[i]
    fun toArray(): DoubleArray = a.copyOf(size)
    fun clear() { size = 0 }
}
