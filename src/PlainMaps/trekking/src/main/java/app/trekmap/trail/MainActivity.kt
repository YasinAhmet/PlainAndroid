package app.trekmap.trail

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import kotlin.concurrent.thread

/**
 * Two tabs: Plan and Trail. Only one screen exists at a time — switching tabs destroys the other
 * one (its map cache, threads and network watchers), and while Trail mode is ON the tab bar is
 * hidden and nothing from the planning side exists or runs.
 */
class MainActivity : Activity() {
    companion object {
        const val RC_IMPORT = 10
        const val RC_EXPORT = 11
        const val RC_PHOTO = 12
        const val PLAIN_CAMERA = "app.plain.camera"
        private const val RC_PERMS = 7
    }

    lateinit var prefs: SharedPreferences
        private set
    private lateinit var content: FrameLayout
    private lateinit var tabs: LinearLayout
    private lateinit var tabPlan: TextView
    private lateinit var tabTrail: TextView
    private var plan: PlanScreen? = null
    private var trail: TrailScreen? = null
    private var permCb: (() -> Unit)? = null
    var exportEntry: Entry? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        if (Hub.gate(this, "trekking")) { finish(); return }  // old Plain Trekking's data first
        TrailInit.init(application)
        prefs = getSharedPreferences(TrailInit.PREFS, MODE_PRIVATE)
        edgeToEdge()
        Power.prefer60Hz(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        content = FrameLayout(this)
        root.addView(content, lpL(MATCH, 0, 1f))
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xFF0E0E0E.toInt()) }
        tabPlan = tab(tr("Plan", "Plan")) { showPlan() }
        tabTrail = tab(tr("Trail", "Rota")) { showTrail() }
        tabs.addView(tabPlan, lpL(0, dp(44), 1f))
        tabs.addView(tabTrail, lpL(0, dp(44), 1f))
        root.addView(tabs, lpL(MATCH, WRAP))
        setContentView(Hub.wrap(this, "trekking", root))
        root.setOnApplyWindowInsetsListener { v, ins ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
                @Suppress("DEPRECATION")
                ins.consumeSystemWindowInsets()
            }
        }

        try { Trail.restore(this) } catch (_: Exception) {}
        if (Trail.active || prefs.getString("tab", "plan") == "trail") showTrail() else showPlan()
        handleIntent(intent)
    }

    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    private fun tab(label: String, onClick: () -> Unit) = text(label, 15f, C.DIM, bold = true).apply {
        gravity = Gravity.CENTER
        background = ripple(roundRect(0x00000000, 0f))
        setOnClickListener { onClick() }
    }

    private fun highlight(planTab: Boolean) {
        tabPlan.setTextColor(if (planTab) C.TEXT else C.DIM)
        tabTrail.setTextColor(if (planTab) C.DIM else C.TEXT)
        tabPlan.setBackgroundColor(if (planTab) 0xFF1E1E1E.toInt() else 0)
        tabTrail.setBackgroundColor(if (planTab) 0 else 0xFF1E1E1E.toInt())
    }

    fun showPlan() {
        if (Trail.active || plan != null) return
        trail?.destroy()
        trail = null
        content.removeAllViews()
        val p = PlanScreen(this)
        plan = p
        content.addView(p.view, FrameLayout.LayoutParams(MATCH, MATCH))
        p.onVisible(true)
        Dev.event("ui", "tab plan")
        prefs.edit().putString("tab", "plan").apply()
        highlight(true)
    }

    fun showTrail() {
        if (trail != null) return
        plan?.destroy()
        plan = null
        content.removeAllViews()
        val t = TrailScreen(this)
        trail = t
        content.addView(t.view, FrameLayout.LayoutParams(MATCH, MATCH))
        prefs.edit().putString("tab", "trail").apply()
        Dev.event("ui", "tab trail")
        highlight(false)
    }

    /** Trail mode: hide everything except the trail screen. */
    fun setTrailMode(on: Boolean) {
        tabs.visibility = if (on) View.GONE else View.VISIBLE
    }

    override fun onStart() {
        super.onStart()
        Dev.event("ui", "app visible · tab ${if (trail != null) "trail" else "plan"} · trail recording ${Trail.active} · battery ${Dev.battery(this)}")
        if (Trail.active && !Trail.hasPermission(this)) withLocationPermission { Trail.ensureService(this) }
        else Trail.ensureService(this)
        Trail.setUiVisible(true)
        plan?.onVisible(true)
        trail?.onVisible(true)
    }

    override fun onStop() {
        Dev.event("ui", "app hidden · trail recording ${Trail.active}")
        Trail.setUiVisible(false)
        plan?.onVisible(false)
        trail?.onVisible(false)
        super.onStop()
    }

    override fun onDestroy() {
        plan?.destroy()
        trail?.destroy()
        plan = null
        trail = null
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (Trail.active) { moveTaskToBack(true); return } // never quit while recording
        if (plan?.onBack() == true) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------------ import / export
    private fun handleIntent(i: Intent?) {
        val uri: Uri? = when (i?.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                i.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            else -> null
        }
        if (uri != null) {
            i?.action = null // don't import twice on rotation / re-delivery
            importUri(uri)
        }
    }

    fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        try { startActivityForResult(i, RC_IMPORT) } catch (e: Exception) { toast(tr("No file picker available", "Dosya seçici bulunamadı")) }
    }

    fun exportFile(e: Entry) {
        exportEntry = e
        prefs.edit().putString("exportKind", e.kind.name).putString("exportId", e.id).apply()
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/gpx+xml").putExtra(Intent.EXTRA_TITLE, ShareProvider.fileName(e.name))
        try { startActivityForResult(i, RC_EXPORT) } catch (ex: Exception) { toast(tr("No file picker available", "Dosya seçici bulunamadı")) }
    }

    // ------------------------------------------------------------------ trail photos
    /**
     * Opens the phone's camera app; it writes the photo straight into the recording's photo folder.
     * The pending photo is remembered in prefs, so it survives HiOS killing the app while the camera is open.
     */
    fun takePhoto() {
        if (!Trail.active) return
        val now = System.currentTimeMillis()
        val name = "PlainTrekking_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date(now)) + ".jpg"
        val pos = Trail.photoPosition(this)
        prefs.edit().putString("photoName", name).putLong("photoTime", now).putString("photoPos", "${pos[0]},${pos[1]},${pos[2]}").apply()
        val out = ShareProvider.captureUri(name)
        val i = Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
            .putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = android.content.ClipData.newRawUri("photo", out)
        // Since Android 11 an implicit photo request only reaches preinstalled camera apps, so the
        // sideloaded Plain Camera is asked by name when it's installed (else the system camera as before).
        if (packageManager.resolveActivity(Intent(i).setPackage(PLAIN_CAMERA), 0) != null) i.setPackage(PLAIN_CAMERA)
        try {
            startActivityForResult(i, RC_PHOTO)
            Trail.devEvent("photo", "camera opened $name" + if (i.`package` != null) " (${i.`package`})" else "")
        } catch (e: android.content.ActivityNotFoundException) {
            toast(tr("No camera app found.", "Kamera uygulaması bulunamadı."))
        }
    }

    private fun photoResult(ok: Boolean) {
        val name = prefs.getString("photoName", null) ?: return
        val time = prefs.getLong("photoTime", System.currentTimeMillis())
        val pos = prefs.getString("photoPos", "")!!.split(',').map { it.toDoubleOrNull() ?: Double.NaN }
        prefs.edit().remove("photoName").remove("photoTime").remove("photoPos").apply()
        val dir = Photos.recDir(this)
        val f = java.io.File(dir, name)
        if (!ok || !f.exists() || f.length() == 0L) {
            f.delete()
            Trail.devEvent("photo", "cancelled")
            return
        }
        thread {
            val msg = try {
                val t0 = System.currentTimeMillis()
                val media = try { Photos.toGallery(this, f, name, time) } catch (e: Exception) { Dev.event("photo", "gallery copy failed: $e"); "" }
                val p = Photo(name, time, pos.getOrElse(0) { Double.NaN }, pos.getOrElse(1) { Double.NaN }, pos.getOrElse(2) { Double.NaN }, media)
                Photos.makeThumb(f, Photos.thumbFile(dir, p))
                Photos.add(dir, p)
                Trail.devEvent("photo", "saved $name ${f.length() / 1024} KB at ${p.lat};${p.lon} gallery=${media.isNotEmpty()} in ${System.currentTimeMillis() - t0} ms", alsoApp = true)
                if (media.isNotEmpty()) tr("Photo saved to the trail and your Gallery.", "Fotoğraf rotaya ve Galeri'ye kaydedildi.")
                else tr("Photo saved to the trail.", "Fotoğraf rotaya kaydedildi.")
            } catch (e: Exception) {
                Dev.event("photo", "save failed: $e")
                tr("Could not save the photo: ${e.message}", "Fotoğraf kaydedilemedi: ${e.message}")
            }
            runOnUiThread {
                toast(msg)
                trail?.refreshPhotos()
            }
        }
    }

    /** Full-screen view of a trail photo; tap to close. */
    /** Full-screen viewer for a trail's photos, opened at [p] (see PhotoViewer). */
    fun showPhoto(p: Photo, dir: java.io.File, route: List<MLine>? = null, onRemoved: ((Photo) -> Unit)? = null) =
        PhotoViewer(this, p, dir, route, onRemoved).show()

    /** Shares an exported .zip / .kmz from the share cache. */
    fun shareExport(f: java.io.File, type: String, title: String) {
        val i = Intent(Intent.ACTION_SEND).setType(type)
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.cacheUri(f))
            .putExtra(Intent.EXTRA_SUBJECT, f.nameWithoutExtension)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, title))
    }

    fun shareDebugZip(f: java.io.File) {
        val i = Intent(Intent.ACTION_SEND).setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.debugUri(f))
            .putExtra(Intent.EXTRA_SUBJECT, "Plain Trekking debug logs")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, tr("Share debug logs", "Hata ayıklama kayıtlarını paylaş")))
    }

    fun share(e: Entry) {
        Dev.event("share", "gpx ${e.kind} “${e.name}”")
        val i = Intent(Intent.ACTION_SEND).setType("application/gpx+xml")
            .putExtra(Intent.EXTRA_STREAM, ShareProvider.uri(e))
            .putExtra(Intent.EXTRA_SUBJECT, e.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(i, tr("Share GPX", "GPX paylaş")))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == RC_PHOTO) { photoResult(resultCode == RESULT_OK); return }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            RC_IMPORT -> importUri(uri)
            RC_EXPORT -> {
                // the process may have died while the file picker was open: fall back to what we saved
                val e = exportEntry ?: Store.get(
                    Kind.entries.firstOrNull { it.name == prefs.getString("exportKind", "") } ?: return,
                    prefs.getString("exportId", null),
                ) ?: return
                thread {
                    val msg = try {
                        contentResolver.openOutputStream(uri)!!.use { out -> Store.gpx(e.kind, e.id).inputStream().use { it.copyTo(out) } }
                        tr("Saved ${ShareProvider.fileName(e.name)}", "Kaydedildi: ${ShareProvider.fileName(e.name)}")
                    } catch (ex: Exception) {
                        tr("Could not save: ${ex.message}", "Kaydedilemedi: ${ex.message}")
                    }
                    runOnUiThread { toast(msg) }
                }
            }
        }
    }

    fun importUri(uri: Uri) {
        if (Trail.active) { toast(tr("Stop the trail first, then import.", "Önce rotayı durdurun, sonra içe aktarın.")); return }
        val display = displayName(uri)
        thread {
            val msg = try {
                val r = contentResolver.openInputStream(uri)!!.use { GpxIo.parse(it) }
                val name = r.name?.takeIf { it.isNotBlank() }
                    ?: display.substringBeforeLast('.').ifBlank { tr("Imported trail", "İçe aktarılan rota") }
                val e = Store.save(Kind.PLAN, r, name)
                Dev.event("import", "“$display” → plan “${e.name}” · ${r.segs.size} segments · ${r.points} points · ${Fmt.dist(e.distM)}")
                tr("Added plan “${e.name}” · ${Fmt.dist(e.distM)}", "Plan eklendi: “${e.name}” · ${Fmt.dist(e.distM)}")
            } catch (ex: Exception) {
                Dev.event("import", "“$display” failed: $ex")
                tr("Could not import: ${ex.message}", "İçe aktarılamadı: ${ex.message}")
            }
            runOnUiThread {
                toast(msg)
                if (!Trail.active) {
                    showPlan()
                    plan?.showSection(1)
                }
            }
        }
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (e: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    // ------------------------------------------------------------------ permissions
    fun withLocationPermission(cb: () -> Unit) {
        val need = ArrayList<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need += Manifest.permission.ACCESS_FINE_LOCATION
            need += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 && !prefs.getBoolean("askedNotif", false) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need += Manifest.permission.POST_NOTIFICATIONS
            prefs.edit().putBoolean("askedNotif", true).apply()
        }
        if (need.isEmpty()) { cb(); return }
        permCb = cb
        requestPermissions(need.toTypedArray(), RC_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Dev.event("permission", permissions.indices.joinToString { "${permissions[it].substringAfterLast('.')}=${if (grantResults.getOrNull(it) == 0) "granted" else "denied"}" })
        if (requestCode != RC_PERMS) return
        val cb = permCb
        permCb = null
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) cb?.invoke()
        else toast(tr("Trail recording needs Precise location permission.", "Rota kaydı için Hassas konum izni gerekir."))
    }

    /** Settings: the three toggles. [onApplied] runs after any change so screens can react immediately. */
    fun showSettings(onApplied: () -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(6), dp(20), 0) }
        fun option(key: String, def: Boolean, title: String, desc: String) {
            val cb = android.widget.CheckBox(this).apply {
                text = title; textSize = 15f; isChecked = prefs.getBoolean(key, def)
                setOnCheckedChangeListener { _, v ->
                    prefs.edit().putBoolean(key, v).apply()
                    Dev.event("settings", "$key=$v")
                    onApplied()
                }
            }
            box.addView(cb)
            box.addView(text(desc, 12f, C.DIM).apply { setPadding(dp(32), 0, 0, dp(10)) })
        }
        option("snap", true, tr("Snap planned trails to paths", "Planlanan rotaları patikalara oturt"),
            tr("While drawing a plan, each tap is routed along OpenStreetMap footpaths and tracks — the dashed paths on the topo map. Needs Wi-Fi; off = straight lines.",
                "Plan çizerken her dokunuş OpenStreetMap patika ve yollarını izler (topo haritadaki kesikli yollar). Wi-Fi gerekir; kapalıysa düz çizgi."))
        option("longHike", false, tr("Long hike mode", "Uzun yürüyüş modu"),
            tr("In your pocket GPS takes a fix every 5 s instead of 2 s and hands them over every 2 minutes, so the GPS chip and phone sleep more. Tracks stay detailed: a point every ~7 m at walking pace.",
                "Cepteyken GPS 2 sn yerine 5 sn'de bir konum alır ve bunları 2 dakikada bir iletir; böylece GPS yongası ve telefon daha çok uyur. İz yine ayrıntılı kalır: yürüme hızında ~7 m'de bir nokta."))
        option("dim", false, tr("Dim map", "Haritayı karart"),
            tr("Darkens the map but keeps your track, plan and position bright. Your screen is AMOLED, so darker pixels use less power.",
                "Haritayı karartır ama izinizi, planınızı ve konumunuzu parlak tutar. Ekranınız AMOLED; koyu pikseller daha az güç harcar."))
        option("land", true, tr("Show private land", "Özel araziyi göster"),
            tr("Colours land you'd better not cross on maps downloaded with the private-land layer: fields & orchards (amber), " +
                "houses & yards (red), military zones (hatched). From OpenStreetMap and ESA satellite land cover — a guide, not the land registry.",
                "Özel arazi katmanıyla indirilen haritalarda girmemeniz gereken yerleri boyar: tarla ve bahçeler (turuncu), " +
                "evler ve avlular (kırmızı), askeri bölgeler (taralı). OpenStreetMap ve ESA uydu arazi örtüsünden — tapu kaydı değil, yalnızca bir rehber."))
        option("stats", false, tr("Statistics overlay", "İstatistik katmanı"),
            tr("Thin text on the map: GPS accuracy, satellites, the direction you face, speed, and how detailed the map really is where you are.",
                "Harita üzerinde ince yazı: GPS doğruluğu, uydular, baktığınız yön, hız ve bulunduğunuz yerde haritanın gerçek ayrıntısı."))
        // language: a dropdown under this row; a change rebuilds the screens (never touches a recording)
        val lang = text(PlainLang.label(), 15f).apply {
            setPadding(dp(4), dp(12), dp(4), dp(12))
            background = ripple(roundRect(0x00000000, dpf(6)))
        }
        box.addView(lang, lpL(MATCH, WRAP))
        val d = AlertDialog.Builder(this).setTitle(tr("Settings", "Ayarlar"))
            .setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(tr("Done", "Bitti"), null)
            .setNeutralButton(PlainUpdate.label(this)) { _, _ -> PlainUpdate.check(this) }
            .show()
        lang.setOnClickListener {
            PlainLang.choose(this, lang) {
                Dev.event("settings", "language=${PlainLang.choice}")
                d.dismiss()
                languageChanged()
            }
        }
    }

    /**
     * After a language change: rebuild the tab labels and the visible screen in place instead of
     * recreating the activity. The recording lives in Trail/TrailService and is not touched; the
     * screen is rebuilt exactly as on a tab switch (the Trail screen re-attaches to the recording).
     */
    private fun languageChanged() {
        try { Notif.channels(this) } catch (_: Exception) {} // channel names follow the language
        tabPlan.text = tr("Plan", "Plan")
        tabTrail.text = tr("Trail", "Rota")
        val onTrail = trail != null
        plan?.destroy()
        trail?.destroy()
        plan = null
        trail = null
        if (onTrail || Trail.active) showTrail() else showPlan()
        Trail.ensureService(this) // re-posts the recording notification in the new language (no-op when idle)
    }

    /** What the private-land colours mean. */
    fun showLandLegend() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        for ((color, label) in LandRender.legend()) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
            row.addView(View(this).apply { background = roundRect(color, dpf(4)) }, lpL(dp(22), dp(16)).apply { marginEnd = dp(12) })
            row.addView(text(label, 14f), lpL(0, WRAP, 1f))
            box.addView(row)
        }
        box.addView(text(
            tr("Built from OpenStreetMap and ESA WorldCover satellite land cover, so it's a guide, not the land registry: " +
                "unmapped fields and houses won't show. Walking across open, unfenced land is usually tolerated; " +
                "fenced land near houses is not. In summer, governors often ban entering forests (fire season) — check before you go.",
                "OpenStreetMap ve ESA WorldCover uydu arazi örtüsünden üretilir; tapu kaydı değil, bir rehberdir: " +
                "haritada olmayan tarla ve evler görünmez. Açık, çitsiz araziden geçmek genelde hoş görülür; " +
                "evlerin yanındaki çitli arazi ise hoş görülmez. Yazın valilikler çoğu zaman ormana girişi yasaklar (yangın sezonu) — yola çıkmadan kontrol edin."),
            12f, C.DIM,
        ).apply { setPadding(0, dp(10), 0, 0) })
        AlertDialog.Builder(this).setTitle(tr("Private land", "Özel arazi")).setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton(tr("OK", "Tamam"), null).show()
    }

    /** One-time guidance so HiOS doesn't kill tracking in the pocket (one step per trail start). */
    fun batteryTips() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName) || prefs.getBoolean("askedBattery", false)) {
            backgroundLocationTip()
            return
        }
        prefs.edit().putBoolean("askedBattery", true).apply()
        AlertDialog.Builder(this)
            .setTitle(tr("Keep tracking in your pocket", "Cepteyken kayıt sürsün"))
            .setMessage(
                tr("Tecno's HiOS can stop apps in the background, which leaves gaps in your track.\n\n" +
                    "1. Tap Continue and choose Allow (ignore battery optimisation).\n" +
                    "2. In Settings › Battery / app launch management, let Plain Trekking run in the background " +
                    "(the menu names differ between HiOS versions).\n" +
                    "3. In Recent apps, lock Plain Trekking.\n\n" +
                    "The app still saves battery itself: GPS turns off while you rest and the map only draws while you look at it.",
                    "Tecno'nun HiOS sistemi arka plandaki uygulamaları durdurabilir; bu da izinizde boşluk bırakır.\n\n" +
                    "1. Devam'a dokunun ve İzin ver'i seçin (pil optimizasyonunu yok say).\n" +
                    "2. Ayarlar › Pil / uygulama başlatma yönetimi bölümünde Plain Trekking'in arka planda çalışmasına izin verin " +
                    "(menü adları HiOS sürümüne göre değişir).\n" +
                    "3. Son uygulamalar ekranında Plain Trekking'i kilitleyin.\n\n" +
                    "Uygulama yine de pil tasarrufu yapar: dinlenirken GPS kapanır, harita yalnızca baktığınızda çizilir.")
            )
            .setPositiveButton(tr("Continue", "Devam")) { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
            .setNegativeButton(tr("Later", "Sonra"), null)
            .show()
    }

    private fun backgroundLocationTip() {
        if (Build.VERSION.SDK_INT < 29 || prefs.getBoolean("askedBgLoc", false)) return
        if (checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) return
        // only remembered once a button is chosen; dismissing asks again next time
        AlertDialog.Builder(this)
            .setTitle(tr("Auto-resume recording", "Kaydı otomatik sürdür"))
            .setMessage(
                tr("If HiOS ever closes Plain Trekking mid-hike, Android can restart the recording by itself — " +
                    "but only with location set to “Allow all the time”.\n\n" +
                    "Location is still used only while a trail is recording. Without this, recording resumes when you reopen the app.",
                    "HiOS yürüyüş sırasında Plain Trekking'i kapatırsa Android kaydı kendiliğinden yeniden başlatabilir — " +
                    "ama yalnızca konum izni “Her zaman izin ver” ise.\n\n" +
                    "Konum yine yalnızca rota kaydedilirken kullanılır. Bu olmadan kayıt, uygulamayı yeniden açtığınızda sürer.")
            )
            .setPositiveButton(tr("Set it", "Ayarla")) { _, _ ->
                prefs.edit().putBoolean("askedBgLoc", true).apply()
                Dev.event("permission", "background location: user chose Set it")
                permCb = null
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RC_PERMS + 1)
            }
            .setNegativeButton(tr("No thanks", "Hayır, kalsın")) { _, _ ->
                prefs.edit().putBoolean("askedBgLoc", true).apply()
                Dev.event("permission", "background location: user chose No thanks")
            }
            .show()
    }
}
