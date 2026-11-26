package app.localmusic

import app.plain.lang.tr

/**
 * Pure rules for erasing (unit-tested). "Erase" = Android's MediaStore trash: restorable for ~30 days, then deleted by
 * Android. Plain Youtube trashes through the same MediaStore, so both apps see each other's erases on the next reload.
 */
class EraseSet private constructor(val songs: List<Song>) {
    val ids: Set<Long> = songs.mapTo(LinkedHashSet()) { it.id }
    val bytes: Long = songs.sumOf { it.size }
    val videos: Int = songs.count { it.isVideo }
    val audio: Int = songs.size - videos
    val artists: Int = songs.map { it.artistKey }.distinct().size
    val count get() = songs.size
    fun isEmpty() = songs.isEmpty()

    /** "12 songs · 2 videos · 345.6 MB" */
    val summary: String get() = (listOfNotNull(
        if (audio > 0 || videos == 0) Text.count(audio, "song") else null,
        if (videos > 0) Text.count(videos, "video") else null,
    ) + Text.bytes(bytes)).joinToString(" · ")

    /** A few titles for the confirmation, "… and N more". */
    fun preview(n: Int = 5): String =
        songs.take(n).joinToString("\n") { "• " + it.title } + if (songs.size > n) tr("\n… and ${songs.size - n} more", "\n… ve ${songs.size - n} tane daha") else ""

    companion object {
        /** Library files only (files opened from other apps are not ours to trash), each once. */
        fun of(songs: List<Song>): EraseSet = EraseSet(songs.filter { it.externalUri == null && it.id >= 0 }.distinctBy { it.id })

        fun of(g: Group): EraseSet = of(g.songs)
        fun of(folder: FolderNode): EraseSet = of(folder.all)
        /** "Erase songs in playlist": the playlist's songs that still exist; the playlist itself stays. */
        fun of(p: Playlist, byId: Map<Long, Song>, byPath: Map<String, Song>): EraseSet = of(p.resolve(byId, byPath))
    }
}

/** Selection mode in song lists: ids in the order they were picked; "Select all" works on the list on screen. */
class Selection {
    val ids = LinkedHashSet<Long>()
    val size get() = ids.size

    fun toggle(id: Long) { if (!ids.remove(id)) ids += id }
    fun isSelected(id: Long) = id in ids
    fun allSelected(list: List<Song>) = list.isNotEmpty() && list.all { it.id in ids }

    /** Select everything in [list], or unselect it all when it is all selected already. */
    fun toggleAll(list: List<Song>) {
        if (allSelected(list)) list.forEach { ids -= it.id } else list.forEach { ids += it.id }
    }

    /** The selected songs, in [list] order (what Play next / Add to playlist / Erase get). */
    fun selected(list: List<Song>): List<Song> = list.filter { it.id in ids }.distinctBy { it.id }
    fun clear() = ids.clear()
}

/** A trashed MediaStore item (audio, or a Plain Youtube video). [expires] = DATE_EXPIRES in seconds. */
class TrashItem(val id: Long, val volume: String, val video: Boolean, val path: String, val size: Long, val expires: Long)

object TrashMath {
    /** Below this much free storage the app points at the trash (or, with Media management, empties the oldest part). */
    const val LOW_SPACE = 3_000_000_000L

    fun isLow(freeBytes: Long) = freeBytes < LOW_SPACE

    /** Whole days until Android deletes it for good (0 = today). */
    fun daysLeft(expiresSec: Long, nowMs: Long): Long = ((expiresSec * 1000 - nowMs).coerceAtLeast(0) + 86_399_999) / 86_400_000

    /** Oldest-trashed first (expires soonest), just enough to get [free] back above [target]. */
    fun toPurge(items: List<TrashItem>, free: Long, target: Long = LOW_SPACE): List<TrashItem> {
        val out = ArrayList<TrashItem>()
        var have = free
        for (i in items.sortedWith(compareBy({ it.expires }, { it.id }))) {
            if (have >= target) break
            out += i
            have += i.size
        }
        return out
    }

    /** Trash file names look like ".trashed-1767225600-Song.mp3": show "Song.mp3". */
    fun displayName(name: String): String = name.removePrefix(".trashed-").replace(Regex("^\\d+-"), "")
}
