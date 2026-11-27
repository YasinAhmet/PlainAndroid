package app.plain.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.PlaybackParams
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.ui.Sheet

/**
 * In-app player (MediaPlayer, no libraries). Streams go through [StreamProxy] so what is watched is cached at the
 * same time: Watch = one HLS quality (video TS segments + AAC audio segments) into the Video cache, Listen = the
 * audio stream only (itag 140 M4A, else 251 Opus) into the Music cache. Saved files play straight from MediaStore.
 *
 * 2.3: one app-level player hosted as an overlay in MainActivity: FULL (this screen), MINI (a small window pinned to
 * a corner of the app while browsing; a compact card for Listen), PIP (the activity in picture-in-picture, video
 * only) or CLOSED. Navigating never stops playback; the app going to the background without PiP pauses and releases
 * everything as before (no background service).
 */
class PlayerPanel private constructor(private val app: Context) : ContextWrapper(app), SurfaceHolder.Callback {
    companion object {
        /** The one player of the process (2.5: it outlives MainActivity; the playback service keeps it alive). */
        @Volatile var current: PlayerPanel? = null
            private set
        val uiMain = Handler(Looper.getMainLooper())

        /** The app's player, created with the application context (never an Activity). */
        fun get(ctx: Context): PlayerPanel = current ?: PlayerPanel(ctx.applicationContext)
    }

    /** The activity showing the player, if any. Only held between [attach] and [detach]. */
    var ui: MainActivity? = null
        private set
    /** The Watch/Listen choice of the current item (the background may play a Watch item as audio). */
    private var itemMode = PlayMode.WATCH

    /**
     * MainActivity shows the player (created, or recreated after it was swiped away while playing). The views are
     * the player's own (application context) and simply move into the new window; a player that wasn't running is
     * restored paused from the saved state (process restarted).
     */
    fun attach(a: MainActivity) {
        if (ui != null && ui !== a) ui?.let { detach(it) }
        ui = a
        (root.parent as? android.view.ViewGroup)?.removeView(root)
        for (step in EngineRule.onAttach(state, Store.lastPlay != null)) when (step) {
            EngineRule.AttachStep.LEAVE_PIP -> go(PlayerUi.Ev.LEAVE_PIP)
            EngineRule.AttachStep.RESTORE_PAUSED -> restore()
            EngineRule.AttachStep.SHOW -> {}
        }
        applyState()
        showMode(); showEntryControls(); updateTime(); updatePlayBtn()
    }

    /**
     * MainActivity is destroyed. Playing: the player goes on (audio only; the activity stopped first, so the
     * background rules already apply). Paused: everything closes, as a swipe from recents always did.
     */
    fun detach(a: MainActivity) {
        if (ui !== a) return
        uiMain.removeCallbacks(hideCtl)
        registerVolume(false)
        if (fullscreen) { fullscreen = false; full.setPadding(0, insetTop, 0, insetBottom); restoreBrightness() }
        (root.parent as? android.view.ViewGroup)?.removeView(root) // the old window's views no longer reach us
        holder = null
        try { mp?.setDisplay(null) } catch (_: Exception) {}
        ui = null
        val keep = keepOnce
        keepOnce = false
        if (keep || EngineRule.keepOnDetach(wantsPlay)) saveState() else close()
    }

    /** What to resume after the process was gone: item, position, mode, queue flag (the queue itself is in Store). */
    private fun saveState() {
        if (state == PState.CLOSED || (videoId.isEmpty() && localUri == null)) { Store.lastPlay = null; return }
        val pos = try { if (prepared) mp?.currentPosition ?: resumeMs else resumeMs } catch (_: Exception) { resumeMs }
        Store.lastPlay = LastPlay(videoId, title, channel, itemMode, localUri?.toString(), localAudio, queueMode, albumHint, pos,
            if (state == PState.FULL) "FULL" else "MINI").encode()
    }

    /** Back from a stored state (process restarted): paused at the saved position, as the mini window. */
    private fun restore(): Boolean {
        val l = LastPlay.decode(Store.lastPlay) ?: return false
        queueMode = l.queue
        val e = if (queueMode) Store.queue().current else null
        if (e != null) setEntry(e) else {
            videoId = l.id; title = l.title; channel = l.channel; localUri = l.uri?.let { Uri.parse(it) }; localAudio = l.audio
            forced = l.mode; albumHint = l.album; musicLink = false; channelId = null
        }
        info = null; choices = emptyList(); tracks = emptyList(); audioLang = Store.audioLang(videoId); triedOpus = false; watchFailed = false; pubFile = null; listenItag = 0
        resumeMs = l.pos
        mode = l.mode; itemMode = l.mode
        playWhenReady = false
        everPlayed = true
        state = if (l.view == "FULL" && ui != null) PState.FULL else PState.MINI
        if (ui == null) bg.hide(mode == PlayMode.WATCH, BgSrc.STREAM) // no window: a Watch item plays audio-only
        return true
    }

    /** A notification button / headset press reached a player with nothing loaded (process restarted). */
    fun restoreForControls(): Boolean =
        EngineRule.canControl(state, Store.lastPlay != null) && (state != PState.CLOSED || restore())

