package app.trekmap.trail

import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingTest {
    // row-major device→world (East, North, Up); columns are the device X, Y, Z axes
    private fun m(x: FloatArray, y: FloatArray, z: FloatArray) = floatArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
    private fun v(e: Float, n: Float, u: Float) = floatArrayOf(e, n, u)

    @Test
    fun flatPhoneTopPointingNorthIsZero() {
        assertEquals(0.0, Heading.fromMatrix(m(v(1f, 0f, 0f), v(0f, 1f, 0f), v(0f, 0f, 1f)), 0), 1e-6)
    }

    @Test
    fun uprightPhoneLookingEastIs90() {
        // screen faces the user (standing west of it), back of the phone points east
        val r = m(v(0f, -1f, 0f), v(0f, 0f, 1f), v(-1f, 0f, 0f))
        assertEquals(90.0, Heading.fromMatrix(r, 0), 1e-6)
    }

    @Test
    fun tiltedPhoneLookingSouthWest() {
        // flat phone with its top pointing south-west, then tilted 45° up
        val s = 0.70710677f
        val back = v(-s * s, -s * s, -s) // -Z direction will be computed by the function; here Z = -back
        val r = m(v(-s, s, 0f), v(-s * s, -s * s, s), v(s * s, s * s, s))
        assertEquals(225.0, Heading.fromMatrix(r, 0), 0.5)
        assertEquals(3, back.size)
    }

    @Test
    fun landscapeUsesTheSideEdge() {
        // flat phone, device +X pointing north, held in landscape (ROTATION_90)
        val r = m(v(0f, 1f, 0f), v(-1f, 0f, 0f), v(0f, 0f, 1f))
        assertEquals(0.0, Heading.fromMatrix(r, 1), 1e-6)
    }

    @Test
    fun cardinalAndDiff() {
        assertEquals("N", Heading.cardinal(359.0))
        assertEquals("NE", Heading.cardinal(40.0))
        assertEquals("SW", Heading.cardinal(225.0))
        assertEquals("W", Heading.cardinal(-90.0))
        assertEquals(-20.0, Heading.diff(350.0, 10.0), 1e-9)
        assertEquals(20.0, Heading.diff(10.0, 350.0), 1e-9)
    }
}
