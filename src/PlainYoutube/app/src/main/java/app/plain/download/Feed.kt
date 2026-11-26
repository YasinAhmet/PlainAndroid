package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A followed channel (kept locally; no account). */
data class Follow(val id: String, val title: String, val added: Long = 0)

object Follows {
    private val UC = Regex("UC[A-Za-z0-9_-]{22}")

    /** Add [incoming] to [current] (by id, current wins). Returns the new list (sorted by title) and how many were new. */
    fun merge(current: List<Follow>, incoming: List<Follow>): Pair<List<Follow>, Int> {
        val map = LinkedHashMap<String, Follow>()
        current.forEach { map[it.id] = it }
        var added = 0
        for (f in incoming) if (UC.matches(f.id) && !map.containsKey(f.id)) { map[f.id] = f; added++ }
        return map.values.sortedBy { it.title.lowercase() } to added
    }

    fun encode(list: List<Follow>): String {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("added", it.added)) }
        return a.toString()
    }

    fun decode(s: String): List<Follow> {
        val out = ArrayList<Follow>()
        val a = try { JSONArray(s) } catch (_: Exception) { return out }
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (UC.matches(id)) out += Follow(id, o.optString("title").ifEmpty { id }, o.optLong("added"))
        }
        return out
    }
}

/**
 * Google Takeout import: "YouTube and YouTube Music/subscriptions/subscriptions.csv" (localised folder/file names in
 * other languages), columns "Channel Id,Channel Url,Channel Title". Accepts the csv itself or the Takeout .zip.
 */
object Takeout {
    private val UC = Regex("UC[A-Za-z0-9_-]{22}")
    const val MAX_CSV = 4 shl 20

    /** RFC 4180-ish: quoted fields, "" escapes, commas/newlines inside quotes, CRLF, BOM. */
    fun csvRows(text: String): List<List<String>> {
        val s = text.removePrefix("﻿")
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val f = StringBuilder()
        var q = false
        var i = 0
        var any = false
        while (i < s.length) {
            val c = s[i]
            if (q) {
                if (c == '"') { if (i + 1 < s.length && s[i + 1] == '"') { f.append('"'); i++ } else q = false } else f.append(c)
            } else when (c) {
                '"' -> { q = true; any = true }
                ',' -> { row.add(f.toString()); f.setLength(0); any = true }
                '\r' -> {}
                '\n' -> { row.add(f.toString()); f.setLength(0); if (any || row.size > 1 || row[0].isNotEmpty()) rows.add(row); row = ArrayList(); any = false }
                else -> { f.append(c); any = true }
            }
            i++
        }
        if (any || f.isNotEmpty()) { row.add(f.toString()); rows.add(row) }
        return rows
    }

    /** Channels in a subscriptions csv. Header row (any language) is skipped because it holds no channel id. */
    fun parseCsv(text: String): List<Follow> {
        val out = LinkedHashMap<String, Follow>()
        for (r in csvRows(text)) {
            val id = r.firstNotNullOfOrNull { cell -> UC.find(cell)?.value?.takeIf { cell.trim() == it || cell.contains("/channel/$it") } }
                ?: continue
            // title: the first cell that is neither the id nor a URL (Takeout: third column)
            val title = r.firstOrNull { it.isNotBlank() && !UC.matches(it.trim()) && !it.contains("://") }?.trim().orEmpty()
            out.putIfAbsent(id, Follow(id, title.ifEmpty { id }))
        }
        return out.values.toList()
    }

    /**
     * The best subscriptions csv inside a Takeout zip: a file named *subscriptions*.csv wins; otherwise the csv with the
     * most channel rows (localised Takeouts name it differently, e.g. "abonelikler.csv").
     */
    fun parseZip(input: InputStream): List<Follow> {
        var best: List<Follow> = emptyList()
        var bestNamed = false
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                val name = e.name.substringAfterLast('/').lowercase()
                if (e.isDirectory || !name.endsWith(".csv")) continue
                val bytes = Net.readAll(z, MAX_CSV)
                val list = parseCsv(String(bytes, Charsets.UTF_8))
                val named = "subscription" in name
                if (named && !bestNamed || named == bestNamed && list.size > best.size) { best = list; bestNamed = named }
            }
        }
        return best
    }

    fun isZip(head: ByteArray) = head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2].toInt() == 3 && head[3].toInt() == 4
}

