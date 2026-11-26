package app.plain.uninstaller

import app.plain.lang.tr

/** What the app does with a package in the list. */
enum class Level { HIDDEN, RISKY, NORMAL }

/**
 * The phone-specific packages that the safety rules need. [Safety] itself is plain Kotlin, so the
 * Android side reads these once (Apps.kt) and the rules stay testable without a device.
 */
data class Defaults(
    val self: String = Safety.SELF,
    val launcher: String? = null,
    /** Settings.Secure.DEFAULT_INPUT_METHOD, e.g. "com.foo.keyboard/.Ime"; only the package part counts. */
    val inputMethod: String? = null,
    val dialer: String? = null,
    val sms: String? = null,
    val webView: String? = null,
)

/**
 * Which packages are never shown (removing them can leave the phone unable to boot, call, show a home
 * screen or install anything) and which are shown with an orange "Risky" tag and a second confirmation.
 *
 * Pattern syntax: "a.b.*" matches anything under "a.b." (not "a.b" itself), "a.b*" matches any package
 * starting with "a.b", anything else must match exactly.
 */
object Safety {
    const val SELF = "app.plain.setup" // Plain Manager, which the Uninstaller tab is part of
    const val SHIZUKU = "moe.shizuku.privileged.api"

    val PROTECTED: List<String> = listOf(
        // Core OS
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.shell",
        "com.android.internal.*", // system overlays (display cutout, navigation modes, ...)
        "com.android.inputdevices",
        "com.android.keychain",
        "com.android.location.fused",
        "com.android.externalstorage",
        "com.android.ext.services",
        "com.google.android.ext.services",
        "com.google.android.ext.shared",
        "com.android.modulemetadata",
        "com.google.android.modulemetadata",
        "com.android.localtransport",
        "com.android.certinstaller",
        "com.android.vpndialogs",
        "com.android.se",
        "com.android.dynsystem",
        // Installing / permissions
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        // Content providers (contacts, media, downloads, calendar, settings, telephony, ...)
        "com.android.providers.*",
        "com.google.android.providers.*",
        // Calls, SIM and messaging plumbing
        "com.android.phone",
        "com.android.server.telecom",
        "com.google.android.server.telecom",
        "com.android.mms.service",
        "com.android.stk",
        "com.android.carrierconfig",
        "com.android.ims*",
        "com.mediatek.ims*",
        "com.android.cellbroadcastservice",
        "com.google.android.cellbroadcastservice",
        // Radios and networking
        "com.android.bluetooth",
        "com.google.android.bluetooth",
        "com.android.networkstack*",
        "com.google.android.networkstack*",
        "com.android.wifi*",
        "com.google.android.wifi*",
        "com.android.tethering",
        "com.google.android.tethering",
        "com.android.nfc",
        // Never remove the tool that is doing the removing
        SHIZUKU,
        SELF,
    )

    /** Shown, but with a "Risky" tag and a second confirmation. */
    val RISKY: List<String> = listOf(
        "com.google.android.gms",
        "com.android.vending",
        "com.google.android.gsf",
        "com.google.android.webview",
        "com.android.webview",
        "com.google.android.trichromelibrary*",
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.google.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver",
        "com.android.emergency",
        "com.google.android.gms.*",
    )

    fun matches(pkg: String, pattern: String): Boolean = when {
        pattern.endsWith(".*") -> pkg.startsWith(pattern.dropLast(1))
        pattern.endsWith("*") -> pkg.startsWith(pattern.dropLast(1))
        else -> pkg == pattern
    }

    private fun anyMatch(pkg: String, patterns: List<String>) = patterns.any { matches(pkg, it) }

    /** "com.foo.keyboard/.Ime" -> "com.foo.keyboard". */
    fun packageOfComponent(component: String?): String? =
        component?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }

    fun level(pkg: String, d: Defaults = Defaults()): Level {
        if (anyMatch(pkg, PROTECTED)) return Level.HIDDEN
        if (pkg == d.self || pkg == d.launcher || pkg == packageOfComponent(d.inputMethod)) return Level.HIDDEN
        if (anyMatch(pkg, RISKY)) return Level.RISKY
        if ("webview" in pkg.lowercase()) return Level.RISKY
        if (pkg == d.dialer || pkg == d.sms || pkg == d.webView) return Level.RISKY
        return Level.NORMAL
    }

    /** Why a package is risky, for the second confirmation. Null when it isn't. */
    fun riskReason(pkg: String, d: Defaults = Defaults()): String? {
        if (level(pkg, d) != Level.RISKY) return null
        return when {
            pkg == "com.google.android.gms" || pkg.startsWith("com.google.android.gms.") ->
                tr("Google Play services: many apps, notifications, location and sign-in stop working",
                    "Google Play hizmetleri: birçok uygulama, bildirim, konum ve oturum açma çalışmaz")
            pkg == "com.android.vending" -> tr("Play Store: no more app installs or updates from Google Play",
                "Play Store: Google Play'den uygulama yükleme ve güncelleme olmaz")
            pkg == "com.google.android.gsf" -> tr("Google Services Framework: Google sign-in and push messages break",
                "Google Services Framework: Google ile oturum açma ve anlık bildirimler bozulur")
            pkg == d.dialer -> tr("Your phone app: you may not be able to make calls", "Telefon uygulamanız: arama yapamayabilirsiniz")
            pkg == d.sms -> tr("Your SMS app: you may not be able to read or send texts",
                "SMS uygulamanız: mesaj okuyamayabilir veya gönderemeyebilirsiniz")
            pkg == d.webView || "webview" in pkg.lowercase() || pkg.startsWith("com.google.android.trichromelibrary") ->
                tr("WebView: apps that show web pages (including logins) can crash",
                    "WebView: web sayfası gösteren uygulamalar (girişler dahil) çökebilir")
            pkg.endsWith("documentsui") -> tr("Files picker: apps can no longer open or save files",
                "Dosya seçici: uygulamalar artık dosya açamaz veya kaydedemez")
            pkg.endsWith("cellbroadcastreceiver") -> tr("Emergency alerts", "Acil durum uyarıları")
            else -> tr("Part of the phone's core functions", "Telefonun temel işlevlerinin bir parçası")
        }
    }

    /** The user's own Plain family of apps. */
    fun isPlain(pkg: String): Boolean =
        pkg.startsWith("app.plain.") || pkg == "app.localmusic" || pkg == "app.trekmap.trail"
}
