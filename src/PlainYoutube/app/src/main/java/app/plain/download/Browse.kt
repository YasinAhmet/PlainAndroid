package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import app.plain.lang.tr

enum class IKind { VIDEO, SHORT, PLAYLIST, CHANNEL }

/** One row of the Search tab: a video, a Short, a playlist or a channel. */
data class Item(
    val kind: IKind,
    val id: String, // video id, playlist id (PL…/UU…/OL…) or channel id (UC…)
    val title: String,
    val channel: String = "",
    val channelId: String? = null,
    val durationSec: Long = 0,
    val views: String = "", // "1.2M views"
    val age: String = "", // "3 days ago"
    val count: String = "", // playlists: "12 videos"; channels: "44.9K subscribers"
    val handle: String = "", // channels: "@name"
    val thumbId: String? = null, // video id whose thumbnail is shown
    val published: Long = 0, // RSS only: epoch ms
    val live: Boolean = false, // live now / upcoming: nothing to download yet
    val members: Boolean = false, // members-only (blue MEMBERS badge; plays only with the account, 2.0)
) {
    val isVideo get() = kind == IKind.VIDEO || kind == IKind.SHORT
}

/** Items of one answer + the token for the next page (null = end). */
class Page(val items: List<Item>, val continuation: String?)

/** Channel page head. [tabs]: "videos"/"shorts"/"playlists" -> browse params read from the answer. */
data class ChannelHead(val id: String, val title: String, val handle: String, val subs: String, val tabs: Map<String, String>)

data class PlaylistHead(val id: String, val title: String, val owner: String, val ownerId: String?, val count: String)

/**
 * Defensive InnerTube (WEB) parsing for search / browse answers. YouTube mixes old renderers (videoRenderer,
 * gridVideoRenderer, playlistVideoRenderer, channelRenderer, playlistRenderer, reelItemRenderer, …) with view models
 * (lockupViewModel, shortsLockupViewModel), and moves them around, so instead of fixed paths the JSON is walked
 * recursively for known keys. Unknown shapes are skipped, never fatal.
 */
object Browse {
    /** Subtrees never holding result items (and carrying unrelated continuations / videos). */
    private val SKIP = setOf(
        "header", "menu", "showEngagementPanelEndpoint", "engagementPanels", "engagementPanel", "frameworkUpdates",
        "responseContext", "topbar", "microformat", "sidebar", "secondaryContents", "metadata", "contextMenu",
    )
    private val ROOTS = listOf("contents", "onResponseReceivedActions", "onResponseReceivedCommands", "continuationContents")
    private val UC = Regex("^UC[A-Za-z0-9_-]{22}$")
    private val VID = Regex("^[A-Za-z0-9_-]{11}$")
    private val THUMB_VID = Regex("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/")
    private val DURATION = Regex("^(?:(\\d+):)?(\\d{1,2}):(\\d{2})$")

    /** Parse a search/browse answer (first page or continuation). Never throws on odd JSON. */
    fun page(json: String): Page {
        val o = try { JSONObject(json) } catch (_: Exception) { return Page(emptyList(), null) }
        return page(o)
    }

    fun page(o: JSONObject): Page {
        val acc = Acc()
        for (k in ROOTS) o.opt(k)?.let { walk(it, acc) }
        return Page(acc.items.values.toList(), acc.token)
    }

    private class Acc {
        val items = LinkedHashMap<String, Item>() // kind+id -> item, first one wins, order kept
        var token: String? = null
        fun add(i: Item?) {
            if (i == null || i.id.isEmpty()) return
            items.putIfAbsent(i.kind.name + i.id, i)
        }
    }

