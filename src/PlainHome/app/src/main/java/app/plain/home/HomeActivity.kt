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
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import app.plain.ui.Ic
import app.plain.ui.Sheet
import java.text.SimpleDateFormat
import java.util.Date
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
    private lateinit var date: TextView
    private lateinit var toggle: TextView
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            dirty = true
            if (isResumed) reload()
        }
    }

    /** The date line is a plain TextView (in the app's language), so it needs the day to roll over. */
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateDate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
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

        val dateFilter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(dateReceiver, dateFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(dateReceiver, dateFilter)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        updateDate()
        if (dirty) reload()
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        unregisterReceiver(dateReceiver)
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
        date = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            maxLines = 1
            setPadding(dp(12f), 0, 0, 0)
        }
        toggle = TextView(this).apply {
            text = "◐"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = tr("Dark mode", "Koyu mod")
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

    /** Day and month names in the app's language (a TextClock would follow the phone's). */
    private fun updateDate() {
        if (!::date.isInitialized) return
        date.text = SimpleDateFormat("EEEE, d MMMM", PlainLang.locale).format(Date())
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
        toggle.contentDescription = if (dark) tr("Switch to light mode", "Açık moda geç") else tr("Switch to dark mode", "Koyu moda geç")
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
                text = sectionName(s.title)
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
        Toast.makeText(this, tr("Can't open that", "Açılamadı"), Toast.LENGTH_SHORT).show(); false
    } catch (_: SecurityException) {
        Toast.makeText(this, tr("Can't open that", "Açılamadı"), Toast.LENGTH_SHORT).show(); false
    }

    private fun appMenu(@Suppress("UNUSED_PARAMETER") anchor: View, e: AppEntry) {
        val current = Layout.sectionOf(e, overrides(), phoneDefaults)
        val sheet = Sheet(this).header(e.label, tr("In “${sectionName(current)}”", "“${sectionName(current)}” bölümünde")) { iv ->
            try { iv.setImageDrawable(packageManager.getActivityIcon(ComponentName(e.pkg, e.cls))) } catch (_: Exception) {}
            iv.setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
        }
            .item(Ic.INFO, tr("App info", "Uygulama bilgisi"), more = true) { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${e.pkg}"))) }
            .item(Ic.EYE_OFF, tr("Hide", "Gizle"), sub = tr("Long-press the clock to bring it back", "Geri getirmek için saate uzun basın")) {
                prefs.edit().putStringSet("hidden", hidden() + e.key).apply()
                refreshGrid()
                Toast.makeText(this@HomeActivity, tr("Hidden. Long-press the clock to bring it back.", "Gizlendi. Geri getirmek için saate uzun basın."), Toast.LENGTH_LONG).show()
            }
            .section(tr("Move to", "Taşı"))
        Layout.TARGETS.forEach { name ->
            sheet.choice(sectionName(name), name == current) {
                sectionPrefs.edit().putString(e.key, name).apply()
                refreshGrid()
            }
        }
        sheet.show()
    }

    /**
     * Long-press on the clock: show hidden apps (yours and stock), or hide stock apps again.
     * Every variant also has the "Check for updates" button (handled by Plain Setup) and the
     * language row (a dropdown; a change closes the dialog and rebuilds the home screen).
     */
    private fun offerUnhide() {
        val h = hidden()
        val ov = overrides()
        val stockHidden = all.filter { it.pkg != packageName && Layout.isAutoHidden(it, ov, showStock(), phoneDefaults) }
        val names = (h.map { k -> all.firstOrNull { it.key == k }?.label ?: k.substringBefore('/') } +
            stockHidden.map { it.label }).distinct().sorted()
        val sheet = Sheet(this).header("Plain Home", when {
            names.isNotEmpty() -> tr("${names.size} hidden apps", "${names.size} gizli uygulama")
            else -> tr("Nothing is hidden. Long-press an app to move or hide it.", "Gizli uygulama yok. Taşımak ya da gizlemek için uygulamaya uzun basın.")
        })
        if (names.isNotEmpty()) {
            sheet.section(tr("Hidden apps", "Gizli uygulamalar")).note(names.joinToString(", "))
                .item(Ic.EYE, tr("Show all", "Tümünü göster")) {
                    prefs.edit().remove("hidden").putBoolean("showStock", true).apply()
                    refreshGrid()
                }
        } else if (showStock()) {
            sheet.item(Ic.EYE_OFF, tr("Hide stock apps again", "Hazır uygulamaları yeniden gizle"), sub = tr("Those that aren't in a section", "Bir bölümde olmayanlar")) {
                prefs.edit().putBoolean("showStock", false).apply(); refreshGrid()
            }
        }
        sheet.section(tr("App", "Uygulama"))
            .item(Ic.LANGUAGE, tr("Language", "Dil"), value = Sheet.languageValue(), more = true) { Sheet.language(this) { recreate() } }
            .item(Ic.UPDATE, tr("Check for updates", "Güncellemeleri denetle"), value = Sheet.version(this)) { PlainUpdate.check(this) }
            .show()
    }


    /** Section names are the user's own (and stored as keys); only the fallback "Other" is translated. */
    private fun sectionName(name: String): String = if (name == Layout.OTHER) tr("Other", "Diğer") else name

    private companion object { const val COLUMNS = 4 }
}
