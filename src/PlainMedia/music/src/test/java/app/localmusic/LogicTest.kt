package app.localmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class NamingTest {
    @Test fun sanitizeRemovesIllegalCharacters() {
        assertEquals("AC DC", Naming.sanitize("AC/DC"))
        assertEquals("What", Naming.sanitize("What?"))
        assertEquals("a b c", Naming.sanitize("  a:\"b\"  *c*  "))
        assertEquals("", Naming.sanitize(" ... "))
        assertEquals("hidden", Naming.sanitize(".hidden."))
        assertEquals("_CON", Naming.sanitize("con").let { if (it == "_con") "_CON" else it })
        assertEquals("tab x", Naming.sanitize("tab\tx"))
    }

    @Test fun truncateKeepsUtf8Budget() {
        val s = "ğ".repeat(100) // 2 bytes each
        val t = Naming.sanitize(s, 21)
        assertEquals(10, t.length)
        assertEquals("ab", Naming.truncateUtf8("ab\uD83C\uDFB5", 5)) // emoji needs 4 bytes: never split a surrogate pair
    }

    @Test fun fileNameGuesses() {
        Naming.guessFromFileName("03 - Daft Punk - One More Time (Official Video).mp3").let {
            assertEquals(3, it.track); assertEquals("Daft Punk", it.artist); assertEquals("One More Time", it.title)
        }
        Naming.guessFromFileName("Zeynel Karabulut-Tanrı Uludur.mp3").let {
            assertEquals("Zeynel Karabulut", it.artist); assertEquals("Tanrı Uludur", it.title)
        }
        Naming.guessFromFileName("Can Yine Bülbül Oldu_spotdown.org (1).mp3").let {
            assertNull(it.artist); assertEquals("Can Yine Bülbül Oldu", it.title)
        }
        Naming.guessFromFileName("Jay-Z.mp3").let { assertNull(it.artist); assertEquals("Jay-Z", it.title) }
        Naming.guessFromFileName("12. Rhalata.flac").let { assertEquals(12, it.track); assertEquals("Rhalata", it.title) }
        // real files from the phone (YouTube downloads)
        Naming.guessFromFileName("Muhammed_dir Cemal-i Hakk_a Mirat [CLyYsDYbd6A].mp3").let {
            assertNull(it.artist); assertEquals("Muhammed dir Cemal-i Hakk a Mirat", it.title)
        }
        Naming.guessFromFileName("Ya İmame_r-Rusli [9wH5a4_jz24].mp3").let { assertNull(it.artist); assertEquals("Ya İmame r-Rusli", it.title) }
        Naming.guessFromFileName("＂İT OĞLU İTLER＂ 2010 Ozan Erhan ÇERKEZOĞLU [2uGEsc0-abc].mp3").let {
            assertNull(it.artist); assertEquals("＂İT OĞLU İTLER＂ 2010 Ozan Erhan ÇERKEZOĞLU", it.title)
        }
    }

    @Test fun noBogusArtistSplits() {
        Naming.guessFromFileName("Ozan Erhan ÇERKEZOĞLU ＂Çağa Bak!＂ Şiir - 2020 [y2TYufKpkI8].mp3").let {
            assertNull(it.artist); assertEquals("Ozan Erhan ÇERKEZOĞLU ＂Çağa Bak!＂ Şiir - 2020", it.title)
        }
        assertNull(Naming.guessFromFileName("＂İZMİR-UYGAR TÜRK DERNEĞİ＂ 2025 [JBoFQ_BcBWs].mp3").artist)
        assertNull(Naming.guessFromFileName("＂KERKÜK_ÜM＂ YALNIZ (DU-TAR ile) 2017 [AeyCX8uVTRw].mp3").artist)
        assertEquals("Zeynel Karabulut", Naming.guessFromFileName("Zeynel Karabulut - Yeter.m4a").artist)
        assertNotNull(Classify.skipReason("Documents/XMusic/Backups/All data backup/", "AutoBackup_1.wav", 60_000))
    }

    @Test fun untaggedFilesKeepTheirFolderAsAlbum() {
        // no artist tag: the folder names the artist ("dervish"), and is then no album name
        val t = Naming.resolve(RawTags(), "Yine Aşkın Sırrı [Cx5FjvUuxcM].mp3", "Download/Müzik-20260731T164646Z-1-001/Müzik/dervish/")
        assertEquals("dervish", Naming.folderArtist(t))
        assertEquals("Singles", Naming.folderAlbum(t))
        assertEquals("", t.artist) // dedupe still compares real tags only
        // already organised under Unknown Artist/<folder>/ by an older version: stays put
        val old = Naming.resolve(RawTags(), "Yine Aşkın Sırrı.mp3", "Music/LocalMusic/Unknown Artist/dervish/")
        assertEquals("Unknown Artist", Naming.folderArtist(old))
        assertEquals("dervish", Naming.folderAlbum(old))
        assertEquals("Yine Aşkın Sırrı.mp3", Naming.fileName(t, "mp3"))
        val s = Naming.resolve(RawTags(title = "Die Wildmagier", artist = "SureAI"), "x.m4a", "Download/Echo/Barden von Enderal (DE) German/")
        assertEquals("Barden von Enderal (DE) German", Naming.folderAlbum(s))
        assertEquals("", s.album) // dedupe still compares real tags only
        assertEquals("", Naming.sourceFolder("Download/Echo/"))
        assertEquals("", Naming.sourceFolder("Download/"))
        assertEquals("", Naming.sourceFolder("Download/10534318-1bd2-4485-820e-4b43fc6e7161/"))
        assertEquals("", Naming.sourceFolder("Music/Forgotten Stories Soundtrack/MP3/"))
        assertEquals("Forgotten Stories Soundtrack", Naming.sourceFolder("Music/Forgotten Stories Soundtrack/"))
        assertEquals("dervish", Naming.sourceFolder("Music/LocalMusic/Unknown Artist/dervish/")) // stays put on a re-run
    }

    @Test fun artistFromFolder() {
        fun a(rel: String) = Naming.artistFromFolder(rel)
        // Plain Youtube: Music/PlainDownload/<Channel>/ and Movies/PlainDownload/<Channel>/
        assertEquals("Some Channel", a("Music/PlainDownload/Some Channel/"))
        assertEquals("Some Channel", a("Movies/PlainDownload/Some Channel/"))
        assertEquals("Tarkan", a("Music/PlainDownload/Tarkan - Topic/")) // YouTube auto channels
        // nearest meaningful folder, generic ones skipped
        assertEquals("dervish", a("Download/Müzik-20260731T164646Z-1-001/Müzik/dervish/"))
        assertEquals("Sezen Aksu", a("Download/Sezen Aksu/"))
        assertEquals("Sezen Aksu", a("Music/Sezen Aksu/CD 2/"))
        assertEquals("Karma", a("Music/Tarkan/Karma/")) // not clearly an album: the nearest folder wins
        // clearly an album folder: the artist is above it, or in "Artist - Album (Year)"
        assertEquals("Tarkan", a("Music/Tarkan/Karma (2001)/"))
        assertEquals("Tarkan", a("Music/Tarkan/2001 - Karma/CD1/"))
        assertEquals("Marvin Kopp", a("Music/Marvin Kopp/Enderal OST/"))
        assertEquals("Pink Floyd", a("Download/Pink Floyd - The Wall (1979)/"))
        assertEquals("Karma (2001)", a("Music/Karma (2001)/")) // nothing above it: the folder itself
        // organised library: the artist level, Unknown Artist/<folder> keeps the folder
        assertEquals("Sezen Aksu", a("Music/LocalMusic/Sezen Aksu/Singles/"))
        assertEquals("Sezen Aksu", a("Music/LocalMusic/Sezen Aksu/Deliveren/"))
        assertEquals("dervish", a("Music/LocalMusic/Unknown Artist/dervish/"))
        // nothing usable: stays "Unknown Artist"
        for (rel in listOf("", "Music/", "Download/", "Music/PlainDownload/", "Bluetooth/", "Download/WhatsApp Audio/", "Music/123/",
            "Download/Unknown/", "Music/LocalMusic/Unknown Artist/Singles/", "Download/10534318-1bd2-4485-820e-4b43fc6e7161/", "Sounds/")) {
            assertNull(rel, a(rel))
        }
    }

    @Test fun organiserUsesFolderArtistAndStaysStable() {
        val t = Naming.resolve(RawTags(), "Some Title.m4a", "Music/PlainDownload/Some Channel/")
        assertEquals("Some Title", t.title)
        assertEquals("Some Channel", Naming.folderArtist(t))
        assertEquals("Singles", Naming.folderAlbum(t)) // not Some Channel/Some Channel/
        val album = Naming.resolve(RawTags(), "x.mp3", "Music/Tarkan/Karma (2001)/")
        assertEquals("Tarkan", Naming.folderArtist(album)); assertEquals("Karma (2001)", Naming.folderAlbum(album))
        val tagged = Naming.resolve(RawTags(title = "T", artist = "Real Artist"), "t.m4a", "Music/PlainDownload/Some Channel/")
        assertEquals("Real Artist", Naming.folderArtist(tagged)) // tags win

        fun f(id: Long, rel: String, name: String) = FileInfo(id, rel, name, 1000 + id, 200_000, "audio/mp4", 128_000, Naming.resolve(RawTags(), name, rel), null)
        // Plain Youtube's music is already sorted per channel/album: left where it is unless ticked
        val first = Plan.build(listOf(f(1, "Music/PlainDownload/Some Channel/", "Some Title.m4a")))
        assertTrue(first.moves.isEmpty()); assertEquals(1, first.skipped.size)
        val ticked = Plan.build(listOf(f(1, "Music/PlainDownload/Some Channel/", "Some Title.m4a")), include = setOf(1L))
        assertEquals("Music/LocalMusic/Some Channel/Singles/Some Title.m4a", ticked.moves.single().toPath)
        val other = Plan.build(listOf(f(3, "Download/Some Channel/", "Other Title.m4a")))
        assertEquals("Music/LocalMusic/Some Channel/Singles/Other Title.m4a", other.moves.single().toPath)
        // re-run on the moved file and on files an older version put under Unknown Artist/<folder>/: nothing moves
        val again = Plan.build(listOf(
            f(1, "Music/LocalMusic/Some Channel/Singles/", "Some Title.m4a"),
            f(2, "Music/LocalMusic/Unknown Artist/dervish/", "Yine.mp3"),
        ))
        assertTrue(again.moves.isEmpty())
        assertEquals(2, again.inPlace.size)
    }

    @Test fun plainYoutubeTree() {
        fun a(rel: String) = Naming.artistFromFolder(rel)
        fun al(rel: String) = PlainYoutube.album(rel, rel.trimEnd('/').substringAfterLast('/')) // as MediaStore fills ALBUM
        // saved music: <Channel>/<Album>/ or just <Channel>/ (older downloads)
        assertEquals("Tarkan", a("Music/PlainDownload/Tarkan - Topic/Karma/")); assertEquals("Karma", al("Music/PlainDownload/Tarkan - Topic/Karma/"))
        assertEquals("Some Channel", a("Music/PlainDownload/Some Channel/")); assertEquals("", al("Music/PlainDownload/Some Channel/"))
        // no year or keyword needed for the album level here (the generic walk would have said "Live Sessions" is the artist)
        assertEquals("Some Channel", a("Music/PlainDownload/Some Channel/Live Sessions/"))
        // cached music, both depths
        assertEquals("Some Channel", a("Music/PlainDownload/Cached/Some Channel/Album X/")); assertEquals("Album X", al("Music/PlainDownload/Cached/Some Channel/Album X/"))
        assertEquals("Some Channel", a("Music/PlainDownload/Cached/Some Channel/")); assertEquals("", al("Music/PlainDownload/Cached/Some Channel/"))
        // videos, saved and cached
        assertEquals("Vid Channel", a("Movies/PlainDownload/Vid Channel/")); assertEquals("", PlainYoutube.album("Movies/PlainDownload/Vid Channel/", ""))
        assertEquals("Vid Channel", a("Movies/PlainDownload/Cached/Vid Channel - Topic/"))
        assertTrue(Videos.isLibraryVideo("Movies/PlainDownload/Cached/Vid Channel/")) // the video query covers the cache
        // a channel named like a generic folder is still the channel; files with no channel folder: no artist
        assertEquals("Music", a("Music/PlainDownload/Music/"))
        assertNull(a("Music/PlainDownload/")); assertNull(a("Music/PlainDownload/Cached/"))
        // a real album tag wins over the folder
        assertEquals("Real Album", PlainYoutube.album("Music/PlainDownload/Ch/Folder/", "Real Album"))
        assertEquals("Elsewhere", PlainYoutube.album("Music/Other/", "Elsewhere"))
        // cached flag, folder titles
        assertTrue(PlainYoutube.isCached("Music/PlainDownload/Cached/Ch/")); assertTrue(PlainYoutube.isCached("movies/plaindownload/cached/Ch/"))
        assertFalse(PlainYoutube.isCached("Music/PlainDownload/Ch/")); assertFalse(PlainYoutube.isCached("Download/Cached/Ch/"))
        assertEquals("Ch › Album", PlainYoutube.folderTitle("Music/PlainDownload/Ch/Album"))
        assertEquals("Ch", PlainYoutube.folderTitle("Music/PlainDownload/Ch - Topic"))
        assertEquals("Cached › Ch › Album", PlainYoutube.folderTitle("Music/PlainDownload/Cached/Ch/Album"))
        assertNull(PlainYoutube.folderTitle("Music/Other"))
    }

    @Test fun organiserNeverTouchesPlainYoutubeCache() {
        fun f(id: Long, rel: String, name: String, size: Long = 5000) =
            FileInfo(id, rel, name, size, 200_000, "audio/mp4", 128_000, Naming.resolve(RawTags(title = "Same Song", artist = "A"), name, rel), null)
        val files = listOf(
            f(1, "Music/PlainDownload/Cached/Ch/Album/", "Same Song.m4a"),
            f(2, "Music/PlainDownload/Ch/Album/", "Same Song.m4a"),
            f(3, "Download/", "Same Song.m4a", size = 4000),
        )
        val p = Plan.build(files, include = setOf(1L)) // even ticked, the cache stays out
        assertTrue(p.removals.none { it.file.id == 1L || it.keep.id == 1L })
        assertTrue(p.moves.none { it.file.id == 1L || it.file.id == 2L })
        assertTrue(p.removals.none { it.file.id == 2L || it.keep.id == 2L }) // saved PlainDownload music is not ticked either
        assertEquals(setOf(1L, 2L), p.skipped.map { it.id }.toSet())
        assertEquals("cached by Plain Youtube (it manages these)", PlainYoutube.organiserSkip("Music/PlainDownload/Cached/Ch/"))
        assertNull(PlainYoutube.organiserSkip("Movies/PlainDownload/Ch/"))
    }

    @Test fun resolveUsesTagsThenFileName() {
        val t = Naming.resolve(RawTags(title = "Rhalata", artist = "Marvin Kopp", album = "Forgotten Stories", track = "2/14"), "x.flac")
        assertEquals(2, t.track); assertFalse(t.fromFileName)
        assertEquals("Music/LocalMusic/Marvin Kopp/Forgotten Stories/", Naming.ROOT + Naming.folderArtist(t) + "/" + Naming.folderAlbum(t) + "/")
        assertEquals("02 Rhalata.flac", Naming.fileName(t, "FLAC"))

        val u = Naming.resolve(RawTags(title = "Track 5", artist = "<unknown>"), "Artist X - Song Y.m4a")
        assertTrue(u.fromFileName); assertEquals("Song Y", u.title); assertEquals("Artist X", u.artist)
        assertEquals("Unknown Artist", Naming.folderArtist(Naming.resolve(RawTags(), "song.mp3")))
        assertEquals("Singles", Naming.folderAlbum(u))
        assertEquals("Song Y.m4a", Naming.fileName(u, "m4a"))
    }

    @Test fun mediaStoreTrackNumbersAndDiscs() {
        assertEquals(3, Naming.parseTrack("1003"))
        assertEquals(2, Naming.parseDisc(null, "2005"))
        val t = Naming.resolve(RawTags(title = "A", track = "7", disc = "2/2"), "a.mp3")
        assertEquals("2-07 A.mp3", Naming.fileName(t, "mp3"))
    }

    @Test fun featuredArtistsShareTheFolder() {
        val t = Naming.resolve(RawTags(title = "S", artist = "Sezen Aksu feat. Tarkan"), "s.mp3")
        assertEquals("Sezen Aksu", Naming.folderArtist(t))
        val v = Naming.resolve(RawTags(title = "S", artist = "X", albumArtist = "Various Artists"), "s.mp3")
        assertEquals("Various Artists", Naming.folderArtist(v))
    }

    @Test fun uniqueNamesAreCaseInsensitive() {
        val taken = HashSet<String>()
        assertEquals("Song.mp3", Naming.unique("d/", "Song.mp3", taken))
        assertEquals("song (2).mp3", Naming.unique("d/", "song.mp3", taken))
        assertEquals("Song (3).mp3", Naming.unique("d/", "Song.mp3", taken))
        assertEquals("Song.mp3", Naming.unique("e/", "Song.mp3", taken))
    }

    @Test fun canonicalSpelling() {
        val m = Naming.canonicalNames(listOf("Marvin Kopp", "marvin kopp", "Marvin Kopp", "Other"))
        assertEquals("Marvin Kopp", m["marvin kopp"])
    }
}

