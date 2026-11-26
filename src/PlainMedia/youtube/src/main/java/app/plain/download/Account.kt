package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import app.plain.lang.tr

/*
 * 2.0 account support: pure logic only (no Android). Nothing here logs, prints or puts a cookie value in an
 * exception message. Cookie VALUES only leave this file inside [CookieJar.header] / [SidAuth.header] results, which
 * only [AuthHttp] sends.
 */

/** One stored account cookie. [expires] = epoch seconds, 0 = session cookie. */
data class Cookie(
    val domain: String, // lower case, no leading dot: "youtube.com"
    val name: String,
    val value: String,
    val path: String = "/",
    val expires: Long = 0,
    val httpOnly: Boolean = false,
) {
    // never print values (logcat, crash text, toString in a debugger)
    override fun toString() = "Cookie($domain, $name, <${value.length} chars>)"
}

/**
 * Which cookies are kept and where they may go.
 *
 * Kept: only YouTube's sign-in cookies on youtube.com. Everything else in a cookies.txt (other sites, google.com,
 * tracking/preferences cookies such as VISITOR_INFO1_LIVE, PREF, YSC) is dropped at import. google.com cookies are
 * dropped too: they would never be sent anywhere (see [SEND_HOSTS]), so storing them would only add risk.
 */
object CookiePolicy {
    val NAMES = setOf(
        "SID", "HSID", "SSID", "APISID", "SAPISID", "LOGIN_INFO", "SIDCC",
        "__Secure-1PSID", "__Secure-3PSID", "__Secure-1PAPISID", "__Secure-3PAPISID",
        "__Secure-1PSIDTS", "__Secure-3PSIDTS", "__Secure-1PSIDCC", "__Secure-3PSIDCC",
    )

    /** The only hosts an account cookie is ever sent to (https only). googlevideo.com is allowed but nothing needs it. */
    val SEND_HOSTS = setOf("www.youtube.com", "youtube.com", "music.youtube.com", "m.youtube.com")

    fun keepDomain(d: String) = normDomain(d) == "youtube.com"

    fun normDomain(d: String) = d.trim().lowercase(Locale.ROOT).removePrefix(".")

    fun keep(c: Cookie) = c.name in NAMES && keepDomain(c.domain) && c.value.isNotEmpty() && c.value.length < 4096

    /** True when [url] is https on an allowed host. */
    fun hostAllowed(url: String): Boolean {
        val m = Regex("^https://([^/:?#]+)(?::443)?(?:[/?#]|$)", RegexOption.IGNORE_CASE).find(url) ?: return false
        return m.groupValues[1].lowercase(Locale.ROOT) in SEND_HOSTS
    }

    /** Signed in = YouTube's LOGIN_INFO plus one of the SAPISID variants (as yt-dlp checks it). */
    fun signedIn(cookies: Collection<Cookie>): Boolean {
        val n = cookies.map { it.name }.toSet()
        return "LOGIN_INFO" in n && ("SAPISID" in n || "__Secure-3PAPISID" in n || "__Secure-1PAPISID" in n)
    }
}

/** Parsed import: the kept cookies + how many lines were dropped (counts only, never names of other sites). */
class Imported(val cookies: List<Cookie>, val dropped: Int)

object CookieFile {
    /**
     * Netscape cookies.txt (the format of curl, yt-dlp and the "cookies.txt" browser add-ons):
     * domain, includeSubdomains, path, secure, expiry, name, value - tab separated. "#HttpOnly_" prefixes a domain.
     */
    fun parse(text: String, nowSec: Long): Imported {
        val out = LinkedHashMap<String, Cookie>()
        var dropped = 0
        for (raw in text.lineSequence()) {
            var line = raw.trimEnd('\r', '\n')
            if (line.isBlank()) continue
            var httpOnly = false
            if (line.startsWith("#HttpOnly_")) { line = line.removePrefix("#HttpOnly_"); httpOnly = true }
            else if (line.startsWith("#")) continue
            val f = line.split('\t')
            if (f.size < 7) { dropped++; continue }
            val exp = f[4].trim().toDoubleOrNull()?.toLong() ?: 0L
            val c = Cookie(CookiePolicy.normDomain(f[0]), f[5].trim(), f.subList(6, f.size).joinToString("\t").trim(), f[2].ifBlank { "/" }, exp, httpOnly)
            if (!CookiePolicy.keep(c) || (exp in 1 until nowSec)) { dropped++; continue }
            out[c.name] = c // one per name (youtube.com only)
        }
        return Imported(out.values.toList(), dropped)
    }

