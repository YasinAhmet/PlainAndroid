package app.plain.clock

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.text.InputType
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.ui.Ic
import app.plain.ui.Sheet
import app.plain.update.PlainUpdate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.min

/**
 * Clock tab: Focus (pomodoro), Timer and Stopwatch, picked in the thumb bar. One big dial with the time in the middle,
 * the controls under it in thumb reach. All state lives in [Engine]; this screen only draws it and ticks while it is
 * visible (nothing runs here once it's paused).
 *
 * Hidden gestures: swipe the dial sideways = next/previous mode; double-tap the time = start/pause;
 * hold the big button = reset; hold the time in Timer = type a length; hold "+1 min" = +5 min.
 */
class ClockActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var mode = Engine.POMO

    private lateinit var nowTime: TextView
    private lateinit var nowDate: TextView
    private lateinit var dial: Dial
    private lateinit var phase: TextView
    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var dots: LinearLayout
    private lateinit var presets: HorizontalScrollView
    private lateinit var laps: LinearLayout
    private lateinit var lapScroll: ScrollView
    private lateinit var warn: TextView
    private lateinit var left: TextView
    private lateinit var right: TextView
    private lateinit var go: GoButton
    private val tabs = LinkedHashMap<String, TextView>()

    private val hm by lazy { SimpleDateFormat("HH:mm", PlainLang.locale) }
    private val dateFmt by lazy { SimpleDateFormat("EEEE d MMMM", PlainLang.locale) }

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            draw()
            main.postDelayed(this, nextTick())
        }
    }

    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        if (Hub.gate(this, "clock")) { finish(); return }
        mode = Engine.prefs(this).getString("mode", Engine.POMO)?.takeIf { it in listOf(Engine.POMO, Engine.TIMER, Engine.WATCH) } ?: Engine.POMO

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }

        // ---- the time of day, quiet, on top
        nowTime = text("", 34f, C.DIM).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        nowDate = text("", 13f, C.LABEL).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        root.addView(nowTime, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        root.addView(nowDate, LinearLayout.LayoutParams(-1, -2))

        // ---- the dial
        val center = FrameLayout(this)
        dial = Dial(this)
        center.addView(dial, FrameLayout.LayoutParams(-1, -1))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        phase = text("", 14f, C.DIM).apply { gravity = Gravity.CENTER }
        big = text("", 46f, C.TEXT).apply {
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        sub = text("", 13f, C.LABEL, lines = 2).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(6), dp(24), 0) }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        mid.addView(phase, LinearLayout.LayoutParams(-1, -2))
        mid.addView(big, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        mid.addView(sub, LinearLayout.LayoutParams(-1, -2))
        mid.addView(dots, LinearLayout.LayoutParams(-1, -2))
        center.addView(mid, FrameLayout.LayoutParams(-1, -1))
        root.addView(center, LinearLayout.LayoutParams(-1, 0, 1f))
        gestures(center)

        // ---- timer presets / laps (just above the controls)
        val chips = LinearLayout(this).apply { setPadding(dp(14), 0, dp(14), 0) }
        for (m in listOf(1, 3, 5, 10, 15, 20, 30, 45, 60)) {
            chips.addView(pill(tr("$m min", "$m dk")) { startTimer(m * 60_000L) },
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(8) })
        }
        chips.addView(pill(tr("Custom…", "Özel…")) { askLength() }, LinearLayout.LayoutParams(-2, dp(44)))
        presets = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        root.addView(presets, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })

        laps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), 0) }
        lapScroll = ScrollView(this).apply { addView(laps); isVerticalScrollBarEnabled = false }
        root.addView(lapScroll, LinearLayout.LayoutParams(-1, dp(150)))

        warn = text(tr("Notifications are off, so timers can't ring. Tap to turn them on.",
            "Bildirimler kapalı, bu yüzden sayaçlar çalamaz. Açmak için dokunun."), 12.5f, C.ACCENT, lines = 3).apply {
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(4), dp(20), dp(8))
            // all notifications off: the app's page; only this mode's channel off: that channel's page
            setOnClickListener { openNotificationSettings(if (Engine.canNotify(this@ClockActivity)) modeChannel() else null) }
        }
        root.addView(warn, LinearLayout.LayoutParams(-1, -2))

        // ---- controls
        val controls = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(12)) }
        left = pill("") { onLeft() }
        right = pill("") { onRight() }
        right.setOnLongClickListener { onRightHold() }
        go = GoButton(this).apply {
            setOnClickListener { onGo() }
            setOnLongClickListener { onLeft(); true }
        }
        controls.addView(left, LinearLayout.LayoutParams(dp(112), dp(44)))
        controls.addView(go, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginStart = dp(20); marginEnd = dp(20) })
        controls.addView(right, LinearLayout.LayoutParams(dp(112), dp(44)))
        root.addView(controls, LinearLayout.LayoutParams(-1, -2))

        setContentView(Hub.wrap(this, "clock", root))

        // ---- thumb bar: the three modes and ⋮
        Hub.bar(this)?.let { bar ->
            for ((k, label) in listOf(Engine.POMO to tr("Focus", "Odak"), Engine.TIMER to tr("Timer", "Sayaç"), Engine.WATCH to tr("Stopwatch", "Kronometre"))) {
                val t = text(label, 14f, C.DIM).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(4), 0, dp(4), 0)
                    background = ripple(null, outline(dpf(12)))
                    setOnClickListener { setMode(k) }
                }
                tabs[k] = t
                bar.addView(t, LinearLayout.LayoutParams(0, dp(44), 1f))
            }
            bar.addView(ImageView(this).apply {
                setImageResource(app.plain.shared.R.drawable.sheet_ic_tune)
                imageTintList = android.content.res.ColorStateList.valueOf(C.TEXT)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(null, ring(C.BG))
                contentDescription = tr("Settings", "Ayarlar")
                setOnClickListener { settings() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        }
        // "set a timer" once: not again when Android recreates this screen (a language change, or after the process
        // was ended in the background), which would start the timer over
        if (b == null) handle(intent)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    /** "Set a timer" from another app or the assistant. */
    private fun handle(i: Intent?) {
        when (i?.action) {
            AlarmClock.ACTION_SET_TIMER -> {
                val sec = i.getIntExtra(AlarmClock.EXTRA_LENGTH, 0)
                setMode(Engine.TIMER)
                if (sec in 1..86_400) startTimer(sec * 1000L)
            }
            AlarmClock.ACTION_SHOW_TIMERS -> setMode(Engine.TIMER)
        }
    }

    override fun onStart() {
        super.onStart()
        Engine.listener = { draw() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        draw()
        main.removeCallbacks(tick)
        main.postDelayed(tick, nextTick())
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        Engine.listener = null
        super.onStop()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ drawing

    /** How long until something on screen changes: tenths for a running stopwatch, the next second, else the next minute. */
    private fun nextTick(): Long {
        val now = System.currentTimeMillis()
        val toMinute = 60_000 - now % 60_000 + 20
        return when (mode) {
            Engine.WATCH -> if (Engine.watch(this).running) 100 else toMinute
            Engine.POMO -> Engine.pomoCd(this).let { if (it.running) (it.remaining(now) % 1000).coerceIn(20, 1000) + 5 else toMinute }
            else -> Engine.timerCd(this).let { if (it.running) (it.remaining(now) % 1000).coerceIn(20, 1000) + 5 else toMinute }
        }
    }

    /** The channel that rings at the end in this mode. */
    private fun modeChannel() = if (mode == Engine.TIMER) Engine.CH_TIMER else Engine.CH_POMO

    private fun setMode(m: String) {
        if (m == mode && ::big.isInitialized) { draw(); return }
        mode = m
        Engine.prefs(this).edit().putString("mode", m).apply()
        draw()
        main.removeCallbacks(tick)
        if (resumed) main.postDelayed(tick, nextTick())
    }

    private fun draw() {
        if (!::big.isInitialized) return
        val now = System.currentTimeMillis()
        // an end whose alarm hasn't come yet (Android may hold back an inexact alarm): catch up while it's on screen
        if (Engine.pomoCd(this).let { it.running && it.endAt < now - 1500 }) Engine.onEnd(this, Engine.POMO)
        if (Engine.timerCd(this).let { it.running && it.endAt < now - 1500 }) Engine.onEnd(this, Engine.TIMER)
        // (the formatters keep the zone they were made in; the phone's may have changed while this tab lived)
        TimeZone.getDefault().let { if (hm.timeZone != it) { hm.timeZone = it; dateFmt.timeZone = it } }
        nowTime.text = hm.format(Date(now))
        nowDate.text = dateFmt.format(Date(now))

        val pc = Engine.pomoCd(this)
        val tc = Engine.timerCd(this)
        val w = Engine.watch(this)
        for ((k, t) in tabs) {
            val busy = when (k) { Engine.POMO -> !pc.idle && !(pc.paused && pc.left == pc.total); Engine.TIMER -> !tc.idle || Engine.ringing(this); else -> !w.idle }
            val sel = k == mode
            t.setTextColor(if (sel) C.ACCENT else C.DIM)
            t.setTypeface(null, if (sel) Typeface.BOLD else Typeface.NORMAL)
            val label = t.text.toString().removeSuffix(" •")
            t.text = if (busy && !sel) "$label •" else label
        }
        presets.visibility = View.GONE
        lapScroll.visibility = View.GONE
        dots.visibility = View.GONE
        sub.setTextColor(C.LABEL)
        var running = false
        when (mode) {
            Engine.POMO -> {
                val cfg = Engine.config(this)
                val p = Engine.pomo(this)
                val cd = if (pc.idle) Countdown.ready(cfg.ms(p.phase)) else pc
                running = cd.running
                phase.text = if (p.phase == Phase.WORK) tr("Focus · ${p.round + 1} of ${cfg.every}", "Odak · ${p.round + 1} / ${cfg.every}") else p.phase.label
                phase.setTextColor(if (p.phase == Phase.WORK) C.DIM else C.ACCENT)
                big.text = Fmt.countdown(cd.remaining(now))
                big.setTextColor(if (cd.paused && cd.left != cd.total) C.DIM else C.TEXT)
                val done = Engine.today(this)
                sub.text = when {
                    cd.running -> tr("Until ${hm.format(Date(cd.endAt))}", "Bitiş ${hm.format(Date(cd.endAt))}")
                    cd.left != cd.total -> tr("Paused", "Duraklatıldı")
                    done > 0 -> tr("$done done today", "Bugün $done tamamlandı")
                    else -> tr("Tap ▶ to focus", "Odaklanmak için ▶")
                }
                dial.set(cd.progress(now))
                showDots(p.round, cfg.every, p.phase)
                left.text = tr("Reset", "Sıfırla")
                left.enable(!(pc.idle && p == Pomo()))
                right.text = tr("Skip", "Atla")
                right.enable(true)
            }
            Engine.TIMER -> {
                val ringing = Engine.ringing(this)
                val cd = if (tc.idle) Countdown.ready(Engine.lastTimer(this)) else tc
                running = cd.running
                phase.text = tr("Timer", "Sayaç")
                phase.setTextColor(C.DIM)
                big.text = Fmt.countdown(cd.remaining(now))
                big.setTextColor(if (cd.paused && !tc.idle) C.DIM else C.TEXT)
                sub.text = when {
                    ringing -> tr("Time's up", "Süre doldu")
                    cd.running -> tr("Rings at ${hm.format(Date(cd.endAt))}", "Çalma ${hm.format(Date(cd.endAt))}")
                    !tc.idle -> tr("Paused", "Duraklatıldı")
                    else -> tr("Pick a length or tap ▶", "Bir süre seçin ya da ▶")
                }
                if (ringing) sub.setTextColor(C.ACCENT)
                dial.set(if (tc.idle) 0f else cd.progress(now))
                presets.visibility = if (tc.idle) View.VISIBLE else View.GONE
                left.text = if (ringing) tr("Stop alarm", "Alarmı kapat") else tr("Reset", "Sıfırla")
                left.enable(ringing || !tc.idle)
                right.text = tr("+1 min", "+1 dk")
                right.enable(true)
            }
            else -> {
                val el = w.elapsed(now)
                running = w.running
                phase.text = tr("Stopwatch", "Kronometre")
                phase.setTextColor(C.DIM)
                big.text = Fmt.stopwatch(el)
                big.setTextColor(if (!w.running && !w.idle) C.DIM else C.TEXT)
                sub.text = when {
                    w.running -> if (w.laps.isEmpty()) "" else tr("Lap ${w.laps.size + 1}: ", "Tur ${w.laps.size + 1}: ") + Fmt.stopwatch(el - w.laps.last())
                    w.idle -> tr("Tap ▶ to start", "Başlatmak için ▶")
                    else -> tr("Paused", "Duraklatıldı")
                }
                dial.set(if (w.idle) 0f else (el % 60_000) / 60_000f)
                showLaps(w)
                left.text = tr("Reset", "Sıfırla")
                left.enable(!w.running && !w.idle)
                right.text = tr("Lap", "Tur")
                right.enable(w.running)
            }
        }
        go.playing = running
        go.contentDescription = if (running) tr("Pause", "Duraklat") else tr("Start", "Başlat")
        // (before Android 13+ has asked, the first Start asks: no warning yet)
        val asked = Build.VERSION.SDK_INT < 33 || Engine.prefs(this).getBoolean("asked_notify", false)
        warn.visibility = if (Engine.canNotify(this, modeChannel()) || mode == Engine.WATCH || !asked) View.GONE else View.VISIBLE
        val keep = running && Engine.prefs(this).getBoolean("keep_on", false)
        if (keep) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** One small dot per focus session of the cycle: done ones in the accent (indicators, 7 dp). */
    private fun showDots(round: Int, every: Int, ph: Phase) {
        dots.visibility = View.VISIBLE
        if (dots.childCount != every) {
            dots.removeAllViews()
            repeat(every) { dots.addView(View(this), LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginStart = dp(4); marginEnd = dp(4) }) }
        }
        val done = if (ph == Phase.LONG) every else round
        for (i in 0 until every) dots.getChildAt(i).background = if (i < done) android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.ACCENT)
        } else ring(C.FAINT)
    }

    private var shownLaps = -1

    private fun showLaps(w: Stopwatch) {
        lapScroll.visibility = if (w.laps.isEmpty()) View.GONE else View.VISIBLE
        if (w.laps.size == shownLaps) return
        shownLaps = w.laps.size
        laps.removeAllViews()
        val splits = w.laps.mapIndexed { i, t -> t - (if (i == 0) 0 else w.laps[i - 1]) }
        val best = splits.minOrNull()
        val worst = splits.maxOrNull()
        for (i in w.laps.indices.reversed()) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(36) }
            val color = when { splits.size < 2 -> C.DIM; splits[i] == best -> C.ACCENT; splits[i] == worst -> C.LABEL; else -> C.DIM }
            row.addView(text(tr("Lap ${i + 1}", "Tur ${i + 1}"), 14f, C.LABEL), LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(Fmt.stopwatch(splits[i]), 14f, color).apply { fontFeatureSettings = "tnum" }, LinearLayout.LayoutParams(-2, -2))
            row.addView(text(Fmt.stopwatch(w.laps[i]), 14f, C.LABEL).apply { fontFeatureSettings = "tnum"; gravity = Gravity.END },
                LinearLayout.LayoutParams(dp(110), -2))
            laps.addView(row)
        }
    }

    // ------------------------------------------------------------------ actions

    private fun onGo() {
        when (mode) {
            Engine.POMO -> if (Engine.pomoCd(this).running) Engine.pomoPause(this) else { askNotify(); Engine.pomoStart(this) }
            Engine.TIMER -> {
                val tc = Engine.timerCd(this)
                when {
                    tc.running -> Engine.timerPause(this)
                    tc.paused -> Engine.timerResume(this)
                    else -> startTimer(Engine.lastTimer(this))
                }
            }
            else -> Engine.watchToggle(this)
        }
    }

    private fun onLeft() {
        when (mode) {
            Engine.POMO -> Engine.pomoReset(this)
            Engine.TIMER -> if (Engine.ringing(this)) Engine.silence(this) else Engine.timerReset(this)
            else -> if (!Engine.watch(this).running) Engine.watchReset(this)
        }
    }

    private fun onRight() {
        when (mode) {
            Engine.POMO -> Engine.pomoSkip(this)
            Engine.TIMER -> { askNotify(); Engine.timerAdd(this, 60_000) }
            else -> Engine.watchLap(this)
        }
    }

    /** Hold "+1 min" in Timer: +5 min (hold is the bigger version of a tap). Elsewhere the tap's own action. */
    private fun onRightHold(): Boolean {
        if (mode != Engine.TIMER) return false
        right.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        askNotify()
        Engine.timerAdd(this, 5 * 60_000L)
        toast(tr("+5 min", "+5 dk"))
        return true
    }

    private fun startTimer(ms: Long) {
        askNotify()
        Engine.timerStart(this, ms)
    }

    /** Android 13+: ask once for notifications (the running countdown and "time's up" are notifications). */
    private fun askNotify() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = Engine.prefs(this)
        if (p.getBoolean("asked_notify", false)) return
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty()) Engine.prefs(this).edit().putBoolean("asked_notify", true).apply()   // answered (not interrupted)
        Engine.refreshNotifications(this)
        draw()
    }

    private fun openNotificationSettings(channel: String?) {
        Engine.channels(this)
        val i = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        try { startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } catch (_: Exception) {}
    }

    /** A typed timer length ("5", "1:30", "1h 20m", "90s"). */
    private fun askLength() {
        val e = EditText(this).apply {
            hint = tr("e.g. 7, 1:30, 1h 20m", "ör. 7, 1:30, 1sa 20dk")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setTextColor(C.TEXT)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        val d = AlertDialog.Builder(this)
            .setTitle(tr("Timer length", "Sayaç süresi"))
            .setMessage(tr("A number is minutes.", "Yalnızca sayı = dakika."))
            .setView(box)
            .setPositiveButton(tr("Start", "Başlat")) { _, _ ->
                val ms = Fmt.parseLength(e.text.toString())
                if (ms == null) toast(tr("That isn't a length.", "Bu bir süre değil.")) else startTimer(ms)
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }

    private fun gestures(v: View) {
        val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val dx = e2.x - (e1?.x ?: return false)
                if (abs(dx) < dp(60) || abs(vx) < abs(vy)) return false
                val order = listOf(Engine.POMO, Engine.TIMER, Engine.WATCH)
                val i = order.indexOf(mode) + if (dx < 0) 1 else -1
                if (i in order.indices) setMode(order[i])
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean { onGo(); return true }
            override fun onLongPress(e: MotionEvent) {
                if (mode == Engine.TIMER) { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); askLength() }
            }
        })
        v.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    }

    // ------------------------------------------------------------------ settings

    private fun minutesRow(s: Sheet, label: String, sub: String, value: Int, def: Int, options: List<Int>, set: (Int) -> Unit) {
        val fmt = { m: Int -> tr("$m min", "$m dk") }
        s.item(Ic.HISTORY, label, sub = sub, value = fmt(value), more = true, reset = Sheet.Reset(fmt(def)) { set(def) }) {
            Sheet.choose(this, label, options.map(fmt), options.indexOf(value), C.ACCENT) { set(options[it]) }
        }
    }

    private fun settings() {
        val cfg = Engine.config(this)
        val upd = { f: (PomoConfig) -> PomoConfig -> Engine.setConfig(this, f(Engine.config(this))) }
        val s = Sheet(this, C.ACCENT).header(tr("Clock", "Saat"), tr("Focus, timer and stopwatch", "Odak, sayaç ve kronometre"))
            .section(tr("Focus (pomodoro)", "Odak (pomodoro)"))
        minutesRow(s, tr("Focus length", "Odak süresi"), tr("One work session", "Bir çalışma oturumu"), cfg.work, 25,
            listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 60, 90)) { m -> upd { it.copy(work = m) } }
        minutesRow(s, tr("Short break", "Kısa mola"), tr("After each focus session", "Her odak oturumundan sonra"), cfg.short, 5,
            listOf(2, 3, 5, 7, 10, 15)) { m -> upd { it.copy(short = m) } }
        minutesRow(s, tr("Long break", "Uzun mola"), tr("After a full cycle", "Tam bir döngüden sonra"), cfg.long, 15,
            listOf(10, 15, 20, 25, 30, 45)) { m -> upd { it.copy(long = m) } }
        val everyOpts = listOf(2, 3, 4, 5, 6)
        val everyFmt = { n: Int -> tr("Every $n", "Her $n") }
        s.item(Ic.SYNC, tr("Long break after", "Uzun mola sıklığı"), sub = tr("Focus sessions in a cycle", "Bir döngüdeki odak oturumu"),
            value = everyFmt(cfg.every), more = true, reset = Sheet.Reset(everyFmt(4)) { upd { it.copy(every = 4) } }) {
            Sheet.choose(this, tr("Long break after", "Uzun mola sıklığı"), everyOpts.map(everyFmt), everyOpts.indexOf(cfg.every), C.ACCENT) { i ->
                upd { it.copy(every = everyOpts[i]) }
            }
        }
        s.toggle(Ic.PLAY_NEXT, tr("Start the next phase by itself", "Sonraki aşama kendiliğinden başlasın"), cfg.auto,
            sub = tr("Off: a chime, then you tap Start", "Kapalı: bir uyarı, sonra Başlat'a siz dokunursunuz")) { on -> upd { it.copy(auto = on) } }
            .section(tr("Sound and screen", "Ses ve ekran"))
            .item(Ic.BELL, tr("Timer alarm sound", "Sayaç alarm sesi"), sub = tr("Android's sound settings for “Time's up”", "“Süre doldu” için Android ses ayarları"), more = true) {
                openNotificationSettings(Engine.CH_TIMER)
            }
            .item(Ic.BELL, tr("Pomodoro chime", "Pomodoro uyarısı"), sub = tr("When a focus session or break ends", "Odak ya da mola bitince"), more = true) {
                openNotificationSettings(Engine.CH_POMO)
            }
            .toggle(Ic.EYE, tr("Keep the screen on while running", "Çalışırken ekran açık kalsın"), Engine.prefs(this).getBoolean("keep_on", false),
                sub = tr("Only while this tab is open", "Yalnızca bu sekme açıkken")) { on ->
                Engine.prefs(this).edit().putBoolean("keep_on", on).apply(); draw()
            }
            .section(null)
            .item(Ic.LANGUAGE, tr("Language", "Dil"), value = Sheet.languageValue(), more = true, reset = Sheet.languageReset(this)) { Sheet.language(this, C.ACCENT) }
            .item(Ic.UPDATE, tr("Check for updates", "Güncellemeleri denetle"), value = Sheet.version(this)) { PlainUpdate.check(this) }
            .show()
    }
}

