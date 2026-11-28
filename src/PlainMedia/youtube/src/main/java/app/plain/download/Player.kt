package app.plain.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import app.plain.lang.tr

/**
 * What the local proxy serves: a cached stream that fills itself from YouTube while the player is open.
 * Network happens on one background thread (the "filler"), one request at a time, bound to Wi-Fi when
 * "Wi-Fi only" is on. [close] stops it; partial caches stay for next time.
 */
abstract class CachedSource(protected val ctx: Context, val meta: CacheMeta) {
    protected val lock = Object()
    @Volatile var closed = false
        protected set
    @Volatile var error: Exception? = null
        protected set
    /** Low storage: don't read ahead, and drop the entry when the player closes. */
    @Volatile var transient = false
        protected set
    protected val dir: File = Store.cache.entryDir(meta.kind, meta.key).apply { mkdirs() }
    protected val calls = Calls()
    private var thread: Thread? = null
    private var sinceTrim = 0L
    private var sinceSave = 0L

    abstract val entryPath: String // what MediaPlayer opens, e.g. "a" or "m.m3u8"
    /** Consecutive network failures tolerated (the player: unlimited while open; a background cache job: 60). */
    @Volatile var maxTries = Int.MAX_VALUE

    /**
     * Cache job (download service, no player): fill this entry to the end on the calling thread, front to back.
     * Stops on [cancelled], on no Wi-Fi / refused links, or when storage would drop below 3 GB.
     */
    fun fillBlocking(cancelled: () -> Boolean, progress: (Float) -> Unit) {
        meta.lastUsed = System.currentTimeMillis()
        Store.cache.save(meta)
        markInUse()
        try {
            while (!closed && !cancelled()) {
                if (transient) throw IOException(tr("Not enough free storage (keeps 3 GB free)", "Yeterli boş alan yok (3 GB boş tutulur)"))
                if (removed()) throw IOException(tr("The cached copy was removed", "Önbellekteki kopya kaldırıldı"))
                if (!fillStep()) break
                progress(meta.fraction)
            }
        } finally { persist() }
    }
    abstract fun serve(path: String, range: LongArray?, out: OutputStream)
    protected abstract fun fillStep(): Boolean // false = nothing left to do

    fun start() {
        meta.lastUsed = System.currentTimeMillis()
        Store.cache.save(meta)
        markInUse()
        if (meta.complete) return // replay: no network at all
        thread = Thread({
            try {
                while (!closed && !removed() && fillStep()) {}
            } catch (e: Exception) {
                if (!closed) synchronized(lock) { error = e; lock.notifyAll() }
            } finally {
                persist()
            }
        }, "cache-filler").apply { priority = Thread.NORM_PRIORITY - 1; start() }
    }

    open fun close() {
        closed = true
        calls.cancelAll()
        synchronized(lock) { lock.notifyAll() }
        thread?.join(1500)
        persist()
        if (inUse) { inUse = false; Store.cache.inUse(meta.kind, meta.key, false) }
        if (transient && !meta.complete) Store.cache.remove(meta.kind, meta.key)
    }

    /** While open, the LRU trim (of this or a parallel cache job) and "Clear" leave this entry alone. */
    private var inUse = false
    private fun markInUse() { if (!inUse) { inUse = true; Store.cache.inUse(meta.kind, meta.key, true) } }

    /** The entry's folder was deleted underneath (Remove on its Cached row): stop filling, don't bring it back. */
    protected fun removed() = !dir.exists()

    fun persist() = synchronized(lock) { if (!removed()) Store.cache.save(meta) }

    /** Bookkeeping after [n] new bytes: keep both caches inside their limits and 3 GB free. */
    protected fun wrote(n: Long) {
        sinceTrim += n; sinceSave += n
        if (sinceSave > (4 shl 20)) { sinceSave = 0; persist() }
        if (sinceTrim > (32 shl 20) || dir.usableSpace < CacheLru.MIN_FREE) {
            sinceTrim = 0
            Store.cache.trim(meta.kind, Store.cap(meta.kind), meta.key)
            if (dir.usableSpace < CacheLru.MIN_FREE) transient = true
        }
    }

    /** Waits on [lock] for the filler (bounded), failing fast when it stopped with an error. */
    protected fun awaitLocked(ms: Long) {
        error?.let { throw IOException(it.message ?: tr("Network error", "Ağ hatası"), it) }
        if (closed) throw Cancelled()
        lock.wait(ms)
    }

