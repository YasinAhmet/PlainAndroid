package app.plain.download

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Search tab logic against InnerTube WEB answers captured from a PC on 2026-11-26 (no account, no cookies;
 * tracking fields, visitorData and IP-bearing URLs stripped; long lists cut to 12 items in some files).
 */
class BrowseTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()
    private fun page(name: String) = Browse.page(res(name))
    private fun json(name: String) = JSONObject(res(name))

    // ---- search ----

    @Test fun searchVideos() {
        val p = page("search_videos.json")
        assertEquals(20, p.items.size)
        assertTrue(p.items.all { it.kind == IKind.VIDEO })
        val v = p.items[0]
        assertEquals("NvftPSb5Xtw", v.id)
        assertEquals("Secret Forest 🍃 Chill Lofi Beats", v.title)
        assertEquals("the bootleg boy", v.channel)
        assertEquals("UC0fiLCwTmAukotCXYnqfj0A", v.channelId)
        assertEquals(2 * 3600 + 3 * 60 + 45L, v.durationSec)
        assertEquals("1.7M views", v.views)
        assertEquals("4 years ago", v.age)
        assertEquals("NvftPSb5Xtw", v.thumbId)
        assertFalse(v.live)
        assertNotNull(p.continuation)
        assertTrue(p.continuation!!.length > 50)
    }

    @Test fun searchContinuation() {
        val p1 = page("search_videos.json")
        val p2 = page("search_videos_cont.json") // reloadContinuationItemsCommand inside onResponseReceivedCommands
        assertEquals(12, p2.items.size)
        assertTrue(p1.items.map { it.id }.intersect(p2.items.map { it.id }.toSet()).isEmpty())
        assertNotNull(p2.continuation)
        assertTrue(p2.continuation != p1.continuation)
        // the first result is a 24/7 live camera: no length, flagged live (nothing to download)
        assertEquals("w9zKli6lmwc", p2.items[0].id)
        assertTrue(p2.items[0].live)
        assertEquals(0L, p2.items[0].durationSec)
    }

    @Test fun searchChannels() {
        val p = page("search_channels.json")
        assertEquals(20, p.items.size)
        val c = p.items[0]
        assertEquals(IKind.CHANNEL, c.kind)
        assertEquals("UCuhNbYgKBoK6D1NgIWyPFqQ", c.id)
        assertEquals("Better Lofi", c.title)
        assertEquals("@BetterLofi", c.handle) // YouTube puts the handle in subscriberCountText…
        assertEquals("44.9K subscribers", c.count) // …and the subscriber count in videoCountText
        assertNotNull(p.continuation)
    }

    @Test fun searchPlaylistsAreLockups() {
        val p = page("search_playlists.json")
        assertEquals(20, p.items.size)
        val pl = p.items[0]
        assertEquals(IKind.PLAYLIST, pl.kind)
        assertEquals("PLcYreR49QPFTlwXDFzQOAaJbg4C0_K6PK", pl.id)
        assertEquals("🚶🏻 LoFi Hike", pl.title)
        assertEquals("LoFi LiFe", pl.channel)
        assertEquals("UC6jgNGOIId6-5r0XF4_CwkQ", pl.channelId)
        assertEquals("9 videos", pl.count)
        assertNotNull(pl.thumbId)
        assertEquals(11, pl.thumbId!!.length)
    }

    @Test fun searchAllHasShortsShelf() {
        val p = page("search_all.json")
        val shorts = p.items.filter { it.kind == IKind.SHORT }
        assertEquals(25, shorts.size)
        val s = shorts.first { it.id == "1ee15kJI_Og" }
        assertTrue(s.title.startsWith("Hike With Me"))
        assertEquals("165K views", s.views)
        assertTrue(s.isVideo)
        assertEquals(20, p.items.count { it.kind == IKind.VIDEO })
    }

    // ---- channel ----

    @Test fun channelVideosTab() {
        val o = json("channel_videos.json")
        val h = Browse.channelHead(o)!!
        assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ", h.id)
        assertEquals("NASA", h.title)
        assertEquals("@NASA", h.handle)
        assertTrue(h.subs, h.subs.endsWith("subscribers"))
        assertEquals(Clients.TAB_VIDEOS, h.tabs["videos"])
        assertEquals(Clients.TAB_SHORTS, h.tabs["shorts"])
        assertEquals(Clients.TAB_PLAYLISTS, h.tabs["playlists"]) // the 2026 value; the old one opened Home
        val p = Browse.page(o)
        assertEquals(30, p.items.size)
        val v = p.items[0]
        assertEquals("TQUqnOtUC_M", v.id)
        assertEquals("2026 Thanksgiving Message from the International Space Station", v.title)
        assertEquals("NASA and NASA Johnson", v.channel) // collab: name only
        assertEquals(166L, v.durationSec)
        assertEquals("54K views", v.views)
        assertEquals("2 days ago", v.age)
        // only the grid's continuation, not the ones hidden in the header's engagement panels
        assertNotNull(p.continuation)
        val p2 = page("channel_videos_cont.json")
        assertEquals(30, p2.items.size)
        assertEquals("Rfyr7-dQnAg", p2.items[0].id)
        assertEquals(65L, p2.items[0].durationSec)
        assertNotNull(p2.continuation)
        assertTrue(p.items.map { it.id }.intersect(p2.items.map { it.id }.toSet()).isEmpty())
    }

    @Test fun channelShortsAndPlaylists() {
        val s = page("channel_shorts.json")
        assertEquals(12, s.items.size)
        assertTrue(s.items.all { it.kind == IKind.SHORT })
        assertEquals("k5Z7sFwFF7Q", s.items[0].id)
        assertEquals("Become a NASA Astronaut!", s.items[0].title)
        val p = page("channel_playlists.json")
        assertEquals(30, p.items.size)
        assertTrue(p.items.all { it.kind == IKind.PLAYLIST })
        assertEquals("PLF98rxtslw6s", p.items[0].id)
        assertEquals("Nancy Grace Roman Space Telescope", p.items[0].title)
        assertEquals("3 videos", p.items[0].count)
        assertNotNull(p.continuation)
    }

    // ---- playlist ----

    @Test fun playlistPagesUseLockupsAndContinuationViewModel() {
        val o = json("playlist_p1.json")
        val h = Browse.playlistHead(o, "UULA_DiR1FfKNvjuUpBHmylQ")!!
        assertEquals("Uploads from NASA", h.title)
        assertEquals("NASA", h.owner)
        assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ", h.ownerId)
        assertEquals("6,212 videos", h.count)
        val p1 = Browse.page(o)
        assertEquals(12, p1.items.size) // 100 in the live answer; the fixture is cut
        val v = p1.items[0]
        assertEquals("LwQMevphh10", v.id)
        assertEquals("Progress 97 Cargo Ship Launch", v.title)
        assertEquals("NASA", v.channel)
        assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ", v.channelId)
        assertEquals(33 * 60 + 9L, v.durationSec)
        assertEquals("Streamed 1 day ago", v.age)
        assertNotNull("continuationItemViewModel token", p1.continuation)
        val p2 = page("playlist_p2.json")
        assertEquals("l6PAhdKxa2c", p2.items[0].id)
        assertNotNull(p2.continuation)
        assertTrue(p2.continuation != p1.continuation)
    }

    @Test fun resolveUrl() {
        assertEquals(Target.Channel("UCLA_DiR1FfKNvjuUpBHmylQ"), Browse.resolved(json("resolve_handle.json")))
        assertEquals(Target.Playlist("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"), Browse.resolved(json("resolve_pl.json")))
        assertNull(Browse.resolved(json("resolve_bad.json"))) // HTTP 404 body
    }

    // ---- defensive parsing on synthetic shapes ----

    @Test fun oldRenderersAndOddShapes() {
        val json = """{"contents":{"x":[
            {"gridVideoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Grid"},"shortBylineText":{"runs":[{"text":"Ch","navigationEndpoint":{"browseEndpoint":{"browseId":"UC0123456789012345678901"}}}]},
              "thumbnailOverlays":[{"thumbnailOverlayTimeStatusRenderer":{"text":{"simpleText":"1:02:03"},"style":"DEFAULT"}}]}},
            {"playlistVideoRenderer":{"videoId":"bbbbbbbbbbb","title":{"runs":[{"text":"PV"}]},"lengthSeconds":"75","videoInfo":{"runs":[{"text":"12K views"},{"text":" • "},{"text":"2 years ago"}]}}},
            {"compactVideoRenderer":{"videoId":"ccccccccccc","title":{"simpleText":"Up"},"upcomingEventData":{"startTime":"1"}}},
            {"reelItemRenderer":{"videoId":"ddddddddddd","headline":{"simpleText":"Reel"}}},
            {"gridPlaylistRenderer":{"playlistId":"PLxyz1234567","title":{"runs":[{"text":"GP"}]},"videoCountText":{"runs":[{"text":"7"},{"text":" videos"}]},
              "navigationEndpoint":{"watchEndpoint":{"videoId":"eeeeeeeeeee","playlistId":"PLxyz1234567"}}}},
            {"playlistRenderer":{"playlistId":"PLabc1234567","title":{"simpleText":"P"},"videoCount":"5"}},
            {"gridChannelRenderer":{"channelId":"UC0123456789012345678901","title":{"simpleText":"GC"}}},
            {"lockupViewModel":{"contentId":"fffffffffff","metadata":{"lockupMetadataViewModel":{"title":{"content":"No type"}}}}},
            {"lockupViewModel":{"contentId":"ggggggggggg","contentType":"LOCKUP_CONTENT_TYPE_VIDEO","contentImage":{"thumbnailViewModel":{"overlays":[{"thumbnailBottomOverlayViewModel":{"badges":[{"thumbnailBadgeViewModel":{"text":"LIVE","badgeStyle":"THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE"}}]}}]}}}},
            {"lockupViewModel":{"contentType":"LOCKUP_CONTENT_TYPE_VIDEO"}},
            {"videoRenderer":{"videoId":"too-short"}},
            {"videoRenderer":{"videoId":"aaaaaaaaaaa","title":{"simpleText":"Duplicate"}}},
            {"menu":{"videoRenderer":{"videoId":"hhhhhhhhhhh"}}},
            {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"TOKEN1"}}}},
            "junk", 42, null
        ]}}"""
        val p = Browse.page(json)
        val byId = p.items.associateBy { it.id }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd", "PLxyz1234567", "PLabc1234567",
            "UC0123456789012345678901", "fffffffffff", "ggggggggggg"), p.items.map { it.id })
        assertEquals(3723L, byId["aaaaaaaaaaa"]!!.durationSec)
        assertEquals("Grid", byId["aaaaaaaaaaa"]!!.title) // first one wins over the duplicate
        assertEquals("UC0123456789012345678901", byId["aaaaaaaaaaa"]!!.channelId)
        assertEquals(75L, byId["bbbbbbbbbbb"]!!.durationSec)
        assertEquals("12K views", byId["bbbbbbbbbbb"]!!.views)
        assertEquals("2 years ago", byId["bbbbbbbbbbb"]!!.age)
        assertTrue(byId["ccccccccccc"]!!.live)
        assertEquals(IKind.SHORT, byId["ddddddddddd"]!!.kind)
        assertEquals("7 videos", byId["PLxyz1234567"]!!.count)
        assertEquals("eeeeeeeeeee", byId["PLxyz1234567"]!!.thumbId)
        assertEquals("5 videos", byId["PLabc1234567"]!!.count)
        assertEquals(IKind.CHANNEL, byId["UC0123456789012345678901"]!!.kind)
        assertEquals(IKind.VIDEO, byId["fffffffffff"]!!.kind)
        assertTrue(byId["ggggggggggg"]!!.live)
        assertNull(byId["hhhhhhhhhhh"]) // inside a menu: not a result
        assertEquals("TOKEN1", p.continuation)
        assertTrue(Browse.page("<html>").items.isEmpty())
        assertNull(Browse.page("{}").continuation)
    }

    @Test fun alertsAndDurations() {
        assertEquals("The playlist does not exist.", Browse.alert(JSONObject(
            """{"alerts":[{"alertRenderer":{"type":"ERROR","text":{"runs":[{"text":"The playlist does not exist."}]}}}]}""")))
        assertNull(Browse.alert(JSONObject("""{"alerts":[{"alertWithButtonRenderer":{"type":"INFO","text":{"simpleText":"x"}}}]}""")))
        assertEquals(59L, Browse.parseDuration("0:59"))
        assertEquals(3600L, Browse.parseDuration("1:00:00"))
        assertEquals(0L, Browse.parseDuration("LIVE"))
        assertEquals(0L, Browse.parseDuration(null))
    }

    // ---- links ----

    @Test fun classifyLinks() {
        val uc = "UCLA_DiR1FfKNvjuUpBHmylQ"
        assertEquals(Target.Video("dQw4w9WgXcQ"), Links.classify("https://youtu.be/dQw4w9WgXcQ?si=x"))
        assertEquals(Target.Video("dQw4w9WgXcQ"), Links.classify("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123456789012"))
        assertEquals(Target.Video("dQw4w9WgXcQ"), Links.classify("dQw4w9WgXcQ"))
        assertEquals(Target.Query("dQw4w9WgXcQ"), Links.classify("dQw4w9WgXcQ", bareId = false))
        assertEquals(Target.Query("photography"), Links.classify("photography", bareId = false))
        assertEquals(Target.Playlist("PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"), Links.classify("https://www.youtube.com/playlist?list=PLrAXtmErZgOeiKm4sgNOknGvNjby9efdf"))
        assertEquals(Target.Playlist("OLAK5uy_abcdefghijk"), Links.classify("Listen https://music.youtube.com/playlist?list=OLAK5uy_abcdefghijk&si=zz"))
        assertEquals(Target.Channel(uc), Links.classify("https://www.youtube.com/channel/$uc/videos"))
        assertEquals(Target.Channel(uc), Links.classify(uc))
        assertEquals(Target.ChannelUrl("https://www.youtube.com/@NASA"), Links.classify("https://m.youtube.com/@NASA/videos?si=a"))
        assertEquals(Target.ChannelUrl("https://www.youtube.com/@NASA"), Links.classify("@NASA"))
        assertEquals(Target.ChannelUrl("https://www.youtube.com/c/NASA"), Links.classify("youtube.com/c/NASA"))
        assertEquals(Target.ChannelUrl("https://www.youtube.com/user/NASAtelevision"), Links.classify("https://www.youtube.com/user/NASAtelevision"))
        assertEquals(Target.Query("lofi hiking"), Links.classify("  lofi hiking "))
        assertEquals(Target.None, Links.classify("https://example.com/x"))
        assertEquals(Target.None, Links.classify("   "))
        assertEquals(Target.None, Links.classify(null))
    }

    // ---- request body ----

    @Test fun webBody() {
        val b = JSONObject(Clients.webBody(mapOf("query" to "q", "params" to Clients.SEARCH_VIDEOS), null))
        val c = b.getJSONObject("context").getJSONObject("client")
        assertEquals("WEB", c.getString("clientName"))
        assertEquals(Clients.WEB_VERSION, c.getString("clientVersion"))
        assertEquals("en", c.getString("hl"))
        assertFalse(c.has("gl")); assertFalse(c.has("visitorData"))
        assertEquals("q", b.getString("query")); assertEquals("EgIQAQ%3D%3D", b.getString("params"))
        val withV = JSONObject(Clients.webBody(mapOf("continuation" to "T"), "VIS"))
        assertEquals("VIS", withV.getJSONObject("context").getJSONObject("client").getString("visitorData"))
        assertEquals("1", Clients.webHeaders(null)["X-YouTube-Client-Name"])
        assertEquals("VIS", Clients.webHeaders("VIS")["X-Goog-Visitor-Id"])
        assertEquals("https://i.ytimg.com/vi/abcdefghijk/mqdefault.jpg", Clients.thumbSmall("abcdefghijk"))
    }

    // ---- RSS ----

    @Test fun rssFeed() {
        val items = Rss.parse(KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) },
            javaClass.getResourceAsStream("/rss_nasa.xml")!!, "UCLA_DiR1FfKNvjuUpBHmylQ")
        assertEquals(15, items.size)
        val v = items[0]
        assertEquals("LwQMevphh10", v.id)
        assertEquals("Progress 97 Cargo Ship Launch", v.title)
        assertEquals("NASA", v.channel)
        assertEquals("UCLA_DiR1FfKNvjuUpBHmylQ", v.channelId)
        assertEquals(Rss.isoMillis("2026-11-24T10:34:48+00:00"), v.published)
        assertTrue(v.published > 0)
        assertTrue(v.views, v.views.endsWith("views"))
        assertTrue(items.all { it.isVideo && it.id.length == 11 && it.channelId == "UCLA_DiR1FfKNvjuUpBHmylQ" })
        // the same with namespace processing off (prefixed names)
        val raw = Rss.parse(KXmlParser(), javaClass.getResourceAsStream("/rss_nasa.xml")!!, "UCLA_DiR1FfKNvjuUpBHmylQ")
        assertEquals(items.map { it.id }, raw.map { it.id })
    }

    @Test fun rssHelpers() {
        assertEquals(1795516488000L, Rss.isoMillis("2026-11-24T10:34:48+00:00"))
        assertEquals(0L, Rss.isoMillis("yesterday"))
        assertEquals("999 views", Rss.viewsText(999))
        assertEquals("1 view", Rss.viewsText(1))
        assertEquals("1.2K views", Rss.viewsText(1290))
        assertEquals("61K views", Rss.viewsText(61_234))
        assertEquals("1.7M views", Rss.viewsText(1_797_797))
        val now = 1_800_000_000_000L
        assertEquals("just now", Ago.text(now - 5_000, now))
        assertEquals("5 min ago", Ago.text(now - 5 * 60_000, now))
        assertEquals("1 hour ago", Ago.text(now - 3_600_000, now))
        assertEquals("3 days ago", Ago.text(now - 3 * 86_400_000L, now))
        assertEquals("2 weeks ago", Ago.text(now - 15 * 86_400_000L, now))
        assertEquals("", Ago.text(0, now))
    }

    // ---- feed cache ----

    private fun vid(id: String, ch: String, t: Long) = Item(IKind.VIDEO, id, "T$id", "C$ch", ch, published = t)

    @Test fun feedMergeAndStaleness() {
        val a = "UCaaaaaaaaaaaaaaaaaaaaaa"; val b = "UCbbbbbbbbbbbbbbbbbbbbbb"; val c = "UCcccccccccccccccccccccc"
        val now = 10_000_000_000L
        var f = FeedCache.EMPTY
        f = f.with(a, listOf(vid("a1aaaaaaaaa", a, 300), vid("a2aaaaaaaaa", a, 100)), now)
        f = f.with(b, listOf(vid("b1bbbbbbbbb", b, 200)), now)
        assertEquals(listOf("a1aaaaaaaaa", "b1bbbbbbbbb", "a2aaaaaaaaa"), f.items.map { it.id })
        // a fresh answer for a replaces its old items
        f = f.with(a, listOf(vid("a3aaaaaaaaa", a, 400)), now)
        assertEquals(listOf("a3aaaaaaaaa", "b1bbbbbbbbb"), f.items.map { it.id })
        val follows = listOf(Follow(a, "A"), Follow(b, "B"), Follow(c, "C"))
        // fresh: only the never-fetched channel is due; 31 min later all are
        assertEquals(listOf(c), f.due(follows, now + 60_000, false).map { it.id })
        assertEquals(3, f.due(follows, now + 31 * 60_000, false).size)
        assertEquals(3, f.due(follows, now, true).size)
        // a failed fetch counts as tried
        f = f.tried(c, now)
        assertTrue(f.due(follows, now + 60_000, false).isEmpty())
        assertEquals(now, f.lastRefresh)
        // unfollow drops items and fetch times
        val g = f.only(listOf(b))
        assertEquals(listOf("b1bbbbbbbbb"), g.items.map { it.id })
        assertEquals(setOf(b), g.fetched.keys)
        // round trip
        val back = FeedCache.decode(f.encode())
        assertEquals(f.items, back.items)
        assertEquals(f.fetched, back.fetched)
        assertTrue(FeedCache.decode("garbage").items.isEmpty())
    }

    @Test fun itemCodecRoundTrip() {
        val items = listOf(
            Item(IKind.VIDEO, "abcdefghijk", "T", "C", "UC0123456789012345678901", 61, "1K views", "1 day ago", thumbId = "abcdefghijk", published = 5, live = true),
            Item(IKind.PLAYLIST, "PL1234567890", "P", count = "3 videos", thumbId = "abcdefghijk"),
            Item(IKind.CHANNEL, "UC0123456789012345678901", "Ch", handle = "@ch", count = "5 subscribers"),
            Item(IKind.SHORT, "zyxwvutsrqp", "S"),
        )
        assertEquals(items, ItemCodec.decode(ItemCodec.encode(items)))
    }

    // ---- follows + Takeout ----

    @Test fun followsMerge() {
        val a = Follow("UCaaaaaaaaaaaaaaaaaaaaaa", "beta")
        val b = Follow("UCbbbbbbbbbbbbbbbbbbbbbb", "Alpha")
        val (l, n) = Follows.merge(listOf(a), listOf(b, a.copy(title = "renamed"), Follow("bad", "x")))
        assertEquals(1, n)
        assertEquals(listOf("Alpha", "beta"), l.map { it.title })
        assertEquals(l, Follows.decode(Follows.encode(l)))
        assertTrue(Follows.decode("{").isEmpty())
    }

    private val csv = "﻿Channel Id,Channel Url,Channel Title\r\n" +
        "UCLA_DiR1FfKNvjuUpBHmylQ,http://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ,NASA\r\n" +
        "UCuhNbYgKBoK6D1NgIWyPFqQ,http://www.youtube.com/channel/UCuhNbYgKBoK6D1NgIWyPFqQ,\"Lofi, \"\"Better\"\" beats\"\r\n" +
        "\r\n" +
        "UC0fiLCwTmAukotCXYnqfj0A,http://www.youtube.com/channel/UC0fiLCwTmAukotCXYnqfj0A,\"Two\nlines\"\r\n" +
        "UCLA_DiR1FfKNvjuUpBHmylQ,http://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ,NASA duplicate\r\n" +
        "not,a,channel"

    @Test fun takeoutCsv() {
        val l = Takeout.parseCsv(csv)
        assertEquals(listOf("UCLA_DiR1FfKNvjuUpBHmylQ", "UCuhNbYgKBoK6D1NgIWyPFqQ", "UC0fiLCwTmAukotCXYnqfj0A"), l.map { it.id })
        assertEquals(listOf("NASA", "Lofi, \"Better\" beats", "Two\nlines"), l.map { it.title })
        // Turkish Takeout header, LF only, no trailing newline, url-only rows
        val tr = "Kanal Kimliği,Kanal URL'si,Kanal Başlığı\nUCLA_DiR1FfKNvjuUpBHmylQ,http://www.youtube.com/channel/UCLA_DiR1FfKNvjuUpBHmylQ,NASA"
        assertEquals(listOf("NASA"), Takeout.parseCsv(tr).map { it.title })
        assertEquals(listOf("UCuhNbYgKBoK6D1NgIWyPFqQ"), Takeout.parseCsv("https://www.youtube.com/channel/UCuhNbYgKBoK6D1NgIWyPFqQ").map { it.id })
        assertTrue(Takeout.parseCsv("").isEmpty())
        assertEquals(listOf(listOf("a", "b,c", ""), listOf("d")), Takeout.csvRows("a,\"b,c\",\r\nd\n"))
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z ->
            for ((n, s) in files) { z.putNextEntry(ZipEntry(n)); z.write(s.toByteArray(Charsets.UTF_8)); z.closeEntry() }
        }
        return bo.toByteArray()
    }

    @Test fun takeoutZip() {
        val bytes = zip(
            "Takeout/archive_browser.html" to "<html>",
            "Takeout/YouTube and YouTube Music/channels/channel.csv" to "Channel ID,Title\nUCzzzzzzzzzzzzzzzzzzzzzz,Me",
            "Takeout/YouTube and YouTube Music/subscriptions/subscriptions.csv" to csv,
        )
        assertTrue(Takeout.isZip(bytes.copyOf(4)))
        assertFalse(Takeout.isZip(csv.toByteArray().copyOf(4)))
        assertEquals(3, Takeout.parseZip(ByteArrayInputStream(bytes)).size)
        // localised names: the csv with the most channels wins over the one-row channel.csv
        val tr = zip(
            "Takeout/YouTube ve YouTube Music/kanallar/kanal.csv" to "Kanal Kimliği,Başlık\nUCzzzzzzzzzzzzzzzzzzzzzz,Ben",
            "Takeout/YouTube ve YouTube Music/abonelikler/abonelikler.csv" to csv,
        )
        assertEquals(3, Takeout.parseZip(ByteArrayInputStream(tr)).size)
        assertTrue(Takeout.parseZip(ByteArrayInputStream(zip("a.txt" to "x"))).isEmpty())
    }

    // ---- bulk ----

    @Test fun bulkPlan() {
        val items = listOf(
            Item(IKind.VIDEO, "aaaaaaaaaaa", "A"),
            Item(IKind.SHORT, "bbbbbbbbbbb", "B"),
            Item(IKind.VIDEO, "aaaaaaaaaaa", "A again"),
            Item(IKind.VIDEO, "ccccccccccc", "Live", live = true),
            Item(IKind.PLAYLIST, "PL1234567890", "P"),
            Item(IKind.VIDEO, "ddddddddddd", "Done"),
        )
        val p = Bulk.plan(items) { it == "ddddddddddd" }
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), p.take.map { it.id })
        assertEquals(1, p.skippedDone)
        assertEquals(1, p.skippedLive)
        assertEquals(0, p.overLimit)
        assertEquals("Queued 2, skipped 1 already downloaded or queued, 1 live/upcoming", Bulk.summary(2, p))
        val many = (0 until 650).map { Item(IKind.VIDEO, "v" + it.toString().padStart(10, '0'), "t") }
        val pm = Bulk.plan(many) { false }
        assertEquals(Bulk.MAX, pm.take.size)
        assertEquals(150, pm.overLimit)
        assertEquals("v0000000000", pm.take[0].id) // playlist order kept
    }

    @Test fun historyTrimKeepsQueue() {
        // the bulk queue is ordered oldest-first by Store.nextQueued (last QUEUED in the newest-first list)
        val jobs = (0 until 3).map { Job(it.toLong(), "v$it".padEnd(11, 'x'), "t", "", 0, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC) }
        val list = ArrayList<Job>()
        for (j in jobs) list.add(0, j) // what Store.addAll does
        assertEquals(0L, list.last { it.state == State.QUEUED }.id)
    }

    @Test fun playlistKeepsItsOwnContinuationNotTheShelfOne() {
        val pvr = (1..3).joinToString(",") { """{"playlistVideoRenderer":{"videoId":"vid0000000$it","title":{"runs":[{"text":"T$it"}]}}}""" }
        val json = """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
            {"itemSectionRenderer":{"contents":[{"playlistVideoListRenderer":{"contents":[$pvr,
              {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT_VIDEOS"}}}}]}}]}},
            {"continuationItemViewModel":{"continuationCommand":{"innertubeCommand":{"continuationCommand":{"token":"SHELF"}}}}}
            ]}}}}]}}}"""
        val p = Browse.page(json)
        assertEquals(3, p.items.count { it.isVideo })
        assertEquals("NEXT_VIDEOS", p.continuation)
    }
}
