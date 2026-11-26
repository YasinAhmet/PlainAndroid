package app.trekmap.trail

import android.app.AlertDialog
import android.content.Intent
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * Execution tab. OFF: choose a planned trail and look around the offline map.
 * ON (the power button): only the map, your position, your track and the plan — nothing else runs.
 * Fully offline: this screen never touches the network.
 */
class TrailScreen(private val act: MainActivity) {
    val view = FrameLayout(act)
    private val prefs = act.prefs
    private val map = MapView(act)
    private val provider = TileProvider(1, 24 * 1024 * 1024) { l, z, x, y -> Packs.read(l, z, x, y) ?: TileProvider.EMPTY }
    private val main = Handler(Looper.getMainLooper())

    private val offBar = LinearLayout(act)
    private val spinner = Spinner(act)
    private val noMaps = act.text("", 13f, C.DIM)
    private val statsBox = LinearLayout(act)
    private val tDist = act.text("0 m", 28f, bold = true)
    private val tTime = act.text("0:00", 28f, bold = true)
    private val tSub = act.text("", 13f, C.DIM)
    private val power = TextView(act)
    private val cam = TextView(act)
    private val layerBtn: Button
    private val landBtn: Button
    private var plans: List<Entry> = emptyList()
    private var planOverlay: MapView.Overlay? = null
    private val trackOverlay = MapView.Overlay(Trail.segs, C.TRACK, 4.5f)
    private var on = false
    private var visible = true

    // compass mode / statistics (Settings)
    private val compass = Compass(act, { displayRotation() }) { h -> onHeading(h) }
    private var compassOn = false
    private var statsOn = false
    private var lastStatsDeg = -1

    private val tick = object : Runnable {
        override fun run() {
            updateStats()
            if (on && visible) main.postDelayed(this, 1000)
        }
    }

