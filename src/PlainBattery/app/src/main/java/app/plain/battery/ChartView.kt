package app.plain.battery

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import app.plain.lang.tr
import java.util.Calendar

/**
 * Battery level from the first sample (at most [maxSpanMs] back) to now, with screen-on time
 * as a thin strip along the bottom and time on power in green. Only the range that has data is drawn.
 */
class ChartView(context: Context) : View(context) {
    var samples: List<Sample> = emptyList()
    var screen: List<Pair<Long, Boolean>> = emptyList()
    var maxSpanMs: Long = 36 * 3_600_000L

    private val dp = resources.displayMetrics.density
    private val grid = Paint().apply { color = 0xFF1C1C1C.toInt(); strokeWidth = dp }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF747474.toInt(); textSize = 10 * dp }
    private val screenPaint = Paint().apply { color = 0xFFE6B422.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE4E4E4.toInt(); strokeWidth = 2 * dp; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val charge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6CBF6C.toInt(); strokeWidth = 3 * dp; style = Paint.Style.STROKE
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (170 * dp).toInt())
    }

    override fun onDraw(c: Canvas) {
        val left = 28 * dp; val right = width - 6 * dp
        val top = 6 * dp; val bottom = height - 16 * dp
        val end = System.currentTimeMillis()
        val first = samples.firstOrNull { it.time >= end - maxSpanMs && it.level >= 0 }?.time ?: (end - 3_600_000L)
        val spanMs = (end - first).coerceAtLeast(30 * 60_000L)
        val start = end - spanMs
        fun x(t: Long) = left + (right - left) * ((t - start).toFloat() / spanMs)
        fun y(level: Int) = bottom - (bottom - top) * level / 100f

        // screen-on shading
        var onAt: Long? = null
        for ((t, on) in screen) {
            if (on) { if (onAt == null) onAt = t }
            else if (onAt != null) {
                if (t > start) c.drawRect(x(maxOf(onAt, start)), bottom - 4 * dp, x(t), bottom, screenPaint)
                onAt = null
            }
        }
        onAt?.let { c.drawRect(x(maxOf(it, start)), bottom - 4 * dp, right, bottom, screenPaint) }

        for (lv in 0..100 step 25) {
            c.drawLine(left, y(lv), right, y(lv), grid)
            c.drawText("$lv", 2 * dp, y(lv) + 4 * dp, text)
        }
        // time ticks: pick a step that gives at most ~6 labels
        val stepMin = listOf(15, 30, 60, 120, 180, 240, 360).first { spanMs / 60_000L / it <= 6 }
        val cal = Calendar.getInstance().apply {
            timeInMillis = start; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        while (cal.timeInMillis < end) {
            cal.add(Calendar.MINUTE, 15)
            val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (minuteOfDay % stepMin == 0 && cal.timeInMillis in start..end) {
                val xx = x(cal.timeInMillis)
                c.drawLine(xx, top, xx, bottom, grid)
                val label = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
                c.drawText(label, (xx - 14 * dp).coerceIn(left, right - 28 * dp), height - 3 * dp, text)
            }
        }

        val pts = samples.filter { it.time >= start && it.level >= 0 }
        if (pts.size < 2) {
            c.drawText(tr("Collecting… one sample every ~15 min", "Toplanıyor… ~15 dk'da bir ölçüm"), left + 8 * dp, (top + bottom) / 2, text)
            return
        }
        val path = Path()
        pts.forEachIndexed { i, s -> if (i == 0) path.moveTo(x(s.time), y(s.level)) else path.lineTo(x(s.time), y(s.level)) }
        c.save(); c.clipRect(left, 0f, right, height.toFloat())
        c.drawPath(path, line)
        for (i in 1 until pts.size) {
            val a = pts[i - 1]; val b = pts[i]
            if (a.charging && b.charging) c.drawLine(x(a.time), y(a.level), x(b.time), y(b.level), charge)
        }
        c.restore()
    }
}
