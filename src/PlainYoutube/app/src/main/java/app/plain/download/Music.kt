package app.plain.download

import org.json.JSONArray
import org.json.JSONObject

/**
 * YouTube Music artist pages (WEB_REMIX `browse` of the channel id). Verified 2026-11-26: a "- Topic" channel on
 * YouTube itself has only a Home tab (one "Albums & Singles" shelf, the rest behind an engagement panel), while
 * music.youtube.com's artist page for the same UC id lists the releases ("Albums", "Singles & EPs", each with a "More"
 * discography page `MPAD<UC…>` + params), "Top songs" (its "Show all" is a playlist `VLOLAK5uy_…` of all songs) and
 * "Videos" (also a playlist). Each release has its own album playlist (OLAK5uy_…), playable / browsable on YouTube.
 */
object MusicParse {
    /** A release: [id] = its album playlist (OLAK5uy_…), [kind] "Album" / "Single" / "EP", [year] when given. */
    data class Release(val id: String, val title: String, val kind: String, val year: String, val cover: String?) {
        fun item(artist: String) = Item(IKind.PLAYLIST, id, title, channel = artist,
            count = listOf(kind, year).filter { it.isNotEmpty() }.joinToString(" · "), thumbId = cover)
    }

    /** A "More" link: REMIX browse id + params. */
    data class More(val browseId: String, val params: String?)

    data class Artist(
        val name: String,
        val albums: List<Release>, val albumsMore: More?,
        val singles: List<Release>, val singlesMore: More?,
        val songsPlaylist: String?, // all songs (OLAK5uy_…), from "Top songs" → Show all
        val videosPlaylist: String?,
    ) {
        val hasMusic get() = albums.isNotEmpty() || singles.isNotEmpty() || songsPlaylist != null
    }

    private fun text(o: Any?) = Browse.text(o)

    private fun shelves(o: Any?, key: String, out: MutableList<JSONObject>, depth: Int = 0) {
        if (depth > 16) return
        when (o) {
            is JSONArray -> for (i in 0 until o.length()) shelves(o.opt(i), key, out, depth + 1)
            is JSONObject -> {
                o.optJSONObject(key)?.let { out += it }
                val ks = o.keys()
                while (ks.hasNext()) { val k = ks.next(); if (k != key) shelves(o.opt(k), key, out, depth + 1) }
            }
        }
    }

    private fun browseEndpoint(o: Any?, depth: Int = 0): JSONObject? {
        if (depth > 10) return null
        when (o) {
            is JSONObject -> {
                o.optJSONObject("browseEndpoint")?.let { return it }
                val ks = o.keys()
                while (ks.hasNext()) browseEndpoint(o.opt(ks.next()), depth + 1)?.let { return it }
            }
            is JSONArray -> for (i in 0 until o.length()) browseEndpoint(o.opt(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun olak(o: Any?): String? = Regex("\"playlistId\":\"(OLAK5uy_[A-Za-z0-9_-]+)\"").find(o.toString())?.groupValues?.get(1)

    /** A musicTwoRowItemRenderer for an album / single. */
    fun release(r: JSONObject, defaultKind: String): Release? {
        val id = olak(r.opt("thumbnailOverlay")) ?: olak(r) ?: return null
        val sub = text(r.opt("subtitle")).split("•").map { it.trim() }.filter { it.isNotEmpty() }
        val year = sub.firstOrNull { Regex("^\\d{4}$").matches(it) }.orEmpty()
        val kind = sub.firstOrNull { it == "Album" || it == "Single" || it == "EP" } ?: defaultKind
        val cover = r.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("url") }.lastOrNull() }
        return Release(id, text(r.opt("title")), kind, year, cover?.takeIf { it.startsWith("https://") })
    }

    fun artist(json: String): Artist? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        val hdr = o.optJSONObject("header")
        val name = text(hdr?.optJSONObject("musicImmersiveHeaderRenderer")?.opt("title"))
            .ifEmpty { text(hdr?.optJSONObject("musicVisualHeaderRenderer")?.opt("title")) }
        var albums = emptyList<Release>(); var singles = emptyList<Release>()
        var albumsMore: More? = null; var singlesMore: More? = null
        var songs: String? = null; var videos: String? = null
        val carousels = ArrayList<JSONObject>(); shelves(o.opt("contents"), "musicCarouselShelfRenderer", carousels)
        for (c in carousels) {
            val h = c.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
            val title = text(h?.opt("title"))
            val more = (h?.optJSONObject("moreContentButton") ?: h?.opt("title"))?.let { browseEndpoint(it) }
                ?.let { More(it.optString("browseId"), it.optString("params").ifEmpty { null }) }
            val items = c.optJSONArray("contents") ?: JSONArray()
            fun rel(kind: String) = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.optJSONObject("musicTwoRowItemRenderer")?.let { r -> release(r, kind) } }
            when {
                title.equals("Albums", true) -> { albums = rel("Album"); albumsMore = more?.takeIf { it.browseId.startsWith("MPAD") } }
                title.startsWith("Singles", true) -> { singles = rel("Single"); singlesMore = more?.takeIf { it.browseId.startsWith("MPAD") } }
                title.equals("Videos", true) -> videos = more?.browseId?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
            }
        }
        val lists = ArrayList<JSONObject>(); shelves(o.opt("contents"), "musicShelfRenderer", lists)
        for (s in lists) {
            val title = text(s.opt("title"))
            if (title.endsWith("songs", true) || title.equals("Songs", true)) {
                songs = (browseEndpoint(s.opt("bottomEndpoint")) ?: browseEndpoint(s.opt("title")))?.optString("browseId")
                    ?.takeIf { it.startsWith("VL") }?.removePrefix("VL")
            }
        }
        if (name.isEmpty() && albums.isEmpty() && singles.isEmpty() && songs == null) return null
        return Artist(name, albums, albumsMore, singles, singlesMore, songs, videos)
    }

