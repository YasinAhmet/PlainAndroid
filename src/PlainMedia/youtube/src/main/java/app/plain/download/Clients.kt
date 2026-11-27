package app.plain.download

import org.json.JSONObject

/**
 * InnerTube clients used to ask YouTube for stream URLs.
 *
 * THIS IS THE FILE TO UPDATE WHEN DOWNLOADS BREAK. YouTube regularly retires client versions or starts demanding
 * PO tokens / the JS player from a client. Compare with yt-dlp's current table:
 *   https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_base.py  (INNERTUBE_CLIENTS)
 *   https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/youtube/_video.py (_DEFAULT_*CLIENTS)
 * Only clients with REQUIRE_JS_PLAYER = False and no required GVS PO token are usable here: their stream URLs are
 * plain (no signatureCipher, no "n" transform) and can be fetched directly.
 *
 * Values copied from yt-dlp master (Nov 2026) and verified from a PC on 2026-11-25:
 *  - VISIONOS     : yt-dlp's default JS-less client. All formats (AVC/VP9/AV1 + AAC/Opus). Without visitorData it often
 *                   answers "Sign in to confirm you're not a bot"; retrying with the visitorData from that answer works.
 *  - ANDROID_VR   : same full format list, plus progressive itag 18. yt-dlp notes intermittent PO-token enforcement.
 *  - IOS_PRODUCER : H.264 + AAC only (enough for MP4/M4A). Worked even without visitorData.
 */
class Client(
    val key: String,
    val clientName: String,
    val clientId: Int, // X-YouTube-Client-Name
    val version: String,
    val userAgent: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val androidSdkVersion: Int? = null,
) {
    override fun toString() = key
}

object Clients {
    const val HOST = "https://www.youtube.com"
    const val PLAYER_URL = "$HOST/youtubei/v1/player?prettyPrint=false"

    val VISIONOS = Client(
        key = "visionos", clientName = "VISIONOS", clientId = 101, version = "1.02",
        userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15",
        deviceMake = "Apple", deviceModel = "RealityDevice17,1", osName = "visionOS", osVersion = "26.5.23O471",
    )

    val ANDROID_VR = Client(
        key = "android_vr", clientName = "ANDROID_VR", clientId = 28, version = "1.65.10",
        userAgent = "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
        deviceMake = "Oculus", deviceModel = "Quest 3", osName = "Android", osVersion = "12L", androidSdkVersion = 32,
    )

    val IOS_PRODUCER = Client(
        key = "ios_producer", clientName = "IOS_PRODUCER", clientId = 92, version = "0.147.945",
        userAgent = "com.google.ios.youtubeproducer/0.147.945 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)",
        deviceMake = "Apple", deviceModel = "iPhone16,2", osName = "iPhone", osVersion = "18.3.2.22D82",
    )

    /** Tried in this order; the first one that returns playable direct URLs wins. */
    val ORDER = listOf(VISIONOS, ANDROID_VR, IOS_PRODUCER)

    /**
     * In-app player. Verified 2026-11-26: VISIONOS and IOS_PRODUCER return an HLS master (video-only TS variants +
     * separate AAC audio groups) and full-range adaptive streams; ANDROID_VR returns no HLS and its streams (also
     * progressive itag 18) answer 403 past the first ~5 MB without a PO token, so it is last and only for Listen.
     */
    val PLAY_ORDER = listOf(VISIONOS, IOS_PRODUCER, ANDROID_VR)
    val HLS_ORDER = listOf(VISIONOS, IOS_PRODUCER)

    // Music detection for the player's default mode (metadata only; these answer UNPLAYABLE without a PO token but
    // still carry the fields): WEB_REMIX -> videoDetails.musicVideoType, WEB -> microformat category.
    const val REMIX_URL = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false"
    const val REMIX_NEXT_URL = "https://music.youtube.com/youtubei/v1/next?prettyPrint=false" // album of a track
    const val REMIX_BROWSE_URL = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false" // artist pages (Music tab)
    const val REMIX_SEARCH_URL = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false" // Spotify matching
    const val REMIX_NAME = "WEB_REMIX"
    const val REMIX_ID = 67
    const val REMIX_VERSION = "1.20260707.12.00" // yt-dlp 'web_music'

    fun byKey(key: String?): Client? = ORDER.firstOrNull { it.key == key } ?: TvPlayer.CLIENT.takeIf { it.key == key }

    /** JSON body for POST /youtubei/v1/player. No account, no cookies; visitorData only if YouTube handed us one. */
    /**
     * [gl] = content region. Videos blocked in a country "due to a legal complaint" are blocked per *country domain*
     * (the region in the request), not per IP: verified 2026-11-26 from a Turkish IP, gl=US returned the video and
     * googlevideo served every byte range. Null = let YouTube pick the region from the network (normal).
     */
    fun playerBody(c: Client, videoId: String, visitorData: String?, gl: String? = null): String {
        val client = JSONObject()
            .put("clientName", c.clientName)
            .put("clientVersion", c.version)
            .put("userAgent", c.userAgent)
            .put("hl", "en")
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        c.deviceMake?.let { client.put("deviceMake", it) }
        c.deviceModel?.let { client.put("deviceModel", it) }
        c.osName?.let { client.put("osName", it) }
        c.osVersion?.let { client.put("osVersion", it) }
        c.androidSdkVersion?.let { client.put("androidSdkVersion", it) }
        if (!visitorData.isNullOrEmpty()) client.put("visitorData", visitorData)
        if (!gl.isNullOrEmpty()) client.put("gl", gl)
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("playbackContext", JSONObject().put("contentPlaybackContext", JSONObject().put("html5Preference", "HTML5_PREF_WANTS")))
            .put("contentCheckOk", true)
            .put("racyCheckOk", true)
            .toString()
    }

