package app.localmusic

import app.plain.lang.tr
import java.util.Locale

/** One audio file found on the phone, with resolved tags (pure data, no Android types). */
data class FileInfo(
    val id: Long,
    val relPath: String,      // "Download/Echo/" (MediaStore RELATIVE_PATH, trailing slash)
    val name: String,         // display name incl. extension
    val size: Long,
    val durationMs: Long,
    val mime: String,
    val bitrate: Int,         // bits/s, 0 if unknown
    val tags: Tags,
    val skipReason: String?,  // null = music; otherwise why it is left alone by default
    val hash: String? = null,
) {
    val path get() = relPath + name
    val ext get() = Naming.extension(name)
    val lossless get() = isLossless(mime, ext)

    /** Bitrate, or an estimate from size and duration when the tag is missing. */
    val effectiveBitrate: Int
        get() = if (bitrate > 0) bitrate else if (durationMs > 0) (size * 8000 / durationMs).toInt() else 0

    companion object {
        fun isLossless(mime: String, ext: String): Boolean {
            val m = mime.lowercase(Locale.ROOT)
            val e = ext.lowercase(Locale.ROOT)
            return "flac" in m || "wav" in m || "alac" in m || "aiff" in m || e in setOf("flac", "wav", "aiff", "aif", "ape", "wv")
        }
    }
}

/** Which files count as music. Ringtones, notifications, voice recordings etc. are left alone. */
object Classify {
    private val NOT_MUSIC_DIR = Regex(
        "^(recordings?|call ?rec.*|.*recorder.*|voice ?(notes?|recorder|memos?)?|whatsapp.*|telegram.*|signal|notifications|ringtones|alarms|podcasts|audiobooks|android|backups?|.* backup)$",
        RegexOption.IGNORE_CASE,
    )
    private val NOT_MUSIC_NAME = Regex("^(audio from |ptt-|aud-\\d|voice[ _-]|recording[ _(-]|record_|call_)", RegexOption.IGNORE_CASE)

    fun skipReason(
        relPath: String,
        name: String,
        durationMs: Long,
        ringtone: Boolean = false,
        notification: Boolean = false,
        alarm: Boolean = false,
        recording: Boolean = false,
        podcast: Boolean = false,
        audiobook: Boolean = false,
        hasTrackNumber: Boolean = false,
    ): String? {
        if (notification) return tr("notification sound", "bildirim sesi")
        if (ringtone) return tr("ringtone", "zil sesi")
        if (alarm) return tr("alarm sound", "alarm sesi")
        if (recording) return tr("voice recording", "ses kaydı")
        if (podcast) return tr("podcast", "podcast")
        if (audiobook) return tr("audiobook", "sesli kitap")
        val dirs = relPath.trim('/').split('/').filter { it.isNotEmpty() }
        dirs.firstOrNull { NOT_MUSIC_DIR.matches(it) }?.let { return tr("in “$it” folder", "klasör: “$it”") }
        if (NOT_MUSIC_NAME.containsMatchIn(name)) return tr("looks like a voice recording", "ses kaydına benziyor")
        if (durationMs in 1 until 20_000 && !hasTrackNumber) return tr("shorter than 20 s", "20 sn'den kısa")
        return null
    }
}

/** Duplicate detection: identical content (hash) or the same song in a different quality. */
object Dedupe {
    const val DURATION_TOLERANCE_MS = 2000L

    class Group(val keep: FileInfo, val remove: List<FileInfo>, val identical: Boolean)

    /** Only files whose size occurs more than once can be byte-identical: hash just those. */
    fun needsHash(files: List<FileInfo>): List<FileInfo> =
        files.groupBy { it.size }.values.filter { it.size > 1 }.flatten()

