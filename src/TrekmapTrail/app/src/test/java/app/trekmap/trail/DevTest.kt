package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DevTest {

    private val sample = """
        # trekmap-raw v1
        E,1000000,start,plan=-
        B,1000000,90,0,30.5,-210,4100,0,0,1
        E,1000100,mode,live interval=1000 batch=0
        F,1001000,1001000,40,41.0,29.0,5.0,100,,3,1.4,0.3,10,5,18,27.5,38,1,S,L
        F,1002000,1002000,35,41.00001,29.0,5.0,100,,3,1.4,0.3,10,5,18,27.5,38,1,M,L
        F,1003000,1003000,30,41.0001,29.0,6.0,100,,3,1.4,0.3,10,5,17,26.0,37,1,S,L
        E,1061000,mode,pocket interval=2000 batch=30000
        E,1091000,batch,15
        F,1091000,1062000,29000,41.0002,29.0,7.0,100,,3,1.4,0.3,10,5,16,25.0,36,15,S,P
        F,1091000,1064000,27000,41.0002,29.0,50.0,100,,3,0.1,0.3,10,5,6,18.0,25,15,A,P
        E,1200000,rest-start,still for 2 min
        E,1200000,mode,rest gps=off waiting-for-motion
        E,1800000,motion,significant motion -> gps on
        E,1800000,mode,pocket interval=2000 batch=30000
        B,1800000,89,0,31.0,-150,4060,0,1,0
        E,4600000,stop,save
        B,4600000,86,0,31.5,-200,3980,0,0,1
    """.trimIndent()

    @Test
    fun summarisesRawLog() {
        val s = DevSummary.of(sample.lineSequence())
        assertEquals(5, s.fixes)
        assertEquals(3, s.stored)
        assertEquals(1, s.decisions['M'])
        assertEquals(1, s.decisions['A'])
        assertEquals(1, s.rests)
        assertEquals(1, s.motionWakes)
        assertEquals(1, s.batches)
        assertEquals(2, s.batchedFixes)
        assertEquals(90, s.battStart)
        assertEquals(86, s.battEnd)
        assertEquals(29_000L, s.maxAgeMs)
        assertEquals(600_000L, s.modeMs["rest"])
        assertEquals(60_900L, s.modeMs["live"])
        assertEquals(139_000L + 2_800_000L, s.modeMs["pocket"])
        assertEquals(22.2, s.storedDistanceM, 1.0) // 41.0 → 41.0002
        val t = s.text()
        assertTrue(t, t.contains("motion wake-ups 1"))
        assertTrue(t, t.contains("%/h"))
    }

    @Test
    fun splitsBatteryUseByState() {
        // 0:00 start, screen on 5 min, then pocket with music over Bluetooth in airplane mode for 2 h
        val h = 3_600_000L
        val log = """
            E,0,start,plan=-
            E,10,env,airplane on · audio-out bt · music on
            B,20,90,0,30,-400000,4500,0,0,1,1,1,1,1,bt,60,0,none,-1,,120,1,500
            E,300000,screen,off
            B,600000,89,0,30,-200000,4450,0,0,0,1,1,1,1,bt,60,0,none,-1,,120,1,300
            E,${600000 + h - 1},music,off
            B,${600000 + h},89,0,30,-200000,4350,0,0,0,1,1,1,0,bt,60,0,none,-1,,120,1,900
            B,${600000 + 2 * h},89,0,30,-200000,4300,0,0,0,1,1,1,0,bt,60,0,none,-1,,120,1,900
            E,${600000 + 2 * h},stop,save
        """.trimIndent()
        val s = DevSummary.of(log.lineSequence())
        assertEquals(300_000L - 20, s.stateMs["screen"]) // from the first battery row to "screen off"
        assertEquals(600_000L + h - 11, s.stateMs["music"])
        assertEquals(600_000L + 2 * h - 10, s.stateMs["airplane"])
        // screen-off hours: one with music (100 mAh), one without (50 mAh)
        val withMusic = s.offDrain["airplane on · music on · bt audio on"]!!
        assertEquals(100.0, withMusic[0] / withMusic[1], 0.5)
        val noMusic = s.offDrain["airplane on · music off · bt audio on"]!!
        assertEquals(50.0, noMusic[0] / noMusic[1], 0.5)
        assertEquals(2600L, s.appCpuMs)
        val t = s.text()
        assertTrue(t, t.contains("Drain screen off, airplane on · music on · bt audio on: 100 mA"))
        assertTrue(t, t.contains("Trekmap CPU time"))
    }

    /**
     * Development tool: replays real recordings through the current filter.
     * Pull logs with   adb pull /sdcard/Android/data/app.trekmap.trail/files/dev/trails <dir>
     * then run         $env:REPLAY_DIR="<dir>"; .\gradlew.bat testDebugUnitTest --tests *DevTest*
     */
    @Test
    fun replayRealRecordings() {
        val dir = System.getenv("REPLAY_DIR")?.let(::File) ?: return
        val files = dir.walkTopDown().filter { it.name.endsWith(".raw.csv") }.toList()
        for (f in files) {
            val filter = TrackFilter()
            var stored = 0
            var dist = 0.0
            var pLat = Double.NaN; var pLon = Double.NaN
            var lastMode = ""
            f.forEachLine { l ->
                val p = l.split(',')
                if (p[0] == "E" && p.getOrNull(2) == "mode") {
                    val m = p.getOrNull(3)?.substringBefore(' ') ?: ""
                    if (m != lastMode && lastMode.startsWith("rest")) filter.reset()
                    lastMode = m
                }
                if (p[0] != "F" || p.size < 20 || p[18] == "R") return@forEachLine
                val lat = p[4].toDouble(); val lon = p[5].toDouble()
                val acc = p[6].toFloatOrNull() ?: 999f
                val r = filter.push(lat, lon, acc, p[2].toLong(), p[10].toFloatOrNull() ?: -1f) ?: return@forEachLine
                stored++
                if (!pLat.isNaN()) dist += Geo.dist(pLat, pLon, r[0], r[1])
                pLat = r[0]; pLon = r[1]
            }
            val orig = f.useLines { DevSummary.of(it) }
            println("${f.name}: recorded ${orig.stored} pts ${Fmt.dist(orig.storedDistanceM)} → replay $stored pts ${Fmt.dist(dist)}")
            println(orig.text().prependIndent("    "))
        }
    }
}
