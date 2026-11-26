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
import java.util.Calendar
import java.util.concurrent.Executors

class MainActivity : Activity() {
    // ------------------------------------------------------------------ screens in the bottom sheet
    private sealed class Screen {
        class Area(val lat: Double, val lon: Double, val r: Double) : Screen()
        class StopS(val id: Int) : Screen()
        class LineS(var variant: Int, var focus: Int, var timetable: Boolean = false, var day: Int = 0) : Screen()
        class PlaceS(val id: Long) : Screen()
        class Search(val q: String) : Screen()
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
    private lateinit var gpsBtn: Icon
    private lateinit var sheet: CappedLinear
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
        super.onCreate(savedInstanceState)
        edgeToEdge()
        store = Store(Sync.dataFile(this))
        map = MapView(this)
        renderer = TileRenderer(store, resources.displayMetrics.density, map.tilePx)
        tiles = Tiles(2, (Runtime.getRuntime().maxMemory() / 5).toInt().coerceIn(24 shl 20, 96 shl 20)) { z, x, y ->
            if (store.ready) renderer.render(z, x, y) else null
        }
        map.tiles = tiles
        Style.load(getSharedPreferences("app", MODE_PRIVATE))
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
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
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

    private fun rememberFix(lat: Double, lon: Double, acc: Float, time: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSaved < 5000) return
        lastSaved = now
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("fix", "$lat,$lon,$acc,${time.coerceAtMost(now)}").apply()
    }

