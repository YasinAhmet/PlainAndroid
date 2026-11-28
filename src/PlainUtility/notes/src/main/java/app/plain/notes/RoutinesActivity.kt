package app.plain.notes

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
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
import java.util.Calendar
import app.plain.shared.R as SR

/**
 * Routines tab: things that come round every day, at set times or a number of times spread evenly through the day,
 * on chosen weekdays. Type one in the thumb bar and send: the schedule editor opens (or pick it first with the clock
 * button). Rows show today's progress and the next time; the ✓ ticks the current (or latest due) time. Ticks belong
 * to the day, so they reset at midnight; routines never go away by themselves. Tap or hold a row for its sheet
 * (today's times, edit, schedule, pause, delete). Each routine's next time is one exact alarm; nothing else runs.
 */
class RoutinesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var routines: List<Routine> = emptyList()
    private var page = 0

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var chip: TextView
    private lateinit var field: EditText
    private lateinit var clockBtn: ImageView
    private lateinit var action: ImageView
    private val rows = HashMap<Long, View>()

    /** The composer's schedule, picked with the clock button before sending. */
    private var pending: Plan? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            show()   // "next today 13:00", what's due, and the new day's empty ticks
            main.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        if (Hub.gate(this, "routines")) { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val frame = FrameLayout(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        empty = text(tr("No routines yet.\n\nWrite something you do every day, like “Drink water” or “Stretch”, and pick when: at set times, or a number of times spread through the day.",
            "Henüz rutin yok.\n\n“Su iç” ya da “Esneme” gibi her gün yaptığınız bir şeyi yazın ve zamanını seçin: belirli saatlerde ya da gün boyunca eşit aralıklarla."),
            15f, C.DIM, lines = 0).apply { gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0); visibility = View.GONE }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        chip = text("", 13.5f, C.ACCENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(36)
            setPadding(dp(14), 0, dp(14), 0)
            background = ripple(outline(dpf(18), C.ACCENT))
            setOnClickListener { pending = null; updateChip() }
            visibility = View.GONE
        }
        root.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); bottomMargin = dp(6) })

        setContentView(Hub.wrap(this, "routines", root))

        Hub.titleEnd(this)?.addView(text("⋮", 22f, C.DIM).apply {
            gravity = Gravity.CENTER
            contentDescription = tr("Settings", "Ayarlar")
            background = ripple(null, ring(C.BG))
            setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        Hub.bar(this)?.let { bar ->
            field = EditText(this).apply {
                hint = tr("New routine…", "Yeni rutin…")
                setHintTextColor(C.LABEL)
                setTextColor(C.TEXT)
                textSize = 15f
                maxLines = 4
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                minHeight = dp(44)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = outline(dpf(22), C.OUTLINE_BTN)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { updateAction() }
                })
            }
            clockBtn = ImageView(this).apply {
                setImageResource(SR.drawable.sheet_ic_history)
                imageTintList = ColorStateList.valueOf(C.DIM)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(null, ring(C.BG))
                contentDescription = tr("When, each day", "Her gün ne zaman")
                setOnClickListener {
                    scheduleEditor(tr("When, each day", "Her gün ne zaman"), pending ?: guess(field.text.toString())) { pending = it; updateChip() }
                }
            }
            action = ImageView(this).apply {
                setImageResource(R.drawable.nts_ic_send)
                setPadding(dp(11), dp(11), dp(11), dp(11))
                contentDescription = tr("Add routine", "Rutin ekle")
                setOnClickListener { send() }
            }
            bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(clockBtn, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            bar.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        updateAction()
        b?.getString("draft")?.let { field.setText(it) }
        page = b?.getInt("page") ?: 0
        if (b == null) handle(intent)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (::field.isInitialized) out.putString("draft", field.text.toString())
        out.putInt("page", page)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    /** A routine's notification: show it (its page, scrolled to). */
    private fun handle(i: Intent?) {
        if (i?.action == ACTION_SHOW) {
            val id = i.getLongExtra(EXTRA_ID, -1)
            load()
            val at = sorted(System.currentTimeMillis()).indexOfFirst { it.id == id }
            if (at >= 0) { page = at / PAGE; show(); scrollTo(id) }
        }
        setIntent(Intent())
    }

    override fun onStart() {
        super.onStart()
        Routines.listener = { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        main.removeCallbacks(tick)
        main.postDelayed(tick, 30_000L)
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        Routines.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun cantSave() = Toast.makeText(this, tr("Couldn't save. Is the phone's storage full?",
        "Saklanamadı. Telefonun depolama alanı dolu mu?"), Toast.LENGTH_LONG).show()

    private fun scrollTo(id: Long) = main.post { rows[id]?.let { r -> scroll.smoothScrollTo(0, (r.top - dp(40)).coerceAtLeast(0)) } }

    // ------------------------------------------------------------------ list

    private fun load() {
        routines = Routines.all(this)
        show()
    }

    /** Running ones by their next time, then the paused ones. */
    private fun sorted(now: Long) = routines.sortedWith(compareBy<Routine>({ it.paused }, { Sched.next(it, now)?.at ?: Long.MAX_VALUE }, { it.created }))

    private fun show() {
        if (!::list.isInitialized) return
        val now = System.currentTimeMillis()
        list.removeAllViews()
        rows.clear()
        val all = sorted(now)
        val pages = pageCount(all.size, PAGE)
        page = page.coerceIn(0, pages - 1)
        all.drop(page * PAGE).take(PAGE).forEach { list.addView(row(it, now)) }
        if (pages > 1) list.addView(pager(page, pages) { page = it; show(); scroll.scrollTo(0, 0) })
        empty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(r: Routine, now: Long): View {
        val today = Sched.today(r, now)
        val doneSet = if (today.isEmpty()) emptySet() else r.doneOn(today.first().day)
        val doneN = today.count { it.min in doneSet }
        val target = Sched.target(r, now)
        val due = target != null && target.at <= now
        val allDone = today.isNotEmpty() && doneN == today.size
        val next = Sched.next(r, now)

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(58)
            setPadding(dp(20), dp(8), dp(8), dp(8))
            background = ripple(null, android.graphics.drawable.ColorDrawable(C.BG))
            setOnClickListener { routineSheet(r) }
            setOnLongClickListener { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); routineSheet(r); true }
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(text(r.text.trim().ifEmpty { r.title }, 15f, if (r.paused) C.LABEL else C.TEXT, lines = 3))
        val parts = ArrayList<String>()
        when {
            r.paused -> parts += tr("Paused", "Duraklatıldı") + " · " + r.plan.describe()
            today.isEmpty() -> { parts += tr("Not today", "Bugün yok"); next?.let { parts += tr("next ", "sıradaki ") + When.label(it.at, now) } }
            else -> {
                if (today.size > 1) parts += tr("$doneN/${today.size} today", "bugün $doneN/${today.size}")
                else if (allDone) parts += tr("Done today", "Bugün tamam")
                if (due) parts += tr("due ", "zamanı geldi ") + Stack.hhmm(target!!.min)
                else next?.let { parts += tr("next ", "sıradaki ") + When.label(it.at, now) }
            }
        }
        mid.addView(text(parts.joinToString(" · "), 12.5f, if (due) C.ACCENT else C.LABEL, lines = 1).apply {
            setPadding(0, dp(3), 0, 0)
            fontFeatureSettings = "tnum"
            if (due) setTypeface(null, Typeface.BOLD)
        })
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        if (!r.paused && today.isNotEmpty()) {
            row.addView(ImageView(this).apply {
                setImageResource(SR.drawable.sheet_ic_check)
                imageTintList = ColorStateList.valueOf(if (due || allDone) C.ACCENT else C.FAINT)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(if (allDone) ring(C.ACCENT) else null, ring(C.BG))
                contentDescription = if (allDone) tr("Done today. Tap to untick the last one.", "Bugün tamam. Sonuncunun işaretini kaldırmak için dokunun.")
                    else tr("Tick off", "İşaretle")
                setOnClickListener { check(r) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        } else row.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        rows[r.id] = row
        return row
    }

    /** The row's ✓: ticks the latest due time not done (or the next one today, early); all done: unticks the last. */
    private fun check(r: Routine) {
        val now = System.currentTimeMillis()
        val t = Sched.target(r, now)
        if (t != null) {
            if (!Routines.mark(this, r.id, t.day, t.min, true)) cantSave()
            else if (t.at > now + 60_000) toast(tr("Ticked ${Stack.hhmm(t.min)} early", "${Stack.hhmm(t.min)} erkenden işaretlendi"))
            return
        }
        val today = Sched.today(r, now)
        if (today.isEmpty()) return
        val day = today.first().day
        val last = r.doneOn(day).maxOrNull() ?: return
        if (!Routines.mark(this, r.id, day, last, false)) cantSave() else toast(tr("Unticked ${Stack.hhmm(last)}", "${Stack.hhmm(last)} işareti kaldırıldı"))
    }

    // ------------------------------------------------------------------ composer

    /** A schedule to start from: a time of day written in the text ("water 10:00"), else the next full hour. */
    private fun guess(t: String): Plan {
        val now = System.currentTimeMillis()
        val at = if (t.isBlank()) null else When.find(t, now)?.takeIf { !it.timer }?.at
        val c = Calendar.getInstance().apply { timeInMillis = at ?: (now + 3_600_000L) }
        return Plan(times = listOf(c.get(Calendar.HOUR_OF_DAY) * 60 + if (at != null) c.get(Calendar.MINUTE) else 0))
    }

    private fun updateChip() {
        val p = pending
        if (p == null) { chip.visibility = View.GONE; clockBtn.imageTintList = ColorStateList.valueOf(C.DIM); return }
        chip.text = tr("Daily · ", "Her gün · ") + p.describe() + "   ✕"
        chip.contentDescription = tr("Schedule set. Tap to remove.", "Zamanlama ayarlı. Kaldırmak için dokunun.")
        chip.visibility = View.VISIBLE
        clockBtn.imageTintList = ColorStateList.valueOf(C.ACCENT)
    }

    private fun updateAction() {
        if (!::action.isInitialized) return
        val on = field.text.isNotBlank()
        action.imageTintList = ColorStateList.valueOf(if (on) C.ACCENT else C.FAINT)
        action.background = ripple(ring(if (on) C.ACCENT else C.OUTLINE_BTN), ring(C.BG))
    }

    private fun send() {
        val t = field.text.toString().trim()
        if (t.isEmpty()) { field.requestFocus(); toast(tr("Write what to do first.", "Önce ne yapılacağını yazın.")); return }
        val p = pending
        if (p != null) { save(t, p); return }
        scheduleEditor(tr("When, each day", "Her gün ne zaman"), guess(t)) { plan ->
            // (the text as it is now: the field may have been changed while the editor was open)
            save(field.text.toString().trim().ifEmpty { t }, plan)
        }
    }

    private fun save(t: String, p: Plan) {
        val r = Routine(id = Routines.newId(this), text = t, plan = p, created = System.currentTimeMillis())
        if (!Routines.put(this, r)) { cantSave(); return }
        field.setText("")
        pending = null
        updateChip()
        askNotify()
        val at = sorted(System.currentTimeMillis()).indexOfFirst { it.id == r.id }
        if (at >= 0) { page = at / PAGE; show() }
        scrollTo(r.id)
    }

    // ------------------------------------------------------------------ sheets

    private fun routineSheet(r0: Routine) {
        val r = Routines.get(this, r0.id) ?: return
        val now = System.currentTimeMillis()
        val today = Sched.today(r, now)
        val target = Sched.target(r, now)
        val s = Sheet(this, C.ACCENT).header(r.title, tr("Daily · ", "Her gün · ") + r.plan.describe())
            .actions(
                if (!r.paused && target != null) Sheet.Action(Ic.CHECK, tr("Tick ", "İşaretle ") + Stack.hhmm(target.min)) { check(r) } else null,
                Sheet.Action(Ic.EDIT, tr("Edit", "Düzenle")) { editText(r) },
                Sheet.Action(Ic.HISTORY, tr("Schedule", "Zamanlama")) { editPlan(r) },
                if (r.paused) Sheet.Action(Ic.PLAY, tr("Resume", "Sürdür")) { setPaused(r, false) }
                else Sheet.Action(Ic.PAUSE, tr("Pause", "Duraklat")) { setPaused(r, true) },
            )
        if (!r.paused && today.isNotEmpty()) {
            s.section(tr("Today", "Bugün"))
            val done = r.doneOn(today.first().day)
            for (o in today) {
                val sub = when {
                    o.min in done -> null
                    o.at <= now -> tr("due", "zamanı geldi")
                    else -> When.left(o.at, now)
                }
                s.toggle(null, Stack.hhmm(o.min), o.min in done, sub) { on -> if (!Routines.mark(this, r.id, o.day, o.min, on)) cantSave() }
            }
        } else if (!r.paused) s.note(tr("Not on today's days. ", "Bugün günleri arasında değil. ") +
            (Sched.next(r, now)?.let { tr("Next: ", "Sıradaki: ") + When.label(it.at, now) } ?: ""))
        s.section(null)
            .item(Ic.COPY, tr("Copy text", "Metni kopyala")) {
                getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(r.title, r.text))
                if (Build.VERSION.SDK_INT < 33) toast(tr("Copied", "Kopyalandı"))
            }
            .section(null)
            .item(Ic.DELETE, tr("Delete…", "Sil…"), danger = true) { confirmDelete(r) }
            .show()
    }

    private fun setPaused(r: Routine, on: Boolean) {
        val cur = Routines.get(this, r.id) ?: return
        if (on) Routines.cancelNotification(this, cur)
        if (!Routines.put(this, cur.copy(paused = on, snoozeAt = 0, snoozeDay = 0, snoozeMin = -1))) cantSave()
        else toast(if (on) tr("Paused: no reminders until you resume it", "Duraklatıldı: sürdürene kadar hatırlatma yok") else tr("Resumed", "Sürdürülüyor"))
    }

    private fun editPlan(r: Routine) {
        scheduleEditor(tr("When, each day", "Her gün ne zaman"), r.plan) { p ->
            val cur = Routines.get(this, r.id) ?: return@scheduleEditor
            if (!Routines.put(this, cur.copy(plan = p, snoozeAt = 0, snoozeDay = 0, snoozeMin = -1))) cantSave()
        }
    }

    private fun editText(r: Routine) {
        val e = EditText(this).apply {
            setText(r.text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 8
            setTextColor(C.TEXT)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        val d = AlertDialog.Builder(this)
            .setTitle(tr("Edit routine", "Rutini düzenle"))
            .setView(box)
            .setPositiveButton(tr("Save", "Sakla")) { _, _ ->
                val t = e.text.toString().trim()
                val cur = Routines.get(this, r.id) ?: return@setPositiveButton
                if (t.isEmpty()) confirmDelete(cur) else if (!Routines.put(this, cur.copy(text = t))) cantSave()
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }

    private fun confirmDelete(r: Routine) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete this routine?", "Bu rutin silinsin mi?"))
            .setMessage(r.title + "\n\n" + tr("This can't be undone. Pause keeps it without reminders.", "Bu geri alınamaz. Duraklatmak onu hatırlatmasız tutar."))
            .setPositiveButton(tr("Delete", "Sil")) { _, _ -> if (!Routines.delete(this, r)) cantSave() }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    // ------------------------------------------------------------------ settings, permissions

    /** Android 13+: ask once for notifications (shared with Notes: one question for the app). */
    private fun askNotify() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("notes", MODE_PRIVATE)
        if (p.getBoolean("asked_notify", false)) {
            if (!Routines.canNotify(this)) toast(tr("Notifications are off: reminders can't show.", "Bildirimler kapalı: hatırlatıcılar görünemez."))
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFY)
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == RC_NOTIFY && r.isNotEmpty()) getSharedPreferences("notes", MODE_PRIVATE).edit().putBoolean("asked_notify", true).apply()
    }

    private fun openNotificationSettings(channel: String?) {
        Routines.channel(this)
        val i = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        try { startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } catch (_: Exception) {}
    }

    private fun settings() {
        val prefs = getSharedPreferences("notes", MODE_PRIVATE)
        val snooze = Notes.snoozeMinutes(this)
        val opts = listOf(5, 10, 15, 30, 60)
        val fmt = { m: Int -> tr("$m min", "$m dk") }
        val s = Sheet(this, C.ACCENT).header(tr("Routines", "Rutinler"), tr("Things you do every day, with reminders", "Her gün yaptığınız şeyler, hatırlatıcılarla"))
            .section(tr("Reminders", "Hatırlatıcılar"))
            .item(Ic.SLEEP, tr("Snooze length", "Erteleme süresi"), sub = tr("Same as in Notes", "Notlar ile aynı"), value = fmt(snooze), more = true,
                reset = Sheet.Reset(fmt(10)) { prefs.edit().putInt("snooze", 10).apply() }) {
                Sheet.choose(this, tr("Snooze length", "Erteleme süresi"), opts.map(fmt), opts.indexOf(snooze), C.ACCENT) {
                    prefs.edit().putInt("snooze", opts[it]).apply()
                }
            }
            .item(Ic.BELL, tr("Reminder sound", "Hatırlatma sesi"), sub = tr("Android's settings for routine reminders", "Rutin hatırlatıcıları için Android ayarları"), more = true) {
                openNotificationSettings(Routines.CHANNEL)
            }
        if (!Routines.canNotify(this)) s.item(Ic.INFO, tr("Notifications are off", "Bildirimler kapalı"),
            sub = tr("Reminders can't show. Tap to turn them on.", "Hatırlatıcılar görünemez. Açmak için dokunun.")) { openNotificationSettings(null) }
        s.section(null)
            .item(Ic.LANGUAGE, tr("Language", "Dil"), value = Sheet.languageValue(), more = true, reset = Sheet.languageReset(this)) { Sheet.language(this, C.ACCENT) }
            .item(Ic.UPDATE, tr("Check for updates", "Güncellemeleri denetle"), value = Sheet.version(this)) { PlainUpdate.check(this) }
            .show()
    }

    companion object {
        const val ACTION_SHOW = "app.plain.routines.SHOW"
        const val EXTRA_ID = "id"
        private const val RC_NOTIFY = 2
        private const val PAGE = 30
    }
}
