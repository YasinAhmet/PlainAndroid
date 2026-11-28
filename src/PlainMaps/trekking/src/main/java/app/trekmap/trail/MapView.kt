package app.trekmap.trail

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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A minimal slippy map. It only draws when something changes (gesture, tile arrived, new fix):
 * no continuous frames — the screen work costs nothing while idle. The only animation is a short
 * glide after a swipe (well under a second), so dragging feels natural.
 */
@SuppressLint("ViewConstructor")
class MapView(ctx: Context) : View(ctx) {

    class Overlay(val lines: List<MLine>, val color: Int, val widthDp: Float, val endpoints: Boolean = false)

    /** A photo pin: drawn upright above its point; [tag] is handed back on tap. */
    class Marker(val lat: Double, val lon: Double, val bmp: android.graphics.Bitmap?, val tag: Any?)

    companion object {
        const val MIN_Z = 2.0
        const val MAX_Z = 20.0
        /** Glide time constant (s): a fast swipe carries on for roughly half a second. */
        private const val GLIDE_TAU = 0.16
        private const val PHOTO = 0xFF7E57C2.toInt() // purple: distinct from the plan, track and land colours
    }

    var cx = 0.5
    var cy = 0.5
    var zoom = 3.0

    private val density = resources.displayMetrics.density
    /** Tiles are shown a bit larger on dense screens so labels stay readable. */
    val tilePx = 256.0 * (density / 2f).coerceIn(1f, 2f)

    var layer = Layer.TOPO
    /** A transparent tile layer drawn over the map (the private-land layer), or null. */
    var overlayLayer: Layer? = null
        set(v) { field = v; invalidate() }
    var provider: TileProvider? = null
        set(v) {
            field?.onLoaded = null
            field = v
            v?.onLoaded = { postInvalidate() }
        }
    var maxTileZ: () -> Int = { layer.maxZ }
    var bg = 0xFF202020.toInt()
    var overlays: List<Overlay> = emptyList()
    var rects: List<BBox> = emptyList()
    var follow = false
    /** 0 = normal, up to 1 = black: dims the map tiles (Settings › Dim map). */
    var dim = 0f
        set(v) { field = v; invalidate() }
    var onTap: ((Double, Double) -> Unit)? = null
    var onUserMove: (() -> Unit)? = null
    var attribution = ""
    /** Screen space covered by floating panels; used for fitting, scale bar and attribution. */
    var padTop = 0
    var padBottom = 0

