package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.0 account logic. Every cookie value here is FAKE (made up for the tests); no real account data anywhere. */
class AccountTest {
    private val now = 1_800_000_000L

    private val file = """
        # Netscape HTTP Cookie File
        # This is a generated file! Do not edit.

        .youtube.com	TRUE	/	TRUE	1900000000	SAPISID	FAKE_SAPISID_abc/def
        .youtube.com	TRUE	/	TRUE	1900000000	__Secure-1PAPISID	FAKE_1P_xyz
        .youtube.com	TRUE	/	TRUE	1900000000	__Secure-3PAPISID	FAKE_3P_qrs
        #HttpOnly_.youtube.com	TRUE	/	TRUE	1900000000	SID	FAKE_SID
        #HttpOnly_.youtube.com	TRUE	/	TRUE	1900000000	__Secure-1PSID	FAKE_1PSID
        #HttpOnly_.youtube.com	TRUE	/	TRUE	1900000000	LOGIN_INFO	FAKE_LOGIN:INFO==
        .youtube.com	TRUE	/	TRUE	1900000000	VISITOR_INFO1_LIVE	FAKE_TRACKING
        .youtube.com	TRUE	/	TRUE	1900000000	PREF	f6=40000000
        .youtube.com	TRUE	/	TRUE	1600000000	HSID	FAKE_EXPIRED
        .google.com	TRUE	/	TRUE	1900000000	SID	FAKE_GOOGLE_SID
        .example.org	TRUE	/	FALSE	1900000000	session	FAKE_OTHER_SITE
        broken line without tabs
    """.trimIndent()

