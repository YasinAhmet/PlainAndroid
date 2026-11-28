package app.localmusic

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Where each song / video was left (3.3; since 3.8 played again it simply continues there). Written when the player
 * pauses, seeks, loads another song or the app leaves the screen — never by a timer. Finished songs (or ones barely
 * started) keep nothing. Keyed by MediaStore id; at most [MAX] entries, oldest dropped.
 *
 * Shared with the Youtube tab (same app, same "resume" prefs, values "pos,dur,at"): a Plain Youtube download is also
 * stored under its video id, so a video left in the Youtube tab continues in Music and the other way round.
 */
object SongResume {
    const val MAX = 800
    const val MIN_START_MS = 5_000L // less than this played: nothing to remember
    const val END_MS = 15_000L // closer than this to the end: finished, next time from the start
    private lateinit var prefs: SharedPreferences
    private var app: Context? = null
    /** MediaStore id -> Plain Youtube video id (files/downloaded.txt), read again only after the file changed. */
    private var videoIds: Map<Long, String> = emptyMap()
    private var videoIdsStamp = -1L

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        app = ctx.applicationContext
        prefs = ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE)
    }

    fun worthKeeping(posMs: Long, durMs: Long): Boolean = posMs >= MIN_START_MS && (durMs <= 0 || durMs - posMs >= END_MS)

    /** (pos, dur, at) from "pos,dur,at" (the Youtube tab's format); the 3.3 "pos,at" is read too. */
    fun decode(v: String?): Triple<Long, Long, Long>? {
        val p = v?.split(',') ?: return null
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        return if (p.size >= 3) Triple(pos, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
        else Triple(pos, 0L, p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    @Synchronized
    private fun videoIdOf(id: Long): String? {
        val f = File(app?.filesDir ?: return null, LinkedPlaylists.SAVED)
        val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp != videoIdsStamp) {
            videoIdsStamp = stamp
            videoIds = try { if (stamp > 0) LinkedPlaylists.savedIds(f.readText()).entries.associate { (v, m) -> m to v } else emptyMap() }
                catch (_: Exception) { emptyMap() }
        }
        return videoIds[id]
    }

    private fun keys(id: Long): List<String> = listOfNotNull(id.toString(), videoIdOf(id))

    /** Position to continue [s] from, or 0 (the newest of its keys). Opened files (negative ids) aren't remembered. */
    fun get(s: Song): Long {
        if (!::prefs.isInitialized || s.id < 0) return 0
        val best = keys(s.id).mapNotNull { decode(prefs.getString(it, null)) }.maxByOrNull { it.third } ?: return 0
        return pick(best, s.durationMs)
    }

    /**
     * The place to continue from, given a stored (pos, dur, at) and the song's length now. MediaStore leaves DURATION 0
     * for some files: then the length stored with the place (the player's) decides whether it was played to the end.
     */
    fun pick(stored: Triple<Long, Long, Long>, durMs: Long): Long {
        val len = durMs.takeIf { it > 0 } ?: stored.second
        return if (worthKeeping(stored.first, len)) stored.first else 0
    }

    /** Where loading a song starts: [at] unless it is not inside the song (unknown length: trusted). */
    fun startAt(at: Long, lenMs: Long): Long = if (at <= 0 || (lenMs > 0 && at >= lenMs)) 0 else at

    fun put(id: Long, posMs: Long, durMs: Long) {
        if (!::prefs.isInitialized || id < 0) return
        val ks = keys(id)
        val e = prefs.edit()
        if (!worthKeeping(posMs, durMs)) { ks.forEach { if (prefs.contains(it)) e.remove(it) }; e.apply(); return }
        val v = "$posMs,$durMs,${System.currentTimeMillis()}"
        ks.forEach { e.putString(it, v) }
        val all = prefs.all
        val over = all.size + ks.count { it !in all } - MAX
        if (over > 0) all.entries.filter { it.key !in ks }.sortedBy { decode(it.value as? String)?.third ?: 0L }
            .take(over).forEach { e.remove(it.key) }
        e.apply()
    }

    fun clear(id: Long) = put(id, 0, 0)

    /** "12:34" / "1:02:03". */
    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
