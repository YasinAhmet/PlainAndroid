package app.plain.maps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws one map tile from the offline cells. A dark, plain style: streets, water and parks
 * underneath, the bus "web" (every İETT route, faint, so busy corridors glow brighter), rail and
 * ferry lines in their colours, then stops, places and labels.
 */
class TileRenderer(private val store: Store, private val density: Float, val size: Int) {
    companion object {
        const val NODATA = 0xFF121416.toInt()
        const val LAND = 0xFF1B1E22.toInt()
        const val WATER = 0xFF0E2232.toInt()
        const val WEB = 0xFF26C6DA.toInt()
        private val EMPTY = emptyList<Feature>()
    }

    private val cells = object : LruCache<Long, List<Feature>>(48) {}

    fun clear() = cells.evictAll()

    private fun cell(lvl: Int, x: Int, y: Int): List<Feature> {
        val k = (lvl.toLong() shl 56) or (x.toLong() shl 28) or y.toLong()
        cells.get(k)?.let { return it }
        val b = store.cell(lvl, x, y)
        val f = if (b == null) EMPTY else try { Cells.decode(b) } catch (_: Exception) { EMPTY }
        cells.put(k, f)
        return f
    }

    // --------------------------------------------------------------- paints (each render thread has its own)
    private inner class Ctx {
        val fill = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        val web = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND }
        val ferry = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = 0xAA4FC3F7.toInt(); strokeWidth = density * 1.4f
            pathEffect = DashPathEffect(floatArrayOf(density * 5, density * 4), 0f)
        }
        val rail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF4A4F56.toInt(); pathEffect = DashPathEffect(floatArrayOf(density * 3, density * 3), 0f) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB4B8BE.toInt(); textSize = density * 10.5f }
        val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xF0141619.toInt(); textSize = density * 10.5f; style = Paint.Style.STROKE; strokeWidth = density * 3f; strokeJoin = Paint.Join.ROUND
        }
        val path = Path()
        val pts = FloatArray(4096)
        val placed = ArrayList<RectF>()
        val names = HashSet<String>()
    }

    private val ctxs = ThreadLocal.withInitial { Ctx() }

    private fun roadColor(k: Int) = when (k) {
        K.MOTORWAY -> 0xFF8C6B3D.toInt()
        K.TRUNK -> 0xFF7C6841.toInt()
        K.PRIMARY -> 0xFF6E6347.toInt()
        K.SECONDARY -> 0xFF5D5B50.toInt()
        K.TERTIARY -> 0xFF50555C.toInt()
        K.MINOR -> 0xFF41464C.toInt()
        K.SERVICE -> 0xFF363A3F.toInt()
        else -> 0xFF33373C.toInt()
    }

    private fun roadWidth(k: Int, tz: Int): Float {
        val base = when (k) {
            K.MOTORWAY -> 4.4f; K.TRUNK -> 4.0f; K.PRIMARY -> 3.6f; K.SECONDARY -> 3.1f; K.TERTIARY -> 2.7f
            K.MINOR -> 2.0f; K.SERVICE -> 1.3f; else -> 0.9f
        }
        val s = 2.0.pow((tz - 15) * 0.55).toFloat()
        val min = if (k >= K.PRIMARY) 0.7f else 0.45f
        return max(base * s, min) * density
    }

    private fun areaColor(k: Int) = when (k) {
        K.LAND -> LAND
        K.WATER -> WATER
        K.PARK -> 0xFF1C2A20.toInt()
        K.FOREST -> 0xFF19271D.toInt()
        else -> 0xFF1F251F.toInt()
    }

    /** Is this kind drawn at tile zoom tz? */
    private fun visible(f: Feature, tz: Int): Boolean = when (f.kind) {
        K.PATH, K.SERVICE -> tz >= 15
        K.RAILWAY -> tz >= 12
        K.STOP -> tz >= 15
        K.STATION -> tz >= 12
        K.POI -> tz >= 17
        K.PLACE -> when (f.ref.toInt()) {
            1, 2 -> tz in 8..12
            3 -> tz in 11..14
            4 -> tz in 12..16
            else -> tz in 14..17
        }
        else -> true
    }

    // --------------------------------------------------------------- rendering
    private class Placed(val f: Feature, val ox: Double, val s: Double, val oy: Double)

    fun render(tz: Int, tx: Int, ty: Int): Bitmap {
        val c = ctxs.get()!!
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        val cv = Canvas(bmp)
        cv.drawColor(NODATA)
        val area = store.area ?: return bmp
        val n = (1 shl tz).toDouble()
        // the synced area: water around land when the coastline is known, plain land otherwise
        val ax0 = ((Geo.mx(area.w) * n - tx) * size).toFloat(); val ax1 = ((Geo.mx(area.e) * n - tx) * size).toFloat()
        val ay0 = ((Geo.my(area.n) * n - ty) * size).toFloat(); val ay1 = ((Geo.my(area.s) * n - ty) * size).toFloat()
        if (ax1 < 0 || ay1 < 0 || ax0 > size || ay0 > size) return bmp
        c.fill.color = LAND
        cv.drawRect(ax0, ay0, ax1, ay1, c.fill)
        val la = store.landArea
        if (store.hasLand && la != null) {
            // sea where the coastline is known; the land polygons cover the rest of it
            c.fill.color = WATER
            cv.drawRect(((Geo.mx(la.w) * n - tx) * size).toFloat(), ((Geo.my(la.n) * n - ty) * size).toFloat(),
                ((Geo.mx(la.e) * n - tx) * size).toFloat(), ((Geo.my(la.s) * n - ty) * size).toFloat(), c.fill)
        }
        cv.save()
        cv.clipRect(ax0, ay0, ax1, ay1)

        val lvl = Levels.forTile(tz)
        val zl = Levels.Z[lvl]
        val list = ArrayList<Placed>()
        if (tz >= zl) {
            val d = tz - zl
            val cx = tx shr d; val cy = ty shr d
            val s = size.toDouble() * (1 shl d)
            for (f in cell(lvl, cx, cy)) list.add(Placed(f, (cx * (1 shl d) - tx).toDouble() * size, s, (cy * (1 shl d) - ty).toDouble() * size))
        } else {
            val d = zl - tz
            val k = 1 shl d
            val s = size.toDouble() / k
            for (yy in 0 until k) for (xx in 0 until k) {
                val cx = (tx shl d) + xx; val cy = (ty shl d) + yy
                for (f in cell(lvl, cx, cy)) list.add(Placed(f, xx * s, s, yy * s))
            }
            list.sortBy { it.f.kind } // stable: keeps each cell's order
        }

        val m = density * 24
        val labels = ArrayList<Placed>()
        for (p in list) {
            val f = p.f
            if (!visible(f, tz)) continue
            // cull by bounding box
            val bx0 = p.ox + f.x0 * p.s; val bx1 = p.ox + f.x1 * p.s
            val by0 = p.oy + f.y0 * p.s; val by1 = p.oy + f.y1 * p.s
            if (bx1 < -m || by1 < -m || bx0 > size + m || by0 > size + m) continue
            when {
                f.rings != null -> {
                    if (f.kind == K.LAND && !store.hasLand) continue
                    c.fill.color = areaColor(f.kind)
                    buildPolygon(c.path, f, p)
                    cv.drawPath(c.path, c.fill)
                }
                f.point -> {
                    drawPoint(cv, c, f, p, tz)
                    if (f.name != null) labels.add(p)
                }
                else -> {
                    buildLine(c.path, f, p)
                    when (f.kind) {
                        K.BUS -> {
                            c.web.color = WEB
                            c.web.alpha = ((if (tz <= 10) 0x46 else if (tz <= 13) 0x50 else 0x5C) * Style.webAlpha).toInt().coerceIn(4, 255)
                            c.web.strokeWidth = Style.webWidth * density * (if (tz <= 10) 0.9f else if (tz <= 13) 1.3f else if (tz <= 15) 1.9f else 2.6f)
                            cv.drawPath(c.path, c.web)
                        }
                        K.RAIL -> {
                            c.line.color = f.color or 0xFF000000.toInt()
                            c.line.strokeWidth = Style.railWidth * density * (if (tz <= 11) 2.2f else if (tz <= 14) 3.0f else 4.0f)
                            cv.drawPath(c.path, c.line)
                        }
                        K.FERRY -> { c.ferry.strokeWidth = density * 1.4f * Style.railWidth; cv.drawPath(c.path, c.ferry) }
                        K.RAILWAY -> {
                            c.rail.strokeWidth = density * (if (tz >= 15) 1.6f else 1.0f)
                            cv.drawPath(c.path, c.rail)
                        }
                        else -> {
                            c.line.color = roadColor(f.kind)
                            c.line.strokeWidth = roadWidth(f.kind, tz)
                            cv.drawPath(c.path, c.line)
                            if (f.name != null && tz >= 16 && f.kind >= K.MINOR) labels.add(p)
                        }
                    }
                }
            }
        }
        // labels last, so nothing draws over them; the most important first
        c.placed.clear(); c.names.clear()
        labels.sortWith(compareBy({ labelRank(it.f) }, { -(it.f.x1 - it.f.x0 + it.f.y1 - it.f.y0) }))
        for (p in labels) drawLabel(cv, c, p, tz)
        cv.restore()
        return bmp
    }

    private fun labelRank(f: Feature) = when (f.kind) {
        K.PLACE -> f.ref.toInt()
        K.STATION -> 7
        K.MOTORWAY, K.TRUNK, K.PRIMARY -> 8
        K.SECONDARY, K.TERTIARY -> 9
        K.POI -> 11
        else -> 10
    }

    private fun buildPolygon(path: Path, f: Feature, p: Placed) {
        path.rewind()
        path.fillType = Path.FillType.EVEN_ODD
        val rings = f.rings!!
        val xy = f.xy
        for (r in rings.indices) {
            val a = rings[r]
            val b = if (r + 1 < rings.size) rings[r + 1] else xy.size
            var i = a
            var first = true
            while (i < b) {
                val x = (p.ox + xy[i] * p.s).toFloat(); val y = (p.oy + xy[i + 1] * p.s).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
                i += 2
            }
            path.close()
        }
    }

    private fun buildLine(path: Path, f: Feature, p: Placed) {
        path.rewind()
        path.fillType = Path.FillType.WINDING
        val xy = f.xy
        var lx = 0f; var ly = 0f
        val n = xy.size / 2
        for (i in 0 until n) {
            val x = (p.ox + xy[2 * i] * p.s).toFloat(); val y = (p.oy + xy[2 * i + 1] * p.s).toFloat()
            if (i == 0) { path.moveTo(x, y); lx = x; ly = y }
            else if (abs(x - lx) + abs(y - ly) >= 0.6f || i == n - 1) { path.lineTo(x, y); lx = x; ly = y }
        }
    }

    private fun drawPoint(cv: Canvas, c: Ctx, f: Feature, p: Placed, tz: Int) {
        val x = (p.ox + f.xy[0] * p.s).toFloat(); val y = (p.oy + f.xy[1] * p.s).toFloat()
        when (f.kind) {
            K.STOP -> {
                val r = density * (if (tz >= 17) 3.4f else if (tz >= 16) 2.8f else 2.2f)
                c.fill.color = WEB; cv.drawCircle(x, y, r + density * 1.1f, c.fill)
                c.fill.color = 0xFFE8FBFD.toInt(); cv.drawCircle(x, y, r, c.fill)
            }
            K.STATION -> {
                val r = density * (if (tz >= 15) 4.6f else 3.4f)
                c.fill.color = f.color or 0xFF000000.toInt(); cv.drawCircle(x, y, r + density * 1.6f, c.fill)
                c.fill.color = 0xFFFFFFFF.toInt(); cv.drawCircle(x, y, r, c.fill)
            }
            K.POI -> {
                val r = density * (if (tz >= 18) 3.0f else 2.3f)
                c.fill.color = 0xFF101214.toInt(); cv.drawCircle(x, y, r + density, c.fill)
                c.fill.color = Poi.color(f.ref.toInt()); cv.drawCircle(x, y, r, c.fill)
            }
        }
    }

    private fun fits(c: Ctx, r: RectF): Boolean {
        val m = density * 2
        if (r.left < m || r.top < m || r.right > size - m || r.bottom > size - m) return false
        for (o in c.placed) if (RectF.intersects(o, r)) return false
        return true
    }

    private fun drawLabel(cv: Canvas, c: Ctx, p: Placed, tz: Int) {
        val f = p.f
        val name = f.name ?: return
        if (f.point) {
            val x = (p.ox + f.xy[0] * p.s).toFloat(); val y = (p.oy + f.xy[1] * p.s).toFloat()
            when (f.kind) {
                K.PLACE -> {
                    val rank = f.ref.toInt()
                    val sz = when (rank) { 1 -> 16f; 2 -> 14f; 3 -> 13f; 4 -> 12f; else -> 11f }
                    c.text.textSize = density * sz; c.halo.textSize = density * sz
                    c.text.color = if (rank <= 3) 0xFFE8E8E8.toInt() else 0xFFC4C7CC.toInt()
                    c.text.typeface = if (rank <= 3) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    c.halo.typeface = c.text.typeface
                    text(cv, c, name, x, y + c.text.textSize * 0.35f, center = true)
                }
                K.STATION -> if (tz >= 14) {
                    c.text.textSize = density * 11f; c.halo.textSize = density * 11f
                    c.text.color = 0xFFFFFFFF.toInt(); c.text.typeface = Typeface.DEFAULT_BOLD; c.halo.typeface = Typeface.DEFAULT_BOLD
                    text(cv, c, name, x + density * 7, y + density * 4, center = false)
                }
                K.POI -> if (tz >= 18) {
                    c.text.textSize = density * 10f; c.halo.textSize = density * 10f
                    c.text.color = Poi.color(f.ref.toInt()); c.text.typeface = Typeface.DEFAULT; c.halo.typeface = Typeface.DEFAULT
                    text(cv, c, name, x, y + density * 14, center = true)
                }
            }
            return
        }
        // street name along the road
        if (!c.names.add(name)) return
        c.text.textSize = density * 10.5f; c.halo.textSize = density * 10.5f
        c.text.color = 0xFFAEB2B8.toInt(); c.text.typeface = Typeface.DEFAULT; c.halo.typeface = Typeface.DEFAULT
        val tw = c.text.measureText(name)
        val xy = f.xy
        val n = min(xy.size / 2, c.pts.size / 2)
        for (i in 0 until n) { c.pts[2 * i] = (p.ox + xy[2 * i] * p.s).toFloat(); c.pts[2 * i + 1] = (p.oy + xy[2 * i + 1] * p.s).toFloat() }
        // the longest fairly straight run inside the tile
        val m = density * 4
        var bestA = -1; var bestB = -1; var bestLen = 0f
        var a = 0
        while (a < n - 1) {
            var len = 0f
            var b = a
            val h0 = atan2(c.pts[2 * a + 3] - c.pts[2 * a + 1], c.pts[2 * a + 2] - c.pts[2 * a])
            while (b < n - 1) {
                val x0 = c.pts[2 * b]; val y0 = c.pts[2 * b + 1]; val x1 = c.pts[2 * b + 2]; val y1 = c.pts[2 * b + 3]
                if (x0 < m || y0 < m || x0 > size - m || y0 > size - m || x1 < m || y1 < m || x1 > size - m || y1 > size - m) break
                val h = atan2(y1 - y0, x1 - x0)
                var dh = abs(h - h0); if (dh > Math.PI) dh = (2 * Math.PI - dh).toFloat()
                if (dh > 0.45f) break
                len += hypot(x1 - x0, y1 - y0)
                b++
            }
            if (len > bestLen) { bestLen = len; bestA = a; bestB = b }
            a = max(b, a + 1)
        }
        if (bestA < 0 || bestLen < tw + density * 16) return
        val path = c.path
        path.rewind()
        val rev = c.pts[2 * bestB] < c.pts[2 * bestA]
        var bx0 = Float.MAX_VALUE; var by0 = Float.MAX_VALUE; var bx1 = -Float.MAX_VALUE; var by1 = -Float.MAX_VALUE
        for (k in 0..(bestB - bestA)) {
            val i = if (rev) bestB - k else bestA + k
            val x = c.pts[2 * i]; val y = c.pts[2 * i + 1]
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            bx0 = min(bx0, x); by0 = min(by0, y); bx1 = max(bx1, x); by1 = max(by1, y)
        }
        val box = RectF(bx0 - density * 5, by0 - density * 5, bx1 + density * 5, by1 + density * 5)
        for (o in c.placed) if (RectF.intersects(o, box)) return
        c.placed.add(box)
        val off = (bestLen - tw) / 2
        cv.drawTextOnPath(name, path, off, density * 3.5f, c.halo)
        cv.drawTextOnPath(name, path, off, density * 3.5f, c.text)
    }

    private fun text(cv: Canvas, c: Ctx, s: String, x: Float, y: Float, center: Boolean) {
        val w = c.text.measureText(s)
        val left = if (center) x - w / 2 else x
        val r = RectF(left - density * 2, y - c.text.textSize, left + w + density * 2, y + density * 3)
        if (!fits(c, r)) return
        c.placed.add(r)
        cv.drawText(s, left, y, c.halo)
        cv.drawText(s, left, y, c.text)
    }
}

