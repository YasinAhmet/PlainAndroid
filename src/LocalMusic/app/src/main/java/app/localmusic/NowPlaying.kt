package app.localmusic

import android.annotation.SuppressLint
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
import app.plain.lang.tr
import kotlin.math.min

/** Full-screen now-playing: big art, seek slider with elapsed/remaining, transport, shuffle/repeat, queue, sleep. */
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
    private val chrome = ArrayList<View>() // everything but the picture (hidden in fullscreen)

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
        top.addView(act.icon(R.drawable.ic_down, desc = tr("Close", "Kapat")) { act.closeNowPlaying() })
        top.addView(from, lpL(0, WRAP, 1f))
        top.addView(act.icon(R.drawable.ic_more, desc = tr("More", "Diğer")) { Player.current?.let { act.songMenu(it) } })
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
        col.addView(artBox, lpL(MATCH, 0, 1f).apply { topMargin = act.dp(12); bottomMargin = act.dp(20) })

        val titleRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val texts = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        title.setSingleLine()
        title.isSelected = true
        title.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
        title.marqueeRepeatLimit = -1
        texts.addView(title); texts.addView(artist)
        titleRow.addView(texts, lpL(0, WRAP, 1f))
        titleRow.addView(act.icon(R.drawable.ic_add_list, 48, 11, desc = tr("Add to playlist", "Çalma listesine ekle")) { Player.current?.let { act.addToPlaylist(listOf(it)) } })
        col.addView(titleRow, lpL(MATCH, WRAP))
        chrome += titleRow

        slider.onSeek = { Player.seekTo(it) }
        col.addView(slider, lpL(MATCH, WRAP).apply { topMargin = act.dp(10); leftMargin = -act.dp(10); rightMargin = -act.dp(10) })
        chrome += slider

        val controls = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        shuffleBtn = act.icon(R.drawable.ic_shuffle, 52, 13, desc = tr("Shuffle", "Karıştır")) { Player.toggleShuffle() }
        repeatBtn = act.icon(R.drawable.ic_repeat, 52, 13, desc = tr("Repeat", "Tekrarla")) { Player.cycleRepeat() }
        playBtn = act.icon(R.drawable.ic_play, 72, 20, 0xFF000000.toInt(), tr("Play/pause", "Oynat/duraklat")) { Player.toggle() }
        playBtn.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), oval(C.TEXT), null)
        fun spacer() = View(act).also { controls.addView(it, lpL(0, 1, 1f)) }
        controls.addView(shuffleBtn); spacer()
        controls.addView(act.icon(R.drawable.ic_prev, 60, 14, desc = tr("Previous", "Önceki")) { Player.prev() }); spacer()
        controls.addView(playBtn); spacer()
        controls.addView(act.icon(R.drawable.ic_next, 60, 14, desc = tr("Next", "Sonraki")) { Player.next() }); spacer()
        controls.addView(repeatBtn)
        col.addView(controls, lpL(MATCH, WRAP).apply { topMargin = act.dp(4) })
        chrome += controls

        bottom = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sleepBtn = act.icon(R.drawable.ic_timer, 48, 12, desc = tr("Sleep timer", "Uyku zamanlayıcısı")) { act.sleepMenu() }
        bottom.addView(sleepBtn)
        bottom.addView(sleepText, lpL(0, WRAP, 1f))
        bottom.addView(act.icon(R.drawable.ic_queue, 48, 12, desc = tr("Queue", "Sıra")) { act.openQueue() })
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
        playBtn.setImageResource(if (Player.playing) R.drawable.ic_pause else R.drawable.ic_play)
        shuffleBtn.tint(if (Player.queue.shuffle) C.ACCENT else C.TEXT)
        when (Player.queue.repeat) {
            Repeat.OFF -> { repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.tint(C.TEXT) }
            Repeat.ALL -> { repeatBtn.setImageResource(R.drawable.ic_repeat); repeatBtn.tint(C.ACCENT) }
            Repeat.ONE -> { repeatBtn.setImageResource(R.drawable.ic_repeat_one); repeatBtn.tint(C.ACCENT) }
        }
        updateSleep()
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
            val pic = picture ?: PictureView(act) { setFullscreen(!fullscreen) }.also {
                picture = it
                artBox.addView(it, lpF(MATCH, MATCH))
                art.visibility = View.INVISIBLE
            }
            pic.setVideoSize(s.videoW, s.videoH)
        } else dropVideo()
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
        for (v in chrome) v.visibility = if (on) View.GONE else View.VISIBLE
        if (on) col.setPadding(0, 0, 0, 0) else col.setPadding(act.dp(20), act.dp(4), act.dp(20), act.dp(12))
        (artBox.layoutParams as LinearLayout.LayoutParams).apply { topMargin = if (on) 0 else act.dp(12); bottomMargin = if (on) 0 else act.dp(20) }
        artBox.requestLayout()
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
        top.addView(act.icon(R.drawable.ic_down, desc = tr("Close", "Kapat")) { act.closeOverlay(this) })
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
private class PictureView(act: MainActivity, onTap: () -> Unit) : FrameLayout(act), SurfaceHolder.Callback {
    private val surface = SurfaceView(act)
    private var vw = 16
    private var vh = 9

    init {
        setBackgroundColor(C.BG)
        addView(surface)
        surface.holder.addCallback(this)
        setOnClickListener { onTap() }
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
