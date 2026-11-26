package app.localmusic

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

/** Describes an audio file handed over by another app (content:// or file:// only — never the network). */
object External {
    fun describe(ctx: Context, uri: Uri): Song {
        // A MediaStore audio URI of a song we already know: play it as a library song.
        if (uri.authority == MediaStore.AUTHORITY && uri.pathSegments.contains("audio")) {
            runCatching { ContentUris.parseId(uri) }.getOrNull()?.let { id -> Library.index.byId[id]?.let { return it } }
        }
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio"
        var size = 0L
        if (uri.scheme == "content") runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        var raw = RawTags()
        var dur = 0L
        val m = MediaMetadataRetriever()
        try {
            m.setDataSource(ctx, uri)
            fun k(key: Int) = m.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            raw = RawTags(title = k(MediaMetadataRetriever.METADATA_KEY_TITLE), artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM))
            dur = k(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        } catch (_: Exception) {
        } finally {
            runCatching { m.release() }
        }
        val t = Naming.resolve(raw, name)
        return Song(
            id = Long.MIN_VALUE, title = t.title, artist = t.artist, album = t.album, albumArtist = "", albumId = -1, genre = "",
            track = 0, disc = 1, year = 0, durationMs = dur, relPath = "", name = name, size = size, dateAdded = 0,
            externalUri = uri.toString(),
        )
    }
}
