package app.plain.uninstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyTest {

    private val d = Defaults(
        self = "app.plain.uninstaller",
        launcher = "app.plain.home",
        inputMethod = "com.example.keyboard/.KeyboardService",
        dialer = "com.example.dialer",
        sms = "com.example.sms",
        webView = "com.example.web",
    )

    @Test fun requiredPackagesAreHidden() {
        val must = listOf(
            "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.shell",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.providers.contacts", "com.android.providers.media", "com.android.providers.downloads.ui",
            "com.android.server.telecom", "com.android.bluetooth",
            "com.android.networkstack", "com.android.networkstack.tethering", "com.android.networkstack.permissionconfig",
            "com.android.wifi.resources", "com.android.wifi.dialog",
            "moe.shizuku.privileged.api", "app.plain.uninstaller",
        )
        for (p in must) assertEquals(p, Level.HIDDEN, Safety.level(p, d))
    }

    @Test fun defaultLauncherKeyboardAndSelfAreHidden() {
        assertEquals(Level.HIDDEN, Safety.level("app.plain.home", d))
        assertEquals(Level.HIDDEN, Safety.level("com.example.keyboard", d))
        assertEquals(Level.HIDDEN, Safety.level("app.plain.uninstaller", Defaults(self = "app.plain.uninstaller")))
        // Without being the default, the same packages are ordinary.
        assertEquals(Level.NORMAL, Safety.level("app.plain.home", Defaults()))
        assertEquals(Level.NORMAL, Safety.level("com.example.keyboard", Defaults()))
    }

    @Test fun riskyPackages() {
        for (p in listOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf",
            "com.google.android.webview", "com.android.webview", "com.sec.android.webview.beta")) {
            assertEquals(p, Level.RISKY, Safety.level(p, d))
        }
        assertEquals(Level.RISKY, Safety.level("com.example.dialer", d))
        assertEquals(Level.RISKY, Safety.level("com.example.sms", d))
        assertEquals(Level.RISKY, Safety.level("com.example.web", d))
        assertNotNull(Safety.riskReason("com.google.android.gms", d))
        assertNotNull(Safety.riskReason("com.example.dialer", d))
    }

    @Test fun ordinaryAppsAreNormal() {
        for (p in listOf("com.facebook.katana", "com.transsion.phonemaster", "com.android.chrome", "app.localmusic",
            "com.android.providersx", "com.android.wif")) {
            assertEquals(p, Level.NORMAL, Safety.level(p, d))
        }
        assertNull(Safety.riskReason("com.facebook.katana", d))
    }

    @Test fun patternSyntax() {
        assertTrue(Safety.matches("com.android.providers.media", "com.android.providers.*"))
        assertFalse(Safety.matches("com.android.providers", "com.android.providers.*"))
        assertFalse(Safety.matches("com.android.providersfoo", "com.android.providers.*"))
        assertTrue(Safety.matches("com.android.networkstack", "com.android.networkstack*"))
        assertTrue(Safety.matches("com.android.networkstack.x", "com.android.networkstack*"))
        assertTrue(Safety.matches("android", "android"))
        assertFalse(Safety.matches("android.ext", "android"))
    }

    @Test fun componentPackage() {
        assertEquals("com.foo", Safety.packageOfComponent("com.foo/.Ime"))
        assertEquals("com.foo", Safety.packageOfComponent("com.foo/com.foo.Ime"))
        assertNull(Safety.packageOfComponent(null))
        assertNull(Safety.packageOfComponent(""))
    }

    @Test fun plainApps() {
        assertTrue(Safety.isPlain("app.plain.home"))
        assertTrue(Safety.isPlain("app.localmusic"))
        assertTrue(Safety.isPlain("app.trekmap.trail"))
        assertFalse(Safety.isPlain("app.plainish"))
        assertFalse(Safety.isPlain("app.localmusic.extra"))
    }
}
