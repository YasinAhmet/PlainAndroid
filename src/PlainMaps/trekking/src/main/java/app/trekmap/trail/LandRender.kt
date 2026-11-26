package app.trekmap.trail

import app.plain.lang.tr
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Renders the private-land layer into transparent 256 px tiles (stored in the map pack like any tile).
 * Colours (see [legend]): amber = fields & orchards, red = homes/yards/private, hatched dark red =
 * military; red lines = fences & walls, dashed = private roads.
 */
object LandRender {
    const val SIZE = 256
    /** Metres of "yard" kept around every building: you'd be in someone's garden. */
    private const val YARD_M = 12.0

    private const val FIELD = 0x55FFB300
    private const val HOME = 0x77F4511E
    private const val MILITARY = 0x99B71C1C.toInt()
    private const val FENCE = 0xE6C62828.toInt()

    fun legend() = listOf(
        FIELD or (0xFF shl 24) to tr("Fields, orchards, vineyards — usually someone's land", "Tarla, bahçe, bağ — genelde birinin arazisi"),
        HOME or (0xFF shl 24) to tr("Houses, yards, farmyards, private areas — keep out", "Ev, avlu, çiftlik, özel alan — girmeyin"),
        MILITARY or (0xFF shl 24) to tr("Military zone (hatched) — entry forbidden", "Askeri bölge (taralı) — girmek yasak"),
        FENCE or (0xFF shl 24) to tr("Fences & walls · dashed: private road", "Çit ve duvar · kesikli: özel yol"),
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) // later (stronger) classes replace, never stack
    }
    private val yard = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    private val hatch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC7F0000.toInt(); strokeWidth = 1.6f }
    private val fence = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = FENCE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val way = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = FENCE; strokeCap = Paint.Cap.BUTT
    }
    private val path = Path().apply { fillType = Path.FillType.EVEN_ODD }

    /**
     * Tile z/x/y as PNG bytes, or EMPTY if nothing private is in it. Not thread-safe (shared paints):
     * the download worker renders tiles one after another.
     */
    @Synchronized
    fun render(z: Int, x: Int, y: Int, osm: LandData?, cover: CoverGrid?): ByteArray {
        val scale = SIZE.toDouble() * (1 shl z) // normalised Mercator → tile pixels
        val ox = x * SIZE.toDouble(); val oy = y * SIZE.toDouble()
        // tile bounds in normalised Mercator, with a margin for line widths
        val pad = 8.0 / scale
        val tw = x / (1 shl z).toDouble() - pad; val te = (x + 1) / (1 shl z).toDouble() + pad
        val tn = y / (1 shl z).toDouble() - pad; val ts = (y + 1) / (1 shl z).toDouble() + pad
        var any = false
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)

        // 1. satellite land cover: cropland and built-up cells
        if (cover != null) {
            val px = IntArray(SIZE * SIZE)
            val lons = DoubleArray(SIZE) { i -> Geo.lon((ox + i + 0.5) / scale) }
            for (r in 0 until SIZE) {
                val lat = Geo.lat((oy + r + 0.5) / scale)
                for (c in 0 until SIZE) {
                    when (cover.at(lons[c], lat)) {
                        WorldCover.CROPLAND -> { px[r * SIZE + c] = FIELD; any = true }
                        WorldCover.BUILT_UP -> { px[r * SIZE + c] = HOME; any = true }
                    }
                }
            }
            if (any) bmp.setPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        }

        val cv = Canvas(bmp)
        if (osm != null) {
            val mpp = Geo.metersPerUnit(Geo.lat((oy + SIZE / 2) / scale)) / scale
            // 2. areas (sorted: fields, homes, military)
            for (p in osm.polys) {
                if (p.e < tw || p.w > te || p.s < tn || p.n > ts) continue
                path.rewind()
                for (ring in p.rings) {
                    for (i in ring.indices step 2) {
                        val sx = (ring[i] * scale - ox).toFloat(); val sy = (ring[i + 1] * scale - oy).toFloat()
                        if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                    }
                    path.close()
                }
                val color = when (p.kind) { LandKind.FIELD -> FIELD; LandKind.HOME -> HOME; LandKind.MILITARY -> MILITARY }
                fill.color = color
                cv.drawPath(path, fill)
                if (p.building) {
                    yard.color = color
                    yard.strokeWidth = (2 * YARD_M / mpp).toFloat()
                    if (yard.strokeWidth >= 0.5f) cv.drawPath(path, yard)
                }
                if (p.kind == LandKind.MILITARY) {
                    cv.save()
                    cv.clipPath(path)
                    var d = -SIZE.toFloat()
                    while (d < SIZE) { cv.drawLine(d, SIZE.toFloat(), d + SIZE, 0f, hatch); d += 9f }
                    cv.restore()
                }
                any = true
            }
            // 3. fences, walls and private roads
            val lw = max(1.5f, (1.6 / mpp).toFloat()).coerceAtMost(4f)
            fence.strokeWidth = lw
            way.strokeWidth = lw
            way.pathEffect = DashPathEffect(floatArrayOf(lw * 3, lw * 2.5f), 0f)
            for (l in osm.lines) {
                if (l.e < tw || l.w > te || l.s < tn || l.n > ts) continue
                path.rewind()
                for (i in l.xy.indices step 2) {
                    val sx = (l.xy[i] * scale - ox).toFloat(); val sy = (l.xy[i + 1] * scale - oy).toFloat()
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                cv.drawPath(path, if (l.kind == LineKind.FENCE) fence else way)
                any = true
            }
        }
        if (!any) { bmp.recycle(); return TileProvider.EMPTY }
        val out = ByteArrayOutputStream(8192)
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }
}
