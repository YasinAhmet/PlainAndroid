package app.plain.maps

import app.plain.lang.tr
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One full refresh of the offline data, over Wi-Fi only:
 *  1. İETT bus lines, stops, timetables and route shapes (İBB open data)
 *  2. the city core: one weekly OpenStreetMap extract (map, places, metro/tram/ferry lines)
 *  3. the outer districts: Overpass queries, best effort (a busy server only leaves them out)
 *  4. cut everything into map cells, then swap the new database in.
 * The work database keeps finished steps, so an interrupted sync continues where it stopped.
 */
class Sync(
    private val ctx: Context,
    private val cancel: AtomicBoolean,
    private val report: (Float, String) -> Unit,
) {
    companion object {
        /** Istanbul province (İETT runs buses across all of it). */
        val AREA get() = City.area
        const val VERSION = "2"
        /** Weekly OpenStreetMap extract of the city core (BBBike), 45 MB. */
        /** The outer districts come from Overpass in squares of this size (degrees). */
        const val OUT_STEP = 0.25
        val OVERPASS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
        const val CKAN = "https://data.ibb.gov.tr/api/3/action/package_show?id="
        const val GTFS_PKG = "iett-gtfs-verisi"
        const val SHAPES_PKG = "iett-hat-guzergahlari"

        fun dataFile(ctx: Context) = File(ctx.filesDir, "data.db")

        private fun bbox(b: BBox) = "${b.s},${b.w},${b.n},${b.e}"

        /** Map and places of one outer square (the same elements as [Filters] keeps from the extract). */
        fun outskirtsQuery(b: BBox) = """
            [out:json][timeout:180][bbox:${bbox(b)}];
            (
              way["highway"~"^(motorway|trunk|primary|secondary|tertiary|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link|unclassified|residential|living_street|road|pedestrian|steps|path|cycleway)$"];
              way["highway"="service"]["service"!~"^(parking_aisle|driveway|drive-through)$"];
              way["highway"="footway"]["footway"!~"^(sidewalk|crossing)$"];
              way["railway"~"^(rail|subway|tram|light_rail|funicular|narrow_gauge|monorail)$"];
              way["natural"~"^(coastline|water|wood)$"];
              way["landuse"~"^(forest|cemetery|reservoir|basin|recreation_ground|village_green)$"];
              way["leisure"~"^(park|garden)$"];
            );
            out tags geom qt;
            (
              rel["type"="multipolygon"]["natural"~"^(water|wood)$"];
              rel["type"="multipolygon"]["landuse"~"^(forest|cemetery|reservoir|basin|recreation_ground)$"];
              rel["type"="multipolygon"]["leisure"~"^(park|garden)$"];
            );
            out body geom qt;
            (
              nwr["shop"];
              nwr["amenity"~"^(restaurant|cafe|fast_food|bar|pub|ice_cream|food_court|nightclub|pharmacy|hospital|clinic|doctors|dentist|veterinary|bank|atm|bureau_de_change|bus_station|ferry_terminal|fuel|taxi|place_of_worship|school|university|college|kindergarten|library|post_office|police|fire_station|townhall|courthouse|community_centre|toilets|marketplace|cinema|theatre|arts_centre)$"];
              nwr["tourism"~"^(museum|attraction|hotel|hostel|guest_house|motel|apartment|viewpoint|gallery|information|zoo|theme_park|aquarium)$"];
              nwr["leisure"~"^(park|garden|sports_centre|stadium|fitness_centre|swimming_pool|playground|water_park|marina)$"]["name"];
              nwr["healthcare"]["name"];
              node["place"~"^(city|town|suburb|borough|quarter|neighbourhood|village|hamlet|island)$"]["name"];
              nwr["railway"="station"]["name"];
            )->.p;
            node.p;
            out body qt;
            (way.p; rel.p;);
            out tags center qt;
        """.trimIndent()

        /** The province minus the core, as squares of at most [OUT_STEP]. */
        fun outskirts(core: BBox, all: BBox = AREA): List<BBox> {
            val bands = listOf(
                BBox(all.w, all.s, core.w, all.n), BBox(core.e, all.s, all.e, all.n),
                BBox(core.w, core.n, core.e, all.n), BBox(core.w, all.s, core.e, core.s),
            ).filter { it.e - it.w > 1e-6 && it.n - it.s > 1e-6 }
            val out = ArrayList<BBox>()
            for (b in bands) {
                val nx = kotlin.math.ceil((b.e - b.w) / OUT_STEP - 1e-9).toInt().coerceAtLeast(1)
                val ny = kotlin.math.ceil((b.n - b.s) / OUT_STEP - 1e-9).toInt().coerceAtLeast(1)
                for (j in 0 until ny) for (i in 0 until nx) {
                    out.add(BBox(b.w + (b.e - b.w) * i / nx, b.s + (b.n - b.s) * j / ny, b.w + (b.e - b.w) * (i + 1) / nx, b.s + (b.n - b.s) * (j + 1) / ny))
                }
            }
            return out
        }
    }

    private val dir = File(ctx.filesDir, "sync").apply { mkdirs() }
    private val dl = File(ctx.cacheDir, "sync").apply { mkdirs() }
    private val newF = File(dir, "data-new.db")
    private val workF = File(dir, "work.db")
    private lateinit var net: Net.Net0
    private lateinit var out: SQLiteDatabase
    private lateinit var work: SQLiteDatabase

    private fun checkCancel() { if (cancel.get()) throw InterruptedException() }

    private fun say(f: Float, s: String) {
        lastF = f
        report(f.coerceIn(0f, 1f), s)
    }

    private fun sleep(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) { checkCancel(); Thread.sleep(250) }
    }

    /** Waits (up to 10 minutes) for Wi-Fi; never falls back to mobile data. */
    private fun wifi(): android.net.Network {
        var waited = 0
        while (true) {
            Net.wifi(ctx)?.let { return it }
            if (waited == 0) say(lastF, tr("Waiting for Wi-Fi…", "Wi-Fi bekleniyor…"))
            if (waited > 600) throw IOException(tr("No Wi-Fi. Sync only runs on Wi-Fi.", "Wi-Fi yok. Eşitleme yalnızca Wi-Fi ile çalışır."))
            sleep(3000); waited += 3
        }
    }

    @Volatile private var lastF = 0f

    /**
     * The map's area: the city's box, grown to cover every stop of its transit
     * (Istanbul: İETT's stops; Ankara: EGO's stops, worked out while the extract is read).
     */
    private var area: BBox = City.area

    private fun saveArea() = work.execSQL("INSERT OR REPLACE INTO wmeta VALUES('area', ?)", arrayOf(area.encode()))

    /** The union of two boxes, clamped to the city's search area. */
    private fun grow(a: BBox, b: BBox): BBox {
        val c = City.searchArea
        return BBox(maxOf(c.w, minOf(a.w, b.w)), maxOf(c.s, minOf(a.s, b.s)), minOf(c.e, maxOf(a.e, b.e)), minOf(c.n, maxOf(a.n, b.n)))
    }

    fun run() {
        net = Net.Net0(wifi())
        openDbs()
        var missing = 0
        try {
            if (City.ankara) {
                // EGO's lines first (they decide how big the map is), then the map, then the stops' positions
                if (!done("egolines")) { egoLines(); mark("egolines") }
                core()
                if (!done("transit")) { egoTransit(); mark("transit") }
            } else {
                if (!done("transit")) { transit(); mark("transit") }
                if (!done("area")) { fitToStops(); mark("area") }
                core()
            }
            missing = if (City.mapCoversArea) 0 else outskirts()
            build(missing)
        } finally {
            if (::out.isInitialized && out.isOpen) out.close()
            if (::work.isInitialized && work.isOpen) work.close()
        }
        finish()
        if (missing > 0) say(1f, tr("Done. $missing outer squares could not be loaded (OpenStreetMap server busy); the next sync tries them again.",
            "Bitti. $missing dış kare yüklenemedi (OpenStreetMap sunucusu meşgul); bir sonraki eşitleme yeniden dener."))
    }

    // ------------------------------------------------------------------ databases
    private fun openDbs() {
        var resume = workF.exists() && newF.exists()
        if (resume) {
            try {
                val w = SQLiteDatabase.openDatabase(workF.path, null, SQLiteDatabase.OPEN_READWRITE)
                val ok = w.rawQuery("SELECT v FROM wmeta WHERE k='version'", null).use { c -> c.moveToFirst() && c.getString(0) == VERSION }
                val age = w.rawQuery("SELECT v FROM wmeta WHERE k='started'", null).use { c -> if (c.moveToFirst()) c.getString(0).toLong() else 0L }
                w.close()
                resume = ok && System.currentTimeMillis() - age < 3L * 86_400_000
            } catch (_: Exception) {
                resume = false
            }
        }
        if (!resume) {
            newF.delete(); workF.delete()
            File(newF.path + "-journal").delete(); File(workF.path + "-journal").delete()
        }
        out = SQLiteDatabase.openOrCreateDatabase(newF, null)
        work = SQLiteDatabase.openOrCreateDatabase(workF, null)
        for (d in listOf(out, work)) {
            d.rawQuery("PRAGMA journal_mode=OFF", null).close()
            d.execSQL("PRAGMA synchronous=OFF")
        }
        if (!resume) {
            Store.SCHEMA.forEach { out.execSQL(it) }
            work.execSQL("CREATE TABLE wmeta(k TEXT PRIMARY KEY, v TEXT)")
            work.execSQL("CREATE TABLE done(k TEXT PRIMARY KEY)")
            work.execSQL("CREATE TABLE seen(id INTEGER PRIMARY KEY)")
            work.execSQL("CREATE TABLE coast(id INTEGER PRIMARY KEY, data BLOB)")
            work.execSQL("CREATE TABLE piece(lvl INTEGER, x INTEGER, y INTEGER, k INTEGER, data BLOB)")
            work.execSQL("INSERT INTO wmeta VALUES('version', '$VERSION')")
            work.execSQL("INSERT INTO wmeta VALUES('started', '${System.currentTimeMillis()}')")
        } else {
            say(0f, tr("Continuing the last sync…", "Son eşitlemeye devam ediliyor…"))
            work.rawQuery("SELECT v FROM wmeta WHERE k='area'", null).use { c -> if (c.moveToFirst()) BBox.decode(c.getString(0))?.let { area = it } }
        }
    }

    /** Istanbul: grow the map's box to every İETT stop (plus about 3 km). */
    private fun fitToStops() {
        out.rawQuery("SELECT min(lat),max(lat),min(lon),max(lon) FROM stop WHERE mode=0", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) area = grow(City.area, BBox(c.getDouble(2), c.getDouble(0), c.getDouble(3), c.getDouble(1)).pad(3000.0))
        }
        saveArea()
    }

    private fun done(k: String) = work.rawQuery("SELECT 1 FROM done WHERE k=?", arrayOf(k)).use { it.moveToFirst() }
    private fun mark(k: String) = work.execSQL("INSERT OR IGNORE INTO done VALUES(?)", arrayOf(k))

    private fun setMeta(k: String, v: String) = out.execSQL("INSERT OR REPLACE INTO meta VALUES(?,?)", arrayOf(k, v))

    private inline fun <T> tx(db: SQLiteDatabase, block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    // ------------------------------------------------------------------ 1. İETT
    private class Res(val name: String, val format: String, val url: String, val stamp: String)

    private fun ckan(pkg: String): List<Res> {
        val txt = retry(tr("İBB catalogue", "İBB kataloğu")) { Net.text(net.n, CKAN + pkg) }
        val out = ArrayList<Res>()
        Json(txt.byteInputStream()).use { j ->
            j.beginObject()
            while (j.hasNext()) {
                if (j.nextName() != "result") { j.skipValue(); continue }
                j.beginObject()
                while (j.hasNext()) {
                    if (j.nextName() != "resources") { j.skipValue(); continue }
                    j.beginArray()
                    while (j.hasNext()) {
                        val m = j.readStringMap()
                        out.add(Res((m["name"] ?: "").lowercase().trim(), (m["format"] ?: "").lowercase(), m["url"] ?: "", (m["id"] ?: "") + "@" + (m["last_modified"] ?: m["created"] ?: "")))
                    }
                    j.endArray()
                }
                j.endObject()
            }
            j.endObject()
        }
        return out
    }

    private fun transit() {
        say(0.01f, tr("İETT: checking the open-data catalogue", "İETT: açık veri kataloğu denetleniyor"))
        val g = ckan(GTFS_PKG)
        val s = ckan(SHAPES_PKG)
        fun pick(name: String, fmt: String? = null) = g.firstOrNull { it.name == name && (fmt == null || it.format == fmt) }
        val stops = pick("stops") ?: throw IOException(tr("İETT feed has no stops file", "İETT verisinde stops dosyası yok"))
        val routes = pick("routes") ?: throw IOException(tr("İETT feed has no routes file", "İETT verisinde routes dosyası yok"))
        val trips = pick("trips") ?: throw IOException(tr("İETT feed has no trips file", "İETT verisinde trips dosyası yok"))
        val cal = pick("calendar") ?: throw IOException(tr("İETT feed has no calendar file", "İETT verisinde calendar dosyası yok"))
        // the zipped stop_times is complete; the CSV copy is cut off at a spreadsheet's row limit
        val st = pick("stop_times", "zip") ?: g.firstOrNull { it.name.contains("stop_times") } ?: throw IOException(tr("İETT feed has no stop_times", "İETT verisinde stop_times yok"))
        val shp = s.firstOrNull { it.format.contains("json") || it.url.endsWith(".geojson") }
        val fp = listOf(stops, routes, trips, cal, st).joinToString("|") { it.stamp } + "|" + (shp?.stamp ?: "")

        val old = dataFile(ctx)
        if (old.exists() && oldMeta(old, "transit_fp") == fp && oldMeta(old, "version") == VERSION) {
            say(0.1f, tr("İETT: unchanged since the last sync, keeping it", "İETT: son eşitlemeden beri değişmemiş, korunuyor"))
            copyBus(old)
            setMeta("transit_fp", fp)
            oldMeta(old, "gtfs_date")?.let { setMeta("gtfs_date", it) }
            return
        }
        val files = listOf(stops to "stops.csv", routes to "routes.csv", trips to "trips.csv", cal to "calendar.csv",
            st to (if (st.format == "zip") "stop_times.zip" else "stop_times.csv")) + listOfNotNull(shp?.let { it to "shapes.geojson" })
        files.forEachIndexed { i, (r, name) ->
            val f = File(dl, name)
            retry(tr("İETT download", "İETT indirmesi")) {
                Net.download(net, r.url, f, { d, t ->
                    val part = if (t > 0) d.toFloat() / t else 0f
                    say(0.01f + 0.1f * (i + part) / files.size, tr("İETT: downloading $name (${d / 1048576} MB)", "İETT: $name indiriliyor (${d / 1048576} MB)"))
                }, { cancel.get() })
            }
        }
        say(0.11f, tr("İETT: reading timetables", "İETT: saatler okunuyor"))
        val t = Transit.read(File(dl, "stops.csv"), File(dl, "routes.csv"), File(dl, "trips.csv"), File(dl, "calendar.csv"),
            File(dl, files[4].second), { say(0.12f, "İETT: $it") }, { cancel.get() })
        if (t.patterns.isEmpty()) throw IOException(tr("İETT feed has no usable trips", "İETT verisinde kullanılabilir sefer yok"))
        val wanted = t.patterns.map { t.routes[it.route].code }.filter { it.isNotEmpty() }.toHashSet()
        if (shp != null) {
            say(0.14f, tr("İETT: reading route shapes", "İETT: güzergâh çizimleri okunuyor"))
            File(dl, "shapes.geojson").inputStream().buffered(1 shl 16).use { Transit.readShapes(it, wanted, 1.2e-5, t.shapes) { cancel.get() } }
        }
        say(0.16f, tr("İETT: saving ${t.patterns.size} routes", "İETT: ${t.patterns.size} güzergâh kaydediliyor"))
        writeBus(t)
        setMeta("transit_fp", fp)
        setMeta("gtfs_date", st.stamp.substringAfter('@').take(10))
        dl.listFiles()?.forEach { it.delete() }
    }

    private fun oldMeta(f: File, k: String): String? = try {
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }
    } catch (_: Exception) { null }

    private fun clearBus() {
        out.execSQL("DELETE FROM stop WHERE mode=0")
        out.execSQL("DELETE FROM line WHERE mode=0")
        out.execSQL("DELETE FROM variant WHERE id<${Store.RAIL_VAR0}")
        out.execSQL("DELETE FROM trip")
        out.execSQL("DELETE FROM stop_variant WHERE variant<${Store.RAIL_VAR0}")
        out.execSQL("DELETE FROM shape WHERE id<${Store.RAIL_VAR0}")
    }

    private fun copyBus(old: File) {
        clearBus()
        out.execSQL("ATTACH DATABASE ? AS o", arrayOf(old.path))
        try {
            tx(out) {
                out.execSQL("INSERT INTO stop SELECT * FROM o.stop WHERE mode=0")
                out.execSQL("INSERT INTO line SELECT * FROM o.line WHERE mode=0")
                out.execSQL("INSERT INTO variant SELECT * FROM o.variant WHERE id<${Store.RAIL_VAR0}")
                out.execSQL("INSERT INTO trip SELECT * FROM o.trip")
                out.execSQL("INSERT INTO stop_variant SELECT * FROM o.stop_variant WHERE variant<${Store.RAIL_VAR0}")
                out.execSQL("INSERT INTO shape SELECT * FROM o.shape WHERE id<${Store.RAIL_VAR0}")
            }
        } finally {
            out.execSQL("DETACH DATABASE o")
        }
    }

    private fun writeBus(t: Transit) {
        clearBus()
        tx(out) {
            val sStop = out.compileStatement("INSERT INTO stop VALUES(?,?,?,?,?,?,0)")
            t.stops.forEachIndexed { i, s ->
                sStop.bindLong(1, i + 1L); sStop.bindString(2, s.code); sStop.bindString(3, s.name)
                sStop.bindString(4, Text.norm(s.name)); sStop.bindDouble(5, s.lat); sStop.bindDouble(6, s.lon)
                sStop.executeInsert()
            }
            // one line per public line number; its name is the one of its busiest variant
            val byRef = t.patterns.groupBy { t.routes[it.route].short.ifEmpty { "?" } }
            val lineId = HashMap<String, Int>()
            val sLine = out.compileStatement("INSERT INTO line VALUES(?,?,?,?,0,?)")
            var li = 0
            for ((ref, ps) in byRef.toSortedMap(compareBy({ it.length }, { it }))) {
                val name = t.routes[ps.maxBy { it.trips.size }.route].long
                lineId[ref] = ++li
                sLine.bindLong(1, li.toLong()); sLine.bindString(2, ref); sLine.bindString(3, name)
                sLine.bindString(4, Text.norm("$ref $name")); sLine.bindLong(5, Mode.defaultColor(Mode.BUS).toLong())
                sLine.executeInsert()
            }
            val shapeId = HashMap<String, Int>()
            val sShape = out.compileStatement("INSERT INTO shape VALUES(?,?)")
            val sVar = out.compileStatement("INSERT INTO variant VALUES(?,?,?,?,?,?,?,?,?)")
            val sTrip = out.compileStatement("INSERT INTO trip VALUES(?,?,?,?)")
            val sSv = out.compileStatement("INSERT INTO stop_variant VALUES(?,?,?)")
            t.patterns.forEachIndexed { pi, p ->
                val r = t.routes[p.route]
                val vid = pi + 1L
                val sid = if (r.code.isNotEmpty() && t.shapes.containsKey(r.code)) shapeId.getOrPut(r.code) {
                    val id = shapeId.size + 1
                    sShape.bindLong(1, id.toLong()); sShape.bindBlob(2, Codec.shape(t.shapes[r.code]!!)); sShape.executeInsert()
                    id
                } else 0
                val stopIds = IntArray(p.stops.size) { p.stops[it] + 1 }
                sVar.bindLong(1, vid); sVar.bindLong(2, lineId[r.short.ifEmpty { "?" }]!!.toLong()); sVar.bindLong(3, p.dir.toLong())
                sVar.bindString(4, p.headsign()); sVar.bindString(5, r.code); sVar.bindLong(6, sid.toLong())
                sVar.bindBlob(7, Codec.ints(stopIds)); sVar.bindBlob(8, Codec.ints(p.dists)); sVar.bindLong(9, p.trips.size.toLong())
                sVar.executeInsert()
                for (tp in p.trips) {
                    sTrip.bindLong(1, vid); sTrip.bindLong(2, tp[0].toLong()); sTrip.bindLong(3, tp[1].toLong()); sTrip.bindLong(4, tp[2].toLong())
                    sTrip.executeInsert()
                }
                stopIds.forEachIndexed { k, s ->
                    sSv.bindLong(1, s.toLong()); sSv.bindLong(2, vid); sSv.bindLong(3, k.toLong()); sSv.executeInsert()
                }
            }
        }
    }

    // ------------------------------------------------------------------ 1b. EGO (Ankara)
    private fun egoLines() {
        say(0.01f, tr("EGO: reading the line list", "EGO: hat listesi okunuyor"))
        val codes = retry(tr("EGO line list", "EGO hat listesi")) { EgoFetch.lineCodes(net) }
        val lines = EgoFetch.lines(net, codes, cancel) { n -> if (n % 10 == 0 || n == codes.size) say(0.01f + 0.18f * n / codes.size, tr("EGO: timetables, line $n of ${codes.size}", "EGO: saatler, hat $n / ${codes.size}")) }
        work.execSQL("CREATE TABLE IF NOT EXISTS egoline(code TEXT PRIMARY KEY, data TEXT)")
        tx(work) {
            work.execSQL("DELETE FROM egoline")
            for (l in lines) work.execSQL("INSERT OR REPLACE INTO egoline VALUES(?,?)", arrayOf(l.code, Ego.encode(l)))
        }
    }

    private fun storedEgoLines(): List<Ego.Line> {
        val out = ArrayList<Ego.Line>()
        work.rawQuery("SELECT data FROM egoline", null).use { c -> while (c.moveToNext()) Ego.decode(c.getString(0))?.let { out.add(it) } }
        return out
    }

    private fun egoTransit() {
        val lines = storedEgoLines()
        if (lines.isEmpty()) throw IOException(tr("EGO's lines were not read", "EGO hatları okunamadı"))
        say(0.84f, tr("EGO: placing the stops on the map", "EGO: duraklar haritaya yerleştiriliyor"))
        val osm = ArrayList<Ego.OsmStop>()
        work.rawQuery("SELECT ref,name,lat,lon FROM osmstop", null).use { c ->
            while (c.moveToNext()) osm.add(Ego.OsmStop(c.getString(0), c.getString(1) ?: "", c.getDouble(2), c.getDouble(3)))
        }
        val shapes = HashMap<String, List<DoubleArray>>()
        work.rawQuery("SELECT ref,data FROM osmroute", null).use { c ->
            while (c.moveToNext()) {
                // a ref may hold several codes ("102-1;102-2"); the first route per code wins
                val parts = Codec.shape(c.getBlob(1)).map { Clip.simplify(it, 1.2e-5) }
                for (r in (c.getString(0) ?: "").split(';')) shapes.putIfAbsent(r.trim(), parts)
            }
        }
        val placed = Ego.place(lines, osm, interpolate = false)
        say(0.86f, tr("EGO: following the lines along the streets", "EGO: hatlar sokaklar boyunca izleniyor"))
        val ways = ArrayList<DoubleArray>()
        work.rawQuery("SELECT data FROM road", null).use { c -> while (c.moveToNext()) ways.addAll(Codec.shape(c.getBlob(0))) }
        val roads = if (ways.isEmpty()) null else RoadGraph(ways)
        ways.clear()
        val routed = Ego.route(lines, placed.pos, shapes, roads)
        val t = Ego.toTransit(lines, routed.pos, shapes + routed.shapes)
        if (t.patterns.isEmpty()) throw IOException(tr("No EGO line could be placed on the map", "Hiçbir EGO hattı haritaya yerleştirilemedi"))
        writeBus(t)
        setMeta("gtfs_date", java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date()))
        val all = lines.flatMap { l -> l.stops.map { it.first } }.toSet()
        // the numbers alone, so the settings can word them in either language; ego_stats stays for older app versions
        setMeta("ego_nums", listOf(t.patterns.size, lines.size, placed.byRef, placed.byName, routed.onRoad, routed.straight,
            all.count { it !in routed.pos }, routed.roadLines, shapes.size).joinToString(","))
        setMeta("ego_stats", "${t.patterns.size} of ${lines.size} EGO lines on the map · stops placed by number ${placed.byRef}, " +
            "by name ${placed.byName}, along the street ${routed.onRoad}, estimated ${routed.straight}, not found ${all.count { it !in routed.pos }} · " +
            "${routed.roadLines} lines drawn along the streets, ${shapes.size} from OpenStreetMap routes")
    }

    // ------------------------------------------------------------------ OSM elements → database
    /** Writes map pieces, places and street names, skipping elements already written. */
    private inner class Writer {
        val seen = HashSet<Long>()
        private val added = ArrayList<Long>()
        private val streetSeen = HashSet<String>()
        private val addedStreets = ArrayList<String>()
        private val coastLen = HashMap<Long, Int>()
        private val sSeen = work.compileStatement("INSERT OR IGNORE INTO seen VALUES(?)")
        private val sCoast = work.compileStatement("INSERT OR REPLACE INTO coast VALUES(?,?)")
        private val sStreet = out.compileStatement("INSERT INTO street VALUES(?,?,?,?)")
        private val sPoi = out.compileStatement("INSERT OR REPLACE INTO poi VALUES(?,?,?,?,?,?,?,?,?)")
        val build = MapBuild(Pieces(pieceSink()), { name, lon, lat ->
            val k = Text.norm(name) + "|" + (lat * 100).toInt() + "|" + (lon * 100).toInt()
            if (streetSeen.add(k)) {
                addedStreets.add(k)
                sStreet.bindString(1, name); sStreet.bindString(2, Text.norm(name)); sStreet.bindDouble(3, lat); sStreet.bindDouble(4, lon)
                sStreet.executeInsert()
            }
        }, { id, g ->
            // the longest copy of a coastline way wins (the extract cuts ways at its edge)
            if ((coastLen[id] ?: 0) < g.size) {
                coastLen[id] = g.size
                sCoast.bindLong(1, id); sCoast.bindBlob(2, Codec.shape(listOf(g))); sCoast.executeInsert()
            }
        })

        init {
            work.rawQuery("SELECT id FROM seen", null).use { c -> while (c.moveToNext()) seen.add(c.getLong(0)) }
            work.rawQuery("SELECT id, data FROM coast", null).use { c ->
                while (c.moveToNext()) coastLen[c.getLong(0)] = Codec.shape(c.getBlob(1)).sumOf { it.size }
            }
        }

        fun begin() { added.clear(); addedStreets.clear() }

        /** After a rolled-back transaction: forget what it added, so it is read again. */
        fun rollback() { seen.removeAll(added.toSet()); streetSeen.removeAll(addedStreets.toSet()) }

        /** A map element; [complete] = false for ways cut at the extract's edge (a full copy may follow). */
        fun map(e: OsmEl, complete: Boolean) {
            if (e["natural"] == "coastline") { build.element(e); return }
            if (seen.contains(e.uid)) return
            if (complete) {
                seen.add(e.uid); added.add(e.uid)
                sSeen.bindLong(1, e.uid); sSeen.executeInsert()
            }
            build.element(e)
        }

        fun poi(e: OsmEl, lat: Double, lon: Double) {
            if (lat.isNaN() || lon.isNaN()) return
            val cat = Poi.category(e.tags) ?: return
            val name = e["name"] ?: ""
            if (cat == Poi.PLACE && name.isEmpty()) return
            sPoi.bindLong(1, e.uid); sPoi.bindDouble(2, lat); sPoi.bindDouble(3, lon); sPoi.bindLong(4, cat.toLong())
            sPoi.bindLong(5, (if (cat == Poi.PLACE) Poi.placeRank(e.tags) else 9).toLong()); sPoi.bindString(6, name)
            sPoi.bindString(7, Text.norm(name + " " + (e["name:en"] ?: "") + " " + (e["brand"] ?: "")))
            sPoi.bindString(8, Poi.label(e.tags)); sPoi.bindString(9, Poi.info(e.tags))
            sPoi.executeInsert()
        }

        /** An element from an Overpass answer. */
        fun overpass(e: OsmEl) {
            when (e.type) {
                'n' -> if (Filters.poi(e.tags)) poi(e, e.lat, e.lon)
                'w' -> {
                    if (e.geom != null && Filters.mapWay(e.tags)) map(e, true)
                    if (Filters.poi(e.tags)) {
                        val c = e.geom?.let { g -> Clip.bounds(g).let { doubleArrayOf((it[1] + it[3]) / 2, (it[0] + it[2]) / 2) } }
                        poi(e, c?.get(0) ?: e.lat, c?.get(1) ?: e.lon)
                    }
                }
                'r' -> {
                    if (e.members != null && Filters.multipolygon(e.tags)) map(e, true)
                    if (Filters.poi(e.tags) && !e.lat.isNaN()) poi(e, e.lat, e.lon)
                }
            }
        }
    }

    private var writer: Writer? = null
    private fun writer() = writer ?: Writer().also { writer = it }

    // ------------------------------------------------------------------ 2. city core: the OSM extract
    private fun core() {
        if (done("core")) return
        val f = File(dl, "core.osm.pbf")
        retry(tr("Map download", "Harita indirmesi")) {
            Net.download(net, City.mapUrl, f, { d, t ->
                say(0.2f + 0.07f * (if (t > 0) d.toFloat() / t else 0f), tr("Map: downloading the city (${d / 1048576} MB)", "Harita: şehir indiriliyor (${d / 1048576} MB)"))
            }, { cancel.get() })
        }
        say(0.27f, tr("Map: reading the city", "Harita: şehir okunuyor"))
        val ex = PbfExtract(f)
        val keepBus = City.ankara // EGO needs OSM's bus stops (their "ref" is the EGO stop number) and bus routes
        ex.wantRelation = { Filters.multipolygon(it) || Filters.route(it) || (keepBus && Filters.busRoute(it)) }
        ex.wantWay = { Filters.mapWay(it) || Filters.poi(it) }
        ex.wantNode = { Filters.poi(it) || (keepBus && Filters.busStop(it)) }
        work.execSQL("CREATE TABLE IF NOT EXISTS road(data BLOB)")
        work.execSQL("DELETE FROM road")
        // a regional extract is cut down to the city's area as it is read. Ankara: stops are read from the
        // whole province; at the first way (nodes come first) the area becomes the box EGO's stops span.
        var a = if (City.ankara) City.searchArea else area
        val egoLines = if (City.ankara) storedEgoLines() else emptyList()
        val seenStops = ArrayList<Ego.OsmStop>()
        var fitted = !City.ankara
        fun fit() {
            if (fitted) return
            fitted = true
            val placed = Ego.place(egoLines, seenStops, interpolate = false)
            Ego.extent(egoLines, placed.pos)?.let { area = grow(City.area, it.pad(3000.0)) }
            a = area
            saveArea()
            say(0.4f, String.format(java.util.Locale.US, tr("Map: EGO reaches %.2f–%.2f N, %.2f–%.2f E", "Harita: EGO %.2f–%.2f K, %.2f–%.2f D arası"), area.s, area.n, area.w, area.e))
        }
        fun inArea(lat: Double, lon: Double) = lat in a.s..a.n && lon in a.w..a.e
        fun inArea(g: DoubleArray): Boolean { val b = Clip.bounds(g); return b[2] >= a.w && b[0] <= a.e && b[3] >= a.s && b[1] <= a.n }
        fun inArea(e: OsmEl) = e.members.orEmpty().any { m -> m.geom?.let { inArea(it) } ?: (!m.lat.isNaN() && inArea(m.lat, m.lon)) }
        work.execSQL("CREATE TABLE IF NOT EXISTS osmstop(ref TEXT, name TEXT, lat REAL, lon REAL)")
        work.execSQL("CREATE TABLE IF NOT EXISTS osmroute(ref TEXT, data BLOB)")
        work.execSQL("DELETE FROM osmstop"); work.execSQL("DELETE FROM osmroute")
        ex.cancelled = { cancel.get() }
        var step = 0
        ex.progress = { say(0.3f + 0.08f * ++step, tr("Map: ", "Harita: ") + it) }
        val w = writer()
        val routes = ArrayList<OsmEl>()
        tx(work) {
            tx(out) {
                w.begin()
                ex.run(
                    { e ->
                        if (!inArea(e.lat, e.lon)) return@run
                        if (keepBus && Filters.busStop(e.tags)) {
                            work.execSQL("INSERT INTO osmstop VALUES(?,?,?,?)", arrayOf(e["ref"], e["name"] ?: "", e.lat, e.lon))
                            seenStops.add(Ego.OsmStop(e["ref"], e["name"] ?: "", e.lat, e.lon))
                        }
                        if (Filters.poi(e.tags)) w.poi(e, e.lat, e.lon)
                    },
                    { e ->
                        fit()
                        if (!inArea(e.geom!!)) return@run
                        // the streets, so EGO lines without a route shape can follow them
                        if (keepBus && Filters.drivable(e.tags)) work.execSQL("INSERT INTO road VALUES(?)", arrayOf<Any>(Codec.shape(listOf(e.geom))))
                        if (Filters.mapWay(e.tags)) w.map(e, !ex.truncated(e.uid))
                        if (Filters.poi(e.tags)) {
                            val b = Clip.bounds(e.geom!!)
                            w.poi(e, (b[1] + b[3]) / 2, (b[0] + b[2]) / 2)
                        }
                    },
                    { e ->
                        fit()
                        if (!inArea(e)) return@run
                        if (keepBus && Filters.busRoute(e.tags)) {
                            val parts = e.members.orEmpty().filter { it.type == 'w' && it.geom != null }.map { it.geom!! }
                            val ref = e["ref"]?.trim()
                            if (ref != null && parts.isNotEmpty()) {
                                val (rings, open) = Coast.join(parts, directed = false)
                                work.execSQL("INSERT INTO osmroute VALUES(?,?)", arrayOf(ref, Codec.shape(open + rings)))
                            }
                        } else if (Filters.route(e.tags)) routes.add(e) else w.map(e, !ex.truncated(e.uid))
                    },
                )
                fit()
                // places were read before the area was known
                if (City.ankara) out.execSQL("DELETE FROM poi WHERE lat<? OR lat>? OR lon<? OR lon>?", arrayOf<Any>(area.s, area.n, area.w, area.e))
                say(0.53f, tr("Metro, tram and ferry lines", "Metro, tramvay ve vapur hatları"))
                lines(routes) { m -> if (m.type == 'n') ex.memberNode(m.ref) else null }
                setMeta("core", (if (City.mapCoversArea) area else ex.bbox)?.encode() ?: "")
                mark("core")
            }
        }
        f.delete()
    }

    // ------------------------------------------------------------------ rail & ferry lines
    private fun lines(rels: List<OsmEl>, named: (OsmEl.Member) -> OsmEl?) {
        out.execSQL("DELETE FROM stop WHERE id>=${Store.RAIL_STOP0}")
        out.execSQL("DELETE FROM line WHERE id>=${Store.RAIL_LINE0}")
        out.execSQL("DELETE FROM variant WHERE id>=${Store.RAIL_VAR0}")
        out.execSQL("DELETE FROM stop_variant WHERE variant>=${Store.RAIL_VAR0}")
        out.execSQL("DELETE FROM shape WHERE id>=${Store.RAIL_VAR0}")

        class Station(val id: Int, val name: String, val lat: Double, val lon: Double, val mode: Int)
        val stations = HashMap<String, MutableList<Station>>()
        var nextStation = Store.RAIL_STOP0
        fun station(name: String, lat: Double, lon: Double, mode: Int): Station {
            val key = Text.norm(name.replace(Regex("(?i)\\b(iskelesi|istasyonu|durağı|peronu?)\\b"), ""))
            val l = stations.getOrPut(key) { ArrayList() }
            l.firstOrNull { Geo.dist(lat, lon, it.lat, it.lon) < 600 }?.let { return it }
            return Station(nextStation++, Text.title(name), lat, lon, mode).also { l.add(it) }
        }

        fun modeOf(route: String?) = when (route) {
            "subway", "light_rail", "monorail" -> Mode.METRO
            "tram" -> Mode.TRAM
            "funicular", "aerialway" -> Mode.CABLE
            "ferry" -> Mode.FERRY
            else -> Mode.TRAIN
        }

        fun color(s: String?): Int? = try {
            if (s == null) null else android.graphics.Color.parseColor(if (s.matches(Regex("[0-9a-fA-F]{6}"))) "#$s" else s)
        } catch (_: Exception) { null }

        val lineIds = HashMap<String, Int>()
        var nextLine = Store.RAIL_LINE0
        var nextVar = Store.RAIL_VAR0
        for (r in rels.sortedBy { it["ref"] ?: it["name"] ?: "" }) {
            val mode = if (r["route"] == "train" && (r["ref"] ?: "").startsWith("T")) Mode.TRAM else modeOf(r["route"])
            val parts = r.members.orEmpty().filter { it.type == 'w' && it.geom != null && !it.role.startsWith("platform") }.map { it.geom!! }
            if (parts.isEmpty()) continue
            // stations in route order: stop positions, else platforms
            val ms = r.members.orEmpty()
            val stopMembers = ms.filter { it.role.startsWith("stop") }.ifEmpty { ms.filter { it.role.startsWith("platform") } }
            val seq = ArrayList<Station>()
            for (m in stopMembers) {
                val e = named(m) ?: continue
                val nm = e["name"] ?: continue
                val la = if (!m.lat.isNaN()) m.lat else e.lat
                val lo = if (!m.lon.isNaN()) m.lon else e.lon
                if (la.isNaN() || lo.isNaN()) continue
                val st = station(nm, la, lo, mode)
                if (seq.lastOrNull() !== st) seq.add(st)
            }
            // a missing ref often leads the name ("M5 (Kızılay – İlkbahar) Metro Hattı")
            val ref = r["ref"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: Regex("^([A-Z]{1,2}\\d{1,2}[a-z]?)\\b").find(r["name"] ?: "")?.groupValues?.get(1)
                ?: if (mode == Mode.FERRY) "Ferry" else (r["name"] ?: "?").take(12)
            val name = r["name"] ?: ref
            val key = "$mode|$ref" + if (mode == Mode.FERRY && r["ref"] == null) "|" + Text.norm(name).split(' ').take(2) else ""
            val col = color(r["colour"]) ?: Mode.defaultColor(mode)
            val lid = lineIds.getOrPut(key) {
                val id = nextLine++
                val lname = if (mode == Mode.FERRY) name else name.replace(Regex("^\\s*${Regex.escape(ref)}\\s*[:\\-–]?\\s*"), "")
                out.execSQL("INSERT INTO line VALUES(?,?,?,?,?,?)", arrayOf<Any>(id, ref, lname, Text.norm("$ref $lname"), mode, col))
                id
            }
            val vid = nextVar++
            out.execSQL("INSERT INTO shape VALUES(?,?)", arrayOf<Any>(vid, Codec.shape(parts)))
            val ids = IntArray(seq.size) { seq[it].id }
            val d = IntArray(seq.size)
            for (i in 1 until seq.size) d[i] = d[i - 1] + Geo.dist(seq[i - 1].lat, seq[i - 1].lon, seq[i].lat, seq[i].lon).toInt()
            // the last stop, not the "to" tag: they disagree on some OSM ferry routes
            val head = seq.lastOrNull()?.name ?: r["to"] ?: name
            out.execSQL(
                "INSERT INTO variant VALUES(?,?,?,?,?,?,?,?,0)",
                arrayOf<Any>(vid, lid, 0, Text.title(head), "osm:${r.id}", vid, Codec.ints(ids), Codec.ints(d)),
            )
            ids.forEachIndexed { k, s -> out.execSQL("INSERT INTO stop_variant VALUES(?,?,?)", arrayOf<Any>(s, vid, k)) }
        }
        for (l in stations.values) for (s in l) {
            out.execSQL("INSERT INTO stop VALUES(?,?,?,?,?,?,?)", arrayOf<Any>(s.id, "", s.name, Text.norm(s.name), s.lat, s.lon, s.mode))
        }
    }

    // ------------------------------------------------------------------ 3. outer districts (Overpass, best effort)
    private fun key(b: BBox) = String.format(java.util.Locale.US, "%.4f,%.4f,%.4f,%.4f", b.w, b.s, b.e, b.n)

    private fun pieceSink(): (Int, Int, Int, Int, ByteArray) -> Unit {
        val st: SQLiteStatement = work.compileStatement("INSERT INTO piece VALUES(?,?,?,?,?)")
        return { lvl, x, y, k, data ->
            st.bindLong(1, lvl.toLong()); st.bindLong(2, x.toLong()); st.bindLong(3, y.toLong()); st.bindLong(4, k.toLong()); st.bindBlob(5, data)
            st.executeInsert()
        }
    }

    /** Returns how many squares could not be loaded. */
    private fun outskirts(): Int {
        val core = BBox.decode(meta("core")) ?: return 0
        val all = outskirts(core, area)
        val queue = ArrayDeque(all.filter { !done("out:" + key(it)) }.map { it to 0 })
        var finished = all.size - queue.size
        var failed = 0
        val w = writer()
        while (queue.isNotEmpty()) {
            checkCancel()
            val (b, depth) = queue.removeFirst()
            if (depth == 0) say(0.55f + 0.33f * finished / all.size, tr("Outer districts: square ${finished + 1} of ${all.size}", "Dış ilçeler: kare ${finished + 1} / ${all.size}"))
            val f = File(dl, "out.json")
            try {
                overpass(outskirtsQuery(b), f, tr("outer districts", "dış ilçeler"), attempts = 4)
                var ok = false
                work.beginTransaction(); out.beginTransaction()
                w.begin()
                try {
                    f.inputStream().buffered(1 shl 16).use { inp -> Overpass.parse(inp) { e -> w.overpass(e) } }
                    work.execSQL("INSERT OR IGNORE INTO done VALUES(?)", arrayOf("out:" + key(b)))
                    work.setTransactionSuccessful(); out.setTransactionSuccessful()
                    ok = true
                } finally {
                    out.endTransaction(); work.endTransaction()
                    if (!ok) w.rollback()
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: IOException) {
                if (depth < 2 && e.message?.startsWith("Overpass:") == true) {
                    // too much data for one answer: try the four quarters
                    val mx = (b.w + b.e) / 2; val my = (b.s + b.n) / 2
                    for (q in listOf(BBox(mx, my, b.e, b.n), BBox(b.w, my, mx, b.n), BBox(mx, b.s, b.e, my), BBox(b.w, b.s, mx, my))) queue.addFirst(q to depth + 1)
                    continue
                }
                failed++
            } finally {
                f.delete()
            }
            if (depth == 0) finished++
        }
        return failed
    }

    private fun meta(k: String): String? = out.rawQuery("SELECT v FROM meta WHERE k=?", arrayOf(k)).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    // ------------------------------------------------------------------ Overpass with retries
    private fun overpass(query: String, dest: File, what: String, attempts: Int = 10) {
        var wait = 15_000L
        var attempt = 0
        while (true) {
            checkCancel()
            val url = OVERPASS[attempt % OVERPASS.size]
            try {
                Net.overpass(net, url, query, dest) { cancel.get() }
                // a busy server sometimes answers 200 with an HTML or XML error page
                val head = dest.inputStream().use { val b = ByteArray(64); val k = it.read(b); if (k > 0) String(b, 0, k) else "" }
                if (!head.trimStart().startsWith("{")) throw IOException("server sent no data")
                return
            } catch (e: InterruptedException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= attempts) throw IOException(tr("OpenStreetMap server busy ($what): ${e.message}", "OpenStreetMap sunucusu meşgul ($what): ${e.message}"))
                val ra = (e as? Net.Http)?.retryAfter ?: 0
                say(lastF, tr("Server busy ($what), retrying in ${maxOf(wait / 1000, ra.toLong())} s…", "Sunucu meşgul ($what), ${maxOf(wait / 1000, ra.toLong())} sn sonra yeniden deneniyor…"))
                sleep(maxOf(wait, ra * 1000L))
                wait = minOf(wait * 2, 60_000L)
                net.n = wifi()
            }
        }
    }

    private fun <T> retry(what: String, block: () -> T): T {
        var wait = 5_000L
        var attempt = 0
        while (true) {
            checkCancel()
            try {
                return block()
            } catch (e: InterruptedException) {
                throw e
            } catch (e: IOException) {
                attempt++
                if (attempt >= 5) throw IOException(tr("$what failed: ${e.message}", "$what başarısız: ${e.message}"))
                say(lastF, tr("$what failed (${e.message}), retrying…", "$what başarısız (${e.message}), yeniden deneniyor…"))
                sleep(wait)
                wait *= 2
                net.n = wifi()
            }
        }
    }

    // ------------------------------------------------------------------ 5. build
    private fun build(missing: Int) {
        val sink = pieceSink()
        val pieces = Pieces(sink)
        if (!done("land")) {
            say(0.89f, tr("Building the coastline", "Kıyı şeridi oluşturuluyor"))
            val ways = ArrayList<DoubleArray>()
            work.rawQuery("SELECT data FROM coast", null).use { c -> while (c.moveToNext()) ways.addAll(Codec.shape(c.getBlob(0))) }
            // with every outer square loaded the coast is complete for the province, else only for the core
            val core = BBox.decode(meta("core"))
            val box = if (missing == 0 || core == null) BBox(area.w + 0.0005, area.s + 0.0005, area.e - 0.0005, area.n - 0.0005)
            else BBox(core.w + 0.01, core.s + 0.01, core.e - 0.01, core.n - 0.01)
            var land = Coast.land(ways, box.w, box.s, box.e, box.n)
            var landBox = box
            if (land == null && core != null && box.w < core.w) {
                landBox = BBox(core.w + 0.01, core.s + 0.01, core.e - 0.01, core.n - 0.01)
                land = Coast.land(ways, landBox.w, landBox.s, landBox.e, landBox.n)
            }
            tx(work) {
                if (land != null) pieces.polygon(K.LAND, land.map { Pieces.merc(it) }, 0)
                mark("land")
            }
            setMeta("land", if (land != null) "1" else "0")
            setMeta("land_area", landBox.encode())
        }
        if (!done("overlay")) {
            say(0.91f, tr("Drawing lines, stops and places into the map", "Hatlar, duraklar ve yerler haritaya çiziliyor"))
            tx(work) {
                // bus routes (each shape once)
                out.rawQuery("SELECT id,data FROM shape WHERE id<${Store.RAIL_VAR0} AND id IN (SELECT shape FROM variant)", null).use { c ->
                    while (c.moveToNext()) {
                        checkCancel()
                        for (p in Codec.shape(c.getBlob(1))) pieces.line(K.BUS, Pieces.merc(p), 0)
                    }
                }
                // rail & ferry
                val stationColor = HashMap<Int, Int>()
                out.rawQuery(
                    "SELECT v.shape,l.mode,l.color,v.stops FROM variant v JOIN line l ON l.id=v.line WHERE v.id>=${Store.RAIL_VAR0}", null,
                ).use { c ->
                    val done = HashSet<Int>()
                    while (c.moveToNext()) {
                        val mode = c.getInt(1); val col = c.getInt(2)
                        for (s in Codec.ints(c.getBlob(3))) stationColor.putIfAbsent(s, col)
                        if (!done.add(c.getInt(0))) continue
                        val data = out.rawQuery("SELECT data FROM shape WHERE id=?", arrayOf(c.getInt(0).toString())).use { d -> if (d.moveToFirst()) d.getBlob(0) else null } ?: continue
                        for (p in Codec.shape(data)) pieces.line(if (mode == Mode.FERRY) K.FERRY else K.RAIL, Pieces.merc(p), 0, color = col)
                    }
                }
                out.rawQuery("SELECT id,lat,lon,mode,name FROM stop", null).use { c ->
                    while (c.moveToNext()) {
                        val id = c.getInt(0); val x = Geo.mx(c.getDouble(2)); val y = Geo.my(c.getDouble(1))
                        if (c.getInt(3) == Mode.BUS) pieces.point(K.STOP, x, y, 2, ref = id.toLong())
                        else pieces.point(K.STATION, x, y, 1, name = c.getString(4), color = stationColor[id] ?: Mode.defaultColor(c.getInt(3)), ref = id.toLong())
                    }
                }
                out.rawQuery("SELECT id,lat,lon,cat,rank,name FROM poi", null).use { c ->
                    while (c.moveToNext()) {
                        val x = Geo.mx(c.getDouble(2)); val y = Geo.my(c.getDouble(1))
                        val cat = c.getInt(3)
                        if (cat == Poi.PLACE) {
                            val rank = c.getInt(4)
                            val lvl = when { rank <= 2 -> 0; rank <= 4 -> 1; else -> 2 }
                            pieces.point(K.PLACE, x, y, lvl, name = c.getString(5), ref = rank.toLong())
                        } else pieces.point(K.POI, x, y, 2, name = c.getString(5)?.ifEmpty { null }, ref = cat.toLong())
                    }
                }
                mark("overlay")
            }
        }
        say(0.94f, tr("Packing map cells", "Harita hücreleri paketleniyor"))
        out.execSQL("DELETE FROM cell")
        work.execSQL("CREATE INDEX IF NOT EXISTS piece_i ON piece(lvl,x,y,k)")
        tx(out) {
            val ins = out.compileStatement("INSERT INTO cell VALUES(?,?,?,?)")
            val buf = BytesOut(1 shl 16)
            var cl = -1; var cx = 0; var cy = 0
            var n = 0
            fun flush() {
                if (cl < 0 || buf.size == 0) return
                ins.bindLong(1, cl.toLong()); ins.bindLong(2, cx.toLong()); ins.bindLong(3, cy.toLong()); ins.bindBlob(4, buf.toArray())
                ins.executeInsert()
                buf.clear()
                if (++n % 500 == 0) { checkCancel(); say(0.94f + 0.04f * minOf(1f, n / 12000f), tr("Packing map cells ($n)", "Harita hücreleri paketleniyor ($n)")) }
            }
            work.rawQuery("SELECT lvl,x,y,data FROM piece ORDER BY lvl,x,y,k,rowid", null).use { c ->
                while (c.moveToNext()) {
                    val l = c.getInt(0); val x = c.getInt(1); val y = c.getInt(2)
                    if (l != cl || x != cx || y != cy) { flush(); cl = l; cx = x; cy = y }
                    val d = c.getBlob(3)
                    buf.bytes(d, 0, d.size)
                }
            }
            flush()
            setMeta("cells", n.toString())
        }
        Store.INDEXES.forEach { out.execSQL(it) }
        setMeta("version", VERSION)
        setMeta("area", area.encode())
        setMeta("synced_at", System.currentTimeMillis().toString())
        setMeta("outskirts_missing", missing.toString())
        out.rawQuery("SELECT count(*) FROM poi WHERE cat<>${Poi.PLACE}", null).use { c -> if (c.moveToFirst()) setMeta("places", c.getLong(0).toString()) }
        say(0.99f, tr("Finishing", "Bitiriliyor"))
    }

    private fun finish() {
        val cur = dataFile(ctx)
        val old = File(dir, "data-old.db")
        old.delete()
        if (cur.exists() && !cur.renameTo(old)) throw IOException(tr("could not replace the old data", "eski veri değiştirilemedi"))
        if (!newF.renameTo(cur)) {
            old.renameTo(cur)
            throw IOException(tr("could not install the new data", "yeni veri kurulamadı"))
        }
        old.delete()
        workF.delete()
        dl.listFiles()?.forEach { it.delete() }
        say(1f, tr("Done", "Bitti"))
    }
}
