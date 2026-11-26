package app.trekmap.trail

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File

/** Map layers. Same sources as the desktop Trekmap (no Google tiles: their terms forbid bulk download). */
enum class Layer(
    val label: String,
    private val template: String,
    private val subs: String,
    val maxZ: Int,
    val avgKb: Int,
    val threads: Int,
    val attribution: String,
    /** For overlay layers: the lowest zoom that has tiles. */
    val minOverlayZ: Int = 0,
) {
    TOPO(
        "Topo", "https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", "abc", 17, 40, 2,
        "© OpenStreetMap contributors · © OpenTopoMap (CC-BY-SA)",
    ),
    SAT(
        "Satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}?blankTile=false", "", 19, 22, 4,
        "Imagery © Esri, Maxar, Earthstar Geographics",
    ),
    DEM(
        "Elevation", "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png", "", 15, 90, 4,
        "Elevation: Mapzen terrain tiles",
    ),
    /** Private-land overlay: rendered on the phone from OSM + ESA WorldCover (see Land), never fetched as tiles. */
    LAND(
        "Private land", "", "", 16, 0, 1,
        "Land: © OpenStreetMap · ESA WorldCover 2021", minOverlayZ = 10,
    );

    fun url(z: Int, x: Int, y: Int): String {
        val s = if (subs.isEmpty()) "" else subs[(x + y) % subs.length].toString()
        return template.replace("{z}", z.toString()).replace("{x}", x.toString())
            .replace("{y}", y.toString()).replace("{s}", s)
    }
}

/** Terrain tiles at this zoom (~30 m/pixel, matching SRTM) give elevation without a barometer. */
const val DEM_Z = 12
const val PACK_MIN_Z = 8

/** A downloaded offline area: one SQLite file holding all of its tiles. */
class Pack(
    val id: String,
    var name: String,
    val bbox: BBox,
    val layers: List<Layer>,
    val minZ: Int,
    val maxZ: Int,
    @Volatile var state: String,
    val created: Long,
    /** > 0: only tiles within this many metres of the pack's trail are downloaded (see Corridor). */
    val corridorM: Double = 0.0,
    /** Satellite tiles from this Esri Wayback release template ("" = current imagery), captured at [satMs]. */
    val satUrl: String = "",
    val satMs: Long = 0L,
    /** Private-land layer: 0 = not wanted, 1 = wanted (built after the tiles), 2 = built. */
    @Volatile var land: Int = 0,
) {
    fun url(l: Layer, z: Int, x: Int, y: Int): String =
        if (l == Layer.SAT && satUrl.isNotEmpty()) Wayback.tileUrl(satUrl, z, x, y) else l.url(z, x, y)

    /** Layers downloaded as tiles. */
    val fetchLayers: List<Layer> = layers + Layer.DEM
    /** Every layer this pack can hold (the land layer is rendered, not fetched). */
    val allLayers: List<Layer> get() = if (land > 0) fetchLayers + Layer.LAND else fetchLayers

    fun zRange(l: Layer): IntRange = when (l) {
        Layer.DEM -> DEM_Z..DEM_Z
        Layer.LAND -> l.minOverlayZ..l.maxZ
        else -> minZ..minOf(maxZ, l.maxZ)
    }

    fun covers(l: Layer, z: Int, x: Int, y: Int): Boolean =
        (l in fetchLayers || (l == Layer.LAND && land > 0)) && z in zRange(l) &&
            x >= Geo.tileX(bbox.w, z) && x <= Geo.tileX(bbox.e, z) &&
            y >= Geo.tileY(bbox.n, z) && y <= Geo.tileY(bbox.s, z)

    fun toJson(): String = JSONObject()
        .put("name", name).put("bbox", bbox.encode()).put("layers", layers.joinToString(",") { it.name })
        .put("minZ", minZ).put("maxZ", maxZ).put("state", state).put("created", created).put("corridorM", corridorM)
        .put("satUrl", satUrl).put("satMs", satMs).put("land", land)
        .toString()

    companion object {
        fun fromJson(id: String, s: String): Pack? = try {
            val j = JSONObject(s)
            Pack(
                id, j.getString("name"), BBox.decode(j.getString("bbox"))!!,
                j.getString("layers").split(',').filter { it.isNotEmpty() }.map { Layer.valueOf(it) },
                j.getInt("minZ"), j.getInt("maxZ"), j.optString("state", "paused"), j.optLong("created"),
                j.optDouble("corridorM", 0.0),
                j.optString("satUrl", ""), j.optLong("satMs", 0L), j.optInt("land", 0),
            )
        } catch (e: Exception) {
            null
        }
    }
}

object Packs {
    lateinit var dir: File
        private set
    private val dbs = HashMap<String, SQLiteDatabase>()
    @Volatile private var cached: List<Pack>? = null