    /** One page of a discography grid: its releases and the token of the next page (null = last page). */
    data class DiscoPage(val releases: List<Release>, val token: String?)

    /**
     * A discography page ("More" of Albums / Singles & EPs, 100 releases each) or one of its continuations
     * (`continuationContents.gridContinuation`). The next page's token is `nextContinuationData.continuation`
     * (also accepted: a `continuationItemRenderer` command, should YouTube Music switch over).
     */
    fun discographyPage(json: String, defaultKind: String): DiscoPage {
        val o = try { JSONObject(json) } catch (_: Exception) { return DiscoPage(emptyList(), null) }
        val out = ArrayList<JSONObject>()
        shelves(o.opt("contents"), "musicTwoRowItemRenderer", out)
        shelves(o.opt("continuationContents"), "musicTwoRowItemRenderer", out)
        return DiscoPage(out.mapNotNull { release(it, defaultKind) }.distinctBy { it.id }, nextToken(o))
    }

    fun discography(json: String, defaultKind: String): List<Release> = discographyPage(json, defaultKind).releases

    /** Continuation token of a WEB_REMIX list answer, or null. */
    fun nextToken(o: JSONObject): String? {
        val nd = ArrayList<JSONObject>()
        shelves(o.opt("contents"), "nextContinuationData", nd)
        shelves(o.opt("continuationContents"), "nextContinuationData", nd)
        nd.firstNotNullOfOrNull { it.optString("continuation").ifEmpty { null } }?.let { return it }
        val ci = ArrayList<JSONObject>()
        shelves(o.opt("contents"), "continuationItemRenderer", ci)
        shelves(o.opt("continuationContents"), "continuationItemRenderer", ci)
        return ci.firstNotNullOfOrNull { Regex("\"token\":\"([^\"]+)\"").find(it.toString())?.groupValues?.get(1) }
    }

    /** Releases for the Music tab: albums then singles (deduped). A failed / empty "More" page keeps the carousel. */
    fun merge(a: Artist, allAlbums: List<Release>?, allSingles: List<Release>?): List<Release> =
        ((allAlbums?.ifEmpty { null } ?: a.albums) + (allSingles?.ifEmpty { null } ?: a.singles)).distinctBy { it.id }

    /**
     * Paging over an artist's albums and singles: the first page comes from the "More" link (or the carousel when
     * there is none / it fails), the following ones from continuation tokens. Pure state; the caller fetches.
     */
    class Cursor(val kind: String, val more: More?, carousel: List<Release>) {
        var token: String? = null
            private set
        var started = false
            private set
        var done = more == null
            private set
        /** Releases known before any page was fetched (the artist page's carousel). */
        val initial: List<Release> = carousel

