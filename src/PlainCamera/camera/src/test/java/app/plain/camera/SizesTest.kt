package app.plain.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SizesTest {
    // the back camera's real JPEG sizes on the TECNO SPARK 20 Pro+ (dumpsys media.camera)
    private val back = listOf(
        "3264x2448", "3248x1440", "2944x2944", "2560x1920", "2560x1440", "2432x1080", "2400x1064", "1920x1088",
        "1920x1080", "1800x800", "1600x1200", "1584x704", "1532x864", "1440x1080", "1280x960", "1280x720", "1280x568",
        "1152x512", "1080x1080", "1024x1024", "960x720", "960x540", "800x600", "800x480", "720x720", "720x480",
        "704x704", "640x480", "352x288", "320x240", "176x144",
    ).map { Dim.parse(it)!! }
    private val screen = 2436.0 / 1080

    @Test fun aspectsOnThePhone() {
        assertEquals(listOf(Aspect.FOUR_THREE, Aspect.SIXTEEN_NINE, Aspect.SQUARE, Aspect.FULL), Sizes.aspects(back, screen))
    }

    @Test fun sizesPerAspectBiggestFirst() {
        val four = Sizes.forAspect(back, Aspect.FOUR_THREE, screen)
        assertEquals(Dim(3264, 2448), four.first())
        assertTrue(four.all { Sizes.sameShape(it.ratio, 4.0 / 3) })
        assertTrue(four.none { it.short < 480 })
        assertEquals(Dim(2944, 2944), Sizes.forAspect(back, Aspect.SQUARE, screen).first())
        val full = Sizes.forAspect(back, Aspect.FULL, screen)
        assertEquals(Dim(3248, 1440), full.first())
        assertTrue(Dim(2432, 1080) in full && Dim(1800, 800) in full)
        val wide = Sizes.forAspect(back, Aspect.SIXTEEN_NINE, screen)
        assertTrue(Dim(1920, 1088) in wide && Dim(1532, 864) in wide)
    }

    @Test fun fullHiddenWhenItIsJust16by9() {
        val s = listOf(Dim(1920, 1080), Dim(1440, 1080))
        assertNull(Sizes.ratioFor(Aspect.FULL, s, 16.0 / 9))
        assertNull(Sizes.ratioFor(Aspect.SQUARE, s, screen))
    }

    @Test fun previewMatchesShapeAndStaysSmall() {
        assertEquals(Dim(1440, 1080), Sizes.preview(back, 4.0 / 3))
        assertEquals(Dim(1920, 1080), Sizes.preview(back, 16.0 / 9))
        assertEquals(Dim(1080, 1080), Sizes.preview(back, 1.0))
        assertEquals(Dim(1800, 800), Sizes.preview(back, 3248.0 / 1440))
    }

    @Test fun jpegOrientation() {
        assertEquals(90, Sizes.jpegOrientation(90, 0, false))     // portrait, back
        assertEquals(180, Sizes.jpegOrientation(90, 90, false))   // turned right
        assertEquals(0, Sizes.jpegOrientation(90, 270, false))    // turned left
        assertEquals(270, Sizes.jpegOrientation(270, 0, true))    // portrait, front
        assertEquals(180, Sizes.jpegOrientation(270, 90, true))
        assertEquals(90, Sizes.jpegOrientation(90, -1, false))    // unknown: portrait
        assertEquals(90, Sizes.jpegOrientation(90, 350, false))   // 350° rounds to upright
    }

    @Test fun tapMapsToSensor() {
        // back sensor at 90°: top of the portrait screen = left of the sensor image
        val (x, y) = Sizes.viewToSensor(0.5f, 0f, 90, false)
        assertEquals(0f, x, 1e-6f); assertEquals(0.5f, y, 1e-6f)
        val (x2, y2) = Sizes.viewToSensor(1f, 0.5f, 90, false) // right edge = top of the sensor
        assertEquals(0.5f, x2, 1e-6f); assertEquals(0f, y2, 1e-6f)
        val (x3, y3) = Sizes.viewToSensor(0.5f, 0.5f, 270, true)
        assertEquals(0.5f, x3, 1e-6f); assertEquals(0.5f, y3, 1e-6f)
    }

    @Test fun cropForStreamShape() {
        assertEquals(listOf(0, 0, 4000, 3000), Sizes.crop(4000, 3000, 4.0 / 3).toList())
        assertEquals(listOf(0, 375, 4000, 2250), Sizes.crop(4000, 3000, 16.0 / 9).toList())
        assertEquals(listOf(500, 0, 3000, 3000), Sizes.crop(4000, 3000, 1.0).toList())
    }

    @Test fun exifFix() {
        assertNull(Sizes.orientationTag(90, 90, 4000, 3000, false))  // HAL wrote EXIF
        assertNull(Sizes.orientationTag(90, 0, 3000, 4000, false))   // HAL turned the pixels
        assertEquals(6, Sizes.orientationTag(90, 0, 4000, 3000, false)) // HAL ignored it
        assertEquals(8, Sizes.orientationTag(270, 0, 4000, 3000, false))
        assertEquals(3, Sizes.orientationTag(180, 0, 4000, 3000, false))
        assertNull(Sizes.orientationTag(180, 0, 4000, 3000, true))
        assertNull(Sizes.orientationTag(0, 0, 4000, 3000, false))
    }

    @Test fun labelsAndBitrate() {
        assertEquals("8.0 MP · 3264×2448", Sizes.photoLabel(Dim(3264, 2448)))
        assertEquals("12 MP · 4000×3000", Sizes.photoLabel(Dim(4000, 3000)))
        assertEquals("1080p · 1920×1080", Sizes.videoLabel(Dim(1920, 1080)))
        assertEquals("4K · 3840×2160", Sizes.videoLabel(Dim(3840, 2160)))
        assertEquals(15_552_000, Sizes.videoBitrate(Dim(1920, 1080)))
        assertEquals(50_000_000, Sizes.videoBitrate(Dim(3840, 2160)))
        assertEquals(Dim(1920, 1080), Sizes.defaultVideo(listOf(Dim(2560, 1440), Dim(1920, 1088), Dim(1920, 1080), Dim(1280, 720))))
        assertEquals("1080p · 1920×1088", Sizes.videoLabel(Dim(1920, 1088)))
        assertEquals("01:05", Sizes.clock(65_400))
        assertEquals("1:00:01", Sizes.clock(3_601_000))
        assertEquals(Dim(4000, 3000), Dim.parse("4000x3000"))
        assertNull(Dim.parse("junk"))
    }

    @Test fun mirrorAxis() {
        // HAL turned the pixels (no tag / normal): flip along x
        assertEquals(false, Sizes.mirrorAlongY(0))
        assertEquals(false, Sizes.mirrorAlongY(1))
        assertEquals(false, Sizes.mirrorAlongY(3)) // 180: still x
        // pixels still turned a quarter by EXIF: upright left-right is the stored y axis
        assertTrue(Sizes.mirrorAlongY(6))
        assertTrue(Sizes.mirrorAlongY(8))
    }

    @Test fun reverseRows() {
        val a = intArrayOf(1, 2, 3, 4, 5, 6, 7)
        Sizes.reverse(a, 0, 3); Sizes.reverse(a, 3, 4)
        assertEquals(listOf(3, 2, 1, 7, 6, 5, 4), a.toList())
        val one = intArrayOf(9); Sizes.reverse(one, 0, 1); assertEquals(9, one[0])
        val big = IntArray(4000) { it }; Sizes.reverse(big, 0, 4000); Sizes.reverse(big, 0, 4000)
        assertEquals((0 until 4000).toList(), big.toList())
    }
}
