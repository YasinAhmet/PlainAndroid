package app.plain.setup

import org.json.JSONObject

/**
 * One app as listed in apps.json (the PlainAndroid repo publishes it; a copy is bundled in assets).
 * [file] is the APK's name, both in assets/apks and as the release asset behind [url].
 */
data class Entry(
    val key: String,
    val name: String,
    val pkg: String,
    val versionCode: Long,
    val versionName: String,
    val file: String,
    val url: String,
    val size: Long,
    val sha256: String,
    val minSdk: Int,
    val summary: String,
    /** Earlier releases (newest first), online only — what "Older versions" offers. */
    val history: List<Entry> = emptyList(),
)

/** Where an install would come from: the APK inside Setup, a download from the releases, or a backup on the phone. */
enum class Source { BUNDLED, ONLINE, LOCAL }

data class Offer(val entry: Entry, val source: Source)

object Catalog {
    const val ONLINE_URL = "https://raw.githubusercontent.com/YasinAhmet/PlainAndroid/main/apps.json"
    const val SELF_PKG = "app.plain.setup"

    fun parse(json: String): List<Entry> {
        val arr = JSONObject(json.trimStart('﻿')).optJSONArray("apps") ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val pkg = o.optString("package")
            val code = o.optLong("versionCode", -1)
            if (pkg.isEmpty() || code < 0) continue
            val e = entry(o, null) ?: continue
            val hist = ArrayList<Entry>()
            o.optJSONArray("history")?.let { h ->
                for (j in 0 until h.length()) h.optJSONObject(j)?.let { entry(it, e) }?.let { if (it.url.isNotEmpty()) hist += it }
            }
            out += e.copy(history = hist.sortedByDescending { it.versionCode })
        }
        return out
    }

    /** One release; a history item inherits name, package, file and summary from [parent]. */
    private fun entry(o: JSONObject, parent: Entry?): Entry? {
        val pkg = parent?.pkg ?: o.optString("package")
        val code = o.optLong("versionCode", -1)
        if (pkg.isEmpty() || code < 0) return null
        return Entry(
            key = parent?.key ?: o.optString("key", pkg),
            name = parent?.name ?: o.optString("name", pkg),
            pkg = pkg,
            versionCode = code,
            versionName = o.optString("versionName", code.toString()),
            file = parent?.file ?: o.optString("file"),
            url = o.optString("url"),
            size = o.optLong("size", 0),
            sha256 = o.optString("sha256").lowercase(),
            minSdk = o.optInt("minSdk", parent?.minSdk ?: 1),
            summary = parent?.summary ?: o.optString("summary"),
        )
    }

    /**
     * Best source per app: the online copy only when it is strictly newer than the bundled one
     * (bundled installs need no download). Order follows the bundled list, then new online apps.
     * Setup itself is offered only online (it can't contain itself).
     */
    fun merge(bundled: List<Entry>, online: List<Entry>?): List<Offer> {
        val byPkg = LinkedHashMap<String, Offer>()
        for (e in bundled) if (e.pkg != SELF_PKG) byPkg[e.pkg] = Offer(e, Source.BUNDLED)
        for (e in online.orEmpty()) {
            val cur = byPkg[e.pkg]
            if (cur == null || e.versionCode > cur.entry.versionCode) byPkg[e.pkg] = Offer(e, Source.ONLINE)
            else byPkg[e.pkg] = cur.copy(entry = cur.entry.copy(history = e.history)) // keep the online release list
        }
        return byPkg.values.toList()
    }

    /** What a row says and whether it can be ticked. */
    enum class State { NOT_INSTALLED, UP_TO_DATE, UPDATE, TOO_OLD_ANDROID, NEWER_INSTALLED }

    fun state(offer: Offer, installedCode: Long?, sdk: Int): State = when {
        sdk < offer.entry.minSdk -> State.TOO_OLD_ANDROID
        installedCode == null -> State.NOT_INSTALLED
        installedCode < offer.entry.versionCode -> State.UPDATE
        installedCode == offer.entry.versionCode -> State.UP_TO_DATE
        else -> State.NEWER_INSTALLED
    }

    /** Every offer that updates an installed app — what "Update all" installs. [held] apps were rolled back on purpose. */
    fun updates(offers: List<Offer>, installedCode: (String) -> Long?, sdk: Int, held: Set<String> = emptySet()): List<Offer> =
        offers.filter { it.entry.pkg !in held && state(it, installedCode(it.entry.pkg), sdk) == State.UPDATE }

    /** Releases older than what's installed that this phone can run, newest first. */
    fun olderVersions(offer: Offer, installedCode: Long?, sdk: Int): List<Entry> {
        val cur = installedCode ?: return emptyList()
        return offer.entry.history.filter { it.versionCode < cur && sdk >= it.minSdk }.sortedByDescending { it.versionCode }
    }

    /** "Android 11" for API 30 etc. (minSdk is what the catalog stores). */
    fun androidName(api: Int): String = when (api) {
        in 0..25 -> "Android 7"
        26, 27 -> "Android 8"
        28 -> "Android 9"
        29 -> "Android 10"
        30 -> "Android 11"
        31, 32 -> "Android 12"
        33 -> "Android 13"
        34 -> "Android 14"
        35 -> "Android 15"
        else -> "Android ${api - 20}"
    }

    fun sizeText(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 * 1024 -> "${(bytes + 512) / 1024} KB"
        else -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
    }
}
