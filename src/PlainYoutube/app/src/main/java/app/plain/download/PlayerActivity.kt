package app.plain.download

import android.content.Context

/** What to play (a row, a download, a Cached entry, or the play queue). */
data class PlayReq(
    val id: String = "",
    val title: String = "",
    val channel: String = "",
    val mode: PlayMode? = null,
    val musicLink: Boolean = false,
    val album: String? = null,
    val uri: String? = null, // a saved file
    val audio: Boolean = false,
    val queue: Boolean = false,
)

/**
 * Entry points kept from the old separate player screen (2.3: the player lives in MainActivity's overlay, see
 * [PlayerPanel]). Every caller is MainActivity or one of its screens.
 */
object PlayerActivity {
    private fun host(ctx: Context): MainActivity? = ctx as? MainActivity

    /** [album]: the playlist it was started from (album folder hint for audio). */
    fun stream(ctx: Context, id: String, title: String, channel: String, mode: PlayMode? = null, musicLink: Boolean = false, album: String? = null) {
        host(ctx)?.player?.open(PlayReq(id, title, channel, mode, musicLink, album))
    }

    /** Play the saved play queue from its current entry (next/previous, auto-advance). */
    fun queue(ctx: Context) { host(ctx)?.player?.open(PlayReq(queue = true)) }

    fun local(ctx: Context, j: Job) {
        host(ctx)?.player?.open(PlayReq(j.videoId, j.title, j.channel, uri = j.uri, audio = j.kind == Kind.AUDIO))
    }
}
