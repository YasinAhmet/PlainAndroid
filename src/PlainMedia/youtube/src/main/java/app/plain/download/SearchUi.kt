package app.plain.download

import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedInputStream
import java.util.concurrent.Executors
import app.plain.lang.tr

/**
 * The Search tab: Feed (followed channels' RSS) | Search (InnerTube WEB), plus a back stack of channel / playlist
 * pages. Network only on user actions (opening a page, Search, Next page, Refresh; the Feed never fetches by itself); every
 * request is cancelled when the tab or screen goes away or the page changes.
 */
/** Rows per list page (Feed, Search, channel, playlist, …): page-based lists instead of endless scrolling. */
private const val PAGE = 20

class SearchScreen(private val act: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    // also the RSS parallelism limit. After destroy() (shutdown) a late execute is dropped instead of throwing
    // RejectedExecutionException on the main thread (a reply posted after the activity went away).
    private val io = java.util.concurrent.ThreadPoolExecutor(4, 4, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.LinkedBlockingQueue(), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val calls = Calls()
    private val thumbs = ThumbLoader(act, calls)

    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val segBar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(8), act.dp(4), act.dp(16), act.dp(4)) }
    private val selBar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(4), act.dp(4), act.dp(16), act.dp(4)); visibility = View.GONE }
    private val host = FrameLayout(act)
    private val backBtn = act.icon(R.drawable.yt_ic_back, desc = tr("Back", "Geri")) { onBack() }

    /** 2.3 app-wide Back history: page pushes and Feed/Search/Lists switches are reported to MainActivity. */
    var onNav: ((NavStep) -> Unit)? = null
    /** The Hub title: the base's name (Feed, Search, Lists, Spotify) and whether a page is open (Hub's ‹). */
    var onTitle: ((String, Boolean) -> Unit)? = null
    fun reportTitle() = onTitle?.invoke(when (base) { searchPage -> tr("Search", "Ara"); listsPage -> tr("Lists", "Listeler"); spotifyPage -> "Spotify"; else -> tr("Feed", "Akış") }, stack.isNotEmpty())
    var onPagePopped: (() -> Unit)? = null
    val topSelecting get() = top.selecting
    fun endTopSelection() = top.endSelection()

    /** Back from the app history: one page back (false = no page open). */
    fun popPage(): Boolean {
        if (stack.isEmpty()) return false
        top.endSelection()
        cancelNet()
        stack.removeAt(stack.size - 1)
        attach()
        if (shown) top.onShow()
        return true
    }

    private fun baseIndex(p: ListPage) = when (p) { searchPage -> 1; listsPage -> 2; spotifyPage -> 3; else -> 0 }

    fun showBaseIndex(i: Int) = showBase(when (i) { 1 -> searchPage; 2 -> listsPage; 3 -> spotifyPage; else -> feedPage }, record = false)
    private val feedChip: Chip
    private val searchChip: Chip
    private val listsChip: Chip
    private val spotifyChip: Chip
    private val selCount = act.text("", 15f, C.TEXT, bold = true)
    private val selGo: TextView
    private lateinit var selMore: View

    private val feedPage = FeedPage()
    private val searchPage = SearchPage()
    private val listsPage = ListsPage()
    private val spotifyPage = SpotifyPage()
    private var base: ListPage = feedPage
    private val stack = ArrayList<ListPage>()
    private val top: ListPage get() = stack.lastOrNull() ?: base
    private var shown = false

    /** Spotify sync progress / results: the Spotify page on screen refreshes. */
    private val onSpotify: () -> Unit = { (top as? SpotifyRefresh)?.refresh() }

    // marks ("✓ Downloaded", "Queued") are redrawn only when downloads finish / the queue changes
    private var markSig = -1
    private var activeIds: Set<String> = emptySet()
    private val onStore: () -> Unit = {
        val s = Store.marksSig()
        if (s != markSig) { markSig = s; activeIds = Store.activeIds(); top.adapter.notifyDataSetChanged() }
    }

    init {
        segBar.addView(backBtn)
        feedChip = Chip(act, tr("Feed", "Akış")) { showBase(feedPage) }
        searchChip = Chip(act, tr("Search", "Ara")) { showBase(searchPage) }
        segBar.addView(feedChip)
        segBar.addView(View(act), lpL(act.dp(8), 1))
        segBar.addView(searchChip)
        listsChip = Chip(act, tr("Lists", "Listeler")) { showBase(listsPage) }
        segBar.addView(View(act), lpL(act.dp(8), 1))
        segBar.addView(listsChip)
        spotifyChip = Chip(act, "Spotify") { showBase(spotifyPage) }
        segBar.addView(View(act), lpL(act.dp(8), 1))
        segBar.addView(spotifyChip)

        selBar.addView(act.icon(R.drawable.yt_ic_close, desc = tr("Stop selecting", "Seçimi bitir")) { top.endSelection() })
        selBar.addView(selCount, lpL(0, WRAP, 1f))
        selBar.addView(act.pill(tr("All", "Tümü")) { top.selectAll() })
        selBar.addView(View(act), lpL(act.dp(8), 1))
        selGo = act.pill(tr("Download", "İndir"), C.ACCENT, C.ACCENT) { top.selGo() }
        selBar.addView(selGo)
        selMore = act.icon(R.drawable.yt_ic_more, desc = tr("More for the selection", "Seçim için diğer")) { selectionMenu() }
        selBar.addView(selMore)
        // thumb bar layout: the page in the middle, Feed / Search / Lists / Spotify (and the selection bar) right above the bottom bar
        view.addView(host, lpL(MATCH, 0, 1f))
        view.addView(selBar, lpL(MATCH, WRAP))
        view.addView(segBar, lpL(MATCH, WRAP))

        base = when (Store.prefs.getInt("searchSeg", if (Store.follows().isEmpty()) 1 else 0)) { 1 -> searchPage; 2 -> listsPage; 3 -> spotifyPage; else -> feedPage }
        attach()
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle / navigation

    fun onShown() {
        if (shown) return
        shown = true
        Store.addListener(onStore)
        SpotifySync.listeners += onSpotify
        markSig = Store.marksSig(); activeIds = Store.activeIds()
        top.onShow()
    }

    fun onHidden() {
        if (!shown) return
        shown = false
        Store.removeListener(onStore)
        SpotifySync.listeners -= onSpotify
        cancelNet()
        feedPage.persist()
    }

    fun destroy() {
        onHidden()
        io.shutdownNow()
        thumbs.shutdown()
    }

    /** Cancel every request of this tab (open connections are disconnected) and queued thumbnails. */
    private fun cancelNet() {
        calls.cancelAll()
        thumbs.cancel()
        (stack + feedPage + searchPage + listsPage + spotifyPage).forEach { it.onCancelled() }
    }

    /** The on-screen ← of a page. */
    fun onBack(): Boolean {
        if (top.selecting) { top.endSelection(); return true }
        if (!popPage()) return false
        onPagePopped?.invoke()
        return true
    }

    private fun showBase(p: ListPage, record: Boolean = true) {
        top.endSelection()
        if (p === base && stack.isEmpty()) return
        if (record && p !== base) onNav?.invoke(NavStep.Base(baseIndex(base)))
        cancelNet()
        stack.clear()
        base = p
        Store.prefs.edit().putInt("searchSeg", when (p) { searchPage -> 1; listsPage -> 2; spotifyPage -> 3; else -> 0 }).apply()
        attach()
        if (shown) top.onShow()
    }

    private fun push(p: ListPage) {
        top.endSelection()
        onNav?.invoke(NavStep.Page)
        cancelNet()
        stack += p
        if (stack.size > 12) stack.removeAt(0) // bounded memory on long wanders
        attach()
        if (shown) p.onShow()
    }

    private fun attach() {
        host.removeAllViews()
        host.addView(top.root, FrameLayout.LayoutParams(MATCH, MATCH))
        backBtn.visibility = View.GONE // the ‹ is Hub's now (reportTitle)
        reportTitle()
        feedChip.select(base === feedPage)
        searchChip.select(base === searchPage)
        listsChip.select(base === listsPage)
        spotifyChip.select(base === spotifyPage)
        updateSelBar()
    }

    private fun updateSelBar() {
        val p = top
        segBar.visibility = if (p.selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (p.selecting) View.VISIBLE else View.GONE
        val n = p.sel.size
        selCount.text = tr("$n selected", "$n seçili")
        selGo.text = p.selLabel(n)
        selMore.visibility = if (p.selMenu) View.VISIBLE else View.GONE
        selGo.isEnabled = n > 0
        selGo.alpha = if (n > 0) 1f else 0.4f
    }

    /**
     * Saved downloads of a page: by video id (album / playlist tracks) or by channel folder (channel pages, also
     * tracks saved under Album subfolders). Only files still in place (not trashed).
     */
    fun downloadedFor(ids: Set<String>, channel: String?): List<Job> {
        val ch = channel?.let { Naming.channel(it) }
        return Store.snapshot().filter { j ->
            j.state == State.DONE && j.uri != null && j.media == Media.OK && !j.cache &&
                (j.videoId in ids || ch != null && (j.channel == ch || j.relPath?.let { "/$ch/" in it } == true))
        }
    }

    fun eraseDownloaded(js: List<Job>, what: String) {
        if (js.isEmpty()) { act.toast(tr("Nothing downloaded from $what", "İndirilmiş bir şey yok: $what")); return }
        android.app.AlertDialog.Builder(act).setTitle(tr("Erase ${js.size} downloaded file${if (js.size == 1) "" else "s"}?", "İndirilen ${js.size} dosya silinsin mi?"))
            .setMessage(tr("From $what. They go to the trash (restorable for about 30 days; Undo right after).", "Kaynak: $what. Dosyalar çöpe gider (yaklaşık 30 gün geri yüklenebilir; hemen ardından Geri al)."))
            .setPositiveButton(tr("Erase", "Sil")) { _, _ -> act.eraseJobs(js) }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    /** Opens a channel page (hold menu "Visit channel", the player's Channel button). */
    fun openChannel(id: String, name: String) = push(ChannelPage(id, name))

    private fun selectionMenu() {
        val p = top
        val sel = p.selectedItems()
        if (sel.isEmpty()) return
        act.menu(tr("${sel.size} selected", "${sel.size} seçili"), listOf(
            tr("Download ${sel.size}…", "${sel.size} tanesini indir…") to { bulk(sel, p.albumHint, music = p.musicDefault) { p.endSelection() } },
            tr("Cache ${sel.size}", "${sel.size} tanesini önbelleğe al") to { cacheItems(sel, p.albumHint, p.musicDefault); p.endSelection() },
            tr("Add to play queue", "Çalma sırasına ekle") to { Hold.enqueue(act, sel.map { PlayQueue.of(it, null, p.albumHint) }); p.endSelection() },
            tr("Add to list…", "Listeye ekle…") to { Hold.addToList(act, sel) { p.endSelection() } },
        ))
    }

    /**
     * "Cache N": low-priority jobs in the download queue that fill the Music cache (items whose remembered choice is
     * Listen) or the Video cache (others; music videos are detected like in the player). Already cached / queued
     * videos are skipped; limits, Wi-Fi only and publishing to the public Cached folders apply as usual.
     */
    fun cacheItems(items: List<Item>, album: String?, music: Boolean = false, title: String? = null) {
        val vids = items.filter { it.isVideo && !it.live }.distinctBy { it.id }
        if (vids.isEmpty() || !act.wifiOk()) return
        val active = Store.activeIds()
        val cached = (Store.cache.list(CacheKind.MUSIC) + Store.cache.list(CacheKind.VIDEO)).filter { it.complete }.mapTo(HashSet()) { it.videoId }
        val todo = vids.filter { it.id !in active && it.id !in cached && !Store.downloadedAny(it.id) } // saved = nothing to cache (3.3)
        if (todo.isNotEmpty()) {
            val base = Store.newId()
            val batch = if (todo.size > 1) base else 0L
            Store.addAll(todo.take(Bulk.MAX).mapIndexed { k, it ->
                Job(id = base + k, videoId = it.id, title = it.title, channel = "", durationSec = it.durationSec,
                    kind = if (music || Store.mode(it.id) == PlayMode.LISTEN) Kind.AUDIO else Kind.VIDEO, audioFmt = AudioFmt.M4A, audioQ = AudioQ.HIGH,
                    height = Store.watchP, codec = VCodec.AVC, album = Albums.pick(null, false, album), cache = true,
                    batch = batch, batchTitle = tr("Cache · ", "Önbellek · ") + (title ?: album ?: tr("${todo.size} videos", "${todo.size} video")))
            })
            act.askNotificationsOnce()
            DownloadService.start(act)
        }
        act.toast(tr("Caching ${todo.size}", "Önbelleğe alınıyor: ${todo.size}") + if (vids.size > todo.size) tr(" (${vids.size - todo.size} already saved or queued)", " (${vids.size - todo.size} tanesi zaten kayıtlı ya da sırada)") else "")
    }

    /** From the Download tab / a shared link / the search box. */
    fun open(t: Target) {
        when (t) {
            is Target.Channel -> push(ChannelPage(t.id, ""))
            is Target.Playlist -> push(PlaylistPage(t.id, ""))
            is Target.ChannelUrl -> resolveAndOpen(t.url)
            is Target.Query -> { showBase(searchPage); searchPage.searchFor(t.text) }
            is Target.Video -> act.openVideo(t.id)
            Target.None -> {}
        }
    }

    private fun resolveAndOpen(url: String) {
        val g = calls.gen
        act.toast(tr("Opening…", "Açılıyor…"))
        io.execute {
            val r = try { Result.success(WebApi.resolve(act, calls, g, url)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != calls.gen || !shown) return@post
                r.fold({ if (it is Target.ChannelUrl) act.toast(tr("Not found", "Bulunamadı")) else open(it) }, { act.toast(errText(it)) })
            }
        }
    }

    private fun errText(e: Throwable): String = when (e) {
        is NoWifi -> tr("Not on Wi-Fi (Wi-Fi only is on)", "Wi-Fi bağlı değil (Yalnızca Wi-Fi açık)")
        is java.net.UnknownHostException -> tr("No internet connection", "İnternet bağlantısı yok")
        is java.net.SocketTimeoutException -> tr("Network timed out", "Ağ zaman aşımına uğradı")
        is NotFound, is YtError, is AccountExpired -> e.message ?: tr("Not found", "Bulunamadı")
        else -> tr("Couldn't load: ${e.message ?: e.javaClass.simpleName}", "Yüklenemedi: ${e.message ?: e.javaClass.simpleName}")
    }

    // ---------------------------------------------------------------------------------------------------------
    // bulk

    /** Ask once for the options, then queue [items] (videos only), skipping what is already downloaded or queued. */
    fun bulk(items: List<Item>, album: String? = null, title: String? = null, music: Boolean = false, link: String? = null, after: () -> Unit = {}) {
        val vids = items.filter { it.isVideo }
        if (vids.isEmpty()) { act.toast(tr("No videos selected", "Seçili video yok")); return }
        bulkTracks(listOf(album to vids), title ?: album ?: tr("${vids.size} selected videos", "Seçili ${vids.size} video"), music, link, after)
    }

    /**
     * Queue several lists as ONE batch (one row in the downloads list): every video once, the first list it was found
     * in gives its album folder, already downloaded / queued ones skipped, at most [Bulk.MAX] per run (run again to
     * continue). [music]: audio is the default choice (albums, Topic / artist channels). [link]: the whole playlist
     * ("yt:…" / "spotify:…") also becomes a playlist in the Music tab once the options are confirmed ([PlaylistLinks]).
     */
    fun bulkTracks(lists: List<Pair<String?, List<Item>>>, title: String, music: Boolean, link: String? = null, after: () -> Unit = {}) {
        val n = lists.flatMap { it.second }.filter { it.isVideo }.distinctBy { it.id }.size
        if (n == 0) { act.toast(tr("Nothing to download", "İndirilecek bir şey yok")); return }
        BulkDialog(act, n, music) { kind, fmt, q, height ->
            if (!act.wifiOk()) return@BulkDialog
            if (link != null) PlaylistLinks.remember(act, link, title, lists.flatMap { it.second })
            val active = Store.activeIds()
            val plan = Collect.plan(lists) { id -> id in active || Store.downloaded(id, kind) }
            if (plan.take.isNotEmpty()) {
                act.askNotificationsOnce()
                val baseId = Store.newId()
                val batch = if (plan.take.size > 1) baseId else 0L
                Store.addAll(plan.take.mapIndexed { i, t ->
                    Job(
                        id = baseId + i, videoId = t.item.id, title = t.item.title, channel = "", durationSec = t.item.durationSec,
                        kind = kind, audioFmt = fmt, audioQ = q, height = height, codec = VCodec.AVC,
                        album = Albums.pick(null, false, t.album), batch = batch, batchTitle = title,
                    )
                })
                DownloadService.start(act)
            }
            act.message(tr("Download", "İndir"), Collect.summary(plan.take.size, plan) +
                if (link != null) tr("\n\nThe songs also appear as the playlist “$title” in the Music tab as they finish.",
                    "\n\nŞarkılar bittikçe Müzik sekmesinde “$title” çalma listesinde de görünür.") else "")
            after()
        }.show()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Takeout import

    fun importDialog() {
        android.app.AlertDialog.Builder(act)
            .setTitle(tr("Import subscriptions", "Abonelikleri içe aktar"))
            .setMessage(tr(
                "Your YouTube subscriptions can be imported once from a Google Takeout file (no sign-in in this app):\n\n" +
                    "1. On takeout.google.com tap \"Deselect all\".\n" +
                    "2. Tick \"YouTube and YouTube Music\".\n" +
                    "3. \"All YouTube data included\" → keep only \"subscriptions\".\n" +
                    "4. Next step → Create export, then download it on this phone.\n\n" +
                    "Then choose the .zip (or subscriptions.csv from inside it). Channels are added to Following; nothing " +
                    "is removed.",
                "YouTube abonelikleriniz bir Google Takeout dosyasından bir kez içe aktarılabilir (bu uygulamada oturum açmadan):\n\n" +
                    "1. takeout.google.com'da \"Tümünün seçimini kaldır\"a dokunun.\n" +
                    "2. \"YouTube ve YouTube Music\"i işaretleyin.\n" +
                    "3. \"Tüm YouTube verileri dahil\" → yalnızca \"abonelikler\"i bırakın.\n" +
                    "4. Sonraki adım → Dışa aktarma oluştur, sonra bu telefona indirin.\n\n" +
                    "Ardından .zip dosyasını (ya da içindeki subscriptions.csv'yi) seçin. Kanallar Takip edilenler'e eklenir; hiçbir şey " +
                    "kaldırılmaz."
            ))
            .setPositiveButton(tr("Choose file…", "Dosya seç…")) { _, _ -> act.pickTakeout() }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    /** Follows changed elsewhere (subscriptions sync). */
    fun followsChanged() { if (shown && top === feedPage) feedPage.onShow() else feedPage.updateInfo() }

    fun importUri(uri: Uri) {
        io.execute {
            val r = try {
                val list = act.contentResolver.openInputStream(uri)!!.use { s ->
                    val b = BufferedInputStream(s, 64 shl 10)
                    b.mark(16)
                    val head = ByteArray(4)
                    val n = b.read(head)
                    b.reset()
                    if (n == 4 && Takeout.isZip(head)) Takeout.parseZip(b)
                    else Takeout.parseCsv(String(Net.readAll(b, Takeout.MAX_CSV), Charsets.UTF_8))
                }
                Result.success(list)
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (act.isDestroyed) return@post
                r.fold({ list ->
                    if (list.isEmpty()) {
                        act.message(tr("Import", "İçe aktar"), tr("No channels found in that file. Pick the Takeout .zip or its subscriptions.csv.", "Bu dosyada kanal bulunamadı. Takeout .zip dosyasını ya da içindeki subscriptions.csv'yi seçin."))
                        return@fold
                    }
                    val now = System.currentTimeMillis()
                    val (merged, added) = Follows.merge(Store.follows(), list.map { it.copy(added = now) })
                    Store.setFollows(merged)
                    act.message(tr("Import", "İçe aktar"), tr("Found ${list.size} channels, $added new. Following ${merged.size} channels now.", "${list.size} kanal bulundu, $added yeni. Artık ${merged.size} kanal takip ediliyor."))
                    if (shown && top === feedPage) feedPage.onShow() else feedPage.updateInfo()
                }, { e -> act.message(tr("Import", "İçe aktar"), tr("Couldn't read the file: ${e.message ?: e.javaClass.simpleName}", "Dosya okunamadı: ${e.message ?: e.javaClass.simpleName}")) })
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // pages

    /**
     * Members-only video tapped: remembered (its /player then goes straight to the account path). Signed out: the
     * "import your account" message instead of playing / opening it. True = blocked.
     */
    fun membersBlocked(i: Item): Boolean {
        if (!i.members) return false
        Store.markMembers(i.id)
        if (Account.usable) return false
        act.toast(if (Account.expired) tr("Members-only — account expired: import new cookies in ⋮ → Account", "Üyelere özel — hesabın süresi doldu: ⋮ → Hesap'tan yeni çerezleri içe aktarın") else Account.SIGNED_OUT)
        return true
    }

    /** A channel's members-only videos (its UUMO… playlist), asked with the account (members-tab purpose only). */
    inner class MembersPage(private val channelId: String, private val name: String) : ListPage() {
        init {
            head.addView(act.text(tr("Members-only · ", "Üyelere özel · ") + name.ifEmpty { tr("Channel", "Kanal") }, 18f, C.TEXT, bold = true, lines = 2).apply { setPadding(0, act.dp(4), 0, 0) })
            head.addView(act.text(tr("Asked with your account (only this list and members-only videos use it)", "Hesabınızla soruldu (yalnızca bu liste ve üyelere özel videolar kullanır)"), 12f, C.BLUE, lines = 2)
                .apply { setPadding(0, act.dp(2), 0, act.dp(6)) })
        }

        private fun mark(p: Page) = Page(p.items.map { if (it.isVideo) it.copy(members = true) else it }, p.continuation)

        override fun firstLoad() {
            run(true, { g -> AuthHttp.membersPage(act, calls, g, channelId, null) }) { o ->
                loadedOnce = true
                items.clear()
                append(mark(Browse.page(o)))
                setStatus(if (items.isEmpty()) Browse.alert(o) ?: tr("No members-only videos here (or this account isn't a member)", "Burada üyelere özel video yok (ya da bu hesap üye değil)") else null)
            }
        }

        override fun loadMore() {
            val t = token ?: return
            run(false, { g -> AuthHttp.membersPage(act, calls, g, channelId, t) }) { append(mark(Browse.page(it))) }
        }
    }

    abstract inner class ListPage {
        val items = ArrayList<Item>()
        var token: String? = null
        var contUrl = Clients.BROWSE_URL
        var loading = false
        var loadedOnce = false
        var region: String? = null // fallback region the first page needed (continuations use it too)
        val sel = Selection()
        val selecting get() = sel.active

        /** Rows that can be selected on this page (videos; channels on Following). */
        open fun selectable(i: Item) = i.isVideo
        open fun selLabel(n: Int) = tr("Download $n", "$n tanesini indir")
        open val selMenu get() = true
        open fun selGo() { bulk(selectedItems(), albumHint, music = musicDefault) { endSelection() } }
        /** Hold menu of a channel row offers "Select" (Following). */
        open val channelSelect get() = false
        val adapter = ItemAdapter(this)
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(12), 0, act.dp(12), act.dp(2)) }
        private val status = act.text("", 13f, C.DIM, lines = 4).apply { setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(4)); visibility = View.GONE }
        val list = ListView(act)
        private val more = act.pill(tr("Retry", "Yeniden dene")) { retry() }
        /** Rows are shown [PAGE] at a time; [pg] may point past the end and is clamped by [shownPage]. */
        var pg = 0
        val pageCount get() = Paging.count(items.size, PAGE)
        val shownPage get() = Paging.shown(pg, items.size, PAGE)
        /** ‹ Previous · Page X of Y · Next ›, once above and once below the rows. */
        private inner class Nav {
            val prev = act.smallPill("‹ " + tr("Previous", "Önceki")) { goPage(shownPage - 1) }
            val next = act.smallPill(tr("Next", "Sonraki") + " ›") { goPage(shownPage + 1) }
            val label = act.text("", 12f, C.LABEL).apply { gravity = Gravity.CENTER }
            val view = LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(prev, lpL(WRAP, WRAP))
                addView(label, lpL(0, WRAP, 1f))
                addView(next, lpL(WRAP, WRAP))
            }
        }
        private val navTop = Nav()
        private val navBottom = Nav()
        private val navTopBox = FrameLayout(act).apply {
            setPadding(act.dp(12), act.dp(2), act.dp(12), act.dp(2))
            addView(navTop.view, FrameLayout.LayoutParams(MATCH, WRAP))
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
        }
        private val nav get() = navBottom.view
        /** False until the page is first shown (subclass state isn't ready while constructing). */
        private var footReady = false
        private val footNote = act.text("", 13f, C.LABEL).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        init {
            val foot = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(24))
                addView(nav, lpL(MATCH, WRAP))
                addView(more, lpL(WRAP, WRAP))
                addView(footNote, lpL(MATCH, WRAP))
            }
            list.addHeaderView(navTopBox, null, false)
            list.addFooterView(foot, null, false)
            list.divider = null
            list.selector = ColorDrawable(0)
            list.cacheColorHint = 0
            list.isVerticalScrollBarEnabled = true
            list.adapter = adapter
            root.addView(head, lpL(MATCH, WRAP))
            root.addView(status, lpL(MATCH, WRAP))
            root.addView(list, lpL(MATCH, 0, 1f))
            more.visibility = View.GONE // refreshFooter() runs in onShow (subclass state isn't ready here)
            footNote.visibility = View.GONE
            nav.visibility = View.GONE
            navTop.view.visibility = View.GONE
        }

        open val canRetry get() = !loadedOnce

        /** Album folder hint for audio from this page (a playlist's title). */
        open val albumHint: String? get() = null

        /** Music page (album, Topic channel, Music tab): bulk downloads default to audio, caches go to Music. */
        open val musicDefault: Boolean get() = false

        open fun onShow() {
            footReady = true
            if (!loadedOnce && !loading) firstLoad()
            adapter.notifyDataSetChanged()
            refreshFooter()
        }

        abstract fun firstLoad()
        open fun retry() = firstLoad()

        fun setStatus(s: String?, error: Boolean = false) {
            status.text = s.orEmpty()
            status.setTextColor(if (error) C.RED else C.DIM)
            status.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        /** Footer + page bars; also marks the page as shown (subclasses' onShow call this). */
        fun refreshFooter() {
            footReady = true
            updateFooter()
        }

        fun updateFooter() {
            if (!footReady) return
            when {
                loading -> { more.visibility = View.GONE; footNote.text = tr("Loading…", "Yükleniyor…"); footNote.visibility = View.VISIBLE }
                !loadedOnce && canRetry -> { more.visibility = View.VISIBLE; footNote.visibility = View.GONE }
                else -> { more.visibility = View.GONE; footNote.visibility = View.GONE }
            }
            val p = shownPage
            val hasNext = Paging.hasNext(p, items.size, PAGE, token != null, loadedOnce)
            val show = if (items.isNotEmpty() && (p > 0 || hasNext)) View.VISIBLE else View.GONE
            val label = tr("Page ${p + 1} of $pageCount${if (token != null) "+" else ""}", "Sayfa ${p + 1} / $pageCount${if (token != null) "+" else ""}")
            fun en(v: View, on: Boolean) { v.isEnabled = on; v.alpha = if (on) 1f else 0.35f }
            for (n in listOf(navTop, navBottom)) {
                n.view.visibility = show
                n.label.text = label
                en(n.prev, p > 0 && !loading)
                en(n.next, hasNext && !loading)
            }
        }

        /** Show page [k] (0-based); past the loaded rows it asks YouTube for the next batch. */
        fun goPage(k: Int) {
            if (k < 0 || loading) return
            pg = k
            adapter.notifyDataSetChanged()
            list.setSelection(0)
            refreshFooter()
            if (Paging.needsMore(k, items.size, PAGE, token != null, loadedOnce)) {
                loadMore() // the page shows the new rows once they're in
            }
        }

        /** Back to page 1 (a new search, tab or filter). */
        fun firstPage() { pg = 0; list.setSelection(0) }

        /** Run [call] on the pool; [done]/[fail] on the main thread unless cancelled meanwhile. */
        fun run(firstPage: Boolean, call: (Int) -> JSONObject, fail: (() -> Unit)? = null, done: (JSONObject) -> Unit) {
            if (loading) return
            loading = true
            if (firstPage) setStatus(null)
            refreshFooter()
            val g = calls.gen
            io.execute {
                val r = try { Result.success(call(g)) } catch (e: Throwable) { Result.failure(e) }
                main.post {
                    if (g != calls.gen) return@post // cancelled; onCancelled() already reset the state
                    loading = false
                    r.fold({ done(it) }, { e -> setStatus(errText(e), true); fail?.invoke() })
                    refreshFooter()
                }
            }
        }

        fun append(p: Page) {
            val have = items.mapTo(HashSet()) { it.kind.name + it.id }
            for (i in p.items) if (have.add(i.kind.name + i.id)) items += i
            token = p.continuation
            adapter.notifyDataSetChanged()
            refreshFooter()
            afterAppend()
        }

        open fun afterAppend() {}

        open fun loadMore() {
            val t = token ?: return
            run(false, { g -> WebApi.more(act, calls, g, contUrl, t, region) }) { append(Browse.page(it)) }
        }

        open fun onCancelled() {
            if (loading) { loading = false; refreshFooter() }
        }

        // ---- selection ----

        fun startSelection(first: Item) {
            if (!selectable(first)) return
            sel.start(first.id)
            adapter.notifyDataSetChanged()
            if (top === this) updateSelBar()
        }

        fun toggle(i: Item) {
            if (!selectable(i)) return
            if (!sel.toggle(i.id)) { adapter.notifyDataSetChanged(); if (top === this) updateSelBar(); return }
            adapter.notifyDataSetChanged()
            if (top === this) updateSelBar()
        }

        fun selectAll() {
            sel.addAll(items.filter { selectable(it) && !it.live }.map { it.id })
            adapter.notifyDataSetChanged()
            if (top === this) updateSelBar()
        }

        fun endSelection() {
            if (!selecting) return
            sel.end()
            adapter.notifyDataSetChanged()
            if (top === this) updateSelBar()
        }

        fun selectedItems(): List<Item> = items.filter { selectable(it) && it.id in sel }

        // ---- taps ----

        open fun onTap(i: Item) {
            if (selecting) { toggle(i); return }
            when (i.kind) {
                IKind.VIDEO, IKind.SHORT -> if (!membersBlocked(i)) act.openVideo(i.id)
                IKind.CHANNEL -> push(ChannelPage(i.id, i.title))
                IKind.PLAYLIST -> when {
                    i.id.startsWith("local:") -> push(LocalListPage(i.id.removePrefix("local:")))
                    i.id.startsWith("spotify:") -> push(SpotifyListPage(i.id.removePrefix("spotify:")))
                    else -> push(PlaylistPage(i.id, i.title))
                }
            }
        }

        /** Extra hold-menu entries for a video row on this page. */
        open fun videoExtras(i: Item): List<Pair<String, () -> Unit>> = emptyList()

        open fun onLongTap(i: Item) {
            when (i.kind) {
                IKind.VIDEO, IKind.SHORT -> if (selecting) toggle(i) else Hold.video(act, i, albumHint, { startSelection(i) }, videoExtras(i))
                IKind.CHANNEL -> {
                    val followed = Store.isFollowed(i.id)
                    act.menu(i.title, listOfNotNull(
                        if (channelSelect) tr("Select", "Seç") to { startSelection(i) } else null,
                        tr("Open channel", "Kanalı aç") to { push(ChannelPage(i.id, i.title)) },
                        tr("Download all music…", "Tüm müzikleri indir…") to { push(ChannelPage(i.id, i.title).apply { autoAll = "music" }) },
                        (if (followed) tr("Unfollow", "Takibi bırak") else tr("Follow", "Takip et")) to {
                            if (followed) UnfollowUi.unfollow(act, listOf(i.id to i.title)) { onFollowsChanged() }
                            else { Store.follow(i.id, i.title); act.toast(tr("Following ${i.title}", "Takip ediliyor: ${i.title}")); onFollowsChanged() }
                        },
                    ))
                }
                IKind.PLAYLIST -> act.menu(i.title, listOf(
                    tr("Open", "Aç") to { push(PlaylistPage(i.id, i.title)) },
                    tr("Download all…", "Tümünü indir…") to { push(PlaylistPage(i.id, i.title).apply { auto = "download" }) },
                    tr("Cache all", "Tümünü önbelleğe al") to { push(PlaylistPage(i.id, i.title).apply { auto = "cache" }) },
                    tr("Listen all", "Tümünü dinle") to { push(PlaylistPage(i.id, i.title).apply { auto = "listen" }) },
                    tr("Shuffle all", "Tümünü karışık çal") to { push(PlaylistPage(i.id, i.title).apply { auto = "shuffle" }) },
                    tr("Add to list…", "Listeye ekle…") to { push(PlaylistPage(i.id, i.title).apply { auto = "list" }) },
                ))
            }
        }

        open fun onChannelTap(i: Item) {
            if (selecting) { toggle(i); return }
            val id = i.channelId ?: return
            push(ChannelPage(id, i.channel))
        }

        open fun onFollowsChanged() { adapter.notifyDataSetChanged() }
    }

    // ---- Feed ----

    inner class FeedPage : ListPage() {
        private val info = act.text("", 11f, C.LABEL, lines = 2)
        private val followingBtn = act.smallPill(tr("Following", "Takip edilenler")) { push(FollowingPage()) }
        private var refreshing = false
        private var doneN = 0
        private var totalN = 0
        private var failedN = 0
        private var noWifi = false
        private var dirty = false
        private var renderPosted = false
        private var ff = Store.feedFilters
        /** Filter chips on one line that scrolls sideways (compact head). */
        private val filterFlow = object : LinearLayout(act) {
            override fun addView(child: View) = super.addView(child, lpL(WRAP, WRAP).apply { marginEnd = act.dp(6) })
        }.apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        private val note = act.text("", 11f, C.LABEL, lines = 3)

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(2), 0, act.dp(2)) }
            row.addView(followingBtn)
            row.addView(View(act), lpL(act.dp(6), 1))
            row.addView(act.smallPill(tr("Import…", "İçe aktar…")) { importDialog() })
            row.addView(View(act), lpL(0, 1, 1f))
            row.addView(act.smallPill(tr("Refresh", "Yenile"), C.ACCENT, C.ACCENT) { refresh() })
            head.addView(row, lpL(MATCH, WRAP))
            head.addView(info, lpL(MATCH, WRAP))
            head.addView(android.widget.HorizontalScrollView(act).apply {
                isHorizontalScrollBarEnabled = false
                setPadding(0, act.dp(3), 0, act.dp(2))
                addView(filterFlow)
            }, lpL(MATCH, WRAP))
            head.addView(note, lpL(MATCH, WRAP))
            renderFilters()
        }

        private fun renderFilters() {
            filterFlow.removeAllViews()
            fun toggle(label: String, on: Boolean, set: (Boolean) -> FeedFilters) = filterFlow.addView(filterChip(label, on) { setFF(set(!on)) })
            toggle(tr("Members only", "Yalnızca üyeler"), ff.membersOnly) { ff.copy(membersOnly = it) }
            toggle(tr("Hide Shorts", "Shorts'u gizle"), ff.hideShorts) { ff.copy(hideShorts = it) }
            toggle(tr("Hide downloaded", "İndirilenleri gizle"), ff.hideDownloaded) { ff.copy(hideDownloaded = it) }
            toggle(tr("Music only", "Yalnızca müzik"), ff.musicOnly) { ff.copy(musicOnly = it) }
            val follows = Store.follows()
            val picked = ff.channels.count { id -> follows.any { it.id == id } }
            filterFlow.addView(filterChip(if (picked == 0) tr("All channels", "Tüm kanallar") else tr("$picked channel${if (picked == 1) "" else "s"}", "$picked kanal"), picked > 0) {
                if (follows.isEmpty()) { act.toast(tr("Not following any channel", "Hiçbir kanal takip edilmiyor")); return@filterChip }
                checks(tr("Channels (none = all)", "Kanallar (hiçbiri = tümü)"), follows.map { it.title }, follows.map { it.id in ff.channels }.toBooleanArray()) { b ->
                    setFF(ff.copy(channels = follows.filterIndexed { i, _ -> b[i] }.map { it.id }.toSet()))
                }
            })
            filterFlow.addView(filterChip(ff.age.label, ff.age != FAge.ALL) {
                act.menu(tr("Age", "Tarih"), FAge.values().map { a -> (if (a == ff.age) "✓ " else "   ") + a.label to { setFF(ff.copy(age = a)) } })
            })
            filterFlow.addView(filterChip(ff.sort.label, ff.sort != FSort.NEWEST) {
                act.menu(tr("Sort", "Sırala"), FSort.values().map { o -> (if (o == ff.sort) "✓ " else "   ") + o.label to { setFF(ff.copy(sort = o)) } })
            })
            if (!ff.isDefault) filterFlow.addView(act.smallPill(tr("Reset", "Sıfırla")) { setFF(FeedFilters()) })
            note.text = if (ff.membersOnly) tr("Members-only uploads aren't in channel RSS: they're found on Refresh, from each channel's public " +
                "members list (no account needed), so this may lag until you tap Refresh.",
                "Üyelere özel yüklemeler kanal RSS'inde yok: Yenile'de her kanalın herkese açık üye listesinden bulunur " +
                "(hesap gerekmez), bu yüzden Yenile'ye dokunana kadar gecikebilir.") else ""
            note.visibility = if (ff.membersOnly) View.VISIBLE else View.GONE
        }

        private fun setFF(n: FeedFilters) { firstPage(); ff = n; Store.feedFilters = n; renderFilters(); showCache() }

        override val canRetry get() = false

        override fun firstLoad() { loadedOnce = true; onShow() }

        /** Show the saved feed. Nothing is fetched until Refresh is tapped (the user's rule: no network unasked). */
        override fun onShow() {
            loadedOnce = true
            showCache()
            refreshFooter()
        }

        private fun showCache() {
            items.clear()
            val all = Store.loadFeed().items
            items += ff.apply(all, System.currentTimeMillis()) { Store.downloadedAny(it) }
            adapter.notifyDataSetChanged()
            setStatus(if (all.isNotEmpty() && items.isEmpty()) tr("Nothing in the feed passes the filters", "Akışta filtrelere uyan bir şey yok") else null)
            updateInfo()
        }

        fun updateInfo() {
            val n = Store.follows().size
            followingBtn.text = tr("Following ($n)", "Takip edilenler ($n)")
            info.text = when {
                n == 0 -> tr("Follow channels (open one from Search → Channels and tap Follow), or import your YouTube " +
                    "subscriptions with Import…. Their newest videos show up here.",
                    "Kanalları takip edin (Ara → Kanallar'dan birini açıp Takip et'e dokunun) ya da YouTube aboneliklerinizi " +
                    "İçe aktar… ile ekleyin. En yeni videoları burada görünür.")
                refreshing -> tr("Refreshing… $doneN of $totalN channels", "Yenileniyor… $doneN / $totalN kanal")
                else -> {
                    val last = Store.loadFeed().lastRefresh
                    (if (last > 0) tr("Updated ${Ago.text(last, System.currentTimeMillis())}", "Güncellendi: ${Ago.text(last, System.currentTimeMillis())}") else tr("Not loaded yet · tap Refresh", "Henüz yüklenmedi · Yenile'ye dokunun")) +
                        Store.loadFeed().fetched.let { fe -> Store.follows().count { (fe[it.id] ?: 0L) == 0L } }.let { k ->
                            if (k == 0 || last == 0L) "" else tr(" · $k new channel${if (k == 1) "" else "s"}: tap Refresh", " · $k yeni kanal: Yenile'ye dokunun")
                        } +
                        (if (noWifi) tr(" · not on Wi-Fi, showing the saved feed", " · Wi-Fi bağlı değil, kayıtlı akış gösteriliyor") else "") +
                        (if (failedN > 0) tr(" · $failedN channels failed", " · $failedN kanal başarısız") else "") +
                        Store.loadFeed().emptyChannels(Store.follows()).let { e ->
                            if (e.isEmpty()) "" else { val names = e.take(3).joinToString(", ") { it.title.ifEmpty { it.id } } + if (e.size > 3) "…" else ""
                                tr(" · no videos from: $names (wrong channel followed? unfollow it and follow the one you reach from its videos)",
                                    " · video gelmeyen: $names (yanlış kanal mı takip edildi? takibi bırakıp videolarından ulaştığınız kanalı takip edin)") }
                        }
                }
            }
        }

        /** Refresh button only: fetches every followed channel. */
        fun refresh() {
            if (refreshing) return
            val due = Store.follows()
            if (due.isEmpty()) {
                // nothing followed: guide to the sign-in / subscriptions sync (or Takeout, or a channel search)
                updateInfo()
                act.noSubscriptions(onImport = { importDialog() }, onSearch = { showBase(searchPage); searchPage.channelsMode() })
                return
            }
            noWifi = false
            try { Net.network(act, Store.wifiOnly) } catch (e: Exception) {
                // checked once here instead of failing every channel; the saved feed stays on screen
                noWifi = e is NoWifi
                failedN = 0
                updateInfo()
                if (e !is NoWifi) act.toast(tr("No network", "Ağ yok"))
                return
            }
            refreshing = true; doneN = 0; failedN = 0; totalN = due.size
            updateInfo()
            val g = calls.gen
            for (f in due) io.execute {
                val r = try {
                    calls.check(g)
                    val fresh = WebApi.rss(act, calls, g, f.id)
                    // manual Refresh: also the channel's members-only uploads (public UUMO feed; 404 = no memberships, skipped a week)
                    var members: List<Item>? = null
                    val now0 = System.currentTimeMillis()
                    if (Store.noMembersUntil(f.id) < now0) members = try {
                        WebApi.rssMembers(act, calls, g, f.id) ?: emptyList<Item>().also { Store.setNoMembers(f.id, now0 + 7 * 86_400_000L) }
                    } catch (e: Cancelled) { throw e } catch (e: NoWifi) { throw e } catch (_: Exception) { null }
                    Result.success(fresh to members)
                } catch (e: Throwable) { Result.failure(e) }
                main.post {
                    if (g != calls.gen) return@post
                    val now = System.currentTimeMillis()
                    r.fold({ Store.saveFeed(Store.loadFeed().with(f.id, it.first, now, it.second), write = false); dirty = true }, { e ->
                        if (e is NoWifi) noWifi = true
                        else { failedN++; Store.saveFeed(Store.loadFeed().tried(f.id, now), write = false); dirty = true }
                    })
                    doneN++
                    if (doneN >= totalN) {
                        refreshing = false; persist(); render()
                    } else scheduleRender()
                }
            }
        }

        /** Redraw at most twice a second while RSS answers come in. */
        private fun scheduleRender() {
            if (renderPosted) { updateInfo(); return }
            renderPosted = true
            main.postDelayed({ renderPosted = false; if (top === this) render() }, 500)
        }

        private fun render() { if (top === this) showCache() else updateInfo() }

        fun persist() { if (dirty) { Store.saveFeed(Store.loadFeed(), write = true); dirty = false } }

        override fun onCancelled() {
            super.onCancelled()
            if (refreshing) { refreshing = false; persist(); updateInfo() }
        }

        override fun onFollowsChanged() { renderFilters(); showCache() }
    }

    inner class FollowingPage : ListPage() {
        /** Filter for long lists (Takeout import, subscriptions sync): name, accents and case ignored. */
        private val filter: EditText by lazy {
            EditText(act).apply {
                hint = tr("Filter channels", "Kanalları filtrele")
                setHintTextColor(C.LABEL); setTextColor(C.TEXT); textSize = 14f; isSingleLine = true
                background = act.outline(act.dpf(18))
                setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
            }
        }

        init {
            head.addView(act.text(tr("Following", "Takip edilenler"), 18f, C.TEXT, bold = true).apply { setPadding(0, act.dp(4), 0, act.dp(2)) })
            head.addView(act.text(tr("Tap to open; long-press for Unfollow / Select.", "Açmak için dokunun; Takibi bırak / Seç için basılı tutun."), 12f, C.LABEL))
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(6), 0, act.dp(4)) }
            row.addView(filter, lpL(0, WRAP, 1f))
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill(tr("Select", "Seç")) { items.firstOrNull()?.let { startSelection(it) } ?: act.toast(tr("Nothing to select", "Seçilecek bir şey yok")) })
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill(tr("Import…", "İçe aktar…")) { importDialog() })
            head.addView(row, lpL(MATCH, WRAP))
            filter.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { fill() }
            })
        }

        override fun selectable(i: Item) = i.kind == IKind.CHANNEL
        override fun selLabel(n: Int) = tr("Unfollow $n", "Takibi bırak ($n)")
        override val selMenu get() = false
        override val channelSelect get() = true
        override fun selGo() {
            val chosen = selectedItems().map { it.id to it.title }
            if (chosen.isEmpty()) return
            UnfollowUi.unfollow(act, chosen) { endSelection(); onFollowsChanged() }
        }

        override val canRetry get() = false
        override fun firstLoad() { loadedOnce = true; fill() }
        override fun onShow() { loadedOnce = true; fill(); refreshFooter() }

        private fun fill() {
            items.clear()
            val all = Store.follows()
            val q = filter.text?.toString().orEmpty()
            UnfollowText.filter(all, q).forEach { items += Item(IKind.CHANNEL, it.id, it.title) }
            if (selecting) { sel.retain(items.map { it.id }.toSet()); if (top === this) updateSelBar() }
            adapter.notifyDataSetChanged()
            setStatus(when {
                all.isEmpty() -> tr("Not following any channel yet.", "Henüz hiçbir kanal takip edilmiyor.")
                items.isEmpty() -> tr("No followed channel matches \"$q\".", "Takip edilen hiçbir kanal \"$q\" ile eşleşmiyor.")
                q.isNotBlank() -> tr("${items.size} of ${all.size}", "${items.size} / ${all.size}")
                else -> null
            })
        }

        override fun onFollowsChanged() { fill(); feedPage.updateInfo() }
    }

    // ---- Search ----

    /** Small chip for a filter: amber outline + text when it isn't at its default. */
    private fun filterChip(label: String, active: Boolean, onClick: () -> Unit) = Chip(act, label) { onClick() }.compact().apply { select(active) }

    /** Multi-choice picker; [done] gets the checked flags. */
    private fun checks(title: String, labels: List<String>, on: BooleanArray, done: (BooleanArray) -> Unit) {
        val st = on.copyOf()
        android.app.AlertDialog.Builder(act).setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), st) { _, i, c -> st[i] = c }
            .setPositiveButton(tr("Apply", "Uygula")) { _, _ -> done(st) }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    inner class SearchPage : ListPage() {
        private val box = EditText(act)
        private val types = listOf(tr("Videos", "Videolar"), tr("Channels", "Kanallar"), tr("Playlists", "Çalma listeleri"))
        private val chips = ArrayList<Chip>()
        private var f = Store.searchFilters
        private var query = ""
        /** Everything loaded for this query; [items] is the filtered (and maybe re-sorted) view of it. */
        private val raw = ArrayList<Item>()
        private val filterFlow = FlowLayout(act).apply { setPadding(0, act.dp(2), 0, act.dp(2)) }
        private val note = act.text("", 12f, C.LABEL, lines = 3)
        private var pagesThisRound = 0
        private var passedThisRound = 0

        init {
            contUrl = Clients.SEARCH_URL
            box.apply {
                hint = tr("Search YouTube, or paste a channel / playlist link", "YouTube'da arayın ya da kanal / çalma listesi bağlantısı yapıştırın")
                setSingleLine()
                inputType = InputType.TYPE_CLASS_TEXT
                imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextColor(C.TEXT); setHintTextColor(C.LABEL); textSize = 15f
                background = act.outline(act.dpf(12))
                setPadding(act.dp(14), act.dp(11), act.dp(14), act.dp(11))
                setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEARCH) { submit(); true } else false }
            }
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, 0) }
            row.addView(box, lpL(0, WRAP, 1f))
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill(tr("Search", "Ara"), C.ACCENT, C.ACCENT) { submit() })
            head.addView(row, lpL(MATCH, WRAP))
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(10), 0, act.dp(4)) }
            types.forEachIndexed { i, label ->
                val c = Chip(act, label) { setType(i) }
                chips += c; flow.addView(c)
            }
            chips.forEachIndexed { i, c -> c.select(i == f.type) }
            head.addView(flow, lpL(MATCH, WRAP))
            head.addView(filterFlow, lpL(MATCH, WRAP))
            head.addView(note, lpL(MATCH, WRAP))
            renderFilters()
        }

        private fun renderFilters() {
            filterFlow.removeAllViews()
            if (f.videos) {
                filterFlow.addView(filterChip(f.date.label, f.date != SDate.ANY) {
                    act.menu(tr("Upload date", "Yükleme tarihi"), SDate.values().map { d -> (if (d == f.date) "✓ " else "   ") + d.label to { setF(f.copy(date = d)) } })
                })
                filterFlow.addView(filterChip(f.dur.label, f.dur != SDur.ANY) {
                    act.menu(tr("Duration", "Süre"), SDur.values().map { d -> (if (d == f.dur) "✓ " else "   ") + d.label to { setF(f.copy(dur = d)) } })
                })
            }
            val sorts = if (f.videos) SSort.values().toList() else listOf(SSort.RELEVANCE, SSort.POPULAR)
            filterFlow.addView(filterChip(tr("Sort: ", "Sırala: ") + f.effSort.label, f.effSort != SSort.RELEVANCE) {
                act.menu(tr("Sort by", "Sıralama"), sorts.map { s -> (if (s == f.effSort) "✓ " else "   ") + s.label to { setF(f.copy(sort = s)) } })
            })
            val feats = listOf(f.hideShorts, f.hideLive, f.membersOnly, f.musicOnly, f.hideDownloaded)
            val n = feats.count { it }
            filterFlow.addView(filterChip(if (n == 0) tr("Features", "Özellikler") else tr("Features ($n)", "Özellikler ($n)"), n > 0) {
                checks(tr("Features", "Özellikler"), listOf(tr("Hide Shorts", "Shorts'u gizle"), tr("Hide live", "Canlıları gizle"), tr("Members-only only", "Yalnızca üyelere özel"), tr("Music only (\"- Topic\" channels)", "Yalnızca müzik (\"- Topic\" kanalları)"), tr("Hide downloaded", "İndirilenleri gizle")), feats.toBooleanArray()) { b ->
                    setF(f.copy(hideShorts = b[0], hideLive = b[1], membersOnly = b[2], musicOnly = b[3], hideDownloaded = b[4]))
                }
            })
            if (!f.isDefault) filterFlow.addView(act.pill(tr("Reset", "Sıfırla")) { setF(SearchFilters(type = f.type)) })
            val lines = ArrayList<String>()
            if (f.membersOnly) lines += tr("Search is always anonymous, and YouTube leaves members-only videos out of it, so this is usually empty: open a channel's Members list instead.", "Arama her zaman anonimdir ve YouTube üyelere özel videoları aramaya katmaz, bu yüzden bu genelde boştur: bunun yerine bir kanalın Üyeler listesini açın.")
            if (f.effSort == SSort.NEWEST) lines += tr("YouTube no longer sorts search by date: the loaded results are sorted here.", "YouTube artık aramayı tarihe göre sıralamıyor: yüklenen sonuçlar burada sıralanır.")
            note.text = lines.joinToString("\n")
            note.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun setF(n: SearchFilters) {
            val server = n.sp() != f.sp()
            f = n
            Store.searchFilters = n
            chips.forEachIndexed { k, c -> c.select(k == n.type) }
            renderFilters()
            if (query.isEmpty()) return
            firstPage()
            if (server) { cancelNet(); doSearch() } else { reapply(); scanIfNeeded(newRound = true) }
        }

        override val canRetry get() = query.isNotEmpty() && !loadedOnce
        override fun firstLoad() { if (query.isNotEmpty()) doSearch() }

        private fun setType(i: Int) { if (i != f.type) setF(f.copy(type = i)) }

        fun searchFor(q: String) { box.setText(q); query = q; cancelNet(); doSearch() }

        /** "Search channels to follow" (Feed with nothing followed): the Channels type, keyboard up in the box. */
        fun channelsMode() {
            setType(1)
            box.requestFocus()
            main.postDelayed({ if (box.isAttachedToWindow) act.getSystemService(InputMethodManager::class.java).showSoftInput(box, 0) }, 150)
        }

        private fun submit() {
            act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
            if (box.text.isBlank()) return
            when (val t = Links.classify(box.text.toString(), bareId = false)) {
                is Target.Query -> { query = t.text; cancelNet(); doSearch() }
                Target.None -> setStatus(tr("That link isn't a YouTube video, channel or playlist", "Bu bağlantı bir YouTube videosu, kanalı ya da çalma listesi değil"), true)
                else -> open(t)
            }
        }

        private fun reapply() {
            items.clear()
            items += ItemFilter.search(raw, f) { Store.downloadedAny(it) }
            adapter.notifyDataSetChanged()
            refreshFooter()
            setStatus(when {
                !loadedOnce -> null
                raw.isEmpty() -> tr("No results", "Sonuç yok")
                items.isEmpty() -> tr("None of the ${raw.size} loaded results pass the filters", "Yüklenen ${raw.size} sonucun hiçbiri filtrelere uymuyor") + (if (token != null) tr(" · Next scans further", " · Sonraki ile daha ileri taranır") else "")
                else -> null
            })
        }

        private fun addPage(p: Page) {
            val have = raw.mapTo(HashSet()) { it.kind.name + it.id }
            val before = items.size
            for (i in p.items) if (have.add(i.kind.name + i.id)) raw += i
            token = p.continuation
            reapply()
            passedThisRound += maxOf(0, items.size - before)
            scanIfNeeded(newRound = false)
        }

        /** With client filters, keep loading pages until enough rows pass or the page budget is spent. */
        private fun scanIfNeeded(newRound: Boolean) {
            if (newRound) { pagesThisRound = 0; passedThisRound = items.size }
            if (!loading && Scan.more(passedThisRound, pagesThisRound, token != null, f.client)) fetchMore()
        }

        override fun loadMore() { pagesThisRound = 0; passedThisRound = 0; fetchMore() }

        private fun fetchMore() {
            val t = token ?: return
            pagesThisRound++
            run(false, { g -> WebApi.more(act, calls, g, contUrl, t, region) }) { addPage(Browse.page(it)) }
        }

        private fun doSearch() {
            endSelection()
            items.clear(); raw.clear(); token = null; loadedOnce = false
            firstPage()
            adapter.notifyDataSetChanged()
            val q = query
            val params = f.sp()
            pagesThisRound = 1; passedThisRound = 0
            val channels = f.type == 1
            var extra = emptyList<Item>()
            run(true, { g ->
                WebApi.search(act, calls, g, q, params).also { o ->
                    // blocked channels are left out of channel search: add them from the owners of video results
                    if (channels) extra = try {
                        val have = Browse.page(o).items.filter { it.kind == IKind.CHANNEL }.map { it.id }
                        Browse.missingChannels(q, Browse.page(WebApi.search(act, calls, g, q, Clients.SEARCH_VIDEOS)).items, have)
                    } catch (e: Cancelled) { throw e } catch (_: Exception) { emptyList() }
                }
            }) { o ->
                loadedOnce = true
                region = o.optString("_gl").ifEmpty { null }
                val p = Browse.page(o)
                addPage(if (extra.isEmpty()) p else Page(extra + p.items, p.continuation))
            }
        }
    }

    // ---- Channel ----

    inner class ChannelPage(private val channelId: String, private var name: String) : ListPage() {
        private val titleV = act.text(name.ifEmpty { tr("Channel", "Kanal") }, 18f, C.TEXT, bold = true, lines = 2)
        private val subV = act.text("", 12f, C.LABEL)
        private val followBtn = act.pill(tr("Follow", "Takip et"), C.ACCENT, C.ACCENT) { toggleFollow() }
        private val tabChips = LinkedHashMap<String, Chip>()
        /** Topic channels ("Artist - Topic") have no Videos tab on YouTube: their Music tab comes first. */
        private val topic get() = name.trim().endsWith(" - Topic")
        private var tab = if (name.trim().endsWith(" - Topic")) "music" else "videos"
        private var tabs: Map<String, String> = emptyMap()
        private val saved = HashMap<String, Triple<List<Item>, String?, Boolean>>()
        /** YouTube Music artist page for this channel id (null = not loaded yet; hasMusic false = none). */
        @Volatile private var artist: MusicParse.Artist? = null
        private var artistTried = false
        /** Paging over albums and singles for the Music tab ("Load more"). */
        private var cursors: List<MusicParse.Cursor>? = null
        /** Hold menu "Download all music…" on a channel row. */
        var autoAll: String? = null
        private val collectV = act.text("", 12f, C.LABEL, lines = 2)
        private val stopBtn = act.pill(tr("Stop", "Durdur")) { collecting = false; cancelNet(); showCollect(null) }
        @Volatile private var collecting = false

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, 0) }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(titleV); col.addView(subV)
            row.addView(col, lpL(0, WRAP, 1f))
            row.addView(followBtn)
            head.addView(row, lpL(MATCH, WRAP))
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(10), 0, act.dp(4)) }
            for ((k, label) in listOf("music" to tr("Music", "Müzik"), "videos" to tr("Videos", "Videolar"), "shorts" to "Shorts", "playlists" to tr("Playlists", "Çalma listeleri"))) {
                val c = Chip(act, label) { switchTab(k) }
                tabChips[k] = c; flow.addView(c)
            }
            flow.addView(act.pill(tr("Download all…", "Tümünü indir…"), C.ACCENT, C.ACCENT) { downloadAllMenu() })
            // members-only videos of this channel: only offered with an account (the list is asked with it)
            if (Account.usable) flow.addView(act.pill(tr("Members", "Üyeler"), C.BLUE, C.BLUE) { push(MembersPage(channelId, name)) })
            head.addView(flow, lpL(MATCH, WRAP))
            val crow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            crow.addView(collectV, lpL(0, WRAP, 1f)); crow.addView(stopBtn)
            head.addView(crow, lpL(MATCH, WRAP))
            showCollect(null)
            updateHead()
        }

        private fun updateHead() {
            titleV.text = name.ifEmpty { tr("Channel", "Kanal") }
            val f = Store.isFollowed(channelId)
            followBtn.text = if (f) tr("Following", "Takip ediliyor") else tr("Follow", "Takip et")
            followBtn.setTextColor(if (f) C.DIM else C.ACCENT)
            followBtn.background = ripple(act.outline(act.dpf(20), if (f) C.OUTLINE else C.ACCENT))
            tabChips.forEach { (k, c) ->
                c.select(k == tab)
                c.visibility = when (k) {
                    "music" -> if (topic || artist?.hasMusic == true) View.VISIBLE else View.GONE
                    "videos" -> if (topic && tabs.isNotEmpty() && "videos" !in tabs && tab != "videos") View.GONE else View.VISIBLE
                    else -> if (tabs.isEmpty() || k in tabs) View.VISIBLE else View.GONE
                }
            }
        }

        private fun toggleFollow() {
            if (Store.isFollowed(channelId)) { UnfollowUi.unfollow(act, listOf(channelId to name)) { updateHead(); feedPage.updateInfo() }; return }
            else { Store.follow(channelId, name); act.toast(tr("Following ${name.ifEmpty { "channel" }}: its videos show up in Feed after Refresh", "Takip ediliyor: ${name.ifEmpty { "kanal" }} · videoları Yenile'den sonra Akış'ta görünür")) }
            updateHead()
            feedPage.updateInfo()
        }

        private fun params(k: String) = tabs[k] ?: when (k) {
            "videos" -> Clients.TAB_VIDEOS
            "shorts" -> Clients.TAB_SHORTS
            else -> Clients.TAB_PLAYLISTS
        }

        override fun firstLoad() {
            val k = tab
            if (k == "music") { loadMusic(); return }
            run(true, { g -> WebApi.browse(act, calls, g, channelId, params(k)) }) { o ->
                Browse.alert(o)?.let { setStatus(it, true); return@run }
                Browse.channelHead(o)?.let { h ->
                    if (h.title.isNotEmpty()) name = h.title
                    tabs = h.tabs
                    subV.text = listOf(h.handle, h.subs).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                loadedOnce = true
                region = o.optString("_gl").ifEmpty { null }
                items.clear()
                append(Browse.page(o))
                val empty = if (items.isEmpty()) Browse.emptyChannel(o) else null
                setStatus(if (items.isEmpty()) empty?.let { "YouTube: $it" } ?: tr("Nothing here", "Burada bir şey yok") else null)
                updateHead()
                if (empty != null && k == "videos" && name.isNotEmpty() && !topic) findSimilar(empty)
                else probeArtist()
            }
        }

        /** In the background: does YouTube Music list releases for this channel? Shows the Music tab if so. */
        private fun probeArtist() {
            if (artistTried) return
            artistTried = true
            val g = calls.gen
            io.execute {
                val a = try { MusicParse.artist(WebApi.remixBrowse(act, calls, g, channelId, null)) } catch (_: Exception) { null }
                main.post {
                    if (g != calls.gen) { artistTried = false; return@post }
                    artist = a
                    if (a?.hasMusic == true && items.isEmpty() && tab != "music") switchTab("music") else updateHead()
                    autoAll?.let { autoAll = null; collectAll(it) }
                }
            }
        }

        /**
         * The Music tab: "All songs" (YouTube Music's songs list for the artist), the music-videos list, then every
         * album, single and EP (the artist page's carousels, completed from their "More" discography pages).
         */
        private fun loadMusic() {
            var first: List<MusicParse.Release> = emptyList()
            run(true, { g ->
                val a = artist ?: MusicParse.artist(WebApi.remixBrowse(act, calls, g, channelId, null))
                artist = a; artistTried = true
                if (a != null) {
                    val cs = listOf(MusicParse.Cursor("Album", a.albumsMore, a.albums), MusicParse.Cursor("Single", a.singlesMore, a.singles))
                    cursors = cs
                    first = cs.flatMap { nextPage(g, it) } // first page of albums, then of singles
                }
                org.json.JSONObject()
            }) {
                loadedOnce = true
                items.clear()
                val a = artist
                if (a != null) {
                    a.songsPlaylist?.let { items += Item(IKind.PLAYLIST, it, tr("All songs", "Tüm şarkılar"), channel = who(), count = tr("Songs", "Şarkılar")) }
                    a.videosPlaylist?.let { items += Item(IKind.PLAYLIST, it, tr("Music videos", "Müzik videoları"), channel = who(), count = tr("Videos", "Videolar")) }
                    addReleases(first)
                }
                token = if (cursors?.any { !it.done } == true) "music" else null
                adapter.notifyDataSetChanged()
                refreshFooter()
                setStatus(if (items.isEmpty()) tr("YouTube Music lists no releases for this channel", "YouTube Music bu kanal için yayın listelemiyor") else null)
                updateHead()
                autoAll?.let { autoAll = null; collectAll(it) }
            }
        }

        private fun who() = artist?.name?.ifEmpty { null } ?: name.removeSuffix(" - Topic")

        private fun addReleases(rel: List<MusicParse.Release>) {
            val have = items.mapTo(HashSet()) { it.id }
            for (r in rel) if (have.add(r.id)) items += r.item(who())
        }

        /**
         * Next page of [c] on a background thread: the "More" discography page first, then continuation pages
         * (100 releases each). When the "More" page fails or is empty, the artist page's carousel is used.
         */
        private fun nextPage(g: Int, c: MusicParse.Cursor): List<MusicParse.Release> {
            if (!c.started && c.more == null) { c.fail(); return c.initial }
            if (c.done) return emptyList()
            val json = if (!c.started) WebApi.remixBrowse(act, calls, g, c.more!!.browseId, c.more.params) else WebApi.remixContinue(act, calls, g, c.token!!)
            val p = MusicParse.discographyPage(json, c.kind)
            if (!c.started && p.releases.isEmpty()) { c.fail(); return c.initial }
            if (p.releases.isEmpty()) { c.fail(); return emptyList() }
            c.took(p)
            return p.releases
        }

        /** Music tab: "Load more" pages through the albums, then the singles. */
        override fun loadMore() {
            if (tab != "music") { super.loadMore(); return }
            val c = cursors?.firstOrNull { !it.done } ?: return
            var got: List<MusicParse.Release> = emptyList()
            run(false, { g -> got = nextPage(g, c); org.json.JSONObject() }) {
                addReleases(got)
                token = if (cursors?.any { !it.done } == true) "music" else null
                adapter.notifyDataSetChanged()
                refreshFooter()
            }
        }

        private fun showCollect(s: String?) {
            collectV.text = s.orEmpty()
            collectV.visibility = if (s == null) View.GONE else View.VISIBLE
            stopBtn.visibility = if (collecting) View.VISIBLE else View.GONE
        }

        private fun downloadAllMenu() {
            val music = topic || artist?.hasMusic == true
            val opts = ArrayList<Pair<String, () -> Unit>>()
            if (music) opts += tr("All music (every album & single)", "Tüm müzikler (her albüm ve single)") to { collectAll("music") }
            if (!topic) opts += tr("All videos", "Tüm videolar") to { collectAll("videos") }
            if (music && !topic) opts += tr("Everything (music + videos)", "Her şey (müzik + videolar)") to { collectAll("all") }
            val dl = downloadedFor(items.filter { it.isVideo }.mapTo(HashSet()) { it.id }, name)
            if (dl.isNotEmpty()) opts += tr("Erase downloaded (${dl.size})…", "İndirilenleri sil (${dl.size})…") to { eraseDownloaded(dl, name.ifEmpty { tr("this channel", "bu kanal") }) }
            if (opts.size == 1) { opts[0].second(); return }
            act.menu(tr("Download all from ", "Tümünü indir: ") + name.ifEmpty { tr("this channel", "bu kanal") }, opts)
        }

        /** Collect every list (progress + Stop), then one batch: each video once, album folder from its release. */
        private fun collectAll(what: String) {
            if (collecting) return
            collecting = true
            showCollect(tr("Collecting…", "Toplanıyor…"))
            val g = calls.gen
            io.execute {
                val lists = ArrayList<Pair<String?, List<Item>>>()
                var err: Throwable? = null
                try {
                    if (what != "videos") {
                        val a = artist ?: MusicParse.artist(WebApi.remixBrowse(act, calls, g, channelId, null)).also { artist = it }
                        if (a != null) {
                            // every page of albums, then of singles; each release's tracks; stop once a run's worth
                            // (Bulk.MAX) of new tracks is collected — "Download all" again continues with the rest
                            val active = Store.activeIds()
                            val seen = HashSet<String>()
                            var fresh = 0
                            var releasesDone = 0
                            val cs = listOf(MusicParse.Cursor("Album", a.albumsMore, a.albums), MusicParse.Cursor("Single", a.singlesMore, a.singles))
                            outer@ for (c in cs) {
                                while (collecting) {
                                    val page = nextPage(g, c)
                                    for (r in page) {
                                        if (!collecting || fresh > Bulk.MAX) break@outer
                                        main.post { showCollect(tr("Collecting: ${releasesDone + 1} releases · $fresh new tracks", "Toplanıyor: ${releasesDone + 1} yayın · $fresh yeni parça")) }
                                        val tracks = playlistAll(g, r.id)
                                        for (t in tracks) if (seen.add(t.id) && t.id !in active && !Store.downloadedAny(t.id)) fresh++
                                        lists += r.title to tracks
                                        releasesDone++
                                    }
                                    if (c.done) break
                                }
                            }
                            if (collecting && fresh <= Bulk.MAX) a.songsPlaylist?.let { lists += null to playlistAll(g, it) } // songs not on a release
                        }
                    }
                    if (what != "music" && collecting) {
                        main.post { showCollect(tr("Collecting videos…", "Videolar toplanıyor…")) }
                        lists += null to channelVideosAll(g)
                    }
                } catch (e: Throwable) { err = e }
                main.post {
                    if (g != calls.gen) return@post
                    val stopped = !collecting
                    collecting = false
                    showCollect(null)
                    if (err != null && lists.isEmpty()) { setStatus(errText(err!!), true); return@post }
                    if (stopped && lists.isEmpty()) return@post
                    val who = (artist?.name?.ifEmpty { null } ?: name).removeSuffix(" - Topic")
                    val label = when (what) { "music" -> tr("All music", "Tüm müzikler"); "videos" -> tr("All videos", "Tüm videolar"); else -> tr("Everything", "Her şey") }
                    bulkTracks(lists, "$who · $label", what != "videos")
                }
            }
        }

        /** Every video of a playlist (all continuations), on a background thread. */
        private fun playlistAll(g: Int, playlistId: String): List<Item> {
            var o = WebApi.browse(act, calls, g, "VL$playlistId", null)
            val gl = o.optString("_gl").ifEmpty { null }
            var p = Browse.page(o)
            val out = ArrayList<Item>(p.items)
            while (p.continuation != null && out.size < 5000 && collecting) {
                o = WebApi.more(act, calls, g, Clients.BROWSE_URL, p.continuation!!, gl)
                p = Browse.page(o); out += p.items
            }
            return out.filter { it.isVideo }
        }

        private fun channelVideosAll(g: Int): List<Item> {
            var o = WebApi.browse(act, calls, g, channelId, params("videos"))
            val gl = o.optString("_gl").ifEmpty { null }
            var p = Browse.page(o)
            val out = ArrayList<Item>(p.items)
            while (p.continuation != null && out.size < 5000 && collecting) {
                main.post { showCollect(tr("Collecting videos… ${out.size}", "Videolar toplanıyor… ${out.size}")) }
                o = WebApi.more(act, calls, g, Clients.BROWSE_URL, p.continuation!!, gl)
                p = Browse.page(o); out += p.items
            }
            return out.filter { it.isVideo }
        }

        override fun onCancelled() { super.onCancelled(); if (collecting) { collecting = false; showCollect(null) } }
        override val musicDefault: Boolean get() = topic || tab == "music"

        /**
         * An empty channel (e.g. "@FosixErlik" is an empty channel; the streamer's channel is "Foşix Erlik" and
         * YouTube's channel search doesn't list it): offer channels with a similar name found in video results.
         */
        private fun findSimilar(msg: String) {
            setStatus(tr("YouTube: $msg. Looking for channels with a similar name…", "YouTube: $msg. Benzer adlı kanallar aranıyor…"))
            val q = name
            run(false, { g -> WebApi.search(act, calls, g, q, Clients.SEARCH_VIDEOS) }) { o ->
                val owners = Browse.page(o).items.filter { it.channelId != null && it.channelId != channelId && Browse.similarName(q, it.channel) }
                    .distinctBy { it.channelId }.map { Item(IKind.CHANNEL, it.channelId!!, it.channel, count = tr("Similar name, from video results", "Benzer ad, video sonuçlarından")) }
                items += owners
                adapter.notifyDataSetChanged()
                setStatus(if (owners.isEmpty()) "YouTube: $msg" else tr("YouTube: $msg. Did you mean:", "YouTube: $msg. Bunu mu demek istediniz:"))
            }
        }

        private fun switchTab(k: String) {
            if (k == tab) return
            endSelection()
            cancelNet()
            saved[tab] = Triple(items.toList(), token, loadedOnce)
            tab = k
            items.clear()
            val s = saved[k]
            if (s != null) { items += s.first; token = s.second; loadedOnce = s.third } else { token = null; loadedOnce = false }
            firstPage()
            adapter.notifyDataSetChanged()
            setStatus(null)
            updateHead()
            if (!loadedOnce) firstLoad() else refreshFooter()
        }

        override fun onShow() { updateHead(); super.onShow() }
        override fun onChannelTap(i: Item) { if (i.channelId == channelId) onTap(i) else super.onChannelTap(i) }
    }

    // ---- Playlist ----

    inner class PlaylistPage(private val playlistId: String, private var name: String) : ListPage() {
        /** After loading: "download" / "cache" / "listen" / "list" over the whole playlist (hold menu actions). */
        var auto: String? = null
        /** YouTube Music albums (OLAK5uy_…) are music: audio is the default and the album name the folder. */
        private val music get() = playlistId.startsWith("OLAK")
        private val titleV = act.text(name.ifEmpty { tr("Playlist", "Çalma listesi") }, 18f, C.TEXT, bold = true, lines = 3)
        private val ownerV = act.text("", 13f, C.DIM)
        private val allBtn = act.pill(tr("Download all", "Tümünü indir"), C.ACCENT, C.ACCENT) { downloadAll() }
        private val stopBtn = act.pill(tr("Stop", "Durdur")) { stopAll() }
        private val progressV = act.text("", 12f, C.LABEL)
        private var ownerId: String? = null
        private var count = ""
        private var gathering = false

        init {
            head.addView(titleV.apply { setPadding(0, act.dp(4), 0, 0) })
            head.addView(ownerV.apply {
                setPadding(0, act.dp(2), 0, 0)
                setOnClickListener { ownerId?.let { push(ChannelPage(it, ownerName)) } }
            })
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(8), 0, act.dp(4)) }
            row.addView(progressV, lpL(0, WRAP, 1f))
            row.addView(stopBtn)
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(allBtn)
            head.addView(row, lpL(MATCH, WRAP))
            val flow = FlowLayout(act).apply { setPadding(0, 0, 0, act.dp(4)) }
            // hold = the same, shuffled
            flow.addView(act.pill(tr("Play all", "Tümünü oynat")) { gatherThen { playAll(items, null, name) } }.holdToShuffle { gatherThen { playAll(items, null, name, shuffle = true) } })
            flow.addView(act.pill(tr("Listen all", "Tümünü dinle")) { gatherThen { playAll(items, PlayMode.LISTEN, name) } }.holdToShuffle { gatherThen { playAll(items, PlayMode.LISTEN, name, shuffle = true) } })
            flow.addView(act.pill(tr("Cache all", "Tümünü önbelleğe al")) { gatherThen { cacheItems(items, name, music, name) } })
            flow.addView(act.pill(tr("Erase downloaded…", "İndirilenleri sil…")) {
                gatherThen { eraseDownloaded(downloadedFor(items.filter { it.isVideo }.mapTo(HashSet()) { it.id }, null), name.ifEmpty { tr("this playlist", "bu çalma listesi") }) }
            })
            head.addView(flow, lpL(MATCH, WRAP))
            stopBtn.visibility = View.GONE
        }

        private var ownerName = ""

        override fun firstLoad() {
            run(true, { g -> WebApi.browse(act, calls, g, "VL$playlistId", null) }) { o ->
                Browse.alert(o)?.let { setStatus(it, true); return@run }
                val h = Browse.playlistHead(o, playlistId)
                if (h == null) { setStatus(tr("This playlist can't be opened (private, or a Mix)", "Bu çalma listesi açılamıyor (özel ya da bir Mix)"), true); return@run }
                name = h.title; ownerName = h.owner; ownerId = h.ownerId; count = h.count
                region = o.optString("_gl").ifEmpty { null }
                titleV.text = name
                ownerV.text = listOf(h.owner, h.count).filter { it.isNotEmpty() }.joinToString(" · ")
                loadedOnce = true
                items.clear()
                append(Browse.page(o))
                setStatus(if (items.isEmpty()) tr("This playlist is empty", "Bu çalma listesi boş") else null)
                updateProgress()
                when (auto) {
                    "download" -> downloadAll()
                    "cache" -> gatherThen { cacheItems(items, name, music, name) }
                    "listen" -> gatherThen { playAll(items, PlayMode.LISTEN, name) }
                    "shuffle" -> gatherThen { playAll(items, null, name, shuffle = true) }
                    "list" -> gatherThen { Hold.addToList(act, items) }
                }
                auto = null
            }
        }

        private fun updateProgress() {
            val n = items.count { it.isVideo }
            progressV.text = if (gathering) tr("Loading all… $n videos", "Tümü yükleniyor… $n video") else if (token != null) tr("$n loaded", "$n yüklendi") + (if (count.isNotEmpty()) tr(" of $count", " / $count") else "") else tr("$n videos", "$n video")
            stopBtn.visibility = if (gathering) View.VISIBLE else View.GONE
            allBtn.visibility = if (gathering) View.GONE else View.VISIBLE
        }

        /** Load every continuation first (progress + Stop), then ask the options once and queue them all. */
        private fun downloadAll() = gatherThen { bulk(items, name, name, music, link = if (music) null else "yt:$playlistId") }

        private var then: (() -> Unit)? = null

        /** Load every continuation (progress + Stop, up to the bulk limit of new videos), then run [action]. */
        private fun gatherThen(action: () -> Unit) {
            if (!loadedOnce || gathering) return
            then = action
            gathering = true
            updateProgress()
            step()
        }

        private fun step() {
            if (!gathering) return
            val t = token
            val fresh = items.count { it.isVideo && !Store.downloadedAny(it.id) }
            if (t == null || fresh > Bulk.MAX) {
                gathering = false
                updateProgress()
                then?.invoke(); then = null
                return
            }
            run(false, { g -> WebApi.more(act, calls, g, contUrl, t, region) }, fail = { gathering = false; updateProgress() }) {
                append(Browse.page(it))
                updateProgress()
                step()
            }
        }

        private fun stopAll() { gathering = false; cancelNet(); updateProgress() }

        override fun onCancelled() { super.onCancelled(); if (gathering) { gathering = false; updateProgress() } }
        override fun afterAppend() { if (loadedOnce) updateProgress() }
        override val albumHint: String? get() = name.ifEmpty { null }
        override val musicDefault: Boolean get() = music
    }

    /** Play all / Listen all: replaces the play queue and opens the player on it ([shuffle]: in random order). */
    fun playAll(items: List<Item>, mode: PlayMode?, album: String?, shuffle: Boolean = false) {
        val e = items.filter { it.isVideo && !it.live }.map { PlayQueue.of(it, mode, album) }.let { if (shuffle) it.shuffled() else it }
        if (e.isEmpty()) { act.toast(tr("Nothing to play", "Oynatılacak bir şey yok")); return }
        Store.setQueue(PlayQueue().replace(e))
        PlayerActivity.queue(act)
        if (shuffle) act.toast(tr("Shuffled ${e.size}", "${e.size} öğe karışık çalınıyor"))
    }

    /** Hold on a Play all / Listen all pill = the shuffled version (haptic, like every hold). */
    private fun View.holdToShuffle(fn: () -> Unit): View = apply {
        setOnLongClickListener { v -> v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); fn(); true }
    }

    // ---- Lists (local, "categorize") ----

    inner class ListsPage : ListPage() {
        private val queueBtn = act.pill(tr("Play queue", "Çalma sırası")) { if (Store.queue().items.isEmpty()) act.toast(tr("The play queue is empty", "Çalma sırası boş")) else PlayerActivity.queue(act) }

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, act.dp(6)) }
            row.addView(act.pill(tr("New list…", "Yeni liste…")) { Hold.newList(act) { fill() } })
            row.addView(View(act), lpL(0, 1, 1f))
            row.addView(queueBtn)
            head.addView(row, lpL(MATCH, WRAP))
        }

        override val canRetry get() = false
        override fun firstLoad() { loadedOnce = true; fill() }
        override fun onShow() { loadedOnce = true; fill(); refreshFooter() }

        fun fill() {
            items.clear()
            Store.lists().forEach { items += Item(IKind.PLAYLIST, "local:" + it.id, it.name, count = tr("${it.items.size} videos", "${it.items.size} video"), thumbId = it.items.firstOrNull()?.id) }
            adapter.notifyDataSetChanged()
            queueBtn.text = tr("Play queue (${Store.queue().items.size})", "Çalma sırası (${Store.queue().items.size})")
            setStatus(if (items.isEmpty()) tr("No lists yet. Press & hold a video → Add to list…, or select several → ⋮ → Add to list…", "Henüz liste yok. Bir videoyu basılı tutun → Listeye ekle… ya da birkaçını seçin → ⋮ → Listeye ekle…") else null)
        }

        override fun onLongTap(i: Item) {
            val l = Store.lists().firstOrNull { "local:" + it.id == i.id } ?: return
            act.menu(l.name, listOf(
                tr("Open", "Aç") to { push(LocalListPage(l.id)) },
                tr("Play all", "Tümünü oynat") to { playAll(l.items, null, l.name) },
                tr("Listen all", "Tümünü dinle") to { playAll(l.items, PlayMode.LISTEN, l.name) },
                tr("Shuffle all", "Tümünü karışık çal") to { playAll(l.items, null, l.name, shuffle = true) },
                tr("Download all…", "Tümünü indir…") to { bulk(l.items, l.name, link = "list:${l.id}") },
                tr("Cache all", "Tümünü önbelleğe al") to { cacheItems(l.items, l.name) },
                tr("Rename…", "Yeniden adlandır…") to { Hold.rename(act, l) { fill() } },
                tr("Delete list", "Listeyi sil") to { Store.setLists(VideoLists.delete(Store.lists(), l.id)); fill() },
            ))
        }
    }

    inner class LocalListPage(private val listId: String) : ListPage() {
        private val titleV = act.text("", 18f, C.TEXT, bold = true, lines = 2)
        private fun list() = Store.lists().firstOrNull { it.id == listId }

        init {
            head.addView(titleV.apply { setPadding(0, act.dp(4), 0, act.dp(4)) })
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(4), 0, act.dp(6)) }
            flow.addView(act.pill(tr("Play all", "Tümünü oynat"), C.ACCENT, C.ACCENT) { playAll(items, null, list()?.name) }.holdToShuffle { playAll(items, null, list()?.name, shuffle = true) })
            flow.addView(act.pill(tr("Listen all", "Tümünü dinle")) { playAll(items, PlayMode.LISTEN, list()?.name) }.holdToShuffle { playAll(items, PlayMode.LISTEN, list()?.name, shuffle = true) })
            flow.addView(act.pill(tr("Download all…", "Tümünü indir…")) { bulk(items, list()?.name, link = "list:$listId") })
            flow.addView(act.pill(tr("Cache all", "Tümünü önbelleğe al")) { cacheItems(items, list()?.name) })
            head.addView(flow, lpL(MATCH, WRAP))
        }

        override val canRetry get() = false
        override val albumHint: String? get() = list()?.name
        override fun firstLoad() { loadedOnce = true; fill() }
        override fun onShow() { loadedOnce = true; fill(); refreshFooter() }

        private fun fill() {
            val l = list()
            titleV.text = l?.name ?: tr("List deleted", "Liste silindi")
            items.clear(); l?.items?.let { items += it }
            adapter.notifyDataSetChanged()
            setStatus(if (items.isEmpty()) tr("Empty list", "Boş liste") else null)
        }

        override fun videoExtras(i: Item) = listOf<Pair<String, () -> Unit>>(
            tr("Remove from this list", "Bu listeden kaldır") to { Store.setLists(VideoLists.removeItem(Store.lists(), listId, i.id)); fill() })
    }

    // ---- Spotify playlists, converted to YouTube (3.2) ----

    interface SpotifyRefresh { fun refresh() }

    private fun ago(t: Long): String {
        if (t <= 0) return tr("never", "hiç")
        val m = (System.currentTimeMillis() - t) / 60000
        return when {
            m < 1 -> tr("just now", "az önce")
            m < 60 -> tr("$m min ago", "$m dk önce")
            m < 48 * 60 -> tr("${m / 60} h ago", "${m / 60} sa önce")
            else -> tr("${m / 1440} days ago", "${m / 1440} gün önce")
        }
    }

    /**
     * The Spotify chip (3.4, no sign-in): type a Spotify profile link / username (or a playlist link), Find reads its
     * public playlists and asks which to convert; Sync reads them again and matches new songs. Nothing is fetched
     * unless the user taps Find / Sync / Try again.
     */
    inner class SpotifyPage : ListPage(), SpotifyRefresh {
        private val info = act.text("", 13f, C.DIM, lines = 4)
        private val input = android.widget.EditText(act).apply {
            hint = tr("Spotify profile link, username or playlist link", "Spotify profil bağlantısı, kullanıcı adı ya da liste bağlantısı")
            setTextColor(C.TEXT); setHintTextColor(C.LABEL); textSize = 14f
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            background = act.outline(act.dpf(10))
            setPadding(act.dp(12), act.dp(8), act.dp(12), act.dp(8))
            setOnEditorActionListener { _, _, _ -> findNow(); true }
        }
        private val findBtn = act.pill(tr("Find", "Bul"), C.ACCENT, C.ACCENT) { findNow() }
        private val sync = act.pill(tr("Sync", "Eşitle"), C.ACCENT, C.ACCENT) { SpotifySync.start(act); refresh() }
        private val stop = act.pill(tr("Stop", "Durdur")) { SpotifySync.stop() }
        private val more = act.icon(R.drawable.yt_ic_more, desc = tr("More", "Diğer")) { menu() }

        init {
            head.addView(info.apply { setPadding(0, act.dp(4), 0, act.dp(4)) }, lpL(MATCH, WRAP))
            val ask = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(2), 0, act.dp(4)) }
            ask.addView(input, lpL(0, WRAP, 1f))
            ask.addView(View(act), lpL(act.dp(8), 1))
            ask.addView(findBtn)
            head.addView(ask, lpL(MATCH, WRAP))
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(2), 0, act.dp(6)) }
            row.addView(sync)
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(stop)
            row.addView(View(act), lpL(0, 1, 1f))
            row.addView(more)
            head.addView(row, lpL(MATCH, WRAP))
        }

        private fun findNow() {
            val q = input.text?.toString().orEmpty().trim()
            if (q.isEmpty()) { act.toast(tr("Type a Spotify profile link or username first", "Önce bir Spotify profil bağlantısı ya da kullanıcı adı yazın")); return }
            act.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
            SpotifySync.find(act, q) { f -> choose(f) }
            refresh()
        }

        /** Which of the found playlists to convert (all ticked at first; the ones already converted stay ticked). */
        private fun choose(f: SpotifySync.Found) {
            if (act.isDestroyed) return
            if (f.lists.isEmpty()) { act.message("Spotify", tr("${f.name} has no public playlists.", "${f.name} adlı profilin herkese açık çalma listesi yok.")); return }
            val d = SpotifySync.data()
            if (f.profile.isEmpty()) { SpotifySync.choose(f, f.lists); SpotifySync.start(act); refresh(); return } // a playlist link: add it
            // playlists added by link before stay offered (ticked)
            val have = d.playlists.map { it.id }.toSet()
            val extra = if (d.profile == f.profile || d.profile.isEmpty()) d.playlists.filter { p -> f.lists.none { it.id == p.id } }.map { SpotifyPublic.Ref(it.id, it.name) } else emptyList()
            val all = f.lists + extra
            val first = d.profile != f.profile || have.isEmpty()
            val ticks = BooleanArray(all.size) { i -> first || all[i].id in have }
            android.app.AlertDialog.Builder(act)
                .setTitle(tr("${f.name}: ${f.lists.size} public playlists", "${f.name}: ${f.lists.size} herkese açık liste"))
                .setMultiChoiceItems(all.map { it.name }.toTypedArray(), ticks) { _, i, on -> ticks[i] = on }
                .setPositiveButton(tr("Convert & sync", "Dönüştür ve eşitle")) { _, _ ->
                    val picked = all.filterIndexed { i, _ -> ticks[i] }
                    if (picked.isEmpty()) { act.toast(tr("Nothing picked", "Hiçbiri seçilmedi")); return@setPositiveButton }
                    SpotifySync.choose(f, picked)
                    SpotifySync.start(act)
                    refresh()
                }
                .setNegativeButton(tr("Cancel", "İptal"), null)
                .show()
        }

        override val canRetry get() = false
        override fun firstLoad() { loadedOnce = true; refresh() }
        override fun onShow() { loadedOnce = true; refresh(); refreshFooter() }

        override fun refresh() {
            SpotifySync.init(act)
            val d = SpotifySync.data()
            if (input.text.isNullOrEmpty() && d.profile.isNotEmpty() && !input.hasFocus()) input.setText(d.profile)
            val busy = SpotifySync.running
            findBtn.visibility = if (busy) View.GONE else View.VISIBLE
            sync.visibility = if (!busy && d.playlists.isNotEmpty()) View.VISIBLE else View.GONE
            stop.visibility = if (busy) View.VISIBLE else View.GONE
            more.visibility = if (d.playlists.isNotEmpty() || d.profile.isNotEmpty()) View.VISIBLE else View.GONE
            info.text = if (d.playlists.isEmpty()) tr(
                "Type a Spotify profile (its link: Profile → ⋯ → Share → Copy link, or the username) and tap Find: its public playlists are read without signing in. Each song is found on YouTube Music and every saved playlist downloads by itself into the Music tab. Spotify's own audio isn't used.",
                "Bir Spotify profili yazın (bağlantısı: Profil → ⋯ → Paylaş → Bağlantıyı kopyala, ya da kullanıcı adı) ve Bul'a dokunun: herkese açık listeleri oturum açmadan okunur. Her şarkı YouTube Music'te bulunur ve kayıtlı her liste kendiliğinden Müzik sekmesine iner. Spotify'ın kendi sesi kullanılmaz.")
            else listOfNotNull(
                if (d.user.isNotEmpty()) "Spotify: ${d.user}" else null,
                tr("synced ${ago(d.synced)}", "eşitlendi: ${ago(d.synced)}"),
            ).joinToString(" · ")
            items.clear()
            d.playlists.forEach { p ->
                val ok = d.matched(p); val miss = d.missing(p).size; val pend = d.pending(p)
                val state = if (p.trackIds.isEmpty() && p.snapshot.isEmpty()) tr("not synced yet", "henüz eşitlenmedi") else listOfNotNull(
                    tr("$ok of ${p.trackIds.size} found", "${p.trackIds.size} şarkıdan $ok bulundu"),
                    if (miss > 0) tr("$miss not found", "$miss bulunamadı") else null,
                    if (pend > 0) tr("$pend to match", "$pend eşleşecek") else null,
                ).joinToString(" · ")
                items += Item(IKind.PLAYLIST, "spotify:" + p.id, p.name, channel = p.owner, count = "${p.trackIds.size}",
                    age = state, thumbId = d.items(p).firstOrNull()?.id)
            }
            adapter.notifyDataSetChanged()
            val err = SpotifySync.lastError
            when {
                busy -> setStatus(SpotifySync.progress ?: tr("Working…", "Çalışıyor…"))
                err != null -> setStatus(err, true)
                else -> setStatus(SpotifySync.lastNote)
            }
        }

        private fun menu() {
            val d = SpotifySync.data()
            act.menu("Spotify", listOfNotNull(
                if (d.profile.isNotEmpty()) tr("Choose playlists of ${d.user.ifEmpty { d.profile }}…", "${d.user.ifEmpty { d.profile }} listelerini seç…") to {
                    SpotifySync.find(act, d.profile) { f -> choose(f) }; refresh()
                } else null,
                if (d.playlists.isNotEmpty()) tr("Try the not-found songs again", "Bulunamayan şarkıları yeniden dene") to { SpotifySync.start(act, onlyMatch = true, retryMissing = true); refresh() } else null,
                tr("Forget the Spotify playlists", "Spotify listelerini unut") to {
                    android.app.AlertDialog.Builder(act)
                        .setMessage(tr("Remove the profile and the converted playlists from this phone? Songs you downloaded stay.",
                            "Profil ve dönüştürülen listeler bu telefondan kaldırılsın mı? İndirdiğiniz şarkılar kalır."))
                        .setPositiveButton(tr("Forget", "Unut")) { _, _ -> SpotifySync.forget(); input.setText(""); refresh() }
                        .setNegativeButton(tr("Cancel", "İptal"), null).show()
                },
            ))
        }

        override fun onLongTap(i: Item) {
            val id = i.id.removePrefix("spotify:")
            val d = SpotifySync.data()
            val p = d.playlists.firstOrNull { it.id == id } ?: return
            val its = d.items(p)
            act.menu(p.name, listOf(
                tr("Open", "Aç") to { push(SpotifyListPage(id)) },
                tr("Listen all", "Tümünü dinle") to { playAll(its, PlayMode.LISTEN, p.name) },
                tr("Shuffle all", "Tümünü karışık çal") to { playAll(its, PlayMode.LISTEN, p.name, shuffle = true) },
                tr("Download all…", "Tümünü indir…") to { bulk(its, p.name, p.name, music = true, link = "spotify:${p.id}") },
                tr("Cache all", "Tümünü önbelleğe al") to { cacheItems(its, p.name, music = true, title = p.name) },
                tr("Copy to my lists", "Listelerime kopyala") to {
                    val (all, l) = VideoLists.create(Store.lists(), p.name, System.currentTimeMillis())
                    Store.setLists(VideoLists.add(all, l.id, its).first)
                    act.toast(tr("Copied to Lists: ${p.name}", "Listelere kopyalandı: ${p.name}"))
                },
                tr("Remove from here", "Buradan kaldır") to { SpotifySync.remove(id); refresh() },
            ))
        }
    }

    /** One converted playlist: its found songs (Spotify's title and artist); tap a song to listen from there. */
    inner class SpotifyListPage(private val playlistId: String) : ListPage(), SpotifyRefresh {
        private val titleV = act.text("", 18f, C.TEXT, bold = true, lines = 2)
        private val subV = act.text("", 13f, C.DIM, lines = 2)
        private val missBtn = act.pill("") { showMissing() }
        private fun pl() = SpotifySync.data().playlists.firstOrNull { it.id == playlistId }

        init {
            head.addView(titleV.apply { setPadding(0, act.dp(4), 0, 0) })
            head.addView(subV.apply { setPadding(0, act.dp(2), 0, act.dp(4)) })
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(4), 0, act.dp(6)) }
            flow.addView(act.pill(tr("Listen all", "Tümünü dinle"), C.ACCENT, C.ACCENT) { playAll(items, PlayMode.LISTEN, pl()?.name) }.holdToShuffle { playAll(items, PlayMode.LISTEN, pl()?.name, shuffle = true) })
            flow.addView(act.pill(tr("Play all", "Tümünü oynat")) { playAll(items, null, pl()?.name) }.holdToShuffle { playAll(items, null, pl()?.name, shuffle = true) })
            flow.addView(act.pill(tr("Download all…", "Tümünü indir…")) { bulk(items, pl()?.name, pl()?.name, music = true, link = "spotify:$playlistId") })
            flow.addView(act.pill(tr("Cache all", "Tümünü önbelleğe al")) { cacheItems(items, pl()?.name, music = true, title = pl()?.name) })
            flow.addView(missBtn)
            head.addView(flow, lpL(MATCH, WRAP))
        }

        override val canRetry get() = false
        override val albumHint: String? get() = pl()?.name
        override val musicDefault: Boolean get() = true
        override fun firstLoad() { loadedOnce = true; refresh() }
        override fun onShow() { loadedOnce = true; refresh(); refreshFooter() }

        override fun refresh() {
            val d = SpotifySync.data()
            val p = pl()
            titleV.text = p?.name ?: tr("Playlist gone (removed on Spotify)", "Liste yok (Spotify'da kaldırıldı)")
            items.clear(); if (p != null) items += d.items(p)
            val miss = if (p == null) 0 else d.missing(p).size
            val pend = if (p == null) 0 else d.pending(p)
            subV.text = if (p == null) "" else listOfNotNull("Spotify · ${p.owner}",
                tr("${items.size} of ${p.trackIds.size} found", "${p.trackIds.size} şarkıdan ${items.size} bulundu"),
                if (pend > 0) tr("$pend to match", "$pend eşleşecek") else null).joinToString(" · ")
            missBtn.text = tr("Not found ($miss)", "Bulunamayan ($miss)")
            missBtn.visibility = if (miss > 0) View.VISIBLE else View.GONE
            adapter.notifyDataSetChanged()
            setStatus(if (SpotifySync.running) SpotifySync.progress
                else if (items.isEmpty() && pend > 0) tr("Not matched yet: Sync on the Spotify page", "Henüz eşleşmedi: Spotify sayfasında Eşitle") else null)
        }

        /** Tap a song: listen to the playlist from that song. */
        override fun onTap(i: Item) {
            if (selecting) { toggle(i); return }
            val k = items.indexOfFirst { it.id == i.id }.coerceAtLeast(0)
            Store.setQueue(PlayQueue().replace(items.map { PlayQueue.of(it, PlayMode.LISTEN, pl()?.name) }, k))
            PlayerActivity.queue(act)
        }

        override fun videoExtras(i: Item) = listOf<Pair<String, () -> Unit>>(
            tr("Wrong song? Find another version", "Yanlış şarkı mı? Başka sürüm bul") to {
                val p = pl()
                val t = p?.let { SpotifySync.data().trackFor(it, i.id) }
                if (t != null) { SpotifySync.reject(act, t, i.id); refresh() }
            },
            tr("Search YouTube for it", "YouTube'da ara") to { val t = spTrack(i.id); showBase(searchPage); searchPage.searchFor(t?.query ?: i.title) },
        )

        private fun spTrack(videoId: String): SpTrack? {
            val d = SpotifySync.data(); val p = pl() ?: return null
            return d.trackFor(p, videoId)?.let { d.tracks[it] }
        }

        /** Songs YouTube Music didn't have: tap one to search YouTube for it. */
        private fun showMissing() {
            val d = SpotifySync.data(); val p = pl() ?: return
            val miss = d.missing(p).mapNotNull { d.tracks[it] }
            if (miss.isEmpty()) return
            act.menu(tr("Not found on YouTube Music — tap to search", "YouTube Music'te bulunamadı — aramak için dokunun"),
                miss.take(60).map { t -> "${t.artist} — ${t.title}" to { showBase(searchPage); searchPage.searchFor(t.query) } })
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // rows

    inner class ItemAdapter(private val page: ListPage) : BaseAdapter() {
        private val start get() = page.shownPage * PAGE
        private fun at(p: Int) = page.items[start + p]
        override fun getCount() = (page.items.size - start).coerceIn(0, PAGE)
        override fun getItem(p: Int): Any = at(p)
        override fun getItemId(p: Int) = (start + p).toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (at(p).kind == IKind.CHANNEL) 1 else 0
        override fun notifyDataSetChanged() { super.notifyDataSetChanged(); page.updateFooter() }

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val i = at(p)
            val h = (cv?.tag as? Row) ?: Row(i.kind == IKind.CHANNEL)
            h.bind(i, page)
            return h.view
        }
    }

    private inner class Row(channelRow: Boolean) {
        val view = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(8), act.dp(3), act.dp(8), act.dp(3))
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
        }
        private val thumbW = act.dp(112)
        private val thumb = if (channelRow) null else RoundImage(act)
        private val badge = act.text("", 11f, C.TEXT, bold = true).apply {
            background = roundRect(0xCC000000.toInt(), act.dpf(4)); setPadding(act.dp(4), act.dp(1), act.dp(4), act.dp(1))
        }
        private val avatar = act.text("", 18f, C.DIM, bold = true).apply {
            gravity = Gravity.CENTER; background = act.outline(act.dpf(22))
        }
        private val title = act.text("", 13f, C.TEXT, bold = true, lines = 2).apply { setLineSpacing(0f, 0.95f) }
        private val channel = act.text("", 11.5f, C.DIM, lines = 1).apply { maxWidth = act.dp(150) }
        private val meta = act.text("", 11.5f, C.LABEL, lines = 1)
        private val mark = act.text("", 11f, C.GREEN)
        private val check = act.text("", 18f, C.ACCENT).apply { gravity = Gravity.CENTER }
        private var thumbBox: FrameLayout? = null
        private val playMark = act.text("▶", 14f, C.TEXT).apply { gravity = Gravity.CENTER; background = oval(0x99000000.toInt()) }
        private val membersMark = act.membersBadge()
        private val normalBg = ripple(null)
        private val selBg = ripple(act.outline(act.dpf(6), C.ACCENT))

        init {
            if (thumb != null) {
                val box = FrameLayout(act)
                thumbBox = box
                box.addView(thumb, FrameLayout.LayoutParams(thumbW, thumbW * 9 / 16))
                box.addView(playMark, FrameLayout.LayoutParams(act.dp(30), act.dp(30), Gravity.CENTER))
                box.addView(badge, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.END or Gravity.BOTTOM).margins(0, 0, act.dp(4), act.dp(4)))
                box.addView(membersMark, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP).margins(act.dp(4), act.dp(4), 0, 0))
                view.addView(box, lpL(thumbW, thumbW * 9 / 16))
            } else {
                view.addView(avatar, lpL(act.dp(36), act.dp(36)).apply { marginStart = act.dp(4); marginEnd = act.dp(4) })
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(8), 0, act.dp(2), 0) }
            col.addView(title, lpL(MATCH, WRAP))
            // channel · views · age on one line (the channel name stays its own tap target)
            val info = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            info.addView(channel, lpL(WRAP, WRAP).apply { marginEnd = act.dp(6) })
            info.addView(meta, lpL(0, WRAP, 1f))
            col.addView(info, lpL(MATCH, WRAP).apply { topMargin = act.dp(1) })
            col.addView(mark, lpL(MATCH, WRAP))
            view.addView(col, lpL(0, WRAP, 1f))
            view.addView(check, lpL(act.dp(28), WRAP))
            view.tag = this
        }

        fun bind(i: Item, page: ListPage) {
            title.text = i.title.ifEmpty { i.id }
            when (i.kind) {
                IKind.CHANNEL -> {
                    // first letter; a whole code point, so a name starting with an emoji doesn't show half of it ("?")
                    avatar.text = i.title.trim().let { t -> if (t.isEmpty()) "" else app.plain.lang.PlainLang.upper(t.substring(0, Character.charCount(t.codePointAt(0)))) }
                    channel.text = i.handle
                    meta.text = i.count
                    channel.setOnClickListener(null); channel.isClickable = false
                }
                IKind.PLAYLIST -> {
                    channel.text = i.channel
                    meta.text = when {
                        i.id.startsWith("local:") -> tr("Local list", "Yerel liste")
                        i.id.startsWith("spotify:") -> i.age.ifEmpty { "Spotify" }
                        else -> tr("Playlist", "Çalma listesi")
                    }
                    badge.text = i.count
                }
                else -> {
                    channel.text = i.channel
                    val age = i.age.ifEmpty { Ago.text(i.published, System.currentTimeMillis()) }
                    meta.text = listOf(if (i.kind == IKind.SHORT) "Short" else "", i.views, age).filter { it.isNotEmpty() }.joinToString(" · ")
                    badge.text = if (i.live) tr("LIVE", "CANLI") else Text.duration(i.durationSec)
                }
            }
            channel.visibility = if (channel.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            meta.visibility = if (meta.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            badge.visibility = if (badge.text.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (i.kind != IKind.CHANNEL) {
                if (i.channelId != null) channel.setOnClickListener { page.onChannelTap(i) } else { channel.setOnClickListener(null); channel.isClickable = false }
            }
            thumb?.let { thumbs.load(it, i.thumbId, thumbW) }

            if (i.isVideo) {
                val queued = i.id in activeIds
                val saved = Store.downloadedAny(i.id)
                mark.text = when { queued -> tr("In the download queue", "İndirme sırasında"); saved -> tr("✓ Downloaded", "✓ İndirildi"); else -> "" }
                mark.setTextColor(if (queued) C.ACCENT else C.GREEN)
            } else mark.text = ""
            mark.visibility = if (mark.text.isNullOrEmpty()) View.GONE else View.VISIBLE

            val selected = page.selecting && page.selectable(i) && i.id in page.sel
            view.background = if (selected) selBg else normalBg
            // thumbnail = play (Watch/Listen decided by the player), ♪ = listen only; title area = download options
            playMark.visibility = if (i.isVideo && !page.selecting && !i.live) View.VISIBLE else View.GONE
            membersMark.visibility = if (i.members) View.VISIBLE else View.GONE
            thumbBox?.setOnClickListener {
                if (page.selecting || !i.isVideo || i.live) page.onTap(i)
                else if (!membersBlocked(i)) PlayerActivity.stream(act, i.id, i.title, i.channel, album = page.albumHint)
            }
            thumbBox?.setOnLongClickListener { page.onLongTap(i); true }
            if (page.selecting || !i.isVideo || i.live) {
                check.text = if (!page.selecting || !page.selectable(i)) "" else if (selected) "●" else "○"
                check.setTextColor(if (selected) C.ACCENT else C.FAINT)
                check.setOnClickListener(null); check.isClickable = false
                check.contentDescription = null
            } else {
                check.text = "♪"
                check.setTextColor(C.DIM)
                check.contentDescription = tr("Listen", "Dinle")
                check.setPadding(0, act.dp(12), 0, act.dp(12))
                check.setOnClickListener { if (!membersBlocked(i)) PlayerActivity.stream(act, i.id, i.title, i.channel, mode = PlayMode.LISTEN, album = page.albumHint) }
            }
            view.setOnClickListener { page.onTap(i) }
            view.setOnLongClickListener { page.onLongTap(i); true }
        }
    }
}

/** Fixed-size thumbnail with rounded corners. */
class RoundImage(ctx: android.content.Context) : ImageView(ctx) {
    private val clip = android.graphics.Path()
    private val rect = android.graphics.RectF()
    private val r = ctx.dpf(8)

    init {
        scaleType = ScaleType.CENTER_CROP
        background = ctx.outline(r)
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        clip.reset(); clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); super.onDraw(canvas); canvas.restore()
    }
}

