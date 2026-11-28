package app.plain.download

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import app.plain.lang.tr

/** Press & hold menus shared by every video row (Search tab pages, downloads list, Cached folder). */
object Hold {
    /**
     * Visit channel · Watch · Listen · Download… · Add to play queue · Add to list… · Select, then [extra].
     * [album]: the list / playlist the row belongs to (album folder for audio).
     */
    fun video(act: MainActivity, item: Item, album: String?, onSelect: (() -> Unit)?, extra: List<Pair<String, () -> Unit>> = emptyList()) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += tr("Visit channel", "Kanala git") to { visitChannel(act, item.id, item.channelId, item.channel) }
        items += tr("Watch", "İzle") to { PlayerActivity.stream(act, item.id, item.title, item.channel, PlayMode.WATCH, album = album) }
        items += tr("Listen", "Dinle") to { PlayerActivity.stream(act, item.id, item.title, item.channel, PlayMode.LISTEN, album = album) }
        items += tr("Download…", "İndir…") to { act.openVideo(item.id) }
        items += tr("Add to play queue", "Çalma sırasına ekle") to { enqueue(act, listOf(PlayQueue.of(item, null, album))) }
        items += tr("Add to list…", "Listeye ekle…") to { addToList(act, listOf(item)) }
        items += tr("Share link…", "Bağlantıyı paylaş…") to { shareLink(act, item.id, item.title) }
        if (onSelect != null) items += tr("Select", "Seç") to onSelect
        items += extra
        act.menu(item.title.ifEmpty { item.id }, items)
    }

    /** "https://youtu.be/<id>", or "…?t=<seconds>" to open at [atSec] (YouTube's own start-time parameter). */
    fun link(videoId: String, atSec: Long = 0): String = "https://youtu.be/$videoId" + if (atSec > 0) "?t=$atSec" else ""

    /** Android share sheet with the video's YouTube link (title as subject), at [atSec] when > 0. Nothing is uploaded by the app. */
    fun shareLink(ctx: android.content.Context, videoId: String, title: String, atSec: Long = 0) {
        val url = link(videoId, atSec)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, if (title.isBlank()) url else "$title\n$url")
            if (title.isNotBlank()) putExtra(android.content.Intent.EXTRA_SUBJECT, title)
        }
        val chooser = android.content.Intent.createChooser(send, tr("Share link", "Bağlantıyı paylaş"))
        if (ctx !is android.app.Activity) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) // the player panel's context
        try { ctx.startActivity(chooser) }
        catch (_: Exception) { android.widget.Toast.makeText(ctx, tr("No app to share with", "Paylaşacak uygulama yok"), android.widget.Toast.LENGTH_SHORT).show() }
    }

    fun enqueue(act: MainActivity, e: List<QEntry>) {
        if (e.isEmpty()) return
        val q = Store.queue().append(e)
        Store.setQueue(q)
        act.toast(if (e.size == 1) tr("Added to the play queue (${q.items.size})", "Çalma sırasına eklendi (${q.items.size})") else tr("${e.size} added to the play queue (${q.items.size})", "${e.size} tanesi çalma sırasına eklendi (${q.items.size})"))
    }

    /** Opens the channel page; looks the channel up from the video when the row doesn't carry it. */
    fun visitChannel(act: MainActivity, videoId: String, channelId: String?, name: String) {
        if (channelId != null) { act.openChannel(channelId, name); return }
        act.toast(tr("Finding the channel…", "Kanal bulunuyor…"))
        Thread({
            val id = try { InnerTube.info(videoId, Net.network(act, Store.wifiOnly), order = Clients.PLAY_ORDER).channelId } catch (_: Exception) { null }
            act.runOnUiThread { if (!act.isDestroyed) { if (id != null) act.openChannel(id, name) else act.toast(tr("Couldn't find the channel", "Kanal bulunamadı")) } }
        }, "channel-lookup").start()
    }

    /** "Add to list…": the existing local lists + New…; [done] gets the list used. */
    fun addToList(act: MainActivity, items: List<Item>, done: (VList) -> Unit = {}) {
        val vids = items.filter { it.isVideo }
        if (vids.isEmpty()) return
        val lists = Store.lists()
        fun into(l: VList) {
            val (all, n) = VideoLists.add(Store.lists(), l.id, vids)
            Store.setLists(all)
            act.toast(if (n == 0) tr("Already in \"${l.name}\"", "Zaten listede: \"${l.name}\"") else tr("Added $n to \"${l.name}\"", "$n öğe eklendi: \"${l.name}\""))
            done(l)
        }
        act.menu(tr("Add to list", "Listeye ekle"), lists.map { l -> "${l.name}  (${l.items.size})" to { into(l) } } + (tr("New list…", "Yeni liste…") to {
            newList(act) { l -> into(l) }
        }))
    }

    fun newList(act: MainActivity, created: (VList) -> Unit) {
        val box = nameBox(act, "")
        AlertDialog.Builder(act).setTitle(tr("New list", "Yeni liste")).setView(box)
            .setPositiveButton(tr("Create", "Oluştur")) { _, _ ->
                val (all, l) = VideoLists.create(Store.lists(), box.text.toString(), System.currentTimeMillis())
                Store.setLists(all); created(l)
            }.setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    fun rename(act: MainActivity, l: VList, done: () -> Unit) {
        val box = nameBox(act, l.name)
        AlertDialog.Builder(act).setTitle(tr("Rename list", "Listeyi yeniden adlandır")).setView(box)
            .setPositiveButton(tr("Rename", "Yeniden adlandır")) { _, _ -> Store.setLists(VideoLists.rename(Store.lists(), l.id, box.text.toString())); done() }
            .setNegativeButton(tr("Cancel", "İptal"), null).show()
    }

    private fun nameBox(act: MainActivity, text: String) = EditText(act).apply {
        setText(text); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        hint = tr("List name", "Liste adı"); setTextColor(C.TEXT); setHintTextColor(C.LABEL)
        setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
    }
}
