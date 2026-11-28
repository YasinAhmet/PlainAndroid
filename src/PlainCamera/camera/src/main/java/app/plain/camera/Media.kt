package app.plain.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import app.plain.lang.tr
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Saving into DCIM/Camera through MediaStore (no storage permission needed for our own files). */
object Media {
    private const val DIR = "DCIM/Camera"

    private fun stamp(t: Long) = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(t))

    /** A new, still hidden (pending) entry; [publish] makes it visible once the file is complete. */
    fun newEntry(ctx: Context, video: Boolean, time: Long): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, (if (video) "VID_" else "IMG_") + stamp(time) + if (video) ".mp4" else ".jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, if (video) "video/mp4" else "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.DATE_TAKEN, time)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val coll = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return ctx.contentResolver.insert(coll, v) ?: throw IOException(tr("the media store refused a new file", "medya deposu yeni dosyayı kabul etmedi"))
    }

    fun publish(ctx: Context, uri: Uri) {
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun delete(ctx: Context, uri: Uri) {
        try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    fun savePhoto(ctx: Context, jpeg: ByteArray, time: Long): Uri {
        val u = newEntry(ctx, false, time)
        try {
            ctx.contentResolver.openOutputStream(u)!!.use { it.write(jpeg) }
            publish(ctx, u)
        } catch (e: Exception) {
            delete(ctx, u)
            throw e
        }
        return u
    }

    /** Writes into a file another app handed us (ACTION_IMAGE_CAPTURE's EXTRA_OUTPUT). */
    fun writeTo(ctx: Context, uri: Uri, bytes: ByteArray) {
        val os = try { ctx.contentResolver.openOutputStream(uri, "wt") } catch (_: Exception) { null }
            ?: ctx.contentResolver.openOutputStream(uri, "w") ?: throw IOException(tr("can't write to $uri", "yazılamıyor: $uri"))
        os.use { it.write(bytes) }
    }

    /**
     * Our newest photo or video that still exists (trashed and pending items are left out); null when
     * there is none. Without read permission the media store only lists our own files. Throws when
     * the media store can't be read, so a failed query never clears the thumbnail.
     */
    fun latestOwn(ctx: Context): Pair<Uri, Boolean>? {
        val coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ctx.packageName))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_TAKEN} DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        val c = ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID, MediaStore.Files.FileColumns.MEDIA_TYPE), args, null)
            ?: throw IOException("the media store didn't answer")
        return c.use {
            if (!c.moveToFirst()) return null
            val id = c.getLong(0)
            val video = c.getInt(1) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO
            val base = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            android.content.ContentUris.withAppendedId(base, id) to video
        }
    }

    fun thumb(ctx: Context, uri: Uri, px: Int): Bitmap? =
        try { ctx.contentResolver.loadThumbnail(uri, Size(px, px), null) } catch (_: Exception) { null }

    /** Free bytes on the shared storage our captures go to; Long.MAX_VALUE when it can't be read (no limit then). */
    @Suppress("DEPRECATION")
    fun freeBytes(): Long = try {
        android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).availableBytes
    } catch (_: Exception) { Long.MAX_VALUE }

    /**
     * Removes our own photos / videos that stayed pending (hidden) longer than [olderThanSec]: a video whose
     * recording was cut off by the app being killed. Such a file has no index and can't be played; the media store
     * would only drop it after a week, and a long video can take gigabytes until then. Best effort, saver thread.
     */
    fun dropStalePending(ctx: Context, olderThanSec: Long) {
        try {
            var coll = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cutoff = System.currentTimeMillis() / 1000 - olderThanSec
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3) AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
                        "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.DATE_ADDED}<?")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ctx.packageName, cutoff.toString()))
                if (android.os.Build.VERSION.SDK_INT >= 30) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }
            @Suppress("DEPRECATION")
            if (android.os.Build.VERSION.SDK_INT < 30) coll = MediaStore.setIncludePending(coll)
            val ids = ArrayList<Long>()
            ctx.contentResolver.query(coll, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            for (id in ids) delete(ctx, android.content.ContentUris.withAppendedId(coll, id))
        } catch (_: Exception) {}
    }
}

/** Makes sure a JPEG from the camera carries the right EXIF orientation and capture time. */
object Jpeg {
    /** Learned on this phone: the camera HAL turns the pixels itself instead of writing EXIF. */
    var prefs: android.content.SharedPreferences? = null
    @Volatile var halRotates = false
        get() = field || prefs?.getBoolean("halRotates", false) == true
        set(v) { if (v && !field) prefs?.edit()?.putBoolean("halRotates", true)?.apply(); field = v }

    private fun rotation(e: ExifInterface) = when (e.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }

    private fun exifTime(t: Long) = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(t))
    private fun exifOffset(t: Long): String {
        val off = TimeZone.getDefault().getOffset(t) / 60000
        val sign = if (off < 0) '-' else '+'
        val a = kotlin.math.abs(off)
        return String.format(Locale.US, "%c%02d:%02d", sign, a / 60, a % 60)
    }

    fun prepare(ctx: Context, bytes: ByteArray, wanted: Int, time: Long): ByteArray {
        val ex = try { ExifInterface(ByteArrayInputStream(bytes)) } catch (_: Exception) { return bytes }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        val rot = rotation(ex)
        if (rot == 0 && (wanted == 90 || wanted == 270) && o.outHeight > o.outWidth) halRotates = true
        val tag = Sizes.orientationTag(wanted, rot, o.outWidth, o.outHeight, halRotates)
        val needTime = ex.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL).isNullOrBlank()
        if (tag == null && !needTime) return bytes
        val f = File(ctx.cacheDir, "exif-${System.nanoTime()}.jpg")
        return try {
            f.writeBytes(bytes)
            val e2 = ExifInterface(f.path)
            if (tag != null) e2.setAttribute(ExifInterface.TAG_ORIENTATION, tag.toString())
            if (needTime) {
                val s = exifTime(time)
                e2.setAttribute(ExifInterface.TAG_DATETIME, s)
                e2.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s)
                e2.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, s)
                val off = exifOffset(time)
                e2.setAttribute(ExifInterface.TAG_OFFSET_TIME, off)
                e2.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, off)
            }
            e2.saveAttributes()
            f.readBytes()
        } catch (_: Exception) {
            bytes
        } finally {
            f.delete()
        }
    }

    /** A small upright bitmap (ACTION_IMAGE_CAPTURE without EXTRA_OUTPUT returns one in "data"). */
    fun small(bytes: ByteArray, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = try { rotation(ExifInterface(ByteArrayInputStream(bytes))) } catch (_: Exception) { 0 }
        val k = maxPx.toFloat() / maxOf(b.width, b.height)
        val m = Matrix().apply { if (k < 1f) postScale(k, k); postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }
}
