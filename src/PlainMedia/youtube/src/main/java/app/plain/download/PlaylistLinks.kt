package app.plain.download

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 3.5: "Download all" on a YouTube playlist or a converted Spotify playlist also gives it a playlist in the Music
 * tab. This tab only writes the hand-off, files/playlist_links.json: the list's key ("yt:<playlistId>" /
 * "spotify:<id>"), its name and its video ids in order. The Music tab (app.localmusic.LinkedPlaylists, same format)
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
