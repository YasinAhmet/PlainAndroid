package app.plain.camera

/**
 * A named snapshot of the camera settings: the Advanced settings ([Adv], flash strength included),
 * photo or video, flash for each mode, and the aspect and size picked for each mode. Pure Kotlin,
 * so the storage format is unit-tested.
 */
data class Profile(
    val name: String,
    val adv: Adv = Adv(),
    val video: Boolean = false,
    /** 0 off · 1 auto · 2 on. */
    val flashPhoto: Int = 0,
    /** 0 off · 1 torch. */
    val flashVideo: Int = 0,
    /** [Aspect] names; "" = the default. */
    val aspectPhoto: String = "",
    val aspectVideo: String = "",
    /** "WxH"; "" = the default for the aspect. */
    val sizePhoto: String = "",
    val sizeVideo: String = "",
)

/** The list of profiles, stored as one string: a line per profile, `|`-separated `key=value` fields. */
object Profiles {
    const val MAX_NAME = 24

    /** Trimmed, single-line, at most [MAX_NAME] characters; null when nothing is left. */
    fun cleanName(n: String): String? =
        n.replace(Regex("\\s+"), " ").trim().take(MAX_NAME).trim().takeIf { it.isNotEmpty() }

    fun find(list: List<Profile>, name: String): Profile? = list.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** Replaces the profile with the same name (ignoring case) in place, or adds it at the end. */
    fun upsert(list: List<Profile>, p: Profile): List<Profile> {
        val i = list.indexOfFirst { it.name.equals(p.name, ignoreCase = true) }
        return if (i < 0) list + p else list.toMutableList().apply { set(i, p) }
    }

    /** Null when [to] is taken by another profile or [from] doesn't exist. */
    fun rename(list: List<Profile>, from: String, to: String): List<Profile>? {
        val i = list.indexOfFirst { it.name.equals(from, ignoreCase = true) }
        if (i < 0) return null
        if (list.anyIndexed { k, p -> k != i && p.name.equals(to, ignoreCase = true) }) return null
        return list.toMutableList().apply { set(i, list[i].copy(name = to)) }
    }

    fun remove(list: List<Profile>, name: String): List<Profile> = list.filterNot { it.name.equals(name, ignoreCase = true) }

    private inline fun <T> List<T>.anyIndexed(f: (Int, T) -> Boolean): Boolean {
        for (k in indices) if (f(k, this[k])) return true
        return false
    }

    // ------------------------------------------------------------ storage
    private fun esc(v: String) = v.replace("%", "%25").replace("|", "%7C").replace("\n", "%0A").replace("\r", "%0D")
    private fun unesc(v: String): String {
        if ('%' !in v) return v
        val sb = StringBuilder(v.length)
        var k = 0
        while (k < v.length) {
            val c = when (if (v[k] == '%' && k + 3 <= v.length) v.substring(k, k + 3) else "") {
                "%25" -> '%'; "%7C" -> '|'; "%0A" -> '\n'; "%0D" -> '\r'; else -> null
            }
            if (c != null) { sb.append(c); k += 3 } else { sb.append(v[k]); k++ }
        }
        return sb.toString()
    }

    fun serialize(list: List<Profile>): String = list.joinToString("\n") { p ->
        listOf(
            "n" to p.name, "adv" to p.adv.serialize(), "v" to p.video.toString(),
            "fp" to p.flashPhoto.toString(), "fv" to p.flashVideo.toString(),
            "ap" to p.aspectPhoto, "av" to p.aspectVideo, "sp" to p.sizePhoto, "sv" to p.sizeVideo,
        ).joinToString("|") { (k, v) -> "$k=${esc(v)}" }
    }

    /** Tolerant: skips lines without a name, falls back to defaults for missing or broken fields. */
    fun parse(s: String?): List<Profile> {
        if (s.isNullOrBlank()) return emptyList()
        val out = ArrayList<Profile>()
        for (line in s.split('\n')) {
            val m = line.split('|').mapNotNull { f -> f.indexOf('=').takeIf { it > 0 }?.let { f.substring(0, it) to unesc(f.substring(it + 1)) } }.toMap()
            val name = cleanName(m["n"] ?: "") ?: continue
            if (find(out, name) != null) continue
            out += Profile(
                name = name,
                adv = Adv.parse(m["adv"]),
                video = m["v"]?.toBooleanStrictOrNull() ?: false,
                flashPhoto = (m["fp"]?.toIntOrNull() ?: 0).coerceIn(0, 2),
                flashVideo = (m["fv"]?.toIntOrNull() ?: 0).coerceIn(0, 1),
                aspectPhoto = m["ap"] ?: "",
                aspectVideo = m["av"] ?: "",
                sizePhoto = m["sp"] ?: "",
                sizeVideo = m["sv"] ?: "",
            )
        }
        return out
    }
}
