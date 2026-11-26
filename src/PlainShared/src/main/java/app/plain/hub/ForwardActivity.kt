package app.plain.hub

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle

/**
 * Entry point for intents from other apps (share to Youtube, open a GPX, "Check for updates", open the gallery…).
 * Each is an `<activity-alias>` of this class in the app's manifest with `<meta-data android:name="app.plain.hub.TAB">`
 * naming the tab. It hands the intent to that tab inside the app's one task ([Hub.open]: the existing tab Activity is
 * brought to the front and gets it in onNewIntent), so no second copy of a tab is ever started. Runs in its own
 * throwaway task (taskAffinity="") and closes at once.
 */
class ForwardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tab = try {
            @Suppress("DEPRECATION")
            packageManager.getActivityInfo(componentName, PackageManager.GET_META_DATA).metaData?.getString(META_TAB)
        } catch (_: Exception) { null }
        val fwd = Intent(intent).setComponent(null)
        fwd.flags = intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        Hub.open(this, (Hub.tab(tab) ?: Hub.lastTab(this)).key, fwd)
        finish()
    }

    companion object {
        const val META_TAB = "app.plain.hub.TAB"
    }
}