    init {
        map.provider = provider
        map.bg = 0xFF141414.toInt()
        map.maxTileZ = { Packs.maxZ(map.layer).coerceAtLeast(0) }
        map.layer = pickLayer()
        map.attribution = map.layer.attribution
        view.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        // --- top: plan chooser (OFF) or stats (ON)
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(10), act.dp(10), act.dp(10), 0) }
        offBar.apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C.PANEL, act.dpf(12))
            setPadding(act.dp(12), act.dp(8), act.dp(8), act.dp(8))
            addView(act.text("Planned trail", 12f, C.DIM))
            addView(spinner, lpL(MATCH, act.dp(40)))
            addView(noMaps)
        }
        top.addView(offBar, lpL(MATCH, WRAP))
        statsBox.apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C.PANEL, act.dpf(12))
            setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(10))
            val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(tDist, lpL(0, WRAP, 1f))
            row.addView(tTime, lpL(WRAP, WRAP))
            addView(row)
            addView(tSub)
        }
        top.addView(statsBox, lpL(MATCH, WRAP))
        view.addView(top, lpF(MATCH, WRAP, Gravity.TOP))
        top.addOnLayoutChangeListener { _, _, t, _, b, _, _, _, _ -> map.padTop = b - t }

        // --- right: zoom, layer, locate
        val side = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, act.dp(10), 0) }
        fun sideBtn(label: String, onClick: () -> Unit) = act.button(label, C.PANEL) { onClick() }.apply { textSize = 20f }
        side.addView(sideBtn("⚙") { act.showSettings { applySettings() } }.apply { textSize = 18f },
            lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        side.addView(sideBtn("+") { map.zoomBy(1.0) }, lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        side.addView(sideBtn("−") { map.zoomBy(-1.0) }, lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        layerBtn = sideBtn(layerLabel()) { toggleLayer() }.apply {
            textSize = 11f
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            // only offer switching when both map types are downloaded
            visibility = if (Packs.has(Layer.TOPO) && Packs.has(Layer.SAT)) View.VISIBLE else View.GONE
        }
        side.addView(layerBtn, lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        // private-land overlay: tap = on/off, hold = what the colours mean
        landBtn = sideBtn("Land") { toggleLand() }.apply {
            textSize = 11f
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            setOnLongClickListener { act.showLandLegend(); true }
        }
        side.addView(landBtn, lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        side.addView(sideBtn("◎") { locate() }, lpL(act.dp(46), act.dp(46)))
        view.addView(side, lpF(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))

        // --- bottom: the power button
        power.apply {
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = act.dpf(6)
            setOnClickListener { if (!on) startTrail() else act.toast("Hold the button to stop.") }
            setOnLongClickListener { if (on) askStop(); on }
        }
        view.addView(power, lpF(act.dp(84), act.dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dp(14) })
        map.padBottom = act.dp(104)

        // --- camera (only while recording): photo goes to the trail + Gallery
        cam.apply {
            text = "📷"
            textSize = 24f
            gravity = Gravity.CENTER
            background = ripple(oval(C.PANEL))
            elevation = act.dpf(4)
            setOnClickListener { act.takePhoto() }
            visibility = View.GONE
        }
        view.addView(cam, lpF(act.dp(60), act.dp(60), Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = act.dp(26); marginStart = act.dp(28)
        })
        map.onMarkerTap = { m ->
            (m.tag as? Photo)?.let { act.showPhoto(it, Photos.recDir(act), Trail.segs) { refreshPhotos() } }
        }

        map.compassButton = true
        map.onCompassTap = { toggleCompass() }
        loadPlans()
        setOn(Trail.active)
        if (!Trail.active) initialView()
        else if (Trail.lastLat.isNaN()) {
            // recording restored before the first fix: show where the track ended, keep following
            val p = Trail.lastPoint()
            if (p != null) map.centerOn(p[0], p[1]) else initialView()
            map.follow = true
        }
        applySettings()
    }

    /** Photos of the current recording as pins (thumbnails load off the main thread). */
    fun refreshPhotos() {
        if (!on) return
        val dir = Photos.recDir(act)
        thread {
            val ms = Photos.list(dir).filter { it.hasPosition }.map { MapView.Marker(it.lat, it.lon, Photos.loadThumb(dir, it), it) }
            act.runOnUiThread { if (on) map.markers = ms }
        }
    }

    // ------------------------------------------------------------------ compass & statistics
    /** Applies the Settings toggles. The compass sensor runs only while this map is visible and needed. */
    fun applySettings() {
        compassOn = Prefs.compass(act)
        statsOn = Prefs.stats(act)
        map.compassActive = compassOn
        map.dim = if (Prefs.dim(act)) 0.45f else 0f
        val hasLand = Packs.has(Layer.LAND)
        landBtn.visibility = if (hasLand) View.VISIBLE else View.GONE
        map.overlayLayer = if (hasLand && Prefs.land(act)) Layer.LAND else null
        landBtn.setTextColor(if (map.overlayLayer != null) 0xFFFFB300.toInt() else C.DIM)
        map.attribution = map.layer.attribution + if (map.overlayLayer != null) " · " + Layer.LAND.attribution else ""
        Trail.settingsChanged()
        map.stats = if (statsOn) ({ trailStats() }) else null
        if (!compassOn) map.setBearing(0f)
        updateSensor()
    }

    private fun toggleCompass() {
        act.prefs.edit().putBoolean("compass", !Prefs.compass(act)).apply()
        applySettings()
        Dev.event("compass", "needle tapped → ${if (compassOn) "heading-up" else "north-up"}")
        act.toast(if (compassOn) "Heading up: the map turns with you." else "North up.")
    }

    private fun updateSensor() {
        val want = visible && (compassOn || statsOn)
        if (want && !compass.running) {
            val s = compass.sensor
            if (s == null) {
                if (compassOn) act.toast("This phone has no compass sensor.")
                return
            }
            setDeclination()
            if (compass.start()) Dev.event("compass", "on · ${s.name} (type ${s.type}, ${s.power} mA) · compass=$compassOn stats=$statsOn")
        } else if (!want && compass.running) {
            compass.stop()
            Dev.event("compass", "off")
        }
    }

    private fun setDeclination() {
        var lat = Trail.lastLat
        var lon = Trail.lastLon
        if (lat.isNaN()) try {
            act.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { lat = it.latitude; lon = it.longitude }
        } catch (_: SecurityException) {}
        if (!lat.isNaN()) compass.setLocation(lat, lon)
    }

    private fun displayRotation(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 30) act.display?.rotation ?: 0
        else @Suppress("DEPRECATION") act.windowManager.defaultDisplay.rotation

    private fun onHeading(h: Double) {
        if (compassOn) map.setBearing(h.toFloat())
        else if (statsOn) {
            val d = h.roundToInt()
            if (d != lastStatsDeg) { lastStatsDeg = d; map.invalidate() }
        }
    }

    private fun trailStats(): List<String> {
        val out = ArrayList<String>(3)
        if (Trail.active) {
            val parts = ArrayList<String>()
            parts += if (Trail.lastLat.isNaN()) "GPS searching…" else "GPS ±${Trail.lastAcc.roundToInt()} m"
            if (Trail.lastSats >= 0) parts += "${Trail.lastSats} sats"
            if (!Trail.lastCn0.isNaN()) parts += "${Trail.lastCn0.roundToInt()} dB-Hz"
            if (!Trail.lastSpeed.isNaN()) parts += "${"%.1f".format(Locale.US, Trail.lastSpeed * 3.6)} km/h"
            out += parts.joinToString(" · ")
        } else {
            out += "GPS off — press START to record"
        }
        val h = compass.heading
        if (compass.running && !h.isNaN()) {
            var line = "Facing ${h.roundToInt()}° ${Heading.cardinal(h)}"
            if (!Trail.lastCourse.isNaN()) line += " · moving ${Trail.lastCourse.roundToInt()}° ${Heading.cardinal(Trail.lastCourse.toDouble())}"
            if (compass.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) line += " · compass unsure: move the phone in a figure-8"
            out += line
        }
        val lat = Geo.lat(map.cy)
        val lon = Geo.lon(map.cx)
        val mpp = Geo.metersPerUnit(lat) / map.world()
        val detail = when {
            map.drawnZ < 0 -> "no offline map here"
            map.drawnZ < map.tileZ -> "z${map.drawnZ} scaled ×${1 shl (map.tileZ - map.drawnZ)} (less detail)"
            else -> "full detail"
        }
        val img = if (map.layer == Layer.SAT) Packs.all().firstOrNull {
            Layer.SAT in it.layers && it.satMs > 0 && lat in it.bbox.s..it.bbox.n && lon in it.bbox.w..it.bbox.e
        }?.satMs else null
        out += "Map z${map.tileZ} · $detail · ${"%.1f".format(Locale.US, mpp)} m/px" + (img?.let { " · imagery ${Season.month(it)}" } ?: "")
        return out
    }

    // ------------------------------------------------------------------ plan & layers
    private fun pickLayer(): Layer {
        val saved = prefs.getString("trailLayer", null)
        val avail = listOf(Layer.TOPO, Layer.SAT).filter { Packs.has(it) }
        return avail.firstOrNull { it.name == saved } ?: avail.firstOrNull() ?: Layer.TOPO
    }

    private fun layerLabel() = if (map.layer == Layer.TOPO) "Sat" else "Topo"

    private fun toggleLand() {
        val on = !Prefs.land(act)
        prefs.edit().putBoolean("land", on).apply()
        Dev.event("settings", "land=$on (trail button)")
        applySettings()
        if (on && !prefs.getBoolean("landLegendSeen", false)) {
            prefs.edit().putBoolean("landLegendSeen", true).apply()
            act.showLandLegend()
        } else act.toast(if (on) "Private land shown · hold Land for the colours" else "Private land hidden")
    }

    private fun toggleLayer() {
        val other = if (map.layer == Layer.TOPO) Layer.SAT else Layer.TOPO
        if (!Packs.has(other)) { act.toast("No offline ${other.label.lowercase()} map downloaded."); return }
        map.layer = other
        prefs.edit().putString("trailLayer", other.name).apply()
        layerBtn.text = layerLabel()
        applySettings()
    }

    private fun loadPlans() {
        plans = Store.list(Kind.PLAN)
        val names = listOf("— No planned trail —") + plans.map { "${it.name}  ·  ${Fmt.dist(it.distM)}" }
        spinner.adapter = ArrayAdapter(act, android.R.layout.simple_spinner_dropdown_item, names)
        val wanted = if (Trail.active) Trail.planId else prefs.getString("trailPlan", null)
        val idx = plans.indexOfFirst { it.id == wanted }
        var current = if (idx >= 0) idx + 1 else 0
        spinner.setSelection(current, false)
        selectPlan(if (idx >= 0) plans[idx] else null, fit = false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos == current) return // Spinner re-reports the initial selection on first layout
                current = pos
                val e = if (pos == 0) null else plans[pos - 1]
                prefs.edit().putString("trailPlan", e?.id).apply()
                selectPlan(e, fit = true)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        val packs = Packs.all()
        noMaps.text = if (packs.isEmpty())
            "No offline maps yet — download one in the Plan tab on Wi-Fi. You can still record without a map."
        else "${packs.size} offline map${if (packs.size > 1) "s" else ""} · pinch, drag and double-tap to look around"
    }

    private var planBox: BBox? = null

    private fun selectPlan(e: Entry?, fit: Boolean) {
        planBox = e?.bbox
        val r = e?.let { Store.load(it) }
        planOverlay = r?.let { MapView.Overlay(it.lines(), C.PLAN, 5f, endpoints = true) }
        refreshOverlays()
        if (fit) planBox?.let { map.fit(it) }
    }

    private fun refreshOverlays() {
        map.overlays = listOfNotNull(planOverlay, if (on) trackOverlay else null)
        map.invalidate()
    }

    private fun initialView() {
        val b = planBox ?: Packs.all().firstOrNull()?.bbox
        if (b != null) { map.fit(b); return }
        try {
            val l = act.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (l != null) { map.centerOn(l.latitude, l.longitude, 14.0); return }
        } catch (_: SecurityException) {}
        map.centerOn(39.0, 35.0, 5.0)
    }

    // ------------------------------------------------------------------ trail mode
    private fun startTrail() {
        act.withLocationPermission {
            val lm = act.getSystemService(LocationManager::class.java)
            if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                act.confirm("Location is off", "Turn on Location (GPS) to record your trail.", "Open settings") {
                    act.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                return@withLocationPermission
            }
            val lim = Power.saverLimitsGps(act)
            if (lim != null) {
                Dev.event("battery-saver", "on at START: $lim")
                android.app.AlertDialog.Builder(act)
                    .setTitle("Battery saver will stop your track")
                    .setMessage("Android's battery saver is on and $lim — your recording would stop in your pocket.\n\nTurn battery saver off before recording.")
                    .setPositiveButton("Battery settings") { _, _ ->
                        try { act.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNegativeButton("Start anyway") { _, _ -> beginTrail() }
                    .show()
                return@withLocationPermission
            }
            beginTrail()
        }
    }

    private fun beginTrail() {
        val sel = spinner.selectedItemPosition
        val plan = if (sel > 0) plans.getOrNull(sel - 1) else null
        Downloads.pauseForTrail(act) // nothing from the planning side runs during a trail
        Dem.clear()
        try {
            Trail.start(act, plan?.id, plan?.name)
        } catch (e: Exception) {
            act.toast(e.message ?: "Could not start recording.")
            Downloads.resumeAfterTrail(act)
            return
        }
        Trail.setUiVisible(true)
        setOn(true)
        act.batteryTips()
    }

    private fun setOn(v: Boolean) {
        on = v
        act.setTrailMode(v)
        offBar.visibility = if (v) View.GONE else View.VISIBLE
        statsBox.visibility = if (v) View.VISIBLE else View.GONE
        power.text = if (v) "STOP\n(hold)" else "START"
        cam.visibility = if (v) View.VISIBLE else View.GONE
        if (v) refreshPhotos() else map.markers = emptyList()
        power.background = ripple(oval(if (v) C.RED else C.GREEN))
        refreshOverlays()
        main.removeCallbacks(tick)
        if (v) {
            Trail.listener = { onTrail() }
            map.follow = true
            if (map.zoom < 15) map.zoom = 16.0
            onTrail()
            if (visible) main.post(tick)
        } else {
            Trail.listener = null
            map.follow = false
            map.clearMe()
        }
    }

    private fun onTrail() {
        if (!on) return
        if (!Trail.active) { setOn(false); return }
        if (!Trail.lastLat.isNaN()) map.setMe(Trail.lastLat, Trail.lastLon, Trail.lastAcc)
        // once per recording, the moment GPS locks: the right time to switch on airplane mode
        if (Trail.gps == Trail.Gps.OK && prefs.getLong("airTipFor", 0L) != Trail.startTime) {
            prefs.edit().putLong("airTipFor", Trail.startTime).apply()
            if (!Power.airplaneOn(act)) act.toast("GPS locked ✓ Switch on airplane mode now to save battery — GPS keeps working offline.")
        }
        updateStats()
        map.invalidate()
    }

    private fun locate() {
        if (!on) { act.toast("Your position shows after you press START."); return }
        if (Trail.lastLat.isNaN()) { act.toast("Waiting for GPS…"); return }
        map.follow = true
        map.setMe(Trail.lastLat, Trail.lastLon, Trail.lastAcc)
    }

    private fun updateStats() {
        if (!on) return
        tDist.text = Fmt.dist(Trail.distM)
        tTime.text = Fmt.dur((System.currentTimeMillis() - Trail.startTime) / 1000)
        val acc = Trail.lastAcc.roundToInt()
        val g = when (Trail.gps) {
            Trail.Gps.OK -> "GPS ±$acc m"
            Trail.Gps.SEARCHING -> if (Trail.lastLat.isNaN()) "Searching for GPS…" else "Weak GPS ±$acc m"
            Trail.Gps.RESTING -> "Resting · GPS paused"
            Trail.Gps.DISABLED -> "Location is turned off!"
            Trail.Gps.NO_PERMISSION -> "No location permission"
            Trail.Gps.OFF -> "Starting…"
        }
        val ele = if (Trail.eleNow.isNaN()) "" else " · ${Trail.eleNow.roundToInt()} m"
        tSub.text = "↑ ${Trail.ascentM.roundToInt()} m$ele · $g"
    }

    private fun askStop() {
        val def = (Trail.planName ?: "Trail") + " · " + Fmt.date(Trail.startTime)
        val et = EditText(act).apply { setText(def); setSingleLine() }
        val box = FrameLayout(act).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0); addView(et) }
        AlertDialog.Builder(act)
            .setTitle("Stop and save this trail?")
            .setMessage("${Fmt.dist(Trail.distM)} · ${Fmt.dur((System.currentTimeMillis() - Trail.startTime) / 1000)} · ↑ ${Trail.ascentM.roundToInt()} m")
            .setView(box)
            .setPositiveButton("Save") { _, _ -> finish(et.text.toString().trim().ifEmpty { def }, true) }
            .setNeutralButton("Discard") { _, _ ->
                act.confirm("Discard this trail?", "The recorded track will be deleted.", "Discard") { finish(null, false) }
            }
            .setNegativeButton("Keep recording", null)
            .show()
    }

    private fun finish(name: String?, save: Boolean) {
        val e = try {
            Trail.stop(act, name, save)
        } catch (ex: Exception) {
            act.toast("Could not save: ${ex.message}")
            return
        }
        setOn(false)
        Downloads.resumeAfterTrail(act)
        act.toast(
            when {
                e != null -> "Saved “${e.name}” · find it in Plan › Past trails"
                save -> "Nothing was recorded."
                else -> "Trail discarded."
            }
        )
    }

    // ------------------------------------------------------------------ lifecycle
    fun onVisible(v: Boolean) {
        visible = v
        updateSensor()
        if (!v) Dev.event("render", "trail map (${if (on) "recording" else "idle"}): ${map.takeStats()} · ${provider.stats()}")
        main.removeCallbacks(tick)
        if (v && on) {
            onTrail()
            main.post(tick)
        }
    }

    fun destroy() {
        compass.stop()
        Dev.event("render", "trail map closed: ${map.takeStats()} · ${provider.stats()}")
        main.removeCallbacks(tick)
        if (Trail.listener != null) Trail.listener = null
        map.provider = null
        provider.shutdown()
    }
}
