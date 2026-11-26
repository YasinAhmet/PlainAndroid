package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    /** Captured on 2026-11-25 from /youtubei/v1/player (ANDROID_VR, "Despacito"); URLs and ids replaced. */
    private val vr by lazy { (Yt.parsePlayer(res("player_android_vr.json"), "android_vr", "kJQP7kiw5Fk") as PlayerResult.Ok).info }
    /** Same day, IOS_PRODUCER, "Me at the zoo": H.264 + AAC only, no progressive stream. */
    private val prod by lazy { (Yt.parsePlayer(res("player_ios_producer.json"), "ios_producer", "jNQXAC9IVRw") as PlayerResult.Ok).info }

    // ---- links ----

    @Test fun videoIdFromLinks() {
        val id = "dQw4w9WgXcQ"
        for (s in listOf(
            "https://www.youtube.com/watch?v=$id",
            "https://youtube.com/watch?v=$id&list=RDdQw4w9WgXcQ&start_radio=1",
            "https://m.youtube.com/watch?feature=share&v=$id",
            "https://music.youtube.com/watch?v=$id&si=abc",
            "https://youtu.be/$id?si=XyZ123",
            "youtu.be/$id",
            "https://www.youtube.com/shorts/$id?feature=share",
            "https://www.youtube.com/embed/$id",
            "https://www.youtube.com/live/$id",
            "https://www.youtube-nocookie.com/embed/$id",
            "Check this out! https://youtu.be/$id\nShared from YouTube",
            id,
        )) assertEquals(s, id, Yt.videoId(s))
    }

    @Test fun notVideoLinks() {
        assertNull(Yt.videoId("https://www.youtube.com/playlist?list=PL123"))
        assertTrue(Yt.isPlaylistOnly("https://www.youtube.com/playlist?list=PL123"))
        assertNull(Yt.videoId("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertNull(Yt.videoId("https://www.youtube.com/watch?v=short"))
        assertNull(Yt.videoId("https://youtu.be/dQw4w9WgXcQextra"))
        assertNull(Yt.videoId(""))
        assertNull(Yt.videoId(null))
    }

    // ---- player response ----

    @Test fun parsesCapturedResponse() {
        assertEquals("kJQP7kiw5Fk", vr.id)
        assertEquals("Luis Fonsi - Despacito ft. Daddy Yankee", vr.title)
        assertEquals("LuisFonsiVEVO", vr.author)
        assertEquals(282, vr.durationSec)
        assertEquals(25, vr.formats.size)
        val f18 = vr.formats.first { it.itag == 18 }
        assertTrue(f18.muxed); assertEquals(VCodec.AVC, f18.vcodec); assertEquals(360, f18.p)
        val f140 = vr.formats.first { it.itag == 140 }
        assertTrue(f140.isAac); assertFalse(f140.hasVideo); assertEquals(4557665, f140.contentLength)
        assertTrue(vr.formats.first { it.itag == 251 }.isOpus)
        assertEquals(VCodec.VP9, vr.formats.first { it.itag == 248 || it.itag == 247 }.vcodec)
        assertEquals(VCodec.AV1, vr.formats.first { it.itag == 399 }.vcodec)
        assertEquals("https://i.ytimg.com/vi/kJQP7kiw5Fk/hqdefault.jpg", vr.thumbnail)
    }

    @Test fun audioPick() {
        assertEquals(140, Pick.audio(vr.formats, AudioFmt.M4A, AudioQ.HIGH)!!.itag)
        assertEquals(139, Pick.audio(vr.formats, AudioFmt.M4A, AudioQ.LOW)!!.itag)
        assertEquals(251, Pick.audio(vr.formats, AudioFmt.OPUS, AudioQ.HIGH)!!.itag)
        assertEquals(249, Pick.audio(vr.formats, AudioFmt.OPUS, AudioQ.LOW)!!.itag)
        assertEquals("~129 kbps", Pick.audioLabel(vr.formats, AudioFmt.M4A, AudioQ.HIGH))
        // ios_producer has no Opus: the plan falls back to M4A
        assertNull(Pick.audio(prod.formats, AudioFmt.OPUS, AudioQ.HIGH))
        val (plan, fmt) = Pick.planAudio(prod.formats, AudioFmt.OPUS, AudioQ.HIGH)!!
        assertEquals(AudioFmt.M4A, fmt); assertEquals(140, plan.single!!.itag)
    }

    @Test fun videoChoicesAvcOnly() {
        val c = Pick.videoChoices(vr.formats, 34)
        assertEquals(listOf(1080, 720, 480, 360, 240, 144), c.map { it.p })
        assertTrue(c.all { it.codec == VCodec.AVC })
        // 360p has a progressive stream: used as-is
        assertEquals(18, Pick.planVideo(vr.formats, 360, VCodec.AVC)!!.single!!.itag)
        // 1080p: H.264 video-only + AAC, merged
        val p = Pick.planVideo(vr.formats, 1080, VCodec.AVC)!!
        assertEquals(137, p.video!!.itag); assertEquals(140, p.audio!!.itag); assertNull(p.single)
        assertEquals(116495956L + 4557665L, p.estBytes)
        assertEquals(c[0].estBytes, p.estBytes)
    }

    @Test fun videoChoicesWithoutProgressive() {
        val c = Pick.videoChoices(prod.formats, 34)
        assertEquals(listOf(240, 144), c.map { it.p })
        val p = Pick.planVideo(prod.formats, 240, VCodec.AVC)!!
        assertEquals(VCodec.AVC, p.video!!.vcodec); assertEquals(140, p.audio!!.itag)
    }

    private fun fmt(itag: Int, mime: String, codecs: String, p: Int = 0, fps: Int = 30, br: Int = 1000, drc: Boolean = false, def: Boolean? = null) =
        Format(itag, "u$itag", mime, codecs, br, br, 1000L * itag, p * 16 / 9, p, fps, if (p > 0) "${p}p" else "", 44100, 1000, drc, def)

    @Test fun av1OnlyAboveBestAvcAndOnlyOnAndroid14() {
        val fs = listOf(
            fmt(137, "video/mp4", "avc1.640028", 1080),
            fmt(400, "video/mp4", "av01.0.12M.08", 1440),
            fmt(399, "video/mp4", "av01.0.08M.08", 1080),
            fmt(271, "video/webm", "vp9", 1440),
            fmt(313, "video/webm", "vp9", 2160),
            fmt(140, "audio/mp4", "mp4a.40.2"),
        )
        assertEquals(listOf("1440p · AV1", "1080p"), Pick.videoChoices(fs, 34).map { it.label })
        assertEquals(listOf("1080p"), Pick.videoChoices(fs, 33).map { it.label })
        assertEquals(400, Pick.planVideo(fs, 1440, VCodec.AV1)!!.video!!.itag)
        assertNull(Pick.planVideo(fs, 2160, VCodec.VP9)) // VP9 can't go into MP4 with MediaMuxer
    }

    @Test fun prefersHigherFpsAndLabelsIt() {
        val fs = listOf(
            fmt(136, "video/mp4", "avc1.4d401f", 720, 30, 2000),
            fmt(298, "video/mp4", "avc1.4d4020", 720, 60, 3000),
            fmt(140, "audio/mp4", "mp4a.40.2"),
        )
        val c = Pick.videoChoices(fs, 34)
        assertEquals(listOf("720p60"), c.map { it.label })
        assertEquals(298, Pick.planVideo(fs, 720, VCodec.AVC)!!.video!!.itag)
    }

    @Test fun noAudioMeansNoMergedVideo() {
        val fs = listOf(fmt(137, "video/mp4", "avc1.640028", 1080))
        assertTrue(Pick.videoChoices(fs, 34).isEmpty())
    }

    @Test fun dropsDrcAndDubbedTracks() {
        val fs = listOf(
            fmt(140, "audio/mp4", "mp4a.40.2", br = 128000, drc = true),
            fmt(140, "audio/mp4", "mp4a.40.2", br = 128000, def = false),
            fmt(140, "audio/mp4", "mp4a.40.2", br = 127000, def = true),
            fmt(139, "audio/mp4", "mp4a.40.5", br = 48000, def = true),
        )
        val c = Pick.audioCandidates(fs, AudioFmt.M4A)
        assertEquals(listOf(140, 139), c.map { it.itag })
        assertEquals(127000, c[0].bitrate)
    }

    @Test fun closestHeight() {
        val c = listOf(VideoChoice(1080, VCodec.AVC, 30, 0), VideoChoice(720, VCodec.AVC, 30, 0), VideoChoice(360, VCodec.AVC, 30, 0))
        assertEquals(720, Pick.closest(c, 720)!!.p)
        assertEquals(360, Pick.closest(c, 480)!!.p)
        assertEquals(360, Pick.closest(c, 144)!!.p)
        assertEquals(1080, Pick.closest(c, 4320)!!.p)
        assertNull(Pick.closest(emptyList(), 720))
    }

    // ---- errors ----

    private fun status(status: String, reason: String, extra: String = "") =
        """{"responseContext":{"visitorData":"VISITOR"},"playabilityStatus":{"status":"$status","reason":"$reason"}$extra}"""

    private fun fail(json: String) = Yt.parsePlayer(json, "visionos", "abcdefghijk") as PlayerResult.Fail

    @Test fun botCheckIsRetriedWithVisitorData() {
        val f = fail(status("LOGIN_REQUIRED", "Sign in to confirm you’re not a bot"))
        assertTrue(f.error.tryOther)
        assertEquals("VISITOR", f.visitorData)
    }

    @Test fun finalErrors() {
        val age = fail(status("LOGIN_REQUIRED", "Sign in to confirm your age"))
        assertFalse(age.error.tryOther); assertTrue(age.error.message!!.startsWith("Age-restricted"))
        assertFalse(fail(status("AGE_CHECK_REQUIRED", "")).error.tryOther)
        val priv = fail(status("LOGIN_REQUIRED", "This video is private"))
        assertFalse(priv.error.tryOther); assertEquals("Private video", priv.error.message)
        val mem = fail(status("UNPLAYABLE", "Join this channel to get access to members-only content like this video, and other exclusive perks."))
        assertFalse(mem.error.tryOther)
        val live = Yt.parsePlayer(
            """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","isLive":true},"streamingData":{"hlsManifestUrl":"x"}}""",
            "visionos", "abcdefghijk"
        ) as PlayerResult.Fail
        assertFalse(live.error.tryOther)
    }

    @Test fun unavailableTriesOtherClient() {
        val f = fail(status("ERROR", "This video is unavailable"))
        assertTrue(f.error.tryOther); assertEquals("This video is unavailable", f.error.message)
        // reason only in errorScreen
        val es = fail("""{"playabilityStatus":{"status":"UNPLAYABLE","errorScreen":{"playerErrorMessageRenderer":{"reason":{"runs":[{"text":"Video "},{"text":"unavailable"}]}}}}}""")
        assertEquals("Video unavailable", es.error.message)
    }

    @Test fun cipheredOnlyIsNotUsable() {
        val json = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"abcdefghijk","title":"T","author":"A"},
            "streamingData":{"adaptiveFormats":[{"itag":140,"mimeType":"audio/mp4; codecs=\"mp4a.40.2\"","signatureCipher":"s=x&url=y"}]}}"""
        val f = fail(json)
        assertTrue(f.error.tryOther)
    }

    @Test fun placeholderVideoIsRejected() {
        val json = """{"playabilityStatus":{"status":"OK"},"videoDetails":{"videoId":"zzzzzzzzzzz"},"streamingData":{"formats":[]}}"""
        assertTrue(fail(json).error.tryOther)
    }

    @Test fun garbageIsAFailure() {
        assertTrue(Yt.parsePlayer("<html>", "visionos") is PlayerResult.Fail)
    }

    // ---- request ----

    @Test fun playerBody() {
        val b = JSONObject(Clients.playerBody(Clients.VISIONOS, "dQw4w9WgXcQ", null))
        val c = b.getJSONObject("context").getJSONObject("client")
        assertEquals("VISIONOS", c.getString("clientName"))
        assertEquals("1.02", c.getString("clientVersion"))
        assertFalse(c.has("visitorData"))
        assertEquals("dQw4w9WgXcQ", b.getString("videoId"))
        assertTrue(b.getBoolean("contentCheckOk"))
        val withV = JSONObject(Clients.playerBody(Clients.ANDROID_VR, "dQw4w9WgXcQ", "VIS"))
        assertEquals("VIS", withV.getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertEquals(32, withV.getJSONObject("context").getJSONObject("client").getInt("androidSdkVersion"))
        val h = Clients.playerHeaders(Clients.IOS_PRODUCER, "VIS")
        assertEquals("92", h["X-YouTube-Client-Name"]); assertEquals("VIS", h["X-Goog-Visitor-Id"])
        assertNull(Clients.playerHeaders(Clients.IOS_PRODUCER, null)["X-Goog-Visitor-Id"])
    }

    // ---- naming ----

    @Test fun channelFolder() {
        assertEquals("Rick Astley", Naming.channel("Rick Astley - Topic"))
        assertEquals("LuisFonsiVEVO", Naming.channel("LuisFonsiVEVO"))
        assertEquals("AC-DC", Naming.channel("AC/DC - Topic"))
        assertEquals("Unknown", Naming.channel(""))
        assertEquals("Unknown", Naming.channel(null))
        assertEquals("Unknown", Naming.channel("..."))
    }

    @Test fun sanitize() {
        assertEquals("Title - Sub", Naming.sanitize("Title: Sub"))
        assertEquals("12-30", Naming.sanitize("12:30"))
        assertEquals("What", Naming.sanitize("What?*"))
        assertEquals("'Quoted' (x) a-b-c", Naming.sanitize("\"Quoted\" <x> a/b\\c"))
        assertEquals("a b", Naming.sanitize("a\n\tb"))
        assertEquals("hidden", Naming.sanitize(".hidden"))
        assertEquals("dots", Naming.sanitize("dots..."))
        assertEquals("Tanrı Şarkısı 🎵", Naming.sanitize("Tanrı Şarkısı 🎵"))
        assertEquals("Untitled", Naming.title("???"))
    }

    @Test fun truncatesByUtf8Bytes() {
        val long = "ş".repeat(100) // 200 bytes
        val t = Naming.sanitize(long)
        assertTrue(t.toByteArray(Charsets.UTF_8).size <= 120)
        assertEquals(60, t.length)
        val emoji = "a" + "🎵".repeat(40) // must not cut a surrogate pair
        val e = Naming.truncateUtf8(emoji, 10)
        assertEquals("a🎵🎵", e)
    }

    @Test fun uniqueNames() {
        assertEquals("Song.m4a", Naming.unique("Song", "m4a", emptyList()))
        assertEquals("Song (2).m4a", Naming.unique("Song", "m4a", listOf("song.M4A")))
        assertEquals("Song (3).m4a", Naming.unique("Song", "m4a", listOf("Song.m4a", "Song (2).m4a")))
        assertEquals("Song.mp4", Naming.unique("Song", "mp4", listOf("Song.m4a")))
    }

    @Test fun layoutContract() {
        assertEquals("Music/PlainDownload/Rick Astley/", Naming.relativePath(Kind.AUDIO, Naming.channel("Rick Astley - Topic")))
        assertEquals("Movies/PlainDownload/LuisFonsiVEVO/", Naming.relativePath(Kind.VIDEO, "LuisFonsiVEVO"))
        assertEquals("m4a", AudioFmt.M4A.ext); assertEquals("audio/mp4", AudioFmt.M4A.mime)
    }

    // ---- history ----

    @Test fun jobsRoundTrip() {
        val a = Job(1, "dQw4w9WgXcQ", "T", "C", 213, Kind.VIDEO, AudioFmt.M4A, AudioQ.HIGH, 1080, VCodec.AVC,
            state = State.DONE, uri = "content://media/x/1", fileName = "T.mp4", relPath = "Movies/PlainDownload/C/", done = 5, total = 5)
        val b = Job(2, "jNQXAC9IVRw", "Me at the zoo", "jawed", 19, Kind.AUDIO, AudioFmt.OPUS, AudioQ.LOW, 0, VCodec.AVC,
            state = State.RUNNING, error = null)
        val c = Job(3, "kJQP7kiw5Fk", "D", "L", 282, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC, state = State.FAILED, error = "HTTP 403")
        val back = JobCodec.decode(JobCodec.encode(listOf(a, b, c)))
        assertEquals(listOf(a, b, c), back)
        JobCodec.afterRestart(back)
        assertEquals(State.DONE, back[0].state)
        assertEquals(State.FAILED, back[1].state); assertEquals("Interrupted", back[1].error)
        assertEquals("HTTP 403", back[2].error)
        assertTrue(JobCodec.decode("""[{"id":1},{"bad":true}]""").isEmpty())
    }

    @Test fun textHelpers() {
        assertEquals(4557665, Text.totalFromContentRange("bytes 0-99999/4557665"))
        assertEquals(-1, Text.totalFromContentRange("bytes 0-99999/*"))
        assertEquals(-1, Text.totalFromContentRange(null))
        assertEquals("4.6 MB", Text.size(4557665))
        assertEquals("309 KB", Text.size(309288))
        assertEquals("3:33", Text.duration(213))
        assertEquals("1:01:01", Text.duration(3661))
        assertNotNull(Text.size(-1))
    }
}
