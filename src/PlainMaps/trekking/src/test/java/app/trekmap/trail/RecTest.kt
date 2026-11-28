package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The recording in progress on disk (current.json / current.csv) and trail time: crash safety, clocks, pauses. */
class RecTest {
    private fun tmpDir(): File = Files.createTempDirectory("rec").toFile().also { it.deleteOnExit() }

    // ------------------------------------------------------------------ current.json
    @Test fun metaRoundTripsAndOldFilesStillLoad() {
        val m = Rec.decode(Rec.encode(Rec.Meta(1_700_000_000_000L, "p1", "Belgrad ormanı “loop”", 5_000L, 0L, 123L, 7)))!!
        assertEquals(1_700_000_000_000L, m.start)
        assertEquals("p1", m.planId)
        assertEquals("Belgrad ormanı “loop”", m.planName)
        assertEquals(5_000L, m.pausedTotal)
        assertEquals(123L, m.clockBase)
        assertEquals(7, m.boot)
        // written by the previous version (no clock anchor, no boot count)
        val old = Rec.decode("""{"start":1700000000000,"planId":"","planName":"","pausedTotal":0,"pausedAt":1700000060000}""")!!
        assertNull(old.planId)
        assertEquals(1_700_000_060_000L, old.pausedAt)
        assertEquals(0L, old.clockBase)
        assertEquals(-1, old.boot)
    }

    @Test fun tornOrDamagedMetaIsRejectedNotCrashing() {
        val good = Rec.encode(Rec.Meta(1_700_000_000_000L, null, null, 0L, 0L))
        for (bad in listOf(null, "", " ", "\u0000\u0000\u0000", "null", "[]", "{}", """{"start":"abc"}""", """{"start":0}""",
            """{"start":-5}""", good.substring(0, good.length / 2), good.dropLast(1))) {
            assertNull("accepted: $bad", Rec.decode(bad))
        }
        // nonsense pause values are clamped, not trusted
        val m = Rec.decode("""{"start":1000,"pausedTotal":-99,"pausedAt":-1}""")!!
        assertEquals(0L, m.pausedTotal)
        assertEquals(0L, m.pausedAt)
    }

    @Test fun atomicWriteReplacesAndLeavesNoTemp() {
        val d = tmpDir()
        val f = File(d, "current.json")
        Rec.atomicWrite(f, "one")
        Rec.atomicWrite(f, "two")
        assertEquals("two", f.readText())
        assertFalse(File(d, "current.json.tmp").exists())
        assertNull(Rec.readOrNull(File(d, "missing.json")))
    }

    // ------------------------------------------------------------------ current.csv
    @Test fun csvSegmentsAndTornRows() {
        val lines = sequenceOf(
            "s", "p,1000,41.0,29.0,100.0", "p,2000,41.0001,29.0,NaN", "p,3000,41.0002", // torn by a crash
            "s", "s",                                                                     // empty segment
            "p,4000,41.1,29.1,Infinity", "p,x,41.1,29.1,1", "p,5000,NaN,29.1,1", "p,6000,95.0,29.1,1", // garbage
            "p,7000,41.2,200.0,1", "\u0000\u0000", "garbage", "", "p,8000,41.3,29.3,12.5",
        )
        val segs = Rec.parse(lines)
        assertEquals(2, segs.size)
        assertEquals(2, segs[0].size)
        assertEquals(100.0, segs[0].ele!![0], 0.0)
        assertTrue(segs[0].ele!![1].isNaN())
        assertEquals(2, segs[1].size)                          // 41.1/29.1 and 41.3/29.3
        assertTrue(segs[1].ele!![0].isNaN())                   // "Infinity" is not an elevation
        assertEquals(1000L, Rec.firstTime(segs))
        assertTrue(Rec.parse(emptySequence()).isEmpty())
    }

    @Test fun tornLastRowGetsItsOwnLineBeforeAppending() {
        // the process died mid-row; on restart a segment break and new points are appended
        val f = File(tmpDir(), "current.csv")
        f.writeText("s\np,1000,41.0,29.0,1.0\np,2000,41.0001,29.0,1.0\np,3000,41.00")
        assertTrue(Rec.endsTorn(f))
        f.appendText((if (Rec.endsTorn(f)) "\n" else "") + "s\n" + Rec.row(9000, 41.5, 29.5, Double.NaN) + Rec.row(9500, 41.5001, 29.5, 3.0))
        assertFalse(Rec.endsTorn(f))
        val segs = f.useLines { Rec.parse(it) }
        assertEquals(2, segs.size)                             // the break survived, the torn row is dropped
        assertEquals(2, segs[0].size)
        assertEquals(41.5, segs[1].lat[0], 0.0)
        assertFalse(Rec.endsTorn(File(f.parentFile, "nope.csv")))
        val empty = File(f.parentFile, "empty.csv").also { it.writeText("") }
        assertFalse(Rec.endsTorn(empty))
    }

