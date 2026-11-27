package app.localmusic

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
    const val PANEL = BG
    const val CARD = BG // a "card" is BG + OUTLINE, never a grey fill
    const val OUTLINE = 0xFF222222.toInt()
    const val OUTLINE_BTN = 0xFF333333.toInt()
    const val LINE = 0xFF333333.toInt()
    const val TEXT = 0xFFE4E4E4.toInt()
    const val DIM = 0xFFA6A6A6.toInt()
    const val LABEL = 0xFF747474.toInt()
    const val FAINT = 0xFF484848.toInt()
    const val ACCENT = 0xFFE8A447.toInt()
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
fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
fun ripple(bg: Drawable?) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, bg ?: roundRect(-1, 0f))

fun lpL(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
fun lpF(w: Int, h: Int, gravity: Int = Gravity.NO_GRAVITY) = FrameLayout.LayoutParams(w, h, gravity)

fun Context.text(s: String, sp: Float = 14f, color: Int = C.TEXT, bold: Boolean = false, lines: Int = 1) = TextView(this).apply {
    text = s
    textSize = sp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
}

fun Context.pill(label: String, color: Int = C.CARD, textColor: Int = C.TEXT, stroke: Int = C.OUTLINE_BTN, onClick: (View) -> Unit) = TextView(this).apply {
    text = label
    textSize = 14f
    setTextColor(textColor)
    setTypeface(typeface, Typeface.BOLD)
    gravity = Gravity.CENTER
    background = ripple(if (color == C.BG) outline(dpf(20), stroke) else roundRect(color, dpf(20)))
    setPadding(dp(16), dp(8), dp(16), dp(8))
    setOnClickListener(onClick)
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

fun ImageView.tint(color: Int) { imageTintList = ColorStateList.valueOf(color) }

fun Context.askText(title: String, initial: String, ok: String = tr("Save", "Kaydet"), cb: (String) -> Unit) {
    val et = EditText(this).apply { setText(initial); setSingleLine(); selectAll() }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
    AlertDialog.Builder(this).setTitle(title).setView(box)
        .setPositiveButton(ok) { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(cb) }
        .setNegativeButton(tr("Cancel", "İptal"), null).show()
    et.requestFocus()
}

/** Any remaining list menu: a bottom sheet (app.plain.ui.Sheet) like the app's other menus. */
fun Context.menu(title: String?, items: List<Pair<String, () -> Unit>>) {
    if (this is android.app.Activity) { app.plain.ui.Sheet.list(this, title, items, C.ACCENT); return }
    val b = AlertDialog.Builder(this)
    if (title != null) b.setTitle(title)
    b.setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }.show()
}

fun Context.confirm(title: String, msg: String, ok: String, cb: () -> Unit) {
    AlertDialog.Builder(this).setTitle(title).setMessage(msg)
        .setPositiveButton(ok) { _, _ -> cb() }.setNegativeButton(tr("Cancel", "İptal"), null).show()
}

/** Album art square with a note placeholder. */
open class ArtView(ctx: Context, radius: Float) : ImageView(ctx) {
    var radius: Float = radius
        set(v) { if (field != v) { field = v; background = context.outline(v); invalidate() } }
    private val clip = android.graphics.Path()
    private val rect = RectF()

    init {
        scaleType = ScaleType.CENTER_CROP
        background = context.outline(radius)
    }

    fun placeholder() {
        setImageResource(R.drawable.mus_ic_note)
        imageTintList = ColorStateList.valueOf(C.FAINT)
        scaleType = ScaleType.CENTER
        val pad = width / 4
        setPadding(pad, pad, pad, pad)
    }

    override fun setImageBitmap(bm: android.graphics.Bitmap?) {
        imageTintList = null
        scaleType = ScaleType.CENTER_CROP
        setPadding(0, 0, 0, 0)
        super.setImageBitmap(bm)
    }

    fun bind(id: Long, px: Int) = Art.into(this, id, px) { placeholder() }

    /** Cover for a group: [songs] are tried in order (callers pass ArtPick lists), the first one with art wins. */
    fun bindAny(songs: List<Song>, px: Int) {
        Art.intoAny(this, songs.take(ArtPick.TRIES).map { it.id }.distinct(), px) { placeholder() }
    }

    /** 2×2 mosaic of different covers (packs, bundles, folders). */
    fun bindMosaic(songs: List<Song>, px: Int) = Art.mosaic(this, songs, px) { placeholder() }

    override fun onDraw(canvas: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); super.onDraw(canvas); canvas.restore()
    }
}

/** Album art that is always as tall as it is wide. */
class SquareArtView(ctx: Context, radius: Float) : ArtView(ctx, radius) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = super.onMeasure(widthMeasureSpec, widthMeasureSpec)
}

/**
 * Seek slider: thin track, thumb that grows while dragging, elapsed time on the left and remaining time on the right.
 * Seeks only when the finger is lifted; the labels follow the finger while dragging.
 */
class SeekSlider(ctx: Context) : View(ctx) {
    var onSeek: ((Long) -> Unit)? = null
    private var pos = 0L
    private var dur = 0L
    private var dragging = false
    private var dragPos = 0L
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.LINE; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT; strokeCap = Paint.Cap.ROUND }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.TEXT }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.DIM; textSize = ctx.dpf(12) }
    private val pad = ctx.dpf(10)
    private val barY get() = ctx_dp(14f)

    private fun ctx_dp(v: Float) = context.dpf(v)

    init {
        minimumHeight = ctx.dp(48)
        isClickable = true
        contentDescription = tr("Seek", "İlerleme çubuğu")
    }

    fun set(position: Long, duration: Long) {
        if (dragging) return
        if (position == pos && duration == dur) return
        pos = position; dur = duration
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), context.dp(48))
    }

    override fun onDraw(c: Canvas) {
        val w = width - 2 * pad
        val shown = if (dragging) dragPos else pos
        val f = if (dur > 0) (shown.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val h = if (dragging) context.dpf(5) else context.dpf(4)
        track.strokeWidth = h; fill.strokeWidth = h
        fill.color = if (dragging) C.ACCENT else C.TEXT
        c.drawLine(pad, barY, pad + w, barY, track)
        if (f > 0f) c.drawLine(pad, barY, pad + w * f, barY, fill)
        c.drawCircle(pad + w * f, barY, if (dragging) context.dpf(8) else context.dpf(6), thumb)
        val ty = barY + context.dpf(24)
        label.textAlign = Paint.Align.LEFT
        c.drawText(Text.time(shown), pad, ty, label)
        label.textAlign = Paint.Align.RIGHT
        c.drawText("-" + Text.time((dur - shown).coerceAtLeast(0)), pad + w, ty, label)
    }

    private fun at(x: Float): Long {
        val w = width - 2 * pad
        return (((x - pad) / w).coerceIn(0f, 1f) * dur).toLong()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dur <= 0) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true; dragPos = at(e.x)
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> { dragPos = at(e.x); invalidate() }
            MotionEvent.ACTION_UP -> {
                dragPos = at(e.x); dragging = false; pos = dragPos
                onSeek?.invoke(dragPos); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; invalidate() }
        }
        return true
    }
}

/** 2-dp progress line along the top of the mini player. */
class ProgressLine(ctx: Context) : View(ctx) {
    private var f = 0f
    private val p = Paint().apply { color = C.TEXT }
    private val bg = Paint().apply { color = C.OUTLINE_BTN }

    fun set(position: Long, duration: Long) {
        val n = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
        if (kotlin.math.abs(n - f) * width < 0.5f) return
        f = n; invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawRect(0f, 0f, width * f, height.toFloat(), p)
    }
}
