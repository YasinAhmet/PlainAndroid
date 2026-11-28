package app.plain.maps

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * All network access. Every request is opened on the Wi-Fi Network object itself,
 * so mobile data can never be used, even if Android would route traffic over it.
 */
object Net {
    const val UA = "PlainMaps/1.0 (personal offline map; Android)"

    class Http(val code: Int, val retryAfter: Int) : IOException("HTTP $code")

    /** The connected Wi-Fi network with internet, or null. */
    @Suppress("DEPRECATION")
    fun wifi(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return null
        for (n in cm.allNetworks) {
            val c = cm.getNetworkCapabilities(n) ?: continue
            if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return n
        }
        return null
    }

    private fun open(net: Network, url: String, readTimeoutMs: Int): HttpURLConnection {
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", UA)
        return c
    }

    private fun check(c: HttpURLConnection) {
        val code = c.responseCode
        if (code !in 200..299) {
            val ra = c.getHeaderField("Retry-After")?.trim()?.toIntOrNull() ?: 0
            throw Http(code, ra)
        }
    }

    fun text(net: Network, url: String): String {
        val c = open(net, url, 60_000)
        try {
            check(c)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /** Streams [url] to [dest] (via a .part file). [progress] gets bytes so far and the total (-1 if unknown). */
    fun download(net: Net0, url: String, dest: File, progress: (Long, Long) -> Unit, cancelled: () -> Boolean) {
        val c = open(net.n, url, 120_000)
        val part = File(dest.path + ".part")
        try {
            check(c)
            val total = c.contentLengthLong
            var done = 0L
            var last = 0L
            c.inputStream.use { inp ->
                part.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val k = inp.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        done += k
                        if (done - last > 1 shl 20) {
                            last = done
                            progress(done, total)
                            if (cancelled()) throw InterruptedException()
                        }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException("download cut short ($done of $total bytes)")
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("could not save ${dest.name}")
        } finally {
            c.disconnect()
            part.delete()
        }
    }

    /** POSTs an Overpass query and saves the answer to [dest]. */
    fun overpass(net: Net0, url: String, query: String, dest: File, cancelled: () -> Boolean) {
        val c = open(net.n, url, 300_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val body = ("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            check(c)
            c.inputStream.use { inp ->
                dest.outputStream().buffered(1 shl 16).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var n = 0
                    while (true) {
                        val k = inp.read(buf)
                        if (k < 0) break
                        out.write(buf, 0, k)
                        if (++n % 64 == 0 && cancelled()) throw InterruptedException()
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Calls an İETT SOAP web service method and returns the text of its result element
     * (the services return JSON inside the SOAP envelope).
     */
    fun soap(net: Net0, url: String, action: String, inner: String = ""): String {
        val c = open(net.n, url, 60_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8")
            c.setRequestProperty("SOAPAction", "\"http://tempuri.org/$action\"")
            val body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?><soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">" +
                "<soap:Body><$action xmlns=\"http://tempuri.org/\">$inner</$action></soap:Body></soap:Envelope>").toByteArray()
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            check(c)
            val xml = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val open = "<${action}Result>"
            val a = xml.indexOf(open)
            if (a < 0) return ""
            val b = xml.indexOf("</${action}Result>", a)
            return xmlText(xml.substring(a + open.length, if (b < 0) xml.length else b))
        } finally {
            c.disconnect()
        }
    }

    /** POSTs a form (application/x-www-form-urlencoded) and returns the page. */
    fun form(net: Net0, url: String, body: String): String {
        val c = open(net.n, url, 60_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            val b = body.toByteArray()
            c.setFixedLengthStreamingMode(b.size)
            c.outputStream.use { it.write(b) }
            check(c)
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    fun xmlText(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        .replace(Regex("&#(\\d+);")) { Text.entity(it.groupValues[1], 10) ?: it.value }
        .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { Text.entity(it.groupValues[1], 16) ?: it.value }.replace("&amp;", "&")

    /** Holder so a sync can swap in a new Wi-Fi network after a reconnect. */
    class Net0(@Volatile var n: Network)
}
