package app.plain.download

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Cover art embedded in the audio files we write (Plain Music has no internet and reads art only from the files).
 * Source: the video thumbnail, centre square (YouTube Music tracks are square artwork inside the 16:9 frame), or a
 * square YouTube Music cover when one is known. Size from ⋮ → Cover art (None / Low 300 px / High 720 px).
 */
object Art {
    /** JPEG bytes for [videoId] at [q], or null (no art wanted, no network, too slow, not found). */
    fun fetch(ctx: Context, videoId: String, coverUrl: String?, q: ArtQuality, deadlineMs: Long = 10_000): ByteArray? {
        if (q == ArtQuality.NONE) return null
        val end = System.currentTimeMillis() + deadlineMs
        val net = try { Net.network(ctx, Store.wifiOnly) } catch (_: Exception) { return null }
        val urls = listOfNotNull(coverUrl?.let { q.coverUrl(it) }) + q.thumbUrls(videoId)
        for (u in urls) {
            if (System.currentTimeMillis() > end) return null
            val bytes = try { get(net, u, end) } catch (_: Exception) { null } ?: continue
            val bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            if (bm.width < 200 && u.contains("i.ytimg.com")) { bm.recycle(); continue } // a placeholder
            return encode(bm, q)
        }
        return null
    }

    private fun get(net: android.net.Network?, url: String, end: Long): ByteArray? {
        val c = Net.open(net, url)
        val left = (end - System.currentTimeMillis()).coerceAtLeast(1000).toInt()
        c.connectTimeout = minOf(left, 5000); c.readTimeout = minOf(left, 5000)
        try {
            if (c.responseCode !in 200..299) return null
            return c.inputStream.use { Net.readAll(it, 4 shl 20) }
        } finally { c.disconnect() }
    }

