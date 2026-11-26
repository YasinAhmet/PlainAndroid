package app.localmusic

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.view.KeyEvent
import app.plain.lang.PlainLang
import app.plain.lang.tr

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PlainLang.init(this)
        Player.init(this)
    }
}

/**
 * Foreground service that keeps the process alive while music plays and shows the media notification
 * (which Android also turns into the lock-screen / quick-settings player). It does no work of its own.
 *
 * It runs only while playing (or paused for a moment by a call). On a real pause it leaves the foreground and stops
 * itself; the paused notification stays (detached, swipe-able) and its buttons are broadcasts, so it still works after
 * the process was reclaimed. Everything goes through [sync], called by Player on each state change (never on a timer).
 */
class PlaybackService : Service() {
    private var foreground = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
        Player.init(this)
        instance = this
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Playback", "Çalma"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        // startForegroundService() requires startForeground() even if playback was paused meanwhile.
        goForeground(build(this))
        sync(this)
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (_: Exception) {
            // Background start not allowed right now: keep playing as a normal service with a notification.
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
    }

    /** Paused: out of the foreground (notification kept, detached) and stop. Nothing of ours keeps running. */
    private fun leave(removeNotification: Boolean) {
        if (foreground || removeNotification) stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        foreground = false
        if (!stopping) { stopping = true; stopSelf() }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Player.playing) Player.stop()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val ID = 1
        var instance: PlaybackService? = null
            private set

        /** Bring service + notification in line with the player: foreground while playing, gone when paused or stopped. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java)
            val s = instance
            if (Player.current == null || !Player.session.isActive) { // stopped: no notification at all
                s?.leave(removeNotification = true)
                nm.cancel(ID)
                return
            }
            if (Player.holdsForeground) {
                if (s != null && !s.stopping) s.goForeground(build(app))
                else try {
                    app.startForegroundService(Intent(app, PlaybackService::class.java))
                } catch (_: Exception) {
                    nm.notify(ID, build(app)) // not allowed from the background right now: plays without the service
                }
            } else {
                s?.leave(removeNotification = false)
                nm.notify(ID, build(app)) // paused notification: still updated (next/prev) while the service is gone
            }
        }

        private fun action(c: Context, icon: Int, title: String, act: String) =
            Notification.Action.Builder(Icon.createWithResource(c, icon), title, ControlReceiver.intent(c, act)).build()

        private fun build(c: Context): Notification {
            val s = Player.current
            val playing = Player.playing
            return Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(s?.title ?: "LocalMusic")
                .setContentText(s?.subtitle ?: "")
                .setLargeIcon(Player.art)
                .setContentIntent(Player.openAppIntent(c))
                .setDeleteIntent(ControlReceiver.intent(c, ControlReceiver.STOP))
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .addAction(action(c, R.drawable.ic_prev, tr("Previous", "Önceki"), ControlReceiver.PREV))
                .addAction(if (playing) action(c, R.drawable.ic_pause, tr("Pause", "Duraklat"), ControlReceiver.TOGGLE) else action(c, R.drawable.ic_play, tr("Play", "Oynat"), ControlReceiver.TOGGLE))
                .addAction(action(c, R.drawable.ic_next, tr("Next", "Sonraki"), ControlReceiver.NEXT))
                .setStyle(Notification.MediaStyle().setMediaSession(Player.session.sessionToken).setShowActionsInCompactView(0, 1, 2))
                .build()
        }
    }
}

/** Notification buttons. */
class ControlReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PlainLang.init(c)
        Player.init(c)
        when (i.action) {
            TOGGLE -> Player.toggle()
            NEXT -> Player.next()
            PREV -> Player.prev()
            STOP -> if (!Player.playing) Player.stop()
        }
    }

    companion object {
        const val TOGGLE = "toggle"
        const val NEXT = "next"
        const val PREV = "prev"
        const val STOP = "stop"

        fun intent(c: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            c, action.hashCode(), Intent(c, ControlReceiver::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** Headset / Bluetooth buttons when our session isn't active (Android routes them to the last media app). */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action != Intent.ACTION_MEDIA_BUTTON) return
        val ev = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        if (ev == null || ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return
        PlainLang.init(c)
        Player.init(c)
        Player.handleKey(ev.keyCode)
    }
}
