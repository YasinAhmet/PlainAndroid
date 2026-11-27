package app.trekmap.trail

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.plain.lang.PlainLang
import app.plain.lang.tr
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.Writer

/**
 * The trail recorder. Lives in the app process, driven by [TrailService] (which keeps it alive).
 *
 * Power strategy:
 *  - GPS chip only (no Wi-Fi/cell positioning, no network at all).
 *  - App on screen: 1 fix/s for a live dot. Screen off / in pocket: 1 fix every 2 s delivered in
 *    30 s batches, so the CPU sleeps between batches.
 *  - Standing still for 1 min with the screen off: GPS is switched OFF and the hardware
 *    "significant motion" sensor wakes it again when you walk on.
 *  - Every stored point is appended to a file immediately, so nothing is lost if the phone kills the app.
 */
object Trail {
    enum class Gps { OFF, SEARCHING, OK, RESTING, DISABLED, NO_PERMISSION }

    var active = false
        private set
    var startTime = 0L
        private set
    var planId: String? = null
        private set
    var planName: String? = null
        private set
    var distM = 0.0
        private set
    var eleNow = Double.NaN
        private set
    var gps = Gps.OFF
        private set
    var lastLat = Double.NaN
        private set
    var lastLon = Double.NaN
        private set
    var lastAcc = 0f
        private set
    /** For the statistics overlay: satellites used, mean signal (dB-Hz), speed m/s and course ° (NaN = unknown). */
    var lastSats = -1
        private set
    var lastCn0 = Double.NaN
        private set
    var lastSpeed = Float.NaN
        private set
    var lastCourse = Float.NaN
        private set
    val ascentM: Double get() = asc.total
    /** Paused by the user (tap the power button): GPS off, no points; the paused time doesn't count as trail time. */
    var paused = false
        private set
    private var pausedTotal = 0L
    private var pausedAt = 0L
    /** Recording time without the pauses (ms). */
    val movingMs: Long get() {
        val now = System.currentTimeMillis()
        return (now - startTime - pausedTotal - (if (paused) now - pausedAt else 0L)).coerceAtLeast(0L)
    }
    val segs = ArrayList<MLine>()
    var listener: (() -> Unit)? = null
    val attached get() = svc != null

    private val asc = AscentCounter(4.0)
    private val filter = TrackFilter()
    private val still = StillDetector()
    private var uiVisible = false
    private var resting = false
    private var restLat = Double.NaN
    private var restLon = Double.NaN
    private var useDem: Boolean? = null
    private var svc: Service? = null
    private var lm: LocationManager? = null
    private var sm: SensorManager? = null
    private var sigMotion: Sensor? = null
    private var writer: Writer? = null
    private var sLat = Double.NaN
    private var sLon = Double.NaN
    private var newSeg = true
    private var writeFailed = false
    private val main = Handler(Looper.getMainLooper())

    // development log (see DevSummary for the row format)
    private var raw: Writer? = null
    private var appCtx: Context? = null
    private var modeCode = 'L'
    private var lastBattRow = 0L
    private var restoredGapMs = -1L

    private fun recDir(ctx: Context) = File(ctx.filesDir, "rec").also { it.mkdirs() }
    private fun rawFile(ctx: Context) = File(recDir(ctx), "raw.csv")

    /** Adds an event row to the raw log (and the app event log when [alsoApp]). */
    fun devEvent(event: String, detail: String = "", alsoApp: Boolean = false) {
        if (!Dev.ENABLED) return
        try { raw?.write("E,${System.currentTimeMillis()},$event,${detail.replace(',', ';')}\n") } catch (_: Exception) {}
        if (alsoApp) Dev.event("trail", "$event $detail")
    }

