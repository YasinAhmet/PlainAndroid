package app.plain.update

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri

/**
 * The "Check for updates" button every Plain app has. It needs no internet permission: it hands
 * the job to Plain Setup (app.plain.setup), which downloads the newest APK from the PlainAndroid
 * GitHub releases, checks it is signed with the Plain key, and installs it.
 *
 * Canonical copy: PlainAndroid/shared/PlainUpdate.kt. Every app keeps an identical copy at
 * app/src/main/java/app/plain/update/PlainUpdate.kt — change them together.
 */
object PlainUpdate {
    const val SETUP_PKG = "app.plain.setup"
    const val ACTION = "app.plain.setup.UPDATE"
    const val EXTRA_PACKAGE = "package"
    /** Plain Setup is always the newest release, so this link always serves the current Setup. */
    const val SETUP_DOWNLOAD = "https://github.com/YasinAhmet/PlainAndroid/releases/latest/download/PlainSetup.apk"

    /** Button text, e.g. "Check for updates (v1.4)". */
    fun label(a: Activity): String {
        val v = try { a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { null }
        return if (v.isNullOrBlank()) "Check for updates" else "Check for updates (v$v)"
    }

    /** Opens Plain Setup on this app. Without Setup, offers to download it in the browser. */
    fun check(a: Activity) {
        val i = Intent(ACTION).setPackage(SETUP_PKG).putExtra(EXTRA_PACKAGE, a.packageName)
        try {
            a.startActivity(i)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(a)
                .setTitle("Plain Setup needed")
                .setMessage("Updates are installed by the Plain Setup app. Download it, install it, then tap Check for updates again.")
                .setPositiveButton("Download") { _, _ ->
                    try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SETUP_DOWNLOAD))) } catch (_: Exception) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
        } catch (_: SecurityException) {
        }
    }
}
