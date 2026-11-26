package app.plain.download

import org.json.JSONArray
import org.json.JSONObject
import app.plain.lang.tr

/** One stream from streamingData.formats / adaptiveFormats. */
data class Format(
    val itag: Int,
    val url: String,
    val mime: String, // "audio/mp4", "video/webm", ...
    val codecs: String, // "mp4a.40.2", "avc1.640028", "avc1.42001E, mp4a.40.2"
    val bitrate: Int,
    val avgBitrate: Int,
    val contentLength: Long, // -1 when unknown
    val width: Int,
    val height: Int,
    val fps: Int,
    val qualityLabel: String,
    val sampleRate: Int,
    val approxDurationMs: Long,
    val drc: Boolean,
    val audioTrackDefault: Boolean?, // null when the video has a single audio track
    val audioTrackId: String? = null, // audioTrack.id, e.g. "en.4" (original) / "ar.3" (dubbed); null = single track
    val audioOriginal: Boolean = false, // the uploader's own track (displayName "… original", xtags acont=original)
) {
    /** Language of the audio track ("en", "zh-Hans"), or null when the video has a single audio track. */
    val audioLang: String? get() = audioTrackId?.substringBefore('.')?.ifEmpty { null }

    val hasVideo get() = mime.startsWith("video/")
    val hasAudio get() = mime.startsWith("audio/") || codecs.contains("mp4a") || codecs.contains("opus")
    val muxed get() = hasVideo && codecs.contains(",")
    val isAac get() = mime == "audio/mp4" && codecs.startsWith("mp4a")
    val isOpus get() = mime == "audio/webm" && codecs.startsWith("opus")
    val vcodec: VCodec
        get() = when {
            !hasVideo -> VCodec.NONE
            codecs.startsWith("avc1") -> VCodec.AVC
            codecs.startsWith("av01") -> VCodec.AV1
            codecs.startsWith("vp9") || codecs.startsWith("vp09") -> VCodec.VP9
            else -> VCodec.OTHER
        }

    /** "1080" for 1080p, also for portrait Shorts (720x1280 is labelled 720p). */
    val p: Int
        get() = Regex("^(\\d+)p").find(qualityLabel)?.groupValues?.get(1)?.toIntOrNull()
            ?: if (width > 0 && height > 0) minOf(width, height) else height

    val bitsPerSec get() = if (avgBitrate > 0) avgBitrate else bitrate

    /** Best guess of the byte size: contentLength, else bitrate x duration. */
    val estBytes: Long
        get() = if (contentLength > 0) contentLength
        else if (approxDurationMs > 0) bitsPerSec.toLong() * approxDurationMs / 8000 else -1
}

enum class VCodec { NONE, AVC, AV1, VP9, OTHER }

data class VideoInfo(
    val id: String,
    val title: String,
    val author: String,
    val durationSec: Long,
    val thumbnail: String,
    val formats: List<Format>,
    val client: String,
    val hls: String? = null, // HLS master (VISIONOS / IOS_PRODUCER): what the in-app player streams for Watch
    val channelId: String? = null,
    val members: Boolean = false, // members-only video, answered through the signed-in account (2.0)
)

/** Why a video can't be downloaded. [tryOther] = another client might still work. */
/** [weak]: a bot check or network error, which a later client's answer may replace (the message is translated, so not matched). */
class YtError(message: String, val tryOther: Boolean, val geo: Boolean = false, val members: Boolean = false, val weak: Boolean = false) : Exception(message)

sealed class PlayerResult {
    class Ok(val info: VideoInfo, val visitorData: String?) : PlayerResult()
    class Fail(val error: YtError, val visitorData: String?) : PlayerResult()
}

object Yt {
    private val ID = "[A-Za-z0-9_-]{11}"
    private val LINK = Regex(
        "(?:https?://)?(?:(?:www|m|music|gaming)\\.)?" +
            "(?:youtube\\.com|youtube-nocookie\\.com)/" +
            "(?:watch\\?(?:[^#\\s]*?&)?v=|shorts/|embed/|live/|v/|e/)($ID)(?![A-Za-z0-9_-])",
        RegexOption.IGNORE_CASE,
    )
    private val SHORT = Regex("(?:https?://)?(?:www\\.)?youtu\\.be/($ID)(?![A-Za-z0-9_-])", RegexOption.IGNORE_CASE)
    private val BARE = Regex("^$ID$")

