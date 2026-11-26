package app.plain.battery

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The periodic job: one sample, plus a per-app snapshot about once an hour. */
class SampleJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        if (!Tracker.isTracking(this)) { Tracker.cancel(this); return false }
        Thread {
            try { Tracker(this).record("job") } catch (_: Exception) {}
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = false
}

/**
 * Re-arms the job after boot or an update and logs one cheap sample (no dumpsys). Disabled
 * (component state) while tracking is off.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Tracker.isTracking(context)) return
        Tracker.schedule(context)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            try { Tracker(context).record("boot", allowSnapshot = false) } catch (_: Exception) {}
        }
    }
}
