package app.plain.download

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
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

/**
 * In-app player (MediaPlayer, no libraries). Streams go through [StreamProxy] so what is watched is cached at the
 * same time: Watch = one HLS quality (video TS segments + AAC audio segments) into the Video cache, Listen = the
 * audio stream only (itag 140 M4A, else 251 Opus) into the Music cache. Saved files play straight from MediaStore.
 * Everything stops and is released when the player is left or the app goes to the background.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback {
    companion object {
        /** [album]: the playlist it was started from (album folder hint for audio). */
        fun stream(ctx: Context, id: String, title: String, channel: String, mode: PlayMode? = null, musicLink: Boolean = false, album: String? = null) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("id", id).putExtra("title", title)
                .putExtra("channel", channel).putExtra("mode", mode?.name).putExtra("musicLink", musicLink).putExtra("album", album))
        }

        /** Play the saved play queue from its current entry (next/previous, auto-advance). */
        fun queue(ctx: Context) { ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("queue", true)) }

        fun local(ctx: Context, j: Job) {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("id", j.videoId).putExtra("title", j.title)
                .putExtra("channel", j.channel).putExtra("uri", j.uri).putExtra("audio", j.kind == Kind.AUDIO))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val calls = Calls()
    private lateinit var thumbs: ThumbLoader

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
    private var listenItag = 0
    private var triedOpus = false
    private var watchFailed = false
    private var resumeMs = 0
    private var playWhenReady = true
    private var started = false
    private var holder: SurfaceHolder? = null
    private var fullscreen = false
    private var saveOnClose: CacheMeta? = null // Save pressed while a private cache is being served
    private var pubMeta: CacheMeta? = null // playing a published cached file
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
    private lateinit var statusV: TextView
    private lateinit var seek: SeekBar
    private lateinit var posV: TextView
    private lateinit var durV: TextView
    private lateinit var playBtn: TextView
    private lateinit var watchChip: Chip
    private lateinit var listenChip: Chip
    private lateinit var qualityBtn: TextView
    private lateinit var saveBtn: TextView
    private lateinit var dlBtn: TextView

    private val tick = object : Runnable {
        override fun run() {
            updateTime()
            if (started && mp?.isPlaying == true) main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        thumbs = ThumbLoader(this, calls)
        queueMode = intent.getBooleanExtra("queue", false)
        val e = if (queueMode) Store.queue().current else null
        if (queueMode && e == null) { toast("The play queue is empty"); finish(); return }
        if (e != null) setEntry(e) else {
            videoId = intent.getStringExtra("id").orEmpty()
            title = intent.getStringExtra("title").orEmpty()
            channel = intent.getStringExtra("channel").orEmpty()
            localUri = intent.getStringExtra("uri")?.let { Uri.parse(it) }
            localAudio = intent.getBooleanExtra("audio", false)
            forced = intent.getStringExtra("mode")?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() }
            musicLink = intent.getBooleanExtra("musicLink", false)
            albumHint = intent.getStringExtra("album")
            channelId = intent.getStringExtra("channelId")
        }
        height = Store.watchP
        mode = forced ?: Store.mode(videoId) ?: PlayMode.WATCH
        if (localUri != null) mode = if (localAudio) PlayMode.LISTEN else PlayMode.WATCH
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR // rotates with the sensor only while the player is open
        setContentView(build())
        showMode()
        showEntryControls()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (isFinishing) return
        if (mp == null) start()
    }

    override fun onStop() {
        started = false
        mp?.let { if (prepared) resumeMs = it.currentPosition }
        playWhenReady = false // come back paused at the same place
        releaseAll()
        super.onStop()
    }

    override fun onDestroy() {
        releaseAll()
        io.shutdownNow()
        thumbs.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (fullscreen) { setFullscreen(false); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // ---------------------------------------------------------------------------------------------------------
    // layout

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins
        }
        topBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(16), dp(4)) }
        topBar.addView(icon(R.drawable.ic_back, desc = "Close") { finish() })
        titleV = text(title.ifEmpty { "Player" }, 15f, C.TEXT, bold = true, lines = 2)
        topBar.addView(titleV, lpL(0, WRAP, 1f))
        root.addView(topBar, lpL(MATCH, WRAP))

        stage = FrameLayout(this).apply { setOnClickListener { toggleControls() } }
        videoFrame = AspectFrame(this)
        stage.addView(videoFrame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        art = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0) }
        artImg = RoundImage(this)
        art.addView(artImg, lpL(dp(240), dp(135)))
        art.addView(text("♪", 28f, C.ACCENT).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, 0) }, lpL(MATCH, WRAP))
        artChannel = text(channel, 14f, C.DIM).apply { gravity = Gravity.CENTER }
        art.addView(artChannel, lpL(MATCH, WRAP))
        stage.addView(art, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        root.addView(stage, lpL(MATCH, 0, 1f))

        controls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(4), dp(12), dp(12)) }
        statusV = text("", 12f, C.LABEL, lines = 3).apply { setPadding(dp(4), 0, dp(4), dp(4)) }
        controls.addView(statusV, lpL(MATCH, WRAP))
        val seekRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        posV = text("0:00", 12f, C.DIM)
        durV = text("0:00", 12f, C.DIM)
        seek = SeekBar(this).apply {
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
        val btnRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, dp(6)) }
        prevBtn = pill("⏮") { step(-1) }
        btnRow.addView(prevBtn)
        btnRow.addView(View(this), lpL(dp(8), 1))
        btnRow.addView(pill("−10 s") { seekBy(-10_000) })
        btnRow.addView(View(this), lpL(dp(12), 1))
        playBtn = pill("▶", C.ACCENT, C.ACCENT) { togglePlay() }.apply { minWidth = dp(72) }
        btnRow.addView(playBtn)
        btnRow.addView(View(this), lpL(dp(12), 1))
        btnRow.addView(pill("+10 s") { seekBy(10_000) })
        btnRow.addView(View(this), lpL(dp(8), 1))
        nextBtn = pill("⏭") { step(1) }
        btnRow.addView(nextBtn)
        controls.addView(btnRow, lpL(MATCH, WRAP))
        val chips = FlowLayout(this)
        watchChip = Chip(this, "Watch") { switchMode(PlayMode.WATCH) }
        listenChip = Chip(this, "Listen") { switchMode(PlayMode.LISTEN) }
        qualityBtn = pill("Quality") { qualityMenu() }
        saveBtn = pill("Save") { save() }
        dlBtn = pill("Download…") { openDownload() }
        chips.addView(watchChip); chips.addView(listenChip); chips.addView(qualityBtn); chips.addView(saveBtn); chips.addView(dlBtn)
        chips.addView(pill("Full screen") { setFullscreen(!fullscreen) })
        chips.addView(pill("Channel") { visitChannel() })
        queueBtn = pill("Queue") { queueSheet() }
        chips.addView(queueBtn)
        controls.addView(chips, lpL(MATCH, WRAP))
        root.addView(controls, lpL(MATCH, WRAP))
        return root
    }

    private fun showMode() {
        val watch = mode == PlayMode.WATCH
        watchChip.select(watch); listenChip.select(!watch)
        qualityBtn.visibility = if (watch && localUri == null) View.VISIBLE else View.GONE
        qualityBtn.text = "Quality ${height}p"
        art.visibility = if (watch) View.GONE else View.VISIBLE
        videoFrame.visibility = if (watch) View.VISIBLE else View.GONE
        if (watch && surfaceView == null) {
            surfaceView = SurfaceView(this).also { sv -> sv.holder.addCallback(this); videoFrame.addView(sv, FrameLayout.LayoutParams(MATCH, MATCH)) }
        } else if (!watch && surfaceView != null) {
            videoFrame.removeAllViews(); surfaceView = null; holder = null // Listen: no surface at all
        }
        if (!watch && videoId.isNotEmpty()) thumbs.load(artImg, videoId, dp(240))
        titleV.text = title.ifEmpty { "Player" }
    }

    private fun toggleControls() {
        val show = controls.visibility != View.VISIBLE
        controls.visibility = if (show) View.VISIBLE else View.GONE
        topBar.visibility = if (show && !fullscreen) View.VISIBLE else View.GONE
    }

    private fun setFullscreen(on: Boolean) {
        fullscreen = on
        requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR
        window.insetsController?.let { c ->
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
        val my = ++seq
        prepared = false
        pubMeta = null
        localUri?.let { preparePlayer(it); return }
        status("Loading…")
        val g = calls.gen
        val m0 = mode
        io.execute {
            val r = try { Result.success(resolve(g, m0)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (my != seq || isDestroyed) { (r.getOrNull() as? CachedSource)?.close(); return@post }
                r.fold({ src ->
                    if (src is CacheMeta) { pubMeta = src; preparePlayer(Uri.parse(src.publicUri)) } // offline, public file
                    else startSource(src as CachedSource)
                }, { e -> status(errText(e), true) })
            }
        }
    }

    private fun errText(e: Throwable): String = when (e) {
        is NoWifi -> "Not on Wi-Fi (Wi-Fi only is on). Cached videos still play."
        is java.net.UnknownHostException -> "No internet connection"
        is YtError -> e.message ?: "Can't play"
        is Cancelled -> ""
        else -> "Couldn't start: ${e.message ?: e.javaClass.simpleName}"
    }

    /** Background thread: pick the mode, prefer complete caches (no network), else ask YouTube. */
    private fun resolve(g: Int, wanted: PlayMode): Any {
        // published entries whose public file was deleted elsewhere leave the cache index
        fun live(k: CacheKind) = Store.cache.list(k).filter { it.videoId == videoId }.filter { m ->
            if (m.published && !CachePublisher.exists(this, m)) { Store.cache.forget(k, m.key); false } else true
        }
        val music = live(CacheKind.MUSIC)
        val video = live(CacheKind.VIDEO)
        var m: PlayMode? = forced ?: Store.mode(videoId)
        if (m == null) {
            val mc = music.any { it.complete }; val vc = video.any { it.complete }
            if (mc && !vc) m = PlayMode.LISTEN else if (vc && !mc) m = PlayMode.WATCH
        }
        if (m == null && wanted != PlayMode.WATCH) m = wanted
        // offline replays
        if (m == PlayMode.LISTEN) music.filter { it.complete && (!triedOpus || it.ext == "webm") }.maxByOrNull { it.lastUsed }?.let {
            setMode(m); touch(it); CachePublisher.publish(this, it); return if (it.published) it else musicSource(it, null)
        }
        if (m == PlayMode.WATCH) video.filter { it.complete }.let { l -> (l.firstOrNull { it.quality == height } ?: Hls.pickMeta(l, height)) }
            ?.let { setMode(m); touch(it); CachePublisher.publish(this, it); return if (it.published) it else hlsOffline(it) }

        calls.check(g)
        val net = Net.network(this, Store.wifiOnly)
        val inf = info ?: InnerTube.info(videoId, net, order = Clients.PLAY_ORDER).also { info = it }
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
        return if (m == PlayMode.LISTEN) musicSource(null, inf) else hlsSource(g, net, inf)
    }

    private var album: String? = null

    private fun touch(m: CacheMeta) { m.lastUsed = System.currentTimeMillis(); Store.cache.save(m) }

    private fun setMode(m: PlayMode) { mode = m; forced = m; main.post { showMode() } } // decided once per session

    private fun musicSource(cached: CacheMeta?, inf: VideoInfo?): CachedSource {
        if (cached != null) { listenItag = cached.quality; return ProgressiveSource(this, cached, null, "", null) }
        inf ?: throw IOException("No stream info")
        val f = (if (triedOpus) inf.formats.firstOrNull { it.itag == 251 } else null) ?: Modes.listenFormat(inf.formats)
            ?: throw YtError("No audio stream offered", false)
        listenItag = f.itag
        val key = "${videoId}_${f.itag}"
        val meta = Store.cache.get(CacheKind.MUSIC, key)?.takeIf { it.total == f.contentLength || f.contentLength <= 0 }
            ?: CacheMeta(CacheKind.MUSIC, videoId, f.itag, title, channel, inf.durationSec, total = maxOf(0L, f.contentLength),
                ext = if (f.isOpus) "webm" else "m4a").also { Store.cache.remove(CacheKind.MUSIC, key) }
        meta.title = title; meta.channel = channel
        if (meta.album == null) meta.album = album
        val skip = HashSet<String>()
        var client = inf.client
        val refresh = {
            skip += client
            val i2 = InnerTube.info(videoId, Net.network(this, Store.wifiOnly), skip, Clients.PLAY_ORDER)
            client = i2.client
            val nf = i2.formats.firstOrNull { it.itag == f.itag } ?: throw YtError("Stream no longer offered", false)
            nf.url to (Clients.byKey(i2.client) ?: Clients.VISIONOS).userAgent
        }
        return ProgressiveSource(this, meta, f.url, (Clients.byKey(inf.client) ?: Clients.VISIONOS).userAgent, refresh)
    }

    private class Hl(val variant: Hls.Variant, val v: List<Hls.Seg>, val a: List<Hls.Seg>?, val vText: String, val aText: String?)

    private fun loadHls(g: Int, inf0: VideoInfo, want: Int): Pair<Hl, String> {
        var inf = inf0
        if (inf.hls == null) inf = InnerTube.info(videoId, Net.network(this, Store.wifiOnly), order = Clients.HLS_ORDER)
        val url = inf.hls ?: throw YtError("No video stream for this video (try Listen)", false)
        val ua = (Clients.byKey(inf.client) ?: Clients.VISIONOS).userAgent
        val master = Hls.parseMaster(String(WebApi.get(this, calls, g, url, ua), Charsets.UTF_8), url)
        choices = Hls.choices(master)
        val v = Hls.pick(choices, want) ?: throw YtError("No H.264 video stream", false)
        val vText = String(WebApi.get(this, calls, g, v.uri, ua), Charsets.UTF_8)
        val aUri = v.audio?.let { master.audio[it] }
        val aText = aUri?.let { String(WebApi.get(this, calls, g, it, ua), Charsets.UTF_8) }
        return Hl(v, Hls.parseMedia(vText, v.uri), aUri?.let { Hls.parseMedia(aText!!, it) }, vText, aText) to ua
    }

    private fun hlsSource(g: Int, net: android.net.Network?, inf: VideoInfo): CachedSource {
        val (h, ua) = loadHls(g, inf, height)
        height = h.variant.height
        main.post { qualityBtn.text = "Quality ${height}p" }
        val key = "${videoId}_$height"
        val old = Store.cache.get(CacheKind.VIDEO, key)
        val meta = old?.takeIf { it.total == h.v.size.toLong() && (h.a == null || h.a.size == h.v.size) }
            ?: CacheMeta(CacheKind.VIDEO, videoId, height, title, channel, inf.durationSec, total = h.v.size.toLong()).also { Store.cache.remove(CacheKind.VIDEO, key) }
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
        main.post { qualityBtn.text = "Quality ${height}p" }
        val dir = Store.cache.entryDir(CacheKind.VIDEO, m.key)
        val v = Hls.parseMedia(HlsSource.segsFile(dir, false).readText(), "https://x/")
        val a = HlsSource.segsFile(dir, true).takeIf { it.exists() }?.let { Hls.parseMedia(it.readText(), "https://x/") }
        return HlsSource(this, m, v, a, Clients.VISIONOS.userAgent, null)
    }

    private fun startSource(src: CachedSource) {
        source = src
        src.start()
        val p = try { StreamProxy(src).start() } catch (e: Exception) { status("Couldn't start the player: ${e.message}", true); src.close(); return }
        proxy = p
        titleV.text = title.ifEmpty { "Player" }
        preparePlayer(Uri.parse(p.url))
    }

    private fun preparePlayer(uri: Uri) {
        val p = MediaPlayer()
        mp = p
        prepared = false
        val my = seq
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(if (mode == PlayMode.LISTEN) AudioAttributes.CONTENT_TYPE_MUSIC else AudioAttributes.CONTENT_TYPE_MOVIE).build())
        p.setOnPreparedListener {
            if (my != seq) return@setOnPreparedListener
            prepared = true
            seek.max = it.duration.coerceAtLeast(0)
            durV.text = Text.duration(it.duration / 1000L).ifEmpty { "0:00" }
            if (resumeMs > 0) it.seekTo(resumeMs)
            if (playWhenReady) play() else { updateTime(); updatePlayBtn() }
            status(cacheText())
        }
        p.setOnCompletionListener {
            updatePlayBtn(); screenOn(false); status(cacheText())
            if (my == seq && queueMode && Store.queue().hasNext) main.post { step(1) } // auto-advance; no prefetch of the next item
        }
        p.setOnErrorListener { _, what, extra -> if (my == seq) onPlayError(what, extra); true }
        p.setOnVideoSizeChangedListener { _, w, h -> videoFrame.setAspect(w, h) }
        p.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> status("Buffering…")
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> status(cacheText())
            }
            false
        }
        try {
            p.setDataSource(this, uri)
            if (mode == PlayMode.WATCH) holder?.let { p.setDisplay(it) }
            p.prepareAsync()
        } catch (e: Exception) {
            status("Can't play this: ${e.message}", true)
        }
    }

    private fun cacheText(): String {
        if (pubMeta != null) return "Cached: ${pubMeta?.relPath ?: ""} · plays offline"
        val m = source?.meta ?: return if (localUri != null) "Saved file" else ""
        val what = if (m.kind == CacheKind.MUSIC) "Music" else "Video"
        return if (m.complete) "Cached ($what) · plays offline" else "Caching while you watch · ${(m.fraction * 100).toInt()}% · ${Text.size(m.size)}"
    }

    private fun onPlayError(what: Int, extra: Int) {
        val err = source?.error
        when {
            err is NoWifi -> status(errText(err), true)
            localUri != null || pubMeta != null -> status("This file can't be played ($what/$extra)", true)
            mode == PlayMode.WATCH && !watchFailed -> {
                watchFailed = true
                toast("This phone couldn't play the video stream: playing the audio instead")
                restartAt(PlayMode.LISTEN, remember = false)
            }
            mode == PlayMode.LISTEN && !triedOpus && listenItag != 251 && info?.formats?.any { it.itag == 251 } == true -> {
                triedOpus = true
                restartAt(PlayMode.LISTEN, remember = false)
            }
            else -> status("Playback failed" + (err?.message?.let { ": $it" } ?: " ($what/$extra)"), true)
        }
    }

    private fun releaseAll() {
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
            // complete now: becomes a public file under Music|Movies/PlainDownload/Cached/ (LocalMusic sees it)
            if (m != null && m.complete && !m.published) CachePublisher.publish(app, m)
            if (sv != null) CachePublisher.save(app, sv)
        }, "player-close").start()
        saveOnClose = null
        updatePlayBtn()
    }

    // ---------------------------------------------------------------------------------------------------------
    // controls

    private fun play() {
        val p = mp ?: return
        if (!prepared) { playWhenReady = true; return }
        if (p.currentPosition >= p.duration - 300 && p.duration > 0) p.seekTo(0)
        p.start()
        playWhenReady = true
        screenOn(true)
        main.removeCallbacks(tick); main.post(tick)
        updatePlayBtn()
        status(cacheText())
    }

    private fun pause() {
        mp?.takeIf { prepared && it.isPlaying }?.pause()
        playWhenReady = false
        screenOn(false)
        main.removeCallbacks(tick)
        updateTime(); updatePlayBtn()
        status(cacheText())
    }

    private fun togglePlay() { if (mp?.isPlaying == true) pause() else if (mp == null) { playWhenReady = true; start() } else play() }

    private fun seekTo(ms: Int) { val p = mp ?: return; if (prepared) { p.seekTo(ms.coerceIn(0, p.duration)); updateTime() } else resumeMs = ms }

    private fun seekBy(d: Int) { val p = mp ?: return; if (prepared) seekTo(p.currentPosition + d) }

    private fun updatePlayBtn() { if (::playBtn.isInitialized) playBtn.text = if (mp?.isPlaying == true) "❚❚" else "▶" }

    private fun updateTime() {
        val p = mp ?: return
        if (!prepared) return
        val pos = p.currentPosition
        if (!dragging) { seek.progress = pos; posV.text = Text.duration(pos / 1000L).ifEmpty { "0:00" } }
        source?.meta?.let { m -> seek.secondaryProgress = (m.fraction * seek.max).toInt() }
        if (source?.meta?.complete == false) status(cacheText())
    }

    private fun screenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun switchMode(m: PlayMode) {
        if (localUri != null) return
        Store.setMode(videoId, m) // remembered for this video
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
        if (started) start()
    }

    private fun qualityMenu() {
        val cached = Store.cache.list(CacheKind.VIDEO).filter { it.videoId == videoId }.associateBy { it.quality }
        val hs = (choices.map { it.height } + cached.keys).distinct().sortedDescending()
        if (hs.isEmpty()) { toast("No other quality known yet"); return }
        menu("Quality", hs.map { h ->
            val c = cached[h]
            val label = "${h}p" + (if (h == height) "  ✓" else "") +
                (if (c?.complete == true) "  · cached" else if (c != null) "  · ${(c.fraction * 100).toInt()}% cached" else "")
            label to {
                if (h != height) {
                    height = h
                    Store.watchP = h
                    restartAt(PlayMode.WATCH, remember = true)
                }
            }
        })
    }

    private fun save() {
        val pm = pubMeta
        val m = pm ?: source?.meta ?: run { toast("Nothing cached yet"); return }
        if (pm == null && m.complete && !m.published) {
            // the player still reads the private copy: it is published and moved when the player closes
            saveOnClose = m
            message("Save", "Fully cached: it will be saved to ${Naming.relativePath(if (m.kind == CacheKind.MUSIC) Kind.AUDIO else Kind.VIDEO, Naming.channel(m.channel), m.album)} when you close the player.")
            return
        }
        status("Saving…")
        io.execute {
            source?.persist()
            val r = try { CachePublisher.save(this, m) } catch (e: Exception) { CachePublisher.Result(false, "Couldn't save: ${e.message}") }
            main.post {
                if (isDestroyed) return@post
                message("Save", r.message)
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
        queueBtn.text = "Queue (${q.items.size})"
        val local = localUri != null
        watchChip.visibility = if (local) View.GONE else View.VISIBLE
        listenChip.visibility = if (local) View.GONE else View.VISIBLE
        saveBtn.visibility = if (local) View.GONE else View.VISIBLE
        dlBtn.visibility = if (local) View.GONE else View.VISIBLE
        artChannel.text = channel
        titleV.text = title.ifEmpty { "Player" }
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
        info = null; choices = emptyList(); triedOpus = false; watchFailed = false; resumeMs = 0; pubMeta = null; listenItag = 0
        mode = forced ?: Store.mode(videoId) ?: PlayMode.WATCH
        if (localUri != null) mode = if (localAudio) PlayMode.LISTEN else PlayMode.WATCH
        playWhenReady = autoplay
        showMode(); showEntryControls()
        if (started) start()
    }

    private fun queueSheet() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(8), dp(8)) }
        val scroll = android.widget.ScrollView(this).apply { addView(col) }
        val dlg = android.app.AlertDialog.Builder(this).setTitle("Play queue").setView(scroll).setPositiveButton("Close", null).create()
        fun render() {
            col.removeAllViews()
            val q = Store.queue()
            if (q.items.isEmpty()) col.addView(text("Empty. Add videos with press & hold → Add to play queue.", 13f, C.LABEL, lines = 3))
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
        toast("Finding the channel…")
        io.execute {
            val id = try { InnerTube.info(videoId, Net.network(this, Store.wifiOnly), order = Clients.PLAY_ORDER).also { info = it }.channelId } catch (_: Exception) { null }
            main.post { if (!isDestroyed) { if (id != null) openChannel(id) else toast("Couldn't find the channel") } }
        }
    }

    private fun openChannel(id: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra("open_channel", id).putExtra("channel_name", channel)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    private fun openDownload() {
        startActivity(Intent(this, MainActivity::class.java).putExtra("open_video", videoId)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    // ---- surface ----

    override fun surfaceCreated(h: SurfaceHolder) { holder = h; if (mode == PlayMode.WATCH) mp?.setDisplay(h) }
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { holder = null; try { mp?.setDisplay(null) } catch (_: Exception) {} }
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
