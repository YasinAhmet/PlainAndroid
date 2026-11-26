package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs

class LogicTest {

    @Test
    fun mercatorRoundTripAndDistance() {
        val lat = 41.1786; val lon = 28.9706
        assertEquals(lat, Geo.lat(Geo.my(lat)), 1e-9)
        assertEquals(lon, Geo.lon(Geo.mx(lon)), 1e-9)
        assertEquals(111_195.0, Geo.dist(0.0, 0.0, 1.0, 0.0), 50.0)
        val b = BBox(28.90, 41.15, 28.95, 41.20)
        assertEquals(1L, Geo.tileCount(b, 8, 8))
        assertTrue(Geo.tileCount(b, 16, 16) > 100)
    }

    private fun standStill(speed: Float): Int {
        val f = TrackFilter()
        val rnd = Random(1)
        var stored = 0
        var t = 0L
        repeat(600) { // 10 minutes of 1 Hz fixes with ±8 m noise around one spot
            val n = 8.0 / 111_320
            if (f.push(41.0 + rnd.nextGaussian() * n, 29.0 + rnd.nextGaussian() * n, 8f, t, speed) != null) stored++
            t += 1000
        }
        return stored
    }

    @Test
    fun standingStillProducesAlmostNoPoints() {
        val withSpeed = standStill(0.1f) // chip reports ~0 m/s (normal on real phones)
        assertTrue("stored $withSpeed jitter points with speed", withSpeed <= 2)
        val noSpeed = standStill(-1f) // worst case: no Doppler speed at all
        assertTrue("stored $noSpeed jitter points without speed", noSpeed < 60)
    }

    @Test
    fun walkingDistanceIsPreservedAndOutlierRejected() {
        val f = TrackFilter()
        val rnd = Random(2)
        val pts = ArrayList<DoubleArray>()
        var t = 0L
        for (i in 0 until 1000) { // 1000 s walking north at 1.4 m/s = 1400 m
            val lat = 41.0 + i * 1.4 / 111_320 + rnd.nextGaussian() * 4.0 / 111_320
            val outlier = i == 500
            val p = f.push(if (outlier) lat + 0.01 else lat, 29.0, 6f, t, 1.4f) // 0.01° ≈ 1.1 km jump
            if (p != null) pts += p
            t += 1000
        }
        var d = 0.0
        for (i in 1 until pts.size) d += Geo.dist(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        assertTrue("distance $d", abs(d - 1400) < 100)
        assertTrue(pts.none { it[0] > 41.0 + 1500.0 / 111_320 })
    }

    @Test
    fun poorAccuracyRejected() {
        val f = TrackFilter()
        assertNull(f.push(41.0, 29.0, 80f, 0))
        assertNull(f.push(41.0, 29.0, 20f, 1000)) // warm-up wants a sharp first fix
        assertNotNull(f.push(41.0, 29.0, 5f, 2000))
    }

    @Test
    fun stillDetectorAndAscent() {
        val s = StillDetector()
        var still = false
        for (i in 0..130) still = s.push(41.0, 29.0, 5.0, i * 1000L, 0f)
        assertTrue(still)
        val a = AscentCounter(4.0)
        for (e in listOf(100.0, 102.0, 101.0, 106.0, 110.0, 105.0, 120.0)) a.push(e)
        assertEquals(25.0, a.total, 1e-9) // 100→106 (+6), 106→110 (+4), 105→120 (+15)
    }

    private val gpx = """<?xml version="1.0"?>
        <gpx version="1.1" xmlns="http://www.topografix.com/GPX/1/1">
          <metadata><name>Belgrad loop</name></metadata>
          <wpt lat="1" lon="1"><name>Spring</name></wpt>
          <trk><name>ignored</name><trkseg>
            <trkpt lat="41.20" lon="28.95"><ele>120.5</ele><time>2026-05-01T08:00:00Z</time></trkpt>
            <trkpt lat="41.21" lon="28.96"><ele>130</ele><time>2026-05-01T08:10:00+03:00</time></trkpt>
          </trkseg><trkseg>
            <trkpt lat="41.22" lon="28.97"/><trkpt lat="41.23" lon="28.98"/>
          </trkseg></trk>
        </gpx>"""

    @Test
    fun parsesGpxTrack() {
        val r = GpxIo.parse(ByteArrayInputStream(gpx.toByteArray()))
        assertEquals("Belgrad loop", r.name)
        assertEquals(2, r.segs.size)
        assertEquals(4, r.points)
        assertEquals(130.0, r.segs[0].ele!![1], 1e-9)
        assertEquals(1777622400000L, r.segs[0].time!![0])
    }

    @Test
    fun parsesGpxRouteAndKmlAndKmz() {
        val rte = """<gpx><rte><name>R</name><rtept lat="1" lon="2"/><rtept lat="1.1" lon="2.1"/></rte></gpx>"""
        val r = GpxIo.parse(ByteArrayInputStream(rte.toByteArray()))
        assertEquals("R", r.name); assertEquals(2, r.points)

        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2" xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><name>K</name>
            <Placemark><Point><coordinates>5,5,0</coordinates></Point></Placemark>
            <Placemark><LineString><coordinates>28.1,41.1,10 28.2,41.2,20
            28.3,41.3,30</coordinates></LineString></Placemark>
            <Placemark><gx:Track><when>x</when><gx:coord>29 40 5</gx:coord><gx:coord>29.1 40.1 6</gx:coord></gx:Track></Placemark>
            </Document></kml>"""
        val k = GpxIo.parse(ByteArrayInputStream(kml.toByteArray()))
        assertEquals("K", k.name)
        assertEquals(2, k.segs.size)
        assertEquals(41.3, k.segs[0].lat[2], 1e-9)
        assertEquals(29.1, k.segs[1].lon[1], 1e-9)

        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("doc.kml")); z.write(kml.toByteArray()); z.closeEntry() }
        assertEquals(5, GpxIo.parse(ByteArrayInputStream(bos.toByteArray())).points)
    }

    @Test
    fun photoWaypointsAreWrittenAndIgnoredOnRead() {
        val r = GpxIo.parse(ByteArrayInputStream(gpx.toByteArray()))
        val out = ByteArrayOutputStream()
        GpxIo.write(out, r, "With photos", null, listOf(GpxIo.Wpt(41.205, 28.955, 125.0, 1777622700000L, "Photo 1", "photos/Trekmap_1.jpg")))
        val text = out.toString("UTF-8")
        assertTrue(text.contains("<wpt lat=\"41.2050000\" lon=\"28.9550000\">"))
        assertTrue(text.contains("<link href=\"photos/Trekmap_1.jpg\">"))
        val back = GpxIo.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals("With photos", back.name)
        assertEquals(r.points, back.points) // the waypoint is not mistaken for a track point
    }

    @Test
    fun gpxWriteReadRoundTrip() {
        val r = GpxIo.parse(ByteArrayInputStream(gpx.toByteArray()))
        val out = ByteArrayOutputStream()
        GpxIo.write(out, r, "Tom & \"Jerry\" <trail>", "desc")
        val back = GpxIo.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals("Tom & \"Jerry\" <trail>", back.name)
        assertEquals(r.points, back.points)
        assertEquals(r.lengthM(), back.lengthM(), 0.5)
        assertEquals(r.segs[0].time!![1], back.segs[0].time!![1])
    }
}