    /**
     * Cookies read from Android's CookieManager after the in-app sign-in: "a=1; b=2" for a URL. CookieManager gives
     * no attributes, so they are stored as session cookies of youtube.com (YouTube rotates them anyway).
     */
    fun fromHeader(header: String?): List<Cookie> {
        if (header.isNullOrBlank()) return emptyList()
        val out = LinkedHashMap<String, Cookie>()
        for (part in header.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) continue
            val c = Cookie("youtube.com", part.substring(0, eq).trim(), part.substring(eq + 1).trim())
            if (CookiePolicy.keep(c)) out[c.name] = c
        }
        return out.values.toList()
    }
}

/** The account cookie jar (in memory; persisted only encrypted by AccountStore). */
class CookieJar(cookies: List<Cookie> = emptyList()) {
    private val map = LinkedHashMap<String, Cookie>().apply { for (c in cookies) if (CookiePolicy.keep(c)) put(c.name, c) }

    val all: List<Cookie> get() = synchronized(map) { map.values.toList() }
    val size get() = synchronized(map) { map.size }
    operator fun get(name: String): String? = synchronized(map) { map[name]?.value }
    val signedIn get() = CookiePolicy.signedIn(all)

    /** Cookie header for [url], or null when the URL is not https on an allowed YouTube host. */
    fun header(url: String, nowSec: Long): String? {
        if (!CookiePolicy.hostAllowed(url)) return null
        val live = all.filter { it.expires == 0L || it.expires > nowSec }
        if (live.isEmpty()) return null
        return live.joinToString("; ") { "${it.name}=${it.value}" }
    }

    /**
     * Applies Set-Cookie headers of an authenticated answer from [host] (YouTube rotates __Secure-*PSIDTS / SIDCC).
     * Only kept names on youtube.com change; expired/empty ones are removed. Returns true when the jar changed.
     */
    fun apply(setCookies: List<String>, host: String, nowSec: Long): Boolean {
        if (CookiePolicy.normDomain(host).let { it != "youtube.com" && !it.endsWith(".youtube.com") }) return false
        var changed = false
        synchronized(map) {
            for (h in setCookies) {
                val sc = SetCookie.parse(h, nowSec) ?: continue
                val domain = sc.domain?.let { CookiePolicy.normDomain(it) } ?: "youtube.com"
                if (domain != "youtube.com" || sc.name !in CookiePolicy.NAMES) continue
                if (sc.delete) { if (map.remove(sc.name) != null) changed = true; continue }
                val old = map[sc.name]
                val c = Cookie("youtube.com", sc.name, sc.value, sc.path ?: "/", sc.expires, sc.httpOnly)
                if (old == null || old.value != c.value || old.expires != c.expires) { map[sc.name] = c; changed = true }
            }
        }
        return changed
    }

    fun encode(): String = JSONArray().apply {
        for (c in all) put(JSONObject().put("d", c.domain).put("n", c.name).put("v", c.value).put("p", c.path).put("e", c.expires).put("h", c.httpOnly))
    }.toString()

    companion object {
        fun decode(s: String): CookieJar {
            val a = JSONArray(s)
            val l = ArrayList<Cookie>()
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                l += Cookie(o.getString("d"), o.getString("n"), o.getString("v"), o.optString("p", "/"), o.optLong("e"), o.optBoolean("h"))
            }
            return CookieJar(l)
        }
    }
}

/** One parsed Set-Cookie header. */
class SetCookie(val name: String, val value: String, val domain: String?, val path: String?, val expires: Long, val httpOnly: Boolean, val delete: Boolean) {
    override fun toString() = "SetCookie($name)"

