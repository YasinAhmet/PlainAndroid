package app.plain.camera

import app.plain.lang.tr
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The Advanced tab's settings. Everything defaults to "auto", and the Basic tab ignores all of
 * it (the activity then uses [Adv] with defaults). Pure Kotlin, so the maths is unit-tested.
 */
data class Adv(
    /** Exposure compensation in 1/10 EV (the camera's own step), −20…20. */
    val ev: Int = 0,
    /** 0 = auto. */
    val iso: Int = 0,
    /** 0 = auto. */
    val shutterNs: Long = 0,
    /** A CONTROL_AWB_MODE value (1 = auto), or [WB_KELVIN]. */
    val wb: Int = WB_AUTO,
    val kelvin: Int = 5500,
    /** −1 = auto focus; 0…1 = manual (0 = infinity, 1 = the closest the lens can do). */
    val focus: Float = -1f,
    /** Camera tone curve, −100…100 each. */
    val contrast: Int = 0,
    val shadows: Int = 0,
    /** 0 auto · 1 off · 2 low · 3 high. */
    val sharpen: Int = 0,
    val noise: Int = 0,
    /** A CONTROL_SCENE_MODE value; 0 = none. */
    val scene: Int = 0,
    /** Look (photo only, applied by the app), −100…100 each. */
    val sat: Int = 0,
    val warmth: Int = 0,
    val tint: Int = 0,
    val vibrance: Int = 0,
    /** 0 off · 1 thirds · 2 golden · 3 centre · 4 diagonals. */
    val grid: Int = 0,
    val level: Boolean = true,
    val faces: Boolean = false,
    val hints: Boolean = true,
    val steady: Boolean = false,
    val jpeg: Int = 95,
    val bitrateHigh: Boolean = false,
    /** Index into the back cameras (0 = main). */
    val lens: Int = 0,
    /** Flash / torch strength in % of the camera's maximum; 0 = the camera's default (Android 15+). */
    val flashPct: Int = 0,
) {
    val manualExposure get() = iso > 0 || shutterNs > 0
    val manualFocus get() = focus >= 0f
    val toneSet get() = contrast != 0 || shadows != 0
    val look get() = Look(sat, warmth, tint, vibrance)

    fun serialize(): String = listOf(
        "ev=$ev", "iso=$iso", "sh=$shutterNs", "wb=$wb", "k=$kelvin", "f=$focus", "c=$contrast", "s=$shadows",
        "sp=$sharpen", "nr=$noise", "sc=$scene", "sat=$sat", "w=$warmth", "t=$tint", "v=$vibrance", "g=$grid",
        "lv=$level", "fc=$faces", "h=$hints", "st=$steady", "q=$jpeg", "br=$bitrateHigh", "l=$lens", "fs=$flashPct",
    ).joinToString(";")

    companion object {
        const val WB_AUTO = 1
        const val WB_KELVIN = -1

        fun parse(s: String?): Adv {
            if (s.isNullOrBlank()) return Adv()
            val m = s.split(';').mapNotNull { p -> p.indexOf('=').takeIf { it > 0 }?.let { p.substring(0, it) to p.substring(it + 1) } }.toMap()
            fun i(k: String, d: Int, lo: Int, hi: Int) = (m[k]?.toIntOrNull() ?: d).coerceIn(lo, hi)
            fun b(k: String, d: Boolean) = m[k]?.toBooleanStrictOrNull() ?: d
            val d = Adv()
            return Adv(
                ev = i("ev", 0, -20, 20),
                iso = i("iso", 0, 0, 409600),
                shutterNs = (m["sh"]?.toLongOrNull() ?: 0L).coerceIn(0L, 60_000_000_000L),
                wb = i("wb", WB_AUTO, -1, 8),
                kelvin = i("k", 5500, 2000, 10000),
                focus = (m["f"]?.toFloatOrNull() ?: -1f).let { if (it < 0f) -1f else it.coerceAtMost(1f) },
                contrast = i("c", 0, -100, 100),
                shadows = i("s", 0, -100, 100),
                sharpen = i("sp", 0, 0, 3),
                noise = i("nr", 0, 0, 3),
                scene = i("sc", 0, 0, 30),
                sat = i("sat", 0, -100, 100),
                warmth = i("w", 0, -100, 100),
                tint = i("t", 0, -100, 100),
                vibrance = i("v", 0, -100, 100),
                grid = i("g", 0, 0, 4),
                level = b("lv", d.level),
                faces = b("fc", d.faces),
                hints = b("h", d.hints),
                steady = b("st", d.steady),
                jpeg = i("q", 95, 50, 100),
                bitrateHigh = b("br", false),
                lens = i("l", 0, 0, 9),
                flashPct = i("fs", 0, 0, 100),
            )
        }

        // ------------------------------------------------------------ exposure
        /** EV slider position −6…6 (thirds of a stop) → the camera's 1/10 steps. */
        fun evTenths(pos: Int): Int = (pos * 10 / 3.0).roundToInt()
        fun evPos(tenths: Int): Int = (tenths * 3 / 10.0).roundToInt()
        fun evLabel(tenths: Int): String = if (tenths == 0) "±0" else String.format(Locale.US, "%+.1f", tenths / 10.0)

        val ISO = listOf(100, 200, 400, 800, 1600, 3200, 6400)
        fun isoChoices(lo: Int, hi: Int) = ISO.filter { it in lo..hi }

        /** Shutter speeds from 1/4000 s to 4 s. */
        val SHUTTER: List<Long> = listOf(4000, 2000, 1000, 500, 250, 125, 60, 30, 15, 8, 4, 2).map { 1_000_000_000L / it } +
            listOf(1_000_000_000L, 2_000_000_000L, 4_000_000_000L)
        const val FRAME_30 = 33_333_333L
        /** Slowest shutter the camera picks by itself when only ISO is set (longer = frozen preview). */
        const val AUTO_SLOWEST = 250_000_000L
        fun shutterChoices(lo: Long, hi: Long, video: Boolean) =
            SHUTTER.filter { it in lo..hi && (!video || it <= FRAME_30) }

        fun shutterLabel(ns: Long): String = when {
            ns <= 0 -> tr("Auto", "Otomatik")
            ns >= 1_000_000_000L -> "${(ns / 1e9).roundToInt()}\""
            else -> "1/${(1e9 / ns).roundToInt()}"
        }

        /**
         * Manual exposure. When only one of ISO / shutter is set, the other keeps the brightness the
         * camera's auto exposure last chose ([autoIso] × [autoNs]), within the sensor's limits. A
         * shutter chosen that way is never slower than [AUTO_SLOWEST]; the picture is darker instead.
         */
        fun exposure(a: Adv, autoIso: Int, autoNs: Long, isoLo: Int, isoHi: Int, nsLo: Long, nsHi: Long, video: Boolean): Pair<Int, Long> {
            val maxNs = if (video) minOf(nsHi, FRAME_30) else nsHi
            val baseIso = if (autoIso > 0) autoIso else 100
            val baseNs = if (autoNs > 0) autoNs else 10_000_000L
            val light = baseIso.toDouble() * baseNs
            return when {
                a.iso > 0 && a.shutterNs > 0 -> a.iso.coerceIn(isoLo, isoHi) to a.shutterNs.coerceIn(nsLo, maxNs)
                a.iso > 0 -> {
                    val iso = a.iso.coerceIn(isoLo, isoHi)
                    iso to (light / iso).roundToLong().coerceIn(nsLo, minOf(maxNs, maxOf(nsLo, AUTO_SLOWEST)))
                }
                else -> {
                    val ns = a.shutterNs.coerceIn(nsLo, maxNs)
                    (light / ns).roundToInt().coerceIn(isoLo, isoHi) to ns
                }
            }
        }

        // ------------------------------------------------------------ white balance
        /** (label, CONTROL_AWB_MODE) in menu order. */
        val WB get() = listOf(
            tr("Auto", "Otomatik") to 1, tr("Daylight", "Gün ışığı") to 5, tr("Cloudy", "Bulutlu") to 6, tr("Shade", "Gölge") to 8,
            tr("Tungsten", "Tungsten") to 2, tr("Fluorescent", "Floresan") to 3, tr("Warm fluor.", "Sıcak flor.") to 4,
            tr("Twilight", "Alacakaranlık") to 7,
        )
        fun wbLabel(a: Adv) = if (a.wb == WB_KELVIN) "${a.kelvin}K" else WB.firstOrNull { it.second == a.wb }?.first ?: tr("Auto", "Otomatik")

        /** Approximate colour of a black body at [k] kelvin, 0…255 per channel (Tanner Helland's fit). */
        fun blackBody(k: Int): DoubleArray {
            val t = k / 100.0
            val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
            val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
            val b = when { t >= 66 -> 255.0; t <= 19 -> 0.0; else -> 138.5177312231 * ln(t - 10) - 305.0447927307 }
            return doubleArrayOf(r.coerceIn(1.0, 255.0), g.coerceIn(1.0, 255.0), b.coerceIn(1.0, 255.0))
        }

        /**
         * Red and blue gain multipliers that turn daylight (5500 K) white balance into white balance
         * for light of [k] kelvin. Below 5500 (warm light) blue goes up and red down.
         */
        fun kelvinFactors(k: Int): Pair<Float, Float> {
            val d = blackBody(5500)
            val c = blackBody(k)
            val r = (d[0] / d[1]) / (c[0] / c[1])
            val b = (d[2] / d[1]) / (c[2] / c[1])
            return r.toFloat() to b.toFloat()
        }

        // ------------------------------------------------------------ flash strength
        /** [pct] (0 = default) → a FLASH_STRENGTH_LEVEL in 1…[max]. */
        fun flashLevel(pct: Int, max: Int, default: Int): Int =
            if (pct <= 0 || max <= 1) default.coerceIn(1, maxOf(1, max)) else (pct * max / 100.0).roundToInt().coerceIn(1, max)

        /** A level the user picked on the 1…[max] slider → the stored percentage. */
        fun flashPct(level: Int, max: Int): Int = if (max <= 1) 0 else (level * 100.0 / max).roundToInt().coerceIn(1, 100)

        // ------------------------------------------------------------ focus
        fun focusLabel(f: Float, minFocusDiopters: Float): String {
            if (f < 0) return tr("Auto", "Otomatik")
            val dio = f * minFocusDiopters
            if (dio < 0.05f) return "∞"
            val m = 1f / dio
            return if (m >= 1f) String.format(Locale.US, "%.1f m", m) else "${(m * 100).roundToInt()} cm"
        }

        // ------------------------------------------------------------ tone curve
        private fun srgb(x: Double) = if (x <= 0.0031308) 12.92 * x else 1.055 * x.pow(1 / 2.4) - 0.055

        /**
         * Points (in, out, in, out…) for TONEMAP_CURVE: the standard sRGB gamma, then an S-curve for
         * [contrast] and a lift or dip in the dark tones for [shadows] (−100…100 each). Never falls.
         */
        fun toneCurve(contrast: Int, shadows: Int, points: Int = 64): FloatArray {
            val n = points.coerceIn(2, 101)
            val c = contrast / 100.0
            val s = shadows / 100.0
            val out = FloatArray(n * 2)
            var last = 0.0
            for (i in 0 until n) {
                val x = (i / (n - 1.0)).pow(2.2) // more points in the dark end
                var y = srgb(x)
                val smooth = y * y * (3 - 2 * y)
                y += c * (smooth - y)
                y += s * 0.6 * y * (1 - y) * (1 - y)
                y = y.coerceIn(0.0, 1.0)
                if (y < last) y = last
                last = y
                out[i * 2] = x.toFloat()
                out[i * 2 + 1] = y.toFloat()
            }
            out[0] = 0f
            out[(n - 1) * 2] = 1f
            return out
        }

        // ------------------------------------------------------------ choices
        /** (label, CONTROL_SCENE_MODE). */
        // labels are getters, so they follow the app language (PlainLang)
        val SCENES get() = listOf(
            tr("Auto", "Otomatik") to 0, tr("Night", "Gece") to 5, tr("Sunset", "Gün batımı") to 10, tr("Landscape", "Manzara") to 4,
            "HDR" to 18, tr("Sports", "Spor") to 13, tr("Portrait", "Portre") to 3,
        )
        val LEVELS get() = listOf(tr("Auto", "Otomatik"), tr("Off", "Kapalı"), tr("Low", "Düşük"), tr("High", "Yüksek"))
        val GRIDS get() = listOf(tr("Off", "Kapalı"), tr("Thirds", "Üçte bir"), tr("Golden", "Altın oran"), tr("Centre", "Merkez"), tr("Diagonals", "Çaprazlar"))
        val JPEG = listOf(85, 90, 95, 100)
    }
}

