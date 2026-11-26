package app.plain.setup

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

/**
 * The version each app had before its last update through Setup, kept in Setup's private storage
 * (files/backups/<package>/<versionCode>-<versionName>.apk). One copy per app: the newest one replaces it.
 * Offline, so "I updated but didn't like it" works without internet.
 */
object Backups {
    private fun dir(ctx: Context, pkg: String) = File(ctx.filesDir, "backups/$pkg")

    /** Copies the installed APK of [pkg] unless it's missing or already [newCode]. Never throws. */
    fun save(ctx: Context, pkg: String, newCode: Long) {
        try {
            val pm = ctx.packageManager
            val pi = try { pm.getPackageInfo(pkg, 0) } catch (_: PackageManager.NameNotFoundException) { return }
            @Suppress("DEPRECATION")
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            if (code == newCode) return
            val ai = pi.applicationInfo ?: return
            if (!ai.splitSourceDirs.isNullOrEmpty()) return // our apps are single APKs; base.apk alone can't restore a split app
            val src = File(ai.sourceDir)
            val d = dir(ctx, pkg).apply { mkdirs() }
            val name = fileName(code, pi.versionName)
            val dst = File(d, name)
            if (dst.isFile && dst.length() == src.length()) return
            val tmp = File(d, "$name.part")
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            d.listFiles()?.forEach { if (it != tmp) it.delete() }
            tmp.renameTo(dst)
        } catch (_: Exception) {
            // A failed backup must never block the update.
        }
    }

    /** The saved copy as an installable entry (url = local path), or null. */
    fun find(ctx: Context, current: Entry): Entry? {
        val f = dir(ctx, current.pkg).listFiles { x -> x.name.endsWith(".apk") }?.maxByOrNull { it.lastModified() } ?: return null
        val (code, name) = parseName(f.name) ?: return null
        return current.copy(versionCode = code, versionName = name, url = f.path, size = f.length(), sha256 = "", history = emptyList())
    }

    fun fileName(code: Long, versionName: String?): String =
        "$code-${(versionName ?: code.toString()).replace(Regex("[^A-Za-z0-9._-]"), "_")}.apk"

    /** "6-1.5.apk" → (6, "1.5"). */
    fun parseName(file: String): Pair<Long, String>? {
        if (!file.endsWith(".apk")) return null
        val base = file.removeSuffix(".apk")
        val dash = base.indexOf('-')
        if (dash <= 0) return null
        val code = base.substring(0, dash).toLongOrNull() ?: return null
        return code to base.substring(dash + 1)
    }
}
