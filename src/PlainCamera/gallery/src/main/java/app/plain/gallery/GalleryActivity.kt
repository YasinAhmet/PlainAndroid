package app.plain.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * All photos and videos, newest first, one fixed page at a time (Previous / Next, no endless
 * scrolling). Thumbnails come from the media store's thumbnail cache, never from full images.
 *
 * The ⋮ menu turns on categories (shown as folders), "hold to delete" (a long press moves the
 * item to the trash at once, with Undo) and selection (bulk categorize / delete / remove).
 */
class GalleryActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val thumbs = Executors.newFixedThreadPool(2)
    private val gen = AtomicInteger()
    private var signal = CancellationSignal()

    private val cache = object : LruCache<Long, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(8 * 1024, 64 * 1024)
    ) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount / 1024
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var grid: PageGrid
    private lateinit var back: TextView
    private lateinit var title: TextView
    private lateinit var pageSel: TextView
    private lateinit var menuBtn: TextView
    private lateinit var camBtn: TextView
    private lateinit var pageLabel: TextView
    private lateinit var prev: TextView
    private lateinit var next: TextView
    private lateinit var message: TextView
    private lateinit var actions: LinearLayout
    private lateinit var catBtn: TextView
    private lateinit var removeBtn: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var undo: UndoBar

    /** The whole library, newest first. */
    private var items: List<Item> = emptyList()
    private var loaded = false
    private var page = 0
    private var perPage = 0
    private var gw = 0
    private var gh = 0
    private var resumed = false
    private var dirty = true

    private enum class Screen { ALL, FOLDERS, CATEGORY, UNSORTED }
    private class Folder(val name: String, val screen: Screen, val cover: Item?, val count: Int)

    private var screen = Screen.ALL
    private var category: String? = null
    private var folderPage = 0
    /** The items on show (all, one category, or the uncategorized ones). */
    private var shown: List<Item> = emptyList()
    private var folders: List<Folder> = emptyList()

    private var selecting = false
    private val selected = LinkedHashSet<Long>()
    private var pendingTrash: List<Item> = emptyList()
    private var pendingRestore: List<Item> = emptyList()
    /** What the Undo bar would put back (quick deletes in a row add up). */
    private val undoBatch = ArrayList<Item>()

    private val catsOn get() = prefs.getBoolean("categories", false)
    private val holdDelete get() = prefs.getBoolean("holdDelete", false)

    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            dirty = true
            if (resumed) { main.removeCallbacks(reload); main.postDelayed(reload, 600) }
        }
    }
    private val reload = Runnable { load() }

    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        if (Hub.gate(this, "gallery")) { finish(); return }  // old Plain Gallery's data first
        prefs = getSharedPreferences("gallery", MODE_PRIVATE)
        page = b?.getInt("page") ?: 0
        folderPage = b?.getInt("folderPage") ?: 0
        category = b?.getString("category")
        screen = b?.getString("screen")?.let { s -> Screen.values().firstOrNull { it.name == s } }
            ?: if (catsOn) Screen.FOLDERS else Screen.ALL
        if (!catsOn) screen = Screen.ALL

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        back = label("‹", 26f).apply { contentDescription = tr("Back", "Geri"); setPadding(0, 0, 0, dp(4)); setOnClickListener { goBack() } }
        title = label(tr("Gallery", "Galeri"), 17f, TEXT2).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        pageSel = button(tr("All on page", "Sayfadakiler")) { selectPage() }.apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f) }
        menuBtn = label("⋮", 22f).apply { contentDescription = tr("Menu", "Menü"); setOnClickListener { showMenu(it) } }
        top.addView(back, LinearLayout.LayoutParams(dp(44), dp(48)))
        top.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        // straight to Plain Camera (shown only when it's installed)
        camBtn = button(tr("Camera", "Kamera")) { openCamera() }.apply {
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(TEXT2)
            contentDescription = tr("Open Plain Camera", "Plain Camera'yı aç")
        }
        top.addView(pageSel, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(10) })
        top.addView(camBtn, LinearLayout.LayoutParams(-2, dp(38)).apply { marginEnd = dp(2) })
        top.addView(menuBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(top, LinearLayout.LayoutParams(-1, dp(48)))

        val frame = FrameLayout(this)
        grid = PageGrid(this).apply {
            thumb = { cache.get(key(it)) }
            isSelected = { it.id in selected }
            onTap = { i -> onCell(i) }
            onHold = { i -> onHoldCell(i) }
            onFling = { d -> go(page + d) }
            onSize = { w, h -> relayout(w, h) }
        }
        frame.addView(grid, FrameLayout.LayoutParams(-1, -1))
        message = label("", 16f, TEXT2).apply { visibility = View.GONE }
        frame.addView(message, FrameLayout.LayoutParams(-1, -1))
        undo = UndoBar(this).apply { onGone = { undoBatch.clear() } }
        frame.addView(undo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(8)) })
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // selection actions, above the pager
        actions = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(6), dp(8), dp(6), 0)
        }
        val alp = { LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4); marginEnd = dp(4) } }
        catBtn = button(tr("Categorize", "Kategorile")) { categorize() }
        removeBtn = button(tr("Remove", "Çıkar")) { removeFromCategory() }.apply { contentDescription = tr("Remove from this category", "Bu kategoriden çıkar") }
        deleteBtn = button(tr("Delete", "Sil")) { trash(selectedItems()) }
        actions.addView(catBtn, alp())
        actions.addView(removeBtn, alp())
        actions.addView(deleteBtn, alp())
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        prev = button(tr("‹ Previous", "‹ Önceki")) { go(page - 1) }
        next = button(tr("Next ›", "Sonraki ›")) { go(page + 1) }
        pageLabel = label("", 15f, TEXT2).apply { setOnClickListener { askPage() } }
        bar.addView(prev, LinearLayout.LayoutParams(-2, dp(44)))
        bar.addView(pageLabel, LinearLayout.LayoutParams(0, dp(44), 1f))
        bar.addView(next, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))

        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(Hub.wrap(this, "gallery", root))
        updateBars()
        contentResolver.registerContentObserver(Library.watch, true, observer)
        if (!hasRead()) requestPermissions(readPerms(), 1)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("page", page)
        out.putInt("folderPage", folderPage)
        out.putString("screen", screen.name)
        out.putString("category", category)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (hasRead()) { if (dirty) load() } else showMessage(tr("Allow access to your photos and videos to see them here.\n\nTap to allow.", "Fotoğraf ve videolarınızı burada görmek için erişim izni verin.\n\nİzin vermek için dokunun."))
    }

    override fun onPause() {
        resumed = false
        main.removeCallbacks(reload)
        super.onPause()
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        signal.cancel()
        loader.shutdownNow()
        thumbs.shutdownNow()
        super.onDestroy()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) cache.trimToSize(cache.maxSize() / 4)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (selecting || (catsOn && screen != Screen.FOLDERS)) goBack() else super.onBackPressed()
    }

    private fun readPerms() = if (Build.VERSION.SDK_INT >= 33)
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasRead() = readPerms().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED)

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        if (hasRead()) { hideMessage(); load() }
        else showMessage(tr("Allow access to your photos and videos to see them here.\n\nTap to allow.", "Fotoğraf ve videolarınızı burada görmek için erişim izni verin.\n\nİzin vermek için dokunun."))
    }

    private fun showMessage(t: String) {
        message.text = t
        message.visibility = View.VISIBLE
        message.setOnClickListener {
            if (hasRead()) return@setOnClickListener
            if (readPerms().any { shouldShowRequestPermissionRationale(it) } || !asked) { asked = true; requestPermissions(readPerms(), 1) }
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }
    private var asked = false

    private fun hideMessage() { message.visibility = View.GONE; message.setOnClickListener(null); message.isClickable = false }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private val hasCamera = true  // the Camera tab of this app

    private fun openCamera() = Hub.open(this, "camera")

    // ------------------------------------------------------------------ data
    private fun load() {
        dirty = false
        try {
            loader.execute {
                val l = try { Library.load(this) } catch (e: Exception) { null }
                main.post {
                    if (isDestroyed) return@post
                    if (l == null) { showMessage(tr("Couldn't read the media library.", "Medya kitaplığı okunamadı.")); return@post }
                    items = l
                    loaded = true
                    refresh()
                }
            }
        } catch (_: Exception) {}
    }

    /** Rebuilds what's on show from the library and the categories. */
    private fun refresh() {
        val t = Categories.get(this)
        if (screen == Screen.CATEGORY && !t.has(category ?: "")) { screen = Screen.FOLDERS; page = folderPage; perPage = 0; layoutGrid() }
        when (screen) {
            Screen.FOLDERS -> folders = buildFolders(t)
            Screen.CATEGORY -> { val s = t.ids(category ?: ""); shown = items.filter { it.id in s } }
            Screen.UNSORTED -> { val all = t.allIds(); shown = items.filter { it.id !in all } }
            Screen.ALL -> shown = items
        }
        if (screen != Screen.FOLDERS) Library.shown = shown
        if (selecting) {
            val ids = shown.mapTo(HashSet()) { it.id }
            selected.retainAll(ids)
        }
        updateBars()
        show()
    }

    private fun buildFolders(t: Tags): List<Folder> {
        val byId = t.byId()
        val covers = HashMap<String, Item>()
        val counts = HashMap<String, Int>()
        var unsorted = 0
        var unsortedCover: Item? = null
        for (it in items) {
            val ns = byId[it.id]
            if (ns == null) { unsorted++; if (unsortedCover == null) unsortedCover = it }
            else for (n in ns) { counts[n] = (counts[n] ?: 0) + 1; covers.putIfAbsent(n, it) }
        }
        val out = ArrayList<Folder>()
        out += Folder(tr("All", "Tümü"), Screen.ALL, items.firstOrNull(), items.size)
        for (n in t.names) out += Folder(n, Screen.CATEGORY, covers[n], counts[n] ?: 0)
        if (t.names.isNotEmpty()) out += Folder(tr("Uncategorized", "Kategorisiz"), Screen.UNSORTED, unsortedCover, unsorted)
        return out
    }

    private fun listSize() = if (screen == Screen.FOLDERS) folders.size else shown.size

    private fun relayout(w: Int, h: Int) {
        if (w == 0 || h == 0) return
        gw = w; gh = h
        layoutGrid()
        show()
    }

    /** Cells per page for the current screen; keeps the first visible item on screen. */
    private fun layoutGrid() {
        if (gw == 0 || gh == 0) return
        val first = page * perPage
        if (screen == Screen.FOLDERS) {
            grid.cols = if (gw > gh) 5 else 3
            grid.rows = (gh / (gw / grid.cols + grid.labelH)).coerceAtLeast(1)
        } else {
            grid.cols = if (gw > gh) 6 else 4
            grid.rows = (gh / (gw / grid.cols)).coerceAtLeast(1)
        }
        val per = grid.cols * grid.rows
        if (perPage > 0 && per != perPage) page = Paging.pageOf(first, per)
        perPage = per
    }

    private fun switchTo(s: Screen, cat: String?, p: Int) {
        if (selecting) endSelect()
        undo.dismiss()
        screen = s
        category = cat
        perPage = 0
        layoutGrid()
        page = p
        refresh()
    }

    private fun go(p: Int) {
        val pages = Paging.pageCount(listSize(), perPage)
        if (p !in 0 until pages) return
        page = p
        show()
    }

    private fun show() {
        if (perPage == 0) return
        val n = listSize()
        val pages = Paging.pageCount(n, perPage)
        page = page.coerceIn(0, pages - 1)
        val r = Paging.range(page, perPage, n)
        grid.selecting = selecting
        if (screen == Screen.FOLDERS) {
            val f = r.map { folders[it] }
            grid.labels = f.map { it.name to Paging.count(it.count) }
            grid.items = f.map { it.cover }
            grid.contentDescription = tr("Folder grid, ${f.size} folders", "Klasör ızgarası, ${f.size} klasör")
            loadThumbs(f.mapNotNull { it.cover }, emptyList())
        } else {
            grid.labels = null
            val slice = r.map { shown[it] }
            grid.items = slice
            grid.contentDescription = tr("Page grid, ${slice.size} items", "Sayfa ızgarası, ${slice.size} öğe")
            loadThumbs(slice, Paging.range(page + 1, perPage, n).map { shown[it] })
        }
        pageLabel.text = tr("Page ${page + 1} / $pages", "Sayfa ${page + 1} / $pages")
        prev.isEnabled = page > 0
        prev.alpha = if (page > 0) 1f else 0.35f
        next.isEnabled = page < pages - 1
        next.alpha = if (page < pages - 1) 1f else 0.35f
        if (hasRead() && loaded) {
            when {
                items.isEmpty() -> showMessage(tr("No photos or videos yet.", "Henüz fotoğraf veya video yok."))
                screen == Screen.CATEGORY && shown.isEmpty() ->
                    showMessage(tr("Nothing in “$category” yet.\n\nOpen All, choose ⋮ › Select, pick photos and tap Categorize.",
                        "“$category” henüz boş.\n\nTümü'nü açın, ⋮ › Seç'i seçin, fotoğrafları işaretleyip Kategorile'ye dokunun."))
                screen == Screen.UNSORTED && shown.isEmpty() -> showMessage(tr("Everything is in a category.", "Her şey bir kategoride."))
                else -> hideMessage()
            }
        }
    }

    private fun key(it: Item) = it.id * 2 + if (it.video) 1 else 0

    /** This page's thumbnails first, then the next page's (so Next is instant). Old requests are dropped. */
    private fun loadThumbs(now: List<Item>, ahead: List<Item>) {
        signal.cancel()
        val sig = CancellationSignal().also { signal = it }
        val g = gen.incrementAndGet()
        val sz = Size(grid.cellW.coerceAtLeast(96), grid.cellH.coerceAtLeast(96))
        for ((list, visible) in listOf(now to true, ahead to false)) for (it in list) {
            if (cache.get(key(it)) != null) continue
            try {
                thumbs.execute {
                    if (gen.get() != g || cache.get(key(it)) != null) return@execute
                    val b = try { contentResolver.loadThumbnail(it.uri, sz, sig) } catch (_: Exception) { null } ?: return@execute
                    cache.put(key(it), b)
                    if (visible) main.post { if (gen.get() == g) grid.invalidate() }
                }
            } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------ taps
    private fun onCell(i: Int) {
        val k = page * perPage + i
        if (screen == Screen.FOLDERS) { folders.getOrNull(k)?.let { openFolder(it) }; return }
        val it = shown.getOrNull(k) ?: return
        if (selecting) toggle(it) else open(k)
    }

    private fun onHoldCell(i: Int) {
        val k = page * perPage + i
        if (screen == Screen.FOLDERS) {
            val f = folders.getOrNull(k) ?: return
            if (f.screen == Screen.CATEGORY) folderOptions(f.name)
            return
        }
        val it = shown.getOrNull(k) ?: return
        when {
            selecting -> toggle(it)
            holdDelete -> trash(listOf(it))
            else -> { startSelect(); toggle(it) }
        }
    }

    private fun openFolder(f: Folder) {
        folderPage = page
        switchTo(f.screen, if (f.screen == Screen.CATEGORY) f.name else null, 0)
    }

    private fun goBack() {
        when {
            selecting -> endSelect()
            catsOn && screen != Screen.FOLDERS -> switchTo(Screen.FOLDERS, null, folderPage)
            else -> finish()
        }
    }

    private fun open(index: Int) {
        if (index !in shown.indices) return
        Library.shown = shown
        startActivityForResult(Intent(this, ViewerActivity::class.java).putExtra("index", index), RC_VIEW)
    }

    // ------------------------------------------------------------------ menu
    private fun showMenu(anchor: View) {
        val m = PopupMenu(this, anchor)
        if (screen != Screen.FOLDERS) m.menu.add(0, 1, 0, tr("Select", "Seç"))
        if (screen == Screen.FOLDERS) m.menu.add(0, 4, 0, tr("New category…", "Yeni kategori…"))
        m.menu.add(0, 2, 1, tr("Categories", "Kategoriler")).setCheckable(true).isChecked = catsOn
        m.menu.add(0, 3, 2, tr("Hold to delete", "Basılı tutarak sil")).setCheckable(true).isChecked = holdDelete
        m.menu.add(0, 6, 3, PlainLang.label())
        m.menu.add(0, 5, 4, PlainUpdate.label(this))
        m.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> startSelect()
                2 -> setCats(!catsOn)
                3 -> setHoldDelete(!holdDelete)
                4 -> Categories.askName(this, tr("New category", "Yeni kategori"), "") { n -> Categories.get(this).create(n); Categories.save(this); refresh() }
                5 -> PlainUpdate.check(this)
                6 -> PlainLang.choose(this, anchor)
            }
            true
        }
        m.show()
    }

    private fun setCats(on: Boolean) {
        prefs.edit().putBoolean("categories", on).apply()
        if (on) switchTo(Screen.FOLDERS, null, 0)
        else switchTo(Screen.ALL, null, if (screen == Screen.ALL) page else 0)
    }

    private fun canManage() = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(this)

    private fun setHoldDelete(on: Boolean) {
        prefs.edit().putBoolean("holdDelete", on).apply()
        if (!on) { toast(tr("Hold now selects.", "Basılı tutmak artık seçer.")); return }
        if (Build.VERSION.SDK_INT >= 31 && !canManage()) {
            AlertDialog.Builder(this)
                .setTitle(tr("Hold to delete is on", "Basılı tutarak silme açık"))
                .setMessage(tr("Holding a photo moves it to the trash (Undo for a few seconds, restorable for 30 days).\n\n" +
                    "To skip Android's confirmation each time, allow Plain Gallery under “Media management”. " +
                    "Without it, Android asks once per delete.",
                    "Bir fotoğrafı basılı tutmak onu çöp kutusuna taşır (birkaç saniye Geri al, 30 gün içinde geri yüklenebilir).\n\n" +
                    "Android'in her seferki onayını atlamak için “Medya yönetimi” altında Plain Gallery'ye izin verin. " +
                    "İzin yoksa Android her silmede bir kez sorar."))
                .setPositiveButton(tr("Open setting", "Ayarı aç")) { _, _ ->
                    try { startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA, Uri.parse("package:$packageName"))) }
                    catch (_: Exception) { toast(tr("Couldn't open the setting.", "Ayar açılamadı.")) }
                }
                .setNegativeButton(tr("Not now", "Şimdi değil"), null)
                .show()
        } else toast(tr("Hold a photo to move it to the trash.", "Çöp kutusuna taşımak için fotoğrafı basılı tutun."))
    }

    // ------------------------------------------------------------------ selection
    private fun startSelect() {
        if (screen == Screen.FOLDERS) return
        undo.dismiss()
        selecting = true
        selected.clear()
        updateBars()
        grid.selecting = true
    }

    private fun endSelect() {
        selecting = false
        selected.clear()
        updateBars()
        grid.selecting = false
    }

    private fun toggle(it: Item) {
        if (!selected.remove(it.id)) selected += it.id
        updateBars()
        grid.invalidate()
    }

    private fun selectPage() {
        val ids = Paging.range(page, perPage, shown.size).map { shown[it].id }
        if (selected.containsAll(ids)) selected.removeAll(ids.toSet()) else selected.addAll(ids)
        updateBars()
        grid.invalidate()
    }

    private fun selectedItems() = shown.filter { it.id in selected }

    private fun updateBars() {
        val nested = catsOn && screen != Screen.FOLDERS
        back.visibility = if (selecting || nested) View.VISIBLE else View.GONE
        back.text = if (selecting) "✕" else "‹"
        back.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, if (selecting) 20f else 26f)
        back.contentDescription = if (selecting) tr("Cancel selection", "Seçimi iptal et") else tr("Back", "Geri")
        title.setPadding(dp(if (selecting || nested) 2 else 14), dp(8), dp(8), dp(8))
        title.text = when {
            selecting -> tr("${Paging.count(selected.size)} selected", "${Paging.count(selected.size)} seçili")
            screen == Screen.FOLDERS -> tr("Categories", "Kategoriler")
            screen == Screen.CATEGORY -> "$category · ${Paging.count(shown.size)}"
            screen == Screen.UNSORTED -> tr("Uncategorized", "Kategorisiz") + " · ${Paging.count(shown.size)}"
            else -> tr("Gallery", "Galeri") + " · ${Paging.count(items.size)}"
        }
        pageSel.visibility = if (selecting) View.VISIBLE else View.GONE
        menuBtn.visibility = if (selecting) View.GONE else View.VISIBLE
        camBtn.visibility = if (selecting || !hasCamera) View.GONE else View.VISIBLE
        actions.visibility = if (selecting) View.VISIBLE else View.GONE
        removeBtn.visibility = if (screen == Screen.CATEGORY) View.VISIBLE else View.GONE
        val any = selected.isNotEmpty()
        for (v in listOf(catBtn, removeBtn, deleteBtn)) { v.isEnabled = any; v.alpha = if (any) 1f else 0.35f }
    }

    // ------------------------------------------------------------------ categories
    private fun categorize() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        Categories.choose(this, ids, {
            endSelect()
            refresh()
            toast(tr("Categories saved.", "Kategoriler kaydedildi."))
        })
    }

    private fun removeFromCategory() {
        val c = category ?: return
        val ids = selected.toList()
        if (ids.isEmpty()) return
        Categories.get(this).remove(c, ids)
        Categories.save(this)
        endSelect()
        refresh()
        toast(tr("Removed ${Paging.count(ids.size)} from “$c”.", "${Paging.count(ids.size)} öğe çıkarıldı: “$c”"))
    }

    /** Hold a category folder: rename or delete it (its photos stay in the gallery). */
    private fun folderOptions(name: String) {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf(tr("Rename", "Yeniden adlandır"), tr("Delete category", "Kategoriyi sil"))) { _, w ->
                val t = Categories.get(this)
                if (w == 0) Categories.askName(this, tr("Rename category", "Kategoriyi yeniden adlandır"), name) { n -> t.rename(name, n); Categories.save(this); refresh() }
                else AlertDialog.Builder(this).setTitle(tr("Delete “$name”?", "“$name” silinsin mi?"))
                    .setMessage(tr("Only the category goes. The photos and videos stay in the gallery.", "Yalnızca kategori silinir. Fotoğraf ve videolar galeride kalır."))
                    .setPositiveButton(tr("Delete", "Sil")) { _, _ -> t.delete(name); Categories.save(this); refresh() }
                    .setNegativeButton(tr("Cancel", "İptal"), null)
                    .show()
            }
            .show()
    }

    // ------------------------------------------------------------------ trash
    /**
     * Moves items to the system trash in one request. With "Media management" allowed there is no
     * prompt at all; otherwise Android confirms once for the whole batch.
     */
    @Suppress("DEPRECATION")
    private fun trash(list: List<Item>) {
        val l = list.filter { it.inStore }
        if (l.isEmpty() || pendingTrash.isNotEmpty()) return
        if (Build.VERSION.SDK_INT < 30) { toast(tr("Open the item to delete it.", "Silmek için öğeyi açın.")); return }
        pendingTrash = l
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, l.map { it.uri }, true)
            startIntentSenderForResult(pi.intentSender, RC_TRASH, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingTrash = emptyList()
            toast(tr("Couldn't delete: ${e.message}", "Silinemedi: ${e.message}"))
        }
    }

    private fun trashed(l: List<Item>) {
        Library.removeAll(l)
        items = Library.items
        if (selecting) endSelect()
        if (!undo.showing) undoBatch.clear()
        undoBatch += l
        val n = undoBatch.size
        undo.show(tr("Moved ${Paging.count(n)} to trash", "${Paging.count(n)} öğe çöpe taşındı")) { restore(ArrayList(undoBatch)) }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun restore(l: List<Item>) {
        if (l.isEmpty() || Build.VERSION.SDK_INT < 30) return
        pendingRestore = l
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, l.map { it.uri }, false)
            startIntentSenderForResult(pi.intentSender, RC_RESTORE, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingRestore = emptyList()
            toast(tr("Couldn't restore: ${e.message}", "Geri yüklenemedi: ${e.message}"))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        when (rc) {
            RC_VIEW -> {
                items = Library.items
                refresh()
                // back on the page of the last item looked at
                val i = data?.getIntExtra("index", -1) ?: -1
                if (i >= 0 && perPage > 0) { page = Paging.pageOf(i, perPage); show() }
            }
            RC_TRASH -> {
                val l = pendingTrash
                pendingTrash = emptyList()
                if (res == RESULT_OK) trashed(l)
            }
            RC_RESTORE -> {
                val l = pendingRestore
                pendingRestore = emptyList()
                if (res == RESULT_OK) {
                    Library.restore(l)
                    items = Library.items
                    refresh()
                    toast(tr("Restored ${Paging.count(l.size)}.", "${Paging.count(l.size)} öğe geri yüklendi."))
                }
            }
        }
    }

    private fun askPage() {
        val pages = Paging.pageCount(listSize(), perPage)
        if (pages <= 1) return
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "1 – $pages"
        }
        AlertDialog.Builder(this)
            .setTitle(tr("Go to page", "Sayfaya git"))
            .setView(e)
            .setPositiveButton(tr("Go", "Git")) { _, _ -> e.text.toString().toIntOrNull()?.let { go((it - 1).coerceIn(0, pages - 1)) } }
            .setNeutralButton(tr("First", "İlk")) { _, _ -> go(0) }
            .setNegativeButton(tr("Last", "Son")) { _, _ -> go(pages - 1) }
            .show()
    }

    companion object {
        private const val RC_VIEW = 2
        private const val RC_TRASH = 3
        private const val RC_RESTORE = 4
        private const val CAMERA_PKG = "app.plain.camera"
    }
}
