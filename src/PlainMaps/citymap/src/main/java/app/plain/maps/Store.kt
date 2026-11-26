package app.plain.maps

import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import app.plain.lang.tr
import java.io.File

/** Transport modes (line.mode / stop.mode). */
object Mode {
    const val BUS = 0
    const val METRO = 1
    const val TRAM = 2
    const val CABLE = 3
    const val FERRY = 4
    const val TRAIN = 5

    fun name(m: Int) = when (m) {
        BUS -> tr("Bus", "Otobüs"); METRO -> "Metro"; TRAM -> tr("Tram", "Tramvay"); CABLE -> tr("Funicular / cable car", "Füniküler / teleferik")
        FERRY -> tr("Ferry", "Vapur"); TRAIN -> tr("Train", "Tren")
        else -> tr("Line", "Hat")
    }

    fun defaultColor(m: Int) = when (m) {
        BUS -> 0xFF26C6DA.toInt(); METRO -> 0xFFE53935.toInt(); TRAM -> 0xFFFB8C00.toInt(); CABLE -> 0xFF8D6E63.toInt()
        FERRY -> 0xFF29B6F6.toInt(); TRAIN -> 0xFF5C6BC0.toInt(); else -> 0xFF9E9E9E.toInt()
    }
}

/**
 * The offline database (files/data.db), written only by a sync and read by the app.
 * Bus data comes from İETT, rail/ferry lines, places and the map from OpenStreetMap.
 * Id ranges keep the two transit sources apart: rail/ferry stops ≥ 10 000 000,
 * lines ≥ 100 000, variants and their shapes ≥ 1 000 000.
 */
class Store(val file: File) {
    companion object {
        const val RAIL_STOP0 = 10_000_000
        const val RAIL_LINE0 = 100_000
        const val RAIL_VAR0 = 1_000_000

        val SCHEMA = listOf(
            "CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT)",
            "CREATE TABLE stop(id INTEGER PRIMARY KEY, code TEXT, name TEXT, norm TEXT, lat REAL, lon REAL, mode INTEGER)",
            "CREATE TABLE line(id INTEGER PRIMARY KEY, ref TEXT, name TEXT, norm TEXT, mode INTEGER, color INTEGER)",
            "CREATE TABLE variant(id INTEGER PRIMARY KEY, line INTEGER, dir INTEGER, headsign TEXT, code TEXT, shape INTEGER, stops BLOB, dists BLOB, ntrips INTEGER)",
            "CREATE TABLE trip(variant INTEGER, days INTEGER, start INTEGER, dur INTEGER)",
            "CREATE TABLE stop_variant(stop INTEGER, variant INTEGER, idx INTEGER)",
            "CREATE TABLE shape(id INTEGER PRIMARY KEY, data BLOB)",
            "CREATE TABLE poi(id INTEGER PRIMARY KEY, lat REAL, lon REAL, cat INTEGER, rank INTEGER, name TEXT, norm TEXT, label TEXT, info TEXT)",
            "CREATE TABLE street(name TEXT, norm TEXT, lat REAL, lon REAL)",
            "CREATE TABLE cell(lvl INTEGER, x INTEGER, y INTEGER, data BLOB, PRIMARY KEY(lvl, x, y)) WITHOUT ROWID",
        )
        val INDEXES = listOf(
            "CREATE INDEX IF NOT EXISTS stop_lat ON stop(lat)",
            "CREATE INDEX IF NOT EXISTS variant_line ON variant(line)",
            "CREATE INDEX IF NOT EXISTS trip_variant ON trip(variant)",
            "CREATE INDEX IF NOT EXISTS sv_stop ON stop_variant(stop)",
            "CREATE INDEX IF NOT EXISTS poi_lat ON poi(lat)",
        )
    }

    @Volatile private var db: SQLiteDatabase? = null
    private val variants = LruCache<Int, Variant>(400)
    private val tripsCache = LruCache<Int, IntArray>(200)
    private val shapes = LruCache<Int, List<DoubleArray>>(120)
    var area: BBox? = null
        private set
    var hasLand = false
        private set
    /** Where the sea is known (the coastline was complete there). */
    var landArea: BBox? = null
        private set

    val ready get() = db != null

    @Synchronized
    fun open(): Boolean {
        close()
        if (!file.exists()) return false
        return try {
            val d = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
            db = d
            area = BBox.decode(meta("area"))
            hasLand = meta("land") == "1"
            landArea = BBox.decode(meta("land_area")) ?: area
            true
        } catch (_: Exception) {
            db = null
            false
        }
    }

