package app.localmusic

import app.plain.lang.tr

/**
 * Sort orders for the library lists (pure, unit-tested in LogicTest.kt). Each list has its own choice, saved in prefs
 * under [Kind.pref] as the option's [Option.id] (ids are stored keys: never translate them).
 * Unknown years and dates always go last, whichever direction is picked.
 */
object Sorting {
    class Option(val id: String, private val en: String, private val trText: String) {
        val label: String get() = tr(en, trText)
    }

    enum class Kind(val pref: String) { SONGS("sort.songs"), ARTISTS("sort.artists"), ALBUMS("sort.albums"), GENRES("sort.genres"), PLAYLISTS("sort.playlists"), PLAYLIST("sort.playlist.") }

    private val AZ = Option("az", "A–Z", "A–Z")
    private val ZA = Option("za", "Z–A", "Z–A")
    private val NEW = Option("new", "Newest added", "En yeni eklenen")
    private val OLD = Option("old", "Oldest added", "En eski eklenen")
    private val YEAR_NEW = Option("year", "Year: newest", "Yıl: en yeni")
    private val YEAR_OLD = Option("year_old", "Year: oldest", "Yıl: en eski")
    private val ARTIST = Option("artist", "Artist", "Sanatçı")
    private val ALBUM = Option("album", "Album", "Albüm")
    private val MOST = Option("most", "Most songs", "En çok şarkı")
    private val LONG = Option("long", "Longest", "En uzun")
    private val PL_OLD = Option("old", "Oldest created", "En eski oluşturulan")
    private val PL_NEW = Option("new", "Newest created", "En yeni oluşturulan")
    private val PL_ORDER = Option("custom", "Playlist order", "Liste sırası")
    private val PL_LAST = Option("last", "Last added first", "Son eklenen önce")

    fun options(kind: Kind): List<Option> = when (kind) {
        Kind.SONGS -> listOf(AZ, ZA, NEW, OLD, ARTIST, ALBUM, YEAR_NEW, YEAR_OLD, LONG)
        Kind.ARTISTS -> listOf(MOST, AZ, ZA, NEW, OLD)
        Kind.ALBUMS -> listOf(AZ, ZA, ARTIST, NEW, OLD, YEAR_NEW, YEAR_OLD, MOST)
        Kind.GENRES -> listOf(AZ, ZA, MOST, NEW)
        Kind.PLAYLISTS -> listOf(PL_OLD, PL_NEW, AZ, ZA, MOST)
        Kind.PLAYLIST -> listOf(PL_ORDER, PL_LAST, AZ, ZA, ARTIST, ALBUM, NEW, OLD, YEAR_NEW, YEAR_OLD)
    }

    /** The saved choice for [kind], falling back to the first option (and to the pre-3.5 int prefs for artists/albums). */
    fun option(kind: Kind, saved: String?, legacy: Int = -1): Option {
        val list = options(kind)
        saved?.let { id -> list.firstOrNull { it.id == id }?.let { return it } }
        return when {
            kind == Kind.ARTISTS && legacy == 1 -> AZ
            kind == Kind.ALBUMS && legacy == 1 -> ARTIST
            kind == Kind.ALBUMS && legacy == 2 -> MOST
            else -> list[0]
        }
    }

    // Unknown (0) years/dates sort after every known one in both directions.
    private fun newest(v: Long) = if (v <= 0) Long.MAX_VALUE else -v
    private fun oldest(v: Long) = if (v <= 0) Long.MAX_VALUE else v

    private val TITLE = compareBy<Song>({ it.sortTitle }, { it.id })
    private val BY_ALBUM = compareBy<Song>({ Text.sortKey(it.albumName) }, { it.disc }, { if (it.track == 0) Int.MAX_VALUE else it.track }, { it.sortTitle }, { it.id })

    fun songs(list: List<Song>, id: String): List<Song> = when (id) {
        "za" -> list.sortedWith(TITLE.reversed())
        "new" -> list.sortedWith(compareBy<Song> { newest(it.dateAdded) }.then(TITLE))
        "old" -> list.sortedWith(compareBy<Song> { oldest(it.dateAdded) }.then(TITLE))
        "artist" -> list.sortedWith(compareBy<Song> { Text.sortKey(it.mainArtist) }.then(BY_ALBUM))
        "album" -> list.sortedWith(BY_ALBUM)
        "year" -> list.sortedWith(compareBy<Song> { newest(it.year.toLong()) }.then(BY_ALBUM))
        "year_old" -> list.sortedWith(compareBy<Song> { oldest(it.year.toLong()) }.then(BY_ALBUM))
        "long" -> list.sortedWith(compareByDescending<Song> { it.durationMs }.then(TITLE))
        "last" -> list.asReversed().toList()
        "az" -> list.sortedWith(TITLE)
        else -> list // "custom": as given (playlist order)
    }

    private val G_TITLE = compareBy<Group>({ Text.sortKey(it.title) }, { it.key })

    /** Artists, albums and genres. */
    fun groups(list: List<Group>, id: String): List<Group> = when (id) {
        "za" -> list.sortedWith(G_TITLE.reversed())
        "new" -> list.sortedWith(compareBy<Group> { newest(it.added) }.then(G_TITLE))
        "old" -> list.sortedWith(compareBy<Group> { oldest(it.firstAdded) }.then(G_TITLE))
        "artist" -> list.sortedWith(compareBy<Group>({ Text.sortKey(it.artist) }, { newest(it.year.toLong()) }).then(G_TITLE))
        "year" -> list.sortedWith(compareBy<Group> { newest(it.year.toLong()) }.then(G_TITLE))
        "year_old" -> list.sortedWith(compareBy<Group> { oldest(it.firstYear.toLong()) }.then(G_TITLE))
        "most" -> list.sortedWith(compareBy<Group> { -it.count }.then(G_TITLE))
        else -> list.sortedWith(G_TITLE)
    }

    /** Playlists with their resolved songs. Ids grow with each new playlist, so they give the creation order. */
    fun <T> playlists(list: List<T>, id: String, name: (T) -> String, pid: (T) -> Long, size: (T) -> Int): List<T> {
        val byName = compareBy<T>({ Text.sortKey(name(it)) }, { pid(it) })
        return when (id) {
            "az" -> list.sortedWith(byName)
            "za" -> list.sortedWith(byName.reversed())
            "new" -> list.sortedByDescending { pid(it) }
            "most" -> list.sortedWith(compareBy<T> { -size(it) }.then(byName))
            else -> list.sortedBy { pid(it) } // "old": creation order
        }
    }
}
