package app.plain.download

import org.json.JSONObject
import java.io.File

/**
 * Sorted, merged half-open intervals [a, b). Used for bytes of a progressive (audio) stream and for segment indices
 * of an HLS video, so "what is cached" survives seeks and restarts. Not thread-safe; callers lock.
 */
class Ranges {
    private val list = ArrayList<LongArray>() // each [a, b), sorted, non-overlapping, non-touching

    val spans: List<Pair<Long, Long>> get() = list.map { it[0] to it[1] }

    fun add(a: Long, b: Long) {
        if (b <= a) return
        var s = a; var e = b
        val out = ArrayList<LongArray>(list.size + 1)
        var placed = false
        for (r in list) {
            when {
                r[1] < s -> out += r
                r[0] > e -> { if (!placed) { out += longArrayOf(s, e); placed = true }; out += r }
                else -> { s = minOf(s, r[0]); e = maxOf(e, r[1]) }
            }
        }
        if (!placed) out += longArrayOf(s, e)
        list.clear(); list.addAll(out)
    }

    fun contains(p: Long) = list.any { p >= it[0] && p < it[1] }

    /** End of the cached run that contains [p], or [p] when p isn't cached. */
    fun runEnd(p: Long): Long = list.firstOrNull { p >= it[0] && p < it[1] }?.get(1) ?: p

    /** Start of the next cached run after [p] (to bound a network fetch), or [limit]. */
    fun nextStart(p: Long, limit: Long): Long = list.firstOrNull { it[0] > p }?.get(0)?.coerceAtMost(limit) ?: limit

    fun covered(): Long = list.sumOf { it[1] - it[0] }

    fun complete(total: Long) = total > 0 && list.size == 1 && list[0][0] <= 0 && list[0][1] >= total

    /** First missing position at or after [from]; wraps to the first gap from 0; null when [0, total) is complete. */
    fun firstGap(from: Long, total: Long): Long? {
        if (total <= 0) return if (contains(from)) runEnd(from) else from
        fun gapFrom(p: Long): Long? {
            var x = p
            while (x < total) { val e = runEnd(x); if (e == x) return x; x = e }
            return null
        }
        return gapFrom(from.coerceIn(0, total)) ?: gapFrom(0)
    }

    fun encode(): String = list.joinToString(",") { "${it[0]}-${it[1]}" }

    companion object {
        fun decode(s: String?): Ranges {
            val r = Ranges()
            s?.split(',')?.forEach { p ->
                val a = p.substringBefore('-').toLongOrNull(); val b = p.substringAfter('-', "").toLongOrNull()
                if (a != null && b != null) r.add(a, b)
            }
            return r
        }
    }
}

enum class CacheKind(val dir: String) { VIDEO("video"), MUSIC("music") }

/**
 * One cached stream. MUSIC: a progressive audio stream (itag 140 M4A or 251 WebM) in data.bin, [total] bytes,
 * [done] = byte ranges. VIDEO: one HLS quality: video segments v_N.ts + audio segments a_N.aac, [total] = segment
 * count, [done] = video segment indices, [aDone] = audio segment indices.
 */
class CacheMeta(
    val kind: CacheKind,
    val videoId: String,
    val quality: Int, // VIDEO: height; MUSIC: itag
    var title: String,
    var channel: String,
    var durationSec: Long,
    var total: Long = 0,
    val done: Ranges = Ranges(),
    val aDone: Ranges = Ranges(),
    var lastUsed: Long = 0,
    var size: Long = 0, // bytes on disk (kept up to date by the writer)
    var ext: String = "", // MUSIC: "m4a" / "webm"
    var codecs: String = "", // VIDEO: for the local master playlist
    var bandwidth: Long = 0,
    var resolution: String = "",
    var album: String? = null, // MUSIC: album folder for the public copy
    var publicUri: String? = null, // set once complete and published under Music|Movies/PlainDownload/Cached/
    var relPath: String? = null,
    var fileName: String? = null,
) {
    val key get() = "${videoId}_$quality"

    /** Complete and living as a public MediaStore file (the private copy is gone). */
    val published get() = publicUri != null

    val complete: Boolean
        get() = published || if (kind == CacheKind.MUSIC) done.complete(total) else total > 0 && done.complete(total) && aDone.complete(total)

    /** 0..1 */
    val fraction: Float
        get() = when {
            total <= 0 -> 0f
            published -> 1f
            kind == CacheKind.MUSIC -> (done.covered().toFloat() / total).coerceIn(0f, 1f)
            else -> ((done.covered() + aDone.covered()).toFloat() / (2 * total)).coerceIn(0f, 1f)
        }

    fun encode(): String = JSONObject().apply {
        put("kind", kind.name); put("id", videoId); put("q", quality); put("title", title); put("channel", channel)
        put("dur", durationSec); put("total", total); put("done", done.encode()); put("aDone", aDone.encode())
        put("used", lastUsed); put("size", size); put("ext", ext); put("codecs", codecs); put("bw", bandwidth)
        put("res", resolution)
        album?.let { put("album", it) }; publicUri?.let { put("uri", it) }; relPath?.let { put("rel", it) }; fileName?.let { put("file", it) }
    }.toString()

    companion object {
        fun decode(s: String): CacheMeta? = try {
            val o = JSONObject(s)
            CacheMeta(
                CacheKind.valueOf(o.getString("kind")), o.getString("id"), o.getInt("q"), o.optString("title"),
                o.optString("channel"), o.optLong("dur"), o.optLong("total"), Ranges.decode(o.optString("done")),
                Ranges.decode(o.optString("aDone")), o.optLong("used"), o.optLong("size"), o.optString("ext"),
                o.optString("codecs"), o.optLong("bw"), o.optString("res"),
                o.optString("album").ifEmpty { null }, o.optString("uri").ifEmpty { null }, o.optString("rel").ifEmpty { null },
                o.optString("file").ifEmpty { null },
            )
        } catch (_: Exception) { null }
    }
}

