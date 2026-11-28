package app.plain.notes

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.plain.lang.PlainLang
import app.plain.lang.tr
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A note: text, a voice recording ([audio] = file name in files/notes/), or both (a caption). [remindAt] > 0: it
 * reminds then (a notification); [fired]: that has happened and it waits for Done or Snooze.
 */
data class Note(
    val id: Long,
    val text: String = "",
    val audio: String? = null,
    val audioMs: Long = 0,
    val created: Long = id,
    val remindAt: Long = 0,
    val fired: Boolean = false,
) {
    val title: String get() = text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        ?: tr("Voice note", "Sesli not") + if (audioMs > 0) " · " + When.duration(audioMs) else ""

    fun json(): JSONObject = JSONObject().put("id", id).put("text", text).put("created", created)
        .put("remindAt", remindAt).put("fired", fired).apply { if (audio != null) put("audio", audio).put("audioMs", audioMs) }

    companion object {
        fun of(o: JSONObject) = Note(
            id = o.getLong("id"),
            text = o.optString("text"),
            audio = o.optString("audio").ifEmpty { null },
            audioMs = o.optLong("audioMs"),
            created = o.optLong("created", o.getLong("id")),
            remindAt = o.optLong("remindAt"),
            fired = o.optBoolean("fired"),
        )
    }
}

/**
 * Notes on disk (files/notes.json, written whole and atomically; a few hundred notes at most) and their reminders:
 * one exact alarm per timed note ([NotesReceiver]), nothing else in the background.
 */
object Notes {
    private const val CH = "notes_reminders"
    private val lock = Any()

    /** The open Notes screen (main thread). */
    var listener: (() -> Unit)? = null

    fun dir(c: Context) = File(c.filesDir, "notes").apply { mkdirs() }
    fun audioFile(c: Context, n: Note) = n.audio?.let { File(dir(c), it) }
    private fun file(c: Context) = File(c.filesDir, "notes.json")

    /**
     * The notes on disk. One broken entry is skipped, not the whole list; null when the file exists but can't be read
     * at all (then a change must not write an empty list over it, see [change]).
     */
    private fun load(c: Context): List<Note>? {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try {
            val a = JSONArray(f.readText())
            (0 until a.length()).mapNotNull { i -> try { a.optJSONObject(i)?.let { Note.of(it) } } catch (_: Exception) { null } }
        } catch (_: Exception) { null }
    }

    fun all(c: Context): List<Note> = synchronized(lock) { load(c) ?: emptyList() }

    fun get(c: Context, id: Long) = all(c).firstOrNull { it.id == id }

    /** False when it couldn't be written (storage full…): the list on disk is then unchanged. */
    private fun write(c: Context, list: List<Note>): Boolean = try {
        val a = JSONArray()
        list.forEach { a.put(it.json()) }
        val text = a.toString()
        val tmp = File(c.filesDir, "notes.json.tmp")
        tmp.writeText(text)
        if (tmp.length() == 0L && text.isNotEmpty()) throw IOException("nothing written")
        // (never delete the old file first: if the second step failed too, every note would be gone)
        if (!tmp.renameTo(file(c))) { file(c).writeText(text); tmp.delete() }
        true
    } catch (_: Exception) { false }

    /**
     * Read, change and write the list in one go. A notes.json that can't be read is moved aside (notes.json.bad-<time>)
     * instead of being overwritten, so a damaged file never takes the notes with it.
     */
    private fun change(c: Context, fn: (List<Note>) -> List<Note>): Boolean = synchronized(lock) {
        val cur = load(c) ?: run {
            file(c).renameTo(File(c.filesDir, "notes.json.bad-${System.currentTimeMillis()}"))
            emptyList()
        }
        write(c, fn(cur))
    }

    /** A free id (ms timestamps, nudged when two notes are made in the same millisecond). */
    fun newId(c: Context): Long {
        val used = all(c).map { it.id }.toSet()
        var id = System.currentTimeMillis()
        while (id in used) id++
        return id
    }

    /** Adds or replaces [n] and sets its reminder. False when it couldn't be saved (nothing changed). */
    fun put(c: Context, n: Note): Boolean {
        if (!change(c) { l -> l.filter { it.id != n.id } + n }) return false
        schedule(c, n)
        if (n.remindAt == 0L || !n.fired) cancelNotification(c, n)
        listener?.invoke()
        return true
    }

    fun delete(c: Context, n: Note): Boolean {
        if (!change(c) { l -> l.filter { it.id != n.id } }) return false
        audioFile(c, n)?.delete()
        cancelAlarm(c, n)
        cancelNotification(c, n)
        listener?.invoke()
        return true
    }

    /** New time (0 = none) for a note's reminder; the note as it is now on disk (it may have changed meanwhile). */
    fun remind(c: Context, n: Note, at: Long): Boolean {
        val cur = get(c, n.id) ?: return false   // deleted meanwhile: not brought back
        return put(c, cur.copy(remindAt = at, fired = false))
    }

