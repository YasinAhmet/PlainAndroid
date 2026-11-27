package app.plain.setup

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

/**
 * Installs a queue of offers one by one through PackageInstaller sessions.
 * Bundled APKs stream straight from assets; online ones are downloaded to the cache, checked
 * (SHA-256 from apps.json + signed with the same key as Setup) and then installed.
 * Before replacing an installed app, its current APK is saved by [Backups] (one per app).
 * Android shows its own confirm dialog per app; on Android 12+ updates of apps Setup installed
 * itself go through without one.
 */
object Installer {
    const val ACTION_STATUS = "app.plain.setup.INSTALL_STATUS"

    interface Listener {
        fun onStatus(text: String)
        fun onConfirm(confirm: Intent)
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
    /** Android's confirm dialog is up for [current] ("Skip this app" is offered meanwhile). */
    @Volatile var confirming = false; private set

    fun start(ctx: Context, offers: List<Offer>) {
        val app = ctx.applicationContext
        // Setup updating itself kills this process, so it always goes last.
        queue.addAll(offers.sortedBy { if (it.entry.pkg == app.packageName) 1 else 0 })
        if (current == null) next(app)
    }

    /** The confirm dialog was dismissed without a result (some phones never report it). */
    fun skipCurrent(ctx: Context) {
        val c = current ?: return
        finish(ctx.applicationContext, c.entry.pkg, false, tr("Skipped", "Atlandı"))
    }

    private fun next(app: Context) {
        val o = queue.removeFirstOrNull()
        current = o
        if (o == null) { main.post { listener?.onQueueDone() }; return }
        worker.execute {
            try {
                install(app, o)
            } catch (e: Exception) {
                main.post { finish(app, o.entry.pkg, false, e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun status(s: String) { confirming = false; main.post { listener?.onStatus(s) } }

    private fun install(app: Context, o: Offer) {
        val e = o.entry
        if (o.source != Source.LOCAL && e.pkg != app.packageName) {
            status(tr("Saving a copy of the installed ${e.name}…", "Yüklü ${e.name} yedekleniyor…"))
            Backups.save(app, e.pkg, e.versionCode) // "I updated but didn't like it" → Older versions… → this copy
        }
        when (o.source) {
            Source.LOCAL -> {
                val f = File(e.url)
                if (!f.isFile) throw IOException(tr("The saved copy is gone", "Kaydedilen kopya artık yok"))
                verify(app, f, e.pkg, deleteOnFail = false)
                status(installing(e))
                f.inputStream().use { commit(app, e, it, f.length()) }
            }
            Source.BUNDLED -> {
                status(installing(e))
                val path = "apks/${e.file}"
                // openFd needs a stored (uncompressed) asset; fall back to a plain stream of unknown size if it isn't.
                val fd = try { app.assets.openFd(path) } catch (_: IOException) { null }
                if (fd != null) fd.use { f -> f.createInputStream().use { commit(app, e, it, f.length) } }
                else app.assets.open(path).use { commit(app, e, it, -1L) }
            }
            Source.ONLINE -> {
                val dir = File(app.cacheDir, "apks").apply { mkdirs() }
                val f = File(dir, e.file.ifEmpty { "${e.pkg}.apk" })
                status(tr("Downloading ${e.name} ${e.versionName}…", "${e.name} ${e.versionName} indiriliyor…"))
                val sha = Net.download(e.url, f) { p ->
                    if (p >= 0) status(tr("Downloading ${e.name} ${e.versionName}… ${(p * 100).toInt()}%", "${e.name} ${e.versionName} indiriliyor… %${(p * 100).toInt()}"))
                }
                if (e.sha256.isNotEmpty() && sha != e.sha256) { f.delete(); throw IOException(tr("Download damaged (checksum mismatch)", "İndirme bozuk (sağlama toplamı tutmuyor)")) }
                verify(app, f, e.pkg)
                status(installing(e))
                f.inputStream().use { commit(app, e, it, f.length()) }
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

    private fun commit(app: Context, e: Entry, input: InputStream, size: Long) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(e.pkg)
            if (size > 0) setSize(size)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                s.openWrite("base.apk", 0, if (size > 0) size else -1).use { out ->
                    input.copyTo(out, 64 * 1024)
                    s.fsync(out)
                }
                val intent = Intent(app, InstallReceiver::class.java).setAction(ACTION_STATUS).putExtra("pkg", e.pkg)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                s.commit(PendingIntent.getBroadcast(app, id, intent, flags).intentSender)
            }
        } catch (ex: Exception) {
            try { pi.abandonSession(id) } catch (_: Exception) {}
            throw ex
        }
        status(tr("Waiting for Android to install ${e.name}…", "Android'in ${e.name} uygulamasını yüklemesi bekleniyor…"))
    }

    /** From [InstallReceiver]. */
    fun onResult(app: Context, pkg: String, status: Int, message: String?, confirm: Intent?) {
        val c = current ?: return
        if (c.entry.pkg != pkg) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) { finish(app, pkg, false, tr("Android didn't ask for confirmation", "Android onay istemedi")); return }
                confirming = true
                val l = listener
                if (l != null) l.onConfirm(confirm)
                else try { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                main.post { listener?.onStatus(tr("Confirm the install of ${c.entry.name}", "${c.entry.name} yüklemesini onaylayın")) }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(app, pkg, true, tr("Installed ${c.entry.versionName}", "${c.entry.versionName} yüklendi"))
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(app, pkg, false, tr("Cancelled", "İptal edildi"))
            // Play Protect (the package verifier) or a device policy refused it.
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                finish(app, pkg, false, tr("Blocked, likely by Play Protect", "Engellendi, büyük olasılıkla Play Protect"), blocked = true)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                finish(app, pkg, false, tr("Conflicts with the installed copy (signed differently?). Uninstall it first, then install.",
                    "Yüklü kopyayla çakışıyor (farklı imzalı olabilir). Önce onu kaldırın, sonra yükleyin."))
            PackageInstaller.STATUS_FAILURE_STORAGE -> finish(app, pkg, false, tr("Not enough storage", "Yeterli depolama alanı yok"))
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> finish(app, pkg, false, tr("Not compatible with this phone", "Bu telefonla uyumlu değil"))
            else -> finish(app, pkg, false, message ?: tr("Install failed", "Yükleme başarısız"),
                blocked = message?.contains("VERIFICATION", ignoreCase = true) == true)
        }
    }

    private fun finish(app: Context, pkg: String, ok: Boolean, msg: String, blocked: Boolean = false) {
        if (current?.entry?.pkg != pkg) return
        current = null
        confirming = false
        File(app.cacheDir, "apks").listFiles()?.forEach { it.delete() }
        listener?.onItemDone(pkg, ok, msg, blocked)
        next(app)
    }
}
