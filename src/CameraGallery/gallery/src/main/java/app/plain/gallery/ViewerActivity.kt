package app.plain.gallery

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.Size
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen viewer: pinch / double-tap zoom, swipe for the next / previous item, video playback,
 * share, move to trash, details. Opened from the grid ("index") or by other apps (ACTION_VIEW).
 * Each item shows at once from its small thumbnail, then sharpens; the neighbours are decoded ahead.
 */
class ViewerActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor()
    private val full = LruCache<String, Bitmap>(3)

    private var items: List<Item> = emptyList()
    private var i = 0
    private var listMode = false
    @Volatile private var closed = false
    /** What the screen shows for the current item: 0 nothing · 1 thumbnail · 2 full · 3 zoomed-in detail. */
    private var level = 0
    private var sharpFor: Item? = null
    private var playing = false
    private var barsShown = true
    private var pendingDelete: Item? = null
    /** Swiping through the camera roll (opened from Plain Camera's thumbnail). */
    private var cameraRoll = false
    private var maxPx = 2048

    private lateinit var iv: ZoomImage
    private lateinit var videoFrame: SwipeFrame
    private lateinit var video: VideoView
    private lateinit var controller: MediaController
    private lateinit var playBtn: TextView
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var caption: TextView
    private lateinit var deleteBtn: TextView
    private lateinit var catBtn: TextView
    private lateinit var undo: UndoBar
    private var pendingRestore: Pair<Item, Int>? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        maxPx = resources.displayMetrics.let { max(it.widthPixels, it.heightPixels) }.coerceIn(1024, 2560)
        buildUi()
        val idx = intent.getIntExtra("index", -1)
        if (idx >= 0 && Library.loaded && idx in Library.shown.indices) {
            items = Library.shown
            i = b?.getInt("i", idx) ?: idx
            listMode = true
            showAt(i.coerceIn(0, items.size - 1))
        } else {
            val u = intent.data ?: run { finish(); return }
            val type = intent.type ?: try { contentResolver.getType(u) } catch (_: Exception) { null } ?: "image/*"
            // a media-store id lets a delete here also drop the item from the shared library
            val id = if (u.authority == MediaStore.AUTHORITY) try { ContentUris.parseId(u) } catch (_: Exception) { -1L } else -1L
            items = listOf(Item(u, id, type.startsWith("video"), 0, 0, type))
            showAt(0)
            // with the library readable, swipe on through more items from this one: Plain Camera's
            // thumbnail asks for the camera roll, anything else gets the whole library
            if (canRead() && u.authority == MediaStore.AUTHORITY) bg {
                var list: List<Item>? = null
                if (intent.getBooleanExtra(EXTRA_CAMERA_ROLL, false)) {
                    val roll = try { Library.cameraRoll(this) } catch (_: Exception) { emptyList() }
                    if (Library.indexOf(u, roll) >= 0) { list = roll; cameraRoll = true }
                }
                if (list == null) {
                    // the shared library may be stale (a capture newer than the last load): reload then
                    if (!Library.loaded || Library.indexOf(u) < 0) try { Library.load(this) } catch (_: Exception) {}
                    if (Library.indexOf(u) >= 0) list = Library.items
                }
                val l = list ?: return@bg
                main.post { adopt(l, Library.indexOf(u, l)) }
            }
        }
    }

    /** Swaps the single opened item for a list that contains it, keeping what's on screen. */
    private fun adopt(list: List<Item>, k: Int) {
        if (closed || items.size != 1 || k !in list.indices) return
        items = list
        i = k
        listMode = true
        updateCaption()
        preload(k)
    }

    override fun onSaveInstanceState(out: Bundle) { super.onSaveInstanceState(out); out.putInt("i", i) }

    override fun onPause() {
        super.onPause()
        if (playing) video.pause()
    }

    override fun onDestroy() {
        closed = true
        decoder.shutdownNow()
        full.evictAll()
        super.onDestroy()
    }

    override fun finish() {
        if (listMode) setResult(RESULT_OK, Intent().putExtra("index", i))
        super.finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (playing) stopVideo() else super.onBackPressed()
    }

    private fun canRead() = (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun bg(r: () -> Unit) { try { decoder.execute(r) } catch (_: RejectedExecutionException) {} }
    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------------ UI
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        iv = ZoomImage(this).apply {
            onTap = { toggleBars() }
            onSwipe = { d -> step(d) }
            onZoomed = { z -> if (z > 1.6f) sharpen() }
        }
        root.addView(iv, FrameLayout.LayoutParams(-1, -1))

        videoFrame = SwipeFrame(this).apply { visibility = View.GONE; onSwipe = { d -> step(d) } }
        video = VideoView(this)
        videoFrame.addView(video, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        controller = MediaController(this)
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1))

        playBtn = label("▶", 34f).apply {
            background = outline(BTN_LINE, dp(40).toFloat(), dp(1))
            contentDescription = "Play video"
            visibility = View.GONE
            setOnClickListener { play() }
        }
        root.addView(playBtn, FrameLayout.LayoutParams(dp(80), dp(80), Gravity.CENTER))

        topBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(4), dp(4), dp(12), dp(4))
        }
        topBar.addView(label("‹", 26f).apply { contentDescription = "Back"; setOnClickListener { if (playing) stopVideo() else finish() } },
            LinearLayout.LayoutParams(dp(48), dp(48)))
        caption = label("", 14f, TEXT2).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START; maxLines = 2 }
        topBar.addView(caption, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        bottomBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            setPadding(dp(8), dp(10), dp(8), dp(14))
        }
        val lp = { LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(6); marginEnd = dp(6) } }
        bottomBar.addView(button("Share") { share() }, lp())
        deleteBtn = button("Delete") { delete() }
        bottomBar.addView(deleteBtn, lp())
        catBtn = button("Category") { categorize() }.apply { contentDescription = "Categories" }
        bottomBar.addView(catBtn, lp())
        bottomBar.addView(button("Details") { details() }, lp())
        for (k in 0 until bottomBar.childCount) (bottomBar.getChildAt(k) as TextView).apply {
            setPadding(dp(2), dp(8), dp(2), dp(8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        undo = UndoBar(this)
        root.addView(undo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(10), 0, dp(10), dp(90)) })

        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val bottom = ins.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            val top = ins.systemWindowInsetTop
            topBar.setPadding(dp(4), dp(4) + top, dp(12), dp(4))
            bottomBar.setPadding(dp(8), dp(10), dp(8), dp(14) + bottom)
            ins
        }
        setContentView(root)
    }

    private fun toggleBars() {
        barsShown = !barsShown
        val v = if (barsShown) View.VISIBLE else View.GONE
        topBar.visibility = v
        bottomBar.visibility = if (playing) View.GONE else v
    }

    private val dateFmt get() = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault())

    private fun updateCaption() {
        val it = items.getOrNull(i) ?: return
        val pos = if (items.size > 1) "${Paging.count(i + 1)} / ${Paging.count(items.size)}" else ""
        val date = if (it.time > 0) dateFmt.format(Date(it.time)) else ""
        caption.text = listOf(if (cameraRoll) "Camera" else "", date, pos).filter { s -> s.isNotEmpty() }.joinToString("  ·  ")
        deleteBtn.visibility = if (it.inStore) View.VISIBLE else View.GONE
        catBtn.visibility = if (it.inStore && it.id >= 0 && getSharedPreferences("gallery", MODE_PRIVATE).getBoolean("categories", false)) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ showing items
    /** Media-store items by type + id, so content://media/external/… and …/external_primary/… match. */
    private fun key(it: Item) = if (it.inStore) (if (it.video) "v" else "i") + (it.uri.lastPathSegment ?: "") else it.uri.toString()

    private fun step(d: Int) {
        val k = i + d
        if (k in items.indices) showAt(k) else toast(if (k < 0) "Newest item" else "Oldest item")
    }

    private fun showAt(k: Int) {
        stopVideo()
        i = k
        val it = items[k]
        updateCaption()
        playBtn.visibility = if (it.video) View.VISIBLE else View.GONE
        sharpFor = null
        val ready = full.get(key(it))
        level = if (ready != null) 2 else 0
        iv.setBitmap(ready, reset = true)
        if (ready == null) load(it, show = true)
        preload(k)
    }

    private fun preload(k: Int) {
        for (n in listOf(k + 1, k - 1)) items.getOrNull(n)?.let { x -> if (full.get(key(x)) == null) load(x, show = false) }
    }

    private fun isCurrent(it: Item) = items.getOrNull(i)?.let { c -> c === it || key(c) == key(it) } == true

    private fun load(it: Item, show: Boolean) = bg {
        if (closed) return@bg
        val idx = items.indexOfFirst { x -> x === it || key(x) == key(it) }
        if (idx < 0 || abs(idx - i) > 1 || full.get(key(it)) != null) return@bg
        if (show && isCurrent(it)) {
            val t = try { contentResolver.loadThumbnail(it.uri, Size(384, 384), null) } catch (_: Exception) { null }
            if (t != null) main.post { if (!closed && isCurrent(it) && level == 0) { level = 1; iv.setBitmap(t, reset = true) } }
        }
        val b = try {
            if (it.video) contentResolver.loadThumbnail(it.uri, Size(1024, 1024), null) else decode(it.uri, maxPx)
        } catch (_: Throwable) { null }
        main.post {
            if (closed) return@post
            if (b == null) { if (show && isCurrent(it) && !it.video) toast("Couldn't open this item."); return@post }
            full.put(key(it), b)
            if (isCurrent(it) && level < 2) { level = 2; iv.setBitmap(b) }
        }
    }

    /** Decodes upright (ImageDecoder applies EXIF rotation) at roughly [px] on the long side. */
    private fun decode(u: Uri, px: Int): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, u)) { d, info, _ ->
        val long = max(info.size.width, info.size.height)
        var s = 1
        while (long / (s * 2) >= px) s *= 2
        d.setTargetSampleSize(s)
        d.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
    }

    /** Zoomed in: decode the current photo once more with up to 3× the detail. */
    private fun sharpen() {
        val it = items.getOrNull(i) ?: return
        if (it.video || sharpFor === it || closed) return
        sharpFor = it
        bg {
            if (closed || !isCurrent(it)) return@bg
            val b = try { decode(it.uri, min(4096, maxPx * 3)) } catch (_: Throwable) { null }
            main.post { if (b != null && !closed && isCurrent(it)) { level = 3; iv.setBitmap(b) } }
        }
    }

    // ------------------------------------------------------------------ video
    private fun play() {
        val it = items.getOrNull(i) ?: return
        if (!it.video) return
        playing = true
        playBtn.visibility = View.GONE
        bottomBar.visibility = View.GONE
        videoFrame.visibility = View.VISIBLE
        iv.visibility = View.INVISIBLE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        controller.setAnchorView(videoFrame)
        video.setMediaController(controller)
        video.setOnPreparedListener { video.start(); controller.show(2500) }
        video.setOnCompletionListener { controller.show(0) }
        video.setOnErrorListener { _, _, _ -> toast("Can't play this video."); stopVideo(); true }
        video.setVideoURI(it.uri)
    }

    private fun stopVideo() {
        if (!playing) return
        playing = false
        try { video.stopPlayback() } catch (_: Exception) {}
        controller.hide()
        videoFrame.visibility = View.GONE
        iv.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playBtn.visibility = if (items.getOrNull(i)?.video == true) View.VISIBLE else View.GONE
        bottomBar.visibility = if (barsShown) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------------ actions
    private fun share() {
        val it = items.getOrNull(i) ?: return
        val send = Intent(Intent.ACTION_SEND).setType(it.mime).putExtra(Intent.EXTRA_STREAM, it.uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri("", it.uri)
        try { startActivity(Intent.createChooser(send, null)) } catch (_: Exception) { toast("No app to share with.") }
    }

    private fun categorize() {
        val it = items.getOrNull(i) ?: return
        if (it.id < 0) return
        Categories.choose(this, listOf(it.id), { toast("Categories saved.") })
    }

    /**
     * Moves the item to the system trash (restorable for 30 days). Android confirms first, unless
     * Plain Gallery is allowed "Media management"; either way there's an Undo for a few seconds.
     */
    @Suppress("DEPRECATION")
    private fun delete() {
        val it = items.getOrNull(i) ?: return
        if (!it.inStore) return
        pendingDelete = it
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createTrashRequest(contentResolver, listOf(it.uri), true)
                startIntentSenderForResult(pi.intentSender, RC_DELETE, null, 0, 0, 0)
            } else {
                try {
                    contentResolver.delete(it.uri, null, null)
                    removed(it)
                } catch (e: RecoverableSecurityException) {
                    startIntentSenderForResult(e.userAction.actionIntent.intentSender, RC_DELETE, null, 0, 0, 0)
                }
            }
        } catch (e: Exception) {
            toast("Couldn't delete: ${e.message}")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, res: Int, data: Intent?) {
        super.onActivityResult(rc, res, data)
        if (rc == RC_RESTORE) {
            val (it, at) = pendingRestore ?: return
            pendingRestore = null
            if (res != RESULT_OK) return
            Library.restore(listOf(it))
            if (items.none { x -> x === it }) items = items.toMutableList().apply { add(at.coerceIn(0, size), it) }
            listMode = listMode || items.size > 1
            showAt(items.indexOf(it))
            toast("Restored.")
            return
        }
        if (rc != RC_DELETE) return
        val it = pendingDelete ?: return
        pendingDelete = null
        if (res != RESULT_OK) return
        if (Build.VERSION.SDK_INT < 30) try { contentResolver.delete(it.uri, null, null) } catch (_: Exception) {}
        removed(it)
    }

    private fun removed(it: Item) {
        Library.remove(it)
        full.remove(key(it))
        val at = items.indexOf(it)
        items = items.filter { x -> x !== it }
        if (items.isEmpty()) { toast("Moved to trash."); listMode = false; super.finish(); return }
        showAt(i.coerceAtMost(items.size - 1))
        if (Build.VERSION.SDK_INT >= 30) {
            (undo.layoutParams as FrameLayout.LayoutParams).bottomMargin = (if (bottomBar.visibility == View.VISIBLE) bottomBar.height else 0) + dp(8)
            undo.requestLayout()
            undo.show("Moved to trash") { restore(it, at) }
        } else toast("Deleted.")
    }

    @Suppress("DEPRECATION")
    private fun restore(it: Item, at: Int) {
        pendingRestore = it to at
        try {
            val pi = MediaStore.createTrashRequest(contentResolver, listOf(it.uri), false)
            startIntentSenderForResult(pi.intentSender, RC_RESTORE, null, 0, 0, 0)
        } catch (e: Exception) {
            pendingRestore = null
            toast("Couldn't restore: ${e.message}")
        }
    }

    private fun details() {
        val it = items.getOrNull(i) ?: return
        bg {
            val text = describe(this, it)
            main.post {
                if (closed) return@post
                AlertDialog.Builder(this).setTitle("Details").setMessage(text).setPositiveButton("OK", null).show()
            }
        }
    }

    private fun describe(ctx: Context, it: Item): String {
        val rows = ArrayList<Pair<String, String>>()
        var w = 0
        var h = 0
        var taken = it.time
        try {
            val cols = if (it.inStore) arrayOf(
                MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.WIDTH,
                MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.ORIENTATION, MediaStore.MediaColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DURATION,
                MediaStore.MediaColumns.MIME_TYPE,
            ) else arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            ctx.contentResolver.query(it.uri, cols, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    fun s(n: String) = c.getColumnIndex(n).let { k -> if (k >= 0 && !c.isNull(k)) c.getString(k) else null }
                    fun l(n: String) = c.getColumnIndex(n).let { k -> if (k >= 0 && !c.isNull(k)) c.getLong(k) else 0L }
                    s(OpenableColumns.DISPLAY_NAME)?.let { v -> rows += "Name" to v }
                    if (it.inStore) {
                        val t = l(MediaStore.MediaColumns.DATE_TAKEN)
                        taken = if (t > 0) t else l(MediaStore.MediaColumns.DATE_MODIFIED) * 1000
                        w = l(MediaStore.MediaColumns.WIDTH).toInt()
                        h = l(MediaStore.MediaColumns.HEIGHT).toInt()
                        val o = l(MediaStore.MediaColumns.ORIENTATION).toInt()
                        if (o == 90 || o == 270) { val x = w; w = h; h = x }
                        s(MediaStore.MediaColumns.RELATIVE_PATH)?.let { v -> rows += "Folder" to v.trimEnd('/') }
                        val dur = l(MediaStore.MediaColumns.DURATION)
                        if (it.video && dur > 0) rows += "Length" to Paging.duration(dur)
                    }
                    val size = l(OpenableColumns.SIZE)
                    if (size > 0) rows += "Size" to Paging.bytes(size)
                }
            }
        } catch (_: Exception) {}
        if ((w <= 0 || h <= 0) && !it.video) try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(it.uri)?.use { s -> BitmapFactory.decodeStream(s, null, o) }
            w = o.outWidth; h = o.outHeight
        } catch (_: Exception) {}
        if (taken > 0) rows.add(minOf(1, rows.size), "Date" to dateFmt.format(Date(taken)))
        if (w > 0 && h > 0) rows += "Resolution" to "$w × $h  (${String.format(Locale.US, "%.1f", w.toLong() * h / 1e6)} MP)"
        rows += "Type" to it.mime
        return rows.joinToString("\n") { (k, v) -> "$k:  $v" }
    }

    /** Lets a sideways fling over the playing video move to the next / previous item. */
    class SwipeFrame(ctx: Context) : FrameLayout(ctx) {
        var onSwipe: ((Int) -> Unit)? = null
        private val g = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) * 1.5f || abs(vx) < 800 * resources.displayMetrics.density) return false
                onSwipe?.invoke(if (vx < 0) 1 else -1)
                return true
            }
        })

        // sees every touch (VideoView and its controls still get them too)
        override fun dispatchTouchEvent(e: MotionEvent): Boolean { g.onTouchEvent(e); super.dispatchTouchEvent(e); return true }
    }

    companion object {
        private const val RC_DELETE = 7
        private const val RC_RESTORE = 8
        /** Plain Camera's thumbnail: swipe through the camera roll (DCIM/Camera) only. */
        const val EXTRA_CAMERA_ROLL = "app.plain.gallery.extra.CAMERA_ROLL"
    }
}
