package app.plain.maps

/**
 * Turns Overpass elements into map pieces. Pure Kotlin, so it is tested with real Overpass answers.
 * [streets] receives (name, lon, lat) of named streets for search; [coast] receives coastline ways.
 */
class MapBuild(
    private val pieces: Pieces,
    private val streets: (String, Double, Double) -> Unit,
    private val coast: (Long, DoubleArray) -> Unit,
) {
    var features = 0
        private set

    companion object {
        fun roadKind(hw: String): Int? = when (hw) {
            "motorway", "motorway_link" -> K.MOTORWAY
            "trunk", "trunk_link" -> K.TRUNK
            "primary", "primary_link" -> K.PRIMARY
            "secondary", "secondary_link" -> K.SECONDARY
            "tertiary", "tertiary_link" -> K.TERTIARY
            "residential", "unclassified", "living_street", "road" -> K.MINOR
            "service" -> K.SERVICE
            "pedestrian", "footway", "steps", "path", "cycleway", "corridor" -> K.PATH
            else -> null
        }

        fun areaKind(t: Map<String, String>): Int? {
            val nat = t["natural"]; val lu = t["landuse"]; val le = t["leisure"]
            return when {
                nat == "water" || lu == "reservoir" || lu == "basin" -> K.WATER
                nat == "wood" || lu == "forest" -> K.FOREST
                lu == "cemetery" || t["amenity"] == "grave_yard" -> K.CEMETERY
                le == "park" || le == "garden" || lu == "recreation_ground" || lu == "grass" || lu == "village_green" -> K.PARK
                else -> null
            }
        }

        /** The first level a road class appears in. */
        fun roadLevel(kind: Int, hw: String): Int = when (kind) {
            K.MOTORWAY, K.TRUNK, K.PRIMARY -> if (hw.endsWith("_link")) 1 else 0
            K.SECONDARY, K.TERTIARY -> 1
            else -> 2
        }
    }

    fun element(e: OsmEl) {
        val t = e.tags
        when (e.type) {
            'w' -> way(e, t)
            'r' -> relation(e, t)
        }
    }

    private fun way(e: OsmEl, t: Map<String, String>) {
        val g = e.geom ?: return
        if (g.size < 4) return
        if (t["natural"] == "coastline") { coast(e.id, g); return }
        val hw = t["highway"]
        if (hw != null) {
            val k = roadKind(hw) ?: return
            if (t["area"] == "yes" && k == K.PATH) return
            val name = t["name"]
            pieces.line(k, Pieces.merc(g), roadLevel(k, hw), name = name, nameLvl = 2)
            if (name != null && k != K.PATH && k != K.SERVICE) {
                val m = g.size / 4 * 2
                streets(name, g[m], g[m + 1])
            }
            features++
            return
        }
        val rw = t["railway"]
        if (rw != null) {
            if (t["tunnel"].let { it != null && it != "no" }) return // underground: the line layer shows those
            if (rw == "abandoned" || rw == "disused" || rw == "construction" || rw == "proposed") return
            pieces.line(K.RAILWAY, Pieces.merc(g), 1)
            features++
            return
        }
        val ak = areaKind(t) ?: return
        if (!Clip.closed(g)) return
        pieces.polygon(ak, listOf(Pieces.merc(g)), if (ak == K.WATER) 0 else 1)
        features++
    }

    private fun relation(e: OsmEl, t: Map<String, String>) {
        if (t["type"] != "multipolygon") return
        val ak = areaKind(t) ?: return
        val ways = e.members?.filter { it.type == 'w' && it.geom != null && (it.role == "outer" || it.role == "inner" || it.role.isEmpty()) }
            ?.map { it.geom!! } ?: return
        val (rings, _) = Coast.join(ways, directed = false)
        if (rings.isEmpty()) return
        pieces.polygon(ak, rings.map { Pieces.merc(it) }, if (ak == K.WATER) 0 else 1)
        features++
    }
}

/** Places (shops, food, health …): categories, labels and the details kept for the info sheet. */
object Poi {
    const val OTHER = 0
    const val FOOD = 1
    const val SHOP = 2
    const val GROCERY = 3
    const val HEALTH = 4
    const val MONEY = 5
    const val TRANSPORT = 6
    const val CULTURE = 7
    const val LODGING = 8
    const val WORSHIP = 9
    const val EDUCATION = 10
    const val PUBLIC = 11
    const val LEISURE = 12
    const val PLACE = 20

