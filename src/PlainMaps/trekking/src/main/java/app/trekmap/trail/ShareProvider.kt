package app.trekmap.trail

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Shares GPX / zip / kmz files with other apps (Drive, WhatsApp, Google Earth…), and gives the camera
 * app one write-only target for a trail photo (capture/NAME.jpg → the recording's photo folder).
 */
class ShareProvider : ContentProvider() {
    companion object {
        /** Set from the package name at start-up (the stress-test build has its own). */
        var AUTH = "app.trekmap.trail.share"
        private val SHARE_NAME = Regex("[\\p{L}\\p{N} ._()-]+\\.(zip|kmz)")
        private val CAPTURE_NAME = Regex("Trekmap_[0-9_]+\\.jpg")

        fun uri(e: Entry): Uri = Uri.parse("content://$AUTH/${e.kind.dir}/${e.id}")
        fun debugUri(f: File): Uri = Uri.parse("content://$AUTH/debug/${f.name}")
        fun cacheUri(f: File): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath("cache").appendPath(f.name).build()
        fun captureUri(name: String): Uri = Uri.Builder().scheme("content").authority(AUTH).appendPath("capture").appendPath(name).build()
        /**
         * Safe, short file name (filesystems allow 255 bytes; non-ASCII letters take up to 3). Runs of dots become one
         * and none lead or trail: "Walk..." must not give "Walk....zip", which the ".." guard in [resolve] refuses
         * (the receiving app then fails to open the shared zip / kmz), nor a hidden ".gpx".
         */
        fun fileName(name: String) = name.replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").replace(DOTS, ".")
            .trim { it == ' ' || it == '.' }.take(80)
            .let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it } // never half a letter
            .trim { it == ' ' || it == '.' }.ifEmpty { "trail" } + ".gpx"
        private val DOTS = Regex("\\.{2,}")
    }

    private fun resolve(uri: Uri): Pair<File, Entry?> {
        val seg = uri.pathSegments
        if (seg.size != 2) throw FileNotFoundException()
        val ctx = context ?: throw FileNotFoundException()
        when (seg[0]) {
            "debug" -> {
                if (!seg[1].matches(Regex("trekmap-debug-[0-9_]+\\.zip"))) throw FileNotFoundException()
                return File(File(ctx.cacheDir, "share"), seg[1]).also { if (!it.exists()) throw FileNotFoundException() } to null
            }
            "cache" -> {
                if (!seg[1].matches(SHARE_NAME) || seg[1].contains("..")) throw FileNotFoundException()
                return File(File(ctx.cacheDir, "share"), seg[1]).also { if (!it.exists()) throw FileNotFoundException() } to null
            }
            "capture" -> {
                if (!seg[1].matches(CAPTURE_NAME)) throw FileNotFoundException()
                return File(Photos.recDir(ctx).also { it.mkdirs() }, seg[1]) to null
            }
        }
        val kind = Kind.entries.firstOrNull { it.dir == seg[0] } ?: throw FileNotFoundException()
        val id = seg[1]
        if (!Store.validId(id)) throw FileNotFoundException()
        val f = Store.gpx(kind, id)
        if (!f.exists()) throw FileNotFoundException()
        return f to Store.get(kind, id)
    }

    override fun onCreate() = true

    override fun getType(uri: Uri): String {
        val last = uri.lastPathSegment.orEmpty()
        return when {
            uri.pathSegments.firstOrNull() == "capture" -> "image/jpeg"
            last.endsWith(".kmz") -> "application/vnd.google-earth.kmz"
            last.endsWith(".zip") -> "application/zip"
            else -> "application/gpx+xml"
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = resolve(uri).first
        if (uri.pathSegments.firstOrNull() == "capture") {
            // the camera app writes the photo here; nothing else is writable
            return ParcelFileDescriptor.open(
                f, if (mode.contains('w')) ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
                else ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val (f, e) = resolve(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row = cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> if (f.extension in setOf("zip", "kmz", "jpg")) f.name else fileName(e?.name ?: f.nameWithoutExtension)
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?) = 0
}
