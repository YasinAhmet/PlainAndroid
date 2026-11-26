package app.trekmap.trail

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.location.LocationManager
import android.net.Network
import android.os.StatFs
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONArray
import java.net.URLEncoder
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Planning tab: browse the map (online on Wi-Fi, offline otherwise), search places, download
 * areas for offline use, manage planned trails, and open past trails.
 */
class PlanScreen(private val act: MainActivity) {
    val view = FrameLayout(act)
    private val prefs = act.prefs
    private val map = MapView(act)
    @Volatile private var wifiNet: Network? = null
    private val provider = TileProvider(4, 40 * 1024 * 1024) { l, z, x, y -> fetch(l, z, x, y) }
    private val wifi = Net.WifiWatcher(act) { n -> wifiNet = n; act.runOnUiThread { onWifi(n) } }
    private var layer = listOf(Layer.TOPO, Layer.SAT).firstOrNull { it.name == prefs.getString("planLayer", "TOPO") } ?: Layer.TOPO

    private val search = EditText(act)
    private val wifiLabel = act.text("", 12f, C.DIM)
    private val layerBtn: Button = act.button(layerLabel()) { toggleLayer() }
    private val topCol = LinearLayout(act)
    private val panel = LinearLayout(act)
    private val body = FrameLayout(act)
    private val tabBtns = ArrayList<Button>()
    private var section = -1
    private var listAdapter: BaseAdapter? = null

    private val drawBar = LinearLayout(act)
    private val drawInfo = act.text("", 14f)
    private var drawLine: MLine? = null
    private val snapBtn: Button = act.button("Snap") { toggleSnap() }

    private var preview: MapView.Overlay? = null
    private var visible = false
    private var lastStates = ""
    private val dlListener: () -> Unit = { onDownloadsChanged() }