object CacheLru {
    const val GB = 1L shl 30
    const val MIN_FREE = 3 * GB

    /**
     * Entries to delete, least recently used first, until the kind's total fits [cap] and the phone keeps
     * [MIN_FREE] free ([free] = usable bytes now). [keep] (the one playing) is never chosen.
     */
    fun victims(entries: List<CacheMeta>, cap: Long, free: Long, keep: String?): List<CacheMeta> {
        var total = entries.sumOf { it.size }
        var freeNow = free
        val out = ArrayList<CacheMeta>()
        for (e in entries.sortedBy { it.lastUsed }) {
            if (total <= cap && freeNow >= MIN_FREE) break
            if (e.key == keep) continue
            out += e
            total -= e.size
            freeNow += e.size
        }
        return out
    }
}

/** Files of both caches, under the app's own cache storage (never MediaStore; not seen by LocalMusic). */
class CacheStore(val root: File) {
    /** Deletes an entry's public MediaStore file (set by the app; returns false when Android refused). */
    var publicDeleter: (CacheMeta) -> Boolean = { true }

    fun dir(kind: CacheKind) = File(root, kind.dir).apply { mkdirs() }
    fun entryDir(kind: CacheKind, key: String) = File(dir(kind), key)

    fun list(kind: CacheKind): List<CacheMeta> =
        dir(kind).listFiles()?.mapNotNull { d -> File(d, "meta.json").takeIf { it.exists() }?.let { load(it) } }.orEmpty()

    private fun load(f: File): CacheMeta? = try { CacheMeta.decode(f.readText()) } catch (_: Exception) { null }

    fun get(kind: CacheKind, key: String): CacheMeta? = File(entryDir(kind, key), "meta.json").takeIf { it.exists() }?.let { load(it) }

    @Synchronized fun save(m: CacheMeta) {
        val d = entryDir(m.kind, m.key).apply { mkdirs() }
        val tmp = File(d, "meta.json.tmp")
        tmp.writeText(m.encode())
        tmp.renameTo(File(d, "meta.json")).also { if (!it) { File(d, "meta.json").delete(); tmp.renameTo(File(d, "meta.json")) } }
    }

    /** Drop an entry: its public file (if published) and its private folder. */
    fun remove(kind: CacheKind, key: String) {
        get(kind, key)?.takeIf { it.published }?.let { publicDeleter(it) }
        entryDir(kind, key).deleteRecursively()
    }

    /** Drop only the index entry (the public file was moved to a permanent folder, or deleted elsewhere). */
    fun forget(kind: CacheKind, key: String) { entryDir(kind, key).deleteRecursively() }

    fun clear(kind: CacheKind) { list(kind).forEach { remove(kind, it.key) }; dir(kind).listFiles()?.forEach { it.deleteRecursively() } }

    fun size(kind: CacheKind) = list(kind).sumOf { it.size }

    /** Delete LRU entries of [kind] so it fits [cap] and keeps 3 GB free. Returns bytes freed. */
    fun trim(kind: CacheKind, cap: Long, keep: String?): Long {
        val v = CacheLru.victims(list(kind), cap, root.usableSpace, keep)
        v.forEach { remove(kind, it.key) }
        return v.sumOf { it.size }
    }
}

object Albums {
    private val NOT_ALBUMS = Regex("^(Uploads from |Mix - |Liked videos|Watch later|Favorites|Popular videos|Videos$|Shorts$)", RegexOption.IGNORE_CASE)

