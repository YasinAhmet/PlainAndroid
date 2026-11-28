package app.plain.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files

/** Edge cases and stress for the 2026-11-28 robustness pass (index file, proxy ranges, cache eviction, paging, …). */
class EdgeCaseTest {

    // ---- files/downloaded.txt ----

    private fun parse(text: String): Pair<Set<String>, Map<String, String>> {
        val d = LinkedHashSet<String>(); val u = HashMap<String, String>()
        DoneIndex.parse(text, d, u)
        return d to u
    }

    @Test fun doneIndexIdsStartingWithDash() {
        // about 1 id in 64 starts with '-': such a line used to be taken for a "forget" line, so after a restart the
        // video lost its ✓ Downloaded mark and its file (a second copy could be downloaded)
        val (d, u) = parse("-x8Gh3kQbZc:A\tcontent://media/external/audio/media/41\n-abcdefghij:V\n")
        assertEquals(setOf("-x8Gh3kQbZc:A", "-abcdefghij:V"), d)
        assertEquals("content://media/external/audio/media/41", u["-x8Gh3kQbZc:A"])
        assertNull(u["-abcdefghij:V"])
    }

    @Test fun doneIndexForgetLineAndLaterLineWins() {
        val (d, u) = parse(
            "dQw4w9WgXcQ:A\tcontent://media/external/audio/media/1\n" +
                "dQw4w9WgXcQ:A\tcontent://media/external/audio/media/2\n" +
                "jNQXAC9IVRw:V\tcontent://media/external/video/media/3\n" +
                "-jNQXAC9IVRw:V\n" + // "-" + a whole key: forget the file of jNQXAC9IVRw
                "--bcdefghij:A\n" // the id "--bcdefghij" itself (11 characters): an ordinary mark
        )
        assertEquals("content://media/external/audio/media/2", u["dQw4w9WgXcQ:A"])
        assertNull(u["jNQXAC9IVRw:V"])
        assertTrue("jNQXAC9IVRw:V" in d) // the mark stays: it was downloaded once
        assertTrue("--bcdefghij:A" in d)
    }

    @Test fun doneIndexCutShortAndGarbage() {
        // killed / disk full while appending: the last line has no newline and half a uri ("…/media/1" of "…/media/1234")
        val (d, u) = parse("dQw4w9WgXcQ:A\tcontent://media/external/audio/media/7\nkJQP7kiw5Fk:A\tcontent://media/external/audio/media/1")
        assertEquals(setOf("dQw4w9WgXcQ:A"), d)
        assertNull(u["kJQP7kiw5Fk:A"])
        val (d2, u2) = parse("garbage\n\n   \nxyz\tcontent://x\nabc:A\tfile:///sdcard/x.m4a\n\u0000\u0000\u0000\nab c:V\n")
        assertEquals(setOf("abc:A"), d2) // the mark is kept; a uri that isn't content:// is not
        assertTrue(u2.isEmpty())
        assertTrue(parse("").first.isEmpty())
        assertTrue(parse("\r\n\r\n").first.isEmpty())
        // Windows line ends (a file edited on a PC) still read
        assertEquals("content://media/external/audio/media/9", parse("dQw4w9WgXcQ:A\tcontent://media/external/audio/media/9\r\n").second["dQw4w9WgXcQ:A"])
    }

    @Test fun doneIndexRoundTrip() {
        val d = linkedSetOf("dQw4w9WgXcQ:A", "-x8Gh3kQbZc:V", "jNQXAC9IVRw:A")
        val u = mapOf("dQw4w9WgXcQ:A" to "content://media/external/audio/media/1", "-x8Gh3kQbZc:V" to "content://media/external/video/media/2")
        val (d2, u2) = parse(DoneIndex.encode(d, u))
        assertEquals(d, d2); assertEquals(u, u2)
        assertTrue(parse(DoneIndex.encode(emptySet(), emptyMap())).first.isEmpty()) // "\n": everything forgotten
    }

    @Test fun doneIndexStress() {
        val sb = StringBuilder()
        val ids = (0 until 20_000).map { "-" + it.toString().padStart(10, '0') } // all start with '-'
        ids.forEachIndexed { i, id -> sb.append("$id:A\tcontent://media/external/audio/media/$i\n") }
        val t0 = System.nanoTime()
        val (d, u) = parse(sb.toString())
        assertEquals(20_000, d.size); assertEquals(20_000, u.size)
        assertTrue("parse took too long", System.nanoTime() - t0 < 5_000_000_000L)
    }

