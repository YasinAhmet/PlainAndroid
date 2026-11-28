package app.plain.notes

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import app.plain.lang.PlainLang
import app.plain.lang.tr
import java.util.Calendar

/**
 * A bottom panel drawn like app.plain.ui.Sheet (black, rounded top, outline, handle) for pickers that need their own
 * controls: the time-stacking "Remind me" picker and the routine schedule editor.
 */
class Panel(private val a: Activity) {
    val dialog = Dialog(a)
    val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20), 0, a.dp(20), a.dp(16)) }
    private val main = Handler(Looper.getMainLooper())
    private var tick: Runnable? = null

    fun title(t: String, sub: String? = null): Panel {
        body.addView(a.text(t, 18f, C.TEXT, bold = true).apply { setPadding(0, 0, 0, if (sub == null) a.dp(10) else 0) })
        if (sub != null) body.addView(a.text(sub, 13f, C.LABEL, lines = 2).apply { setPadding(0, a.dp(2), 0, a.dp(10)) })
        return this
    }

    /** Calls [fn] every [ms] while the panel is up (a live "in 5 min"); nothing runs once it's closed. */
    fun every(ms: Long, fn: () -> Unit): Panel {
        val r = object : Runnable { override fun run() { if (!dialog.isShowing) return; fn(); main.postDelayed(this, ms) } }
        tick = r
        return this
    }

    fun dismiss() { try { dialog.dismiss() } catch (_: Exception) {} }

    fun show() {
        if (a.isFinishing || a.isDestroyed) return
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(C.BG)
                val r = a.dpf(22)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(a.dp(1), C.OUTLINE_BTN)
            }
        }
        root.addView(View(a).apply { background = GradientDrawable().apply { setColor(0xFF3A3A3A.toInt()); cornerRadius = a.dpf(2) } },
            LinearLayout.LayoutParams(a.dp(36), a.dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = a.dp(10); bottomMargin = a.dp(12) })
        val scroll = object : ScrollView(a) {
            override fun onMeasure(w: Int, h: Int) {
                var max = (resources.displayMetrics.heightPixels * 0.85f).toInt()
                if (MeasureSpec.getMode(h) != MeasureSpec.UNSPECIFIED) max = minOf(max, MeasureSpec.getSize(h))
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max.coerceAtLeast(0), MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(body) }
        root.addView(scroll)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnDismissListener { main.removeCallbacksAndMessages(null) }
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            navigationBarColor = C.BG
        }
        try { dialog.show() } catch (_: Exception) { return }
        tick?.let { main.postDelayed(it, 15_000) }
    }
}

/** An outlined button for a row of equal buttons; [onHold] (with a haptic) is the hidden "take away" of a step. */
fun Activity.stepButton(label: String, color: Int = C.TEXT, stroke: Int = C.OUTLINE_BTN, onHold: (() -> Unit)? = null, onTap: () -> Unit) =
    text(label, 14f, color, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(4), 0, dp(4), 0)
        background = ripple(outline(dpf(22), stroke))
        setOnClickListener { onTap() }
        if (onHold != null) setOnLongClickListener { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); onHold(); true }
    }

/** A row of equal-width views with small gaps. */
fun Activity.buttonRow(vararg views: View?, top: Int = 8): LinearLayout = LinearLayout(this).apply {
    views.filterNotNull().forEachIndexed { i, v ->
        addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(6) })
    }
    layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
}

/**
 * "Remind me": stack time with +1 d / +1 h / +15 m / +5 m / +1 m (each tap adds, from now or from the time already
 * set; holding a button takes that much away), see the time and "in 1 h 16 min" live, or pick a date / type a time.
 * [onPick] gets the time, or 0 for "No reminder".
 */
fun Activity.remindPicker(current: Long, onPick: (Long) -> Unit) {
    val p = Panel(this)
    var at = if (current > System.currentTimeMillis()) current else 0L
    val big = text("", 26f, C.ACCENT, bold = true).apply { fontFeatureSettings = "tnum" }
    val rel = text("", 14f, C.DIM, lines = 2).apply { setPadding(0, dp(2), 0, dp(4)) }
    lateinit var set: TextView
    fun refresh() {
        val now = System.currentTimeMillis()
        if (at in 1..now) at = 0
        if (at > 0) {
            big.text = When.label(at, now); big.setTextColor(C.ACCENT)
            rel.text = Stack.left(at, now)
        } else {
            big.text = tr("Not set", "Ayarlı değil"); big.setTextColor(C.FAINT)
            rel.text = tr("Tap below to add time from now", "Şimdiden itibaren süre eklemek için aşağıya dokunun")
        }
        set.enable(at > 0)
    }
    p.title(tr("Remind me", "Hatırlat"))
    p.body.addView(big)
    p.body.addView(rel)
    p.body.addView(buttonRow(*Stack.REMIND.map { s ->
        stepButton(s.label, onHold = { at = Stack.add(at, System.currentTimeMillis(), s, -1); refresh() }) {
            at = Stack.add(at, System.currentTimeMillis(), s, 1); refresh()
        }
    }.toTypedArray()))
    p.body.addView(text(tr("Each tap adds. Hold a button to take it away.", "Her dokunuş ekler. Geri almak için düğmeyi basılı tutun."),
        12.5f, C.LABEL, lines = 2).apply { setPadding(0, dp(8), 0, 0) })
    p.body.addView(buttonRow(
        stepButton(tr("Clear", "Sıfırla"), C.DIM) { at = 0; refresh() },
        stepButton(tr("Date…", "Tarih…"), C.DIM) { p.dismiss(); pickDate(if (at > 0) at else current, onPick) },
        stepButton(tr("Type…", "Yaz…"), C.DIM) { p.dismiss(); askTime(onPick) },
        top = 10))
    set = stepButton(tr("Set", "Ayarla"), C.ACCENT, C.ACCENT) {
        if (at <= System.currentTimeMillis()) { Toast.makeText(this, tr("That time has passed.", "Bu zaman geçti."), Toast.LENGTH_SHORT).show(); refresh() }
        else { p.dismiss(); onPick(at) }
    }
    p.body.addView(buttonRow(
        if (current > 0) stepButton(tr("No reminder", "Hatırlatıcı yok"), C.RED, C.OUTLINE_BTN) { p.dismiss(); onPick(0) } else null,
        set, top = 18))
    refresh()
    p.every(15_000) { refresh() }
    p.show()
}

