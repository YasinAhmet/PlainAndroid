package app.plain.hub

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Insets
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.migrate.ImportActivity
import app.plain.migrate.Migrate
import app.plain.ui.Sheet
import java.io.File

/**
 * One app made of several former apps ("tabs"): Plain Maps = Istanbul / Ankara / Trekking, and so on.
 *
 * Every tab is its own Activity, all in the app's one task (one entry in Recents). The drawers on top (see [wrap]; the
 * switcher pill on full-screen tabs) switch tabs by bringing the other tab's existing Activity to the front
 * (FLAG_ACTIVITY_REORDER_TO_FRONT),
 * so a tab is never started twice and keeps its state. Intents from other apps come in through [ForwardActivity]
 * aliases, which do the same. Nothing of a tab runs while it is not open, except what the former app already ran in
 * the background (a recording, playback, a sync). Back from a tab returns to the tab used before it.
 *
 * The launcher icon opens [HubActivity], which forwards to the last used tab (the first tab the first time).
 * State lives in small files under files/hub/, not SharedPreferences: some apps run tabs in separate processes,
 * and SharedPreferences caches across processes would go stale.
 *
 * Canonical copy: A:/Projects/PlainShared (the `:shared` module of every merged Plain app).
 */
object Hub {
    /** "Plain Maps": the app's name (the tab sheet's header). */
    var appName: String = ""
        private set
    var tabs: List<Tab> = emptyList()
        private set
    /** The app's colour: the switcher pill and the tab sheet. */
    var accent: Int = Sheet.ACCENT
        private set

    /** Extra on the launcher intent: open this tab key instead of the last one. */
    const val EXTRA_TAB = "app.plain.hub.TAB"

    /** Call first thing in Application.onCreate, in every process. */
    fun setup(app: Application, name: String, tabs: List<Tab>, accent: Int = Sheet.ACCENT) {
        appName = name
        this.tabs = tabs
        this.accent = accent
        PlainLang.init(app)
    }

    /** Tabs this phone can run (a tab may need a newer Android than the app itself). */
    val visibleTabs: List<Tab> get() = tabs.filter { Build.VERSION.SDK_INT >= it.minSdk }

    fun tab(key: String?): Tab? = tabs.firstOrNull { it.key == key }

    /**
     * The tabs shown in the drawers / the tab sheet from tab [current]: those without [Tab.shown] or whose test
     * passes (a downloaded city), plus [current] itself. The rest wait under "Others" ([otherTabs]).
     */
    fun mainTabs(ctx: Context, current: String?): List<Tab> =
        // (a test that throws counts as "shown": the drawers must always build)
        visibleTabs.filter { it.key == current || (try { it.shown?.invoke(ctx) } catch (_: Exception) { null }) != false }

    fun otherTabs(ctx: Context, current: String?): List<Tab> {
        val main = mainTabs(ctx, current).toSet()
        return visibleTabs.filter { it !in main }
    }

    // ------------------------------------------------------------------ state

    internal fun dir(ctx: Context) = File(ctx.filesDir, "hub").apply { mkdirs() }

    internal fun read(ctx: Context, name: String): String? =
        try { File(dir(ctx), name).takeIf { it.exists() }?.readText()?.trim() } catch (_: Exception) { null }

    internal fun write(ctx: Context, name: String, value: String?) {
        try {
            val f = File(dir(ctx), name)
            if (value == null) f.delete() else if (read(ctx, name) != value) f.writeText(value)
        } catch (_: Exception) {}
    }

    /** The tab the launcher icon opens: the last one used, else the first (a key no tab has any more counts as none). */
    fun lastTab(ctx: Context): Tab {
        val vis = visibleTabs
        val last = read(ctx, "last")
        return vis.firstOrNull { it.key == last } ?: vis.firstOrNull() ?: tabs.first()
    }

    fun setLast(ctx: Context, key: String) = write(ctx, "last", key)

