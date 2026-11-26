package app.localmusic

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.util.concurrent.Executors

/** Reads the library from MediaStore (local files only). */
object Library {
    @Volatile var index = LibraryIndex(emptyList())
        private set
    @Volatile private var generation = -1L

    val PERMISSION: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Plain Youtube's videos. On 30-32 READ_EXTERNAL_STORAGE covers them; on 33+ it is a separate, optional grant. */
    val VIDEO_PERMISSION: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Rebuild the index from the same songs (no MediaStore query): labels it bakes in follow a language change. */
    fun relabel() { index = LibraryIndex(index.songs) }

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED
    fun hasVideoPermission(ctx: Context) = ctx.checkSelfPermission(VIDEO_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** MediaStore ids are unique across audio and video (one files table), so a song id says which collection it is in. */
    fun uri(id: Long): Uri = uri(id, index.byId[id]?.isVideo == true)
    fun uri(s: Song): Uri = uri(s.id, s.isVideo)
    private fun uri(id: Long, video: Boolean, volume: String = MediaStore.VOLUME_EXTERNAL): Uri = ContentUris.withAppendedId(
        if (video) MediaStore.Video.Media.getContentUri(volume) else MediaStore.Audio.Media.getContentUri(volume), id)

    /** The item on its own volume: what createTrashRequest / createDeleteRequest want. */
    fun volumeUri(s: Song): Uri = uri(s.id, s.isVideo, s.volume.ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY })
    fun volumeUri(t: TrashItem): Uri = uri(t.id, t.video, t.volume.ifEmpty { MediaStore.VOLUME_EXTERNAL_PRIMARY })

    private fun currentGeneration(ctx: Context): Long =
        MediaStore.getExternalVolumeNames(ctx).sumOf { runCatching { MediaStore.getGeneration(ctx, it) }.getOrDefault(0L) }

    /** Cheap check whether MediaStore changed since the last load. */
    fun stale(ctx: Context): Boolean = hasPermission(ctx) && currentGeneration(ctx) != generation

    private fun clean(s: String?): String = s?.trim()?.takeUnless { it.equals("<unknown>", true) } ?: ""

    /** Synchronous MediaStore query (≈100 ms for a thousand songs). */
    fun load(ctx: Context): LibraryIndex {
        if (!hasPermission(ctx)) return index
        val gen = currentGeneration(ctx)
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.GENRE, MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DISC_NUMBER,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.IS_RINGTONE, MediaStore.Audio.Media.IS_NOTIFICATION, MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_PODCAST, MediaStore.Audio.Media.IS_AUDIOBOOK, MediaStore.Audio.Media.VOLUME_NAME,
            MediaStore.Audio.Media.DATE_MODIFIED,
        )
        if (Build.VERSION.SDK_INT >= 31) cols += MediaStore.Audio.Media.IS_RECORDING
        val out = ArrayList<Song>()
        ctx.contentResolver.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), cols.toTypedArray(), null, null, null)?.use { c ->
            fun i(n: String) = c.getColumnIndexOrThrow(n)
            val iId = i(cols[0]); val iTitle = i(cols[1]); val iArtist = i(cols[2]); val iAlbum = i(cols[3]); val iAA = i(cols[4])
            val iAlbumId = i(cols[5]); val iGenre = i(cols[6]); val iTrack = i(cols[7]); val iDisc = i(cols[8]); val iYear = i(cols[9])
            val iDur = i(cols[10]); val iRel = i(cols[11]); val iName = i(cols[12]); val iSize = i(cols[13]); val iAdded = i(cols[14])
            val iRing = i(cols[15]); val iNotif = i(cols[16]); val iAlarm = i(cols[17]); val iPod = i(cols[18]); val iBook = i(cols[19])
            val iVol = i(cols[20]); val iMod = i(cols[21])
            val iRec = if (cols.size > 22) i(cols[22]) else -1
            while (c.moveToNext()) {
                val rel = c.getString(iRel) ?: ""
                val name = c.getString(iName) ?: continue
                val dur = c.getLong(iDur)
                val trackRaw = c.getString(iTrack)
                val skip = Classify.skipReason(
                    rel, name, dur,
                    ringtone = c.getInt(iRing) != 0, notification = c.getInt(iNotif) != 0, alarm = c.getInt(iAlarm) != 0,
                    recording = iRec >= 0 && c.getInt(iRec) != 0, podcast = c.getInt(iPod) != 0, audiobook = c.getInt(iBook) != 0,
                    hasTrackNumber = Naming.parseTrack(trackRaw) > 0,
                )
                if (skip != null) continue
                out += Song(
                    id = c.getLong(iId),
                    title = clean(c.getString(iTitle)).ifEmpty { name.substringBeforeLast('.') },
                    artist = clean(c.getString(iArtist)),
                    album = PlainYoutube.album(rel, clean(c.getString(iAlbum))), // its untagged files: the album folder
                    albumArtist = clean(c.getString(iAA)),
                    albumId = c.getLong(iAlbumId),
                    genre = clean(c.getString(iGenre)),
                    track = Naming.parseTrack(trackRaw),
                    disc = Naming.parseDisc(c.getString(iDisc), trackRaw),
                    year = c.getInt(iYear),
                    durationMs = dur,
                    relPath = rel,
                    name = name,
                    size = c.getLong(iSize),
                    dateAdded = c.getLong(iAdded),
                    volume = c.getString(iVol) ?: "",
                    modified = c.getLong(iMod),
                )
            }
        }
        if (hasVideoPermission(ctx)) runCatching { loadVideos(ctx, out) } // denied or failing: audio only
        // Files rewritten in place (Plain Youtube embedding cover art): drop their cached art so the new cover shows.
        val changed = ArtPick.changed(index.songs, out)
        if (changed.isNotEmpty()) Art.invalidate(changed)
        index = LibraryIndex(out)
        generation = gen
        return index
    }

    /** Videos from Plain Youtube only (Movies/PlainDownload/<Channel>/): never camera or other videos. */
    private fun loadVideos(ctx: Context, out: MutableList<Song>) {
        val cols = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.TITLE, MediaStore.MediaColumns.ARTIST, MediaStore.MediaColumns.ALBUM,
            MediaStore.MediaColumns.DURATION, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.WIDTH,
            MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
        ctx.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), cols,
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(Videos.DIR + "%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(5) ?: continue
                if (!Videos.isLibraryVideo(rel)) continue
                val name = c.getString(6) ?: continue
                val (w, h) = Videos.displaySize(c.getInt(9), c.getInt(10), c.getInt(11))
                out += Song(
                    id = c.getLong(0),
                    title = clean(c.getString(1)).ifEmpty { name.substringBeforeLast('.') },
                    artist = clean(c.getString(2)),
                    album = PlainYoutube.album(rel, ""), albumArtist = "", albumId = -1, genre = "", track = 0, disc = 1, year = 0,
                    durationMs = c.getLong(4), relPath = rel, name = name, size = c.getLong(7), dateAdded = c.getLong(8),
                    isVideo = true, videoW = w, videoH = h, volume = c.getString(12) ?: "", modified = c.getLong(13),
                )
            }
        }
    }
}

