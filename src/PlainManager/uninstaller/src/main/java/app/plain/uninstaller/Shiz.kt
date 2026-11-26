package app.plain.uninstaller

import android.content.Context
import android.content.pm.PackageManager
import app.plain.lang.tr
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * Everything that talks to Shizuku. Every call is wrapped so that a missing, stopped, outdated
 * (pre-v11) or unauthorised Shizuku shows up as a [State], never as a crash.
 */
object Shiz {
    const val PKG = Safety.SHIZUKU
    const val REQUEST_CODE = 41

    enum class State { NOT_INSTALLED, NOT_RUNNING, TOO_OLD, NO_PERMISSION, DENIED, READY }

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: Exception) { false }

    fun running(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun state(ctx: Context): State {
        if (!running()) return if (installed(ctx)) State.NOT_RUNNING else State.NOT_INSTALLED
        return try {
            when {
                Shizuku.isPreV11() || Shizuku.getVersion() < 11 -> State.TOO_OLD
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> State.READY
                // true after "Deny" with "don't ask again": only the Shizuku app can grant it now
                Shizuku.shouldShowRequestPermissionRationale() -> State.DENIED
                else -> State.NO_PERMISSION
            }
        } catch (_: Throwable) {
            State.NOT_RUNNING
        }
    }

    /** Shows Shizuku's own "Allow Plain Manager?" dialog. False if it couldn't be asked. */
    fun requestPermission(): Boolean = try {
        if (Shizuku.isPreV11()) false else { Shizuku.requestPermission(REQUEST_CODE); true }
    } catch (_: Throwable) { false }

    fun versionText(): String? = try { if (running()) "v" + Shizuku.getVersion() else null } catch (_: Throwable) { null }

    /**
     * Shizuku.newProcess is private in API 13 (the library would rather apps use a UserService), but it is
     * still the simplest way to run a few `pm` commands. Looked up once; proguard-rules.pro keeps it.
     */
    private val newProcess: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    /** Runs `sh -c [cmd]` as the shell user. Blocking: call from a background thread. Never throws. */
    fun run(cmd: String): ShellResult {
        val m = newProcess ?: return ShellResult(-1, "", tr("Shizuku API not available", "Shizuku API'si kullanılamıyor"))
        return try {
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            try {
                p.outputStream.close()
                var err = ""
                val errThread = thread(name = "shizuku-stderr") { err = readAll(p.errorStream) }
                val out = readAll(p.inputStream)
                val exit = p.waitFor()
                errThread.join(5000)
                ShellResult(exit, out, err)
            } finally {
                try { p.destroy() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            ShellResult(-1, "", cause.javaClass.simpleName + ": " + (cause.message ?: "failed"))
        }
    }

    private fun readAll(s: InputStream): String = try {
        s.bufferedReader().use { it.readText() }
    } catch (_: Throwable) { "" }
}
