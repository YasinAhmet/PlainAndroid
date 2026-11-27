package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistLinksTest {
    @Test fun upsertKeepsOneLinkPerListAndRoundTrips() {
        var l = PlaylistLinks.upsert(emptyList(), "spotify:x", "Hike", listOf("a", "b", "a"), 1)
        l = PlaylistLinks.upsert(l, "yt:PL1", "Mix", listOf("c"), 2)
        l = PlaylistLinks.upsert(l, "spotify:x", "", listOf("a", "b", "d"), 3) // run again: same key, new songs
        assertEquals(listOf("yt:PL1", "spotify:x"), l.map { it.key })
        assertEquals("Hike", l[1].name) // a blank name keeps the old one
        assertEquals(listOf("a", "b", "d"), l[1].ids)
        assertEquals(l, PlaylistLinks.decode(PlaylistLinks.encode(l)))
        assertEquals(emptyList<PlaylistLinks.Link>(), PlaylistLinks.decode("not json"))
    }

    @Test fun backfillTakesListsThatAreMostlySaved() {
        val existing = PlaylistLinks.upsert(emptyList(), "spotify:old", "Old", listOf("x", "y"), 1)
        val saved = setOf("a", "b", "c", "x", "y", "q")
        val out = PlaylistLinks.backfill(existing, listOf(
            Triple("spotify:old", "Old again", listOf("a", "b")), // linked already: untouched
            Triple("spotify:full", "Full", listOf("a", "b", "c")),
            Triple("spotify:half", "Half", listOf("a", "b", "m", "n")),
            Triple("spotify:few", "Few", listOf("a", "m", "n", "o")), // 1 of 4: not a downloaded list
            Triple("list:one", "One", listOf("q")), // a single saved song is not enough
        ), { it in saved }, 9)
        assertEquals(listOf("spotify:old", "spotify:full", "spotify:half"), out.map { it.key })
        assertEquals("Old", out[0].name)
        assertEquals(listOf("a", "b", "m", "n"), out[2].ids) // the whole list, so later downloads join in order
    }
}
