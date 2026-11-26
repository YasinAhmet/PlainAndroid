package app.localmusic

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import app.plain.lang.PlainLang
import app.plain.lang.tr

/**
 * Organiser screen. Flow: intro → scan → PLAN (preview; every line can be switched off) → user confirms →
 * Android's own consent dialogs (trash duplicates, then move files) → report.
 * Nothing is moved or removed before the user confirms here AND in Android's dialog.
 */
@SuppressLint("ViewConstructor")
class OrganizerView(private val act: MainActivity) : LinearLayout(act) {
    private val body = LinearLayout(act).apply { orientation = VERTICAL }
    private val status = act.text("", 14f, C.DIM, lines = 0)
    private var scan: Organizer.Scan? = null
    private var plan: Plan? = null
    private val keepDup = HashSet<Long>()
    private val dontMove = HashSet<Long>()
    private val include = HashSet<Long>()
    private var trashed: Set<Long> = emptySet()
    private var trashAsked = false
    private var busy = false

    companion object {
        const val REQ_TRASH = 101
        const val REQ_WRITE = 102
    }

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(C.PANEL)
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(6), act.dp(6), act.dp(12), act.dp(6)) }
        top.addView(act.icon(R.drawable.mus_ic_close, desc = tr("Close", "Kapat")) { if (!busy) act.closeOverlay(this) })
        top.addView(act.text(tr("Organize library", "Kitaplığı düzenle"), 20f, C.TEXT, bold = true), lpL(0, WRAP, 1f))
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
            tr("Gathers every music file on this phone into one folder:\n\n" +
                "    Music/LocalMusic/Artist/Album/01 Title.ext\n\n" +
                "• Duplicates are found by identical content, and by the same title + artist + length in another quality (the best copy is kept).\n" +
                "• Removed duplicates go to Android's trash (restorable for 30 days from ⋮ → Recently removed).\n" +
                "• Ringtones, notification sounds and voice recordings are left alone.\n\n" +
                "First you get a full plan to review. Nothing changes until you confirm it — and Android will ask you too.",
                "Bu telefondaki tüm müzik dosyalarını tek klasörde toplar:\n\n" +
                "    Music/LocalMusic/Sanatçı/Albüm/01 Başlık.uzantı\n\n" +
                "• Kopyalar aynı içerikten ve başka kalitedeki aynı başlık + sanatçı + süreden bulunur (en iyi kopya tutulur).\n" +
                "• Kaldırılan kopyalar Android'in çöp kutusuna gider (30 gün boyunca ⋮ → Son silinenler'den geri yüklenebilir).\n" +
                "• Zil sesleri, bildirim sesleri ve ses kayıtlarına dokunulmaz.\n\n" +
                "Önce gözden geçirmeniz için tam bir plan görürsünüz. Siz onaylayana kadar hiçbir şey değişmez — Android de size soracak."),
            15f, C.TEXT, lines = 0,
        ))
        col.addView(act.pill(tr("Scan my phone", "Telefonumu tara"), C.ACCENT, 0xFF000000.toInt()) { startScan() }, lpL(WRAP, WRAP).apply { topMargin = act.dp(20) })
        col.addView(status, lpL(MATCH, WRAP).apply { topMargin = act.dp(16) })
        setBody(ScrollView(act).apply { addView(col) })
    }

    private fun startScan() {
        if (busy) return
        busy = true
        status.text = tr("Starting…", "Başlatılıyor…")
        val app = act.applicationContext
        Thread {
            val s = try { Organizer.scan(app) { msg -> post { status.text = msg } } } catch (e: Exception) { null }
            post {
                busy = false
                if (s == null) { status.text = tr("Scan failed.", "Tarama başarısız."); return@post }
                scan = s
                rebuild()
            }
        }.start()
    }

    // --- plan ------------------------------------------------------------------------------------

    private sealed class PRow
    private class PHeader(val text: String, val sub: String? = null) : PRow()
    private class PItem(val id: Long, val kind: Int, val title: String, val line: String, val checked: Boolean) : PRow()

    private val K_REMOVE = 0
    private val K_MOVE = 1
    private val K_SKIP = 2

    private val adapter = object : BaseAdapter() {
        var rows: List<PRow> = emptyList()
        override fun getCount() = rows.size
        override fun getItem(p: Int) = rows[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (rows[p] is PHeader) 0 else 1
        override fun isEnabled(p: Int) = rows[p] is PItem
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View = when (val r = rows[p]) {
            is PHeader -> ((cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = VERTICAL; setPadding(act.dp(16), act.dp(18), act.dp(16), act.dp(6))
                addView(act.text("", 16f, C.TEXT, bold = true, lines = 2)); addView(act.text("", 13f, C.DIM, lines = 0))
            }).apply {
                (getChildAt(0) as TextView).text = r.text
                (getChildAt(1) as TextView).apply { text = r.sub ?: ""; visibility = if (r.sub == null) GONE else VISIBLE }
            }
            is PItem -> ((cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(act.dp(8), act.dp(6), act.dp(16), act.dp(6))
                addView(CheckBox(act).apply { isClickable = false; isFocusable = false })
                addView(LinearLayout(act).apply {
                    orientation = VERTICAL
                    addView(act.text("", 14f, C.TEXT, lines = 2)); addView(act.text("", 12f, C.DIM, lines = 4))
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

    private fun rebuild() {
        val s = scan ?: return
        val p = Plan.build(s.files, keepDup, dontMove, include)
        plan = p
        val rows = ArrayList<PRow>()
        // what the library will look like
        val target = p.moves.map { it.file } + p.inPlace + p.keptByUser
        val albums = p.albumFolders.size
        val artists = p.albumFolders.map { it.substringBefore('/').lowercase() }.distinct().size
        val genres = target.map { Text.fold(it.tags.genre) }.filter { it.isNotBlank() }.distinct().size
        val freed = Text.bytes(p.bytesFreed)
        rows += PHeader(
            tr("Plan: ${s.files.size} audio files found", "Plan: ${s.files.size} ses dosyası bulundu"),
            tr("• ${p.moves.size} files will move into Music/LocalMusic (${p.inPlace.size} already there)\n" +
                "• ${p.removals.size} duplicates go to the trash, freeing $freed\n" +
                "• ${p.skipped.size} files are not music and stay where they are\n" +
                "• Result: ${p.musicCount} songs · $artists artists · $albums albums · $genres genres\n\n" +
                "Tap a line to leave it out. The plan updates as you go.",
                "• ${p.moves.size} dosya Music/LocalMusic içine taşınacak (${p.inPlace.size} zaten orada)\n" +
                "• ${p.removals.size} kopya çöp kutusuna gider, $freed boşalır\n" +
                "• ${p.skipped.size} dosya müzik değil, yerinde kalır\n" +
                "• Sonuç: ${p.musicCount} şarkı · $artists sanatçı · $albums albüm · $genres tür\n\n" +
                "Dışarıda bırakmak için bir satıra dokunun. Plan siz ilerledikçe güncellenir."),
        )
        if (p.removals.isNotEmpty() || keepDup.isNotEmpty()) {
            rows += PHeader(tr("Duplicates to remove (${p.removals.size} · $freed)", "Kaldırılacak kopyalar (${p.removals.size} · $freed)"),
                tr("The better copy is kept. Untick to keep both.", "Daha iyi kopya tutulur. İkisini de tutmak için işareti kaldırın."))
            for (g in Dedupe.find(s.files.filter { it.skipReason == null || it.id in include })) {
                for (r in g.remove) {
                    val on = r.id !in keepDup
                    rows += PItem(r.id, K_REMOVE, "${r.tags.title} — ${r.tags.artist.ifBlank { "?" }}",
                        tr("remove  ${r.path}\n           ${Organizer.describe(r)}\nkeep      ${g.keep.path}\n           ${Organizer.describe(g.keep)}",
                            "kaldır  ${r.path}\n           ${Organizer.describe(r)}\ntut         ${g.keep.path}\n           ${Organizer.describe(g.keep)}") +
                            if (g.identical) tr("\n(identical files)", "\n(aynı dosyalar)") else "", on)
                }
            }
        }
        val moveRows = p.moves.map { PItem(it.file.id, K_MOVE, it.toPath.removePrefix(Naming.ROOT), tr("from ${it.file.path}", "kaynak: ${it.file.path}") +
            if (it.file.tags.fromFileName) tr("  (no tags: named from the file name)", "  (etiket yok: dosya adından adlandırıldı)") else "", true) } +
            p.keptByUser.map { PItem(it.id, K_MOVE, it.path, tr("stays where it is", "yerinde kalır"), false) }
        if (moveRows.isNotEmpty()) {
            rows += PHeader(tr("Files to move (${p.moves.size})", "Taşınacak dosyalar (${p.moves.size})"), tr("Into ${Naming.ROOT}", "Hedef: ${Naming.ROOT}"))
            rows += moveRows.sortedBy { it.title.lowercase() }
        }
        val skipRows = s.files.filter { it.skipReason != null }.map { f ->
            PItem(f.id, K_SKIP, f.path, if (f.id in include) tr("will be organised as music", "müzik olarak düzenlenecek") else tr("left alone: ${f.skipReason}", "dokunulmayacak: ${f.skipReason}"), f.id in include)
        }
        if (skipRows.isNotEmpty()) {
            rows += PHeader(tr("Not music (${skipRows.size})", "Müzik değil (${skipRows.size})"),
                tr("Left where they are. Tick one to organise it as music anyway.", "Yerlerinde kalırlar. Yine de müzik olarak düzenlemek için birini işaretleyin."))
            rows += skipRows
        }
        adapter.rows = rows
        adapter.notifyDataSetChanged()
        if (body.getChildAt(0) !is LinearLayout || (body.getChildAt(0) as LinearLayout).tag != "plan") showPlanLayout()
        applyBtn.text = if (p.moves.isEmpty() && p.removals.isEmpty()) tr("Nothing to do", "Yapılacak bir şey yok") else tr("Apply plan…", "Planı uygula…")
    }

    private lateinit var applyBtn: TextView

    private fun showPlanLayout() {
        val wrap = LinearLayout(act).apply { orientation = VERTICAL; tag = "plan" }
        val lv = ListView(act).apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(0)
            isFastScrollEnabled = true
            adapter = this@OrganizerView.adapter
            setOnItemClickListener { _, _, pos, _ -> (this@OrganizerView.adapter.rows[pos] as? PItem)?.let { toggle(it) } }
        }
        wrap.addView(lv, lpL(MATCH, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(16), act.dp(10), act.dp(16), act.dp(10)); setBackgroundColor(C.BG) }
        bar.addView(status.also { (it.parent as? ViewGroup)?.removeView(it); it.text = "" }, lpL(0, WRAP, 1f))
        applyBtn = act.pill(tr("Apply plan…", "Planı uygula…"), C.ACCENT, 0xFF000000.toInt()) { confirmApply() }
        bar.addView(applyBtn)
        wrap.addView(bar, lpL(MATCH, WRAP))
        setBody(wrap)
    }

    private fun toggle(r: PItem) {
        if (busy) return
        if (r.kind == K_SKIP && scan?.files?.firstOrNull { it.id == r.id }?.let { PlainYoutube.isCached(it.relPath) } == true) {
            act.toast(tr("Plain Youtube manages its cache: left alone", "Önbelleği Plain Youtube yönetir: dokunulmadı")); return
        }
        val set = when (r.kind) { K_REMOVE -> keepDup; K_MOVE -> dontMove; else -> include }
        if (!set.remove(r.id)) set.add(r.id)
        rebuild()
    }

    // --- apply -----------------------------------------------------------------------------------

    private fun confirmApply() {
        val p = plan ?: return
        if (busy || (p.moves.isEmpty() && p.removals.isEmpty())) return
        val freed = Text.bytes(p.bytesFreed)
        val twice = p.moves.isNotEmpty() && p.removals.isNotEmpty()
        AlertDialog.Builder(act).setTitle(tr("Apply this plan?", "Bu plan uygulansın mı?"))
            .setMessage(tr(
                "• Move ${p.moves.size} files into Music/LocalMusic\n" +
                    "• Send ${p.removals.size} duplicates ($freed) to Android's trash — restorable for 30 days\n\n" +
                    "Android will now ask for your permission" + (if (twice) " twice (trash, then move)." else "."),
                "• ${p.moves.size} dosyayı Music/LocalMusic içine taşı\n" +
                    "• ${p.removals.size} kopyayı ($freed) Android'in çöp kutusuna gönder — 30 gün geri yüklenebilir\n\n" +
                    "Şimdi Android izninizi isteyecek" + (if (twice) " (iki kez: önce çöp kutusu, sonra taşıma)." else "."),
            ))
            .setPositiveButton(tr("Continue", "Devam")) { _, _ -> startTrash() }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun uris(ids: List<Long>): List<Uri> = ids.mapNotNull { scan?.uris?.get(it) }

    private fun startTrash() {
        val p = plan ?: return
        busy = true
        if (p.removals.isEmpty()) { startWrite(); return }
        trashAsked = true
        status.text = tr("Waiting for Android's permission (trash)…", "Android'in izni bekleniyor (çöp kutusu)…")
        try {
            val pi = MediaStore.createTrashRequest(act.contentResolver, uris(p.removals.map { it.file.id }), true)
            @Suppress("DEPRECATION")
            act.startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            status.text = tr("Trash request failed: ${e.message}", "Çöp kutusu isteği başarısız: ${e.message}")
            startWrite()
        }
    }

    private fun startWrite() {
        val p = plan ?: return
        trashed = Organizer.trashedIds(act, p.removals.map { it.file.id })
        if (p.moves.isEmpty()) { finish(null); return }
        status.text = tr("Waiting for Android's permission (move)…", "Android'in izni bekleniyor (taşıma)…")
        try {
            val pi = MediaStore.createWriteRequest(act.contentResolver, uris(p.moves.map { it.file.id }))
            @Suppress("DEPRECATION")
            act.startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
        } catch (e: Exception) {
            status.text = tr("Write request failed: ${e.message}", "Yazma isteği başarısız: ${e.message}")
            finish(null)
        }
    }

    fun onConsent(req: Int, ok: Boolean) {
        when (req) {
            REQ_TRASH -> startWrite() // denied: duplicates simply stay; moves are asked for separately
            REQ_WRITE -> if (!ok) finish(null) else {
                val p = plan ?: return
                val s = scan ?: return
                val app = act.applicationContext
                Thread {
                    val r = Organizer.applyMoves(app, p.moves, s.uris) { msg -> post { status.text = msg } }
                    post { finish(r) }
                }.start()
            }
        }
    }

    private fun finish(r: Organizer.Result?) {
        val p = plan ?: return
        busy = false
        val text = Organizer.report(act, p, r, trashed, trashAsked)
        val col = LinearLayout(act).apply { orientation = VERTICAL; setPadding(act.dp(16), act.dp(8), act.dp(16), act.dp(24)) }
        val title = when {
            r == null && trashed.isEmpty() -> tr("Nothing was changed", "Hiçbir şey değişmedi")
            r != null && r.failed.isNotEmpty() -> tr("Done, with ${r.failed.size} problems", "Bitti, ${r.failed.size} sorunla")
            else -> tr("Done", "Bitti")
        }
        col.addView(act.text(title, 20f, C.TEXT, bold = true))
        col.addView(act.text(text, 12.5f, C.TEXT, lines = 0).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }, lpL(MATCH, WRAP).apply { topMargin = act.dp(12) })
        setBody(ScrollView(act).apply { addView(col) })
        act.loadLibrary()
    }
}

/**
 * Android's trash (what LocalMusic, Plain Youtube or anything else trashed): audio and Plain Youtube's videos, with the
 * days until Android deletes them for good. Restore, Delete now, Empty trash. Restore/delete ask Android's consent
 * unless "Media management" is granted.
 */
@SuppressLint("ViewConstructor")
class TrashView(private val act: MainActivity) : LinearLayout(act) {
    private class Item(val t: TrashItem, var checked: Boolean = false)
    private var items = ArrayList<Item>()
    private val list = ListView(act)
    private val title = act.text(tr("Trash", "Çöp kutusu"), 20f, C.TEXT, bold = true)
    private val info = act.text("", 13f, C.DIM, lines = 2)

    companion object {
        const val REQ_UNTRASH = 201
        const val REQ_DELETE = 202
    }

    private val adapter = object : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = items[p].t.id
        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val it = items[p]
            val v = (cv as? LinearLayout) ?: LinearLayout(act).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(8), act.dp(6), act.dp(16), act.dp(6))
                addView(CheckBox(act).apply { isClickable = false; isFocusable = false })
                addView(LinearLayout(act).apply { orientation = VERTICAL; addView(act.text("", 14f, lines = 2)); addView(act.text("", 12f, C.DIM)) }, lpL(0, WRAP, 1f))
            }
            (v.getChildAt(0) as CheckBox).isChecked = it.checked
            val col = v.getChildAt(1) as LinearLayout
            (col.getChildAt(0) as TextView).text = it.t.path
            val days = TrashMath.daysLeft(it.t.expires, System.currentTimeMillis())
            (col.getChildAt(1) as TextView).text = listOfNotNull(if (it.t.video) tr("Video", "Video") else null, Text.bytes(it.t.size),
                if (days == 0L) tr("deleted for good today", "bugün kalıcı olarak silinir")
                else tr("deleted for good in ${Text.count(days.toInt(), "day")}", "${Text.count(days.toInt(), "day")} sonra kalıcı olarak silinir")).joinToString(" · ")
            return v
        }
    }

    init {
        orientation = VERTICAL
        isClickable = true
        setBackgroundColor(C.PANEL)
        val top = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(6), act.dp(6), act.dp(12), act.dp(6)) }
        top.addView(act.icon(R.drawable.mus_ic_close, desc = tr("Close", "Kapat")) { act.closeOverlay(this) })
        val t = LinearLayout(act).apply { orientation = VERTICAL }
        t.addView(title); t.addView(info)
        top.addView(t, lpL(0, WRAP, 1f))
        addView(top, lpL(MATCH, WRAP))
        list.divider = null
        list.adapter = adapter
        list.setOnItemClickListener { _, _, p, _ -> items[p].checked = !items[p].checked; adapter.notifyDataSetChanged(); updateInfo() }
        addView(list, lpL(MATCH, 0, 1f))
        val bar = LinearLayout(act).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(12), act.dp(10), act.dp(12), act.dp(10)) }
        bar.addView(act.pill(tr("All", "Tümü"), C.BG) { val all = items.all { it.checked }; items.forEach { it.checked = !all }; adapter.notifyDataSetChanged(); updateInfo() })
        bar.addView(act.pill(tr("Restore", "Geri yükle"), C.ACCENT, 0xFF000000.toInt()) { restore() }, lpL(WRAP, WRAP).apply { leftMargin = act.dp(8) })
        bar.addView(act.pill(tr("Delete now", "Şimdi sil"), C.BG, C.RED, C.RED) { delete(items.filter { it.checked }, tr("the selected files", "seçili dosyalar")) }, lpL(WRAP, WRAP).apply { leftMargin = act.dp(8) })
        bar.addView(act.pill(tr("Empty trash", "Çöp kutusunu boşalt"), C.BG, C.RED, C.RED) { delete(items, tr("everything in the trash", "çöp kutusundaki her şey")) }, lpL(WRAP, WRAP).apply { leftMargin = act.dp(8) })
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; addView(bar) }, lpL(MATCH, WRAP))
        load()
    }

    private fun load() {
        items = ArrayList(Eraser.queryTrash(act).sortedByDescending { it.expires }.map { Item(it) })
        title.text = if (items.isEmpty()) tr("Trash", "Çöp kutusu") else tr("Trash", "Çöp kutusu") + " (${items.size} · ${Text.bytes(items.sumOf { it.t.size })})"
        adapter.notifyDataSetChanged()
        updateInfo()
    }

    private fun updateInfo() {
        val n = items.count { it.checked }
        info.text = when {
            items.isEmpty() -> tr("The trash is empty.", "Çöp kutusu boş.")
            n > 0 -> tr("$n selected", "$n seçili") + " · ${Text.bytes(items.filter { it.checked }.sumOf { it.t.size })}"
            else -> tr("Android deletes trashed files for good after about 30 days. Tap to select.", "Android çöpteki dosyaları yaklaşık 30 gün sonra kalıcı olarak siler. Seçmek için dokunun.")
        }
    }

    private fun restore() {
        val sel = items.filter { it.checked }
        if (sel.isEmpty()) { act.toast(tr("Select files to restore", "Geri yüklenecek dosyaları seçin")); return }
        act.eraser.restore(sel.map { it.t }, REQ_UNTRASH)
    }

    private fun delete(sel: List<Item>, what: String) {
        if (sel.isEmpty()) { act.toast(if (items.isEmpty()) tr("The trash is empty", "Çöp kutusu boş") else tr("Select files first", "Önce dosyaları seçin")); return }
        act.confirm(tr("Delete $what for good?", what.replaceFirstChar { it.titlecase(PlainLang.locale) } + " kalıcı olarak silinsin mi?"),
            "${Text.count(sel.size, "file")} · ${Text.bytes(sel.sumOf { it.t.size })}" + tr("\n\nThis cannot be undone.", "\n\nBu işlem geri alınamaz."),
            tr("Delete for good", "Kalıcı olarak sil")) {
            act.eraser.deleteForGood(sel.map { it.t }, REQ_DELETE)
        }
    }

    fun onConsent(req: Int, ok: Boolean) {
        if (req != REQ_UNTRASH && req != REQ_DELETE) return
        if (ok) act.toast(if (req == REQ_UNTRASH) tr("Restored", "Geri yüklendi") else tr("Deleted for good", "Kalıcı olarak silindi"))
        load()
        act.loadLibrary()
    }
}
