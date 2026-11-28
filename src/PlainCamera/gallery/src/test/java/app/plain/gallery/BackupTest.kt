package app.plain.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class BackupTest {
    private fun tags(vararg c: Pair<String, List<Long>>) = Tags().apply { for ((n, ids) in c) { create(n); add(n, ids) } }

    @Test fun roundTrip() {
        val t = tags("Doğa" to listOf(1L, 2L), "Aile İzmir" to listOf(2L, 3L), "Boş" to emptyList())
        val keys = mapOf(1L to "DCIM/Camera/a.jpg|10", 2L to "DCIM/Camera/b\tx.jpg|20")
        val d = Backup.decode(Backup.encode(1234L, t, keys))!!
        assertEquals(1234L, d.time)
        assertEquals(t.serialize(), d.tags.serialize())
        assertEquals("DCIM/Camera/a.jpg|10", d.keys[1L])
        assertEquals("DCIM/Camera/b x.jpg|20", d.keys[2L]) // tabs flattened
        assertTrue(d.tags.has("Boş"))
    }

    @Test fun decodeRejectsAndSurvivesJunk() {
        assertNull(Backup.decode(""))
        assertNull(Backup.decode("hello\nc\tX\t1"))
        val d = Backup.decode("PlainGalleryCategories 1\r\ntime\tabc\nk\tnope\tx\nk\t5\t\nc\nc\tA\t1,zz,2\nweird line\n")!!
        assertEquals(0L, d.time)
        assertTrue(d.keys.isEmpty())
        assertEquals(setOf(1L, 2L), d.tags.ids("A"))
    }

    @Test fun key() {
        assertNull(Backup.key("DCIM/", null, 1))
        assertNull(Backup.key("DCIM/", "", 1))
        assertEquals("a.jpg|5", Backup.key(null, "a.jpg", 5))
    }

    @Test fun remapKeepsSameIds() {
        val d = Backup.Data(1, tags("A" to listOf(1L, 2L, 9L)), mapOf(1L to "x|1", 2L to "y|2", 9L to "gone|9"))
        val t = Backup.remap(d, mapOf(1L to "x|1", 2L to "y|2"))
        assertEquals(setOf(1L, 2L, 9L), t.ids("A")) // 9 is gone: kept, harmless
    }

    @Test fun remapFollowsKeysWhenIdsChanged() {
        // the media store was rebuilt: every id now names another file
        val d = Backup.Data(1, tags("A" to listOf(1L, 2L), "B" to listOf(3L)), mapOf(1L to "x|1", 2L to "y|2", 3L to "z|3"))
        val now = mapOf(1L to "other|7", 2L to "other2|8", 3L to "other3|9", 101L to "x|1", 102L to "y|2")
        val t = Backup.remap(d, now)
        assertEquals(setOf(101L, 102L), t.ids("A"))
        assertTrue(t.has("B"))
        assertTrue(t.ids("B").isEmpty()) // z.jpg is nowhere: not pinned to the wrong photo
    }

    @Test fun remapRenamedUnderSameIdStays() {
        val d = Backup.Data(1, tags("A" to listOf(1L, 2L, 3L)), mapOf(1L to "x|1", 2L to "y|2", 3L to "z|3"))
        val t = Backup.remap(d, mapOf(1L to "x|1", 2L to "y|2", 3L to "renamed|3"))
        assertEquals(setOf(1L, 2L, 3L), t.ids("A"))
    }

    @Test fun mergeKeepsExisting() {
        val into = tags("A" to listOf(1L))
        Backup.merge(into, tags("a" to listOf(2L), "B" to listOf(3L)))
        assertEquals(setOf(1L, 2L), into.ids("A"))
        assertEquals(setOf(3L), into.ids("B"))
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val t = type.toByteArray(Charsets.ISO_8859_1)
        val n = data.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(t); out.write(data)
        val c = CRC32().apply { update(t); update(data) }.value
        out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
    }

    private fun fakePng(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
        chunk(out, "IHDR", ByteArray(13))
        chunk(out, "IDAT", ByteArray(20) { it.toByte() })
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    @Test fun pngCarriesData() {
        val png = fakePng()
        assertNull(Png.readData(png))
        val text = Backup.encode(5L, tags("Doğa" to (1L..20000L).toList()), (1L..20000L).associateWith { "DCIM/Camera/IMG_$it.jpg|$it" })
        val once = Png.withData(png, text.toByteArray(Charsets.UTF_8))
        assertTrue(Png.isPng(once))
        assertEquals(text, String(Png.readData(once)!!, Charsets.UTF_8))
        // writing again replaces the old copy instead of stacking chunks
        val twice = Png.withData(once, "PlainGalleryCategories 1\n".toByteArray())
        assertEquals("PlainGalleryCategories 1\n", String(Png.readData(twice)!!))
        assertTrue(twice.size < once.size)
        // the original chunks are untouched and IEND stays last
        assertArrayEquals(png.copyOfRange(png.size - 12, png.size), twice.copyOfRange(twice.size - 12, twice.size))
    }

    @Test fun pngJunk() {
        assertNull(Png.readData(ByteArray(0)))
        assertNull(Png.readData("not a png at all".toByteArray()))
        val cut = Png.withData(fakePng(), "x".repeat(1000).toByteArray()).let { it.copyOf(it.size - 30) }
        Png.readData(cut) // truncated: no crash
        val bad = fakePng().copyOf(); bad[8] = 0x7F // absurd chunk length
        assertNull(Png.readData(bad))
    }
}
