package app.plain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AdvTest {
    @Test fun serializeRoundTrip() {
        val a = Adv(ev = -7, iso = 400, shutterNs = 4_000_000, wb = Adv.WB_KELVIN, kelvin = 3200, focus = 0.4f,
            contrast = 30, shadows = -20, sharpen = 3, noise = 1, scene = 5, sat = 25, warmth = -10, tint = 5,
            vibrance = 30, grid = 2, level = false, faces = true, hints = false, steady = true, jpeg = 90,
            bitrateHigh = true, lens = 1, flashPct = 40)
        assertEquals(a, Adv.parse(a.serialize()))
        assertEquals(Adv(), Adv.parse(null))
        assertEquals(Adv(), Adv.parse("garbage;;x=1"))
        assertEquals(20, Adv.parse("ev=99").ev)
        assertEquals(100, Adv.parse("fs=500").flashPct)
        assertEquals(0, Adv.parse("ev=3").flashPct) // settings saved before flash strength existed
    }

    @Test fun flashStrength() {
        // default (0 %) keeps the camera's own default level
        assertEquals(4, Adv.flashLevel(0, 10, 4))
        assertEquals(1, Adv.flashLevel(0, 1, 1))
        // every slider level survives the trip through the stored percentage
        for (max in listOf(2, 3, 5, 10, 16, 32)) for (lv in 1..max) assertEquals(lv, Adv.flashLevel(Adv.flashPct(lv, max), max, 1))
        // a percentage saved on one camera maps onto another's range
        assertEquals(5, Adv.flashLevel(50, 10, 1))
        assertEquals(1, Adv.flashLevel(1, 10, 1))
        assertEquals(10, Adv.flashLevel(100, 10, 1))
        assertEquals(0, Adv.flashPct(3, 1)) // no levels: nothing to store
    }

    @Test fun evThirds() {
        assertEquals(listOf(-20, -17, -13, -10, -7, -3, 0, 3, 7, 10, 13, 17, 20), (-6..6).map { Adv.evTenths(it) })
        for (p in -6..6) assertEquals(p, Adv.evPos(Adv.evTenths(p)))
        assertEquals("+0.7", Adv.evLabel(7))
        assertEquals("-1.3", Adv.evLabel(-13))
        assertEquals("±0", Adv.evLabel(0))
    }

    @Test fun shutterLabelsAndChoices() {
        assertEquals("1/250", Adv.shutterLabel(4_000_000))
        assertEquals("2\"", Adv.shutterLabel(2_000_000_000))
        assertEquals("Auto", Adv.shutterLabel(0))
        // the phone: 1/10000 s … 30 s
        val photo = Adv.shutterChoices(100_000, 30_000_000_000, false)
        assertEquals(Adv.SHUTTER, photo)
        val video = Adv.shutterChoices(100_000, 30_000_000_000, true)
        assertTrue(video.all { it <= Adv.FRAME_30 })
        assertEquals("1/30", Adv.shutterLabel(video.last()))
        assertEquals(listOf(100, 200, 400, 800, 1600, 3200, 6400), Adv.isoChoices(100, 19200))
    }

    @Test fun exposureFollowsTheOther() {
        // auto chose ISO 200 at 1/100 s
        val (iso, ns) = Adv.exposure(Adv(iso = 800), 200, 10_000_000, 100, 19200, 100_000, 30_000_000_000, false)
        assertEquals(800, iso)
        assertEquals(2_500_000, ns) // same light at 4x the ISO
        val (iso2, ns2) = Adv.exposure(Adv(shutterNs = 40_000_000), 200, 10_000_000, 100, 19200, 100_000, 30_000_000_000, false)
        assertEquals(40_000_000, ns2)
        assertEquals(100, iso2) // would be 50, clamped to the sensor's lowest
        // video can't go slower than a frame
        val (_, ns3) = Adv.exposure(Adv(iso = 100), 3200, 30_000_000, 100, 19200, 100_000, 30_000_000_000, true)
        assertEquals(Adv.FRAME_30, ns3)
        // very dark (auto had ISO 6400 at 1/6 s): ISO 400 alone would need 2.7 s; capped at 1/4 s
        val (_, ns4) = Adv.exposure(Adv(iso = 400), 6400, 170_000_000, 100, 19200, 100_000, 30_000_000_000, false)
        assertEquals(Adv.AUTO_SLOWEST, ns4)
        val both = Adv.exposure(Adv(iso = 400, shutterNs = 1_000_000_000), 0, 0, 100, 19200, 100_000, 30_000_000_000, false)
        assertEquals(400 to 1_000_000_000L, both)
    }

    @Test fun kelvin() {
        val (r, b) = Adv.kelvinFactors(5500)
        assertTrue(abs(r - 1f) < 1e-4 && abs(b - 1f) < 1e-4)
        val (rw, bw) = Adv.kelvinFactors(3000) // tungsten light: cool the picture down
        assertTrue(rw < 1f && bw > 1f)
        val (rc, bc) = Adv.kelvinFactors(8000) // shade: warm it up
        assertTrue(rc > 1f && bc < 1f)
    }

    @Test fun focusLabels() {
        assertEquals("Auto", Adv.focusLabel(-1f, 10f))
        assertEquals("∞", Adv.focusLabel(0f, 10f))
        assertEquals("10 cm", Adv.focusLabel(1f, 10f))
        assertEquals("2.0 m", Adv.focusLabel(0.05f, 10f))
    }

    @Test fun toneCurveIsSrgbAndMonotonic() {
        val flat = Adv.toneCurve(0, 0)
        assertEquals(128, flat.size)
        assertEquals(0f, flat[0]); assertEquals(0f, flat[1])
        assertEquals(1f, flat[126]); assertEquals(1f, flat[127])
        // linear 0.214 is about sRGB 0.5
        val mid = (0 until 64).minByOrNull { abs(flat[it * 2] - 0.214f) }!!
        assertTrue(abs(flat[mid * 2 + 1] - 0.5f) < 0.03f)
        for (c in listOf(-100, 0, 100)) for (s in listOf(-100, 0, 100)) {
            val k = Adv.toneCurve(c, s, 101)
            for (i in 1 until 101) {
                assertTrue(k[i * 2] >= k[i * 2 - 2])
                assertTrue(k[i * 2 + 1] >= k[i * 2 - 1])
            }
        }
        // more shadows = brighter dark tones
        val up = Adv.toneCurve(0, 100)
        assertTrue(up[mid / 2 * 2 + 1] > flat[mid / 2 * 2 + 1])
    }

    @Test fun lookMatrix() {
        val n = Look()
        assertTrue(n.neutral)
        val m = n.matrix()
        assertEquals(0xFF336699.toInt(), n.pixel(0xFF336699.toInt(), m))
        val bw = Look(sat = -100)
        val p = bw.pixel(0xFFFF0000.toInt(), bw.matrix())
        val r = p shr 16 and 255; val g = p shr 8 and 255; val b = p and 255
        assertTrue(r == g && g == b)
        val warm = Look(warmth = 100)
        val q = warm.pixel(0xFF808080.toInt(), warm.matrix())
        assertTrue((q shr 16 and 255) > 128 && (q and 255) < 128)
        // vibrance lifts a dull colour more than an already strong one
        val v = Look(vibrance = 100)
        val vm = v.matrix()
        fun chroma(c: Int) = maxOf(c shr 16 and 255, c shr 8 and 255, c and 255) - minOf(c shr 16 and 255, c shr 8 and 255, c and 255)
        val dull = 0xFF8C8078.toInt()
        val strong = 0xFFE01010.toInt()
        val dullGain = chroma(v.pixel(dull, vm)) - chroma(dull)
        val strongGain = chroma(v.pixel(strong, vm)) - chroma(strong)
        assertTrue(dullGain > 0)
        assertTrue(dullGain.toFloat() / chroma(dull) > strongGain.toFloat() / chroma(strong))
        assertEquals("Vivid", Look.name(Look(sat = 25, vibrance = 30)))
        assertEquals("Custom", Look.name(Look(sat = 1)))
    }

    @Test fun sensorToViewInvertsViewToSensor() {
        for (s in listOf(0, 90, 180, 270)) for (front in listOf(false, true)) {
            val (sx, sy) = Sizes.viewToSensor(0.2f, 0.7f, s, front)
            val (vx, vy) = Geo.sensorToView(sx, sy, s, front)
            assertEquals(0.2f, vx, 1e-5f)
            assertEquals(0.7f, vy, 1e-5f)
        }
    }

    @Test fun levelFromGravity() {
        assertEquals(0f, Geo.roll(0f, 9.8f), 0.01f)
        assertEquals(90f, Geo.roll(-9.8f, 0f), 0.01f) // turned clockwise onto its side
        assertEquals(5f, Geo.offLevel(5f), 1e-4f)
        assertEquals(-3f, Geo.offLevel(87f), 1e-4f)
        assertEquals(2f, Geo.offLevel(-88f), 1e-4f)
    }
}

