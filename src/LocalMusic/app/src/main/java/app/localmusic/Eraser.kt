package app.localmusic

import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.Settings
import app.plain.lang.PlainLang
import app.plain.lang.tr

/**
 * Erasing from LocalMusic: one MediaStore.createTrashRequest per action (one Android consent dialog), or no dialog at all
 * when the user granted "Media management" (MANAGE_MEDIA, Android 12+) — then an 8 s Undo bar instead.
 * Nothing is deleted for good unless the user picks Delete now / Empty trash (or, with Media management, low storage).
 */
class Eraser(private val act: MainActivity) {
    companion object {
        const val REQ_ERASE = 301
        const val REQ_UNDO = 302
        const val REQ_PURGE = 303

        fun canManage(ctx: Context) = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(ctx)

        /** Opens the "Media management" special-access page for this app (Android 12+). */
        fun manageMediaIntent(ctx: Context): Intent =
            Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData(Uri.parse("package:" + ctx.packageName))

        fun freeBytes(): Long = runCatching { StatFs(Environment.getExternalStorageDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)

        /** Everything in Android's trash that LocalMusic shows: audio, and Plain Youtube's videos (if videos may be read). */
        fun queryTrash(ctx: Context): List<TrashItem> {
            val out = ArrayList<TrashItem>()
            fun q(uri: Uri, video: Boolean) {
                val args = Bundle().apply {
                    putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, MediaStore.MediaColumns.DATE_EXPIRES + " ASC")
                    if (video) {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?")
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(Videos.DIR + "%"))
                    }
                }
                runCatching {
                    ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.VOLUME_NAME, MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_EXPIRES), args, null)?.use { c ->
                        while (c.moveToNext()) {
                            val rel = c.getString(2) ?: ""
                            if (video && !Videos.isLibraryVideo(rel)) continue
                            out += TrashItem(c.getLong(0), c.getString(1) ?: "", video, rel + TrashMath.displayName(c.getString(3) ?: ""), c.getLong(4), c.getLong(5))
                        }
                    }
                }
            }
            q(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), false)
            if (Library.hasVideoPermission(ctx)) q(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), true)
            return out
        }
    }

    private var pending: EraseSet? = null
    private var undoable: EraseSet? = null
    private var purging: List<TrashItem> = emptyList()

    /** Confirm with count and size, then trash the whole set with one request. [strong] asks twice (packs, big sets). */
    fun confirm(set: EraseSet, what: String, strong: Boolean = false) {
        if (set.isEmpty()) { act.toast(tr("Nothing to erase", "Silinecek bir şey yok")); return }
        val big = strong || set.count >= 50
        val msg = "${set.summary}" + (if (set.artists > 1) " · ${Text.count(set.artists, "artist")}" else "") +
            tr("\n\nThey go to Android's trash: restore them from ⋮ → Trash within 30 days. After that Android deletes them for good.",
                "\n\nAndroid'in çöp kutusuna gider: 30 gün içinde ⋮ → Çöp kutusu'ndan geri yükleyebilirsiniz. Sonra Android onları kalıcı olarak siler.") +
            "\n\n" + set.preview()
        val title = tr("Erase $what?", what.replaceFirstChar { it.titlecase(PlainLang.locale) } + " silinsin mi?")
        AlertDialog.Builder(act).setTitle(title).setMessage(msg)
            .setPositiveButton(if (big) tr("Erase ${set.count}…", "${set.count} öğeyi sil…") else tr("Erase", "Sil")) { _, _ ->
                if (!big) send(set)
                else AlertDialog.Builder(act).setTitle(tr("Really erase ${set.count} files?", "${set.count} dosya gerçekten silinsin mi?"))
                    .setMessage("${set.summary}" + (if (set.artists > 1) tr(" from ${Text.count(set.artists, "artist")}", " (${Text.count(set.artists, "artist")})") else "") +
                        tr(" go to the trash.", " çöp kutusuna gider."))
                    .setPositiveButton(tr("Erase all ${set.count}", "Tümünü sil (${set.count})")) { _, _ -> send(set) }.setNegativeButton(tr("Cancel", "İptal"), null).show()
            }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun send(set: EraseSet) {
        pending = set
        launch(REQ_ERASE) { MediaStore.createTrashRequest(act.contentResolver, set.songs.map { Library.volumeUri(it) }, true) }
    }

    private fun launch(req: Int, make: () -> android.app.PendingIntent) {
        try {
            @Suppress("DEPRECATION")
            act.startIntentSenderForResult(make().intentSender, req, null, 0, 0, 0)
        } catch (e: Exception) {
            act.toast(tr("Android refused: ${e.message}", "Android reddetti: ${e.message}"))
        }
    }

    /** Deletes [items] from the trash for good (consent dialog unless Media management is granted). */
    fun deleteForGood(items: List<TrashItem>, req: Int) =
        launch(req) { MediaStore.createDeleteRequest(act.contentResolver, items.map { Library.volumeUri(it) }) }

    fun restore(items: List<TrashItem>, req: Int) =
        launch(req) { MediaStore.createTrashRequest(act.contentResolver, items.map { Library.volumeUri(it) }, false) }

    /** Result of an Android consent request; false if it was not ours. */
    fun onResult(req: Int, ok: Boolean): Boolean {
        when (req) {
            REQ_ERASE -> {
                val set = pending ?: return true
                pending = null
                if (!ok) return true
                Player.skipErased(set.ids) // erased the song that plays: on to the next one
                act.endSelection()
                act.loadLibrary()
                if (canManage(act)) {
                    undoable = set
                    act.showUndo(tr("Erased ${set.summary}", "Silindi: ${set.summary}")) { undo() }
                } else {
                    act.toast(tr("Moved to the trash · ⋮ → Trash to restore", "Çöp kutusuna taşındı · geri yüklemek için ⋮ → Çöp kutusu"))
                    offerMediaManagement()
                }
            }
            REQ_UNDO -> { act.loadLibrary(); if (ok) act.toast(tr("Restored", "Geri yüklendi")) }
            REQ_PURGE -> {
                if (ok) Text.bytes(purging.sumOf { it.size }).let { b ->
                    act.toast(tr("Low storage: deleted $b of the oldest trash for good", "Depolama az: çöp kutusundaki en eski $b kalıcı olarak silindi"))
                }
                purging = emptyList()
            }
            else -> return false
        }
        return true
    }

    private fun undo() {
        val set = undoable ?: return
        undoable = null
        launch(REQ_UNDO) { MediaStore.createTrashRequest(act.contentResolver, set.songs.map { Library.volumeUri(it) }, false) }
    }

    /** Once: Android asks every time unless "Media management" is allowed for LocalMusic. */
    private fun offerMediaManagement() {
        if (Build.VERSION.SDK_INT < 31 || Store.prefs.getBoolean("askedManageMedia", false)) return
        Store.prefs.edit().putBoolean("askedManageMedia", true).apply()
        AlertDialog.Builder(act).setTitle(tr("Skip Android's confirmation?", "Android'in onayı atlansın mı?"))
            .setMessage(tr("Android asks before every erase. If you allow “Media management” for LocalMusic, erasing is immediate " +
                "(with an Undo bar) and the trash can be emptied without asking. You can change this any time in Settings " +
                "or from ⋮ → Media management.",
                "Android her silmeden önce sorar. LocalMusic için “Medya yönetimi”ne izin verirseniz silme hemen olur " +
                "(Geri al çubuğuyla) ve çöp kutusu sormadan boşaltılabilir. Bunu istediğiniz zaman Ayarlar'dan " +
                "veya ⋮ → Medya yönetimi'nden değiştirebilirsiniz."))
            .setPositiveButton(tr("Open settings", "Ayarları aç")) { _, _ -> runCatching { act.startActivity(manageMediaIntent(act)) } }
            .setNegativeButton(tr("Not now", "Şimdi değil"), null).show()
    }

    /** Low storage on open/resume (no background job). With Media management: delete the oldest trash until 3 GB are free. */
    fun onLowSpace(free: Long, trash: List<TrashItem>): Boolean {
        if (!canManage(act) || trash.isEmpty()) return false
        purging = TrashMath.toPurge(trash, free)
        if (purging.isEmpty()) return false
        deleteForGood(purging, REQ_PURGE)
        return true
    }
}
