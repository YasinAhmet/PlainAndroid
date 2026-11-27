package app.plain.maps

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.ui.Ic
import app.plain.ui.Sheet
import java.util.Calendar
import java.util.concurrent.Executors

/** The Istanbul tab (and, as [AnkaraActivity] in its own process, the Ankara tab). */
open class MainActivity : Activity() {
    // ------------------------------------------------------------------ screens in the bottom sheet
    private sealed class Screen {
        class Area(val lat: Double, val lon: Double, val r: Double) : Screen()
        class StopS(val id: Int) : Screen()
        class LineS(var variant: Int, var focus: Int, var timetable: Boolean = false, var day: Int = 0) : Screen()
        class PlaceS(val id: Long) : Screen()
        /** A typed search, or a saved query pack ([pack] = its name, [q] unused). */
        class Search(val q: String, val pack: String? = null) : Screen()
        object Settings : Screen()
        object Welcome : Screen()
        /** The route finder: from/to and, once both are set, the best options. */
        object Route : Screen()
        class Legs(val opt: RouteOpt) : Screen()
    }

    /** A chosen point (route start or destination). */
    class Pt(val lat: Double, val lon: Double, val name: String)

    /** Route finder state, kept while the app runs. */
    private var from: Pt? = null
    private var to: Pt? = null
    /** 1 = the next map tap sets the start, 2 = the destination. */
    private var picking = 0
    /** Line sequence of a saved route to open once the options are found. */
    private var prefer: String? = null

    class LegInfo(
        val v: Store.Variant, val board: Store.StopR, val alight: Store.StopR, val boardIdx: Int, val alightIdx: Int,
        val next: List<Int>, val geom: List<DoubleArray>, val color: Int,
    )

    class RouteOpt(val j: Router.Journey, val legs: List<LegInfo>) {
        val key get() = legs.joinToString(">") { it.v.ref }
    }

    private lateinit var store: Store
    private lateinit var renderer: TileRenderer
    private lateinit var tiles: Tiles
    private lateinit var map: MapView
    private lateinit var gps: Gps
    private val bg = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var token = 0
    private var generation = -1

    private lateinit var ui: FrameLayout
    private lateinit var topBar: LinearLayout
    private lateinit var search: EditText
    private lateinit var clearSearch: Icon
    private lateinit var packsBtn: Icon
    /** Search filters. They last for one search session and start from "everything" each time. */
    private var searchKinds = Store.Kinds.ALL
    private var searchCats: Set<Int>? = null
    private lateinit var gpsBtn: Icon
    private lateinit var sheet: CappedLinear
    private lateinit var sheetHandle: View
    private lateinit var sheetHead: LinearLayout
    private lateinit var sheetBack: Icon
    private lateinit var sheetTitle: LinearLayout
    private lateinit var sheetBody: LinearLayout
    private lateinit var sheetScroll: ScrollView
    private val stack = ArrayList<Screen>()
    private var expanded = false
    private var gotFix = false

    private val syncListener: () -> Unit = { onSyncChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        reopenSettings = savedInstanceState?.getBoolean("lang_settings") == true
        edgeToEdge()
        store = Store(Sync.dataFile(this))
        map = MapView(this)
        renderer = TileRenderer(store, resources.displayMetrics.density, map.tilePx)
        tiles = Tiles(2, (Runtime.getRuntime().maxMemory() / 5).toInt().coerceIn(24 shl 20, 96 shl 20)) { z, x, y ->
            if (store.ready) renderer.render(z, x, y) else null
        }
        map.tiles = tiles
        Style.load(City.prefs(this))
        gps = Gps(this) { l ->
            map.setMe(l.latitude, l.longitude, l.accuracy, l.time.coerceAtMost(System.currentTimeMillis()))
            rememberFix(l.latitude, l.longitude, l.accuracy, l.time)
            if (!gotFix) {
                gotFix = true
                map.follow = true
                map.centerOn(l.latitude, l.longitude, maxOf(map.zoom, 16.0))
            }
            updateGpsBtn()
        }
        buildUi()
        val prefs = City.prefs(this)
        map.cx = Geo.mx(City.centerLon); map.cy = Geo.my(City.centerLat)
        prefs.getString("view", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.let {
            map.cx = it[0]; map.cy = it[1]; map.zoom = it[2]
        }
        // the last known position, kept across restarts (drawn grey once it is over a minute old)
        prefs.getString("fix", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 4 }?.let {
            map.setMe(it[0], it[1], it[2].toFloat(), it[3].toLong())
        }
        openStore()
    }

    private var lastSaved = 0L

    /** Set after a language change: the recreated activity opens the settings again, scrolled to the language row. */
    private var reopenSettings = false
    private var langRow: View? = null

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (stack.lastOrNull() is Screen.Settings) outState.putBoolean("lang_settings", true)
    }

