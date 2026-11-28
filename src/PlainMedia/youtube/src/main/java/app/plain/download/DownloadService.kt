package app.plain.download

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import app.plain.lang.PlainLang
import app.plain.lang.tr

/** What Plain Youtube's Application did at process start; Plain Media's Application calls it. */
object YoutubeApp {
    fun init(app: Application) {
        PlainLang.init(app)
        Store.init(app)
        Account.init(app)
        Resume.init(app)
        PlayService.channel(app)
        // (3.3: completed caches are saved when the Youtube tab opens, not at every process start — MainActivity)
        DownloadService.channel(app)
    }
}

/**
 * Foreground service (type dataSync) that exists only while there is work. A pipeline:
 *  - download workers fetch streams: audio jobs up to ⋮ "parallel audio downloads" at once (default 3), a job with
 *    video alone ([Sched]); then each job is handed to
 *  - the saver, which converts/copies one job at a time into MediaStore,
 * so downloads continue while earlier jobs convert. The service (and its wake locks) stays up until every worker is
 * idle and nothing is queued. No timers, no polling, no restart after the process dies.
 */
class DownloadService : Service() {
    companion object {
        const val CHANNEL = "downloads"
        private const val NOTIF_ID = 1
        private const val NOTIF_DONE = 2
        private const val ACTION_RUN = "app.plain.download.RUN"
        private const val ACTION_CANCEL = "app.plain.download.CANCEL"

        @Volatile var running = false
            private set

        /**
         * Starts the worker. False when Android refuses a foreground service right now (API 31+: the app went to the
         * background between the tap and this call, e.g. "Save (download)" finishing on a worker thread): the jobs
         * stay queued and start the next time the app is opened, instead of crashing that thread.
         */
        fun start(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).setAction(ACTION_RUN)); true
        } catch (_: IllegalStateException) { false } catch (_: SecurityException) { false }

        /** Creates the channel, or renames it after a language change. */
        fun channel(c: Context) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Downloads", "İndirmeler"), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                }
            )
        }
    }

    /** Everything the saver needs from a finished download. */
    private class SaveTask(val id: Long, val info: VideoInfo, val plan: Plan, val parts: List<File>, val out: SavePlan.Out, val outFmt: AudioFmt, val jpeg: ByteArray?)

    private val main = Handler(Looper.getMainLooper())
    private val lock = Object()
    private val downloads = HashSet<Long>() // jobs in their download stage (one thread each)
    private var saver: Thread? = null
    private val saveQueue = ArrayDeque<SaveTask>()
    @Volatile private var savingId = -1L
    private var lastNotif = 0L
    @Volatile private var finishedOk = 0
    @Volatile private var finishedBad = 0
    @Volatile private var lastDoneTitle = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Store.init(this)
        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getLongExtra("id", -1)
            if (id >= 0) Store.cancel(id)
            val batch = intent.getLongExtra("batch", 0)
            if (batch != 0L) Store.cancelBatch(batch)
            if (synchronized(lock) { downloads.isEmpty() && saver == null }) stopNow()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIF_ID, progressNotif(null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            // refused (Android 15's daily dataSync time used up, or not allowed from the background): nothing starts,
            // the queue waits for the next open of the app (no crash, no half-started worker without a notification)
            if (synchronized(lock) { downloads.isEmpty() && saver == null }) { running = false; stopSelf() }
            return START_NOT_STICKY
        }
        running = true
        schedule()
        return START_NOT_STICKY
    }

    /**
     * Start as many queued jobs as [Sched] allows (audio in parallel, video alone), oldest first. A job that doesn't
     * fit in the free storage waits until a download or save finishes (they call this again); when nothing is
     * running any more and it still doesn't fit, it fails with "Not enough free storage".
     */
    private fun schedule() {
        synchronized(lock) {
            while (true) {
                val all = Store.snapshot()
                val running = all.filter { it.id in downloads }
                val next = Store.queuedInOrder().firstOrNull() ?: break
                if (!Sched.canStart(running, next, Store.parallelAudio)) break
                if (!spaceForNext(next, running, all)) {
                    if (downloads.isEmpty() && saver == null) {
                        Store.update(next.id) { it.state = State.FAILED; it.error = tr("Not enough free storage", "Yeterli boş alan yok") }
                        continue
                    }
                    break
                }
                if (!Store.claim(next.id)) continue
                downloads += next.id
                Thread({
                    try { runDownload(next.id) } finally {
                        synchronized(lock) { downloads -= next.id }
                        schedule()
                        main.post { maybeStop() }
                    }
                }, "download").start()
            }
        }
    }

    private fun enqueueSave(t: SaveTask) {
        synchronized(lock) {
            saveQueue.addLast(t)
            if (saver?.isAlive == true) return
            saver = Thread({
                val pm = getSystemService(PowerManager::class.java)
                val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlainDownload:save").apply { setReferenceCounted(false); acquire(3 * 3600_000L) }
                try {
                    while (true) {
                        val next = synchronized(lock) { saveQueue.pollFirst() ?: run { saver = null; lock.notifyAll(); null } } ?: break
                        runSave(next)
                    }
                } finally { try { wake.release() } catch (_: Exception) {} }
                schedule()
                main.post { maybeStop() }
            }, "save").apply { start() }
        }
    }

    /** Free storage must cover jobs waiting to convert, what running downloads still need, and the next job. */
    private fun spaceForNext(j: Job, running: List<Job>, all: List<Job>): Boolean {
        val waiting = all.filter { it.state == State.RUNNING && it.phase != Phase.DOWNLOAD }
            .sumOf { w -> Store.partsDir.listFiles()?.filter { it.name.startsWith("${w.id}_") }?.sumOf { it.length() } ?: 0L }
        return Pipeline.canDownload(Store.partsDir.usableSpace, j.total, waiting + Sched.pendingBytes(running))
    }

    private fun maybeStop() {
        if (!synchronized(lock) { downloads.isEmpty() && saver == null && saveQueue.isEmpty() }) return
        if (Store.queuedInOrder().isNotEmpty()) {
            schedule()
            if (!synchronized(lock) { downloads.isEmpty() && saver == null }) return
            if (Store.queuedInOrder().isNotEmpty()) return // (cannot happen: schedule starts or fails each job)
        }
        stopNow()
    }

    private fun stopNow() {
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (finishedOk + finishedBad > 0) summaryNotif()
        stopSelf()
    }

    /** Android 15+: dataSync services get ~6 h per day. Pause everything; the user can tap Retry later. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        for (j in Store.snapshot()) if (j.active) Store.update(j.id) { it.state = State.FAILED; it.error = tr("Stopped by Android (time limit). Tap Retry.", "Android durdurdu (süre sınırı). Yeniden dene'ye dokunun.") }
        stopNow()
    }

    override fun onDestroy() {
        running = false
        // Process stays alive only if the workers still run; make them notice.
        val ids = synchronized(lock) { downloads.toList() } + listOf(savingId)
        for (id in ids) if (id >= 0) Store.update(id) { if (it.state == State.RUNNING) { it.state = State.FAILED; it.error = tr("Interrupted", "Yarıda kesildi") } }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------------------------------
    // stage 1: download

    /** One job's download stage (already claimed by [schedule]). */
    private fun runDownload(id: Long) {
        val job = Store.get(id)?.copy() ?: return
        val pm = getSystemService(PowerManager::class.java)
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlainDownload:download").apply { setReferenceCounted(false); acquire(3 * 3600_000L) }
        var wifiLock: WifiManager.WifiLock? = null
        try {
            val wifiOnly = Store.wifiOnly
            val net = Net.network(this, wifiOnly)
            if (wifiOnly && Build.VERSION.SDK_INT < 34) { // the high-perf Wi-Fi lock is a no-op from Android 14 on
                @Suppress("DEPRECATION")
                wifiLock = getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PlainDownload:download").apply { setReferenceCounted(false); acquire() }
            }
            if (job.cache) { runCacheJob(id, job, net); finishedOk++; lastDoneTitle = Store.get(id)?.title.orEmpty(); return }
            if (job.art) { runArtJob(id, job); finishedOk++; return }
            // one copy per video: saved already (a download, or a cache that completed) -> nothing to fetch
            Saved.find(this, job.videoId, job.kind)?.let { have -> alreadySaved(id, have); finishedOk++; return }
            val skip = HashSet<String>()
            var membersTries = 0
            while (true) {
                val info = InnerTube.info(job.videoId, net, skip)
                Store.update(id) {
                    if (info.members) it.members = true
                    if (it.title.isBlank()) it.title = info.title
                    if (it.channel.isBlank()) it.channel = Naming.channel(info.author)
                    if (it.durationSec <= 0) it.durationSec = info.durationSec
                }
                earlyAdopt(id, info)?.let { have -> alreadySaved(id, have); finishedOk++; return }
                try {
                    enqueueSave(fetch(id, info))
                    break
                } catch (e: Expired) {
                    // 403 from googlevideo: this client's URLs are refused (or expired). Ask another client.
                    skip += info.client
                    if (info.members && ++membersTries > 1) throw IOException(tr("YouTube refused the members-only streams (HTTP 403)", "YouTube üyelere özel akışları reddetti (HTTP 403)"))
                    if (skip.size >= Clients.ORDER.size) throw IOException(tr("YouTube refused the streams (HTTP 403) for every client", "YouTube akışları her istemci için reddetti (HTTP 403)"))
                }
            }
        } catch (e: Exception) {
            failed(id, e)
        } finally {
            try { wake.release() } catch (_: Exception) {}
            try { wifiLock?.release() } catch (_: Exception) {}
        }
    }

    /**
     * 3.10: the file is in its folder already (same name and length), e.g. from before a reinstall, which forgets what
     * was downloaded: adopted before any stream is fetched. A YouTube Music track without an album is checked when
     * saving instead (its folder needs the album lookup).
     */
    private fun earlyAdopt(id: Long, info: VideoInfo): Saved.File? {
        val j = Store.get(id) ?: return null
        if (j.kind == Kind.AUDIO && j.album == null && info.author.trim().endsWith(" - Topic")) return null
        val rel = Naming.relativePath(j.kind, j.channel.ifBlank { Naming.channel(info.author) }, j.album)
        val exts = if (j.kind == Kind.AUDIO) listOf("m4a", "ogg") else listOf("mp4")
        return exts.firstNotNullOfOrNull { Saved.adopt(contentResolver, j.videoId, j.kind, rel, j.title.ifBlank { info.title }, it, info.durationSec.toInt()) }
    }

    /** The job's video is saved already: the job finishes pointing at that file (no second copy, no "Title (2)"). */
    private fun alreadySaved(id: Long, f: Saved.File) {
        deleteParts(id)
        Store.update(id) {
            it.state = State.DONE; it.uri = f.uri; it.fileName = f.name; it.relPath = f.relPath
            it.finished = System.currentTimeMillis(); it.error = null; it.phase = Phase.DOWNLOAD; it.media = Media.OK
        }
        lastDoneTitle = f.name.orEmpty()
    }

    private fun failed(id: Long, e: Exception) {
        if (e is Cancelled || Store.get(id)?.state == State.CANCELED) { if (Store.get(id)?.state == State.CANCELED) deleteParts(id); return }
        finishedBad++
        val msg = when (e) {
            is YtError, is NoWifi -> e.message
            is java.net.UnknownHostException -> tr("No internet connection", "İnternet bağlantısı yok")
            is java.net.SocketTimeoutException -> tr("Network timed out", "Ağ zaman aşımına uğradı")
            else -> e.message ?: e.javaClass.simpleName
        }
        Store.update(id) { it.state = State.FAILED; it.error = msg; it.phase = Phase.DOWNLOAD }
        // Wi-Fi gone: don't walk a long (bulk) queue failing one job after another; pause it all at once.
        if (e is NoWifi) Store.failQueued(e.message ?: tr("Not on Wi-Fi", "Wi-Fi bağlı değil"))
    }

    /** Download the streams into parts; returns the work for the saver. */
    private fun fetch(id: Long, info: VideoInfo): SaveTask {
        val job = Store.get(id)?.copy() ?: throw Cancelled()
        val cancelled = { Store.isCancelled(id) }
        var outFmt = job.audioFmt
        val plan: Plan = when (job.kind) {
            Kind.AUDIO -> {
                val (p, f) = Pick.planAudio(info.formats, job.audioFmt, job.audioQ) ?: throw YtError(tr("No audio stream offered", "Ses akışı sunulmuyor"), true)
                outFmt = f
                p
            }
            Kind.VIDEO -> Pick.planVideo(info.formats, job.height, job.codec)
                ?: Pick.closest(Pick.videoChoices(info.formats, Build.VERSION.SDK_INT), job.height)?.let { Pick.planVideo(info.formats, it.p, it.codec) }
                ?: throw YtError(tr("No MP4-compatible video stream offered", "MP4 uyumlu video akışı sunulmuyor"), true)
        }
        if (outFmt != job.audioFmt) Store.update(id) { it.audioFmt = outFmt }

        val client = Clients.byKey(info.client) ?: Clients.ORDER[0]
        val fetcher = Fetcher(this, Store.wifiOnly, client.userAgent, cancelled)
        val streams = plan.streams
        val parts = streams.map { File(Store.partsDir, "${id}_${it.itag}_${maxOf(0L, it.contentLength)}.part") }
        val sizes = LongArray(streams.size) { maxOf(0L, streams[it].estBytes) }
        for ((i, f) in streams.withIndex()) {
            val before = (0 until i).sumOf { k -> parts[k].length() }
            sizes[i] = fetcher.fetch(f.url, parts[i], f.contentLength) { pos, tot ->
                if (tot > 0) sizes[i] = tot
                progress(id, before + pos, sizes.sum())
            }
        }
        val out = if (job.kind == Kind.AUDIO) SavePlan.audio(outFmt, Store.originalAudio) else SavePlan.video(plan.single != null)
        Store.update(id) { it.done = it.total; it.error = null; it.phase = Phase.WAIT_SAVE; it.saveConverts = out.converts; it.savePct = 0; it.etaSec = -1 }
        notifyProgress(id, force = true)
        // cover art is fetched here, in the (parallel) download stage, not by the single saver (≤ 10 s, else none)
        val jpeg = if (job.kind == Kind.AUDIO) Art.fetch(this, job.videoId, null, Store.artQuality) else null
        return SaveTask(id, info, plan, parts, out, outFmt, jpeg)
    }

    // ---------------------------------------------------------------------------------------------------------
    // stage 2: convert / copy into MediaStore (temp file in app storage, then one 1 MB-block copy)

    private fun runSave(t: SaveTask) {
        val id = t.id
        if (Store.isCancelled(id)) { if (Store.get(id)?.state == State.CANCELED) deleteParts(id); return }
        savingId = id
        val started = System.currentTimeMillis()
        val eta = Eta()
        var lastUi = 0L
        fun stage(phase: Phase, f: Float) {
            val now = System.currentTimeMillis()
            if (now - lastUi < 1000 && f < 1f) return // row + notification at most once a second
            lastUi = now
            val left = if (phase == Phase.CONVERT) eta.update(f.toDouble(), now - started) ?: -1 else -1
            Store.update(id, persist = false) { it.phase = phase; it.savePct = (f * 100).toInt().coerceIn(0, 100); it.etaSec = left }
            notifyProgress(id, force = true)
        }
        val cancelled = { Store.isCancelled(id) }
        val tmp = File(Store.partsDir, "${id}_out.${t.out.ext}")
        var uri: android.net.Uri? = null
        val cr = contentResolver
        try {
            val j = Store.get(id)?.copy() ?: throw Cancelled()
            val channel = j.channel.ifBlank { Naming.channel(t.info.author) }
            var album = j.album
            if (j.kind == Kind.AUDIO && album == null && t.info.author.trim().endsWith(" - Topic")) {
                // YouTube Music track: its album becomes the folder below the channel
                album = Albums.pick(WebApi.remixAlbum(this, Calls(), 0, j.videoId), true, null)
                if (album != null) Store.update(id) { it.album = album }
            }
            val rel = Naming.relativePath(j.kind, channel, album)
            // saved meanwhile (a cache completed while this downloaded), or before 3.3 with only the mark: keep that one
            (Saved.find(this, j.videoId, j.kind) ?: Saved.adopt(cr, j.videoId, j.kind, rel, j.title, t.out.ext, t.info.durationSec.toInt()))?.let { have ->
                alreadySaved(id, have); finishedOk++; return
            }
            val name = Naming.unique(Naming.title(j.title), t.out.ext, Saver.takenNames(cr, j.kind, rel))
            stage(if (t.out.converts) Phase.CONVERT else Phase.COPY, 0f)
            // convert into a plain file first (no FUSE); 90 % of the bar is conversion, 10 % the final copy
            val src = when (t.out.mode) {
                SavePlan.Mode.COPY -> t.parts[0]
                SavePlan.Mode.MERGE -> { Mux.merge(t.parts[0], t.parts[1], tmp, cancelled) { stage(Phase.CONVERT, it * 0.9f) }; tmp }
                else -> { Mux.audio(t.parts[0], tmp, t.out.mode == SavePlan.Mode.REMUX_OGG, cancelled) { stage(Phase.CONVERT, it * 0.9f) }; tmp }
            }
            // audio: title / artist / album tags + cover art, written during the copy (time included in "saved in")
            val tags = if (j.kind == Kind.AUDIO) TagSet(j.title, channel, album, t.jpeg) else null
            val u = Saver.create(cr, j.kind, rel, name, t.out.mime, j.title)
            uri = u
            Art.saveTagged(cr, u, src, t.out.ext == "ogg", tags, cancelled) { f ->
                if (t.out.converts) stage(Phase.CONVERT, 0.9f + f * 0.1f) else stage(Phase.COPY, f)
            }
            if (cancelled()) throw Cancelled()
            Saver.publish(cr, u)
            val shown = Saver.displayName(cr, u) ?: name
            deleteParts(id)
            val took = System.currentTimeMillis() - started
            Store.update(id) {
                it.state = State.DONE; it.uri = u.toString(); it.fileName = shown; it.relPath = rel
                it.finished = System.currentTimeMillis(); it.error = null
                it.saveMs = took; it.savedConverted = t.out.converts; it.phase = Phase.DOWNLOAD
            }
            finishedOk++
            lastDoneTitle = shown
            // the saved file replaces any partial cache of the same thing (no second copy kept)
            val ck = if (j.kind == Kind.AUDIO) CacheKind.MUSIC else CacheKind.VIDEO
            try { Store.cache.list(ck).filter { it.videoId == j.videoId && !it.published }.forEach { Store.cache.forget(ck, it.key) } } catch (_: Exception) {}
        } catch (e: Exception) {
            uri?.let { Saver.discard(cr, it) }
            failed(id, if (e is Cancelled) e else IOException(tr("Saving failed: ", "Kaydetme başarısız: ") + (e.message ?: e.javaClass.simpleName), e))
        } finally {
            tmp.delete()
            savingId = -1
            refreshNotif()
        }
    }

    /**
     * "Cache N": fill the Music cache (Listen items) or the Video cache (HLS, best up to the job's height) like the
     * player would, without playing; the finished entry is saved like a download (Music|Movies/PlainDownload/<Channel>/).
     */
    private fun runCacheJob(id: Long, job: Job, net: android.net.Network?) {
        val info = InnerTube.info(job.videoId, net, order = Clients.PLAY_ORDER)
        val title = job.title.ifBlank { info.title }
        val channel = Naming.channel(info.author)
        Store.update(id) { if (info.members) it.members = true; if (it.title.isBlank()) it.title = info.title; if (it.channel.isBlank()) it.channel = channel; if (it.durationSec <= 0) it.durationSec = info.durationSec }
        var listen = job.kind == Kind.AUDIO
        if (!listen && Store.mode(job.videoId) == null) {
            // no choice made for this video: the same default as the player (music -> Listen)
            val calls = Calls()
            val m = if (info.author.trim().endsWith(" - Topic")) PlayMode.LISTEN else {
                val (type, cat) = WebApi.musicMeta(this, calls, calls.gen, job.videoId)
                Modes.decide(null, type, cat, info.author, false)
            }
            listen = m == PlayMode.LISTEN
        }
        // saved already (as what this would cache): nothing to fetch
        Saved.find(this, job.videoId, if (listen) Kind.AUDIO else Kind.VIDEO)?.let { have -> alreadySaved(id, have); return }
        val calls = Calls()
        val album = if (listen) Albums.pick(if (info.author.trim().endsWith(" - Topic")) WebApi.remixAlbum(this, calls, calls.gen, job.videoId) else null,
            info.author.trim().endsWith(" - Topic"), job.album) else null
        val src: CachedSource = if (listen) Sources.music(this, info, title, channel, album)
            else Sources.video(this, calls, info, title, channel, if (job.height > 0) job.height else Store.watchP)
        src.maxTries = 60
        try {
            src.fillBlocking({ Store.isCancelled(id) }) { f -> progress(id, (f * 1000).toLong(), 1000) }
        } finally { src.close() }
        if (Store.isCancelled(id)) throw Cancelled()
        val m = src.meta
        if (!m.complete) throw IOException(tr("Caching stopped at ${(m.fraction * 100).toInt()}%", "Önbelleğe alma %${(m.fraction * 100).toInt()} noktasında durdu"))
        val started = System.currentTimeMillis()
        Store.update(id) { it.done = it.total; it.error = null; it.phase = Phase.COPY }
        notifyProgress(id, force = true)
        CachePublisher.publish(this, m, id) ?: throw IOException(tr("Couldn't write the file", "Dosya yazılamadı"))
        Store.update(id) {
            it.saveMs = System.currentTimeMillis() - started; it.savedConverted = !(m.kind == CacheKind.MUSIC && m.ext == "m4a" && Store.originalAudio)
        }
    }

    /**
     * "Add missing cover art": fetch the art (Wi-Fi only applies) and rewrite the saved file in place (read → tagged
     * copy in app storage → back through the "rwt" descriptor). Files that aren't ours fail as "skipped".
     */
    private fun runArtJob(id: Long, job: Job) {
        val uri = android.net.Uri.parse(job.uri ?: throw IOException(tr("No file", "Dosya yok")))
        val started = System.currentTimeMillis()
        val jpeg = Art.fetch(this, job.videoId, null, Store.artQuality.takeIf { it != ArtQuality.NONE } ?: ArtQuality.LOW, 20_000)
            ?: throw IOException(tr("Couldn't get the cover art", "Kapak resmi alınamadı"))
        Store.update(id, persist = false) { it.phase = Phase.COPY; it.savePct = 50 }
        notifyProgress(id, force = true)
        try {
            Art.backfill(this, uri, job.fileName, TagSet(job.title, Naming.channel(job.channel), job.album, jpeg), Store.partsDir) { Store.isCancelled(id) }
        } catch (e: SecurityException) {
            throw IOException(tr("Skipped: the file isn't Plain Youtube's (e.g. after a reinstall)", "Atlandı: dosya Plain Youtube'a ait değil (ör. yeniden yüklemeden sonra)"))
        }
        Store.update(id) { it.state = State.DONE; it.finished = System.currentTimeMillis(); it.saveMs = it.finished - started; it.savedConverted = false; it.phase = Phase.DOWNLOAD }
    }

    private fun deleteParts(id: Long) = Store.deleteParts(id)

    private val lastProgress = HashMap<Long, Long>()
    private fun progress(id: Long, done: Long, total: Long) {
        val now = System.currentTimeMillis()
        synchronized(lastProgress) {
            if (now - (lastProgress[id] ?: 0L) < 300) return // UI and notification don't need more
            lastProgress[id] = now
        }
        Store.update(id, persist = false) { it.done = done; it.total = total }
        notifyProgress(id, force = false)
    }

    // ---- notifications ----

    private fun notifyProgress(id: Long, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotif < 1000) return
        lastNotif = now
        refreshNotif(id)
    }

    /** One notification for both stages: the most interesting job + "1 downloading · 1 converting · 3 queued". */
    private fun refreshNotif(preferred: Long = -1) {
        val all = Store.snapshot()
        val dl = synchronized(lock) { downloads.toList() }
        val j = all.firstOrNull { it.id == preferred && it.state == State.RUNNING }
            ?: all.firstOrNull { it.id == savingId } ?: all.firstOrNull { it.id in dl }
        if (j == null && dl.isEmpty() && saver == null) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, progressNotif(j, Pipeline.summary(all), all))
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun progressNotif(j: Job?, summary: String = "", all: List<Job> = emptyList()): Notification {
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.yt_ic_notif)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(openApp())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        if (j == null) return b.setContentTitle(tr("Starting download…", "İndirme başlatılıyor…")).setProgress(0, 0, true).build()
        if (j.batch != 0L) {
            // bulk action: the batch's progress ("37/120 · 3 downloading · 1 converting") with the current track
            val st = BatchStats.of(all.filter { it.batch == j.batch })
            b.setContentTitle(j.batchTitle.ifBlank { tr("Bulk download", "Toplu indirme") })
            b.setContentText(st.text + " · " + j.title)
            b.setProgress(st.total.coerceAtLeast(1), st.done, false)
            if (summary.isNotEmpty()) b.setSubText(summary)
            val cancelAll = PendingIntent.getService(
                this, (j.batch % Int.MAX_VALUE).toInt(), Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra("batch", j.batch),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, tr("Cancel all", "Tümünü iptal et"), cancelAll).build())
            return b.build()
        }
        b.setContentTitle(j.title.ifBlank { tr("Downloading", "İndiriliyor") })
        b.setContentText(Pipeline.status(j))
        if (summary.isNotEmpty()) b.setSubText(summary)
        val pct = when {
            j.phase == Phase.CONVERT || j.phase == Phase.COPY -> j.savePct
            j.phase == Phase.DOWNLOAD && j.total > 0 -> (j.done * 100 / j.total).toInt().coerceIn(0, 100)
            else -> -1
        }
        if (pct >= 0) b.setProgress(100, pct, false) else b.setProgress(0, 0, true)
        val cancel = PendingIntent.getService(
            this, j.id.toInt(), Intent(this, DownloadService::class.java).setAction(ACTION_CANCEL).putExtra("id", j.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        b.addAction(Notification.Action.Builder(null, tr("Cancel", "İptal"), cancel).build())
        return b.build()
    }

    private fun summaryNotif() {
        val title = when {
            finishedBad == 0 && finishedOk == 1 -> tr("Downloaded: $lastDoneTitle", "İndirildi: $lastDoneTitle")
            finishedBad == 0 -> tr("$finishedOk downloads finished", "$finishedOk indirme tamamlandı")
            finishedOk == 0 -> if (finishedBad == 1) tr("Download failed", "İndirme başarısız") else tr("$finishedBad downloads failed", "$finishedBad indirme başarısız")
            else -> tr("$finishedOk finished, $finishedBad failed", "$finishedOk tamamlandı, $finishedBad başarısız")
        }
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.yt_ic_notif)
            .setContentTitle(title)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_DONE, n)
        finishedOk = 0; finishedBad = 0
    }
}
