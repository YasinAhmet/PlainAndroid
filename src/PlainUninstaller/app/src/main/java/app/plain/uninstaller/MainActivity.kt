package app.plain.uninstaller

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import app.plain.update.PlainUpdate
import rikka.shizuku.Shizuku
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Plain Uninstaller: one screen with three tabs.
 *  - Apps: filter, search, multi-select, uninstall (system dialog one by one, or silently via Shizuku).
 *  - Restore: system packages removed for user 0, put back with `cmd package install-existing`.
 *  - Setup: live checklist that gets Shizuku running, re-checked in onResume.
 * Nothing runs while the app is closed: the worker threads die with the activity.
 */
class MainActivity : Activity() {

    private enum class Tab { APPS, RESTORE, SETUP }
    private enum class Filter(val label: String) { USER("User apps"), SYSTEM("System apps"), PLAIN("Plain apps") }

    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val iconWorker: ExecutorService = Executors.newFixedThreadPool(2)
    private var destroyed = false

    // Apps tab state
    private var defaults = Defaults()
    private var apps: List<AppItem> = emptyList()
    private var visible: List<AppItem> = emptyList()
    private var filter = Filter.USER
    private var query = ""
    private val selected = LinkedHashSet<String>()
    private var loading = true
    private val icons = HashMap<String, Drawable>()
    private val iconPending = HashSet<String>()

    // Restore tab state
    private var restoreItems: List<RestoreItem> = emptyList()
    private val restoreSelected = LinkedHashSet<String>()
    private var restoreLoading = false
    private var restoreFromShizuku = false

    // Uninstall via the system dialog, one app at a time
    private val queue = ArrayDeque<Target>()
    private var current: Target? = null
    private val dialogResults = ArrayList<Pair<Target, String?>>()
    private var busy = false

    private var tab = Tab.APPS
    private var shizuku = Shiz.State.NOT_INSTALLED

    // Views
    private lateinit var root: LinearLayout
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var pages: Map<Tab, View>
    private lateinit var appsStatus: TextView
    private lateinit var chips: Map<Filter, TextView>
    private lateinit var search: EditText
    private lateinit var countText: TextView
    private lateinit var appList: ListView
    private lateinit var appsEmpty: TextView
    private lateinit var uninstallBtn: TextView
    private lateinit var restoreStatus: TextView
    private lateinit var restoreList: ListView
    private lateinit var restoreEmpty: TextView
    private lateinit var restoreBtn: TextView
    private lateinit var setupBox: LinearLayout

    private val appAdapter = AppAdapter()
    private val restoreAdapter = RestoreAdapter()

    private val onBinder = Shizuku.OnBinderReceivedListener { main.post { refreshShizuku() } }
    private val onBinderDead = Shizuku.OnBinderDeadListener { main.post { refreshShizuku() } }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> main.post { refreshShizuku(); loadRestore() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(C.BG))
        if (Build.VERSION.SDK_INT >= 30) {
            @Suppress("DEPRECATION") // edge-to-edge is the default from Android 15; this covers 11-14
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
        buildUi()
        try {
            Shizuku.addBinderReceivedListenerSticky(onBinder)
            Shizuku.addBinderDeadListener(onBinderDead)
            Shizuku.addRequestPermissionResultListener(onPermission)
        } catch (_: Throwable) {
        }
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshShizuku()
        // Apps may have been removed or added elsewhere while we were away.
        if (!busy && !loading) loadApps()
    }

