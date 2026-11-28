package app.trekmap.trail

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.plain.lang.PlainLang
import app.plain.lang.tr

/** Keeps the trail recorder alive with the screen off. Sticky: Android restarts it if it gets killed. */
class TrailService : Service() {
    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pending = Trail.restore(this)
        if (intent == null) Dev.event("trail", "service restarted by Android (sticky) · recording pending=$pending")
        if (!Trail.hasPermission(this)) {
            Dev.event("trail", "service start refused: no location permission")
            // Only reachable via a system restart (the app never starts us without permission);
            // such starts don't require startForeground, so stopping here is safe.
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startFg()
        } catch (e: Exception) {
            Dev.event("trail", "startForeground failed: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        // an exception here would crash-loop a sticky service: the recording stays safe on disk instead
        val attached = pending && try { Trail.attach(this) } catch (e: Exception) {
            Dev.event("trail", "attach failed: $e")
            try { Trail.detach() } catch (_: Exception) {}
            false
        }
        if (!attached) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startFg() {
        // the chronometer counts trail time (pauses left out); paused: no clock
        val start = if (Trail.startTime > 0) System.currentTimeMillis() - Trail.movingMs else System.currentTimeMillis()
        val paused = Trail.paused
        val n = Notification.Builder(this, Notif.TRAIL)
            .setSmallIcon(R.drawable.trail_ic_notif)
            .setContentTitle(if (paused) tr("Trail paused · GPS off", "Rota duraklatıldı · GPS kapalı") else tr("Recording trail", "Rota kaydediliyor"))
            .setContentText(if (paused) tr("Tap the button in the app to go on · hold it to stop", "Sürdürmek için uygulamadaki düğmeye dokunun · durdurmak için basılı tutun")
                else tr("Tap to open · tap the power button to pause, hold it to stop", "Açmak için dokunun · duraklatmak için düğmeye dokunun, durdurmak için basılı tutun"))
            .setUsesChronometer(!paused)
            .setWhen(start)
            .setShowWhen(!paused)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(Notif.openApp(this))
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(1, n)
    }

    override fun onDestroy() {
        Trail.detach()
        super.onDestroy()
    }
}
