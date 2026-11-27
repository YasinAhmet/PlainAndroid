package app.plain.maps

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import app.plain.lang.tr
import java.io.File

/**
 * The city of this process. Plain Maps runs each city tab in its own process: Istanbul in the main one, the others in
 * ":<id>" (AnkaraActivity/AnkaraSyncService, IzmirActivity …). The Application sets [id] once per process, so each
 * city's statics, database and caches stay apart. Everything city-specific lives in [Def]; the rest is shared.
 *
 * Istanbul keeps the paths of the former Plain Istanbul Map (files/data.db, prefs "app"), so its synced data
 * carries on after the update. Every other city lives in files/<id>/, cache/<id>/ and prefs "app_<id>".
 */
object City {
    /** Where a city's buses come from. */
    enum class Bus {
        /** İBB open data: İETT's GTFS (with its quirks) and route-shape GeoJSON. */
        IETT,
        /** ego.gov.tr timetable pages, placed on the map with OpenStreetMap's stops and routes. */
        EGO,
        /** A plain GTFS zip ([Def.gtfsUrl]). */
        GTFS,
        /** Only OpenStreetMap's bus routes and stops (no timetables). */
        OSM,
    }

    class Def(
        val id: String,
        val en: String,
        val trName: String,
        /** Everything kept, at least: map, places, lines and stops. */
        val area: BBox,
        /** Where to look for the city's stops; the map's final area is the box its transit spans, never smaller than [area]. */
        val searchArea: BBox,
        val centerLat: Double,
        val centerLon: Double,
        /** The OpenStreetMap extract downloaded for the map. */
        val mapUrl: String,
        /** The extract covers all of [area], so nothing is fetched from Overpass. */
        val mapCoversArea: Boolean,
        val bus: Bus,
        /** Who runs the buses, for texts. */
        val operator: String,
        val mapSource: Pair<String, String>,
        val busSource: Pair<String, String>,
        val size: Pair<String, String>,
        /** Sync notification id (cities may sync at the same time). */
        val notifId: Int,
        val gtfsUrl: String? = null,
        val activity: Class<out Activity>,
        val syncService: Class<out SyncService>,
    ) {
        val label get() = tr(en, trName)
    }

    private fun osmFr(region: String) = "https://download.openstreetmap.fr/extracts/europe/turkey/$region-latest.osm.pbf"

