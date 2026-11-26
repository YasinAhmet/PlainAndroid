package app.plain.download

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** What gets embedded: title, artist (= the channel folder), album when known, cover art (JPEG) when wanted. */
class TagSet(val title: String, val artist: String, val album: String?, val jpeg: ByteArray?)

private fun be32(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
private fun be64(b: ByteArray, o: Int) = (be32(b, o).toLong() shl 32) or (be32(b, o + 4).toLong() and 0xFFFFFFFFL)
private fun ByteArrayOutputStream.u32(v: Int) { write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v) }
private fun ByteArrayOutputStream.bytes(b: ByteArray) = write(b, 0, b.size)

private fun readAt(ch: FileChannel, pos: Long, n: Int): ByteArray {
    val buf = ByteBuffer.allocate(n)
    var p = pos
    while (buf.hasRemaining()) { val r = ch.read(buf, p); if (r < 0) throw IOException("Unexpected end of file"); p += r }
    return buf.array()
}

private fun copyRange(ch: FileChannel, from: Long, to: Long, out: OutputStream) {
    val buf = ByteBuffer.allocate(1 shl 20)
    var p = from
    while (p < to) {
        buf.clear(); buf.limit(minOf(buf.capacity().toLong(), to - p).toInt())
        val r = ch.read(buf, p)
        if (r <= 0) throw IOException("Unexpected end of file")
        out.write(buf.array(), 0, r); p += r
    }
}

/**
 * MP4 / M4A metadata (iTunes-style `moov/udta/meta/ilst`): ©nam, ©ART, aART, ©alb and `covr` (JPEG).
 *
 * Only `moov` is rewritten; everything else is copied as-is. YouTube's DASH M4A (moov first, then sidx + moof/mdat
 * fragments) has no absolute offsets after moov: `stco` is empty, `tfhd` uses default-base-is-moof without
 * base_data_offset, and `sidx` offsets are relative (verified on itag 140 files, 2026-11-26), so growing moov is safe.
 * A regular MP4 whose moov comes before its media data (MediaMuxer may reserve room for moov at the start) gets every
 * `stco`/`co64` chunk offset shifted by the growth; with moov at the end nothing moves.
 */
object Mp4Tags {
    class Box(val type: String, val offset: Long, val size: Long)

    private val OURS = setOf("©nam", "©ART", "aART", "©alb")

