package app.plain.camera

import app.plain.lang.tr
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A width × height pair (android.util.Size is a stub in local unit tests, so the logic uses this). */
data class Dim(val w: Int, val h: Int) {
    val long get() = max(w, h)
    val short get() = min(w, h)
    /** Long side / short side, so 4:3 and 3:4 are both 1.333. */
    val ratio: Double get() = if (short == 0) 0.0 else long.toDouble() / short
    val area: Long get() = w.toLong() * h

    override fun toString() = "${w}x$h"

    companion object {
        fun parse(s: String?): Dim? {
            val p = s?.split('x') ?: return null
            if (p.size != 2) return null
            val w = p[0].toIntOrNull() ?: return null
            val h = p[1].toIntOrNull() ?: return null
            return if (w > 0 && h > 0) Dim(w, h) else null
        }
    }
}

/** Aspect choices; FULL means "as close to the phone screen's shape as the sensor offers". */
enum class Aspect(val label: String, val ratio: Double) {
    FOUR_THREE("4:3", 4.0 / 3),
    SIXTEEN_NINE("16:9", 16.0 / 9),
    SQUARE("1:1", 1.0),
    FULL("Full", 0.0);

    /** What the aspect dropdown shows (the [label] follows the app language). */
    val display: String get() = if (this == FULL) tr("Full", "Tam") else label
}

object Sizes {
    /** Relative tolerance when grouping sizes by shape (1920x1088 counts as 16:9). */
    private const val TOL = 0.015

    fun sameShape(a: Double, b: Double) = b > 0 && abs(a - b) / b < TOL

    /**
     * The ratio an aspect stands for on this sensor. FULL picks the available shape closest to the
     * screen; it's null (hidden) when that shape is just 4:3 / 16:9 again or nothing is close.
     */
    fun ratioFor(a: Aspect, sizes: List<Dim>, screenRatio: Double): Double? {
        if (a != Aspect.FULL) return if (sizes.any { sameShape(it.ratio, a.ratio) }) a.ratio else null
        val best = sizes.map { it.ratio }.minByOrNull { abs(it - screenRatio) } ?: return null
        if (abs(best - screenRatio) / screenRatio > 0.06) return null
        if (Aspect.entries.any { it != Aspect.FULL && sameShape(best, it.ratio) }) return null
        return best
    }

    fun aspects(sizes: List<Dim>, screenRatio: Double): List<Aspect> =
        Aspect.entries.filter { ratioFor(it, sizes, screenRatio) != null }

    /** Sizes of one aspect, biggest first, without duplicates and without tiny ones. */
    fun forAspect(sizes: List<Dim>, a: Aspect, screenRatio: Double, minShort: Int = 480): List<Dim> {
        val r = ratioFor(a, sizes, screenRatio) ?: return emptyList()
        return sizes.filter { sameShape(it.ratio, r) && it.short >= minShort }
            .distinct().sortedByDescending { it.area }
    }

    fun photoLabel(d: Dim): String {
        val mp = d.area / 1_000_000.0
        val m = if (mp >= 10) mp.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", mp)
        return "$m MP · ${d.w}×${d.h}"
    }

    fun videoLabel(d: Dim): String = when (d.short) {
        2160 -> "4K"
        1088 -> "1080p"
        else -> "${d.short}p"
    } + " · ${d.w}×${d.h}"

    /** Default video size: the largest whose short side is at most 1080 (1080p when there is one). */
    fun defaultVideo(list: List<Dim>): Dim? =
        list.firstOrNull { it.short == 1080 } ?: list.firstOrNull { it.short <= 1088 } ?: list.lastOrNull()

    /**
     * Preview stream size: same shape as the capture, at most about 1080p (enough for the screen
     * and light on the battery). Falls back to the closest shape.
     */
    fun preview(sizes: List<Dim>, ratio: Double, maxLong: Int = 1920, maxShort: Int = 1088): Dim? {
        val small = sizes.filter { it.long <= maxLong && it.short <= maxShort }.ifEmpty { sizes }
        val same = small.filter { sameShape(it.ratio, ratio) }
        if (same.isNotEmpty()) {
            // the biggest, but of near-equal ones the truest shape (1920x1080 over 1920x1088)
            val top = same.maxOf { it.area }
            return same.filter { it.area >= top * 0.95 }.minByOrNull { abs(it.ratio - ratio) }
        }
        val r = small.minByOrNull { abs(it.ratio - ratio) }?.ratio ?: return null
        return small.filter { it.ratio == r }.maxByOrNull { it.area }
    }