    // ---- network helpers for subclasses ----

    protected fun open(url: String, ua: String, range: String?): HttpURLConnection {
        val g = calls.gen
        val con = Net.open(Net.network(ctx, Store.wifiOnly), url)
        calls.add(g, con)
        con.setRequestProperty("User-Agent", ua)
        con.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) con.setRequestProperty("Range", range)
        return con
    }

    protected fun done(con: HttpURLConnection) = calls.remove(con)
}

/** MUSIC cache: one progressive audio stream (data.bin), served with HTTP ranges, filled in 4 MB requests. */
class ProgressiveSource(
    ctx: Context,
    meta: CacheMeta,
    private var url: String?,
    private var ua: String,
    private val refresh: (() -> Pair<String, String>)?, // fresh (url, user agent) after a 403
) : CachedSource(ctx, meta) {
    override val entryPath = "a"
    private val file = File(dir, "data.bin")
    private val raf = RandomAccessFile(file, "rw")
    @Volatile private var target = 0L
    private var tries = 0
    private var refreshes = 0
    // VIDEO kind here = a members-only video's muxed 360p MP4 (itag 18): the signed-in TV client gives no HLS
    val mime get() = if (meta.kind == CacheKind.VIDEO) "video/mp4" else if (meta.ext == "webm") "audio/webm" else "audio/mp4"

    override fun close() {
        super.close()
        try { raf.close() } catch (_: Exception) {}
    }

    override fun fillStep(): Boolean {
        val start: Long
        val end: Long
        synchronized(lock) {
            if (transient && !waiting) { lock.wait(500); return true }
            start = meta.done.firstGap(target, meta.total) ?: return false
            end = if (meta.total > 0) minOf(meta.done.nextStart(start, meta.total), start + CHUNK) else start + CHUNK
        }
        val u = url ?: throw IOException(tr("No stream address", "Akış adresi yok"))
        var con: HttpURLConnection? = null
        try {
            con = open(u, ua, "bytes=$start-${end - 1}")
            val code = con.responseCode
            if (code == 403 || code == 410) {
                val r = refresh ?: throw Expired()
                if (++refreshes > 3) throw Expired()
                val (nu, nua) = r(); url = nu; ua = nua
                return true
            }
            if (code == 416) {
                // past the end of a stream of unknown size: that's its size. Anything else (a 416 for a range inside
                // the stream, or at 0) is an error and goes through the retry delay below instead of an instant loop.
                if (start > 0 && synchronized(lock) { if (meta.total <= 0) { meta.total = start; lock.notifyAll(); true } else false }) return true
                throw IOException("HTTP 416")
            }
            if (code != 206 && !(code == 200 && start == 0L)) throw IOException("HTTP $code")
            val t = Text.totalFromContentRange(con.getHeaderField("Content-Range")).takeIf { it > 0 } ?: if (code == 200) con.contentLengthLong else -1
            if (t > 0 && meta.total != t) synchronized(lock) { meta.total = t; lock.notifyAll() }
            val buf = ByteArray(64 shl 10)
            var pos = start
            con.inputStream.use { inp ->
                while (!closed) {
                    val n = inp.read(buf)
                    if (n < 0) break
                    synchronized(lock) {
                        try { raf.seek(pos); raf.write(buf, 0, n) } catch (e: IOException) { throw StorageFull(e) }
                        meta.done.add(pos, pos + n)
                        meta.size = meta.done.covered()
                        lock.notifyAll()
                    }
                    pos += n
                    wrote(n.toLong())
                    tries = 0
                    val tg = target
                    if (tg < start || tg > pos + (1 shl 20)) break // the player jumped elsewhere: go there
                }
            }
            done(con)
        } catch (e: IOException) {
            con?.disconnect()
            if (closed || e is Cancelled) return false
            if (e is NoWifi || e is Expired || e is StorageFull) throw e
            // Plain network errors are retried for as long as the player is open (some ISP caches reset most TLS
            // handshakes; one segment needed 17 s of retries in the PC test). Only no-Wi-Fi / refused / a full disk end it.
            if (++tries > maxTries) throw e
            Thread.sleep(minOf(200L * tries, 2000L))
        }
        return true
    }

    @Volatile private var waiting = false

    override fun serve(path: String, range: LongArray?, out: OutputStream) {
        val total = synchronized(lock) {
            while (meta.total <= 0) { target = 0; waiting = true; awaitLocked(500) }
            waiting = false
            meta.total
        }
        // "bytes=a-b", "bytes=a-" and "bytes=-n" (the last n bytes); past the end = 416
        val span = if (range != null) Http.span(range, total) else longArrayOf(0, total - 1)
        if (span == null) { Http.head(out, 416, "Range Not Satisfiable", mime, 0, "bytes */$total"); return }
        val from = span[0]
        val to = span[1]
        Http.head(out, if (range != null) 206 else 200, if (range != null) "Partial Content" else "OK", mime, to - from + 1,
            if (range != null) "bytes $from-$to/$total" else null)
        val reader = RandomAccessFile(file, "r")
        reader.use {
            val buf = ByteArray(64 shl 10)
            var pos = from
            while (pos <= to) {
                val avail = synchronized(lock) {
                    while (!meta.done.contains(pos)) { target = pos; waiting = true; awaitLocked(500) }
                    waiting = false
                    meta.done.runEnd(pos)
                }
                val n = minOf(buf.size.toLong(), avail - pos, to + 1 - pos).toInt()
                reader.seek(pos)
                reader.readFully(buf, 0, n)
                out.write(buf, 0, n)
                pos += n
            }
            out.flush()
        }
    }

    companion object { const val CHUNK = 4L shl 20 }
}

