package app.plain.battery

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.PowerManager
import app.plain.lang.tr
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * All logging work. Runs inside a JobScheduler job (the system holds the wake lock for the
 * job's few hundred milliseconds) or on the UI's worker thread; never takes its own wake lock.
 */
class Tracker(context: Context) {
    private val ctx = context.applicationContext
    private val prefs: SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val logDir: File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }
    private val batteryLog get() = File(logDir, "battery.csv")
    private val screenLog get() = File(logDir, "screen.csv")
    private val appsLog get() = File(logDir, "apps.csv")
    private val latestFile get() = File(ctx.filesDir, "latest.tsv")
    private val baselineFile get() = File(ctx.filesDir, "baseline.tsv")
    private val prevBaselineFile get() = File(ctx.filesDir, "baseline-prev.tsv")

    /** Last error from dumpsys, or null when per-app data works. */
    val accessError: String? get() = prefs.getString("accessError", null)

    fun readSample(source: String): Sample {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = it.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (l >= 0 && sc > 0) l * 100 / sc else -1
        } ?: -1
        val status = when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
            else -> "unknown"
        }
        val plugged = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            8 /* BATTERY_PLUGGED_DOCK */ -> "dock"
            else -> "none"
        }
        val pm = ctx.getSystemService(PowerManager::class.java)
        return Sample(
            time = System.currentTimeMillis(),
            level = level,
            status = status,
            plugged = plugged,
            currentNowRaw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW),
            voltageMv = i?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
            tempDeciC = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0,
            chargeCounterUah = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            screenOn = pm.isInteractive,
            source = source,
        )
    }

    /**
     * Log one sample; take a per-app snapshot when the power state changed, when the last one
     * is older than [SNAPSHOT_EVERY_MS], or when [forceSnapshot].
     */
    fun record(source: String, forceSnapshot: Boolean = false, allowSnapshot: Boolean = true) =
        synchronized(LOCK) {
            val s = readSample(source)
            append(batteryLog, Csv.BATTERY_HEADER, listOf(Csv.battery(s)))
            logScreenEvents(s.time)
            val wasCharging = if (prefs.contains("charging")) prefs.getBoolean("charging", false) else null
            val due = s.time - prefs.getLong("snapshotAt", 0L) >= SNAPSHOT_EVERY_MS
            if (allowSnapshot) {
                if (forceSnapshot || due || wasCharging != s.charging) snapshot(s)
                prefs.edit().putBoolean("charging", s.charging).apply()
            }
        }

    private fun snapshot(s: Sample) {
        val now = System.currentTimeMillis()
        val prev = load(latestFile)
        var (text, err) = runCheckin()
        var cur = text?.let { Checkin.parse(it, now) }
        // Right after a reset, the first `--checkin` returns the saved *previous* period once
        // (and deletes it). So whenever the period looks new, ask again and keep the second answer.
        if (cur != null && cur.statsStart != prev?.statsStart) {
            val again = runCheckin()
            again.first?.let { Checkin.parse(it, System.currentTimeMillis()) }?.let { text = again.first; cur = it }
        }
        try {
            File(logDir, "status.txt").writeText(
                "${Csv.time(now)} snapshot " + if (cur != null)
                    "ok: ${text?.length} bytes, ${cur?.apps?.size} uids, stats since ${Csv.time(cur!!.statsStart)}\n"
                else "failed: ${err ?: "unparsable output"}\n")
        } catch (_: Exception) {}
        val snap = cur ?: run {
            prefs.edit().putString("accessError", err ?: "no power data in dumpsys output").apply()
            return
        }
        prefs.edit().remove("accessError").putLong("snapshotAt", now).apply()
        append(appsLog, Csv.APPS_HEADER, Csv.appRows(prev, snap, { label(it, snap, UidNames.english) }))
        save(latestFile, snap)
        saveHistory(snap)
        // Stats do not grow while on power, so the last snapshot on power is the unplug baseline.
        if (s.charging) {
            // First snapshot on power after a run on battery: keep the old baseline so the
            // UI can still show that last run while charging (stats are frozen on power).
            if (!prefs.getBoolean("lastSnapCharging", false)) {
                baselineFile.takeIf { it.exists() }?.copyTo(prevBaselineFile, overwrite = true)
            }
            save(baselineFile, snap)
        }
        prefs.edit().putBoolean("lastSnapCharging", s.charging).apply()
    }

    private fun runCheckin(): Pair<String?, String?> = try {
        val p = ProcessBuilder("/system/bin/dumpsys", "batterystats", "--checkin")
            .redirectErrorStream(true).start()
        p.outputStream.close()
        val out = StringBuilder(400_000)
        val reader = Thread {
            try { p.inputStream.bufferedReader().use { r -> out.append(r.readText()) } } catch (_: Exception) {}
        }
        reader.start()
        if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroy(); reader.join(1000); null to "dumpsys timed out" }
        else {
            reader.join(5000)
            val t = synchronized(out) { out.toString() }
            if (t.contains(",pwi,")) t to null
            else null to t.lineSequence().firstOrNull { it.isNotBlank() }?.take(200).orEmpty().ifEmpty { "empty dumpsys output" }
        }
    } catch (e: Exception) {
        null to (e.javaClass.simpleName + ": " + e.message)
    }

    private fun logScreenEvents(now: Long) {
        val from = prefs.getLong("screenQueryTo", now - TimeUnit.HOURS.toMillis(24))
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return
        val lines = ArrayList<String>()
        try {
            val ev = usm.queryEvents(from, now)
            val e = UsageEvents.Event()
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.SCREEN_INTERACTIVE -> lines += Csv.screen(e.timeStamp, true)
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE -> lines += Csv.screen(e.timeStamp, false)
                }
            }
        } catch (_: SecurityException) { return }
        if (lines.isNotEmpty()) append(screenLog, Csv.SCREEN_HEADER, lines)
        prefs.edit().putLong("screenQueryTo", now).apply()
    }

    // ---- reading back for the UI ----

    fun latest(): Snapshot? = load(latestFile)
    fun baseline(): Snapshot? = load(baselineFile)
    /** Baseline of the previous run on battery (valid while charging). */
    fun previousBaseline(): Snapshot? = load(prevBaselineFile)

    /** Snapshots of the last ~30 h (one per 15 min at most), oldest first, without uid names. */
    fun history(): List<Snapshot> =
        (historyDir.listFiles() ?: emptyArray()).sortedBy { it.name }.mapNotNull { load(it) }

    private val historyDir get() = File(ctx.filesDir, "history").also { it.mkdirs() }

    private fun saveHistory(s: Snapshot) {
        val files = historyDir.listFiles() ?: emptyArray()
        for (f in files) {
            val t = f.nameWithoutExtension.toLongOrNull() ?: 0L
            // keep at most one per 15 minutes, and nothing older than 30 hours
            if (t > s.takenAt - 15 * 60_000L || t < s.takenAt - 30 * 3_600_000L) f.delete()
        }
        save(File(historyDir, "${s.takenAt}.tsv"), s.copy(names = emptyMap()))
    }

    /** Result line of the last per-app snapshot (status.txt), or null. */
    fun lastStatus(): String? = try {
        File(logDir, "status.txt").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) { null }

    fun samples(maxBytes: Int = 256 * 1024): List<Sample> =
        tailLines(batteryLog, maxBytes).mapNotNull { Csv.parseBattery(it) }

    fun screenEvents(maxBytes: Int = 64 * 1024): List<Pair<Long, Boolean>> =
        tailLines(screenLog, maxBytes).mapNotNull { Csv.parseScreen(it) }

    /** Foreground minutes per package since [since]; the fallback when dumpsys is not allowed. */
    fun foregroundMinutes(since: Long): List<Pair<String, Double>> = try {
        val usm = ctx.getSystemService(UsageStatsManager::class.java)
        usm.queryAndAggregateUsageStats(since, System.currentTimeMillis())
            .map { (pkg, u) -> pkg to u.totalTimeInForeground / 60000.0 }
            .filter { it.second >= 0.5 }.sortedByDescending { it.second }
    } catch (_: Exception) { emptyList() }

    private val labelCache = HashMap<String, String?>()

    fun appLabel(pkg: String): String? = synchronized(labelCache) {
        labelCache.getOrPut(pkg) {
            try {
                val pm = ctx.packageManager
                pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
            } catch (_: PackageManager.NameNotFoundException) { null }
        }
    }

    /** Name of [uid] in the UI language; [t] = [UidNames.english] for the logs. */
    fun label(uid: Int, s: Snapshot?, t: (String, String) -> String = ::tr): String {
        val pkgs = s?.names?.get(uid) ?: ctx.packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return UidNames.label(uid, pkgs, { appLabel(it) }, t)
    }

    // ---- files ----

    private fun load(f: File): Snapshot? = try {
        if (f.exists()) Checkin.deserialize(f.readText()) else null
    } catch (_: Exception) { null }

    private fun save(f: File, s: Snapshot) {
        val tmp = File(f.path + ".tmp")
        tmp.writeText(Checkin.serialize(s))
        tmp.renameTo(f)
    }

    private fun append(f: File, header: String, lines: List<String>) {
        if (lines.isEmpty()) return
        if (f.length() > MAX_LOG_BYTES) f.renameTo(File(f.parentFile, f.nameWithoutExtension + ".1.csv"))
        val fresh = !f.exists() || f.length() == 0L
        f.appendText(buildString {
            if (fresh) append(header).append('\n')
            for (l in lines) append(l).append('\n')
        })
    }

    private fun tailLines(f: File, maxBytes: Int): List<String> {
        if (!f.exists()) return emptyList()
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val start = (len - maxBytes).coerceAtLeast(0)
            r.seek(start)
            val buf = ByteArray((len - start).toInt())
            r.readFully(buf)
            val lines = String(buf).split('\n')
            return (if (start > 0) lines.drop(1) else lines).filter { it.isNotBlank() && it[0].isDigit() }
        }
    }

    companion object {
        private val LOCK = Any()
        const val JOB_ID = 1
        val SNAPSHOT_EVERY_MS = TimeUnit.MINUTES.toMillis(55)
        const val MAX_LOG_BYTES = 4L * 1024 * 1024

        private const val PREFS = "tracker"

        /** The user's background-tracking switch (default on). Off: no job, no boot receiver. */
        fun isTracking(context: Context): Boolean =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("tracking", true)

        fun setTracking(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("tracking", on).commit()
            // The boot/update receiver is only needed to re-arm the job; disable it while off so
            // nothing of this app starts in the background at all.
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            if (on) schedule(context) else cancel(context)
        }

        /** Periodic 15-minute job, inexact, no constraints, survives reboots; only while tracking is on. */
        fun schedule(context: Context) {
            if (!isTracking(context)) { cancel(context); return }
            val js = context.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, SampleJob::class.java))
                    .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                    .setPersisted(true)
                    .build()
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
        }

        fun isScheduled(context: Context): Boolean =
            context.getSystemService(JobScheduler::class.java).getPendingJob(JOB_ID) != null
    }
}