    val COLORS = mapOf(
        OTHER to 0xFF9E9E9E.toInt(), FOOD to 0xFFFF8A65.toInt(), SHOP to 0xFF7986CB.toInt(),
        GROCERY to 0xFF81C784.toInt(), HEALTH to 0xFFEF5350.toInt(), MONEY to 0xFFFFD54F.toInt(),
        TRANSPORT to 0xFF4DD0E1.toInt(), CULTURE to 0xFFBA68C8.toInt(), LODGING to 0xFFF06292.toInt(),
        WORSHIP to 0xFFA1887F.toInt(), EDUCATION to 0xFF90A4AE.toInt(), PUBLIC to 0xFF4FC3F7.toInt(),
        LEISURE to 0xFF66BB6A.toInt(), PLACE to 0xFFFFFFFF.toInt(),
    )

    fun color(cat: Int) = COLORS[cat] ?: 0xFF9E9E9E.toInt()

    private val FOODS = setOf("restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court", "nightclub", "biergarten")
    private val HEALTHS = setOf("pharmacy", "hospital", "clinic", "doctors", "dentist", "veterinary")
    private val MONEYS = setOf("bank", "atm", "bureau_de_change")
    private val TRANSPORTS = setOf("bus_station", "ferry_terminal", "fuel", "taxi")
    private val CULTURES = setOf("cinema", "theatre", "arts_centre")
    private val EDUS = setOf("school", "university", "college", "kindergarten", "library")
    private val PUBLICS = setOf("post_office", "police", "fire_station", "townhall", "courthouse", "community_centre", "toilets", "marketplace")
    private val GROCERIES = setOf("supermarket", "convenience", "greengrocer", "butcher", "bakery", "pastry", "deli", "dairy", "seafood", "beverages")

    /** Category of a place, or null if it isn't one the app keeps. */
    fun category(t: Map<String, String>): Int? {
        val a = t["amenity"]; val s = t["shop"]; val tr = t["tourism"]; val le = t["leisure"]
        return when {
            t["place"] != null -> PLACE
            a in FOODS -> FOOD
            a in HEALTHS || t["healthcare"] != null -> HEALTH
            a in MONEYS -> MONEY
            a in TRANSPORTS || t["railway"] == "station" || t["public_transport"] == "station" -> TRANSPORT
            a == "place_of_worship" -> WORSHIP
            a in EDUS -> EDUCATION
            a in PUBLICS -> PUBLIC
            a in CULTURES -> CULTURE
            s in GROCERIES -> GROCERY
            s != null -> SHOP
            tr == "hotel" || tr == "hostel" || tr == "guest_house" || tr == "motel" || tr == "apartment" -> LODGING
            tr != null -> CULTURE
            le != null -> LEISURE
            else -> null
        }
    }

    /** 1 city … 6 hamlet; smaller is bigger. */
    fun placeRank(t: Map<String, String>): Int = when (t["place"]) {
        "city" -> 1
        "town" -> 2
        "suburb", "borough" -> 3
        "quarter", "island", "village" -> 4
        "neighbourhood" -> 5
        else -> 6
    }

    /** "fast_food" → "Fast food". */
    fun pretty(v: String) = v.replace('_', ' ').replaceFirstChar { it.uppercase() }

    fun label(t: Map<String, String>): String {
        t["cuisine"]?.let { c -> if (t["amenity"] == "restaurant") return "Restaurant · " + c.split(';').joinToString(", ") { pretty(it) } }
        for (k in listOf("amenity", "shop", "tourism", "healthcare", "leisure", "place", "railway", "public_transport", "office")) {
            val v = t[k] ?: continue
            if (v == "yes") return pretty(k)
            return if (k == "shop") pretty(v) + " shop".takeIf { !v.contains("market") && v != "bakery" && v != "kiosk" }.orEmpty() else pretty(v)
        }
        return "Place"
    }

    private val SKIP = setOf("source", "note", "fixme", "FIXME", "check_date", "survey:date", "created_by", "wikidata", "brand:wikidata", "operator:wikidata", "network:wikidata")

    /** The tags worth keeping for the info sheet, as "key=value" lines. */
    fun info(t: Map<String, String>): String {
        val sb = StringBuilder()
        for ((k, v) in t.toSortedMap()) {
            if (k in SKIP || k.startsWith("source:") || k.startsWith("ref:") || k.startsWith("old_name") || k.startsWith("alt_name:")) continue
            if (k.startsWith("name:") && k != "name:en") continue
            if (k.startsWith("brand:") || k.startsWith("operator:") || k.startsWith("not:")) continue
            sb.append(k).append('=').append(v.replace('\n', ' ')).append('\n')
        }
        return sb.toString()
    }
}