class AdvisorTest {
    private fun frame(w: Int = 160, h: Int = 200, f: (Int, Int) -> Int) = Stats(w, h, IntArray(w * h) { f(it % w, it / w) })

    @Test fun statsBasics() {
        val s = frame { _, _ -> 100 }
        assertEquals(100f, s.mean, 0.01f)
        assertEquals(0f, s.clipped)
        assertEquals(0f, s.spread)
        assertFalse(s.sun)
        assertEquals(100f, s.meanIn(0.1f, 0.1f, 0.3f, 0.3f), 0.01f)
    }

    @Test fun sunIsASmallHotSpot() {
        // dim landscape with a small blown disc near the top
        val sun = frame { x, y -> if ((x - 40) * (x - 40) + (y - 30) * (y - 30) < 64) 255 else 90 + y / 4 }
        assertTrue(sun.sun)
        assertEquals("Sun or a bright light in frame – flare likely. Move it to the edge or shade the lens", Advisor.hint(Moment(sun)))
        // a whole blown sky is not "the sun"
        val sky = frame { _, y -> if (y < 70) 255 else 80 }
        assertFalse(sky.sun)
        assertTrue(Advisor.hint(Moment(sky))!!.startsWith("Highlights blown"))
    }

    @Test fun backlitAndDark() {
        val backlit = frame { x, y -> if (x in 40 until 120 && y in 50 until 150) 40 else 254 }
        assertEquals("Subject backlit – tap it to expose for it, or use flash", Advisor.hint(Moment(backlit)))
        val dark = frame { _, _ -> 20 }
        assertEquals("Too dark – add light or turn on flash", Advisor.hint(Moment(dark, iso = 6400, exposureNs = 60_000_000)))
        assertEquals("Too dark – add light or turn on flash", Advisor.hint(Moment(frame { _, _ -> 120 }, flashRequired = true)))
        assertNull(Advisor.hint(Moment(frame { x, _ -> x }, flashRequired = true, flashOn = true)))
    }

