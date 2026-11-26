package app.localmusic

import app.plain.lang.tr
import org.json.JSONArray
import org.json.JSONObject

/** A playable song from MediaStore (pure data). */
class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val albumId: Long,
    val genre: String,
    val track: Int,
    val disc: Int,
    val year: Int,
    val durationMs: Long,
    val relPath: String,
    val name: String,
    val size: Long,
    val dateAdded: Long,
    /** Set for a file another app handed us (not in the library): content:// or file:// URI. */
    val externalUri: String? = null,
    /** A video from Plain Youtube (Movies/PlainDownload/); plays as audio unless the now-playing screen shows the picture. */
    val isVideo: Boolean = false,
    /** Video frame size from MediaStore (0 = unknown); only for laying out the picture. */
    val videoW: Int = 0,
    val videoH: Int = 0,
    /** MediaStore volume ("external_primary", an SD card...): trash/delete requests need the item's own volume. */
    val volume: String = "",
    /** DATE_MODIFIED (s): changes when a file is rewritten in place (e.g. Plain Youtube adding cover art). */
    val modified: Long = 0,
) {
    /** Artist named by the folders when the file has no artist tags ("Music/PlainDownload/Some Channel/" -> "Some Channel"). */
    val inferredArtist: String? =
        if (artist.isBlank() && albumArtist.isBlank() && externalUri == null) Naming.artistFromFolder(relPath) else null
    /** Artist as shown in rows, now playing and the notification: the tag, else the folder, else "Unknown Artist" (in the UI language). */
    val shownArtist: String get() = artist.ifBlank { albumArtist }.ifBlank { inferredArtist ?: tr(Naming.UNKNOWN_ARTIST, "Bilinmeyen sanatçı") }
    /** Artist used for the Artists tab and folders: album artist, else the lead track artist ("A, B feat. C" -> "A"), else the folder.
     *  A Plain Youtube file whose artist tag is its channel keeps the channel whole ("Rock, Paper" stays one artist). */
    val mainArtist: String = PlainYoutube.place(relPath)?.channel
        ?.takeIf { albumArtist.isBlank() && artist.isNotBlank() && Text.groupKey(artist) == Text.groupKey(it) }
        ?: Text.leadArtist(albumArtist.ifBlank { artist }).ifBlank { inferredArtist ?: Naming.UNKNOWN_ARTIST }
    /** Fully cached by Plain Youtube (Music|Movies/PlainDownload/Cached/): may disappear when it evicts the file. */
    val cached: Boolean = PlainYoutube.isCached(relPath)
    val albumName: String = album.ifBlank { Naming.SINGLES }
    val folder: String = relPath.trimEnd('/').ifEmpty { "/" }
    val path get() = relPath + name
    val haystack: String = Text.fold("$title$artist$album$albumArtist${inferredArtist ?: ""}")
    val sortTitle: String = Text.sortKey(title)
    val subtitle: String get() = listOf(shownArtist, album).filter { it.isNotBlank() }.joinToString(" · ")

    /** Merged-album key: same album name (ignoring "(Remastered)", years, case, accents) = one album across folders and artists.
     *  Generic names ("Singles", "Greatest Hits") stay per artist. */
    val artistKey: String = Text.groupKey(mainArtist)
    val albumKey: String = Text.albumKey(albumName).let { k -> if (Text.isGenericAlbum(k)) Text.groupKey(k) + "|" + artistKey else Text.groupKey(k) }
}

/** Which videos belong in the library: only Plain Youtube's folder tree, never camera or other videos. */
object Videos {
    /** Plain Youtube writes videos to Movies/PlainDownload/<Channel>/<Title>.mp4 (MediaStore RELATIVE_PATH). */
    const val DIR = "Movies/PlainDownload/"

    fun isLibraryVideo(relPath: String): Boolean = relPath.startsWith(DIR, ignoreCase = true)