    @Test fun parsesNetscapeFileKeepingOnlyYoutubeAuthCookies() {
        val r = CookieFile.parse(file, now)
        val names = r.cookies.map { it.name }.toSet()
        assertEquals(setOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID", "SID", "__Secure-1PSID", "LOGIN_INFO"), names)
        assertTrue(r.cookies.all { it.domain == "youtube.com" })
        assertEquals("FAKE_SID", r.cookies.first { it.name == "SID" }.value) // the youtube.com SID, not google.com's
        assertTrue(r.cookies.first { it.name == "LOGIN_INFO" }.httpOnly)
        assertEquals("FAKE_LOGIN:INFO==", r.cookies.first { it.name == "LOGIN_INFO" }.value)
        assertEquals(6, r.dropped) // tracking, PREF, expired, google.com, other site, broken line
        assertTrue(CookiePolicy.signedIn(r.cookies))
        assertFalse(CookiePolicy.signedIn(r.cookies.filter { it.name != "LOGIN_INFO" }))
    }

    @Test fun cookieToStringNeverShowsTheValue() {
        val c = Cookie("youtube.com", "SID", "FAKE_SECRET_VALUE")
        assertFalse(c.toString().contains("FAKE_SECRET_VALUE"))
    }

    @Test fun cookieHeaderOnlyForHttpsYoutubeHosts() {
        val jar = CookieJar(CookieFile.parse(file, now).cookies)
        val h = jar.header("https://www.youtube.com/youtubei/v1/player?prettyPrint=false", now)!!
        assertTrue(h.contains("SAPISID=FAKE_SAPISID_abc/def"))
        assertTrue(h.contains("LOGIN_INFO=FAKE_LOGIN:INFO=="))
        assertTrue(jar.header("https://music.youtube.com/youtubei/v1/browse", now) != null)
        for (u in listOf(
            "http://www.youtube.com/youtubei/v1/player", // not https
            "https://rr3---sn-abc.googlevideo.com/videoplayback?x=1", // streams never get cookies
            "https://i.ytimg.com/vi/x/hqdefault.jpg",
            "https://www.youtube.com.evil.example/", "https://evil.example/?u=https://www.youtube.com/",
            "https://www.youtube.com@evil.example/", "https://accounts.google.com/", "https://www.google.com/",
            "https://youtube.com:8443/", "https://notyoutube.com/",
        )) assertNull(u, jar.header(u, now))
        assertTrue(CookiePolicy.hostAllowed("https://www.youtube.com:443/x"))
    }

    @Test fun expiredCookiesAreNotSent() {
        val jar = CookieJar(listOf(Cookie("youtube.com", "SID", "FAKE", expires = now - 1), Cookie("youtube.com", "HSID", "FAKE2", expires = now + 100)))
        assertEquals("HSID=FAKE2", jar.header("https://www.youtube.com/", now))
    }

    @Test fun setCookieRotationUpdatesOnlyAllowedYoutubeCookies() {
        val jar = CookieJar(CookieFile.parse(file, now).cookies)
        val changed = jar.apply(listOf(
            "__Secure-1PSIDTS=FAKE_ROTATED_TS; Domain=.youtube.com; Path=/; Expires=Fri, 26-Nov-2027 10:00:00 GMT; Secure; HttpOnly; Priority=HIGH",
            "SID=; Domain=.youtube.com; Max-Age=0", // deleted
            "VISITOR_INFO1_LIVE=FAKE_TRACK; Domain=.youtube.com", // not kept
            "SAPISID=FAKE_FROM_GOOGLE; Domain=.google.com", // wrong domain
            "YSC=FAKE; Domain=.youtube.com",
        ), "www.youtube.com", now)
        assertTrue(changed)
        assertEquals("FAKE_ROTATED_TS", jar["__Secure-1PSIDTS"])
        assertNull(jar["SID"])
        assertNull(jar["VISITOR_INFO1_LIVE"])
        assertNull(jar["YSC"])
        assertEquals("FAKE_SAPISID_abc/def", jar["SAPISID"])
        val exp = jar.all.first { it.name == "__Secure-1PSIDTS" }.expires
        assertEquals(1827223200L, exp) // 2027-11-26 10:00:00 UTC
        // answers from other hosts never touch the jar
        assertFalse(jar.apply(listOf("SAPISID=FAKE_EVIL; Domain=.youtube.com"), "evil.example", now))
        assertEquals("FAKE_SAPISID_abc/def", jar["SAPISID"])
        // same value again = no change (no needless re-encryption)
        assertFalse(jar.apply(listOf("__Secure-1PSIDTS=FAKE_ROTATED_TS; Domain=.youtube.com; Expires=Fri, 26-Nov-2027 10:00:00 GMT"), "www.youtube.com", now))
    }

    @Test fun jarRoundTrip() {
        val jar = CookieJar(CookieFile.parse(file, now).cookies)
        val back = CookieJar.decode(jar.encode())
        assertEquals(jar.all, back.all)
        assertTrue(back.signedIn)
    }

    @Test fun cookieManagerHeaderIsFiltered() {
        val l = CookieFile.fromHeader("YSC=FAKE1; SID=FAKE_SID; LOGIN_INFO=FAKE:LI=; PREF=f6=4; __Secure-3PAPISID=FAKE3P; VISITOR_INFO1_LIVE=X")
        assertEquals(listOf("SID", "LOGIN_INFO", "__Secure-3PAPISID"), l.map { it.name })
        assertEquals("FAKE:LI=", l[1].value)
        assertTrue(CookiePolicy.signedIn(l))
        assertTrue(CookieFile.fromHeader(null).isEmpty())
    }

    @Test fun sapisidHashMatchesKnownVectors() {
        val jar = CookieJar(CookieFile.parse(file, now).cookies)
        val h = SidAuth.header(jar, "https://www.youtube.com", 1_700_000_000L)
        // sha1("1700000000 FAKE_SAPISID_abc/def https://www.youtube.com") etc., computed independently with Python
        assertEquals(
            "SAPISIDHASH 1700000000_888c9db3662e554641af8cf4a4c10c83611d46b8 " +
                "SAPISID1PHASH 1700000000_95d0d885aee83d5899e23baecf343f1f8563f30b " +
                "SAPISID3PHASH 1700000000_9a76402fbe1d62e3ed365686ae6781890bffcd3e",
            h,
        )
        // no SAPISID: falls back to __Secure-3PAPISID for the first part (as YouTube and yt-dlp do)
        val only3p = CookieJar(listOf(Cookie("youtube.com", "__Secure-3PAPISID", "FAKE_3P_qrs")))
        assertEquals(
            "SAPISIDHASH 1700000000_9a76402fbe1d62e3ed365686ae6781890bffcd3e SAPISID3PHASH 1700000000_9a76402fbe1d62e3ed365686ae6781890bffcd3e",
            SidAuth.header(only3p, "https://www.youtube.com", 1_700_000_000L),
        )
        assertNull(SidAuth.header(CookieJar(listOf(Cookie("youtube.com", "SID", "FAKE"))), "https://www.youtube.com", 1L))
    }

    @Test fun separationRule() {
        val yt = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
        // anonymous purposes never get the account, whatever else is true
        for (p in Purpose.values().filter { !it.accountUse }) {
            assertFalse(p.name, AuthRule.useAccount(p, yt, signedIn = true, membersOnly = true))
        }
        // signed out: nothing
        for (p in Purpose.values()) assertFalse(AuthRule.useAccount(p, yt, signedIn = false, membersOnly = true))
        // members purposes only for members-only content
        assertTrue(AuthRule.useAccount(Purpose.MEMBERS_PLAYER, yt, true, true))
        assertFalse(AuthRule.useAccount(Purpose.MEMBERS_PLAYER, yt, true, false))
        assertTrue(AuthRule.useAccount(Purpose.MEMBERS_TAB, "https://www.youtube.com/youtubei/v1/browse", true, true))
        assertFalse(AuthRule.useAccount(Purpose.MEMBERS_TAB, "https://www.youtube.com/youtubei/v1/browse", true, false))
        // user-started account actions
        assertTrue(AuthRule.useAccount(Purpose.SUBSCRIPTIONS_SYNC, "https://www.youtube.com/youtubei/v1/browse", true, false))
        assertTrue(AuthRule.useAccount(Purpose.ACCOUNT_CHECK, AuthHttp.MENU_URL, true, false))
        // never to other hosts, even for an account purpose
        for (u in listOf("https://rr1---sn-x.googlevideo.com/videoplayback", "https://evil.example/youtubei/v1/browse", "http://www.youtube.com/x")) {
            assertFalse(u, AuthRule.useAccount(Purpose.SUBSCRIPTIONS_SYNC, u, true, true))
            assertFalse(u, AuthRule.useAccount(Purpose.MEMBERS_PLAYER, u, true, true))
        }
        // streams and the public player JS are anonymous by definition
        assertFalse(Purpose.STREAM.accountUse)
        assertFalse(Purpose.PLAYER_JS.accountUse)
    }

    // ---- members-only detection ----

    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    @Test fun membersBadgeInVideoRenderer() {
        val json = JSONObject().put("contents", JSONArray()
            .put(JSONObject().put("videoRenderer", JSONObject().put("videoId", "AAAAAAAAAAA").put("title", JSONObject().put("simpleText", "Members video"))
                .put("badges", JSONArray().put(JSONObject().put("metadataBadgeRenderer", JSONObject()
                    .put("style", "BADGE_STYLE_TYPE_MEMBERS_ONLY").put("label", "Members only"))))))
            .put(JSONObject().put("videoRenderer", JSONObject().put("videoId", "BBBBBBBBBBB").put("title", JSONObject().put("simpleText", "Public video"))
                .put("badges", JSONArray().put(JSONObject().put("metadataBadgeRenderer", JSONObject().put("style", "BADGE_STYLE_TYPE_SIMPLE").put("label", "New")))))))
        val items = Browse.page(json).items
        assertEquals(listOf("AAAAAAAAAAA", "BBBBBBBBBBB"), items.map { it.id })
        assertTrue(items[0].members)
        assertFalse(items[1].members)
    }

    @Test fun membersBadgeInLockup() {
        val badge = JSONObject().put("badgeViewModel", JSONObject().put("badgeText", "Members only").put("badgeStyle", "BADGE_MEMBERS_ONLY"))
        val lockup = JSONObject().put("lockupViewModel", JSONObject().put("contentId", "CCCCCCCCCCC").put("contentType", "LOCKUP_CONTENT_TYPE_VIDEO")
            .put("metadata", JSONObject().put("lockupMetadataViewModel", JSONObject().put("title", JSONObject().put("content", "Locked"))
                .put("metadata", JSONObject().put("contentMetadataViewModel", JSONObject().put("metadataRows", JSONArray()
                    .put(JSONObject().put("badges", JSONArray().put(badge)))))))))
        val items = Browse.page(JSONObject().put("contents", JSONArray().put(lockup))).items
        assertEquals(1, items.size)
        assertTrue(items[0].members)
        // round trip through the feed/list codec
        assertTrue(ItemCodec.decode(ItemCodec.encode(items))[0].members)
    }

    @Test fun realAnonymousListingsHaveNoMembersFlags() {
        for (f in listOf("search_videos.json", "channel_videos.json", "playlist_p1.json", "search_all.json")) {
            val items = Browse.page(res(f)).items
            assertTrue(f, items.isNotEmpty())
            assertTrue(f, items.none { it.members })
        }
    }

    @Test fun membersOnlyPlayerAnswer() {
        val reason = "Join this channel to get access to members-only content like this video, and other exclusive perks."
        val json = JSONObject().put("playabilityStatus", JSONObject().put("status", "LOGIN_REQUIRED").put("reason", reason)).toString()
        val r = Yt.parsePlayer(json, "visionos", "AAAAAAAAAAA") as PlayerResult.Fail
        assertTrue(r.error.members)
        assertFalse(r.error.tryOther)
        assertTrue(Members.inReason("This video is available to this channel's members on level: Gold"))
        assertFalse(Members.inReason("Private video"))
        assertFalse(Yt.classify("ERROR", "Video unavailable").members)
        assertFalse(Yt.classify("LOGIN_REQUIRED", "Sign in to confirm you're not a bot").members)
        assertEquals("UUMOabcdefghijklmnopqrstuv", Members.playlistOf("UCabcdefghijklmnopqrstuv"))
        assertNull(Members.playlistOf("PL123"))
    }

    // ---- signed-in TV player: pure parts ----

    @Test fun playerIdAndSignatureTimestamp() {
        val iframe = "var scriptUrl = 'https:\\/\\/www.youtube.com\\/s\\/player\\/b485e332\\/www-widgetapi.vflset\\/www-widgetapi.js';"
        assertEquals("b485e332", TvPlayer.playerId(iframe))
        assertEquals("https://www.youtube.com/s/player/b485e332/tv-player-ias.vflset/tv-player-ias.js", TvPlayer.playerUrl("b485e332"))
        assertEquals(20779, TvPlayer.sts("var x={foo:1,signatureTimestamp:20779,bar:2}"))
        assertEquals(20779, TvPlayer.sts("a.sts: 20779;"))
        assertNull(TvPlayer.sts("nothing here"))
        val body = JSONObject(TvPlayer.body("AAAAAAAAAAA", 20779, null))
        assertEquals(20779, body.getJSONObject("playbackContext").getJSONObject("contentPlaybackContext").getInt("signatureTimestamp"))
        assertEquals("TVHTML5", body.getJSONObject("context").getJSONObject("client").getString("clientName"))
    }

    private val tvAnswer = JSONObject()
        .put("playabilityStatus", JSONObject().put("status", "OK"))
        .put("videoDetails", JSONObject().put("videoId", "AAAAAAAAAAA").put("title", "T").put("author", "A").put("lengthSeconds", "60"))
        .put("streamingData", JSONObject()
            .put("formats", JSONArray().put(JSONObject().put("itag", 18).put("mimeType", "video/mp4; codecs=\"avc1.42001E, mp4a.40.2\"")
                .put("url", "https://rr1---sn-fake.googlevideo.com/videoplayback?itag=18&n=NNN1&sig=x").put("qualityLabel", "360p").put("width", 640).put("height", 360)))
            .put("adaptiveFormats", JSONArray()
                .put(JSONObject().put("itag", 140).put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("contentLength", "1000")
                    .put("signatureCipher", "s=SSS%3D1&sp=sig&url=https%3A%2F%2Frr1---sn-fake.googlevideo.com%2Fvideoplayback%3Fitag%3D140%26n%3DNNN2"))
                .put(JSONObject().put("itag", 251).put("mimeType", "audio/webm; codecs=\"opus\"")
                    .put("url", "https://rr1---sn-fake.googlevideo.com/videoplayback?itag=251&n=NNN3")))).toString()

    @Test fun challengesAndRewrite() {
        val c = TvPlayer.challenges(tvAnswer)
        assertEquals(listOf("SSS=1"), c.sig)
        assertEquals(listOf("NNN1", "NNN2", "NNN3"), c.n)
        val input = JSONObject(TvPlayer.solverInput("/*player*/", c))
        assertEquals("player", input.getString("type"))
        assertEquals(2, input.getJSONArray("requests").length())
        val out = """{"type":"result","responses":[{"type":"result","data":{"SSS=1":"SOLVED+SIG"}},{"type":"result","data":{"NNN1":"n1ok","NNN2":"n2ok"}}]}"""
        val (sig, n) = TvPlayer.solverOutput(out, c)
        val rw = TvPlayer.rewrite(tvAnswer, sig, n)
        val info = (Yt.parsePlayer(rw, TvPlayer.KEY, "AAAAAAAAAAA") as PlayerResult.Ok).info
        val byItag = info.formats.associateBy { it.itag }
        assertEquals("https://rr1---sn-fake.googlevideo.com/videoplayback?itag=18&n=n1ok&sig=x", byItag[18]!!.url)
        assertEquals("https://rr1---sn-fake.googlevideo.com/videoplayback?itag=140&n=n2ok&sig=SOLVED%2BSIG", byItag[140]!!.url)
        assertNull(byItag[251]) // its n wasn't solved: dropped rather than served throttled
        assertEquals(TvPlayer.UA, Clients.byKey(TvPlayer.KEY)!!.userAgent)
    }

    @Test(expected = YtError::class) fun solverErrorIsReported() {
        TvPlayer.solverOutput("""{"type":"error","error":"boom"}""", TvPlayer.Challenges(emptyList(), listOf("x")))
    }

    // ---- account menu, subscriptions sync ----

    @Test fun accountNameAndLoggedOut() {
        val menu = JSONObject().put("actions", JSONArray().put(JSONObject().put("openPopupAction", JSONObject().put("popup",
            JSONObject().put("multiPageMenuRenderer", JSONObject().put("header", JSONObject().put("activeAccountHeaderRenderer",
                JSONObject().put("accountName", JSONObject().put("simpleText", "Test User")))))))))
        assertEquals("Test User", AccountParse.accountName(menu.toString()))
        assertNull(AccountParse.accountName("{}"))
        assertTrue(AccountParse.loggedOut("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":true}}}"""))
        assertFalse(AccountParse.loggedOut("""{"responseContext":{"mainAppWebResponseContext":{"loggedOut":false}}}"""))
    }

    @Test fun subscriptionsAndSyncPlan() {
        fun ch(id: String, t: String, h: String) = JSONObject().put("channelRenderer", JSONObject().put("channelId", id)
            .put("title", JSONObject().put("simpleText", t)).put("subscriberCountText", JSONObject().put("simpleText", h))
            .put("videoCountText", JSONObject().put("simpleText", "1.2M subscribers")))
        val page = JSONObject().put("contents", JSONArray()
            .put(ch("UCaaaaaaaaaaaaaaaaaaaaaa", "Alpha", "@alpha"))
            .put(ch("UCbbbbbbbbbbbbbbbbbbbbbb", "Beta", "@beta"))
            .put(JSONObject().put("continuationItemRenderer", JSONObject().put("continuationEndpoint",
                JSONObject().put("continuationCommand", JSONObject().put("token", "TOKEN2"))))))
        val (subs, next) = AccountParse.subscriptions(page.toString())
        assertEquals(listOf("Alpha", "Beta"), subs.map { it.name })
        assertEquals("@alpha", subs[0].handle)
        assertEquals("TOKEN2", next)
        val plan = SyncPlan.of(subs, setOf("UCaaaaaaaaaaaaaaaaaaaaaa", "UCzzzzzzzzzzzzzzzzzzzzzz"))
        assertEquals(listOf("UCbbbbbbbbbbbbbbbbbbbbbb"), plan.add.map { it.id })
        assertEquals(1, plan.already)
        assertEquals(listOf("UCzzzzzzzzzzzzzzzzzzzzzz"), plan.gone)
        assertEquals("Added 1, already followed 1", plan.summary())
    }

    @Test fun inAppSignInRefusalDetection() {
        assertTrue(SignInActivity.refusedUrl("https://accounts.google.com/v3/signin/rejected?continue=x"))
        assertTrue(SignInActivity.refusedUrl("https://accounts.google.com/signin/oauth/error?authError=disallowed_useragent"))
        assertFalse(SignInActivity.refusedUrl("https://accounts.google.com/v3/signin/identifier?continue=x"))
        assertTrue(SignInActivity.refusedText("\"Couldn’t sign you in\nThis browser or app may not be secure. Learn more\""))
        assertTrue(SignInActivity.refusedText("Couldn't sign you in"))
        assertFalse(SignInActivity.refusedText("Sign in\nto continue to YouTube"))
    }

    // ---- 2.1 unsubscribe ----

    @Test fun unsubscribeRule() {
        val u = Unsub.URL
        assertTrue(AuthRule.useAccount(Purpose.UNSUBSCRIBE, u, signedIn = true, membersOnly = false))
        assertTrue(AuthRule.useAccount(Purpose.UNSUBSCRIBE, "https://youtube.com/youtubei/v1/subscription/unsubscribe", true, false))
        assertFalse(AuthRule.useAccount(Purpose.UNSUBSCRIBE, u, signedIn = false, membersOnly = false))
        for (bad in listOf("https://music.youtube.com/youtubei/v1/subscription/unsubscribe", "https://m.youtube.com/youtubei/v1/subscription/unsubscribe",
            "http://www.youtube.com/youtubei/v1/subscription/unsubscribe", "https://evil.example/youtubei/v1/subscription/unsubscribe",
            "https://www.youtube.com.evil.example/x")) assertFalse(bad, AuthRule.useAccount(Purpose.UNSUBSCRIBE, bad, true, false))
        assertTrue(Purpose.UNSUBSCRIBE.accountUse)
        // the earlier purposes are unchanged
        assertFalse(AuthRule.useAccount(Purpose.MEMBERS_PLAYER, "https://www.youtube.com/youtubei/v1/player", true, false))
    }

    @Test fun unsubscribeBodyAndAnswer() {
        val b = JSONObject(Unsub.body("UCaaaaaaaaaaaaaaaaaaaaaa"))
        assertEquals("UCaaaaaaaaaaaaaaaaaaaaaa", b.getJSONArray("channelIds").getString(0))
        assertEquals(1, b.getJSONArray("channelIds").length())
        assertEquals("WEB", b.getJSONObject("context").getJSONObject("client").getString("clientName"))
        assertFalse(b.has("params"))
        assertFalse(b.getJSONObject("context").getJSONObject("client").has("visitorData"))
        try { Unsub.body("PL123"); org.junit.Assert.fail() } catch (_: IllegalArgumentException) {}
        val id = "UCaaaaaaaaaaaaaaaaaaaaaa"
        assertNull(Unsub.failure("""{"actions":[{"updateSubscribeButtonAction":{"subscribed":false,"channelId":"$id"}}]}""", id))
        assertNull(Unsub.failure("""{"responseContext":{}}""", id))
        assertEquals("YouTube still shows the subscription",
            Unsub.failure("""{"actions":[{"updateSubscribeButtonAction":{"subscribed":true,"channelId":"$id"}}]}""", id))
        assertEquals("Request contains an invalid argument.", Unsub.failure("""{"error":{"code":400,"message":"Request contains an invalid argument."}}""", id))
    }
}
