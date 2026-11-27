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
import app.plain.lang.PlainLang
import app.plain.lang.tr
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
    private val switcher = app.plain.hub.Hub.switcher(act, "trekking")
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

        // --- top: stats (ON); the plan chooser (OFF) sits at the bottom, above the START button, within thumb reach
        val top = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(10), act.dp(10), act.dp(10), 0) }
        offBar.apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(C.PANEL, act.dpf(12))
            setPadding(act.dp(12), act.dp(8), act.dp(8), act.dp(8))
            addView(act.text(tr("Planned trail", "Planlanan rota"), 12f, C.DIM))
            addView(spinner, lpL(MATCH, act.dp(40)))
            addView(noMaps)
        }
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
        landBtn = sideBtn(tr("Land", "Arazi")) { toggleLand() }.apply {
            textSize = 11f
            isSingleLine = true
            setPadding(0, 0, 0, 0)
            setOnLongClickListener { act.showLandLegend(); true }
        }
        side.addView(landBtn, lpL(act.dp(46), act.dp(46)).apply { bottomMargin = act.dp(8) })
        side.addView(sideBtn("◎") { locate() }, lpL(act.dp(46), act.dp(46)))
        view.addView(side, lpF(WRAP, WRAP, Gravity.END or Gravity.CENTER_VERTICAL))

        // --- bottom: the plan chooser (OFF), above the power button
        view.addView(offBar, lpF(MATCH, WRAP, Gravity.BOTTOM).apply {
            leftMargin = act.dp(10); rightMargin = act.dp(10); bottomMargin = act.dp(14 + 84 + 10)
        })
        offBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updatePadBottom() }

        // --- bottom: the power button
        power.apply {
            gravity = Gravity.CENTER
            setTextColor(C.TEXT)
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            elevation = act.dpf(6)
            // recording: tap = pause / resume, hold = stop
            setOnClickListener { if (!on) startTrail() else togglePause() }
            setOnLongClickListener { if (on) askStop(); on }
        }
        view.addView(power, lpF(act.dp(84), act.dp(84), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = act.dp(14) })
        updatePadBottom()

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
        // --- while recording the Plan / Trail bar is hidden: the Plain Maps switcher sits right of the power button
        // (mirroring the camera on the left), never over it
        switcher.visibility = View.GONE
        view.addView(switcher, lpF(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = act.dp(36); marginEnd = act.dp(14)
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
        act.toast(if (compassOn) tr("Heading up: the map turns with you.", "Yön yukarı: harita sizinle döner.") else tr("North up.", "Kuzey yukarı."))
    }

    private fun updateSensor() {
        val want = visible && (compassOn || statsOn)
        if (want && !compass.running) {
            val s = compass.sensor
            if (s == null) {
                if (compassOn) act.toast(tr("This phone has no compass sensor.", "Bu telefonda pusula sensörü yok."))
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
            parts += if (Trail.lastLat.isNaN()) tr("GPS searching…", "GPS aranıyor…") else "GPS ±${Trail.lastAcc.roundToInt()} m"
            if (Trail.lastSats >= 0) parts += tr("${Trail.lastSats} sats", "${Trail.lastSats} uydu")
            if (!Trail.lastCn0.isNaN()) parts += "${Trail.lastCn0.roundToInt()} dB-Hz"
            if (!Trail.lastSpeed.isNaN()) parts += "${"%.1f".format(Locale.US, Trail.lastSpeed * 3.6)} km/h"
            out += parts.joinToString(" · ")
        } else {
            out += tr("GPS off — press START to record", "GPS kapalı — kayıt için BAŞLA'ya basın")
        }
        val h = compass.heading
        if (compass.running && !h.isNaN()) {
            var line = tr("Facing", "Yön") + " ${h.roundToInt()}° ${Heading.cardinal(h)}"
            if (!Trail.lastCourse.isNaN()) line += " · " + tr("moving", "gidiş") + " ${Trail.lastCourse.roundToInt()}° ${Heading.cardinal(Trail.lastCourse.toDouble())}"
            if (compass.accuracy <= SensorManager.SENSOR_STATUS_ACCURACY_LOW) line += tr(" · compass unsure: move the phone in a figure-8", " · pusula emin değil: telefonu 8 çizerek hareket ettirin")
            out += line
        }
        val lat = Geo.lat(map.cy)
        val lon = Geo.lon(map.cx)
        val mpp = Geo.metersPerUnit(lat) / map.world()
        val detail = when {
            map.drawnZ < 0 -> tr("no offline map here", "burada çevrimdışı harita yok")
            map.drawnZ < map.tileZ -> tr("z${map.drawnZ} scaled ×${1 shl (map.tileZ - map.drawnZ)} (less detail)", "z${map.drawnZ} ×${1 shl (map.tileZ - map.drawnZ)} büyütülmüş (az ayrıntı)")
            else -> tr("full detail", "tam ayrıntı")
        }
        val img = if (map.layer == Layer.SAT) Packs.all().firstOrNull {
            Layer.SAT in it.layers && it.satMs > 0 && lat in it.bbox.s..it.bbox.n && lon in it.bbox.w..it.bbox.e
        }?.satMs else null
        out += tr("Map", "Harita") + " z${map.tileZ} · $detail · ${"%.1f".format(Locale.US, mpp)} m/px" + (img?.let { " · " + tr("imagery", "görüntü") + " ${Season.month(it)}" } ?: "")
        return out
    }

    // ------------------------------------------------------------------ plan & layers
    private fun pickLayer(): Layer {
        val saved = prefs.getString("trailLayer", null)
        val avail = listOf(Layer.TOPO, Layer.SAT).filter { Packs.has(it) }
        return avail.firstOrNull { it.name == saved } ?: avail.firstOrNull() ?: Layer.TOPO
    }

    private fun layerLabel() = if (map.layer == Layer.TOPO) tr("Sat", "Uydu") else "Topo"

    private fun toggleLand() {
        val on = !Prefs.land(act)
        prefs.edit().putBoolean("land", on).apply()
        Dev.event("settings", "land=$on (trail button)")
        applySettings()
        if (on && !prefs.getBoolean("landLegendSeen", false)) {
            prefs.edit().putBoolean("landLegendSeen", true).apply()
            act.showLandLegend()
        } else act.toast(if (on) tr("Private land shown · hold Land for the colours", "Özel arazi gösteriliyor · renkler için Arazi'ye basılı tutun") else tr("Private land hidden", "Özel arazi gizlendi"))
    }

    private fun toggleLayer() {
        val other = if (map.layer == Layer.TOPO) Layer.SAT else Layer.TOPO
        if (!Packs.has(other)) { act.toast(tr("No offline ${other.label.lowercase()} map downloaded.", "İndirilmiş çevrimdışı ${PlainLang.lower(other.label)} haritası yok.")); return }
        map.layer = other
        prefs.edit().putString("trailLayer", other.name).apply()
        layerBtn.text = layerLabel()
        applySettings()
    }

    private fun loadPlans() {
        plans = Store.list(Kind.PLAN)
        val names = listOf(tr("— No planned trail —", "— Planlanan rota yok —")) + plans.map { "${it.name}  ·  ${Fmt.dist(it.distM)}" }
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
            tr("No offline maps yet — download one in the Plan tab on Wi-Fi. You can still record without a map.",
                "Henüz çevrimdışı harita yok — Plan sekmesinden Wi-Fi'da indirin. Harita olmadan da kayıt yapabilirsiniz.")
        else tr("${packs.size} offline map${if (packs.size > 1) "s" else ""} · pinch, drag and double-tap to look around",
            "${packs.size} çevrimdışı harita · gezinmek için sıkıştırın, sürükleyin, çift dokunun")
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
                act.confirm(tr("Location is off", "Konum kapalı"), tr("Turn on Location (GPS) to record your trail.", "Rotanızı kaydetmek için Konum'u (GPS) açın."), tr("Open settings", "Ayarları aç")) {
                    act.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                return@withLocationPermission
            }
            val lim = Power.saverLimitsGps(act)
            if (lim != null) {
                Dev.event("battery-saver", "on at START: $lim")
                android.app.AlertDialog.Builder(act)
                    .setTitle(tr("Battery saver will stop your track", "Pil tasarrufu kaydınızı durdurur"))
                    .setMessage(tr("Android's battery saver is on and $lim — your recording would stop in your pocket.\n\nTurn battery saver off before recording.",
                        "Android pil tasarrufu açık: $lim — kaydınız cepteyken durur.\n\nKayda başlamadan pil tasarrufunu kapatın."))
                    .setPositiveButton(tr("Battery settings", "Pil ayarları")) { _, _ ->
                        try { act.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)) } catch (_: Exception) {}
                    }
                    .setNegativeButton(tr("Start anyway", "Yine de başla")) { _, _ -> beginTrail() }
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
            act.toast(e.message ?: tr("Could not start recording.", "Kayıt başlatılamadı."))
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
        syncPower()
        cam.visibility = if (v) View.VISIBLE else View.GONE
        switcher.visibility = if (v) View.VISIBLE else View.GONE
        updatePadBottom()
        if (v) refreshPhotos() else map.markers = emptyList()
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

    /** Keeps the map's centre above the bottom controls: the power button, and the plan chooser when OFF. */
    private fun updatePadBottom() {
        val base = act.dp(104)
        val pad = if (!on && offBar.visibility == View.VISIBLE && offBar.height > 0) act.dp(14 + 84 + 10) + offBar.height else base
        if (map.padBottom != pad) { map.padBottom = pad; map.invalidate() }
    }

    /** The power button: START (green), STOP (red, hold) or PAUSED (amber, tap to resume). */
    private fun syncPower() {
        val paused = on && Trail.paused
        power.text = when {
            !on -> tr("START", "BAŞLA")
            paused -> tr("PAUSED\n(tap)", "DURAKLADI\n(dokun)")
            else -> tr("STOP\n(hold)", "DUR\n(uzun bas)")
        }
        power.textSize = if (PlainLang.turkish || paused) 13f else 16f
        power.background = ripple(oval(when { !on -> C.GREEN; paused -> 0xFFE0A030.toInt(); else -> C.RED }))
    }

    private fun togglePause() {
        val p = !Trail.paused
        Trail.setPaused(act, p)
        power.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        act.toast(if (p) tr("Paused: GPS off, this time doesn't count. Tap again to go on.", "Duraklatıldı: GPS kapalı, bu süre sayılmaz. Sürdürmek için yine dokunun.")
            else tr("Recording again", "Kayıt sürüyor"))
        syncPower()
        updateStats()
    }

    private fun onTrail() {
        if (!on) return
        if (!Trail.active) { setOn(false); return }
        syncPower()
        if (!Trail.lastLat.isNaN()) map.setMe(Trail.lastLat, Trail.lastLon, Trail.lastAcc)
        // once per recording, the moment GPS locks: the right time to switch on airplane mode
        if (Trail.gps == Trail.Gps.OK && prefs.getLong("airTipFor", 0L) != Trail.startTime) {
            prefs.edit().putLong("airTipFor", Trail.startTime).apply()
            if (!Power.airplaneOn(act)) act.toast(tr("GPS locked ✓ Switch on airplane mode now to save battery — GPS keeps working offline.",
                "GPS kilitlendi ✓ Pil için şimdi uçak modunu açın — GPS çevrimdışı çalışmaya devam eder."))
        }
        updateStats()
        map.invalidate()
    }

    private fun locate() {
        if (!on) { act.toast(tr("Your position shows after you press START.", "Konumunuz BAŞLA'ya bastıktan sonra görünür.")); return }
        if (Trail.lastLat.isNaN()) { act.toast(tr("Waiting for GPS…", "GPS bekleniyor…")); return }
        map.follow = true
        map.setMe(Trail.lastLat, Trail.lastLon, Trail.lastAcc)
    }

    private fun updateStats() {
        if (!on) return
        tDist.text = Fmt.dist(Trail.distM)
        tTime.text = Fmt.dur(Trail.movingMs / 1000)
        val acc = Trail.lastAcc.roundToInt()
        val g = if (Trail.paused) tr("Paused · GPS off", "Duraklatıldı · GPS kapalı") else when (Trail.gps) {
            Trail.Gps.OK -> "GPS ±$acc m"
            Trail.Gps.SEARCHING -> if (Trail.lastLat.isNaN()) tr("Searching for GPS…", "GPS aranıyor…") else tr("Weak GPS ±$acc m", "Zayıf GPS ±$acc m")
            Trail.Gps.RESTING -> tr("Resting · GPS paused", "Mola · GPS duraklatıldı")
            Trail.Gps.DISABLED -> tr("Location is turned off!", "Konum kapalı!")
            Trail.Gps.NO_PERMISSION -> tr("No location permission", "Konum izni yok")
            Trail.Gps.OFF -> tr("Starting…", "Başlatılıyor…")
        }
        val ele = if (Trail.eleNow.isNaN()) "" else " · ${Trail.eleNow.roundToInt()} m"
        tSub.text = "↑ ${Trail.ascentM.roundToInt()} m$ele · $g"
    }

    private fun askStop() {
        val def = (Trail.planName ?: tr("Trail", "Rota")) + " · " + Fmt.date(Trail.startTime)
        val et = EditText(act).apply { setText(def); setSingleLine() }
        val box = FrameLayout(act).apply { setPadding(act.dp(20), act.dp(8), act.dp(20), 0); addView(et) }
        AlertDialog.Builder(act)
            .setTitle(tr("Stop and save this trail?", "Rota durdurulup kaydedilsin mi?"))
            .setMessage("${Fmt.dist(Trail.distM)} · ${Fmt.dur(Trail.movingMs / 1000)} · ↑ ${Trail.ascentM.roundToInt()} m")
            .setView(box)
            .setPositiveButton(tr("Save", "Kaydet")) { _, _ -> finish(et.text.toString().trim().ifEmpty { def }, true) }
            .setNeutralButton(tr("Discard", "At")) { _, _ ->
                act.confirm(tr("Discard this trail?", "Bu rota atılsın mı?"), tr("The recorded track will be deleted.", "Kaydedilen iz silinecek."), tr("Discard", "At")) { finish(null, false) }
            }
            .setNegativeButton(tr("Keep recording", "Kayda devam"), null)
            .show()
    }

    private fun finish(name: String?, save: Boolean) {
        val e = try {
            Trail.stop(act, name, save)
        } catch (ex: Exception) {
            act.toast(tr("Could not save: ${ex.message}", "Kaydedilemedi: ${ex.message}"))
            return
        }
        setOn(false)
        Downloads.resumeAfterTrail(act)
        act.toast(
            when {
                e != null -> tr("Saved “${e.name}” · find it in Plan › Past trails", "Kaydedildi: “${e.name}” · Plan › Geçmiş rotalar")
                save -> tr("Nothing was recorded.", "Hiçbir şey kaydedilmedi.")
                else -> tr("Trail discarded.", "Rota atıldı.")
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