    /** Every city, in the order the tab menu lists them. */
    val all: List<Def> by lazy {
        listOf(
            Def("istanbul", "Istanbul", "İstanbul",
                BBox(27.95, 40.78, 29.95, 41.60), BBox(27.0, 40.3, 30.6, 41.9), 41.03, 28.98,
                "https://download.bbbike.org/osm/bbbike/Istanbul/Istanbul.osm.pbf", false, Bus.IETT, "İETT",
                "The city (Avcılar–Kartal, Başakşehir–Sarıyer) comes from a weekly OpenStreetMap extract. The outer districts come from OpenStreetMap's public server, which is often busy; then they are left out and the next sync tries again." to
                    "Şehir (Avcılar–Kartal, Başakşehir–Sarıyer) haftalık bir OpenStreetMap dosyasından gelir. Dış ilçeler OpenStreetMap'in genel sunucusundan gelir; sunucu sık sık meşgul olur, o zaman dış ilçeler atlanır ve bir sonraki eşitleme yeniden dener.",
                "Bus lines, stops, route shapes and timetables: İBB Open Data Portal (İETT)." to
                    "Otobüs hatları, duraklar, güzergâh çizimleri ve saatler: İBB Açık Veri Portalı (İETT).",
                "about 350 MB" to "yaklaşık 350 MB", 7,
                activity = MainActivity::class.java, syncService = SyncService::class.java),
            Def("ankara", "Ankara", "Ankara",
                BBox(31.85, 39.35, 33.55, 40.55), BBox(30.75, 38.55, 34.15, 40.80), 39.92, 32.85,
                osmFr("central_anatolia"), true, Bus.EGO, "EGO",
                "The map, places and metro, Ankaray, Başkentray and cable-car lines come from openstreetmap.fr's daily Central Anatolia extract (about 120 MB)." to
                    "Harita, yerler ve metro, Ankaray, Başkentray ve teleferik hatları openstreetmap.fr'nin günlük İç Anadolu dosyasından gelir (yaklaşık 120 MB).",
                "Bus lines, stops and timetables: EGO (ego.gov.tr hareket saatleri). Stop positions and route shapes: OpenStreetMap." to
                    "Otobüs hatları, duraklar ve saatler: EGO (ego.gov.tr hareket saatleri). Durak konumları ve güzergâh çizimleri: OpenStreetMap.",
                "about 200 MB" to "yaklaşık 200 MB", 8,
                activity = AnkaraActivity::class.java, syncService = AnkaraSyncService::class.java),
            Def("izmir", "Izmir", "İzmir",
                BBox(26.70, 38.25, 27.40, 38.62), BBox(26.20, 37.80, 28.50, 39.40), 38.42, 27.14,
                osmFr("aegean"), true, Bus.GTFS, "ESHOT",
                "The map, places and metro, tram, İZBAN and ferry lines come from openstreetmap.fr's daily Aegean extract (about 90 MB)." to
                    "Harita, yerler ve metro, tramvay, İZBAN ve vapur hatları openstreetmap.fr'nin günlük Ege dosyasından gelir (yaklaşık 90 MB).",
                "Bus lines, stops, route shapes and timetables: ESHOT's GTFS feed (eshot.gov.tr, İzmir Open Data)." to
                    "Otobüs hatları, duraklar, güzergâh çizimleri ve saatler: ESHOT GTFS verisi (eshot.gov.tr, İzmir Açık Veri).",
                "about 110 MB" to "yaklaşık 110 MB", 9,
                gtfsUrl = "https://www.eshot.gov.tr/gtfs/bus-eshot-gtfs.zip",
                activity = IzmirActivity::class.java, syncService = IzmirSyncService::class.java),
            Def("kocaeli", "Kocaeli", "Kocaeli",
                BBox(29.70, 40.65, 30.10, 40.85), BBox(29.00, 40.40, 30.50, 41.30), 40.765, 29.94,
                osmFr("marmara"), true, Bus.GTFS, "Kocaeli BB",
                "The map, places and tram and rail lines come from openstreetmap.fr's daily Marmara extract (about 170 MB)." to
                    "Harita, yerler ve tramvay ve tren hatları openstreetmap.fr'nin günlük Marmara dosyasından gelir (yaklaşık 170 MB).",
                "Bus lines, stops, route shapes and timetables: Kocaeli Metropolitan Municipality's GTFS data (veri.kocaeli.bel.tr, CC BY)." to
                    "Otobüs hatları, duraklar, güzergâh çizimleri ve saatler: Kocaeli Büyükşehir Belediyesi GTFS verisi (veri.kocaeli.bel.tr, CC BY).",
                "about 280 MB" to "yaklaşık 280 MB", 10,
                gtfsUrl = "https://kavisacikveri.kocaeli.bel.tr/api/public/OpenDataPublic/e2a87342-d39a-4742-ae25-165e10d2bc72",
                activity = KocaeliActivity::class.java, syncService = KocaeliSyncService::class.java),
            Def("antalya", "Antalya", "Antalya",
                BBox(30.50, 36.80, 30.95, 37.05), BBox(29.20, 36.00, 32.70, 37.60), 36.89, 30.70,
                osmFr("mediterranean"), true, Bus.OSM, "Antalya Ulaşım",
                "The map, places and tram and ferry lines come from openstreetmap.fr's daily Mediterranean extract (about 100 MB)." to
                    "Harita, yerler ve tramvay ve vapur hatları openstreetmap.fr'nin günlük Akdeniz dosyasından gelir (yaklaşık 100 MB).",
                "Bus lines and stops: OpenStreetMap (no timetables)." to
                    "Otobüs hatları ve duraklar: OpenStreetMap (saatler yok).",
                "about 110 MB" to "yaklaşık 110 MB", 11,
                activity = AntalyaActivity::class.java, syncService = AntalyaSyncService::class.java),
        )
    }

    fun def(id: String): Def? = all.firstOrNull { it.id == id }

    @Volatile
    var id: String = "istanbul"

    val d: Def get() = def(id) ?: all[0]

    val istanbul get() = id == "istanbul"
    val ankara get() = id == "ankara"
    val bus get() = d.bus

    /** Situational sync (announcements, today's times) exists only where the operator has a service for it. */
    val situational get() = bus == Bus.IETT || bus == Bus.EGO

    /** True when the city's buses come with timetables. */
    val timetables get() = bus != Bus.OSM

    /** Where city [cid]'s data lives (data.db, sync/). */
    fun files(ctx: Context, cid: String): File = if (cid == "istanbul") ctx.filesDir else File(ctx.filesDir, cid)

    /** True when city [cid] has synced data (its tab shows in the tab menu; the others wait under "Others"). */
    fun downloaded(ctx: Context, cid: String) = File(files(ctx, cid), "data.db").exists()

    /** Where this city's data lives (data.db, sync/). */
    fun files(ctx: Context): File = files(ctx, id).apply { mkdirs() }

    fun cache(ctx: Context): File = if (istanbul) ctx.cacheDir else File(ctx.cacheDir, id).apply { mkdirs() }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(if (istanbul) "app" else "app_$id", Context.MODE_PRIVATE)

    /** Android/data/<app>/files/<logName>: a plain-text log of every sync, readable over adb. */
    val logName get() = if (istanbul) "sync.log" else "sync-$id.log"

    val syncService: Class<out SyncService> get() = d.syncService
    val activity: Class<out Activity> get() = d.activity
    val notifId get() = d.notifId

    /** For the app's name ("Plain Istanbul Map"); [localName] in running text. */
    val name get() = d.en
    val localName get() = d.label

    val area: BBox get() = d.area
    val searchArea: BBox get() = d.searchArea
    val centerLat get() = d.centerLat
    val centerLon get() = d.centerLon
    val mapUrl get() = d.mapUrl
    val mapCoversArea get() = d.mapCoversArea
    val operator get() = d.operator
    val mapSourceText get() = tr(d.mapSource.first, d.mapSource.second)
    val busSourceText get() = tr(d.busSource.first, d.busSource.second)
    val downloadSize get() = tr(d.size.first, d.size.second)
}
