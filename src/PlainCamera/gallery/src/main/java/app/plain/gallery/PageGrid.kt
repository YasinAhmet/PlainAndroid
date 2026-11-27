package app.plain.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import kotlin.math.abs

/**
 * One fixed page of thumbnails drawn straight onto a canvas: [cols] × [rows] cells that exactly
 * fill the view. No scrolling: the activity swaps the page. With [labels] set it draws folders
 * (a cover with a name and a count under it); with [selecting] it marks the selected items.
 */
class PageGrid(ctx: Context) : View(ctx) {
    var cols = 4
    var rows = 6
    /** A null entry is a folder without a cover. */
    var items: List<Item?> = emptyList()
        set(v) { field = v; invalidate() }
    /** Folder mode: a name and a count for each cell. */
    var labels: List<Pair<String, String>>? = null
        set(v) { field = v; invalidate() }
    var selecting = false
        set(v) { field = v; invalidate() }
    var isSelected: (Item) -> Boolean = { false }
    var thumb: (Item) -> Bitmap? = { null }
    var onTap: ((Int) -> Unit)? = null
    var onHold: ((Int) -> Unit)? = null
    /** +1 = next page, -1 = previous (a quick sideways fling). */
    var onFling: ((Int) -> Unit)? = null
    /** Hold a cell, then slide without lifting: the cells from [anchor] to [to] (page indices); [onDragEnd] when the finger lifts. */
    var onDragSelect: ((anchor: Int, to: Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null
    /** Pinch: +1 = spread (bigger photos, fewer columns), −1 = pinch together (more columns). Once per gesture. */
    var onPinch: ((Int) -> Unit)? = null
    private var pinchScale = 1f
    private var pinched = false
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(det: android.view.ScaleGestureDetector): Boolean { pinchScale = 1f; return onPinch != null }
        override fun onScale(det: android.view.ScaleGestureDetector): Boolean {
            pinchScale *= det.scaleFactor
            if (!pinched && (pinchScale > 1.25f || pinchScale < 0.8f)) {
                pinched = true
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onPinch?.invoke(if (pinchScale > 1f) 1 else -1)
            }
            return true
        }
    })
    private var dragAnchor = -1
    private var dragAt = -1
    var onSize: ((Int, Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val gap = (2 * d)
    val labelH = (34 * d).toInt()
    private val empty = Paint().apply { color = LINE; style = Paint.Style.STROKE; strokeWidth = d }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xAA000000.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = 11 * d }
    private val nameP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textSize = 13 * d }
    private val countP = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT2; textSize = 11 * d }
    private val selFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
    private val dotShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88000000.toInt() }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT2; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); style = Paint.Style.STROKE; strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val tickPath = Path()
    private val src = Rect()
    private val dst = RectF()

    val cellW get() = if (cols > 0) width / cols else 0
    val cellH get() = if (rows > 0) height / rows - (if (labels != null) labelH else 0) else 0

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { onSize?.invoke(w, h) }

    override fun onDraw(c: Canvas) {
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        val lab = labels
        for ((i, it) in items.withIndex()) {
            val x = (i % cols) * cw
            val y = (i / cols) * ch
            val bottom = if (lab != null) y + ch - labelH else y + ch - gap / 2
            dst.set(x + gap / 2, y + gap / 2, x + cw - gap / 2, bottom)
            val b = it?.let(thumb)
            if (b == null || b.isRecycled) c.drawRect(dst, empty) else drawCrop(c, b)
            if (it != null && it.video && lab == null) {
                val t = "▶ " + if (it.duration > 0) Paging.duration(it.duration) else ""
                val tw = badgeText.measureText(t)
                val bx = dst.left + 4 * d
                val by = dst.bottom - 4 * d
                c.drawRoundRect(bx, by - 16 * d, bx + tw + 10 * d, by, 8 * d, 8 * d, badge)
                c.drawText(t, bx + 5 * d, by - 4.5f * d, badgeText)
            }
            if (lab != null) {
                val (name, count) = lab.getOrNull(i) ?: continue
                val w = cw - gap - 4 * d
                c.drawText(TextUtils.ellipsize(name, nameP, w, TextUtils.TruncateAt.END).toString(), x + gap / 2 + 2 * d, bottom + 15 * d, nameP)
                c.drawText(count, x + gap / 2 + 2 * d, bottom + 29 * d, countP)
            }
            if (selecting && it != null && lab == null) drawMark(c, isSelected(it))
        }
    }

    private fun drawCrop(c: Canvas, b: Bitmap) {
        val br = b.width.toFloat() / b.height
        val cr = dst.width() / dst.height()
        if (br > cr) {
            val w = (b.height * cr).toInt()
            src.set((b.width - w) / 2, 0, (b.width + w) / 2, b.height)
        } else {
            val h = (b.width / cr).toInt()
            src.set(0, (b.height - h) / 2, b.width, (b.height + h) / 2)
        }
        c.drawBitmap(b, src, dst, bmpPaint)
    }

    /** A ring in the corner; selected = an amber frame and a ticked amber dot. */
    private fun drawMark(c: Canvas, on: Boolean) {
        val r = 9 * d
        val cx = dst.right - r - 5 * d
        val cy = dst.top + r + 5 * d
        if (on) {
            val h = selFrame.strokeWidth / 2
            c.drawRect(dst.left + h, dst.top + h, dst.right - h, dst.bottom - h, selFrame)
            c.drawCircle(cx, cy, r, dotFill)
            tickPath.reset()
            tickPath.moveTo(cx - 4.5f * d, cy)
            tickPath.lineTo(cx - 1.2f * d, cy + 3.3f * d)
            tickPath.lineTo(cx + 4.5f * d, cy - 3.3f * d)
            c.drawPath(tickPath, tick)
        } else {
            c.drawCircle(cx, cy, r, dotShade)
            c.drawCircle(cx, cy, r, dotRing)
        }
    }

    private fun indexAt(e: MotionEvent): Int {
        val cw = width.toFloat() / cols
        val ch = height.toFloat() / rows
        val col = (e.x / cw).toInt().coerceIn(0, cols - 1)
        return (e.y / ch).toInt() * cols + col
    }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = indexAt(e)
            if (i in items.indices) { playSoundEffect(SoundEffectConstants.CLICK); onTap?.invoke(i) }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = indexAt(e)
            if (i in items.indices && onHold != null) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onHold?.invoke(i)
                if (onDragSelect != null && labels == null) { dragAnchor = i; dragAt = i; parent?.requestDisallowInterceptTouchEvent(true) }
            }
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vx) < abs(vy) * 1.5f || abs(vx) < 600 * d) return false
            onFling?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (dragAnchor < 0) {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            scaler.onTouchEvent(e)
            if (e.pointerCount > 1 || pinched || scaler.isInProgress) {
                // two fingers: no tap, hold or page fling
                if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.let { gestures.onTouchEvent(it); it.recycle() }
                return true
            }
            return gestures.onTouchEvent(e)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = indexAt(e).coerceIn(0, (items.size - 1).coerceAtLeast(0))
                if (e.y >= 0 && i != dragAt && i in items.indices) {
                    dragAt = i
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onDragSelect?.invoke(dragAnchor, i)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragAnchor = -1; dragAt = -1
                onDragEnd?.invoke()
                MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }.let { gestures.onTouchEvent(it); it.recycle() } // no tap, no fling
            }
        }
        return true
    }
}