class TextTest {
    @Test fun foldHandlesTurkish() {
        assertEquals("tanri uludur", Text.fold("Tanrı Uludur"))
        assertEquals("istanbul", Text.fold("İSTANBUL"))
        assertEquals("cagri", Text.fold("Çağrı"))
    }

    @Test fun searchTokens() {
        val s = Text.fold("Bülbül Oldu\u0001Can Yine\u0001Album")
        assertTrue(Text.matches(s, Text.tokens("bulbul can")))
        assertFalse(Text.matches(s, Text.tokens("bulbul x")))
    }

    @Test fun titleAndArtistKeys() {
        assertEquals(Text.titleKey("One More Time"), Text.titleKey("01 - One More Time (Official Video)"))
        assertEquals(Text.titleKey("Song"), Text.titleKey("Song feat. Someone"))
        assertFalse(Text.titleKey("Song (Remix)") == Text.titleKey("Song"))
        assertEquals(Text.artistKey("Daft Punk"), Text.artistKey("Daft Punk - Topic"))
        assertEquals(Text.artistKey("Beatles"), Text.artistKey("The Beatles"))
    }

    @Test fun time() {
        assertEquals("0:00", Text.time(0))
        assertEquals("3:05", Text.time(185_000))
        assertEquals("1:00:01", Text.time(3_601_000))
    }
}

class DedupeTest {
    private var nextId = 1L
    private fun f(
        path: String, size: Long = 5_000_000, dur: Long = 200_000, mime: String = "audio/mpeg", bitrate: Int = 192_000,
        title: String = "Song", artist: String = "Artist", album: String = "Album", hash: String? = null, skip: String? = null,
    ): FileInfo {
        val name = path.substringAfterLast('/')
        val rel = path.substring(0, path.length - name.length)
        return FileInfo(nextId++, rel, name, size, dur, mime, bitrate,
            Tags(title, artist, "", album, 0, 1, "", false), skip, hash)
    }

    @Test fun onlySameSizedFilesAreHashed() {
        val a = f("x/a.mp3", size = 10); val b = f("x/b.mp3", size = 10); val c = f("x/c.mp3", size = 11)
        assertEquals(setOf(a.id, b.id), Dedupe.needsHash(listOf(a, b, c)).map { it.id }.toSet())
    }

    @Test fun identicalFilesKeepTheOrganisedOrTaggedCopy() {
        val a = f("Download/Echo/Song.mp3", hash = "h")
        val b = f("Music/LocalMusic/Artist/Album/Song.mp3", hash = "h")
        val g = Dedupe.find(listOf(a, b)).single()
        assertEquals(b.id, g.keep.id)
        assertTrue(g.identical)
    }

    @Test fun lowerQualityCopyOfSameSongIsRemoved() {
        val flac = f("Music/OST/01 Song.flac", size = 30_000_000, mime = "audio/flac", bitrate = 900_000)
        val mp3 = f("Music/OST/01 Song.mp3", size = 5_000_000, dur = 201_000)
        val mp3b = f("Download/Song.mp3", size = 8_000_000, dur = 199_500, bitrate = 320_000, title = "Song (Official Audio)")
        val g = Dedupe.find(listOf(mp3, flac, mp3b)).single()
        assertEquals(flac.id, g.keep.id)
        assertEquals(listOf(mp3b.id, mp3.id), g.remove.map { it.id }) // 320k before 192k
        assertFalse(g.identical)
    }

    @Test fun differentSongsAreNotDuplicates() {
        val a = f("a/Song.mp3", dur = 200_000)
        val b = f("b/Song.mp3", dur = 230_000)                 // other length
        val c = f("c/Song.mp3", artist = "Someone Else")       // other artist
        val d = f("d/Song.mp3", album = "Other Album")         // same title on another album (e.g. OSTs)
        val e = f("e/Song (Remix).mp3", title = "Song (Remix)")
        assertTrue(Dedupe.find(listOf(a, b, c, d, e)).isEmpty())
    }

    @Test fun missingAlbumStillMatches() {
        val a = f("Music/LocalMusic/Artist/Album/Song.m4a", mime = "audio/mp4", bitrate = 128_000)
        val b = f("Download/Echo/Artist - Song.mp3", album = "", bitrate = 256_000)
        val g = Dedupe.find(listOf(a, b)).single()
        assertEquals(b.id, g.keep.id)
    }

    @Test fun vbrNoiseDoesNotBeatTaggedOrganisedCopy() {
        val a = f("Music/LocalMusic/Artist/Album/Song.mp3", bitrate = 190_000)
        val b = f("Download/Song.mp3", bitrate = 193_000)
        assertEquals(a.id, Dedupe.find(listOf(b, a)).single().keep.id)
    }

    @Test fun planMovesRemovesAndSkips() {
        val keep = f("Download/Echo/Artist - Song.flac", mime = "audio/flac", bitrate = 800_000, size = 20_000_000)
        val dup = f("Download/Song.mp3")
        val other = f("Music/X/Other.mp3", title = "Other", size = 4_000_000, dur = 100_000)
        val voice = f("Music/SoundRecorder/rec1.aac", title = "rec1", skip = "in “SoundRecorder” folder", size = 1, dur = 50_000)
        val placed = f("Music/LocalMusic/Artist/Album/Placed.mp3", title = "Placed", size = 2, dur = 70_000)

        val plan = Plan.build(listOf(keep, dup, other, voice, placed))
        assertEquals(listOf(dup.id), plan.removals.map { it.file.id })
        assertEquals(dup.size, plan.bytesFreed)
        assertEquals(listOf(voice.id), plan.skipped.map { it.id })
        assertEquals(listOf(placed.id), plan.inPlace.map { it.id })
        assertEquals(
            setOf("Music/LocalMusic/Artist/Album/Song.flac", "Music/LocalMusic/Artist/Album/Other.mp3"),
            plan.moves.map { it.toPath }.toSet(),
        )

        // user overrides: keep the duplicate, don't move "other", include the recording
        val p2 = Plan.build(listOf(keep, dup, other, voice, placed), keepDuplicate = setOf(dup.id), dontMove = setOf(other.id), include = setOf(voice.id))
        assertTrue(p2.removals.isEmpty())
        assertEquals(listOf(other.id), p2.keptByUser.map { it.id })
        val names = p2.moves.associate { it.file.id to it.toName }
        assertEquals("Song.flac", names[keep.id])
        assertEquals("Song.mp3", names[dup.id])
        assertNotNull(names[voice.id])
        assertTrue(p2.skipped.isEmpty())
    }