    fun playerHeaders(c: Client, visitorData: String?): Map<String, String> {
        val h = linkedMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to c.userAgent,
            "X-YouTube-Client-Name" to c.clientId.toString(),
            "X-YouTube-Client-Version" to c.version,
            "Origin" to HOST,
        )
        if (!visitorData.isNullOrEmpty()) h["X-Goog-Visitor-Id"] = visitorData
        return h
    }

    // -----------------------------------------------------------------------------------------------------------
    // Search tab: the plain WEB client, no account, no cookies (search / browse / resolve_url need no PO token).
    // UPDATE THESE WHEN SEARCH OR CHANNEL PAGES BREAK:
    //  - WEB_VERSION: 'web' -> INNERTUBE_CONTEXT.client.clientVersion in yt-dlp's _base.py
    //  - SEARCH_*: the "sp=" value of youtube.com/results?search_query=x&sp=... after picking a Type filter
    //  - TAB_*: tabRenderer.endpoint.browseEndpoint.params of a channel page. The app reads the real values from the
    //    first channel answer (by tab URL .../videos, /shorts, /playlists); these are only the starting/fallback values.
    // Verified from a PC on 2026-11-26 (search, channel Videos/Shorts/Playlists + continuations, a 6,000-video
    // playlist paged 100 at a time, resolve_url for @handle / c/ / channel/ / playlist URLs, RSS).

    const val WEB_NAME = "WEB"
    const val WEB_ID = 1
    const val WEB_VERSION = "2.20260708.00.00" // yt-dlp master, Nov 2026
    const val WEB_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    const val SEARCH_URL = "$HOST/youtubei/v1/search?prettyPrint=false"
    const val BROWSE_URL = "$HOST/youtubei/v1/browse?prettyPrint=false"
    const val RESOLVE_URL = "$HOST/youtubei/v1/navigation/resolve_url?prettyPrint=false"
    const val RSS_URL = "$HOST/feeds/videos.xml?channel_id=" // + UC… : 15 newest uploads, public, no API key
    const val RSS_PLAYLIST_URL = "$HOST/feeds/videos.xml?playlist_id=" // + UUMO… : a channel's members-only uploads

    const val SEARCH_VIDEOS = "EgIQAQ%3D%3D" // Type: Video
    const val SEARCH_CHANNELS = "EgIQAg%3D%3D" // Type: Channel
    const val SEARCH_PLAYLISTS = "EgIQAw%3D%3D" // Type: Playlist

    const val TAB_VIDEOS = "EgZ2aWRlb3PyBgQKAjoA"
    const val TAB_SHORTS = "EgZzaG9ydHPyBgUKA5oBAA%3D%3D"
    const val TAB_PLAYLISTS = "EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA" // changed in 2026 (the older EgZwbGF5bGlzdHPyBgQKAkIA now lands on Home)

    /** 320x180 thumbnail; small enough to decode as-is. */
    fun thumbSmall(videoId: String) = if (videoId.startsWith("https://")) videoId else "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

    /** JSON body for the WEB client: [fields] are merged in (query/params, browseId/params, continuation, url). */
    fun webBody(fields: Map<String, String>, visitorData: String?, gl: String? = null): String {
        val client = JSONObject()
            .put("clientName", WEB_NAME)
            .put("clientVersion", WEB_VERSION)
            .put("userAgent", WEB_UA)
            .put("hl", "en") // English texts ("3 days ago", "1.2M views") are what the parser understands
            .put("timeZone", "UTC")
            .put("utcOffsetMinutes", 0)
        // no "gl" normally: YouTube picks the region from the network, like the website does. [gl] = the
        // "region for blocked videos" retry (country blocks are per content region, see playerBody).
        if (!gl.isNullOrEmpty()) client.put("gl", gl)
        if (!visitorData.isNullOrEmpty()) client.put("visitorData", visitorData)
        val o = JSONObject().put("context", JSONObject().put("client", client))
        for ((k, v) in fields) o.put(k, v)
        return o.toString()
    }

    fun webHeaders(visitorData: String?): Map<String, String> {
        val h = linkedMapOf(
            "Content-Type" to "application/json",
            "User-Agent" to WEB_UA,
            "X-YouTube-Client-Name" to WEB_ID.toString(),
            "X-YouTube-Client-Version" to WEB_VERSION,
            "Origin" to HOST,
        )
        if (!visitorData.isNullOrEmpty()) h["X-Goog-Visitor-Id"] = visitorData
        return h
    }
}
