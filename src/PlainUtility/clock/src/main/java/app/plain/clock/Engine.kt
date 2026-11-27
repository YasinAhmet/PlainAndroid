package app.plain.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.text.SimpleDateFormat
import java.util.Date

/**
 * The Clock tab's state and everything that happens while the app is closed. Nothing ticks in the background:
 * a running pomodoro or timer is one exact alarm at its end time ([ClockReceiver]), and the running notification's
 * countdown is drawn by Android itself (a chronometer), so the app wakes only when a phase or timer ends.
 * State is a few strings in prefs "clock"; every change goes through here and tells the open screen via [listener].
 */
object Engine {
    const val POMO = "pomo"
    const val TIMER = "timer"
    const val WATCH = "watch"

    const val CH_RUN = "clock_running"
    const val CH_TIMER = "clock_timer_done"
    const val CH_POMO = "clock_pomodoro"

    const val N_POMO = 1
    const val N_TIMER = 2
    const val N_WATCH = 3
    const val N_TIMER_DONE = 11
    const val N_POMO_DONE = 12

    /** The open Clock screen (main thread). */
    var listener: (() -> Unit)? = null

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences("clock", Context.MODE_PRIVATE)
    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ state

    fun config(c: Context): PomoConfig = prefs(c).let {
        PomoConfig(it.getInt("work", 25), it.getInt("short", 5), it.getInt("long", 15), it.getInt("every", 4), it.getBoolean("auto", true))
    }

    fun setConfig(c: Context, cfg: PomoConfig) {
        prefs(c).edit().putInt("work", cfg.work).putInt("short", cfg.short).putInt("long", cfg.long)
            .putInt("every", cfg.every).putBoolean("auto", cfg.auto).apply()
        // a pomodoro that hasn't started yet shows the new length at once
        val cd = pomoCd(c)
        if (cd.idle || (cd.paused && cd.left == cd.total)) save(c, POMO, Countdown.ready(cfg.ms(pomo(c).phase)))
        changed(c)
    }

    fun pomoCd(c: Context) = Countdown.decode(prefs(c).getString("pomo_cd", null))
    fun pomo(c: Context) = Pomo.decode(prefs(c).getString("pomo", null))
    fun timerCd(c: Context) = Countdown.decode(prefs(c).getString("timer_cd", null))
    fun watch(c: Context) = Stopwatch.decode(prefs(c).getString("watch", null))
    /** The last timer length, offered again. */
    fun lastTimer(c: Context) = prefs(c).getLong("timer_last", 5 * 60_000L)
    fun ringing(c: Context) = prefs(c).getBoolean("ringing", false)

    /** Focus sessions finished today. */
    fun today(c: Context): Int = prefs(c).let { if (it.getString("pomo_day", "") == Fmt.day(now())) it.getInt("pomo_count", 0) else 0 }

    private fun save(c: Context, which: String, cd: Countdown) {
        prefs(c).edit().putString(if (which == POMO) "pomo_cd" else "timer_cd", cd.encode()).apply()
    }

    private fun changed(c: Context) {
        refreshNotifications(c)
        listener?.invoke()
    }

    // ------------------------------------------------------------------ pomodoro

    fun pomoStart(c: Context) {
        val cd = pomoCd(c)
        val next = when {
            cd.paused -> cd.resume(now())
            cd.idle -> Countdown.start(config(c).ms(pomo(c).phase), now())
            else -> return
        }
        save(c, POMO, next)
        schedule(c, POMO, next.endAt)
        changed(c)
    }

    fun pomoPause(c: Context) {
        val cd = pomoCd(c)
        if (!cd.running) return
        save(c, POMO, cd.pause(now()))
        cancelAlarm(c, POMO)
        changed(c)
    }

    /** Ends this phase now and goes to the next one (stopped, ready to start; a skipped focus session isn't counted). */
    fun pomoSkip(c: Context) {
        val cfg = config(c)
        val p = pomo(c).next(cfg)
        prefs(c).edit().putString("pomo", p.encode()).apply()
        val wasRunning = pomoCd(c).running
        cancelAlarm(c, POMO)
        val cd = if (wasRunning) Countdown.start(cfg.ms(p.phase), now()) else Countdown.ready(cfg.ms(p.phase))
        save(c, POMO, cd)
        if (cd.running) schedule(c, POMO, cd.endAt)
        changed(c)
    }