    @Synchronized
    fun close() {
        db?.close()
        db = null
        net = null
        noticeRefs = null
        variants.evictAll(); tripsCache.evictAll(); shapes.evictAll()
    }

    fun meta(k: String): String? = q("SELECT v FROM meta WHERE k=?", k) { it.getString(0) }.firstOrNull()

    private fun <T> q(sql: String, vararg args: Any?, map: (android.database.Cursor) -> T): List<T> {
        val d = db ?: return emptyList()
        val out = ArrayList<T>()
        try {
            d.rawQuery(sql, args.map { it?.toString() }.toTypedArray()).use { c -> while (c.moveToNext()) out.add(map(c)) }
        } catch (_: Exception) {
        }
        return out
    }

    fun cell(lvl: Int, x: Int, y: Int): ByteArray? =
        q("SELECT data FROM cell WHERE lvl=? AND x=? AND y=?", lvl, x, y) { it.getBlob(0) }.firstOrNull()

    // ------------------------------------------------------------ stops & lines
    class StopR(val id: Int, val code: String, val name: String, val lat: Double, val lon: Double, val mode: Int)

    private fun stopOf(c: android.database.Cursor) =
        StopR(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getDouble(3), c.getDouble(4), c.getInt(5))

    fun stop(id: Int) = q("SELECT id,code,name,lat,lon,mode FROM stop WHERE id=?", id) { stopOf(it) }.firstOrNull()

    fun stops(ids: IntArray): Map<Int, StopR> {
        val out = HashMap<Int, StopR>()
        for (chunk in ids.distinct().chunked(400)) {
            q("SELECT id,code,name,lat,lon,mode FROM stop WHERE id IN (${chunk.joinToString(",")})") { stopOf(it) }.forEach { out[it.id] = it }
        }
        return out
    }

    /** Stops within [r] metres, nearest first. */
    fun stopsNear(lat: Double, lon: Double, r: Double, limit: Int = 80): List<Pair<StopR, Double>> {
        val dLat = r / 111_320.0
        val dLon = r / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
        return q(
            "SELECT id,code,name,lat,lon,mode FROM stop WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            lat - dLat, lat + dLat, lon - dLon, lon + dLon,
        ) { stopOf(it) }
            .map { it to Geo.dist(lat, lon, it.lat, it.lon) }
            .filter { it.second <= r }.sortedBy { it.second }.take(limit)
    }

    class Variant(
        val id: Int, val line: Int, val ref: String, val lineName: String, val mode: Int, val color: Int,
        val dir: Int, val headsign: String, val shape: Int, val stops: IntArray, val dists: IntArray, val ntrips: Int,
    )

    private val VSQL = "SELECT v.id,v.line,l.ref,l.name,l.mode,l.color,v.dir,v.headsign,v.shape,v.stops,v.dists,v.ntrips FROM variant v JOIN line l ON l.id=v.line"

    private fun variantOf(c: android.database.Cursor) = Variant(
        c.getInt(0), c.getInt(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getInt(4), c.getInt(5),
        c.getInt(6), c.getString(7) ?: "", c.getInt(8), Codec.ints(c.getBlob(9)), Codec.ints(c.getBlob(10)), c.getInt(11),
    )

    fun variant(id: Int): Variant? {
        variants.get(id)?.let { return it }
        return q("$VSQL WHERE v.id=?", id) { variantOf(it) }.firstOrNull()?.also { variants.put(id, it) }
    }

    fun variantsOfLine(line: Int): List<Variant> =
        q("$VSQL WHERE v.line=? ORDER BY v.ntrips DESC", line) { variantOf(it) }.onEach { variants.put(it.id, it) }

    /** (stop, variant, index of the stop in the variant) for these stops. */
    fun serving(stopIds: Collection<Int>): List<IntArray> {
        if (stopIds.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>()
        for (chunk in stopIds.chunked(400)) {
            out += q("SELECT stop,variant,idx FROM stop_variant WHERE stop IN (${chunk.joinToString(",")})") {
                intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2))
            }
        }
        return out
    }

    /** Trips of a variant as triples: days mask, start second, duration seconds (sorted by start). */
    fun trips(variant: Int): IntArray {
        tripsCache.get(variant)?.let { return it }
        val l = q("SELECT days,start,dur FROM trip WHERE variant=? ORDER BY start", variant) {
            intArrayOf(it.getInt(0), it.getInt(1), it.getInt(2))
        }
        val a = IntArray(l.size * 3)
        l.forEachIndexed { i, t -> a[3 * i] = t[0]; a[3 * i + 1] = t[1]; a[3 * i + 2] = t[2] }
        tripsCache.put(variant, a)
        return a
    }

