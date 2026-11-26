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
        if (!pending || !Trail.attach(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startFg() {
        val start = if (Trail.startTime > 0) Trail.startTime else System.currentTimeMillis()
        val n = Notification.Builder(this, Notif.TRAIL)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(tr("Recording trail", "Rota kaydediliyor"))
            .setContentText(tr("Tap to open · hold the power button in the app to stop", "Açmak için dokunun · durdurmak için uygulamadaki düğmeye basılı tutun"))
            .setUsesChronometer(true)
            .setWhen(start)
            .setShowWhen(true)
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
