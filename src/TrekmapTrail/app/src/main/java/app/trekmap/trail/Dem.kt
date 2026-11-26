package app.trekmap.trail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.floor

/**
 * Elevation from the downloaded Terrarium terrain tiles (bilinear). Most Tecno phones have no
 * barometer and GPS altitude is noisy, so this gives far steadier elevation and ascent numbers.
 */
object Dem {
    private val cache = object : LinkedHashMap<Long, FloatArray?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FloatArray?>?) = size > 4
    }

    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun elevation(lat: Double, lon: Double): Double? {
        val n = 1 shl DEM_Z
        val fx = Geo.mx(lon) * n
        val fy = Geo.my(lat) * n
        val tx = floor(fx).toInt()
        val ty = floor(fy).toInt()
        val key = (tx.toLong() shl 32) or ty.toLong()
        val data = if (cache.containsKey(key)) cache[key] else load(tx, ty).also { cache[key] = it }
        data ?: return null
        val px = ((fx - tx) * 256 - 0.5).coerceIn(0.0, 255.0)
        val py = ((fy - ty) * 256 - 0.5).coerceIn(0.0, 255.0)
        val x0 = floor(px).toInt().coerceAtMost(254)
        val y0 = floor(py).toInt().coerceAtMost(254)
        val ax = px - x0
        val ay = py - y0
        val i = y0 * 256 + x0
        return data[i] * (1 - ax) * (1 - ay) + data[i + 1] * ax * (1 - ay) +
            data[i + 256] * (1 - ax) * ay + data[i + 257] * ax * ay
    }

    private fun load(x: Int, y: Int): FloatArray? {
        val bytes = Packs.read(Layer.DEM, DEM_Z, x, y) ?: return null
        if (bytes.isEmpty()) return null
        val o = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            inScaled = false
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
        if (bmp.width != 256 || bmp.height != 256) { bmp.recycle(); return null }
        val px = IntArray(256 * 256)
        bmp.getPixels(px, 0, 256, 0, 0, 256, 256)
        bmp.recycle()
        return FloatArray(px.size) {
            val c = px[it]
            ((c shr 16) and 0xFF) * 256f + ((c shr 8) and 0xFF) + (c and 0xFF) / 256f - 32768f
        }
    }
}
