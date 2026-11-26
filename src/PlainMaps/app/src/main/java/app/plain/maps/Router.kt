package app.plain.maps

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * The transit network as the route finder sees it: lines (variants) and the stops they touch.
 * Two lines "contact" where one of their stops lies within [TRANSFER] metres of the other's.
 * No walking or street routing: only the lines and where they meet.
 */
class Network(
    val stopId: IntArray,
    val stopLat: DoubleArray,
    val stopLon: DoubleArray,
    val varId: IntArray,
    val varLine: IntArray,
    /** stop indices (into stopId) of each variant, in riding order */
    val varStops: Array<IntArray>,
    /** metres from the first stop, per stop of each variant */
    val varDists: Array<IntArray>,
) {
    companion object {
        const val TRANSFER = 250.0
    }

    /** For every stop: pairs (variant index, position in it), flattened. */
    val serves: Array<IntArray>
    /** For every stop: the stops within [TRANSFER] metres (itself included) and their distances. */
    val near: Array<IntArray>
    val nearD: Array<IntArray>
    private val index = HashMap<Int, Int>()
    private val cellOf = HashMap<Long, MutableList<Int>>()
    private val kLat = 111_320.0
    private val kLon: Double

    init {
        stopId.forEachIndexed { i, id -> index[id] = i }
        val lists = Array(stopId.size) { IntList(4) }
        for (v in varStops.indices) for ((pos, s) in varStops[v].withIndex()) { lists[s].add(v); lists[s].add(pos) }
        serves = Array(stopId.size) { lists[it].toArray() }
        kLon = 111_320.0 * cos(Math.toRadians(if (stopLat.isEmpty()) 41.0 else stopLat.average()))
        for (i in stopId.indices) cellOf.getOrPut(cell(stopLat[i], stopLon[i])) { ArrayList() }.add(i)
        val nb = Array(stopId.size) { IntList(8) }
        val nd = Array(stopId.size) { IntList(8) }
        for (i in stopId.indices) {
            within(stopLat[i], stopLon[i], TRANSFER) { j, d -> nb[i].add(j); nd[i].add(d.toInt()) }
        }
        near = Array(stopId.size) { nb[it].toArray() }
        nearD = Array(stopId.size) { nd[it].toArray() }
    }

    fun indexOf(stopDbId: Int) = index[stopDbId] ?: -1

    private fun cell(lat: Double, lon: Double): Long {
        val cy = Math.floor(lat * kLat / TRANSFER).toLong()
        val cx = Math.floor(lon * kLon / TRANSFER).toLong()
        return (cy shl 32) xor (cx and 0xFFFFFFFFL)
    }

    fun dist(i: Int, lat: Double, lon: Double): Double {
        val dy = (stopLat[i] - lat) * kLat
        val dx = (stopLon[i] - lon) * kLon
        return sqrt(dx * dx + dy * dy)
    }

    /** Every stop within r metres of a point. */
    fun within(lat: Double, lon: Double, r: Double, cb: (Int, Double) -> Unit) {
        val cy = Math.floor(lat * kLat / TRANSFER).toLong()
        val cx = Math.floor(lon * kLon / TRANSFER).toLong()
        val k = Math.ceil(r / TRANSFER).toLong()
        for (dy in -k..k) for (dx in -k..k) {
            val l = cellOf[((cy + dy) shl 32) xor ((cx + dx) and 0xFFFFFFFFL)] ?: continue
            for (j in l) { val d = dist(j, lat, lon); if (d <= r) cb(j, d) }
        }
    }
}

/**
 * Finds journeys from A to B by line contacts: fewest changes first, then the shortest ride,
 * ending at a stop close to B. Works in rounds (0 changes, 1, 2 …); in each round every line
 * reachable so far is ridden to every later stop, and changes are allowed between stops that are
 * within [Network.TRANSFER] metres of each other.
 */
object Router {
    class Leg(val v: Int, val from: Int, val to: Int)

    class Journey(val legs: List<Leg>, val startDist: Int, val endDist: Int, val rideMeters: Int, val walkMeters: Int) {
        val changes get() = legs.size - 1
        val score get() = rideMeters + 4 * walkMeters
    }

    private class Label(val cost: Int, val ride: Int, val walk: Int, val start: Int, val prev: Label?, val leg: Leg?, val line: Int)

