package app.plain.download

import android.content.Context
import android.content.SharedPreferences

/**
 * Where each video / song was left (3.3; since 3.8 opened again it simply continues there). Written when the player
 * pauses, seeks away, closes, moves to another item or the app leaves the screen — never by a timer. Finished items
 * (or ones barely started) keep nothing. At most [MAX] entries, oldest dropped.
 *
 * Shared with the Music tab (same app, same "resume" prefs): an item can have several keys — its video id, and the
 * MediaStore id of its saved file — and the newest of them wins, so a video left in one tab continues in the other.
 */
object Resume {
    const val MAX = 800
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) { if (!::prefs.isInitialized) prefs = ctx.applicationContext.getSharedPreferences("resume", Context.MODE_PRIVATE) }

    /**
     * Position (ms) to continue from for an item known by [keys], or 0. [longOnly]: only for long items (a queue moving
     * on by itself continues a video or a podcast, but starts a song over).
     */
    fun get(keys: List<String>, longOnly: Boolean = false): Int {
        if (!::prefs.isInitialized) return 0
        val best = keys.filter { it.isNotEmpty() }.mapNotNull { ResumeRule.decodeFull(prefs.getString(it, null)) }.maxByOrNull { it.third } ?: return 0
        if (longOnly && best.second in 1 until ResumeRule.LONG_MS) return 0
        return if (ResumeRule.worthKeeping(best.first, best.second)) best.first.toInt() else 0
    }

    fun put(keys: List<String>, posMs: Long, durMs: Long) {
        if (!::prefs.isInitialized) return
        val ks = keys.filter { it.isNotEmpty() }.distinct()
        if (ks.isEmpty()) return
        if (!ResumeRule.worthKeeping(posMs, durMs)) { clear(ks); return }
        val v = ResumeRule.encode(posMs, durMs, System.currentTimeMillis())
        val e = prefs.edit()
        ks.forEach { e.putString(it, v) }
        val all = prefs.all
        val over = all.size + ks.count { it !in all } - MAX
        if (over > 0) ResumeRule.oldest(all.filterKeys { it !in ks }.mapValues { it.value as? String }, over).forEach { e.remove(it) }
        e.apply()
    }

    fun clear(keys: List<String>) {
        if (!::prefs.isInitialized) return
        val ks = keys.filter { it.isNotEmpty() && prefs.contains(it) }
        if (ks.isNotEmpty()) prefs.edit().apply { ks.forEach { remove(it) } }.apply()
    }
}

/** Pure rules (unit-tested). */
object ResumeRule {
    const val MIN_START_MS = 5_000L // less than this played: nothing to remember
    const val END_MS = 15_000L // closer than this to the end: finished, next time from the start
    const val LONG_MS = 10 * 60_000L // a queue moving on by itself continues only items at least this long

    fun worthKeeping(posMs: Long, durMs: Long): Boolean = posMs >= MIN_START_MS && (durMs <= 0 || durMs - posMs >= END_MS)

    fun encode(pos: Long, dur: Long, at: Long) = "$pos,$dur,$at"

    /** (position, duration) or null. */
    fun decode(s: String?): Pair<Long, Long>? = decodeFull(s)?.let { it.first to it.second }

    /** (position, duration, written at) or null; the Music tab's 3.3 "pos,at" (no duration) is read too. */
    fun decodeFull(s: String?): Triple<Long, Long, Long>? {
        val p = s?.split(',') ?: return null
        val pos = p.getOrNull(0)?.toLongOrNull() ?: return null
        return when {
            p.size >= 3 -> Triple(pos, p[1].toLongOrNull() ?: 0L, p[2].toLongOrNull() ?: 0L)
            p.size == 2 -> Triple(pos, 0L, p[1].toLongOrNull() ?: 0L)
            else -> Triple(pos, 0L, 0L)
        }
    }

    fun at(s: String?): Long = decodeFull(s)?.third ?: 0L

    /** The [n] keys written longest ago. */
    fun oldest(all: Map<String, String?>, n: Int): List<String> = if (n <= 0) emptyList() else all.entries.sortedBy { at(it.value) }.take(n).map { it.key }

    /** Keys of an item: its video id and, for a saved file, the MediaStore id ("content://media/…/123" → "123"), else the uri. */
    fun keys(videoId: String, uri: String?): List<String> {
        val media = uri?.takeIf { it.startsWith("content://media/") }?.substringAfterLast('/')?.takeIf { it.toLongOrNull() != null }
        return listOfNotNull(videoId.ifEmpty { null }, media, if (videoId.isEmpty() && media == null) uri?.ifEmpty { null } else null)
    }

    /** "12:34" / "1:02:03". */
    fun clock(ms: Long): String {
        val s = ms / 1000; val h = s / 3600; val m = s % 3600 / 60; val sec = s % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(java.util.Locale.US, "%d:%02d", m, sec)
    }
}