    @Test fun mergedAlbumsShareOneFolder() {
        fun g(path: String, artist: String, album: String, title: String) = f(path, title = title, artist = artist, album = album, size = path.length.toLong(), dur = 100_000L + path.length * 3000)
        val a = g("x/1.flac", "Marvin Kopp", "Enderal OST", "One")
        val b = g("x/22.flac", "Marvin Kopp", "Enderal OST", "Two")
        val c = g("y/333.flac", "Simon W. Autenrieth", "Enderal OST", "Three")
        val d = g("z/4444.mp3", "Sagopa Kajmer", "Romantizma 2015 (Re-Mastered)", "Four")
        val e = g("z/55555.mp3", "Sagopa Kajmer", "Romantizma 2015 (Re-Mastered)", "Five")
        val h = g("w/666666.mp3", "Sagopa Kajmer", "Romantizma", "Six")
        val dirs = Plan.build(listOf(a, b, c, d, e, h)).moves.associate { it.file.id to it.toDir }
        assertEquals("Music/LocalMusic/Marvin Kopp/Enderal OST/", dirs[c.id])
        assertEquals("Music/LocalMusic/Sagopa Kajmer/Romantizma 2015 (Re-Mastered)/", dirs[h.id])
    }

    @Test fun reRunIsStable() {
        // untagged file whose artist came from its old file name: the artist folder keeps it
        val t = Naming.resolve(RawTags(), "Tanrı Uludur.mp3", "Music/LocalMusic/Zeynel Karabulut/Singles/")
        assertEquals("Zeynel Karabulut", Naming.folderArtist(t))
        assertEquals("Unknown Artist", Naming.folderArtist(Naming.resolve(RawTags(), "x.mp3", "Music/LocalMusic/Unknown Artist/dervish/")))
        // "Song (2).mp3" from an earlier clash stays where it is
        val a = f("Music/LocalMusic/Artist/Album/Song.mp3", dur = 100_000)
        val b = f("Music/LocalMusic/Artist/Album/Song (2).mp3", dur = 300_000, size = 7)
        val plan = Plan.build(listOf(a, b))
        assertTrue(plan.moves.isEmpty())
        assertEquals(2, plan.inPlace.size)
        assertTrue(Naming.isNumberedVariant("Song (12).mp3", "song.mp3"))
        assertFalse(Naming.isNumberedVariant("Song 2.mp3", "Song.mp3"))
    }

    @Test fun nameClashesGetNumberedWorstLast() {
        val a = f("x/Song.mp3", bitrate = 128_000, dur = 100_000)
        val b = f("y/Song.mp3", bitrate = 320_000, dur = 300_000) // same name, different song length -> not a dup
        val plan = Plan.build(listOf(a, b))
        assertTrue(plan.removals.isEmpty())
        val names = plan.moves.associate { it.file.id to it.toName }
        assertEquals("Song.mp3", names[b.id])
        assertEquals("Song (2).mp3", names[a.id])
    }

    @Test fun classify() {
        assertNotNull(Classify.skipReason("Music/SoundRecorder/", "a.aac", 60_000))
        assertNotNull(Classify.skipReason("Notifications/", "Slack - Ding.mp3", 1_000, notification = true))
        assertNotNull(Classify.skipReason("Download/", "Audio from Someone.mp3", 60_000))
        assertNotNull(Classify.skipReason("Download/", "Recording (202).mp3", 60_000))
        assertNotNull(Classify.skipReason("Download/", "jingle.mp3", 5_000))
        assertNull(Classify.skipReason("Download/Echo/Album/", "Intro (Skit).m4a", 15_000, hasTrackNumber = true))
        assertNull(Classify.skipReason("Download/Echo/", "Artist - Song.m4a", 200_000))
        assertNull(Classify.skipReason("Music/Audio Media Converter/", "x.mp3", 200_000))
    }
}

class QueueTest {
    private val ids = (1L..10L).toList()

    @Test fun shuffleKeepsCurrentFirstAndIsAPermutation() {
        val q = PlayQueue(Random(42))
        q.set(ids, 4, shuffle = true)
        assertEquals(5L, q.current())
        assertEquals(0, q.pos)
        assertEquals(ids.toSet(), q.playOrder().toSet())
        assertEquals(10, q.playOrder().size)
        q.jump(6)
        val cur = q.current()
        q.setShuffle(false)
        assertEquals(cur, q.current())
        assertEquals(ids, q.playOrder())
    }

    @Test fun repeatModes() {
        val q = PlayQueue()
        q.set(ids, 9, false)
        assertNull(q.peekNext(auto = true))
        q.repeat = Repeat.ALL
        assertEquals(0, q.peekNext(auto = true))
        q.repeat = Repeat.ONE
        assertEquals(9, q.peekNext(auto = true))
        assertEquals(0, q.peekNext(auto = false)) // skipping still moves on
        q.jump(0)
        q.repeat = Repeat.OFF
        assertEquals(0, q.peekPrev())
        q.repeat = Repeat.ALL
        assertEquals(9, q.peekPrev())
        assertEquals(Repeat.ONE, Repeat.ALL.next())
        assertEquals(Repeat.OFF, Repeat.ONE.next())
    }

    @Test fun moveAndRemoveKeepCurrent() {
        val q = PlayQueue()
        q.set(ids, 3, false) // current = 4
        q.move(0, 5)
        assertEquals(4L, q.current()); assertEquals(2, q.pos)
        q.move(2, 0)
        assertEquals(4L, q.current()); assertEquals(0, q.pos)
        q.removeAt(5)
        assertEquals(4L, q.current())
        q.removeAt(0) // removing the current: next one takes its place
        assertEquals(8, q.size)
        assertEquals(q.playOrder()[0], q.current())
    }

    @Test fun removeInShuffleFixesIndices() {
        val q = PlayQueue(Random(1))
        q.set(ids, 0, true)
        val before = q.playOrder()
        q.removeAt(3)
        assertEquals(before.filterIndexed { i, _ -> i != 3 }, q.playOrder())
        q.retain { it % 2L == 0L || it == q.current() }
        assertTrue(q.playOrder().all { it % 2L == 0L || it == 1L })
    }

    @Test fun playNextAndAddToEnd() {
        val q = PlayQueue(Random(3))
        q.set(ids, 2, false)
        q.playNext(99)
        assertEquals(99L, q.idAt(q.peekNext(false)!!))
        q.setShuffle(true)
        q.playNext(77)
        assertEquals(77L, q.idAt(q.peekNext(false)!!))
        q.addToEnd(listOf(55))
        assertEquals(55L, q.playOrder().last())
        assertEquals(13, q.size)
    }

    @Test fun saveRestoreRoundTrip() {
        val q = PlayQueue(Random(5))
        q.set(ids, 3, true); q.repeat = Repeat.ONE; q.jump(4)
        val r = PlayQueue(); r.restore(q.save())
        assertEquals(q.playOrder(), r.playOrder()); assertEquals(q.current(), r.current())
        assertTrue(r.shuffle); assertEquals(Repeat.ONE, r.repeat)
        val broken = PlayQueue(); broken.restore(mapOf("items" to "1,2,3", "order" to "0,0,1", "pos" to "9"))
        assertEquals(listOf(1L, 2L, 3L), broken.playOrder()); assertEquals(3L, broken.current())
    }
}

class LibraryTest {
    @Test fun untaggedSongsGetTheirFolderArtist() {
        fun u(id: Long, rel: String, artist: String = "", video: Boolean = false) =
            Song(id, "s$id", artist, "", "", -1, "", 0, 1, 0, 180_000, rel, "s$id.m4a", 1, 0, isVideo = video)
        val lib = LibraryIndex(listOf(
            u(1, "Music/PlainDownload/Channel A/"),
            u(2, "Music/PlainDownload/Channel A/"),
            u(3, "Movies/PlainDownload/Channel A/", video = true), // a video of the same channel joins the artist
            u(4, "Music/PlainDownload/Channel B - Topic/"),
            u(5, "Download/"),
            u(6, "Music/PlainDownload/Channel B - Topic/", artist = "Tagged Artist"),
        ))
        assertEquals(listOf("Channel A", "Channel B", "Tagged Artist", "Unknown Artist"), lib.artists.map { it.title })
        assertEquals(listOf(1L, 2L, 3L), lib.artists.first { it.title == "Channel A" }.songs.map { it.id }.sorted())
        val s1 = lib.byId.getValue(1)
        assertEquals("Channel A", s1.shownArtist); assertEquals("Channel A", s1.inferredArtist); assertEquals("Channel A", s1.subtitle)
        assertEquals("Unknown Artist", lib.byId.getValue(5).shownArtist)
        assertNull(lib.byId.getValue(6).inferredArtist) // tags win
        assertEquals(listOf(4L), lib.search("channel b").map { it.id })
        assertTrue(lib.byId.getValue(3).isVideo); assertFalse(s1.isVideo)
        assertFalse(s1.cached)
        val cached = u(7, "Music/PlainDownload/Cached/Channel A/Album/")
        assertTrue(cached.cached); assertEquals("Channel A", cached.mainArtist)
        // a playlist entry whose cached file was evicted is skipped, not a crash
        val pl = Playlist(1, "P", mutableListOf(Playlist.Entry(7, cached.path), Playlist.Entry(1, s1.path)))
        assertEquals(listOf(1L), pl.resolve(lib.byId, lib.songs.associateBy { it.path.lowercase() }).map { it.id })
    }

    @Test fun onlyPlainDownloadVideosAreLibraryVideos() {
        assertTrue(Videos.isLibraryVideo("Movies/PlainDownload/Some Channel/"))
        assertTrue(Videos.isLibraryVideo("movies/plaindownload/x/"))
        assertFalse(Videos.isLibraryVideo("DCIM/Camera/"))
        assertFalse(Videos.isLibraryVideo("Movies/"))
        assertFalse(Videos.isLibraryVideo("Movies/PlainDownloadX/"))
        assertFalse(Videos.isLibraryVideo("Movies/Other/PlainDownload/"))
        assertFalse(Videos.isLibraryVideo("Music/PlainDownload/Some Channel/"))
        assertFalse(Videos.isLibraryVideo("Pictures/Screenrecorder/"))
        assertEquals(1080 to 1920, Videos.displaySize(1920, 1080, 90))
        assertEquals(1920 to 1080, Videos.displaySize(1920, 1080, 0))
        // Plain Youtube's audio is music, not "voice notes" or "recordings"
        assertNull(Classify.skipReason("Music/PlainDownload/Some Channel/", "Some Title.opus", 200_000))
        assertNull(Classify.skipReason("Music/PlainDownload/Some Channel/", "Some Title.m4a", 200_000))
    }

    private fun s(id: Long, title: String, artist: String, album: String, track: Int = 0, genre: String = "", rel: String = "Music/") =
        Song(id, title, artist, album, "", id / 10, genre, track, 1, 0, 180_000, rel, "$title.mp3", 1, 0)

    @Test fun categoriesAndSearch() {
        val lib = LibraryIndex(listOf(
            s(11, "B song", "Marvin Kopp", "OST", 2, "Soundtrack"),
            s(12, "A song", "Marvin Kopp", "OST", 1, "Soundtrack"),
            s(21, "Tanrı Uludur", "Zeynel Karabulut feat. X", "", genre = "Folk", rel = "Download/"),
        ))
        assertEquals(listOf("Marvin Kopp", "Zeynel Karabulut"), lib.artists.map { it.title })
        assertEquals(listOf(12L, 11L), lib.albums.first { it.title == "OST" }.songs.map { it.id })
        assertEquals(listOf("Folk", "Soundtrack"), lib.genres.map { it.title })
        assertEquals(listOf("Download", "Music"), lib.folders.map { it.title })
        assertEquals(listOf(21L), lib.search("tanri zeynel").map { it.id })
        assertEquals(2, lib.search("kopp ost").size)
        assertTrue(lib.search("  ").isEmpty())
    }

    private fun t(id: Long, artist: String, album: String, rel: String, albumId: Long = id, track: Int = 0, year: Int = 0, dur: Long = 180_000) =
        Song(id, "t$id", artist, album, "", albumId, "", track, 1, year, dur, rel, "t$id.mp3", 1, 0)

