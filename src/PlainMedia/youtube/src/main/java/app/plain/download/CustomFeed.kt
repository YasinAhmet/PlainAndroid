package app.plain.download

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One user-made feed (Custom Feed tab): a name, its channels, and whether watched / opened videos are hidden. */
data class CFeed(val id: Long, val name: String, val channels: List<Follow>, val unwatchedOnly: Boolean = false)

/** Pure rules of the Custom Feed tab (unit-tested): storage format, merge / order, the "only unwatched" filter. */
object CustomFeeds {
    const val VERSION = 1
    /** Videos per feed strip (newest first). */
    const val STRIP = 30
    const val MAX_FEEDS = 200
    const val MAX_CHANNELS = 500
    const val MAX_NAME = 80
    private val UC = Regex("UC[A-Za-z0-9_-]{22}")

    fun cleanName(s: String): String = s.replace(Regex("\\s+"), " ").trim().take(MAX_NAME)

    /** {"version":1,"feeds":[{"id","name","unwatched","channels":[{"id","title"}]}]} */
    fun encode(feeds: List<CFeed>): String {
        val a = JSONArray()
        for (f in feeds) a.put(JSONObject().put("id", f.id).put("name", f.name).put("unwatched", f.unwatchedOnly)
            .put("channels", JSONArray().apply { f.channels.forEach { put(JSONObject().put("id", it.id).put("title", it.title)) } }))
        return JSONObject().put("version", VERSION).put("feeds", a).toString()
    }

    /**
     * Never throws: a damaged or truncated file gives what can be read (or nothing). A bare array (no version) and a
     * newer version are read the same way; unknown fields are ignored. Bad channel ids, duplicate channels and
     * duplicate feed ids are dropped; a missing name becomes "Feed N".
     */
    fun decode(s: String?): List<CFeed> {
        if (s.isNullOrBlank()) return emptyList()
        val arr = try {
            val t = s.trim()
            if (t.startsWith("[")) JSONArray(t) else JSONObject(t).optJSONArray("feeds") ?: return emptyList()
        } catch (_: Exception) { return emptyList() }
        val out = ArrayList<CFeed>()
        val ids = HashSet<Long>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_FEEDS) break
            val o = arr.optJSONObject(i) ?: continue
            var id = o.optLong("id", 0L)
            if (id <= 0 || id in ids) id = (ids.maxOrNull() ?: 0L) + 1
            ids += id
            val seen = HashSet<String>()
            val chans = ArrayList<Follow>()
            val ca = o.optJSONArray("channels")
            if (ca != null) for (k in 0 until ca.length()) {
                val c = ca.optJSONObject(k)
                val cid = c?.optString("id") ?: ca.optString(k)
                if (!UC.matches(cid) || !seen.add(cid) || chans.size >= MAX_CHANNELS) continue
                chans += Follow(cid, c?.optString("title").orEmpty().ifEmpty { cid })
            }
            val name = cleanName(o.optString("name")).ifEmpty { "Feed ${out.size + 1}" }
            out += CFeed(id, name, chans, o.optBoolean("unwatched", false))
        }
        return out
    }

    /**
     * The feed's strip: videos of its channels from [pool] (any order, duplicates allowed), newest on the left,
     * undated ones last, at most [limit]. [watched] (only-unwatched feeds) is applied before the limit, so the strip
     * still fills up with unwatched videos.
     */
    fun strip(feed: CFeed, pool: List<Item>, watched: (String) -> Boolean = { false }, limit: Int = STRIP): List<Item> {
        val chans = feed.channels.mapTo(HashSet()) { it.id }
        if (chans.isEmpty()) return emptyList()
        val seen = HashSet<String>()
        val mine = pool.filter { it.isVideo && it.channelId in chans && seen.add(it.id) }
        val shown = if (feed.unwatchedOnly) unwatched(mine, watched) else mine
        return shown.sortedWith(compareByDescending<Item> { it.published > 0 }.thenByDescending { it.published }).take(limit.coerceAtLeast(0))
    }

    fun unwatched(items: List<Item>, watched: (String) -> Boolean): List<Item> = items.filter { !watched(it.id) }

    /** Every channel of every feed, once (what Refresh fetches). */
    fun allChannels(feeds: List<CFeed>): List<Follow> = feeds.flatMap { it.channels }.distinctBy { it.id }

    fun newId(feeds: List<CFeed>, now: Long): Long = maxOf(now, (feeds.maxOfOrNull { it.id } ?: 0L) + 1)

    fun add(feeds: List<CFeed>, name: String, channels: List<Follow>, now: Long): Pair<List<CFeed>, CFeed> {
        val f = CFeed(newId(feeds, now), cleanName(name).ifEmpty { "Feed ${feeds.size + 1}" }, channels.distinctBy { it.id }.take(MAX_CHANNELS))
        return (feeds + f) to f
    }

    fun update(feeds: List<CFeed>, id: Long, fn: (CFeed) -> CFeed): List<CFeed> = feeds.map { if (it.id == id) fn(it) else it }

    fun withChannels(f: CFeed, add: List<Follow>): CFeed = f.copy(channels = (f.channels + add).distinctBy { it.id }.take(MAX_CHANNELS))

    fun withoutChannels(f: CFeed, remove: Set<String>): CFeed = f.copy(channels = f.channels.filter { it.id !in remove })

    /** Move feed [id] by [delta] places (clamped); unknown id = unchanged. */
    fun move(feeds: List<CFeed>, id: Long, delta: Int): List<CFeed> {
        val i = feeds.indexOfFirst { it.id == id }
        if (i < 0) return feeds
        val j = (i + delta).coerceIn(0, feeds.size - 1)
        if (i == j) return feeds
        val l = feeds.toMutableList()
        l.add(j, l.removeAt(i))
        return l
    }

    /** Page math for the feed list: [count] rows (+1 for "New feed") in pages of [per]. */
    fun pages(count: Int, per: Int): Int = maxOf(1, (count + 1 + per - 1) / per)
}

