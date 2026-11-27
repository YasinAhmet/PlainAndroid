package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {
    @Test fun asksOnlyInTheMiddle() {
        assertFalse(ResumeRule.worthAsking(5_000, 600_000)) // barely started
        assertTrue(ResumeRule.worthAsking(15_000, 600_000))
        assertTrue(ResumeRule.worthAsking(300_000, 600_000))
        assertFalse(ResumeRule.worthAsking(590_000, 600_000)) // practically finished
        assertTrue(ResumeRule.worthAsking(60_000, 0)) // duration unknown: the start rule alone
    }

    @Test fun codec() {
        assertEquals(123_456L to 600_000L, ResumeRule.decode(ResumeRule.encode(123_456, 600_000, 99)))
        assertEquals(99L, ResumeRule.at(ResumeRule.encode(1, 2, 99)))
        assertEquals(42L to 0L, ResumeRule.decode("42"))
        assertNull(ResumeRule.decode(null))
        assertNull(ResumeRule.decode("x,1"))
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
