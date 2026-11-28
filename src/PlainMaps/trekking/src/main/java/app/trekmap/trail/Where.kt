package app.trekmap.trail

import java.util.Locale
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * Offline "where am I" maths for the position sheet (hold the ◎ button): coordinates to read out or send,
 * an Open Location Code ("plus code"), the way back to the trail's start and today's sunrise / sunset.
 * Pure functions, no network, no sensors.
 */
object Where {
    private const val ALPHABET = "23456789CFGHJMPQRVWX"

    /** "41.012340, 28.976540" (always '.' decimals: it's read out or pasted into other apps). */
    fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.6f, %.6f", lat, lon)

    /**
     * 10-digit Open Location Code (about 14 × 14 m), e.g. "8GGFX2VF+2X". Works offline; Google Maps and
     * rescue services understand it. Integer maths, as in the reference implementation, so cell edges don't wobble.
     */
    fun plusCode(lat0: Double, lon0: Double): String {
        var lat = lat0.coerceIn(-90.0, 90.0)
        var lon = ((lon0 + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        if (lat >= 90.0) lat = 90.0 - 0.000125 / 2
        // units of 1/8000° (the size of the last pair), rounded first to absorb floating-point noise
        var la = floor(((lat + 90.0) * 8000.0 * 1e6).roundToLong() / 1e6).toLong()
        var lo = floor(((lon + 180.0) * 8000.0 * 1e6).roundToLong() / 1e6).toLong()
        lo = lo.coerceIn(0L, 360L * 8000 - 1)
        val digits = CharArray(10)
        for (i in 4 downTo 0) {
            digits[2 * i] = ALPHABET[(la % 20).toInt()]
            digits[2 * i + 1] = ALPHABET[(lo % 20).toInt()]
            la /= 20; lo /= 20
        }
        return String(digits, 0, 8) + "+" + String(digits, 8, 2)
    }

    /** Initial compass bearing from 1 to 2, degrees 0..360 (0 = north). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Sunrise and sunset (epoch ms) of the solar day nearest [nowMs] at this place. */
    class Sun(val rise: Long, val set: Long)

    /**
     * Sunrise equation (as NOAA, about a minute accurate). null when the sun doesn't rise or set that day
     * (polar day or night) or the position is unknown.
     */
    fun sun(lat: Double, lon: Double, nowMs: Long): Sun? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0) return null
        val jd = nowMs / 86_400_000.0 + 2440587.5
        // the local solar noon nearest to now (so "today" follows the sun, not UTC midnight)
        val n = Math.round(jd - 2451545.0 + lon / 360.0).toDouble()
        val jStar = n - lon / 360.0
        val m = Math.toRadians((357.5291 + 0.98560028 * jStar) % 360.0)
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = Math.toRadians((Math.toDegrees(m) + c + 180.0 + 102.9372) % 360.0)
        val transit = 2451545.0 + jStar + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(Math.toRadians(23.44)))
        val phi = Math.toRadians(lat)
        val cosW = (sin(Math.toRadians(-0.833)) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        if (!cosW.isFinite() || cosW < -1.0 || cosW > 1.0) return null
        val w = acos(cosW) / (2 * PI) // fraction of a day
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).roundToLong()
        return Sun(ms(transit - w), ms(transit + w))
    }
}
