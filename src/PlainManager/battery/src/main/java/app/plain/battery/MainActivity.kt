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
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import app.plain.ui.Ic
import app.plain.ui.Sheet
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
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        if (Hub.gate(this, "battery")) { finish(); return }  // old Plain Battery's history first
        Tracker.firstOpen(this)
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
        refresh = pill(tr("Refresh", "Yenile"), false) { update(force = true) }
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
        setContentView(Hub.wrap(this, "battery", root))
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
                if (!isDestroyed) { refresh.isEnabled = true; refresh.text = tr("Refresh", "Yenile"); render() }
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
            card().addView(text(tr("No reading yet.", "Henüz ölçüm yok."), 14f, LIGHT)); return
        }
        val screen = tracker.screenEvents()
        val c = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("${now.level}%", 44f, levelColor(now.level), bold = true))
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, 0, 0) }
        val unplug = Drain.lastUnplugIndex(samples)
        val state = when {
            now.charging -> tr("Charging", "Şarjda") + " · " + pluggedName(now.plugged)
            now.status == "full" -> tr("Full", "Dolu")
            unplug > 0 -> tr("On battery · ", "Pilde · ") + Drain.formatDuration(now.time - samples[unplug].time)
            else -> tr("On battery", "Pilde")
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
                c.addView(text(tr("≈ ${Rates.formatHours(left)} left", "≈ ${Rates.formatHours(left)} kaldı"), 22f, WHITE, bold = true).apply { setPadding(0, px(10), 0, 0) })
                c.addView(text(tr("at the pace of the last ${Rates.formatHours(recent.second)}",
                    "son ${Rates.formatHours(recent.second)} içindeki hıza göre"), 12f, GREY))
            } else {
                c.addView(text(tr("Time left: after ~20 min on battery", "Kalan süre: pilde ~20 dk sonra"), 12f, GREY).apply { setPadding(0, px(10), 0, 0) })
            }
            Rates.standbyMa(samples, screen)?.let { Rates.hoursLeft(remaining, it) }?.let {
                c.addView(text(tr("idle with the screen off: ≈ ${Rates.formatHours(it)}",
                    "ekran kapalı beklemede: ≈ ${Rates.formatHours(it)}"), 12f, GREY))
            }
        }

        val end = System.currentTimeMillis()
        val firstT = samples.firstOrNull { it.time >= end - 36 * 3_600_000L }?.time
        section(if (firstT == null) tr("Level", "Seviye") else tr("Level · last ", "Seviye · son ") + Drain.formatDuration(end - firstT))
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
            body.addView(text(tr("Last snapshot failed, showing an older one. See Settings.",
                "Son ölçüm alınamadı, eskisi gösteriliyor. Ayarlar'a bakın."), 12f, ACCENT).apply {
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
                prev to tr("Last run", "Son kullanım")
            baseline == null || Checkin.isReset(baseline, latest) -> null to tr("Since full charge", "Tam şarjdan beri")
            else -> baseline to tr("Since unplug", "Şarjdan çıkalı")
        }

        val seg = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(10), 0, px(10)) }
        val labels = listOf(recent?.let { tr("Last ", "Son ") + Rates.formatHours(it.batteryHours) } ?: tr("Last 3 h", "Son 3 sa"), sinceLabel)
        labels.forEachIndexed { i, l ->
            seg.addView(pill(l, i == period) {
                if (period != i) { period = i; ui.edit().putInt("period", i).apply(); expanded.clear(); render() }
            }, LinearLayout.LayoutParams(0, px(34), 1f).apply { if (i == 0) marginEnd = px(6) })
        }
        body.addView(seg)

        val d = if (period == PERIOD_RECENT) recent?.delta else Checkin.delta(sinceBase, latest)
        if (d == null) {
            card().addView(text(tr("Needs two snapshots on battery at least 10 min apart. One is taken about every " +
                "hour, and when you open this tab.",
                "Pildeyken en az 10 dk arayla iki ölçüm gerekir. Yaklaşık saatte bir ve bu sekme " +
                "açıldığında bir ölçüm alınır."), 13f, LIGHT))
            return
        }

        val sum = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(px(4), 0, px(4), px(8)) }
        sum.addView(text(tr("Phone used ", "Telefon tüketimi "), 14f, LIGHT))
        sum.addView(text(Usage.format(d.dischargeMah, cap), 14f, WHITE, bold = true))
        sum.addView(text(tr(" · apps ${Usage.format(d.appsTotal, cap)} (estimate)", " · uygulamalar ${Usage.format(d.appsTotal, cap)} (tahmini)"), 14f, LIGHT))
        body.addView(sum)

        val list = Usage.visibleApps(d, cap)
        if (list.isEmpty()) {
            card().addView(text(tr("Nothing used yet in this period.", "Bu dönemde henüz tüketim yok."), 13f, LIGHT))
        } else {
            val c = card()
            val top = list.first().mah
            val me = Process.myUid()
            val shown = if (showAllApps) list else list.take(15)
            for (p in shown) {
                val pkgs = latest.names[p.uid] ?: packageManager.getPackagesForUid(p.uid)?.toList().orEmpty()
                val details = if (p.uid !in expanded) null else buildString {
                    val bg = (p.mah - p.screenMah).coerceAtLeast(0.0)
                    if (p.screenMah >= 0.05) append(tr("screen ${Usage.format(p.screenMah, cap)} · background ${Usage.format(bg, cap)}\n",
                        "ekran ${Usage.format(p.screenMah, cap)} · arka plan ${Usage.format(bg, cap)}\n"))
                    append("uid ${p.uid}")
                    for (k in pkgs.take(6)) append("\n").append(k)
                    if (pkgs.size > 6) append(tr("\n+${pkgs.size - 6} more", "\n+${pkgs.size - 6} daha"))
                }
                appRow(c, icon(pkgs.firstOrNull { tracker.appLabel(it) != null }), tracker.label(p.uid, latest),
                    Usage.format(p.mah, cap), if (cap != null) Usage.formatMah(p.mah) else null,
                    p.mah / top, p.uid == me, details) {
                    if (!expanded.remove(p.uid)) expanded += p.uid
                    render()
                }
            }
            if (list.size > 15) {
                body.addView(button(if (showAllApps) tr("Show top 15", "İlk 15'i göster") else tr("Show all ${list.size}", "Tümünü göster (${list.size})")) { showAllApps = !showAllApps; render() })
            }
        }

        val hw = d.hardware.filter { it.value >= 0.05 }.entries.sortedByDescending { it.value }
        if (hw.isNotEmpty()) {
            body.addView(button(if (showComponents) tr("Hide components", "Bileşenleri gizle") else tr("By component (screen, radio…)", "Bileşene göre (ekran, radyo…)")) {
                showComponents = !showComponents; render()
            })
            if (showComponents) {
                val c = card()
                c.addView(text(tr("Whole-phone totals; they overlap the apps.", "Tüm telefonun toplamları; uygulamalarla örtüşür."), 11f, GREY).apply { setPadding(0, 0, 0, px(4)) })
                for ((k, v) in hw) {
                    val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, px(4), 0, px(4)) }
                    r.addView(text(hwNames()[k] ?: k, 14f, WHITE), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                    r.addView(text(Usage.format(v, cap), 14f, LIGHT))
                    c.addView(r)
                }
            }
        }
    }

    /** No per-app snapshot at all: point to Settings, show screen time from UsageStats instead. */
    private fun noAppData(samples: List<Sample>) {
        body.addView(text(tr("Per-app battery data needs a one-time setup, see Settings.",
            "Uygulama başına pil verisi tek seferlik kurulum ister, Ayarlar'a bakın."), 13f, ACCENT).apply {
            setPadding(px(4), px(12), 0, 0)
        })
        val i = Drain.lastUnplugIndex(samples)
        val since = if (i in samples.indices) samples[i].time else System.currentTimeMillis() - 86_400_000L
        val fg = tracker.foregroundMinutes(since)
        if (fg.isEmpty()) return
        section(tr("Screen time since ", "Ekran süresi · başlangıç ") + Csv.time(since).substring(11, 16))
        val c = card()
        val top = fg.first().second
        for ((pkg, min) in fg.take(20)) {
            appRow(c, icon(pkg), tracker.appLabel(pkg) ?: pkg, tr("%.0f min", "%.0f dk").format(Locale.US, min), null,
                min / top, false, null) {}
        }
    }

    // ---- History: one card per day ----

    private fun historyTab() {
        val samples = tracker.samples()
        if (samples.isEmpty()) { card().addView(text(tr("No samples yet.", "Henüz ölçüm yok."), 13f, LIGHT)); return }
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
            val name = when (day.date) { today -> tr("Today", "Bugün"); yesterday -> tr("Yesterday", "Dün"); else -> dayName(day.date) }
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
        if (days.size > daysShown) body.addView(button(tr("Older days", "Önceki günler")) { daysShown += 7; render() })
        body.addView(text(tr("Level lost (−) and gained (+) per day. Tap a day for its readings.",
            "Günlük kaybedilen (−) ve kazanılan (+) seviye. Ölçümler için bir güne dokunun."), 11f, GREY).apply {
            setPadding(px(4), px(4), 0, 0)
        })
    }

    private fun historyRow(s: Sample) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, px(3), 0, px(3))
        addView(text(Csv.time(s.time).substring(11, 16), 13f, LIGHT, mono = true), LinearLayout.LayoutParams(px(60), WRAP_CONTENT))
        addView(text("${s.level}%", 13f, levelColor(s.level), bold = true), LinearLayout.LayoutParams(px(52), WRAP_CONTENT))
        val (state, color) = when {
            s.charging -> tr("charging", "şarjda") + " · " + pluggedName(s.plugged) to GREEN
            s.screenOn -> tr("screen on", "ekran açık") to GREY
            else -> "" to GREY
        }
        addView(text(state, 13f, color), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
    }

    private fun dayName(date: String): String = try {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date)!!
        SimpleDateFormat("EEE d MMM", PlainLang.locale).format(d)
    } catch (_: Exception) { date }

    /** How the phone is plugged in, for the UI (the logs keep the raw "ac" / "usb" / ...). */
    private fun pluggedName(p: String): String = PlainLang.upper(when (p) {
        "wireless" -> tr("wireless", "kablosuz")
        "dock" -> tr("dock", "yuva")
        else -> p
    })

    // ---- Settings: tracking switch, setup status, capacity, logs ----

    private fun settingsTab() {
        section(tr("Tracking", "İzleme"))
        val on = Tracker.isTracking(this)
        val t = card()
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, px(12), 0) }
        col.addView(text(tr("Background tracking", "Arka planda izleme"), 16f, WHITE, bold = true))
        col.addView(text(if (on) tr("A reading every ~15 min and an app snapshot about hourly (~10 s CPU a day).",
                "~15 dk'da bir ölçüm, yaklaşık saatte bir uygulama ölçümü (günde ~10 sn CPU).")
            else tr("Off: nothing runs in the background. Readings are taken only while the app is open.",
                "Kapalı: arka planda hiçbir şey çalışmaz. Ölçüm yalnızca uygulama açıkken alınır."), 12f, GREY))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(pill(if (on) tr("On", "Açık") else tr("Off", "Kapalı"), on) {
            Tracker.setTracking(this, !on); render()
        }, LinearLayout.LayoutParams(px(64), px(34)))
        t.addView(row)

        section(tr("Per-app data", "Uygulama başına veri"))
        val c = card()
        val err = tracker.accessError
        val latest = tracker.latest()
        val okay = err == null && latest != null
        c.addView(text(when {
            okay -> tr("Working", "Çalışıyor")
            err != null -> tr("Not working: ", "Çalışmıyor: ") + err
            else -> tr("No snapshot yet", "Henüz ölçüm yok")
        }, 14f, if (okay) GREEN else ACCENT, bold = okay))
        tracker.lastStatus()?.let { c.addView(text(it, 12f, GREY).apply { setPadding(0, px(4), 0, 0) }) }

        val checks = listOf(
            Triple("DUMP", granted("android.permission.DUMP"),
                "adb shell pm grant $packageName android.permission.DUMP"),
            Triple("PACKAGE_USAGE_STATS", granted("android.permission.PACKAGE_USAGE_STATS"),
                "adb shell pm grant $packageName android.permission.PACKAGE_USAGE_STATS"),
            Triple("INTERACT_ACROSS_USERS", granted("android.permission.INTERACT_ACROSS_USERS"),
                "adb shell pm grant $packageName android.permission.INTERACT_ACROSS_USERS"),
            Triple("usage app-op", usageOpAllowed(),
                "adb shell appops set $packageName GET_USAGE_STATS allow"),
        )
        val checkList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, px(8), 0, 0) }
        for ((name, ok, _) in checks) {
            checkList.addView(text((if (ok) "✓  " else "✗  ") + name, 13f, if (ok) LIGHT else ACCENT, mono = true))
        }
        c.addView(checkList)
        val missing = checks.filter { !it.second }
        if (missing.isNotEmpty() || !okay) {
            c.addView(text(tr("Per-app use needs a one-time grant from a PC with adb (USB or wireless debugging). " +
                "Without it, battery level and charging are still logged.",
                "Uygulama başına kullanım için adb'li bir bilgisayardan tek seferlik izin gerekir (USB veya " +
                "kablosuz hata ayıklama). İzin olmadan da pil seviyesi ve şarj kaydedilir."), 12f, GREY).apply { setPadding(0, px(10), 0, 0) })
            c.addView(text(tr("Grant once from a PC:", "Bilgisayardan bir kez izin verin:"), 13f, LIGHT).apply { setPadding(0, px(10), 0, px(4)) })
            c.addView(text(missing.ifEmpty { checks }.joinToString("\n") { it.third }, 11f, LIGHT, mono = true))
        }

        val cap = Usage.capacity(latest?.capacityMah, tracker.samples(maxBytes = 4096).lastOrNull())
        c.addView(text(when (cap?.source) {
            Usage.Source.BATTERYSTATS -> tr("Battery capacity %.0f mAh (power profile). App use is shown as %% of it.",
                "Pil kapasitesi %.0f mAh (güç profili). Uygulama kullanımı bunun yüzdesi olarak gösterilir.")
                .format(Locale.US, cap.mah)
            Usage.Source.CHARGE_COUNTER -> tr("Battery capacity ≈ %.0f mAh (charge counter ÷ level). App use is shown as %% of it.",
                "Pil kapasitesi ≈ %.0f mAh (şarj sayacı ÷ seviye). Uygulama kullanımı bunun yüzdesi olarak gösterilir.")
                .format(Locale.US, cap.mah)
            null -> tr("Battery capacity unknown; app use is shown in mAh.",
                "Pil kapasitesi bilinmiyor; uygulama kullanımı mAh olarak gösterilir.")
        }, 12f, GREY).apply { setPadding(0, px(10), 0, 0) })

        section(tr("Logs", "Kayıtlar"))
        card().addView(text("${tracker.logDir}\nbattery.csv · screen.csv · apps.csv · status.txt\n" +
            tr("Pull with adb pull. Each log rotates to *.1.csv at 4 MB.",
                "adb pull ile alın. Her kayıt 4 MB olunca *.1.csv olarak yenilenir."), 12f, LIGHT))

        section(tr("About", "Hakkında"))
        val about = card()
        about.addView(pill(PlainUpdate.label(this), false) { PlainUpdate.check(this) }, LinearLayout.LayoutParams(WRAP_CONTENT, px(34)))
        // Language dropdown under the pill; a change recreates the activity, which reopens on this tab.
        var lang: TextView? = null
        lang = pill(PlainLang.label(), false) { Sheet.language(this, ACCENT) }
        about.addView(lang, LinearLayout.LayoutParams(WRAP_CONTENT, px(34)).apply { topMargin = px(8) })
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
        body.addView(text(PlainLang.upper(title), 12f, GREY, bold = true).apply {
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
        line1.addView(text(if (mine) tr("$name (this app)", "$name (bu uygulama)") else name, 15f, if (mine) GREEN else WHITE).apply {
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
        item(WHITE, tr("level", "seviye")); item(GREEN, tr("on power", "şarjda")); item(ACCENT, tr("screen on", "ekran açık"))
    }

    private fun levelColor(level: Int) = when { level <= 15 -> RED; level <= 30 -> ACCENT; else -> WHITE }

    companion object {
        const val TAB_NOW = 0
        const val TAB_APPS = 1
        const val TAB_HISTORY = 2
        const val TAB_SETTINGS = 3
        /** A getter, so the names follow the language chosen after the class was loaded. */
        val TAB_NAMES get() = listOf(tr("Now", "Şimdi"), tr("Apps", "Uygulama"), tr("History", "Geçmiş"), tr("Settings", "Ayarlar"))
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
        /** Display names of the batterystats hardware keys (the keys stay as logged). */
        fun hwNames() = mapOf(
            "cell" to tr("Mobile radio", "Mobil radyo"), "scrn" to tr("Screen", "Ekran"),
            "idle" to tr("Idle / sleep", "Boşta / uyku"), "blue" to "Bluetooth",
            "ambi" to tr("Always-on display", "Her zaman açık ekran"), "gnss" to "GPS", "cpu" to tr("CPU", "İşlemci"),
            "wifi" to "Wi-Fi", "audio" to tr("Audio", "Ses"), "video" to "Video", "camera" to tr("Camera", "Kamera"),
            "sensors" to tr("Sensors", "Sensörler"), "other" to tr("Other", "Diğer"),
            "flashlight" to tr("Flashlight", "Fener"), "memory" to tr("Memory", "Bellek"), "phone" to tr("Calls", "Aramalar"),
        )
    }
}
