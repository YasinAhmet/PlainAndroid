package app.plain.download

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.update.PlainUpdate
import app.plain.ui.Ic
import app.plain.ui.Sheet
import java.util.concurrent.Executors
import app.plain.lang.tr

class MainActivity : Activity() {
    val main = Handler(Looper.getMainLooper())
    // one background thread. After onDestroy (shutdown) a late execute (a reply posted back, e.g. sync() after
    // "Empty trash" or refreshCached() after a cache trim) is dropped instead of crashing with RejectedExecutionException
    private val io = java.util.concurrent.ThreadPoolExecutor(1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())

    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var card: LinearLayout
    private lateinit var thumb: Thumb
    private lateinit var titleV: TextView
    private lateinit var metaV: TextView
    private lateinit var audioBox: LinearLayout
    private lateinit var videoBox: LinearLayout
    private lateinit var fmtRow: FlowLayout
    private lateinit var qRow: FlowLayout
    private lateinit var heightRow: FlowLayout
    private lateinit var noteV: TextView
    private lateinit var goBtn: TextView
    private lateinit var list: LinearLayout
    private lateinit var listHeader: LinearLayout

    private var info: VideoInfo? = null
    private var fetchSeq = 0
    private var kind = Kind.AUDIO
    private var audioFmt = AudioFmt.M4A
    private var audioQ = AudioQ.HIGH
    private var choice: VideoChoice? = null
    private val kindChips = HashMap<Kind, Chip>()
    private val rows = LinkedHashMap<Long, Row>()
    private val batchRows = HashMap<Long, BatchRow>()
    private val openBatches = HashSet<Long>()
    private var shownKeys: List<Long> = emptyList() // job ids, and -batch for batch rows
    private val onStore: () -> Unit = { renderList() }
    private lateinit var moreRow: TextView
    private var showAll = false

    // thumbnails of the Download tab (rows on screen only) + the temporary "Cached" folder
    private val dlCalls = Calls()
    private lateinit var dlThumbs: ThumbLoader
    private lateinit var scroll: ScrollView
    private val thumbRect = android.graphics.Rect()
    private lateinit var cachedHeader: TextView
    private lateinit var cachedBox: LinearLayout
    private var cachedOpen = false
    private val cacheRows = ArrayList<Pair<android.widget.ImageView, String>>()

    // tabs: 0 = Download (the original screen), 1 = Search
    lateinit var search: SearchScreen
        private set
    private lateinit var downloadView: View
    private val tabViews = ArrayList<TextView>()
    private var tab = -1
    private var started = false
    /** Between onStart and onStop (the Spotify page reader needs the screen shown). */
    val visibleNow get() = started
    private var cameFromSearch = false // Back on Download returns to Search after a tap on a search result

    // 2.3: the app-level player (overlay) and the Back history
    lateinit var player: PlayerPanel
        private set
    private val nav = NavHistory()
    private var lastBackAt = -10_000L
    private var lastBackHandled = -1_000_000L
    private var leftPip = false // PiP just ended: expanded (onResume follows) or closed (onStop follows)
    private var moreBtn: View? = null // the ⋮ button: the language dropdown hangs under it

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        Store.init(this)
        kind = Store.kind; audioFmt = Store.audioFmt; audioQ = Store.audioQ
        search = SearchScreen(this)
        dlThumbs = ThumbLoader(this, dlCalls)
        val frame = android.widget.FrameLayout(this)
        frame.addView(build(), android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        player = PlayerPanel.get(this)
        player.attach(this)
        frame.addView(player.root, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(Hub.wrap(this, "youtube", frame))
        // thumb bar (layout "draft 3"): Download / Search and ⋮ sit at the bottom next to the "Youtube ▾" switcher
        Hub.bar(this)?.apply {
            tabViews.forEach { addView(it, lpL(WRAP, WRAP).apply { rightMargin = dp(6) }) }
            addView(View(this@MainActivity), lpL(0, 1, 1f))
            addView(icon(R.drawable.yt_ic_more, desc = tr("Menu", "Menü")) { mainMenu() }.also { moreBtn = it })
        }
        search.onTitle = { name, page -> if (tab == 1) { Hub.setTitle(this, name); Hub.setBack(this, if (page) ({ handleBack() }) else null) } }
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC // hardware keys = media volume while the app is in front
        search.onNav = { nav.push(it) }
        search.onPagePopped = { nav.dropPage() }
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { handleBack() }
        }
        selectTab(Store.tab.coerceIn(0, 1), record = false)
        handleIntent(intent)
        // 3.3: completed caches become saved files (also the old Cached/ folder: moved, duplicates deleted) — once
        // when this tab opens, not at every app start (the Music tab alone never runs it)
        io.execute { try { CachePublisher.publishAll(applicationContext) } catch (_: Exception) {}; main.post { if (!isDestroyed) { renderList(); refreshCached() } } }
    }

