package app.plain.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.plain.lang.PlainLang

/** A pomodoro phase or timer ended (exact alarm), or a button on a Clock notification was tapped. Not exported. */
class ClockReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        PlainLang.init(c)
        val w = i.getStringExtra(WHICH) ?: return
        when (i.action) {
            END -> Engine.onEnd(c, w)
            PAUSE -> when (w) {
                Engine.POMO -> Engine.pomoPause(c)
                Engine.TIMER -> Engine.timerPause(c)
                Engine.WATCH -> Engine.watchToggle(c)
            }
            RESUME -> when (w) {
                Engine.POMO -> Engine.pomoStart(c)
                Engine.TIMER -> Engine.timerResume(c)
                Engine.WATCH -> Engine.watchToggle(c)
            }
            SKIP -> Engine.pomoSkip(c)
            STOP -> when (w) {
                Engine.POMO -> Engine.pomoReset(c)
                Engine.TIMER -> Engine.timerReset(c)
                Engine.WATCH -> Engine.watchReset(c)
            }
            ADD -> when (w) {
                Engine.TIMER -> Engine.timerAdd(c, 60_000)
                Engine.WATCH -> Engine.watchLap(c)
            }
            SILENCE -> Engine.silence(c)
        }
    }

    companion object {
        const val WHICH = "which"
        const val END = "app.plain.clock.END"
        const val PAUSE = "app.plain.clock.PAUSE"
        const val RESUME = "app.plain.clock.RESUME"
        const val SKIP = "app.plain.clock.SKIP"
        const val STOP = "app.plain.clock.STOP"
        const val ADD = "app.plain.clock.ADD"
        const val SILENCE = "app.plain.clock.SILENCE"
    }
}

/**
 * After a restart or an app update: sets the running pomodoro/timer alarms and notifications again. When the time or
 * the time zone is changed: keeps running timers at their real time left (see [Engine.onClockChanged]).
 */
class ClockBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { PlainLang.init(c); Engine.restore(c) }
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> { PlainLang.init(c); Engine.onClockChanged(c) }
        }
    }
}
