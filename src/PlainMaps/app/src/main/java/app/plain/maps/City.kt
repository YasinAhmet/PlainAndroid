package app.plain.maps

import app.plain.lang.tr

/**
 * The city this build is for (the "istanbul" and "ankara" product flavors).
 * Everything city-specific lives here; the rest of the app is shared.
 */
object City {
    val id: String = BuildConfig.CITY
    val istanbul get() = id == "istanbul"
    val ankara get() = id == "ankara"

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
