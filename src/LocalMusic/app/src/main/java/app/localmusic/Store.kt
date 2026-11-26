package app.localmusic

import android.content.Context
import android.content.SharedPreferences
import android.util.AtomicFile
import java.io.File

/** Local-only persistence: player state in SharedPreferences, playlists in a JSON file. */
object Store {
    lateinit var prefs: SharedPreferences
        private set
    private lateinit var playlistFile: AtomicFile
    private lateinit var historyFile: AtomicFile
    private var historyLoaded: PlayHistory? = null

    /** Play history (files/history.tsv), read on first use only. */
    val history: PlayHistory get() = historyLoaded ?: (try {
        PlayHistory.decode(String(historyFile.readFully(), Charsets.UTF_8))
    } catch (_: Exception) { PlayHistory() }).also { historyLoaded = it }

    /** Bumped on every change, so screens can tell whether their history-based rows are stale. */
    var historyVersion = 0
        private set

    fun recordPlay(s: Song) {
        if (s.externalUri != null || s.id < 0) return
        history.record(s.id, s.path, System.currentTimeMillis())
        saveHistory()
    }

    fun clearHistory() { history.clear(); saveHistory() }

    private fun saveHistory() {
        historyVersion++
        val out = historyFile.startWrite()
        try {
            out.write(history.encode().toByteArray(Charsets.UTF_8))
            historyFile.finishWrite(out)
        } catch (e: Exception) {
            historyFile.failWrite(out)
        }
    }
    var playlists: MutableList<Playlist> = ArrayList()
        private set

    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.getSharedPreferences("state", Context.MODE_PRIVATE)
        playlistFile = AtomicFile(File(ctx.filesDir, "playlists.json"))
        historyFile = AtomicFile(File(ctx.filesDir, "history.tsv"))
        playlists = try {
            PlaylistCodec.decode(String(playlistFile.readFully(), Charsets.UTF_8))
        } catch (_: Exception) { ArrayList() }
    }

    fun savePlaylists() {
        val out = playlistFile.startWrite()
        try {
            out.write(PlaylistCodec.encode(playlists).toByteArray(Charsets.UTF_8))
            playlistFile.finishWrite(out)
        } catch (e: Exception) {
            playlistFile.failWrite(out)
        }
    }

    fun newPlaylist(name: String): Playlist {
        val p = Playlist((playlists.maxOfOrNull { it.id } ?: 0) + 1, name)
        playlists.add(p)
        savePlaylists()
        return p
    }

    fun addToPlaylist(p: Playlist, songs: List<Song>) {
        for (s in songs) if (s.externalUri == null) p.entries += Playlist.Entry(s.id, s.path)
        savePlaylists()
    }
}
