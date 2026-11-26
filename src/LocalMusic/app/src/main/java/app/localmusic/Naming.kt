package app.localmusic

import app.plain.lang.tr
import java.util.Locale

/** Tags as read from the file (any may be missing). */
data class RawTags(
    val title: String? = null,
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val track: String? = null,
    val disc: String? = null,
    val genre: String? = null,
)

/** Tags after fallbacks: everything usable for naming. */
data class Tags(
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val track: Int,
    val disc: Int,
    val genre: String,
    val fromFileName: Boolean,
    /** Name of the folder the file came from; used as the album folder when there is no album tag. */
    val sourceFolder: String = "",
    /** Artist taken from the folders when the file has no artist tags (see [Naming.folderArtistOf]); never used for dedupe. */
    val inferredArtist: String = "",
)

/**
 * Folder contract of the sibling app Plain Youtube (package app.plain.download; files are untagged, title = file name):
 *   Music/PlainDownload/[Cached/]<Channel>/[<Album>/]<Title>.m4a   (saved, or fully cached when under Cached/)
 *   Movies/PlainDownload/[Cached/]<Channel>/<Title>.mp4
 * In these trees the folders say exactly what they are: first folder = artist (channel), second = album.
 */
object PlainYoutube {
    const val MUSIC = "Music/PlainDownload/"
    const val MOVIES = "Movies/PlainDownload/"
    private const val CACHED = "Cached/"

    /** Where a file sits in Plain Youtube's tree: channel (" - Topic" stripped), album ("" = none), cached or saved. */
    class Place(val channel: String, val album: String, val cached: Boolean)

    private fun root(relPath: String): String? = listOf(MUSIC, MOVIES).firstOrNull { relPath.startsWith(it, ignoreCase = true) }

    fun inTree(relPath: String) = root(relPath) != null
    fun inMusicTree(relPath: String) = relPath.startsWith(MUSIC, ignoreCase = true)

    /** Fully cached by Plain Youtube: it may evict the file at any time. */
    fun isCached(relPath: String): Boolean = root(relPath)?.let { relPath.startsWith(it + CACHED, ignoreCase = true) } ?: false

    fun place(relPath: String): Place? {
        val r = root(relPath) ?: return null
        val cached = isCached(relPath)
        val parts = relPath.substring(r.length + if (cached) CACHED.length else 0).split('/')
            .map { it.trim() }.filter { it.isNotEmpty() }
        val channel = Naming.stripTopic(parts.firstOrNull() ?: return null).ifEmpty { return null }
        return Place(channel, parts.getOrNull(1) ?: "", cached)
    }

    /**
     * Album of a file in the tree. MediaStore puts the folder name into ALBUM for untagged files: that is not a tag,
     * so the album folder (or "" when there is none) is used. A real album tag wins.
     */
    fun album(relPath: String, storeAlbum: String): String {
        val p = place(relPath) ?: return storeAlbum
        val last = relPath.trimEnd('/').substringAfterLast('/').trim()
        return if (storeAlbum.isBlank() || storeAlbum.trim().equals(last, ignoreCase = true)) p.album else storeAlbum
    }

    /** Folders tab: "Channel › Album", cached ones as "Cached › Channel › Album". Null outside the tree. */
    fun folderTitle(folder: String): String? {
        val p = place("$folder/") ?: return null
        return (if (p.cached) "Cached › " else "") + p.channel + if (p.album.isNotEmpty()) " › " + p.album else ""
    }

    /**
     * Why the organiser leaves a file alone: Plain Youtube owns its cache (never moved or trashed), and its saved music is
     * already sorted per channel/album (moving it would break its Saved/downloaded marks; can be ticked to organise anyway).
     */
    fun organiserSkip(relPath: String): String? = when {
        !inMusicTree(relPath) -> null
        isCached(relPath) -> tr("cached by Plain Youtube (it manages these)", "Plain Youtube önbelleğinde (onları o yönetir)")
        else -> tr("saved by Plain Youtube, already sorted per channel", "Plain Youtube kaydetti, zaten kanala göre sıralı")
    }
}

