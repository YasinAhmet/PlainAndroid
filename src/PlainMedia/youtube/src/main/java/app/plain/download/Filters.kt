package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import app.plain.lang.tr

/*
 * 2.2 Search and Feed filters. Pure (unit-tested).
 *
 * Server side (YouTube's search "sp" protobuf, verified live 2026-11-26 from a PC, WEB 2.20260708):
 *   SearchParams { 1: sort (3 = view count / "popularity"), 2: Filters { 1: upload date (2 today, 3 week, 4 month,
 *   5 year), 2: type (1 video, 2 channel, 3 playlist), 3: duration (1 under 4 min, 3 = 4-20 min, 2 over 20 min) } }
 *   All combinations of type + date + duration + view-count sort returned matching results. Sort 2 ("upload date")
 *   is IGNORED by YouTube now (answers identical to relevance), so "Newest" sorts the loaded results here instead.
 * Client side: Newest, Hide Shorts, Hide live, Members-only only, Music only ("- Topic" channels), Hide downloaded.
 */

// labels are getters (tr at display time), so a language change needs no restart
enum class SDate(private val en: String, private val trk: String, val v: Int) {
    ANY("Any time", "Her zaman", 0), TODAY("Today", "Bugün", 2), WEEK("This week", "Bu hafta", 3), MONTH("This month", "Bu ay", 4), YEAR("This year", "Bu yıl", 5);
    val label: String get() = tr(en, trk)
}
enum class SDur(private val en: String, private val trk: String, val v: Int) {
    ANY("Any length", "Her süre", 0), SHORT("Under 4 min", "4 dk'dan kısa", 1), MEDIUM("4–20 min", "4–20 dk", 3), LONG("Over 20 min", "20 dk'dan uzun", 2);
    val label: String get() = tr(en, trk)
}
enum class SSort(private val en: String, private val trk: String, val v: Int) {
    RELEVANCE("Relevance", "Alaka düzeyi", 0), NEWEST("Upload date", "Yükleme tarihi", 0), POPULAR("View count", "İzlenme sayısı", 3);
    val label: String get() = tr(en, trk)
}

/** [type]: 0 videos, 1 channels, 2 playlists (the Search tab's type chips). */
data class SearchFilters(
    val type: Int = 0,
    val date: SDate = SDate.ANY,
    val dur: SDur = SDur.ANY,
    val sort: SSort = SSort.RELEVANCE,
    val hideShorts: Boolean = false,
    val hideLive: Boolean = false,
    val membersOnly: Boolean = false,
    val musicOnly: Boolean = false,
    val hideDownloaded: Boolean = false,
) {
    val videos get() = type == 0
    /** Date / duration / newest are video filters; YouTube ignores or rejects them for channels and playlists. */
    val effDate get() = if (videos) date else SDate.ANY
    val effDur get() = if (videos) dur else SDur.ANY
    val effSort get() = if (sort == SSort.NEWEST && !videos) SSort.RELEVANCE else sort

    val client: Boolean get() = hideShorts || hideLive || membersOnly || musicOnly || hideDownloaded || effSort == SSort.NEWEST
    val isDefault get() = this == SearchFilters(type = type)

    /** YouTube's "sp" value (base64 of the protobuf, '=' url-encoded like the website's links). */
    fun sp(): String = SearchParams.encode(effSort.v, effDate.v, type + 1, effDur.v)

    fun encode(): String = JSONObject().put("t", type).put("d", date.name).put("u", dur.name).put("s", sort.name)
        .put("hs", hideShorts).put("hl", hideLive).put("m", membersOnly).put("mu", musicOnly).put("hd", hideDownloaded).toString()

    companion object {
        fun decode(s: String?): SearchFilters = try {
            val o = JSONObject(s ?: "")
            SearchFilters(
                o.optInt("t").coerceIn(0, 2), enumOr(o.optString("d"), SDate.ANY), enumOr(o.optString("u"), SDur.ANY),
                enumOr(o.optString("s"), SSort.RELEVANCE), o.optBoolean("hs"), o.optBoolean("hl"), o.optBoolean("m"), o.optBoolean("mu"), o.optBoolean("hd"),
            )
        } catch (_: Exception) { SearchFilters() }
    }
}

inline fun <reified E : Enum<E>> enumOr(name: String, def: E): E = try { enumValueOf<E>(name) } catch (_: Exception) { def }

object SearchParams {
    private fun varint(out: java.io.ByteArrayOutputStream, v: Int) {
        var x = v
        while (x >= 0x80) { out.write((x and 0x7F) or 0x80); x = x ushr 7 }
        out.write(x)
    }

    fun bytes(sort: Int, date: Int, type: Int, dur: Int): ByteArray {
        val inner = java.io.ByteArrayOutputStream()
        if (date != 0) { inner.write(0x08); varint(inner, date) }
        if (type != 0) { inner.write(0x10); varint(inner, type) }
        if (dur != 0) { inner.write(0x18); varint(inner, dur) }
        val out = java.io.ByteArrayOutputStream()
        if (sort != 0) { out.write(0x08); varint(out, sort) }
        if (inner.size() > 0) { out.write(0x12); varint(out, inner.size()); out.write(inner.toByteArray()) }
        return out.toByteArray()
    }

