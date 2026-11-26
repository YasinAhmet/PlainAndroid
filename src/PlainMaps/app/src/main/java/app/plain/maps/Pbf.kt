package app.plain.maps

import app.plain.lang.tr
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.util.zip.Inflater

/** Minimal protobuf reader over a byte range. */
class Pb(@JvmField val b: ByteArray, @JvmField var pos: Int, @JvmField val end: Int) {
    @JvmField var field = 0
    @JvmField var wire = 0

    fun next(): Boolean {
        if (pos >= end) return false
        val k = varint()
        field = (k ushr 3).toInt()
        wire = (k and 7).toInt()
        return true
    }

    fun varint(): Long {
        var r = 0L
        var s = 0
        while (true) {
            val x = b[pos++].toInt()
            r = r or ((x and 0x7F).toLong() shl s)
            if (x >= 0) return r
            s += 7
        }
    }

    fun sint(): Long { val v = varint(); return (v ushr 1) xor -(v and 1) }

    /** Length of a length-delimited field; pos is left at its first byte. */
    fun len(): Int = varint().toInt()

    fun sub(): Pb { val n = len(); val p = Pb(b, pos, pos + n); pos += n; return p }

    fun str(): String { val n = len(); val s = String(b, pos, n, Charsets.UTF_8); pos += n; return s }

    fun skip() {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val n = len(); pos += n }
            5 -> pos += 4
            else -> throw IOException("PBF: unknown wire type $wire")
        }
    }
}

/**
 * Reads an OpenStreetMap .osm.pbf file without loading it: three streaming passes
 * (relations, then the ways that are needed, then only the nodes those use).
 * Elements come out as [OsmEl], like an Overpass answer, so the same code builds the map.
 */
class PbfExtract(private val file: File) {
    /** Which elements to keep (on tags; tags arrive as a flat key/value array). */
    var wantRelation: (Map<String, String>) -> Boolean = { false }
    var wantWay: (Map<String, String>) -> Boolean = { false }
    var wantNode: (Map<String, String>) -> Boolean = { false }
    var cancelled: () -> Boolean = { false }
    var progress: (String) -> Unit = {}

    private class Rel(val id: Long, val tags: Array<String>, val types: ByteArray, val ids: LongArray, val roles: Array<String>)
    private class Way(val id: Long, val tags: Array<String>, val refs: LongArray)

    private val rels = ArrayList<Rel>()
    private val ways = HashMap<Long, Way>()
    private var relWays = LongArray(0)
    private var relNodes = LongArray(0)
    private var needNodes = LongArray(0)
    private var nodeLat = IntArray(0)
    private var nodeLon = IntArray(0)
    private val relNodeTags = HashMap<Long, OsmEl>()
    private val strings = HashMap<String, String>()

    private fun intern(s: String): String = strings.getOrPut(s) { s }

    // ------------------------------------------------------------------ blocks
    private class Block(val strings: Array<String>, val gran: Long, val latOff: Long, val lonOff: Long, val groups: List<Pb>)