    override fun onDestroy() {
        destroyed = true
        try {
            Shizuku.removeBinderReceivedListener(onBinder)
            Shizuku.removeBinderDeadListener(onBinderDead)
            Shizuku.removeRequestPermissionResultListener(onPermission)
        } catch (_: Throwable) {
        }
        worker.shutdownNow()
        iconWorker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != Tab.APPS) {
            showTab(Tab.APPS)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = vbox().apply { setBackgroundColor(C.BG) }

        // 1. Always-visible warning.
        val banner = text(
            "Use at your own risk. Removing the wrong system app can break calls, the home screen or updates. " +
                "Removed system apps can be restored from the Restore tab.",
            13f, C.RED, lines = 0,
        ).apply {
            background = outline(dpf(10), C.RED)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(banner, lpL(MATCH, WRAP).margins(dp(12), dp(8), dp(12), dp(4)))

        // Toolbar
        val bar = hbox().apply { setPadding(dp(16), dp(4), dp(4), 0) }
        bar.addView(text("Plain Uninstaller", 20f, C.TEXT, bold = true), lpL(0, WRAP, 1f))
        bar.addView(toolButton("?", "How to declutter") { showHelp() })
        bar.addView(toolButton("⋮", "Menu") { showMenu() })
        root.addView(bar, lpL(MATCH, WRAP))

        // Tabs
        val tabs = hbox().apply { setPadding(dp(12), dp(4), dp(12), dp(4)) }
        val tv = LinkedHashMap<Tab, TextView>()
        for ((t, label) in listOf(Tab.APPS to "Apps", Tab.RESTORE to "Restore", Tab.SETUP to "Setup")) {
            val v = text(label, 15f, C.DIM, bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { showTab(t) }
            }
            tv[t] = v
            tabs.addView(v, lpL(0, WRAP, 1f))
        }
        tabViews = tv
        root.addView(tabs, lpL(MATCH, WRAP))
        root.addView(View(this).apply { setBackgroundColor(C.OUTLINE) }, lpL(MATCH, dp(1)))

        val content = FrameLayout(this)
        pages = mapOf(Tab.APPS to buildAppsPage(), Tab.RESTORE to buildRestorePage(), Tab.SETUP to buildSetupPage())
        pages.values.forEach { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        root.addView(content, lpL(MATCH, 0, 1f))

        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
        showTab(Tab.APPS)
    }

    private fun toolButton(label: String, desc: String, onClick: () -> Unit) = text(label, 20f, C.DIM, bold = true).apply {
        gravity = Gravity.CENTER
        contentDescription = desc
        background = ripple(null)
        setOnClickListener { onClick() }
        layoutParams = lpL(dp(48), dp(48))
    }

    private fun bottomButton(label: String, onClick: () -> Unit) = pill(label, C.TEXT, C.OUTLINE_BTN) { onClick() }.apply {
        textSize = 16f
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun buildAppsPage(): View {
        val page = vbox(12, 8)

        appsStatus = text("", 13f, C.DIM, lines = 0).apply {
            setPadding(dp(4), 0, dp(4), dp(6))
            setOnClickListener { showTab(Tab.SETUP) }
        }
        page.addView(appsStatus, lpL(MATCH, WRAP))

        val chipRow = hbox()
        val cm = LinkedHashMap<Filter, TextView>()
        for (f in Filter.values()) {
            val c = chip(f.label) { setFilter(f) }
            cm[f] = c
            chipRow.addView(c, lpL(WRAP, WRAP).margins(r = dp(8)))
        }
        chips = cm
        page.addView(chipRow, lpL(MATCH, WRAP))

        search = EditText(this).apply {
            hint = "Search apps or package names"
            textSize = 14f
            setTextColor(C.TEXT)
            setHintTextColor(C.LABEL)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = outline(dpf(20), C.OUTLINE_BTN)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refilter() }
            })
            setOnEditorActionListener { v, _, _ ->
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0); true
            }
        }
        page.addView(search, lpL(MATCH, WRAP).margins(t = dp(8), b = dp(6)))

        val selRow = hbox()
        countText = text("", 13f, C.DIM)
        selRow.addView(countText, lpL(0, WRAP, 1f))
        selRow.addView(pill("Select all visible") { selectAllVisible() }.apply { textSize = 13f }, lpL(WRAP, WRAP).margins(r = dp(6)))
        selRow.addView(pill("Clear") { selected.clear(); updateSelection() }.apply { textSize = 13f })
        page.addView(selRow, lpL(MATCH, WRAP).margins(b = dp(4)))

        val listBox = FrameLayout(this)
        appList = listView(appAdapter) { pos -> visible.getOrNull(pos)?.let { toggle(it.pkg) } }
        appsEmpty = text("Loading apps…", 14f, C.LABEL, lines = 0).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        listBox.addView(appList, FrameLayout.LayoutParams(MATCH, MATCH))
        listBox.addView(appsEmpty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        page.addView(listBox, lpL(MATCH, 0, 1f))

        uninstallBtn = bottomButton("Uninstall 0 selected") { startUninstall() }
        page.addView(uninstallBtn, lpL(MATCH, WRAP).margins(t = dp(6)))
        return page
    }

