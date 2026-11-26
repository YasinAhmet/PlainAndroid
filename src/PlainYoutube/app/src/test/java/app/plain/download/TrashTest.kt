package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.8: MediaStore sync (trashed / restored / deleted / moved by any app), trash purge rule, texts. */
class TrashTest {
    private fun job(id: Long, uri: String?, state: State = State.DONE, media: Media = Media.OK, rel: String = "Music/PlainDownload/C/", name: String = "T$id.m4a") =
        Job(id, "v$id".padEnd(11, 'x'), "T$id", "C", 60, Kind.AUDIO, AudioFmt.M4A, AudioQ.HIGH, 0, VCodec.AVC,
            state = state, uri = uri, relPath = rel, fileName = name, media = media)

    private fun row(id: Long, trashed: Boolean = false, rel: String = "Music/PlainDownload/C/", name: String = "T$id.m4a", size: Long = 100, expires: Long = 0) =
        MediaRow(id, trashed, rel, name, size, expires)

    @Test fun idsFromUris() {
        assertEquals(123L, Reconcile.idOf("content://media/external_primary/audio/media/123"))
        assertEquals(7L, Reconcile.idOf("content://media/external/video/media/7?x=1"))
        assertNull(Reconcile.idOf(null)); assertNull(Reconcile.idOf("content://media/external/audio/media/abc"))
    }

    @Test fun reconcile() {
        val u = { id: Long -> "content://media/external_primary/audio/media/$id" }
        val jobs = listOf(
            job(1, u(1)), // unchanged
            job(2, u(2)), // trashed by Plain Music
            job(3, u(3)), // deleted for good
            job(4, u(4), media = Media.TRASHED), // restored
            job(5, u(5)), // moved by an organizer
            job(6, null), // no file
            job(7, u(7), state = State.FAILED), // not finished
            job(8, u(8), media = Media.GONE), // still gone: no change
            job(9, u(9), media = Media.TRASHED), // still trashed: no change
        )
        val rows = mapOf(1L to row(1), 2L to row(2, trashed = true), 4L to row(4), 5L to row(5, rel = "Music/PlainDownload/C/Album/", name = "T5 (2).m4a"),
            9L to row(9, trashed = true))
        val fixes = Reconcile.plan(jobs, rows).associateBy { it.jobId }
        assertEquals(setOf(2L, 3L, 4L, 5L), fixes.keys)
        assertEquals(Media.TRASHED, fixes[2]!!.media)
        assertEquals(Media.GONE, fixes[3]!!.media); assertEquals("Music/PlainDownload/C/", fixes[3]!!.relPath) // keeps the last known path
        assertEquals(Media.OK, fixes[4]!!.media)
        assertEquals(Media.OK, fixes[5]!!.media); assertEquals("Music/PlainDownload/C/Album/", fixes[5]!!.relPath); assertEquals("T5 (2).m4a", fixes[5]!!.fileName)
        assertTrue(Reconcile.plan(emptyList(), rows).isEmpty())
    }

    @Test fun purgeOldestTrashFirst() {
        val gb = 1L shl 30
        val t = listOf(row(1, true, size = 1 * gb, expires = 300), row(2, true, size = 2 * gb, expires = 100), row(3, true, size = 1 * gb, expires = 200), row(4, false, size = 9 * gb))
        assertTrue(Purge.pick(t, 5 * gb).isEmpty()) // enough space: nothing
        assertEquals(listOf(2L), Purge.pick(t, 2 * gb).map { it.id }) // 2 + 2 >= 3
        assertEquals(listOf(2L, 3L), Purge.pick(t, gb / 2).map { it.id }) // 0.5 + 2 < 3, + 1 >= 3
        assertEquals(listOf(2L, 3L), Purge.pick(t, 0).map { it.id }) // 2 + 1 = 3 GB: enough
        assertEquals(listOf(2L, 3L, 1L), Purge.pick(t, 0, 10 * gb).map { it.id }) // all trashed ones, never live files
        val now = 1_800_000_000_000L
        assertEquals(30L, Purge.daysLeft(now / 1000 + 30 * 86400, now))
        assertEquals(1L, Purge.daysLeft(now / 1000 + 3600, now))
        assertEquals(0L, Purge.daysLeft(now / 1000 - 10, now))
    }

    @Test fun textsAndPersistence() {
        val t = job(1, "content://x/1", media = Media.TRASHED)
        assertTrue(Pipeline.status(t).startsWith("In trash"))
        assertTrue(Pipeline.status(job(2, "content://x/2", media = Media.GONE)).startsWith("Removed"))
        assertTrue(Pipeline.status(job(3, "content://x/3")).startsWith("Saved"))
        val back = JobCodec.decode(JobCodec.encode(listOf(t, job(2, "u"))))
        assertEquals(Media.TRASHED, back[0].media); assertEquals(Media.OK, back[1].media)
    }
}