    private fun blocks(cb: (Block) -> Unit) {
        val inf = Inflater()
        DataInputStream(file.inputStream().buffered(1 shl 16)).use { inp ->
            var hdrBuf = ByteArray(256)
            var blobBuf = ByteArray(1 shl 16)
            var rawBuf = ByteArray(1 shl 20)
            var n = 0
            while (true) {
                val hl = try { inp.readInt() } catch (_: EOFException) { break }
                if (hl > hdrBuf.size) hdrBuf = ByteArray(hl)
                inp.readFully(hdrBuf, 0, hl)
                var type = ""; var size = 0
                val h = Pb(hdrBuf, 0, hl)
                while (h.next()) when (h.field) {
                    1 -> type = h.str()
                    3 -> size = h.varint().toInt()
                    else -> h.skip()
                }
                if (size > blobBuf.size) blobBuf = ByteArray(size)
                inp.readFully(blobBuf, 0, size)
                if (type == "OSMHeader" && bbox == null) { header(blobBuf, size, inf); continue }
                if (type != "OSMData") continue
                // blob: raw or zlib
                var rawSize = 0; var zStart = -1; var zLen = 0; var rawStart = -1; var rawLen = 0
                val bl = Pb(blobBuf, 0, size)
                while (bl.next()) when (bl.field) {
                    1 -> { rawLen = bl.len(); rawStart = bl.pos; bl.pos += rawLen }
                    2 -> rawSize = bl.varint().toInt()
                    3 -> { zLen = bl.len(); zStart = bl.pos; bl.pos += zLen }
                    else -> bl.skip()
                }
                val data: ByteArray
                val dlen: Int
                if (rawStart >= 0) { data = blobBuf.copyOfRange(rawStart, rawStart + rawLen); dlen = rawLen }
                else if (zStart >= 0) {
                    if (rawSize > rawBuf.size) rawBuf = ByteArray(rawSize)
                    inf.reset()
                    inf.setInput(blobBuf, zStart, zLen)
                    var got = 0
                    while (got < rawSize) {
                        val k = inf.inflate(rawBuf, got, rawSize - got)
                        if (k == 0 && (inf.finished() || inf.needsInput())) break
                        got += k
                    }
                    data = rawBuf; dlen = got
                } else throw IOException("PBF: unsupported compression")
                cb(block(data, dlen))
                if (++n % 50 == 0 && cancelled()) throw InterruptedException()
            }
        }
        inf.end()
    }

    /** The extract's bounding box (w, s, e, n), from the file header. */
    var bbox: BBox? = null
        private set

    private fun header(blob: ByteArray, size: Int, inf: Inflater) {
        var rawSize = 0; var zStart = -1; var zLen = 0; var rawStart = -1; var rawLen = 0
        val bl = Pb(blob, 0, size)
        while (bl.next()) when (bl.field) {
            1 -> { rawLen = bl.len(); rawStart = bl.pos; bl.pos += rawLen }
            2 -> rawSize = bl.varint().toInt()
            3 -> { zLen = bl.len(); zStart = bl.pos; bl.pos += zLen }
            else -> bl.skip()
        }
        val data: ByteArray
        if (rawStart >= 0) data = blob.copyOfRange(rawStart, rawStart + rawLen)
        else {
            data = ByteArray(rawSize)
            inf.reset(); inf.setInput(blob, zStart, zLen); inf.inflate(data)
        }
        val h = Pb(data, 0, data.size)
        while (h.next()) {
            if (h.field != 1) { h.skip(); continue }
            val b = h.sub()
            var l = 0L; var r = 0L; var t = 0L; var bo = 0L
            while (b.next()) when (b.field) { 1 -> l = b.sint(); 2 -> r = b.sint(); 3 -> t = b.sint(); 4 -> bo = b.sint(); else -> b.skip() }
            bbox = BBox(l * 1e-9, bo * 1e-9, r * 1e-9, t * 1e-9)
        }
    }

    private fun block(data: ByteArray, len: Int): Block {
        val p = Pb(data, 0, len)
        var st: Array<String> = emptyArray()
        var gran = 100L; var latOff = 0L; var lonOff = 0L
        val groups = ArrayList<Pb>()
        while (p.next()) when (p.field) {
            1 -> {
                val s = p.sub()
                val l = ArrayList<String>()
                while (s.next()) if (s.field == 1) l.add(s.str()) else s.skip()
                st = l.toTypedArray()
            }
            2 -> groups.add(p.sub())
            17 -> gran = p.varint()
            19 -> latOff = p.varint()
            20 -> lonOff = p.varint()
            else -> p.skip()
        }
        return Block(st, gran, latOff, lonOff, groups)
    }

    private fun tagMap(t: Array<String>): Map<String, String> {
        val m = HashMap<String, String>(t.size)
        var i = 0
        while (i < t.size) { m[t[i]] = t[i + 1]; i += 2 }
        return m
    }