    @Test fun albumsMergeAcrossFoldersArtistsAndEditions() {
        val lib = LibraryIndex(listOf(
            t(1, "Marvin Kopp", "Enderal OST", "Music/LocalMusic/Marvin Kopp/Enderal OST/", 10, track = 1),
            t(2, "Marvin Kopp", "Enderal OST", "Music/LocalMusic/Marvin Kopp/Enderal OST/", 10, track = 2),
            t(3, "Simon W. Autenrieth", "Enderal OST", "Music/LocalMusic/Simon W. Autenrieth/Enderal OST/", 11, track = 3),
            t(4, "Marvin Kopp, Andreas Makusev", "Enderal OST", "Music/LocalMusic/Marvin Kopp, Andreas Makusev/Enderal OST/", 12, track = 4),
            t(5, "Sagopa Kajmer", "Romantizma 2015 (Re-Mastered)", "a/", 20, year = 2015),
            t(6, "Sagopa Kajmer", "Romantizma", "b/", 21, year = 2008),
            t(7, "Sagopa Kajmer", "Singles", "c/", 22),
            t(8, "Other", "Singles", "d/", 23),
        ))
        val ost = lib.albums.single { it.title == "Enderal OST" }
        assertEquals(listOf(1L, 2L, 3L, 4L), ost.songs.map { it.id })
        assertEquals("Marvin Kopp & more", ost.artist)
        assertEquals(1, lib.albums.count { it.title.startsWith("Romantizma") })
        assertEquals(2, lib.albums.count { it.title == "Singles" }) // generic names stay per artist

        val kopp = lib.artists.single { it.title == "Marvin Kopp" } // "Marvin Kopp, Andreas Makusev" merged in
        assertEquals(3, kopp.count)
        assertEquals("3 songs · 1 album · 9 min", kopp.stats)
        val sagopa = lib.artists.single { it.title == "Sagopa Kajmer" }
        assertEquals(2, sagopa.albumCount)
        assertEquals(listOf("Romantizma 2015 (Re-Mastered)", "Singles"), lib.albumsOf(sagopa).map { it.title }.sorted())
        assertEquals(ost, lib.albumOf(lib.byId.getValue(3)))
    }

    @Test fun albumKeys() {
        assertEquals("romantizma", Text.albumKey("Romantizma 2015 (Re-Mastered)"))
        assertEquals("abbey road", Text.albumKey("Abbey Road (Super Deluxe Edition)"))
        assertEquals("1989", Text.albumKey("1989"))
        assertEquals("enderal ost", Text.albumKey("Enderal OST"))
        assertTrue(Text.isGenericAlbum(Text.albumKey("Singles")))
        assertEquals("Marvin Kopp", Text.leadArtist("Marvin Kopp, Andreas Makusev"))
        assertEquals("Simon & Garfunkel", Text.leadArtist("Simon & Garfunkel"))
        assertEquals("5 h 12 min", Text.longTime(312 * 60_000L))
    }

    @Test fun playlistsSurviveRescanAndMoves() {
        val p = Playlist(1, "Hike", mutableListOf(Playlist.Entry(5, "Download/a.mp3"), Playlist.Entry(6, "Music/b.mp3"), Playlist.Entry(7, "gone.mp3")))
        val moved = s(5, "a", "x", "y", rel = "Music/LocalMusic/x/y/") // same id, new path
        val renumbered = Song(60, "b", "x", "y", "", 1, "", 0, 1, 0, 1, "Music/", "b.mp3", 1, 0)
        val byId = listOf(moved, renumbered).associateBy { it.id }
        val byPath = listOf(moved, renumbered).associateBy { it.path.lowercase() }
        assertEquals(listOf(5L, 60L), p.resolve(byId, byPath).map { it.id })
        assertEquals("Music/LocalMusic/x/y/a.mp3", p.entries[0].path)
        assertEquals(60L, p.entries[1].songId)

        val json = PlaylistCodec.encode(listOf(p))
        val back = PlaylistCodec.decode(json).single()
        assertEquals("Hike", back.name); assertEquals(3, back.entries.size); assertEquals(60L, back.entries[1].songId)
        assertTrue(PlaylistCodec.decode("not json").isEmpty())
    }
}

class SectionsTest {
    private fun s(id: Long, artist: String, album: String = "", rel: String = "Music/", added: Long = 0, dur: Long = 180_000, genre: String = "", video: Boolean = false) =
        Song(id, "t$id", artist, album, "", -1, genre, 0, 1, 0, dur, rel, "t$id.m4a", 1, added, isVideo = video)

    @Test fun artistsWithOneSongArePacked() {
        val lib = LibraryIndex(listOf(
            s(1, "Big", "A"), s(2, "Big", "A"), s(3, "Big", "B"),
            s(4, "Pair", "P"), s(5, "Pair", "P"),
            s(6, "Lonely"), s(7, "Zed Solo"), s(8, ""), // "" = Unknown Artist (relPath Music/ names nothing)
        ))
        val one = lib.packedArtists(1)
        assertEquals(listOf("Big", "Pair"), one.groups.map { it.title })
        val p = one.packed!!
        assertEquals(Packing.ONE_OFFS, p.title)
        assertEquals(listOf("Lonely", "Unknown Artist", "Zed Solo"), p.songs.map { it.mainArtist }) // sorted by artist
        assertEquals("3 artists", p.artist)
        val two = Packing.artists(lib.artists, 2)
        assertEquals(listOf("Big"), two.groups.map { it.title }); assertEquals(5, two.packed!!.count)
        // Unknown Artist is packed even when it has many songs
        val unknown = LibraryIndex(listOf(s(1, ""), s(2, ""), s(3, ""), s(4, "Big"), s(5, "Big")))
        assertEquals(listOf("Big"), Packing.artists(unknown.artists, 1).groups.map { it.title })
        assertNull(Packing.artists(LibraryIndex(listOf(s(1, "A"), s(2, "A"))).artists, 1).packed)
        assertSame(lib.packedArtists(1), lib.packedArtists(1)) // computed once per library load
    }

    @Test fun singleTrackAlbumsBecomeOneTile() {
        val lib = LibraryIndex(listOf(
            s(1, "X", "Full Album"), s(2, "X", "Full Album"),
            s(3, "Y", "One Track EP"), s(4, "Z", "Another Single"), s(5, "Z", ""), // "" = Z's Singles with 1 song
        ))
        val p = Packing.albums(lib.albums)
        assertEquals(listOf("Full Album"), p.groups.map { it.title })
        assertEquals(Naming.SINGLES, p.packed!!.title); assertTrue(Packing.isPacked(p.packed!!))
        assertEquals(listOf(3L, 4L, 5L), p.packed!!.songs.map { it.id })
        assertEquals("2 artists", p.packed!!.artist)
        // a lone single-track album stays a normal cover
        assertNull(Packing.albums(LibraryIndex(listOf(s(1, "X", "Full"), s(2, "X", "Full"), s(3, "Y", "Solo"))).albums).packed)
    }

    @Test fun smallGenresGoToOther() {
        val songs = (1L..3L).map { s(it, "A", genre = "Rock") } + listOf(s(4, "B", genre = "Jazz"), s(5, "C", genre = "Folk"), s(6, "D", genre = "Other"))
        val p = Packing.genres(LibraryIndex(songs).genres)
        assertEquals(listOf("Rock"), p.groups.map { it.title })
        assertEquals(Packing.OTHER_GENRE, p.packed!!.title); assertEquals(3, p.packed!!.count)
    }

    @Test fun folderTreeCollapsesAndPacks() {
        val songs = listOf(
            s(1, "a", rel = "Music/Albums/Tarkan/Karma/"), s(2, "a", rel = "Music/Albums/Tarkan/Karma/"),
            s(3, "b", rel = "Music/Albums/Tarkan/Dudu/"), s(4, "b", rel = "Music/Albums/Tarkan/Dudu/"),
            s(5, "c", rel = "Download/One/"), s(6, "c", rel = "Download/Two/"), s(7, "c", rel = "Download/"),
            s(8, "d", rel = "Download/Three/Deep/"), // a single-file chain: packed too
            s(9, "e", rel = "Music/PlainDownload/Ch/Album/"), s(10, "e", rel = "Music/PlainDownload/Ch/Album/"),
            s(11, "f", rel = "Movies/PlainDownload/Cached/Vid/", video = true), s(12, "f", rel = "Music/PlainDownload/Cached/Vid/"),
            s(13, "g", rel = "Documents/Old/"), s(14, "g", rel = "Documents/Old/"),
        )
        val roots = FolderTree.build(songs)
        assertEquals(listOf("Music › Albums › Tarkan", "Download", "Plain Youtube › Ch › Album", "Plain Youtube · cached › Vid", "Documents › Old"), roots.map { it.title })
        val music = roots[0]
        assertEquals(listOf("Dudu", "Karma"), music.children.map { it.title })
        assertEquals("2 folders · 4 songs · 12 min", music.stats)
        val dl = roots[1]
        assertEquals(listOf(FolderTree.LOOSE), dl.children.map { it.title })
        assertEquals(listOf(5L, 6L, 8L), dl.children[0].songs.map { it.id }) // One, Two, Three › Deep: songs by title
        assertEquals(listOf(7L), dl.songs.map { it.id })
        assertEquals(4, dl.count)
        assertEquals(2, roots[3].count) // cached audio and video of one channel together
        assertSame(music.children[1], roots.firstNotNullOf { it.find(music.children[1].key) })
        // a lone single-file folder is not packed
        assertEquals(listOf("A"), FolderTree.build(listOf(s(1, "x", rel = "Download/A/"), s(2, "x", rel = "Download/"))).single().children.map { it.title })
    }

    @Test fun historyRules() {
        assertFalse(PlayHistory.counts(29_000, 300_000))
        assertTrue(PlayHistory.counts(30_000, 300_000))   // 30 s first
        assertTrue(PlayHistory.counts(25_000, 40_000))    // half of a short song first
        assertFalse(PlayHistory.counts(15_000, 40_000))
        assertFalse(PlayHistory.counts(0, 0))

        val h = PlayHistory(max = 3)
        h.record(1, "Music/a.mp3", 100); h.record(2, "Music/b.mp3", 200); h.record(1, "Music/a.mp3", 300)
        h.record(3, "Music/c.mp3", 400); h.record(4, "Music/d.mp3", 500) // over the bound: oldest (b) dropped
        assertEquals(listOf(4L, 3L, 1L), h.entries.sortedByDescending { it.last }.map { it.id })
        val songs = listOf(s(1, "A"), s(3, "C"), Song(40, "t4", "D", "", "", -1, "", 0, 1, 0, 1, "Music/", "d.mp3", 1, 0))
        val byId = songs.associateBy { it.id }
        val byPath = songs.associateBy { it.path.lowercase() }
        // id 4 renumbered to 40: found by path; song 3's file is "t3.m4a", not c.mp3, but the id still matches
        assertEquals(listOf(40L, 3L, 1L), h.recent(byId, byPath).map { it.id })
        assertEquals(listOf(1L to 2), h.most(byId, byPath).map { it.first.id to it.second })
        val back = PlayHistory.decode(h.encode())
        assertEquals(h.encode(), back.encode())
        assertTrue(PlayHistory.decode("garbage\n1\tx").entries.isEmpty())
        h.clear(); assertTrue(h.entries.isEmpty())
    }

    @Test fun newPiecesBundleThingsThatArrivedTogether() {
        val now = 1_800_000_000L
        val day = 86_400L
        val songs = listOf(
            s(1, "Tarkan", "Karma", added = now - 3600), s(2, "Tarkan", "Karma", added = now - 3500), s(3, "Tarkan", "Karma", added = now - 3400),
            s(4, "", rel = "Music/PlainDownload/Ch/", added = now - 2 * day), s(5, "", rel = "Music/PlainDownload/Ch/", added = now - 2 * day + 60),
            s(6, "", rel = "Music/PlainDownload/Ch/", added = now - 2 * day + 120),
            s(7, "Solo", "Hit", added = now - 60),
            s(8, "Tarkan", "Karma", added = now - 10 * day), // same album, another day: not part of the bundle
            s(9, "Old", "Gone", added = now - 40 * day),     // older than 30 days
        )
        val items = NewPieces.build(songs, now)
        assertEquals(listOf("t7", "Karma", "Ch", "t8"), items.map { it.title })
        assertTrue(items[1].isBundle); assertEquals(listOf(1L, 2L, 3L), items[1].songs.map { it.id }.sorted())
        assertEquals("3 new · Tarkan", items[1].sub)
        assertEquals("3 new", items[2].sub) // a channel bundle (no album)
        assertFalse(items[3].isBundle)
    }
}

