package app.plain.download

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/** A file of ours as MediaStore sees it now (one query covers trashed and live files). */
data class MediaRow(
    val id: Long,
    val trashed: Boolean,
    val relPath: String,
    val name: String,
    val size: Long,
    val expires: Long, // DATE_EXPIRES, seconds (trashed items: when Android deletes them for good)
    val audio: Boolean = true,
)

/** What a finished download's file is now: there, in the trash (restorable), or gone for good. */
enum class Media { OK, TRASHED, GONE }

/**
 * Sync with MediaStore (Plain Music, a file manager or Android may have trashed, restored, deleted or moved our files):
 * pure decisions for the downloads history.
 */
object Reconcile {
    data class Fix(val jobId: Long, val media: Media, val relPath: String?, val fileName: String?)

    /** MediaStore id of a content:// uri (…/media/123), or null. */
    fun idOf(uri: String?): Long? = uri?.substringAfterLast('/')?.substringBefore('?')?.toLongOrNull()

    /** Changes for finished jobs: trashed / restored / deleted / moved (renamed). [rows] = one query by id. */
    fun plan(jobs: List<Job>, rows: Map<Long, MediaRow>): List<Fix> {
        val out = ArrayList<Fix>()
        for (j in jobs) {
            if (j.state != State.DONE) continue
            val id = idOf(j.uri) ?: continue
            val r = rows[id]
            val media = when { r == null -> Media.GONE; r.trashed -> Media.TRASHED; else -> Media.OK }
            val moved = r != null && (r.relPath != (j.relPath ?: "") || r.name != (j.fileName ?: ""))
            if (media != j.media || moved) out += Fix(j.id, media, if (r != null) r.relPath else j.relPath, if (r != null) r.name else j.fileName)
        }
        return out
    }
}

/** Space rule: when free storage is below 3 GB, delete the oldest trashed files of ours until it is above again. */
object Purge {
    const val MIN_FREE = 3L shl 30

    /** Oldest trashed first (soonest DATE_EXPIRES); empty when there is enough space already. */
    fun pick(trashed: List<MediaRow>, free: Long, minFree: Long = MIN_FREE): List<MediaRow> {
        if (free >= minFree) return emptyList()
        var f = free
        val out = ArrayList<MediaRow>()
        for (r in trashed.filter { it.trashed }.sortedBy { it.expires }) {
            if (f >= minFree) break
            out += r; f += r.size
        }
        return out
    }

    /** Whole days until Android deletes a trashed file (0 = today). */
    fun daysLeft(expiresSec: Long, nowMs: Long): Long = maxOf(0L, Math.ceil((expiresSec * 1000.0 - nowMs) / 86_400_000.0).toLong())
}

/** MediaStore trash operations for our own files (Music/PlainDownload/, Movies/PlainDownload/). */
object MediaTrash {
    private val FILES: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val PROJ = arrayOf(
        MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED, MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_EXPIRES,
        MediaStore.Files.FileColumns.MEDIA_TYPE,
    )

    /** Null when MediaStore didn't answer (then nothing is concluded from it: no file is marked "Removed"). */
    private fun query(cr: ContentResolver, sel: String, args: Array<String>, match: Int): List<MediaRow>? {
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, sel)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, match)
        }
        val out = ArrayList<MediaRow>()
        val cur = cr.query(FILES, PROJ, b, null) ?: return null
        cur.use { c ->
            while (c.moveToNext()) out += MediaRow(
                c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty(), c.getString(3).orEmpty(), c.getLong(4),
                if (c.isNull(5)) 0 else c.getLong(5), c.getInt(6) != MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO,
            )
        }
        return out
    }

    /** Current state of these ids (live or trashed), in chunks of 400; null when MediaStore didn't answer. */
    fun rows(cr: ContentResolver, ids: Collection<Long>): Map<Long, MediaRow>? {
        val out = HashMap<Long, MediaRow>()
        for (chunk in ids.distinct().chunked(400)) {
            val sel = "${MediaStore.MediaColumns._ID} IN (" + chunk.joinToString(",") { "?" } + ")"
            (query(cr, sel, chunk.map { it.toString() }.toTypedArray(), MediaStore.MATCH_INCLUDE) ?: return null).forEach { out[it.id] = it }
        }
        return out
    }

    /** Our trashed files (oldest first). */
    fun trashed(cr: ContentResolver): List<MediaRow> {
        val sel = "(${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)"
        return query(cr, sel, arrayOf(Naming.AUDIO_ROOT + "%", Naming.VIDEO_ROOT + "%"), MediaStore.MATCH_ONLY).orEmpty()
            .filter { it.trashed }.sortedBy { it.expires }
    }

    fun uriOf(r: MediaRow): Uri = ContentUris.withAppendedId(
        if (r.audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), r.id)

    private fun includeTrashed() = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }

    /** Trash (or restore) files we own; returns the uris Android refused (not ours any more → system dialog). */
    fun setTrashed(cr: ContentResolver, uris: List<Uri>, trashed: Boolean): List<Uri> {
        val refused = ArrayList<Uri>()
        for (u in uris) try {
            // trashed rows are only matched when the request says so (restoring would otherwise touch 0 rows)
            cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0) }, includeTrashed())
        } catch (_: SecurityException) { refused += u } catch (_: Exception) {}
        return refused
    }

    /** Delete for good (Delete now / Empty trash / low space); returns refused uris. */
    fun delete(cr: ContentResolver, uris: List<Uri>): List<Uri> {
        val refused = ArrayList<Uri>()
        for (u in uris) try { cr.delete(u, includeTrashed()) } catch (_: SecurityException) { refused += u } catch (_: Exception) {}
        return refused
    }
}
