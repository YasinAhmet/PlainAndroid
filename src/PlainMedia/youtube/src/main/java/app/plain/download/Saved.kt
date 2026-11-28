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
        // forgotten only when MediaStore says the row is gone (or the uri is unusable); a failed query (provider busy
        // or restarting) must not drop the "saved" mark, or the next download would make a second copy
        var gone = false
        val row = try {
            val c = ctx.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            c?.use { if (it.moveToFirst()) (it.getString(0) to it.getString(1)) else { gone = true; null } }
        } catch (_: IllegalArgumentException) { gone = true; null } catch (_: Exception) { null }
        if (row == null) { if (gone) Store.forgetSaved(id, kind, u); return null }
        return File(u, row.first, row.second, kind == Kind.AUDIO)
    }

    /** Saved as audio or as video: the file to play for [mode] (Listen takes a video file too: played without picture). */
    fun forPlay(ctx: Context, id: String, mode: PlayMode?): File? = when (mode) {
        PlayMode.LISTEN -> find(ctx, id, Kind.AUDIO) ?: find(ctx, id, Kind.VIDEO)
        PlayMode.WATCH -> find(ctx, id, Kind.VIDEO)
        null -> find(ctx, id, Kind.VIDEO) ?: find(ctx, id, Kind.AUDIO)
    }

    /**
     * Our own file of that title already in [rel]: adopted, so it isn't saved a second time as "Title (2)".
     *  - marked downloaded but no file known (before 3.3 only the mark was kept): the name is enough;
     *  - not marked at all (3.10: the app was reinstalled or its data cleared, which forgets every download while the
     *    files stay in Music/PlainDownload): the name AND the length ([durationSec], within 2 s) must match.
     */
    fun adopt(cr: ContentResolver, id: String, kind: Kind, rel: String, title: String, ext: String, durationSec: Int = 0): File? {
        if (Store.savedUri(id, kind) != null) return null
        val marked = Store.downloaded(id, kind)
        if (!marked && durationSec <= 0) return null
        val name = "${Naming.title(title)}.$ext"
        val b = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(rel, name))
        }
        val coll = Saver.collection(kind)
        val mid = try {
            cr.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DURATION), b, null)?.use { c ->
                if (!c.moveToFirst()) null
                else if (marked || sameLength(c.getLong(1), durationSec)) c.getLong(0) else null
            }
        } catch (_: Exception) { null } ?: return null
        val uri = android.content.ContentUris.withAppendedId(coll, mid).toString()
        Store.setSaved(id, kind, uri)
        return File(uri, rel, name, kind == Kind.AUDIO)
    }

    /** A file's length (ms, 0 = unknown) against a video's (s): within 2 s. Unknown never matches. */
    fun sameLength(fileMs: Long, durationSec: Int): Boolean = fileMs > 0 && durationSec > 0 && kotlin.math.abs(fileMs - durationSec * 1000L) <= 2000

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

/**
 * files/downloaded.txt, the "ever saved" index (pure, unit-tested; the Music tab reads the same file). Lines:
 * "key" (a mark without a file, before 3.3), "key<TAB>content://…" (a later line wins) and "-key" (forget the file).
 * key = "<videoId>:A" or "<videoId>:V".
 */
object DoneIndex {
    /**
     * Fills [done] and [uris] from [text].
     *  - A YouTube id may itself start with '-' ("-x8Gh3kQbZc:A"): "-" means "forget" only when no uri follows and
     *    what comes after it is a whole key with an 11-character id; otherwise the line is an ordinary entry.
     *  - A last line without its newline was cut short (killed or disk full while appending): ignored, so a
     *    half-written uri can't point at some other file.
     *  - Lines that aren't "<id>:A|V" are skipped; a uri that isn't content:// is dropped (the mark itself is kept).
     */
    fun parse(text: String, done: MutableSet<String>, uris: MutableMap<String, String>) {
        val lines = text.split('\n')
        val complete = if (text.endsWith("\n")) lines else lines.dropLast(1)
        for (l in complete) {
            val t = l.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("-") && '\t' !in t && isKey(t.substring(1)) && t.substringBefore(':').length == 12) {
                uris.remove(t.substring(1)); continue
            }
            val k = t.substringBefore('\t').trim()
            if (!isKey(k)) continue
            done += k
            val u = t.substringAfter('\t', "").trim()
            if (u.startsWith("content://")) uris[k] = u
        }
    }

    /** "<id>:A" / "<id>:V" with no blanks inside. */
    fun isKey(k: String): Boolean = k.length >= 3 && (k.endsWith(":A") || k.endsWith(":V")) && k.none { it.isWhitespace() }

    fun encode(done: Collection<String>, uris: Map<String, String>): String =
        done.joinToString("\n", postfix = "\n") { k -> uris[k]?.let { "$k\t$it" } ?: k }
}
