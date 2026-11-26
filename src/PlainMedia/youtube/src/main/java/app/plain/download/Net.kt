package app.plain.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import app.plain.lang.tr

class NoWifi : IOException(tr("Not on Wi-Fi (Wi-Fi only is on)", "Wi-Fi bağlı değil (Yalnızca Wi-Fi açık)"))
class Expired : IOException(tr("Stream link expired or refused (HTTP 403)", "Akış bağlantısının süresi doldu ya da reddedildi (HTTP 403)"))
class Cancelled : IOException("Cancelled")

object Net {
    /**
     * The network to use, or null for the system default. With Wi-Fi only, sockets are bound to the Wi-Fi network,
     * so if Wi-Fi drops they fail instead of silently continuing over mobile data.
     */
    fun network(ctx: Context, wifiOnly: Boolean): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (!wifiOnly) {
            if (active == null || caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) != true) throw IOException(tr("No network", "Ağ yok"))
            return null
        }
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return active // also a VPN running over Wi-Fi
        @Suppress("DEPRECATION")
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) return n
        }
        throw NoWifi()
    }

    fun open(net: Network?, url: String): HttpURLConnection {
        val u = URL(url)
        val c = (net?.openConnection(u) ?: u.openConnection()) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        return c
    }

    fun readAll(s: InputStream, limit: Int = 8 shl 20): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 shl 10)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > limit) throw IOException(tr("Answer too large", "Yanıt çok büyük"))
        }
        return out.toByteArray()
    }

    /** Small GET (thumbnail). */
    fun get(net: Network?, url: String): ByteArray {
        val c = open(net, url)
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { readAll(it, 2 shl 20) }
        } finally {
            c.disconnect()
        }
    }
}

/** InnerTube /player client with fallback over [Clients.ORDER]. */
object InnerTube {
    // YouTube's pseudonymous visitor id from an earlier answer; kept in memory only (never stored, no account).
    @Volatile private var visitor: String? = null

    /**
     * Player info with client fallback. A video blocked in this country (legal complaint) is asked again with the
     * "region for blocked videos" (⋮, default US); such videos are remembered and asked that way directly next time.
     */
    fun info(videoId: String, net: Network?, skip: Set<String> = emptySet(), order: List<Client> = Clients.ORDER): VideoInfo {
        // members-only (2.0): known ones go straight to the signed-in path; everything else is asked anonymously
        if (Store.isMembers(videoId)) return members(videoId, net)
        return try { anon(videoId, net, skip, order) } catch (e: YtError) {
            if (!e.members) throw e
            Store.markMembers(videoId)
            members(videoId, net)
        }
    }

    /** The only place a /player request may use the account: a video YouTube itself called members-only. */
    private fun members(videoId: String, net: Network?): VideoInfo {
        if (!Account.usable) throw YtError(if (Account.expired) tr("Members-only — account expired: import new cookies in ⋮ → Account", "Üyelere özel — hesabın süresi doldu: ⋮ → Hesap'tan yeni çerezleri içe aktarın") else Account.SIGNED_OUT, false, members = true)
        return AuthPlayer.info(Account.ctx, videoId, net)
    }

    private fun anon(videoId: String, net: Network?, skip: Set<String>, order: List<Client>): VideoInfo {
        val alt = Store.altRegion
        val known = alt.isNotEmpty() && Store.isBlocked(videoId)
        try {
            return infoIn(videoId, net, skip, order, if (known) alt else null)
        } catch (e: YtError) {
            if (!e.geo) throw e
            if (alt.isEmpty()) throw YtError(tr("Blocked in your country (legal complaint). Turn on ⋮ → Region for blocked videos.", "Ülkenizde engelli (yasal şikâyet). ⋮ → Engelli videolar için bölge'yi açın."), false, true)
            if (known) throw YtError(tr("Blocked in your country and in region $alt too. Try another region in ⋮.", "Ülkenizde ve $alt bölgesinde de engelli. ⋮ menüsünden başka bir bölge deneyin."), false, true)
        }
        val i = try { infoIn(videoId, net, skip, order, alt) } catch (e: YtError) {
            if (e.geo) throw YtError(tr("Blocked in your country and in region $alt too. Try another region in ⋮.", "Ülkenizde ve $alt bölgesinde de engelli. ⋮ menüsünden başka bir bölge deneyin."), false, true)
            throw e
        }
        Store.markBlocked(videoId)
        return i
    }

