package app.plain.maps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LogicTest {
    @Test
    fun mangledCoordinates() {
        assertEquals(41.01917000055640, Text.coord("410.191.700.005.564", 39.5, 42.5)!!, 1e-12)
        assertEquals(28.6843529999755, Text.coord("286.843.529.999.755", 26.0, 31.0)!!, 1e-12)
        assertEquals(41.05, Text.coord("41.05", 39.5, 42.5)!!, 1e-12)
        assertEquals(41.05, Text.coord("41,05", 39.5, 42.5)!!, 1e-12)
        assertNull(Text.coord("", 39.5, 42.5))
        assertNull(Text.coord("abc", 39.5, 42.5))
    }

    @Test
    fun mojibake() {
        assertEquals("KADIKÖY - KİRAZLITEPE", Text.fixMojibake("KADIKÃ–Y - KÄ°RAZLITEPE"))
        assertEquals("11ÇB_D_D0", Text.fixMojibake("11Ã‡B_D_D0"))
        // real Turkish text with Â must stay as it is
        assertEquals("GÜZERGÂHINDA", Text.fixMojibake("GÜZERGÂHINDA"))
        assertEquals("plain", Text.fixMojibake("plain"))
    }

    @Test
    fun normAndTitle() {
        assertEquals("kadikoy iskele", Text.norm("KADIKÖY İSKELE"))
        assertEquals("sisli cagdas", Text.norm("Şişli-Çağdaş"))
        assertEquals("Kadıköy - Kirazlıtepe", Text.title("KADIKÖY - KİRAZLITEPE"))
        assertEquals("Already Mixed", Text.title("Already Mixed"))
    }

    @Test
    fun csvFreeTextOverflow() {
        val csv = "route_id;agency_id;route_short_name;route_long_name;route_type;route_desc;route_code\n" +
            "7;1;11ÇB;A - B;3;SEFERLER; ŞURADAN GİDER.;11ÇB_D_D0\n"
        val c = Text.Csv(csv.reader().buffered(), freeText = "route_desc")
        val row = c.next()!!
        assertEquals("11ÇB_D_D0", c.get(row, c.col("route_code")))
        assertEquals("SEFERLER; ŞURADAN GİDER.", c.get(row, c.col("route_desc")))
    }

    @Test
    fun codecs() {
        val parts = listOf(doubleArrayOf(28.9, 41.0, 28.901234, 41.00321, 29.5, 40.9), doubleArrayOf(1.0, 2.0, 3.0, 4.0))
        val back = Codec.shape(Codec.shape(parts))
        assertEquals(2, back.size)
        for (i in parts.indices) for (k in parts[i].indices) assertEquals(parts[i][k], back[i][k], 1e-6)
        val ints = intArrayOf(5, 3, 100000, 0, -7, 12)
        assertArrayEquals(ints, Codec.ints(Codec.ints(ints)))
    }

    @Test
    fun json() {
        val j = Json("""{"a": [1, 2.5, -3e2], "b": {"c": "x\"yç", "d": null, "e": true}, "f": []}""".byteInputStream())
        j.beginObject()
        assertEquals("a", j.nextName())
        j.beginArray()
        val nums = ArrayList<Double>()
        while (j.hasNext()) nums.add(j.nextDouble())
        j.endArray()
        assertEquals(listOf(1.0, 2.5, -300.0), nums)
        assertTrue(j.hasNext())
        assertEquals("b", j.nextName())
        val m = j.readStringMap()
        assertEquals("x\"yç", m["c"])
        assertEquals("true", m["e"])
        assertTrue(j.hasNext()); assertEquals("f", j.nextName()); j.skipValue()
        j.endObject()
    }

    @Test
    fun clipLineAndRing() {
        val pieces = Clip.clipLine(doubleArrayOf(-1.0, 0.5, 2.0, 0.5, 2.0, 0.6, -1.0, 0.6), 0.0, 0.0, 1.0, 1.0)
        assertEquals(2, pieces.size)
        assertArrayEquals(doubleArrayOf(0.0, 0.5, 1.0, 0.5), pieces[0], 1e-12)
        val ring = Clip.clipRing(doubleArrayOf(-1.0, -1.0, 2.0, -1.0, 2.0, 2.0, -1.0, 2.0, -1.0, -1.0), 0.0, 0.0, 1.0, 1.0)!!
        assertEquals(1.0, abs(Clip.area(ring)), 1e-12)
        val s = Clip.simplify(doubleArrayOf(0.0, 0.0, 1.0, 0.001, 2.0, 0.0, 3.0, 5.0), 0.01)
        assertEquals(3, s.size / 2)
    }

    @Test
    fun coastToLand() {
        // a coastline running west→east across the box: land (on its left) is the north half
        val land = Coast.land(listOf(doubleArrayOf(-1.0, 0.5, 0.5, 0.5), doubleArrayOf(0.5, 0.5, 2.0, 0.5)), 0.0, 0.0, 1.0, 1.0)!!
        assertEquals(1, land.size)
        assertEquals(0.5, abs(Clip.area(land[0])), 1e-9)
        // an island (counter-clockwise) plus that coast
        val island = doubleArrayOf(0.2, 0.1, 0.3, 0.1, 0.3, 0.2, 0.2, 0.2, 0.2, 0.1)
        val land2 = Coast.land(listOf(doubleArrayOf(-1.0, 0.5, 2.0, 0.5), island), 0.0, 0.0, 1.0, 1.0)!!
        assertEquals(0.5 + 0.01, land2.sumOf { abs(Clip.area(it)) }, 1e-9)
        // reversed direction: land is the south half
        val south = Coast.land(listOf(doubleArrayOf(2.0, 0.5, -1.0, 0.5)), 0.0, 0.0, 1.0, 1.0)!!
        val b = Clip.bounds(south[0])
        assertEquals(0.0, b[1], 1e-12); assertEquals(0.5, b[3], 1e-12)
        // a coast that stops inside the box is incomplete
        assertNull(Coast.land(listOf(doubleArrayOf(-1.0, 0.5, 0.5, 0.5)), 0.0, 0.0, 1.0, 1.0))
        // the coast enters and leaves through the south edge: land on its left is a peninsula
        val pen = Coast.land(listOf(doubleArrayOf(0.7, -1.0, 0.7, 0.6, 0.3, 0.6, 0.3, -1.0)), 0.0, 0.0, 1.0, 1.0)
        assertNotNull(pen)
        assertEquals(0.4 * 0.6, pen!!.sumOf { abs(Clip.area(it)) }, 1e-9)
        // the same path reversed is a bay: everything except the inlet
        val bay = Coast.land(listOf(doubleArrayOf(0.3, -1.0, 0.3, 0.6, 0.7, 0.6, 0.7, -1.0)), 0.0, 0.0, 1.0, 1.0)
        assertEquals(1.0 - 0.4 * 0.6, bay!!.sumOf { abs(Clip.area(it)) }, 1e-9)
    }

    @Test
    fun joinRings() {
        val (rings, open) = Coast.join(
            listOf(doubleArrayOf(0.0, 0.0, 1.0, 0.0), doubleArrayOf(1.0, 1.0, 1.0, 0.0), doubleArrayOf(1.0, 1.0, 0.0, 0.0), doubleArrayOf(5.0, 5.0, 6.0, 6.0)),
            directed = false,
        )
        assertEquals(1, rings.size)
        assertEquals(1, open.size)
    }

    @Test
    fun cellsRoundTrip() {
        val got = ArrayList<IntArray>()
        val blobs = HashMap<String, BytesOut>()
        val p = Pieces { lvl, x, y, k, data ->
            got.add(intArrayOf(lvl, x, y, k))
            blobs.getOrPut("$lvl/$x/$y") { BytesOut() }.bytes(data, 0, data.size)
        }
        val line = Pieces.merc(doubleArrayOf(28.95, 41.00, 29.05, 41.02, 29.10, 41.05))
        p.line(K.PRIMARY, line, 0, name = "Barbaros Bulvarı")
        p.point(K.STOP, Geo.mx(29.0), Geo.my(41.01), 2, ref = 42)
        p.polygon(K.WATER, listOf(Pieces.merc(doubleArrayOf(28.9, 40.9, 29.2, 40.9, 29.2, 41.1, 28.9, 41.1, 28.9, 40.9))), 0)
        assertTrue(got.any { it[0] == 0 } && got.any { it[0] == 1 } && got.any { it[0] == 2 })
        var named = 0; var stops = 0; var water = 0
        for ((k, b) in blobs) {
            val lvl = k.substringBefore('/').toInt()
            for (f in Cells.decode(b.toArray())) {
                if (f.kind == K.PRIMARY && f.name == "Barbaros Bulvarı") { named++; assertEquals(2, lvl) }
                if (f.kind == K.STOP) { stops++; assertEquals(42L, f.ref); assertTrue(f.point) }
                if (f.kind == K.WATER) { water++; assertNotNull(f.rings) }
                // cell-local coordinates stay within the padded cell
                assertTrue(f.x0 >= -0.05f && f.x1 <= 1.05f && f.y0 >= -0.05f && f.y1 <= 1.05f)
            }
        }
        assertTrue(named > 0); assertEquals(1, stops); assertTrue(water > 3)
    }

    @Test
    fun departures() {
        // two trips Mon–Fri at 06:00 and 23:50 (40 min long), one on Sunday
        val trips = intArrayOf(0x1F, 6 * 3600, 2400, 0x1F, 23 * 3600 + 3000, 2400, 1 shl 6, 8 * 3600, 2400)
        val dists = intArrayOf(0, 500, 1000)
        val mon = Times.dayBit(java.util.Calendar.MONDAY)
        val sun = Times.dayBit(java.util.Calendar.SUNDAY)
        assertEquals(1, mon); assertEquals(1 shl 6, sun)
        // at the middle stop, Monday 05:00 → 06:20, then 00:10 next night (24:10)
        assertEquals(listOf(6 * 3600 + 1200, 24 * 3600 + 600), Times.next(trips, dists, 1, 5 * 3600, mon, sun, 5))
        // Tuesday 00:05: Monday's late trip is still running (00:10 at the middle stop)
        assertEquals(600, Times.next(trips, dists, 1, 300, 2, mon, 1).first())
        assertEquals(listOf(8 * 3600 + 1200), Times.all(trips, dists, 1, sun))
    }
}
