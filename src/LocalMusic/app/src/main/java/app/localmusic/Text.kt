package app.localmusic

import java.text.Normalizer
import java.util.Locale

/** Pure text helpers: search folding, title/artist normalisation, time formatting. */
object Text {
    private val MARKS = Regex("\\p{M}+")
    private val SPACES = Regex("\\s+")

    /** Lower-case, accent-free form for search and matching (Turkish dotless/dotted i folded to "i"). */
    fun fold(s: String): String {
        if (s.isEmpty()) return s
        val t = s.replace('İ', 'i').replace('ı', 'i').replace('I', 'i').lowercase(Locale.ROOT)
        return MARKS.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "")
            .replace("ß", "ss").replace('ø', 'o').replace("æ", "ae").replace("œ", "oe").replace('đ', 'd').replace('ł', 'l')
    }

    /** Folded search tokens of a query; a song matches when every token is in its haystack. */
    fun tokens(query: String): List<String> = SPACES.split(fold(query).trim()).filter { it.isNotEmpty() }

    fun matches(haystack: String, tokens: List<String>): Boolean = tokens.all { haystack.contains(it) }

    // Bracketed decorations that don't make a different recording.
    private val JUNK_BRACKET = Regex(
        "[(\\[{][^)\\]}]*\\b(official|video|audio|lyrics?|lyric video|visuali[sz]er|hd|hq|4k|music video|mv|clip|explicit|clean)\\b[^)\\]}]*[)\\]}]",
        RegexOption.IGNORE_CASE,
    )
    private val FEAT = Regex("\\s+[(\\[]?\\s*(feat\\.?|ft\\.?|featuring)\\s+.*$", RegexOption.IGNORE_CASE)
    private val LEADING_TRACK = Regex("^\\d{1,3}\\s*[-._)]\\s*|^\\d{1,3}\\s+(?=\\D)")
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

    /** Key for "same song" matching: case/accents/punctuation/decorations ignored. */
    fun titleKey(title: String): String {
        var t = JUNK_BRACKET.replace(title, " ")
        t = FEAT.replace(t, "")
        t = LEADING_TRACK.replace(t.trim(), "")
        return NON_ALNUM.replace(fold(t), " ").trim()
    }

    private val ARTIST_JUNK = Regex("(\\s*-\\s*topic|vevo|\\s+official)$", RegexOption.IGNORE_CASE)

    fun artistKey(artist: String): String {
        var a = primaryArtist(artist)
        a = ARTIST_JUNK.replace(a, "")
        a = NON_ALNUM.replace(fold(a), " ").trim()
        return a.removePrefix("the ")
    }

    /** "A feat. B" -> "A" (used for folders and the Artists tab). */
    fun primaryArtist(artist: String): String = FEAT.replace(artist.trim(), "").trim().ifEmpty { artist.trim() }

    private val MULTI_ARTIST = Regex("""\s*(?:,|;|\s/\s)\s*""")

    /** First of several credited artists: "Marvin Kopp, Andreas Makusev" -> "Marvin Kopp". ("Simon & Garfunkel" stays whole.) */
    fun leadArtist(artist: String): String {
        val p = primaryArtist(artist)
        return MULTI_ARTIST.split(p).firstOrNull { it.isNotBlank() }?.trim() ?: p
    }

    private val EDITION = Regex(
        """[(\[{][^)\]}]*\b(re-?master(ed)?|deluxe|expanded|edition|bonus|anniversary|special|collector'?s?)\b[^)\]}]*[)\]}]""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_YEAR = Regex("""\s+(19|20)\d{2}$""")
    private val GENERIC_ALBUMS = setOf("singles", "single", "unknown album", "unknown", "greatest hits", "best of", "the best of", "hits", "live", "demo", "demos", "untitled", "music", "songs")

    /** Album identity for merging: "Romantizma 2015 (Re-Mastered)" and "Romantizma" are the same album. */
    fun albumKey(album: String): String {
        val plain = NON_ALNUM.replace(fold(album), " ").trim()
        var a = NON_ALNUM.replace(fold(EDITION.replace(album, " ")), " ").trim()
        a = TRAILING_YEAR.replace(a, "").trim()
        return a.ifEmpty { plain }
    }

    /**
     * Grouping key for artists and albums: folded, every non-letter/digit removed. A tag ("AC/DC", "Live: 2020") and a
     * folder name with those characters sanitised ("AC_DC", "Live_ 2020", "Live 2020") give the same key.
     */
    fun groupKey(s: String): String = NON_ALNUM.replace(fold(s), "").ifEmpty { fold(s).trim() }

    fun isGenericAlbum(key: String): Boolean = key.isEmpty() || key in GENERIC_ALBUMS

    fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

    /** "5 h 12 min", "42 min" */
    fun longTime(ms: Long): String {
        val min = (ms + 30_000) / 60_000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    fun time(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.ROOT, "%d:%02d", m, sec)
    }

    fun bytes(n: Long): String = when {
        n >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", n / 1e9)
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", n / 1e6)
        n >= 1_000 -> String.format(Locale.ROOT, "%.0f kB", n / 1e3)
        else -> "$n B"
    }

    /** Sort key: folded, leading "the " and punctuation ignored. */
    fun sortKey(s: String): String {
        val f = fold(s).trimStart { !it.isLetterOrDigit() }
        return f.removePrefix("the ")
    }
}
