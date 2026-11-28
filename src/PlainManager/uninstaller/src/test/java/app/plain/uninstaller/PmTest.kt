package app.plain.uninstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PmTest {

    @Test fun parsesPmListPackages() {
        val out = "package:com.android.chrome\r\npackage:com.google.android.gms\n\npackage:android\n" +
            "WARNING: linker: something\npackage:/data/app/~~x==/com.foo-1/base.apk=com.foo\n" +
            "package:com.bar uid:10123\n  package:com.baz  \npackage:\npackage:bad;name rm -rf\n"
        assertEquals(
            setOf("com.android.chrome", "com.google.android.gms", "android", "com.foo", "com.bar", "com.baz"),
            PmOutput.parsePackages(out),
        )
        assertTrue(PmOutput.parsePackages("").isEmpty())
    }

    @Test fun restoreDiff() {
        val all = setOf("a.one", "a.two", "a.three", "a.four")
        val installed = setOf("a.one", "a.three")
        val r = RestoreDiff.candidates(all, installed, logged = setOf("a.four", "a.five", "a.one"))
        // logged first, then by name; a.five (logged, missing from -u, not installed) is included;
        // a.one is installed again, so it isn't a candidate.
        assertEquals(
            listOf(RestoreCandidate("a.five", true), RestoreCandidate("a.four", true), RestoreCandidate("a.two", false)),
            r,
        )
    }

    @Test fun restoreDiffIgnoresLogWithoutAListing() {
        assertEquals(emptyList<RestoreCandidate>(), RestoreDiff.candidates(emptySet(), emptySet(), setOf("a.x")))
    }

    @Test fun commandsForUserAndSystemApps() {
        // user apps: for user 0 only, like Android's own dialog (a work-profile / app-twin copy stays)
        assertEquals("pm uninstall --user 0 com.facebook.katana", Commands.uninstall("com.facebook.katana", system = false))
        assertEquals("pm uninstall -k --user 0 com.transsion.phonemaster", Commands.uninstall("com.transsion.phonemaster", system = true))
        assertEquals("cmd package install-existing com.transsion.phonemaster", Commands.restore("com.transsion.phonemaster"))
        assertEquals("adb shell cmd package install-existing com.x", Commands.adb(Commands.restore("com.x")))
    }

    @Test fun planUsesTheRightCommandPerApp() {
        val user = Target("com.user", "User", system = false, risky = false)
        val sys = Target("com.sys", "Sys", system = true, risky = true)
        val cmds = Plan.shizukuCommands(listOf(user, sys))
        assertEquals(listOf("pm uninstall --user 0 com.user", "pm uninstall -k --user 0 com.sys"), cmds.map { it.second })
        assertEquals(listOf(sys), Plan.needShizuku(listOf(user, sys)))
        assertEquals(listOf(user), Plan.dialogQueue(listOf(user, sys)))
        assertEquals(listOf(sys), Plan.risky(listOf(user, sys)))
        assertTrue(Plan.describe(listOf(sys)).contains("system, RISKY"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesShellInjection() {
        Commands.uninstall("com.foo; reboot", system = true)
    }

    @Test fun packageNameValidation() {
        assertTrue(Commands.isValidPackage("android"))
        assertTrue(Commands.isValidPackage("com.google.android.gms"))
        assertTrue(Commands.isValidPackage("com.a_b.c1"))
        assertFalse(Commands.isValidPackage(""))
        assertFalse(Commands.isValidPackage("com..foo"))
        assertFalse(Commands.isValidPackage("com.foo bar"))
        assertFalse(Commands.isValidPackage("\$(reboot)"))
        assertFalse(Commands.isValidPackage("1com.foo"))
    }

    @Test fun commandResults() {
        assertTrue(Commands.uninstallOk(ShellResult(0, "Success\n", "")))
        assertFalse(Commands.uninstallOk(ShellResult(0, "Failure [DELETE_FAILED_INTERNAL_ERROR]\n", "")))
        assertFalse(Commands.uninstallOk(ShellResult(1, "Success\n", "")))
        assertEquals("Failure [not installed for 0]", ShellResult(1, "Failure [not installed for 0]\n", "").message())
        assertEquals("exit code 255", ShellResult(255, "", "").message())
        assertTrue(Commands.restoreOk(ShellResult(0, "Package com.x installed for user: 0\n", "")))
        assertFalse(Commands.restoreOk(ShellResult(0, "Package com.x doesn't exist\n", "")))
        assertFalse(Commands.restoreOk(ShellResult(1, "", "Error")))
    }

    @Test fun removedLogRoundTrip() {
        val entries = listOf(
            LogEntry(1000, LogEntry.REMOVED, "com.a", "Plain label"),
            LogEntry(2000, LogEntry.REMOVED, "com.b", "Label, with \"quotes\"\nand a newline"),
            LogEntry(3000, LogEntry.RESTORED, "com.a", "Plain label"),
        )
        val text = RemovedLog.HEADER + "\r\n" + entries.joinToString("\n") { RemovedLog.row(it) } + "\n"
        val parsed = RemovedLog.parse(text)
        assertEquals(entries, parsed)
        val now = RemovedLog.currentlyRemoved(parsed)
        assertEquals(setOf("com.b"), now.keys)
    }

    @Test fun removedLogSkipsJunk() {
        assertEquals(emptyList<LogEntry>(), RemovedLog.parse("time,action,package,label\nnot,a,row\n\n"))
    }
}
