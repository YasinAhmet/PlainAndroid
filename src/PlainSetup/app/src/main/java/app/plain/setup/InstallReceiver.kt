package app.plain.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives PackageInstaller session results and hands them to [Installer]. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Installer.ACTION_STATUS) return
        val pkg = i.getStringExtra("pkg") ?: return
        val status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        @Suppress("DEPRECATION")
        val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else i.getParcelableExtra(Intent.EXTRA_INTENT)
        Installer.onResult(c.applicationContext, pkg, status, msg, confirm)
    }
}