    private fun battRow() {
        val ctx = appCtx ?: return
        if (!Dev.ENABLED) return
        val b = Dev.battery(ctx)
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        lastBattRow = System.currentTimeMillis()
        try {
            raw?.write(
                "B,$lastBattRow,${b.pct},${if (b.charging) 1 else 0},${b.tempC},${b.currentRaw},${b.chargeMah}," +
                    "${if (pm.isPowerSaveMode) 1 else 0},${if (pm.isDeviceIdleMode) 1 else 0},${if (pm.isInteractive) 1 else 0}," +
                    "${try { Env.snapshot(ctx) } catch (e: Exception) { "" }}\n"
            )
        } catch (_: Exception) {}
    }

    private fun openRaw(ctx: Context) {
        if (!Dev.ENABLED || raw != null) return
        val f = rawFile(ctx)
        val fresh = !f.exists()
        raw = BufferedWriter(FileWriter(f, true), 16384)
        if (fresh) {
            raw?.write("# trekmap-raw v1 · ${Dev.versionInfo(ctx)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n")
            raw?.write("# F,wall,fixTime,ageMs,lat,lon,acc,alt,mslAlt,vAcc,speed,sAcc,bearing,bAcc,sats,meanCn0,maxCn0,batch,decision,mode\n")
            raw?.write("# E,wall,event,detail\n# B,wall,pct,charging,tempC,currentRaw,chargeMah,powerSave,idle,screenOn,${Env.COLUMNS}\n")
        }
    }

    /** Moves the finished raw log into the dev folder with a digest next to it. */
    private fun archiveRaw(ctx: Context, name: String) {
        if (!Dev.ENABLED) return
        try { raw?.close() } catch (_: Exception) {}
        raw = null
        val f = rawFile(ctx)
        if (!f.exists()) return
        val dest = File(Dev.trailsDir, "$name.raw.csv")
        try {
            if (!f.renameTo(dest)) { f.copyTo(dest, overwrite = true); f.delete() }
        } catch (e: Exception) {
            f.delete() // dev log only; never block the real trail
            return
        }
        try {
            val s = dest.useLines { DevSummary.of(it) }
            File(Dev.trailsDir, "$name.summary.txt").writeText(s.text() + "\n")
            Dev.event("trail", "saved dev log $name · ${s.fixes} fixes, ${s.stored} stored, ${Fmt.dur(s.durationMs / 1000)}, battery ${s.battStart}%→${s.battEnd}%")
        } catch (e: Exception) {
            Dev.event("trail", "summary failed for $name: $e")
        }
    }

    // ------------------------------------------------------------------ lifecycle
    fun start(ctx: Context, pid: String?, pname: String?) {
        if (active) return
        val dir = recDir(ctx)
        File(dir, "current.csv").delete()
        rawFile(ctx).delete()
        Photos.deleteRec(ctx)
        val now = System.currentTimeMillis()
        resetState(now, pid, pname)
        try {
            writeMeta(ctx)
        } catch (e: Exception) {
            Dev.event("trail", "cannot start: $e")
            throw IllegalStateException(tr("Phone storage is full — free some space to record.", "Telefon hafızası dolu — kayıt için yer açın."))
        }
        active = true
        gps = Gps.SEARCHING
        appCtx = ctx.applicationContext
        restoredGapMs = -1L
        openRaw(ctx)
        devEvent("start", "plan=${pname ?: "-"}", alsoApp = true)
        battRow()
        ensureService(ctx)
    }

    private fun writeMeta(ctx: Context) {
        File(recDir(ctx), "current.json").writeText(
            JSONObject().put("start", startTime).put("planId", planId ?: "").put("planName", planName ?: "")
                .put("pausedTotal", pausedTotal).put("pausedAt", if (paused) pausedAt else 0L).toString()
        )
    }

