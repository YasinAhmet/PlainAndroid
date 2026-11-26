package app.plain.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.View

// OLED palette: black chrome, thin outlines instead of grey fills, no pure white text
const val TEXT = 0xFFE4E4E4.toInt()
const val TEXT2 = 0xFFA6A6A6.toInt()
const val LABEL = 0xFF747474.toInt()
const val BTN_LINE = 0xFF333333.toInt()
const val ACCENT = 0xFFE6B422.toInt()
const val RED = 0xFFD32F2F.toInt()
const val GREEN = 0xFF43A047.toInt()

/** The round shutter: white for photos, red for video, a red square while recording. */
class ShutterButton(ctx: Context) : View(ctx) {
    var video = false
        set(v) { field = v; invalidate() }
    var recording = false
        set(v) { field = v; invalidate() }
    /** Advanced guidance: green dot = a good moment, null = no dot. */
    var ready: Boolean? = null
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 4 * d; color = TEXT }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init { isClickable = true; contentDescription = "Shutter" }

    override fun setPressed(pressed: Boolean) { super.setPressed(pressed); invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f - ring.strokeWidth
        c.drawCircle(cx, cy, r, ring)
        fill.color = if (video) RED else TEXT
        val inner = r - 6 * d - if (isPressed) 4 * d else 0f
        if (recording) {
            val s = inner * 0.55f
            c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 6 * d, 6 * d, fill)
        } else {
            c.drawCircle(cx, cy, inner, fill)
        }
        ready?.let {
            dot.color = if (it) GREEN else 0xFF000000.toInt()
            val dx = cx + r * 0.72f
            val dy = cy - r * 0.72f
            c.drawCircle(dx, dy, 7 * d, dot)
            if (!it) { dot.color = LABEL; dot.style = Paint.Style.STROKE; dot.strokeWidth = 1.5f * d; c.drawCircle(dx, dy, 6 * d, dot); dot.style = Paint.Style.FILL }
        }
    }
}

/**
 * Where the user tapped: a short-lived ring (metering only), or, while focus is locked there, an
 * amber square with an "AF-L" tag that stays until it is tapped again (grey: the lens couldn't focus).
 */
class FocusRing(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = ACCENT }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; color = ACCENT; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private var x = -1f
    private var y = -1f
    private var lx = -1f
    private var ly = -1f
    private val hide = Runnable { x = -1f; invalidate() }

    /** Focus locked at the marker; null = no lock. */
    val locked get() = if (lx < 0) null else lx to ly
    /** The locked lens reported "not focused". */
    var failed = false
        set(v) { if (field != v) { field = v; invalidate() } }

    fun show(px: Float, py: Float) {
        x = px; y = py
        invalidate()
        removeCallbacks(hide)
        postDelayed(hide, 1500)
    }

    fun lock(px: Float, py: Float) {
        removeCallbacks(hide)
        x = -1f
        lx = px; ly = py
        failed = false
        invalidate()
    }

    /** The preview moved or changed shape: keep the marker on its point. */
    fun moveLock(px: Float, py: Float) {
        if (lx < 0) return
        lx = px; ly = py
        invalidate()
    }

    fun unlock() {
        if (lx < 0 && x < 0) return
        removeCallbacks(hide)
        lx = -1f; x = -1f
        failed = false
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (x >= 0) c.drawCircle(x, y, 36 * d, p)
        if (lx < 0) return
        val col = if (failed) LABEL else ACCENT
        p.color = col
        tag.color = col
        val h = 34 * d
        c.drawRect(lx - h, ly - h, lx + h, ly + h, p)
        val t = "AF-L"
        c.drawText(t, lx - tag.measureText(t) / 2f, ly - h - 5 * d, tag)
        p.color = ACCENT
    }
}

fun pill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun circle(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
fun outline(stroke: Int, radius: Float, width: Int) = GradientDrawable().apply {
    setColor(0xFF000000.toInt()); cornerRadius = radius; setStroke(width, stroke)
}
fun ring(stroke: Int, width: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(0xFF000000.toInt()); setStroke(width, stroke)
}
