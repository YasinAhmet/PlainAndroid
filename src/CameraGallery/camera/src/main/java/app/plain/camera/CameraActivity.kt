package app.plain.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.params.TonemapCurve
import android.media.AudioManager
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaCodecList
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.provider.MediaStore
import android.provider.Settings
import android.util.Range
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A plain Camera2 camera: photo and video, captures the moment the shutter is pressed. Two tabs:
 * Basic (aspect, photo/video, everything automatic) and Advanced (manual controls, look, guides
 * and offline shot advice). Also answers other apps' IMAGE_CAPTURE / VIDEO_CAPTURE requests.
 */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {

    private enum class Mode { PHOTO, VIDEO }
    private enum class Caller { NONE, IMAGE, VIDEO }
    private class Shot(val time: Long, val rotation: Int, val look: Look? = null, val quality: Int = 95)

    private lateinit var prefs: SharedPreferences
    private lateinit var cm: CameraManager
    private val main = Handler(Looper.getMainLooper())
    private val saver = Executors.newSingleThreadExecutor()
    private var readerThread: HandlerThread? = null
    private var readerHandler: Handler? = null
    private val sound = MediaActionSound()

    // ---- views
    private lateinit var root: FrameLayout
    private lateinit var texture: TextureView
    private lateinit var ring: FocusRing
    private lateinit var blink: View
    private lateinit var topBar: LinearLayout
    private lateinit var bottomBar: LinearLayout
    private lateinit var aspectSp: Spinner
    private lateinit var basicTab: TextView
    private lateinit var advTab: TextView
    private lateinit var overlay: GuideOverlay
    private lateinit var hintView: TextView
    private lateinit var lockTag: TextView
    private lateinit var panel: LinearLayout
    private lateinit var chipScroll: HorizontalScrollView
    private val chips = LinkedHashMap<String, TextView>()
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView
    private lateinit var shutter: ShutterButton
    private lateinit var thumb: ImageView
    private lateinit var flipBtn: TextView
    private lateinit var timer: TextView
    private lateinit var message: TextView

    // ---- who started us
    private var caller = Caller.NONE
    private var outputUri: Uri? = null
    private var callerBusy = false
    private var secure = false
    private var maxDurationMs = 0
    private var maxBytes = 0L
    private var lowQuality = false

    // ---- settings
    private var mode = Mode.PHOTO
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var flash = 0 // photo: 0 off, 1 auto, 2 on · video: 0 off, 1 torch
    private val front get() = facing == CameraCharacteristics.LENS_FACING_FRONT
    /** The Advanced tab is showing; Basic ignores [adv] (and flash) completely. */
    private var advanced = false
    private var adv = Adv()
    private val eff get() = if (advanced) adv else BASIC
    private val fl get() = if (advanced) flash else 0
    private var openPanel: String? = null
    private var lookParam = 0
    private var toneParam = 0

    // ---- advanced: what this camera can do
    private var isoRange: Range<Int>? = null
    private var nsRange: Range<Long>? = null
    private var evRange: Range<Int>? = null
    private var minFocus = 0f
    private var awbModes = IntArray(0)
    private var sceneModes = IntArray(0)
    private var nrModes = IntArray(0)
    private var edgeModes = IntArray(0)
    private var curvePoints = 0
    private var manualSensor = false
    private var manualPost = false
    private var faceSimple = false
    private var aeLockOk = false
    private var awbLockOk = false
    private var backIds = emptyList<String>()
    private val manualExp get() = eff.manualExposure && manualSensor && isoRange != null && nsRange != null
    private val manualFocusOk get() = minFocus > 0f && CameraMetadata.CONTROL_AF_MODE_OFF in afModes

    // ---- advanced: live state
    private var aeLocked = false
    private var autoIso = 0
    private var autoNs = 0L
    private var lastIso = 0
    private var lastNs = 0L
    private var afState: Int? = null
    private var faces = emptyList<FaceBox>()
    private var stats: Stats? = null
    private var shake = 0f
    private var roll: Float? = null
    private val grav = FloatArray(3)
    private var gravFromAccel = false
    private var sensorsOn = false
    private var steadyWaiting = false
    private var verdictUntil = 0L
    private val gate = HintGate()
    private val wbRef = HashMap<String, Pair<RggbChannelVector, ColorSpaceTransform?>>()
    private var wbRefWanted = false
    private var wbRefFrames = 0
    private var aBmp: Bitmap? = null
    private var aPx: IntArray? = null

    // ---- camera
    private var camId: String? = null
    private var sensorOrientation = 90
    private var activeArray = Rect()
    private var hasFlash = false
    private var afModes = IntArray(0)
    private var maxAf = 0
    private var maxAe = 0
    private var fps30: Range<Int>? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var preview: Surface? = null
    private var opening = false
    private var resumed = false
    private var idlePaused = false
    private var photoSizes = emptyList<Dim>()
    private var videoSizes = emptyList<Dim>()
    private var previewSizes = emptyList<Dim>()
    private var aspects = emptyList<Aspect>()
    private var sizeList = emptyList<Dim>()
    private var aspect = Aspect.FOUR_THREE
    private var size = Dim(1920, 1080)
    private var previewDim = Dim(1440, 1080)
    private var screenRatio = 2.0
    private var deviceDeg = -1
    private var lastAe: Int? = null
    private var focusRegion: MeteringRectangle? = null
    // ---- tap to focus: a tap locks focus on that point until the marker is tapped again
    /** The locked point as fractions of the preview; null = continuous auto focus. */
    private var afLock: Pair<Float, Float>? = null
    /** 0 none · 1 trigger sent · 2 scanning · 3 locked (focused or not). */
    private var lockPhase = 0
    /** Where the lens settled when the lock completed (diopters, as the camera reports it). */
    private var lockDist: Float? = null
    /** After a session rebuild the lock is held as a fixed lens distance (AF off), so nothing refocuses. */
    private var lockPinned = false
    // ---- flash strength (Android 15+, when the camera has levels)
    private var singleMax = 1
    private var singleDef = 1
    private var torchMax = 1
    private var torchDef = 1
    private var precapture = 0 // 0 idle · 1 trigger sent · 2 waiting for metering to finish
    private val shots = ArrayDeque<Shot>()

    // ---- video
    private var recorder: MediaRecorder? = null
    private var recordSurface: Surface? = null
    private var recordPfd: ParcelFileDescriptor? = null
    private var recordUri: Uri? = null
    private var recordOwned = false
    private var recording = false
    private var recordStart = 0L

    private var lastUri: Uri? = null
    private var lastVideo = false

    // ---- profiles (named snapshots of the settings)
    private var profiles = emptyList<Profile>()
    private var activeProfile: String? = null

    private val orient by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(o: Int) { if (o != ORIENTATION_UNKNOWN) deviceDeg = o }
        }
    }

    // ================================================================== lifecycle
    override fun onCreate(b: Bundle?) {
        PlainLang.init(this)
        super.onCreate(b)
        prefs = getSharedPreferences("camera", MODE_PRIVATE)
        Jpeg.prefs = prefs
        cm = getSystemService(CameraManager::class.java)
        readIntent(intent)
        mode = when {
            caller == Caller.IMAGE -> Mode.PHOTO
            caller == Caller.VIDEO -> Mode.VIDEO
            intent.action == MediaStore.INTENT_ACTION_VIDEO_CAMERA -> Mode.VIDEO
            intent.action == MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA || secure -> Mode.PHOTO
            else -> if (prefs.getString("mode", "PHOTO") == "VIDEO") Mode.VIDEO else Mode.PHOTO
        }
        facing = prefs.getInt("facing", CameraCharacteristics.LENS_FACING_BACK)
        flash = prefs.getInt(flashKey(), 0)
        adv = Adv.parse(prefs.getString("adv", null))
        // other apps asking for a photo or video always start in Basic
        advanced = caller == Caller.NONE && prefs.getBoolean("advanced", false)
        if (secure) setShowWhenLocked(true)
        screenRatio = realScreenRatio()
        buildUi()
        // back from a recreate (language change): reopen the panel that was showing once the camera is up
        openPanel = b?.getString("panel")
        sound.load(MediaActionSound.SHUTTER_CLICK)
        profiles = Profiles.parse(prefs.getString("profiles", null))
        activeProfile = prefs.getString("profile", null)?.takeIf { Profiles.find(profiles, it) != null }
        if (!hasCam()) requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("panel", openPanel)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orient.enable()
        readerThread = HandlerThread("jpeg").also { it.start(); readerHandler = Handler(it.looper) }
        startCamera()
        poke()
        syncAdvanced()
        syncThumb()
    }

    /**
     * Points the last-capture thumbnail at our newest capture that still exists. Items trashed or
     * deleted in the gallery meanwhile drop out of the media store's answer, so the thumbnail moves
     * to the one before (or goes away). One small query on the saver thread, only on resume.
     */
    private fun syncThumb() {
        if (caller != Caller.NONE || secure) return
        val have = lastUri.takeIf { thumb.drawable != null }
        try {
            saver.execute {
                val l = try { Media.latestOwn(this) } catch (_: Exception) { return@execute }
                val bmp = if (l == null || l.first == have) null else Media.thumb(this, l.first, dp(96))
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (l == null) { lastUri = null; thumb.setImageDrawable(null) }
                    else if (l.first != lastUri || bmp != null) { lastUri = l.first; lastVideo = l.second; if (bmp != null) thumb.setImageBitmap(bmp) }
                    updateUi()
                }
            }
        } catch (_: RejectedExecutionException) {}
    }

    override fun onPause() {
        resumed = false
        orient.disable()
        stopAdvanced()
        main.removeCallbacks(idle)
        main.removeCallbacks(clearFocus)
        main.removeCallbacks(precaptureTimeout)
        precapture = 0
        if (recording) stopRecording(reopen = false)
        // a photo still on its way from the sensor: give it a moment before closing
        val pending = synchronized(shots) { shots.isNotEmpty() }
        val t = readerThread
        if (pending) main.postDelayed({ if (!resumed) { closeCamera(); t?.quitSafely() } }, 1500)
        else { closeCamera(); t?.quitSafely() }
        readerThread = null
        super.onPause()
    }

    override fun onDestroy() {
        saver.shutdown() // pending saves still finish
        sound.release()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        if (hasCam()) { hideMessage(); startCamera() }
        else showMessage(tr("Camera permission is needed.\nTap here to allow it.", "Kamera izni gerekli.\nİzin vermek için buraya dokunun.")) {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
                requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 1)
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun hasCam() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun readIntent(i: Intent) {
        when (i.action) {
            MediaStore.ACTION_IMAGE_CAPTURE -> caller = Caller.IMAGE
            MediaStore.ACTION_IMAGE_CAPTURE_SECURE -> { caller = Caller.IMAGE; secure = true }
            MediaStore.ACTION_VIDEO_CAPTURE -> caller = Caller.VIDEO
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE -> secure = true
        }
        if (caller != Caller.NONE) {
            outputUri = i.getParcelableExtra(MediaStore.EXTRA_OUTPUT)
            maxDurationMs = i.getIntExtra(MediaStore.EXTRA_DURATION_LIMIT, 0) * 1000
            maxBytes = i.getLongExtra(MediaStore.EXTRA_SIZE_LIMIT, 0L)
            lowQuality = i.getIntExtra(MediaStore.EXTRA_VIDEO_QUALITY, 1) == 0
        }
    }

    // ================================================================== battery: idle pause
    private val idle = Runnable {
        if (!recording && device != null && caller == Caller.NONE) {
            closeCamera()
            stopAdvanced()
            idlePaused = true
            showMessage(tr("Paused to save battery.\nTap to continue.", "Pil tasarrufu için duraklatıldı.\nDevam etmek için dokunun.")) { idlePaused = false; hideMessage(); startCamera(); syncAdvanced() }
        }

    }

    private fun poke() {
        main.removeCallbacks(idle)
        if (resumed) main.postDelayed(idle, 120_000)
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean { poke(); return super.dispatchTouchEvent(e) }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || code == KeyEvent.KEYCODE_CAMERA) {
            poke()
            if (e.repeatCount == 0 && device != null) onShutter()
            return true
        }
        return super.onKeyDown(code, e)
    }

    // ================================================================== UI
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun label(t: String, sizeSp: Float = 14f) = TextView(this).apply {
        text = t
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        texture = TextureView(this).apply { surfaceTextureListener = this@CameraActivity }
        root.addView(texture, FrameLayout.LayoutParams(1, 1, Gravity.CENTER_HORIZONTAL))
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var dx = 0f
        var dy = 0f
        var held = false
        val hold = Runnable {
            held = true
            texture.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleLock()
        }
        texture.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx = e.x; dy = e.y; held = false
                    if (advanced && !manualExp && (aeLockOk || awbLockOk)) main.postDelayed(hold, 550)
                }
                MotionEvent.ACTION_MOVE -> if (abs(e.x - dx) >= slop || abs(e.y - dy) >= slop) main.removeCallbacks(hold)
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(hold)
                    if (!held && abs(e.x - dx) < slop && abs(e.y - dy) < slop) onPreviewTap(e.x, e.y, v.width, v.height)
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(hold)
            }
            true
        }
        overlay = GuideOverlay(this)
        root.addView(overlay, FrameLayout.LayoutParams(1, 1, Gravity.CENTER_HORIZONTAL))
        ring = FocusRing(this)
        root.addView(ring, FrameLayout.LayoutParams(-1, -1))
        blink = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        root.addView(blink, FrameLayout.LayoutParams(-1, -1))

        // top: aspect · BASIC | ADVANCED
        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        aspectSp = dropdown()
        basicTab = label(tr("BASIC", "TEMEL"), 13f).apply { setOnClickListener { setAdvanced(false) } }
        advTab = label(tr("ADVANCED", "GELİŞMİŞ"), 13f).apply { setOnClickListener { setAdvanced(true) } }
        topBar.addView(aspectSp, LinearLayout.LayoutParams(-2, dp(44)))
        topBar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        topBar.addView(basicTab, LinearLayout.LayoutParams(-2, dp(44)))
        topBar.addView(advTab, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        hintView = label("", 13f).apply {
            visibility = View.GONE
            isSingleLine = false
            maxLines = 2
            setTextColor(TEXT)
            background = outline(BTN_LINE, dp(14).toFloat(), dp(1))
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        root.addView(hintView, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { marginStart = dp(16); marginEnd = dp(16) })
        lockTag = label("AE-L", 12f).apply {
            visibility = View.GONE
            setTextColor(ACCENT)
            background = outline(ACCENT, dp(10).toFloat(), dp(1))
            setPadding(dp(8), dp(2), dp(8), dp(2))
            contentDescription = tr("Exposure locked. Tap to unlock.", "Pozlama kilitli. Açmak için dokunun.")
            setOnClickListener { if (aeLocked) toggleLock() }
        }
        root.addView(lockTag, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { marginStart = dp(12) })

        timer = label("", 16f).apply {
            visibility = View.GONE
            setTextColor(0xFFEF5350.toInt())
            background = outline(RED, dp(14).toFloat(), dp(1))
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        root.addView(timer, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(60) })

        // bottom: PHOTO | VIDEO, then last capture · shutter · flip
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x66000000)
            setPadding(dp(16), dp(4), dp(16), dp(16))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(2), 0, dp(4))
        }
        bottomBar.addView(panel, LinearLayout.LayoutParams(-1, -2))
        val chipRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(4), 0, dp(2)) }
        for (k in CHIPS) {
            val c = chip(k) { showPanel(if (openPanel == k) null else k) }
            chips[k] = c
            chipRow.addView(c, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        }
        chipScroll = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(chipRow) }
        bottomBar.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }
        photoTab = label(tr("PHOTO", "FOTOĞRAF"), 13f).apply { setOnClickListener { setMode(Mode.PHOTO) } }
        videoTab = label(tr("VIDEO", "VİDEO"), 13f).apply { setOnClickListener { setMode(Mode.VIDEO) } }
        tabs.addView(photoTab)
        tabs.addView(videoTab)
        bottomBar.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        thumb = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ring(BTN_LINE, dp(1))
            clipToOutline = true
            contentDescription = tr("Last capture", "Son çekim")
            setOnClickListener { openLast() }
        }
        shutter = ShutterButton(this).apply { setOnClickListener { onShutter() } }
        flipBtn = label(tr("Flip", "Çevir"), 13f).apply {
            background = ring(BTN_LINE, dp(1))
            setOnClickListener { flip() }
        }
        row.addView(thumb, LinearLayout.LayoutParams(dp(54), dp(54)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(shutter, LinearLayout.LayoutParams(dp(78), dp(78)))
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        row.addView(flipBtn, LinearLayout.LayoutParams(dp(54), dp(54)))
        bottomBar.addView(row, LinearLayout.LayoutParams(-1, -2))
        root.addView(bottomBar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        message = label("", 16f).apply {
            visibility = View.GONE
            setTextColor(TEXT2)
            setBackgroundColor(Color.BLACK)
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -1))

        // keep the controls clear of the navigation bar (and the cutout on edge-to-edge Android versions)
        root.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION")
            val bottom = ins.systemWindowInsetBottom
            @Suppress("DEPRECATION")
            val top = ins.systemWindowInsetTop
            bottomBar.setPadding(dp(16), dp(4), dp(16), dp(16) + bottom)
            (topBar.layoutParams as FrameLayout.LayoutParams).topMargin = top
            ins
        }
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) layoutPreview() }
        setContentView(root)
        updateUi()
    }

    private fun dropdown() = Spinner(this, Spinner.MODE_DROPDOWN).apply {
        setPopupBackgroundDrawable(outline(BTN_LINE, dp(8).toFloat(), dp(1)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { onPicked(this@apply, pos) }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setItems(sp: Spinner, items: List<String>, sel: Int) {
        val ad = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getView(pos, cv, parent) as TextView).apply {
                    setTextColor(TEXT); setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); isSingleLine = true
                }
            override fun getDropDownView(pos: Int, cv: View?, parent: ViewGroup): View =
                (super.getDropDownView(pos, cv, parent) as TextView).apply {
                    setTextColor(if (pos == sp.selectedItemPosition) ACCENT else TEXT2)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                }
        }
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sp.adapter = ad
        if (sel in items.indices) sp.setSelection(sel, false)
    }

    private fun onPicked(sp: Spinner, pos: Int) {
        if (sp !== aspectSp) return
        val a = aspects.getOrNull(pos) ?: return
        if (a == aspect) return
        prefs.edit().putString(aspectKey(), a.name).apply()
        reconfigure()
    }

    private fun updateUi() {
        val idleUi = !recording
        aspectSp.isEnabled = idleUi
        basicTab.setTextColor(if (!advanced) ACCENT else LABEL)
        advTab.setTextColor(if (advanced) ACCENT else LABEL)
        basicTab.isEnabled = idleUi
        advTab.isEnabled = idleUi
        chipScroll.visibility = if (advanced) View.VISIBLE else View.GONE
        if (!advanced && openPanel != null) showPanel(null) else refreshChips()
        lockTag.visibility = if (advanced && aeLocked && !manualExp) View.VISIBLE else View.GONE
        overlay.grid = eff.grid
        if (!eff.faces) overlay.faces = emptyList()
        if (!eff.level) overlay.roll = null
        if (!advanced || !eff.hints) { hintView.visibility = View.GONE; shutter.ready = null }
        texture.setLayerPaint(if (advanced && mode == Mode.PHOTO && !adv.look.neutral)
            Paint().apply { colorFilter = ColorMatrixColorFilter(adv.look.matrix(vibranceAsSat = true)) } else null)

        val tabsShown = caller == Caller.NONE
        photoTab.visibility = if (tabsShown) View.VISIBLE else View.GONE
        videoTab.visibility = if (tabsShown) View.VISIBLE else View.GONE
        photoTab.setTextColor(if (mode == Mode.PHOTO) ACCENT else LABEL)
        videoTab.setTextColor(if (mode == Mode.VIDEO) ACCENT else LABEL)
        photoTab.isEnabled = idleUi
        videoTab.isEnabled = idleUi
        flipBtn.isEnabled = idleUi
        flipBtn.alpha = if (idleUi) 1f else 0.4f
        shutter.video = mode == Mode.VIDEO
        shutter.recording = recording
        thumb.visibility = if (caller == Caller.NONE && !secure && lastUri != null && idleUi) View.VISIBLE else View.INVISIBLE
    }

    private fun showMessage(t: String, onTap: (() -> Unit)? = null) {
        message.text = t
        message.visibility = View.VISIBLE
        message.setOnClickListener(if (onTap != null) View.OnClickListener { onTap() } else null)
        message.isClickable = onTap != null
    }

    private fun hideMessage() { message.visibility = View.GONE }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    @Suppress("DEPRECATION")
    private fun realScreenRatio(): Double {
        val p = android.graphics.Point()
        windowManager.defaultDisplay.getRealSize(p)
        return max(p.x, p.y).toDouble() / max(1, min(p.x, p.y))
    }

    /** Fits the preview's shape across the screen (portrait), just under the top bar when there's room. */
    private fun layoutPreview() {
        val w0 = root.width
        val h0 = root.height
        if (w0 == 0 || h0 == 0) return
        val r = previewDim.ratio
        var w = w0
        var h = (w0 * r).roundToInt()
        if (h > h0) { h = h0; w = (h0 / r).roundToInt() }
        val top = min(dp(52) + topInset(), h0 - h).coerceAtLeast(0)
        val lp = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        texture.layoutParams = lp
        overlay.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top }
        (hintView.layoutParams as FrameLayout.LayoutParams).topMargin = max(top, dp(52) + topInset()) + dp(40)
        (lockTag.layoutParams as FrameLayout.LayoutParams).topMargin = max(top, dp(52) + topInset()) + dp(8)
        hintView.requestLayout()
        lockTag.requestLayout()
        afLock?.let { (fx, fy) -> ring.moveLock((w0 - w) / 2f + fx * w, top + fy * h) }
    }

    @Suppress("DEPRECATION")
    private fun topInset() = root.rootWindowInsets?.systemWindowInsetTop ?: 0

    /** Opens the last capture in Plain Gallery (swipe through the camera roll there); any gallery when it's missing. */
    private fun openLast() {
        val u = lastUri ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, if (lastVideo) "video/mp4" else "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(Intent(view).setPackage(GALLERY_PKG).putExtra(EXTRA_CAMERA_ROLL, true))
            return
        } catch (_: Exception) {}
        try { startActivity(view) } catch (_: Exception) { toast(tr("No gallery app found.", "Galeri uygulaması bulunamadı.")) }
    }

    private fun shutterFeedback(what: Int) {
        val am = getSystemService(AudioManager::class.java)
        val must = Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound()
        if (must || am.ringerMode == AudioManager.RINGER_MODE_NORMAL) sound.play(what)
        if (what == MediaActionSound.SHUTTER_CLICK) {
            blink.animate().cancel()
            blink.alpha = 0.7f
            blink.animate().alpha(0f).setDuration(160).start()
        }
    }

    // ================================================================== settings
    private fun m() = if (mode == Mode.PHOTO) "photo" else "video"
    private fun flashKey() = "flash.${m()}"
    private fun aspectKey() = "aspect.${m()}.$facing"
    // "asize": the Advanced tab's own choice (the old "size" keys came from the pre-tabs dropdown)
    private fun sizeKey(a: Aspect) = "asize.${m()}.$facing.${a.name}"

    private fun setFlash(f: Int) {
        flash = f
        prefs.edit().putInt(flashKey(), flash).apply()
        updateUi()
        repeat()
    }

    private fun setMode(md: Mode) {
        if (md == mode || recording) return
        mode = md
        prefs.edit().putString("mode", md.name).apply()
        flash = prefs.getInt(flashKey(), 0)
        reconfigure()
        if (openPanel != null) showPanel(openPanel)
        syncAdvanced()
    }

    private fun flip() {
        if (recording) return
        facing = if (front) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        prefs.edit().putInt("facing", facing).apply()
        if (openPanel == "Lens") showPanel(null)
        closeCamera()
        startCamera()
    }

    /** Picks aspect + size from the saved settings and what the sensor offers. */
    private fun chooseSizes() {
        val all = if (mode == Mode.PHOTO) photoSizes else videoSizes
        aspects = Sizes.aspects(all, screenRatio)
        val def = if (mode == Mode.PHOTO) Aspect.FOUR_THREE else Aspect.SIXTEEN_NINE
        val saved = prefs.getString(aspectKey(), def.name)
        aspect = aspects.firstOrNull { it.name == saved } ?: aspects.firstOrNull { it == def } ?: aspects.firstOrNull() ?: def
        sizeList = Sizes.forAspect(all, aspect, screenRatio).ifEmpty { all.sortedByDescending { it.area } }
        val want = Dim.parse(prefs.getString(sizeKey(aspect), null))
        size = when {
            caller == Caller.VIDEO && lowQuality -> sizeList.lastOrNull()
            advanced && want != null && want in sizeList -> want
            mode == Mode.VIDEO -> Sizes.defaultVideo(sizeList)
            else -> sizeList.firstOrNull()
        } ?: Dim(1280, 720)
        previewDim = Sizes.preview(previewSizes, size.ratio) ?: Dim(1280, 720)
        setItems(aspectSp, aspects.map { it.display }, aspects.indexOf(aspect))
    }

    private fun reconfigure() {
        chooseSizes()
        layoutPreview()
        updateUi()
        main.removeCallbacks(clearFocus)
        val lk = afLock
        if (lk != null && afLockOk) focusRegion = regionAt(lk.first, lk.second)
        else { focusRegion = null; dropLock() }
        if (device != null) createSession()
    }

    // ================================================================== camera
    private fun camerasFacing(f: Int): List<String> = cm.cameraIdList.filter { id ->
        val c = cm.getCameraCharacteristics(id)
        c.get(CameraCharacteristics.LENS_FACING) == f &&
            c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true
    }

    private fun pickCamera(f: Int): String? = camerasFacing(f).firstOrNull()

    private fun loadCamera(id: String) {
        val c = cm.getCameraCharacteristics(id)
        camId = id
        sensorOrientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        activeArray = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: Rect(0, 0, 4000, 3000)
        hasFlash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        fps30 = ranges.firstOrNull { it.lower == 30 && it.upper == 30 } ?: ranges.filter { it.upper == 30 }.maxByOrNull { it.lower }
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        photoSizes = (map.getOutputSizes(ImageFormat.JPEG).orEmpty().toList() + map.getHighResolutionOutputSizes(ImageFormat.JPEG).orEmpty().toList())
            .map { Dim(it.width, it.height) }.distinct()
        previewSizes = map.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { Dim(it.width, it.height) }
        val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { t -> t.equals("video/avc", true) } }
            .mapNotNull { try { it.getCapabilitiesForType("video/avc").videoCapabilities } catch (_: Exception) { null } }
        videoSizes = map.getOutputSizes(MediaRecorder::class.java).orEmpty().map { Dim(it.width, it.height) }
            .filter { d ->
                // encoders often report 16-pixel alignment, yet record 1920x1080 fine (padded to 1088)
                d.long <= 3840 && codecs.any { v ->
                    try { v.areSizeAndRateSupported(d.w, d.h, 30.0) || v.areSizeAndRateSupported(up16(d.w), up16(d.h), 30.0) } catch (_: Exception) { false }
                }
            }
            .distinct()
        if (mode == Mode.PHOTO && flash > 2) flash = 0
        // what the Advanced tab can offer on this camera
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        manualSensor = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        manualPost = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in caps
        isoRange = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        nsRange = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.takeIf { it.upper > it.lower }
        minFocus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        awbModes = c.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES) ?: IntArray(0)
        sceneModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES) ?: IntArray(0)
        nrModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: IntArray(0)
        edgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: IntArray(0)
        val tm = c.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: IntArray(0)
        curvePoints = if (CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE in tm) (c.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 0) else 0
        faceSimple = CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE in (c.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: IntArray(0))
        aeLockOk = c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        // flash strength levels: Android 15+ only, and only when the camera offers more than one
        singleMax = 1; singleDef = 1; torchMax = 1; torchDef = 1
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && hasFlash) {
            singleMax = c.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_MAX_LEVEL) ?: 1
            singleDef = c.get(CameraCharacteristics.FLASH_SINGLE_STRENGTH_DEFAULT_LEVEL) ?: 1
            torchMax = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_MAX_LEVEL) ?: 1
            torchDef = c.get(CameraCharacteristics.FLASH_TORCH_STRENGTH_DEFAULT_LEVEL) ?: 1
        }
        awbLockOk = c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true
        backIds = camerasFacing(CameraCharacteristics.LENS_FACING_BACK)
    }

    private fun up16(v: Int) = (v + 15) / 16 * 16

    private fun startCamera() {
        if (!hasCam() || !resumed || idlePaused || !texture.isAvailable || device != null || opening) return
        val id = try {
            (if (!front && advanced && adv.lens > 0) camerasFacing(facing).getOrNull(adv.lens) else null)
                ?: pickCamera(facing) ?: pickCamera(if (front) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT)
        } catch (e: Exception) { null }
        if (id == null) { showMessage(tr("No camera found.", "Kamera bulunamadı.")); return }
        try {
            loadCamera(id)
            facing = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ?: facing
        } catch (e: Exception) { showMessage(tr("Can't read the camera: ${e.message}", "Kamera okunamıyor: ${e.message}")); return }
        flipBtn.visibility = if (pickCamera(CameraCharacteristics.LENS_FACING_FRONT) != null && pickCamera(CameraCharacteristics.LENS_FACING_BACK) != null) View.VISIBLE else View.INVISIBLE
        chooseSizes()
        layoutPreview()
        updateUi()
        if (openPanel != null) showPanel(openPanel)
        opening = true
        try {
            cm.openCamera(id, stateCb, main)
        } catch (e: SecurityException) {
            opening = false
        } catch (e: Exception) {
            opening = false
            showMessage(tr("Can't open the camera:\n${e.message}", "Kamera açılamıyor:\n${e.message}"))
        }
    }

    private val stateCb = object : CameraDevice.StateCallback() {
        override fun onOpened(d: CameraDevice) {
            opening = false
            if (!resumed || idlePaused) { d.close(); return }
            device = d
            hideMessage()
            createSession()
        }

        override fun onDisconnected(d: CameraDevice) {
            opening = false
            d.close()
            if (device === d) { device = null; session = null }
        }

        override fun onError(d: CameraDevice, error: Int) {
            opening = false
            d.close()
            if (device === d) { device = null; session = null }
            showMessage(if (error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE)
                tr("Another app is using the camera.\nTap to try again.", "Kamerayı başka bir uygulama kullanıyor.\nTekrar denemek için dokunun.")
            else tr("The camera stopped (error $error).\nTap to try again.", "Kamera durdu (hata $error).\nTekrar denemek için dokunun.")) { hideMessage(); startCamera() }
        }
    }

    private fun closeCamera() {
        if (recording) stopRecording(reopen = false)
        try { session?.close() } catch (_: Exception) {}
        session = null
        try { device?.close() } catch (_: Exception) {}
        device = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        synchronized(shots) { shots.clear() }
        precapture = 0
        focusRegion = null
        main.removeCallbacks(clearFocus)
        dropLock()
        aeLocked = false
        faces = emptyList()
        overlay.faces = emptyList()
        stats = null
        steadyWaiting = false
        main.removeCallbacks(steadyTimeout)
    }


    /** Preview + (photo) JPEG reader or (recording) the video encoder's surface. */
    private fun createSession(then: (() -> Unit)? = null) {
        val d = device ?: return
        val st = texture.surfaceTexture ?: return
        try { session?.close() } catch (_: Exception) {}
        session = null
        st.setDefaultBufferSize(previewDim.w, previewDim.h)
        val pv = preview ?: Surface(st).also { preview = it }
        val outs = mutableListOf(pv)
        if (mode == Mode.PHOTO) {
            val r = reader
            if (r == null || r.width != size.w || r.height != size.h) {
                try { r?.close() } catch (_: Exception) {}
                reader = ImageReader.newInstance(size.w, size.h, ImageFormat.JPEG, 3).apply { setOnImageAvailableListener(onImage, readerHandler) }
            }
            outs += reader!!.surface
        } else {
            try { reader?.close() } catch (_: Exception) {}
            reader = null
        }
        recordSurface?.let { outs += it }
        val cfg = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outs.map { OutputConfiguration(it) }, mainExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device !== d) { s.close(); return }
                    session = s
                    // a completed focus lock survives the rebuild as a fixed lens distance; one still
                    // scanning is simply started again on the same point
                    if (afLock != null && lockPhase == 3 && lockDist != null && manualFocusOk) lockPinned = true
                    repeat()
                    if (afLock != null && lockPhase in 1..2 && afLockOk) { lockPhase = 1; triggerAf() }
                    then?.invoke()
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    s.close()
                    if (recorder != null) abortRecording()
                    toast(tr("This camera can't use ${size.w}×${size.h} here. Pick another size.", "Bu kamera burada ${size.w}×${size.h} kullanamıyor. Başka bir boyut seçin."))
                }
            })
        try { d.createCaptureSession(cfg) } catch (e: Exception) { toast(tr("Camera setup failed: ${e.message}", "Kamera kurulamadı: ${e.message}")) }
    }

    private fun common(b: CaptureRequest.Builder) {
        val a = eff
        val manual = manualExp
        val scene = a.scene != 0 && a.scene in sceneModes && !manual && a.wb == Adv.WB_AUTO
        b.set(CaptureRequest.CONTROL_MODE, if (scene) CameraMetadata.CONTROL_MODE_USE_SCENE_MODE else CameraMetadata.CONTROL_MODE_AUTO)
        if (scene) b.set(CaptureRequest.CONTROL_SCENE_MODE, a.scene)
        val region = focusRegion
        val cont = if (mode == Mode.VIDEO) CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val pinned = lockDist
        if (a.manualFocus && manualFocusOk) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, a.focus * minFocus)
        } else if (afLock != null && lockPinned && pinned != null && manualFocusOk) {
            // focus lock carried over a session rebuild: hold the distance the lock found
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, pinned)
        } else if (afLock != null && region != null && maxAf > 0 && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes) {
            // locked: AUTO without a trigger never moves the lens (captures don't refocus either)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
        } else if (cont in afModes) {
            b.set(CaptureRequest.CONTROL_AF_MODE, cont)
        }
        if (region != null && maxAe > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
        if (manual) {
            val ir = isoRange!!
            val nr = nsRange!!
            val (iso, ns) = Adv.exposure(a, autoIso, autoNs, ir.lower, ir.upper, nr.lower, nr.upper, mode == Mode.VIDEO)
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(ns, Adv.FRAME_30))
            if (mode == Mode.VIDEO) {
                b.set(CaptureRequest.FLASH_MODE, if (hasFlash && fl == 1) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
                if (hasFlash && fl == 1) strength(b, torch = true)
            }
        } else if (mode == Mode.PHOTO) {
            b.set(CaptureRequest.CONTROL_AE_MODE, if (!hasFlash) CameraMetadata.CONTROL_AE_MODE_ON else when (fl) {
                1 -> CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH
                2 -> CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CameraMetadata.CONTROL_AE_MODE_ON
            })
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (hasFlash && fl == 1) CameraMetadata.FLASH_MODE_TORCH else CameraMetadata.FLASH_MODE_OFF)
            if (hasFlash && fl == 1) strength(b, torch = true)
            fps30?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        }
        if (!advanced) return
        // ---- Advanced only
        if (!manual) {
            evRange?.let { if (a.ev != 0) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, a.ev.coerceIn(it.lower, it.upper)) }
            if (aeLockOk) b.set(CaptureRequest.CONTROL_AE_LOCK, aeLocked)
        }
        if (awbLockOk) b.set(CaptureRequest.CONTROL_AWB_LOCK, aeLocked && a.wb == Adv.WB_AUTO)
        val id = camId
        if (a.wb == Adv.WB_KELVIN && manualPost && id != null) {
            val ref = wbRef[id]
            if (ref != null) {
                val (rf, bf) = Adv.kelvinFactors(a.kelvin)
                val g = ref.first
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(g.red * rf, g.greenEven, g.greenOdd, g.blue * bf))
                ref.second?.let { b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, it) }
            } else {
                // learn this camera's daylight gains first, then work from them
                b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT)
                if (!wbRefWanted) { wbRefWanted = true; wbRefFrames = 0 }
            }
        } else if (a.wb != Adv.WB_AUTO && a.wb in awbModes) {
            b.set(CaptureRequest.CONTROL_AWB_MODE, a.wb)
        }
        if (a.toneSet && curvePoints >= 2) {
            val pts = Adv.toneCurve(a.contrast, a.shadows, min(curvePoints, 64))
            b.set(CaptureRequest.TONEMAP_MODE, CameraMetadata.TONEMAP_MODE_CONTRAST_CURVE)
            b.set(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
        }
        when (a.sharpen) {
            1 -> CameraMetadata.EDGE_MODE_OFF
            2 -> CameraMetadata.EDGE_MODE_FAST
            3 -> CameraMetadata.EDGE_MODE_HIGH_QUALITY
            else -> null
        }?.takeIf { it in edgeModes }?.let { b.set(CaptureRequest.EDGE_MODE, it) }
        when (a.noise) {
            1 -> CameraMetadata.NOISE_REDUCTION_MODE_OFF
            2 -> if (CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL in nrModes) CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL else CameraMetadata.NOISE_REDUCTION_MODE_FAST
            3 -> CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY
            else -> null
        }?.takeIf { it in nrModes }?.let { b.set(CaptureRequest.NOISE_REDUCTION_MODE, it) }
        if ((a.faces || a.hints) && faceSimple) b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_SIMPLE)
    }

    /** Flash / torch strength chosen in Advanced (Android 15+, cameras with levels); otherwise the default. */
    private fun strength(b: CaptureRequest.Builder, torch: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM || !advanced || adv.flashPct <= 0) return
        val max = if (torch) torchMax else singleMax
        if (max <= 1) return
        b.set(CaptureRequest.FLASH_STRENGTH_LEVEL, Adv.flashLevel(adv.flashPct, max, if (torch) torchDef else singleDef))
    }

    /**
     * Flash On with a chosen strength: Android resets the strength whenever auto exposure drives the
     * flash, so the photo fires a plain flash at that strength over the metered daylight exposure.
     */
    private val manualFlash get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && advanced &&
        mode == Mode.PHOTO && hasFlash && fl == 2 && adv.flashPct > 0 && singleMax > 1 && !manualExp

    private fun request(template: Int): CaptureRequest.Builder? {
        val d = device ?: return null
        val pv = preview ?: return null
        return d.createCaptureRequest(template).apply {
            addTarget(pv)
            recordSurface?.let { addTarget(it) }
            common(this)
        }
    }

    private fun repeat() {
        val s = session ?: return
        try {
            val b = request(if (recordSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW) ?: return
            s.setRepeatingRequest(b.build(), results, main)
        } catch (_: Exception) {}
    }

    private val results = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, req: CaptureRequest, r: TotalCaptureResult) {
            val ae = r.get(CaptureResult.CONTROL_AE_STATE)
            lastAe = ae
            readResult(r)

            if (req.tag == AF_START && lockPhase == 1) lockPhase = 2
            else if (lockPhase == 2 && afLock != null) {
                val af = r.get(CaptureResult.CONTROL_AF_STATE)
                if (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                    lockPhase = 3
                    lockDist = r.get(CaptureResult.LENS_FOCUS_DISTANCE)
                    ring.failed = af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                }
            }
            when (precapture) {
                1 -> if (req.tag == PRE) precapture = 2
                2 -> if (ae == null || ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE) capture()
            }
        }
    }

    // ---- tap to focus (locks) / meter
    /** Tap focus can lock on this camera right now (it has AF regions and isn't on manual focus). */
    private val afLockOk get() = maxAf > 0 && CameraMetadata.CONTROL_AF_MODE_AUTO in afModes && !(eff.manualFocus && manualFocusOk)
    private fun afTemplate() = if (recordSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW

    /** Tap on the locked marker: back to continuous focus. Anywhere else: focus and lock there. */
    private fun onPreviewTap(x: Float, y: Float, w: Int, h: Int) {
        if (device == null || session == null || w == 0 || h == 0) return
        val lk = afLock
        if (lk != null && hypot(x - lk.first * w, y - lk.second * h) < dp(56)) { unlockFocus(); return }
        focusAt(x / w, y / h)
    }

    /** A metering / focus rectangle around the preview point ([fx], [fy] = 0..1). */
    private fun regionAt(fx: Float, fy: Float): MeteringRectangle {
        val (sx, sy) = Sizes.viewToSensor(fx, fy, sensorOrientation, front)
        val c = Sizes.crop(activeArray.width(), activeArray.height(), previewDim.ratio)
        val cx = c[0] + (sx * c[2]).toInt()
        val cy = c[1] + (sy * c[3]).toInt()
        val half = (min(c[2], c[3]) * 0.08).toInt()
        val l = (cx - half).coerceIn(0, activeArray.width() - 1)
        val t = (cy - half).coerceIn(0, activeArray.height() - 1)
        val rr = (cx + half).coerceIn(l + 1, activeArray.width())
        val bb = (cy + half).coerceIn(t + 1, activeArray.height())
        return MeteringRectangle(l, t, rr - l, bb - t, MeteringRectangle.METERING_WEIGHT_MAX - 1)
    }

    /** Screen position (in [root]) of a preview point, from the preview's layout. */
    private fun markerAt(fx: Float, fy: Float): Pair<Float, Float> {
        val lp = texture.layoutParams as FrameLayout.LayoutParams
        return (root.width - lp.width) / 2f + fx * lp.width to lp.topMargin + fy * lp.height
    }

    private fun focusAt(fx: Float, fy: Float) {
        if (maxAf == 0 && maxAe == 0) return
        main.removeCallbacks(clearFocus)
        focusRegion = regionAt(fx, fy)
        val (mx, my) = markerAt(fx, fy)
        if (afLockOk) {
            // AUTO mode + one trigger: the lens focuses once and then stays put (no continuous
            // refocusing, no timeout, moving the phone changes nothing) until the marker is tapped again
            afLock = fx to fy
            lockPhase = 1
            lockDist = null
            lockPinned = false
            repeat()
            triggerAf()
            ring.lock(mx, my)
        } else {
            // fixed-focus camera or manual focus: just meter there for a few seconds
            dropLock()
            repeat()
            ring.show(mx, my)
            main.postDelayed(clearFocus, 5000)
        }
    }

    private fun triggerAf() {
        val s = session ?: return
        try {
            val cancel = request(afTemplate()) ?: return
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            s.capture(cancel.build(), null, main)
            val start = request(afTemplate()) ?: return
            start.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            start.setTag(AF_START)
            s.capture(start.build(), results, main)
        } catch (_: Exception) {}
    }

    /** Forgets the focus lock (no camera request). */
    private fun dropLock() {
        afLock = null
        lockPhase = 0
        lockDist = null
        lockPinned = false
        ring.unlock()
    }

    /** Back to continuous auto focus (and metering over the whole frame). */
    private fun unlockFocus() {
        main.removeCallbacks(clearFocus)
        dropLock()
        focusRegion = null
        try {
            val b = request(afTemplate())
            b?.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            if (b != null) session?.capture(b.build(), null, main)
        } catch (_: Exception) {}
        repeat()
    }

    /** Ends a metering-only spot (fixed focus / manual focus). */
    private val clearFocus = Runnable {
        if (focusRegion == null || afLock != null) return@Runnable
        focusRegion = null
        repeat()
    }

    // ================================================================== photo
    private fun onShutter() {
        if (mode == Mode.PHOTO) {
            if (steadyWaiting) return
            // steady shutter: wait (at most 1 s) for the phone to stop moving
            if (eff.steady && sensorsOn && shake > Advisor.STILL && session != null) {
                steadyWaiting = true
                showHint(tr("Hold still…", "Sabit tutun…"))
                main.postDelayed(steadyTimeout, 1000)
                return
            }
            takePhoto()
        } else if (recording) stopRecording() else startRecording()
    }

    private val steadyTimeout = Runnable { fireSteady() }

    private fun fireSteady() {
        if (!steadyWaiting) return
        steadyWaiting = false
        main.removeCallbacks(steadyTimeout)
        takePhoto()
    }

    private fun takePhoto() {
        if (session == null || reader == null || precapture != 0) return
        if (caller == Caller.IMAGE && callerBusy) return
        val needFlash = hasFlash && !manualExp && !manualFlash && (fl == 2 || (fl == 1 && lastAe == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED))
        if (needFlash) startPrecapture() else capture()
    }

    /** With flash: let auto-exposure meter with the pre-flash first (at most 1.5 s), then shoot. */
    private fun startPrecapture() {
        val s = session ?: return
        try {
            val b = request(CameraDevice.TEMPLATE_PREVIEW) ?: return
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            b.setTag(PRE)
            precapture = 1
            s.capture(b.build(), results, main)
            main.postDelayed(precaptureTimeout, 1500)
        } catch (_: Exception) { precapture = 0; capture() }
    }

    private val precaptureTimeout = Runnable { if (precapture != 0) capture() }

    private fun capture() {
        main.removeCallbacks(precaptureTimeout)
        precapture = 0
        val s = session ?: return
        val d = device ?: return
        val r = reader ?: return
        val pv = preview ?: return
        val look = if (advanced) adv.look.takeIf { !it.neutral } else null
        val shot = Shot(System.currentTimeMillis(), Sizes.jpegOrientation(sensorOrientation, deviceDeg, front), look, eff.jpeg)
        try {
            val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(r.surface)
            b.addTarget(pv)
            common(b)
            if (manualExp && hasFlash && fl == 2) {
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                strength(b, torch = false)
            } else if (manualFlash) {
                b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE)
                strength(b, torch = false)
            }
            b.set(CaptureRequest.JPEG_ORIENTATION, shot.rotation)
            b.set(CaptureRequest.JPEG_QUALITY, shot.quality.toByte())
            synchronized(shots) { shots.addLast(shot) }
            if (caller == Caller.IMAGE) callerBusy = true
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(s: CameraCaptureSession, req: CaptureRequest, f: CaptureFailure) {
                    synchronized(shots) { shots.remove(shot) }
                    callerBusy = false
                    toast(tr("The photo failed. Try again.", "Fotoğraf çekilemedi. Tekrar deneyin."))
                }
            }, main)
            shutterFeedback(MediaActionSound.SHUTTER_CLICK)
            if (advanced && adv.hints) Advisor.verdict(moment())?.let { showHint(it); verdictUntil = SystemClock.elapsedRealtime() + 2500 }
        } catch (e: Exception) {
            synchronized(shots) { shots.remove(shot) }
            callerBusy = false
            toast(tr("The photo failed: ${e.message}", "Fotoğraf çekilemedi: ${e.message}"))
        }
    }

    private val onImage = ImageReader.OnImageAvailableListener { r ->
        val img = try { r.acquireNextImage() } catch (_: Exception) { null } ?: return@OnImageAvailableListener
        val bytes = try {
            val buf = img.planes[0].buffer
            ByteArray(buf.remaining()).also { buf.get(it) }
        } finally { img.close() }
        val shot = synchronized(shots) { shots.removeFirstOrNull() } ?: Shot(System.currentTimeMillis(), 0)
        try { saver.execute { savePhoto(bytes, shot) } } catch (_: Exception) {}
    }

    private fun savePhoto(raw: ByteArray, shot: Shot) {
        val prepared = Jpeg.prepare(this, raw, shot.rotation, shot.time)
        val jpeg = shot.look?.let { LookJpeg.apply(this, prepared, it, shot.quality) } ?: prepared
        if (caller == Caller.IMAGE) { deliverPhoto(jpeg); return }
        try {
            val uri = Media.savePhoto(this, jpeg, shot.time)
            val bmp = Media.thumb(this, uri, dp(96))
            runOnUiThread { lastUri = uri; lastVideo = false; thumb.setImageBitmap(bmp); updateUi() }
        } catch (e: Exception) {
            runOnUiThread { toast(tr("Couldn't save the photo: ${e.message}", "Fotoğraf kaydedilemedi: ${e.message}")) }
        }
    }

    /** IMAGE_CAPTURE: write into the caller's file (or hand back a small picture) and return at once. */
    private fun deliverPhoto(jpeg: ByteArray) {
        try {
            val result = Intent()
            val out = outputUri
            if (out != null) Media.writeTo(this, out, jpeg)
            else result.putExtra("data", Jpeg.small(jpeg, 320))
            runOnUiThread { setResult(RESULT_OK, result); finish() }
        } catch (e: Exception) {
            runOnUiThread { callerBusy = false; toast(tr("Couldn't hand the photo over: ${e.message}", "Fotoğraf iletilemedi: ${e.message}")) }
        }
    }

    // ================================================================== video
    private fun startRecording() {
        if (device == null || session == null || recorder != null) return
        val time = System.currentTimeMillis()
        val out = outputUri
        val owned = !(caller == Caller.VIDEO && out != null)
        val uri: Uri
        val pfd: ParcelFileDescriptor
        try {
            uri = if (owned) Media.newEntry(this, true, time) else out!!
            pfd = (try { contentResolver.openFileDescriptor(uri, "rw") } catch (_: Exception) { null }
                ?: contentResolver.openFileDescriptor(uri, "w"))!!
        } catch (e: Exception) {
            toast(tr("Can't create the video file: ${e.message}", "Video dosyası oluşturulamıyor: ${e.message}"))
            return
        }
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        try {
            @Suppress("DEPRECATION")
            val prof = try { CamcorderProfile.get(camId!!.toInt(), CamcorderProfile.QUALITY_HIGH) } catch (_: Exception) { null }
            val audio = hasMic()
            if (audio) rec.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setOutputFile(pfd.fileDescriptor)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            rec.setVideoSize(size.w, size.h)
            rec.setVideoFrameRate(30)
            rec.setVideoEncodingBitRate(Sizes.videoBitrate(size).let { if (eff.bitrateHigh) (it * 1.6).toInt().coerceAtMost(50_000_000) else it })

            if (audio) {
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                rec.setAudioEncodingBitRate(prof?.audioBitRate ?: 128_000)
                rec.setAudioSamplingRate(prof?.audioSampleRate ?: 48_000)
                rec.setAudioChannels(prof?.audioChannels ?: 1)
            }
            rec.setOrientationHint(Sizes.jpegOrientation(sensorOrientation, deviceDeg, front))
            if (maxDurationMs > 0) rec.setMaxDuration(maxDurationMs)
            if (maxBytes > 0) rec.setMaxFileSize(maxBytes)
            rec.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED || what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED)
                    main.post { if (recording) stopRecording() }
            }
            rec.prepare()
        } catch (e: Exception) {
            rec.release()
            try { pfd.close() } catch (_: Exception) {}
            if (owned) Media.delete(this, uri)
            toast(tr("Can't record at ${size.w}×${size.h}: ${e.message}", "${size.w}×${size.h} kaydedilemiyor: ${e.message}"))
            return
        }
        recorder = rec
        recordPfd = pfd
        recordUri = uri
        recordOwned = owned
        recordSurface = rec.surface
        recording = true
        updateUi()
        createSession {
            try {
                rec.start()
                recordStart = SystemClock.elapsedRealtime()
                shutterFeedback(MediaActionSound.START_VIDEO_RECORDING)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                timer.visibility = View.VISIBLE
                tick.run()
            } catch (e: Exception) {
                toast(tr("Recording didn't start: ${e.message}", "Kayıt başlamadı: ${e.message}"))
                abortRecording()
            }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!recording) return
            timer.text = "● " + Sizes.clock(SystemClock.elapsedRealtime() - recordStart)
            main.postDelayed(this, 1000 - (SystemClock.elapsedRealtime() - recordStart) % 1000)
        }
    }

    private fun stopRecording(reopen: Boolean = true) {
        val rec = recorder ?: return
        recording = false
        recorder = null
        recordSurface = null
        main.removeCallbacks(tick)
        timer.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        try { session?.stopRepeating(); session?.abortCaptures() } catch (_: Exception) {}
        val ok = try { rec.stop(); true } catch (_: RuntimeException) { false }
        rec.release()
        try { recordPfd?.close() } catch (_: Exception) {}
        recordPfd = null
        val uri = recordUri
        val owned = recordOwned
        recordUri = null
        if (ok) shutterFeedback(MediaActionSound.STOP_VIDEO_RECORDING)
        if (uri != null && owned) saver.execute {
            if (!ok) { Media.delete(this, uri); return@execute }
            Media.publish(this, uri)
            if (caller == Caller.NONE) {
                val bmp = Media.thumb(this, uri, dp(96))
                runOnUiThread { lastUri = uri; lastVideo = true; thumb.setImageBitmap(bmp); updateUi() }
            }
        }
        if (caller == Caller.VIDEO) {
            if (ok && uri != null) {
                // the media store entry must be visible before the caller opens it
                if (owned) try { saver.submit { }.get() } catch (_: Exception) {}
                setResult(RESULT_OK, Intent().setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                finish()
                return
            }
            toast(tr("The video was too short.", "Video çok kısaydı."))
        } else if (!ok) toast(tr("The video was too short to keep.", "Video saklamak için çok kısaydı."))
        updateUi()
        if (reopen && device != null) createSession()
    }

    /** Recording could not start: throw the half-made file away and go back to the preview. */
    private fun abortRecording() {
        val rec = recorder ?: return
        recorder = null
        recordSurface = null
        recording = false
        main.removeCallbacks(tick)
        timer.visibility = View.GONE
        rec.release()
        try { recordPfd?.close() } catch (_: Exception) {}
        recordPfd = null
        val uri = recordUri
        if (uri != null && recordOwned) saver.execute { Media.delete(this, uri) }
        recordUri = null
        updateUi()
        if (device != null) createSession()
    }

    // ================================================================== Advanced tab
    private enum class Apply { UI, CAMERA, SESSION, RESTART }

    private fun setAdvanced(on: Boolean) {
        if (on == advanced || recording) return
        val lensChange = !front && adv.lens > 0
        advanced = on
        if (caller == Caller.NONE) prefs.edit().putBoolean("advanced", on).apply()
        if (!on) { aeLocked = false; gate.reset(); steadyWaiting = false }
        if (lensChange && device != null) { closeCamera(); startCamera() } else reconfigure()
        syncAdvanced()
    }

    /** Stores new Advanced settings and applies them as cheaply as [how] allows. */
    private fun setAdv(n: Adv, how: Apply = Apply.CAMERA, rebuild: Boolean = true) {
        if (n.manualFocus && manualFocusOk && afLock != null) { dropLock(); focusRegion = null }
        adv = n
        prefs.edit().putString("adv", n.serialize()).apply()
        when (how) {
            Apply.UI -> {}
            Apply.CAMERA -> repeat()
            Apply.SESSION -> reconfigure()
            Apply.RESTART -> { closeCamera(); startCamera() }
        }
        updateUi()
        syncAdvanced()
        if (rebuild) showPanel(openPanel)
    }

    private fun toggleLock() {
        aeLocked = !aeLocked
        updateUi()
        repeat()
    }

    private fun sceneOptions() = Adv.SCENES.filter { it.second == 0 || it.second in sceneModes }

    private fun qualityLabel() = if (mode == Mode.PHOTO) Sizes.photoLabel(size).substringBefore(" ·") else Sizes.videoLabel(size).substringBefore(" ·")

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    private fun refreshChips() {
        if (chips.isEmpty()) return
        val a = adv
        val video = mode == Mode.VIDEO
        val manOk = manualSensor && isoRange != null && nsRange != null
        fun set(k: String, show: Boolean, text: String, on: Boolean, dim: Boolean = false, locked: Boolean = false) {
            val c = chips[k] ?: return
            c.visibility = if (show) View.VISIBLE else View.GONE
            c.text = text
            c.isEnabled = !locked
            c.chipState(on && !dim, openPanel == k, !dim && !locked)
        }
        set("Profiles", true, activeProfile ?: tr("Profiles", "Profiller"), activeProfile != null, locked = recording)
        set("EV", evRange != null, if (a.ev == 0) "EV" else "EV ${Adv.evLabel(a.ev)}", a.ev != 0, dim = manualExp)
        set("ISO", manOk, if (a.iso == 0) "ISO" else "ISO ${a.iso}", a.iso > 0)
        set("Shutter", manOk, if (a.shutterNs == 0L) tr("Shutter", "Enstantane") else Adv.shutterLabel(a.shutterNs), a.shutterNs > 0)
        set("WB", awbModes.size > 1, if (a.wb == Adv.WB_AUTO) "WB" else Adv.wbLabel(a), a.wb != Adv.WB_AUTO)
        set("Focus", manualFocusOk, if (a.manualFocus) "MF ${Adv.focusLabel(a.focus, minFocus)}" else tr("Focus", "Odak"), a.manualFocus)
        set("Tone", curvePoints >= 2 || nrModes.size > 1 || edgeModes.size > 1, tr("Tone", "Ton"), a.toneSet || a.sharpen != 0 || a.noise != 0)
        set("Look", true, if (a.look.neutral) tr("Look", "Görünüm") else Look.name(a.look), !a.look.neutral, dim = video)
        val sc = sceneOptions()
        set("Scene", sc.size > 1, sc.firstOrNull { it.second == a.scene && it.second != 0 }?.first ?: tr("Scene", "Sahne"), a.scene != 0,
            dim = a.scene != 0 && (manualExp || a.wb != Adv.WB_AUTO))
        set("Guides", true, tr("Guides", "Kılavuzlar"), a.grid != 0 || a.faces || a.steady)
        set("Quality", true, qualityLabel(), false, locked = recording)
        set("Flash", hasFlash, if (video) (if (flash == 1) tr("Torch", "Fener") else tr("Torch off", "Fener kapalı"))
            else arrayOf(tr("Flash off", "Flaş kapalı"), tr("Flash auto", "Flaş otomatik"), tr("Flash on", "Flaş açık"))[flash.coerceIn(0, 2)], flash > 0)
        set("Lens", !front && backIds.size > 1, if (a.lens == 0) tr("Main lens", "Ana lens") else "Lens ${a.lens + 1}", a.lens > 0, locked = recording)
    }

    /** Opens the option rows for one chip (or closes them with null). */
    private fun showPanel(key: String?) {
        panel.removeAllViews()
        refreshChips()
        if (key == null || !advanced || chips[key]?.visibility != View.VISIBLE) {
            openPanel = null
            panel.visibility = View.GONE
            refreshChips()
            return
        }
        openPanel = key
        val a = adv
        val video = mode == Mode.VIDEO
        val add = { v: View -> panel.addView(v, LinearLayout.LayoutParams(-1, -2)) }
        when (key) {
            "EV" -> if (manualExp) add(noteRow(tr("EV works with automatic exposure. Set ISO and Shutter back to Auto to use it.",
                "EV otomatik pozlamayla çalışır. Kullanmak için ISO ve Enstantane'yi Otomatik'e döndürün.")))
            else {
                add(sliderRow("EV", -6, 6, Adv.evPos(a.ev), { Adv.evLabel(Adv.evTenths(it)) },
                    { p -> setAdv(adv.copy(ev = Adv.evTenths(p)), rebuild = false) }, { setAdv(adv.copy(ev = 0)) }))
                if (aeLockOk) add(noteRow(tr("Hold the preview to lock exposure and white balance (AE-L). Hold again, or tap the AE-L tag, to unlock. A tap only locks focus.",
                    "Pozlamayı ve beyaz dengesini kilitlemek için önizlemeyi basılı tutun (AE-L). Açmak için yine basılı tutun veya AE-L etiketine dokunun. Dokunmak yalnızca odağı kilitler.")))
            }
            "ISO" -> {
                val r = isoRange!!
                val ch = Adv.isoChoices(r.lower, r.upper)
                add(optionRow("ISO", listOf(tr("Auto", "Otomatik")) + ch.map { "$it" }, { i -> if (i == 0) a.iso == 0 else ch[i - 1] == a.iso }) { i ->
                    setAdv(adv.copy(iso = if (i == 0) 0 else ch[i - 1], scene = 0))
                })
                add(noteRow(if (a.iso >= 1600) tr("High ISO: brighter but grainier.", "Yüksek ISO: daha parlak ama daha kumlu.")
                else tr("Set ISO or Shutter alone and the other follows the light. Both set = full manual.", "Yalnız ISO'yu veya Enstantane'yi ayarlayın, diğeri ışığa uyar. İkisi de ayarlı = tam manuel.")))
            }
            "Shutter" -> {
                val r = nsRange!!
                val ch = Adv.shutterChoices(r.lower, r.upper, video)
                add(optionRow(tr("Shutter", "Enstantane"), listOf(tr("Auto", "Otomatik")) + ch.map { Adv.shutterLabel(it) }, { i -> if (i == 0) a.shutterNs == 0L else ch[i - 1] == a.shutterNs }) { i ->
                    setAdv(adv.copy(shutterNs = if (i == 0) 0L else ch[i - 1], scene = 0))
                })
                add(noteRow(when {
                    a.shutterNs >= 125_000_000L -> tr("Slow shutter: rest the phone on something or use Steady shutter (Guides).", "Yavaş enstantane: telefonu bir yere dayayın veya Sabit deklanşör kullanın (Kılavuzlar).")
                    video -> tr("Video can't go slower than 1/30.", "Video 1/30'dan yavaş olamaz.")
                    else -> tr("Fast speeds freeze motion; slow ones let in more light but blur movement.", "Hızlı enstantane hareketi dondurur; yavaşı daha çok ışık alır ama hareketi bulanıklaştırır.")
                }))
            }
            "WB" -> {
                val presets = Adv.WB.filter { it.second == Adv.WB_AUTO || it.second in awbModes }
                val labels = presets.map { it.first } + if (manualPost) listOf("Kelvin") else emptyList()
                add(optionRow("WB", labels, { i -> if (i < presets.size) presets[i].second == a.wb else a.wb == Adv.WB_KELVIN }) { i ->
                    setAdv(adv.copy(wb = if (i < presets.size) presets[i].second else Adv.WB_KELVIN, scene = 0))
                })
                if (a.wb == Adv.WB_KELVIN) add(sliderRow("Kelvin", 25, 90, a.kelvin / 100, { "${it * 100}K" },
                    { p -> setAdv(adv.copy(kelvin = p * 100), rebuild = false) }, { setAdv(adv.copy(kelvin = 5500)) }))
            }
            "Focus" -> {
                add(optionRow(tr("Focus", "Odak"), listOf(tr("Auto", "Otomatik"), tr("Manual", "Manuel")), { i -> (i == 1) == a.manualFocus }) { i ->
                    setAdv(adv.copy(focus = if (i == 0) -1f else if (a.manualFocus) a.focus else 0f))
                })
                if (a.manualFocus) add(sliderRow(tr("Distance", "Mesafe"), 0, 100, (a.focus * 100).roundToInt(), { Adv.focusLabel(it / 100f, minFocus) },
                    { p -> setAdv(adv.copy(focus = p / 100f), rebuild = false) }, null))
                add(noteRow(if (a.manualFocus) tr("Manual focus overrides tap focus; a tap only meters there.", "Manuel odak dokunarak odaklamanın yerine geçer; dokunmak yalnızca orada ölçüm yapar.")
                else tr("Tap the preview to focus there and lock it (AF-L): it stays even if you move. Tap the square again for continuous auto focus.",
                    "Oraya odaklayıp kilitlemek için önizlemeye dokunun (AF-L): hareket etseniz de kalır. Sürekli otomatik odak için kareye yine dokunun.")))
            }
            "Tone" -> {
                val params = buildList {
                    if (curvePoints >= 2) { add("Contrast"); add("Shadows") }
                    if (edgeModes.size > 1) add("Sharpen")
                    if (nrModes.size > 1) add("Noise")
                }
                toneParam = toneParam.coerceIn(0, params.size - 1)
                // params are keys; the buttons show their names in the app language
                fun toneName(p: String) = when (p) {
                    "Contrast" -> tr("Contrast", "Kontrast"); "Shadows" -> tr("Shadows", "Gölgeler")
                    "Sharpen" -> tr("Sharpen", "Keskinlik"); else -> tr("Noise", "Gürültü")
                }
                add(optionRow("", params.map(::toneName), { it == toneParam }) { i -> toneParam = i; showPanel("Tone") })
                when (params.getOrNull(toneParam)) {
                    "Contrast" -> add(sliderRow(tr("Contrast", "Kontrast"), -100, 100, a.contrast, ::signed, { p -> setAdv(adv.copy(contrast = p), rebuild = false) }, { setAdv(adv.copy(contrast = 0)) }))
                    "Shadows" -> add(sliderRow(tr("Shadows", "Gölgeler"), -100, 100, a.shadows, ::signed, { p -> setAdv(adv.copy(shadows = p), rebuild = false) }, { setAdv(adv.copy(shadows = 0)) }))
                    "Sharpen" -> add(optionRow(tr("Sharpen", "Keskinlik"), Adv.LEVELS, { it == a.sharpen }) { i -> setAdv(adv.copy(sharpen = i)) })
                    "Noise" -> add(optionRow(tr("Noise red.", "Gürültü az."), Adv.LEVELS, { it == a.noise }) { i -> setAdv(adv.copy(noise = i)) })
                }
            }
            "Look" -> if (video) add(noteRow(tr("Look is for photos only. In video, use WB, EV and Tone (contrast, shadows): they work while recording.",
                "Görünüm yalnızca fotoğraf içindir. Videoda WB, EV ve Ton (kontrast, gölgeler) kullanın: kayıt sırasında da çalışırlar.")))
            else {
                val presets = Look.PRESETS
                add(optionRow(tr("Look", "Görünüm"), presets.map { it.first }, { i -> presets[i].second == a.look }) { i ->
                    val l = presets[i].second
                    setAdv(adv.copy(sat = l.sat, warmth = l.warmth, tint = l.tint, vibrance = l.vibrance), Apply.UI)
                })
                val params = listOf(tr("Saturation", "Doygunluk"), tr("Warmth", "Sıcaklık"), tr("Tint", "Renk tonu"), tr("Vibrance", "Canlılık"))
                add(optionRow("", params, { it == lookParam }) { i -> lookParam = i; showPanel("Look") })
                val v = when (lookParam) { 0 -> a.sat; 1 -> a.warmth; 2 -> a.tint; else -> a.vibrance }
                fun with(p: Int) = when (lookParam) { 0 -> adv.copy(sat = p); 1 -> adv.copy(warmth = p); 2 -> adv.copy(tint = p); else -> adv.copy(vibrance = p) }
                add(sliderRow(params[lookParam], -100, 100, v, ::signed, { p -> setAdv(with(p), Apply.UI, rebuild = false) }, { setAdv(with(0), Apply.UI) }))
            }
            "Scene" -> {
                val sc = sceneOptions()
                add(optionRow(tr("Scene", "Sahne"), sc.map { it.first }, { i -> sc[i].second == a.scene }) { i ->
                    val s = sc[i].second
                    setAdv(if (s == 0) adv.copy(scene = 0) else adv.copy(scene = s, iso = 0, shutterNs = 0L, wb = Adv.WB_AUTO))
                })
                add(noteRow(tr("Scene modes are hints to the phone's own processing; how much they change depends on the phone. They replace manual ISO, Shutter and WB.",
                    "Sahne modları telefonun kendi işlemesine ipucudur; ne kadar değiştirdikleri telefona bağlıdır. Manuel ISO, Enstantane ve WB'nin yerini alırlar.")))
            }
            "Guides" -> {
                add(optionRow(tr("Grid", "Izgara"), Adv.GRIDS, { it == a.grid }) { i -> setAdv(adv.copy(grid = i), Apply.UI) })
                val toggles = buildList {
                    add("Level"); if (faceSimple) add("Faces"); add("Hints"); if (mode == Mode.PHOTO) add("Steady shutter")
                }
                fun isOn(t: String) = when (t) { "Level" -> a.level; "Faces" -> a.faces; "Hints" -> a.hints; else -> a.steady }
                // toggles are keys; the buttons show their names in the app language
                fun toggleName(t: String) = when (t) {
                    "Level" -> tr("Level", "Seviye"); "Faces" -> tr("Faces", "Yüzler")
                    "Hints" -> tr("Hints", "İpuçları"); else -> tr("Steady shutter", "Sabit deklanşör")
                }
                add(optionRow(tr("Show", "Göster"), toggles.map(::toggleName), { isOn(toggles[it]) }) { i ->
                    setAdv(when (toggles[i]) {
                        "Level" -> adv.copy(level = !a.level)
                        "Faces" -> adv.copy(faces = !a.faces)
                        "Hints" -> adv.copy(hints = !a.hints)
                        else -> adv.copy(steady = !a.steady)
                    })
                })
                add(noteRow(tr("Hints: offline advice about light, shake, tilt and faces. The dot on the shutter turns green when it's a good moment.",
                    "İpuçları: ışık, titreme, eğiklik ve yüzler hakkında çevrimdışı öneriler. Doğru anda deklanşördeki nokta yeşile döner.")))
            }
            "Quality" -> {
                add(optionRow(tr("Size", "Boyut"), sizeList.map { if (mode == Mode.PHOTO) Sizes.photoLabel(it) else Sizes.videoLabel(it) }, { sizeList[it] == size }) { i ->
                    val s = sizeList[i]
                    if (s != size && !recording) {
                        prefs.edit().putString(sizeKey(aspect), s.toString()).apply()
                        reconfigure()
                        showPanel("Quality")
                    }
                })
                if (mode == Mode.PHOTO) add(optionRow("JPEG", Adv.JPEG.map { "$it" }, { Adv.JPEG[it] == a.jpeg }) { i -> setAdv(adv.copy(jpeg = Adv.JPEG[i]), Apply.UI) })
                else add(optionRow(tr("Bitrate", "Bit hızı"), listOf(tr("Standard", "Standart"), tr("High", "Yüksek")), { (it == 1) == a.bitrateHigh }) { i -> setAdv(adv.copy(bitrateHigh = i == 1), Apply.UI) })
                add(optionRow("", listOf(tr("Reset all Advanced settings", "Tüm Gelişmiş ayarları sıfırla")), { false }) {
                    val lens = adv.lens
                    aeLocked = false
                    setAdv(Adv(), if (lens != 0 && !front) Apply.RESTART else Apply.CAMERA)
                    toast(tr("Advanced settings reset.", "Gelişmiş ayarlar sıfırlandı."))
                })
                // language: a dropdown under the row; the activity is recreated and comes back to this panel
                lateinit var langRow: View
                langRow = optionRow("", listOf(PlainLang.label()), { false }) { if (!recording) PlainLang.choose(this, langRow) }
                add(langRow)
                add(optionRow("", listOf(PlainUpdate.label(this)), { false }) { PlainUpdate.check(this) })
            }
            "Flash" -> {
                val labels = if (video) listOf(tr("Off", "Kapalı"), tr("Torch", "Fener")) else listOf(tr("Off", "Kapalı"), tr("Auto", "Otomatik"), tr("On", "Açık"))
                add(optionRow(tr("Flash", "Flaş"), labels, { it == flash }) { i -> setFlash(i); showPanel("Flash") })
                // strength: Android 15+ and a flash with levels; shown for On (photo) and Torch (video)
                val max = if (video) torchMax else singleMax
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && max > 1 && flash == (if (video) 1 else 2)) {
                    val def = if (video) torchDef else singleDef
                    add(sliderRow(tr("Strength", "Güç"), 1, max, Adv.flashLevel(a.flashPct, max, def), { "$it/$max" },
                        { p -> setAdv(adv.copy(flashPct = Adv.flashPct(p, max)), rebuild = false) }, { setAdv(adv.copy(flashPct = 0)) }))
                    add(noteRow(when {
                        a.flashPct == 0 -> tr("Strength: the phone's default. Move the slider to set your own.", "Güç: telefonun varsayılanı. Kendiniz ayarlamak için kaydırıcıyı oynatın.")
                        video || manualExp -> tr("Reset = the phone's default strength.", "Sıfırla = telefonun varsayılan gücü.")
                        else -> tr("A set strength fires a plain flash at that level (no pre-flash metering). Reset = the phone's own flash metering.",
                            "Ayarlı güç, o seviyede düz bir flaş patlatır (ön flaş ölçümü yok). Sıfırla = telefonun kendi flaş ölçümü.")
                    }))
                }
                if (manualExp && !video) add(noteRow(tr("With manual exposure, Auto flash is off and On fires a plain flash.", "Manuel pozlamada otomatik flaş kapalıdır, Açık düz bir flaş patlatır.")))
            }
            "Profiles" -> {
                if (profiles.isNotEmpty()) add(optionRow(tr("Load", "Yükle"), profiles.map { it.name }, { profiles[it].name == activeProfile },
                    onHold = { i -> profileOptions(profiles[i].name) }) { i -> loadProfile(profiles[i]) })
                val act = activeProfile
                val acts = listOf(tr("Save as…", "Farklı kaydet…")) + if (act != null) listOf(tr("Overwrite “$act”", "“$act” üzerine yaz")) else emptyList()
                add(optionRow("", acts, { false }) { i -> if (i == 0) saveProfileAs() else if (act != null) overwriteProfile(act) })
                add(noteRow(if (profiles.isEmpty()) tr("A profile keeps the Advanced settings, flash, aspect, size and photo / video. Save as… to make one.",
                    "Profil; Gelişmiş ayarları, flaşı, en-boy oranını, boyutu ve fotoğraf / videoyu saklar. Oluşturmak için Farklı kaydet…")
                else tr("Tap a profile to load it. Hold one to rename or delete it.", "Yüklemek için profile dokunun. Yeniden adlandırmak veya silmek için basılı tutun.")))
            }
            "Lens" -> {
                val labels = backIds.mapIndexed { i, id ->
                    val px = try { cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) } catch (_: Exception) { null }
                    val mp = px?.let { " · ${((it.width.toLong() * it.height) / 1_000_000.0).roundToInt()} MP" } ?: ""
                    (if (i == 0) tr("Main", "Ana") else "Lens ${i + 1}") + mp
                }
                add(optionRow("Lens", labels, { it == a.lens }) { i -> if (i != a.lens && !recording) setAdv(adv.copy(lens = i), Apply.RESTART) })
            }
        }
        panel.visibility = View.VISIBLE
        refreshChips()
    }

    // ---- profiles
    private fun saveProfiles() {
        prefs.edit().putString("profiles", Profiles.serialize(profiles)).apply {
            val a = activeProfile
            if (a != null) putString("profile", a) else remove("profile")
        }.apply()
    }

    /** The current settings under [name]. */
    private fun snapshot(name: String): Profile {
        val cur = m()
        fun asp(md: String) = if (md == cur) aspect.name else prefs.getString("aspect.$md.$facing", null) ?: ""
        fun sz(md: String) = if (md == cur) size.toString() else asp(md).takeIf { it.isNotEmpty() }?.let { prefs.getString("asize.$md.$facing.$it", null) } ?: ""
        return Profile(
            name = name, adv = adv, video = mode == Mode.VIDEO,
            flashPhoto = if (mode == Mode.PHOTO) flash else prefs.getInt("flash.photo", 0),
            flashVideo = if (mode == Mode.VIDEO) flash else prefs.getInt("flash.video", 0),
            aspectPhoto = asp("photo"), aspectVideo = asp("video"), sizePhoto = sz("photo"), sizeVideo = sz("video"),
        )
    }

    private fun storeProfile(name: String) {
        profiles = Profiles.upsert(profiles, snapshot(name))
        activeProfile = name
        saveProfiles()
        showPanel("Profiles")
        toast(tr("Profile “$name” saved.", "“$name” profili kaydedildi."))
    }

    private fun saveProfileAs() {
        askName(tr("Save profile", "Profili kaydet"), "") { n ->
            val old = Profiles.find(profiles, n)
            if (old == null) storeProfile(n) else overwriteProfile(old.name)
        }
    }

    private fun overwriteProfile(name: String) {
        AlertDialog.Builder(this).setTitle(tr("Overwrite “$name”?", "“$name” üzerine yazılsın mı?"))
            .setMessage(tr("It gets the current settings.", "Geçerli ayarları alır."))
            .setPositiveButton(tr("Overwrite", "Üzerine yaz")) { _, _ -> storeProfile(name) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    /** Hold a profile: rename or delete it. */
    private fun profileOptions(name: String) {
        AlertDialog.Builder(this).setTitle(name)
            .setItems(arrayOf(tr("Rename", "Yeniden adlandır"), tr("Delete", "Sil"))) { _, w ->
                if (w == 0) askName(tr("Rename profile", "Profili yeniden adlandır"), name) { n ->
                    val l = Profiles.rename(profiles, name, n) ?: run { toast(tr("There is already a profile called “$n”.", "“$n” adında bir profil zaten var.")); return@askName }
                    profiles = l
                    if (activeProfile.equals(name, ignoreCase = true)) activeProfile = n
                    saveProfiles()
                    showPanel("Profiles")
                } else AlertDialog.Builder(this).setTitle(tr("Delete “$name”?", "“$name” silinsin mi?"))
                    .setMessage(tr("Only the profile goes; the current settings stay as they are.", "Yalnızca profil silinir; geçerli ayarlar olduğu gibi kalır."))
                    .setPositiveButton(tr("Delete", "Sil")) { _, _ ->
                        profiles = Profiles.remove(profiles, name)
                        if (activeProfile.equals(name, ignoreCase = true)) activeProfile = null
                        saveProfiles()
                        showPanel("Profiles")
                    }
                    .setNegativeButton(tr("Cancel", "İptal"), null)
                    .show()
            }
            .show()
    }

    /** Applies a profile at once: Advanced settings, flash, aspect and size, photo / video. */
    private fun loadProfile(p: Profile) {
        if (recording) return
        val e = prefs.edit()
        e.putString("adv", p.adv.serialize())
        e.putInt("flash.photo", p.flashPhoto)
        e.putInt("flash.video", p.flashVideo)
        for ((m, asp, sz) in listOf(Triple("photo", p.aspectPhoto, p.sizePhoto), Triple("video", p.aspectVideo, p.sizeVideo))) {
            if (asp.isEmpty()) { e.remove("aspect.$m.$facing"); continue }
            e.putString("aspect.$m.$facing", asp)
            if (sz.isNotEmpty()) e.putString("asize.$m.$facing.$asp", sz) else e.remove("asize.$m.$facing.$asp")
        }
        // other apps' capture requests keep the mode they asked for
        if (caller == Caller.NONE) { mode = if (p.video) Mode.VIDEO else Mode.PHOTO; e.putString("mode", mode.name) }
        activeProfile = p.name
        e.putString("profile", p.name)
        e.apply()
        val lensChange = !front && p.adv.lens != adv.lens
        adv = p.adv
        flash = prefs.getInt(flashKey(), 0)
        aeLocked = false
        if (adv.manualFocus && manualFocusOk) dropLock()
        if (lensChange && device != null) { closeCamera(); startCamera() } else reconfigure()
        updateUi()
        syncAdvanced()
        showPanel("Profiles")
        toast(tr("Profile “${p.name}” loaded.", "“${p.name}” profili yüklendi."))
    }

    /** A one-line name dialog (OK only with a usable name). */
    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setText(initial)
            setSelection(text.length)
            hint = tr("Name", "Ad")
            setTextColor(TEXT)
            setHintTextColor(LABEL)
            filters = arrayOf(android.text.InputFilter.LengthFilter(Profiles.MAX_NAME))
        }
        val box = FrameLayout(this).apply { setPadding(dp(22), dp(8), dp(22), 0); addView(e) }
        AlertDialog.Builder(this).setTitle(title).setView(box)
            .setPositiveButton(tr("OK", "Tamam")) { _, _ -> Profiles.cleanName(e.text.toString())?.let(done) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }

    // ---- what the camera reports each frame
    private fun readResult(r: TotalCaptureResult) {
        val iso = r.get(CaptureResult.SENSOR_SENSITIVITY)
        val ns = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        if (iso != null) lastIso = iso
        if (ns != null) lastNs = ns
        if (!manualExp && r.get(CaptureResult.CONTROL_AE_MODE) != CameraMetadata.CONTROL_AE_MODE_OFF) {
            if (iso != null) autoIso = iso
            if (ns != null) autoNs = ns
        }
        if (!advanced) return
        afState = r.get(CaptureResult.CONTROL_AF_STATE)
        if (wbRefWanted && r.get(CaptureResult.CONTROL_AWB_MODE) == CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT && ++wbRefFrames >= 4) {
            val g = r.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val id = camId
            wbRefWanted = false
            if (g != null && id != null) {
                wbRef[id] = g to r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                repeat()
            }
        }
        if ((eff.faces || eff.hints) && faceSimple) {
            val fs = r.get(CaptureResult.STATISTICS_FACES)
            val list = if (fs.isNullOrEmpty()) emptyList() else {
                val c = Sizes.crop(activeArray.width(), activeArray.height(), previewDim.ratio)
                fs.mapNotNull { box(it.bounds, c) }
            }
            if (list != faces) {
                faces = list
                if (eff.faces) overlay.faces = list
            }
        }
    }

    /** A face rectangle (active-array pixels) → portrait view fractions. */
    private fun box(b: Rect, c: IntArray): FaceBox? {
        if (c[2] <= 0 || c[3] <= 0) return null
        val (x0, y0) = Geo.sensorToView((b.left - c[0]).toFloat() / c[2], (b.top - c[1]).toFloat() / c[3], sensorOrientation, front)
        val (x1, y1) = Geo.sensorToView((b.right - c[0]).toFloat() / c[2], (b.bottom - c[1]).toFloat() / c[3], sensorOrientation, front)
        val l = min(x0, x1).coerceIn(0f, 1f)
        val t = min(y0, y1).coerceIn(0f, 1f)
        val r = max(x0, x1).coerceIn(0f, 1f)
        val bt = max(y0, y1).coerceIn(0f, 1f)
        return if (r - l < 0.01f || bt - t < 0.01f) null else FaceBox(l, t, r, bt)
    }

    private fun moment() = Moment(
        stats = stats,
        iso = lastIso,
        exposureNs = lastNs,
        flashRequired = lastAe == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED,
        flashOn = hasFlash && fl > 0,
        unfocused = afState == CameraMetadata.CONTROL_AF_STATE_PASSIVE_UNFOCUSED || afState == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
        shake = if (sensorsOn) shake else 0f,
        tilt = roll?.let { Geo.offLevel(it) },
        faces = faces,
    )

    private fun showHint(t: String) {
        hintView.text = t
        hintView.visibility = View.VISIBLE
    }

    // ---- guidance loop: a small copy of the preview 3 times a second (only with Hints on)
    private val analyse = object : Runnable {
        override fun run() {
            if (!advanced || !adv.hints || !resumed || idlePaused) return
            if (session != null && texture.isAvailable && texture.width > 0 && texture.height > 0) {
                val w = 160
                val h = (160f * texture.height / texture.width).roundToInt().coerceIn(40, 480)
                val b = aBmp?.takeIf { it.width == w && it.height == h }
                    ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { aBmp?.recycle(); aBmp = it }
                try {
                    texture.getBitmap(b)
                    val px = aPx?.takeIf { it.size == w * h } ?: IntArray(w * h).also { aPx = it }
                    b.getPixels(px, 0, w, 0, 0, w, h)
                    stats = Stats.of(px, w, h)
                } catch (_: Exception) {}
                evaluate()
            }
            main.postDelayed(this, 333)
        }
    }

    private fun evaluate() {
        val now = SystemClock.elapsedRealtime()
        val m = moment()
        val h = gate.update(now, Advisor.hint(m))
        when {
            steadyWaiting || now < verdictUntil -> {}
            h != null -> showHint(h)
            else -> hintView.visibility = View.GONE
        }
        shutter.ready = if (recording) null else Advisor.ready(m)
    }

    private val sm by lazy { getSystemService(SensorManager::class.java) }

    private val sensorCb = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val v = e.values
            if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val r = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
                shake = shake * 0.7f + r * 0.3f
                if (steadyWaiting && shake < 0.12f) fireSteady()
                return
            }
            val k = if (gravFromAccel) 0.15f else 1f
            for (i in 0..2) grav[i] += (v[i] - grav[i]) * k
            val g = sqrt(grav[0] * grav[0] + grav[1] * grav[1] + grav[2] * grav[2])
            val r = if (g < 1f || abs(grav[2]) > g * 0.85f) null else Geo.roll(grav[0], grav[1])
            val old = roll
            roll = r
            val changed = (r == null) != (old == null) || (r != null && old != null && abs(r - old) > 0.2f)
            if (eff.level && changed) overlay.roll = r
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /** Starts or stops the sensors and the guidance loop to match the settings. */
    private fun syncAdvanced() {
        main.removeCallbacks(analyse)
        val on = advanced && resumed && !idlePaused
        val gyro = on && (adv.hints || adv.steady)
        val gravity = on && (adv.level || adv.hints)
        sm.unregisterListener(sensorCb)
        sensorsOn = false
        if (gyro) sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sensorsOn = sm.registerListener(sensorCb, it, SensorManager.SENSOR_DELAY_UI) }
        if (gravity) {
            val gs = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
            gravFromAccel = gs == null
            (gs ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let { sm.registerListener(sensorCb, it, SensorManager.SENSOR_DELAY_UI) }
        } else {
            roll = null
            overlay.roll = null
        }
        if (!sensorsOn) shake = 0f
        if (on && adv.hints) main.postDelayed(analyse, 400)
        else {
            stats = null
            gate.reset()
            hintView.visibility = View.GONE
            shutter.ready = null
        }
    }

    private fun stopAdvanced() {
        main.removeCallbacks(analyse)
        sm.unregisterListener(sensorCb)
        sensorsOn = false
        steadyWaiting = false
        main.removeCallbacks(steadyTimeout)
    }

    // ================================================================== TextureView
    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { startCamera() }
    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        closeCamera()
        preview?.release()
        preview = null
        return true
    }
    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    companion object {
        private const val PRE = "precapture"
        private const val AF_START = "af-start"
        private const val GALLERY_PKG = "app.plain.gallery"
        /** Plain Gallery: swipe through the camera roll from the opened item. */
        private const val EXTRA_CAMERA_ROLL = "app.plain.gallery.extra.CAMERA_ROLL"
        /** What Basic uses: everything automatic, no guides, no hints. */
        private val BASIC = Adv(level = false, hints = false)
        private val CHIPS = listOf("Profiles", "EV", "ISO", "Shutter", "WB", "Focus", "Tone", "Look", "Scene", "Guides", "Quality", "Flash", "Lens")
    }
}
