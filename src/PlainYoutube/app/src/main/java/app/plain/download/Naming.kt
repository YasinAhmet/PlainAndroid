package app.plain.download

/**
 * File layout (a contract with LocalMusic, which reads artist = channel folder, album = folder below it):
 *   saved audio  -> Music/PlainDownload/<Channel>/[<Album>/]<Title>.m4a   (or .ogg for Opus)
 *   saved video  -> Movies/PlainDownload/<Channel>/<Title>.mp4
 *   cached audio -> Music/PlainDownload/Cached/<Channel>/[<Album>/]<Title>.m4a   (fully cached streams)
 *   cached video -> Movies/PlainDownload/Cached/<Channel>/<Title>.mp4
 * A channel literally named "Cached" becomes "Cached (channel)" so it never clashes with the cache root.
 */
object Naming {
    const val AUDIO_ROOT = "Music/PlainDownload/"
    const val VIDEO_ROOT = "Movies/PlainDownload/"
    const val CACHED = "Cached"
    private const val MAX_BYTES = 120 // leaves room for " (99).m4a" well under the 255-byte name limit

    /** Channel name used as folder: "Artist - Topic" (YouTube Music auto channels) -> "Artist". */
    fun channel(author: String?): String {
        val a = author.orEmpty().trim().removeSuffix(" - Topic").trim()
        val c = sanitize(a).ifEmpty { "Unknown" }
        return if (c.equals(CACHED, ignoreCase = true)) "$c (channel)" else c
    }

    fun title(t: String?): String = sanitize(t.orEmpty()).ifEmpty { "Untitled" }

    /** Make a name safe on FAT/exFAT/ext4 and in MediaStore: no / \ : * ? " < > |, no control chars, no leading dot. */
    fun sanitize(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ':' && i + 1 < s.length && s[i + 1] == ' ' -> sb.append(" -")
                c == ':' -> sb.append('-')
                c == '/' || c == '\\' || c == '|' -> sb.append('-')
                c == '"' -> sb.append('\'')
                c == '<' -> sb.append('(')
                c == '>' -> sb.append(')')
                c == '*' || c == '?' -> {}
                c.code < 0x20 || c.code == 0x7F -> sb.append(' ')
                c == '​' || c == '‎' || c == '‏' || c == '﻿' -> {}
                else -> sb.append(c)
            }
            i++
        }
        var r = sb.toString().replace(Regex("\\s+"), " ").trim()
        r = r.trimStart('.', ' ').trimEnd('.', ' ')
        return truncateUtf8(r, MAX_BYTES).trimEnd('.', ' ')
    }

    /** Cut to at most [max] UTF-8 bytes without splitting a character (or a surrogate pair). */
    fun truncateUtf8(s: String, max: Int): String {
        if (s.toByteArray(Charsets.UTF_8).size <= max) return s
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val n = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + n > max) break
            bytes += n
            end += Character.charCount(cp)
        }
        return s.substring(0, end).trimEnd()
    }

    /** Album folder name, or null when there is none (blank after cleaning). */
    fun album(a: String?): String? = sanitize(a.orEmpty()).ifEmpty { null }

    /** [album] is used for audio only; [cached] puts it under the temporary Cached/ tree. */
    fun relativePath(kind: Kind, channel: String, album: String? = null, cached: Boolean = false): String {
        val root = (if (kind == Kind.AUDIO) AUDIO_ROOT else VIDEO_ROOT) + (if (cached) "$CACHED/" else "")
        val al = if (kind == Kind.AUDIO) album(album) else null
        return root + channel + "/" + (if (al != null) "$al/" else "")
    }

    /** "Title.m4a", or "Title (2).m4a", "Title (3).m4a"... if taken (case-insensitive). */
    fun unique(base: String, ext: String, taken: Collection<String>): String {
        val lower = taken.mapTo(HashSet()) { it.lowercase() }
        val first = "$base.$ext"
        if (first.lowercase() !in lower) return first
        var n = 2
        while (true) {
            val name = "$base ($n).$ext"
            if (name.lowercase() !in lower) return name
            n++
        }
    }
}