class EraseTest {
    private fun s(id: Long, artist: String, album: String = "", rel: String = "Music/", size: Long = 1_000_000, video: Boolean = false) =
        Song(id, "t$id", artist, album, "", -1, "", 0, 1, 0, 180_000, rel, "t$id.m4a", size, 0, isVideo = video)

    private val lib = LibraryIndex(listOf(
        s(1, "Tarkan", "Karma", "Music/Tarkan/Karma/"), s(2, "Tarkan", "Karma", "Music/Tarkan/Karma/"), s(3, "Tarkan", "Dudu", "Music/Tarkan/Dudu/"),
        s(4, "Lonely", "", "Download/"), s(5, "", "", "Download/A/"), s(6, "", "", "Download/B/"),
        s(7, "", rel = "Movies/PlainDownload/Ch/", size = 50_000_000, video = true),
    ))

    @Test fun eraseSetsFromGroups() {
        assertEquals(setOf(1L, 2L), EraseSet.of(lib.albums.single { it.title == "Karma" }).ids)
        assertEquals(setOf(1L, 2L, 3L), EraseSet.of(lib.artists.single { it.title == "Tarkan" }).ids)
        // the "Singles & one-offs" pack: one-song artists Lonely, A, B (from their folders) and the channel Ch
        val pack = EraseSet.of(lib.packedArtists(1).packed!!)
        assertEquals(setOf(4L, 5L, 6L, 7L), pack.ids); assertEquals(4, pack.artists)
        // folders: "Download" root with A/ and B/ packed into Loose files, plus the file directly in it
        val dl = lib.folderTree.single { it.title == "Download" }
        assertEquals(setOf(4L, 5L, 6L), EraseSet.of(dl).ids)
        assertEquals(setOf(5L, 6L), EraseSet.of(dl.children.single { it.loose }).ids)
        // playlist: only songs that still exist; files opened from other apps are never erased
        val pl = Playlist(1, "P", mutableListOf(Playlist.Entry(1, "Music/Tarkan/Karma/t1.m4a"), Playlist.Entry(99, "gone.mp3")))
        assertEquals(setOf(1L), EraseSet.of(pl, lib.byId, lib.byPath).ids)
        val ext = Song(-2, "x", "", "", "", -1, "", 0, 1, 0, 1, "", "x.mp3", 5, 0, externalUri = "content://x")
        assertEquals(setOf(1L), EraseSet.of(listOf(lib.byId.getValue(1), lib.byId.getValue(1), ext)).ids) // each once
    }

    @Test fun eraseSummary() {
        val set = EraseSet.of(lib.songs)
        assertEquals("6 songs · 1 video · 56.0 MB", set.summary)
        assertEquals("1 video · 50.0 MB", EraseSet.of(lib.videos).summary)
        assertEquals("0 songs · 0 B", EraseSet.of(emptyList()).summary)
        assertTrue(EraseSet.of(emptyList()).isEmpty())
        assertTrue(set.preview(2).endsWith("… and 5 more"))
    }

    @Test fun selection() {
        val list = lib.songs.take(4)
        val sel = Selection()
        sel.toggle(list[2].id); sel.toggle(list[0].id)
        assertEquals(listOf(list[0], list[2]), sel.selected(list)) // list order, not pick order
        sel.toggle(list[2].id); assertEquals(1, sel.size)
        sel.toggleAll(list); assertTrue(sel.allSelected(list)); assertEquals(4, sel.size)
        sel.toggleAll(list); assertEquals(0, sel.size) // all selected → none
        sel.toggle(list[1].id); sel.clear(); assertFalse(sel.isSelected(list[1].id))
        assertFalse(sel.allSelected(emptyList()))
    }

    @Test fun trashMath() {
        val now = 1_800_000_000_000L
        assertEquals(30, TrashMath.daysLeft(now / 1000 + 30 * 86_400, now))
        assertEquals(1, TrashMath.daysLeft(now / 1000 + 3600, now))
        assertEquals(0, TrashMath.daysLeft(now / 1000 - 10, now))
        assertTrue(TrashMath.isLow(2_900_000_000)); assertFalse(TrashMath.isLow(3_000_000_000))
        val items = listOf(
            TrashItem(1, "", false, "a", 400_000_000, 300), TrashItem(2, "", false, "b", 400_000_000, 100),
            TrashItem(3, "", true, "c", 400_000_000, 200),
        )
        // oldest trashed (expires first) go first, only as many as needed
        assertEquals(listOf(2L, 3L), TrashMath.toPurge(items, free = 2_300_000_000).map { it.id })
        assertTrue(TrashMath.toPurge(items, free = 3_500_000_000).isEmpty())
        assertEquals(3, TrashMath.toPurge(items, free = 0).size)
        assertEquals("Song.mp3", TrashMath.displayName(".trashed-1767225600-Song.mp3"))
    }
}

class ArtAndTagsTest {
    /** As Library builds it: tags (may be blank), album via PlainYoutube.album (MediaStore puts the folder name in ALBUM when untagged). */
    private fun f(id: Long, rel: String, artistTag: String = "", albumTag: String? = null, size: Long = 1, modified: Long = 0, albumId: Long = -1) =
        Song(id, "t$id", artistTag, PlainYoutube.album(rel, albumTag ?: rel.trimEnd('/').substringAfterLast('/')), "", albumId, "", 0, 1, 0, 180_000, rel, "t$id.m4a", size, 0, modified = modified)

    @Test fun embeddedTagsGroupLikeTheFolders() {
        val lib = LibraryIndex(listOf(
            // untagged (older) and tagged (backfilled) files of one channel/album; the folder name was sanitised
            f(1, "Music/PlainDownload/AC_DC - Topic/Back_ In_Black/"),
            f(2, "Music/PlainDownload/AC_DC - Topic/Back_ In_Black/", artistTag = "AC/DC", albumTag = "Back: In/Black"),
            f(3, "Music/PlainDownload/AC_DC - Topic/", artistTag = "AC/DC"), // no album folder → single
            f(4, "Music/PlainDownload/AC_DC - Topic/"),
            // case and whitespace differences
            f(5, "Music/PlainDownload/Some  Channel/Album X/", artistTag = "some channel ", albumTag = "ALBUM X"),
            f(6, "Music/PlainDownload/Some Channel/Album X/"),
            // a channel name with a comma stays one artist, tagged or not (no "A, B" → "A" split)
            f(7, "Music/PlainDownload/Rock, Paper/", artistTag = "Rock, Paper"),
            f(8, "Music/PlainDownload/Rock, Paper/"),
            // cached files of the same channel join it too
            f(9, "Music/PlainDownload/Cached/Some Channel/Album X/", artistTag = "Some Channel", albumTag = "Album X"),
        ))
        assertEquals(3, lib.artists.size)
        assertEquals(setOf(1L, 2L, 3L, 4L), lib.artists.single { it.key == Text.groupKey("AC/DC") }.songs.map { it.id }.toSet())
        assertEquals(setOf(5L, 6L, 9L), lib.artists.single { it.key == Text.groupKey("Some Channel") }.songs.map { it.id }.toSet())
        assertEquals("Rock, Paper", lib.byId.getValue(7).mainArtist)
        assertEquals(setOf(1L, 2L), lib.albums.single { it.key == Text.groupKey("Back in Black") }.songs.map { it.id }.toSet())
        assertEquals(setOf(5L, 6L, 9L), lib.albums.single { it.songs.any { s -> s.id == 5L } }.songs.map { it.id }.toSet())
        assertEquals(setOf(3L, 4L), lib.albums.single { it.songs.any { s -> s.id == 3L } }.songs.map { it.id }.toSet()) // AC/DC singles
        assertEquals(Text.groupKey("AC/DC"), Text.groupKey("AC_DC"))
        assertEquals("acdc", Text.groupKey(" AC / DC "))
        assertEquals("★", Text.groupKey("★")) // nothing alphanumeric: the folded text itself
        // outside Plain Youtube "A, B" still credits the lead artist
        assertEquals("Marvin Kopp", Song(10, "x", "Marvin Kopp, Andreas Makusev", "", "", -1, "", 0, 1, 0, 1, "Music/", "x.mp3", 1, 0).mainArtist)
    }

    @Test fun artPicks() {
        fun s(id: Long, album: String) = Song(id, "t$id", "A", album, "", -1, "", 0, 1, 0, 1, "Music/", "t$id.mp3", 1, 0)
        val songs = listOf(s(1, "Big"), s(2, "Big"), s(3, "Big"), s(4, "Big"), s(5, "Loved"), s(6, "Loved"), s(7, "Other"))
        // most-played album first (3 tries), then one per other album
        assertEquals(listOf(5L, 6L, 1L, 7L), ArtPick.artist(songs, mapOf(5L to 4, 6L to 1)).map { it.id })
        // no history: the biggest album first
        assertEquals(listOf(1L, 2L, 3L, 5L, 7L), ArtPick.artist(songs, emptyMap()).map { it.id })
        assertEquals(listOf(1L, 5L, 7L), ArtPick.mosaic(songs).map { it.id }) // one per album: different covers
        assertEquals(8, ArtPick.album((1L..20L).map { s(it, "X") }).size)
    }

    @Test fun changedFilesAreFound() {
        val old = listOf(f(1, "Music/PlainDownload/Ch/", size = 100, modified = 10), f(2, "Music/PlainDownload/Ch/", size = 100, modified = 10),
            f(3, "Music/PlainDownload/Ch/", size = 100, modified = 10))
        val new = listOf(f(1, "Music/PlainDownload/Ch/", size = 140, modified = 10), // art embedded: bigger
            f(2, "Music/PlainDownload/Ch/", size = 100, modified = 20),                // rewritten, same size
            f(3, "Music/PlainDownload/Ch/", size = 100, modified = 10), f(4, "Music/PlainDownload/Ch/"))
        assertEquals(setOf(1L, 2L), ArtPick.changed(old, new))
        assertTrue(ArtPick.changed(emptyList(), new).isEmpty())
    }
}

class ControlsTest {
    @Test fun speeds() {
        assertEquals("1×", Speeds.label(1f))
        assertEquals("1.25×", Speeds.label(1.25f))
        assertEquals("0.5×", Speeds.label(0.5f))
        assertEquals(1.5f, Speeds.valid(1.5f), 0f)
        assertEquals(1f, Speeds.valid(3f), 0f) // not offered: back to normal
        assertEquals(1f, Speeds.valid(Float.NaN), 0f)
    }

    @Test fun seekStep() {
        assertEquals(0L, SeekStep.target(4_000, -SeekStep.MS, 200_000)) // not before the start
        assertEquals(60_000L, SeekStep.target(50_000, SeekStep.MS, 200_000))
        assertEquals(199_000L, SeekStep.target(195_000, SeekStep.MS, 200_000)) // stops short of the end
        assertEquals(15_000L, SeekStep.target(5_000, SeekStep.MS, 0)) // unknown length: no upper clamp
    }

    @Test fun volumeMuteRemembersLevel() {
        val v = VolumeModel(15)
        v.sync(9)
        assertEquals(0, v.toggleMute()); assertTrue(v.muted)
        assertEquals(9, v.toggleMute())
        v.sync(0)
        assertEquals(1, VolumeModel(15).apply { sync(0) }.toggleMute()) // muted from outside: comes back at 1 step
        assertEquals(15, v.set(40))
    }

    @Test fun pipRatioIsClamped() {
        assertEquals(1920 to 1080, PipRatio.of(1920, 1080))
        assertEquals(239 to 100, PipRatio.of(3000, 1000))
        assertEquals(100 to 239, PipRatio.of(100, 1000))
        assertEquals(16 to 9, PipRatio.of(0, 0))
    }
}

class SortingTest {
    private fun s(id: Long, title: String, artist: String = "A", album: String = "", year: Int = 0, added: Long = 0, dur: Long = 180_000, track: Int = 0) =
        Song(id, title, artist, album, "", -1, "", track, 1, year, dur, "Music/", "$title.mp3", 1, added)

    private fun ids(l: List<Song>) = l.map { it.id }

