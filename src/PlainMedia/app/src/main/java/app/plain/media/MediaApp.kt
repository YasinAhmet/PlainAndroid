package app.plain.media

import android.app.Application
import app.localmusic.Player
import app.plain.download.YoutubeApp
import app.plain.hub.Hub
import app.plain.hub.Tab
import app.plain.migrate.Migrate

/** Plain Media (formerly Plain Youtube): Music (default) and Youtube tabs; Spotify playlists are a section of the Youtube tab (3.2). */
class MediaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.setup(this, "Plain Media", listOf(
            Tab("music", "Music", "Müzik", "app.localmusic.MainActivity",
                import = Migrate.Spec("app.localmusic", "Plain Music",
                    "its playlists, play history, queue and settings (your songs stay where they are)",
                    "çalma listelerini, çalma geçmişini, kuyruğunu ve ayarlarını (şarkılarınız yerinde kalır)")),
            Tab("youtube", "Youtube", "Youtube", "app.plain.download.MainActivity"),
        ), accent = 0xFFE8A447.toInt())
        YoutubeApp.init(this)
        // Downloaded YouTube / Spotify playlists and Lists become Music playlists (3.5): the lists saved before that get
        // their links once, when the Music tab first loads its library.
        app.localmusic.LinkedPlaylists.prepare = { ctx -> app.plain.download.PlaylistLinks.backfillOnce(ctx) }
        // The music player restores its queue at start; with old Plain Music data still to copy, it starts later
        // (MainActivity / PlaybackService call Player.init themselves), so it never reads an empty state first.
        if (!Migrate.needsDecision(this, Hub.tab("music")!!)) Player.init(this)
    }
}
