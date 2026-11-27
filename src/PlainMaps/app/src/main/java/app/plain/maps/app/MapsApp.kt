package app.plain.maps.app

import android.app.Application
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.maps.City
import app.plain.migrate.Migrate
import app.trekmap.trail.TrailInit

/**
 * Plain Maps (formerly Plain Istanbul Map): Istanbul (default), Ankara and Trekking tabs.
 *
 * Processes: Istanbul in the main one, Ankara in ":ankara" (the same map code with [City.id] = "ankara"), Trekking in
 * ":trail" (a recording never shares memory with a big city map). This runs once in each of them.
 */
class MapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val trekking = Tab("trekking", "Trekking", "Trekking", "app.trekmap.trail.MainActivity", ownProcess = true,
            import = Migrate.Spec("app.trekmap.trail", "Plain Trekking",
                "its plans, walked trails, trail photos, offline maps and settings",
                "planlarını, yürünen rotalarını, rota fotoğraflarını, çevrimdışı haritalarını ve ayarlarını",
                prefs = mapOf("app" to TrailInit.PREFS)))
        Hub.setup(this, "Plain Maps", listOf(
            Tab("istanbul", "Istanbul", "İstanbul", "app.plain.maps.MainActivity"),
            Tab("ankara", "Ankara", "Ankara", "app.plain.maps.AnkaraActivity", ownProcess = true),
            trekking,
        ), accent = 0xFF26C6DA.toInt())
        val proc = Hub.processName()
        when {
            proc.endsWith(":ankara") -> City.id = "ankara"
            proc.endsWith(":trail") -> {
                TrailInit.shareAuthority(this)
                // with old Plain Trekking data still to copy, nothing may load the empty state first (see Hub.gate)
                if (!Migrate.needsDecision(this, trekking)) TrailInit.init(this)
            }
            else -> City.id = "istanbul"
        }
    }
}
