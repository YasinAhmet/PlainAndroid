package app.plain.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import app.plain.lang.tr

/**
 * Lossless remux with the platform's MediaExtractor -> MediaMuxer (no re-encoding, no libraries):
 *  - DASH audio (fragmented MP4, itag 140/139) -> plain .m4a           (MUXER_OUTPUT_MPEG_4)
 *  - WebM Opus (itag 251/250/249)              -> .ogg                 (MUXER_OUTPUT_OGG, API 29+)
 *  - DASH video-only H.264/AV1 + AAC audio     -> one .mp4             (MUXER_OUTPUT_MPEG_4; AV1 needs Android 14)
 */
object Mux {
    class Input(val file: File, val mimePrefix: String) // "audio/" or "video/"

    // All outputs go to a plain [File] in app storage (no FUSE): MPEG4Writer's many small writes and seeks are far
    // cheaper there than through a MediaStore file descriptor; the caller then copies the result in 1 MB blocks.
    // [progress] gets 0..1 (sample time ÷ duration), at most every 256 samples.

    fun audio(src: File, out: File, ogg: Boolean, cancelled: () -> Boolean, progress: (Float) -> Unit = {}) =
        run(listOf(Input(src, "audio/")), out,
            if (ogg) MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, cancelled, progress = progress)

    fun merge(video: File, audio: File, out: File, cancelled: () -> Boolean, progress: (Float) -> Unit = {}) =
        run(listOf(Input(video, "video/"), Input(audio, "audio/")), out, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, cancelled, progress = progress)

    /**
     * Cached HLS video -> MP4: concatenated MPEG-TS video segments + concatenated ADTS audio (ID3 stripped). TS
     * timestamps start at an arbitrary PTS and the ADTS file at 0, so each track is shifted to start at 0.
     */
    fun mergeTs(videoTs: File, audioAac: File?, out: File, cancelled: () -> Boolean) =
        run(listOfNotNull(Input(videoTs, "video/"), audioAac?.let { Input(it, "audio/") }), out,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, cancelled, perTrackZero = true)

    private class Src(val ex: MediaExtractor, val track: Int, var muxTrack: Int = -1, var done: Boolean = false)

    private fun run(inputs: List<Input>, out: File, format: Int, cancelled: () -> Boolean, perTrackZero: Boolean = false, progress: (Float) -> Unit = {}) {
        val srcs = ArrayList<Src>()
        var muxer: MediaMuxer? = null
        var started = false
        var ok = false
        try {
            var maxInput = 1 shl 20
            var rotation = 0
            val formats = ArrayList<MediaFormat>()
            for (inp in inputs) {
                val ex = MediaExtractor()
                srcs += Src(ex, -1) // so it's released on failure
                ex.setDataSource(inp.file.absolutePath)
                val t = (0 until ex.trackCount).firstOrNull {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith(inp.mimePrefix) == true
                } ?: throw IOException("No ${inp.mimePrefix.trimEnd('/')} track in ${inp.file.name}")
                ex.selectTrack(t)
                srcs[srcs.size - 1] = Src(ex, t)
                val f = ex.getTrackFormat(t)
                formats += f
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxInput = maxOf(maxInput, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                if (f.containsKey(MediaFormat.KEY_ROTATION)) rotation = f.getInteger(MediaFormat.KEY_ROTATION)
                if (inp.mimePrefix == "video/") maxInput = maxOf(maxInput, 8 shl 20)
            }
            val durationUs = formats.maxOfOrNull { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else 0L } ?: 0L
            out.delete()
            val m = MediaMuxer(out.absolutePath, format)
            muxer = m
            for ((i, s) in srcs.withIndex()) {
                s.muxTrack = try { m.addTrack(formats[i]) } catch (e: Exception) {
                    throw IOException(tr("This phone can't write ${formats[i].getString(MediaFormat.KEY_MIME)} into this container", "Bu telefon ${formats[i].getString(MediaFormat.KEY_MIME)} biçimini bu kapsayıcıya yazamıyor"), e)
                }
            }
            if (rotation != 0) m.setOrientationHint(rotation)
            m.start()
            started = true

            val buf = ByteBuffer.allocateDirect(maxInput)
            val info = MediaCodec.BufferInfo()
            // Interleave by timestamp so the MP4 has video and audio chunks side by side.
            var shift = Long.MAX_VALUE
            for (s in srcs) { val t = s.ex.sampleTime; if (t >= 0) shift = minOf(shift, t) }
            shift = if (shift == Long.MAX_VALUE || shift > 0) 0 else -shift
            val zero = LongArray(srcs.size) { i -> if (perTrackZero) maxOf(0L, srcs[i].ex.sampleTime) else 0L }
            var n = 0
            while (true) {
                val s = srcs.filter { !it.done }.minByOrNull { it.ex.sampleTime - zero[srcs.indexOf(it)] } ?: break
                if ((++n and 0xFF) == 0) {
                    if (cancelled()) throw Cancelled()
                    if (durationUs > 0) progress(((s.ex.sampleTime - zero[srcs.indexOf(s)]).toFloat() / durationUs).coerceIn(0f, 1f))
                }
                buf.clear()
                val size = s.ex.readSampleData(buf, 0)
                if (size < 0) { s.done = true; continue }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = maxOf(0L, s.ex.sampleTime + shift - zero[srcs.indexOf(s)])
                info.flags = if (s.ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                m.writeSampleData(s.muxTrack, buf, info)
                if (!s.ex.advance()) s.done = true
            }
            ok = true
        } finally {
            for (s in srcs) try { s.ex.release() } catch (_: Exception) {}
            try {
                if (started) muxer?.stop()
            } catch (e: Exception) {
                if (ok) throw IOException(tr("Muxer failed: ${e.message}", "Birleştirme başarısız: ${e.message}"), e) // don't hide the first error
            } finally {
                try { muxer?.release() } catch (_: Exception) {}
            }
        }
    }
}
