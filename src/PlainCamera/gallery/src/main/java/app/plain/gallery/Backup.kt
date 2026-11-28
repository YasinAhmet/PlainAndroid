package app.plain.gallery

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The categories backup's text (pure, unit-tested). Besides the media-store ids it keeps each tagged item's
 * folder + name + size, so the categories still find their photos if the ids ever change.
 *
 *     PlainGalleryCategories 1
 *     time<TAB>ms
 *     k<TAB>id<TAB>folder/name|size
 *     c<TAB>category<TAB>id,id,…
 */
object Backup {
    const val HEAD = "PlainGalleryCategories"

    class Data(val time: Long, val tags: Tags, val keys: Map<Long, String>)

    /** The fallback key of one item: RELATIVE_PATH + DISPLAY_NAME + "|" + SIZE; null without a name. */
    fun key(relPath: String?, name: String?, size: Long): String? =
        if (name.isNullOrEmpty()) null else flat((relPath ?: "") + name + "|" + size)

    private fun flat(s: String) = s.replace(Regex("[\\t\\r\\n]"), " ")

    fun encode(time: Long, tags: Tags, keys: Map<Long, String>): String = buildString {
        append(HEAD).append(" 1\n")
        append("time\t").append(time).append('\n')
        for ((id, k) in keys) append("k\t").append(id).append('\t').append(flat(k)).append('\n')
        for (line in tags.serialize().split('\n')) if (line.isNotBlank()) append("c\t").append(line).append('\n')
    }

    /** Null when [text] isn't a backup. Bad lines are skipped. */
    fun decode(text: String): Data? {
        val lines = text.split('\n')
        if (lines.firstOrNull()?.trim()?.startsWith(HEAD) != true) return null
        var time = 0L
        val keys = HashMap<Long, String>()
        val cats = StringBuilder()
        for (raw in lines.drop(1)) {
            val l = raw.trimEnd('\r')
            val p = l.split('\t', limit = 3)
            when (p[0]) {
                "time" -> time = p.getOrNull(1)?.trim()?.toLongOrNull() ?: 0L
                "k" -> {
                    val id = p.getOrNull(1)?.trim()?.toLongOrNull()
                    val k = p.getOrNull(2)
                    if (id != null && !k.isNullOrEmpty()) keys[id] = k
                }
                "c" -> if (p.size >= 2) cats.append(p[1]).append('\t').append(p.getOrNull(2) ?: "").append('\n')
            }
        }
        return Data(time, Tags.parse(cats.toString()), keys)
    }

    /**
     * The backup's categories mapped onto the library as it is now ([current]: id → key of every photo and video).
     * An id that still names the same file stays; a moved id is found again by its key. When most surviving ids now
     * name other files (the media store was rebuilt), an id that matches no key is dropped instead of mis-tagging.
     */
    fun remap(d: Data, current: Map<Long, String>): Tags {
        val byKey = HashMap<String, Long>(current.size * 2)
        for ((id, k) in current) byKey.putIfAbsent(k, id)
        var checked = 0
        var same = 0
        for ((id, k) in d.keys) {
            val now = current[id] ?: continue
            checked++
            if (now == k) same++
        }
        val trustIds = checked == 0 || same * 2 >= checked
        val out = Tags()
        for (n in d.tags.names) {
            val name = out.create(n) ?: continue
            out.add(name, d.tags.ids(n).mapNotNull { id ->
                val saved = d.keys[id]
                val now = current[id]
                when {
                    saved == null || now == saved -> id
                    byKey[saved] != null -> byKey[saved]
                    now == null -> id // gone or in the trash: harmless, and it comes back with the photo
                    trustIds -> id   // renamed or moved under the same id
                    else -> null
                }
            })
        }
        return out
    }

    /** Adds every category and id of [from] to [into] (nothing in [into] is lost). */
    fun merge(into: Tags, from: Tags) {
        for (n in from.names) into.add(n, from.ids(n))
    }
}

/** A private PNG chunk that carries the backup text inside a real picture (so it survives as media). */
object Png {
    private val SIG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    /** Ancillary, private, safe-to-copy: viewers skip it. */
    private const val TYPE = "plGc"
    /** A backup never grows near this; it guards against a corrupt or hostile file. */
    const val MAX = 32 shl 20

    fun isPng(b: ByteArray) = b.size >= 8 && (0 until 8).all { b[it] == SIG[it] }

    private fun int(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun type(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    /** [png] with [data] (zlib-packed) in a chunk just before IEND; any older copy of the chunk is dropped. */
    fun withData(png: ByteArray, data: ByteArray): ByteArray {
        require(isPng(png))
        val out = ByteArrayOutputStream(png.size + data.size / 2 + 64)
        out.write(png, 0, 8)
        var pos = 8
        var done = false
        while (pos + 12 <= png.size) {
            val len = int(png, pos)
            if (len < 0 || pos + 12L + len > png.size) break
            val t = type(png, pos + 4)
            if (t == "IEND") { chunk(out, pack(data)); done = true }
            if (t != TYPE) out.write(png, pos, 12 + len)
            pos += 12 + len
            if (t == "IEND") break
        }
        require(done) { "no IEND" }
        return out.toByteArray()
    }

    /** The data in [png]'s backup chunk, or null. */
    fun readData(png: ByteArray): ByteArray? {
        if (!isPng(png)) return null
        var pos = 8
        while (pos + 12 <= png.size) {
            val len = int(png, pos)
            if (len < 0 || pos + 12L + len > png.size) return null
            if (type(png, pos + 4) == TYPE) return try { unpack(png, pos + 8, len) } catch (_: Exception) { null }
            pos += 12 + len
        }
        return null
    }

    private fun chunk(out: ByteArrayOutputStream, data: ByteArray) {
        val t = TYPE.toByteArray(Charsets.ISO_8859_1)
        val n = data.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(t)
        out.write(data)
        val c = CRC32().apply { update(t); update(data) }.value
        out.write(byteArrayOf((c ushr 24).toByte(), (c ushr 16).toByte(), (c ushr 8).toByte(), c.toByte()))
    }

    private fun pack(b: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(b); d.finish()
        val out = ByteArrayOutputStream(b.size / 3 + 64)
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    private fun unpack(b: ByteArray, off: Int, len: Int): ByteArray? {
        val f = Inflater()
        try {
            f.setInput(b, off, len)
            val out = ByteArrayOutputStream(len * 4)
            val buf = ByteArray(8192)
            while (!f.finished()) {
                val n = f.inflate(buf)
                if (n == 0 && (f.needsInput() || f.needsDictionary())) return null
                out.write(buf, 0, n)
                if (out.size() > MAX) return null
            }
            return out.toByteArray()
        } finally { f.end() }
    }
}
