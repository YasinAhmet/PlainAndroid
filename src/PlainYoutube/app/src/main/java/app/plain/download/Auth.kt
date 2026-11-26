package app.plain.download

import android.annotation.SuppressLint
import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The account answered "signed out": cookies rotated away or revoked. Text never contains cookie data. */
class AccountExpired : IOException("Account expired — import new cookies (⋮ → Account)")

/**
 * The signed-in account (2.0), used ONLY for: the subscriptions sync, account check, /player of members-only videos
 * and a channel's members-only videos (see [AuthRule]).
 *
 * Storage: the kept cookies (see [CookiePolicy]) + the account name, as JSON encrypted with AES-256-GCM under an
 * Android Keystore key that can't be exported (hardware-backed where the phone has it), in files/account.bin.
 * allowBackup is false and backup/device-transfer rules exclude everything, and the key never leaves the phone
 * anyway, so a copied file is useless. Sign out deletes the file and the key.
 */
object Account {
    private const val ALIAS = "plain_youtube_account"
    private const val FILE = "account.bin"
    private lateinit var app: Context
    private var jar: CookieJar? = null
    private var loaded = false
    @Volatile var name: String? = null
        private set

    const val SIGNED_OUT = "Members-only — import your account in ⋮ → Account"

    fun init(ctx: Context) { app = ctx.applicationContext }
    val ctx: Context get() = app

    private fun file() = File(app.filesDir, FILE)

    /** Cheap (no decryption): an account is stored and not marked expired. */
    val hasAccount: Boolean get() = ::app.isInitialized && file().exists()
    val expired: Boolean get() = hasAccount && Store.prefs.getBoolean("acctExpired", false)
    val usable: Boolean get() = hasAccount && !expired

    /** Decrypted jar, or null. Loaded once per process. */
    @Synchronized fun jar(): CookieJar? {
        if (!loaded) {
            loaded = true
            jar = try {
                if (!file().exists()) null else {
                    val o = JSONObject(String(decrypt(file().readBytes()), Charsets.UTF_8))
                    name = o.optString("name").ifEmpty { null }
                    CookieJar.decode(o.getString("jar"))
                }
            } catch (_: Exception) { null } // key gone (e.g. lock-screen reset): treated as signed out
        }
        return jar
    }

    val signedIn: Boolean get() = usable && jar()?.signedIn == true

    @Synchronized fun save(j: CookieJar, accountName: String?) {
        val o = JSONObject().put("jar", j.encode()).put("name", accountName ?: "")
        val tmp = File(app.filesDir, "$FILE.tmp")
        tmp.writeBytes(encrypt(o.toString().toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file())) { file().delete(); tmp.renameTo(file()) }
        jar = j; name = accountName; loaded = true
        Store.prefs.edit().putBoolean("acctExpired", false).apply()
    }

    /** Persist rotated cookies (Set-Cookie on authenticated answers). */
    @Synchronized fun rotated() { jar?.let { save(it, name) } }

    fun markExpired() { Store.prefs.edit().putBoolean("acctExpired", true).apply() }

    /** Sign out: file + key + memory + any WebView cookies. */
    @Synchronized fun signOut() {
        file().delete()
        File(app.filesDir, "$FILE.tmp").delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) } catch (_: Exception) {}
        jar = null; name = null; loaded = true
        Store.prefs.edit().remove("acctExpired").apply()
        WebWipe.all()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build())
        return g.generateKey()
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain)
        return byteArrayOf(iv.size.toByte()) + iv + ct
    }

    private fun decrypt(data: ByteArray): ByteArray {
        val n = data[0].toInt()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 1, n))
        return c.doFinal(data, 1 + n, data.size - 1 - n)
    }
}