    /** Display size of a frame: MediaStore's width/height are before rotation. */
    fun displaySize(w: Int, h: Int, orientation: Int): Pair<Int, Int> = if (orientation % 180 != 0) h to w else w to h
}

/** A list of songs under a name: artist, album, genre or folder. */
class Group(val key: String, val title: String, val songs: List<Song>, val artist: String = "", val albumCount: Int = 0) {
    val duration: Long = songs.sumOf { it.durationMs }
    val year: Int = songs.maxOfOrNull { it.year } ?: 0
    val count get() = songs.size
    val sub: String get() = Text.count(songs.size, "song")

    /** "99 songs · 4 albums · 5 h 12 min" */
    val stats: String get() = listOfNotNull(
        Text.count(songs.size, "song"),
        if (albumCount > 0) Text.count(albumCount, "album") else null,
        Text.longTime(duration),
    ).joinToString(" · ")
}

/** Categorised view of the library: artists, merged albums, genres, folders (pure). */
class LibraryIndex(all: List<Song>) {
    val songs: List<Song> = all.sortedWith(compareBy({ it.sortTitle }, { it.id }))
    val byId: Map<Long, Song> = all.associateBy { it.id }

    val albums: List<Group> = songs.groupBy { it.albumKey }.map { (k, v) ->
        val artists = v.groupingBy { it.mainArtist }.eachCount()
        val top = artists.maxBy { it.value }
        val artist = when {
            artists.size == 1 -> top.key
            top.value * 2 >= v.size -> top.key + tr(" & more", " ve diğerleri")
            else -> tr("Various artists", "Çeşitli sanatçılar")
        }
        Group(k, majority(v.map { it.albumName }), trackOrder(v), artist)
    }.sortedWith(compareBy({ Text.sortKey(it.title) }, { it.key }))

    private val albumBySong: Map<Long, Group> = HashMap<Long, Group>().apply { for (a in albums) for (s in a.songs) put(s.id, a) }

    val artists: List<Group> = songs.groupBy { it.artistKey }.map { (k, v) ->
        Group(k, majority(v.map { it.mainArtist }), albumOrder(v), albumCount = v.map { it.albumKey }.distinct().size)
    }.sortedBy { Text.sortKey(it.title) }

    val genres: List<Group> = songs.filter { it.genre.isNotBlank() }.groupBy { Text.fold(it.genre) }
        .map { (k, v) -> Group(k, majority(v.map { it.genre }), albumOrder(v), albumCount = v.map { it.albumKey }.distinct().size) }
        .sortedBy { Text.sortKey(it.title) }

    val folders: List<Group> = songs.groupBy { it.folder.lowercase() }
        .map { (k, v) -> Group(k, majority(v.map { it.folder }), trackOrder(v)) }
        .sortedBy { Text.sortKey(it.title) }

    /** Albums an artist appears on, newest first. */
    fun albumsOf(artist: Group): List<Group> =
        artist.songs.mapNotNull { albumBySong[it.id] }.distinctBy { it.key }.sortedWith(compareBy({ -it.year }, { Text.sortKey(it.title) }))

    fun search(query: String): List<Song> {
        val t = Text.tokens(query)
        if (t.isEmpty()) return emptyList()
        return songs.filter { Text.matches(it.haystack, t) }
    }

    fun searchGroups(groups: List<Group>, query: String): List<Group> {
        val t = Text.tokens(query)
        if (t.isEmpty()) return emptyList()
        return groups.filter { Text.matches(Text.fold(it.title + " " + it.artist), t) }
    }

    // --- derived views, computed once per library load (the index is rebuilt when MediaStore's generation changes) ---