/**
 * VIDEO cache: one HLS quality. Video segments v_N.ts and audio segments a_N.aac are fetched in order from the
 * segment the player asks for, then onwards (and wrapping to the start), so the whole video ends up cached.
 */
class HlsSource(
    ctx: Context,
    meta: CacheMeta,
    private var vSegs: List<Hls.Seg>,
    private var aSegs: List<Hls.Seg>?,
    private val ua: String,
    private val refresh: (() -> Pair<List<Hls.Seg>, List<Hls.Seg>?>)?,
) : CachedSource(ctx, meta) {
    override val entryPath = "m.m3u8"
    @Volatile private var target = 0L
    @Volatile private var waiting = false
    private var tries = 0
    private var refreshes = 0

    private fun file(audio: Boolean, i: Long) = File(dir, (if (audio) "a_" else "v_") + i + if (audio) ".aac" else ".ts")

    override fun fillStep(): Boolean {
        val audio: Boolean
        val idx: Long
        synchronized(lock) {
            if (transient && !waiting) { lock.wait(500); return true }
            val n = meta.total
            if (n <= 0) return false // no segments (an empty media playlist): nothing to fetch, and no "% n" below
            val gv = meta.done.firstGap(target, n)
            val ga = if (aSegs != null) meta.aDone.firstGap(target, n) else null
            if (gv == null && ga == null) return false
            fun dist(x: Long) = (x - target + n) % n
            audio = gv == null || ga != null && dist(ga) < dist(gv)
            idx = if (audio) ga!! else gv!!
        }
        val segs = (if (audio) aSegs else vSegs) ?: return false
        val url = segs.getOrNull(idx.toInt())?.uri ?: throw IOException("Segment $idx missing")
        var con: HttpURLConnection? = null
        try {
            con = open(url, ua, null)
            val code = con.responseCode
            if (code == 403 || code == 410 || code == 404) {
                val r = refresh ?: throw Expired()
                if (++refreshes > 3) throw Expired()
                val (v, a) = r()
                if (v.size.toLong() != meta.total) throw IOException(tr("YouTube changed the segments", "YouTube parçaları değiştirdi"))
                vSegs = v; aSegs = a
                return true
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val tmp = File(dir, "tmp_" + (if (audio) "a" else "v") + idx)
            var len = 0L
            con.inputStream.use { inp ->
                val fo = try { tmp.outputStream() } catch (e: IOException) { if (removed()) { con?.disconnect(); return false }; throw StorageFull(e) }
                WriteGuard(fo).use { o -> len = inp.copyTo(o, 64 shl 10) }
            }
            done(con)
            if (closed) { tmp.delete(); return false }
            val f = file(audio, idx)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            synchronized(lock) {
                (if (audio) meta.aDone else meta.done).add(idx, idx + 1)
                meta.size += len
                lock.notifyAll()
            }
            wrote(len)
            tries = 0
        } catch (e: IOException) {
            con?.disconnect()
            if (closed || e is Cancelled) return false
            if (e is NoWifi || e is Expired || e is StorageFull) throw e
            if (++tries > maxTries) throw e
            Thread.sleep(minOf(200L * tries, 2000L))
        }
        return true
    }

    override fun serve(path: String, range: LongArray?, out: OutputStream) {
        when {
            path == "m.m3u8" -> text(out, Hls.localMaster(meta.bandwidth, meta.codecs, meta.resolution, aSegs != null))
            path == "v.m3u8" -> text(out, Hls.localMedia(vSegs, "v/"))
            path == "a.m3u8" && aSegs != null -> text(out, Hls.localMedia(aSegs!!, "a/"))
            path.startsWith("v/") || path.startsWith("a/") -> {
                val audio = path[0] == 'a'
                val i = path.substring(2).toLongOrNull() ?: throw IOException("bad path")
                if (i < 0 || i >= meta.total) { Http.head(out, 404, "Not Found", "text/plain", 0, null); return }
                synchronized(lock) {
                    while (!(if (audio) meta.aDone else meta.done).contains(i)) { target = i; waiting = true; awaitLocked(500) }
                    waiting = false
                }
                val f = file(audio, i)
                Http.head(out, 200, "OK", if (audio) "audio/aac" else "video/mp2t", f.length(), null)
                f.inputStream().use { it.copyTo(out, 64 shl 10) }
                out.flush()
            }
            else -> Http.head(out, 404, "Not Found", "text/plain", 0, null)
        }
    }

    private fun text(out: OutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        Http.head(out, 200, "OK", "application/vnd.apple.mpegurl", b.size.toLong(), null)
        out.write(b); out.flush()
    }

    companion object {
        fun segsFile(dir: File, audio: Boolean) = File(dir, if (audio) "a.src.m3u8" else "v.src.m3u8")
    }
}

/** Minimal HTTP/1.1 for the local proxy. */
object Http {
    fun head(out: OutputStream, code: Int, msg: String, type: String, length: Long, contentRange: String?) {
        val sb = StringBuilder("HTTP/1.1 $code $msg\r\nContent-Type: $type\r\nContent-Length: $length\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (contentRange != null) sb.append("Content-Range: $contentRange\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private val RANGE = Regex("^bytes\\s*=\\s*(\\d*)\\s*-\\s*(\\d*)\\s*(?:,.*)?$", RegexOption.IGNORE_CASE)

    /**
     * "bytes=a-b" / "bytes=a-" -> [a, b or -1]; "bytes=-n" (the last n bytes) -> [-1, n]. Only the first range of a
     * list is used. Null when absent or invalid (b < a, "bytes=-", numbers too big): the whole file is served then,
     * as RFC 7233 asks for a header that can't be used.
     */
    fun range(h: String?): LongArray? {
        val m = RANGE.find(h?.trim() ?: return null) ?: return null
        val a = m.groupValues[1]; val b = m.groupValues[2]
        if (a.isEmpty()) return b.toLongOrNull()?.let { longArrayOf(-1, it) }
        val from = a.toLongOrNull() ?: return null
        if (b.isEmpty()) return longArrayOf(from, -1)
        val to = b.toLongOrNull() ?: return null
        return if (to < from) null else longArrayOf(from, to)
    }

    /** A parsed [range] against a file of [total] bytes: [from, to] inclusive, or null when it can't be satisfied (416). */
    fun span(r: LongArray, total: Long): LongArray? {
        if (total <= 0) return null
        if (r[0] < 0) { val n = r[1]; return if (n <= 0) null else longArrayOf(maxOf(0L, total - n), total - 1) }
        if (r[0] >= total) return null
        return longArrayOf(r[0], if (r[1] < 0) total - 1 else minOf(r[1], total - 1))
    }

    fun readHead(inp: InputStream): List<String> {
        val lines = ArrayList<String>()
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) break
            if (c == '\n'.code) {
                val l = sb.toString().trimEnd('\r'); sb.setLength(0)
                if (l.isEmpty()) break
                lines += l
                if (lines.size > 100) break
            } else if (sb.length < 8192) sb.append(c.toChar())
        }
        return lines
    }
}

/** HTTP server on 127.0.0.1 (random port) that MediaPlayer reads from; lives only while the player is open. */
class StreamProxy(private val source: CachedSource) {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val sockets = Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var closed = false

    val url: String get() = "http://127.0.0.1:${server.localPort}/${source.entryPath}"

    fun start(): StreamProxy {
        Thread({
            while (!closed) {
                val s = try { server.accept() } catch (_: IOException) { break }
                sockets += s
                Thread({ handle(s) }, "proxy-conn").start()
            }
        }, "proxy").start()
        return this
    }

    private fun handle(s: Socket) {
        try {
            s.use {
                val head = Http.readHead(BufferedInputStream(s.getInputStream()))
                val path = head.firstOrNull()?.split(' ')?.getOrNull(1)?.trimStart('/')?.substringBefore('?') ?: return
                val range = Http.range(head.firstOrNull { it.startsWith("Range:", true) }?.substringAfter(':')?.trim())
                source.serve(path, range, s.getOutputStream().buffered(64 shl 10))
            }
        } catch (_: Exception) {
            // MediaPlayer closes connections on seeks; the filler's own errors are reported through source.error
        } finally {
            sockets -= s
        }
    }

    fun close() {
        closed = true
        try { server.close() } catch (_: Exception) {}
        synchronized(sockets) { sockets.forEach { try { it.close() } catch (_: Exception) {} }; sockets.clear() }
        source.close()
    }
}

/**
 * 3.3: a cache that completes is SAVED, like a download: remuxed / joined (no re-encoding) into
 *   Music/PlainDownload/<Channel>/[<Album>/]<Title>.m4a (.ogg)   or   Movies/PlainDownload/<Channel>/<Title>.mp4
 * through MediaStore, listed with the downloads (a finished job), and the private copy and its cache entry go. If
 * that video is already saved as that kind, the cached copy is just dropped: one file per video (see [Saved]).
 * Entries published by older versions under …/PlainDownload/Cached/ move into the channel folder (same file) or,
 * when they duplicate a download, are deleted. Runs off the main thread.
 */
object CachePublisher {
    class Result(val saved: Boolean, val message: String)

    private fun kindOf(m: CacheMeta) = if (m.kind == CacheKind.MUSIC) Kind.AUDIO else Kind.VIDEO
    private fun fmtOf(m: CacheMeta) = if (m.ext == "webm") AudioFmt.OPUS else AudioFmt.M4A

    private fun done(j: Job, f: Saved.File, started: Long = 0) {
        j.state = State.DONE; j.uri = f.uri; j.fileName = f.name; j.relPath = f.relPath; j.finished = System.currentTimeMillis()
        j.error = null; j.phase = Phase.DOWNLOAD; j.media = Media.OK
        if (started > 0) j.saveMs = System.currentTimeMillis() - started
    }

    /** A duplicate of an already saved file: the cached copy goes, [jobId] (if any) points at the saved one. */
    private fun dropDuplicate(ctx: Context, m: CacheMeta, have: Saved.File, jobId: Long): Saved.File {
        if (m.published) { deletePublic(ctx, m); Store.dropJobsWithUri(m.publicUri!!) }
        Store.cache.forget(m.kind, m.key)
        if (jobId != 0L) Store.update(jobId) { done(it, have) }
        return have
    }

    /**
     * Complete entry -> saved file (see above). [jobId]: the "Cache" job to finish with it (else a finished job is
     * added to the downloads list). Null when incomplete or the write failed (then the entry stays).
     */
    @Synchronized fun publish(ctx: Context, m: CacheMeta, jobId: Long = 0): Saved.File? {
        if (!m.complete) return null
        val kind = kindOf(m)
        val cr = ctx.contentResolver
        val started = System.currentTimeMillis()
        // already saved (a download, or this video cached before): this copy is a duplicate
        Saved.find(ctx, m.videoId, kind)?.takeIf { it.uri != m.publicUri }?.let { return dropDuplicate(ctx, m, it, jobId) }
        val fmt = fmtOf(m)
        val channel = Naming.channel(m.channel)
        val rel = Naming.relativePath(kind, channel, m.album)
        val ext = if (m.published) (m.fileName ?: "").substringAfterLast('.', if (kind == Kind.AUDIO) fmt.ext else "mp4")
            else if (kind == Kind.AUDIO) fmt.ext else "mp4"
        Saved.adopt(cr, m.videoId, kind, rel, m.title, ext)?.let { return dropDuplicate(ctx, m, it, jobId) }
        val name = Naming.unique(Naming.title(m.title), ext, Saver.takenNames(cr, kind, rel))
        val uri: Uri
        if (m.published) {
            // published by an older version under Cached/: the same file moves into the channel folder
            uri = Uri.parse(m.publicUri)
            val n = try {
                cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null)
            } catch (_: Exception) { -1 }
            if (n == 0) { Store.cache.forget(m.kind, m.key); return null } // deleted elsewhere meanwhile
            if (n < 0) return null
        } else {
            val dir = Store.cache.entryDir(m.kind, m.key)
            val mime = if (kind == Kind.AUDIO) fmt.mime else "video/mp4"
            val tmpV = File(Store.partsDir, "pub_${m.key}_video.ts")
            val tmpA = File(Store.partsDir, "pub_${m.key}_audio.aac")
            val tmpOut = File(Store.partsDir, "pub_${m.key}.$ext")
            var u0: Uri? = null
            try {
                // build the file in app storage (no FUSE), then one copy in 1 MB blocks into MediaStore
                val src = if (kind == Kind.AUDIO) {
                    val plan = SavePlan.audio(fmt, Store.originalAudio)
                    if (plan.mode == SavePlan.Mode.COPY) File(dir, "data.bin") // YouTube's original .m4a, as-is
                    else { Mux.audio(File(dir, "data.bin"), tmpOut, fmt == AudioFmt.OPUS, { false }); tmpOut }
                } else if (m.ext == "mp4") {
                    File(dir, "data.bin") // members-only progressive MP4 (itag 18): as-is
                } else {
                    val hasA = File(dir, "a_0.aac").exists()
                    tmpV.outputStream().use { o -> for (i in 0 until m.total) File(dir, "v_$i.ts").inputStream().use { x -> x.copyTo(o, 1 shl 20) } }
                    if (hasA) tmpA.outputStream().use { o -> for (i in 0 until m.total) o.write(Id3.strip(File(dir, "a_$i.aac").readBytes())) }
                    Mux.mergeTs(tmpV, if (hasA) tmpA else null, tmpOut) { false }
                    tmpOut
                }
                val u = Saver.create(cr, kind, rel, name, mime, m.title)
                u0 = u
                // music: tags + cover art in the file (Plain Music reads art only from the files)
                val tags = if (kind == Kind.AUDIO) TagSet(m.title, channel, m.album, Art.fetch(ctx, m.videoId, null, Store.artQuality)) else null
                Art.saveTagged(cr, u, src, ext == "ogg", tags, { false }) {}
                Saver.publish(cr, u)
            } catch (e: Throwable) {
                u0?.let { Saver.discard(cr, it) }
                return null
            } finally {
                tmpV.delete(); tmpA.delete(); tmpOut.delete()
            }
            uri = u0!!
        }
        val f = Saved.File(uri.toString(), rel, Saver.displayName(cr, uri) ?: name, kind == Kind.AUDIO)
        Store.setSaved(m.videoId, kind, f.uri)
        if (jobId != 0L) Store.update(jobId) { done(it, f, started) }
        else if (!Store.hasJobWithUri(f.uri)) Store.add(Job(
            id = Store.newId(), videoId = m.videoId, title = m.title, channel = channel, durationSec = m.durationSec, kind = kind,
            audioFmt = fmt, audioQ = AudioQ.HIGH, height = if (kind == Kind.VIDEO) m.quality else 0, codec = VCodec.AVC,
            state = State.DONE, uri = f.uri, fileName = f.name, relPath = rel, finished = System.currentTimeMillis(), album = m.album,
        ))
        Store.cache.forget(m.kind, m.key)
        return f
    }

    /**
     * Once per Youtube tab start, off the main thread: complete entries become saved files (also the pre-3.3
     * Cached/ ones: moved, or deleted as duplicates), and stale unfinished MediaStore entries go.
     */
    fun publishAll(ctx: Context) {
        for (k in CacheKind.values()) for (m in Store.cache.list(k)) if (m.complete) try { publish(ctx, m) } catch (_: Exception) {}
        try { Saved.dropStalePending(ctx) } catch (_: Exception) {}
    }

    fun exists(ctx: Context, m: CacheMeta): Boolean {
        val u = m.publicUri ?: return false
        return try {
            ctx.contentResolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
        } catch (_: Exception) { false }
    }

    /** True when gone. Refused deletes (not owned any more) are remembered for a trash request. */
    fun deletePublic(ctx: Context, m: CacheMeta): Boolean {
        val u = m.publicUri ?: return true
        return try { ctx.contentResolver.delete(Uri.parse(u), null, null); true } catch (e: SecurityException) {
            Store.addTrashPending(u); false
        } catch (_: Exception) { true }
    }

    /** Save: complete = saved now (see [publish]); incomplete = a normal download, unless it is saved already. */
    fun save(ctx: Context, m: CacheMeta): Result {
        val kind = kindOf(m)
        Saved.find(ctx, m.videoId, kind)?.let { return Result(true, tr("Already saved: ${it.where}", "Zaten kayıtlı: ${it.where}")) }
        if (!m.complete) {
            if (Store.queued(m.videoId)) return Result(false, tr("Already in the download queue", "Zaten indirme sırasında"))
            Store.add(Job(
                id = Store.newId(), videoId = m.videoId, title = m.title, channel = "", durationSec = m.durationSec, kind = kind,
                audioFmt = fmtOf(m), audioQ = AudioQ.HIGH, height = if (kind == Kind.VIDEO) m.quality else 0, codec = VCodec.AVC,
                album = m.album,
            ))
            DownloadService.start(ctx)
            return Result(false, tr("Not fully cached yet (${(m.fraction * 100).toInt()}%): queued as a normal download", "Henüz tamamen önbellekte değil (%${(m.fraction * 100).toInt()}): normal indirme olarak sıraya alındı"))
        }
        val f = publish(ctx, m) ?: return Result(false, tr("Couldn't write the file", "Dosya yazılamadı"))
        return Result(true, tr("Saved to ${f.where}", "Kaydedildi: ${f.where}"))
    }
}