    init {
        view.isFocusable = true
        view.isFocusableInTouchMode = true
        map.provider = provider
        map.onMarkerTap = { m -> (m.tag as? Pair<*, *>)?.let { (p, d) -> if (p is Photo && d is java.io.File) openPhoto(p, d) } }
        map.layer = layer
        map.attribution = layer.attribution
        map.bg = 0xFF2B2B2B.toInt()
        view.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))
        buildTop()
        buildPanel()
        buildDrawBar()
        restoreView()
        refreshOverlays()
        onWifi(null)
        applySettings()
    }

    // ------------------------------------------------------------------ layout
    private fun buildTop() {
        topCol.orientation = LinearLayout.VERTICAL
        topCol.setPadding(act.dp(8), act.dp(8), act.dp(8), 0)
        val bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(C.PANEL, act.dpf(12))
            setPadding(act.dp(12), act.dp(2), act.dp(3), act.dp(2))
        }
        search.apply {
            hint = "Search a place or forest"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(C.TEXT)
            setHintTextColor(C.DIM)
            background = null
            textSize = 14f
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { doSearch(); true } else false }
        }
        bar.addView(search, lpL(0, WRAP, 1f))
        bar.addView(act.button("Search") { doSearch() }, lpL(WRAP, act.dp(36)))
        topCol.addView(bar, lpL(MATCH, WRAP))

        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        wifiLabel.setPadding(act.dp(8), act.dp(4), act.dp(8), act.dp(4))
        wifiLabel.background = roundRect(C.PANEL, act.dpf(8))
        row.addView(wifiLabel, lpL(WRAP, WRAP))
        row.addView(View(act), lpL(0, 1, 1f))
        row.addView(act.button("⚙") { act.showSettings { applySettings() } }, lpL(act.dp(40), act.dp(34)).apply { marginEnd = act.dp(6) })
        row.addView(layerBtn, lpL(WRAP, act.dp(34)))
        topCol.addView(row, lpL(MATCH, WRAP).apply { topMargin = act.dp(4) })
        view.addView(topCol, lpF(MATCH, WRAP, Gravity.TOP))
        topCol.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ -> map.padTop = b - t }
    }

    private fun buildPanel() {
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(C.PANEL)
        body.visibility = View.GONE
        panel.addView(body, lpL(MATCH, (act.resources.displayMetrics.heightPixels * 0.30).toInt()))
        val segRow = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4)) }
        listOf("Maps", "Plans", "Past trails").forEachIndexed { i, t ->
            val b = act.button(t) { showSection(if (section == i) -1 else i) }
            tabBtns.add(b)
            segRow.addView(b, lpL(0, act.dp(36), 1f).apply { marginStart = act.dp(2); marginEnd = act.dp(2) })
        }
        panel.addView(segRow, lpL(MATCH, WRAP))
        view.addView(panel, lpF(MATCH, WRAP, Gravity.BOTTOM))
        panel.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ ->
            if (panel.visibility == View.VISIBLE) { map.padBottom = b - t; map.invalidate() }
        }
    }

    private fun buildDrawBar() {
        drawBar.orientation = LinearLayout.VERTICAL
        drawBar.setBackgroundColor(C.PANEL)
        drawBar.setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
        drawBar.addView(drawInfo, lpL(MATCH, WRAP).apply { bottomMargin = act.dp(6) })
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(snapBtn, lpL(0, act.dp(36), 1f).apply { marginEnd = act.dp(6) })
        row.addView(act.button("Undo") { undoDraw() }, lpL(0, act.dp(36), 1f).apply { marginEnd = act.dp(6) })
        row.addView(act.button("Save", C.GREEN) { saveDraw() }, lpL(0, act.dp(36), 1f).apply { marginEnd = act.dp(6) })
        row.addView(act.button("Cancel") { endDraw() }, lpL(0, act.dp(36), 1f))
        drawBar.addView(row)
        drawBar.visibility = View.GONE
        view.addView(drawBar, lpF(MATCH, WRAP, Gravity.BOTTOM))
    }

    // ------------------------------------------------------------------ lifecycle
    fun onVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) {
            wifi.start()
            Downloads.listen(dlListener)
        } else {
            wifi.stop()
            wifiNet = null
            Downloads.unlisten(dlListener)
            saveView() // HiOS may kill us in the background; keep the framed area
            Dev.event("render", "plan map: ${map.takeStats()} · ${provider.stats()}")
        }
    }

    /** Screen gone (tab switch / activity destroyed): background results must not open dialogs. */
    private var destroyed = false
    private fun gone() = destroyed || act.isFinishing || act.isDestroyed || Trail.active

    fun destroy() {
        destroyed = true
        onVisible(false)
        saveView()
        map.provider = null
        provider.shutdown()
    }

    fun onBack(): Boolean {
        if (drawLine != null) { endDraw(); return true }
        if (section != -1) { showSection(-1); return true }
        return false
    }

    private fun saveView() {
        prefs.edit().putString("planView", "${map.cx},${map.cy},${map.zoom}").apply()
    }

    private fun restoreView() {
        val p = prefs.getString("planView", null)?.split(',')?.mapNotNull { it.toDoubleOrNull() }
        if (p != null && p.size == 3) {
            map.cx = p[0]; map.cy = p[1]; map.zoom = p[2]
            return
        }
        Packs.all().firstOrNull()?.let { map.fit(it.bbox); return }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                val lm = act.getSystemService(LocationManager::class.java)
                val l = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (l != null) { map.centerOn(l.latitude, l.longitude, 12.0); return }
            } catch (_: SecurityException) {}
        }
        map.centerOn(39.0, 35.0, 5.0)
    }

    // ------------------------------------------------------------------ map data
    private fun fetch(l: Layer, z: Int, x: Int, y: Int): ByteArray? {
        Packs.read(l, z, x, y)?.let { return it }
        if (l == Layer.LAND) return TileProvider.EMPTY // only ever in downloaded maps
        val n = wifiNet ?: return TileProvider.EMPTY // offline: show what we have (scaled parents)
        return try {
            Net.get(n, l.url(z, x, y))
        } catch (e: Net.Http) {
            if (e.code == 404) TileProvider.EMPTY else null
        } catch (e: Exception) {
            null
        }
    }

    private var lastWifiLogged: Boolean? = null

    private fun onWifi(n: Network?) {
        if (visible && lastWifiLogged != (n != null)) {
            lastWifiLogged = n != null
            Dev.event("net", "plan screen: Wi-Fi ${if (n != null) "available" else "not available"}")
        }
        wifiLabel.text = if (n != null) "● Wi-Fi · online map" else "○ No Wi-Fi · offline maps only"
        wifiLabel.setTextColor(if (n != null) C.WIFI else C.DIM)
        provider.clearMissing()
        map.invalidate()
    }

    /** Applies the Settings toggles that affect this screen. */
    fun applySettings() {
        map.stats = if (Prefs.stats(act)) ({ planStats() }) else null
        map.dim = if (Prefs.dim(act)) 0.45f else 0f
        map.overlayLayer = if (Prefs.land(act) && Packs.has(Layer.LAND)) Layer.LAND else null
        map.attribution = layer.attribution + if (map.overlayLayer != null) " · " + Layer.LAND.attribution else ""
        if (drawLine != null) { updateSnapBtn(); updateDrawInfo() }
    }

    private fun planStats(): List<String> {
        val lat = Geo.lat(map.cy)
        val mpp = Geo.metersPerUnit(lat) / map.world()
        val detail = when {
            map.drawnZ < 0 -> "no map here"
            map.drawnZ < map.tileZ -> "z${map.drawnZ} scaled ×${1 shl (map.tileZ - map.drawnZ)} (less detail)"
            else -> "full detail"
        }
        return listOf(
            "Map z${map.tileZ} · $detail · ${"%.1f".format(Locale.US, mpp)} m/px",
            "Centre ${"%.5f".format(Locale.US, lat)}, ${"%.5f".format(Locale.US, Geo.lon(map.cx))}",
            if (wifiNet != null) "Wi-Fi: online map + offline packs" else "No Wi-Fi: offline packs only",
        )
    }

    private fun layerLabel() = if (layer == Layer.TOPO) "Satellite" else "Topo"

    private fun toggleLayer() {
        layer = if (layer == Layer.TOPO) Layer.SAT else Layer.TOPO
        prefs.edit().putString("planLayer", layer.name).apply()
        map.layer = layer
        layerBtn.text = layerLabel()
        applySettings()
    }

    private fun refreshOverlays() {
        val draw = drawLine?.let { MapView.Overlay(listOf(it), C.PLAN, 4f, endpoints = true) }
        map.overlays = listOfNotNull(preview, draw)
        map.rects = Packs.all().filter { it.corridorM <= 0 }.map { it.bbox }
        map.invalidate()
    }

    private fun onDownloadsChanged() {
        if (section == 0) listAdapter?.notifyDataSetChanged()
        val states = Packs.all().joinToString { "${it.state}/${it.land}" }
        if (states != lastStates) {
            lastStates = states
            provider.clearMissing()
            applySettings()
            refreshOverlays()
        }
    }

    // ------------------------------------------------------------------ search
    private fun doSearch() {
        val q = search.text.toString().trim()
        if (q.isEmpty()) return
        val n = wifiNet
        if (n == null) { act.toast("Search works on Wi-Fi only (mobile data is never used)."); return }
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(search.windowToken, 0)
        view.requestFocus() // otherwise focus (and the keyboard) bounces back to the search box
        thread {
            val t0 = System.currentTimeMillis()
            try {
                val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=10&q=" + URLEncoder.encode(q, "UTF-8")
                val arr = JSONArray(String(Net.get(n, url, mapOf("Accept-Language" to Locale.getDefault().toLanguageTag())), Charsets.UTF_8))
                val results = ArrayList<Pair<String, BBox>>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val bb = o.optJSONArray("boundingbox") ?: continue
                    val box = BBox(bb.getString(2).toDouble(), bb.getString(0).toDouble(), bb.getString(3).toDouble(), bb.getString(1).toDouble())
                    results += o.optString("display_name", o.optString("name")) to box
                }
                Dev.event("search", "“$q” → ${results.size} results in ${System.currentTimeMillis() - t0} ms")
                act.runOnUiThread {
                    if (gone()) return@runOnUiThread
                    if (results.isEmpty()) act.toast("Nothing found for “$q”.")
                    else act.choose("Results", results.map { (name, box) ->
                        name to {
                            map.fit(box)
                        }
                    })
                }
            } catch (e: Exception) {
                Dev.event("search", "“$q” failed after ${System.currentTimeMillis() - t0} ms: $e")
                act.runOnUiThread { act.toast("Search failed: ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ sections
    fun showSection(i: Int) {
        section = i
        listAdapter = null
        body.removeAllViews()
        tabBtns.forEachIndexed { k, b -> b.background = ripple(roundRect(if (k == i) C.GREEN else C.CARD, act.dpf(10))) }
        if (i < 0) {
            body.visibility = View.GONE
            return
        }
        body.visibility = View.VISIBLE
        when (i) {
            0 -> buildMaps()
            1 -> buildPlans()
            2 -> buildPast()
        }
    }

    private fun column() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(act.dp(6), act.dp(4), act.dp(6), 0)
        body.addView(this, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun hint(box: LinearLayout, s: String) {
        box.addView(act.text(s, 12f, C.DIM).apply { setPadding(act.dp(4), act.dp(4), act.dp(4), act.dp(4)) })
    }

    private fun <T> addList(box: LinearLayout, items: List<T>, l1: (T) -> String, l2: (T) -> String, tap: (T) -> Unit, hold: (T) -> Unit) {
        val lv = ListView(act)
        lv.divider = ColorDrawable(0xFF2E2E2E.toInt())
        lv.dividerHeight = 1
        val ad = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(p: Int) = items[p]
            override fun getItemId(p: Int) = p.toLong()
            override fun getView(p: Int, cv: View?, parent: ViewGroup?): View {
                val v = (cv as? LinearLayout) ?: LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(act.dp(8), act.dp(6), act.dp(8), act.dp(6))
                    addView(act.text("", 15f))
                    addView(act.text("", 12f, C.DIM))
                }
                (v.getChildAt(0) as TextView).text = l1(items[p])
                (v.getChildAt(1) as TextView).text = l2(items[p])
                return v
            }
        }
        lv.adapter = ad
        lv.setOnItemClickListener { _, _, p, _ -> tap(items[p]) }
        lv.setOnItemLongClickListener { _, _, p, _ -> hold(items[p]); true }
        listAdapter = ad
        box.addView(lv, lpL(MATCH, 0, 1f))
    }

    // Maps --------------------------------------------------------------
    private fun buildMaps() {
        val box = column()
        box.addView(act.button("⬇  Download map for a trail", C.GREEN) { pickTrailForDownload() }, lpL(MATCH, act.dp(36)))
        val packs = Packs.all()
        if (packs.isEmpty()) {
            hint(box, "No offline maps yet. Pick a planned or past trail and the map along it is downloaded (Wi-Fi only).")
            return
        }
        hint(box, "Tap to show · hold for options")
        addList(box, packs, { it.name }, { packLine(it) }, { map.fit(it.bbox) }, { packMenu(it) })
    }

    private fun packLine(p: Pack): String {
        val pr = Downloads.prog[p.id]
        val running = Downloads.isActive(p.id)
        val st = when {
            running && p.state == "downloading" && pr != null ->
                if (pr.waitingWifi) "Waiting for Wi-Fi" else "Downloading ${if (pr.total > 0) pr.done * 100 / pr.total else 0}%"
            running -> "Queued (Wi-Fi only)"
            p.state == "done" -> "Ready offline"
            p.state == "incomplete" -> "Incomplete — hold › Resume"
            else -> "Paused — hold › Resume"
        }
        return "$st · ${Fmt.mb(Packs.sizeBytes(p))} · z${p.minZ}–${p.maxZ} · ${p.layers.joinToString(" + ") { it.label }}" +
            (if (p.corridorM > 0) " · ±${Fmt.dist(p.corridorM)} of trail" else "") +
            (if (Layer.SAT in p.layers && p.satMs > 0) " · imagery ${Season.month(p.satMs)}" else "") +
            when (p.land) { 2 -> " · private land ✓"; 1 -> " · private land pending"; else -> "" }
    }

    private fun packMenu(p: Pack) {
        val running = Downloads.isActive(p.id)
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (running) items += "Pause download" to { Downloads.pause(p.id); act.toast("Pausing…") }
        else if (p.state != "done") items += "Resume download (Wi-Fi only)" to {
            Downloads.start(act, p.id)
            if (wifiNet == null) act.toast("Queued — it continues when Wi-Fi is connected.")
        }
        items += "Show on map" to { map.fit(p.bbox) }
        if (!running && p.land != 2) items += (if (p.land == 1) "Build private-land layer (Wi-Fi)" else "Add private-land layer (Wi-Fi)") to {
            p.land = 1
            Packs.saveMeta(p)
            Downloads.start(act, p.id)
            act.toast(if (wifiNet == null) "Queued — it's built when Wi-Fi is connected." else "Building the private-land layer…")
        }
        if (!running && p.land == 2) items += "Rebuild private-land layer (Wi-Fi)" to {
            p.land = 1
            Packs.saveMeta(p)
            Downloads.start(act, p.id)
        }
        items += "Rename" to { act.askText("Rename map", p.name) { Packs.rename(p, it); showSection(0) } }
        items += "Delete" to {
            if (Downloads.isActive(p.id)) act.toast("Pause the download first, then delete.")
            else act.confirm("Delete “${p.name}”?", "Frees ${Fmt.mb(Packs.sizeBytes(p))}. This can't be undone.", "Delete") {
                Packs.delete(p)
                provider.clearAll()
                refreshOverlays()
                showSection(0)
            }
        }
        act.choose(p.name, items)
    }

    // Plans -------------------------------------------------------------
    private fun buildPlans() {
        val box = column()
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(act.button("Import GPX / KML") { act.pickFile() }, lpL(0, act.dp(36), 1f).apply { marginEnd = act.dp(6) })
        row.addView(act.button("Draw on map") { startDraw() }, lpL(0, act.dp(36), 1f))
        box.addView(row, lpL(MATCH, WRAP))
        val plans = Store.list(Kind.PLAN)
        if (plans.isEmpty()) {
            hint(box, "No planned trails yet. Import a GPX/KML/KMZ (e.g. from Wikiloc or desktop Trekmap), or draw one on the map. You can also share a GPX file to this app.")
            return
        }
        hint(box, "Tap to show · hold for options")
        addList(box, plans, { it.name }, { e ->
            Fmt.dist(e.distM) + if (e.ascM >= 1) " · ↑ ${e.ascM.roundToInt()} m" else ""
        }, { showRoute(it, C.PLAN) }, { planMenu(it) })
    }

    private fun planMenu(e: Entry) {
        act.choose(e.name, listOf(
            "Download map along this trail" to { downloadFor(e) },
            "Show on map" to { showRoute(e, C.PLAN) },
            "Share GPX" to { act.share(e) },
            "Rename" to { act.askText("Rename plan", e.name) { Store.rename(e, it); showSection(1) } },
            "Delete" to { act.confirm("Delete “${e.name}”?", "This can't be undone.", "Delete") { Store.delete(e); clearPreview(); showSection(1) } },
        ))
    }

    // Past trails -------------------------------------------------------
    private fun buildPast() {
        val box = column()
        if (Dev.ENABLED) {
            box.addView(act.button("Share debug logs (development)") { shareDebug() }, lpL(MATCH, act.dp(34)))
        }
        val tracks = Store.list(Kind.TRACK)
        if (tracks.isEmpty()) {
            hint(box, "Trails you record in the Trail tab are saved here as GPX files.")
            return
        }
        hint(box, "Tap to show · hold to share, save as file, rename…")
        addList(box, tracks, { it.name }, { e ->
            "${Fmt.date(e.time)} · ${Fmt.dist(e.distM)} · ${Fmt.dur(e.durS)} · ↑ ${e.ascM.roundToInt()} m" +
                Photos.count(e).let { n -> if (n > 0) " · 📷 $n" else "" }
        }, { showRoute(it, C.PREVIEW) }, { pastMenu(it) })
    }

    private fun pastMenu(e: Entry) {
        act.choose(e.name, listOf(
            "Share GPX" to { act.share(e) },
            "Share with photos (.zip)" to { exportShare(e, kmz = false) },
            "Share for Google Earth (.kmz)" to { exportShare(e, kmz = true) },
            "Save as file…" to { act.exportFile(e) },
            "Download map along this trail" to { downloadFor(e) },
            "Use as planned trail" to {
                thread {
                    val msg = try {
                        val r = Store.load(e)
                        if (r == null) "Could not read this trail." else {
                            Store.save(Kind.PLAN, r, e.name)
                            "Added “${e.name}” to Plans."
                        }
                    } catch (ex: Exception) {
                        "Could not add it: ${ex.message}"
                    }
                    act.runOnUiThread { act.toast(msg) }
                }
            },
            "Show on map" to { showRoute(e, C.PREVIEW) },
            "Rename" to { act.askText("Rename trail", e.name) { Store.rename(e, it); showSection(2) } },
            "Delete" to { act.confirm("Delete “${e.name}”?", "The GPX file is deleted. This can't be undone.", "Delete") { Store.delete(e); clearPreview(); showSection(2) } },
        ))
    }

    private fun shareDebug() {
        act.confirm(
            "Share debug logs?",
            "One zip with every recording's raw GPS log (your precise positions and times), app events, " +
                "crash reports and phone details. Only share it with someone working on the app.",
            "Share",
        ) {
            act.toast("Packing logs…")
            thread {
                val msg = try {
                    val z = Dev.shareZip(act)
                    act.runOnUiThread { act.shareDebugZip(z) }
                    null
                } catch (e: Exception) {
                    "Could not pack logs: ${e.message}"
                }
                if (msg != null) act.runOnUiThread { act.toast(msg) }
            }
        }
    }

    private fun exportShare(e: Entry, kmz: Boolean) {
        act.toast("Packing “${e.name}”…")
        thread {
            try {
                val t0 = System.currentTimeMillis()
                val f = if (kmz) Photos.exportKmz(act, e, Store.load(e) ?: throw IllegalStateException("unreadable GPX"))
                else Photos.exportZip(act, e)
                Dev.event("share", "${f.name} · ${Photos.count(e)} photos · ${Fmt.mb(f.length())} in ${System.currentTimeMillis() - t0} ms")
                act.runOnUiThread {
                    act.shareExport(f, if (kmz) "application/vnd.google-earth.kmz" else "application/zip",
                        if (kmz) "Share for Google Earth" else "Share trail with photos")
                }
            } catch (ex: Exception) {
                act.runOnUiThread { act.toast("Could not pack it: ${ex.message}") }
            }
        }
    }

    private fun showRoute(e: Entry, color: Int) {
        thread {
            val r = Store.load(e)
            val dir = Photos.dirOf(e)
            val photos = Photos.list(dir).filter { it.hasPosition }
            // the route and photo dots show at once; thumbnails (for close zoom) follow when ready
            act.runOnUiThread {
                if (r == null) { act.toast("Could not read “${e.name}”."); return@runOnUiThread }
                preview = MapView.Overlay(r.lines(), color, 4.5f, endpoints = true)
                previewEntry = if (e.kind == Kind.TRACK) e else null
                map.markers = photos.map { MapView.Marker(it.lat, it.lon, null, it to dir) }
                refreshOverlays()
                r.bbox()?.let { map.fit(it) }
            }
            if (dir != null && photos.isNotEmpty()) {
                val pins = photos.map { MapView.Marker(it.lat, it.lon, Photos.loadThumb(dir, it), it to dir) }
                act.runOnUiThread { if ((map.markers.firstOrNull()?.tag as? Pair<*, *>)?.second == dir) map.markers = pins }
            }
        }
    }

    /** The past trail whose photos are on the map (for removing photos from it). */
    private var previewEntry: Entry? = null

    private fun openPhoto(p: Photo, dir: java.io.File) {
        val e = previewEntry
        act.showPhoto(p, dir, preview?.lines, if (e == null) null else { removed ->
            map.markers = map.markers.filter { (it.tag as? Pair<*, *>)?.first.let { t -> t !is Photo || t.file != removed.file } }
            thread { try { Store.rewriteGpx(e) } catch (ex: Exception) { Dev.event("photo", "GPX rewrite failed: $ex") } }
            if (section == 2) listAdapter?.notifyDataSetChanged()
        })
    }

    private fun clearPreview() {
        previewEntry = null
        preview = null
        map.markers = emptyList()
        refreshOverlays()
    }

    // ------------------------------------------------------------------ draw a plan (with path snapping)
    /** One tap's worth of line: a single point, or a routed path (snapped = followed OSM paths). */
    private class Leg(val lat: DoubleArray, val lon: DoubleArray, val ele: DoubleArray?, val snapped: Boolean)

    private val legs = ArrayList<Leg>()
    private var routing = false

    private fun startDraw() {
        showSection(-1)
        panel.visibility = View.GONE
        drawBar.visibility = View.VISIBLE
        legs.clear()
        drawLine = MLine()
        map.onTap = { lat, lon -> addDrawPoint(lat, lon) }
        updateSnapBtn()
        refreshOverlays()
        updateDrawInfo()
    }

    private fun addDrawPoint(lat: Double, lon: Double) {
        if (routing) { act.toast("Still finding the path…"); return }
        if (legs.isEmpty()) {
            legs += Leg(doubleArrayOf(lat), doubleArrayOf(lon), null, false)
            rebuildDraw()
            return
        }
        val last = legs[legs.size - 1]
        val aLat = last.lat[last.lat.size - 1]
        val aLon = last.lon[last.lon.size - 1]
        val net = wifiNet
        if (!Prefs.snap(act) || net == null) {
            if (Prefs.snap(act)) act.toast("No Wi-Fi: straight line (path snapping needs Wi-Fi).")
            legs += Leg(doubleArrayOf(lat), doubleArrayOf(lon), null, false)
            rebuildDraw()
            return
        }
        routing = true
        updateDrawInfo()
        thread {
            val t0 = System.currentTimeMillis()
            val seg = try {
                Router.route(net, aLat, aLon, lat, lon)
            } catch (e: Exception) {
                Dev.event("route", "no path ${Fmt.dist(Geo.dist(aLat, aLon, lat, lon))} leg: $e")
                null
            }
            act.runOnUiThread {
                routing = false
                if (drawLine == null) return@runOnUiThread // drawing was cancelled meanwhile
                if (seg == null) {
                    act.toast("No path found there — straight line used.")
                    legs += Leg(doubleArrayOf(lat), doubleArrayOf(lon), null, false)
                } else {
                    // the route begins where we already are: skip its first point unless it starts off our last point
                    val skip = if (Geo.dist(aLat, aLon, seg.lat[0], seg.lon[0]) < 5.0) 1 else 0
                    legs += Leg(
                        seg.lat.copyOfRange(skip, seg.size), seg.lon.copyOfRange(skip, seg.size),
                        seg.ele?.copyOfRange(skip, seg.size), true,
                    )
                    // the very first tap moves onto the path too
                    if (legs.size == 2 && !legs[0].snapped) {
                        legs[0] = Leg(doubleArrayOf(seg.lat[0]), doubleArrayOf(seg.lon[0]), seg.ele?.let { doubleArrayOf(it[0]) }, true)
                    }
                    Dev.event("route", "snapped ${seg.size} pts · ${Fmt.dist(Route(null, listOf(seg)).lengthM())} in ${System.currentTimeMillis() - t0} ms")
                }
                rebuildDraw()
            }
        }
    }

    private fun drawRoute(name: String?): Route {
        val la = DoubleList(); val lo = DoubleList(); val el = DoubleList()
        var hasEle = false
        for (l in legs) for (i in l.lat.indices) {
            la.add(l.lat[i]); lo.add(l.lon[i])
            val e = l.ele?.get(i) ?: Double.NaN
            if (!e.isNaN()) hasEle = true
            el.add(e)
        }
        return Route(name, listOf(Seg(la.toArray(), lo.toArray(), if (hasEle) el.toArray() else null)))
    }

    private fun rebuildDraw() {
        val line = MLine()
        for (l in legs) for (i in l.lat.indices) line.add(l.lat[i], l.lon[i])
        drawLine = line
        refreshOverlays()
        updateDrawInfo()
    }

    private fun updateDrawInfo() {
        val r = drawRoute(null)
        val asc = r.ascentM()
        drawInfo.text = when {
            routing -> "Finding the path…"
            legs.isEmpty() -> "Tap the map to add points" + if (Prefs.snap(act)) " — the line follows paths between taps." else "."
            else -> "${legs.size} taps · ${Fmt.dist(r.lengthM())}" + (if (asc >= 1) " · ↑ ${asc.roundToInt()} m" else "") +
                " — keep tapping, pinch to zoom."
        }
    }

    private fun updateSnapBtn() {
        snapBtn.text = if (Prefs.snap(act)) "Snap ✓" else "Snap ✗"
        snapBtn.background = ripple(roundRect(if (Prefs.snap(act)) C.GREEN else C.CARD, act.dpf(10)))
    }

    private fun toggleSnap() {
        act.prefs.edit().putBoolean("snap", !Prefs.snap(act)).apply()
        updateSnapBtn()
        updateDrawInfo()
        act.toast(if (Prefs.snap(act)) "Snapping to paths: new taps follow trails (Wi-Fi)." else "Snapping off: straight lines.")
    }

    private fun undoDraw() {
        if (routing || legs.isEmpty()) return
        legs.removeAt(legs.size - 1)
        rebuildDraw()
    }

    private fun saveDraw() {
        if (routing) { act.toast("Still finding the path…"); return }
        val r = drawRoute(null)
        if (r.points < 2) { act.toast("Add at least two points."); return }
        act.askText("Name this plan", "Plan ${Fmt.date(System.currentTimeMillis())}") { name ->
            val e = Store.save(Kind.PLAN, drawRoute(name), name)
            Dev.event("plan", "drew “${e.name}” · ${legs.size} taps (${legs.count { it.snapped }} snapped) · ${Fmt.dist(e.distM)}")
            act.toast("Saved plan “${e.name}” · ${Fmt.dist(e.distM)}")
            endDraw()
            showSection(1)
        }
    }

    private fun endDraw() {
        map.onTap = null
        drawLine = null
        legs.clear()
        drawBar.visibility = View.GONE
        panel.visibility = View.VISIBLE
        refreshOverlays()
    }

    // ------------------------------------------------------------------ download along a trail
    /** Corridor widths offered (metres each side); 0 = the trail's whole bounding box. */
    private val buffers = listOf(250.0, 500.0, 1000.0, 2000.0, 5000.0, 0.0)

    private fun bufferLabel(m: Double) = if (m <= 0) "Whole box around the trail" else "${Fmt.dist(m)} each side of the trail"

    private fun pickTrailForDownload() {
        val plans = Store.list(Kind.PLAN)
        val past = Store.list(Kind.TRACK)
        if (plans.isEmpty() && past.isEmpty()) {
            act.toast("Add a planned trail first: Plans › Import GPX / KML, or Draw on map.")
            return
        }
        val items = plans.map { e -> "${e.name} · ${Fmt.dist(e.distM)}" to { downloadFor(e) } } +
            past.map { e -> "Past: ${e.name} · ${Fmt.dist(e.distM)}" to { downloadFor(e) } }
        act.choose("Download the map along…", items)
    }

    private fun downloadFor(e: Entry) {
        thread {
            val r = Store.load(e)
            act.runOnUiThread {
                if (gone()) return@runOnUiThread
                if (r == null || r.points < 2) { act.toast("Could not read “${e.name}”."); return@runOnUiThread }
                preview = MapView.Overlay(r.lines(), if (e.kind == Kind.PLAN) C.PLAN else C.PREVIEW, 4.5f, endpoints = true)
                refreshOverlays()
                r.bbox()?.let { map.fit(it) }
                showDownloadDialog(r, "${e.name} map")
            }
        }
    }

    private fun showDownloadDialog(route: Route, defName: String) {
        val trailBox = route.bbox() ?: return
        val segs = Corridor.simplify(route.segs, 10.0)
        val zs = (12..19).toList()
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(4), act.dp(20), 0) }
        val name = EditText(act).apply { setText(defName); setSingleLine(); textSize = 15f }
        box.addView(act.text("Name", 12f, C.DIM)); box.addView(name)
        val topo = CheckBox(act).apply { text = "Topographic (OpenTopoMap)"; isChecked = prefs.getBoolean("dlTopo", true) }
        val sat = CheckBox(act).apply { text = "Satellite (Esri)"; isChecked = prefs.getBoolean("dlSat", false) }
        box.addView(topo); box.addView(sat)
        val land = CheckBox(act).apply { text = "Private land (fields, homes, fences, military)"; isChecked = prefs.getBoolean("dlLand", true) }
        box.addView(land)
        box.addView(act.text("From OpenStreetMap + ESA satellite land cover: shows where you'd likely be on someone's land. Adds a few MB.", 12f, C.DIM).apply { setPadding(act.dp(32), 0, 0, 0) })
        // satellite imagery date: Esri's archive, preferring leaf-off captures where paths show through the forest
        val satLabel = act.text("Satellite imagery date", 12f, C.DIM).apply { setPadding(0, act.dp(6), 0, 0) }
        val satSpin = Spinner(act)
        val satInfo = act.text("", 12f, C.DIM)
        box.addView(satLabel); box.addView(satSpin); box.addView(satInfo)
        class SatChoice(val label: String, val url: String, val ms: Long)
        var satChoices = listOf(SatChoice("Current Esri imagery", "", 0L))
        fun setSatChoices(list: List<SatChoice>, select: Int) {
            satChoices = list
            satSpin.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, list.map { it.label })
            satSpin.setSelection(select.coerceIn(0, list.size - 1))
        }
        setSatChoices(satChoices, 0)
        val midSeg = route.segs.maxByOrNull { it.size }!!
        val midLat = midSeg.lat[midSeg.size / 2]
        val midLon = midSeg.lon[midSeg.size / 2]
        val open = java.util.concurrent.atomic.AtomicBoolean(true)
        var scanStarted = false
        fun startScan() {
            if (scanStarted) return
            val n = wifiNet
            if (n == null) { satInfo.text = "Connect to Wi-Fi to look up leaf-off imagery from Esri's archive."; return }
            scanStarted = true
            satInfo.text = "Looking for leaf-off imagery…"
            thread {
                val t0 = System.currentTimeMillis()
                val caps = try {
                    Wayback.scan(n, midLat, midLon,
                        { i, total -> act.runOnUiThread { if (open.get()) satInfo.text = "Checking Esri's imagery archive… $i / $total" } },
                        { !open.get() })
                } catch (e: Exception) {
                    Dev.event("imagery", "archive lookup failed: $e")
                    null
                }
                if (caps != null) Dev.event(
                    "imagery",
                    "archive lookup ${System.currentTimeMillis() - t0} ms · " + caps.joinToString(" | ") { "${it.rel.date}→${Season.month(it.ms)} ${Season.describe(it.ms, midLat)} ${it.resM}m" },
                )
                act.runOnUiThread {
                    if (!open.get()) return@runOnUiThread
                    if (caps.isNullOrEmpty()) {
                        satInfo.text = "Couldn't read the imagery archive — the current imagery will be used."
                        return@runOnUiThread
                    }
                    val rec = Season.recommend(caps.map { it.ms }, midLat)
                    setSatChoices(caps.mapIndexed { i, c ->
                        SatChoice(
                            (if (i == rec) "★ " else "") + c.label(midLat) + (if (i == 0) " (current)" else ""),
                            if (i == 0) "" else c.rel.tileUrl, c.ms,
                        )
                    }, rec)
                    satInfo.text = if (Season.leafOff(caps[rec].ms, midLat))
                        "★ = newest leaf-off imagery: paths show through bare trees. Dates checked at the trail's middle."
                    else "No leaf-off imagery here — using the newest. Dates checked at the trail's middle."
                }
            }
        }
        fun satVisible() {
            val v = if (sat.isChecked) View.VISIBLE else View.GONE
            satLabel.visibility = v; satSpin.visibility = v; satInfo.visibility = v
            if (sat.isChecked) startScan()
        }
        box.addView(act.text("Area", 12f, C.DIM).apply { setPadding(0, act.dp(6), 0, 0) })
        val bufSpin = Spinner(act).apply {
            adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, buffers.map { bufferLabel(it) })
            setSelection(prefs.getInt("dlBuffer", 2).coerceIn(0, buffers.size - 1))
        }
        box.addView(bufSpin)
        box.addView(act.text("Most detail", 12f, C.DIM).apply { setPadding(0, act.dp(6), 0, 0) })
        val labels = zs.map { z ->
            "Zoom $z · ${String.format(Locale.getDefault(), "%.1f", Geo.metersPerPixel(trailBox.centerLat, z))} m/pixel" +
                if (z > Layer.TOPO.maxZ) " (topo stops at 17)" else ""
        }
        val spin = Spinner(act).apply { adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, labels) }
        box.addView(spin)
        val est = act.text("", 14f).apply { setPadding(0, act.dp(6), 0, act.dp(2)) }
        box.addView(est)
        box.addView(act.text("Trail ${Fmt.dist(route.lengthM())} · elevation data included · downloads only on Wi-Fi", 12f, C.DIM))

        fun layers() = listOfNotNull(Layer.TOPO.takeIf { topo.isChecked }, Layer.SAT.takeIf { sat.isChecked })
        fun buffer() = buffers[bufSpin.selectedItemPosition]
        fun boxFor(b: Double) = trailBox.pad(if (b > 0) b else 1000.0)
        val counts = HashMap<Long, Long>()
        fun count(b: Double, z: Int): Long = counts.getOrPut((b.toLong() shl 8) or z.toLong()) {
            // a huge trail at high zoom can't even be planned in memory: count it as "won't fit"
            if (b > 0) try { Corridor.grid(segs, b, z).count() } catch (e: Corridor.TooLarge) { 1_000_000_000L }
            else Geo.tileCount(boxFor(b), z, z)
        }
        fun estimate(ls: List<Layer>, z: Int, b: Double): Pair<Long, Double> {
            var n = count(b, DEM_Z)
            var kb = n * Layer.DEM.avgKb.toDouble()
            for (l in ls) {
                val top = minOf(z, l.maxZ)
                for (zz in minOf(PACK_MIN_Z, top)..top) {
                    val c = count(b, zz)
                    n += c
                    kb += c * l.avgKb.toDouble()
                }
            }
            return n to kb / 1024.0
        }
        val freeMb = StatFs(act.filesDir.path).availableBytes / 1048576
        fun update() {
            val ls = layers()
            if (ls.isEmpty()) { est.text = "Choose at least one map type."; return }
            val z = zs[spin.selectedItemPosition]
            val b = buffer()
            val (n, mb) = estimate(ls, z, b)
            val tooBig = mb > freeMb - 300
            val boxNote = if (b > 0) " · whole box would be ~${Fmt.mb((estimate(ls, z, 0.0).second * 1048576).toLong())}" else ""
            est.text = "≈ $n tiles · ~${Fmt.mb((mb * 1048576).toLong())}$boxNote\nFree space ${Fmt.mb(freeMb * 1048576)}" +
                when {
                    tooBig -> " — won't fit: choose less detail or a narrower area."
                    mb > 2048 -> " · large download: keep the phone on Wi-Fi and charging (pause/resume anytime)."
                    else -> ""
                }
            est.setTextColor(if (tooBig) 0xFFEF5350.toInt() else if (mb > 2048) 0xFFFFB74D.toInt() else C.TEXT)
        }

        // no size limit: start at the most detail the chosen map types have (or the last choice)
        val maxZ = if (sat.isChecked) Layer.SAT.maxZ else Layer.TOPO.maxZ
        spin.setSelection((prefs.getInt("dlZoom", maxZ) - 12).coerceIn(0, zs.size - 1))
        val onSel = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = update()
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        topo.setOnCheckedChangeListener { _, _ -> update() }
        sat.setOnCheckedChangeListener { _, _ -> update(); satVisible() }
        satVisible()
        spin.onItemSelectedListener = onSel
        bufSpin.onItemSelectedListener = onSel
        update()

        AlertDialog.Builder(act)
            .setTitle("Download map along the trail")
            .setView(ScrollView(act).apply { addView(box) })
            .setPositiveButton("Download") { _, _ ->
                val ls = layers()
                if (ls.isEmpty()) { act.toast("Choose at least one map type."); return@setPositiveButton }
                val z = zs[spin.selectedItemPosition]
                val b = buffer()
                val (n, mb) = estimate(ls, z, b)
                if (mb > freeMb - 300) { act.toast("Not enough free storage on the phone for this."); return@setPositiveButton }
                prefs.edit().putBoolean("dlTopo", topo.isChecked).putBoolean("dlSat", sat.isChecked).putBoolean("dlLand", land.isChecked)
                    .putInt("dlZoom", z).putInt("dlBuffer", bufSpin.selectedItemPosition).apply()
                val sc = if (Layer.SAT in ls) satChoices[satSpin.selectedItemPosition.coerceIn(0, satChoices.size - 1)] else null
                val p = Packs.create(
                    name.text.toString().trim().ifEmpty { defName }, boxFor(b), ls, z, if (b > 0) segs else null, b,
                    sc?.url ?: "", sc?.ms ?: 0L, land.isChecked,
                )
                Dev.event(
                    "download",
                    "created “${p.name}” · $ls${if (land.isChecked) " + land" else ""} · z$z · ${bufferLabel(b)} · $n tiles · est ${mb.roundToInt()} MB · " +
                        "trail ${Fmt.dist(route.lengthM())} · satellite ${sc?.label ?: "-"} · Wi-Fi ${wifiNet != null}",
                )
                Downloads.start(act, p.id)
                if (wifiNet == null) act.toast("Queued — it downloads when Wi-Fi is connected.")
                refreshOverlays()
                showSection(0)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { open.set(false) }
            .show()
    }
}
