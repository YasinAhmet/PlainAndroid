package app.plain.download

/** Where a RUNNING job is: downloading its streams, waiting for the saver, converting (remux/merge) or copying. */
enum class Phase { DOWNLOAD, WAIT_SAVE, CONVERT, COPY }

/** How a finished download becomes the public file. */
object SavePlan {
    enum class Mode { COPY, REMUX_M4A, REMUX_OGG, MERGE }

    data class Out(val mode: Mode, val ext: String, val mime: String) {
        val converts get() = mode != Mode.COPY
    }

    /**
     * Audio. M4A with "original format" (default) = YouTube's own file copied as-is (DASH fragmented MP4 with a
     * sidx index, .m4a / audio/mp4, an audio file for MediaStore). Opus always becomes .ogg: its original container
     * is WebM, and Android's MIME map has no audio-typed WebM extension (.webm = video/webm, no .weba), so an
     * original Opus file would be filed as a video.
     */
    fun audio(fmt: AudioFmt, original: Boolean): Out = when {
        fmt == AudioFmt.OPUS -> Out(Mode.REMUX_OGG, "ogg", "audio/ogg")
        original -> Out(Mode.COPY, "m4a", "audio/mp4")
        else -> Out(Mode.REMUX_M4A, "m4a", "audio/mp4")
    }

    /** Video: a progressive stream is copied; separate video + audio streams must be merged. */
    fun video(single: Boolean): Out = if (single) Out(Mode.COPY, "mp4", "video/mp4") else Out(Mode.MERGE, "mp4", "video/mp4")
}

/**
 * Smoothed "about 1:10 left" for conversions. The *rate* (fraction per second, overall average so far) is smoothed
 * exponentially and the time left is (1 − f) ÷ rate: exact for steady progress, calm when the speed jumps.
 */
class Eta(private val alpha: Double = 0.3) {
    private var rate = -1.0

    /** Seconds left, or null while there's too little to go on (first 2 % or first 1.5 s). */
    fun update(fraction: Double, elapsedMs: Long): Long? {
        if (fraction <= 0.02 || elapsedMs < 1500) return null
        if (fraction >= 1.0) return 0
        val raw = fraction / (elapsedMs / 1000.0)
        rate = if (rate < 0) raw else alpha * raw + (1 - alpha) * rate
        return Math.round((1 - fraction) / rate)
    }
}

object Pipeline {
    /** "4 s", "2:41", "1:02:03". */
    fun took(ms: Long): String {
        val s = Math.round(ms / 1000.0)
        return if (s < 60) "$s s" else Text.duration(s)
    }

    /** The status line of a downloads-list row. */
    fun status(j: Job): String {
        val pct = if (j.total > 0) (j.done * 100 / j.total).toInt().coerceIn(0, 100) else 0
        return when (j.state) {
            State.QUEUED -> "Queued"
            State.RUNNING -> when (j.phase) {
                Phase.WAIT_SAVE -> "Downloaded · waiting to " + if (j.saveConverts) "convert" else "save"
                Phase.CONVERT -> "Converting ${j.savePct}%" + (j.etaSec.takeIf { it >= 0 }?.let { " · about ${took(it * 1000)} left" } ?: "")
                Phase.COPY -> "Saving ${j.savePct}%"
                else -> if (j.done > 0 && j.total > 0) "Downloading $pct% · ${Text.size(j.done)} of ${Text.size(j.total)}" else "Starting…"
            }
            State.DONE -> if (j.media == Media.TRASHED) "In trash · Restore from the menu or ⋮ → Trash"
            else if (j.media == Media.GONE) "Removed (the file was deleted)"
            else "Saved · ${(j.relPath ?: "") + (j.fileName ?: "")}" +
                (if (j.saveMs > 0) " · " + (if (j.savedConverted) "converted" else "saved") + " in " + took(j.saveMs) else "")
            State.FAILED -> "Failed: ${j.error ?: "unknown error"}"
            State.CANCELED -> "Canceled"
        }
    }

    /** "1 downloading · 1 converting · 3 queued" for the notification. */
    fun summary(jobs: List<Job>): String {
        val run = jobs.filter { it.state == State.RUNNING }
        val parts = ArrayList<String>()
        run.count { it.phase == Phase.DOWNLOAD }.takeIf { it > 0 }?.let { parts += "$it downloading" }
        run.count { it.phase == Phase.CONVERT || it.phase == Phase.COPY }.takeIf { it > 0 }?.let { parts += "$it converting" }
        run.count { it.phase == Phase.WAIT_SAVE }.takeIf { it > 0 }?.let { parts += "$it waiting to convert" }
        jobs.count { it.state == State.QUEUED }.takeIf { it > 0 }?.let { parts += "$it queued" }
        return parts.joinToString(" · ")
    }

    /**
     * May the downloader start the next job while others wait for the saver? Their parts are already on disk (and
     * counted in [free]) but each still needs about its size again for the output; the next download needs its
     * estimate twice (parts + output). Unknown estimates (≤ 0) count as 0; [margin] is always kept.
     */
    fun canDownload(free: Long, nextEst: Long, waitingParts: Long, margin: Long = 300L shl 20): Boolean =
        free - waitingParts - 2 * maxOf(0L, nextEst) >= margin
}
