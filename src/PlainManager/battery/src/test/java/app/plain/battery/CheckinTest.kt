package app.plain.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckinTest {
    // Trimmed from a real `dumpsys batterystats --checkin` of the phone (Android 14).
    private fun checkin(start: Long = 1795343259091, wa: String = "289", opera: String = "122",
                        dc: String = "3550", batMs: Long = 118501912) = """
        9,0,i,vers,36,212,UP1A.231005.007,UP1A.231005.007
        9,0,i,uid,1000,com.android.dynsystem
        9,0,i,uid,1000,com.transsion.statisticalsales
        9,0,i,uid,1000,com.android.dynsystem
        9,0,i,uid,10274,com.whatsapp
        9,0,i,uid,10280,com.opera.browser
        9,0,l,bt,1,$batMs,33268065,122640106,34824380,$start,107992433,22758581,5000,5000000,5000000,0
        9,0,l,dc,70,71,26,45,$dc,2250,0,0,850,50
        9,0,l,pws,5000,3550,3500,3550
        9,0,l,pwi,cell,2433,1,0,0
        9,0,l,pwi,scrn,416,1,0,0
        9,0,l,pwi,???,60.8,0,0,0
        9,0,l,pwi,???,20.2,0,0,0
        9,-5,l,pwi,uid,1210,0,0,1332
        9,10274,l,pwi,uid,$wa,0,117,318
        9,10280,l,pwi,uid,$opera,0,85.7,134
        9,1000,l,pwi,uid,155,1,0,0
        9,10304,l,pwi,uid,0,0,0,0
        9,10274,l,wl,*alarm*,0,f,0,0,0,0
        garbage line
    """.trimIndent()

    @Test fun parsesPowerItemsAndTotals() {
        val s = Checkin.parse(checkin(), 42)!!
        assertEquals(42L, s.takenAt)
        assertEquals(1795343259091, s.statsStart)
        assertEquals(118501912L, s.onBatteryMs)
        assertEquals(3550.0, s.dischargeMah, 0.0)
        assertEquals(70, s.dischargePct)
        assertEquals(5000.0, s.capacityMah, 0.0)
        assertEquals(289.0, s.apps[10274]!!.mah, 1e-9)
        assertEquals(117.0, s.apps[10274]!!.screenMah, 1e-9)
        assertEquals(318.0, s.apps[10274]!!.smearMah, 1e-9)
        assertEquals(1210.0, s.apps[-5]!!.mah, 1e-9)
        assertEquals(81.0, s.hardware["other"]!!, 1e-9)   // the two "???" rows are summed
        assertEquals(listOf("com.android.dynsystem", "com.transsion.statisticalsales"), s.names[1000])
        assertEquals(listOf("com.whatsapp"), s.names[10274])
    }

    @Test fun noPowerDataMeansNull() {
        assertNull(Checkin.parse("Permission Denial: can't dump BatteryStats from pid=1", 0))
        assertNull(Checkin.parse("", 0))
    }

    @Test fun rankingIsSortedAndSkipsZero() {
        val r = Checkin.ranking(Checkin.parse(checkin(), 0)!!)
        assertEquals(listOf(-5, 10274, 1000, 10280), r.map { it.uid })
    }

    @Test fun deltaSubtractsWithinOnePeriod() {
        val a = Checkin.parse(checkin(wa = "100", opera = "50", dc = "1000", batMs = 1000), 0)!!
        val b = Checkin.parse(checkin(wa = "130.5", opera = "50", dc = "1100", batMs = 5000), 60_000)!!
        assertFalse(Checkin.isReset(a, b))
        val d = Checkin.delta(a, b)
        assertEquals(30.5, d.apps[10274]!!.mah, 1e-9)
        assertEquals(0.0, d.apps[10280]!!.mah, 1e-9)
        assertEquals(100.0, d.dischargeMah, 1e-9)
        assertEquals(4000L, d.onBatteryMs)
        assertEquals(0.0, d.hardware["cell"]!!, 1e-9)
    }

    @Test fun resetUsesCurrentAsIs() {
        val a = Checkin.parse(checkin(start = 1, dc = "3000"), 0)!!
        val b = Checkin.parse(checkin(start = 2, dc = "10", wa = "5"), 1)!!
        assertTrue(Checkin.isReset(a, b))
        assertEquals(5.0, Checkin.delta(a, b).apps[10274]!!.mah, 1e-9)
        // same start time but the counters went backwards: also treated as a reset
        val c = Checkin.parse(checkin(dc = "10"), 1)!!
        val d = Checkin.parse(checkin(dc = "3000"), 0)!!
        assertTrue(Checkin.isReset(d, c))
        assertEquals(10.0, Checkin.delta(null, c).dischargeMah, 1e-9)
    }

    @Test fun serializeRoundTrip() {
        val s = Checkin.parse(checkin(), 7)!!
        val back = Checkin.deserialize(Checkin.serialize(s))
        assertEquals(s, back)
        assertNull(Checkin.deserialize("junk"))
    }

    @Test fun uidNames() {
        val labels = mapOf("com.whatsapp" to "WhatsApp")
        val l = { p: String -> labels[p] }
        assertEquals("WhatsApp", UidNames.label(10274, listOf("com.whatsapp"), l))
        assertEquals("WhatsApp +1", UidNames.label(10274, listOf("x.y", "com.whatsapp"), l))
        assertEquals("com.gone (uninstalled)", UidNames.label(10500, listOf("com.gone"), l))
        assertEquals("Uninstalled app (uid 10304)", UidNames.label(10304, emptyList(), l))
        assertEquals("Android system", UidNames.label(1000, listOf("android"), l))
        assertEquals("Tethering / hotspot traffic", UidNames.label(-5, emptyList(), l))
        assertEquals("System: com.x +1", UidNames.label(1234, listOf("com.x", "com.y"), l))
    }

    @Test fun appRowsLogIntervalDrain() {
        val a = Checkin.parse(checkin(wa = "100", opera = "50"), 0)!!
        val b = Checkin.parse(checkin(wa = "112", opera = "50.01"), 30 * 60_000L)!!
        val rows = Csv.appRows(a, b, { if (it == 10274) "Whats, App" else "u$it" })
        assertEquals(1, rows.size)   // Opera's 0.01 mAh is under the threshold
        val f = rows[0].split(',')
        assertEquals("30.0", f[2]); assertEquals("10274", f[3])
        assertTrue(rows[0].contains("\"Whats, App\",12.00,112.00,117.00,"))
        // first snapshot: whole period, empty interval
        val first = Csv.appRows(null, b, { "u$it" })
        assertEquals("", first[0].split(',')[2])
        assertEquals(4, first.size)
    }
}