    /** True the first time [key] is entered (then remembered): for "sync now?" style first-visit questions. */
    fun firstVisit(ctx: Context, key: String): Boolean {
        val name = "visited-$key"
        if (read(ctx, name) != null) return false
        write(ctx, name, System.currentTimeMillis().toString())
        return true
    }

    // ------------------------------------------------------------------ switching

    /**
     * Opens tab [key]. When the tab's former app is still installed and its data hasn't been imported or declined,
     * the import screen comes first. [forward] carries an intent (e.g. "Open with") on to the tab.
     */
    fun open(from: Context, key: String, forward: Intent? = null) {
        val t = tab(key) ?: return
        setLast(from, key)
        val first = read(from, TASKS_DONE) == null
        if (first) { write(from, TASKS_DONE, "1"); closeOtherTasks(from, (from as? Activity)?.taskId ?: -1) }
        val i = if (Migrate.needsDecision(from, t)) {
            Intent(from, ImportActivity::class.java).putExtra(ImportActivity.EXTRA_TAB, key).apply {
                if (forward != null) putExtra(ImportActivity.EXTRA_FORWARD, forward)
            }
        } else tabIntent(from, t, forward)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        from.startActivity(i)
        if (from is Activity) {
            @Suppress("DEPRECATION")
            from.overridePendingTransition(0, 0)
            // a tab still in an old per-tab task leaves it, so every tab ends up in the one task
            if (first && from !is HubActivity && from !is ForwardActivity) from.finishAndRemoveTask()
        }
    }

    /**
     * Plain Manager 2.0, Plain Maps 3.0, Plain Camera 2.0 and Plain Media 3.0 gave every tab its own task. Their
     * tasks survive an update, so the first switch after it closes all of the app's other tasks (once).
     */
    private const val TASKS_DONE = "one-task"

    private fun closeOtherTasks(ctx: Context, keep: Int) {
        val am = ctx.getSystemService(android.app.ActivityManager::class.java) ?: return
        for (task in try { am.appTasks } catch (_: Exception) { emptyList() }) try {
            val info = task.taskInfo
            @Suppress("DEPRECATION")
            val id = if (Build.VERSION.SDK_INT >= 29) info.taskId else info.persistentId
            if (id != keep) task.finishAndRemoveTask()
        } catch (_: Exception) {}
    }

