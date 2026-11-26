package app.plain.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RatesTest {
    private val h = 3_600_000L

    private fun snap(t: Long, batMs: Long, wa: Double, opera: Double, dc: Double, start: Long = 1) = Snapshot(
        takenAt = t, statsStart = start, onBatteryMs = batMs, dischargeMah = dc, dischargePct = 0,
        capacityMah = 5000.0,
        apps = mapOf(10274 to UidPower(10274, wa, wa / 2, wa), 10280 to UidPower(10280, opera, 0.0, opera)),
        hardware = emptyMap(), names = emptyMap(),
    )

    private fun s(t: Long, counter: Long, screen: Boolean = false, plugged: String = "none") =
        Sample(t, 50, "discharging", plugged, 0, 4000, 250, counter, screen, "job")

    @Test fun recentPicksSnapshotAboutThreeHoursBack() {
        val hist = listOf(
            snap(0, 0, 0.0, 0.0, 0.0),
            snap(2 * h, 2 * h, 20.0, 5.0, 100.0),
            snap(4 * h, 4 * h, 60.0, 5.0, 200.0),
        )
        val latest = snap(5 * h, 5 * h, 160.0, 6.0, 300.0)
        val r = Rates.recent(hist, latest)!!
        assertEquals(2 * h, r.from.takenAt)                 // newest one at least 3 h old
        assertEquals(3.0, r.batteryHours, 1e-9)
        assertEquals(140.0, r.delta.apps[10274]!!.mah, 1e-9)
        assertEquals(70.0, r.delta.apps[10274]!!.screenMah, 1e-9)
        assertEquals(200.0, r.delta.dischargeMah, 1e-9)
    }

    @Test fun recentUsesBatteryTimeNotWallTime() {
        // 2 h of wall time, but only 30 min of it on battery
        val r = Rates.recent(listOf(snap(0, 0, 0.0, 0.0, 0.0)), snap(2 * h, h / 2, 10.0, 0.0, 30.0))!!
        assertEquals(0.5, r.batteryHours, 1e-9)
    }

    @Test fun recentNeedsSamePeriodAndEnoughBatteryTime() {
        val latest = snap(5 * h, 5 * h, 10.0, 0.0, 10.0)
        assertNull(Rates.recent(emptyList(), latest))
        assertNull(Rates.recent(listOf(snap(0, 0, 0.0, 0.0, 0.0, start = 99)), latest))      // other period
        assertNull(Rates.recent(listOf(snap(4 * h, 5 * h - 60_000, 0.0, 0.0, 0.0)), latest)) // 1 min on battery
        // newer than the window: falls back to the oldest usable one
        assertEquals(4 * h, Rates.recent(listOf(snap(4 * h, 4 * h, 0.0, 0.0, 0.0)), latest)!!.from.takenAt)
    }

    @Test fun hoursLeft() {
        assertEquals(10.0, Rates.hoursLeft(500.0, 50.0)!!, 1e-9)
        assertNull(Rates.hoursLeft(500.0, null))
        assertNull(Rates.hoursLeft(500.0, 0.2))
        assertEquals("45 min", Rates.formatHours(0.75))
        assertEquals("2.5 h", Rates.formatHours(2.5))
        assertEquals("13 h", Rates.formatHours(12.6))
        assertEquals("2.5 days", Rates.formatHours(60.0))
    }

    @Test fun standbySkipsScreenOnAndCharging() {
        val samples = listOf(
            s(0, 4_000_000),
            s(h, 3_970_000),                       // 30 mA standby hour
            s(2 * h, 3_800_000),                   // screen came on in between -> skipped
            s(3 * h, 3_780_000, plugged = "ac"),   // charging -> skipped
            s(4 * h, 3_900_000),
            s(5 * h, 3_870_000),                   // 30 mA standby hour
        )
        val screen = listOf((h + 60_000) to true, (h + 120_000) to false)
        assertEquals(30.0, Rates.standbyMa(samples, screen)!!, 1e-9)
        assertNull(Rates.standbyMa(samples.take(1), screen))
    }

    @Test fun recentMaFromChargeCounter() {
        val now = 6 * h
        val samples = listOf(
            s(0, 4_000_000, plugged = "ac"),
            s(h, 4_000_000),                       // unplugged here
            s(2 * h, 3_950_000),
            s(3 * h, 3_900_000),
            s(4 * h, 3_850_000),
            s(5 * h, 3_800_000),
            s(6 * h, 3_650_000),
        )
        val (ma, hours) = Rates.recentMa(samples, now)!!
        assertEquals(3.0, hours, 1e-9)                         // from the 3 h mark
        assertEquals(250.0 / 3, ma, 1e-9)
        assertNull(Rates.recentMa(samples + s(7 * h, 3_600_000, plugged = "ac"), 7 * h))
        assertNotNull(Rates.recentMa(samples.take(3), 2 * h))   // run shorter than the window
    }
}
