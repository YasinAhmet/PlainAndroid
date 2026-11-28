package app.plain.battery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Odd `dumpsys batterystats --checkin` output: NaN, negative values, cut-off lines, new fields, huge dumps. */
class CheckinEdgeTest {

    @Test fun nanAndInfinityDoNotPoisonTheTotals() {
        val s = Checkin.parse("""
            9,0,l,bt,1,5000,1,1,1,1700000000000
            9,0,l,dc,10,11,1,1,NaN,0
            9,0,l,pws,Infinity,1,1,1
            9,10001,l,pwi,uid,NaN,0,NaN,-Infinity
            9,10002,l,pwi,uid,12.5,0,2,13
            9,0,l,pwi,cell,Infinity,0
        """.trimIndent(), 1)!!
        assertEquals(0.0, s.dischargeMah, 0.0)
        assertEquals(0.0, s.capacityMah, 0.0)
        assertEquals(0.0, s.apps[10001]!!.mah, 0.0)
        assertEquals(0.0, s.apps[10001]!!.smearMah, 0.0)
        assertEquals(12.5, s.appsTotal, 1e-9)
        assertEquals(0.0, s.hardware["cell"]!!, 0.0)
        assertTrue(s.appsTotal.isFinite())
        assertEquals(listOf(10002), Checkin.ranking(s).map { it.uid })
    }

    @Test fun negativeValuesNeverShowAsUse() {
        val a = Checkin.parse("9,0,l,bt,1,5000,1,1,1,7\n9,10001,l,pwi,uid,5,0,0,5\n9,0,l,dc,1,1,1,1,100,0", 0)!!
        val b = Checkin.parse("9,0,l,bt,1,9000,1,1,1,7\n9,10001,l,pwi,uid,-3,0,-1,-3\n9,0,l,dc,1,1,1,1,150,0", 1)!!
        val d = Checkin.delta(a, b)
        assertEquals(0.0, d.apps[10001]!!.mah, 0.0)
        assertTrue(Checkin.ranking(b).isEmpty()) // a negative estimate is not a row
    }

    @Test fun cutOffAndUnknownLines() {
        val text = "9,0,l,bt,1,5000\n" +            // too short: ignored
            "9,10001,l,pwi,uid,4.5,0,1,4.5,extra,fields,from,a,new,android\n" +
            "9,10002,l,pwi,uid,3.\n" +           // cut off in the middle of the line
            "9,10003,l,pwi,uid\n" +              // cut off before the number
            "9,x,l,pwi,uid,9\n" +                // broken uid
            "10,10004,l,pwi,uid,2,0,0,2\n" +     // a future checkin version number
            "9,10005,l,newthing,1,2,3\n" +
            "\n\r\n,,,,,,,,\n9,"
        val s = Checkin.parse(text, 0)!!
        assertEquals(setOf(10001, 10002, 10004), s.apps.keys)
        assertEquals(4.5, s.apps[10001]!!.mah, 1e-9)
        assertEquals(3.0, s.apps[10002]!!.mah, 1e-9)
        assertEquals(0L, s.statsStart)
    }

    @Test fun emptyAndPermissionDeniedOutput() {
        assertNull(Checkin.parse("", 0))
        assertNull(Checkin.parse("\n\n", 0))
        assertNull(Checkin.parse("Permission Denial: can't dump BatteryStats from from pid=1, uid=10200 without permission android.permission.DUMP", 0))
        assertNull(Checkin.parse("9,0,i,vers,36,212\n9,0,l,bt,1,1,1,1,1,1", 0)) // no power lines at all
    }

    @Test fun savedSnapshotWithNanOrJunkStillLoads() {
        val text = "v\t1\nmeta\t1\t2\t3\tNaN\t4\t5000.0\napp\t10001\tNaN\t1.0\t2.0\napp\tbad\t1\t1\t1\napp\t10002\t1.5\nhw\tcell\tInfinity\nname\t10001\t\n"
        val s = Checkin.deserialize(text)!!
        assertEquals(0.0, s.dischargeMah, 0.0)
        assertEquals(0.0, s.apps[10001]!!.mah, 0.0)
        assertEquals(setOf(10001), s.apps.keys) // the damaged app lines are skipped
        assertEquals(0.0, s.hardware["cell"]!!, 0.0)
        assertEquals(emptyList<String>(), s.names[10001])
        assertNull(Checkin.deserialize("v\t1\nmeta\t1\t2\n")) // cut-off meta line
        assertNull(Checkin.deserialize(""))
    }

    @Test fun hugeDumpParsesQuickly() {
        val sb = StringBuilder("9,0,l,bt,1,5000,1,1,1,1700000000000\n9,0,l,pws,5000,1,1,1\n9,0,l,dc,1,1,1,1,300,0\n")
        for (u in 10000 until 15000) {
            sb.append("9,0,i,uid,").append(u).append(",com.example.app").append(u).append('\n')
            sb.append("9,").append(u).append(",l,pwi,uid,").append(u % 97 / 10.0).append(",0,0.5,1\n")
            sb.append("9,").append(u).append(",l,wl,*alarm*,0,f,0,0,0,0\n")
        }
        val t0 = System.nanoTime()
        val s = Checkin.parse(sb.toString(), 0)!!
        val back = Checkin.deserialize(Checkin.serialize(s))
        assertEquals(5000, s.apps.size)
        assertEquals(s, back)
        assertNotNull(Checkin.ranking(s).firstOrNull())
        assertTrue("too slow", System.nanoTime() - t0 < 5_000_000_000L)
    }

    @Test fun durationsNeverNegative() {
        assertEquals("0m", Drain.formatDuration(-3_600_000L)) // clock set back
        assertEquals("0m", Drain.formatDuration(0))
        assertEquals("106751991167d 7h", Drain.formatDuration(Long.MAX_VALUE)) // no overflow, just big
    }

    @Test fun batteryCsvWithUnsupportedCounters() {
        // getLongProperty returns Long.MIN_VALUE when a phone has no counter: logged raw, read back, never used as drain
        val x = Sample(1_700_000_000_000, 55, "discharging", "none", Long.MIN_VALUE, 4000, -52, Long.MIN_VALUE, false, "job")
        val back = Csv.parseBattery(Csv.battery(x))!!
        assertEquals(x, back)
        assertNull(Drain.averageMa(back, back.copy(time = back.time + 3_600_000L)))
        assertNull(Usage.capacity(null, back))
        assertNull(Csv.parseBattery("2026-11-28 10:00:00,1764316800000,8"))     // cut off while being written
        assertNull(Csv.parseBattery("2026-11-28 10:00:00,1764316800000,x,a,b,c,d,e,f,g,h"))
    }
}