/** Cache sources built without a player (the download service's "Cache" jobs). */
object Sources {
    fun ua(info: VideoInfo) = (Clients.byKey(info.client) ?: Clients.VISIONOS).userAgent

    /** Listen = audio stream into the Music cache (itag 140, else 251). */
    fun music(ctx: Context, info: VideoInfo, title: String, channel: String, album: String?): ProgressiveSource {
        val f = Modes.listenFormat(info.formats) ?: throw YtError(tr("No audio stream offered", "Ses akışı sunulmuyor"), false)
        val key = CacheMeta.keyOf(info.id, f.itag, f.audioLang.orEmpty())
        val meta = Store.cache.get(CacheKind.MUSIC, key)?.takeIf { !it.published && (it.total == f.contentLength || f.contentLength <= 0) }
            ?: CacheMeta(CacheKind.MUSIC, info.id, f.itag, title, channel, info.durationSec, total = maxOf(0L, f.contentLength),
                ext = if (f.isOpus) "webm" else "m4a", lang = f.audioLang.orEmpty(), langOriginal = f.audioOriginal, langKnown = true)
                .also { Store.cache.forget(CacheKind.MUSIC, key) }
        if (meta.album == null) meta.album = album
        val skip = HashSet<String>()
        var client = info.client
        val refresh = {
            skip += client
            val i2 = InnerTube.info(info.id, Net.network(ctx, Store.wifiOnly), skip, Clients.PLAY_ORDER)
            client = i2.client
            val nf = i2.formats.firstOrNull { it.itag == f.itag && it.audioLang == f.audioLang } ?: throw YtError(tr("Stream no longer offered", "Akış artık sunulmuyor"), false)
            nf.url to ua(i2)
        }
        return ProgressiveSource(ctx, meta, f.url, ua(info), refresh)
    }

