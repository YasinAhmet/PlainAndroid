package app.trekmap.trail

/** Small growable primitive lists (no boxing; tracks can have tens of thousands of points). */
class DoubleList(cap: Int = 64) {
    var a = DoubleArray(cap.coerceAtLeast(4))
    var size = 0
        private set

    fun add(v: Double) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun pop() { if (size > 0) size-- }
    fun clear() { size = 0 }
    operator fun get(i: Int) = a[i]
    fun toArray(): DoubleArray = a.copyOf(size)
}

class LongList(cap: Int = 64) {
    var a = LongArray(cap.coerceAtLeast(4))
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(size * 2)
        a[size++] = v
    }

    fun toArray(): LongArray = a.copyOf(size)
}

/** One continuous piece of a track or route. ele/time are optional (NaN / 0 where unknown). */
class Seg(val lat: DoubleArray, val lon: DoubleArray, val ele: DoubleArray? = null, val time: LongArray? = null) {
    val size get() = lat.size
}

class Route(val name: String?, val segs: List<Seg>) {
    val points get() = segs.sumOf { it.size }

    fun lengthM(): Double {
        var d = 0.0
        for (s in segs) for (i in 1 until s.size) d += Geo.dist(s.lat[i - 1], s.lon[i - 1], s.lat[i], s.lon[i])
        return d
    }

    fun bbox(): BBox? {
        var w = 180.0; var so = 90.0; var e = -180.0; var n = -90.0
        var any = false
        for (s in segs) for (i in 0 until s.size) {
            any = true
            w = minOf(w, s.lon[i]); e = maxOf(e, s.lon[i])
            so = minOf(so, s.lat[i]); n = maxOf(n, s.lat[i])
        }
        return if (any) BBox(w, so, e, n) else null
    }

    fun ascentM(threshold: Double = 4.0): Double {
        val c = AscentCounter(threshold)
        for (s in segs) { val e = s.ele ?: continue; for (v in e) c.push(v) }
        return c.total
    }

    fun firstTime(): Long {
        for (s in segs) { val t = s.time ?: continue; for (v in t) if (v > 0) return v }
        return 0L
    }

    fun lines(): List<MLine> = segs.map { MLine.of(it) }
}

/** Growable polyline in normalised Mercator coordinates, ready for drawing. */
class MLine(cap: Int = 64) {
    var xy = DoubleArray(cap.coerceAtLeast(4) * 2)
    var n = 0
        private set

    fun add(lat: Double, lon: Double) {
        if (2 * n + 2 > xy.size) xy = xy.copyOf(xy.size * 2)
        xy[2 * n] = Geo.mx(lon)
        xy[2 * n + 1] = Geo.my(lat)
        n++
    }

    fun pop() { if (n > 0) n-- }

    companion object {
        fun of(s: Seg) = MLine(s.size).also { for (i in 0 until s.size) it.add(s.lat[i], s.lon[i]) }
    }
}
