package app.plain.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.util.concurrent.Executors

/** What the recorder is doing. */
enum class RecState { IDLE, STARTING, RECORDING, PAUSED, SAVING }

/**
 * Records one voice memo: AAC in MPEG-4 (.m4a), mono, 44.1 kHz, 96 kbps, straight into a pending media-store entry
 * that is published when recording stops (and removed when it fails).
 *
 * A microphone foreground service, so recording goes on with the screen off or in another tab. It runs only while
 * recording: started by the Record button, it stops itself once the file is saved. The ongoing notification shows
 * the time (drawn by the system, no timers here) and a Stop button. Its state lives in the companion, read by
 * [VoiceActivity] on the main thread.
 */
class RecorderService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var recorder: MediaRecorder? = null
    private var file: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        PlainLang.init(this)
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_PAUSE -> pause()
            ACTION_RESUME -> resume()
            ACTION_STOP -> stop()
            else -> if (recorder == null && state != RecState.SAVING) quit()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // the system ends us while recording (rare): keep what was recorded
        if (recorder != null) {
            val u = finishRecorder()
            if (u != null && !(okStop && Recordings.publish(this, u))) Recordings.discard(this, u)
            setState(RecState.IDLE)
        }
        io.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ recording

    private fun start() {
        // a startForegroundService must be answered with startForeground, even when recording then fails
        if (!goForeground()) { fail(tr("Couldn't start recording.", "Kayıt başlatılamadı.")); return }
        if (recorder != null) return
        val u = Recordings.create(this, System.currentTimeMillis())
            ?: return fail(tr("Couldn't create the file.", "Dosya oluşturulamadı."))
        var r: MediaRecorder? = null
        try {
            val fd = contentResolver.openFileDescriptor(u, "rw") ?: throw IllegalStateException("no file")
            file = fd
            r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setOutputFile(fd.fileDescriptor)
            r.setOnErrorListener { _, _, _ -> main.post { stop() } }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            try { r?.release() } catch (_: Exception) {}
            try { file?.close() } catch (_: Exception) {}
            file = null
            Recordings.discard(this, u)
            return fail(tr("Couldn't start recording. Is another app using the microphone?",
                "Kayıt başlatılamadı. Mikrofonu başka bir uygulama mı kullanıyor?"))
        }
        recorder = r
        uri = u
        accumulated = 0
        since = SystemClock.elapsedRealtime()
        setState(RecState.RECORDING)
        notifyNow()
    }

    private fun pause() {
        val r = recorder ?: return
        if (state != RecState.RECORDING) return
        try { r.pause() } catch (_: Exception) { return }
        accumulated += SystemClock.elapsedRealtime() - since
        setState(RecState.PAUSED)
        notifyNow()
    }

    private fun resume() {
        val r = recorder ?: return
        if (state != RecState.PAUSED) return
        try { r.resume() } catch (_: Exception) { return }
        since = SystemClock.elapsedRealtime()
        setState(RecState.RECORDING)
        notifyNow()
    }

    private var okStop = false

    /** Stops and releases the recorder and closes the file; returns the entry ([okStop] says if it's usable). */
    private fun finishRecorder(): Uri? {
        val r = recorder ?: return null
        recorder = null
        okStop = try { r.stop(); true } catch (_: Exception) { false }  // throws when nothing was recorded
        try { r.release() } catch (_: Exception) {}
        try { file?.close() } catch (_: Exception) {}
        file = null
        val u = uri
        uri = null
        return u
    }

    private fun stop() {
        if (recorder == null) { if (state != RecState.SAVING) quit(); return }
        val length = elapsed()
        accumulated = length   // the clock holds still while saving
        val u = finishRecorder()
        val ok = okStop
        setState(RecState.SAVING)
        val app = applicationContext
        io.execute {
            val saved = u != null && ok && Recordings.publish(app, u)
            if (!saved && u != null) Recordings.discard(app, u)
            main.post {
                val msg = when {
                    saved -> tr("Saved.", "Kaydedildi.")
                    length < 1500 -> tr("Too short, nothing saved.", "Çok kısa, kaydedilmedi.")
                    else -> tr("Recording failed, nothing saved.", "Kayıt başarısız, kaydedilmedi.")
                }
                setState(RecState.IDLE, msg, saved)
                quit()
            }
        }
    }

    private fun fail(msg: String) {
        setState(RecState.IDLE, msg, false)
        quit()
    }

    private fun quit() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ------------------------------------------------------------------ notification

    private fun goForeground(): Boolean = try {
        startForeground(NOTE_ID, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        true
    } catch (_: Exception) {
        false   // no microphone permission (Android 14 checks it here) or not allowed from the background
    }

    private fun notifyNow() {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, note()) } catch (_: Exception) {}
    }

    private fun note(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm?.getNotificationChannel(CHANNEL) == null) nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, tr("Voice recording", "Ses kaydı"), NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            })
        val open = Intent().setClassName(packageName, VoiceActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        val openPi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPi = PendingIntent.getService(this, 1, Intent(this, RecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val paused = state == RecState.PAUSED
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.voc_ic_mic)
            .setColor(ACCENT)
            .setContentTitle(if (paused) tr("Paused", "Duraklatıldı") else tr("Recording…", "Kayıt yapılıyor…"))
            .setContentText(if (paused) Format.clock(elapsed()) else tr("Plain Camera · Record Voice", "Plain Camera · Ses Kaydı"))
            .setContentIntent(openPi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.voc_ic_stop), tr("Stop", "Durdur"), stopPi).build())
        if (!paused) {
            // the system's own chronometer counts on screen; nothing ticks in this app
            b.setUsesChronometer(true).setShowWhen(true).setWhen(System.currentTimeMillis() - elapsed())
        } else b.setShowWhen(false)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    companion object {
        private const val ACTION_START = "app.plain.voice.START"
        private const val ACTION_PAUSE = "app.plain.voice.PAUSE"
        private const val ACTION_RESUME = "app.plain.voice.RESUME"
        private const val ACTION_STOP = "app.plain.voice.STOP"
        private const val CHANNEL = "voice_recording"
        private const val NOTE_ID = 41

        /** Main thread only. */
        var state = RecState.IDLE
            private set
        private var accumulated = 0L
        private var since = 0L

        /** Called on the main thread on every change: an optional message, and whether a recording was just saved. */
        var listener: ((message: String?, saved: Boolean) -> Unit)? = null

        val busy get() = state != RecState.IDLE

        /** Recorded time so far (pauses left out). */
        fun elapsed(): Long = accumulated + if (state == RecState.RECORDING) SystemClock.elapsedRealtime() - since else 0

        private fun setState(s: RecState, message: String? = null, saved: Boolean = false) {
            state = s
            if (s == RecState.IDLE) accumulated = 0
            listener?.invoke(message, saved)
        }

        /** Call from the visible Activity, with the microphone permission granted. */
        fun start(ctx: Context) {
            if (state != RecState.IDLE) return
            setState(RecState.STARTING)
            try {
                ctx.startForegroundService(Intent(ctx, RecorderService::class.java).setAction(ACTION_START))
            } catch (_: Exception) {
                setState(RecState.IDLE, tr("Couldn't start recording.", "Kayıt başlatılamadı."))
            }
        }

        fun pause(ctx: Context) = send(ctx, ACTION_PAUSE)
        fun resume(ctx: Context) = send(ctx, ACTION_RESUME)
        fun stop(ctx: Context) = send(ctx, ACTION_STOP)

        private fun send(ctx: Context, action: String) {
            try { ctx.startService(Intent(ctx, RecorderService::class.java).setAction(action)) } catch (_: Exception) {}
        }
    }
}
