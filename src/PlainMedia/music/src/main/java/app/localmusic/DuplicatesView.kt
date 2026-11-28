package app.localmusic

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import app.plain.lang.tr

/**
 * ⋮ → Remove duplicates: scan → list of copies to remove (each can be unticked) → Android's trash dialog → kept
 * "Song (2)" copies get their plain name back, playlists point at the kept copies → report.
 * Nothing goes to the trash before the user confirms here AND in Android's dialog; the trash keeps them ~30 days.
 */
@SuppressLint("ViewConstructor")
class DuplicatesView(private val act: MainActivity) : LinearLayout(act) {
    private val body = LinearLayout(act).apply { orientation = VERTICAL }
    private val status = act.text("", 14f, C.DIM, lines = 0)
    private var scan: Organizer.Scan? = null
    private var tracked: Set<Long> = emptySet()
    private var plan: Duplicates.Plan? = null
    private val keep = HashSet<Long>()
    private var busy = false
    private lateinit var trashBtn: TextView

    companion object {
        const val REQ_TRASH = 111
    }

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(C.PANEL)
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(6), act.dp(6), act.dp(12), act.dp(6)) }
        top.addView(act.icon(R.drawable.mus_ic_close, desc = tr("Close", "Kapat")) { if (!busy) act.closeOverlay(this) })
        top.addView(act.text(tr("Remove duplicates", "Kopyaları kaldır"), 20f, C.TEXT, bold = true), lpL(0, WRAP, 1f))
        addView(top, lpL(MATCH, WRAP))
        addView(body, lpL(MATCH, 0, 1f))
        intro()
    }

    private fun setBody(v: View) {
        body.removeAllViews()
        body.addView(v, lpL(MATCH, MATCH))
    }

    private fun intro() {
        val col = LinearLayout(act).apply { orientation = VERTICAL; setPadding(act.dp(20), act.dp(8), act.dp(20), act.dp(20)) }
        col.addView(act.text(
            tr("Finds songs that are on this phone more than once, Plain Youtube downloads included:\n\n" +
                "• identical files (e.g. “Song.m4a” and “Song (2).m4a”), and\n" +
                "• the same title + artist + length in another quality (the better copy is kept).\n\n" +
                "You see the list first; untick anything you want to keep. Removed copies go to Android's trash " +
                "(restorable for about 30 days from ⋮ → Trash). Playlists switch to the kept copy. Nothing is moved.",
                "Bu telefonda birden fazla kez bulunan şarkıları bulur, Plain Youtube indirmeleri dahil:\n\n" +
                "• aynı dosyalar (ör. “Şarkı.m4a” ve “Şarkı (2).m4a”) ve\n" +
                "• başka kalitedeki aynı başlık + sanatçı + süre (daha iyi kopya tutulur).\n\n" +
                "Önce listeyi görürsünüz; tutmak istediklerinizin işaretini kaldırın. Kaldırılan kopyalar Android'in çöp " +
                "kutusuna gider (yaklaşık 30 gün ⋮ → Çöp kutusu'ndan geri yüklenebilir). Çalma listeleri tutulan kopyaya geçer. Hiçbir şey taşınmaz."),
            15f, C.TEXT, lines = 0,
        ))
        col.addView(act.pill(tr("Find duplicates", "Kopyaları bul"), C.BG, C.ACCENT, C.ACCENT) { startScan() }, lpL(WRAP, WRAP).apply { topMargin = act.dp(20) })
        col.addView(status, lpL(MATCH, WRAP).apply { topMargin = act.dp(16) })
        setBody(ScrollView(act).apply { addView(col) })
    }

    private fun startScan() {
        if (busy) return
        busy = true
        status.text = tr("Starting…", "Başlatılıyor…")
        val app = act.applicationContext
        Thread {
            val s = try { Organizer.scan(app, withPlainYoutube = true) { msg -> post { status.text = msg } } } catch (_: Exception) { null }
            val t = Duplicates.Fix.tracked(app)
            post {
                busy = false
                if (s == null) { status.text = tr("Scan failed.", "Tarama başarısız."); return@post }
                scan = s; tracked = t
                rebuild()
            }
        }.start()
    }

    // --- list ------------------------------------------------------------------------------------

    private sealed class Row
    private class Header(val text: String, val sub: String?) : Row()
    private class Item(val id: Long, val title: String, val line: String, val checked: Boolean) : Row()

    private val adapter = object : BaseAdapter() {
        var rows: List<Row> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is Header) 0 else 1
        override fun isEnabled(p: Int) = rows[p] is Item
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = when (val r = rows[p]) {
            is Header -> ((cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = VERTICAL; setPadding(act.dp(16), act.dp(18), act.dp(16), act.dp(6))
                addView(act.text("", 16f, C.TEXT, bold = true, lines = 2)); addView(act.text("", 13f, C.DIM, lines = 0))
            }).apply {
                (getChildAt(0) as TextView).text = r.text
                (getChildAt(1) as TextView).apply { text = r.sub ?: ""; visibility = if (r.sub == null) GONE else VISIBLE }
            }
            is Item -> ((cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(8), act.dp(6), act.dp(16), act.dp(6))
                addView(CheckBox(act).apply { isClickable = false; isFocusable = false })
                addView(LinearLayout(act).apply {
                    orientation = VERTICAL
                    addView(act.text("", 14f, C.TEXT, lines = 2)); addView(act.text("", 12f, C.DIM, lines = 5))
                }, lpL(0, WRAP, 1f))
                background = ripple(null)
            }).apply {
                (getChildAt(0) as CheckBox).isChecked = r.checked
                val col = getChildAt(1) as LinearLayout
                (col.getChildAt(0) as TextView).apply { text = r.title; setTextColor(if (r.checked) C.TEXT else C.DIM) }
                (col.getChildAt(1) as TextView).text = r.line
            }
        }
    }

    private val planner = Latest(this)

    /** The list again (after the scan and after every tap): off the main thread, the newest tap wins (see [Latest]). */
    private fun rebuild() {
        val s = scan ?: return
        val t = tracked; val k = HashSet(keep)
        planner.run({ Duplicates.plan(s.files, t) to Duplicates.plan(s.files, t, k) }) { r -> if (r != null) show(s, r.first, r.second) }
    }

    /** [all]: every candidate, ticked or not; [p]: what goes to the trash now. */
    private fun show(s: Organizer.Scan, all: Duplicates.Plan, p: Duplicates.Plan) {
        plan = p
        val freed = Text.bytes(p.bytesFreed)
        val rows = ArrayList<Row>()
        rows += Header(
            if (all.removals.isEmpty()) tr("No duplicates found", "Kopya bulunamadı")
            else tr("${p.removals.size} duplicate copies to remove · $freed", "Kaldırılacak ${p.removals.size} kopya · $freed"),
            listOfNotNull(
                tr("${s.files.count { Duplicates.eligible(it) }} songs checked.", "${s.files.count { Duplicates.eligible(it) }} şarkı tarandı."),
                if (all.removals.isNotEmpty()) tr("The better copy is kept. Tap a line to keep both.", "Daha iyi kopya tutulur. İkisini de tutmak için bir satıra dokunun.") else null,
                if (p.protected.isNotEmpty()) tr("${p.protected.size} more copies stay: Plain Youtube's downloads use them (removing one would make it download the song again).",
                    "${p.protected.size} kopya daha kalıyor: Plain Youtube indirmeleri onları kullanıyor (birini kaldırmak şarkının yeniden inmesine yol açar).") else null,
            ).joinToString("\n"),
        )
        for (r in all.removals) {
            val on = r.file.id !in keep
            rows += Item(r.file.id, "${r.file.tags.title} — ${r.file.tags.artist.ifBlank { "?" }}",
                tr("remove  ${r.file.path}\n           ${Organizer.describe(r.file)}\nkeep      ${r.keep.path}\n           ${Organizer.describe(r.keep)}",
                    "kaldır  ${r.file.path}\n           ${Organizer.describe(r.file)}\ntut         ${r.keep.path}\n           ${Organizer.describe(r.keep)}") +
                    if (r.identical) tr("\n(identical files)", "\n(aynı dosyalar)") else "", on)
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        if ((body.getChildAt(0) as? LinearLayout)?.tag != "list") showList()
        trashBtn.text = if (p.removals.isEmpty()) tr("Nothing to remove", "Kaldırılacak bir şey yok") else tr("Move ${p.removals.size} to trash…", "${p.removals.size} kopyayı çöpe at…")
    }

    private fun showList() {
        val wrap = LinearLayout(act).apply { orientation = VERTICAL; tag = "list" }
        val lv = ListView(act).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            isFastScrollEnabled = true
            adapter = this@DuplicatesView.adapter
            setOnItemClickListener { _, _, pos, _ -> (this@DuplicatesView.adapter.rows[pos] as? Item)?.let { toggle(it) } }
        }
        wrap.addView(lv, lpL(MATCH, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10)); setBackgroundColor(C.BG) }
        bar.addView(status.also { (it.parent as? ViewGroup)?.removeView(it); it.text = "" }, lpL(0, WRAP, 1f))
        trashBtn = act.pill("", C.BG, C.ACCENT, C.ACCENT) { confirm() }
        bar.addView(trashBtn)
        wrap.addView(bar, lpL(MATCH, WRAP))
        setBody(wrap)
    }

    private fun toggle(r: Item) {
        if (busy) return
        if (!keep.remove(r.id)) keep.add(r.id)
        rebuild()
    }

    // --- trash -----------------------------------------------------------------------------------

    private fun confirm() {
        val p = plan ?: return
        if (planner.pending) return // a newer list (the last tap) is still being made
        if (busy || p.removals.isEmpty()) return
        AlertDialog.Builder(act).setTitle(tr("Move ${p.removals.size} duplicates to the trash?", "${p.removals.size} kopya çöp kutusuna gönderilsin mi?"))
            .setMessage(tr("${Text.bytes(p.bytesFreed)} freed. They stay restorable for about 30 days (⋮ → Trash). Android will ask for your permission now.",
                "${Text.bytes(p.bytesFreed)} boşalır. Yaklaşık 30 gün geri yüklenebilirler (⋮ → Çöp kutusu). Şimdi Android izninizi isteyecek."))
            .setPositiveButton(tr("Continue", "Devam")) { _, _ -> startTrash() }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun startTrash() {
        val p = plan ?: return
        val s = scan ?: return
        busy = true
        status.text = tr("Waiting for Android's permission…", "Android'in izni bekleniyor…")
        try {
            val pi = MediaStore.createTrashRequest(act.contentResolver, p.removals.mapNotNull { s.uris[it.file.id] }, true)
            @Suppress("DEPRECATION")
            act.startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            busy = false
            status.text = tr("Trash request failed: ${e.message}", "Çöp kutusu isteği başarısız: ${e.message}")
        }
    }

    fun onConsent(req: Int, @Suppress("UNUSED_PARAMETER") ok: Boolean) {
        if (req != REQ_TRASH) return
        val p = plan ?: return
        val s = scan ?: return
        val app = act.applicationContext
        status.text = tr("Tidying up…", "Toparlanıyor…")
        Thread {
            val gone = Organizer.trashedIds(app, p.removals.map { it.file.id })
            val done = p.removals.filter { it.file.id in gone }
            val kept = Duplicates.Fix.rename(app, done, s.uris)
            val renamed = kept.values.count { k -> done.any { it.keep.id == k.id && it.keep.name != k.name } }
            post {
                val keepOf = done.associate { it.file.id to (kept[it.keep.id] ?: it.keep) }
                if (Duplicates.repoint(Store.playlists, keepOf)) Store.savePlaylists()
                finish(done, renamed)
            }
        }.start()
    }

    private fun finish(done: List<Duplicates.Removal>, renamed: Int) {
        val p = plan ?: return
        busy = false
        val col = LinearLayout(act).apply { orientation = VERTICAL; setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(24)) }
        col.addView(act.text(if (done.isEmpty()) tr("Nothing was removed", "Hiçbir şey kaldırılmadı") else tr("Done", "Bitti"), 20f, C.TEXT, bold = true))
        val text = if (done.isEmpty()) tr("Android's permission was not given, so every copy is still there.", "Android izni verilmedi, tüm kopyalar yerinde.")
        else tr("${done.size} of ${p.removals.size} duplicates are in the trash (${Text.bytes(done.sumOf { it.file.size })} freed; ⋮ → Trash to restore).",
            "${p.removals.size} kopyadan ${done.size} tanesi çöp kutusunda (${Text.bytes(done.sumOf { it.file.size })} boşaldı; geri yüklemek için ⋮ → Çöp kutusu).") +
            (if (renamed > 0) tr("\n$renamed kept copies got their name back (no more “(2)”).", "\n$renamed tutulan kopya adını geri aldı (artık “(2)” yok).") else "") +
            tr("\nPlaylists now use the kept copies.", "\nÇalma listeleri artık tutulan kopyaları kullanıyor.") +
            "\n\n" + done.joinToString("\n") { tr("removed ", "kaldırıldı ") + it.file.path }
        col.addView(act.text(text, 12.5f, C.TEXT, lines = 0).apply { setTextIsSelectable(true) }, lpL(MATCH, WRAP).apply { topMargin = act.dp(12) })
        setBody(ScrollView(act).apply { addView(col) })
        act.loadLibrary()
    }
}
