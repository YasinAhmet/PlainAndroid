package app.plain.download

import android.content.Context
import android.content.SharedPreferences

/**
 * 3.3: where each video / song was left ("Continue from last session?" when it is opened again). Written when the
 * player pauses, closes or moves to another item — never by a timer. Finished items (or ones barely started) keep
 * nothing. At most [MAX] entries, oldest dropped.
 */
object Resume {
    const val MAX = 500
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE) }

    /** Position (ms) to offer for [key], or 0. */
    fun get(key: String): Int {
        if (!::prefs.isInitialized || key.isEmpty()) return 0
        val (pos, dur) = ResumeRule.decode(prefs.getString(key, null)) ?: return 0
        return if (ResumeRule.worthAsking(pos, dur)) pos.toInt() else 0
    }

    fun put(key: String, posMs: Long, durMs: Long) {
        if (!::prefs.isInitialized || key.isEmpty()) return
        if (!ResumeRule.worthAsking(posMs, durMs)) { clear(key); return }
        val e = prefs.edit().putString(key, ResumeRule.encode(posMs, durMs, System.currentTimeMillis()))
        val all = prefs.all
        if (all.size > MAX) ResumeRule.oldest(all.mapValues { it.value as? String }, all.size - MAX).forEach { e.remove(it) }
        e.apply()
    }

    fun clear(key: String) { if (::prefs.isInitialized && key.isNotEmpty() && prefs.contains(key)) prefs.edit().remove(key).apply() }
}

/** Pure rules (unit-tested). */
object ResumeRule {
    const val MIN_MS = 15_000L // less than this from the start or from the end: start over, don't ask

    fun worthAsking(posMs: Long, durMs: Long): Boolean = posMs >= MIN_MS && (durMs <= 0 || durMs - posMs >= MIN_MS)

    fun encode(pos: Long, dur: Long, at: Long) = "$pos,$dur,$at"

    /** (position, duration) or null. */
    fun decode(s: String?): Pair<Long, Long>? {
        val p = s?.split(',') ?: return null
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        return pos to (p.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    fun at(s: String?): Long = s?.split(',')?.getOrNull(2)?.toLongOrNull() ?: 0L

    /** The [n] keys written longest ago. */
    fun oldest(all: Map<String, String?>, n: Int): List<String> = if (n <= 0) emptyList() else all.entries.sortedBy { at(it.value) }.take(n).map { it.key }

    /** "12:34" / "1:02:03" for the question. */
    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
