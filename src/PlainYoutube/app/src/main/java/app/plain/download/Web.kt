package app.plain.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Xml
import android.widget.ImageView
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class NotFound(msg: String) : IOException(msg)

/**
 * In-flight requests of one screen. [cancelAll] disconnects every open connection (so a blocked read returns at once)
 * and bumps [gen]; results of older generations are dropped by the callers.
 */
class Calls {
    private val open = HashSet<HttpURLConnection>()
    @Volatile var gen = 0
        private set

    fun cancelAll() {
        val list = synchronized(open) { gen++; open.toList().also { open.clear() } }
        for (c in list) try { c.disconnect() } catch (_: Exception) {}
    }

    fun check(g: Int) { if (g != gen) throw Cancelled() }

    fun add(g: Int, c: HttpURLConnection) {
        synchronized(open) { if (g == gen) { open += c; return } }
        c.disconnect(); throw Cancelled()
    }

    fun remove(c: HttpURLConnection) { synchronized(open) { open -= c } }
}

/**
 * The Search tab's network: InnerTube WEB (search, browse, resolve_url) and channel RSS. Every call is started by a
 * user action, honours "Wi-Fi only" (sockets bound to Wi-Fi) and can be cancelled through [Calls].
 */
object WebApi {
    // pseudonymous visitor id handed out by YouTube; memory only, like the player client's
    @Volatile private var visitor: String? = null

    fun search(ctx: Context, calls: Calls, g: Int, query: String, params: String?): JSONObject =
        post(ctx, calls, g, Clients.SEARCH_URL, buildMap { put("query", query); if (params != null) put("params", params) })

    /** [browseId] UC… channels known to be region-blocked are asked with the fallback region directly. */
    fun browse(ctx: Context, calls: Calls, g: Int, browseId: String, params: String?): JSONObject =
        post(ctx, calls, g, Clients.BROWSE_URL, buildMap { put("browseId", browseId); if (params != null) put("params", params) },
            channel = browseId.takeIf { it.startsWith("UC") })

    /** Next page of a search or browse list; [gl] = the region the first page was loaded with ("_gl" in its answer). */
    fun more(ctx: Context, calls: Calls, g: Int, url: String, token: String, gl: String? = null): JSONObject =
        post(ctx, calls, g, url, mapOf("continuation" to token), gl = gl)

    fun resolve(ctx: Context, calls: Calls, g: Int, url: String): Target {
        val o = try { post(ctx, calls, g, Clients.RESOLVE_URL, mapOf("url" to url)) } catch (e: NotFound) {
            throw NotFound("Channel or playlist not found")
        }
        return Browse.resolved(o) ?: throw NotFound("Not a channel or playlist link")
    }

    fun rss(ctx: Context, calls: Calls, g: Int, channelId: String): List<Item> {
        val bytes = request(ctx, calls, g, Clients.RSS_URL + channelId, null, mapOf("User-Agent" to Clients.WEB_UA), 2 shl 20)
        return Rss.parse(Xml.newPullParser(), bytes.inputStream(), channelId)
    }

    /**
     * A channel's members-only uploads: YouTube's public RSS of its UUMO… playlist (no account; verified 2026-11-26:
     * 15 newest members-only uploads; 404 for channels without memberships). Null = no members list.
     */
    fun rssMembers(ctx: Context, calls: Calls, g: Int, channelId: String): List<Item>? {
        val pl = Members.playlistOf(channelId) ?: return null
        val bytes = try { request(ctx, calls, g, Clients.RSS_PLAYLIST_URL + pl, null, mapOf("User-Agent" to Clients.WEB_UA), 2 shl 20) }
            catch (_: NotFound) { return null }
        return Rss.parse(Xml.newPullParser(), bytes.inputStream(), channelId).map { it.copy(members = true) }
    }

    /** Plain GET (HLS playlists) with the same retries, Wi-Fi binding and cancellation. */
    fun get(ctx: Context, calls: Calls, g: Int, url: String, ua: String, limit: Int = 4 shl 20): ByteArray =
        request(ctx, calls, g, url, null, mapOf("User-Agent" to ua), limit)

