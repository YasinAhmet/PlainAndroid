package app.plain.maps

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt

/** Web-Mercator helpers. "Normalised" coordinates run 0..1 across the world (x east, y south). */
object Geo {
    const val R = 6371008.8
    const val MAX_LAT = 85.05112878

    fun mx(lon: Double) = (lon + 180.0) / 360.0

    fun my(lat: Double): Double {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT)))
        return 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    }

    fun lon(mx: Double) = mx * 360.0 - 180.0
    fun lat(my: Double) = Math.toDegrees(atan(sinh(PI * (1 - 2 * my))))

    /** Great-circle distance in metres. */
    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Metres covered by one unit of normalised x at this latitude. */
    fun metersPerUnit(lat: Double) = 2 * PI * R * cos(Math.toRadians(lat))

    fun tileX(lon: Double, z: Int): Int {
        val n = 1 shl z
        return floor(mx(lon) * n).toInt().coerceIn(0, n - 1)
    }

    fun tileY(lat: Double, z: Int): Int {
        val n = 1 shl z
        return floor(my(lat) * n).toInt().coerceIn(0, n - 1)
    }

    fun tileCount(b: BBox, z0: Int, z1: Int): Long {
        var t = 0L
        for (z in z0..z1) {
            t += (tileX(b.e, z) - tileX(b.w, z) + 1).toLong() * (tileY(b.s, z) - tileY(b.n, z) + 1)
        }
        return t
    }

    /** Ground resolution of a native 256 px tile pixel, metres. */
    fun metersPerPixel(lat: Double, z: Int) = 156543.03392 * cos(Math.toRadians(lat)) / (1 shl z)
}

/** Lon/lat box: west, south, east, north. */
data class BBox(val w: Double, val s: Double, val e: Double, val n: Double) {
    fun intersects(o: BBox) = w <= o.e && e >= o.w && s <= o.n && n >= o.s
    fun union(o: BBox) = BBox(min(w, o.w), min(s, o.s), max(e, o.e), max(n, o.n))
    val centerLat get() = (s + n) / 2
    val centerLon get() = (w + e) / 2

    fun pad(meters: Double): BBox {
        val dLat = meters / 111_320.0
        val dLon = meters / (111_320.0 * cos(Math.toRadians(centerLat)).coerceAtLeast(0.01))
        return BBox(w - dLon, (s - dLat).coerceAtLeast(-Geo.MAX_LAT), e + dLon, (n + dLat).coerceAtMost(Geo.MAX_LAT))
    }

    fun widthM() = Geo.dist(centerLat, w, centerLat, e)
    fun heightM() = Geo.dist(s, centerLon, n, centerLon)

    fun encode() = "$w,$s,$e,$n"

    companion object {
        fun decode(str: String?): BBox? {
            val p = str?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
            return if (p.size == 4) BBox(p[0], p[1], p[2], p[3]) else null
        }
    }
}