    private var meLat = Double.NaN
    private var meLon = Double.NaN
    private var meAcc = 0f
    private var pendingFit: BBox? = null

    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val casePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0x99000000.toInt()
    }
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = density * 2; color = 0xCC66BB6A.toInt()
        pathEffect = DashPathEffect(floatArrayOf(density * 8, density * 6), 0f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = density * 11; setShadowLayer(density * 2, 0f, 0f, 0xFF000000.toInt())
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
    /** Map rotation in degrees: this compass direction points up on screen (0 = north up). */
    var bearing = 0f
        private set
    private var cosB = 1.0
    private var sinB = 0.0

    fun setBearing(deg: Float) {
        val d = ((deg % 360) + 360) % 360
        if (abs(d - bearing) < 0.05f) return
        bearing = d
        val r = Math.toRadians(d.toDouble())
        cosB = kotlin.math.cos(r); sinB = kotlin.math.sin(r)
        invalidate()
    }

    // a screen offset (from the view centre) expressed in map pixels, undoing the rotation
    private fun offX(sx: Double, sy: Double) = sx * cosB - sy * sinB
    private fun offY(sx: Double, sy: Double) = sx * sinB + sy * cosB
    private fun xToM(sx: Float, sy: Float) = cx + offX(sx - width / 2.0, sy - height / 2.0) / world()
    private fun yToM(sx: Float, sy: Float) = cy + offY(sx - width / 2.0, sy - height / 2.0) / world()
    private fun xToM(sx: Float) = xToM(sx, height / 2f)
    private fun yToM(sy: Float) = yToM(width / 2f, sy)

    fun visibleBBox(coveredTop: Int = padTop, coveredBottom: Int = padBottom): BBox {
        val top = coveredTop.toFloat()
        val bottom = (height - coveredBottom).toFloat().coerceAtLeast(top + 1)
        return BBox(
            Geo.lon(xToM(0f).coerceIn(0.0, 1.0)), Geo.lat(yToM(bottom).coerceIn(0.0, 1.0)),
            Geo.lon(xToM(width.toFloat()).coerceIn(0.0, 1.0)), Geo.lat(yToM(top).coerceIn(0.0, 1.0)),
        )
    }

    fun centerOn(lat: Double, lon: Double, z: Double? = null) {
        if (!lat.isFinite() || !lon.isFinite()) return
        cx = Geo.mx(lon.coerceIn(-180.0, 180.0)); cy = Geo.my(lat)
        if (z != null && z.isFinite()) zoom = z.coerceIn(MIN_Z, MAX_Z)
        invalidate()
    }

    fun fit(b: BBox) {
        // a damaged box (NaN / infinite) would put NaN into the view and crash every frame after it
        if (!(b.w.isFinite() && b.s.isFinite() && b.e.isFinite() && b.n.isFinite())) return
        if (width == 0 || height == 0) { pendingFit = b; return }
        val pad = density * 32
        val w = (width - 2 * pad).coerceAtLeast(width / 2f)
        val h = (height - padTop - padBottom - 2 * pad).coerceAtLeast(height / 3f)
        val dx = (Geo.mx(b.e) - Geo.mx(b.w)).coerceAtLeast(1e-9)
        val dy = (Geo.my(b.s) - Geo.my(b.n)).coerceAtLeast(1e-9)
        zoom = min(log2(w / (tilePx * dx)), log2(h / (tilePx * dy))).coerceIn(MIN_Z, 17.5)
        cx = (Geo.mx(b.w) + Geo.mx(b.e)) / 2
        // shift so the box is centred in the uncovered area
        cy = (Geo.my(b.n) + Geo.my(b.s)) / 2 + ((padBottom - padTop) / 2.0) / world()
        follow = false
        invalidate()
    }

    fun setMe(lat: Double, lon: Double, acc: Float) {
        meLat = lat; meLon = lon; meAcc = acc
        if (follow) { cx = Geo.mx(lon); cy = Geo.my(lat) }
        invalidate()
    }

    fun clearMe() {
        meLat = Double.NaN
        invalidate()
    }

    fun zoomBy(dz: Double) = zoomAround(width / 2f, height / 2f, dz)

    private fun zoomAround(fx0: Float, fy0: Float, dz: Double) {
        if (!dz.isFinite()) return
        val fx = if (follow) width / 2f else fx0
        val fy = if (follow) height / 2f else fy0
        val mx = xToM(fx, fy); val my = yToM(fx, fy)
        zoom = (zoom + dz).coerceIn(MIN_Z, MAX_Z)
        val w = world()
        val ox = offX(fx - width / 2.0, fy - height / 2.0)
        val oy = offY(fx - width / 2.0, fy - height / 2.0)
        cx = (mx - ox / w).coerceIn(0.0, 1.0)
        cy = (my - oy / w).coerceIn(0.0, 1.0)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        pendingFit?.let { pendingFit = null; fit(it) }
    }

    // ------------------------------------------------------------------ gestures
    // One finger: drag (with a short glide), double-tap zooms in, double-tap-and-drag zooms smoothly
    // (down = in, up = out, like most map apps). Two fingers: pinch, or a quick two-finger tap zooms out.
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

        // zoom in when the second tap lifts — unless it turned into a double-tap-and-drag zoom
        override fun onDoubleTap(e: MotionEvent) = true

        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_UP && !scaledSinceDown) zoomAround(e.x, e.y, 1.0)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            if (compassButton && onNeedle(e.x, e.y)) { onCompassTap?.invoke(); return true }
            markerAt(e.x, e.y)?.let { m -> onMarkerTap?.invoke(m); return true }
            val cb = onTap ?: return false
            cb(Geo.lat(yToM(e.x, e.y)), Geo.lon(xToM(e.x, e.y)))
            return true
        }
    })

    private fun panBy(dx: Double, dy: Double) {
        val w = world()
        cx = (cx + offX(dx, dy) / w).coerceIn(0.0, 1.0)
        cy = (cy + offY(dx, dy) / w).coerceIn(0.0, 1.0)
        if (follow) { follow = false; onUserMove?.invoke() }
        invalidate()
    }

    // --- glide after a swipe: velocity decays quickly, frames stop as soon as it settles
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
                // a quick two-finger tap (no pinch, no drag) zooms out around the fingers
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
    private var frames = 0L
    private var drawNs = 0L
    private var maxDrawNs = 0L

    /** Development stats since the last call (frames drawn and how long they took). */
    fun takeStats(): String {
        val s = "frames $frames · draw avg ${if (frames > 0) "%.2f".format(java.util.Locale.US, drawNs / frames / 1e6) else "0"} ms" +
            " max ${"%.1f".format(java.util.Locale.US, maxDrawNs / 1e6)} ms"
        frames = 0; drawNs = 0; maxDrawNs = 0
        return s
    }

    override fun onDraw(c: Canvas) {
        val t0 = System.nanoTime()
        drawMap(c)
        val dt = System.nanoTime() - t0
        frames++
        drawNs += dt
        if (dt > maxDrawNs) maxDrawNs = dt
    }

    /** Last line of defence: a NaN / out-of-range view would throw in roundToInt() on every frame (a crash loop). */
    private fun sanitize() {
        if (!zoom.isFinite()) zoom = MIN_Z else if (zoom !in MIN_Z..MAX_Z) zoom = zoom.coerceIn(MIN_Z, MAX_Z)
        if (!cx.isFinite()) cx = 0.5 else if (cx !in 0.0..1.0) cx = cx.coerceIn(0.0, 1.0)
        if (!cy.isFinite()) cy = 0.5 else if (cy !in 0.0..1.0) cy = cy.coerceIn(0.0, 1.0)
    }

    private fun drawMap(c: Canvas) {
        sanitize()
        c.drawColor(bg)
        val w = width.toDouble()
        val h = height.toDouble()
        val world = world()
        val ox = cx * world - w / 2
        val oy = cy * world - h / 2
        val rotated = bearing != 0f
        if (rotated) { c.save(); c.rotate(-bearing, (w / 2).toFloat(), (h / 2).toFloat()) }
        // when rotated, cover the circle around the screen so the corners never show gaps
        val half = if (rotated) kotlin.math.hypot(w, h) / 2 else 0.0
        provider?.beginFrame() // only tiles wanted by this frame get loaded
        provider?.let {
            if (rotated) drawTiles(c, it, layer, world, ox, oy, cx * world - half, cy * world - half, 2 * half, 2 * half)
            else drawTiles(c, it, layer, world, ox, oy, ox, oy, w, h)
        }
        if (dim > 0f) c.drawColor(((dim * 255).toInt() shl 24)) // darken tiles only; lines and dot stay bright
        val ov = overlayLayer
        if (ov != null && zoom >= ov.minOverlayZ - 0.5) provider?.let {
            if (rotated) drawTiles(c, it, ov, world, ox, oy, cx * world - half, cy * world - half, 2 * half, 2 * half)
            else drawTiles(c, it, ov, world, ox, oy, ox, oy, w, h)
        }
        provider?.endFrame()

        for (r in rects) {
            c.drawRect(
                (Geo.mx(r.w) * world - ox).toFloat(), (Geo.my(r.n) * world - oy).toFloat(),
                (Geo.mx(r.e) * world - ox).toFloat(), (Geo.my(r.s) * world - oy).toFloat(), rectPaint,
            )
        }
        for (o in overlays) {
            linePaint.color = o.color
            linePaint.strokeWidth = o.widthDp * density
            casePaint.strokeWidth = (o.widthDp + 2.5f) * density
            for (line in o.lines) drawLine(c, line, world, ox, oy)
            if (o.endpoints) {
                val first = o.lines.firstOrNull { it.n > 0 }
                val last = o.lines.lastOrNull { it.n > 0 }
                if (first != null) dot(c, first.xy[0], first.xy[1], world, ox, oy, 0xFF43A047.toInt())
                if (last != null) dot(c, last.xy[2 * last.n - 2], last.xy[2 * last.n - 1], world, ox, oy, 0xFFE53935.toInt())
            }
        }
        if (rotated) c.restore()
        // photos sit on the route but under your position, which always stays visible
        drawMarkers(c)
        drawMe(c)
        if (rotated || compassButton) drawNorth(c)
        drawScale(c, world)
        stats?.let { drawStats(c, it()) }
        if (attribution.isNotEmpty()) {
            val tw = textPaint.measureText(attribution)
            c.drawText(attribution, width - tw - density * 6, height - padBottom - density * 6, textPaint)
        }
    }

    /** ox/oy position tiles on the canvas; ex/ey/ew/eh is the map-pixel extent to cover. */
    private fun drawTiles(c: Canvas, p: TileProvider, l: Layer, world: Double, ox: Double, oy: Double, ex: Double, ey: Double, ew: Double, eh: Double) {
        val base = l == layer
        val tz = zoom.roundToInt().coerceIn(0, (if (base) maxTileZ() else l.maxZ).coerceAtLeast(0))
        if (base) tileZ = tz
        val n = 1 shl tz
        val ts = world / n
        val x0 = floor(ex / ts).toInt().coerceAtLeast(0)
        val x1 = floor((ex + ew) / ts).toInt().coerceAtMost(n - 1)
        val y0 = floor(ey / ts).toInt().coerceAtLeast(0)
        val y1 = floor((ey + eh) / ts).toInt().coerceAtMost(n - 1)
        if (base) drawnZ = -1
        if (x0 > x1 || y0 > y1) return
        // centre tiles first so they load first
        val ccx = cx * world / ts
        val ccy = cy * world / ts
        if (base) { centerTx = floor(ccx).toInt(); centerTy = floor(ccy).toInt() }
        val tiles = ArrayList<Long>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) for (x in x0..x1) tiles.add((x.toLong() shl 32) or y.toLong())
        tiles.sortBy { val x = (it ushr 32).toInt(); val y = it.toInt(); (x + 0.5 - ccx).pow(2) + (y + 0.5 - ccy).pow(2) }
        for (t in tiles) {
            val x = (t ushr 32).toInt()
            val y = t.toInt()
            dst.set(
                floor(x * ts - ox).toFloat(), floor(y * ts - oy).toFloat(),
                floor((x + 1) * ts - ox).toFloat(), floor((y + 1) * ts - oy).toFloat(),
            )
            drawTile(c, p, l, base, tz, x, y)
        }
    }

    /** Draws the tile, or the best cached ancestor scaled up while it loads / when it doesn't exist. */
    private fun drawTile(c: Canvas, p: TileProvider, l: Layer, base: Boolean, z: Int, x: Int, y: Int) {
        var requested = false
        for (d in 0..6) {
            val pz = z - d
            if (pz < 0 || (!base && pz < l.minOverlayZ)) break
            val px = x shr d
            val py = y shr d
            val k = TileProvider.key(l, pz, px, py)
            val bmp = p.get(k)
            if (bmp != null) {
                if (base && x == centerTx && y == centerTy) drawnZ = pz
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
            if (!requested && !p.isMissing(k)) {
                p.want(k)
                requested = true
            }
        }
    }

    // ------------------------------------------------------------------ photo pins
    // Small thumbnails standing just above their spot (a short pointer marks the exact place), so the
    // route stays visible. Photos whose thumbnails would overlap merge: the first one shows, with a count.
    var markers: List<Marker> = emptyList()
        set(v) { field = v; invalidate() }
    var onMarkerTap: ((Marker) -> Unit)? = null
    private val pt = FloatArray(2)
    private val pinRect = RectF()
    private val pinPath = Path()
    private class Group(val x: Float, val y: Float, val first: Marker) { var n = 1 }
    private val groups = ArrayList<Group>()
    private val pinHalf = density * 12   // thumbnail: 24 dp square
    private val pinLift = density * 18   // its centre sits this far above the spot
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = density * 9.5f; textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /** Screen position of a normalised map point, including the compass rotation. */
    private fun screenOf(mx: Double, my: Double, out: FloatArray) {
        val w = world()
        val dx = (mx - cx) * w
        val dy = (my - cy) * w
        out[0] = (width / 2.0 + dx * cosB + dy * sinB).toFloat()
        out[1] = (height / 2.0 - dx * sinB + dy * cosB).toFloat()
    }

    /** Groups the visible photos whose thumbnails would overlap (greedy, in the order they were taken). */
    private fun buildGroups() {
        groups.clear()
        val r = pinHalf * 2 + density * 4
        val r2 = r * r
        val m0 = density * 40
        for (m in markers) {
            screenOf(Geo.mx(m.lon), Geo.my(m.lat), pt)
            val x = pt[0]; val y = pt[1]
            if (x < -m0 || x > width + m0 || y < -m0 || y > height + m0) continue
            var g: Group? = null
            for (k in groups) { val dx = k.x - x; val dy = k.y - y; if (dx * dx + dy * dy < r2) { g = k; break } }
            if (g != null) g.n++ else groups.add(Group(x, y, m))
        }
    }

    private fun drawMarkers(c: Canvas) {
        if (markers.isEmpty()) { groups.clear(); return }
        buildGroups()
        val half = pinHalf; val lift = pinLift; val border = density * 1.5f
        for (g in groups) {
            val x = g.x; val y = g.y
            // pointer down to the exact spot, then the framed thumbnail
            fill.color = 0xFFFFFFFF.toInt()
            pinPath.rewind()
            pinPath.moveTo(x - density * 4, y - lift + half); pinPath.lineTo(x + density * 4, y - lift + half); pinPath.lineTo(x, y); pinPath.close()
            c.drawPath(pinPath, fill)
            pinRect.set(x - half - border, y - lift - half - border, x + half + border, y - lift + half + border)
            c.drawRoundRect(pinRect, density * 4, density * 4, fill)
            pinRect.set(x - half, y - lift - half, x + half, y - lift + half)
            val b = g.first.bmp
            if (b != null) c.drawBitmap(b, null, pinRect, tilePaint)
            else { fill.color = PHOTO; c.drawRect(pinRect, fill) }
            if (g.n > 1) {
                // count badge on the top-right corner
                val bx = x + half; val by = y - lift - half
                val label = if (g.n > 99) "99+" else g.n.toString()
                val br = max(density * 7.5f, countPaint.measureText(label) / 2 + density * 3.5f)
                fill.color = 0xFFFFFFFF.toInt(); c.drawCircle(bx, by, br + border, fill)
                fill.color = PHOTO; c.drawCircle(bx, by, br, fill)
                c.drawText(label, bx, by + countPaint.textSize * 0.36f, countPaint)
            }
        }
    }

    private fun markerAt(x: Float, y: Float): Marker? {
        if (markers.isEmpty()) return null
        val r = density * 22
        var best: Group? = null
        var bestD = r * r
        for (g in groups) {
            val dx = x - g.x; val dy = y - (g.y - pinLift)
            val d = dx * dx + dy * dy
            if (d <= bestD) { bestD = d; best = g }
        }
        return best?.first
    }

    /** Zoom of the tiles requested / actually drawn at the screen centre (lower = scaled-up, less detail). */
    var tileZ = 0
        private set
    var drawnZ = -1
        private set
    private var centerTx = 0
    private var centerTy = 0

    /** Optional lines of small statistics text drawn top-left (evaluated each frame). */
    var stats: (() -> List<String>)? = null
        set(v) { field = v; invalidate() }

    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = density * 11.5f
        typeface = android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL)
        setShadowLayer(density * 2.2f, 0f, 0f, 0xFF000000.toInt())
    }

    private fun drawStats(c: Canvas, lines: List<String>) {
        var y = padTop + density * 16
        for (l in lines) {
            c.drawText(l, density * 10, y, statsPaint)
            y += density * 14
        }
    }

    /** Show the north needle permanently as a button; [compassActive] = heading-up mode is on. */
    var compassButton = false
        set(v) { field = v; invalidate() }
    var compassActive = false
        set(v) { field = v; invalidate() }
    var onCompassTap: (() -> Unit)? = null

    private fun needleX() = width - density * 30
    private fun needleY() = padTop + density * 30
    private fun onNeedle(x: Float, y: Float): Boolean {
        val dx = x - needleX(); val dy = y - needleY(); val r = density * 28
        return dx * dx + dy * dy <= r * r
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val northPath = Path()

    /** Small compass needle (top-right) showing where north is while the map is rotated. */
    private fun drawNorth(c: Canvas) {
        val r = density * 13
        val x = needleX()
        val y = needleY()
        fill.color = 0xAA000000.toInt()
        c.drawCircle(x, y, r + density * 5, fill)
        if (compassActive) { // heading-up on: green ring
            ringPaint.color = 0xFF66BB6A.toInt(); ringPaint.strokeWidth = density * 2.5f
            c.drawCircle(x, y, r + density * 4, ringPaint)
        }
        c.save()
        c.rotate(-bearing, x, y)
        northPath.rewind()
        northPath.moveTo(x, y - r); northPath.lineTo(x + r * 0.42f, y); northPath.lineTo(x - r * 0.42f, y); northPath.close()
        northPaint.color = 0xFFEF5350.toInt(); c.drawPath(northPath, northPaint)
        northPath.rewind()
        northPath.moveTo(x, y + r); northPath.lineTo(x + r * 0.42f, y); northPath.lineTo(x - r * 0.42f, y); northPath.close()
        northPaint.color = 0xFFEEEEEE.toInt(); c.drawPath(northPath, northPaint)
        c.restore()
    }

    private fun drawLine(c: Canvas, line: MLine, world: Double, ox: Double, oy: Double) {
        val n = line.n
        if (n < 2) return
        val xy = line.xy
        path.rewind()
        var lx = 0f
        var ly = 0f
        for (i in 0 until n) {
            val x = (xy[2 * i] * world - ox).toFloat()
            val y = (xy[2 * i + 1] * world - oy).toFloat()
            if (i == 0 || abs(x - lx) > world / 2) { path.moveTo(x, y); lx = x; ly = y } // 180° meridian: break the line
            else if (abs(x - lx) + abs(y - ly) >= 2f || i == n - 1) { path.lineTo(x, y); lx = x; ly = y }
        }
        c.drawPath(path, casePaint)
        c.drawPath(path, linePaint)
    }

    private fun dot(c: Canvas, mx: Double, my: Double, world: Double, ox: Double, oy: Double, color: Int) {
        val x = (mx * world - ox).toFloat()
        val y = (my * world - oy).toFloat()
        fill.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, density * 6, fill)
        fill.color = color
        c.drawCircle(x, y, density * 4.5f, fill)
    }

    private fun drawMe(c: Canvas) {
        if (meLat.isNaN()) return
        screenOf(Geo.mx(meLon), Geo.my(meLat), pt)
        val x = pt[0]; val y = pt[1]
        val accPx = (meAcc * world() / Geo.metersPerUnit(meLat)).toFloat()
        if (accPx > density * 8) {
            fill.color = 0x3329B6F6
            c.drawCircle(x, y, accPx, fill)
        }
        fill.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, y, density * 9, fill)
        fill.color = 0xFF1E88E5.toInt()
        c.drawCircle(x, y, density * 6.5f, fill)
    }

    private fun drawScale(c: Canvas, world: Double) {
        val mpp = Geo.metersPerUnit(Geo.lat(cy)) / world
        val target = density * 90 * mpp
        if (!(target > 0) || !target.isFinite()) return
        val p10 = 10.0.pow(floor(log10(target)))
        // firstOrNull: log10 can round up just below a power of ten (999.99999… → 3.0)
        val nice = listOf(5.0, 2.0, 1.0).map { it * p10 }.firstOrNull { it <= target } ?: (p10 / 2)
        val len = (nice / mpp).toFloat()
        val x0 = density * 12
        // sits above the attribution line so the two never overlap
        val y0 = height - padBottom - density * (if (attribution.isNotEmpty()) 26 else 14)
        c.drawLine(x0, y0, x0 + len, y0, scalePaint)
        c.drawLine(x0, y0 - density * 5, x0, y0, scalePaint)
        c.drawLine(x0 + len, y0 - density * 5, x0 + len, y0, scalePaint)
        val label = if (nice >= 1000) "${(nice / 1000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
        c.drawText(label, x0 + density * 4, y0 - density * 6, textPaint)
    }
}