    private fun buildRestorePage(): View {
        val page = vbox(12, 8)
        restoreStatus = text("", 13f, C.DIM, lines = 0).apply { setPadding(dp(4), 0, dp(4), dp(6)) }
        page.addView(restoreStatus, lpL(MATCH, WRAP))

        val row = hbox()
        row.addView(View(this), lpL(0, 1, 1f))
        row.addView(pill("Refresh") { loadRestore() }.apply { textSize = 13f }, lpL(WRAP, WRAP).margins(r = dp(6)))
        row.addView(pill("Select all") { restoreSelected.addAll(restoreItems.map { it.pkg }); updateRestoreSelection() }
            .apply { textSize = 13f }, lpL(WRAP, WRAP).margins(r = dp(6)))
        row.addView(pill("Clear") { restoreSelected.clear(); updateRestoreSelection() }.apply { textSize = 13f })
        page.addView(row, lpL(MATCH, WRAP).margins(b = dp(4)))

        val listBox = FrameLayout(this)
        restoreList = listView(restoreAdapter) { pos ->
            restoreItems.getOrNull(pos)?.let { if (!restoreSelected.remove(it.pkg)) restoreSelected.add(it.pkg); updateRestoreSelection() }
        }
        restoreEmpty = text("", 14f, C.LABEL, lines = 0).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        listBox.addView(restoreList, FrameLayout.LayoutParams(MATCH, MATCH))
        listBox.addView(restoreEmpty, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        page.addView(listBox, lpL(MATCH, 0, 1f))

        restoreBtn = bottomButton("Restore 0 selected") { startRestore() }
        page.addView(restoreBtn, lpL(MATCH, WRAP).margins(t = dp(6)))
        return page
    }

    private fun buildSetupPage(): View {
        setupBox = vbox(12, 8)
        return scrollOf(setupBox)
    }

    private fun listView(adapter: BaseAdapter, onClick: (Int) -> Unit) = ListView(this).apply {
        this.adapter = adapter
        divider = null
        selector = ColorDrawable(0)
        isVerticalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_NEVER
        setOnItemClickListener { _, _, pos, _ -> onClick(pos) }
    }

    private fun showTab(t: Tab) {
        tab = t
        tabViews.forEach { (k, v) -> v.setTextColor(if (k == t) C.ACCENT else C.DIM) }
        pages.forEach { (k, v) -> v.visibility = if (k == t) View.VISIBLE else View.GONE }
        if (t != Tab.APPS) hideKeyboard()
        when (t) {
            Tab.RESTORE -> loadRestore()
            Tab.SETUP -> renderSetup()
            Tab.APPS -> {}
        }
    }

    private fun hideKeyboard() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
    }

    private fun showMenu() {
        menu(null, listOf(
            "How to declutter" to { showHelp() },
            "Setup for system apps" to { showTab(Tab.SETUP) },
            "Restore removed apps" to { showTab(Tab.RESTORE) },
            PlainUpdate.label(this) to { PlainUpdate.check(this) },
        ))
    }

    private fun showHelp() {
        longMessage(
            "How to declutter",
            "1. Start with apps you recognise: games, trials, shopping, a second browser, the maker's own " +
                "store or \"tips\" apps you never open.\n\n" +
                "2. Leave anything you're unsure about. A name you don't recognise is often a part of the phone " +
                "(camera, SIM, calls, fingerprint). Protected parts are hidden from the list altogether.\n\n" +
                "3. Google Play services and the Play Store are marked Risky: without them many apps, notifications " +
                "and updates stop working.\n\n" +
                "4. Removed a system app by mistake? Open the Restore tab, select it and tap Restore. " +
                "Without Shizuku, the tab shows the adb commands to run from a PC.\n\n" +
                "5. A factory reset brings every system app back by itself, so there's nothing to restore after one.\n\n" +
                "User apps can be removed straight away. System apps need Shizuku: see the Setup tab.",
        ).setPositiveButton("OK", null).show()
    }

    // ---------------------------------------------------------------- Apps tab

    private fun loadApps() {
        loading = true
        if (apps.isEmpty()) appsEmpty.text = "Loading apps…"
        worker.execute {
            val d = Apps.defaults(this)
            val list = Apps.load(this, d)
            main.post {
                if (destroyed) return@post
                defaults = d
                apps = list
                loading = false
                val present = list.mapTo(HashSet()) { it.pkg }
                selected.retainAll(present)
                refilter()
            }
        }
    }

    private fun setFilter(f: Filter) {
        filter = f
        refilter()
        appList.setSelection(0)
    }

    private fun matchesFilter(a: AppItem) = when (filter) {
        Filter.USER -> !a.system
        Filter.SYSTEM -> a.system
        Filter.PLAIN -> Safety.isPlain(a.pkg)
    }

    private fun refilter() {
        chips.forEach { (f, v) -> v.setChip(f == filter) }
        val q = query.lowercase()
        visible = apps.filter { matchesFilter(it) && (q.isEmpty() || q in it.label.lowercase() || q in it.pkg.lowercase()) }
        appsEmpty.visibility = if (visible.isEmpty()) View.VISIBLE else View.GONE
        if (!loading && visible.isEmpty()) {
            appsEmpty.text = if (query.isNotEmpty()) "No apps match \"$query\"." else when (filter) {
                Filter.PLAIN -> "No Plain apps installed."
                else -> "Nothing here."
            }
        }
        appAdapter.notifyDataSetChanged()
        updateSelection()
    }

    private fun toggle(pkg: String) {
        if (!selected.remove(pkg)) selected.add(pkg)
        updateSelection()
    }

    private fun selectAllVisible() {
        visible.forEach { selected.add(it.pkg) }
        updateSelection()
    }