    val byPath: Map<String, Song> by lazy { songs.associateBy { it.path.lowercase() } }
    /** Plain Youtube's videos, newest first. */
    val videos: List<Song> by lazy { songs.filter { it.isVideo }.sortedByDescending { it.dateAdded } }
    /** Videos per channel, the channel with the newest video first. */
    val videoChannels: List<Group> by lazy {
        videos.groupBy { it.artistKey }.map { (k, v) -> Group(k, majority(v.map { it.mainArtist }), v) }
    }
    /** Everything from Plain Youtube (saved and cached, audio and video), newest first. */
    val plainYoutube: List<Song> by lazy { songs.filter { PlainYoutube.inTree(it.relPath) }.sortedByDescending { it.dateAdded } }
    val newPieces: List<NewPieces.Item> by lazy { NewPieces.build(songs, System.currentTimeMillis() / 1000) }
    val folderTree: List<FolderNode> by lazy { FolderTree.build(songs) }
    val packedGenres: Packing.Packed by lazy { Packing.genres(genres) }
    private val packedArtists = HashMap<Int, Packing.Packed>()
    fun packedArtists(max: Int): Packing.Packed = synchronized(packedArtists) { packedArtists.getOrPut(max) { Packing.artists(artists, max) } }
    private val packedAlbums = HashMap<Int, Packing.Packed>()
    /** Album grid packing for one sort order of [albums] (cached per sort). */
    fun packedAlbums(sort: Int, sorted: () -> List<Group>): Packing.Packed = synchronized(packedAlbums) { packedAlbums.getOrPut(sort) { Packing.albums(sorted()) } }

    fun albumOf(s: Song): Group? = albumBySong[s.id]
    fun artistOf(s: Song): Group? = artists.firstOrNull { it.key == s.artistKey }

    companion object {
        fun trackOrder(list: List<Song>) = list.sortedWith(compareBy({ it.disc }, { if (it.track == 0) Int.MAX_VALUE else it.track }, { it.sortTitle }))
        fun albumOrder(list: List<Song>) = list.sortedWith(compareBy({ Text.sortKey(it.albumName) }, { it.disc }, { if (it.track == 0) Int.MAX_VALUE else it.track }, { it.sortTitle }))
        fun majority(names: List<String>): String = names.groupingBy { it }.eachCount().maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { -names.indexOf(it.key) }).key
    }
}

/** A user playlist. Entries keep the MediaStore id and the file path, so they survive a rescan. */
class Playlist(var id: Long, var name: String, val entries: MutableList<Entry> = ArrayList()) {
    class Entry(var songId: Long, var path: String)

    /** Resolve entries to songs, repairing ids from paths when MediaStore renumbered files. */
    fun resolve(byId: Map<Long, Song>, byPath: Map<String, Song>): List<Song> = entries.mapNotNull { e ->
        val s = byId[e.songId]
        when {
            s != null && s.path.equals(e.path, ignoreCase = true) -> s
            byPath[e.path.lowercase()] != null -> byPath.getValue(e.path.lowercase()).also { e.songId = it.id } // ids renumbered
            s != null -> s.also { e.path = it.path } // same file, moved (e.g. by the organiser)
            else -> null
        }
    }
}

object PlaylistCodec {
    fun encode(list: List<Playlist>): String = JSONArray().apply {
        for (p in list) put(JSONObject().apply {
            put("id", p.id)
            put("name", p.name)
            put("songs", JSONArray().apply { for (e in p.entries) put(JSONObject().put("id", e.songId).put("path", e.path)) })
        })
    }.toString(1)

    fun decode(json: String): MutableList<Playlist> {
        val out = ArrayList<Playlist>()
        val arr = runCatching { JSONArray(json) }.getOrNull() ?: return out
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val songs = o.optJSONArray("songs") ?: JSONArray()
            val entries = ArrayList<Playlist.Entry>()
            for (j in 0 until songs.length()) {
                val s = songs.optJSONObject(j) ?: continue
                entries += Playlist.Entry(s.optLong("id", -1), s.optString("path", ""))
            }
            out += Playlist(o.optLong("id", i.toLong()), o.optString("name", "Playlist"), entries)
        }
        return out
    }
}