    @Test fun songsAlphabeticAndChronological() {
        val list = listOf(
            s(1, "Bravo", "Zed", "Z1", year = 2001, added = 300),
            s(2, "alpha", "Ann", "A1", year = 0, added = 0, dur = 400_000), // unknown year and date
            s(3, "Çay", "Ann", "A1", year = 1999, added = 100),
            s(4, "The Delta", "Bob", "B1", year = 2020, added = 200),
        )
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(Sorting.songs(list, "az"))) // "Çay" folds to "cay", "The Delta" to "delta"
        assertEquals(listOf(4L, 3L, 1L, 2L), ids(Sorting.songs(list, "za")))
        assertEquals(listOf(1L, 4L, 3L, 2L), ids(Sorting.songs(list, "new"))) // unknown date last
        assertEquals(listOf(3L, 4L, 1L, 2L), ids(Sorting.songs(list, "old"))) // ...in both directions
        assertEquals(listOf(4L, 1L, 3L, 2L), ids(Sorting.songs(list, "year")))
        assertEquals(listOf(3L, 1L, 4L, 2L), ids(Sorting.songs(list, "year_old")))
        assertEquals(listOf(2L, 3L, 4L, 1L), ids(Sorting.songs(list, "artist")))
        assertEquals(2L, Sorting.songs(list, "long").first().id)
        assertEquals(listOf(4L, 3L, 2L, 1L), ids(Sorting.songs(list, "last"))) // playlist: last added first
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(Sorting.songs(list, "custom")))
    }

    @Test fun albumsAndArtistsSort() {
        val lib = LibraryIndex(listOf(
            s(1, "x", "Ann", "Beta", year = 2010, added = 50), s(2, "y", "Ann", "Beta", year = 2010, added = 900),
            s(3, "z", "Zed", "Alpha", year = 1990, added = 500),
            s(4, "w", "Bob", "Gamma", year = 0, added = 100), s(5, "v", "Bob", "Gamma", added = 110), s(6, "u", "Bob", "Gamma", added = 120),
        ))
        fun titles(id: String) = Sorting.groups(lib.albums, id).map { it.title }
        assertEquals(listOf("Alpha", "Beta", "Gamma"), titles("az"))
        assertEquals(listOf("Gamma", "Beta", "Alpha"), titles("za"))
        assertEquals(listOf("Beta", "Alpha", "Gamma"), titles("new")) // newest song in the album counts
        assertEquals(listOf("Beta", "Gamma", "Alpha"), titles("old")) // oldest song in the album counts
        assertEquals(listOf("Beta", "Alpha", "Gamma"), titles("year")) // no year: last
        assertEquals(listOf("Alpha", "Beta", "Gamma"), titles("year_old"))
        assertEquals(listOf("Gamma", "Beta", "Alpha"), titles("most"))
        assertEquals(listOf("Beta", "Gamma", "Alpha"), titles("artist"))
        assertEquals(listOf("Bob", "Ann", "Zed"), Sorting.groups(lib.artists, "most").map { it.title })
        assertEquals(listOf("Ann", "Zed", "Bob"), Sorting.groups(lib.artists, "new").map { it.title })
        // the album grid caches one packing per sort
        assertSame(lib.packedAlbums("za"), lib.packedAlbums("za"))
        assertEquals(listOf("Gamma", "Beta", "Alpha"), lib.packedAlbums("za").groups.map { it.title }) // one single track: not packed
    }

    @Test fun savedChoiceAndLegacyPrefs() {
        assertEquals("most", Sorting.option(Sorting.Kind.ARTISTS, null).id)
        assertEquals("az", Sorting.option(Sorting.Kind.ARTISTS, null, legacy = 1).id)
        assertEquals("artist", Sorting.option(Sorting.Kind.ALBUMS, null, legacy = 1).id)
        assertEquals("most", Sorting.option(Sorting.Kind.ALBUMS, null, legacy = 2).id)
        assertEquals("year", Sorting.option(Sorting.Kind.ALBUMS, "year", legacy = 2).id)
        assertEquals("az", Sorting.option(Sorting.Kind.SONGS, "bogus").id)
        assertEquals("custom", Sorting.option(Sorting.Kind.PLAYLIST, null).id)
        for (k in Sorting.Kind.values()) assertEquals(k.name, Sorting.options(k).size, Sorting.options(k).map { it.id }.distinct().size)
    }

    @Test fun playlistsSort() {
        val l = listOf(Triple(1L, "Hike", 5), Triple(2L, "akşam", 12), Triple(3L, "Zen", 1))
        fun names(id: String) = Sorting.playlists(l, id, { it.second }, { it.first }, { it.third }).map { it.second }
        assertEquals(listOf("Hike", "akşam", "Zen"), names("old"))
        assertEquals(listOf("Zen", "akşam", "Hike"), names("new"))
        assertEquals(listOf("akşam", "Hike", "Zen"), names("az"))
        assertEquals(listOf("Zen", "Hike", "akşam"), names("za"))
        assertEquals(listOf("akşam", "Hike", "Zen"), names("most"))
    }
}

class LinkedPlaylistsTest {
    private fun s(id: Long) = Song(id, "t$id", "A", "", "", -1, "", 0, 1, 0, 180_000, "Music/PlainDownload/A/", "t$id.m4a", 1, 0)

    @Test fun savedIndexPrefersAudioAndHonoursForgets() {
        // real ids are 11 characters, and the Youtube tab ends every line with a newline
        val m = LinkedPlaylists.savedIds(
            "aaaaaaaaaaa:V\tcontent://media/external_primary/video/media/10\n" +
            "aaaaaaaaaaa:A\tcontent://media/external_primary/audio/media/11\n" +
            "bbbbbbbbbbb:A\n" +
            "bbbbbbbbbbb:A\tcontent://media/external_primary/audio/media/20\n" +
            "ccccccccccc:A\tcontent://media/external_primary/audio/media/30\n" +
            "-ccccccccccc:A\n" +
            "ddddddddddd:V\tcontent://media/external_primary/video/media/40\n")
        assertEquals(mapOf("aaaaaaaaaaa" to 11L, "bbbbbbbbbbb" to 20L, "ddddddddddd" to 40L), m)
    }

    @Test fun idsStartingWithADashAreEntriesNotForgets() {
        // about 1 in 64 YouTube ids starts with '-': such a line used to be read as "forget", so the song was missing
        // from its linked playlist and the resume mapping, and "Remove duplicates" no longer protected that copy
        val t = "-x8GhAbCdEf:A\tcontent://media/external/audio/media/77\n" +
            "abcdefghijk:A\tcontent://media/external/audio/media/78\n" +
            "-abcdefghijk:A\n" +                                          // forget abcdefghijk:A
            "-x8GhAbCdEf:V\n" +                                           // a mark of the '-' id (no file), not a forget
            "hello world\n" + "plain:A\tfile:///sdcard/x.mp3\n" +         // not a key / not a saved file
            "zzzzzzzzzzz:A\tcontent://media/external/audio/media/8"      // cut short (no newline): ignored
        assertEquals(mapOf("-x8GhAbCdEf" to 77L), LinkedPlaylists.savedIds(t))
        assertEquals(setOf(77L), Duplicates.trackedIds(t))
        // a forget of a '-' id: '-' + "-x8GhAbCdEf:A"
        assertTrue(LinkedPlaylists.savedIds("-x8GhAbCdEf:A\tcontent://media/external/audio/media/77\n--x8GhAbCdEf:A\n").isEmpty())
        // the copy the Youtube tab indexes stays protected from "Remove duplicates"
        fun f(id: Long, path: String) = FileInfo(id, path.substringBeforeLast('/') + "/", path.substringAfterLast('/'), 5, 100_000, "audio/mp4", 128_000,
            Naming.resolve(RawTags(title = "Song", artist = "A"), path.substringAfterLast('/'), ""), null, "h")
        val plan = Duplicates.plan(listOf(f(76, "Music/a/Song.m4a"), f(77, "Music/PlainDownload/A/Song.m4a")), Duplicates.trackedIds(t))
        assertEquals(listOf(76L), plan.removals.map { it.file.id })
    }

    @Test fun playlistFillsInSourceOrderAndKeepsUserEdits() {
        // the same JSON shape app.plain.download.PlaylistLinks.encode writes
        val links = LinkedPlaylists.decodeLinks("""{"v":1,"links":[{"key":"spotify:x","name":"Hike","ids":["v1","v2","v3","v4"],"t":5}]}""")
        val lists = ArrayList<Playlist>().apply { add(Playlist(7, "Mine")) }
        val got = HashMap<String, Song>()
        fun run() = LinkedPlaylists.sync(lists, links) { got[it] }

        assertFalse(run()) // nothing downloaded yet: no playlist
        assertEquals(1, lists.size)
        got["v3"] = s(3)
        assertTrue(run())
        val p = lists.first { it.source == "spotify:x" }
        assertEquals("Hike", p.name); assertEquals(8L, p.id)
        got["v1"] = s(1); got["v4"] = s(4)
        assertTrue(run())
        assertEquals(listOf(1L, 3L, 4L), p.entries.map { it.songId }) // late arrivals go to their place
        p.entries.removeAt(0) // the user removes v1
        got["v2"] = s(2)
        assertTrue(run())
        assertEquals(listOf(2L, 3L, 4L), p.entries.map { it.songId }) // v2 placed before v3; v1 not back
        assertFalse(run())
        // round trip keeps the link
        val back = PlaylistCodec.decode(PlaylistCodec.encode(lists)).first { it.id == 8L }
        assertEquals("spotify:x", back.source); assertEquals(setOf("v1", "v2", "v3", "v4"), back.got)
        assertNull(PlaylistCodec.decode(PlaylistCodec.encode(lists)).first { it.id == 7L }.source)
    }

    @Test fun forgettingALinkDropsOnlyThatOne() {
        val json = """{"v":1,"links":[{"key":"yt:a","name":"A","ids":["1"],"t":1},{"key":"yt:b","name":"B","ids":["2"],"t":2}]}"""
        val out = LinkedPlaylists.dropLink(json, "yt:a")!!
        assertEquals(listOf("yt:b"), LinkedPlaylists.decodeLinks(out).map { it.key })
        assertNull(LinkedPlaylists.dropLink(json, "yt:zzz"))
    }
}

// --- edge cases and stress (2026-11-28 pass) -------------------------------------------------------------------

class QueueEdgeTest {
    /** The old retain: remove one by one from the back. The one-pass version must give exactly the same queue. */
    private fun slowRetain(q: PlayQueue, keep: (Long) -> Boolean) { for (p in q.order.indices.reversed()) if (!keep(q.items[q.order[p]])) q.removeAt(p) }
    private fun slowClear(q: PlayQueue) { for (p in q.size - 1 downTo q.pos + 1) q.removeAt(p) }
    private fun copy(q: PlayQueue) = PlayQueue().apply { restore(q.save()) }
    private fun consistent(q: PlayQueue) {
        assertEquals(q.items.indices.toList(), q.order.sorted())
        if (q.isEmpty()) assertEquals(-1, q.pos) else assertTrue(q.pos in q.order.indices)
    }

    @Test fun onePassRetainAndClearMatchRemovingOneByOne() {
        val rnd = Random(7)
        repeat(600) { round ->
            val n = rnd.nextInt(12)
            val q = PlayQueue(Random(round.toLong()))
            val ids = List(n) { 1L + rnd.nextInt(6) } // the same song can be queued twice
            q.set(ids, if (n == 0) 0 else rnd.nextInt(n), rnd.nextBoolean())
            q.repeat = Repeat.values()[rnd.nextInt(3)]
            if (n > 0) q.jump(rnd.nextInt(n))
            val drop = (1L..6L).filter { rnd.nextBoolean() }.toSet()
            val a = copy(q); val b = copy(q)
            a.retain { it !in drop }; slowRetain(b) { it !in drop }
            assertEquals(b.playOrder(), a.playOrder()); assertEquals(b.pos, a.pos); assertEquals(b.items, a.items)
            consistent(a)
            val c = copy(q); val d = copy(q)
            c.clearAfterCurrent(); slowClear(d)
            assertEquals(d.playOrder(), c.playOrder()); assertEquals(d.pos, c.pos); assertEquals(d.current(), c.current())
            consistent(c)
        }
    }

