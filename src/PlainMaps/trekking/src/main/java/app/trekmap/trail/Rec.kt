package app.trekmap.trail

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The recording in progress on disk (files/rec): current.json (start, plan, pauses, clock anchor) and current.csv
 * (one "p,time,lat,lon,ele" row per stored point, "s" = a new segment). Pure parsing / writing so it can be
 * unit-tested; [Trail] does the rest. Rule: a torn or damaged file never costs the recording.
 */
object Rec {
    class Meta(
        val start: Long,
        val planId: String?,
        val planName: String?,
        val pausedTotal: Long,
        val pausedAt: Long,
        /** [RecTime.base] when written (0 = unknown, e.g. written by an older version). */
        val clockBase: Long = 0L,
        /** Android's boot count when written (-1 = unknown): same boot → [clockBase] still holds. */
        val boot: Int = -1,
    )

    fun encode(m: Meta): String = JSONObject()
        .put("start", m.start).put("planId", m.planId ?: "").put("planName", m.planName ?: "")
        .put("pausedTotal", m.pausedTotal).put("pausedAt", m.pausedAt)
        .put("clockBase", m.clockBase).put("boot", m.boot)
        .toString()

    /** current.json → Meta, or null when it's empty, torn or not ours (then the recording is rebuilt from its points). */
    fun decode(s: String?): Meta? = try {
        val j = JSONObject(s ?: "")
        val start = j.optLong("start", 0L)
        if (start <= 0L) null
        else Meta(
            start, j.optString("planId").ifEmpty { null }, j.optString("planName").ifEmpty { null },
            j.optLong("pausedTotal", 0L).coerceAtLeast(0L), j.optLong("pausedAt", 0L).coerceAtLeast(0L),
            j.optLong("clockBase", 0L), j.optInt("boot", -1),
        )
    } catch (e: Exception) {
        null
    }

    /**
     * current.csv rows → segments. Rows torn by a crash or power loss ("p,17000,41.0"), garbage and impossible
     * positions are skipped; empty segments are dropped.
     */
    fun parse(lines: Sequence<String>): List<Seg> {
        val out = ArrayList<Seg>()
        var la = DoubleList(); var lo = DoubleList(); var el = DoubleList(); var tm = LongList()
        fun flush() {
            if (la.size > 0) out.add(Seg(la.toArray(), lo.toArray(), el.toArray(), tm.toArray()))
            la = DoubleList(); lo = DoubleList(); el = DoubleList(); tm = LongList()
        }
        for (line in lines) {
            if (line == "s") { flush(); continue }
            if (!line.startsWith("p,")) continue
            val p = line.split(',')
            if (p.size < 5) continue
            val t = p[1].toLongOrNull() ?: continue
            val a = p[2].toDoubleOrNull() ?: continue
            val b = p[3].toDoubleOrNull() ?: continue
            if (!validPos(a, b)) continue
            la.add(a); lo.add(b); el.add(p[4].toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN); tm.add(t)
        }
        flush()
        return out
    }

    fun validPos(lat: Double, lon: Double) = lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0

    /** One stored point as a current.csv row (Kotlin's Double.toString: always '.' decimals, whatever the language). */
    fun row(t: Long, lat: Double, lon: Double, ele: Double) = "p,$t,$lat,$lon,$ele\n"

    /** Time of the first point with a time, or 0. */
    fun firstTime(segs: List<Seg>): Long {
        for (s in segs) { val t = s.time ?: continue; for (v in t) if (v > 0) return v }
        return 0L
    }

    /**
     * Writes [text] to [f] through a temporary file and a rename, so a crash or full storage never leaves a half-written
     * file. The temp file is synced before the rename (a few small files per recording: start, pause, resume), so even a
     * sudden power loss leaves the old or the new version, never an empty one.
     */
    fun atomicWrite(f: File, text: String) {
        val tmp = File(f.path + ".tmp")
        java.io.FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            try { out.fd.sync() } catch (_: Exception) {}
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("could not replace ${f.name}")
        }
    }

    /** The file as text, or null if it can't be read. */
    fun readOrNull(f: File): String? = try { if (f.exists()) f.readText() else null } catch (e: Exception) { null }

    /**
     * True if [f] ends in the middle of a row (the process died mid-write): the next row must start on a new
     * line, or it would be glued onto the torn one and both would be lost — or a segment break with them.
     */
    fun endsTorn(f: File): Boolean = try {
        if (!f.exists() || f.length() == 0L) false
        else RandomAccessFile(f, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
    } catch (e: Exception) {
        false
    }
}

/**
 * Trail time that a wall-clock change (network time, a manual change, a time zone) can't bend while the phone stays
 * on: anchored to the wall clock once when the recording starts, then counted with the monotonic [elapsed] clock.
 * After the process restarts on the same boot it keeps the saved anchor; after a reboot it trusts the wall clock again.
 * All values are wall-clock-like milliseconds. Paused time is left out of [movingMs].
 */
class RecTime(private val elapsed: () -> Long, private val wall: () -> Long) {
    /** wall − elapsed at the anchor: [now] = base + elapsed. */
    var base = 0L
        private set
    var start = 0L
        private set
    var pausedTotal = 0L
        private set
    var pausedAt = 0L
        private set
    val paused get() = pausedAt > 0L

    fun now(): Long = base + elapsed()

    /** A new recording starts now. */
    fun begin() {
        base = wall() - elapsed()
        start = now()
        pausedTotal = 0L; pausedAt = 0L
    }

    /** Rebuilt from disk: [sameBoot] = the phone wasn't restarted since [m] was written. */
    fun resume(m: Rec.Meta, sameBoot: Boolean) {
        base = if (sameBoot && m.clockBase != 0L) m.clockBase else wall() - elapsed()
        start = m.start
        pausedTotal = m.pausedTotal.coerceAtLeast(0L)
        pausedAt = m.pausedAt.coerceAtLeast(0L)
    }

    /** Pause / resume; false if nothing changed (already in that state). */
    fun setPaused(on: Boolean): Boolean {
        if (on == paused) return false
        val t = now()
        if (on) pausedAt = t.coerceAtLeast(1L)
        else {
            pausedTotal += (t - pausedAt).coerceAtLeast(0L)
            pausedAt = 0L
        }
        return true
    }

    val movingMs: Long get() {
        if (start <= 0L) return 0L
        val t = now()
        val pausedNow = if (paused) (t - pausedAt).coerceAtLeast(0L) else 0L
        return (t - start - pausedTotal - pausedNow).coerceAtLeast(0L)
    }

    fun reset() { start = 0L; pausedTotal = 0L; pausedAt = 0L }

    fun meta(planId: String?, planName: String?, boot: Int) = Rec.Meta(start, planId, planName, pausedTotal, pausedAt, base, boot)
}