    /** Best file first: lossless, higher bitrate, tagged, already organised, bigger, shorter path. */
    val BEST_FIRST: Comparator<FileInfo> = compareByDescending<FileInfo> { it.lossless }
        .thenByDescending { if (it.lossless) 0 else (it.effectiveBitrate + 16_000) / 32_000 } // ~32 kbps buckets: VBR noise isn't "better"
        .thenByDescending { if (it.ext.equals("flac", true)) 1 else 0 }          // flac over wav (tags, size)
        .thenByDescending { !it.tags.fromFileName }
        .thenByDescending { it.relPath.startsWith(Naming.ROOT, ignoreCase = true) }
        .thenByDescending { it.size }
        .thenBy { it.path.length }
        .thenBy { it.id }

    private fun tagKey(f: FileInfo): String? {
        val t = Text.titleKey(f.tags.title)
        if (t.length < 2) return null
        return t + "|" + Text.artistKey(f.tags.artist.ifEmpty { f.tags.albumArtist })
    }

    private fun albumsCompatible(a: FileInfo, b: FileInfo): Boolean {
        val x = Text.titleKey(a.tags.album)
        val y = Text.titleKey(b.tags.album)
        return x.isEmpty() || y.isEmpty() || x == y
    }

    fun find(files: List<FileInfo>): List<Group> {
        val parent = IntArray(files.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }
            return r
        }
        fun union(a: Int, b: Int) { parent[root(a)] = root(b) }

        // 1) byte-identical
        files.indices.filter { files[it].hash != null }.groupBy { files[it].hash + ":" + files[it].size }
            .values.forEach { g -> for (i in 1 until g.size) union(g[0], g[i]) }

        // 2) same title+artist, compatible album, duration within tolerance
        files.indices.mapNotNull { i -> tagKey(files[i])?.let { it to i } }.groupBy({ it.first }, { it.second })
            .values.filter { it.size > 1 }.forEach { g ->
                for (a in g.indices) for (b in a + 1 until g.size) {
                    val x = files[g[a]]
                    val y = files[g[b]]
                    if (x.durationMs > 0 && y.durationMs > 0 &&
                        kotlin.math.abs(x.durationMs - y.durationMs) <= DURATION_TOLERANCE_MS &&
                        albumsCompatible(x, y)
                    ) union(g[a], g[b])
                }
            }

        return files.indices.groupBy { root(it) }.values.filter { it.size > 1 }.map { idx ->
            val members = idx.map { files[it] }.sortedWith(BEST_FIRST)
            val keep = members[0]
            val rest = members.drop(1)
            Group(keep, rest, rest.all { it.hash != null && it.hash == keep.hash && it.size == keep.size })
        }.sortedBy { Text.sortKey(it.keep.tags.title) }
    }
}

