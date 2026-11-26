package app.plain.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import app.plain.lang.tr

/**
 * The "Check for updates" button every Plain app has. It needs no internet permission: it hands
 * the job to Plain Manager (formerly Plain Setup, package app.plain.setup), which downloads the newest
 * APK from the PlainAndroid GitHub releases, checks it is signed with the Plain key, and installs it.
 *
 * Canonical copy: A:/Projects/PlainShared (the merged apps' :shared module), mirrored to
 * PlainAndroid/shared/PlainUpdate.kt. Older single apps keep their own copies. Needs PlainLang.kt.
 */
object PlainUpdate {
    const val SETUP_PKG = "app.plain.setup"
    const val ACTION = "app.plain.setup.UPDATE"
    const val EXTRA_PACKAGE = "package"
    /** Plain Manager is always the newest release (its file keeps the old name), so this link always serves it. */
    const val SETUP_DOWNLOAD = "https://github.com/YasinAhmet/PlainAndroid/releases/latest/download/PlainSetup.apk"

    /** Button text, e.g. "Check for updates (v1.4)". */
    fun label(a: Activity): String {
        val v = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { null }
        val t = tr("Check for updates", "Güncellemeleri denetle")
        return if (v.isNullOrBlank()) t else "$t (v$v)"
    }

    /** Opens Plain Manager on this app. Without it, offers to download it in the browser. */
    fun check(a: Activity) {
        val i = Intent(ACTION).setPackage(SETUP_PKG).putExtra(EXTRA_PACKAGE, a.packageName)
        try {
            a.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(a)
                .setTitle(tr("Plain Manager needed", "Plain Manager gerekli"))
                .setMessage(tr("Updates are installed by the Plain Manager app (formerly Plain Setup). Download it, install it, then tap Check for updates again.", "Güncellemeleri Plain Manager uygulaması (eski adıyla Plain Setup) yükler. Onu indirip yükleyin, sonra Güncellemeleri denetle'ye yeniden dokunun."))
                .setPositiveButton(tr("Download", "İndir")) { _, _ ->
                    try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_DOWNLOAD))) } catch (_: Exception) {}
                }
                .setNegativeButton(tr("Cancel", "İptal"), null)
                .show()
        } catch (_: SecurityException) {
        }
    }
}
