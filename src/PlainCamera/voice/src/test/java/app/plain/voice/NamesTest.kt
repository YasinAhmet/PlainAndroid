package app.plain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Rename edge cases (illegal, hidden, too long in bytes, taken names), the storage limit and call pauses. */
class NamesTest {
    private fun utf8(s: String) = s.toByteArray(Charsets.UTF_8).size

    @Test fun noHiddenNames() {
        assertNull(Format.cleanName(". ."))
        assertNull(Format.cleanName(" . . . "))
        assertEquals("hidden.m4a", Format.cleanName(" .hidden"))
        assertEquals("hidden.m4a", Format.cleanName(". .hidden"))
        assertEquals("a.b.m4a", Format.cleanName("a.b"))
        assertEquals("x y.m4a", Format.cleanName("x\u007Fy"))
        assertEquals("tab here.m4a", Format.cleanName("tab\there"))
    }

    @Test fun nameFitsTheFileSystemInBytes() {
        // Turkish letters are 2 bytes each: 150 of them would be 300 bytes, over the 255-byte limit
        val tr = Format.cleanName("ş".repeat(150))!!
        assertEquals("ş".repeat(100) + ".m4a", tr)
        assertTrue(utf8(tr) <= 255)
        // emoji are 4 bytes (2 chars): never cut in half
        val em = Format.cleanName("😀".repeat(70))!!
        assertEquals("😀".repeat(50) + ".m4a", em)
        // an emoji across the 120-character line is left out whole
        assertEquals("x".repeat(119) + ".m4a", Format.cleanName("x".repeat(119) + "😀"))
        assertEquals(124, Format.cleanName("x".repeat(300))!!.length)
    }

    @Test fun fitKeepsWholeCharacters() {
        assertEquals("ab", Format.fit("abc", 2, 10))
        assertEquals("ço", Format.fit("çok", 10, 3))
        assertEquals("", Format.fit("😀", 1, 10))
        assertEquals("", Format.fit("😀", 10, 3))
        assertEquals("😀", Format.fit("😀a", 2, 4))
        assertEquals("", Format.fit("", 5, 5))
    }

    @Test fun cleanNameStress() {
        val rnd = Random(11)
        val pool = "abcçğıİöşüÇĞÖŞÜ .-_/\\:*?\"<>|\t\n😀🎵日本".let { p ->
            val cps = ArrayList<String>(); var i = 0
            while (i < p.length) { val cp = p.codePointAt(i); cps += String(Character.toChars(cp)); i += Character.charCount(cp) }
            cps
        }
        repeat(10_000) {
            val typed = buildString { repeat(rnd.nextInt(0, 260)) { append(pool[rnd.nextInt(pool.size)]) } }
            val n = Format.cleanName(typed) ?: return@repeat
            assertTrue(n.endsWith(".m4a"))
            assertTrue(utf8(n) <= Format.MAX_NAME_BYTES + 4)
            assertFalse(n.startsWith(".") || n.startsWith(" "))
            assertTrue(n.none { it in "/\\:*?\"<>|" || it.code < 32 })
            for (k in n.indices) {
                if (Character.isHighSurrogate(n[k])) assertTrue(k + 1 < n.length && Character.isLowSurrogate(n[k + 1]))
                if (Character.isLowSurrogate(n[k])) assertTrue(k > 0 && Character.isHighSurrogate(n[k - 1]))
            }
        }
    }

    @Test fun takenNamesGetANumber() {
        assertEquals("Trail.m4a", Format.uniqueName("Trail.m4a", listOf("Other", "Trail 2")))
        assertEquals("Trail (2).m4a", Format.uniqueName("Trail.m4a", listOf("trail")))
        assertEquals("Trail (3).m4a", Format.uniqueName("Trail.m4a", listOf("Trail", "Trail (2)")))
        assertEquals("Kuş (2).m4a", Format.uniqueName("Kuş.m4a", listOf("KUŞ")))
        assertEquals("A.m4a", Format.uniqueName("A.m4a", emptyList()))
        val many = (2..500).map { "Note ($it)" } + "Note"
        assertEquals("Note (501).m4a", Format.uniqueName("Note.m4a", many))
    }

    @Test fun storageLimit() {
        val mb = 1L shl 20
        assertEquals(Long.MAX_VALUE, Format.recordLimit(Long.MAX_VALUE))
        assertEquals(84 * mb, Format.recordLimit(100 * mb))
        assertEquals(0L, Format.recordLimit(16 * mb + 500 * 1024))
        assertEquals(0L, Format.recordLimit(0))
        assertEquals(0L, Format.recordLimit(-1))
    }

    @Test fun callsPauseAndResume() {
        // a call while recording pauses
        assertEquals(Format.CALL_PAUSE, Format.callAction(inCall = true, recording = true, paused = false, pausedByCall = false))
        // the end of that call resumes
        assertEquals(Format.CALL_RESUME, Format.callAction(inCall = false, recording = false, paused = true, pausedByCall = true))
        // a pause the user made stays, during and after a call
        assertEquals(Format.CALL_NONE, Format.callAction(inCall = true, recording = false, paused = true, pausedByCall = false))
        assertEquals(Format.CALL_NONE, Format.callAction(inCall = false, recording = false, paused = true, pausedByCall = false))
        // ringing → in call: already paused by the call, nothing more
        assertEquals(Format.CALL_NONE, Format.callAction(inCall = true, recording = false, paused = true, pausedByCall = true))
        // nothing to do while recording normally
        assertEquals(Format.CALL_NONE, Format.callAction(inCall = false, recording = true, paused = false, pausedByCall = false))
    }
}
