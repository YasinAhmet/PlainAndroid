package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonTest {
    private fun d(ymd: Long) = Season.fromYmd(ymd)

    @Test
    fun leafOffNorthSouthTropics() {
        val belgrad = 41.18
        assertTrue(Season.leafOff(d(20240206), belgrad))   // Feb
        assertTrue(Season.leafOff(d(20211122), belgrad))   // late Nov
        assertTrue(Season.leafOff(d(20230410), belgrad))   // early Apr
        assertFalse(Season.leafOff(d(20250503), belgrad))  // May: leaves on
        assertFalse(Season.leafOff(d(20230712), belgrad))  // July
        assertFalse(Season.leafOff(d(20231105), belgrad))  // early Nov: still leafy
        assertTrue(Season.leafOff(d(20230720), -42.0))     // southern winter
        assertFalse(Season.leafOff(d(20240206), -42.0))
        assertFalse(Season.leafOff(d(20240206), 10.0))     // tropics: never
        assertTrue(Season.ideal(d(20220315), belgrad))
        assertFalse(Season.ideal(d(20240206), belgrad))    // leaf-off but deep winter
    }

    @Test
    fun recommendsNewestLeafOffPreferringIdealMonths() {
        val lat = 41.18
        // what Esri's archive actually holds at Belgrad Forest (newest first)
        val caps = listOf(d(20250503), d(20240206), d(20230712), d(20211122))
        assertEquals(1, Season.recommend(caps, lat))              // Feb 2024 (Nov 2021 ideal but too old)
        val caps2 = listOf(d(20250503), d(20240206), d(20231210))
        assertEquals(2, Season.recommend(caps2, lat))             // Dec 2023 ideal, only 2 months older
        assertEquals(0, Season.recommend(listOf(d(20250503), d(20230712)), lat)) // no leaf-off: newest
        assertEquals(0, Season.recommend(caps, 5.0))              // tropics: newest
        assertEquals(-1, Season.recommend(emptyList(), lat))
    }
}