    /**
     * Music hints for the player's default mode: WEB_REMIX videoDetails.musicVideoType and the WEB microformat
     * category (both small answers; UNPLAYABLE without a PO token, but the metadata is there). Failures -> nulls.
     */
    fun musicMeta(ctx: Context, calls: Calls, g: Int, videoId: String): Pair<String?, String?> {
        val type = try {
            val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
                .put("clientName", Clients.REMIX_NAME).put("clientVersion", Clients.REMIX_VERSION).put("hl", "en")))
                .put("videoId", videoId).toString()
            val h = mapOf("Content-Type" to "application/json", "User-Agent" to Clients.WEB_UA, "Origin" to "https://music.youtube.com",
                "X-YouTube-Client-Name" to Clients.REMIX_ID.toString(), "X-YouTube-Client-Version" to Clients.REMIX_VERSION)
            Modes.musicVideoType(String(request(ctx, calls, g, Clients.REMIX_URL, body, h, 2 shl 20), Charsets.UTF_8))
        } catch (e: Cancelled) { throw e } catch (e: NoWifi) { throw e } catch (_: Exception) { null }
        val cat = try {
            val v = visitor
            Modes.category(String(request(ctx, calls, g, Clients.PLAYER_URL, Clients.webBody(mapOf("videoId" to videoId), v),
                Clients.webHeaders(v), 2 shl 20), Charsets.UTF_8))
        } catch (e: Cancelled) { throw e } catch (e: NoWifi) { throw e } catch (_: Exception) { null }
        return type to cat
    }

    // YouTube Music's own visitor id: its continuation tokens only work with the visitorData of the session that
    // produced them ("Something went wrong" otherwise; verified 2026-11-26). Memory only.
    @Volatile private var remixVisitor: String? = null

    /** YouTube Music (WEB_REMIX) browse: an artist page (UC…) or its discography ("More": MPAD… + params). */
    fun remixBrowse(ctx: Context, calls: Calls, g: Int, browseId: String, params: String?): String =
        remixPost(ctx, calls, g, JSONObject().put("browseId", browseId).apply { if (params != null) put("params", params) })

    /** Next page of a WEB_REMIX list (discography grids). */
    fun remixContinue(ctx: Context, calls: Calls, g: Int, token: String): String =
        remixPost(ctx, calls, g, JSONObject().put("continuation", token))

    private fun remixPost(ctx: Context, calls: Calls, g: Int, fields: JSONObject): String {
        val v = remixVisitor
        val client = JSONObject().put("clientName", Clients.REMIX_NAME).put("clientVersion", Clients.REMIX_VERSION).put("hl", "en")
        if (v != null) client.put("visitorData", v)
        val body = fields.put("context", JSONObject().put("client", client)).toString()
        val h = linkedMapOf("Content-Type" to "application/json", "User-Agent" to Clients.WEB_UA, "Origin" to "https://music.youtube.com",
            "X-YouTube-Client-Name" to Clients.REMIX_ID.toString(), "X-YouTube-Client-Version" to Clients.REMIX_VERSION)
        if (v != null) h["X-Goog-Visitor-Id"] = v
        val s = String(request(ctx, calls, g, Clients.REMIX_BROWSE_URL, body, h, 12 shl 20), Charsets.UTF_8)
        if (remixVisitor == null) try { remixVisitor = Browse.visitorData(JSONObject(s)) } catch (_: Exception) {}
        return s
    }

    /** YouTube Music album of a track (WEB_REMIX `next`, ~20 KB), or null. */
    fun remixAlbum(ctx: Context, calls: Calls, g: Int, videoId: String): String? = try {
        val body = JSONObject().put("context", JSONObject().put("client", JSONObject()
            .put("clientName", Clients.REMIX_NAME).put("clientVersion", Clients.REMIX_VERSION).put("hl", "en")))
            .put("videoId", videoId).toString()
        val h = mapOf("Content-Type" to "application/json", "User-Agent" to Clients.WEB_UA, "Origin" to "https://music.youtube.com",
            "X-YouTube-Client-Name" to Clients.REMIX_ID.toString(), "X-YouTube-Client-Version" to Clients.REMIX_VERSION)
        Albums.fromRemixNext(String(request(ctx, calls, g, Clients.REMIX_NEXT_URL, body, h, 4 shl 20), Charsets.UTF_8), videoId)
    } catch (e: Cancelled) { throw e } catch (e: NoWifi) { throw e } catch (_: Exception) { null }

    /**
     * Search / browse / resolve. When an answer looks region-blocked (an error, a "not available / legal complaint"
     * alert, or an empty channel page where content was expected; see [Browse.regionBlocked]) it is asked once more
     * with ⋮ → Region for blocked videos; a channel that then shows content is remembered and asked that way first
     * next time. The region used is stored in the answer as "_gl" so continuations use the same one.
     */
    private fun post(ctx: Context, calls: Calls, g: Int, url: String, fields: Map<String, String>, gl: String? = null, channel: String? = null): JSONObject {
        val alt = Store.altRegion
        val first = gl ?: if (channel != null && alt.isNotEmpty() && Store.isBlockedChannel(channel)) alt else null
        val o = try { postOnce(ctx, calls, g, url, fields, first) } catch (e: YtError) {
            if (first != null || alt.isEmpty()) throw e
            return postOnce(ctx, calls, g, url, fields, alt).also { it.put("_gl", alt); channel?.let { c -> Store.markBlockedChannel(c) } }
        }
        if (first == null && alt.isNotEmpty() && Browse.regionBlocked(o)) {
            val o2 = try { postOnce(ctx, calls, g, url, fields, alt) } catch (e: Cancelled) { throw e } catch (_: Exception) { null }
            if (o2 != null && !Browse.regionBlocked(o2)) {
                channel?.let { Store.markBlockedChannel(it) }
                return o2.put("_gl", alt)
            }
        }
        if (first != null) o.put("_gl", first)
        return o
    }

    private fun postOnce(ctx: Context, calls: Calls, g: Int, url: String, fields: Map<String, String>, gl: String?): JSONObject {
        val v = visitor
        val body = Clients.webBody(fields, v, gl)
        val bytes = request(ctx, calls, g, url, body, Clients.webHeaders(v), 12 shl 20)
        val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { throw IOException("YouTube sent an unreadable answer") }
        if (visitor == null) visitor = Browse.visitorData(o)
        return o
    }

    /**
     * One request with quick retries: some ISP cache nodes reset many TLS handshakes. 4xx is final
     * (404 -> [NotFound]); network errors and 5xx are retried. Connections are not disconnected after a good answer,
     * so the pool can reuse them (fewer handshakes).
     */
    private fun request(ctx: Context, calls: Calls, g: Int, url: String, body: String?, headers: Map<String, String>, limit: Int): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until 6) {
            calls.check(g)
            val net = Net.network(ctx, Store.wifiOnly) // NoWifi is thrown, not retried
            val con = Net.open(net, url)
            calls.add(g, con)
            var ok = false
            try {
                for ((k, v) in headers) con.setRequestProperty(k, v)
                if (body != null) {
                    con.requestMethod = "POST"
                    con.doOutput = true
                    con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = con.responseCode
                if (code == 404) throw NotFound("Not found")
                if (code in 400..499) throw YtError("YouTube answered HTTP $code", false)
                if (code !in 200..299) throw IOException("HTTP $code")
                val bytes = con.inputStream.use { Net.readAll(it, limit) }
                ok = true
                return bytes
            } catch (e: IOException) {
                calls.check(g) // cancelled: the disconnect caused this
                if (e is NotFound || e is NoWifi) throw e
                last = e
                Thread.sleep(300L * (attempt + 1))
            } finally {
                calls.remove(con)
                if (!ok) try { con.disconnect() } catch (_: Exception) {}
            }
        }
        throw last ?: IOException("Network error")
    }
}

