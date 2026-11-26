package app.plain.uninstaller

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.webkit.WebView
import java.io.File

/** One row of the Apps list. */
data class AppItem(val pkg: String, val label: String, val system: Boolean, val level: Level)

/** One row of the Restore list. */
data class RestoreItem(val pkg: String, val label: String, val removedByThisApp: Boolean)

object Apps {
    fun isSystem(ai: ApplicationInfo): Boolean =
        ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /** Reads the phone's current default launcher, keyboard, dialer, SMS app and WebView. */
    fun defaults(ctx: Context): Defaults {
        val pm = ctx.packageManager
        val launcher = try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        } catch (_: Exception) { null }
        val ime = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) } catch (_: Exception) { null }
        val dialer = try { (ctx.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage } catch (_: Exception) { null }
        val sms = try { Telephony.Sms.getDefaultSmsPackage(ctx) } catch (_: Exception) { null }
        val webView = try { WebView.getCurrentWebViewPackage()?.packageName } catch (_: Throwable) { null }
        return Defaults(ctx.packageName, launcher, ime, dialer, sms, webView)
    }

    /** Installed apps minus the protected ones, sorted by label. Slow (labels): call off the main thread. */
    fun load(ctx: Context, d: Defaults): List<AppItem> {
        val pm = ctx.packageManager
        val list = try { pm.getInstalledApplications(0) } catch (_: Exception) { emptyList() }
        return list.mapNotNull { ai ->
            val level = Safety.level(ai.packageName, d)
            if (level == Level.HIDDEN) return@mapNotNull null
            val label = try { ai.loadLabel(pm).toString().trim() } catch (_: Exception) { "" }
            AppItem(ai.packageName, label.ifEmpty { ai.packageName }, isSystem(ai), level)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it: AppItem -> it.label }.thenBy { it.pkg })
    }

    fun isInstalled(ctx: Context, pkg: String): Boolean = try {
        ctx.packageManager.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_INSTALLED != 0
    } catch (_: Exception) { false }

    /** Label of a package even if it is uninstalled for this user (still on the system image). */
    fun labelOf(ctx: Context, pkg: String, fallback: String?): String {
        val pm = ctx.packageManager
        return try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(pm).toString().trim()
                .ifEmpty { fallback ?: pkg }
        } catch (_: Exception) { fallback?.takeIf { it.isNotBlank() } ?: pkg }
    }

    /**
     * Without Shizuku: the same "all minus installed" diff, from PackageManager. MATCH_UNINSTALLED_PACKAGES
     * includes packages removed with `pm uninstall -k --user 0`; FLAG_INSTALLED tells them apart.
     */
    fun packageSetsFromPm(ctx: Context): Pair<Set<String>, Set<String>> {
        val all = try {
            ctx.packageManager.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (_: Exception) { emptyList() }
        val installed = all.filter { it.flags and ApplicationInfo.FLAG_INSTALLED != 0 }.map { it.packageName }.toSet()
        return all.map { it.packageName }.toSet() to installed
    }

    // --- removal log (files dir) ---

    private fun logFile(ctx: Context) = File(ctx.filesDir, RemovedLog.FILE)

    @Synchronized
    fun readLog(ctx: Context): List<LogEntry> = try {
        val f = logFile(ctx)
        if (f.exists()) RemovedLog.parse(f.readText()) else emptyList()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun appendLog(ctx: Context, e: LogEntry) {
        try {
            val f = logFile(ctx)
            if (!f.exists() || f.length() == 0L) f.writeText(RemovedLog.HEADER + "\n")
            f.appendText(RemovedLog.row(e) + "\n")
        } catch (_: Exception) {
        }
    }
}
