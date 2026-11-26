package app.plain.maps

import app.plain.lang.tr
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Downloads EGO's timetable pages (Wi-Fi only, three at a time to go easy on ego.gov.tr). */
object EgoFetch {
    fun lineCodes(net: Net.Net0): List<String> {
        val codes = Ego.lineCodes(Net.text(net.n, Ego.PAGE)).map { it.first }
        if (codes.isEmpty()) throw IOException(tr("EGO's timetable page lists no lines", "EGO saat sayfasında hat yok"))
        return codes
    }

    fun line(net: Net.Net0, code: String): Ego.Line? {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return Ego.parseLine(code, Net.form(net, Ego.POST, "hat_no1=" + URLEncoder.encode(code, "UTF-8")))
            } catch (e: IOException) {
                last = e
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("EGO line $code")
    }

    /** Every line; [progress] gets the number done. Fails if most lines could not be read. */
    fun lines(net: Net.Net0, codes: List<String>, cancel: AtomicBoolean, progress: (Int) -> Unit): List<Ego.Line> {
        val pool = Executors.newFixedThreadPool(3)
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        try {
            val fs = codes.map { c ->
                pool.submit<Ego.Line?> {
                    if (cancel.get()) return@submit null
                    val l = try { line(net, c) } catch (_: Exception) { failed.incrementAndGet(); null }
                    progress(done.incrementAndGet())
                    l
                }
            }
            val out = fs.mapNotNull { it.get() }
            if (cancel.get()) throw InterruptedException()
            if (failed.get() > codes.size / 2) throw IOException(tr("ego.gov.tr did not answer for most lines (${failed.get()} of ${codes.size}). Try again later.",
                "ego.gov.tr hatların çoğu için yanıt vermedi (${failed.get()} / ${codes.size}). Daha sonra yeniden deneyin."))
            return out
        } finally {
            pool.shutdownNow()
        }
    }
}