    private fun openStore() {
        bg.execute {
            val ok = store.open()
            renderer.clear()
            tiles.clear()
            main.post {
                generation = SyncState.generation
                map.invalidate()
                if (!ok) { if (stack.isEmpty() || stack.last() !is Screen.Settings) show(Screen.Welcome, replaceAll = true) }
                else if (stack.lastOrNull() is Screen.Welcome) closeSheet()
            }
        }
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
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("view", "${map.cx},${map.cy},${map.zoom}").apply()
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

        // search bar + settings
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C.PANEL, dpf(24))
            setPadding(dp(12), 0, dp(4), 0)
        }
        box.addView(Icon(this, Icon.SEARCH).apply { color = C.DIM }, lpL(dp(24), dp(24)))
        search = EditText(this).apply {
            hint = "Line, stop, place or street"
            setHintTextColor(C.DIM)
            setTextColor(C.TEXT)
            textSize = 15f
            background = null
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setPadding(dp(8), dp(10), dp(8), dp(10))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        box.addView(search, lpL(0, dp(46), 1f))
        clearSearch = Icon(this, Icon.CLOSE).apply {
            color = C.DIM
            visibility = View.GONE
            background = ripple(null)
            setOnClickListener { search.setText(""); if (stack.lastOrNull() is Screen.Search) back() }
        }
        box.addView(clearSearch, lpL(dp(40), dp(40)))
        topBar.addView(box, lpL(0, WRAP, 1f))
        val routeBtn = Icon(this, Icon.ROUTE).apply {
            color = C.ACCENT
            background = ripple(oval(C.PANEL))
            setOnClickListener { hideKeyboard(); search.clearFocus(); openRoute() }
        }
        topBar.addView(routeBtn, lpL(dp(46), dp(46)).apply { leftMargin = dp(8) })
        val settings = Icon(this, Icon.SETTINGS).apply {
            background = ripple(oval(C.PANEL))
            setOnClickListener { hideKeyboard(); search.clearFocus(); show(Screen.Settings) }
        }
        topBar.addView(settings, lpL(dp(46), dp(46)).apply { leftMargin = dp(8) })
        ui.addView(topBar, lpF(MATCH, WRAP, Gravity.TOP))

        search.addTextChangedListener(object : TextWatcher {
            private val run = Runnable { doSearch() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                clearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                main.removeCallbacks(run)
                if (!s.isNullOrBlank()) main.postDelayed(run, 220)
            }
        })

        // bottom: location button above the sheet
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        gpsBtn = Icon(this, Icon.GPS).apply {
            color = C.DIM
            background = ripple(oval(C.PANEL))
            setOnClickListener { gpsTap() }
        }
        bottom.addView(gpsBtn, lpL(dp(50), dp(50)).apply { gravity = Gravity.END; rightMargin = dp(12); bottomMargin = dp(10) })
        sheet = CappedLinear(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.PANEL)
                cornerRadii = floatArrayOf(dpf(16), dpf(16), dpf(16), dpf(16), 0f, 0f, 0f, 0f)
            }
            visibility = View.GONE
            isClickable = true
        }
        sheetHead = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(2))
            setOnClickListener { expanded = !expanded; sizeSheet() }
        }
        sheetBack = Icon(this, Icon.BACK).apply { background = ripple(null); setOnClickListener { back() } }
        sheetHead.addView(sheetBack, lpL(dp(40), dp(40)))
        sheetTitle = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        sheetHead.addView(sheetTitle, lpL(0, WRAP, 1f))
        sheetHead.addView(Icon(this, Icon.CLOSE).apply { background = ripple(null); setOnClickListener { closeSheet() } }, lpL(dp(40), dp(40)))
        sheet.addView(sheetHead, lpL(MATCH, WRAP))
        sheetScroll = ScrollView(this).apply { isFillViewport = false; isVerticalScrollBarEnabled = false }
        sheetBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(2), dp(10), dp(12)) }
        sheetScroll.addView(sheetBody, FrameLayout.LayoutParams(MATCH, WRAP))
        sheet.addView(sheetScroll, lpL(MATCH, WRAP))
        bottom.addView(sheet, lpL(MATCH, WRAP))
        ui.addView(bottom, lpF(MATCH, WRAP, Gravity.BOTTOM))

        sheet.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePads() }
        topBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePads() }

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
        setContentView(root)

        map.onTap = { lat, lon, _, _ -> mapTap(lat, lon) }
        map.onUserMove = { updateGpsBtn() }
    }

    private var navBottom = 0
    private var imeBottom = 0
    private val insetBottom get() = if (imeBottom > 0) imeBottom else navBottom

    /** The keyboard lifts the whole bottom part; otherwise the sheet pads itself above the navigation bar. */
    private fun applyInsets() {
        val bottom = sheet.parent as View
        val open = sheet.visibility == View.VISIBLE
        bottom.setPadding(0, 0, 0, if (imeBottom > 0) imeBottom else if (open) 0 else navBottom)
        // the sheet itself stops above the navigation bar, so its last rows are never under it
        sheet.setPadding(0, 0, 0, if (imeBottom > 0) 0 else navBottom)
        sheetBody.setPadding(dp(10), dp(2), dp(10), dp(12))
        main.post { updatePads() }
    }

    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private fun updatePads() {
        val top = ui.paddingTop + topBar.height
        val bottomH = if (sheet.visibility == View.VISIBLE) sheet.height else insetBottom
        if (map.padTop != top || map.padBottom != bottomH) { map.padTop = top; map.padBottom = bottomH }
    }

    private fun sizeSheet() {
        val h = resources.displayMetrics.heightPixels
        sheet.maxH = if (expanded) (h * 0.85).toInt() else (h * 0.48).toInt()
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
            android.widget.Toast.makeText(this, "Location is switched off in Android", android.widget.Toast.LENGTH_LONG).show()
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
        if (replaceAll) stack.clear()
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
        token++
        sheet.visibility = View.GONE
        applyInsets()
        map.lines = emptyList(); map.dots = emptyList(); map.circle = null
    }

    private fun openSheet() {
        if (sheet.visibility != View.VISIBLE) {
            sheet.visibility = View.VISIBLE
            applyInsets()
        }
        sheetBack.visibility = if (stack.size > 1) View.VISIBLE else View.INVISIBLE
    }

    private fun render() {
        val s = stack.lastOrNull() ?: return closeSheet()
        val t = ++token
        openSheet()
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
        row.addView(text(title, 17f, bold = true, lines = 2), lpL(0, WRAP, 1f))
        sheetTitle.addView(row)
        if (!sub.isNullOrEmpty()) sheetTitle.addView(text(sub, 13f, C.DIM, lines = 2))
    }

    private fun body(vararg v: View) {
        sheetBody.removeAllViews()
        for (x in v) sheetBody.addView(x)
        sheetScroll.scrollTo(0, 0)
    }

    private fun note(s: String, color: Int = C.DIM) = text(s, 13f, color).apply { setPadding(dp(6), dp(8), dp(6), dp(8)) }

    private fun loading() = body(note("Loading…"))

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
                else -> {
                    val r = (dpf(70) * mpp).coerceIn(400.0, 1500.0)
                    show(Screen.Area(lat, lon, r), replaceAll = true)
                }
            }
        }
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
        if (rows.isEmpty()) return listOf(note("No lines stop within this circle. Tap closer to a street, or zoom out and tap again."))
        return rows.mapIndexed { i, r ->
            val col = lineColor(r.v, i)
            row(badge(r.v.ref, col), "→ " + r.v.headsign.ifEmpty { r.v.lineName },
                warn(r.v) + "${r.stop.name} · ${Fmt.dist(r.dist)}" + times(r.next, now, r.v)) {
                show(Screen.LineS(r.v.id, r.stop.id))
            }
        }
    }

    /** "⚠ " when İETT has a current announcement for the line (from Situational sync). */
    private fun warn(v: Store.Variant) = if (v.mode == Mode.BUS && store.noticed().contains(v.ref)) "⚠ " else ""

    /** "Route from here" / "Route to here" for a stop, place or tapped point. */
    private fun routeButtons(p: Pt): View {
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(6)) }
        bar.addView(button("Route from here") { from = p; prefer = null; openRoute() }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        bar.addView(button("Route to here") { to = p; prefer = null; openRoute() }, lpL(WRAP, WRAP))
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) }
    }

    private fun times(next: List<Int>, now: Int, v: Store.Variant): String = when {
        v.ntrips == 0 -> if (v.mode == Mode.BUS) "" else " · no timetable offline"
        next.isEmpty() -> " · no more today"
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
        header("Lines near here", "Within ${Fmt.dist(s.r)} · tap a line to see its route and stops")
        loading()
        map.circle = doubleArrayOf(Geo.mx(s.lon), Geo.my(s.lat), s.r)
        map.lines = emptyList(); map.dots = emptyList()
        val n = now()
        load(t, { nearby(s.lat, s.lon, s.r) }) { rows ->
            showWeb(rows, Geo.mx(s.lon), Geo.my(s.lat))
            header("Lines near here", "${rows.size} lines and directions within ${Fmt.dist(s.r)}")
            body(routeButtons(Pt(s.lat, s.lon, rows.firstOrNull()?.stop?.name?.let { "Near $it" } ?: "Point on map")), *nearRows(rows, n.sec, s.lat, s.lon).toTypedArray())
        }
    }

    // ------------------------------------------------------------------ stop
    private class StopData(val stop: Store.StopR, val rows: List<Triple<Store.Variant, Int, List<Int>>>, val geoms: List<List<DoubleArray>>, val notices: List<Store.Notice>)

    private fun renderStop(s: Screen.StopS, t: Int) {
        header("Stop", null)
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
            val sub = (if (st.mode == Mode.BUS) "Bus stop" + (if (st.code.isNotEmpty()) " ${st.code}" else "") else Mode.name(st.mode) + " station") +
                " · ${d.rows.map { it.first.line }.distinct().size} lines"
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
                setPadding(dp(10), dp(8), dp(10), dp(8)); background = roundRect(0x33FF9800, dpf(8))
                layoutParams = lpL(MATCH, WRAP).apply { bottomMargin = dp(6) }
            })
            if (d.rows.isEmpty()) views.add(note("No departures from this stop in the timetable."))
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
        header("Line", null)
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
                (if (v.mode == Mode.BUS) " · $tripsToday trips today" else ""), badge(v.ref, col))
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
            if (opposite != null) bar.addView(button("⇄ Other direction") {
                s.variant = opposite.id
                s.focus = nearestStopIn(opposite, d.stops[s.focus])
                render()
            }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
            if (d.others.size > 2) bar.addView(button("Variants (${d.others.size})") { chooseVariant(s, d) }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
            if (v.ntrips > 0) bar.addView(button(if (s.timetable) "Stops" else "Timetable") {
                s.timetable = !s.timetable
                if (s.day == 0) s.day = n.today
                render()
            })
            views.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })

            for (nt in d.notices) {
                views.add(text("⚠ İETT notice · ${nt.kind} · ${nt.at}\n${nt.msg}", 13f, 0xFFFFCC80.toInt()).apply {
                    setPadding(dp(10), dp(8), dp(10), dp(8)); background = roundRect(0x33FF9800, dpf(8))
                    layoutParams = lpL(MATCH, WRAP).apply { bottomMargin = dp(6) }
                })
            }
            var focusRow: View? = null
            if (s.timetable) {
                views.addAll(timetable(s, d))
            } else {
                if (v.mode != Mode.BUS && v.ntrips == 0) views.add(note("Metro, tram and ferry lines come from OpenStreetMap, without timetables."))
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
        val items = d.others.map { o -> "→ ${o.headsign} · ${o.stops.size} stops" + (if (o.ntrips > 0) " · ${o.ntrips} trips/week" else "") }
        android.app.AlertDialog.Builder(this).setTitle(d.v.ref + " variants")
            .setItems(items.toTypedArray()) { _, i ->
                val o = d.others[i]
                bg.execute {
                    val f = nearestStopIn(o, d.stops[s.focus])
                    main.post { s.variant = o.id; s.focus = f; render() }
                }
            }.show()
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
        for ((label, mask) in listOf("Weekdays" to weekday, "Saturday" to sat, "Sunday" to sun)) {
            val on = s.day and mask != 0
            days.addView(button(label, if (on) C.ACCENT else C.CARD, if (on) 0xFF06262B.toInt() else C.TEXT) {
                s.day = if (mask == weekday) 1 else mask
                render()
            }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        }
        out.add(days)
        out.add(note("Departures at ${st?.name ?: "the first stop"}" + if (idx > 0) " (estimated from the first stop's times)" else ""))
        val dayMask = if (s.day and weekday != 0 && s.day and (sat or sun) == 0) (s.day and weekday).let { if (it == 0) 1 else it } else s.day
        val times = Times.all(d.trips, v.dists, idx, dayMask)
        if (times.isEmpty()) { out.add(note("No trips on this day.")); return out }
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
        header("Place", null)
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
            info("Address", addr)
            info("Opening hours", tags["opening_hours"]?.replace(";", "\n"))
            val phone = tags["phone"] ?: tags["contact:phone"] ?: tags["mobile"] ?: tags["contact:mobile"]
            info("Phone", phone) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + phone!!.split(';').first().replace(" ", "")))) }
            val web = tags["website"] ?: tags["contact:website"] ?: tags["url"]
            info("Website", web) { open(web!!) }
            info("Instagram", tags["contact:instagram"]) { open(tags["contact:instagram"]!!.let { if (it.startsWith("http")) it else "https://instagram.com/$it" }) }
            info("Email", tags["email"] ?: tags["contact:email"])
            info("Cuisine", tags["cuisine"]?.split(';')?.joinToString(", ") { Poi.pretty(it) })
            info("Brand / operator", tags["brand"] ?: tags["operator"])
            info("Wheelchair", tags["wheelchair"])
            info("Description", tags["description"])
            info("Name in English", tags["name:en"])
            if (rows.isNotEmpty()) {
                views.add(text("Lines within 400 m", 14f, C.TEXT, bold = true).apply { setPadding(dp(6), dp(12), dp(6), dp(4)) })
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
        if (stack.lastOrNull() is Screen.Search) { stack[stack.size - 1] = Screen.Search(q); render() } else show(Screen.Search(q))
    }

    private fun renderSearch(s: Screen.Search, t: Int) {
        header("“${s.q}”", null)
        loading()
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        val near = Geo.lat(map.cy) to Geo.lon(map.cx)
        val view = map.visibleNorm()
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val kinds = prefs.getInt("sk", Store.Kinds.ALL)
        val cats = prefs.getString("sc", null)?.let { s -> if (s == "*") null else s.split(',').mapNotNull { it.toIntOrNull() }.toSet() }
        fun inView(x: Double, y: Double) = x in view[0]..view[2] && y in view[1]..view[3]
        class Found(val hits: List<Store.Hit>, val glowLines: Map<Long, List<List<DoubleArray>>>)
        load(t, {
            val hits = store.search(s.q, near, kinds, cats)
            // lines with a stretch inside the visible map glow too
            val gl = LinkedHashMap<Long, List<List<DoubleArray>>>()
            for (h in hits.filter { it.kind == Store.HitKind.LINE }.take(8)) {
                val geoms = store.variantsOfLine(h.id.toInt()).take(2).map { v -> store.geometry(v).map { Pieces.merc(it) } }
                if (geoms.any { g -> g.any { p -> (0 until p.size / 2).any { inView(p[2 * it], p[2 * it + 1]) } } }) gl[h.id] = geoms
            }
            Found(hits, gl)
        }) { f ->
            val hits = f.hits
            header("“${s.q}”", if (hits.isEmpty()) "Nothing found" else "${hits.size} results" +
                (if (kinds != Store.Kinds.ALL || cats != null) " · filtered" else ""))
            val views = ArrayList<View>()
            views.add(searchFilters(kinds, cats))
            var lastKind = -1
            val glowDots = ArrayList<MapView.Dot>()
            val glowLines = ArrayList<MapView.Line>()
            for (h in hits) {
                if (h.kind != lastKind) {
                    lastKind = h.kind
                    val label = when (h.kind) { Store.HitKind.LINE -> "Lines"; Store.HitKind.STOP -> "Stops"; Store.HitKind.PLACE -> "Places"; else -> "Streets" }
                    views.add(text(label, 13f, C.DIM, bold = true).apply { setPadding(dp(6), dp(10), dp(6), dp(2)) })
                }
                val x = Geo.mx(h.lon); val y = Geo.my(h.lat)
                val visible = if (h.kind == Store.HitKind.LINE) f.glowLines.containsKey(h.id) else inView(x, y)
                if (visible) {
                    if (h.kind == Store.HitKind.LINE) f.glowLines[h.id]?.forEach { g -> glowLines.add(MapView.Line(g, h.color, 3f, casing = false, glow = true)) }
                    else glowDots.add(MapView.Dot(x, y, h.color, 4.5f, 0xFF000000.toInt(), glow = true))
                }
                val lead: View = if (h.badge != null) badge(h.badge, h.color) else View(this).apply { background = oval(h.color) }
                views.add(row(lead, h.title, h.sub + if (visible) " · in view" else "", leadSize = if (h.badge != null) 0 else dp(12)) {
                    hideKeyboard(); search.clearFocus()
                    pick(h)
                })
            }
            body(*views.toTypedArray())
            map.lines = glowLines
            map.dots = glowDots
        }
    }

    private val placeCats = listOf(
        Poi.FOOD to "Food & drink", Poi.GROCERY to "Groceries & bakeries", Poi.SHOP to "Other shops", Poi.HEALTH to "Health & pharmacies",
        Poi.MONEY to "Banks & ATMs", Poi.TRANSPORT to "Transport & fuel", Poi.CULTURE to "Culture & sights", Poi.LODGING to "Hotels",
        Poi.WORSHIP to "Places of worship", Poi.EDUCATION to "Schools & libraries", Poi.PUBLIC to "Public services", Poi.LEISURE to "Parks & sport",
        Poi.PLACE to "Neighbourhoods & towns", Poi.OTHER to "Other",
    )

    /** Toggle chips for what the search looks for, plus a list to pick place types. */
    private fun searchFilters(kinds: Int, cats: Set<Int>?): View {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(2), 0, dp(4)) }
        for ((label, bit) in listOf("Lines" to Store.Kinds.LINES, "Stops" to Store.Kinds.STOPS, "Places" to Store.Kinds.PLACES, "Streets" to Store.Kinds.STREETS)) {
            val on = kinds and bit != 0
            bar.addView(button(label, if (on) C.ACCENT else C.CARD, if (on) 0xFF06262B.toInt() else C.DIM) {
                val k = kinds xor bit
                prefs.edit().putInt("sk", if (k == 0) Store.Kinds.ALL else k).apply()
                render()
            }, lpL(WRAP, WRAP).apply { rightMargin = dp(6) })
        }
        val n = cats?.size ?: placeCats.size
        bar.addView(button("Place types ($n/${placeCats.size})", if (cats != null) C.ACCENT else C.CARD, if (cats != null) 0xFF06262B.toInt() else C.TEXT) {
            val checked = BooleanArray(placeCats.size) { cats == null || placeCats[it].first in cats }
            android.app.AlertDialog.Builder(this).setTitle("Show these places")
                .setMultiChoiceItems(placeCats.map { it.second }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
                .setPositiveButton("Apply") { _, _ ->
                    val sel = placeCats.indices.filter { checked[it] }.map { placeCats[it].first }
                    prefs.edit().putString("sc", if (sel.size == placeCats.size) "*" else sel.joinToString(",")).apply()
                    render()
                }
                .setNeutralButton("All") { _, _ -> prefs.edit().putString("sc", "*").apply(); render() }
                .setNegativeButton("Cancel", null).show()
        })
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
        if (st != null) return Pt(lat, lon, if (st.second < 60) st.first.name else "Near ${st.first.name}")
        val p = store.poisNear(lat, lon, 200.0, 1).firstOrNull()
        if (p != null) return Pt(lat, lon, "Near ${p.first.name.ifEmpty { p.first.label }}")
        return Pt(lat, lon, "Point on map")
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
        header("Route", "By line contacts: fewest changes, ending near the destination")
        map.circle = null
        val a = from; val b = to
        val views = ArrayList<View>()
        fun endRow(label: String, p: Pt?, which: Int): View {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(6), dp(4), 0, dp(4)) }
            r.addView(View(this).apply { background = oval(if (which == 1) 0xFF66BB6A.toInt() else 0xFFEF5350.toInt()) }, lpL(dp(12), dp(12)).apply { rightMargin = dp(10) })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(text(label, 12f, C.DIM))
            col.addView(text(if (picking == which) "Tap the map…" else p?.name ?: "Not set", 15f, if (picking == which) C.ACCENT else C.TEXT, lines = 1))
            r.addView(col, lpL(0, WRAP, 1f))
            r.addView(button("Map", if (picking == which) C.ACCENT else C.CARD, if (picking == which) 0xFF06262B.toInt() else C.TEXT) {
                picking = if (picking == which) 0 else which
                render()
            }, lpL(WRAP, WRAP).apply { leftMargin = dp(6) })
            if (map.hasMe()) r.addView(button("Me") {
                val p0 = Pt(map.meLat(), map.meLon(), "My location")
                if (which == 1) from = p0 else to = p0
                picking = 0; prefer = null; render()
            }, lpL(WRAP, WRAP).apply { leftMargin = dp(6) })
            return r
        }
        views.add(endRow("From", a, 1))
        views.add(endRow("To", b, 2))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, dp(4)) }
        bar.addView(button("⇅ Swap") { val x = from; from = to; to = x; prefer = null; render() }, lpL(WRAP, WRAP).apply { rightMargin = dp(8) })
        bar.addView(button("My routes ▾") { myRoutes() }, lpL(WRAP, WRAP))
        views.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(bar) })
        val dots = ArrayList<MapView.Dot>()
        a?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFF66BB6A.toInt(), 5f, 0xFF000000.toInt())) }
        b?.let { dots.add(MapView.Dot(Geo.mx(it.lon), Geo.my(it.lat), 0xFFEF5350.toInt(), 5f, 0xFF000000.toInt())) }
        map.dots = dots
        map.lines = emptyList()
        if (picking != 0) views.add(note("Tap the map to set the ${if (picking == 1) "start" else "destination"}. Pan and zoom first if you like."))
        if (a == null || b == null) {
            if (picking == 0) views.add(note("Set both ends: pick them on the map, use your last location, or use “Route from/to here” on a stop or place."))
            body(*views.toTypedArray())
            return
        }
        views.add(note("Finding routes…"))
        body(*views.toTypedArray())
        val finding = views.last()
        load(t, {
            val net = store.network() ?: return@load null
            routeOptions(net, a, b)
        }) { opts ->
            sheetBody.removeView(finding)
            if (opts.isEmpty()) {
                sheetBody.addView(note("No line connection found within 500 m of both ends and two changes. Try points closer to a stop."))
                return@load
            }
            val pref = prefer
            prefer = null
            val n = now()
            opts.forEachIndexed { i, o ->
                val first = o.legs.first()
                val dep = first.next.firstOrNull()?.let { " · next ${Times.mins(it, n.sec)}" } ?: if (first.v.ntrips == 0 && first.v.mode != Mode.BUS) "" else " · no more today"
                val sub = (if (o.j.changes == 0) "Direct" else "${o.j.changes} change${if (o.j.changes > 1) "s" else ""}") +
                    " · ${String.format(java.util.Locale.US, "%.1f", o.j.rideMeters / 1000.0)} km by line · ends ${Fmt.dist(o.j.endDist.toDouble())} from the destination" +
                    "\nBoard at ${first.board.name}$dep"
                val r = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8))
                    background = ripple(roundRect(if (i == 0) 0x1826C6DA else 0x0FFFFFFF, dpf(10)))
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

    private fun renderLegs(s: Screen.Legs) {
        val o = s.opt
        header(if (o.j.changes == 0) "Direct" else "${o.j.changes} change${if (o.j.changes > 1) "s" else ""}",
            "${from?.name ?: "?"} → ${to?.name ?: "?"}", legBadges(o))
        map.circle = null
        val n = now()
        val views = ArrayList<View>()
        if (o.j.startDist > 30) views.add(note("Start: ${o.legs.first().board.name} is about ${Fmt.dist(o.j.startDist.toDouble())} from your start."))
        o.legs.forEachIndexed { i, l ->
            if (i > 0) {
                val prev = o.legs[i - 1].alight
                val gap = Geo.dist(prev.lat, prev.lon, l.board.lat, l.board.lon)
                views.add(note(if (gap < 40) "Change at ${l.board.name}" else "Change: ${prev.name} → ${l.board.name} (about ${Fmt.dist(gap)})", C.ACCENT))
            }
            val dep = when {
                l.next.isNotEmpty() -> "next " + l.next.joinToString(", ") { Times.mins(it, n.sec) }
                l.v.mode != Mode.BUS -> "no timetable offline"
                else -> "no more today"
            }
            val stopsN = l.alightIdx - l.boardIdx
            views.add(row(badge(l.v.ref, l.color), "→ " + l.v.headsign.ifEmpty { l.v.lineName },
                warn(l.v) + "Board at ${l.board.name} · $dep\n$stopsN stop${if (stopsN > 1) "s" else ""} " +
                    "(${String.format(java.util.Locale.US, "%.1f", (l.v.dists[l.alightIdx] - l.v.dists[l.boardIdx]) / 1000.0)} km) to ${l.alight.name}") {
                show(Screen.LineS(l.v.id, l.board.id))
            })
        }
        views.add(note("Get off at ${o.legs.last().alight.name}, about ${Fmt.dist(o.j.endDist.toDouble())} from the destination."))
        views.add(button("★ Save to My routes", C.ACCENT, 0xFF06262B.toInt()) { saveRoute(o) }.apply {
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
            val arr = org.json.JSONArray(getSharedPreferences("app", MODE_PRIVATE).getString("routes", "[]"))
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
        getSharedPreferences("app", MODE_PRIVATE).edit().putString("routes", arr.toString()).apply()
    }

    private fun saveRoute(o: RouteOpt) {
        val a = from ?: return; val b = to ?: return
        val et = EditText(this).apply { setText("${a.name} → ${b.name}"); setSingleLine(); selectAll() }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) }
        android.app.AlertDialog.Builder(this).setTitle("Save route (${o.key.replace(">", " › ")})").setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "${a.name} → ${b.name}" }
                val l = favs()
                l.removeAll { it.name == name }
                l.add(0, Fav(name, a, b, o.key))
                saveFavs(l)
                android.widget.Toast.makeText(this, "Saved to My routes", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun myRoutes() {
        val l = favs()
        if (l.isEmpty()) {
            android.widget.Toast.makeText(this, "No saved routes yet. Open a route option and tap “Save to My routes”.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        android.app.AlertDialog.Builder(this).setTitle("My routes")
            .setItems(l.map { it.name + "\n   " + it.lines.replace(">", " › ") }.toTypedArray()) { _, i ->
                val f = l[i]
                android.app.AlertDialog.Builder(this).setTitle(f.name)
                    .setItems(arrayOf("Open", "Delete")) { _, k ->
                        if (k == 0) {
                            from = f.a; to = f.b; prefer = f.lines; picking = 0
                            if (stack.lastOrNull() is Screen.Route) render() else show(Screen.Route)
                        } else {
                            android.app.AlertDialog.Builder(this).setTitle("Delete “${f.name}”?")
                                .setPositiveButton("Delete") { _, _ -> saveFavs(favs().filterNot { it.name == f.name }) }
                                .setNegativeButton("Cancel", null).show()
                        }
                    }.show()
            }
            .setNegativeButton("Close", null).show()
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
        header("No offline data yet", "Plain ${City.name} Map works offline after one sync")
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        body(
            note("Sync downloads the ${City.name} map, places, metro/tram/ferry lines (OpenStreetMap) and all ${City.operator} bus lines, stops and timetables. " +
                "It needs Wi-Fi and ${City.downloadSize} of download, and takes 10–60 minutes. After that, everything works offline."),
            button("Open sync settings", C.ACCENT, 0xFF06262B.toInt()) { show(Screen.Settings) },
        )
    }

    private fun renderSettings() {
        header("Settings", "Offline data")
        map.circle = null; map.lines = emptyList(); map.dots = emptyList()
        val views = ArrayList<View>()
        val info = text("…", 14f).apply { setPadding(dp(6), dp(4), dp(6), dp(8)) }
        views.add(info)
        bg.execute {
            val s = if (store.ready) {
                val at = store.meta("synced_at")?.toLongOrNull()
                "Last sync: " + (at?.let { Fmt.date(it) } ?: "?") + "\n" +
                    (if (City.ankara) "EGO timetables read: " else "İETT timetable published: ") + (store.meta("gtfs_date") ?: "?") + "\n" +
                    (store.meta("ego_stats")?.let { it + "\n" } ?: "") +
                    store.counts() + "\n" +
                    "Size on phone: " + Fmt.mb(store.file.length()) +
                    (store.meta("outskirts_missing")?.toIntOrNull()?.takeIf { it > 0 }
                        ?.let { "\n$it outer squares were left out (OpenStreetMap server busy). Sync again later to fill them in." } ?: "") +
                    if (City.istanbul && !store.hasLand) "\nCoastline: not complete in this sync (sea is drawn as land)" else ""
            } else "No offline data yet."
            val sit = if (store.ready) store.meta("situational_at")?.toLongOrNull()?.let { at ->
                "Last situational sync: ${Fmt.date(at)} · ${store.meta("situational_lines")?.substringBefore('/') ?: "?"} lines checked, " +
                    "${store.meta("situational_lines")?.substringAfter('/') ?: "?"} routes with new times · ${store.noticed().size} lines with announcements"
            } ?: "No situational sync yet." else ""
            main.post { info.text = s; sitInfo?.text = sit }
        }
        syncBtn = button("", C.ACCENT, 0xFF06262B.toInt()) {
            if (SyncState.running) SyncService.stop() else startSync()
        }
        views.add(syncBtn!!)
        syncBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
        views.add(syncBar!!.also { it.layoutParams = lpL(MATCH, dp(16)).apply { topMargin = dp(10) } })
        syncText = text("", 13f, C.DIM).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        views.add(syncText!!)
        syncErr = text("", 13f, C.RED).apply { setPadding(dp(6), dp(2), dp(6), dp(2)) }
        views.add(syncErr!!)
        // situational sync
        views.add(text("Situational sync", 15f, bold = true).apply { setPadding(dp(6), dp(14), dp(6), dp(2)) })
        sitInfo = text("", 13f, C.DIM).apply { setPadding(dp(6), 0, dp(6), dp(6)) }
        views.add(sitInfo!!)
        sitBtn = button("", C.CARD) { if (SyncState.running) SyncService.stop() else startSync(situational = true) }
        views.add(sitBtn!!)
        views.add(note(if (City.ankara)
            "Fetches EGO's latest announcements (route changes, new services, stop notices; shown with ⚠ on lines and stops) and reads every bus line's " +
                "current departure times again from ego.gov.tr, without downloading the map. About 660 pages on Wi-Fi; a few minutes."
        else
            "Fetches İETT's current announcements (cancelled trips, detours, closed roads; shown with ⚠ on the lines) and today's planned " +
                "departure times of every bus line, without downloading the map again. About 800 small requests on Wi-Fi; usually under a minute."))
        // line looks
        views.add(text("Lines on the map", 15f, bold = true).apply { setPadding(dp(6), dp(14), dp(6), dp(2)) })
        views.add(slider("Bus web thickness", Style.webWidth, 0.3f, 2f) { Style.webWidth = it })
        views.add(slider("Bus web brightness", Style.webAlpha, 0.15f, 2f) { Style.webAlpha = it })
        views.add(slider("Metro, tram and ferry thickness", Style.railWidth, 0.3f, 2f) { Style.railWidth = it })
        views.add(slider("Selected lines thickness", Style.lineWidth, 0.4f, 2f) { Style.lineWidth = it })
        views.add(note(
            "Full sync runs only on Wi-Fi (never mobile data). It downloads ${City.downloadSize} and takes 10–60 minutes; you can leave the app. " +
                "If it stops, the next sync continues where it left off. Nothing runs in the background otherwise.\n\n" + City.mapSourceText
        ))
        views.add(note(
            if (City.ankara) "EGO publishes departure times at the first stop and the trip's length; times at later stops are estimated from them. " +
                "Stops EGO lists without a map position are placed by name near the line, or estimated between their neighbours. On holidays EGO runs the Saturday times."
            else "Times at stops after the first are estimated from the first and last stop's scheduled times. Public holidays are not in the timetable.",
        ))
        views.add(note(
            "Map, places, metro, tram and ferry lines © OpenStreetMap contributors (ODbL). " + City.busSourceText,
        ))
        // Hands off to Plain Setup (shared helper, app/plain/update/PlainUpdate.kt).
        views.add(button(app.plain.update.PlainUpdate.label(this), C.CARD) { app.plain.update.PlainUpdate.check(this) })
        body(*views.toTypedArray())
        onSyncChanged()
    }

    private var sitInfo: TextView? = null
    private var sitBtn: TextView? = null

    /** A labelled slider for a style multiplier; the map is redrawn when it is let go. */
    private fun slider(label: String, value: Float, lo: Float, hi: Float, set: (Float) -> Unit): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(4), dp(6), dp(4)) }
        val t = text("", 13f, C.TEXT)
        fun label(v: Float) { t.text = "$label: ${(v * 100).toInt()} %" }
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
                    Style.save(getSharedPreferences("app", MODE_PRIVATE))
                    tiles.clear()
                    map.invalidate()
                }
            })
        })
        return col
    }

    private fun startSync(situational: Boolean = false) {
        if (Net.wifi(this) == null) {
            syncErr?.text = "Connect to Wi-Fi first. Sync never uses mobile data."
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
        syncBtn?.text = if (SyncState.running && !sit) "Stop sync" else if (store.ready) "Full sync (Wi-Fi)" else "Download offline data (Wi-Fi)"
        sitBtn?.text = if (SyncState.running && sit) "Stop situational sync" else "Situational sync (Wi-Fi)"
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
    private fun row(lead: View, title: String, sub: String?, leadSize: Int = 0, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = ripple(null)
            setOnClickListener { onClick() }
        }
        if (leadSize > 0) r.addView(lead, lpL(leadSize, leadSize).apply { rightMargin = dp(12); leftMargin = dp(4) })
        else r.addView(lead, lpL(WRAP, WRAP).apply { rightMargin = dp(10) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, lines = 1))
        if (!sub.isNullOrEmpty()) col.addView(text(sub, 12.5f, C.DIM, lines = 2))
        r.addView(col, lpL(0, WRAP, 1f))
        return r
    }

    /** A stop in a line's stop list: a track with a dot, the name and the next time. */
    private fun stopRow(i: Int, n: Int, name: String, time: String, color: Int, focus: Boolean, onClick: () -> Unit): View {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(6), 0)
            background = ripple(if (focus) roundRect(0x22FFD740, dpf(8)) else null)
            setOnClickListener { onClick() }
        }
        val track = object : View(this) {
            val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(c: android.graphics.Canvas) {
                val x = width / 2f; val y = height / 2f
                p.color = color; p.strokeWidth = dpf(4)
                c.drawLine(x, if (i == 0) y else 0f, x, if (i == n - 1) y else height.toFloat(), p)
                p.color = 0xFFFFFFFF.toInt(); c.drawCircle(x, y, dpf(if (focus) 6 else 4.5f), p)
                p.color = color; c.drawCircle(x, y, dpf(if (focus) 3.5f else 2.2f), p)
            }
        }
        r.addView(track, lpL(dp(24), dp(40)))
        r.addView(text(name, 14.5f, if (focus) C.HIGHLIGHT else C.TEXT, bold = focus, lines = 1).apply { setPadding(dp(8), 0, 0, 0) }, lpL(0, WRAP, 1f))
        if (time.isNotEmpty()) r.addView(text(time, 13f, C.DIM).apply { typeface = Typeface.MONOSPACE })
        return r
    }

}
