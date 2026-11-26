package app.plain.migrate

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.MediaStore
import java.io.File
import java.io.FileNotFoundException

/**
 * Lets the merged Plain app that replaces this one (Plain Maps, Plain Media, Plain Camera, Plain Manager) copy this
 * app's data: its private files, Android/data files, databases and settings. Read only: nothing here changes or
 * deletes anything. Only apps signed with the same key as this one get an answer.
 *
 *   authority  <package>.migrate
 *   query  content://<package>.migrate/files     rows: path ("files/…", "ext/…", "db/…"), size, mtime
 *   call   "info"           -> version, prefs (names), trashed (own media items in the trash or pending; -1 unknown)
 *   call   "prefs", <name>  -> that SharedPreferences file as a Bundle (string sets as ArrayLists, key + SET)
 *   openFile content://<package>.migrate/<path>   read only
 *
 * Canonical copy: A:/Projects/PlainShared/bridge/MigrateProvider.kt (identical in every former app).
 */
class MigrateProvider : ContentProvider() {
    companion object {
        const val VERSION = 1
        const val SET = "\u0000set"
    }

    override fun onCreate() = true

    private fun ctx(): Context = context!!

    private fun check() {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        if (ctx().packageManager.checkSignatures(uid, Process.myUid()) != PackageManager.SIGNATURE_MATCH)
            throw SecurityException("Only Plain apps may read this")
    }

    private fun roots(): Map<String, File?> = mapOf(
        "files" to ctx().filesDir,
        "ext" to ctx().getExternalFilesDir(null),
        "db" to ctx().getDatabasePath("x").parentFile,
    )

    private fun prefsDir() = File(ctx().applicationInfo.dataDir, "shared_prefs")

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        check()
        val c = MatrixCursor(arrayOf("path", "size", "mtime"))
        for ((name, root) in roots()) {
            if (root == null || !root.isDirectory) continue
            root.walkTopDown().filter { it.isFile }.forEach { f ->
                c.addRow(arrayOf<Any>(name + "/" + f.relativeTo(root).invariantSeparatorsPath, f.length(), f.lastModified()))
            }
        }
        return c
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        check()
        return when (method) {
            "info" -> Bundle().apply {
                putInt("version", VERSION)
                putStringArrayList("prefs", ArrayList(prefsDir().listFiles()?.filter { it.name.endsWith(".xml") }
                    ?.map { it.name.removeSuffix(".xml") }.orEmpty()))
                putInt("trashed", trashed())
            }
            "prefs" -> Bundle().apply {
                val name = arg ?: return null
                for ((k, v) in ctx().getSharedPreferences(name, Context.MODE_PRIVATE).all) when (v) {
                    is String -> putString(k, v)
                    is Int -> putInt(k, v)
                    is Long -> putLong(k, v)
                    is Float -> putFloat(k, v)
                    is Boolean -> putBoolean(k, v)
                    is Set<*> -> putStringArrayList(k + SET, ArrayList(v.map { it.toString() }))
                }
            }
            else -> null
        }
    }

    /** Media items this app owns that Android deletes along with it when it is uninstalled (trashed or pending). */
    private fun trashed(): Int {
        if (Build.VERSION.SDK_INT < 30) return 0
        return try {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                    "owner_package_name=? AND (is_trashed=1 OR is_pending=1)")
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(ctx().packageName))
            }
            ctx().contentResolver.query(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL),
                arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { it.count } ?: 0
        } catch (_: Exception) { -1 }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        check()
        if (mode != "r") throw SecurityException("read only")
        val segs = uri.pathSegments
        if (segs.size < 2) throw FileNotFoundException()
        val root = roots()[segs[0]]?.canonicalFile ?: throw FileNotFoundException()
        val f = File(root, segs.drop(1).joinToString("/")).canonicalFile
        if (!f.path.startsWith(root.path + File.separator) || !f.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
