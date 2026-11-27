package app.plain.voice

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore

/** One recording: [time] is when it was added (ms). [duration] is 0 while the media store doesn't know it. */
class Rec(val uri: Uri, val id: Long, val name: String, val time: Long, val duration: Long, val size: Long)

/**
 * The app's own recordings in the media store (Recordings/Plain Voice/, or Music/Plain Voice/ on Android 10–11).
 * An app sees and changes its own media-store files without any storage permission. Files left by an earlier
 * install belong to nobody we know, so they don't show; that's accepted.
 */
object Recordings {
    private val collection: Uri = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Newest first. Pending (still recording) and trashed entries are left out by the media store itself. */
    fun list(ctx: Context): List<Rec> {
        val out = ArrayList<Rec>()
        val folders = Format.allFolders
        val where = folders.joinToString(" OR ") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" } // LIKE: case-insensitive
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Audio.AudioColumns.DURATION, MediaStore.MediaColumns.SIZE,
        )
        val order = "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
        try {
            ctx.contentResolver.query(collection, cols, where, folders.toTypedArray(), order)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += Rec(
                        ContentUris.withAppendedId(collection, id), id,
                        Format.shownName(c.getString(1) ?: ""), c.getLong(2) * 1000, c.getLong(3), c.getLong(4),
                    )
                }
            }
        } catch (_: Exception) {}
        return out
    }

    /**
     * A new, still hidden (IS_PENDING) entry to record into, or null. Marked as a recording, not music, where the
     * media store takes it (it may recompute those flags from the folder when the file is published).
     */
    fun create(ctx: Context, time: Long): Uri? {
        val target = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val base = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, Format.fileName(time))
            put(MediaStore.MediaColumns.MIME_TYPE, Format.MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Format.folderFor(Build.VERSION.SDK_INT))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val flags = ContentValues(base).apply {
            put(MediaStore.Audio.AudioColumns.IS_MUSIC, 0)
            if (Build.VERSION.SDK_INT >= 31) put(MediaStore.Audio.AudioColumns.IS_RECORDING, 1)
        }
        val first = try { ctx.contentResolver.insert(target, flags) } catch (_: Exception) { null }
        return first ?: try { ctx.contentResolver.insert(target, base) } catch (_: Exception) { null }
    }

    /** Makes a finished recording visible. False when that failed (the entry is then removed). */
    fun publish(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0
    } catch (_: Exception) {
        discard(ctx, uri)
        false
    }

    /** Drops a failed or empty recording. */
    fun discard(ctx: Context, uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    /** Renames the file (its DISPLAY_NAME); [fileName] already carries ".m4a". Throws when the media store refuses. */
    fun rename(ctx: Context, r: Rec, fileName: String) {
        val n = ctx.contentResolver.update(r.uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, fileName) }, null, null)
        if (n <= 0) throw IllegalStateException("not renamed")
    }

    /** Deletes the file for good. Throws RecoverableSecurityException when it isn't ours after all. */
    fun delete(ctx: Context, r: Rec): Boolean = ctx.contentResolver.delete(r.uri, null, null) > 0
}