/** Pure naming rules for the organised library: Music/LocalMusic/<Artist>/<Album>/NN Title.ext */
object Naming {
    const val ROOT = "Music/LocalMusic/"
    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val SINGLES = "Singles"

    private val BAD = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
    private val SPACES = Regex("\\s+")
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }

    /** Safe single path component (also safe when copied to Windows/FAT). Empty if nothing usable is left. */
    fun sanitize(s: String, maxBytes: Int = 120): String {
        var t = BAD.replace(s, " ")
        t = SPACES.replace(t, " ").trim()
        t = t.trim('.', ' ')
        if (t.isEmpty()) return ""
        t = truncateUtf8(t, maxBytes).trim().trimEnd('.', ' ')
        if (t.uppercase(Locale.ROOT) in RESERVED) t = "_$t"
        return t
    }

    fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + n > maxBytes) return s.substring(0, i)
            bytes += n
            i += Character.charCount(cp)
        }
        return s
    }

    data class Guess(val artist: String?, val title: String, val track: Int)

    private val YOUTUBE_ID = Regex("""\s*\[[A-Za-z0-9_-]{11}]$""")
    private val COPY_MARK = Regex("\\s*\\(\\d{1,2}\\)$")
    private val SITE = Regex("[\\s_-]*[\\w-]+\\.(org|com|net|io|me|cc|to|ru|xyz)$", RegexOption.IGNORE_CASE)
    private val TRACK_PREFIX = Regex("^(\\d{1,3})(?:\\s*[-._)]\\s*|\\s+)(.+)$")
    private val JUNK_TAIL = Regex("\\s*[(\\[][^)\\]]*\\b(official|lyrics?|video|audio|hd|hq)\\b[^)\\]]*[)\\]]\\s*", RegexOption.IGNORE_CASE)

    /** Best guess from a file name like "03 - Artist - Title (Official Video).mp3". */
    fun guessFromFileName(fileName: String): Guess {
        var s = fileName.substringBeforeLast('.', fileName)
        s = YOUTUBE_ID.replace(s.trim(), "")
        s = s.replace('_', ' ')
        s = COPY_MARK.replace(s.trim(), "")
        s = SITE.replace(s, "")
        s = JUNK_TAIL.replace(s, " ")
        s = SPACES.replace(s, " ").trim()
        var track = 0
        TRACK_PREFIX.matchEntire(s)?.let { m ->
            track = m.groupValues[1].toInt()
            s = m.groupValues[2].trim()
        }
        var artist: String? = null
        val dash = s.indexOf(" - ")
        if (dash > 0 && dash + 3 < s.length && s.substring(dash + 3).any { it.isLetter() } && balanced(s.substring(0, dash))) {
            artist = s.substring(0, dash).trim()
            s = s.substring(dash + 3).trim()
        } else if (s.count { it == '-' } == 1) {
            val a = s.substringBefore('-').trim()
            val b = s.substringAfter('-').trim()
            // "Zeynel Karabulut-Tanrı Uludur" yes; "Cemal-i Hakk" (Turkish suffix) and "İmame r-Rusli" no
            if (a.length >= 2 && b.length >= 2 && a.contains(' ') && balanced(a) &&
                b[0].isUpperCase() && a.substringAfterLast(' ').length >= 2
            ) {
                artist = a
                s = b
            }
        }
        if (s.isEmpty()) s = fileName.substringBeforeLast('.', fileName)
        return Guess(artist, s, track)
    }

    /** No unclosed bracket or quote: a hyphen inside "(DU-TAR)" or "＂İZMİR-UYGAR＂" is not an artist separator. */
    private fun balanced(s: String): Boolean =
        s.count { it == '(' } == s.count { it == ')' } && s.count { it == '[' } == s.count { it == ']' } &&
            s.count { it == '"' || it == '＂' } % 2 == 0

    private val GENERIC_TITLE = Regex("^(track|audiotrack|audio track|untitled|unknown)\\s*\\d*$", RegexOption.IGNORE_CASE)
    private val UNKNOWN = setOf("<unknown>", "unknown", "unknown artist", "unknown album", "artist", "album")

    private fun clean(s: String?): String? {
        val t = s?.trim()?.let { SPACES.replace(it, " ") } ?: return null
        if (t.isEmpty() || t.lowercase(Locale.ROOT) in UNKNOWN) return null
        return t
    }

    /** "3/12" -> 3, "03" -> 3; MediaStore's 1003 (disc*1000+track) -> 3. */
    fun parseTrack(s: String?): Int {
        val n = s?.trim()?.substringBefore('/')?.trim()?.toIntOrNull() ?: return 0
        return when {
            n <= 0 -> 0
            n >= 1000 -> n % 1000
            else -> n
        }
    }

    fun parseDisc(disc: String?, track: String?): Int {
        val d = disc?.trim()?.substringBefore('/')?.trim()?.toIntOrNull()
        if (d != null && d > 0) return d
        val t = track?.trim()?.substringBefore('/')?.trim()?.toIntOrNull() ?: 0
        return if (t >= 1000) t / 1000 else 1
    }

    private val ID3_GENRE = Regex("^\\(?(\\d{1,3})\\)?$")

    private val GENERIC_FOLDER = Regex(
        """^(echo|m[uü]zik|music|musik|download|downloads|documents|audio|songs|mp3|flac|m4a|ogg|wav|media|sounds?|liked music|\d+|.*\d{8}t\d{6}z.*|[0-9a-f]{8}-[0-9a-f]{4}-.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** Meaningful name of the folder a file is in ("" for Download/, Music/, app download roots, takeout dumps...). */
    fun sourceFolder(relPath: String): String {
        val parts = relPath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.size < 2) return ""
        val last = parts.last()
        return if (GENERIC_FOLDER.matches(last.trim())) "" else last.trim()
    }

    /** Artist folder of a file already in Music/LocalMusic/<Artist>/<Album>/ (keeps re-runs stable for untagged files). */
    fun organisedArtist(relPath: String): String? {
        if (!relPath.startsWith(ROOT, ignoreCase = true)) return null
        val parts = relPath.substring(ROOT.length).trim('/').split('/')
        return parts.takeIf { it.size >= 2 }?.first()?.takeUnless { it.isBlank() || it.equals(UNKNOWN_ARTIST, true) }
    }

    // --- artist from the folder (files without artist tags) -----------------------------------

    /** Folders that say nothing about the artist: storage roots, app download folders, format names, disc folders... */
    private val GENERIC_ARTIST_FOLDER = Regex(
        """^(music|m[uü]zik|musik|m[uú]sica|download|downloads|documents|audio|audios|songs|sounds?|media|movies|videos?|""" +
            """mp3|mp4|m4a|flac|ogg|opus|wav|aac|liked music|bluetooth|recordings?|plain ?download|local ?music|echo|""" +
            """whatsapp( audio| video)?|telegram( audio| video)?|unknown|unknown artist|<unknown>|various|various artists|""" +
            """singles?|new folder|misc|other|playlists?|android|data|files|""" +
            """(cd|dis[ck])\s*\d+|\d+|.*\d{8}t\d{6}z.*|[0-9a-f]{8}-[0-9a-f]{4}-.*)$""",
        RegexOption.IGNORE_CASE,
    )

    /** "Karma (2001)", "2001 - Karma", "Best Of", "Enderal OST", "Vol. 2": clearly an album folder. */
    private val ALBUM_FOLDER = Regex(
        """(^|[\s(\[_-])(19|20)\d{2}($|[\s)\]_-])|\b(album|ost|soundtrack|ep|lp|deluxe|remaster(ed)?|edition|greatest hits|best of|vol\.?\s*\d+|volume\s*\d+)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val TOPIC = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)
    private val YEARISH = Regex("""^[(\[]?\d{2,4}[)\]]?$""")

    private fun isGenericFolder(name: String) = GENERIC_ARTIST_FOLDER.matches(name)

    /** YouTube's auto-generated "X - Topic" channels -> "X". */
    fun stripTopic(name: String): String = TOPIC.replace(SPACES.replace(name, " ").trim(), "").trim()

    /** Folder name as an artist: spaces tidied, YouTube's "X - Topic" -> "X". Null if nothing usable is left. */
    private fun tidyArtist(name: String): String? =
        TOPIC.replace(SPACES.replace(name, " ").trim(), "").trim().takeUnless { it.isEmpty() || isGenericFolder(it) }

    /** "Pink Floyd - The Wall (1979)" -> "Pink Floyd" (only for album folders; "1998 - Karma" has no artist part). */
    private fun artistPart(albumFolder: String): String? {
        val i = albumFolder.indexOf(" - ")
        if (i <= 0) return null
        val left = albumFolder.substring(0, i).trim()
        return if (YEARISH.matches(left)) null else tidyArtist(left)
    }

    /** An artist found in the folders, and whether it is the file's own folder taken whole (then it is no album name). */
    class FolderArtist(val name: String, val ownFolder: Boolean)

    /**
     * Artist for a file without artist tags, from its RELATIVE_PATH. The rule:
     * 1. In the organised library (Music/LocalMusic/<Artist>/<Album>/) the <Artist> level, unless it is "Unknown Artist".
     * 2. Otherwise walk up from the file's own folder and skip generic folders (Music, Download, PlainDownload, WhatsApp Audio,
     *    Bluetooth, numbers, "CD 1", "Unknown"...). The first other folder is the artist, with "X - Topic" -> "X".
     * 3. If that folder is clearly an album (a year like "Karma (2001)" / "2001 - Karma", or words like OST, Best Of, Vol. 2),
     *    "Artist - Album (Year)" gives its artist part; else the next non-generic folder above is the artist, if there is one.
     * Null when nothing usable is left (the song stays under "Unknown Artist").
     * Before all this, Plain Youtube's folders (Music|Movies/PlainDownload/[Cached/]<Channel>/[<Album>/]) are read as they are.
     */
    fun folderArtistOf(relPath: String): FolderArtist? {
        // Plain Youtube's tree is explicit: <Channel>/[<Album>/]. No year or keyword guessing there.
        PlainYoutube.place(relPath)?.let { return FolderArtist(it.channel, ownFolder = it.album.isEmpty()) }
        if (PlainYoutube.inTree(relPath)) return null // a file directly in PlainDownload/ or Cached/
        if (relPath.startsWith(ROOT, ignoreCase = true)) organisedArtist(relPath)?.let { a -> tidyArtist(a)?.let { return FolderArtist(it, false) } }
        val parts = relPath.split('/').map { SPACES.replace(it, " ").trim() }.filter { it.isNotEmpty() }
        for (i in parts.indices.reversed()) {
            val p = parts[i]
            if (isGenericFolder(p)) continue
            if (ALBUM_FOLDER.containsMatchIn(p)) {
                artistPart(p)?.let { return FolderArtist(it, false) }
                val above = (i - 1 downTo 0).firstOrNull { !isGenericFolder(parts[it]) }
                if (above != null) tidyArtist(parts[above])?.let { return FolderArtist(it, false) }
            }
            return tidyArtist(p)?.let { FolderArtist(it, i == parts.size - 1) }
        }
        return null
    }

    fun artistFromFolder(relPath: String): String? = folderArtistOf(relPath)?.name

    /** "Song (2).mp3" is a numbered variant of "Song.mp3". */
    fun isNumberedVariant(name: String, of: String): Boolean {
        if (name.equals(of, ignoreCase = true)) return true
        val ext = extension(of)
        val base = if (ext.isEmpty()) of else of.substring(0, of.length - ext.length - 1)
        val rx = Regex(Regex.escape(base) + """ \(\d+\)""" + (if (ext.isEmpty()) "" else Regex.escape(".$ext")), RegexOption.IGNORE_CASE)
        return rx.matches(name)
    }

    fun resolve(raw: RawTags, fileName: String, relPath: String = ""): Tags {
        val guess = guessFromFileName(fileName)
        var title = clean(raw.title)
        var fromName = false
        if (title == null || GENERIC_TITLE.matches(title)) {
            title = guess.title
            fromName = true
        }
        val artist = clean(raw.artist) ?: guess.artist?.takeIf { fromName } ?: organisedArtist(relPath) ?: ""
        val albumArtist = clean(raw.albumArtist) ?: ""
        val album = clean(raw.album) ?: ""
        var track = parseTrack(raw.track)
        if (track == 0 && fromName) track = guess.track
        val genre = clean(raw.genre)?.takeUnless { ID3_GENRE.matches(it) } ?: ""
        var source = sourceFolder(relPath)
        var inferred = ""
        // No artist at all: the folder names it ("Music/PlainDownload/Some Channel/" -> Some Channel/Singles/). Files already
        // in Music/LocalMusic/ keep their folders (organisedArtist above, or Unknown Artist/<folder>/), so re-runs are stable.
        if (artist.isEmpty() && albumArtist.isEmpty() && !relPath.startsWith(ROOT, ignoreCase = true)) folderArtistOf(relPath)?.let { fa ->
            inferred = fa.name
            if (fa.ownFolder) source = "" // the folder is the artist, not an album: "Some Channel/Singles", not "Some Channel/Some Channel"
        }
        return Tags(title, artist, albumArtist, album, track, parseDisc(raw.disc, raw.track), genre, fromName, source, inferred)
    }

    /** Folder artist: album artist, else the primary track artist, else the artist named by the folders. */
    fun folderArtist(t: Tags): String =
        sanitize(Text.leadArtist(t.albumArtist.ifEmpty { t.artist }), 80).ifEmpty { sanitize(t.inferredArtist, 80) }.ifEmpty { UNKNOWN_ARTIST }

    fun folderAlbum(t: Tags): String = sanitize(t.album, 80).ifEmpty { sanitize(t.sourceFolder, 80) }.ifEmpty { SINGLES }

    fun fileName(t: Tags, ext: String): String {
        val prefix = when {
            t.track in 1..999 && t.disc > 1 -> String.format(Locale.ROOT, "%d-%02d ", t.disc, t.track)
            t.track in 1..999 -> String.format(Locale.ROOT, "%02d ", t.track)
            else -> ""
        }
        val title = sanitize(t.title, 110).ifEmpty { "Untitled" }
        val e = sanitize(ext.lowercase(Locale.ROOT), 10)
        return if (e.isEmpty()) prefix + title else "$prefix$title.$e"
    }

    fun extension(fileName: String): String =
        if (fileName.contains('.')) fileName.substringAfterLast('.') else ""

    /** Unify the spelling of names that differ only in case (the phone's storage is case-insensitive). Most common wins. */
    fun canonicalNames(names: List<String>): Map<String, String> {
        val byKey = names.groupBy { it.lowercase(Locale.ROOT) }
        return byKey.mapValues { (_, v) -> v.groupingBy { it }.eachCount().entries.maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { -v.indexOf(it.key) }).key }
    }

    /** Make [name] unique within [taken] (lower-cased "dir/name" keys) by appending " (2)", " (3)", ... */
    fun unique(dir: String, name: String, taken: MutableSet<String>): String {
        var candidate = name
        var n = 2
        while (!taken.add((dir + candidate).lowercase(Locale.ROOT))) {
            val ext = extension(name)
            val base = if (ext.isEmpty()) name else name.substring(0, name.length - ext.length - 1)
            candidate = if (ext.isEmpty()) "$base ($n)" else "$base ($n).$ext"
            n++
        }
        return candidate
    }
}