    /** The YouTube Music album of [videoId] in a WEB_REMIX `next` answer: the byline run linking to an album page. */
    fun fromRemixNext(json: String, videoId: String): String? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        var found: String? = null
        fun walk(v: Any?) {
            if (found != null) return
            when (v) {
                is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                is JSONObject -> {
                    val p = v.optJSONObject("playlistPanelVideoRenderer")
                    if (p != null && p.optString("videoId") == videoId) {
                        val runs = p.optJSONObject("longBylineText")?.optJSONArray("runs")
                        if (runs != null) for (i in 0 until runs.length()) {
                            val r = runs.optJSONObject(i) ?: continue
                            val type = r.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                ?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")
                                ?.optString("pageType")
                            if (type == "MUSIC_PAGE_TYPE_ALBUM") { found = r.optString("text").ifEmpty { null }; return }
                        }
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) walk(v.opt(keys.next()))
                }
            }
        }
        walk(o)
        return found
    }

    /**
     * Album folder: the YouTube Music album for music tracks (ATV / "- Topic"), else the playlist the item was played
     * or bulk-downloaded from (not automatic lists like "Uploads from …" or Mixes), else none. Cleaned for file names.
     */
    fun pick(remixAlbum: String?, musicTrack: Boolean, playlist: String?): String? {
        if (musicTrack) Naming.album(remixAlbum)?.let { return it }
        val p = playlist?.trim().orEmpty()
        if (p.isEmpty() || NOT_ALBUMS.containsMatchIn(p)) return null
        return Naming.album(p)
    }
}

/** Watch (HLS video) or Listen (audio stream only). */
enum class PlayMode { WATCH, LISTEN }

object Modes {
    /**
     * Default mode for a video. Audio tracks / art-only uploads (ATV), YouTube Music "- Topic" channels and
     * music.youtube.com links default to Listen, as do other videos in the Music category (Watch is one tap away).
     * A choice the user made for this video wins.
     */
    fun decide(remembered: PlayMode?, musicVideoType: String?, category: String?, author: String?, musicLink: Boolean): PlayMode = when {
        remembered != null -> remembered
        musicVideoType == "MUSIC_VIDEO_TYPE_ATV" -> PlayMode.LISTEN
        author?.trim()?.endsWith(" - Topic") == true -> PlayMode.LISTEN
        musicLink -> PlayMode.LISTEN
        category.equals("Music", ignoreCase = true) -> PlayMode.LISTEN
        else -> PlayMode.WATCH
    }

    fun musicVideoType(playerJson: String): String? = try {
        JSONObject(playerJson).optJSONObject("videoDetails")?.optString("musicVideoType")?.ifEmpty { null }
    } catch (_: Exception) { null }

    fun category(playerJson: String): String? = try {
        JSONObject(playerJson).optJSONObject("microformat")?.optJSONObject("playerMicroformatRenderer")?.optString("category")?.ifEmpty { null }
    } catch (_: Exception) { null }

    /** Audio stream to listen to: itag 140 (M4A) first, then 251 (Opus WebM), then any AAC / Opus. */
    fun listenFormat(formats: List<Format>): Format? =
        formats.firstOrNull { it.itag == 140 && !it.drc } ?: formats.firstOrNull { it.itag == 251 && !it.drc }
            ?: Pick.audio(formats, AudioFmt.M4A, AudioQ.HIGH) ?: Pick.audio(formats, AudioFmt.OPUS, AudioQ.HIGH)
}

object Id3 {
    /** Length of the ID3v2 tag at the start of [b] (HLS packed-audio segments carry one), or 0. */
    fun tagLength(b: ByteArray, off: Int = 0): Int {
        if (b.size - off < 10 || b[off] != 'I'.code.toByte() || b[off + 1] != 'D'.code.toByte() || b[off + 2] != '3'.code.toByte()) return 0
        val sz = ((b[off + 6].toInt() and 0x7F) shl 21) or ((b[off + 7].toInt() and 0x7F) shl 14) or
            ((b[off + 8].toInt() and 0x7F) shl 7) or (b[off + 9].toInt() and 0x7F)
        val footer = if (b[off + 5].toInt() and 0x10 != 0) 10 else 0
        return (10 + sz + footer).coerceAtMost(b.size - off)
    }

    /** Segment bytes without leading ID3 tags (pure ADTS), so segments can be concatenated into one .aac. */
    fun strip(b: ByteArray): ByteArray {
        var off = 0
        while (true) { val n = tagLength(b, off); if (n == 0) break; off += n }
        return if (off == 0) b else b.copyOfRange(off, b.size)
    }
}