    private fun keysVals(p: Pb, keys: IntList, vals: IntList) {
        when (p.field) {
            2 -> { val n = p.len(); val e = p.pos + n; while (p.pos < e) keys.add(p.varint().toInt()) }
            3 -> { val n = p.len(); val e = p.pos + n; while (p.pos < e) vals.add(p.varint().toInt()) }
        }
    }

    // ------------------------------------------------------------------ passes
    fun run(onNode: (OsmEl) -> Unit, onWay: (OsmEl) -> Unit, onRelation: (OsmEl) -> Unit) {
        passRelations()
        progress(tr("${rels.size} relations", "${rels.size} ilişki"))
        passWays()
        progress(tr("${ways.size} ways", "${ways.size} yol"))
        passNodes(onNode)
        progress(tr("${needNodes.size} way nodes", "${needNodes.size} yol noktası"))
        emitWays(onWay)
        emitRelations(onRelation)
    }

    private fun passRelations() {
        val wayIds = LongList(); val nodeIds = LongList()
        val keys = IntList(); val vals = IntList()
        blocks { b ->
            for (g in b.groups) {
                val gp = Pb(g.b, g.pos, g.end)
                while (gp.next()) {
                    if (gp.field != 4) { gp.skip(); continue }
                    val r = gp.sub()
                    var id = 0L
                    keys.clear(); vals.clear()
                    var rolesS = -1; var rolesE = -1; var idsS = -1; var idsE = -1; var tyS = -1; var tyE = -1
                    while (r.next()) when (r.field) {
                        1 -> id = r.varint()
                        2, 3 -> keysVals(r, keys, vals)
                        8 -> { val n = r.len(); rolesS = r.pos; rolesE = r.pos + n; r.pos += n }
                        9 -> { val n = r.len(); idsS = r.pos; idsE = r.pos + n; r.pos += n }
                        10 -> { val n = r.len(); tyS = r.pos; tyE = r.pos + n; r.pos += n }
                        else -> r.skip()
                    }
                    val tags = Array(keys.size * 2) { i -> if (i % 2 == 0) b.strings[keys[i / 2]] else b.strings[vals[i / 2]] }
                    if (!wantRelation(tagMap(tags))) continue
                    val roles = ArrayList<String>(); val ids = LongList(); val types = ArrayList<Byte>()
                    if (rolesS >= 0) { val q = Pb(r.b, rolesS, rolesE); while (q.pos < rolesE) roles.add(intern(b.strings[q.varint().toInt()])) }
                    if (idsS >= 0) { val q = Pb(r.b, idsS, idsE); var acc = 0L; while (q.pos < idsE) { acc += q.sint(); ids.add(acc) } }
                    if (tyS >= 0) { val q = Pb(r.b, tyS, tyE); while (q.pos < tyE) types.add(q.varint().toByte()) }
                    val n = minOf(ids.size, types.size, roles.size)
                    val rel = Rel(id, tags.map { intern(it) }.toTypedArray(), ByteArray(n) { types[it] }, LongArray(n) { ids[it] }, Array(n) { roles[it] })
                    rels.add(rel)
                    for (i in 0 until n) when (rel.types[i].toInt()) { 0 -> nodeIds.add(rel.ids[i]); 1 -> wayIds.add(rel.ids[i]) }
                }
            }
        }
        relWays = wayIds.sortedUnique()
        relNodes = nodeIds.sortedUnique()
    }