/**
 * Renders tiles on background threads into a bounded memory cache. Only tiles wanted by the
 * latest frame are rendered, so panning never builds a backlog.
 */
class Tiles(threads: Int, cacheBytes: Int, private val render: (Int, Int, Int) -> Bitmap?) {
    companion object {
        fun key(z: Int, x: Int, y: Int): Long = (z.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()
    }

    @Volatile var onLoaded: (() -> Unit)? = null
    private val cache = object : LruCache<Long, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val lock = Object()
    private val wanted = LinkedHashSet<Long>()
    private val inflight = HashSet<Long>()
    @Volatile private var stopped = false
    @Volatile private var gen = 0

    init {
        repeat(threads) { kotlin.concurrent.thread(isDaemon = true, name = "tiles-$it", priority = Thread.NORM_PRIORITY - 1) { work() } }
    }

    fun get(k: Long): Bitmap? = cache.get(k)
    fun beginFrame() = synchronized(lock) { wanted.clear() }
    fun want(k: Long) = synchronized(lock) { if (k !in inflight) wanted.add(k) }
    fun endFrame() = synchronized(lock) { if (wanted.isNotEmpty()) lock.notifyAll() }

    /** Drops every tile (new data). */
    fun clear() {
        gen++
        cache.evictAll()
    }

    fun shutdown() {
        stopped = true
        onLoaded = null
        synchronized(lock) { wanted.clear(); lock.notifyAll() }
        cache.evictAll()
    }

    private fun work() {
        while (!stopped) {
            val k: Long = synchronized(lock) {
                while (!stopped && wanted.isEmpty()) lock.wait()
                if (stopped) return
                val it = wanted.iterator()
                val k = it.next()
                it.remove()
                inflight.add(k)
                k
            }
            val g = gen
            val z = ((k ushr 52) and 63L).toInt()
            val x = ((k ushr 26) and 0x3FFFFFFL).toInt()
            val y = (k and 0x3FFFFFFL).toInt()
            val bmp = try { render(z, x, y) } catch (_: Throwable) { null }
            synchronized(lock) { inflight.remove(k) }
            if (stopped) return
            if (bmp != null && g == gen) cache.put(k, bmp)
            onLoaded?.invoke()
        }
    }
}
