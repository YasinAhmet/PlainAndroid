package app.plain.maps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.Locale

/** Edge cases and stress for the feed readers, timetable maths, route finder and codecs (2026-11-28 pass). */
class EdgeCaseTest {
    private fun dir(): File = kotlin.io.path.createTempDirectory("maps-edge").toFile()

    private fun File.put(name: String, text: String): File = File(this, name).also { it.writeText(text, Charsets.UTF_8) }

    // ------------------------------------------------------------------ CSV
    @Test
    fun csvQuotes() {
        assertEquals(listOf("a", "b,c", "d \"e\" f", "g"), Text.split("a,\"b,c\",\"d \"\"e\"\" f\",g", ','))
        // a stray quote inside an unquoted field is text; it must not swallow the columns after it
        assertEquals(listOf("5", "12\" BUS", "41.0", "29.0"), Text.split("5;12\" BUS;41.0;29.0", ';'))
        assertEquals(listOf("ABC \"X\" DEF", "1"), Text.split("ABC \"X\" DEF,1", ','))
        // blanks before an opening quote, an empty quoted field, a quote right after a closing one
        assertEquals(listOf("1", "x, y", "2"), Text.split("1, \"x, y\",2", ','))
        assertEquals(listOf("", "", "z"), Text.split("\"\",,z", ','))
        assertEquals(listOf("say \"hi\""), Text.split("\"say \"\"hi\"\"\"", ','))
        // the fast path (İETT, no quotes) is unchanged, empty fields included
        assertEquals(listOf("1", "", "", "x", ""), Text.split("1;;;x;", ';'))
        assertTrue(Text.openQuote("1,\"open", ','))
        assertFalse(Text.openQuote("1,\"closed\",2", ','))
        assertFalse(Text.openQuote("5;12\" BUS", ';'))
    }

    @Test
    fun csvMultiLineFieldsBomCrlfAndShortRows() {
        val csv = "﻿\"stop_id\",\"stop_name\",\"stop_desc\",\"x\"\r\n" +
            "1,\"A\",\"first line\r\nsecond line\",5\r\n" +
            "2,B,,6\r\n" +
            "\r\n" +
            "3,C\r\n"
        val c = Text.Csv(csv.reader().buffered())
        assertEquals(listOf("stop_id", "stop_name", "stop_desc", "x"), c.header)
        val r1 = c.next()!!
        assertEquals("first line\nsecond line", c.get(r1, c.col("stop_desc")))
        assertEquals("5", c.get(r1, c.col("x")))
        val r2 = c.next()!!
        assertEquals("2", c.get(r2, c.col("stop_id"))); assertEquals("", c.get(r2, c.col("stop_desc"))); assertEquals("6", c.get(r2, c.col("x")))
        val r3 = c.next()!! // a short row: the missing columns read as empty
        assertEquals("C", c.get(r3, c.col("stop_name"))); assertEquals("", c.get(r3, c.col("x")))
        assertEquals("", c.get(r3, c.col("no_such_column")))
        assertNull(c.next())
    }

    @Test
    fun csvUnclosedQuoteDoesNotSwallowRows() {
        val csv = "id,name,x\n1,\"broken,5\n2,ok,6\n3,ok,7\n"
        val c = Text.Csv(csv.reader().buffered())
        assertEquals("1", c.get(c.next()!!, 0))
        assertEquals(listOf("2", "ok", "6"), c.next())
        assertEquals(listOf("3", "ok", "7"), c.next())
        assertNull(c.next())
    }

