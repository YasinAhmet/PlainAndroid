package app.plain.download

import org.json.JSONArray
import org.json.JSONObject

/**
 * Spotify playlists converted to YouTube (3.2; 3.4: no sign-in): the PUBLIC playlists of a Spotify profile the user
 * types (or playlist links) are read once per manual Sync (see [SpotifySync]); every track is matched to a YouTube
 * Music song, and the converted playlists play, download and cache through the Youtube tab like any other list.
 * Nothing of Spotify's audio is used.
 *
 * Everything in this file is pure (unit-tested): data, the public-page parsers, the YouTube Music song-search
 * parser and the matcher.
 */

/** One Spotify track (local files and podcast episodes are skipped). */
data class SpTrack(
    val id: String,
    val title: String,
    val artists: List<String>,
    val album: String,
    val durationMs: Long,
) {
    val artist get() = artists.firstOrNull().orEmpty()
    /** The text searched on YouTube Music. */
    val query get() = (artists.take(2) + SpMatcher.core(title)).joinToString(" ")
}

/** A synced playlist: [trackIds] in Spotify's order. [snapshot] decides whether its tracks are fetched again. */
data class SpPlaylist(
    val id: String,
    val name: String,
    val owner: String,
    val snapshot: String,
    val total: Int,
    val trackIds: List<String>,
) {
    val liked get() = id == LIKED

    companion object { const val LIKED = "liked" }
}

/**
 * The YouTube match of a track. [videoId] null = nothing good enough was found. [rejected] = videos the user said
 * were wrong ("Not this one"); they are never picked again.
 */
data class SpMatch(
    val videoId: String?,
    val ytTitle: String = "",
    val ytArtist: String = "",
    val durationSec: Long = 0,
    val score: Double = 0.0,
    val rejected: List<String> = emptyList(),
)

/**
 * Everything kept in files/spotify.json: [profile] = the Spotify user id typed by the user, [user] = its display
 * name, [playlists] = the playlists chosen for converting (from the profile, or added by link).
 */
data class SpData(
    val user: String = "",
    val synced: Long = 0,
    val playlists: List<SpPlaylist> = emptyList(),
    val tracks: Map<String, SpTrack> = emptyMap(),
    val matches: Map<String, SpMatch> = emptyMap(),
    val profile: String = "",
) {
    fun matched(p: SpPlaylist) = p.trackIds.count { matches[it]?.videoId != null }
    fun missing(p: SpPlaylist) = p.trackIds.filter { matches.containsKey(it) && matches[it]?.videoId == null }
    fun pending(p: SpPlaylist) = p.trackIds.count { !matches.containsKey(it) }

    /** The converted playlist as Youtube-tab rows: Spotify's title and artist, the matched video. */
    fun items(p: SpPlaylist): List<Item> = p.trackIds.mapNotNull { id ->
        val t = tracks[id] ?: return@mapNotNull null
        val m = matches[id] ?: return@mapNotNull null
        val v = m.videoId ?: return@mapNotNull null
        Item(IKind.VIDEO, v, t.title, channel = t.artist, durationSec = m.durationSec, thumbId = v)
    }

    /** The Spotify track behind a converted row (for "Not this one"). */
    fun trackFor(p: SpPlaylist, videoId: String): String? = p.trackIds.firstOrNull { matches[it]?.videoId == videoId }

    fun encode(): String {
        val o = JSONObject().put("v", 2).put("user", user).put("synced", synced).put("profile", profile)
        o.put("playlists", JSONArray().apply {
            playlists.forEach { p ->
                put(JSONObject().put("id", p.id).put("name", p.name).put("owner", p.owner).put("snap", p.snapshot)
                    .put("total", p.total).put("tracks", JSONArray(p.trackIds)))
            }
        })
        o.put("tracks", JSONObject().apply {
            tracks.values.forEach { t ->
                put(t.id, JSONObject().put("t", t.title).put("a", JSONArray(t.artists)).put("al", t.album).put("d", t.durationMs))
            }
        })
        o.put("matches", JSONObject().apply {
            matches.forEach { (k, m) ->
                put(k, JSONObject().apply {
                    m.videoId?.let { put("v", it) }
                    if (m.videoId != null) { put("t", m.ytTitle); put("a", m.ytArtist); put("d", m.durationSec); put("s", m.score) }
                    if (m.rejected.isNotEmpty()) put("x", JSONArray(m.rejected))
                })
            }
        })
        return o.toString()
    }

    companion object {
        fun decode(s: String): SpData = try {
            val o = JSONObject(s)
            val pa = o.optJSONArray("playlists") ?: JSONArray()
            val playlists = (0 until pa.length()).mapNotNull { i ->
                val p = pa.optJSONObject(i) ?: return@mapNotNull null
                SpPlaylist(p.optString("id"), p.optString("name"), p.optString("owner"), p.optString("snap"), p.optInt("total"),
                    strings(p.optJSONArray("tracks")))
            }
            val to = o.optJSONObject("tracks") ?: JSONObject()
            val tracks = HashMap<String, SpTrack>()
            to.keys().forEach { k ->
                val t = to.optJSONObject(k) ?: return@forEach
                tracks[k] = SpTrack(k, t.optString("t"), strings(t.optJSONArray("a")), t.optString("al"), t.optLong("d"))
            }
            val mo = o.optJSONObject("matches") ?: JSONObject()
            val matches = HashMap<String, SpMatch>()
            mo.keys().forEach { k ->
                val m = mo.optJSONObject(k) ?: return@forEach
                matches[k] = SpMatch(m.optString("v").ifEmpty { null }, m.optString("t"), m.optString("a"), m.optLong("d"),
                    m.optDouble("s", 0.0), strings(m.optJSONArray("x")))
            }
            SpData(o.optString("user"), o.optLong("synced"), playlists, tracks, matches, o.optString("profile"))
        } catch (_: Exception) { SpData() }

        private fun strings(a: JSONArray?) = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    }
}

