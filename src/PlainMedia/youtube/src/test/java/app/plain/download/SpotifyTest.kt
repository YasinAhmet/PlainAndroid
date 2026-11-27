package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SpotifyTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun parsesYouTubeMusicSongSearch() {
        // real WEB_REMIX answer for "Tate McRae Housewife" with the Songs filter (2026-11-27)
        val songs = RemixSearch.parse(res("remix_search_songs.json"))
        assertTrue(songs.size >= 10)
        val first = songs[0]
        assertEquals("FXk4oLgkKL0", first.videoId)
        assertEquals("Housewife", first.title)
        assertEquals(listOf("Tate McRae"), first.artists)
        assertEquals("Housewife", first.album)
        assertEquals(209L, first.durationSec)
    }

    @Test fun picksTheRealSongNotTheSlowedOne() {
        val songs = RemixSearch.parse(res("remix_search_songs.json"))
        val t = SpTrack("x", "Housewife", listOf("Tate McRae"), "Housewife", 209_000)
        val (song, score) = SpMatcher.pick(t, songs)!!
        assertEquals("FXk4oLgkKL0", song.videoId)
        assertTrue(score > 0.9)
        // the user said it was wrong: never the same one again, and nothing else is good enough
        assertNull(SpMatcher.pick(t, songs, rejected = listOf("FXk4oLgkKL0")))
    }

    @Test fun titleCoreDropsFeaturesAndRemasters() {
        assertEquals("Flower In A Hurricane", SpMatcher.core("Flower In A Hurricane (feat. Tame Impala, SZA)"))
        assertEquals("Let It Be", SpMatcher.core("Let It Be - Remastered 2009"))
        assertEquals("Song", SpMatcher.core("Song [From \"Some Movie\" Soundtrack]"))
        assertEquals("Song", SpMatcher.core("Song feat. Someone"))
    }

    @Test fun variantsNeedTheSpotifyTitleToSaySo() {
        val t = SpTrack("x", "Song", listOf("Artist"), "", 200_000)
        val live = YtSong("a", "Song (Live)", listOf("Artist"), "", 200)
        val studio = YtSong("b", "Song", listOf("Artist"), "", 201)
        assertEquals("b", SpMatcher.pick(t, listOf(live, studio))!!.first.videoId)
        val tLive = t.copy(title = "Song - Live")
        assertTrue(SpMatcher.score(tLive, live) > 0.62)
    }

    @Test fun wrongArtistOrLengthIsNoMatch() {
        val t = SpTrack("x", "Yesterday", listOf("The Beatles"), "", 125_000)
        assertNull(SpMatcher.pick(t, listOf(YtSong("a", "Yesterday", listOf("Somebody Else"), "", 300))))
        assertNotNull(SpMatcher.pick(t, listOf(YtSong("b", "Yesterday (Remastered 2009)", listOf("The Beatles"), "", 126))))
    }

    @Test fun accentsAndTurkishLettersMatch() {
        val t = SpTrack("x", "Ayamaysın", listOf("Akdeniz Erbaş"), "", 229_000)
        assertTrue(SpMatcher.artistMatch(t.artists, listOf("Akdeniz Erbas"), ""))
        assertTrue(SpMatcher.titleSim("Ayamaysın", "Ayamaysin") > 0.99)
    }

    @Test fun readsWhatTheUserTyped() {
        assertEquals(SpotifyPublic.Target.Profile("31abc_x"), SpotifyPublic.target("https://open.spotify.com/user/31abc_x?si=123"))
        assertEquals(SpotifyPublic.Target.Profile("smedjan"), SpotifyPublic.target("open.spotify.com/intl-tr/user/smedjan"))
        assertEquals(SpotifyPublic.Target.Profile("smedjan"), SpotifyPublic.target("spotify:user:smedjan"))
        assertEquals(SpotifyPublic.Target.Profile("some.user"), SpotifyPublic.target("  some.user "))
        assertEquals(SpotifyPublic.Target.Playlist("37i9dQZF1DX5Ejj0EkURtP"), SpotifyPublic.target("https://open.spotify.com/playlist/37i9dQZF1DX5Ejj0EkURtP?si=a"))
        assertNull(SpotifyPublic.target("https://spotify.link/AbC"))
        assertNull(SpotifyPublic.target("two words"))
        assertNull(SpotifyPublic.target(""))
    }

    @Test fun parsesEmbedPage() {
        val json = """{"props":{"pageProps":{"state":{"data":{"entity":{"name":"All Out","subtitle":"Spotify","trackList":[
            {"uri":"spotify:track:t1","title":"Beauty","subtitle":"Justin Bieber, Nicki Minaj","duration":227000,"entityType":"track"},
            {"uri":"spotify:track:t2","title":"Who","subtitle":"Tyler, The Creator","duration":1000,"entityType":"track"},
            {"uri":"spotify:episode:e1","title":"Pod","subtitle":"x","duration":5,"entityType":"episode"}]}}}}}}"""
        val html = "<html><script id=\"__NEXT_DATA__\" type=\"application/json\">$json</script></html>"
        val e = SpotifyPublic.embed(html)!!
        assertEquals("All Out", e.name)
        assertEquals("Spotify", e.owner)
        assertEquals(listOf("t1", "t2"), e.tracks.map { it.id })
        assertEquals(listOf("Justin Bieber", "Nicki Minaj"), e.tracks[0].artists)
        assertEquals(listOf("Tyler, The Creator"), e.tracks[1].artists)
        assertEquals(227000L, e.tracks[0].durationMs)
        assertNull(SpotifyPublic.embed("<html>no data</html>"))
    }

    @Test fun parsesPageScriptResults() {
        val p = SpotifyPublic.profile("""{"found":true,"name":"smedjan","showAll":true,"lists":[{"id":"a","name":"Road"},{"id":"a","name":""},{"id":"b","name":""}]}""")
        assertTrue(p.found); assertTrue(p.showAll)
        assertEquals(listOf("a", "b"), p.lists.map { it.id })
        assertEquals("b", p.lists[1].name) // no name: the id
        assertFalse(SpotifyPublic.profile("""{"found":false}""").found)
        val pg = SpotifyPublic.page("""{"found":true,"name":"L","owner":"me","total":3,"tracks":[{"id":"x","t":"A","a":["P","Q"],"d":"3:45"},{"id":"x","t":"A","a":[],"d":""},{"id":"y","t":"B","a":["R"],"d":"1:02:03"}]}""")
        assertEquals(listOf("x", "y"), pg.tracks.map { it.id })
        assertEquals(225_000L, pg.tracks[0].durationMs)
        assertEquals(3_723_000L, pg.tracks[1].durationMs)
        assertEquals(3, pg.total)
    }

    @Test fun snapshotFollowsTheTrackOrder() {
        assertEquals(SpotifyPublic.snapshot(listOf("a", "b")), SpotifyPublic.snapshot(listOf("a", "b")))
        assertNotEquals(SpotifyPublic.snapshot(listOf("a", "b")), SpotifyPublic.snapshot(listOf("b", "a")))
    }

    @Test fun dataRoundTripsAndBuildsRows() {
        val t1 = SpTrack("t1", "A", listOf("X"), "Al", 1000)
        val t2 = SpTrack("t2", "B", listOf("Y"), "Bl", 2000)
        val p = SpPlaylist("p", "Road", "me", "s", 2, listOf("t1", "t2"))
        val d = SpData("me", 5, listOf(p), mapOf("t1" to t1, "t2" to t2),
            mapOf("t1" to SpMatch("vid00000001", "A", "X", 1, 0.9), "t2" to SpMatch(null, rejected = listOf("bad"))))
        val back = SpData.decode(d.encode())
        assertEquals(d, back)
        assertEquals(1, back.matched(p))
        assertEquals(listOf("t2"), back.missing(p))
        assertEquals(0, back.pending(p))
        val rows = back.items(p)
        assertEquals("vid00000001", rows.single().id)
        assertEquals("A", rows.single().title)
        assertEquals("X", rows.single().channel)
        assertEquals("t1", back.trackFor(p, "vid00000001"))
        assertFalse(SpData.decode("not json").playlists.isNotEmpty())
    }
}
