package app.localmusic

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 3.5: YouTube / Spotify playlists downloaded with "Download all" in the Youtube tab become playlists here.
 * The Youtube tab writes files/playlist_links.json (app.plain.download.PlaylistLinks: key, name, video ids in order);
 * its saved-file index files/downloaded.txt says which file each video became ("videoId:A|V<TAB>content://…/<id>").
 * Each library load ([sync]) adds the songs that finished downloading since, in the playlist's own order, to the
 * playlist with that [Playlist.source]. Songs the user removed or reordered here stay that way ([Playlist.got]).
 * Deleting the playlist here removes its link ([forget]), so it doesn't come back.
 */
object LinkedPlaylists {
    const val LINKS = "playlist_links.json"
    const val SAVED = "downloaded.txt"

    /** Set by the app (the Youtube tab's one-time catch-up for lists saved before 3.5); run on the library-load thread. */
    @Volatile var prepare: ((Context) -> Unit)? = null

    class Link(val key: String, val name: String, val ids: List<String>)

    fun decodeLinks(s: String): List<Link> = try {
        val a = JSONObject(s).optJSONArray("links") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").ifEmpty { return@mapNotNull null }
            val ids = o.optJSONArray("ids") ?: JSONArray()
            Link(key, o.optString("name").ifBlank { "Playlist" }, (0 until ids.length()).map { ids.optString(it) }.filter { it.isNotEmpty() })
        }
    } catch (_: Exception) { emptyList() }

    /** The links file without [key] (same JSON shape the Youtube tab writes; other fields kept). */
    fun dropLink(s: String, key: String): String? = try {
        val o = JSONObject(s)
        val a = o.optJSONArray("links") ?: JSONArray()
        val out = JSONArray()
        var hit = false
        for (i in 0 until a.length()) { val l = a.optJSONObject(i) ?: continue; if (l.optString("key") == key) hit = true else out.put(l) }
        if (hit) o.put("links", out).toString() else null
    } catch (_: Exception) { null }

    /**
     * downloaded.txt -> video key ("<id>:A|V") -> content:// uri of its saved file. The same rules as the Youtube tab's
     * reader (app.plain.download.DoneIndex.parse): "key<TAB>uri" (a later line wins), "key" (a mark without a file),
     * "-key" (the file was forgotten).
     *  - A YouTube id can itself start with '-' (about 1 in 64): "-x8GhAbCdEf:A<TAB>content://…" is an ordinary entry.
     *    "-" means "forget" only on a line without a uri whose rest is a whole key with an 11-character id.
     *  - A last line without its newline was cut short (the app was killed, the disk was full): ignored.
     *  - Lines that aren't "<id>:A|V" are skipped; a uri that isn't content:// is not a saved file.
     */
    fun savedUris(text: String): Map<String, String> {
        val uris = HashMap<String, String>()
        val lines = text.split('\n')
        for (l in if (text.endsWith("\n")) lines else lines.dropLast(1)) {
            val t = l.trim()
            if (t.isEmpty()) continue
            if (t.startsWith("-") && '\t' !in t && isKey(t.substring(1)) && t.substringBefore(':').length == 12) {
                uris.remove(t.substring(1)); continue
            }
            val k = t.substringBefore('\t').trim()
            if (!isKey(k)) continue
            val u = t.substringAfter('\t', "").trim()
            if (u.startsWith("content://")) uris[k] = u
        }
        return uris
    }

    /** "<id>:A" / "<id>:V" with no blanks inside. */
    private fun isKey(k: String): Boolean = k.length >= 3 && (k.endsWith(":A") || k.endsWith(":V")) && k.none { it.isWhitespace() }

    /** downloaded.txt -> video id -> MediaStore id of its saved file (audio preferred over video). */
    fun savedIds(text: String): Map<String, Long> {
        val uris = savedUris(text)
        val out = HashMap<String, Long>()
        for (kind in listOf("V", "A")) for ((k, u) in uris) { // audio last: it wins
            if (!k.endsWith(":$kind")) continue
            val id = u.substringAfterLast('/').toLongOrNull() ?: continue
            out[k.substringBeforeLast(':')] = id
        }
        return out
    }

    /**
     * Bring [playlists] up to date with [links] (pure). [songOf]: the library song of a downloaded video, null while
     * not downloaded or not scanned yet (tried again next load). A new playlist is made only once a song is there.
     * Returns true when something changed.
     */
    fun sync(playlists: MutableList<Playlist>, links: List<Link>, songOf: (String) -> Song?): Boolean {
        var changed = false
        for (link in links) {
            val pos = HashMap<String, Int>().apply { link.ids.forEachIndexed { i, v -> putIfAbsent(v, i) } }
            val have = link.ids.mapNotNull { v -> songOf(v)?.let { v to it } }
            var p = playlists.firstOrNull { it.source == link.key }
            if (p == null) {
                if (have.isEmpty()) continue
                p = Playlist((playlists.maxOfOrNull { it.id } ?: 0) + 1, link.name, source = link.key)
                playlists += p
                changed = true
            }
            // which video each entry came from (by song id), to keep the playlist's order for late arrivals
            val vidOf = HashMap<Long, String>().apply { for ((v, s) in have) put(s.id, v) }
            for ((v, s) in have) {
                if (v in p.got) continue
                val at = pos.getValue(v)
                val pl = p.entries.map { e -> vidOf[e.songId]?.let { pos[it] } }
                val before = pl.indexOfLast { it != null && it < at }
                val after = pl.indexOfFirst { it != null && it > at }
                val i = when { before >= 0 -> before + 1; after >= 0 -> after; else -> p.entries.size }
                p.entries.add(i, Playlist.Entry(s.id, s.path))
                p.got += v
                changed = true
            }
        }
        return changed
    }

    // --- files -------------------------------------------------------------------------------------

    private fun links(ctx: Context) = AtomicFile(File(ctx.filesDir, LINKS))

    /** Main thread, after a library load: add newly downloaded songs to their playlists. */
    fun sync(ctx: Context, lib: LibraryIndex): Boolean {
        val l = try { decodeLinks(String(links(ctx).readFully(), Charsets.UTF_8)) } catch (_: Exception) { return false }
        if (l.isEmpty()) return false
        val saved = try { savedIds(File(ctx.filesDir, SAVED).readText()) } catch (_: Exception) { emptyMap() }
        val changed = sync(Store.playlists, l) { v -> saved[v]?.let { lib.byId[it] } }
        if (changed) Store.savePlaylists()
        return changed
    }

    /** The playlist [p] was deleted here: stop mirroring its source. */
    fun forget(ctx: Context, p: Playlist) {
        val key = p.source ?: return
        val f = links(ctx)
        val s = try { String(f.readFully(), Charsets.UTF_8) } catch (_: Exception) { return }
        val out = dropLink(s, key) ?: return
        val w = f.startWrite()
        try { w.write(out.toByteArray(Charsets.UTF_8)); f.finishWrite(w) } catch (_: Exception) { f.failWrite(w) }
    }
}