/**
 * Spotify's public pages, read without any account (3.4):
 *  - what the user typed: a profile link / spotify:user: uri / user id, or a playlist link;
 *  - a playlist's embed page (open.spotify.com/embed/playlist/<id>, the page Spotify serves for sharing): its
 *    __NEXT_DATA__ JSON lists up to 100 tracks;
 *  - the JSON our page script builds from the rendered profile / playlist page (see [SpotifyWeb]).
 */
object SpotifyPublic {
    sealed class Target {
        data class Profile(val id: String) : Target()
        data class Playlist(val id: String) : Target()
    }

    /** A playlist on a profile page. */
    data class Ref(val id: String, val name: String)

    /** The embed page holds at most this many tracks: a playlist this long is read from its full page instead. */
    const val EMBED_MAX = 100

    private val LINK = Regex("""(?:open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?|spotify:)(user|playlist)[/:]([^/?#&\s]+)""")
    private val BARE = Regex("""[A-Za-z0-9._-]{1,64}""")

    /** What the user typed -> a profile or a playlist; null when it isn't one (spotify.link short links included). */
    fun target(input: String): Target? {
        val s = input.trim()
        LINK.find(s)?.let { m ->
            val id = try { java.net.URLDecoder.decode(m.groupValues[2], "UTF-8") } catch (_: Exception) { m.groupValues[2] }
            if (id.isBlank()) return null
            return if (m.groupValues[1] == "user") Target.Profile(id) else Target.Playlist(id)
        }
        if (s.contains('/') || s.contains(':')) return null
        return if (BARE.matches(s)) Target.Profile(s) else null
    }

    class Embed(val name: String, val owner: String, val tracks: List<SpTrack>)

    /** The embed page of a playlist -> name, owner and its first (up to [EMBED_MAX]) tracks; null when not a playlist page. */
    fun embed(html: String): Embed? {
        val m = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        val e = try {
            JSONObject(m.groupValues[1]).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
                ?.optJSONObject("data")?.optJSONObject("entity")
        } catch (_: Exception) { null } ?: return null
        val list = e.optJSONArray("trackList") ?: return null
        val tracks = (0 until list.length()).mapNotNull { i ->
            val t = list.optJSONObject(i) ?: return@mapNotNull null
            if (t.optString("entityType", "track") != "track") return@mapNotNull null
            val id = t.optString("uri").removePrefix("spotify:track:").takeIf { it.isNotEmpty() && !it.contains(':') } ?: return@mapNotNull null
            SpTrack(id, t.optString("title"), artists(t.optString("subtitle")), "", t.optLong("duration"))
        }
        return Embed(e.optString("name").ifEmpty { e.optString("title") }, e.optString("subtitle"), tracks)
    }

