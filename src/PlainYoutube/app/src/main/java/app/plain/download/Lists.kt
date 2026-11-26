package app.plain.download

import org.json.JSONArray
import org.json.JSONObject

/** A local named list of videos ("Add to list…"). Its name doubles as the album folder for audio saved from it. */
data class VList(val id: String, val name: String, val items: List<Item>)

/** Pure operations on the local lists (stored in files/lists.json by [Store]). */
object VideoLists {
    fun create(all: List<VList>, name: String, now: Long): Pair<List<VList>, VList> {
        val n = name.trim().ifEmpty { "List" }
        val l = VList("l$now", n, emptyList())
        return (all + l) to l
    }

    /** Adds videos (no duplicates, order kept); returns the new lists and how many were new. */
    fun add(all: List<VList>, listId: String, items: List<Item>): Pair<List<VList>, Int> {
        var added = 0
        val out = all.map { l ->
            if (l.id != listId) l else {
                val have = l.items.mapTo(HashSet()) { it.id }
                val fresh = items.filter { it.isVideo && have.add(it.id) }
                added = fresh.size
                l.copy(items = l.items + fresh)
            }
        }
        return out to added
    }

    fun removeItem(all: List<VList>, listId: String, videoId: String) =
        all.map { if (it.id == listId) it.copy(items = it.items.filter { i -> i.id != videoId }) else it }

    fun rename(all: List<VList>, listId: String, name: String) =
        all.map { if (it.id == listId && name.isNotBlank()) it.copy(name = name.trim()) else it }

    fun delete(all: List<VList>, listId: String) = all.filter { it.id != listId }

    fun encode(all: List<VList>): String {
        val a = JSONArray()
        all.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("items", ItemCodec.encode(it.items))) }
        return a.toString()
    }

    fun decode(s: String): List<VList> = try {
        val a = JSONArray(s)
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifEmpty { return@mapNotNull null }
            VList(id, o.optString("name").ifEmpty { "List" }, ItemCodec.decode(o.optJSONArray("items") ?: JSONArray()))
        }
    } catch (_: Exception) { emptyList() }
}

/**
 * One play-queue entry: a stream (video id) or a saved file ([localUri]), with the Watch/Listen choice for it
 * (null = the player decides) and the album hint of the list/playlist it came from.
 */
data class QEntry(
    val id: String,
    val title: String,
    val channel: String,
    val channelId: String? = null,
    val mode: PlayMode? = null,
    val album: String? = null,
    val localUri: String? = null,
    val localAudio: Boolean = false,
)

/** Play queue: entries + the current index. Pure; persisted in files/queue.json. */
data class PlayQueue(val items: List<QEntry> = emptyList(), val index: Int = 0) {
    val current: QEntry? get() = items.getOrNull(index)
    val hasNext get() = index + 1 < items.size
    val hasPrev get() = index > 0

    fun append(e: List<QEntry>) = copy(items = items + e, index = if (items.isEmpty()) 0 else index)
    fun replace(e: List<QEntry>, start: Int = 0) = PlayQueue(e, start.coerceIn(0, maxOf(0, e.size - 1)))
    fun next() = if (hasNext) copy(index = index + 1) else this
    fun prev() = if (hasPrev) copy(index = index - 1) else this
    fun jump(i: Int) = if (i in items.indices) copy(index = i) else this
    fun setMode(i: Int, m: PlayMode) = copy(items = items.mapIndexed { k, e -> if (k == i) e.copy(mode = m) else e })

    /** Swap entry [i] with [i]+[d] (d = -1 up, +1 down); the playing entry keeps playing. */
    fun move(i: Int, d: Int): PlayQueue {
        val j = i + d
        if (i !in items.indices || j !in items.indices) return this
        val l = items.toMutableList(); val t = l[i]; l[i] = l[j]; l[j] = t
        val idx = when (index) { i -> j; j -> i; else -> index }
        return PlayQueue(l, idx)
    }

    fun remove(i: Int): PlayQueue {
        if (i !in items.indices) return this
        val l = items.toMutableList().also { it.removeAt(i) }
        val idx = when {
            l.isEmpty() -> 0
            i < index -> index - 1
            else -> index.coerceAtMost(l.size - 1)
        }
        return PlayQueue(l, idx)
    }

    fun encode(): String {
        val a = JSONArray()
        for (e in items) a.put(JSONObject().apply {
            put("id", e.id); put("t", e.title); put("c", e.channel)
            e.channelId?.let { put("ci", it) }; e.mode?.let { put("m", it.name) }; e.album?.let { put("al", it) }
            e.localUri?.let { put("u", it) }; if (e.localAudio) put("au", true)
        })
        return JSONObject().put("index", index).put("items", a).toString()
    }

    companion object {
        fun decode(s: String): PlayQueue = try {
            val o = JSONObject(s)
            val a = o.optJSONArray("items") ?: JSONArray()
            val l = (0 until a.length()).mapNotNull { i ->
                val e = a.optJSONObject(i) ?: return@mapNotNull null
                QEntry(
                    e.optString("id"), e.optString("t"), e.optString("c"), e.optString("ci").ifEmpty { null },
                    e.optString("m").ifEmpty { null }?.let { runCatching { PlayMode.valueOf(it) }.getOrNull() },
                    e.optString("al").ifEmpty { null }, e.optString("u").ifEmpty { null }, e.optBoolean("au"),
                )
            }
            PlayQueue(l, o.optInt("index").coerceIn(0, maxOf(0, l.size - 1)))
        } catch (_: Exception) { PlayQueue() }

        fun of(i: Item, mode: PlayMode? = null, album: String? = null) = QEntry(i.id, i.title, i.channel, i.channelId, mode, album)
    }
}
