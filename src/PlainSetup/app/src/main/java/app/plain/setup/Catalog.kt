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
)

/** Where an install would come from: the APK inside Setup, or a download from the releases. */
enum class Source { BUNDLED, ONLINE }

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
            out += Entry(
                key = o.optString("key", pkg),
                name = o.optString("name", pkg),
                pkg = pkg,
                versionCode = code,
                versionName = o.optString("versionName", code.toString()),
                file = o.optString("file"),
                url = o.optString("url"),
                size = o.optLong("size", 0),
                sha256 = o.optString("sha256").lowercase(),
                minSdk = o.optInt("minSdk", 1),
                summary = o.optString("summary"),
            )
        }
        return out
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
