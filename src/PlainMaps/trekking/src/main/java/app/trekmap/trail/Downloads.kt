package app.trekmap.trail

import app.plain.lang.tr
import app.plain.lang.PlainLang
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** UI-facing download state and commands. */
object Downloads {
    class Prog {
        @Volatile var done = 0L
        @Volatile var total = 0L
        @Volatile var bytes = 0L
        @Volatile var waitingWifi = false
        @Volatile var failed = 0
    }

    val prog = ConcurrentHashMap<String, Prog>()
    @Volatile var current: String? = null
    private val listeners = LinkedHashSet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun fire() { main.post { listeners.toList().forEach { it() } } }

    fun isActive(id: String) = current == id || DownloadService.instance?.isQueued(id) == true

    fun start(ctx: Context, id: String) {
        val p = Packs.get(id) ?: return
        if (!isActive(id)) {
            p.state = "queued"
            Packs.saveMeta(p)
        }
        ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra("id", id))
        fire()
    }

    fun pause(id: String) {
        Dev.event("download", "pause requested ${Packs.get(id)?.name}")
        val s = DownloadService.instance
        if (s != null) s.pause(id) else markPaused(id)
        fire()
    }

    fun markPaused(id: String) {
        Packs.get(id)?.let { it.state = "paused"; Packs.saveMeta(it) }
    }

    /** Trail mode starts: stop all downloads, remember them for later. */
    fun pauseForTrail(ctx: Context) {
        val s = DownloadService.instance ?: return
        val ids = s.activeIds()
        Dev.event("download", "paused for trail mode: $ids")
        ctx.getSharedPreferences(TrailInit.PREFS, Context.MODE_PRIVATE).edit().putString("resumeIds", ids.joinToString(",")).apply()
        s.stopAll()
    }

    fun resumeAfterTrail(ctx: Context) {
        val prefs = ctx.getSharedPreferences(TrailInit.PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString("resumeIds", "").orEmpty().split(',').filter { it.isNotEmpty() }
        prefs.edit().remove("resumeIds").apply()
        for (id in ids) if (Packs.get(id) != null) try { start(ctx, id) } catch (_: Exception) {}
    }
}

/**
 * Downloads map packs over Wi-Fi only. When Wi-Fi drops it waits (never falls back to mobile data)
 * and continues by itself when Wi-Fi returns. Already-downloaded tiles are skipped, so a pack can be
 * paused and resumed at any time.
 */
class DownloadService : Service() {
    companion object {
        @Volatile var instance: DownloadService? = null
        private const val NOTIF_ID = 2
    }

