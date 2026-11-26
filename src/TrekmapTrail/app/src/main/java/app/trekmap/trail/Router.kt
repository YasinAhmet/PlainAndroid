package app.trekmap.trail

import android.net.Network
import org.json.JSONObject

/**
 * Path snapping for planned trails: routes between two taps along OpenStreetMap footpaths and tracks
 * (the same paths OpenTopoMap draws as dashed lines) using the public BRouter service, hiking profile.
 * Wi-Fi only, like every request (see Net).
 */
object Router {
    private const val PROFILE = "hiking-mountain"

    /** The route as one segment with elevation, or throws (no path nearby, no Wi-Fi, server error). */
    fun route(net: Network, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Seg {
        val url = "https://brouter.de/brouter?lonlats=$aLon,$aLat%7C$bLon,$bLat&profile=$PROFILE&alternativeidx=0&format=geojson"
        val j = JSONObject(String(Net.get(net, url), Charsets.UTF_8))
        val coords = j.getJSONArray("features").getJSONObject(0).getJSONObject("geometry").getJSONArray("coordinates")
        val la = DoubleList(coords.length()); val lo = DoubleList(coords.length()); val el = DoubleList(coords.length())
        for (i in 0 until coords.length()) {
            val c = coords.getJSONArray(i)
            lo.add(c.getDouble(0)); la.add(c.getDouble(1))
            el.add(if (c.length() > 2) c.optDouble(2, Double.NaN) else Double.NaN)
        }
        if (la.size < 2) throw IllegalStateException("empty route")
        return Seg(la.toArray(), lo.toArray(), el.toArray())
    }
}

/** User toggles (Settings dialog). */
object Prefs {
    fun snap(a: MainActivity) = a.prefs.getBoolean("snap", true)
    fun compass(a: MainActivity) = a.prefs.getBoolean("compass", false)
    fun stats(a: MainActivity) = a.prefs.getBoolean("stats", false)
    fun dim(a: MainActivity) = a.prefs.getBoolean("dim", false)
    fun land(a: MainActivity) = a.prefs.getBoolean("land", true)
}
