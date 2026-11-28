package app.trekmap.trail

import kotlin.math.max

/**
 * Cleans raw GPS fixes into a track:
 *  - drops fixes with poor accuracy,
 *  - discards the first fixes after the GPS (re)starts unless they are already sharp,
 *  - rejects physically implausible jumps,
 *  - smooths with a simple accuracy-weighted Kalman filter,
 *  - only stores a point after moving [minStep] metres (no "spider web" while standing).
 */
class TrackFilter(
    private val maxAcc: Float = 30f,
    private val maxSpeed: Double = 12.0,
    private val minStep: Double = 4.0,
    private val qMps: Double = 3.0,
) {
    private var kLat = 0.0
    private var kLon = 0.0
    private var variance = -1.0
    private var kTime = 0L
    private var warm = 0
    private var rejects = 0
    private var sLat = Double.NaN
    private var sLon = Double.NaN

    /**
     * Why the last fix was kept or dropped (for the development log):
     * S stored · A bad accuracy · W warm-up · J implausible jump · M moved less than the min step · P chip says standing
     */
    var lastReason = 'S'
        private set

    init { reset() }

    /** Call when the GPS restarts (start, after a rest). Keeps the last stored point. */
    fun reset() {
        variance = -1.0
        warm = 2
        rejects = 0
    }

    /** New recording: also forget the last stored point. */
    fun fullReset() {
        reset()
        sLat = Double.NaN
        sLon = Double.NaN
    }

    /**
     * Feeds one fix; returns the smoothed [lat, lon] to store, or null.
     * [speed] is the chip's Doppler speed in m/s (very accurate), or negative if unknown.
     */
    fun push(lat: Double, lon: Double, acc: Float, t: Long, speed: Float = -1f): DoubleArray? {
        // NaN-safe: anything that isn't a finite, positive, good-enough accuracy and position is dropped
        if (!(acc > 0f && acc <= maxAcc) || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            lastReason = 'A'
            return null
        }
        if (warm > 0) {
            warm--
            if (acc > 12f) { lastReason = 'W'; return null }
        }
        val a = max(acc.toDouble(), 3.0)
        if (variance < 0) {
            kLat = lat; kLon = lon; variance = a * a; kTime = t
        } else {
            val dt = ((t - kTime) / 1000.0).coerceAtLeast(0.0)
            val d = Geo.dist(kLat, kLon, lat, lon)
            if (d > maxSpeed * dt + 2 * a + 10 && rejects < 5) {
                rejects++
                lastReason = 'J'
                return null
            }
            if (rejects >= 5) {
                // we were the outlier: snap to the new consistent position
                kLat = lat; kLon = lon; variance = a * a; kTime = t
            } else {
                variance += dt * qMps * qMps
                kTime = t
                val k = variance / (variance + a * a)
                kLat += k * (lat - kLat)
                kLon += k * (lon - kLon)
                variance *= (1 - k)
            }
            rejects = 0
        }
        if (!sLat.isNaN()) {
            val moved = Geo.dist(sLat, sLon, kLat, kLon)
            // need to move further than the fix is uncertain (capped), so noise doesn't draw zig-zags
            if (moved < max(minStep, minOf(acc.toDouble(), 15.0))) { lastReason = 'M'; return null }
            // the chip says we're standing: ignore drift unless we clearly moved anyway
            if (speed in 0f..0.3f && moved < 20.0) { lastReason = 'P'; return null }
        }
        lastReason = 'S'
        sLat = kLat; sLon = kLon
        return doubleArrayOf(kLat, kLon)
    }
}

/** True once the position has stayed within [radius] metres for [windowMs]. */
class StillDetector(private val radius: Double = 15.0, private val windowMs: Long = 60_000L) {
    private var aLat = Double.NaN
    private var aLon = Double.NaN
    private var aTime = 0L

    fun reset() { aLat = Double.NaN }

    fun push(lat: Double, lon: Double, acc: Double, t: Long, speed: Float): Boolean {
        if (aLat.isNaN() || speed > 1.0f || Geo.dist(aLat, aLon, lat, lon) > max(radius, acc)) {
            aLat = lat; aLon = lon; aTime = t
            return false
        }
        return t - aTime >= windowMs
    }
}

/** Total climb with a hysteresis threshold so noise doesn't add up. */
class AscentCounter(private val threshold: Double) {
    var total = 0.0
        private set
    private var ref = Double.NaN

    fun reset() { total = 0.0; ref = Double.NaN }

    fun push(e: Double) {
        if (!e.isFinite()) return // an infinite spike would make the total (and the saved trail's JSON) unusable
        if (ref.isNaN() || e < ref) { ref = e; return }
        if (e - ref >= threshold) { total += e - ref; ref = e }
    }
}
