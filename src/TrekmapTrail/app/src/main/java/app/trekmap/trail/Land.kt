package app.trekmap.trail

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * "Likely private" land for the optional overlay. There is no public, reusable ownership map of Turkey
 * (TKGM parcel data is not open for bulk use), so this is built from proxies:
 *  - OpenStreetMap: fields, orchards, villages, farmyards, houses, gardens, private areas, military
 *    zones, fences and walls, private roads (Overpass, once per map pack, Wi-Fi only);
 *  - ESA WorldCover 2021 (10 m satellite land cover): cropland and built-up areas, which fills the
 *    many fields OSM doesn't have (see [WorldCover]).
 * The result is rendered into transparent tiles (see LandRender) and stored in the pack, so the
 * Trail side only reads tiles, like any other map layer.
 */
enum class LandKind { FIELD, HOME, MILITARY }

enum class LineKind { FENCE, PRIVATE_WAY }

/** An area: one or more rings (filled even-odd, so inner rings are holes), in normalised Mercator x,y pairs. */
class LandPoly(val kind: LandKind, val rings: List<DoubleArray>, val building: Boolean = false) {
    val w: Double; val n: Double; val e: Double; val s: Double
    init {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (r in rings) for (i in r.indices step 2) {
            a = minOf(a, r[i]); c = maxOf(c, r[i]); b = minOf(b, r[i + 1]); d = maxOf(d, r[i + 1])
        }
        w = a; n = b; e = c; s = d
    }
}

class LandLine(val kind: LineKind, val xy: DoubleArray) {
    val w: Double; val n: Double; val e: Double; val s: Double
    init {
        var a = Double.MAX_VALUE; var b = Double.MAX_VALUE; var c = -Double.MAX_VALUE; var d = -Double.MAX_VALUE
        for (i in xy.indices step 2) { a = minOf(a, xy[i]); c = maxOf(c, xy[i]); b = minOf(b, xy[i + 1]); d = maxOf(d, xy[i + 1]) }
        w = a; n = b; e = c; s = d
    }
}

class LandData(val polys: List<LandPoly>, val lines: List<LandLine>) {
    val buildings get() = polys.count { it.building }
    override fun toString() = "${polys.size} areas (${polys.count { it.kind == LandKind.FIELD }} fields, " +
        "${polys.count { it.kind == LandKind.HOME && !it.building }} homes/yards, $buildings buildings, " +
        "${polys.count { it.kind == LandKind.MILITARY }} military) · ${lines.count { it.kind == LineKind.FENCE }} fences · " +
        "${lines.count { it.kind == LineKind.PRIVATE_WAY }} private roads"
}