/**
 * Thumbnails for visible rows only: requests are LIFO (the rows on screen now come first), a recycled row cancels
 * its old request, decoded bitmaps are RGB_565 and downsampled to the view width, kept in a small LRU.
 */
class ThumbLoader(private val ctx: Context, private val calls: Calls) {
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(6 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val pool = ThreadPoolExecutor(3, 3, 30, TimeUnit.SECONDS, object : LinkedBlockingDeque<Runnable>() {
        override fun offer(e: Runnable) = offerFirst(e)
    }).apply { allowCoreThreadTimeOut(true) }
    private val pending = HashMap<ImageView, Future<*>>()

    fun load(iv: ImageView, videoId: String?, widthPx: Int) {
        pending.remove(iv)?.cancel(false)
        val url = videoId?.let { Clients.thumbSmall(it) }
        iv.tag = url
        if (url == null) { iv.setImageDrawable(null); return }
        cache.get(url)?.let { iv.setImageBitmap(it); return }
        iv.setImageDrawable(null)
        val g = calls.gen
        pending[iv] = pool.submit {
            val bm = try {
                if (calls.gen != g) return@submit
                val bytes = fetch(url)
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                var s = 1
                while (o.outWidth / (s * 2) >= widthPx && widthPx > 0) s *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                    inSampleSize = s; inPreferredConfig = Bitmap.Config.RGB_565
                })
            } catch (_: Exception) { null } ?: return@submit
            main.post {
                cache.put(url, bm)
                if (iv.tag == url && calls.gen == g) { iv.setImageBitmap(bm); pending.remove(iv) }
            }
        }
    }

    /** Small GET with 3 quick tries (handshake resets); keeps the connection pooled on success. */
    private fun fetch(url: String): ByteArray {
        var last: IOException? = null
        repeat(3) { attempt ->
            val con = Net.open(Net.network(ctx, Store.wifiOnly), url)
            try {
                if (con.responseCode !in 200..299) throw NotFound("HTTP ${con.responseCode}")
                return con.inputStream.use { Net.readAll(it, 1 shl 20) }
            } catch (e: IOException) {
                con.disconnect()
                if (e is NotFound || e is NoWifi) throw e
                last = e
                Thread.sleep(200L * (attempt + 1))
            }
        }
        throw last!!
    }

    /** Drop queued requests (screen hidden). Cached bitmaps stay. */
    fun cancel() {
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        pool.queue.clear()
    }

    fun shutdown() { cancel(); pool.shutdownNow(); cache.evictAll() }
}
