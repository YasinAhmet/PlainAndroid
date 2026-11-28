package app.plain.migrate

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.lang.PlainLang
import app.plain.lang.tr
import app.plain.update.PlainUpdate
import java.util.Locale

/**
 * Shown before a tab opens for the first time while its former app is still installed:
 * "Copy your Plain Trekking data?" — Copy / Start fresh / Not now. After copying it offers to remove the former app
 * (Android asks to confirm). The former app is never changed by any of this.
 */
class ImportActivity : Activity() {
    companion object {
        const val EXTRA_TAB = "tab"
        const val EXTRA_FORWARD = "forward"
        private const val REQ_REMOVE = 7
        private const val BG = 0xFF000000.toInt()
        private const val OUTLINE = 0xFF3A3A3A.toInt()
        private const val TEXT = 0xFFE4E4E4.toInt()
        private const val DIM = 0xFFA6A6A6.toInt()
        private const val RED = 0xFFD46A6A.toInt()
        private const val GREEN = 0xFF6CBF6C.toInt()
    }

    private lateinit var tab: Tab
    private lateinit var spec: Migrate.Spec
    private lateinit var body: LinearLayout
    @Volatile private var working = false
    private var finishedCopy = false
    private var plan: Migrate.Plan? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        val t = Hub.tab(intent.getStringExtra(EXTRA_TAB))
        val s = t?.import
        if (t == null || s == null) { finish(); return }
        tab = t; spec = s
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(20)) }
        val scroll = ScrollView(this).apply { setBackgroundColor(BG); addView(body) }
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars()).let { v.setPadding(it.left, it.top, it.right, it.bottom) }
            else v.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        if (!working && !finishedCopy) show()
    }

    @Deprecated("No AndroidX here")
    override fun onBackPressed() {
        if (!working) super.onBackPressed()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = sp; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(6), 0, dp(6))
    }

    /** The app's colour (outlines and text only, never a fill: the OLED style). */
    private val accent get() = Hub.accent

    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) accent else TEXT)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            setColor(BG); setStroke(dp(1), if (primary) accent else OUTLINE)
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    private fun mb(b: Long) = String.format(Locale.US, "%.1f MB", b / 1048576.0)
    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"
    private fun settings(n: Int) = if (n == 1) "1 settings file" else "$n settings files"

    private fun reset(title: String) {
        body.removeAllViews()
        body.addView(text(Hub.appName + "  ·  " + tab.label, 13f, DIM))
        body.addView(text(title, 22f, TEXT, bold = true))
    }

    /** First screen: depends on whether the former app can hand its data over. */
    private fun show() {
        if (!Migrate.installed(this, spec.pkg)) { openTab(); return }
        reset(tr("Copy your ${spec.name} data?", "${spec.name} verileriniz kopyalansın mı?"))
        body.addView(text(tr("${spec.name} is still installed. ${Hub.appName} can copy ${spec.whatEn} into its ${tab.en} tab. " +
            "${spec.name} itself is not changed.",
            "${spec.name} hâlâ yüklü. ${Hub.appName}, ${spec.whatTr} ${tab.trName} sekmesine kopyalayabilir. " +
            "${spec.name}'in kendisi değişmez."), 15f, DIM))
        if (!Migrate.hasBridge(this, spec.pkg)) {
            body.addView(text(tr("First update ${spec.name} (now ${Migrate.versionName(this, spec.pkg) ?: "?"}): only the newest version can hand its data over. " +
                "Tap Update, install it, then come back here.",
                "Önce ${spec.name} uygulamasını güncelleyin (şu an ${Migrate.versionName(this, spec.pkg) ?: "?"}): verilerini yalnızca en yeni sürüm aktarabilir. " +
                "Güncelle'ye dokunun, yükleyin, sonra buraya dönün."), 15f, accent))
            body.addView(button(tr("Update ${spec.name}", "${spec.name}'i güncelle"), true) {
                try { startActivity(Intent(PlainUpdate.ACTION).setPackage(PlainUpdate.SETUP_PKG).putExtra(PlainUpdate.EXTRA_PACKAGE, spec.pkg)) }
                catch (_: Exception) { PlainUpdate.check(this) }
            })
            addFreshAndLater()
            return
        }
        val sizeLine = text(tr("Counting…", "Hesaplanıyor…"), 14f, DIM)
        body.addView(sizeLine)
        val copy = button(tr("Copy", "Kopyala"), true) { start() }.apply { isEnabled = false; alpha = 0.4f }
        body.addView(copy)
        addFreshAndLater()
        Thread {
            val r = runCatching { Migrate.plan(this, spec) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { p ->
                    plan = p
                    sizeLine.text = tr("${files(p.files.size)}, ${mb(p.bytes)}, ${settings(p.prefs.size)}. Free space: ${mb(Migrate.freeBytes(this))}.",
                        "${p.files.size} dosya, ${mb(p.bytes)}, ${p.prefs.size} ayar dosyası. Boş alan: ${mb(Migrate.freeBytes(this))}.")
                    copy.isEnabled = true; copy.alpha = 1f
                }.onFailure { e ->
                    sizeLine.setTextColor(RED)
                    sizeLine.text = tr("${spec.name} didn't answer: ${e.message}", "${spec.name} yanıt vermedi: ${e.message}")
                }
            }
        }.start()
    }

    private fun addFreshAndLater() {
        body.addView(button(tr("Start fresh", "Boş başla"), false) {
            AlertDialog.Builder(this)
                .setTitle(tr("Start ${tab.en} empty?", "${tab.trName} boş başlasın mı?"))
                .setMessage(tr("Nothing is copied, and you won't be asked again. ${spec.name} keeps its data until you remove it.",
                    "Hiçbir şey kopyalanmaz ve bir daha sorulmaz. ${spec.name} siz kaldırana kadar verilerini saklar."))
                .setPositiveButton(tr("Start fresh", "Boş başla")) { _, _ -> Migrate.setState(this, tab, "fresh"); openTab() }
                .setNegativeButton(tr("Cancel", "İptal"), null)
                .show()
        })
        body.addView(button(tr("Not now", "Şimdi değil"), false) { finish() })
    }

    private fun start() {
        val p = plan ?: return
        working = true
        reset(tr("Copying from ${spec.name}…", "${spec.name}'den kopyalanıyor…"))
        val line = text("", 15f, DIM)
        body.addView(line)
        body.addView(text(tr("Keep this screen open.", "Bu ekranı açık tutun."), 13f, DIM))
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val total = p.bytes
        Thread {
            val r = runCatching {
                Migrate.run(this, spec, p) { f, b ->
                    runOnUiThread { line.text = tr("$f / ${files(p.files.size)} · ${mb(b)} / ${mb(total)}", "$f / ${p.files.size} dosya · ${mb(b)} / ${mb(total)}") }
                }
            }
            runOnUiThread {
                working = false
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { done(it, p) }.onFailure { failed(it) }
            }
        }.start()
    }

    private fun failed(e: Throwable) {
        reset(tr("Copy stopped", "Kopyalama durdu"))
        val m = e.message.orEmpty()
        body.addView(text(if (m.startsWith("NOSPACE:")) tr("Not enough free space: ${mb(m.removePrefix("NOSPACE:").toLongOrNull() ?: 0)} needed, ${mb(Migrate.freeBytes(this))} free. Free some space and try again.",
                "Yeterli boş alan yok: ${mb(m.removePrefix("NOSPACE:").toLongOrNull() ?: 0)} gerekli, ${mb(Migrate.freeBytes(this))} boş. Biraz yer açıp yeniden deneyin.")
            else tr("Error: $m\n\nWhat was copied is kept; trying again continues from there. ${spec.name} is unchanged.",
                "Hata: $m\n\nKopyalanan kısım duruyor; yeniden denemek oradan sürdürür. ${spec.name} değişmedi."), 15f, RED))
        body.addView(button(tr("Try again", "Yeniden dene"), true) { show() })
        body.addView(button(tr("Not now", "Şimdi değil"), false) { finish() })
    }

    private fun done(r: Migrate.Result, p: Migrate.Plan) {
        Migrate.setState(this, tab, "done")
        finishedCopy = true
        reset(tr("Copied", "Kopyalandı"))
        body.addView(text(tr("${files(r.files)} (${mb(r.bytes)}) and ${settings(r.prefs)} are now in ${tab.en}.",
            "${r.files} dosya (${mb(r.bytes)}) ve ${r.prefs} ayar dosyası artık ${tab.trName} sekmesinde."), 15f, GREEN))
        if (r.kept > 0) body.addView(text(tr("${files(r.kept)} ${Hub.appName} already had were kept as they were.",
            "${Hub.appName}'de zaten olan ${r.kept} dosya olduğu gibi bırakıldı."), 14f, DIM))
        body.addView(text(tr("You can now remove ${spec.name}. Files in your shared folders (Music, Pictures, DCIM…) stay.",
            "Artık ${spec.name} uygulamasını kaldırabilirsiniz. Ortak klasörlerinizdeki dosyalar (Müzik, Resimler, DCIM…) kalır."), 15f, DIM))
        if (p.trashed != 0) body.addView(text(tr("Careful: ${spec.name} has ${if (p.trashed > 0) p.trashed.toString() else "some"} items in the trash or half-saved. " +
            "Android deletes those for good together with the app. Open ${spec.name} and restore (or empty) its trash before removing it.",
            "Dikkat: ${spec.name} uygulamasının çöp kutusunda ya da yarım kaydedilmiş ${if (p.trashed > 0) p.trashed.toString() else "bazı"} öğe var. " +
            "Android bunları uygulamayla birlikte kalıcı olarak siler. Kaldırmadan önce ${spec.name} uygulamasını açıp çöp kutusunu geri yükleyin (ya da boşaltın)."), 15f, RED))
        body.addView(button(tr("Open ${tab.en}", "${tab.trName} sekmesini aç"), true) { openTab() })
        body.addView(button(tr("Remove ${spec.name}…", "${spec.name}'i kaldır…"), false) {
            @Suppress("DEPRECATION")
            val i = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:${spec.pkg}")).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            try { startActivityForResult(i, REQ_REMOVE) } catch (_: Exception) {
                try { startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${spec.pkg}"))) } catch (_: Exception) {}
            }
        })
    }

    @Deprecated("No AndroidX here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REMOVE && !Migrate.installed(this, spec.pkg)) openTab()
    }

    private fun openTab() {
        @Suppress("DEPRECATION")
        val fwd = intent.getParcelableExtra<Intent>(EXTRA_FORWARD)
        startActivity(Hub.tabIntent(this, tab, fwd))
        finish()
    }
}
