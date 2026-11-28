package app.plain.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.shared.R

/**
 * The menus of every Plain app (2026-11-27): a bottom sheet instead of a plain dropdown list.
 *
 *  - a header: what the menu is about (title, a line under it, optional cover art);
 *  - [actions]: the 2–4 things done most often, as big round buttons;
 *  - [section]s: rows with an icon, a label, an optional explanation line, and on the right the current value with ›
 *    (opens a choice), a switch ([toggle], stays open) or a check mark ([choice]);
 *  - destructive rows in red, at the end.
 *
 * OLED style like the apps: pure black with 1 dp outlines; [accent] is the app's colour.
 *
 * Sheet(activity, ACCENT).header("Song", "Artist").actions(...).section("Go to").item(...).show()
 *
 * Plain Home (not merged) carries a copy of this file and of res/drawable/sheet_ic_* (only the R import differs).
 */
class Sheet(private val a: Activity, private val accent: Int = ACCENT) {
    class Action(val icon: Int, val label: String, val fn: () -> Unit)

    /**
     * Hold a setting row to put it back to its default: [label] is the default as the row shows it, [fn] applies it.
     * [closes]: the reset rebuilds the screen (language), so the sheet closes first.
     */
    class Reset(val label: String, val closes: Boolean = false, val fn: () -> Unit)

    private val dialog = Dialog(a)
    private val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
    private var rows = 0
    private var lastWasSection = false
    private var built = false

