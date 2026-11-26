package app.plain.download

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

/** Player/cache logic. HLS fixtures: an IOS_PRODUCER master + one media playlist captured 2026-11-26 (scrubbed, cut). */
class PlayerLogicTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    // ---- byte / segment range bookkeeping ----

    @Test fun rangesMergeAndGaps() {
        val r = Ranges()
        r.add(10, 20); r.add(30, 40); r.add(20, 25) // touching merges
        assertEquals(listOf(10L to 25L, 30L to 40L), r.spans)
        r.add(5, 12); r.add(24, 31) // overlaps bridge everything
        assertEquals(listOf(5L to 40L), r.spans)
        r.add(50, 60); r.add(0, 1)
        assertEquals(listOf(0L to 1L, 5L to 40L, 50L to 60L), r.spans)
        assertEquals(46L, r.covered())
        assertTrue(r.contains(5)); assertFalse(r.contains(40)); assertFalse(r.contains(4))
        assertEquals(40L, r.runEnd(7)); assertEquals(45L, r.runEnd(45))
        assertEquals(50L, r.nextStart(41, 100)); assertEquals(100L, r.nextStart(55, 100)); assertEquals(30L, r.nextStart(41, 30))
        // next gap from a position, wrapping to the start
        assertEquals(40L, r.firstGap(7, 100))
        assertEquals(60L, r.firstGap(50, 100))
        assertEquals(1L, r.firstGap(0, 100))
        r.add(60, 100); r.add(40, 50)
        assertEquals(1L, r.firstGap(70, 100)) // nothing after 70: wraps to the gap at 1
        r.add(1, 5)
        assertNull(r.firstGap(30, 100))
        assertTrue(r.complete(100)); assertFalse(r.complete(101)); assertFalse(Ranges().complete(0))
        // unknown total: the position itself, or the end of its run
        assertEquals(0L, Ranges().firstGap(0, 0))
        val back = Ranges.decode(r.encode())
        assertEquals(r.spans, back.spans)
        assertTrue(Ranges.decode("garbage,1-x,5-3").spans.isEmpty())
        assertEquals(listOf(2L to 4L), Ranges.decode("2-4").spans)
    }

    @Test fun segmentBookkeeping() {
        val m = CacheMeta(CacheKind.VIDEO, "abcdefghijk", 720, "T", "C", 300, total = 4)
        m.done.add(0, 4)
        assertFalse(m.complete) // audio segments missing
        assertEquals(0.5f, m.fraction)
        m.aDone.add(0, 2); m.aDone.add(2, 4)
        assertTrue(m.complete)
        val mm = CacheMeta(CacheKind.MUSIC, "abcdefghijk", 140, "T", "C", 300, total = 1000, ext = "m4a")
        mm.done.add(0, 250)
        assertEquals(0.25f, mm.fraction)
        val back = CacheMeta.decode(m.encode())!!
        assertEquals(m.key, back.key); assertEquals("abcdefghijk_720", back.key)
        assertTrue(back.complete); assertEquals(CacheKind.VIDEO, back.kind)
        assertNull(CacheMeta.decode("{}"))
    }

    // ---- LRU ----

    private fun meta(id: String, size: Long, used: Long, kind: CacheKind = CacheKind.MUSIC) =
        CacheMeta(kind, id.padEnd(11, 'x'), 140, id, "", 0, lastUsed = used, size = size)

    @Test fun lruEviction() {
        val gb = CacheLru.GB
        val l = listOf(meta("a", 6 * gb, 300), meta("b", 8 * gb, 100), meta("c", 9 * gb, 200), meta("d", 1 * gb, 400))
        // 24 GB in a 20 GB cache, plenty free: drop the least recently used (b) only
        assertEquals(listOf("b"), CacheLru.victims(l, 20 * gb, 100 * gb, null).map { it.title })
        // b is playing: c goes instead
        assertEquals(listOf("c"), CacheLru.victims(l, 20 * gb, 100 * gb, l[1].key).map { it.title })
        // under the cap but only 1 GB free: free up until 3 GB are free
        assertEquals(listOf("b"), CacheLru.victims(l, 50 * gb, 1 * gb, null).map { it.title })
        assertEquals(listOf("b", "c"), CacheLru.victims(l, 10 * gb, 100 * gb, null).map { it.title })
        assertTrue(CacheLru.victims(l, 50 * gb, 10 * gb, null).isEmpty())
        // nothing evictable but the playing one: nothing
        assertTrue(CacheLru.victims(listOf(l[0]), 1, 0, l[0].key).isEmpty())
    }

    @Test fun storeOnDisk() {
        val root = Files.createTempDirectory("pdcache").toFile()
        try {
            val s = CacheStore(root)
            val gb = CacheLru.GB
            val a = meta("a", 2 * gb, 1).also { it.done.add(0, 10) }
            val b = meta("b", 3 * gb, 2)
            val v = meta("v", 1, 3, CacheKind.VIDEO)
            s.save(a); s.save(b); s.save(v)
            assertEquals(setOf("a", "b"), s.list(CacheKind.MUSIC).map { it.title }.toSet())
            assertEquals(1, s.list(CacheKind.VIDEO).size)
            assertEquals(5 * gb, s.size(CacheKind.MUSIC))
            assertEquals("0-10", s.get(CacheKind.MUSIC, a.key)!!.done.encode())
            assertTrue(java.io.File(s.entryDir(CacheKind.MUSIC, a.key), "meta.json").exists())
            // separate caps per kind: trimming music to 4 GB drops the older "a", video untouched
            if (root.usableSpace > CacheLru.MIN_FREE) {
                s.trim(CacheKind.MUSIC, 4 * gb, null)
                assertEquals(listOf("b"), s.list(CacheKind.MUSIC).map { it.title })
                assertEquals(1, s.list(CacheKind.VIDEO).size)
            }
            s.clear(CacheKind.MUSIC)
            assertTrue(s.list(CacheKind.MUSIC).isEmpty())
            s.remove(CacheKind.VIDEO, v.key)
            assertTrue(s.list(CacheKind.VIDEO).isEmpty())
        } finally { root.deleteRecursively() }
    }

    // ---- watch / listen decision ----

    @Test fun defaultMode() {
        assertEquals(PlayMode.LISTEN, Modes.decide(null, "MUSIC_VIDEO_TYPE_ATV", null, "Luis Fonsi", false))
        assertEquals(PlayMode.LISTEN, Modes.decide(null, null, null, "Luis Fonsi - Topic", false))
        assertEquals(PlayMode.LISTEN, Modes.decide(null, null, null, "x", true)) // music.youtube.com link
        assertEquals(PlayMode.LISTEN, Modes.decide(null, "MUSIC_VIDEO_TYPE_OMV", "Music", "LuisFonsiVEVO", false))
        assertEquals(PlayMode.WATCH, Modes.decide(null, null, "Film & Animation", "jawed", false))
        assertEquals(PlayMode.WATCH, Modes.decide(null, null, null, null, false))
        // the user's choice for this video wins
        assertEquals(PlayMode.WATCH, Modes.decide(PlayMode.WATCH, "MUSIC_VIDEO_TYPE_ATV", "Music", "A - Topic", true))
        assertEquals(PlayMode.LISTEN, Modes.decide(PlayMode.LISTEN, null, "Gaming", "x", false))
        // fields as YouTube sends them (WEB_REMIX player / WEB player, UNPLAYABLE but with metadata)
        assertEquals("MUSIC_VIDEO_TYPE_ATV", Modes.musicVideoType("""{"playabilityStatus":{"status":"UNPLAYABLE"},"videoDetails":{"videoId":"FXovf5dsRTw","musicVideoType":"MUSIC_VIDEO_TYPE_ATV"}}"""))
        assertEquals("Music", Modes.category("""{"microformat":{"playerMicroformatRenderer":{"category":"Music"}}}"""))
        assertNull(Modes.musicVideoType("""{"videoDetails":{}}""")); assertNull(Modes.category("<html>"))
    }

    private fun fmt(itag: Int, mime: String, codecs: String, br: Int = 128000, drc: Boolean = false) =
        Format(itag, "u$itag", mime, codecs, br, br, 1000, 0, 0, 0, "", 44100, 1000, drc, null)

    @Test fun listenFormat() {
        val all = listOf(fmt(251, "audio/webm", "opus"), fmt(140, "audio/mp4", "mp4a.40.2"), fmt(139, "audio/mp4", "mp4a.40.5", 48000))
        assertEquals(140, Modes.listenFormat(all)!!.itag)
        assertEquals(251, Modes.listenFormat(all.filter { it.itag != 140 })!!.itag)
        assertEquals(139, Modes.listenFormat(listOf(fmt(139, "audio/mp4", "mp4a.40.5", 48000)))!!.itag)
        assertEquals(251, Modes.listenFormat(listOf(fmt(140, "audio/mp4", "mp4a.40.2", drc = true), fmt(251, "audio/webm", "opus")))!!.itag)
        assertNull(Modes.listenFormat(emptyList()))
    }

    // ---- HLS ----

    @Test fun hlsMaster() {
        val base = "https://manifest.googlevideo.com/api/manifest/hls_variant/x/file/index.m3u8"
        val m = Hls.parseMaster(res("hls_master.m3u8"), base)
        assertEquals(7, m.variants.size)
        assertEquals(setOf("233", "234"), m.audio.keys) // separate AAC audio renditions (HE-AAC / AAC-LC)
        assertTrue(m.audio.values.all { it.startsWith("https://manifest.googlevideo.com/") })
        val v = m.variants.first { it.height == 360 }
        assertEquals(640, v.width); assertEquals(804467L, v.bandwidth); assertEquals("avc1.4D401E,mp4a.40.2", v.codecs)
        assertEquals("234", v.audio); assertEquals(24, v.fps)
        val c = Hls.choices(m)
        assertEquals(listOf(1080, 720, 480, 360, 240, 144), c.map { it.height })
        assertEquals("234", c.first { it.height == 240 }.audio) // best bandwidth per height
        assertEquals(720, Hls.pick(c, 720)!!.height)
        assertEquals(480, Hls.pick(c, 600)!!.height)
        assertEquals(144, Hls.pick(c, 100)!!.height)
        assertNull(Hls.pick(emptyList(), 720))
        val vp9 = Hls.Master(listOf(Hls.Variant(1, 256, 144, "vp09.00.10.08,mp4a.40.5", "233", 24, "u")), mapOf("233" to "a"))
        assertTrue(Hls.choices(vp9).isEmpty()) // VP9 variants are not offered
        val attrs = Hls.attrs("#EXT-X-MEDIA:URI=\"x,y\",TYPE=AUDIO,GROUP-ID=\"233\",DEFAULT=YES")
        assertEquals("x,y", attrs["URI"]); assertEquals("AUDIO", attrs["TYPE"]); assertEquals("233", attrs["GROUP-ID"])
        assertEquals("https://h/a/b/seg1.ts", Hls.resolve("https://h/a/b/index.m3u8", "seg1.ts"))
    }

    @Test fun hlsMediaAndLocalPlaylists() {
        val segs = Hls.parseMedia(res("hls_media.m3u8"), "https://x/")
        assertEquals(6, segs.size)
        assertEquals(3.169833, segs[0].duration, 1e-9)
        assertTrue(segs.all { it.uri.contains("/videoplayback/") && it.uri.contains("/itag/230/") })
        val local = Hls.localMedia(segs, "v/")
        assertTrue(local.contains("#EXT-X-TARGETDURATION:7\n"))
        assertTrue(local.contains("#EXTINF:3.169833,\nv/0\n"))
        assertTrue(local.contains("v/5\n#EXT-X-ENDLIST"))
        assertEquals(segs.map { it.duration }, Hls.parseMedia(local, "http://127.0.0.1:5/v.m3u8").map { it.duration })
        assertEquals("http://127.0.0.1:5/v/3", Hls.parseMedia(local, "http://127.0.0.1:5/v.m3u8")[3].uri)
        val master = Hls.localMaster(804467, "avc1.4D401E,mp4a.40.2", "640x360", true)
        val pm = Hls.parseMaster(master, "http://127.0.0.1:5/m.m3u8")
        assertEquals("http://127.0.0.1:5/v.m3u8", pm.variants.single().uri)
        assertEquals("http://127.0.0.1:5/a.m3u8", pm.audio["a"])
        assertFalse(Hls.localMaster(1, "", "", false).contains("AUDIO"))
        val cached = listOf(meta("a", 1, 1, CacheKind.VIDEO).let { CacheMeta(CacheKind.VIDEO, "x", 480, "", "", 0) },
            CacheMeta(CacheKind.VIDEO, "x", 1080, "", "", 0))
        assertEquals(480, Hls.pickMeta(cached, 720)!!.quality)
        assertEquals(480, Hls.pickMeta(cached, 360)!!.quality)
    }

    @Test fun id3Strip() {
        // ID3v2.4 header with a 4-byte body (synchsafe size), then ADTS sync 0xFFF1
        val seg = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 0, 4, 1, 2, 3, 4, 0xFF.toByte(), 0xF1.toByte(), 9)
        assertEquals(14, Id3.tagLength(seg))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 9), Id3.strip(seg))
        val plain = byteArrayOf(0xFF.toByte(), 0xF1.toByte())
        assertEquals(0, Id3.tagLength(plain)); assertTrue(Id3.strip(plain) === plain)
        // synchsafe: 0x00 0x00 0x02 0x01 = 257
        val big = ByteArray(300).also { b -> "ID3".forEachIndexed { i, c -> b[i] = c.code.toByte() }; b[8] = 2; b[9] = 1 }
        assertEquals(267, Id3.tagLength(big))
    }

    // ---- proxy HTTP bits ----

    @Test fun httpParsing() {
        assertArrayEquals(longArrayOf(100, 199), Http.range("bytes=100-199"))
        assertArrayEquals(longArrayOf(5, -1), Http.range("bytes=5-"))
        assertNull(Http.range(null)); assertNull(Http.range("bytes=-500"))
        val head = Http.readHead(ByteArrayInputStream("GET /v/3 HTTP/1.1\r\nRange: bytes=0-\r\nHost: x\r\n\r\nBODY".toByteArray()))
        assertEquals(listOf("GET /v/3 HTTP/1.1", "Range: bytes=0-", "Host: x"), head)
        val out = java.io.ByteArrayOutputStream()
        Http.head(out, 206, "Partial Content", "audio/mp4", 10, "bytes 0-9/100")
        val s = out.toString("ISO-8859-1")
        assertTrue(s.startsWith("HTTP/1.1 206 Partial Content\r\n"))
        assertTrue(s.contains("Content-Range: bytes 0-9/100\r\n") && s.endsWith("\r\n\r\n"))
        assertNotNull(Http.readHead(ByteArrayInputStream(ByteArray(0))))
    }

    // ---- dubbed videos: original audio by default, other languages on request ----

    @Test fun hlsDubbedPicksOriginal() {
        // a real dubbed master (cut to ar/de/tr/en): every rendition DEFAULT=NO, alphabetical, "English - original" last
        val base = "https://manifest.googlevideo.com/api/manifest/hls_variant/x/file/index.m3u8"
        val m = Hls.parseMaster(res("hls_master_dubbed.m3u8"), base)
        assertEquals(listOf("en", "ar", "de", "tr"), m.tracks.map { it.lang })
        assertTrue(m.tracks.first().original && m.tracks.drop(1).none { it.original })
        for (g in m.audio.keys) {
            assertTrue(m.audio[g]!!.contains("acont%3Doriginal")) // not the first listed (Arabic)
            assertEquals("en", m.audioFor(g, null)!!.second)
            val tr = m.audioFor(g, "tr")!!
            assertEquals("tr", tr.second); assertTrue(tr.first.contains("lang%3Dtr"))
            assertEquals("en", m.audioFor(g, "xx")!!.second) // unknown language -> original
        }
        // single-track master: no languages, language "" (cache keys unchanged)
        val single = Hls.parseMaster(res("hls_master.m3u8"), base)
        assertTrue(single.tracks.isEmpty())
        assertEquals("" , single.audioFor("234", null)!!.second)
        assertEquals("English · original", m.tracks.first().label(java.util.Locale.ENGLISH))
        assertEquals("Turkish", m.tracks.last().label(java.util.Locale.ENGLISH))
    }

    private fun track(itag: Int, codecs: String, id: String?, orig: Boolean, def: Boolean?) =
        Format(itag, "u$itag$id", if (codecs == "opus") "audio/webm" else "audio/mp4", codecs, 128000, 128000, 1000, 0, 0, 0, "", 44100, 1000, false,
            def, id, orig)

    @Test fun dubbedFormatsPickOriginal() {
        val fs = listOf("ar.3", "de.3", "tr.3", "en.4").flatMap { id ->
            val o = id == "en.4"
            listOf(track(140, "mp4a.40.2", id, o, o), track(251, "opus", id, o, o), track(139, "mp4a.40.5", id, o, o))
        }
        assertEquals("en", Modes.listenFormat(fs)!!.audioLang)
        assertEquals(140, Modes.listenFormat(fs)!!.itag)
        assertEquals("tr", Modes.listenFormat(fs, "tr")!!.audioLang)
        assertEquals("en", Pick.audio(fs, AudioFmt.M4A, AudioQ.HIGH)!!.audioLang)
        assertEquals("de", Pick.audio(fs, AudioFmt.OPUS, AudioQ.HIGH, "de")!!.audioLang)
        assertEquals(listOf("en", "ar", "de", "tr"), AudioTracks.of(fs).map { it.lang })
        // original not marked: YouTube's default track
        val noOrig = fs.map { it.copy(audioOriginal = false) }
        assertEquals("en", Modes.listenFormat(noOrig)!!.audioLang)
        // single track: untouched
        assertTrue(AudioTracks.of(listOf(track(140, "mp4a.40.2", null, false, null))).isEmpty())
    }

    @Test fun parsePlayerAudioTrack() {
        val j = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"KrLj6nc516A","title":"T","lengthSeconds":"1"},
            "streamingData":{"adaptiveFormats":[
             {"itag":140,"url":"https://x/videoplayback?xtags=acont%3Ddubbed%3Alang%3Dar","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":1,
              "audioTrack":{"displayName":"Arabic","id":"ar.3","audioIsDefault":false}},
             {"itag":140,"url":"https://x/videoplayback?xtags=acont%3Doriginal%3Alang%3Den","mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","bitrate":1,
              "audioTrack":{"displayName":"English original","id":"en.4","audioIsDefault":true}}]}}"""
        val info = (Yt.parsePlayer(j, "visionos") as PlayerResult.Ok).info
        assertEquals(listOf("ar", "en"), info.formats.map { it.audioLang })
        assertEquals(listOf(false, true), info.formats.map { it.audioOriginal })
        assertEquals("en", Modes.listenFormat(info.formats)!!.audioLang)
    }

    @Test fun cacheMetaLanguage() {
        val old = CacheMeta(CacheKind.VIDEO, "abcdefghijk", 720, "T", "C", 1)
        assertEquals("abcdefghijk_720", old.key)
        assertTrue(old.langMatches(null)); assertFalse(old.langMatches("en"))
        val en = CacheMeta(CacheKind.VIDEO, "abcdefghijk", 720, "T", "C", 1, lang = "en", langOriginal = true, langKnown = true)
        assertEquals("abcdefghijk_720_en", en.key)
        assertTrue(en.langMatches(null)); assertTrue(en.langMatches("en")); assertFalse(en.langMatches("tr"))
        val tr = CacheMeta.decode(CacheMeta(CacheKind.MUSIC, "abcdefghijk", 140, "T", "C", 1, lang = "tr", langKnown = true).encode())!!
        assertEquals("abcdefghijk_140_tr", tr.key); assertFalse(tr.langMatches(null)); assertTrue(tr.langMatches("tr")); assertTrue(tr.langKnown)
        assertFalse(CacheMeta.decode(old.encode())!!.langKnown)
    }
}
