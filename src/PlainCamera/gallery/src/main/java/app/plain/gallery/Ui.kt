package app.plain.gallery

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import app.plain.lang.tr
import kotlin.math.roundToInt

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

// OLED palette: black everywhere, thin outlines instead of grey fills, no pure white text
const val TEXT = 0xFFE4E4E4.toInt()
const val TEXT2 = 0xFFA6A6A6.toInt()
const val LABEL = 0xFF747474.toInt()
const val LINE = 0xFF222222.toInt()
const val BTN_LINE = 0xFF333333.toInt()
const val ACCENT = 0xFFE6B422.toInt()

fun Context.label(t: String, sp: Float = 15f, color: Int = TEXT) = TextView(this).apply {
    text = t
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    gravity = Gravity.CENTER
    setPadding(dp(14), dp(8), dp(14), dp(8))
}

fun Context.button(t: String, onClick: () -> Unit) = label(t, 15f).apply {
    background = outline(BTN_LINE, dp(20).toFloat(), dp(1))
    isSingleLine = true
    setOnClickListener { onClick() }
}

fun pill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun outline(stroke: Int, radius: Float, width: Int) = GradientDrawable().apply {
    setColor(Color.BLACK); cornerRadius = radius; setStroke(width, stroke)
}

/** "Moved 3 to trash · UNDO" along the bottom for a few seconds after a delete. */
class UndoBar(ctx: Context) : LinearLayout(ctx) {
    private val text = ctx.label("", 15f).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
    private val btn = ctx.label(tr("UNDO", "GERİ AL"), 15f, ACCENT).apply { contentDescription = tr("Undo", "Geri al") }
    private val hide = Runnable { visibility = GONE; onGone?.invoke() }
    /** Called when the bar goes away (timed out or undone). */
    var onGone: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = outline(BTN_LINE, ctx.dp(12).toFloat(), ctx.dp(1))
        visibility = GONE
        isClickable = true
        addView(text, LayoutParams(0, -2, 1f))
        addView(btn, LayoutParams(-2, -2))
    }

    val showing get() = visibility == VISIBLE

    fun show(msg: String, undo: () -> Unit) {
        text.text = msg
        btn.setOnClickListener { removeCallbacks(hide); undo(); hide.run() }
        visibility = VISIBLE
        removeCallbacks(hide)
        postDelayed(hide, 8000)
    }

    fun dismiss() { if (showing) { removeCallbacks(hide); hide.run() } }
}
