package app.plain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class FormatTest {
    @Test fun fileNames() {
        val t = Calendar.getInstance().apply { clear(); set(2026, Calendar.NOVEMBER, 27, 14, 3, 22) }.timeInMillis
        assertEquals("Voice 2026-11-27 14-03-22.m4a", Format.fileName(t))
        assertEquals("Voice 2026-11-27 14-03-22", Format.shownName(Format.fileName(t)))
        assertEquals("Notes", Format.shownName("Notes.M4A"))
        assertEquals("Notes.mp3", Format.shownName("Notes.mp3"))
    }

    @Test fun cleanNames() {
        assertEquals("Trail notes.m4a", Format.cleanName("  Trail notes  "))
        assertEquals("Trail notes.m4a", Format.cleanName("Trail notes.m4a"))
        assertEquals("a b c.m4a", Format.cleanName("a/b\\c"))
        assertEquals("Kuş sesi.m4a", Format.cleanName("Kuş sesi"))
        assertEquals("hidden.m4a", Format.cleanName("..hidden"))
        assertNull(Format.cleanName("   "))
        assertNull(Format.cleanName("???"))
        assertNull(Format.cleanName(".m4a"))
        assertEquals(124, Format.cleanName("x".repeat(300))!!.length)
    }

    @Test fun folders() {
        assertEquals("Music/Plain Voice/", Format.folderFor(29))
        assertEquals("Music/Plain Voice/", Format.folderFor(30))
        assertEquals("Recordings/Plain Voice/", Format.folderFor(31))
        assertEquals("Recordings/Plain Voice/", Format.folderFor(35))
        assertTrue(Format.allFolders.containsAll(listOf(Format.folderFor(29), Format.folderFor(35))))
    }

    @Test fun times() {
        assertEquals("0:00", Format.duration(0))
        assertEquals("0:42", Format.duration(41_600))
        assertEquals("1:02:03", Format.duration(3_723_000))
        assertEquals("00:00", Format.clock(999))
        assertEquals("00:07", Format.clock(7_900))
        assertEquals("12:34", Format.clock(754_000))
        assertEquals("1:02:03", Format.clock(3_723_400))
        assertEquals("00:00", Format.clock(-5))
    }

    @Test fun sizes() {
        assertEquals("3.5 MB", Format.bytes(3_670_016))
        assertEquals("12 KB", Format.bytes(12_400))
        assertEquals("800 B", Format.bytes(800))
    }

    @Test fun paging() {
        assertEquals(1, Format.pageCount(0, 8))
        assertEquals(1, Format.pageCount(8, 8))
        assertEquals(2, Format.pageCount(9, 8))
        assertEquals(1, Format.pageCount(5, 0))
        assertEquals(0, Format.pageOf(7, 8))
        assertEquals(1, Format.pageOf(8, 8))
        assertEquals(8 until 9, Format.range(1, 8, 9))
        assertEquals(0 until 5, Format.range(0, 8, 5))
        assertTrue(Format.range(2, 8, 9).isEmpty())
        assertEquals(7, Format.rowsFor(420, 60))
        assertEquals(1, Format.rowsFor(10, 60))
        assertEquals(1, Format.rowsFor(500, 0))
    }
}
