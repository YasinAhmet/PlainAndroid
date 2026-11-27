package app.trekmap.trail

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    const val BG = 0xFF000000.toInt()
    const val PANEL = 0xEE121212.toInt()
    const val CARD = 0xFF262626.toInt()
    const val TEXT = 0xFFEDEDED.toInt()
    const val DIM = 0xFF9E9E9E.toInt()
    const val GREEN = 0xFF2E7D32.toInt()
    const val RED = 0xFFC62828.toInt()
    const val PLAN = 0xFFFF4081.toInt()
    const val TRACK = 0xFF29B6F6.toInt()
    const val PREVIEW = 0xFFFFCA28.toInt()
    const val WIFI = 0xFF81C784.toInt()
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density

fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
fun ripple(bg: Drawable) = RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), bg, null)

fun lpL(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun lpF(w: Int, h: Int, gravity: Int) = FrameLayout.LayoutParams(w, h, gravity)

fun Context.text(s: String, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.button(label: String, color: Int = C.CARD, onClick: (View) -> Unit): Button = Button(this).apply {
    text = label
    isAllCaps = false
    textSize = 14f
    setTextColor(C.TEXT)
    background = ripple(roundRect(color, dpf(10)))
    stateListAnimator = null
    minWidth = 0; minimumWidth = 0
    minHeight = dp(36); minimumHeight = dp(36)
    setPadding(dp(12), 0, dp(12), 0)
    gravity = Gravity.CENTER
    setOnClickListener(onClick)
}

fun Context.askText(title: String, initial: String, ok: String = tr("Save", "Kaydet"), cb: (String) -> Unit) {
    val et = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton(ok) { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(cb) }
        .setNegativeButton(tr("Cancel", "İptal"), null).show()
}

fun Context.confirm(title: String, msg: String, ok: String, cb: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> cb() }
        .setNegativeButton(tr("Cancel", "İptal"), null).show()
}

/** A list of actions: a bottom sheet (app.plain.ui.Sheet) like the other Plain menus. */
fun Context.choose(title: String, items: List<Pair<String, () -> Unit>>) {
    if (this is android.app.Activity) { app.plain.ui.Sheet.list(this, title, items, C.TRACK); return }
    AlertDialog.Builder(this).setTitle(title)
        .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
        .show()
}

object Fmt {
    fun dist(m: Double): String =
        if (m < 1000) "${m.roundToInt()} m" else String.format(Locale.getDefault(), "%.2f km", m / 1000)

    fun dur(sec: Long): String {
        val s = sec.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, ss) else String.format(Locale.US, "%d:%02d", m, ss)
    }

    fun date(t: Long): String = SimpleDateFormat("d MMM yyyy HH:mm", PlainLang.locale).format(Date(t))

    fun mb(bytes: Long): String {
        val mb = bytes / 1048576.0
        return if (mb < 1000) String.format(Locale.getDefault(), "%.0f MB", mb)
        else String.format(Locale.getDefault(), "%.1f GB", mb / 1024)
    }
}
