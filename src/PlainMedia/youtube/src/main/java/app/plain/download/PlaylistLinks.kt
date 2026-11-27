package app.plain.download

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 3.5: "Download all" on a YouTube playlist, a converted Spotify playlist or one of the Lists also gives it a
 * playlist in the Music tab. This tab only writes the hand-off, files/playlist_links.json: the list's key ("yt:<playlistId>" /
 * "spotify:<id>" / "list:<id>"), its name and its video ids in order. The Music tab (app.localmusic.LinkedPlaylists, same format)
 * fills its playlist from the downloaded files (files/downloaded.txt) whenever its library loads, so the playlist
 * grows as the downloads finish. Deleting that playlist in the Music tab removes the link.
 */
object PlaylistLinks {
    const val FILE = "playlist_links.json"

    data class Link(val key: String, val name: String, val ids: List<String>, val time: Long)

    /** Add or update the link [key] (a list run again keeps its key: new songs are added to the same playlist). */
    fun upsert(links: List<Link>, key: String, name: String, ids: List<String>, now: Long): List<Link> {
        val l = Link(key, name.ifBlank { links.firstOrNull { it.key == key }?.name ?: "Playlist" }, ids.distinct(), now)
        return links.filter { it.key != key } + l
    }

    fun encode(links: List<Link>): String = JSONObject().put("v", 1).put("links", JSONArray().apply {
        links.forEach { put(JSONObject().put("key", it.key).put("name", it.name).put("ids", JSONArray(it.ids)).put("t", it.time)) }
    }).toString()

    fun decode(s: String): List<Link> = try {
        val a = JSONObject(s).optJSONArray("links") ?: JSONArray()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val key = o.optString("key").ifEmpty { return@mapNotNull null }
            val ids = o.optJSONArray("ids") ?: JSONArray()
            Link(key, o.optString("name"), (0 until ids.length()).map { ids.optString(it) }.filter { it.isNotEmpty() }, o.optLong("t"))
        }
    } catch (_: Exception) { emptyList() }

    /**
     * Lists saved before 3.5 (pure): a link for each candidate (key, name, video ids) that has no link yet and of which
     * at least 2 videos and half the list are saved ([saved]).
     */
    fun backfill(links: List<Link>, candidates: List<Triple<String, String, List<String>>>, saved: (String) -> Boolean, now: Long): List<Link> {
        var out = links
        for ((key, name, ids) in candidates) {
            if (out.any { it.key == key }) continue
            val v = ids.distinct()
            val n = v.count(saved)
            if (n >= 2 && n * 2 >= v.size) out = upsert(out, key, name, v, now)
        }
        return out
    }

    /**
     * Once (3.5): Spotify playlists and Lists that were downloaded or cached before links existed get their Music
     * playlist too. Called by the Music tab's library load (background thread) through LinkedPlaylists.prepare.
     */
    fun backfillOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("playlist_links", Context.MODE_PRIVATE)
        if (prefs.getBoolean("backfill1", false)) return
        Store.init(ctx); SpotifySync.init(ctx)
        val d = SpotifySync.data()
        val candidates = d.playlists.map { Triple("spotify:" + it.id, it.name, d.items(it).map { i -> i.id }) } +
            Store.lists().map { Triple("list:" + it.id, it.name, it.items.filter { i -> i.isVideo }.map { i -> i.id }) }
        val saved = { id: String -> Store.savedUri(id, Kind.AUDIO) != null || Store.savedUri(id, Kind.VIDEO) != null }
        synchronized(this) {
            val f = AtomicFile(File(ctx.filesDir, FILE))
            val old = try { decode(String(f.readFully(), Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
            val new = backfill(old, candidates, saved, System.currentTimeMillis())
            if (new != old) {
                val out = f.startWrite()
                try { out.write(encode(new).toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (_: Exception) { f.failWrite(out); return }
            }
        }
        prefs.edit().putBoolean("backfill1", true).apply()
    }

    /** Remember that [items] (in order) were downloaded as the list [key] named [name]. */
    @Synchronized fun remember(ctx: Context, key: String, name: String, items: List<Item>) {
        val ids = items.filter { it.isVideo && !it.live }.map { it.id }
        if (ids.isEmpty()) return
        val f = AtomicFile(File(ctx.filesDir, FILE))
        val old = try { decode(String(f.readFully(), Charsets.UTF_8)) } catch (_: Exception) { emptyList() }
        val out = f.startWrite()
        try {
            out.write(encode(upsert(old, key, name, ids, System.currentTimeMillis())).toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (_: Exception) { f.failWrite(out) }
    }
}
