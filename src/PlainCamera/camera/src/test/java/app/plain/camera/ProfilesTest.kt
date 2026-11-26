package app.plain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilesTest {
    private val night = Profile(
        name = "Night walk", adv = Adv(ev = -7, iso = 1600, shutterNs = 125_000_000, wb = Adv.WB_KELVIN, kelvin = 3200, focus = 0.2f,
            grid = 1, hints = false, steady = true, jpeg = 100, lens = 1, flashPct = 30),
        video = false, flashPhoto = 2, flashVideo = 1, aspectPhoto = "FOUR_THREE", aspectVideo = "SIXTEEN_NINE",
        sizePhoto = "4000x3000", sizeVideo = "1920x1080",
    )

    @Test fun roundTrip() {
        val odd = Profile(name = "a|b %7C c=d;e", video = true)
        val list = listOf(night, odd, Profile("Plain"))
        assertEquals(list, Profiles.parse(Profiles.serialize(list)))
        assertEquals(emptyList<Profile>(), Profiles.parse(null))
        assertEquals(emptyList<Profile>(), Profiles.parse(""))
        assertEquals(emptyList<Profile>(), Profiles.parse(Profiles.serialize(emptyList())))
    }

    @Test fun tolerantParse() {
        // missing fields fall back to defaults, broken numbers are clamped, nameless lines are skipped
        val l = Profiles.parse("n=Day|fp=9|fv=x|v=maybe\nadv=ev=3\ngarbage\nn=day|v=true\nn=  Spaced   name  ")
        assertEquals(listOf("Day", "Spaced name"), l.map { it.name })
        assertEquals(2, l[0].flashPhoto)
        assertEquals(0, l[0].flashVideo)
        assertEquals(false, l[0].video)
        assertEquals(Adv(), l[0].adv)
    }

    @Test fun names() {
        assertEquals("Night walk", Profiles.cleanName("  Night\n  walk "))
        assertNull(Profiles.cleanName("   "))
        assertEquals(Profiles.MAX_NAME, Profiles.cleanName("x".repeat(60))!!.length)
    }

    @Test fun upsertRenameRemove() {
        var l = listOf(Profile("A"), night)
        l = Profiles.upsert(l, Profile("night WALK", video = true))
        assertEquals(2, l.size)
        assertEquals("night WALK", l[1].name)
        assertTrue(l[1].video)
        l = Profiles.upsert(l, Profile("B"))
        assertEquals(listOf("A", "night WALK", "B"), l.map { it.name })
        assertNull(Profiles.rename(l, "A", "b")) // taken
        assertNull(Profiles.rename(l, "Z", "Y")) // missing
        assertEquals(listOf("A", "Night", "B"), Profiles.rename(l, "night walk", "Night")!!.map { it.name })
        assertEquals(listOf("a2", "night WALK", "B"), Profiles.rename(l, "A", "a2")!!.map { it.name })
        assertEquals(listOf("A", "Night Walk", "B"), Profiles.rename(l, "night walk", "Night Walk")!!.map { it.name }) // own name, new case
        assertEquals(listOf("A", "B"), Profiles.remove(l, "NIGHT walk").map { it.name })
        assertEquals("B", Profiles.find(l, "b")?.name)
    }
}
