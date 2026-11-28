package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Blocked channels missing from YouTube's channel search, and followed channels whose feed stays empty. */
class BlockedChannelTest {
    private val fosix = "UCXqOIL3_CwYrna1LgYlGL9A"
    private fun v(id: String, ch: String, cid: String) = Item(IKind.VIDEO, id, "t", channel = ch, channelId = cid)
    private val vids = listOf(
        v("DfSOC-fpVeM", "Foşix Erlik", fosix), v("837ZVCB3Teg", "Foşix Erlik", fosix),
        v("aaaaaaaaaaa", "Duayen TV", "UCJ34yha9ltV177cfWsTxvzA"),
    )

    @Test fun ownerOfVideosBecomesChannelRow() {
        val m = Browse.missingChannels("Foşix Erlik", vids, have = listOf("UCYd0VXugUt6t0sGGePpadwA"))
        assertEquals(listOf(fosix), m.map { it.id })
        assertEquals(IKind.CHANNEL, m[0].kind)
        assertEquals("Foşix Erlik", m[0].title)
        assertEquals(1, Browse.missingChannels("fosix erlik", vids, emptyList()).size)
        // the user's own queries (Custom Feed › Search channels uses the same bypass)
        assertEquals(listOf(fosix), Browse.missingChannels("ERLIK", vids, emptyList()).map { it.id })
        assertEquals(listOf(fosix), Browse.missingChannels("FOSIXERLIK", vids, emptyList()).map { it.id })
    }

    @Test fun notAddedWhenPresentOrUnrelated() {
        assertTrue(Browse.missingChannels("Foşix Erlik", vids, have = listOf(fosix)).isEmpty())
        assertTrue(Browse.missingChannels("gta 6", vids, emptyList()).isEmpty())
        // a short word inside a long channel name is not that channel
        assertTrue(Browse.missingChannels("tv", listOf(v("bbbbbbbbbbb", "Some Long Channel TV", "UC" + "b".repeat(22))), emptyList()).isEmpty())
    }

    @Test fun emptyFeedChannels() {
        val follows = listOf(Follow(fosix, "Foşix Erlik"), Follow("UC" + "c".repeat(22), "Foschix Erlik"), Follow("UC" + "d".repeat(22), "New"))
        val cache = FeedCache(listOf(vids[0]), mapOf(fosix to 1L, ("UC" + "c".repeat(22)) to 1L))
        assertEquals(listOf("Foschix Erlik"), cache.emptyChannels(follows).map { it.title })
    }
}