/** Clears the WebView's cookies, storage and cache (after sign-in, on sign-out). */
object WebWipe {
    fun all() {
        Handler(Looper.getMainLooper()).post {
            try {
                android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (_: Throwable) {} // no WebView provider: nothing stored either
        }
    }
}

/**
 * The ONLY code that sends account cookies. Every call states its [Purpose]; [AuthRule.useAccount] must allow it or
 * the call fails before anything is sent. Redirects are not followed (a cookie header never travels to another
 * host). Set-Cookie rotation is written back to the encrypted store. Nothing here logs.
 */
object AuthHttp {
    const val ORIGIN = "https://www.youtube.com"
    const val MENU_URL = "https://www.youtube.com/youtubei/v1/account/account_menu?prettyPrint=false"

    fun post(
        ctx: Context, purpose: Purpose, url: String, body: String, membersOnly: Boolean, headers: Map<String, String>,
        calls: Calls? = null, g: Int = 0,
    ): String {
        val jar = Account.jar()
        if (jar == null || !AuthRule.useAccount(purpose, url, Account.usable && jar.signedIn, membersOnly)) {
            throw YtError(if (Account.expired) "Account expired — import new cookies (⋮ → Account)" else Account.SIGNED_OUT, false)
        }
        var last: IOException? = null
        for (attempt in 0 until 4) {
            calls?.check(g)
            val net = Net.network(ctx, Store.wifiOnly)
            val con = Net.open(net, url)
            con.instanceFollowRedirects = false
            calls?.add(g, con)
            try {
                con.requestMethod = "POST"
                con.doOutput = true
                for ((k, v) in headers) con.setRequestProperty(k, v)
                val now = System.currentTimeMillis() / 1000
                jar.header(url, now)?.let { con.setRequestProperty("Cookie", it) }
                SidAuth.header(jar, ORIGIN, now)?.let {
                    con.setRequestProperty("Authorization", it)
                    con.setRequestProperty("X-Origin", ORIGIN)
                    con.setRequestProperty("X-Goog-AuthUser", "0")
                }
                con.setRequestProperty("Origin", ORIGIN)
                con.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                // cookie rotation, also on error answers
                val sc = con.headerFields.entries.filter { it.key != null && it.key.equals("Set-Cookie", true) }.flatMap { it.value }
                if (sc.isNotEmpty() && jar.apply(sc, java.net.URL(url).host, now)) Account.rotated()
                if (code == 401) { Account.markExpired(); throw AccountExpired() }
                if (code in 300..399) throw YtError("YouTube redirected an account request (HTTP $code)", false)
                if (code in 400..499 && code != 400) throw YtError("YouTube answered HTTP $code", false)
                val bytes = (if (code in 200..299) con.inputStream else con.errorStream)?.use { Net.readAll(it, 12 shl 20) }
                    ?: throw IOException("HTTP $code")
                if (code !in 200..299 && code != 400) throw IOException("HTTP $code")
                val s = String(bytes, Charsets.UTF_8)
                if (AccountParse.loggedOut(s)) { Account.markExpired(); throw AccountExpired() }
                return s
            } catch (e: IOException) {
                calls?.check(g)
                if (e is AccountExpired || e is NoWifi) throw e
                last = e
                Thread.sleep(300L * (attempt + 1))
            } finally {
                calls?.remove(con)
                con.disconnect()
            }
        }
        throw last ?: IOException("Network error")
    }

    private fun webHeaders() = Clients.webHeaders(null)

    /** Unsubscribe [channelId] on YouTube. Only called after the user's confirmation (UnfollowUi). Null = done. */
    fun unsubscribe(ctx: Context, channelId: String): String? =
        Unsub.failure(post(ctx, Purpose.UNSUBSCRIBE, Unsub.URL, Unsub.body(channelId), false, webHeaders()), channelId)

    /** account_menu: display name; also the "is it still valid?" test. */
    fun accountName(ctx: Context): String {
        val s = post(ctx, Purpose.ACCOUNT_CHECK, MENU_URL, Clients.webBody(emptyMap(), null), false, webHeaders())
        return AccountParse.accountName(s) ?: run { Account.markExpired(); throw AccountExpired() }
    }

