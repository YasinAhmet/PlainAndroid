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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.migrate.ImportActivity
import app.plain.migrate.Migrate
import java.io.File

/**
 * One app made of several former apps ("tabs"): Plain Maps = Istanbul / Ankara / Trekking, and so on.
 *
 * Every tab is its own Activity, all in the app's one task (one entry in Recents). The dropdown header on top
 * ([wrap]) switches tabs by bringing the other tab's existing Activity to the front (FLAG_ACTIVITY_REORDER_TO_FRONT),
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

    /** Extra on the launcher intent: open this tab key instead of the last one. */
    const val EXTRA_TAB = "app.plain.hub.TAB"

    /** Call first thing in Application.onCreate, in every process. */
    fun setup(app: Application, name: String, tabs: List<Tab>) {
        appName = name
        this.tabs = tabs
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

    // ------------------------------------------------------------------ header

    /** Puts the dropdown header above [content]; use as `setContentView(Hub.wrap(this, "key", root))`. */
    fun wrap(a: Activity, key: String, content: View): View {
        (content.parent as? ViewGroup)?.removeView(content)
        return HubFrame(a, key, content)
    }

    /** Hides the header (fullscreen video, a fullscreen player) or shows it again. */
    fun setHeaderShown(a: Activity, shown: Boolean) {
        frameOf(a)?.setHeaderWanted(shown)
    }

    private fun frameOf(a: Activity): HubFrame? {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return null
        return (0 until root.childCount).map { root.getChildAt(it) }.filterIsInstance<HubFrame>().firstOrNull()
    }

    /** The menu of tabs under [anchor]; picking one switches to it. */
    fun showMenu(a: Activity, anchor: View, current: String) {
        val m = PopupMenu(a, anchor)
        val vis = visibleTabs
        vis.forEachIndexed { i, t -> m.menu.add(0, i, i, t.label).isCheckable = true }
        m.menu.setGroupCheckable(0, true, true)
        vis.indexOfFirst { it.key == current }.takeIf { it >= 0 }?.let { m.menu.getItem(it).isChecked = true }
        m.setOnMenuItemClickListener { item ->
            val t = vis[item.itemId]
            if (t.key != current) open(a, t.key)
            true
        }
        m.show()
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
 * The header strip above a tab's own UI: "Plain Maps · Istanbul ▾". It takes the status-bar inset itself and
 * hands the content the window insets without it, so the content's own inset handling keeps working.
 * Hidden in picture-in-picture and when the tab asks ([Hub.setHeaderShown]).
 */
internal class HubFrame(private val act: Activity, private val key: String, private val content: View) : LinearLayout(act) {
    private val header: TextView
    private var wanted = true
    private var lastInsets: WindowInsets? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        val dp = resources.displayMetrics.density
        val tab = Hub.tab(key)
        header = TextView(act).apply {
            val s = SpannableStringBuilder()
            if (Hub.appName.isNotEmpty()) {
                s.append(Hub.appName)
                s.setSpan(ForegroundColorSpan(DIM), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.append("  ·  ")
            }
            val st = s.length
            s.append(tab?.label ?: key)
            s.setSpan(StyleSpan(Typeface.BOLD), st, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.append("  ▾")
            text = s
            textSize = 14f
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
            minHeight = (36 * dp).toInt()
            setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0)
            background = android.graphics.drawable.LayerDrawable(arrayOf(
                android.graphics.drawable.ColorDrawable(LINE),
                android.graphics.drawable.ColorDrawable(BG),
            )).apply { setLayerInset(1, 0, 0, 0, (1 * dp).toInt().coerceAtLeast(1)) }
            contentDescription = tr("Switch: ", "Değiştir: ") + (tab?.label ?: key)
            setOnClickListener { Hub.showMenu(act, this, key) }
            if (Hub.visibleTabs.size < 2) isClickable = false
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        updateVisibility()
    }

    fun setHeaderWanted(shown: Boolean) {
        wanted = shown
        updateVisibility()
    }

    private fun updateVisibility() {
        val pip = Build.VERSION.SDK_INT >= 24 && act.isInPictureInPictureMode
        val v = if (wanted && !pip) View.VISIBLE else View.GONE
        if (header.visibility != v) {
            header.visibility = v
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
        if (header.visibility != View.VISIBLE) {
            content.dispatchApplyWindowInsets(insets)
            return insets
        }
        val top: Int
        val child: WindowInsets
        if (Build.VERSION.SDK_INT >= 30) {
            val sb = insets.getInsets(WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())
            top = sb.top
            val c = insets.getInsets(WindowInsets.Type.displayCutout())
            child = WindowInsets.Builder(insets)
                .setInsets(WindowInsets.Type.statusBars(), Insets.NONE)
                .setInsets(WindowInsets.Type.captionBar(), Insets.NONE)
                .setInsets(WindowInsets.Type.displayCutout(), Insets.of(c.left, 0, c.right, c.bottom))
                .setInsetsIgnoringVisibility(WindowInsets.Type.statusBars(), Insets.NONE)
                .setInsetsIgnoringVisibility(WindowInsets.Type.captionBar(), Insets.NONE)
                .build()
        } else {
            top = insets.systemWindowInsetTop
            child = insets.replaceSystemWindowInsets(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        if (header.paddingTop != top) header.setPadding(header.paddingLeft, top, header.paddingRight, 0)
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