    private fun updateSelection() {
        val shownSel = visible.count { it.pkg in selected }
        countText.text = if (selected.size == shownSel) "${visible.size} shown · ${selected.size} selected"
        else "${visible.size} shown · ${selected.size} selected (${selected.size - shownSel} hidden by filter)"
        uninstallBtn.text = "Uninstall ${selected.size} selected"
        uninstallBtn.setTextColor(if (selected.isEmpty()) C.LABEL else C.TEXT)
        uninstallBtn.background = ripple(outline(dpf(20), if (selected.isEmpty()) C.OUTLINE_BTN else C.RED))
        appAdapter.notifyDataSetChanged()
    }

    private fun iconFor(pkg: String, into: ImageView) {
        into.tag = pkg
        val cached = icons[pkg]
        if (cached != null) { into.setImageDrawable(cached); return }
        into.setImageDrawable(null)
        if (!iconPending.add(pkg)) return
        iconWorker.execute {
            val d = try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { packageManager.defaultActivityIcon }
            main.post {
                if (destroyed) return@post
                iconPending.remove(pkg)
                icons[pkg] = d
                if (tab == Tab.APPS) appAdapter.notifyDataSetChanged()
                if (tab == Tab.RESTORE) restoreAdapter.notifyDataSetChanged()
            }
        }
    }

    private class RowHolder(val icon: ImageView, val label: TextView, val pkg: TextView, val tag: TextView, val check: CheckBox)

    private fun makeRow(parent: ViewGroup): View {
        val row = hbox().apply {
            setPadding(dp(8), dp(8), dp(4), dp(8))
            background = ripple(null)
            minimumHeight = dp(60)
        }
        val icon = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        row.addView(icon, lpL(dp(40), dp(40)).margins(r = dp(12)))
        val texts = vbox()
        val label = text("", 15f, C.TEXT)
        val pkg = text("", 11f, C.LABEL)
        texts.addView(label)
        texts.addView(pkg)
        row.addView(texts, lpL(0, WRAP, 1f))
        val tag = tag("Risky", C.ORANGE)
        row.addView(tag, lpL(WRAP, WRAP).margins(l = dp(6)))
        val check = checkBox()
        row.addView(check, lpL(WRAP, WRAP))
        row.tag = RowHolder(icon, label, pkg, tag, check)
        row.layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
        return row
    }