        /** Apply a fetched page; a first page with nothing in it ("Something went wrong") ends the cursor. */
        fun took(p: DiscoPage) {
            started = true
            token = p.token
            done = p.token == null
        }

        fun fail() { started = true; done = true }
    }
}

/**
 * "Download all" over several lists (albums, singles, the songs list, videos): each video once, the first list it was
 * found in gives its album folder; already-downloaded ones are skipped; at most [Bulk.MAX] per run — running it again
 * continues with the rest (they are then skipped as queued / downloaded).
 */
object Collect {
    data class Track(val item: Item, val album: String?)

    class Result(val take: List<Track>, val skipped: Int, val live: Int, val left: Int)

    fun plan(lists: List<Pair<String?, List<Item>>>, have: (String) -> Boolean): Result {
        val seen = HashSet<String>()
        val all = ArrayList<Track>()
        for ((album, items) in lists) for (i in items) if (i.isVideo && seen.add(i.id)) all += Track(i, album)
        val live = all.count { it.item.live }
        val usable = all.filter { !it.item.live }
        val todo = usable.filter { !have(it.item.id) }
        return Result(todo.take(Bulk.MAX), usable.size - todo.size, live, maxOf(0, todo.size - Bulk.MAX))
    }

    fun summary(queued: Int, r: Result): String {
        val p = ArrayList<String>()
        p += "Queued $queued"
        if (r.skipped > 0) p += "skipped ${r.skipped} already downloaded or queued"
        if (r.live > 0) p += "${r.live} live/upcoming"
        val s = p.joinToString(", ") + "."
        return if (r.left > 0) "$s ${r.left} more are left (limit ${Bulk.MAX} per run): run Download all again to continue." else s
    }
}

/** A bulk action's jobs shown as one row: counts and the one-line status. */
data class BatchStats(val total: Int, val done: Int, val failed: Int, val canceled: Int, val downloading: Int, val converting: Int, val waiting: Int, val queued: Int) {
    val active get() = downloading + converting + waiting + queued
    val text: String
        get() {
            val p = ArrayList<String>()
            p += "$done/$total"
            if (downloading > 0) p += "$downloading downloading"
            if (converting > 0) p += "$converting converting"
            if (waiting > 0) p += "$waiting waiting to convert"
            if (queued > 0) p += "$queued queued"
            if (failed > 0) p += "$failed failed"
            if (canceled > 0) p += "$canceled canceled"
            if (active == 0 && failed == 0 && canceled == 0) p += "done"
            return p.joinToString(" · ")
        }

    companion object {
        fun of(jobs: List<Job>) = BatchStats(
            total = jobs.size,
            done = jobs.count { it.state == State.DONE },
            failed = jobs.count { it.state == State.FAILED },
            canceled = jobs.count { it.state == State.CANCELED },
            downloading = jobs.count { it.state == State.RUNNING && it.phase == Phase.DOWNLOAD },
            converting = jobs.count { it.state == State.RUNNING && (it.phase == Phase.CONVERT || it.phase == Phase.COPY) },
            waiting = jobs.count { it.state == State.RUNNING && it.phase == Phase.WAIT_SAVE },
            queued = jobs.count { it.state == State.QUEUED },
        )
    }
}

/** Download scheduling: audio jobs in parallel (up to the setting, 1–4), anything with video alone. */
object Sched {
    fun isAudio(j: Job) = j.kind == Kind.AUDIO

    /** May [next] start now, given the jobs currently in their download stage? */
    fun canStart(downloading: List<Job>, next: Job, maxAudio: Int): Boolean = when {
        downloading.isEmpty() -> true
        !isAudio(next) -> false // video: one download at a time
        downloading.any { !isAudio(it) } -> false
        else -> downloading.size < maxAudio.coerceIn(1, 4)
    }

    /** Bytes the running downloads still need (their estimate minus what they have). */
    fun pendingBytes(downloading: List<Job>) = downloading.sumOf { maxOf(0L, it.total - it.done) }
}
