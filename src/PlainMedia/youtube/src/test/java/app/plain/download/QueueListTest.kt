package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.4: region fallback detection for browse answers, similar channel names, local lists, the play queue. */
class QueueListTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun regionFallbackDetection() {
        // captured 2026-11-26 from a Turkish IP: "@FosixErlik" -> UCFQWm4ff-_CKSits6qNxdoA, Videos tab = empty state
        val empty = JSONObject(res("browse_empty_channel.json"))
        assertEquals("This channel doesn't have any content", Browse.emptyChannel(empty))
        assertTrue(Browse.regionBlocked(empty))
        assertTrue(Browse.page(empty).items.isEmpty())
        assertEquals("Foschix Erlik", Browse.channelHead(empty)!!.title)
        // a normal channel page is not
        val ok = JSONObject(res("channel_videos.json"))
        assertNull(Browse.emptyChannel(ok)); assertFalse(Browse.regionBlocked(ok))
        assertFalse(Browse.regionBlocked(JSONObject(res("search_videos.json"))))
        // alerts / errors
        assertTrue(Browse.regionBlocked(JSONObject("""{"alerts":[{"alertRenderer":{"type":"ERROR","text":{"simpleText":"This channel is not available in your country."}}}]}""")))
        assertTrue(Browse.regionBlocked(JSONObject("""{"alerts":[{"alertRenderer":{"type":"ERROR","text":{"runs":[{"text":"Blocked due to a legal complaint"}]}}}]}""")))
        assertTrue(Browse.regionBlocked(JSONObject("""{"error":{"code":400,"message":"Precondition check failed."}}""")))
        assertFalse(Browse.regionBlocked(JSONObject("""{"alerts":[{"alertRenderer":{"type":"ERROR","text":{"simpleText":"The playlist does not exist."}}}]}""")))
        // the region the fallback used rides along in the answer for continuations
        assertEquals("US", JSONObject().put("_gl", "US").optString("_gl"))
        val body = JSONObject(Clients.webBody(mapOf("browseId" to "UCx"), null, "US"))
        assertEquals("US", body.getJSONObject("context").getJSONObject("client").getString("gl"))
    }

    @Test fun similarChannelNames() {
        assertEquals("fosixerlik", Browse.fold("Foşix Erlik"))
        assertEquals("isik", Browse.fold("Işık"))
        assertTrue(Browse.similarName("FosixErlik", "Foşix Erlik"))
        assertTrue(Browse.similarName("Foschix Erlik", "Foşix Erlik"))
        assertTrue(Browse.similarName("NASA", "NASA Johnson"))
        assertFalse(Browse.similarName("Foschix Erlik", "Emirhan Takva"))
        assertFalse(Browse.similarName("abc", "xyz"))
        assertFalse(Browse.similarName("", "x"))
    }

    private fun v(id: String) = Item(IKind.VIDEO, id.padEnd(11, 'x'), "T$id", "C", "UC0123456789012345678901", 60, thumbId = id.padEnd(11, 'x'))

    @Test fun lists() {
        var (all, a) = VideoLists.create(emptyList(), "  Road trip ", 1)
        assertEquals("Road trip", a.name); assertEquals("l1", a.id)
        val (all2, b) = VideoLists.create(all, "", 2)
        all = all2
        assertEquals("List", b.name)
        var r = VideoLists.add(all, a.id, listOf(v("a"), v("b"), v("a"), Item(IKind.PLAYLIST, "PLx", "p")))
        all = r.first
        assertEquals(2, r.second)
        r = VideoLists.add(all, a.id, listOf(v("b"), v("c")))
        all = r.first
        assertEquals(1, r.second)
        assertEquals(listOf("axxxxxxxxxx", "bxxxxxxxxxx", "cxxxxxxxxxx"), all.first { it.id == a.id }.items.map { it.id })
        assertTrue(all.first { it.id == b.id }.items.isEmpty())
        all = VideoLists.removeItem(all, a.id, "bxxxxxxxxxx")
        all = VideoLists.rename(all, a.id, "Hike")
        all = VideoLists.rename(all, b.id, "  ")
        assertEquals(listOf("Hike", "List"), all.map { it.name })
        val back = VideoLists.decode(VideoLists.encode(all))
        assertEquals(all, back)
        assertEquals(listOf(b.id), VideoLists.delete(all, a.id).map { it.id })
        assertTrue(VideoLists.decode("nope").isEmpty())
        // a list's name is the album folder for audio saved from it
        assertEquals("Hike", Albums.pick(null, false, back[0].name))
    }

    private fun e(id: String, m: PlayMode? = null) = QEntry(id, "T$id", "C", mode = m)

    @Test fun queueOps() {
        var q = PlayQueue().append(listOf(e("a"), e("b")))
        assertEquals("a", q.current!!.id); assertFalse(q.hasPrev); assertTrue(q.hasNext)
        q = q.next().append(listOf(e("c")))
        assertEquals("b", q.current!!.id)
        q = q.next()
        assertEquals("c", q.current!!.id); assertFalse(q.hasNext)
        assertEquals(q, q.next()) // end stays
        q = q.prev()
        assertEquals("b", q.current!!.id)
        // move keeps the playing entry playing
        q = q.move(1, -1)
        assertEquals(listOf("b", "a", "c"), q.items.map { it.id }); assertEquals("b", q.current!!.id); assertEquals(0, q.index)
        q = q.move(2, 1) // out of range: unchanged
        assertEquals(listOf("b", "a", "c"), q.items.map { it.id })
        q = q.move(1, 1)
        assertEquals(listOf("b", "c", "a"), q.items.map { it.id }); assertEquals("b", q.current!!.id)
        // remove before / at / after the current entry
        q = q.jump(1)
        assertEquals("c", q.current!!.id)
        q = q.remove(0)
        assertEquals(listOf("c", "a"), q.items.map { it.id }); assertEquals("c", q.current!!.id)
        q = q.remove(0)
        assertEquals("a", q.current!!.id)
        q = q.remove(0)
        assertNull(q.current); assertTrue(q.items.isEmpty())
        // replace (Play all / Listen all), per-entry mode, round trip
        q = PlayQueue().replace(listOf(e("x", PlayMode.LISTEN), e("y")), 1).setMode(1, PlayMode.WATCH)
        assertEquals("y", q.current!!.id); assertEquals(PlayMode.WATCH, q.current!!.mode)
        val withLocal = q.append(listOf(QEntry("z", "Z", "C", localUri = "content://media/1", localAudio = true, album = "Hike")))
        assertEquals(withLocal, PlayQueue.decode(withLocal.encode()))
        assertEquals(PlayQueue(), PlayQueue.decode("{"))
        assertEquals(0, PlayQueue().replace(emptyList(), 5).index)
        assertEquals("Hike", PlayQueue.of(v("q"), null, "Hike").album)
    }

    @Test fun cacheJobs() {
        val j = Job(1, "abcdefghijk", "T", "", 0, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 720, VCodec.AVC, cache = true, album = "Hike")
        val back = JobCodec.decode(JobCodec.encode(listOf(j)))[0]
        assertTrue(back.cache); assertEquals("Cache · music", back.what)
        assertEquals("Cache · video 720p", j.copy(kind = Kind.VIDEO).what)
        assertFalse(JobCodec.decode(JobCodec.encode(listOf(j.copy(cache = false))))[0].cache)
    }
}