/** Which OSM elements the app keeps, the same for the downloaded extract and the Overpass queries. */
object Filters {
    private val HW = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link",
        "tertiary_link", "unclassified", "residential", "living_street", "road", "pedestrian", "steps", "path", "cycleway",
    )
    private val RAIL = setOf("rail", "subway", "tram", "light_rail", "funicular", "narrow_gauge", "monorail")
    private val LANDUSE = setOf("forest", "cemetery", "reservoir", "basin", "recreation_ground", "village_green")
    private val AMENITY = setOf(
        "restaurant", "cafe", "fast_food", "bar", "pub", "ice_cream", "food_court", "nightclub", "pharmacy", "hospital", "clinic",
        "doctors", "dentist", "veterinary", "bank", "atm", "bureau_de_change", "bus_station", "ferry_terminal", "fuel", "taxi",
        "place_of_worship", "school", "university", "college", "kindergarten", "library", "post_office", "police", "fire_station",
        "townhall", "courthouse", "community_centre", "toilets", "marketplace", "cinema", "theatre", "arts_centre",
    )
    private val TOURISM = setOf("museum", "attraction", "hotel", "hostel", "guest_house", "motel", "apartment", "viewpoint", "gallery", "information", "zoo", "theme_park", "aquarium")
    private val LEISURE = setOf("park", "garden", "sports_centre", "stadium", "fitness_centre", "swimming_pool", "playground", "water_park", "marina")
    private val PLACES = setOf("city", "town", "suburb", "borough", "quarter", "neighbourhood", "village", "hamlet", "island")
    private val ROUTES = setOf("subway", "tram", "light_rail", "funicular", "aerialway", "monorail", "ferry")
    private val TRAIN_REF = Regex("^(B[0-9]|T[0-9]|M[0-9])")

    fun mapWay(t: Map<String, String>): Boolean {
        val hw = t["highway"]
        if (hw != null) return hw in HW ||
            (hw == "service" && t["service"] !in setOf("parking_aisle", "driveway", "drive-through")) ||
            (hw == "footway" && t["footway"] !in setOf("sidewalk", "crossing"))
        val nat = t["natural"]
        return t["railway"] in RAIL || nat == "coastline" || nat == "water" || nat == "wood" ||
            t["landuse"] in LANDUSE || t["leisure"] == "park" || t["leisure"] == "garden"
    }

    fun multipolygon(t: Map<String, String>): Boolean {
        if (t["type"] != "multipolygon") return false
        val nat = t["natural"]
        return nat == "water" || nat == "wood" || t["landuse"] in LANDUSE || t["leisure"] == "park" || t["leisure"] == "garden"
    }

    private val REGIONAL = Regex("(?i)bölgesel|ekspres|yht|hızlı tren")

    fun route(t: Map<String, String>): Boolean {
        if (t["type"] != "route") return false
        val r = t["route"]
        val name = t["name"] ?: ""
        if (REGIONAL.containsMatchIn(name)) return false // intercity trains are not city transport
        return r in ROUTES || (r == "train" && (TRAIN_REF.containsMatchIn(t["ref"] ?: "") || name.contains("Marmaray") || name.contains("Başkentray")))
    }

    private val DRIVABLE = setOf(
        "motorway", "trunk", "primary", "secondary", "tertiary", "motorway_link", "trunk_link", "primary_link", "secondary_link",
        "tertiary_link", "unclassified", "residential", "living_street", "road", "service",
    )

    /** Streets a bus can use (for routing lines along the roads). */
    fun drivable(t: Map<String, String>) = t["highway"] in DRIVABLE && t["area"] != "yes" &&
        t["access"] != "no" && t["service"] !in setOf("parking_aisle", "driveway", "drive-through")

    fun busStop(t: Map<String, String>) = t["highway"] == "bus_stop" || (t["public_transport"] == "platform" && t["bus"] == "yes")

    fun busRoute(t: Map<String, String>) = t["type"] == "route" && t["route"] == "bus"

    fun poi(t: Map<String, String>): Boolean {
        val named = t["name"] != null
        return t["shop"] != null || t["amenity"] in AMENITY || t["tourism"] in TOURISM ||
            (named && (t["leisure"] in LEISURE || t["healthcare"] != null || t["place"] in PLACES || t["railway"] == "station"))
    }
}
