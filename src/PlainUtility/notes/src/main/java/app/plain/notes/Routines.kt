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
 * Routines on disk (files/routines.json: {"version":1,"routines":[…]}, written whole and atomically) and their
 * reminders: one exact alarm per routine, at its next occurrence (or a snooze), set again each time it goes off.
 * Nothing else runs in the background; the day's ticks reset by themselves (they belong to a date).
 */
object Routines {
    const val VERSION = 1
    private const val CH = "routines_reminders"
    const val CHANNEL = CH
    private val lock = Any()

    /** The open Routines screen (main thread). */
    var listener: (() -> Unit)? = null

    private fun file(c: Context) = File(c.filesDir, "routines.json")

    /** routines.json's text: the object form, or a bare array (read for safety). Null when it can't be read at all. */
    fun parse(text: String): List<Routine>? = try {
        val t = text.trim()
        val a = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).getJSONArray("routines")
        (0 until a.length()).mapNotNull { i -> try { a.optJSONObject(i)?.let { Routine.of(it) } } catch (_: Exception) { null } }
    } catch (_: Exception) { null }

    fun encode(list: List<Routine>): String =
        JSONObject().put("version", VERSION).put("routines", JSONArray().apply { list.forEach { put(it.json()) } }).toString()

    private fun load(c: Context): List<Routine>? {
        val f = file(c)
        if (!f.exists()) return emptyList()
        return try { parse(f.readText()) } catch (_: Exception) { null }
    }

    fun all(c: Context): List<Routine> = synchronized(lock) { load(c) ?: emptyList() }

    fun get(c: Context, id: Long) = all(c).firstOrNull { it.id == id }

    private fun write(c: Context, list: List<Routine>): Boolean = try {
        val text = encode(list)
        val tmp = File(c.filesDir, "routines.json.tmp")
        tmp.writeText(text)
        if (tmp.length() == 0L && text.isNotEmpty()) throw IOException("nothing written")
        if (!tmp.renameTo(file(c))) { file(c).writeText(text); tmp.delete() }
        true
    } catch (_: Exception) { false }

    /** Read, change, write; an unreadable file is moved aside (routines.json.bad-<time>), never overwritten. */
    private fun change(c: Context, fn: (List<Routine>) -> List<Routine>): Boolean = synchronized(lock) {
        val cur = load(c) ?: run {
            file(c).renameTo(File(c.filesDir, "routines.json.bad-${System.currentTimeMillis()}"))
            emptyList()
        }
        write(c, fn(cur))
    }

    fun newId(c: Context): Long {
        val used = all(c).map { it.id }.toSet()
        var id = System.currentTimeMillis()
        while (id in used) id++
        return id
    }

    /** Adds or replaces [r] and sets its next alarm. False when it couldn't be saved. */
    fun put(c: Context, r: Routine): Boolean {
        if (!change(c) { l -> if (l.any { it.id == r.id }) l.map { if (it.id == r.id) r else it } else l + r }) return false
        schedule(c, r)
        listener?.invoke()
        return true
    }

    fun delete(c: Context, r: Routine): Boolean {
        if (!change(c) { l -> l.filter { it.id != r.id } }) return false
        cancelAlarm(c, r)
        cancelNotification(c, r)
        listener?.invoke()
        return true
    }

    /** Ticks (or unticks) one of today's occurrences of the routine as it is on disk now. */
    fun mark(c: Context, id: Long, day: Int, min: Int, on: Boolean): Boolean {
        val cur = get(c, id) ?: return false
        if (min !in 0..1439) return false
        cancelNotification(c, cur)
        val snoozed = cur.snoozeAt > 0 && cur.snoozeDay == day && cur.snoozeMin == min
        return put(c, cur.mark(day, min, on).let { if (snoozed && on) it.copy(snoozeAt = 0, snoozeMin = -1, snoozeDay = 0) else it })
    }

    fun doneFromNotification(c: Context, id: Long, day: Int, min: Int) {
        val cur = get(c, id) ?: return
        cancelNotification(c, cur)
        // a tap on yesterday's notification doesn't tick today
        if (day == Sched.dayKey(System.currentTimeMillis())) mark(c, id, day, min, true)
    }

    fun snooze(c: Context, id: Long, day: Int, min: Int) {
        val cur = get(c, id) ?: return
        cancelNotification(c, cur)
        put(c, cur.copy(snoozeAt = System.currentTimeMillis() + Notes.snoozeMinutes(c) * 60_000L, snoozeDay = day, snoozeMin = min))
    }

    // ------------------------------------------------------------------ alarms

    /** When the routine's alarm should go off after [after]: its next occurrence, or a sooner snooze; 0 = none. */
    fun alarmAt(r: Routine, after: Long): Long {
        val next = Sched.next(r, after)?.at ?: 0L
        val snooze = if (r.snoozeAt > after && !r.paused) r.snoozeAt else 0L
        return listOf(next, snooze).filter { it > 0 }.minOrNull() ?: 0L
    }

    private fun alarmPi(c: Context, r: Routine) = PendingIntent.getBroadcast(c, 0,
        Intent(c, NotesReceiver::class.java).setAction(NotesReceiver.R_REMIND).setData(Uri.parse("plainroutine://${r.id}")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun schedule(c: Context, r: Routine, after: Long = System.currentTimeMillis()) {
        val at = alarmAt(r, after)
        if (at <= 0) { cancelAlarm(c, r); return }
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmPi(c, r)
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun cancelAlarm(c: Context, r: Routine) {
        c.getSystemService(AlarmManager::class.java)?.cancel(alarmPi(c, r))
    }

    /** The alarm went off: remind of the occurrence (or the snooze) that is due, then set the next alarm. */
    fun fire(c: Context, id: Long) {
        var r = get(c, id) ?: return
        if (r.paused) { cancelAlarm(c, r); return }
        val now = System.currentTimeMillis()
        val soon = now + 1500
        var from = now
        if (r.snoozeAt in 1..soon) {
            val day = r.snoozeDay
            val min = r.snoozeMin
            r = r.copy(snoozeAt = 0, snoozeDay = 0, snoozeMin = -1)
            change(c) { l -> l.map { if (it.id == id) r else it } }
            if (min in 0..1439 && min !in r.doneOn(day)) notify(c, r, day, min)
        } else {
            val o = Sched.latest(r, soon)
            // (an alarm Android held back, e.g. without the exact-alarm grant, still counts for half an hour)
            if (o != null && o.at > now - 30 * 60_000L) {
                from = maxOf(now, o.at)
                if (o.min !in r.doneOn(o.day)) notify(c, r, o.day, o.min)
            }
        }
        schedule(c, r, from)
        listener?.invoke()
    }

    /** After a restart, an update or a clock change: every routine's next alarm again (missed ones aren't rung). */
    fun restore(c: Context) {
        for (r in all(c)) schedule(c, r)
    }

    // ------------------------------------------------------------------ notifications

    private fun nm(c: Context) = c.getSystemService(NotificationManager::class.java)
    private fun nid(r: Routine) = (r.id xor (r.id ushr 32)).toInt() xor 0x5254_0000
    fun cancelNotification(c: Context, r: Routine) { nm(c)?.cancel(nid(r)) }

    fun canNotify(c: Context) = nm(c)?.let { it.areNotificationsEnabled() && it.getNotificationChannel(CH)?.importance != NotificationManager.IMPORTANCE_NONE } != false

    fun channel(c: Context) {
        nm(c)?.createNotificationChannel(NotificationChannel(CH, tr("Routine reminders", "Rutin hatırlatıcıları"), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 300, 200, 300)
        })
    }

    private fun pi(c: Context, action: String, r: Routine, day: Int, min: Int) = PendingIntent.getBroadcast(c, 0,
        Intent(c, NotesReceiver::class.java).setAction(action).setData(Uri.parse("plainroutine://${r.id}/$day/$min")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openPi(c: Context, r: Routine) = PendingIntent.getActivity(c, nid(r),
        Intent(RoutinesActivity.ACTION_SHOW).setClassName(c.packageName, "app.plain.hub.OpenRoutines")
            .setData(Uri.parse("plainroutine://${r.id}")).putExtra(RoutinesActivity.EXTRA_ID, r.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notify(c: Context, r: Routine, day: Int, min: Int) {
        PlainLang.init(c)
        channel(c)
        val total = r.plan.minutes.size
        val doneN = r.doneOn(day).size
        val rest = r.text.trim().lines().drop(1).joinToString("\n").trim()
        val body = Stack.hhmm(min) + (if (total > 1) tr(" · $doneN/$total done today", " · bugün $doneN/$total tamam") else "") +
            if (rest.isNotEmpty()) "\n$rest" else ""
        val snooze = Notes.snoozeMinutes(c)
        val b = Notification.Builder(c, CH)
            .setSmallIcon(R.drawable.nts_ic_note)
            .setColor(C.ACCENT)
            .setContentTitle(r.title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setShowWhen(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openPi(c, r))
            .addAction(Notification.Action.Builder(null, tr("Done", "Tamam"), pi(c, NotesReceiver.R_DONE, r, day, min)).build())
            .addAction(Notification.Action.Builder(null, tr("Snooze $snooze min", "$snooze dk ertele"), pi(c, NotesReceiver.R_SNOOZE, r, day, min)).build())
        try { nm(c)?.notify(nid(r), b.build()) } catch (_: Exception) {}
    }
}
