package app.trekmap.trail

import java.util.zip.Inflater
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Land-cover classes for an area, one byte per cell on a regular lon/lat grid (ESA WorldCover classes:
 * 40 cropland, 50 built-up, …; 0 = no data).
 */
class CoverGrid(
    /** Global pixel index of the grid's top-left cell at this grid's [step]. */
    val x0: Long, val y0: Long,
    val w: Int, val h: Int,
    /** Cell size in degrees. */
    val step: Double,
    val cls: ByteArray,
) {
    fun at(lon: Double, lat: Double): Int {
        val gx = floor((lon + 180.0) / step).toLong() - x0
        val gy = floor((90.0 - lat) / step).toLong() - y0
        if (gx < 0 || gy < 0 || gx >= w || gy >= h) return 0
        return cls[(gy * w + gx).toInt()].toInt() and 0xFF
    }

    fun count(c: Int): Int = cls.count { (it.toInt() and 0xFF) == c }
}

/**
 * Reads ESA WorldCover 2021 (10 m, CC BY 4.0) straight from its public Cloud-Optimised GeoTIFFs on AWS:
 * only the header and the 1024×1024 blocks covering the area are fetched with HTTP range requests,
 * so a trail corridor costs a few hundred KB. Files are 3°×3° (e.g. N39E027 = 39–42°N, 27–30°E).
 */
object WorldCover {
    const val CROPLAND = 40
    const val BUILT_UP = 50
    /** Full-resolution cell size (1/12000°, ~10 m). */
    const val STEP0 = 1.0 / 12000.0

    fun tileName(latS: Int, lonW: Int): String =
        // part of a URL: ASCII digits whatever the phone's language (Arabic/Persian/… locales format %d with their own digits)
        (if (latS >= 0) "N" else "S") + String.format(java.util.Locale.US, "%02d", kotlin.math.abs(latS)) +
            (if (lonW >= 0) "E" else "W") + String.format(java.util.Locale.US, "%03d", kotlin.math.abs(lonW))

    fun url(name: String) =
        "https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_${name}_Map.tif"

    /** Fetches [len] bytes of [url] starting at [from]; throws Net.Http(403/404) when the file doesn't exist (sea). */
    fun interface Ranger { fun get(url: String, from: Long, len: Int): ByteArray }

    class Ifd(val width: Int, val height: Int, val tileW: Int, val tileH: Int, val offsets: LongArray, val counts: LongArray, val compression: Int, val predictor: Int)

    /**
     * The grid covering [b] at the finest resolution that keeps it within [maxCells] per side
     * (WorldCover's overviews halve the resolution each level).
     */
    fun read(b: BBox, ranger: Ranger, maxCells: Int = 3000): CoverGrid {
        var level = 0
        while (level < 5 && max((b.e - b.w), (b.n - b.s)) / (STEP0 * (1 shl level)) > maxCells) level++
        val step = STEP0 * (1 shl level)
        val x0 = floor((b.w + 180.0) / step).toLong()
        val y0 = floor((90.0 - b.n) / step).toLong()
        val x1 = ceil((b.e + 180.0) / step).toLong()
        val y1 = ceil((90.0 - b.s) / step).toLong()
        val w = (x1 - x0).toInt().coerceAtLeast(1)
        val h = (y1 - y0).toInt().coerceAtLeast(1)
        val grid = ByteArray(w * h)
        val perFile = 36000L shr level // cells per 3° file at this level
        for (latS in (floor(b.s / 3).toInt() * 3)..(floor(b.n / 3).toInt() * 3) step 3) {
            for (lonW in (floor(b.w / 3).toInt() * 3)..(floor(b.e / 3).toInt() * 3) step 3) {
                // this file's top-left in global cells
                val fx0 = Math.round((lonW + 180.0) / step)
                val fy0 = Math.round((90.0 - (latS + 3)) / step)
                readFile(url(tileName(latS, lonW)), level, ranger, fx0, fy0, perFile, x0, y0, w, h, grid)
            }
        }
        return CoverGrid(x0, y0, w, h, step, grid)
    }