    private inner class AppAdapter : BaseAdapter() {
        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: makeRow(parent)
            val h = v.tag as RowHolder
            val a = visible[position]
            h.label.text = a.label
            h.pkg.text = if (a.system) "${a.pkg} · system" else a.pkg
            h.tag.text = "Risky"
            h.tag.setTextColor(C.ORANGE)
            h.tag.background = outline(dpf(8), C.ORANGE)
            h.tag.visibility = if (a.level == Level.RISKY) View.VISIBLE else View.GONE
            h.check.isChecked = a.pkg in selected
            iconFor(a.pkg, h.icon)
            return v
        }
    }

    // ---------------------------------------------------------------- Uninstall

    private fun targetsInOrder(): List<Target> {
        val byPkg = apps.associateBy { it.pkg }
        return selected.mapNotNull { p -> byPkg[p]?.let { Target(it.pkg, it.label, it.system, it.level == Level.RISKY) } }
    }

    private fun startUninstall() {
        if (busy) return
        val targets = targetsInOrder()
        if (targets.isEmpty()) { toast("Select apps first"); return }
        val ready = shizuku == Shiz.State.READY
        val sys = Plan.needShizuku(targets)
        if (!ready && sys.isNotEmpty()) {
            val users = Plan.dialogQueue(targets)
            val b = longMessage(
                "Shizuku needed for system apps",
                "${sys.size} of the selected apps are system apps. Android only lets them be removed through " +
                    "Shizuku (a free helper app, set up once over wireless debugging).\n\n" +
                    sys.joinToString("\n") { "• ${it.label}" } +
                    if (users.isEmpty()) "" else "\n\nThe ${users.size} user apps can be removed now, one by one, with Android's own uninstall dialog.",
            )
            b.setPositiveButton("Set up Shizuku") { _, _ -> showTab(Tab.SETUP) }
            if (users.isNotEmpty()) b.setNeutralButton("Remove ${users.size} user apps") { _, _ -> confirmAndRun(users, false) }
            b.setNegativeButton("Cancel", null).show()
            return
        }
        confirmAndRun(targets, ready)
    }

    private fun confirmAndRun(targets: List<Target>, viaShizuku: Boolean) {
        val how = if (viaShizuku) "They will be removed silently through Shizuku." +
            (if (Plan.needShizuku(targets).isNotEmpty()) " System apps are removed for this user only and can be restored from the Restore tab." else "")
        else "Android will ask you to confirm each one."
        longMessage("Uninstall ${targets.size} apps?", Plan.describe(targets) + "\n\n" + how)
            .setPositiveButton("Uninstall") { _, _ -> confirmRisky(targets, viaShizuku) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun confirmRisky(targets: List<Target>, viaShizuku: Boolean) {
        val risky = Plan.risky(targets)
        if (risky.isEmpty()) { run(targets, viaShizuku); return }
        val msg = risky.joinToString("\n\n") { t -> "• ${t.label}\n   ${Safety.riskReason(t.pkg, defaults) ?: t.pkg}" } +
            "\n\nOnly continue if you know you don't need these."
        longMessage("Remove ${risky.size} risky apps?", msg)
            .setPositiveButton("Remove anyway") { _, _ -> run(targets, viaShizuku) }
            .setNeutralButton("Skip risky") { _, _ ->
                val safe = targets.filterNot { it.risky }
                if (safe.isNotEmpty()) run(safe, viaShizuku)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun run(targets: List<Target>, viaShizuku: Boolean) {
        if (viaShizuku) runShizuku(targets) else runDialogs(targets)
    }

    /** Path b: Shizuku, silent, in the background, with a progress dialog and a per-app report. */
    private fun runShizuku(targets: List<Target>) {
        busy = true
        val (dialog, status, bar) = progressDialog("Uninstalling", targets.size)
        worker.execute {
            val results = ArrayList<Pair<Target, String?>>()
            for ((i, pair) in Plan.shizukuCommands(targets).withIndex()) {
                if (destroyed) break
                val (t, cmd) = pair
                main.post { if (!destroyed) { status.text = "${i + 1} of ${targets.size}: ${t.label}"; bar.progress = i } }
                val r = Shiz.run(cmd)
                val ok = Commands.uninstallOk(r)
                if (ok && t.system) Apps.appendLog(this, LogEntry(System.currentTimeMillis(), LogEntry.REMOVED, t.pkg, t.label))
                results.add(t to if (ok) null else r.message())
            }
            main.post {
                if (destroyed) return@post
                try { dialog.dismiss() } catch (_: Exception) {}
                busy = false
                finishBatch("Uninstall", results)
            }
        }
    }

    /** Path a: Android's own uninstall dialog, one app at a time, in selection order. */
    private fun runDialogs(targets: List<Target>) {
        busy = true
        queue.clear(); queue.addAll(targets)
        dialogResults.clear()
        nextDialog()
    }

    private fun nextDialog() {
        val t = queue.removeFirstOrNull()
        current = t
        if (t == null) {
            busy = false
            finishBatch("Uninstall", ArrayList(dialogResults))
            return
        }
        @Suppress("DEPRECATION")
        val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${t.pkg}"))
            .putExtra(Intent.EXTRA_RETURN_RESULT, true)
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_UNINSTALL)
        } catch (e: Exception) {
            dialogResults.add(t to "Could not open the uninstall dialog")
            nextDialog()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_UNINSTALL) return
        val t = current ?: return
        // The result code isn't reliable on every ROM: check whether the app is really gone.
        val gone = !Apps.isInstalled(this, t.pkg)
        dialogResults.add(t to when {
            gone -> null
            resultCode == RESULT_CANCELED -> "Cancelled"
            else -> "Not removed"
        })
        // Let the uninstall activity finish closing before opening the next one.
        main.postDelayed({ if (!destroyed) nextDialog() }, 300)
    }

    private fun finishBatch(what: String, results: List<Pair<Target, String?>>) {
        val ok = results.count { it.second == null }
        val lines = results.joinToString("\n") { (t, err) -> if (err == null) "✓ ${t.label}" else "✗ ${t.label} — $err" }
        longMessage("$what: $ok of ${results.size} done", lines.ifEmpty { "Nothing was done." })
            .setPositiveButton("OK", null).show()
        selected.removeAll(results.filter { it.second == null }.map { it.first.pkg }.toSet())
        loadApps()
    }

    private fun progressDialog(title: String, max: Int): Triple<AlertDialog, TextView, ProgressBar> {
        val box = vbox(24, 12)
        val status = text("Starting…", 14f, C.TEXT, lines = 2)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { this.max = max; progress = 0 }
        box.addView(status, lpL(MATCH, WRAP))
        box.addView(bar, lpL(MATCH, WRAP).margins(t = dp(12)))
        val d = AlertDialog.Builder(this).setTitle(title).setView(box).setCancelable(false).show()
        return Triple(d, status, bar)
    }

    // ---------------------------------------------------------------- Restore tab

    private fun loadRestore() {
        if (restoreLoading || busy) return
        restoreLoading = true
        val ready = shizuku == Shiz.State.READY
        restoreStatus.text = "Looking for removed system apps…"
        worker.execute {
            val log = RemovedLog.currentlyRemoved(Apps.readLog(this))
            var fromShizuku = false
            var sets: Pair<Set<String>, Set<String>>? = null
            if (ready) {
                val all = Shiz.run(Commands.LIST_ALL)
                val inst = Shiz.run(Commands.LIST_INSTALLED)
                val a = PmOutput.parsePackages(all.out)
                val i = PmOutput.parsePackages(inst.out)
                if (all.exit == 0 && inst.exit == 0 && i.isNotEmpty()) { sets = a to i; fromShizuku = true }
            }
            val (all, installed) = sets ?: Apps.packageSetsFromPm(this)
            val items = RestoreDiff.candidates(all, installed, log.keys).map {
                RestoreItem(it.pkg, Apps.labelOf(this, it.pkg, log[it.pkg]?.label), it.removedByThisApp)
            }.sortedWith(compareBy<RestoreItem> { !it.removedByThisApp }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.label })
            main.post {
                if (destroyed) return@post
                restoreLoading = false
                restoreItems = items
                restoreFromShizuku = fromShizuku
                restoreSelected.retainAll(items.mapTo(HashSet()) { it.pkg })
                updateRestoreSelection()
            }
        }
    }

    private fun updateRestoreSelection() {
        val ready = shizuku == Shiz.State.READY
        restoreStatus.text = when {
            restoreLoading -> "Looking for removed system apps…"
            ready -> "System apps removed for this user. They are still on the phone and come back with their data. " +
                "Apps removed with this app are listed first."
            else -> "Shizuku isn't ready, so this list comes from Android itself and restoring is done from a PC: " +
                "select apps and tap the button to get the adb commands. Or finish the Setup tab to restore here."
        }
        restoreEmpty.visibility = if (restoreItems.isEmpty()) View.VISIBLE else View.GONE
        restoreEmpty.text = if (restoreLoading) "" else "No removed system apps found."
        restoreBtn.text = if (ready) "Restore ${restoreSelected.size} selected" else "Show adb commands for ${restoreSelected.size}"
        restoreBtn.setTextColor(if (restoreSelected.isEmpty()) C.LABEL else C.TEXT)
        restoreBtn.background = ripple(outline(dpf(20), if (restoreSelected.isEmpty()) C.OUTLINE_BTN else C.GREEN))
        restoreAdapter.notifyDataSetChanged()
    }

    private inner class RestoreAdapter : BaseAdapter() {
        override fun getCount() = restoreItems.size
        override fun getItem(position: Int) = restoreItems[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: makeRow(parent)
            val h = v.tag as RowHolder
            val r = restoreItems[position]
            h.label.text = r.label
            h.pkg.text = r.pkg
            h.tag.text = "Removed here"
            h.tag.setTextColor(C.DIM)
            h.tag.background = outline(dpf(8), C.OUTLINE_BTN)
            h.tag.visibility = if (r.removedByThisApp) View.VISIBLE else View.GONE
            h.check.isChecked = r.pkg in restoreSelected
            iconFor(r.pkg, h.icon)
            return v
        }
    }

    private fun startRestore() {
        if (busy) return
        val chosen = restoreItems.filter { it.pkg in restoreSelected }
        if (chosen.isEmpty()) { toast("Select apps first"); return }
        if (shizuku != Shiz.State.READY) { showAdbCommands(chosen); return }
        busy = true
        val (dialog, status, bar) = progressDialog("Restoring", chosen.size)
        worker.execute {
            val results = ArrayList<Pair<Target, String?>>()
            for ((i, r) in chosen.withIndex()) {
                if (destroyed) break
                main.post { if (!destroyed) { status.text = "${i + 1} of ${chosen.size}: ${r.label}"; bar.progress = i } }
                val res = Shiz.run(Commands.restore(r.pkg))
                val ok = Commands.restoreOk(res)
                if (ok) Apps.appendLog(this, LogEntry(System.currentTimeMillis(), LogEntry.RESTORED, r.pkg, r.label))
                results.add(Target(r.pkg, r.label, true, false) to if (ok) null else res.message())
            }
            main.post {
                if (destroyed) return@post
                try { dialog.dismiss() } catch (_: Exception) {}
                busy = false
                restoreSelected.removeAll(results.filter { it.second == null }.map { it.first.pkg }.toSet())
                finishBatch("Restore", results)
                loadRestore()
            }
        }
    }

    private fun showAdbCommands(items: List<RestoreItem>) {
        val cmds = items.joinToString("\n") { Commands.adb(Commands.restore(it.pkg)) }
        val msg = "On a PC with Android platform-tools: turn on USB debugging on this phone, connect it by cable, " +
            "allow the PC when asked, then run:\n\n$cmds"
        longMessage("Restore from a PC", msg)
            .setPositiveButton("Copy commands") { _, _ ->
                val cb = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cb.setPrimaryClip(ClipData.newPlainText("adb commands", cmds))
                toast("Copied")
            }
            .setNegativeButton("Close", null).show()
    }

    // ---------------------------------------------------------------- Setup tab

    private fun refreshShizuku() {
        if (destroyed) return
        shizuku = Shiz.state(this)
        appsStatus.text = when (shizuku) {
            Shiz.State.READY -> "Shizuku ready: user and system apps are removed silently."
            Shiz.State.NOT_INSTALLED, Shiz.State.NOT_RUNNING ->
                "Without Shizuku: user apps only, each through Android's uninstall dialog. Tap here to set up system apps."
            Shiz.State.NO_PERMISSION, Shiz.State.DENIED -> "Shizuku is running but hasn't allowed Plain Uninstaller yet. Tap here."
            Shiz.State.TOO_OLD -> "Shizuku is too old (before v11). Update it to remove system apps. Tap here."
        }
        appsStatus.setTextColor(if (shizuku == Shiz.State.READY) C.GREEN else C.DIM)
        if (tab == Tab.SETUP) renderSetup()
        if (tab == Tab.RESTORE && !restoreLoading) updateRestoreSelection()
        if (tab == Tab.RESTORE && shizuku == Shiz.State.READY && !restoreFromShizuku) loadRestore()
    }

    private fun devOptionsOn(): Boolean = try {
        Settings.Global.getInt(contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
    } catch (_: Exception) { false }

    /** Wireless debugging switch (Android 11+). Not every ROM lets apps read it: null = unknown. */
    private fun wirelessDebuggingOn(): Boolean? = try {
        if (Build.VERSION.SDK_INT < 30) null else Settings.Global.getInt(contentResolver, "adb_wifi_enabled") == 1
    } catch (_: Exception) { null }

    private fun wifiConnected(): Boolean? = try {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    } catch (_: Exception) { null }

    private fun renderSetup() {
        val box = setupBox
        box.removeAllViews()
        val s = shizuku
        val installed = Shiz.installed(this) || Shiz.running()
        val dev = devOptionsOn()
        val running = Shiz.running()
        val wireless = wirelessDebuggingOn()
        val wifi = wifiConnected()
        val ready = s == Shiz.State.READY

        box.addView(text(
            if (ready) "Ready: system apps can be removed" else "Setup for system apps",
            18f, if (ready) C.GREEN else C.TEXT, bold = true, lines = 0,
        ), lpL(MATCH, WRAP).margins(l = dp(4), t = dp(4)))
        box.addView(text(
            if (ready) "Shizuku ${Shiz.versionText() ?: ""} is running and Plain Uninstaller is allowed to use it."
            else "User apps can always be removed. System apps need Shizuku, a free app that runs commands with " +
                "the same rights as a PC connected over USB. Work through the steps; each one ticks itself off " +
                "when you come back here.",
            13f, C.DIM, lines = 0,
        ), lpL(MATCH, WRAP).margins(l = dp(4), t = dp(4), b = dp(8)))

        // 1. Shizuku installed
        step(box, 1, "Shizuku installed?", installed,
            if (installed) "Shizuku is installed." else "Install Shizuku from the Play Store (or its website if there is no Play Store on this phone), then come back.",
        ) { if (!installed) it.addView(stepButton("Get Shizuku") { openShizukuDownload() }) }

        // 2. Developer options
        step(box, 2, "Developer options on?", dev,
            if (dev) "Developer options are on."
            else "Open About phone and tap \"Build number\" 7 times (on some phones it is under Software information). " +
                "Enter your PIN if asked, then come back.",
        ) { if (!dev) it.addView(stepButton("Open About phone") { openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS) }) }

        // 3. Wireless debugging
        val wDone = running || wireless == true
        val wifiLine = when (wifi) { true -> "Wi-Fi is connected."; false -> "Wi-Fi is NOT connected: connect to any Wi-Fi first (no internet needed)."; null -> "" }
        val wText = if (Build.VERSION.SDK_INT >= 30) {
            (if (running) "Done: Shizuku is already running.\n\n" else "") +
                "Wireless debugging needs Wi-Fi to be connected. In Developer options, turn on \"Wireless debugging\" " +
                "and confirm. Leave the screen open for the next step.\n$wifiLine"
        } else {
            "This phone runs Android ${Build.VERSION.RELEASE}, which has no wireless debugging. Shizuku has to be started " +
                "once from a PC over USB: turn on \"USB debugging\" in Developer options, connect the cable and run the " +
                "command shown in the Shizuku app (\"Start via connected computer\"), usually:\n" +
                "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh"
        }
        step(box, 3, if (Build.VERSION.SDK_INT >= 30) "Wireless debugging" else "USB debugging (Android 10 and older)", wDone, wText) {
            it.addView(stepButton("Open Developer options") { openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) })
        }

        // 4. Start Shizuku
        val startText = if (running) "Shizuku is running." else if (Build.VERSION.SDK_INT >= 30) {
            "Open Shizuku. The first time, tap \"Pairing\", then in Developer options > Wireless debugging tap " +
                "\"Pair device with pairing code\" and type that code into Shizuku's notification. Then tap \"Start\" in Shizuku.\n\n" +
                "After a reboot, pairing isn't needed again: turn Wireless debugging back on and tap \"Start\" in Shizuku."
        } else {
            "Open Shizuku and start it with the PC command from step 3. After a reboot it has to be started again the same way."
        }
        step(box, 4, "Start Shizuku", running, startText) {
            if (!running && installed) it.addView(stepButton("Open Shizuku") { openShizuku() })
        }

        // 5. Permission
        val allowed = ready
        val permText = when (s) {
            Shiz.State.READY -> "Plain Uninstaller is allowed."
            Shiz.State.DENIED -> "Permission was denied. Open Shizuku > Authorized applications and switch Plain Uninstaller on."
            Shiz.State.TOO_OLD -> "This Shizuku is older than v11 and can't be used. Update it (step 1)."
            Shiz.State.NO_PERMISSION -> "Tap Allow, then choose \"Allow all the time\" in Shizuku's dialog."
            else -> "Available once Shizuku is running."
        }
        step(box, 5, "Allow Plain Uninstaller", allowed, permText) {
            when (s) {
                Shiz.State.NO_PERMISSION -> it.addView(stepButton("Allow") {
                    if (!Shiz.requestPermission()) toast("Shizuku couldn't ask. Open Shizuku and allow it there.")
                })
                Shiz.State.DENIED -> it.addView(stepButton("Open Shizuku") { openShizuku() })
                Shiz.State.TOO_OLD -> it.addView(stepButton("Update Shizuku") { openShizukuDownload() })
                else -> {}
            }
        }

        if (ready) {
            box.addView(text("Ready: system apps can be removed", 16f, C.GREEN, bold = true, lines = 0),
                lpL(MATCH, WRAP).margins(l = dp(4), t = dp(12)))
            box.addView(pill("Go to apps", C.GREEN, C.GREEN) { setFilter(Filter.SYSTEM); showTab(Tab.APPS) },
                lpL(WRAP, WRAP).margins(l = dp(4), t = dp(8), b = dp(16)))
        }
        box.addView(text(
            "Shizuku stops when the phone restarts; nothing of Plain Uninstaller runs in the background.",
            12f, C.LABEL, lines = 0,
        ), lpL(MATCH, WRAP).margins(l = dp(4), t = dp(8), b = dp(16)))
    }

    private fun step(box: LinearLayout, n: Int, title: String, done: Boolean, body: String, buttons: (LinearLayout) -> Unit) {
        val card = vbox(14, 12).apply { background = outline(dpf(12), if (done) C.OUTLINE else C.OUTLINE_BTN) }
        val head = hbox()
        head.addView(text(if (done) "✓" else "✗", 18f, if (done) C.GREEN else C.RED, bold = true), lpL(dp(28), WRAP))
        head.addView(text("$n. $title", 15f, C.TEXT, bold = true, lines = 0), lpL(0, WRAP, 1f))
        card.addView(head)
        card.addView(text(body, 13f, C.DIM, lines = 0).apply { setTextIsSelectable(true) }, lpL(MATCH, WRAP).margins(l = dp(28), t = dp(4)))
        val row = hbox()
        buttons(row)
        if (row.childCount > 0) card.addView(row, lpL(MATCH, WRAP).margins(l = dp(28), t = dp(10)))
        box.addView(card, lpL(MATCH, WRAP).margins(t = dp(8)))
    }

    private fun stepButton(label: String, onClick: () -> Unit) = pill(label) { onClick() }.apply {
        textSize = 13f
        layoutParams = lpL(WRAP, WRAP).margins(r = dp(8))
    }

    private fun openSettings(action: String) {
        try { startActivity(Intent(action)) } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_SETTINGS)) } catch (_: Exception) { toast("Couldn't open Settings") }
        }
    }

    private fun openShizukuDownload() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${Shiz.PKG}")))
        } catch (_: ActivityNotFoundException) {
            try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"))) } catch (_: Exception) {
                toast("No app can open shizuku.rikka.app/download")
            }
        } catch (_: Exception) {
            toast("Couldn't open the download page")
        }
    }

    private fun openShizuku() {
        val i = try { packageManager.getLaunchIntentForPackage(Shiz.PKG) } catch (_: Exception) { null }
        if (i == null) { toast("Shizuku isn't installed"); return }
        try { startActivity(i) } catch (_: Exception) { toast("Couldn't open Shizuku") }
    }

    companion object {
        private const val REQ_UNINSTALL = 7
    }
}
