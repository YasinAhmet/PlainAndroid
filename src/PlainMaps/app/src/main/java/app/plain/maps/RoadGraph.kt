package app.plain.maps

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The drivable streets as a graph, for drawing bus lines that have no route shape along the real
 * roads (shortest path between two stops) and for putting stops without a position onto them.
 * Ways meet where they share a point (OSM ways share their nodes, so the coordinates are equal).
 * One-way rules are ignored: good enough to follow the streets a bus uses.
 */
class RoadGraph(ways: List<DoubleArray>) {
    private val lon: DoubleArray
    private val lat: DoubleArray
    private val off: IntArray
    private val to: IntArray
    private val w: FloatArray
    private val kLon: Double
    private val cells = HashMap<Long, IntArray>()
    val nodes get() = lon.size

    companion object {
        private const val CELL = 150.0 // metres, for the nearest-node search
        private const val KLAT = 111_320.0
    }

    init {
        val id = HashMap<Long, Int>(ways.sumOf { it.size / 2 } * 2)
        val xs = DoubleList(1 shl 16)
        val ea = IntList(1 shl 16); val eb = IntList(1 shl 16)
        fun node(x: Double, y: Double): Int {
            val k = (Math.round(x * 1e7) shl 32) xor (Math.round(y * 1e7) and 0xFFFFFFFFL)
            return id.getOrPut(k) { xs.add(x, y); xs.size / 2 - 1 }
        }
        for (g in ways) {
            var prev = -1
            var i = 0
            while (i < g.size) {
                val n = node(g[i], g[i + 1])
                if (prev >= 0 && prev != n) { ea.add(prev); eb.add(n) }
                prev = n
                i += 2
            }
        }
        val all = xs.toArray()
        val n = all.size / 2
        lon = DoubleArray(n) { all[2 * it] }
        lat = DoubleArray(n) { all[2 * it + 1] }
        kLon = KLAT * cos(Math.toRadians(if (n > 0) lat.average() else 40.0))
        // adjacency in compressed rows, both directions
        val deg = IntArray(n + 1)
        for (e in 0 until ea.size) { deg[ea[e]]++; deg[eb[e]]++ }
        off = IntArray(n + 1)
        for (i in 0 until n) off[i + 1] = off[i] + deg[i]
        to = IntArray(off[n]); w = FloatArray(off[n])
        val fill = off.copyOf()
        for (e in 0 until ea.size) {
            val a = ea[e]; val b = eb[e]
            val d = dist(a, b).toFloat()
            to[fill[a]] = b; w[fill[a]++] = d
            to[fill[b]] = a; w[fill[b]++] = d
        }
        val tmp = HashMap<Long, IntList>()
        for (i in 0 until n) tmp.getOrPut(cell(lat[i], lon[i])) { IntList(4) }.add(i)
        for ((k, v) in tmp) cells[k] = v.toArray()
    }

    private fun cell(la: Double, lo: Double): Long {
        val cy = floor(la * KLAT / CELL).toLong(); val cx = floor(lo * kLon / CELL).toLong()
        return (cy shl 32) xor (cx and 0xFFFFFFFFL)
    }

    private fun dist(a: Int, b: Int): Double {
        val dy = (lat[a] - lat[b]) * KLAT; val dx = (lon[a] - lon[b]) * kLon
        return sqrt(dx * dx + dy * dy)
    }

    private fun dist(a: Int, la: Double, lo: Double): Double {
        val dy = (lat[a] - la) * KLAT; val dx = (lon[a] - lo) * kLon
        return sqrt(dx * dx + dy * dy)
    }

    /** The road node nearest to a point within [max] metres, or -1. */
    fun nearest(la: Double, lo: Double, max: Double = 300.0): Int {
        val cy = floor(la * KLAT / CELL).toLong(); val cx = floor(lo * kLon / CELL).toLong()
        val r = kotlin.math.ceil(max / CELL).toLong()
        var best = -1; var bd = max
        for (dy in -r..r) for (dx in -r..r) {
            val l = cells[((cy + dy) shl 32) xor ((cx + dx) and 0xFFFFFFFFL)] ?: continue
            for (i in l) { val d = dist(i, la, lo); if (d < bd) { bd = d; best = i } }
        }
        return best
    }

