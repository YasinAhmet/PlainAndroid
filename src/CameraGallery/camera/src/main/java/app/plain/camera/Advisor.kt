package app.plain.camera

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Brightness statistics of a small copy of the preview (about 160 px wide, as shown on screen).
 * [luma] is kept for per-area questions such as "is the face darker than the frame?".
 */
class Stats(val w: Int, val h: Int, val luma: IntArray) {
    val total = w * h
    val hist = IntArray(256)
    /** Share of pixels at 250+ (blown) and under 16 (black). */
    val clipped: Float
    val dark: Float
    val mean: Float
    /** Middle ninth vs. the outer border. */
    val centerMean: Float
    val edgeMean: Float
    /** (5th…95th percentile spread) / 255. */
    val spread: Float
    /** A small, compact, fully blown patch: the sun or a lamp in the frame. */
    val sun: Boolean

    init {
        var sum = 0L
        for (v in luma) { hist[v]++; sum += v }
        mean = if (total > 0) sum.toFloat() / total else 0f
        var c = 0
        for (v in 250..255) c += hist[v]
        clipped = if (total > 0) c.toFloat() / total else 0f
        var dk = 0
        for (v in 0 until 16) dk += hist[v]
        dark = if (total > 0) dk.toFloat() / total else 0f
        spread = (percentile(0.95f) - percentile(0.05f)) / 255f
        centerMean = meanIn(1 / 3f, 1 / 3f, 2 / 3f, 2 / 3f)
        val bw = maxOf(1, w / 6)
        val bh = maxOf(1, h / 6)
        var es = 0L
        var en = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (x < bw || x >= w - bw || y < bh || y >= h - bh) { es += luma[y * w + x]; en++ }
        }
        edgeMean = if (en > 0) es.toFloat() / en else mean
        sun = findSun()
    }

    fun percentile(p: Float): Int {
        val target = (total * p).toInt()
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc > target) return v }
        return 255
    }

    /** Mean brightness inside a rectangle given as 0…1 fractions of the frame. */
    fun meanIn(l: Float, t: Float, r: Float, b: Float): Float {
        val x0 = (l * w).toInt().coerceIn(0, w - 1)
        val x1 = (r * w).toInt().coerceIn(x0 + 1, w)
        val y0 = (t * h).toInt().coerceIn(0, h - 1)
        val y1 = (b * h).toInt().coerceIn(y0 + 1, h)
        var s = 0L
        for (y in y0 until y1) for (x in x0 until x1) s += luma[y * w + x]
        return s.toFloat() / ((x1 - x0) * (y1 - y0))
    }

    private fun findSun(): Boolean {
        if (clipped < 0.0008f || clipped > 0.10f || total == 0) return false
        // blown pixels per cell of a coarse grid; the sun is one hot spot, not a bright sky
        val gx = 8
        val gy = maxOf(1, (8f * h / w).roundToInt())
        val cells = IntArray(gx * gy)
        val cw = w.toFloat() / gx
        val ch = h.toFloat() / gy
        for (y in 0 until h) for (x in 0 until w) {
            if (luma[y * w + x] >= 252) cells[minOf(gy - 1, (y / ch).toInt()) * gx + minOf(gx - 1, (x / cw).toInt())]++
        }
        val all = cells.sum()
        if (all == 0) return false
        val top = cells.indices.maxByOrNull { cells[it] } ?: return false
        val tx = top % gx
        val ty = top / gx
        var near = 0
        for (dy in -1..1) for (dx in -1..1) {
            val x = tx + dx
            val y = ty + dy
            if (x in 0 until gx && y in 0 until gy) near += cells[y * gx + x]
        }
        val cellArea = cw * ch
        return near >= all * 0.8f && cells[top] >= cellArea * 0.25f && mean < 190
    }

    companion object {
        /** From ARGB pixels (Bitmap.getPixels). */
        fun of(px: IntArray, w: Int, h: Int): Stats {
            val l = IntArray(w * h)
            for (i in l.indices) {
                val p = px[i]
                l[i] = ((p shr 16 and 255) * 299 + (p shr 8 and 255) * 587 + (p and 255) * 114) / 1000
            }
            return Stats(w, h, l)
        }
    }
}