    /**
     * Rotation the photo needs to look upright (Camera2 JPEG_ORIENTATION / MediaRecorder hint).
     * [deviceDeg] is OrientationEventListener's value (clockwise tilt), or -1 when unknown.
     */
    fun jpegOrientation(sensor: Int, deviceDeg: Int, front: Boolean): Int {
        if (deviceDeg < 0) return sensor
        val dev = ((deviceDeg + 45) / 90 * 90) % 360
        return if (front) (sensor - dev + 360) % 360 else (sensor + dev) % 360
    }

    /**
     * A tap on the portrait preview (0..1 across and down) → the same spot in sensor
     * coordinates (0..1, sensor's own landscape orientation).
     */
    fun viewToSensor(nx: Float, ny: Float, sensor: Int, front: Boolean): Pair<Float, Float> {
        val ux = if (front) 1f - nx else nx // the front preview is mirrored
        val uy = ny
        return when ((sensor % 360 + 360) % 360) {
            90 -> uy to 1f - ux
            180 -> 1f - ux to 1f - uy
            270 -> 1f - uy to ux
            else -> ux to uy
        }
    }

    /** The part of the sensor a stream of [ratio] shows (centre crop): left, top, width, height. */
    fun crop(activeW: Int, activeH: Int, ratio: Double): IntArray {
        if (ratio <= 0) return intArrayOf(0, 0, activeW, activeH)
        val landscape = activeW >= activeH
        val sensorRatio = if (landscape) activeW.toDouble() / activeH else activeH.toDouble() / activeW
        var w = activeW
        var h = activeH
        if (ratio > sensorRatio) { // wider than the sensor: cut top and bottom (in sensor terms)
            if (landscape) h = (activeW / ratio).roundToInt() else w = (activeH / ratio).roundToInt()
        } else if (ratio < sensorRatio) { // narrower: cut the sides
            if (landscape) w = (activeH * ratio).roundToInt() else h = (activeW * ratio).roundToInt()
        }
        return intArrayOf((activeW - w) / 2, (activeH - h) / 2, w, h)
    }

    /**
     * Some camera HALs ignore JPEG_ORIENTATION. Given the rotation we asked for, the EXIF rotation
     * the HAL wrote and the JPEG's pixel size, returns the EXIF orientation tag to write, or null
     * when the file is already right. [halRotates] = this HAL was seen rotating the pixels itself.
     */
    fun orientationTag(wanted: Int, exifRot: Int, w: Int, h: Int, halRotates: Boolean): Int? {
        if (exifRot != 0 || wanted == 0) return null
        val quarter = wanted == 90 || wanted == 270
        if (quarter && h > w) return null // pixels already turned
        if ((!quarter || w == h) && halRotates) return null
        return when (wanted) {
            90 -> 6   // ExifInterface.ORIENTATION_ROTATE_90
            180 -> 3  // ORIENTATION_ROTATE_180
            270 -> 8  // ORIENTATION_ROTATE_270
            else -> null
        }
    }

    /** Storage left free when a video stops for a full disk (room for the file's index and the system). */
    const val VIDEO_RESERVE = 64L shl 20
    /** Below this much room a recording isn't started at all. */
    private const val MIN_RECORD = 4L shl 20

    /**
     * The byte limit for a recording: the calling app's own limit ([callerMax], 0 = none) and the [free] storage less
     * [reserve], whichever is smaller. 0 = too little room to start; Long.MAX_VALUE = no limit known.
     */
    fun recordLimit(callerMax: Long, free: Long, reserve: Long): Long {
        val room = if (free == Long.MAX_VALUE) Long.MAX_VALUE else free - reserve
        if (room < MIN_RECORD) return 0L
        return if (callerMax > 0) minOf(callerMax, room) else room
    }

    /**
     * MediaStore.EXTRA_DURATION_LIMIT (seconds; apps pass it as int or long) → milliseconds for
     * MediaRecorder.setMaxDuration, 0 = none. Never overflows into a short or negative limit.
     */
    fun durationLimitMs(v: Any?): Int {
        val s = (v as? Number)?.toLong() ?: (v as? String)?.trim()?.toLongOrNull() ?: return 0
        if (s <= 0) return 0
        return if (s >= Int.MAX_VALUE / 1000) Int.MAX_VALUE else (s * 1000).toInt()
    }

    /** H.264 bit rate: about 0.25 bit per pixel per frame, 2–50 Mbit/s. */
    fun videoBitrate(d: Dim, fps: Int = 30): Int =
        (d.area * fps * 0.25).toLong().coerceIn(2_000_000, 50_000_000).toInt()

    fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(java.util.Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
