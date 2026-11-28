package app.plain.download

import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import app.plain.lang.tr
import app.plain.ui.Ic
import app.plain.ui.Sheet

/**
 * The Custom Feed tab: the user's own feeds ("slots"), one row each with its newest videos in a sideways strip
 * (newest on the left, merged across the feed's channels). Last row: "+ New feed". Hold a row (or its ⋮) to edit it.
 *
 * Network only on user action: Refresh (every feed's channels), and right after channels are added to a feed (just
 * those). Opening the tab shows the saved results. No timers. Feeds: files/custom_feeds.json ([CustomFeedStore]).
 */
class CustomFeedScreen(private val act: MainActivity) {
    companion object {
        /** Feed rows per page (the vertical list is page-based). */
        const val PER = 5
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val calls = Calls()
    private val thumbs = ThumbLoader(act, calls)

    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val info = act.text("", 11f, C.LABEL, lines = 2)
    private val rows = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(12), 0, act.dp(12), act.dp(12)) }
    private val scroll = ScrollView(act).apply { isVerticalScrollBarEnabled = false }
    private val pager = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(12), act.dp(4), act.dp(12), act.dp(4)) }
    private val pageLabel = act.text("", 12f, C.LABEL).apply { gravity = Gravity.CENTER }
    private val prevBtn = act.smallPill(tr("‹ Prev", "‹ Önceki")) { page--; render(); scroll.scrollTo(0, 0) }
    private val nextBtn = act.smallPill(tr("Next ›", "Sonraki ›")) { page++; render(); scroll.scrollTo(0, 0) }

    private var page = 0
    private var shown = false
    private var rendered = false
    private var seenVisited = -1
    private var refreshing = false
    private var doneN = 0
    private var totalN = 0
    private var failedN = 0
    private var noWifi = false
    private var renderPosted = false
    /** Thumbnails of the cards on screen, loaded only once they scroll into view. */
    private val cards = ArrayList<Pair<ImageView, String>>()
    private val asked = HashSet<ImageView>()
    private val rect = android.graphics.Rect()

    init {
        val head = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(4)) }
        head.addView(info, lpL(0, WRAP, 1f))
        head.addView(View(act), lpL(act.dp(8), 1))
        head.addView(act.smallPill(tr("Refresh", "Yenile"), C.ACCENT, C.ACCENT) { refreshAll() })
        scroll.addView(rows)
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> loadVisible() }
        pager.addView(prevBtn)
        pager.addView(pageLabel, lpL(0, WRAP, 1f))
        pager.addView(nextBtn)
        view.addView(head, lpL(MATCH, WRAP))
        view.addView(scroll, lpL(MATCH, 0, 1f))
        view.addView(pager, lpL(MATCH, WRAP))
    }

    fun onShown() {
        if (shown) return
        shown = true
        CustomFeedStore.init(act)
        if (!rendered || seenVisited != Visited.version) render() else { updateInfo(); main.post { loadVisible() } }
    }

    fun onHidden() {
        if (!shown) return
        shown = false
        calls.cancelAll()
        thumbs.cancel()
        asked.clear()
        if (refreshing) { refreshing = false; persist(); updateInfo() }
    }

    fun destroy() {
        onHidden()
        io.shutdownNow()
        thumbs.shutdown()
    }

    // ---- rendering ----

    private fun pool(): List<Item> = CustomFeedStore.cache().items + Store.loadFeed().items

    private fun render() {
        rendered = true
        seenVisited = Visited.version
        val feeds = CustomFeedStore.feeds()
        val pages = CustomFeeds.pages(feeds.size, PER)
        page = page.coerceIn(0, pages - 1)
        rows.removeAllViews()
        cards.clear(); asked.clear()
        thumbs.cancel()
        if (feeds.isEmpty()) rows.addView(act.text(tr("Make your own feeds from channels you follow or find in search. Each feed shows its newest videos in one row.",
            "Takip ettiğiniz ya da aramada bulduğunuz kanallardan kendi akışlarınızı oluşturun. Her akış en yeni videolarını tek satırda gösterir."), 13f, C.DIM, lines = 0)
            .apply { setPadding(act.dp(4), act.dp(8), act.dp(4), act.dp(8)) }, lpL(MATCH, WRAP))
        val pool = pool()
        val from = page * PER
        for (f in feeds.drop(from).take(PER)) rows.addView(feedRow(f, pool), lpL(MATCH, WRAP).apply { topMargin = act.dp(10) })
        if (from + PER > feeds.size) rows.addView(newFeedRow(), lpL(MATCH, WRAP).apply { topMargin = act.dp(14) })
        pager.visibility = if (pages > 1) View.VISIBLE else View.GONE
        pageLabel.text = tr("Page ${page + 1} of $pages", "Sayfa ${page + 1} / $pages")
        prevBtn.isEnabled = page > 0; prevBtn.alpha = if (page > 0) 1f else 0.35f
        nextBtn.isEnabled = page < pages - 1; nextBtn.alpha = if (page < pages - 1) 1f else 0.35f
        updateInfo()
        main.post { loadVisible() }
    }

    private fun updateInfo() {
        val c = CustomFeedStore.cache()
        info.text = when {
            refreshing -> tr("Refreshing… $doneN of $totalN channels", "Yenileniyor… $doneN / $totalN kanal")
            else -> {
                val last = maxOf(c.lastRefresh, Store.loadFeed().lastRefresh)
                (if (last > 0) tr("Updated ${Ago.text(last, System.currentTimeMillis())}", "Güncellendi: ${Ago.text(last, System.currentTimeMillis())}")
                else tr("Not loaded yet · tap Refresh", "Henüz yüklenmedi · Yenile'ye dokunun")) +
                    (if (noWifi) tr(" · not on Wi-Fi, showing saved videos", " · Wi-Fi bağlı değil, kayıtlı videolar gösteriliyor") else "") +
                    (if (failedN > 0) tr(" · $failedN channels failed", " · $failedN kanal başarısız") else "")
            }
        }
    }

    private fun feedRow(f: CFeed, pool: List<Item>): View {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val head = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(4), 0, 0, act.dp(2))
            setOnLongClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); editSheet(f); true }
        }
        head.addView(act.text(f.name, 15f, C.TEXT, bold = true), lpL(0, WRAP, 1f))
        val n = f.channels.size
        head.addView(act.text((if (f.unwatchedOnly) tr("Unwatched · ", "İzlenmemiş · ") else "") + tr("$n channel${if (n == 1) "" else "s"}", "$n kanal"), 11f,
            if (f.unwatchedOnly) C.ACCENT else C.LABEL))
        head.addView(act.icon(R.drawable.yt_ic_more, sizeDp = 36, padDp = 9, tint = C.DIM, desc = tr("Edit feed", "Akışı düzenle")) { editSheet(f) })
        col.addView(head, lpL(MATCH, WRAP))
        val videos = CustomFeeds.strip(f, pool, { Visited.watched(it) })
        if (videos.isEmpty()) {
            val hint = when {
                f.channels.isEmpty() -> tr("No channels yet · hold or ⋮ → Add channels", "Henüz kanal yok · basılı tutun ya da ⋮ → Kanal ekle")
                f.unwatchedOnly && CustomFeeds.strip(f.copy(unwatchedOnly = false), pool).isNotEmpty() -> tr("All watched · tap Refresh for new videos", "Hepsi izlendi · yeni videolar için Yenile'ye dokunun")
                else -> tr("No videos yet · tap Refresh", "Henüz video yok · Yenile'ye dokunun")
            }
            col.addView(act.text(hint, 12f, C.LABEL, lines = 2).apply {
                setPadding(act.dp(12), act.dp(14), act.dp(12), act.dp(14)); background = act.outline(act.dpf(10))
                setOnLongClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); editSheet(f); true }
            }, lpL(MATCH, WRAP))
            return col
        }
        val strip = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in videos) strip.addView(card(i, f), lpL(act.dp(156), WRAP).apply { marginEnd = act.dp(8) })
        col.addView(HorizontalScrollView(act).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
            setOnScrollChangeListener { _, _, _, _, _ -> loadVisible() }
        }, lpL(MATCH, WRAP))
        return col
    }

    private fun card(i: Item, f: CFeed): View {
        val w = act.dp(156)
        val c = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; background = ripple(null) }
        val box = FrameLayout(act)
        val img = RoundImage(act)
        box.addView(img, FrameLayout.LayoutParams(w, w * 9 / 16))
        if (i.members) box.addView(act.membersBadge(), FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP).margins(act.dp(4), act.dp(4), 0, 0))
        if (i.live) box.addView(act.text(tr("LIVE", "CANLI"), 10f, C.RED, bold = true).apply { setPadding(act.dp(5), act.dp(1), act.dp(5), act.dp(1)); background = act.outline(act.dpf(4), C.RED) },
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).margins(0, 0, act.dp(4), act.dp(4)))
        c.addView(box, lpL(w, w * 9 / 16))
        c.addView(act.text(i.title.ifEmpty { i.id }, 12f, C.TEXT, lines = 2).apply { setPadding(act.dp(2), act.dp(4), act.dp(2), 0) }, lpL(MATCH, WRAP))
        val age = i.age.ifEmpty { if (i.published > 0) Ago.text(i.published, System.currentTimeMillis()) else "" }
        c.addView(act.text(listOf(i.channel, age).filter { it.isNotEmpty() }.joinToString(" · "), 11f, C.LABEL).apply { setPadding(act.dp(2), 0, act.dp(2), 0) }, lpL(MATCH, WRAP))
        if (i.thumbId != null || i.id.isNotEmpty()) cards += img to (i.thumbId ?: i.id)
        c.setOnClickListener { play(i, f) }
        c.setOnLongClickListener { v -> v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); hold(i); true }
        return c
    }

    private fun newFeedRow(): View = act.text(tr("+ New feed", "+ Yeni akış"), 15f, C.ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(act.dp(12), act.dp(14), act.dp(12), act.dp(14))
        background = ripple(act.outline(act.dpf(12), C.ACCENT))
        setOnClickListener { newFeed() }
    }

    /** Thumbnails of cards now on screen (vertical page and sideways strips); off-screen ones wait. */
    private fun loadVisible() {
        if (!shown) return
        for ((iv, id) in cards) {
            if (iv in asked || !iv.isAttachedToWindow || !iv.getGlobalVisibleRect(rect)) continue
            asked += iv
            thumbs.load(iv, id, act.dp(156))
        }
    }

    // ---- taps ----

    private fun play(i: Item, f: CFeed) {
        if (i.live && i.published > System.currentTimeMillis()) { act.toast(tr("Not started yet", "Henüz başlamadı")); return }
        if (act.search.membersBlocked(i)) return
        PlayerActivity.stream(act, i.id, i.title, i.channel) // the player marks it visited
        if (f.unwatchedOnly || CustomFeedStore.feeds().any { it.unwatchedOnly }) main.post { if (shown && seenVisited != Visited.version) render() }
    }

    private fun hold(i: Item) {
        val watched = Visited.watched(i.id)
        Hold.video(act, i, null, null, listOf(
            if (watched) tr("Mark as unwatched", "İzlenmedi olarak işaretle") to { Visited.unmark(i.id); Resume.clear(listOf(i.id)); render() }
            else tr("Mark as watched", "İzlendi olarak işaretle") to { Visited.mark(i.id); render() },
        ))
    }

    // ---- editing ----

    private fun feed(id: Long) = CustomFeedStore.feeds().firstOrNull { it.id == id }

    private fun save(l: List<CFeed>) { CustomFeedStore.setFeeds(l); render() }

    private fun nameDialog(title: String, start: String, ok: String, done: (String) -> Unit) {
        if (act.isFinishing || act.isDestroyed) return
        val box = EditText(act).apply {
            setText(start); setSelection(start.length); setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = tr("Feed name", "Akış adı"); setTextColor(C.TEXT); setHintTextColor(C.LABEL)
            setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
        }
        AlertDialog.Builder(act).setTitle(title).setView(box)
            .setPositiveButton(ok) { _, _ -> done(box.text.toString()) }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun newFeed() {
        val all = CustomFeedStore.feeds()
        if (all.size >= CustomFeeds.MAX_FEEDS) { act.toast(tr("At most ${CustomFeeds.MAX_FEEDS} feeds", "En fazla ${CustomFeeds.MAX_FEEDS} akış")); return }
        nameDialog(tr("New feed", "Yeni akış"), "", tr("Next", "İleri")) { name ->
            val (l, f) = CustomFeeds.add(CustomFeedStore.feeds(), name, emptyList(), System.currentTimeMillis())
            page = CustomFeeds.pages(l.size, PER) - 1
            save(l)
            addSheet(f.id)
        }
    }

    private fun editSheet(f0: CFeed) {
        val f = feed(f0.id) ?: return
        val all = CustomFeedStore.feeds()
        val idx = all.indexOfFirst { it.id == f.id }
        val n = f.channels.size
        Sheet(act, C.ACCENT).header(f.name, tr("$n channel${if (n == 1) "" else "s"}", "$n kanal"))
            .item(Ic.EDIT, tr("Rename…", "Yeniden adlandır…")) {
                nameDialog(tr("Rename feed", "Akışı yeniden adlandır"), f.name, tr("Rename", "Yeniden adlandır")) { s ->
                    val name = CustomFeeds.cleanName(s)
                    if (name.isNotEmpty()) save(CustomFeeds.update(CustomFeedStore.feeds(), f.id) { it.copy(name = name) })
                }
            }
            .item(Ic.ADD, tr("Add channels", "Kanal ekle"), more = true) { addSheet(f.id) }
            .item(Ic.CLOSE, tr("Remove channels", "Kanal çıkar"), more = true, enabled = n > 0) { removeSheet(f.id) }
            .toggle(Ic.EYE_OFF, tr("Only unwatched", "Yalnızca izlenmemiş"), f.unwatchedOnly,
                sub = tr("Hides videos you opened or started", "Açtığınız ya da başladığınız videoları gizler")) { on ->
                save(CustomFeeds.update(CustomFeedStore.feeds(), f.id) { it.copy(unwatchedOnly = on) })
            }
            .item(Ic.REFRESH, tr("Refresh this feed", "Bu akışı yenile"), enabled = n > 0) { fetch(f.channels) }
            .item(Ic.SORT, tr("Move up", "Yukarı taşı"), enabled = idx > 0) { moveTo(f.id, -1) }
            .item(Ic.SORT, tr("Move down", "Aşağı taşı"), enabled = idx in 0 until all.size - 1) { moveTo(f.id, 1) }
            .item(Ic.DELETE, tr("Delete feed", "Akışı sil"), danger = true) {
                Sheet(act, C.ACCENT).header(tr("Delete \"${f.name}\"?", "\"${f.name}\" silinsin mi?"),
                    tr("Only this feed goes; followed channels and downloads stay.", "Yalnızca bu akış silinir; takip edilen kanallar ve indirilenler kalır."))
                    .item(Ic.DELETE, tr("Delete", "Sil"), danger = true) {
                        save(CustomFeedStore.feeds().filter { it.id != f.id })
                        persist() // its channels' saved videos go too (unless another feed has them)
                    }
                    .show()
            }
            .show()
    }

    private fun moveTo(id: Long, delta: Int) {
        val l = CustomFeeds.move(CustomFeedStore.feeds(), id, delta)
        val i = l.indexOfFirst { it.id == id }
        if (i >= 0) page = i / PER
        save(l)
    }

    /** Where to add channels from: the followed ones, or a channel search. */
    private fun addSheet(id: Long) {
        val f = feed(id) ?: return
        val follows = Store.follows()
        val have = f.channels.mapTo(HashSet()) { it.id }
        val left = follows.count { it.id !in have }
        Sheet(act, C.ACCENT).header(tr("Add channels to ${f.name}", "${f.name} akışına kanal ekle"))
            .item(Ic.CHECK, tr("From followed channels", "Takip edilen kanallardan"), value = "$left", more = true, enabled = left > 0) { pickFollows(id) }
            .item(Ic.SEARCH, tr("Search channels…", "Kanal ara…"), more = true) { searchDialog(id) }
            .apply { if (follows.isEmpty()) note(tr("Not following any channel yet: search, or follow channels in Search → Feed (Import… brings your YouTube subscriptions).",
                "Henüz hiçbir kanal takip edilmiyor: arayın ya da Ara → Akış'ta kanal takip edin (İçe aktar… YouTube aboneliklerinizi getirir).")) }
            .show()
    }

    /**
     * Multi-select as a sheet: tap rows to tick them, then Done (top or bottom). Closing the sheet any other way
     * changes nothing.
     */
    private fun pickSheet(title: String, sub: String, done: String, danger: Boolean, options: List<Follow>, subOf: (Follow) -> String?, apply: (List<Follow>) -> Unit) {
        val picked = LinkedHashSet<String>()
        val s = Sheet(act, C.ACCENT).header(title, sub)
        val go = { val sel = options.filter { it.id in picked }; if (sel.isNotEmpty()) apply(sel) }
        s.item(Ic.CHECK, done, danger = danger) { go() }
        for (o in options) s.toggle(null, o.title.ifEmpty { o.id }, false, sub = subOf(o)) { on -> if (on) picked += o.id else picked -= o.id }
        if (options.size > 8) s.item(Ic.CHECK, done, danger = danger) { go() }
        s.show()
    }

    private fun pickFollows(id: Long) {
        val f = feed(id) ?: return
        val have = f.channels.mapTo(HashSet()) { it.id }
        val opts = Store.follows().filter { it.id !in have }
        if (opts.isEmpty()) { act.toast(tr("Every followed channel is already in this feed", "Takip edilen her kanal zaten bu akışta")); return }
        pickSheet(tr("Followed channels", "Takip edilen kanallar"), tr("Tap to tick, then Add", "İşaretlemek için dokunun, sonra Ekle'ye basın"),
            tr("Add the ticked channels", "İşaretli kanalları ekle"), false, opts, { null }) { sel -> addChannels(id, sel) }
    }

    private fun removeSheet(id: Long) {
        val f = feed(id) ?: return
        pickSheet(tr("Remove channels", "Kanal çıkar"), f.name, tr("Remove the ticked channels", "İşaretli kanalları çıkar"), true, f.channels, { null }) { sel ->
            val ids = sel.mapTo(HashSet()) { it.id }
            save(CustomFeeds.update(CustomFeedStore.feeds(), id) { CustomFeeds.withoutChannels(it, ids) })
            persist()
        }
    }

    private fun addChannels(id: Long, sel: List<Follow>) {
        save(CustomFeeds.update(CustomFeedStore.feeds(), id) { CustomFeeds.withChannels(it, sel) })
        // the user just asked for these channels: fetch them now (only the ones not loaded in the last half hour)
        val c = CustomFeedStore.cache()
        val fe = Store.loadFeed().fetched
        val now = System.currentTimeMillis()
        val due = sel.filter { now - maxOf(c.fetched[it.id] ?: 0L, fe[it.id] ?: 0L) >= FeedCache.MAX_AGE }
        if (due.isNotEmpty()) fetch(due)
    }

    private fun searchDialog(id: Long) {
        if (act.isFinishing || act.isDestroyed) return
        val box = EditText(act).apply {
            setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT
            hint = tr("Channel name", "Kanal adı"); setTextColor(C.TEXT); setHintTextColor(C.LABEL)
            setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
        }
        AlertDialog.Builder(act).setTitle(tr("Search channels", "Kanal ara")).setView(box)
            .setPositiveButton(tr("Search", "Ara")) { _, _ -> box.text.toString().trim().let { if (it.isNotEmpty()) searchChannels(id, it) } }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun searchChannels(id: Long, q: String) {
        act.toast(tr("Searching…", "Aranıyor…"))
        val g = calls.gen
        io.execute {
            val r = try {
                val chans = Browse.page(WebApi.search(act, calls, g, q, SearchFilters(type = 1).sp())).items.filter { it.kind == IKind.CHANNEL && it.id.startsWith("UC") }
                // Legally blocked channels ("Foşix Erlik" from a Turkish IP) are left out of channel search in every
                // region, but their videos are still found: add matching owners of video results (same as Search › Channels).
                val extra = try {
                    Browse.missingChannels(q, Browse.page(WebApi.search(act, calls, g, q, Clients.SEARCH_VIDEOS)).items, chans.map { it.id })
                        .filter { it.id.startsWith("UC") }
                } catch (e: Cancelled) { throw e } catch (_: Exception) { emptyList() }
                Result.success(extra + chans)
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != calls.gen || act.isFinishing || act.isDestroyed) return@post
                r.fold({ found ->
                    val have = feed(id)?.channels?.mapTo(HashSet()) { it.id } ?: return@fold
                    val opts = found.filter { it.id !in have }
                    if (opts.isEmpty()) { act.toast(if (found.isEmpty()) tr("No channels found", "Kanal bulunamadı") else tr("Those channels are already in this feed", "Bu kanallar zaten bu akışta")); return@fold }
                    val subs = opts.associate { it.id to listOf(it.handle, it.count).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
                    pickSheet(tr("Channels for \"$q\"", "\"$q\" için kanallar"), tr("Tap to tick, then Add", "İşaretlemek için dokunun, sonra Ekle'ye basın"),
                        tr("Add the ticked channels", "İşaretli kanalları ekle"), false, opts.map { Follow(it.id, it.title) }, { subs[it.id]?.ifEmpty { null } }) { sel -> addChannels(id, sel) }
                }, { e ->
                    if (e is Cancelled) return@fold
                    act.toast(if (e is NoWifi) e.message ?: tr("Not on Wi-Fi", "Wi-Fi bağlı değil") else tr("Search failed: ${e.message ?: e.javaClass.simpleName}", "Arama başarısız: ${e.message ?: e.javaClass.simpleName}"))
                })
            }
        }
    }

    // ---- network (user action only) ----

    private fun refreshAll() {
        val feeds = CustomFeedStore.feeds()
        val chans = CustomFeeds.allChannels(feeds)
        if (chans.isEmpty()) {
            act.toast(if (feeds.isEmpty()) tr("Make a feed first: + New feed", "Önce bir akış oluşturun: + Yeni akış") else tr("Add channels to a feed first", "Önce bir akışa kanal ekleyin"))
            return
        }
        if (refreshing) return
        fetch(chans)
    }

    /** Fetch these channels' RSS (added to a running refresh if there is one). */
    private fun fetch(chans: List<Follow>) {
        if (chans.isEmpty()) return
        if (!refreshing) {
            noWifi = false
            try { Net.network(act, Store.wifiOnly) } catch (e: Exception) {
                noWifi = e is NoWifi
                updateInfo()
                act.toast(if (e is NoWifi) e.message ?: tr("Not on Wi-Fi", "Wi-Fi bağlı değil") else tr("No network", "Ağ yok"))
                return
            }
            refreshing = true; doneN = 0; totalN = 0; failedN = 0
        }
        totalN += chans.size
        updateInfo()
        val g = calls.gen
        for (f in chans) io.execute {
            val r = try { calls.check(g); Result.success(WebApi.rss(act, calls, g, f.id)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != calls.gen || !refreshing) return@post
                val now = System.currentTimeMillis()
                val c = CustomFeedStore.cache()
                r.fold({ CustomFeedStore.setCache(c.with(f.id, it, now), write = false) }, { e ->
                    if (e is NoWifi) noWifi = true
                    else if (e !is Cancelled) { failedN++; CustomFeedStore.setCache(c.tried(f.id, now), write = false) }
                })
                doneN++
                if (doneN >= totalN) { refreshing = false; persist(); render() } else scheduleRender()
            }
        }
    }

    /** Redraw at most twice a second while answers come in. */
    private fun scheduleRender() {
        if (renderPosted) { updateInfo(); return }
        renderPosted = true
        main.postDelayed({ renderPosted = false; if (shown) render() }, 500)
    }

    /** Write the cache, keeping only channels some feed still has. */
    private fun persist() {
        val keep = CustomFeeds.allChannels(CustomFeedStore.feeds()).map { it.id }
        CustomFeedStore.setCache(CustomFeedStore.cache().only(keep), write = true)
    }
}
