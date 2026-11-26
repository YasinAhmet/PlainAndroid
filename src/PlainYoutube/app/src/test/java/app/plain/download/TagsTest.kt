package app.plain.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * 1.9: tags + cover art written into M4A (YouTube's fragmented DASH file: "Me at the zoo" itag 140, cut after the first
 * fragment) and Ogg Opus (the same video's Opus stream in Ogg, cut after 8 pages). Full files were also checked on
 * the PC with PyAV (identical decoded audio, duration, seeks) and mutagen (tags, cover).
 */
class TagsTest {
    private fun res(name: String) = File(javaClass.getResource("/$name")!!.toURI())
    private val jpeg by lazy { res("cover_300.jpg").readBytes() }
    private val tags get() = TagSet("Me at the zoo (ñ)", "jawed", "Zoo Album", jpeg)

    private fun write(src: File, ogg: Boolean, t: TagSet): File {
        val out = File.createTempFile("tagged", if (ogg) ".ogg" else ".m4a")
        RandomAccessFile(src, "r").use { r -> out.outputStream().use { o -> if (ogg) OggTags.write(r.channel, o, t) else Mp4Tags.write(r.channel, o, t) } }
        return out
    }

    private fun <T> ch(f: File, block: (java.nio.channels.FileChannel) -> T): T = RandomAccessFile(f, "r").use { block(it.channel) }

    @Test fun fragmentedM4a() {
        val src = res("zoo_frag_cut.m4a")
        val before = ch(src) { Mp4Tags.topBoxes(it) }
        assertEquals(listOf("ftyp", "moov", "sidx", "moof", "mdat"), before.map { it.type })
        assertFalse(ch(src) { Mp4Tags.hasCover(it) })
        val out = write(src, false, tags)
        val after = ch(out) { Mp4Tags.topBoxes(it) }
        assertEquals(before.map { it.type }, after.map { it.type })
        val grow = after[1].size - before[1].size
        assertTrue(grow > jpeg.size)
        assertEquals(src.length() + grow, out.length())
        // everything after moov is byte-identical (sidx/moof offsets are relative: nothing to patch)
        val a = src.readBytes(); val b = out.readBytes()
        val moovEnd = (before[1].offset + before[1].size).toInt()
        assertArrayEquals(a.copyOfRange(moovEnd, a.size), b.copyOfRange(moovEnd + grow.toInt(), b.size))
        val t = Mp4Tags.tagsOf(ch(out) { Mp4Tags.readMoov(it)!!.second })
        assertEquals("Me at the zoo (ñ)", String(t["©nam"]!!, Charsets.UTF_8))
        assertEquals("jawed", String(t["©ART"]!!)); assertEquals("jawed", String(t["aART"]!!)); assertEquals("Zoo Album", String(t["©alb"]!!))
        assertEquals("Google", String(t["©too"]!!)) // YouTube's own item kept
        assertArrayEquals(jpeg, t["covr"])
        assertTrue(ch(out) { Mp4Tags.hasCover(it) })
        // tagging again replaces (no stacking); without a picture the existing cover stays
        val again = write(out, false, tags)
        assertEquals(out.length(), again.length())
        val noPic = write(out, false, TagSet("New title", "jawed", null, null))
        val t2 = Mp4Tags.tagsOf(ch(noPic) { Mp4Tags.readMoov(it)!!.second })
        assertEquals("New title", String(t2["©nam"]!!)); assertArrayEquals(jpeg, t2["covr"]); assertFalse("©alb" in t2)
        listOf(out, again, noPic).forEach { it.delete() }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + payload.size); b.putInt(8 + payload.size); b.put(type.toByteArray()); b.put(payload); return b.array()
    }

    @Test fun moovBeforeMediaShiftsChunkOffsets() {
        // ftyp + moov(trak/mdia/minf/stbl/stco: 2 chunks) + mdat("AAAA" "BBBB"): like MediaMuxer's moov-at-start layout
        val ftyp = box("ftyp", "M4A ".toByteArray() + ByteArray(4))
        fun moov(o1: Int, o2: Int): ByteArray {
            val stco = box("stco", ByteBuffer.allocate(16).putInt(0).putInt(2).putInt(o1).putInt(o2).array())
            return box("moov", box("trak", box("mdia", box("minf", box("stbl", stco)))))
        }
        val m0 = moov(0, 0)
        val mdatStart = ftyp.size + m0.size + 8
        val file = ftyp + moov(mdatStart, mdatStart + 4) + box("mdat", "AAAABBBB".toByteArray())
        val src = File.createTempFile("moovstart", ".m4a").apply { writeBytes(file) }
        val out = write(src, false, tags)
        val b = out.readBytes()
        val boxes = ch(out) { Mp4Tags.topBoxes(it) }
        val moovBox = boxes.first { it.type == "moov" }
        val mdat = boxes.first { it.type == "mdat" }
        // find stco in the new moov and check both offsets still point at "AAAA" / "BBBB"
        val s = String(b, Charsets.ISO_8859_1).indexOf("stco")
        val bb = ByteBuffer.wrap(b)
        val o1 = bb.getInt(s + 12); val o2 = bb.getInt(s + 16)
        assertEquals(mdat.offset.toInt() + 8, o1)
        assertEquals("AAAA", String(b, o1, 4)); assertEquals("BBBB", String(b, o2, 4))
        assertTrue(moovBox.offset < mdat.offset)
        // moov at the end: nothing moves
        val end = ftyp + box("mdat", "AAAABBBB".toByteArray()) + moov(ftyp.size + 8, ftyp.size + 12)
        val src2 = File.createTempFile("moovend", ".m4a").apply { writeBytes(end) }
        val out2 = write(src2, false, tags).readBytes()
        val s2 = String(out2, Charsets.ISO_8859_1).indexOf("stco")
        assertEquals(ftyp.size + 8, ByteBuffer.wrap(out2).getInt(s2 + 12))
        assertEquals("AAAA", String(out2, ftyp.size + 8, 4))
        listOf(src, out, src2).forEach { it.delete() }
    }

    private class OggPage(val seq: Int, val crc: Int, val bytes: ByteArray, val headerType: Int, val serial: Int)

    private fun pages(f: File): List<OggPage> {
        val out = ArrayList<OggPage>()
        ch(f) { c ->
            var p = 0L
            while (true) {
                val pg = OggTags.page(c, p) ?: break
                val bytes = ByteArray((pg.end - pg.offset).toInt()); c.read(ByteBuffer.wrap(bytes), pg.offset)
                val crc = ByteBuffer.wrap(bytes, 22, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                out += OggPage(pg.seq, crc, bytes, pg.headerType, pg.serial)
                p = pg.end
            }
        }
        return out
    }

    private fun crcOk(p: OggPage): Boolean {
        val b = p.bytes.copyOf(); b[22] = 0; b[23] = 0; b[24] = 0; b[25] = 0
        return OggTags.crc(b) == p.crc
    }

    @Test fun oggOpusCommentsAndPicture() {
        val src = res("zoo_cut.ogg")
        val orig = pages(src)
        assertTrue(orig.all { crcOk(it) }) // our CRC matches libogg's
        assertFalse(ch(src) { OggTags.hasPicture(it) })
        // a big picture (≈ 120 KB) makes the comment packet span several pages
        val big = jpeg + ByteArray(120_000) { (it * 31).toByte() }
        for (pic in listOf(jpeg, big)) {
            val out = write(src, true, TagSet("Me at the zoo (ñ)", "jawed", "Zoo Album", pic))
            val pg = pages(out)
            assertTrue(pg.all { crcOk(it) })
            assertEquals((0 until pg.size).toList(), pg.map { it.seq }) // renumbered, no gaps
            assertEquals(orig.first().bytes.toList(), pg.first().bytes.toList()) // OpusHead untouched
            val commentPages = pg.size - (orig.size - 1)
            // audio pages: same payload after the header (only seq + CRC differ)
            val oa = orig.drop(2).map { it.bytes.copyOfRange(27 + (it.bytes[26].toInt() and 0xFF), it.bytes.size).toList() }
            val na = pg.drop(1 + commentPages).map { it.bytes.copyOfRange(27 + (it.bytes[26].toInt() and 0xFF), it.bytes.size).toList() }
            assertEquals(oa, na)
            if (pic === big) assertTrue("spans pages: $commentPages", commentPages >= 2)
            assertTrue(pg.drop(2).take(commentPages - 1).all { it.headerType == 1 }) // continued pages
            assertTrue(ch(out) { OggTags.hasPicture(it) })
            // read the comment packet back
            val pkt = ByteArrayOutputStream()
            for (p in pg.drop(1).take(commentPages)) { val n = p.bytes[26].toInt() and 0xFF; pkt.write(p.bytes, 27 + n, p.bytes.size - 27 - n) }
            val (vendor, comments) = OggTags.parse(pkt.toByteArray())
            assertTrue(vendor.isNotEmpty())
            assertTrue("TITLE=Me at the zoo (ñ)" in comments); assertTrue("ARTIST=jawed" in comments); assertTrue("ALBUM=Zoo Album" in comments)
            val picB64 = comments.first { it.startsWith("METADATA_BLOCK_PICTURE=") }.substringAfter('=')
            val block = java.util.Base64.getDecoder().decode(picB64)
            val bb = ByteBuffer.wrap(block)
            assertEquals(3, bb.int) // front cover
            val mime = ByteArray(bb.int).also { bb.get(it) }
            assertEquals("image/jpeg", String(mime))
            assertEquals(0, bb.int) // description
            assertEquals(300, bb.int); assertEquals(300, bb.int)
            out.delete()
        }
    }

    @Test fun artSizes() {
        assertEquals(300 to 300, Jpeg.size(jpeg))
        assertEquals(Triple(105, 45, 270), ArtQuality.square(480, 360)) // hqdefault: 16:9 letterboxed in 4:3
        assertEquals(Triple(70, 0, 180), ArtQuality.square(320, 180)) // mqdefault
        assertEquals(Triple(280, 0, 720), ArtQuality.square(1280, 720)) // maxresdefault
        assertEquals(Triple(0, 0, 300), ArtQuality.square(300, 300))
        assertEquals("https://lh3.googleusercontent.com/abc=w300-h300-l90-rj", ArtQuality.LOW.coverUrl("https://lh3.googleusercontent.com/abc=w226-h226-l90-rj"))
        assertEquals("https://yt3.googleusercontent.com/x=w720-h720-l90-rj", ArtQuality.HIGH.coverUrl("https://yt3.googleusercontent.com/x=w544-h544-l90-rj"))
        assertEquals(listOf("https://i.ytimg.com/vi/abcdefghijk/hqdefault.jpg", "https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg"), ArtQuality.LOW.thumbUrls("abcdefghijk"))
        assertTrue(ArtQuality.HIGH.thumbUrls("x").first().endsWith("maxresdefault.jpg"))
        val j = Job(1, "abcdefghijk", "T", "C", 0, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC, art = true, uri = "content://m/1")
        val back = JobCodec.decode(JobCodec.encode(listOf(j)))[0]
        assertTrue(back.art); assertEquals("Cover art", back.what)
    }
}
