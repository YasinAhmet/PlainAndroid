package app.plain.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class PagingTest {
    @Test fun pages() {
        assertEquals(1, Paging.pageCount(0, 28))
        assertEquals(1, Paging.pageCount(28, 28))
        assertEquals(2, Paging.pageCount(29, 28))
        assertEquals(0, Paging.pageOf(27, 28))
        assertEquals(1, Paging.pageOf(28, 28))
        assertEquals(28 until 29, Paging.range(1, 28, 29))
        assertEquals(0 until 10, Paging.range(0, 28, 10))
        assertEquals(true, Paging.range(2, 28, 29).isEmpty())
    }

    @Test fun newestFirst() {
        data class R(val t: Long, val id: Long)
        val l = listOf(R(5, 1), R(9, 2), R(5, 3), R(1, 4))
        assertEquals(listOf(R(9, 2), R(5, 3), R(5, 1), R(1, 4)), Paging.newestFirst(l, { it.t }, { it.id }))
    }

    @Test fun takenTimeFallsBack() {
        assertEquals(1234L, Paging.takenTime(1234, 9, 8))
        assertEquals(9000L, Paging.takenTime(0, 9, 8))
        assertEquals(8000L, Paging.takenTime(0, 0, 8))
    }

    @Test fun formats() {
        assertEquals("0:42", Paging.duration(41_600))
        assertEquals("1:02:03", Paging.duration(3_723_000))
        assertEquals("3.5 MB", Paging.bytes(3_670_016))
        assertEquals("12 KB", Paging.bytes(12_400))
        assertEquals("12,345", Paging.count(12345))
    }
}
