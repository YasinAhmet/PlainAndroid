package app.plain.maps

import app.plain.lang.tr
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Reads the İETT GTFS files into memory. Pure Kotlin (no Android), so it is unit-tested with
 * the real published files. It copes with the known problems of that feed: semicolon CSVs,
 * spreadsheet-mangled coordinates, double-encoded UTF-8 and stray semicolons in free text.
 */
class Transit {
    class Stop(val gtfsId: String, val code: String, val name: String, val lat: Double, val lon: Double)
    class Route(val gtfsId: String, val short: String, val long: String, val code: String) {
        /**
         * The line number shown and grouped by. GTFS lets a route have only a long name; those used to all become one
         * line "?", now each keeps (the start of) its own name.
         */
        val ref: String get() = short.trim().ifEmpty { long.trim().take(12).trim().ifEmpty { gtfsId.trim().ifEmpty { "?" } } }
    }

    /** One stop sequence of one route (a "variant" in the app). */
    class Pattern(val route: Int, val stops: IntArray) {
        var dir = 0
        val headsigns = HashMap<String, Int>()
        /** GTFS shape_id votes of the pattern's trips (plain GTFS feeds; İETT keys shapes by route code instead). */
        val shapeIds = HashMap<String, Int>()
        /** days mask (bit 0 = Monday … bit 6 = Sunday), start second, duration seconds */
        val trips = ArrayList<IntArray>()
        lateinit var dists: IntArray

        fun headsign(): String = headsigns.maxByOrNull { it.value }?.key ?: ""

        /** The shape most of the pattern's trips use ("" when the feed has none). */
        fun shapeId(): String = shapeIds.maxByOrNull { it.value }?.key ?: ""
    }

    val stops = ArrayList<Stop>()
    val routes = ArrayList<Route>()
    val patterns = ArrayList<Pattern>()
    /** route code (GUZERGAH_KODU) or GTFS shape_id → lon/lat line */
    val shapes = HashMap<String, List<DoubleArray>>()
    var tripsRead = 0
    var tripsSkipped = 0
    var badStops = 0

    companion object {
        // Istanbul and its surroundings, used to put the mangled coordinates' decimal point back
        const val LAT_LO = 39.5
        const val LAT_HI = 42.5
        const val LON_LO = 26.0
        const val LON_HI = 31.0

        fun days(row: (String) -> String): Int {
            var m = 0
            listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday").forEachIndexed { i, d ->
                if (row(d).trim() == "1") m = m or (1 shl i)
            }
            return m
        }

        private fun reader(f: File): BufferedReader = f.inputStream().bufferedReader(Charsets.UTF_8)

        private val DATE = Regex("^(19|20)\\d{6}$")
        private val NOT_STOPS = setOf("1", "2", "3")

        /** A GTFS end_date ("20261005") before [today] (same form). Other forms never count as expired. */
        fun expired(end: String?, today: String): Boolean {
            val e = end?.trim() ?: return false
            return DATE.matches(e) && DATE.matches(today) && e < today
        }

        /**
         * The services to leave out. Only the weekday pattern of a calendar is used (feeds keep running after their
         * dates end), so an old timetable left in a feed next to the current one would show every departure twice.
         * A service that has ended is dropped when the same route has a current service on all of its days; a route
         * whose timetable has simply run out keeps it.
         * [routeServices]: route → its trips' service ids.
         */
        fun staleServices(days: Map<String, Int>, ends: Map<String, String>, routeServices: Map<Int, Set<String>>, today: String): Set<String> {
            val out = HashSet<String>()
            for ((_, sids) in routeServices) {
                var current = 0
                for (s in sids) if (!expired(ends[s], today)) current = current or (days[s] ?: 0)
                if (current == 0) continue
                for (s in sids) {
                    val m = days[s] ?: 0
                    if (expired(ends[s], today) && m and current.inv() == 0) out.add(s)
                }
            }
            // a service shared with a route that still needs it stays
            for ((_, sids) in routeServices) {
                var current = 0
                for (s in sids) if (!expired(ends[s], today)) current = current or (days[s] ?: 0)
                for (s in sids) if (s in out && (days[s] ?: 0) and current.inv() != 0) out.remove(s)
            }
            return out
        }

