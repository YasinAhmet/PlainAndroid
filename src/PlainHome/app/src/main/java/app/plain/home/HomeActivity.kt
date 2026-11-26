package app.plain.home

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import app.plain.update.PlainUpdate
import java.util.concurrent.Executors

/**
 * Minimal home screen: clock + date on top, then apps in rows under section headers
 * (the user's old HiOS folders). Stock apps outside those sections are hidden on the user's
 * phone; a fresh install (a friend's phone) shows them and puts its dialer + SMS app in Temel.
 * Dark by default; the ◐ button switches to light and the choice is remembered.
 * Long-press an icon: App info / Move to / Hide. Long-press the clock: show hidden apps again.
 */
class HomeActivity : Activity() {

    private val prefs by lazy { getSharedPreferences("home", MODE_PRIVATE) }
    private val sectionPrefs by lazy { getSharedPreferences("sections", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()

    private var dark = true
    private var all: List<AppEntry> = emptyList()
    private var icons: Map<String, Drawable> = emptyMap()
    private var dirty = true
    private var isResumed = false

    private lateinit var root: LinearLayout
    private lateinit var time: TextClock
    private lateinit var date: TextClock
    private lateinit var toggle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            if (isResumed) reload()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = prefs.getBoolean("dark", true)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        buildUi()
        applyColors()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(packageReceiver, filter, RECEIVER_EXPORTED)
        else registerReceiver(packageReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        if (dirty) reload()
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        worker.shutdownNow()
        super.onDestroy()
    }

    /** Home pressed while already home: back to the top of the list. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::scroll.isInitialized) scroll.smoothScrollTo(0, 0)
    }

    @Deprecated("Home screen ignores Back")
    override fun onBackPressed() { /* stay home */ }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        time = TextClock(this).apply {
            format24Hour = "HH:mm"
            format12Hour = "h:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            includeFontPadding = false
            setOnClickListener { open(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            setOnLongClickListener { offerUnhide(); true }
        }
        date = TextClock(this).apply {
            format24Hour = "EEEE, d MMMM"
            format12Hour = "EEEE, d MMMM"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            setPadding(dp(12f), 0, 0, 0)
        }
        toggle = TextView(this).apply {
            text = "◐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = "Dark mode"
            setOnClickListener {
                dark = !dark
                prefs.edit().putBoolean("dark", dark).apply()
                applyColors()
            }
        }
        // One compact row: time, date beside it, toggle at the right edge.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(8f), dp(8f), dp(4f))
            addView(time, LinearLayout.LayoutParams(-2, -2))
            addView(date, LinearLayout.LayoutParams(0, -2, 1f))
            addView(toggle, LinearLayout.LayoutParams(dp(44f), dp(44f)))
        }

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8f), 0, dp(8f), dp(12f))
        }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(list, FrameLayout.LayoutParams(-1, -2))
        }

        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(b.left, b.top, b.right, b.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(root)
    }

    private val fg get() = if (dark) 0xFFEDEDED.toInt() else 0xFF161616.toInt()
    private val dim get() = if (dark) 0xFF9A9A9A.toInt() else 0xFF5F5F5F.toInt()

    private fun applyColors() {
        val bg = if (dark) 0xFF000000.toInt() else 0xFFF4F4F2.toInt()
        root.setBackgroundColor(bg)
        window.setBackgroundDrawable(ColorDrawable(bg))
        time.setTextColor(fg)
        date.setTextColor(dim)
        toggle.setTextColor(dim)
        toggle.contentDescription = if (dark) "Switch to light mode" else "Switch to dark mode"
        if (Build.VERSION.SDK_INT >= 30) {
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) 0 else
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        refreshGrid()
    }

    private fun menuContext(): Context = ContextThemeWrapper(this,
        if (dark) android.R.style.Theme_Material else android.R.style.Theme_Material_Light)

    // ---------------------------------------------------------------- apps

    private fun hidden(): Set<String> = prefs.getStringSet("hidden", emptySet()) ?: emptySet()

    /**
     * Stock apps outside a section start hidden only on the phone this was made for (an update
     * keeps its choice). On a fresh install — a friend's phone — they start visible, so Chrome,
     * Camera, Contacts… don't vanish; long-press the clock to hide them.
     */
    private fun showStock(): Boolean {
        if (!prefs.contains("showStock")) {
            val fresh = try {
                packageManager.getPackageInfo(packageName, 0).let { it.firstInstallTime == it.lastUpdateTime }
            } catch (_: Exception) { false }
            prefs.edit().putBoolean("showStock", fresh).apply()
        }
        return prefs.getBoolean("showStock", false)
    }

    /** This phone's own dialer and SMS app go to Temel unless placed elsewhere. */
    private val phoneDefaults: Map<String, String> by lazy {
        val m = HashMap<String, String>()
        try { (getSystemService(TELECOM_SERVICE) as? android.telecom.TelecomManager)?.defaultDialerPackage?.let { m[it] = "Temel" } } catch (_: Exception) {}
        try { android.provider.Telephony.Sms.getDefaultSmsPackage(this)?.let { m[it] = "Temel" } } catch (_: Exception) {}
        m
    }

    private fun reload() {
        dirty = false
        worker.execute {
            val pm = packageManager
            val found = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            val list = ArrayList<AppEntry>(found.size)
            val iconMap = HashMap<String, Drawable>(found.size)
            for (ri in found) {
                val ai = ri.activityInfo
                val stock = ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0
                val e = AppEntry(ai.packageName, ai.name, ri.loadLabel(pm).toString(), stock)
                list += e
                iconMap[e.key] = ri.loadIcon(pm)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                all = list
                icons = iconMap
                refreshGrid()
            }
        }
    }

    private fun overrides(): Map<String, String> =
        sectionPrefs.all.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }.toMap()

    /** Rebuilds the section headers and icon rows; cheap for a few dozen apps. */
    private fun refreshGrid() {
        if (!::list.isInitialized) return
        val sections = Layout.build(Apps.visible(all, hidden(), packageName), overrides(), showStock(), phoneDefaults)
        list.removeAllViews()
        for (s in sections) {
            list.addView(TextView(this).apply {
                text = s.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                letterSpacing = 0.06f
                setTextColor(dim)
                setPadding(dp(12f), dp(18f), dp(12f), dp(4f))
            })
            s.apps.chunked(COLUMNS).forEach { list.addView(row(it, COLUMNS)) }
        }
    }

    /** One row of [apps] in [slots] equal-width cells (empty cells keep the columns aligned). */
    private fun row(apps: List<AppEntry>, slots: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (i in 0 until slots) {
            val e = apps.getOrNull(i)
            addView(if (e == null) View(context) else cell(e), LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun cell(e: AppEntry): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(4f), dp(8f), dp(4f), dp(8f))
        isClickable = true
        isLongClickable = true
        contentDescription = e.label
        addView(ImageView(context).apply { setImageDrawable(icons[e.key]) }, LinearLayout.LayoutParams(dp(54f), dp(54f)))
        addView(TextView(context).apply {
            text = e.label
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(6f), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        setOnClickListener { launch(e) }
        setOnLongClickListener { appMenu(it, e); true }
    }

    private fun launch(e: AppEntry) {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(e.pkg, e.cls))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (!open(i)) reload()
    }

    private fun open(i: Intent): Boolean = try {
        startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show(); false
    } catch (_: SecurityException) {
        Toast.makeText(this, "Can't open that", Toast.LENGTH_SHORT).show(); false
    }

    private fun appMenu(anchor: View, e: AppEntry) {
        PopupMenu(menuContext(), anchor).apply {
            menu.add(0, 1, 0, "App info")
            val move = menu.addSubMenu(0, 3, 1, "Move to")
            val current = Layout.sectionOf(e, overrides(), phoneDefaults)
            Layout.TARGETS.forEachIndexed { i, name ->
                if (name != current) move.add(1, 100 + i, i, name)
            }
            menu.add(0, 2, 2, "Hide")
            setOnMenuItemClickListener {
                if (it.itemId >= 100) {
                    sectionPrefs.edit().putString(e.key, Layout.TARGETS[it.itemId - 100]).apply()
                    refreshGrid()
                    return@setOnMenuItemClickListener true
                }
                when (it.itemId) {
                    1 -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}")))
                    2 -> {
                        prefs.edit().putStringSet("hidden", hidden() + e.key).apply()
                        refreshGrid()
                        Toast.makeText(this@HomeActivity, "Hidden. Long-press the clock to bring it back.", Toast.LENGTH_LONG).show()
                    }
                }
                true
            }
            show()
        }
    }

    /**
     * Long-press on the clock: show hidden apps (yours and stock), or hide stock apps again.
     * Every variant also has the "Check for updates" button (handled by Plain Setup).
     */
    private fun offerUnhide() {
        val h = hidden()
        val ov = overrides()
        val stockHidden = all.filter { it.pkg != packageName && Layout.isAutoHidden(it, ov, showStock(), phoneDefaults) }
        if (h.isEmpty() && stockHidden.isEmpty()) {
            if (!showStock()) {
                AlertDialog.Builder(menuContext())
                    .setTitle("Plain Home")
                    .setMessage("Nothing is hidden. Long-press an app to move or hide it.")
                    .setPositiveButton(PlainUpdate.label(this)) { _, _ -> PlainUpdate.check(this) }
                    .setNegativeButton("Close", null)
                    .show()
                return
            }
            AlertDialog.Builder(menuContext())
                .setMessage("Hide stock apps that aren't in a section again?")
                .setPositiveButton("Hide") { _, _ -> prefs.edit().putBoolean("showStock", false).apply(); refreshGrid() }
                .setNeutralButton("Updates") { _, _ -> PlainUpdate.check(this) }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        val names = (h.map { k -> all.firstOrNull { it.key == k }?.label ?: k.substringBefore('/') } +
            stockHidden.map { it.label }).distinct().sorted()
        AlertDialog.Builder(menuContext())
            .setTitle("Hidden apps")
            .setMessage(names.joinToString("\n"))
            .setPositiveButton("Show all") { _, _ ->
                prefs.edit().remove("hidden").putBoolean("showStock", true).apply()
                refreshGrid()
            }
            .setNeutralButton("Updates") { _, _ -> PlainUpdate.check(this) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private companion object { const val COLUMNS = 4 }
}
