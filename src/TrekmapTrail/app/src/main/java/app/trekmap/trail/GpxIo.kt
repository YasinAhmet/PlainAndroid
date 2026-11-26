package app.trekmap.trail

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.BufferedInputStream
import java.io.BufferedWriter
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory

/** Reads GPX (tracks or routes), KML (LineString / gx:Track) and KMZ; writes GPX 1.1. */
object GpxIo {

    fun parse(input: InputStream): Route {
        val bin = BufferedInputStream(input)
        bin.mark(8)
        val b0 = bin.read()
        val b1 = bin.read()
        bin.reset()
        if (b0 == 'P'.code && b1 == 'K'.code) {
            val zip = ZipInputStream(bin)
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory && e.name.lowercase(Locale.ROOT).endsWith(".kml")) return parseXml(NoClose(zip))
            }
            throw IOException("No KML file inside this KMZ")
        }
        return parseXml(bin)
    }

    private class NoClose(i: InputStream) : FilterInputStream(i) {
        override fun close() {}
    }

    private fun parseXml(input: InputStream): Route {
        val f = SAXParserFactory.newInstance()
        f.isNamespaceAware = true
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Exception) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Exception) {}
        val h = Handler()
        f.newSAXParser().parse(InputSource(input), h)
        val segs = (if (h.trk.any { it.size >= 2 }) h.trk else h.rte).filter { it.size >= 2 }
        if (segs.isEmpty()) throw IOException("No track or route found in this file")
        return Route(h.name, segs)
    }

    private class Handler : DefaultHandler() {
        val trk = ArrayList<Seg>()
        val rte = ArrayList<Seg>()
        var name: String? = null

        private val stack = ArrayList<String>()
        private val text = StringBuilder()
        private var lat = DoubleList(); private var lon = DoubleList()
        private var ele = DoubleList(); private var time = LongList()
        private var hasEle = false; private var hasTime = false
        private var pLat = Double.NaN; private var pLon = Double.NaN
        private var pEle = Double.NaN; private var pTime = 0L
        private var target: ArrayList<Seg>? = null

        override fun resolveEntity(publicId: String?, systemId: String?) = InputSource(StringReader(""))

        private fun local(localName: String?, qName: String?): String {
            val n = if (!localName.isNullOrEmpty()) localName else (qName ?: "")
            return n.substringAfter(':')
        }

        private fun parent() = if (stack.size >= 2) stack[stack.size - 2] else ""

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val n = local(localName, qName)
            stack.add(n)
            text.setLength(0)
            when (n) {
                "trkseg" -> { flush(); target = trk }
                "rte" -> { flush(); target = rte }
                "LineString", "Track" -> { flush(); target = trk }
                "trkpt", "rtept" -> {
                    if (target == null) target = if (n == "trkpt") trk else rte
                    pLat = attributes.getValue("lat")?.trim()?.toDoubleOrNull() ?: Double.NaN
                    pLon = attributes.getValue("lon")?.trim()?.toDoubleOrNull() ?: Double.NaN
                    pEle = Double.NaN
                    pTime = 0L
                }
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val n = local(localName, qName)
            val par = parent()
            when (n) {
                "ele" -> if (par == "trkpt" || par == "rtept") pEle = text.trim().toString().toDoubleOrNull() ?: Double.NaN
                "time" -> if (par == "trkpt" || par == "rtept") pTime = parseTime(text.trim().toString())
                "name" -> {
                    val t = text.trim().toString()
                    if (name == null && t.isNotEmpty() && par in NAME_PARENTS) name = t
                }
                "trkpt", "rtept" -> if (!pLat.isNaN() && !pLon.isNaN()) add(pLat, pLon, pEle, pTime)
                "trkseg", "rte", "LineString", "Track" -> flush()
                "coordinates" -> if (par == "LineString") {
                    for (tuple in text.trim().split(WS)) {
                        val p = tuple.split(',')
                        if (p.size < 2) continue
                        val lo = p[0].toDoubleOrNull() ?: continue
                        val la = p[1].toDoubleOrNull() ?: continue
                        add(la, lo, p.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN, 0L)
                    }
                }
                "coord" -> if (par == "Track") {
                    val p = text.trim().split(WS)
                    val lo = p.getOrNull(0)?.toDoubleOrNull()
                    val la = p.getOrNull(1)?.toDoubleOrNull()
                    if (lo != null && la != null) add(la, lo, p.getOrNull(2)?.toDoubleOrNull() ?: Double.NaN, 0L)
                }
            }
            stack.removeAt(stack.size - 1)
            text.setLength(0)
        }

        override fun endDocument() = flush()

        private fun add(la: Double, lo: Double, e: Double, t: Long) {
            lat.add(la); lon.add(lo); ele.add(e); time.add(t)
            if (!e.isNaN()) hasEle = true
            if (t > 0) hasTime = true
        }

        private fun flush() {
            if (lat.size > 0) {
                (target ?: trk).add(
                    Seg(lat.toArray(), lon.toArray(), if (hasEle) ele.toArray() else null, if (hasTime) time.toArray() else null)
                )
            }
            lat = DoubleList(); lon = DoubleList(); ele = DoubleList(); time = LongList()
            hasEle = false; hasTime = false
        }
    }

    private val WS = Regex("\\s+")
    private val NAME_PARENTS = setOf("metadata", "trk", "rte", "Document", "Folder", "Placemark")

    fun parseTime(s: String): Long = try {
        Instant.parse(s).toEpochMilli()
    } catch (e: Exception) {
        try { OffsetDateTime.parse(s).toInstant().toEpochMilli() } catch (e2: Exception) { 0L }
    }

    /** A GPX waypoint; [link] (e.g. "photos/IMG.jpg") lets viewers attach a photo to it. */
    class Wpt(val lat: Double, val lon: Double, val ele: Double, val time: Long, val name: String, val link: String? = null)

    fun write(out: OutputStream, route: Route, name: String, desc: String? = null, wpts: List<Wpt> = emptyList()) {
        val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8), 65536)
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        w.write("<gpx version=\"1.1\" creator=\"Plain Trekking\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        w.write("<metadata><name>${esc(name)}</name>")
        val t0 = route.firstTime()
        if (t0 > 0) w.write("<time>${iso(t0)}</time>")
        w.write("</metadata>\n")
        for (p in wpts) {
            w.write("<wpt lat=\"${f7(p.lat)}\" lon=\"${f7(p.lon)}\">")
            if (!p.ele.isNaN()) w.write("<ele>${f1(p.ele)}</ele>")
            if (p.time > 0) w.write("<time>${iso(p.time)}</time>")
            w.write("<name>${esc(p.name)}</name>")
            if (p.link != null) w.write("<link href=\"${esc(p.link)}\"><text>${esc(p.name)}</text><type>image/jpeg</type></link><sym>Scenic Area</sym>")
            w.write("</wpt>\n")
        }
        w.write("<trk><name>${esc(name)}</name>")
        if (desc != null) w.write("<desc>${esc(desc)}</desc>")
        w.write("\n")
        for (s in route.segs) {
            w.write("<trkseg>\n")
            for (i in 0 until s.size) {
                w.write("<trkpt lat=\""); w.write(f7(s.lat[i])); w.write("\" lon=\""); w.write(f7(s.lon[i])); w.write("\">")
                val e = s.ele?.get(i)
                if (e != null && !e.isNaN()) { w.write("<ele>"); w.write(f1(e)); w.write("</ele>") }
                val t = s.time?.get(i)
                if (t != null && t > 0) { w.write("<time>"); w.write(iso(t)); w.write("</time>") }
                w.write("</trkpt>\n")
            }
            w.write("</trkseg>\n")
        }
        w.write("</trk>\n</gpx>\n")
        w.flush()
    }

    private fun f7(v: Double) = String.format(Locale.US, "%.7f", v)
    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun iso(t: Long) = Instant.ofEpochSecond(t / 1000).toString()
    /** Escapes for XML and drops characters XML 1.0 forbids (control chars, lone surrogates), so files stay readable. */
    private fun esc(s: String): String {
        val b = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> b.append("&amp;")
                c == '<' -> b.append("&lt;")
                c == '>' -> b.append("&gt;")
                c == '"' -> b.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> b.append(c)
                c < ' ' || c == '￾' || c == '￿' -> {}
                Character.isHighSurrogate(c) -> {
                    if (i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) { b.append(c).append(s[i + 1]); i++ }
                }
                Character.isLowSurrogate(c) -> {}
                else -> b.append(c)
            }
            i++
        }
        return b.toString()
    }
}