    // ---- local proxy: Range headers ----

    @Test fun proxyRanges() {
        assertArrayEquals(longArrayOf(-1, 500), Http.range("bytes=-500"))
        assertArrayEquals(longArrayOf(0, -1), Http.range("BYTES=0-"))
        assertArrayEquals(longArrayOf(0, 1), Http.range("bytes=0-1,5-6")) // only the first range of a list
        assertArrayEquals(longArrayOf(7, 9), Http.range(" bytes = 7 - 9 "))
        assertNull(Http.range("bytes=500-100")) // invalid: ignored, the whole file is sent (RFC 7233)
        assertNull(Http.range("bytes=99999999999999999999-")) // too big for a Long: used to throw
        assertNull(Http.range("bytes=-")); assertNull(Http.range("items=0-5")); assertNull(Http.range(""))
        val total = 1000L
        assertArrayEquals(longArrayOf(100, 999), Http.span(Http.range("bytes=100-")!!, total))
        assertArrayEquals(longArrayOf(100, 999), Http.span(Http.range("bytes=100-5000")!!, total)) // cut at the end
        assertArrayEquals(longArrayOf(500, 999), Http.span(Http.range("bytes=-500")!!, total))
        assertArrayEquals(longArrayOf(0, 999), Http.span(Http.range("bytes=-5000")!!, total)) // more than the file: all of it
        assertArrayEquals(longArrayOf(999, 999), Http.span(Http.range("bytes=999-999")!!, total))
        assertNull(Http.span(Http.range("bytes=1000-")!!, total)) // past the end: 416
        assertNull(Http.span(Http.range("bytes=-0")!!, total))
        assertNull(Http.span(longArrayOf(0, -1), 0))
    }

    // ---- cache eviction never touches what is open ----

    private fun m(id: String, size: Long, used: Long, kind: CacheKind = CacheKind.MUSIC) =
        CacheMeta(kind, id.padEnd(11, 'x'), 140, id, "", 0, lastUsed = used, size = size)

    @Test fun openEntriesAreNeverEvicted() {
        val gb = CacheLru.GB
        val l = listOf(m("a", 6 * gb, 300), m("b", 8 * gb, 100), m("c", 9 * gb, 200))
        // b (the oldest) is being filled by a parallel "Cache" job: c goes instead
        assertEquals(listOf("c"), CacheLru.victims(l, 20 * gb, 100 * gb, null, setOf(l[1].key)).map { it.title })
        // everything open or playing: nothing, however full
        assertTrue(CacheLru.victims(l, 1, 0, l[0].key, setOf(l[1].key, l[2].key)).isEmpty())
    }

    @Test fun clearAndTrimSkipOpenEntries() {
        val root = Files.createTempDirectory("pdbusy").toFile()
        try {
            val s = CacheStore(root)
            val a = m("a", 10, 1); val b = m("b", 10, 2); val c = m("c", 10, 3)
            s.save(a); s.save(b); s.save(c)
            s.inUse(CacheKind.MUSIC, a.key, true); s.inUse(CacheKind.MUSIC, a.key, true) // the player + a cache job
            assertTrue(s.isBusy(CacheKind.MUSIC, a.key)); assertEquals(setOf(a.key), s.busyKeys(CacheKind.MUSIC))
            assertTrue(s.busyKeys(CacheKind.VIDEO).isEmpty())
            s.trim(CacheKind.MUSIC, 0, b.key) // limit 0: all but the playing (b) and the open (a) go
            assertEquals(setOf("a", "b"), s.list(CacheKind.MUSIC).map { it.title }.toSet())
            s.clear(CacheKind.MUSIC)
            assertEquals(listOf("a"), s.list(CacheKind.MUSIC).map { it.title }) // the open one keeps its files
            s.inUse(CacheKind.MUSIC, a.key, false)
            assertTrue(s.isBusy(CacheKind.MUSIC, a.key)) // still open once
            s.inUse(CacheKind.MUSIC, a.key, false)
            assertFalse(s.isBusy(CacheKind.MUSIC, a.key))
            s.inUse(CacheKind.MUSIC, a.key, false) // an extra close never goes below zero
            s.inUse(CacheKind.MUSIC, a.key, true); assertTrue(s.isBusy(CacheKind.MUSIC, a.key)); s.inUse(CacheKind.MUSIC, a.key, false)
            s.clear(CacheKind.MUSIC)
            assertTrue(s.list(CacheKind.MUSIC).isEmpty())
        } finally { root.deleteRecursively() }
    }

