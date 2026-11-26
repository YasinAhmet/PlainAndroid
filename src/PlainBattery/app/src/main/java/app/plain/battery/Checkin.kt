package app.plain.battery

/**
 * Parsing of `dumpsys batterystats --checkin` (checkin format version 9, Android 12+).
 *
 * Lines look like `9,<uid>,<category>,<type>,<values...>`. Only the category "l" (since the
 * stats were last reset, i.e. since the last full charge) carries power estimates; "i" lines
 * carry the uid -> package table.
 *
 *  - `pwi,uid,<mAh>,<hide>,<screen mAh>,<mAh incl. smeared share>` : per-uid power estimate
 *  - `pwi,<component>,<mAh>,...` (uid 0)                             : device-wide components
 *  - `dc,<low%>,<high%>,<scrOn%>,<scrOff%>,<mAh>,<scrOff mAh>,...`    : measured discharge
 *  - `pws,<capacity>,<computed>,<min>,<max>`                          : capacity & totals
 *  - `bt,<startCount>,<batRealtime>,<batUptime>,<realtime>,<uptime>,<startClockTime>,...`
 */
data class UidPower(val uid: Int, val mah: Double, val screenMah: Double, val smearMah: Double)

data class Snapshot(
    val takenAt: Long,
    /** Wall clock time (ms) when batterystats was last reset; identifies the stats period. */
    val statsStart: Long,
    /** Time on battery (ms) since the reset. */
    val onBatteryMs: Long,
    /** Coulomb-counter discharge (mAh) since the reset. */
    val dischargeMah: Double,
    val dischargePct: Int,
    val capacityMah: Double,
    val apps: Map<Int, UidPower>,
    val hardware: Map<String, Double>,
    val names: Map<Int, List<String>>,
) {
    val appsTotal: Double get() = apps.values.sumOf { it.mah }
}

object Checkin {

    fun parse(text: String, takenAt: Long): Snapshot? {
        var statsStart = 0L
        var onBattery = 0L
        var dischargeMah = 0.0
        var dischargePct = 0
        var capacity = 0.0
        var sawPower = false
        val apps = HashMap<Int, UidPower>()
        val hw = LinkedHashMap<String, Double>()
        val names = HashMap<Int, MutableList<String>>()
        for (line in text.lineSequence()) {
            if (line.length < 8 || !line[0].isDigit()) continue
            val f = line.split(',')
            if (f.size < 5) continue
            val uid = f[1].toIntOrNull() ?: continue
            val cat = f[2]
            when (f[3]) {
                "uid" -> if (cat == "i" && f.size >= 6) {
                    val u = f[4].toIntOrNull() ?: continue
                    val list = names.getOrPut(u) { ArrayList() }
                    if (f[5] !in list) list += f[5]
                }
                "bt" -> if (cat == "l" && f.size >= 10) {
                    onBattery = f[5].toLongOrNull() ?: 0L
                    statsStart = f[9].toLongOrNull() ?: 0L
                }
                "dc" -> if (cat == "l" && f.size >= 9) {
                    dischargePct = f[4].toIntOrNull() ?: 0
                    dischargeMah = f[8].toDoubleOrNull() ?: 0.0
                }
                "pws" -> if (cat == "l") capacity = f[4].toDoubleOrNull() ?: 0.0
                "pwi" -> if (cat == "l" && f.size >= 6) {
                    sawPower = true
                    val mah = f[5].toDoubleOrNull() ?: 0.0
                    if (f[4] == "uid") {
                        val screen = f.getOrNull(7)?.toDoubleOrNull() ?: 0.0
                        val smear = f.getOrNull(8)?.toDoubleOrNull() ?: mah
                        val old = apps[uid]
                        apps[uid] = if (old == null) UidPower(uid, mah, screen, smear)
                        else UidPower(uid, old.mah + mah, old.screenMah + screen, old.smearMah + smear)
                    } else {
                        val label = if (f[4] == "???") "other" else f[4]
                        hw[label] = (hw[label] ?: 0.0) + mah
                    }
                }
            }
        }
        if (!sawPower) return null
        return Snapshot(takenAt, statsStart, onBattery, dischargeMah, dischargePct, capacity, apps, hw, names)
    }

