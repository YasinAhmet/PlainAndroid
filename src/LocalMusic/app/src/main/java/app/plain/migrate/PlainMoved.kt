package app.plain.migrate

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import app.plain.lang.tr

/**
 * The notice a former app shows after it was merged into a bigger Plain app: "Plain Trekking is now part of
 * Plain Maps". It never blocks the app: this copy keeps working, with its data, until the user removes it.
 *
 * Canonical copy: A:/Projects/PlainShared/bridge/PlainMoved.kt (identical in every former app).
 */
object PlainMoved {
    private var shownThisRun = false

    /**
     * Call at the end of the main Activity's onCreate. [intoPkg]/[intoName] is the new app ("Plain Maps"),
     * [tabKey] its tab for this app, [tabEn]/[tabTr] that tab's name.
     */
    fun notice(a: Activity, myName: String, intoName: String, intoPkg: String, tabKey: String, tabEn: String, tabTr: String) {
        val prefs = a.getSharedPreferences("plain_moved", Context.MODE_PRIVATE)
        if (shownThisRun || prefs.getBoolean("never", false)) return
        shownThisRun = true
        val installed = a.packageManager.getLaunchIntentForPackage(intoPkg) != null
        val msg = tr(
            "$myName is now part of $intoName, as its \"$tabEn\" tab.\n\n" +
                (if (installed) "Open $intoName and pick $tabEn: it offers to copy everything from this app (it needs this version or newer). "
                else "Install $intoName from Plain Manager (Plain Setup). Its $tabEn tab offers to copy everything from this app. ") +
                "Nothing here is changed or deleted. Once your data is there, you can remove $myName.",
            "$myName artık $intoName uygulamasının \"$tabTr\" sekmesi.\n\n" +
                (if (installed) "$intoName'i açıp $tabTr'yi seçin: bu uygulamadaki her şeyi kopyalamayı önerir (bu sürüm ya da daha yenisi gerekir). "
                else "$intoName'i Plain Manager'dan (Plain Setup) yükleyin. $tabTr sekmesi bu uygulamadaki her şeyi kopyalamayı önerir. ") +
                "Burada hiçbir şey değişmez ya da silinmez. Verileriniz oraya geçince $myName'i kaldırabilirsiniz."
        )
        AlertDialog.Builder(a)
            .setTitle(tr("$myName moved", "$myName taşındı"))
            .setMessage(msg)
            .setPositiveButton(if (installed) tr("Open $intoName", "$intoName'i aç") else tr("Get $intoName", "$intoName'i edin")) { _, _ ->
                try {
                    if (installed) a.startActivity(a.packageManager.getLaunchIntentForPackage(intoPkg)!!.putExtra("app.plain.hub.TAB", tabKey))
                    else a.startActivity(Intent("app.plain.setup.UPDATE").setPackage("app.plain.setup").putExtra("package", intoPkg))
                } catch (_: Exception) {}
            }
            .setNeutralButton(tr("Don't show again", "Bir daha gösterme")) { _, _ -> prefs.edit().putBoolean("never", true).apply() }
            .setNegativeButton(tr("Later", "Sonra"), null)
            .show()
    }
}
