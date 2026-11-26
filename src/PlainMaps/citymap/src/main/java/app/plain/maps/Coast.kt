package app.plain.maps

import kotlin.math.abs

/**
 * Joins OSM ways into rings, and turns coastline ways into land polygons for a box.
 * Everything here works in lon/lat (y up): OSM coastlines have the land on their left.
 */
object Coast {

    private fun key(x: Double, y: Double): Long = x.toRawBits() * 31 + y.toRawBits()

    /**
     * Joins ways that share end points. [directed] = only head-to-tail joins (coastlines);
     * otherwise ways may be reversed (multipolygon members).
     * Returns closed rings and the chains that stayed open.
     */
    fun join(ways: List<DoubleArray>, directed: Boolean): Pair<List<DoubleArray>, List<DoubleArray>> {
        val rings = ArrayList<DoubleArray>()
        val open = ArrayList<DoubleArray>()
        val ws = ways.filter { it.size >= 4 }
        val used = BooleanArray(ws.size)
        val byStart = HashMap<Long, MutableList<Int>>()
        val byEnd = HashMap<Long, MutableList<Int>>()
        ws.forEachIndexed { i, w ->
            if (Clip.closed(w)) { rings.add(w); used[i] = true; return@forEachIndexed }
            byStart.getOrPut(key(w[0], w[1])) { ArrayList(1) }.add(i)
            byEnd.getOrPut(key(w[w.size - 2], w[w.size - 1])) { ArrayList(1) }.add(i)
        }

        fun take(map: HashMap<Long, MutableList<Int>>, x: Double, y: Double, atStart: Boolean): Int {
            val l = map[key(x, y)] ?: return -1
            for (j in l) {
                if (used[j]) continue
                val w = ws[j]
                val wx = if (atStart) w[0] else w[w.size - 2]
                val wy = if (atStart) w[1] else w[w.size - 1]
                if (wx == x && wy == y) return j
            }
            return -1
        }

        for (i in ws.indices) {
            if (used[i]) continue
            used[i] = true
            val chain = ArrayDeque<Double>()
            for (v in ws[i]) chain.addLast(v)
            // forward
            while (true) {
                val ex = chain[chain.size - 2]; val ey = chain[chain.size - 1]
                if (ex == chain[0] && ey == chain[1] && chain.size >= 8) break
                var j = take(byStart, ex, ey, true)
                var rev = false
                if (j < 0 && !directed) { j = take(byEnd, ex, ey, false); rev = true }
                if (j < 0) break
                used[j] = true
                val w = ws[j]
                val n = w.size / 2
                if (!rev) for (k in 1 until n) { chain.addLast(w[2 * k]); chain.addLast(w[2 * k + 1]) }
                else for (k in n - 2 downTo 0) { chain.addLast(w[2 * k]); chain.addLast(w[2 * k + 1]) }
            }
            // backward
            while (true) {
                val sx = chain[0]; val sy = chain[1]
                if (sx == chain[chain.size - 2] && sy == chain[chain.size - 1] && chain.size >= 8) break
                var j = take(byEnd, sx, sy, false)
                var rev = false
                if (j < 0 && !directed) { j = take(byStart, sx, sy, true); rev = true }
                if (j < 0) break
                used[j] = true
                val w = ws[j]
                val n = w.size / 2
                if (!rev) for (k in n - 2 downTo 0) { chain.addFirst(w[2 * k + 1]); chain.addFirst(w[2 * k]) }
                else for (k in 1 until n) { chain.addFirst(w[2 * k + 1]); chain.addFirst(w[2 * k]) }
            }
            val a = chain.toDoubleArray()
            if (Clip.closed(a)) rings.add(a) else open.add(a)
        }
        return rings to open
    }

    /**
     * Land polygons inside the box w,s,e,n from coastline ways (lon/lat, land on the left).
     * Returns null when the coastline is incomplete (a chain ends inside the box) or when the box
     * holds no coastline at all — the caller then draws everything as land.
     */
    fun land(coast: List<DoubleArray>, w: Double, s: Double, e: Double, n: Double): List<DoubleArray>? {
        val (rings, chains) = join(coast, directed = true)
        val out = ArrayList<DoubleArray>()
        for (r in rings) Clip.clipRing(r, w, s, e, n)?.let { out.add(it) }
        val pieces = ArrayList<DoubleArray>()
        for (c in chains) pieces.addAll(Clip.clipLine(c, w, s, e, n))
        if (pieces.isEmpty()) return if (out.isEmpty()) null else out

        val W = e - w; val H = n - s; val P = 2 * (W + H)
        val eps = 1e-9 * (W + H)
        fun onEdge(x: Double, y: Double) = abs(x - w) < eps || abs(x - e) < eps || abs(y - s) < eps || abs(y - n) < eps
        // position along the perimeter, counter-clockwise from the south-west corner
        fun param(x: Double, y: Double): Double {
            val dS = abs(y - s); val dE = abs(x - e); val dN = abs(y - n); val dW = abs(x - w)
            val m = minOf(dS, dE, dN, dW)
            return when (m) {
                dS -> (x - w).coerceIn(0.0, W)
                dE -> W + (y - s).coerceIn(0.0, H)
                dN -> W + H + (e - x).coerceIn(0.0, W)
                else -> (2 * W + H + (n - y).coerceIn(0.0, H)) % P
            }
        }
        for (p in pieces) {
            if (!onEdge(p[0], p[1]) || !onEdge(p[p.size - 2], p[p.size - 1])) return null
        }
        val startT = DoubleArray(pieces.size) { param(pieces[it][0], pieces[it][1]) }
        val corners = doubleArrayOf(W, W + H, 2 * W + H, 0.0)
        val cornerXY = arrayOf(doubleArrayOf(e, s), doubleArrayOf(e, n), doubleArrayOf(w, n), doubleArrayOf(w, s))
        val visited = BooleanArray(pieces.size)
        for (p0 in pieces.indices) {
            if (visited[p0]) continue
            val ring = DoubleList(256)
            var cur = p0
            var guard = 0
            while (true) {
                if (++guard > pieces.size + 2) return null
                visited[cur] = true
                val pc = pieces[cur]
                var i = 0
                while (i < pc.size) { ring.add(pc[i], pc[i + 1]); i += 2 }
                val exitT = param(pc[pc.size - 2], pc[pc.size - 1])
                // the next coastline entry going counter-clockwise along the box edge
                var best = -1; var bestD = Double.MAX_VALUE
                for (q in pieces.indices) {
                    if (visited[q] && q != p0) continue
                    val d = ((startT[q] - exitT) % P + P) % P
                    if (d < bestD) { bestD = d; best = q }
                }
                if (best < 0) return null
                // box corners passed on the way
                val cs = corners.indices.map { it to ((corners[it] - exitT) % P + P) % P }
                    .filter { it.second > 1e-15 && it.second < bestD }.sortedBy { it.second }
                for ((ci, _) in cs) ring.add(cornerXY[ci][0], cornerXY[ci][1])
                if (best == p0) break
                cur = best
            }
            val a = ring.toArray()
            if (a.size >= 6) out.add(a + doubleArrayOf(a[0], a[1]))
        }
        return out
    }
}
