package app.plain.setup

import app.plain.lang.tr
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** Plain HTTPS GETs (GitHub raw + release downloads). Called from a worker thread only. */
object Net {
    private const val UA = "PlainSetup"

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
        if (u.protocol != "https") throw IOException(tr("Refusing a non-HTTPS link", "HTTPS olmayan bağlantı reddedildi"))
        repeat(6) { // follow redirects ourselves (release assets bounce to another host)
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000
            c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Cache-Control", "no-cache")
            val code = c.responseCode
            if (code in 300..399) {
                val loc = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                c.disconnect()
                u = URL(u, loc)
                if (u.protocol != "https") throw IOException("Refusing a non-HTTPS redirect")
                return@repeat
            }
            if (code != 200) { c.disconnect(); throw IOException("HTTP $code") }
            return c
        }
        throw IOException("Too many redirects")
    }

    fun text(url: String): String {
        val c = open(url)
        try {
            return c.inputStream.use { inp ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_TEXT) throw IOException(tr("The app list is too big", "Uygulama listesi çok büyük"))
                }
                out.toByteArray().toString(Charsets.UTF_8)
            }
        } finally { c.disconnect() }
    }

    /** apps.json is ~50 KB; anything far bigger is not it (and must not fill the memory). */
    private const val MAX_TEXT = 8 * 1024 * 1024

    /**
     * Downloads [url] to [dest] and returns its SHA-256 (lowercase hex). [progress] gets 0..1
     * (or -1 when the size is unknown). Writes to a .part file first.
     */
    fun download(url: String, dest: File, progress: (Float) -> Unit): String {
        val c = open(url)
        val part = File(dest.path + ".part")
        val md = MessageDigest.getInstance("SHA-256")
        try {
            val total = c.contentLengthLong
            var done = 0L
            var last = 0L
            c.inputStream.use { inp ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException(tr("Cancelled", "İptal edildi"))
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - last > 32 * 1024) { last = done; progress(if (total > 0) done.toFloat() / total else -1f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException(tr("Download cut short", "İndirme yarıda kesildi"))
        } catch (e: Exception) {
            part.delete() // a half download is never used, so don't keep it
            throw e
        } finally { c.disconnect() }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException(tr("Can't save the download", "İndirilen dosya kaydedilemedi"))
        progress(1f)
        return hex(md.digest())
    }

    /** Lowercase hex, the same in every locale (compared with the sha256 in apps.json). */
    fun hex(b: ByteArray): String {
        val digits = "0123456789abcdef"
        val sb = StringBuilder(b.size * 2)
        for (x in b) { val v = x.toInt() and 0xFF; sb.append(digits[v ushr 4]).append(digits[v and 0x0F]) }
        return sb.toString()
    }

    /** A short reason for a failed check or download, in the app's language (not "Unable to resolve host …"). */
    fun describe(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is UnknownHostException || e is ConnectException || e is NoRouteToHostException ->
                tr("No internet connection", "İnternet bağlantısı yok")
            e is SocketTimeoutException -> tr("The connection timed out", "Bağlantı zaman aşımına uğradı")
            e is SSLException -> tr("Secure connection failed (is the phone's date and time right?)",
                "Güvenli bağlantı kurulamadı (telefonun tarihi ve saati doğru mu?)")
            e is org.json.JSONException -> tr("The online app list is damaged", "İnternetteki uygulama listesi bozuk")
            "ENOSPC" in m -> tr("Not enough storage", "Yeterli depolama alanı yok")
            // an APK that should be inside Plain Manager (or a saved copy) isn't there
            e is java.io.FileNotFoundException -> tr("The app's file is missing", "Uygulamanın dosyası bulunamadı")
            m.isNotBlank() -> m
            else -> e.javaClass.simpleName
        }
    }
}
