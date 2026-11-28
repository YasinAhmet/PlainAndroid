package app.plain.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.media.ExifInterface
import android.view.View
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.min

/** Drawn over the preview (same size and place): grid, level line and face brackets. */
class GuideOverlay(ctx: Context) : View(ctx) {
    var grid = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    /** Phone roll in degrees, or null to hide the level line. */
    var roll: Float? = null
        set(v) { field = v; invalidate() }
    var faces: List<FaceBox> = emptyList()
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88E4E4E4.toInt(); strokeWidth = d; style = Paint.Style.STROKE }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000; strokeWidth = 3 * d; style = Paint.Style.STROKE }
    private val lvl = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * d; strokeCap = Paint.Cap.ROUND }
    private val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; strokeWidth = 2 * d; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    init { isClickable = false; isFocusable = false }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        when (grid) {
            1 -> lines(c, w, h, 1 / 3f)
            2 -> lines(c, w, h, 0.382f)
            3 -> { both(c, w / 2, 0f, w / 2, h); both(c, 0f, h / 2, w, h / 2) }
            4 -> { both(c, 0f, 0f, w, h); both(c, w, 0f, 0f, h) }
        }
        roll?.let { r ->
            val off = Geo.offLevel(r)
            val level = abs(off) < 1f
            lvl.color = if (level) ACCENT else TEXT2
            val len = min(w, h) * (if (level) 0.42f else 0.3f)
            c.save()
            c.rotate(-r, w / 2, h / 2)
            c.drawLine(w / 2 - len, h / 2, w / 2 - 8 * d, h / 2, lvl)
            c.drawLine(w / 2 + 8 * d, h / 2, w / 2 + len, h / 2, lvl)
            c.restore()
            // fixed reference marks so the tilt is visible
            lvl.color = LABEL
            c.drawLine(w / 2 - len - 14 * d, h / 2, w / 2 - len - 4 * d, h / 2, lvl)
            c.drawLine(w / 2 + len + 4 * d, h / 2, w / 2 + len + 14 * d, h / 2, lvl)
        }
        for (f in faces) bracket(c, f.l * w, f.t * h, f.r * w, f.b * h)
    }

    private fun lines(c: Canvas, w: Float, h: Float, k: Float) {
        both(c, w * k, 0f, w * k, h); both(c, w * (1 - k), 0f, w * (1 - k), h)
        both(c, 0f, h * k, w, h * k); both(c, 0f, h * (1 - k), w, h * (1 - k))
    }

    /** A thin light line with a soft dark edge, readable over bright and dark scenes. */
    private fun both(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float) {
        c.drawLine(x0, y0, x1, y1, shade)
        c.drawLine(x0, y0, x1, y1, line)
    }

    private fun bracket(c: Canvas, l: Float, t: Float, r: Float, b: Float) {
        val k = min(r - l, b - t) * 0.25f
        c.drawLine(l, t, l + k, t, face); c.drawLine(l, t, l, t + k, face)
        c.drawLine(r, t, r - k, t, face); c.drawLine(r, t, r, t + k, face)
        c.drawLine(l, b, l + k, b, face); c.drawLine(l, b, l, b - k, face)
        c.drawLine(r, b, r - k, b, face); c.drawLine(r, b, r, b - k, face)
    }
}

/** Applies a [Look] to a finished JPEG, keeping its EXIF. Runs on the saver thread. */
object LookJpeg {
    private val TAGS = arrayOf(
        ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
        ExifInterface.TAG_OFFSET_TIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE, ExifInterface.TAG_APERTURE_VALUE, ExifInterface.TAG_SHUTTER_SPEED_VALUE,
        ExifInterface.TAG_BRIGHTNESS_VALUE, ExifInterface.TAG_SCENE_CAPTURE_TYPE, ExifInterface.TAG_DIGITAL_ZOOM_RATIO,
    )

    /** The bitmap ([bytes]) plus its JPEG copy fit comfortably in what the heap has left. */
    private fun fits(bytes: Long): Boolean {
        val rt = Runtime.getRuntime()
        val left = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        return bytes * 3 / 2 < left
    }

    fun apply(ctx: Context, jpeg: ByteArray, look: Look, quality: Int): ByteArray {
        if (look.neutral) return jpeg
        // a full-size ARGB copy of a big sensor's photo can need hundreds of MB; an OutOfMemoryError there may hit
        // another thread instead of this catch, so a photo too big for the heap is kept untouched up front
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0 || !fits(o.outWidth.toLong() * o.outHeight * 4)) return jpeg
        val src = File(ctx.cacheDir, "look-src-${System.nanoTime()}.jpg")
        val dst = File(ctx.cacheDir, "look-dst-${System.nanoTime()}.jpg")
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply {
                inMutable = true; inPreferredConfig = Bitmap.Config.ARGB_8888
            }) ?: return jpeg
            val m = look.matrix()
            val w = bmp.width
            val rows = 32
            val buf = IntArray(w * rows)
            var y = 0
            while (y < bmp.height) {
                val n = min(rows, bmp.height - y)
                bmp.getPixels(buf, 0, w, 0, y, w, n)
                for (i in 0 until w * n) buf[i] = look.pixel(buf[i], m)
                bmp.setPixels(buf, 0, w, 0, y, w, n)
                y += n
            }
            val out = ByteArrayOutputStream(jpeg.size)
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            bmp.recycle()
            src.writeBytes(jpeg)
            dst.writeBytes(out.toByteArray())
            val a = ExifInterface(src.path)
            val b = ExifInterface(dst.path)
            for (t in TAGS) a.getAttribute(t)?.let { b.setAttribute(t, it) }
            b.saveAttributes()
            dst.readBytes()
        } catch (_: Throwable) {
            jpeg // out of memory or a bad file: keep the untouched photo
        } finally {
            src.delete(); dst.delete()
        }
    }
}