/** A typed time: "20 min", "18:30", "friday 9"; a number alone is minutes. */
fun Activity.askTime(onPick: (Long) -> Unit) {
    val e = EditText(this).apply {
        hint = tr("e.g. 20 min, 18:30, tomorrow 9", "ör. 20 dk, 18:30, yarın 9")
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setTextColor(C.TEXT)
    }
    val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
    val d = AlertDialog.Builder(this)
        .setTitle(tr("Remind me", "Hatırlat"))
        .setMessage(tr("A number alone is minutes.", "Yalnızca sayı = dakika."))
        .setView(box)
        .setPositiveButton(tr("Set", "Ayarla")) { _, _ ->
            val t = e.text.toString().trim()
            val now = System.currentTimeMillis()
            val at = t.replace(',', '.').toDoubleOrNull()?.let { m -> if (m > 0 && m <= 43_200) now + (m * 60_000).toLong() else null }
                ?: When.find(t, now)?.at
            if (at == null) Toast.makeText(this, tr("That isn't a time I know.", "Bu zamanı anlayamadım."), Toast.LENGTH_SHORT).show() else onPick(at)
        }
        .setNegativeButton(tr("Cancel", "İptal"), null)
        .create()
    d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    d.show()
    e.requestFocus()
}

/** The calendar, then the clock. */
fun Activity.pickDate(current: Long, onPick: (Long) -> Unit) {
    val c = Calendar.getInstance().apply {
        if (current > 0) timeInMillis = current else { add(Calendar.HOUR_OF_DAY, 1); set(Calendar.MINUTE, 0) }
    }
    DatePickerDialog(this, { _, y, m, d ->
        TimePickerDialog(this, { _, h, min ->
            val at = Calendar.getInstance().apply { set(y, m, d, h, min, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            if (at <= System.currentTimeMillis()) Toast.makeText(this, tr("That time has passed.", "Bu zaman geçti."), Toast.LENGTH_SHORT).show() else onPick(at)
        }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
        datePicker.minDate = System.currentTimeMillis() - 1000
    }.show()
}

/**
 * A routine's schedule: "At set times" (one or more times of day) or "Evenly" (N times from a start to an end), and
 * the weekdays. Times are stacked like the reminder picker (+1 h / +15 m / +5 m / +1 m from the current value,
 * wrapping at midnight; hold = take away); "Exact…" opens the clock.
 */
fun Activity.scheduleEditor(title: String, initial: Plan, onSave: (Plan) -> Unit) {
    val p = Panel(this)
    var even = initial.even
    val times = initial.times.map { Math.floorMod(it, 1440) }.distinct().sorted().toMutableList().ifEmpty { mutableListOf(9 * 60) }
    var count = initial.count.coerceIn(1, Sched.MAX_COUNT)
    var start = initial.start
    var end = initial.end
    var days = initial.days.takeIf { (it and Sched.ALL_DAYS) != 0 } ?: Sched.ALL_DAYS
    var sel = 0   // times mode: index in times; evenly: 0 count, 1 start, 2 end
    p.title(title)
    val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    p.body.addView(content)

    fun plan() = Plan(even, times.toList(), count, start, end, days)

    fun chip(label: String, on: Boolean, onHold: (() -> Unit)? = null, fn: () -> Unit) = stepButton(label,
        if (on) C.ACCENT else C.DIM, if (on) C.ACCENT else C.OUTLINE_BTN, onHold, fn).apply { setPadding(dp(14), 0, dp(14), 0) }

    lateinit var render: () -> Unit
    fun setTime(v: Int) {
        val m = Math.floorMod(v, 1440)
        if (even) { if (sel == 1) start = m else end = m }
        else {
            // another time already there: step past it so the list keeps one of each
            var x = m
            while (times.indices.any { it != sel && times[it] == x }) x = Math.floorMod(x + 1, 1440)
            times[sel] = x
        }
    }
    fun curTime() = if (even) (if (sel == 1) start else end) else times[sel]

    render = {
        content.removeAllViews()
        content.addView(buttonRow(
            chip(tr("At set times", "Belirli saatlerde"), !even) { if (even) { even = false; sel = 0; render() } },
            chip(tr("Evenly spread", "Eşit aralıkla"), even) { if (!even) { even = true; sel = 0; render() } },
            top = 0))
        // the fields: the times (tap to pick, hold to remove) or count / from / to
        val fields = LinearLayout(this)
        if (even) {
            listOf(tr("$count×", "$count kez"), tr("from ", "başlangıç ") + Stack.hhmm(start), tr("to ", "bitiş ") + Stack.hhmm(end))
                .forEachIndexed { i, l -> fields.addView(chip(l, sel == i) { sel = i; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) }) }
        } else {
            times.forEachIndexed { i, t ->
                fields.addView(chip(Stack.hhmm(t), sel == i, onHold = {
                    if (times.size > 1) { times.removeAt(i); sel = sel.coerceAtMost(times.size - 1); render() }
                }) { sel = i; render() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
            }
            if (times.size < Sched.MAX_COUNT) fields.addView(chip(tr("+ Add", "+ Ekle"), false) {
                var x = Math.floorMod(times.last() + 60, 1440)
                while (x in times) x = Math.floorMod(x + 1, 1440)
                times += x; times.sort(); sel = times.indexOf(x); render()
            }, LinearLayout.LayoutParams(-2, -2))
        }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(fields) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        // the selected field's value, and its buttons
        val countSel = even && sel == 0
        content.addView(text(if (countSel) tr("$count times a day", "günde $count kez") else Stack.hhmm(curTime()), 26f, C.ACCENT, bold = true).apply {
            fontFeatureSettings = "tnum"; setPadding(0, dp(12), 0, 0)
        })
        if (countSel) {
            content.addView(buttonRow(*listOf(1, 2, 4).map { n ->
                stepButton("+$n", onHold = { count = (count - n).coerceAtLeast(1); render() }) { count = (count + n).coerceAtMost(Sched.MAX_COUNT); render() }
            }.toTypedArray(), stepButton(tr("Clear", "Sıfırla"), C.DIM) { count = 1; render() }))
        } else {
            content.addView(buttonRow(*Stack.CLOCK.map { s ->
                stepButton(s.label, onHold = { setTime(Stack.clock(curTime(), -s.minutes)); render() }) { setTime(Stack.clock(curTime(), s.minutes)); render() }
            }.toTypedArray()))
            content.addView(buttonRow(
                stepButton(tr("Clear", "Sıfırla"), C.DIM) { setTime(0); render() },
                stepButton(tr("Exact…", "Tam saat…"), C.DIM) {
                    val t = curTime()
                    TimePickerDialog(this, { _, h, m -> setTime(h * 60 + m); render() }, t / 60, t % 60, true).show()
                },
                if (!even && times.size > 1) stepButton(tr("Remove", "Kaldır"), C.RED) { times.removeAt(sel); sel = sel.coerceAtMost(times.size - 1); render() } else null))
        }
        content.addView(text(tr("Each tap adds. Hold a button to take it away.", "Her dokunuş ekler. Geri almak için düğmeyi basılı tutun."),
            12.5f, C.LABEL, lines = 2).apply { setPadding(0, dp(8), 0, 0) })
        // what it comes to
        val m = plan().minutes
        val preview = if (m.size <= 8) m.joinToString(" · ") { Stack.hhmm(it) } else m.take(7).joinToString(" · ") { Stack.hhmm(it) } + " … " + Stack.hhmm(m.last())
        content.addView(text(preview, 13.5f, C.DIM, lines = 3).apply { fontFeatureSettings = "tnum"; setPadding(0, dp(10), 0, 0) })
        // the weekdays
        content.addView(text(PlainLang.upper(tr("Days", "Günler")) + " · " + Sched.daysLabel(days), 12f, C.LABEL, bold = true).apply {
            letterSpacing = 0.08f; setPadding(0, dp(16), 0, 0)
        })
        content.addView(buttonRow(*Sched.ORDER.map { dow ->
            val bit = 1 shl (dow - 1)
            chip(Sched.dayName(dow), (days and bit) != 0,
                onHold = { days = if (days == Sched.ALL_DAYS) bit else Sched.ALL_DAYS; render() }) {
                val nd = days xor bit
                if (nd != 0) { days = nd; render() }
            }.apply { setPadding(0, 0, 0, 0); textSize = 12.5f }
        }.toTypedArray(), top = 6).apply { (getChildAt(0)?.layoutParams as? LinearLayout.LayoutParams)?.marginStart = 0 })
        content.addView(buttonRow(
            stepButton(tr("Cancel", "İptal"), C.DIM) { p.dismiss() },
            stepButton(tr("Save", "Sakla"), C.ACCENT, C.ACCENT) { p.dismiss(); onSave(plan()) },
            top = 18))
    }
    render()
    p.show()
}
