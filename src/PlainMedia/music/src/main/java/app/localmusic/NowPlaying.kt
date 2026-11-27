package app.localmusic

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.media.AudioManager
import android.view.GestureDetector
import android.widget.SeekBar
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.tr
import kotlin.math.min

/**
 * Full-screen now-playing: big art, seek slider with elapsed/remaining, transport, shuffle/repeat, ±10 s, speed, volume,
 * queue, sleep. Double-tap the left/right half of the art (or picture) to seek ∓10 s; fling down to close.
 */
@SuppressLint("ViewConstructor")
class NowPlayingView(private val act: MainActivity) : FrameLayout(act) {
    private val art = ArtView(act, act.dpf(10))
    private val title = act.text("", 22f, C.TEXT, bold = true)
    private val artist = act.text("", 16f, C.DIM)
    private val slider = SeekSlider(act)
    private val playBtn: ImageView
    private val shuffleBtn: ImageView
    private val repeatBtn: ImageView
    private val sleepBtn: ImageView
    private val sleepText = act.text("", 12f, C.ACCENT)
    private val from = act.text("", 12f, C.DIM).apply { gravity = Gravity.CENTER }
    private var shownId = Long.MIN_VALUE
    private val col: LinearLayout
    private val artBox: FrameLayout
    private val bottom: LinearLayout
    private val chrome = ArrayList<View>() // everything but the picture (hidden in fullscreen and PiP)
    private val speedBtn: TextView
    private val pipBtn: ImageView
    private val seekHint: TextView
    private val volIcon: ImageView
    private val volBar: SeekBar
    private val audio = act.getSystemService(AudioManager::class.java)
    private val vol = VolumeModel(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    var inPip = false
        private set

    // Video: created only once a video item is current; the picture only while the toggle is on and we are visible.
    private var videoBtn: TextView? = null
    private var picture: PictureView? = null
    var fullscreen = false
        private set

    init {
        isClickable = true // swallow touches meant for the list below
        setBackgroundColor(C.BG) // OLED: no lit gradient behind the art
        col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(4), act.dp(20), act.dp(12)) }
        addView(col, LayoutParams(MATCH, MATCH))

        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(act.icon(R.drawable.mus_ic_down, desc = tr("Close", "Kapat")) { act.closeNowPlaying() })
        top.addView(from, lpL(0, WRAP, 1f))
        pipBtn = act.icon(R.drawable.mus_ic_pip, desc = tr("Picture-in-picture", "Pencere içinde pencere")) { act.enterPip() }.apply { visibility = View.GONE }
        top.addView(pipBtn)
        top.addView(act.icon(R.drawable.mus_ic_more, desc = tr("More", "Diğer")) { Player.current?.let { act.songMenu(it) } })
        col.addView(top, lpL(MATCH, WRAP))
        chrome += top