    companion object {
        private const val REQ_IMPORT = 7
        private const val REQ_TRASH = 20
        private const val REQ_UNTRASH = 21
        private const val REQ_DELETE = 22
        private const val LIST_LIMIT = 50
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        started = true
        player.hostStart()
        Store.addListener(onStore)
        renderList()
        if (tab == 1) search.onShown()
        sync() // MediaStore → history (trashed / restored / deleted / moved by any app), then the Cached folder
        main.post { loadVisibleThumbs() }
        // queued downloads left from before (e.g. after the phone restarted the app) run when the app is opened
        if (Store.queuedCount() > 0 && !DownloadService.running && (!Store.wifiOnly || runCatching { Net.network(this, true) }.isSuccess)) {
            DownloadService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        leftPip = false
    }

    override fun onStop() {
        // PiP closed with its ✕ = stop; screen off in PiP or Home without PiP = pause and release (no background playback)
        player.hostStop(pipClosed = leftPip)
        leftPip = false
        started = false
        Store.removeListener(onStore)
        search.onHidden() // cancels every Search request (and queued thumbnails)
        dlCalls.cancelAll(); dlThumbs.cancel()
        rows.values.forEach { it.thumbAsked = false }
        super.onStop()
    }

    override fun onDestroy() {
        player.detach(this) // playing: goes on under the playback service; paused: closes
        io.shutdownNow()
        search.destroy()
        dlThumbs.shutdown()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = handleBack()

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (!inPip) leftPip = true
        player.onPip(inPip)
    }

    /** API 30 has no auto-enter: Home while a video plays enters PiP if the setting is on (31+ uses setAutoEnterEnabled). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && Store.pipAuto && player.state != PState.CLOSED) player.enterPip()
    }

    fun onPlayerState(s: PState) {
        if (s == PState.FULL) getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        Hub.setHeaderShown(this, s != PState.FULL) // the full player has the whole screen
    }

    /**
     * Back = one step back through the app: end a selection, leave the player's full screen / minimise it, then the
     * history (tabs, Feed/Search/Lists, pages, the Cached folder); at the very start "press back again to exit".
     * Dialogs and menus are their own windows and close first by themselves. Both Back paths (onBackPressed and the
     * API 33+ callback) may fire for one press: the second is ignored.
     */
    private fun handleBack() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastBackHandled < 150) return
        lastBackHandled = now
        val selectingHere = if (tab == 0) selecting else search.topSelecting
        when (BackRule.decide(selectingHere, player.isFull, player.isFullscreen, !nav.isEmpty, lastBackAt, now)) {
            BackRule.Action.END_SELECTION -> if (tab == 0) endSelection() else search.endTopSelection()
            BackRule.Action.LEAVE_FULLSCREEN -> player.leaveFullscreen()
            BackRule.Action.MINIMIZE_PLAYER -> player.minimize()
            BackRule.Action.POP -> popNav()
            BackRule.Action.EXIT -> moveTaskToBack(true)
            BackRule.Action.EXIT_HINT -> { lastBackAt = now; toast(tr("Press back again to exit", "Çıkmak için tekrar geri basın")) }
        }
    }

    private fun popNav() {
        while (true) {
            when (val s = nav.pop() ?: return) {
                is NavStep.Tab -> { selectTab(s.from, record = false); return }
                is NavStep.Base -> { if (tab != 1) selectTab(1, record = false); search.showBaseIndex(s.from); return }
                NavStep.Page -> { if (tab != 1) selectTab(1, record = false); if (search.popPage()) return } // stale = already gone
                NavStep.CachedOpen -> { if (cachedOpen) { if (tab != 0) selectTab(0, record = false); cachedOpen = false; refreshCached(); return } }
            }
        }
    }

    /** Shared links still land in Download; a shared channel/playlist link opens its page in Search. */
    private fun handleIntent(i: Intent?) {
        i?.getStringExtra("open_video")?.let { id -> i.removeExtra("open_video"); openVideo(id); cameFromSearch = false; return }
        i?.getStringExtra("open_channel")?.let { id ->
            val name = i.getStringExtra("channel_name").orEmpty()
            i.removeExtra("open_channel"); openChannel(id, name); return
        }
        if (i?.action != Intent.ACTION_SEND) return
        val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
        i.action = null // don't refetch on rotation/recreate
        when (val target = Links.classify(t)) {
            is Target.Video -> { selectTab(0); cameFromSearch = false; input.setText(Yt.watchUrl(target.id)); fetch() }
            is Target.Playlist, is Target.Channel, is Target.ChannelUrl -> { if (tab != 1) nav.push(NavStep.Tab(tab)); search.open(target); selectTab(1, record = false) }
            else -> { selectTab(0); showStatus(tr("No YouTube link in what was shared", "Paylaşılan içerikte YouTube bağlantısı yok"), true) }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // tabs

    private fun selectTab(t: Int, record: Boolean = true) {
        if (t != 0) cameFromSearch = false
        val changed = t != tab
        if (changed && record && tab >= 0) nav.push(NavStep.Tab(tab))
        tab = t
        Store.tab = t
        tabViews.forEachIndexed { i, v ->
            v.setTextColor(if (i == t) C.ACCENT else C.DIM)
            v.background = outline(dpf(18), if (i == t) C.ACCENT else C.OUTLINE)
        }
        if (t == 0) { Hub.setTitle(this, tr("Download", "İndir")); Hub.setBack(this, null) } else search.reportTitle()
        downloadView.visibility = if (t == 0) View.VISIBLE else View.GONE
        search.view.visibility = if (t == 1) View.VISIBLE else View.GONE
        if (!changed) return
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(window.decorView.windowToken, 0)
        if (t == 1) { if (started) search.onShown() } else search.onHidden()
        if (t == 0) main.post { loadVisibleThumbs() }
    }

    /** 2 dp amber line under the selected tab. */
    private fun underline() = android.graphics.drawable.LayerDrawable(arrayOf(roundRect(C.ACCENT, 0f))).apply {
        setLayerGravity(0, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
        setLayerHeight(0, dp(2))
    }

    /** A search result was tapped: the usual Download flow for that video, prefilled. */
    /** Channel page in the Search tab (hold menu "Visit channel", the player's Channel button, the info card). */
    fun openChannel(id: String, name: String) {
        if (tab != 1) nav.push(NavStep.Tab(tab)) // Back: the page first, then the tab we came from
        search.openChannel(id, name) // pushed first, so only that page loads when the tab shows
        selectTab(1, record = false)
    }

    fun openVideo(id: String) {
        selectTab(0)
        cameFromSearch = true
        input.setText(Yt.watchUrl(id))
        fetch()
    }

    /** With "Wi-Fi only", refuse to start downloads off Wi-Fi (with an explanation). */
    fun wifiOk(): Boolean {
        if (!Store.wifiOnly) return true
        return try { Net.network(this, true); true } catch (e: Exception) {
            message(tr("Not on Wi-Fi", "Wi-Fi bağlı değil"), tr("Wi-Fi only is on, so nothing is downloaded over mobile data. Connect to Wi-Fi, or turn Wi-Fi only off in ⋮.", "Yalnızca Wi-Fi açık, bu yüzden mobil veriyle hiçbir şey indirilmez. Wi-Fi'a bağlanın ya da ⋮ menüsünden Yalnızca Wi-Fi'ı kapatın."))
            false
        }
    }

    fun pickTakeout() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "text/csv",
                "text/comma-separated-values", "text/plain", "application/octet-stream"))
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_IMPORT)
        } catch (e: Exception) { toast(tr("No file picker available", "Dosya seçici yok")) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK) data?.data?.let { search.importUri(it) }
        if (requestCode == AccountUi.REQ_COOKIES || requestCode == AccountUi.REQ_SIGNIN) account.onResult(requestCode, resultCode, data)
        if (requestCode in REQ_TRASH..REQ_DELETE) sync() // the system dialog trashed / restored / deleted files
    }

    // ---------------------------------------------------------------------------------------------------------
    // layout

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins
        }

        // Download / Search: pills placed in the Hub thumb bar (onCreate)
        for ((i, label) in listOf(tr("Download", "İndir"), tr("Search", "Ara")).withIndex()) {
            val tv = text(label, 14f, C.DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(8), dp(13), dp(8))
                setOnClickListener { selectTab(i) }
            }
            tabViews += tv
        }

        val pages = android.widget.FrameLayout(this)
        root.addView(pages, lpL(MATCH, 0, 1f))
        undoBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(16), dp(8), dp(12), dp(8)); background = outline(0f, C.ACCENT)
        }
        undoText = text("", 14f, C.TEXT, lines = 2)
        undoBar.addView(undoText, lpL(0, WRAP, 1f))
        undoBar.addView(pill(tr("Undo", "Geri al"), C.ACCENT, C.ACCENT) { undoAction?.invoke(); hideUndo() })
        root.addView(undoBar, lpL(MATCH, WRAP))
        scroll = ScrollView(this).apply { isFillViewport = true; setOnScrollChangeListener { _, _, _, _, _ -> loadVisibleThumbs() } }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(24)) }
        scroll.addView(col)
        downloadView = scroll
        pages.addView(scroll, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        pages.addView(search.view, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))

        input = EditText(this).apply {
            hint = tr("Paste a YouTube link (video, channel or playlist)", "YouTube bağlantısı yapıştırın (video, kanal veya çalma listesi)")
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setTextColor(C.TEXT); setHintTextColor(C.LABEL); textSize = 15f
            background = outline(dpf(12))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { fetch(); true } else false }
        }
        col.addView(input, lpL(MATCH, WRAP))

        val btns = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(10), 0, 0) }
        btns.addView(pill(tr("Paste", "Yapıştır")) { paste() })
        btns.addView(View(this), lpL(dp(10), 1))
        btns.addView(pill(tr("Get info", "Bilgi al"), C.ACCENT, C.ACCENT) { fetch() })
        col.addView(btns, lpL(MATCH, WRAP))

        status = text("", 13f, C.DIM, lines = 4).apply { visibility = View.GONE; setPadding(dp(2), dp(10), dp(2), 0) }
        col.addView(status, lpL(MATCH, WRAP))

        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(16), 0, 0)
        }
        thumb = Thumb(this)
        val thumbBox = android.widget.FrameLayout(this).apply { setOnClickListener { info?.let { playInfo(it) } } }
        thumbBox.addView(thumb, android.widget.FrameLayout.LayoutParams(MATCH, WRAP))
        thumbBox.addView(playGlyph(28f), android.widget.FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER))
        card.addView(thumbBox, lpL(MATCH, WRAP))
        titleV = text("", 16f, C.TEXT, bold = true, lines = 3).apply { setPadding(0, dp(10), 0, 0) }
        card.addView(titleV, lpL(MATCH, WRAP))
        metaV = text("", 13f, C.DIM).apply {
            setPadding(0, dp(2), 0, dp(10))
            // the channel name on the info card opens the channel page
            setOnClickListener { info?.let { v -> Hold.visitChannel(this@MainActivity, v.id, v.channelId, Naming.channel(v.author)) } }
        }
        card.addView(metaV, lpL(MATCH, WRAP))

        val kindRow = FlowLayout(this)
        for (k in Kind.values()) {
            val c = Chip(this, if (k == Kind.AUDIO) tr("Audio", "Ses") else "Video (MP4)") { setKind(k) }
            kindChips[k] = c
            kindRow.addView(c)
        }
        card.addView(kindRow, lpL(MATCH, WRAP))

        audioBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        audioBox.addView(label(tr("Format", "Biçim")))
        fmtRow = FlowLayout(this); audioBox.addView(fmtRow, lpL(MATCH, WRAP))
        audioBox.addView(label(tr("Quality", "Kalite")).apply { setPadding(0, dp(12), 0, dp(6)) })
        qRow = FlowLayout(this); audioBox.addView(qRow, lpL(MATCH, WRAP))
        card.addView(audioBox, lpL(MATCH, WRAP))

        videoBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(12), 0, 0) }
        videoBox.addView(label(tr("Quality", "Kalite")))
        heightRow = FlowLayout(this); videoBox.addView(heightRow, lpL(MATCH, WRAP))
        card.addView(videoBox, lpL(MATCH, WRAP))

        noteV = text("", 12f, C.LABEL, lines = 4).apply { setPadding(0, dp(12), 0, 0) }
        card.addView(noteV, lpL(MATCH, WRAP))
        goBtn = pill(tr("Download", "İndir"), C.ACCENT, C.ACCENT) { download() }
        card.addView(goBtn, lpL(MATCH, WRAP).apply { topMargin = dp(12) })
        col.addView(card, lpL(MATCH, WRAP))

        selBar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; setPadding(0, dp(20), 0, dp(4)) }
        selText = text("", 15f, C.TEXT, bold = true)
        selBar.addView(icon(R.drawable.yt_ic_close, desc = tr("Stop selecting", "Seçimi bitir")) { endSelection() })
        selBar.addView(selText, lpL(0, WRAP, 1f))
        selBar.addView(pill(tr("All", "Tümü")) { selectAllJobs() })
        selBar.addView(View(this), lpL(dp(8), 1))
        selBar.addView(pill(tr("Erase", "Sil"), C.ACCENT, C.ACCENT) { eraseJobs(Store.snapshot().filter { it.id in selected }); endSelection() })
        selBar.addView(icon(R.drawable.yt_ic_more, desc = tr("More for the selection", "Seçim için diğer")) { selectionMenu() })
        col.addView(selBar, lpL(MATCH, WRAP))
        listHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(24), 0, dp(4))
            addView(text(tr("Downloads", "İndirilenler"), 15f, C.DIM, bold = true), lpL(0, WRAP, 1f))
            addView(text(tr("Clear finished", "Bitenleri temizle"), 13f, C.LABEL).apply {
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setOnClickListener { Store.clearFinished() }
            })
        }
        col.addView(listHeader, lpL(MATCH, WRAP))
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(list, lpL(MATCH, WRAP))
        moreRow = text("", 13f, C.DIM).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(14))
            setOnClickListener { showAll = true; renderList() }
        }
        col.addView(moreRow, lpL(MATCH, WRAP))

        cachedHeader = text(tr("Cached", "Önbellek"), 15f, C.DIM, bold = true, lines = 2).apply {
            setPadding(0, dp(24), 0, dp(4))
            setOnClickListener { cachedOpen = !cachedOpen; if (cachedOpen) nav.push(NavStep.CachedOpen) else nav.dropCached(); refreshCached() }
        }
        col.addView(cachedHeader, lpL(MATCH, WRAP))
        cachedBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        col.addView(cachedBox, lpL(MATCH, WRAP))
        return root
    }

    private fun label(s: String) = text(s, 12f, C.LABEL).apply { setPadding(0, 0, 0, dp(6)) }

    private fun showStatus(s: String?, error: Boolean = false) {
        status.text = s.orEmpty()
        status.setTextColor(if (error) C.RED else C.DIM)
        status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun mainMenu() {
        val q = Store.queue().items.size
        val acct = if (!Account.hasAccount) tr("Not signed in", "Oturum yok") else if (Account.expired) tr("Expired", "Süresi doldu") else tr("Signed in", "Oturum açık")
        Sheet(this, C.ACCENT).header("Youtube", tr("Downloads, playback and your account", "İndirmeler, oynatma ve hesabınız"))
            .actions(
                Sheet.Action(Ic.QUEUE, tr("Play queue ($q)", "Çalma sırası ($q)")) { if (q == 0) toast(tr("The play queue is empty", "Çalma sırası boş")) else PlayerActivity.queue(this) },
                Sheet.Action(Ic.REFRESH, tr("Retry failed", "Başarısızları dene")) {
                    if (wifiOk()) {
                        val n = Store.retryAllFailed()
                        if (n > 0) DownloadService.start(this)
                        toast(if (n > 0) tr("Retrying $n", "$n yeniden deneniyor") else tr("Nothing failed", "Başarısız olan yok"))
                    }
                },
                Sheet.Action(Ic.CLOSE, tr("Cancel queued", "Sıradakileri iptal et")) { Store.cancelQueued() },
                Sheet.Action(Ic.CHECK, tr("Clear finished", "Bitenleri temizle")) { Store.clearFinished() },
            )
            .section(tr("Downloads", "İndirmeler"))
            .toggle(Ic.WIFI, tr("Wi-Fi only", "Yalnızca Wi-Fi"), Store.wifiOnly, sub = tr("Off: mobile data is used too", "Kapalı: mobil veri de kullanılır")) { on -> Store.wifiOnly = on }
            .item(Ic.TUNE, tr("Audio format", "Ses biçimi"), value = if (Store.originalAudio) tr("Original", "Orijinal") else tr("Clean M4A", "Temiz M4A"), more = true,
                reset = Sheet.Reset(tr("Original", "Orijinal")) { Store.originalAudio = true }) { audioFormatMenu() }
            .item(Ic.SPEED, tr("Parallel audio downloads", "Paralel ses indirme"), sub = tr("For bulk music downloads", "Toplu müzik indirmeleri için"), value = "${Store.parallelAudio}", more = true,
                reset = Sheet.Reset("3") { Store.parallelAudio = 3 }) {
                Sheet.choose(this, tr("Parallel audio downloads", "Paralel ses indirme"), (1..4).map { "$it" }, Store.parallelAudio - 1, C.ACCENT,
                    tr("More is faster on good Wi-Fi; videos always download one at a time", "İyi Wi-Fi'de çoğu daha hızlıdır; videolar her zaman tek tek iner")) { Store.parallelAudio = it + 1 }
            }
            .item(Ic.PHOTO, tr("Cover art in audio files", "Ses dosyalarında kapak"), value = Store.artQuality.label, more = true,
                reset = Sheet.Reset(ArtQuality.LOW.label) { Store.artQuality = ArtQuality.LOW }) { artMenu() }
            .item(Ic.ADD, tr("Add missing cover art", "Eksik kapakları ekle"), sub = tr("For files saved without one", "Kapaksız kaydedilen dosyalar için")) { offerArt(force = true) }
            .section(tr("Storage", "Depolama"))
            .item(Ic.CACHE, tr("Cache limits", "Önbellek sınırları"), value = "${Store.videoCapGb} / ${Store.musicCapGb} GB", sub = tr("Video / music", "Video / müzik"), more = true) { cacheLimits() }
            .item(Ic.RESTORE, tr("Trash", "Çöp kutusu"), value = "$trashCount" + if (trashBytes > 0) " · " + Text.size(trashBytes) else "", more = true) { trashView() }
            .item(Ic.DELETE, tr("Clear cached videos and music", "Önbellekteki video ve müzikleri temizle"), sub = tr("Downloads stay", "İndirilenler kalır"), danger = true) {
                io.execute { Store.cache.clear(CacheKind.VIDEO); Store.cache.clear(CacheKind.MUSIC); main.post { refreshCached() } }
            }
            .section(tr("Playback", "Oynatma"))
            .toggle(Ic.PIP, tr("Picture-in-picture on Home", "Ana ekranda küçük pencere"), Store.pipAuto,
                sub = tr("Off: Home pauses the video", "Kapalı: Ana ekran videoyu duraklatır")) { on -> Store.pipAuto = on; player.updatePip(force = true) }
            .item(Ic.MAP, tr("Region for blocked videos", "Engelli videolar için bölge"), value = Store.altRegion.ifEmpty { tr("Off", "Kapalı") }, more = true,
                reset = Sheet.Reset("US") { Store.altRegion = "US" }) { regionMenu() }
            .section(tr("Account", "Hesap"))
            .item(Ic.ACCOUNT, tr("YouTube account", "YouTube hesabı"), value = acct, more = true) { account.show() }
            .item(Ic.UPLOAD, tr("Import subscriptions", "Abonelikleri içe aktar"), sub = tr("From a Google Takeout file", "Google Takeout dosyasından"), more = true) { selectTab(1); search.importDialog() }
            .section(tr("App", "Uygulama"))
            .item(Ic.LANGUAGE, tr("Language", "Dil"), value = Sheet.languageValue(), more = true, reset = Sheet.languageReset(this) { languageChanged() }) { Sheet.language(this, C.ACCENT) { languageChanged() } }
            .item(Ic.UPDATE, tr("Check for updates", "Güncellemeleri denetle"), value = Sheet.version(this)) { PlainUpdate.check(this) }
            .item(Ic.INFO, tr("About & legal", "Hakkında ve yasal")) { about() }
            .show()
    }

    private fun audioFormatMenu() {
        Sheet(this, C.ACCENT).header(tr("Audio format", "Ses biçimi"))
            .choice(tr("Clean M4A", "Temiz M4A"), !Store.originalAudio, sub = tr("Converted on the phone: tags and cover art, plays everywhere", "Telefonda dönüştürülür: etiketler ve kapak, her yerde çalar")) { Store.originalAudio = false; refreshNote() }
            .choice(tr("Original format", "Orijinal biçim"), Store.originalAudio, sub = tr("YouTube's file as it is: faster, no conversion", "YouTube'un dosyası olduğu gibi: daha hızlı, dönüştürme yok")) { Store.originalAudio = true; refreshNote() }
            .show()
    }

    /**
     * A new language: the activity is recreated so every string is rebuilt. The player outlives the activity (it is
     * the process's); it is kept even when paused (a normal destroy would close a paused player) and its own fixed
     * labels are rewritten, since its views are not rebuilt.
     */
    private fun languageChanged() {
        if (player.state != PState.CLOSED) player.keepOnNextDetach()
        player.relabel()
        PlayService.channel(this); DownloadService.channel(this) // notification channel names follow too
        recreate()
    }

    private val account by lazy { AccountUi(this) }

    private fun about() = message(
        "Plain Youtube",
        tr("Downloads YouTube audio (M4A/AAC or Opus) and MP4 video for personal offline use.\n\n" +
            "Audio → Music/PlainDownload/<Channel>/\nVideo → Movies/PlainDownload/<Channel>/\n\n" +
            "Streams are found with YouTube's own InnerTube API (clients: " + Clients.ORDER.joinToString { it.key } +
            "). YouTube changes break this from time to time.\n\n" +
            "YouTube's Terms of Service forbid downloading except where YouTube offers a download option. " +
            "Use this only for content you have the right to keep; you are responsible for how you use it.\n\n" +
            "No analytics. No account unless you add one in ⋮ → Account, and then it's used only for members-only videos and " +
            "the subscriptions sync; everything else stays anonymous. Network is used only when you fetch a link, search, open a channel, " +
            "playlist or the Feed, or download. Search follows are kept on this phone only.",
            "YouTube seslerini (M4A/AAC veya Opus) ve MP4 videolarını kişisel çevrimdışı kullanım için indirir.\n\n" +
            "Ses → Music/PlainDownload/<Kanal>/\nVideo → Movies/PlainDownload/<Kanal>/\n\n" +
            "Akışlar YouTube'un kendi InnerTube API'siyle bulunur (istemciler: " + Clients.ORDER.joinToString { it.key } +
            "). YouTube'daki değişiklikler bunu zaman zaman bozar.\n\n" +
            "YouTube Hizmet Şartları, YouTube'un indirme seçeneği sunduğu durumlar dışında indirmeyi yasaklar. " +
            "Bunu yalnızca saklama hakkınız olan içerikler için kullanın; nasıl kullandığınızdan siz sorumlusunuz.\n\n" +
            "Analiz yok. ⋮ → Hesap'tan eklemediğiniz sürece hesap da yok; eklerseniz yalnızca üyelere özel videolar ve " +
            "abonelik eşitlemesi için kullanılır, geri kalan her şey anonim kalır. Ağ yalnızca bir bağlantı getirdiğinizde, arama yaptığınızda, " +
            "bir kanal, çalma listesi ya da Akış'ı açtığınızda veya indirdiğinizde kullanılır. Arama takipleri yalnızca bu telefonda tutulur.")
    )

    // ---------------------------------------------------------------------------------------------------------
    // fetch info

    private fun paste() {
        val cm = getSystemService(ClipboardManager::class.java)
        val t = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (t.isNullOrBlank()) { toast(tr("Clipboard is empty", "Pano boş")); return }
        input.setText(t.trim())
        fetch()
    }

    private fun fetch() {
        val text = input.text.toString()
        val target = Links.classify(text)
        if (target is Target.Playlist || target is Target.Channel || target is Target.ChannelUrl) {
            // channel / playlist links open their page in the Search tab
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
            showStatus(null)
            search.open(target) // pushed first, so only that page loads when the tab shows
            selectTab(1)
            return
        }
        val id = (target as? Target.Video)?.id
        if (id == null) {
            showStatus(tr("That isn't a YouTube link (video, shorts, music.youtube.com, a channel or a playlist)", "Bu bir YouTube bağlantısı değil (video, shorts, music.youtube.com, kanal veya çalma listesi)"), true)
            return
        }
        (getSystemService(InputMethodManager::class.java)).hideSoftInputFromWindow(input.windowToken, 0)
        val seq = ++fetchSeq
        info = null
        card.visibility = View.GONE
        showStatus(tr("Getting info…", "Bilgi alınıyor…"))
        val wifiOnly = Store.wifiOnly
        io.execute {
            val result: Result<VideoInfo> = try {
                val net = Net.network(this, wifiOnly)
                Result.success(InnerTube.info(id, net))
            } catch (e: Exception) { Result.failure(e) }
            main.post {
                if (seq != fetchSeq || isDestroyed) return@post
                result.fold({ show(it) }, { e ->
                    showStatus(when (e) {
                        is YtError, is NoWifi -> e.message
                        is java.net.UnknownHostException -> tr("No internet connection", "İnternet bağlantısı yok")
                        else -> tr("Couldn't get info: ${e.message ?: e.javaClass.simpleName}", "Bilgi alınamadı: ${e.message ?: e.javaClass.simpleName}")
                    }, true)
                })
            }
        }
    }

    private fun show(v: VideoInfo) {
        info = v
        showStatus(null)
        card.visibility = View.VISIBLE
        titleV.text = v.title
        metaV.text = listOf(if (v.members) tr("MEMBERS", "ÜYELER") else "", Naming.channel(v.author), Text.duration(v.durationSec)).filter { it.isNotEmpty() }.joinToString(" · ")
        metaV.setTextColor(if (v.members) C.BLUE else C.DIM)
        thumb.setImageDrawable(null)
        loadThumb(v)
        val choices = Pick.videoChoices(v.formats, Build.VERSION.SDK_INT)
        choice = Pick.closest(choices, Store.videoP)
        heightRow.removeAllViews()
        for (c in choices) {
            val chip = Chip(this, c.label + (if (c.estBytes > 0) " · " + Text.size(c.estBytes) else "")) { ch ->
                choice = c; Store.videoP = c.p
                for (i in 0 until heightRow.childCount) (heightRow.getChildAt(i) as Chip).select(heightRow.getChildAt(i) === ch)
                refreshNote()
            }
            chip.select(c == choice)
            heightRow.addView(chip)
        }
        if (choices.isEmpty()) heightRow.addView(text(tr("No MP4-compatible video stream for this video", "Bu video için MP4 uyumlu video akışı yok"), 13f, C.LABEL))
        setKind(kind)
    }

    private fun loadThumb(v: VideoInfo) {
        val seq = fetchSeq
        val wifiOnly = Store.wifiOnly
        io.execute {
            val bm = try {
                val bytes = Net.get(Net.network(this, wifiOnly), v.thumbnail)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) { null }
            if (bm != null) main.post { if (seq == fetchSeq && !isDestroyed) thumb.setImageBitmap(bm) }
        }
    }

    private fun setKind(k: Kind) {
        kind = k; Store.kind = k
        kindChips.forEach { (kk, c) -> c.select(kk == k) }
        audioBox.visibility = if (k == Kind.AUDIO) View.VISIBLE else View.GONE
        videoBox.visibility = if (k == Kind.VIDEO) View.VISIBLE else View.GONE
        if (k == Kind.AUDIO) buildAudioChips()
        refreshNote()
    }

    private fun buildAudioChips() {
        val v = info ?: return
        val offered = AudioFmt.values().filter { Pick.audio(v.formats, it, AudioQ.HIGH) != null }
        if (audioFmt !in offered && offered.isNotEmpty()) audioFmt = offered[0]
        fmtRow.removeAllViews()
        for (f in AudioFmt.values()) {
            if (f !in offered) continue
            fmtRow.addView(Chip(this, f.label) { audioFmt = f; Store.audioFmt = f; buildAudioChips(); refreshNote() }.apply { select(f == audioFmt) })
        }
        qRow.removeAllViews()
        val hi = Pick.audio(v.formats, audioFmt, AudioQ.HIGH)
        val lo = Pick.audio(v.formats, audioFmt, AudioQ.LOW)
        for (q in AudioQ.values()) {
            if (q == AudioQ.LOW && (lo == null || lo.itag == hi?.itag)) continue
            val lbl = (if (q == AudioQ.HIGH) tr("Best ", "En iyi ") else tr("Small ", "Küçük ")) + (Pick.audioLabel(v.formats, audioFmt, q) ?: "")
            qRow.addView(Chip(this, lbl.trim()) { audioQ = q; Store.audioQ = q; buildAudioChips(); refreshNote() }
                .apply { select(q == audioQ || q == AudioQ.HIGH && (lo == null || lo.itag == hi?.itag)) })
        }
    }

    private fun refreshNote() {
        val v = info ?: return
        val ch = Naming.channel(v.author)
        when (kind) {
            Kind.AUDIO -> {
                val f = Pick.audio(v.formats, audioFmt, audioQ) ?: Pick.audio(v.formats, audioFmt, AudioQ.HIGH)
                goBtn.isEnabled = f != null
                goBtn.text = tr("Download audio", "Sesi indir") + (f?.estBytes?.takeIf { it > 0 }?.let { " · " + Text.size(it) } ?: "")
                noteV.text = (if (audioFmt == AudioFmt.M4A) (if (Store.originalAudio) tr("Saved as YouTube's original .m4a (AAC, no conversion; change in ⋮) in ", "YouTube'un orijinal .m4a dosyası olarak kaydedilir (AAC, dönüştürme yok; ⋮ menüsünden değiştirin). Konum: ")
                    else tr("Converted to a clean .m4a (AAC, no re-encoding) in ", "Temiz bir .m4a'ya dönüştürülür (AAC, yeniden kodlama yok). Konum: "))
                    else tr("Opus is converted to .ogg (no re-encoding: Android has no audio WebM file type) in ", "Opus, .ogg'a dönüştürülür (yeniden kodlama yok: Android'de ses WebM dosya türü yok). Konum: ")) +
                    "Music/PlainDownload/$ch/"
            }
            Kind.VIDEO -> {
                val c = choice
                goBtn.isEnabled = c != null
                goBtn.text = tr("Download video", "Videoyu indir") + (c?.let { " " + it.label } ?: "") + (c?.estBytes?.takeIf { it > 0 }?.let { " · " + Text.size(it) } ?: "")
                noteV.text = tr("Saved as .mp4 (H.264 + AAC", ".mp4 olarak kaydedilir (H.264 + AAC") + (if (c?.codec == VCodec.AV1) tr("; AV1 video: needs an AV1 decoder", "; AV1 video: AV1 kod çözücü gerekir") else "") +
                    tr(") in ", "). Konum: ") + "Movies/PlainDownload/$ch/"
            }
        }
        goBtn.alpha = if (goBtn.isEnabled) 1f else 0.4f
    }

    // ---------------------------------------------------------------------------------------------------------
    // start

    private fun download() {
        val v = info ?: return
        if (!wifiOk()) return
        val c = choice
        if (kind == Kind.VIDEO && c == null) return
        // one copy per video (3.3): already saved / already queued as this kind -> say where, don't download again
        if (Store.queued(v.id, kind)) { toast(tr("Already in the download queue", "Zaten indirme sırasında")); return }
        val have = Store.savedUri(v.id, kind)
        if (have != null) {
            io.execute {
                val f = Saved.find(this, v.id, kind)
                main.post {
                    if (isDestroyed) return@post
                    if (f != null) message(tr("Already downloaded", "Zaten indirildi"), f.where) else download()
                }
            }
            return
        }
        askNotificationsOnce()
        val j = Job(
            id = Store.newId(), videoId = v.id, title = v.title, channel = Naming.channel(v.author), durationSec = v.durationSec,
            kind = kind, audioFmt = audioFmt, audioQ = audioQ, height = c?.p ?: 0, codec = c?.codec ?: VCodec.AVC,
            total = if (kind == Kind.AUDIO) (Pick.audio(v.formats, audioFmt, audioQ)?.estBytes ?: 0) else (c?.estBytes ?: 0),
            members = v.members,
        )
        Store.add(j)
        DownloadService.start(this)
        toast(tr("Queued", "Sıraya alındı"))
    }

    fun askNotificationsOnce() {
        if (Build.VERSION.SDK_INT < 33 || Store.askedNotif) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        Store.askedNotif = true
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    // ---------------------------------------------------------------------------------------------------------
    // downloads list

    private inner class Row(val id: Long) {
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(outline(dpf(12)))
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val title = text("", 14f, C.TEXT, bold = true, lines = 2)
        val sub = text("", 12f, C.DIM)
        val state = text("", 12f, C.LABEL, lines = 3)
        val bar = ProgressLine(this@MainActivity)
        val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        val thumbV = RoundImage(this@MainActivity)
        val membersV = membersBadge()
        var thumbAsked = false
        var videoId = ""

        init {
            val top = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            val box = android.widget.FrameLayout(this@MainActivity).apply {
                // the thumbnail plays: the saved file when done, else the stream
                setOnClickListener { Store.get(this@Row.id)?.copy()?.let { playJob(it) } }
            }
            box.addView(thumbV, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
            box.addView(playGlyph(16f), android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            box.addView(membersV, android.widget.FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP).apply { setMargins(dp(3), dp(3), 0, 0) })
            top.addView(box, lpL(dp(112), dp(63)))
            val texts = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
            texts.addView(title, lpL(MATCH, WRAP))
            texts.addView(sub, lpL(MATCH, WRAP))
            texts.addView(state, lpL(MATCH, WRAP).apply { topMargin = dp(2) })
            top.addView(texts, lpL(0, WRAP, 1f))
            view.addView(top, lpL(MATCH, WRAP))
            view.addView(bar, lpL(MATCH, dp(2)).apply { topMargin = dp(8); bottomMargin = dp(2) })
            view.addView(actions, lpL(MATCH, WRAP).apply { topMargin = dp(6) })
            view.setOnLongClickListener { if (selecting) toggleSel(id) else Store.get(id)?.copy()?.let { rowMenu(it) }; true }
            view.setOnClickListener {
                if (selecting) { toggleSel(id); return@setOnClickListener }
                Store.get(id)?.copy()?.let { if (it.state == State.DONE && it.media == Media.OK) open(it) else rowMenu(it) }
            }
        }

        var shownState: String? = null
        private var bound: Job? = null
        private var shownSel = false

        fun select(on: Boolean) {
            if (on == shownSel) return
            shownSel = on
            view.background = ripple(outline(dpf(12), if (on) C.ACCENT else C.OUTLINE))
        }

        fun bind(j: Job) {
            if (j == bound) return // during a download only the running row changes; the others stay untouched
            bound = j
            if (videoId != j.videoId) { videoId = j.videoId; thumbAsked = false }
            title.text = j.title
            membersV.visibility = if (j.members) View.VISIBLE else View.GONE
            sub.text = listOf(j.channel, j.what, Text.duration(j.durationSec)).filter { it.isNotEmpty() }.joinToString(" · ")
            val pct = if (j.total > 0) (j.done * 100 / j.total).toInt().coerceIn(0, 100) else 0
            state.text = Pipeline.status(j) // "Downloading 40%", "Converting 37% · about 1:10 left", "Saved · … · converted in 2:41"
            state.setTextColor(when (j.state) { State.FAILED -> C.RED; State.DONE -> C.LABEL; State.RUNNING -> C.ACCENT; else -> C.LABEL })
            bar.visibility = if (j.state == State.RUNNING && j.phase != Phase.WAIT_SAVE) View.VISIBLE else View.GONE
            if (j.phase == Phase.CONVERT || j.phase == Phase.COPY) bar.set(j.savePct.toLong(), 100) else bar.set(j.done, j.total)
            if (shownState != "${j.state}/${j.media}") {
                shownState = "${j.state}/${j.media}"
                actions.removeAllViews()
                fun add(v: View) { if (actions.childCount > 0) actions.addView(View(this@MainActivity), lpL(dp(8), 1)); actions.addView(v) }
                when (j.state) {
                    State.QUEUED, State.RUNNING -> add(pill(tr("Cancel", "İptal")) { Store.cancel(id) })
                    State.FAILED, State.CANCELED -> {
                        add(pill(tr("Remove", "Kaldır")) { Store.remove(id) })
                        add(pill(tr("Retry", "Yeniden dene"), C.ACCENT, C.ACCENT) { retry(id) })
                    }
                    State.DONE -> when (j.media) {
                        Media.TRASHED -> add(pill(tr("Restore", "Geri yükle"), C.ACCENT, C.ACCENT) { Store.get(id)?.copy()?.let { restoreJobs(listOf(it)) } })
                        Media.GONE -> add(pill(tr("Remove", "Kaldır")) { Store.remove(id) })
                        Media.OK -> {
                            if (j.kind == Kind.AUDIO && hasLocalMusic()) add(pill(tr("Show in Music", "Müzik'te göster")) { showInMusic() })
                            add(pill(tr("Open", "Aç"), C.ACCENT, C.ACCENT) { Store.get(id)?.copy()?.let { open(it) } })
                        }
                    }
                }
            }
        }
    }

    /**
     * Running first, then the queue in download order, then finished ones newest first. At most [LIST_LIMIT] rows
     * until "Show all" is tapped, so a 500-video bulk queue stays light.
     */
    /**
     * Running first, then the queue in download order, then finished ones newest first. A bulk action is ONE batch
     * row ("37/120 · 3 downloading · 1 converting · 2 failed"); tapping it opens its tracks below it. At most
     * [LIST_LIMIT] rows until "Show all" is tapped.
     */
    private fun renderList() {
        val all = Store.snapshot()
        val ordered = all.filter { it.state == State.RUNNING } + all.filter { it.state == State.QUEUED }.asReversed() + all.filter { !it.active }
        val byBatch = ordered.filter { it.batch != 0L }.groupBy { it.batch }
        val keys = ArrayList<Long>()
        val seen = HashSet<Long>()
        for (j in ordered) {
            if (j.batch == 0L) { keys += j.id; continue }
            if (!seen.add(j.batch)) continue
            keys += -j.batch
            if (j.batch in openBatches) byBatch[j.batch]?.forEach { keys += it.id }
        }
        val shown = if (showAll) keys else keys.take(LIST_LIMIT)
        val queued = all.count { it.state == State.QUEUED }
        moreRow.text = tr("Show all ${keys.size} (${keys.size - shown.size} more)", "Tümünü göster: ${keys.size} (${keys.size - shown.size} tane daha)") + if (queued > 0) tr(" · $queued queued", " · $queued sırada") else ""
        moreRow.visibility = if (shown.size < keys.size) View.VISIBLE else View.GONE
        listHeader.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        val jobById = all.associateBy { it.id }
        if (shown != shownKeys) {
            list.removeAllViews()
            val oldRows = HashMap(rows); val oldB = HashMap(batchRows)
            rows.clear(); batchRows.clear()
            for (k in shown) {
                if (k < 0) {
                    val b = oldB[-k] ?: BatchRow(-k)
                    batchRows[-k] = b
                    list.addView(b.view, lpL(MATCH, WRAP).apply { topMargin = dp(8) })
                } else {
                    val r = oldRows[k] ?: Row(k)
                    rows[k] = r
                    val inBatch = (jobById[k]?.batch ?: 0L) != 0L
                    list.addView(r.view, lpL(MATCH, WRAP).apply { topMargin = dp(if (inBatch) 4 else 8); marginStart = if (inBatch) dp(16) else 0 })
                }
            }
            shownKeys = shown
        }
        for (k in shown) {
            if (k < 0) batchRows[-k]?.bind(byBatch[-k].orEmpty()) else jobById[k]?.let { rows[k]?.bind(it); rows[k]?.select(selecting && k in selected) }
        }
        updateSelBar()
        main.post { loadVisibleThumbs() }
    }

    /** One row per bulk action: title, counts, progress, Cancel all / Retry failed / Remove; tap = show the tracks. */
    private inner class BatchRow(val batch: Long) {
        val view = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(outline(dpf(12), C.FAINT))
            setPadding(dp(14), dp(10), dp(10), dp(10))
        }
        private val title = text("", 14f, C.TEXT, bold = true, lines = 2)
        private val stats = text("", 12f, C.ACCENT, lines = 2)
        private val bar = ProgressLine(this@MainActivity)
        private val actions = LinearLayout(this@MainActivity).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        private var last: BatchStats? = null

        init {
            view.addView(title, lpL(MATCH, WRAP))
            view.addView(stats, lpL(MATCH, WRAP).apply { topMargin = dp(2) })
            view.addView(bar, lpL(MATCH, dp(2)).apply { topMargin = dp(8); bottomMargin = dp(2) })
            view.addView(actions, lpL(MATCH, WRAP).apply { topMargin = dp(6) })
            view.setOnClickListener {
                if (selecting) { toggleBatchSel(batch); return@setOnClickListener }
                if (!openBatches.add(batch)) openBatches.remove(batch); shownKeys = emptyList(); renderList()
            }
            view.setOnLongClickListener {
                menu(title.text.toString(), listOf(
                    tr("Erase all files of this batch (to trash)", "Bu toplu işin tüm dosyalarını sil (çöpe)") to { eraseJobs(Store.snapshot().filter { it.batch == batch }) },
                    tr("Cancel all", "Tümünü iptal et") to { Store.cancelBatch(batch) },
                    tr("Retry failed", "Başarısızları yeniden dene") to { retryBatch() },
                    tr("Select", "Seç") to { startSelection(); toggleBatchSel(batch) },
                    tr("Remove from history (keeps the files)", "Geçmişten kaldır (dosyalar kalır)") to { Store.removeBatch(batch) },
                ))
                true
            }
        }

        private fun retryBatch() {
            if (!wifiOk()) return
            val n = Store.retryBatch(batch)
            if (n > 0) DownloadService.start(this@MainActivity)
            toast(if (n > 0) tr("Retrying $n", "$n yeniden deneniyor") else tr("Nothing failed", "Başarısız olan yok"))
        }

        fun bind(jobs: List<Job>) {
            val st = BatchStats.of(jobs)
            title.text = (if (batch in openBatches) "▾ " else "▸ ") + (jobs.firstOrNull()?.batchTitle?.ifEmpty { null } ?: tr("Bulk download", "Toplu indirme"))
            if (st == last) return
            stats.text = st.text
            stats.setTextColor(if (st.active > 0) C.ACCENT else if (st.failed > 0) C.RED else C.LABEL)
            bar.visibility = if (st.active > 0) View.VISIBLE else View.GONE
            bar.set(st.done.toLong(), st.total.toLong())
            if (last == null || (last!!.active > 0) != (st.active > 0) || (last!!.failed > 0) != (st.failed > 0)) {
                actions.removeAllViews()
                fun add(v: View) { if (actions.childCount > 0) actions.addView(View(this@MainActivity), lpL(dp(8), 1)); actions.addView(v) }
                if (st.active > 0) add(pill(tr("Cancel all", "Tümünü iptal et")) { Store.cancelBatch(batch) })
                if (st.failed + st.canceled > 0) add(pill(tr("Retry failed", "Başarısızları yeniden dene"), C.ACCENT, C.ACCENT) { retryBatch() })
                if (st.active == 0) add(pill(tr("Remove", "Kaldır")) { Store.removeBatch(batch) })
            }
            last = st
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // selection in the downloads list (bulk erase / remove / retry)

    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private lateinit var selBar: LinearLayout
    private lateinit var selText: TextView

    private fun startSelection() { selecting = true; selected.clear(); renderList() }
    private fun endSelection() { selecting = false; selected.clear(); renderList() }
    private fun toggleSel(id: Long) { if (!selected.remove(id)) selected += id; if (selected.isEmpty()) endSelection() else renderList() }
    private fun toggleBatchSel(b: Long) {
        val ids = Store.snapshot().filter { it.batch == b }.map { it.id }
        if (ids.all { it in selected }) selected.removeAll(ids.toSet()) else selected.addAll(ids)
        if (selected.isEmpty()) endSelection() else renderList()
    }
    private fun selectAllJobs() { selected.addAll(Store.snapshot().map { it.id }); renderList() }

    private fun updateSelBar() {
        if (!::selBar.isInitialized) return
        selBar.visibility = if (selecting) View.VISIBLE else View.GONE
        listHeader.visibility = if (selecting) View.GONE else listHeader.visibility
        selText.text = tr("${selected.size} selected", "${selected.size} seçili")
    }

    private fun selectionMenu() {
        val js = Store.snapshot().filter { it.id in selected }
        menu(tr("${js.size} selected", "${js.size} seçili"), listOf(
            tr("Erase files (to trash)", "Dosyaları sil (çöpe)") to { eraseJobs(js); endSelection() },
            tr("Restore from trash", "Çöpten geri yükle") to { restoreJobs(js.filter { it.media == Media.TRASHED }); endSelection() },
            tr("Retry failed", "Başarısızları yeniden dene") to { js.filter { it.state == State.FAILED || it.state == State.CANCELED }.forEach { Store.retry(it.id) }; DownloadService.start(this); endSelection() },
            tr("Cancel", "İptal") to { js.filter { it.active }.forEach { Store.cancel(it.id) }; endSelection() },
            tr("Remove from history (keeps the files)", "Geçmişten kaldır (dosyalar kalır)") to { js.filter { !it.active }.forEach { Store.remove(it.id) }; endSelection() },
        ))
    }

    // ---------------------------------------------------------------------------------------------------------
    // erase = MediaStore trash (restorable; Android deletes it for good after ~30 days)

    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private var undoAction: (() -> Unit)? = null
    private val hideUndoTask = Runnable { hideUndo() }

    fun showUndo(msg: String, undo: () -> Unit) {
        undoText.text = msg; undoAction = undo
        undoBar.visibility = View.VISIBLE
        main.removeCallbacks(hideUndoTask); main.postDelayed(hideUndoTask, 8000)
    }

    private fun hideUndo() { undoBar.visibility = View.GONE; undoAction = null; main.removeCallbacks(hideUndoTask) }

    /** Erase saved files: trashed straight away (we own them, no dialog); refused ones go through one system dialog. */
    fun eraseJobs(all: List<Job>) {
        val js = all.filter { it.state == State.DONE && it.uri != null && it.media == Media.OK && !it.art }
        if (js.isEmpty()) { toast(tr("Nothing to erase", "Silinecek bir şey yok")); return }
        io.execute {
            val refused = MediaTrash.setTrashed(contentResolver, js.map { Uri.parse(it.uri) }, true).map { it.toString() }.toSet()
            val ok = js.filter { it.uri !in refused }
            Store.applyFixes(ok.map { Reconcile.Fix(it.id, Media.TRASHED, it.relPath, it.fileName) })
            main.post {
                if (isDestroyed) return@post
                if (ok.isNotEmpty()) showUndo(tr("Erased ${ok.size} file${if (ok.size == 1) "" else "s"} (in trash for 30 days)", "${ok.size} dosya silindi (30 gün çöpte kalır)")) { restoreJobs(ok) }
                if (refused.isNotEmpty()) systemRequest(refused.map { Uri.parse(it) }, REQ_TRASH)
                refreshTrashSummary()
            }
        }
    }

    fun restoreJobs(js: List<Job>) {
        if (js.isEmpty()) return
        io.execute {
            val refused = MediaTrash.setTrashed(contentResolver, js.mapNotNull { it.uri?.let(Uri::parse) }, false)
            main.post { if (!isDestroyed) { if (refused.isNotEmpty()) systemRequest(refused, REQ_UNTRASH); sync() } }
        }
    }

    private fun deleteNowJobs(js: List<Job>) {
        android.app.AlertDialog.Builder(this).setTitle(tr("Delete now?", "Hemen silinsin mi?"))
            .setMessage(tr("${js.size} file(s) will be deleted for good. This can't be undone.", "${js.size} dosya kalıcı olarak silinecek. Bu geri alınamaz."))
            .setPositiveButton(tr("Delete", "Sil")) { _, _ ->
                io.execute {
                    val refused = MediaTrash.delete(contentResolver, js.mapNotNull { it.uri?.let(Uri::parse) })
                    main.post { if (!isDestroyed) { if (refused.isNotEmpty()) systemRequest(refused, REQ_DELETE); sync() } }
                }
            }.setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    /** Files Android won't let us change directly (not ours after a reinstall): one system dialog for all. */
    private fun systemRequest(uris: List<Uri>, req: Int) {
        if (uris.isEmpty()) return
        try {
            val pi = when (req) {
                REQ_TRASH -> android.provider.MediaStore.createTrashRequest(contentResolver, uris, true)
                REQ_UNTRASH -> android.provider.MediaStore.createTrashRequest(contentResolver, uris, false)
                else -> android.provider.MediaStore.createDeleteRequest(contentResolver, uris)
            }
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, req, null, 0, 0, 0)
        } catch (e: Exception) { toast(tr("Android refused: ${e.message}", "Android reddetti: ${e.message}")) }
    }

    private fun eraseCached(m: CacheMeta) {
        io.execute {
            if (m.published) {
                val u = Uri.parse(m.publicUri)
                val refused = MediaTrash.setTrashed(contentResolver, listOf(u), true)
                Store.cache.forget(m.kind, m.key)
                main.post {
                    if (isDestroyed) return@post
                    if (refused.isNotEmpty()) systemRequest(refused, REQ_TRASH)
                    else showUndo(tr("Erased \"${m.title}\" (in trash for 30 days)", "\"${m.title}\" silindi (30 gün çöpte kalır)")) {
                        io.execute { MediaTrash.setTrashed(contentResolver, listOf(u), false); main.post { toast(tr("Restored to ${m.relPath ?: "Cached"}", "Geri yüklendi: ${m.relPath ?: "Cached"}")) } }
                    }
                    refreshCached(); refreshTrashSummary()
                }
            } else {
                Store.cache.remove(m.kind, m.key) // a partial private copy: nothing public to trash
                main.post { refreshCached() }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // sync with MediaStore + trash

    private var trashCount = 0
    private var trashBytes = 0L

    /**
     * On start/resume, off the main thread: (1) when free storage is below 3 GB, delete our oldest trashed files for
     * good until it isn't (before any cache eviction); (2) one query by id for every saved file: trashed / restored /
     * deleted / moved by any app (Plain Music too) updates the history and the ✓ marks; (3) the same for the public
     * Cached entries. Nothing runs in the background.
     */
    private fun sync() {
        io.execute {
            val cr = contentResolver
            var freed = 0L
            try {
                val free = Store.partsDir.usableSpace
                val victims = Purge.pick(MediaTrash.trashed(cr), free)
                if (victims.isNotEmpty()) {
                    val refused = MediaTrash.delete(cr, victims.map { MediaTrash.uriOf(it) }).toSet()
                    freed = victims.filter { MediaTrash.uriOf(it) !in refused }.sumOf { it.size }
                }
            } catch (_: Exception) {}
            val fixes = try {
                val jobs = Store.snapshot().filter { it.state == State.DONE && it.uri != null }
                MediaTrash.rows(cr, jobs.mapNotNull { Reconcile.idOf(it.uri) })?.let { Reconcile.plan(jobs, it) } ?: emptyList()
            } catch (_: Exception) { emptyList() }
            Store.applyFixes(fixes)
            try {
                val pub = CacheKind.values().flatMap { Store.cache.list(it) }.filter { it.published }
                val rows = MediaTrash.rows(cr, pub.mapNotNull { Reconcile.idOf(it.publicUri) }) ?: emptyMap()
                if (pub.isNotEmpty() && rows.isNotEmpty()) for (m in pub) {
                    val r = rows[Reconcile.idOf(m.publicUri)]
                    if (r == null || r.trashed) Store.cache.forget(m.kind, m.key) // trashed / deleted elsewhere: leaves the Cached folder
                    else if (r.relPath != m.relPath || r.name != m.fileName) { m.relPath = r.relPath; m.fileName = r.name; Store.cache.save(m) }
                }
            } catch (_: Exception) {}
            val t = try { MediaTrash.trashed(cr) } catch (_: Exception) { emptyList() }
            main.post {
                if (isDestroyed) return@post
                trashCount = t.size; trashBytes = t.sumOf { it.size }
                if (freed > 0) toast(tr("Freed ${Text.size(freed)} from trash", "Çöpten ${Text.size(freed)} boşaltıldı"))
                refreshCached()
                if (!Store.artOffered && Store.artQuality != ArtQuality.NONE) offerArt(force = false) // once, on the first start of 1.9
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // cover art

    private fun artMenu() {
        val before = Store.artQuality
        val all = ArtQuality.values().toList()
        Sheet.choose(this, tr("Cover art in audio files", "Ses dosyalarında kapak resmi"), all.map { it.label }, all.indexOf(before), C.ACCENT) { i ->
            val q = all[i]
            Store.artQuality = q
            if (before == ArtQuality.NONE && q != ArtQuality.NONE) offerArt(force = true)
        }
    }

    /** Check our audio files for missing art (reads each header), then offer the backfill batch. */
    private fun offerArt(force: Boolean) {
        if (!force) Store.artOffered = true
        if (force) toast(tr("Checking your files…", "Dosyalarınız denetleniyor…"))
        io.execute {
            val (missing, unreadable) = try { Art.scanMissing(this) } catch (_: Exception) { emptyList<Art.Missing>() to 0 }
            main.post {
                if (isDestroyed) return@post
                if (missing.isEmpty()) {
                    if (force) message(tr("Cover art", "Kapak resmi"), tr("Every saved song has cover art.", "Kaydedilen her şarkının kapak resmi var.") + if (unreadable > 0) tr(" ($unreadable file(s) couldn't be read: not Plain Youtube's.)", " ($unreadable dosya okunamadı: Plain Youtube'a ait değil.)") else "")
                    return@post
                }
                val q = Store.artQuality.takeIf { it != ArtQuality.NONE }?.label ?: tr("Low", "Düşük")
                android.app.AlertDialog.Builder(this).setTitle(tr("Cover art", "Kapak resmi"))
                    .setMessage(tr("${missing.size} song(s) have no cover art. Add it now? It downloads a small picture per song " +
                        "($q) and writes it into the files, as a batch in the downloads list (Wi-Fi only applies).",
                        "${missing.size} şarkının kapak resmi yok. Şimdi eklensin mi? Her şarkı için küçük bir resim indirilip " +
                        "($q) dosyalara yazılır; indirilenler listesinde toplu iş olarak görünür (Yalnızca Wi-Fi geçerlidir).") +
                        if (unreadable > 0) tr("\n\n$unreadable file(s) can't be changed (not Plain Youtube's) and are skipped.", "\n\n$unreadable dosya değiştirilemiyor (Plain Youtube'a ait değil) ve atlanacak.") else "")
                    .setPositiveButton(tr("Add now", "Şimdi ekle")) { _, _ -> enqueueArt(missing) }
                    .setNegativeButton(tr("Not now", "Şimdi değil"), null).show()
            }
        }
    }

    private fun enqueueArt(missing: List<Art.Missing>) {
        if (!wifiOk()) return
        val base = Store.newId()
        Store.addAll(missing.mapIndexed { i, m ->
            Job(id = base + i, videoId = m.videoId, title = m.title, channel = m.channel, durationSec = 0, kind = Kind.AUDIO,
                audioFmt = AudioFmt.M4A, audioQ = AudioQ.HIGH, height = 0, codec = VCodec.AVC, uri = m.uri, fileName = m.fileName,
                album = m.album, art = true, batch = if (missing.size > 1) base else 0L, batchTitle = tr("Add cover art", "Kapak resmi ekle"))
        })
        DownloadService.start(this)
        toast(tr("Adding cover art to ${missing.size} song(s)", "${missing.size} şarkıya kapak resmi ekleniyor"))
    }

    private fun refreshTrashSummary() {
        io.execute {
            val t = try { MediaTrash.trashed(contentResolver) } catch (_: Exception) { emptyList() }
            main.post { trashCount = t.size; trashBytes = t.sumOf { it.size } }
        }
    }

    /** ⋮ → Trash: our trashed files with days left, Restore / Delete now each, Empty trash. */
    private fun trashView() {
        io.execute {
            val t = try { MediaTrash.trashed(contentResolver) } catch (_: Exception) { emptyList() }
            main.post { if (!isDestroyed) showTrash(t) }
        }
    }

    private fun showTrash(t: List<MediaRow>) {
        trashCount = t.size; trashBytes = t.sumOf { it.size }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(12), dp(8)) }
        val scroll = ScrollView(this).apply { addView(col) }
        val dlg = android.app.AlertDialog.Builder(this).setTitle(tr("Trash (${t.size} · ${Text.size(trashBytes)})", "Çöp kutusu (${t.size} · ${Text.size(trashBytes)})")).setView(scroll)
            .setNegativeButton(tr("Close", "Kapat"), null)
            .apply {
                if (t.isNotEmpty()) setPositiveButton(tr("Empty trash", "Çöpü boşalt")) { _, _ ->
                    android.app.AlertDialog.Builder(this@MainActivity).setTitle(tr("Empty trash?", "Çöp boşaltılsın mı?"))
                        .setMessage(tr("${t.size} file(s), ${Text.size(trashBytes)}, will be deleted for good.", "${t.size} dosya (${Text.size(trashBytes)}) kalıcı olarak silinecek."))
                        .setPositiveButton(tr("Delete all", "Tümünü sil")) { _, _ ->
                            io.execute {
                                val refused = MediaTrash.delete(contentResolver, t.map { MediaTrash.uriOf(it) })
                                main.post { if (refused.isNotEmpty()) systemRequest(refused, REQ_DELETE); sync() }
                            }
                        }.setNegativeButton(tr("Cancel", "İptal"), null).show()
                }
            }.create()
        col.addView(text(if (t.isEmpty()) tr("The trash is empty.", "Çöp kutusu boş.") else tr("Erased files stay here until Android deletes them (about 30 days after erasing), or until space runs low (below 3 GB free, oldest first).", "Silinen dosyalar, Android onları silene kadar (silindikten yaklaşık 30 gün sonra) ya da yer azalana kadar (3 GB'tan az boş alan, en eskiden başlayarak) burada kalır."),
            12f, C.LABEL, lines = 4).apply { setPadding(0, 0, 0, dp(8)) })
        val now = System.currentTimeMillis()
        for (r in t) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
            row.addView(text(r.name, 14f, C.TEXT, bold = true, lines = 2))
            row.addView(text(tr("${r.relPath} · ${Text.size(r.size)} · ${Purge.daysLeft(r.expires, now)} days left", "${r.relPath} · ${Text.size(r.size)} · ${Purge.daysLeft(r.expires, now)} gün kaldı"), 12f, C.LABEL, lines = 2))
            val acts = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(4), 0, 0) }
            acts.addView(pill(tr("Delete now", "Hemen sil")) {
                io.execute { val refused = MediaTrash.delete(contentResolver, listOf(MediaTrash.uriOf(r))); main.post { if (refused.isNotEmpty()) systemRequest(refused, REQ_DELETE); dlg.dismiss(); sync() } }
            })
            acts.addView(View(this), lpL(dp(8), 1))
            acts.addView(pill(tr("Restore", "Geri yükle"), C.ACCENT, C.ACCENT) {
                io.execute { val refused = MediaTrash.setTrashed(contentResolver, listOf(MediaTrash.uriOf(r)), false); main.post { if (refused.isNotEmpty()) systemRequest(refused, REQ_UNTRASH); dlg.dismiss(); sync() } }
            })
            row.addView(acts, lpL(MATCH, WRAP))
            col.addView(row, lpL(MATCH, WRAP))
        }
        dlg.show()
    }

    /** Thumbnails only for rows that are on screen now (scrolling asks again). Wi-Fi only applies. */
    private fun loadVisibleThumbs() {
        if (!::scroll.isInitialized || tab != 0) return
        for (r in rows.values) {
            if (r.thumbAsked || r.videoId.isEmpty() || !r.view.isShown) continue
            if (r.view.getLocalVisibleRect(thumbRect)) { r.thumbAsked = true; dlThumbs.load(r.thumbV, r.videoId, dp(112)) }
        }
        for ((iv, id) in cacheRows) {
            if (iv.tag == null && iv.isShown && iv.getLocalVisibleRect(thumbRect)) dlThumbs.load(iv, id, dp(112))
        }
    }

    private fun playGlyph(sp: Float) = text("▶", sp, C.TEXT).apply {
        gravity = Gravity.CENTER
        background = oval(0x99000000.toInt())
    }

    /** Thumbnail tap in the list: a saved file plays from MediaStore (offline); anything else streams. */
    private fun playJob(j: Job) {
        if (j.state == State.DONE && j.uri != null) PlayerActivity.local(this, j)
        else PlayerActivity.stream(this, j.videoId, j.title, j.channel, mode = if (j.kind == Kind.AUDIO) PlayMode.LISTEN else null)
    }

    private fun playInfo(v: VideoInfo) {
        PlayerActivity.stream(this, v.id, v.title, Naming.channel(v.author), musicLink = input.text.contains("music.youtube.com"))
    }

    // ---------------------------------------------------------------------------------------------------------
    // the temporary "Cached" folder: what was streamed in the player and isn't complete yet (3.3: complete = saved)

    private fun refreshCached() {
        io.execute {
            // a public cached file deleted elsewhere (file manager, LocalMusic) drops out of the index
            fun live(k: CacheKind) = Store.cache.list(k).filter { m ->
                if (m.published && !CachePublisher.exists(this, m)) { Store.cache.forget(k, m.key); false } else true
            }.sortedByDescending { it.lastUsed }
            val v = live(CacheKind.VIDEO)
            val m = live(CacheKind.MUSIC)
            main.post { if (!isDestroyed) { renderCached(v, m); flushTrash() } }
        }
    }

    private fun renderCached(video: List<CacheMeta>, music: List<CacheMeta>) {
        val n = video.size + music.size
        cachedHeader.visibility = if (n == 0) View.GONE else View.VISIBLE
        cachedHeader.text = (if (cachedOpen) "▾ " else "▸ ") + tr("Partly cached · videos ${video.size} (${Text.size(video.sumOf { it.size })}) · ", "Kısmen önbellekte · videolar ${video.size} (${Text.size(video.sumOf { it.size })}) · ") +
            tr("music ${music.size} (${Text.size(music.sumOf { it.size })})", "müzik ${music.size} (${Text.size(music.sumOf { it.size })})")
        cachedBox.removeAllViews()
        cacheRows.clear()
        cachedBox.visibility = if (cachedOpen && n > 0) View.VISIBLE else View.GONE
        if (!cachedOpen || n == 0) return
        cachedBox.addView(text(tr("Unfinished streams, kept to resume — cleared when space runs low (keeps 3 GB free) or past the limits in ⋮. " +
            "Once a video or song is fully played it is saved with your downloads (one copy).",
            "Yarım kalan akışlar, devam etmek için tutulur: yer azalınca (3 GB boş tutulur) ya da ⋮ menüsündeki sınırlar aşılınca temizlenir. " +
            "Bir video ya da şarkı tamamen oynatılınca indirilenlerinizle birlikte kaydedilir (tek kopya)."), 12f, C.LABEL, lines = 3)
            .apply { setPadding(0, 0, 0, dp(4)) })
        for ((kind, list) in listOf(CacheKind.VIDEO to video, CacheKind.MUSIC to music)) {
            if (list.isEmpty()) continue
            val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, dp(2)) }
            head.addView(text((if (kind == CacheKind.VIDEO) tr("Videos", "Videolar") else tr("Music", "Müzik")) + " (${list.size} · ${Text.size(list.sumOf { it.size })})",
                13f, C.DIM, bold = true), lpL(0, WRAP, 1f))
            head.addView(text(tr("Clear", "Temizle"), 13f, C.LABEL).apply {
                setPadding(dp(8), dp(6), dp(4), dp(6))
                setOnClickListener { io.execute { Store.cache.clear(kind); main.post { refreshCached() } } }
            })
            cachedBox.addView(head, lpL(MATCH, WRAP))
            for (m in list) cachedBox.addView(cacheRow(m), lpL(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        main.post { loadVisibleThumbs() }
    }

    private fun cacheRow(m: CacheMeta): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ripple(outline(dpf(12)))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setOnClickListener { play(m) }
            setOnLongClickListener { cacheMenu(m); true }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val box = android.widget.FrameLayout(this)
        val iv = RoundImage(this)
        box.addView(iv, android.widget.FrameLayout.LayoutParams(MATCH, MATCH))
        box.addView(playGlyph(16f), android.widget.FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        top.addView(box, lpL(dp(112), dp(63)))
        cacheRows += iv to m.videoId
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        texts.addView(text(m.title.ifEmpty { m.videoId }, 14f, C.TEXT, bold = true, lines = 2))
        texts.addView(text(listOf(m.channel, if (m.kind == CacheKind.VIDEO) "${m.quality}p" else if (m.ext == "webm") "Opus" else "M4A",
            Text.duration(m.durationSec)).filter { it.isNotEmpty() }.joinToString(" · "), 12f, C.DIM))
        texts.addView(text(if (m.published) tr("Complete", "Tamamlandı") + " · ${Text.size(m.size)} · ${m.relPath ?: ""}" else if (m.complete) tr("Complete · ${Text.size(m.size)} · plays offline", "Tamamlandı · ${Text.size(m.size)} · çevrimdışı oynar")
            else tr("${(m.fraction * 100).toInt()}% cached · ${Text.size(m.size)}", "%${(m.fraction * 100).toInt()} önbellekte · ${Text.size(m.size)}"),
            12f, if (m.complete) C.GREEN else C.LABEL))
        top.addView(texts, lpL(0, WRAP, 1f))
        row.addView(top, lpL(MATCH, WRAP))
        val actions = LinearLayout(this).apply { gravity = Gravity.END; setPadding(0, dp(8), 0, 0) }
        actions.addView(pill(if (m.published) tr("Erase", "Sil") else tr("Remove", "Kaldır")) { eraseCached(m) })
        actions.addView(View(this), lpL(dp(8), 1))
        actions.addView(pill(if (m.complete) tr("Save", "Kaydet") else tr("Save (download)", "Kaydet (indir)"), C.ACCENT, C.ACCENT) { saveCached(m) })
        row.addView(actions, lpL(MATCH, WRAP))
        return row
    }

    private fun play(m: CacheMeta) =
        PlayerActivity.stream(this, m.videoId, m.title, m.channel, mode = if (m.kind == CacheKind.MUSIC) PlayMode.LISTEN else PlayMode.WATCH)

    private fun saveCached(m: CacheMeta) {
        if (!m.complete && !wifiOk()) return
        toast(tr("Saving…", "Kaydediliyor…"))
        io.execute {
            val r = try { CachePublisher.save(this, m) } catch (e: Exception) { CachePublisher.Result(false, tr("Couldn't save: ${e.message}", "Kaydedilemedi: ${e.message}")) }
            main.post { if (!isDestroyed) { message(tr("Save", "Kaydet"), r.message); refreshCached() } }
        }
    }

    /** Public cached files Android wouldn't let us delete (after a reinstall they aren't ours): ask once via the system. */
    private fun flushTrash() {
        val uris = Store.takeTrashPending().map { Uri.parse(it) }
        if (uris.isEmpty()) return
        try {
            val pi = android.provider.MediaStore.createTrashRequest(contentResolver, uris, true)
            @Suppress("DEPRECATION")
            startIntentSenderForResult(pi.intentSender, 8, null, 0, 0, 0)
        } catch (_: Exception) {}
    }

    private fun regionMenu() {
        val regions = listOf("US", "DE", "GB", "NL", "FR", "JP", "CA") // gl codes, not translated
        Sheet.choose(this, tr("Region for blocked videos", "Engelli videolar için bölge"), listOf(tr("Off (show the block)", "Kapalı (engeli göster)")) + regions,
            if (Store.altRegion.isEmpty()) 0 else regions.indexOf(Store.altRegion) + 1, C.ACCENT,
            tr("A video blocked in your country is asked for again as if from this region", "Ülkenizde engelli bir video bu bölgeden istenmiş gibi yeniden istenir")) { i ->
            Store.altRegion = if (i == 0) "" else regions[i - 1]
        }
    }

    private fun cacheLimits() {
        val gbs = listOf(2, 5, 10, 20, 50, 100)
        Sheet(this, C.ACCENT).header(tr("Cache limits", "Önbellek sınırları"), tr("The oldest cached files go first when a limit is reached", "Sınıra ulaşınca en eski önbellek dosyaları önce silinir"))
            .item(Ic.VIDEO, tr("Video cache", "Video önbelleği"), value = "${Store.videoCapGb} GB", more = true) {
                Sheet.choose(this, tr("Video cache limit", "Video önbellek sınırı"), gbs.map { "$it GB" }, gbs.indexOf(Store.videoCapGb), C.ACCENT) { Store.videoCapGb = gbs[it]; trimCaches() }
            }
            .item(Ic.QUEUE, tr("Music cache", "Müzik önbelleği"), value = "${Store.musicCapGb} GB", more = true) {
                Sheet.choose(this, tr("Music cache limit", "Müzik önbellek sınırı"), gbs.map { "$it GB" }, gbs.indexOf(Store.musicCapGb), C.ACCENT) { Store.musicCapGb = gbs[it]; trimCaches() }
            }
            .show()
    }

    private fun trimCaches() {
        io.execute {
            for (k in CacheKind.values()) Store.cache.trim(k, Store.cap(k), null)
            main.post { refreshCached() }
        }
    }

    private fun retry(id: Long) {
        if (Store.wifiOnly) {
            try { Net.network(this, true) } catch (e: Exception) { toast(tr("Not on Wi-Fi (Wi-Fi only is on)", "Wi-Fi bağlı değil (Yalnızca Wi-Fi açık)")); return }
        }
        Store.retry(id)
        DownloadService.start(this)
    }

    /**
     * Press & hold on a downloads-list row: Visit channel, Watch / Listen (a finished item plays its saved file),
     * Download…, Add to play queue, Add to list…, then Open / Open in Music, Retry / Cancel, Remove from history.
     */
    private fun rowMenu(j: Job) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val item = Item(IKind.VIDEO, j.videoId, j.title, j.channel, null, j.durationSec, thumbId = j.videoId)
        val saved = j.state == State.DONE && j.uri != null && j.media == Media.OK
        items += tr("Visit channel", "Kanala git") to { Hold.visitChannel(this, j.videoId, null, j.channel) }
        if (saved) items += (if (j.kind == Kind.AUDIO) tr("Listen (saved file)", "Dinle (kayıtlı dosya)") else tr("Watch (saved file)", "İzle (kayıtlı dosya)")) to { PlayerActivity.local(this, j) }
        items += tr("Watch", "İzle") to { PlayerActivity.stream(this, j.videoId, j.title, j.channel, PlayMode.WATCH, album = j.album) }
        items += tr("Listen", "Dinle") to { PlayerActivity.stream(this, j.videoId, j.title, j.channel, PlayMode.LISTEN, album = j.album) }
        items += tr("Download…", "İndir…") to { openVideo(j.videoId) }
        items += tr("Add to play queue", "Çalma sırasına ekle") to {
            Hold.enqueue(this, listOf(if (saved) QEntry(j.videoId, j.title, j.channel, localUri = j.uri, localAudio = j.kind == Kind.AUDIO)
                else PlayQueue.of(item, if (j.kind == Kind.AUDIO) PlayMode.LISTEN else null, j.album)))
        }
        items += tr("Add to list…", "Listeye ekle…") to { Hold.addToList(this, listOf(item)) }
        if (saved && j.media == Media.OK) items += tr("Erase (to trash)", "Sil (çöpe)") to { eraseJobs(listOf(j)) }
        if (j.media == Media.TRASHED) {
            items += tr("Restore from trash", "Çöpten geri yükle") to { restoreJobs(listOf(j)) }
            items += tr("Delete now (can't be undone)", "Hemen sil (geri alınamaz)") to { deleteNowJobs(listOf(j)) }
        }
        items += tr("Select", "Seç") to { startSelection(); toggleSel(j.id) }
        if (saved) items += tr("Open in another app", "Başka uygulamada aç") to { open(j) }
        if (saved && j.kind == Kind.AUDIO && hasLocalMusic()) items += tr("Open in Music", "Müzik'te aç") to { showInMusic() }
        if (j.active) items += tr("Cancel", "İptal") to { Store.cancel(j.id) }
        if (j.state == State.FAILED || j.state == State.CANCELED) items += tr("Retry", "Yeniden dene") to { retry(j.id) }
        if (!j.active) items += tr("Remove from history (keeps the file)", "Geçmişten kaldır (dosya kalır)") to { Store.remove(j.id) }
        menu(j.title, items)
    }

    /** Press & hold on a Cached-folder row. */
    private fun cacheMenu(m: CacheMeta) {
        val item = Item(IKind.VIDEO, m.videoId, m.title, m.channel, null, m.durationSec, thumbId = m.videoId)
        val mode = if (m.kind == CacheKind.MUSIC) PlayMode.LISTEN else PlayMode.WATCH
        menu(m.title, listOf(
            tr("Visit channel", "Kanala git") to { Hold.visitChannel(this, m.videoId, null, m.channel) },
            tr("Play (cached)", "Oynat (önbellek)") to { play(m) },
            tr("Watch", "İzle") to { PlayerActivity.stream(this, m.videoId, m.title, m.channel, PlayMode.WATCH, album = m.album) },
            tr("Listen", "Dinle") to { PlayerActivity.stream(this, m.videoId, m.title, m.channel, PlayMode.LISTEN, album = m.album) },
            tr("Download…", "İndir…") to { openVideo(m.videoId) },
            tr("Add to play queue", "Çalma sırasına ekle") to { Hold.enqueue(this, listOf(PlayQueue.of(item, mode, m.album))) },
            tr("Add to list…", "Listeye ekle…") to { Hold.addToList(this, listOf(item)) },
            (if (m.complete) tr("Save", "Kaydet") else tr("Save (download)", "Kaydet (indir)")) to { saveCached(m) },
            (if (m.published) tr("Erase (to trash)", "Sil (çöpe)") else tr("Remove", "Kaldır")) to { eraseCached(m) },
        ))
    }

    private fun open(j: Job) {
        val uri = j.uri ?: return
        val mime = if (j.kind == Kind.AUDIO) j.audioFmt.mime else "video/mp4"
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) {
            toast(tr("No app can open this file (it may have been moved or deleted)", "Bu dosyayı açabilecek uygulama yok (taşınmış ya da silinmiş olabilir)"))
        }
    }

    private fun hasLocalMusic() = true // the Music tab of Plain Media

    private fun showInMusic() = Hub.open(this, "music")
}
