package app.localmusic

import app.plain.lang.tr
import java.util.Locale

/**
 * Pure grouping for the library screens (unit-tested in LogicTest.kt): packing small groups, the collapsed folder
 * tree, "new pieces" and the play history. Everything works on the in-memory LibraryIndex; nothing touches files on disk.
 */
object Packing {
    const val ONE_OFFS = "Singles & one-offs"
    const val OTHER_GENRE = "Other"
    /** Keys of packed groups start with \u0000 so they never collide with a real album/artist/genre key. */
    const val ONE_OFFS_KEY = "\u0000one-offs"
    const val SINGLES_KEY = "\u0000singles"
    const val OTHER_KEY = "\u0000other"

    fun isPacked(g: Group) = g.key.startsWith('\u0000')

    // Shown titles of the packed groups (the English constants stay for keys and tests). LibraryIndex builds these once
    // per load; Library.relabel() rebuilds the index after a language change.
    val oneOffsTitle get() = tr(ONE_OFFS, "Az şarkılı sanatçılar")
    val singlesTitle get() = tr(Naming.SINGLES, "Tekliler")
    val otherGenreTitle get() = tr(OTHER_GENRE, "Diğer")

    /** The groups that stay as they are, plus the one group the small ones were packed into (null = nothing packed). */
    class Packed(val groups: List<Group>, val packed: Group?)

    private val BY_ARTIST = compareBy<Song>({ it.sortArtist }, { it.sortTitle }, { it.id })
    private val UNKNOWN_KEY = Text.groupKey(Naming.UNKNOWN_ARTIST)

    /** Artists with at most [max] songs, and Unknown Artist, go into one "Singles & one-offs" entry (songs by artist). */
    fun artists(artists: List<Group>, max: Int): Packed {
        val (small, big) = artists.partition { it.count <= max || it.key == UNKNOWN_KEY }
        if (small.isEmpty()) return Packed(artists, null)
        val songs = small.flatMap { it.songs }.sortedWith(BY_ARTIST)
        return Packed(big, Group(ONE_OFFS_KEY, oneOffsTitle, songs, artist = Text.count(small.size, "artist")))
    }

    /** Album grid: single-track albums (of any artists) become one "Singles" tile. A lone one stays as it is. */
    fun albums(albums: List<Group>): Packed {
        val (one, many) = albums.partition { it.count == 1 }
        if (one.size < 2) return Packed(albums, null)
        val songs = one.flatMap { it.songs }.sortedWith(BY_ARTIST)
        val artists = songs.map { it.artistKey }.distinct().size
        return Packed(many, Group(SINGLES_KEY, singlesTitle, songs, artist = if (artists == 1) songs[0].mainArtist else Text.count(artists, "artist")))
    }

    /** Genres with fewer than [min] songs go into "Other" (merged with a real "Other" genre if there is one). */
    fun genres(genres: List<Group>, min: Int = 3): Packed {
        val (small, big) = genres.partition { it.count < min || Text.fold(it.title) == "other" }
        if (small.isEmpty()) return Packed(genres, null)
        val songs = small.flatMap { it.songs }.sortedWith(BY_ARTIST)
        return Packed(big, Group(OTHER_KEY, otherGenreTitle, songs, albumCount = songs.map { it.albumKey }.distinct().size))
    }
}

/** A folder row in the collapsed tree. [songs] are the files directly in it; [all] includes every subfolder. */
class FolderNode(val title: String, val key: String, val children: List<FolderNode>, val songs: List<Song>, val loose: Boolean = false) {
    val all: List<Song> by lazy { songs + children.flatMap { it.all } }
    val count: Int get() = all.size
    val duration: Long by lazy { all.sumOf { it.durationMs } }
    val stats: String get() = listOfNotNull(
        children.count { !it.loose }.takeIf { it > 0 }?.let { Text.count(it, "folder") },
        Text.count(count, "song"), Text.longTime(duration),
    ).joinToString(" · ")

    fun find(k: String): FolderNode? = if (key == k) this else children.firstNotNullOfOrNull { it.find(k) }
}

/**
 * The Folders view as a collapsed tree:
 * - top level by root: Music, Download, Plain Youtube (saved), Plain Youtube · cached, then any other root folder;
 * - a folder whose only content is one subfolder is merged into one row ("Music › Albums › Tarkan");
 * - two or more subfolders holding a single file each are packed into one "Loose files" entry under their parent.
 */
object FolderTree {
    const val LOOSE = "Loose files"
    const val PLAIN = "Plain Youtube"
    const val PLAIN_CACHED = "Plain Youtube · cached"
    const val PHONE = "Phone storage"
    val looseTitle get() = tr(LOOSE, "Tek dosyalar")