    /**
     * Members-only Watch: the muxed 360p MP4 (itag 18) as one progressive file in the Video cache (ext "mp4"). The
     * signed-in TV client returns no HLS master, and higher qualities are separate video/audio streams (Download
     * merges those).
     */
    fun membersVideo(ctx: Context, info: VideoInfo, title: String, channel: String): ProgressiveSource {
        val f = info.formats.firstOrNull { it.itag == 18 } ?: info.formats.filter { it.muxed && it.mime == "video/mp4" }.maxByOrNull { it.p }
            ?: throw YtError(tr("No single-file video stream for this members-only video: use Download (or Listen)", "Bu üyelere özel video için tek dosyalık video akışı yok: İndir'i (ya da Dinle'yi) kullanın"), false)
        val key = "${info.id}_m${f.itag}"
        val meta = Store.cache.get(CacheKind.VIDEO, key)?.takeIf { !it.published && (it.total == f.contentLength || f.contentLength <= 0) }
            ?: CacheMeta(CacheKind.VIDEO, info.id, f.p, title, channel, info.durationSec, total = maxOf(0L, f.contentLength), ext = "mp4")
                .also { Store.cache.forget(CacheKind.VIDEO, key) }
        meta.resolution = "${f.width}x${f.height}"
        val refresh = {
            val i2 = InnerTube.info(info.id, Net.network(ctx, Store.wifiOnly))
            val nf = i2.formats.firstOrNull { it.itag == f.itag } ?: throw YtError(tr("Stream no longer offered", "Akış artık sunulmuyor"), false)
            nf.url to ua(i2)
        }
        return ProgressiveSource(ctx, meta, f.url, ua(info), refresh)
    }