    fun header(title: String, sub: String? = null, art: ((ImageView) -> Unit)? = null): Sheet {
        val row = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(2), dp(20), dp(14))
        }
        if (art != null) {
            val iv = ImageView(a).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = box(dpf(8), OUTLINE)
                clipToOutline = true
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(52), dp(52)).apply { rightMargin = dp(14) })
            art(iv)
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 18f, TEXT, bold = true, lines = 2))
        if (!sub.isNullOrBlank()) col.addView(text(sub, 13.5f, DIM, lines = 3).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        body.addView(row)
        divider()
        return this
    }

    /** Big round buttons for the most common actions (up to 4 fit a row; more wrap to a second row). */
    fun actions(vararg list: Action?): Sheet {
        val items = list.filterNotNull()
        if (items.isEmpty()) return this
        for (chunk in items.chunked(4)) {
            val row = LinearLayout(a).apply { setPadding(dp(8), dp(14), dp(8), dp(6)) }
            for (it in chunk) row.addView(actionTile(it), LinearLayout.LayoutParams(0, WRAP, 1f))
            repeat(4 - chunk.size) { if (items.size > 4) row.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            body.addView(row)
        }
        divider(top = dp(8))
        return this
    }

    /** A small heading above a group of rows (null: just a gap). */
    fun section(title: String?): Sheet {
        if (title == null) { body.addView(View(a), LinearLayout.LayoutParams(1, dp(8))); return this }
        body.addView(text(title.uppercase(PlainLang.locale), 12f, LABEL, bold = true).apply {
            letterSpacing = 0.08f
            setPadding(dp(20), dp(if (rows == 0) 10 else 18), dp(20), dp(6))
        })
        lastWasSection = true
        return this
    }

    /**
     * A row. [value]: the current setting, shown on the right; [more]: opens another choice (› on the right);
     * [danger]: red (erase, delete…); [keepOpen]: the sheet stays (for rows that only change something shown here);
     * [onLong]: a long press (edit / delete the thing the row stands for); [reset]: a long press puts the setting back to
     * its default (the row's value changes in place, the sheet stays).
     */
    fun item(icon: Int?, label: String, sub: String? = null, value: String? = null, more: Boolean = false,
             danger: Boolean = false, enabled: Boolean = true, keepOpen: Boolean = false, onLong: (() -> Unit)? = null,
             reset: Reset? = null, fn: () -> Unit): Sheet {
        val color = when { !enabled -> FAINT; danger -> RED; else -> TEXT }
        val r = rowBase(icon, label, sub, if (danger) RED else if (enabled) DIM else FAINT, color)
        val valueView = if (value != null) text(value, 14f, if (enabled) DIM else FAINT, lines = 1).apply {
            gravity = Gravity.END
            maxWidth = dp(150)
            setPadding(dp(10), 0, if (more) dp(2) else 0, 0)
        }.also { r.addView(it) } else null
        if (more) r.addView(icon(R.drawable.sheet_ic_chevron, FAINT), LinearLayout.LayoutParams(dp(20), dp(20)))
        if (enabled) r.setOnClickListener { if (!keepOpen) dialog.dismiss(); fn() } else r.isEnabled = false
        if (enabled && onLong != null) r.setOnLongClickListener { dialog.dismiss(); onLong(); true }
        else if (enabled && reset != null) r.setOnLongClickListener {
            if (valueView?.text?.toString() == reset.label) { toast(tr("Already the default: ${reset.label}", "Zaten varsayılan: ${reset.label}")); return@setOnLongClickListener true }
            if (reset.closes) dialog.dismiss()
            reset.fn()
            valueView?.text = reset.label
            toast(tr("Reset to ${reset.label}", "Varsayılana döndü: ${reset.label}"))
            true
        }
        add(r)
        return this
    }

    /** An on/off row with a switch; tapping flips it and the sheet stays open. */
    fun toggle(icon: Int?, label: String, on: Boolean, sub: String? = null, fn: (Boolean) -> Unit): Sheet {
        val r = rowBase(icon, label, sub, DIM, TEXT)
        val sw = Switch(a, accent).apply { checked = on }
        r.addView(sw, LinearLayout.LayoutParams(dp(42), dp(24)).apply { leftMargin = dp(12) })
        r.setOnClickListener { sw.checked = !sw.checked; fn(sw.checked) }
        add(r)
        return this
    }

    /** One of several options: the selected one has a check mark and the accent colour. Picking closes the sheet. */
    fun choice(label: String, selected: Boolean, sub: String? = null, fn: () -> Unit): Sheet {
        val r = rowBase(null, label, sub, accent, if (selected) accent else TEXT, check = selected)
        r.setOnClickListener { dialog.dismiss(); if (!selected) fn() }
        add(r)
        return this
    }

    /** A short explanation in small dim text. */
    fun note(s: String): Sheet {
        body.addView(text(s, 12.5f, LABEL, lines = 0).apply { setPadding(dp(20), dp(6), dp(20), dp(6)) })
        return this
    }

    /**
     * Shows the sheet. Safe to call on a closing screen (nothing happens), twice on one sheet (shown once), and from a
     * double tap: a second sheet asked for while one this screen opened under half a second ago is still up is skipped.
     */
    fun show(): Dialog {
        if (a.isFinishing || a.isDestroyed) return dialog
        if (built) { if (!dialog.isShowing) safeShow(); return dialog }
        val prev = lastShown[a]
        if (prev != null && prev.first.get()?.isShowing == true && SystemClock.uptimeMillis() - prev.second < DOUBLE_TAP_MS) return dialog
        built = true
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                val r = dpf(22)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
                setStroke(dp(1), OUTLINE_BTN)
            }
        }
        root.addView(View(a).apply { background = round(HANDLE, dpf(2)) },
            LinearLayout.LayoutParams(dp(36), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(10); bottomMargin = dp(12) })
        val scroll = object : ScrollView(a) {
            // at most 85% of the screen as it is now (it may have been turned since), and never more than the window
            // leaves, so a long list always scrolls instead of being cut off at the bottom
            override fun onMeasure(w: Int, h: Int) {
                var max = (resources.displayMetrics.heightPixels * 0.85f).toInt()
                if (View.MeasureSpec.getMode(h) != View.MeasureSpec.UNSPECIFIED) max = minOf(max, View.MeasureSpec.getSize(h))
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(max.coerceAtLeast(0), View.MeasureSpec.AT_MOST))
            }
        }.apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        body.setPadding(0, 0, 0, dp(14))
        scroll.addView(body)
        root.addView(scroll)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(0))
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.65f)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            navigationBarColor = BG
        }
        safeShow()
        return dialog
    }

    private fun safeShow() {
        try {
            dialog.show()
            lastShown[a] = java.lang.ref.WeakReference(dialog) to SystemClock.uptimeMillis()
        } catch (_: WindowManager.BadTokenException) {   // the screen went away meanwhile
        } catch (_: IllegalStateException) {}
    }

    // --- pieces ------------------------------------------------------------------------------------

    private fun add(v: View) { body.addView(v, LinearLayout.LayoutParams(MATCH, WRAP)); rows++; lastWasSection = false }

    private fun divider(top: Int = 0) {
        body.addView(View(a).apply { setBackgroundColor(DIVIDER) }, LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = top })
    }

    private fun rowBase(icon: Int?, label: String, sub: String?, iconColor: Int, color: Int, check: Boolean = false): LinearLayout {
        val r = LinearLayout(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(54)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(-1))
        }
        when {
            check -> r.addView(icon(R.drawable.sheet_ic_check, accent), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
            icon != null -> r.addView(icon(icon, iconColor), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
            else -> r.addView(View(a), LinearLayout.LayoutParams(dp(22), dp(22)).apply { rightMargin = dp(18) })
        }
        val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(label, 15.5f, color, lines = 2))
        if (!sub.isNullOrBlank()) col.addView(text(sub, 12.5f, LABEL, lines = 3).apply { setPadding(0, dp(2), 0, 0) })
        r.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        return r
    }

    private fun actionTile(act: Action): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(2), dp(4), dp(2), dp(4))
        background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, round(-1, dpf(14)))
        val circle = ImageView(a).apply {
            setImageResource(act.icon)
            imageTintList = ColorStateList.valueOf(TEXT)
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(BG); setStroke(dp(1), OUTLINE_BTN) }
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        addView(circle, LinearLayout.LayoutParams(dp(54), dp(54)))
        addView(text(act.label, 12.5f, DIM, lines = 2).apply { gravity = Gravity.CENTER; setPadding(0, dp(7), 0, 0) })
        contentDescription = act.label
        setOnClickListener { dialog.dismiss(); act.fn() }
    }

    private fun icon(res: Int, tint: Int) = ImageView(a).apply { setImageResource(res); imageTintList = ColorStateList.valueOf(tint) }

    private fun toast(s: String) = android.widget.Toast.makeText(a, s, android.widget.Toast.LENGTH_SHORT).show()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false, lines: Int = 1) = TextView(a).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    private fun dp(v: Number) = (v.toFloat() * a.resources.displayMetrics.density + 0.5f).toInt()
    private fun dpf(v: Number) = v.toFloat() * a.resources.displayMetrics.density
    private fun round(c: Int, r: Float) = GradientDrawable().apply { setColor(c); cornerRadius = r }
    private fun box(r: Float, stroke: Int) = GradientDrawable().apply { setColor(BG); cornerRadius = r; setStroke(dp(1), stroke) }

    /** A small on/off switch drawn in the OLED style: an outlined track, grey when off, the accent when on; never filled. */
    private class Switch(ctx: Context, private val accent: Int) : View(ctx) {
        var checked = false
            set(v) { field = v; invalidate() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val h = height.toFloat(); val w = width.toFloat()
            rect.set(d, d, w - d, h - d)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 1.5f * d
            p.color = if (checked) accent else 0xFF555555.toInt()
            c.drawRoundRect(rect, h / 2, h / 2, p)
            p.style = Paint.Style.FILL
            p.color = if (checked) accent else 0xFF8A8A8A.toInt()
            val r = h / 2 - 4 * d
            c.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, r, p)
        }
    }

    companion object {
        const val BG = 0xFF000000.toInt()
        const val OUTLINE = 0xFF222222.toInt()
        const val OUTLINE_BTN = 0xFF333333.toInt()
        const val DIVIDER = 0xFF1A1A1A.toInt()
        const val HANDLE = 0xFF4A4A4A.toInt()
        const val TEXT = 0xFFE4E4E4.toInt()
        const val DIM = 0xFFA6A6A6.toInt()
        const val LABEL = 0xFF747474.toInt()
        const val FAINT = 0xFF484848.toInt()
        const val RED = 0xFFD46A6A.toInt()
        const val ACCENT = 0xFFE8A447.toInt()
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val DOUBLE_TAP_MS = 500L

        /** The sheet each screen showed last, and when (weak: a closed screen is not kept). Main thread only. */
        private val lastShown = java.util.WeakHashMap<Activity, Pair<java.lang.ref.WeakReference<Dialog>, Long>>()

        /** A plain list of actions (what a dropdown menu used to show), as a sheet. */
        fun list(a: Activity, title: String?, items: List<Pair<String, () -> Unit>>, accent: Int = ACCENT): Dialog {
            val s = Sheet(a, accent)
            if (title != null) s.header(title) else s.section(null)
            items.forEach { (label, fn) -> s.item(null, label, fn = fn) }
            return s.show()
        }

        /** Pick one of [options]; [selected] is checked. */
        fun choose(a: Activity, title: String, options: List<String>, selected: Int, accent: Int = ACCENT, sub: String? = null, onPick: (Int) -> Unit): Dialog {
            val s = Sheet(a, accent).header(title, sub)
            options.forEachIndexed { i, o -> s.choice(o, i == selected) { onPick(i) } }
            return s.show()
        }

        /** The language picker (System default / English / Türkçe). */
        fun language(a: Activity, accent: Int = ACCENT, onChanged: () -> Unit = { a.recreate() }) {
            val values = listOf(PlainLang.SYSTEM, PlainLang.EN, PlainLang.TR)
            choose(a, tr("Language", "Dil"), values.map { PlainLang.name(it) }, values.indexOf(PlainLang.choice).coerceAtLeast(0), accent,
                tr("Menus and messages in this app.", "Bu uygulamadaki menüler ve iletiler.")) { i ->
                if (values[i] != PlainLang.choice) { PlainLang.set(a, values[i]); onChanged() }
            }
        }

        /** The language row's value: "English", "Türkçe" or "System default (English)". */
        fun languageValue(): String = PlainLang.name(PlainLang.choice)

        /** Hold the language row: back to the phone's language. */
        fun languageReset(a: Activity, onChanged: () -> Unit = { a.recreate() }) =
            Reset(PlainLang.name(PlainLang.SYSTEM), closes = true) { if (PlainLang.choice != PlainLang.SYSTEM) { PlainLang.set(a, PlainLang.SYSTEM); onChanged() } }

        /** This app's version for the "Check for updates" row ("v3.6"). */
        fun version(a: Activity): String = try { "v" + a.packageManager.getPackageInfo(a.packageName, 0).versionName } catch (_: Exception) { "" }
    }
}