    /** Shown name of a root (keys stay English: they come from the English label). */
    private fun rootTitle(name: String): String = when (name) {
        PLAIN_CACHED -> tr(PLAIN_CACHED, "Plain Youtube · önbellek")
        PHONE -> tr(PHONE, "Telefon belleği")
        else -> name
    }
    private val ROOT_ORDER = listOf("music", "download", PLAIN.lowercase(Locale.ROOT), PLAIN_CACHED.lowercase(Locale.ROOT))

    /** Root label and the folder names below it. Plain Youtube's music and video trees share one root per channel. */
    fun split(relPath: String): Pair<String, List<String>> {
        val parts = relPath.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (PlainYoutube.inTree(relPath)) {
            val cached = PlainYoutube.isCached(relPath)
            return (if (cached) PLAIN_CACHED else PLAIN) to parts.drop(if (cached) 3 else 2)
        }
        return (parts.firstOrNull() ?: PHONE) to parts.drop(1)
    }

    private class B(val name: String) {
        val kids = LinkedHashMap<String, B>()
        val songs = ArrayList<Song>()
    }

    fun build(songs: List<Song>): List<FolderNode> {
        val roots = LinkedHashMap<String, B>()
        for (s in songs) {
            val (root, rest) = split(s.relPath)
            var b = roots.getOrPut(root.lowercase(Locale.ROOT)) { B(root) }
            for (p in rest) b = b.kids.getOrPut(p.lowercase(Locale.ROOT)) { B(p) }
            b.songs += s
        }
        return roots.values.map { finish(it, rootTitle(it.name), it.name.lowercase(Locale.ROOT)) }
            .sortedWith(compareBy({ ROOT_ORDER.indexOf(it.key.substringBefore('/')).let { i -> if (i < 0) ROOT_ORDER.size else i } }, { Text.sortKey(it.title) }))
    }

    private fun finish(b: B, title: String, key: String): FolderNode {
        val kids = b.kids.values.map { finish(it, it.name, key + "/" + it.name.lowercase(Locale.ROOT)) }
        val single = kids.filter { it.children.isEmpty() && it.songs.size == 1 }
        val children = ArrayList(if (single.size >= 2) kids - single.toSet() else kids)
        children.sortBy { Text.sortKey(it.title) }
        if (single.size >= 2) children += FolderNode(looseTitle, "$key/\u0000loose", emptyList(), single.flatMap { it.songs }.sortedBy { it.sortTitle }, loose = true)
        var node = FolderNode(title, key, children, b.songs.sortedWith(compareBy({ it.sortTitle }, { it.id })))
        while (node.songs.isEmpty() && node.children.size == 1 && !node.children[0].loose) {
            val c = node.children[0]
            node = FolderNode(node.title + " › " + c.title, c.key, c.children, c.songs)
        }
        return node
    }
}

/** "New pieces": what was added lately, with 3+ songs of one album or channel that arrived together as one card. */
object NewPieces {
    const val DAYS = 30
    const val TOGETHER_SEC = 12 * 3600L
    const val MIN_BUNDLE = 3

    /** One new song, or a bundle of new songs from one album/channel. [time] = newest DATE_ADDED (seconds). */
    class Item(val songs: List<Song>, val title: String, val sub: String, val time: Long) {
        val isBundle get() = songs.size > 1
    }

    private fun key(s: Song): String {
        val album = Text.albumKey(s.album)
        return if (s.album.isNotBlank() && !Text.isGenericAlbum(album)) "a|" + Text.groupKey(album) else "r|" + s.artistKey
    }

    fun build(songs: List<Song>, nowSec: Long, days: Int = DAYS): List<Item> {
        val from = nowSec - days * 86_400L
        val recent = songs.filter { it.dateAdded in from..nowSec + 86_400 && it.externalUri == null }
        val out = ArrayList<Item>()
        for ((_, list) in recent.groupBy { key(it) }) {
            val sorted = list.sortedByDescending { it.dateAdded }
            var cluster = ArrayList<Song>()
            fun flush() {
                if (cluster.size >= MIN_BUNDLE) {
                    val ordered = LibraryIndex.trackOrder(cluster)
                    val album = cluster.first().album.takeIf { key(cluster.first()).startsWith("a|") }
                    val artist = LibraryIndex.majority(cluster.map { it.mainArtist })
                    out += Item(ordered, album?.let { LibraryIndex.majority(cluster.map { s -> s.album }) } ?: artist,
                        tr("${cluster.size} new", "${cluster.size} yeni") + if (album != null) " · $artist" else "", cluster.maxOf { it.dateAdded })
                } else cluster.forEach { out += Item(listOf(it), it.title, it.shownArtist, it.dateAdded) }
                cluster = ArrayList()
            }
            for (s in sorted) {
                if (cluster.isNotEmpty() && cluster.last().dateAdded - s.dateAdded > TOGETHER_SEC) flush()
                cluster += s
            }
            flush()
        }
        return out.sortedWith(compareByDescending<Item> { it.time }.thenBy { Text.sortKey(it.title) })
    }
}

