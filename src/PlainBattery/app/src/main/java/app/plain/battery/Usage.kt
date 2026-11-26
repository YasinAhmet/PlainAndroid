package app.plain.battery

import java.util.Locale

/** Battery use expressed as a share of the whole battery. Pure, unit-tested. */
object Usage {
    enum class Source { BATTERYSTATS, CHARGE_COUNTER }

    data class Capacity(val mah: Double, val source: Source)

    private val PLAUSIBLE = 500.0..30_000.0

    /**
     * Battery capacity in mAh. First choice: the capacity batterystats reports (`pws` line, from
     * the phone's power profile, i.e. the design capacity). Fallback: charge counter / level from
     * a battery sample, the charge a full battery holds today. Null when neither is usable.
     */
    fun capacity(statsMah: Double?, sample: Sample?): Capacity? {
        if (statsMah != null && statsMah in PLAUSIBLE) return Capacity(statsMah, Source.BATTERYSTATS)
        val s = sample ?: return null
        // Below ~20 % the 1 % level steps make the estimate too coarse.
        if (s.level !in 20..100 || s.chargeCounterUah <= 0) return null
        val mah = s.chargeCounterUah / 1000.0 * 100 / s.level
        return if (mah in PLAUSIBLE) Capacity(mah, Source.CHARGE_COUNTER) else null
    }

    /** [mah] as a percentage of the whole battery. */
    fun pct(mah: Double, capacityMah: Double): Double = if (capacityMah > 0) 100 * mah / capacityMah else 0.0

    /** "4.2 %", "12 %", "<0.1 %". */
    fun formatPct(p: Double): String = when {
        p < 0.05 -> "<0.1 %"
        p < 9.95 -> "%.1f %%".format(Locale.US, p)
        else -> "%.0f %%".format(Locale.US, p)
    }

    fun formatMah(v: Double): String =
        if (v >= 10) "%.0f mAh".format(Locale.US, v) else "%.1f mAh".format(Locale.US, v)

    /** A value as % of battery when the capacity is known, else in mAh. */
    fun format(mah: Double, cap: Capacity?): String = if (cap == null) formatMah(mah) else formatPct(pct(mah, cap.mah))

    /**
     * Apps of a period (a [Checkin.delta] snapshot), biggest first, without the ones too small to
     * show: under 0.05 % of the battery (rounds to 0.0 %), or under 0.05 mAh without a capacity.
     */
    fun visibleApps(period: Snapshot, cap: Capacity?): List<UidPower> {
        val min = if (cap == null) 0.05 else cap.mah * 0.0005
        return Checkin.ranking(period, minMah = min)
    }
}

/** Samples grouped by calendar day, for the History tab. Pure, unit-tested. */
object History {
    /**
     * @param date yyyy-MM-dd (local time)
     * @param usedPct level points lost during that day, @param chargedPct points gained
     * @param samples that day's samples, oldest first
     */
    data class Day(val date: String, val usedPct: Int, val chargedPct: Int, val samples: List<Sample>)

    /** Newest day first. A change between two samples counts for the day of the later one. */
    fun days(samples: List<Sample>): List<Day> {
        val groups = LinkedHashMap<String, MutableList<Sample>>()
        val used = HashMap<String, Int>()
        val charged = HashMap<String, Int>()
        var prev: Sample? = null
        for (s in samples) {
            if (s.level < 0) continue
            val key = Csv.time(s.time).substring(0, 10)
            groups.getOrPut(key) { ArrayList() } += s
            prev?.let { p ->
                val d = s.level - p.level
                if (d < 0) used[key] = (used[key] ?: 0) - d
                else if (d > 0) charged[key] = (charged[key] ?: 0) + d
            }
            prev = s
        }
        return groups.entries.reversed().map { (k, l) -> Day(k, used[k] ?: 0, charged[k] ?: 0, l) }
    }
}
