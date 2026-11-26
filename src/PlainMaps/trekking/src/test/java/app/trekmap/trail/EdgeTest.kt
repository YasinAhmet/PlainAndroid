package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Stress / edge cases for the pure logic. */
class EdgeTest {
    private fun parse(s: String) = GpxIo.parse(ByteArrayInputStream(s.toByteArray()))
    private fun parse(b: ByteArray) = GpxIo.parse(ByteArrayInputStream(b))

    private fun fails(block: () -> Unit) {
        try { block(); fail("expected an exception") } catch (e: AssertionError) { throw e } catch (e: Exception) { /* ok */ }
    }

    // ------------------------------------------------------------------ GPX / KML input
    @Test fun emptyAndGarbageInputsFailCleanly() {
        fails { parse("") }
        fails { parse("not xml at all") }
        fails { parse("<gpx></gpx>") }
        fails { parse("<gpx><wpt lat=\"1\" lon=\"2\"/></gpx>") }                       // waypoints only
        fails { parse("<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"/></trkseg></trk></gpx>") } // 1 point
        fails { parse("<kml><Placemark><Point><coordinates>1,2</coordinates></Point></Placemark></kml>") }
        val zip = ByteArrayOutputStream().also { b -> ZipOutputStream(b).use { it.putNextEntry(ZipEntry("x.txt")); it.write(1); it.closeEntry() } }
        fails { parse(zip.toByteArray()) }                                                 // KMZ without KML
        fails { parse(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0)) }      // truncated zip
    }

    @Test fun toleratesMessyButValidFiles() {
        val gpx10 = """<?xml version="1.0"?><gpx version="1.0" xmlns="http://www.topografix.com/GPX/1/0">
            <trk><trkseg><trkpt lat=" 41.1 " lon="29.1"><ele>NaN</ele><time>2024-05-01T08:00:00.123Z</time></trkpt>
            <trkpt lat="abc" lon="29.2"/><trkpt lon="29.3"/><trkpt lat="41.2" lon="29.2"><ele></ele>
            <time>2024-05-01T10:00:00+02:00</time></trkpt><trkpt lat="41.3" lon="29.3"><time>yesterday</time></trkpt></trkseg></trk></gpx>"""
        val r = parse(gpx10)
        assertEquals(3, r.points)                      // bad points skipped
        assertTrue(r.segs[0].ele == null || r.segs[0].ele!!.all { it.isNaN() })
        assertEquals(1714550400123L, r.segs[0].time!![0])
        assertEquals(1714550400000L, r.segs[0].time!![1]) // offset handled
        assertEquals(0L, r.segs[0].time!![2])             // unparseable time → 0

        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1.1\" lon=\"2.1\"/></trkseg></trk></gpx>".toByteArray()
        assertEquals(2, parse(bom).points)

        val kml = "<kml><Document><name><![CDATA[Tom & Jerry's <loop>]]></name><Placemark><LineString><coordinates>\n\t28.1,41.1,\n\t28.2,41.2,0 \n 28.3,41.3\n</coordinates></LineString></Placemark></Document></kml>"
        val k = parse(kml)
        assertEquals("Tom & Jerry's <loop>", k.name)
        assertEquals(3, k.points)
    }

    @Test fun externalEntitiesAreNeverRead() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE gpx [<!ENTITY x SYSTEM "file:///etc/passwd">]>
            <gpx><metadata><name>&x;</name></metadata><trk><trkseg><trkpt lat="1" lon="2"/><trkpt lat="1.1" lon="2.1"/></trkseg></trk></gpx>"""
        try {
            val r = parse(xxe)
            assertFalse((r.name ?: "").contains("root"))
        } catch (e: Exception) { /* refusing the DOCTYPE is fine too */ }
    }

    @Test fun hugeTrackParsesAndWritesQuickly() {
        val n = 150_000
        val sb = StringBuilder("<gpx><trk><name>big</name><trkseg>")
        for (i in 0 until n) sb.append("<trkpt lat=\"").append(41 + i * 1e-6).append("\" lon=\"29\"><ele>").append(i % 300).append("</ele></trkpt>")
        sb.append("</trkseg></trk></gpx>")
        val t0 = System.currentTimeMillis()
        val r = parse(sb.toString())
        assertEquals(n, r.points)
        val out = ByteArrayOutputStream()
        GpxIo.write(out, r, "big")
        assertEquals(n, parse(out.toByteArray()).points)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 15_000)
    }

    @Test fun writerSurvivesHostileNames() {
        val r = parse("<gpx><trk><trkseg><trkpt lat=\"1\" lon=\"2\"/><trkpt lat=\"1.1\" lon=\"2.1\"/></trkseg></trk></gpx>")
        for (name in listOf("a\u0001b\u0008c", "emoji 🥾⛰️", "]]> & <x> \"q\" 'a'", "x".repeat(5000), "tab\tnew\nline")) {
            val out = ByteArrayOutputStream()
            GpxIo.write(out, r, name, "desc \u0000 zero", listOf(GpxIo.Wpt(1.0, 2.0, Double.NaN, 0, "p\u0002", "photos/a b.jpg")))
            val back = parse(out.toByteArray())       // must still be valid XML
            assertEquals(2, back.points)
        }
    }

    @Test fun shareFileNamesAreSafe() {
        assertEquals("trail.gpx", ShareProvider.fileName("   "))
        assertFalse(ShareProvider.fileName("../../etc/x").contains('/'))
        assertTrue(ShareProvider.fileName("x".repeat(1000)).length <= 100)
        assertTrue(ShareProvider.fileName("🥾 walk").endsWith(".gpx"))
    }

    // ------------------------------------------------------------------ GPS filter
    @Test fun filterRejectsInvalidFixes() {
        val f = TrackFilter()
        assertNull(f.push(41.0, 29.0, Float.NaN, 0))
        assertNull(f.push(Double.NaN, 29.0, 5f, 1000))
        assertNull(f.push(41.0, Double.POSITIVE_INFINITY, 5f, 2000))
        assertNull(f.push(41.0, 29.0, 0f, 3000))
        assertNull(f.push(41.0, 29.0, -3f, 4000))
        val p = f.push(41.0, 29.0, 5f, 5000)
        assertTrue(p != null && !p[0].isNaN() && !p[1].isNaN())
    }

    @Test fun filterSurvivesTimeGoingBackwardsAndDuplicates() {
        val f = TrackFilter()
        var t = 100_000L
        val out = ArrayList<DoubleArray>()
        for (i in 0 until 200) {
            val ti = if (i % 7 == 0) t - 5000 else t           // batched fixes out of order
            f.push(41.0 + i * 1.4 / 111_320, 29.0, 6f, ti, 1.4f)?.let { out += it }
            if (i % 3 != 0) t += 1000                           // duplicate timestamps
        }
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { !it[0].isNaN() && !it[1].isNaN() })
    }

    @Test fun filterRecoversAfterLongGapAndTeleport() {
        val f = TrackFilter()
        f.push(41.0, 29.0, 5f, 0, 1f)
        f.push(41.0001, 29.0, 5f, 1000, 1f)
        // 1 h later 5 km away (bus ride with the app paused): must follow, not reject forever
        var last: DoubleArray? = null
        for (i in 0 until 10) last = f.push(41.045 + i * 1e-5, 29.0, 5f, 3_600_000L + i * 1000, 1f) ?: last
        assertTrue(last != null && last[0] > 41.04)
    }

    // ------------------------------------------------------------------ corridors
    @Test fun corridorExtremes() {
        val one = listOf(Seg(doubleArrayOf(41.0), doubleArrayOf(29.0)))
        assertTrue(Corridor.grid(one, 500.0, 16).count() > 0)
        val polar = listOf(Seg(doubleArrayOf(84.9, 85.0, 89.0), doubleArrayOf(10.0, 10.1, 10.2)))
        Corridor.grid(polar, 1000.0, 12)                       // must not throw
        val empty = listOf(Seg(DoubleArray(0), DoubleArray(0)))
        assertEquals(0L, Corridor.grid(empty, 500.0, 14).count())
    }

    @Test fun corridorAcross180MeridianStaysLocal() {
        // Fiji-style trail crossing the date line: must not paint a band around the whole planet
        val s = listOf(Seg(doubleArrayOf(-17.0, -17.001, -17.002), doubleArrayOf(179.995, 179.999, -179.997)))
        val g = Corridor.grid(s, 300.0, 14)
        assertTrue("count ${g.count()}", g.count() in 1..100)
    }

    @Test fun corridorTooLargeIsRefusedNotCrashing() {
        // a 2000 km trail at zoom 19 would need a ~1-billion-cell grid
        val s = listOf(Seg(doubleArrayOf(36.0, 54.0), doubleArrayOf(26.0, 45.0)))
        try {
            val g = Corridor.grid(s, 2000.0, 19)
            assertTrue(g.count() > 0)
        } catch (e: Corridor.TooLarge) {
            // fine: the dialog shows it as "won't fit"
        }
    }

    // ------------------------------------------------------------------ logs & dates
    @Test fun devSummaryIgnoresTruncatedAndGarbageLines() {
        val s = DevSummary.of(sequenceOf("F,123", "E", "B,x,y", "F,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,S", "garbage,,,", "", "#x",
            "F,1000,1000,0,41,29,5,,,,,,,,,,,1,S,L", "F,2000,2000,0,41.001,29,5,,,,,,,,,,,1,S,L"))
        assertEquals(2, s.fixes)
        assertTrue(s.storedDistanceM > 100)
        s.text()
    }

    @Test fun badDatesAndHeadings() {
        assertEquals(0L, Season.fromYmd(20240231))
        assertEquals(0L, Season.fromYmd(0))
        assertEquals(0L, Season.fromYmd(99999999))
        assertEquals("N", Heading.cardinal(720.0))
        Heading.cardinal(Double.NaN)
        assertEquals(-1, Season.recommend(emptyList(), Double.NaN))
    }
}
