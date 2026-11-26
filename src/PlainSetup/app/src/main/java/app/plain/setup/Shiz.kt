package app.plain.setup

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.lang.reflect.Method
import kotlin.concurrent.thread

/**
 * Quiet installs through Shizuku (same helper as Plain Uninstaller's Shiz.kt).
 * `pm install` run with shell rights counts as an adb install: Android shows no confirm dialog, and
 * Play Protect only checks it when "Verify apps over USB" is on. Every call is wrapped so a missing,
 * stopped, outdated or unauthorised Shizuku shows up as a [State], never as a crash.
 */
object Shiz {
    const val PKG = "moe.shizuku.privileged.api"
    const val REQUEST_CODE = 42

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
                Shizuku.shouldShowRequestPermissionRationale() -> State.DENIED
                else -> State.NO_PERMISSION
            }
        } catch (_: Throwable) { State.NOT_RUNNING }
    }

    fun requestPermission(): Boolean = try {
        if (Shizuku.isPreV11()) false else { Shizuku.requestPermission(REQUEST_CODE); true }
    } catch (_: Throwable) { false }

    /** Shizuku.newProcess is private in API 13; proguard-rules.pro keeps it for this reflective call. */
    private val newProcess: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    class Result(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0 && out.contains("Success")
        val text get() = (out + "\n" + err).trim()
    }

    /** Runs `sh -c [cmd]` as the shell user, feeding [stdin] to it. Blocking; never throws. */
    fun run(cmd: String, stdin: InputStream? = null): Result {
        val m = newProcess ?: return Result(-1, "", "Shizuku API not available")
        return try {
            val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            try {
                var err = ""
                var out = ""
                val errT = thread(name = "shizuku-err") { err = readAll(p.errorStream) }
                val outT = thread(name = "shizuku-out") { out = readAll(p.inputStream) }
                p.outputStream.use { o -> stdin?.copyTo(o, 64 * 1024) }
                val exit = p.waitFor()
                outT.join(5000); errT.join(5000)
                Result(exit, out, err)
            } finally {
                try { p.destroy() } catch (_: Throwable) {}
            }
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            Result(-1, "", cause.javaClass.simpleName + ": " + (cause.message ?: "failed"))
        }
    }

    /**
     * Streams the APK into `pm install -S` (shell can't read Setup's private files). `-i` keeps Setup
     * as the installer of record, so later updates without Shizuku still skip Android's confirm dialog.
     */
    fun install(input: InputStream, size: Long, setupPkg: String): Result =
        run("pm install -r -S $size -i $setupPkg", input)

    /** "Verify apps over USB" (Developer options). Unset means on. Null if Shizuku can't tell. */
    fun verifiesAdbInstalls(): Boolean? {
        val r = run("settings get global verifier_verify_adb_installs")
        if (r.exit != 0) return null
        return r.out.trim() != "0"
    }

    private fun readAll(s: InputStream): String = try { s.bufferedReader().use { it.readText() } } catch (_: Throwable) { "" }
}
