package app.plain.maps

/**
 * The city this build is for (the "istanbul" and "ankara" product flavors).
 * Everything city-specific lives here; the rest of the app is shared.
 */
object City {
    val id: String = BuildConfig.CITY
    val istanbul get() = id == "istanbul"
    val ankara get() = id == "ankara"

    val name get() = if (ankara) "Ankara" else "Istanbul"

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
        get() = if (ankara) "The map, places and metro, Ankaray, Başkentray and cable-car lines come from openstreetmap.fr's daily Central Anatolia extract (about 120 MB)."
        else "The city (Avcılar–Kartal, Başakşehir–Sarıyer) comes from a weekly OpenStreetMap extract. The outer districts come from OpenStreetMap's public server, which is often busy; then they are left out and the next sync tries again."

    val busSourceText
        get() = if (ankara) "Bus lines, stops and timetables: EGO (ego.gov.tr hareket saatleri). Stop positions and route shapes: OpenStreetMap."
        else "Bus lines, stops, route shapes and timetables: İBB Open Data Portal (İETT)."

    val downloadSize get() = if (ankara) "about 200 MB" else "about 350 MB"
}
