package app.trekmap.trail

import app.plain.lang.tr
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen viewer for a trail's photos.
 *  - Swipe left/right through all photos of the trail; tap or Back closes it.
 *  - Pinch or double-tap to zoom, drag to look around; zoomed in, a sharper version is decoded.
 *  - Each photo appears at once from its small thumbnail, then sharpens when the full image is decoded;
 *    the neighbours are decoded ahead, so swiping through many photos doesn't wait.
 *  - Top-right: a mini map of the route with this photo's spot (tap to fold / unfold).
 *  - "Remove" takes the photo off the trail (the Gallery copy stays).
 */
class PhotoViewer(
    private val act: MainActivity,
    start: Photo,
    private val dir: File,
    /** The trail's line(s) for the mini map (normalised Mercator), or null. */
    private val route: List<MLine>?,
    /** Called after a photo was removed from the trail. */
    private val onRemoved: ((Photo) -> Unit)?,
) {
    private val all = ArrayList(Photos.list(dir).ifEmpty { listOf(start) })
    private var i = all.indexOfFirst { it.file == start.file }.coerceAtLeast(0)
    private val d = Dialog(act, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    private val iv = ZoomImage(act)
    private val caption = act.text("", 13f).apply { maxLines = 2; setPadding(0, 0, act.dp(8), 0) }
    private val gallery = act.button(tr("Gallery", "Galeri")) { openGallery() }
    private val mini = MiniMap(act)
    private val miniChip = act.text("🗺", 18f).apply {
        gravity = Gravity.CENTER
        background = roundRect(0xCC000000.toInt(), act.dpf(10))
    }
    private val maxPx = act.resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }

    // full images: 3 kept (current + neighbours), RGB_565 halves their memory
    private val full = LruCache<String, Bitmap>(3)
    private val decoder = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val root = FrameLayout(act).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(14), act.dp(10), act.dp(10), act.dp(18))
            setBackgroundColor(0x99000000.toInt())
        }
        bar.addView(caption, lpL(0, WRAP, 1f))
        bar.addView(gallery, lpL(WRAP, act.dp(36)).apply { marginEnd = act.dp(6) })
        if (onRemoved != null) bar.addView(act.button(tr("Remove", "Kaldır"), 0xFF5D1F1F.toInt()) { askRemove() }, lpL(WRAP, act.dp(36)))
        root.addView(bar, lpF(MATCH, WRAP, Gravity.BOTTOM))

        // mini map (top-right), foldable; remembers the choice
        val hasMap = route?.any { it.n > 1 } == true || all.count { it.hasPosition } > 1
        if (hasMap) {
            mini.lines = route.orEmpty()
            mini.photos = all
            mini.setOnClickListener { setMini(false) }
            miniChip.setOnClickListener { setMini(true) }
            val top = act.dp(14)
            root.addView(mini, lpF(act.dp(132), act.dp(132), Gravity.TOP or Gravity.END).apply { topMargin = top; marginEnd = act.dp(12) })
            root.addView(miniChip, lpF(act.dp(40), act.dp(40), Gravity.TOP or Gravity.END).apply { topMargin = top; marginEnd = act.dp(12) })
            setMini(act.prefs.getBoolean("photoMiniMap", true), save = false)
        }

        iv.onTap = { d.dismiss() }
        iv.onSwipe = { dir ->
            val k = i + dir
            if (k in all.indices) showAt(k) else act.toast(if (k < 0) tr("First photo", "İlk fotoğraf") else tr("Last photo", "Son fotoğraf"))
        }
        iv.onZoomed = { z -> if (z > 1.6f) sharpen() }
        d.setOnDismissListener {
            closed = true
            decoder.shutdownNow()
            iv.setBitmap(null)
            full.evictAll()
            sharp = null
        }
        d.setContentView(root)
        d.show()
        // a true full-screen window: no dialog margins, nothing of the map showing around the photo
        d.window?.setLayout(MATCH, MATCH)
        d.window?.setBackgroundDrawable(ColorDrawable(0xFF000000.toInt()))
        d.window?.decorView?.setPadding(0, 0, 0, 0)
        showAt(i)
        if (all.size > 1 && !act.prefs.getBoolean("photoSwipeTip", false)) {
            act.prefs.edit().putBoolean("photoSwipeTip", true).apply()
            act.toast(tr("Swipe left or right for the other photos of this trail.", "Bu rotanın diğer fotoğrafları için sola ya da sağa kaydırın."))
        }
    }

    private fun setMini(open: Boolean, save: Boolean = true) {
        mini.visibility = if (open) View.VISIBLE else View.GONE
        miniChip.visibility = if (open) View.GONE else View.VISIBLE
        if (save) act.prefs.edit().putBoolean("photoMiniMap", open).apply()
    }

    private fun showAt(k: Int) {
        i = k
        val ph = all[k]
        caption.text = (if (all.size > 1) "${k + 1} / ${all.size} · " else "") + Fmt.date(ph.time) +
            if (!ph.ele.isNaN()) " · ${ph.ele.toInt()} m" else ""
        gallery.visibility = if (Photos.galleryUri(ph) != null) View.VISIBLE else View.GONE
        mini.current = ph
        // instant: the full image if it's ready, else the thumbnail (blurry for a moment)
        val ready = full.get(ph.file)
        sharp = null
        sharpFor = null
        iv.setBitmap(ready ?: Photos.cachedThumb(dir, ph), reset = true)
        if (ready == null) decode(ph, show = true)
        // then the neighbours, in the direction you're likely to swipe first
        for (n in listOf(k + 1, k - 1, k + 2)) all.getOrNull(n)?.let { if (full.get(it.file) == null) decode(it, show = false) }
    }

    private fun decode(ph: Photo, show: Boolean) {
        if (closed) return
        try {
            decoder.execute {
                if (closed) return@execute
                // skip work for photos swiped far past
                val idx = all.indexOfFirst { it.file == ph.file }
                if (idx < 0 || abs(idx - i) > 2 || full.get(ph.file) != null) return@execute
                val b = try { Photos.decode(File(dir, ph.file), maxPx, Bitmap.Config.RGB_565) } catch (e: Throwable) { null } // OutOfMemoryError too: this process records the trail
                act.runOnUiThread {
                    if (closed) return@runOnUiThread
                    if (b == null) { if (show && all.getOrNull(i)?.file == ph.file) act.toast(tr("Could not open the photo.", "Fotoğraf açılamadı.")); return@runOnUiThread }
                    full.put(ph.file, b)
                    if (all.getOrNull(i)?.file == ph.file && sharp == null) iv.setBitmap(b)
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    // --- zoomed in: decode the current photo at up to 3x screen size (once), swap it in place
    private var sharp: Bitmap? = null
    private var sharpFor: String? = null

    private fun sharpen() {
        val ph = all.getOrNull(i) ?: return
        if (sharpFor == ph.file || closed) return
        sharpFor = ph.file
        try {
            decoder.execute {
                if (closed || all.getOrNull(i)?.file != ph.file) return@execute
                val b = try { Photos.decode(File(dir, ph.file), min(4096, maxPx * 3), Bitmap.Config.RGB_565) } catch (e: Throwable) { null }
                act.runOnUiThread {
                    if (b == null || closed || all.getOrNull(i)?.file != ph.file) return@runOnUiThread
                    sharp = b
                    iv.setBitmap(b) // same photo, same view: only the detail changes
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private fun openGallery() {
        val u = Photos.galleryUri(all.getOrNull(i) ?: return) ?: return
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { act.toast(tr("No gallery app found.", "Galeri uygulaması bulunamadı.")) }
    }

    private fun askRemove() {
        val ph = all.getOrNull(i) ?: return
        AlertDialog.Builder(act)
            .setTitle(tr("Remove this photo from the trail?", "Bu fotoğraf rotadan kaldırılsın mı?"))
            .setMessage(tr("It disappears from the map and the trail's exports. The copy in your phone's Gallery stays.", "Haritadan ve rotanın dışa aktarımlarından kalkar. Telefonun Galeri'sindeki kopya kalır."))
            .setPositiveButton(tr("Remove", "Kaldır")) { _, _ ->
                try { Photos.remove(dir, ph) } catch (e: Exception) { // storage full / folder gone: say so, change nothing
                    act.toast(tr("Could not remove the photo: ${e.message}", "Fotoğraf kaldırılamadı: ${e.message}"))
                    return@setPositiveButton
                }
                Dev.event("photo", "removed ${ph.file} from ${dir.name}")
                full.remove(ph.file)
                all.removeAt(i)
                mini.photos = all
                onRemoved?.invoke(ph)
                if (all.isEmpty()) { d.dismiss(); act.toast(tr("Photo removed.", "Fotoğraf kaldırıldı.")); return@setPositiveButton }
                showAt(i.coerceAtMost(all.size - 1))
                act.toast(tr("Photo removed.", "Fotoğraf kaldırıldı."))
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    /**
     * Shows one bitmap fitted to the screen; pinch / double-tap zoom (up to 8x), drag to pan while
     * zoomed. At normal size a sideways fling means "next / previous photo" and a tap means close.
     */
    private class ZoomImage(ctx: Context) : View(ctx) {
        var onTap: (() -> Unit)? = null
        var onSwipe: ((Int) -> Unit)? = null
        var onZoomed: ((Float) -> Unit)? = null

        private var bmp: Bitmap? = null
        private var zoom = 1f           // relative to "fit to screen"
        private var left = 0f           // image's top-left on screen
        private var top = 0f
        private var scaled = false      // this gesture pinched: no fling / tap
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val m = android.graphics.Matrix()

        /** [reset] = new photo (back to fit); otherwise the same view is kept if the aspect ratio matches. */
        fun setBitmap(b: Bitmap?, reset: Boolean = false) {
            val old = bmp
            bmp = b
            val sameShape = old != null && b != null &&
                abs(old.width.toFloat() / old.height - b.width.toFloat() / b.height) < 0.01f
            if (reset || !sameShape) { zoom = 1f; centre() }
            invalidate()
        }

        private fun fit(): Float {
            val b = bmp ?: return 1f
            return min(width.toFloat() / b.width, height.toFloat() / b.height)
        }

        private fun dw() = (bmp?.width ?: 0) * fit() * zoom
        private fun dh() = (bmp?.height ?: 0) * fit() * zoom

        private fun centre() { left = (width - dw()) / 2; top = (height - dh()) / 2 }

        /** Keeps the image covering the screen when it's bigger, centred when it's smaller. */
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
            val s = fit() * zoom
            m.setScale(s, s)
            m.postTranslate(left, top)
            c.drawBitmap(b, m, paint)
        }

        private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                scaled = true
                zoomTo(zoom * d.scaleFactor, d.focusX, d.focusY)
                return true
            }
        }).apply { isQuickScaleEnabled = false } // double-tap is our own zoom toggle

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

    /** The whole route with every photo spot; the shown photo is the big ringed dot. */
    private class MiniMap(ctx: Context) : View(ctx) {
        var lines: List<MLine> = emptyList()
            set(v) { field = v; fitDirty = true; invalidate() }
        var photos: List<Photo> = emptyList()
            set(v) { field = v; fitDirty = true; invalidate() }
        var current: Photo? = null
            set(v) { field = v; invalidate() }

        private val dens = resources.displayMetrics.density
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC000000.toInt() }
        private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; color = C.PREVIEW; strokeWidth = dens * 2.2f
            strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
        }
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private var fitDirty = true
        private var x0 = 0.0; private var y0 = 0.0; private var k = 1.0; private var offX = 0f; private var offY = 0f

        private fun fit() {
            fitDirty = false
            var w = Double.MAX_VALUE; var n = Double.MAX_VALUE; var e = -Double.MAX_VALUE; var s = -Double.MAX_VALUE
            for (l in lines) for (j in 0 until l.n) {
                w = min(w, l.xy[2 * j]); e = max(e, l.xy[2 * j]); n = min(n, l.xy[2 * j + 1]); s = max(s, l.xy[2 * j + 1])
            }
            for (p in photos) if (p.hasPosition) {
                val x = Geo.mx(p.lon); val y = Geo.my(p.lat)
                w = min(w, x); e = max(e, x); n = min(n, y); s = max(s, y)
            }
            if (w > e) return
            val pad = dens * 12
            val span = max(max(e - w, s - n), 1e-9)
            k = (min(width, height) - 2 * pad) / span
            x0 = w; y0 = n
            offX = ((width - (e - w) * k) / 2).toFloat()
            offY = ((height - (s - n) * k) / 2).toFloat()
        }

        private fun sx(mx: Double) = ((mx - x0) * k).toFloat() + offX
        private fun sy(my: Double) = ((my - y0) * k).toFloat() + offY

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fitDirty = true }

        override fun onDraw(c: Canvas) {
            if (fitDirty) fit()
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), dens * 12, dens * 12, bg)
            for (l in lines) {
                if (l.n < 2) continue
                path.rewind()
                for (j in 0 until l.n) {
                    val x = sx(l.xy[2 * j]); val y = sy(l.xy[2 * j + 1])
                    if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                c.drawPath(path, line)
            }
            dot.color = 0xFFB39DDB.toInt()
            for (p in photos) if (p.hasPosition) c.drawCircle(sx(Geo.mx(p.lon)), sy(Geo.my(p.lat)), dens * 1.6f, dot)
            val cur = current
            if (cur != null && cur.hasPosition) {
                val x = sx(Geo.mx(cur.lon)); val y = sy(Geo.my(cur.lat))
                dot.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, dens * 6.5f, dot)
                dot.color = 0xFF7E57C2.toInt(); c.drawCircle(x, y, dens * 4.5f, dot)
            }
        }
    }
}
