package app.plain.maps

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    // OLED (2026-11-28): panels over the map are near-opaque black (they darken the map, they light nothing) and
    // buttons/cards are black with a 1 dp outline; nothing non-black is filled.
    const val PANEL = 0xF2000000.toInt()
    const val CARD = 0xFF000000.toInt()
    const val LINE = 0xFF333333.toInt()
    const val OUTLINE = 0xFF222222.toInt()
    const val TEXT = 0xFFEDEDED.toInt()
    const val DIM = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFF26C6DA.toInt()
    const val RED = 0xFFEF5350.toInt()
    const val HIGHLIGHT = 0xFFFFD740.toInt()

    /** Colours for the lines around a tapped point (the list badges use the same ones). */
    val PALETTE = intArrayOf(
        0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(), 0xFFE040FB.toInt(), 0xFFFFAB40.toInt(),
        0xFFB2FF59.toInt(), 0xFF18FFFF.toInt(), 0xFFFF80AB.toInt(), 0xFFB388FF.toInt(), 0xFFEEFF41.toInt(), 0xFF64FFDA.toInt(),
    )
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
/** Black shape with a 1 dp outline (the OLED replacement for a filled one). */
fun Context.outline(radius: Float, stroke: Int = C.LINE, fill: Int = 0xFF000000.toInt()) =
    GradientDrawable().apply { setColor(fill); cornerRadius = radius; setStroke(dp(1), stroke) }
fun Context.outlineOval(stroke: Int = C.LINE, fill: Int = 0xFF000000.toInt()) =
    GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill); setStroke(dp(1), stroke) }

fun ripple(bg: Drawable?) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, if (bg == null) roundRect(0xFFFFFFFF.toInt(), 0f) else null)

fun lpL(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun lpF(w: Int, h: Int, gravity: Int) = FrameLayout.LayoutParams(w, h, gravity)

fun Context.text(s: CharSequence, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 0) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

/** An outlined button: [color] is the outline ([C.CARD] = the plain grey line, [C.ACCENT] = on / primary). */
fun Context.button(label: String, color: Int = C.CARD, textColor: Int = C.TEXT, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    textSize = 13.5f
    setTextColor(textColor)
    gravity = Gravity.CENTER
    background = ripple(outline(dpf(16), if (color == C.CARD) C.LINE else color))
    setPadding(dp(12), dp(6), dp(12), dp(6))
    setOnClickListener(onClick)
}

/** [color] made light enough to read on black (dark line colours are mixed towards white). */
fun readable(color: Int): Int {
    val r = (color shr 16) and 0xFF; val g = (color shr 8) and 0xFF; val b = color and 0xFF
    val lum = 0.299 * r + 0.587 * g + 0.114 * b
    if (lum >= 110) return color or 0xFF000000.toInt()
    val t = ((110 - lum) / 110.0 * 0.7 + 0.25).coerceAtMost(0.8)
    fun mix(c: Int) = (c + (255 - c) * t).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
}

/** A line number in a pill outlined in the line's colour (OLED: outline and text, no fill). */
fun Context.badge(ref: String, color: Int) = TextView(this).apply {
    text = ref
    textSize = 13f
    setTypeface(Typeface.DEFAULT_BOLD)
    val c = readable(color)
    setTextColor(c)
    background = GradientDrawable().apply { setColor(0xFF000000.toInt()); cornerRadius = dpf(6); setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1), c) }
    gravity = Gravity.CENTER
    minWidth = dp(46)
    setPadding(dp(6), dp(3), dp(6), dp(3))
    maxLines = 1
}

/** Simple drawn icons (no image assets). */
class Icon(ctx: Context, val kind: Int) : View(ctx) {
    companion object {
        const val GPS = 0
        const val SETTINGS = 1
        const val CLOSE = 2
        const val BACK = 3
        const val SEARCH = 4
        const val ROUTE = 5
        const val LIST = 6
    }

