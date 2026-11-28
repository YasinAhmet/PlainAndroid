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

    /** A file name may have 255 bytes (UTF-8); the name part keeps room for ".m4a" and a " (2)" the media store may add. */
    const val MAX_NAME_BYTES = 200

    /**
     * A typed new name made safe for a file name: no path or reserved characters, no leading dots or spaces (a
     * hidden file would drop out of the list), trimmed, at most 120 characters and [MAX_NAME_BYTES] bytes (Turkish
     * letters take 2, emoji 4), never cut inside a character, ".m4a" added. Null when nothing usable is left.
     */
    fun cleanName(typed: String): String? {
        var s = shownName(typed.trim())
        s = s.map { c -> if (c in "/\\:*?\"<>|" || c.code < 32 || c.code == 127) ' ' else c }.joinToString("")
        s = s.replace(Regex("\\s+"), " ").trim().trimStart { it == '.' || it == ' ' }.trim()
        s = fit(s, 120, MAX_NAME_BYTES).trim()
        return if (s.isEmpty()) null else s + EXT
    }

    /** [s] cut to at most [maxChars] UTF-16 chars and [maxBytes] UTF-8 bytes, keeping surrogate pairs whole. */
    fun fit(s: String, maxChars: Int, maxBytes: Int): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val n = when { cp < 0x80 -> 1; cp < 0x800 -> 2; cp < 0x10000 -> 3; else -> 4 }
            val len = Character.charCount(cp)
            if (bytes + n > maxBytes || i + len > maxChars) break
            bytes += n
            i += len
        }
        return s.substring(0, i)
    }

    /**
     * [fileName] ("Name.m4a") as it is, or "Name (2).m4a", "Name (3).m4a"… when another recording is already
     * called that ([taken]: the other recordings' shown names; case is ignored, as the storage does).
     */
    fun uniqueName(fileName: String, taken: Collection<String>): String {
        val base = shownName(fileName)
        fun free(n: String) = taken.none { it.equals(n, ignoreCase = true) }
        if (free(base)) return fileName
        var k = 2
        while (!free("$base ($k)")) k++
        return "$base ($k)$EXT"
    }

    const val CALL_NONE = 0
    const val CALL_PAUSE = 1
    const val CALL_RESUME = 2

    /**
     * What a change of the phone's audio mode does to a recording: a call (or ringing) while recording pauses it;
     * the end of the call resumes it, but only when the call paused it (a pause the user made stays).
     */
    fun callAction(inCall: Boolean, recording: Boolean, paused: Boolean, pausedByCall: Boolean): Int = when {
        inCall && recording -> CALL_PAUSE
        !inCall && paused && pausedByCall -> CALL_RESUME
        else -> CALL_NONE
    }

    /** Storage left free when a recording stops for a full disk (room for the file's index). */
    const val RESERVE = 16L shl 20

    /** The byte limit for a recording from the [free] storage: 0 = too little room to start, Long.MAX_VALUE = unknown. */
    fun recordLimit(free: Long, reserve: Long = RESERVE): Long {
        if (free == Long.MAX_VALUE) return Long.MAX_VALUE
        val room = free - reserve
        return if (room < (1L shl 20)) 0L else room
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