        // square art, as large as fits
        artBox = object : FrameLayout(act) {
            override fun onMeasure(w: Int, h: Int) {
                val size = min(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
                super.onMeasure(w, h)
                art.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
            }
        }
        artBox.addView(art, LayoutParams(WRAP, WRAP, Gravity.CENTER))
        seekHint = act.text("", 18f, C.TEXT, bold = true).apply {
            background = oval(0x99000000.toInt()); gravity = Gravity.CENTER; visibility = View.GONE
            setPadding(act.dp(18), act.dp(18), act.dp(18), act.dp(18))
        }
        artBox.addView(seekHint, LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.START).apply { setMargins(act.dp(24), 0, act.dp(24), 0) })
        val gd = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (picture != null) setFullscreen(!fullscreen); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                val left = e.x < artBox.width / 2f
                Player.seekBy(if (left) -SeekStep.MS else SeekStep.MS)
                flashSeek(left)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (vy > 1500 && Math.abs(vy) > Math.abs(vx) * 1.5f && !fullscreen && !inPip) { act.closeNowPlaying(); return true }
                return false
            }
        })
        artBox.setOnTouchListener { _, e -> if (inPip) false else gd.onTouchEvent(e) }
        col.addView(artBox, lpL(MATCH, 0, 1f).apply { topMargin = act.dp(12); bottomMargin = act.dp(20) })

        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title.setSingleLine()
        title.isSelected = true
        title.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        title.marqueeRepeatLimit = -1
        // the song's names are links: title / "· Album" → album page, artist → artist page
        title.setOnClickListener { Player.current?.let { act.goToAlbum(it) } }
        artist.setOnClickListener { Player.current?.let { act.goToArtist(it) } }
        from.setOnClickListener { Player.current?.let { act.goToAlbum(it) } }
        texts.addView(title); texts.addView(artist, lpL(WRAP, WRAP))
        titleRow.addView(texts, lpL(0, WRAP, 1f))
        titleRow.addView(act.icon(R.drawable.mus_ic_add_list, 48, 11, desc = tr("Add to playlist", "Çalma listesine ekle")) { Player.current?.let { act.addToPlaylist(listOf(it)) } })
        col.addView(titleRow, lpL(MATCH, WRAP))
        chrome += titleRow

        slider.onSeek = { Player.seekTo(it) }
        col.addView(slider, lpL(MATCH, WRAP).apply { topMargin = act.dp(10); leftMargin = -act.dp(10); rightMargin = -act.dp(10) })
        chrome += slider

        val controls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shuffleBtn = act.icon(R.drawable.mus_ic_shuffle, 52, 13, desc = tr("Shuffle", "Karıştır")) { Player.toggleShuffle() }
        repeatBtn = act.icon(R.drawable.mus_ic_repeat, 52, 13, desc = tr("Repeat", "Tekrarla")) { Player.cycleRepeat() }
        playBtn = act.icon(R.drawable.mus_ic_play, 72, 20, 0xFF000000.toInt(), tr("Play/pause", "Oynat/duraklat")) { Player.toggle() }
        playBtn.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), oval(C.TEXT), null)
        playBtn.setOnLongClickListener { act.toggleStopAfterSong(); true } // hold: stop after this song
        fun spacer() = View(act).also { controls.addView(it, lpL(0, 1, 1f)) }
        controls.addView(shuffleBtn); spacer()
        controls.addView(act.icon(R.drawable.mus_ic_prev, 60, 14, desc = tr("Previous", "Önceki")) { Player.prev() }); spacer()
        controls.addView(playBtn); spacer()
        controls.addView(act.icon(R.drawable.mus_ic_next, 60, 14, desc = tr("Next", "Sonraki")) { Player.next() }); spacer()
        controls.addView(repeatBtn)
        col.addView(controls, lpL(MATCH, WRAP).apply { topMargin = act.dp(4) })
        chrome += controls

        // −10 s · speed · +10 s
        val steps = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        steps.addView(act.icon(R.drawable.mus_ic_back10, 48, 11, desc = tr("Back 10 s", "10 sn geri")) { Player.seekBy(-SeekStep.MS) })
        speedBtn = act.pill(Speeds.label(Player.speed), C.BG) { act.speedMenu() }.apply {
            contentDescription = tr("Playback speed", "Oynatma hızı")
            setPadding(act.dp(14), act.dp(5), act.dp(14), act.dp(5))
            minWidth = act.dp(64)
            setOnLongClickListener { if (Player.speed != 1f) { Player.setSpeed(1f); act.toast(tr("Normal speed", "Normal hız")) }; true } // hold: back to 1×
        }
        steps.addView(speedBtn, lpL(WRAP, WRAP).apply { leftMargin = act.dp(18); rightMargin = act.dp(18) })
        steps.addView(act.icon(R.drawable.mus_ic_fwd10, 48, 11, desc = tr("Forward 10 s", "10 sn ileri")) { Player.seekBy(SeekStep.MS) })
        col.addView(steps, lpL(MATCH, WRAP))
        chrome += steps

        // volume: the media stream, like the hardware keys (no system panel); the speaker mutes / restores
        val volRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        volIcon = act.icon(R.drawable.mus_ic_volume, 40, 10, desc = tr("Mute", "Sesi kapat")) { setVolume(vol.toggleMute()); syncVolumeBar() }
        volBar = SeekBar(act).apply {
            max = vol.max
            progressTintList = ColorStateList.valueOf(C.TEXT)
            thumbTintList = ColorStateList.valueOf(C.TEXT)
            progressBackgroundTintList = ColorStateList.valueOf(C.LINE)
            contentDescription = tr("Volume", "Ses düzeyi")
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) setVolume(p) }
                override fun onStartTrackingTouch(sb: SeekBar) { sb.parent?.requestDisallowInterceptTouchEvent(true) }
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        volRow.addView(volIcon); volRow.addView(volBar, lpL(0, WRAP, 1f))
        col.addView(volRow, lpL(MATCH, WRAP))
        chrome += volRow

        bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sleepBtn = act.icon(R.drawable.mus_ic_timer, 48, 12, desc = tr("Sleep timer", "Uyku zamanlayıcısı")) { act.sleepMenu() }
        bottom.addView(sleepBtn)
        bottom.addView(sleepText, lpL(0, WRAP, 1f))
        bottom.addView(act.icon(R.drawable.mus_ic_queue, 48, 12, desc = tr("Queue", "Sıra")) { act.openQueue() })
        col.addView(bottom, lpL(MATCH, WRAP).apply { topMargin = act.dp(8) })
        chrome += bottom

        // swipe down to close
        var downY = 0f
        setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.rawY
                MotionEvent.ACTION_UP -> if (e.rawY - downY > act.dp(120)) act.closeNowPlaying()
            }
            true
        }
    }

    fun update() {
        val s = Player.current ?: return
        if (s.id != shownId) {
            shownId = s.id
            title.text = s.title
            artist.text = s.shownArtist
            art.bind(s.id, 720)
        }
        if (s.isVideo || videoBtn != null) syncVideo(s) // audio-only libraries never get here
        from.text = "${Player.queue.pos + 1} / ${Player.queue.size}" + if (s.album.isNotBlank()) "  ·  ${s.album}" else ""
        playBtn.setImageResource(if (Player.playing) R.drawable.mus_ic_pause else R.drawable.mus_ic_play)
        shuffleBtn.tint(if (Player.queue.shuffle) C.ACCENT else C.TEXT)
        when (Player.queue.repeat) {
            Repeat.OFF -> { repeatBtn.setImageResource(R.drawable.mus_ic_repeat); repeatBtn.tint(C.TEXT) }
            Repeat.ALL -> { repeatBtn.setImageResource(R.drawable.mus_ic_repeat); repeatBtn.tint(C.ACCENT) }
            Repeat.ONE -> { repeatBtn.setImageResource(R.drawable.mus_ic_repeat_one); repeatBtn.tint(C.ACCENT) }
        }
        speedBtn.text = Speeds.label(Player.speed)
        speedBtn.setTextColor(if (Player.speed != 1f) C.ACCENT else C.TEXT)
        pipBtn.visibility = if (picture != null && act.pipSupported) View.VISIBLE else View.GONE
        updateSleep()
    }

    private val hideHint = Runnable { seekHint.visibility = View.GONE }

    private fun flashSeek(left: Boolean) {
        seekHint.text = if (left) tr("« 10 s", "« 10 sn") else tr("10 s »", "10 sn »")
        (seekHint.layoutParams as LayoutParams).gravity = Gravity.CENTER_VERTICAL or (if (left) Gravity.START else Gravity.END)
        seekHint.requestLayout()
        seekHint.visibility = View.VISIBLE
        seekHint.bringToFront()
        removeCallbacks(hideHint); postDelayed(hideHint, 650)
    }

    // --- volume (receiver only while this screen is shown and the app is in front) ---------------

    private var volReceiver: BroadcastReceiver? = null

    fun watchVolume(on: Boolean) {
        if (on && volReceiver == null) {
            val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { syncVolume() } }
            val f = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
            if (android.os.Build.VERSION.SDK_INT >= 33) act.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else act.registerReceiver(r, f)
            volReceiver = r
            syncVolume()
        } else if (!on) volReceiver?.let { runCatching { act.unregisterReceiver(it) }; volReceiver = null }
    }

    private fun syncVolume() {
        vol.sync(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        syncVolumeBar()
    }

    private fun syncVolumeBar() {
        if (volBar.progress != vol.level) volBar.progress = vol.level
        volIcon.setImageResource(if (vol.muted) R.drawable.mus_ic_volume_off else R.drawable.mus_ic_volume)
    }

    private fun setVolume(v: Int) {
        runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, vol.set(v), 0) } // no system volume panel
        volIcon.setImageResource(if (vol.muted) R.drawable.mus_ic_volume_off else R.drawable.mus_ic_volume)
    }

    // --- video ---------------------------------------------------------------------------------

    private fun videoWanted() = Store.prefs.getBoolean("video", true)

    private fun syncVideo(s: Song) {
        var btn = videoBtn
        if (s.isVideo && btn == null) {
            btn = act.text(tr("Video", "Video"), 14f, C.TEXT, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(act.dp(14), act.dp(6), act.dp(14), act.dp(6))
                contentDescription = tr("Show the video", "Videoyu göster")
                setOnClickListener { Store.prefs.edit().putBoolean("video", !videoWanted()).apply(); Player.current?.let { syncVideo(it) } }
            }
            videoBtn = btn
            bottom.addView(btn, bottom.childCount - 1, lpL(WRAP, WRAP).apply { rightMargin = act.dp(6) })
        }
        val on = videoWanted()
        btn?.apply {
            visibility = if (s.isVideo) View.VISIBLE else View.GONE
            setTextColor(if (on) C.ACCENT else C.DIM)
            background = ripple(act.outline(act.dpf(16), if (on) C.ACCENT else C.OUTLINE_BTN))
        }
        if (s.isVideo && on && act.started) {
            val pic = picture ?: PictureView(act).also {
                picture = it
                artBox.addView(it, 0, lpF(MATCH, MATCH))
                art.visibility = View.INVISIBLE
            }
            pic.setVideoSize(s.videoW, s.videoH)
        } else dropVideo()
        pipBtn.visibility = if (picture != null && act.pipSupported) View.VISIBLE else View.GONE
    }

    /** The picture is on screen (PiP and auto-enter need it). */
    val hasPicture get() = picture != null

    /** Where the picture is on screen (PiP's enter animation starts there). */
    fun pictureRect(): android.graphics.Rect? {
        val r = android.graphics.Rect()
        return picture?.takeIf { it.getGlobalVisibleRect(r) && !r.isEmpty }?.let { r }
    }

    /** In picture-in-picture only the picture is shown. */
    fun setPip(on: Boolean) {
        if (on == inPip) return
        inPip = on
        if (on || !fullscreen) layoutChrome(!on)
    }

    private fun layoutChrome(show: Boolean) {
        for (v in chrome) v.visibility = if (show) View.VISIBLE else View.GONE
        if (show) col.setPadding(act.dp(20), act.dp(4), act.dp(20), act.dp(12)) else col.setPadding(0, 0, 0, 0)
        (artBox.layoutParams as LinearLayout.LayoutParams).apply { topMargin = if (show) act.dp(12) else 0; bottomMargin = if (show) act.dp(20) else 0 }
        artBox.requestLayout()
    }

    /** Remove the picture: the surface goes away and the player keeps playing as audio (video decoder shut down). */
    fun dropVideo() {
        val pic = picture ?: return
        picture = null
        setFullscreen(false)
        Player.showVideo(null)
        artBox.removeView(pic)
        art.visibility = View.VISIBLE
    }

    /** Picture only, edge to edge, system bars hidden (swipe from the edge shows them). Tap or Back leaves it. */
    fun setFullscreen(on: Boolean) {
        if (on == fullscreen || (on && picture == null)) return
        fullscreen = on
        Hub.setHeaderShown(act, !on)
        if (!inPip) layoutChrome(!on)
        // a landscape video turns the screen to landscape (either way round, by the sensor) while fullscreen
        val s = Player.current
        val wide = s == null || s.videoW <= 0 || s.videoH <= 0 || s.videoW >= s.videoH
        act.requestedOrientation = if (on && wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        act.window.insetsController?.let { c ->
            if (on) { c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; c.hide(WindowInsets.Type.systemBars()) }
            else c.show(WindowInsets.Type.systemBars())
        }
    }

    private fun updateSleep() {
        val active = Player.sleepAt > 0 || Player.sleepEndOfSong
        sleepBtn.tint(if (active) C.ACCENT else C.TEXT)
        sleepText.text = when {
            Player.sleepAt > 0 -> tr("Pausing in ", "Duraklatmaya kalan: ") + Text.time(Player.sleepAt - SystemClock.elapsedRealtime())
            Player.sleepEndOfSong -> tr("Pausing after this song", "Bu şarkıdan sonra duraklar")
            else -> ""
        }
    }

    fun tick(pos: Long, dur: Long) {
        slider.set(pos, dur)
        if (Player.sleepAt > 0) updateSleep()
    }
}

