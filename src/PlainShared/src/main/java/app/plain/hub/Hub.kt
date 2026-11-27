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
 * Every tab is its own Activity, all in the app's one task (one entry in Recents). The switcher pill ("Music ▾", in
 * the thumb bar at the bottom, see [wrap]) switches tabs by bringing the other tab's existing Activity to the front
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
    /** "Plain Maps": the app's name, shown before the tab name in the header. */
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

    /** The tab the launcher icon opens: the last one used, else the first. */
    fun lastTab(ctx: Context): Tab {
        val vis = visibleTabs
        return vis.firstOrNull { it.key == read(ctx, "last") } ?: vis.first()
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
     * How a tab sits in the frame. [LIST]: a quiet title on top ("Plain Media" small, the page's name big) and the
     * thumb bar at the bottom: the switcher pill, then whatever the tab puts in [bar] (its search field, ⋮ …).
     * [FULL]: the tab draws edge to edge (a map, the camera) and places [switcher] itself; it gets all insets.
     */
    enum class Style { LIST, FULL }

    /**
     * Puts the title above and the thumb bar below [content]; use as `setContentView(Hub.wrap(this, "key", root))`.
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

    /** The big title (null: the tab's name). The small line above it then reads "Plain Media · Music". */
    fun setTitle(a: Activity, title: CharSequence?) { frameOf(a)?.setTitle(title) }

    /** A ‹ before the title (a sub-page, search results); null hides it. */
    fun setBack(a: Activity, onBack: (() -> Unit)?) { frameOf(a)?.setBack(onBack) }

    /** Tapping the title (e.g. scroll the list back to the top); null: nothing. */
    fun setOnTitleTap(a: Activity, fn: (() -> Unit)?) { frameOf(a)?.titleTap = fn }

    /** Small views at the right end of the title row (an action that belongs to the page, not the thumb bar). */
    fun titleEnd(a: Activity): LinearLayout? = frameOf(a)?.titleEnd

    /**
     * The thumb bar's slot after the switcher pill: horizontal, vertically centred; add the tab's search field (weight 1)
     * and ⋮ here after setContentView. Null for [Style.FULL] tabs.
     */
    fun bar(a: Activity): LinearLayout? = frameOf(a)?.slot

    /** The switcher pill ("Istanbul ▾") for a [Style.FULL] tab to place where it likes (a map sheet, a camera bar). */
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

    /** The app's tabs as a bottom sheet; picking one switches to it. */
    @Suppress("UNUSED_PARAMETER")
    fun showMenu(a: Activity, anchor: View, current: String) {
        val vis = visibleTabs
        val s = Sheet(a, accent).header(appName.ifEmpty { tr("Switch", "Değiştir") })
        for (t in vis) s.choice(t.label, t.key == current) { open(a, t.key) }
        s.show()
    }

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
) {
    val label: String get() = tr(en, trName)
}

/**
 * The thumb-bar frame around a tab's own UI (2026-11-27): a quiet title on top ("Plain Maps" small, the page's name
 * big) and at the bottom the switcher pill ("Istanbul ▾") with the tab's own [slot] (search, ⋮). It takes the
 * status-bar inset (title) and the navigation-bar + keyboard inset (thumb bar) itself and hands the content the
 * window insets without them. Title and bar hide in picture-in-picture and when the tab asks ([Hub.setHeaderShown]).
 * [Hub.Style.FULL]: no title, no bar, the content gets every inset.
 */
internal class HubFrame(private val act: Activity, private val key: String, private val content: View, private val style: Hub.Style) : LinearLayout(act) {
    private val titleBox: LinearLayout
    private val label: TextView
    private val title: TextView
    private val back: TextView
    val titleEnd: LinearLayout
    private val bar: LinearLayout
    val slot: LinearLayout?
    private var wanted = true
    private var lastInsets: WindowInsets? = null
    var titleTap: (() -> Unit)? = null
    private var onBack: (() -> Unit)? = null
    private val dp = resources.displayMetrics.density

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        val tabLabel = Hub.tab(key)?.label ?: key
        // --- title: "Plain Media" (small) / "Music" (big); ‹ for sub-pages
        titleBox = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((10 * dp).toInt(), 0, (10 * dp).toInt(), (6 * dp).toInt())
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
        titleBox.addView(back, LayoutParams((40 * dp).toInt(), (48 * dp).toInt()))
        val col = LinearLayout(act).apply { orientation = VERTICAL; setPadding((8 * dp).toInt(), 0, 0, 0) }
        label = TextView(act).apply {
            text = Hub.appName
            textSize = 12f
            setTextColor(DIM)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = 0.02f
        }
        title = TextView(act).apply {
            text = tabLabel
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(TEXT)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(0, (2 * dp).toInt(), 0, 0)
        }
        col.addView(label); col.addView(title)
        col.setOnClickListener { titleTap?.invoke() }
        titleBox.addView(col, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        titleEnd = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleBox.addView(titleEnd, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        // --- thumb bar: switcher pill + the tab's slot
        bar = LinearLayout(act).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * dp).toInt(), (6 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        bar.addView(Hub.pill(act, key), LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { rightMargin = (6 * dp).toInt() })
        // a fixed minimum height: buttons appearing in the slot (a selection) never resize the content above
        slot = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; minimumHeight = (44 * dp).toInt() }
        bar.addView(slot, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (style == Hub.Style.LIST) addView(titleBox, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        if (style == Hub.Style.LIST) addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        updateVisibility()
    }

    fun setTitle(t: CharSequence?) {
        val tabLabel = Hub.tab(key)?.label ?: key
        title.text = t ?: tabLabel
        label.text = if (t == null || t.toString() == tabLabel) Hub.appName else "${Hub.appName}  ·  $tabLabel"
    }

    fun setBack(fn: (() -> Unit)?) {
        onBack = fn
        back.visibility = if (fn != null) View.VISIBLE else View.GONE
    }

    fun setHeaderWanted(shown: Boolean) {
        wanted = shown
        updateVisibility()
    }

    private fun updateVisibility() {
        if (style == Hub.Style.FULL) return
        val pip = Build.VERSION.SDK_INT >= 24 && act.isInPictureInPictureMode
        val v = if (wanted && !pip) View.VISIBLE else View.GONE
        if (titleBox.visibility != v) {
            titleBox.visibility = v
            bar.visibility = v
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
        if (style == Hub.Style.FULL || titleBox.visibility != View.VISIBLE) {
            content.dispatchApplyWindowInsets(insets)
            return insets
        }
        val top: Int
        val bottom: Int
        val child: WindowInsets
        if (Build.VERSION.SDK_INT >= 30) {
            val sb = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())
            val nav = insets.getInsets(WindowInsets.Type.navigationBars())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            top = sb.top
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
            top = insets.systemWindowInsetTop
            bottom = insets.systemWindowInsetBottom
            child = insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, 0)
        }
        val tTop = top + (12 * dp).toInt()
        if (titleBox.paddingTop != tTop) titleBox.setPadding(titleBox.paddingLeft, tTop, titleBox.paddingRight, titleBox.paddingBottom)
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
