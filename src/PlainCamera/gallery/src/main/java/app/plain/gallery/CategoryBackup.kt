package app.plain.gallery

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.plain.lang.tr
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * A copy of the categories that outlives the app: a small picture, Pictures/Plain/plain-gallery-categories.png,
 * whose PNG carries the backup text ([Png], [Backup]). Being a picture, a reinstalled gallery can still read it
 * with its photo permission (an app can't read its own old non-media files after a reinstall). Written a moment
 * after each change; read back automatically when the categories are empty. The grid never shows it.
 */
object CategoryBackup {
    const val DIR = "Pictures/Plain/"
    const val NAME = "plain-gallery-categories.png"
    /** LIKE pattern for every copy (a reinstalled app can't overwrite the old one, so it writes "… (1).png"). */
    const val LIKE = "plain-gallery-categories%"

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "category-backup").apply { isDaemon = true } }
    private var app: Context? = null
    private var pending = false
    private var restoreChecked = false
    private val writeNow = Runnable { flush() }

    private val images: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val files: Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

    /** A category changed (main thread): write the backup ~1.5 s later, once for a burst of changes. */
    fun schedule(ctx: Context) {
        app = ctx.applicationContext
        pending = true
        main.removeCallbacks(writeNow)
        main.postDelayed(writeNow, 1500)
    }

    /** Writes at once if a change is waiting (the gallery leaves the screen). Main thread. */
    fun flush() {
        main.removeCallbacks(writeNow)
        if (!pending) return
        pending = false
        val ctx = app ?: return
        val t = Tags.parse(Categories.get(ctx).serialize()) // a snapshot: Tags is main-thread only
        val time = System.currentTimeMillis()
        io.execute { try { write(ctx, time, t) } catch (_: Throwable) {} }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("gallery", Context.MODE_PRIVATE)

    private fun write(ctx: Context, time: Long, t: Tags) {
        val bytes = Png.withData(picture(), Backup.encode(time, t, keysOf(ctx, t.allIds())).toByteArray(Charsets.UTF_8))
        val cr = ctx.contentResolver
        val p = prefs(ctx)
        p.getString("backupUri", null)?.let { if (tryWrite(cr, Uri.parse(it), bytes)) return }
        // our own earlier copy (app data cleared): still ours to overwrite; an earlier install's copy isn't
        for (u in copies(ctx)) if (tryWrite(cr, u, bytes)) { p.edit().putString("backupUri", u.toString()).apply(); return }
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val u = cr.insert(images, v) ?: return
        try {
            (cr.openOutputStream(u, "w") ?: throw IllegalStateException()).use { it.write(bytes) }
            cr.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            p.edit().putString("backupUri", u.toString()).apply()
        } catch (e: Exception) {
            try { cr.delete(u, null, null) } catch (_: Exception) {}
        }
    }

    private fun tryWrite(cr: ContentResolver, u: Uri, bytes: ByteArray): Boolean = try {
        (cr.openOutputStream(u, "wt") ?: throw IllegalStateException()).use { it.write(bytes) }
        true
    } catch (_: Exception) { false }

    /** Every backup copy the media store shows us, newest first. */
    private fun copies(ctx: Context): List<Uri> {
        val out = ArrayList<Uri>()
        val args = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(LIKE))
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")
        }
        try {
            ctx.contentResolver.query(images, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c ->
                while (c.moveToNext()) out += android.content.ContentUris.withAppendedId(images, c.getLong(0))
            }
        } catch (_: Exception) {}
        return out
    }

    /** id → fallback key for [ids] (and for every photo and video when [ids] is null). */
    private fun keysOf(ctx: Context, ids: Set<Long>?): Map<Long, String> {
        val out = HashMap<Long, String>()
        val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE)
        fun run(where: String) {
            val args = Bundle().apply { putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where) }
            ctx.contentResolver.query(files, cols, args, null)?.use { c ->
                while (c.moveToNext()) Backup.key(c.getString(1), c.getString(2), if (c.isNull(3)) 0 else c.getLong(3))?.let { out[c.getLong(0)] = it }
            }
        }
        try {
            if (ids == null) run("${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (1,3)")
            else for (part in ids.chunked(500)) run("${MediaStore.MediaColumns._ID} IN (${part.joinToString(",")})")
        } catch (_: Exception) {}
        return out
    }

    /** The backup's picture: black, with a line saying what it is. */
    private fun picture(): ByteArray {
        val b = Bitmap.createBitmap(480, 160, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.BLACK)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB0B0B0.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER }
        c.drawText(tr("Plain Gallery categories", "Plain Gallery kategorileri"), 240f, 70f, p)
        p.textSize = 20f; p.color = 0xFF808080.toInt()
        c.drawText(tr("Backup - keep this file", "Yedek - bu dosyayı silmeyin"), 240f, 110f, p)
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        b.recycle()
        return out.toByteArray()
    }

    /** Reads a backup file's bytes; null when it isn't one. */
    private fun read(cr: ContentResolver, u: Uri): Backup.Data? = try {
        cr.openInputStream(u)?.use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16384)
            while (true) { val n = s.read(buf); if (n < 0) break; out.write(buf, 0, n); if (out.size() > Png.MAX) return null }
            Png.readData(out.toByteArray())?.let { Backup.decode(String(it, Charsets.UTF_8)) }
        }
    } catch (_: Exception) { null }

    /**
     * Once per run: with no categories here (a fresh install), the newest backup found is mapped onto the library
     * and becomes the categories. [done] runs on the main thread when something was restored.
     */
    fun restoreIfEmpty(ctx: Context, done: () -> Unit) {
        if (restoreChecked) return
        restoreChecked = true
        if (Categories.get(ctx).names.isNotEmpty()) return
        val a = ctx.applicationContext
        io.execute {
            val d = try {
                copies(a).mapNotNull { read(a.contentResolver, it) }.maxByOrNull { it.time }
            } catch (_: Throwable) { null } ?: return@execute
            if (d.tags.names.isEmpty()) return@execute
            val t = Backup.remap(d, keysOf(a, null))
            main.post {
                if (Categories.get(a).names.isNotEmpty()) return@post
                Categories.replace(a, t) // no new backup copy: the one just read holds the same
                done()
            }
        }
    }

    /** A backup the user picked (Restore categories…): merged into the categories here. [done] gets the count. */
    fun restoreFrom(ctx: Context, u: Uri, done: (Int?) -> Unit) {
        val a = ctx.applicationContext
        io.execute {
            val d = read(a.contentResolver, u)
            val t = d?.let { Backup.remap(it, keysOf(a, null)) }
            main.post {
                if (t == null) { done(null); return@post }
                Backup.merge(Categories.get(a), t)
                Categories.save(a)
                done(t.names.size)
            }
        }
    }
}
