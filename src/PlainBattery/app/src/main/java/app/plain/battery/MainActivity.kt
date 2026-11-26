package app.plain.battery

import android.app.Activity
import android.app.AppOpsManager
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plain.update.PlainUpdate
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Four tabs, each fact shown once:
 *  - Now: level, state, time left, level chart
 *  - Apps: per-app use as % of the battery, for "last few hours" or "since unplug"
 *  - History: level by day
 *  - Settings: tracking switch, per-app data setup, capacity source, log location
 * Only the visible tab is built. No timers: the screen updates on open, on Refresh and on tab change.
 */
class MainActivity : Activity() {
    private lateinit var tracker: Tracker
    private lateinit var ui: SharedPreferences
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var refresh: TextView
    private val tabViews = ArrayList<TextView>()
    private var tab = TAB_NOW
    private var period = PERIOD_RECENT
    private var busy = false
    private var showAllApps = false
    private var showComponents = false
    private var daysShown = 7
    private val expanded = HashSet<Int>()
    private val toggledDays = HashSet<String>()
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tracker = Tracker(this)
        ui = getSharedPreferences("ui", MODE_PRIVATE)
        tab = ui.getInt("tab", TAB_NOW).coerceIn(0, TAB_NAMES.size - 1)
        period = ui.getInt("period", PERIOD_RECENT).coerceIn(0, 1)
        Tracker.schedule(this)   // no-op while tracking is switched off

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16), px(10), px(12), px(6))
        }
        bar.addView(text("Plain Battery", 20f, WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        refresh = pill("Refresh", false) { update(force = true) }
        bar.addView(refresh, LinearLayout.LayoutParams(WRAP_CONTENT, px(34)))
        root.addView(bar)

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(12), px(4), px(12), px(4)) }
        TAB_NAMES.forEachIndexed { i, name ->
            val v = pill(name, i == tab) { selectTab(i) }
            tabViews += v
            tabs.addView(v, LinearLayout.LayoutParams(0, px(36), 1f).apply {
                if (i < TAB_NAMES.size - 1) marginEnd = px(6)
            })
        }
        root.addView(tabs)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, px(12), px(32)) }
        scroll = ScrollView(this).apply { addView(body); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        render()
        update(force = false)
    }

    private fun selectTab(i: Int) {
        if (i == tab) return
        tab = i
        ui.edit().putInt("tab", i).apply()
        scroll.scrollTo(0, 0)
        render()
        if (i == TAB_APPS) update(force = false)
    }

    /**
     * Take a fresh sample, then redraw. The per-app snapshot (a dumpsys, ~0.2 s CPU) is only
     * forced on the Apps and Settings tabs, and on open only when the last one is over 2 min old.
     */
    private fun update(force: Boolean) {
        if (busy) return
        busy = true
        refresh.isEnabled = false
        refresh.text = "…"
        val wantSnapshot = tab == TAB_APPS || tab == TAB_SETTINGS
        thread {
            try {
                val age = System.currentTimeMillis() - (tracker.latest()?.takenAt ?: 0L)
                tracker.record("ui", forceSnapshot = wantSnapshot && (force || age > 120_000L))
            } catch (_: Exception) {}
            runOnUiThread {
                busy = false
                if (!isDestroyed) { refresh.isEnabled = true; refresh.text = "Refresh"; render() }
            }
        }
    }

    private fun render() {
        tabViews.forEachIndexed { i, v -> stylePill(v, i == tab) }
        body.removeAllViews()
        when (tab) {
            TAB_NOW -> nowTab()
            TAB_APPS -> appsTab()
            TAB_HISTORY -> historyTab()
            else -> settingsTab()
        }
    }

    // ---- Now: level, state, time left, chart ----

    private fun nowTab() {
        val samples = tracker.samples()
        val now = samples.lastOrNull() ?: run {
            card().addView(text("No reading yet.", 14f, LIGHT)); return
        }
        val screen = tracker.screenEvents()
        val c = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("${now.level}%", 44f, levelColor(now.level), bold = true))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0) }
        val unplug = Drain.lastUnplugIndex(samples)
        val state = when {
            now.charging -> "Charging · ${now.plugged.uppercase(Locale.US)}"
            now.status == "full" -> "Full"
            unplug > 0 -> "On battery · " + Drain.formatDuration(now.time - samples[unplug].time)
            else -> "On battery"
        }
        right.addView(text(state, 17f, if (now.charging) GREEN else WHITE, bold = true))
        right.addView(text("%s · %.1f °C · %.2f V".format(Locale.US, Csv.time(now.time).substring(11, 16),
            now.tempDeciC / 10.0, now.voltageMv / 1000.0), 12f, GREY))
        top.addView(right)
        c.addView(top)

        if (!now.charging && now.chargeCounterUah > 0) {
            val remaining = now.chargeCounterUah / 1000.0
            val recent = Rates.recentMa(samples, now.time)
            val left = recent?.let { Rates.hoursLeft(remaining, it.first) }
            if (recent != null && left != null) {
                c.addView(text("≈ ${Rates.formatHours(left)} left", 22f, WHITE, bold = true).apply { setPadding(0, px(10), 0, 0) })
                c.addView(text("at the pace of the last ${Rates.formatHours(recent.second)}", 12f, GREY))
            } else {
                c.addView(text("Time left: after ~20 min on battery", 12f, GREY).apply { setPadding(0, px(10), 0, 0) })
            }
            Rates.standbyMa(samples, screen)?.let { Rates.hoursLeft(remaining, it) }?.let {
                c.addView(text("idle with the screen off: ≈ ${Rates.formatHours(it)}", 12f, GREY))
            }
        }

        val end = System.currentTimeMillis()
        val firstT = samples.firstOrNull { it.time >= end - 36 * 3_600_000L }?.time
        section(if (firstT == null) "Level" else "Level · last " + Drain.formatDuration(end - firstT))
        card().apply {
            addView(ChartView(this@MainActivity).apply { this.samples = samples; this.screen = screen })
            addView(legend())
        }
    }

    // ---- Apps: % of battery per app, one list with a period switch ----

    private fun appsTab() {
        val latest = tracker.latest()
        val samples = tracker.samples()
        if (latest == null) { noAppData(samples); return }
        if (tracker.accessError != null) {
            body.addView(text("Last snapshot failed, showing an older one. See Settings.", 12f, ACCENT).apply {
                setPadding(px(4), px(8), 0, 0)
            })
        }
        val cap = Usage.capacity(latest.capacityMah, samples.lastOrNull())

        val recent = Rates.recent(tracker.history(), latest)
        val charging = samples.lastOrNull()?.charging == true
        val baseline = tracker.baseline()
        val prev = tracker.previousBaseline()
        // Stats are frozen on power: while charging, "since unplug" would be empty, so show the last run.
        val (sinceBase, sinceLabel) = when {
            charging && prev != null && !Checkin.isReset(prev, latest) && Checkin.delta(prev, latest).dischargeMah > 0 ->
                prev to "Last run"
            baseline == null || Checkin.isReset(baseline, latest) -> null to "Since full charge"
            else -> baseline to "Since unplug"
        }

        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, px(10)) }
        val labels = listOf(recent?.let { "Last " + Rates.formatHours(it.batteryHours) } ?: "Last 3 h", sinceLabel)
        labels.forEachIndexed { i, l ->
            seg.addView(pill(l, i == period) {
                if (period != i) { period = i; ui.edit().putInt("period", i).apply(); expanded.clear(); render() }
            }, LinearLayout.LayoutParams(0, px(34), 1f).apply { if (i == 0) marginEnd = px(6) })
        }
        body.addView(seg)

        val d = if (period == PERIOD_RECENT) recent?.delta else Checkin.delta(sinceBase, latest)
        if (d == null) {
            card().addView(text("Needs two snapshots on battery at least 10 min apart. One is taken about every " +
                "hour, and when you open this tab.", 13f, LIGHT))
            return
        }

        val sum = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(4), 0, px(4), px(8)) }
        sum.addView(text("Phone used ", 14f, LIGHT))
        sum.addView(text(Usage.format(d.dischargeMah, cap), 14f, WHITE, bold = true))
        sum.addView(text(" · apps ${Usage.format(d.appsTotal, cap)} (estimate)", 14f, LIGHT))
        body.addView(sum)

        val list = Usage.visibleApps(d, cap)
        if (list.isEmpty()) {
            card().addView(text("Nothing used yet in this period.", 13f, LIGHT))
        } else {
            val c = card()
            val top = list.first().mah
            val me = Process.myUid()
            val shown = if (showAllApps) list else list.take(15)
            for (p in shown) {
                val pkgs = latest.names[p.uid] ?: packageManager.getPackagesForUid(p.uid)?.toList().orEmpty()
                val details = if (p.uid !in expanded) null else buildString {
                    val bg = (p.mah - p.screenMah).coerceAtLeast(0.0)
                    if (p.screenMah >= 0.05) append("screen ${Usage.format(p.screenMah, cap)} · background ${Usage.format(bg, cap)}\n")
                    append("uid ${p.uid}")
                    for (k in pkgs.take(6)) append("\n").append(k)
                    if (pkgs.size > 6) append("\n+${pkgs.size - 6} more")
                }
                appRow(c, icon(pkgs.firstOrNull { tracker.appLabel(it) != null }), tracker.label(p.uid, latest),
                    Usage.format(p.mah, cap), if (cap != null) Usage.formatMah(p.mah) else null,
                    p.mah / top, p.uid == me, details) {
                    if (!expanded.remove(p.uid)) expanded += p.uid
                    render()
                }
            }
            if (list.size > 15) {
                body.addView(button(if (showAllApps) "Show top 15" else "Show all ${list.size}") { showAllApps = !showAllApps; render() })
            }
        }

        val hw = d.hardware.filter { it.value >= 0.05 }.entries.sortedByDescending { it.value }
        if (hw.isNotEmpty()) {
            body.addView(button(if (showComponents) "Hide components" else "By component (screen, radio…)") {
                showComponents = !showComponents; render()
            })
            if (showComponents) {
                val c = card()
                c.addView(text("Whole-phone totals; they overlap the apps.", 11f, GREY).apply { setPadding(0, 0, 0, px(4)) })
                for ((k, v) in hw) {
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4), 0, px(4)) }
                    r.addView(text(HW_NAMES[k] ?: k, 14f, WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    r.addView(text(Usage.format(v, cap), 14f, LIGHT))
                    c.addView(r)
                }
            }
        }
    }

    /** No per-app snapshot at all: point to Settings, show screen time from UsageStats instead. */
    private fun noAppData(samples: List<Sample>) {
        body.addView(text("Per-app battery data needs a one-time setup, see Settings.", 13f, ACCENT).apply {
            setPadding(px(4), px(12), 0, 0)
        })
        val i = Drain.lastUnplugIndex(samples)
        val since = if (i in samples.indices) samples[i].time else System.currentTimeMillis() - 86_400_000L
        val fg = tracker.foregroundMinutes(since)
        if (fg.isEmpty()) return
        section("Screen time since ${Csv.time(since).substring(11, 16)}")
        val c = card()
        val top = fg.first().second
        for ((pkg, min) in fg.take(20)) {
            appRow(c, icon(pkg), tracker.appLabel(pkg) ?: pkg, "%.0f min".format(Locale.US, min), null,
                min / top, false, null) {}
        }
    }

    // ---- History: one card per day ----

    private fun historyTab() {
        val samples = tracker.samples()
        if (samples.isEmpty()) { card().addView(text("No samples yet.", 13f, LIGHT)); return }
        val days = History.days(samples)
        val today = Csv.time(System.currentTimeMillis()).substring(0, 10)
        val yesterday = Csv.time(System.currentTimeMillis() - 86_400_000L).substring(0, 10)
        body.addView(View(this), LinearLayout.LayoutParams(1, px(8)))
        days.take(daysShown).forEachIndexed { idx, day ->
            val open = (idx == 0) xor (day.date in toggledDays)
            val c = card()
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { if (!toggledDays.remove(day.date)) toggledDays += day.date; render() }
            }
            val name = when (day.date) { today -> "Today"; yesterday -> "Yesterday"; else -> dayName(day.date) }
            head.addView(text(name, 15f, WHITE, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            if (day.usedPct > 0) head.addView(text("−${day.usedPct} %", 14f, LIGHT).apply { setPadding(px(10), 0, 0, 0) })
            if (day.chargedPct > 0) head.addView(text("+${day.chargedPct} %", 14f, GREEN).apply { setPadding(px(10), 0, 0, 0) })
            head.addView(text(if (open) "▴" else "▾", 14f, GREY).apply { setPadding(px(12), 0, 0, 0) })
            c.addView(head)
            if (open) {
                c.addView(View(this), LinearLayout.LayoutParams(1, px(6)))
                for (s in day.samples.asReversed()) c.addView(historyRow(s))
            }
        }
        if (days.size > daysShown) body.addView(button("Older days") { daysShown += 7; render() })
        body.addView(text("Level lost (−) and gained (+) per day. Tap a day for its readings.", 11f, GREY).apply {
            setPadding(px(4), px(4), 0, 0)
        })
    }

    private fun historyRow(s: Sample) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(3), 0, px(3))
        addView(text(Csv.time(s.time).substring(11, 16), 13f, LIGHT, mono = true), LinearLayout.LayoutParams(px(60), WRAP_CONTENT))
        addView(text("${s.level}%", 13f, levelColor(s.level), bold = true), LinearLayout.LayoutParams(px(52), WRAP_CONTENT))
        val (state, color) = when {
            s.charging -> "charging · ${s.plugged.uppercase(Locale.US)}" to GREEN
            s.screenOn -> "screen on" to GREY
            else -> "" to GREY
        }
        addView(text(state, 13f, color), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun dayName(date: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        SimpleDateFormat("EEE d MMM", Locale.US).format(d)
    } catch (_: Exception) { date }

    // ---- Settings: tracking switch, setup status, capacity, logs ----

    private fun settingsTab() {
        section("Tracking")
        val on = Tracker.isTracking(this)
        val t = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, px(12), 0) }
        col.addView(text("Background tracking", 16f, WHITE, bold = true))
        col.addView(text(if (on) "A reading every ~15 min and an app snapshot about hourly (~10 s CPU a day)."
            else "Off: nothing runs in the background. Readings are taken only while the app is open.", 12f, GREY))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(pill(if (on) "On" else "Off", on) {
            Tracker.setTracking(this, !on); render()
        }, LinearLayout.LayoutParams(px(64), px(34)))
        t.addView(row)

        section("Per-app data")
        val c = card()
        val err = tracker.accessError
        val latest = tracker.latest()
        val okay = err == null && latest != null
        c.addView(text(when {
            okay -> "Working"
            err != null -> "Not working: $err"
            else -> "No snapshot yet"
        }, 14f, if (okay) GREEN else ACCENT, bold = okay))
        tracker.lastStatus()?.let { c.addView(text(it, 12f, GREY).apply { setPadding(0, px(4), 0, 0) }) }

        val checks = listOf(
            Triple("DUMP", granted("android.permission.DUMP"),
                "adb shell pm grant app.plain.battery android.permission.DUMP"),
            Triple("PACKAGE_USAGE_STATS", granted("android.permission.PACKAGE_USAGE_STATS"),
                "adb shell pm grant app.plain.battery android.permission.PACKAGE_USAGE_STATS"),
            Triple("INTERACT_ACROSS_USERS", granted("android.permission.INTERACT_ACROSS_USERS"),
                "adb shell pm grant app.plain.battery android.permission.INTERACT_ACROSS_USERS"),
            Triple("usage app-op", usageOpAllowed(),
                "adb shell appops set app.plain.battery GET_USAGE_STATS allow"),
        )
        val checkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, 0) }
        for ((name, ok, _) in checks) {
            checkList.addView(text((if (ok) "✓  " else "✗  ") + name, 13f, if (ok) LIGHT else ACCENT, mono = true))
        }
        c.addView(checkList)
        val missing = checks.filter { !it.second }
        if (missing.isNotEmpty() || !okay) {
            c.addView(text("Per-app use needs a one-time grant from a PC with adb (USB or wireless debugging). " +
                "Without it, battery level and charging are still logged.", 12f, GREY).apply { setPadding(0, px(10), 0, 0) })
            c.addView(text("Grant once from a PC:", 13f, LIGHT).apply { setPadding(0, px(10), 0, px(4)) })
            c.addView(text(missing.ifEmpty { checks }.joinToString("\n") { it.third }, 11f, LIGHT, mono = true))
        }

        val cap = Usage.capacity(latest?.capacityMah, tracker.samples(maxBytes = 4096).lastOrNull())
        c.addView(text(when (cap?.source) {
            Usage.Source.BATTERYSTATS -> "Battery capacity %.0f mAh (power profile). App use is shown as %% of it."
                .format(Locale.US, cap.mah)
            Usage.Source.CHARGE_COUNTER -> "Battery capacity ≈ %.0f mAh (charge counter ÷ level). App use is shown as %% of it."
                .format(Locale.US, cap.mah)
            null -> "Battery capacity unknown; app use is shown in mAh."
        }, 12f, GREY).apply { setPadding(0, px(10), 0, 0) })

        section("Logs")
        card().addView(text("${tracker.logDir}\nbattery.csv · screen.csv · apps.csv · status.txt\n" +
            "Pull with adb pull. Each log rotates to *.1.csv at 4 MB.", 12f, LIGHT))

        section("About")
        card().addView(pill(PlainUpdate.label(this), false) { PlainUpdate.check(this) }, LinearLayout.LayoutParams(WRAP_CONTENT, px(34)))
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun usageOpAllowed(): Boolean = try {
        getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) { false }

    // ---- building blocks ----

    private fun px(v: Int) = (v * dp).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            typeface = when { mono -> Typeface.MONOSPACE; bold -> Typeface.DEFAULT_BOLD; else -> Typeface.DEFAULT }
        }

    /** OLED-friendly container: black inside (pixels off), only a thin outline is lit. */
    private fun outline(radiusDp: Float, edge: Int) = GradientDrawable().apply {
        setColor(BLACK); cornerRadius = radiusDp * dp; setStroke(px(1), edge)
    }

    /** Outlined pill used for tabs, the period switch, the tracking switch and Refresh. */
    private fun pill(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; maxLines = 1
        setPadding(px(12), 0, px(12), 0)
        stylePill(this, selected)
        setOnClickListener { onClick() }
    }

    private fun stylePill(v: TextView, selected: Boolean) {
        v.background = outline(17f, if (selected) ACCENT else EDGE)
        v.setTextColor(if (selected) ACCENT else LIGHT)
        v.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun section(title: String) {
        body.addView(text(title.uppercase(Locale.US), 12f, GREY, bold = true).apply {
            letterSpacing = 0.08f; setPadding(px(4), px(18), 0, px(6))
        })
    }

    /** An outlined card appended to the body; returns its content column. */
    private fun card(): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = outline(14f, EDGE)
            setPadding(px(14), px(12), px(14), px(12))
        }
        body.addView(c, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) })
        return c
    }

    private fun bar(fraction: Double, color: Int): View {
        val fill = ClipDrawable(GradientDrawable().apply { setColor(color); cornerRadius = 2 * dp }, Gravity.START, ClipDrawable.HORIZONTAL)
        fill.level = (fraction.coerceIn(0.0, 1.0) * 10000).toInt().coerceAtLeast(150)
        return View(this).apply { background = fill }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; setTextColor(ACCENT); gravity = Gravity.CENTER
        background = outline(14f, EDGE)
        setPadding(0, px(12), 0, px(12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = px(8) }
    }

    private fun icon(pkg: String?): Drawable? = try {
        pkg?.let { packageManager.getApplicationIcon(it) }
    } catch (_: Exception) { null }

    /** One app: icon, name, value; a bar under the name (relative to the biggest row) and a small secondary value. */
    private fun appRow(
        parent: LinearLayout, icon: Drawable?, name: String, value: String, secondary: String?,
        fraction: Double, mine: Boolean, details: String?, onClick: () -> Unit,
    ) {
        val color = if (mine) GREEN else ACCENT
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(7), 0, px(7))
            setOnClickListener { onClick() }
        }
        row.addView(iconView(icon, name), LinearLayout.LayoutParams(px(32), px(32)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(12), 0, 0, 0) }
        val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line1.addView(text(if (mine) "$name (this app)" else name, 15f, if (mine) GREEN else WHITE).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        line1.addView(text(value, 15f, WHITE, bold = true).apply { setPadding(px(8), 0, 0, 0) })
        col.addView(line1)
        val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), 0, 0) }
        line2.addView(bar(fraction, color), LinearLayout.LayoutParams(0, px(4), 1f))
        line2.addView(text(secondary ?: "", 11f, GREY).apply {
            gravity = Gravity.END; setPadding(px(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(px(72), WRAP_CONTENT))
        col.addView(line2)
        if (details != null) col.addView(text(details, 11f, GREY, mono = true).apply { setPadding(0, px(6), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        parent.addView(row)
    }

    private fun iconView(d: Drawable?, name: String): View {
        if (d != null) return ImageView(this).apply { setImageDrawable(d) }
        val letter = name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
        return FrameLayout(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BLACK); setStroke(px(1), EDGE) }
            addView(text(letter.toString(), 15f, LIGHT, bold = true).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
    }

    private fun legend() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(6), 0, 0)
        fun item(color: Int, label: String) {
            addView(View(this@MainActivity).apply { background = GradientDrawable().apply { setColor(color); cornerRadius = 2 * dp } },
                LinearLayout.LayoutParams(px(10), px(10)).apply { gravity = Gravity.CENTER_VERTICAL; marginEnd = px(5) })
            addView(text(label, 11f, GREY).apply { setPadding(0, 0, px(14), 0) })
        }
        item(WHITE, "level"); item(GREEN, "on power"); item(ACCENT, "screen on")
    }

    private fun levelColor(level: Int) = when { level <= 15 -> RED; level <= 30 -> ACCENT; else -> WHITE }

    companion object {
        const val TAB_NOW = 0
        const val TAB_APPS = 1
        const val TAB_HISTORY = 2
        const val TAB_SETTINGS = 3
        val TAB_NAMES = listOf("Now", "Apps", "History", "Settings")
        const val PERIOD_RECENT = 0

        // OLED palette: black everywhere, 1dp outlines, three text greys, one amber accent.
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFE4E4E4.toInt()
        const val LIGHT = 0xFFA6A6A6.toInt()
        const val GREY = 0xFF747474.toInt()
        const val GREEN = 0xFF6CBF6C.toInt()
        const val RED = 0xFFE65C5C.toInt()
        const val ACCENT = 0xFFE6B422.toInt()
        const val EDGE = 0xFF333333.toInt()
        val HW_NAMES = mapOf(
            "cell" to "Mobile radio", "scrn" to "Screen", "idle" to "Idle / sleep", "blue" to "Bluetooth",
            "ambi" to "Always-on display", "gnss" to "GPS", "cpu" to "CPU", "wifi" to "Wi-Fi",
            "audio" to "Audio", "video" to "Video", "camera" to "Camera", "sensors" to "Sensors",
            "other" to "Other", "flashlight" to "Flashlight", "memory" to "Memory", "phone" to "Calls",
        )
    }
}