/** YouTube's public channel RSS (15 newest uploads). Parsed with an XmlPullParser (Xml.newPullParser() on Android). */
object Rss {
    fun parse(p: XmlPullParser, input: InputStream, channelId: String): List<Item> {
        p.setInput(input, "UTF-8")
        val out = ArrayList<Item>()
        var feedAuthor = ""
        var inEntry = false
        var inAuthor = false
        var id = ""; var title = ""; var author = ""; var cid = ""; var published = 0L; var views = ""; var short = false
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            val name = p.name?.substringAfter(':')
            if (ev == XmlPullParser.START_TAG) when (name) {
                "entry" -> { inEntry = true; id = ""; title = ""; author = ""; cid = ""; published = 0; views = ""; short = false }
                "link" -> if (inEntry && p.getAttributeValue(null, "href")?.contains("/shorts/") == true) short = true
                "author" -> inAuthor = true
                "videoId" -> if (inEntry) id = p.nextText().trim()
                "channelId" -> if (inEntry) cid = p.nextText().trim()
                "title" -> if (inEntry && title.isEmpty()) title = p.nextText().trim()
                "name" -> if (inAuthor) { val n = p.nextText().trim(); if (inEntry) author = n else feedAuthor = n }
                "published" -> if (inEntry) published = isoMillis(p.nextText().trim())
                "statistics" -> if (inEntry) p.getAttributeValue(null, "views")?.toLongOrNull()?.let { views = viewsText(it) }
            } else if (ev == XmlPullParser.END_TAG) when (name) {
                "author" -> inAuthor = false
                "entry" -> {
                    inEntry = false
                    if (id.length == 11) out += Item(
                        if (short) IKind.SHORT else IKind.VIDEO, id, title, channel = author.ifEmpty { feedAuthor },
                        channelId = cid.takeIf { it.startsWith("UC") } ?: channelId, views = views, thumbId = id, published = published,
                    )
                }
            }
        }
        return out
    }

    /** "2026-11-24T10:34:48+00:00" -> epoch ms (0 if unreadable). */
    fun isoMillis(s: String): Long = try { java.time.OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (_: Exception) { 0 }

    fun viewsText(n: Long): String = when {
        n == 1L -> "1 view"
        n < 1000 -> "$n views"
        n < 1_000_000 -> trim1(n / 1e3) + "K views"
        n < 1_000_000_000 -> trim1(n / 1e6) + "M views"
        else -> trim1(n / 1e9) + "B views"
    }

    private fun trim1(d: Double): String {
        val s = if (d < 10) String.format(java.util.Locale.US, "%.1f", Math.floor(d * 10) / 10) else Math.floor(d).toLong().toString()
        return s.removeSuffix(".0")
    }
}

/**
 * The merged feed of followed channels, cached in files/feed.json so it shows at once (also offline).
 * [fetched]: channel id -> when its RSS was last read, so only stale or newly followed channels are fetched again.
 */
class FeedCache(val items: List<Item>, val fetched: Map<String, Long>) {
    companion object {
        const val MAX_AGE = 30 * 60_000L
        const val MAX_ITEMS = 1500
        val EMPTY = FeedCache(emptyList(), emptyMap())

        fun decode(s: String): FeedCache = try {
            val o = JSONObject(s)
            val f = HashMap<String, Long>()
            o.optJSONObject("fetched")?.let { fo -> fo.keys().forEach { k -> f[k] = fo.optLong(k) } }
            FeedCache(ItemCodec.decode(o.optJSONArray("items") ?: JSONArray()), f)
        } catch (_: Exception) { EMPTY }
    }

    /** Channels to (re)fetch: all when [force], else those never fetched or older than 30 min. */
    fun due(follows: List<Follow>, now: Long, force: Boolean): List<Follow> =
        if (force) follows else follows.filter { now - (fetched[it.id] ?: 0L) >= MAX_AGE || (fetched[it.id] ?: 0L) > now }

    /**
     * Replace one channel's items with a fresh RSS answer. [members] = that channel's members-only uploads (its
     * public UUMO… feed, fetched on a manual Refresh; the channel RSS leaves them out); null = not asked this time,
     * so the ones found earlier stay.
     */
    fun with(channelId: String, fresh: List<Item>, now: Long, members: List<Item>? = null): FeedCache {
        val keep = items.filter { it.channelId != channelId }
        val m = members?.map { it.copy(members = true) } ?: items.filter { it.channelId == channelId && it.members }
        val ids = m.map { it.id }.toSet()
        return FeedCache(sortFeed(keep + m + fresh.filter { it.id !in ids }), fetched + (channelId to now))
    }

