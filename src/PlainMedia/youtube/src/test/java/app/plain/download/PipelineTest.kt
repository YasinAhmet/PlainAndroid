package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.5: original-format plan, conversion ETA, pipeline stages and texts, restart behaviour. */
class PipelineTest {
    private fun job(id: Long, state: State, phase: Phase = Phase.DOWNLOAD) =
        Job(id, "v$id".padEnd(11, 'x'), "T$id", "C", 60, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC, state = state, phase = phase)

    @Test fun outputPlan() {
        val orig = SavePlan.audio(AudioFmt.M4A, original = true)
        assertEquals(SavePlan.Mode.COPY, orig.mode); assertEquals("m4a", orig.ext); assertEquals("audio/mp4", orig.mime); assertFalse(orig.converts)
        val conv = SavePlan.audio(AudioFmt.M4A, original = false)
        assertEquals(SavePlan.Mode.REMUX_M4A, conv.mode); assertTrue(conv.converts)
        // Opus has no audio-typed original container on Android: always .ogg
        for (o in listOf(true, false)) {
            val op = SavePlan.audio(AudioFmt.OPUS, o)
            assertEquals(SavePlan.Mode.REMUX_OGG, op.mode); assertEquals("ogg", op.ext); assertEquals("audio/ogg", op.mime)
        }
        assertEquals(SavePlan.Mode.COPY, SavePlan.video(single = true).mode)
        assertEquals(SavePlan.Mode.MERGE, SavePlan.video(single = false).mode)
        assertEquals("mp4", SavePlan.video(false).ext)
    }

    @Test fun etaSmoothing() {
        val e = Eta()
        assertNull(e.update(0.01, 10_000)) // too early
        assertNull(e.update(0.5, 1_000))
        assertEquals(60L, e.update(0.25, 20_000)) // 20 s for 25 % -> 60 s left
        // a jump in speed (raw rate 0.05/s after 0.0125/s) moves the rate part of the way: 0.02375/s -> 21 s for 50 %
        assertEquals(21L, e.update(0.5, 10_000))
        assertEquals(0L, e.update(1.0, 50_000))
        // steady progress (100 s in total) is exact at every step
        val s = Eta()
        for (t in 10..90 step 10) assertEquals(100L - t, s.update(t / 100.0, t * 1000L))
    }

    @Test fun statusTexts() {
        val d = job(1, State.RUNNING).also { it.done = 40; it.total = 100 }
        assertEquals("Downloading 40% · 40 B of 100 B", Pipeline.status(d))
        assertEquals("Starting…", Pipeline.status(job(2, State.RUNNING)))
        val w = job(3, State.RUNNING, Phase.WAIT_SAVE).also { it.saveConverts = true }
        assertEquals("Downloaded · waiting to convert", Pipeline.status(w))
        w.saveConverts = false
        assertEquals("Downloaded · waiting to save", Pipeline.status(w))
        val c = job(4, State.RUNNING, Phase.CONVERT).also { it.savePct = 37; it.etaSec = 70 }
        assertEquals("Converting 37% · about 1:10 left", Pipeline.status(c))
        c.etaSec = -1
        assertEquals("Converting 37%", Pipeline.status(c))
        assertEquals("Saving 37%", Pipeline.status(job(5, State.RUNNING, Phase.COPY).also { it.savePct = 37 }))
        val done = job(6, State.DONE).also { it.relPath = "Music/PlainDownload/C/"; it.fileName = "T.m4a"; it.saveMs = 161_000; it.savedConverted = true }
        assertEquals("Saved · Music/PlainDownload/C/T.m4a · converted in 2:41", Pipeline.status(done))
        done.saveMs = 3_600; done.savedConverted = false
        assertEquals("Saved · Music/PlainDownload/C/T.m4a · saved in 4 s", Pipeline.status(done))
        done.saveMs = 0
        assertEquals("Saved · Music/PlainDownload/C/T.m4a", Pipeline.status(done))
        // never "Downloading 100%" once the download is done
        val full = job(7, State.RUNNING, Phase.WAIT_SAVE).also { it.done = 100; it.total = 100 }
        assertFalse(Pipeline.status(full).startsWith("Downloading"))
    }

    @Test fun stagesAndSummary() {
        val jobs = listOf(job(1, State.RUNNING, Phase.DOWNLOAD), job(2, State.RUNNING, Phase.CONVERT), job(3, State.RUNNING, Phase.WAIT_SAVE),
            job(4, State.QUEUED), job(5, State.QUEUED), job(6, State.QUEUED), job(7, State.DONE))
        assertEquals("1 downloading · 1 converting · 1 waiting to convert · 3 queued", Pipeline.summary(jobs))
        assertEquals("", Pipeline.summary(emptyList()))
        assertEquals("2 queued", Pipeline.summary(listOf(job(1, State.QUEUED), job(2, State.QUEUED))))
        // every pipeline stage is still an active (cancellable) job
        assertTrue(jobs.take(3).all { it.active })
    }

    @Test fun spaceBeforeNextDownload() {
        val mb = 1L shl 20
        assertTrue(Pipeline.canDownload(2000 * mb, 500 * mb, 0))
        assertFalse(Pipeline.canDownload(2000 * mb, 900 * mb, 0)) // 1800 + 300 margin > 2000
        assertFalse(Pipeline.canDownload(2000 * mb, 500 * mb, 800 * mb)) // waiting conversions need room for their output
        assertTrue(Pipeline.canDownload(400 * mb, 0, 0)) // unknown size: margin only
        assertFalse(Pipeline.canDownload(200 * mb, 0, 0))
    }

    @Test fun restartKeepsQueue() {
        val l = mutableListOf(job(1, State.RUNNING, Phase.CONVERT), job(2, State.QUEUED), job(3, State.DONE))
        JobCodec.afterRestart(l)
        assertEquals(State.FAILED, l[0].state); assertEquals("Interrupted", l[0].error); assertEquals(Phase.DOWNLOAD, l[0].phase)
        assertEquals(State.QUEUED, l[1].state)
        assertEquals(State.DONE, l[2].state)
        // the time saving took is kept in history
        val d = job(9, State.DONE).also { it.saveMs = 4000; it.savedConverted = true }
        val back = JobCodec.decode(JobCodec.encode(listOf(d)))[0]
        assertEquals(4000L, back.saveMs); assertTrue(back.savedConverted)
        assertEquals("4 s", Pipeline.took(3600)); assertEquals("2:41", Pipeline.took(161_000)); assertEquals("1:00:00", Pipeline.took(3_600_000))
    }
}