    fun encode(sort: Int, date: Int, type: Int, dur: Int): String =
        java.util.Base64.getEncoder().encodeToString(bytes(sort, date, type, dur)).replace("=", "%3D")
}

/** Client-side predicates shared by Search and Feed. */
object ItemFilter {
    fun isMusic(i: Item) = i.channel.trim().endsWith(" - Topic") || (i.kind == IKind.CHANNEL && i.title.trim().endsWith(" - Topic"))

    fun keep(i: Item, f: SearchFilters, downloaded: (String) -> Boolean): Boolean {
        if (f.membersOnly && !i.members) return false
        if (f.musicOnly && !isMusic(i)) return false
        if (!i.isVideo) return true
        if (f.hideShorts && i.kind == IKind.SHORT) return false
        if (f.hideLive && i.live) return false
        if (f.hideDownloaded && downloaded(i.id)) return false
        return true
    }

    /** Search: filtered and, for "Upload date", newest first by the "3 days ago" text. */
    fun search(all: List<Item>, f: SearchFilters, downloaded: (String) -> Boolean): List<Item> {
        val l = all.filter { keep(it, f, downloaded) }
        return if (f.effSort == SSort.NEWEST) l.sortedBy { AgoParse.seconds(it.age) ?: Long.MAX_VALUE } else l
    }
}

/** "3 days ago" / "Streamed 2 weeks ago" -> seconds (approximate), null if unreadable. */
object AgoParse {
    private val RX = Regex("(\\d+)\\s+(second|minute|min|hour|day|week|month|year)s?\\s+ago", RegexOption.IGNORE_CASE)
    fun seconds(s: String): Long? {
        val m = RX.find(s) ?: return if (s.contains("just now", true)) 0L else null
        val n = m.groupValues[1].toLong()
        return n * when (m.groupValues[2].lowercase()) {
            "second" -> 1L; "minute", "min" -> 60L; "hour" -> 3600L; "day" -> 86400L; "week" -> 7 * 86400L
            "month" -> 30 * 86400L; else -> 365 * 86400L
        }
    }
}

enum class FAge(private val en: String, private val trk: String, val ms: Long) {
    ALL("Any age", "Tüm zamanlar", 0), DAY("24 hours", "24 saat", 86_400_000L), WEEK("Week", "Hafta", 7 * 86_400_000L), MONTH("Month", "Ay", 30 * 86_400_000L);
    val label: String get() = tr(en, trk)
}
enum class FSort(private val en: String, private val trk: String) {
    NEWEST("Newest", "En yeni"), CHANNEL("By channel", "Kanala göre");
    val label: String get() = tr(en, trk)
}

/** Feed filters, all local over the merged feed. [channels] empty = all followed channels. */
data class FeedFilters(
    val membersOnly: Boolean = false,
    val hideShorts: Boolean = false,
    val hideDownloaded: Boolean = false,
    val musicOnly: Boolean = false,
    val channels: Set<String> = emptySet(),
    val age: FAge = FAge.ALL,
    val sort: FSort = FSort.NEWEST,
) {
    val isDefault get() = this == FeedFilters()

    fun apply(items: List<Item>, now: Long, downloaded: (String) -> Boolean): List<Item> {
        val l = items.filter { i ->
            (!membersOnly || i.members) && (!hideShorts || i.kind != IKind.SHORT) && (!hideDownloaded || !downloaded(i.id)) &&
                (!musicOnly || ItemFilter.isMusic(i)) && (channels.isEmpty() || i.channelId in channels) &&
                (age == FAge.ALL || i.published > 0 && now - i.published <= age.ms)
        }
        return if (sort == FSort.CHANNEL) l.sortedWith(compareBy<Item>({ Browse.fold(it.channel) }, { -it.published })) else l.sortedByDescending { it.published }
    }

    fun encode(): String = JSONObject().put("m", membersOnly).put("hs", hideShorts).put("hd", hideDownloaded).put("mu", musicOnly)
        .put("c", JSONArray(channels.toList())).put("a", age.name).put("s", sort.name).toString()

    companion object {
        fun decode(s: String?): FeedFilters = try {
            val o = JSONObject(s ?: "")
            val c = o.optJSONArray("c")
            FeedFilters(o.optBoolean("m"), o.optBoolean("hs"), o.optBoolean("hd"), o.optBoolean("mu"),
                if (c == null) emptySet() else (0 until c.length()).map { c.getString(it) }.toSet(),
                enumOr(o.optString("a"), FAge.ALL), enumOr(o.optString("s"), FSort.NEWEST))
        } catch (_: Exception) { FeedFilters() }
    }
}

/** Search paging with client filters: keep loading while too few rows pass, up to a page budget. */
object Scan {
    const val WANT = 10
    const val MAX_PAGES = 5

    fun more(passedThisRound: Int, pagesThisRound: Int, hasToken: Boolean, clientFilters: Boolean) =
        clientFilters && hasToken && passedThisRound < WANT && pagesThisRound < MAX_PAGES
}
