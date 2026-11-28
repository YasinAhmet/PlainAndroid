package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.Locale
import kotlin.math.abs

/** GPX hardening, Past-trail index recovery and the offline position maths. */
class RobustTest {
    private fun parse(s: String) = GpxIo.parse(ByteArrayInputStream(s.toByteArray()))
    private fun tmpDir(): File = Files.createTempDirectory("store").toFile().also { it.deleteOnExit() }

    private inline fun <T> inLocale(tag: String, block: () -> T): T {
        val prev = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag(tag)); return block() } finally { Locale.setDefault(prev) }
    }

    // ------------------------------------------------------------------ GPX
    @Test fun gpxAcceptsCommaDecimalsAndDropsImpossiblePoints() {
        val r = parse("""<gpx><trk><trkseg>
            <trkpt lat="41,0123" lon="28,9876"><ele>120,5</ele></trkpt>
            <trkpt lat="41.0200" lon="28.9900"/>
            <trkpt lat="Infinity" lon="29"/><trkpt lat="1e300" lon="29"/><trkpt lat="91" lon="29"/>
            <trkpt lat="41" lon="-181"/><trkpt lat="1,234,5" lon="29"/><trkpt lat="NaN" lon="NaN"/>
            </trkseg></trk></gpx>""")
        assertEquals(2, r.points)
        assertEquals(41.0123, r.segs[0].lat[0], 1e-9)
        assertEquals(28.9876, r.segs[0].lon[0], 1e-9)
        assertEquals(120.5, r.segs[0].ele!![0], 1e-9)
        assertTrue(r.lengthM().isFinite())
        // KML with garbage tuples
        val k = parse("<kml><Placemark><LineString><coordinates>29,41 Infinity,41 29,95 29.1,41.1,NaN 29.2,41.2</coordinates></LineString></Placemark></kml>")
        assertEquals(3, k.points)
        assertTrue(k.segs[0].ele == null || k.segs[0].ele!!.all { it.isNaN() })
    }

    @Test fun gpxTimesWithoutZoneAreUtc() {
        assertEquals(1714550400000L, GpxIo.parseTime("2024-05-01T08:00:00"))
        assertEquals(1714550400000L, GpxIo.parseTime("2024-05-01T08:00:00Z"))
        assertEquals(0L, GpxIo.parseTime(""))
        assertEquals(0L, GpxIo.parseTime("2024-02-30T08:00:00"))
    }

    @Test fun gpxWriterSkipsImpossiblePointsAndIgnoresTheLanguage() {
        val seg = Seg(
            doubleArrayOf(41.0, Double.NaN, 41.001, 41.002), doubleArrayOf(29.0, 29.0, Double.POSITIVE_INFINITY, 29.002),
            doubleArrayOf(100.0, 1.0, 2.0, Double.POSITIVE_INFINITY), longArrayOf(1_700_000_000_000L, 0, 0, 1_700_000_060_000L),
        )
        val text = inLocale("tr-TR") {
            ByteArrayOutputStream().also { GpxIo.write(it, Route("x", listOf(seg)), "İğneada Longoz · ışık", "d",
                listOf(GpxIo.Wpt(Double.NaN, 1.0, 0.0, 0, "bad"), GpxIo.Wpt(41.0, 29.0, Double.NEGATIVE_INFINITY, 0, "ok"))) }.toString("UTF-8")
        }
        assertFalse(text, text.contains("NaN") || text.contains("Infinity"))
        assertTrue(text, text.contains("lat=\"41.0000000\""))      // '.' decimals in Turkish too
        assertTrue(text, text.contains("<ele>100.0</ele>"))
        assertEquals(1, Regex("<wpt ").findAll(text).count())
        val back = parse(text)
        assertEquals(2, back.points)
        assertEquals("İğneada Longoz · ışık", back.name)
        assertEquals("a&lt;b&gt; c", GpxIo.esc("a\u0001<b>\u0000 c"))
    }

    @Test fun fiftyThousandPointTrackRoundTripsInTurkish() {
        val n = 50_000
        val seg = Seg(DoubleArray(n) { 41.0 + it * 1e-5 }, DoubleArray(n) { 29.0 + it * 1e-6 }, DoubleArray(n) { 100.0 + it % 40 },
            LongArray(n) { 1_700_000_000_000L + it * 2000L })
        val out = inLocale("tr-TR") { ByteArrayOutputStream().also { GpxIo.write(it, Route("big", listOf(seg)), "big") } }
        val back = GpxIo.parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals(n, back.points)
        assertEquals(seg.time!![n - 1], back.segs[0].time!![n - 1])
        assertEquals(Route(null, listOf(seg)).lengthM(), back.lengthM(), 1.0)
    }

    @Test fun shareNamesNeverHoldDoubleDotsOrHalfLetters() {
        assertEquals("Walk.gpx", ShareProvider.fileName("Walk..."))
        assertEquals("trail.gpx", ShareProvider.fileName("..."))
        assertEquals("Day 1 . Day 2.gpx", ShareProvider.fileName("Day 1 .. Day 2"))
        assertEquals("Gün batımı İğneada.gpx", ShareProvider.fileName(" Gün batımı İğneada "))
        for (n in listOf("Walk...", "a..b", ". hidden", "x".repeat(79) + "𝐀tail", "𝐀".repeat(60))) {
            val f = ShareProvider.fileName(n)
            val base = f.removeSuffix(".gpx")
            assertFalse(f, base.contains("..") || base.startsWith(".") || base.endsWith("."))
            assertFalse(f, base.isNotEmpty() && base.last().isHighSurrogate())
            // the zip / kmz made from it must pass the share provider's own name check
            assertTrue(f, "$base.zip".matches(Regex("[\\p{L}\\p{N} ._()-]+\\.(zip|kmz)")) && !"$base.zip".contains(".."))
        }
    }

    // ------------------------------------------------------------------ Past trails index
    @Test fun trailWhoseIndexIsLostOrTornComesBack() {
        Store.initDir(tmpDir())
        val r = Route("x", listOf(Seg(doubleArrayOf(41.0, 41.01), doubleArrayOf(29.0, 29.0), doubleArrayOf(100.0, 130.0),
            longArrayOf(1_700_000_000_000L, 1_700_000_600_000L))))
        val e = Store.save(Kind.TRACK, r, "Belgrad “loop” <1>", 1_700_000_000_000L, 600, 30.0)
        assertEquals(1, Store.list(Kind.TRACK).size)
        val meta = File(Store.dir(Kind.TRACK), "${e.id}.json")
        assertTrue(meta.delete())                                 // the app died between the GPX and its index
        val l = Store.list(Kind.TRACK)
        assertEquals(1, l.size)
        assertEquals(e.id, l[0].id)
        assertEquals("Belgrad “loop” <1>", l[0].name)
        assertEquals(1_700_000_000_000L, l[0].time)
        assertEquals(600L, l[0].durS)
        assertEquals(r.lengthM(), l[0].distM, 1.0)
        assertTrue(meta.exists())                                 // rebuilt once, then read normally
        meta.writeText("{\"name\":\"Bel")                         // torn index
        assertEquals("Belgrad “loop” <1>", Store.list(Kind.TRACK).single().name)
        // junk next to it is ignored; a temp file is never a trail
        File(Store.dir(Kind.TRACK), "junk_1.gpx").writeText("not xml")
        File(Store.dir(Kind.TRACK), "x_2.gpx.tmp").writeText("<gpx/>")
        File(Store.dir(Kind.TRACK), "../evil.gpx").writeText("<gpx/>")
        assertEquals(1, Store.list(Kind.TRACK).size)
    }

    @Test fun nonFiniteNumbersCantMakeATrailUnsavable() {
        Store.initDir(tmpDir())
        val r = Route(null, listOf(Seg(doubleArrayOf(41.0, 41.001), doubleArrayOf(29.0, 29.0))))
        val e = Store.save(Kind.TRACK, r, "nan", 0L, 0L, Double.NaN)
        val back = Store.get(Kind.TRACK, e.id)
        assertNotNull(back)
        assertEquals(0.0, back!!.ascM, 0.0)
        assertNull(Store.get(Kind.TRACK, "../x"))
    }

    // ------------------------------------------------------------------ position sheet maths
    @Test fun plusCodesMatchTheReference() {
        assertEquals("8FVC2222+22", Where.plusCode(47.0000625, 8.0000625))
        assertEquals("4VCPPQGP+Q9", Where.plusCode(-41.2730625, 174.7859375))
        assertEquals("7FG49QCJ+2V", Where.plusCode(20.3700625, 2.7821875))
        assertEquals(Where.plusCode(1.0, -179.0), Where.plusCode(1.0, 181.0))      // longitude wraps
        for ((la, lo) in listOf(90.0 to 180.0, -90.0 to -180.0, 95.0 to 0.0, 0.0 to 540.0, -0.0 to -0.0)) {
            val c = Where.plusCode(la, lo)
            assertEquals(c, 11, c.length)
            assertEquals(c, '+', c[8])
        }
    }

    @Test fun coordinatesAndBearings() {
        assertEquals("41.012340, -28.976540", inLocale("tr-TR") { Where.coords(41.01234, -28.97654) })
        assertEquals(0.0, Where.bearing(0.0, 0.0, 1.0, 0.0), 1e-6)
        assertEquals(90.0, Where.bearing(0.0, 0.0, 0.0, 1.0), 1e-6)
        assertEquals(180.0, Where.bearing(0.0, 0.0, -1.0, 0.0), 1e-6)
        assertEquals(270.0, Where.bearing(0.0, 0.0, 0.0, -1.0), 1e-6)
        assertEquals("SW", Heading.cardinal(Where.bearing(41.0, 29.0, 40.99, 28.99)))
    }

    private fun utc(s: String) = Instant.parse(s).toEpochMilli()
    private fun near(expected: Long, actual: Long, minutes: Int) =
        assertTrue("off by ${(actual - expected) / 60_000} min", abs(actual - expected) <= minutes * 60_000L)

    @Test fun sunriseAndSunsetOffline() {
        // Istanbul (UTC+3 all year): midsummer ~05:32 / 20:39, midwinter ~08:25 / 17:39
        val summer = Where.sun(41.0082, 28.9784, utc("2024-06-21T09:00:00Z"))!!
        near(utc("2024-06-21T02:32:00Z"), summer.rise, 6)
        near(utc("2024-06-21T17:39:00Z"), summer.set, 6)
        val winter = Where.sun(41.0082, 28.9784, utc("2024-12-21T09:00:00Z"))!!
        near(utc("2024-12-21T05:25:00Z"), winter.rise, 6)
        near(utc("2024-12-21T14:39:00Z"), winter.set, 6)
        // late evening still means today's sun; twelve hours on gives tomorrow's sunrise
        val night = utc("2024-06-21T20:30:00Z")
        val today = Where.sun(41.0082, 28.9784, night)!!
        assertTrue(today.set < night)
        assertTrue(Where.sun(41.0082, 28.9784, night + 12 * 3_600_000L)!!.rise > night)
        // Svalbard: midnight sun / polar night; nonsense input
        assertNull(Where.sun(78.2, 15.6, utc("2024-06-21T12:00:00Z")))
        assertNull(Where.sun(78.2, 15.6, utc("2024-12-21T12:00:00Z")))
        assertNull(Where.sun(Double.NaN, 29.0, 0L))
        assertNull(Where.sun(95.0, 29.0, 0L))
    }
}
