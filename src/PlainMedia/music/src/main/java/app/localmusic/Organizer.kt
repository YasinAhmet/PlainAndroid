package app.localmusic

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import app.plain.lang.tr
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android side of the organiser: read every audio file (MediaStore + MediaMetadataRetriever), hash possible
 * duplicates, and later carry out a plan the user confirmed. The rules themselves live in Plan.kt (unit-tested).
 */
object Organizer {
    class Scan(val files: List<FileInfo>, val uris: Map<Long, Uri>)

    /** [withPlainYoutube]: Plain Youtube's saved music (not its cache) counts as music too ("Remove duplicates"). */
    fun scan(ctx: Context, withPlainYoutube: Boolean = false, progress: (String) -> Unit): Scan {
        val cr = ctx.contentResolver
        val cols = mutableListOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.VOLUME_NAME, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.MIME_TYPE, MediaStore.Audio.Media.BITRATE, MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM_ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.TRACK, MediaStore.Audio.Media.DISC_NUMBER, MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.IS_RINGTONE, MediaStore.Audio.Media.IS_NOTIFICATION, MediaStore.Audio.Media.IS_ALARM,
            MediaStore.Audio.Media.IS_PODCAST, MediaStore.Audio.Media.IS_AUDIOBOOK,
        )
        if (Build.VERSION.SDK_INT >= 31) cols += MediaStore.Audio.Media.IS_RECORDING

