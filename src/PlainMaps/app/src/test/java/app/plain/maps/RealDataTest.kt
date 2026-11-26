package app.plain.maps

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the importers on real downloaded files (skipped unless the folders are given):
 *   GTFS_DIR = folder with stops.csv, routes.csv, trips.csv, calendar.csv, stop_times.zip, shapes.geojson
 *   OSM_CELL = an Overpass answer for the map query
 */
class RealDataTest {
    @Test
    fun ietfFeed() {
        val dir = System.getenv("GTFS_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val t0 = System.currentTimeMillis()
        dir!!
        val t = Transit.read(File(dir, "stops.csv"), File(dir, "routes.csv"), File(dir, "trips.csv"), File(dir, "calendar.csv"),
            File(dir, "stop_times.zip"), { println("  $it") })
        println("read in ${System.currentTimeMillis() - t0} ms: ${t.stops.size} stops (${t.badStops} bad), ${t.routes.size} routes, " +
            "${t.patterns.size} patterns, ${t.tripsRead} trips (${t.tripsSkipped} skipped)")
        assertTrue(t.stops.size > 14000)
        assertTrue(t.badStops < 20)
        assertTrue(t.tripsRead > 130000)
        assertTrue(t.patterns.size > 2000)
        val wanted = t.patterns.map { t.routes[it.route].code }.filter { it.isNotEmpty() }.toHashSet()
        val t1 = System.currentTimeMillis()
        File(dir, "shapes.geojson").inputStream().buffered(1 shl 16).use { Transit.readShapes(it, wanted, 1.2e-5, t.shapes) }
        val pts = t.shapes.values.sumOf { l -> l.sumOf { it.size / 2 } }
        println("shapes in ${System.currentTimeMillis() - t1} ms: ${t.shapes.size} of ${wanted.size} codes, $pts points after simplifying")
        assertTrue(t.shapes.size >= wanted.size * 0.97)
        // a known line
        val r500 = t.routes.filter { it.short == "500T" }
        println("500T: " + r500.map { it.long }.distinct())
        assertTrue(r500.isNotEmpty())
        // all stops inside Istanbul
        assertTrue(t.stops.all { it.lat in 40.7..41.7 && it.lon in 27.9..30.0 })
        val names = t.stops.take(3).map { it.name }
        println("stop names: $names; headsigns: ${t.patterns.take(3).map { it.headsign() }}")
        // time sanity: night trips run past midnight (up to about 30:30)
        val odd = t.patterns.sumOf { p -> p.trips.count { it[1] !in 0..32 * 3600 || it[2] !in 0..8 * 3600 } }
        println("trips with odd times: $odd")
        assertTrue(odd < 50)
    }

    @Test
    fun overpassCell() {
        val f = System.getenv("OSM_CELL")?.let(::File)
        assumeTrue(f != null && f.isFile)
        val counts = HashMap<Int, Int>()
        var bytes = 0L
        val coast = ArrayList<DoubleArray>()
        val streets = HashSet<String>()
        val b = MapBuild(Pieces { _, _, _, k, data -> counts.merge(k, 1, Int::plus); bytes += data.size }, { n, _, _ -> streets.add(n) }, { _, g -> coast.add(g) })
        val seen = HashSet<Long>()
        val t0 = System.currentTimeMillis()
        f!!.inputStream().buffered(1 shl 16).use { inp -> Overpass.parse(inp) { e -> if (seen.add(e.uid)) b.element(e) } }
        println("parsed ${seen.size} elements → ${b.features} features in ${System.currentTimeMillis() - t0} ms, pieces by kind $counts, ${bytes / 1024} KB")
        println("${streets.size} street names, ${coast.size} coastline ways")
        assertTrue(b.features > 10000)
        assertTrue((counts[K.MINOR] ?: 0) > 1000)
        assertTrue(coast.isNotEmpty())
    }
}

class PbfRealTest {
    @Test
    fun istanbulExtract() {
        val f = System.getenv("OSM_PBF")?.let(::File)
        assumeTrue(f != null && f.isFile)
        f!!
        val rt = Runtime.getRuntime()
        val t0 = System.currentTimeMillis()
        val ex = PbfExtract(f)
        ex.wantRelation = { Filters.multipolygon(it) || Filters.route(it) }
        ex.wantWay = { Filters.mapWay(it) || Filters.poi(it) }
        ex.wantNode = { Filters.poi(it) }
        ex.progress = { println("  $it  (heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB)") }
        val counts = HashMap<Int, Int>()
        var bytes = 0L
        val coast = ArrayList<DoubleArray>()
        val b = MapBuild(Pieces { _, _, _, k, data -> counts.merge(k, 1, Int::plus); bytes += data.size }, { _, _, _ -> }, { _, g -> coast.add(g) })
        var pois = 0; var wayPois = 0
        val routes = ArrayList<OsmEl>()
        ex.run(
            { e -> if (Poi.category(e.tags) != null) pois++ },
            { e -> if (Filters.mapWay(e.tags)) b.element(e); if (Filters.poi(e.tags) && Poi.category(e.tags) != null) wayPois++ },
            { e -> if (Filters.route(e.tags)) routes.add(e) else b.element(e) },
        )
        println("extract ${ex.bbox} in ${System.currentTimeMillis() - t0} ms: ${b.features} map features, $pois node places, $wayPois way places, " +
            "${routes.size} routes, ${coast.size} coast ways, pieces $counts, ${bytes shr 20} MB")
        println("routes: " + routes.map { (it["ref"] ?: "") + ":" + it["route"] }.sorted().distinct().joinToString(" "))
        val withStations = routes.count { r -> r.members!!.any { m -> m.type == 'n' && ex.memberNode(m.ref)?.get("name") != null } }
        println("routes with named stop nodes: $withStations")
        val bb = ex.bbox!!
        val land = Coast.land(coast, bb.w + 0.01, bb.s + 0.01, bb.e - 0.01, bb.n - 0.01)
        println("land rings: ${land?.size}, area share ${land?.sumOf { kotlin.math.abs(Clip.area(it)) }?.div((bb.e - bb.w - 0.02) * (bb.n - bb.s - 0.02))}")
        assertTrue(b.features > 100000)
        assertTrue(pois > 10000)
        assertTrue(land != null)
    }
}

class RouterRealTest {
    @Test
    fun contactRoutes() {
        val dir = System.getenv("GTFS_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!
        val t = Transit.read(File(dir, "stops.csv"), File(dir, "routes.csv"), File(dir, "trips.csv"), File(dir, "calendar.csv"), File(dir, "stop_times.zip"))
        val lineOf = HashMap<String, Int>()
        val pats = t.patterns
        val t0 = System.currentTimeMillis()
        val net = Network(
            IntArray(t.stops.size) { it }, DoubleArray(t.stops.size) { t.stops[it].lat }, DoubleArray(t.stops.size) { t.stops[it].lon },
            IntArray(pats.size) { it }, IntArray(pats.size) { lineOf.getOrPut(t.routes[pats[it].route].short) { lineOf.size } },
            Array(pats.size) { pats[it].stops }, Array(pats.size) { pats[it].dists },
        )
        println("network built in ${System.currentTimeMillis() - t0} ms")
        fun show(name: String, a: DoubleArray, b: DoubleArray) {
            val t1 = System.currentTimeMillis()
            val js = Router.find(net, a[0], a[1], b[0], b[1])
            println("$name: ${js.size} routes in ${System.currentTimeMillis() - t1} ms")
            for (j in js) println("   " + j.legs.joinToString("  →  ") { l ->
                val p = pats[l.v]
                "${t.routes[p.route].short} (${t.stops[p.stops[l.from]].name} → ${t.stops[p.stops[l.to]].name}, ${l.to - l.from} stops)"
            } + "   | changes ${j.changes}, ride ${j.rideMeters / 1000} km, ends ${j.endDist} m away")
            assertTrue(js.isNotEmpty())
        }
        show("Kadıköy → Taksim", doubleArrayOf(40.9905, 29.0237), doubleArrayOf(41.0369, 28.9850))
        show("Tuzla → Beşiktaş", doubleArrayOf(40.8160, 29.3010), doubleArrayOf(41.0430, 29.0070))
        show("Silivri → Kadıköy", doubleArrayOf(41.0735, 28.2460), doubleArrayOf(40.9905, 29.0237))
    }
}

class SituationalTest {
    @Test
    fun plannedTimes() {
        val f = System.getenv("IETT_PLAN")?.let(::File)
        assumeTrue(f != null && f.isFile)
        val xml = f!!.readText()
        val a = xml.indexOf("<GetPlanlananSeferSaati_jsonResult>") + "<GetPlanlananSeferSaati_jsonResult>".length
        val json = Net.xmlText(xml.substring(a, xml.indexOf("</GetPlanlananSeferSaati_jsonResult>")))
        val plan = Situational.parsePlanned(json)
        println("planned: " + plan.mapValues { (_, l) -> l.groupBy { it[0] }.mapValues { it.value.size } })
        val d0 = plan["500T_D_D0"]!!
        assertTrue(d0.any { it[0] == 0x1F } && d0.any { it[0] == 1 shl 5 } && d0.any { it[0] == 1 shl 6 })
        assertTrue(d0.all { it[1] in 0..86400 })
    }
}

class AnkaraProbeTest {
    @Test
    fun centralAnatolia() {
        val f = System.getenv("ANKARA_PBF")?.let(::File)
        assumeTrue(f != null && f.isFile)
        f!!
        val rt = Runtime.getRuntime()
        var peak = 0L
        fun heap() { System.gc(); val u = rt.totalMemory() - rt.freeMemory(); if (u > peak) peak = u }
        val box = BBox(32.2, 39.55, 33.3, 40.25)
        val ex = PbfExtract(f)
        val busRoute = { t: Map<String, String> -> t["type"] == "route" && t["route"] == "bus" }
        ex.wantRelation = { Filters.multipolygon(it) || Filters.route(it) || busRoute(it) }
        ex.wantWay = { Filters.mapWay(it) || Filters.poi(it) }
        ex.wantNode = { Filters.poi(it) || it["highway"] == "bus_stop" || it["public_transport"] == "platform" }
        ex.progress = { heap(); println("  $it  (heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB)") }
        val t0 = System.currentTimeMillis()
        var stops = 0; var withRef = 0; val refs = ArrayList<String>()
        var ways = 0; var waysIn = 0; var pois = 0
        val routes = ArrayList<OsmEl>()
        fun inBox(lat: Double, lon: Double) = lat in box.s..box.n && lon in box.w..box.e
        ex.run(
            { e ->
                if (!inBox(e.lat, e.lon)) return@run
                if (e["highway"] == "bus_stop" || e["public_transport"] == "platform") { stops++; e["ref"]?.let { withRef++; if (refs.size < 25) refs.add(it + "=" + (e["name"] ?: "")) } }
                else pois++
            },
            { e -> ways++; val g = e.geom!!; if (inBox(g[1], g[0])) waysIn++ },
            { e -> if (Filters.route(e.tags) || busRoute(e.tags)) routes.add(e) },
        )
        heap()
        println("done in ${System.currentTimeMillis() - t0} ms, bbox ${ex.bbox}, peak heap ${peak shr 20} MB")
        println("ways $ways (in Ankara box $waysIn), pois in box $pois, bus stops in box $stops, with ref $withRef")
        println("sample refs: $refs")
        val bus = routes.filter { busRoute(it.tags) }
        val busIn = bus.filter { r -> r.members!!.any { it.type == 'n' && !it.lat.isNaN() && inBox(it.lat, it.lon) } }
        println("bus route relations: ${bus.size}, touching Ankara: ${busIn.size}; refs: " + busIn.mapNotNull { it["ref"] }.distinct().sorted().take(60))
        println("rail etc: " + routes.filter { Filters.route(it.tags) }.map { (it["ref"] ?: "") + ":" + it["route"] + ":" + (it["name"] ?: "") }.distinct().sorted())
    }
}

class EgoRealTest {
    @Test
    fun egoLines() {
        val dir = System.getenv("EGO_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        dir!!
        val codes = Ego.lineCodes(File(dir, "_index.html").readText())
        val lines = codes.mapNotNull { (c, _) -> File(dir, c.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".html").takeIf { it.isFile }?.let { Ego.parseLine(c, it.readText()) } }
        val stopNos = lines.flatMap { l -> l.stops.map { it.first } }.toSet()
        println("codes ${codes.size}, parsed ${lines.size}, distinct stops ${stopNos.size}, trips/day weekday ${lines.sumOf { it.weekday.size }} sat ${lines.sumOf { it.saturday.size }} sun ${lines.sumOf { it.sunday.size }}")
        val l0 = lines.first()
        println("first: ${l0.code} '${l0.name}' ${l0.minutes} min, ${l0.stops.size} stops, wk ${l0.weekday.map { Times.clock(it) }}")
        println("no minutes: ${lines.count { it.minutes == 0 }}, no times: ${lines.count { it.weekday.isEmpty() && it.saturday.isEmpty() && it.sunday.isEmpty() }}")
        val notices = Ego.parseNotices(File(dir, "_duyurular.html").readText())
        println("notices: " + notices.map { it.refs.toString() + " " + it.date + " " + it.title.take(50) })
        assertTrue(lines.size > 500)
        // OSM stop numbers
        val f = System.getenv("ANKARA_PBF")?.let(::File) ?: return
        val box = BBox(31.85, 39.35, 33.55, 40.55)
        val ex = PbfExtract(f)
        ex.wantNode = { it["highway"] == "bus_stop" || it["public_transport"] == "platform" }
        val osm = ArrayList<Ego.OsmStop>()
        ex.run({ e -> if (e.lat in box.s..box.n && e.lon in box.w..box.e) osm.add(Ego.OsmStop(e["ref"], e["name"] ?: "", e.lat, e.lon)) }, { }, { })
        val placed = Ego.place(lines, osm)
        val pos = placed.pos
        println("placed: by ref ${placed.byRef}, by name ${placed.byName}, guessed ${placed.guessed}")
        val found = stopNos.count { it in pos }
        println("EGO stops with an OSM position: $found of ${stopNos.size} (${100 * found / stopNos.size}%)")
        val linesOk = lines.count { l -> l.stops.count { it.first in pos } >= l.stops.size * 0.8 }
        println("lines with >=80% placed stops: $linesOk of ${lines.size}")
        val missing = stopNos.filter { it !in pos }.take(15)
        println("missing sample: $missing")
        val lat = pos.values.map { it[0] }; val lon = pos.values.map { it[1] }
        println("OSM stop extent lat ${lat.min()}..${lat.max()} lon ${lon.min()}..${lon.max()}")
    }
}

class EgoNetworkTest {
    @Test
    fun ankaraNetwork() {
        val dir = System.getenv("EGO_DIR")?.let(::File); val f = System.getenv("ANKARA_PBF")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && f != null && f.isFile)
        val codes = Ego.lineCodes(File(dir!!, "_index.html").readText())
        val lines = codes.mapNotNull { (c, _) -> File(dir, c.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".html").takeIf { it.isFile }?.let { Ego.parseLine(c, it.readText()) } }
        val box = BBox(31.85, 39.35, 33.55, 40.55)
        val ex = PbfExtract(f!!)
        ex.wantNode = { Filters.busStop(it) }
        ex.wantRelation = { Filters.busRoute(it) }
        val osm = ArrayList<Ego.OsmStop>(); val shapes = HashMap<String, List<DoubleArray>>()
        ex.run({ e -> if (e.lat in box.s..box.n && e.lon in box.w..box.e) osm.add(Ego.OsmStop(e["ref"], e["name"] ?: "", e.lat, e.lon)) }, { },
            { e -> val parts = e.members!!.filter { it.type == 'w' && it.geom != null }.map { it.geom!! }
                e["ref"]?.let { r -> if (parts.isNotEmpty()) shapes.putIfAbsent(r, Coast.join(parts, false).let { it.second + it.first }) } })
        val placed = Ego.place(lines, osm)
        val t = Ego.toTransit(lines, placed.pos, shapes)
        println("transit: ${t.stops.size} stops, ${t.routes.size} routes, ${t.patterns.size} patterns, ${t.tripsRead} trips, ${t.shapes.size} shapes, skipped ${t.tripsSkipped}")
        val lineOf = HashMap<String, Int>()
        val p = t.patterns
        val net = Network(IntArray(t.stops.size) { it }, DoubleArray(t.stops.size) { t.stops[it].lat }, DoubleArray(t.stops.size) { t.stops[it].lon },
            IntArray(p.size) { it }, IntArray(p.size) { lineOf.getOrPut(t.routes[p[it].route].short) { lineOf.size } }, Array(p.size) { p[it].stops }, Array(p.size) { p[it].dists })
        for ((nm, pt) in listOf("Kızılay" to doubleArrayOf(39.9208, 32.8541), "Batıkent" to doubleArrayOf(39.9680, 32.7310), "Etimesgut" to doubleArrayOf(39.9566, 32.6720))) {
            var n = 0; var best = 1e9; net.within(pt[0], pt[1], 500.0) { _, d -> n++; if (d < best) best = d }
            println("$nm: $n stops within 500 m, nearest ${best.toInt()} m")
        }
        var js = Router.find(net, 39.9208, 32.8541, 39.9566, 32.6720) // Kızılay → Etimesgut
        if (js.isEmpty()) js = Router.find(net, 39.9208, 32.8541, 39.9680, 32.7310)
        for (j in js) println("   " + j.legs.joinToString("  →  ") { l -> val q = p[l.v]; "${t.routes[q.route].code} (${t.stops[q.stops[l.from]].name} → ${t.stops[q.stops[l.to]].name})" } + " | changes ${j.changes}, ends ${j.endDist} m")
        assertTrue(t.patterns.size > 550)
        assertTrue(js.isNotEmpty())
    }
}

class EgoRoadTest {
    @Test
    fun onTheStreets() {
        val dir = System.getenv("EGO_DIR")?.let(::File); val f = System.getenv("ANKARA_PBF")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && f != null && f.isFile)
        val codes = Ego.lineCodes(File(dir!!, "_index.html").readText())
        val lines = codes.mapNotNull { (c, _) -> File(dir, c.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".html").takeIf { it.isFile }?.let { Ego.parseLine(c, it.readText()) } }
        val box = BBox(31.85, 39.35, 33.55, 40.55)
        val ex = PbfExtract(f!!)
        ex.wantNode = { Filters.busStop(it) }
        ex.wantRelation = { Filters.busRoute(it) }
        ex.wantWay = { Filters.drivable(it) }
        val osm = ArrayList<Ego.OsmStop>(); val shapes = HashMap<String, List<DoubleArray>>(); val roads = ArrayList<DoubleArray>()
        ex.run({ e -> if (e.lat in box.s..box.n && e.lon in box.w..box.e) osm.add(Ego.OsmStop(e["ref"], e["name"] ?: "", e.lat, e.lon)) },
            { e -> val g = e.geom!!; val b = Clip.bounds(g); if (Filters.drivable(e.tags) && b[2] >= box.w && b[0] <= box.e && b[3] >= box.s && b[1] <= box.n) roads.add(g) },
            { e -> val parts = e.members!!.filter { it.type == 'w' && it.geom != null }.map { it.geom!! }
                e["ref"]?.let { r -> if (parts.isNotEmpty()) shapes.putIfAbsent(r, Coast.join(parts, false).let { it.second + it.first }) } })
        val rt = Runtime.getRuntime()
        val t0 = System.currentTimeMillis()
        val g = RoadGraph(roads)
        System.gc()
        println("road graph: ${roads.size} ways, ${g.nodes} nodes in ${System.currentTimeMillis() - t0} ms, heap ${(rt.totalMemory() - rt.freeMemory()) shr 20} MB")
        val placed = Ego.place(lines, osm, interpolate = false)
        val t1 = System.currentTimeMillis()
        val r = Ego.route(lines, placed.pos, shapes, g)
        println("routed in ${System.currentTimeMillis() - t1} ms: known ${placed.pos.size}, on streets ${r.onRoad}, straight ${r.straight}, lines given street shapes ${r.roadLines} of ${r.shapes.size} shapeless")
        val all = lines.flatMap { l -> l.stops.map { it.first } }.toSet()
        println("stops placed ${all.count { it in r.pos }} of ${all.size}")
        assertTrue(r.onRoad > 2000)
    }
}

class EgoExtentTest {
    @Test
    fun howFar() {
        val dir = System.getenv("EGO_DIR")?.let(::File); val f = System.getenv("ANKARA_PBF")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && f != null && f.isFile)
        val codes = Ego.lineCodes(File(dir!!, "_index.html").readText())
        val lines = codes.mapNotNull { (c, _) -> File(dir, c.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".html").takeIf { it.isFile }?.let { Ego.parseLine(c, it.readText()) } }
        val ex = PbfExtract(f!!)
        ex.wantNode = { Filters.busStop(it) }
        val osm = ArrayList<Ego.OsmStop>()
        ex.run({ e -> osm.add(Ego.OsmStop(e["ref"], e["name"] ?: "", e.lat, e.lon)) }, { }, { })
        val placed = Ego.place(lines, osm, interpolate = false)
        val egoStops = lines.flatMap { l -> l.stops.map { it.first } }.toSet()
        val pts = egoStops.mapNotNull { placed.pos[it] }
        println("EGO stops placed anywhere: ${pts.size} of ${egoStops.size}; lat ${pts.minOf { it[0] }}..${pts.maxOf { it[0] }} lon ${pts.minOf { it[1] }}..${pts.maxOf { it[1] }}")
        val box = BBox(31.85, 39.35, 33.55, 40.55)
        val outside = pts.filter { !(it[0] in box.s..box.n && it[1] in box.w..box.e) }
        println("outside the current box: ${outside.size}")
        val far = lines.filter { l -> l.stops.any { s -> placed.pos[s.first]?.let { !(it[0] in box.s..box.n && it[1] in box.w..box.e) } == true } }
        println("lines reaching outside: " + far.map { it.code + " " + it.name.take(40) })
        // percentiles, to see whether a few far stops stretch the box
        val la = pts.map { it[0] }.sorted(); val lo = pts.map { it[1] }.sorted()
        fun pc(l: List<Double>, p: Double) = l[((l.size - 1) * p).toInt()]
        println("lat 0.1%..99.9%: ${pc(la, 0.001)}..${pc(la, 0.999)}  lon: ${pc(lo, 0.001)}..${pc(lo, 0.999)}")
        val province = BBox(30.75, 38.55, 34.15, 40.80)
        val inProv = osm.filter { it.lat in province.s..province.n && it.lon in province.w..province.e }
        val p2 = Ego.place(lines, inProv, interpolate = false)
        println("extent from province stops: ${Ego.extent(lines, p2.pos)}")
        val enc = lines.take(5).map { Ego.decode(Ego.encode(it))!! }
        assertTrue(enc.zip(lines.take(5)).all { (a, b) -> a.code == b.code && a.stops == b.stops && a.weekday.contentEquals(b.weekday) && a.minutes == b.minutes })
    }
}
