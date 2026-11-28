package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomFeedTest {
    private val a = "UC" + "a".repeat(22)
    private val b = "UC" + "b".repeat(22)
    private val c = "UC" + "c".repeat(22)

    private fun v(id: String, ch: String, at: Long, kind: IKind = IKind.VIDEO) = Item(kind, id, "t$id", "n$ch", ch, published = at)

    private fun feed(vararg ch: String, unwatched: Boolean = false) = CFeed(1, "F", ch.map { Follow(it, it) }, unwatched)

    @Test fun mergeNewestFirstAcrossChannels() {
        val pool = listOf(v("a1", a, 100), v("b1", b, 300), v("a2", a, 200), v("c1", c, 999), v("b0", b, 0))
        assertEquals(listOf("b1", "a2", "a1", "b0"), CustomFeeds.strip(feed(a, b), pool).map { it.id })
    }

    @Test fun duplicatesFromBothCachesShowOnce() {
        val pool = listOf(v("x", a, 5), v("x", a, 5), v("y", a, 4))
        assertEquals(listOf("x", "y"), CustomFeeds.strip(feed(a), pool).map { it.id })
    }

    @Test fun boundedToStripAndNoChannelsMeansEmpty() {
        val pool = (1..100).map { v("v$it", a, it.toLong()) }
        val s = CustomFeeds.strip(feed(a), pool)
        assertEquals(CustomFeeds.STRIP, s.size)
        assertEquals("v100", s.first().id)
        assertTrue(CustomFeeds.strip(feed(), pool).isEmpty())
        assertTrue(CustomFeeds.strip(feed(a), pool, limit = -3).isEmpty())
        // only videos / shorts, not channels or playlists
        assertEquals(1, CustomFeeds.strip(feed(b), listOf(v("s", b, 1, IKind.SHORT), v("p", b, 2, IKind.PLAYLIST))).size)
    }

    @Test fun unwatchedFilterBeforeLimit() {
        val pool = (1..40).map { v("v$it", a, it.toLong()) }
        val watched = (21..40).map { "v$it" }.toSet()
        val s = CustomFeeds.strip(feed(a, unwatched = true), pool, { it in watched })
        assertEquals(20, s.size)
        assertEquals("v20", s.first().id)
        assertFalse(s.any { it.id in watched })
        // the toggle off: watched ones show
        assertEquals("v40", CustomFeeds.strip(feed(a), pool, { it in watched }).first().id)
        assertEquals(listOf("b"), CustomFeeds.unwatched(listOf(v("a", a, 1), v("b", a, 2)), { it == "a" }).map { it.id })
    }

    @Test fun jsonRoundTrip() {
        val l = listOf(CFeed(5, "Müzik ğüşİı 🎵", listOf(Follow(a, "A \"q\""), Follow(b, "B")), true), CFeed(9, "Two", emptyList()))
        val s = CustomFeeds.encode(l)
        assertEquals(CustomFeeds.VERSION, JSONObject(s).getInt("version"))
        assertEquals(l, CustomFeeds.decode(s))
    }

    @Test fun damagedOrOldFiles() {
        assertTrue(CustomFeeds.decode(null).isEmpty())
        assertTrue(CustomFeeds.decode("").isEmpty())
        assertTrue(CustomFeeds.decode("{\"version\":1,\"feeds\":[{\"id\":1,\"na").isEmpty()) // truncated
        assertTrue(CustomFeeds.decode("garbage").isEmpty())
        assertTrue(CustomFeeds.decode("{\"version\":1}").isEmpty())
        assertTrue(CustomFeeds.decode("{\"version\":1,\"feeds\":\"x\"}").isEmpty())
        // a bare array (no version), a newer version, junk entries, bad / duplicate channels, duplicate ids, no name
        val bare = "[{\"id\":3,\"name\":\"  A   b \",\"channels\":[{\"id\":\"$a\",\"title\":\"\"},{\"id\":\"$a\"},{\"id\":\"bad\"},\"$b\",5,null]}," +
            "7,null,{\"id\":3,\"channels\":[]},{}]"
        val d = CustomFeeds.decode(bare)
        assertEquals(3, d.size)
        assertEquals("A b", d[0].name)
        assertEquals(listOf(a, b), d[0].channels.map { it.id })
        assertEquals(a, d[0].channels[0].title) // empty title -> id
        assertEquals(3, d.map { it.id }.toSet().size) // duplicate / missing ids made unique
        assertEquals("Feed 2", d[1].name)
        assertFalse(d[0].unwatchedOnly)
        val newer = "{\"version\":99,\"extra\":{},\"feeds\":[{\"id\":1,\"name\":\"N\",\"unwatched\":true,\"future\":1,\"channels\":[]}]}"
        assertEquals(listOf(CFeed(1, "N", emptyList(), true)), CustomFeeds.decode(newer))
    }

    @Test fun editing() {
        var (l, f) = CustomFeeds.add(emptyList(), "  ", listOf(Follow(a, "A"), Follow(a, "A")), 1000)
        assertEquals("Feed 1", f.name); assertEquals(1, f.channels.size)
        val (l2, g) = CustomFeeds.add(l, "G", emptyList(), 500) // clock went back: ids stay unique
        l = l2
        assertTrue(g.id > f.id)
        l = CustomFeeds.update(l, g.id) { CustomFeeds.withChannels(it, listOf(Follow(b, "B"), Follow(c, "C"), Follow(b, "B"))) }
        assertEquals(listOf(b, c), l[1].channels.map { it.id })
        l = CustomFeeds.update(l, g.id) { CustomFeeds.withoutChannels(it, setOf(b)) }
        assertEquals(listOf(c), l[1].channels.map { it.id })
        assertEquals(listOf(g.id, f.id), CustomFeeds.move(l, g.id, -1).map { it.id })
        assertEquals(l, CustomFeeds.move(l, g.id, 5)) // already last
        assertEquals(l, CustomFeeds.move(l, 12345, 1))
        assertEquals(listOf(a, c), CustomFeeds.allChannels(l).map { it.id })
        assertEquals(CustomFeeds.MAX_NAME, CustomFeeds.cleanName("x".repeat(500)).length)
    }

    @Test fun paging() {
        assertEquals(1, CustomFeeds.pages(0, 5)) // just "+ New feed"
        assertEquals(1, CustomFeeds.pages(4, 5))
        assertEquals(2, CustomFeeds.pages(5, 5)) // the "+ New feed" row goes to page 2
        assertEquals(3, CustomFeeds.pages(14, 5))
    }

    @Test fun visitedParse() {
        assertEquals(listOf("dQw4w9WgXcQ"), Visited.parse("dQw4w9WgXcQ\n\nbad\ndQw4w9WgXcQ\nwaytoolongid12"))
        assertTrue(Visited.parse(null).isEmpty())
    }

    @Test fun stressManyFeedsAndItems() {
        val chans = (0 until 500).map { "UC" + it.toString().padStart(22, '0') }
        val pool = (0 until 20_000).map { v("v$it", chans[it % 500], (it * 7919L) % 100_000) }
        val f = CFeed(1, "big", chans.map { Follow(it, it) })
        val t0 = System.nanoTime()
        val s = CustomFeeds.strip(f, pool)
        assertTrue((System.nanoTime() - t0) < 2_000_000_000L)
        assertEquals(30, s.size)
        assertTrue(s.zipWithNext().all { (x, y) -> x.published >= y.published })
        val many = (1..300).map { CFeed(it.toLong(), "f$it", listOf(Follow(a, "A"))) }
        assertEquals(CustomFeeds.MAX_FEEDS, CustomFeeds.decode(CustomFeeds.encode(many)).size)
    }
}
