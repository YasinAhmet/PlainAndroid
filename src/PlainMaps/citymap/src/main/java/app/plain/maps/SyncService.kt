package app.plain.maps

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
import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** What the sync is doing, for the settings sheet (listeners are called on the main thread). */
object SyncState {
    @Volatile var running = false
    @Volatile var progress = 0f
    @Volatile var text = ""
    @Volatile var error: String? = null
    /** The running (or last) sync was the situational one. */
    @Volatile var situational = false
    /** Set when a sync finished and installed new data (the app reopens its database). */
    @Volatile var generation = 0

    private val main = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<() -> Unit>()

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun changed() = main.post { listeners.toList().forEach { it() } }
}

/**
 * Runs a sync as a foreground service, so it survives the screen turning off.
 * It holds a wake lock and a Wi-Fi lock only while the sync runs, then stops itself.
 */
/** Istanbul's sync (main process); [AnkaraSyncService] is the same in the :ankara process. */
open class SyncService : Service() {
    companion object {
        private const val CH = "sync"
        private const val ACTION_CANCEL = "cancel"
        private val cancel = AtomicBoolean(false)

        /** [situational] = only İETT announcements and today's planned times, not the whole map. */
        fun start(ctx: Context, situational: Boolean = false) {
            if (SyncState.running) return
            ctx.startForegroundService(Intent(ctx, City.syncService).putExtra("situational", situational))
        }

        fun stop() { cancel.set(true) }
    }

    private var lastNote = 0L
    private var lastLogged = ""

    /** A plain-text log of every sync (Android/data/app.plain.maps/files/sync.log or sync-ankara.log), readable over adb. */
    private fun log(s: String) {
        try {
            val f = java.io.File(getExternalFilesDir(null), City.logName)
            if (f.length() > 512 * 1024) f.delete()
            f.appendText(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "  " + s + "\n")
        } catch (_: Exception) {
        }
    }

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { cancel.set(true); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, tr("Sync", "Eşitleme"), NotificationManager.IMPORTANCE_LOW))
        val n = note(tr("Starting…", "Başlıyor…"), 0f)
        if (Build.VERSION.SDK_INT >= 29) startForeground(City.notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(City.notifId, n)
        if (SyncState.running) return START_NOT_STICKY
        SyncState.running = true
        SyncState.error = null
        SyncState.progress = 0f
        SyncState.text = tr("Starting…", "Başlıyor…")
        SyncState.changed()
        log(if (intent?.getBooleanExtra("situational", false) == true) "situational sync started" else "sync started")
        cancel.set(false)

        val pm = getSystemService(PowerManager::class.java)
        val wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PlainMaps:sync").apply { acquire(3 * 3600_000L) }
        @Suppress("DEPRECATION")
        val wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PlainMaps:sync").apply { acquire() }

        val situational = intent?.getBooleanExtra("situational", false) == true
        SyncState.situational = situational
        thread(name = "sync") {
            try {
                val report: (Float, String) -> Unit = { f, s ->
                    SyncState.progress = f
                    SyncState.text = s
                    SyncState.changed()
                    val key = s.replace(Regex("\\(\\d+ MB\\)"), "")
                    if (key != lastLogged) { lastLogged = key; log("${(f * 100).toInt()}%  $s") }
                    val now = System.currentTimeMillis()
                    if (now - lastNote > 1500) { lastNote = now; nm.notify(City.notifId, note(s, f)) }
                }
                if (situational) Situational(applicationContext, cancel, report).run()
                else Sync(applicationContext, cancel, report).run()
                SyncState.generation++
                log("done")
            } catch (_: InterruptedException) {
                SyncState.text = tr("Stopped. Progress is kept for the next sync.", "Durduruldu. İlerleme bir sonraki eşitleme için saklanır.")
                log("stopped")
            } catch (t: Throwable) {
                SyncState.error = t.message ?: t.javaClass.simpleName
                SyncState.text = tr("Sync failed", "Eşitleme başarısız")
                log("FAILED: " + android.util.Log.getStackTraceString(t))
                try {
                    java.io.File(City.files(this), "sync/last-error.txt").writeText(android.util.Log.getStackTraceString(t))
                } catch (_: Exception) {
                }
            } finally {
                SyncState.running = false
                SyncState.changed()
                try { if (wake.isHeld) wake.release() } catch (_: Exception) {}
                try { if (wifiLock.isHeld) wifiLock.release() } catch (_: Exception) {}
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun note(text: String, f: Float): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, City.activity), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, javaClass).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH)
            .setSmallIcon(R.drawable.map_ic_notif)
            .setContentTitle(tr("Plain Maps: ${City.localName} sync", "Plain Maps: ${City.localName} eşitlemesi"))
            .setContentText(text)
            .setProgress(1000, (f * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, tr("Stop", "Durdur"), stop).build())
            .build()
    }
}
