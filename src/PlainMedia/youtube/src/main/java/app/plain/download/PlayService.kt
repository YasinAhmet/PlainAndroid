package app.plain.download

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
import android.os.IBinder
import app.plain.lang.PlainLang
import app.plain.lang.tr

/**
 * 2.4 background playback (pattern from LocalMusic's PlaybackService). A mediaPlayback foreground service that does
 * no work of its own: it keeps the process alive and shows the media notification (Android also turns it into the
 * lock-screen / quick-settings player) while the player in [PlayerPanel] plays. Foreground only while playing (or
 * about to: buffering, the next queue item, the audio/video switch). On pause it leaves the foreground and stops at
 * once; the paused notification stays (swipe-able) and its buttons still work. Close stops everything.
 * Everything goes through [sync], called by the player on state changes (never on a timer).
 */
class PlayService : Service() {
    private var foreground = false
    private var stopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
        instance = this
        channel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopping = false
        goForeground(build(this)) // startForegroundService() requires startForeground() even if paused meanwhile
        sync(this)
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        try {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
        } catch (_: Exception) {
            getSystemService(NotificationManager::class.java).notify(ID, n)
        }
    }

    private fun leave(removeNotification: Boolean) {
        if (foreground || removeNotification) stopForeground(if (removeNotification) STOP_FOREGROUND_REMOVE else STOP_FOREGROUND_DETACH)
        foreground = false
        if (!stopping) { stopping = true; stopSelf() }
    }

    /** Swiped out of recents: a paused player closes; a playing one goes on (until Close or the activity goes). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = PlayerPanel.current
        if (p != null && !p.wantsPlay) PlayerPanel.uiMain.post { p.close() }
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val CHANNEL = "playback"
        const val ID = 3 // DownloadService uses 1 (progress) and 2 (done)

        var instance: PlayService? = null
            private set

        fun channel(c: Context) {
            c.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Playback", "Oynatma"), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false); setSound(null, null); enableVibration(false)
                },
            )
        }

        /** Service + notification in line with the player: foreground while playing, detached when paused, gone when closed. */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val nm = app.getSystemService(NotificationManager::class.java)
            val s = instance
            val p = PlayerPanel.current
            if (p == null || !p.notifying) {
                s?.leave(removeNotification = true)
                nm.cancel(ID)
                return
            }
            if (p.wantsPlay) {
                if (s != null && !s.stopping) s.goForeground(build(app))
                else try { app.startForegroundService(Intent(app, PlayService::class.java)) } catch (_: Exception) {
                    nm.notify(ID, build(app)) // not allowed from the background right now: plays on without the service
                }
            } else {
                s?.leave(removeNotification = false)
                nm.notify(ID, build(app))
            }
        }

        private fun action(c: Context, icon: Int, title: String, cmd: String) =
            Notification.Action.Builder(Icon.createWithResource(c, icon), title, PlayControl.intent(c, cmd)).build()

        private fun build(c: Context): Notification {
            val p = PlayerPanel.current
            val playing = p?.wantsPlay == true
            val b = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.yt_ic_notif)
                .setContentTitle(p?.nowTitle ?: "Plain Youtube")
                .setContentText(p?.nowChannel ?: "")
                .setLargeIcon(p?.cover)
                .setContentIntent(PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE))
                .setDeleteIntent(PlayControl.intent(c, PlayControl.DISMISS))
                .setOngoing(playing)
                .setShowWhen(false)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_TRANSPORT)
            val acts = ArrayList<Notification.Action>()
            if (p?.inQueue == true) acts += action(c, R.drawable.yt_ic_prev, tr("Previous", "Önceki"), PlayControl.PREV)
            acts += action(c, R.drawable.yt_ic_back10, tr("Back 10 s", "10 sn geri"), PlayControl.BACK)
            val play = acts.size // the play/pause button (found by position, not by its translated title)
            acts += if (playing) action(c, R.drawable.yt_ic_pause, tr("Pause", "Duraklat"), PlayControl.TOGGLE) else action(c, R.drawable.yt_ic_play, tr("Play", "Oynat"), PlayControl.TOGGLE)
            acts += action(c, R.drawable.yt_ic_fwd10, tr("Forward 10 s", "10 sn ileri"), PlayControl.FWD)
            if (p?.inQueue == true) acts += action(c, R.drawable.yt_ic_next, tr("Next", "Sonraki"), PlayControl.NEXT)
            acts += action(c, R.drawable.yt_ic_close, tr("Close", "Kapat"), PlayControl.CLOSE)
            acts.forEach { b.addAction(it) }
            val style = Notification.MediaStyle().setShowActionsInCompactView(play - 1, play, play + 1)
            p?.sessionToken?.let { style.setMediaSession(it) }
            b.setStyle(style)
            return b.build()
        }
    }
}

/** Notification buttons (explicit broadcasts to this app only). */
class PlayControl : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        // the process may have been restarted since the notification was posted: rebuild the player from its saved state
        PlainLang.init(c)
        Store.init(c); Account.init(c)
        val cmd = i.action.orEmpty()
        PlayerPanel.uiMain.post {
            val p = PlayerPanel.get(c)
            if (!p.restoreForControls()) { c.getSystemService(NotificationManager::class.java).cancel(PlayService.ID); return@post }
            p.command(cmd)
        }
    }

    companion object {
        const val TOGGLE = "toggle"; const val NEXT = "next"; const val PREV = "prev"; const val BACK = "back"; const val FWD = "fwd"
        const val CLOSE = "close"; const val DISMISS = "dismiss"

        fun intent(c: Context, cmd: String): PendingIntent = PendingIntent.getBroadcast(
            c, cmd.hashCode(), Intent(c, PlayControl::class.java).setAction(cmd), PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
