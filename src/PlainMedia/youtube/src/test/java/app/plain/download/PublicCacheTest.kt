package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** 1.3: folder contract (Cached tree, albums), album sources, country-block workaround, published cache entries. */
class PublicCacheTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun cachedChannelNameNeverClashes() {
        assertEquals("Cached (channel)", Naming.channel("Cached"))
        assertEquals("cached (channel)", Naming.channel("cached"))
        assertEquals("CACHED (channel)", Naming.channel("CACHED - Topic"))
        assertEquals("Cached Music", Naming.channel("Cached Music"))
        assertEquals("Music/PlainDownload/Cached (channel)/", Naming.relativePath(Kind.AUDIO, Naming.channel("Cached")))
    }

    @Test fun folderContract() {
        assertEquals("Music/PlainDownload/NASA/", Naming.relativePath(Kind.AUDIO, "NASA"))
        assertEquals("Music/PlainDownload/Luis Fonsi/Despacito/", Naming.relativePath(Kind.AUDIO, "Luis Fonsi", "Despacito"))
        assertEquals("Music/PlainDownload/Cached/Luis Fonsi/Despacito/", Naming.relativePath(Kind.AUDIO, "Luis Fonsi", "Despacito", cached = true))
        assertEquals("Music/PlainDownload/Cached/Luis Fonsi/", Naming.relativePath(Kind.AUDIO, "Luis Fonsi", "  ", cached = true))
        // video: never an album level
        assertEquals("Movies/PlainDownload/NASA/", Naming.relativePath(Kind.VIDEO, "NASA", "Some playlist"))
        assertEquals("Movies/PlainDownload/Cached/NASA/", Naming.relativePath(Kind.VIDEO, "NASA", null, cached = true))
        assertEquals("AC-DC - Back in Black", Naming.album("AC/DC: Back in Black")?.replace(" -", " -"))
        assertNull(Naming.album("???")); assertNull(Naming.album(null))
    }

    @Test fun albumSources() {
        assertEquals("Despacito", Albums.fromRemixNext(res("remix_next_FXovf5dsRTw.json"), "FXovf5dsRTw")) // ATV track
        assertNull(Albums.fromRemixNext(res("remix_next_kJQP7kiw5Fk.json"), "kJQP7kiw5Fk")) // official video: no album
        assertNull(Albums.fromRemixNext(res("remix_next_FXovf5dsRTw.json"), "otherVideoX"))
        assertNull(Albums.fromRemixNext("<html>", "x"))
        // music track: its album wins; otherwise the playlist it came from; never automatic lists
        assertEquals("Despacito", Albums.pick("Despacito", true, "Road trip"))
        assertEquals("Road trip", Albums.pick("Despacito", false, "Road trip"))
        assertEquals("Road trip", Albums.pick(null, true, "Road trip"))
        assertNull(Albums.pick(null, false, "Uploads from NASA"))
        assertNull(Albums.pick(null, false, "Mix - Lofi"))
        assertNull(Albums.pick(null, false, null))
        assertEquals("Hits 2026 - Vol. 1", Albums.pick(null, false, "Hits 2026: Vol. 1"))
    }

    @Test fun countryBlock() {
        val msg = "This content is not available on this country domain due to a legal complaint from the government."
        val e = Yt.classify("UNPLAYABLE", msg)
        assertTrue(e.geo); assertFalse(e.tryOther)
        assertTrue(Yt.classify("ERROR", msg).geo)
        assertTrue(Yt.classify("UNPLAYABLE", "The uploader has not made this video available in your country").geo)
        assertFalse(Yt.classify("ERROR", "Video unavailable").geo)
        assertFalse(Yt.classify("LOGIN_REQUIRED", "Sign in to confirm your age").geo)
        val f = Yt.parsePlayer("""{"playabilityStatus":{"status":"UNPLAYABLE","reason":"$msg"}}""", "visionos", "9d15OsTjApU") as PlayerResult.Fail
        assertTrue(f.error.geo)
        // the workaround: same client, content region in the request
        val c = JSONObject(Clients.playerBody(Clients.VISIONOS, "9d15OsTjApU", null, "US")).getJSONObject("context").getJSONObject("client")
        assertEquals("US", c.getString("gl"))
        assertFalse(JSONObject(Clients.playerBody(Clients.VISIONOS, "x", null)).getJSONObject("context").getJSONObject("client").has("gl"))
    }

    @Test fun publishedEntries() {
        val m = CacheMeta(CacheKind.MUSIC, "FXovf5dsRTw", 140, "Despacito", "Luis Fonsi - Topic", 229, total = 100, ext = "m4a", album = "Despacito")
        assertFalse(m.complete)
        m.publicUri = "content://media/external/audio/media/5"; m.relPath = "Music/PlainDownload/Cached/Luis Fonsi/Despacito/"; m.fileName = "Despacito.m4a"
        assertTrue(m.published); assertTrue(m.complete); assertEquals(1f, m.fraction)
        val back = CacheMeta.decode(m.encode())!!
        assertEquals("Despacito", back.album); assertEquals(m.publicUri, back.publicUri); assertEquals(m.relPath, back.relPath)
        assertEquals("Despacito.m4a", back.fileName); assertTrue(back.published)

        val root = Files.createTempDirectory("pdpub").toFile()
        try {
            val s = CacheStore(root)
            val deleted = ArrayList<String>()
            s.publicDeleter = { deleted += it.key; true }
            val v = CacheMeta(CacheKind.VIDEO, "abcdefghijk", 720, "T", "C", 1, total = 3)
            s.save(m); s.save(v)
            s.remove(CacheKind.VIDEO, v.key) // private only: no public delete
            assertTrue(deleted.isEmpty())
            s.forget(CacheKind.MUSIC, m.key) // moved by Save: index entry only
            assertTrue(deleted.isEmpty()); assertTrue(s.list(CacheKind.MUSIC).isEmpty())
            s.save(m)
            s.clear(CacheKind.MUSIC) // Clear deletes the public file too
            assertEquals(listOf(m.key), deleted)
        } finally { root.deleteRecursively() }
    }

    @Test fun jobAlbumRoundTrip() {
        val j = Job(1, "FXovf5dsRTw", "Despacito", "Luis Fonsi", 229, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC, album = "Despacito")
        assertEquals("Despacito", JobCodec.decode(JobCodec.encode(listOf(j)))[0].album)
        assertNull(JobCodec.decode(JobCodec.encode(listOf(j.copy(album = null))))[0].album)
    }
}
