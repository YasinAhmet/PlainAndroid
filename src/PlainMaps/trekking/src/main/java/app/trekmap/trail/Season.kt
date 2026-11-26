package app.trekmap.trail

import app.plain.lang.tr
import app.plain.lang.PlainLang
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.TextStyle
import kotlin.math.abs

/**
 * When are forest paths visible from space? In deciduous forests: leaf-off season, after the leaves
 * fall and before they return. Mid-November → mid-April in the northern hemisphere, mid-May →
 * mid-October in the southern; the tropics have no real leaf-off season.
 */
object Season {
    private const val TROPICS = 23.5

    /** Day-of-year style key (month*100 + day) of a capture, in UTC. */
    private fun md(ms: Long): Int {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        return d.monthValue * 100 + d.dayOfMonth
    }

    fun leafOff(ms: Long, lat: Double): Boolean {
        if (abs(lat) < TROPICS) return false
        val k = md(ms)
        return if (lat > 0) k >= 1115 || k <= 415 else k in 515..1015
    }

    /** Best of the leaf-off months: no full canopy, less risk of snow and long winter shadows. */
    fun ideal(ms: Long, lat: Double): Boolean {
        if (!leafOff(ms, lat)) return false
        val m = md(ms) / 100
        return if (lat > 0) m in setOf(3, 4, 11, 12) else m in setOf(5, 6, 9, 10)
    }

    fun describe(ms: Long, lat: Double): String = when {
        abs(lat) < TROPICS -> tr("tropics", "tropikal")
        ideal(ms, lat) -> tr("leaf-off ★", "yapraksız ★")
        leafOff(ms, lat) -> tr("leaf-off", "yapraksız")
        else -> tr("leaves on", "yapraklı")
    }

    fun month(ms: Long): String {
        val d = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC)
        return "${d.month.getDisplayName(TextStyle.SHORT, PlainLang.locale)} ${d.year}"
    }

    /** yyyymmdd (Esri SRC_DATE) → epoch ms, or 0. */
    fun fromYmd(ymd: Long): Long {
        if (ymd < 19000101 || ymd > 21001231) return 0
        return try {
            java.time.LocalDate.of((ymd / 10000).toInt(), ((ymd / 100) % 100).toInt(), (ymd % 100).toInt())
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Index of the capture to recommend from [capturesNewestFirst] (epoch ms): the newest leaf-off one,
     * preferring an "ideal" month if one exists no more than ~2 years older; otherwise the newest.
     */
    fun recommend(capturesNewestFirst: List<Long>, lat: Double): Int {
        if (capturesNewestFirst.isEmpty()) return -1
        val leaf = capturesNewestFirst.indices.filter { leafOff(capturesNewestFirst[it], lat) }
        if (leaf.isEmpty()) return 0
        val first = leaf.first()
        val idealIdx = leaf.firstOrNull { ideal(capturesNewestFirst[it], lat) }
        if (idealIdx != null && capturesNewestFirst[first] - capturesNewestFirst[idealIdx] <= 2L * 365 * 86_400_000) return idealIdx
        return first
    }
}
