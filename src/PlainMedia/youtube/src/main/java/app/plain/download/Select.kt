package app.plain.download

import app.plain.lang.tr

/** Page-based lists (2.11): [size] rows per page. Pure, unit-tested. */
object Paging {
    /** Pages for [n] loaded rows; an empty list still has page 1. */
    fun count(n: Int, size: Int): Int = maxOf(1, (maxOf(0, n) + size - 1) / size)

    /** The page shown for a wanted [page] (rows deleted meanwhile, or a page asked before its rows arrived): clamped. */
    fun shown(page: Int, n: Int, size: Int): Int = page.coerceIn(0, count(n, size) - 1)

    /** Rows [from, to) of the shown page. */
    fun window(page: Int, n: Int, size: Int): IntRange {
        val from = shown(page, n, size) * size
        return from until minOf(maxOf(0, n), from + size)
    }

    /** Is there a page after [page]: loaded rows, or more that YouTube has ([token], once the first answer is in). */
    fun hasNext(page: Int, n: Int, size: Int, token: Boolean, loadedOnce: Boolean): Boolean =
        (shown(page, n, size) + 1) * size < n || token && loadedOnce

    /** Going to page [k] needs the next batch from YouTube first. */
    fun needsMore(k: Int, n: Int, size: Int, token: Boolean, loadedOnce: Boolean): Boolean =
        n < (k + 1) * size && token && loadedOnce
}

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
        if (names.size == 1) return tr("Unfollow ${names[0].ifEmpty { "this channel" }}?", "Takip bırakılsın mı: ${names[0].ifEmpty { "bu kanal" }}")
        val sb = StringBuilder(tr("Unfollow ${names.size} channels?", "${names.size} kanalın takibi bırakılsın mı?"))
        for (n in names.take(show)) sb.append("\n• ").append(n)
        if (names.size > show) sb.append(tr("\n+${names.size - show} more", "\n+${names.size - show} tane daha"))
        return sb.toString()
    }

    fun undoBar(n: Int, youtube: Boolean): String =
        (if (n == 1) tr("Unfollowed", "Takip bırakıldı") else tr("Unfollowed $n", "$n kanalın takibi bırakıldı")) + if (youtube) tr(" · YouTube unsubscribes can't be undone here", " · YouTube abonelik iptalleri burada geri alınamaz") else ""
}
