package app.plain.maps

import java.io.IOException
import java.io.InputStream

/** An element from an Overpass JSON answer (geometry as lon/lat pairs). */
class OsmEl(
    val type: Char,
    val id: Long,
    val tags: Map<String, String>,
    val lat: Double,
    val lon: Double,
    val geom: DoubleArray?,
    val members: List<Member>?,
) {
    class Member(val type: Char, val ref: Long, val role: String, val geom: DoubleArray?, val lat: Double, val lon: Double)

    operator fun get(k: String) = tags[k]
    /** Unique across element types. */
    val uid get() = id * 4 + when (type) { 'n' -> 0; 'w' -> 1; else -> 2 }
}

object Overpass {
    /**
     * Streams the "elements" of an Overpass JSON answer to [cb]. Throws if Overpass reports a
     * runtime error (a timeout or out-of-memory gives HTTP 200 with a "remark").
     */
    fun parse(input: InputStream, cb: (OsmEl) -> Unit) {
        var remark: String? = null
        var elements = 0
        Json(input).use { j ->
            j.beginObject()
            while (j.hasNext()) {
                when (j.nextName()) {
                    "elements" -> {
                        j.beginArray()
                        while (j.hasNext()) { cb(element(j)); elements++ }
                        j.endArray()
                    }
                    "remark" -> remark = j.nextStringOrNull()
                    else -> j.skipValue()
                }
            }
            j.endObject()
        }
        val r = remark
        if (r != null && (r.contains("runtime error", true) || r.contains("timed out", true) || r.contains("out of memory", true))) {
            throw IOException("Overpass: $r")
        }
    }

    private fun element(j: Json): OsmEl {
        var type = 'n'; var id = 0L; var lat = Double.NaN; var lon = Double.NaN
        var tags: Map<String, String> = emptyMap()
        var geom: DoubleArray? = null
        var members: List<OsmEl.Member>? = null
        j.beginObject()
        while (j.hasNext()) {
            when (j.nextName()) {
                "type" -> type = j.nextString().firstOrNull() ?: 'n'
                "id" -> id = j.nextLong()
                "lat" -> lat = j.nextDouble()
                "lon" -> lon = j.nextDouble()
                "center" -> { val c = latLon(j); lat = c[0]; lon = c[1] }
                "tags" -> tags = j.readStringMap()
                "geometry" -> geom = geometry(j)
                "members" -> {
                    val ms = ArrayList<OsmEl.Member>()
                    j.beginArray()
                    while (j.hasNext()) ms.add(member(j))
                    j.endArray()
                    members = ms
                }
                else -> j.skipValue()
            }
        }
        j.endObject()
        return OsmEl(type, id, tags, lat, lon, geom, members)
    }

    private fun member(j: Json): OsmEl.Member {
        var type = 'n'; var ref = 0L; var role = ""; var lat = Double.NaN; var lon = Double.NaN
        var geom: DoubleArray? = null
        j.beginObject()
        while (j.hasNext()) {
            when (j.nextName()) {
                "type" -> type = j.nextString().firstOrNull() ?: 'n'
                "ref" -> ref = j.nextLong()
                "role" -> role = j.nextString()
                "lat" -> lat = j.nextDouble()
                "lon" -> lon = j.nextDouble()
                "geometry" -> geom = geometry(j)
                else -> j.skipValue()
            }
        }
        j.endObject()
        return OsmEl.Member(type, ref, role, geom, lat, lon)
    }

    private fun latLon(j: Json): DoubleArray {
        val r = doubleArrayOf(Double.NaN, Double.NaN)
        j.beginObject()
        while (j.hasNext()) {
            when (j.nextName()) {
                "lat" -> r[0] = j.nextDouble()
                "lon" -> r[1] = j.nextDouble()
                else -> j.skipValue()
            }
        }
        j.endObject()
        return r
    }

    /** [{lat,lon}, …] → lon/lat pairs; null entries (outside a clip box) are dropped. */
    private fun geometry(j: Json): DoubleArray? {
        if (j.peek() == Json.Kind.NULL) { j.skipValue(); return null }
        val out = DoubleList(32)
        j.beginArray()
        while (j.hasNext()) {
            if (j.peek() == Json.Kind.NULL) { j.skipValue(); continue }
            val p = latLon(j)
            if (!p[0].isNaN() && !p[1].isNaN()) out.add(p[1], p[0])
        }
        j.endArray()
        return out.toArray()
    }
}
