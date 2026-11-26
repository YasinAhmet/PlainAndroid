package app.plain.setup

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import java.util.concurrent.Executors

/**
 * Pick Plain apps to install or update. The APKs inside Setup work offline; "Check for updates"
 * (also run on open) reads apps.json from the PlainAndroid repo and offers newer releases.
 * Other Plain apps open this screen with [PlainUpdate.ACTION] + their package to update themselves.
 */
class SetupActivity : Activity(), Installer.Listener {

    private companion object { const val REQ_UNINSTALL = 41 }

    private object C {
        const val BG = 0xFF000000.toInt()
        const val OUTLINE = 0xFF2A2A2A.toInt()
        const val TEXT = 0xFFE4E4E4.toInt()
        const val DIM = 0xFFA6A6A6.toInt()
        const val FAINT = 0xFF6A6A6A.toInt()
        const val ACCENT = 0xFFE8A447.toInt()
        const val GREEN = 0xFF6CBF6C.toInt()
        const val RED = 0xFFD46A6A.toInt()
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var bundled: List<Entry> = emptyList()
    private var online: List<Entry>? = null
    private var onlineError: String? = null
    private var checking = false
    private var offers: List<Offer> = emptyList()
    private val selected = HashSet<String>()
    private val results = HashMap<String, Pair<Boolean, String>>()
    private var focusPkg: String? = null      // set when another app asked for its update
    private var autoInstallFocus = false
    private var waitingPermission: List<Offer>? = null
    private var pendingRollback: Offer? = null   // old release to install once the uninstall is done
    private var infoStatus = false               // status shows the online-check note, which rebuild() may replace
    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }

    /** Apps rolled back on purpose: not pre-ticked, not in "Update all", until updated by hand. */
    private var held: Set<String>
        get() = prefs.getStringSet("held", emptySet())!!.toSet()
        set(v) { prefs.edit().putStringSet("held", v).apply() }

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var skip: TextView
    private lateinit var checkBtn: TextView
    private lateinit var installBtn: TextView
    private lateinit var updateAllBtn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        bundled = try { Catalog.parse(assets.open("apps.json").use { it.readBytes().toString(Charsets.UTF_8) }) } catch (_: Exception) { emptyList() }
        buildUi()
        handle(intent)
        Installer.listener = this
        rebuild()
        checkOnline()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Switching to the Setup tab brings this screen back with a plain MAIN intent: nothing to do (no online check).
        if (intent.action != PlainUpdate.ACTION) return
        setIntent(intent)
        handle(intent)
        rebuild()
        checkOnline()
    }

    private fun handle(i: Intent?) {
        if (i?.action == PlainUpdate.ACTION) {
            focusPkg = i.getStringExtra(PlainUpdate.EXTRA_PACKAGE)
            autoInstallFocus = focusPkg != null
            selected.clear()
        }
    }

    override fun onResume() {
        super.onResume()
        Installer.listener = this
        waitingPermission?.let { w ->
            if (canInstall()) { waitingPermission = null; begin(w) }
        }
        rebuild()
    }

    override fun onDestroy() {
        if (Installer.listener === this) Installer.listener = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ data

    private fun installedCode(pkg: String): Long? = try {
        val pi = packageManager.getPackageInfo(pkg, 0)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) { null }

    private fun installedName(pkg: String): String? = try { packageManager.getPackageInfo(pkg, 0).versionName } catch (_: Exception) { null }

    private fun stateOf(o: Offer) = Catalog.state(o, installedCode(o.entry.pkg), Build.VERSION.SDK_INT)

    private fun checkOnline() {
        if (checking) return
        checking = true
        onlineError = null
        rebuild()
        worker.execute {
            val r = try { Result.success(Catalog.parse(Net.text(Catalog.ONLINE_URL))) } catch (e: Exception) { Result.failure(e) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                checking = false
                r.onSuccess { online = it }.onFailure { onlineError = it.message ?: it.javaClass.simpleName }
                rebuild()
                afterCheck()
            }
        }
    }

    /** When opened from another app's "Check for updates": install its update straight away. */
    private fun afterCheck() {
        val pkg = focusPkg ?: return
        if (!autoInstallFocus) return
        autoInstallFocus = false
        val o = offers.firstOrNull { it.entry.pkg == pkg }
        when {
            o == null -> say(tr("This app isn't in the Plain catalog.", "Bu uygulama Plain kataloğunda yok."))
            stateOf(o) == Catalog.State.UPDATE || stateOf(o) == Catalog.State.NOT_INSTALLED -> { selected.clear(); selected += pkg; rebuild(); install() }
            onlineError != null -> say(tr("Couldn't check online: $onlineError", "İnternetten denetlenemedi: $onlineError"))
            else -> say(tr("${o.entry.name} is up to date (${installedName(pkg) ?: o.entry.versionName}).",
                "${o.entry.name} güncel (${installedName(pkg) ?: o.entry.versionName})."))
        }
    }

    // ------------------------------------------------------------------ install

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun updates() = Catalog.updates(offers, ::installedCode, Build.VERSION.SDK_INT, held)

    /** Bulk update: every installed Plain app with a newer version, whatever is ticked. */
    private fun updateAll() {
        val ups = updates()
        if (ups.isEmpty()) { say(tr("Everything is up to date.", "Hepsi güncel.")); return }
        selected += ups.map { it.entry.pkg }
        install(ups)
    }

    private fun install(only: List<Offer>? = null) {
        val chosen = only ?: offers.filter { it.entry.pkg in selected && stateOf(it).let { s -> s == Catalog.State.NOT_INSTALLED || s == Catalog.State.UPDATE } }
        if (chosen.isEmpty()) { say(tr("Nothing to install: tick an app that isn't installed or has an update.",
            "Yüklenecek bir şey yok: yüklü olmayan ya da güncellemesi olan bir uygulama seçin.")); return }
        // The first install from a phone with Play Protect: say beforehand what its warning means.
        if (!prefs.getBoolean("protect_seen", false)) {
            prefs.edit().putBoolean("protect_seen", true).apply()
            playProtect(onContinue = { install(chosen) })
            return
        }
        if (!canInstall()) {
            waitingPermission = chosen
            AlertDialog.Builder(this)
                .setTitle(tr("Allow installs", "Yüklemeye izin verin"))
                .setMessage(tr("Android needs your OK once: on the next screen, allow Plain Manager to install apps, then come back.",
                    "Android bir kez onayınızı istiyor: sonraki ekranda Plain Manager'ın uygulama yüklemesine izin verin, sonra geri dönün."))
                .setPositiveButton(tr("Open", "Aç")) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }
                .setNegativeButton(tr("Cancel", "İptal")) { _, _ -> waitingPermission = null }
                .show()
            return
        }
        begin(chosen)
    }

    private fun begin(chosen: List<Offer>) {
        chosen.forEach { results.remove(it.entry.pkg) }
        // Installing the newest version by hand ends a hold; a rollback install starts one.
        val rolling = chosen.filter { o -> offers.none { it.entry.pkg == o.entry.pkg && it.entry.versionCode == o.entry.versionCode } }.map { it.entry.pkg }
        held = held - chosen.map { it.entry.pkg }.toSet() + rolling
        Installer.start(this, chosen)
        rebuild()
    }

    // ------------------------------------------------------------------ rollback

    /** The local backup first (the version before the last update), then the online releases. */
    private fun rollbackChoices(o: Offer): List<Offer> {
        val code = installedCode(o.entry.pkg) ?: return emptyList()
        val local = Backups.find(this, o.entry)?.takeIf { it.versionCode < code }
        val online = Catalog.olderVersions(o, code, Build.VERSION.SDK_INT).filter { it.versionCode != local?.versionCode }
        return listOfNotNull(local?.let { Offer(it, Source.LOCAL) }) + online.map { Offer(it, Source.ONLINE) }
    }

    private fun showOlder(o: Offer) {
        val olds = rollbackChoices(o)
        if (olds.isEmpty()) { say(tr("No older version of ${o.entry.name} to go back to.", "${o.entry.name} için dönülecek eski sürüm yok.")); return }
        val labels = olds.map {
            it.entry.versionName + if (it.source == Source.LOCAL) tr("  ·  your previous version (saved on this phone)", "  ·  önceki sürümünüz (bu telefonda kayıtlı)")
            else Catalog.sizeText(it.entry.size).let { z -> if (z.isEmpty()) tr("  ·  download", "  ·  indir") else tr("  ·  download $z", "  ·  indir $z") }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(tr("${o.entry.name}: go back to", "${o.entry.name}: şu sürüme dön"))
            .setItems(labels) { _, i -> confirmRollback(o, olds[i].entry, olds[i].source) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    private fun confirmRollback(o: Offer, old: Entry, src: Source) {
        val now = installedName(o.entry.pkg) ?: "?"
        if (o.entry.pkg == packageName) {
            // Setup can't uninstall itself and carry on; hand over to the release page.
            AlertDialog.Builder(this)
                .setTitle("Plain Manager ${old.versionName}")
                .setMessage(tr("Setup can't replace itself with an older copy. The release page opens: download PlainSetup.apk there, " +
                    "uninstall this Setup, then open the download.",
                    "Setup kendini eski bir kopyayla değiştiremez. Sürüm sayfası açılır: oradan PlainSetup.apk dosyasını indirin, " +
                    "bu Setup'ı kaldırın, sonra indirdiğiniz dosyayı açın."))
                .setPositiveButton(tr("Open page", "Sayfayı aç")) { _, _ ->
                    val page = old.url.substringBefore("/releases/download/") + "/releases/tag/" + old.url.substringAfter("/releases/download/").substringBefore('/')
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) } catch (e: Exception) { say(tr("No browser: ${e.message}", "Tarayıcı yok: ${e.message}")) }
                }
                .setNegativeButton(tr("Cancel", "İptal"), null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Go back to ${o.entry.name} ${old.versionName}?", "${o.entry.name} ${old.versionName} sürümüne dönülsün mü?"))
            .setMessage(tr("Android can't install an older version over a newer one. Setup will:\n\n" +
                "1. Uninstall ${o.entry.name} $now. Its app data is deleted: settings, saved lists and anything kept inside the app. " +
                "Files in your shared folders (Music, Pictures, Download…) stay.\n" +
                "2. Install ${old.versionName}" + (if (src == Source.LOCAL) " from the copy saved on this phone.\n\n" else " (downloaded and checked).\n\n") +
                "Afterwards ${o.entry.name} stays on ${old.versionName}: Update all skips it until you update it yourself.",
                "Android eski bir sürümü yenisinin üzerine yükleyemez. Setup şunları yapacak:\n\n" +
                "1. ${o.entry.name} $now kaldırılır. Uygulama verileri silinir: ayarlar, kayıtlı listeler ve uygulamanın içinde tutulan her şey. " +
                "Ortak klasörlerinizdeki dosyalar (Müzik, Resimler, İndirilenler…) kalır.\n" +
                "2. ${old.versionName} " + (if (src == Source.LOCAL) "bu telefonda kayıtlı kopyadan yüklenir.\n\n" else "indirilip denetlenerek yüklenir.\n\n") +
                "Sonrasında ${o.entry.name} ${old.versionName} sürümünde kalır: siz elle güncelleyene kadar Tümünü güncelle onu atlar."))
            .setPositiveButton(tr("Uninstall and go back", "Kaldır ve geri dön")) { _, _ -> startRollback(Offer(old, src)) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    private fun startRollback(old: Offer) {
        if (Installer.busy) { say(tr("Wait for the current install to finish.", "Süren yüklemenin bitmesini bekleyin.")); return }
        pendingRollback = old
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${old.entry.pkg}")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try { startActivityForResult(i, REQ_UNINSTALL) } catch (e: Exception) { pendingRollback = null; say(tr("Can't uninstall: ${e.message}", "Kaldırılamıyor: ${e.message}")) }
    }

    @Deprecated("Activity result API needs AndroidX; this app has no libraries.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UNINSTALL) return
        val old = pendingRollback ?: return
        pendingRollback = null
        if (installedCode(old.entry.pkg) != null) { say(tr("Rollback cancelled: ${old.entry.name} is still installed.", "Geri dönüş iptal edildi: ${old.entry.name} hâlâ yüklü.")); rebuild(); return }
        selected.remove(old.entry.pkg)
        if (!canInstall()) { waitingPermission = listOf(old); install(listOf(old)); return }
        begin(listOf(old))
    }

    override fun onStatus(text: String) { setStatus(text, C.ACCENT); skip.visibility = if (Installer.confirming) View.VISIBLE else View.GONE }

    override fun onConfirm(confirm: Intent) {
        try { startActivity(confirm) } catch (e: Exception) { say(tr("Can't show the install dialog: ${e.message}", "Yükleme penceresi gösterilemiyor: ${e.message}")) }
    }

    override fun onItemDone(pkg: String, ok: Boolean, message: String, blocked: Boolean) {
        results[pkg] = ok to message
        if (ok) selected.remove(pkg)
        skip.visibility = View.GONE
        rebuild()
        if (blocked && !protectShown) { protectShown = true; playProtect() }
    }
    private var protectShown = false   // the guide pops up by itself once per visit, not after every blocked app

    override fun onQueueDone() {
        val fails = results.values.count { !it.first }
        setStatus(if (fails == 0) tr("Done.", "Bitti.") else tr("Done — $fails didn't install (see below).", "Bitti — $fails uygulama yüklenmedi (aşağıya bakın)."),
            if (fails == 0) C.GREEN else C.RED)
        rebuild()
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun outline(stroke: Int = C.OUTLINE, radius: Int = 14) = GradientDrawable().apply {
        setColor(C.BG); cornerRadius = dp(radius).toFloat(); setStroke(dp(1), stroke)
    }

    private fun pill(label: String, primary: Boolean, onClick: () -> Unit) = text(label, 15f, if (primary) C.BG else C.TEXT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = if (primary) GradientDrawable().apply { setColor(C.ACCENT); cornerRadius = dp(24).toFloat() } else outline(0xFF3A3A3A.toInt(), 24)
        setOnClickListener { onClick() }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val langBtn = text(PlainLang.label(), 13f, C.TEXT, bold = true).apply {
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = outline(0xFF3A3A3A.toInt(), 16)
        }
        langBtn.setOnClickListener { PlainLang.choose(this, langBtn) }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(tr("Install & update", "Yükle ve güncelle"), 22f, C.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(langBtn)
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            addView(titleRow)
            addView(text(tr("Simple, offline-first apps. Tick the ones you want and tap Install. " +
                "Android asks you to confirm each app.",
                "Sade, internetsiz çalışan uygulamalar. İstediklerinizi seçip Yükle'ye dokunun. " +
                "Android her uygulama için onay ister."), 14f, C.DIM).apply { setPadding(0, dp(4), 0, 0) })
            addView(text(tr("Play Protect warning? How to install anyway ›", "Play Protect uyarısı mı? Yine de yüklemek için ›"), 14f, C.ACCENT, bold = true).apply {
                setPadding(0, dp(8), 0, dp(2))
                setOnClickListener { playProtect() }
            }, LinearLayout.LayoutParams(-2, -2))
        }
        status = text("", 13f, C.ACCENT).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        skip = text(tr("Skip this app", "Bu uygulamayı atla"), 13f, C.TEXT, bold = true).apply {
            setPadding(dp(20), dp(2), dp(20), dp(8)); visibility = View.GONE
            setOnClickListener { Installer.skipCurrent(this@SetupActivity) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        val scroll = ScrollView(this).apply { addView(list) }
        checkBtn = pill(tr("Check for updates", "Güncellemeleri denetle"), false) { checkOnline() }
        installBtn = pill(tr("Install", "Yükle"), true) { install() }
        updateAllBtn = pill(tr("Update all", "Tümünü güncelle"), false) { updateAll() }.apply {
            setTextColor(C.ACCENT); background = outline(C.ACCENT, 24); visibility = View.GONE
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(checkBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(8) })
            addView(installBtn, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
            addView(updateAllBtn, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            addView(row)
        }
        root.addView(head); root.addView(status); root.addView(skip)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); root.addView(bar)
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(Hub.wrap(this, "setup", root))
    }

    private fun setStatus(s: String, color: Int, info: Boolean = false) { infoStatus = info; status.text = s; status.setTextColor(color); status.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }

    private fun say(s: String) {
        setStatus(s, C.DIM)
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun rebuild() {
        if (!::list.isInitialized) return
        offers = Catalog.merge(bundled, online)
        // Pre-tick available updates the first time we see them (not while installing).
        val hold = held
        if (!Installer.busy && focusPkg == null) for (o in offers) {
            if (stateOf(o) == Catalog.State.UPDATE && o.entry.pkg !in results && o.entry.pkg !in hold) selected += o.entry.pkg
            if (stateOf(o) == Catalog.State.NOT_INSTALLED && o.entry.pkg !in results && o.entry.pkg !in preTicked && replacedBy(o).isNotEmpty()) {
                preTicked += o.entry.pkg; selected += o.entry.pkg
            }
        }
        list.removeAllViews()
        val busy = Installer.busy
        if (offers.isEmpty()) list.addView(text(tr("No app list yet. Connect to the internet and tap Check for updates.",
            "Henüz uygulama listesi yok. İnternete bağlanıp Güncellemeleri denetle'ye dokunun."), 14f, C.DIM).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) })
        for (o in offers) if (o.entry.mergedInto.isEmpty() || installedCode(o.entry.pkg) != null) list.addView(row(o, busy))

        val n = offers.count { it.entry.pkg in selected && stateOf(it).let { s -> s == Catalog.State.NOT_INSTALLED || s == Catalog.State.UPDATE } }
        installBtn.text = if (busy) tr("Installing…", "Yükleniyor…") else if (n == 0) tr("Install", "Yükle") else tr("Install $n", "$n uygulamayı yükle")
        installBtn.isEnabled = !busy && n > 0
        installBtn.alpha = if (installBtn.isEnabled) 1f else 0.4f
        val ups = if (busy) 0 else updates().size
        updateAllBtn.text = if (ups == 1) tr("Update all (1 app)", "Tümünü güncelle (1 uygulama)") else tr("Update all ($ups apps)", "Tümünü güncelle ($ups uygulama)")
        updateAllBtn.visibility = if (ups > 0) View.VISIBLE else View.GONE
        checkBtn.text = if (checking) tr("Checking…", "Denetleniyor…") else tr("Check for updates", "Güncellemeleri denetle")
        checkBtn.isEnabled = !checking && !busy

        if (!busy && status.text.isNullOrEmpty() || infoStatus) {
            when {
                checking -> setStatus("", C.DIM)
                onlineError != null -> setStatus(tr("Offline: showing the apps inside Setup ($onlineError).",
                    "İnternet yok: Setup'ın içindeki uygulamalar gösteriliyor ($onlineError)."), C.FAINT, info = true)
                online != null -> setStatus(tr("Checked online: newest versions shown.", "İnternetten denetlendi: en yeni sürümler gösteriliyor."), C.FAINT, info = true)
            }
        }
        list.addView(text(tr("Setup ${installedName(packageName) ?: ""} · apps from github.com/YasinAhmet/PlainAndroid. " +
            "To remove apps, use the Uninstaller tab.",
            "Setup ${installedName(packageName) ?: ""} · uygulamalar github.com/YasinAhmet/PlainAndroid adresinden. " +
            "Uygulama kaldırmak için Kaldırıcı sekmesini kullanın."), 12f, C.FAINT).apply { setPadding(dp(8), dp(12), dp(8), 0) })
    }

    private val preTicked = HashSet<String>()

    /** Installed former apps that were merged into [o] (Plain Trekking for Plain Maps…). */
    private fun replacedBy(o: Offer): List<Entry> =
        offers.map { it.entry }.filter { it.mergedInto == o.entry.key && installedCode(it.pkg) != null }

    private fun removeApp(e: Entry) {
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${e.pkg}"))
        try { startActivity(i) } catch (ex: Exception) { say(tr("Can't uninstall: ${ex.message}", "Kaldırılamıyor: ${ex.message}")) }
    }

    private fun row(o: Offer, busy: Boolean): View {
        val e = o.entry
        val st = stateOf(o)
        val pickable = !busy && (st == Catalog.State.NOT_INSTALLED || st == Catalog.State.UPDATE)
        val on = e.pkg in selected && pickable
        val box = text(if (on) "☑" else "☐", 24f, if (pickable) (if (on) C.ACCENT else C.TEXT) else C.FAINT).apply {
            gravity = Gravity.CENTER; setPadding(0, 0, dp(12), 0)
        }
        val title = text(e.name, 17f, C.TEXT, bold = true)
        val ver = when (st) {
            Catalog.State.NOT_INSTALLED -> tr("Not installed · ${e.versionName}", "Yüklü değil · ${e.versionName}")
            Catalog.State.UPDATE -> if (e.pkg in held) tr("Kept at ${installedName(e.pkg) ?: "?"} (rolled back) · ${e.versionName} available",
                    "${installedName(e.pkg) ?: "?"} sürümünde tutuluyor (geri dönüldü) · ${e.versionName} var")
                else tr("Update: ${installedName(e.pkg) ?: "?"} → ${e.versionName}", "Güncelleme: ${installedName(e.pkg) ?: "?"} → ${e.versionName}")
            Catalog.State.UP_TO_DATE -> tr("Installed ${e.versionName} · up to date", "${e.versionName} yüklü · güncel")
            Catalog.State.NEWER_INSTALLED -> tr("Installed ${installedName(e.pkg) ?: ""} (newer)", "${installedName(e.pkg) ?: ""} yüklü (daha yeni)")
            Catalog.State.TOO_OLD_ANDROID -> tr("Needs ${Catalog.androidName(e.minSdk)} or newer", "${Catalog.androidName(e.minSdk)} ya da daha yenisi gerekli")
        } + (if (o.source == Source.ONLINE && pickable) tr(" · download ${Catalog.sizeText(e.size)}", " · indir ${Catalog.sizeText(e.size)}").trimEnd() else "")
        val verColor = when (st) {
            Catalog.State.UPDATE -> C.ACCENT
            Catalog.State.UP_TO_DATE -> C.GREEN
            Catalog.State.TOO_OLD_ANDROID -> C.RED
            else -> C.DIM
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(text(ver, 13f, verColor))
            val into = offers.firstOrNull { it.entry.key == e.mergedInto }?.entry
            if (e.mergedInto.isNotEmpty()) {
                val target = into?.name ?: e.mergedInto
                val targetIn = into != null && installedCode(into.pkg) != null
                addView(text(tr("Now part of $target. " + (if (st == Catalog.State.UPDATE) "Update it once (it hands its data over), then " else "") +
                        (if (targetIn) "open $target: it offers to copy everything from here. Then remove this app." else "install $target: it offers to copy everything from here. Then remove this app."),
                    "Artık $target uygulamasının parçası. " + (if (st == Catalog.State.UPDATE) "Bir kez güncelleyin (verilerini aktarır), sonra " else "") +
                        (if (targetIn) "$target'i açın: buradaki her şeyi kopyalamayı önerir. Sonra bu uygulamayı kaldırın." else "$target'i yükleyin: buradaki her şeyi kopyalamayı önerir. Sonra bu uygulamayı kaldırın.")),
                    13f, C.ACCENT).apply { setPadding(0, dp(4), 0, 0) })
                if (!busy) addView(text(tr("Remove ${e.name}…", "${e.name}'i kaldır…"), 13f, C.ACCENT, bold = true).apply {
                    setPadding(0, dp(8), dp(16), dp(2))
                    setOnClickListener { removeApp(e) }
                }, LinearLayout.LayoutParams(-2, -2))
            } else if (e.summary.isNotEmpty()) addView(text(e.shownSummary, 13f, C.DIM).apply { setPadding(0, dp(4), 0, 0); maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
            replacedBy(o).takeIf { it.isNotEmpty() }?.let { olds ->
                addView(text(tr("Replaces ${olds.joinToString { it.name }}, which you have: its data can be copied over.",
                    "Sizde olan ${olds.joinToString { it.name }} uygulamasının yerini alır: verileri kopyalanabilir."), 13f, C.ACCENT).apply { setPadding(0, dp(4), 0, 0) })
            }
            results[e.pkg]?.let { (ok, msg) ->
                addView(text(msg, 13f, if (ok) C.GREEN else C.RED, bold = true).apply { setPadding(0, dp(4), 0, 0) })
                // A failed or cancelled install is often Play Protect's doing.
                if (!ok && !busy) addView(text(tr("Stopped by Play Protect? See how ›", "Play Protect mi durdurdu? Bakın ›"), 13f, C.ACCENT, bold = true).apply {
                    setPadding(0, dp(6), dp(16), dp(2))
                    setOnClickListener { playProtect() }
                }, LinearLayout.LayoutParams(-2, -2))
            }
            if (!busy && rollbackChoices(o).isNotEmpty())
                addView(text(tr("Older versions…", "Eski sürümler…"), 13f, C.ACCENT, bold = true).apply {
                    setPadding(0, dp(8), dp(16), dp(2))
                    setOnClickListener { showOlder(o) }
                }, LinearLayout.LayoutParams(-2, -2))
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = outline(if (e.pkg == focusPkg) C.ACCENT else C.OUTLINE)
            addView(box)
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            if (pickable) setOnClickListener {
                if (!selected.add(e.pkg)) selected.remove(e.pkg)
                rebuild()
            }
            alpha = if (st == Catalog.State.TOO_OLD_ANDROID) 0.5f else 1f
        }
    }

    // ------------------------------------------------------------------ Play Protect

    /**
     * Google Play Protect warns about (or blocks) apps that don't come from the Play Store. No app may
     * switch it off or whitelist itself, and it has no per-app exception, so this explains the two ways
     * through and opens its settings page. [onContinue] set: shown before the first install, with a
     * button that carries on with it.
     */
    private fun playProtect(onContinue: (() -> Unit)? = null) {
        val msg = tr(
            "Plain apps don't come from the Play Store, so Google Play Protect may stop them with \"Unsafe app blocked\" " +
                "or \"App blocked to protect your device\". They are safe: the source is public on GitHub, and Setup checks " +
                "that every app is signed with the Plain key.\n\n" +
                "Play Protect can't be turned off for only some apps. Two ways through:\n\n" +
                "1. Keep it on (easiest)\n" +
                "When the warning appears, tap \"More details\", then \"Install anyway\". If it asks to send the app for a " +
                "scan, either answer is fine. An update may ask once more.\n\n" +
                "2. Pause it while you install\n" +
                "Tap \"Open Play Protect\" below and turn off \"Scan apps with Play Protect\". Install or update your Plain " +
                "apps here, then go back and turn it on again.\n\n" +
                "Setup can't change this setting for you: Android only lets you do it.",
            "Plain uygulamaları Play Store'dan gelmediği için Google Play Protect onları \"Güvenli olmayan uygulama engellendi\" " +
                "ya da \"Cihazınızı korumak için uygulama engellendi\" diyerek durdurabilir. Uygulamalar güvenlidir: kaynak kodları " +
                "GitHub'da herkese açık ve Setup her uygulamanın Plain anahtarıyla imzalandığını denetler.\n\n" +
                "Play Protect yalnızca bazı uygulamalar için kapatılamaz. İki yol var:\n\n" +
                "1. Açık bırakın (en kolayı)\n" +
                "Uyarı çıkınca \"Diğer ayrıntılar\"a, sonra \"Yine de yükle\"ye dokunun. Uygulamayı taramaya göndermeniz " +
                "istenirse iki yanıt da olur. Bir güncellemede bir kez daha sorabilir.\n\n" +
                "2. Yüklerken duraklatın\n" +
                "Aşağıdaki \"Play Protect'i aç\"a dokunun ve \"Uygulamaları Play Protect ile tara\" seçeneğini kapatın. " +
                "Plain uygulamalarını burada yükleyin ya da güncelleyin, sonra geri dönüp seçeneği yeniden açın.\n\n" +
                "Setup bu ayarı sizin yerinize değiştiremez: Android buna yalnızca sizin izin verir."
        )
        val b = AlertDialog.Builder(this)
            .setTitle("Google Play Protect")
            .setMessage(msg)
            .setNeutralButton(tr("Open Play Protect", "Play Protect'i aç")) { _, _ -> openPlayProtect() }
        if (onContinue != null) {
            b.setPositiveButton(tr("Continue", "Devam et")) { _, _ -> onContinue() }
            b.setNegativeButton(tr("Cancel", "İptal"), null)
        } else {
            b.setPositiveButton(tr("Close", "Kapat"), null)
        }
        b.show()
    }

    /** Play Protect's own settings (Google Play services), else the phone's Security page. */
    private fun openPlayProtect() {
        val tries = listOf(
            Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS"),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (i in tries) {
            try { startActivity(i); return } catch (_: Exception) {}
        }
        say(tr("Open the Play Store app, tap your profile picture, then Play Protect.",
            "Play Store uygulamasını açın, profil resminize, sonra Play Protect'e dokunun."))
    }
}
