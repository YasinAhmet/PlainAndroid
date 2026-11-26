package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6: Topic channels / YouTube Music artist pages (captured 2026-11-26, WEB + WEB_REMIX, no account), the
 * "Download all" collection (dedupe, continue after the cap), batches, parallel scheduling.
 */
class MusicTest {
    private fun res(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun topicChannelOnYouTubeHasOnlyHome() {
        // "Botho Lukas Chor - Topic" (UCqEZVN5SnfNGz6yD7UWOJRw): Home tab only, one "Albums & Singles" shelf
        val o = org.json.JSONObject(res("topic_home_botho.json"))
        val h = Browse.channelHead(o)!!
        assertEquals("Botho Lukas Chor - Topic", h.title)
        assertEquals(setOf("featured"), h.tabs.keys)
        val p = Browse.page(o)
        val album = p.items.first { it.kind == IKind.PLAYLIST }
        assertTrue(album.id.startsWith("OLAK5uy_"))
        assertEquals("24 songs", album.count)
        assertTrue(p.items.none { it.kind == IKind.VIDEO })
    }

    @Test fun smallArtistPage() {
        val a = MusicParse.artist(res("remix_artist_botho.json"))!!
        assertEquals("Botho Lukas Chor", a.name)
        assertEquals(1, a.albums.size)
        val r = a.albums[0]
        assertEquals("OLAK5uy_nd7v7cB9WgL6qnhLb5HyIYLafVXa7Kn8A", r.id)
        assertEquals("Wenn die Landsknecht singen", r.title)
        assertEquals("2014", r.year); assertEquals("Album", r.kind)
        assertTrue(r.cover!!.startsWith("https://"))
        assertNull(a.albumsMore) // no "More": the carousel is complete
        assertTrue(a.singles.isEmpty())
        assertEquals("OLAK5uy_nbBlB52jBzW5QlgG262B8yM71jpXHOmf8", a.songsPlaylist) // "Top songs" → Show all (42 songs live)
        assertEquals("OLAK5uy_mRX4CN5QcVSPWu70BrDL3gDShGOqhHvQc", a.videosPlaylist)
        assertTrue(a.hasMusic)
        val item = r.item("Botho Lukas Chor")
        assertEquals(IKind.PLAYLIST, item.kind); assertEquals("Album · 2014", item.count); assertEquals(r.cover, item.thumbId)
        assertEquals(r.cover, Clients.thumbSmall(item.thumbId!!)) // covers load from their own URL
    }

    @Test fun bigArtistPageAndDiscography() {
        val a = MusicParse.artist(res("remix_artist_fonsi.json"))!!
        assertEquals("Luis Fonsi", a.name)
        assertEquals(10, a.albums.size)
        assertEquals("MPADUCk91oFc2hY2CdHirU93baLg", a.albumsMore!!.browseId)
        assertEquals("ggMIegYIARoCAQI%3D", a.albumsMore!!.params)
        assertEquals(10, a.singles.size)
        assertEquals("ggMIegYIAhoCAQI%3D", a.singlesMore!!.params)
        assertEquals("OLAK5uy_n4iin5ia_C2K0yASnWyC6vgayHV7HwcX8", a.songsPlaylist)
        // the albums "More" page lists all 11
        val all = MusicParse.discography(res("remix_discography_albums.json"), "Album")
        assertEquals(11, all.size)
        assertEquals("El Viaje", all[0].title); assertEquals("2024", all[0].year); assertEquals("Album", all[0].kind)
        assertTrue(all.all { it.id.startsWith("OLAK5uy_") })
        val merged = MusicParse.merge(a, all, null)
        assertEquals(11 + a.singles.size, merged.size)
        assertEquals(merged.map { it.id }.distinct(), merged.map { it.id })
        assertNull(MusicParse.artist("<html>"))
        assertTrue(MusicParse.discography("{}", "Album").isEmpty())
        assertFalse(MusicParse.Artist("", emptyList(), null, emptyList(), null, null, null).hasMusic)
    }

    @Test fun discographyPaging() {
        // "Jan Sebastian Bach" (UCFtSXTlIMFFkyJbHO3V5b7A) Singles & EPs: 31 pages of 100 (3,041 releases) live; cut to 12 here
        val p1 = MusicParse.discographyPage(res("remix_disco_bach_p1.json"), "Single")
        assertEquals(12, p1.releases.size)
        assertTrue(p1.releases.all { it.id.startsWith("OLAK5uy_") })
        assertTrue(p1.token!!.length > 100)
        val p2 = MusicParse.discographyPage(res("remix_disco_bach_p2.json"), "Single") // continuationContents.gridContinuation
        assertEquals(12, p2.releases.size)
        assertTrue(p2.token != null && p2.token != p1.token)
        assertTrue(p1.releases.map { it.id }.intersect(p2.releases.map { it.id }.toSet()).isEmpty())
        // a "More" page that fails ("Something went wrong", seen live for Bach's albums): no items, no token
        val broken = MusicParse.discographyPage(res("remix_disco_broken.json"), "Album")
        assertTrue(broken.releases.isEmpty()); assertNull(broken.token)
        assertNull(MusicParse.nextToken(org.json.JSONObject("{}")))
        assertEquals("T1", MusicParse.nextToken(org.json.JSONObject("""{"continuationContents":{"gridContinuation":{"continuations":[{"nextContinuationData":{"continuation":"T1"}}]}}}""")))
        assertEquals("T2", MusicParse.nextToken(org.json.JSONObject("""{"contents":{"x":[{"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"T2"}}}}]}}""")))
        // the Fonsi albums "More" page has everything on one page
        assertNull(MusicParse.discographyPage(res("remix_discography_albums.json"), "Album").token)
        // an empty / failed full list keeps the carousel
        val a = MusicParse.artist(res("remix_artist_fonsi.json"))!!
        assertEquals(a.albums.size + a.singles.size, MusicParse.merge(a, emptyList(), null).size)
        // cursor: More page, continuation pages, end
        val c = MusicParse.Cursor("Single", MusicParse.More("MPADx", "p"), a.singles)
        assertFalse(c.started); assertFalse(c.done)
        c.took(p1); assertTrue(c.started); assertFalse(c.done); assertEquals(p1.token, c.token)
        c.took(MusicParse.DiscoPage(p2.releases, null)); assertTrue(c.done)
        val none = MusicParse.Cursor("Album", null, a.albums)
        assertTrue(none.done); assertEquals(a.albums, none.initial)
    }

    private fun v(id: String, live: Boolean = false) = Item(IKind.VIDEO, id.padEnd(11, 'x'), "T$id", live = live)

    @Test fun collectDedupeAndContinue() {
        val lists = listOf("Album A" to listOf(v("a1"), v("a2"), v("s1")), "Single S" to listOf(v("s1")), null to listOf(v("a1"), v("x1"), v("l", live = true)))
        val r = Collect.plan(lists) { it == "a2xxxxxxxxx" }
        assertEquals(listOf("a1xxxxxxxxx", "s1xxxxxxxxx", "x1xxxxxxxxx"), r.take.map { it.item.id })
        assertEquals(listOf("Album A", "Album A", null), r.take.map { it.album }) // first list wins the album folder
        assertEquals(1, r.skipped); assertEquals(1, r.live); assertEquals(0, r.left)
        assertEquals("Queued 3, skipped 1 already downloaded or queued, 1 live/upcoming.", Collect.summary(3, r))
        // more than the cap: the first 500 now, the rest when run again (those 500 are then "queued" and skipped)
        val many = (0 until 620).map { v("m" + it.toString().padStart(9, '0')) }
        val first = Collect.plan(listOf("Big" to many)) { false }
        assertEquals(Bulk.MAX, first.take.size); assertEquals(120, first.left)
        assertTrue(Collect.summary(500, first).endsWith("120 more are left (limit 500 per run): run Download all again to continue."))
        val queued = first.take.map { it.item.id }.toHashSet()
        val second = Collect.plan(listOf("Big" to many)) { it in queued }
        assertEquals(120, second.take.size); assertEquals(500, second.skipped); assertEquals(0, second.left)
    }

    private fun job(id: Long, state: State, phase: Phase = Phase.DOWNLOAD, kind: Kind = Kind.AUDIO, batch: Long = 7) =
        Job(id, "v$id".padEnd(11, 'x'), "T", "", 0, kind, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC, state = state, phase = phase, batch = batch, batchTitle = "Botho Lukas Chor · All music")

    @Test fun batchAggregation() {
        val jobs = listOf(job(1, State.DONE), job(2, State.DONE), job(3, State.RUNNING), job(4, State.RUNNING, Phase.CONVERT),
            job(5, State.RUNNING, Phase.WAIT_SAVE), job(6, State.QUEUED), job(7, State.FAILED), job(8, State.CANCELED))
        val st = BatchStats.of(jobs)
        assertEquals(8, st.total); assertEquals(2, st.done); assertEquals(1, st.failed); assertEquals(4, st.active)
        assertEquals("2/8 · 1 downloading · 1 converting · 1 waiting to convert · 1 queued · 1 failed · 1 canceled", st.text)
        assertEquals("2/2 · done", BatchStats.of(jobs.take(2)).text)
        val back = JobCodec.decode(JobCodec.encode(jobs))
        assertEquals(7L, back[0].batch); assertEquals("Botho Lukas Chor · All music", back[0].batchTitle)
        // restart: only the running ones of the batch become "Interrupted", queued stay queued
        val l = back.toMutableList(); JobCodec.afterRestart(l)
        assertEquals(listOf(State.DONE, State.DONE, State.FAILED, State.FAILED, State.FAILED, State.QUEUED, State.FAILED, State.CANCELED), l.map { it.state })
    }

    @Test fun parallelScheduling() {
        val a = { id: Long -> job(id, State.RUNNING) }
        val video = job(9, State.QUEUED, kind = Kind.VIDEO)
        val audio = job(10, State.QUEUED)
        assertTrue(Sched.canStart(emptyList(), video, 3))
        assertTrue(Sched.canStart(emptyList(), audio, 3))
        assertTrue(Sched.canStart(listOf(a(1), a(2)), audio, 3))
        assertFalse(Sched.canStart(listOf(a(1), a(2), a(3)), audio, 3))
        assertFalse(Sched.canStart(listOf(a(1)), video, 3)) // video waits for the audio downloads
        assertFalse(Sched.canStart(listOf(job(11, State.RUNNING, kind = Kind.VIDEO)), audio, 3)) // and runs alone
        assertFalse(Sched.canStart(listOf(a(1)), audio, 1))
        assertTrue(Sched.canStart(listOf(a(1), a(2), a(3)), audio, 9)) // capped at 4
        assertFalse(Sched.canStart(listOf(a(1), a(2), a(3), a(4)), audio, 9))
        val r = listOf(a(1).also { it.total = 100; it.done = 40 }, a(2).also { it.total = 50; it.done = 60 })
        assertEquals(60L, Sched.pendingBytes(r))
    }
}