/** The play queue in play order: tap to jump, drag ≡ to reorder, ✕ to remove. */
@SuppressLint("ViewConstructor")
class QueueView(private val act: MainActivity) : LinearLayout(act) {
    private val list = ListView(act)
    private lateinit var dragger: MainActivity.Dragger
    private val adapter = act.newRowAdapter { dragger }
    private val info: TextView = act.text("", 13f, C.DIM)

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(C.PANEL)
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(6), act.dp(6), act.dp(12), act.dp(6)) }
        top.addView(act.icon(R.drawable.mus_ic_down, desc = tr("Close", "Kapat")) { act.closeOverlay(this) })
        val t = LinearLayout(act).apply { orientation = VERTICAL }
        t.addView(act.text(tr("Queue", "Sıra"), 20f, C.TEXT, bold = true)); t.addView(info)
        top.addView(t, lpL(0, WRAP, 1f))
        top.addView(act.pill(tr("Clear upcoming", "Sıradakileri temizle"), C.CARD) { Player.clearUpcoming() })
        addView(top, lpL(MATCH, WRAP))
        list.divider = null
        list.selector = android.graphics.drawable.ColorDrawable(0)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> (adapter.rows[pos] as? SongRow)?.let { Player.jump(it.index) } }
        list.setOnItemLongClickListener { _, _, pos, _ -> (adapter.rows[pos] as? SongRow)?.let { act.songMenu(it.song) }; true }
        addView(list, lpL(MATCH, 0, 1f))
        dragger = act.Dragger(list, { adapter.rows as MutableList<Row> }, { adapter.notifyDataSetChanged() }) { from, to -> Player.moveInQueue(from, to) }
        refresh()
        list.setSelection((Player.queue.pos - 1).coerceAtLeast(0))
    }

    fun refresh() {
        val q = Player.queue
        val lib = Library.index.byId
        val songs = q.playOrder().mapNotNull { lib[it] }
        val rows = songs.mapIndexed { i, s -> SongRow(s, songs, i, SongRow.Mode.QUEUE) }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        val left = songs.drop(q.pos + 1).sumOf { it.durationMs }
        info.text = tr("${songs.size} songs · ${Text.time(left)} after this one", "${songs.size} şarkı · bundan sonra ${Text.time(left)}") +
            (if (q.shuffle) tr(" · shuffle", " · karışık") else "") +
            when (q.repeat) { Repeat.OFF -> ""; Repeat.ALL -> tr(" · repeat all", " · tümünü tekrarla"); Repeat.ONE -> tr(" · repeat one", " · birini tekrarla") }
    }
}

/** The video picture: a SurfaceView fitted to the video's aspect ratio. Exists only while it is on screen. */
@SuppressLint("ViewConstructor")
private class PictureView(act: MainActivity) : FrameLayout(act), SurfaceHolder.Callback {
    private val surface = SurfaceView(act)
    private var vw = 16
    private var vh = 9

    init {
        setBackgroundColor(C.BG)
        addView(surface)
        surface.holder.addCallback(this) // taps go to the art box (single = fullscreen, double = ∓10 s)
    }

    fun setVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || (w == vw && h == vh)) return
        vw = w; vh = h
        requestLayout()
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        var cw = w
        var ch = (w.toLong() * vh / vw).toInt()
        if (ch > h) { ch = h; cw = (h.toLong() * vw / vh).toInt() }
        surface.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = (width - surface.measuredWidth) / 2
        val y = (height - surface.measuredHeight) / 2
        surface.layout(x, y, x + surface.measuredWidth, y + surface.measuredHeight)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Player.showVideo(holder)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    // Must detach before returning: the screen went off, the app left the screen or the picture was removed.
    override fun surfaceDestroyed(holder: SurfaceHolder) = Player.showVideo(null)
}