/** files/custom_feeds.json (the feeds) and files/custom_feeds_cache.json (their channels' RSS), written atomically. */
object CustomFeedStore {
    private var file: AtomicFile? = null
    private var cacheFile: AtomicFile? = null
    private var feeds: List<CFeed>? = null
    private var cache: FeedCache? = null

    fun init(ctx: Context) {
        if (file != null) return
        file = AtomicFile(File(ctx.filesDir, "custom_feeds.json"))
        cacheFile = AtomicFile(File(ctx.filesDir, "custom_feeds_cache.json"))
    }

    fun feeds(): List<CFeed> = feeds ?: run {
        val f = file ?: return emptyList()
        val text = try { String(f.readFully(), Charsets.UTF_8) } catch (_: Exception) { "" }
        val list = CustomFeeds.decode(text)
        // unreadable but not empty: keep a copy before anything overwrites it
        if (list.isEmpty() && text.isNotBlank()) try { File(f.baseFile.path + ".bad").writeText(text) } catch (_: Exception) {}
        list.also { feeds = it }
    }

    fun setFeeds(l: List<CFeed>) {
        feeds = l
        file?.let { write(it, CustomFeeds.encode(l)) }
    }

    fun cache(): FeedCache = cache ?: (try { FeedCache.decode(String(cacheFile!!.readFully(), Charsets.UTF_8)) } catch (_: Exception) { FeedCache.EMPTY })
        .also { cache = it }

    fun setCache(c: FeedCache, write: Boolean) {
        cache = c
        if (write) cacheFile?.let { write(it, c.encode()) }
    }

    private fun write(f: AtomicFile, s: String) {
        val out = try { f.startWrite() } catch (_: Exception) { return }
        try { out.write(s.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (_: Exception) { f.failWrite(out) }
    }
}

/**
 * Videos opened anywhere in the Youtube tab (the player, the Download card), for "only unwatched" feeds. Local, at
 * most [MAX] ids, oldest dropped. A video with a resume position counts as watched too.
 */
object Visited {
    const val MAX = 5000
    private val ID = Regex("[A-Za-z0-9_-]{11}")
    private var prefs: SharedPreferences? = null
    private val ids = LinkedHashSet<String>()
    /** Bumped on every change (screens re-filter when it moved). */
    @Volatile var version = 0
        private set

    @Synchronized fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("visited", Context.MODE_PRIVATE)
        prefs = p
        ids += parse(p.getString("ids", null))
    }

    fun parse(s: String?): List<String> = s.orEmpty().split('\n').filter { ID.matches(it) }.distinct().takeLast(MAX)

    @Synchronized fun mark(id: String) {
        if (!ID.matches(id)) return
        ids.remove(id); ids += id
        while (ids.size > MAX) ids.remove(ids.first())
        version++
        prefs?.edit()?.putString("ids", ids.joinToString("\n"))?.apply()
    }

    @Synchronized fun unmark(id: String) {
        if (!ids.remove(id)) return
        version++
        prefs?.edit()?.putString("ids", ids.joinToString("\n"))?.apply()
    }

    @Synchronized fun has(id: String) = id in ids

    /** Opened, or left in the middle (Resume). */
    fun watched(id: String) = has(id) || Resume.get(listOf(id)) > 0
}