    @Test fun csvRowsAreLocaleProof() {
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"))
            val row = Rec.row(1000, 41.0123456, 28.9876543, 123.5)
            assertEquals("p,1000,41.0123456,28.9876543,123.5\n", row)
            assertEquals(1, Rec.parse(sequenceOf(row.trim())).size)
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test fun hugeRecordingParsesQuickly() {
        val f = File(tmpDir(), "current.csv")
        f.bufferedWriter().use { w ->
            for (i in 0 until 60_000) {
                if (i % 10_000 == 0) w.write("s\n")
                w.write(Rec.row(1_000L * i, 41.0 + i * 1e-5, 29.0, 100.0 + i % 50))
            }
        }
        val t0 = System.currentTimeMillis()
        val segs = f.useLines { Rec.parse(it) }
        assertEquals(6, segs.size)
        assertEquals(60_000, segs.sumOf { it.size })
        assertTrue("took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 5_000)
    }

    // ------------------------------------------------------------------ trail time
    private class Clocks(var elapsed: Long = 5_000_000L, var wall: Long = 1_732_800_000_000L) {
        fun time() = RecTime({ elapsed }, { wall })
        fun pass(ms: Long) { elapsed += ms; wall += ms }
    }

    @Test fun wallClockJumpsDontBendTrailTime() {
        val c = Clocks()
        val t = c.time()
        t.begin()
        c.pass(10 * 60_000L)
        c.wall += 3_600_000L                 // network time / a manual change: +1 h
        assertEquals(10 * 60_000L, t.movingMs)
        c.wall -= 5 * 3_600_000L             // -5 h (before the start)
        c.pass(60_000L)
        assertEquals(11 * 60_000L, t.movingMs)
        t.setPaused(true); c.wall += 86_400_000L; c.pass(2 * 60_000L); t.setPaused(false)
        assertEquals(11 * 60_000L, t.movingMs) // the paused 2 min don't count, the jump doesn't either
    }

    @Test fun pauseAtStartDoubleToggleAndMidnight() {
        val c = Clocks(wall = 1_732_838_400_000L - 10 * 60_000L) // 23:50 UTC
        val t = c.time()
        t.begin()
        assertTrue(t.setPaused(true))         // pause before the first fix
        assertFalse(t.setPaused(true))        // second tap of a double tap: no change
        val at = t.pausedAt
        c.pass(30 * 60_000L)                  // paused across midnight
        assertEquals(at, t.pausedAt)
        assertEquals(0L, t.movingMs)
        assertTrue(t.setPaused(false))
        assertFalse(t.setPaused(false))
        c.pass(5 * 60_000L)
        assertEquals(5 * 60_000L, t.movingMs)
        assertEquals(30 * 60_000L, t.pausedTotal)
    }

    @Test fun survivesProcessDeathOnSameBootAndAfterReboot() {
        val c = Clocks()
        val t = c.time()
        t.begin()
        c.pass(20 * 60_000L)
        t.setPaused(true)
        c.pass(5 * 60_000L)
        val saved = Rec.decode(Rec.encode(t.meta("p", "Plan", 3)))!!
        // the process is killed; Android restarts it 2 min later on the same boot, and the wall clock moved meanwhile
        c.pass(2 * 60_000L); c.wall += 600_000L
        val again = c.time().also { it.resume(saved, sameBoot = true) }
        assertTrue(again.paused)
        assertEquals(20 * 60_000L, again.movingMs)
        again.setPaused(false)
        c.pass(60_000L)
        assertEquals(21 * 60_000L, again.movingMs)
        // after a reboot elapsed time restarts near 0: trust the wall clock again
        val rebooted = Clocks(elapsed = 30_000L, wall = c.wall)
        val r = rebooted.time().also { it.resume(Rec.decode(Rec.encode(again.meta(null, null, 3)))!!, sameBoot = false) }
        assertTrue(r.movingMs >= 21 * 60_000L)
        // a wall clock set back to before the start can't make the time negative
        val back = Clocks(elapsed = 30_000L, wall = t.start - 3_600_000L).time().also { it.resume(saved, sameBoot = false) }
        assertEquals(0L, back.movingMs)
    }

    @Test fun notRecordingMeansZero() {
        val t = Clocks().time()
        assertEquals(0L, t.movingMs)
        t.begin(); t.reset()
        assertEquals(0L, t.movingMs)
        assertFalse(t.paused)
    }

    // ------------------------------------------------------------------ elevation / numbers
    @Test fun ascentIgnoresInfiniteSpikes() {
        val a = AscentCounter(4.0)
        for (e in listOf(100.0, Double.POSITIVE_INFINITY, 110.0, Double.NEGATIVE_INFINITY, Double.NaN, 120.0)) a.push(e)
        assertEquals(20.0, a.total, 1e-9)
        assertEquals("– m", Fmt.dist(Double.NaN))
        assertEquals("– m", Fmt.dist(Double.POSITIVE_INFINITY))
        assertEquals("0 m", Fmt.dist(0.0))
    }

    @Test fun urlTileNamesUseAsciiDigitsInAnyLanguage() {
        val prev = java.util.Locale.getDefault()
        try {
            for (tag in listOf("ar-EG", "fa-IR", "tr-TR", "hi-IN")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                assertEquals("N39E027", WorldCover.tileName(39, 27))
                assertEquals("S03W071", WorldCover.tileName(-3, -71))
            }
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    // ------------------------------------------------------------------ photos index
    @Test fun oneBadPhotoEntryDoesNotCostTheOthersTheirPosition() {
        val d = tmpDir()
        File(d, "a.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "b.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "photos.json").writeText("""[{"file":"a.jpg","time":5,"lat":41.0,"lon":29.0,"ele":null,"media":""},{"nofile":1},{"file":"gone.jpg","time":1,"lat":1,"lon":1}]""")
        val ps = Photos.list(d)
        assertEquals(2, ps.size)                                // a.jpg (indexed) + b.jpg (orphan file)
        assertTrue(ps.first { it.file == "a.jpg" }.hasPosition)
        assertFalse(ps.first { it.file == "b.jpg" }.hasPosition)
        File(d, "photos.json").writeText("{torn")
        assertEquals(2, Photos.list(d).size)                    // damaged index: the files still count
    }
}