    /** "A,\u00A0B" (Spotify joins artists with a comma + no-break space; "Tyler, The Creator" keeps a plain space). */
    fun artists(s: String): List<String> = s.split(",\u00A0").map { it.trim() }.filter { it.isNotEmpty() }

    class Profile(val name: String, val lists: List<Ref>, val found: Boolean, val showAll: Boolean)

    /** {"found":true,"name":"…","showAll":true,"lists":[{"id":"…","name":"…"}]} from the profile page script. */
    fun profile(json: String): Profile {
        val o = JSONObject(json)
        val a = o.optJSONArray("lists") ?: JSONArray()
        val seen = HashSet<String>()
        val lists = (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val id = r.optString("id").ifEmpty { return@mapNotNull null }
            if (!seen.add(id)) null else Ref(id, r.optString("name").ifEmpty { id })
        }
        return Profile(o.optString("name"), lists, o.optBoolean("found"), o.optBoolean("showAll"))
    }

    class Page(val name: String, val owner: String, val total: Int, val tracks: List<SpTrack>)

    /** {"name","owner","total":150,"tracks":[{"id","t","a":[…],"d":"3:45"}]} from the playlist page script. */
    fun page(json: String): Page {
        val o = JSONObject(json)
        val a = o.optJSONArray("tracks") ?: JSONArray()
        val seen = HashSet<String>()
        val tracks = (0 until a.length()).mapNotNull { i ->
            val t = a.optJSONObject(i) ?: return@mapNotNull null
            val id = t.optString("id").ifEmpty { return@mapNotNull null }
            if (!seen.add(id)) return@mapNotNull null
            val aa = t.optJSONArray("a") ?: JSONArray()
            SpTrack(id, t.optString("t"), (0 until aa.length()).map { aa.optString(it) }.filter { it.isNotBlank() }, "",
                Browse.parseDuration(t.optString("d")) * 1000)
        }
        return Page(o.optString("name"), o.optString("owner"), o.optInt("total", tracks.size), tracks)
    }

    /** Snapshot of a playlist = its track ids in order (no API snapshot ids without an account). */
    fun snapshot(ids: List<String>): String = "h" + ids.joinToString(",").hashCode().toUInt().toString(16) + ":" + ids.size
}

/** One YouTube Music search result. */
data class YtSong(val videoId: String, val title: String, val artists: List<String>, val album: String, val durationSec: Long)

/** WEB_REMIX `search` answers (songs / videos filters): musicResponsiveListItemRenderer rows. Checked 2026-11-27. */
object RemixSearch {
    /** "Songs" filter of ytmusicapi (YouTube Music's own chip). */
    const val SONGS = "EgWKAQIIAWoMEA4QChADEAQQCRAF"
    /** "Videos" filter. */
    const val VIDEOS = "EgWKAQIQAWoMEA4QChADEAQQCRAF"

    fun parse(json: String): List<YtSong> {
        val rows = ArrayList<JSONObject>()
        collect(JSONObject(json), rows, 0)
        return rows.mapNotNull { r ->
            val cols = r.optJSONArray("flexColumns") ?: return@mapNotNull null
            val id = r.optJSONObject("playlistItemData")?.optString("videoId")?.ifEmpty { null }
                ?: Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"").find(r.toString())?.groupValues?.get(1) ?: return@mapNotNull null
            val title = runs(cols, 0).joinToString("") { it.optString("text") }
            val meta = runs(cols, 1)
            val artists = meta.filter { pageType(it) == "MUSIC_PAGE_TYPE_ARTIST" }.map { it.optString("text") }.ifEmpty {
                // artists without a page: the text before the first " • "
                listOf(meta.joinToString("") { it.optString("text") }.split(" • ").first()).filter { it.isNotBlank() }
            }
            val album = meta.firstOrNull { pageType(it) == "MUSIC_PAGE_TYPE_ALBUM" }?.optString("text").orEmpty()
            val dur = meta.map { it.optString("text").trim() }.lastOrNull { Regex("""\d{1,2}(:\d\d){1,2}""").matches(it) }
            YtSong(id, title, artists, album, Browse.parseDuration(dur))
        }
    }

    private fun runs(cols: JSONArray, i: Int): List<JSONObject> {
        val a = cols.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun pageType(run: JSONObject) = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
        ?.optJSONObject("browseEndpointContextSupportedConfigs")?.optJSONObject("browseEndpointContextMusicConfig")?.optString("pageType")

    private fun collect(v: Any?, out: MutableList<JSONObject>, depth: Int) {
        if (depth > 40) return
        when (v) {
            is JSONObject -> {
                v.optJSONObject("musicResponsiveListItemRenderer")?.let { out += it; return }
                v.keys().forEach { collect(v.opt(it), out, depth + 1) }
            }
            is JSONArray -> for (i in 0 until v.length()) collect(v.opt(i), out, depth + 1)
        }
    }
}