    private fun passWays() {
        val nodeIds = LongList()
        val keys = IntList(); val vals = IntList()
        blocks { b ->
            for (g in b.groups) {
                val gp = Pb(g.b, g.pos, g.end)
                while (gp.next()) {
                    if (gp.field != 3) { gp.skip(); continue }
                    val w = gp.sub()
                    var id = 0L
                    keys.clear(); vals.clear()
                    var refS = -1; var refE = -1
                    while (w.next()) when (w.field) {
                        1 -> id = w.varint()
                        2, 3 -> keysVals(w, keys, vals)
                        8 -> { val n = w.len(); refS = w.pos; refE = w.pos + n; w.pos += n }
                        else -> w.skip()
                    }
                    val tags = Array(keys.size * 2) { i -> if (i % 2 == 0) b.strings[keys[i / 2]] else b.strings[vals[i / 2]] }
                    val member = relWays.binarySearch(id) >= 0
                    if (!member && !wantWay(tagMap(tags))) continue
                    val refs = LongList()
                    if (refS >= 0) { val q = Pb(w.b, refS, refE); var acc = 0L; while (q.pos < refE) { acc += q.sint(); refs.add(acc) } }
                    val ra = refs.toArray()
                    for (r in ra) nodeIds.add(r)
                    ways[id] = Way(id, tags.map { intern(it) }.toTypedArray(), ra)
                }
            }
        }
        for (r in relNodes) nodeIds.add(r)
        needNodes = nodeIds.sortedUnique()
        nodeLat = IntArray(needNodes.size) { Int.MIN_VALUE }
        nodeLon = IntArray(needNodes.size)
    }

    private fun passNodes(onNode: (OsmEl) -> Unit) {
        blocks { b ->
            for (g in b.groups) {
                val gp = Pb(g.b, g.pos, g.end)
                while (gp.next()) {
                    when (gp.field) {
                        2 -> dense(gp.sub(), b, onNode)
                        1 -> single(gp.sub(), b, onNode)
                        else -> gp.skip()
                    }
                }
            }
        }
    }

    private fun node(id: Long, lat: Double, lon: Double, tags: Array<String>?, onNode: (OsmEl) -> Unit) {
        val i = needNodes.binarySearch(id)
        if (i >= 0) { nodeLat[i] = Math.round(lat * 1e7).toInt(); nodeLon[i] = Math.round(lon * 1e7).toInt() }
        if (tags == null || tags.isEmpty()) return
        val m = tagMap(tags)
        val isMember = relNodes.binarySearch(id) >= 0
        if (isMember) relNodeTags[id] = OsmEl('n', id, m, lat, lon, null, null)
        if (wantNode(m)) onNode(OsmEl('n', id, m, lat, lon, null, null))
    }

    private fun dense(d: Pb, b: Block, onNode: (OsmEl) -> Unit) {
        var idS = -1; var idE = -1; var laS = -1; var laE = -1; var loS = -1; var loE = -1; var kvS = -1; var kvE = -1
        while (d.next()) when (d.field) {
            1 -> { val n = d.len(); idS = d.pos; idE = d.pos + n; d.pos += n }
            8 -> { val n = d.len(); laS = d.pos; laE = d.pos + n; d.pos += n }
            9 -> { val n = d.len(); loS = d.pos; loE = d.pos + n; d.pos += n }
            10 -> { val n = d.len(); kvS = d.pos; kvE = d.pos + n; d.pos += n }
            else -> d.skip()
        }
        if (idS < 0) return
        val ip = Pb(d.b, idS, idE); val la = Pb(d.b, laS, laE); val lo = Pb(d.b, loS, loE)
        val kv = if (kvS >= 0) Pb(d.b, kvS, kvE) else null
        var id = 0L; var lat = 0L; var lon = 0L
        val tl = ArrayList<String>()
        while (ip.pos < idE) {
            id += ip.sint(); lat += la.sint(); lon += lo.sint()
            var tags: Array<String>? = null
            if (kv != null && kv.pos < kvE) {
                tl.clear()
                while (kv.pos < kvE) {
                    val k = kv.varint().toInt()
                    if (k == 0) break
                    val v = kv.varint().toInt()
                    tl.add(b.strings[k]); tl.add(b.strings[v])
                }
                if (tl.isNotEmpty()) tags = tl.toTypedArray()
            }
            val need = needNodes.binarySearch(id) >= 0
            if (!need && tags == null) continue
            node(id, 1e-9 * (b.latOff + b.gran * lat), 1e-9 * (b.lonOff + b.gran * lon), tags, onNode)
        }
    }

