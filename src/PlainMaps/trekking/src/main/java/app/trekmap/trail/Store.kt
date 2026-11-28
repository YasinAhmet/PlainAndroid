package app.trekmap.trail

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Random
import kotlin.math.roundToInt

enum class Kind(val dir: String) { PLAN("plans"), TRACK("tracks") }

class Entry(
    val kind: Kind,
    val id: String,
    val name: String,
    val time: Long,
    val distM: Double,
    val durS: Long,
    val ascM: Double,
    val bbox: BBox?,
)

/** Planned trails and walked trails: one GPX file + a small JSON index file each. */
object Store {
    private lateinit var root: File

    fun init(ctx: Context) { root = ctx.filesDir }

    /** For unit tests: keep the files under [dir]. */
    internal fun initDir(dir: File) { root = dir }

    fun dir(k: Kind) = File(root, k.dir).also { it.mkdirs() }
    fun gpx(k: Kind, id: String) = File(dir(k), "$id.gpx")
    private fun meta(k: Kind, id: String) = File(dir(k), "$id.json")

    fun list(k: Kind): List<Entry> {
        val files = dir(k).listFiles() ?: emptyArray()
        val out = files.filter { it.name.endsWith(".json") }.mapNotNull { read(k, it) }.toMutableList()
        // a GPX whose index file is missing or damaged (the app died between the two writes, power loss…) is
        // still the user's trail: rebuild its index from the GPX instead of hiding it
        val known = out.mapTo(HashSet()) { it.id }
        for (g in files) {
            if (!g.name.endsWith(".gpx")) continue
            val id = g.name.removeSuffix(".gpx")
            if (id !in known && validId(id)) recover(k, id, g)?.let { out += it }
        }
        return out.sortedByDescending { it.time }
    }

    /** Index entry rebuilt from a GPX alone (and saved, so this happens once). */
    private fun recover(k: Kind, id: String, g: File): Entry? = try {
        val r = g.inputStream().use { GpxIo.parse(it) }
        val t0 = r.firstTime()
        var t1 = 0L
        for (s in r.segs) { val t = s.time ?: continue; for (v in t) if (v > t1) t1 = v }
        val e = Entry(
            k, id, r.name?.takeIf { it.isNotBlank() } ?: id, if (t0 > 0) t0 else g.lastModified(), r.lengthM(),
            if (k == Kind.TRACK && t0 > 0 && t1 > t0) (t1 - t0) / 1000 else 0L, r.ascentM(), r.bbox(),
        )
        try { writeMeta(e) } catch (_: Exception) {}
        e
    } catch (ex: Exception) {
        null
    }

    fun get(k: Kind, id: String?): Entry? {
        if (id.isNullOrEmpty() || !validId(id)) return null
        val f = meta(k, id)
        return if (f.exists()) read(k, f) else null
    }

    fun validId(id: String) = id.all { it.isLetterOrDigit() || it == '_' }

    private fun read(k: Kind, f: File): Entry? = try {
        val j = JSONObject(f.readText())
        Entry(
            k, f.name.removeSuffix(".json"), j.optString("name", "Untitled"), j.optLong("time"),
            j.optDouble("dist", 0.0), j.optLong("dur"), j.optDouble("asc", 0.0), BBox.decode(j.optString("bbox")),
        )
    } catch (e: Exception) {
        null
    }

    fun load(e: Entry): Route? = try {
        gpx(e.kind, e.id).inputStream().use { GpxIo.parse(it) }
    } catch (ex: Exception) {
        null
    }

    fun save(
        k: Kind,
        route: Route,
        name: String,
        time: Long = System.currentTimeMillis(),
        durS: Long = 0,
        ascM: Double = route.ascentM(),
        id: String = newId(),
    ): Entry {
        val e = Entry(k, id, name, time, route.lengthM(), durS, ascM, route.bbox())
        try {
            writeGpx(e, route)
            writeMeta(e)
        } catch (ex: Exception) {
            // half a save (storage full…) must not leave a GPX behind that shows up as a trail later
            gpx(k, id).delete(); File(gpx(k, id).path + ".tmp").delete()
            meta(k, id).delete(); File(meta(k, id).path + ".tmp").delete()
            throw ex
        }
        return e
    }

    fun rename(e: Entry, name: String): Entry {
        val r = load(e)
        val n = Entry(e.kind, e.id, name, e.time, e.distM, e.durS, e.ascM, e.bbox)
        if (r != null) writeGpx(n, r)
        writeMeta(n)
        return n
    }

    /** Rewrites a trail's GPX (e.g. after a photo was removed, so its waypoint goes too). */
    fun rewriteGpx(e: Entry) {
        val r = load(e) ?: return
        writeGpx(e, r)
    }

    fun delete(e: Entry) {
        gpx(e.kind, e.id).delete()
        meta(e.kind, e.id).delete()
        Photos.dirOf(e)?.deleteRecursively()
    }

    private fun writeGpx(e: Entry, route: Route) {
        val f = gpx(e.kind, e.id)
        val tmp = File(f.path + ".tmp")
        // photos of a walked trail become waypoints linking to photos/FILE (as in the exported zip)
        val wpts = Photos.list(Photos.dirOf(e)).filter { it.hasPosition }.map {
            GpxIo.Wpt(it.lat, it.lon, it.ele, it.time, "Photo ${Fmt.date(it.time)}", "photos/${it.file}")
        }
        tmp.outputStream().use { GpxIo.write(it, route, e.name, desc(e), wpts) }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw java.io.IOException("could not write ${f.name}")
        }
    }

    // JSON can't hold NaN / Infinity (put() throws): one bad value must not make a trail unsavable
    private fun fin(v: Double) = if (v.isFinite()) v else 0.0

    private fun writeMeta(e: Entry) {
        Rec.atomicWrite(
            meta(e.kind, e.id),
            JSONObject()
                .put("name", e.name).put("time", e.time).put("dist", fin(e.distM))
                .put("dur", e.durS).put("asc", fin(e.ascM)).put("bbox", e.bbox?.encode() ?: "")
                .toString(),
        )
    }

    private fun desc(e: Entry): String? =
        if (e.kind == Kind.TRACK) "${Fmt.dist(e.distM)} · ${Fmt.dur(e.durS)} · ascent ${fin(e.ascM).roundToInt()} m" else null

    fun newId() =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + Integer.toHexString(Random().nextInt(0xF000) + 0x1000)
}