class SamplesTest {
    private fun s(t: Long, level: Int, counter: Long, plugged: String = "none") =
        Sample(t, level, "discharging", plugged, -250000, 4100, 251, counter, false, "job")

    @Test fun batteryCsvRoundTrip() {
        val x = Sample(1_700_000_000_000, 74, "charging", "ac", -312000, 4135, 250, 3_700_000, true, "ui")
        val line = Csv.battery(x)
        assertEquals(Csv.BATTERY_HEADER.split(',').size, line.split(',').size)
        assertEquals(x, Csv.parseBattery(line))
        assertNull(Csv.parseBattery(Csv.BATTERY_HEADER))
    }

    @Test fun screenCsvRoundTrip() {
        assertEquals(5L to true, Csv.parseScreen(Csv.screen(5, true)))
        assertEquals(6L to false, Csv.parseScreen(Csv.screen(6, false)))
        assertNull(Csv.parseScreen("time,epoch_ms,event"))
    }

    @Test fun averageCurrentFromChargeCounter() {
        val h = 3_600_000L
        assertEquals(30.0, Drain.averageMa(s(0, 80, 4_000_000), s(2 * h, 79, 3_940_000))!!, 1e-9)
        assertNull(Drain.averageMa(s(0, 80, 4_000_000, "ac"), s(h, 79, 3_940_000)))
        assertNull(Drain.averageMa(s(0, 80, 0), s(h, 79, 0)))
    }

    @Test fun lastUnplug() {
        val l = listOf(s(0, 50, 1), s(1, 60, 1, "ac"), s(2, 70, 1), s(3, 69, 1))
        assertEquals(2, Drain.lastUnplugIndex(l))
        assertEquals(0, Drain.lastUnplugIndex(listOf(s(0, 50, 1), s(1, 49, 1))))
        assertEquals(-1, Drain.lastUnplugIndex(emptyList()))
        assertNotNull(Drain.formatDuration(1))
        assertEquals("3h 5m", Drain.formatDuration(185 * 60_000L))
        assertEquals("2d 1h", Drain.formatDuration((49 * 60 + 5) * 60_000L))
    }
}