        /**
         * Reads a feed from files: stops, routes, trips, calendar (CSV/TXT) and stop_times
         * (CSV/TXT or a ZIP holding it). [log] gets progress lines.
         */
        fun read(
            stopsF: File, routesF: File, tripsF: File, calendarF: File, stopTimesF: File,
            log: (String) -> Unit = {}, cancelled: () -> Boolean = { false },
            box: BBox = BBox(LON_LO, LAT_LO, LON_HI, LAT_HI),
            /** Only bus routes (route_type 3, 11, 7xx): rail and ferry lines come from OpenStreetMap. */
            busOnly: Boolean = false,
            /** The day of the sync as yyyyMMdd (calendars that have ended), today when null. */
            today: String? = null,
        ): Transit {
            val t = Transit()
            // calendar: service id → day mask (and its last day)
            val service = HashMap<String, Int>()
            val ends = HashMap<String, String>()
            val canon = HashMap<String, String>()
            reader(calendarF).use { r ->
                val c = Text.Csv(r)
                val sid = c.col("service_id"); val end = c.col("end_date")
                while (true) {
                    val row = c.next() ?: break
                    val id = c.get(row, sid)
                    service[id] = days { name -> c.get(row, c.col(name)) }
                    ends[id] = c.get(row, end)
                    canon[id] = id
                }
            }
            // stops
            val stopIdx = HashMap<String, Int>()
            /** Stations, entrances and path nodes (location_type 1–3): left out at the end unless a trip calls there. */
            val stations = HashSet<Int>()
            reader(stopsF).use { r ->
                val c = Text.Csv(r, freeText = "stop_desc")
                val id = c.col("stop_id"); val code = c.col("stop_code"); val name = c.col("stop_name")
                val la = c.col("stop_lat"); val lo = c.col("stop_lon"); val lt = c.col("location_type")
                while (true) {
                    val row = c.next() ?: break
                    val lat = Text.coord(c.get(row, la), box.s, box.n)
                    val lon = Text.coord(c.get(row, lo), box.w, box.e)
                    if (lat == null || lon == null) { t.badStops++; continue }
                    if (lt >= 0 && c.get(row, lt).trim() in NOT_STOPS) stations.add(t.stops.size)
                    stopIdx[c.get(row, id)] = t.stops.size
                    t.stops.add(Stop(c.get(row, id), c.get(row, code), Text.title(c.get(row, name)), lat, lon))
                }
            }
            log(tr("${t.stops.size} stops", "${t.stops.size} durak"))
            // routes (route_desc may contain the delimiter)
            val routeIdx = HashMap<String, Int>()
            reader(routesF).use { r ->
                val c = Text.Csv(r, freeText = "route_desc")
                val id = c.col("route_id"); val sn = c.col("route_short_name"); val ln = c.col("route_long_name")
                val code = c.col("route_code"); val type = c.col("route_type")
                while (true) {
                    val row = c.next() ?: break
                    if (busOnly && type >= 0) {
                        val rt = c.get(row, type).toIntOrNull()
                        if (rt != null && rt != 3 && rt != 11 && rt !in 700..799) continue
                    }
                    routeIdx[c.get(row, id)] = t.routes.size
                    t.routes.add(Route(c.get(row, id), c.get(row, sn), Text.title(c.get(row, ln)), if (code >= 0) c.get(row, code) else ""))
                }
            }
            log(tr("${t.routes.size} routes", "${t.routes.size} hat"))
            // trips
            class TripInfo(val route: Int, val days: Int, val headsign: String, val dir: Int, val shape: String, val service: String)
            val trips = HashMap<String, TripInfo>(200_000)
            val routeServices = HashMap<Int, MutableSet<String>>()
            reader(tripsF).use { r ->
                val c = Text.Csv(r, freeText = "trip_headsign")
                val id = c.col("trip_id"); val rid = c.col("route_id"); val sid = c.col("service_id")
                val hs = c.col("trip_headsign"); val dir = c.col("direction_id"); val shp = c.col("shape_id")
                while (true) {
                    val row = c.next() ?: break
                    val ri = routeIdx[c.get(row, rid)] ?: continue
                    val s = canon[c.get(row, sid)] ?: continue
                    val days = service[s] ?: continue
                    routeServices.getOrPut(ri) { HashSet(4) }.add(s)
                    trips[c.get(row, id)] = TripInfo(ri, days, if (hs >= 0) Text.title(c.get(row, hs)) else "", c.get(row, dir).toIntOrNull() ?: 0,
                        if (shp >= 0) c.get(row, shp).intern() else "", s)
                }
            }
            val day = today ?: java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val stale = staleServices(service, ends, routeServices, day)
            if (stale.isNotEmpty()) {
                val before = trips.size
                trips.values.removeAll { it.service in stale }
                log(tr("${before - trips.size} trips of ended timetables left out", "sona ermiş çizelgelerin ${before - trips.size} seferi atlandı"))
            }
            log(tr("${trips.size} trips", "${trips.size} sefer"))

            // stop_times: rows are grouped by trip; every trip becomes (pattern, days, start, duration)
            class Key(val route: Int, val stops: IntArray) {
                private val h = route * 31 + stops.contentHashCode()
                override fun hashCode() = h
                override fun equals(other: Any?) = other is Key && other.route == route && other.stops.contentEquals(stops)
            }
            val patIdx = HashMap<Key, Int>()
            val seqs = IntList(); val sts = IntList(); val times = IntList()
            var curTrip: String? = null

            fun finish() {
                val tid = curTrip ?: return
                curTrip = null
                val info = trips[tid]
                if (info == null || seqs.size < 2) { t.tripsSkipped++; seqs.clear(); sts.clear(); times.clear(); return }
                var sorted = true
                for (i in 1 until seqs.size) if (seqs[i] < seqs[i - 1]) { sorted = false; break }
                val order = if (sorted) IntArray(seqs.size) { it } else (0 until seqs.size).sortedBy { seqs[it] }.toIntArray()
                // stops the feed has no position for (-1) are left out of the sequence, but their times still count:
                // when only the first and last stop have times, a first stop without coordinates must not make the
                // trip start at its arrival time
                var known = 0
                for (k in order) if (sts[k] >= 0) known++
                if (known < 2) { t.tripsSkipped++; seqs.clear(); sts.clear(); times.clear(); return }
                val st = IntArray(known)
                var w = 0
                for (k in order) if (sts[k] >= 0) st[w++] = sts[k]
                var first = -1; var last = -1
                for (k in order) {
                    val tm = times[k]
                    if (tm >= 0) { if (first < 0) first = tm; last = tm }
                }
                if (first < 0) { t.tripsSkipped++; seqs.clear(); sts.clear(); times.clear(); return }
                val key = Key(info.route, st)
                val pi = patIdx.getOrPut(key) {
                    t.patterns.add(Pattern(info.route, st).also { it.dir = info.dir })
                    t.patterns.size - 1
                }
                val p = t.patterns[pi]
                // feeds without headsigns (or with the line's name as headsign in both directions): the last stop's name
                val hs = info.headsign.takeIf { it.isNotEmpty() && !it.equals(t.routes[info.route].long, true) } ?: t.stops[st.last()].name
                p.headsigns.merge(hs, 1, Int::plus)
                if (info.shape.isNotEmpty()) p.shapeIds.merge(info.shape, 1, Int::plus)
                p.trips.add(intArrayOf(info.days, first, (last - first).coerceAtLeast(0)))
                t.tripsRead++
                seqs.clear(); sts.clear(); times.clear()
            }

            fun readStopTimes(input: InputStream) {
                val r = input.bufferedReader(Charsets.UTF_8)
                val h = (r.readLine() ?: return).removePrefix("﻿")
                val d = if (h.count { it == ';' } > h.count { it == ',' }) ';' else ','
                val cols = Text.split(h, d).map { it.trim().lowercase() }
                val cT = cols.indexOf("trip_id"); val cS = cols.indexOf("stop_id"); val cQ = cols.indexOf("stop_sequence")
                val cA = cols.indexOf("arrival_time"); val cD = cols.indexOf("departure_time")
                if (cT < 0 || cS < 0) throw java.io.IOException(tr("stop_times has no trip_id or stop_id column", "stop_times dosyasında trip_id ya da stop_id sütunu yok"))
                var n = 0L
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.isBlank()) continue
                    val f = Text.split(line, d)
                    if (f.size <= maxOf(cT, cS)) continue
                    val tid = f[cT].trim()
                    if (tid != curTrip) {
                        finish()
                        curTrip = tid
                    }
                    // a stop without a usable position (-1) keeps its place in the order and its time
                    val si = stopIdx[f[cS].trim()] ?: -1
                    // without stop_sequence (or with a blank one) the file's order is the riding order
                    val q = if (cQ >= 0) f.getOrNull(cQ)?.trim()?.toIntOrNull() else null
                    seqs.add(q ?: if (seqs.size == 0) Int.MIN_VALUE / 2 else seqs[seqs.size - 1] + 1)
                    sts.add(si)
                    val tm = Text.time(f.getOrNull(cD) ?: "") ?: Text.time(f.getOrNull(cA) ?: "") ?: -1
                    times.add(tm)
                    if (++n % 500_000 == 0L) {
                        log(tr("stop times: ${n / 1000}k rows", "durak saatleri: ${n / 1000} bin satır"))
                        if (cancelled()) throw InterruptedException()
                    }
                }
                finish()
            }

            if (stopTimesF.name.endsWith(".zip", ignoreCase = true)) {
                ZipFile(stopTimesF).use { z ->
                    val e = z.entries().asSequence().firstOrNull { !it.isDirectory && it.name.contains("stop_times", true) }
                        ?: z.entries().asSequence().first { !it.isDirectory }
                    z.getInputStream(e).use { readStopTimes(it) }
                }
            } else stopTimesF.inputStream().use { readStopTimes(it) }
            log(tr("${t.tripsRead} trips in ${t.patterns.size} stop sequences", "${t.patterns.size} durak dizisinde ${t.tripsRead} sefer"))

            // stations and entrances no trip calls at are no stops (dead pins on the map and in search); a feed that runs
            // its trips through them (against the spec) keeps them
            if (stations.isNotEmpty()) {
                val used = BooleanArray(t.stops.size)
                for (p in t.patterns) for (s in p.stops) used[s] = true
                val drop = stations.filter { !used[it] }.toHashSet()
                if (drop.isNotEmpty()) {
                    val map = IntArray(t.stops.size)
                    val kept = ArrayList<Stop>(t.stops.size - drop.size)
                    t.stops.forEachIndexed { i, s -> if (i in drop) map[i] = -1 else { map[i] = kept.size; kept.add(s) } }
                    t.stops.clear(); t.stops.addAll(kept)
                    for (p in t.patterns) for (k in p.stops.indices) p.stops[k] = map[p.stops[k]]
                }
            }
            // distance along each pattern, for estimating times at the stops in between
            for (p in t.patterns) {
                val d = IntArray(p.stops.size)
                var acc = 0.0
                for (i in 1 until p.stops.size) {
                    val a = t.stops[p.stops[i - 1]]; val b = t.stops[p.stops[i]]
                    acc += Geo.dist(a.lat, a.lon, b.lat, b.lon)
                    d[i] = acc.toInt()
                }
                p.dists = d
            }
            return t
        }

        /**
         * Reads a GTFS shapes.txt (rows grouped by shape, in sequence order or not), keeping only the [wanted]
         * shape ids, simplified to about [tolDeg] degrees.
         */
        fun readGtfsShapes(
            input: InputStream, wanted: Set<String>, tolDeg: Double, into: HashMap<String, List<DoubleArray>>, cancelled: () -> Boolean = { false },
            /** Points outside this box (a stray 0,0 would draw a line across the world) are left out; null = keep all. */
            box: BBox? = null,
        ) {
            val r = input.bufferedReader(Charsets.UTF_8)
            val h = (r.readLine() ?: return).removePrefix("﻿")
            val d = if (h.count { it == ';' } > h.count { it == ',' }) ';' else ','
            val cols = Text.split(h, d).map { it.trim().lowercase() }
            val cI = cols.indexOf("shape_id"); val cA = cols.indexOf("shape_pt_lat"); val cO = cols.indexOf("shape_pt_lon")
            val cQ = cols.indexOf("shape_pt_sequence")
            if (cI < 0 || cA < 0 || cO < 0) return
            // per shape: (sequence, lon, lat) triples in one primitive list (millions of points, no small arrays)
            val pts = HashMap<String, DoubleList>()
            var n = 0L
            while (true) {
                val line = r.readLine() ?: break
                if (line.isBlank()) continue
                val f = Text.split(line, d)
                if (f.size <= maxOf(cI, cA, cO)) continue
                val id = f[cI].trim()
                if (id !in wanted) continue
                val la = f[cA].trim().toDoubleOrNull() ?: continue
                val lo = f[cO].trim().toDoubleOrNull() ?: continue
                if (!la.isFinite() || !lo.isFinite() || la !in -90.0..90.0 || lo !in -180.0..180.0) continue
                if (box != null && (la !in box.s..box.n || lo !in box.w..box.e)) continue
                val q = if (cQ >= 0) f.getOrNull(cQ)?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0 else n.toDouble()
                val l = pts.getOrPut(id) { DoubleList(256) }
                l.add(q); l.add(lo, la)
                if (++n % 200_000 == 0L && cancelled()) throw InterruptedException()
            }
            for ((id, l) in pts) {
                val k = l.size / 3
                if (k < 2) continue
                var sorted = true
                for (i in 1 until k) if (l[3 * i] < l[3 * (i - 1)]) { sorted = false; break }
                val order = if (sorted) IntArray(k) { it } else (0 until k).sortedBy { l[3 * it] }.toIntArray()
                val g = DoubleArray(k * 2)
                for (i in 0 until k) { val p = order[i]; g[2 * i] = l[3 * p + 1]; g[2 * i + 1] = l[3 * p + 2] }
                into[id] = listOf(Clip.simplify(g, tolDeg))
            }
        }

        /**
         * Streams the İETT route-shapes GeoJSON, keeping only the [wanted] route codes,
         * simplified to about [tolDeg] degrees.
         */
        fun readShapes(input: InputStream, wanted: Set<String>, tolDeg: Double, into: HashMap<String, List<DoubleArray>>, cancelled: () -> Boolean = { false }) {
            Json(input).use { j ->
                j.beginObject()
                while (j.hasNext()) {
                    if (j.nextName() != "features") { j.skipValue(); continue }
                    j.beginArray()
                    var n = 0
                    while (j.hasNext()) {
                        var code: String? = null
                        var coords: DoubleArray? = null
                        j.beginObject()
                        while (j.hasNext()) {
                            when (j.nextName()) {
                                "properties" -> {
                                    val p = j.readStringMap()
                                    code = p["GUZERGAH_KODU"] ?: p["guzergah_kodu"] ?: p["route_code"]
                                }
                                "geometry" -> coords = readLineCoords(j)
                                else -> j.skipValue()
                            }
                        }
                        j.endObject()
                        val c = code?.trim()
                        if (c != null && coords != null && c in wanted && coords.size >= 4) {
                            into[c] = listOf(Clip.simplify(coords, tolDeg))
                        }
                        if (++n % 200 == 0 && cancelled()) throw InterruptedException()
                    }
                    j.endArray()
                }
                j.endObject()
            }
        }

        /** LineString (or MultiLineString, joined) coordinates as lon/lat pairs. */
        private fun readLineCoords(j: Json): DoubleArray? {
            if (j.peek() == Json.Kind.NULL) { j.skipValue(); return null }
            val out = DoubleList(256)
            j.beginObject()
            while (j.hasNext()) {
                if (j.nextName() != "coordinates") { j.skipValue(); continue }
                readNested(j, out)
            }
            j.endObject()
            return out.toArray()
        }

        private fun readNested(j: Json, out: DoubleList) {
            j.beginArray()
            if (j.hasNext() && j.peek() == Json.Kind.NUMBER) {
                val x = j.nextDouble()
                j.hasNext()
                val y = j.nextDouble()
                while (j.hasNext()) j.skipValue() // altitude
                out.add(x, y)
            } else {
                while (j.hasNext()) readNested(j, out)
            }
            j.endArray()
        }
    }
}

class IntList(cap: Int = 64) {
    private var a = IntArray(cap)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun clear() { size = 0 }
    fun toArray(): IntArray = a.copyOf(size)
}
