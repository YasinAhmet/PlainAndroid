package app.plain.migrate

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.StatFs
import app.plain.hub.Hub
import app.plain.hub.Tab
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/**
 * Copies a former app's data into the merged app (Plain Trekking -> Plain Maps' Trekking tab, …).
 *
 * The former app hands its data over through its MigrateProvider (PlainShared/bridge), which only answers apps
 * signed with the same key. Copying never changes the former app, never overwrites a file the merged app already
 * has, writes each file under a temporary name first, and can simply be run again after an interruption.
 */
object Migrate {
    /**
     * [pkg] is the former app, [name] its name ("Plain Trekking"); [what] says what gets copied, in English and
     * Turkish. [prefs] renames SharedPreferences files whose names the merged app already uses for something else.
     */
    data class Spec(
        val pkg: String,
        val name: String,
        val whatEn: String,
        val whatTr: String,
        val prefs: Map<String, String> = emptyMap(),
    )

    /** Settings that belong to the merged app itself, not to the tab. */
    private val SKIP_PREFS = setOf("plain_lang", "plain_moved")

    private fun stateName(t: Tab) = "import-${t.key}"

    /** "done" (copied), "fresh" (the user chose to start empty) or null (not decided). */
    fun state(ctx: Context, t: Tab): String? = Hub.read(ctx, stateName(t))

    fun setState(ctx: Context, t: Tab, v: String) = Hub.write(ctx, stateName(t), v)

    fun installed(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    fun versionName(ctx: Context, pkg: String): String? = try { ctx.packageManager.getPackageInfo(pkg, 0).versionName } catch (_: Exception) { null }

    /** The former app is new enough to hand its data over. */
    fun hasBridge(ctx: Context, pkg: String): Boolean = ctx.packageManager.resolveContentProvider("$pkg.migrate", 0) != null

    /** The import screen must come before the tab: the former app is installed and nothing was decided yet. */
    fun needsDecision(ctx: Context, t: Tab): Boolean {
        val s = t.import ?: return false
        return state(ctx, t) == null && installed(ctx, s.pkg)
    }

    /** What would be copied. */
    class Plan(val files: List<Item>, val prefs: List<String>, val trashed: Int) {
        val bytes: Long get() = files.sumOf { it.size }
    }

    class Item(val path: String, val size: Long, val mtime: Long)

    private fun base(s: Spec) = Uri.parse("content://${s.pkg}.migrate")

    fun plan(ctx: Context, s: Spec): Plan {
        val cr = ctx.contentResolver
        val info = cr.call(base(s), "info", null, null) ?: throw IOException("${s.name} did not answer")
        val files = ArrayList<Item>()
        cr.query(Uri.withAppendedPath(base(s), "files"), null, null, null, null)?.use { c ->
            while (c.moveToNext()) files += Item(c.getString(0), c.getLong(1), c.getLong(2))
        } ?: throw IOException("${s.name} did not list its files")
        val prefs = info.getStringArrayList("prefs").orEmpty().filter { it !in SKIP_PREFS }
        return Plan(files.filter { target(ctx, it.path) != null && !it.path.endsWith(".part") }, prefs, info.getInt("trashed", 0))
    }

    /** Where a former app's file goes: files/… -> files/…, ext/… -> Android/data/<this app>/files/…, db/… -> databases/…. */
    fun target(ctx: Context, path: String): File? {
        val root = path.substringBefore('/')
        val rel = path.substringAfter('/', "")
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        val dir = when (root) {
            "files" -> if (rel.startsWith("hub/")) return null else ctx.filesDir
            "ext" -> ctx.getExternalFilesDir(null) ?: return null
            "db" -> ctx.getDatabasePath("x").parentFile ?: return null
            else -> return null
        }
        return File(dir, rel)
    }

    fun freeBytes(ctx: Context): Long = try { StatFs(ctx.filesDir.path).availableBytes } catch (_: Exception) { Long.MAX_VALUE }

    class Result(val files: Int, val bytes: Long, val kept: Int, val prefs: Int)

    /**
     * Copies everything in [p]. [progress] gets (files done, bytes done) on this thread. Throws on failure; what was
     * copied stays and is skipped next time.
     */
    fun run(ctx: Context, s: Spec, p: Plan, progress: (Int, Long) -> Unit): Result {
        val need = p.files.filter { target(ctx, it.path)?.let { f -> !(f.exists() && f.length() == it.size) } ?: false }.sumOf { it.size }
        val margin = 64L shl 20
        if (need + margin > freeBytes(ctx)) throw IOException("NOSPACE:$need")
        val cr = ctx.contentResolver
        var files = 0; var bytes = 0L; var kept = 0
        val buf = ByteArray(256 * 1024)
        for (it in p.files) {
            val dest = target(ctx, it.path) ?: continue
            if (dest.exists()) {
                if (dest.length() != it.size) kept++ // the merged app has its own file here: keep it
                files++; bytes += it.size; progress(files, bytes); continue
            }
            dest.parentFile?.mkdirs()
            val part = File(dest.path + ".part")
            val uri = it.path.split('/').fold(base(s).buildUpon()) { b, seg -> b.appendPath(seg) }.build()
            val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Can't open ${it.path}")
            pfd.use { fd ->
                FileInputStream(fd.fileDescriptor).use { inp ->
                    part.outputStream().use { out ->
                        var lastReport = bytes
                        while (true) {
                            val n = inp.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            bytes += n
                            if (bytes - lastReport > (4L shl 20)) { lastReport = bytes; progress(files, bytes) }
                        }
                        out.fd.sync()
                    }
                }
            }
            if (part.length() != it.size) { part.delete(); throw IOException("Copy of ${it.path} is incomplete") }
            if (!part.renameTo(dest)) { part.delete(); throw IOException("Can't write ${dest.path}") }
            if (it.mtime > 0) dest.setLastModified(it.mtime)
            files++
            progress(files, bytes)
        }
        var prefs = 0
        for (name in p.prefs) {
            val b = cr.call(base(s), "prefs", name, null) ?: continue
            val target = ctx.getSharedPreferences(s.prefs[name] ?: name, Context.MODE_PRIVATE)
            if (copyPrefs(b, target)) prefs++
        }
        return Result(files, bytes, kept, prefs)
    }

    /** Writes [b] into [target] without replacing keys it already has. */
    @Suppress("DEPRECATION")
    private fun copyPrefs(b: Bundle, target: SharedPreferences): Boolean {
        val e = target.edit()
        var any = false
        for (k in b.keySet()) {
            val isSet = k.endsWith(MigrateProviderContract.SET)
            val key = if (isSet) k.removeSuffix(MigrateProviderContract.SET) else k
            if (target.contains(key)) continue
            when (val v = b.get(k)) {
                is String -> e.putString(key, v)
                is Int -> e.putInt(key, v)
                is Long -> e.putLong(key, v)
                is Float -> e.putFloat(key, v)
                is Boolean -> e.putBoolean(key, v)
                is ArrayList<*> -> if (isSet) e.putStringSet(key, v.map { it.toString() }.toSet()) else continue
                else -> continue
            }
            any = true
        }
        return e.commit() && any
    }
}

/** Constants shared with the former apps' MigrateProvider (which lives in those apps, not here). */
object MigrateProviderContract {
    const val SET = "\u0000set"
}