/** Picks the YouTube song that is the Spotify track: same title core, same artist, about the same length. */
object SpMatcher {
    /** Versions that are a different recording unless the Spotify title says so too. */
    private val VARIANTS = listOf("slowed", "sped up", "speed up", "reverb", "nightcore", "8d", "karaoke", "instrumental",
        "cover", "remix", "live", "acoustic", "tribute", "lofi", "lo-fi", "bass boosted", "1 hour", "extended", "mashup")

    /** The title without "feat." parts and remaster / edit / version notes. */
    fun core(title: String): String {
        var t = title
        t = t.replace(Regex("""[(\[](feat\.?|ft\.?|featuring|with) [^)\]]*[)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s.*$""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""[(\[][^)\]]*(remaster|version|edit|mono|stereo|from |soundtrack|deluxe)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("""\s+-\s+[^-]*(remaster|version|edit|mono|stereo|from |soundtrack)[^-]*$""", RegexOption.IGNORE_CASE), " ")
        return t.replace(Regex("""\s+"""), " ").trim()
    }

    private fun words(s: String): Set<String> =
        s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).map { Browse.fold(it) }.filter { it.isNotEmpty() }.toSet()

    /** Dice overlap of the two titles' words (0..1). */
    fun titleSim(spotify: String, yt: String): Double {
        val a = words(core(spotify)); val b = words(core(yt))
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return 2.0 * a.intersect(b).size / (a.size + b.size)
    }

    fun artistMatch(spotify: List<String>, yt: List<String>, ytTitle: String): Boolean {
        val ys = (yt + ytTitle).map { Browse.fold(it) }
        return spotify.any { s -> val f = Browse.fold(s); f.isNotEmpty() && ys.any { y -> y.contains(f) || (y.isNotEmpty() && f.contains(y)) } }
    }

    fun durScore(spotifyMs: Long, ytSec: Long): Double {
        if (spotifyMs <= 0 || ytSec <= 0) return 0.5
        val d = kotlin.math.abs(spotifyMs / 1000.0 - ytSec)
        return when { d <= 3 -> 1.0; d <= 7 -> 0.7; d <= 15 -> 0.3; else -> 0.0 }
    }

    private fun variantPenalty(spotify: String, yt: String): Double {
        val s = spotify.lowercase(); val y = yt.lowercase()
        return if (VARIANTS.any { it in y && it !in s }) 0.4 else 0.0
    }

    fun score(t: SpTrack, c: YtSong): Double =
        0.5 * titleSim(t.title, c.title) + 0.3 * (if (artistMatch(t.artists, c.artists, c.title)) 1.0 else 0.0) +
            0.2 * durScore(t.durationMs, c.durationSec) - variantPenalty(t.title, c.title)

    /**
     * The best acceptable candidate, or null. The artist must match (a cover or another language version by someone
     * else scored high on title and length alone); rejected videos never count.
     */
    fun pick(t: SpTrack, candidates: List<YtSong>, rejected: Collection<String> = emptyList()): Pair<YtSong, Double>? =
        candidates.filter { it.videoId !in rejected && artistMatch(t.artists, it.artists, it.title) }.map { it to score(t, it) }
            .filter { (c, s) -> s >= 0.62 && (t.durationMs <= 0 || c.durationSec <= 0 || kotlin.math.abs(t.durationMs / 1000 - c.durationSec) <= 20) }
            .maxByOrNull { it.second }

    fun match(c: YtSong, score: Double, rejected: List<String> = emptyList()) =
        SpMatch(c.videoId, c.title, c.artists.joinToString(", "), c.durationSec, score, rejected)
}
