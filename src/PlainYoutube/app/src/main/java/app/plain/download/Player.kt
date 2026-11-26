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
        try {
            while (!closed && !cancelled()) {
                if (transient) throw IOException("Not enough free storage (keeps 3 GB free)")
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
        if (meta.complete) return // replay: no network at all
        thread = Thread({
            try {
                while (!closed && fillStep()) {}
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
        if (transient && !meta.complete) Store.cache.remove(meta.kind, meta.key)
    }

    fun persist() = synchronized(lock) { Store.cache.save(meta) }

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
        error?.let { throw IOException(it.message ?: "Network error", it) }
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
    val mime get() = if (meta.ext == "webm") "audio/webm" else "audio/mp4"

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
        val u = url ?: throw IOException("No stream address")
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
            if (code == 416) { synchronized(lock) { if (meta.total <= 0) meta.total = start }; return true }
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
                        raf.seek(pos); raf.write(buf, 0, n)
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
            if (e is NoWifi || e is Expired) throw e
            // Plain network errors are retried for as long as the player is open (some ISP caches reset most TLS
            // handshakes; one segment needed 17 s of retries in the PC test). Only no-Wi-Fi / refused end it.
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
        val from = range?.get(0) ?: 0L
        val to = (range?.get(1)?.takeIf { it >= 0 } ?: (total - 1)).coerceAtMost(total - 1)
        if (from > to) { Http.head(out, 416, "Range Not Satisfiable", mime, 0, "bytes */$total"); return }
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
                if (v.size.toLong() != meta.total) throw IOException("YouTube changed the segments")
                vSegs = v; aSegs = a
                return true
            }
            if (code !in 200..299) throw IOException("HTTP $code")
            val tmp = File(dir, "tmp_" + (if (audio) "a" else "v") + idx)
            var len = 0L
            con.inputStream.use { inp -> tmp.outputStream().use { o -> len = inp.copyTo(o, 64 shl 10) } }
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
            if (e is NoWifi || e is Expired) throw e
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

    /** "bytes=a-b" / "bytes=a-" -> [a, b or -1]; null when absent or not a simple range. */
    fun range(h: String?): LongArray? {
        val m = Regex("bytes=(\\d+)-(\\d*)").find(h ?: return null) ?: return null
        return longArrayOf(m.groupValues[1].toLong(), m.groupValues[2].toLongOrNull() ?: -1)
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
 * Cached streams in public storage. A complete entry is remuxed / joined (no re-encoding) into
 *   Music/PlainDownload/Cached/<Channel>/[<Album>/]<Title>.m4a (.ogg)   or   Movies/PlainDownload/Cached/<Channel>/<Title>.mp4
 * through MediaStore (so LocalMusic shows it), and the private partial copy is deleted. "Save" then only moves the
 * file to the permanent <Channel>/ folder (RELATIVE_PATH update, no copy). The app owns these files, so the cache
 * limits, Remove and Clear delete them without prompts; a refused delete (e.g. after a reinstall) is collected for
 * a system trash request. Runs off the main thread.
 */
object CachePublisher {
    class Result(val saved: Boolean, val message: String)

    private fun kindOf(m: CacheMeta) = if (m.kind == CacheKind.MUSIC) Kind.AUDIO else Kind.VIDEO
    private fun fmtOf(m: CacheMeta) = if (m.ext == "webm") AudioFmt.OPUS else AudioFmt.M4A

    /** Complete private entry -> public Cached/ file. True when published (or already was). */
    @Synchronized fun publish(ctx: Context, m: CacheMeta): Boolean {
        if (m.published) return true
        if (!m.complete) return false
        val cr = ctx.contentResolver
        val dir = Store.cache.entryDir(m.kind, m.key)
        val kind = kindOf(m)
        val fmt = fmtOf(m)
        val rel = Naming.relativePath(kind, Naming.channel(m.channel), m.album, cached = true)
        val ext = if (kind == Kind.AUDIO) fmt.ext else "mp4"
        val mime = if (kind == Kind.AUDIO) fmt.mime else "video/mp4"
        val name = Naming.unique(Naming.title(m.title), ext, Saver.takenNames(cr, kind, rel))
        val tmpV = File(Store.partsDir, "pub_${m.key}_video.ts")
        val tmpA = File(Store.partsDir, "pub_${m.key}_audio.aac")
        val tmpOut = File(Store.partsDir, "pub_${m.key}.$ext")
        var uri: Uri? = null
        try {
            // build the file in app storage (no FUSE), then one copy in 1 MB blocks into MediaStore
            val src = if (kind == Kind.AUDIO) {
                val plan = SavePlan.audio(fmt, Store.originalAudio)
                if (plan.mode == SavePlan.Mode.COPY) File(dir, "data.bin") // YouTube's original .m4a, as-is
                else { Mux.audio(File(dir, "data.bin"), tmpOut, fmt == AudioFmt.OPUS, { false }); tmpOut }
            } else {
                val hasA = File(dir, "a_0.aac").exists()
                tmpV.outputStream().use { o -> for (i in 0 until m.total) File(dir, "v_$i.ts").inputStream().use { x -> x.copyTo(o, 1 shl 20) } }
                if (hasA) tmpA.outputStream().use { o -> for (i in 0 until m.total) o.write(Id3.strip(File(dir, "a_$i.aac").readBytes())) }
                Mux.mergeTs(tmpV, if (hasA) tmpA else null, tmpOut) { false }
                tmpOut
            }
            val u = Saver.create(cr, kind, rel, name, mime, m.title)
            uri = u
            // music: tags + cover art in the file (Plain Music reads art only from the files)
            val tags = if (kind == Kind.AUDIO) TagSet(m.title, Naming.channel(m.channel), m.album, Art.fetch(ctx, m.videoId, null, Store.artQuality)) else null
            Art.saveTagged(cr, u, src, ext == "ogg", tags, { false }) {}
            Saver.publish(cr, u)
        } catch (e: Throwable) {
            uri?.let { Saver.discard(cr, it) }
            return false
        } finally {
            tmpV.delete(); tmpA.delete(); tmpOut.delete()
        }
        val uri2 = uri!!
        m.publicUri = uri2.toString()
        m.relPath = rel
        m.fileName = Saver.displayName(cr, uri2) ?: name
        m.size = try { cr.openFileDescriptor(uri2, "r")?.use { it.statSize } ?: m.size } catch (_: Exception) { m.size }
        Store.cache.save(m)
        dir.listFiles()?.forEach { if (it.name != "meta.json") it.deleteRecursively() } // the private copy goes
        return true
    }

    /** Publish every complete private entry (startup migration, after the player closes). */
    fun publishAll(ctx: Context) {
        for (k in CacheKind.values()) for (m in Store.cache.list(k)) if (m.complete && !m.published) publish(ctx, m)
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

    /**
     * Save: a complete entry moves from Cached/ to the permanent folder (published first if needed) and leaves the
     * cache; an incomplete one goes to the normal download queue.
     */
    fun save(ctx: Context, m: CacheMeta): Result {
        val kind = kindOf(m)
        if (!m.complete) {
            Store.add(Job(
                id = Store.newId(), videoId = m.videoId, title = m.title, channel = "", durationSec = m.durationSec, kind = kind,
                audioFmt = fmtOf(m), audioQ = AudioQ.HIGH, height = if (kind == Kind.VIDEO) m.quality else 0, codec = VCodec.AVC,
                album = m.album,
            ))
            DownloadService.start(ctx)
            return Result(false, "Not fully cached yet (${(m.fraction * 100).toInt()}%): queued as a normal download")
        }
        if (!publish(ctx, m)) return Result(false, "Couldn't write the file")
        val cr = ctx.contentResolver
        val uri = Uri.parse(m.publicUri)
        val channel = Naming.channel(m.channel)
        val rel = Naming.relativePath(kind, channel, m.album)
        val ext = (m.fileName ?: "").substringAfterLast('.', if (kind == Kind.AUDIO) fmtOf(m).ext else "mp4")
        val name = Naming.unique(Naming.title(m.title), ext, Saver.takenNames(cr, kind, rel))
        try {
            val n = cr.update(uri, ContentValues().apply {
                put(MediaStore.MediaColumns.RELATIVE_PATH, rel); put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            }, null, null)
            if (n < 1) return Result(false, "Couldn't move the file (it may have been deleted)")
        } catch (e: Exception) {
            return Result(false, "Couldn't move the file: ${e.message}")
        }
        val shown = Saver.displayName(cr, uri) ?: name
        Store.add(Job(
            id = Store.newId(), videoId = m.videoId, title = m.title, channel = channel, durationSec = m.durationSec, kind = kind,
            audioFmt = fmtOf(m), audioQ = AudioQ.HIGH, height = if (kind == Kind.VIDEO) m.quality else 0, codec = VCodec.AVC,
            state = State.DONE, uri = uri.toString(), fileName = shown, relPath = rel, finished = System.currentTimeMillis(), album = m.album,
        ))
        Store.cache.forget(m.kind, m.key) // the file lives on as a normal download
        return Result(true, "Saved to $rel$shown")
    }
}


/** Cache sources built without a player (the download service's "Cache" jobs). */
object Sources {
    fun ua(info: VideoInfo) = (Clients.byKey(info.client) ?: Clients.VISIONOS).userAgent

    /** Listen = audio stream into the Music cache (itag 140, else 251). */
    fun music(ctx: Context, info: VideoInfo, title: String, channel: String, album: String?): ProgressiveSource {
        val f = Modes.listenFormat(info.formats) ?: throw YtError("No audio stream offered", false)
        val key = "${info.id}_${f.itag}"
        val meta = Store.cache.get(CacheKind.MUSIC, key)?.takeIf { !it.published && (it.total == f.contentLength || f.contentLength <= 0) }
            ?: CacheMeta(CacheKind.MUSIC, info.id, f.itag, title, channel, info.durationSec, total = maxOf(0L, f.contentLength),
                ext = if (f.isOpus) "webm" else "m4a").also { Store.cache.forget(CacheKind.MUSIC, key) }
        if (meta.album == null) meta.album = album
        val skip = HashSet<String>()
        var client = info.client
        val refresh = {
            skip += client
            val i2 = InnerTube.info(info.id, Net.network(ctx, Store.wifiOnly), skip, Clients.PLAY_ORDER)
            client = i2.client
            val nf = i2.formats.firstOrNull { it.itag == f.itag } ?: throw YtError("Stream no longer offered", false)
            nf.url to ua(i2)
        }
        return ProgressiveSource(ctx, meta, f.url, ua(info), refresh)
    }

    /** Watch = one HLS quality (best H.264 up to [height]) into the Video cache. */
    fun video(ctx: Context, calls: Calls, info0: VideoInfo, title: String, channel: String, height: Int): HlsSource {
        fun load(inf0: VideoInfo): Triple<Hls.Variant, Pair<String, String?>, String> {
            var inf = inf0
            if (inf.hls == null) inf = InnerTube.info(inf0.id, Net.network(ctx, Store.wifiOnly), order = Clients.HLS_ORDER)
            val url = inf.hls ?: throw YtError("No video stream for this video", false)
            val ua = ua(inf)
            val master = Hls.parseMaster(String(WebApi.get(ctx, calls, calls.gen, url, ua), Charsets.UTF_8), url)
            val v = Hls.pick(Hls.choices(master), height) ?: throw YtError("No H.264 video stream", false)
            val vText = String(WebApi.get(ctx, calls, calls.gen, v.uri, ua), Charsets.UTF_8)
            val aText = v.audio?.let { master.audio[it] }?.let { String(WebApi.get(ctx, calls, calls.gen, it, ua), Charsets.UTF_8) }
            return Triple(v, vText to aText, ua)
        }
        val (v, texts, ua) = load(info0)
        val vSegs = Hls.parseMedia(texts.first, v.uri)
        val aSegs = texts.second?.let { Hls.parseMedia(it, v.uri) }
        val key = "${info0.id}_${v.height}"
        val meta = Store.cache.get(CacheKind.VIDEO, key)?.takeIf { !it.published && it.total == vSegs.size.toLong() }
            ?: CacheMeta(CacheKind.VIDEO, info0.id, v.height, title, channel, info0.durationSec, total = vSegs.size.toLong())
                .also { Store.cache.forget(CacheKind.VIDEO, key) }
        meta.codecs = v.codecs; meta.bandwidth = v.bandwidth; meta.resolution = "${v.width}x${v.height}"
        val dir = Store.cache.entryDir(CacheKind.VIDEO, key).apply { mkdirs() }
        HlsSource.segsFile(dir, false).writeText(texts.first)
        texts.second?.let { HlsSource.segsFile(dir, true).writeText(it) }
        val refresh = {
            val i2 = InnerTube.info(info0.id, Net.network(ctx, Store.wifiOnly), order = Clients.HLS_ORDER)
            val (v2, t2, _) = load(i2)
            Hls.parseMedia(t2.first, v2.uri) to t2.second?.let { Hls.parseMedia(it, v2.uri) }
        }
        return HlsSource(ctx, meta, vSegs, aSegs, ua, refresh)
    }
}
