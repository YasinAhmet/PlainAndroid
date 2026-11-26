package app.plain.maps

import kotlin.math.floor
import kotlin.math.roundToLong

/** Feature classes. The number is also the drawing order inside a cell. */
object K {
    const val LAND = 1
    const val WATER = 2
    const val PARK = 3
    const val FOREST = 4
    const val CEMETERY = 5
    const val PATH = 10
    const val SERVICE = 11
    const val MINOR = 12
    const val TERTIARY = 13
    const val SECONDARY = 14
    const val PRIMARY = 15
    const val TRUNK = 16
    const val MOTORWAY = 17
    const val RAILWAY = 20
    const val BUS = 30
    const val FERRY = 31
    const val RAIL = 32
    const val STOP = 40
    const val STATION = 41
    const val POI = 42
    const val PLACE = 43

    fun isPolygon(k: Int) = k in LAND..CEMETERY
}

/**
 * Three levels of detail, each cut into a grid of cells (a cell = one web-mercator tile at that
 * zoom). Tiles at zoom ≤10 draw from level 0 (grid z9), 11–13 from level 1 (z12), 14+ from level 2 (z14).
 */
object Levels {
    val Z = intArrayOf(9, 12, 14)
    fun forTile(tz: Int) = if (tz <= 10) 0 else if (tz <= 13) 1 else 2

    /** Simplification tolerance (normalised units): well under a pixel at the deepest zoom using the level. */
    val TOL = doubleArrayOf(0.7 / (256.0 * 1024), 0.7 / (256.0 * 8192), 0.7 / (256.0 * 262144))

    /** Polygons smaller than this (normalised units²) are left out of a level. */
    val MIN_AREA = doubleArrayOf(
        (6.0 / (256.0 * 1024)).let { it * it },
        (4.0 / (256.0 * 8192)).let { it * it },
        0.0,
    )

    /** Coordinates inside a cell run 0..UNITS. */
    const val UNITS = 65536.0

    /** Each cell holds its features a little past its edges, so strokes meet without seams. */
    const val PAD = 1.0 / 64
}

/** A decoded feature; coordinates are cell-local, 0..1 across the cell. */
class Feature(
    @JvmField val kind: Int,
    @JvmField val name: String?,
    @JvmField val color: Int,
    @JvmField val ref: Long,
    @JvmField val xy: FloatArray,
    /** Polygon ring starts (indices into xy, in floats); null for lines and points. */
    @JvmField val rings: IntArray?,
    @JvmField val point: Boolean,
) {
    @JvmField var x0 = 0f
    @JvmField var y0 = 0f
    @JvmField var x1 = 0f
    @JvmField var y1 = 0f
}

object Cells {
    private const val F_NAME = 1
    private const val F_POLY = 2
    private const val F_COLOR = 4
    private const val F_REF = 8
    private const val F_POINT = 16

    private fun lx(x: Double, z: Int, cx: Int) = ((x * (1 shl z) - cx) * Levels.UNITS).roundToLong()

    /** Encodes one feature for cell (lvl, cx, cy). Coordinates are normalised mercator. */
    fun encode(
        o: BytesOut, kind: Int, lvl: Int, cx: Int, cy: Int,
        name: String? = null, color: Int? = null, ref: Long? = null,
        line: DoubleArray? = null, rings: List<DoubleArray>? = null, px: Double = 0.0, py: Double = 0.0,
    ) {
        val z = Levels.Z[lvl]
        var flags = 0
        if (!name.isNullOrEmpty()) flags = flags or F_NAME
        if (rings != null) flags = flags or F_POLY
        if (color != null) flags = flags or F_COLOR
        if (ref != null) flags = flags or F_REF
        if (line == null && rings == null) flags = flags or F_POINT
        o.u8(kind); o.u8(flags)
        if (!name.isNullOrEmpty()) o.str(name)
        if (color != null) o.i32(color)
        if (ref != null) o.uv(ref)
        fun pts(a: DoubleArray) {
            val n = a.size / 2
            o.uv(n)
            var pxl = 0L; var pyl = 0L
            for (i in 0 until n) {
                val x = lx(a[2 * i], z, cx); val y = lx(a[2 * i + 1], z, cy)
                o.sv(x - pxl); o.sv(y - pyl)
                pxl = x; pyl = y
            }
        }
        when {
            rings != null -> { o.uv(rings.size); for (r in rings) pts(r) }
            line != null -> pts(line)
            else -> { o.sv(lx(px, z, cx)); o.sv(lx(py, z, cy)) }
        }
    }

    fun decode(b: ByteArray): List<Feature> {
        val r = BytesIn(b)
        val out = ArrayList<Feature>()
        val inv = (1.0 / Levels.UNITS).toFloat()
        while (r.more()) {
            val kind = r.u8()
            val flags = r.u8()
            val name = if (flags and F_NAME != 0) r.str() else null
            val color = if (flags and F_COLOR != 0) r.i32() else 0
            val ref = if (flags and F_REF != 0) r.uv() else 0L
            val f: Feature
            if (flags and F_POINT != 0) {
                val x = r.sv() * inv; val y = r.sv() * inv
                f = Feature(kind, name, color, ref, floatArrayOf(x, y), null, true)
            } else if (flags and F_POLY != 0) {
                val nr = r.ui()
                val starts = IntArray(nr)
                val all = ArrayList<FloatArray>(nr)
                var total = 0
                for (k in 0 until nr) {
                    starts[k] = total
                    val a = readPts(r, inv)
                    all.add(a); total += a.size
                }
                val xy = FloatArray(total)
                var o = 0
                for (a in all) { System.arraycopy(a, 0, xy, o, a.size); o += a.size }
                f = Feature(kind, name, color, ref, xy, starts, false)
            } else {
                f = Feature(kind, name, color, ref, readPts(r, inv), null, false)
            }
            var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
            val xy = f.xy
            var i = 0
            while (i < xy.size) {
                val x = xy[i]; val y = xy[i + 1]
                if (x < x0) x0 = x; if (x > x1) x1 = x
                if (y < y0) y0 = y; if (y > y1) y1 = y
                i += 2
            }
            f.x0 = x0; f.y0 = y0; f.x1 = x1; f.y1 = y1
            out.add(f)
        }
        return out
    }

