package app.plain.gallery

/**
 * The user's categories: each name holds a set of media-store ids, and one item can be in any
 * number of them. Pure Kotlin (unit-tested); [Categories] keeps it on disk.
 * Ids are never reused by the media store, so ids of deleted items are harmless; they are kept so
 * that a photo restored from the trash comes back with its categories.
 */
class Tags {
    private val map = sortedMapOf<String, LinkedHashSet<Long>>(String.CASE_INSENSITIVE_ORDER)

    val names: List<String> get() = map.keys.toList()
    fun has(name: String) = map.containsKey(name)
    fun ids(name: String): Set<Long> = map[name] ?: emptySet()

    /** Every id that is in at least one category. */
    fun allIds(): Set<Long> = map.values.flatMapTo(HashSet()) { it }

    /** id → the categories it is in (names in display order). */
    fun byId(): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        for ((n, s) in map) for (id in s) out.getOrPut(id) { ArrayList(2) } += n
        return out
    }

    /** Adds an empty category (or finds the existing one, ignoring case). Returns its name, or null for a blank name. */
    fun create(name: String): String? {
        val n = clean(name)
        if (n.isEmpty()) return null
        if (!map.containsKey(n)) map[n] = LinkedHashSet()
        return map.keys.first { it.equals(n, ignoreCase = true) }
    }

    fun delete(name: String) { map.remove(name) }

    /** Renames; renaming onto another existing category merges the two. */
    fun rename(old: String, new: String): Boolean {
        val n = clean(new)
        val set = map[old] ?: return false
        if (n.isEmpty()) return false
        map.remove(old)
        val other = map[n]
        if (other != null) other.addAll(set) else map[n] = set
        return true
    }

    fun add(name: String, ids: Collection<Long>) { create(name)?.let { map[it]!!.addAll(ids) } }
    fun remove(name: String, ids: Collection<Long>) { map[name]?.removeAll(ids.toSet()) }

    /** Whether none, some or all of [ids] are in [name]. */
    fun state(name: String, ids: Collection<Long>): Int {
        val s = map[name] ?: return NONE
        val c = ids.count { it in s }
        return when {
            ids.isEmpty() || c == 0 -> NONE
            c == ids.size -> ALL
            else -> SOME
        }
    }

    /**
     * One row of the categorize dialog: checked puts every id in; unchecked takes them all out,
     * but only if the user touched that row (a "some" row left alone stays as it was).
     */
    fun applyChoice(name: String, ids: Collection<Long>, checked: Boolean, touched: Boolean) {
        if (checked) add(name, ids) else if (touched) remove(name, ids)
    }

    /** One line per category: name, a tab, then the ids separated by commas. */
    fun serialize(): String = map.entries.joinToString("\n") { (k, v) -> k + "\t" + v.joinToString(",") }

    companion object {
        const val NONE = 0
        const val SOME = 1
        const val ALL = 2

        fun clean(s: String) = s.replace(Regex("[\\t\\r\\n]+"), " ").trim().take(40).trim()

        fun parse(text: String): Tags {
            val t = Tags()
            for (line in text.split('\n')) {
                if (line.isBlank()) continue
                val parts = line.split('\t', limit = 2)
                val n = t.create(parts[0]) ?: continue
                parts.getOrNull(1)?.split(',')?.mapNotNull { it.trim().toLongOrNull() }?.let { t.map[n]!!.addAll(it) }
            }
            return t
        }
    }
}
