package app.plain.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppsTest {
    private val list = listOf(
        AppEntry("com.whatsapp", "Main", "WhatsApp"),
        AppEntry("app.plain.home", "HomeActivity", "Plain Home"),
        AppEntry("com.discord", "Main", "discord"),
        AppEntry("com.transsion.camera", "Cap", "Camera"),
        AppEntry("com.anthropic.claude", "Main", "Claude"),
    )

    @Test fun sortsCaseInsensitivelyAndSkipsSelf() {
        val names = Apps.visible(list, emptySet(), "app.plain.home").map { it.label }
        assertEquals(listOf("Camera", "Claude", "discord", "WhatsApp"), names)
    }

    @Test fun turkishAlphabetOrder() {
        val tr = Locale.forLanguageTag("tr-TR")
        val apps = listOf("Dolap", "Çanta", "Cüzdan", "Şeker", "Saat", "Tamam", "İnstagram", "Ilgaz", "Ödeme", "Otobüs")
            .map { AppEntry("p.$it", "A", it) }
        assertEquals(listOf("Cüzdan", "Çanta", "Dolap", "Ilgaz", "İnstagram", "Otobüs", "Ödeme", "Saat", "Şeker", "Tamam"),
            Apps.visible(apps, emptySet(), "x", tr).map { it.label })
        // the same names in English order: the accents don't count
        assertEquals(listOf("Çanta", "Cüzdan", "Dolap"),
            Apps.visible(apps.filter { it.label in setOf("Dolap", "Çanta", "Cüzdan") }, emptySet(), "x", Locale.ENGLISH).map { it.label })
    }

    @Test fun sameNamesKeepAFixedOrder() {
        val a = AppEntry("com.b", "Main", "Camera")
        val b = AppEntry("com.a", "Main", "Camera")
        val c = AppEntry("com.c", "Main", "camera")
        val d = AppEntry("com.a", "Second", "Camera")
        val once = Apps.visible(listOf(a, b, c, d), emptySet(), "x", Locale.ENGLISH)
        for (seed in 1..20) {
            val shuffled = listOf(a, b, c, d).shuffled(java.util.Random(seed.toLong()))
            assertEquals(once, Apps.visible(shuffled, emptySet(), "x", Locale.ENGLISH))
        }
        assertEquals(listOf(b, d, a, c), once)   // "Camera" before "camera", then by package/activity
    }

    @Test fun severalLaunchersInOnePackageAndManyApps() {
        val many = (0 until 5000).map { AppEntry("pkg.$it", "A", "App ${4999 - it}") } +
            AppEntry("pkg.0", "B", "App second icon") + AppEntry("", "", "")
        val shown = Apps.visible(many, setOf("pkg.0/A"), "self", Locale.ENGLISH)
        assertEquals(5001, shown.size)
        assertEquals("", shown.first().label)                       // an empty label never crashes the sort
        assertEquals(1, shown.count { it.pkg == "pkg.0" })
        val sections = Layout.build(shown, emptyMap())
        assertEquals(listOf(Layout.OTHER), sections.map { it.title })
        assertEquals(5001, sections[0].apps.size)
    }

    @Test fun skipsHidden() {
        val names = Apps.visible(list, setOf("com.transsion.camera/Cap"), "app.plain.home").map { it.label }
        assertEquals(listOf("Claude", "discord", "WhatsApp"), names)
    }

    private val phone = AppEntry("com.sh.smart.caller", "com.android.dialer.main.impl.MainActivity", "Phone", stock = true)
    private val contacts = AppEntry("com.sh.smart.caller", "com.android.dialer.ContactMainActivity", "Contacts", stock = true)
    private val sms = AppEntry("com.transsion.smartmessage", "M", "Messages", stock = true)
    private val calc = AppEntry("com.transsion.calculator", "C", "Calculator", stock = true)
    private val stockCam = AppEntry("com.transsion.camera", "Cap", "Camera", stock = true)
    private val newApp = AppEntry("org.example.new", "N", "Newly Installed")
    private val trekmap = AppEntry("app.trekmap.trail", "T", "Trekmap Trail")
    private val gallery = AppEntry("app.plain.gallery", "G", "Plain Gallery")
    private val edevlet = AppEntry("tr.gov.turkiye.edevlet.kapisi", "E", "e-Devlet")
    private val mhrs = AppEntry("tr.gov.saglik.MHRSMOBIL", "S", "CDAS")

    private val sorted = Apps.visible(
        listOf(phone, contacts, sms, calc, stockCam, newApp, trekmap, gallery, edevlet, mhrs), emptySet(), "x")

    @Test fun groupsIntoFoldersInFolderOrderAndHidesStockLeftovers() {
        val sections = Layout.build(sorted, emptyMap())
        assertEquals(listOf("Devlet", "Araç", "Temel", "Other"), sections.map { it.title })
        assertEquals(listOf("CDAS", "e-Devlet"), sections[0].apps.map { it.label }) // folder order, MHRS first
        assertEquals(listOf("Contacts"), sections[1].apps.map { it.label })        // activity-level key
        assertEquals(listOf("Phone", "Messages", "Plain Gallery", "Trekmap Trail"), sections[2].apps.map { it.label })
        assertEquals(listOf("Newly Installed"), sections[3].apps.map { it.label }) // stock Camera/Calculator hidden
    }

    @Test fun showStockRevealsThemInOther() {
        val other = Layout.build(sorted, emptyMap(), showStock = true).last()
        assertEquals(listOf("Calculator", "Camera", "Newly Installed"), other.apps.map { it.label })
    }

    @Test fun overrideMovesAnAppAndStaleDockOverrideFallsBack() {
        val sections = Layout.build(sorted, mapOf(calc.key to "Temel", newApp.key to "Dock"))
        assertEquals(listOf("Devlet", "Araç", "Temel", "Other"), sections.map { it.title })
        assertEquals("Calculator", sections[2].apps.last().label)                 // a moved stock app shows
        assertEquals(listOf("Newly Installed"), sections[3].apps.map { it.label })
    }

    @Test fun friendsPhoneDialerAndSmsGoToTemelButUserPlacementWins() {
        val dialer = AppEntry("com.samsung.android.dialer", "D", "Phone (Samsung)", stock = true)
        val msgs = AppEntry("com.samsung.android.messaging", "M", "Messages (Samsung)", stock = true)
        val apps = Apps.visible(listOf(dialer, msgs, calc), emptySet(), "x")
        val extra = mapOf(dialer.pkg to "Temel", msgs.pkg to "Temel")
        val sections = Layout.build(apps, mapOf(msgs.key to "Social"), extra = extra)
        assertEquals(listOf("Social", "Temel"), sections.map { it.title }) // stock calculator still hidden
        assertEquals(listOf("Phone (Samsung)"), sections[1].apps.map { it.label })
        assertEquals("Araç", Layout.sectionOf(contacts, emptyMap(), mapOf(contacts.pkg to "Temel"))) // built-in layout wins
    }
}