    /** Centre square (letterbox removed), scaled to the quality's size, JPEG. */
    fun encode(src: Bitmap, q: ArtQuality): ByteArray {
        val (x, y, side) = ArtQuality.square(src.width, src.height)
        val sq = Bitmap.createBitmap(src, x, y, side, side)
        val target = minOf(side, q.px)
        val scaled = if (target == side) sq else Bitmap.createScaledBitmap(sq, target, target, true)
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, q.jpegQ, out)
        if (scaled !== sq) scaled.recycle()
        if (sq !== src) sq.recycle()
        src.recycle()
        return out.toByteArray()
    }

    fun isOgg(name: String?) = name?.lowercase()?.endsWith(".ogg") == true || name?.lowercase()?.endsWith(".opus") == true

    /**
     * Write [src] to [out] with the tags (M4A: moov/udta/meta/ilst; Ogg Opus: OpusTags). Throws when the layout isn't
     * supported; callers then fall back to a plain copy.
     */
    fun tagInto(src: File, ogg: Boolean, out: OutputStream, tags: TagSet) {
        FileInputStream(src).use { fi -> if (ogg) OggTags.write(fi.channel, out, tags) else Mp4Tags.write(fi.channel, out, tags) }
    }

    /**
     * Save [src] into the MediaStore file [uri]: tagged in one streaming pass, or a plain copy when tagging fails
     * (the file is reopened truncated). [progress] 0..1.
     */
    fun saveTagged(cr: ContentResolver, uri: Uri, src: File, ogg: Boolean, tags: TagSet?, cancelled: () -> Boolean, progress: (Float) -> Unit) {
        if (tags != null) try {
            cr.openFileDescriptor(uri, "rwt")!!.use { pfd ->
                FileOutputStream(pfd.fileDescriptor).use { fo -> tagInto(src, ogg, Counting(fo, src.length(), cancelled, progress), tags) }
            }
            return
        } catch (e: Cancelled) { throw e } catch (_: Exception) { /* fall back to the untagged copy */ }
        cr.openFileDescriptor(uri, "rwt")!!.use { Saver.copyInto(src, it.fileDescriptor, cancelled, progress) }
    }

    private class Counting(val o: OutputStream, val total: Long, val cancelled: () -> Boolean, val progress: (Float) -> Unit) : OutputStream() {
        var n = 0L
        override fun write(b: Int) { o.write(b); n++ }
        override fun write(b: ByteArray, off: Int, len: Int) {
            o.write(b, off, len); n += len
            if (cancelled()) throw Cancelled()
            progress((n.toFloat() / total.coerceAtLeast(1)).coerceAtMost(1f))
        }
        override fun flush() = o.flush()
    }

    /** Does this saved file (MediaStore uri) already carry cover art? null = can't read it (not ours / gone). */
    fun hasArt(cr: ContentResolver, uri: Uri, name: String?): Boolean? = try {
        cr.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).use { fi -> if (isOgg(name)) OggTags.hasPicture(fi.channel) else Mp4Tags.hasCover(fi.channel) }
        }
    } catch (_: Exception) { null }

    /**
     * Backfill one file in place: read it from MediaStore, write a tagged copy into app storage, then put it back
     * through the "rwt" descriptor. Throws SecurityException when the file isn't ours.
     */
    fun backfill(ctx: Context, uri: Uri, name: String?, tags: TagSet, tmpDir: File, cancelled: () -> Boolean) {
        val cr = ctx.contentResolver
        val ogg = isOgg(name)
        val src = File(tmpDir, "art_in_${System.nanoTime()}")
        val out = File(tmpDir, "art_out_${System.nanoTime()}")
        try {
            cr.openFileDescriptor(uri, "r")!!.use { pfd -> FileInputStream(pfd.fileDescriptor).use { i -> src.outputStream().use { o -> i.copyTo(o, 1 shl 20) } } }
            if (cancelled()) throw Cancelled()
            out.outputStream().use { o -> tagInto(src, ogg, o, tags) }
            if (cancelled()) throw Cancelled()
            // From here the song itself is rewritten: never stop halfway (no cancel check), and if the write fails,
            // put the untouched original back so the file is never left cut short.
            try {
                cr.openFileDescriptor(uri, "rwt")!!.use { Saver.copyInto(out, it.fileDescriptor, { false }) }
                val written = cr.openFileDescriptor(uri, "r")!!.use { it.statSize }
                if (written >= 0 && written != out.length()) throw IOException("Wrote $written of ${out.length()} bytes")
            } catch (e: Exception) {
                try { cr.openFileDescriptor(uri, "rwt")!!.use { Saver.copyInto(src, it.fileDescriptor, { false }) } } catch (_: Exception) {}
                throw e
            }
        } finally { src.delete(); out.delete() }
    }

    /** Saved / public-cached audio files of ours without cover art (checked by reading each file's header). */
    class Missing(val uri: String, val videoId: String, val title: String, val channel: String, val album: String?, val fileName: String?)

    fun scanMissing(ctx: Context): Pair<List<Missing>, Int> {
        val cr = ctx.contentResolver
        val out = ArrayList<Missing>()
        var unreadable = 0
        val seen = HashSet<String>()
        val jobs = Store.snapshot().filter { it.state == State.DONE && it.kind == Kind.AUDIO && it.uri != null && it.media == Media.OK && !it.art }
        for (j in jobs) {
            if (!seen.add(j.uri!!)) continue
            when (hasArt(cr, Uri.parse(j.uri), j.fileName)) {
                false -> out += Missing(j.uri!!, j.videoId, j.title, j.channel, j.album, j.fileName)
                null -> unreadable++
                else -> {}
            }
        }
        for (m in Store.cache.list(CacheKind.MUSIC).filter { it.published }) {
            val u = m.publicUri ?: continue
            if (!seen.add(u)) continue
            when (hasArt(cr, Uri.parse(u), m.fileName)) {
                false -> out += Missing(u, m.videoId, m.title, Naming.channel(m.channel), m.album, m.fileName)
                null -> unreadable++
                else -> {}
            }
        }
        return out to unreadable
    }
}
