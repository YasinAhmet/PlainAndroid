package app.trekmap.trail

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Pure heading maths (unit-tested). */
object Heading {
    /**
     * Direction the user faces, degrees from north, from a rotation matrix (row-major 3×3, device → world
     * East/North/Up). Uses the screen's "up" edge plus the back of the phone, so it works whether the
     * phone lies flat, is tilted, or held upright. [displayRotation] is Surface.ROTATION_* (0..3).
     */
    fun fromMatrix(r: FloatArray, displayRotation: Int): Double {
        // device axes in world coordinates are the matrix columns
        val (ue, un) = when (displayRotation) {
            1 -> r[0] to r[3]      // landscape (turned left): screen up = device +X
            2 -> -r[1] to -r[4]    // upside down
            3 -> -r[0] to -r[3]    // landscape (turned right)
            else -> r[1] to r[4]   // portrait: screen up = device +Y
        }
        val e = ue - r[2]          // + back of the phone (−Z)
        val n = un - r[5]
        return norm(Math.toDegrees(atan2(e.toDouble(), n.toDouble())))
    }

    fun norm(d: Double) = ((d % 360) + 360) % 360

    private val NAMES = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun cardinal(d: Double) = NAMES[(((norm(d) + 22.5) / 45).toInt()) % 8]

    /** Smallest signed difference a−b in degrees (−180..180). */
    fun diff(a: Double, b: Double): Double = ((a - b + 540) % 360) - 180
}

/**
 * Compass for the Trail screen. Prefers the geomagnetic rotation vector (accelerometer + magnetometer,
 * no gyroscope: the cheapest option); falls back to the full rotation vector. Runs only while started —
 * the Trail screen starts it only while it is visible and compass mode or statistics are on.
 */
class Compass(ctx: Context, private val displayRotation: () -> Int, private val onHeading: (Double) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    var running = false
        private set
    /** Magnetic → true north correction for the current location. */
    var declination = 0.0
    /** SensorManager.SENSOR_STATUS_* of the magnetometer; low = needs a figure-8 calibration. */
    var accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        private set
    var heading = Double.NaN
        private set

    private val rm = FloatArray(9)
    private var sx = 0.0
    private var sy = 0.0
    private var primed = false
    private var lastSent = Double.NaN
    private var lastSentAt = 0L

    fun start(): Boolean {
        val s = sensor ?: return false
        if (!running) running = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
        return running
    }

    fun stop() {
        if (running) sm.unregisterListener(this)
        running = false
        primed = false
    }

    fun setLocation(lat: Double, lon: Double) {
        declination = GeomagneticField(lat.toFloat(), lon.toFloat(), 0f, System.currentTimeMillis()).declination.toDouble()
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = if (e.values.size > 4) e.values.copyOf(4) else e.values
        try { SensorManager.getRotationMatrixFromVector(rm, v) } catch (_: Exception) { return }
        val h = Math.toRadians(Heading.fromMatrix(rm, displayRotation()) + declination)
        // smooth on the unit circle (no jump at 359°→0°)
        if (!primed) { sx = cos(h); sy = sin(h); primed = true } else { sx = sx * 0.82 + cos(h) * 0.18; sy = sy * 0.82 + sin(h) * 0.18 }
        heading = Heading.norm(Math.toDegrees(atan2(sy, sx)))
        val now = System.currentTimeMillis()
        // only redraw for a visible change, at most ~20×/s
        if (lastSent.isNaN() || (kotlin.math.abs(Heading.diff(heading, lastSent)) >= 1.0 && now - lastSentAt >= 50)) {
            lastSent = heading
            lastSentAt = now
            onHeading(heading)
        }
    }

    override fun onAccuracyChanged(s: Sensor?, acc: Int) { accuracy = acc }
}
