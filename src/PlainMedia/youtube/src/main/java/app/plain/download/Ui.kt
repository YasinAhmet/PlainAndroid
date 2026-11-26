package app.plain.download

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.plain.lang.tr

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

object C {
    // OLED: everything that isn't content is pure black (unlit pixels). Cards and chips are black with a 1 dp
    // outline instead of a grey fill; text is a notch below white and secondary text is dimmer.
    const val BG = 0xFF000000.toInt()
    const val OUTLINE = 0xFF333333.toInt()
    const val LINE = 0xFF333333.toInt()
    const val TEXT = 0xFFE4E4E4.toInt()
    const val DIM = 0xFFA6A6A6.toInt()
    const val LABEL = 0xFF747474.toInt()
    const val FAINT = 0xFF484848.toInt()
    const val ACCENT = 0xFFE6B422.toInt()
    const val RED = 0xFFD46A6A.toInt()
    const val GREEN = 0xFF6CBF6C.toInt()
    const val BLUE = 0xFF4A90E2.toInt() // members-only content (2.0)
}

/** Blue "MEMBERS" badge: members-only video (plays/downloads only with the account in ⋮ → Account). */
fun Context.membersBadge() = text(tr("MEMBERS", "ÜYELER"), 10f, C.BLUE, bold = true).apply {
    background = GradientDrawable().apply { setColor(0xCC000000.toInt()); cornerRadius = dpf(4); setStroke(dp(1), C.BLUE) }
    setPadding(dp(4), dp(1), dp(4), dp(1))
    contentDescription = tr("Members only", "Üyelere özel")
}

fun Context.dp(v: Number): Int = (v.toFloat() * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Number): Float = v.toFloat() * resources.displayMetrics.density
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }
/** Black shape with a 1 dp outline (OLED-friendly replacement for a grey fill). */
fun Context.outline(radius: Float, stroke: Int = C.OUTLINE) =
    GradientDrawable().apply { setColor(C.BG); cornerRadius = radius; setStroke(dp(1), stroke) }
fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
fun ripple(bg: Drawable?) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg ?: roundRect(-1, 0f))

fun lpL(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)

fun Context.text(s: String, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

/** Outlined button; [stroke]/[textColor] amber for the main action. */
fun Context.pill(label: String, textColor: Int = C.TEXT, stroke: Int = C.OUTLINE, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    textSize = 14f
    setTextColor(textColor)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    background = ripple(outline(dpf(20), stroke))
    setPadding(dp(16), dp(8), dp(16), dp(8))
    setOnClickListener(onClick)
}

/** Compact outlined button (list page bars, Feed actions). */
fun Context.smallPill(label: String, textColor: Int = C.TEXT, stroke: Int = C.OUTLINE, onClick: (View) -> Unit) =
    pill(label, textColor, stroke, onClick).apply { textSize = 12f; setPadding(dp(10), dp(4), dp(10), dp(4)) }

/** Selectable chip: the selected one gets an amber outline and amber text (no lit fill). */
class Chip(ctx: Context, label: String, onClick: (Chip) -> Unit) : TextView(ctx) {
    /** Smaller text and padding (filter rows). */
    fun compact() = apply { textSize = 12f; setPadding(context.dp(9), context.dp(3), context.dp(9), context.dp(3)) }

    init {
        text = label
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(ctx.dp(14), ctx.dp(7), ctx.dp(14), ctx.dp(7))
        setOnClickListener { onClick(this) }
        select(false)
    }

    fun select(on: Boolean) {
        isSelected = on
        setTextColor(if (on) C.ACCENT else C.DIM)
        background = ripple(context.outline(context.dpf(16), if (on) C.ACCENT else C.OUTLINE))
    }
}

fun Context.icon(res: Int, sizeDp: Int = 44, padDp: Int = 10, tint: Int = C.TEXT, desc: String, onClick: ((View) -> Unit)? = null) = ImageView(this).apply {
    setImageResource(res)
    imageTintList = ColorStateList.valueOf(tint)
    contentDescription = desc
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    if (onClick != null) {
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, oval(-1))
        setOnClickListener(onClick)
    }
}

fun Context.menu(title: String?, items: List<Pair<String, () -> Unit>>) {
    val b = AlertDialog.Builder(this)
    if (title != null) b.setTitle(title)
    b.setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
}

fun Context.message(title: String, msg: String) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton(tr("OK", "Tamam"), null).show()
}

/** Row of chips that wraps onto new lines. */
class FlowLayout(ctx: Context) : ViewGroup(ctx) {
    private val gap = ctx.dp(8)

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val maxW = MeasureSpec.getSize(wSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(wSpec), y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + gap; lineH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }
}

/** 2-dp progress line. */
class ProgressLine(ctx: Context) : View(ctx) {
    private var f = -1f
    private val p = Paint().apply { color = C.ACCENT }
    private val bg = Paint().apply { color = C.OUTLINE }

    fun set(done: Long, total: Long) {
        val n = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
        if (kotlin.math.abs(n - f) * width < 0.5f) return
        f = n; invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawRect(0f, 0f, width * f.coerceAtLeast(0f), height.toFloat(), p)
    }
}

/** Thumbnail clipped to rounded corners, 16:9. */
class Thumb(ctx: Context) : ImageView(ctx) {
    private val clip = android.graphics.Path()
    private val rect = android.graphics.RectF()
    private val r = ctx.dpf(12)

    init {
        scaleType = ScaleType.CENTER_CROP
        background = ctx.outline(r)
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        setMeasuredDimension(width, width * 9 / 16)
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); super.onDraw(canvas); canvas.restore()
    }
}

fun FrameLayout.LayoutParams.margins(l: Int, t: Int, r: Int, b: Int) = apply { setMargins(l, t, r, b) }
