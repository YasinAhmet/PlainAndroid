package app.plain.download

import org.json.JSONArray
import org.json.JSONObject

enum class State { QUEUED, RUNNING, DONE, FAILED, CANCELED }

/**
 * One download. Holds the user's choices, not stream URLs: those expire after ~6 h, so every (re)try asks YouTube
 * again. Partial stream files survive in noBackupFilesDir/parts so a retry resumes where it stopped.
 */
data class Job(
    val id: Long,
    val videoId: String,
    var title: String,
    var channel: String, // already cleaned (" - Topic" stripped)
    var durationSec: Long,
    val kind: Kind,
    var audioFmt: AudioFmt,
    val audioQ: AudioQ,
    val height: Int, // video only
    val codec: VCodec, // video only: AVC or AV1
    var state: State = State.QUEUED,
    var error: String? = null,
    var done: Long = 0,
    var total: Long = 0,
    var uri: String? = null, // content:// of the saved file
    var fileName: String? = null,
    var relPath: String? = null,
    val created: Long = System.currentTimeMillis(),
    var finished: Long = 0,
    var album: String? = null, // audio: album folder (YouTube Music album, or the playlist it came from)
    val cache: Boolean = false, // "Cache N": fill the Music (AUDIO) / Video cache instead of saving a download
    // pipeline (memory only): which stage a RUNNING job is in, conversion progress / ETA
    var phase: Phase = Phase.DOWNLOAD,
    var savePct: Int = 0,
    var etaSec: Long = -1,
    var saveConverts: Boolean = false,
    // kept: how long saving took and whether it converted ("converted in 2:41" / "saved in 4 s")
    var saveMs: Long = 0,
    var savedConverted: Boolean = false,
    // bulk actions: every track of one action shares a batch id and title (one row in the downloads list)
    val batch: Long = 0,
    val batchTitle: String = "",
    // the saved file as MediaStore sees it (synced on resume: trashed / deleted / restored by any app)
    var media: Media = Media.OK,
    // "Add missing cover art": a job that tags an already saved file ([uri]) in place instead of downloading
    val art: Boolean = false,
    // members-only video (2.0): blue MEMBERS badge in the downloads list
    var members: Boolean = false,
) {
    val active get() = state == State.QUEUED || state == State.RUNNING

    val what: String
        get() = if (art) "Cover art" else if (cache) (if (kind == Kind.AUDIO) "Cache · music" else "Cache · video ${height}p")
        else if (kind == Kind.AUDIO) "Audio ${audioFmt.label}" else "Video MP4 ${height}p" + if (codec == VCodec.AV1) " AV1" else ""
}

object JobCodec {
    fun encode(jobs: List<Job>): String {
        val a = JSONArray()
        for (j in jobs) a.put(JSONObject().apply {
            put("id", j.id); put("videoId", j.videoId); put("title", j.title); put("channel", j.channel)
            put("duration", j.durationSec); put("kind", j.kind.name); put("audioFmt", j.audioFmt.name)
            put("audioQ", j.audioQ.name); put("height", j.height); put("codec", j.codec.name)
            put("state", j.state.name); put("done", j.done); put("total", j.total); put("created", j.created)
            put("finished", j.finished)
            j.error?.let { put("error", it) }
            j.uri?.let { put("uri", it) }
            j.fileName?.let { put("fileName", it) }
            j.relPath?.let { put("relPath", it) }
            j.album?.let { put("album", it) }
            if (j.cache) put("cache", true)
            if (j.saveMs > 0) { put("saveMs", j.saveMs); put("converted", j.savedConverted) }
            if (j.batch != 0L) { put("batch", j.batch); put("batchTitle", j.batchTitle) }
            if (j.media != Media.OK) put("media", j.media.name)
            if (j.art) put("art", true)
            if (j.members) put("members", true)
        })
        return a.toString()
    }

    fun decode(s: String): MutableList<Job> {
        val out = ArrayList<Job>()
        val a = JSONArray(s)
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            try {
                out += Job(
                    id = o.getLong("id"),
                    videoId = o.getString("videoId"),
                    title = o.optString("title"),
                    channel = o.optString("channel"),
                    durationSec = o.optLong("duration"),
                    kind = Kind.valueOf(o.getString("kind")),
                    audioFmt = enumOr(o.optString("audioFmt"), AudioFmt.M4A),
                    audioQ = enumOr(o.optString("audioQ"), AudioQ.HIGH),
                    height = o.optInt("height"),
                    codec = enumOr(o.optString("codec"), VCodec.AVC),
                    state = enumOr(o.optString("state"), State.FAILED),
                    error = o.optString("error").ifEmpty { null },
                    done = o.optLong("done"),
                    total = o.optLong("total"),
                    uri = o.optString("uri").ifEmpty { null },
                    fileName = o.optString("fileName").ifEmpty { null },
                    relPath = o.optString("relPath").ifEmpty { null },
                    created = o.optLong("created"),
                    finished = o.optLong("finished"),
                    album = o.optString("album").ifEmpty { null },
                    cache = o.optBoolean("cache"),
                    saveMs = o.optLong("saveMs"),
                    savedConverted = o.optBoolean("converted"),
                    batch = o.optLong("batch"),
                    members = o.optBoolean("members"),
                    batchTitle = o.optString("batchTitle"),
                    media = enumOr(o.optString("media"), Media.OK),
                    art = o.optBoolean("art"),
                )
            } catch (_: Exception) {
            }
        }
        return out
    }

    /**
     * After a restart nothing is running any more: a job that was RUNNING becomes "Interrupted" (Retry resumes its
     * parts); QUEUED jobs stay queued and run when the app is opened again (no background auto-start).
     */
    fun afterRestart(jobs: MutableList<Job>) {
        for (j in jobs) if (j.state == State.RUNNING) { j.state = State.FAILED; j.error = "Interrupted"; j.phase = Phase.DOWNLOAD }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String, def: E): E =
        try { enumValueOf<E>(name) } catch (_: Exception) { def }
}

object Text {
    fun size(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1000 -> "$bytes B"
        bytes < 1_000_000 -> "${(bytes + 500) / 1000} KB"
        bytes < 1_000_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(java.util.Locale.US, "%.2f GB", bytes / 1e9)
    }

    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60
        return if (h > 0) String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(java.util.Locale.US, "%d:%02d", m, s)
    }

    /** "Content-Range: bytes 0-99999/4557665" -> 4557665, or -1. */
    fun totalFromContentRange(h: String?): Long =
        h?.substringAfterLast('/', "")?.trim()?.toLongOrNull() ?: -1L
}
