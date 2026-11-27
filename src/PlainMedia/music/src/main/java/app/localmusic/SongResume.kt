package app.localmusic

import android.content.Context
import android.content.SharedPreferences

/**
 * 3.3: where each song / video was left, so tapping it again asks "Continue from last session?". Written when the
 * player pauses, seeks or loads another song — never by a timer. Finished songs (or ones barely started) keep
 * nothing. Keyed by MediaStore id; at most [MAX] entries, oldest dropped.
 */
object SongResume {
    const val MAX = 500
    const val MIN_MS = 15_000L // closer than this to the start or the end: start over, don't ask
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE) }

    fun worthAsking(posMs: Long, durMs: Long): Boolean = posMs >= MIN_MS && (durMs <= 0 || durMs - posMs >= MIN_MS)

    /** Position to offer for [s], or 0. Opened files (negative ids) aren't remembered. */
    fun get(s: Song): Long {
        if (!::prefs.isInitialized || s.id < 0) return 0
        val v = prefs.getString(s.id.toString(), null)?.split(',') ?: return 0
        val pos = v.getOrNull(0)?.toLongOrNull() ?: return 0
        return if (worthAsking(pos, s.durationMs)) pos else 0
    }

    fun put(id: Long, posMs: Long, durMs: Long) {
        if (!::prefs.isInitialized || id < 0) return
        val k = id.toString()
        if (!worthAsking(posMs, durMs)) { if (prefs.contains(k)) prefs.edit().remove(k).apply(); return }
        val e = prefs.edit().putString(k, "$posMs,${System.currentTimeMillis()}")
        val all = prefs.all
        if (all.size > MAX) all.entries.sortedBy { (it.value as? String)?.substringAfter(',')?.toLongOrNull() ?: 0L }
            .take(all.size - MAX).forEach { e.remove(it.key) }
        e.apply()
    }

    fun clear(id: Long) = put(id, 0, 0)

    /** "12:34" / "1:02:03". */
    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
