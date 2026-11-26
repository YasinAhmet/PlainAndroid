package app.plain.setup

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Plain HTTPS GETs (GitHub raw + release downloads). Called from a worker thread only. */
object Net {
    private const val UA = "PlainSetup"

    private fun open(url: String): HttpURLConnection {
        var u = URL(url)
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
        try { return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } } finally { c.disconnect() }
    }

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
                        if (Thread.currentThread().isInterrupted) throw IOException(app.plain.lang.tr("Cancelled", "İptal edildi"))
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (done - last > 32 * 1024) { last = done; progress(if (total > 0) done.toFloat() / total else -1f) }
                    }
                }
            }
            if (total > 0 && done != total) throw IOException(app.plain.lang.tr("Download cut short", "İndirme yarıda kesildi"))
        } finally { c.disconnect() }
        if (dest.exists()) dest.delete()
        if (!part.renameTo(dest)) throw IOException(app.plain.lang.tr("Can't save the download", "İndirilen dosya kaydedilemedi"))
        progress(1f)
        return hex(md.digest())
    }

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