    companion object {
        private val DATES = listOf("EEE, dd-MMM-yyyy HH:mm:ss zzz", "EEE, dd MMM yyyy HH:mm:ss zzz", "EEE, dd-MMM-yy HH:mm:ss zzz")

        fun parseDate(s: String): Long? {
            for (p in DATES) {
                val f = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
                try { return f.parse(s.trim())!!.time / 1000 } catch (_: Exception) {}
            }
            return null
        }

        fun parse(h: String, nowSec: Long): SetCookie? {
            val parts = h.split(';')
            val nv = parts[0]
            val eq = nv.indexOf('=')
            if (eq <= 0) return null
            val name = nv.substring(0, eq).trim()
            val value = nv.substring(eq + 1).trim().removeSurrounding("\"")
            var domain: String? = null
            var path: String? = null
            var expires = 0L
            var maxAge: Long? = null
            var httpOnly = false
            for (a in parts.drop(1)) {
                val k = a.substringBefore('=').trim().lowercase(Locale.ROOT)
                val v = a.substringAfter('=', "").trim()
                when (k) {
                    "domain" -> domain = v
                    "path" -> path = v
                    "expires" -> expires = parseDate(v) ?: expires
                    "max-age" -> maxAge = v.toLongOrNull()
                    "httponly" -> httpOnly = true
                }
            }
            maxAge?.let { expires = if (it <= 0) 1 else nowSec + it } // Max-Age wins over Expires
            val delete = value.isEmpty() || (expires in 1..nowSec)
            return SetCookie(name, value, domain, path, if (delete) 0 else expires, httpOnly, delete)
        }
    }
}

/**
 * YouTube's cookie authorization header for InnerTube (what the website sends; as in yt-dlp's
 * _get_sid_authorization_header): "SAPISIDHASH <ts>_<sha1(ts SAPISID origin)> SAPISID1PHASH … SAPISID3PHASH …".
 */
object SidAuth {
    fun sha1Hex(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun part(scheme: String, sid: String, origin: String, ts: Long, userSession: String? = null): String {
        val hashParts = ArrayList<String>()
        if (userSession != null) hashParts += userSession
        hashParts += listOf(ts.toString(), sid, origin)
        val parts = arrayListOf(ts.toString(), sha1Hex(hashParts.joinToString(" ")))
        if (userSession != null) parts += "u"
        return "$scheme ${parts.joinToString("_")}"
    }

    /** Null when no SAPISID-type cookie is present. */
    fun header(jar: CookieJar, origin: String, ts: Long): String? {
        val sap = jar["SAPISID"] ?: jar["__Secure-3PAPISID"]
        val out = ArrayList<String>()
        sap?.let { out += part("SAPISIDHASH", it, origin, ts) }
        jar["__Secure-1PAPISID"]?.let { out += part("SAPISID1PHASH", it, origin, ts) }
        jar["__Secure-3PAPISID"]?.let { out += part("SAPISID3PHASH", it, origin, ts) }
        return if (out.isEmpty()) null else out.joinToString(" ")
    }
}

/**
 * THE separation rule. Every request that could carry the account asks this one function; everything else uses
 * the anonymous request code, which has no way to attach cookies.
 */
enum class Purpose(val accountUse: Boolean) {
    ACCOUNT_CHECK(true), // account_menu: name + "still signed in?" (user pressed Test / just signed in)
    UNSUBSCRIBE(true), // subscription/unsubscribe, only after the user confirmed "Also unsubscribe on YouTube" (2.1)
    SUBSCRIPTIONS_SYNC(true), // FEchannels (user pressed Sync subscriptions)
    MEMBERS_PLAYER(true), // /player for a video known to be members-only
    MEMBERS_TAB(true), // a channel's members-only videos, when the user opens that tab
    STREAM(false), // googlevideo bytes: the URL itself is the authorization; no cookies needed
    PLAYER_JS(false), // iframe_api / tv-player-ias.js: public files
    SEARCH(false), BROWSE(false), FEED(false), PLAYER(false), MUSIC(false), THUMBNAIL(false), OTHER(false),
}

object AuthRule {
    /**
     * True only when the account may be attached: signed in, an account purpose, members-only content for the two
     * members purposes, and https on a YouTube host.
     */
    fun useAccount(purpose: Purpose, url: String, signedIn: Boolean, membersOnly: Boolean): Boolean {
        if (!signedIn || !purpose.accountUse) return false
        if (!CookiePolicy.hostAllowed(url)) return false
        return when (purpose) {
            Purpose.ACCOUNT_CHECK, Purpose.SUBSCRIPTIONS_SYNC -> true
            // youtube.com itself only (not music.youtube.com)
            Purpose.UNSUBSCRIBE -> Regex("^https://(www\\.)?youtube\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(url)
            Purpose.MEMBERS_PLAYER, Purpose.MEMBERS_TAB -> membersOnly
            else -> false
        }
    }
}

/** Members-only detection in listings and in /player answers. */
object Members {
    private val STYLES = setOf("BADGE_STYLE_TYPE_MEMBERS_ONLY", "BADGE_MEMBERS_ONLY")
    private val TEXTS = setOf("members only", "members first", "members-only")

