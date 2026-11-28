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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
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
import android.view.MotionEvent
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
import java.io.File
import java.util.Calendar
import app.plain.shared.R as SR

/**
 * Notes tab: quick text and voice notes, each with an optional reminder (a timer from now or a date).
 *
 * Quick use: the note field sits in the thumb bar. Type and send; a time written in the text ("in 20 min",
 * "tomorrow 9", "yarın 18:30") becomes its reminder by itself (shown above the bar, tap ✕ to drop it), or tap the
 * bell to pick one. With the field empty the button is a mic: hold it to say a note (release saves, slide left
 * cancels) or tap it to record hands-free and tap ■ to save. Whatever is typed becomes the voice note's caption.
 *
 * List: timed notes first (soonest first; due ones with a ✓), then the rest, newest first. Tap a text note to edit it,
 * a voice note to play it; hold either for its sheet. The ✓ at a row's end finishes the note: it moves to the Archive
 * (a row at the list's end, or ⋮ → Archive), where it can be used again today or deleted for good.
 * Nothing ticks unless this tab is open.
 */
class NotesActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var resumed = false
    private var notes: List<Note> = emptyList()

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var empty: TextView
    private lateinit var chip: TextView
    private lateinit var field: EditText
    private lateinit var recStatus: TextView
    private lateinit var bell: ImageView
    private lateinit var cancelRec: TextView
    private lateinit var action: ImageView
    private val rows = HashMap<Long, View>()

    // the Archive (finished notes), a sub-page of this tab
    private var archiveMode = false
    private var archPage = 0

    // composer reminder: picked with the bell, or found in the text
    private var pickedAt = 0L
    private var detected: When.Hit? = null
    private var dropped = false

    // recording
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recId = 0L
    private var recStart = 0L
    private var pushing = false
    private var pressStartedRec = false

    // playback
    private var player: MediaPlayer? = null
    private var playingId = -1L
    private var playSub: TextView? = null
    private var focus: AudioFocusRequest? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!resumed) return
            when {
                recorder != null -> updateRecStatus()
                player != null -> updatePlay()
                // only reminders' "in 5 min" and the day in "today 14:05" change: don't rebuild a long list for nothing
                notes.any { it.remindAt > 0 && it.archived == 0L } || shownDay != dayOf(System.currentTimeMillis()) -> show()
                else -> refreshChip()
            }
            val next = if (recorder != null || player?.isPlaying == true) 250L else 30_000L
            main.postDelayed(this, next)
        }
    }

    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        if (Hub.gate(this, "notes")) { finish(); return }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        val frame = FrameLayout(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
        scroll = ScrollView(this).apply { addView(list); isVerticalScrollBarEnabled = false }
        frame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        empty = text(tr("No notes yet.\n\nType one below, or hold the mic and say it.\nWrite a time like “in 20 min” or “tomorrow 9:00” and it reminds you then.",
            "Henüz not yok.\n\nAşağıya yazın ya da mikrofonu basılı tutup söyleyin.\n“20 dk sonra” ya da “yarın 9:00” gibi bir zaman yazarsanız o zaman hatırlatır."),
            15f, C.DIM, lines = 0).apply { gravity = Gravity.CENTER; setPadding(dp(32), 0, dp(32), 0); visibility = View.GONE }
        frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // the composer's reminder, right above the bar
        chip = text("", 13.5f, C.ACCENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(36)
            setPadding(dp(14), 0, dp(14), 0)
            background = ripple(outline(dpf(18), C.ACCENT))
            setOnClickListener { dropReminder() }
            visibility = View.GONE
        }
        root.addView(chip, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12); bottomMargin = dp(6) })

        setContentView(Hub.wrap(this, "notes", root))

        // ⋮ next to the drawers: settings
        Hub.titleEnd(this)?.addView(text("⋮", 22f, C.DIM).apply {
            gravity = Gravity.CENTER
            contentDescription = tr("Settings", "Ayarlar")
            background = ripple(null, ring(C.BG))
            setOnClickListener { settings() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))

        // ---- thumb bar: field (or the recording status), bell (or cancel), mic / send / stop
        Hub.bar(this)?.let { bar ->
            field = EditText(this).apply {
                hint = tr("Note…", "Not…")
                setHintTextColor(C.LABEL)
                setTextColor(C.TEXT)
                textSize = 15f
                maxLines = 5
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_SEND
                minHeight = dp(44)
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = outline(dpf(22), C.OUTLINE_BTN)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) { onTyped() }
                })
            }
            recStatus = text("", 14f, C.REC, lines = 2).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(8), 0)
                fontFeatureSettings = "tnum"
                visibility = View.GONE
            }
            bell = iconButton(SR.drawable.sheet_ic_bell, tr("Remind me", "Hatırlat")) { remindPicker(effectiveAt()) { pickedAt = it; dropped = it == 0L; updateChip() } }
            cancelRec = text("✕", 20f, C.DIM).apply {
                gravity = Gravity.CENTER
                contentDescription = tr("Cancel recording", "Kaydı iptal et")
                background = ripple(null, ring(C.BG))
                setOnClickListener { stopRec(false) }
                visibility = View.GONE
            }
            action = ImageView(this).apply {
                setPadding(dp(11), dp(11), dp(11), dp(11))
                background = ripple(ring(C.OUTLINE_BTN), ring(C.BG))
                isClickable = true
                setOnTouchListener { v, e -> onAction(v, e) }
            }
            bar.addView(field, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(recStatus, LinearLayout.LayoutParams(0, -2, 1f))
            bar.addView(bell, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            bar.addView(cancelRec, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            bar.addView(action, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(4) })
            field.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
        }
        updateAction()
        b?.getString("draft")?.let { field.setText(it) }
        if (b?.getBoolean("archive") == true) { archiveMode = true; archPage = b.getInt("archPage"); Hub.setBack(this) { leaveArchive() } }
        // the intent that started this tab, once: not again when Android recreates it (it would overwrite the draft
        // or play the note again)
        if (b == null) handle(intent)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        if (::field.isInitialized) out.putString("draft", field.text.toString())
        out.putBoolean("archive", archiveMode)
        out.putInt("archPage", archPage)
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        handle(i)
    }

    /** Text shared from another app becomes the draft; a reminder notification opens (and plays) its note. */
    private fun handle(i: Intent?) {
        when (i?.action) {
            Intent.ACTION_SEND -> i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let {
                val subject = i.getStringExtra(Intent.EXTRA_SUBJECT)
                field.setText(if (!subject.isNullOrBlank() && !it.contains(subject)) "$subject\n$it" else it)
                field.setSelection(field.text.length)
                field.requestFocus()
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            }
            ACTION_SHOW -> {
                val id = i.getLongExtra(EXTRA_ID, -1)
                load()
                val n = notes.firstOrNull { it.id == id && it.archived == 0L } ?: return
                if (archiveMode) leaveArchive()
                main.post { rows[id]?.let { r -> scroll.smoothScrollTo(0, (r.top - dp(40)).coerceAtLeast(0)) } }
                if (n.audio != null && playingId != id) togglePlay(n)
            }
        }
        setIntent(Intent())   // handled once (not again after a rotation)
    }

    override fun onStart() {
        super.onStart()
        Notes.listener = { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        kick()
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onStop() {
        Notes.listener = null
        if (recorder != null) stopRec(true)   // leaving mid-recording keeps what was said
        stopPlay()
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun kick() {
        main.removeCallbacks(tick)
        if (resumed) main.postDelayed(tick, if (recorder != null || player != null) 250L else 30_000L)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun iconButton(icon: Int, desc: String, fn: () -> Unit) = ImageView(this).apply {
        setImageResource(icon)
        imageTintList = ColorStateList.valueOf(C.DIM)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = ripple(null, ring(C.BG))
        contentDescription = desc
        setOnClickListener { fn() }
    }

    // ------------------------------------------------------------------ list

    private fun load() {
        notes = Notes.all(this)
        if (playingId >= 0 && notes.none { it.id == playingId }) stopPlay()
        show()
    }

    private var shownDay = -1

    private fun dayOf(t: Long) = Calendar.getInstance().apply { timeInMillis = t }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    private fun show() {
        if (!::list.isInitialized) return
        val now = System.currentTimeMillis()
        shownDay = dayOf(now)
        list.removeAllViews()
        rows.clear()
        playSub = null
        val archived = notes.filter { it.archived > 0 }.sortedByDescending { it.archived }
        if (archiveMode) { showArchive(archived, now); return }
        val active = notes.filter { it.archived == 0L }
        val timed = active.filter { it.remindAt > 0 }.sortedBy { it.remindAt }
        val rest = active.filter { it.remindAt <= 0 }.sortedByDescending { it.created }
        if (timed.isNotEmpty()) {
            list.addView(heading(tr("Reminders", "Hatırlatıcılar")))
            timed.forEach { list.addView(row(it, now)) }
        }
        if (rest.isNotEmpty()) {
            if (timed.isNotEmpty()) list.addView(heading(tr("Notes", "Notlar")))
            else list.addView(View(this), LinearLayout.LayoutParams(-1, dp(8)))
            rest.forEach { list.addView(row(it, now)) }
        }
        if (archived.isNotEmpty()) list.addView(link(tr("Archive", "Arşiv"), archived.size.toString()) { enterArchive() })
        empty.visibility = if (notes.isEmpty()) View.VISIBLE else View.GONE
        refreshChip()
    }

    /** A quiet row that opens a sub-page: "Archive   3 ›". */
    private fun link(label: String, value: String, fn: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        setPadding(dp(20), dp(8), dp(16), dp(8))
        background = ripple(null, android.graphics.drawable.ColorDrawable(C.BG))
        setOnClickListener { fn() }
        addView(ImageView(this@NotesActivity).apply { setImageResource(SR.drawable.sheet_ic_folder); imageTintList = ColorStateList.valueOf(C.LABEL) },
            LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(14) })
        addView(text(label, 15f, C.DIM), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("$value  ›", 14f, C.LABEL))
    }

    // ------------------------------------------------------------------ archive

    private fun enterArchive() {
        if (recorder != null) return
        archiveMode = true
        archPage = 0
        Hub.setBack(this) { leaveArchive() }
        show()
        scroll.scrollTo(0, 0)
    }

    private fun leaveArchive() {
        if (!archiveMode) return
        archiveMode = false
        Hub.setBack(this, null)
        show()
        scroll.scrollTo(0, 0)
    }

    @Deprecated("Activity back handling")
    override fun onBackPressed() {
        if (archiveMode) leaveArchive() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    private fun showArchive(archived: List<Note>, now: Long) {
        val pages = pageCount(archived.size, PAGE)
        archPage = archPage.coerceIn(0, pages - 1)
        list.addView(heading(tr("Archive", "Arşiv") + " · " + archived.size))
        if (archived.isEmpty()) list.addView(text(tr("Nothing here. Finished notes (✓) wait here; use one again today or delete it for good.",
            "Burada bir şey yok. Biten notlar (✓) burada bekler; bugün yeniden kullanabilir ya da kalıcı olarak silebilirsiniz."),
            14f, C.DIM, lines = 0).apply { setPadding(dp(20), dp(8), dp(20), dp(8)) })
        archived.drop(archPage * PAGE).take(PAGE).forEach { list.addView(row(it, now)) }
        if (pages > 1) list.addView(pager(archPage, pages) { archPage = it; show(); scroll.scrollTo(0, 0) })
        if (archived.isNotEmpty()) list.addView(text(tr("Delete all archived…", "Arşivdekilerin hepsini sil…"), 14f, C.RED).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(52)
            setPadding(dp(20), 0, dp(20), 0)
            background = ripple(null, android.graphics.drawable.ColorDrawable(C.BG))
            setOnClickListener { confirmDeleteArchived(archived.size) }
        })
        empty.visibility = View.GONE
        refreshChip()
    }

    private fun archiveSheet(n: Note) {
        val now = System.currentTimeMillis()
        val again = Notes.reuseAt(n.remindAt, now)
        val sub = when {
            n.remindAt <= 0 -> tr("Back on the list", "Listeye geri döner")
            again > 0 -> tr("Reminds today at ", "Bugün şu saatte hatırlatır: ") + java.util.Calendar.getInstance().apply { timeInMillis = again }
                .let { Stack.hhmm(it.get(java.util.Calendar.HOUR_OF_DAY) * 60 + it.get(java.util.Calendar.MINUTE)) }
            else -> tr("Back on the list; today's time has passed, so no reminder", "Listeye döner; bugünkü saat geçti, hatırlatıcı olmaz")
        }
        val s = Sheet(this, C.ACCENT).header(n.title, tr("Finished ", "Bitti: ") + When.label(n.archived, now))
            .actions(
                Sheet.Action(Ic.RESTORE, tr("Use again", "Yeniden kullan")) { reuse(n) },
                if (n.audio != null) Sheet.Action(Ic.PLAY, tr("Play", "Oynat")) { togglePlay(n) } else null,
                if (n.text.isNotBlank()) Sheet.Action(Ic.COPY, tr("Copy", "Kopyala")) { copy(n) } else null,
                Sheet.Action(Ic.SHARE, tr("Share", "Paylaş")) { share(n) },
            )
            .section(null)
            .item(Ic.RESTORE, tr("Use again (today)", "Yeniden kullan (bugün)"), sub = sub) { reuse(n) }
        s.section(null).item(Ic.DELETE, tr("Delete permanently…", "Kalıcı olarak sil…"), danger = true) { confirmDelete(n) }.show()
    }

    private fun reuse(n: Note) {
        val had = n.remindAt > 0
        if (!Notes.reuse(this, n)) { cantSave(); return }
        val back = Notes.get(this, n.id)
        when {
            back != null && back.remindAt > 0 -> { askNotify(); toast(tr("Back on the list · reminds ", "Listede · hatırlatma: ") + When.label(back.remindAt, System.currentTimeMillis())) }
            had -> toast(tr("Back on the list, without a reminder (today's time has passed)", "Listede, hatırlatıcısız (bugünkü saat geçti)"))
            else -> toast(tr("Back on the list", "Listeye geri döndü"))
        }
    }

    private fun finishNote(n: Note) {
        if (n.id == playingId) stopPlay()
        if (!Notes.finish(this, n)) cantSave() else toast(tr("Done · moved to the archive", "Bitti · arşive taşındı"))
    }

    private fun confirmDeleteArchived(count: Int) {
        AlertDialog.Builder(this)
            .setTitle(tr("Delete all $count archived notes?", "Arşivdeki $count notun hepsi silinsin mi?"))
            .setMessage(tr("This can't be undone.", "Bu geri alınamaz."))
            .setPositiveButton(tr("Delete", "Sil")) { _, _ -> stopPlay(); if (!Notes.deleteArchived(this)) cantSave() }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    /** The composer's "in 10 min" chip keeps its time current (typed or picked with the bell). */
    private fun refreshChip() {
        if (detected != null) onTyped() else if (pickedAt > 0 && ::bell.isInitialized) updateChip()
    }

    private fun heading(t: String) = text(PlainLang.upper(t), 12f, C.LABEL, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(20), dp(16), dp(20), dp(6))
    }

    private fun row(n: Note, now: Long): View {
        val current = n.id == playingId && player != null
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(8), dp(8), dp(8))
            background = ripple(null, android.graphics.drawable.ColorDrawable(C.BG))
            setOnClickListener { if (n.audio != null) togglePlay(n) else if (n.archived > 0) archiveSheet(n) else edit(n) }
            setOnLongClickListener { it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); if (n.archived > 0) archiveSheet(n) else noteSheet(n); true }
        }
        if (n.audio != null) {
            val playing = current && player?.isPlaying == true
            row.addView(ImageView(this).apply {
                setImageResource(if (playing) SR.drawable.sheet_ic_pause else SR.drawable.sheet_ic_play)
                imageTintList = ColorStateList.valueOf(if (current) C.ACCENT else C.TEXT)
                setPadding(dp(9), dp(9), dp(9), dp(9))
                background = ripple(ring(if (current) C.ACCENT else C.OUTLINE_BTN), ring(C.BG))
                contentDescription = if (playing) tr("Pause", "Duraklat") else tr("Play", "Oynat")
                setOnClickListener { togglePlay(n) }
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
        }
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = n.text.trim().ifEmpty { n.title }
        mid.addView(text(body, 15f, if (current) C.ACCENT else C.TEXT, lines = if (n.audio != null) 2 else 4))
        val sub = text("", 12.5f, C.LABEL, lines = 1).apply { setPadding(0, dp(3), 0, 0); fontFeatureSettings = "tnum" }
        val len = if (n.audio != null) " · " + When.duration(n.audioMs) else ""
        when {
            n.archived > 0 -> sub.text = tr("Finished ", "Bitti: ") + When.label(n.archived, now) + len
            n.remindAt > 0 && (n.fired || n.remindAt <= now) -> {
                sub.text = tr("Due · ", "Zamanı geldi · ") + When.left(n.remindAt, now) + len
                sub.setTextColor(C.ACCENT)
                sub.setTypeface(null, Typeface.BOLD)
            }
            n.remindAt > 0 -> {
                sub.text = When.left(n.remindAt, now) + " · " + When.label(n.remindAt, now) + len
                sub.setTextColor(C.ACCENT)
            }
            else -> sub.text = When.label(n.created, now) + len
        }
        mid.addView(sub)
        if (current) playSub = sub
        row.addView(mid, LinearLayout.LayoutParams(0, -2, 1f))
        if (n.archived == 0L) {
            // ✓: finished, into the Archive (bright when the reminder is due, faint otherwise)
            val due = n.remindAt > 0 && (n.fired || n.remindAt <= now)
            row.addView(ImageView(this).apply {
                setImageResource(SR.drawable.sheet_ic_check)
                imageTintList = ColorStateList.valueOf(if (due) C.ACCENT else C.FAINT)
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = ripple(null, ring(C.BG))
                contentDescription = tr("Done: move to the archive", "Bitti: arşive taşı")
                setOnClickListener { finishNote(n) }
            }, LinearLayout.LayoutParams(dp(44), dp(44)))
        } else row.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        rows[n.id] = row
        return row
    }

    // ------------------------------------------------------------------ composer

    private fun effectiveAt(): Long = when {
        pickedAt > 0 -> pickedAt
        !dropped -> detected?.at ?: 0
        else -> 0
    }

    private fun onTyped() {
        val t = field.text.toString()
        if (t.isBlank()) dropped = false
        detected = if (t.isBlank()) null else When.find(t, System.currentTimeMillis())
        updateChip()
        updateAction()
    }

    private fun updateChip() {
        val at = effectiveAt()
        val now = System.currentTimeMillis()
        if (at <= 0 || recorder != null && pickedAt <= 0 && detected == null) { chip.visibility = View.GONE; return }
        val left = if (at - now < 24 * 3_600_000L) When.left(at, now) + " · " else ""
        chip.text = tr("Remind ", "Hatırlat ") + left + When.label(at, now) + "   ✕"
        chip.contentDescription = tr("Reminder set. Tap to remove.", "Hatırlatıcı ayarlı. Kaldırmak için dokunun.")
        chip.visibility = View.VISIBLE
        bell.imageTintList = ColorStateList.valueOf(C.ACCENT)
    }

    private fun dropReminder() {
        pickedAt = 0
        dropped = true
        chip.visibility = View.GONE
        bell.imageTintList = ColorStateList.valueOf(C.DIM)
    }

    private fun clearComposer() {
        field.setText("")
        pickedAt = 0
        dropped = false
        detected = null
        chip.visibility = View.GONE
        bell.imageTintList = ColorStateList.valueOf(C.DIM)
    }

    /** The bar's last button: send (text typed), mic (empty), stop (recording). */
    private fun updateAction() {
        if (!::action.isInitialized) return
        val (icon, color, desc) = when {
            recorder != null -> Triple(R.drawable.nts_ic_stop, C.REC, tr("Stop and save", "Durdur ve sakla"))
            field.text.isNotBlank() -> Triple(R.drawable.nts_ic_send, C.ACCENT, tr("Save note", "Notu sakla"))
            else -> Triple(SR.drawable.sheet_ic_mic, C.TEXT, tr("Voice note: hold to talk, or tap", "Sesli not: konuşurken basılı tutun ya da dokunun"))
        }
        action.setImageResource(icon)
        action.imageTintList = ColorStateList.valueOf(color)
        action.background = ripple(ring(if (recorder != null) C.REC else if (color == C.ACCENT) C.ACCENT else C.OUTLINE_BTN), ring(C.BG))
        action.contentDescription = desc
    }

    private fun send() {
        val t = field.text.toString().trim()
        if (t.isEmpty()) return
        val now = System.currentTimeMillis()
        val at = when {
            pickedAt > 0 -> pickedAt
            dropped -> 0L
            else -> When.find(t, now)?.at ?: 0L   // "in 10 min" counts from the moment it's saved
        }
        val n = Note(id = Notes.newId(this), text = t, created = now, remindAt = at)
        if (!Notes.put(this, n)) { cantSave(); return }   // the text stays in the field
        clearComposer()
        leaveArchive()
        if (at > 0) { askNotify(); toast(tr("Reminds ", "Hatırlatma: ") + When.label(at, System.currentTimeMillis())) }
        scrollTo(n.id)
    }

    private fun cantSave() = Toast.makeText(this, tr("Couldn't save the note. Is the phone's storage full?",
        "Not saklanamadı. Telefonun depolama alanı dolu mu?"), Toast.LENGTH_LONG).show()

    private fun scrollTo(id: Long) = main.post { rows[id]?.let { r -> scroll.smoothScrollTo(0, (r.top - dp(40)).coerceAtLeast(0)) } }

    /** Touches on the send / mic / stop button: push-to-talk, tap-to-record, slide left to cancel. */
    private fun onAction(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                pressStartedRec = false
                if (recorder == null && field.text.isBlank()) {
                    if (!hasMic()) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC); return true }
                    if (startRec()) { pressStartedRec = true; pushing = true; v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (pressStartedRec && pushing && recorder != null && e.x < -dp(70)) {
                v.isPressed = false
                pressStartedRec = false
                stopRec(false)
                toast(tr("Recording cancelled", "Kayıt iptal edildi"))
            }
            MotionEvent.ACTION_UP -> {
                v.isPressed = false
                when {
                    pressStartedRec && recorder != null -> {
                        if (System.currentTimeMillis() - recStart > 600) stopRec(true)
                        else { pushing = false; updateRecStatus() }   // a tap: keep recording until ■
                    }
                    recorder != null -> stopRec(true)
                    field.text.isNotBlank() -> send()
                }
                pressStartedRec = false
            }
            MotionEvent.ACTION_CANCEL -> {
                v.isPressed = false
                if (pressStartedRec && recorder != null) stopRec(true)
                pressStartedRec = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ recording

    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startRec(): Boolean {
        stopPlay()
        val id = Notes.newId(this)
        val f = File(Notes.dir(this), "$id.m4a")
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(64_000)
            r.setOutputFile(f.absolutePath)
            r.prepare()
            r.start()
        } catch (_: Exception) {
            try { r.release() } catch (_: Exception) {}
            f.delete()
            toast(tr("Can't record: is the microphone in use?", "Kayıt yapılamıyor: mikrofon kullanımda mı?"))
            return false
        }
        recorder = r
        recFile = f
        recId = id
        recStart = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        field.visibility = View.GONE
        bell.visibility = View.GONE
        recStatus.visibility = View.VISIBLE
        cancelRec.visibility = View.VISIBLE
        updateRecStatus()
        updateAction()
        kick()
        return true
    }

    private fun updateRecStatus() {
        if (recorder == null) return
        val el = When.duration(System.currentTimeMillis() - recStart)
        recStatus.text = "●  $el   " + if (pushing) tr("release to save · ← cancel", "bırakınca saklanır · ← iptal")
            else tr("tap ■ to save", "saklamak için ■")
    }

    private fun stopRec(save: Boolean) {
        val r = recorder ?: return
        val f = recFile
        val dur = System.currentTimeMillis() - recStart
        var ok = true
        try { r.stop() } catch (_: Exception) { ok = false }   // throws when nothing was recorded yet
        try { r.release() } catch (_: Exception) {}
        recorder = null
        recFile = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        field.visibility = View.VISIBLE
        bell.visibility = View.VISIBLE
        recStatus.visibility = View.GONE
        cancelRec.visibility = View.GONE
        updateAction()
        kick()
        if (f == null) return
        if (!save) { f.delete(); return }
        if (!ok || dur < 700 || f.length() == 0L) {
            f.delete()
            toast(tr("Too short: hold the mic while you talk, or tap it once.", "Çok kısa: konuşurken mikrofonu basılı tutun ya da bir kez dokunun."))
            return
        }
        val now = System.currentTimeMillis()
        val t = field.text.toString().trim()
        val at = when {
            pickedAt > 0 -> pickedAt
            dropped || t.isEmpty() -> 0L
            else -> When.find(t, now)?.at ?: 0L
        }
        val n = Note(id = recId, text = t, audio = f.name, audioMs = realLength(f, dur), created = now, remindAt = at)
        if (!Notes.put(this, n)) { f.delete(); cantSave(); return }
        clearComposer()
        leaveArchive()
        if (at > 0) askNotify()
        scrollTo(n.id)
    }

    private fun realLength(f: File, fallback: Long): Long = try {
        val m = android.media.MediaMetadataRetriever()
        try {
            m.setDataSource(f.absolutePath)
            m.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: fallback
        } finally { m.release() }
    } catch (_: Exception) { fallback }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (rc == RC_NOTIFY && r.isNotEmpty()) getSharedPreferences("notes", MODE_PRIVATE).edit().putBoolean("asked_notify", true).apply()
        if (rc != RC_MIC) return
        if (hasMic()) toast(tr("Ready: hold the mic to talk, or tap it.", "Hazır: konuşmak için mikrofonu basılı tutun ya da dokunun."))
        else explainMic()
    }

    private fun explainMic() {
        val again = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        val d = AlertDialog.Builder(this)
            .setTitle(tr("Microphone needed", "Mikrofon gerekli"))
            .setMessage(tr("Voice notes need the microphone. They stay on your phone, inside this app.\n\nAllow “Microphone” in the app's settings.",
                "Sesli notlar mikrofona ihtiyaç duyar. Telefonunuzda, bu uygulamanın içinde kalırlar.\n\nUygulama ayarlarında “Mikrofon” iznini verin."))
            .setPositiveButton(tr("App settings", "Uygulama ayarları")) { _, _ ->
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
        if (again) d.setNeutralButton(tr("Ask again", "Tekrar sor")) { _, _ -> requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC) }
        d.show()
    }

    /** Android 13+: ask once for notifications (reminders are notifications). */
    private fun askNotify() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val p = getSharedPreferences("notes", MODE_PRIVATE)
        if (p.getBoolean("asked_notify", false)) {
            if (!Notes.canNotify(this)) toast(tr("Notifications are off: reminders can't show.", "Bildirimler kapalı: hatırlatıcılar görünemez."))
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFY)
    }

    // ------------------------------------------------------------------ playback

    private val attrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun togglePlay(n: Note) {
        if (recorder != null) return
        val p = player
        if (p != null && n.id == playingId) {
            if (p.isPlaying) { p.pause(); letGo() } else { gainFocus(); p.start() }
            show(); kick()
            return
        }
        stopPlay()
        val f = Notes.audioFile(this, n) ?: return
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(f.absolutePath)
            mp.prepare()
        } catch (_: Exception) {
            mp.release()
            toast(tr("Can't play this note.", "Bu not oynatılamıyor."))
            return
        }
        mp.setOnCompletionListener { stopPlay(); show() }
        player = mp
        playingId = n.id
        gainFocus()
        mp.start()
        show()
        kick()
    }

    private fun gainFocus() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val f = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener({ c ->
                if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { player?.let { if (it.isPlaying) it.pause() }; show() }
            }, main)
            .build().also { focus = it }
        am.requestAudioFocus(f)
    }

    private fun letGo() {
        focus?.let { getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) }
    }

    private fun stopPlay() {
        player?.let { try { it.release() } catch (_: Exception) {} }
        player = null
        playingId = -1
        letGo()
    }

    private fun updatePlay() {
        val p = player ?: return
        val (pos, len) = try { p.currentPosition.toLong() to p.duration.toLong() } catch (_: Exception) { return }
        playSub?.text = When.duration(pos) + " / " + When.duration(len)
    }

    // ------------------------------------------------------------------ editing, sheets

    private fun edit(n: Note) {
        val e = EditText(this).apply {
            setText(n.text)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 10
            setTextColor(C.TEXT)
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(e) }
        val d = AlertDialog.Builder(this)
            .setTitle(if (n.audio != null) tr("Caption", "Açıklama") else tr("Edit note", "Notu düzenle"))
            .setView(box)
            .setPositiveButton(tr("Save", "Sakla")) { _, _ ->
                val t = e.text.toString().trim()
                // the note as it is now: its reminder may have gone off (or been snoozed) while this was open
                val cur = Notes.get(this, n.id) ?: n
                if (t.isEmpty() && cur.audio == null) confirmDelete(cur) else if (!Notes.put(this, cur.copy(text = t))) cantSave()
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        e.requestFocus()
    }

    private fun noteSheet(n: Note) {
        val now = System.currentTimeMillis()
        val sub = if (n.remindAt > 0) tr("Reminds ", "Hatırlatma: ") + When.label(n.remindAt, now) else tr("Written ", "Yazıldı: ") + When.label(n.created, now)
        val s = Sheet(this, C.ACCENT).header(n.title, sub)
            .actions(
                Sheet.Action(Ic.BELL, tr("Remind", "Hatırlat")) { remindPicker(n.remindAt) { Notes.remind(this, n, it); if (it > 0) askNotify() } },
                Sheet.Action(Ic.EDIT, if (n.audio != null) tr("Caption", "Açıklama") else tr("Edit", "Düzenle")) { edit(n) },
                if (n.text.isNotBlank()) Sheet.Action(Ic.COPY, tr("Copy", "Kopyala")) { copy(n) } else null,
                Sheet.Action(Ic.SHARE, tr("Share", "Paylaş")) { share(n) },
            )
        if (n.remindAt > 0) {
            s.section(tr("Reminder", "Hatırlatıcı"))
            if (n.fired || n.remindAt <= now) {
                s.item(Ic.CLOSE, tr("Keep the note, drop the reminder", "Not kalsın, hatırlatıcı kalksın")) { Notes.remind(this, n, 0) }
                val m = Notes.snoozeMinutes(this)
                s.item(Ic.SLEEP, tr("Snooze $m min", "$m dk ertele")) { Notes.remind(this, n, System.currentTimeMillis() + m * 60_000L) }
            } else {
                s.item(Ic.CLOSE, tr("Remove the reminder", "Hatırlatıcıyı kaldır"), sub = When.left(n.remindAt, now)) { Notes.remind(this, n, 0) }
            }
        }
        s.section(null).item(Ic.CHECK, tr("Done", "Bitti"), sub = tr("Moves the note to the archive", "Notu arşive taşır")) { finishNote(n) }
        if (n.audio == null) s.section(null).item(Ic.MIC, tr("Add a voice note with this text", "Bu metinle sesli not ekle"),
            sub = tr("Fills the field; then hold the mic", "Alanı doldurur; sonra mikrofonu basılı tutun")) {
            field.setText(n.text); field.setSelection(field.text.length)
        }
        s.section(null).item(Ic.DELETE, tr("Delete…", "Sil…"), danger = true) { confirmDelete(n) }.show()
    }

    private fun copy(n: Note) {
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(n.title, n.text))
        if (Build.VERSION.SDK_INT < 33) toast(tr("Copied", "Kopyalandı"))
    }

    private fun share(n: Note) {
        val uri = NotesProvider.uri(this, n)
        val send = Intent(Intent.ACTION_SEND)
        if (uri != null) {
            send.setType("audio/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(n.title, uri)
            if (n.text.isNotBlank()) send.putExtra(Intent.EXTRA_TEXT, n.text)
        } else send.setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text)
        try { startActivity(Intent.createChooser(send, tr("Share note", "Notu paylaş"))) }
        catch (_: Exception) { toast(tr("No app can share this.", "Bunu paylaşabilecek uygulama yok.")) }
    }

    private fun confirmDelete(n: Note) {
        AlertDialog.Builder(this)
            .setTitle(if (n.archived > 0) tr("Delete permanently?", "Kalıcı olarak silinsin mi?") else tr("Delete this note?", "Bu not silinsin mi?"))
            .setMessage(n.title + if (n.archived > 0) "\n\n" + tr("This can't be undone.", "Bu geri alınamaz.") else "")
            .setPositiveButton(tr("Delete", "Sil")) { _, _ -> if (n.id == playingId) stopPlay(); if (!Notes.delete(this, n)) cantSave() }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    private fun openNotificationSettings(channel: String?) {
        Notes.channel(this)
        val i = if (channel != null) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, channel)
            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        try { startActivity(i.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) } catch (_: Exception) {}
    }

    private fun settings() {
        val prefs = getSharedPreferences("notes", MODE_PRIVATE)
        val snooze = Notes.snoozeMinutes(this)
        val opts = listOf(5, 10, 15, 30, 60)
        val fmt = { m: Int -> tr("$m min", "$m dk") }
        val archived = notes.count { it.archived > 0 }
        val s = Sheet(this, C.ACCENT).header(tr("Notes", "Notlar"), tr("Quick text and voice notes with reminders", "Hatırlatıcılı hızlı yazılı ve sesli notlar"))
            .item(Ic.FOLDER, tr("Archive", "Arşiv"), sub = tr("Finished notes: use again or delete", "Biten notlar: yeniden kullan ya da sil"),
                value = archived.toString(), more = true) { if (archiveMode) leaveArchive() else enterArchive() }
            .section(tr("Reminders", "Hatırlatıcılar"))
            .item(Ic.SLEEP, tr("Snooze length", "Erteleme süresi"), value = fmt(snooze), more = true,
                reset = Sheet.Reset(fmt(10)) { prefs.edit().putInt("snooze", 10).apply() }) {
                Sheet.choose(this, tr("Snooze length", "Erteleme süresi"), opts.map(fmt), opts.indexOf(snooze), C.ACCENT) {
                    prefs.edit().putInt("snooze", opts[it]).apply()
                }
            }
            .item(Ic.BELL, tr("Reminder sound", "Hatırlatma sesi"), sub = tr("Android's settings for note reminders", "Not hatırlatıcıları için Android ayarları"), more = true) {
                openNotificationSettings(Notes.CHANNEL)
            }
        if (!Notes.canNotify(this)) s.item(Ic.INFO, tr("Notifications are off", "Bildirimler kapalı"),
            sub = tr("Reminders can't show. Tap to turn them on.", "Hatırlatıcılar görünemez. Açmak için dokunun.")) { openNotificationSettings(null) }
        s.section(null)
            .item(Ic.LANGUAGE, tr("Language", "Dil"), value = Sheet.languageValue(), more = true, reset = Sheet.languageReset(this)) { Sheet.language(this, C.ACCENT) }
            .item(Ic.UPDATE, tr("Check for updates", "Güncellemeleri denetle"), value = Sheet.version(this)) { PlainUpdate.check(this) }
            .show()
    }

    companion object {
        const val ACTION_SHOW = "app.plain.notes.SHOW"
        const val EXTRA_ID = "id"
        private const val RC_MIC = 1
        private const val RC_NOTIFY = 2
        private const val PAGE = 40
    }
}
