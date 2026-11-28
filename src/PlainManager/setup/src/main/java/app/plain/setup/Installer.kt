package app.plain.setup

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.plain.lang.tr
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Installs a queue of offers one by one through PackageInstaller sessions.
 * Bundled APKs stream straight from assets; online ones are downloaded to the cache, checked
 * (SHA-256 from apps.json + signed with the same key as Setup) and then installed.
 * Before replacing an installed app, its current APK is saved by [Backups] (one per app).
 * Android shows its own confirm dialog per app; on Android 12+ updates of apps Setup installed
 * itself go through without one.
 *
 * The queue lives in this process, not in the screen: the screen can be closed, recreated or in the background
 * while it runs. [attach] brings a (new) screen up to date: progress line, "Skip this app", and a confirm dialog
 * that arrived while no screen could show it (Android 10+ doesn't let a background app open one).
 */
object Installer {
    const val ACTION_STATUS = "app.plain.setup.INSTALL_STATUS"
    /** The PackageInstaller session a status broadcast belongs to (a late answer for a skipped app is ignored). */
    const val EXTRA_SESSION = "session"

    interface Listener {
        fun onStatus(text: String)
        /** Shows Android's confirm dialog. False when the screen can't show it now (it isn't visible). */
        fun onConfirm(confirm: Intent): Boolean
        /** [blocked]: Android or Play Protect refused the app (not the user cancelling). */
        fun onItemDone(pkg: String, ok: Boolean, message: String, blocked: Boolean)
        fun onQueueDone()
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Offer>()
    @Volatile var current: Offer? = null; private set
    val busy: Boolean get() = current != null || queue.isNotEmpty()
    /** Android's confirm dialog is up for [current]. */
    @Volatile var confirming = false; private set
    /** The last progress line of [current], for a screen that comes back while the queue runs. */
    @Volatile var lastStatus = ""; private set
    /** PackageInstaller session of [current] once it exists, else -1. */
    @Volatile private var session = -1
    private var job: Future<*>? = null
    /** A confirm dialog that came while no screen could show it; [attach] shows it. */
    private var unshown: Intent? = null

    fun start(ctx: Context, offers: List<Offer>) {
        val app = ctx.applicationContext
        val idle = !busy
        // No app twice (a second "Check for updates" from an app while its update runs); Setup itself always last,
        // because updating it kills this process.
        val order = Catalog.queueOrder(queue.toList(), current?.entry?.pkg, offers, app.packageName)
        queue.clear(); queue.addAll(order)
        // Sessions left over from an earlier run (process killed, dialog never answered) count against Android's limit.
        if (idle) worker.execute { dropStaleSessions(app) }
        if (current == null) next(app)
    }

    /** A screen is (back) in front: show it where the queue is, and a confirm dialog it missed. */
    fun attach(l: Listener) {
        listener = l
        if (current == null) return
        l.onStatus(lastStatus)
        val u = unshown ?: return
        unshown = null
        if (!l.onConfirm(u)) unshown = u
    }

    /**
     * "Skip this app": while downloading, or when the confirm dialog was dismissed without a result (some phones never
     * report it). Its session is abandoned, so a dialog still open somewhere can't install it later.
     */
    fun skipCurrent(ctx: Context) {
        val c = current ?: return
        val app = ctx.applicationContext
        job?.cancel(true)
        val sid = session
        if (sid >= 0) try { app.packageManager.packageInstaller.abandonSession(sid) } catch (_: Exception) {}
        finish(app, c, false, tr("Skipped", "Atlandı"))
    }

    private fun next(app: Context) {
        val o = queue.removeFirstOrNull()
        current = o
        session = -1
        confirming = false
        unshown = null
        lastStatus = ""
        if (o == null) { main.post { listener?.onQueueDone() }; return }
        job = worker.submit {
            try {
                install(app, o)
            } catch (e: Throwable) {
                main.post { finish(app, o, false, Net.describe(e)) }
            }
        }
    }

    private fun status(o: Offer, s: String) {
        if (current !== o) return
        confirming = false
        lastStatus = s
        main.post { if (current === o) listener?.onStatus(s) }
    }

    /** Stops the work for [o] when it was skipped meanwhile. */
    private fun alive(o: Offer) {
        if (current !== o || Thread.currentThread().isInterrupted) throw IOException(tr("Skipped", "Atlandı"))
    }

    private fun install(app: Context, o: Offer) {
        val e = o.entry
        if (o.source != Source.LOCAL && e.pkg != app.packageName) {
            status(o, tr("Saving a copy of the installed ${e.name}…", "Yüklü ${e.name} yedekleniyor…"))
            Backups.save(app, e.pkg, e.versionCode) // "I updated but didn't like it" → Older versions… → this copy
        }
        alive(o)
        when (o.source) {
            Source.LOCAL -> {
                val f = File(e.url)
                if (!f.isFile) throw IOException(tr("The saved copy is gone", "Kaydedilen kopya artık yok"))
                verify(app, f, e.pkg, deleteOnFail = false)
                status(o, installing(e))
                f.inputStream().use { commit(app, o, it, f.length()) }
            }
            Source.BUNDLED -> {
                status(o, installing(e))
                val path = "apks/${e.file}"
                // openFd needs a stored (uncompressed) asset; fall back to a plain stream of unknown size if it isn't.
                val fd = try { app.assets.openFd(path) } catch (_: IOException) { null }
                if (fd != null) fd.use { f -> f.createInputStream().use { commit(app, o, it, f.length) } }
                else app.assets.open(path).use { commit(app, o, it, -1L) }
            }
            Source.ONLINE -> {
                val dir = File(app.cacheDir, "apks").apply { mkdirs() }
                val f = File(dir, Catalog.cacheName(e))
                status(o, tr("Downloading ${e.name} ${e.versionName}…", "${e.name} ${e.versionName} indiriliyor…"))
                val sha = Net.download(e.url, f) { p ->
                    if (p >= 0) status(o, tr("Downloading ${e.name} ${e.versionName}… ${(p * 100).toInt()}%", "${e.name} ${e.versionName} indiriliyor… %${(p * 100).toInt()}"))
                }
                if (e.sha256.isNotEmpty() && sha != e.sha256) { f.delete(); throw IOException(tr("Download damaged (checksum mismatch)", "İndirme bozuk (sağlama toplamı tutmuyor)")) }
                alive(o)
                verify(app, f, e.pkg)
                status(o, installing(e))
                f.inputStream().use { commit(app, o, it, f.length()) }
            }
        }
    }

    private fun installing(e: Entry) = tr("Installing ${e.name} ${e.versionName}…", "${e.name} ${e.versionName} yükleniyor…")

    /** The APK must be [pkg] and signed with Setup's own key; otherwise it isn't ours. */
    private fun verify(app: Context, f: File, pkg: String, deleteOnFail: Boolean = true) {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(f.path, flags) ?: throw IOException(tr("Not a valid APK", "Geçerli bir APK değil"))
        if (info.packageName != pkg) throw IOException(tr("Wrong app inside the download (${info.packageName})", "İndirilen dosyada başka bir uygulama var (${info.packageName})"))
        val mine = certs(pm.getPackageInfo(app.packageName, flags))
        val theirs = certs(info)
        // Some old Android versions can't read an archive's certificate; the checksum still covers it.
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs != mine) {
            if (deleteOnFail) f.delete(); throw IOException(tr("Not signed by the Plain apps' key — refused", "Plain uygulamalarının anahtarıyla imzalanmamış — reddedildi"))
        }
    }

    private fun certs(info: PackageInfo): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        @Suppress("DEPRECATION")
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return emptySet()
            if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        } else info.signatures
        return sigs.orEmpty().map { Net.hex(md.digest(it.toByteArray())) }.toSet()
    }

    private fun commit(app: Context, o: Offer, input: InputStream, size: Long) {
        val e = o.entry
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(e.pkg)
            if (size > 0) setSize(size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        if (current === o) session = id
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, if (size > 0) size else -1).use { out ->
                    input.copyTo(out, 64 * 1024)
                    s.fsync(out)
                }
                alive(o) // skipped while writing: abandoned below instead of committed
                val intent = Intent(app, InstallReceiver::class.java).setAction(ACTION_STATUS)
                    .putExtra("pkg", e.pkg).putExtra(EXTRA_SESSION, id)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (ex: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            throw ex
        }
        status(o, tr("Waiting for Android to install ${e.name}…", "Android'in ${e.name} uygulamasını yüklemesi bekleniyor…"))
    }

    /** From [InstallReceiver]. [sid]: the session the answer is for (-1 when the broadcast doesn't say). */
    fun onResult(app: Context, pkg: String, sid: Int, status: Int, message: String?, confirm: Intent?) {
        val c = current ?: return
        if (c.entry.pkg != pkg) return
        if (sid >= 0 && sid != session) return // a late answer for an app that was skipped
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) { finish(app, c, false, tr("Android didn't ask for confirmation", "Android onay istemedi")); return }
                confirming = true
                val text = tr("Confirm the install of ${c.entry.name}", "${c.entry.name} yüklemesini onaylayın")
                lastStatus = text
                if (listener?.onConfirm(confirm) != true) {
                    // No visible Setup screen. Before Android 10, or while another tab of this app is in front, Android
                    // lets us open the dialog anyway; otherwise it waits until the Setup tab comes back ([attach]).
                    if (Build.VERSION.SDK_INT < 29 || inFront()) {
                        try { app.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { unshown = confirm }
                    } else unshown = confirm
                }
                main.post { if (current === c) listener?.onStatus(text) }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(app, c, true, tr("Installed ${c.entry.versionName}", "${c.entry.versionName} yüklendi"))
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(app, c, false, tr("Cancelled", "İptal edildi"))
            // Play Protect (the package verifier) or a device policy refused it.
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                finish(app, c, false, tr("Blocked, likely by Play Protect", "Engellendi, büyük olasılıkla Play Protect"), blocked = true)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                finish(app, c, false, tr("Conflicts with the installed copy (signed differently?). Uninstall it first, then install.",
                    "Yüklü kopyayla çakışıyor (farklı imzalı olabilir). Önce onu kaldırın, sonra yükleyin."))
            PackageInstaller.STATUS_FAILURE_STORAGE -> finish(app, c, false, tr("Not enough storage", "Yeterli depolama alanı yok"))
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> finish(app, c, false,
                if (message?.contains("DOWNGRADE", ignoreCase = true) == true)
                    tr("A newer version is installed: Android won't put an older one over it",
                        "Daha yeni bir sürüm yüklü: Android eskisini onun üzerine yüklemez")
                else tr("Not compatible with this phone", "Bu telefonla uyumlu değil"))
            else -> finish(app, c, false, message ?: tr("Install failed", "Yükleme başarısız"),
                blocked = message?.contains("VERIFICATION", ignoreCase = true) == true)
        }
    }

    /** This app has a screen in front (another tab, say), so Android lets it open the confirm dialog. */
    private fun inFront(): Boolean = try {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } catch (_: Exception) { false }

    /** Abandons this app's PackageInstaller sessions except the current one. Worker thread. */
    private fun dropStaleSessions(app: Context) {
        try {
            val pi = app.packageManager.packageInstaller
            for (s in pi.mySessions) if (s.sessionId != session) try { pi.abandonSession(s.sessionId) } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun finish(app: Context, o: Offer, ok: Boolean, msg: String, blocked: Boolean = false) {
        if (current !== o) return
        current = null
        confirming = false
        session = -1
        unshown = null
        job = null
        lastStatus = ""
        File(app.cacheDir, "apks").listFiles()?.forEach { it.delete() }
        listener?.onItemDone(o.entry.pkg, ok, msg, blocked)
        next(app)
    }
}