    /** Pause / resume (tap the power button). Paused: GPS off and nothing recorded; resuming starts a new segment (no straight line across). */
    fun setPaused(ctx: Context, on: Boolean) {
        if (!active || on == paused) return
        val now = System.currentTimeMillis()
        if (on) { paused = true; pausedAt = now }
        else {
            pausedTotal += now - pausedAt
            paused = false; pausedAt = 0L
            resting = false; filter.reset(); still.reset()
        }
        newSeg = true
        try { writeMeta(ctx) } catch (_: Exception) {}
        devEvent(if (on) "pause" else "resume", alsoApp = true)
        applyMode()
        ensureService(ctx) // the notification shows the new state
        notifyUi()
    }

    /** Where a photo taken now belongs: the latest fix if fresh, else the last track point. [lat, lon, ele] (NaN if unknown). */
    @SuppressLint("MissingPermission")
    fun photoPosition(ctx: Context): DoubleArray {
        val fresh = !lastLat.isNaN() && lastAcc <= 50f
        if (fresh) return doubleArrayOf(lastLat, lastLon, eleNow)
        if (!sLat.isNaN()) return doubleArrayOf(sLat, sLon, eleNow)
        // no fix yet (e.g. dense forest, just started): the phone's last known position from the past 10 min
        try {
            val lm = ctx.getSystemService(LocationManager::class.java)
            val l = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER)
                .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }
                .filter { System.currentTimeMillis() - it.time < 10 * 60_000L }
                .minByOrNull { if (it.hasAccuracy()) it.accuracy else 9999f }
            if (l != null) return doubleArrayOf(l.latitude, l.longitude, Double.NaN)
        } catch (_: Exception) {}
        return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
    }

    /** Settings changed (e.g. Long hike): re-apply the GPS mode. */
    fun settingsChanged() { if (svc != null) applyMode() }

    private var saverReceiver: android.content.BroadcastReceiver? = null
    private var envWatcher: Env.Watcher? = null

    /** Battery saver switched on mid-hike can stop GPS with the screen off: warn with a notification. */
    private fun watchBatterySaver(s: Service) {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                PlainLang.init(c)
                val lim = Power.saverLimitsGps(c)
                devEvent("battery-saver", lim ?: "changed (no GPS limit)", alsoApp = true)
                if (lim != null && active) Notif.warn(c, tr("Battery saver may stop your track", "Pil tasarrufu kaydınızı durdurabilir"),
                    tr("Battery saver $lim. Turn it off, or keep the screen on, to keep recording.", "Pil tasarrufu $lim. Kaydın sürmesi için kapatın ya da ekranı açık tutun."))
            }
        }
        val f = android.content.IntentFilter(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) s.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else s.registerReceiver(r, f)
            saverReceiver = r
        } catch (_: Exception) {}
    }

    /** Last stored track point [lat, lon], or null. */
    fun lastPoint(): DoubleArray? = if (sLat.isNaN()) null else doubleArrayOf(sLat, sLon)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /**
     * (Re)starts the foreground service while the app is in the foreground. Also called when already
     * running: starting it again from the foreground renews "while in use" location access in case
     * Android restarted it from the background. Never starts without permission — a location
     * service that can't call startForeground would crash the app.
     */
    fun ensureService(ctx: Context) {
        if (!active) return
        if (!hasPermission(ctx)) {
            gps = Gps.NO_PERMISSION
            notifyUi()
            return
        }
        try {
            ctx.startForegroundService(Intent(ctx, TrailService::class.java))
        } catch (e: Exception) {
        }
    }

    /** Rebuilds an unfinished recording from disk. */
    fun restore(ctx: Context): Boolean {
        if (active) return true
        val dir = recDir(ctx)
        val meta = File(dir, "current.json")
        if (!meta.exists()) return false
        return try {
            val j = JSONObject(meta.readText())
            resetState(j.getLong("start"), j.optString("planId").ifEmpty { null }, j.optString("planName").ifEmpty { null })
            pausedTotal = j.optLong("pausedTotal", 0L)
            pausedAt = j.optLong("pausedAt", 0L)
            paused = pausedAt > 0L
            for (s in readCsv(File(dir, "current.csv"))) {
                val line = MLine(s.size + 64)
                segs.add(line)
                for (i in 0 until s.size) {
                    line.add(s.lat[i], s.lon[i])
                    if (i > 0) distM += Geo.dist(s.lat[i - 1], s.lon[i - 1], s.lat[i], s.lon[i])
                    val e = s.ele?.get(i) ?: Double.NaN
                    if (!e.isNaN()) { asc.push(e); eleNow = e }
                }
                if (s.size > 0) { sLat = s.lat[s.size - 1]; sLon = s.lon[s.size - 1] }
            }
            newSeg = true // the app was down: start a new segment instead of drawing a fake straight line
            active = true
            appCtx = ctx.applicationContext
            val rf = rawFile(ctx)
            restoredGapMs = if (rf.exists()) System.currentTimeMillis() - rf.lastModified() else 0L
            true
        } catch (e: Exception) {
            false
        }
    }

    fun attach(s: Service): Boolean {
        if (svc != null) return true
        if (!restore(s)) return false
        svc = s
        lm = s.getSystemService(LocationManager::class.java)
        sm = s.getSystemService(SensorManager::class.java)
        sigMotion = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
        writer = BufferedWriter(FileWriter(File(recDir(s), "current.csv"), true), 8192)
        gps = if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) Gps.SEARCHING else Gps.DISABLED
        appCtx = s.applicationContext
        openRaw(s)
        watchBatterySaver(s)
        if (Dev.ENABLED) envWatcher = Env.Watcher(s.applicationContext) { e, d -> devEvent(e, d) }.also { it.start() }
        if (restoredGapMs >= 0) {
            devEvent("restore", "process was killed; no data for ~${restoredGapMs / 1000} s", alsoApp = true)
            restoredGapMs = -1L
        }
        devEvent(
            "attach",
            "gps=${gps} sigMotion=${sigMotion?.name ?: "none"} ui=${if (uiVisible) "visible" else "hidden"}",
        )
        battRow()
        applyMode()
        notifyUi()
        return true
    }

    fun detach() {
        if (svc == null) return
        devEvent("detach")
        saverReceiver?.let { r -> try { svc?.unregisterReceiver(r) } catch (_: Exception) {} }
        saverReceiver = null
        envWatcher?.stop()
        envWatcher = null
        try { raw?.flush() } catch (_: Exception) {}
        try { lm?.removeUpdates(loc) } catch (_: Exception) {}
        sigMotion?.let { sm?.cancelTriggerSensor(trigger, it) }
        try { writer?.close() } catch (_: Exception) {}
        writer = null
        svc = null
        lm = null
        sm = null
    }

    /** Stops recording. Saves to Past trails when [save]; returns the saved entry (null if nothing recorded). */
    fun stop(ctx: Context, name: String?, save: Boolean): Entry? = try {
        stopInner(ctx, name, save)
    } catch (e: Exception) {
        // saving failed (e.g. storage full): the recording stays intact and the service runs again
        Dev.event("trail", "stop failed: $e")
        ensureService(ctx)
        throw e
    }

    private fun stopInner(ctx: Context, name: String?, save: Boolean): Entry? {
        devEvent("stop", if (save) "save" else "discard", alsoApp = true)
        battRow()
        detach()
        ctx.stopService(Intent(ctx, TrailService::class.java))
        val dir = recDir(ctx)
        val csv = File(dir, "current.csv")
        var entry: Entry? = null
        if (save) {
            var data = readCsv(csv).filter { it.size >= 2 }
            if (data.isEmpty()) {
                // no usable GPS track, but photos were taken: keep them as a trail through the photo spots
                val ps = Photos.list(Photos.recDir(ctx)).filter { it.hasPosition }
                if (ps.isNotEmpty()) {
                    val la = ps.map { it.lat }.toMutableList(); val lo = ps.map { it.lon }.toMutableList()
                    if (la.size == 1) { la += la[0]; lo += lo[0] }
                    data = listOf(Seg(la.toDoubleArray(), lo.toDoubleArray(), null, LongArray(la.size) { ps[minOf(it, ps.size - 1)].time }))
                    Dev.event("trail", "no GPS track: saved ${ps.size} photo spots as the trail")
                }
            }
            if (data.isNotEmpty()) {
                val n = name ?: (tr("Trail", "Rota") + " ${Fmt.date(startTime)}")
                val id = Store.newId()
                val photos = Photos.moveRecToTrack(ctx, id) // before the GPX is written, so photos become waypoints
                entry = Store.save(
                    Kind.TRACK, Route(n, data), n, startTime,
                    movingMs / 1000, asc.total, id, // pauses don't count
                )
                if (photos > 0) Dev.event("trail", "saved with $photos photos")
            }
        }
        Photos.deleteRec(ctx) // discarded / empty: the photos stay in the Gallery
        // the dev log is kept even for discarded / empty trails — those are often the interesting ones
        archiveRaw(ctx, entry?.id ?: "${if (save) "empty" else "discarded"}_${Dev.stamp()}")
        csv.delete()
        File(dir, "current.json").delete()
        active = false
        resetState(0L, null, null)
        listener?.invoke()
        return entry
    }

    fun setUiVisible(v: Boolean) {
        if (uiVisible == v) return
        uiVisible = v
        if (active) devEvent("ui", if (v) "visible" else "hidden")
        if (svc != null) applyMode()
    }

    private fun resetState(start: Long, pid: String?, pname: String?) {
        startTime = start; planId = pid; planName = pname
        distM = 0.0; eleNow = Double.NaN; asc.reset(); segs.clear()
        filter.fullReset(); still.reset()
        resting = false; useDem = null; newSeg = true
        paused = false; pausedTotal = 0L; pausedAt = 0L
        sLat = Double.NaN; sLon = Double.NaN
        lastLat = Double.NaN; lastLon = Double.NaN; lastAcc = 0f
        lastSats = -1; lastCn0 = Double.NaN; lastSpeed = Float.NaN; lastCourse = Float.NaN
        gps = Gps.OFF
    }

    // ------------------------------------------------------------------ GPS modes
    private fun applyMode() {
        val lm = lm ?: return
        try { lm.removeUpdates(loc) } catch (_: Exception) {}
        sigMotion?.let { sm?.cancelTriggerSensor(trigger, it) }

        if (paused) {
            gps = Gps.RESTING
            modeCode = 'Z'
            devEvent("mode", "paused gps=off")
            notifyUi()
            return
        }
        if (resting && !uiVisible) {
            gps = Gps.RESTING
            val sensor = sigMotion
            if (sensor != null && sm?.requestTriggerSensor(trigger, sensor) == true) {
                devEvent("mode", "rest gps=off waiting-for-motion")
                battRow()
                notifyUi()
                return // GPS fully off until the motion sensor fires
            }
            modeCode = 'R'
            devEvent("mode", "rest-poll interval=20000")
            battRow()
            request(lm, 20_000L, 0L) // no motion sensor: slow polling while resting
            return
        }
        if (resting) { // screen came on while resting
            resting = false
            filter.reset()
            still.reset()
            devEvent("rest-end", "screen on")
        }
        if (gps == Gps.RESTING || gps == Gps.OFF) gps = Gps.SEARCHING
        if (uiVisible) {
            modeCode = 'L'
            devEvent("mode", "live interval=1000 batch=0")
            request(lm, 1000L, 0L)
        } else {
            modeCode = 'P'
            // "Long hike" (Settings): a fix every 5 s handed over every 2 min lets the GPS chip and CPU sleep more
            val long = appCtx?.getSharedPreferences(TrailInit.PREFS, Context.MODE_PRIVATE)?.getBoolean("longHike", false) == true
            val interval = if (long) 5000L else 2000L
            val batch = if (long) 120_000L else 30_000L
            devEvent("mode", "pocket interval=$interval batch=$batch${if (long) " long-hike" else ""}")
            request(lm, interval, batch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun request(lm: LocationManager, interval: Long, maxDelay: Long) {
        val ctx = svc ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val req = LocationRequest.Builder(interval)
                    .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(interval)
                    .setMaxUpdateDelayMillis(maxDelay)
                    .build()
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, req, ctx.mainExecutor, loc)
            } else {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, interval, 0f, loc, Looper.getMainLooper())
            }
        } catch (e: SecurityException) {
            gps = Gps.NO_PERMISSION
            devEvent("gps-error", "SecurityException ${e.message}", alsoApp = true)
        } catch (e: IllegalArgumentException) {
            gps = Gps.DISABLED
            devEvent("gps-error", "IllegalArgument ${e.message}", alsoApp = true)
        }
    }

    private val loc = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            onFix(location, 1)
            afterBatch()
        }

        override fun onLocationChanged(locations: MutableList<Location>) {
            if (locations.size > 1) devEvent("batch", "${locations.size}")
            for (l in locations) onFix(l, locations.size)
            afterBatch()
        }

        override fun onProviderDisabled(provider: String) {
            gps = Gps.DISABLED
            devEvent("provider-off", provider, alsoApp = true)
            notifyUi()
        }

        override fun onProviderEnabled(provider: String) {
            if (gps == Gps.DISABLED) gps = Gps.SEARCHING
            devEvent("provider-on", provider, alsoApp = true)
            notifyUi()
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            main.post {
                devEvent("motion", "significant motion -> gps on")
                if (resting) exitRest()
            }
        }
    }

    private fun enterRest(l: Location) {
        devEvent("rest-start", "still for 1 min")
        resting = true
        restLat = l.latitude
        restLon = l.longitude
        still.reset()
        applyMode()
    }

    private fun exitRest() {
        resting = false
        filter.reset()
        still.reset()
        gps = Gps.SEARCHING
        applyMode()
        notifyUi()
    }

    // ------------------------------------------------------------------ fixes
    private fun onFix(l: Location, batch: Int) {
        if (!active || paused) return
        val acc = if (l.hasAccuracy()) l.accuracy else 999f
        lastLat = l.latitude
        lastLon = l.longitude
        lastAcc = acc
        @Suppress("DEPRECATION")
        l.extras?.let { x ->
            lastSats = x.get("satellites")?.toString()?.toIntOrNull() ?: -1
            lastCn0 = x.get("meanCn0")?.toString()?.toDoubleOrNull() ?: Double.NaN
        }
        lastSpeed = if (l.hasSpeed()) l.speed else Float.NaN
        lastCourse = if (l.hasBearing() && l.hasSpeed() && l.speed > 0.6f) l.bearing else Float.NaN
        if (resting) { // only reached when polling without a motion sensor
            rawFix(l, batch, 'R')
            if (acc <= 30f && Geo.dist(restLat, restLon, l.latitude, l.longitude) > maxOf(25.0, acc.toDouble())) exitRest()
            return
        }
        gps = if (acc <= 30f) Gps.OK else Gps.SEARCHING
        val p = filter.push(l.latitude, l.longitude, acc, l.time, if (l.hasSpeed()) l.speed else -1f)
        rawFix(l, batch, if (p != null) 'S' else filter.lastReason)
        if (p != null) storePoint(p[0], p[1], l.time, elevation(p[0], p[1], l))
        if (!uiVisible && acc <= 30f &&
            still.push(l.latitude, l.longitude, acc.toDouble(), l.time, if (l.hasSpeed()) l.speed else 0f)
        ) enterRest(l)
    }

    private fun elevation(lat: Double, lon: Double, l: Location): Double {
        if (useDem == null) useDem = Dem.elevation(lat, lon) != null
        if (useDem == true) return Dem.elevation(lat, lon) ?: Double.NaN
        return when {
            Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude() -> l.mslAltitudeMeters
            l.hasAltitude() -> l.altitude
            else -> Double.NaN
        }
    }

    private fun storePoint(lat: Double, lon: Double, t: Long, ele: Double) {
        val w = writer
        try {
            if (newSeg || segs.isEmpty()) {
                segs.add(MLine(256))
                w?.write("s\n")
                newSeg = false
            } else if (!sLat.isNaN()) {
                distM += Geo.dist(sLat, sLon, lat, lon)
            }
            segs[segs.size - 1].add(lat, lon)
            if (!ele.isNaN()) { asc.push(ele); eleNow = ele }
            w?.write("p,$t,$lat,$lon,$ele\n")
        } catch (e: Exception) {
            if (!writeFailed) {
                writeFailed = true
                appCtx?.let { Notif.warn(it, tr("Track could not be saved", "İz kaydedilemedi"), tr("Phone storage may be full — free some space to keep recording.", "Telefon hafızası dolu olabilir — kaydın sürmesi için yer açın.")) }
                Dev.event("trail", "track write failed: $e")
            }
        }
        sLat = lat
        sLon = lon
    }

    @Suppress("DEPRECATION")
    private fun rawFix(l: Location, batch: Int, decision: Char) {
        val w = raw ?: return
        val now = System.currentTimeMillis()
        val ageMs = (android.os.SystemClock.elapsedRealtimeNanos() - l.elapsedRealtimeNanos) / 1_000_000
        val x = l.extras
        fun ex(k: String) = x?.get(k)?.toString() ?: ""
        val msl = if (Build.VERSION.SDK_INT >= 34 && l.hasMslAltitude()) l.mslAltitudeMeters.toString() else ""
        try {
            w.write(
                "F,$now,${l.time},$ageMs,${l.latitude},${l.longitude}," +
                    "${if (l.hasAccuracy()) l.accuracy else ""},${if (l.hasAltitude()) l.altitude else ""},$msl," +
                    "${if (l.hasVerticalAccuracy()) l.verticalAccuracyMeters else ""}," +
                    "${if (l.hasSpeed()) l.speed else ""},${if (l.hasSpeedAccuracy()) l.speedAccuracyMetersPerSecond else ""}," +
                    "${if (l.hasBearing()) l.bearing else ""},${if (l.hasBearingAccuracy()) l.bearingAccuracyDegrees else ""}," +
                    "${ex("satellites")},${ex("meanCn0")},${ex("maxCn0")},$batch,$decision,$modeCode\n"
            )
        } catch (_: Exception) {}
    }

    private fun afterBatch() {
        try { writer?.flush() } catch (_: Exception) {}
        // battery snapshot every 5 min, piggy-backing on a wake-up that is happening anyway
        if (System.currentTimeMillis() - lastBattRow > 5 * 60_000L) battRow()
        try { raw?.flush() } catch (_: Exception) {}
        if (uiVisible) notifyUi()
    }

    private fun notifyUi() = listener?.invoke()

    private fun readCsv(f: File): List<Seg> {
        val out = ArrayList<Seg>()
        if (!f.exists()) return out
        var la = DoubleList(); var lo = DoubleList(); var el = DoubleList(); var tm = LongList()
        fun flush() {
            if (la.size > 0) out.add(Seg(la.toArray(), lo.toArray(), el.toArray(), tm.toArray()))
            la = DoubleList(); lo = DoubleList(); el = DoubleList(); tm = LongList()
        }
        f.forEachLine { line ->
            if (line == "s") flush()
            else if (line.startsWith("p,")) {
                val p = line.split(',')
                if (p.size >= 5) {
                    val t = p[1].toLongOrNull()
                    val a = p[2].toDoubleOrNull()
                    val b = p[3].toDoubleOrNull()
                    if (t != null && a != null && b != null) {
                        la.add(a); lo.add(b); el.add(p[4].toDoubleOrNull() ?: Double.NaN); tm.add(t)
                    }
                }
            }
        }
        flush()
        return out
    }
}