/** Options asked once for a bulk download: audio (format + quality) or video ("best up to" a height). */
class BulkDialog(
    private val act: MainActivity,
    private val n: Int,
    music: Boolean = false,
    private val onGo: (Kind, AudioFmt, AudioQ, Int) -> Unit,
) {
    private var kind = if (music) Kind.AUDIO else Store.kind // albums / Topic / artist channels: audio first
    private var fmt = Store.audioFmt
    private var q = Store.audioQ
    private var height = Store.videoP

    fun show() {
        val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(8), act.dp(20), 0) }
        val kindRow = FlowLayout(act)
        val audioBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val videoBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        fun label(s: String) = act.text(s, 12f, C.LABEL).apply { setPadding(0, act.dp(12), 0, act.dp(6)) }
        fun chipRow(row: FlowLayout, labels: List<String>, isSel: (Int) -> Boolean, pick: (Int) -> Unit) {
            row.removeAllViews()
            labels.forEachIndexed { i, l -> row.addView(Chip(act, l) { pick(i); chipRow(row, labels, isSel, pick) }.apply { select(isSel(i)) }) }
        }
        fun showKind() {
            audioBox.visibility = if (kind == Kind.AUDIO) View.VISIBLE else View.GONE
            videoBox.visibility = if (kind == Kind.VIDEO) View.VISIBLE else View.GONE
        }
        chipRow(kindRow, listOf(tr("Audio", "Ses"), "Video (MP4)"), { (it == 0) == (kind == Kind.AUDIO) }) { kind = if (it == 0) Kind.AUDIO else Kind.VIDEO; showKind() }
        col.addView(kindRow)

        audioBox.addView(label(tr("Format", "Biçim")))
        val fmtRow = FlowLayout(act)
        chipRow(fmtRow, AudioFmt.values().map { it.label }, { AudioFmt.values()[it] == fmt }) { fmt = AudioFmt.values()[it] }
        audioBox.addView(fmtRow)
        audioBox.addView(label(tr("Quality", "Kalite")))
        val qRow = FlowLayout(act)
        chipRow(qRow, listOf(tr("Best", "En iyi"), tr("Small", "Küçük")), { AudioQ.values()[it] == q }) { q = AudioQ.values()[it] }
        audioBox.addView(qRow)
        col.addView(audioBox)

        videoBox.addView(label(tr("Best quality up to", "En iyi kalite, en fazla")))
        val hs = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        val hRow = FlowLayout(act)
        chipRow(hRow, hs.map { "${it}p" }, { hs[it] == height || height !in hs && it == hs.indexOfFirst { h -> h <= height } }) { height = hs[it] }
        videoBox.addView(hRow)
        videoBox.addView(act.text(tr("Each video gets its best H.264 stream up to this height (lower if that's all it has).", "Her video bu yüksekliğe kadarki en iyi H.264 akışıyla iner (yalnızca daha düşüğü varsa o)."), 12f, C.LABEL, lines = 3)
            .apply { setPadding(0, act.dp(8), 0, 0) })
        col.addView(videoBox)
        col.addView(act.text(tr("Already downloaded or queued videos are skipped.", "Zaten indirilmiş ya da sırada olan videolar atlanır."), 12f, C.LABEL, lines = 2).apply { setPadding(0, act.dp(12), 0, act.dp(4)) })
        showKind()

        val scroll = android.widget.ScrollView(act).apply { addView(col) }
        android.app.AlertDialog.Builder(act)
            .setTitle(tr("Download $n video" + (if (n == 1) "" else "s"), "$n video indir"))
            .setView(scroll)
            .setPositiveButton(tr("Download", "İndir")) { _, _ ->
                Store.kind = kind; Store.audioFmt = fmt; Store.audioQ = q
                if (kind == Kind.VIDEO) Store.videoP = height
                onGo(kind, fmt, q, height)
            }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }
}
