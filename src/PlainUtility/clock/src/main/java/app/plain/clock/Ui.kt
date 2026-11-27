package app.plain.clock

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.TextView
import kotlin.math.roundToInt

/** OLED palette (see the plain-design skill): black, thin outlines, three greys, one accent. */
object C {
    const val BG = 0xFF000000.toInt()
    const val OUTLINE = 0xFF222222.toInt()
    const val OUTLINE_BTN = 0xFF333333.toInt()
    const val TEXT = 0xFFE4E4E4.toInt()
    const val DIM = 0xFFA6A6A6.toInt()
    const val LABEL = 0xFF747474.toInt()
    const val FAINT = 0xFF484848.toInt()
    const val RED = 0xFFD46A6A.toInt()
    /** Plain Utility's violet (UtilityApp.ACCENT). */
    const val ACCENT = 0xFFA594F9.toInt()
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Int) = v * resources.displayMetrics.density

fun Context.text(t: CharSequence, sp: Float, color: Int = C.TEXT, lines: Int = 1, bold: Boolean = false) = TextView(this).apply {
    text = t
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    if (lines == 1) isSingleLine = true
}

/** Black with a 1 dp outline: cards, chips, buttons. Never a fill. */
fun Context.outline(radius: Float, stroke: Int = C.OUTLINE) = GradientDrawable().apply {
    setColor(C.BG); cornerRadius = radius; setStroke(dp(1).coerceAtLeast(1), stroke)
}

fun Context.ring(stroke: Int, width: Int = 1) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(C.BG); setStroke(dp(width).coerceAtLeast(1), stroke)
}

fun ripple(bg: Drawable?, mask: Drawable? = null) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, mask)

/** An outlined pill button (bold 14 sp); the screen's one primary action passes the accent as text and stroke. */
fun Context.pill(t: String, textColor: Int = C.TEXT, stroke: Int = C.OUTLINE_BTN, onClick: () -> Unit) =
    text(t, 14f, textColor, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(16), 0, dp(16), 0)
        background = ripple(outline(dpf(22), stroke))
        setOnClickListener { onClick() }
    }

fun View.enable(on: Boolean) { isEnabled = on; alpha = if (on) 1f else 0.35f }
