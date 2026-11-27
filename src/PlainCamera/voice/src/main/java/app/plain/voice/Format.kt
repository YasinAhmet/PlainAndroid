package app.plain.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Pure helpers (unit-tested): names, folders, times, sizes, pages. */
object Format {
    const val EXT = ".m4a"
    const val MIME = "audio/mp4"
    const val FOLDER = "Plain Voice"

    /**
     * Where new recordings go (MediaStore RELATIVE_PATH). Recordings/ is a valid audio folder only on Android 12+
     * (Environment.DIRECTORY_RECORDINGS, API 31); Android 10–11 accept just Music/, Podcasts/, Ringtones/… for audio.
     */
    fun folderFor(sdk: Int) = if (sdk >= 31) "Recordings/$FOLDER/" else "Music/$FOLDER/"

    /** Every folder this app may have saved to (a phone updated from Android 11 to 12 has both). */
    val allFolders = listOf("Recordings/$FOLDER/", "Music/$FOLDER/")

    /** "Voice 2026-11-27 14-03-22.m4a" */
    fun fileName(time: Long): String =
        "Voice " + SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).format(Date(time)) + EXT

    /** The name shown in the list: the file name without ".m4a". */
    fun shownName(displayName: String): String =
        if (displayName.endsWith(EXT, ignoreCase = true)) displayName.dropLast(EXT.length) else displayName

    /**
     * A typed new name made safe for a file name: no path or reserved characters, no leading dots, trimmed,
     * at most 120 characters, ".m4a" added. Null when nothing usable is left.
     */
    fun cleanName(typed: String): String? {
        var s = shownName(typed.trim())
        s = s.map { c -> if (c in "/\\:*?\"<>|" || c.code < 32) ' ' else c }.joinToString("")
        s = s.replace(Regex("\\s+"), " ").trim().trimStart('.').trim()
        if (s.length > 120) s = s.take(120).trim()
        return if (s.isEmpty()) null else s + EXT
    }

    /** List lengths: "0:42", "1:02:03". */
    fun duration(ms: Long): String {
        val s = (ms.coerceAtLeast(0) + 500) / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /** The big recording clock, never rounded up: "00:07", "12:34", "1:02:03". */
    fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    fun bytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1L shl 30).toDouble())
        b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1L shl 20).toDouble())
        b >= 1L shl 10 -> String.format(Locale.US, "%d KB", b shr 10)
        else -> "$b B"
    }

    fun pageCount(total: Int, perPage: Int) = if (total <= 0 || perPage <= 0) 1 else (total + perPage - 1) / perPage
    fun pageOf(index: Int, perPage: Int) = if (perPage <= 0) 0 else index.coerceAtLeast(0) / perPage
    fun range(page: Int, perPage: Int, total: Int): IntRange {
        val from = page * perPage
        return from until minOf(total, from + perPage)
    }

    /** Rows that fit a list [height] px tall, at least one. */
    fun rowsFor(height: Int, rowHeight: Int) = if (rowHeight <= 0) 1 else (height / rowHeight).coerceAtLeast(1)
}