/** HLS (m3u8) parsing for YouTube's iOS/visionOS manifests: video-only TS variants + separate AAC audio groups. */
object Hls {
    data class Variant(val bandwidth: Long, val width: Int, val height: Int, val codecs: String, val audio: String?, val fps: Int, val uri: String) {
        val avc get() = codecs.startsWith("avc1")
        val label get() = "${height}p" + if (fps > 30) "$fps" else ""
    }

    data class Master(val variants: List<Variant>, val audio: Map<String, String>) // group id -> playlist uri

    data class Seg(val duration: Double, val uri: String)

    private val ATTR = Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)")

    fun attrs(line: String): Map<String, String> =
        ATTR.findAll(line.substringAfter(':')).associate { it.groupValues[1] to it.groupValues[2].trim('"') }

    fun resolve(base: String, uri: String): String = try { java.net.URI(base).resolve(uri).toString() } catch (_: Exception) { uri }

    fun parseMaster(text: String, base: String): Master {
        val lines = text.lines().map { it.trim() }
        val vs = ArrayList<Variant>()
        val audio = LinkedHashMap<String, String>()
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("#EXT-X-MEDIA:")) {
                val a = attrs(l)
                if (a["TYPE"] == "AUDIO" && a["URI"] != null && a["GROUP-ID"] != null) audio.putIfAbsent(a["GROUP-ID"]!!, resolve(base, a["URI"]!!))
            } else if (l.startsWith("#EXT-X-STREAM-INF:")) {
                val a = attrs(l)
                val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
                val res = a["RESOLUTION"].orEmpty()
                vs += Variant(
                    a["BANDWIDTH"]?.toLongOrNull() ?: 0, res.substringBefore('x').toIntOrNull() ?: 0, res.substringAfter('x', "").toIntOrNull() ?: 0,
                    a["CODECS"].orEmpty(), a["AUDIO"], a["FRAME-RATE"]?.toDoubleOrNull()?.toInt() ?: 0, resolve(base, uri),
                )
            }
        }
        return Master(vs, audio)
    }

    fun parseMedia(text: String, base: String): List<Seg> {
        val out = ArrayList<Seg>()
        var dur = 0.0
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("#EXTINF:")) dur = l.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: 0.0
            else if (l.isNotEmpty() && !l.startsWith("#")) { out += Seg(dur, resolve(base, l)); dur = 0.0 }
        }
        return out
    }

    /** H.264 variants that have an audio group (one per height, best bandwidth), highest first. */
    fun choices(m: Master): List<Variant> =
        m.variants.filter { it.avc && it.height > 0 && (it.audio == null || it.audio in m.audio) }
            .groupBy { it.height }.map { (_, l) -> l.maxByOrNull { it.bandwidth }!! }.sortedByDescending { it.height }

    /** The variant for [wantP]: same height, else the closest lower one, else the lowest. */
    fun pick(choices: List<Variant>, wantP: Int): Variant? =
        choices.filter { it.height <= wantP }.maxByOrNull { it.height } ?: choices.minByOrNull { it.height }

    /** Cached quality to replay for [wantP]: same rule as [pick]. */
    fun pickMeta(cached: List<CacheMeta>, wantP: Int): CacheMeta? =
        cached.filter { it.quality <= wantP }.maxByOrNull { it.quality } ?: cached.minByOrNull { it.quality }

    /** Master playlist served by the local proxy: one quality, its audio group, local URIs only. */
    fun localMaster(bandwidth: Long, codecs: String, resolution: String, hasAudio: Boolean): String = buildString {
        append("#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n")
        if (hasAudio) append("#EXT-X-MEDIA:URI=\"a.m3u8\",TYPE=AUDIO,GROUP-ID=\"a\",NAME=\"Default\",DEFAULT=YES,AUTOSELECT=YES\n")
        append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth")
        if (codecs.isNotEmpty()) append(",CODECS=\"$codecs\"")
        if (resolution.isNotEmpty()) append(",RESOLUTION=$resolution")
        if (hasAudio) append(",AUDIO=\"a\"")
        append("\nv.m3u8\n")
    }

    /** VOD media playlist with local segment URIs "<prefix><index>". */
    fun localMedia(segs: List<Seg>, prefix: String): String = buildString {
        val target = kotlin.math.ceil(segs.maxOfOrNull { it.duration } ?: 10.0).toInt().coerceAtLeast(1)
        append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:$target\n#EXT-X-MEDIA-SEQUENCE:0\n")
        for ((i, s) in segs.withIndex()) append("#EXTINF:").append(String.format(java.util.Locale.US, "%.6f", s.duration)).append(",\n$prefix$i\n")
        append("#EXT-X-ENDLIST\n")
    }
}
