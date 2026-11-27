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

    fun all(c: Context): List<Note> = synchronized(lock) {
        try {
            val a = JSONArray(file(c).takeIf { it.exists() }?.readText() ?: "[]")
            (0 until a.length()).map { Note.of(a.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun get(c: Context, id: Long) = all(c).firstOrNull { it.id == id }

    private fun write(c: Context, list: List<Note>) {
        val a = JSONArray()
        list.forEach { a.put(it.json()) }
        val tmp = File(c.filesDir, "notes.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(file(c))) { file(c).delete(); tmp.renameTo(file(c)) }
    }

    /** A free id (ms timestamps, nudged when two notes are made in the same millisecond). */
    fun newId(c: Context): Long {
        val used = all(c).map { it.id }.toSet()
        var id = System.currentTimeMillis()
        while (id in used) id++
        return id
    }

    /** Adds or replaces [n] and sets its reminder. */
    fun put(c: Context, n: Note) {
        synchronized(lock) { write(c, all(c).filter { it.id != n.id } + n) }
        schedule(c, n)
        if (n.remindAt == 0L || !n.fired) cancelNotification(c, n)
        listener?.invoke()
    }

    fun delete(c: Context, n: Note) {
        synchronized(lock) { write(c, all(c).filter { it.id != n.id }) }
        audioFile(c, n)?.delete()
        cancelAlarm(c, n)
        cancelNotification(c, n)
        listener?.invoke()
    }

    /** New time (0 = none) for a note's reminder. */
    fun remind(c: Context, n: Note, at: Long) = put(c, n.copy(remindAt = at, fired = false))

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
        synchronized(lock) { write(c, all(c).map { if (it.id == id) f else it }) }
        notify(c, f)
        listener?.invoke()
    }

    /** After a restart or update: alarms are gone, so set them again; reminders missed while off show now. */
    fun restore(c: Context) {
        val now = System.currentTimeMillis()
        for (n in all(c)) when {
            n.remindAt <= 0 -> {}
            n.fired -> notify(c, n)
            n.remindAt <= now -> fire(c, n.id)
            else -> schedule(c, n)
        }
    }

    // ------------------------------------------------------------------ notifications

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun nid(n: Note) = (n.id xor (n.id ushr 32)).toInt()
    private fun cancelNotification(c: Context, n: Note) { nm(c)?.cancel(nid(n)) }

    fun canNotify(c: Context) = nm(c)?.areNotificationsEnabled() != false

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

    private fun notify(c: Context, n: Note) {
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
        nm(c)?.notify(nid(n), b.build())
    }
}
