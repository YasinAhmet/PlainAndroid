package app.localmusic

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.File

/**
 * "Remove duplicates" (⋮ → Library): the same song on the phone more than once, found by [Dedupe] (identical content,
 * or the same title + artist + length within 2 s). Unlike the organiser it also looks inside Plain Youtube's saved
 * music, and nothing is ever moved. The rules (pure, unit-tested) are in [plan]; the Android side in [Fix].
 *
 * A copy that Plain Youtube's download index (files/downloaded.txt) points to is never removed: that index is how the
 * Youtube tab knows a song is downloaded, so removing it would make the next Spotify Sync download the song again.
 */
object Duplicates {
    class Removal(val file: FileInfo, val keep: FileInfo, val identical: Boolean)

    /** [removals]: what goes to the trash. [protected]: copies kept only because Plain Youtube's downloads use them. */
    class Plan(val removals: List<Removal>, val protected: List<FileInfo>) {
        val bytesFreed get() = removals.sumOf { it.file.size }
    }

    /** Music, and Plain Youtube's saved music; never its cache (it evicts those itself). */
    fun eligible(f: FileInfo) = f.skipReason == null && !PlainYoutube.isCached(f.relPath)

    /**
     * @param tracked MediaStore ids that Plain Youtube's download index points to: never removed, and kept first
     * @param keepByUser ids the user unticked
     */
    fun plan(files: List<FileInfo>, tracked: Set<Long>, keepByUser: Set<Long> = emptySet()): Plan {
        val removals = ArrayList<Removal>()
        val protected = ArrayList<FileInfo>()
        for (g in Dedupe.find(files.filter { eligible(it) })) {
            val members = (listOf(g.keep) + g.remove).sortedWith(compareByDescending<FileInfo> { it.id in tracked }.then(Dedupe.BEST_FIRST))
            val keep = members[0]
            for (f in members.drop(1)) {
                when {
                    f.id in tracked -> protected += f
                    f.id in keepByUser -> {}
                    else -> removals += Removal(f, keep, f.hash != null && f.hash == keep.hash && f.size == keep.size)
                }
            }
        }
        return Plan(removals.sortedWith(compareBy({ Text.sortKey(it.keep.tags.title) }, { it.file.path.lowercase() })), protected)
    }

    /** MediaStore ids of every saved file in Plain Youtube's index ("videoId:A|V<TAB>content://…/<id>", "-key" = forgotten). */
    fun trackedIds(downloadedTxt: String): Set<Long> {
        val uris = HashMap<String, String>()
        for (line in downloadedTxt.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("-")) { uris.remove(t.substring(1)); continue }
            val u = t.substringAfter('\t', "")
            if (u.isNotEmpty()) uris[t.substringBefore('\t')] = u
        }
        return uris.values.mapNotNullTo(HashSet()) { it.substringAfterLast('/').toLongOrNull() }
    }

    /**
     * The kept copy is "Song (2).m4a" and the removed one was "Song.m4a" in the same folder: the kept one takes the
     * plain name back once the other is in the trash. Null = nothing to rename.
     */
    fun plainName(keep: FileInfo, removed: FileInfo): String? =
        if (keep.relPath.equals(removed.relPath, ignoreCase = true) && !keep.name.equals(removed.name, ignoreCase = true) &&
            Naming.isNumberedVariant(keep.name, removed.name)) removed.name else null

    /**
     * Playlists after the removal (pure): an entry of a removed file points at its kept copy instead, or goes when the
     * playlist already has that copy. True = something changed.
     */
    fun repoint(playlists: List<Playlist>, keepOf: Map<Long, FileInfo>): Boolean {
        var changed = false
        for (p in playlists) {
            val have = p.entries.mapTo(HashSet()) { it.songId }
            val it = p.entries.listIterator()
            while (it.hasNext()) {
                val e = it.next()
                val k = keepOf[e.songId] ?: continue
                changed = true
                if (k.id in have) it.remove() else { e.songId = k.id; e.path = k.path; have += k.id }
            }
        }
        return changed
    }

    /** Android side: after Android's trash dialog. */
    object Fix {
        fun tracked(ctx: Context): Set<Long> = try {
            trackedIds(File(ctx.filesDir, LinkedPlaylists.SAVED).readText())
        } catch (_: Exception) { emptySet() }

        /**
         * Kept "Song (2)" copies get the plain name of the removed copy (best effort: only files this app may rename
         * without asking). Returns the kept files with their names now.
         */
        fun rename(ctx: Context, done: List<Removal>, uris: Map<Long, Uri>): Map<Long, FileInfo> {
            val cr = ctx.contentResolver
            val out = HashMap<Long, FileInfo>()
            for (r in done) {
                val k = out[r.keep.id] ?: r.keep
                out[r.keep.id] = k
                val name = plainName(k, r.file) ?: continue
                if (k.name != r.keep.name) continue // renamed already (from another removed copy)
                val uri = uris[k.id] ?: continue
                try {
                    if (cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) > 0)
                        out[k.id] = k.copy(name = name)
                } catch (_: Exception) {} // not ours to rename: it keeps its "(2)"
            }
            return out
        }
    }
}
