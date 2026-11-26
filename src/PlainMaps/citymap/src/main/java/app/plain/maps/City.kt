package app.plain.maps

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.plain.lang.tr
import java.io.File

/**
 * The city of this process. Plain Maps runs its Istanbul tab in the main process and its Ankara tab in the
 * ":ankara" process (AnkaraActivity, AnkaraSyncService); the Application sets [id] once per process, so each
 * city's statics, database and caches stay apart. Everything city-specific lives here; the rest is shared.
 *
 * Istanbul keeps the paths of the former Plain Istanbul Map (files/data.db, prefs "app"), so its synced data
 * carries on after the update. Ankara lives in files/ankara/, cache/ankara/ and prefs "app_ankara".
 */
object City {
    @Volatile
    var id: String = "istanbul"
    val istanbul get() = id == "istanbul"
    val ankara get() = id == "ankara"

    /** Where this city's data lives (data.db, sync/). */
    fun files(ctx: Context): File = if (ankara) File(ctx.filesDir, "ankara").apply { mkdirs() } else ctx.filesDir

    fun cache(ctx: Context): File = if (ankara) File(ctx.cacheDir, "ankara").apply { mkdirs() } else ctx.cacheDir

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(if (ankara) "app_ankara" else "app", Context.MODE_PRIVATE)

    /** Android/data/<app>/files/<logName>: a plain-text log of every sync, readable over adb. */
    val logName get() = if (ankara) "sync-ankara.log" else "sync.log"

    val syncService: Class<out SyncService> get() = if (ankara) AnkaraSyncService::class.java else SyncService::class.java
    val activity: Class<out Activity> get() = if (ankara) AnkaraActivity::class.java else MainActivity::class.java

    /** Sync notification id (the two cities may sync at the same time). */
    val notifId get() = if (ankara) 8 else 7

    /** For the app's name ("Plain Istanbul Map"); [localName] in running text. */
    val name get() = if (ankara) "Ankara" else "Istanbul"

    val localName get() = if (ankara) "Ankara" else tr("Istanbul", "İstanbul")

    /** Everything kept: map, places, lines and stops. */
    val area: BBox
        get() = if (ankara) BBox(31.85, 39.35, 33.55, 40.55) else BBox(27.95, 40.78, 29.95, 41.60)

    /**
     * Where to look for the city's stops. The map's final area is the box its transit spans
     * (worked out during each sync), never smaller than [area].
     */
    val searchArea: BBox
        get() = if (ankara) BBox(30.75, 38.55, 34.15, 40.80) else BBox(27.0, 40.3, 30.6, 41.9)

    /** Where the map opens the first time. */
    val centerLat get() = if (ankara) 39.92 else 41.03
    val centerLon get() = if (ankara) 32.85 else 28.98

    /**
     * The OpenStreetMap extract downloaded for the map. Istanbul: BBBike's weekly city-core extract
     * (the outer districts come from Overpass). Ankara: openstreetmap.fr's daily Central Anatolia
     * extract, which covers the whole area, cut down to [area] while it is read.
     */
    val mapUrl
        get() = if (ankara) "https://download.openstreetmap.fr/extracts/europe/turkey/central_anatolia-latest.osm.pbf"
        else "https://download.bbbike.org/osm/bbbike/Istanbul/Istanbul.osm.pbf"

    /** The extract covers all of [area], so nothing is fetched from Overpass. */
    val mapCoversArea get() = ankara

    /** Who runs the buses, for texts. */
    val operator get() = if (ankara) "EGO" else "İETT"

    val mapSourceText
        get() = if (ankara) tr("The map, places and metro, Ankaray, Başkentray and cable-car lines come from openstreetmap.fr's daily Central Anatolia extract (about 120 MB).",
            "Harita, yerler ve metro, Ankaray, Başkentray ve teleferik hatları openstreetmap.fr'nin günlük İç Anadolu dosyasından gelir (yaklaşık 120 MB).")
        else tr("The city (Avcılar–Kartal, Başakşehir–Sarıyer) comes from a weekly OpenStreetMap extract. The outer districts come from OpenStreetMap's public server, which is often busy; then they are left out and the next sync tries again.",
            "Şehir (Avcılar–Kartal, Başakşehir–Sarıyer) haftalık bir OpenStreetMap dosyasından gelir. Dış ilçeler OpenStreetMap'in genel sunucusundan gelir; sunucu sık sık meşgul olur, o zaman dış ilçeler atlanır ve bir sonraki eşitleme yeniden dener.")

    val busSourceText
        get() = if (ankara) tr("Bus lines, stops and timetables: EGO (ego.gov.tr hareket saatleri). Stop positions and route shapes: OpenStreetMap.",
            "Otobüs hatları, duraklar ve saatler: EGO (ego.gov.tr hareket saatleri). Durak konumları ve güzergâh çizimleri: OpenStreetMap.")
        else tr("Bus lines, stops, route shapes and timetables: İBB Open Data Portal (İETT).",
            "Otobüs hatları, duraklar, güzergâh çizimleri ve saatler: İBB Açık Veri Portalı (İETT).")

    val downloadSize get() = if (ankara) tr("about 200 MB", "yaklaşık 200 MB") else tr("about 350 MB", "yaklaşık 350 MB")
}