    /** Up to [k] journeys with different line sequences, best first. */
    fun find(
        net: Network, aLat: Double, aLon: Double, bLat: Double, bLon: Double,
        rA: Double = 500.0, rB: Double = 500.0, maxChanges: Int = 2, k: Int = 3, perStop: Int = 4,
    ): List<Journey> {
        val n = net.stopId.size
        // boardable labels for this round
        var board = arrayOfNulls<MutableList<Label>>(n)
        net.within(aLat, aLon, rA) { s, d ->
            val l = Label(4 * d.toInt(), 0, d.toInt(), d.toInt(), null, null, -1)
            board[s] = mutableListOf(l)
        }
        val goal = HashMap<Int, Int>()
        net.within(bLat, bLon, rB) { s, d -> goal[s] = d.toInt() }
        if (goal.isEmpty() || board.all { it == null }) return emptyList()

        val found = ArrayList<Journey>()
        var firstRound = -1
        for (round in 0..maxChanges) {
            if (firstRound >= 0 && round > firstRound + 1) break
            // ride: for each variant, the best way onto it, then every later stop
            val bestOn = HashMap<Int, Pair<Label, Int>>() // variant → (label, boarding position)
            val onCost = HashMap<Int, Int>()
            for (s in 0 until n) {
                val ls = board[s] ?: continue
                val sv = net.serves[s]
                var i = 0
                while (i < sv.size) {
                    val v = sv[i]; val pos = sv[i + 1]
                    i += 2
                    val stops = net.varStops[v]
                    if (pos >= stops.size - 1) continue
                    val line = net.varLine[v]
                    for (l in ls) {
                        if (l.line == line) continue // no change onto the same line
                        val c = l.cost - net.varDists[v][pos]
                        val cur = onCost[v]
                        if (cur == null || c < cur) { onCost[v] = c; bestOn[v] = l to pos }
                    }
                }
            }
            val arrive = arrayOfNulls<MutableList<Label>>(n)
            for ((v, lp) in bestOn) {
                val (l, pos) = lp
                val stops = net.varStops[v]; val dists = net.varDists[v]; val line = net.varLine[v]
                for (j in pos + 1 until stops.size) {
                    val ride = dists[j] - dists[pos]
                    val lab = Label(l.cost + ride, l.ride + ride, l.walk, l.start, l, Leg(v, pos, j), line)
                    keep(arrive, stops[j], lab, perStop)
                }
            }
            // journeys ending near B
            for ((s, d) in goal) {
                val ls = arrive[s] ?: continue
                for (l in ls) {
                    val legs = ArrayList<Leg>()
                    var x: Label? = l
                    while (x?.leg != null) { legs.add(0, x.leg!!); x = x.prev }
                    found.add(Journey(legs, l.start, d, l.ride, l.walk + d))
                    if (firstRound < 0) firstRound = round
                }
            }
            if (round == maxChanges) break
            // change: from each arrival to the stops around it
            val next = arrayOfNulls<MutableList<Label>>(n)
            for (s in 0 until n) {
                val ls = arrive[s] ?: continue
                val nb = net.near[s]; val nd = net.nearD[s]
                for (q in nb.indices) {
                    for (l in ls) {
                        val walk = nd[q]
                        keep(next, nb[q], Label(l.cost + 4 * walk + 300, l.ride, l.walk + walk, l.start, l.prev, l.leg, l.line), perStop)
                    }
                }
            }
            board = next
        }
        // best per line sequence, fewest changes first
        val seen = HashSet<String>()
        return found.sortedWith(compareBy({ it.changes }, { it.score }))
            .filter { j -> seen.add(j.legs.joinToString(">") { net.varLine[it.v].toString() }) }
            .take(k)
    }

    /** Keeps up to [max] labels per stop, at most one per line (the cheapest). */
    private fun keep(at: Array<MutableList<Label>?>, s: Int, lab: Label, max: Int) {
        val l = at[s] ?: ArrayList<Label>(2).also { at[s] = it }
        val same = l.indexOfFirst { it.line == lab.line }
        if (same >= 0) {
            if (l[same].cost <= lab.cost) return
            l.removeAt(same)
        }
        if (l.size >= max) {
            val worst = l.indices.maxBy { l[it].cost }
            if (l[worst].cost <= lab.cost) return
            l.removeAt(worst)
        }
        l.add(lab)
    }
}