    /** All subscribed channels (FEchannels + continuations, at most 40 pages). */
    fun subscriptions(ctx: Context, calls: Calls, g: Int, progress: (Int) -> Unit): List<AccountParse.Sub> {
        val out = LinkedHashMap<String, AccountParse.Sub>()
        var s = post(ctx, Purpose.SUBSCRIPTIONS_SYNC, Clients.BROWSE_URL, Clients.webBody(mapOf("browseId" to "FEchannels"), null), false, webHeaders(), calls, g)
        for (page in 0 until 40) {
            val (subs, next) = AccountParse.subscriptions(s)
            for (x in subs) out.putIfAbsent(x.id, x)
            // channel lockups (newer layout), parsed by the normal page parser
            for (i in Browse.page(s).items) if (i.kind == IKind.CHANNEL) out.putIfAbsent(i.id, AccountParse.Sub(i.id, i.title, i.handle))
            progress(out.size)
            if (next == null) break
            s = post(ctx, Purpose.SUBSCRIPTIONS_SYNC, Clients.BROWSE_URL, Clients.webBody(mapOf("continuation" to next), null), false, webHeaders(), calls, g)
        }
        return out.values.toList()
    }

    /** A channel's members-only videos (the UUMO… playlist), page 1 or a continuation. */
    fun membersPage(ctx: Context, calls: Calls, g: Int, channelId: String, token: String?): JSONObject {
        val fields = if (token != null) mapOf("continuation" to token)
            else mapOf("browseId" to "VL" + (Members.playlistOf(channelId) ?: throw YtError("Not a channel", false)))
        return JSONObject(post(ctx, Purpose.MEMBERS_TAB, Clients.BROWSE_URL, Clients.webBody(fields, null), true, webHeaders(), calls, g))
    }
}

/**
 * Signed-in /player for members-only videos (TVHTML5): iframe_api -> tv-player-ias.js (public, anonymous) ->
 * signatureTimestamp -> authenticated /player -> "n"/"s" solved by yt-dlp's ejs solver in an off-screen WebView ->
 * plain stream URLs (googlevideo gets no cookies: the URL is its own authorization).
 */
object AuthPlayer {
    @Volatile private var visitor: String? = null
    @Volatile private var playerId: String? = null
    @Volatile private var playerIdAt = 0L

    fun info(ctx: Context, videoId: String, net: Network?): VideoInfo {
        val js = playerJs(ctx, net)
        val sts = TvPlayer.sts(js) ?: throw YtError("Couldn't read YouTube's player version (signatureTimestamp)", false)
        val c = TvPlayer.CLIENT
        var answer = ""
        for (attempt in 0 until 2) {
            val h = linkedMapOf(
                "Content-Type" to "application/json", "User-Agent" to c.userAgent,
                "X-YouTube-Client-Name" to c.clientId.toString(), "X-YouTube-Client-Version" to c.version,
            )
            visitor?.let { h["X-Goog-Visitor-Id"] = it }
            answer = AuthHttp.post(ctx, Purpose.MEMBERS_PLAYER, Clients.PLAYER_URL, TvPlayer.body(videoId, sts, visitor), true, h)
            val first = Yt.parsePlayer(answer, c.key, videoId)
            val v = (first as? PlayerResult.Fail)?.visitorData ?: (first as? PlayerResult.Ok)?.visitorData
            if (first is PlayerResult.Fail && first.error.tryOther && v != null && v != visitor) { visitor = v; continue }
            if (visitor == null) visitor = v
            break
        }
        // status first (members-only for this account, private, …), before any solving
        val probe = JSONObject(answer).optJSONObject("playabilityStatus")
        if (probe?.optString("status") != "OK") {
            val r = Yt.parsePlayer(answer, c.key, videoId) as PlayerResult.Fail
            if (r.error.members) throw YtError("Your account can't play this members-only video (not a member of the channel?)", false, members = true)
            throw r.error
        }
        val ch = TvPlayer.challenges(answer)
        val rewritten = if (ch.empty) answer else {
            val out = JsSolver.solve(ctx, TvPlayer.solverInput(js, ch))
            val (sig, n) = TvPlayer.solverOutput(out, ch)
            TvPlayer.rewrite(answer, sig, n)
        }
        return when (val r = Yt.parsePlayer(rewritten, c.key, videoId)) {
            is PlayerResult.Ok -> {
                // the account could play members-only content of this channel: a member (used for the unfollow warning)
                r.info.channelId?.let { Store.markMemberChannel(it) }
                r.info.copy(members = true)
            }
            is PlayerResult.Fail -> throw r.error
        }
    }