/** Embedded album art, decoded off the main thread and cached in memory. */
object Art {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val none = HashSet<String>()
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "art").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    private fun key(id: Long, px: Int) = "$id/$px"

    /** Forget art (and "has no art") for files that changed, and every mosaic (they may contain them). */
    fun invalidate(ids: Set<Long>) {
        for (k in cache.snapshot().keys) {
            if (k.startsWith("m:") || k.substringBefore('/').toLongOrNull() in ids) cache.remove(k)
        }
        synchronized(none) { none.removeAll { it.startsWith("m:") || it.substringBefore('/').toLongOrNull() in ids } }
        Player.onArtChanged(ids)
    }

    /**
     * 2×2 mosaic of the first 4 distinct covers among [songs] (see ArtPick.mosaic), for packs, bundles and folders.
     * Fewer than 4 covers: the first one alone. Quarter-size thumbnails, built off the main thread, cached in the same LRU.
     */
    fun mosaic(view: ImageView, songs: List<Song>, px: Int, placeholder: () -> Unit) {
        val picks = ArtPick.mosaic(songs).map { it.id }
        if (picks.isEmpty()) { view.tag = null; placeholder(); return }
        val k = "m:" + picks.hashCode() + "/" + px
        view.tag = k
        cache.get(k)?.let { view.setImageBitmap(it); return }
        placeholder()
        synchronized(none) { if (k in none) return }
        val app = view.context.applicationContext
        exec.execute {
            val half = px / 2
            val found = ArrayList<Bitmap>(4)
            for (id in picks) { loadSync(app, id, half)?.let { found += it }; if (found.size == 4) break }
            val b: Bitmap? = when {
                found.size == 4 -> runCatching {
                    Bitmap.createBitmap(half * 2, half * 2, Bitmap.Config.RGB_565).also { out ->
                        val c = android.graphics.Canvas(out)
                        val p = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
                        found.forEachIndexed { i, bm ->
                            val l = (i % 2) * half; val t = (i / 2) * half
                            c.drawBitmap(bm, null, android.graphics.Rect(l, t, l + half, t + half), p)
                        }
                    }
                }.getOrNull()
                found.isNotEmpty() -> found[0]
                else -> null
            }
            if (b != null) cache.put(k, b) else synchronized(none) { none += k }
            main.post { if (view.tag == k && b != null) view.setImageBitmap(b) }
        }
    }

    fun cached(id: Long, px: Int): Bitmap? = cache.get(key(id, px))

    fun loadSync(ctx: Context, id: Long, px: Int): Bitmap? {
        val k = key(id, px)
        cache.get(k)?.let { return it }
        synchronized(none) { if (k in none) return null }
        val bmp = try {
            ctx.contentResolver.loadThumbnail(Library.uri(id), Size(px, px), null)
        } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        if (bmp != null) cache.put(k, bmp) else synchronized(none) { none += k }
        return bmp
    }

    fun async(ctx: Context, id: Long, px: Int, cb: (Bitmap?) -> Unit) {
        cached(id, px)?.let { cb(it); return }
        val app = ctx.applicationContext
        exec.execute { val b = loadSync(app, id, px); main.post { cb(b) } }
    }

    /** First of [ids] that has embedded art (tries a few, off the main thread); for artist/album covers. */
    fun intoAny(view: ImageView, ids: List<Long>, px: Int, placeholder: () -> Unit) {
        val key = ids.firstOrNull() ?: return placeholder()
        view.tag = key
        ids.firstNotNullOfOrNull { cached(it, px) }?.let { view.setImageBitmap(it); return }
        placeholder()
        val app = view.context.applicationContext
        exec.execute {
            val b = ids.firstNotNullOfOrNull { loadSync(app, it, px) }
            main.post { if (view.tag == key && b != null) view.setImageBitmap(b) }
        }
    }

    /** Bind art into an ImageView; stale results are dropped if the view was recycled meanwhile. */
    fun into(view: ImageView, id: Long, px: Int, placeholder: () -> Unit) {
        view.tag = id
        val hit = cached(id, px)
        if (hit != null) { view.setImageBitmap(hit); return }
        placeholder()
        async(view.context, id, px) { b -> if (view.tag == id && b != null) view.setImageBitmap(b) }
    }
}