    private fun rememberFix(lat: Double, lon: Double, acc: Float, time: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSaved < 5000) return
        lastSaved = now
        City.prefs(this).edit().putString("fix", "$lat,$lon,$acc,${time.coerceAtMost(now)}").apply()
    }

    private fun openStore() {
        bg.execute {
            val ok = store.open()
            renderer.clear()
            tiles.clear()
            main.post {
                generation = SyncState.generation
                map.invalidate()
                if (reopenSettings) {
                    reopenSettings = false
                    show(Screen.Settings, replaceAll = true)
                    sheetScroll.postDelayed({ langRow?.let { r -> sheetScroll.scrollTo(0, maxOf(0, r.top - dp(80))) } }, 200)
                } else if (!ok) {
                    if (stack.isEmpty() || stack.last() !is Screen.Settings) show(Screen.Welcome, replaceAll = true)
                    if (!SyncState.running && Hub.firstVisit(this, City.id)) askFirstSync()
                }
                else if (stack.lastOrNull() is Screen.Welcome) closeSheet()
            }
        }
    }

    /** First visit of a city tab without data: offer the sync once (nothing is downloaded unless the user says so). */
    /** What the sync brings for buses, in running text. */
    private fun busWhat() = if (City.timetables) tr("all ${City.operator} bus lines, stops and timetables", "tüm ${City.operator} otobüs hatları, durakları ve saatleri")
        else tr("the bus lines and stops mapped in OpenStreetMap (${City.operator} publishes no open timetables)",
            "OpenStreetMap'te çizili otobüs hatları ve durakları (${City.operator} açık saat verisi yayımlamıyor)")

    private fun busWhatAcc() = if (City.timetables) "tüm ${City.operator} otobüs hatlarını, duraklarını ve saatlerini"
        else "OpenStreetMap'te çizili otobüs hatlarını ve duraklarını"

    private fun askFirstSync() {
        android.app.AlertDialog.Builder(this)
            .setTitle(tr("Download ${City.localName} data?", "${City.localName} verisi indirilsin mi?"))
            .setMessage(tr("The ${City.localName} tab works offline after one sync: the map, places, metro/tram/ferry lines and ${busWhat()}. " +
                "It needs Wi-Fi and ${City.downloadSize} of download, and takes 10–60 minutes; you can leave the app meanwhile.",
                "${City.localName} sekmesi tek bir eşitlemeden sonra çevrimdışı çalışır: harita, yerler, metro/tramvay/vapur hatları ve ${busWhat()}. " +
                "Wi-Fi ve ${City.downloadSize} indirme gerekir; 10–60 dakika sürer, bu sırada uygulamadan çıkabilirsiniz."))
            .setPositiveButton(tr("Sync now", "Şimdi eşitle")) { _, _ -> startSync() }
            .setNegativeButton(tr("Later", "Sonra"), null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        SyncState.listen(syncListener)
        if (generation >= 0 && generation != SyncState.generation) openStore()
        onSyncChanged()
    }

    override fun onStop() {
        super.onStop()
        SyncState.unlisten(syncListener)
        // location is only ever on while the app is on screen (the last fix stays, and turns grey)
        if (gps.on) { gps.stop(); gotFix = false; updateGpsBtn() }
        City.prefs(this).edit().putString("view", "${map.cx},${map.cy},${map.zoom}").apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        tiles.shutdown()
        bg.execute { store.close() }
        bg.shutdown()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (search.hasFocus() || search.text.isNotEmpty()) {
            search.setText(""); search.clearFocus(); hideKeyboard()
            if (stack.lastOrNull() is Screen.Search) back()
            return
        }
        if (stack.isNotEmpty()) { back(); return }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    // ------------------------------------------------------------------ layout
    private fun buildUi() {
        val root = FrameLayout(this)
        root.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        ui = FrameLayout(this)
        root.addView(ui, FrameLayout.LayoutParams(MATCH, MATCH))

        // thumb row (the sheet's top row, always shown): search box, settings (the city drawers sit on top, Hub)
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(8), dp(8))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = outline(dpf(22))
            setPadding(dp(10), 0, dp(2), 0)
        }
        box.addView(Icon(this, Icon.SEARCH).apply { color = C.DIM }, lpL(dp(20), dp(20)))
        search = EditText(this).apply {
            hint = tr("Search stops, lines", "Durak, hat ara")
            setHintTextColor(C.DIM)
            setTextColor(C.TEXT)
            textSize = 15f
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(6), dp(8), dp(4), dp(8))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        box.addView(search, lpL(0, dp(42), 1f))
        clearSearch = Icon(this, Icon.CLOSE).apply {
            color = C.DIM
            visibility = View.GONE
            background = ripple(null)
            contentDescription = tr("Clear", "Temizle")
            setOnClickListener { search.setText(""); if (stack.lastOrNull() is Screen.Search) back() }
        }
        box.addView(clearSearch, lpL(dp(38), dp(38)))
        packsBtn = Icon(this, Icon.LIST).apply {
            color = C.DIM
            background = ripple(null)
            contentDescription = tr("Query packs", "Arama paketleri")
            setOnClickListener { hideKeyboard(); search.clearFocus(); packsDialog() }
        }
        box.addView(packsBtn, lpL(dp(38), dp(38)))
        topBar.addView(box, lpL(0, WRAP, 1f))
        val settings = Icon(this, Icon.SETTINGS).apply {
            background = ripple(null)
            contentDescription = tr("Settings", "Ayarlar")
            setOnClickListener { hideKeyboard(); search.clearFocus(); show(Screen.Settings) }
        }
        topBar.addView(settings, lpL(dp(42), dp(42)).apply { leftMargin = dp(2) })

        search.addTextChangedListener(object : TextWatcher {
            private val run = Runnable { doSearch() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                packsBtn.visibility = if (s.isNullOrEmpty()) View.VISIBLE else View.GONE
                main.removeCallbacks(run)
                if (!s.isNullOrBlank()) main.postDelayed(run, 220)
            }
        })

        // bottom: route finder and location buttons above the sheet
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val fabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val routeBtn = Icon(this, Icon.ROUTE).apply {
            color = C.ACCENT
            background = ripple(outlineOval(fill = C.PANEL))
            contentDescription = tr("Route", "Rota")
            setOnClickListener { hideKeyboard(); search.clearFocus(); openRoute() }
        }
        fabs.addView(routeBtn, lpL(dp(50), dp(50)).apply { rightMargin = dp(10) })
        gpsBtn = Icon(this, Icon.GPS).apply {
            color = C.DIM
            background = ripple(outlineOval(fill = C.PANEL))
            setOnClickListener { gpsTap() }
        }
        fabs.addView(gpsBtn, lpL(dp(50), dp(50)))
        bottom.addView(fabs, lpL(MATCH, WRAP).apply { rightMargin = dp(12); bottomMargin = dp(10) })
        sheet = CappedLinear(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.PANEL)
                cornerRadii = floatArrayOf(dpf(16), dpf(16), dpf(16), dpf(16), 0f, 0f, 0f, 0f)
                setStroke(dp(1), C.LINE)
            }
            isClickable = true
        }
        // grab handle: tap or swipe up to make the sheet taller, swipe down to make it small again
        val handle = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF }
            override fun onDraw(c: android.graphics.Canvas) {
                val w = dpf(36); val h = dpf(4)
                c.drawRoundRect(width / 2f - w / 2, height / 2f - h / 2, width / 2f + w / 2, height / 2f + h / 2, h, h, p)
            }
        }
        var downY = 0f
        handle.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> downY = e.rawY
                android.view.MotionEvent.ACTION_UP -> {
                    val dy = e.rawY - downY
                    expanded = when {
                        dy < -dpf(24) -> true
                        dy > dpf(24) -> false
                        else -> !expanded
                    }
                    sizeSheet()
                    v.performClick()
                }
            }
            true
        }
        sheetHandle = handle
        sheet.addView(handle, lpL(MATCH, dp(18)))
        sheet.addView(topBar, lpL(MATCH, WRAP))
        sheetHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), dp(2))
        }
        sheetBack = Icon(this, Icon.BACK).apply { background = ripple(null); setOnClickListener { back() } }
        sheetHead.addView(sheetBack, lpL(dp(36), dp(36)))
        sheetTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        sheetHead.addView(sheetTitle, lpL(0, WRAP, 1f))
        sheetHead.addView(Icon(this, Icon.CLOSE).apply { background = ripple(null); setOnClickListener { closeSheet() } }, lpL(dp(36), dp(36)))
        sheet.addView(sheetHead, lpL(MATCH, WRAP))
        sheetScroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        sheetBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, dp(10), dp(10)) }
        sheetScroll.addView(sheetBody, FrameLayout.LayoutParams(MATCH, WRAP))
        sheet.addView(sheetScroll, lpL(MATCH, WRAP))
        bottom.addView(sheet, lpL(MATCH, WRAP))
        ui.addView(bottom, lpF(MATCH, WRAP, Gravity.BOTTOM))
        showSheetContent(false)

        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePads() }

        root.setOnApplyWindowInsetsListener { _, ins ->
            val top: Int; val bot: Int; val l: Int; val r: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val sys = ins.getInsets(WindowInsets.Type.systemBars())
                top = sys.top; bot = sys.bottom; l = sys.left; r = sys.right
            } else {
                @Suppress("DEPRECATION") run { top = ins.systemWindowInsetTop; bot = ins.systemWindowInsetBottom; l = ins.systemWindowInsetLeft; r = ins.systemWindowInsetRight }
            }
            ui.setPadding(l, top, r, 0)
            navBottom = bot
            imeBottom = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.ime()).bottom else 0
            applyInsets()
            sizeSheet()
            WindowInsets.CONSUMED
        }
        setContentView(Hub.wrap(this, City.id, root, Hub.Style.FULL))

        map.onTap = { lat, lon, _, _ -> mapTap(lat, lon) }
        map.onHold = { lat, lon, r -> mapHold(lat, lon, r) }
        map.onUserMove = { updateGpsBtn() }
    }

    private var navBottom = 0
    private var imeBottom = 0
    private val insetBottom get() = if (imeBottom > 0) imeBottom else navBottom

    /**
     * The keyboard lifts the whole bottom part (so the search field in the sheet's top row stays above it); otherwise
     * the sheet pads itself above the navigation bar. The sheet is always shown: closed, it is just its top row.
     */
    private fun applyInsets() {
        val bottom = sheet.parent as View
        bottom.setPadding(0, 0, 0, if (imeBottom > 0) imeBottom else 0)
        // the sheet itself stops above the navigation bar, so its last rows are never under it
        sheet.setPadding(0, 0, 0, if (imeBottom > 0) 0 else navBottom)
        sheetBody.setPadding(dp(10), dp(2), dp(10), dp(12))
        main.post { updatePads() }
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun updatePads() {
        val top = ui.paddingTop
        // the sheet (always shown, at least its top row) plus the keyboard under it
        val bottomH = maxOf(sheet.height + (sheet.parent as View).paddingBottom, insetBottom)
        if (map.padTop != top || map.padBottom != bottomH) { map.padTop = top; map.padBottom = bottomH }
    }

    private fun sizeSheet() {
        val h = resources.displayMetrics.heightPixels
        val top = stack.lastOrNull()
        val frac = when {
            expanded -> 0.70
            top is Screen.Settings || top is Screen.Welcome -> 0.50
            else -> 0.34
        }
        // plus the top row (search, settings), which used to sit at the top of the screen
        sheet.maxH = (h * frac).toInt() + maxOf(topBar.height, dp(60))
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(search.windowToken, 0)
    }

    // ------------------------------------------------------------------ location button
    private fun gpsTap() {
        if (!gps.on) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
                return
            }
            startGps()
        } else if (!map.follow) {
            map.follow = true
            gotFix = false // re-centre on the next fix
        } else {
            gps.stop(); map.follow = false; gotFix = false; map.invalidate()
        }
        updateGpsBtn()
    }

    private fun startGps() {
        gotFix = false
        if (!gps.start()) {
            android.widget.Toast.makeText(this, tr("Location is switched off in Android", "Android'de konum kapalı"), android.widget.Toast.LENGTH_LONG).show()
        }
        updateGpsBtn()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 1 && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startGps()
    }

    private fun updateGpsBtn() {
        gpsBtn.color = if (gps.on) 0xFF42A5F5.toInt() else C.DIM
        gpsBtn.filled = gps.on && map.follow
    }

    // ------------------------------------------------------------------ navigation
    private fun show(s: Screen, replaceAll: Boolean = false) {
        if (replaceAll) { stack.clear(); expanded = false } // a new selection starts small
        // a new search replaces the previous one
        if (s is Screen.Search && stack.lastOrNull() is Screen.Search) stack.removeAt(stack.size - 1)
        stack.add(s)
        render()
    }

    private fun back() {
        if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        if (stack.isEmpty()) closeSheet() else render()
    }

    private fun closeSheet() {
        stack.clear()
        expanded = false
        token++
        showSheetContent(false)
        applyInsets()
        map.lines = emptyList(); map.dots = emptyList(); map.circle = null
    }

    private fun openSheet() {
        if (sheetScroll.visibility != View.VISIBLE) {
            showSheetContent(true)
            applyInsets()
        }
        sheetBack.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
    }

    /** Open: grab handle, title row and body under the top row. Closed: only the top row (search, settings). */
    private fun showSheetContent(open: Boolean) {
        val v = if (open) View.VISIBLE else View.GONE
        sheetHandle.visibility = v
        sheetHead.visibility = v
        sheetScroll.visibility = v
        topBar.setPadding(dp(8), if (open) 0 else dp(10), dp(8), dp(8))
    }

    private fun render() {
        val s = stack.lastOrNull() ?: return closeSheet()
        val t = ++token
        openSheet()
        sizeSheet()
        when (s) {
            is Screen.Area -> renderArea(s, t)
            is Screen.StopS -> renderStop(s, t)
            is Screen.LineS -> renderLine(s, t)
            is Screen.PlaceS -> renderPlace(s, t)
            is Screen.Search -> renderSearch(s, t)
            is Screen.Settings -> renderSettings()
            is Screen.Welcome -> renderWelcome()
            is Screen.Route -> renderRoute(t)
            is Screen.Legs -> renderLegs(s)
        }
    }

    /** Runs [work] off the main thread, then [done] on it unless the screen changed meanwhile. */
    private fun <T : Any> load(t: Int, work: () -> T?, done: (T) -> Unit) {
        bg.execute {
            val r = try { work() } catch (_: Exception) { null }
            main.post { if (t == token && r != null) done(r) }
        }
    }

    private fun header(title: CharSequence, sub: CharSequence?, badge: View? = null) {
        sheetTitle.removeAllViews()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (badge != null) row.addView(badge, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        row.addView(text(title, 16f, bold = true, lines = 1), lpL(0, WRAP, 1f))
        sheetTitle.addView(row)
        if (!sub.isNullOrEmpty()) sheetTitle.addView(text(sub, 12.5f, C.DIM, lines = 1))
    }

    private fun body(vararg v: View) {
        sheetBody.removeAllViews()
        for (x in v) sheetBody.addView(x)
        sheetScroll.scrollTo(0, 0)
    }

    private fun note(s: String, color: Int = C.DIM) = text(s, 13f, color).apply { setPadding(dp(6), dp(8), dp(6), dp(8)) }

    private fun loading() = body(note(tr("Loading…", "Yükleniyor…")))

    // ------------------------------------------------------------------ time helpers
    private class Now(val sec: Int, val today: Int, val yesterday: Int)

    private fun now(): Now {
        val c = Calendar.getInstance()
        val sec = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
        val today = Times.dayBit(c.get(Calendar.DAY_OF_WEEK))
        val yest = if (today == 1) 1 shl 6 else today shr 1
        return Now(sec, today, yest)
    }

    // ------------------------------------------------------------------ map taps
    private fun mapTap(lat: Double, lon: Double) {
        if (search.hasFocus()) { search.clearFocus(); hideKeyboard() }
        if (!store.ready) { show(Screen.Welcome, replaceAll = true); return }
        if (picking != 0 && stack.lastOrNull() is Screen.Route) {
            val which = picking
            picking = 0
            bg.execute {
                val p = pointNamed(lat, lon)
                main.post { if (which == 1) from = p else to = p; render() }
            }
            return
        }
        val mpp = map.metersPerPx()
        val tol = dpf(20) * mpp
        val z = map.zoom
        val t = ++token
        load(t, {
            val stop = if (z >= 14.5) store.stopsNear(lat, lon, tol, 1).firstOrNull() else null
            val poi = if (z >= 16.5) store.poisNear(lat, lon, tol * 0.9, 1).firstOrNull() else null
            Pair(stop, poi)
        }) { (stop, poi) ->
            when {
                stop != null && (poi == null || stop.second <= poi.second) -> show(Screen.StopS(stop.first.id), replaceAll = true)
                poi != null -> show(Screen.PlaceS(poi.first.id), replaceAll = true)
                else -> show(Screen.Area(lat, lon, MapView.HOLD_MIN_M), replaceAll = true)
            }
        }
    }

    /** Press and hold: lines within the circle that grew while the finger was down (100–400 m). */
    private fun mapHold(lat: Double, lon: Double, r: Double) {
        if (search.hasFocus()) { search.clearFocus(); hideKeyboard() }
        if (!store.ready) { show(Screen.Welcome, replaceAll = true); return }
        if (picking != 0 && stack.lastOrNull() is Screen.Route) { mapTap(lat, lon); return }
        show(Screen.Area(lat, lon, r), replaceAll = true)
    }

    // ------------------------------------------------------------------ nearby lines (area & place)
    private class Near(val v: Store.Variant, val stop: Store.StopR, val idx: Int, val dist: Double, val next: List<Int>, val geom: List<DoubleArray>)

    private fun nearby(lat: Double, lon: Double, r: Double): List<Near> {
        val stops = store.stopsNear(lat, lon, r, 150)
        val byId = stops.associateBy { it.first.id }
        val serving = store.serving(byId.keys)
        val best = HashMap<Int, IntArray>() // variant → (stop, idx)
        for (sv in serving) {
            val v = store.variant(sv[1]) ?: continue
            if (sv[2] == v.stops.size - 1 && v.stops.size > 1) continue // last stop: nothing departs
            val cur = best[sv[1]]
            if (cur == null || byId[sv[0]]!!.second < byId[cur[0]]!!.second) best[sv[1]] = intArrayOf(sv[0], sv[2])
        }
        val n = now()
        val rows = best.mapNotNull { (vid, si) ->
            val v = store.variant(vid) ?: return@mapNotNull null
            val st = byId[si[0]] ?: return@mapNotNull null
            val next = Times.next(store.trips(vid), v.dists, si[1], n.sec, n.today, n.yesterday, 3)
            Near(v, st.first, si[1], st.second, next, emptyList())
        }
        // one row per line and direction: the nearest stop, else the variant with the next departure
        val rows2 = rows.groupBy { it.v.line.toString() + "|" + it.v.headsign }
            .map { (_, l) -> l.minWith(compareBy({ it.dist.toInt() / 60 }, { it.next.firstOrNull() ?: Int.MAX_VALUE }, { -it.v.ntrips })) }
            .sortedWith(compareBy({ if (it.v.mode == Mode.BUS) 1 else 0 }, { (it.dist / 50).toInt() }, { it.v.ref.length }, { it.v.ref }))
            .take(60)
        return rows2.mapIndexed { i, r -> Near(r.v, r.stop, r.idx, r.dist, r.next, if (i < 24) store.geometry(r.v).map { Pieces.merc(it) } else emptyList()) }
    }

    private fun lineColor(v: Store.Variant, i: Int) = if (v.mode == Mode.BUS) C.PALETTE[i % C.PALETTE.size] else v.color

    private fun nearRows(rows: List<Near>, now: Int, fromLat: Double, fromLon: Double): List<View> {
        if (rows.isEmpty()) return listOf(note(tr("No lines stop within this circle. Press and hold the map for a bigger circle (up to 400 m), or tap closer to a street.",
            "Bu dairede duran hat yok. Daha büyük daire için haritaya basılı tutun (400 m'ye kadar) ya da bir sokağa daha yakın dokunun.")))
        return rows.mapIndexed { i, r ->
            val col = lineColor(r.v, i)
            // tap: the line; hold: the stop named under it (all its lines)
            row(badge(r.v.ref, col), "→ " + r.v.headsign.ifEmpty { r.v.lineName },
                warn(r.v) + "${r.stop.name} · ${Fmt.dist(r.dist)}" + times(r.next, now, r.v), onLong = { show(Screen.StopS(r.stop.id)) }) {
                show(Screen.LineS(r.v.id, r.stop.id))
            }
        }
    }

    /** "⚠ " when İETT has a current announcement for the line (from Situational sync). */
    private fun warn(v: Store.Variant) = if (v.mode == Mode.BUS && store.noticed().contains(v.ref)) "⚠ " else ""

    /** "Route from here" / "Route to here" for a stop, place or tapped point. */
    private fun routeButtons(p: Pt): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(6)) }
        bar.addView(button(tr("Route from here", "Buradan rota")) { from = p; prefer = null; openRoute() }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        bar.addView(button(tr("Route to here", "Buraya rota")) { to = p; prefer = null; openRoute() }, lpL(WRAP, WRAP))
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    private fun times(next: List<Int>, now: Int, v: Store.Variant): String = when {
        v.ntrips == 0 -> if (v.mode == Mode.BUS) "" else tr(" · no timetable offline", " · çevrimdışı saat yok")
        next.isEmpty() -> tr(" · no more today", " · bugün sefer kalmadı")
        else -> " · " + next.joinToString(", ") { Times.mins(it, now) }
    }

    private fun showWeb(rows: List<Near>, focusX: Double, focusY: Double) {
        val lines = ArrayList<MapView.Line>()
        rows.forEachIndexed { i, r -> if (r.geom.isNotEmpty()) lines.add(MapView.Line(r.geom, lineColor(r.v, i), 2.4f, casing = false)) }
        map.lines = lines.reversed() // nearest on top
        val dots = rows.map { it.stop }.distinctBy { it.id }.map { MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFF101214.toInt(), 3.2f) }
        map.dots = dots + MapView.Dot(focusX, focusY, C.HIGHLIGHT, 4.5f, 0xFF000000.toInt())
    }

    private fun renderArea(s: Screen.Area, t: Int) {
        header(tr("Lines near here", "Yakındaki hatlar"), tr("Within ${Fmt.dist(s.r)} · tap a line to see its route and stops",
            "${Fmt.dist(s.r)} içinde · güzergâh ve duraklar için bir hatta dokunun"))
        loading()
        map.circle = doubleArrayOf(Geo.mx(s.lon), Geo.my(s.lat), s.r)
        map.lines = emptyList(); map.dots = emptyList()
        val n = now()
        load(t, { nearby(s.lat, s.lon, s.r) }) { rows ->
            showWeb(rows, Geo.mx(s.lon), Geo.my(s.lat))
            header(tr("Lines near here", "Yakındaki hatlar"), tr("${rows.size} lines and directions within ${Fmt.dist(s.r)}",
                "${Fmt.dist(s.r)} içinde ${rows.size} hat ve yön"))
            body(routeButtons(Pt(s.lat, s.lon, rows.firstOrNull()?.stop?.name?.let { tr("Near $it", "$it yakını") } ?: tr("Point on map", "Haritada nokta"))), *nearRows(rows, n.sec, s.lat, s.lon).toTypedArray())
        }
    }

    // ------------------------------------------------------------------ stop
    private class StopData(val stop: Store.StopR, val rows: List<Triple<Store.Variant, Int, List<Int>>>, val geoms: List<List<DoubleArray>>, val notices: List<Store.Notice>)

    private fun renderStop(s: Screen.StopS, t: Int) {
        header(tr("Stop", "Durak"), null)
        loading()
        map.circle = null
        val n = now()
        load(t, {
            val st = store.stop(s.id) ?: return@load null
            val rows = store.serving(listOf(s.id)).mapNotNull { sv ->
                val v = store.variant(sv[1]) ?: return@mapNotNull null
                if (sv[2] == v.stops.size - 1 && v.stops.size > 1) return@mapNotNull null
                Triple(v, sv[2], Times.next(store.trips(v.id), v.dists, sv[2], n.sec, n.today, n.yesterday, 4))
            }.groupBy { it.first.line.toString() + "|" + it.first.headsign }
                .map { (_, l) -> l.minWith(compareBy({ it.third.firstOrNull() ?: Int.MAX_VALUE }, { -it.first.ntrips })) }
                .sortedWith(compareBy({ it.third.firstOrNull() ?: Int.MAX_VALUE }, { it.first.ref }))
            StopData(st, rows, rows.take(24).map { r -> store.geometry(r.first).map { Pieces.merc(it) } },
                if (st.code.isNotEmpty()) store.notices("durak:" + st.code) else emptyList())
        }) { d ->
            val st = d.stop
            val nLines = d.rows.map { it.first.line }.distinct().size
            val sub = (if (st.mode == Mode.BUS) tr("Bus stop", "Otobüs durağı") + (if (st.code.isNotEmpty()) " ${st.code}" else "")
                else tr(Mode.name(st.mode) + " station", if (st.mode == Mode.FERRY) "İskele" else Mode.name(st.mode) + " istasyonu")) +
                tr(" · $nLines lines", " · $nLines hat")
            header(st.name, sub)
            val lines = ArrayList<MapView.Line>()
            d.rows.forEachIndexed { i, r -> if (i < d.geoms.size) lines.add(MapView.Line(d.geoms[i], lineColor(r.first, i), 2.4f, casing = false)) }
            map.lines = lines.reversed()
            val x = Geo.mx(st.lon); val y = Geo.my(st.lat)
            map.dots = listOf(MapView.Dot(x, y, C.HIGHLIGHT, 5f, 0xFF000000.toInt()))
            if (map.zoom < 15) map.centerOn(st.lat, st.lon, 15.5) else map.invalidate()
            val views = ArrayList<View>()
            views.add(routeButtons(Pt(st.lat, st.lon, st.name)))
            for (nt in d.notices) views.add(text("⚠ ${nt.at}\n${nt.msg}", 13f, 0xFFFFCC80.toInt()).apply {
                setPadding(dp(10), dp(8), dp(10), dp(8)); background = outline(dpf(8), 0x99FF9800.toInt())
                layoutParams = lpL(MATCH, WRAP).apply { bottomMargin = dp(6) }
            })
            if (d.rows.isEmpty()) views.add(note(tr("No departures from this stop in the timetable.", "Saat çizelgesinde bu duraktan kalkış yok.")))
            d.rows.forEachIndexed { i, r ->
                val v = r.first
                views.add(row(badge(v.ref, lineColor(v, i)), "→ " + v.headsign.ifEmpty { v.lineName }, warn(v) + times(r.third, n.sec, v).removePrefix(" · ").ifEmpty { v.lineName }) {
                    show(Screen.LineS(v.id, st.id))
                })
            }
            body(*views.toTypedArray())
        }
    }

    // ------------------------------------------------------------------ line
    private class LineData(val v: Store.Variant, val others: List<Store.Variant>, val stops: Map<Int, Store.StopR>, val geom: List<DoubleArray>, val trips: IntArray, val notices: List<Store.Notice>)

    private fun renderLine(s: Screen.LineS, t: Int) {
        header(tr("Line", "Hat"), null)
        loading()
        map.circle = null
        val n = now()
        load(t, {
            val v = store.variant(s.variant) ?: return@load null
            LineData(v, store.variantsOfLine(v.line), store.stops(v.stops), store.geometry(v).map { Pieces.merc(it) }, store.trips(v.id),
                if (v.mode == Mode.BUS) store.notices(v.ref) else emptyList())
        }) { d ->
            val v = d.v
            val col = if (v.mode == Mode.BUS) C.HIGHLIGHT else v.color
            val tripsToday = run { var k = 0; var i = 0; while (i < d.trips.size) { if (d.trips[i] and n.today != 0) k++; i += 3 }; k }
            header("→ " + v.headsign.ifEmpty { v.lineName }, v.lineName + " · " + Mode.name(v.mode) +
                (if (v.mode == Mode.BUS && v.ntrips > 0) tr(" · $tripsToday trips today", " · bugün $tripsToday sefer") else ""), badge(v.ref, col))
            // map: the route, its stops, the focused stop
            map.lines = listOf(MapView.Line(d.geom, col, 4.5f))
            val dots = ArrayList<MapView.Dot>()
            for (sid in v.stops) d.stops[sid]?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFFFFFFFF.toInt(), 2.6f, 0xFF101214.toInt())) }
            d.stops[s.focus]?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), col, 5f, 0xFF000000.toInt(), it.name)) }
            map.dots = dots
            val fit = !s.timetable

            val views = ArrayList<View>()
            // direction / variant switch, timetable toggle
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(6)) }
            val opposite = d.others.filter { it.id != v.id && it.dir != v.dir }.maxByOrNull { it.ntrips }
                ?: d.others.firstOrNull { it.id != v.id && it.headsign != v.headsign }
            if (opposite != null) bar.addView(button(tr("⇄ Other direction", "⇄ Diğer yön")) {
                s.variant = opposite.id
                s.focus = nearestStopIn(opposite, d.stops[s.focus])
                render()
            }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
            if (d.others.size > 2) bar.addView(button(tr("Variants (${d.others.size})", "Güzergâhlar (${d.others.size})")) { chooseVariant(s, d) }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
            if (v.ntrips > 0) bar.addView(button(if (s.timetable) tr("Stops", "Duraklar") else tr("Timetable", "Saatler")) {
                s.timetable = !s.timetable
                if (s.day == 0) s.day = n.today
                render()
            })
            views.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })

            for (nt in d.notices) {
                views.add(text(tr("⚠ İETT notice", "⚠ İETT duyurusu") + " · ${nt.kind} · ${nt.at}\n${nt.msg}", 13f, 0xFFFFCC80.toInt()).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8)); background = outline(dpf(8), 0x99FF9800.toInt())
                    layoutParams = lpL(MATCH, WRAP).apply { bottomMargin = dp(6) }
                })
            }
            var focusRow: View? = null
            if (s.timetable) {
                views.addAll(timetable(s, d))
            } else {
                if (v.mode != Mode.BUS && v.ntrips == 0) views.add(note(tr("Metro, tram and ferry lines come from OpenStreetMap, without timetables.",
                    "Metro, tramvay ve vapur hatları OpenStreetMap'ten gelir; saatleri yoktur.")))
                else if (v.ntrips == 0 && !City.timetables) views.add(note(tr("${City.operator} publishes no open timetables; this line comes from OpenStreetMap.",
                    "${City.operator} açık saat verisi yayımlamıyor; bu hat OpenStreetMap'ten gelir.")))
                v.stops.forEachIndexed { i, sid ->
                    val st = d.stops[sid] ?: return@forEachIndexed
                    val next = if (v.ntrips > 0 && i < v.stops.size - 1) Times.next(d.trips, v.dists, i, n.sec, n.today, n.yesterday, 1).firstOrNull() else null
                    val r = stopRow(i, v.stops.size, st.name, next?.let { Times.clock(it) } ?: "", col, sid == s.focus) {
                        show(Screen.StopS(sid))
                    }
                    if (sid == s.focus && i > 0) focusRow = r
                    views.add(r)
                }
            }
            body(*views.toTypedArray())
            focusRow?.let { fr -> sheetScroll.post { sheetScroll.scrollTo(0, maxOf(0, fr.top - dp(80))) } }
            // fit the route once the sheet has its new height, so none of it hides behind the sheet
            if (fit) sheet.post { updatePads(); fitGeom(d.geom) }
        }
    }

    private fun nearestStopIn(v: Store.Variant, from: Store.StopR?): Int {
        if (from == null) return v.stops.firstOrNull() ?: 0
        val st = store.stops(v.stops)
        return v.stops.minByOrNull { id -> st[id]?.let { Geo.dist(from.lat, from.lon, it.lat, it.lon) } ?: Double.MAX_VALUE } ?: 0
    }

    private fun chooseVariant(s: Screen.LineS, d: LineData) {
        val sheet = Sheet(this, C.ACCENT).header(tr(d.v.ref + " variants", d.v.ref + " · güzergâhlar"), tr("Other routes this line runs", "Bu hattın diğer güzergâhları"))
        for (o in d.others) sheet.item(Ic.ROUTE, "→ ${o.headsign}", sub = tr("${o.stops.size} stops", "${o.stops.size} durak") +
            (if (o.ntrips > 0) tr(" · ${o.ntrips} trips/week", " · haftada ${o.ntrips} sefer") else "")) {
            bg.execute {
                val f = nearestStopIn(o, d.stops[s.focus])
                main.post { s.variant = o.id; s.focus = f; render() }
            }
        }
        sheet.show()
    }

    private fun fitGeom(g: List<DoubleArray>) {
        var x0 = 1.0; var y0 = 1.0; var x1 = 0.0; var y1 = 0.0
        for (p in g) { val b = Clip.bounds(p); x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1]); x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3]) }
        if (x1 > x0) map.fit(doubleArrayOf(x0, y0, x1, y1), 16.0)
    }

    private fun timetable(s: Screen.LineS, d: LineData): List<View> {
        val v = d.v
        val idx = v.stops.indexOf(s.focus).takeIf { it >= 0 && it < v.stops.size - 1 } ?: 0
        val st = d.stops[v.stops[idx]]
        val out = ArrayList<View>()
        val days = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        val sat = 1 shl 5; val sun = 1 shl 6; val weekday = 0x1F
        for ((label, mask) in listOf(tr("Weekdays", "Hafta içi") to weekday, tr("Saturday", "Cumartesi") to sat, tr("Sunday", "Pazar") to sun)) {
            val on = s.day and mask != 0
            days.addView(button(label, if (on) C.ACCENT else C.CARD, if (on) C.ACCENT else C.TEXT) {
                s.day = if (mask == weekday) 1 else mask
                render()
            }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        out.add(days)
        out.add(note(tr("Departures at ${st?.name ?: "the first stop"}", "Kalkışlar: ${st?.name ?: "ilk durak"}") +
            if (idx > 0) tr(" (estimated from the first stop's times)", " (ilk durağın saatlerinden tahmini)") else ""))
        val dayMask = if (s.day and weekday != 0 && s.day and (sat or sun) == 0) (s.day and weekday).let { if (it == 0) 1 else it } else s.day
        val times = Times.all(d.trips, v.dists, idx, dayMask)
        if (times.isEmpty()) { out.add(note(tr("No trips on this day.", "Bu gün sefer yok."))); return out }
        val byHour = times.groupBy { (it / 3600) }
        for ((h, l) in byHour.toSortedMap()) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(3), dp(6), dp(3)) }
            r.addView(text(String.format(java.util.Locale.US, "%02d", h % 24), 15f, C.ACCENT, bold = true), lpL(dp(34), WRAP))
            r.addView(text(l.joinToString("   ") { String.format(java.util.Locale.US, "%02d", (it / 60) % 60) }, 15f), lpL(0, WRAP, 1f))
            out.add(r)
        }
        return out
    }

    // ------------------------------------------------------------------ place
    private fun renderPlace(s: Screen.PlaceS, t: Int) {
        header(tr("Place", "Yer"), null)
        loading()
        map.circle = null
        val n = now()
        load(t, {
            val p = store.poi(s.id) ?: return@load null
            p to nearby(p.lat, p.lon, 400.0)
        }) { (p, rows) ->
            header(p.name.ifEmpty { p.label }, p.label)
            val tags = p.info.lines().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
            val views = ArrayList<View>()
            views.add(routeButtons(Pt(p.lat, p.lon, p.name.ifEmpty { p.label })))
            fun info(label: String, value: String?, action: (() -> Unit)? = null) {
                if (value.isNullOrBlank()) return
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(6), dp(6), dp(6))
                    if (action != null) { background = ripple(null); setOnClickListener { action() } }
                }
                r.addView(text(label, 12f, C.DIM))
                r.addView(text(value, 15f, if (action != null) C.ACCENT else C.TEXT))
                views.add(r)
            }
            val addr = listOfNotNull(
                listOfNotNull(tags["addr:street"], tags["addr:housenumber"]).joinToString(" ").ifEmpty { null },
                tags["addr:neighbourhood"] ?: tags["addr:suburb"], tags["addr:district"], tags["addr:city"],
            ).joinToString(", ")
            info(tr("Address", "Adres"), addr)
            info(tr("Opening hours", "Çalışma saatleri"), tags["opening_hours"]?.replace(";", "\n"))
            val phone = tags["phone"] ?: tags["contact:phone"] ?: tags["mobile"] ?: tags["contact:mobile"]
            info(tr("Phone", "Telefon"), phone) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone!!.split(';').first().replace(" ", "")))) }
            val web = tags["website"] ?: tags["contact:website"] ?: tags["url"]
            info(tr("Website", "Web sitesi"), web) { open(web!!) }
            info("Instagram", tags["contact:instagram"]) { open(tags["contact:instagram"]!!.let { if (it.startsWith("http")) it else "https://instagram.com/$it" }) }
            info(tr("Email", "E-posta"), tags["email"] ?: tags["contact:email"])
            info(tr("Cuisine", "Mutfak"), tags["cuisine"]?.split(';')?.joinToString(", ") { Poi.pretty(it) })
            info(tr("Brand / operator", "Marka / işletmeci"), tags["brand"] ?: tags["operator"])
            info(tr("Wheelchair", "Tekerlekli sandalye"), tags["wheelchair"])
            info(tr("Description", "Açıklama"), tags["description"])
            info(tr("Name in English", "İngilizce adı"), tags["name:en"])
            if (rows.isNotEmpty()) {
                views.add(text(tr("Lines within 400 m", "400 m içindeki hatlar"), 14f, C.TEXT, bold = true).apply { setPadding(dp(6), dp(12), dp(6), dp(4)) })
                views.addAll(nearRows(rows, n.sec, p.lat, p.lon))
            }
            body(*views.toTypedArray())
            val x = Geo.mx(p.lon); val y = Geo.my(p.lat)
            showWeb(rows, x, y)
            map.dots = map.dots.dropLast(1) + MapView.Dot(x, y, Poi.color(p.cat), 5.5f, 0xFF000000.toInt())
            if (map.zoom < 15) map.centerOn(p.lat, p.lon, 16.0) else map.invalidate()
        }
    }

    private fun open(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(if (url.startsWith("http")) url else "https://$url"))) } catch (_: Exception) {}
    }

    // ------------------------------------------------------------------ search
    private fun doSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        if (!store.ready) { show(Screen.Welcome, replaceAll = true); return }
        val cur = stack.lastOrNull()
        if (cur is Screen.Search && cur.pack == null) { stack[stack.size - 1] = Screen.Search(q); render() }
        else { resetSearchFilters(); show(Screen.Search(q)) }
    }

    private fun resetSearchFilters() { searchKinds = Store.Kinds.ALL; searchCats = null }

    private fun renderSearch(s: Screen.Search, t: Int) {
        val pack = s.pack?.let { n -> packs().firstOrNull { it.name == n } }
        if (s.pack != null && pack == null) { back(); return } // deleted meanwhile
        val title = pack?.name ?: "“${s.q}”"
        header(title, null)
        loading()
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        val near = Geo.lat(map.cy) to Geo.lon(map.cx)
        val view = map.visibleNorm()
        // the visible part as lat0, lon0, lat1, lon1
        val box = doubleArrayOf(Geo.lat(view[3]), Geo.lon(view[0]), Geo.lat(view[1]), Geo.lon(view[2]))
        val kinds = if (pack != null) Store.Kinds.PLACES else searchKinds
        val cats = searchCats
        fun inView(x: Double, y: Double) = x in view[0]..view[2] && y in view[1]..view[3]
        class Found(val hits: List<Store.Hit>, val glowLines: Map<Long, List<List<DoubleArray>>>, val glowPlaces: List<Store.PoiR>)
        load(t, {
            val terms = pack?.terms?.map { Text.norm(it) }?.filter { it.isNotEmpty() } ?: listOf(Text.norm(s.q))
            val hits = if (pack != null) store.placeHits(store.placesMatching(terms, true, near, cats, 400), near).take(60)
            else store.search(s.q, near, kinds, cats)
            // every matching place on screen glows, not just the ones listed
            val glowPlaces = if (kinds and Store.Kinds.PLACES != 0) store.placesMatching(terms, pack != null, near, cats, 600, box) else emptyList()
            // lines with a stretch inside the visible map glow too
            val gl = LinkedHashMap<Long, List<List<DoubleArray>>>()
            for (h in hits.filter { it.kind == Store.HitKind.LINE }.take(8)) {
                val geoms = store.variantsOfLine(h.id.toInt()).take(2).map { v -> store.geometry(v).map { Pieces.merc(it) } }
                if (geoms.any { g -> g.any { p -> (0 until p.size / 2).any { inView(p[2 * it], p[2 * it + 1]) } } }) gl[h.id] = geoms
            }
            Found(hits, gl, glowPlaces)
        }) { f ->
            val hits = f.hits
            val onScreen = f.glowPlaces.size
            header(title, (if (hits.isEmpty()) tr("Nothing found", "Sonuç yok") else tr("${hits.size} results", "${hits.size} sonuç")) +
                (if (onScreen > 0) tr(" · $onScreen places on screen", " · ekranda $onScreen yer") else "") +
                (if (kinds != Store.Kinds.ALL && pack == null || cats != null) tr(" · filtered", " · filtreli") else ""))
            val views = ArrayList<View>()
            views.add(searchFilters(kinds, cats, pack, s.q))
            if (pack != null && pack.terms.isNotEmpty()) views.add(note(pack.terms.joinToString(" · ")))
            var lastKind = -1
            val glowDots = ArrayList<MapView.Dot>()
            val glowLines = ArrayList<MapView.Line>()
            for (h in hits) {
                if (h.kind != lastKind) {
                    lastKind = h.kind
                    val label = when (h.kind) {
                        Store.HitKind.LINE -> tr("Lines", "Hatlar"); Store.HitKind.STOP -> tr("Stops", "Duraklar")
                        Store.HitKind.PLACE -> tr("Places", "Yerler"); else -> tr("Streets", "Sokaklar")
                    }
                    views.add(text(label, 13f, C.DIM, bold = true).apply { setPadding(dp(6), dp(10), dp(6), dp(2)) })
                }
                val x = Geo.mx(h.lon); val y = Geo.my(h.lat)
                val visible = if (h.kind == Store.HitKind.LINE) f.glowLines.containsKey(h.id) else inView(x, y)
                if (visible) {
                    if (h.kind == Store.HitKind.LINE) f.glowLines[h.id]?.forEach { g -> glowLines.add(MapView.Line(g, h.color, 3f, casing = false, glow = true)) }
                    else glowDots.add(MapView.Dot(x, y, h.color, 4.5f, 0xFF000000.toInt(), glow = true))
                }
                val lead: View = if (h.badge != null) badge(h.badge, h.color) else View(this).apply { background = oval(h.color) }
                views.add(row(lead, h.title, h.sub + if (visible) tr(" · in view", " · görünümde") else "", leadSize = if (h.badge != null) 0 else dp(12)) {
                    hideKeyboard(); search.clearFocus()
                    pick(h)
                })
            }
            val listed = hits.filter { it.kind == Store.HitKind.PLACE }.mapTo(HashSet()) { it.id }
            for (p in f.glowPlaces) if (p.id !in listed) glowDots.add(MapView.Dot(Geo.mx(p.lon), Geo.my(p.lat), Poi.color(p.cat), 4.5f, 0xFF000000.toInt(), glow = true))
            if (hits.isEmpty() && pack != null) views.add(note(tr("No place matches this pack. Edit it to change the names.",
                "Bu pakete uyan yer yok. Adları değiştirmek için paketi düzenleyin.")))
            body(*views.toTypedArray())
            map.lines = glowLines
            map.dots = glowDots
        }
    }

    // ------------------------------------------------------------------ query packs ("Shops - common": a101, migros, bim, şok)
    private class Pack(val name: String, val terms: List<String>)

    private fun packs(): List<Pack> {
        val prefs = City.prefs(this)
        val raw = prefs.getString("packs", null)
            ?: return listOf(Pack(tr("Shops - common", "Marketler - yaygın"), listOf("a101", "migros", "bim", "şok")))
        val out = ArrayList<Pack>()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val t = o.getJSONArray("terms")
                out.add(Pack(o.getString("name"), List(t.length()) { t.getString(it) }))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun savePacks(l: List<Pack>) {
        val arr = org.json.JSONArray()
        for (p in l) arr.put(org.json.JSONObject().put("name", p.name).put("terms", org.json.JSONArray(p.terms)))
        City.prefs(this).edit().putString("packs", arr.toString()).apply()
    }

    private fun runPack(p: Pack) {
        if (!store.ready) { show(Screen.Welcome, replaceAll = true); return }
        resetSearchFilters()
        if (stack.lastOrNull() is Screen.Search) stack.removeAt(stack.size - 1)
        show(Screen.Search("", p.name))
    }

    /** The list of packs: tap one to search it, long-press to edit or delete. */
    private fun packsDialog() {
        val l = packs()
        val sheet = Sheet(this, C.ACCENT).header(tr("Query packs", "Arama paketleri"), tr("Several searches in one tap. Hold a pack to edit or delete it.", "Tek dokunuşta birkaç arama. Düzenlemek ya da silmek için pakete basılı tutun."))
            .item(Ic.ADD, tr("New pack", "Yeni paket")) { editPack(null) }
        if (l.isNotEmpty()) sheet.section(tr("Your packs", "Paketleriniz"))
        for (p in l) sheet.item(Ic.SEARCH, p.name, sub = p.terms.joinToString(", "), onLong = { packMenu(p) }) { runPack(p) }
        sheet.show()
    }

    private fun packMenu(p: Pack) {
        Sheet(this, C.ACCENT).header(p.name, p.terms.joinToString(", "))
            .item(Ic.SEARCH, tr("Search", "Ara")) { runPack(p) }
            .item(Ic.EDIT, tr("Edit", "Düzenle")) { editPack(p) }
            .item(Ic.DELETE, tr("Delete", "Sil"), danger = true) {
                android.app.AlertDialog.Builder(this).setTitle(tr("Delete “${p.name}”?", "“${p.name}” silinsin mi?"))
                    .setPositiveButton(tr("Delete", "Sil")) { _, _ ->
                        savePacks(packs().filterNot { it.name == p.name })
                        if ((stack.lastOrNull() as? Screen.Search)?.pack == p.name) back()
                    }
                    .setNegativeButton(tr("Cancel", "İptal"), null).show()
            }
            .show()
    }

    /** Create ([old] = null) or change a pack. [add] pre-fills one more name (from "Add to pack"). */
    private fun editPack(old: Pack?, add: String? = null) {
        val name = EditText(this).apply {
            setSingleLine(); hint = tr("Pack name, e.g. Shops - common", "Paket adı, ör. Marketler - yaygın")
            setText(old?.name ?: "")
        }
        val terms = EditText(this).apply {
            hint = tr("One name per line or comma-separated:\na101, migros, bim, şok", "Her satıra bir ad ya da virgülle ayırın:\na101, migros, bim, şok")
            minLines = 3; gravity = Gravity.TOP
            setText(((old?.terms ?: emptyList()) + listOfNotNull(add)).distinct().joinToString("\n"))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0)
            addView(name); addView(terms)
            addView(text(tr("Matches whole words in place names and brands, so “şok” won't find “sokak”.",
                "Yer adlarında ve markalarda tam kelime arar; “şok” “sokak”ı bulmaz."), 12f, C.DIM).apply { setPadding(dp(4), dp(6), dp(4), 0) })
        }
        android.app.AlertDialog.Builder(this).setTitle(if (old == null) tr("New pack", "Yeni paket") else tr("Edit pack", "Paketi düzenle")).setView(box)
            .setPositiveButton(tr("Save", "Kaydet")) { _, _ ->
                val t = terms.text.toString().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                val n = name.text.toString().trim().ifEmpty { t.take(3).joinToString(", ") }
                if (t.isEmpty()) return@setPositiveButton
                val l = packs().toMutableList()
                val at = l.indexOfFirst { it.name == (old?.name ?: n) }.takeIf { it >= 0 }
                l.removeAll { it.name == n || it.name == old?.name }
                l.add((at ?: 0).coerceAtMost(l.size), Pack(n, t))
                savePacks(l)
                runPack(Pack(n, t))
            }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
        name.requestFocus()
    }

    /** Adds a typed search to a pack (or a new one). */
    private fun addToPack(q: String) {
        val l = packs()
        val sheet = Sheet(this, C.ACCENT).header(tr("Add “$q” to…", "“$q” şuna ekle…"))
            .item(Ic.ADD, tr("New pack", "Yeni paket")) { editPack(null, q) }
        if (l.isNotEmpty()) sheet.section(tr("Your packs", "Paketleriniz"))
        for (p in l) sheet.item(Ic.SEARCH, p.name, sub = p.terms.joinToString(", ")) {
            savePacks(l.map { if (it === p) Pack(p.name, (p.terms + q).distinct()) else it })
            android.widget.Toast.makeText(this, tr("Added to ${p.name}", "${p.name} paketine eklendi"), android.widget.Toast.LENGTH_SHORT).show()
        }
        sheet.show()
    }

    // a getter, so the names follow the language chosen after the activity was created
    private val placeCats get() = listOf(
        Poi.FOOD to tr("Food & drink", "Yeme içme"), Poi.GROCERY to tr("Groceries & bakeries", "Market ve fırınlar"),
        Poi.SHOP to tr("Other shops", "Diğer dükkânlar"), Poi.HEALTH to tr("Health & pharmacies", "Sağlık ve eczaneler"),
        Poi.MONEY to tr("Banks & ATMs", "Banka ve ATM'ler"), Poi.TRANSPORT to tr("Transport & fuel", "Ulaşım ve akaryakıt"),
        Poi.CULTURE to tr("Culture & sights", "Kültür ve gezilecek yerler"), Poi.LODGING to tr("Hotels", "Oteller"),
        Poi.WORSHIP to tr("Places of worship", "İbadet yerleri"), Poi.EDUCATION to tr("Schools & libraries", "Okul ve kütüphaneler"),
        Poi.PUBLIC to tr("Public services", "Kamu hizmetleri"), Poi.LEISURE to tr("Parks & sport", "Park ve spor"),
        Poi.PLACE to tr("Neighbourhoods & towns", "Mahalle ve ilçeler"), Poi.OTHER to tr("Other", "Diğer"),
    )

    /** Toggle chips for what the search looks for, plus a list to pick place types. */
    private fun searchFilters(kinds: Int, cats: Set<Int>?, pack: Pack?, q: String): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        if (pack != null) {
            bar.addView(button(tr("Edit pack", "Paketi düzenle"), C.CARD, C.TEXT) { editPack(pack) }, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
        } else {
            for ((label, bit) in listOf(tr("Lines", "Hatlar") to Store.Kinds.LINES, tr("Stops", "Duraklar") to Store.Kinds.STOPS,
                tr("Places", "Yerler") to Store.Kinds.PLACES, tr("Streets", "Sokaklar") to Store.Kinds.STREETS)) {
                val on = kinds and bit != 0
                bar.addView(button(label, if (on) C.ACCENT else C.CARD, if (on) C.ACCENT else C.DIM) {
                    val k = kinds xor bit
                    searchKinds = if (k == 0) Store.Kinds.ALL else k
                    render()
                }, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
            }
        }
        val placeCats = placeCats
        val n = cats?.size ?: placeCats.size
        bar.addView(button(tr("Place types ($n/${placeCats.size})", "Yer türleri ($n/${placeCats.size})"), if (cats != null) C.ACCENT else C.CARD, if (cats != null) C.ACCENT else C.TEXT) {
            val checked = BooleanArray(placeCats.size) { cats == null || placeCats[it].first in cats }
            android.app.AlertDialog.Builder(this).setTitle(tr("Show these places", "Bu yerleri göster"))
                .setMultiChoiceItems(placeCats.map { it.second }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
                .setPositiveButton(tr("Apply", "Uygula")) { _, _ ->
                    val sel = placeCats.indices.filter { checked[it] }.map { placeCats[it].first }
                    searchCats = if (sel.size == placeCats.size) null else sel.toSet()
                    render()
                }
                .setNeutralButton(tr("All", "Tümü")) { _, _ -> searchCats = null; render() }
                .setNegativeButton(tr("Cancel", "İptal"), null).show()
        }, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
        if (pack == null && q.isNotBlank()) bar.addView(button(tr("+ Pack", "+ Paket"), C.CARD, C.TEXT) { addToPack(q.trim()) })
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    // ------------------------------------------------------------------ route finder
    private fun openRoute() {
        if (!store.ready) { show(Screen.Welcome, replaceAll = true); return }
        if (stack.lastOrNull() is Screen.Route) render() else show(Screen.Route)
    }

    /** A tapped point, named after the nearest stop or place. */
    private fun pointNamed(lat: Double, lon: Double): Pt {
        val st = store.stopsNear(lat, lon, 300.0, 1).firstOrNull()
        if (st != null) return Pt(lat, lon, if (st.second < 60) st.first.name else tr("Near ${st.first.name}", "${st.first.name} yakını"))
        val p = store.poisNear(lat, lon, 200.0, 1).firstOrNull()
        if (p != null) return p.first.name.ifEmpty { p.first.label }.let { Pt(lat, lon, tr("Near $it", "$it yakını")) }
        return Pt(lat, lon, tr("Point on map", "Haritada nokta"))
    }

    private val legColors = intArrayOf(0xFFFFD740.toInt(), 0xFF40C4FF.toInt(), 0xFFFF80AB.toInt(), 0xFF69F0AE.toInt())

    private fun routeOptions(net: Network, a: Pt, b: Pt): List<RouteOpt> {
        val n = now()
        // a few more candidates than shown, so lines that don't run now (night lines by day, say) can drop behind
        val opts = Router.find(net, a.lat, a.lon, b.lat, b.lon, k = 8).mapNotNull { j ->
            val legs = j.legs.mapIndexed { i, l ->
                val v = store.variant(net.varId[l.v]) ?: return@mapNotNull null
                val st = store.stops(intArrayOf(v.stops[l.from], v.stops[l.to]))
                val board = st[v.stops[l.from]] ?: return@mapNotNull null
                val alight = st[v.stops[l.to]] ?: return@mapNotNull null
                val next = if (v.ntrips > 0) Times.next(store.trips(v.id), v.dists, l.from, n.sec, n.today, n.yesterday, 3) else emptyList()
                LegInfo(v, board, alight, l.from, l.to, next, slice(v, l.from, l.to), if (v.mode == Mode.BUS) legColors[i % legColors.size] else v.color)
            }
            RouteOpt(j, legs)
        }
        // a bus leg with no departure in the next two hours makes an option "not now"
        fun notNow(o: RouteOpt) = o.legs.any { l -> l.v.mode == Mode.BUS && l.v.ntrips > 0 && (l.next.firstOrNull()?.let { it - n.sec > 2 * 3600 } ?: true) }
        return opts.sortedWith(compareBy({ notNow(it) }, { it.j.changes }, { it.j.score })).take(3)
    }

    /** The part of a line between two of its stops (normalised mercator). */
    private fun slice(v: Store.Variant, a: Int, b: Int): List<DoubleArray> {
        val stops = store.stops(v.stops.copyOfRange(a, b + 1))
        val pts = v.stops.copyOfRange(a, b + 1).toList().mapNotNull { stops[it] }
        val g = store.shape(v.shape)
        if (g != null && g.size == 1 && pts.size >= 2) {
            val shp = g[0]
            fun nearest(lat: Double, lon: Double, from: Int): Int {
                var best = from; var bd = Double.MAX_VALUE
                var i = from
                while (i < shp.size / 2) {
                    val dx = (shp[2 * i] - lon) * 0.755; val dy = shp[2 * i + 1] - lat
                    val d = dx * dx + dy * dy
                    if (d < bd) { bd = d; best = i }
                    i++
                }
                return best
            }
            val i0 = nearest(pts.first().lat, pts.first().lon, 0)
            val i1 = nearest(pts.last().lat, pts.last().lon, i0)
            if (i1 > i0) return listOf(Pieces.merc(shp.copyOfRange(2 * i0, 2 * i1 + 2)))
        }
        val a2 = DoubleArray(pts.size * 2)
        pts.forEachIndexed { i, s -> a2[2 * i] = s.lon; a2[2 * i + 1] = s.lat }
        return listOf(Pieces.merc(a2))
    }

    private fun legBadges(o: RouteOpt): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        o.legs.forEachIndexed { i, l ->
            if (i > 0) r.addView(text("›", 16f, C.DIM).apply { setPadding(dp(3), 0, dp(3), 0) })
            r.addView(badge(l.v.ref, l.color).apply { minWidth = dp(38) })
        }
        return r
    }

    private fun renderRoute(t: Int) {
        header(tr("Route", "Rota"), tr("By line contacts: fewest changes, ending near the destination", "Hat bağlantılarıyla: en az aktarma, hedefe yakın bitiş"))
        map.circle = null
        val a = from; val b = to
        val views = ArrayList<View>()
        fun endRow(label: String, p: Pt?, which: Int): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), 0, dp(4)) }
            r.addView(View(this).apply { background = oval(if (which == 1) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt()) }, lpL(dp(12), dp(12)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(label, 12f, C.DIM))
            col.addView(text(if (picking == which) tr("Tap the map…", "Haritaya dokunun…") else p?.name ?: tr("Not set", "Seçilmedi"), 15f, if (picking == which) C.ACCENT else C.TEXT, lines = 1))
            r.addView(col, lpL(0, WRAP, 1f))
            r.addView(button(tr("Map", "Harita"), if (picking == which) C.ACCENT else C.CARD, if (picking == which) C.ACCENT else C.TEXT) {
                picking = if (picking == which) 0 else which
                render()
            }, lpL(WRAP, WRAP).apply { leftMargin = dp(6) })
            if (map.hasMe()) r.addView(button(tr("Me", "Ben")) {
                val p0 = Pt(map.meLat(), map.meLon(), tr("My location", "Konumum"))
                if (which == 1) from = p0 else to = p0
                picking = 0; prefer = null; render()
            }, lpL(WRAP, WRAP).apply { leftMargin = dp(6) })
            return r
        }
        views.add(endRow(tr("From", "Nereden"), a, 1))
        views.add(endRow(tr("To", "Nereye"), b, 2))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        bar.addView(button(tr("⇅ Swap", "⇅ Değiştir")) { val x = from; from = to; to = x; prefer = null; render() }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        bar.addView(button(tr("My routes ▾", "Rotalarım ▾")) { myRoutes() }, lpL(WRAP, WRAP))
        views.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
        val dots = ArrayList<MapView.Dot>()
        a?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFF66BB6A.toInt(), 5f, 0xFF000000.toInt())) }
        b?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFFEF5350.toInt(), 5f, 0xFF000000.toInt())) }
        map.dots = dots
        map.lines = emptyList()
        if (picking != 0) views.add(note(tr("Tap the map to set the ${if (picking == 1) "start" else "destination"}. Pan and zoom first if you like.",
            "${if (picking == 1) "Başlangıcı" else "Varış noktasını"} seçmek için haritaya dokunun. İsterseniz önce kaydırıp yakınlaştırın.")))
        if (a == null || b == null) {
            if (picking == 0) views.add(note(tr("Set both ends: pick them on the map, use your last location, or use “Route from/to here” on a stop or place.",
                "İki ucu da seçin: haritadan seçin, son konumunuzu kullanın ya da bir durak veya yerde “Buradan/Buraya rota” düğmesine dokunun.")))
            body(*views.toTypedArray())
            return
        }
        views.add(note(tr("Finding routes…", "Rotalar aranıyor…")))
        body(*views.toTypedArray())
        val finding = views.last()
        load(t, {
            val net = store.network() ?: return@load null
            routeOptions(net, a, b)
        }) { opts ->
            sheetBody.removeView(finding)
            if (opts.isEmpty()) {
                sheetBody.addView(note(tr("No line connection found within 500 m of both ends and two changes. Try points closer to a stop.",
                    "İki uca 500 m mesafede, en çok iki aktarmalı hat bağlantısı bulunamadı. Bir durağa daha yakın noktalar deneyin.")))
                return@load
            }
            val pref = prefer
            prefer = null
            val n = now()
            opts.forEachIndexed { i, o ->
                val first = o.legs.first()
                val dep = first.next.firstOrNull()?.let { tr(" · next ${Times.mins(it, n.sec)}", " · sonraki ${Times.mins(it, n.sec)}") }
                    ?: if (first.v.ntrips == 0) "" else tr(" · no more today", " · bugün sefer kalmadı")
                val km = String.format(java.util.Locale.US, "%.1f", o.j.rideMeters / 1000.0)
                val end = Fmt.dist(o.j.endDist.toDouble())
                val sub = changes(o.j.changes) +
                    tr(" · $km km by line · ends $end from the destination", " · hatla $km km · hedefe $end kala biter") +
                    tr("\nBoard at ${first.board.name}$dep", "\nBiniş: ${first.board.name}$dep")
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = ripple(outline(dpf(10), if (i == 0) C.ACCENT else C.OUTLINE))
                    setOnClickListener { show(Screen.Legs(o)) }
                    layoutParams = lpL(MATCH, WRAP).apply { topMargin = dp(6) }
                }
                r.addView(legBadges(o))
                r.addView(text(sub, 13f, C.DIM).apply { setPadding(0, dp(4), 0, 0) })
                sheetBody.addView(r)
            }
            // the best option, faint, on the map
            map.lines = opts.first().legs.map { MapView.Line(it.geom, it.color, 3f, casing = true) }
            fitPoints(listOf(a, b))
            if (pref != null) opts.firstOrNull { it.key == pref }?.let { show(Screen.Legs(it)) }
        }
    }

    private fun fitPoints(ps: List<Pt>) {
        val xs = ps.map { Geo.mx(it.lon) }; val ys = ps.map { Geo.my(it.lat) }
        val pad = 0.15 * maxOf(xs.max() - xs.min(), ys.max() - ys.min(), 2e-5)
        sheet.post { updatePads(); map.fit(doubleArrayOf(xs.min() - pad, ys.min() - pad, xs.max() + pad, ys.max() + pad), 16.0) }
    }

    /** "Direct" / "1 change" / "2 changes". */
    private fun changes(k: Int) = if (k == 0) tr("Direct", "Aktarmasız") else tr("$k change${if (k > 1) "s" else ""}", "$k aktarma")

    private fun renderLegs(s: Screen.Legs) {
        val o = s.opt
        header(changes(o.j.changes), "${from?.name ?: "?"} → ${to?.name ?: "?"}", legBadges(o))
        map.circle = null
        val n = now()
        val views = ArrayList<View>()
        if (o.j.startDist > 30) views.add(note(tr("Start: ${o.legs.first().board.name} is about ${Fmt.dist(o.j.startDist.toDouble())} from your start.",
            "Başlangıç: ${o.legs.first().board.name}, başlangıç noktanıza yaklaşık ${Fmt.dist(o.j.startDist.toDouble())} uzakta.")))
        o.legs.forEachIndexed { i, l ->
            if (i > 0) {
                val prev = o.legs[i - 1].alight
                val gap = Geo.dist(prev.lat, prev.lon, l.board.lat, l.board.lon)
                views.add(note(if (gap < 40) tr("Change at ${l.board.name}", "Aktarma: ${l.board.name}")
                    else tr("Change: ${prev.name} → ${l.board.name} (about ${Fmt.dist(gap)})", "Aktarma: ${prev.name} → ${l.board.name} (yaklaşık ${Fmt.dist(gap)})"), C.ACCENT))
            }
            val dep = when {
                l.next.isNotEmpty() -> tr("next ", "sonraki ") + l.next.joinToString(", ") { Times.mins(it, n.sec) }
                l.v.mode != Mode.BUS -> tr("no timetable offline", "çevrimdışı saat yok")
                else -> tr("no more today", "bugün sefer kalmadı")
            }
            val stopsN = l.alightIdx - l.boardIdx
            views.add(row(badge(l.v.ref, l.color), "→ " + l.v.headsign.ifEmpty { l.v.lineName },
                warn(l.v) + run {
                    val km = String.format(java.util.Locale.US, "%.1f", (l.v.dists[l.alightIdx] - l.v.dists[l.boardIdx]) / 1000.0)
                    tr("Board at ${l.board.name} · $dep\n$stopsN stop${if (stopsN > 1) "s" else ""} ($km km) to ${l.alight.name}",
                        "Biniş: ${l.board.name} · $dep\n$stopsN durak ($km km), iniş: ${l.alight.name}")
                }, onLong = { show(Screen.StopS(l.board.id)) }) { // hold: the boarding stop
                show(Screen.LineS(l.v.id, l.board.id))
            })
        }
        views.add(note(tr("Get off at ${o.legs.last().alight.name}, about ${Fmt.dist(o.j.endDist.toDouble())} from the destination.",
            "İniş: ${o.legs.last().alight.name}, hedefe yaklaşık ${Fmt.dist(o.j.endDist.toDouble())}.")))
        views.add(button(tr("★ Save to My routes", "★ Rotalarıma kaydet"), C.ACCENT, C.ACCENT) { saveRoute(o) }.apply {
            layoutParams = lpL(MATCH, WRAP).apply { topMargin = dp(8) }
        })
        body(*views.toTypedArray())
        // map: each leg in its colour, boarding and alighting stops, both ends
        map.lines = o.legs.map { MapView.Line(it.geom, it.color, 4.5f) }
        val dots = ArrayList<MapView.Dot>()
        for (l in o.legs) {
            dots.add(MapView.Dot(Geo.mx(l.board.lon), Geo.my(l.board.lat), 0xFFFFFFFF.toInt(), 3.5f, l.color, l.board.name))
            dots.add(MapView.Dot(Geo.mx(l.alight.lon), Geo.my(l.alight.lat), 0xFFFFFFFF.toInt(), 3.5f, l.color))
        }
        from?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFF66BB6A.toInt(), 5f, 0xFF000000.toInt())) }
        to?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFFEF5350.toInt(), 5f, 0xFF000000.toInt())) }
        map.dots = dots
        val pts = ArrayList<Pt>()
        from?.let { pts.add(it) }; to?.let { pts.add(it) }
        for (l in o.legs) { pts.add(Pt(l.board.lat, l.board.lon, "")); pts.add(Pt(l.alight.lat, l.alight.lon, "")) }
        fitPoints(pts)
    }

    // ------------------------------------------------------------------ saved routes
    private class Fav(val name: String, val a: Pt, val b: Pt, val lines: String)

    private fun favs(): MutableList<Fav> {
        val out = ArrayList<Fav>()
        try {
            val arr = org.json.JSONArray(City.prefs(this).getString("routes", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                fun pt(k: String) = o.getJSONObject(k).let { Pt(it.getDouble("lat"), it.getDouble("lon"), it.optString("name")) }
                out.add(Fav(o.getString("name"), pt("a"), pt("b"), o.optString("lines")))
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun saveFavs(l: List<Fav>) {
        val arr = org.json.JSONArray()
        for (f in l) {
            fun pt(p: Pt) = org.json.JSONObject().put("lat", p.lat).put("lon", p.lon).put("name", p.name)
            arr.put(org.json.JSONObject().put("name", f.name).put("a", pt(f.a)).put("b", pt(f.b)).put("lines", f.lines))
        }
        City.prefs(this).edit().putString("routes", arr.toString()).apply()
    }

    private fun saveRoute(o: RouteOpt) {
        val a = from ?: return; val b = to ?: return
        val et = EditText(this).apply { setText("${a.name} → ${b.name}"); setSingleLine(); selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        android.app.AlertDialog.Builder(this).setTitle(tr("Save route", "Rotayı kaydet") + " (${o.key.replace(">", " › ")})").setView(box)
            .setPositiveButton(tr("Save", "Kaydet")) { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "${a.name} → ${b.name}" }
                val l = favs()
                l.removeAll { it.name == name }
                l.add(0, Fav(name, a, b, o.key))
                saveFavs(l)
                android.widget.Toast.makeText(this, tr("Saved to My routes", "Rotalarıma kaydedildi"), android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun myRoutes() {
        val l = favs()
        if (l.isEmpty()) {
            android.widget.Toast.makeText(this, tr("No saved routes yet. Open a route option and tap “Save to My routes”.",
                "Henüz kayıtlı rota yok. Bir rota seçeneğini açıp “Rotalarıma kaydet” düğmesine dokunun."), android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val sheet = Sheet(this, C.ACCENT).header(tr("My routes", "Rotalarım"), tr("Hold a route to delete it", "Silmek için rotaya basılı tutun"))
        for (f in l) sheet.item(Ic.ROUTE, f.name, sub = f.lines.replace(">", " › "), onLong = {
            android.app.AlertDialog.Builder(this).setTitle(tr("Delete “${f.name}”?", "“${f.name}” silinsin mi?"))
                .setPositiveButton(tr("Delete", "Sil")) { _, _ -> saveFavs(favs().filterNot { it.name == f.name }) }
                .setNegativeButton(tr("Cancel", "İptal"), null).show()
        }) {
            from = f.a; to = f.b; prefer = f.lines; picking = 0
            if (stack.lastOrNull() is Screen.Route) render() else show(Screen.Route)
        }
        sheet.show()
    }

    private fun pick(h: Store.Hit) {
        when (h.kind) {
            Store.HitKind.LINE -> bg.execute {
                val v = store.variantsOfLine(h.id.toInt()).firstOrNull()
                main.post { if (v != null) show(Screen.LineS(v.id, v.stops.firstOrNull() ?: 0)) }
            }
            Store.HitKind.STOP -> { map.centerOn(h.lat, h.lon, maxOf(map.zoom, 16.0)); show(Screen.StopS(h.id.toInt())) }
            Store.HitKind.PLACE -> { map.centerOn(h.lat, h.lon, maxOf(map.zoom, 16.5)); show(Screen.PlaceS(h.id)) }
            else -> {
                map.centerOn(h.lat, h.lon, 16.5)
                show(Screen.Area(h.lat, h.lon, 400.0))
            }
        }
    }

    // ------------------------------------------------------------------ settings & sync
    private var syncText: TextView? = null
    private var syncBar: ProgressBar? = null
    private var syncBtn: TextView? = null
    private var syncErr: TextView? = null

    private fun renderWelcome() {
        header(tr("No offline data yet", "Henüz çevrimdışı veri yok"), tr("The ${City.localName} tab works offline after one sync",
            "${City.localName} sekmesi tek bir eşitlemeden sonra çevrimdışı çalışır"))
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        body(
            note(tr("Sync downloads the ${City.name} map, places, metro/tram/ferry lines (OpenStreetMap) and ${busWhat()}. " +
                "It needs Wi-Fi and ${City.downloadSize} of download, and takes 10–60 minutes. After that, everything works offline.",
                "Eşitleme ${City.localName} haritasını, yerleri, metro/tramvay/vapur hatlarını (OpenStreetMap) ve ${busWhatAcc()} " +
                    "indirir. Wi-Fi ve ${City.downloadSize} indirme gerekir; 10–60 dakika sürer. Sonrasında her şey çevrimdışı çalışır.")),
            button(tr("Open sync settings", "Eşitleme ayarlarını aç"), C.ACCENT, C.ACCENT) { show(Screen.Settings) },
        )
    }

    private fun renderSettings() {
        header(tr("Settings", "Ayarlar"), tr("Offline data", "Çevrimdışı veri"))
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        val views = ArrayList<View>()
        val info = text("…", 14f).apply { setPadding(dp(6), dp(4), dp(6), dp(8)) }
        views.add(info)
        bg.execute {
            val s = if (store.ready) {
                val at = store.meta("synced_at")?.toLongOrNull()
                tr("Last sync: ", "Son eşitleme: ") + (at?.let { Fmt.date(it) } ?: "?") + "\n" +
                    (when (City.bus) {
                        City.Bus.EGO -> tr("EGO timetables read: ", "EGO saatleri okundu: ")
                        City.Bus.IETT -> tr("İETT timetable published: ", "İETT saatlerinin yayın tarihi: ")
                        City.Bus.GTFS -> tr("${City.operator} timetable starts: ", "${City.operator} saatlerinin başlangıcı: ")
                        City.Bus.OSM -> tr("Bus routes read: ", "Otobüs güzergâhları okundu: ")
                    }) +
                    (store.meta("gtfs_date") ?: "?") + "\n" +
                    (egoStats()?.let { it + "\n" } ?: "") +
                    store.counts() + "\n" +
                    tr("Size on phone: ", "Telefondaki boyut: ") + Fmt.mb(store.file.length()) +
                    (store.meta("outskirts_missing")?.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { tr("\n$it outer squares were left out (OpenStreetMap server busy). Sync again later to fill them in.",
                            "\n$it dış kare atlandı (OpenStreetMap sunucusu meşgul). Tamamlamak için daha sonra yeniden eşitleyin.") } ?: "") +
                    if (City.istanbul && !store.hasLand) tr("\nCoastline: not complete in this sync (sea is drawn as land)",
                        "\nKıyı şeridi: bu eşitlemede eksik (deniz kara olarak çizildi)") else ""
            } else tr("No offline data yet.", "Henüz çevrimdışı veri yok.")
            val sit = if (store.ready) store.meta("situational_at")?.toLongOrNull()?.let { at ->
                val checked = store.meta("situational_lines")?.substringBefore('/') ?: "?"
                val newTimes = store.meta("situational_lines")?.substringAfter('/') ?: "?"
                val noticed = store.noticed().size
                tr("Last situational sync: ${Fmt.date(at)} · $checked lines checked, $newTimes routes with new times · $noticed lines with announcements",
                    "Son güncel durum eşitlemesi: ${Fmt.date(at)} · $checked hat denetlendi, $newTimes güzergâhın saati yenilendi · $noticed hatta duyuru var")
            } ?: tr("No situational sync yet.", "Henüz güncel durum eşitlemesi yapılmadı.") else ""
            main.post { info.text = s; sitInfo?.text = sit }
        }
        syncBtn = button("", C.ACCENT, C.ACCENT) {
            if (SyncState.running) SyncService.stop() else startSync()
        }
        views.add(syncBtn!!)
        syncBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        views.add(syncBar!!.also { it.layoutParams = lpL(MATCH, dp(16)).apply { topMargin = dp(10) } })
        syncText = text("", 13f, C.DIM).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        views.add(syncText!!)
        syncErr = text("", 13f, C.RED).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        views.add(syncErr!!)
        // situational sync (only where the operator publishes announcements and today's times)
        if (!City.situational) { sitInfo = null; sitBtn = null } else {
        views.add(text(tr("Situational sync", "Güncel durum eşitlemesi"), 15f, bold = true).apply { setPadding(dp(6), dp(14), dp(6), dp(2)) })
        sitInfo = text("", 13f, C.DIM).apply { setPadding(dp(6), 0, dp(6), dp(6)) }
        views.add(sitInfo!!)
        sitBtn = button("", C.CARD) { if (SyncState.running) SyncService.stop() else startSync(situational = true) }
        views.add(sitBtn!!)
        views.add(note(if (City.ankara) tr(
            "Fetches EGO's latest announcements (route changes, new services, stop notices; shown with ⚠ on lines and stops) and reads every bus line's " +
                "current departure times again from ego.gov.tr, without downloading the map. About 660 pages on Wi-Fi; a few minutes.",
            "EGO'nun son duyurularını (güzergâh değişiklikleri, yeni seferler, durak duyuruları; hatlarda ve duraklarda ⚠ ile gösterilir) alır ve " +
                "her otobüs hattının güncel kalkış saatlerini haritayı indirmeden ego.gov.tr'den yeniden okur. Wi-Fi ile yaklaşık 660 sayfa; birkaç dakika.")
        else tr(
            "Fetches İETT's current announcements (cancelled trips, detours, closed roads; shown with ⚠ on the lines) and today's planned " +
                "departure times of every bus line, without downloading the map again. About 800 small requests on Wi-Fi; usually under a minute.",
            "İETT'nin güncel duyurularını (iptal edilen seferler, güzergâh değişiklikleri, kapalı yollar; hatlarda ⚠ ile gösterilir) ve her otobüs " +
                "hattının bugünkü planlı kalkış saatlerini haritayı yeniden indirmeden alır. Wi-Fi ile yaklaşık 800 küçük istek; genelde bir dakikadan kısa.")))
        }
        // line looks
        views.add(text(tr("Lines on the map", "Haritadaki hatlar"), 15f, bold = true).apply { setPadding(dp(6), dp(14), dp(6), dp(2)) })
        views.add(slider(tr("Bus web thickness", "Otobüs ağı kalınlığı"), Style.webWidth, 0.3f, 2f) { Style.webWidth = it })
        views.add(slider(tr("Bus web brightness", "Otobüs ağı parlaklığı"), Style.webAlpha, 0.15f, 2f) { Style.webAlpha = it })
        views.add(slider(tr("Metro, tram and ferry thickness", "Metro, tramvay ve vapur kalınlığı"), Style.railWidth, 0.3f, 2f) { Style.railWidth = it })
        views.add(slider(tr("Selected lines thickness", "Seçili hat kalınlığı"), Style.lineWidth, 0.4f, 2f) { Style.lineWidth = it })
        views.add(note(tr(
            "Full sync runs only on Wi-Fi (never mobile data). It downloads ${City.downloadSize} and takes 10–60 minutes; you can leave the app. " +
                "If it stops, the next sync continues where it left off. Nothing runs in the background otherwise.\n\n",
            "Tam eşitleme yalnızca Wi-Fi ile çalışır (asla mobil veriyle değil). ${City.downloadSize} indirir ve 10–60 dakika sürer; uygulamadan çıkabilirsiniz. " +
                "Durursa bir sonraki eşitleme kaldığı yerden devam eder. Bunun dışında arka planda hiçbir şey çalışmaz.\n\n",
        ) + City.mapSourceText))
        views.add(note(
            if (City.ankara) tr("EGO publishes departure times at the first stop and the trip's length; times at later stops are estimated from them. " +
                "Stops EGO lists without a map position are placed by name near the line, or estimated between their neighbours. On holidays EGO runs the Saturday times.",
                "EGO ilk duraktaki kalkış saatlerini ve sefer süresini yayımlar; sonraki duraklardaki saatler bunlardan tahmin edilir. " +
                    "EGO'nun konumsuz listelediği duraklar adlarına göre hattın yakınına yerleştirilir ya da komşu duraklar arasında tahmin edilir. Bayramlarda EGO cumartesi saatlerini uygular.")
            else if (!City.timetables) tr("${City.operator} publishes no open timetables, so bus lines show their route and stops only. " +
                "The lines are the ones mapped in OpenStreetMap; some may be missing or out of date.",
                "${City.operator} açık saat verisi yayımlamıyor; otobüs hatlarında yalnızca güzergâh ve duraklar görünür. " +
                    "Hatlar OpenStreetMap'te çizilmiş olanlardır; bazıları eksik ya da eski olabilir.")
            else tr("Times at stops after the first are estimated from the first and last stop's scheduled times. Public holidays are not in the timetable.",
                "İlk duraktan sonraki saatler, ilk ve son durağın planlı saatlerinden tahmin edilir. Resmî tatiller saat çizelgesinde yoktur."),
        ))
        views.add(note(
            tr("Map, places, metro, tram and ferry lines © OpenStreetMap contributors (ODbL). ",
                "Harita, yerler, metro, tramvay ve vapur hatları © OpenStreetMap katkıcıları (ODbL). ") + City.busSourceText,
        ))
        // Hands off to Plain Setup (shared helper, app/plain/update/PlainUpdate.kt).
        views.add(button(app.plain.update.PlainUpdate.label(this), C.CARD) { app.plain.update.PlainUpdate.check(this) })
        // Language (shared helper, app/plain/lang/PlainLang.kt): a dropdown under the row; a change recreates the
        // activity, which then reopens these settings (see onSaveInstanceState / openStore).
        val lang = button(PlainLang.label(), C.CARD) { Sheet.language(this, C.ACCENT) }
        lang.layoutParams = lpL(MATCH, WRAP).apply { topMargin = dp(8) }
        langRow = lang
        views.add(lang)
        body(*views.toTypedArray())
        onSyncChanged()
    }

    /** EGO's placement numbers (written by the sync as "ego_nums"), worded in the current language; older data keeps its English line. */
    private fun egoStats(): String? {
        val n = store.meta("ego_nums")?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 9 } ?: return store.meta("ego_stats")
        return tr("${n[0]} of ${n[1]} EGO lines on the map · stops placed by number ${n[2]}, by name ${n[3]}, along the street ${n[4]}, " +
            "estimated ${n[5]}, not found ${n[6]} · ${n[7]} lines drawn along the streets, ${n[8]} from OpenStreetMap routes",
            "${n[0]}/${n[1]} EGO hattı haritada · durak yerleşimi: numarayla ${n[2]}, adla ${n[3]}, sokak boyunca ${n[4]}, " +
                "tahmini ${n[5]}, bulunamayan ${n[6]} · ${n[7]} hat sokaklar boyunca, ${n[8]} hat OpenStreetMap güzergâhlarından çizildi")
    }

    private var sitInfo: TextView? = null
    private var sitBtn: TextView? = null

    /** A labelled slider for a style multiplier; the map is redrawn when it is let go. */
    private fun slider(label: String, value: Float, lo: Float, hi: Float, set: (Float) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        val t = text("", 13f, C.TEXT)
        fun label(v: Float) { t.text = tr("$label: ${(v * 100).toInt()} %", "$label: %${(v * 100).toInt()}") }
        label(value)
        col.addView(t)
        col.addView(android.widget.SeekBar(this).apply {
            max = 100
            progress = (((value - lo) / (hi - lo)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar, p: Int, user: Boolean) { label(lo + (hi - lo) * p / 100f) }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar) {
                    set(lo + (hi - lo) * sb.progress / 100f)
                    Style.save(City.prefs(this@MainActivity))
                    tiles.clear()
                    map.invalidate()
                }
            })
        })
        return col
    }

    private fun startSync(situational: Boolean = false) {
        if (Net.wifi(this) == null) {
            syncErr?.text = tr("Connect to Wi-Fi first. Sync never uses mobile data.", "Önce Wi-Fi'a bağlanın. Eşitleme asla mobil veri kullanmaz.")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        SyncService.start(this, situational)
        syncErr?.text = ""
    }

    private fun onSyncChanged() {
        val sit = SyncState.situational
        syncBtn?.text = if (SyncState.running && !sit) tr("Stop sync", "Eşitlemeyi durdur")
            else if (store.ready) tr("Full sync (Wi-Fi)", "Tam eşitleme (Wi-Fi)") else tr("Download offline data (Wi-Fi)", "Çevrimdışı veriyi indir (Wi-Fi)")
        sitBtn?.text = if (SyncState.running && sit) tr("Stop situational sync", "Güncel durum eşitlemesini durdur")
            else tr("Situational sync (Wi-Fi)", "Güncel durum eşitlemesi (Wi-Fi)")
        syncBtn?.isEnabled = !(SyncState.running && sit)
        sitBtn?.isEnabled = store.ready && !(SyncState.running && !sit)
        sitBtn?.alpha = if (sitBtn?.isEnabled == true) 1f else 0.4f
        syncBar?.progress = (SyncState.progress * 1000).toInt()
        syncBar?.visibility = if (SyncState.running) View.VISIBLE else View.GONE
        syncText?.text = SyncState.text
        SyncState.error?.let { syncErr?.text = it }
        if (!SyncState.running && generation >= 0 && SyncState.generation != generation) {
            openStore()
            if (stack.lastOrNull() is Screen.Settings) main.postDelayed({ if (stack.lastOrNull() is Screen.Settings) render() }, 500)
        }
    }

    // ------------------------------------------------------------------ rows
    private fun row(lead: View, title: String, sub: String?, leadSize: Int = 0, onLong: (() -> Unit)? = null, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(5), dp(6), dp(5))
            background = ripple(null)
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        if (leadSize > 0) r.addView(lead, lpL(leadSize, leadSize).apply { rightMargin = dp(12); leftMargin = dp(4) })
        else r.addView(lead, lpL(WRAP, WRAP).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 14f, lines = 1))
        if (!sub.isNullOrEmpty()) col.addView(text(sub, 12f, C.DIM, lines = 2))
        r.addView(col, lpL(0, WRAP, 1f))
        return r
    }

    /** A stop in a line's stop list: a track with a dot, the name and the next time. */
    private fun stopRow(i: Int, n: Int, name: String, time: String, color: Int, focus: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            background = ripple(if (focus) outline(dpf(8), 0x99FFD740.toInt()) else null)
            setOnClickListener { onClick() }
        }
        val track = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val x = width / 2f; val y = height / 2f
                p.color = color; p.strokeWidth = dpf(4)
                c.drawLine(x, if (i == 0) y else 0f, x, if (i == n - 1) y else height.toFloat(), p)
                p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, dpf(if (focus) 5.5f else 4f), p)
                p.color = color; c.drawCircle(x, y, dpf(if (focus) 3.2f else 2f), p)
            }
        }
        r.addView(track, lpL(dp(22), dp(30)))
        r.addView(text(name, 13.5f, if (focus) C.HIGHLIGHT else C.TEXT, bold = focus, lines = 1).apply { setPadding(dp(8), 0, 0, 0) }, lpL(0, WRAP, 1f))
        if (time.isNotEmpty()) r.addView(text(time, 12f, C.DIM).apply { typeface = Typeface.MONOSPACE })
        return r
    }

}
