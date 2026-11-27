package app.plain.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import app.plain.lang.tr

/** Pre-3.4 Spotify sign-in (files/spotify_account.bin + its Keystore key): deleted once — there is no sign-in any more. */
object SpotifyLegacy {
    fun cleanup(ctx: Context) {
        val f = File(ctx.applicationContext.filesDir, "spotify_account.bin")
        if (!f.exists()) return
        f.delete()
        try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("plain_spotify_account") } catch (_: Exception) {}
    }
}

/**
 * Spotify playlists without an account (3.4), only ever started by the user:
 *  1. **Find** — the user types a profile link / username (or a playlist link); the profile's public playlists are
 *     read from its public page ([SpotifyWeb]) and the user picks which to convert;
 *  2. **Sync** — each chosen playlist's tracks come from its public embed page (one small HTTP request; up to 100
 *     tracks), longer playlists from the full public page;
 *  3. every track without a match is searched on YouTube Music (songs, then videos) and matched by [SpMatcher]
 *     (3 at a time; saved as it goes, so a stopped sync resumes);
 *  4. (3.10) every saved playlist downloads by itself: the songs not downloaded or queued yet are queued as audio,
 *     one batch per playlist, and each playlist is linked to its Music-tab playlist ([autoDownload]).
 * One run at a time, in a background thread; progress goes to [listeners] on the main thread. Honours "Wi-Fi only".
 */
object SpotifySync {
    private val main = Handler(Looper.getMainLooper())
    private val calls = Calls()
    private lateinit var file: AtomicFile
    @Volatile private var cache: SpData? = null