    /** A failed fetch still counts as "tried now" (deleted channels don't get hammered); items stay. */
    fun tried(channelId: String, now: Long) = FeedCache(items, fetched + (channelId to now))

    /** Only followed channels' items remain (after an unfollow). */
    fun only(follows: Collection<String>): FeedCache {
        val set = follows.toHashSet()
        return FeedCache(items.filter { it.channelId in set }, fetched.filterKeys { it in set })
    }

    val lastRefresh: Long get() = fetched.values.maxOrNull() ?: 0L

    fun encode(): String {
        val f = JSONObject()
        fetched.forEach { (k, v) -> f.put(k, v) }
        return JSONObject().put("items", ItemCodec.encode(items)).put("fetched", f).toString()
    }

    private fun sortFeed(l: List<Item>) = l.distinctBy { it.id }.sortedByDescending { it.published }.take(MAX_ITEMS)
}

object ItemCodec {
    fun encode(items: List<Item>): JSONArray {
        val a = JSONArray()
        for (i in items) a.put(JSONObject().apply {
            put("k", i.kind.name); put("id", i.id); put("t", i.title)
            if (i.channel.isNotEmpty()) put("c", i.channel)
            i.channelId?.let { put("ci", it) }
            if (i.durationSec > 0) put("d", i.durationSec)
            if (i.views.isNotEmpty()) put("v", i.views)
            if (i.age.isNotEmpty()) put("a", i.age)
            if (i.count.isNotEmpty()) put("n", i.count)
            if (i.handle.isNotEmpty()) put("h", i.handle)
            i.thumbId?.let { put("th", it) }
            if (i.published > 0) put("p", i.published)
            if (i.live) put("l", true)
            if (i.members) put("m", true)
        })
        return a
    }

    fun decode(a: JSONArray): List<Item> {
        val out = ArrayList<Item>()
        for (x in 0 until a.length()) {
            val o = a.optJSONObject(x) ?: continue
            val kind = try { IKind.valueOf(o.optString("k")) } catch (_: Exception) { continue }
            val id = o.optString("id").ifEmpty { continue }
            out += Item(
                kind, id, o.optString("t"), o.optString("c"), o.optString("ci").ifEmpty { null }, o.optLong("d"),
                o.optString("v"), o.optString("a"), o.optString("n"), o.optString("h"), o.optString("th").ifEmpty { null },
                o.optLong("p"), o.optBoolean("l"), o.optBoolean("m"),
            )
        }
        return out
    }
}

/** Bulk enqueue decisions (pure). */
object Bulk {
    const val MAX = 500

    class Plan(val take: List<Item>, val skippedDone: Int, val skippedLive: Int, val overLimit: Int)

    /** Videos to queue: dedup by id, skip live/upcoming and ids already downloaded (or queued) as [kind]. */
    fun plan(items: List<Item>, have: (String) -> Boolean): Plan {
        val vids = items.filter { it.isVideo }.distinctBy { it.id }
        val live = vids.count { it.live }
        val notLive = vids.filter { !it.live }
        val todo = notLive.filter { !have(it.id) }
        val done = notLive.size - todo.size
        return Plan(todo.take(MAX), done, live, maxOf(0, todo.size - MAX))
    }

    fun summary(queued: Int, p: Plan): String {
        val parts = ArrayList<String>()
        parts += "Queued $queued"
        if (p.skippedDone > 0) parts += "skipped ${p.skippedDone} already downloaded or queued"
        if (p.skippedLive > 0) parts += "${p.skippedLive} live/upcoming"
        if (p.overLimit > 0) parts += "${p.overLimit} over the $MAX limit"
        return parts.joinToString(", ")
    }
}

object Ago {
    /** "5 min ago", "3 hours ago", "2 days ago", … for RSS dates (the website's style). */
    fun text(then: Long, now: Long): String {
        if (then <= 0) return ""
        val s = maxOf(0L, (now - then) / 1000)
        fun f(n: Long, unit: String) = "$n $unit${if (n == 1L) "" else "s"} ago"
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> f(s / 3600, "hour")
            s < 7 * 86400 -> f(s / 86400, "day")
            s < 30 * 86400 -> f(s / (7 * 86400), "week")
            s < 365 * 86400 -> f(s / (30 * 86400), "month")
            else -> f(s / (365 * 86400), "year")
        }
    }
}