/** The app's own colour processing for photos; all −100…100, 0 = untouched. */
data class Look(val sat: Int = 0, val warmth: Int = 0, val tint: Int = 0, val vibrance: Int = 0) {
    val neutral get() = sat == 0 && warmth == 0 && tint == 0 && vibrance == 0

    /**
     * A 4×5 colour matrix (android.graphics.ColorMatrix layout) for saturation, warmth and tint.
     * [vibranceAsSat] folds vibrance in as plain saturation (for the live preview, which can only
     * use a matrix).
     */
    fun matrix(vibranceAsSat: Boolean = false): FloatArray {
        val s = (1 + sat / 100f + if (vibranceAsSat) vibrance / 200f else 0f).coerceAtLeast(0f)
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        val w = warmth / 100f * 0.15f
        val t = tint / 100f * 0.12f
        val kr = 1 + w
        val kg = 1 - t
        val kb = 1 - w
        val sr = (1 - s) * lr; val sg = (1 - s) * lg; val sb = (1 - s) * lb
        return floatArrayOf(
            kr * (sr + s), kr * sg, kr * sb, 0f, 0f,
            kg * sr, kg * (sg + s), kg * sb, 0f, 0f,
            kb * sr, kb * sg, kb * (sb + s), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** One ARGB pixel through [m] (from [matrix]) and then vibrance (boosts dull colours more). */
    fun pixel(argb: Int, m: FloatArray): Int {
        val r0 = (argb shr 16 and 255).toFloat()
        val g0 = (argb shr 8 and 255).toFloat()
        val b0 = (argb and 255).toFloat()
        var r = m[0] * r0 + m[1] * g0 + m[2] * b0
        var g = m[5] * r0 + m[6] * g0 + m[7] * b0
        var b = m[10] * r0 + m[11] * g0 + m[12] * b0
        if (vibrance != 0) {
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val satLevel = ((mx - mn) / 255f).coerceIn(0f, 1f)
            val k = 1 + vibrance / 100f * (1 - satLevel)
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            r = l + (r - l) * k; g = l + (g - l) * k; b = l + (b - l) * k
        }
        return (argb and -0x1000000) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    }

    private fun clamp(v: Float): Int = if (v <= 0f) 0 else if (v >= 255f) 255 else (v + 0.5f).toInt()

    companion object {
        val PRESETS get() = listOf(
            tr("Natural", "Doğal") to Look(),
            tr("Vivid", "Canlı") to Look(sat = 25, vibrance = 30),
            tr("Muted", "Soluk") to Look(sat = -35),
            tr("B&W", "S&B") to Look(sat = -100),
            tr("Warm", "Sıcak") to Look(sat = 10, warmth = 40),
        )
        fun name(l: Look) = PRESETS.firstOrNull { it.second == l }?.first ?: tr("Custom", "Özel")
    }
}

/** Sensor ↔ view mapping helpers missing from [Sizes]. */
object Geo {
    /** Inverse of [Sizes.viewToSensor]: sensor 0…1 (landscape) → portrait view 0…1. */
    fun sensorToView(sx: Float, sy: Float, sensor: Int, front: Boolean): Pair<Float, Float> {
        val (ux, uy) = when ((sensor % 360 + 360) % 360) {
            90 -> 1f - sy to sx
            180 -> 1f - sx to 1f - sy
            270 -> sy to 1f - sx
            else -> sx to sy
        }
        return (if (front) 1f - ux else ux) to uy
    }

    /** Phone roll from gravity (x right, y up, in m/s²): 0 upright, + when turned clockwise. */
    fun roll(gx: Float, gy: Float): Float = Math.toDegrees(kotlin.math.atan2(-gx.toDouble(), gy.toDouble())).toFloat()

    /**
     * A finger moved ([mx], [my] px) across the preview: −1 = a swipe to the left, 1 = to the right, 0 = no clear
     * sideways swipe (shorter than [minPx], or more up / down than sideways).
     */
    fun sideSwipe(mx: Float, my: Float, minPx: Float): Int =
        if (!(abs(mx) >= minPx && abs(mx) > abs(my) * 1.5f)) 0 else if (mx < 0) -1 else 1

    /** How far [roll] is from the nearest level position (upright, either landscape, upside down). */
    fun offLevel(roll: Float): Float {
        val r = roll - (roll / 90f).roundToInt() * 90f
        return if (abs(r) < 1e-4f) 0f else r
    }
}