    /** Back to the start of a cycle: focus 1, nothing running. */
    fun pomoReset(c: Context) {
        cancelAlarm(c, POMO)
        prefs(c).edit().putString("pomo", Pomo().encode()).remove("pomo_cd").apply()
        cancel(c, N_POMO_DONE)
        changed(c)
    }

    // ------------------------------------------------------------------ timer

    fun timerStart(c: Context, ms: Long) {
        silence(c)
        val cd = Countdown.start(ms, now())
        prefs(c).edit().putLong("timer_last", ms).apply()
        save(c, TIMER, cd)
        schedule(c, TIMER, cd.endAt)
        changed(c)
    }

    fun timerPause(c: Context) {
        val cd = timerCd(c)
        if (!cd.running) return
        save(c, TIMER, cd.pause(now()))
        cancelAlarm(c, TIMER)
        changed(c)
    }

    fun timerResume(c: Context) {
        val cd = timerCd(c)
        if (!cd.paused) return
        val r = cd.resume(now())
        save(c, TIMER, r)
        schedule(c, TIMER, r.endAt)
        changed(c)
    }

    fun timerAdd(c: Context, ms: Long) {
        val cd = timerCd(c)
        if (cd.idle) { timerStart(c, ms); return }
        val r = cd.add(ms, now())
        save(c, TIMER, r)
        if (r.running) schedule(c, TIMER, r.endAt)
        changed(c)
    }

    fun timerReset(c: Context) {
        cancelAlarm(c, TIMER)
        prefs(c).edit().remove("timer_cd").apply()
        silence(c)
        changed(c)
    }

    /** Stops the "time's up" sound. */
    fun silence(c: Context) {
        if (!ringing(c)) return
        prefs(c).edit().putBoolean("ringing", false).apply()
        cancel(c, N_TIMER_DONE)
        listener?.invoke()
    }

    // ------------------------------------------------------------------ stopwatch

    private fun saveWatch(c: Context, w: Stopwatch) {
        prefs(c).edit().putString("watch", w.encode()).apply()
        changed(c)
    }

    fun watchToggle(c: Context) = watch(c).let { saveWatch(c, if (it.running) it.pause(now()) else it.start(now())) }
    fun watchLap(c: Context) = watch(c).let { if (it.running) saveWatch(c, it.lap(now())) }
    fun watchReset(c: Context) = saveWatch(c, Stopwatch())

    // ------------------------------------------------------------------ alarms

    /** The alarm for [which] went off (or the phone restarted and its end time has passed). */
    fun onEnd(c: Context, which: String) {
        val cd = if (which == POMO) pomoCd(c) else timerCd(c)
        if (!cd.running) return
        val t = now()
        if (cd.endAt > t + 1500) { schedule(c, which, cd.endAt); return }   // early (shouldn't happen): wait on
        if (which == TIMER) {
            prefs(c).edit().remove("timer_cd").putBoolean("ringing", true).apply()
            notifyTimerDone(c, cd.total)
        } else {
            val cfg = config(c)
            val p = pomo(c)
            if (p.phase == Phase.WORK) {
                val e = prefs(c).edit()
                val day = Fmt.day(t)
                e.putInt("pomo_count", today(c) + 1).putString("pomo_day", day).apply()
            }
            val n = p.next(cfg)
            prefs(c).edit().putString("pomo", n.encode()).apply()
            val next = if (cfg.auto) Countdown.start(cfg.ms(n.phase), t) else Countdown.ready(cfg.ms(n.phase))
            save(c, POMO, next)
            if (next.running) schedule(c, POMO, next.endAt)
            notifyPhase(c, p.phase, n, cfg)
        }
        changed(c)
    }

    /** After a restart or an update: alarms and notifications are gone, so set them again (ends that passed fire now). */
    fun restore(c: Context) {
        for (w in listOf(POMO, TIMER)) {
            val cd = if (w == POMO) pomoCd(c) else timerCd(c)
            if (!cd.running) continue
            if (cd.endAt <= now()) onEnd(c, w) else schedule(c, w, cd.endAt)
        }
        if (ringing(c)) prefs(c).edit().putBoolean("ringing", false).apply()
        refreshNotifications(c)
    }

