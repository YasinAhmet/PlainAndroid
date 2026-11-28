package app.plain.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import java.util.Locale

/** One photo or video. [time] is when it was taken (ms), falling back to the file's date. */
class Item(val uri: Uri, val id: Long, val video: Boolean, val time: Long, val duration: Long, val mime: String) {
    /** Lives in the media store (so it can be trashed, and its id found in the library). */
    val inStore get() = uri.authority == MediaStore.AUTHORITY
}

/** Pure helpers (unit-tested). */
object Paging {
    fun pageCount(total: Int, perPage: Int) = if (total <= 0 || perPage <= 0) 1 else (total + perPage - 1) / perPage
    fun pageOf(index: Int, perPage: Int) = if (perPage <= 0) 0 else (index.coerceAtLeast(0)) / perPage
    fun range(page: Int, perPage: Int, total: Int): IntRange {
        val from = page * perPage
        return from until minOf(total, from + perPage)
    }

    /** Newest first; equal times by newest id (the later insert). */
    fun <T> newestFirst(list: List<T>, time: (T) -> Long, id: (T) -> Long): List<T> =
        list.sortedWith(compareByDescending<T> { time(it) }.thenByDescending { id(it) })

    /** dateTaken (ms) when known, else date modified / added (seconds). */
    fun takenTime(dateTaken: Long, modifiedSec: Long, addedSec: Long) = when {
        dateTaken > 0 -> dateTaken
        modifiedSec > 0 -> modifiedSec * 1000
        else -> addedSec * 1000
    }

    fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%d KB", b shr 10)
        else -> "$b B"
    }

    fun count(n: Int): String = String.format(Locale.US, "%,d", n)

    /** [w] × [h] scaled down (never up) so the long side is at most [maxLong]; each side at least 1 px. */
    fun fitSize(w: Int, h: Int, maxLong: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= maxLong || long <= 0 || maxLong <= 0) return w to h
        val k = maxLong.toDouble() / long
        return Math.round(w * k).toInt().coerceIn(1, maxLong) to Math.round(h * k).toInt().coerceIn(1, maxLong)
    }
}

/** Every image and video the media store knows, newest first. Shared by the grid and the viewer. */
object Library {
    @Volatile var items: List<Item> = emptyList()
        private set
    @Volatile var loaded = false
        private set

    private val images: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val videos: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val watch: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** Runs a single query of a few small columns (no pixels), then sorts in memory. */
    fun load(ctx: Context): List<Item> {
        val sorted = query(ctx, null, null)
        items = sorted
        loaded = true
        return sorted
    }

    /**
     * The camera roll (DCIM/Camera: Plain Camera's and the stock camera's photos and videos), newest
     * first. Queried fresh each time, so it never shows an item that was trashed meanwhile.
     */
    fun cameraRoll(ctx: Context): List<Item> =
        query(ctx, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(CAMERA_DIR)) // no wildcards: case-insensitive match

    const val CAMERA_DIR = "DCIM/Camera/"
    /** Plain Media's downloads (LIKE is case-insensitive in MediaStore). Audio, voice recordings included, never shows here. */
    const val OUR_DOWNLOADS = "Movies/PlainDownload/"

    private fun query(ctx: Context, where: String?, whereArgs: Array<String>?): List<Item> {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.MIME_TYPE,
        )
        val args = Bundle().apply {
            // photos and videos, but not the YouTube videos Plain Media saved (Movies/PlainDownload/<Channel>/…)
            val types = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) AND " +
                "(${MediaStore.MediaColumns.RELATIVE_PATH} IS NULL OR ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE '$OUR_DOWNLOADS%')"
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, if (where == null) types else "$types AND $where")
            if (whereArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, whereArgs)
        }
        val out = ArrayList<Item>()
        ctx.contentResolver.query(watch, cols, args, null)?.use { c ->
            out.ensureCapacity(c.count)
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
                val t = Paging.takenTime(if (c.isNull(2)) 0 else c.getLong(2), c.getLong(3), c.getLong(4))
                val mime = c.getString(6) ?: if (video) "video/*" else "image/*"
                out += Item(ContentUris.withAppendedId(if (video) videos else images, id), id, video, t, if (c.isNull(5)) 0 else c.getLong(5), mime)
            }
        }
        return Paging.newestFirst(out, { it.time }, { it.id })
    }

    /** What the grid shows right now (everything, or one category); the viewer swipes through this. */
    @Volatile var shown: List<Item> = emptyList()

    fun remove(it: Item) = removeAll(listOf(it))

    fun removeAll(gone: Collection<Item>) {
        val keys = gone.mapTo(HashSet()) { it.id * 2 + if (it.video) 1 else 0 }
        val keep = { x: Item -> x.id * 2 + (if (x.video) 1 else 0) !in keys }
        items = items.filter(keep)
        shown = shown.filter(keep)
    }

    /** Puts items back after an undo (the next reload confirms it). */
    fun restore(back: Collection<Item>) {
        val have = items.mapTo(HashSet()) { it.id }
        items = Paging.newestFirst(items + back.filter { it.id !in have }, { it.time }, { it.id })
    }

    /** Where [uri] (a media-store item) sits in [l]; -1 when it isn't there. */
    fun indexOf(uri: Uri, l: List<Item> = items): Int {
        if (uri.authority != MediaStore.AUTHORITY) return -1
        val id = try { ContentUris.parseId(uri) } catch (_: Exception) { return -1 }
        val video = uri.pathSegments.contains("video")
        return l.indexOfFirst { it.id == id && (it.video == video || !uri.pathSegments.any { s -> s == "images" || s == "video" }) }
    }
}
