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
        fun onItemDone(pkg: String, ok: Boolean, message: String)
        fun onQueueDone()
    }

    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val queue = ArrayDeque<Offer>()
    @Volatile var current: Offer? = null; private set
    val busy: Boolean get() = current != null || queue.isNotEmpty()

    fun start(ctx: Context, offers: List<Offer>) {
        val app = ctx.applicationContext
        // Setup updating itself kills this process, so it always goes last.
        queue.addAll(offers.sortedBy { if (it.entry.pkg == app.packageName) 1 else 0 })
        if (current == null) next(app)
    }

    /** The confirm dialog was dismissed without a result (some phones never report it). */
    fun skipCurrent(ctx: Context) {
        val c = current ?: return
        finish(ctx.applicationContext, c.entry.pkg, false, "Skipped")
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

    private fun status(s: String) = main.post { listener?.onStatus(s) }

    private fun install(app: Context, o: Offer) {
        val e = o.entry
        if (o.source != Source.LOCAL && e.pkg != app.packageName) {
            status("Saving a copy of the installed ${e.name}…")
            Backups.save(app, e.pkg, e.versionCode) // "I updated but didn't like it" → Older versions… → this copy
        }
        when (o.source) {
            Source.LOCAL -> {
                val f = File(e.url)
                if (!f.isFile) throw IOException("The saved copy is gone")
                verify(app, f, e.pkg, deleteOnFail = false)
                status("Installing ${e.name} ${e.versionName}…")
                f.inputStream().use { commit(app, e, it, f.length()) }
            }
            Source.BUNDLED -> {
                status("Installing ${e.name} ${e.versionName}…")
                app.assets.openFd("apks/${e.file}").use { fd -> fd.createInputStream().use { commit(app, e, it, fd.length) } }
            }
            Source.ONLINE -> {
                val dir = File(app.cacheDir, "apks").apply { mkdirs() }
                val f = File(dir, e.file.ifEmpty { "${e.pkg}.apk" })
                status("Downloading ${e.name} ${e.versionName}…")
                val sha = Net.download(e.url, f) { p ->
                    if (p >= 0) status("Downloading ${e.name} ${e.versionName}… ${(p * 100).toInt()}%")
                }
                if (e.sha256.isNotEmpty() && sha != e.sha256) { f.delete(); throw IOException("Download damaged (checksum mismatch)") }
                verify(app, f, e.pkg)
                status("Installing ${e.name} ${e.versionName}…")
                f.inputStream().use { commit(app, e, it, f.length()) }
            }
        }
    }

    /** The APK must be [pkg] and signed with Setup's own key; otherwise it isn't ours. */
    private fun verify(app: Context, f: File, pkg: String, deleteOnFail: Boolean = true) {
        val pm = app.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(f.path, flags) ?: throw IOException("Not a valid APK")
        if (info.packageName != pkg) throw IOException("Wrong app inside the download (${info.packageName})")
        val mine = certs(pm.getPackageInfo(app.packageName, flags))
        val theirs = certs(info)
        // Some old Android versions can't read an archive's certificate; the checksum still covers it.
        if (theirs.isNotEmpty() && mine.isNotEmpty() && theirs != mine) {
            if (deleteOnFail) f.delete(); throw IOException("Not signed by the Plain apps' key — refused")
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
        status("Waiting for Android to install ${e.name}…")
    }

    /** From [InstallReceiver]. */
    fun onResult(app: Context, pkg: String, status: Int, message: String?, confirm: Intent?) {
        val c = current ?: return
        if (c.entry.pkg != pkg) return
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                if (confirm == null) { finish(app, pkg, false, "Android didn't ask for confirmation"); return }
                val l = listener
                if (l != null) l.onConfirm(confirm)
                else try { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                main.post { listener?.onStatus("Confirm the install of ${c.entry.name}") }
            }
            PackageInstaller.STATUS_SUCCESS -> finish(app, pkg, true, "Installed ${c.entry.versionName}")
            PackageInstaller.STATUS_FAILURE_ABORTED -> finish(app, pkg, false, "Cancelled")
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                finish(app, pkg, false, "Conflicts with the installed copy (signed differently?). Uninstall it first, then install.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> finish(app, pkg, false, "Not enough storage")
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> finish(app, pkg, false, "Not compatible with this phone")
            else -> finish(app, pkg, false, message ?: "Install failed")
        }
    }

    private fun finish(app: Context, pkg: String, ok: Boolean, msg: String) {
        if (current?.entry?.pkg != pkg) return
        current = null
        File(app.cacheDir, "apks").listFiles()?.forEach { it.delete() }
        listener?.onItemDone(pkg, ok, msg)
        next(app)
    }
}
