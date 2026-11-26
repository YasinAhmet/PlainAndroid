package app.localmusic

import java.util.Random

enum class Repeat {
    OFF, ALL, ONE;

    fun next(): Repeat = entries[(ordinal + 1) % entries.size]
}

/**
 * Play queue with shuffle and repeat (pure logic).
 * [items] is the natural order; [order] maps play positions to indices in [items].
 * Without shuffle [order] is the identity.
 */
class PlayQueue(private val rnd: Random = Random()) {
    val items = ArrayList<Long>()
    val order = ArrayList<Int>()
    var pos = -1
        private set
    var shuffle = false
        private set
    var repeat = Repeat.OFF

    val size get() = order.size
    fun isEmpty() = order.isEmpty()
    fun current(): Long? = if (pos in order.indices) items[order[pos]] else null
    fun idAt(playPos: Int): Long = items[order[playPos]]
    fun playOrder(): List<Long> = order.map { items[it] }

    /** Replace the queue; [start] is an index into [ids]. With shuffle, [start] plays first and the rest is shuffled. */
    fun set(ids: List<Long>, start: Int, shuffle: Boolean) {
        items.clear(); items.addAll(ids)
        order.clear(); order.addAll(items.indices)
        this.shuffle = shuffle
        if (items.isEmpty()) { pos = -1; return }
        pos = start.coerceIn(0, items.size - 1)
        if (shuffle) shuffleAround(pos)
    }

    /** "Shuffle play": random first song. */
    fun setShuffled(ids: List<Long>) = set(ids, if (ids.isEmpty()) 0 else rnd.nextInt(ids.size), true)

    private fun shuffleAround(itemIndex: Int) {
        val rest = items.indices.filterTo(ArrayList()) { it != itemIndex }
        java.util.Collections.shuffle(rest, rnd)
        order.clear(); order.add(itemIndex); order.addAll(rest)
        pos = 0
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        if (order.isEmpty()) return
        val cur = order[pos]
        if (on) shuffleAround(cur) else {
            order.clear(); order.addAll(items.indices); pos = cur
        }
    }

    /** Play position that follows the current one. [auto] = the song ended by itself (repeat-one replays it). */
    fun peekNext(auto: Boolean): Int? {
        if (pos < 0) return null
        if (auto && repeat == Repeat.ONE) return pos
        if (pos + 1 < order.size) return pos + 1
        return if (repeat != Repeat.OFF) 0 else null
    }

    fun peekPrev(): Int? = when {
        pos < 0 -> null
        pos > 0 -> pos - 1
        repeat != Repeat.OFF -> order.size - 1
        else -> 0
    }

    fun jump(playPos: Int) {
        if (playPos in order.indices) pos = playPos
    }

    /** Move an entry from one play position to another (drag to reorder). */
    fun move(from: Int, to: Int) {
        if (from == to || from !in order.indices || to !in order.indices) return
        if (shuffle) {
            val v = order.removeAt(from); order.add(to, v)
        } else {
            val v = items.removeAt(from); items.add(to, v)
        }
        pos = when {
            pos == from -> to
            from < pos && to >= pos -> pos - 1
            from > pos && to <= pos -> pos + 1
            else -> pos
        }
    }

    /** Remove the entry at a play position. If it was the current one, the following entry becomes current. */
    fun removeAt(playPos: Int) {
        if (playPos !in order.indices) return
        val itemIndex = order.removeAt(playPos)
        items.removeAt(itemIndex)
        for (i in order.indices) if (order[i] > itemIndex) order[i] = order[i] - 1
        if (playPos < pos) pos--
        if (order.isEmpty()) pos = -1 else if (pos >= order.size) pos = if (repeat == Repeat.ALL) 0 else order.size - 1
    }

    /** Drop every entry whose id fails [keep] (e.g. files that no longer exist). */
    fun retain(keep: (Long) -> Boolean) {
        for (p in order.indices.reversed()) if (!keep(items[order[p]])) removeAt(p)
    }

    fun playNext(id: Long) {
        if (pos < 0) { set(listOf(id), 0, shuffle); return }
        if (shuffle) {
            items.add(id); order.add(pos + 1, items.size - 1)
        } else {
            items.add(pos + 1, id); order.add(order.size); // identity stays valid
        }
    }

    fun addToEnd(ids: List<Long>) {
        if (pos < 0) { set(ids, 0, shuffle); return }
        for (id in ids) { items.add(id); order.add(items.size - 1) }
    }

    // --- persistence -------------------------------------------------------------------------

    fun save(): Map<String, String> = mapOf(
        "items" to items.joinToString(","),
        "order" to order.joinToString(","),
        "pos" to pos.toString(),
        "shuffle" to shuffle.toString(),
        "repeat" to repeat.name,
    )

    fun restore(m: Map<String, String?>) {
        items.clear(); order.clear(); pos = -1
        repeat = runCatching { Repeat.valueOf(m["repeat"] ?: "") }.getOrDefault(Repeat.OFF)
        shuffle = m["shuffle"] == "true"
        val its = m["items"].orEmpty().split(',').mapNotNull { it.toLongOrNull() }
        val ord = m["order"].orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        if (its.isEmpty()) return
        items.addAll(its)
        if (ord.size == its.size && ord.toSet() == its.indices.toSet()) order.addAll(ord) else { order.addAll(its.indices); shuffle = false }
        pos = (m["pos"]?.toIntOrNull() ?: 0).coerceIn(0, order.size - 1)
    }
}
