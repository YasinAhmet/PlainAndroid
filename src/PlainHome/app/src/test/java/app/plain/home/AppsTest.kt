package app.plain.home

import org.junit.Assert.assertEquals
import org.junit.Test

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
