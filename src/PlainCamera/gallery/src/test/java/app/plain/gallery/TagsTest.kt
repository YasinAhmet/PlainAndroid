package app.plain.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TagsTest {
    @Test fun createIgnoresCaseAndBlank() {
        val t = Tags()
        assertEquals("Hike", t.create("  Hike "))
        assertEquals("Hike", t.create("hike"))
        assertNull(t.create("   "))
        assertEquals(listOf("Hike"), t.names)
    }

    @Test fun namesSortedCaseInsensitive() {
        val t = Tags()
        t.create("zoo"); t.create("Apple"); t.create("bird")
        assertEquals(listOf("Apple", "bird", "zoo"), t.names)
    }

    @Test fun oneItemInManyCategories() {
        val t = Tags()
        t.add("Hike", listOf(1L, 2L))
        t.add("Family", listOf(2L, 3L))
        assertEquals(listOf("Family", "Hike"), t.byId()[2L])
        assertEquals(setOf(1L, 2L, 3L), t.allIds())
        t.remove("Hike", listOf(2L))
        assertEquals(listOf("Family"), t.byId()[2L])
    }

    @Test fun stateNoneSomeAll() {
        val t = Tags()
        t.add("A", listOf(1L, 2L))
        assertEquals(Tags.ALL, t.state("A", listOf(1L, 2L)))
        assertEquals(Tags.SOME, t.state("A", listOf(1L, 3L)))
        assertEquals(Tags.NONE, t.state("A", listOf(3L)))
        assertEquals(Tags.NONE, t.state("missing", listOf(1L)))
    }

    @Test fun applyChoiceLeavesUntouchedPartialAlone() {
        val t = Tags()
        t.add("A", listOf(1L))
        t.applyChoice("A", listOf(1L, 2L), checked = false, touched = false)
        assertEquals(setOf(1L), t.ids("A"))
        t.applyChoice("A", listOf(1L, 2L), checked = false, touched = true)
        assertTrue(t.ids("A").isEmpty())
        assertTrue(t.has("A"))
        t.applyChoice("A", listOf(1L, 2L), checked = true, touched = true)
        assertEquals(setOf(1L, 2L), t.ids("A"))
    }

    @Test fun renameAndMerge() {
        val t = Tags()
        t.add("A", listOf(1L)); t.add("B", listOf(2L))
        assertTrue(t.rename("A", "C"))
        assertEquals(listOf("B", "C"), t.names)
        assertTrue(t.rename("C", "b"))
        assertEquals(listOf("B"), t.names)
        assertEquals(setOf(1L, 2L), t.ids("B"))
        assertFalse(t.rename("missing", "x"))
    }

    @Test fun turkishNamesMatchWithoutCrashing() {
        // create() looks the stored key up again by equals(ignoreCase): it must agree with the map's order for
        // dotted / dotless i, or first {} would throw
        val t = Tags()
        assertEquals("İzmir", t.create("İzmir"))
        assertEquals("İzmir", t.create("izmir"))
        assertEquals("Işık", t.create("Işık"))
        assertEquals("Işık", t.create("IŞIK"))
        assertEquals("Işık", t.create("ışık"))
        t.add("ŞEHİR", listOf(1L)); t.add("şehir", listOf(2L))
        assertEquals(setOf(1L, 2L), t.ids("Şehir"))
        assertEquals(3, t.names.size)
        val back = Tags.parse(t.serialize())
        assertEquals(t.names, back.names)
    }

    @Test fun manyCategoriesAndIds() {
        val t = Tags()
        for (c in 0 until 200) t.add("Cat $c", (0L until 500L).map { it * 7 + c })
        assertEquals(200, t.names.size)
        val back = Tags.parse(t.serialize())
        assertEquals(500, back.ids("cat 199").size)
        assertEquals(t.allIds(), back.allIds())
    }

    @Test fun serializeRoundTrip() {
        val t = Tags()
        t.add("Hike\tday", listOf(5L, 7L))
        t.create("Empty")
        val back = Tags.parse(t.serialize())
        assertEquals(listOf("Empty", "Hike day"), back.names)
        assertEquals(setOf(5L, 7L), back.ids("hike day"))
        assertTrue(back.ids("Empty").isEmpty())
        assertEquals(0, Tags.parse("").names.size)
        assertEquals(setOf(3L), Tags.parse("X\t3,bad,\n\n").ids("X"))
    }
}
