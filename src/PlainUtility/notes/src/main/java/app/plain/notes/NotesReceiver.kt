package app.plain.notes

import android.content.BroadcastReceiver
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.plain.lang.PlainLang
import java.io.File

/** A note's reminder is due, or Done / Snooze was tapped on its notification. Not exported. */
class NotesReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PlainLang.init(c)
        val id = i.data?.host?.toLongOrNull() ?: return
        when (i.action) {
            REMIND -> Notes.fire(c, id)
            DONE -> Notes.get(c, id)?.let { Notes.remind(c, it, 0) }
            SNOOZE -> Notes.get(c, id)?.let { Notes.remind(c, it, System.currentTimeMillis() + Notes.snoozeMinutes(c) * 60_000L) }
        }
    }

    companion object {
        const val REMIND = "app.plain.notes.REMIND"
        const val DONE = "app.plain.notes.DONE"
        const val SNOOZE = "app.plain.notes.SNOOZE"
    }
}

/** After a restart or an app update: sets the note reminders again. */
class NotesBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_BOOT_COMPLETED && i.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        PlainLang.init(c)
        Notes.restore(c)
    }
}

/**
 * Read-only access to one voice note's file for Share (content://<package>.notes/<file name>), handed out with a
 * one-time read grant. Not exported; serves files/notes/ only.
 */
class NotesProvider : ContentProvider() {
    override fun onCreate() = true

    private fun fileFor(uri: Uri): File? {
        val c = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.startsWith(".")) return null
        return File(Notes.dir(c), name).takeIf { it.isFile }
    }

    override fun getType(uri: Uri) = "audio/mp4"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: return null
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(cols, 1).apply {
            addRow(cols.map { when (it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        fun uri(c: Context, n: Note): Uri? = n.audio?.let { Uri.parse("content://${c.packageName}.notes/$it") }
    }
}
