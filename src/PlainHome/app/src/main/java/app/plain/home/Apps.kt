package app.plain.home

import java.text.Collator
import java.util.Locale

/**
 * One launchable activity. [key] is what the hidden-apps set and section overrides store.
 * [stock] = preinstalled (system) app.
 */
data class AppEntry(val pkg: String, val cls: String, val label: String, val stock: Boolean = false) {
    val key: String get() = "$pkg/$cls"
}

data class Section(val title: String, val apps: List<AppEntry>)

object Apps {
    /**
     * Apps to show: not this launcher, not hidden, sorted by label in the phone's language ([locale]: Turkish puts
     * Ç after C, İ after I, Ş after S), case and accent insensitive; equal names in a fixed order (by name, then key)
     * so the grid never reshuffles between reloads.
     */
    fun visible(all: List<AppEntry>, hidden: Set<String>, selfPkg: String, locale: Locale = Locale.getDefault()): List<AppEntry> {
        val collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }
        return all.filter { it.pkg != selfPkg && it.key !in hidden }
            .sortedWith(compareBy<AppEntry, String>(collator) { it.label }.thenBy { it.label }.thenBy { it.key })
    }
}

/**
 * Sections copied from the user's HiOS folders (2026-11-24), in their on-screen order,
 * and apps in each folder's order. Keys are a package, or "package/activity" when one
 * package has several icons (Phone and Contacts are both com.sh.smart.caller).
 * Phone and Messages (the old HiOS dock) lead the Temel section.
 */
object Layout {
    const val OTHER = "Other"

    val ORDER = listOf("Devlet", "Parasal", "Google", "Araç", "Social", "Temel")

    private val DEFAULTS: List<Pair<String, String>> = listOf(
        "tr.gov.saglik.enabiz" to "Devlet",
        "tr.gov.saglik.MHRSMOBIL" to "Devlet",
        "tr.gov.turkiye.edevlet.kapisi" to "Devlet",

        "com.ttech.android.onlineislem" to "Parasal",
        "com.turknet.oim" to "Parasal",
        "com.enparabank.retail" to "Parasal",
        "com.getmidas.app" to "Parasal",
        "com.belbim.istanbulkart" to "Parasal",
        "com.dolap.android" to "Parasal",

        "com.google.android.gm" to "Google",
        "com.google.android.apps.maps" to "Google",
        "com.android.vending" to "Google",

        "com.anthropic.claude" to "Araç",
        "com.tplink.iot" to "Araç",
        "com.transsion.deskclock" to "Araç",
        "com.android.settings" to "Araç",
        "com.transsion.filemanagerx" to "Araç",
        "com.android.stk" to "Araç",
        "com.sh.smart.caller/com.android.dialer.ContactMainActivity" to "Araç",

        "com.discord" to "Social",
        "com.opera.browser" to "Social",
        "com.whatsapp" to "Social",
        "com.cloudflare.onedotonedotonedotone" to "Social",
        "com.google.android.apps.authenticator2" to "Social",
        "com.valvesoftware.android.steam.community" to "Social",

        "com.sh.smart.caller" to "Temel",
        "com.transsion.smartmessage" to "Temel",
        "app.plain.gallery" to "Temel",
        "app.localmusic" to "Temel",
        "app.plain.camera" to "Temel",
        "app.trekmap.trail" to "Temel",
    )
    private val defaultSection = DEFAULTS.toMap()
    private val defaultRank = DEFAULTS.mapIndexed { i, p -> p.first to i }.toMap()

    /** Sections a user can move an app into. */
    val TARGETS: List<String> get() = ORDER + OTHER

    /**
     * Unknown names (e.g. an old "Dock" override) fall back to Other.
     * [extra] = package → section for this phone's own defaults (its dialer and SMS app → Temel),
     * used only when neither the user nor the built-in layout placed the app.
     */
    fun sectionOf(e: AppEntry, overrides: Map<String, String>, extra: Map<String, String> = emptyMap()): String =
        (overrides[e.key] ?: defaultSection[e.key] ?: defaultSection[e.pkg] ?: extra[e.pkg])?.takeIf { it in ORDER } ?: OTHER

    private fun rankOf(e: AppEntry): Int = defaultRank[e.key] ?: defaultRank[e.pkg] ?: Int.MAX_VALUE

    /** Stock apps the user never put in a section are hidden unless [showStock]. */
    fun isAutoHidden(e: AppEntry, overrides: Map<String, String>, showStock: Boolean,
                     extra: Map<String, String> = emptyMap()): Boolean =
        !showStock && e.stock && sectionOf(e, overrides, extra) == OTHER

    /**
     * Groups already-sorted [apps] into the fixed sections (empty ones dropped, "Other" last).
     * Inside a section the user's folder order wins, then alphabetical.
     */
    fun build(apps: List<AppEntry>, overrides: Map<String, String>, showStock: Boolean = false,
              extra: Map<String, String> = emptyMap()): List<Section> {
        val groups = apps.filterNot { isAutoHidden(it, overrides, showStock, extra) }.groupBy { sectionOf(it, overrides, extra) }
        return TARGETS.mapNotNull { name ->
            groups[name].orEmpty().sortedBy { rankOf(it) } // stable: keeps A-Z for ties
                .takeIf { it.isNotEmpty() }?.let { Section(name, it) }
        }
    }
}