/** The ring around the time: an OUTLINE track and a 3 dp accent arc for progress (an indicator, never a fill). */
class Dial(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private var frac = 0f
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = C.OUTLINE_BTN }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3 * d; color = C.ACCENT; strokeCap = Paint.Cap.ROUND }
    private val box = RectF()

    fun set(f: Float) { if (f != frac) { frac = f; invalidate() } }

    override fun onDraw(c: Canvas) {
        val r = min(min(width, height) / 2f - 12 * d, 150 * d)
        if (r <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        c.drawCircle(cx, cy, r, track)
        if (frac > 0f) {
            box.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(box, -90f, 360f * frac, false, arc)
        }
    }
}

/** The big round start/pause button: an outlined ring with the glyph in the accent. */
class GoButton(ctx: Context) : View(ctx) {
    var playing = false
        set(v) { if (field != v) { field = v; invalidate() } }
    private val d = resources.displayMetrics.density
    private val ringP = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = C.ACCENT }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = C.ACCENT }
    private val path = Path()

    init {
        isClickable = true
        isFocusable = true
        background = ripple(null, android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(C.BG) })
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 2 * d
        c.drawCircle(cx, cy, r, ringP)
        val s = r * 0.36f
        if (playing) {
            val w = s * 0.38f
            c.drawRoundRect(cx - s * 0.75f, cy - s, cx - s * 0.75f + w, cy + s, 2 * d, 2 * d, glyph)
            c.drawRoundRect(cx + s * 0.75f - w, cy - s, cx + s * 0.75f, cy + s, 2 * d, 2 * d, glyph)
        } else {
            path.reset()
            path.moveTo(cx - s * 0.6f, cy - s)
            path.lineTo(cx + s, cy)
            path.lineTo(cx - s * 0.6f, cy + s)
            path.close()
            c.drawPath(path, glyph)
        }
    }
}
