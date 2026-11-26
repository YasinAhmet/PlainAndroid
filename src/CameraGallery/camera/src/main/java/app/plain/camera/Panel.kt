package app.plain.camera

import android.content.Context
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import app.plain.lang.tr
import kotlin.math.roundToInt

/** Small builders for the Advanced tab's chips and option rows (OLED style). */
private fun Context.px(v: Int) = (v * resources.displayMetrics.density).roundToInt()

private fun Context.text(t: String, sp: Float, color: Int) = TextView(this).apply {
    text = t
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
    gravity = Gravity.CENTER
    isSingleLine = true
}

/** An outlined pill; amber outline and text when [on]. */
fun Context.chip(t: String, onClick: () -> Unit) = text(t, 13f, TEXT2).apply {
    setPadding(px(12), px(6), px(12), px(6))
    setOnClickListener { onClick() }
}

fun TextView.chipState(on: Boolean, open: Boolean, enabled: Boolean = true) {
    val c = context
    val stroke = if (open) TEXT else if (on) ACCENT else BTN_LINE
    background = outline(stroke, 16 * c.resources.displayMetrics.density, (c.resources.displayMetrics.density).roundToInt().coerceAtLeast(1))
    setTextColor(if (!enabled) LABEL else if (on) ACCENT else TEXT)
}

private fun Context.rowTitle(t: String) = text(t, 12f, LABEL).apply {
    gravity = Gravity.START or Gravity.CENTER_VERTICAL
    setPadding(0, 0, px(8), 0)
    minWidth = px(64)
}

/** "Title  [opt] [opt] [opt]…" scrolling sideways; [selected] decides which ones are amber. [onHold]: long-press on an option. */
fun Context.optionRow(title: String, labels: List<String>, selected: (Int) -> Boolean, onHold: ((Int) -> Unit)? = null, onPick: (Int) -> Unit): View {
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10), px(3), px(4), px(3)) }
    if (title.isNotEmpty()) row.addView(rowTitle(title))
    val inner = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
    labels.forEachIndexed { i, l ->
        val on = selected(i)
        inner.addView(text(l, 13f, if (on) ACCENT else TEXT).apply {
            setPadding(px(11), px(5), px(11), px(5))
            background = outline(if (on) ACCENT else BTN_LINE, px(14).toFloat(), px(1).coerceAtLeast(1))
            setOnClickListener { onPick(i) }
            if (onHold != null) setOnLongClickListener { onHold(i); true }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
    }
    val scroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(inner) }
    row.addView(scroll, LinearLayout.LayoutParams(0, -2, 1f))
    // keep the selected option in view
    scroll.post {
        val i = labels.indices.firstOrNull(selected) ?: return@post
        val v = inner.getChildAt(i) ?: return@post
        if (v.right > scroll.width) scroll.scrollTo(v.left - px(24), 0)
    }
    return row
}

/** "Title ———●——— value  Reset". [format] turns progress into the value text. */
fun Context.sliderRow(
    title: String, min: Int, max: Int, value: Int, format: (Int) -> String,
    onChange: (Int) -> Unit, onReset: (() -> Unit)?,
): View {
    val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(10), px(2), px(4), px(2)) }
    row.addView(rowTitle(title))
    val v = text(format(value), 13f, ACCENT).apply { minWidth = px(52) }
    val bar = SeekBar(this).apply {
        this.min = min
        this.max = max
        progress = value
        progressTintList = ColorStateList.valueOf(ACCENT)
        thumbTintList = ColorStateList.valueOf(ACCENT)
        progressBackgroundTintList = ColorStateList.valueOf(0xFF555555.toInt())
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) { if (user) { v.text = format(p); onChange(p) } }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }
    row.addView(bar, LinearLayout.LayoutParams(0, px(36), 1f))
    row.addView(v)
    if (onReset != null) row.addView(text(tr("Reset", "Sıfırla"), 12f, TEXT2).apply {
        setPadding(px(10), px(6), px(10), px(6))
        setOnClickListener { onReset() }
    })
    return row
}

/** A quiet line of explanation inside a panel. */
fun Context.noteRow(t: String) = text(t, 12f, LABEL).apply {
    isSingleLine = false
    gravity = Gravity.START
    setPadding(px(10), px(4), px(10), px(4))
}