    /** Watch = one HLS quality (best H.264 up to [height]) into the Video cache. */
    fun video(ctx: Context, calls: Calls, info0: VideoInfo, title: String, channel: String, height: Int): CachedSource {
        if (info0.members && info0.hls == null) return membersVideo(ctx, info0, title, channel)
        var lang = "" // audio language of a dubbed video: always the original here
        var aBase: String? = null // the audio playlist's own address (relative segment uris resolve against it)
        fun load(inf0: VideoInfo): Triple<Hls.Variant, Pair<String, String?>, String> {
            var inf = inf0
            if (inf.hls == null) inf = InnerTube.info(inf0.id, Net.network(ctx, Store.wifiOnly), order = Clients.HLS_ORDER)
            val url = inf.hls ?: throw YtError(tr("No video stream for this video", "Bu video için video akışı yok"), false)
            val ua = ua(inf)
            val master = Hls.parseMaster(String(WebApi.get(ctx, calls, calls.gen, url, ua), Charsets.UTF_8), url)
            val v = Hls.pick(Hls.choices(master), height) ?: throw YtError(tr("No H.264 video stream", "H.264 video akışı yok"), false)
            val vText = String(WebApi.get(ctx, calls, calls.gen, v.uri, ua), Charsets.UTF_8)
            val a = v.audio?.let { master.audioFor(it, null) }
            a?.let { lang = it.second; aBase = it.first }
            val aText = a?.let { String(WebApi.get(ctx, calls, calls.gen, it.first, ua), Charsets.UTF_8) }
            return Triple(v, vText to aText, ua)
        }
        val (v, texts, ua) = load(info0)
        val vSegs = Hls.parseMedia(texts.first, v.uri)
        if (vSegs.isEmpty()) throw YtError(tr("YouTube sent an empty video playlist", "YouTube boş bir video listesi gönderdi"), false)
        val aSegs = texts.second?.let { Hls.parseMedia(it, aBase ?: v.uri) }
        val key = CacheMeta.keyOf(info0.id, v.height, lang)
        val meta = Store.cache.get(CacheKind.VIDEO, key)?.takeIf { !it.published && it.total == vSegs.size.toLong() }
            ?: CacheMeta(CacheKind.VIDEO, info0.id, v.height, title, channel, info0.durationSec, total = vSegs.size.toLong(),
                lang = lang, langOriginal = lang.isNotEmpty(), langKnown = true)
                .also { Store.cache.forget(CacheKind.VIDEO, key) }
        meta.codecs = v.codecs; meta.bandwidth = v.bandwidth; meta.resolution = "${v.width}x${v.height}"
        val dir = Store.cache.entryDir(CacheKind.VIDEO, key).apply { mkdirs() }
        HlsSource.segsFile(dir, false).writeText(texts.first)
        texts.second?.let { HlsSource.segsFile(dir, true).writeText(it) }
        val refresh = {
            val i2 = InnerTube.info(info0.id, Net.network(ctx, Store.wifiOnly), order = Clients.HLS_ORDER)
            val (v2, t2, _) = load(i2)
            Hls.parseMedia(t2.first, v2.uri) to t2.second?.let { Hls.parseMedia(it, aBase ?: v2.uri) }
        }
        return HlsSource(ctx, meta, vSegs, aSegs, ua, refresh)
    }
}