    fun topBoxes(ch: FileChannel): List<Box> {
        val out = ArrayList<Box>()
        var p = 0L
        val end = ch.size()
        while (p + 8 <= end) {
            val h = readAt(ch, p, 8)
            var size = be32(h, 0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            if (size == 1L) size = be64(readAt(ch, p + 8, 8), 0)
            if (size == 0L) size = end - p
            if (size < 8 || p + size > end) break
            out += Box(type, p, size); p += size
        }
        return out
    }

    private class Child(val type: String, val bytes: ByteArray) // whole box incl. header

    private fun children(b: ByteArray, from: Int, to: Int): List<Child> {
        val out = ArrayList<Child>()
        var p = from
        while (p + 8 <= to) {
            val size = be32(b, p)
            if (size < 8 || p + size > to) break
            out += Child(String(b, p + 4, 4, Charsets.ISO_8859_1), b.copyOfRange(p, p + size)); p += size
        }
        return out
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(payload.size + 8)
        o.u32(payload.size + 8); o.bytes(type.toByteArray(Charsets.ISO_8859_1)); o.bytes(payload)
        return o.toByteArray()
    }

    private fun boxOf(type: String, parts: List<ByteArray>, prefix: ByteArray = ByteArray(0)): ByteArray {
        val o = ByteArrayOutputStream(); o.bytes(prefix); parts.forEach { o.bytes(it) }
        return box(type, o.toByteArray())
    }

    /** An ilst item: `type` → `data` (type 1 = UTF-8 text, 13 = JPEG). */
    private fun item(type: String, dataType: Int, value: ByteArray): ByteArray {
        val d = ByteArrayOutputStream(); d.u32(dataType); d.u32(0); d.bytes(value)
        return box(type, box("data", d.toByteArray()))
    }

    /** Does this moov carry cover art (and the title / artist tags)? */
    fun tagsOf(moov: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        val udta = children(moov, 8, moov.size).firstOrNull { it.type == "udta" } ?: return out
        val meta = children(udta.bytes, 8, udta.bytes.size).firstOrNull { it.type == "meta" } ?: return out
        val ilst = children(meta.bytes, 12, meta.bytes.size).firstOrNull { it.type == "ilst" } ?: return out
        for (it in children(ilst.bytes, 8, ilst.bytes.size)) {
            val data = children(it.bytes, 8, it.bytes.size).firstOrNull { d -> d.type == "data" } ?: continue
            out[it.type] = data.bytes.copyOfRange(16, data.bytes.size)
        }
        return out
    }

    fun readMoov(ch: FileChannel): Pair<Box, ByteArray>? {
        val moov = topBoxes(ch).firstOrNull { it.type == "moov" } ?: return null
        if (moov.size > (64 shl 20)) return null
        return moov to readAt(ch, moov.offset, moov.size.toInt())
    }

    fun hasCover(ch: FileChannel): Boolean = readMoov(ch)?.let { "covr" in tagsOf(it.second) } == true

    /**
     * The new moov: [tags] merged into udta/meta/ilst (other items such as YouTube's ©too stay; covr is replaced only
     * when [TagSet.jpeg] is set); chunk offsets shifted when [moovBeforeData].
     */
    fun newMoov(moov: ByteArray, tags: TagSet, moovBeforeData: Boolean): ByteArray {
        val kids = children(moov, 8, moov.size)
        val udta = kids.firstOrNull { it.type == "udta" }
        val uKids = udta?.let { children(it.bytes, 8, it.bytes.size) }.orEmpty()
        val meta = uKids.firstOrNull { it.type == "meta" }
        val mKids = meta?.let { children(it.bytes, 12, it.bytes.size) }.orEmpty()
        val hdlr = mKids.firstOrNull { it.type == "hdlr" }?.bytes ?: box("hdlr", ByteArray(4 + 4) + "mdir".toByteArray() + "appl".toByteArray() + ByteArray(4 + 4 + 1))
        val oldItems = mKids.firstOrNull { it.type == "ilst" }?.let { children(it.bytes, 8, it.bytes.size) }.orEmpty()
        val drop = if (tags.jpeg != null) OURS + "covr" else OURS
        val items = ArrayList<ByteArray>()
        oldItems.filter { it.type !in drop }.forEach { items += it.bytes }
        fun text(t: String, v: String?) { if (!v.isNullOrBlank()) items += item(t, 1, v.toByteArray(Charsets.UTF_8)) }
        text("©nam", tags.title); text("©ART", tags.artist); text("aART", tags.artist); text("©alb", tags.album)
        tags.jpeg?.let { items += item("covr", 13, it) }
        val ilst = boxOf("ilst", items)
        val newMeta = boxOf("meta", listOf(hdlr, ilst) + mKids.filter { it.type != "hdlr" && it.type != "ilst" }.map { it.bytes }, prefix = ByteArray(4))
        val newUdta = boxOf("udta", uKids.filter { it.type != "meta" }.map { it.bytes } + newMeta)
        val parts = kids.filter { it.type != "udta" }.map { it.bytes } + newUdta
        val out = boxOf("moov", parts)
        val delta = out.size - moov.size
        if (moovBeforeData && delta != 0) shiftChunkOffsets(out, 8, out.size, delta.toLong())
        return out
    }

    /** Add [delta] to every stco / co64 entry below moov (trak/mdia/minf/stbl). */
    private fun shiftChunkOffsets(b: ByteArray, from: Int, to: Int, delta: Long) {
        var p = from
        while (p + 8 <= to) {
            val size = be32(b, p)
            if (size < 8 || p + size > to) return
            when (String(b, p + 4, 4, Charsets.ISO_8859_1)) {
                "trak", "mdia", "minf", "stbl" -> shiftChunkOffsets(b, p + 8, p + size, delta)
                "stco" -> {
                    val n = be32(b, p + 12)
                    for (i in 0 until n) {
                        val o = p + 16 + 4 * i
                        val v = (be32(b, o).toLong() and 0xFFFFFFFFL) + delta
                        if (v > 0xFFFFFFFFL) throw IOException("Chunk offset overflow")
                        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte(); b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
                    }
                }
                "co64" -> {
                    val n = be32(b, p + 12)
                    for (i in 0 until n) {
                        val o = p + 16 + 8 * i
                        val v = be64(b, o) + delta
                        for (k in 0 until 8) b[o + k] = (v ushr (56 - 8 * k)).toByte()
                    }
                }
            }
            p += size
        }
    }

    /** Copy [ch] to [out] with the tagged moov (one streaming pass; 1 MB blocks). */
    fun write(ch: FileChannel, out: OutputStream, tags: TagSet) {
        val top = topBoxes(ch)
        val moov = top.firstOrNull { it.type == "moov" } ?: throw IOException("Not an MP4 file (no moov)")
        val firstData = top.filter { it.type == "mdat" || it.type == "moof" }.minOfOrNull { it.offset } ?: Long.MAX_VALUE
        val m = newMoov(readAt(ch, moov.offset, moov.size.toInt()), tags, moov.offset < firstData)
        copyRange(ch, 0, moov.offset, out)
        out.write(m)
        copyRange(ch, moov.offset + moov.size, ch.size(), out)
    }
}

/**
 * Ogg Opus metadata: the OpusTags packet gets TITLE / ARTIST / ALBUMARTIST / ALBUM and a METADATA_BLOCK_PICTURE
 * (base64 FLAC picture block, front cover). The comment packet is re-paged (it may span pages), the following pages
 * are renumbered and every rewritten page gets a new CRC; audio packets are untouched.
 */
object OggTags {
    private val CRC = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var r = i shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
            t[i] = r
        }
    }

    fun crc(page: ByteArray): Int {
        var c = 0
        for (b in page) c = (c shl 8) xor CRC[((c ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return c
    }

    class Page(val offset: Long, val headerType: Int, val granule: Long, val serial: Int, val seq: Int, val lacing: IntArray, val dataOffset: Long) {
        val dataSize get() = lacing.sum()
        val end get() = dataOffset + dataSize
    }

    private fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun page(ch: FileChannel, pos: Long): Page? {
        if (pos + 27 > ch.size()) return null
        val h = readAt(ch, pos, 27)
        if (String(h, 0, 4, Charsets.ISO_8859_1) != "OggS") return null
        val n = h[26].toInt() and 0xFF
        val lac = readAt(ch, pos + 27, n).map { it.toInt() and 0xFF }.toIntArray()
        val gran = (le32(h, 6).toLong() and 0xFFFFFFFFL) or (le32(h, 10).toLong() shl 32)
        return Page(pos, h[5].toInt() and 0xFF, gran, le32(h, 14), le32(h, 18), lac, pos + 27 + n)
    }

    /** The OpusTags packet: its bytes and the pages it occupies (it must end its last page). */
    private fun commentPacket(ch: FileChannel): Pair<ByteArray, List<Page>>? {
        val first = page(ch, 0) ?: return null
        var p = page(ch, first.end) ?: return null
        val pages = ArrayList<Page>()
        val data = ByteArrayOutputStream()
        while (true) {
            pages += p
            var off = p.dataOffset
            for ((i, l) in p.lacing.withIndex()) {
                data.write(readAt(ch, off, l)); off += l
                if (l < 255) {
                    if (i != p.lacing.size - 1) return null // audio data on the same page: leave the file alone
                    val pkt = data.toByteArray()
                    return if (pkt.size >= 8 && String(pkt, 0, 8, Charsets.ISO_8859_1) == "OpusTags") pkt to pages else null
                }
            }
            p = page(ch, p.end) ?: return null
        }
    }

    /** Comments of an OpusTags packet: vendor + "KEY=value" list. */
    fun parse(pkt: ByteArray): Pair<String, List<String>> {
        var o = 8
        val vl = le32(pkt, o); o += 4
        val vendor = String(pkt, o, vl, Charsets.UTF_8); o += vl
        val n = le32(pkt, o); o += 4
        val out = ArrayList<String>()
        repeat(n) { val l = le32(pkt, o); o += 4; out += String(pkt, o, l, Charsets.UTF_8); o += l }
        return vendor to out
    }

    fun hasPicture(ch: FileChannel): Boolean =
        commentPacket(ch)?.let { (pkt, _) -> parse(pkt).second.any { it.startsWith("METADATA_BLOCK_PICTURE=", true) } } == true

    private fun ByteArrayOutputStream.le(v: Int) { write(v); write(v ushr 8); write(v ushr 16); write(v ushr 24) }

    /** FLAC picture block (front cover) for METADATA_BLOCK_PICTURE. */
    fun pictureBlock(jpeg: ByteArray): ByteArray {
        val (w, h) = Jpeg.size(jpeg) ?: (0 to 0)
        val o = ByteArrayOutputStream()
        o.u32(3); val mime = "image/jpeg".toByteArray(); o.u32(mime.size); o.bytes(mime)
        o.u32(0); o.u32(w); o.u32(h); o.u32(24); o.u32(0); o.u32(jpeg.size); o.bytes(jpeg)
        return o.toByteArray()
    }

    fun newPacket(old: ByteArray, tags: TagSet): ByteArray {
        val (vendor, comments) = parse(old)
        val drop = setOf("TITLE", "ARTIST", "ALBUMARTIST", "ALBUM") + if (tags.jpeg != null) setOf("METADATA_BLOCK_PICTURE") else emptySet()
        val keep = comments.filter { it.substringBefore('=').uppercase() !in drop }
        val add = ArrayList<String>()
        if (tags.title.isNotBlank()) add += "TITLE=${tags.title}"
        if (tags.artist.isNotBlank()) { add += "ARTIST=${tags.artist}"; add += "ALBUMARTIST=${tags.artist}" }
        tags.album?.takeIf { it.isNotBlank() }?.let { add += "ALBUM=$it" }
        tags.jpeg?.let { add += "METADATA_BLOCK_PICTURE=" + java.util.Base64.getEncoder().encodeToString(pictureBlock(it)) }
        val o = ByteArrayOutputStream()
        o.bytes("OpusTags".toByteArray())
        val v = vendor.toByteArray(Charsets.UTF_8); o.le(v.size); o.bytes(v)
        val all = keep + add
        o.le(all.size)
        for (c in all) { val b = c.toByteArray(Charsets.UTF_8); o.le(b.size); o.bytes(b) }
        return o.toByteArray()
    }

    private fun pageBytes(headerType: Int, granule: Long, serial: Int, seq: Int, lacing: List<Int>, data: ByteArray, dataFrom: Int, dataLen: Int): ByteArray {
        val o = ByteArrayOutputStream(27 + lacing.size + dataLen)
        o.bytes("OggS".toByteArray()); o.write(0); o.write(headerType)
        for (k in 0 until 8) o.write((granule ushr (8 * k)).toInt())
        o.le(serial); o.le(seq); o.le(0); o.write(lacing.size)
        lacing.forEach { o.write(it) }
        o.write(data, dataFrom, dataLen)
        val b = o.toByteArray()
        val c = crc(b)
        b[22] = c.toByte(); b[23] = (c ushr 8).toByte(); b[24] = (c ushr 16).toByte(); b[25] = (c ushr 24).toByte()
        return b
    }

    /** Copy [ch] to [out] with a new OpusTags packet (re-paged), following pages renumbered with fresh CRCs. */
    fun write(ch: FileChannel, out: OutputStream, tags: TagSet) {
        val head = page(ch, 0) ?: throw IOException("Not an Ogg file")
        val (old, oldPages) = commentPacket(ch) ?: throw IOException("Unsupported Ogg layout (no separate OpusTags page)")
        val pkt = newPacket(old, tags)
        val lacing = ArrayList<Int>()
        var left = pkt.size
        while (left >= 255) { lacing += 255; left -= 255 }
        lacing += left // final value < 255 ends the packet (0 when the size is a multiple of 255)
        copyRange(ch, 0, head.end, out)
        val serial = oldPages[0].serial
        var seq = oldPages[0].seq
        var dataPos = 0
        val chunks = lacing.chunked(255)
        for ((i, c) in chunks.withIndex()) {
            val len = c.sum()
            val last = i == chunks.size - 1
            out.write(pageBytes(if (i == 0) 0 else 1, if (last) 0L else -1L, serial, seq++, c, pkt, dataPos, len))
            dataPos += len
        }
        val shift = seq - (oldPages.last().seq + 1)
        var p = page(ch, oldPages.last().end)
        while (p != null) {
            if (shift == 0 || p.serial != serial) { copyRange(ch, p.offset, p.end, out) } else {
                val bytes = readAt(ch, p.offset, (p.end - p.offset).toInt())
                val s = p.seq + shift
                bytes[18] = s.toByte(); bytes[19] = (s ushr 8).toByte(); bytes[20] = (s ushr 16).toByte(); bytes[21] = (s ushr 24).toByte()
                bytes[22] = 0; bytes[23] = 0; bytes[24] = 0; bytes[25] = 0
                val c = crc(bytes)
                bytes[22] = c.toByte(); bytes[23] = (c ushr 8).toByte(); bytes[24] = (c ushr 16).toByte(); bytes[25] = (c ushr 24).toByte()
                out.write(bytes)
            }
            p = page(ch, p.end)
        }
    }
}

object Jpeg {
    /** Width and height from the SOF marker, or null. */
    fun size(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var p = 2
        while (p + 9 < b.size) {
            if (b[p] != 0xFF.toByte()) { p++; continue }
            val m = b[p + 1].toInt() and 0xFF
            if (m == 0xD8 || m in 0xD0..0xD7 || m == 0x01) { p += 2; continue }
            val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = ((b[p + 5].toInt() and 0xFF) shl 8) or (b[p + 6].toInt() and 0xFF)
                val w = ((b[p + 7].toInt() and 0xFF) shl 8) or (b[p + 8].toInt() and 0xFF)
                return w to h
            }
            p += 2 + len
        }
        return null
    }
}

/** Cover-art sizes for the ⋮ setting. */
enum class ArtQuality(val px: Int, val jpegQ: Int, val label: String) {
    NONE(0, 0, "None"), LOW(300, 80, "Low (300 px)"), HIGH(720, 85, "High (720 px)");

    /** A square YouTube Music cover URL (`…=w226-h226-l90-rj`) at this size. */
    fun coverUrl(url: String): String = url.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w$px-h$px-l90-rj")

    /** The video thumbnail to crop the square from. */
    fun thumbUrls(videoId: String): List<String> = if (this == HIGH)
        listOf("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg", "https://i.ytimg.com/vi/$videoId/sddefault.jpg", "https://i.ytimg.com/vi/$videoId/hqdefault.jpg")
    else listOf("https://i.ytimg.com/vi/$videoId/hqdefault.jpg", "https://i.ytimg.com/vi/$videoId/mqdefault.jpg")

    companion object {
        /**
         * Centre square of a thumbnail: 4:3 thumbnails (hqdefault / sddefault) carry a 16:9 picture letterboxed, so the
         * bars are dropped first; Topic artwork is the square in the middle of the 16:9 frame. Returns (x, y, side).
         */
        fun square(w: Int, h: Int): Triple<Int, Int, Int> {
            val contentH = if (w * 3 == h * 4) w * 9 / 16 else h
            val top = (h - contentH) / 2
            val side = minOf(w, contentH)
            return Triple((w - side) / 2, top + (contentH - side) / 2, side)
        }
    }
}