    fun shape(id: Int): List<DoubleArray>? {
        if (id == 0) return null
        shapes.get(id)?.let { return it }
        return q("SELECT data FROM shape WHERE id=?", id) { Codec.shape(it.getBlob(0)) }.firstOrNull()?.also { shapes.put(id, it) }
    }

    /** The line drawn for a variant: its shape, or its stops joined when there is none. */
    fun geometry(v: Variant): List<DoubleArray> {
        shape(v.shape)?.let { if (it.isNotEmpty()) return it }
        val st = stops(v.stops)
        val a = DoubleArray(v.stops.size * 2)
        var n = 0
        for (s in v.stops) st[s]?.let { a[n++] = it.lon; a[n++] = it.lat }
        return listOf(a.copyOf(n))
    }

    // ------------------------------------------------------------ places
    class PoiR(val id: Long, val lat: Double, val lon: Double, val cat: Int, val rank: Int, val name: String, val label: String, val info: String)

    private val PSQL = "SELECT id,lat,lon,cat,rank,name,label,info FROM poi"
    private fun poiOf(c: android.database.Cursor) = PoiR(
        c.getLong(0), c.getDouble(1), c.getDouble(2), c.getInt(3), c.getInt(4), c.getString(5) ?: "", c.getString(6) ?: "", c.getString(7) ?: "",
    )

    fun poi(id: Long) = q("$PSQL WHERE id=?", id) { poiOf(it) }.firstOrNull()

    fun poisNear(lat: Double, lon: Double, r: Double, limit: Int = 40): List<Pair<PoiR, Double>> {
        val dLat = r / 111_320.0
        val dLon = r / (111_320.0 * kotlin.math.cos(Math.toRadians(lat)))
        return q("$PSQL WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? AND cat<>${Poi.PLACE}", lat - dLat, lat + dLat, lon - dLon, lon + dLon) { poiOf(it) }
            .map { it to Geo.dist(lat, lon, it.lat, it.lon) }.filter { it.second <= r }.sortedBy { it.second }.take(limit)
    }

    // ------------------------------------------------------------ search
    class Hit(val kind: Int, val id: Long, val title: String, val sub: String, val lat: Double, val lon: Double, val color: Int, val badge: String?)

    object HitKind { const val LINE = 0; const val STOP = 1; const val PLACE = 2; const val STREET = 3 }

    /** Search kinds for [search]'s mask. */
    object Kinds { const val LINES = 1; const val STOPS = 2; const val PLACES = 4; const val STREETS = 8; const val ALL = 15 }

    /**
     * Lines, stops, places and streets matching [text]. [kinds] picks which; [cats] limits places to
     * these categories (null = all).
     */
    fun search(text: String, near: Pair<Double, Double>?, kinds: Int = Kinds.ALL, cats: Set<Int>? = null): List<Hit> {
        val n = Text.norm(text)
        if (n.isEmpty()) return emptyList()
        val like = "%" + n.replace("%", "").replace("_", "") + "%"
        val pre = n.replace("%", "").replace("_", "") + "%"
        val out = ArrayList<Hit>()
        fun dist(lat: Double, lon: Double) = near?.let { Geo.dist(it.first, it.second, lat, lon) } ?: 0.0
        // lines: exact ref first, then prefix, then name matches
        if (kinds and Kinds.LINES != 0) out += q(
            "SELECT id,ref,name,mode,color FROM line WHERE norm LIKE ? ORDER BY (lower(ref)=?) DESC, (norm LIKE ?) DESC, length(ref), ref LIMIT 25",
            like, n, pre,
        ) { Hit(HitKind.LINE, it.getLong(0), it.getString(2) ?: "", Mode.name(it.getInt(3)), 0.0, 0.0, it.getInt(4), it.getString(1)) }
        if (kinds and Kinds.STOPS != 0) out += q("SELECT id,code,name,lat,lon,mode FROM stop WHERE norm LIKE ? LIMIT 300", like) { stopOf(it) }
            .sortedBy { dist(it.lat, it.lon) }.distinctBy { it.name + "|" + (it.lat * 200).toInt() + "|" + (it.lon * 200).toInt() }.take(25)
            .map { Hit(HitKind.STOP, it.id.toLong(), it.name, tr(Mode.name(it.mode) + " stop", if (it.mode == Mode.FERRY) "İskele" else Mode.name(it.mode) + " durağı") + (if (it.code.isNotEmpty() && it.mode == Mode.BUS) " · ${it.code}" else ""), it.lat, it.lon, Mode.defaultColor(it.mode), null) }
        if (kinds and Kinds.PLACES != 0) out += placeHits(placesMatching(listOf(n), false, near, cats, 400), near).take(30)
        if (kinds and Kinds.STREETS != 0) out += q("SELECT name,lat,lon FROM street WHERE norm LIKE ? LIMIT 300", like) { Triple(it.getString(0) ?: "", it.getDouble(1), it.getDouble(2)) }
            .sortedBy { dist(it.second, it.third) }.distinctBy { it.first }.take(20)
            .map { Hit(HitKind.STREET, 0, it.first, tr("Street", "Sokak"), it.second, it.third, 0xFF9E9E9E.toInt(), null) }
        return out
    }

