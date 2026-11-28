package app.plain.uninstaller

import app.plain.lang.tr

/** Result of one shell command run through Shizuku. */
data class ShellResult(val exit: Int, val out: String, val err: String) {
    /** One-line reason for a failure, e.g. "Failure [DELETE_FAILED_INTERNAL_ERROR]". */
    fun message(): String {
        val text = (out.trim() + "\n" + err.trim()).trim()
        val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        return line ?: tr("exit code $exit", "çıkış kodu $exit")
    }
}

/** The shell commands this app runs (through Shizuku) or shows (for adb from a PC). Plain Kotlin, no Android. */
object Commands {
    const val LIST_INSTALLED = "pm list packages --user 0"
    const val LIST_ALL = "pm list packages -u --user 0"

    private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$")

    /** Package names go into `sh -c`, so anything that isn't a plain package name is refused. */
    fun isValidPackage(pkg: String): Boolean = pkg.length <= 255 && PKG.matches(pkg)

    private fun checked(pkg: String): String {
        require(isValidPackage(pkg)) { "Not a package name: $pkg" }
        return pkg
    }

    /**
     * User apps are uninstalled for this user (user 0), like Android's own uninstall dialog does: when no other user
     * has the app, that removes it completely; a copy in a work profile or an "app twin" (another user) and its data
     * stay. A plain `pm uninstall` would wipe those too. System apps can only be removed for user 0: the APK stays on
     * the read-only system image (so it can be restored) and -k keeps its data.
     */
    fun uninstall(pkg: String, system: Boolean): String =
        if (system) "pm uninstall -k --user 0 ${checked(pkg)}" else "pm uninstall --user 0 ${checked(pkg)}"

    fun restore(pkg: String): String = "cmd package install-existing ${checked(pkg)}"

    fun adb(cmd: String): String = "adb shell $cmd"

    /** `pm uninstall` prints "Success" or "Failure [...]"; some ROMs exit 0 on failure, so check both. */
    fun uninstallOk(r: ShellResult): Boolean =
        r.exit == 0 && r.out.lineSequence().any { it.trim() == "Success" }

    /** `cmd package install-existing` prints "Package x installed for user: 0" (or "doesn't exist"). */
    fun restoreOk(r: ShellResult): Boolean =
        r.exit == 0 && r.out.contains("installed for user", ignoreCase = true) &&
            !r.out.contains("doesn't exist", ignoreCase = true)
}

/** Parsing of `pm list packages` output. */
object PmOutput {
    /**
     * Lines look like "package:com.foo" or, with -f, "package:/data/app/.../base.apk=com.foo".
     * Blank lines, CRLF and anything that isn't a package line (warnings) are ignored.
     */
    fun parsePackages(output: String): Set<String> {
        val result = LinkedHashSet<String>()
        for (raw in output.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("package:")) continue
            var name = line.removePrefix("package:")
            val eq = name.lastIndexOf('=')
            if (eq >= 0) name = name.substring(eq + 1)
            name = name.substringBefore(' ').trim() // "package:x uid:1000" with -U
            if (Commands.isValidPackage(name)) result.add(name)
        }
        return result
    }
}

/** A package that is on the system image but removed for user 0. */
data class RestoreCandidate(val pkg: String, val removedByThisApp: Boolean)

object RestoreDiff {
    /**
     * `pm list packages -u` minus `pm list packages` gives the packages that exist but aren't installed
     * for the user. Packages from our own removal log are flagged, and also listed when the -u output
     * missed them (e.g. a ROM that doesn't support -u), as long as they aren't installed. Logged entries
     * are only trusted on top of a real listing: with an empty [installed] set the result would be a guess.
     */
    fun candidates(all: Set<String>, installed: Set<String>, logged: Set<String>): List<RestoreCandidate> {
        val missing = all - installed
        val extra = if (installed.isNotEmpty()) logged - installed - missing else emptySet()
        return (missing + extra)
            .map { RestoreCandidate(it, it in logged) }
            .sortedWith(compareBy<RestoreCandidate> { !it.removedByThisApp }.thenBy { it.pkg })
    }
}

/** One line of the removal log. */
data class LogEntry(val time: Long, val action: String, val pkg: String, val label: String) {
    companion object {
        const val REMOVED = "removed"
        const val RESTORED = "restored"
    }
}

/**
 * The removal log (files/removed_system_apps.csv): one row per system package removed or restored, newest
 * last. A tiny CSV codec so it can be opened in a spreadsheet and still be read back without libraries.
 */
object RemovedLog {
    const val FILE = "removed_system_apps.csv"
    const val HEADER = "time,action,package,label"

    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun row(e: LogEntry): String = listOf(e.time.toString(), e.action, e.pkg, e.label).joinToString(",") { escape(it) }

    /** Splits CSV text into records, honouring quotes (including quoted newlines and doubled quotes). */
    fun parseCsv(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var any = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> { quoted = true; any = true }
                ',' -> { row.add(cell.toString()); cell.clear(); any = true }
                '\r' -> {}
                '\n' -> {
                    if (any || cell.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
                    row = ArrayList(); cell.clear(); any = false
                }
                else -> { cell.append(c); any = true }
            }
            i++
        }
        if (any || cell.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
        return rows
    }

    fun parse(text: String): List<LogEntry> = parseCsv(text).mapNotNull { r ->
        if (r.size < 3) return@mapNotNull null
        val time = r[0].toLongOrNull() ?: return@mapNotNull null // also skips the header
        LogEntry(time, r[1], r[2], r.getOrElse(3) { "" })
    }

    /** Packages whose latest log row is "removed". */
    fun currentlyRemoved(entries: List<LogEntry>): Map<String, LogEntry> {
        val last = LinkedHashMap<String, LogEntry>()
        for (e in entries.sortedBy { it.time }) last[e.pkg] = e
        return last.filterValues { it.action == LogEntry.REMOVED }
    }
}
