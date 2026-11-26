package app.trekmap.trail

import android.net.Network
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Esri World Imagery Wayback: every past release of Esri's satellite mosaic, each with a metadata
 * service that says when (and by which satellite, at what resolution) the imagery at a spot was captured.
 * Lets the app pick imagery from the season when forest paths are visible (see Season).
 * All requests go through Net on the Wi-Fi network.
 */
object Wayback {
    private const val CONFIG = "https://s3-us-west-2.amazonaws.com/config.maptiles.arcgis.com/waybackconfig.json"

    class Release(val num: Int, val date: String, val tileUrl: String, val metaUrl: String)

    class Capture(val rel: Release, val ms: Long, val resM: Double, val source: String) {
        fun label(lat: Double) = "${Season.month(ms)} · ${Season.describe(ms, lat)} · ${"%.2f".format(java.util.Locale.US, resM)} m" +
            if (source.isNotBlank()) " · $source" else ""
    }

    @Volatile private var releasesCache: List<Release>? = null
    @Volatile private var layerHint = 4

    fun releases(net: Network): List<Release> {
        releasesCache?.let { return it }
        val j = JSONObject(String(Net.get(net, CONFIG), Charsets.UTF_8))
        val out = ArrayList<Release>()
        for (k in j.keys()) {
            val o = j.getJSONObject(k)
            val date = Regex("(\\d{4}-\\d{2}-\\d{2})").find(o.optString("itemTitle"))?.value ?: continue
            val tile = o.optString("itemURL").replace("{level}", "{z}").replace("{row}", "{y}").replace("{col}", "{x}")
            val meta = o.optString("metadataLayerUrl")
            if (tile.isEmpty() || meta.isEmpty()) continue
            out += Release(k.toIntOrNull() ?: continue, date, tile, meta)
        }
        out.sortByDescending { it.date }
        releasesCache = out
        return out
    }

    /** Capture info of [r] at a point (finest resolution layer that has data there). */
    fun captureAt(net: Network, r: Release, lat: Double, lon: Double): Capture? {
        val order = listOf(layerHint) + listOf(3, 4, 5, 6, 2, 7).filter { it != layerHint }
        for (layer in order) {
            val q = "geometry=" + URLEncoder.encode("$lon,$lat", "UTF-8") +
                "&geometryType=esriGeometryPoint&inSR=4326&spatialRel=esriSpatialRelIntersects" +
                "&outFields=SRC_DATE,SRC_RES,NICE_DESC&returnGeometry=false&f=json"
            val j = JSONObject(String(Net.get(net, "${r.metaUrl}/$layer/query?$q"), Charsets.UTF_8))
            val f = j.optJSONArray("features") ?: continue
            if (f.length() == 0) continue
            val a = f.getJSONObject(0).getJSONObject("attributes")
            val ms = Season.fromYmd(a.optLong("SRC_DATE"))
            if (ms == 0L) continue
            layerHint = layer
            return Capture(r, ms, a.optDouble("SRC_RES", 0.0), a.optString("NICE_DESC", ""))
        }
        return null
    }

    /**
     * Distinct captures at a point, newest first. Walks releases newest → oldest a few at a time and
     * stops once a leaf-off capture is found (plus one more batch, in case an ideal month is close behind).
     */
    fun scan(net: Network, lat: Double, lon: Double, progress: (Int, Int) -> Unit, cancelled: () -> Boolean): List<Capture> {
        val rels = releases(net)
        val found = LinkedHashMap<Long, Capture>()
        val pool = Executors.newFixedThreadPool(6)
        var extraBatches = -1
        try {
            var i = 0
            while (i < rels.size && !cancelled()) {
                val batch = rels.subList(i, minOf(i + 6, rels.size))
                val res = pool.invokeAll(batch.map { r -> Callable { try { captureAt(net, r, lat, lon) } catch (e: Exception) { null } } })
                for (fut in res) {
                    val c = fut.get() ?: continue
                    if (c.ms !in found) found[c.ms] = c // keep the newest release that holds this capture
                }
                i += batch.size
                progress(i, rels.size)
                if (extraBatches < 0 && found.values.any { Season.leafOff(it.ms, lat) }) extraBatches = 3
                if (extraBatches >= 0 && extraBatches-- == 0) break
            }
        } finally {
            pool.shutdownNow()
        }
        return found.values.sortedByDescending { it.ms }
    }

    fun tileUrl(template: String, z: Int, x: Int, y: Int) =
        template.replace("{z}", z.toString()).replace("{x}", x.toString()).replace("{y}", y.toString())
}