    /** Video id from a pasted/shared text ("Watch this! https://youtu.be/xyz?si=..."), or null. */
    fun videoId(text: String?): String? {
        if (text == null) return null
        val t = text.trim()
        LINK.find(t)?.let { return it.groupValues[1] }
        SHORT.find(t)?.let { return it.groupValues[1] }
        if (BARE.matches(t)) return t
        return null
    }

    fun isPlaylistOnly(text: String?): Boolean =
        text != null && videoId(text) == null && Regex("youtube\\.com/playlist\\?", RegexOption.IGNORE_CASE).containsMatchIn(text)

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    fun thumbUrl(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** Parse a /player response. Never throws for API-level problems; returns Fail with a readable message. */
    fun parsePlayer(json: String, clientKey: String, expectId: String? = null): PlayerResult {
        val o = try { JSONObject(json) } catch (e: Exception) {
            return PlayerResult.Fail(YtError(tr("YouTube sent an unreadable answer", "YouTube okunamayan bir yanıt gönderdi"), true), null)
        }
        val visitor = o.optJSONObject("responseContext")?.optString("visitorData")?.takeIf { it.isNotEmpty() }
        val ps = o.optJSONObject("playabilityStatus")
        val status = ps?.optString("status").orEmpty()
        val reason = ps?.let { reasonOf(it) }.orEmpty()
        val vd = o.optJSONObject("videoDetails")

        if (status != "OK") return PlayerResult.Fail(classify(status, reason), visitor)
        if (vd != null && expectId != null && vd.optString("videoId").let { it.isNotEmpty() && it != expectId }) {
            // YouTube sometimes answers a blocked client with a placeholder video
            return PlayerResult.Fail(YtError(tr("YouTube returned a different video", "YouTube farklı bir video döndürdü"), true), visitor)
        }
        if (vd?.optBoolean("isLive") == true || vd?.optBoolean("isUpcoming") == true) {
            return PlayerResult.Fail(YtError(tr("Live streams and premieres can't be downloaded", "Canlı yayınlar ve prömiyerler indirilemez"), false), visitor)
        }
        val sd = o.optJSONObject("streamingData")
            ?: return PlayerResult.Fail(YtError(tr("No streams in YouTube's answer", "YouTube'un yanıtında akış yok"), true), visitor)
        val formats = ArrayList<Format>()
        parseFormats(sd.optJSONArray("formats"), formats)
        parseFormats(sd.optJSONArray("adaptiveFormats"), formats)
        if (formats.isEmpty()) {
            val live = sd.has("hlsManifestUrl") && vd?.optBoolean("isLiveContent") == true
            return PlayerResult.Fail(
                if (live) YtError(tr("Live streams can't be downloaded", "Canlı yayınlar indirilemez"), false)
                else YtError(tr("No downloadable streams (protected or SABR only)", "İndirilebilir akış yok (korumalı ya da yalnızca SABR)"), true), visitor
            )
        }
        val id = vd?.optString("videoId").orEmpty().ifEmpty { expectId.orEmpty() }
        val info = VideoInfo(
            id = id,
            title = vd?.optString("title").orEmpty().ifBlank { id },
            author = vd?.optString("author").orEmpty(),
            durationSec = vd?.optString("lengthSeconds")?.toLongOrNull() ?: 0L,
            thumbnail = if (id.isNotEmpty()) thumbUrl(id) else "",
            formats = formats,
            client = clientKey,
            hls = sd.optString("hlsManifestUrl").ifEmpty { null },
            channelId = vd?.optString("channelId")?.ifEmpty { null },
        )
        return PlayerResult.Ok(info, visitor)
    }

    private fun reasonOf(ps: JSONObject): String {
        ps.optString("reason").takeIf { it.isNotBlank() }?.let { return it }
        // errorScreen.playerErrorMessageRenderer.reason.simpleText / runs
        val r = ps.optJSONObject("errorScreen")?.optJSONObject("playerErrorMessageRenderer")?.optJSONObject("reason")
        r?.optString("simpleText")?.takeIf { it.isNotBlank() }?.let { return it }
        val runs = r?.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    fun classify(status: String, reason: String): YtError {
        val r = reason.lowercase()
        return when {
            "not a bot" in r || "confirm you" in r && "bot" in r -> YtError(tr("YouTube asked to confirm you're not a bot", "YouTube bot olmadığınızı doğrulamanızı istedi"), true, weak = true)
            // "This content is not available on this country domain due to a legal complaint from the government."
            isGeo(r) -> YtError(tr("Blocked in your country (legal complaint)", "Ülkenizde engelli (yasal şikâyet)"), false, geo = true)
            // "Join this channel to get access to members-only content like this video, and other exclusive perks."
            Members.inReason(reason) -> YtError(tr("Members-only video", "Üyelere özel video"), false, members = true)
            "age" in r && ("confirm" in r || "restrict" in r || "inappropriate" in r) || status == "AGE_CHECK_REQUIRED" ||
                status == "AGE_VERIFICATION_REQUIRED" -> YtError(tr("Age-restricted video: needs sign-in, not supported", "Yaş kısıtlamalı video: oturum açma gerekir, desteklenmiyor"), false)
            "private" in r -> YtError(tr("Private video", "Özel video"), false)
            "live" in r && ("stream" in r || "event" in r) -> YtError(tr("Live streams can't be downloaded", "Canlı yayınlar indirilemez"), false)
            status == "LOGIN_REQUIRED" -> YtError(reason.ifBlank { tr("YouTube wants a sign-in for this video", "YouTube bu video için oturum açılmasını istiyor") }, true)
            status == "ERROR" -> YtError(reason.ifBlank { tr("Video unavailable", "Video kullanılamıyor") }, true) // e.g. "made for kids" on visionos
            status == "UNPLAYABLE" -> YtError(reason.ifBlank { tr("Video can't be played", "Video oynatılamıyor") }, true)
            status == "CONTENT_CHECK_REQUIRED" -> YtError(tr("Video needs a content warning confirmation", "Video için içerik uyarısı onayı gerekiyor"), true)
            else -> YtError(reason.ifBlank { tr("YouTube said: $status", "YouTube yanıtı: $status") }, true)
        }
    }

    /** YouTube's per-country block messages. */
    fun isGeo(reasonLower: String): Boolean =
        "country domain" in reasonLower || "legal complaint" in reasonLower ||
            "not available in your country" in reasonLower || "not made this video available in your country" in reasonLower

    private fun parseFormats(a: JSONArray?, out: MutableList<Format>) {
        if (a == null) return
        for (i in 0 until a.length()) {
            val f = a.optJSONObject(i) ?: continue
            val url = f.optString("url")
            if (url.isEmpty()) continue // signatureCipher needs the JS player: not supported
            val mimeType = f.optString("mimeType")
            val mime = mimeType.substringBefore(';').trim()
            val codecs = Regex("codecs=\"([^\"]*)\"").find(mimeType)?.groupValues?.get(1).orEmpty()
            val track = f.optJSONObject("audioTrack")
            out += Format(
                itag = f.optInt("itag"),
                url = url,
                mime = mime,
                codecs = codecs,
                bitrate = f.optInt("bitrate"),
                avgBitrate = f.optInt("averageBitrate"),
                contentLength = f.optString("contentLength").toLongOrNull() ?: -1L,
                width = f.optInt("width"),
                height = f.optInt("height"),
                fps = f.optInt("fps"),
                qualityLabel = f.optString("qualityLabel"),
                sampleRate = f.optString("audioSampleRate").toIntOrNull() ?: 0,
                approxDurationMs = f.optString("approxDurationMs").toLongOrNull() ?: 0L,
                drc = f.optBoolean("isDrc") || f.optString("xtags").contains("drc"),
                audioTrackDefault = if (track != null && track.has("audioIsDefault")) track.optBoolean("audioIsDefault") else null,
                audioTrackId = track?.optString("id")?.ifEmpty { null },
                audioOriginal = track != null && AudioTracks.isOriginal(track.optString("displayName"), url),
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Choosing streams

enum class Kind { AUDIO, VIDEO }
enum class AudioFmt(val label: String, val ext: String, val mime: String) {
    M4A("M4A (AAC)", "m4a", "audio/mp4"),
    OPUS("Opus (.ogg)", "ogg", "audio/ogg"),
}
enum class AudioQ { HIGH, LOW }

/** A selectable video quality: [p] lines, [codec] AVC (default) or AV1 (only when no AVC exists at that height). */
data class VideoChoice(val p: Int, val codec: VCodec, val fps: Int, val estBytes: Long) {
    val label get() = "${p}p" + (if (fps > 30) "$fps" else "") + (if (codec == VCodec.AV1) " · AV1" else "")
}

/** What to fetch: one stream copied as-is / remuxed, or video + audio merged. */
data class Plan(val video: Format?, val audio: Format?, val single: Format?) {
    val streams: List<Format> get() = listOfNotNull(single, video, audio)
    val estBytes: Long get() = streams.sumOf { maxOf(0L, it.estBytes) }
}

object Pick {
    /**
     * Audio-only streams of one container in one language, DRC dropped. [lang] null = the original track (dubbed
     * videos list every auto-dub too, alphabetically, so "the first one" was Arabic); a language with no stream of
     * this container falls back to the original.
     */
    fun audioCandidates(formats: List<Format>, fmt: AudioFmt, lang: String? = null): List<Format> {
        val plain = formats.filter { !it.hasVideo && !it.drc && (if (fmt == AudioFmt.M4A) it.isAac else it.isOpus) }
        return AudioTracks.select(plain, lang).distinctBy { it.itag }.sortedByDescending { it.bitsPerSec }
    }

    fun audio(formats: List<Format>, fmt: AudioFmt, q: AudioQ, lang: String? = null): Format? {
        val c = audioCandidates(formats, fmt, lang)
        return if (q == AudioQ.HIGH) c.firstOrNull() else c.lastOrNull()
    }

    /** "~128 kbps" style label for the chosen quality, or null when the container isn't offered. */
    fun audioLabel(formats: List<Format>, fmt: AudioFmt, q: AudioQ): String? =
        audio(formats, fmt, q)?.let { "~${(it.bitsPerSec + 500) / 1000} kbps" }

    /**
     * Heights to offer for MP4. Every height that has H.264 (video-only or progressive); AV1 only for heights above
     * the best H.264 and only where the platform muxer can write AV1 into MP4 (Android 14+). VP9 never: MediaMuxer's
     * MPEG4Writer has no VP9 support.
     */
    fun videoChoices(formats: List<Format>, sdk: Int): List<VideoChoice> {
        val aac = audio(formats, AudioFmt.M4A, AudioQ.HIGH)
        val out = ArrayList<VideoChoice>()
        for (p in formats.filter { it.vcodec == VCodec.AVC }.map { it.p }.distinct()) {
            if (p <= 0) continue
            val plan = planVideo(formats, p, VCodec.AVC) ?: continue
            out += VideoChoice(p, VCodec.AVC, (plan.single ?: plan.video)!!.fps, plan.estBytes)
        }
        val maxAvc = out.maxOfOrNull { it.p } ?: 0
        if (sdk >= 34 && aac != null) {
            for (p in formats.filter { it.vcodec == VCodec.AV1 && !it.muxed && it.p > maxAvc }.map { it.p }.distinct()) {
                val plan = planVideo(formats, p, VCodec.AV1) ?: continue
                out += VideoChoice(p, VCodec.AV1, plan.video!!.fps, plan.estBytes)
            }
        }
        return out.sortedByDescending { it.p }
    }

    /**
     * Streams for an MP4 at [p] lines. A progressive (muxed H.264+AAC, itag 18) stream at that height is used as-is;
     * otherwise the best video-only stream of [codec] at that height (highest fps, then bitrate) + the best AAC audio.
     */
    fun planVideo(formats: List<Format>, p: Int, codec: VCodec): Plan? {
        if (codec == VCodec.AVC) {
            formats.filter { it.muxed && it.vcodec == VCodec.AVC && it.p == p && it.codecs.contains("mp4a") && it.mime == "video/mp4" }
                .maxByOrNull { it.bitsPerSec }?.let { return Plan(null, null, it) }
        }
        val v = formats.filter { !it.muxed && it.vcodec == codec && it.mime == "video/mp4" && it.p == p }
            .sortedWith(compareByDescending<Format> { it.fps }.thenByDescending { it.bitsPerSec })
            .firstOrNull() ?: return null
        val a = audio(formats, AudioFmt.M4A, AudioQ.HIGH) ?: return null
        return Plan(v, a, null)
    }

    /** Plan for audio; falls back to the other container if the client didn't offer the wanted one. */
    fun planAudio(formats: List<Format>, fmt: AudioFmt, q: AudioQ): Pair<Plan, AudioFmt>? {
        audio(formats, fmt, q)?.let { return Plan(null, null, it) to fmt }
        val other = if (fmt == AudioFmt.M4A) AudioFmt.OPUS else AudioFmt.M4A
        return audio(formats, other, q)?.let { Plan(null, null, it) to other }
    }

    /** The remembered height if offered, else the closest lower one, else the lowest offered. */
    fun closest(choices: List<VideoChoice>, wantP: Int): VideoChoice? =
        choices.filter { it.p <= wantP }.maxByOrNull { it.p } ?: choices.minByOrNull { it.p }
}

// ---------------------------------------------------------------------------------------------------------------
// Audio languages (dubbed videos: the uploader's original track + YouTube's auto-dubs)

/** One audio language of a video. [lang] "en", "zh-Hans"; [original] = the uploader's own track. */
data class AudioTrack(val lang: String, val original: Boolean, val name: String = "") {
    /** "English · original", "Turkish", in the app's language. */
    fun label(locale: java.util.Locale): String {
        val shown = java.util.Locale.forLanguageTag(lang).getDisplayName(locale)
            .takeIf { it.isNotBlank() && !it.equals(lang, ignoreCase = true) }?.replaceFirstChar { it.titlecase(locale) }
            ?: name.substringBefore(" - ").ifBlank { lang }
        return shown + if (original) (if (locale.language == "tr") " · orijinal" else " · original") else ""
    }
}

object AudioTracks {
    /** displayName "English original" / HLS NAME "English - original", or xtags acont=original in the stream URL. */
    fun isOriginal(name: String?, url: String?): Boolean =
        name?.contains("original", ignoreCase = true) == true ||
            url?.let { "acont%3Doriginal" in it || "acont=original" in it || "acont%253Doriginal" in it } == true

    /** Languages offered by the stream list, original first; empty for a single-track video. */
    fun of(formats: List<Format>): List<AudioTrack> =
        sortTracks(formats.filter { !it.hasVideo && it.audioLang != null }.groupBy { it.audioLang!! }
            .map { (l, fs) -> AudioTrack(l, fs.any { it.audioOriginal }) })

    fun sortTracks(l: List<AudioTrack>): List<AudioTrack> =
        l.distinctBy { it.lang }.sortedWith(compareByDescending<AudioTrack> { it.original }.thenBy { it.lang })

    /**
     * Streams in language [lang] (null = the original). Single-track video (no track info) = all of them; original
     * not marked = YouTube's default track, else all.
     */
    fun select(formats: List<Format>, lang: String?): List<Format> {
        if (formats.none { it.audioTrackId != null || it.audioTrackDefault != null }) return formats
        if (lang != null) formats.filter { it.audioLang == lang }.takeIf { it.isNotEmpty() }?.let { return it }
        formats.filter { it.audioOriginal }.takeIf { it.isNotEmpty() }?.let { return it }
        formats.filter { it.audioTrackDefault == true }.takeIf { it.isNotEmpty() }?.let { return it }
        return formats
    }
}