    private val queue = ArrayDeque<String>()
    @Volatile private var stopAllFlag = false
    @Volatile private var cancelId: String? = null
    @Volatile private var waiting = false
    @Volatile private var wifiNet: Network? = null
    private var worker: Thread? = null
    private val lock = ReentrantLock()
    private val wifiCond = lock.newCondition()
    private lateinit var watcher: Net.WifiWatcher
    private var wake: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    // development stats for the pack being downloaded
    private val httpCodes = ConcurrentHashMap<Int, AtomicInteger>()
    private val nRetries = AtomicInteger()
    private val nIoErrors = AtomicInteger()
    private val nWifiWaits = AtomicInteger()
    private val wifiWaitMs = AtomicLong()
    @Volatile private var curPack: Pack? = null
    @Volatile private var diskFull = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
        instance = this
        watcher = Net.WifiWatcher(this) { n ->
            Dev.event("net", "download service: Wi-Fi ${if (n != null) "available" else "lost"}")
            lock.withLock { wifiNet = n; wifiCond.signalAll() }
        }
        watcher.start()
        wake = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "trekmap:download")
            .apply { setReferenceCounted(false); acquire(6 * 3600_000L) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startFg(notif(tr("Preparing map download…", "Harita indirmeye hazırlanılıyor…"), null, 0, 0))
        val id = intent?.getStringExtra("id")
        if (id != null) {
            stopAllFlag = false
            synchronized(queue) { if (id !in queue) queue.addLast(id) } // even if it's winding down right now
        }
        ensureWorker()
        return START_NOT_STICKY
    }

    private fun startFg(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIF_ID, n)
    }

    fun isQueued(id: String) = synchronized(queue) { id in queue }
    fun activeIds(): List<String> = synchronized(queue) { listOfNotNull(Downloads.current) + queue }

    fun pause(id: String) {
        val removed = synchronized(queue) { queue.remove(id) }
        if (removed) Downloads.markPaused(id)
        if (Downloads.current == id) cancelId = id
        lock.withLock { wifiCond.signalAll() }
    }

    fun stopAll() {
        stopAllFlag = true
        val ids = synchronized(queue) { queue.toList().also { queue.clear() } }
        ids.forEach { Downloads.markPaused(it) }
        cancelId = Downloads.current
        lock.withLock { wifiCond.signalAll() }
    }

    private fun ensureWorker() {
        if (worker?.isAlive == true) return
        worker = thread(name = "map-download") { loop() }
    }

    private fun loop() {
        while (!stopAllFlag) {
            val id = synchronized(queue) { queue.removeFirstOrNull() } ?: break
            try {
                runPack(id)
            } catch (e: Exception) {
                Packs.get(id)?.let { it.state = "incomplete"; Packs.saveMeta(it) }
            } finally {
                Downloads.current = null
            }
        }
        val me = Thread.currentThread()
        main.post { finishOrContinue(me) }
    }

    private fun finishOrContinue(finished: Thread) {
        // a newer worker may already have picked up a pack queued in the meantime
        if (worker !== finished && worker?.isAlive == true) return
        val more = synchronized(queue) { queue.isNotEmpty() }
        if (more && !stopAllFlag) {
            worker = thread(name = "map-download") { loop() }
            return
        }
        Downloads.fire()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Dev.event("download", "Android dataSync time limit reached — pausing")
        // Android 15 limits dataSync services to 6 h/day and kills the app unless we stop promptly.
        // Pause everything and leave right away; the worker winds down by itself.
        stopAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        instance = null
        stopAllFlag = true
        cancelId = Downloads.current
        watcher.stop()
        lock.withLock { wifiCond.signalAll() }
        try { wake?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    // ------------------------------------------------------------------ work
    private fun runPack(id: String) {
        val pack = Packs.get(id) ?: return
        curPack = pack
        Downloads.current = id
        cancelId = null
        landNote = null
        val db = Packs.db(id)
        val pr = Downloads.Prog().also { Downloads.prog[id] = it }
        // corridor packs fetch only tiles near their trail; grids depend on zoom only, so cache per zoom
        val segs = Packs.corridor(pack)
        val grids = HashMap<Int, TileGrid?>()
        fun gridFor(z: Int) = grids.getOrPut(z) { Packs.grid(pack, segs, z) }
        pr.total = pack.fetchLayers.sumOf { l ->
            pack.zRange(l).sumOf { z -> gridFor(z)?.count() ?: Geo.tileCount(pack.bbox, z, z) }
        }
        pr.done = db.compileStatement("SELECT COUNT(*) FROM tiles WHERE l<>${Layer.LAND.ordinal}").use { it.simpleQueryForLong() }
        pack.state = "downloading"
        Packs.saveMeta(pack)
        Downloads.fire()
        httpCodes.clear(); nRetries.set(0); nIoErrors.set(0); nWifiWaits.set(0); wifiWaitMs.set(0)
        val t0 = System.currentTimeMillis()
        val startDone = pr.done
        Dev.event(
            "download",
            "start “${pack.name}” · ${pack.layers} z${pack.minZ}-${pack.maxZ} · " +
                "${if (pack.corridorM > 0) "corridor ${pack.corridorM.toInt()} m" else "whole box"} · " +
                "satellite ${if (pack.satMs > 0) Season.month(pack.satMs) + " (wayback)" else "current"} · ${pr.total} tiles, ${pr.done} already stored · " +
                "bbox ${pack.bbox.encode()} · Wi-Fi ${if (wifiNet != null) "up" else "down"}",
        )

        val done = AtomicLong(pr.done)
        val bytes = AtomicLong(0)
        val failed = AtomicInteger(0)
        diskFull = false
        val cancelled = { stopAllFlag || cancelId == id || diskFull }
        val ticker = object : Runnable {
            override fun run() {
                publish(pack, pr, done, bytes, failed)
                main.postDelayed(this, 1500)
            }
        }
        main.post(ticker)
        val insert = db.compileStatement("INSERT OR REPLACE INTO tiles(l,z,x,y,d) VALUES(?,?,?,?,?)")
        try {
            for (layer in pack.fetchLayers) {
                if (cancelled()) break
                val pool = Executors.newFixedThreadPool(layer.threads)
                val sem = Semaphore(layer.threads * 2)
                try {
                    for (z in pack.zRange(layer)) {
                        if (cancelled()) break
                        var curX = Int.MIN_VALUE
                        var have: Set<Int> = emptySet()
                        fun visit(x: Int, y: Int) {
                            if (x != curX) { have = existing(db, layer, z, x); curX = x }
                            if (y in have) return
                            sem.acquire()
                            pool.execute {
                                try {
                                    fetchOne(layer, z, x, y, insert, done, bytes, failed, cancelled)
                                } catch (e: android.database.sqlite.SQLiteFullException) {
                                    diskFull = true
                                } catch (e: Throwable) {
                                    failed.incrementAndGet()
                                    Dev.event("download", "tile error ${layer.name} $z/$x/$y: $e")
                                } finally { sem.release() }
                            }
                        }
                        val g = gridFor(z)
                        if (g != null) {
                            var i = g.bits.nextSetBit(0)
                            while (i >= 0 && !cancelled()) {
                                visit(g.x0 + i / g.h, g.y0 + i % g.h)
                                i = g.bits.nextSetBit(i + 1)
                            }
                        } else {
                            val x0 = Geo.tileX(pack.bbox.w, z); val x1 = Geo.tileX(pack.bbox.e, z)
                            val y0 = Geo.tileY(pack.bbox.n, z); val y1 = Geo.tileY(pack.bbox.s, z)
                            outer@ for (x in x0..x1) for (y in y0..y1) {
                                if (cancelled()) break@outer
                                visit(x, y)
                            }
                        }
                    }
                } finally {
                    pool.shutdown()
                    pool.awaitTermination(1, TimeUnit.DAYS)
                }
            }
            if (pack.land == 1 && !cancelled()) {
                try {
                    buildLand(pack, insert, pr, done, cancelled) { z -> gridFor(z) }
                } catch (e: android.database.sqlite.SQLiteFullException) {
                    diskFull = true
                } catch (e: Exception) {
                    failed.incrementAndGet()
                    Dev.event("land", "failed for “${pack.name}”: $e")
                }
            }
        } finally {
            main.removeCallbacks(ticker)
            landNote = null
            synchronized(insert) { insert.close() }
        }
        if (diskFull) {
            Dev.event("download", "storage full — stopped “${pack.name}”")
            Notif.warn(this, tr("Phone storage is full", "Telefon hafızası dolu"), tr("The map download “${pack.name}” stopped. Free some space, then resume it.", "“${pack.name}” harita indirmesi durdu. Yer açın, sonra sürdürün."))
        }
        pack.state = when {
            diskFull -> "incomplete"
            cancelled() -> "paused"
            failed.get() > 0 -> "incomplete"
            else -> "done"
        }
        Packs.saveMeta(pack)
        Packs.get(id)?.state = pack.state // the cached list may hold a newer object for this pack
        pr.done = done.get(); pr.bytes = bytes.get(); pr.failed = failed.get(); pr.waitingWifi = false
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val fetched = done.get() - startDone
        Dev.event(
            "download",
            "end “${pack.name}” → ${pack.state} · ${fetched} tiles in ${"%.0f".format(java.util.Locale.US, secs)} s" +
                " (${if (secs > 0) "%.1f".format(java.util.Locale.US, fetched / secs) else "–"} tiles/s) · ${Fmt.mb(bytes.get())}" +
                " · failed ${failed.get()} · retries ${nRetries.get()} · io errors ${nIoErrors.get()}" +
                " · http ${httpCodes.entries.joinToString { "${it.key}×${it.value}" }.ifEmpty { "-" }}" +
                " · Wi-Fi waits ${nWifiWaits.get()} (${wifiWaitMs.get() / 1000} s) · db ${Fmt.mb(Packs.sizeBytes(pack))}",
        )
        Downloads.fire()
    }

    /**
     * Builds the private-land layer: OSM areas/fences (Overpass) + WorldCover cropland, rendered into
     * transparent tiles for the pack's tiles at the land zooms. Marks the pack land = 2 when complete.
     */
    private fun buildLand(
        pack: Pack, ins: SQLiteStatement, pr: Downloads.Prog, done: AtomicLong,
        cancelled: () -> Boolean, gridFor: (Int) -> TileGrid?,
    ) {
        val t0 = System.currentTimeMillis()
        val zs = pack.zRange(Layer.LAND)
        val total = zs.sumOf { z -> gridFor(z)?.count() ?: Geo.tileCount(pack.bbox, z, z) }
        pr.total += total
        landNote = tr("Private land: reading OpenStreetMap…", "Özel arazi: OpenStreetMap okunuyor…")

        // 1. OpenStreetMap through Overpass (mirrors in turn; big answers take a while)
        var osm: LandData? = null
        var osmErr: Exception? = null
        for (m in Land.MIRRORS) {
            if (cancelled()) return
            val net = awaitWifi(cancelled) ?: return
            try {
                val js = String(Net.get(net, Land.url(m, pack.bbox), readTimeoutMs = 200_000), Charsets.UTF_8)
                osm = Land.parse(js)
                Dev.event("land", "OSM from ${java.net.URL(m).host}: ${js.length / 1024} KB · $osm")
                break
            } catch (e: Exception) {
                osmErr = e
                Dev.event("land", "OSM ${java.net.URL(m).host} failed: $e")
            }
        }
        if (osm == null) throw IllegalStateException("OpenStreetMap servers unavailable ($osmErr)")

        // 2. WorldCover cropland / built-up (range requests on the public COGs)
        landNote = tr("Private land: reading satellite land cover…", "Özel arazi: uydu arazi örtüsü okunuyor…")
        val cover = try {
            WorldCover.read(pack.bbox, { url, from, len ->
                val net = awaitWifi(cancelled) ?: throw java.io.IOException("cancelled")
                val b = Net.get(net, url, mapOf("Range" to "bytes=$from-${from + len - 1}"), 60_000)
                // a server ignoring Range sends the whole file: keep only the part asked for
                if (b.size > len) b.copyOfRange(from.toInt().coerceAtMost(b.size), (from + len).toInt().coerceAtMost(b.size)) else b
            }).also { Dev.event("land", "WorldCover ${it.w}×${it.h} cells at ${"%.0f".format(java.util.Locale.US, it.step * 111_000)} m · cropland ${it.count(WorldCover.CROPLAND)} · built-up ${it.count(WorldCover.BUILT_UP)}") }
        } catch (e: Exception) {
            if (cancelled()) return
            Dev.event("land", "WorldCover failed: $e")
            null
        }
        if (cancelled()) return

        // 3. render
        landNote = tr("Private land: drawing the map layer…", "Özel arazi: harita katmanı çiziliyor…")
        var n = 0
        var kept = 0
        var bytes = 0L
        for (z in zs) {
            if (cancelled()) return
            fun one(x: Int, y: Int) {
                val d = LandRender.render(z, x, y, osm, cover)
                store(ins, Layer.LAND, z, x, y, d)
                n++; done.incrementAndGet()
                if (d.isNotEmpty()) { kept++; bytes += d.size }
            }
            val g = gridFor(z)
            if (g != null) {
                var i = g.bits.nextSetBit(0)
                while (i >= 0 && !cancelled()) { one(g.x0 + i / g.h, g.y0 + i % g.h); i = g.bits.nextSetBit(i + 1) }
            } else {
                val x0 = Geo.tileX(pack.bbox.w, z); val x1 = Geo.tileX(pack.bbox.e, z)
                val y0 = Geo.tileY(pack.bbox.n, z); val y1 = Geo.tileY(pack.bbox.s, z)
                outer@ for (x in x0..x1) for (y in y0..y1) { if (cancelled()) break@outer; one(x, y) }
            }
        }
        if (cancelled()) return
        landNote = null
        // without the satellite part, keep it "wanted" so Resume tries again
        pack.land = if (cover != null) 2 else 1
        Packs.saveMeta(pack)
        Packs.get(pack.id)?.land = pack.land
        if (cover == null) throw IllegalStateException("satellite land cover unavailable — OSM part saved")
        Dev.event("land", "built “${pack.name}” · $n tiles ($kept with land, ${Fmt.mb(bytes)}) in ${(System.currentTimeMillis() - t0) / 1000} s")
    }

    @Volatile private var landNote: String? = null

    private fun existing(db: SQLiteDatabase, l: Layer, z: Int, x: Int): HashSet<Int> {
        val s = HashSet<Int>()
        db.rawQuery(
            "SELECT y FROM tiles WHERE l=? AND z=? AND x=?",
            arrayOf(l.ordinal.toString(), z.toString(), x.toString()),
        ).use { c -> while (c.moveToNext()) s.add(c.getInt(0)) }
        return s
    }

    private fun fetchOne(
        l: Layer, z: Int, x: Int, y: Int, ins: SQLiteStatement,
        done: AtomicLong, bytes: AtomicLong, failed: AtomicInteger, cancelled: () -> Boolean,
    ) {
        var attempt = 0
        while (!cancelled()) {
            val net = awaitWifi(cancelled) ?: return
            try {
                val data = Net.get(net, curPack?.url(l, z, x, y) ?: l.url(z, x, y))
                store(ins, l, z, x, y, data)
                done.incrementAndGet()
                bytes.addAndGet(data.size.toLong())
                return
            } catch (e: Net.Http) {
                httpCodes.getOrPut(e.code) { AtomicInteger() }.incrementAndGet()
                if (e.code == 404 || e.code == 204 || e.code == 410) {
                    store(ins, l, z, x, y, TileProvider.EMPTY) // tile doesn't exist: remember, don't retry
                    done.incrementAndGet()
                    return
                }
                attempt++
            } catch (e: android.database.sqlite.SQLiteFullException) {
                throw e
            } catch (e: Exception) {
                nIoErrors.incrementAndGet()
                if (wifiNet == null || wifiNet != net) continue // Wi-Fi changed/lost: wait, not a failure
                attempt++
            }
            if (attempt >= 4) {
                failed.incrementAndGet()
                Dev.event("download", "tile gave up: ${l.name} $z/$x/$y")
                return
            }
            nRetries.incrementAndGet()
            try { Thread.sleep(800L * attempt * attempt) } catch (_: InterruptedException) { return }
        }
    }

    private fun store(ins: SQLiteStatement, l: Layer, z: Int, x: Int, y: Int, d: ByteArray) = synchronized(ins) {
        ins.clearBindings()
        ins.bindLong(1, l.ordinal.toLong())
        ins.bindLong(2, z.toLong())
        ins.bindLong(3, x.toLong())
        ins.bindLong(4, y.toLong())
        ins.bindBlob(5, d)
        ins.executeInsert()
    }

    private fun awaitWifi(cancelled: () -> Boolean): Network? = lock.withLock {
        if (wifiNet == null && !cancelled()) nWifiWaits.incrementAndGet()
        val t0 = System.currentTimeMillis()
        while (wifiNet == null && !cancelled()) {
            waiting = true
            wifiCond.await(3, TimeUnit.SECONDS)
        }
        wifiWaitMs.addAndGet(System.currentTimeMillis() - t0)
        waiting = false
        if (cancelled()) null else wifiNet
    }

    private fun publish(p: Pack, pr: Downloads.Prog, done: AtomicLong, bytes: AtomicLong, failed: AtomicInteger) {
        pr.done = done.get(); pr.bytes = bytes.get(); pr.failed = failed.get(); pr.waitingWifi = waiting
        val pct = if (pr.total > 0) (pr.done * 100 / pr.total).toInt() else 0
        val pctS = tr("$pct%", "%$pct")
        val text = if (pr.waitingWifi) tr("Waiting for Wi-Fi — mobile data is never used", "Wi-Fi bekleniyor — mobil veri hiç kullanılmaz")
        else landNote?.let { "$pctS · $it" } ?: ("$pctS · " + tr("${pr.done} / ${pr.total} tiles · ${Fmt.mb(pr.bytes)} this session", "${pr.done} / ${pr.total} karo · bu oturumda ${Fmt.mb(pr.bytes)}"))
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notif(tr("Downloading ${p.name}", "İndiriliyor: ${p.name}"), text, pr.total, pr.done))
        } catch (_: Exception) {}
        Downloads.fire()
    }

    private fun notif(title: String, text: String?, max: Long, cur: Long): Notification =
        Notification.Builder(this, Notif.DL)
            .setSmallIcon(R.drawable.trail_ic_notif)
            .setContentTitle(title)
            .apply { if (text != null) setContentText(text) }
            .apply { if (max > 0) setProgress(1000, (cur * 1000 / max).toInt().coerceIn(0, 1000), false) }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(Notif.openApp(this))
            .build()
}