    /**
     * Places whose name matches any of [terms] (already [Text.norm]ed), nearest to [near] first.
     * The ordering happens in SQL: a chain like A101 has thousands of branches, and taking an
     * arbitrary few hundred before sorting used to miss the ones on screen. [words] matches whole
     * words only ("şok" must not match "sokak", "bim" not "ibrahim"). [box] (lat0, lon0, lat1, lon1)
     * limits the search to an area.
     */
    fun placesMatching(terms: List<String>, words: Boolean, near: Pair<Double, Double>?, cats: Set<Int>?, limit: Int, box: DoubleArray? = null): List<PoiR> {
        val conds = ArrayList<String>(); val args = ArrayList<Any>()
        for (t0 in terms) {
            val t = t0.replace("%", "").replace("_", "")
            if (t.isEmpty()) continue
            if (words) {
                conds += "(norm=? OR norm LIKE ? OR norm LIKE ? OR norm LIKE ?)"
                args += t; args += "$t %"; args += "% $t"; args += "% $t %"
            } else { conds += "norm LIKE ?"; args += "%$t%" }
        }
        if (conds.isEmpty()) return emptyList()
        var sql = "$PSQL WHERE (${conds.joinToString(" OR ")})"
        cats?.let { sql += if (it.isEmpty()) " AND 0" else " AND cat IN (${it.joinToString(",")})" }
        box?.let { sql += " AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?"; args += it[0]; args += it[2]; args += it[1]; args += it[3] }
        if (near != null) {
            val k = kotlin.math.cos(Math.toRadians(near.first)).let { it * it }
            sql += " ORDER BY (lat-?)*(lat-?)+(lon-?)*(lon-?)*?"
            args += near.first; args += near.first; args += near.second; args += near.second; args += k
        }
        sql += " LIMIT $limit"
        return q(sql, *args.toTypedArray()) { poiOf(it) }
    }

    /** Search hits for [places]: neighbourhoods and towns first, then by distance. */
    fun placeHits(places: List<PoiR>, near: Pair<Double, Double>?): List<Hit> {
        fun dist(lat: Double, lon: Double) = near?.let { Geo.dist(it.first, it.second, lat, lon) } ?: 0.0
        return places.sortedWith(compareBy({ if (it.cat == Poi.PLACE) it.rank - 10 else 0 }, { dist(it.lat, it.lon) }))
            .map { Hit(HitKind.PLACE, it.id, it.name.ifEmpty { it.label }, it.label, it.lat, it.lon, Poi.color(it.cat), null) }
    }

    // ------------------------------------------------------------ route finding
    @Volatile private var net: Network? = null

    /** The line network for the route finder, built once per opened database (about a second). */
    fun network(): Network? {
        net?.let { return it }
        synchronized(this) {
            net?.let { return it }
            if (db == null) return null
            val ids = IntList(16000); val lat = DoubleList(32000)
            q("SELECT id,lat,lon FROM stop") { c -> ids.add(c.getInt(0)); lat.add(c.getDouble(1), c.getDouble(2)) }
            val idx = HashMap<Int, Int>(ids.size * 2)
            for (i in 0 until ids.size) idx[ids[i]] = i
            val vId = IntList(); val vLine = IntList(); val vStops = ArrayList<IntArray>(); val vDists = ArrayList<IntArray>()
            q("SELECT id,line,stops,dists FROM variant") { c ->
                val st = Codec.ints(c.getBlob(2))
                val ix = IntArray(st.size) { idx[st[it]] ?: -1 }
                val d = Codec.ints(c.getBlob(3))
                if (ix.size >= 2 && ix.none { it < 0 } && d.size == ix.size) {
                    vId.add(c.getInt(0)); vLine.add(c.getInt(1)); vStops.add(ix); vDists.add(d)
                }
            }
            val a = lat.toArray()
            return Network(
                ids.toArray(), DoubleArray(ids.size) { a[2 * it] }, DoubleArray(ids.size) { a[2 * it + 1] },
                vId.toArray(), vLine.toArray(), vStops.toTypedArray(), vDists.toTypedArray(),
            ).also { net = it }
        }
    }