/** A detected face in portrait view fractions (0…1). */
data class FaceBox(val l: Float, val t: Float, val r: Float, val b: Float)

/** Everything the advisor looks at for one moment. */
class Moment(
    val stats: Stats?,
    val iso: Int = 0,
    val exposureNs: Long = 0,
    val flashRequired: Boolean = false,
    val flashOn: Boolean = false,
    val unfocused: Boolean = false,
    /** Rotation rate (rad/s), smoothed. */
    val shake: Float = 0f,
    /** Degrees off level, or null when the phone points up or down. */
    val tilt: Float? = null,
    val faces: List<FaceBox> = emptyList(),
)

/** Offline shot advice: plain rules over numbers the phone already has. */
object Advisor {
    const val SHAKE = 0.35f
    const val STILL = 0.15f

    /** The most important problem right now, or null. */
    fun hint(m: Moment): String? {
        val s = m.stats
        if (m.shake > SHAKE) return "Shaking – hold still"
        if ((m.flashRequired && !m.flashOn) || (s != null && s.mean < 40 && m.iso >= 3200)) return "Too dark – add light or turn on flash"
        if (s != null) {
            if (s.sun) return "Sun or a bright light in frame – flare likely. Move it to the edge or shade the lens"
            if (s.edgeMean - s.centerMean > 70 && s.clipped > 0.03f) return "Subject backlit – tap it to expose for it, or use flash"
            if (s.clipped > 0.04f) return "Highlights blown (${(s.clipped * 100).roundToInt()}%) – lower EV"
        }
        for (f in m.faces) {
            if (f.l < 0.02f || f.r > 0.98f || f.t < 0.01f || f.b > 0.98f) return "Face cut off by the edge"
        }
        if (s != null) for (f in m.faces) {
            if (s.meanIn(f.l, f.t, f.r, f.b) < s.mean - 45) return "Face in shadow – tap the face or add light"
        }
        if (m.iso >= 1600 && m.exposureNs >= Adv.FRAME_30) return "Low light – hold steady, the photo will be noisy"
        if (m.unfocused) return "Soft – tap to focus"
        val t = m.tilt
        if (t != null && abs(t) > 3f && abs(t) < 30f) return "Tilted ${abs(t).roundToInt()}° – straighten"
        for (f in m.faces) if (f.t < 0.06f) return "Face too close to the top"
        if (s != null && s.spread < 0.25f && s.mean in 50f..200f) return "Flat light – low contrast"
        return null
    }

    /** Steady, focused, level, nothing blown: a good moment to press. */
    fun ready(m: Moment): Boolean {
        val s = m.stats ?: return false
        val t = m.tilt
        return m.shake < STILL && !m.unfocused && (t == null || abs(t) <= 2f) &&
            s.clipped < 0.02f && !s.sun && s.mean > 30 && !m.flashRequired
    }

    /** A few words about the moment a photo was taken, or null when it looked fine. */
    fun verdict(m: Moment): String? {
        val s = m.stats ?: return null
        return when {
            m.shake > SHAKE -> "Saved · may be blurred (shake)"
            s.clipped > 0.02f -> "Saved · ${(s.clipped * 100).roundToInt()}% blown"
            s.mean < 35 -> "Saved · dark"
            else -> null
        }
    }
}

/**
 * Stops the hint line from flickering: a hint must last [showAfter] ms before it appears, and
 * stays at least [holdMin] ms once shown. Times come in, so it is testable.
 */
class HintGate(private val showAfter: Long = 900, private val holdMin: Long = 2000) {
    var shown: String? = null
        private set
    private var shownAt = 0L
    private var pending: String? = null
    private var pendingSince = 0L

    fun update(now: Long, candidate: String?): String? {
        if (candidate != null && candidate == shown) { pending = candidate; return shown }
        if (candidate != pending) { pending = candidate; pendingSince = now }
        val held = shown == null || now - shownAt >= holdMin
        if (held) {
            if (candidate == null) shown = null
            else if (now - pendingSince >= showAfter) { shown = candidate; shownAt = now }
        }
        return shown
    }

    fun reset() { shown = null; pending = null }
}