    private fun infoIn(videoId: String, net: Network?, skip: Set<String>, order: List<Client>, gl: String?): VideoInfo {
        var best: YtError? = null
        for (c in order) {
            if (c.key in skip) continue
            var used = visitor
            var r = try { call(c, videoId, used, net, gl) } catch (e: IOException) {
                if (e is NoWifi) throw e
                best = best ?: YtError(tr("Network error: ${e.message}", "Ağ hatası: ${e.message}"), true, weak = true); continue
            }
            if (r is PlayerResult.Fail && r.error.tryOther && r.visitorData != null && r.visitorData != used) {
                // "confirm you're not a bot" without visitorData: retry once with the id YouTube just handed out
                used = r.visitorData
                visitor = used
                r = try { call(c, videoId, used, net, gl) } catch (e: IOException) { r }
            }
            when (r) {
                is PlayerResult.Ok -> {
                    if (visitor == null) visitor = r.visitorData
                    return r.info
                }
                is PlayerResult.Fail -> {
                    if (!r.error.tryOther) throw r.error
                    val b = best
                    if (b == null || b.weak || b.message!!.contains("bot") || b.message!!.startsWith("Network")) best = r.error
                }
            }
        }
        throw best ?: YtError(tr("No InnerTube client worked", "Hiçbir InnerTube istemcisi çalışmadı"), true)
    }

    private fun call(c: Client, videoId: String, visitorData: String?, net: Network?, gl: String?): PlayerResult {
        var last: IOException? = null
        repeat(3) { attempt ->
            val con = Net.open(net, Clients.PLAYER_URL)
            try {
                con.requestMethod = "POST"
                con.doOutput = true
                for ((k, v) in Clients.playerHeaders(c, visitorData)) con.setRequestProperty(k, v)
                con.outputStream.use { it.write(Clients.playerBody(c, videoId, visitorData, gl).toByteArray(Charsets.UTF_8)) }
                val code = con.responseCode
                val body = (if (code in 200..299) con.inputStream else con.errorStream)?.use { Net.readAll(it) }
                    ?: throw IOException("HTTP $code")
                if (code !in 200..299 && code != 400) throw IOException("HTTP $code")
                return Yt.parsePlayer(String(body, Charsets.UTF_8), c.key, videoId)
            } catch (e: IOException) {
                last = e
                Thread.sleep(400L * (attempt + 1))
            } finally {
                con.disconnect()
            }
        }
        throw last!!
    }
}

/**
 * Chunked, resumable stream download. 10 MB Range requests (like yt-dlp's http_chunk_size), because googlevideo
 * throttles long single requests for some clients. Connections are kept alive between chunks; failed handshakes and
 * dropped reads are retried quickly (some ISP cache nodes reset a share of TLS handshakes).
 */
class Fetcher(
    private val ctx: Context,
    private val wifiOnly: Boolean,
    private val userAgent: String,
    private val cancelled: () -> Boolean,
) {
    companion object {
        const val CHUNK = 10L shl 20
        const val MAX_TRIES = 60
    }

    /** Appends to [dest] until complete. [onBytes] gets the file length now. Returns the total size. */
    fun fetch(url: String, dest: File, knownTotal: Long, onBytes: (Long, Long) -> Unit): Long {
        var total = knownTotal
        var pos = dest.length()
        if (total > 0 && pos > total) { dest.delete(); pos = 0 }
        var tries = 0
        val buf = ByteArray(64 shl 10)
        FileOutputStream(dest, true).use { out ->
            while (total < 0 || pos < total) {
                if (cancelled()) throw Cancelled()
                val net = Net.network(ctx, wifiOnly)
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                var con: HttpURLConnection? = null
                try {
                    con = Net.open(net, url)
                    con.setRequestProperty("User-Agent", userAgent)
                    con.setRequestProperty("Range", "bytes=$pos-$end")
                    con.setRequestProperty("Accept-Encoding", "identity")
                    val code = con.responseCode
                    when {
                        code == 206 || code == 200 && pos == 0L -> {
                            val t = Text.totalFromContentRange(con.getHeaderField("Content-Range"))
                            if (t > 0) total = t else if (code == 200) total = con.contentLengthLong
                            con.inputStream.use { inp ->
                                while (true) {
                                    if (cancelled()) throw Cancelled()
                                    val n = inp.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    pos += n
                                    onBytes(pos, total)
                                }
                            }
                            tries = 0
                            if (total < 0 && end - pos >= 0) total = pos // short last chunk with unknown size
                        }
                        code == 416 && pos > 0 -> { total = pos }
                        code == 403 || code == 410 -> throw Expired()
                        else -> throw IOException("HTTP $code")
                    }
                } catch (e: IOException) {
                    if (e is Cancelled || e is Expired || e is NoWifi) throw e
                    con?.disconnect()
                    if (++tries > MAX_TRIES) throw IOException(tr("Download failed: ${e.message}", "İndirme başarısız: ${e.message}"), e)
                    Thread.sleep(minOf(200L * tries, 2000L))
                }
            }
        }
        return total
    }
}