/**
 * Play history: one entry per song (id + path, last played, play count), at most [max] entries (oldest dropped).
 * A play counts once the song has played for 30 s or half its length, whichever comes first ([counts]).
 */
class PlayHistory(private val max: Int = 2000) {
    class Entry(var id: Long, var path: String, var last: Long, var count: Int)

    val entries = ArrayList<Entry>()

    fun record(id: Long, path: String, timeMs: Long) {
        val e = entries.firstOrNull { it.id == id } ?: entries.firstOrNull { it.path.equals(path, ignoreCase = true) }
        if (e != null) { e.id = id; e.path = path; e.last = timeMs; e.count++ }
        else entries += Entry(id, path, timeMs, 1)
        if (entries.size > max) {
            entries.sortByDescending { it.last }
            while (entries.size > max) entries.removeAt(entries.size - 1)
        }
    }

    fun clear() = entries.clear()

    /** Most recent first, resolved to library songs (ids repaired from paths, missing files skipped). */
    fun recent(byId: Map<Long, Song>, byPath: Map<String, Song>): List<Song> =
        entries.sortedByDescending { it.last }.mapNotNull { resolve(it, byId, byPath) }

    /** Most plays first (at least [minCount]), ties by most recent. */
    fun most(byId: Map<Long, Song>, byPath: Map<String, Song>, minCount: Int = 2): List<Pair<Song, Int>> =
        entries.filter { it.count >= minCount }.sortedWith(compareByDescending<Entry> { it.count }.thenByDescending { it.last })
            .mapNotNull { e -> resolve(e, byId, byPath)?.let { it to e.count } }

    private fun resolve(e: Entry, byId: Map<Long, Song>, byPath: Map<String, Song>): Song? {
        val s = byId[e.id]
        return if (s != null && s.path.equals(e.path, ignoreCase = true)) s else byPath[LibraryIndex.pathKey(e.path)] ?: s
    }

    fun encode(): String = entries.joinToString("\n") { "${it.id}\t${it.last}\t${it.count}\t${it.path}" }

    companion object {
        const val MIN_MS = 30_000L

        /** Counted after 30 s or half the song, whichever is first. */
        fun counts(listenedMs: Long, durationMs: Long): Boolean =
            listenedMs >= MIN_MS || (durationMs > 0 && listenedMs * 2 >= durationMs)

        fun decode(text: String, max: Int = 2000): PlayHistory = PlayHistory(max).apply {
            for (line in text.lineSequence()) {
                val f = line.split('\t', limit = 4)
                if (f.size < 4) continue
                entries += Entry(f[0].toLongOrNull() ?: continue, f[3], f[1].toLongOrNull() ?: continue, f[2].toIntOrNull() ?: 1)
            }
        }
    }
}

/**
 * Which songs to try for a cover (low resolution is fine). Loading stays lazy: the view tries these off the main thread,
 * only when its row is on screen, and the result lands in Art's small LRU.
 */
object ArtPick {
    const val TRIES = 8
    const val MOSAIC_TRIES = 12

    /** Album: its songs in track order, the first one with art wins. */
    fun album(songs: List<Song>): List<Song> = songs.take(TRIES)

    /** Artist: songs of its most-played album first (plays from the history; ties: the biggest album), then one per other album. */
    fun artist(songs: List<Song>, plays: Map<Long, Int>): List<Song> {
        val albums = songs.groupBy { it.albumKey }.values
            .sortedWith(compareByDescending<List<Song>> { a -> a.sumOf { plays[it.id] ?: 0 } }.thenByDescending { it.size })
        val first = albums.firstOrNull()?.take(3).orEmpty()
        return (first + albums.drop(1).map { it.first() }).take(TRIES)
    }

    /** Mosaic (packs, bundles, folders): one song per distinct album, so the 2×2 shows different covers. */
    fun mosaic(songs: List<Song>): List<Song> = songs.distinctBy { it.albumKey }.take(MOSAIC_TRIES)

    /** Ids whose file changed in place since the last load (size or DATE_MODIFIED): their cached art is stale. */
    fun changed(old: Collection<Song>, new: Collection<Song>): Set<Long> {
        val before = old.associateBy { it.id }
        return new.filterTo(HashSet()) { n -> before[n.id]?.let { o -> o.size != n.size || o.modified != n.modified } == true }.mapTo(HashSet()) { it.id }
    }
}
