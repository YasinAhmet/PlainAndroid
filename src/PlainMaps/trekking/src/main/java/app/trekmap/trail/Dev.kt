package app.trekmap.trail

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Development diagnostics ("aggressive" logging while the app is being tuned).
 *
 * Everything lands in  Android/data/app.trekmap.trail/files/dev/  (adb can pull it; the app can
 * share it as one zip). Logging never wakes the phone by itself: rows are written only when the
 * app is already awake for another reason, so trail-mode power use is unchanged.
 *
 *  events-YYYYMMDD.log   app-wide event log (screens, downloads, permissions, process deaths…)
 *  device.txt            phone, GNSS chip, sensors, permissions, battery state at last start
 *  crash-TIME.txt             stack traces of any crash
 *  trails/NAME.raw.csv        every GPS fix of a recording + filter decision + modes + battery
 *  trails/NAME.summary.txt    per-trail digest computed from the raw file
 */
object Dev {
    const val ENABLED = true
    private const val KEEP_DAYS = 30
    private const val KEEP_TRAILS = 60

    lateinit var dir: File
        private set
    val trailsDir get() = File(dir, "trails").also { it.mkdirs() }

    private var writer: BufferedWriter? = null
    private var writerDay = ""
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val tsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val fileFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun init(app: Context) {
        dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "dev").also { it.mkdirs() }
        if (!ENABLED) return
        installCrashHandler(app)
        prune()
        event("app", "process start · ${versionInfo(app)}")
        try { File(dir, "device.txt").writeText(deviceInfo(app)) } catch (_: Exception) {}
        exitReasons(app)
    }

    @Synchronized
    fun event(tag: String, msg: String) {
        if (!ENABLED || !::dir.isInitialized) return
        try {
            val now = Date()
            val day = dayFmt.format(now)
            if (writer == null || day != writerDay) {
                writer?.close()
                writer = BufferedWriter(FileWriter(File(dir, "events-$day.log"), true))
                writerDay = day
            }
            writer!!.apply {
                write(tsFmt.format(now)); write(" ["); write(tag); write("] "); write(msg); write("\n"); flush()
            }
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun stamp(): String = fileFmt.format(Date())

    // ------------------------------------------------------------------ crashes & process deaths
    private fun installCrashHandler(app: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                File(dir, "crash-${stamp()}.txt").writeText(
                    "Thread: ${t.name}\n${versionInfo(app)}\nTrail recording: ${Trail.active}\n" +
                        "Device: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n\n$sw"
                )
                event("crash", "${e.javaClass.name}: ${e.message} (thread ${t.name})")
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Why did the previous process die? (Android 11+; catches HiOS kills, low memory, ANRs, crashes.) */
    private fun exitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = ctx.getSharedPreferences("dev", Context.MODE_PRIVATE)
        val last = prefs.getLong("lastExit", 0L)
        val list = try {
            ctx.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
        } catch (e: Exception) {
            return
        }
        var newest = last
        for (r in list.sortedBy { it.timestamp }) {
            if (r.timestamp <= last) continue
            newest = maxOf(newest, r.timestamp)
            event(
                "exit",
                "previous process ended ${synchronized(this) { tsFmt.format(Date(r.timestamp)) }}: ${reasonName(r.reason)}" +
                    " · status ${r.status} · importance ${r.importance} · pss ${r.pss / 1024} MB · ${r.description ?: ""}",
            )
        }
        prefs.edit().putLong("lastExit", newest).apply()
    }

    private fun reasonName(r: Int) = when (r) {
        1 -> "EXIT_SELF"; 2 -> "SIGNALED (killed)"; 3 -> "LOW_MEMORY"; 4 -> "CRASH"; 5 -> "CRASH_NATIVE"
        6 -> "ANR"; 7 -> "INITIALIZATION_FAILURE"; 8 -> "PERMISSION_CHANGE"; 9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"; 11 -> "USER_STOPPED"; 12 -> "DEPENDENCY_DIED"; 13 -> "OTHER (often the OEM battery killer)"
        14 -> "FREEZER"; 15 -> "PACKAGE_STATE_CHANGE"; 16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }

    // ------------------------------------------------------------------ device & battery
    fun versionInfo(ctx: Context): String = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "Plain Trekking ${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else 0})"
    } catch (e: Exception) {
        "Plain Trekking"
    }

    /** currentRaw = BatteryManager CURRENT_NOW exactly as reported (unit and sign differ between vendors). */
    class Batt(val pct: Int, val charging: Boolean, val tempC: Float, val currentRaw: Int, val chargeMah: Int) {
        override fun toString() = "$pct% ${if (charging) "charging" else "on battery"} · ${tempC}°C · current(raw) $currentRaw · ${chargeMah} mAh left"
    }

    fun battery(ctx: Context): Batt {
        val i = try { ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) } catch (e: Exception) { null }
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val cur = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (e: Exception) { 0 }
        val chg = try { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (e: Exception) { 0 }
        return Batt(if (level >= 0 && scale > 0) level * 100 / scale else -1, plugged, temp, cur, chg / 1000)
    }

    fun deviceInfo(ctx: Context): String = buildString {
        appendLine(versionInfo(ctx))
        appendLine("Written: ${synchronized(this@Dev) { tsFmt.format(Date()) }}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("Build: ${Build.DISPLAY}")
        val dm = ctx.resources.displayMetrics
        appendLine("Screen: ${dm.widthPixels}x${dm.heightPixels} @ ${dm.densityDpi} dpi")
        val sm = ctx.getSystemService(SensorManager::class.java)
        fun sensor(type: Int) = sm.getDefaultSensor(type)?.let { "${it.name} · ${it.vendor} · wake-up ${it.isWakeUpSensor} · ${it.power} mA" } ?: "none"
        appendLine("Significant motion: ${sensor(Sensor.TYPE_SIGNIFICANT_MOTION)}")
        appendLine("Step detector: ${sensor(Sensor.TYPE_STEP_DETECTOR)}")
        appendLine("Barometer: ${sensor(Sensor.TYPE_PRESSURE)}")
        appendLine("Accelerometer: ${sensor(Sensor.TYPE_ACCELEROMETER)}")
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (Build.VERSION.SDK_INT >= 28) {
            appendLine("GNSS chip: ${lm.gnssHardwareModelName} (hardware year ${lm.gnssYearOfHardware})")
            appendLine("Location enabled: ${lm.isLocationEnabled}")
        }
        if (Build.VERSION.SDK_INT >= 31) appendLine("GNSS capabilities: ${lm.gnssCapabilities}")
        appendLine("Providers: ${lm.allProviders}")
        val pm = ctx.getSystemService(PowerManager::class.java)
        appendLine("Battery optimisation exempt: ${pm.isIgnoringBatteryOptimizations(ctx.packageName)} · power save mode: ${pm.isPowerSaveMode}")
        fun perm(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        appendLine(
            "Permissions: fine ${perm(Manifest.permission.ACCESS_FINE_LOCATION)} · coarse ${perm(Manifest.permission.ACCESS_COARSE_LOCATION)}" +
                (if (Build.VERSION.SDK_INT >= 29) " · background ${perm(Manifest.permission.ACCESS_BACKGROUND_LOCATION)}" else "") +
                (if (Build.VERSION.SDK_INT >= 33) " · notifications ${perm(Manifest.permission.POST_NOTIFICATIONS)}" else "")
        )
        appendLine("Battery: ${battery(ctx)}")
        appendLine("Storage free: ${Fmt.mb(ctx.filesDir.usableSpace)}")
        appendLine("Offline maps: ${Packs.all().joinToString { "${it.name} [${it.state}, z${it.minZ}-${it.maxZ}, ${it.layers}]" }}")
    }

    // ------------------------------------------------------------------ housekeeping & sharing
    private fun prune() {
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        (dir.listFiles() ?: emptyArray()).filter { it.isFile && it.lastModified() < cutoff && it.name != "device.txt" }.forEach { it.delete() }
        val trails = (trailsDir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".raw.csv") }.sortedByDescending { it.lastModified() }
        for (f in trails.drop(KEEP_TRAILS)) {
            f.delete()
            File(f.path.removeSuffix(".raw.csv") + ".summary.txt").delete()
        }
    }

    /** All diagnostics + recorded GPX files in one zip, for sharing. */
    fun shareZip(ctx: Context): File {
        try { File(dir, "device.txt").writeText(deviceInfo(ctx)) } catch (_: Exception) {}
        val out = File(File(ctx.cacheDir, "share").also { it.mkdirs() }, "trekmap-debug-${stamp()}.zip")
        File(ctx.cacheDir, "share").listFiles()?.filter { it != out }?.forEach { it.delete() }
        synchronized(this) { writer?.flush() }
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            fun add(f: File, name: String) {
                if (!f.isFile) return
                z.putNextEntry(ZipEntry(name))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            dir.walkTopDown().filter { it.isFile }.forEach { add(it, "dev/" + it.relativeTo(dir).path.replace('\\', '/')) }
            Store.dir(Kind.TRACK).listFiles()?.filter { it.name.endsWith(".gpx") || it.name.endsWith(".json") }
                ?.forEach { add(it, "tracks/${it.name}") }
            val rec = File(ctx.filesDir, "rec")
            rec.listFiles()?.forEach { add(it, "recording-in-progress/${it.name}") }
        }
        event("share", "debug zip ${out.name} · ${Fmt.mb(out.length())}")
        return out
    }
}
