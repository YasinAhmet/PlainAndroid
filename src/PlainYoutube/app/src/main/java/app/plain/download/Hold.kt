package app.plain.download

import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText

/** Press & hold menus shared by every video row (Search tab pages, downloads list, Cached folder). */
object Hold {
    /**
     * Visit channel · Watch · Listen · Download… · Add to play queue · Add to list… · Select, then [extra].
     * [album]: the list / playlist the row belongs to (album folder for audio).
     */
    fun video(act: MainActivity, item: Item, album: String?, onSelect: (() -> Unit)?, extra: List<Pair<String, () -> Unit>> = emptyList()) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += "Visit channel" to { visitChannel(act, item.id, item.channelId, item.channel) }
        items += "Watch" to { PlayerActivity.stream(act, item.id, item.title, item.channel, PlayMode.WATCH, album = album) }
        items += "Listen" to { PlayerActivity.stream(act, item.id, item.title, item.channel, PlayMode.LISTEN, album = album) }
        items += "Download…" to { act.openVideo(item.id) }
        items += "Add to play queue" to { enqueue(act, listOf(PlayQueue.of(item, null, album))) }
        items += "Add to list…" to { addToList(act, listOf(item)) }
        if (onSelect != null) items += "Select" to onSelect
        items += extra
        act.menu(item.title.ifEmpty { item.id }, items)
    }

    fun enqueue(act: MainActivity, e: List<QEntry>) {
        if (e.isEmpty()) return
        val q = Store.queue().append(e)
        Store.setQueue(q)
        act.toast(if (e.size == 1) "Added to the play queue (${q.items.size})" else "${e.size} added to the play queue (${q.items.size})")
    }

    /** Opens the channel page; looks the channel up from the video when the row doesn't carry it. */
    fun visitChannel(act: MainActivity, videoId: String, channelId: String?, name: String) {
        if (channelId != null) { act.openChannel(channelId, name); return }
        act.toast("Finding the channel…")
        Thread({
            val id = try { InnerTube.info(videoId, Net.network(act, Store.wifiOnly), order = Clients.PLAY_ORDER).channelId } catch (_: Exception) { null }
            act.runOnUiThread { if (!act.isDestroyed) { if (id != null) act.openChannel(id, name) else act.toast("Couldn't find the channel") } }
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
            act.toast(if (n == 0) "Already in \"${l.name}\"" else "Added $n to \"${l.name}\"")
            done(l)
        }
        act.menu("Add to list", lists.map { l -> "${l.name}  (${l.items.size})" to { into(l) } } + ("New list…" to {
            newList(act) { l -> into(l) }
        }))
    }

    fun newList(act: MainActivity, created: (VList) -> Unit) {
        val box = nameBox(act, "")
        AlertDialog.Builder(act).setTitle("New list").setView(box)
            .setPositiveButton("Create") { _, _ ->
                val (all, l) = VideoLists.create(Store.lists(), box.text.toString(), System.currentTimeMillis())
                Store.setLists(all); created(l)
            }.setNegativeButton("Cancel", null).show()
    }

    fun rename(act: MainActivity, l: VList, done: () -> Unit) {
        val box = nameBox(act, l.name)
        AlertDialog.Builder(act).setTitle("Rename list").setView(box)
            .setPositiveButton("Rename") { _, _ -> Store.setLists(VideoLists.rename(Store.lists(), l.id, box.text.toString())); done() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun nameBox(act: MainActivity, text: String) = EditText(act).apply {
        setText(text); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        hint = "List name"; setTextColor(C.TEXT); setHintTextColor(C.LABEL)
        setPadding(act.dp(20), act.dp(12), act.dp(20), act.dp(12))
    }
}