    private fun readFile(
        url: String, level: Int, ranger: Ranger, fx0: Long, fy0: Long, perFile: Long,
        x0: Long, y0: Long, w: Int, h: Int, grid: ByteArray,
    ) {
        // the part of the grid inside this file, in file cells
        val ax = max(x0, fx0) - fx0; val bx = min(x0 + w, fx0 + perFile) - fx0
        val ay = max(y0, fy0) - fy0; val by = min(y0 + h, fy0 + perFile) - fy0
        if (ax >= bx || ay >= by) return
        val head = try {
            ranger.get(url, 0, 65536)
        } catch (e: Net.Http) {
            if (e.code == 403 || e.code == 404) return // no file: open sea
            throw e
        }
        val tiff = Tiff(head) { off, len -> ranger.get(url, off, len) }
        val ifd = tiff.ifds().getOrNull(level) ?: throw IllegalStateException("WorldCover overview $level missing")
        if (ifd.compression != 8 && ifd.compression != 32946) throw IllegalStateException("WorldCover compression ${ifd.compression}")
        val across = (ifd.width + ifd.tileW - 1) / ifd.tileW
        val buf = ByteArray(ifd.tileW * ifd.tileH)
        for (ty in (ay / ifd.tileH).toInt()..((by - 1) / ifd.tileH).toInt()) {
            for (tx in (ax / ifd.tileW).toInt()..((bx - 1) / ifd.tileW).toInt()) {
                val idx = ty * across + tx
                if (idx >= ifd.offsets.size || ifd.counts[idx] == 0L) continue
                val raw = ranger.get(url, ifd.offsets[idx], ifd.counts[idx].toInt())
                inflate(raw, buf)
                if (ifd.predictor == 2) for (r in 0 until ifd.tileH) for (c in 1 until ifd.tileW) {
                    val i = r * ifd.tileW + c
                    buf[i] = (buf[i] + buf[i - 1]).toByte()
                }
                // copy the overlap of this block into the grid
                val bx0 = tx.toLong() * ifd.tileW; val by0 = ty.toLong() * ifd.tileH
                val cx0 = max(bx0, ax); val cx1 = min(bx0 + ifd.tileW, bx)
                val cy0 = max(by0, ay); val cy1 = min(by0 + ifd.tileH, by)
                for (fy in cy0 until cy1) {
                    val gy = (fy + fy0 - y0).toInt()
                    val gx = (cx0 + fx0 - x0).toInt()
                    System.arraycopy(buf, ((fy - by0) * ifd.tileW + (cx0 - bx0)).toInt(), grid, gy * w + gx, (cx1 - cx0).toInt())
                }
            }
        }
    }

    private fun inflate(src: ByteArray, dst: ByteArray) {
        val inf = Inflater()
        try {
            inf.setInput(src)
            var n = 0
            while (n < dst.size && !inf.finished()) {
                val k = inf.inflate(dst, n, dst.size - n)
                if (k == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n < dst.size) java.util.Arrays.fill(dst, n, dst.size, 0)
        } finally {
            inf.end()
        }
    }

    /** Minimal classic-TIFF reader: just the IFD chain and the tags a tiled single-band image needs. */
    class Tiff(private val head: ByteArray, private val more: (Long, Int) -> ByteArray) {
        private val le = head[0] == 'I'.code.toByte()

        init {
            require(head.size >= 8 && (head[0] == head[1]) && (head[0] == 'I'.code.toByte() || head[0] == 'M'.code.toByte())) { "not a TIFF" }
            require(u16(head, 2) == 42) { "BigTIFF/unknown TIFF variant" }
        }

        private fun u16(b: ByteArray, o: Int): Int =
            if (le) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

        private fun u32(b: ByteArray, o: Int): Long =
            if (le) (u16(b, o).toLong()) or (u16(b, o + 2).toLong() shl 16)
            else (u16(b, o).toLong() shl 16) or u16(b, o + 2).toLong()

        /** [len] bytes at file offset [off] (from the header if it's there). */
        private fun bytes(off: Long, len: Int): ByteArray =
            if (off + len <= head.size) head.copyOfRange(off.toInt(), off.toInt() + len) else more(off, len)

        private fun values(type: Int, count: Long, inline: ByteArray, inlineAt: Int): LongArray {
            val size = when (type) { 3 -> 2; 4 -> 4; 1, 7 -> 1; 16 -> 8; else -> return LongArray(0) }
            val total = size * count
            val (b, o) = if (total <= 4) inline to inlineAt else bytes(u32(inline, inlineAt), total.toInt()) to 0
            return LongArray(count.toInt()) { i ->
                when (size) {
                    1 -> (b[o + i].toLong() and 0xFF)
                    2 -> u16(b, o + 2 * i).toLong()
                    else -> u32(b, o + 4 * i)
                }
            }
        }

        fun ifds(): List<Ifd> {
            val out = ArrayList<Ifd>()
            var off = u32(head, 4)
            while (off != 0L && out.size < 12) {
                val n = u16(bytes(off, 2), 0)
                val e = bytes(off + 2, n * 12 + 4)
                var width = 0; var height = 0; var tw = 0; var th = 0; var comp = 1; var pred = 1
                var offs = LongArray(0); var cnts = LongArray(0)
                for (k in 0 until n) {
                    val p = k * 12
                    val tag = u16(e, p); val type = u16(e, p + 2); val count = u32(e, p + 4)
                    when (tag) {
                        256 -> width = values(type, 1, e, p + 8)[0].toInt()
                        257 -> height = values(type, 1, e, p + 8)[0].toInt()
                        259 -> comp = values(type, 1, e, p + 8)[0].toInt()
                        317 -> pred = values(type, 1, e, p + 8)[0].toInt()
                        322 -> tw = values(type, 1, e, p + 8)[0].toInt()
                        323 -> th = values(type, 1, e, p + 8)[0].toInt()
                        324 -> offs = values(type, count, e, p + 8)
                        325 -> cnts = values(type, count, e, p + 8)
                    }
                }
                if (tw > 0 && th > 0) out += Ifd(width, height, tw, th, offs, cnts, comp, pred)
                off = u32(e, n * 12)
            }
            return out
        }
    }
}
