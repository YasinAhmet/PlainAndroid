package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/** 2.2: search "sp" builder, client-side filters, feed filters, selection model, unfollow texts. */
class FiltersTest {
    // ---- sp ----

    /**
     * sp_expected.txt = every combination the app builds (videos: 5 dates x 4 durations x 2 sorts; channels and
     * playlists: 2 sorts), generated independently in Python and each sent to YouTube on 2026-11-26: all 44 answered
     * with results, durations inside the asked range.
     */
    @Test fun spMatchesLiveVerifiedCombinations() {
        val lines = javaClass.getResourceAsStream("/sp_expected.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() }
        assertEquals(44, lines.size)
        for (l in lines) {
            val (t, d, u, s, sp) = l.split(' ')
            val f = SearchFilters(type = t.toInt(), date = SDate.valueOf(d), dur = SDur.valueOf(u), sort = SSort.valueOf(s))
            assertEquals(l, sp, f.sp())
        }
    }

    @Test fun spDefaultsAreTheOldTypeConstants() {
        assertEquals(Clients.SEARCH_VIDEOS, SearchFilters(type = 0).sp())
        assertEquals(Clients.SEARCH_CHANNELS, SearchFilters(type = 1).sp())
        assertEquals(Clients.SEARCH_PLAYLISTS, SearchFilters(type = 2).sp())
    }

    @Test fun spDropsWhatYoutubeDoesntDo() {
        // "upload date" sort is ignored by YouTube now: no server sort, sorted locally instead
        val newest = SearchFilters(sort = SSort.NEWEST, date = SDate.WEEK)
        assertEquals(SearchFilters(date = SDate.WEEK).sp(), newest.sp())
        assertTrue(newest.client)
        // date / duration / newest are video-only
        val ch = SearchFilters(type = 1, date = SDate.TODAY, dur = SDur.LONG, sort = SSort.NEWEST)
        assertEquals(Clients.SEARCH_CHANNELS, ch.sp())
        assertFalse(ch.client)
        assertEquals("CAMSAhAD", SearchFilters(type = 2, sort = SSort.POPULAR).sp())
    }

    @Test fun spProtobufBytes() {
        // sort=3, filters{date=3, type=1, dur=2}
        assertEquals(listOf(0x08, 3, 0x12, 6, 0x08, 3, 0x10, 1, 0x18, 2), SearchParams.bytes(3, 3, 1, 2).map { it.toInt() })
        assertEquals(0, SearchParams.bytes(0, 0, 0, 0).size)
    }

    @Test fun filtersRoundTrip() {
        val f = SearchFilters(1, SDate.MONTH, SDur.MEDIUM, SSort.POPULAR, true, true, true, true, true)
        assertEquals(f, SearchFilters.decode(f.encode()))
        assertEquals(SearchFilters(), SearchFilters.decode("garbage"))
        val ff = FeedFilters(true, true, true, true, setOf("UCaaaaaaaaaaaaaaaaaaaaaa"), FAge.WEEK, FSort.CHANNEL)
        assertEquals(ff, FeedFilters.decode(ff.encode()))
        assertEquals(FeedFilters(), FeedFilters.decode(null))
    }

    // ---- client-side predicates ----

    private val vid = Item(IKind.VIDEO, "AAAAAAAAAAA", "Song", channel = "Artist - Topic", age = "3 days ago")
    private val short = Item(IKind.SHORT, "BBBBBBBBBBB", "Short", channel = "Someone", age = "2 hours ago")
    private val live = Item(IKind.VIDEO, "CCCCCCCCCCC", "Live", channel = "Someone", live = true, age = "")
    private val mem = Item(IKind.VIDEO, "DDDDDDDDDDD", "Members", channel = "Someone", members = true, age = "1 year ago")
    private val chan = Item(IKind.CHANNEL, "UCaaaaaaaaaaaaaaaaaaaaaa", "Band - Topic")
    private val all = listOf(vid, short, live, mem, chan)
    private val none: (String) -> Boolean = { false }

    @Test fun clientPredicates() {
        fun ids(f: SearchFilters, dl: (String) -> Boolean = none) = ItemFilter.search(all, f, dl).map { it.title }
        assertEquals(listOf("Song", "Short", "Live", "Members", "Band - Topic"), ids(SearchFilters()))
        assertEquals(listOf("Song", "Live", "Members", "Band - Topic"), ids(SearchFilters(hideShorts = true)))
        assertEquals(listOf("Song", "Short", "Members", "Band - Topic"), ids(SearchFilters(hideLive = true)))
        assertEquals(listOf("Members"), ids(SearchFilters(membersOnly = true)))
        assertEquals(listOf("Song", "Band - Topic"), ids(SearchFilters(musicOnly = true)))
        assertEquals(listOf("Short", "Live", "Members", "Band - Topic"), ids(SearchFilters(hideDownloaded = true), { it == "AAAAAAAAAAA" }))
        // newest: by the "… ago" text, unknown last
        assertEquals(listOf("Short", "Song", "Members", "Live", "Band - Topic"), ids(SearchFilters(sort = SSort.NEWEST)))
        assertFalse(SearchFilters().client)
        assertTrue(SearchFilters(hideShorts = true).client)
    }

    @Test fun agoParse() {
        assertEquals(3 * 86400L, AgoParse.seconds("3 days ago"))
        assertEquals(2 * 7 * 86400L, AgoParse.seconds("Streamed 2 weeks ago"))
        assertEquals(60L, AgoParse.seconds("1 minute ago"))
        assertEquals(0L, AgoParse.seconds("just now"))
        assertNull(AgoParse.seconds("Premieres tomorrow"))
    }

    @Test fun scanBudget() {
        assertTrue(Scan.more(0, 1, hasToken = true, clientFilters = true))
        assertFalse(Scan.more(Scan.WANT, 1, true, true))
        assertFalse(Scan.more(0, Scan.MAX_PAGES, true, true))
        assertFalse(Scan.more(0, 1, false, true))
        assertFalse(Scan.more(0, 1, true, false))
    }

    @Test fun shortsAreDetectedInSearchAndRss() {
        val r = JSONObject().put("contents", JSONArray().put(JSONObject().put("videoRenderer", JSONObject().put("videoId", "EEEEEEEEEEE")
            .put("title", JSONObject().put("simpleText", "x")).put("navigationEndpoint", JSONObject().put("reelWatchEndpoint", JSONObject().put("videoId", "EEEEEEEEEEE"))))))
        assertEquals(IKind.SHORT, Browse.page(r).items.single().kind)
        val items = Rss.parse(KXmlParser().apply { setFeature(org.xmlpull.v1.XmlPullParser.FEATURE_PROCESS_NAMESPACES, true) },
            javaClass.getResourceAsStream("/rss_nasa.xml")!!, "UCLA_DiR1FfKNvjuUpBHmylQ")
        assertEquals(1, items.count { it.kind == IKind.SHORT }) // the one /shorts/ link in the captured feed
        assertEquals(15, items.size)
    }

    // ---- feed ----

    @Test fun feedFilters() {
        val now = 1_800_000_000_000L
        val h = 3_600_000L
        val a = Item(IKind.VIDEO, "AAAAAAAAAAA", "a", channel = "Zeta", channelId = "UCz", published = now - 2 * h)
        val b = Item(IKind.SHORT, "BBBBBBBBBBB", "b", channel = "Alpha", channelId = "UCa", published = now - 30 * h)
        val c = Item(IKind.VIDEO, "CCCCCCCCCCC", "c", channel = "Alpha", channelId = "UCa", published = now - 10 * 24 * h, members = true)
        val d = Item(IKind.VIDEO, "DDDDDDDDDDD", "d", channel = "Band - Topic", channelId = "UCm", published = now - h)
        val feed = listOf(a, b, c, d)
        fun t(f: FeedFilters, dl: (String) -> Boolean = none) = f.apply(feed, now, dl).map { it.title }
        assertEquals(listOf("d", "a", "b", "c"), t(FeedFilters()))
        assertEquals(listOf("c"), t(FeedFilters(membersOnly = true)))
        assertEquals(listOf("d", "a", "c"), t(FeedFilters(hideShorts = true)))
        assertEquals(listOf("a", "b", "c"), t(FeedFilters(hideDownloaded = true), { it == "DDDDDDDDDDD" }))
        assertEquals(listOf("d"), t(FeedFilters(musicOnly = true)))
        assertEquals(listOf("b", "c"), t(FeedFilters(channels = setOf("UCa"))))
        assertEquals(listOf("d", "a"), t(FeedFilters(age = FAge.DAY)))
        assertEquals(listOf("d", "a", "b"), t(FeedFilters(age = FAge.WEEK)))
        assertEquals(listOf("b", "c", "d", "a"), t(FeedFilters(sort = FSort.CHANNEL))) // Alpha (newest first), Band - Topic, Zeta
    }

    @Test fun feedCacheKeepsMembersUploadsBetweenRefreshes() {
        val now = 1_000L
        val pub = Item(IKind.VIDEO, "AAAAAAAAAAA", "public", channelId = "UCa", published = 5)
        val memb = Item(IKind.VIDEO, "MMMMMMMMMMM", "members", channelId = "UCa", published = 6)
        var c = FeedCache.EMPTY.with("UCa", listOf(pub), now, listOf(memb))
        assertEquals(listOf("members", "public"), c.items.map { it.title })
        assertTrue(c.items.first { it.id == "MMMMMMMMMMM" }.members)
        // an automatic refresh (members not asked) keeps the members-only uploads found earlier
        c = c.with("UCa", listOf(pub), now + 1)
        assertTrue(c.items.any { it.id == "MMMMMMMMMMM" && it.members })
        // a manual refresh that found none (404 / gone) drops them
        c = c.with("UCa", listOf(pub), now + 2, emptyList())
        assertEquals(listOf("public"), c.items.map { it.title })
        // the same video in both feeds counts once, as members-only
        c = c.with("UCa", listOf(pub, memb), now + 3, listOf(memb))
        assertEquals(2, c.items.size)
        assertTrue(c.items.first { it.id == "MMMMMMMMMMM" }.members)
    }

    // ---- selection + unfollow ----

    @Test fun selectionModel() {
        val s = Selection()
        assertFalse(s.active)
        s.addAll(listOf("x")) // ignored while not selecting
        assertEquals(0, s.size)
        s.start("a")
        assertTrue(s.active); assertTrue("a" in s)
        assertTrue(s.toggle("b")); assertEquals(2, s.size)
        assertTrue(s.toggle("a")); assertEquals(listOf("b"), s.ids.toList())
        s.addAll(listOf("c", "d", "b")); assertEquals(listOf("b", "c", "d"), s.ids.toList())
        s.retain(setOf("c", "z")); assertEquals(listOf("c"), s.ids.toList())
        assertEquals(listOf("c"), s.pick(listOf("a", "c", "d")) { it })
        assertFalse(s.toggle("c")) // emptied: selection ends
        assertFalse(s.active); assertEquals(0, s.size)
        s.start("q"); s.end(); assertFalse(s.active); assertEquals(0, s.size)
    }

    @Test fun unfollowTexts() {
        assertEquals("Unfollow Alpha?", UnfollowText.confirm(listOf("Alpha")))
        assertEquals("Unfollow 3 channels?\n• A\n• B\n• C", UnfollowText.confirm(listOf("A", "B", "C")))
        assertEquals("Unfollow 8 channels?\n• 1\n• 2\n• 3\n• 4\n• 5\n+3 more", UnfollowText.confirm((1..8).map { "$it" }))
        assertEquals("Unfollowed 6 · YouTube unsubscribes can't be undone here", UnfollowText.undoBar(6, true))
        assertEquals("Unfollowed", UnfollowText.undoBar(1, false))
        val f = Follow("UCaaaaaaaaaaaaaaaaaaaaaa", "Işık Müzik")
        assertTrue(UnfollowText.matches(f, "isik"))
        assertTrue(UnfollowText.matches(f, "MUZ"))
        assertTrue(UnfollowText.matches(f, ""))
        assertTrue(UnfollowText.matches(f, "UCaaaaaaaaaaaaaaaaaaaaaa"))
        assertFalse(UnfollowText.matches(f, "rock"))
    }
}