    /** A renderer (videoRenderer / lockupViewModel / playlistVideoRenderer…) marked as members-only. */
    fun inRenderer(o: Any?, depth: Int = 0): Boolean {
        if (depth > 16) return false
        when (o) {
            is JSONObject -> {
                val keys = o.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = o.opt(k)
                    if (v is String) {
                        if ((k == "style" || k == "badgeStyle") && v in STYLES) return true
                        if ((k == "label" || k == "badgeText" || k == "text") && v.trim().lowercase(Locale.ROOT) in TEXTS) return true
                    } else if (inRenderer(v, depth + 1)) return true
                }
            }
            is JSONArray -> for (i in 0 until o.length()) if (inRenderer(o.opt(i), depth + 1)) return true
        }
        return false
    }

    /** /player refusal text for members-only videos ("Join this channel to get access to members-only content…"). */
    fun inReason(reason: String): Boolean {
        val r = reason.lowercase(Locale.ROOT)
        return "members-only" in r || "members only" in r || "join this channel" in r || "available to this channel's members" in r ||
            ("member" in r && "channel" in r)
    }

    /** The members-only uploads playlist of a channel (UC… -> UUMO…), what the Members tab browses. */
    fun playlistOf(channelId: String): String? = if (channelId.startsWith("UC") && channelId.length == 24) "UUMO" + channelId.substring(2) else null
}

/** account_menu / FEchannels answers. */
object AccountParse {
    /** Display name from youtubei/v1/account/account_menu, or null when signed out. */
    fun accountName(json: String): String? {
        val o = try { JSONObject(json) } catch (_: Exception) { return null }
        var found: String? = null
        fun walk(v: Any?, d: Int) {
            if (found != null || d > 24) return
            when (v) {
                is JSONObject -> {
                    v.optJSONObject("activeAccountHeaderRenderer")?.let { h ->
                        found = Browse.text(h.opt("accountName")).ifEmpty { null }
                        if (found != null) return
                    }
                    val k = v.keys(); while (k.hasNext()) walk(v.opt(k.next()), d + 1)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), d + 1)
            }
        }
        walk(o, 0)
        return found
    }

    /** True when an authenticated answer says we're signed out (responseContext.mainAppWebResponseContext.loggedOut). */
    fun loggedOut(json: String): Boolean {
        val o = try { JSONObject(json) } catch (_: Exception) { return false }
        val m = o.optJSONObject("responseContext")?.optJSONObject("mainAppWebResponseContext") ?: return false
        return m.optBoolean("loggedOut", false)
    }

    class Sub(val id: String, val name: String, val handle: String)

    /** Channels of FEchannels (channelRenderer or lockup channel items) + continuation token. */
    fun subscriptions(json: String): Pair<List<Sub>, String?> {
        val o = JSONObject(json)
        val out = LinkedHashMap<String, Sub>()
        fun walk(v: Any?, d: Int) {
            if (d > 40) return
            when (v) {
                is JSONObject -> {
                    val cr = v.optJSONObject("channelRenderer")
                    if (cr != null) {
                        val id = cr.optString("channelId")
                        if (id.startsWith("UC")) {
                            val sub = Browse.text(cr.opt("subscriberCountText"))
                            // on FEchannels the handle sits in subscriberCountText and the count in videoCountText
                            val handle = listOf(sub, Browse.text(cr.opt("videoCountText"))).firstOrNull { it.startsWith("@") }.orEmpty()
                            out.putIfAbsent(id, Sub(id, Browse.text(cr.opt("title")), handle))
                        }
                        return
                    }
                    val k = v.keys(); while (k.hasNext()) walk(v.opt(k.next()), d + 1)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), d + 1)
            }
        }
        walk(o, 0)
        return out.values.toList() to Browse.page(o).continuation
    }
}

/**
 * 2.1 unsubscribe: POST https://www.youtube.com/youtubei/v1/subscription/unsubscribe with the WEB context and
 * `channelIds: ["UC…"]` - the web client's unsubscribeEndpoint. Its `params` (UI-source context such as the watch page
 * that showed the button) is optional: YouTube Music's own unsubscribeEndpoint has none, so none is sent.
 */
object Unsub {
    const val URL = "https://www.youtube.com/youtubei/v1/subscription/unsubscribe?prettyPrint=false"
    private val UC = Regex("^UC[A-Za-z0-9_-]{22}$")

