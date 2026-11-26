package app.plain.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageTest {
    private fun sample(t: Long, level: Int, counterUah: Long = 0, plugged: String = "none") =
        Sample(t, level, "discharging", plugged, 0, 4000, 250, counterUah, false, "job")

    @Test fun capacityPrefersBatterystats() {
        val c = Usage.capacity(5000.0, sample(0, 50, 2_400_000))!!
        assertEquals(5000.0, c.mah, 0.0)
        assertEquals(Usage.Source.BATTERYSTATS, c.source)
    }

    @Test fun capacityFallsBackToChargeCounter() {
        // 2.4 Ah left at 50 % -> a full battery holds 4.8 Ah
        val c = Usage.capacity(0.0, sample(0, 50, 2_400_000))!!
        assertEquals(4800.0, c.mah, 1e-9)
        assertEquals(Usage.Source.CHARGE_COUNTER, c.source)
        assertEquals(Usage.Source.CHARGE_COUNTER, Usage.capacity(null, sample(0, 50, 2_400_000))!!.source)
    }

    @Test fun capacityRejectsJunk() {
        assertNull(Usage.capacity(null, null))
        assertNull(Usage.capacity(0.0, sample(0, 10, 500_000)))     // level too low to trust
        assertNull(Usage.capacity(0.0, sample(0, 50, 0)))           // no charge counter
        assertNull(Usage.capacity(0.0, sample(0, 50, 50_000_000)))  // 100 Ah: implausible
        // implausible batterystats value falls through to the counter
        assertEquals(Usage.Source.CHARGE_COUNTER, Usage.capacity(5.0, sample(0, 80, 4_000_000))!!.source)
    }

    @Test fun percentOfCapacity() {
        assertEquals(4.2, Usage.pct(210.0, 5000.0), 1e-9)
        assertEquals(0.0, Usage.pct(210.0, 0.0), 0.0)
        assertEquals("4.2 %", Usage.formatPct(4.2))
        assertEquals("0.1 %", Usage.formatPct(0.06))
        assertEquals("<0.1 %", Usage.formatPct(0.04))
        assertEquals("12 %", Usage.formatPct(12.4))
        assertEquals("10 %", Usage.formatPct(9.96))
        val cap = Usage.Capacity(5000.0, Usage.Source.BATTERYSTATS)
        assertEquals("4.9 %", Usage.format(245.0, cap))
        assertEquals("245 mAh", Usage.format(245.0, null))
        assertEquals("3.5 mAh", Usage.format(3.5, null))
    }

    @Test fun visibleAppsDropsRowsUnderATenthOfAPercent() {
        val s = Snapshot(0, 1, 0, 0.0, 0, 5000.0, mapOf(
            1 to UidPower(1, 245.0, 214.0, 262.0),
            2 to UidPower(2, 2.0, 0.0, 2.0),      // 0.04 % -> hidden
            3 to UidPower(3, 3.0, 0.0, 3.0),      // 0.06 % -> shown
        ), emptyMap(), emptyMap())
        val cap = Usage.Capacity(5000.0, Usage.Source.BATTERYSTATS)
        assertEquals(listOf(1, 3), Usage.visibleApps(s, cap).map { it.uid })
        assertEquals(listOf(1, 3, 2), Usage.visibleApps(s, null).map { it.uid })
    }

    @Test fun daysSumLevelChangesPerDay() {
        val day1 = java.util.Calendar.getInstance().apply { set(2026, 10, 23, 22, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis
        val h = 3_600_000L
        val samples = listOf(
            sample(day1, 80),
            sample(day1 + h, 75),                    // -5 on day 1
            sample(day1 + 3 * h, 70),                // 01:00 next day: -5 on day 2
            sample(day1 + 4 * h, 90, plugged = "ac"),// +20
            sample(day1 + 5 * h, -1),                // unknown level: skipped
            sample(day1 + 6 * h, 88),                // -2
        )
        val days = History.days(samples)
        assertEquals(2, days.size)
        assertEquals(Csv.time(day1 + 3 * h).substring(0, 10), days[0].date)   // newest first
        assertEquals(7, days[0].usedPct)
        assertEquals(20, days[0].chargedPct)
        assertEquals(3, days[0].samples.size)
        assertEquals(5, days[1].usedPct)
        assertEquals(0, days[1].chargedPct)
        assertEquals(day1, days[1].samples.first().time)
        assertEquals(emptyList<History.Day>(), History.days(emptyList()))
    }
}