    @Test fun twentyThousandSongQueueEditsAreFast() {
        val ids = (1L..20_000L).toList()
        val q = PlayQueue(Random(1))
        q.set(ids, 0, true)
        val cur = q.current()
        val t0 = System.nanoTime()
        q.retain { it % 2L == 0L || it == cur } // half the library vanished (an SD card, Plain Youtube's cache)
        assertEquals(cur, q.current()); assertEquals(10_000 + (if (cur!! % 2L == 0L) 0 else 1), q.size)
        q.jump(5000)
        q.clearAfterCurrent()
        assertEquals(5001, q.size)
        q.playNext((100_000L until 120_000L).toList()) // "Play next" on all songs
        assertEquals(25_001, q.size)
        assertEquals(100_000L, q.idAt(q.pos + 1))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("20k queue edits: $ms ms")
        assertTrue("took $ms ms", ms < 2000)
        consistent(q)
    }

    @Test fun playNextKeepsTheOrderAndAnEmptyQueueStartsAtTheFirst() {
        val q = PlayQueue(); q.set(listOf(1L, 2L, 3L), 1, false)
        q.playNext(listOf(10L, 11L, 12L))
        assertEquals(listOf(1L, 2L, 10L, 11L, 12L, 3L), q.playOrder()); assertEquals(2L, q.current())
        val s = PlayQueue(Random(2)); s.set(listOf(1L, 2L, 3L), 0, true)
        s.playNext(listOf(10L, 11L))
        assertEquals(listOf(10L, 11L), s.playOrder().subList(s.pos + 1, s.pos + 3))
        val e = PlayQueue(); e.playNext(listOf(5L, 6L, 7L))
        assertEquals(5L, e.current()) // was 7 (the songs went in one by one, the last one first)
        e.playNext(emptyList()); assertEquals(3, e.size)
        consistent(q); consistent(s); consistent(e)
    }

    @Test fun tinyAndEmptyQueues() {
        val one = PlayQueue(Random(4)); one.setShuffled(listOf(9L))
        assertEquals(9L, one.current()); assertNull(one.peekNext(auto = true))
        one.setShuffle(false); one.setShuffle(true); assertEquals(9L, one.current())
        one.repeat = Repeat.ALL; assertEquals(0, one.peekNext(auto = false)); assertEquals(0, one.peekPrev())
        one.removeAt(0); assertTrue(one.isEmpty()); assertNull(one.current()); assertNull(one.peekNext(false)); assertNull(one.peekPrev())
        one.clearAfterCurrent(); one.retain { false }; one.move(0, 1); one.removeAt(3); one.jump(2); one.setShuffle(false)
        assertTrue(one.isEmpty())
        val e = PlayQueue(); e.setShuffled(emptyList()); assertTrue(e.isEmpty()); assertEquals(-1, e.pos)
        // the last song removed while current, repeat off: the queue itself points at the one before (Player then goes
        // to the first song, paused, like Next at the end); repeat all: back to the first
        val q = PlayQueue(); q.set(listOf(1L, 2L, 3L), 2, false); q.removeAt(2); assertEquals(2L, q.current())
        q.set(listOf(1L, 2L, 3L), 2, false); q.repeat = Repeat.ALL; q.removeAt(2); assertEquals(1L, q.current())
        // garbage from older versions or a cut-off write
        val g = PlayQueue(); g.restore(mapOf("items" to "1,x,,3", "order" to "5,0", "pos" to "-4", "shuffle" to "true", "repeat" to "SOMETIMES"))
        assertEquals(listOf(1L, 3L), g.playOrder()); assertEquals(1L, g.current()); assertFalse(g.shuffle); assertEquals(Repeat.OFF, g.repeat)
        g.restore(mapOf("items" to null, "order" to null, "pos" to null)); assertTrue(g.isEmpty())
    }
}

class ResumeEdgeTest {
    @Test fun unknownLengthsAndOddPlaces() {
        assertEquals(60_000L, SongResume.pick(Triple(60_000L, 0L, 1L), 0))      // nothing knows the length: kept
        assertEquals(0L, SongResume.pick(Triple(175_000L, 180_000L, 1L), 0))    // the stored length says it was nearly done
        assertEquals(60_000L, SongResume.pick(Triple(60_000L, 180_000L, 1L), 0))
        assertEquals(0L, SongResume.pick(Triple(3_000L, 0L, 1L), 200_000))      // barely started
        assertEquals(0L, SongResume.pick(Triple(190_000L, 0L, 1L), 200_000))    // MediaStore's length wins when it has one
        assertEquals(0L, SongResume.pick(Triple(300_000L, 0L, 1L), 200_000))    // past the end (file replaced)
        assertEquals(0L, SongResume.startAt(250_000, 200_000))                  // outside the song: from the start
        assertEquals(50_000L, SongResume.startAt(50_000, 0))                    // unknown length: trusted
        assertEquals(0L, SongResume.startAt(-5, 0))
        assertEquals(0L, SongResume.startAt(200_000, 200_000))
        assertEquals(Triple(5L, 0L, 9L), SongResume.decode("5,9"))              // 3.3's "pos,at"
        assertEquals(Triple(5L, 0L, 0L), SongResume.decode("5,,"))
        assertNull(SongResume.decode("x,1,2")); assertNull(SongResume.decode("")); assertNull(SongResume.decode(null))
        assertFalse(SongResume.worthKeeping(Long.MIN_VALUE, 0)); assertTrue(SongResume.worthKeeping(Long.MAX_VALUE, 0))
    }
}

class SortEdgeTest {
    @Test fun articlesPunctuationAndEmptyTags() {
        assertEquals("beatles", Text.sortKey("The  Beatles"))
        assertEquals("beatles", Text.sortKey("  the Beatles"))
        assertEquals("band", Text.sortKey("The - Band"))
        assertEquals("theatre", Text.sortKey("Theatre"))
        assertEquals("the", Text.sortKey("The"))
        assertEquals("1975", Text.sortKey("The 1975"))
        assertEquals("", Text.sortKey("")); assertEquals("", Text.sortKey("★★"))
        assertEquals("song", Text.sortKey("🎵 Song"))
        // songs without artist / album / year / date sort without trouble, unknowns last
        fun s(id: Long, artist: String, album: String, year: Int) = Song(id, "t$id", artist, album, "", -1, "", 0, 1, year, 1000, "Download/", "t$id.mp3", 1, 0)
        val l = listOf(s(1, "", "", 0), s(2, "Zed", "Z", 2001), s(3, "Ann", "", 1999))
        for (o in Sorting.options(Sorting.Kind.SONGS)) assertEquals(3, Sorting.songs(l, o.id).size)
        assertEquals(listOf(2L, 3L, 1L), Sorting.songs(l, "year").map { it.id })
        assertEquals(listOf(3L, 1L, 2L), Sorting.songs(l, "artist").map { it.id }) // "Unknown Artist" between A and Z
    }

    @Test fun turkishLettersSortWithTheirBaseLetters() {
        val titles = listOf("Zeytin", "Çiçek", "Cem", "Ömür", "Oya", "Şeker", "Su", "Ünal", "Uçak", "İnci", "Irmak", "ılık", "Ağrı", "Ag")
        // accent-insensitive (Ç with C, Ş with S, İ / I / ı with i); raw character order put "Çiçek" after "Zeytin"
        assertEquals(listOf("Ag", "Ağrı", "Cem", "Çiçek", "ılık", "İnci", "Irmak", "Ömür", "Oya", "Şeker", "Su", "Uçak", "Ünal", "Zeytin"),
            titles.sortedBy { Text.sortKey(it) })
        assertEquals(Text.sortKey("ISTANBUL"), Text.sortKey("İstanbul"))
        assertEquals(Text.sortKey("ışık"), Text.sortKey("IŞIK"))
    }

    @Test fun twentyThousandSongsSortQuicklyAndTheSame() {
        val rnd = Random(3)
        val songs = (1L..20_000L).map { i ->
            Song(i, "Şarkı ${rnd.nextInt(5000)}", "The Artist ${rnd.nextInt(800)}", if (i % 7 == 0L) "" else "Albüm ${rnd.nextInt(2000)}", "", -1, "",
                rnd.nextInt(15), 1, if (i % 5 == 0L) 0 else 1990 + rnd.nextInt(30), 120_000L + rnd.nextInt(200_000), "Music/A${i % 300}/", "s$i.mp3", 1, rnd.nextInt(1_000_000).toLong())
        }
        val t0 = System.nanoTime()
        val lib = LibraryIndex(songs)
        val tIndex = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        for (o in Sorting.options(Sorting.Kind.SONGS)) assertEquals(20_000, lib.songsBy(o.id).size)
        for (o in Sorting.options(Sorting.Kind.ALBUMS)) assertEquals(lib.albums.size, Sorting.groups(lib.albums, o.id).size)
        for (o in Sorting.options(Sorting.Kind.ARTISTS)) assertEquals(lib.artists.size, Sorting.groups(lib.artists, o.id).size)
        val tSort = (System.nanoTime() - t1) / 1_000_000
        println("20k songs: index $tIndex ms, all sorts $tSort ms")
        assertTrue("sorting took $tSort ms", tSort < 4000)
        // the cached keys change nothing but the speed
        val t2 = System.nanoTime()
        val direct = lib.songs.sortedWith(compareBy<Song>({ Text.sortKey(it.mainArtist) }, { Text.sortKey(it.albumName) }, { it.disc },
            { if (it.track == 0) Int.MAX_VALUE else it.track }, { it.sortTitle }, { it.id }))
        println("20k songs by artist, folding in the comparator (before): ${(System.nanoTime() - t2) / 1_000_000} ms")
        assertEquals(direct.map { it.id }, lib.songsBy("artist").map { it.id })
        assertEquals(lib.artists.sortedWith(compareBy({ Text.sortKey(it.title) }, { it.key })).map { it.key }, Sorting.groups(lib.artists, "az").map { it.key })
        // search and paging-free lists hold up too
        assertTrue(lib.search("şarkı 12").isNotEmpty())
        assertEquals(20_000, lib.folderTree.sumOf { it.count })
    }

    @Test fun emptyLibrary() {
        val lib = LibraryIndex(emptyList())
        assertTrue(lib.songs.isEmpty() && lib.artists.isEmpty() && lib.albums.isEmpty() && lib.folderTree.isEmpty() && lib.newPieces.isEmpty())
        assertNull(lib.packedArtists(1).packed); assertNull(lib.packedAlbums("az").packed); assertNull(lib.packedGenres.packed)
        assertTrue(lib.search("x").isEmpty()); assertTrue(lib.songsBy("artist").isEmpty())
        // a file at the storage root (no folder at all)
        val root = Song(1, "x", "A", "", "", -1, "", 0, 1, 0, 1000, "", "x.mp3", 1, 0)
        assertEquals(listOf(FolderTree.PHONE), FolderTree.build(listOf(root)).map { it.title })
    }
}

class LocaleKeyTest {
    @Test fun pathAndFolderKeysDoNotDependOnThePhoneLanguage() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val song = Song(70, "x", "A", "", "", -1, "", 0, 1, 0, 1000, "Music/IMAGINE/", "BIG I.mp3", 1, 0)
            val lib = LibraryIndex(listOf(song))
            val byPath = lib.byPath // made while the phone was in Turkish
            java.util.Locale.setDefault(java.util.Locale.ENGLISH)
            val p = Playlist(1, "P", mutableListOf(Playlist.Entry(7, "Music/IMAGINE/BIG I.mp3"))) // MediaStore renumbered 7 -> 70
            assertEquals(listOf(70L), p.resolve(lib.byId, byPath).map { it.id })
            val h = PlayHistory(); h.record(7, "Music/IMAGINE/BIG I.mp3", 1)
            assertEquals(listOf(70L), h.recent(lib.byId, byPath).map { it.id })
            // Folders: an upper-case "MUSIC" is still the Music root (first) in Turkish
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val roots = FolderTree.build(listOf(Song(1, "a", "A", "", "", -1, "", 0, 1, 0, 1000, "Download/", "a.mp3", 1, 0),
                Song(2, "b", "B", "", "", -1, "", 0, 1, 0, 1000, "MUSIC/X/", "b.mp3", 1, 0)))
            assertTrue(roots.first().title, roots.first().title.startsWith("MUSIC"))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }
}

