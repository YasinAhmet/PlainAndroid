package app.plain.uninstaller

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Plain OLED palette: pure black, 1 dp outlines instead of grey fills, white-ish text. */
object C {
    const val BG = 0xFF000000.toInt()
    const val OUTLINE = 0xFF222222.toInt()
    const val OUTLINE_BTN = 0xFF333333.toInt()
    const val TEXT = 0xFFE4E4E4.toInt()
    const val DIM = 0xFFA6A6A6.toInt()
    const val LABEL = 0xFF747474.toInt()
    const val FAINT = 0xFF484848.toInt()
    const val ACCENT = 0xFFE8A447.toInt()
    const val ORANGE = 0xFFE8913A.toInt()
    const val RED = 0xFFD46A6A.toInt()
    const val GREEN = 0xFF6CBF6C.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

/** Black shape with a 1 dp outline (OLED-friendly replacement for a grey fill). */
fun Context.outline(radius: Float, stroke: Int = C.OUTLINE) =
    GradientDrawable().apply { setColor(C.BG); cornerRadius = radius; setStroke(dp(1), stroke) }

fun ripple(bg: Drawable?) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg ?: roundRect(-1, 0f))

fun lpL(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply { setMargins(l, t, r, b) }

fun Context.text(s: CharSequence, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

/** Rounded outlined button. [stroke] colours the outline, [textColor] the label. */
fun Context.pill(label: String, textColor: Int = C.TEXT, stroke: Int = C.OUTLINE_BTN, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    textSize = 14f
    setTextColor(textColor)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    background = ripple(outline(dpf(20), stroke))
    setPadding(dp(14), dp(8), dp(14), dp(8))
    setOnClickListener(onClick)
}

/** Filter chip: outlined, accent outline + text when on. */
fun Context.chip(label: String, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    textSize = 13f
    gravity = Gravity.CENTER
    setPadding(dp(12), dp(6), dp(12), dp(6))
    setOnClickListener(onClick)
    setChip(false)
}

fun TextView.setChip(on: Boolean) {
    setTextColor(if (on) C.ACCENT else C.DIM)
    background = ripple(context.outline(context.dpf(16), if (on) C.ACCENT else C.OUTLINE_BTN))
}

/** Small outlined tag, e.g. the orange "Risky" marker. */
fun Context.tag(label: String, color: Int) = TextView(this).apply {
    text = label
    textSize = 11f
    setTextColor(color)
    setTypeface(typeface, Typeface.BOLD)
    background = outline(dpf(8), color)
    setPadding(dp(6), dp(1), dp(6), dp(1))
}

fun Context.checkBox() = CheckBox(this).apply {
    buttonTintList = ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
        intArrayOf(C.ACCENT, C.LABEL),
    )
    isClickable = false
    isFocusable = false
}

fun Context.vbox(padH: Int = 0, padV: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
}

fun Context.hbox() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.scrollOf(child: View) = ScrollView(this).apply {
    isFillViewport = true
    overScrollMode = View.OVER_SCROLL_NEVER
    addView(child, FrameLayout.LayoutParams(MATCH, WRAP))
}

/** A list of actions: a bottom sheet (app.plain.ui.Sheet) like the other Plain menus. */
fun Context.menu(title: String?, items: List<Pair<String, () -> Unit>>) {
    if (this is android.app.Activity) { app.plain.ui.Sheet.list(this, title, items, C.ACCENT); return }
    val b = AlertDialog.Builder(this)
    if (title != null) b.setTitle(title)
    b.setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
}

/** Scrollable message dialog (long lists of apps don't fit the default message area). */
fun Context.longMessage(title: String, msg: CharSequence): AlertDialog.Builder {
    val tv = text(msg, 14f, C.TEXT, lines = 0).apply { setTextIsSelectable(true); setPadding(dp(24), dp(8), dp(24), dp(8)) }
    return AlertDialog.Builder(this).setTitle(title).setView(scrollOf(tv))
}