    // ------------------------------------------------------------ situational notices (İETT announcements)
    class Notice(val ref: String, val kind: String, val at: String, val msg: String)

    fun notices(ref: String): List<Notice> =
        q("SELECT ref,kind,at,msg FROM notice WHERE ref=?", ref) { Notice(it.getString(0) ?: "", it.getString(1) ?: "", it.getString(2) ?: "", it.getString(3) ?: "") }

    @Volatile private var noticeRefs: Set<String>? = null

    /** Line numbers that have a current announcement. */
    fun noticed(): Set<String> = noticeRefs ?: q("SELECT DISTINCT ref FROM notice") { it.getString(0) ?: "" }.toHashSet().also { noticeRefs = it }

    fun lineRow(id: Int) = q("SELECT ref,name,mode,color FROM line WHERE id=?", id) { arrayOf<Any>(it.getString(0) ?: "", it.getString(1) ?: "", it.getInt(2), it.getInt(3)) }.firstOrNull()

    fun counts(): String {
        fun n(sql: String) = q(sql) { it.getLong(0) }.firstOrNull() ?: 0L
        val bus = n("SELECT count(*) FROM line WHERE mode=0"); val rail = n("SELECT count(*) FROM line WHERE mode>0")
        val stops = n("SELECT count(*) FROM stop"); val places = n("SELECT count(*) FROM poi WHERE cat<>${Poi.PLACE}")
        return tr("$bus bus lines · $rail rail/ferry lines · $stops stops · $places places",
            "$bus otobüs hattı · $rail raylı/vapur hattı · $stops durak · $places yer")
    }
}

/** Timetable arithmetic (pure). Seconds count from midnight of the service day. */
object Times {
    /** Bit for a java.util.Calendar day of week (Monday = bit 0). */
    fun dayBit(calDay: Int) = 1 shl ((calDay + 5) % 7)

    /**
     * The next departures at stop [idx] of a variant, from [now] (seconds today). Trips of the
     * previous service day that run past midnight count too. Times may exceed 86 400 (after midnight).
     */
    fun next(trips: IntArray, dists: IntArray, idx: Int, now: Int, today: Int, yesterday: Int, count: Int): List<Int> {
        val total = dists.lastOrNull()?.takeIf { it > 0 } ?: 1
        val frac = (dists.getOrElse(idx) { 0 }).toDouble() / total
        val out = ArrayList<Int>()
        var i = 0
        while (i < trips.size) {
            val days = trips[i]; val start = trips[i + 1]; val dur = trips[i + 2]
            val at = start + (dur * frac).toInt()
            if (days and today != 0 && at >= now - 30) out.add(at)
            if (days and yesterday != 0 && at - 86400 >= now - 30) out.add(at - 86400)
            i += 3
        }
        out.sort()
        return out.take(count)
    }

    /** All departures at stop [idx] on days matching [dayMask]. */
    fun all(trips: IntArray, dists: IntArray, idx: Int, dayMask: Int): List<Int> {
        val total = dists.lastOrNull()?.takeIf { it > 0 } ?: 1
        val frac = (dists.getOrElse(idx) { 0 }).toDouble() / total
        val out = ArrayList<Int>()
        var i = 0
        while (i < trips.size) {
            if (trips[i] and dayMask != 0) out.add(trips[i + 1] + (trips[i + 2] * frac).toInt())
            i += 3
        }
        out.sort()
        return out
    }

    fun clock(sec: Int): String {
        val s = ((sec % 86400) + 86400) % 86400
        return String.format(java.util.Locale.US, "%02d:%02d", s / 3600, (s / 60) % 60)
    }

    fun mins(sec: Int, now: Int): String {
        val m = (sec - now) / 60
        return if (m <= 0) tr("now", "şimdi") else if (m < 60) tr("$m min", "$m dk") else clock(sec)
    }
}