        class Row(val id: Long, val uri: Uri, val rel: String, val name: String, val size: Long, val dur: Long, val mime: String,
                  val bitrate: Int, val store: RawTags, val skip: String?)
        val rows = ArrayList<Row>()
        progress(tr("Listing audio files…", "Ses dosyaları listeleniyor…"))
        cr.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), cols.toTypedArray(), null, null, null)?.use { c ->
            fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
            fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
            fun b(n: String) = c.getInt(c.getColumnIndexOrThrow(n)) != 0
            while (c.moveToNext()) {
                val id = l(MediaStore.Audio.Media._ID)
                val vol = s(MediaStore.Audio.Media.VOLUME_NAME) ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
                val rel = s(MediaStore.Audio.Media.RELATIVE_PATH) ?: ""
                val name = s(MediaStore.Audio.Media.DISPLAY_NAME) ?: continue
                val dur = l(MediaStore.Audio.Media.DURATION)
                val title = s(MediaStore.Audio.Media.TITLE)
                rows += Row(
                    id, MediaStore.Audio.Media.getContentUri(vol, id), rel, name, l(MediaStore.Audio.Media.SIZE), dur,
                    s(MediaStore.Audio.Media.MIME_TYPE) ?: "", c.getInt(c.getColumnIndexOrThrow(MediaStore.Audio.Media.BITRATE)),
                    RawTags(
                        // MediaStore puts the file name in TITLE when there is no tag: treat that as "no title"
                        title = title?.takeUnless { it == name.substringBeforeLast('.') },
                        artist = s(MediaStore.Audio.Media.ARTIST), albumArtist = s(MediaStore.Audio.Media.ALBUM_ARTIST),
                        album = s(MediaStore.Audio.Media.ALBUM)?.takeUnless { a -> rel.trimEnd('/').endsWith(a) }, // folder name used as album
                        track = s(MediaStore.Audio.Media.TRACK), disc = s(MediaStore.Audio.Media.DISC_NUMBER), genre = s(MediaStore.Audio.Media.GENRE),
                    ),
                    (if (withPlainYoutube && !PlainYoutube.isCached(rel)) null else PlainYoutube.organiserSkip(rel)) ?: Classify.skipReason(
                        rel, name, dur, ringtone = b(MediaStore.Audio.Media.IS_RINGTONE), notification = b(MediaStore.Audio.Media.IS_NOTIFICATION),
                        alarm = b(MediaStore.Audio.Media.IS_ALARM), podcast = b(MediaStore.Audio.Media.IS_PODCAST),
                        audiobook = b(MediaStore.Audio.Media.IS_AUDIOBOOK),
                        recording = Build.VERSION.SDK_INT >= 31 && b(MediaStore.Audio.Media.IS_RECORDING),
                        hasTrackNumber = Naming.parseTrack(s(MediaStore.Audio.Media.TRACK)) > 0,
                    ),
                )
            }
        }

        // Tags straight from the files.
        val files = ArrayList<FileInfo>(rows.size)
        rows.forEachIndexed { i, r ->
            if (i % 10 == 0) progress(tr("Reading tags ${i + 1} / ${rows.size}", "Etiketler okunuyor ${i + 1} / ${rows.size}"))
            var raw = r.store
            var bitrate = r.bitrate
            if (r.skip == null) {
                val m = MediaMetadataRetriever()
                try {
                    m.setDataSource(ctx, r.uri)
                    fun k(key: Int) = m.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
                    raw = RawTags(
                        title = k(MediaMetadataRetriever.METADATA_KEY_TITLE),
                        artist = k(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        albumArtist = k(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                        album = k(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                        track = k(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                        disc = k(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
                        genre = k(MediaMetadataRetriever.METADATA_KEY_GENRE) ?: r.store.genre,
                    )
                    k(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let { bitrate = it }
                } catch (_: Exception) {
                    // unreadable by the retriever: keep MediaStore's view of the tags
                } finally {
                    runCatching { m.release() }
                }
            }
            files += FileInfo(r.id, r.rel, r.name, r.size, r.dur, r.mime, bitrate, Naming.resolve(raw, r.name, r.rel), r.skip)
        }

        // Content hashes, only where sizes collide.
        val toHash = Dedupe.needsHash(files.filter { it.skipReason == null }).map { it.id }.toHashSet()
        var done = 0
        val uris = rows.associate { it.id to it.uri }
        val hashed = files.map { f ->
            if (f.id !in toHash) f else {
                done++
                progress(tr("Comparing contents $done / ${toHash.size}", "İçerikler karşılaştırılıyor $done / ${toHash.size}"))
                f.copy(hash = md5(ctx, uris.getValue(f.id)))
            }
        }
        return Scan(hashed, uris)
    }

    private fun md5(ctx: Context, uri: Uri): String? {
        return try {
            val d = MessageDigest.getInstance("MD5")
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use {
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            d.digest().joinToString("") { b -> "%02x".format(b) }
        } catch (_: Exception) { null }
    }

    class Result(val moved: List<Pair<Plan.Move, String>>, val failed: List<Pair<Plan.Move, String>>)

    /** Carry out the moves (the user has granted write access via MediaStore.createWriteRequest). */
    fun applyMoves(ctx: Context, moves: List<Plan.Move>, uris: Map<Long, Uri>, progress: (String) -> Unit): Result {
        val cr = ctx.contentResolver
        val ok = ArrayList<Pair<Plan.Move, String>>()
        val bad = ArrayList<Pair<Plan.Move, String>>()
        moves.forEachIndexed { i, m ->
            if (i % 5 == 0) progress(tr("Moving ${i + 1} / ${moves.size}", "Taşınıyor ${i + 1} / ${moves.size}"))
            val uri = uris[m.file.id] ?: run { bad += m to tr("no uri", "uri yok"); return@forEachIndexed }
            try {
                val v = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, m.toDir)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, m.toName)
                }
                val n = cr.update(uri, v, null, null)
                val now = currentPath(ctx, uri)
                if (n > 0 && now != null) ok += m to now else bad += m to tr("not updated (now: ${now ?: "?"})", "güncellenmedi (şu an: ${now ?: "?"})")
            } catch (e: Exception) {
                bad += m to (e.message ?: e.javaClass.simpleName)
            }
        }
        return Result(ok, bad)
    }

    fun currentPath(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) (c.getString(0) ?: "") + (c.getString(1) ?: "") else null
        }
    } catch (_: Exception) { null }

    /** Ids among [ids] that MediaStore now reports as trashed. */
    fun trashedIds(ctx: Context, ids: Collection<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<Long>()
        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
        try {
            ctx.contentResolver.query(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), arrayOf(MediaStore.Audio.Media._ID), args, null)?.use { c ->
                while (c.moveToNext()) c.getLong(0).let { if (it in ids) out += it }
            }
        } catch (_: Exception) {}
        return out
    }

    /** A plain-text report (also saved under Android/data/<app>/files so it can be read later). */
    fun report(ctx: Context, plan: Plan, result: Result?, trashed: Set<Long>, trashAsked: Boolean): String {
        val sb = StringBuilder()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date())
        sb.appendLine(tr("LocalMusic organiser report — $stamp", "LocalMusic düzenleme raporu — $stamp")).appendLine()
        val moved = result?.moved.orEmpty()
        val failed = result?.failed.orEmpty()
        sb.appendLine(tr("Moved: ${moved.size}   Failed: ${failed.size}   Already in place: ${plan.inPlace.size}   Left where they were (your choice): ${plan.keptByUser.size}",
            "Taşınan: ${moved.size}   Başarısız: ${failed.size}   Zaten yerinde: ${plan.inPlace.size}   Yerinde bırakılan (sizin seçiminiz): ${plan.keptByUser.size}"))
        val removedOk = plan.removals.filter { it.file.id in trashed }
        val freed = Text.bytes(removedOk.sumOf { it.file.size })
        sb.appendLine(tr("Duplicates sent to trash: ${removedOk.size} of ${plan.removals.size} ($freed freed)",
            "Çöp kutusuna gönderilen kopyalar: ${removedOk.size} / ${plan.removals.size} ($freed boşaldı)") +
            if (trashAsked && removedOk.size < plan.removals.size) tr(" — the rest stayed where they were", " — kalanlar yerinde kaldı") else "")
        sb.appendLine(tr("Not music, left alone: ${plan.skipped.size}", "Müzik değil, dokunulmadı: ${plan.skipped.size}")).appendLine()

        val targets = moved.map { it.first.file } + plan.inPlace + plan.keptByUser
        val albums = plan.albumFolders
        val artists = albums.map { it.substringBefore('/').lowercase() }.distinct()
        val genres = targets.map { it.tags.genre }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        sb.appendLine(tr("Categories: ${artists.size} artists, ${albums.size} albums, ${genres.size} genres",
            "Kategoriler: ${artists.size} sanatçı, ${albums.size} albüm, ${genres.size} tür"))
        genres.entries.sortedByDescending { it.value }.forEach { sb.appendLine(tr("  genre ${it.key}: ${it.value}", "  tür ${it.key}: ${it.value}")) }
        sb.appendLine()
        if (removedOk.isNotEmpty()) {
            sb.appendLine(tr("== Duplicates removed (in Android trash for 30 days; restore from ⋮ → Recently removed)",
                "== Kaldırılan kopyalar (30 gün Android çöp kutusunda; ⋮ → Son silinenler'den geri yükleyin)"))
            removedOk.forEach { sb.appendLine(tr("  ${it.file.path}   [kept ${it.keep.path}]", "  ${it.file.path}   [tutulan: ${it.keep.path}]")) }
            sb.appendLine()
        }
        if (failed.isNotEmpty()) {
            sb.appendLine(tr("== Failed moves", "== Başarısız taşımalar"))
            failed.forEach { (m, why) -> sb.appendLine("  ${m.file.path} -> ${m.toPath}: $why") }
            sb.appendLine()
        }
        if (moved.isNotEmpty()) {
            sb.appendLine(tr("== Moved", "== Taşınanlar"))
            moved.forEach { (m, now) -> sb.appendLine("  ${m.file.path} -> $now") }
            sb.appendLine()
        }
        if (plan.skipped.isNotEmpty()) {
            sb.appendLine(tr("== Left alone (not music)", "== Dokunulmayanlar (müzik değil)"))
            plan.skipped.forEach { sb.appendLine("  ${it.path}  (${it.skipReason})") }
        }
        val text = sb.toString()
        try {
            val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            File(dir, "organize-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt").writeText(text)
            // machine-readable log so the moves could be undone later: id, old path, new path
            File(dir, "moves-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".tsv")
                .writeText(moved.joinToString("\n") { (m, now) -> "${m.file.id}\t${m.file.path}\t$now" })
        } catch (_: Exception) {}
        return text
    }

    /** Short text for the plan screen to show what each file is. */
    fun describe(f: FileInfo): String {
        val kbps = if (f.lossless) tr("lossless", "kayıpsız") else "${(f.effectiveBitrate + 500) / 1000} kbps"
        return "${f.ext.uppercase(Locale.ROOT)} · $kbps · ${Text.bytes(f.size)} · ${Text.time(f.durationMs)}"
    }
}