    private fun single(n: Pb, b: Block, onNode: (OsmEl) -> Unit) {
        var id = 0L; var lat = 0L; var lon = 0L
        val keys = IntList(); val vals = IntList()
        while (n.next()) when (n.field) {
            1 -> id = n.sint()
            2, 3 -> keysVals(n, keys, vals)
            8 -> lat = n.sint()
            9 -> lon = n.sint()
            else -> n.skip()
        }
        val tags = if (keys.size == 0) null else Array(keys.size * 2) { i -> if (i % 2 == 0) b.strings[keys[i / 2]] else b.strings[vals[i / 2]] }
        node(id, 1e-9 * (b.latOff + b.gran * lat), 1e-9 * (b.lonOff + b.gran * lon), tags, onNode)
    }

    private val cut = HashSet<Long>()

    /** True if the element lost nodes or members at the edge of the extract (uid as in [OsmEl.uid]). */
    fun truncated(uid: Long) = uid in cut

    private fun geom(w: Way): DoubleArray? {
        val out = DoubleList(w.refs.size * 2)
        for (r in w.refs) {
            val i = needNodes.binarySearch(r)
            if (i < 0 || nodeLat[i] == Int.MIN_VALUE) { cut.add(w.id * 4 + 1); continue } // outside the extract
            out.add(nodeLon[i] / 1e7, nodeLat[i] / 1e7)
        }
        return if (out.size >= 4) out.toArray() else null
    }

    private fun emitWays(onWay: (OsmEl) -> Unit) {
        var n = 0
        for (w in ways.values) {
            val m = tagMap(w.tags)
            if (m.isEmpty()) continue // only a relation member
            val g = geom(w) ?: continue
            onWay(OsmEl('w', w.id, m, Double.NaN, Double.NaN, g, null))
            if (++n % 20000 == 0 && cancelled()) throw InterruptedException()
        }
    }

    private fun emitRelations(onRelation: (OsmEl) -> Unit) {
        for (r in rels) {
            val ms = ArrayList<OsmEl.Member>(r.ids.size)
            for (i in r.ids.indices) {
                when (r.types[i].toInt()) {
                    0 -> {
                        val k = needNodes.binarySearch(r.ids[i])
                        val e = relNodeTags[r.ids[i]]
                        val lat = e?.lat ?: if (k >= 0 && nodeLat[k] != Int.MIN_VALUE) nodeLat[k] / 1e7 else Double.NaN
                        val lon = e?.lon ?: if (k >= 0 && nodeLat[k] != Int.MIN_VALUE) nodeLon[k] / 1e7 else Double.NaN
                        ms.add(OsmEl.Member('n', r.ids[i], r.roles[i], null, lat, lon))
                    }
                    1 -> {
                        val w = ways[r.ids[i]]
                        val g = w?.let { geom(it) }
                        if (g == null || truncated(r.ids[i] * 4 + 1)) cut.add(r.id * 4 + 2)
                        ms.add(OsmEl.Member('w', r.ids[i], r.roles[i], g, Double.NaN, Double.NaN))
                    }
                    else -> ms.add(OsmEl.Member('r', r.ids[i], r.roles[i], null, Double.NaN, Double.NaN))
                }
            }
            onRelation(OsmEl('r', r.id, tagMap(r.tags), Double.NaN, Double.NaN, null, ms))
        }
    }

    /** Tags of nodes that are members of the kept relations (station names). */
    fun memberNode(id: Long): OsmEl? = relNodeTags[id]

    /** Way tags + centre, for ways kept as places. */
    fun memberWayCenter(id: Long): DoubleArray? = ways[id]?.let { geom(it) }?.let { g -> Clip.bounds(g).let { doubleArrayOf((it[0] + it[2]) / 2, (it[1] + it[3]) / 2) } }
}

class LongList(cap: Int = 1024) {
    private var a = LongArray(cap)
    var size = 0
        private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }

    operator fun get(i: Int) = a[i]
    fun toArray(): LongArray = a.copyOf(size)

    fun sortedUnique(): LongArray {
        val s = a.copyOf(size)
        s.sort()
        var m = 0
        for (i in s.indices) if (i == 0 || s[i] != s[i - 1]) s[m++] = s[i]
        return s.copyOf(m)
    }
}
