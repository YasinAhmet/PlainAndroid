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
            o == null -> say("This app isn't in the Plain catalog.")
            stateOf(o) == Catalog.State.UPDATE -> { selected.clear(); selected += pkg; rebuild(); install() }
            onlineError != null -> say("Couldn't check online: $onlineError")
            else -> say("${o.entry.name} is up to date (${installedName(pkg) ?: o.entry.versionName}).")
        }
    }

    // ------------------------------------------------------------------ install

    private fun canInstall() = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()

    private fun updates() = Catalog.updates(offers, ::installedCode, Build.VERSION.SDK_INT, held)

    /** Bulk update: every installed Plain app with a newer version, whatever is ticked. */
    private fun updateAll() {
        val ups = updates()
        if (ups.isEmpty()) { say("Everything is up to date."); return }
        selected += ups.map { it.entry.pkg }
        install(ups)
    }

    private fun install(only: List<Offer>? = null) {
        val chosen = only ?: offers.filter { it.entry.pkg in selected && stateOf(it).let { s -> s == Catalog.State.NOT_INSTALLED || s == Catalog.State.UPDATE } }
        if (chosen.isEmpty()) { say("Nothing to install: tick an app that isn't installed or has an update."); return }
        if (!canInstall()) {
            waitingPermission = chosen
            AlertDialog.Builder(this)
                .setTitle("Allow installs")
                .setMessage("Android needs your OK once: on the next screen, allow Plain Setup to install apps, then come back.")
                .setPositiveButton("Open") { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                }
                .setNegativeButton("Cancel") { _, _ -> waitingPermission = null }
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
        if (olds.isEmpty()) { say("No older version of ${o.entry.name} to go back to."); return }
        val labels = olds.map {
            it.entry.versionName + if (it.source == Source.LOCAL) "  ·  your previous version (saved on this phone)"
            else Catalog.sizeText(it.entry.size).let { z -> if (z.isEmpty()) "  ·  download" else "  ·  download $z" }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("${o.entry.name}: go back to")
            .setItems(labels) { _, i -> confirmRollback(o, olds[i].entry, olds[i].source) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRollback(o: Offer, old: Entry, src: Source) {
        val now = installedName(o.entry.pkg) ?: "?"
        if (o.entry.pkg == packageName) {
            // Setup can't uninstall itself and carry on; hand over to the release page.
            AlertDialog.Builder(this)
                .setTitle("Plain Setup ${old.versionName}")
                .setMessage("Setup can't replace itself with an older copy. The release page opens: download PlainSetup.apk there, " +
                    "uninstall this Setup, then open the download.")
                .setPositiveButton("Open page") { _, _ ->
                    val page = old.url.substringBefore("/releases/download/") + "/releases/tag/" + old.url.substringAfter("/releases/download/").substringBefore('/')
                    try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(page))) } catch (e: Exception) { say("No browser: ${e.message}") }
                }
                .setNegativeButton("Cancel", null).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Go back to ${o.entry.name} ${old.versionName}?")
            .setMessage("Android can't install an older version over a newer one. Setup will:\n\n" +
                "1. Uninstall ${o.entry.name} $now. Its app data is deleted: settings, saved lists and anything kept inside the app. " +
                "Files in your shared folders (Music, Pictures, Download…) stay.\n" +
                "2. Install ${old.versionName}" + (if (src == Source.LOCAL) " from the copy saved on this phone.\n\n" else " (downloaded and checked).\n\n") +
                "Afterwards ${o.entry.name} stays on ${old.versionName}: Update all skips it until you update it yourself.")
            .setPositiveButton("Uninstall and go back") { _, _ -> startRollback(Offer(old, src)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRollback(old: Offer) {
        if (Installer.busy) { say("Wait for the current install to finish."); return }
        pendingRollback = old
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${old.entry.pkg}")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try { startActivityForResult(i, REQ_UNINSTALL) } catch (e: Exception) { pendingRollback = null; say("Can't uninstall: ${e.message}") }
    }

    @Deprecated("Activity result API needs AndroidX; this app has no libraries.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UNINSTALL) return
        val old = pendingRollback ?: return
        pendingRollback = null
        if (installedCode(old.entry.pkg) != null) { say("Rollback cancelled: ${old.entry.name} is still installed."); rebuild(); return }
        selected.remove(old.entry.pkg)
        if (!canInstall()) { waitingPermission = listOf(old); install(listOf(old)); return }
        begin(listOf(old))
    }

    override fun onStatus(text: String) { setStatus(text, C.ACCENT); skip.visibility = if (text.startsWith("Confirm")) View.VISIBLE else View.GONE }

    override fun onConfirm(confirm: Intent) {
        try { startActivity(confirm) } catch (e: Exception) { say("Can't show the install dialog: ${e.message}") }
    }

    override fun onItemDone(pkg: String, ok: Boolean, message: String) {
        results[pkg] = ok to message
        if (ok) selected.remove(pkg)
        skip.visibility = View.GONE
        rebuild()
    }

    override fun onQueueDone() {
        val fails = results.values.count { !it.first }
        setStatus(if (fails == 0) "Done." else "Done — $fails didn't install (see below).", if (fails == 0) C.GREEN else C.RED)
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
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(8))
            addView(text("Plain Setup", 26f, C.TEXT, bold = true))
            addView(text("Simple, offline-first apps. Tick the ones you want and tap Install. " +
                "Android asks you to confirm each app.", 14f, C.DIM).apply { setPadding(0, dp(4), 0, 0) })
        }
        status = text("", 13f, C.ACCENT).apply { setPadding(dp(20), dp(4), dp(20), dp(4)) }
        skip = text("Skip this app", 13f, C.TEXT, bold = true).apply {
            setPadding(dp(20), dp(2), dp(20), dp(8)); visibility = View.GONE
            setOnClickListener { Installer.skipCurrent(this@SetupActivity) }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), dp(12)) }
        val scroll = ScrollView(this).apply { addView(list) }
        checkBtn = pill("Check for updates", false) { checkOnline() }
        installBtn = pill("Install", true) { install() }
        updateAllBtn = pill("Update all", false) { updateAll() }.apply {
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
        setContentView(root)
    }

    private fun setStatus(s: String, color: Int) { status.text = s; status.setTextColor(color); status.visibility = if (s.isEmpty()) View.GONE else View.VISIBLE }

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
        }
        list.removeAllViews()
        val busy = Installer.busy
        if (offers.isEmpty()) list.addView(text("No app list yet. Connect to the internet and tap Check for updates.", 14f, C.DIM).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) })
        for (o in offers) list.addView(row(o, busy))

        val n = offers.count { it.entry.pkg in selected && stateOf(it).let { s -> s == Catalog.State.NOT_INSTALLED || s == Catalog.State.UPDATE } }
        installBtn.text = if (busy) "Installing…" else if (n == 0) "Install" else "Install $n"
        installBtn.isEnabled = !busy && n > 0
        installBtn.alpha = if (installBtn.isEnabled) 1f else 0.4f
        val ups = if (busy) 0 else updates().size
        updateAllBtn.text = if (ups == 1) "Update all (1 app)" else "Update all ($ups apps)"
        updateAllBtn.visibility = if (ups > 0) View.VISIBLE else View.GONE
        checkBtn.text = if (checking) "Checking…" else "Check for updates"
        checkBtn.isEnabled = !checking && !busy

        if (!busy && status.text.isNullOrEmpty() || status.text.startsWith("Checked") || status.text.startsWith("Offline")) {
            when {
                checking -> setStatus("", C.DIM)
                onlineError != null -> setStatus("Offline: showing the apps inside Setup ($onlineError).", C.FAINT)
                online != null -> setStatus("Checked online: newest versions shown.", C.FAINT)
            }
        }
        list.addView(text("Setup ${installedName(packageName) ?: ""} · apps from github.com/YasinAhmet/PlainAndroid. " +
            "To remove apps, use Plain Uninstaller.", 12f, C.FAINT).apply { setPadding(dp(8), dp(12), dp(8), 0) })
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
            Catalog.State.NOT_INSTALLED -> "Not installed · ${e.versionName}"
            Catalog.State.UPDATE -> if (e.pkg in held) "Kept at ${installedName(e.pkg) ?: "?"} (rolled back) · ${e.versionName} available"
                else "Update: ${installedName(e.pkg) ?: "?"} → ${e.versionName}"
            Catalog.State.UP_TO_DATE -> "Installed ${e.versionName} · up to date"
            Catalog.State.NEWER_INSTALLED -> "Installed ${installedName(e.pkg) ?: ""} (newer)"
            Catalog.State.TOO_OLD_ANDROID -> "Needs ${Catalog.androidName(e.minSdk)} or newer"
        } + (if (o.source == Source.ONLINE && pickable) " · download ${Catalog.sizeText(e.size)}".trimEnd() else "")
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
            if (e.summary.isNotEmpty()) addView(text(e.summary, 13f, C.DIM).apply { setPadding(0, dp(4), 0, 0); maxLines = 4; ellipsize = TextUtils.TruncateAt.END })
            results[e.pkg]?.let { (ok, msg) -> addView(text(msg, 13f, if (ok) C.GREEN else C.RED, bold = true).apply { setPadding(0, dp(4), 0, 0) }) }
            if (!busy && rollbackChoices(o).isNotEmpty())
                addView(text("Older versions…", 13f, C.ACCENT, bold = true).apply {
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
}
