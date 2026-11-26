package app.plain.manager

import android.app.Application
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.migrate.Migrate

/** Plain Manager (formerly Plain Setup): Setup (default), Battery and Uninstaller tabs. */
class ManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.setup(this, "Plain Manager", listOf(
            Tab("setup", "Setup", "Kurulum", "app.plain.setup.SetupActivity"),
            Tab("battery", "Battery", "Pil", "app.plain.battery.MainActivity", minSdk = 29,
                import = Migrate.Spec("app.plain.battery", "Plain Battery",
                    "its battery history and settings", "pil geçmişini ve ayarlarını")),
            Tab("uninstaller", "Uninstaller", "Kaldırıcı", "app.plain.uninstaller.MainActivity",
                import = Migrate.Spec("app.plain.uninstaller", "Plain Uninstaller",
                    "its list of removed system apps (for restoring them) and settings",
                    "kaldırılan sistem uygulamaları listesini (geri yüklemek için) ve ayarlarını")),
        ))
    }
}
