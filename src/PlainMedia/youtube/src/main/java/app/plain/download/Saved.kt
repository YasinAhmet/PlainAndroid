package app.plain.download

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore

/**
 * 3.3: one library. Downloads and completed caches are the same thing, a file in
 * Music|Movies/PlainDownload/<Channel>/…, and a video is saved at most once per kind (audio / video): a second
 * download is refused, a completed cache of something already saved is dropped, and the player plays the saved file
 * instead of streaming. [Store] keeps the "videoId:kind -> content://" index (survives the history trim).
 */
object Saved {
    class File(val uri: String, val relPath: String?, val name: String?, val audio: Boolean) {
        val where get() = relPath.orEmpty() + name.orEmpty()
    }

    /** The saved file of [id] as [kind] if it still exists (not trashed or deleted); a vanished one is forgotten. */
    fun find(ctx: Context, id: String, kind: Kind): File? {
        if (id.isEmpty()) return null
        val u = Store.savedUri(id, kind) ?: return null
        val row = try {
            ctx.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) (c.getString(0) to c.getString(1)) else null }
        } catch (_: Exception) { null }
        if (row == null) { Store.forgetSaved(id, kind, u); return null }
        return File(u, row.first, row.second, kind == Kind.AUDIO)
    }

    /** Saved as audio or as video: the file to play for [mode] (Listen takes a video file too: played without picture). */
    fun forPlay(ctx: Context, id: String, mode: PlayMode?): File? = when (mode) {
        PlayMode.LISTEN -> find(ctx, id, Kind.AUDIO) ?: find(ctx, id, Kind.VIDEO)
        PlayMode.WATCH -> find(ctx, id, Kind.VIDEO)
        null -> find(ctx, id, Kind.VIDEO) ?: find(ctx, id, Kind.AUDIO)
    }

    /**
     * Marked downloaded (before 3.3 only the mark was kept) but no file known: look for our own file of that title in
     * [rel] and adopt it, so it isn't saved a second time as "Title (2)".
     */
    fun adopt(cr: ContentResolver, id: String, kind: Kind, rel: String, title: String, ext: String): File? {
        if (!Store.downloaded(id, kind) || Store.savedUri(id, kind) != null) return null
        val name = "${Naming.title(title)}.$ext"
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(rel, name))
        }
        val coll = Saver.collection(kind)
        val mid = try { cr.query(coll, arrayOf(MediaStore.MediaColumns._ID), b, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } } catch (_: Exception) { null }
            ?: return null
        val uri = android.content.ContentUris.withAppendedId(coll, mid).toString()
        Store.setSaved(id, kind, uri)
        return File(uri, rel, name, kind == Kind.AUDIO)
    }

    /**
     * Unfinished MediaStore entries of ours (".pending-…" files left by a save that was killed) when nothing is
     * being saved: deleted, they are invisible copies taking space.
     */
    fun dropStalePending(ctx: Context) {
        if (Store.hasActive()) return
        val cr = ctx.contentResolver
        for (kind in Kind.values()) {
            val b = Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf((if (kind == Kind.AUDIO) Naming.AUDIO_ROOT else Naming.VIDEO_ROOT) + "%"))
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            }
            val coll = Saver.collection(kind)
            val ids = ArrayList<Long>()
            try { cr.query(coll, arrayOf(MediaStore.MediaColumns._ID), b, null)?.use { c -> while (c.moveToNext()) ids += c.getLong(0) } } catch (_: Exception) {}
            for (i in ids) try { cr.delete(android.content.ContentUris.withAppendedId(coll, i), null, null) } catch (_: Exception) {}
        }
    }
}