/** The icons the sheets use (Material icons, Apache 2.0), tinted by the sheet. */
object Ic {
    val ADD = R.drawable.sheet_ic_add
    val ADD_LIST = R.drawable.sheet_ic_playlist_add
    val ALBUM = R.drawable.sheet_ic_album
    val ARTIST = R.drawable.sheet_ic_person
    val CHECK = R.drawable.sheet_ic_check
    val CLOSE = R.drawable.sheet_ic_close
    val DELETE = R.drawable.sheet_ic_delete
    val DOWNLOAD = R.drawable.sheet_ic_download
    val EDIT = R.drawable.sheet_ic_edit
    val FOLDER = R.drawable.sheet_ic_folder
    val HISTORY = R.drawable.sheet_ic_history
    val INFO = R.drawable.sheet_ic_info
    val LANGUAGE = R.drawable.sheet_ic_translate
    val OPEN = R.drawable.sheet_ic_open
    val PLAY = R.drawable.sheet_ic_play
    val PAUSE = R.drawable.sheet_ic_pause
    val PLAY_NEXT = R.drawable.sheet_ic_play_next
    val QUEUE = R.drawable.sheet_ic_queue
    val REFRESH = R.drawable.sheet_ic_refresh
    val SEARCH = R.drawable.sheet_ic_search
    val SELECT = R.drawable.sheet_ic_select
    val TUNE = R.drawable.sheet_ic_tune
    val SHARE = R.drawable.sheet_ic_share
    val SHUFFLE = R.drawable.sheet_ic_shuffle
    val SLEEP = R.drawable.sheet_ic_sleep
    val SORT = R.drawable.sheet_ic_sort
    val SPEED = R.drawable.sheet_ic_speed
    val UPDATE = R.drawable.sheet_ic_update
    val VIDEO = R.drawable.sheet_ic_video
    val SHIELD = R.drawable.sheet_ic_shield
    val PIP = R.drawable.sheet_ic_pip
    val RESTORE = R.drawable.sheet_ic_restore
    val EYE = R.drawable.sheet_ic_eye
    val EYE_OFF = R.drawable.sheet_ic_eye_off
    val WIFI = R.drawable.sheet_ic_wifi
    val MAP = R.drawable.sheet_ic_map
    val PLACE = R.drawable.sheet_ic_place
    val LAYERS = R.drawable.sheet_ic_layers
    val GRID = R.drawable.sheet_ic_grid
    val LIST = R.drawable.sheet_ic_list
    val PHOTO = R.drawable.sheet_ic_photo
    val CAMERA = R.drawable.sheet_ic_camera
    val MIC = R.drawable.sheet_ic_mic
    val BATTERY = R.drawable.sheet_ic_battery
    val LOCK = R.drawable.sheet_ic_lock
    val LINK = R.drawable.sheet_ic_link
    val UPLOAD = R.drawable.sheet_ic_upload
    val STAR = R.drawable.sheet_ic_star
    val FILTER = R.drawable.sheet_ic_filter
    val FLAG = R.drawable.sheet_ic_flag
    val ROUTE = R.drawable.sheet_ic_route
    val LOGOUT = R.drawable.sheet_ic_logout
    val ACCOUNT = R.drawable.sheet_ic_account
    val BELL = R.drawable.sheet_ic_bell
    val CACHE = R.drawable.sheet_ic_cache
    val HELP = R.drawable.sheet_ic_help
    val COPY = R.drawable.sheet_ic_copy
    val SYNC = R.drawable.sheet_ic_sync
    val APPS = R.drawable.sheet_ic_apps
}
