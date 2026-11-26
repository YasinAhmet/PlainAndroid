package app.plain.gallery

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.min

/**
 * One bitmap fitted to the screen; pinch / double-tap zoom (up to 8x), drag to pan while zoomed.
 * At normal size a sideways fling means next / previous and a tap toggles the bars.
 */
class ZoomImage(ctx: Context) : View(ctx) {
    var onTap: (() -> Unit)? = null
    var onSwipe: ((Int) -> Unit)? = null
    var onZoomed: ((Float) -> Unit)? = null

    private var bmp: Bitmap? = null
    private var zoom = 1f
    private var left = 0f
    private var top = 0f
    private var scaled = false
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val m = Matrix()

    val isZoomed get() = zoom > 1.01f

    /** [reset] = a different item (back to fit); otherwise the view is kept if the shape matches. */
    fun setBitmap(b: Bitmap?, reset: Boolean = false) {
        val old = bmp
        bmp = b
        val same = old != null && b != null && abs(old.width.toFloat() / old.height - b.width.toFloat() / b.height) < 0.01f
        if (reset || !same) { zoom = 1f; centre() }
        invalidate()
    }

    private fun fit(): Float {
        val b = bmp ?: return 1f
        return min(width.toFloat() / b.width, height.toFloat() / b.height)
    }

    private fun dw() = (bmp?.width ?: 0) * fit() * zoom
    private fun dh() = (bmp?.height ?: 0) * fit() * zoom
    private fun centre() { left = (width - dw()) / 2; top = (height - dh()) / 2 }

    private fun clamp() {
        val w = dw(); val h = dh()
        left = if (w <= width) (width - w) / 2 else left.coerceIn(width - w, 0f)
        top = if (h <= height) (height - h) / 2 else top.coerceIn(height - h, 0f)
    }

    private fun zoomTo(z: Float, fx: Float, fy: Float) {
        val nz = z.coerceIn(1f, 8f)
        val f = nz / zoom
        left = fx - (fx - left) * f
        top = fy - (fy - top) * f
        zoom = nz
        clamp()
        invalidate()
        onZoomed?.invoke(zoom)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { zoom = 1f; centre() }

    override fun onDraw(c: Canvas) {
        val b = bmp ?: return
        if (b.isRecycled) return
        val s = fit() * zoom
        m.setScale(s, s)
        m.postTranslate(left, top)
        c.drawBitmap(b, m, paint)
    }

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaled = true
            zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { onTap?.invoke(); return true }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) zoomTo(1f, e.x, e.y) else zoomTo(2.5f, e.x, e.y)
            return true
        }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (zoom <= 1.01f || scaler.isInProgress) return false
            left -= dx; top -= dy
            clamp()
            invalidate()
            return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaled || zoom > 1.01f || abs(vx) < abs(vy) * 1.2f) return false
            onSwipe?.invoke(if (vx < 0) 1 else -1)
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) scaled = false
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        return true
    }
}
