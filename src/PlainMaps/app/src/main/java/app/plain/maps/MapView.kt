package app.plain.maps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A slippy map that only draws when something changes (gesture, tile arrived, new fix):
 * no continuous frames, so it costs nothing while idle.
 */
@SuppressLint("ViewConstructor")
class MapView(ctx: Context) : View(ctx) {

    /**
     * A line drawn over the map; parts are normalised-mercator x,y pairs. Its Path is built once per
     * zoom band and only moved while panning, so long routes cost almost nothing per frame.
     */
    class Line(val parts: List<DoubleArray>, val color: Int, val widthDp: Float, val casing: Boolean = true, val glow: Boolean = false) {
        internal var path: Path? = null
        internal var builtWorld = 0.0
        internal var x0 = 0.0
        internal var y0 = 0.0
    }

    /** A dot over the map; [glow] adds a soft halo (search results in view). */
    class Dot(val x: Double, val y: Double, val color: Int, val rDp: Float, val ring: Int = 0xFFFFFFFF.toInt(), val label: String? = null, val glow: Boolean = false)

    companion object {
        const val MIN_Z = 8.0
        const val MAX_Z = 20.0
        const val MAX_TILE_Z = 19
        private const val GLIDE_TAU = 0.16
    }

    var cx = Geo.mx(28.98)
    var cy = Geo.my(41.03)
    var zoom = 11.0

    private val density = resources.displayMetrics.density
    /** Tiles are drawn a little larger on dense screens so text stays readable. */
    val tilePx = (256.0 * (density / 2f).coerceIn(1f, 2f)).roundToInt()

    var tiles: Tiles? = null
        set(v) {
            field?.onLoaded = null
            field = v
            v?.onLoaded = { postInvalidate() }
        }

    var lines: List<Line> = emptyList()
        set(v) { field = v; invalidate() }
    var dots: List<Dot> = emptyList()
        set(v) { field = v; invalidate() }
    /** A dashed circle: x, y (normalised) and radius in metres. */
    var circle: DoubleArray? = null
        set(v) { field = v; invalidate() }

    var follow = false
    var onTap: ((Double, Double, Float, Float) -> Unit)? = null
    var onUserMove: (() -> Unit)? = null
    var onViewChanged: (() -> Unit)? = null
    var padTop = 0
    var padBottom = 0
        set(v) { field = v; invalidate() }