    fun body(channelId: String): String {
        require(UC.matches(channelId)) { "Not a channel id" }
        return JSONObject(Clients.webBody(emptyMap(), null)).put("channelIds", JSONArray().put(channelId)).toString()
    }

    /**
     * Null = unsubscribed (YouTube's updateSubscribeButtonAction says subscribed=false for the channel, or a plain
     * answer without an error); otherwise the reason it failed.
     */
    fun failure(json: String, channelId: String): String? {
        val o = try { JSONObject(json) } catch (_: Exception) { return "unreadable answer" }
        o.optJSONObject("error")?.let { e -> return e.optString("message").ifEmpty { "error ${e.optInt("code")}" } }
        var subscribed: Boolean? = null
        fun walk(v: Any?, d: Int) {
            if (d > 20 || subscribed != null) return
            when (v) {
                is JSONObject -> {
                    v.optJSONObject("updateSubscribeButtonAction")?.let { a ->
                        if (a.optString("channelId").let { it.isEmpty() || it == channelId }) { subscribed = a.optBoolean("subscribed", false); return }
                    }
                    val k = v.keys(); while (k.hasNext()) walk(v.opt(k.next()), d + 1)
                }
                is JSONArray -> for (i in 0 until v.length()) walk(v.opt(i), d + 1)
            }
        }
        walk(o, 0)
        return if (subscribed == true) tr("YouTube still shows the subscription", "YouTube aboneliği hâlâ gösteriyor") else null
    }
}

/** Subscription sync merge: what to add, what's already followed, what's followed but no longer subscribed. */
class SyncPlan(val add: List<AccountParse.Sub>, val already: Int, val gone: List<String>) {
    companion object {
        fun of(subs: List<AccountParse.Sub>, followed: Set<String>): SyncPlan {
            val ids = subs.map { it.id }.toSet()
            val add = subs.filter { it.id !in followed }
            return SyncPlan(add, subs.size - add.size, followed.filter { it !in ids })
        }
    }

    fun summary() = tr("Added ${add.size}, already followed $already", "Eklendi: ${add.size}, zaten takip edilen: $already")
}

/**
 * The signed-in /player request (TVHTML5, yt-dlp's "tv" client, which accepts cookies and has no PO-token policy for
 * streams; it needs the JS player: signatureTimestamp in the request, "n" (and "s" for signatureCipher) solved).
 * The parts here are pure so they're tested; [AuthPlayer] does the network.
 */
object TvPlayer {
    const val KEY = "tv_account"
    const val NAME = "TVHTML5"
    const val ID = 7
    const val VERSION = "5.20260707" // yt-dlp 'tv' / 'tv_samsung'
    const val UA = "Mozilla/5.0 (SMART-TV; Linux; Tizen 2.4.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/2.4.0 TV Safari/538.1"
    const val ORIGIN = "https://www.youtube.com"
    const val IFRAME_API = "https://www.youtube.com/iframe_api"

    val CLIENT = Client(KEY, NAME, ID, VERSION, UA, deviceMake = "Samsung", deviceModel = "UKS9800", osName = "Tizen", osVersion = "2.4.0")

    fun playerId(iframeApi: String): String? = Regex("player.{0,4}/([0-9a-fA-F]{8})").find(iframeApi)?.groupValues?.get(1)

    fun playerUrl(id: String) = "https://www.youtube.com/s/player/$id/tv-player-ias.vflset/tv-player-ias.js"

    fun sts(playerJs: String): Int? = Regex("(?:signatureTimestamp|sts)\\s*:\\s*(\\d{5})").find(playerJs)?.groupValues?.get(1)?.toIntOrNull()

    fun body(videoId: String, sts: Int, visitorData: String?): String {
        val client = JSONObject().put("clientName", NAME).put("clientVersion", VERSION).put("userAgent", UA)
            .put("deviceMake", "Samsung").put("deviceModel", "UKS9800").put("osName", "Tizen").put("osVersion", "2.4.0")
            .put("hl", "en").put("timeZone", "UTC").put("utcOffsetMinutes", 0)
        if (!visitorData.isNullOrEmpty()) client.put("visitorData", visitorData)
        return JSONObject()
            .put("context", JSONObject().put("client", client))
            .put("videoId", videoId)
            .put("playbackContext", JSONObject().put("contentPlaybackContext",
                JSONObject().put("html5Preference", "HTML5_PREF_WANTS").put("signatureTimestamp", sts)))
            .put("contentCheckOk", true).put("racyCheckOk", true)
            .toString()
    }

