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
}