    // ---- 2.4 background playback ----
    private val bg = BgVideo()
    private val bgSwitch = Runnable { runBg(bg.timer(mode == PlayMode.WATCH && streaming())) }
    private var everPlayed = false
    private var resumeOnGain = false
    private var hasFocus = false
    private var noisy: BroadcastReceiver? = null
    @Volatile var cover: android.graphics.Bitmap? = null
        private set
    private var artFor = ""
    private val session = android.media.session.MediaSession(app, "PlainYoutube").apply {
        setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() = resume()
            override fun onPause() = pause()
            override fun onSkipToNext() = step(1)
            override fun onSkipToPrevious() = step(-1)
            override fun onFastForward() = seekBy(10_000)
            override fun onRewind() = seekBy(-10_000)
            override fun onSeekTo(pos: Long) = seekTo(pos.toInt())
            override fun onStop() = close()
        }, uiMain)
    }
    private val focusReq = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ c -> onFocus(c) }, uiMain)
        .build()

    /** Playing, or about to (buffering, the next queue item, the audio/video switch): the service stays foreground. */
    val wantsPlay get() = state != PState.CLOSED && playWhenReady
    /** The media notification exists once something played, until the player closes. */
    val notifying get() = state != PState.CLOSED && everPlayed
    val nowTitle get() = title.ifEmpty { "Plain Youtube" }
    val nowChannel get() = channel
    val inQueue get() = queueMode
    val sessionToken: android.media.session.MediaSession.Token get() = session.sessionToken

    /** Notification buttons. */
    fun command(cmd: String) {
        when (cmd) {
            PlayControl.TOGGLE -> togglePlay()
            PlayControl.NEXT -> step(1)
            PlayControl.PREV -> step(-1)
            PlayControl.BACK -> seekBy(-10_000)
            PlayControl.FWD -> seekBy(10_000)
            PlayControl.CLOSE -> close()
            PlayControl.DISMISS -> if (!wantsPlay) { if (ui == null) close() else { everPlayed = false; PlayService.sync(app) } } // swiped away while paused
        }
    }

    /** Also from a notification button or a headset: the service goes foreground at once (while that's still allowed). */
    private fun resume() {
        if (mp == null) { playWhenReady = true; everPlayed = true; PlayService.sync(app); start() } else play()
    }

    /** Streaming a video (not a saved file, a published or complete cache): costs video bytes while hidden. */
    private fun streaming() = localUri == null && pubFile == null && source?.meta?.complete != true

    private fun runBg(acts: List<BgAct>) {
        for (a in acts) when (a) {
            BgAct.DETACH -> try { mp?.setDisplay(null) } catch (_: Exception) {} // audio only: the video decoder stops
            BgAct.ATTACH -> holder?.let { h -> if (mode == PlayMode.WATCH) try { mp?.setDisplay(h) } catch (_: Exception) {} }
            BgAct.SCHEDULE_SWITCH -> { uiMain.removeCallbacks(bgSwitch); uiMain.postDelayed(bgSwitch, BgVideo.DELAY_MS) }
            BgAct.CANCEL_TIMER -> uiMain.removeCallbacks(bgSwitch)
            BgAct.SWITCH_TO_AUDIO -> if (mp != null && playWhenReady) handoff(PlayMode.LISTEN) else { releaseAll(); mode = PlayMode.LISTEN; forced = PlayMode.LISTEN }
            BgAct.SWITCH_TO_VIDEO -> if (mp != null && playWhenReady) handoff(PlayMode.WATCH) else { releaseAll(); mode = PlayMode.WATCH; forced = PlayMode.WATCH; showMode() }
        }
    }

    /**
     * Same position, other source, with a short gap: the new source is resolved (cache lookup, YouTube answer, album)
     * while the old one keeps playing; only then is the old player released and the new one prepared.
     */
    private fun handoff(m: PlayMode) {
        val old = mp
        if (old == null || !prepared) { restartAt(m, remember = false); return }
        forced = m
        val my = ++seq
        val g = calls.gen
        io.execute {
            val r = try { Result.success(resolve(g, m)) } catch (e: Throwable) { Result.failure(e) }
            uiMain.post {
                if (my != seq || isDestroyed) { (r.getOrNull() as? CachedSource)?.close(); return@post }
                val pos = try { old.currentPosition } catch (_: Exception) { resumeMs }
                val wanted = playWhenReady
                releaseAll()
                mode = m
                resumeMs = pos; playWhenReady = wanted; pubFile = null; prepared = false
                r.fold({ src ->
                    if (src is Saved.File) { pubFile = src; preparePlayer(Uri.parse(src.uri)) } else startSource(src as CachedSource)
                }, { e -> status(errText(e), true) })
            }
        }
    }

    private fun onFocus(change: Int) {
        when (Focus.onChange(change, mp?.isPlaying == true, resumeOnGain)) {
            Focus.Do.PAUSE_FOR_GOOD -> { resumeOnGain = false; pause() } // e.g. Plain Music started playing
            Focus.Do.PAUSE_RESUME_LATER -> { resumeOnGain = true; pause(keepFocus = true) } // a call
            Focus.Do.DUCK -> mp?.setVolume(0.25f, 0.25f)
            Focus.Do.RESUME -> { resumeOnGain = false; mp?.setVolume(1f, 1f); resume() }
            Focus.Do.UNDUCK -> mp?.setVolume(1f, 1f)
            Focus.Do.NOTHING -> {}
        }
    }

    private fun requestFocus(): Boolean {
        if (!hasFocus) hasFocus = audio.requestAudioFocus(focusReq) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() { if (hasFocus) { audio.abandonAudioFocusRequest(focusReq); hasFocus = false } }

    /** Headphones unplugged / Bluetooth gone: pause (registered only while playing). */
    private fun noisyWatch(on: Boolean) {
        if (on && noisy == null) {
            val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { pause() } }
            noisy = r
            val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
        } else if (!on) { noisy?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; noisy = null }
    }

    /** MediaSession (lock screen, Bluetooth buttons, the notification) follows the player. */
    private fun syncSession() {
        val playing = mp?.isPlaying == true
        val pos = try { if (prepared) mp?.currentPosition?.toLong() ?: 0L else resumeMs.toLong() } catch (_: Exception) { 0L }
        var actions = android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or android.media.session.PlaybackState.ACTION_SEEK_TO or
            android.media.session.PlaybackState.ACTION_FAST_FORWARD or android.media.session.PlaybackState.ACTION_REWIND or
            android.media.session.PlaybackState.ACTION_STOP
        if (queueMode) actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        session.setPlaybackState(android.media.session.PlaybackState.Builder()
            .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else if (playWhenReady) android.media.session.PlaybackState.STATE_BUFFERING
                else android.media.session.PlaybackState.STATE_PAUSED, pos, if (playing) speed else 0f)
            .setActions(actions).build())
        session.isActive = state != PState.CLOSED
        PlayService.sync(app)
    }

    private fun syncMeta() {
        val dur = try { if (prepared) mp?.duration?.toLong() ?: 0L else 0L } catch (_: Exception) { 0L }
        session.setMetadata(android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, title.ifEmpty { "Plain Youtube" })
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, channel)
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, dur)
            .apply { cover?.let { putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, it) } }
            .build())
        // cover for the notification / lock screen: the low-res thumbnail (Wi-Fi only respected), once per video
        val id = videoId
        if (id.isNotEmpty() && artFor != id) {
            artFor = id; cover = null
            Thread({
                val bytes = try { Art.fetch(app, id, null, ArtQuality.LOW, 8000) } catch (_: Exception) { null }
                val bm = bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                uiMain.post { if (artFor == id && bm != null) { cover = bm; syncMeta(); PlayService.sync(app) } }
            }, "player-art").start()
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val calls = Calls()
    private val thumbs: ThumbLoader = ThumbLoader(app, calls)

    var state = PState.CLOSED
        private set
    private var beforePip = PState.FULL
    val root = FrameLayout(this) // overlay host (MATCH_PARENT, transparent: touches outside the mini pass through)

    // ---- language: the panel's views outlive MainActivity, so its fixed labels are rewritten on a change ----
    private val relabels = ArrayList<() -> Unit>()
    private fun <T : TextView> T.say(s: () -> String): T = apply { text = s(); relabels += { text = s() } }
    private fun <T : View> T.desc(s: () -> String): T = apply { contentDescription = s(); relabels += { contentDescription = s() } }
    /** The activity is recreated on purpose (language change): the next [detach] keeps even a paused player. */
    private var keepOnce = false
    fun keepOnNextDetach() { keepOnce = true }

    /** A new app language: rewrite the labels the panel built once (MainActivity rebuilds its own views). */
    fun relabel() {
        relabels.forEach { it() }
        rotateBtn.text = rotateText()
        qualityBtn.text = tr("Quality ${height}p", "Kalite ${height}p")
        membersV.text = tr("MEMBERS", "ÜYELER"); membersV.contentDescription = tr("Members only", "Üyelere özel")
        showEntryControls()
    }

    private fun rotateText() = if (rotationLocked) tr("Rotate: locked", "Döndür: kilitli") else tr("Rotate: auto", "Döndür: otomatik")
    private lateinit var full: LinearLayout // the player itself: whole screen, or the mini window
    private lateinit var miniCtl: FrameLayout
    private lateinit var miniCard: LinearLayout
    private lateinit var miniPlay: ImageView
    private lateinit var miniCardPlay: ImageView
    private lateinit var miniCardTitle: TextView
    private lateinit var miniCardThumb: RoundImage
    private lateinit var seekHint: TextView
    private lateinit var channelV: TextView
    private lateinit var upNextV: TextView
    private lateinit var speedBtn: TextView
    private lateinit var rotateBtn: TextView
    private lateinit var volIcon: ImageView
    private lateinit var volBar: SeekBar
    private var insetTop = 0
    private var insetBottom = 0
    private var speed = 1f
    private var rotationLocked = false
    private var videoW = 16
    private var videoH = 9
    private val audio = app.getSystemService(AudioManager::class.java)
    private val vol = VolumeModel(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    private var volReceiver: BroadcastReceiver? = null
    private val hideCtl = Runnable { if (state == PState.FULL && mode == PlayMode.WATCH && mp?.isPlaying == true) showControls(false) }

    private var videoId = ""
    private var title = ""
    private var channel = ""
    private var localUri: Uri? = null
    private var localAudio = false
    private var forced: PlayMode? = null
    private var musicLink = false
    private var channelId: String? = null
    private var queueMode = false
    private lateinit var artChannel: TextView
    private lateinit var prevBtn: TextView
    private lateinit var nextBtn: TextView
    private lateinit var queueBtn: TextView

    private var mode = PlayMode.WATCH
    private var mp: MediaPlayer? = null
    private var prepared = false
    private var source: CachedSource? = null
    private var proxy: StreamProxy? = null
    private var info: VideoInfo? = null
    private var choices: List<Hls.Variant> = emptyList()
    private var height = 720
    private var tracks: List<AudioTrack> = emptyList() // audio languages of a dubbed video (empty = single track / not known yet)
    private var audioLang: String? = null // picked language; null = the original track
    private var listenItag = 0
    private var triedOpus = false
    private var watchFailed = false
    private var resumeMs = 0
    private var playWhenReady = true
    private var started = false // the host activity is started
    private var holder: SurfaceHolder? = null
    private var fullscreen = false
    private var saveOnClose: CacheMeta? = null // Save pressed while a private cache is being served
    private var pubFile: Saved.File? = null // playing the saved file (a download, or a cache that completed)
    private var albumHint: String? = null
    private var seq = 0
    private var dragging = false

    // views
    private lateinit var stage: FrameLayout
    private lateinit var videoFrame: AspectFrame
    private var surfaceView: SurfaceView? = null
    private lateinit var art: LinearLayout
    private lateinit var artImg: RoundImage
    private lateinit var topBar: LinearLayout
    private lateinit var controls: LinearLayout
    private lateinit var titleV: TextView
    private lateinit var membersV: TextView
    private lateinit var statusV: TextView
    private lateinit var seek: SeekBar
    private lateinit var posV: TextView
    private lateinit var durV: TextView
    private lateinit var playBtn: TextView
    private lateinit var watchChip: Chip
    private lateinit var listenChip: Chip
    private lateinit var qualityBtn: TextView
    private lateinit var audioBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var dlBtn: TextView

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            if (started && mp?.isPlaying == true) main.postDelayed(this, 500)
        }
    }

    /**
     * Start playing [r] (from a row, the downloads list, the queue): the player opens full. Left in the middle last
     * time (3.3)? "Continue from last session?" first: Continue = from there, Start over = from the beginning.
     */
    fun open(r: PlayReq) {
        val e = if (r.queue) Store.queue().current else null
        if (r.queue && e == null) { toast(tr("The play queue is empty", "Çalma sırası boş")); return }
        val key = (e?.id ?: r.id).ifEmpty { (e?.localUri ?: r.uri).orEmpty() }
        val at = Resume.get(key)
        val a = ui
        if (at <= 0 || a == null || a.isFinishing) { openAt(r, 0); return }
        android.app.AlertDialog.Builder(a)
            .setTitle(tr("Continue from last session?", "Son oturumdan devam edilsin mi?"))
            .setMessage(tr("You stopped at ${ResumeRule.clock(at.toLong())}.", "${ResumeRule.clock(at.toLong())} noktasında kalmıştınız."))
            .setPositiveButton(tr("Continue", "Devam et")) { _, _ -> openAt(r, at) }
            .setNegativeButton(tr("From the beginning", "Baştan başla")) { _, _ -> Resume.clear(key); openAt(r, 0) }
            .show()
    }

    private fun openAt(r: PlayReq, startMs: Int) {
        val e = if (r.queue) Store.queue().current else null
        if (r.queue && e == null) return
        releaseAll()
        queueMode = r.queue
        if (e != null) setEntry(e) else {
            videoId = r.id; title = r.title; channel = r.channel
            localUri = r.uri?.let { Uri.parse(it) }; localAudio = r.audio
            forced = r.mode; musicLink = r.musicLink; albumHint = r.album; channelId = null
        }
        info = null; choices = emptyList(); tracks = emptyList(); audioLang = Store.audioLang(videoId); triedOpus = false; watchFailed = false; resumeMs = startMs; pubFile = null; listenItag = 0
        height = Store.watchP
        mode = forced ?: Store.mode(videoId) ?: PlayMode.WATCH
        if (localUri != null) mode = if (localAudio) PlayMode.LISTEN else PlayMode.WATCH
        itemMode = mode
        playWhenReady = true
        bg.reset()
        go(PlayerUi.Ev.OPEN)
        showMode()
        showEntryControls()
        if (started || bg.hidden) start()
    }

    // ---- host lifecycle ----

    fun hostStart() {
        started = true
        runBg(bg.show()) // back in the app: video again (surface, or the video stream at the same position)
        if (state != PState.CLOSED && mp == null) start()
        if (state == PState.FULL) registerVolume(true)
    }

    /**
     * The app went to the background: pause and release (no background playback). In PiP the activity also stops
     * when the user closes the PiP window ([pipClosed]) — then the player closes — or when the screen goes off — then it
     * just pauses.
     */
    fun hostStop(pipClosed: Boolean) {
        started = false
        registerVolume(false)
        if (pipClosed) { close(); return }
        if (state == PState.CLOSED) return
        // 2.4: audio goes on in the background (also Watch, as audio); paused = nothing keeps running
        runBg(bg.hide(mode == PlayMode.WATCH, if (streaming()) BgSrc.STREAM else BgSrc.LOCAL))
        if (!playWhenReady) {
            mp?.let { if (prepared) resumeMs = it.currentPosition }
            releaseAll()
        }
        saveState()
    }

    fun hostDestroy() {
        uiMain.removeCallbacks(bgSwitch)
        playWhenReady = false
        releaseAll()
        abandonFocus(); noisyWatch(false)
        session.isActive = false
        session.release()
        if (current === this) current = null
        PlayService.sync(app)
        registerVolume(false)
        unregisterPipActions()
        io.shutdownNow()
        thumbs.shutdown()
    }

    val isFull get() = state == PState.FULL
    val isFullscreen get() = fullscreen && state == PState.FULL
    private val isDestroyed get() = false // the player outlives activities (2.5)

    // ---- overlay state ----

    private fun go(e: PlayerUi.Ev) {
        val old = state
        if (e == PlayerUi.Ev.ENTER_PIP) beforePip = old
        state = PlayerUi.next(old, e, beforePip)
        if (state != old) applyState()
    }

    /** ⌄, Back, swipe down, or anything opened from the player (Channel, Download…). */
    fun minimize() {
        if (state != PState.FULL) return
        if (fullscreen) setFullscreen(false)
        go(PlayerUi.Ev.MINIMIZE)
    }

    fun expand() = go(PlayerUi.Ev.EXPAND)

    /** ✕: stop, release, gone. */
    fun close() {
        if (fullscreen) setFullscreen(false)
        playWhenReady = false
        uiMain.removeCallbacks(bgSwitch)
        bg.reset()
        releaseAll()
        abandonFocus(); noisyWatch(false); resumeOnGain = false
        everPlayed = false
        Store.lastPlay = null
        go(PlayerUi.Ev.CLOSE)
        syncSession()
    }

    fun leaveFullscreen() { if (fullscreen) setFullscreen(false) }

    private fun applyState() {
        val st = state
        root.visibility = if (st == PState.CLOSED) View.GONE else View.VISIBLE
        uiMain.removeCallbacks(hideCtl)
        val watch = mode == PlayMode.WATCH
        when (st) {
            PState.CLOSED -> {
                ui?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                registerVolume(false)
            }
            PState.FULL -> {
                full.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                full.translationX = 0f; full.translationY = 0f
                full.background = null; full.setBackgroundColor(C.BG)
                full.setPadding(0, if (fullscreen) 0 else insetTop, 0, if (fullscreen) 0 else insetBottom)
                full.isClickable = true
                stage.visibility = View.VISIBLE
                topBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
                controls.visibility = if (fullscreen) View.GONE else View.VISIBLE
                miniCtl.visibility = View.GONE; miniCard.visibility = View.GONE
                ui?.requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR
                if (started) registerVolume(true)
                syncVolume()
                kickHide()
            }
            PState.MINI -> {
                val corner = Corners.parse(Store.miniCorner)
                val w = (resources.displayMetrics.widthPixels * (if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 0.3f else 0.4f)).toInt()
                val h = if (watch) w * 9 / 16 else WRAP
                val g = when (corner) {
                    Corner.TOP_LEFT -> Gravity.TOP or Gravity.START
                    Corner.TOP_RIGHT -> Gravity.TOP or Gravity.END
                    Corner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
                    Corner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
                }
                val m = dp(10)
                full.layoutParams = FrameLayout.LayoutParams(if (watch) w else (w * 1.6f).toInt().coerceAtMost(resources.displayMetrics.widthPixels - 2 * m), h, g)
                    .apply { setMargins(m, m + insetTop + dp(48), m, m + insetBottom + dp(8)) }
                full.translationX = 0f; full.translationY = 0f
                full.background = outline(dpf(10))
                full.setPadding(dp(1), dp(1), dp(1), dp(1))
                full.isClickable = false
                full.clipToOutline = true
                topBar.visibility = View.GONE; controls.visibility = View.GONE
                stage.visibility = if (watch) View.VISIBLE else View.GONE
                miniCtl.visibility = if (watch) View.VISIBLE else View.GONE
                miniCard.visibility = if (watch) View.GONE else View.VISIBLE
                ui?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                registerVolume(false)
                showControls(true, kick = false)
            }
            PState.PIP -> {
                full.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
                full.translationX = 0f; full.translationY = 0f
                full.background = null; full.setBackgroundColor(C.BG)
                full.setPadding(0, 0, 0, 0)
                topBar.visibility = View.GONE; controls.visibility = View.GONE
                stage.visibility = View.VISIBLE
                miniCtl.visibility = View.GONE; miniCard.visibility = View.GONE
                registerVolume(false)
            }
        }
        updateMini()
        ui?.onPlayerState(st)
        updatePip()
    }

    private fun updateMini() {
        if (!::miniPlay.isInitialized) return
        val playing = mp?.isPlaying == true
        val icon = if (playing) R.drawable.yt_ic_pause else R.drawable.yt_ic_play
        miniPlay.setImageResource(icon); miniCardPlay.setImageResource(icon)
        miniCardTitle.text = title.ifEmpty { tr("Player", "Oynatıcı") }
        if (videoId.isNotEmpty() && state == PState.MINI && mode == PlayMode.LISTEN) thumbs.load(miniCardThumb, videoId, dp(64))
    }

    /** Drag the mini window to a corner; a tap without moving opens the full player. */
    private fun dragTouch() = object : View.OnTouchListener {
        private var x0 = 0f; private var y0 = 0f; private var moved = false
        private val slop = ViewConfiguration.get(app).scaledTouchSlop
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x0 = e.rawX; y0 = e.rawY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - x0; val dy = e.rawY - y0
                    if (!moved && Math.hypot(dx.toDouble(), dy.toDouble()) > slop) moved = true
                    if (moved) { full.translationX = dx; full.translationY = dy }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) { expand(); return true }
                    val cx = full.left + full.translationX + full.width / 2f
                    val cy = full.top + full.translationY + full.height / 2f
                    Store.miniCorner = Corners.nearest(cx, cy, root.width, root.height).name
                    applyState()
                }
                MotionEvent.ACTION_CANCEL -> { full.translationX = 0f; full.translationY = 0f }
            }
            return true
        }
    }

    // ---- picture-in-picture ----

    private val pipActions = "app.plain.download.PIP"
    private var pipReceiver: BroadcastReceiver? = null

    /** PiP button: over other apps, video only. Android places and sizes the window; the app can't pin it. */
    fun enterPip() {
        if (mode != PlayMode.WATCH || state == PState.CLOSED) { toast(tr("Picture-in-picture is for Watch", "Pencere içinde pencere yalnızca İzle için")); return }
        if (state == PState.FULL) minimize() // the window then animates out of the top-right mini window
        root.post {
            try {
                registerPipActions()
                if (ui?.enterPictureInPictureMode(pipParams()) == true) go(PlayerUi.Ev.ENTER_PIP)
            } catch (e: Exception) { toast(tr("Picture-in-picture isn't available: ${e.message}", "Pencere içinde pencere kullanılamıyor: ${e.message}")) }
        }
    }

    /** The activity entered or left PiP (also auto-enter on Home). */
    fun onPip(inPip: Boolean) {
        if (inPip) { if (state != PState.PIP) { registerPipActions(); go(PlayerUi.Ev.ENTER_PIP) } }
        else if (state == PState.PIP) go(PlayerUi.Ev.LEAVE_PIP)
    }

    private fun pipParams(): android.app.PictureInPictureParams {
        val r = (videoW.toFloat() / videoH).coerceIn(1 / 2.39f, 2.39f)
        val ratio = if (r >= 1) android.util.Rational((r * 1000).toInt(), 1000) else android.util.Rational(1000, (1000 / r).toInt())
        val b = android.app.PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(pipRemoteActions())
        val rect = android.graphics.Rect()
        if (videoFrame.getGlobalVisibleRect(rect) && !rect.isEmpty) b.setSourceRectHint(rect)
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            b.setAutoEnterEnabled(Store.pipAuto && state != PState.CLOSED && mode == PlayMode.WATCH && mp?.isPlaying == true)
            b.setSeamlessResizeEnabled(true)
        }
        return b.build()
    }

    /** Keeps PiP actions (play/pause icon) and auto-enter in step with the playback state. */
    fun updatePip(force: Boolean = false) {
        if (!force && state != PState.PIP && !Store.pipAuto) return
        try { ui?.setPictureInPictureParams(pipParams()) } catch (_: Exception) {}
    }

    private fun pipRemoteActions(): List<android.app.RemoteAction> {
        fun action(code: Int, icon: Int, label: String, cmd: String) = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(app, icon), label, label,
            android.app.PendingIntent.getBroadcast(app, code, Intent(pipActions).setPackage(app.packageName).putExtra("cmd", cmd),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
        val playing = mp?.isPlaying == true
        return listOf(
            action(1, R.drawable.yt_ic_back10, tr("Back 10 s", "10 sn geri"), "back"),
            action(2, if (playing) R.drawable.yt_ic_pause else R.drawable.yt_ic_play, if (playing) tr("Pause", "Duraklat") else tr("Play", "Oynat"), "toggle"),
            action(3, R.drawable.yt_ic_fwd10, tr("Forward 10 s", "10 sn ileri"), "fwd"),
        )
    }

    private fun registerPipActions() {
        if (pipReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.getStringExtra("cmd")) { "back" -> seekBy(-10_000); "fwd" -> seekBy(10_000); "toggle" -> togglePlay() }
            }
        }
        pipReceiver = r
        if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, IntentFilter(pipActions), Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, IntentFilter(pipActions))
    }

    private fun unregisterPipActions() { pipReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; pipReceiver = null }

    // ---- volume (media stream, like the hardware keys) ----

    private fun registerVolume(on: Boolean) {
        if (on && volReceiver == null) {
            val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { syncVolume() } }
            volReceiver = r
            val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (android.os.Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(r, f)
            syncVolume()
        } else if (!on) { volReceiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) {} }; volReceiver = null }
    }

    private fun syncVolume() {
        if (!::volBar.isInitialized) return
        vol.sync(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        volBar.max = vol.max
        if (volBar.progress != vol.level) volBar.progress = vol.level
        volIcon.setImageResource(if (vol.muted) R.drawable.yt_ic_volume_off else R.drawable.yt_ic_volume)
    }

    private fun setVolume(v: Int) {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol.set(v), 0) // no system volume panel
        volIcon.setImageResource(if (vol.muted) R.drawable.yt_ic_volume_off else R.drawable.yt_ic_volume)
    }

    // ---- controls visibility ----

    private fun showControls(show: Boolean, kick: Boolean = true) {
        if (state != PState.FULL) return
        controls.visibility = if (show && !fullscreen) View.VISIBLE else if (show && fullscreen) View.VISIBLE else View.GONE
        topBar.visibility = if (show && !fullscreen) View.VISIBLE else View.GONE
        if (show && kick) kickHide()
    }

    /** Controls hide 3 s after the last touch while a video plays (one delayed post, only while the player is full). */
    private fun kickHide() {
        uiMain.removeCallbacks(hideCtl)
        if (state == PState.FULL && mode == PlayMode.WATCH && mp?.isPlaying == true) uiMain.postDelayed(hideCtl, 3000)
    }

    private fun flashSeek(text: String, left: Boolean) {
        seekHint.text = text
        (seekHint.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (left) Gravity.START else Gravity.END)
        seekHint.requestLayout()
        seekHint.animate().cancel()
        seekHint.alpha = 1f; seekHint.visibility = View.VISIBLE
        seekHint.animate().alpha(0f).setStartDelay(350).setDuration(450).withEndAction { seekHint.visibility = View.GONE }.start()
    }

    // ---------------------------------------------------------------------------------------------------------
    // layout

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun build() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        full = root
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            insetTop = ins.systemWindowInsetTop
            @Suppress("DEPRECATION")
            insetBottom = ins.systemWindowInsetBottom
            if (state == PState.FULL && !fullscreen) root.setPadding(0, insetTop, 0, insetBottom)
            ins
        }
        this.root.addView(root, FrameLayout.LayoutParams(MATCH, MATCH))
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(8), dp(4)) }
        topBar.addView(icon(R.drawable.yt_ic_minimize, desc = "") { minimize() }.desc { tr("Minimise", "Küçült") })
        val tcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleV = text(title.ifEmpty { tr("Player", "Oynatıcı") }, 15f, C.TEXT, bold = true, lines = 2)
        channelV = text(channel, 12f, C.DIM).apply { setOnClickListener { visitChannel() } }
        tcol.addView(titleV); tcol.addView(channelV)
        topBar.addView(tcol, lpL(0, WRAP, 1f))
        membersV = membersBadge().apply { visibility = if (Store.isMembers(videoId)) View.VISIBLE else View.GONE }
        topBar.addView(membersV, lpL(WRAP, WRAP).apply { marginEnd = dp(4) })
        topBar.addView(icon(R.drawable.yt_ic_pip, desc = "") { enterPip() }.desc { tr("Picture-in-picture", "Pencere içinde pencere") })
        topBar.addView(icon(R.drawable.yt_ic_close, desc = "") { close() }.desc { tr("Close the player", "Oynatıcıyı kapat") })
        root.addView(topBar, lpL(MATCH, WRAP))

        stage = FrameLayout(this)
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleControls(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (mp == null || !prepared) return false
                val left = e.x < stage.width / 2f
                seekBy(if (left) -10_000 else 10_000)
                flashSeek(if (left) tr("« 10 s", "« 10 sn") else tr("10 s »", "10 sn »"), left)
                kickHide()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > 1500 && Math.abs(vy) > Math.abs(vx) * 1.5f && !fullscreen) { minimize(); return true }
                return false
            }
            // hold: 2× while the finger stays down
            override fun onLongPress(e: MotionEvent) { if (adjust == 0) boost(true) }
            // fullscreen: slide up/down on the left half = brightness, right half = volume
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!fullscreen || e1 == null || e2.pointerCount > 1 || boosted) return false
                if (adjust == 0) {
                    val mx = Math.abs(e2.x - e1.x); val my = Math.abs(e2.y - e1.y)
                    if (my < dp(16) || my < mx * 1.5f) return false
                    adjust = if (e1.x < stage.width / 2f) 1 else 2
                    volAcc = 0f
                }
                val f = dy / stage.height.coerceAtLeast(1) // finger up = more
                if (adjust == 1) brightnessBy(f) else volumeBy(f)
                return true
            }
        })
        stage.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) adjust = 0
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                boost(false)
                if (adjust != 0) { adjust = 0; fadeHint() }
            }
            if (state == PState.FULL) gd.onTouchEvent(e) else false
        }
        videoFrame = AspectFrame(this)
        stage.addView(videoFrame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        seekHint = text("", 18f, C.TEXT, bold = true).apply {
            background = oval(0x66000000); gravity = Gravity.CENTER; visibility = View.GONE; setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        stage.addView(seekHint, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { setMargins(dp(32), 0, dp(32), 0) })
        // mini window (Watch): tap = full, drag = corner, ▶ ✕ and PiP
        miniCtl = FrameLayout(this).apply { setOnTouchListener(dragTouch()) }
        miniPlay = icon(R.drawable.yt_ic_play, sizeDp = 32, padDp = 6, desc = "") { togglePlay() }.desc { tr("Play / pause", "Oynat / duraklat") }
        miniCtl.addView(miniPlay, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.START))
        miniCtl.addView(icon(R.drawable.yt_ic_close, sizeDp = 32, padDp = 7, desc = "") { close() }.desc { tr("Close the player", "Oynatıcıyı kapat") },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.END))
        miniCtl.addView(icon(R.drawable.yt_ic_pip, sizeDp = 32, padDp = 6, desc = "") { enterPip() }.desc { tr("Picture-in-picture", "Pencere içinde pencere") },
            FrameLayout.LayoutParams(dp(32), dp(32), Gravity.BOTTOM or Gravity.END))
        stage.addView(miniCtl, FrameLayout.LayoutParams(MATCH, MATCH))
        art = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        artImg = RoundImage(this)
        art.addView(artImg, lpL(dp(240), dp(135)))
        art.addView(text("♪", 28f, C.ACCENT).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }, lpL(MATCH, WRAP))
        artChannel = text(channel, 14f, C.DIM).apply { gravity = Gravity.CENTER; setOnClickListener { visitChannel() } }
        art.addView(artChannel, lpL(MATCH, WRAP))
        stage.addView(art, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(stage, lpL(MATCH, 0, 1f))
        // mini card (Listen): cover, title, ▶, ✕
        miniCard = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(6), dp(2), dp(6)); setOnTouchListener(dragTouch()) }
        miniCardThumb = RoundImage(this)
        miniCard.addView(miniCardThumb, lpL(dp(64), dp(36)))
        miniCardTitle = text("", 12f, C.TEXT, bold = true, lines = 2).apply { setPadding(dp(8), 0, dp(4), 0) }
        miniCard.addView(miniCardTitle, lpL(0, WRAP, 1f))
        miniCardPlay = icon(R.drawable.yt_ic_play, sizeDp = 36, padDp = 8, desc = "") { togglePlay() }.desc { tr("Play / pause", "Oynat / duraklat") }
        miniCard.addView(miniCardPlay)
        miniCard.addView(icon(R.drawable.yt_ic_close, sizeDp = 36, padDp = 9, desc = "") { close() }.desc { tr("Close the player", "Oynatıcıyı kapat") })
        root.addView(miniCard, lpL(MATCH, WRAP))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12)) }
        statusV = text("", 12f, C.LABEL, lines = 3).apply { setPadding(dp(4), 0, dp(4), dp(4)) }
        controls.addView(statusV, lpL(MATCH, WRAP))
        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        posV = text("0:00", 12f, C.DIM)
        durV = text("0:00", 12f, C.DIM)
        seek = SeekBar(this).apply {
            minHeight = dp(6); maxHeight = dp(6) // bigger bar; the grey part = cached
            progressTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
            secondaryProgressTintList = android.content.res.ColorStateList.valueOf(C.FAINT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(C.OUTLINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) posV.text = Text.duration(p / 1000L).ifEmpty { "0:00" } }
                override fun onStartTrackingTouch(s: SeekBar) { dragging = true }
                override fun onStopTrackingTouch(s: SeekBar) { dragging = false; seekTo(s.progress) }
            })
        }
        seekRow.addView(posV); seekRow.addView(seek, lpL(0, WRAP, 1f)); seekRow.addView(durV)
        controls.addView(seekRow, lpL(MATCH, WRAP))
        // volume: the media stream, like the hardware keys (Spotify-style bar, speaker = mute)
        val volRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, 0) }
        volIcon = icon(R.drawable.yt_ic_volume, sizeDp = 36, padDp = 8, desc = "") { setVolume(vol.toggleMute()); syncVolumeBar() }.desc { tr("Mute", "Sesi kapat") }
        volBar = SeekBar(this).apply {
            max = vol.max
            progressTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            thumbTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(C.OUTLINE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) setVolume(p) }
                override fun onStartTrackingTouch(s: SeekBar) { uiMain.removeCallbacks(hideCtl) }
                override fun onStopTrackingTouch(s: SeekBar) { kickHide() }
            })
        }
        volRow.addView(volIcon); volRow.addView(volBar, lpL(0, WRAP, 1f))
        controls.addView(volRow, lpL(MATCH, WRAP))
        val btnRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        prevBtn = pill("⏮") { step(-1) }
        btnRow.addView(prevBtn)
        btnRow.addView(View(this), lpL(dp(8), 1))
        btnRow.addView(pill("") { seekBy(-10_000); kickHide() }.say { tr("−10 s", "−10 sn") })
        btnRow.addView(View(this), lpL(dp(12), 1))
        playBtn = pill("▶", C.ACCENT, C.ACCENT) { togglePlay() }.apply { minWidth = dp(72) }
        btnRow.addView(playBtn)
        btnRow.addView(View(this), lpL(dp(12), 1))
        btnRow.addView(pill("") { seekBy(10_000); kickHide() }.say { tr("+10 s", "+10 sn") })
        btnRow.addView(View(this), lpL(dp(8), 1))
        nextBtn = pill("⏭") { step(1) }
        btnRow.addView(nextBtn)
        controls.addView(btnRow, lpL(MATCH, WRAP))
        upNextV = text("", 12f, C.LABEL).apply { setPadding(dp(4), 0, dp(4), dp(4)); setOnClickListener { step(1) } }
        controls.addView(upNextV, lpL(MATCH, WRAP))
        val chips = FlowLayout(this)
        watchChip = Chip(this, "") { switchMode(PlayMode.WATCH) }.say { tr("Watch", "İzle") }
        listenChip = Chip(this, "") { switchMode(PlayMode.LISTEN) }.say { tr("Listen", "Dinle") }
        qualityBtn = pill(tr("Quality", "Kalite")) { qualityMenu() }
        audioBtn = pill(tr("Audio", "Ses")) { audioMenu() }
        saveBtn = pill("") { save() }.say { tr("Save", "Kaydet") }
        dlBtn = pill("") { openDownload() }.say { tr("Download…", "İndir…") }
        chips.addView(watchChip); chips.addView(listenChip); chips.addView(qualityBtn); chips.addView(audioBtn); chips.addView(saveBtn); chips.addView(dlBtn)
        speedBtn = pill(Speeds.label(speed)) { speedMenu() }
        chips.addView(speedBtn)
        chips.addView(pill("") { setFullscreen(!fullscreen) }.say { tr("Full screen", "Tam ekran") })
        rotateBtn = pill(rotateText()) { toggleRotation() }
        chips.addView(rotateBtn)
        chips.addView(pill("") { visitChannel() }.say { tr("Channel", "Kanal") })
        chips.addView(pill("") { if (videoId.isNotEmpty()) Hold.shareLink(this, videoId, title) else toast(tr("Local file: no link to share", "Yerel dosya: paylaşılacak bağlantı yok")) }.say { tr("Share", "Paylaş") })
        queueBtn = pill(tr("Queue", "Sıra")) { queueSheet() }
        chips.addView(queueBtn)
        controls.addView(chips, lpL(MATCH, WRAP))
        root.addView(controls, lpL(MATCH, WRAP))
    }

    private fun syncVolumeBar() { if (volBar.progress != vol.level) volBar.progress = vol.level }

    private fun speedMenu() {
        val a = ui as? android.app.Activity ?: return
        Sheet.choose(a, tr("Playback speed", "Oynatma hızı"), Speeds.ALL.map { Speeds.label(it) }, Speeds.ALL.indexOf(speed), C.ACCENT) { setSpeed(Speeds.ALL[it]) }
    }

    private var boosted = false
    /** A fullscreen slide in progress: 0 none, 1 brightness, 2 volume. */
    private var adjust = 0
    private var volAcc = 0f
    /** The window brightness was changed by a slide (restored to the system's when fullscreen ends). */
    private var brightSet = false

    private fun brightnessBy(f: Float) {
        val w = ui?.window ?: return
        val now = w.attributes.screenBrightness.takeIf { it >= 0f }
            ?: (try { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f } catch (_: Exception) { 0.5f })
        val v = (now + f).coerceIn(0.01f, 1f)
        w.attributes = w.attributes.apply { screenBrightness = v }
        brightSet = true
        showHint("☀ ${Math.round(v * 100)}%", true)
    }

    private fun volumeBy(f: Float) {
        volAcc += f * vol.max * 1.5f
        val steps = volAcc.toInt()
        if (steps != 0) {
            volAcc -= steps
            vol.sync(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            setVolume(vol.level + steps)
            syncVolumeBar()
        }
        showHint("♪ ${vol.level} / ${vol.max}", false)
    }

    private fun restoreBrightness() {
        if (!brightSet) return
        brightSet = false
        ui?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = -1f } }
    }

    private fun showHint(text: String, left: Boolean) {
        seekHint.text = text
        (seekHint.layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (left) Gravity.START else Gravity.END)
        seekHint.requestLayout()
        seekHint.animate().cancel()
        seekHint.alpha = 1f; seekHint.visibility = View.VISIBLE
    }

    private fun fadeHint() = seekHint.animate().alpha(0f).setStartDelay(300).setDuration(300).withEndAction { seekHint.visibility = View.GONE }.start()

    /** Hold on the picture: play at 2× (or the chosen speed if faster) until the finger lifts, then back to [speed]. */
    private fun boost(on: Boolean) {
        val p = mp ?: return
        if (on == boosted) return
        if (on && (!prepared || !p.isPlaying || speed >= 2f)) return
        boosted = on
        // a paused player must not get a speed (that starts it): play() sets [speed] again
        if (p.isPlaying) try { p.playbackParams = (p.playbackParams ?: PlaybackParams()).setSpeed(if (on) 2f else speed) } catch (_: Exception) {}
        if (on) {
            stage.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            showHint("2× »", false)
        } else seekHint.animate().alpha(0f).setDuration(250).withEndAction { seekHint.visibility = View.GONE }.start()
    }

    private fun setSpeed(s: Float) {
        speed = s
        speedBtn.text = Speeds.label(s)
        // setting a speed on a paused MediaPlayer starts it, so a paused player gets it on the next play()
        val p = mp
        if (p != null && prepared && p.isPlaying) try { p.playbackParams = (p.playbackParams ?: PlaybackParams()).setSpeed(s) } catch (_: Exception) {}
    }

    private fun toggleRotation() {
        rotationLocked = !rotationLocked
        rotateBtn.text = rotateText()
        if (state == PState.FULL && !fullscreen) ui?.requestedOrientation = if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR
    }

    private fun showMode() {
        val watch = mode == PlayMode.WATCH
        watchChip.select(watch); listenChip.select(!watch)
        qualityBtn.visibility = if (watch && localUri == null) View.VISIBLE else View.GONE
        qualityBtn.text = tr("Quality ${height}p", "Kalite ${height}p")
        showAudio()
        art.visibility = if (watch) View.GONE else View.VISIBLE
        videoFrame.visibility = if (watch) View.VISIBLE else View.GONE
        if (watch && surfaceView == null) {
            surfaceView = SurfaceView(this).also { sv -> sv.holder.addCallback(this); videoFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH)) }
        } else if (!watch && surfaceView != null) {
            videoFrame.removeAllViews(); surfaceView = null; holder = null // Listen: no surface at all
        }
        if (!watch && videoId.isNotEmpty()) thumbs.load(artImg, videoId, dp(240))
        titleV.text = title.ifEmpty { tr("Player", "Oynatıcı") }
        if (state == PState.MINI) applyState() // Watch <-> Listen changes the mini window's shape
    }

    private fun toggleControls() { showControls(controls.visibility != View.VISIBLE) }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        if (!on) restoreBrightness()
        ui?.let { Hub.setHeaderShown(it, !on) }
        ui?.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else if (rotationLocked) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        full.setPadding(0, if (on) 0 else insetTop, 0, if (on) 0 else insetBottom)
        ui?.window?.insetsController?.let { c ->
            if (on) { c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(WindowInsets.Type.systemBars()) }
            else c.show(WindowInsets.Type.systemBars())
        }
        topBar.visibility = if (on) View.GONE else View.VISIBLE
        controls.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun status(s: String?, error: Boolean = false) {
        statusV.text = s.orEmpty()
        statusV.setTextColor(if (error) C.RED else C.LABEL)
    }

    // ---------------------------------------------------------------------------------------------------------
    // sources

    private fun start() {
        // hidden (background, or no window at all): a streamed Watch item plays audio-only; video comes back when shown
        if (bg.startHidden(itemMode == PlayMode.WATCH && localUri == null)) { mode = PlayMode.LISTEN; forced = PlayMode.LISTEN }
        val my = ++seq
        prepared = false
        pubFile = null
        localUri?.let { preparePlayer(it); return }
        status(tr("Loading…", "Yükleniyor…"))
        val g = calls.gen
        val m0 = mode
        io.execute {
            val r = try { Result.success(resolve(g, m0)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (my != seq || isDestroyed) { (r.getOrNull() as? CachedSource)?.close(); return@post }
                r.fold({ src ->
                    if (src is Saved.File) { pubFile = src; preparePlayer(Uri.parse(src.uri)) } // offline, the saved file
                    else startSource(src as CachedSource)
                }, { e -> status(errText(e), true) })
                showAudio()
            }
        }
    }

    private fun errText(e: Throwable): String = when (e) {
        is NoWifi -> tr("Not on Wi-Fi (Wi-Fi only is on). Cached videos still play.", "Wi-Fi bağlı değil (Yalnızca Wi-Fi açık). Önbellekteki videolar yine oynar.")
        is java.net.UnknownHostException -> tr("No internet connection", "İnternet bağlantısı yok")
        is YtError, is AccountExpired -> e.message ?: tr("Can't play", "Oynatılamıyor")
        is Cancelled -> ""
        else -> tr("Couldn't start: ${e.message ?: e.javaClass.simpleName}", "Başlatılamadı: ${e.message ?: e.javaClass.simpleName}")
    }

    /**
     * Background thread: pick the mode; play the saved file when there is one (a download or a completed cache —
     * offline, and never a second copy), else complete caches, else ask YouTube.
     */
    private fun resolve(g: Int, wanted: PlayMode): Any {
        // completed caches are saved first (3.3: one library), so below only the saved file or a partial cache remains
        for (k in CacheKind.values()) for (c in Store.cache.list(k)) if (c.videoId == videoId && c.complete) try { CachePublisher.publish(this, c) } catch (_: Exception) {}
        val music = Store.cache.list(CacheKind.MUSIC).filter { it.videoId == videoId }
        val video = Store.cache.list(CacheKind.VIDEO).filter { it.videoId == videoId }
        var m: PlayMode? = forced ?: Store.mode(videoId)
        val savedA = Saved.find(this, videoId, Kind.AUDIO)
        val savedV = Saved.find(this, videoId, Kind.VIDEO)
        if (m == null) {
            if (savedA != null && savedV == null) m = PlayMode.LISTEN else if (savedV != null && savedA == null) m = PlayMode.WATCH
        }
        if (m == null && wanted != PlayMode.WATCH) m = wanted
        // offline: the saved file (Listen also plays a saved video, without its picture). Another audio language than
        // the original isn't in the saved file: then it streams.
        if (audioLang == null && !triedOpus) {
            val f = when (m) { PlayMode.LISTEN -> savedA ?: savedV; PlayMode.WATCH -> savedV; null -> savedV ?: savedA }
            if (f != null) { setMode(m ?: if (f.audio) PlayMode.LISTEN else PlayMode.WATCH); return f }
        }
        if (m == PlayMode.LISTEN) music.filter { it.complete && it.langMatches(audioLang) && (!triedOpus || it.ext == "webm") }.maxByOrNull { it.lastUsed }?.let {
            setMode(m); touch(it); return musicSource(it, null)
        }
        if (m == PlayMode.WATCH) video.filter { it.complete && it.langMatches(audioLang) }.let { l -> (l.firstOrNull { it.quality == height } ?: Hls.pickMeta(l, height)) }
            ?.let { setMode(m); touch(it); return if (it.ext == "mp4") ProgressiveSource(this, it, null, "", null) else hlsOffline(it) }

        calls.check(g)
        val net = Net.network(this, Store.wifiOnly)
        val inf = info ?: InnerTube.info(videoId, net, order = Clients.PLAY_ORDER).also { info = it }
        AudioTracks.of(inf.formats).takeIf { it.size > 1 }?.let { t -> main.post { tracks = t; showAudio() } }
        if (inf.members) main.post { membersV.visibility = View.VISIBLE }
        if (title.isEmpty()) title = inf.title
        if (channel.isEmpty()) channel = Naming.channel(inf.author)
        if (m == null) {
            m = if (inf.author.trim().endsWith(" - Topic") || musicLink) PlayMode.LISTEN else {
                val (type, cat) = WebApi.musicMeta(this, calls, g, videoId)
                Modes.decide(null, type, cat, inf.author, musicLink)
            }
        }
        setMode(m)
        if (m == PlayMode.LISTEN) {
            // album folder for the public copy: the YouTube Music album of a "- Topic" track, else the playlist
            val track = inf.author.trim().endsWith(" - Topic")
            album = Albums.pick(if (track) WebApi.remixAlbum(this, calls, g, videoId) else null, track, albumHint)
        }
        if (m == PlayMode.WATCH && inf.members && inf.hls == null) {
            val src = Sources.membersVideo(this, inf, title, channel)
            height = src.meta.quality
            main.post { qualityBtn.text = tr("Quality ${height}p", "Kalite ${height}p") }
            return src
        }
        return if (m == PlayMode.LISTEN) musicSource(null, inf) else hlsSource(g, net, inf)
    }

    private var album: String? = null

    private fun touch(m: CacheMeta) { m.lastUsed = System.currentTimeMillis(); Store.cache.save(m) }

    private fun setMode(m: PlayMode) { mode = m; forced = m; main.post { showMode() } } // decided once per session

    private fun musicSource(cached: CacheMeta?, inf: VideoInfo?): CachedSource {
        if (cached != null) { listenItag = cached.quality; return ProgressiveSource(this, cached, null, "", null) }
        inf ?: throw IOException(tr("No stream info", "Akış bilgisi yok"))
        val f = (if (triedOpus) AudioTracks.select(inf.formats.filter { !it.hasVideo }, audioLang).firstOrNull { it.itag == 251 } else null)
            ?: Modes.listenFormat(inf.formats, audioLang)
            ?: throw YtError(tr("No audio stream offered", "Ses akışı sunulmuyor"), false)
        listenItag = f.itag
        val lang = f.audioLang.orEmpty()
        val key = CacheMeta.keyOf(videoId, f.itag, lang)
        val meta = Store.cache.get(CacheKind.MUSIC, key)?.takeIf { it.total == f.contentLength || f.contentLength <= 0 }
            ?: CacheMeta(CacheKind.MUSIC, videoId, f.itag, title, channel, inf.durationSec, total = maxOf(0L, f.contentLength),
                ext = if (f.isOpus) "webm" else "m4a", lang = lang, langOriginal = f.audioOriginal, langKnown = true)
                .also { Store.cache.remove(CacheKind.MUSIC, key) }
        meta.title = title; meta.channel = channel
        if (meta.album == null) meta.album = album
        val skip = HashSet<String>()
        var client = inf.client
        val refresh = {
            skip += client
            val i2 = InnerTube.info(videoId, Net.network(this, Store.wifiOnly), skip, Clients.PLAY_ORDER)
            client = i2.client
            val nf = i2.formats.firstOrNull { it.itag == f.itag && it.audioLang == f.audioLang } ?: throw YtError(tr("Stream no longer offered", "Akış artık sunulmuyor"), false)
            nf.url to (Clients.byKey(i2.client) ?: Clients.VISIONOS).userAgent
        }
        return ProgressiveSource(this, meta, f.url, (Clients.byKey(inf.client) ?: Clients.VISIONOS).userAgent, refresh)
    }

    private class Hl(val variant: Hls.Variant, val v: List<Hls.Seg>, val a: List<Hls.Seg>?, val vText: String, val aText: String?, val lang: String, val langOriginal: Boolean)

    private fun loadHls(g: Int, inf0: VideoInfo, want: Int): Pair<Hl, String> {
        var inf = inf0
        if (inf.hls == null) inf = InnerTube.info(videoId, Net.network(this, Store.wifiOnly), order = Clients.HLS_ORDER)
        val url = inf.hls ?: throw YtError(tr("No video stream for this video (try Listen)", "Bu video için video akışı yok (Dinle'yi deneyin)"), false)
        val ua = (Clients.byKey(inf.client) ?: Clients.VISIONOS).userAgent
        val master = Hls.parseMaster(String(WebApi.get(this, calls, g, url, ua), Charsets.UTF_8), url)
        choices = Hls.choices(master)
        master.tracks.takeIf { it.isNotEmpty() }?.let { t -> main.post { tracks = t; showAudio() } }
        val v = Hls.pick(choices, want) ?: throw YtError(tr("No H.264 video stream", "H.264 video akışı yok"), false)
        val vText = String(WebApi.get(this, calls, g, v.uri, ua), Charsets.UTF_8)
        // dubbed videos: the original track unless another language was picked (the first listed is usually Arabic)
        val a = v.audio?.let { master.audioFor(it, audioLang) }
        val aUri = a?.first
        val lang = a?.second.orEmpty()
        val orig = lang.isNotEmpty() && master.tracks.firstOrNull { it.lang == lang }?.original == true
        val aText = aUri?.let { String(WebApi.get(this, calls, g, it, ua), Charsets.UTF_8) }
        return Hl(v, Hls.parseMedia(vText, v.uri), aUri?.let { Hls.parseMedia(aText!!, it) }, vText, aText, lang, orig) to ua
    }

    private fun hlsSource(g: Int, net: android.net.Network?, inf: VideoInfo): CachedSource {
        val (h, ua) = loadHls(g, inf, height)
        height = h.variant.height
        main.post { qualityBtn.text = tr("Quality ${height}p", "Kalite ${height}p") }
        val key = CacheMeta.keyOf(videoId, height, h.lang)
        val old = Store.cache.get(CacheKind.VIDEO, key)
        val meta = old?.takeIf { it.total == h.v.size.toLong() && (h.a == null || h.a.size == h.v.size) }
            ?: CacheMeta(CacheKind.VIDEO, videoId, height, title, channel, inf.durationSec, total = h.v.size.toLong(),
                lang = h.lang, langOriginal = h.langOriginal, langKnown = true).also { Store.cache.remove(CacheKind.VIDEO, key) }
        meta.title = title; meta.channel = channel
        meta.codecs = h.variant.codecs; meta.bandwidth = h.variant.bandwidth; meta.resolution = "${h.variant.width}x${h.variant.height}"
        val dir = Store.cache.entryDir(CacheKind.VIDEO, key).apply { mkdirs() }
        HlsSource.segsFile(dir, false).writeText(h.vText)
        h.aText?.let { HlsSource.segsFile(dir, true).writeText(it) }
        val refresh = {
            val i2 = InnerTube.info(videoId, Net.network(this, Store.wifiOnly), order = Clients.HLS_ORDER)
            val (h2, _) = loadHls(calls.gen, i2, height)
            h2.v to h2.a
        }
        return HlsSource(this, meta, h.v, h.a, ua, refresh)
    }

    private fun hlsOffline(m: CacheMeta): CachedSource {
        height = m.quality
        main.post { qualityBtn.text = tr("Quality ${height}p", "Kalite ${height}p") }
        val dir = Store.cache.entryDir(CacheKind.VIDEO, m.key)
        val v = Hls.parseMedia(HlsSource.segsFile(dir, false).readText(), "https://x/")
        val a = HlsSource.segsFile(dir, true).takeIf { it.exists() }?.let { Hls.parseMedia(it.readText(), "https://x/") }
        return HlsSource(this, m, v, a, Clients.VISIONOS.userAgent, null)
    }

    private fun startSource(src: CachedSource) {
        source = src
        src.start()
        val p = try { StreamProxy(src).start() } catch (e: Exception) { status(tr("Couldn't start the player: ${e.message}", "Oynatıcı başlatılamadı: ${e.message}"), true); src.close(); return }
        proxy = p
        titleV.text = title.ifEmpty { tr("Player", "Oynatıcı") }
        preparePlayer(Uri.parse(p.url))
    }

    private fun preparePlayer(uri: Uri) {
        val p = MediaPlayer()
        mp = p
        prepared = false
        val my = seq
        p.setWakeMode(app, android.os.PowerManager.PARTIAL_WAKE_LOCK) // held by MediaPlayer only while it plays
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (mode == PlayMode.LISTEN) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE).build())
        p.setOnPreparedListener {
            if (my != seq) return@setOnPreparedListener
            prepared = true
            seek.max = it.duration.coerceAtLeast(0)
            durV.text = Text.duration(it.duration / 1000L).ifEmpty { "0:00" }
            if (resumeMs > 0) it.seekTo(resumeMs)
            if (playWhenReady) play() else { updateTime(); updatePlayBtn() }
            syncMeta()
            status(cacheText())
        }
        p.setOnCompletionListener {
            if (my == seq) Resume.clear(resumeKey()) // watched to the end: next time starts from the beginning
            if (my == seq && queueMode && Store.queue().hasNext) main.post { step(1) } // auto-advance (also in the background); no prefetch
            else if (my == seq) {
                // the end: nothing keeps running (no foreground service, focus given back, released when in the background)
                playWhenReady = false
                noisyWatch(false); abandonFocus()
                if (bg.hidden) { resumeMs = 0; releaseAll() }
            }
            updatePlayBtn(); screenOn(false); status(cacheText())
        }
        p.setOnErrorListener { _, what, extra -> if (my == seq) onPlayError(what, extra); true }
        p.setOnVideoSizeChangedListener { _, w, h -> videoFrame.setAspect(w, h); if (w > 0 && h > 0) { videoW = w; videoH = h; updatePip() } }
        p.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> status(tr("Buffering…", "Arabelleğe alınıyor…"))
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> status(cacheText())
            }
            false
        }
        try {
            p.setDataSource(this, uri)
            if (mode == PlayMode.WATCH) holder?.let { p.setDisplay(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            status(tr("Can't play this: ${e.message}", "Bu oynatılamıyor: ${e.message}"), true)
        }
    }

    private fun cacheText(): String {
        pubFile?.let { return tr("Saved: ", "Kayıtlı: ") + it.relPath.orEmpty() + tr(" · plays offline", " · çevrimdışı oynar") }
        val m = source?.meta ?: return if (localUri != null) tr("Saved file", "Kayıtlı dosya") else ""
        val what = if (m.kind == CacheKind.MUSIC) tr("Music", "Müzik") else "Video"
        return if (m.complete) tr("Cached ($what) · plays offline", "Önbellekte ($what) · çevrimdışı oynar") else tr("Caching while you watch · ${(m.fraction * 100).toInt()}% · ${Text.size(m.size)}", "İzlerken önbelleğe alınıyor · %${(m.fraction * 100).toInt()} · ${Text.size(m.size)}")
    }

    private fun onPlayError(what: Int, extra: Int) {
        val err = source?.error
        when {
            err is NoWifi -> status(errText(err), true)
            localUri != null || pubFile != null -> status(tr("This file can't be played ($what/$extra)", "Bu dosya oynatılamıyor ($what/$extra)"), true)
            mode == PlayMode.WATCH && !watchFailed -> {
                watchFailed = true
                toast(tr("This phone couldn't play the video stream: playing the audio instead", "Bu telefon video akışını oynatamadı: yerine ses oynatılıyor"))
                restartAt(PlayMode.LISTEN, remember = false)
            }
            mode == PlayMode.LISTEN && !triedOpus && listenItag != 251 && info?.formats?.any { it.itag == 251 } == true -> {
                triedOpus = true
                restartAt(PlayMode.LISTEN, remember = false)
            }
            else -> status(tr("Playback failed", "Oynatma başarısız") + (err?.message?.let { ": $it" } ?: " ($what/$extra)"), true)
        }
    }

    /** Where this item was left (3.3 "Continue from last session?"): at pause, close and item changes, no timer. */
    private fun resumeKey() = videoId.ifEmpty { localUri?.toString().orEmpty() }

    private fun rememberPos() {
        val p = mp ?: return
        if (!prepared) return
        try { Resume.put(resumeKey(), p.currentPosition.toLong(), p.duration.toLong()) } catch (_: Exception) {}
    }

    private fun releaseAll() {
        rememberPos()
        seq++
        main.removeCallbacks(tick)
        screenOn(false)
        calls.cancelAll()
        mp?.let { try { it.release() } catch (_: Exception) {} }
        mp = null
        prepared = false
        val p = proxy; val s = source; val sv = saveOnClose
        proxy = null; source = null
        val app = applicationContext
        if (p != null || s != null) Thread({
            p?.close() ?: s?.close() // stops the filler; the partial cache stays for next time
            val m = s?.meta
            // complete now: saved like a download, Music|Movies/PlainDownload/<Channel>/ (one copy; Plain Music sees it)
            if (m != null && m.complete) try { CachePublisher.publish(app, m) } catch (_: Exception) {}
            if (sv != null && sv !== m) try { CachePublisher.save(app, sv) } catch (_: Exception) {}
        }, "player-close").start()
        saveOnClose = null
        updatePlayBtn()
    }

    // ---------------------------------------------------------------------------------------------------------
    // controls

    private fun play() {
        val p = mp ?: return
        if (!prepared) { playWhenReady = true; return }
        if (!requestFocus()) { status(tr("Another app is playing (audio focus refused)", "Başka bir uygulama çalıyor (ses odağı reddedildi)"), true); return }
        everPlayed = true
        noisyWatch(true)
        if (p.currentPosition >= p.duration - 300 && p.duration > 0) p.seekTo(0)
        // also undoes a 2× hold that ended while paused
        try { if (speed != 1f || p.playbackParams.speed != 1f) p.playbackParams = (p.playbackParams ?: PlaybackParams()).setSpeed(speed) } catch (_: Exception) {}
        p.start()
        playWhenReady = true
        screenOn(true)
        main.removeCallbacks(tick); main.post(tick)
        updatePlayBtn()
        status(cacheText())
        kickHide()
    }

    private fun pause(keepFocus: Boolean = false) {
        mp?.takeIf { prepared && it.isPlaying }?.pause()
        rememberPos()
        playWhenReady = false
        screenOn(false)
        main.removeCallbacks(tick)
        noisyWatch(false)
        if (!keepFocus) abandonFocus()
        updateTime(); updatePlayBtn()
        status(cacheText())
        // paused in the background: release everything (proxy, read-ahead) — nothing runs; play resumes from here
        if (EngineRule.releaseOnPause(bg.hidden, ui != null)) {
            mp?.let { if (prepared) resumeMs = it.currentPosition }
            releaseAll()
        }
        saveState()
    }

    private fun togglePlay() { if (mp?.isPlaying == true) pause() else resume() }

    private fun seekTo(ms: Int) { val p = mp ?: return; if (prepared) { p.seekTo(ms.coerceIn(0, p.duration)); updateTime() } else resumeMs = ms }

    private fun seekBy(d: Int) { val p = mp ?: return; if (prepared) seekTo(p.currentPosition + d) }

    private fun updatePlayBtn() {
        if (::playBtn.isInitialized) playBtn.text = if (mp?.isPlaying == true) "❚❚" else "▶"
        syncSession()
        updateMini()
        if (state == PState.PIP || Store.pipAuto) updatePip()
        if (mp?.isPlaying != true) uiMain.removeCallbacks(hideCtl)
    }

    private fun updateTime() {
        val p = mp ?: return
        if (!prepared) return
        val pos = p.currentPosition
        if (!dragging) { seek.progress = pos; posV.text = Text.duration(pos / 1000L).ifEmpty { "0:00" } }
        source?.meta?.let { m -> seek.secondaryProgress = (m.fraction * seek.max).toInt() }
        if (source?.meta?.complete == false) status(cacheText())
    }

    private fun screenOn(on: Boolean) {
        val window = ui?.window ?: return
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun switchMode(m: PlayMode) {
        if (localUri != null) return
        Store.setMode(videoId, m) // remembered for this video
        itemMode = m
        if (queueMode) Store.setQueue(Store.queue().let { q -> q.setMode(q.index, m) })
        if (m == mode && mp != null) { showMode(); return }
        restartAt(m, remember = true)
    }

    /** Same position, other source (Watch <-> Listen, other quality, fallback). */
    private fun restartAt(m: PlayMode, remember: Boolean) {
        mp?.let { if (prepared) resumeMs = it.currentPosition }
        playWhenReady = mp?.isPlaying == true || playWhenReady
        releaseAll()
        mode = m
        forced = m // this session plays [m]; [remember] already stored it for next time when the user chose it
        showMode()
        if (started || bg.hidden) start()
    }

    /**
     * Audio button: shown for dubbed videos (tracks known), and for cached entries made before 2.8, which may hold a
     * dub (their tracks are looked up when tapped). Label = the language playing.
     */
    private fun showAudio() {
        if (!::audioBtn.isInitialized) return
        val m = source?.meta
        val legacy = m != null && !m.langKnown
        audioBtn.visibility = if (localUri == null && (tracks.size > 1 || legacy)) View.VISIBLE else View.GONE
        val playing = if (legacy) null else m?.lang?.ifEmpty { null } ?: audioLang ?: tracks.firstOrNull { it.original }?.lang
        val t = tracks.firstOrNull { it.lang == playing }
        audioBtn.text = tr("Audio", "Ses") + (t?.let { " · " + java.util.Locale.forLanguageTag(it.lang).getDisplayLanguage(PlainLang.locale).ifBlank { it.lang } } ?: "")
    }

    private fun audioMenu() {
        if (tracks.size < 2) {
            // a replay from the cache: ask YouTube which languages exist
            status(tr("Loading audio languages…", "Ses dilleri yükleniyor…"))
            val id = videoId
            io.execute {
                val t = try { AudioTracks.of((info ?: InnerTube.info(id, Net.network(this, Store.wifiOnly), order = Clients.PLAY_ORDER).also { info = it }).formats) } catch (e: Exception) { null }
                main.post {
                    if (isDestroyed || id != videoId) return@post
                    status(cacheText())
                    when {
                        t == null -> toast(tr("Couldn't load the audio languages", "Ses dilleri yüklenemedi"))
                        t.size < 2 -> { toast(tr("This video has a single audio track", "Bu videonun tek ses parçası var")); showAudio() }
                        else -> { tracks = t; showAudio(); audioMenu() }
                    }
                }
            }
            return
        }
        val m = source?.meta
        val playing = if (m != null && !m.langKnown) null else m?.lang?.ifEmpty { null } ?: audioLang ?: tracks.firstOrNull { it.original }?.lang
        val a = ui as? android.app.Activity ?: return
        val sheet = Sheet(a, C.ACCENT).header(tr("Audio language", "Ses dili"))
        tracks.forEach { t ->
            sheet.choice(t.label(PlainLang.locale), t.lang == playing) {
                // an entry cached before 2.8 (maybe a dub) is left by picking any language, the original included:
                // an explicit language never matches those entries, so the pick is stored as that language
                val leaveLegacy = m != null && !m.langKnown
                val want = if (t.original && !leaveLegacy) null else t.lang
                if (t.lang != playing || want != audioLang || leaveLegacy) {
                    audioLang = want
                    Store.setAudioLang(videoId, want)
                    restartAt(mode, remember = true)
                }
            }
        }
        sheet.show()
    }

    private fun qualityMenu() {
        val cached = Store.cache.list(CacheKind.VIDEO).filter { it.videoId == videoId && it.langMatches(audioLang) }.associateBy { it.quality }
        val hs = (choices.map { it.height } + cached.keys).distinct().sortedDescending()
        if (hs.isEmpty()) { toast(tr("No other quality known yet", "Henüz başka kalite bilinmiyor")); return }
        val a = ui as? android.app.Activity ?: return
        val sheet = Sheet(a, C.ACCENT).header(tr("Quality", "Kalite"))
        hs.forEach { h ->
            val c = cached[h]
            val sub = if (c?.complete == true) tr("Cached", "Önbellekte") else if (c != null) tr("${(c.fraction * 100).toInt()}% cached", "%${(c.fraction * 100).toInt()} önbellekte") else null
            sheet.choice("${h}p", h == height, sub) {
                if (h != height) {
                    height = h
                    Store.watchP = h
                    restartAt(PlayMode.WATCH, remember = true)
                }
            }
        }
        sheet.show()
    }

    private fun save() {
        pubFile?.let { f -> ui?.message(tr("Save", "Kaydet"), tr("Already saved: ${f.where}", "Zaten kayıtlı: ${f.where}")); return }
        if (localUri != null) { toast(tr("This is a saved file", "Bu kayıtlı bir dosya")); return }
        val m = source?.meta ?: run { toast(tr("Nothing cached yet", "Henüz önbellekte bir şey yok")); return }
        if (m.complete) {
            // the player still reads the private copy: it is published and moved when the player closes
            saveOnClose = m
            ui?.message(tr("Save", "Kaydet"), tr("Fully cached: it will be saved to ${Naming.relativePath(if (m.kind == CacheKind.MUSIC) Kind.AUDIO else Kind.VIDEO, Naming.channel(m.channel), m.album)} when you close the player.", "Tamamen önbellekte: oynatıcıyı kapattığınızda kaydedilecek. Konum: ${Naming.relativePath(if (m.kind == CacheKind.MUSIC) Kind.AUDIO else Kind.VIDEO, Naming.channel(m.channel), m.album)}"))
            return
        }
        status(tr("Saving…", "Kaydediliyor…"))
        io.execute {
            source?.persist()
            val r = try { CachePublisher.save(this, m) } catch (e: Exception) { CachePublisher.Result(false, tr("Couldn't save: ${e.message}", "Kaydedilemedi: ${e.message}")) }
            main.post {
                if (isDestroyed) return@post
                ui?.message(tr("Save", "Kaydet"), r.message)
                status(cacheText())
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // play queue

    private fun setEntry(e: QEntry) {
        videoId = e.id; title = e.title; channel = e.channel; channelId = e.channelId
        localUri = e.localUri?.let { Uri.parse(it) }; localAudio = e.localAudio
        forced = e.mode; albumHint = e.album; musicLink = false
    }

    private fun showEntryControls() {
        val q = Store.queue()
        prevBtn.visibility = if (queueMode) View.VISIBLE else View.GONE
        nextBtn.visibility = if (queueMode) View.VISIBLE else View.GONE
        prevBtn.alpha = if (q.hasPrev) 1f else 0.4f
        nextBtn.alpha = if (q.hasNext) 1f else 0.4f
        queueBtn.text = tr("Queue (${q.items.size})", "Sıra (${q.items.size})")
        val local = localUri != null
        watchChip.visibility = if (local) View.GONE else View.VISIBLE
        listenChip.visibility = if (local) View.GONE else View.VISIBLE
        saveBtn.visibility = if (local) View.GONE else View.VISIBLE
        dlBtn.visibility = if (local) View.GONE else View.VISIBLE
        artChannel.text = channel
        channelV.text = channel
        channelV.visibility = if (channel.isEmpty()) View.GONE else View.VISIBLE
        titleV.text = title.ifEmpty { tr("Player", "Oynatıcı") }
        val next = if (queueMode && q.hasNext) q.items.getOrNull(q.index + 1) else null
        upNextV.text = next?.let { tr("Up next: ", "Sıradaki: ") + it.title.ifEmpty { it.id } + "  ⏭" }.orEmpty()
        upNextV.visibility = if (next == null) View.GONE else View.VISIBLE
        updateMini()
        membersV.visibility = if (Store.isMembers(videoId)) View.VISIBLE else View.GONE
        syncMeta()
    }

    /** Next (+1) / previous (-1) queue entry, keeping each entry's own Watch/Listen choice. */
    private fun step(d: Int) {
        if (!queueMode) return
        val q0 = Store.queue()
        val q = if (d > 0) q0.next() else q0.prev()
        if (q.index == q0.index) return
        Store.setQueue(q)
        playEntry(q.current ?: return, autoplay = true)
    }

    private fun playEntry(e: QEntry, autoplay: Boolean) {
        releaseAll()
        setEntry(e)
        info = null; choices = emptyList(); tracks = emptyList(); audioLang = Store.audioLang(videoId); triedOpus = false; watchFailed = false; resumeMs = 0; pubFile = null; listenItag = 0
        mode = forced ?: Store.mode(videoId) ?: PlayMode.WATCH
        if (localUri != null) mode = if (localAudio) PlayMode.LISTEN else PlayMode.WATCH
        itemMode = mode
        playWhenReady = autoplay
        showMode(); showEntryControls()
        if (started || bg.hidden) start()
    }

    private fun queueSheet() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8)) }
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        val dlg = android.app.AlertDialog.Builder(ui ?: return).setTitle(tr("Play queue", "Çalma sırası")).setView(scroll).setPositiveButton(tr("Close", "Kapat"), null).create()
        fun render() {
            col.removeAllViews()
            val q = Store.queue()
            if (q.items.isEmpty()) col.addView(text(tr("Empty. Add videos with press & hold → Add to play queue.", "Boş. Basılı tutup → Çalma sırasına ekle ile video ekleyin."), 13f, C.LABEL, lines = 3))
            for ((i, e) in q.items.withIndex()) {
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
                val t = text((if (i == q.index) "▶ " else "") + e.title.ifEmpty { e.id } + (e.mode?.let { if (it == PlayMode.LISTEN) "  ♪" else "" } ?: ""),
                    14f, if (i == q.index) C.ACCENT else C.TEXT, lines = 2)
                t.setOnClickListener {
                    val nq = Store.queue().jump(i); Store.setQueue(nq)
                    if (!queueMode) { queueMode = true }
                    playEntry(nq.current!!, autoplay = true); dlg.dismiss()
                }
                row.addView(t, lpL(0, WRAP, 1f))
                for ((g, act) in listOf("▲" to { Store.setQueue(Store.queue().move(i, -1)) }, "▼" to { Store.setQueue(Store.queue().move(i, 1)) },
                    "✕" to { Store.setQueue(Store.queue().remove(i)) })) {
                    row.addView(text(g, 16f, C.DIM).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { act(); render(); showEntryControls() } })
                }
                col.addView(row, lpL(MATCH, WRAP))
            }
        }
        render()
        dlg.show()
    }

    /** "Channel": open the channel page in the Search tab (looked up from the video when unknown). */
    private fun visitChannel() {
        val known = channelId ?: info?.channelId
        if (known != null) { openChannel(known); return }
        if (videoId.isEmpty()) return
        toast(tr("Finding the channel…", "Kanal bulunuyor…"))
        io.execute {
            val id = try { InnerTube.info(videoId, Net.network(this, Store.wifiOnly), order = Clients.PLAY_ORDER).also { info = it }.channelId } catch (_: Exception) { null }
            main.post { if (!isDestroyed) { if (id != null) openChannel(id) else toast(tr("Couldn't find the channel", "Kanal bulunamadı")) } }
        }
    }

    private fun openChannel(id: String) { minimize(); ui?.openChannel(id, channel) }

    private fun openDownload() { minimize(); ui?.openVideo(videoId) }

    // ---- surface ----

    override fun surfaceCreated(h: SurfaceHolder) { holder = h; if (mode == PlayMode.WATCH) mp?.setDisplay(h) }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { holder = null; try { mp?.setDisplay(null) } catch (_: Exception) {} }

    // last, so every property above is initialised before the views are built
    init {
        current = this
        build()
        applyState()
    }
}

/** Keeps the video's aspect ratio inside the available space. */
class AspectFrame(ctx: Context) : FrameLayout(ctx) {
    private var ratio = 16f / 9f

    fun setAspect(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val r = w.toFloat() / h
        if (r != ratio) { ratio = r; requestLayout() }
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        var cw = w
        var ch = (w / ratio).toInt()
        if (h > 0 && ch > h) { ch = h; cw = (h * ratio).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }
}
