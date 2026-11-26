package app.trekmap.trail

import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sqrt

/** A set of tiles at one zoom, stored as a bit grid (column-major, so each column is one contiguous run). */
class TileGrid(val z: Int, val x0: Int, val y0: Int, val w: Int, val h: Int) {
    val bits = BitSet(maxOf(1, w * h))

    fun count(): Long = bits.cardinality().toLong()

    fun contains(x: Int, y: Int) = x in x0 until x0 + w && y in y0 until y0 + h && bits[(x - x0) * h + (y - y0)]

    internal fun setColumn(x: Int, yFrom: Int, yTo: Int) {
        if (x < x0 || x >= x0 + w) return
        val a = maxOf(yFrom, y0)
        val b = minOf(yTo, y0 + h - 1)
        if (a > b) return
        val base = (x - x0) * h - y0
        bits.set(base + a, base + b + 1)
    }

    /** Visits tiles column by column (x ascending, then y ascending). */
    inline fun forEach(fn: (x: Int, y: Int) -> Unit) {
        var i = bits.nextSetBit(0)
        while (i >= 0) {
            fn(x0 + i / h, y0 + i % h)
            i = bits.nextSetBit(i + 1)
        }
    }
}

/**
 * Tiles within a distance of a trail ("corridor"). Downloading only these instead of the trail's
 * whole bounding box keeps high-detail maps of long trails small.
 */
object Corridor {
    /** The grid would need too much memory (very long trail at very high zoom): treat as "won't fit". */
    class TooLarge(val cells: Long) : RuntimeException("corridor grid too large: $cells cells")

    /** 200 M cells = 25 MB of bits; anything bigger is far beyond what fits on a phone anyway. */
    private const val MAX_CELLS = 200_000_000L

    fun grid(segs: List<Seg>, bufferM: Double, z: Int): TileGrid {
        val n = 1 shl z
        val box = Route(null, segs).bbox()?.pad(bufferM + 1.0) ?: return TileGrid(z, 0, 0, 0, 0)
        val x0 = Geo.tileX(box.w, z); val x1 = Geo.tileX(box.e, z)
        val y0 = Geo.tileY(box.n, z); val y1 = Geo.tileY(box.s, z)
        val cells = (x1 - x0 + 1).toLong() * (y1 - y0 + 1)
        if (cells > MAX_CELLS) throw TooLarge(cells)
        val g = TileGrid(z, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
        for (s in segs) {
            if (s.size == 0) continue
            var px = Geo.mx(s.lon[0]) * n
            var py = Geo.my(s.lat[0]) * n
            stamp(g, px, py, radius(s.lat[0], bufferM, n))
            for (i in 1 until s.size) {
                val x = Geo.mx(s.lon[i]) * n
                val y = Geo.my(s.lat[i]) * n
                val r = radius(s.lat[i], bufferM, n)
                if (kotlin.math.abs(s.lon[i] - s.lon[i - 1]) > 180) { // crosses the 180° meridian: don't paint around the world
                    stamp(g, x, y, r); px = x; py = y; continue
                }
                val steps = ceil(hypot(x - px, y - py) / 0.5).toInt().coerceAtLeast(1) // half-tile steps
                for (k in 1..steps) {
                    val t = k.toDouble() / steps
                    stamp(g, px + (x - px) * t, py + (y - py) * t, r)
                }
                px = x; py = y
            }
        }
        return g
    }

    /** Buffer in tile units (+ margin so half-tile sampling never leaves gaps; always covers the trail's own tiles). */
    private fun radius(lat: Double, bufferM: Double, n: Int): Double {
        val metersPerTile = Geo.metersPerUnit(lat) / n
        return maxOf(bufferM / metersPerTile, 0.0) + 0.3
    }

    /** Marks every tile whose square is within [r] (tile units) of the point. */
    private fun stamp(g: TileGrid, cx: Double, cy: Double, r: Double) {
        val ixFrom = floor(cx - r).toInt()
        val ixTo = floor(cx + r).toInt()
        for (ix in ixFrom..ixTo) {
            val dx = when {
                cx < ix -> ix - cx
                cx > ix + 1 -> cx - (ix + 1)
                else -> 0.0
            }
            if (dx > r) continue
            val hh = sqrt(r * r - dx * dx)
            g.setColumn(ix, floor(cy - hh).toInt(), floor(cy + hh).toInt())
        }
    }

    /** Thins a trail for storage: keeps a point every [minStepM] metres (plus segment ends). */
    fun simplify(segs: List<Seg>, minStepM: Double): List<Seg> = segs.filter { it.size > 0 }.map { s ->
        val la = DoubleList(); val lo = DoubleList()
        la.add(s.lat[0]); lo.add(s.lon[0])
        for (i in 1 until s.size) {
            val last = i == s.size - 1
            if (last || Geo.dist(la[la.size - 1], lo[lo.size - 1], s.lat[i], s.lon[i]) >= minStepM) {
                la.add(s.lat[i]); lo.add(s.lon[i])
            }
        }
        Seg(la.toArray(), lo.toArray())
    }

    fun encode(segs: List<Seg>): String = segs.joinToString("|") { s ->
        (0 until s.size).joinToString(";") { "${s.lat[it]},${s.lon[it]}" }
    }

    fun decode(str: String): List<Seg> = str.split('|').filter { it.isNotBlank() }.map { part ->
        val la = DoubleList(); val lo = DoubleList()
        for (p in part.split(';')) {
            val c = p.split(',')
            val a = c.getOrNull(0)?.toDoubleOrNull() ?: continue
            val b = c.getOrNull(1)?.toDoubleOrNull() ?: continue
            la.add(a); lo.add(b)
        }
        Seg(la.toArray(), lo.toArray())
    }
}
