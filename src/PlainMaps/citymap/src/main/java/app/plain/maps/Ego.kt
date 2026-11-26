package app.plain.maps

/**
 * Reads EGO's (Ankara) public timetable pages. ego.gov.tr/tr/hareketsaatleri lists every bus line;
 * posting a line number returns its name, trip time, departure times (weekdays, Saturday, Sunday)
 * and its stops in order with their EGO stop numbers. Stop positions come from OpenStreetMap
 * (bus stops there carry the EGO number as "ref").
 */
object Ego {
    const val PAGE = "https://www.ego.gov.tr/tr/hareketsaatleri"
    const val POST = "https://www.ego.gov.tr/tr/HareketSaatleri"
    const val NOTICES = "https://www.ego.gov.tr/tr/duyurular"

    class Line(
        val code: String,
        val name: String,
        val minutes: Int,
        /** departure times (seconds) for weekdays, Saturday, Sunday */
        val weekday: IntArray, val saturday: IntArray, val sunday: IntArray,
        /** (EGO stop number, stop name) in riding order */
        val stops: List<Pair<String, String>>,
    )

    class Notice(val refs: List<String>, val title: String, val date: String)

    /** HTML text: tags removed, entities decoded, spaces collapsed. */
    fun text(html: String): String {
        val t = html.replace(Regex("<[^>]+>"), " ")
            .replace(Regex("&#x([0-9a-fA-F]+);")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
            .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** The bus line numbers offered on the timetable page (value, label). */
    fun lineCodes(html: String): List<Pair<String, String>> {
        val a = html.indexOf("id=\"hat_liste_otobus\"")
        if (a < 0) return emptyList()
        val b = html.indexOf("</select>", a).let { if (it < 0) html.length else it }
        return Regex("<option value=\"([^\"]*)\"[^>]*>([^<]*)").findAll(html.substring(a, b))
            .map { it.groupValues[1].trim() to text(it.groupValues[2]) }
            .filter { it.first.isNotEmpty() && it.first != "0" }
            .toList()
    }

    private fun clock(s: String): Int? {
        val m = Regex("(\\d{1,2}):(\\d{2})").find(s) ?: return null
        return m.groupValues[1].toInt() * 3600 + m.groupValues[2].toInt() * 60
    }

    /** One line's page, or null if it has no stops. */
    fun parseLine(code: String, html: String): Line? {
        fun kv(key: String): String? {
            val m = Regex("<td class=\"key\">\\s*${Regex.escape(key)}\\s*</td>\\s*<td[^>]*>[^<]*</td>\\s*<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).find(html)
            return m?.let { text(it.groupValues[1]) }
        }
        val name = kv("Hat Adı") ?: ""
        val minutes = kv("Süresi")?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() } ?: 0
        // the three time columns: weekdays, Saturday, Sunday
        val cols = ArrayList<IntArray>()
        val ta = html.indexOf("class=\"hs-table\"")
        if (ta >= 0) {
            val tb = html.indexOf("</table>", ta)
            for (m in Regex("<td class=\"hs-times\">(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(html.substring(ta, if (tb < 0) html.length else tb))) {
                cols.add(m.groupValues[1].split("<br").mapNotNull { clock(it) }.toIntArray())
            }
        }
        // stops
        val stops = ArrayList<Pair<String, String>>()
        val ra = html.indexOf("route-table")
        if (ra >= 0) {
            val rb = html.indexOf("</table>", ra)
            for (tr in Regex("<tr>(.*?)</tr>", RegexOption.DOT_MATCHES_ALL).findAll(html.substring(ra, if (rb < 0) html.length else rb))) {
                val tds = Regex("<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL).findAll(tr.groupValues[1]).map { text(it.groupValues[1]) }.toList()
                if (tds.size >= 3 && tds[1].isNotEmpty()) stops.add(tds[1] to tds[2])
            }
        }
        if (stops.size < 2) return null
        return Line(code, name, minutes, cols.getOrElse(0) { IntArray(0) }, cols.getOrElse(1) { IntArray(0) }, cols.getOrElse(2) { IntArray(0) }, stops)
    }

    /** An OpenStreetMap bus stop: its EGO number (if tagged), name and position. */
    class OsmStop(val ref: String?, val name: String, val lat: Double, val lon: Double)

    class Placed(val pos: HashMap<String, DoubleArray>, val byRef: Int, val byName: Int, val guessed: Int)

    /**
     * Positions for EGO stop numbers: first OSM stops tagged with the number; then, for a stop still
     * missing, an OSM stop of the same name near the stretch between its placed neighbours on a line;
     * last, a point between those neighbours (spaced by stop order).
     */
    class Routed(val pos: HashMap<String, DoubleArray>, val shapes: HashMap<String, List<DoubleArray>>, val onRoad: Int, val straight: Int, val roadLines: Int)

    /**
     * Follows each line along the streets between its stops with known positions: its OSM route
     * shape when there is one, else the shortest drivable path. Stops still without a position are
     * spread along that path in stop order, so they sit on the street the bus uses. Lines with no
     * OSM shape get the joined paths as their shape. Without [roads], straight lines are used.
     */
    fun route(lines: List<Line>, known: Map<String, DoubleArray>, osmShapes: Map<String, List<DoubleArray>>, roads: RoadGraph?): Routed {
        val pos = HashMap(known)
        val shapes = HashMap<String, List<DoubleArray>>()
        var onRoad = 0; var straight = 0; var roadLines = 0
        for (l in lines) {
            val st = l.stops
            val own = osmShapes[l.code]
            val geom = DoubleList(256)
            var usedRoad = false
            var i = st.indexOfFirst { it.first in pos }
            while (i >= 0) {
                var j = i + 1
                while (j < st.size && st[j].first !in pos) j++
                if (j >= st.size) break
                val a = pos[st[i].first]!!; val b = pos[st[j].first]!!
                val d = Geo.dist(a[0], a[1], b[0], b[1])
                var seg: DoubleArray? = null
                // the line's own OSM shape, if both stops lie near it in riding order
                if (own != null) for (part in own) {
                    val ia = Poly.nearestVertex(part, a[0], a[1]); val ib = Poly.nearestVertex(part, b[0], b[1])
                    if (Geo.dist(a[0], a[1], part[2 * ia + 1], part[2 * ia]) > 150 || Geo.dist(b[0], b[1], part[2 * ib + 1], part[2 * ib]) > 150) continue
                    if (ia < ib) { seg = part.copyOfRange(2 * ia, 2 * ib + 2); break }
                    if (ib < ia) {
                        val r = part.copyOfRange(2 * ib, 2 * ia + 2)
                        seg = DoubleArray(r.size).also { o -> for (k in 0 until r.size / 2) { o[2 * k] = r[r.size - 2 - 2 * k]; o[2 * k + 1] = r[r.size - 1 - 2 * k] } }
                        break
                    }
                }
                // else the streets
                if (seg == null && roads != null && d > 30) {
                    val p = roads.path(roads.nearest(a[0], a[1]), roads.nearest(b[0], b[1]), 3 * d + 400)
                    if (p != null) seg = doubleArrayOf(a[1], a[0]) + p + doubleArrayOf(b[1], b[0])
                }
                val onStreets = seg != null
                if (onStreets) usedRoad = true
                val s = seg ?: doubleArrayOf(a[1], a[0], b[1], b[0])
                // the stops between, spread along the path
                for (k in i + 1 until j) {
                    val no = st[k].first
                    if (no in pos) continue
                    pos[no] = Poly.at(s, (k - i).toDouble() / (j - i))
                    if (onStreets) onRoad++ else straight++
                }
                var q = if (geom.size > 0) 2 else 0
                while (q < s.size) { geom.add(s[q], s[q + 1]); q += 2 }
                i = j
            }
            if (own == null && geom.size >= 4) {
                shapes[l.code] = listOf(Clip.simplify(geom.toArray(), 1.2e-5))
                if (usedRoad) roadLines++
            }
        }
        return Routed(pos, shapes, onRoad, straight, roadLines)
    }

    /** [interpolate] = false leaves stops between known ones to [route]. */
    fun place(lines: List<Line>, osm: List<OsmStop>, interpolate: Boolean = true): Placed {
        val pos = HashMap<String, DoubleArray>()
        for (s in osm) s.ref?.split(';')?.forEach { r -> val k = r.trim(); if (k.isNotEmpty() && k !in pos) pos[k] = doubleArrayOf(s.lat, s.lon) }
        val byRef = pos.size
        val byName = HashMap<String, MutableList<OsmStop>>()
        for (s in osm) if (s.name.isNotEmpty()) byName.getOrPut(Text.norm(s.name)) { ArrayList() }.add(s)
        var named = 0
        var guessed = 0
        repeat(3) { pass ->
            for (l in lines) {
                val st = l.stops
                for (k in st.indices) {
                    val no = st[k].first
                    if (no in pos) continue
                    var p = k - 1; while (p >= 0 && st[p].first !in pos) p--
                    var n = k + 1; while (n < st.size && st[n].first !in pos) n++
                    val a = if (p >= 0) pos[st[p].first] else null
                    val b = if (n < st.size) pos[st[n].first] else null
                    if (a == null && b == null) continue
                    val mid = if (a != null && b != null) doubleArrayOf((a[0] + b[0]) / 2, (a[1] + b[1]) / 2) else (a ?: b)!!
                    val reach = (if (a != null && b != null) Geo.dist(a[0], a[1], b[0], b[1]) / 2 else 0.0) + 700.0
                    val cand = byName[Text.norm(st[k].second)]?.filter { Geo.dist(mid[0], mid[1], it.lat, it.lon) <= reach }
                        ?.minByOrNull { Geo.dist(mid[0], mid[1], it.lat, it.lon) }
                    if (cand != null) { pos[no] = doubleArrayOf(cand.lat, cand.lon); named++; continue }
                    // only after the name searches: between the neighbours, by stop order
                    if (interpolate && pass == 2 && a != null && b != null) {
                        val f = (k - p).toDouble() / (n - p)
                        pos[no] = doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f)
                        guessed++
                    }
                }
            }
        }
        return Placed(pos, byRef, named, guessed)
    }

    /** A line as text, to keep it between sync steps. */
    fun encode(l: Line): String = buildString {
        append(l.code).append('\n').append(l.name).append('\n').append(l.minutes).append('\n')
        for (a in listOf(l.weekday, l.saturday, l.sunday)) append(a.joinToString(",")).append('\n')
        for ((n, nm) in l.stops) append(n).append('\t').append(nm).append('\n')
    }

    fun decode(s: String): Line? {
        val p = s.split('\n')
        if (p.size < 6) return null
        fun ints(x: String) = if (x.isBlank()) IntArray(0) else x.split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        val stops = p.drop(6).filter { '\t' in it }.map { it.substringBefore('\t') to it.substringAfter('\t') }
        return Line(p[0], p[1], p[2].toIntOrNull() ?: 0, ints(p[3]), ints(p[4]), ints(p[5]), stops)
    }

    /**
     * The box (lat/lon) that EGO's network spans: every placed stop that lies within 15 km of a
     * placed neighbour on its line. A stop number that also exists elsewhere (a wrong match far away)
     * has no such neighbour, so it can't stretch the map. Null if nothing is placed.
     */
    fun extent(lines: List<Line>, pos: Map<String, DoubleArray>): BBox? {
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        var any = false
        for (l in lines) {
            val pts = l.stops.mapNotNull { pos[it.first] }
            for (i in pts.indices) {
                val p = pts[i]
                val near = (i > 0 && Geo.dist(p[0], p[1], pts[i - 1][0], pts[i - 1][1]) < 15_000) ||
                    (i + 1 < pts.size && Geo.dist(p[0], p[1], pts[i + 1][0], pts[i + 1][1]) < 15_000)
                if (!near) continue
                any = true
                if (p[0] < s) s = p[0]; if (p[0] > n) n = p[0]; if (p[1] < w) w = p[1]; if (p[1] > e) e = p[1]
            }
        }
        return if (any) BBox(w, s, e, n) else null
    }

    /** Weekday / Saturday / Sunday day masks. */
    const val WEEKDAYS = 0x1F
    const val SATURDAY = 1 shl 5
    const val SUNDAY = 1 shl 6

    /** (day mask, departure second) of every trip of a line. */
    fun departures(l: Line): List<IntArray> =
        l.weekday.map { intArrayOf(WEEKDAYS, it) } + l.saturday.map { intArrayOf(SATURDAY, it) } + l.sunday.map { intArrayOf(SUNDAY, it) }

    /**
     * The EGO network in the shape the database writer takes (the same as the İETT feed):
     * one route per EGO line code, grouped into lines by their number ("102-1", "102-2" → line 102).
     * Stops without a position are left out of their lines. [shapes] are OSM bus routes by code.
     */
    fun toTransit(lines: List<Line>, pos: Map<String, DoubleArray>, shapes: Map<String, List<DoubleArray>>): Transit {
        val t = Transit()
        val stopIdx = HashMap<String, Int>()
        for (l in lines) {
            val idx = IntList(l.stops.size)
            for ((no, name) in l.stops) {
                val p = pos[no] ?: continue
                val i = stopIdx.getOrPut(no) { t.stops.add(Transit.Stop(no, no, Text.title(name), p[0], p[1])); t.stops.size - 1 }
                if (idx.size == 0 || idx[idx.size - 1] != i) idx.add(i)
            }
            if (idx.size < 2) { t.tripsSkipped++; continue }
            val b = base(l.code)
            t.routes.add(Transit.Route(l.code, b, Text.title(l.name), l.code))
            val p = Transit.Pattern(t.routes.size - 1, idx.toArray())
            // "-2", "-4" … are usually the way back
            p.dir = l.code.substringAfter('-', "").toIntOrNull()?.let { if (it % 2 == 0) 1 else 0 } ?: 0
            val head = Text.title(destination(l.name, l.stops.last().second)) + if (l.code != b) " (${l.code})" else ""
            p.headsigns[head] = 1
            val dur = l.minutes * 60
            for (d in departures(l)) p.trips.add(intArrayOf(d[0], d[1], dur))
            val dists = IntArray(p.stops.size)
            var acc = 0.0
            for (i in 1 until p.stops.size) {
                val a = t.stops[p.stops[i - 1]]; val c = t.stops[p.stops[i]]
                acc += Geo.dist(a.lat, a.lon, c.lat, c.lon)
                dists[i] = acc.toInt()
            }
            p.dists = dists
            t.patterns.add(p)
            t.tripsRead += p.trips.size
            shapes[l.code]?.let { t.shapes[l.code] = it }
        }
        return t
    }

    /** The line this code belongs to: "102-1" → "102" (EGO numbers directions and variants with a suffix). */
    fun base(code: String) = code.substringBefore('-').trim().ifEmpty { code }

    /** The destination from a line name like "AKKÖPRÜ-ATILIM ÜNİVERSİTESİ". */
    fun destination(name: String, lastStop: String): String =
        name.split('-').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: lastStop

    /**
     * EGO's announcement list: title, date and the line numbers the title starts with
     * ("434-458-459 …" → 434, 458, 459; "102-1 …" → 102-1). Stop notices give "durak:NNNNN".
     */
    fun parseNotices(html: String): List<Notice> {
        val out = ArrayList<Notice>()
        for (m in Regex("<div class=\"duyuru-info\">(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
            val block = m.groupValues[1]
            val title = Regex("<div class=\"title\">(.*?)</div>", RegexOption.DOT_MATCHES_ALL).find(block)?.let { text(it.groupValues[1]) } ?: continue
            val date = Regex("<span class=\"date\">(.*?)</span>", RegexOption.DOT_MATCHES_ALL).find(block)?.let { text(it.groupValues[1]) } ?: ""
            out.add(Notice(refsOf(title), title, date))
        }
        return out
    }

    fun refsOf(title: String): List<String> {
        val t = title.trim()
        Regex("^(\\d{4,6})\\s+NUMARALI", RegexOption.IGNORE_CASE).find(t)?.let { return listOf("durak:" + it.groupValues[1]) }
        val lead = Regex("^[\\d\\s,/\\-–]+").find(t)?.value ?: return emptyList()
        val parts = lead.split(Regex("[\\s,/–]+")).filter { it.isNotEmpty() }
        val refs = ArrayList<String>()
        for (p in parts) {
            val sub = p.split('-').filter { it.isNotEmpty() }
            var i = 0
            while (i < sub.size) {
                val a = sub[i]
                // a 1–2 digit piece after a line number is its variant ("102-1")
                if (i + 1 < sub.size && sub[i + 1].length <= 2 && a.length >= 2) { refs.add("$a-${sub[i + 1]}"); refs.add(a); i += 2 }
                else { if (a.length >= 2) refs.add(a); i++ }
            }
        }
        return refs.distinct()
    }
}