class DuplicateEdgeTest {
    private var nextId = 1L
    private fun f(path: String, dur: Long, raw: RawTags = RawTags(), hash: String? = null, size: Long = 4_000_000): FileInfo {
        val name = path.substringAfterLast('/')
        val rel = path.substring(0, path.length - name.length)
        return FileInfo(nextId++, rel, name, size, dur, "audio/mpeg", 192_000, Naming.resolve(raw, name, rel), null, hash)
    }

    @Test fun numberedUntaggedRipsAreNotOneSong() {
        // "CD1/01.mp3" and "CD2/01.mp3", no tags, about as long: two different songs (only the number is shared)
        val a = f("Download/CD1/01.mp3", 200_000); val b = f("Download/CD2/01.mp3", 201_000)
        assertTrue(Dedupe.find(listOf(a, b)).isEmpty())
        assertTrue(Plan.build(listOf(a, b)).removals.isEmpty())
        // identical bytes are still duplicates, whatever the name
        val c = f("Download/CD1/02.mp3", 150_000, hash = "h", size = 7); val d = f("Download/02.mp3", 150_000, hash = "h", size = 7)
        assertEquals(1, Dedupe.find(listOf(c, d)).size)
        // a tagged title that is a number is a real title
        val e = f("Music/X/1999.mp3", 200_000, RawTags(title = "1999", artist = "Prince")); val g = f("Download/1999.mp3", 200_500, RawTags(title = "1999", artist = "Prince"))
        assertEquals(1, Dedupe.find(listOf(e, g)).size)
    }

    @Test fun untaggedSameTitleUnderDifferentArtistFolders() {
        val a = f("Music/Tarkan/Intro.mp3", 90_000); val b = f("Music/Sezen Aksu/Intro.mp3", 90_500)
        assertTrue(Dedupe.find(listOf(a, b)).isEmpty())
        assertTrue(Duplicates.plan(listOf(a, b), emptySet()).removals.isEmpty())
        // a copy whose folder names no artist still matches
        val c = f("Download/Intro.mp3", 90_200)
        assertEquals(1, Dedupe.find(listOf(a, c)).size)
        // so does the same channel's download in Plain Youtube's tree
        val d = f("Music/PlainDownload/Tarkan - Topic/Intro.m4a", 90_100)
        assertEquals(1, Dedupe.find(listOf(a, d)).size)
        // tagged files are judged by their tags, as before
        val t1 = f("x/Song.mp3", 100_000, RawTags(title = "Song", artist = "A")); val t2 = f("y/Song.mp3", 100_500, RawTags(title = "Song", artist = "A"))
        assertEquals(1, Dedupe.find(listOf(t1, t2)).size)
    }

    @Test fun numberedVariantWithoutRegexMatchesTheOldRegex() {
        fun old(name: String, of: String): Boolean {
            if (name.equals(of, ignoreCase = true)) return true
            val ext = Naming.extension(of)
            val base = if (ext.isEmpty()) of else of.substring(0, of.length - ext.length - 1)
            return Regex(Regex.escape(base) + """ \(\d+\)""" + (if (ext.isEmpty()) "" else Regex.escape(".$ext")), RegexOption.IGNORE_CASE).matches(name)
        }
        val ofs = listOf("Song.mp3", "song.MP3", "Şarkı.m4a", "a", "a.", ".mp3", "x (2).flac", "Intro (Live).mp3", "İ.mp3", "")
        val names = ofs + ofs.flatMap { o ->
            val e = Naming.extension(o); val b = if (e.isEmpty()) o else o.substring(0, o.length - e.length - 1)
            val t = if (e.isEmpty()) "" else ".$e"
            listOf("$b (2)$t", "$b (12)$t", "${b.uppercase()} (3)${t.uppercase()}", "$b ()$t", "$b (x)$t", "$b(2)$t", "$b  (2)$t", "$b (2)", "$b (2)$t.bak", " (2)$t", "$b (",
                "$b (٣)$t", "$b (2) (3)$t")
        }
        for (o in ofs) for (n in names) assertEquals("$n vs $o", old(n, o), Naming.isNumberedVariant(n, o))
    }

    @Test fun twentyThousandFilesPlanInReasonableTime() {
        val rnd = Random(5)
        val files = (1..20_000).map { i ->
            f("Music/Artist ${i % 400}/Album ${i % 1500}/${i % 20} - Song $i.mp3", 120_000L + rnd.nextInt(200_000),
                RawTags(title = "Song ${rnd.nextInt(15_000)}", artist = "Artist ${i % 400}", album = "Album ${i % 1500}"), size = 3_000_000L + rnd.nextInt(5000))
        }
        val t0 = System.nanoTime()
        val plan = Plan.build(files)
        val tPlan = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        val dup = Duplicates.plan(files, emptySet())
        val tDup = (System.nanoTime() - t1) / 1_000_000
        val t2 = System.nanoTime()
        Plan.build(files, dontMove = setOf(files[0].id)) // a second tap (warm)
        val tPlan2 = (System.nanoTime() - t2) / 1_000_000
        val t3 = System.nanoTime()
        Dedupe.find(files)
        val tFind = (System.nanoTime() - t3) / 1_000_000
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("20k files: organiser plan $tPlan ms (again $tPlan2 ms, of it Dedupe.find $tFind ms), duplicates $tDup ms (${plan.moves.size} moves, ${dup.removals.size} duplicates)")
        // an organiser re-run: everything already in place
        val placed = plan.moves.map { m -> m.file.copy(relPath = m.toDir, name = m.toName) }
        val t4 = System.nanoTime()
        val again = Plan.build(placed)
        println("20k files already organised: plan ${(System.nanoTime() - t4) / 1_000_000} ms (${again.inPlace.size} in place, ${again.moves.size} moves)")
        assertEquals(placed.size, again.inPlace.size + again.moves.size)
        assertTrue("took $ms ms", ms < 8000)
    }
}

class LinkedPlaylistsEdgeTest {
    private fun s(id: Long) = Song(id, "t$id", "A", "", "", -1, "", 0, 1, 0, 180_000, "Music/PlainDownload/A/", "t$id.m4a", 1, 0)

    @Test fun brokenLinksFilesAreIgnored() {
        for (bad in listOf("", "null", "[]", "{", "{\"links\":5}", "{\"links\":[null,3,\"x\",{\"key\":\"\"},{\"name\":\"no key\"}]}", "\u0000\u0001", "{\"v\":1,\"links\":[{\"key\":\"yt:a\",\"ids\":[\"v1\",\"v2\""))
            assertTrue(bad, LinkedPlaylists.decodeLinks(bad).isEmpty())
        val l = LinkedPlaylists.decodeLinks("{\"links\":[{\"key\":\"yt:a\",\"ids\":\"nope\"},{\"key\":\"yt:b\",\"name\":\"  \",\"ids\":[\"v1\",\"\",7]}]}")
        assertEquals(listOf("yt:a", "yt:b"), l.map { it.key })
        assertTrue(l[0].ids.isEmpty())
        assertEquals(listOf("v1", "7"), l[1].ids); assertEquals("Playlist", l[1].name)
        assertNull(LinkedPlaylists.dropLink("{", "yt:a")); assertNull(LinkedPlaylists.dropLink("", "yt:a"))
    }

    @Test fun linksToMissingOrDeletedSongs() {
        val links = listOf(LinkedPlaylists.Link("yt:x", "X", listOf("a", "a", "b")))
        val lists = ArrayList<Playlist>()
        assertFalse(LinkedPlaylists.sync(lists, links) { null }) // nothing downloaded (or all deleted): no empty playlist
        assertTrue(lists.isEmpty())
        val got = mutableMapOf("a" to s(5))
        assertTrue(LinkedPlaylists.sync(lists, links) { got[it] })
        assertEquals(listOf(5L), lists.single().entries.map { it.songId }) // a video listed twice is added once
        got.clear()
        assertFalse(LinkedPlaylists.sync(lists, links) { got[it] }) // the file was deleted: the playlist stays as it was
        assertTrue(lists.single().resolve(emptyMap(), emptyMap()).isEmpty()) // and its entry is skipped, not a crash
        got["a"] = s(6) // downloaded again (new MediaStore id): not added twice
        assertFalse(LinkedPlaylists.sync(lists, links) { got[it] })
    }

    @Test fun downloadIndexWithGarbage() {
        val m = LinkedPlaylists.savedIds("\r\n\t\n-\nabc:A\tcontent://media/external/audio/media/notanumber\r\nxyz:A\tcontent://media/external/audio/media/12\r\nqqq:V\t\nold:A\n")
        assertEquals(12L, m["xyz"]); assertNull(m["abc"]); assertNull(m["qqq"]); assertNull(m["old"])
        assertTrue(Duplicates.trackedIds("garbage\n\n-\t\n").isEmpty())
    }

    @Test fun playlistsFileFromBrokenOrOlderVersions() {
        assertTrue(PlaylistCodec.decode("").isEmpty()); assertTrue(PlaylistCodec.decode("{}").isEmpty()); assertTrue(PlaylistCodec.decode("[{\"name\":\"A\"").isEmpty())
        val l = PlaylistCodec.decode("[null, 5, {\"name\":\"A\"}, {\"id\":9,\"songs\":\"no\",\"got\":3}, {\"id\":10,\"songs\":[null,{\"id\":7,\"path\":\"Music/a.mp3\"},{}]}]")
        assertEquals(listOf("A", "Playlist", "Playlist"), l.map { it.name })
        assertEquals(listOf(7L, -1L), l[2].entries.map { it.songId })
        assertTrue(l[1].entries.isEmpty()); assertNull(l[1].source)
    }
}

class FavouritesTest {
    private fun s(id: Long, path: String = "Music/s$id.mp3") =
        Song(id, "t$id", "A", "", "", -1, "", 0, 1, 0, 1000, path.substringBeforeLast('/') + "/", path.substringAfterLast('/'), 1, 0)

    @Test fun holdTitleTogglesOneFavouritesList() {
        val lists = mutableListOf(Playlist(3, "Mine"))
        assertFalse(Favourites.contains(lists, s(1)))
        assertTrue(Favourites.toggle(lists, s(1), "Favourites"))
        val fav = Favourites.of(lists)!!
        assertEquals(4L, fav.id); assertEquals(Favourites.SOURCE, fav.source); assertEquals("Favourites", fav.name)
        assertTrue(Favourites.toggle(lists, s(2), "ignored once it exists"))
        assertEquals(listOf(1L, 2L), fav.entries.map { it.songId })
        assertEquals(2, lists.size) // one Favourites list, not one per song
        // MediaStore renumbered the file: still found by its path, and taken out
        assertTrue(Favourites.contains(lists, s(99, "Music/s1.mp3")))
        assertFalse(Favourites.toggle(lists, s(99, "MUSIC/S1.mp3"), "x"))
        assertEquals(listOf(2L), fav.entries.map { it.songId })
        // kept in playlists.json like any playlist; a links sync never touches it
        val back = PlaylistCodec.decode(PlaylistCodec.encode(lists))
        assertEquals(listOf(2L), Favourites.of(back)!!.entries.map { it.songId })
        assertFalse(LinkedPlaylists.sync(back, listOf(LinkedPlaylists.Link(Favourites.SOURCE + ":x", "Z", listOf("v")))) { null })
        // deleted (the user removed the playlist): the next hold makes a new one
        back.remove(Favourites.of(back)!!); assertNull(Favourites.of(back))
        assertTrue(Favourites.toggle(back, s(5), "Favoriler")); assertEquals("Favoriler", Favourites.of(back)!!.name)
    }
}

class SleepFadeTest {
    @Test fun fadesOverTheLastHalfMinuteOnly() {
        assertEquals(1f, SleepFade.volume(60_000), 0f)
        assertEquals(1f, SleepFade.volume(SleepFade.MS), 0f)
        assertEquals(0.25f, SleepFade.volume(15_000), 1e-6f)
        assertEquals(0f, SleepFade.volume(0), 0f); assertEquals(0f, SleepFade.volume(-5), 0f)
        var last = 1f
        for (left in SleepFade.MS downTo 0L step 1000L) {
            val v = SleepFade.volume(left)
            assertTrue(v <= last && v >= 0f && v <= 1f); last = v
        }
    }
}