    var color = C.TEXT
        set(v) { field = v; invalidate() }
    var filled = false
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        val cx = width / 2f; val cy = height / 2f
        p.color = color
        p.strokeWidth = 2f * d
        p.style = Paint.Style.STROKE
        when (kind) {
            GPS -> {
                c.drawCircle(cx, cy, 7 * d, p)
                for (k in 0 until 4) {
                    val a = Math.toRadians(90.0 * k)
                    val x0 = cx + (7 * d) * kotlin.math.cos(a).toFloat(); val y0 = cy + (7 * d) * kotlin.math.sin(a).toFloat()
                    val x1 = cx + (10.5f * d) * kotlin.math.cos(a).toFloat(); val y1 = cy + (10.5f * d) * kotlin.math.sin(a).toFloat()
                    c.drawLine(x0, y0, x1, y1, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx, cy, if (filled) 3.6f * d else 2.2f * d, p)
            }
            SETTINGS -> {
                for (k in -1..1) {
                    val y = cy + k * 6 * d
                    c.drawLine(cx - 9 * d, y, cx + 9 * d, y, p)
                }
                p.style = Paint.Style.FILL
                c.drawCircle(cx - 4 * d, cy - 6 * d, 2.6f * d, p)
                c.drawCircle(cx + 4 * d, cy, 2.6f * d, p)
                c.drawCircle(cx - 1 * d, cy + 6 * d, 2.6f * d, p)
            }
            CLOSE -> {
                c.drawLine(cx - 6 * d, cy - 6 * d, cx + 6 * d, cy + 6 * d, p)
                c.drawLine(cx + 6 * d, cy - 6 * d, cx - 6 * d, cy + 6 * d, p)
            }
            BACK -> {
                path.rewind()
                path.moveTo(cx + 3 * d, cy - 7 * d); path.lineTo(cx - 4 * d, cy); path.lineTo(cx + 3 * d, cy + 7 * d)
                c.drawPath(path, p)
            }
            ROUTE -> {
                path.rewind()
                path.moveTo(cx - 6 * d, cy + 6 * d)
                path.cubicTo(cx - 6 * d, cy - 2 * d, cx + 6 * d, cy + 2 * d, cx + 6 * d, cy - 6 * d)
                c.drawPath(path, p)
                p.style = Paint.Style.FILL
                c.drawCircle(cx - 6 * d, cy + 6 * d, 2.8f * d, p)
                c.drawCircle(cx + 6 * d, cy - 6 * d, 2.8f * d, p)
            }
            LIST -> {
                for (k in -1..1) c.drawLine(cx - 3 * d, cy + k * 5.5f * d, cx + 8 * d, cy + k * 5.5f * d, p)
                p.style = Paint.Style.FILL
                for (k in -1..1) c.drawCircle(cx - 7.5f * d, cy + k * 5.5f * d, 1.6f * d, p)
            }
            SEARCH -> {
                c.drawCircle(cx - 2 * d, cy - 2 * d, 6 * d, p)
                c.drawLine(cx + 2.5f * d, cy + 2.5f * d, cx + 7.5f * d, cy + 7.5f * d, p)
            }
        }
    }
}

/** A vertical layout that never grows taller than [maxH] pixels. */
class CappedLinear(ctx: Context) : LinearLayout(ctx) {
    var maxH = Int.MAX_VALUE
        set(v) { field = v; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(heightMeasureSpec)
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (mode == MeasureSpec.UNSPECIFIED) maxH else minOf(size, maxH)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

object Fmt {
    fun dist(m: Double): String = if (m < 1000) "${(m / 10).toInt() * 10} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)
    fun date(t: Long): String = java.text.SimpleDateFormat("d MMM yyyy HH:mm", app.plain.lang.PlainLang.locale).format(java.util.Date(t))
    fun mb(bytes: Long): String = String.format(java.util.Locale.US, "%.0f MB", bytes / 1048576.0)
}