    // ---- page-based lists ----

    @Test fun paging() {
        val p = 20
        assertEquals(1, Paging.count(0, p)); assertEquals(1, Paging.count(20, p)); assertEquals(2, Paging.count(21, p)); assertEquals(1, Paging.count(-3, p))
        assertEquals(0, Paging.shown(5, 0, p)) // empty list: page 1
        assertEquals(2, Paging.shown(9, 45, p)) // a page past the end (rows deleted, or asked before they arrived): the last
        assertEquals(0, Paging.shown(-1, 45, p))
        assertEquals(40 until 45, Paging.window(2, 45, p))
        assertEquals(40 until 45, Paging.window(7, 45, p))
        assertTrue(Paging.window(0, 0, p).isEmpty())
        assertEquals(0 until 20, Paging.window(0, 20, p))
        // Next: more loaded rows, or YouTube has more (once the first answer is in)
        assertTrue(Paging.hasNext(0, 21, p, token = false, loadedOnce = true))
        assertFalse(Paging.hasNext(1, 21, p, token = false, loadedOnce = true))
        assertTrue(Paging.hasNext(1, 21, p, token = true, loadedOnce = true))
        assertFalse(Paging.hasNext(0, 20, p, token = true, loadedOnce = false))
        assertFalse(Paging.hasNext(0, 20, p, token = false, loadedOnce = true)) // exactly one full page
        assertFalse(Paging.hasNext(5, 20, p, token = false, loadedOnce = true)) // a stale page index
        assertTrue(Paging.needsMore(1, 20, p, token = true, loadedOnce = true))
        assertFalse(Paging.needsMore(1, 40, p, token = true, loadedOnce = true))
        assertFalse(Paging.needsMore(1, 20, p, token = false, loadedOnce = true))
        // every size and page index: the window is inside the list, at most one page, never empty when rows exist
        for (n in 0..205) for (pg in -2..12) {
            val w = Paging.window(pg, n, p)
            assertTrue(w.first >= 0 && w.last < maxOf(n, 1) && w.count() <= p)
            if (n > 0) assertFalse(w.isEmpty())
        }
    }

    // ---- play queue shuffle, share at a time (QoL) ----

    @Test fun shuffleRestKeepsThePlayingOne() {
        val q = PlayQueue((1..30).map { QEntry("v$it", "T$it", "C") }, 4)
        val s = q.shuffleRest(kotlin.random.Random(7))
        assertEquals(q.items.take(5), s.items.take(5)); assertEquals(4, s.index); assertEquals("v5", s.current!!.id)
        assertEquals(q.items.drop(5).toSet(), s.items.drop(5).toSet())
        assertNotEquals(q.items.drop(5), s.items.drop(5))
        assertEquals(PlayQueue(), PlayQueue().shuffleRest())
        val last = PlayQueue(listOf(QEntry("a", "", ""), QEntry("b", "", "")), 1)
        assertEquals(last, last.shuffleRest())
    }

    @Test fun shareLinkAtTime() {
        assertEquals("https://youtu.be/dQw4w9WgXcQ", Hold.link("dQw4w9WgXcQ"))
        assertEquals("https://youtu.be/dQw4w9WgXcQ?t=754", Hold.link("dQw4w9WgXcQ", 754))
        assertEquals("https://youtu.be/dQw4w9WgXcQ", Hold.link("dQw4w9WgXcQ", 0))
        // the app reads such a link back (paste / share into it)
        assertEquals("dQw4w9WgXcQ", Yt.videoId("Look https://youtu.be/dQw4w9WgXcQ?t=754"))
    }

    // ---- a full disk is not a network error ----