    /** tv-player-ias.js, cached by player id in the app cache (one file; ~2.5 MB, re-fetched only when YouTube updates it). */
    private fun playerJs(ctx: Context, net: Network?): String {
        val now = System.currentTimeMillis()
        var id = playerId
        if (id == null || now - playerIdAt > 6 * 3600_000L) {
            id = TvPlayer.playerId(String(anonGet(net, TvPlayer.IFRAME_API, 1 shl 20), Charsets.UTF_8))
                ?: throw YtError("Couldn't find YouTube's player version", false)
            playerId = id; playerIdAt = now
        }
        val dir = File(ctx.cacheDir, "tvplayer").apply { mkdirs() }
        val f = File(dir, "$id.js")
        if (f.exists() && f.length() > 10_000) return f.readText()
        val text = String(anonGet(net, TvPlayer.playerUrl(id), 12 shl 20), Charsets.UTF_8)
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "$id.tmp").apply { writeText(text); renameTo(f) }
        return text
    }

    /** Public files: no cookies, no account headers. */
    private fun anonGet(net: Network?, url: String, limit: Int): ByteArray {
        var last: IOException? = null
        repeat(4) { attempt ->
            val c = Net.open(net, url)
            try {
                c.setRequestProperty("User-Agent", TvPlayer.UA)
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { Net.readAll(it, limit) }
            } catch (e: IOException) {
                last = e; Thread.sleep(300L * (attempt + 1))
            } finally { c.disconnect() }
        }
        throw last!!
    }
}

/**
 * Runs yt-dlp's ejs solver (assets/ejs, Unlicense + meriyah ISC + astring MIT) in an off-screen WebView: no network
 * (blockNetworkLoads), no file/content access, no page, destroyed right after. Only used for members-only videos.
 */
object JsSolver {
    private val main = Handler(Looper.getMainLooper())

    private fun asset(ctx: Context, name: String) = ctx.assets.open("ejs/$name").use { String(it.readBytes(), Charsets.UTF_8) }

    @SuppressLint("SetJavaScriptEnabled")
    fun solve(ctx: Context, input: String, timeoutSec: Long = 90): String {
        val app = ctx.applicationContext
        val script = asset(app, "yt.solver.lib.min.js") + "\n;Object.assign(globalThis, lib);\n" + asset(app, "yt.solver.core.min.js") +
            "\n;JSON.stringify(jsc(" + input + "));"
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        var wv: WebView? = null
        main.post {
            try {
                val w = WebView(app)
                wv = w
                w.settings.javaScriptEnabled = true
                w.settings.blockNetworkLoads = true
                w.settings.allowFileAccess = false
                w.settings.allowContentAccess = false
                w.webViewClient = object : WebViewClient() {
                    private var ran = false
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (ran) return
                        ran = true
                        view.evaluateJavascript(script) { r -> result = r; latch.countDown() }
                    }
                }
                w.loadDataWithBaseURL("about:blank", "<html><body></body></html>", "text/html", "utf-8", null)
            } catch (t: Throwable) {
                error = t.javaClass.simpleName; latch.countDown()
            }
        }
        val ok = latch.await(timeoutSec, TimeUnit.SECONDS)
        main.post { wv?.let { it.stopLoading(); it.destroy() } }
        if (!ok) throw YtError("Signature solver timed out", false)
        error?.let { throw YtError("Signature solver couldn't start ($it)", false) }
        val r = result
        if (r == null || r == "null") throw YtError("Signature solver failed", false)
        // evaluateJavascript returns the value JSON-encoded: a JS string -> "\"{...}\""
        return JSONArray("[$r]").getString(0)
    }
}