    fun snoozeMinutes(c: Context) = c.getSharedPreferences("notes", Context.MODE_PRIVATE).getInt("snooze", 10)

    // ------------------------------------------------------------------ alarms

    private fun alarmPi(c: Context, n: Note) = PendingIntent.getBroadcast(c, 0,
        Intent(c, NotesReceiver::class.java).setAction(NotesReceiver.REMIND).setData(Uri.parse("plainnote://${n.id}")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(c: Context, n: Note) {
        if (n.remindAt <= 0 || n.fired) { cancelAlarm(c, n); return }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPi(c, n)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.remindAt, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.remindAt, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, n.remindAt, pi)
        }
    }

    private fun cancelAlarm(c: Context, n: Note) {
        c.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(c, n))
    }

    /** The alarm went off: show the note. */
    fun fire(c: Context, id: Long) {
        val n = get(c, id) ?: return
        if (n.remindAt <= 0 || n.fired) return
        if (n.remindAt > System.currentTimeMillis() + 1500) { schedule(c, n); return }   // moved later meanwhile
        val f = n.copy(fired = true)
        change(c) { l -> l.map { if (it.id == id) f else it } }
        notify(c, f)
        listener?.invoke()
    }

    /**
     * After a restart or update: alarms are gone, so set them again; reminders missed while off show now. Ones that
     * already went off are shown again without a sound (an update must not buzz every old reminder once more).
     */
    fun restore(c: Context) {
        val now = System.currentTimeMillis()
        for (n in all(c)) when {
            n.remindAt <= 0 -> {}
            n.fired -> notify(c, n, quiet = true)
            n.remindAt <= now -> fire(c, n.id)
            else -> schedule(c, n)
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun nid(n: Note) = (n.id xor (n.id ushr 32)).toInt()
    private fun cancelNotification(c: Context, n: Note) { nm(c)?.cancel(nid(n)) }

    /** False when Android won't show reminders: notifications off, or the reminders channel switched off. */
    fun canNotify(c: Context) = nm(c)?.let { it.areNotificationsEnabled() && it.getNotificationChannel(CH)?.importance != NotificationManager.IMPORTANCE_NONE } != false

    fun channel(c: Context) {
        nm(c)?.createNotificationChannel(NotificationChannel(CH, tr("Note reminders", "Not hatırlatıcıları"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 250, 400)
        })
    }

    const val CHANNEL = CH

    private fun pi(c: Context, action: String, n: Note) = PendingIntent.getBroadcast(c, 0,
        Intent(c, NotesReceiver::class.java).setAction(action).setData(Uri.parse("plainnote://${n.id}")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Opens the Notes tab on this note (and plays it when it's a voice note), through the app's tab forwarder. */
    private fun openPi(c: Context, n: Note) = PendingIntent.getActivity(c, nid(n),
        Intent(NotesActivity.ACTION_SHOW).setClassName(c.packageName, "app.plain.hub.OpenNotes")
            .setData(Uri.parse("plainnote://${n.id}")).putExtra(NotesActivity.EXTRA_ID, n.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(c: Context, n: Note, quiet: Boolean = false) {
        PlainLang.init(c)
        channel(c)
        val rest = n.text.trim().lines().drop(1).joinToString("\n").trim()
        val body = when {
            n.audio != null && n.text.isNotBlank() -> tr("Voice note · ${When.duration(n.audioMs)}", "Sesli not · ${When.duration(n.audioMs)}") + if (rest.isNotEmpty()) "\n$rest" else ""
            n.audio != null -> tr("Tap to play", "Oynatmak için dokunun")
            else -> rest
        }
        val snooze = snoozeMinutes(c)
        val b = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.nts_ic_note)
            .setColor(C.ACCENT)
            .setContentTitle(n.title)
            .setContentText(body.ifEmpty { null })
            .setStyle(Notification.BigTextStyle().bigText(body.ifEmpty { n.title }))
            .setWhen(n.remindAt)
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openPi(c, n))
            .addAction(Notification.Action.Builder(null, tr("Done", "Tamam"), pi(c, NotesReceiver.DONE, n)).build())
            .addAction(Notification.Action.Builder(null, tr("Snooze $snooze min", "$snooze dk ertele"), pi(c, NotesReceiver.SNOOZE, n)).build())
        if (quiet) {
            b.setOnlyAlertOnce(true)   // still showing (an update): no second alert
            // after a restart it's new to Android: a group whose (never posted) summary alone may alert keeps it silent
            b.setGroup("app.plain.notes.quiet").setGroupAlertBehavior(Notification.GROUP_ALERT_SUMMARY)
        }
        try { nm(c)?.notify(nid(n), b.build()) } catch (_: Exception) {}
    }
}