    fun init(ctx: Context) { dir = File(ctx.filesDir, "maps").also { it.mkdirs() } }

    private fun file(id: String) = File(dir, "$id.db")

    private val deleted = HashSet<String>()

    @Synchronized
    fun db(id: String): SQLiteDatabase = dbs.getOrPut(id) {
        if (id in deleted) throw IllegalStateException("map $id was deleted")
        val db = SQLiteDatabase.openDatabase(
            file(id).path, null,
            SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING,
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS tiles(l INTEGER NOT NULL, z INTEGER NOT NULL, x INTEGER NOT NULL, " +
                "y INTEGER NOT NULL, d BLOB, PRIMARY KEY(l,z,x,y)) WITHOUT ROWID"
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT)")
        db
    }

    fun all(): List<Pack> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val list = (dir.listFiles() ?: emptyArray())
                .filter { it.name.endsWith(".db") }
                .mapNotNull { f -> try { readMeta(f.name.removeSuffix(".db")) } catch (e: Exception) { null } }
                .sortedByDescending { it.created }
            cached = list
            return list
        }
    }

    private fun readMeta(id: String): Pack? =
        db(id).rawQuery("SELECT v FROM meta WHERE k='info'", null).use { c ->
            if (c.moveToFirst()) Pack.fromJson(id, c.getString(0)) else null
        }

    fun get(id: String) = all().firstOrNull { it.id == id }

    /** [corridor]: the trail to follow (with [corridorM] > 0), or null to download the whole [bbox]. */
    fun create(
        name: String, bbox: BBox, layers: List<Layer>, maxZ: Int,
        corridor: List<Seg>? = null, corridorM: Double = 0.0, satUrl: String = "", satMs: Long = 0L, land: Boolean = false,
    ): Pack {
        val now = System.currentTimeMillis()
        val cm = if (corridor != null) corridorM else 0.0
        val p = Pack("map_$now", name, bbox, layers, minOf(PACK_MIN_Z, maxZ), maxZ, "queued", now, cm, satUrl, satMs, if (land) 1 else 0)
        saveMeta(p)
        if (corridor != null) {
            db(p.id).execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('corridor',?)", arrayOf<Any>(Corridor.encode(corridor)))
        }
        // keep existing objects (a running download may hold one) instead of re-reading them
        synchronized(this) { cached = listOf(p) + all().filter { it.id != p.id } }
        return p
    }

    fun saveMeta(p: Pack) {
        db(p.id).execSQL("INSERT OR REPLACE INTO meta(k,v) VALUES('info',?)", arrayOf<Any>(p.toJson()))
    }

    /** The trail a corridor pack follows (null for whole-area packs). */
    fun corridor(p: Pack): List<Seg>? {
        if (p.corridorM <= 0) return null
        return db(p.id).rawQuery("SELECT v FROM meta WHERE k='corridor'", null).use { c ->
            if (c.moveToFirst()) Corridor.decode(c.getString(0)) else null
        }
    }

    /** Tiles to fetch at zoom [z]: a corridor grid, or null meaning the whole bounding box. */
    fun grid(p: Pack, segs: List<Seg>?, z: Int): TileGrid? = segs?.let { Corridor.grid(it, p.corridorM, z) }

    fun rename(p: Pack, name: String) {
        p.name = name
        saveMeta(p)
    }

    fun sizeBytes(p: Pack): Long = file(p.id).length() + File(file(p.id).path + "-wal").length()

    fun delete(p: Pack) = synchronized(this) {
        deleted += p.id
        dbs.remove(p.id)?.close()
        SQLiteDatabase.deleteDatabase(file(p.id))
        cached = all().filter { it.id != p.id }
    }

    /** Tile bytes from any pack that covers it; empty array = known missing; null = not downloaded. */
    fun read(l: Layer, z: Int, x: Int, y: Int): ByteArray? {
        for (p in all()) {
            if (!p.covers(l, z, x, y)) continue
            try {
                db(p.id).rawQuery(
                    "SELECT d FROM tiles WHERE l=? AND z=? AND x=? AND y=?",
                    arrayOf(l.ordinal.toString(), z.toString(), x.toString(), y.toString()),
                ).use { c -> if (c.moveToFirst()) return c.getBlob(0) ?: TileProvider.EMPTY }
            } catch (e: Exception) {
                // pack being deleted / closed: treat as absent
            }
        }
        return null
    }

    fun has(l: Layer) = if (l == Layer.LAND) all().any { it.land > 0 } else all().any { l in it.layers }
    fun maxZ(l: Layer): Int = all().filter { l in it.layers }.maxOfOrNull { it.zRange(l).last } ?: -1
}
