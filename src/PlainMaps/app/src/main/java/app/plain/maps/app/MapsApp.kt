package app.plain.maps.app

import android.app.Application
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.maps.City
import app.plain.migrate.Migrate
import app.trekmap.trail.TrailInit

/**
 * Plain Maps (formerly Plain Istanbul Map): a tab per city (Istanbul default, Ankara, İzmir, Kocaeli, Antalya; see
 * [City.all]) and Trekking.
 *
 * Processes: Istanbul in the main one, every other city in ":<id>" (the same map code with [City.id] = that city),
 * Trekking in ":trail" (a recording never shares memory with a big city map). This runs once in each of them.
 */
class MapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val trekking = Tab("trekking", "Trekking", "Trekking", "app.trekmap.trail.MainActivity", ownProcess = true,
            import = Migrate.Spec("app.trekmap.trail", "Plain Trekking",
                "its plans, walked trails, trail photos, offline maps and settings",
                "planlarını, yürünen rotalarını, rota fotoğraflarını, çevrimdışı haritalarını ve ayarlarını",
                prefs = mapOf("app" to TrailInit.PREFS)))
        // one tab per city; only downloaded cities sit in the tab sheet, the rest wait under "Others"
        val cities = City.all.map { c ->
            Tab(c.id, c.en, c.trName, c.activity.name, ownProcess = c.id != "istanbul", shown = { ctx -> City.downloaded(ctx, c.id) })
        }
        Hub.setup(this, "Plain Maps", cities + trekking, accent = 0xFF26C6DA.toInt())
        Hub.othersNote = { app.plain.lang.tr("Not downloaded yet. Nothing downloads until you say so.",
            "Henüz indirilmedi. Siz istemeden hiçbir şey indirilmez.") }
        val proc = Hub.processName()
        val cityProc = City.all.firstOrNull { it.id != "istanbul" && proc.endsWith(":" + it.id) }
        when {
            cityProc != null -> City.id = cityProc.id
            proc.endsWith(":trail") -> {
                TrailInit.shareAuthority(this)
                // with old Plain Trekking data still to copy, nothing may load the empty state first (see Hub.gate)
                if (!Migrate.needsDecision(this, trekking)) TrailInit.init(this)
            }
            else -> City.id = "istanbul"
        }
    }
}