    // A* state, reused between searches (stamps instead of clearing)
    private var stamp = 0
    private val seen by lazy { IntArray(lon.size) }
    private val g by lazy { FloatArray(lon.size) }
    private val from by lazy { IntArray(lon.size) }
    private var heapN = IntArray(1024)
    private var heapF = FloatArray(1024)
    private var hs = 0

    private fun push(n: Int, f: Float) {
        if (hs == heapN.size) { heapN = heapN.copyOf(hs * 2); heapF = heapF.copyOf(hs * 2) }
        var i = hs++
        while (i > 0) {
            val p = (i - 1) / 2
            if (heapF[p] <= f) break
            heapN[i] = heapN[p]; heapF[i] = heapF[p]; i = p
        }
        heapN[i] = n; heapF[i] = f
    }

    private fun pop(): Int {
        val top = heapN[0]
        val ln = heapN[--hs]; val lf = heapF[hs]
        var i = 0
        while (true) {
            var c = 2 * i + 1
            if (c >= hs) break
            if (c + 1 < hs && heapF[c + 1] < heapF[c]) c++
            if (heapF[c] >= lf) break
            heapN[i] = heapN[c]; heapF[i] = heapF[c]; i = c
        }
        if (hs > 0) { heapN[i] = ln; heapF[i] = lf }
        return top
    }

    /** Shortest path between two nodes as lon/lat pairs, or null if longer than [maxLen] metres or not connected. */
    fun path(a: Int, b: Int, maxLen: Double): DoubleArray? {
        if (a < 0 || b < 0) return null
        if (a == b) return doubleArrayOf(lon[a], lat[a])
        stamp++
        hs = 0
        seen[a] = stamp; g[a] = 0f; from[a] = -1
        push(a, dist(a, b).toFloat())
        var steps = 0
        while (hs > 0) {
            val u = pop()
            if (u == b) break
            if (++steps > 200_000) return null
            val gu = g[u]
            if (gu + dist(u, b) > maxLen) continue
            for (e in off[u] until off[u + 1]) {
                val v = to[e]
                val nv = gu + w[e]
                if (seen[v] != stamp || nv < g[v]) {
                    seen[v] = stamp; g[v] = nv; from[v] = u
                    push(v, nv + dist(v, b).toFloat())
                }
            }
        }
        if (seen[b] != stamp) return null
        val out = IntList(64)
        var x = b
        while (x >= 0) { out.add(x); x = from[x]; if (out.size > 100_000) return null }
        val r = DoubleArray(out.size * 2)
        for (i in 0 until out.size) { val n = out[out.size - 1 - i]; r[2 * i] = lon[n]; r[2 * i + 1] = lat[n] }
        return r
    }
}

/** Helpers on lon/lat polylines. */
object Poly {
    fun length(p: DoubleArray): Double {
        var s = 0.0
        for (i in 1 until p.size / 2) s += Geo.dist(p[2 * i - 1], p[2 * i - 2], p[2 * i + 1], p[2 * i])
        return s
    }

    /** The point at fraction [f] of the polyline's length (lat, lon). */
    fun at(p: DoubleArray, f: Double): DoubleArray {
        val n = p.size / 2
        if (n == 1) return doubleArrayOf(p[1], p[0])
        val total = length(p)
        var want = total * f.coerceIn(0.0, 1.0)
        for (i in 1 until n) {
            val d = Geo.dist(p[2 * i - 1], p[2 * i - 2], p[2 * i + 1], p[2 * i])
            if (want <= d || i == n - 1) {
                val t = if (d > 0) (want / d).coerceIn(0.0, 1.0) else 0.0
                return doubleArrayOf(p[2 * i - 1] + (p[2 * i + 1] - p[2 * i - 1]) * t, p[2 * i - 2] + (p[2 * i] - p[2 * i - 2]) * t)
            }
            want -= d
        }
        return doubleArrayOf(p[p.size - 1], p[p.size - 2])
    }

    /** Index of the vertex nearest to (lat, lon), searching from [from]. */
    fun nearestVertex(p: DoubleArray, lat: Double, lon: Double, from: Int = 0): Int {
        var best = from; var bd = Double.MAX_VALUE
        for (i in from until p.size / 2) {
            val dx = (p[2 * i] - lon) * 0.76; val dy = p[2 * i + 1] - lat
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }
}
