package app.plain.voice

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.ui.Ic
import app.plain.ui.Sheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Record Voice: a big Record / Stop button with Pause, and below it this app's own recordings, newest first, one
 * fixed page at a time. Tap a recording to play or pause it right in its row (with a seek bar); hold it or tap ⋮ to
 * share, rename or delete it.
 *
 * Recording itself runs in [RecorderService] (so it goes on with the screen off or in another tab). Here nothing
 * runs unless the tab is open: the clock and the seek bar tick only while it is resumed, and playback ends in onStop.
 */
class VoiceActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var loadGen = 0

    private var recs: List<Rec> = emptyList()
    private var loaded = false
    private var page = 0
    private var perPage = 0
    private var resumed = false
    private var rowH = 0

    private lateinit var clock: TextView
    private lateinit var status: TextView
    private lateinit var recBtn: RecButton
    private lateinit var pauseBtn: TextView
    private lateinit var header: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var message: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private lateinit var pageLabel: TextView

    // inline playback
    private var player: MediaPlayer? = null
    private var playingId = -1L
    private var seek: SeekBar? = null
    private var pos: TextView? = null
    private var focus: AudioFocusRequest? = null
    private var noisyOn = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { pausePlayback() }   // headphones pulled out
    }

    private var pendingDelete: Rec? = null

    private val dateFmt by lazy { SimpleDateFormat("d MMM yyyy  HH:mm", PlainLang.locale) }

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            updatePosition()
            val playing = player?.isPlaying == true
            if (resumed && (playing || RecorderService.state == RecState.RECORDING))
                main.postDelayed(this, if (playing) 250 else 500)
        }
    }

    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        if (Hub.gate(this, "voice")) { finish(); return }
        page = b?.getInt("page") ?: 0
        rowH = dp(60)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        // ---- recorder
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, dp(14), 0, dp(14))
        }
        clock = label("00:00", 46f, TEXT).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            setPadding(0, 0, 0, 0)
        }
        status = label("", 14f, TEXT2).apply { setPadding(0, 0, 0, dp(12)) }
        panel.addView(clock, LinearLayout.LayoutParams(-2, -2))
        panel.addView(status, LinearLayout.LayoutParams(-2, -2))
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER }
        pauseBtn = button(tr("Pause", "Duraklat")) { pauseOrResume() }
        recBtn = RecButton(this).apply { setOnClickListener { onRecord() } }
        controls.addView(pauseBtn, LinearLayout.LayoutParams(dp(112), dp(44)))
        controls.addView(recBtn, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginStart = dp(20); marginEnd = dp(20) })
        controls.addView(View(this), LinearLayout.LayoutParams(dp(112), dp(44)))  // keeps the button centred
        panel.addView(controls, LinearLayout.LayoutParams(-1, -2))
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))

        // ---- recordings
        header = label("", 14f, TEXT2).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(16), dp(10), dp(16), dp(6))
            background = lineAbove()
        }
        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        val frame = FrameLayout(this)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(listBox, FrameLayout.LayoutParams(-1, -1))
        message = label("", 15f, TEXT2).apply { visibility = View.GONE }
        frame.addView(message, FrameLayout.LayoutParams(-1, -1))
        frame.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val n = Format.rowsFor(bottom - top, rowH)
                if (n != perPage) { perPage = n; main.post { show() } }
            }
        }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        prev = button(tr("‹ Previous", "‹ Önceki")) { go(page - 1) }
        next = button(tr("Next ›", "Sonraki ›")) { go(page + 1) }
        pageLabel = label("", 15f, TEXT2)
        bar.addView(prev, LinearLayout.LayoutParams(-2, dp(44)))
        bar.addView(pageLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(next, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(Hub.wrap(this, "voice", root))
        updateRecorder()
        show()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
    }

    override fun onStart() {
        super.onStart()
        RecorderService.listener = { msg, saved ->
            updateRecorder()
            msg?.let { toast(it) }
            if (saved) { page = 0; load() }
        }
        updateRecorder()
        load()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        kick()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        RecorderService.listener = null
        stopPlayback()
        super.onStop()
    }

    override fun onDestroy() {
        loader.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    /** (Re)starts the ticker when something needs it; it stops by itself. */
    private fun kick() {
        main.removeCallbacks(tick)
        if (resumed) main.post(tick)
    }

    private fun lineAbove() = LayerDrawable(arrayOf(ColorDrawable(LINE), ColorDrawable(Color.BLACK))).apply {
        setLayerInset(1, 0, dp(1).coerceAtLeast(1), 0, 0)
    }

    private fun lineBelow() = LayerDrawable(arrayOf(ColorDrawable(LINE), ColorDrawable(Color.BLACK))).apply {
        setLayerInset(1, 0, 0, 0, dp(1).coerceAtLeast(1))
    }

    // ------------------------------------------------------------------ recording

    private fun updateRecorder() {
        if (!::recBtn.isInitialized) return
        val s = RecorderService.state
        val on = s == RecState.RECORDING || s == RecState.PAUSED
        recBtn.recording = on || s == RecState.SAVING
        recBtn.isEnabled = s == RecState.IDLE || on
        recBtn.alpha = if (recBtn.isEnabled) 1f else 0.5f
        recBtn.contentDescription = if (on) tr("Stop recording", "Kaydı durdur") else tr("Record", "Kaydet")
        pauseBtn.visibility = if (on) View.VISIBLE else View.INVISIBLE
        pauseBtn.text = if (s == RecState.PAUSED) tr("Resume", "Sürdür") else tr("Pause", "Duraklat")
        status.text = when (s) {
            RecState.IDLE -> tr("Tap to record", "Kaydetmek için dokunun")
            RecState.STARTING -> tr("Starting…", "Başlıyor…")
            RecState.RECORDING -> tr("Recording", "Kayıt yapılıyor")
            RecState.PAUSED -> tr("Paused", "Duraklatıldı")
            RecState.SAVING -> tr("Saving…", "Saklanıyor…")
        }
        status.setTextColor(if (s == RecState.RECORDING) REC else TEXT2)
        updateClock()
        kick()
    }

    private fun updateClock() {
        val s = RecorderService.state
        clock.text = Format.clock(if (s == RecState.IDLE || s == RecState.STARTING) 0 else RecorderService.elapsed())
        clock.setTextColor(if (s == RecState.PAUSED) TEXT2 else TEXT)
    }

    private fun onRecord() {
        when (RecorderService.state) {
            RecState.IDLE -> {
                val need = ArrayList<String>()
                if (!hasMic()) need += Manifest.permission.RECORD_AUDIO
                val prefs = getSharedPreferences("voice", MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotify", false) &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    need += Manifest.permission.POST_NOTIFICATIONS   // asked once, with the first recording
                    prefs.edit().putBoolean("askedNotify", true).apply()
                }
                if (need.isEmpty()) startRecording() else requestPermissions(need.toTypedArray(), RC_MIC)
            }
            RecState.RECORDING, RecState.PAUSED -> RecorderService.stop(this)
            else -> {}
        }
    }

    private fun pauseOrResume() {
        when (RecorderService.state) {
            RecState.RECORDING -> RecorderService.pause(this)
            RecState.PAUSED -> RecorderService.resume(this)
            else -> {}
        }
    }

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startRecording() {
        stopPlayback()
        show()
        RecorderService.start(this)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc != RC_MIC) return
        if (hasMic()) startRecording() else explainMic()
    }

    private fun explainMic() {
        val again = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val d = AlertDialog.Builder(this)
            .setTitle(tr("Microphone needed", "Mikrofon gerekli"))
            .setMessage(tr("Record Voice needs the microphone to record. Recordings stay on your phone.\n\nAllow “Microphone” in the app's settings.",
                "Ses Kaydı, kayıt için mikrofona ihtiyaç duyar. Kayıtlar telefonunuzda kalır.\n\nUygulama ayarlarında “Mikrofon” iznini verin."))
            .setPositiveButton(tr("App settings", "Uygulama ayarları")) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
        if (again) d.setNeutralButton(tr("Ask again", "Tekrar sor")) { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC) }
        d.show()
    }

    // ------------------------------------------------------------------ list

    private fun load() {
        val gen = ++loadGen
        val app = applicationContext
        try {
            loader.execute {
                val l = Recordings.list(app)
                main.post {
                    if (gen != loadGen || isDestroyed) return@post
                    recs = l
                    loaded = true
                    if (playingId >= 0 && recs.none { it.id == playingId }) stopPlayback()
                    show()
                }
            }
        } catch (_: Exception) {}
    }

    private fun go(p: Int) {
        if (p !in 0 until Format.pageCount(recs.size, perPage)) return
        page = p
        show()
    }

    private fun show() {
        if (!::listBox.isInitialized) return
        val n = recs.size
        header.text = tr("Recordings", "Kayıtlar") + if (loaded) " · $n" else ""
        if (perPage == 0) return
        val pages = Format.pageCount(n, perPage)
        page = page.coerceIn(0, pages - 1)
        listBox.removeAllViews()
        seek = null
        pos = null
        for (i in Format.range(page, perPage, n)) listBox.addView(row(recs[i]), LinearLayout.LayoutParams(-1, rowH))
        pageLabel.text = tr("Page ${page + 1} / $pages", "Sayfa ${page + 1} / $pages")
        prev.isEnabled = page > 0
        prev.alpha = if (page > 0) 1f else 0.35f
        next.isEnabled = page < pages - 1
        next.alpha = if (page < pages - 1) 1f else 0.35f
        if (loaded && n == 0) {
            message.text = tr("No recordings yet.\n\nTap the red button to record.", "Henüz kayıt yok.\n\nKaydetmek için kırmızı düğmeye dokunun.")
            message.visibility = View.VISIBLE
        } else message.visibility = View.GONE
        updatePosition()
    }

    private fun row(r: Rec): View {
        val current = r.id == playingId && player != null
        val playing = current && player?.isPlaying == true
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = lineBelow()
            isClickable = true
            contentDescription = r.name
            setOnClickListener { tap(r) }
        }
        val icon = label(if (playing) "❚❚" else "▶", 15f, if (current) ACCENT else TEXT2).apply {
            setPadding(0, 0, 0, 0)
            contentDescription = if (playing) tr("Pause", "Duraklat") else tr("Play", "Oynat")
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(48), -1))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        mid.addView(TextView(this).apply {
            text = r.name
            setTextColor(if (current) ACCENT else TEXT)
            textSize = 15f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2))
        if (current) {
            val line = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val sb = SeekBar(this).apply {
                max = player?.duration?.coerceAtLeast(1) ?: 1
                progressTintList = ColorStateList.valueOf(ACCENT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
                progressBackgroundTintList = ColorStateList.valueOf(BTN_LINE)
                setPadding(0, dp(6), dp(10), dp(6))
                contentDescription = tr("Position", "Konum")
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        if (fromUser) { player?.seekTo(p); updatePosition() }
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            val t = TextView(this).apply { setTextColor(TEXT2); textSize = 12f; fontFeatureSettings = "tnum" }
            line.addView(sb, LinearLayout.LayoutParams(0, -2, 1f))
            line.addView(t, LinearLayout.LayoutParams(-2, -2))
            mid.addView(line, LinearLayout.LayoutParams(-1, -2))
            seek = sb
            pos = t
        } else {
            val meta = listOfNotNull(
                dateFmt.format(Date(r.time)),
                if (r.duration > 0) Format.duration(r.duration) else null,
                Format.bytes(r.size),
            ).joinToString("  ·  ")
            mid.addView(TextView(this).apply {
                text = meta
                setTextColor(TEXT2)
                textSize = 12.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2))
        }
        row.addView(mid, LinearLayout.LayoutParams(0, -1, 1f))
        val more = label("⋮", 20f, TEXT2).apply {
            setPadding(0, 0, 0, 0)
            contentDescription = tr("Options", "Seçenekler")
            setOnClickListener { options(r, it) }
        }
        row.addView(more, LinearLayout.LayoutParams(dp(48), -1))
        row.setOnLongClickListener { options(r, more); true }
        return row
    }

    // ------------------------------------------------------------------ playback

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun tap(r: Rec) {
        if (RecorderService.busy) { toast(tr("Stop recording first.", "Önce kaydı durdurun.")); return }
        val p = player
        if (p != null && r.id == playingId) {
            if (p.isPlaying) pausePlayback() else resumePlayback()
            return
        }
        stopPlayback()
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(this, r.uri)
            mp.prepare()   // a local file: quick
        } catch (_: Exception) {
            mp.release()
            toast(tr("Can't play this recording.", "Bu kayıt oynatılamıyor."))
            return
        }
        mp.setOnCompletionListener { stopPlayback(); show() }
        mp.setOnErrorListener { _, _, _ -> stopPlayback(); show(); true }
        player = mp
        playingId = r.id
        resumePlayback()
    }

    private fun resumePlayback() {
        val p = player ?: return
        val am = getSystemService(AudioManager::class.java)
        val f = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pausePlayback()
            }, main)
            .build().also { focus = it }
        am?.requestAudioFocus(f)
        p.start()
        if (!noisyOn) {
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, f, RECEIVER_NOT_EXPORTED) else registerReceiver(noisy, f)
            noisyOn = true
        }
        show()
        kick()
    }

    private fun pausePlayback() {
        val p = player ?: return
        try { if (p.isPlaying) p.pause() } catch (_: Exception) {}
        letGo()
        show()
    }

    private fun stopPlayback() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
        playingId = -1
        letGo()
    }

    /** Gives back audio focus and stops listening for unplugged headphones. */
    private fun letGo() {
        focus?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
        if (noisyOn) { try { unregisterReceiver(noisy) } catch (_: Exception) {}; noisyOn = false }
    }

    private fun updatePosition() {
        val p = player ?: return
        val now = try { p.currentPosition } catch (_: Exception) { return }
        seek?.progress = now
        pos?.text = Format.duration(now.toLong()) + " / " + Format.duration(p.duration.toLong())
    }

    // ------------------------------------------------------------------ share / rename / delete

    private fun options(r: Rec, @Suppress("UNUSED_PARAMETER") anchor: View) {
        Sheet(this, ACCENT).header(r.name, tr("Voice recording", "Ses kaydı"))
            .actions(
                Sheet.Action(Ic.SHARE, tr("Share", "Paylaş")) { share(r) },
                Sheet.Action(Ic.EDIT, tr("Rename", "Yeniden adlandır")) { rename(r) },
            )
            .item(Ic.DELETE, tr("Delete", "Sil"), danger = true) { confirmDelete(r) }
            .show()
    }

    private fun share(r: Rec) {
        val send = Intent(Intent.ACTION_SEND)
            .setType(Format.MIME)
            .putExtra(Intent.EXTRA_STREAM, r.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(r.name, r.uri)   // carries the read grant through the chooser
        try { startActivity(Intent.createChooser(send, tr("Share recording", "Kaydı paylaş"))) }
        catch (_: Exception) { toast(tr("No app can share this.", "Bunu paylaşabilecek uygulama yok.")) }
    }

    private fun rename(r: Rec) {
        val e = EditText(this).apply {
            setText(r.name)
            setSelectAllOnFocus(true)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setTextColor(TEXT)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        AlertDialog.Builder(this)
            .setTitle(tr("Rename", "Yeniden adlandır"))
            .setView(box)
            .setPositiveButton(tr("Rename", "Adlandır")) { _, _ ->
                val name = Format.cleanName(e.text.toString())
                if (name == null) { toast(tr("Type a name.", "Bir ad yazın.")); return@setPositiveButton }
                if (Format.shownName(name) == r.name) return@setPositiveButton
                val app = applicationContext
                try {
                    loader.execute {
                        val ok = try { Recordings.rename(app, r, name); true } catch (_: Exception) { false }
                        main.post {
                            if (isDestroyed) return@post
                            if (!ok) toast(tr("Couldn't rename. Is the name taken?", "Yeniden adlandırılamadı. Bu ad kullanılıyor mu?"))
                            load()
                        }
                    }
                } catch (_: Exception) {}
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    private fun confirmDelete(r: Rec) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete “${r.name}”?", "“${r.name}” silinsin mi?"))
            .setMessage(tr("The recording is deleted for good.", "Kayıt kalıcı olarak silinir."))
            .setPositiveButton(tr("Delete", "Sil")) { _, _ -> delete(r) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    @Suppress("DEPRECATION")
    private fun delete(r: Rec) {
        if (r.id == playingId) { stopPlayback(); show() }
        try {
            if (!Recordings.delete(this, r)) toast(tr("Couldn't delete.", "Silinemedi."))
            load()
        } catch (e: RecoverableSecurityException) {
            // not this app's file after all (e.g. from before a reinstall): Android asks the user
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    val pi = MediaStore.createTrashRequest(contentResolver, listOf(r.uri), true)
                    startIntentSenderForResult(pi.intentSender, RC_TRASH, null, 0, 0, 0)
                } else {
                    pendingDelete = r
                    startIntentSenderForResult(e.userAction.actionIntent.intentSender, RC_ALLOW, null, 0, 0, 0)
                }
            } catch (_: Exception) { toast(tr("Couldn't delete.", "Silinemedi.")) }
        } catch (_: Exception) {
            toast(tr("Couldn't delete.", "Silinemedi."))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        when (rc) {
            RC_TRASH -> if (res == RESULT_OK) load()
            RC_ALLOW -> {
                val r = pendingDelete
                pendingDelete = null
                if (res == RESULT_OK && r != null) {
                    try { Recordings.delete(this, r) } catch (_: Exception) { toast(tr("Couldn't delete.", "Silinemedi.")) }
                    load()
                }
            }
        }
    }

    companion object {
        private const val RC_MIC = 1
        private const val RC_TRASH = 2
        private const val RC_ALLOW = 3
    }
}

/** The big round button: a red dot to record, a red square to stop. */
class RecButton(ctx: Context) : View(ctx) {
    var recording = false
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = REC }

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2 * d
        ringP.color = if (recording) REC else BTN_LINE
        c.drawCircle(cx, cy, r, ringP)
        if (recording) {
            val h = r * 0.42f
            c.drawRoundRect(cx - h, cy - h, cx + h, cy + h, 5 * d, 5 * d, fill)
        } else c.drawCircle(cx, cy, r * 0.72f, fill)
    }
}
