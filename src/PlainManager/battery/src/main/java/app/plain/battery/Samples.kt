package app.plain.battery

import app.plain.lang.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One battery reading. Values are logged raw; [currentNowRaw] units differ between phones. */
data class Sample(
    val time: Long,
    val level: Int,
    val status: String,      // charging / discharging / full / not-charging / unknown
    val plugged: String,     // ac / usb / wireless / dock / none
    val currentNowRaw: Long,
    val voltageMv: Int,
    val tempDeciC: Int,
    val chargeCounterUah: Long,
    val screenOn: Boolean,
    val source: String,      // job / ui / boot
) {
    val charging: Boolean get() = plugged != "none"
}

object Csv {
    const val BATTERY_HEADER =
        "time,epoch_ms,level,status,plugged,current_now_raw,voltage_mv,temp_c,charge_counter_uah,screen,source"
    const val SCREEN_HEADER = "time,epoch_ms,event"
    const val APPS_HEADER =
        "time,epoch_ms,interval_min,uid,name,mah_in_interval,mah_since_reset,screen_mah_since_reset,stats_since"

    private val fmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US) }

    fun time(ms: Long): String = fmt.get()!!.format(Date(ms))

    fun battery(s: Sample): String = listOf(
        time(s.time), s.time, s.level, s.status, s.plugged, s.currentNowRaw, s.voltageMv,
        "%.1f".format(Locale.US, s.tempDeciC / 10.0), s.chargeCounterUah,
        if (s.screenOn) "on" else "off", s.source,
    ).joinToString(",")

    fun parseBattery(line: String): Sample? {
        val f = line.split(',')
        if (f.size < 11) return null
        return try {
            Sample(
                time = f[1].toLong(), level = f[2].toInt(), status = f[3], plugged = f[4],
                currentNowRaw = f[5].toLong(), voltageMv = f[6].toInt(),
                tempDeciC = Math.round(f[7].toDouble() * 10).toInt(),
                chargeCounterUah = f[8].toLong(), screenOn = f[9] == "on", source = f[10],
            )
        } catch (e: RuntimeException) { null }
    }

    fun screen(time: Long, on: Boolean): String = "${time(time)},$time,${if (on) "on" else "off"}"

    fun parseScreen(line: String): Pair<Long, Boolean>? {
        val f = line.split(',')
        if (f.size < 3) return null
        val t = f[1].toLongOrNull() ?: return null
        return when (f[2]) { "on" -> t to true; "off" -> t to false; else -> null }
    }

    /** Quote a free-text field (app names can contain commas). */
    fun text(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"").replace('\n', ' ') + "\"" else s

    /** Rows for apps.csv: what each uid burned between two snapshots. */
    fun appRows(
        prev: Snapshot?, cur: Snapshot, label: (Int) -> String, minMah: Double = 0.05,
    ): List<String> {
        val d = Checkin.delta(prev, cur)
        val minutes = if (prev == null || Checkin.isReset(prev, cur)) "" else
            ((cur.takenAt - prev.takenAt) / 60000.0).let { "%.1f".format(Locale.US, it) }
        val t = time(cur.takenAt)
        return d.apps.values.filter { it.mah >= minMah }.sortedByDescending { it.mah }.map { p ->
            val total = cur.apps[p.uid]
            listOf(
                t, cur.takenAt, minutes, p.uid, text(label(p.uid)),
                "%.2f".format(Locale.US, p.mah),
                "%.2f".format(Locale.US, total?.mah ?: 0.0),
                "%.2f".format(Locale.US, total?.screenMah ?: 0.0),
                time(cur.statsStart),
            ).joinToString(",")
        }
    }
}

object Drain {
    /**
     * Average discharge current (mA) between two samples from the charge counter (µAh), which
     * is far finer than the 1% level. Null when either side is charging or the counter is absent.
     */
    fun averageMa(a: Sample, b: Sample): Double? {
        if (a.charging || b.charging) return null
        if (a.chargeCounterUah <= 0 || b.chargeCounterUah <= 0) return null
        val hours = (b.time - a.time) / 3_600_000.0
        if (hours < 1.0 / 60) return null
        val usedMah = (a.chargeCounterUah - b.chargeCounterUah) / 1000.0
        return usedMah / hours
    }

    /** Index of the first sample of the current discharge run (after the last time on power). */
    fun lastUnplugIndex(samples: List<Sample>): Int {
        var i = samples.size - 1
        if (i < 0) return -1
        while (i > 0 && !samples[i - 1].charging) i--
        return i
    }

    fun formatDuration(ms: Long): String {
        val m = (ms / 60000).coerceAtLeast(0) // a clock set back must not show "-5m"
        return when {
            m < 60 -> tr("${m}m", "$m dk")
            m < 48 * 60 -> tr("${m / 60}h ${m % 60}m", "${m / 60} sa ${m % 60} dk")
            else -> tr("${m / 1440}d ${(m % 1440) / 60}h", "${m / 1440} g ${(m % 1440) / 60} sa")
        }
    }
}