    private var meLat = Double.NaN
    private var meLon = Double.NaN
    private var meAcc = 0f
    /** When the shown position was measured; after a minute without news it is drawn grey. */
    private var meTime = 0L
    private val greyOut = Runnable { invalidate() }
    private var pendingFit: DoubleArray? = null

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val casePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xCC000000.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density * 1.5f; color = 0xCCFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(density * 6, density * 5), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = density * 11; setShadowLayer(density * 2, 0f, 0f, 0xFF000000.toInt())
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = density * 12; typeface = android.graphics.Typeface.DEFAULT_BOLD }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt(); textSize = density * 12; typeface = android.graphics.Typeface.DEFAULT_BOLD
        style = Paint.Style.STROKE; strokeWidth = density * 3.5f; strokeJoin = Paint.Join.ROUND
    }
    private val scalePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); strokeWidth = density * 2; style = Paint.Style.STROKE
        setShadowLayer(density * 2, 0f, 0f, 0xFF000000.toInt())
    }
    private val path = Path()
    private val dst = RectF()
    private val src = Rect()

    // ------------------------------------------------------------------ geometry
    fun world() = tilePx * 2.0.pow(zoom)
    fun xToM(sx: Float) = cx + (sx - width / 2.0) / world()
    fun yToM(sy: Float) = cy + (sy - height / 2.0) / world()
    fun sx(mx: Double) = ((mx - cx) * world() + width / 2.0).toFloat()
    fun sy(my: Double) = ((my - cy) * world() + height / 2.0).toFloat()

    /** The part of the map not covered by the bars: normalised x0, y0, x1, y1. */
    fun visibleNorm() = doubleArrayOf(xToM(0f), yToM(padTop.toFloat()), xToM(width.toFloat()), yToM((height - padBottom).toFloat()))

    /** Metres per screen pixel at the centre. */
    fun metersPerPx() = Geo.metersPerUnit(Geo.lat(cy)) / world()

    fun centerOn(lat: Double, lon: Double, z: Double? = null) {
        cx = Geo.mx(lon); cy = Geo.my(lat)
        if (z != null) zoom = z.coerceIn(MIN_Z, MAX_Z)
        // keep the point in the visible part above the sheet
        cy += ((padBottom - padTop) / 2.0) / world()
        changed()
    }

    /** Fits a normalised box (x0, y0, x1, y1) into the visible area. */
    fun fit(b: DoubleArray, maxZoom: Double = 17.0) {
        if (width == 0 || height == 0) { pendingFit = b; return }
        val pad = density * 40
        val w = (width - 2 * pad).coerceAtLeast(width / 2f)
        val h = (height - padTop - padBottom - 2 * pad).coerceAtLeast(height / 4f)
        val dx = (b[2] - b[0]).coerceAtLeast(1e-9)
        val dy = (b[3] - b[1]).coerceAtLeast(1e-9)
        zoom = min(log2(w / (tilePx * dx)), log2(h / (tilePx * dy))).coerceIn(MIN_Z, maxZoom)
        cx = (b[0] + b[2]) / 2
        cy = (b[1] + b[3]) / 2 + ((padBottom - padTop) / 2.0) / world()
        follow = false
        changed()
    }

    fun setMe(lat: Double, lon: Double, acc: Float, time: Long = System.currentTimeMillis()) {
        meLat = lat; meLon = lon; meAcc = acc; meTime = time
        removeCallbacks(greyOut)
        val left = 60_000 - (System.currentTimeMillis() - time)
        if (left > 0) postDelayed(greyOut, left + 50)
        if (follow) { cx = Geo.mx(lon); cy = Geo.my(lat) + ((padBottom - padTop) / 2.0) / world() }
        invalidate()
    }

    fun clearMe() { meLat = Double.NaN; invalidate() }

    /** The last position is known (shown live or grey). */
    fun hasMe() = !meLat.isNaN()
    fun meLat() = meLat
    fun meLon() = meLon

    private fun changed() {
        invalidate()
        onViewChanged?.invoke()
    }

    private fun zoomAround(fx0: Float, fy0: Float, dz: Double) {
        val fx = if (follow) width / 2f else fx0
        val fy = if (follow) height / 2f else fy0
        val mx = xToM(fx); val my = yToM(fy)
        zoom = (zoom + dz).coerceIn(MIN_Z, MAX_Z)
        val w = world()
        cx = (mx - (fx - width / 2.0) / w).coerceIn(0.0, 1.0)
        cy = (my - (fy - height / 2.0) / w).coerceIn(0.0, 1.0)
        changed()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pendingFit?.let { pendingFit = null; fit(it) }
    }

    // ------------------------------------------------------------------ gestures
    private var scaledSinceDown = false
    private var twoFingerT = 0L
    private var twoFingerMove = 0.0
    private var twoFingerScale = 0.0
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop

    private val scaler = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            scaledSinceDown = true
            val dz = log2(d.scaleFactor.toDouble())
            twoFingerScale += abs(dz)
            zoomAround(d.focusX, d.focusY, dz)
            return true
        }
    }).apply { isQuickScaleEnabled = true }

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (scaler.isInProgress) return true
            if (e2.pointerCount > 1) twoFingerMove += kotlin.math.hypot(dx.toDouble(), dy.toDouble())
            panBy(dx.toDouble(), dy.toDouble())
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaledSinceDown || e2.pointerCount > 1 || twoFingerT != 0L) return false
            startGlide(vx, vy)
            return true
        }

        override fun onDoubleTap(e: MotionEvent) = true

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !scaledSinceDown) zoomAround(e.x, e.y, 1.0)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onTap?.invoke(Geo.lat(yToM(e.y)), Geo.lon(xToM(e.x)), e.x, e.y)
            return true
        }
    })

    private fun panBy(dx: Double, dy: Double) {
        val w = world()
        cx = (cx + dx / w).coerceIn(0.0, 1.0)
        cy = (cy + dy / w).coerceIn(0.0, 1.0)
        if (follow) { follow = false; onUserMove?.invoke() }
        changed()
    }

    private var glideVx = 0.0
    private var glideVy = 0.0
    private var glideT = 0L
    private val glide = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - glideT) / 1e9).coerceIn(0.0, 0.05)
            glideT = now
            val k = kotlin.math.exp(-dt / GLIDE_TAU)
            glideVx *= k; glideVy *= k
            if (kotlin.math.hypot(glideVx, glideVy) < 40 * density) { stopGlide(); return }
            panBy(-glideVx * dt, -glideVy * dt)
            postOnAnimation(this)
        }
    }

    private fun startGlide(vx: Float, vy: Float) {
        val max = 5000.0 * density / 2.75
        val v = kotlin.math.hypot(vx.toDouble(), vy.toDouble())
        val s = if (v > max) max / v else 1.0
        glideVx = vx * s; glideVy = vy * s
        if (kotlin.math.hypot(glideVx, glideVy) < 150 * density) return
        glideT = System.nanoTime()
        removeCallbacks(glide)
        postOnAnimation(glide)
    }

    private fun stopGlide() {
        glideVx = 0.0; glideVy = 0.0
        removeCallbacks(glide)
    }

    override fun onDetachedFromWindow() {
        stopGlide()
        super.onDetachedFromWindow()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stopGlide(); scaledSinceDown = false; twoFingerT = 0L }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) {
                twoFingerT = e.eventTime; twoFingerMove = 0.0; twoFingerScale = 0.0
            } else twoFingerT = -1L
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount == 2 && twoFingerT > 0L) {
                if (e.eventTime - twoFingerT < 300 && twoFingerMove < slop && twoFingerScale < 0.07) {
                    zoomAround((e.getX(0) + e.getX(1)) / 2, (e.getY(0) + e.getY(1)) / 2, -1.0)
                }
                twoFingerT = -1L
            }
        }
        scaler.onTouchEvent(e)
        gestures.onTouchEvent(e)
        return true
    }

    // ------------------------------------------------------------------ drawing
    override fun onDraw(c: Canvas) {
        c.drawColor(TileRenderer.NODATA)
        val w = width.toDouble()
        val h = height.toDouble()
        val world = world()
        val ox = cx * world - w / 2
        val oy = cy * world - h / 2
        tiles?.let { t ->
            t.beginFrame()
            drawTiles(c, t, world, ox, oy, w, h)
            t.endFrame()
        }
        circle?.let { ci ->
            val r = (ci[2] / Geo.metersPerUnit(Geo.lat(ci[1])) * world).toFloat()
            fill.color = 0x18FFFFFF
            c.drawCircle((ci[0] * world - ox).toFloat(), (ci[1] * world - oy).toFloat(), r, fill)
            c.drawCircle((ci[0] * world - ox).toFloat(), (ci[1] * world - oy).toFloat(), r, circlePaint)
        }
        for (l in lines) drawOverlay(c, l, world, ox, oy)
        for (d in dots) {
            val x = (d.x * world - ox).toFloat(); val y = (d.y * world - oy).toFloat()
            if (x < -50 || y < -50 || x > w + 50 || y > h + 50) continue
            if (d.glow) {
                fill.color = (d.color and 0xFFFFFF) or 0x30000000; c.drawCircle(x, y, (d.rDp + 14f) * density, fill)
                fill.color = (d.color and 0xFFFFFF) or 0x55000000; c.drawCircle(x, y, (d.rDp + 7f) * density, fill)
            }
            fill.color = d.ring; c.drawCircle(x, y, (d.rDp + 1.8f) * density, fill)
            fill.color = d.color; c.drawCircle(x, y, d.rDp * density, fill)
        }
        for (d in dots) {
            val lbl = d.label ?: continue
            val x = (d.x * world - ox).toFloat() + (d.rDp + 5) * density
            val y = (d.y * world - oy).toFloat() + 4 * density
            c.drawText(lbl, x, y, labelHalo)
            c.drawText(lbl, x, y, labelPaint)
        }
        drawMe(c)
        drawScale(c, world)
    }

    private fun drawTiles(c: Canvas, p: Tiles, world: Double, ox: Double, oy: Double, w: Double, h: Double) {
        val tz = zoom.roundToInt().coerceIn(0, MAX_TILE_Z)
        val n = 1 shl tz
        val ts = world / n
        val x0 = floor(ox / ts).toInt().coerceAtLeast(0)
        val x1 = floor((ox + w) / ts).toInt().coerceAtMost(n - 1)
        val y0 = floor(oy / ts).toInt().coerceAtLeast(0)
        val y1 = floor((oy + h) / ts).toInt().coerceAtMost(n - 1)
        if (x0 > x1 || y0 > y1) return
        val ccx = cx * world / ts
        val ccy = cy * world / ts
        val list = ArrayList<Long>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) for (x in x0..x1) list.add((x.toLong() shl 32) or y.toLong())
        list.sortBy { val x = (it ushr 32).toInt(); val y = it.toInt(); (x + 0.5 - ccx).pow(2) + (y + 0.5 - ccy).pow(2) }
        for (t in list) {
            val x = (t ushr 32).toInt()
            val y = t.toInt()
            dst.set(floor(x * ts - ox).toFloat(), floor(y * ts - oy).toFloat(), floor((x + 1) * ts - ox).toFloat(), floor((y + 1) * ts - oy).toFloat())
            drawTile(c, p, tz, x, y)
        }
    }

    /** The tile, or the best cached ancestor scaled up while it renders. */
    private fun drawTile(c: Canvas, p: Tiles, z: Int, x: Int, y: Int) {
        var requested = false
        for (d in 0..6) {
            val pz = z - d
            if (pz < 0) break
            val px = x shr d
            val py = y shr d
            val k = Tiles.key(pz, px, py)
            val bmp = p.get(k)
            if (bmp != null) {
                if (d == 0) c.drawBitmap(bmp, null, dst, tilePaint)
                else {
                    val sub = bmp.width.toFloat() / (1 shl d)
                    val sx = (x - (px shl d)) * sub
                    val sy = (y - (py shl d)) * sub
                    src.set(sx.toInt(), sy.toInt(), (sx + sub).roundToInt(), (sy + sub).roundToInt())
                    c.drawBitmap(bmp, src, dst, tilePaint)
                }
                return
            }
            if (!requested) { p.want(k); requested = true }
        }
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** Builds the line's Path for this zoom (relative to its own corner, simplified to the pixel). */
    private fun build(l: Line, world: Double) {
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE
        for (p in l.parts) { var i = 0; while (i < p.size) { if (p[i] < x0) x0 = p[i]; if (p[i + 1] < y0) y0 = p[i + 1]; i += 2 } }
        val pa = Path()
        for (xy in l.parts) {
            val n = xy.size / 2
            if (n < 2) continue
            var lx = 0f; var ly = 0f
            for (i in 0 until n) {
                val x = ((xy[2 * i] - x0) * world).toFloat()
                val y = ((xy[2 * i + 1] - y0) * world).toFloat()
                if (i == 0) { pa.moveTo(x, y); lx = x; ly = y }
                else if (abs(x - lx) + abs(y - ly) >= 1.2f || i == n - 1) { pa.lineTo(x, y); lx = x; ly = y }
            }
        }
        l.path = pa; l.builtWorld = world; l.x0 = x0; l.y0 = y0
    }

    private fun drawOverlay(c: Canvas, l: Line, world: Double, ox: Double, oy: Double) {
        val ratio = if (l.path == null) 0.0 else world / l.builtWorld
        if (ratio < 0.8 || ratio > 1.25) build(l, world)
        val pa = l.path ?: return
        val s = (world / l.builtWorld).toFloat()
        val wpx = l.widthDp * density * Style.lineWidth / s
        c.save()
        c.translate((l.x0 * world - ox).toFloat(), (l.y0 * world - oy).toFloat())
        c.scale(s, s)
        if (l.glow) {
            glowPaint.color = (l.color and 0xFFFFFF) or 0x40000000
            glowPaint.strokeWidth = wpx * 4.5f
            c.drawPath(pa, glowPaint)
        }
        if (l.casing) { casePaint.strokeWidth = wpx + 2.5f * density / s; c.drawPath(pa, casePaint) }
        linePaint.color = l.color
        linePaint.strokeWidth = wpx
        c.drawPath(pa, linePaint)
        c.restore()
    }

    private fun drawMe(c: Canvas) {
        if (meLat.isNaN()) return
        val x = sx(Geo.mx(meLon)); val y = sy(Geo.my(meLat))
        val accPx = (meAcc * world() / Geo.metersPerUnit(meLat)).toFloat()
        if (accPx > density * 8 && System.currentTimeMillis() - meTime <= 60_000) {
            fill.color = 0x3329B6F6
            c.drawCircle(x, y, accPx, fill)
        }
        val stale = System.currentTimeMillis() - meTime > 60_000
        fill.color = if (stale) 0xFFBDBDBD.toInt() else 0xFFFFFFFF.toInt(); c.drawCircle(x, y, density * 9, fill)
        fill.color = if (stale) 0xFF757575.toInt() else 0xFF1E88E5.toInt(); c.drawCircle(x, y, density * 6.5f, fill)
    }

    private fun drawScale(c: Canvas, world: Double) {
        val mpp = Geo.metersPerUnit(Geo.lat(cy)) / world
        val target = density * 90 * mpp
        if (target <= 0 || target.isNaN()) return
        val p10 = 10.0.pow(floor(log10(target)))
        val nice = listOf(5.0, 2.0, 1.0).map { it * p10 }.first { it <= target }
        val len = (nice / mpp).toFloat()
        val x0 = density * 12
        val y0 = height - padBottom - density * 14
        c.drawLine(x0, y0, x0 + len, y0, scalePaint)
        c.drawLine(x0, y0 - density * 5, x0, y0, scalePaint)
        c.drawLine(x0 + len, y0 - density * 5, x0 + len, y0, scalePaint)
        val label = if (nice >= 1000) "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
        c.drawText(label, x0 + density * 4, y0 - density * 6, textPaint)
    }
}
