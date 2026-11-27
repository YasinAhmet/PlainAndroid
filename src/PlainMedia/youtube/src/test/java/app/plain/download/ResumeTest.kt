package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {
    @Test fun keepsEverythingButTheEdges() {
        assertFalse(ResumeRule.worthKeeping(4_000, 600_000)) // barely started
        assertTrue(ResumeRule.worthKeeping(5_000, 600_000))
        assertTrue(ResumeRule.worthKeeping(300_000, 600_000))
        assertFalse(ResumeRule.worthKeeping(590_000, 600_000)) // practically finished
        assertTrue(ResumeRule.worthKeeping(60_000, 0)) // duration unknown: the start rule alone
        assertTrue(ResumeRule.worthKeeping(20_000, 40_000)) // a short clip counts too
    }

    @Test fun codec() {
        assertEquals(123_456L to 600_000L, ResumeRule.decode(ResumeRule.encode(123_456, 600_000, 99)))
        assertEquals(99L, ResumeRule.at(ResumeRule.encode(1, 2, 99)))
        assertEquals(42L to 0L, ResumeRule.decode("42"))
        assertNull(ResumeRule.decode(null))
        assertNull(ResumeRule.decode("x,1"))
        // the Music tab's old "pos,at": no duration, the second field is the time
        assertEquals(Triple(30_000L, 0L, 1_700_000_000_000L), ResumeRule.decodeFull("30000,1700000000000"))
    }

    @Test fun keysJoinBothTabs() {
        assertEquals(listOf("dQw4w9WgXcQ", "123"), ResumeRule.keys("dQw4w9WgXcQ", "content://media/external/audio/media/123"))
        assertEquals(listOf("123"), ResumeRule.keys("", "content://media/external/video/media/123"))
        assertEquals(listOf("file:///x.mp4"), ResumeRule.keys("", "file:///x.mp4"))
        assertEquals(listOf("abc"), ResumeRule.keys("abc", null))
    }

    @Test fun oldestGoFirst() {
        val all = mapOf("a" to "1,2,30", "b" to "1,2,10", "c" to "1,2,20")
        assertEquals(listOf("b", "c"), ResumeRule.oldest(all, 2))
        assertEquals(emptyList<String>(), ResumeRule.oldest(all, 0))
    }

    @Test fun clock() {
        assertEquals("0:15", ResumeRule.clock(15_400))
        assertEquals("12:34", ResumeRule.clock(754_000))
        assertEquals("1:02:03", ResumeRule.clock(3_723_000))
    }
}