/** The organiser's plan. Nothing happens to the files until the user confirms it in the app. */
class Plan(
    val moves: List<Move>,
    val removals: List<Removal>,
    val inPlace: List<FileInfo>,
    val skipped: List<FileInfo>,   // not music, left alone
    val keptByUser: List<FileInfo>, // music the user chose not to move
) {
    class Move(val file: FileInfo, val toDir: String, val toName: String) {
        val toPath get() = toDir + toName
    }
    class Removal(val file: FileInfo, val keep: FileInfo, val identical: Boolean)

    val bytesFreed get() = removals.sumOf { it.file.size }

    /** "Artist/Album" folders of the organised library after this plan. */
    val albumFolders: List<String> get() =
        (moves.map { it.toDir } + inPlace.map { it.relPath }).map { it.removePrefix(Naming.ROOT).trimEnd('/') }.distinctBy { it.lowercase(Locale.ROOT) }
    val musicCount get() = moves.size + inPlace.size + keptByUser.size

    companion object {
        /**
         * @param keepDuplicate ids the user chose to keep although they are duplicates
         * @param dontMove ids the user chose to leave where they are
         * @param include ids of "not music" files the user wants organised anyway
         */
        fun build(
            files: List<FileInfo>,
            keepDuplicate: Set<Long> = emptySet(),
            dontMove: Set<Long> = emptySet(),
            include: Set<Long> = emptySet(),
        ): Plan {
            // Plain Youtube's cache is never touched (not even if ticked); its saved music only when ticked.
            fun organise(f: FileInfo) = !PlainYoutube.isCached(f.relPath) &&
                ((f.skipReason == null && PlainYoutube.organiserSkip(f.relPath) == null) || f.id in include)
            val music = files.filter { organise(it) }
            val skipped = files.filter { !organise(it) }

            val removals = ArrayList<Removal>()
            val removedIds = HashSet<Long>()
            for (g in Dedupe.find(music)) for (r in g.remove) {
                if (r.id in keepDuplicate) continue
                removals += Removal(r, g.keep, g.identical && r.hash == g.keep.hash)
                removedIds += r.id
            }
            val survivors = music.filter { it.id !in removedIds }

            // Merged albums: every track of one tagged album goes into one folder under the album's main artist
            // ("Enderal OST" credited to five composers -> Marvin Kopp/Enderal OST; "Romantizma" + "Romantizma 2015 (Re-Mastered)").
            val mergedAlbum = HashMap<String, Pair<String, String>>() // album key -> (artist folder, album folder)
            survivors.filter { it.tags.album.isNotBlank() }.groupBy { Text.albumKey(it.tags.album) }
                .filterKeys { !Text.isGenericAlbum(it) }
                .forEach { (k, fs) ->
                    mergedAlbum[k] = LibraryIndex.majority(fs.map { Naming.folderArtist(it.tags) }) to LibraryIndex.majority(fs.map { Naming.folderAlbum(it.tags) })
                }
            fun folders(f: FileInfo): Pair<String, String> =
                f.tags.album.takeIf { it.isNotBlank() }?.let { mergedAlbum[Text.albumKey(it)] } ?: (Naming.folderArtist(f.tags) to Naming.folderAlbum(f.tags))

            // One spelling per artist/album folder.
            val artistName = Naming.canonicalNames(survivors.map { folders(it).first })
            val albumName = Naming.canonicalNames(survivors.map { folders(it).let { (a, b) -> "$a/$b" } })

            fun targetDir(f: FileInfo): String {
                val (a, b) = folders(f)
                val canonA = artistName[a.lowercase(Locale.ROOT)] ?: a
                val al = albumName["$a/$b".lowercase(Locale.ROOT)]?.substringAfter('/') ?: b
                return Naming.ROOT + canonA + "/" + al + "/"
            }

            // Names already taken by files that stay put.
            val taken = HashSet<String>()
            val stay = survivors.filter { it.id in dontMove }
            stay.forEach { taken += it.path.lowercase(Locale.ROOT) }

            val inPlace = ArrayList<FileInfo>()
            val wanted = ArrayList<Pair<FileInfo, Pair<String, String>>>()
            for (f in survivors) {
                if (f.id in dontMove) continue
                val dir = targetDir(f)
                val name = Naming.fileName(f.tags, f.ext)
                if (dir.equals(f.relPath, ignoreCase = true) && Naming.isNumberedVariant(f.name, name) && taken.add(f.path.lowercase(Locale.ROOT))) inPlace += f
                else wanted += f to (dir to name)
            }
            // Best files claim names first, so "Song (2)" is never the better copy.
            val moves = wanted.sortedWith(compareBy<Pair<FileInfo, Pair<String, String>>> { it.second.first.lowercase(Locale.ROOT) + it.second.second.lowercase(Locale.ROOT) }
                .thenComparator { a, b -> Dedupe.BEST_FIRST.compare(a.first, b.first) })
                .map { (f, t) -> Move(f, t.first, Naming.unique(t.first, t.second, taken)) }
                .sortedBy { it.toPath.lowercase(Locale.ROOT) }
            return Plan(moves, removals.sortedBy { it.file.path.lowercase(Locale.ROOT) }, inPlace, skipped, stay)
        }
    }
}