    /** Challenges in an answer: "s" values of signatureCipher and "n" params of the stream URLs. */
    class Challenges(val sig: List<String>, val n: List<String>) { val empty get() = sig.isEmpty() && n.isEmpty() }

    private fun forEachFormat(o: JSONObject, f: (JSONObject) -> Unit) {
        val sd = o.optJSONObject("streamingData") ?: return
        for (k in listOf("formats", "adaptiveFormats")) {
            val a = sd.optJSONArray(k) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.let(f)
        }
    }

    fun query(s: String): Map<String, String> = s.split('&').mapNotNull { p ->
        val eq = p.indexOf('=')
        if (eq <= 0) null else java.net.URLDecoder.decode(p.substring(0, eq), "UTF-8") to java.net.URLDecoder.decode(p.substring(eq + 1), "UTF-8")
    }.toMap()

    fun nOf(url: String): String? = query(url.substringAfter('?', "")).get("n")

    fun challenges(json: String): Challenges {
        val o = JSONObject(json)
        val sig = LinkedHashSet<String>(); val n = LinkedHashSet<String>()
        forEachFormat(o) { f ->
            val url = f.optString("url").ifEmpty {
                val sc = query(f.optString("signatureCipher"))
                sc["s"]?.let { sig += it }
                sc["url"].orEmpty()
            }
            nOf(url)?.let { n += it }
        }
        return Challenges(sig.toList(), n.toList())
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    /** Puts solved values into every stream URL (unsolvable formats are dropped). Returns the rewritten answer. */
    fun rewrite(json: String, sig: Map<String, String>, n: Map<String, String>): String {
        val o = JSONObject(json)
        val sd = o.optJSONObject("streamingData") ?: return json
        for (k in listOf("formats", "adaptiveFormats")) {
            val a = sd.optJSONArray(k) ?: continue
            val keep = JSONArray()
            for (i in 0 until a.length()) {
                val f = a.optJSONObject(i) ?: continue
                var url = f.optString("url")
                if (url.isEmpty()) {
                    val sc = query(f.optString("signatureCipher"))
                    val s = sc["s"]; val base = sc["url"]
                    val solved = s?.let { sig[it] }
                    if (base == null || solved == null) continue
                    url = base + (if ('?' in base) "&" else "?") + (sc["sp"] ?: "signature") + "=" + enc(solved)
                    f.remove("signatureCipher")
                }
                val nv = nOf(url)
                if (nv != null) {
                    val solved = n[nv] ?: continue // unsolved n = throttled to a crawl / 403: drop the format
                    url = url.replace(Regex("([?&])n=[^&]*"), "$1n=" + Regex.escapeReplacement(enc(solved)))
                }
                f.put("url", url)
                keep.put(f)
            }
            sd.put(k, keep)
        }
        return o.toString()
    }

    /** The JS run by the solver (yt-dlp/ejs, bundled in assets): input for jsc(). */
    fun solverInput(playerJs: String, c: Challenges): String {
        val reqs = JSONArray()
        if (c.sig.isNotEmpty()) reqs.put(JSONObject().put("type", "sig").put("challenges", JSONArray(c.sig)))
        if (c.n.isNotEmpty()) reqs.put(JSONObject().put("type", "n").put("challenges", JSONArray(c.n)))
        return JSONObject().put("type", "player").put("player", playerJs).put("requests", reqs).put("output_preprocessed", false).toString()
    }

    /** Solver output -> (sig map, n map). Throws on an error answer. */
    fun solverOutput(json: String, c: Challenges): Pair<Map<String, String>, Map<String, String>> {
        val o = JSONObject(json)
        if (o.optString("type") != "result") throw YtError(tr("Signature solver failed: ", "İmza çözücü başarısız: ") + o.optString("error").take(200), false)
        val rs = o.getJSONArray("responses")
        val types = ArrayList<String>()
        if (c.sig.isNotEmpty()) types += "sig"
        if (c.n.isNotEmpty()) types += "n"
        val sig = HashMap<String, String>(); val n = HashMap<String, String>()
        for (i in 0 until minOf(rs.length(), types.size)) {
            val r = rs.getJSONObject(i)
            if (r.optString("type") != "result") continue
            val d = r.optJSONObject("data") ?: continue
            val target = if (types[i] == "sig") sig else n
            val k = d.keys(); while (k.hasNext()) { val key = k.next(); target[key] = d.getString(key) }
        }
        return sig to n
    }
}
