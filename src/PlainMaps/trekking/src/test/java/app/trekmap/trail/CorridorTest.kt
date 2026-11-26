package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CorridorTest {
    // 10 km straight north from 41.0, 29.0, plus a 5 km leg east
    private val trail = run {
        val la = DoubleList(); val lo = DoubleList()
        for (i in 0..100) { la.add(41.0 + i * 0.0009); lo.add(29.0) }
        for (i in 1..50) { la.add(41.09); lo.add(29.0 + i * 0.0012) }
        listOf(Seg(la.toArray(), lo.toArray()))
    }

    @Test
    fun coversTheTrailAndStaysNarrow() {
        val z = 16
        val g = Corridor.grid(trail, 500.0, z)
        // every tile the trail passes through is included
        for (i in 0 until trail[0].size) assertTrue(g.contains(Geo.tileX(trail[0].lon[i], z), Geo.tileY(trail[0].lat[i], z)))
        // a point 3 km east of the north-south leg is not
        assertFalse(g.contains(Geo.tileX(29.036, z), Geo.tileY(41.03, z)))
        // a point 400 m away is
        assertTrue(g.contains(Geo.tileX(29.0047, z), Geo.tileY(41.03, z)))
        // area check: ~ (15 km × 1 km + end caps) / tile area, and far below the bounding box
        val tileM = Geo.metersPerUnit(41.05) / (1 shl z)
        val expected = (15_000.0 * 1_000 + Math.PI * 500 * 500) / (tileM * tileM)
        val n = g.count().toDouble()
        assertTrue("count $n vs expected $expected", n > expected * 0.95 && n < expected * 2.0)
        val box = Route(null, trail).bbox()!!.pad(500.0)
        assertTrue(n < Geo.tileCount(box, z, z) * 0.5)
    }

    @Test
    fun iteratesColumnByColumn() {
        val g = Corridor.grid(trail, 200.0, 14)
        var lastX = Int.MIN_VALUE; var lastY = Int.MIN_VALUE; var k = 0L
        g.forEach { x, y ->
            assertTrue(x > lastX || (x == lastX && y > lastY))
            lastX = x; lastY = y; k++
        }
        assertEquals(g.count(), k)
    }

    @Test
    fun simplifyAndEncodeRoundTrip() {
        val s = Corridor.simplify(trail, 300.0)
        assertTrue(s[0].size < trail[0].size)
        assertEquals(trail[0].lat.last(), s[0].lat.last(), 1e-12)
        val back = Corridor.decode(Corridor.encode(s))
        assertEquals(s[0].size, back[0].size)
        assertEquals(s[0].lon[3], back[0].lon[3], 1e-12)
    }
}