object Land {
    /** Overpass servers, tried in order (the main one is usually the most reliable). */
    val MIRRORS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
    )

    private const val FIELDS = "farmland|orchard|vineyard|greenhouse_horticulture|plant_nursery|animal_keeping"
    private const val HOMES = "residential|farmyard|allotments|industrial|commercial|retail|construction|garages"
    private const val LANDUSE = "^($FIELDS|$HOMES|military)$"

    fun query(b: BBox): String {
        val bb = "(${b.s},${b.w},${b.n},${b.e})"
        return "[out:json][timeout:180][maxsize:268435456];(" +
            "way[\"landuse\"~\"$LANDUSE\"]$bb;relation[\"landuse\"~\"$LANDUSE\"][\"type\"=\"multipolygon\"]$bb;" +
            "way[\"military\"]$bb;relation[\"military\"][\"type\"=\"multipolygon\"]$bb;" +
            "way[\"leisure\"=\"garden\"]$bb;way[\"building\"]$bb;" +
            "way[\"access\"~\"^(private|no)$\"]$bb;relation[\"access\"~\"^(private|no)$\"][\"type\"=\"multipolygon\"]$bb;" +
            "way[\"barrier\"~\"^(fence|wall|hedge)$\"]$bb;" +
            ");out geom qt;"
    }

    fun url(mirror: String, b: BBox) = "$mirror?data=" + URLEncoder.encode(query(b), "UTF-8")

    /** What a feature's tags make it (null = not part of the layer). */
    fun areaKind(t: JSONObject?): LandKind? {
        if (t == null) return null
        val lu = t.optString("landuse")
        if (lu == "military" || t.has("military")) return LandKind.MILITARY
        if (lu.isNotEmpty() && lu in HOMES.split('|')) return LandKind.HOME
        if (lu.isNotEmpty() && lu in FIELDS.split('|')) return LandKind.FIELD
        if (t.has("building") && t.optString("building") != "no") return LandKind.HOME
        if (t.optString("leisure") == "garden") return LandKind.HOME
        val acc = t.optString("access")
        if ((acc == "private" || acc == "no") && !t.has("highway") && !t.has("barrier")) return LandKind.HOME
        return null
    }

    fun lineKind(t: JSONObject?): LineKind? {
        if (t == null) return null
        if (t.optString("barrier") in setOf("fence", "wall", "hedge")) return LineKind.FENCE
        val acc = t.optString("access")
        if (t.has("highway") && (acc == "private" || acc == "no")) return LineKind.PRIVATE_WAY
        return null
    }

    private fun geom(a: JSONArray?): DoubleArray? {
        if (a == null || a.length() < 2) return null
        val xy = DoubleArray(a.length() * 2)
        for (i in 0 until a.length()) {
            val p = a.optJSONObject(i) ?: return null // null = node outside the data (rare); skip the way
            xy[2 * i] = Geo.mx(p.getDouble("lon"))
            xy[2 * i + 1] = Geo.my(p.getDouble("lat"))
        }
        return xy
    }

    private fun closed(xy: DoubleArray) = xy.size >= 8 && xy[0] == xy[xy.size - 2] && xy[1] == xy[xy.size - 1]

    /** Parses an Overpass `out geom` answer. */
    fun parse(json: String): LandData {
        val els = JSONObject(json).optJSONArray("elements") ?: JSONArray()
        val polys = ArrayList<LandPoly>()
        val lines = ArrayList<LandLine>()
        for (i in 0 until els.length()) {
            val el = els.getJSONObject(i)
            val tags = el.optJSONObject("tags")
            when (el.optString("type")) {
                "way" -> {
                    val xy = geom(el.optJSONArray("geometry")) ?: continue
                    val lk = lineKind(tags)
                    if (lk != null) { lines += LandLine(lk, xy); continue }
                    val ak = areaKind(tags) ?: continue
                    if (closed(xy)) polys += LandPoly(ak, listOf(xy), building = ak == LandKind.HOME && tags!!.has("building"))
                }
                "relation" -> {
                    val ak = areaKind(tags) ?: continue
                    val members = el.optJSONArray("members") ?: continue
                    val parts = ArrayList<DoubleArray>()
                    for (k in 0 until members.length()) {
                        val m = members.getJSONObject(k)
                        if (m.optString("type") != "way") continue
                        geom(m.optJSONArray("geometry"))?.let { parts += it }
                    }
                    val rings = stitch(parts)
                    if (rings.isNotEmpty()) polys += LandPoly(ak, rings)
                }
            }
        }
        // draw order: fields, then homes, then military on top
        polys.sortBy { it.kind.ordinal }
        return LandData(polys, lines)
    }

    /**
     * Joins multipolygon member ways into closed rings (members come as pieces in any order and
     * direction). Pieces that never close are closed straight, which is right for clipped edges.
     */
    fun stitch(parts: List<DoubleArray>): List<DoubleArray> {
        val rings = ArrayList<DoubleArray>()
        val open = ArrayList<DoubleArray>()
        for (p in parts) if (closed(p)) rings += p else if (p.size >= 4) open += p
        while (open.isNotEmpty()) {
            val cur = DoubleList(64)
            val first = open.removeAt(open.size - 1)
            for (v in first) cur.add(v)
            var grew = true
            while (grew && !(cur.size >= 8 && cur[0] == cur[cur.size - 2] && cur[1] == cur[cur.size - 1])) {
                grew = false
                val ex = cur[cur.size - 2]; val ey = cur[cur.size - 1]
                for (j in open.indices) {
                    val q = open[j]
                    val fwd = q[0] == ex && q[1] == ey
                    val rev = q[q.size - 2] == ex && q[q.size - 1] == ey
                    if (!fwd && !rev) continue
                    open.removeAt(j)
                    if (fwd) for (k in 2 until q.size) cur.add(q[k])
                    else { var k = q.size - 4; while (k >= 0) { cur.add(q[k]); cur.add(q[k + 1]); k -= 2 } }
                    grew = true
                    break
                }
            }
            if (cur.size >= 6) {
                if (!(cur[0] == cur[cur.size - 2] && cur[1] == cur[cur.size - 1])) { cur.add(cur[0]); cur.add(cur[1]) }
                rings += cur.toArray()
            }
        }
        return rings
    }
}
