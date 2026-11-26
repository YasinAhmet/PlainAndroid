package app.trekmap.trail

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.plain.lang.tr

object Notif {
    const val TRAIL = "trail"
    const val DL = "downloads"

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(TRAIL, tr("Trail recording", "Rota kaydı"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(DL, tr("Map downloads", "Harita indirmeleri"), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(NotificationChannel(WARN, tr("Trail warnings", "Rota uyarıları"), NotificationManager.IMPORTANCE_HIGH))
    }

    const val WARN = "warn"

    fun warn(ctx: Context, title: String, text: String) {
        try {
            ctx.getSystemService(NotificationManager::class.java).notify(
                3,
                android.app.Notification.Builder(ctx, WARN).setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle(title).setContentText(text)
                    .setStyle(android.app.Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openApp(ctx)).setAutoCancel(true).build(),
            )
        } catch (_: Exception) {}
    }

    fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0,
        Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