    @Test fun writeFailuresAreStorageFull() {
        val full = object : OutputStream() { override fun write(b: Int) { throw IOException("write failed: ENOSPC (No space left on device)") } }
        val g = WriteGuard(full)
        try { g.write(ByteArray(10), 0, 10); fail() } catch (e: StorageFull) {
            assertEquals("Not enough free storage", e.message); assertTrue(e.cause is IOException)
        }
        try { g.write(1); fail() } catch (_: StorageFull) {}
        val ok = ByteArrayOutputStream()
        WriteGuard(ok).use { it.write("abc".toByteArray()) }
        assertEquals("abc", ok.toString())
    }

    // ---- file names ----

    @Test fun fileNameEdges() {
        assertEquals("Cached (channel)", Naming.channel("Cached - Topic"))
        assertEquals("CACHED (channel)", Naming.channel(" CACHED. "))
        assertEquals("Music/PlainDownload/cached (channel)/", Naming.relativePath(Kind.AUDIO, Naming.channel("cached")))
        assertEquals("İstanbul'da Işık - ıı", Naming.title("İstanbul'da Işık: ıı"))
        assertEquals("🎵🎶", Naming.title("🎵🎶"))
        assertEquals("Untitled", Naming.title(" . .. "))
        // 300 Turkish letters (2 bytes each): cut to 120 bytes, and "Title (99).m4a" stays far under 255 bytes
        val t = Naming.title("ş".repeat(300))
        assertTrue(t.toByteArray().size <= 120)
        assertTrue(Naming.unique(t, "m4a", (1..98).map { "$t ($it).m4a" } + "$t.m4a").toByteArray().size < 255)
        // a cut that lands on dots: no trailing dot (FAT and some file managers drop it)
        val dots = Naming.title("a".repeat(119) + ".." + "b".repeat(10))
        assertFalse(dots.endsWith(".")); assertEquals("a".repeat(119), dots)
        // the 501st copy of a common title is still found quickly
        val taken = (2..500).map { "Song ($it).m4a" } + "Song.m4a"
        assertEquals("Song (501).m4a", Naming.unique("Song", "m4a", taken))
    }

    // ---- InnerTube answers with missing / renamed / wrong-typed fields degrade, never throw ----

