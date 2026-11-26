package app.plain.battery

import app.plain.lang.tr

/** Recent window, time-left estimates and standby current. Pure, unit-tested. */
object Rates {
    /** Two snapshots of the same stats period, [batteryHours] of time on battery apart. */
    data class Recent(val from: Snapshot, val to: Snapshot, val batteryHours: Double) {
        /** What each uid (and the whole phone, measured) used in between. */
        val delta: Snapshot get() = Checkin.delta(from, to)
    }

    /**
     * The window of roughly the last [windowMs] on battery: the newest snapshot of the same stats
     * period that is at least that old (else the oldest usable one). Battery time, not wall time,
     * so charging does not count.
     */
    fun recent(
        history: List<Snapshot>, latest: Snapshot,
        windowMs: Long = 3 * 3_600_000L, minBatteryMs: Long = 10 * 60_000L,
    ): Recent? {
        val usable = history.filter {
            it.statsStart == latest.statsStart && it.takenAt < latest.takenAt &&
                !Checkin.isReset(it, latest) && latest.onBatteryMs - it.onBatteryMs >= minBatteryMs
        }
        if (usable.isEmpty()) return null
        val target = latest.takenAt - windowMs
        val from = usable.filter { it.takenAt <= target }.maxByOrNull { it.takenAt }
            ?: usable.minByOrNull { it.takenAt }!!
        return Recent(from, latest, (latest.onBatteryMs - from.onBatteryMs) / 3_600_000.0)
    }

    /** Hours until empty at [ma], or null when the rate is unknown or tiny. */
    fun hoursLeft(remainingMah: Double, ma: Double?): Double? =
        if (ma == null || ma < 1.0 || remainingMah <= 0) null else remainingMah / ma

    /**
     * Average current with the screen off and off power, from consecutive samples that have no
     * screen-on event between them: the phone's standby cost, for the "idle" time-left estimate.
     */
    fun standbyMa(samples: List<Sample>, screen: List<Pair<Long, Boolean>>, maxGapMs: Long = 2 * 3_600_000L): Double? {
        val onTimes = screen.filter { it.second }.map { it.first }.sorted()
        var mah = 0.0
        var hours = 0.0
        var j = 0
        for (i in 1 until samples.size) {
            val a = samples[i - 1]; val b = samples[i]
            while (j < onTimes.size && onTimes[j] <= a.time) j++
            val screenOnBetween = j < onTimes.size && onTimes[j] < b.time
            val dt = b.time - a.time
            if (a.charging || b.charging || a.screenOn || b.screenOn || screenOnBetween) continue
            if (dt <= 0 || dt > maxGapMs || a.chargeCounterUah <= 0 || b.chargeCounterUah <= 0) continue
            if (b.chargeCounterUah > a.chargeCounterUah) continue
            mah += (a.chargeCounterUah - b.chargeCounterUah) / 1000.0
            hours += dt / 3_600_000.0
        }
        return if (hours >= 0.5) mah / hours else null
    }

    /**
     * Average current over the last [windowMs] of the current discharge run (charge counter).
     * Returns the rate and the hours it covers.
     */
    fun recentMa(samples: List<Sample>, now: Long, windowMs: Long = 3 * 3_600_000L): Pair<Double, Double>? {
        val last = samples.lastOrNull() ?: return null
        if (last.charging) return null
        val runStart = Drain.lastUnplugIndex(samples)
        if (runStart !in samples.indices) return null
        val from = (runStart until samples.size).firstOrNull { samples[it].time >= now - windowMs } ?: return null
        val a = samples[from]
        val ma = Drain.averageMa(a, last) ?: return null
        val hours = (last.time - a.time) / 3_600_000.0
        return if (hours >= 0.33) ma to hours else null
    }

    fun formatHours(h: Double): String = when {
        h < 1 -> "${Math.round(h * 60)} " + tr("min", "dk")
        h < 10 -> tr("%.1f h", "%.1f sa").format(java.util.Locale.US, h)
        h < 48 -> "${Math.round(h)} " + tr("h", "sa")
        else -> tr("%.1f days", "%.1f gün").format(java.util.Locale.US, h / 24)
    }
}