    @Test
    fun csvStress() {
        val sb = StringBuilder("route_id;route_short_name;route_long_name;route_desc;route_code\n")
        for (i in 0 until 60_000) sb.append(i).append(";L").append(i).append(";\"A; B\";SEFER; ŞURADAN;C").append(i).append('\n')
        val c = Text.Csv(sb.toString().reader().buffered(), freeText = "route_desc")
        var n = 0
        val t0 = System.currentTimeMillis()
        while (true) {
            val row = c.next() ?: break
            assertEquals("C$n", c.get(row, c.col("route_code")))
            assertEquals("A; B", c.get(row, c.col("route_long_name")))
            n++
        }
        assertEquals(60_000, n)
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 8000)
    }

    // ------------------------------------------------------------------ times and entities
    @Test
    fun timesParse() {
        assertEquals(25 * 3600 + 10 * 60, Text.time("25:10:00"))
        assertEquals(6 * 3600 + 40 * 60, Text.time("06:40"))
        assertEquals(7 * 3600 + 5 * 60, Text.time(" 7:05:00 "))
        assertEquals(24 * 3600, Text.time("24:00:00"))
        assertNull(Text.time(""))
        assertNull(Text.time("aa:bb"))
        assertNull(Text.time("12:60:00"))
        assertNull(Text.time("-1:00:00"))
        assertNull(Text.time("99999999:00:00")) // would overflow into a negative "departure"
        assertNull(Text.time("0640"))
    }

    @Test
    fun entities() {
        assertEquals("ş", Text.entity("351", 10))
        assertEquals("ş", Text.entity("15F", 16))
        assertEquals("😀", Text.entity("128512", 10)) // an emoji: two chars, not a truncated one
        assertNull(Text.entity("99999999999", 10))
        assertNull(Text.entity("0", 10))
        assertNull(Text.entity("D800", 16))
        // these used to throw NumberFormatException out of the sync
        assertEquals("a &#99999999999; b", Net.xmlText("a &#99999999999; b"))
        assertEquals("Kadıköy ş 😀", Net.xmlText("Kadıköy &#351; &#x1F600;"))
        assertEquals("Ankara ş &#xFFFFFFFFFF;x", Ego.text("<b>Ankara</b> &#x15F; &#xFFFFFFFFFF;x"))
    }

    // ------------------------------------------------------------------ GTFS feeds
    private val stopsTxt = "stop_id,stop_code,stop_name,stop_lat,stop_lon\n" +
        "S1,1,FIRST,,\n" + // no coordinates
        "S2,2,KADIKÖY,41.0100,29.0100\n" +
        "S3,3,ÜSKÜDAR,41.0200,29.0200\n" +
        "S4,4,BEŞİKTAŞ,41.0300,29.0300\n" +
        "S5,5,UZAK,NaN,29.0\n"

    @Test
    fun stopWithoutPositionKeepsTheTripsStartTime() {
        val d = dir()
        try {
            val t = Transit.read(
                d.put("stops.txt", stopsTxt),
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\n"),
                d.put("trips.txt", "route_id,service_id,trip_id\nR1,WK,T1\n"),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,0,0,20260101,20991231\n"),
                d.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,06:00:00,06:00:00,S1,1\nT1,,,S2,2\nT1,,,S3,3\nT1,06:30:00,06:30:00,S4,4\n"),
                box = BBox(28.0, 40.0, 30.0, 42.0), today = "20261128",
            )
            assertEquals(2, t.badStops) // no coordinates, and "NaN"
            assertEquals(1, t.patterns.size)
            val p = t.patterns[0]
            assertEquals(listOf("Kadıköy", "Üsküdar", "Beşiktaş"), p.stops.map { t.stops[it].name })
            // starts at the first stop's 06:00 (it has no position), not at the last stop's 06:30
            assertArrayEquals(intArrayOf(0x1F, 6 * 3600, 1800), p.trips[0])
        } finally { d.deleteRecursively() }
    }

    @Test
    fun stopTimesWithoutSequenceColumnPastMidnightAndBlankLines() {
        val d = dir()
        try {
            val t = Transit.read(
                d.put("stops.txt", stopsTxt),
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\n"),
                d.put("trips.txt", "route_id,service_id,trip_id,trip_headsign\nR1,WK,T1,\"Last, stop\"\n"),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\nWK,1,1,1,1,1,1,1,20260101,20991231\n"),
                d.put("stop_times.txt", "﻿trip_id;arrival_time;departure_time;stop_id\r\n" +
                    "T1;24:50:00;24:50:00;S2\r\n\r\nT1;;;S3\r\nT1;25:10:00;25:10:00;S4\r\n"),
                box = BBox(28.0, 40.0, 30.0, 42.0), today = "20261128",
            )
            val p = t.patterns.single()
            assertEquals(listOf("Kadıköy", "Üsküdar", "Beşiktaş"), p.stops.map { t.stops[it].name })
            assertEquals("Last, stop", p.headsign())
            assertArrayEquals(intArrayOf(0x7F, 24 * 3600 + 50 * 60, 1200), p.trips[0])
        } finally { d.deleteRecursively() }
    }

    @Test
    fun stopTimesWithoutTripIdFailsClearly() {
        val d = dir()
        try {
            Transit.read(
                d.put("stops.txt", stopsTxt),
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\n"),
                d.put("trips.txt", "route_id,service_id,trip_id\nR1,WK,T1\n"),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nWK,1,1,1,1,1,0,0\n"),
                d.put("stop_times.txt", "trip,arrival_time,stop_id\nT1,06:00:00,S2\n"),
                box = BBox(28.0, 40.0, 30.0, 42.0),
            )
            fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("trip_id"))
        } finally { d.deleteRecursively() }
    }

    @Test
    fun endedTimetablesNextToCurrentOnesAreLeftOut() {
        val d = dir()
        try {
            // R1 has an old weekday timetable (ended) and the current one; R2 only one that has ended (kept: it's all R2 has)
            val t = Transit.read(
                d.put("stops.txt", stopsTxt),
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\nR2,,Coastal Express Line,3\nR3,M1,Metro,1\n"),
                d.put("trips.txt", "route_id,service_id,trip_id\nR1,OLD,A\nR1,NEW,B\nR2,OLD2,C\nR3,NEW,D\n"),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                    "OLD,1,1,1,1,1,0,0,20250101,20251231\nNEW,1,1,1,1,1,1,1,20260101,20991231\nOLD2,1,1,1,1,1,0,0,20250101,20251231\n"),
                d.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "A,07:00:00,07:00:00,S2,1\nA,07:20:00,07:20:00,S3,2\n" +
                    "B,07:00:00,07:00:00,S2,1\nB,07:20:00,07:20:00,S3,2\n" +
                    "C,08:00:00,08:00:00,S3,1\nC,08:20:00,08:20:00,S4,2\n" +
                    "D,09:00:00,09:00:00,S2,1\nD,09:20:00,09:20:00,S4,2\n"),
                box = BBox(28.0, 40.0, 30.0, 42.0), busOnly = true, today = "20261128",
            )
            assertEquals(2, t.routes.size) // the metro is left to OpenStreetMap
            val r1 = t.patterns.single { t.routes[it.route].short == "10" }
            assertEquals(1, r1.trips.size) // the departure is not doubled
            assertEquals(0x7F, r1.trips[0][0])
            val r2 = t.patterns.single { t.routes[it.route].gtfsId == "R2" }
            assertEquals(1, r2.trips.size)
            // a route with only a long name keeps its own line number instead of joining one line "?"
            assertEquals("Coastal Expr", t.routes[r2.route].ref)
            assertEquals("10", t.routes[r1.route].ref)
            // the same files read as of 2025: nothing has ended yet, both timetables count
            val t2 = Transit.read(File(d, "stops.txt"), File(d, "routes.txt"), File(d, "trips.txt"), File(d, "calendar.txt"), File(d, "stop_times.txt"),
                box = BBox(28.0, 40.0, 30.0, 42.0), busOnly = true, today = "20250601")
            assertEquals(2, t2.patterns.single { t2.routes[it.route].short == "10" }.trips.size)
        } finally { d.deleteRecursively() }
    }

    @Test
    fun stationsNoTripCallsAtAreLeftOut() {
        val d = dir()
        try {
            val t = Transit.read(
                d.put("stops.txt", "stop_id,stop_name,stop_lat,stop_lon,location_type,parent_station\n" +
                    "ST,KONAK İSTASYONU,41.0150,29.0150,1,\n" + // a station: no trip calls there
                    "S2,KADIKÖY,41.0100,29.0100,0,ST\n" +
                    "S3,ÜSKÜDAR,41.0200,29.0200,,\n" +
                    "EN,GİRİŞ,41.0300,29.0300,2,ST\n"), // an entrance a (broken) feed runs a trip through: kept
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\n"),
                d.put("trips.txt", "route_id,service_id,trip_id\nR1,WK,T1\n"),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nWK,1,1,1,1,1,0,0\n"),
                d.put("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,06:00:00,06:00:00,S2,1\nT1,,,S3,2\nT1,06:20:00,06:20:00,EN,3\n"),
                box = BBox(28.0, 40.0, 30.0, 42.0), today = "20261128",
            )
            assertEquals(listOf("S2", "S3", "EN"), t.stops.map { it.gtfsId })
            val p = t.patterns.single()
            assertEquals(listOf("S2", "S3", "EN"), p.stops.map { t.stops[it].gtfsId })
            assertEquals(3, p.dists.size)
            assertTrue(p.dists[2] > p.dists[1])
        } finally { d.deleteRecursively() }
    }

    @Test
    fun staleServiceRules() {
        val days = mapOf("old" to 0x1F, "new" to 0x1F, "sun" to 0x40, "shared" to 0x1F, "odd" to 0x1F)
        val ends = mapOf("old" to "20250101", "new" to "20991231", "sun" to "20991231", "shared" to "20250101", "odd" to "31.12.2025")
        // route 0: old covered by new → stale; route 1: only Sunday is current → the old weekdays stay
        assertEquals(setOf("old"), Transit.staleServices(days, ends, mapOf(0 to setOf("old", "new"), 1 to setOf("sun", "odd")), "20261128"))
        assertEquals(emptySet<String>(), Transit.staleServices(days, ends, mapOf(1 to setOf("old", "sun")), "20261128"))
        // a service shared with a route that still needs it is kept
        assertEquals(emptySet<String>(), Transit.staleServices(days, ends, mapOf(0 to setOf("shared", "new"), 1 to setOf("shared")), "20261128"))
        // dates in another form never count as ended
        assertFalse(Transit.expired("31.12.2025", "20261128"))
        assertFalse(Transit.expired("", "20261128"))
        assertTrue(Transit.expired("20261127", "20261128"))
        assertFalse(Transit.expired("20261128", "20261128"))
    }

    @Test
    fun stopTimesInZipAndStress() {
        val d = dir()
        try {
            val trips = 20_000
            val st = StringBuilder("trip_id,arrival_time,departure_time,stop_id,stop_sequence\n")
            val tr = StringBuilder("route_id,service_id,trip_id\n")
            for (i in 0 until trips) {
                tr.append("R1,WK,T").append(i).append('\n')
                val start = 5 * 3600 + (i % 1200) * 60
                for (k in 0 until 10) {
                    val tm = if (k == 0 || k == 9) Times.clock(start + k * 120) + ":00" else ""
                    st.append('T').append(i).append(',').append(tm).append(',').append(tm).append(",S").append(2 + k % 3).append(',').append(k + 1).append('\n')
                }
            }
            val zip = File(d, "stop_times.zip")
            java.util.zip.ZipOutputStream(zip.outputStream()).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("gtfs/stop_times.txt")); z.write(st.toString().toByteArray()); z.closeEntry()
            }
            val t0 = System.currentTimeMillis()
            val t = Transit.read(
                d.put("stops.txt", stopsTxt),
                d.put("routes.txt", "route_id,route_short_name,route_long_name,route_type\nR1,10,Ten,3\n"),
                d.put("trips.txt", tr.toString()),
                d.put("calendar.txt", "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday\nWK,1,1,1,1,1,0,0\n"),
                zip, box = BBox(28.0, 40.0, 30.0, 42.0),
            )
            assertEquals(trips, t.tripsRead)
            assertEquals(1, t.patterns.size)
            assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 9000)
        } finally { d.deleteRecursively() }
    }

    @Test
    fun gtfsShapesSkipBadPoints() {
        val csv = "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n" +
            "A,41.02,29.02,3\nA,41.00,29.00,1\nA,NaN,29.01,2\nA,0,0,4\nA,41.01,29.01,2\n" +
            "B,41.0,29.0,1\n" + // a single point is no line
            "C,41.0,29.0,1\nC,41.1,29.1,2\n"
        val into = HashMap<String, List<DoubleArray>>()
        Transit.readGtfsShapes(csv.byteInputStream(), setOf("A", "B"), 0.0, into, box = BBox(28.0, 40.0, 30.0, 42.0))
        assertEquals(setOf("A"), into.keys)
        assertArrayEquals(doubleArrayOf(29.00, 41.00, 29.01, 41.01, 29.02, 41.02), into["A"]!![0], 1e-12)
        // without a box only the NaN goes
        val all = HashMap<String, List<DoubleArray>>()
        Transit.readGtfsShapes(csv.byteInputStream(), setOf("A"), 0.0, all)
        assertEquals(4, all["A"]!![0].size / 2)
    }

    @Test
    fun codecSkipsNaN() {
        val b = Codec.shape(listOf(doubleArrayOf(29.0, 41.0, Double.NaN, 41.0, 29.1, 41.1, Double.POSITIVE_INFINITY, 1.0)))
        val back = Codec.shape(b)
        assertArrayEquals(doubleArrayOf(29.0, 41.0, 29.1, 41.1), back[0], 1e-6)
    }

    // ------------------------------------------------------------------ timetable maths
    @Test
    fun departuresDedupedAndLastOfTheDay() {
        // the same 23:40 trip from two calendars, plus 22:00
        val trips = intArrayOf(0x1F, 22 * 3600, 600, 0x1F, 23 * 3600 + 2400, 600, 0x1F, 23 * 3600 + 2400, 600)
        val dists = intArrayOf(0, 1000)
        val mon = Times.dayBit(java.util.Calendar.MONDAY)
        val sun = Times.dayBit(java.util.Calendar.SUNDAY)
        val next = Times.next(trips, dists, 0, 21 * 3600, mon, sun, 4)
        assertEquals(listOf(22 * 3600, 23 * 3600 + 2400), next)
        assertEquals(1, Times.lastShown(next, 3)) // 23:40 is the last bus
        assertEquals(listOf(22 * 3600, 23 * 3600 + 2400), Times.all(trips, dists, 0, 0x1F))
        // more departures than shown: none is marked
        assertEquals(-1, Times.lastShown(listOf(1, 2, 3, 4), 3))
        assertEquals(-1, Times.lastShown(emptyList(), 3))
        assertEquals(0, Times.lastShown(listOf(5), 1))
        // after midnight on Tuesday: Monday's last trip, nothing of Tuesday's service before 22:00 is "last"
        val tue = Times.dayBit(java.util.Calendar.TUESDAY)
        val late = Times.next(intArrayOf(0x1F, 24 * 3600 + 600, 600), dists, 0, 300, tue, mon, 4)
        assertEquals(listOf(600, 24 * 3600 + 600), late)
        assertEquals("00:10", Times.clock(24 * 3600 + 600))
        assertEquals("23:59", Times.clock(-60))
    }

    // ------------------------------------------------------------------ route finder
    /** Two lines: 1 runs a→b→c, 2 runs d→e (far away); stops 0..4. */
    private fun net(): Network = Network(
        intArrayOf(10, 11, 12, 13, 14),
        doubleArrayOf(41.000, 41.010, 41.020, 40.500, 40.510),
        doubleArrayOf(29.000, 29.000, 29.000, 29.500, 29.500),
        intArrayOf(100, 200), intArrayOf(1, 2),
        arrayOf(intArrayOf(0, 1, 2), intArrayOf(3, 4)), arrayOf(intArrayOf(0, 1100, 2200), intArrayOf(0, 1100)),
    )

    @Test
    fun routerNoPathSamePlaceAndEmptyCity() {
        val n = net()
        assertEquals(1, Router.find(n, 41.000, 29.000, 41.020, 29.000).first().legs.size)
        // no line joins the two areas
        assertTrue(Router.find(n, 41.000, 29.000, 40.510, 29.500).isEmpty())
        // nowhere near a stop
        assertTrue(Router.find(n, 38.0, 27.0, 41.020, 29.000).isEmpty())
        // NaN coordinates (no fix) find nothing instead of failing
        assertTrue(Router.find(n, Double.NaN, Double.NaN, 41.020, 29.000).isEmpty())
        assertTrue(Router.samePlace(41.0, 29.0, 41.0, 29.0))
        assertTrue(Router.samePlace(41.0, 29.0, 41.001, 29.0))
        assertFalse(Router.samePlace(41.0, 29.0, 41.01, 29.0))
        // a city with no lines (synced, but nothing in the feed)
        val empty = Network(IntArray(0), DoubleArray(0), DoubleArray(0), IntArray(0), IntArray(0), emptyArray(), emptyArray())
        assertTrue(Router.find(empty, 41.0, 29.0, 41.02, 29.0).isEmpty())
    }

    @Test
    fun savedRouteKeptWhenRankedLower() {
        val sorted = listOf("A", "B", "C", "D>E", "F")
        assertEquals(listOf("A", "B", "C"), Router.withPreferred(sorted, 3, null) { it })
        assertEquals(listOf("A", "B", "C", "D>E"), Router.withPreferred(sorted, 3, "D>E") { it })
        assertEquals(listOf("A", "B", "C"), Router.withPreferred(sorted, 3, "B") { it })
        // its lines are gone after a re-sync: just the best ones
        assertEquals(listOf("A", "B", "C"), Router.withPreferred(sorted, 3, "X>Y") { it })
        assertEquals(emptyList<String>(), Router.withPreferred(emptyList<String>(), 3, "X") { it })
    }

    // ------------------------------------------------------------------ EGO stop numbers used in two towns
    @Test
    fun egoStopNumberInTwoTownsTakesTheOneOnTheLine() {
        val line = Ego.Line("100-1", "A-B", 30, intArrayOf(), intArrayOf(), intArrayOf(),
            listOf("1001" to "Kızılay", "1002" to "Sıhhiye", "1003" to "Ulus"))
        val osm = listOf(
            Ego.OsmStop("1002", "Kırıkkale Merkez", 39.846, 33.515), // the same number 70 km away, listed first
            Ego.OsmStop("1001", "Kızılay", 39.920, 32.854),
            Ego.OsmStop("1002", "Sıhhiye", 39.929, 32.858),
            Ego.OsmStop("1003", "Ulus", 39.941, 32.855),
            Ego.OsmStop("9999;9998", "Elsewhere", 39.0, 32.0),
        )
        val p = Ego.place(listOf(line), osm, interpolate = false)
        assertEquals(39.929, p.pos["1002"]!![0], 1e-9)
        assertNotNull(p.pos["9998"])
        // a number only ever in the other town keeps it (as before)
        val p2 = Ego.place(listOf(line), listOf(Ego.OsmStop("1002", "x", 39.846, 33.515), Ego.OsmStop("1002", "y", 39.0, 31.0)), interpolate = false)
        assertEquals(39.846, p2.pos["1002"]!![0], 1e-9)
    }

    @Test
    fun egoColumnPastMidnight() {
        assertArrayEquals(intArrayOf(19800, 85500, 87300, 89100), Ego.afterMidnight(listOf(19800, 85500, 900, 2700)))
        // sorted, or only slightly out of order: unchanged
        assertArrayEquals(intArrayOf(900, 19800, 85500), Ego.afterMidnight(listOf(900, 19800, 85500)))
        assertArrayEquals(intArrayOf(21600, 21000), Ego.afterMidnight(listOf(21600, 21000)))
        assertArrayEquals(IntArray(0), Ego.afterMidnight(emptyList()))
        val html = "<table class=\"hs-table\"><tr><td class=\"hs-times\">05:30<br>23:45<br>00:15</td><td class=\"hs-times\">06:00</td>" +
            "<td class=\"hs-times\"></td></tr></table><table class=\"route-table\"><tr><td>1</td><td>1001</td><td>KIZILAY</td></tr>" +
            "<tr><td>2</td><td>1002</td><td>ULUS</td></tr></table>"
        val l = Ego.parseLine("100-1", html)!!
        assertArrayEquals(intArrayOf(19800, 85500, 87300), l.weekday)
        assertArrayEquals(intArrayOf(21600), l.saturday)
        assertEquals(0, l.sunday.size)
        // kept between sync steps as text: the late times survive
        assertArrayEquals(l.weekday, Ego.decode(Ego.encode(l))!!.weekday)
        // Friday's 00:15 bus is still there on Saturday at 00:10
        val sat = Times.dayBit(java.util.Calendar.SATURDAY); val fri = Times.dayBit(java.util.Calendar.FRIDAY)
        val trips = Ego.departures(l).flatMap { listOf(it[0], it[1], 0) }.toIntArray()
        assertEquals(listOf(900), Times.next(trips, intArrayOf(0, 100), 0, 600, sat, fri, 1))
    }

    // ------------------------------------------------------------------ damaged downloads
    @Test
    fun damagedExtractFailsAsIOException() {
        val d = dir()
        try {
            fun run(bytes: ByteArray) {
                val f = File(d, "x.osm.pbf").apply { writeBytes(bytes) }
                PbfExtract(f).run({}, {}, {})
            }
            run(ByteArray(0)) // empty: nothing in it, no error
            for (bad in listOf(
                byteArrayOf(0x7F, -1, -1, -1), // a 2 GB block header would be allocated
                byteArrayOf(-1, -1, -1, -16), // negative length
                byteArrayOf(0, 0, 0, 10, 1, 2), // cut short inside the header
            )) {
                try { run(bad); fail("expected an IOException") } catch (_: IOException) {}
            }
            // a header announcing a 1 GB blob
            val hdr = BytesOut().apply { u8(0x0A); str("OSMData"); u8(0x18); uv(1L shl 30) }.toArray()
            try { run(byteArrayOf(0, 0, 0, hdr.size.toByte()) + hdr); fail("expected an IOException") } catch (_: IOException) {}
        } finally { d.deleteRecursively() }
    }

    @Test
    fun truncatedOverpassAnswerFails() {
        try {
            Overpass.parse("""{"elements":[{"type":"node","id":1,"lat":41.0,"lon":29.0,"tags":{"shop":"ba""".byteInputStream()) {}
            fail("expected an IOException")
        } catch (_: IOException) {}
        val got = ArrayList<OsmEl>()
        Overpass.parse("""{"elements":[{"type":"node","id":1,"lat":41.0,"lon":29.0}]}""".byteInputStream()) { got.add(it) }
        assertEquals(1, got.size)
    }

    // ------------------------------------------------------------------ Turkish locale
    @Test
    fun machineTextIgnoresTurkishLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("28.95,40.9,29.25,41.27", BBox(28.95, 40.9, 29.25, 41.27).encode())
            assertEquals(BBox(28.95, 40.9, 29.25, 41.27), BBox.decode(BBox(28.95, 40.9, 29.25, 41.27).encode()))
            assertEquals("1.5 km", Fmt.dist(1500.0))
            assertEquals("06:05", Times.clock(6 * 3600 + 300))
            // header names: "TRIP_ID" must not become "trıp_ıd"
            val c = Text.Csv("TRIP_ID,STOP_ID,ISIM\n1,2,İ\n".reader().buffered())
            assertEquals(0, c.col("trip_id")); assertEquals(2, c.col("isim"))
            assertEquals("istanbul isparta", Text.norm("İSTANBUL ISPARTA"))
            assertEquals(0x1F, Situational.dayMask("i"))
            assertEquals(41.0191700005564, Text.coord("410.191.700.005.564", 39.5, 42.5)!!, 1e-12)
            assertEquals(41.05, Text.coord("41,05", 39.5, 42.5)!!, 1e-12)
        } finally {
            Locale.setDefault(saved)
        }
    }
}
