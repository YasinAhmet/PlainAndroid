package app.localmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicatesTest {
    private var nextId = 1L

    private fun f(path: String, size: Long = 2_407_659, dur: Long = 147_000, hash: String? = "h1", title: String = "Apathy - Slowed",
                  artist: String = "Lxzt", skip: String? = null): FileInfo {
        val name = path.substringAfterLast('/')
        val rel = path.substring(0, path.length - name.length)
        return FileInfo(nextId++, rel, name, size, dur, "audio/mp4", 130_000, Naming.resolve(RawTags(title = title, artist = artist), name, rel), skip, hash)
    }

    @Test fun reinstallCopyKeepsTheTrackedOneAndRemovesTheOrphan() {
        val orig = f("Music/PlainDownload/Lxzt/Alt-right/Apathy - Slowed.m4a")
        val copy = f("Music/PlainDownload/Lxzt/Alt-right/Apathy - Slowed (2).m4a")
        val p = Duplicates.plan(listOf(orig, copy), tracked = setOf(copy.id))
        assertEquals(1, p.removals.size)
        assertEquals(orig.id, p.removals[0].file.id)
        assertEquals(copy.id, p.removals[0].keep.id)
        assertTrue(p.removals[0].identical)
        assertEquals("Apathy - Slowed.m4a", Duplicates.plainName(copy, orig))
    }

    @Test fun untrackedIdenticalCopiesKeepThePlainName() {
        val orig = f("Music/Mine/Song.mp3", title = "Song", artist = "A")
        val copy = f("Music/Mine/Song (2).mp3", title = "Song", artist = "A")
        val p = Duplicates.plan(listOf(copy, orig), tracked = emptySet())
        assertEquals(copy.id, p.removals.single().file.id)
        assertNull(Duplicates.plainName(orig, copy))
    }

    @Test fun trackedCopiesAreNeverRemoved() {
        val a = f("Music/PlainDownload/X/Türkçe Pop/Song.m4a", hash = null)
        val b = f("Music/PlainDownload/X/Unut/Song.m4a", hash = null)
        val p = Duplicates.plan(listOf(a, b), tracked = setOf(a.id, b.id))
        assertTrue(p.removals.isEmpty())
        assertEquals(1, p.protected.size)
    }

    @Test fun cacheAndNonMusicAreLeftAlone() {
        val saved = f("Music/PlainDownload/Lxzt/Song.m4a")
        val cached = f("Music/PlainDownload/Cached/Lxzt/Song.m4a")
        val ring = f("Ringtones/Song.m4a", skip = "ringtone")
        assertTrue(Duplicates.plan(listOf(saved, cached, ring), emptySet()).removals.isEmpty())
    }

    @Test fun untickedCopiesStay() {
        val a = f("Music/Mine/Song.mp3"); val b = f("Music/Mine/Song (2).mp3")
        assertTrue(Duplicates.plan(listOf(a, b), emptySet(), keepByUser = setOf(b.id)).removals.isEmpty())
    }

    @Test fun differentSongsAreNotDuplicates() {
        val a = f("Music/Mine/A.mp3", title = "A", hash = "x", size = 10)
        val b = f("Music/Mine/B.mp3", title = "B", hash = "y", size = 11)
        assertTrue(Duplicates.plan(listOf(a, b), emptySet()).removals.isEmpty())
    }

    @Test fun trackedIdsReadTheDownloadIndex() {
        val t = "abc:A\tcontent://media/external/audio/media/100\n" +
            "def:A\tcontent://media/external/audio/media/200\n" +
            "-def:A\n" +
            "ghi:V\tcontent://media/external/video/media/300\n" +
            "old:A\n"
        assertEquals(setOf(100L, 300L), Duplicates.trackedIds(t))
    }

    @Test fun playlistsPointAtTheKeptCopy() {
        val keep = f("Music/a/Song (2).m4a").copy(name = "Song.m4a")
        val p1 = Playlist(1, "one", mutableListOf(Playlist.Entry(5, "Music/a/Song.m4a"), Playlist.Entry(9, "Music/a/Other.m4a")))
        val p2 = Playlist(2, "two", mutableListOf(Playlist.Entry(keep.id, "Music/a/Song (2).m4a"), Playlist.Entry(5, "Music/a/Song.m4a")))
        assertTrue(Duplicates.repoint(listOf(p1, p2), mapOf(5L to keep)))
        assertEquals(listOf(keep.id, 9L), p1.entries.map { it.songId })
        assertEquals("Music/a/Song.m4a", p1.entries[0].path)
        assertEquals(listOf(keep.id), p2.entries.map { it.songId }) // had the kept copy already: the removed one just goes
        assertFalse(Duplicates.repoint(listOf(p1), mapOf(77L to keep)))
    }
}
