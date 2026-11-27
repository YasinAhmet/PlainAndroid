package app.plain.voice

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import kotlin.math.roundToInt

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

// OLED palette: black everywhere, thin outlines instead of grey fills, no pure white text
const val TEXT = 0xFFE4E4E4.toInt()
const val TEXT2 = 0xFFA6A6A6.toInt()
const val LABEL = 0xFF747474.toInt()
const val LINE = 0xFF222222.toInt()
const val BTN_LINE = 0xFF333333.toInt()
const val ACCENT = 0xFFE6B422.toInt()
const val REC = 0xFFD84343.toInt()

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

fun outline(stroke: Int, radius: Float, width: Int) = GradientDrawable().apply {
    setColor(Color.BLACK); cornerRadius = radius; setStroke(width, stroke)
}

fun ring(stroke: Int, width: Int) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL; setColor(Color.BLACK); setStroke(width, stroke)
}