    /** The tab's Activity, or [forward] as it was (an Activity of the tab reached directly, e.g. "Open with"). */
    internal fun tabIntent(ctx: Context, t: Tab, forward: Intent?): Intent {
        val i = if (forward != null) Intent(forward) else Intent(Intent.ACTION_MAIN)
        if (i.component == null) i.setClassName(ctx.packageName, t.cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        return i
    }

    /**
     * Call right after super.onCreate() in every tab Activity. Returns true when the Activity must stop here
     * (`finish(); return`): the tab's old data has to be imported or declined first. That happens only when the
     * tab is reached without the launcher/header (an "Open with", a recent task), because [open] checks first.
     * The process is then ended, so the tab starts clean after the import (nothing loaded the empty state).
     */
    fun gate(a: Activity, key: String): Boolean {
        val t = tab(key) ?: return false
        if (!Migrate.needsDecision(a, t)) return false
        val fwd = a.intent?.takeIf { it.action != null && it.action != Intent.ACTION_MAIN }
        a.startActivity(Intent(a, ImportActivity::class.java).putExtra(ImportActivity.EXTRA_TAB, key).apply {
            if (fwd != null) putExtra(ImportActivity.EXTRA_FORWARD, Intent(fwd).setComponent(a.componentName))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        a.finish()
        // (a Handler, not a View post: this Activity's window is never attached)
        if (t.ownProcess) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 500)
        return true
    }

    // ------------------------------------------------------------------ thumb bar layout (2026-11-27, "draft 3")

    /**
     * How a tab sits in the frame. [LIST]: the app's tabs as drawers on top (the open one pulled out), a big title under
     * them only when the tab sets one, and the thumb bar at the bottom with whatever the tab puts in [bar] (search, ⋮ …).
     * [FULL]: the drawers on top as a black strip (they take the status-bar inset); below them the tab draws to the
     * screen's bottom edge (a map, the camera) and handles the bottom insets itself. No title, no thumb bar.
     */
    enum class Style { LIST, FULL }

    /**
     * Puts the drawers (and title) above and the thumb bar below [content]; use as `setContentView(Hub.wrap(this, "key", root))`.
     * The frame takes the status-bar inset (title) and the navigation-bar + keyboard inset (thumb bar) itself and hands
     * [content] the window insets without them, so the content's own inset handling keeps working.
     */
    fun wrap(a: Activity, key: String, content: View, style: Style = Style.LIST): View {
        (content.parent as? ViewGroup)?.removeView(content)
        return HubFrame(a, key, content, style)
    }

    /** Hides the title and the thumb bar (fullscreen video, a fullscreen player, a selection bar) or shows them again. */
    fun setHeaderShown(a: Activity, shown: Boolean) {
        frameOf(a)?.setHeaderWanted(shown)
    }

    /** The big title under the drawers; null hides it (the drawers name the tab; a list may head itself). */
    fun setTitle(a: Activity, title: CharSequence?) { frameOf(a)?.setTitle(title) }

    /** A ‹ before the drawers (a sub-page, search results); null hides it. */
    fun setBack(a: Activity, onBack: (() -> Unit)?) { frameOf(a)?.setBack(onBack) }

    /** Tapping the title or the open tab's drawer again (e.g. scroll the list back to the top); null: nothing. */
    fun setOnTitleTap(a: Activity, fn: (() -> Unit)?) { frameOf(a)?.titleTap = fn }

    /** Small views at the right end of the drawer row (an action that belongs to the page, not the thumb bar). */
    fun titleEnd(a: Activity): LinearLayout? = frameOf(a)?.titleEnd

    /**
     * The thumb bar's slot: horizontal, vertically centred; add the tab's search field (weight 1)
     * and ⋮ here after setContentView. Null for [Style.FULL] tabs.
     */
    fun bar(a: Activity): LinearLayout? = frameOf(a)?.slot

    /** Dims the drawers and stops them switching (the camera while it records), or turns them back on. */
    fun setSwitchEnabled(a: Activity, on: Boolean) { frameOf(a)?.setSwitchEnabled(on) }

    /**
     * The old switcher pill ("Istanbul ▾"). Since 2026-11-28 every tab, full-screen ones included, switches with the
     * drawers on top; kept for a screen outside the frame that still needs a way to the other tabs.
     */
    fun switcher(a: Activity, key: String): TextView = pill(a, key)

    internal fun pill(a: Activity, key: String): TextView {
        val dp = a.resources.displayMetrics.density
        val label = tab(key)?.label ?: key
        val many = visibleTabs.size > 1
        return TextView(a).apply {
            text = if (many) "$label  ▾" else label
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(accent)
            gravity = Gravity.CENTER
            isSingleLine = true
            minHeight = (40 * dp).toInt()
            setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
            background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                GradientDrawable().apply { setColor(HubFrame.BG); cornerRadius = 20 * dp; setStroke((1 * dp).toInt().coerceAtLeast(1), accent) }, null)
            contentDescription = tr("Switch: ", "Değiştir: ") + label
            if (many) setOnClickListener { showMenu(a, this, key) }
        }
    }

    private fun frameOf(a: Activity): HubFrame? {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<HubFrame>().firstOrNull()
    }

    /**
     * The app's tabs as a bottom sheet; picking one switches to it. Tabs that are not shown yet (a city without
     * data) sit behind an "Others" row, which opens them as a second sheet.
     */
    @Suppress("UNUSED_PARAMETER")
    fun showMenu(a: Activity, anchor: View, current: String) {
        val s = Sheet(a, accent).header(appName.ifEmpty { tr("Switch", "Değiştir") })
        for (t in mainTabs(a, current)) s.choice(t.label, t.key == current) { open(a, t.key) }
        val others = otherTabs(a, current)
        if (others.isNotEmpty()) {
            s.item(null, tr("Others", "Diğerleri"), others.joinToString(", ") { it.label }, more = true) { showOthers(a, current) }
        }
        s.show()
    }

    /** The tabs under "Others" (not downloaded yet), as their own sheet. */
    fun showOthers(a: Activity, current: String) {
        val s = Sheet(a, accent).header(tr("Others", "Diğerleri"), othersNote?.invoke())
        for (t in otherTabs(a, current)) s.choice(t.label, false) { open(a, t.key) }
        s.show()
    }

    /** The line under the "Others" sheet's title ("Not downloaded yet"); set by the app. */
    var othersNote: (() -> String)? = null

    /** Process name (`app.plain.maps:ankara`), for apps that run tabs in their own processes. */
    fun processName(): String {
        if (Build.VERSION.SDK_INT >= 28) Application.getProcessName()?.let { return it }
        return try { File("/proc/self/cmdline").readText().trim { it <= ' ' } } catch (_: Exception) { "" }
    }
}

/**
 * A tab. [cls] is the Activity's class name; [import] names the former app whose data the tab can copy over;
 * [ownProcess] is true when the tab's Activity runs in its own process (see [Hub.gate]).
 */
data class Tab(
    val key: String,
    val en: String,
    val trName: String,
    val cls: String,
    val minSdk: Int = 1,
    val import: Migrate.Spec? = null,
    val ownProcess: Boolean = false,
    /** Null: always in the drawers / tab sheet. Else shown there only while this is true (e.g. the city is downloaded); otherwise under "Others". */
    val shown: ((Context) -> Boolean)? = null,
) {
    val label: String get() = tr(en, trName)
}

/**
 * The frame around a tab's own UI (thumb bar 2026-11-27, drawers 2026-11-28). On top: the app's tabs as a row of
 * "drawers", outlined boxes hanging from the top edge; the open tab's drawer is pulled out (slid a little down, accent
 * outline) and tapping another one slides that one out and switches. A ‹ sits before the drawers on sub-pages; the big
 * title shows only when the tab sets one ([Hub.setTitle]), so a tab that heads its own list never shows two titles.
 * At the bottom: the tab's own [slot] (search, ⋮). It takes the status-bar inset (drawers) and the navigation-bar +
 * keyboard inset (thumb bar) itself and hands the content the window insets without them. Drawers, title and bar hide
 * in picture-in-picture and when the tab asks ([Hub.setHeaderShown]).
 * [Hub.Style.FULL]: the drawers on top too, no title, no bar; the content gets every inset but the top one.
 */
internal class HubFrame(private val act: Activity, private val key: String, private val content: View, private val style: Hub.Style) : LinearLayout(act) {
    private val top: LinearLayout
    private val drawerRow: LinearLayout
    private val drawers = ArrayList<Pair<String, LinearLayout>>()
    private val titleBox: LinearLayout
    private val title: TextView
    private val back: TextView
    val titleEnd: LinearLayout
    private val bar: LinearLayout
    val slot: LinearLayout?
    private var wanted = true
    private var switching = false
    private var lastInsets: WindowInsets? = null
    var titleTap: (() -> Unit)? = null
    private var onBack: (() -> Unit)? = null
    private val dp = resources.displayMetrics.density
    private val drop = 7 * dp // how far the open drawer is pulled out

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        top = LinearLayout(act).apply { orientation = VERTICAL }
        // --- drawers: one outlined box per tab; ‹ before them on sub-pages, the tab's titleEnd after them
        drawerRow = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), (drop + 6 * dp).toInt())
            clipChildren = false
            clipToPadding = false
        }
        back = TextView(act).apply {
            text = "‹"
            textSize = 30f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = tr("Back", "Geri")
            visibility = View.GONE
            setOnClickListener { onBack?.invoke() }
        }
        drawerRow.addView(back, LayoutParams((40 * dp).toInt(), (DRAWER_H * dp).toInt()))
        // the cabinet: each drawer as wide as its name, the spare width shared out; when there are too many (Plain
        // Maps' cities) it scrolls sideways. The open drawer slides down into the row's bottom padding (no clipping).
        val cabinet = Cabinet(act).apply { orientation = HORIZONTAL; clipChildren = false; clipToPadding = false }
        val vis = Hub.mainTabs(act, key).ifEmpty { listOfNotNull(Hub.tab(key)) }
        vis.forEachIndexed { i, t ->
            val d = drawer(t.label) { pick(t.key) }
            drawers += t.key to d
            cabinet.addView(d, LayoutParams(LayoutParams.WRAP_CONTENT, (DRAWER_H * dp).toInt()).apply { if (i > 0) leftMargin = (6 * dp).toInt() })
        }
        // tabs not shown yet (see Tab.shown) wait behind one more drawer
        if (Hub.otherTabs(act, key).isNotEmpty()) {
            val d = drawer(tr("Others", "Diğerleri") + " ▾") { Hub.showOthers(act, key) }
            drawers += "" to d
            cabinet.addView(d, LayoutParams(LayoutParams.WRAP_CONTENT, (DRAWER_H * dp).toInt()).apply { leftMargin = (6 * dp).toInt() })
        }
        val shelf = android.widget.HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipChildren = false
            clipToPadding = false
            // more drawers off to the side fade into the black (a darkening edge, no lit pixels)
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength((24 * dp).toInt())
            addView(cabinet)
        }
        drawerRow.addView(shelf, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        // keep the open drawer in view when the cabinet scrolls
        drawers.firstOrNull { it.first == key }?.second?.let { d -> shelf.post { shelf.scrollTo((d.left - 24 * dp).toInt().coerceAtLeast(0), 0) } }
        titleEnd = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        drawerRow.addView(titleEnd, LayoutParams(LayoutParams.WRAP_CONTENT, (DRAWER_H * dp).toInt()).apply { leftMargin = (4 * dp).toInt() })
        top.addView(drawerRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // --- title: only when the tab sets one (Youtube's "Download", a search page's name)
        titleBox = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((18 * dp).toInt(), 0, (12 * dp).toInt(), (6 * dp).toInt())
            visibility = View.GONE
        }
        title = TextView(act).apply {
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setOnClickListener { titleTap?.invoke() }
        }
        titleBox.addView(title, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        top.addView(titleBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        // --- thumb bar: the tab's slot (search, ⋮); only the nav-bar inset when the tab puts nothing there
        bar = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), (4 * dp).toInt())
        }
        // a fixed minimum height: buttons appearing in the slot (a selection) never resize the content above
        slot = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setOnHierarchyChangeListener(object : OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) = fitBar()
                override fun onChildViewRemoved(parent: View?, child: View?) = fitBar()
            })
        }
        bar.addView(slot, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        fitBar()
        // full-screen tabs (a map, the camera) get the drawers too, as a black strip above them
        addView(top, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        if (style == Hub.Style.LIST) addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        showOpen(key, animate = false)
        updateVisibility()
    }

    /** A drawer front: the tab's name with a small pull under it; square top corners (it hangs from the screen's top). */
    private fun drawer(label: String, onTap: () -> Unit): LinearLayout = LinearLayout(act).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        val name = TextView(act).apply {
            text = label
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding((6 * dp).toInt(), 0, (6 * dp).toInt(), 0)
        }
        addView(name, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        minimumWidth = (72 * dp).toInt()
        setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        // the pull: a 2 dp line (a tiny indicator, allowed as a fill by the design rules)
        addView(View(act), LayoutParams((16 * dp).toInt(), (2 * dp).toInt().coerceAtLeast(1)).apply { topMargin = (6 * dp).toInt() })
        contentDescription = label
        setOnClickListener { onTap() }
    }

    private fun styleDrawer(d: LinearLayout, open: Boolean) {
        val c = if (open) Hub.accent else OUTLINE_BTN
        val r = 10 * dp
        d.background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x22FFFFFF),
            GradientDrawable().apply {
                setColor(BG)
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
                setStroke((1 * dp).toInt().coerceAtLeast(1), c)
            }, null)
        (d.getChildAt(0) as TextView).apply {
            setTextColor(if (open) Hub.accent else DIM)
            setTypeface(null, if (open) Typeface.BOLD else Typeface.NORMAL)
        }
        d.getChildAt(1).setBackgroundColor(if (open) Hub.accent else FAINT)
        d.isSelected = open
    }

    /** Pulls out [open]'s drawer and pushes the others back in. */
    private fun showOpen(open: String, animate: Boolean) {
        for ((k, d) in drawers) {
            val o = k == open
            styleDrawer(d, o)
            val y = if (o) drop else 0f
            d.animate().cancel()
            if (animate) d.animate().translationY(y).setDuration(140).start() else d.translationY = y
        }
    }

    private fun pick(k: String) {
        if (switching) return
        if (k == key) { titleTap?.invoke(); return }
        switching = true
        showOpen(k, animate = true)
        // (the screen may be closing by then: Back right after the tap)
        postDelayed({ if (act.isFinishing || act.isDestroyed) { switching = false; showOpen(key, animate = false) } else Hub.open(act, k) }, 150)
    }

    private fun fitBar() {
        val s = slot ?: return
        val has = s.childCount > 0
        s.minimumHeight = if (has) (44 * dp).toInt() else 0
        bar.setPadding(bar.paddingLeft, if (has) (6 * dp).toInt() else 0, bar.paddingRight, bar.paddingBottom)
    }

    // back from another tab: this tab's drawer is the open one again
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE && switching) { switching = false; showOpen(key, animate = false) }
    }

    fun setTitle(t: CharSequence?) {
        title.text = t ?: ""
        titleBox.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    fun setBack(fn: (() -> Unit)?) {
        onBack = fn
        back.visibility = if (fn != null) View.VISIBLE else View.GONE
    }

    fun setHeaderWanted(shown: Boolean) {
        wanted = shown
        updateVisibility()
    }

    /** Drawers dimmed and not tappable (the camera while it records). */
    fun setSwitchEnabled(on: Boolean) {
        for ((_, d) in drawers) { d.isEnabled = on; d.alpha = if (on) 1f else 0.4f }
    }

    private fun updateVisibility() {
        val pip = Build.VERSION.SDK_INT >= 24 && act.isInPictureInPictureMode
        val v = if (wanted && !pip) View.VISIBLE else View.GONE
        if (top.visibility != v) {
            top.visibility = v
            if (style == Hub.Style.LIST) bar.visibility = v
            lastInsets?.let { dispatchApplyWindowInsets(it) } ?: requestApplyInsets()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateVisibility()
    }

    // entering/leaving picture-in-picture resizes the window; the PiP flag may flip after the configuration change
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { updateVisibility() }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) Hub.setLast(act, key)
    }

    @Suppress("DEPRECATION")
    override fun dispatchApplyWindowInsets(insets: WindowInsets): WindowInsets {
        lastInsets = insets
        if (top.visibility != View.VISIBLE) {
            content.dispatchApplyWindowInsets(insets)
            return insets
        }
        val topInset: Int
        val bottom: Int
        val child: WindowInsets
        if (style == Hub.Style.FULL) {
            // a full-screen tab keeps its bottom insets (it places its own controls); only the top is the drawers'
            if (Build.VERSION.SDK_INT >= 30) {
                topInset = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar()).top
                val c = insets.getInsets(WindowInsets.Type.displayCutout())
                child = WindowInsets.Builder(insets)
                    .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
                    .setInsets(WindowInsets.Type.captionBar(), Insets.NONE)
                    .setInsets(WindowInsets.Type.displayCutout(), Insets.of(c.left, 0, c.right, c.bottom))
                    .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.NONE)
                    .setInsetsIgnoringVisibility(WindowInsets.Type.captionBar(), Insets.NONE)
                    .build()
            } else {
                topInset = insets.systemWindowInsetTop
                child = insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            if (drawerRow.paddingTop != topInset) drawerRow.setPadding(drawerRow.paddingLeft, topInset, drawerRow.paddingRight, drawerRow.paddingBottom)
            content.dispatchApplyWindowInsets(child)
            return insets
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val sb = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            topInset = sb.top
            bottom = maxOf(nav.bottom, ime.bottom)
            val c = insets.getInsets(WindowInsets.Type.displayCutout())
            child = WindowInsets.Builder(insets)
                .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
                .setInsets(WindowInsets.Type.captionBar(), Insets.NONE)
                .setInsets(WindowInsets.Type.navigationBars(), Insets.of(nav.left, 0, nav.right, 0))
                .setInsets(WindowInsets.Type.ime(), Insets.NONE)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(c.left, 0, c.right, 0))
                .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.captionBar(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.of(nav.left, 0, nav.right, 0))
                .build()
        } else {
            topInset = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
            child = insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
        }
        // the drawers hang right under the status bar
        if (drawerRow.paddingTop != topInset) drawerRow.setPadding(drawerRow.paddingLeft, topInset, drawerRow.paddingRight, drawerRow.paddingBottom)
        val bBottom = bottom + (4 * dp).toInt()
        if (bar.paddingBottom != bBottom) bar.setPadding(bar.paddingLeft, bar.paddingTop, bar.paddingRight, bBottom)
        content.dispatchApplyWindowInsets(child)
        return insets
    }

    companion object {
        const val BG = 0xFF000000.toInt()
        const val LINE = 0xFF2A2A2A.toInt()
        const val TEXT = 0xFFE4E4E4.toInt()
        const val DIM = 0xFFA6A6A6.toInt()
        const val FAINT = 0xFF484848.toInt()
        const val OUTLINE_BTN = 0xFF333333.toInt()
        const val DRAWER_H = 44
    }
}

/**
 * The row of drawers: each drawer as wide as its name (at least its minimum); when they are narrower together than
 * the screen, the spare width is shared out evenly, else the row is wider and its [HorizontalScrollView] scrolls.
 * (LinearLayout weights shrank every drawer to the same width inside the scroll view, cutting the names.)
 */
internal class Cabinet(ctx: Context) : LinearLayout(ctx) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
        val avail = MeasureSpec.getSize(widthMeasureSpec)
        val n = childCount
        if (n == 0 || measuredWidth >= avail) return
        val extra = (avail - measuredWidth) / n
        var rest = (avail - measuredWidth) - extra * n
        for (i in 0 until n) {
            val c = getChildAt(i)
            val w = c.measuredWidth + extra + if (rest > 0) 1 else 0
            if (rest > 0) rest--
            c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(c.measuredHeight, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(avail, measuredHeight)
    }
}

/** The launcher icon: forwards to the last used tab (or the one named by [Hub.EXTRA_TAB]) and closes. */
class HubActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val want = intent?.getStringExtra(Hub.EXTRA_TAB)?.let { Hub.tab(it) }?.takeIf { it in Hub.visibleTabs }
        Hub.open(this, (want ?: Hub.lastTab(this)).key)
        finish()
    }
}
