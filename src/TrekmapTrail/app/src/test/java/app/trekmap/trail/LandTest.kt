package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

class LandTest {

    private fun g(vararg ll: Double) = ll.toList().chunked(2).joinToString(",") { (lat, lon) -> """{"lat":$lat,"lon":$lon}""" }

    private val sample = """
        {"elements":[
          {"type":"way","id":1,"tags":{"landuse":"farmland"},"geometry":[${g(41.0, 28.0, 41.0, 28.01, 41.01, 28.01, 41.0, 28.0)}]},
          {"type":"way","id":2,"tags":{"building":"house"},"geometry":[${g(41.02, 28.0, 41.02, 28.0002, 41.0202, 28.0002, 41.02, 28.0)}]},
          {"type":"way","id":3,"tags":{"barrier":"fence"},"geometry":[${g(41.0, 28.0, 41.001, 28.001)}]},
          {"type":"way","id":4,"tags":{"highway":"track","access":"private"},"geometry":[${g(41.0, 28.0, 41.002, 28.0)}]},
          {"type":"way","id":5,"tags":{"highway":"path"},"geometry":[${g(41.0, 28.0, 41.002, 28.0)}]},
          {"type":"way","id":6,"tags":{"landuse":"forest"},"geometry":[${g(41.0, 28.0, 41.0, 28.01, 41.01, 28.01, 41.0, 28.0)}]},
          {"type":"way","id":7,"tags":{"landuse":"residential"},"geometry":[${g(41.0, 28.0, 41.0, 28.01)}]},
          {"type":"relation","id":8,"tags":{"type":"multipolygon","landuse":"military"},"members":[
            {"type":"way","ref":10,"role":"outer","geometry":[${g(41.1, 28.1, 41.1, 28.2)}]},
            {"type":"way","ref":11,"role":"outer","geometry":[${g(41.1, 28.1, 41.2, 28.15)}]},
            {"type":"way","ref":12,"role":"outer","geometry":[${g(41.1, 28.2, 41.2, 28.15)}]},
            {"type":"way","ref":13,"role":"inner","geometry":[${g(41.12, 28.14, 41.12, 28.16, 41.13, 28.15, 41.12, 28.14)}]}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun parsesOverpassAnswer() {
        val d = Land.parse(sample)
        // farmland, building, military; forest and the open residential way are skipped
        assertEquals(3, d.polys.size)
        assertEquals(listOf(LandKind.FIELD, LandKind.HOME, LandKind.MILITARY), d.polys.map { it.kind })
        assertTrue(d.polys[1].building)
        assertEquals(listOf(LineKind.FENCE, LineKind.PRIVATE_WAY), d.lines.map { it.kind })
        // the military relation: three open pieces in mixed directions → one closed outer ring + the inner ring
        val mil = d.polys[2]
        assertEquals(2, mil.rings.size)
        for (r in mil.rings) {
            assertEquals(r[0], r[r.size - 2], 0.0)
            assertEquals(r[1], r[r.size - 1], 0.0)
        }
        assertEquals(8, mil.rings.first { it.size == 8 }.size) // 3 corners + closing point
    }

    @Test
    fun classifiesTags() {
        fun t(vararg kv: String) = org.json.JSONObject().apply { kv.toList().chunked(2).forEach { (k, v) -> put(k, v) } }
        assertEquals(LandKind.FIELD, Land.areaKind(t("landuse", "orchard")))
        assertEquals(LandKind.HOME, Land.areaKind(t("landuse", "farmyard")))
        assertEquals(LandKind.MILITARY, Land.areaKind(t("military", "danger_area")))
        assertEquals(LandKind.HOME, Land.areaKind(t("access", "private", "leisure", "pitch")))
        assertNull(Land.areaKind(t("access", "private", "highway", "service")))
        assertNull(Land.areaKind(t("building", "no")))
        assertNull(Land.areaKind(t("landuse", "meadow")))
        assertEquals(LineKind.FENCE, Land.lineKind(t("barrier", "wall")))
        assertNull(Land.lineKind(t("barrier", "gate")))
    }

    @Test
    fun queryCoversTheBox() {
        val q = Land.query(BBox(28.39, 41.12, 28.46, 41.17))
        assertTrue(q.contains("(41.12,28.39,41.17,28.46)"))
        assertTrue(q.startsWith("[out:json]") && q.endsWith("out geom qt;"))
    }

    @Test
    fun worldCoverNames() {
        assertEquals("N39E027", WorldCover.tileName(39, 27))
        assertEquals("S03W060", WorldCover.tileName(-3, -60))
    }

    // ------------------------------------------------------------------ synthetic COG
    /**
     * A tiny tiled GeoTIFF laid out like a WorldCover file (36000² cells of 1/12000°, 1024² deflate
     * blocks, N39E027) where only one block has data: cropland on its left half, built-up on the right.
     */
    private fun fakeCog(blockX: Int, blockY: Int): ByteArray {
        val across = (36000 + 1023) / 1024
        val nTiles = across * across
        val block = ByteArray(1024 * 1024) { i -> if (i % 1024 < 512) 40 else 50 }
        val z = Deflater().run { setInput(block); finish(); val o = ByteArrayOutputStream(); val b = ByteArray(65536); while (!finished()) o.write(b, 0, deflate(b)); end(); o.toByteArray() }
        val entries = 8
        val ifdAt = 8
        val ifdSize = 2 + entries * 12 + 4
        val offsAt = ifdAt + ifdSize
        val cntsAt = offsAt + 4 * nTiles
        val dataAt = cntsAt + 4 * nTiles
        val buf = ByteBuffer.allocate(dataAt + z.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(ifdAt)
        buf.putShort(entries.toShort())
        fun e(tag: Int, type: Int, count: Int, v: Int) { buf.putShort(tag.toShort()).putShort(type.toShort()).putInt(count).putInt(v) }
        e(256, 4, 1, 36000); e(257, 4, 1, 36000); e(259, 3, 1, 8); e(317, 3, 1, 1)
        e(322, 3, 1, 1024); e(323, 3, 1, 1024); e(324, 4, nTiles, offsAt); e(325, 4, nTiles, cntsAt)
        buf.putInt(0)
        val idx = blockY * across + blockX
        for (i in 0 until nTiles) buf.putInt(if (i == idx) dataAt else 0)
        for (i in 0 until nTiles) buf.putInt(if (i == idx) z.size else 0)
        buf.put(z)
        return buf.array()
    }

    @Test
    fun readsCogBlocksForABox() {
        // block (12, 0): lon 27 + 12*1024/12000 = 28.024°, top lat 42°
        val file = fakeCog(12, 0)
        var fetched = 0L
        val ranger = WorldCover.Ranger { url, from, len ->
            if (!url.contains("N39E027")) throw Net.Http(403)
            fetched += len
            file.copyOfRange(from.toInt(), minOf(file.size.toLong(), from + len).toInt())
        }
        val lon0 = 27 + 12 * 1024 / 12000.0
        val box = BBox(lon0 + 0.01, 41.97, lon0 + 0.07, 41.99)
        val grid = WorldCover.read(box, ranger)
        assertEquals(WorldCover.STEP0, grid.step, 1e-12)
        // left half of the block (512 cells ≈ 0.0427°) is cropland, the rest built-up
        assertEquals(WorldCover.CROPLAND, grid.at(lon0 + 0.02, 41.98))
        assertEquals(WorldCover.BUILT_UP, grid.at(lon0 + 0.05, 41.98))
        assertEquals(0, grid.at(lon0 - 1.0, 41.98)) // outside the grid
        assertTrue("fetched only header + one block, not the file", fetched < 200_000)
    }

    @Test
    fun coarserLevelForBigBoxes() {
        val file = fakeCog(0, 0)
        val ranger = WorldCover.Ranger { _, _, _ -> throw Net.Http(404) } // all sea
        val g = WorldCover.read(BBox(27.0, 39.0, 29.9, 41.9), ranger)
        assertTrue(g.w <= 3000 && g.h <= 3000)
        assertEquals(0, g.count(WorldCover.CROPLAND))
        assertTrue(file.isNotEmpty())
    }

    // ------------------------------------------------------------------ live (opt-in: LAND_LIVE=1)
    private fun http(url: String, range: String? = null): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.setRequestProperty("User-Agent", Net.UA)
        if (range != null) c.setRequestProperty("Range", range)
        c.readTimeout = 200_000
        val code = c.responseCode
        if (code != 200 && code != 206) throw Net.Http(code)
        return c.inputStream.use { it.readBytes() }
    }

    @Test
    fun liveCatalcaTrek() {
        assumeTrue(System.getenv("LAND_LIVE") == "1")
        // the user's İnceğiz–Çatalca trek area
        val box = BBox(28.42, 41.135, 28.45, 41.16)
        val t0 = System.currentTimeMillis()
        val cover = WorldCover.read(box, { url, from, len -> http(url, "bytes=$from-${from + len - 1}") })
        println("WorldCover ${cover.w}x${cover.h} in ${System.currentTimeMillis() - t0} ms: cropland ${cover.count(40)} · built-up ${cover.count(50)} · tree ${cover.count(10)} · grass ${cover.count(30)}")
        assertTrue(cover.w >= 300 && cover.h >= 300)
        assertTrue(cover.count(10) + cover.count(30) + cover.count(40) > cover.w * cover.h / 2)
        val js = String(http(Land.url(Land.MIRRORS[0], box)), Charsets.UTF_8)
        val osm = Land.parse(js)
        println("OSM ${js.length / 1024} KB: $osm")
    }
}