    private fun alarmPi(c: Context, which: String) = PendingIntent.getBroadcast(c, if (which == POMO) 100 else 101,
        Intent(c, ClockReceiver::class.java).setAction(ClockReceiver.END).putExtra(ClockReceiver.WHICH, which),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(c: Context, which: String, at: Long) {
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPi(c, which)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(c: Context, which: String) {
        c.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(c, which))
    }

    // ------------------------------------------------------------------ notifications

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun cancel(c: Context, id: Int) { nm(c)?.cancel(id) }

    /** False when Android won't show our notifications (then timers can't ring). */
    fun canNotify(c: Context) = nm(c)?.areNotificationsEnabled() != false

    fun channels(c: Context) {
        val nm = nm(c) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RUN, tr("Running timers", "Çalışan sayaçlar"), NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null); setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_TIMER, tr("Timer done", "Sayaç bitti"), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 500, 400, 500)
            setBypassDnd(true)
        })
        nm.createNotificationChannel(NotificationChannel(CH_POMO, tr("Pomodoro", "Pomodoro"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        })
    }

    /** Opens the Clock tab (through the app's tab forwarder, so it lands in the app's one task). */
    private fun openPi(c: Context) = PendingIntent.getActivity(c, 0,
        Intent().setClassName(c.packageName, "app.plain.hub.OpenClock").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun actionPi(c: Context, action: String, which: String, code: Int) = PendingIntent.getBroadcast(c, code,
        Intent(c, ClockReceiver::class.java).setAction(action).putExtra(ClockReceiver.WHICH, which),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun act(c: Context, label: String, action: String, which: String, code: Int) =
        Notification.Action.Builder(null, label, actionPi(c, action, which, code)).build()

    private fun base(c: Context, ch: String) = Notification.Builder(c, ch)
        .setSmallIcon(R.drawable.clk_ic_timer)
        .setColor(C.ACCENT)
        .setContentIntent(openPi(c))

    private val hm get() = SimpleDateFormat("HH:mm", PlainLang.locale)

    /** The running/paused notifications for pomodoro, timer and stopwatch (none when idle). */
    fun refreshNotifications(c: Context) {
        PlainLang.init(c)
        channels(c)
        val nm = nm(c) ?: return
        val t = now()

        val pc = pomoCd(c)
        if (pc.idle || (pc.paused && pc.left == pc.total)) nm.cancel(N_POMO) else {
            val p = pomo(c)
            val cfg = config(c)
            val title = if (p.phase == Phase.WORK) tr("Focus · ${p.round + 1} of ${cfg.every}", "Odak · ${p.round + 1} / ${cfg.every}") else p.phase.label
            val b = base(c, CH_RUN).setOngoing(true).setOnlyAlertOnce(true).setContentTitle(title)
            if (pc.running) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(pc.endAt).setShowWhen(true)
                    .setContentText(tr("Until ${hm.format(Date(pc.endAt))}", "Bitiş ${hm.format(Date(pc.endAt))}"))
                    .addAction(act(c, tr("Pause", "Duraklat"), ClockReceiver.PAUSE, POMO, 1))
            } else {
                b.setShowWhen(false).setContentText(tr("Paused · ${Fmt.countdown(pc.left)} left", "Duraklatıldı · ${Fmt.countdown(pc.left)} kaldı"))
                    .addAction(act(c, tr("Resume", "Sürdür"), ClockReceiver.RESUME, POMO, 2))
            }
            b.addAction(act(c, tr("Skip", "Atla"), ClockReceiver.SKIP, POMO, 3))
            b.addAction(act(c, tr("Stop", "Durdur"), ClockReceiver.STOP, POMO, 4))
            nm.notify(N_POMO, b.build())
        }

        val tc = timerCd(c)
        if (tc.idle) nm.cancel(N_TIMER) else {
            val b = base(c, CH_RUN).setOngoing(true).setOnlyAlertOnce(true)
                .setContentTitle(tr("Timer · ${Fmt.length(tc.total)}", "Sayaç · ${Fmt.length(tc.total)}"))
            if (tc.running) {
                b.setUsesChronometer(true).setChronometerCountDown(true).setWhen(tc.endAt).setShowWhen(true)
                    .setContentText(tr("Rings at ${hm.format(Date(tc.endAt))}", "Çalma ${hm.format(Date(tc.endAt))}"))
                    .addAction(act(c, tr("Pause", "Duraklat"), ClockReceiver.PAUSE, TIMER, 11))
            } else {
                b.setShowWhen(false).setContentText(tr("Paused · ${Fmt.countdown(tc.left)} left", "Duraklatıldı · ${Fmt.countdown(tc.left)} kaldı"))
                    .addAction(act(c, tr("Resume", "Sürdür"), ClockReceiver.RESUME, TIMER, 12))
            }
            b.addAction(act(c, tr("+1 min", "+1 dk"), ClockReceiver.ADD, TIMER, 13))
            b.addAction(act(c, tr("Stop", "Durdur"), ClockReceiver.STOP, TIMER, 14))
            nm.notify(N_TIMER, b.build())
        }

        val w = watch(c)
        if (w.idle) nm.cancel(N_WATCH) else {
            val b = base(c, CH_RUN).setOngoing(w.running).setOnlyAlertOnce(true).setContentTitle(tr("Stopwatch", "Kronometre"))
            if (w.running) {
                b.setUsesChronometer(true).setWhen(t - w.elapsed(t)).setShowWhen(true)
                    .addAction(act(c, tr("Pause", "Duraklat"), ClockReceiver.PAUSE, WATCH, 21))
                    .addAction(act(c, tr("Lap", "Tur"), ClockReceiver.ADD, WATCH, 22))
            } else {
                b.setShowWhen(false).setContentText(tr("Paused · ${Fmt.stopwatch(w.acc)}", "Duraklatıldı · ${Fmt.stopwatch(w.acc)}"))
                    .addAction(act(c, tr("Resume", "Sürdür"), ClockReceiver.RESUME, WATCH, 23))
                    .addAction(act(c, tr("Reset", "Sıfırla"), ClockReceiver.STOP, WATCH, 24))
            }
            nm.notify(N_WATCH, b.build())
        }
    }

    private fun notifyTimerDone(c: Context, total: Long) {
        PlainLang.init(c)
        channels(c)
        val n = base(c, CH_TIMER)
            .setContentTitle(tr("Time's up", "Süre doldu"))
            .setContentText(tr("Timer · ${Fmt.length(total)}", "Sayaç · ${Fmt.length(total)}"))
            .setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setTimeoutAfter(5 * 60_000L)   // never rings for ever
            .setDeleteIntent(actionPi(c, ClockReceiver.SILENCE, TIMER, 15))
            .addAction(act(c, tr("Stop", "Durdur"), ClockReceiver.SILENCE, TIMER, 16))
            .addAction(act(c, tr("+1 min", "+1 dk"), ClockReceiver.ADD, TIMER, 17))
            .build()
        n.flags = n.flags or Notification.FLAG_INSISTENT   // the alarm sound repeats until Stop
        nm(c)?.notify(N_TIMER_DONE, n)
    }

    private fun notifyPhase(c: Context, ended: Phase, next: Pomo, cfg: PomoConfig) {
        PlainLang.init(c)
        channels(c)
        val title = if (ended == Phase.WORK) tr("Focus done", "Odak bitti") else tr("Break over", "Mola bitti")
        val len = tr("${cfg.minutes(next.phase)} min", "${cfg.minutes(next.phase)} dk")
        val text = if (cfg.auto) tr("${next.phase.label} started · $len", "${next.phase.label} başladı · $len")
            else tr("Next: ${next.phase.label} · $len. Tap Start when ready.", "Sıradaki: ${next.phase.label} · $len. Hazır olunca Başlat'a dokunun.")
        val b = base(c, CH_POMO).setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
        if (!cfg.auto) b.addAction(act(c, tr("Start", "Başlat"), ClockReceiver.RESUME, POMO, 5))
        nm(c)?.notify(N_POMO_DONE, b.build())
    }
}