    @Test fun playerAnswersWithOddFields() {
        val odd = listOf(
            "", "null", "[]", "{", "{}",
            """{"playabilityStatus":"OK"}""",
            """{"playabilityStatus":{"status":"OK"}}""",
            """{"playabilityStatus":{"status":"OK"},"streamingData":[]}""",
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"formats":{},"adaptiveFormats":"x"}}""",
            """{"playabilityStatus":{"status":"OK"},"streamingData":{"adaptiveFormats":[null,1,"x",{},{"itag":"abc","url":""}]}}""",
            """{"playabilityStatus":{"status":"OK","reason":{"runs":5}},"videoDetails":[]}""",
            """{"playabilityStatus":{"status":"ERROR","errorScreen":{"playerErrorMessageRenderer":{"reason":{"runs":[null,{"text":7}]}}}}}""",
        )
        for (j in odd) assertTrue(j, Yt.parsePlayer(j, "visionos", "abcdefghijk") is PlayerResult.Fail)
        // numbers as strings and strings as numbers still give a usable answer
        val ok = Yt.parsePlayer(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","lengthSeconds":212,"title":""},
            "streamingData":{"adaptiveFormats":[{"itag":"140","url":"https://x/a","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"",
            "bitrate":"130000","contentLength":3400000,"audioTrack":"en"}]}}""", "visionos", "abcdefghijk")
        val info = (ok as PlayerResult.Ok).info
        assertEquals(212L, info.durationSec)
        assertEquals("abcdefghijk", info.title) // no title: the id
        assertEquals(140, info.formats.single().itag)
        assertEquals(3_400_000L, info.formats.single().contentLength)
        assertNotNull(Pick.audio(info.formats, AudioFmt.M4A, AudioQ.HIGH))
    }

    @Test fun browseOddShapes() {
        for (j in listOf("", "[]", "{}", """{"contents":5}""",
            """{"contents":[null,1,"x",[[]],{"videoRenderer":"x"},{"videoRenderer":{"videoId":5}},{"lockupViewModel":{"contentId":""}}]}""",
            """{"onResponseReceivedActions":[{"appendContinuationItemsAction":{"continuationItems":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":7}}}}]}}]}""")) {
            assertTrue(j, Browse.page(j).items.isEmpty())
        }
        assertEquals(0L, Browse.parseDuration("99999999999999999999:00:00").coerceAtLeast(0))
        assertEquals(0L, Browse.parseDuration("1:2"))
        assertNull(AgoParse.seconds("99999999999999999999 days ago")) // used to throw NumberFormatException
        assertEquals(3 * 86400L, AgoParse.seconds("Streamed 3 DAYS AGO"))
    }

    // ---- stored state written by older / killed versions ----

    @Test fun historyWithBrokenEntries() {
        val s = """[{"id":1,"videoId":"dQw4w9WgXcQ","kind":"AUDIO","state":"DONE","audioFmt":"MP3","codec":"VP8","media":"??"},
            {"id":"x","videoId":"a","kind":"AUDIO"}, null, 7, {"id":2,"videoId":"b","kind":"BOTH"},
            {"id":3,"videoId":"jNQXAC9IVRw","kind":"VIDEO","state":"RUNNING","done":"12","total":-5}]"""
        val l = JobCodec.decode(s)
        assertEquals(listOf(1L, 3L), l.map { it.id })
        assertEquals(AudioFmt.M4A, l[0].audioFmt); assertEquals(VCodec.AVC, l[0].codec); assertEquals(Media.OK, l[0].media)
        assertEquals(12L, l[1].done)
        JobCodec.afterRestart(l)
        assertEquals(State.FAILED, l[1].state)
    }

    @Test fun cacheMetaAndRangesFromBrokenFiles() {
        assertNull(CacheMeta.decode("""{"kind":"AUDIO","id":"x","q":1}""")) // unknown kind
        assertNull(CacheMeta.decode("not json"))
        val c = CacheMeta.decode("""{"kind":"MUSIC","id":"abc","q":"140","done":"0-x,5-2,10-20","total":"oops"}""")!!
        assertEquals(listOf(10L to 20L), c.done.spans); assertEquals(0L, c.total); assertFalse(c.complete); assertTrue(c.fraction == 0f)
        // a gap search from past the end wraps to the first gap
        assertEquals(100L, Ranges().apply { add(0, 100) }.firstGap(5000, 1000))
        assertNull(Ranges().apply { add(0, 1000) }.firstGap(5000, 1000))
    }

    @Test fun resumeEdges() {
        assertNull(ResumeRule.decodeFull(""))
        assertNull(ResumeRule.decodeFull(",,"))
        assertEquals(Triple(5000L, 0L, 0L), ResumeRule.decodeFull("5000,x,y"))
        assertFalse(ResumeRule.worthKeeping(-1, 600_000))
        assertFalse(ResumeRule.worthKeeping(700_000, 600_000)) // past the end (the file is shorter now)
        assertTrue(ResumeRule.keys("", null).isEmpty())
        // values another version / the other tab wrote in an unexpected shape are the first to go
        assertEquals(listOf("bad"), ResumeRule.oldest(mapOf("bad" to null, "ok" to "6000,0,5"), 1))
    }

    @Test fun hlsOddPlaylists() {
        assertTrue(Hls.parseMedia("", "https://x/").isEmpty())
        assertTrue(Hls.parseMedia("#EXTM3U\n#EXT-X-ENDLIST\n", "https://x/").isEmpty())
        val segs = Hls.parseMedia("#EXTINF:abc,\nseg0.ts\n#EXTINF:4.0\nseg1.ts\n", "https://h/p/v.m3u8")
        assertEquals(listOf(0.0, 4.0), segs.map { it.duration })
        assertEquals("https://h/p/seg0.ts", segs[0].uri)
        val m = Hls.parseMaster("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=x,RESOLUTION=bad\n#EXT-X-MEDIA:TYPE=AUDIO\n#EXT-X-STREAM-INF:CODECS=\"avc1\"\n", "https://h/m.m3u8")
        assertTrue(Hls.choices(m).isEmpty()) // nothing usable, nothing thrown
        assertNull(Hls.pick(emptyList(), 720))
        assertTrue(Hls.localMedia(emptyList(), "v/").contains("#EXT-X-ENDLIST"))
    }
}
