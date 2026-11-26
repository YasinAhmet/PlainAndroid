package app.plain.download

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.AtomicFile
import java.io.File

/** Settings in SharedPreferences, download history in files/history.json. Everything local and private. */
object Store {
    lateinit var prefs: SharedPreferences
        private set
    private lateinit var file: AtomicFile
    lateinit var partsDir: File
        private set
    private val jobs = ArrayList<Job>()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private const val KEEP = 200

    private lateinit var followsFile: AtomicFile
    private lateinit var feedFile: AtomicFile
    private lateinit var doneFile: File
    private val done = HashSet<String>() // "videoId:A" / "videoId:V" ever saved (survives the 200-entry history trim)

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        file = AtomicFile(File(ctx.filesDir, "history.json"))
        partsDir = File(ctx.noBackupFilesDir, "parts").apply { mkdirs() }
        followsFile = AtomicFile(File(ctx.filesDir, "follows.json"))
        feedFile = AtomicFile(File(ctx.filesDir, "feed.json"))
        doneFile = File(ctx.filesDir, "downloaded.txt")
        listsFile = AtomicFile(File(ctx.filesDir, "lists.json"))
        queueFile = AtomicFile(File(ctx.filesDir, "queue.json"))
        cache = CacheStore(File(ctx.externalCacheDir ?: ctx.cacheDir, "stream"))
        modes = ctx.getSharedPreferences("modes", Context.MODE_PRIVATE)
        val app = ctx.applicationContext
        cache.publicDeleter = { m -> CachePublisher.deletePublic(app, m) }
        val loaded = try { JobCodec.decode(String(file.readFully(), Charsets.UTF_8)) } catch (_: Exception) { ArrayList() }
        JobCodec.afterRestart(loaded)
        jobs.addAll(loaded)
        try { if (doneFile.exists()) doneFile.forEachLine { if (it.isNotBlank()) done += it.trim() } } catch (_: Exception) {}
        for (j in loaded) if (j.state == State.DONE && j.media == Media.OK && !j.cache) done += doneKey(j.videoId, j.kind)
    }

    // ---- watch / listen cache (temporary, app cache storage) ----

    lateinit var cache: CacheStore
        private set
    private lateinit var modes: SharedPreferences

    /** Size limits in GB (⋮ → Cache limits), 20 GB each by default. */
    var videoCapGb: Int
        get() = prefs.getInt("videoCapGb", 20)
        set(v) = prefs.edit().putInt("videoCapGb", v).apply()
    var musicCapGb: Int
        get() = prefs.getInt("musicCapGb", 20)
        set(v) = prefs.edit().putInt("musicCapGb", v).apply()

    fun cap(kind: CacheKind): Long = (if (kind == CacheKind.VIDEO) videoCapGb else musicCapGb) * CacheLru.GB

    /** Audio: keep YouTube's original file (M4A copied as-is, no conversion). Off = "Convert to clean M4A". */
    var originalAudio: Boolean
        get() = prefs.getBoolean("originalAudio", true)
        set(v) = prefs.edit().putBoolean("originalAudio", v).apply()

    /** Cover art embedded in audio files (⋮): None / Low (default) / High. */
    var artQuality: ArtQuality
        get() = try { ArtQuality.valueOf(prefs.getString("artQuality", "LOW")!!) } catch (_: Exception) { ArtQuality.LOW }
        set(v) = prefs.edit().putString("artQuality", v.name).apply()

    /** The one-time "N songs have no cover art — add now?" offer of 1.9 was shown. */
    var artOffered: Boolean
        get() = prefs.getBoolean("artOffered", false)
        set(v) = prefs.edit().putBoolean("artOffered", v).apply()

    /** Watch quality for streams (height). */
    var watchP: Int
        get() = prefs.getInt("watchP", 720)
        set(v) = prefs.edit().putInt("watchP", v).apply()

    /** The user's Watch/Listen choice per video. */
    fun mode(videoId: String): PlayMode? = when (modes.getString(videoId, null)) { "W" -> PlayMode.WATCH; "L" -> PlayMode.LISTEN; else -> null }
    fun setMode(videoId: String, m: PlayMode) { modes.edit().putString(videoId, if (m == PlayMode.WATCH) "W" else "L").apply() }

    /** Content region used for videos blocked in this country ("" = off). See [InnerTube.info]. */
    var altRegion: String
        get() = prefs.getString("altRegion", "US") ?: "US"
        set(v) = prefs.edit().putString("altRegion", v).apply()

    @Synchronized fun isBlocked(videoId: String) = prefs.getStringSet("blocked", emptySet())!!.contains(videoId)
    @Synchronized fun markBlocked(videoId: String) {
        val s = HashSet(prefs.getStringSet("blocked", emptySet())!!)
        if (s.add(videoId)) prefs.edit().putStringSet("blocked", s).apply()
    }

    /** Public cached files Android refused to delete (no longer owned): MainActivity asks the system to trash them. */
    @Synchronized fun addTrashPending(uri: String) {
        val s = HashSet(prefs.getStringSet("trash", emptySet())!!); s += uri; prefs.edit().putStringSet("trash", s).apply()
    }
    @Synchronized fun takeTrashPending(): Set<String> {
        val s = prefs.getStringSet("trash", emptySet())!!.toSet(); if (s.isNotEmpty()) prefs.edit().remove("trash").apply(); return s
    }

    /** Videos YouTube called members-only: their /player goes through the account directly (no anonymous try first). */
    @Synchronized fun isMembers(videoId: String) = prefs.getStringSet("members", emptySet())!!.contains(videoId)
    @Synchronized fun markMembers(videoId: String) {
        val s = HashSet(prefs.getStringSet("members", emptySet())!!)
        if (s.add(videoId)) { while (s.size > 2000) s.remove(s.first()); prefs.edit().putStringSet("members", s).apply() }
    }

    /** Channels whose members-only videos played with the account (= the account is a member). */
    @Synchronized fun isMemberChannel(id: String) = prefs.getStringSet("memberCh", emptySet())!!.contains(id)
    @Synchronized fun markMemberChannel(id: String) {
        val s = HashSet(prefs.getStringSet("memberCh", emptySet())!!)
        if (s.add(id)) prefs.edit().putStringSet("memberCh", s).apply()
    }

    var searchFilters: SearchFilters
        get() = SearchFilters.decode(prefs.getString("searchFilters", null)).let { f ->
            if (prefs.contains("searchFilters")) f else f.copy(type = prefs.getInt("searchType", 0).coerceIn(0, 2))
        }
        set(v) { prefs.edit().putString("searchFilters", v.encode()).putInt("searchType", v.type).apply() }

    var feedFilters: FeedFilters
        get() = FeedFilters.decode(prefs.getString("feedFilters", null))
        set(v) { prefs.edit().putString("feedFilters", v.encode()).apply() }

    /** Channels whose members feed answered 404 (no memberships): skipped for a week. */
    @Synchronized fun noMembersUntil(id: String): Long =
        prefs.getStringSet("noMembers", emptySet())!!.firstOrNull { it.startsWith("$id@") }?.substringAfter('@')?.toLongOrNull() ?: 0L
    @Synchronized fun setNoMembers(id: String, until: Long) {
        val s = HashSet(prefs.getStringSet("noMembers", emptySet())!!).apply { removeAll { it.startsWith("$id@") }; if (until > 0) add("$id@$until") }
        prefs.edit().putStringSet("noMembers", s).apply()
    }

    /** 2.3 player: the mini window's corner, and picture-in-picture when pressing Home (off by default). */
    var miniCorner: String
        get() = prefs.getString("miniCorner", Corner.TOP_RIGHT.name)!!
        set(v) { prefs.edit().putString("miniCorner", v).apply() }
    var pipAuto: Boolean
        get() = prefs.getBoolean("pipAuto", false)
        set(v) { prefs.edit().putBoolean("pipAuto", v).apply() }

    /** 2.5: the open player's item and position, to resume after the process was gone (cleared on Close). */
    var lastPlay: String?
        get() = prefs.getString("lastPlay", null)
        set(v) { prefs.edit().apply { if (v == null) remove("lastPlay") else putString("lastPlay", v) }.apply() }

    /** "Also unsubscribe on YouTube" checkbox: last choice, default on. */
    var unsubDefault: Boolean
        get() = prefs.getBoolean("unsubDefault", true)
        set(v) { prefs.edit().putBoolean("unsubDefault", v).apply() }

    @Synchronized fun isBlockedChannel(id: String) = prefs.getStringSet("blockedCh", emptySet())!!.contains(id)
    @Synchronized fun markBlockedChannel(id: String) {
        val s = HashSet(prefs.getStringSet("blockedCh", emptySet())!!)
        if (s.add(id)) prefs.edit().putStringSet("blockedCh", s).apply()
    }

    // ---- local lists + play queue (small JSON files, UI thread) ----

    private lateinit var listsFile: AtomicFile
    private lateinit var queueFile: AtomicFile
    private var listsCache: List<VList>? = null
    private var queueCache: PlayQueue? = null

    fun lists(): List<VList> = listsCache ?: (try { VideoLists.decode(String(listsFile.readFully(), Charsets.UTF_8)) } catch (_: Exception) { emptyList() })
        .also { listsCache = it }

    fun setLists(l: List<VList>) { listsCache = l; writeAtomic(listsFile, VideoLists.encode(l)) }

    fun queue(): PlayQueue = queueCache ?: (try { PlayQueue.decode(String(queueFile.readFully(), Charsets.UTF_8)) } catch (_: Exception) { PlayQueue() })
        .also { queueCache = it }

    fun setQueue(q: PlayQueue) { queueCache = q; writeAtomic(queueFile, q.encode()) }

    var tab: Int
        get() = prefs.getInt("tab", 0)
        set(v) = prefs.edit().putInt("tab", v).apply()

    // ---- "already downloaded" marks ----

    private fun doneKey(id: String, kind: Kind) = id + ":" + (if (kind == Kind.AUDIO) "A" else "V")

    @Synchronized fun downloaded(id: String, kind: Kind) = doneKey(id, kind) in done
    @Synchronized fun downloadedAny(id: String) = doneKey(id, Kind.AUDIO) in done || doneKey(id, Kind.VIDEO) in done
    @Synchronized fun queued(id: String) = jobs.any { it.active && it.videoId == id }

    /** Ids queued/running right now. */
    @Synchronized fun activeIds(): Set<String> = jobs.filter { it.active }.mapTo(HashSet()) { it.videoId }

    /** Apply MediaStore sync results: trashed / deleted files lose their ✓ mark (can be downloaded again). */
    fun applyFixes(fixes: List<Reconcile.Fix>) {
        if (fixes.isEmpty()) return
        synchronized(this) {
            for (f in fixes) {
                val j = jobs.firstOrNull { it.id == f.jobId } ?: continue
                j.media = f.media; j.relPath = f.relPath; j.fileName = f.fileName
                val k = doneKey(j.videoId, j.kind)
                if (f.media == Media.OK) markDone(j) else if (!j.cache && done.remove(k)) rewriteDone()
            }
            save()
        }
        notifyListeners()
    }

    private fun rewriteDone() { try { doneFile.writeText(done.joinToString("\n", postfix = "\n")) } catch (_: Exception) {} }

    private fun markDone(j: Job) {
        if (j.cache || j.art || j.media != Media.OK) return // cached / art-only / trashed: not "downloaded"
        val k = doneKey(j.videoId, j.kind)
        if (done.add(k)) try { doneFile.appendText(k + "\n") } catch (_: Exception) {}
    }

    // ---- follows + feed cache (UI thread only) ----

    private var followsCache: List<Follow>? = null

    fun follows(): List<Follow> = followsCache ?: (try { Follows.decode(String(followsFile.readFully(), Charsets.UTF_8)) } catch (_: Exception) { emptyList() })
        .also { followsCache = it }

    fun isFollowed(id: String) = follows().any { it.id == id }

    fun setFollows(list: List<Follow>) {
        followsCache = list
        writeAtomic(followsFile, Follows.encode(list))
    }

    fun follow(id: String, title: String): Boolean {
        val (l, n) = Follows.merge(follows(), listOf(Follow(id, title.ifEmpty { id }, System.currentTimeMillis())))
        if (n > 0) setFollows(l)
        return n > 0
    }

    fun unfollow(id: String) {
        setFollows(follows().filter { it.id != id })
        saveFeed(loadFeed().only(follows().map { it.id }))
    }

    private var feedCache: FeedCache? = null

    fun loadFeed(): FeedCache = feedCache ?: (try { FeedCache.decode(String(feedFile.readFully(), Charsets.UTF_8)) } catch (_: Exception) { FeedCache.EMPTY })
        .also { feedCache = it }

    fun saveFeed(f: FeedCache, write: Boolean = true) {
        feedCache = f
        if (write) writeAtomic(feedFile, f.encode())
    }

    private fun writeAtomic(f: AtomicFile, s: String) {
        val out = f.startWrite()
        try { out.write(s.toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }

    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(v) = prefs.edit().putBoolean("wifiOnly", v).apply()
    var kind: Kind
        get() = try { Kind.valueOf(prefs.getString("kind", "AUDIO")!!) } catch (_: Exception) { Kind.AUDIO }
        set(v) = prefs.edit().putString("kind", v.name).apply()
    var audioFmt: AudioFmt
        get() = try { AudioFmt.valueOf(prefs.getString("audioFmt", "M4A")!!) } catch (_: Exception) { AudioFmt.M4A }
        set(v) = prefs.edit().putString("audioFmt", v.name).apply()
    var audioQ: AudioQ
        get() = try { AudioQ.valueOf(prefs.getString("audioQ", "HIGH")!!) } catch (_: Exception) { AudioQ.HIGH }
        set(v) = prefs.edit().putString("audioQ", v.name).apply()
    var videoP: Int
        get() = prefs.getInt("videoP", 720)
        set(v) = prefs.edit().putInt("videoP", v).apply()
    var askedNotif: Boolean
        get() = prefs.getBoolean("askedNotif", false)
        set(v) = prefs.edit().putBoolean("askedNotif", v).apply()

    // ---- jobs (shared by UI thread and the service's worker thread) ----

    @Synchronized fun snapshot(): List<Job> = jobs.map { it.copy() }

    @Synchronized fun get(id: Long): Job? = jobs.firstOrNull { it.id == id }

    @Synchronized fun add(j: Job) { jobs.add(0, j); trim(); save() }

    /** Bulk add with one history write; [list] in download order (first one is downloaded first). */
    fun addAll(list: List<Job>) {
        synchronized(this) { for (j in list) jobs.add(0, j); trim(); save() }
        notifyListeners()
    }

    /** Wi-Fi gone: the rest of the queue fails at once ("Retry all failed" later) instead of one by one. */
    fun failQueued(msg: String) {
        synchronized(this) {
            var any = false
            for (j in jobs) if (j.state == State.QUEUED) { j.state = State.FAILED; j.error = msg; any = true }
            if (any) save()
        }
        notifyListeners()
    }

    fun retryAllFailed(): Int {
        var n = 0
        synchronized(this) {
            for (j in jobs) if (j.state == State.FAILED) { j.state = State.QUEUED; j.error = null; n++ }
            if (n > 0) save()
        }
        notifyListeners()
        return n
    }

    /** Oldest first; "Cache" jobs are low priority (after every normal download). */
    @Synchronized fun nextQueued(): Job? = jobs.lastOrNull { it.state == State.QUEUED && !it.cache } ?: jobs.lastOrNull { it.state == State.QUEUED }

    @Synchronized fun hasActive(): Boolean = jobs.any { it.active }

    @Synchronized fun queuedCount(): Int = jobs.count { it.state == State.QUEUED }

    /** Changes when a download finishes or the queue changes (Search tab redraws its marks only then). */
    @Synchronized fun marksSig(): Int = done.size * 100_003 + jobs.count { it.active }

    fun cancelQueued() {
        synchronized(this) {
            var any = false
            for (j in jobs) if (j.state == State.QUEUED) { j.state = State.CANCELED; any = true; deleteParts(j.id) }
            if (any) save()
        }
        notifyListeners()
    }

    @Synchronized fun newId(): Long = maxOf(System.currentTimeMillis(), (jobs.maxOfOrNull { it.id } ?: 0) + 1)

    /** Mutate a job under the lock; [persist] = write history (state changes; progress alone is not written). */
    fun update(id: Long, persist: Boolean = true, block: (Job) -> Unit) {
        synchronized(this) {
            val j = jobs.firstOrNull { it.id == id } ?: return
            block(j)
            if (j.state == State.DONE) markDone(j)
            if (persist) save()
        }
        notifyListeners()
    }

    fun cancel(id: Long) {
        var wasQueued = false
        update(id) { if (it.active) { wasQueued = it.state == State.QUEUED; it.state = State.CANCELED; it.error = null } }
        if (wasQueued) deleteParts(id) // a running job cleans up itself
    }

    fun retry(id: Long) = update(id) {
        if (it.state == State.FAILED || it.state == State.CANCELED) { it.state = State.QUEUED; it.error = null }
    }

    fun remove(id: Long) {
        synchronized(this) { if (jobs.removeAll { it.id == id && !it.active }) deleteParts(id); save() }
        notifyListeners()
    }

    fun clearFinished() {
        synchronized(this) {
            jobs.filter { !it.active }.forEach { deleteParts(it.id) }
            jobs.removeAll { !it.active }
            save()
        }
        notifyListeners()
    }

    /** Partial stream files of a job ("<id>_<itag>_<size>.part"). Kept after a failure so Retry resumes. */
    fun deleteParts(id: Long) {
        partsDir.listFiles()?.forEach { if (it.name.startsWith("${id}_")) it.delete() }
    }

    @Synchronized fun isCancelled(id: Long) = jobs.firstOrNull { it.id == id }?.state != State.RUNNING

    /**
     * Keep the newest [KEEP] finished single entries and the newest 30 batches; queued/running ones never count (a
     * bulk queue doesn't wipe history), and a batch goes only as a whole once none of its tracks is active.
     */
    private fun trim() {
        var finished = jobs.count { !it.active && it.batch == 0L }
        while (finished > KEEP) {
            val i = jobs.indexOfLast { !it.active && it.batch == 0L }
            if (i < 0) break
            jobs.removeAt(i)
            finished--
        }
        val batches = jobs.map { it.batch }.filter { it != 0L }.distinct() // newest first (list is newest first)
        for (b in batches.drop(30)) if (jobs.none { it.batch == b && it.active }) jobs.removeAll { it.batch == b }
    }

    /** Parallel audio downloads for bulk music (⋮, 1–4, default 3). */
    var parallelAudio: Int
        get() = prefs.getInt("parallelAudio", 3).coerceIn(1, 4)
        set(v) = prefs.edit().putInt("parallelAudio", v.coerceIn(1, 4)).apply()

    /** Queued jobs in start order: normal downloads oldest first, then cache jobs. */
    @Synchronized fun queuedInOrder(): List<Job> {
        val q = jobs.filter { it.state == State.QUEUED }.asReversed()
        return q.filter { !it.cache && !it.art } + q.filter { it.cache } + q.filter { it.art }
    }

    /** Claim a queued job for a download worker (QUEUED -> RUNNING); false when it was cancelled meanwhile. */
    fun claim(id: Long): Boolean {
        var ok = false
        update(id) { if (it.state == State.QUEUED) { it.state = State.RUNNING; it.error = null; it.done = 0; it.phase = Phase.DOWNLOAD; ok = true } }
        return ok
    }

    fun cancelBatch(batch: Long) {
        val ids = synchronized(this) { jobs.filter { it.batch == batch && it.active }.map { it.id } }
        ids.forEach { cancel(it) }
    }

    fun retryBatch(batch: Long): Int {
        var n = 0
        synchronized(this) {
            for (j in jobs) if (j.batch == batch && (j.state == State.FAILED || j.state == State.CANCELED)) { j.state = State.QUEUED; j.error = null; n++ }
            if (n > 0) save()
        }
        notifyListeners()
        return n
    }

    fun removeBatch(batch: Long) {
        synchronized(this) {
            jobs.filter { it.batch == batch && !it.active }.forEach { deleteParts(it.id) }
            jobs.removeAll { it.batch == batch && !it.active }
            save()
        }
        notifyListeners()
    }

    private fun save() {
        val out = file.startWrite()
        try {
            out.write(JobCodec.encode(jobs).toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
        }
    }

    fun addListener(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun removeListener(l: () -> Unit) { synchronized(listeners) { listeners -= l } }

    private var posted = false
    private fun notifyListeners() {
        synchronized(listeners) {
            if (posted || listeners.isEmpty()) return
            posted = true
        }
        main.post {
            val ls = synchronized(listeners) { posted = false; listeners.toList() }
            ls.forEach { it() }
        }
    }
}

/** MediaStore writes. No storage permission: the app only creates (and sees) its own entries. */
object Saver {
    /** Copy a finished file into a MediaStore file descriptor in 1 MB blocks; [progress] 0..1. */
    fun copyInto(src: File, fd: java.io.FileDescriptor, cancelled: () -> Boolean, progress: (Float) -> Unit = {}) {
        val total = src.length().coerceAtLeast(1)
        java.io.FileOutputStream(fd).use { o ->
            src.inputStream().use { i ->
                val buf = ByteArray(1 shl 20)
                var done = 0L
                while (true) {
                    val n = i.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    if (cancelled()) throw Cancelled()
                    progress(done.toFloat() / total)
                }
            }
        }
    }

    fun collection(kind: Kind): Uri =
        if (kind == Kind.AUDIO) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /** Names this app already has in that folder (MediaStore itself also renames on a clash with other apps' files). */
    fun takenNames(cr: ContentResolver, kind: Kind, relPath: String): Set<String> {
        val out = HashSet<String>()
        cr.query(
            collection(kind), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=?", arrayOf(relPath), null
        )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { out += it } }
        return out
    }

    /** Insert a pending (invisible) entry. */
    fun create(cr: ContentResolver, kind: Kind, relPath: String, name: String, mime: String, title: String): Uri {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
            put(MediaStore.MediaColumns.TITLE, title)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (kind == Kind.AUDIO) put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        return cr.insert(collection(kind), v) ?: throw java.io.IOException("Can't create the file in ${relPath.trimEnd('/')}")
    }

    fun displayName(cr: ContentResolver, uri: Uri): String? =
        cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    fun publish(cr: ContentResolver, uri: Uri) {
        cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    fun discard(cr: ContentResolver, uri: Uri) {
        try { cr.delete(uri, null, null) } catch (_: Exception) {}
    }
}
