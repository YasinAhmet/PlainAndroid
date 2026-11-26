package app.plain.download

/** Selection on a list page (videos on most pages, channels on Following). Pure, unit-tested. */
class Selection {
    val ids = LinkedHashSet<String>()
    var active = false
        private set
    val size get() = ids.size
    operator fun contains(id: String) = id in ids

    fun start(id: String) { active = true; ids.clear(); ids += id }

    /** Toggles [id]; false when that emptied the selection, which then ends. */
    fun toggle(id: String): Boolean {
        if (!ids.remove(id)) ids += id
        if (ids.isEmpty()) { end(); return false }
        return true
    }

    fun addAll(list: Iterable<String>) { if (active) ids.addAll(list) }

    /** After a filter change: only what's still shown stays selected (so "Unfollow N" never hits hidden rows). */
    fun retain(shown: Set<String>) { ids.retainAll(shown) }

    fun end() { active = false; ids.clear() }

    fun <T> pick(items: List<T>, id: (T) -> String): List<T> = items.filter { id(it) in ids }
}

/** Following list filter and unfollow texts (2.2). */
object UnfollowText {
    /** Case- and accent-insensitive match on the channel name or id ("isik" finds "Işık"). */
    fun matches(f: Follow, query: String): Boolean {
        val q = Browse.fold(query.trim())
        if (q.isEmpty()) return true
        return Browse.fold(f.title).contains(q) || f.id.equals(query.trim(), ignoreCase = true)
    }

    fun filter(list: List<Follow>, query: String) = list.filter { matches(it, query) }

    /** "Unfollow Alpha?" or "Unfollow 7 channels?" + up to [show] names + "+K more". */
    fun confirm(names: List<String>, show: Int = 5): String {
        if (names.size == 1) return "Unfollow ${names[0].ifEmpty { "this channel" }}?"
        val sb = StringBuilder("Unfollow ${names.size} channels?")
        for (n in names.take(show)) sb.append("\n• ").append(n)
        if (names.size > show) sb.append("\n+${names.size - show} more")
        return sb.toString()
    }

    fun undoBar(n: Int, youtube: Boolean): String =
        (if (n == 1) "Unfollowed" else "Unfollowed $n") + if (youtube) " · YouTube unsubscribes can't be undone here" else ""
}