    @Test fun priorities() {
        val ok = frame { x, y -> (x + y) % 256 }
        assertNull(Advisor.hint(Moment(ok)))
        assertTrue(Advisor.ready(Moment(ok)))
        assertEquals("Shaking – hold still", Advisor.hint(Moment(ok, shake = 1f)))
        assertFalse(Advisor.ready(Moment(ok, shake = 0.5f)))
        assertEquals("Tilted 5° – straighten", Advisor.hint(Moment(ok, tilt = -5f)))
        assertNull(Advisor.hint(Moment(ok, tilt = 45f))) // on purpose
        assertEquals("Soft – tap to focus", Advisor.hint(Moment(ok, unfocused = true)))
        assertEquals("Low light – hold steady, the photo will be noisy", Advisor.hint(Moment(ok, iso = 1600, exposureNs = 40_000_000)))
        assertEquals("Face cut off by the edge", Advisor.hint(Moment(ok, faces = listOf(FaceBox(0.9f, 0.3f, 1f, 0.5f)))))
        val shade = frame { x, y -> if (x in 40 until 80 && y in 40 until 80) 30 else 160 }
        assertEquals("Face in shadow – tap the face or add light", Advisor.hint(Moment(shade, faces = listOf(FaceBox(0.25f, 0.2f, 0.5f, 0.4f)))))
        assertEquals("Flat light – low contrast", Advisor.hint(Moment(frame { x, _ -> 110 + x / 8 })))
    }

    @Test fun verdicts() {
        assertNull(Advisor.verdict(Moment(frame { x, y -> (x + y) % 256 })))
        assertEquals("Saved · dark", Advisor.verdict(Moment(frame { _, _ -> 10 })))
        assertNotNull(Advisor.verdict(Moment(frame { _, y -> if (y < 20) 255 else 100 })))
    }

    @Test fun hintGateNoFlicker() {
        val g = HintGate(showAfter = 900, holdMin = 2000)
        assertNull(g.update(0, "A"))
        assertNull(g.update(500, "A"))
        assertEquals("A", g.update(1000, "A"))
        // gone, but held for 2 s
        assertEquals("A", g.update(1500, null))
        assertEquals("A", g.update(2900, "B"))
        assertEquals("A", g.update(3100, "B")) // A's 2 s are up, but B has only lasted 200 ms
        assertEquals("B", g.update(3800, "B"))
        val h = HintGate(showAfter = 900, holdMin = 2000)
        h.update(0, "A"); h.update(1000, "A")
        assertEquals("A", h.update(2500, "B"))
        assertEquals("B", h.update(3500, "B"))
        assertNull(h.update(6000, null))
    }
}