    private fun walk(v: Any?, acc: Acc) {
        when (v) {
            is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), acc)
            is JSONObject -> {
                val keys = v.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k in SKIP) continue
                    val c = v.opt(k)
                    if (c !is JSONObject) { if (c is JSONArray) walk(c, acc); continue }
                    when (k) {
                        "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer",
                        "playlistPanelVideoRenderer", "videoWithContextRenderer" -> acc.add(video(c))
                        "reelItemRenderer" -> acc.add(reel(c))
                        "shortsLockupViewModel" -> acc.add(shortsLockup(c))
                        "lockupViewModel" -> acc.add(lockup(c))
                        "channelRenderer", "gridChannelRenderer" -> acc.add(channel(c))
                        "playlistRenderer", "gridPlaylistRenderer", "compactPlaylistRenderer" -> acc.add(playlist(c))
                        "continuationItemRenderer", "continuationItemViewModel" -> token(c)?.let { acc.token = it }
                        "nextContinuationData" -> c.optString("continuation").takeIf { it.isNotEmpty() }?.let { acc.token = it }
                        else -> walk(c, acc) // richItemRenderer, itemSectionRenderer, shelves, tabs, …
                    }
                }
            }
        }
    }

    /** First continuationCommand.token below [o]. */
    private fun token(o: JSONObject): String? {
        o.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotEmpty() }?.let { return it }
        val keys = o.keys()
        while (keys.hasNext()) {
            when (val c = o.opt(keys.next())) {
                is JSONObject -> token(c)?.let { return it }
                is JSONArray -> for (i in 0 until c.length()) (c.opt(i) as? JSONObject)?.let { x -> token(x)?.let { return it } }
            }
        }
        return null
    }

    // ---- text helpers ----

    /** simpleText / runs[].text / content (view models). */
    fun text(o: Any?): String = when (o) {
        is String -> o
        is JSONObject -> o.optString("simpleText").ifEmpty {
            o.optJSONArray("runs")?.let { r -> (0 until r.length()).joinToString("") { r.optJSONObject(it)?.optString("text").orEmpty() } }
                ?: o.optString("content")
        }
        else -> ""
    }.trim()

    /** "1:02:03" -> 3723, "4:05" -> 245, else 0. */
    fun parseDuration(s: String?): Long {
        val m = DURATION.find(s?.trim().orEmpty()) ?: return 0
        val h = m.groupValues[1].toLongOrNull() ?: 0
        return h * 3600 + m.groupValues[2].toLong() * 60 + m.groupValues[3].toLong()
    }

    /** First browseEndpoint.browseId below [o] that is a channel id. */
    private fun channelIdIn(o: Any?, depth: Int = 0): String? {
        if (depth > 12) return null
        when (o) {
            is JSONObject -> {
                o.optJSONObject("browseEndpoint")?.optString("browseId")?.takeIf { UC.matches(it) }?.let { return it }
                val keys = o.keys()
                while (keys.hasNext()) channelIdIn(o.opt(keys.next()), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) channelIdIn(o.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    /** All "text" strings of thumbnailBadgeViewModel / thumbnailOverlayTimeStatusRenderer below [o] (+ their styles). */
    private fun badges(o: Any?, out: MutableList<Pair<String, String>>, depth: Int = 0) {
        if (depth > 14) return
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val c = o.opt(k)
                    if (k == "thumbnailBadgeViewModel" && c is JSONObject) out += c.optString("text") to c.optString("badgeStyle")
                    else if (k == "thumbnailOverlayTimeStatusRenderer" && c is JSONObject) out += text(c.opt("text")) to c.optString("style")
                    else badges(c, out, depth + 1)
                }
            }
            is JSONArray -> for (i in 0 until o.length()) badges(o.opt(i), out, depth + 1)
        }
    }

    private fun thumbIdIn(o: Any?, depth: Int = 0): String? {
        if (depth > 14) return null
        when (o) {
            is JSONObject -> {
                o.optString("url").takeIf { it.isNotEmpty() }?.let { u -> THUMB_VID.find(u)?.let { return it.groupValues[1] } }
                val keys = o.keys()
                while (keys.hasNext()) thumbIdIn(o.opt(keys.next()), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) thumbIdIn(o.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun isViews(s: String) = s.endsWith(" views") || s.endsWith(" view") || s == "No views" || s.endsWith(" watching")
    private fun isAge(s: String) = s.endsWith(" ago") || s.startsWith("Streamed") || s.startsWith("Premieres") || s.startsWith("Scheduled")

    // ---- renderers ----

    private fun video(r: JSONObject): Item? {
        val id = r.optString("videoId")
        if (!VID.matches(id)) return null
        val owner = r.opt("ownerText") ?: r.opt("shortBylineText") ?: r.opt("longBylineText")
        val bs = ArrayList<Pair<String, String>>(); badges(r.opt("thumbnailOverlays"), bs)
        var dur = parseDuration(text(r.opt("lengthText"))).takeIf { it > 0 } ?: r.optString("lengthSeconds").toLongOrNull() ?: 0L
        if (dur == 0L) dur = bs.map { parseDuration(it.first) }.firstOrNull { it > 0 } ?: 0
        val liveBadge = r.optJSONArray("badges")?.let { a ->
            (0 until a.length()).any { a.optJSONObject(it)?.optJSONObject("metadataBadgeRenderer")?.optString("style") == "BADGE_STYLE_TYPE_LIVE_NOW" }
        } == true
        val live = liveBadge || r.has("upcomingEventData") || bs.any { it.second == "LIVE" || it.second == "UPCOMING" }
        // playlistVideoRenderer: videoInfo runs = "1.2M views • 3 years ago"
        val info = text(r.opt("videoInfo")).split(" • ", "•").map { it.trim() }
        val short = r.optJSONObject("navigationEndpoint")?.has("reelWatchEndpoint") == true
        return Item(
            kind = if (short) IKind.SHORT else IKind.VIDEO, id = id, title = text(r.opt("title")).ifEmpty { text(r.opt("headline")) },
            channel = text(owner), channelId = channelIdIn(owner),
            durationSec = dur,
            views = text(r.opt("shortViewCountText")).ifEmpty { text(r.opt("viewCountText")) }.ifEmpty { info.firstOrNull(::isViews).orEmpty() },
            age = text(r.opt("publishedTimeText")).ifEmpty { info.firstOrNull(::isAge).orEmpty() },
            thumbId = id, live = live, members = Members.inRenderer(r),
        )
    }

    private fun reel(r: JSONObject): Item? {
        val id = r.optString("videoId")
        if (!VID.matches(id)) return null
        return Item(IKind.SHORT, id, text(r.opt("headline")), views = text(r.opt("viewCountText")), thumbId = id)
    }

    private fun shortsLockup(r: JSONObject): Item? {
        val id = r.optJSONObject("onTap")?.optJSONObject("innertubeCommand")?.optJSONObject("reelWatchEndpoint")?.optString("videoId")
            ?.takeIf { VID.matches(it) }
            ?: r.optString("entityId").substringAfterLast('-').takeIf { VID.matches(it) && r.optString("entityId").startsWith("shorts-") }
            ?: return null
        val om = r.optJSONObject("overlayMetadata")
        val title = text(om?.opt("primaryText")).ifEmpty {
            Regex("^(.+), [^,]+ views? - play Short$").find(r.optString("accessibilityText"))?.groupValues?.get(1).orEmpty()
        }
        return Item(IKind.SHORT, id, title, views = text(om?.opt("secondaryText")), thumbId = id)
    }

    private fun lockup(v: JSONObject): Item? {
        val id = v.optString("contentId")
        if (id.isEmpty()) return null
        val type = v.optString("contentType")
        val md = v.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val title = text(md?.opt("title"))
        var channel = ""
        var channelId: String? = null
        val texts = ArrayList<String>()
        val rows = md?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
        if (rows != null) for (i in 0 until rows.length()) {
            val parts = rows.optJSONObject(i)?.optJSONArray("metadataParts") ?: continue
            for (p in 0 until parts.length()) {
                val part = parts.optJSONObject(p) ?: continue
                val t = text(part.opt("text"))
                if (t.isEmpty()) continue
                val cid = channelIdIn(part.optJSONObject("text")?.opt("commandRuns"))
                if (cid != null && channelId == null) { channel = t; channelId = cid } else texts += t
            }
        }
        val bs = ArrayList<Pair<String, String>>(); badges(v.opt("contentImage"), bs)
        val isVideo = type == "LOCKUP_CONTENT_TYPE_VIDEO" || type.isEmpty() && VID.matches(id)
        return when {
            isVideo -> {
                if (channel.isEmpty()) channel = texts.firstOrNull { !isViews(it) && !isAge(it) }.orEmpty() // e.g. "A and B" collabs
                Item(
                    kind = IKind.VIDEO, id = id, title = title, channel = channel, channelId = channelId,
                    durationSec = bs.map { parseDuration(it.first) }.firstOrNull { it > 0 } ?: 0,
                    views = texts.firstOrNull(::isViews).orEmpty(), age = texts.firstOrNull(::isAge).orEmpty(),
                    thumbId = id,
                    live = bs.any { it.second.endsWith("_LIVE") || it.first.equals("LIVE", true) || it.first.equals("UPCOMING", true) },
                    members = Members.inRenderer(v),
                )
            }
            type == "LOCKUP_CONTENT_TYPE_PLAYLIST" || type == "LOCKUP_CONTENT_TYPE_PODCAST" || type == "LOCKUP_CONTENT_TYPE_ALBUM" -> Item(
                kind = IKind.PLAYLIST, id = id, title = title, channel = channel, channelId = channelId,
                count = bs.map { it.first }.firstOrNull { Regex("\\d").containsMatchIn(it) }.orEmpty(),
                thumbId = thumbIdIn(v.opt("contentImage"))
                    ?: v.optJSONObject("itemPlayback")?.let { watchVideoId(it) },
            )
            UC.matches(id) -> Item(IKind.CHANNEL, id, title, count = texts.firstOrNull { "subscriber" in it }.orEmpty())
            else -> null
        }
    }

    private fun watchVideoId(o: Any?, depth: Int = 0): String? {
        if (depth > 10) return null
        when (o) {
            is JSONObject -> {
                o.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { VID.matches(it) }?.let { return it }
                val keys = o.keys()
                while (keys.hasNext()) watchVideoId(o.opt(keys.next()), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) watchVideoId(o.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun channel(r: JSONObject): Item? {
        val id = r.optString("channelId")
        if (!UC.matches(id)) return null
        // channelRenderer in search: subscriberCountText holds the @handle and videoCountText the subscriber count
        val a = text(r.opt("subscriberCountText"))
        val b = text(r.opt("videoCountText"))
        val handle = listOf(a, b).firstOrNull { it.startsWith("@") }.orEmpty()
        val subs = listOf(a, b).firstOrNull { "subscriber" in it }.orEmpty()
        return Item(IKind.CHANNEL, id, text(r.opt("title")), handle = handle,
            count = subs.ifEmpty { listOf(a, b).firstOrNull { it.isNotEmpty() && !it.startsWith("@") }.orEmpty() })
    }

    private fun playlist(r: JSONObject): Item? {
        val id = r.optString("playlistId")
        if (id.isEmpty()) return null
        val owner = r.opt("shortBylineText") ?: r.opt("longBylineText") ?: r.opt("ownerText")
        val count = text(r.opt("videoCountText")).ifEmpty { r.optString("videoCount").takeIf { it.isNotEmpty() }?.let { "$it videos" }.orEmpty() }
        return Item(IKind.PLAYLIST, id, text(r.opt("title")), channel = text(owner), channelId = channelIdIn(owner), count = count,
            thumbId = watchVideoId(r.opt("navigationEndpoint")) ?: thumbIdIn(r.opt("thumbnail") ?: r.opt("thumbnails")))
    }

    // ---- page heads ----

    fun channelHead(o: JSONObject): ChannelHead? {
        val md = o.optJSONObject("metadata")?.optJSONObject("channelMetadataRenderer")
        val header = o.optJSONObject("header")
        val id = md?.optString("externalId")?.takeIf { UC.matches(it) }
            ?: header?.optJSONObject("c4TabbedHeaderRenderer")?.optString("channelId")?.takeIf { UC.matches(it) }
            ?: return null
        val title = md?.optString("title").orEmpty()
            .ifEmpty { header?.optJSONObject("pageHeaderRenderer")?.optString("pageTitle").orEmpty() }
            .ifEmpty { text(header?.optJSONObject("c4TabbedHeaderRenderer")?.opt("title")) }
        val handle = md?.optString("vanityChannelUrl").orEmpty().substringAfterLast('/', "").takeIf { it.startsWith("@") }.orEmpty()
        val headTexts = ArrayList<String>()
        collectContent(header?.optJSONObject("pageHeaderRenderer")?.optJSONObject("content")?.optJSONObject("pageHeaderViewModel")?.opt("metadata"), headTexts)
        val subs = headTexts.flatMap { it.split(" • ", "•") }.map { it.trim() }.firstOrNull { "subscriber" in it }
            ?: text(header?.optJSONObject("c4TabbedHeaderRenderer")?.opt("subscriberCountText"))
        val tabs = HashMap<String, String>()
        val ta = o.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
        if (ta != null) for (i in 0 until ta.length()) {
            val t = ta.optJSONObject(i)?.let { it.optJSONObject("tabRenderer") ?: it.optJSONObject("expandableTabRenderer") } ?: continue
            val ep = t.optJSONObject("endpoint") ?: continue
            val url = ep.optJSONObject("commandMetadata")?.optJSONObject("webCommandMetadata")?.optString("url").orEmpty()
            val params = ep.optJSONObject("browseEndpoint")?.optString("params").orEmpty()
            val key = url.substringBefore('?').substringAfterLast('/')
            if (params.isNotEmpty() && key.isNotEmpty()) tabs[key] = params
        }
        return ChannelHead(id, title, handle, subs, tabs)
    }

    private fun collectContent(o: Any?, out: MutableList<String>, depth: Int = 0) {
        if (depth > 10) return
        when (o) {
            is JSONObject -> {
                o.optString("content").takeIf { it.isNotEmpty() }?.let { out += it }
                val keys = o.keys()
                while (keys.hasNext()) { val k = keys.next(); if (k != "content") collectContent(o.opt(k), out, depth + 1) }
            }
            is JSONArray -> for (i in 0 until o.length()) collectContent(o.opt(i), out, depth + 1)
        }
    }

    fun playlistHead(o: JSONObject, playlistId: String): PlaylistHead? {
        val h = o.optJSONObject("header")?.optJSONObject("playlistHeaderRenderer")
        val title = text(h?.opt("title"))
            .ifEmpty { o.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title").orEmpty() }
            .ifEmpty { o.optJSONObject("header")?.optJSONObject("pageHeaderRenderer")?.optString("pageTitle").orEmpty() }
        if (title.isEmpty()) return null
        val owner = h?.opt("ownerText")
        val count = text(h?.opt("numVideosText")).ifEmpty { text(h?.opt("stats")) }
        val ownerTexts = ArrayList<String>()
        if (h == null) collectContent(o.optJSONObject("header")?.optJSONObject("pageHeaderRenderer")?.optJSONObject("content"), ownerTexts)
        return PlaylistHead(
            playlistId, title,
            owner = text(owner).ifEmpty { ownerTexts.firstOrNull { it.startsWith("by ") }?.removePrefix("by ").orEmpty() },
            ownerId = channelIdIn(owner) ?: channelIdIn(o.optJSONObject("header")),
            count = count.ifEmpty { ownerTexts.firstOrNull { it.endsWith(" videos") || it.endsWith(" video") }.orEmpty() },
        )
    }

    /**
     * Does this answer look blocked for our region? An "error" object, an ERROR alert mentioning availability /
     * legal complaints / country, or an empty channel page ("This channel doesn't have any content") where a
     * listing was expected. Such answers are retried with the fallback region.
     */
    fun regionBlocked(o: JSONObject): Boolean {
        if (o.has("error")) return true
        val al = alert(o)?.lowercase()
        if (al != null && ("not available" in al || "legal" in al || "country" in al || "blocked" in al || "unavailable" in al)) return true
        return emptyChannel(o) != null
    }

    /** The empty-channel message of a channel page, or null. */
    fun emptyChannel(o: JSONObject): String? {
        var msg: String? = null
        fun walk(v: Any?, depth: Int) {
            if (msg != null || depth > 12) return
            when (v) {
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), depth + 1)
                is JSONObject -> {
                    v.optJSONObject("channelOwnerEmptyStateRenderer")?.let { r ->
                        msg = text(r.opt("description")).ifEmpty { tr("This channel has no content", "Bu kanalda içerik yok") }; return
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()), depth + 1)
                }
            }
        }
        walk(o.opt("contents"), 0)
        return msg
    }

    /** Letters and digits only, lowercased, accents and Turkish letters folded ("Foşix Erlik" -> "fosixerlik"). */
    fun fold(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase().replace('ı', 'i'), java.text.Normalizer.Form.NFD)
        return n.filter { it.isLetterOrDigit() && Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
    }

    /** Close enough to be the channel someone meant ("FosixErlik" vs "Foşix Erlik" vs "Foschix Erlik"). */
    fun similarName(a: String, b: String): Boolean {
        val x = fold(a); val y = fold(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y || x.length >= 4 && y.length >= 4 && (x in y || y in x)) return true
        val d = IntArray(y.length + 1) { it }
        for (i in 1..x.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..y.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (x[i - 1] == y[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[y.length] <= maxOf(2, minOf(x.length, y.length) / 5)
    }

    /** YouTube's own message for a missing/unavailable page (alerts), or null. */
    fun alert(o: JSONObject): String? {
        val a = o.optJSONArray("alerts") ?: return null
        for (i in 0 until a.length()) {
            val al = a.optJSONObject(i) ?: continue
            val r = al.optJSONObject("alertRenderer") ?: al.optJSONObject("alertWithButtonRenderer") ?: continue
            if (r.optString("type") == "ERROR") return text(r.opt("text")).ifEmpty { tr("Unavailable", "Kullanılamıyor") }
        }
        return null
    }

    /** navigation/resolve_url answer -> what to open. */
    fun resolved(o: JSONObject): Target? {
        val ep = o.optJSONObject("endpoint") ?: return null
        ep.optJSONObject("browseEndpoint")?.optString("browseId")?.let { id ->
            if (UC.matches(id)) return Target.Channel(id)
            if (id.startsWith("VL") && id.length > 2) return Target.Playlist(id.substring(2))
        }
        ep.optJSONObject("watchEndpoint")?.optString("videoId")?.takeIf { VID.matches(it) }?.let { return Target.Video(it) }
        return null
    }

    fun visitorData(o: JSONObject): String? =
        o.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }
}

/** What a pasted text / shared link / search box entry means. */
sealed class Target {
    data class Video(val id: String) : Target()
    data class Playlist(val id: String) : Target()
    data class Channel(val id: String) : Target() // UC…
    data class ChannelUrl(val url: String) : Target() // @handle, /c/, /user/: needs navigation/resolve_url
    data class Query(val text: String) : Target()
    object None : Target()
}

object Links {
    private val HOST = "(?:https?://)?(?:(?:www|m|music)\\.)?youtube\\.com"
    private val PLAYLIST = Regex("$HOST/(?:playlist|watch|embed/videoseries)\\?(?:[^#\\s]*?&)?list=([A-Za-z0-9_-]{10,})", RegexOption.IGNORE_CASE)
    private val CHANNEL_ID = Regex("$HOST/channel/(UC[A-Za-z0-9_-]{22})(?![A-Za-z0-9_-])", RegexOption.IGNORE_CASE)
    private val HANDLE_URL = Regex("$HOST/(@[\\w.%-]{1,100})", RegexOption.IGNORE_CASE)
    private val NAMED_URL = Regex("$HOST/(c|user)/([\\w.%-]{1,100})", RegexOption.IGNORE_CASE)
    private val BARE_HANDLE = Regex("^@[\\w.-]{3,100}$")
    private val BARE_UC = Regex("^UC[A-Za-z0-9_-]{22}$")

    /**
     * Classify user input. A video link wins (also watch?v=…&list=…, like before); then playlist links, channel links,
     * a bare @handle or UC… id. Anything else is a search query (or None when empty / an unknown URL).
     * [bareId]: a lone 11-character word is a video id (Download tab) rather than a search word ("photography").
     */
    fun classify(input: String?, bareId: Boolean = true): Target {
        val t = input?.trim().orEmpty()
        if (t.isEmpty()) return Target.None
        val bare = Regex("^[A-Za-z0-9_-]{11}$").matches(t)
        Yt.videoId(t)?.let { if (!bare || bareId) return Target.Video(it) }
        PLAYLIST.find(t)?.let { return Target.Playlist(it.groupValues[1]) }
        CHANNEL_ID.find(t)?.let { return Target.Channel(it.groupValues[1]) }
        HANDLE_URL.find(t)?.let { return Target.ChannelUrl("https://www.youtube.com/" + it.groupValues[1]) }
        NAMED_URL.find(t)?.let { return Target.ChannelUrl("https://www.youtube.com/" + it.groupValues[1].lowercase() + "/" + it.groupValues[2]) }
        if (BARE_HANDLE.matches(t)) return Target.ChannelUrl("https://www.youtube.com/$t")
        if (BARE_UC.matches(t)) return Target.Channel(t)
        if (Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE).matches(t)) return Target.None // some other link
        return Target.Query(t)
    }
}