    private fun readPts(r: BytesIn, inv: Float): FloatArray {
        val n = r.ui()
        val a = FloatArray(n * 2)
        var x = 0L; var y = 0L
        for (i in 0 until n) {
            x += r.sv(); y += r.sv()
            a[2 * i] = x * inv; a[2 * i + 1] = y * inv
        }
        return a
    }
}

/**
 * Cuts features into cells for every level they belong to and hands each encoded piece
 * to [sink] (level, cell x, cell y, kind, bytes). Coordinates are normalised mercator.
 */
class Pieces(private val sink: (Int, Int, Int, Int, ByteArray) -> Unit) {
    private val o = BytesOut(4096)

    fun line(kind: Int, xy: DoubleArray, minLvl: Int, name: String? = null, nameLvl: Int = 2, color: Int? = null, ref: Long? = null) {
        if (xy.size < 4) return
        for (lvl in minLvl..2) {
            val s = Clip.simplify(xy, Levels.TOL[lvl])
            if (s.size < 4) continue
            val z = Levels.Z[lvl]
            val nm = if (lvl >= nameLvl) name else null
            splitLine(s, z) { cx, cy, part ->
                o.clear()
                Cells.encode(o, kind, lvl, cx, cy, name = nm, color = color, ref = ref, line = part)
                sink(lvl, cx, cy, kind, o.toArray())
            }
        }
    }

    fun polygon(kind: Int, rings: List<DoubleArray>, minLvl: Int, color: Int? = null) {
        if (rings.isEmpty()) return
        for (lvl in minLvl..2) {
            val minA = Levels.MIN_AREA[lvl]
            val rs = rings.mapNotNull { r ->
                val s = Clip.simplify(r, Levels.TOL[lvl])
                if (s.size < 8 || kotlin.math.abs(Clip.area(s)) < minA) null else s
            }
            if (rs.isEmpty()) continue
            Clip.splitPolygon(rs, Levels.Z[lvl], Levels.PAD / 4) { cx, cy, parts ->
                o.clear()
                Cells.encode(o, kind, lvl, cx, cy, color = color, rings = parts)
                sink(lvl, cx, cy, kind, o.toArray())
            }
        }
    }

    fun point(kind: Int, x: Double, y: Double, minLvl: Int, maxLvl: Int = 2, name: String? = null, color: Int? = null, ref: Long? = null) {
        for (lvl in minLvl..maxLvl) {
            val n = (1 shl Levels.Z[lvl]).toDouble()
            val cx = floor(x * n).toInt(); val cy = floor(y * n).toInt()
            o.clear()
            Cells.encode(o, kind, lvl, cx, cy, name = name, color = color, ref = ref, px = x, py = y)
            sink(lvl, cx, cy, kind, o.toArray())
        }
    }

    /** Splits a line into the cells of grid z (top-down, like polygons). */
    private fun splitLine(xy: DoubleArray, z: Int, out: (Int, Int, DoubleArray) -> Unit) {
        val b = Clip.bounds(xy)
        var sz = 0
        while (sz < z) {
            val n = (1 shl (sz + 1)).toDouble()
            if (floor(b[0] * n) != floor(b[2] * n) || floor(b[1] * n) != floor(b[3] * n)) break
            sz++
        }
        val n = (1 shl sz).toDouble()
        rec(listOf(xy), sz, floor(b[0] * n).toInt(), floor(b[1] * n).toInt(), z, out)
    }

    private fun rec(parts: List<DoubleArray>, cz: Int, cx: Int, cy: Int, z: Int, out: (Int, Int, DoubleArray) -> Unit) {
        if (cz == z) { for (p in parts) out(cx, cy, p); return }
        val n = (1 shl (cz + 1)).toDouble()
        val p = Levels.PAD / (1 shl z)
        for (dy in 0..1) for (dx in 0..1) {
            val x = cx * 2 + dx; val y = cy * 2 + dy
            val kids = ArrayList<DoubleArray>()
            for (part in parts) kids.addAll(Clip.clipLine(part, x / n - p, y / n - p, (x + 1) / n + p, (y + 1) / n + p))
            if (kids.isNotEmpty()) rec(kids, cz + 1, x, y, z, out)
        }
    }

    companion object {
        /** lon/lat pairs → normalised mercator pairs. */
        fun merc(lonlat: DoubleArray): DoubleArray {
            val a = DoubleArray(lonlat.size)
            var i = 0
            while (i < a.size) { a[i] = Geo.mx(lonlat[i]); a[i + 1] = Geo.my(lonlat[i + 1]); i += 2 }
            return a
        }
    }
}
