package app.localmusic

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate

sealed class Row
class HeaderRow(val title: String, val sub: String?, val actions: List<Pair<String, () -> Unit>>, val accent: Int = 0) : Row()
class SectionRow(val text: String) : Row()
class GroupRow(val title: String, val sub: String, val artId: Long, val onClick: () -> Unit, val onLong: (() -> Unit)? = null, val round: Boolean = false, val artSongs: List<Song>? = null, val mosaic: Boolean = false) : Row()
/** Up to three album cards side by side (compact album grid). */
class AlbumPairRow(val albums: List<Group>) : Row()
class EmptyRow(val text: String, val action: Pair<String, () -> Unit>? = null) : Row()
/** Home: a section title with an optional play-all button and "See all". */
class ShelfHeaderRow(val title: String, val sub: String?, val onPlay: (() -> Unit)?, val onSeeAll: (() -> Unit)?) : Row()
/** Home: one horizontally scrolling shelf of cards. [key] keeps its scroll position across re-renders. */
class ShelfRow(val key: String, val cards: List<Card>) : Row()
class Card(val title: String, val sub: String, val artSongs: List<Song>, val onClick: () -> Unit, val onLong: (() -> Unit)? = null, val round: Boolean = false, val mosaic: Boolean = false)
class SongRow(
    val song: Song,
    val list: List<Song>,     // what plays when tapped
    val index: Int,           // index in list
    val mode: Mode = Mode.NORMAL,
    val playlist: Playlist? = null,
    val inAlbum: Boolean = false, // album page: track number instead of art, artist only
) : Row() {
    enum class Mode { NORMAL, PLAYLIST_EDIT, QUEUE }
}

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var list: ListView
    private val adapter = RowAdapter()
    private lateinit var search: EditText
    private lateinit var back: ImageView
    private lateinit var menuBtn: ImageView
    private lateinit var titleView: TextView
    private val tabViews = ArrayList<TextView>()
    private var tab = 0
    private var seg = 0 // Library: Songs / Artists / Albums / Genres / Folders
    private val segViews = ArrayList<TextView>()
    private lateinit var segBar: View
    private var shownHistory = -1 // Store.historyVersion the Home rows were built with
    val eraser = Eraser(this)
    private var selection: Selection? = null
    private lateinit var selBar: View
    private lateinit var selCount: TextView
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private var undoAction: (() -> Unit)? = null
    private val hideUndo = Runnable { undoBar.visibility = View.GONE; undoAction = null }
    private lateinit var spaceBar: TextView
    private var spaceChecked = false
    private class Drill(val title: String, val rows: () -> List<Row>, var scroll: Int = 0, var scrollTop: Int = 0)
    private val drill = ArrayList<Drill>()
    private val tabScroll = HashMap<Int, Pair<Int, Int>>()
    private var query = ""
    private var editPlaylist: Playlist? = null

    private lateinit var mini: LinearLayout
    private lateinit var miniArt: ArtView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniPlay: ImageView
    private lateinit var miniProgress: ProgressLine
    private var miniFor = Long.MIN_VALUE

    var nowPlaying: NowPlayingView? = null
    private val overlays = ArrayList<View>()
    var organizer: OrganizerView? = null
    var trash: TrashView? = null

    private var resumed = false
    /** Between onStart and onStop: the only time a video picture may exist. */
    var started = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed || !Player.playing) return
            tick()
            handler.postDelayed(this, 500)
        }
    }
    private val onPlayer: () -> Unit = { onPlayerChanged() }

    companion object {
        // getters, not vals: built in the current language each time
        val TABS get() = listOf(tr("Home", "Ana sayfa"), tr("Library", "Kitaplık"), tr("Videos", "Videolar"), tr("Playlists", "Çalma listeleri"))
        val SEGMENTS get() = listOf(tr("Songs", "Şarkılar"), tr("Artists", "Sanatçılar"), tr("Albums", "Albümler"), tr("Genres", "Türler"), tr("Folders", "Klasörler"))
        const val HOME = 0; const val LIBRARY = 1; const val VIDEOS = 2; const val PLAYLISTS = 3
        const val SHELF_MAX = 15
        const val REQ_PERMS = 1
        const val REQ_VIDEO = 2
    }

    // --- lifecycle ---------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        if (Hub.gate(this, "music")) { finish(); return }  // old LocalMusic's playlists first
        Player.init(this)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC // hardware keys = media volume anywhere in the app
        tab = Store.prefs.getInt("tab4", 0).coerceIn(0, TABS.size - 1)
        seg = Store.prefs.getInt("libSeg", 0).coerceIn(0, SEGMENTS.size - 1)
        buildUi()
        if (!Library.hasPermission(this)) {
            val perms = mutableListOf(Library.PERMISSION)
            // Plain Youtube's videos (optional: denied = audio only). On 30-32 the storage permission covers them.
            if (Build.VERSION.SDK_INT >= 33) perms += listOf(Library.VIDEO_PERMISSION, Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), REQ_PERMS)
        }
        if (Library.index.byId.isNotEmpty()) render()
        debugCommand(intent)
        if (savedInstanceState == null) openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        debugCommand(intent)
        openFromIntent(intent)
    }

    /** "Open with LocalMusic" from a file manager, WhatsApp, etc.: play the audio file once. */
    private fun openFromIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_VIEW) return
        val uri = i.data ?: return
        if (uri.scheme != "content" && uri.scheme != "file") return
        i.action = null // handled
        val app = applicationContext
        Thread {
            if (Library.index.byId.isEmpty()) Library.load(app) // so a library song opened from outside is recognised
            val song = try { External.describe(app, uri) } catch (e: Exception) { null }
            Player.dlog("open $uri -> ${song?.title} external=${song?.externalUri != null}")
            runOnUiThread {
                if (song == null) { toast(tr("Can't open this file", "Bu dosya açılamıyor")); return@runOnUiThread }
                if (Player.playExternal(song)) openNowPlaying() else toast(tr("Can't play this file", "Bu dosya oynatılamıyor"))
            }
        }.start()
    }

    /** Debug builds only: `am start -n app.localmusic.dev/app.localmusic.MainActivity --es cmd play|pause|next|seek:MS|state|plan`. */
    private fun debugCommand(i: Intent?) {
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val cmd = i?.getStringExtra("cmd") ?: return
        i.removeExtra("cmd")
        val app = applicationContext
        Thread {
            if (Library.index.byId.isEmpty()) Library.load(app)
            runOnUiThread {
                when {
                    cmd == "play" -> Player.play()
                    cmd == "pause" -> Player.pause()
                    cmd == "next" -> Player.next()
                    cmd == "shuffle" -> Player.toggleShuffle()
                    cmd == "fwd" -> Player.seekBy(SeekStep.MS)
                    cmd == "back" -> Player.seekBy(-SeekStep.MS)
                    cmd.startsWith("speed:") -> Player.setSpeed(cmd.substring(6).toFloatOrNull() ?: 1f)
                    cmd.startsWith("seek:") -> Player.seekTo(cmd.substring(5).toLong())
                    cmd == "stats" -> Library.index.let { lib ->
                        android.util.Log.i("LocalMusic", "stats artists=${lib.artists.size} albums=${lib.albums.size}")
                        lib.artists.sortedByDescending { it.count }.take(12).forEach { a ->
                            android.util.Log.i("LocalMusic", "artist ${a.title}: ${a.stats} | albums: " + lib.albumsOf(a).joinToString { "${it.title} (${it.count}, ${it.artist})" })
                        }
                        lib.albums.filter { it.artist.contains("&") || it.artist.startsWith("Various") }.forEach { al ->
                            android.util.Log.i("LocalMusic", "merged album ${al.title}: ${al.count} songs, ${al.artist}")
                        }
                    }
                    cmd == "plan" -> Thread {
                        val s = Organizer.scan(app) { android.util.Log.i("LocalMusic", it) }
                        val p = Plan.build(s.files)
                        val text = Organizer.report(app, p, null, emptySet(), false).replace("LocalMusic organiser report", "DRY RUN PLAN") +
                            "\n== Planned moves\n" + p.moves.joinToString("\n") { "  ${it.file.path} -> ${it.toPath}" } +
                            "\n\n== Planned duplicate removals\n" + p.removals.joinToString("\n") { "  ${it.file.path} (${Organizer.describe(it.file)})\n     keep ${it.keep.path} (${Organizer.describe(it.keep)})" + if (it.identical) " [identical]" else "" }
                        java.io.File(getExternalFilesDir(null), "plan-dryrun.txt").writeText(text)
                        android.util.Log.i("LocalMusic", "plan written: ${p.moves.size} moves, ${p.removals.size} removals")
                    }.start()
                }
                android.util.Log.i("LocalMusic", "cmd=$cmd songs=${Library.index.songs.size} playing=${Player.playing} pos=${Player.position()} dur=${Player.duration()} speed=${Player.speed} " +
                    "queue=${Player.queue.pos + 1}/${Player.queue.size} song=${Player.current?.path}")
            }
        }.start()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQ_VIDEO && !Library.hasVideoPermission(this)) toast(tr("Videos stay hidden. Allow “Videos” for this app in Settings to list them.", "Videolar gizli kalır. Listelemek için Ayarlar'da bu uygulamaya “Videolar” izni verin."))
        if (requestCode == REQ_PERMS || requestCode == REQ_VIDEO) loadLibrary()
    }

    override fun onStart() {
        super.onStart()
        started = true
        Player.setUiVisible(true)
        Player.addListener(onPlayer)
        if (Library.stale(this) || Library.index.byId.isEmpty()) loadLibrary() else render()
        watchMedia(true)
        nowPlaying?.watchVolume(true)
        onPlayerChanged()
    }

    // New files (e.g. from Plain Youtube) show up without a manual rescan: MediaStore's generation is checked on every
    // resume, and while the activity is visible a ContentObserver triggers the same cheap check. No observer in the background.
    private val rescan = Runnable { if (started && Library.stale(this)) loadLibrary() }
    private val mediaObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(rescan)
            handler.postDelayed(rescan, 1500) // a download fires several changes: check once they settle
        }
    }
    private var watching = false

    private fun watchMedia(on: Boolean) {
        if (on == watching) return
        watching = on
        if (on) {
            contentResolver.registerContentObserver(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
            if (Library.hasVideoPermission(this)) contentResolver.registerContentObserver(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true, mediaObserver)
        } else {
            contentResolver.unregisterContentObserver(mediaObserver)
            handler.removeCallbacks(rescan)
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        leftPip = false // PiP was expanded back into the app
        checkSpace()
        if (!loading && Library.stale(this)) loadLibrary()
        tick()
        handler.removeCallbacks(ticker)
        if (Player.playing) handler.postDelayed(ticker, 500)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onStop() {
        started = false
        spaceChecked = false
        handler.removeCallbacks(hideUndo); undoBar.visibility = View.GONE; undoAction = null
        nowPlaying?.dropVideo() // no surface in the background or with the screen off: the video plays as audio
        nowPlaying?.watchVolume(false)
        if (leftPip) { leftPip = false; Player.pause() } // the PiP window was closed with its ✕: pause (the queue stays)
        watchMedia(false)
        Player.removeListener(onPlayer)
        Player.savePosition()
        Store.prefs.edit().putInt("tab4", tab).putInt("libSeg", seg).apply()
        Player.setUiVisible(false) // paused: the players are released, nothing of ours keeps running
        super.onStop()
    }

    @Deprecated("plain Activity back handling")
    override fun onBackPressed() {
        when {
            nowPlaying?.fullscreen == true -> nowPlaying?.setFullscreen(false)
            selection != null && nowPlaying == null && overlays.isEmpty() -> endSelection()
            overlays.isNotEmpty() -> closeOverlay(overlays.last())
            nowPlaying != null -> closeNowPlaying()
            query.isNotEmpty() -> { search.setText(""); hideKeyboard() }
            editPlaylist != null -> { editPlaylist = null; render() }
            drill.isNotEmpty() -> popDrill()
            else -> moveTaskToBack(true)
        }
    }

    @Deprecated("startIntentSenderForResult result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (eraser.onResult(requestCode, resultCode == RESULT_OK)) return
        organizer?.onConsent(requestCode, resultCode == RESULT_OK)
        trash?.onConsent(requestCode, resultCode == RESULT_OK)
    }

    var loading = false
    fun loadLibrary() {
        if (loading) return
        loading = true
        val app = applicationContext
        Thread {
            Library.load(app)
            runOnUiThread {
                loading = false
                Player.onLibraryChanged()
                render()
                onPlayerChanged()
            }
        }.start()
    }

    // --- layout ------------------------------------------------------------------------------

    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(C.BG) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(col, lpF(MATCH, MATCH))

        // top bar: back, search, menu
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(4), dp(2))
        }
        back = icon(R.drawable.mus_ic_back, desc = tr("Back", "Geri")) { onBackPressed() }
        top.addView(back)
        val searchBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outline(dpf(22))
            setPadding(dp(12), 0, dp(6), 0)
        }
        searchBox.addView(ImageView(this).apply { setImageResource(R.drawable.mus_ic_search); tint(C.LABEL) }, lpL(dp(20), dp(20)))
        search = EditText(this).apply {
            hint = tr("Search songs, artists, albums", "Şarkı, sanatçı, albüm ara")
            setHintTextColor(C.LABEL)
            setTextColor(C.TEXT)
            textSize = 15f
            background = null
            setSingleLine()
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), dp(10), dp(4), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val q = s?.toString() ?: ""
                    if (q != query) { query = q; render(resetScroll = true) }
                }
            })
            setOnEditorActionListener { _, _, _ -> hideKeyboard(); true }
        }
        searchBox.addView(search, lpL(0, WRAP, 1f))
        searchBox.addView(icon(R.drawable.mus_ic_close, 36, 9, C.LABEL, tr("Clear", "Temizle")) { search.setText("") }.also { clear ->
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { clear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE }
            })
            clear.visibility = View.GONE
        })
        top.addView(searchBox, lpL(0, dp(44), 1f).apply { leftMargin = dp(4); rightMargin = dp(2) })
        menuBtn = icon(R.drawable.mus_ic_more, desc = tr("Menu", "Menü")) { mainMenu() }
        top.addView(menuBtn)
        col.addView(top, lpL(MATCH, WRAP))
        spaceBar = text("", 13f, C.ACCENT, lines = 2).apply {
            background = outline(dpf(10), C.ACCENT)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE; openTrash() }
        }
        col.addView(spaceBar, lpL(MATCH, WRAP).apply { leftMargin = dp(10); rightMargin = dp(10); topMargin = dp(4) })

        // tabs
        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(10), dp(6), dp(10), dp(6)) }
        TABS.forEachIndexed { i, name ->
            val t = text(name, 14f, C.TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(7), dp(14), dp(7))
                setOnClickListener { selectTab(i) }
            }
            tabViews += t
            tabRow.addView(t, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        val tabScrollView = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(tabRow) }
        col.addView(tabScrollView, lpL(MATCH, WRAP))

        // Library segments (only on the Library tab)
        val segRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), 0, dp(12), dp(4)) }
        SEGMENTS.forEachIndexed { i, name ->
            val t = text(name, 13f, C.DIM).apply {
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(5), dp(11), dp(5))
                setOnClickListener { selectSeg(i) }
            }
            segViews += t
            segRow.addView(t, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        segBar = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(segRow) }
        col.addView(segBar, lpL(MATCH, WRAP))

        titleView = text("", 22f, C.TEXT, bold = true).apply { setPadding(dp(16), dp(4), dp(16), dp(2)); visibility = View.GONE }
        col.addView(titleView, lpL(MATCH, WRAP))

        list = ListView(this).apply {
            divider = null
            dividerHeight = 0
            selector = android.graphics.drawable.ColorDrawable(0)
            isFastScrollEnabled = true
            adapter = this@MainActivity.adapter
            clipToPadding = false
            setPadding(0, 0, 0, dp(8))
            setOnItemClickListener { _, _, pos, _ -> onRowClick(this@MainActivity.adapter.rows[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> onRowLong(this@MainActivity.adapter.rows[pos]) }
        }
        col.addView(list, lpL(MATCH, 0, 1f))

        // mini player
        mini = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = outline(dpf(10), C.OUTLINE_BTN)
            setOnClickListener { openNowPlaying() }
            visibility = View.GONE
        }
        val miniRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(4), dp(6))
        }
        miniArt = ArtView(this, dpf(6))
        miniRow.addView(miniArt, lpL(dp(42), dp(42)))
        val miniText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(6), 0) }
        miniTitle = text("", 14f, C.TEXT, bold = true)
        miniSub = text("", 12.5f, C.DIM)
        miniText.addView(miniTitle); miniText.addView(miniSub)
        miniRow.addView(miniText, lpL(0, WRAP, 1f))
        miniPlay = icon(R.drawable.mus_ic_play, 44, 9, desc = tr("Play/pause", "Oynat/duraklat")) { Player.toggle() }
        miniRow.addView(miniPlay)
        miniRow.addView(icon(R.drawable.mus_ic_next, 44, 10, desc = tr("Next", "Sonraki")) { Player.next() })
        mini.addView(miniRow, lpL(MATCH, WRAP))
        miniProgress = ProgressLine(this)
        mini.addView(miniProgress, lpL(MATCH, dp(2)).apply { leftMargin = dp(8); rightMargin = dp(8); bottomMargin = dp(1) })
        // bulk selection (hold a song → Select)
        val sel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outline(dpf(10), C.ACCENT)
            setPadding(dp(2), dp(4), dp(8), dp(4))
            visibility = View.GONE
        }
        sel.addView(icon(R.drawable.mus_ic_close, 40, 10, C.TEXT, tr("End selection", "Seçimi bitir")) { endSelection() })
        selCount = text("", 14f, C.TEXT, bold = true)
        sel.addView(selCount, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        val selActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun selPill(label: String, fn: () -> Unit) = selActions.addView(pill(label, C.BG) { fn() }, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
        selPill(tr("All", "Tümü")) { selection?.toggleAll(shownSongs()); updateSelection() }
        selPill(tr("Play next", "Sonra çal")) { selectedSongs().takeIf { it.isNotEmpty() }?.let { Player.playNext(it); toast(tr("${it.size} play next", "${it.size} şarkı sonra çalacak")); endSelection() } }
        selPill(tr("Add to playlist", "Çalma listesine ekle")) { selectedSongs().takeIf { it.isNotEmpty() }?.let { addToPlaylist(it) } }
        selActions.addView(pill(tr("Erase", "Sil"), C.BG, C.RED, C.RED) { selectedSongs().takeIf { it.isNotEmpty() }?.let { eraser.confirm(EraseSet.of(it), tr("${it.size} selected", "${it.size} seçili öğe")) } })
        sel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(selActions) }, lpL(0, WRAP, 1f))
        selBar = sel
        col.addView(sel, lpL(MATCH, WRAP).apply { leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(6) })

        // Undo after an erase without Android's dialog (Media management granted): 8 s, UI only
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outline(dpf(10), C.OUTLINE_BTN)
            setPadding(dp(14), dp(4), dp(4), dp(4))
            visibility = View.GONE
        }
        undoText = text("", 14f, C.TEXT)
        undoBar.addView(undoText, lpL(0, WRAP, 1f))
        undoBar.addView(text(PlainLang.upper(tr("Undo", "Geri al")), 14f, C.ACCENT, bold = true).apply {
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { val a = undoAction; handler.removeCallbacks(hideUndo); hideUndo.run(); a?.invoke() }
        })
        col.addView(undoBar, lpL(MATCH, WRAP).apply { leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(6) })

        col.addView(mini, lpL(MATCH, WRAP).apply { leftMargin = dp(6); rightMargin = dp(6); bottomMargin = dp(6) })

        setContentView(Hub.wrap(this, "music", root))
        // edge-to-edge safe padding for status/navigation bars
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            col.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            for (o in overlays) o.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            nowPlaying?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insetsCache = intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        updateTabs()
    }

    private var insetsCache = intArrayOf(0, 0, 0, 0)

    private fun applyInsets(v: View) = v.setPadding(insetsCache[0], insetsCache[1], insetsCache[2], insetsCache[3])

    private fun updateTabs() {
        tabViews.forEachIndexed { i, t ->
            val sel = i == tab && query.isEmpty()
            t.background = outline(dpf(18), if (sel) C.ACCENT else C.OUTLINE) // OLED: outline, not a lit fill
            t.setTextColor(if (sel) C.ACCENT else C.DIM)
            t.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
        }
        segBar.visibility = if (tab == LIBRARY && query.isEmpty() && drill.isEmpty() && editPlaylist == null) View.VISIBLE else View.GONE
        segViews.forEachIndexed { i, t ->
            val sel = i == seg
            t.background = outline(dpf(14), if (sel) C.TEXT else C.OUTLINE)
            t.setTextColor(if (sel) C.TEXT else C.DIM)
        }
        back.visibility = if (drill.isNotEmpty() || query.isNotEmpty() || editPlaylist != null) View.VISIBLE else View.GONE
    }

    private fun selectTab(i: Int) {
        endSelection()
        if (query.isNotEmpty()) { search.setText(""); hideKeyboard() }
        if (i == tab && drill.isEmpty()) { list.setSelection(0); return }
        saveScroll()
        tab = i
        drill.clear()
        editPlaylist = null
        render()
        tabScroll[scrollKey()]?.let { (p, top) -> list.setSelectionFromTop(p, top) }
    }

    private fun selectSeg(i: Int) {
        endSelection()
        if (i == seg) { list.setSelection(0); return }
        saveScroll()
        seg = i
        render()
        tabScroll[scrollKey()]?.let { (p, top) -> list.setSelectionFromTop(p, top) } ?: list.setSelection(0)
    }

    private fun scrollKey() = if (tab == LIBRARY) 10 + seg else tab

    private fun saveScroll() {
        val p = list.firstVisiblePosition
        val top = list.getChildAt(0)?.top ?: 0
        if (drill.isEmpty()) tabScroll[scrollKey()] = p to top else drill.last().apply { scroll = p; scrollTop = top }
    }

    private fun pushDrill(title: String, rows: () -> List<Row>) {
        endSelection()
        saveScroll()
        drill += Drill(title, rows)
        render(resetScroll = true)
    }

    private fun popDrill() {
        endSelection()
        editPlaylist = null
        drill.removeAt(drill.size - 1)
        render()
        if (drill.isEmpty()) tabScroll[scrollKey()]?.let { (p, t) -> list.setSelectionFromTop(p, t) }
        else drill.last().let { list.setSelectionFromTop(it.scroll, it.scrollTop) }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        search.clearFocus()
    }

    // --- content -----------------------------------------------------------------------------

    fun render(resetScroll: Boolean = false) {
        updateTabs()
        val lib = Library.index
        val rows: List<Row> = when {
            !Library.hasPermission(this) -> listOf(EmptyRow(tr("LocalMusic needs access to the music files on this phone.\nNothing ever leaves the phone: the app has no internet permission.",
                "LocalMusic'in bu telefondaki müzik dosyalarına erişmesi gerekiyor.\nHiçbir şey telefondan çıkmaz: uygulamanın internet izni yok."), tr("Allow access", "Erişime izin ver") to {
                requestPermissions(if (Build.VERSION.SDK_INT >= 33) arrayOf(Library.PERMISSION, Library.VIDEO_PERMISSION) else arrayOf(Library.PERMISSION), REQ_PERMS)
            }))
            query.isNotEmpty() -> searchRows(lib)
            drill.isNotEmpty() -> drill.last().rows()
            else -> tabRows(lib)
        }
        titleView.visibility = View.GONE
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        if (resetScroll) list.setSelection(0)
    }

    private fun tabRows(lib: LibraryIndex): List<Row> {
        if (lib.songs.isEmpty() && tab != PLAYLISTS) {
            return listOf(EmptyRow(if (loading) tr("Loading…", "Yükleniyor…") else tr("No music found on this phone.", "Bu telefonda müzik bulunamadı."), tr("Rescan", "Yeniden tara") to { loadLibrary() }))
        }
        return when (tab) {
            HOME -> homeRows(lib)
            LIBRARY -> libraryRows(lib)
            VIDEOS -> videoRows(lib)
            else -> playlistRows(lib)
        }
    }

    // --- Home: shelves built from the cached library views and the play history ------------------
    // ListView only binds the shelves on screen, and a shelf's art loads only when it is bound.

    private fun videoOn() = Store.prefs.getBoolean("video", true)

    /** Play [list] from [i]; a video opens now playing when the picture is wanted. */
    private fun playFrom(list: List<Song>, i: Int) {
        hideKeyboard()
        Player.playList(list.map { it.id }, i)
        if (list[i].isVideo && videoOn()) openNowPlaying()
    }

    private fun songCard(list: List<Song>, i: Int, sub: String? = null): Card {
        val s = list[i]
        val tags = listOfNotNull(if (s.isVideo) tr("Video", "Video") else null, if (s.cached) tr("Cached", "Önbellekte") else null)
        return Card(s.title, sub ?: (tags + s.shownArtist).joinToString(" · "), listOf(s), { playFrom(list, i) }, { songMenu(s) })
    }

    private fun shelf(rows: MutableList<Row>, title: String, sub: String?, songs: List<Song>, cards: List<Card>, seeAll: (() -> Unit)?) {
        if (cards.isEmpty()) return // empty sections are hidden
        rows += ShelfHeaderRow(title, sub, if (songs.isEmpty()) null else ({ Player.playList(songs.map { it.id }, 0) }), seeAll)
        rows += ShelfRow(title, cards.take(SHELF_MAX))
    }

    private fun homeRows(lib: LibraryIndex): List<Row> {
        val rows = ArrayList<Row>()
        val total = lib.songs.size - lib.videos.size
        rows += HeaderRow(tr("Home", "Ana sayfa"), listOfNotNull(Text.count(total, "song"), if (lib.videos.isNotEmpty()) Text.count(lib.videos.size, "video") else null,
            Text.longTime(lib.songs.sumOf { it.durationMs })).joinToString(" · "), listOf(
            tr("Shuffle all", "Tümünü karıştır") to { Player.shufflePlay(lib.songs.filter { !it.isVideo }.map { it.id }); openNowPlaying() },
        ))

        val fresh = lib.newPieces
        val freshSongs = fresh.flatMap { it.songs }
        shelf(rows, tr("New pieces", "Yeni parçalar"), tr("Last ${NewPieces.DAYS} days", "Son ${NewPieces.DAYS} gün") + " · ${Text.count(freshSongs.size, "piece")}", freshSongs, fresh.take(SHELF_MAX).map { item ->
            if (item.isBundle) Card(item.title, item.sub, item.songs, { openSongList(item.title, item.sub, item.songs) }, { groupMenu(Group("new", item.title, item.songs), K_NEW) }, mosaic = true)
            else songCard(freshSongs, freshSongs.indexOf(item.songs[0]))
        }) { openNewPieces() }

        shownHistory = Store.historyVersion
        val h = Store.history
        val recent = h.recent(lib.byId, lib.byPath).take(100)
        shelf(rows, tr("Recently listened", "Son dinlenenler"), null, recent, recent.indices.map { songCard(recent, it) }) {
            openSongList(tr("Recently listened", "Son dinlenenler"), tr("Newest first", "Önce en yeniler"), Store.history.recent(Library.index.byId, Library.index.byPath).take(200))
        }
        val most = h.most(lib.byId, lib.byPath).take(100)
        if (most.size >= 3) {
            val songs = most.map { it.first }
            shelf(rows, tr("Most played", "En çok çalınanlar"), null, songs, most.indices.map { i -> songCard(songs, i, "${most[i].first.shownArtist} · " + tr("${most[i].second} plays", "${most[i].second} kez çalındı")) }) {
                openSongList(tr("Most played", "En çok çalınanlar"), tr("Most plays first", "Önce en çok çalınanlar"), Store.history.most(Library.index.byId, Library.index.byPath).take(200).map { it.first })
            }
        }

        shelf(rows, tr("Videos", "Videolar"), Text.count(lib.videos.size, "video"), lib.videos, lib.videos.indices.take(SHELF_MAX).map { songCard(lib.videos, it) }) { selectTab(VIDEOS) }
        val pyt = lib.plainYoutube
        val pytTitle = tr("From Plain Youtube", "Plain Youtube'dan")
        val pytSub = tr("Saved and cached · newest first", "Kaydedilen ve önbellekteki · önce en yeniler")
        shelf(rows, pytTitle, pytSub, pyt, pyt.indices.take(SHELF_MAX).map { songCard(pyt, it) }) {
            openSongList(pytTitle, pytSub, Library.index.plainYoutube)
        }
        val pls = Store.playlists.map { it to it.resolve(lib.byId, lib.byPath) }
        shelf(rows, tr("Playlists", "Çalma listeleri"), null, emptyList(), pls.map { (p, songs) ->
            Card(p.name, Text.count(songs.size, "song"), songs, { openPlaylist(p) }, { playlistMenu(p) })
        }) { selectTab(PLAYLISTS) }
        return rows
    }

    private fun openNewPieces() {
        pushDrill(tr("New pieces", "Yeni parçalar")) {
            val items = Library.index.newPieces
            val songs = items.flatMap { it.songs }
            val rows = ArrayList<Row>()
            rows += HeaderRow(tr("New pieces", "Yeni parçalar"), tr("Added in the last ${NewPieces.DAYS} days", "Son ${NewPieces.DAYS} günde eklendi") + " · ${Text.count(songs.size, "piece")}", playButtons(songs))
            for (it in items) {
                if (it.isBundle) rows += GroupRow(it.title, it.sub, it.songs[0].id, { openSongList(it.title, it.sub, it.songs) }, { groupMenu(Group("new", it.title, it.songs), K_NEW) }, artSongs = it.songs, mosaic = true)
                else rows += SongRow(it.songs[0], songs, songs.indexOf(it.songs[0]))
            }
            rows
        }
    }

    /** A plain song list page (a snapshot): header with play buttons, then the songs. */
    private fun openSongList(title: String, sub: String?, songs: List<Song>, erase: Boolean = false) {
        if (query.isNotEmpty()) hideKeyboard()
        pushDrill(title) {
            listOf<Row>(HeaderRow(title, listOfNotNull(sub, Text.count(songs.size, "song") + " · " + Text.longTime(songs.sumOf { it.durationMs })).joinToString("\n"),
                playButtons(songs) + if (erase) listOf(eraseButton(songs, K_ALL_IN, title, strong = true)) else emptyList())) +
                songs.mapIndexed { i, s -> SongRow(s, songs, i) }
        }
    }

    private fun openPacked(g: Group) = openSongList(g.title, g.artist.ifBlank { null }, g.songs, erase = true)

    // --- Library: Songs / Artists / Albums / Genres / Folders -------------------------------------

    private fun libraryRows(lib: LibraryIndex): List<Row> = when (seg) {
        0 -> listOf<Row>(HeaderRow(tr("Songs", "Şarkılar"), tr("${lib.songs.size} songs", "${lib.songs.size} şarkı") + " · ${Text.time(lib.songs.sumOf { it.durationMs })}", listOf(
            tr("Shuffle all", "Tümünü karıştır") to { Player.shufflePlay(lib.songs.map { it.id }); openNowPlaying() },
            tr("Play", "Oynat") to { Player.playList(lib.songs.map { it.id }, 0) },
        ))) + lib.songs.mapIndexed { i, s -> SongRow(s, lib.songs, i) }
        1 -> {
            val sort = Store.prefs.getInt("artistSort", 0)
            val max = Store.prefs.getInt("packMax", 1).coerceIn(1, 2)
            val packed = lib.packedArtists(max)
            val plays = playCounts()
            val list = if (sort == 0) packed.groups.sortedWith(compareBy({ -it.count }, { Text.sortKey(it.title) })) else packed.groups
            val rows = ArrayList<Row>()
            rows += HeaderRow(tr("Artists", "Sanatçılar"), "${Text.count(lib.artists.size, "artist")} · ${Text.count(lib.songs.size, "song")}" +
                (packed.packed?.let {
                    tr("\nArtists with ${if (max == 1) "1 song" else "1–2 songs"} are packed into “${Packing.oneOffsTitle}”",
                        "\n${if (max == 1) "1" else "1–2"} şarkılı sanatçılar “${Packing.oneOffsTitle}” içinde toplanır")
                } ?: ""), listOf(
                tr("Most songs", "En çok şarkı") to { setSort("artistSort", 0) }, "A–Z" to { setSort("artistSort", 1) },
                (if (max == 1) tr("Pack: 1 song", "Topla: 1 şarkı") else tr("Pack: ≤2 songs", "Topla: ≤2 şarkı")) to { setSort("packMax", if (max == 1) 2 else 1) },
            ), accent = sort)
            packed.packed?.let { p -> rows += GroupRow(p.title, p.artist + " · " + Text.count(p.count, "song"), p.songs.first().id, { openPacked(p) }, { groupMenu(p, K_ALL_IN, strong = true) }, round = true, artSongs = p.songs, mosaic = true) }
            list.forEach { g -> rows += GroupRow(g.title, g.stats, g.songs.first().id, { openArtist(g) }, { groupMenu(g, K_ARTIST) }, round = true, artSongs = ArtPick.artist(g.songs, plays)) }
            rows
        }
        2 -> {
            val sort = Store.prefs.getInt("albumSort", 0)
            val packed = lib.packedAlbums(sort) {
                when (sort) {
                    1 -> lib.albums.sortedWith(compareBy({ Text.sortKey(it.artist) }, { -it.year }, { Text.sortKey(it.title) }))
                    2 -> lib.albums.sortedWith(compareBy({ -it.count }, { Text.sortKey(it.title) }))
                    else -> lib.albums
                }
            }
            listOf<Row>(HeaderRow(tr("Albums", "Albümler"), "${Text.count(packed.groups.size, "album")} · ${Text.count(lib.songs.size, "song")}" +
                (packed.packed?.let { tr("\nSingle tracks are packed into “${Packing.singlesTitle}”", "\nTek parçalar “${Packing.singlesTitle}” içinde toplanır") } ?: ""), listOf(
                "A–Z" to { setSort("albumSort", 0) }, tr("By artist", "Sanatçıya göre") to { setSort("albumSort", 1) }, tr("Most songs", "En çok şarkı") to { setSort("albumSort", 2) },
            ), accent = sort)) + albumGrid(listOfNotNull(packed.packed) + packed.groups)
        }
        3 -> {
            val packed = lib.packedGenres
            if (lib.genres.isEmpty()) listOf(EmptyRow(tr("No genre tags found.", "Tür etiketi bulunamadı."))) else
                (packed.groups.map { g -> GroupRow(g.title, g.stats, g.songs.first().id, { openGroup(g, null) }, { groupMenu(g, K_GENRE) }) } +
                    listOfNotNull(packed.packed?.let { p -> GroupRow(p.title, tr("Genres with fewer than 3 songs · ", "3'ten az şarkılı türler · ") + p.stats, p.songs.first().id, { openPacked(p) }, { groupMenu(p, K_GENRE) }) }))
        }
        else -> listOf<Row>(HeaderRow(tr("Folders", "Klasörler"), tr("Single-subfolder chains are merged; single files are packed into “${FolderTree.looseTitle}”",
            "Tek alt klasörlü zincirler birleştirilir; tek dosyalar “${FolderTree.looseTitle}” içinde toplanır"), emptyList())) +
            lib.folderTree.map { folderRow(it) }
    }

    /** Plays per song id (history), for picking an artist's cover from its most-played album. */
    private fun playCounts(): Map<Long, Int> = Store.history.entries.associate { it.id to it.count }

    private fun folderRow(n: FolderNode) =
        GroupRow(n.title, n.stats, n.all.first().id, { openFolder(n) }, { groupMenu(Group(n.key, n.title, n.all), K_FOLDER, strong = n.loose || n.count >= 50) }, artSongs = n.all, mosaic = true)

    private fun openFolder(n: FolderNode) {
        pushDrill(n.title) {
            val fresh = Library.index.folderTree.firstNotNullOfOrNull { it.find(n.key) } ?: n
            val rows = ArrayList<Row>()
            rows += HeaderRow(fresh.title, fresh.stats, playButtons(fresh.all) + eraseButton(fresh.all, K_FOLDER, fresh.title, fresh.loose || fresh.count >= 50))
            fresh.children.forEach { rows += folderRow(it) }
            if (fresh.songs.isNotEmpty()) {
                if (fresh.children.isNotEmpty()) rows += SectionRow(tr("Files here (${fresh.songs.size})", "Buradaki dosyalar (${fresh.songs.size})"))
                fresh.songs.forEachIndexed { i, s -> rows += SongRow(s, fresh.songs, i) }
            }
            rows
        }
    }

    // --- Videos: Plain Youtube's videos per channel -----------------------------------------------

    private fun videoRows(lib: LibraryIndex): List<Row> {
        val v = lib.videos
        if (v.isEmpty()) {
            val needPerm = Build.VERSION.SDK_INT >= 33 && !Library.hasVideoPermission(this)
            return listOf(EmptyRow(
                if (needPerm) tr("Videos from Plain Youtube need the “Videos” permission.\nWithout it LocalMusic lists audio only.",
                    "Plain Youtube videoları için “Videolar” izni gerekir.\nİzin olmadan LocalMusic yalnızca sesleri listeler.")
                else tr("No videos from Plain Youtube yet.\nSaved and cached videos appear here by themselves.",
                    "Henüz Plain Youtube videosu yok.\nKaydedilen ve önbellekteki videolar burada kendiliğinden görünür."),
                if (needPerm) tr("Allow videos", "Videolara izin ver") to { requestPermissions(arrayOf(Library.VIDEO_PERMISSION), REQ_VIDEO) } else null,
            ))
        }
        val on = videoOn()
        val rows = ArrayList<Row>()
        rows += HeaderRow(tr("Videos", "Videolar"), "${Text.count(v.size, "video")} · ${Text.count(lib.videoChannels.size, "channel")} · " + tr("newest first", "önce en yeniler"), listOf(
            tr("Play all", "Tümünü oynat") to { Player.playList(v.map { it.id }, 0); if (on) openNowPlaying() },
            tr("Shuffle", "Karıştır") to { Player.shufflePlay(v.map { it.id }); if (on) openNowPlaying() },
            (if (on) tr("Picture: on", "Görüntü: açık") else tr("Picture: off", "Görüntü: kapalı")) to { Store.prefs.edit().putBoolean("video", !on).apply(); render() },
        ), accent = if (on) 2 else -1)
        for (ch in lib.videoChannels) {
            rows += SectionRow("${ch.title} · ${ch.count}")
            ch.songs.forEachIndexed { i, s -> rows += SongRow(s, ch.songs, i) }
        }
        return rows
    }

    private fun playlistRows(lib: LibraryIndex): List<Row> {
        val byPath = lib.byPath
        val rows = ArrayList<Row>()
        rows += HeaderRow(tr("Playlists", "Çalma listeleri"), Text.count(Store.playlists.size, "playlist"), listOf(tr("New playlist", "Yeni çalma listesi") to { newPlaylist(null) }))
        for (p in Store.playlists) {
            val songs = p.resolve(lib.byId, byPath)
            rows += GroupRow(p.name, tr("${songs.size} songs", "${songs.size} şarkı") + " · ${Text.time(songs.sumOf { it.durationMs })}", songs.firstOrNull()?.id ?: -1, { openPlaylist(p) }, { playlistMenu(p) })
        }
        if (Store.playlists.isEmpty()) rows += EmptyRow(tr("No playlists yet. Use ⋮ on any song → Add to playlist, or create one here.",
            "Henüz çalma listesi yok. Bir şarkıda ⋮ → Çalma listesine ekle'yi kullanın ya da burada oluşturun."))
        return rows
    }

    private fun searchRows(lib: LibraryIndex): List<Row> {
        val rows = ArrayList<Row>()
        val artists = lib.searchGroups(lib.artists, query).take(4)
        val plays = playCounts()
        val albums = lib.searchGroups(lib.albums, query).take(4)
        val songs = lib.search(query)
        if (artists.isNotEmpty()) {
            rows += SectionRow(tr("Artists", "Sanatçılar"))
            artists.forEach { g -> rows += GroupRow(g.title, g.stats, g.songs.first().id, { openArtist(g) }, { groupMenu(g, K_ARTIST) }, round = true, artSongs = ArtPick.artist(g.songs, plays)) }
        }
        if (albums.isNotEmpty()) {
            rows += SectionRow(tr("Albums", "Albümler"))
            albums.forEach { g -> rows += GroupRow(g.title, g.artist + " · " + g.sub, g.songs.first().id, { openAlbum(g) }, { groupMenu(g, K_ALBUM) }) }
        }
        if (songs.isNotEmpty()) {
            rows += SectionRow(tr("Songs (${songs.size})", "Şarkılar (${songs.size})"))
            songs.forEachIndexed { i, s -> rows += SongRow(s, songs, i) }
        }
        if (rows.isEmpty()) rows += EmptyRow(tr("Nothing matches “$query”.", "“$query” ile eşleşen bir şey yok."))
        return rows
    }

    private fun playButtons(songs: List<Song>): List<Pair<String, () -> Unit>> = listOf(
        tr("Play", "Oynat") to { Player.playList(songs.map { it.id }, 0) },
        tr("Shuffle", "Karıştır") to { Player.shufflePlay(songs.map { it.id }) },
        tr("Add to queue", "Sıraya ekle") to { Player.addToQueue(songs); toast(tr("Added ${songs.size} to queue", "${songs.size} şarkı sıraya eklendi")) },
        tr("Add to playlist", "Çalma listesine ekle") to { addToPlaylist(songs) },
    )

    private fun openGroup(g: Group, sub: String?) {
        if (query.isNotEmpty()) { hideKeyboard() }
        pushDrill(g.title) {
            val fresh = findGroup(g) ?: g
            listOf<Row>(HeaderRow(fresh.title, listOfNotNull(sub, fresh.stats).joinToString("\n"), playButtons(fresh.songs))) +
                fresh.songs.mapIndexed { i, s -> SongRow(s, fresh.songs, i) }
        }
    }

    private fun findGroup(g: Group): Group? {
        val lib = Library.index
        return (lib.albums.firstOrNull { it.key == g.key } ?: lib.genres.firstOrNull { it.key == g.key }
            ?: lib.folders.firstOrNull { it.key == g.key } ?: lib.artists.firstOrNull { it.key == g.key })
    }

    private fun setSort(key: String, v: Int) {
        Store.prefs.edit().putInt(key, v).apply()
        render(resetScroll = true)
    }

    private fun albumGrid(albums: List<Group>): List<Row> = albums.chunked(3).map { AlbumPairRow(it) }

    // Erase kinds: internal keys (English); eraseLabel/eraseWhat turn them into the shown text.
    private val K_ALBUM = "album"
    private val K_ARTIST = "artist"
    private val K_ALL_IN = "all in"
    private val K_GENRE = "genre songs"
    private val K_FOLDER = "folder contents"
    private val K_SINGLES = "all single tracks"
    private val K_NEW = "these new pieces"

    /** Menu item: "Erase album (12 songs)…". */
    private fun eraseLabel(kind: String, count: String): String = tr("Erase $kind ($count)…", when (kind) {
        K_ALBUM -> "Albümü sil"
        K_ARTIST -> "Sanatçıyı sil"
        K_ALL_IN -> "Tümünü sil"
        K_GENRE -> "Türün şarkılarını sil"
        K_FOLDER -> "Klasör içeriğini sil"
        K_SINGLES -> "Tüm tekli parçaları sil"
        K_NEW -> "Bu yeni parçaları sil"
        else -> "Sil"
    } + " ($count)…")

    /** What the confirmation names: "album “Karma”". */
    private fun eraseWhat(kind: String, title: String): String = tr("$kind “$title”", when (kind) {
        K_ALBUM -> "albüm “$title”"
        K_ARTIST -> "sanatçı “$title”"
        K_ALL_IN -> "“$title” içindekilerin tümü"
        K_GENRE -> "“$title” türünün şarkıları"
        K_FOLDER -> "“$title” klasörünün içeriği"
        K_SINGLES -> "“$title” içindeki tüm tekli parçalar"
        K_NEW -> "bu yeni parçalar: “$title”"
        else -> "“$title”"
    })

    /** Long-press on an artist/album/genre/folder: play it without opening it, or erase it ([kind]: "album", "artist"...). */
    private fun groupMenu(g: Group, kind: String = "", strong: Boolean = false) {
        menu(g.title, listOf(
            tr("Play", "Oynat") to { Player.playList(g.songs.map { it.id }, 0) },
            tr("Shuffle", "Karıştır") to { Player.shufflePlay(g.songs.map { it.id }) },
            tr("Play next", "Sonra çal") to { Player.playNext(g.songs); toast(tr("Plays next", "Sonra çalacak")) },
            tr("Add to queue", "Sıraya ekle") to { Player.addToQueue(g.songs); toast(tr("Added ${g.songs.size} to queue", "${g.songs.size} şarkı sıraya eklendi")) },
            tr("Add to playlist…", "Çalma listesine ekle…") to { addToPlaylist(g.songs) },
        ) + (if (kind.isEmpty()) emptyList() else listOf(eraseItem(g.songs, kind, g.title, strong))))
    }

    /** "Erase album (12 songs)…" → confirmation with count and size → one trash request. */
    private fun eraseItem(songs: List<Song>, kind: String, title: String, strong: Boolean = false): Pair<String, () -> Unit> {
        val set = EraseSet.of(songs)
        return eraseLabel(kind, set.summary.substringBeforeLast(" · ")) to { eraser.confirm(set, eraseWhat(kind, title), strong) }
    }

    private fun eraseButton(songs: List<Song>, kind: String, title: String, strong: Boolean = false): Pair<String, () -> Unit> =
        tr("Erase…", "Sil…") to { eraser.confirm(EraseSet.of(songs), eraseWhat(kind, title), strong) }

    /** Artist page: play everything (merged) or pick an album. */
    private fun openArtist(g: Group) {
        if (query.isNotEmpty()) hideKeyboard()
        pushDrill(g.title) {
            val lib = Library.index
            val fresh = lib.artists.firstOrNull { it.key == g.key } ?: g
            val albums = lib.albumsOf(fresh)
            val rows = ArrayList<Row>()
            rows += HeaderRow(fresh.title, fresh.stats, listOf(
                tr("Play all", "Tümünü oynat") to { Player.playList(fresh.songs.map { it.id }, 0) },
                tr("Shuffle all", "Tümünü karıştır") to { Player.shufflePlay(fresh.songs.map { it.id }) },
                tr("Add to queue", "Sıraya ekle") to { Player.addToQueue(fresh.songs); toast(tr("Added ${fresh.songs.size} to queue", "${fresh.songs.size} şarkı sıraya eklendi")) },
                tr("Add to playlist", "Çalma listesine ekle") to { addToPlaylist(fresh.songs) },
                eraseButton(fresh.songs, K_ARTIST, fresh.title),
            ))
            if (albums.size > 1 || albums.firstOrNull()?.let { it.count != fresh.count } == true) {
                val packed = Packing.albums(albums) // single tracks: one "Singles" tile
                rows += SectionRow(tr("Albums (${packed.groups.size})", "Albümler (${packed.groups.size})"))
                rows += albumGrid(packed.groups + listOfNotNull(packed.packed))
            }
            rows += SectionRow(tr("All songs (${fresh.count})", "Tüm şarkılar (${fresh.count})"))
            fresh.songs.forEachIndexed { i, s -> rows += SongRow(s, fresh.songs, i) }
            rows
        }
    }

    /** Album page: tracks in order, track numbers instead of art. */
    fun openAlbum(g: Group) {
        if (query.isNotEmpty()) hideKeyboard()
        pushDrill(g.title) {
            val fresh = Library.index.albums.firstOrNull { it.key == g.key } ?: g
            val info = listOfNotNull(fresh.artist, fresh.year.takeIf { it > 0 }?.toString()).joinToString(" · ")
            listOf<Row>(HeaderRow(fresh.title, info + "\n" + fresh.stats, playButtons(fresh.songs) + eraseButton(fresh.songs, K_ALBUM, fresh.title, Packing.isPacked(fresh)))) +
                fresh.songs.mapIndexed { i, s -> SongRow(s, fresh.songs, i, inAlbum = true) }
        }
    }

    private fun openPlaylist(p: Playlist) {
        pushDrill(p.name) {
            val lib = Library.index
            val songs = p.resolve(lib.byId, lib.songs.associateBy { it.path.lowercase() })
            val editing = editPlaylist === p
            val actions = if (editing) listOf(tr("Done", "Bitti") to { editPlaylist = null; Store.savePlaylists(); render() })
            else playButtons(songs).take(2) + listOf(tr("Edit", "Düzenle") to { editPlaylist = p; render() }, tr("More", "Diğer") to { playlistMenu(p) })
            val rows = ArrayList<Row>()
            rows += HeaderRow(p.name, tr("${songs.size} songs", "${songs.size} şarkı") + " · ${Text.time(songs.sumOf { it.durationMs })}" +
                if (editing) tr("\nDrag ≡ to reorder, ✕ to remove", "\nSıralamak için ≡ sürükleyin, kaldırmak için ✕") else "", actions)
            songs.forEachIndexed { i, s -> rows += SongRow(s, songs, i, if (editing) SongRow.Mode.PLAYLIST_EDIT else SongRow.Mode.NORMAL, p) }
            if (songs.isEmpty()) rows += EmptyRow(tr("Empty. Add songs with ⋮ → Add to playlist.", "Boş. ⋮ → Çalma listesine ekle ile şarkı ekleyin."))
            rows
        }
    }

    private fun onRowClick(r: Row) {
        if (selection != null && r is SongRow && r.mode == SongRow.Mode.NORMAL) { toggleSelected(r.song); return }
        when (r) {
            is SongRow -> if (r.mode == SongRow.Mode.NORMAL) {
                hideKeyboard()
                Player.playList(r.list.map { it.id }, r.index)
                if (r.song.isVideo && Store.prefs.getBoolean("video", true)) openNowPlaying() // a video: show it
            }
            is GroupRow -> r.onClick()
            is EmptyRow -> r.action?.second?.invoke()
            else -> {}
        }
    }

    private fun onRowLong(r: Row): Boolean {
        if (selection != null && r is SongRow && r.mode == SongRow.Mode.NORMAL) { toggleSelected(r.song); return true }
        when (r) {
            is SongRow -> songMenu(r)
            is GroupRow -> r.onLong?.invoke() ?: return false
            else -> return false
        }
        return true
    }

    // --- menus -------------------------------------------------------------------------------

    fun songMenu(r: SongRow) = songMenu(r.song, r.playlist, selectable = r.mode == SongRow.Mode.NORMAL)

    // --- bulk selection ---------------------------------------------------------------------------

    private fun shownSongs(): List<Song> = adapter.rows.filterIsInstance<SongRow>().filter { it.mode == SongRow.Mode.NORMAL }.map { it.song }
    private fun selectedSongs(): List<Song> = selection?.selected(shownSongs()) ?: emptyList()

    private fun startSelection(first: Song) {
        selection = Selection().also { it.toggle(first.id) }
        updateSelection()
    }

    private fun toggleSelected(s: Song) {
        val sel = selection ?: return
        sel.toggle(s.id)
        if (sel.size == 0) endSelection() else updateSelection()
    }

    private fun updateSelection() {
        val sel = selection ?: return
        selCount.text = tr("${sel.size} selected", "${sel.size} seçili")
        selBar.visibility = View.VISIBLE
        mini.visibility = View.GONE
        adapter.notifyDataSetChanged()
    }

    fun endSelection() {
        if (selection == null) return
        selection = null
        selBar.visibility = View.GONE
        mini.visibility = if (Player.current != null) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    /** After an erase without Android's dialog: "Erased … · UNDO" for 8 s (a UI timeout, removed in onStop). */
    fun showUndo(text: String, undo: () -> Unit) {
        endSelection()
        undoText.text = text
        undoAction = undo
        undoBar.visibility = View.VISIBLE
        handler.removeCallbacks(hideUndo)
        handler.postDelayed(hideUndo, 8000)
    }

    /** Low storage, checked on open/resume only. Without Media management just a banner pointing at the trash. */
    private fun checkSpace() {
        if (spaceChecked) return
        spaceChecked = true
        val app = applicationContext
        Thread {
            val free = Eraser.freeBytes()
            val trash = if (TrashMath.isLow(free) && Library.hasPermission(app)) Eraser.queryTrash(app) else emptyList()
            runOnUiThread {
                val bytes = trash.sumOf { it.size }
                if (trash.isEmpty() || isFinishing) { spaceBar.visibility = View.GONE; return@runOnUiThread }
                if (eraser.onLowSpace(free, trash)) { spaceBar.visibility = View.GONE; return@runOnUiThread }
                spaceBar.text = tr("Low storage — trash holds ${Text.bytes(bytes)} · Empty trash", "Depolama az — çöp kutusunda ${Text.bytes(bytes)} var · Çöp kutusunu boşalt")
                spaceBar.visibility = View.VISIBLE
            }
        }.start()
    }

    fun songMenu(s: Song, playlist: Playlist? = null, selectable: Boolean = false) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (selectable && selection == null && nowPlaying == null && overlays.isEmpty()) items += tr("Select", "Seç") to { startSelection(s) }
        items += tr("Play next", "Sonra çal") to { Player.playNext(listOf(s)); toast(tr("Plays next", "Sonra çalacak")) }
        items += tr("Add to queue", "Sıraya ekle") to { Player.addToQueue(listOf(s)); toast(tr("Added to queue", "Sıraya eklendi")) }
        items += tr("Add to playlist…", "Çalma listesine ekle…") to { addToPlaylist(listOf(s)) }
        if (playlist != null) items += tr("Remove from this playlist", "Bu listeden kaldır") to {
            val idx = playlist.entries.indexOfFirst { it.songId == s.id }
            if (idx >= 0) { playlist.entries.removeAt(idx); Store.savePlaylists(); render() }
        }
        items += tr("Go to album", "Albüme git") to { closeNowPlaying(); Library.index.albumOf(s)?.let { openAlbum(it) } }
        items += tr("Go to artist", "Sanatçıya git") to { closeNowPlaying(); Library.index.artistOf(s)?.let { openArtist(it) } }
        if (s.externalUri == null && s.id >= 0 || s.externalUri?.startsWith("content://") == true)
            items += (if (s.isVideo) tr("Share video…", "Videoyu paylaş…") else tr("Share file…", "Dosyayı paylaş…")) to { shareFile(s) }
        items += tr("Details", "Ayrıntılar") to {
            confirm(s.title, listOf(
                tr("Artist: ", "Sanatçı: ") + (s.inferredArtist?.let { tr("$it (from the folder)", "$it (klasörden)") } ?: s.artist.ifBlank { "—" }),
                tr("Album: ", "Albüm: ") + s.album.ifBlank { "—" }, tr("Genre: ", "Tür: ") + s.genre.ifBlank { "—" },
                tr("Length: ", "Süre: ") + Text.time(s.durationMs), tr("Size: ", "Boyut: ") + Text.bytes(s.size), tr("File: ", "Dosya: ") + s.path,
            ).let { if (s.isVideo) it + tr("Type: video (plays as audio unless “Video” is on in Now playing)", "Biçim: video (Şimdi çalınan ekranında “Video” açık değilse ses olarak çalar)") else it }
                .let { if (s.cached) it + tr("Cached by Plain Youtube: it may remove this file later", "Plain Youtube önbelleğinde: bu dosyayı daha sonra kaldırabilir") else it }.joinToString("\n"), tr("OK", "Tamam")) {}
        }
        if (s.externalUri == null && s.id >= 0) items += (if (s.isVideo) tr("Erase video…", "Videoyu sil…") else tr("Erase…", "Sil…")) to {
            eraser.confirm(EraseSet.of(listOf(s)), if (s.isVideo) tr("video “${s.title}”", "video “${s.title}”") else "“${s.title}”")
        }
        menu(s.title, items)
    }

    /**
     * Android share sheet with the file itself (its MediaStore content URI, read access granted to the receiving app).
     * The app has no internet: whichever app the user picks does the sending.
     */
    private fun shareFile(s: Song) {
        val uri = s.externalUri?.let { android.net.Uri.parse(it) } ?: Library.uri(s)
        val type = runCatching { contentResolver.getType(uri) }.getOrNull() ?: if (s.isVideo) "video/*" else "audio/*"
        val send = Intent(Intent.ACTION_SEND).apply {
            this.type = type
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, s.title)
            clipData = android.content.ClipData.newUri(contentResolver, s.title, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(send, tr("Share “${s.title}”", "Paylaş: “${s.title}”"))) }
        catch (_: Exception) { toast(tr("No app to share with", "Paylaşacak uygulama yok")) }
    }

    fun addToPlaylist(songs: List<Song>) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += tr("＋ New playlist…", "＋ Yeni çalma listesi…") to { newPlaylist(songs) }
        for (p in Store.playlists) items += p.name to { Store.addToPlaylist(p, songs); toast(tr("Added to ${p.name}", "Eklendi: ${p.name}")); render() }
        val what = if (songs.size == 1) "“${songs[0].title}”" else tr("${songs.size} songs", "${songs.size} şarkı")
        menu(tr("Add $what to", "Ekle: $what"), items)
    }

    private fun newPlaylist(songs: List<Song>?) {
        askText(tr("New playlist", "Yeni çalma listesi"), "", tr("Create", "Oluştur")) { name ->
            val p = Store.newPlaylist(name)
            if (songs != null) { Store.addToPlaylist(p, songs); toast(tr("Added to $name", "Eklendi: $name")) }
            render()
        }
    }

    private fun playlistMenu(p: Playlist) {
        menu(p.name, listOf(
            tr("Rename", "Yeniden adlandır") to { askText(tr("Rename playlist", "Listeyi yeniden adlandır"), p.name) { p.name = it; Store.savePlaylists(); if (drill.isNotEmpty()) drill[drill.size - 1] = Drill(it, drill.last().rows); render() } },
            tr("Edit order / remove songs", "Sırayı düzenle / şarkı kaldır") to { if (drill.isEmpty()) openPlaylist(p); editPlaylist = p; render() },
            tr("Erase songs in playlist (files)…", "Listedeki şarkıları sil (dosyalar)…") to {
                val lib = Library.index
                eraser.confirm(EraseSet.of(p, lib.byId, lib.byPath), tr("the songs in playlist “${p.name}” (the playlist stays)", "“${p.name}” listesindeki şarkılar (liste kalır)"), strong = true)
            },
            tr("Delete playlist (keeps songs)", "Listeyi sil (şarkılar kalır)") to {
                confirm(tr("Delete “${p.name}”?", "“${p.name}” silinsin mi?"), tr("Only the playlist is deleted, not the songs.", "Yalnızca liste silinir, şarkılar silinmez."), tr("Delete", "Sil")) {
                    Store.playlists.remove(p); Store.savePlaylists()
                    if (drill.isNotEmpty()) popDrill() else render()
                }
            },
        ))
    }

    private fun mainMenu() {
        menu(null, listOf(
            tr("Organize library…", "Kitaplığı düzenle…") to { openOrganizer() },
            tr("Trash", "Çöp kutusu") to { openTrash() },
            tr("Sleep timer", "Uyku zamanlayıcısı") to { sleepMenu() },
            tr("Playback speed: ", "Oynatma hızı: ") + Speeds.label(Player.speed) to { speedMenu() },
        ) + (if (pipSupported) listOf(
            (if (pipAuto) tr("Picture-in-picture on Home: on", "Ana ekranda pencere içinde pencere: açık")
            else tr("Picture-in-picture on Home: off", "Ana ekranda pencere içinde pencere: kapalı")) to {
                Store.prefs.edit().putBoolean("pip_auto", !pipAuto).apply()
                updatePip()
                toast(if (pipAuto) tr("A playing video goes into a small window when you leave the app", "Uygulamadan çıkınca oynayan video küçük pencerede devam eder")
                else tr("Videos play as audio when you leave the app", "Uygulamadan çıkınca videolar ses olarak devam eder"))
            },
        ) else emptyList()) + listOf(
            tr("Rescan music", "Müziği yeniden tara") to { loadLibrary(); toast(tr("Rescanning…", "Yeniden taranıyor…")) },
            tr("Clear listening history", "Dinleme geçmişini temizle") to {
                confirm(tr("Clear listening history?", "Dinleme geçmişi temizlensin mi?"),
                    tr("Recently listened and Most played start over. Songs and playlists stay.", "Son dinlenenler ve En çok çalınanlar sıfırlanır. Şarkılar ve çalma listeleri kalır."),
                    tr("Clear", "Temizle")) { Store.clearHistory(); render() }
            },
        ) + (if (Build.VERSION.SDK_INT >= 31 && !Eraser.canManage(this)) listOf(
            tr("Media management (erase without asking)…", "Medya yönetimi (sormadan sil)…") to { runCatching { startActivity(Eraser.manageMediaIntent(this)) }; Unit },
        ) else emptyList()) + (if (Build.VERSION.SDK_INT >= 33 && Library.hasPermission(this) && !Library.hasVideoPermission(this)) listOf(
            tr("Show Plain Youtube videos…", "Plain Youtube videolarını göster…") to { requestPermissions(arrayOf(Library.VIDEO_PERMISSION), REQ_VIDEO) },
        ) else emptyList()) + listOf(
            tr("About", "Hakkında") to {
                confirm("LocalMusic", tr("Plays only music files stored on this phone.\n\nNo internet permission: the app cannot go online.\nNo background polling: it only works while music plays or while you look at it.\n\n${Library.index.songs.size} songs in the library.",
                    "Yalnızca bu telefonda kayıtlı müzik dosyalarını çalar.\n\nİnternet izni yok: uygulama çevrimiçi olamaz.\nArka planda yoklama yok: yalnızca müzik çalarken ya da siz ona bakarken çalışır.\n\nKitaplıkta ${Library.index.songs.size} şarkı var."), tr("OK", "Tamam")) {}
            },
            PlainLang.label() to { PlainLang.choose(this, menuBtn) { onLanguageChanged() } },
            PlainUpdate.label(this) to { PlainUpdate.check(this) },
        ))
    }

    /**
     * After a language change: rebuild the library index (it bakes in labels such as "Various artists") and recreate the
     * activity so every string is built again. Playback lives in Player/PlaybackService and goes on; the tab and library
     * section come back from prefs (onStop saves them), an opened sub-page (artist, album...) closes.
     */
    private fun onLanguageChanged() {
        Library.relabel()
        if (Player.playing) PlaybackService.sync(this) // notification buttons in the new language
        recreate()
    }

    fun sleepMenu() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (Player.sleepAt > 0 || Player.sleepEndOfSong) items += tr("Turn off timer", "Zamanlayıcıyı kapat") to { Player.setSleepMinutes(0); toast(tr("Sleep timer off", "Uyku zamanlayıcısı kapalı")) }
        for (m in listOf(15, 30, 45, 60, 90)) items += tr("$m minutes", "$m dakika") to { Player.setSleepMinutes(m); toast(tr("Pausing in $m min", "$m dk sonra duraklar")) }
        items += tr("End of this song", "Bu şarkının sonunda") to { Player.setSleepEndOfSong(); toast(tr("Pausing after this song", "Bu şarkıdan sonra duraklar")) }
        val title = when {
            Player.sleepAt > 0 -> Text.time(Player.sleepAt - android.os.SystemClock.elapsedRealtime()).let { tr("Sleep timer: $it left", "Uyku zamanlayıcısı: $it kaldı") }
            Player.sleepEndOfSong -> tr("Sleep timer: end of song", "Uyku zamanlayıcısı: şarkı sonu")
            else -> tr("Sleep timer", "Uyku zamanlayıcısı")
        }
        menu(title, items)
    }

    // --- overlays ----------------------------------------------------------------------------

    fun openNowPlaying() {
        if (Player.current == null) return
        hideKeyboard()
        if (nowPlaying != null) return
        val v = NowPlayingView(this)
        nowPlaying = v
        applyInsets(v)
        root.addView(v, lpF(MATCH, MATCH))
        if (started) v.watchVolume(true)
        v.update(); tick()
    }

    fun closeNowPlaying() {
        nowPlaying?.let { it.dropVideo(); it.watchVolume(false); root.removeView(it) }
        nowPlaying = null
        updatePip()
    }

    // --- playback speed ------------------------------------------------------------------------

    fun speedMenu() {
        menu(tr("Playback speed", "Oynatma hızı"), Speeds.ALL.map { sp ->
            (if (sp == Player.speed) "✓ " else "    ") + Speeds.label(sp) + (if (sp == 1f) tr("  (normal)", "  (normal)") else "") to { Player.setSpeed(sp) }
        })
    }

    // --- picture-in-picture (videos with the picture on screen) ----------------------------------

    val pipSupported by lazy { packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    private var leftPip = false // PiP just ended: expanded (onResume follows) or closed (onStop follows)
    private val pipAuto get() = Store.prefs.getBoolean("pip_auto", false)

    /** Only the picture of a video in now playing goes into PiP; audio never does. */
    private fun pipWanted() = pipSupported && nowPlaying?.hasPicture == true

    fun enterPip() {
        if (!pipWanted()) return
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    private fun pipParams(): android.app.PictureInPictureParams {
        val s = Player.current
        val (w, h) = PipRatio.of(s?.videoW ?: 0, s?.videoH ?: 0)
        fun action(icon: Int, label: String, cmd: String) = android.app.RemoteAction(
            android.graphics.drawable.Icon.createWithResource(this, icon), label, label, ControlReceiver.intent(this, cmd))
        val b = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational(w, h))
            .setActions(listOf(
                action(R.drawable.mus_ic_back10, tr("Back 10 s", "10 sn geri"), ControlReceiver.BACK),
                if (Player.playing) action(R.drawable.mus_ic_pause, tr("Pause", "Duraklat"), ControlReceiver.TOGGLE)
                else action(R.drawable.mus_ic_play, tr("Play", "Oynat"), ControlReceiver.TOGGLE),
                action(R.drawable.mus_ic_fwd10, tr("Forward 10 s", "10 sn ileri"), ControlReceiver.FWD),
            ))
        nowPlaying?.pictureRect()?.let { b.setSourceRectHint(it) }
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(pipAuto && pipWanted() && Player.playing)
        return b.build()
    }

    /** Keeps PiP's play/pause icon and auto-enter in step with playback. Nothing to do for audio without auto-enter. */
    private fun updatePip() {
        if (!pipSupported) return
        if (!isInPictureInPictureMode && !(pipAuto && Build.VERSION.SDK_INT >= 31)) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    @Deprecated("Android 11 has no auto-enter; 12+ uses setAutoEnterEnabled")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < 31 && pipAuto && Player.playing) enterPip()
    }

    override fun onPictureInPictureModeChanged(inPip: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(inPip, newConfig)
        if (!inPip) leftPip = true
        nowPlaying?.setPip(inPip)
        // only the picture in the small window: hide whatever lies over now playing (queue, menus' overlays)
        overlays.forEach { it.visibility = if (inPip) View.GONE else View.VISIBLE }
    }

    fun showOverlay(v: View) {
        hideKeyboard()
        applyInsets(v)
        overlays += v
        root.addView(v, lpF(MATCH, MATCH))
    }

    fun closeOverlay(v: View) {
        overlays -= v
        root.removeView(v)
        if (v === organizer) organizer = null
        if (v === trash) trash = null
        if (v is QueueView) nowPlaying?.update()
    }

    fun openQueue() = showOverlay(QueueView(this))

    private fun openOrganizer() {
        if (!Library.hasPermission(this)) { toast(tr("Allow music access first", "Önce müzik erişimine izin verin")); return }
        organizer = OrganizerView(this).also { showOverlay(it) }
    }

    private fun openTrash() {
        trash = TrashView(this).also { showOverlay(it) }
    }

    // --- player updates ----------------------------------------------------------------------

    private fun onPlayerChanged() {
        val s = Player.current
        if (s == null) mini.visibility = View.GONE
        else {
            mini.visibility = if (selection != null) View.GONE else View.VISIBLE
            if (miniFor != s.id) {
                miniFor = s.id
                miniTitle.text = s.title
                miniSub.text = s.shownArtist
                miniArt.bind(s.id, dp(42) * 2)
            }
            miniPlay.setImageResource(if (Player.playing) R.drawable.mus_ic_pause else R.drawable.mus_ic_play)
        }
        if (nowPlaying != null && s == null) closeNowPlaying()
        nowPlaying?.update()
        updatePip()
        overlays.forEach { (it as? QueueView)?.refresh() }
        if (tab == HOME && drill.isEmpty() && query.isEmpty() && shownHistory != Store.historyVersion) render()
        adapter.notifyDataSetChanged() // current-song highlight
        tick()
        handler.removeCallbacks(ticker)
        if (resumed && Player.playing) handler.postDelayed(ticker, 500)
    }

    private fun tick() {
        val pos = Player.position()
        val dur = Player.duration()
        miniProgress.set(pos, dur)
        nowPlaying?.tick(pos, dur)
    }

    // --- list rows -----------------------------------------------------------------------------

    /** Drag-to-reorder for playlist edit mode and the queue. */
    inner class Dragger(private val lv: ListView, private val rows: () -> MutableList<Row>, private val notify: () -> Unit, private val commit: (from: Int, to: Int) -> Unit) {
        private var at = -1
        private var start = -1

        fun handleTouch(handle: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    at = lv.getPositionForView(handle)
                    start = at
                    lv.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (at < 0) return true
                    val loc = IntArray(2); lv.getLocationOnScreen(loc)
                    val y = (e.rawY - loc[1]).toInt()
                    val target = lv.pointToPosition(lv.width / 2, y.coerceIn(1, lv.height - 1))
                    val list = rows()
                    if (target >= 0 && target != at && list.getOrNull(target) is SongRow) {
                        val r = list.removeAt(at); list.add(target, r)
                        at = target
                        notify()
                    }
                    val edge = context().dp(56)
                    if (y < edge) lv.smoothScrollBy(-context().dp(24), 40) else if (y > lv.height - edge) lv.smoothScrollBy(context().dp(24), 40)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (at >= 0 && start >= 0 && at != start) {
                        val first = rows().indexOfFirst { it is SongRow }
                        commit(start - first, at - first)
                    }
                    at = -1; start = -1
                }
            }
            return true
        }

        private fun context() = this@MainActivity
    }

    private val mainDragger by lazy {
        Dragger(list, { adapter.rows as MutableList<Row> }, { adapter.notifyDataSetChanged() }) { from, to ->
            val p = editPlaylist ?: return@Dragger
            val idx = presentEntries(p)
            if (from !in idx.indices || to !in idx.indices) return@Dragger
            val e = p.entries.removeAt(idx[from]); p.entries.add(idx[to].coerceAtMost(p.entries.size), e)
            Store.savePlaylists()
        }
    }

    inner class RowAdapter(var dragger: (() -> Dragger)? = null) : BaseAdapter() {
        var rows: List<Row> = emptyList()
            set(v) { field = if (v is MutableList<Row>) v else v.toMutableList() }

        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 8
        override fun getItemViewType(position: Int) = when (rows[position]) {
            is SongRow -> 0
            is GroupRow -> 1
            is HeaderRow -> 2
            is SectionRow -> 3
            is EmptyRow -> 4
            is AlbumPairRow -> 5
            is ShelfHeaderRow -> 6
            is ShelfRow -> 7
        }
        override fun isEnabled(position: Int) = rows[position].let { it is SongRow || it is GroupRow || it is EmptyRow }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = when (val r = rows[position]) {
            is SongRow -> songView(r, convertView as? SongHolder ?: SongHolder(this@MainActivity), dragger?.invoke() ?: mainDragger)
            is GroupRow -> groupView(r, convertView as? GroupHolder ?: GroupHolder(this@MainActivity))
            is HeaderRow -> headerView(r)
            is AlbumPairRow -> albumPairView(r, convertView as? AlbumPairHolder ?: AlbumPairHolder(this@MainActivity))
            is ShelfHeaderRow -> shelfHeaderView(r)
            is ShelfRow -> shelfView(r, convertView as? ShelfHolder ?: ShelfHolder(this@MainActivity))
            is SectionRow -> text(r.text, 17f, C.TEXT, bold = true).apply { setPadding(dp(16), dp(18), dp(16), dp(6)) }
            is EmptyRow -> LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(48), dp(24), dp(24))
                addView(text(r.text, 15f, C.DIM, lines = 0).apply { gravity = Gravity.CENTER })
                r.action?.let { (label, fn) -> addView(pill(label, C.ACCENT, 0xFF000000.toInt()) { fn() }, lpL(WRAP, WRAP).apply { topMargin = dp(16) }) }
            }
        }
    }

    private fun shelfHeaderView(r: ShelfHeaderRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(16), dp(14), dp(8), dp(6))
        val t = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
        t.addView(text(r.title, 18f, C.TEXT, bold = true))
        r.sub?.let { t.addView(text(it, 12f, C.DIM)) }
        addView(t, lpL(0, WRAP, 1f))
        r.onPlay?.let { fn -> addView(icon(R.drawable.mus_ic_play, 40, 10, C.TEXT, tr("Play all", "Tümünü oynat")) { fn() }) }
        r.onSeeAll?.let { fn -> addView(text(tr("See all", "Tümünü gör"), 13f, C.ACCENT, bold = true).apply { setPadding(dp(10), dp(8), dp(8), dp(8)); setOnClickListener { fn() } }) }
    }

    class ShelfCard(ctx: MainActivity) : LinearLayout(ctx) {
        val art = SquareArtView(ctx, ctx.dpf(8))
        val title = ctx.text("", 13f, C.TEXT, bold = true)
        val sub = ctx.text("", 11.5f, C.DIM, lines = 2)

        init {
            orientation = VERTICAL
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(6))
            addView(art, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(4) })
            addView(sub)
        }
    }

    class ShelfHolder(ctx: MainActivity) : HorizontalScrollView(ctx) {
        val strip = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ctx.dp(12), 0, ctx.dp(12), ctx.dp(4)) }
        val cards = ArrayList<ShelfCard>()

        init {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        }
    }

    private fun shelfView(r: ShelfRow, h: ShelfHolder): View {
        if (h.tag != r.key) { h.tag = r.key; h.scrollTo(0, 0) }
        while (h.cards.size < r.cards.size) {
            val c = ShelfCard(this)
            h.cards += c
            h.strip.addView(c, lpL(dp(116), WRAP).apply { rightMargin = dp(10) })
        }
        h.cards.forEachIndexed { i, c ->
            val card = r.cards.getOrNull(i)
            if (card == null) { c.visibility = View.GONE; return@forEachIndexed }
            c.visibility = View.VISIBLE
            c.title.text = card.title
            c.sub.text = card.sub
            c.art.radius = if (card.round) dpf(58) else dpf(8)
            if (card.mosaic) c.art.bindMosaic(card.artSongs, dp(116)) else c.art.bindAny(card.artSongs, dp(116))
            c.background = ripple(null)
            c.setOnClickListener { card.onClick() }
            c.setOnLongClickListener { card.onLong?.invoke(); card.onLong != null }
        }
        return h
    }

    private fun headerView(r: HeaderRow): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(10), dp(16), dp(10))
        addView(text(r.title, 24f, C.TEXT, bold = true, lines = 2))
        r.sub?.let { addView(text(it, 13f, C.DIM, lines = 3).apply { setPadding(0, dp(2), 0, 0) }) }
        if (r.actions.isNotEmpty()) {
            val row = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            r.actions.forEachIndexed { i, (label, fn) ->
                val on = i == r.accent
                row.addView(pill(label, C.BG, if (on) C.ACCENT else C.TEXT, if (on) C.ACCENT else C.OUTLINE_BTN) { fn() }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
            }
            addView(HorizontalScrollView(this@MainActivity).apply { isHorizontalScrollBarEnabled = false; addView(row) })
        }
    }

    class SongHolder(ctx: MainActivity) : LinearLayout(ctx) {
        val handle = ctx.icon(R.drawable.mus_ic_drag, 40, 9, C.LABEL, tr("Drag to reorder", "Sıralamak için sürükleyin"))
        val art = ArtView(ctx, ctx.dpf(4))
        val num = ctx.text("", 14f, C.LABEL).apply { gravity = Gravity.CENTER }
        val title = ctx.text("", 15f)
        val sub = ctx.text("", 13f, C.DIM)
        val dur = ctx.text("", 12f, C.LABEL)
        val more = ctx.icon(R.drawable.mus_ic_more, 40, 10, C.LABEL, tr("More", "Diğer"))
        val remove = ctx.icon(R.drawable.mus_ic_close, 40, 11, C.LABEL, tr("Remove", "Kaldır"))
        private var badge: TextView? = null // "VIDEO" / "CACHED", created the first time this row needs one

        fun showBadge(label: String?) {
            val on = label != null
            if (on && badge == null) badge = context.text("", 9.5f, C.LABEL, bold = true).apply {
                background = context.outline(context.dpf(4), C.OUTLINE_BTN)
                setPadding(context.dp(5), context.dp(1), context.dp(5), context.dp(1))
            }.also { addView(it, indexOfChild(dur), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = context.dp(8) }) }
            badge?.let { it.visibility = if (on) View.VISIBLE else View.GONE; if (on) it.text = label }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(62)
            setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(2), ctx.dp(5))
            addView(handle)
            addView(art, LinearLayout.LayoutParams(ctx.dp(48), ctx.dp(48)))
            addView(num, LinearLayout.LayoutParams(ctx.dp(32), WRAP))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dp(12), 0, ctx.dp(6), 0) }
            col.addView(title); col.addView(sub)
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(dur)
            addView(more)
            addView(remove)
        }
    }

    private fun songView(r: SongRow, h: SongHolder, dragger: Dragger): View {
        val s = r.song
        val isCurrent = Player.current?.id == s.id && (r.mode != SongRow.Mode.QUEUE || r.index == Player.queue.pos)
        h.title.text = s.title
        h.title.setTextColor(if (isCurrent) C.ACCENT else C.TEXT)
        h.sub.text = if (r.inAlbum) s.shownArtist else s.subtitle
        h.dur.text = Text.time(s.durationMs)
        h.minimumHeight = dp(if (r.inAlbum) 54 else 62)
        if (r.inAlbum) {
            h.art.visibility = View.GONE
            h.num.visibility = View.VISIBLE
            h.num.text = if (s.track > 0) s.track.toString() else "·"
            h.num.setTextColor(if (isCurrent) C.ACCENT else C.LABEL)
        } else {
            h.art.visibility = View.VISIBLE
            h.num.visibility = View.GONE
            h.art.bind(s.id, dp(48) * 2)
        }
        val draggable = r.mode != SongRow.Mode.NORMAL
        h.handle.visibility = if (draggable) View.VISIBLE else View.GONE
        h.handle.setOnTouchListener(if (draggable) View.OnTouchListener { v, e -> dragger.handleTouch(v, e) } else null)
        h.more.visibility = if (draggable) View.GONE else View.VISIBLE
        h.more.setOnClickListener { songMenu(r) }
        val selecting = selection != null && r.mode == SongRow.Mode.NORMAL
        val picked = selecting && selection?.isSelected(s.id) == true
        if (selecting) { h.more.visibility = View.GONE; if (picked) h.title.setTextColor(C.ACCENT) }
        h.remove.visibility = if (draggable) View.VISIBLE else View.GONE
        h.dur.visibility = if (draggable) View.GONE else View.VISIBLE
        h.showBadge(if (draggable) null else listOfNotNull(if (s.isVideo) PlainLang.upper(tr("Video", "Video")) else null, if (s.cached) PlainLang.upper(tr("Cached", "Önbellekte")) else null).joinToString(" · ").ifEmpty { null })
        h.remove.setOnClickListener {
            when (r.mode) {
                SongRow.Mode.PLAYLIST_EDIT -> r.playlist?.let { p ->
                    presentEntries(p).getOrNull(r.index)?.let { p.entries.removeAt(it) }
                    Store.savePlaylists(); render()
                }
                SongRow.Mode.QUEUE -> Player.removeFromQueue(r.index)
                else -> {}
            }
        }
        h.background = if (picked) ripple(outline(dpf(8), C.ACCENT)) else ripple(null)
        return h
    }

    class GroupHolder(ctx: MainActivity) : LinearLayout(ctx) {
        val art = ArtView(ctx, ctx.dpf(6))
        val title = ctx.text("", 15.5f)
        val sub = ctx.text("", 13f, C.DIM)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(62)
            setPadding(ctx.dp(12), ctx.dp(5), ctx.dp(12), ctx.dp(5))
            addView(art, LinearLayout.LayoutParams(ctx.dp(52), ctx.dp(52)))
            val col = LinearLayout(ctx).apply { orientation = VERTICAL; setPadding(ctx.dp(14), 0, 0, 0) }
            col.addView(title); col.addView(sub)
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun groupView(r: GroupRow, h: GroupHolder): View {
        h.title.text = r.title
        h.sub.text = r.sub
        h.art.radius = if (r.round) dpf(26) else dpf(6)
        when {
            r.artSongs != null && r.mosaic -> h.art.bindMosaic(r.artSongs, dp(52) * 2)
            r.artSongs != null -> h.art.bindAny(r.artSongs, dp(52) * 2)
            r.artId >= 0 -> h.art.bind(r.artId, dp(52) * 2)
            else -> { h.art.tag = null; h.art.placeholder() }
        }
        h.background = ripple(null)
        return h
    }

    class AlbumCard(ctx: MainActivity) : LinearLayout(ctx) {
        val art = SquareArtView(ctx, ctx.dpf(8))
        val play = ctx.icon(R.drawable.mus_ic_play, 30, 7, 0xFF000000.toInt(), tr("Play album", "Albümü oynat"))
        val title = ctx.text("", 13f, C.TEXT, bold = true)
        val sub = ctx.text("", 11.5f, C.DIM, lines = 2)

        init {
            orientation = VERTICAL
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(4), ctx.dp(8))
            val box = FrameLayout(ctx)
            box.addView(art, lpF(MATCH, WRAP))
            play.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x33000000), oval(C.ACCENT), null)
            box.addView(play, lpF(ctx.dp(30), ctx.dp(30), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, ctx.dp(5), ctx.dp(5)) })
            addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(title, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dp(4) })
            addView(sub)
        }
    }

    class AlbumPairHolder(ctx: MainActivity) : LinearLayout(ctx) {
        val cards = List(3) { AlbumCard(ctx) }

        init {
            orientation = HORIZONTAL
            setPadding(ctx.dp(10), 0, ctx.dp(10), 0)
            for (c in cards) addView(c, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    private fun bindCard(c: AlbumCard, g: Group?) {
        if (g == null) { c.visibility = View.INVISIBLE; return }
        c.visibility = View.VISIBLE
        c.title.text = g.title
        c.sub.text = g.artist + "\n" + Text.count(g.count, "song")
        if (Packing.isPacked(g)) c.art.bindMosaic(g.songs, dp(120)) else c.art.bindAny(ArtPick.album(g.songs), dp(120))
        c.background = ripple(null)
        c.setOnClickListener { if (Packing.isPacked(g)) openPacked(g) else openAlbum(g) }
        c.setOnLongClickListener { if (Packing.isPacked(g)) groupMenu(g, K_SINGLES, strong = true) else groupMenu(g, K_ALBUM); true }
        c.play.setOnClickListener { Player.playList(g.songs.map { it.id }, 0) }
    }

    private fun albumPairView(r: AlbumPairRow, h: AlbumPairHolder): View {
        h.cards.forEachIndexed { i, c -> bindCard(c, r.albums.getOrNull(i)) }
        return h
    }

    /** Indices of playlist entries whose songs exist (the rows shown). */
    private fun presentEntries(p: Playlist): List<Int> {
        val lib = Library.index
        p.resolve(lib.byId, lib.songs.associateBy { it.path.lowercase() })
        return p.entries.indices.filter { p.entries[it].songId in lib.byId }
    }

    fun newRowAdapter(dragger: () -> Dragger) = RowAdapter(dragger)
}