    @Volatile var running = false
        private set
    @Volatile var progress: String? = null
        private set
    @Volatile var lastError: String? = null
        private set
    /** What the last run queued for download (shown under the Spotify page's buttons). */
    @Volatile var lastNote: String? = null
        private set
    val listeners = ArrayList<() -> Unit>()

    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = AtomicFile(File(ctx.applicationContext.filesDir, "spotify.json"))
        SpotifyLegacy.cleanup(ctx)
    }

    @Synchronized fun data(): SpData = cache ?: (try { SpData.decode(String(file.readFully(), Charsets.UTF_8)) } catch (_: Exception) { SpData() }).also { cache = it }

    @Synchronized private fun save(d: SpData) {
        cache = d
        val out = file.startWrite()
        try { out.write(d.encode().toByteArray(Charsets.UTF_8)); file.finishWrite(out) } catch (_: Exception) { file.failWrite(out) }
    }

    @Synchronized private fun update(f: (SpData) -> SpData) = save(f(data()))

    private fun notifyUi() = main.post { listeners.toList().forEach { it() } }

    private fun say(s: String?) { progress = s; notifyUi() }

    fun stop() { calls.cancelAll() }

    /** Forget the profile, the playlists and the matches (songs already downloaded stay). */
    fun forget() {
        stop()
        save(SpData())
        lastError = null
        notifyUi()
    }

    /** What Find found: a profile's public playlists, or the one playlist of a pasted link ([profile] empty). */
    class Found(val profile: String, val name: String, val lists: List<SpotifyPublic.Ref>)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun failText(e: Exception): String = when (e) {
        is NoWifi -> e.message ?: tr("Wi-Fi only: no Wi-Fi", "Yalnızca Wi-Fi: Wi-Fi yok")
        is NotFound -> tr("Spotify says it doesn't exist (or it's private)", "Spotify böyle bir şey olmadığını söylüyor (ya da gizli)")
        is java.net.UnknownHostException -> tr("No internet connection", "İnternet bağlantısı yok")
        else -> e.message ?: e.javaClass.simpleName
    }

    /**
     * Find: read what [input] names. A profile -> its public playlists (the profile page, then its "Show all" page);
     * a playlist link -> that playlist. [done] runs on the main thread (not on failure: see [lastError]).
     */
    fun find(act: MainActivity, input: String, done: (Found) -> Unit) {
        if (running) return
        val app = act.applicationContext
        init(app)
        val t = SpotifyPublic.target(input)
        if (t == null) {
            lastError = tr("Type a Spotify profile link (Profile → ⋯ → Share → Copy link), a username, or a playlist link",
                "Bir Spotify profil bağlantısı (Profil → ⋯ → Paylaş → Bağlantıyı kopyala), kullanıcı adı ya da çalma listesi bağlantısı yazın")
            notifyUi(); return
        }
        running = true; lastError = null
        say(tr("Looking it up on Spotify…", "Spotify'da aranıyor…"))
        val g = calls.gen
        val cancelled = { calls.gen != g }
        Thread {
            try {
                Net.network(app, Store.wifiOnly) // the page reader can't be bound to Wi-Fi: check first
                val f = when (t) {
                    is SpotifyPublic.Target.Profile -> {
                        say(tr("Opening the profile ${t.id}…", "${t.id} profili açılıyor…"))
                        val p1 = SpotifyPublic.profile(SpotifyWeb.read(act, "https://open.spotify.com/user/${enc(t.id)}", SpotifyWeb.PROFILE_JS, cancelled))
                        if (!p1.found) throw IOException(tr(
                            "No Spotify profile \"${t.id}\". Use the profile link (Profile → ⋯ → Share → Copy link): the name shown on a profile isn't always its username.",
                            "\"${t.id}\" adında Spotify profili yok. Profil bağlantısını kullanın (Profil → ⋯ → Paylaş → Bağlantıyı kopyala): profilde görünen ad her zaman kullanıcı adı değildir."))
                        var lists = p1.lists
                        if (p1.showAll) {
                            say(tr("Reading all public playlists of ${p1.name}…", "${p1.name} adlı kişinin tüm herkese açık listeleri okunuyor…"))
                            val p2 = SpotifyPublic.profile(SpotifyWeb.read(act, "https://open.spotify.com/user/${enc(t.id)}/playlists", SpotifyWeb.PROFILE_JS, cancelled))
                            if (p2.found && p2.lists.size >= lists.size) lists = p2.lists
                        }
                        Found(t.id, p1.name.ifEmpty { t.id }, lists)
                    }
                    is SpotifyPublic.Target.Playlist -> {
                        val e = SpotifyPublic.embed(String(WebApi.get(app, calls, g, "https://open.spotify.com/embed/playlist/${enc(t.id)}", Clients.WEB_UA), Charsets.UTF_8))
                            ?: throw IOException(tr("Couldn't read that playlist", "Bu çalma listesi okunamadı"))
                        Found("", "", listOf(SpotifyPublic.Ref(t.id, e.name.ifEmpty { t.id })))
                    }
                }
                main.post { done(f) }
            } catch (_: Cancelled) {
            } catch (e: Exception) {
                lastError = failText(e)
            } finally {
                running = false
                say(null)
            }
        }.start()
    }

    /**
     * The user's pick from Find. A profile's pick replaces the list (playlists added by link stay when picked again);
     * a playlist link adds that one. Playlists already synced keep their songs until the next Sync.
     */
    fun choose(found: Found, picked: List<SpotifyPublic.Ref>) {
        update { d ->
            val keep = d.playlists.associateBy { it.id }
            val fresh = picked.map { r -> keep[r.id]?.copy(name = r.name) ?: SpPlaylist(r.id, r.name, found.name, "", 0, emptyList()) }
            if (found.profile.isEmpty()) d.copy(playlists = d.playlists + fresh.filter { it.id !in keep })
            else d.copy(profile = found.profile, user = found.name, playlists = fresh)
        }
        notifyUi()
    }

    /** Remove one converted playlist from the list (its songs stay downloaded / cached). */
    fun remove(id: String) {
        update { d -> d.copy(playlists = d.playlists.filter { it.id != id }) }
        notifyUi()
    }

    /** Sync: read the chosen playlists again, then match new songs. [onlyMatch] skips Spotify (Try again on not-found songs). */
    fun start(act: MainActivity, onlyMatch: Boolean = false, retryMissing: Boolean = false) {
        if (running) return
        val app = act.applicationContext
        init(app)
        running = true; lastError = null; lastNote = null
        say(tr("Starting…", "Başlıyor…"))
        val g = calls.gen
        Thread {
            try {
                if (retryMissing) update { d -> d.copy(matches = d.matches.filterValues { it.videoId != null || it.rejected.isNotEmpty() }) }
                val failed = if (onlyMatch) 0 else readPlaylists(act, g)
                matchAll(app, g)
                autoDownload(app)
                if (failed > 0) lastError = tr("$failed playlist(s) couldn't be read (private or removed?): kept as last synced",
                    "$failed çalma listesi okunamadı (gizli ya da kaldırılmış olabilir): son eşitlenen hâli tutuldu")
                say(null)
            } catch (_: Cancelled) {
                say(null)
            } catch (e: Exception) {
                lastError = failText(e); say(null)
            } finally {
                running = false
                notifyUi()
            }
        }.start()
    }

    /** One playlist's name, owner and tracks: the embed page, or for a long playlist its full page. */
    private fun readOne(act: MainActivity, g: Int, id: String): SpotifyPublic.Page {
        val e = SpotifyPublic.embed(String(WebApi.get(act.applicationContext, calls, g, "https://open.spotify.com/embed/playlist/${enc(id)}", Clients.WEB_UA), Charsets.UTF_8))
            ?: throw IOException("not a playlist page")
        if (e.tracks.size < SpotifyPublic.EMBED_MAX) return SpotifyPublic.Page(e.name, e.owner, e.tracks.size, e.tracks)
        val full = try { SpotifyPublic.page(SpotifyWeb.read(act, "https://open.spotify.com/playlist/${enc(id)}", SpotifyWeb.PLAYLIST_JS, { calls.gen != g }, 300_000)) }
            catch (c: Cancelled) { throw c } catch (_: Exception) { null }
        return if (full != null && full.tracks.size > e.tracks.size) SpotifyPublic.Page(e.name.ifEmpty { full.name }, e.owner.ifEmpty { full.owner }, full.total, full.tracks)
            else SpotifyPublic.Page(e.name, e.owner, e.tracks.size, e.tracks)
    }

    /** Returns how many playlists couldn't be read (they keep their last synced songs). */
    private fun readPlaylists(act: MainActivity, g: Int): Int {
        Net.network(act.applicationContext, Store.wifiOnly)
        val d = data()
        val tracks = HashMap(d.tracks)
        val lists = ArrayList<SpPlaylist>()
        var failed = 0
        d.playlists.forEachIndexed { i, p ->
            calls.check(g)
            if (p.liked) { lists += p; return@forEachIndexed } // pre-3.4 Liked Songs: private, kept as last synced
            say(tr("Reading playlist ${i + 1} of ${d.playlists.size}: ${p.name}", "Çalma listesi ${i + 1} / ${d.playlists.size}: ${p.name}"))
            val got = try { readOne(act, g, p.id) } catch (c: Cancelled) { throw c } catch (w: NoWifi) { throw w } catch (_: Exception) { null }
            if (got == null) { failed++; lists += p; return@forEachIndexed }
            got.tracks.forEach { tracks[it.id] = it }
            val ids = got.tracks.map { it.id }
            lists += SpPlaylist(p.id, got.name.ifEmpty { p.name }, got.owner.ifEmpty { p.owner }, SpotifyPublic.snapshot(ids), ids.size, ids)
        }
        // only tracks still in some playlist are kept
        val used = lists.flatMapTo(HashSet()) { it.trackIds }
        update { x -> x.copy(synced = System.currentTimeMillis(), playlists = lists,
            tracks = tracks.filterKeys { it in used }, matches = x.matches.filterKeys { it in used }) }
        return failed
    }

    private fun matchAll(ctx: Context, g: Int) {
        val d = data()
        val todo = d.playlists.flatMap { it.trackIds }.distinct().filter { !d.matches.containsKey(it) }.mapNotNull { d.tracks[it] }
        if (todo.isEmpty()) return
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        val dirty = AtomicInteger()
        try {
            val futures = todo.map { t ->
                pool.submit {
                    calls.check(g)
                    val m = find(ctx, g, t, emptyList())
                    synchronized(this) { cache = data().let { it.copy(matches = it.matches + (t.id to m)) } }
                    val n = done.incrementAndGet()
                    if (dirty.incrementAndGet() >= 15) { dirty.set(0); save(data()) }
                    say(tr("Finding songs on YouTube… $n of ${todo.size}", "Şarkılar YouTube'da aranıyor… $n / ${todo.size}"))
                }
            }
            for (f in futures) try { f.get() } catch (e: java.util.concurrent.ExecutionException) { throw (e.cause ?: e) }
        } finally {
            pool.shutdownNow()
            save(data())
        }
    }

    /** YouTube Music songs first, then videos; [rejected] videos never come back. */
    private fun find(ctx: Context, g: Int, t: SpTrack, rejected: List<String>): SpMatch {
        for (filter in listOf(RemixSearch.SONGS, RemixSearch.VIDEOS)) {
            val c = try { RemixSearch.parse(WebApi.remixSearch(ctx, calls, g, t.query, filter)) } catch (e: Cancelled) { throw e } catch (e: NoWifi) { throw e } catch (_: Exception) { emptyList() }
            SpMatcher.pick(t, c, rejected)?.let { (song, score) -> return SpMatcher.match(song, score, rejected) }
        }
        return SpMatch(null, rejected = rejected)
    }

    /** "Not this one": that video is rejected for the track and the next best is looked up. */
    fun reject(ctx: Context, trackId: String, videoId: String) {
        val app = ctx.applicationContext
        val t = data().tracks[trackId] ?: return
        val rej = (data().matches[trackId]?.rejected.orEmpty() + videoId).distinct()
        update { d -> d.copy(matches = d.matches + (trackId to SpMatch(null, rejected = rej))) }
        notifyUi()
        if (running) return
        running = true
        val g = calls.gen
        Thread {
            try {
                say(tr("Looking for another version…", "Başka bir sürüm aranıyor…"))
                val m = find(app, g, t, rej)
                update { d -> d.copy(matches = d.matches + (trackId to m)) }
                autoDownload(app)
            } catch (_: Exception) {
            } finally { running = false; say(null) }
        }.start()
    }

    /**
     * Saved = downloaded (3.10): every saved playlist's found songs that aren't downloaded (audio or video) or queued
     * yet are queued as audio in the user's last audio format / quality, one batch per playlist (named after it), and
     * each playlist gets its Music-tab playlist link, so it shows up in the library as the songs finish. Runs at the
     * end of a Sync the user started, so nothing is fetched unasked. Off Wi-Fi (with "Wi-Fi only") the songs stay
     * queued and start when the app is next opened on Wi-Fi.
     */
    private fun autoDownload(ctx: Context) {
        Store.init(ctx)
        val d = data()
        val taken = HashSet<String>()
        var queued = 0
        for (p in d.playlists) {
            val its = d.items(p)
            if (its.isEmpty()) continue
            PlaylistLinks.remember(ctx, "spotify:" + p.id, p.name, its)
            val active = Store.activeIds()
            val plan = Collect.plan(listOf(p.name to its)) { id -> id in taken || id in active || Store.downloadedAny(id) }
            if (plan.take.isEmpty()) continue
            val baseId = Store.newId()
            val batch = if (plan.take.size > 1) baseId else 0L
            Store.addAll(plan.take.mapIndexed { i, t ->
                taken += t.item.id
                Job(
                    id = baseId + i, videoId = t.item.id, title = t.item.title, channel = "", durationSec = t.item.durationSec,
                    kind = Kind.AUDIO, audioFmt = Store.audioFmt, audioQ = Store.audioQ, height = Store.videoP, codec = VCodec.AVC,
                    album = Albums.pick(null, false, t.album), batch = batch, batchTitle = p.name,
                )
            })
            queued += plan.take.size
        }
        if (queued == 0) return
        lastNote = tr("Downloading $queued new song(s) from your saved playlists: they appear in the Music tab as they finish.",
            "Kayıtlı listelerinizden $queued yeni şarkı indiriliyor: bittikçe Müzik sekmesinde görünürler.")
        val wifi = !Store.wifiOnly || runCatching { Net.network(ctx, true) }.isSuccess
        if (wifi) main.post { try { DownloadService.start(ctx) } catch (_: Exception) {} } // not allowed from the background: starts on next open
        notifyUi()
    }
}
