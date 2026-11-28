package app.plain.uninstaller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** Packages that must never be removable, the risky ones that need a second OK, Turkish search, bulk select. */
class SafetyEdgeTest {

    private val homes = setOf("com.transsion.hilauncher", "app.plain.home", "com.android.settings")
    private val withDefault = Defaults(self = "app.plain.setup", launcher = "app.plain.home", homes = homes,
        cameras = setOf("com.transsion.camera"))

    @Test fun systemConfigAndResourcePackagesAreNeverListed() {
        for (p in listOf(
            "android.auto_generated_rro_product__", "android.auto_generated_rro_vendor__",
            "com.android.systemui.auto_generated_rro_vendor__", "com.android.phone.auto_generated_rro_vendor__",
            "com.google.android.connectivity.resources", "com.android.connectivity.resources",
            "com.google.android.safetycenter.resources", "com.android.uwb.resources",
            "com.google.android.wifi.resources", "com.android.providers.media.module",
        )) {
            assertEquals(p, Level.HIDDEN, Safety.level(p, withDefault, system = true))
            assertEquals(p, Level.HIDDEN, Safety.level(p, withDefault, system = false))
        }
        // look-alikes that are ordinary apps stay listed
        for (p in listOf("androidx.test.app", "com.example.resources", "com.android.chrome", "com.facebook.katana")) {
            assertEquals(p, Level.NORMAL, Safety.level(p, withDefault, system = false))
        }
    }

    @Test fun withoutADefaultHomeEveryHomeAppIsKept() {
        // Android resolves HOME to its chooser when no default is set: Apps.defaults passes launcher = null then.
        val noDefault = withDefault.copy(launcher = null)
        assertEquals(Level.HIDDEN, Safety.level("com.transsion.hilauncher", noDefault))
        assertEquals(Level.HIDDEN, Safety.level("app.plain.home", noDefault, system = false))
        // with a default: that one is hidden, the stock launcher is risky (it often runs Recents and gestures)
        assertEquals(Level.HIDDEN, Safety.level("app.plain.home", withDefault, system = false))
        assertEquals(Level.RISKY, Safety.level("com.transsion.hilauncher", withDefault))
        assertTrue(Safety.riskReason("com.transsion.hilauncher", withDefault)!!.contains("Recents"))
    }

    @Test fun riskyNewcomers() {
        assertEquals(Level.RISKY, Safety.level("com.transsion.camera", withDefault))
        assertTrue(Safety.riskReason("com.transsion.camera", withDefault)!!.contains("camera"))
        assertEquals(Level.RISKY, Safety.level("com.android.intentresolver", withDefault))
        assertTrue(Safety.riskReason("com.android.intentresolver", withDefault)!!.contains("Share"))
        assertEquals(Level.RISKY, Safety.level("com.google.android.cellbroadcastreceiver.module", withDefault))
        assertEquals("Emergency alerts", Safety.riskReason("com.android.cellbroadcastreceiver.module", withDefault))
        // overlays: risky when preinstalled, ordinary for a user app that happens to have the word in its name
        assertEquals(Level.RISKY, Safety.level("com.transsion.overlay.navigationbar", withDefault, system = true))
        assertNotNull(Safety.riskReason("com.transsion.overlay.navigationbar", withDefault, system = true))
        assertEquals(Level.NORMAL, Safety.level("com.example.screenoverlay", withDefault, system = false))
        // the Plain apps themselves are ordinary (the Manager hides itself)
        assertEquals(Level.NORMAL, Safety.level("app.plain.maps", withDefault, system = false))
        assertEquals(Level.HIDDEN, Safety.level("app.plain.setup", withDefault, system = false))
    }

    @Test fun everyRiskyAppHasAReason() {
        val pkgs = listOf("com.google.android.gms", "com.android.vending", "com.google.android.gsf", "com.android.webview",
            "com.google.android.documentsui", "com.android.emergency", "com.google.android.gms.location",
            "com.transsion.hilauncher", "com.transsion.camera", "com.android.intentresolver", "x.y.overlay")
        for (p in pkgs) {
            assertEquals(p, Level.RISKY, Safety.level(p, withDefault))
            assertNotNull(p, Safety.riskReason(p, withDefault))
        }
    }

    @Test fun turkishSearch() {
        assertTrue(Search.matches("indir", "İndirilenler"))
        assertTrue(Search.matches("İNDİR", "indirilenler"))
        assertTrue(Search.matches("ınstagram", "Instagram"))
        assertTrue(Search.matches("ISTANBUL", "İstanbul Kart"))
        assertTrue(Search.matches("şehir", "Şehir Hatları"))
        assertTrue(Search.matches("  ", "anything"))
        assertTrue(Search.matches("phonemaster", "Phone Master", "com.transsion.phonemaster"))
        assertFalse(Search.matches("xyz", "İndirilenler", "com.a"))
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertTrue(Search.matches("instagram", "INSTAGRAM"))
            assertEquals("istanbul", Search.fold("İSTANBUL"))
        } finally { Locale.setDefault(saved) }
    }

    @Test fun selectAllLeavesRiskyAppsOut() {
        val items = listOf(
            AppItem("a.one", "One", true, Level.NORMAL),
            AppItem("com.google.android.gms", "Play services", true, Level.RISKY),
            AppItem("a.two", "Two", false, Level.NORMAL),
        )
        assertEquals(listOf("a.one", "a.two"), Plan.bulkPick(items))
        assertTrue(Plan.bulkPick(emptyList()).isEmpty())
    }

    @Test fun manyPackagesClassifyQuickly() {
        val t0 = System.nanoTime()
        var hidden = 0
        for (i in 0 until 20_000) {
            val p = when (i % 4) { 0 -> "com.vendor.app$i"; 1 -> "com.android.providers.x$i"; 2 -> "android.rro$i"; else -> "com.x.overlay$i" }
            if (Safety.level(p, withDefault) == Level.HIDDEN) hidden++
        }
        assertEquals(10_000, hidden)
        assertTrue("too slow", System.nanoTime() - t0 < 5_000_000_000L)
    }
}