    /**
     * What was burned between [base] and [cur]. When batterystats was reset in between (full
     * charge), [cur] already counts only from the reset, so it is returned as is.
     */
    fun delta(base: Snapshot?, cur: Snapshot): Snapshot {
        if (base == null || isReset(base, cur)) return cur
        val apps = HashMap<Int, UidPower>()
        for ((uid, p) in cur.apps) {
            val b = base.apps[uid]
            val d = if (b == null) p else UidPower(
                uid,
                (p.mah - b.mah).coerceAtLeast(0.0),
                (p.screenMah - b.screenMah).coerceAtLeast(0.0),
                (p.smearMah - b.smearMah).coerceAtLeast(0.0),
            )
            apps[uid] = d
        }
        val hw = LinkedHashMap<String, Double>()
        for ((k, v) in cur.hardware) hw[k] = (v - (base.hardware[k] ?: 0.0)).coerceAtLeast(0.0)
        return cur.copy(
            onBatteryMs = (cur.onBatteryMs - base.onBatteryMs).coerceAtLeast(0L),
            dischargeMah = (cur.dischargeMah - base.dischargeMah).coerceAtLeast(0.0),
            dischargePct = (cur.dischargePct - base.dischargePct).coerceAtLeast(0),
            apps = apps,
            hardware = hw,
        )
    }

    fun isReset(base: Snapshot, cur: Snapshot): Boolean =
        base.statsStart != cur.statsStart || cur.dischargeMah + 0.5 < base.dischargeMah ||
            cur.onBatteryMs < base.onBatteryMs

    /** Apps sorted by estimated drain, biggest first, dropping zero rows. */
    fun ranking(s: Snapshot, minMah: Double = 0.05): List<UidPower> =
        s.apps.values.filter { it.mah >= minMah }.sortedByDescending { it.mah }

    // ---- persistence (small TSV, private storage) ----

    fun serialize(s: Snapshot): String = buildString {
        append("v\t1\n")
        append("meta\t").append(s.takenAt).append('\t').append(s.statsStart).append('\t')
            .append(s.onBatteryMs).append('\t').append(s.dischargeMah).append('\t')
            .append(s.dischargePct).append('\t').append(s.capacityMah).append('\n')
        for (p in s.apps.values) append("app\t${p.uid}\t${p.mah}\t${p.screenMah}\t${p.smearMah}\n")
        for ((k, v) in s.hardware) append("hw\t").append(k).append('\t').append(v).append('\n')
        for ((u, l) in s.names) append("name\t$u\t").append(l.joinToString(",")).append('\n')
    }

    fun deserialize(text: String): Snapshot? {
        var meta: List<String>? = null
        val apps = HashMap<Int, UidPower>()
        val hw = LinkedHashMap<String, Double>()
        val names = HashMap<Int, List<String>>()
        for (line in text.lineSequence()) {
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "meta" -> meta = f
                    "app" -> apps[f[1].toInt()] =
                        UidPower(f[1].toInt(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble())
                    "hw" -> hw[f[1]] = f[2].toDouble()
                    "name" -> names[f[1].toInt()] = f[2].split(',').filter { it.isNotEmpty() }
                }
            } catch (e: RuntimeException) { /* skip damaged line */ }
        }
        val m = meta ?: return null
        return try {
            Snapshot(m[1].toLong(), m[2].toLong(), m[3].toLong(), m[4].toDouble(), m[5].toInt(),
                m[6].toDouble(), apps, hw, names)
        } catch (e: RuntimeException) { null }
    }
}

/** Human names for system / virtual uids that have no app label. */
object UidNames {
    private val fixed = mapOf(
        -5 to "Tethering / hotspot traffic",
        -4 to "Removed apps",
        0 to "Kernel / root processes",
        1000 to "Android system",
        1001 to "Phone / radio",
        1002 to "Bluetooth",
        1010 to "Wi-Fi",
        1013 to "Media server",
        1019 to "DRM server",
        1021 to "GPS",
        1027 to "NFC",
        1036 to "Log daemon",
        1041 to "Audio server",
        1046 to "Media codecs",
        1047 to "Camera server",
        1066 to "statsd",
        1073 to "Network stack",
        2000 to "ADB shell",
        9999 to "nobody",
    )

    /**
     * @param packages packages that share this uid (from the checkin table)
     * @param appLabel looks up a user-visible label for a package, or null when not installed
     */
    fun label(uid: Int, packages: List<String>, appLabel: (String) -> String?): String {
        fixed[uid]?.let { return it }
        if (uid < 10000) return if (packages.isEmpty()) "System uid $uid" else
            "System: " + packages.first() + if (packages.size > 1) " +${packages.size - 1}" else ""
        if (packages.isEmpty()) return "Uninstalled app (uid $uid)"
        for (p in packages) appLabel(p)?.let { l ->
            return if (packages.size > 1) "$l +${packages.size - 1}" else l
        }
        return packages.first() + " (uninstalled)"
    }
}
