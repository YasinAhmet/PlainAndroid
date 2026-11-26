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

/**
 * The Search tab: Feed (followed channels' RSS) | Search (InnerTube WEB), plus a back stack of channel / playlist
 * pages. Network only on user actions (opening a page, Search, Load more, Refresh, a stale Feed being opened); every
 * request is cancelled when the tab or screen goes away or the page changes.
 */
class SearchScreen(private val act: MainActivity) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4) // also the RSS parallelism limit
    private val calls = Calls()
    private val thumbs = ThumbLoader(act, calls)

    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
    private val segBar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(8), act.dp(4), act.dp(16), act.dp(4)) }
    private val selBar = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(act.dp(4), act.dp(4), act.dp(16), act.dp(4)); visibility = View.GONE }
    private val host = FrameLayout(act)
    private val backBtn = act.icon(R.drawable.ic_back, desc = "Back") { onBack() }

    /** 2.3 app-wide Back history: page pushes and Feed/Search/Lists switches are reported to MainActivity. */
    var onNav: ((NavStep) -> Unit)? = null
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

    private fun baseIndex(p: ListPage) = when (p) { searchPage -> 1; listsPage -> 2; else -> 0 }

    fun showBaseIndex(i: Int) = showBase(when (i) { 1 -> searchPage; 2 -> listsPage; else -> feedPage }, record = false)
    private val feedChip: Chip
    private val searchChip: Chip
    private val listsChip: Chip
    private val selCount = act.text("", 15f, C.TEXT, bold = true)
    private val selGo: TextView
    private lateinit var selMore: View

    private val feedPage = FeedPage()
    private val searchPage = SearchPage()
    private val listsPage = ListsPage()
    private var base: ListPage = feedPage
    private val stack = ArrayList<ListPage>()
    private val top: ListPage get() = stack.lastOrNull() ?: base
    private var shown = false

    // marks ("✓ Downloaded", "Queued") are redrawn only when downloads finish / the queue changes
    private var markSig = -1
    private var activeIds: Set<String> = emptySet()
    private val onStore: () -> Unit = {
        val s = Store.marksSig()
        if (s != markSig) { markSig = s; activeIds = Store.activeIds(); top.adapter.notifyDataSetChanged() }
    }

    init {
        segBar.addView(backBtn)
        feedChip = Chip(act, "Feed") { showBase(feedPage) }
        searchChip = Chip(act, "Search") { showBase(searchPage) }
        segBar.addView(feedChip)
        segBar.addView(View(act), lpL(act.dp(8), 1))
        segBar.addView(searchChip)
        listsChip = Chip(act, "Lists") { showBase(listsPage) }
        segBar.addView(View(act), lpL(act.dp(8), 1))
        segBar.addView(listsChip)
        view.addView(segBar, lpL(MATCH, WRAP))

        selBar.addView(act.icon(R.drawable.ic_close, desc = "Stop selecting") { top.endSelection() })
        selBar.addView(selCount, lpL(0, WRAP, 1f))
        selBar.addView(act.pill("All") { top.selectAll() })
        selBar.addView(View(act), lpL(act.dp(8), 1))
        selGo = act.pill("Download", C.ACCENT, C.ACCENT) { top.selGo() }
        selBar.addView(selGo)
        selMore = act.icon(R.drawable.ic_more, desc = "More for the selection") { selectionMenu() }
        selBar.addView(selMore)
        view.addView(selBar, lpL(MATCH, WRAP))
        view.addView(host, lpL(MATCH, 0, 1f))

        base = when (Store.prefs.getInt("searchSeg", if (Store.follows().isEmpty()) 1 else 0)) { 1 -> searchPage; 2 -> listsPage; else -> feedPage }
        attach()
    }

    // ---------------------------------------------------------------------------------------------------------
    // lifecycle / navigation

    fun onShown() {
        if (shown) return
        shown = true
        Store.addListener(onStore)
        markSig = Store.marksSig(); activeIds = Store.activeIds()
        top.onShow()
    }

    fun onHidden() {
        if (!shown) return
        shown = false
        Store.removeListener(onStore)
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
        (stack + feedPage + searchPage + listsPage).forEach { it.onCancelled() }
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
        Store.prefs.edit().putInt("searchSeg", when (p) { searchPage -> 1; listsPage -> 2; else -> 0 }).apply()
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
        backBtn.visibility = if (stack.isEmpty()) View.GONE else View.VISIBLE
        feedChip.select(base === feedPage)
        searchChip.select(base === searchPage)
        listsChip.select(base === listsPage)
        updateSelBar()
    }

    private fun updateSelBar() {
        val p = top
        segBar.visibility = if (p.selecting) View.GONE else View.VISIBLE
        selBar.visibility = if (p.selecting) View.VISIBLE else View.GONE
        val n = p.sel.size
        selCount.text = "$n selected"
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
        if (js.isEmpty()) { act.toast("Nothing downloaded from $what"); return }
        android.app.AlertDialog.Builder(act).setTitle("Erase ${js.size} downloaded file${if (js.size == 1) "" else "s"}?")
            .setMessage("From $what. They go to the trash (restorable for about 30 days; Undo right after).")
            .setPositiveButton("Erase") { _, _ -> act.eraseJobs(js) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Opens a channel page (hold menu "Visit channel", the player's Channel button). */
    fun openChannel(id: String, name: String) = push(ChannelPage(id, name))

    private fun selectionMenu() {
        val p = top
        val sel = p.selectedItems()
        if (sel.isEmpty()) return
        act.menu("${sel.size} selected", listOf(
            "Download ${sel.size}…" to { bulk(sel, p.albumHint, music = p.musicDefault) { p.endSelection() } },
            "Cache ${sel.size}" to { cacheItems(sel, p.albumHint, p.musicDefault); p.endSelection() },
            "Add to play queue" to { Hold.enqueue(act, sel.map { PlayQueue.of(it, null, p.albumHint) }); p.endSelection() },
            "Add to list…" to { Hold.addToList(act, sel) { p.endSelection() } },
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
        val todo = vids.filter { it.id !in active && it.id !in cached }
        if (todo.isNotEmpty()) {
            val base = Store.newId()
            val batch = if (todo.size > 1) base else 0L
            Store.addAll(todo.take(Bulk.MAX).mapIndexed { k, it ->
                Job(id = base + k, videoId = it.id, title = it.title, channel = "", durationSec = it.durationSec,
                    kind = if (music || Store.mode(it.id) == PlayMode.LISTEN) Kind.AUDIO else Kind.VIDEO, audioFmt = AudioFmt.M4A, audioQ = AudioQ.HIGH,
                    height = Store.watchP, codec = VCodec.AVC, album = Albums.pick(null, false, album), cache = true,
                    batch = batch, batchTitle = "Cache · " + (title ?: album ?: "${todo.size} videos"))
            })
            act.askNotificationsOnce()
            DownloadService.start(act)
        }
        act.toast("Caching ${todo.size}" + if (vids.size > todo.size) " (${vids.size - todo.size} already cached or queued)" else "")
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
        act.toast("Opening…")
        io.execute {
            val r = try { Result.success(WebApi.resolve(act, calls, g, url)) } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (g != calls.gen || !shown) return@post
                r.fold({ if (it is Target.ChannelUrl) act.toast("Not found") else open(it) }, { act.toast(errText(it)) })
            }
        }
    }

    private fun errText(e: Throwable): String = when (e) {
        is NoWifi -> "Not on Wi-Fi (Wi-Fi only is on)"
        is java.net.UnknownHostException -> "No internet connection"
        is java.net.SocketTimeoutException -> "Network timed out"
        is NotFound, is YtError, is AccountExpired -> e.message ?: "Not found"
        else -> "Couldn't load: ${e.message ?: e.javaClass.simpleName}"
    }

    // ---------------------------------------------------------------------------------------------------------
    // bulk

    /** Ask once for the options, then queue [items] (videos only), skipping what is already downloaded or queued. */
    fun bulk(items: List<Item>, album: String? = null, title: String? = null, music: Boolean = false, after: () -> Unit = {}) {
        val vids = items.filter { it.isVideo }
        if (vids.isEmpty()) { act.toast("No videos selected"); return }
        bulkTracks(listOf(album to vids), title ?: album ?: "${vids.size} selected videos", music, after)
    }

    /**
     * Queue several lists as ONE batch (one row in the downloads list): every video once, the first list it was found
     * in gives its album folder, already downloaded / queued ones skipped, at most [Bulk.MAX] per run (run again to
     * continue). [music]: audio is the default choice (albums, Topic / artist channels).
     */
    fun bulkTracks(lists: List<Pair<String?, List<Item>>>, title: String, music: Boolean, after: () -> Unit = {}) {
        val n = lists.flatMap { it.second }.filter { it.isVideo }.distinctBy { it.id }.size
        if (n == 0) { act.toast("Nothing to download"); return }
        BulkDialog(act, n, music) { kind, fmt, q, height ->
            if (!act.wifiOk()) return@BulkDialog
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
            act.message("Download", Collect.summary(plan.take.size, plan))
            after()
        }.show()
    }

    // ---------------------------------------------------------------------------------------------------------
    // Takeout import

    fun importDialog() {
        android.app.AlertDialog.Builder(act)
            .setTitle("Import subscriptions")
            .setMessage(
                "Your YouTube subscriptions can be imported once from a Google Takeout file (no sign-in in this app):\n\n" +
                    "1. On takeout.google.com tap \"Deselect all\".\n" +
                    "2. Tick \"YouTube and YouTube Music\".\n" +
                    "3. \"All YouTube data included\" → keep only \"subscriptions\".\n" +
                    "4. Next step → Create export, then download it on this phone.\n\n" +
                    "Then choose the .zip (or subscriptions.csv from inside it). Channels are added to Following; nothing " +
                    "is removed."
            )
            .setPositiveButton("Choose file…") { _, _ -> act.pickTakeout() }
            .setNegativeButton("Cancel", null)
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
                        act.message("Import", "No channels found in that file. Pick the Takeout .zip or its subscriptions.csv.")
                        return@fold
                    }
                    val now = System.currentTimeMillis()
                    val (merged, added) = Follows.merge(Store.follows(), list.map { it.copy(added = now) })
                    Store.setFollows(merged)
                    act.message("Import", "Found ${list.size} channels, $added new. Following ${merged.size} channels now.")
                    if (shown && top === feedPage) feedPage.onShow() else feedPage.updateInfo()
                }, { e -> act.message("Import", "Couldn't read the file: ${e.message ?: e.javaClass.simpleName}") })
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
        act.toast(if (Account.expired) "Members-only — account expired: import new cookies in ⋮ → Account" else Account.SIGNED_OUT)
        return true
    }

    /** A channel's members-only videos (its UUMO… playlist), asked with the account (members-tab purpose only). */
    inner class MembersPage(private val channelId: String, private val name: String) : ListPage() {
        init {
            head.addView(act.text("Members-only · ${name.ifEmpty { "Channel" }}", 18f, C.TEXT, bold = true, lines = 2).apply { setPadding(0, act.dp(4), 0, 0) })
            head.addView(act.text("Asked with your account (only this list and members-only videos use it)", 12f, C.BLUE, lines = 2)
                .apply { setPadding(0, act.dp(2), 0, act.dp(6)) })
        }

        private fun mark(p: Page) = Page(p.items.map { if (it.isVideo) it.copy(members = true) else it }, p.continuation)

        override fun firstLoad() {
            run(true, { g -> AuthHttp.membersPage(act, calls, g, channelId, null) }) { o ->
                loadedOnce = true
                items.clear()
                append(mark(Browse.page(o)))
                setStatus(if (items.isEmpty()) Browse.alert(o) ?: "No members-only videos here (or this account isn't a member)" else null)
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
        open fun selLabel(n: Int) = "Download $n"
        open val selMenu get() = true
        open fun selGo() { bulk(selectedItems(), albumHint, music = musicDefault) { endSelection() } }
        /** Hold menu of a channel row offers "Select" (Following). */
        open val channelSelect get() = false
        val adapter = ItemAdapter(this)
        val head = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(16), 0, act.dp(16), act.dp(4)) }
        private val status = act.text("", 13f, C.DIM, lines = 4).apply { setPadding(act.dp(16), act.dp(4), act.dp(16), act.dp(4)); visibility = View.GONE }
        val list = ListView(act)
        private val more = act.pill("Load more") { if (loadedOnce) loadMore() else retry() }
        private val footNote = act.text("", 13f, C.LABEL).apply { gravity = Gravity.CENTER }
        val root = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        init {
            val foot = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(act.dp(16), act.dp(12), act.dp(16), act.dp(24))
                addView(more, lpL(WRAP, WRAP))
                addView(footNote, lpL(MATCH, WRAP))
            }
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
        }

        open val canRetry get() = !loadedOnce

        /** Album folder hint for audio from this page (a playlist's title). */
        open val albumHint: String? get() = null

        /** Music page (album, Topic channel, Music tab): bulk downloads default to audio, caches go to Music. */
        open val musicDefault: Boolean get() = false

        open fun onShow() {
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

        fun refreshFooter() {
            when {
                loading -> { more.visibility = View.GONE; footNote.text = "Loading…"; footNote.visibility = View.VISIBLE }
                loadedOnce && token != null -> { more.text = "Load more"; more.visibility = View.VISIBLE; footNote.visibility = View.GONE }
                !loadedOnce && canRetry -> { more.text = "Retry"; more.visibility = View.VISIBLE; footNote.visibility = View.GONE }
                else -> { more.visibility = View.GONE; footNote.visibility = View.GONE }
            }
        }

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
                IKind.PLAYLIST -> if (i.id.startsWith("local:")) push(LocalListPage(i.id.removePrefix("local:"))) else push(PlaylistPage(i.id, i.title))
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
                        if (channelSelect) "Select" to { startSelection(i) } else null,
                        "Open channel" to { push(ChannelPage(i.id, i.title)) },
                        "Download all music…" to { push(ChannelPage(i.id, i.title).apply { autoAll = "music" }) },
                        (if (followed) "Unfollow" else "Follow") to {
                            if (followed) UnfollowUi.unfollow(act, listOf(i.id to i.title)) { onFollowsChanged() }
                            else { Store.follow(i.id, i.title); act.toast("Following ${i.title}"); onFollowsChanged() }
                        },
                    ))
                }
                IKind.PLAYLIST -> act.menu(i.title, listOf(
                    "Open" to { push(PlaylistPage(i.id, i.title)) },
                    "Download all…" to { push(PlaylistPage(i.id, i.title).apply { auto = "download" }) },
                    "Cache all" to { push(PlaylistPage(i.id, i.title).apply { auto = "cache" }) },
                    "Listen all" to { push(PlaylistPage(i.id, i.title).apply { auto = "listen" }) },
                    "Add to list…" to { push(PlaylistPage(i.id, i.title).apply { auto = "list" }) },
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
        private val info = act.text("", 12f, C.LABEL, lines = 2)
        private val followingBtn = act.pill("Following") { push(FollowingPage()) }
        private var refreshing = false
        private var doneN = 0
        private var totalN = 0
        private var failedN = 0
        private var noWifi = false
        private var dirty = false
        private var renderPosted = false
        private var ff = Store.feedFilters
        private val filterFlow = FlowLayout(act).apply { setPadding(0, act.dp(2), 0, act.dp(4)) }
        private val note = act.text("", 12f, C.LABEL, lines = 3)

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, act.dp(6)) }
            row.addView(followingBtn)
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill("Import…") { importDialog() })
            row.addView(View(act), lpL(0, 1, 1f))
            row.addView(act.pill("Refresh") { refresh(true) })
            head.addView(row, lpL(MATCH, WRAP))
            head.addView(info, lpL(MATCH, WRAP))
            head.addView(filterFlow, lpL(MATCH, WRAP))
            head.addView(note, lpL(MATCH, WRAP))
            renderFilters()
        }

        private fun renderFilters() {
            filterFlow.removeAllViews()
            fun toggle(label: String, on: Boolean, set: (Boolean) -> FeedFilters) = filterFlow.addView(filterChip(label, on) { setFF(set(!on)) })
            toggle("Members only", ff.membersOnly) { ff.copy(membersOnly = it) }
            toggle("Hide Shorts", ff.hideShorts) { ff.copy(hideShorts = it) }
            toggle("Hide downloaded", ff.hideDownloaded) { ff.copy(hideDownloaded = it) }
            toggle("Music only", ff.musicOnly) { ff.copy(musicOnly = it) }
            val follows = Store.follows()
            val picked = ff.channels.count { id -> follows.any { it.id == id } }
            filterFlow.addView(filterChip(if (picked == 0) "All channels" else "$picked channel${if (picked == 1) "" else "s"}", picked > 0) {
                if (follows.isEmpty()) { act.toast("Not following any channel"); return@filterChip }
                checks("Channels (none = all)", follows.map { it.title }, follows.map { it.id in ff.channels }.toBooleanArray()) { b ->
                    setFF(ff.copy(channels = follows.filterIndexed { i, _ -> b[i] }.map { it.id }.toSet()))
                }
            })
            filterFlow.addView(filterChip(ff.age.label, ff.age != FAge.ALL) {
                act.menu("Age", FAge.values().map { a -> (if (a == ff.age) "✓ " else "   ") + a.label to { setFF(ff.copy(age = a)) } })
            })
            filterFlow.addView(filterChip(ff.sort.label, ff.sort != FSort.NEWEST) {
                act.menu("Sort", FSort.values().map { o -> (if (o == ff.sort) "✓ " else "   ") + o.label to { setFF(ff.copy(sort = o)) } })
            })
            if (!ff.isDefault) filterFlow.addView(act.pill("Reset") { setFF(FeedFilters()) })
            note.text = if (ff.membersOnly) "Members-only uploads aren't in channel RSS: they're found on Refresh, from each channel's public " +
                "members list (no account needed), so this may lag until you tap Refresh." else ""
            note.visibility = if (ff.membersOnly) View.VISIBLE else View.GONE
        }

        private fun setFF(n: FeedFilters) { ff = n; Store.feedFilters = n; renderFilters(); showCache() }

        override val canRetry get() = false

        override fun firstLoad() { loadedOnce = true; onShow() }

        /** Show the cached feed at once; fetch only channels whose RSS is older than 30 min (or newly followed). */
        override fun onShow() {
            loadedOnce = true
            showCache()
            refresh(false)
            refreshFooter()
        }

        private fun showCache() {
            items.clear()
            val all = Store.loadFeed().items
            items += ff.apply(all, System.currentTimeMillis()) { Store.downloadedAny(it) }
            adapter.notifyDataSetChanged()
            setStatus(if (all.isNotEmpty() && items.isEmpty()) "Nothing in the feed passes the filters" else null)
            updateInfo()
        }

        fun updateInfo() {
            val n = Store.follows().size
            followingBtn.text = "Following ($n)"
            info.text = when {
                n == 0 -> "Follow channels (open one from Search → Channels and tap Follow), or import your YouTube " +
                    "subscriptions with Import…. Their newest videos show up here."
                refreshing -> "Refreshing… $doneN of $totalN channels"
                else -> {
                    val last = Store.loadFeed().lastRefresh
                    (if (last > 0) "Updated ${Ago.text(last, System.currentTimeMillis())}" else "Not loaded yet") +
                        (if (noWifi) " · not on Wi-Fi, showing the saved feed" else "") +
                        (if (failedN > 0) " · $failedN channels failed" else "")
                }
            }
        }

        private var again = false

        fun refresh(force: Boolean) {
            if (refreshing) { again = true; return } // e.g. an import finished meanwhile: fetch the new ones after this round
            val follows = Store.follows()
            val due = Store.loadFeed().due(follows, System.currentTimeMillis(), force)
            if (due.isEmpty()) { updateInfo(); return }
            noWifi = false
            try { Net.network(act, Store.wifiOnly) } catch (e: Exception) {
                // checked once here instead of failing every channel; the saved feed stays on screen
                noWifi = e is NoWifi
                failedN = 0
                updateInfo()
                if (e !is NoWifi) act.toast("No network")
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
                    if (force && Store.noMembersUntil(f.id) < now0) members = try {
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
                        if (again) { again = false; refresh(false) }
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
            again = false
            if (refreshing) { refreshing = false; persist(); updateInfo() }
        }

        override fun onFollowsChanged() { renderFilters(); showCache() }
    }

    inner class FollowingPage : ListPage() {
        /** Filter for long lists (Takeout import, subscriptions sync): name, accents and case ignored. */
        private val filter: EditText by lazy {
            EditText(act).apply {
                hint = "Filter channels"
                setHintTextColor(C.LABEL); setTextColor(C.TEXT); textSize = 14f; isSingleLine = true
                background = act.outline(act.dpf(18))
                setPadding(act.dp(14), act.dp(8), act.dp(14), act.dp(8))
            }
        }

        init {
            head.addView(act.text("Following", 18f, C.TEXT, bold = true).apply { setPadding(0, act.dp(4), 0, act.dp(2)) })
            head.addView(act.text("Tap to open; long-press for Unfollow / Select.", 12f, C.LABEL))
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(6), 0, act.dp(4)) }
            row.addView(filter, lpL(0, WRAP, 1f))
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill("Select") { items.firstOrNull()?.let { startSelection(it) } ?: act.toast("Nothing to select") })
            row.addView(View(act), lpL(act.dp(8), 1))
            row.addView(act.pill("Import…") { importDialog() })
            head.addView(row, lpL(MATCH, WRAP))
            filter.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) { fill() }
            })
        }

        override fun selectable(i: Item) = i.kind == IKind.CHANNEL
        override fun selLabel(n: Int) = "Unfollow $n"
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
                all.isEmpty() -> "Not following any channel yet."
                items.isEmpty() -> "No followed channel matches \"$q\"."
                q.isNotBlank() -> "${items.size} of ${all.size}"
                else -> null
            })
        }

        override fun onFollowsChanged() { fill(); feedPage.updateInfo() }
    }

    // ---- Search ----

    /** Small chip for a filter: amber outline + text when it isn't at its default. */
    private fun filterChip(label: String, active: Boolean, onClick: () -> Unit) = Chip(act, label) { onClick() }.apply { select(active) }

    /** Multi-choice picker; [done] gets the checked flags. */
    private fun checks(title: String, labels: List<String>, on: BooleanArray, done: (BooleanArray) -> Unit) {
        val st = on.copyOf()
        android.app.AlertDialog.Builder(act).setTitle(title)
            .setMultiChoiceItems(labels.toTypedArray(), st) { _, i, c -> st[i] = c }
            .setPositiveButton("Apply") { _, _ -> done(st) }
            .setNegativeButton("Cancel", null).show()
    }

    inner class SearchPage : ListPage() {
        private val box = EditText(act)
        private val types = listOf("Videos", "Channels", "Playlists")
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
                hint = "Search YouTube, or paste a channel / playlist link"
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
            row.addView(act.pill("Search", C.ACCENT, C.ACCENT) { submit() })
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
                    act.menu("Upload date", SDate.values().map { d -> (if (d == f.date) "✓ " else "   ") + d.label to { setF(f.copy(date = d)) } })
                })
                filterFlow.addView(filterChip(f.dur.label, f.dur != SDur.ANY) {
                    act.menu("Duration", SDur.values().map { d -> (if (d == f.dur) "✓ " else "   ") + d.label to { setF(f.copy(dur = d)) } })
                })
            }
            val sorts = if (f.videos) SSort.values().toList() else listOf(SSort.RELEVANCE, SSort.POPULAR)
            filterFlow.addView(filterChip("Sort: " + f.effSort.label, f.effSort != SSort.RELEVANCE) {
                act.menu("Sort by", sorts.map { s -> (if (s == f.effSort) "✓ " else "   ") + s.label to { setF(f.copy(sort = s)) } })
            })
            val feats = listOf(f.hideShorts, f.hideLive, f.membersOnly, f.musicOnly, f.hideDownloaded)
            val n = feats.count { it }
            filterFlow.addView(filterChip(if (n == 0) "Features" else "Features ($n)", n > 0) {
                checks("Features", listOf("Hide Shorts", "Hide live", "Members-only only", "Music only (\"- Topic\" channels)", "Hide downloaded"), feats.toBooleanArray()) { b ->
                    setF(f.copy(hideShorts = b[0], hideLive = b[1], membersOnly = b[2], musicOnly = b[3], hideDownloaded = b[4]))
                }
            })
            if (!f.isDefault) filterFlow.addView(act.pill("Reset") { setF(SearchFilters(type = f.type)) })
            val lines = ArrayList<String>()
            if (f.membersOnly) lines += "Search is always anonymous, and YouTube leaves members-only videos out of it, so this is usually empty: open a channel's Members list instead."
            if (f.effSort == SSort.NEWEST) lines += "YouTube no longer sorts search by date: the loaded results are sorted here."
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
            if (server) { cancelNet(); doSearch() } else { reapply(); scanIfNeeded(newRound = true) }
        }

        override val canRetry get() = query.isNotEmpty() && !loadedOnce
        override fun firstLoad() { if (query.isNotEmpty()) doSearch() }

        private fun setType(i: Int) { if (i != f.type) setF(f.copy(type = i)) }

        fun searchFor(q: String) { box.setText(q); query = q; cancelNet(); doSearch() }

        private fun submit() {
            act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
            if (box.text.isBlank()) return
            when (val t = Links.classify(box.text.toString(), bareId = false)) {
                is Target.Query -> { query = t.text; cancelNet(); doSearch() }
                Target.None -> setStatus("That link isn't a YouTube video, channel or playlist", true)
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
                raw.isEmpty() -> "No results"
                items.isEmpty() -> "None of the ${raw.size} loaded results pass the filters" + (if (token != null) " · Load more scans further" else "")
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
            adapter.notifyDataSetChanged()
            list.setSelection(0)
            val q = query
            val params = f.sp()
            pagesThisRound = 1; passedThisRound = 0
            run(true, { g -> WebApi.search(act, calls, g, q, params) }) { o ->
                loadedOnce = true
                region = o.optString("_gl").ifEmpty { null }
                addPage(Browse.page(o))
            }
        }
    }

    // ---- Channel ----

    inner class ChannelPage(private val channelId: String, private var name: String) : ListPage() {
        private val titleV = act.text(name.ifEmpty { "Channel" }, 18f, C.TEXT, bold = true, lines = 2)
        private val subV = act.text("", 12f, C.LABEL)
        private val followBtn = act.pill("Follow", C.ACCENT, C.ACCENT) { toggleFollow() }
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
        private val stopBtn = act.pill("Stop") { collecting = false; cancelNet(); showCollect(null) }
        @Volatile private var collecting = false

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, 0) }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
            col.addView(titleV); col.addView(subV)
            row.addView(col, lpL(0, WRAP, 1f))
            row.addView(followBtn)
            head.addView(row, lpL(MATCH, WRAP))
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(10), 0, act.dp(4)) }
            for ((k, label) in listOf("music" to "Music", "videos" to "Videos", "shorts" to "Shorts", "playlists" to "Playlists")) {
                val c = Chip(act, label) { switchTab(k) }
                tabChips[k] = c; flow.addView(c)
            }
            flow.addView(act.pill("Download all…", C.ACCENT, C.ACCENT) { downloadAllMenu() })
            // members-only videos of this channel: only offered with an account (the list is asked with it)
            if (Account.usable) flow.addView(act.pill("Members", C.BLUE, C.BLUE) { push(MembersPage(channelId, name)) })
            head.addView(flow, lpL(MATCH, WRAP))
            val crow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
            crow.addView(collectV, lpL(0, WRAP, 1f)); crow.addView(stopBtn)
            head.addView(crow, lpL(MATCH, WRAP))
            showCollect(null)
            updateHead()
        }

        private fun updateHead() {
            titleV.text = name.ifEmpty { "Channel" }
            val f = Store.isFollowed(channelId)
            followBtn.text = if (f) "Following" else "Follow"
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
            else { Store.follow(channelId, name); act.toast("Following ${name.ifEmpty { "channel" }}: its new videos show up in Feed") }
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
                setStatus(if (items.isEmpty()) empty?.let { "YouTube: $it" } ?: "Nothing here" else null)
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
                    a.songsPlaylist?.let { items += Item(IKind.PLAYLIST, it, "All songs", channel = who(), count = "Songs") }
                    a.videosPlaylist?.let { items += Item(IKind.PLAYLIST, it, "Music videos", channel = who(), count = "Videos") }
                    addReleases(first)
                }
                token = if (cursors?.any { !it.done } == true) "music" else null
                adapter.notifyDataSetChanged()
                refreshFooter()
                setStatus(if (items.isEmpty()) "YouTube Music lists no releases for this channel" else null)
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
            if (music) opts += "All music (every album & single)" to { collectAll("music") }
            if (!topic) opts += "All videos" to { collectAll("videos") }
            if (music && !topic) opts += "Everything (music + videos)" to { collectAll("all") }
            val dl = downloadedFor(items.filter { it.isVideo }.mapTo(HashSet()) { it.id }, name)
            if (dl.isNotEmpty()) opts += "Erase downloaded (${dl.size})…" to { eraseDownloaded(dl, name.ifEmpty { "this channel" }) }
            if (opts.size == 1) { opts[0].second(); return }
            act.menu("Download all from ${name.ifEmpty { "this channel" }}", opts)
        }

        /** Collect every list (progress + Stop), then one batch: each video once, album folder from its release. */
        private fun collectAll(what: String) {
            if (collecting) return
            collecting = true
            showCollect("Collecting…")
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
                                        main.post { showCollect("Collecting: ${releasesDone + 1} releases · $fresh new tracks") }
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
                        main.post { showCollect("Collecting videos…") }
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
                    val label = when (what) { "music" -> "All music"; "videos" -> "All videos"; else -> "Everything" }
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
                main.post { showCollect("Collecting videos… ${out.size}") }
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
            setStatus("YouTube: $msg. Looking for channels with a similar name…")
            val q = name
            run(false, { g -> WebApi.search(act, calls, g, q, Clients.SEARCH_VIDEOS) }) { o ->
                val owners = Browse.page(o).items.filter { it.channelId != null && it.channelId != channelId && Browse.similarName(q, it.channel) }
                    .distinctBy { it.channelId }.map { Item(IKind.CHANNEL, it.channelId!!, it.channel, count = "Similar name, from video results") }
                items += owners
                adapter.notifyDataSetChanged()
                setStatus(if (owners.isEmpty()) "YouTube: $msg" else "YouTube: $msg. Did you mean:")
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
            adapter.notifyDataSetChanged()
            list.setSelection(0)
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
        private val titleV = act.text(name.ifEmpty { "Playlist" }, 18f, C.TEXT, bold = true, lines = 3)
        private val ownerV = act.text("", 13f, C.DIM)
        private val allBtn = act.pill("Download all", C.ACCENT, C.ACCENT) { downloadAll() }
        private val stopBtn = act.pill("Stop") { stopAll() }
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
            flow.addView(act.pill("Play all") { gatherThen { playAll(items, null, name) } })
            flow.addView(act.pill("Listen all") { gatherThen { playAll(items, PlayMode.LISTEN, name) } })
            flow.addView(act.pill("Cache all") { gatherThen { cacheItems(items, name, music, name) } })
            flow.addView(act.pill("Erase downloaded…") {
                gatherThen { eraseDownloaded(downloadedFor(items.filter { it.isVideo }.mapTo(HashSet()) { it.id }, null), name.ifEmpty { "this playlist" }) }
            })
            head.addView(flow, lpL(MATCH, WRAP))
            stopBtn.visibility = View.GONE
        }

        private var ownerName = ""

        override fun firstLoad() {
            run(true, { g -> WebApi.browse(act, calls, g, "VL$playlistId", null) }) { o ->
                Browse.alert(o)?.let { setStatus(it, true); return@run }
                val h = Browse.playlistHead(o, playlistId)
                if (h == null) { setStatus("This playlist can't be opened (private, or a Mix)", true); return@run }
                name = h.title; ownerName = h.owner; ownerId = h.ownerId; count = h.count
                region = o.optString("_gl").ifEmpty { null }
                titleV.text = name
                ownerV.text = listOf(h.owner, h.count).filter { it.isNotEmpty() }.joinToString(" · ")
                loadedOnce = true
                items.clear()
                append(Browse.page(o))
                setStatus(if (items.isEmpty()) "This playlist is empty" else null)
                updateProgress()
                when (auto) {
                    "download" -> downloadAll()
                    "cache" -> gatherThen { cacheItems(items, name, music, name) }
                    "listen" -> gatherThen { playAll(items, PlayMode.LISTEN, name) }
                    "list" -> gatherThen { Hold.addToList(act, items) }
                }
                auto = null
            }
        }

        private fun updateProgress() {
            val n = items.count { it.isVideo }
            progressV.text = if (gathering) "Loading all… $n videos" else if (token != null) "$n loaded" + (if (count.isNotEmpty()) " of $count" else "") else "$n videos"
            stopBtn.visibility = if (gathering) View.VISIBLE else View.GONE
            allBtn.visibility = if (gathering) View.GONE else View.VISIBLE
        }

        /** Load every continuation first (progress + Stop), then ask the options once and queue them all. */
        private fun downloadAll() = gatherThen { bulk(items, name, name, music) }

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

    /** Play all / Listen all: replaces the play queue and opens the player on it. */
    fun playAll(items: List<Item>, mode: PlayMode?, album: String?) {
        val e = items.filter { it.isVideo && !it.live }.map { PlayQueue.of(it, mode, album) }
        if (e.isEmpty()) { act.toast("Nothing to play"); return }
        Store.setQueue(PlayQueue().replace(e))
        PlayerActivity.queue(act)
    }

    // ---- Lists (local, "categorize") ----

    inner class ListsPage : ListPage() {
        private val queueBtn = act.pill("Play queue") { if (Store.queue().items.isEmpty()) act.toast("The play queue is empty") else PlayerActivity.queue(act) }

        init {
            val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, act.dp(4), 0, act.dp(6)) }
            row.addView(act.pill("New list…") { Hold.newList(act) { fill() } })
            row.addView(View(act), lpL(0, 1, 1f))
            row.addView(queueBtn)
            head.addView(row, lpL(MATCH, WRAP))
        }

        override val canRetry get() = false
        override fun firstLoad() { loadedOnce = true; fill() }
        override fun onShow() { loadedOnce = true; fill(); refreshFooter() }

        fun fill() {
            items.clear()
            Store.lists().forEach { items += Item(IKind.PLAYLIST, "local:" + it.id, it.name, count = "${it.items.size} videos", thumbId = it.items.firstOrNull()?.id) }
            adapter.notifyDataSetChanged()
            queueBtn.text = "Play queue (${Store.queue().items.size})"
            setStatus(if (items.isEmpty()) "No lists yet. Press & hold a video → Add to list…, or select several → ⋮ → Add to list…" else null)
        }

        override fun onLongTap(i: Item) {
            val l = Store.lists().firstOrNull { "local:" + it.id == i.id } ?: return
            act.menu(l.name, listOf(
                "Open" to { push(LocalListPage(l.id)) },
                "Play all" to { playAll(l.items, null, l.name) },
                "Listen all" to { playAll(l.items, PlayMode.LISTEN, l.name) },
                "Download all…" to { bulk(l.items, l.name) },
                "Cache all" to { cacheItems(l.items, l.name) },
                "Rename…" to { Hold.rename(act, l) { fill() } },
                "Delete list" to { Store.setLists(VideoLists.delete(Store.lists(), l.id)); fill() },
            ))
        }
    }

    inner class LocalListPage(private val listId: String) : ListPage() {
        private val titleV = act.text("", 18f, C.TEXT, bold = true, lines = 2)
        private fun list() = Store.lists().firstOrNull { it.id == listId }

        init {
            head.addView(titleV.apply { setPadding(0, act.dp(4), 0, act.dp(4)) })
            val flow = FlowLayout(act).apply { setPadding(0, act.dp(4), 0, act.dp(6)) }
            flow.addView(act.pill("Play all", C.ACCENT, C.ACCENT) { playAll(items, null, list()?.name) })
            flow.addView(act.pill("Listen all") { playAll(items, PlayMode.LISTEN, list()?.name) })
            flow.addView(act.pill("Download all…") { bulk(items, list()?.name) })
            flow.addView(act.pill("Cache all") { cacheItems(items, list()?.name) })
            head.addView(flow, lpL(MATCH, WRAP))
        }

        override val canRetry get() = false
        override val albumHint: String? get() = list()?.name
        override fun firstLoad() { loadedOnce = true; fill() }
        override fun onShow() { loadedOnce = true; fill(); refreshFooter() }

        private fun fill() {
            val l = list()
            titleV.text = l?.name ?: "List deleted"
            items.clear(); l?.items?.let { items += it }
            adapter.notifyDataSetChanged()
            setStatus(if (items.isEmpty()) "Empty list" else null)
        }

        override fun videoExtras(i: Item) = listOf<Pair<String, () -> Unit>>(
            "Remove from this list" to { Store.setLists(VideoLists.removeItem(Store.lists(), listId, i.id)); fill() })
    }

    // ---------------------------------------------------------------------------------------------------------
    // rows

    inner class ItemAdapter(private val page: ListPage) : BaseAdapter() {
        override fun getCount() = page.items.size
        override fun getItem(p: Int): Any = page.items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (page.items[p].kind == IKind.CHANNEL) 1 else 0

        override fun getView(p: Int, cv: View?, parent: ViewGroup): View {
            val i = page.items[p]
            val h = (cv?.tag as? Row) ?: Row(i.kind == IKind.CHANNEL)
            h.bind(i, page)
            return h.view
        }
    }

    private inner class Row(channelRow: Boolean) {
        val view = LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(act.dp(12), act.dp(6), act.dp(12), act.dp(6))
            layoutParams = AbsListView.LayoutParams(MATCH, WRAP)
        }
        private val thumbW = act.dp(128)
        private val thumb = if (channelRow) null else RoundImage(act)
        private val badge = act.text("", 11f, C.TEXT, bold = true).apply {
            background = roundRect(0xCC000000.toInt(), act.dpf(4)); setPadding(act.dp(4), act.dp(1), act.dp(4), act.dp(1))
        }
        private val avatar = act.text("", 18f, C.DIM, bold = true).apply {
            gravity = Gravity.CENTER; background = act.outline(act.dpf(22))
        }
        private val title = act.text("", 14f, C.TEXT, bold = true, lines = 2)
        private val channel = act.text("", 12f, C.DIM)
        private val meta = act.text("", 12f, C.LABEL)
        private val mark = act.text("", 12f, C.GREEN)
        private val check = act.text("", 18f, C.ACCENT).apply { gravity = Gravity.CENTER }
        private var thumbBox: FrameLayout? = null
        private val playMark = act.text("▶", 14f, C.TEXT).apply { gravity = Gravity.CENTER; background = oval(0x99000000.toInt()) }
        private val membersMark = act.membersBadge()
        private val normalBg = ripple(null)
        private val selBg = ripple(act.outline(act.dpf(10), C.ACCENT))

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
                view.addView(avatar, lpL(act.dp(44), act.dp(44)).apply { marginStart = act.dp(4); marginEnd = act.dp(4) })
            }
            val col = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(12), 0, act.dp(4), 0) }
            col.addView(title, lpL(MATCH, WRAP))
            col.addView(channel, lpL(MATCH, WRAP).apply { topMargin = act.dp(2) })
            col.addView(meta, lpL(MATCH, WRAP))
            col.addView(mark, lpL(MATCH, WRAP))
            view.addView(col, lpL(0, WRAP, 1f))
            view.addView(check, lpL(act.dp(28), WRAP))
            view.tag = this
        }

        fun bind(i: Item, page: ListPage) {
            title.text = i.title.ifEmpty { i.id }
            when (i.kind) {
                IKind.CHANNEL -> {
                    avatar.text = i.title.trim().take(1).uppercase()
                    channel.text = i.handle
                    meta.text = i.count
                    channel.setOnClickListener(null); channel.isClickable = false
                }
                IKind.PLAYLIST -> {
                    channel.text = i.channel
                    meta.text = if (i.id.startsWith("local:")) "Local list" else "Playlist"
                    badge.text = i.count
                }
                else -> {
                    channel.text = i.channel
                    val age = i.age.ifEmpty { Ago.text(i.published, System.currentTimeMillis()) }
                    meta.text = listOf(if (i.kind == IKind.SHORT) "Short" else "", i.views, age).filter { it.isNotEmpty() }.joinToString(" · ")
                    badge.text = if (i.live) "LIVE" else Text.duration(i.durationSec)
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
                mark.text = when { queued -> "In the download queue"; saved -> "✓ Downloaded"; else -> "" }
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
                check.contentDescription = "Listen"
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
        chipRow(kindRow, listOf("Audio", "Video (MP4)"), { (it == 0) == (kind == Kind.AUDIO) }) { kind = if (it == 0) Kind.AUDIO else Kind.VIDEO; showKind() }
        col.addView(kindRow)

        audioBox.addView(label("Format"))
        val fmtRow = FlowLayout(act)
        chipRow(fmtRow, AudioFmt.values().map { it.label }, { AudioFmt.values()[it] == fmt }) { fmt = AudioFmt.values()[it] }
        audioBox.addView(fmtRow)
        audioBox.addView(label("Quality"))
        val qRow = FlowLayout(act)
        chipRow(qRow, listOf("Best", "Small"), { AudioQ.values()[it] == q }) { q = AudioQ.values()[it] }
        audioBox.addView(qRow)
        col.addView(audioBox)

        videoBox.addView(label("Best quality up to"))
        val hs = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
        val hRow = FlowLayout(act)
        chipRow(hRow, hs.map { "${it}p" }, { hs[it] == height || height !in hs && it == hs.indexOfFirst { h -> h <= height } }) { height = hs[it] }
        videoBox.addView(hRow)
        videoBox.addView(act.text("Each video gets its best H.264 stream up to this height (lower if that's all it has).", 12f, C.LABEL, lines = 3)
            .apply { setPadding(0, act.dp(8), 0, 0) })
        col.addView(videoBox)
        col.addView(act.text("Already downloaded or queued videos are skipped.", 12f, C.LABEL, lines = 2).apply { setPadding(0, act.dp(12), 0, act.dp(4)) })
        showKind()

        val scroll = android.widget.ScrollView(act).apply { addView(col) }
        android.app.AlertDialog.Builder(act)
            .setTitle("Download $n video" + if (n == 1) "" else "s")
            .setView(scroll)
            .setPositiveButton("Download") { _, _ ->
                Store.kind = kind; Store.audioFmt = fmt; Store.audioQ = q
                if (kind == Kind.VIDEO) Store.videoP = height
                onGo(kind, fmt, q, height)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
