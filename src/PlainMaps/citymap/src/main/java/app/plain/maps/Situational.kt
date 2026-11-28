package app.plain.maps

import app.plain.lang.tr
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small sync for day-to-day changes, without downloading the map or the 290 MB İETT files:
 *  1. İETT's current announcements (cancelled trips, detours, closed roads), shown on the lines;
 *  2. today's planned departure times of every bus line (İETT's planned-trips service),
 *     replacing the published timetable's times where İETT has changed them.
 * It works on a copy of the database and swaps it in at the end, like the full sync.
 */
class Situational(
    private val ctx: Context,
    private val cancel: AtomicBoolean,
    private val report: (Float, String) -> Unit,
) {
    companion object {
        const val NOTICES = "https://api.ibb.gov.tr/iett/UlasimDinamikVeri/Duyurular.asmx"
        const val PLANNED = "https://api.ibb.gov.tr/iett/UlasimAnaVeri/PlanlananSeferSaati.asmx"

        /** İETT day types: I = weekdays, C = Saturday, P = Sunday. */
        fun dayMask(t: String?) = when (t?.trim()?.uppercase()) {
            "I", "İ" -> 0x1F
            "C" -> 1 shl 5
            "P" -> 1 shl 6
            else -> 0
        }

        /** Parses a planned-times answer into route code → list of (day mask, start second). */
        fun parsePlanned(json: String): Map<String, List<IntArray>> {
            val out = HashMap<String, MutableList<IntArray>>()
            if (!json.trimStart().startsWith("[")) return out
            Json(json.byteInputStream()).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    val m = j.readStringMap()
                    val code = (m["SGUZERAH"] ?: m["SGUZERGAH"] ?: continue).trim()
                    val days = dayMask(m["SGUNTIPI"])
                    val t = Text.time(m["DT"] ?: "") ?: continue
                    if (days == 0) continue
                    out.getOrPut(code) { ArrayList() }.add(intArrayOf(days, t))
                }
                j.endArray()
            }
            return out
        }
    }

    private lateinit var net: Net.Net0

    private fun checkCancel() { if (cancel.get()) throw InterruptedException() }

    fun run() {
        net = Net.Net0(Net.wifi(ctx) ?: throw IOException(tr("No Wi-Fi. Sync only runs on Wi-Fi.", "Wi-Fi yok. Eşitleme yalnızca Wi-Fi ile çalışır.")))
        val cur = Sync.dataFile(ctx)
        if (!cur.exists()) throw IOException(tr("Do a full sync first.", "Önce tam eşitleme yapın."))
        val dir = File(City.files(ctx), "sync").apply { mkdirs() }
        val tmp = File(dir, "data-sit.db")
        report(0.01f, tr("Copying the offline data", "Çevrimdışı veri kopyalanıyor"))
        // a full disk leaves a partial copy of a few hundred MB behind otherwise
        try { cur.copyTo(tmp, overwrite = true) } catch (e: Exception) { tmp.delete(); throw e }
        val db = SQLiteDatabase.openDatabase(tmp.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            db.rawQuery("PRAGMA journal_mode=OFF", null).close()
            if (City.ankara) egoNotices(db) else notices(db)
            val (done, changed) = times(db)
            db.execSQL("INSERT OR REPLACE INTO meta VALUES('situational_at', ?)", arrayOf(System.currentTimeMillis().toString()))
            db.execSQL("INSERT OR REPLACE INTO meta VALUES('situational_lines', ?)", arrayOf("$done/$changed"))
        } catch (e: Throwable) {
            db.close(); tmp.delete()
            throw e
        }
        db.close()
        checkCancel()
        val old = File(dir, "data-old.db")
        old.delete()
        if (!cur.renameTo(old)) { tmp.delete(); throw IOException(tr("could not replace the data", "veri değiştirilemedi")) }
        if (!tmp.renameTo(cur)) { old.renameTo(cur); throw IOException(tr("could not install the new data", "yeni veri kurulamadı")) }
        old.delete()
        report(1f, tr("Done", "Bitti"))
    }

    private fun notices(db: SQLiteDatabase) {
        report(0.03f, tr("İETT announcements", "İETT duyuruları"))
        val json = Net.soap(net, NOTICES, "GetDuyurular_json")
        db.execSQL("CREATE TABLE IF NOT EXISTS notice(ref TEXT, kind TEXT, at TEXT, msg TEXT)")
        db.execSQL("DELETE FROM notice")
        var n = 0
        if (json.trimStart().startsWith("[")) {
            Json(json.byteInputStream()).use { j ->
                j.beginArray()
                while (j.hasNext()) {
                    val m = j.readStringMap()
                    val ref = m["HATKODU"]?.trim() ?: continue
                    db.execSQL("INSERT INTO notice VALUES(?,?,?,?)", arrayOf(ref, m["TIP"] ?: "", (m["GUNCELLEME_SAATI"] ?: "").replace("Kayit Saati:", "").trim(), m["MESAJ"]?.trim() ?: ""))
                    n++
                }
                j.endArray()
            }
        }
        db.execSQL("CREATE INDEX IF NOT EXISTS notice_ref ON notice(ref)")
        report(0.06f, tr("$n announcements", "$n duyuru"))
    }

    /** EGO's announcement list: the line numbers in each title get a notice; "durak:NNNNN" for stop notices. */
    private fun egoNotices(db: SQLiteDatabase) {
        report(0.03f, tr("EGO announcements", "EGO duyuruları"))
        val list = Ego.parseNotices(Net.text(net.n, Ego.NOTICES))
        db.execSQL("CREATE TABLE IF NOT EXISTS notice(ref TEXT, kind TEXT, at TEXT, msg TEXT)")
        db.execSQL("DELETE FROM notice")
        for (n in list) for (r in n.refs) db.execSQL("INSERT INTO notice VALUES(?,?,?,?)", arrayOf(r, "Duyuru", n.date, n.title))
        db.execSQL("CREATE INDEX IF NOT EXISTS notice_ref ON notice(ref)")
        report(0.06f, tr("${list.size} announcements", "${list.size} duyuru"))
    }

    /**
     * The planned times of one fetch unit: an İETT line number (one answer covers all its route
     * codes) or an EGO line code (its page). Route code → (day mask, start second).
     */
    private fun plannedFor(unit: String): Map<String, List<IntArray>> =
        if (City.ankara) EgoFetch.line(net, unit)?.let { mapOf(it.code to Ego.departures(it)) } ?: emptyMap()
        else parsePlanned(Net.soap(net, PLANNED, "GetPlanlananSeferSaati_json", "<HatKodu>${Net.xmlText(unit).replace("&", "&amp;").replace("<", "&lt;")}</HatKodu>"))

    /** Returns (lines checked, route variants whose times were replaced). */
    private fun times(db: SQLiteDatabase): Pair<Int, Int> {
        val refs = ArrayList<String>()
        // İETT answers per line number; EGO has a page per route code
        val unitSql = if (City.ankara) "SELECT DISTINCT code FROM variant WHERE id<${Store.RAIL_VAR0}" else "SELECT ref FROM line WHERE mode=0"
        db.rawQuery(unitSql, null).use { c -> while (c.moveToNext()) c.getString(0)?.let { refs.add(it) } }
        // route code → variants (id, trips), and each variant's typical duration per day type
        class V(val id: Int, val ntrips: Int, val length: Int)
        val byCode = HashMap<String, MutableList<V>>()
        db.rawQuery("SELECT id,code,ntrips,dists FROM variant WHERE id<${Store.RAIL_VAR0}", null).use { c ->
            while (c.moveToNext()) {
                val d = Codec.ints(c.getBlob(3))
                byCode.getOrPut(c.getString(1) ?: "") { ArrayList() }.add(V(c.getInt(0), c.getInt(2), d.lastOrNull() ?: 0))
            }
        }
        val durs = HashMap<Long, MutableList<Int>>() // (variant, day mask) → durations
        db.rawQuery("SELECT variant,days,dur FROM trip", null).use { c ->
            while (c.moveToNext()) durs.getOrPut((c.getLong(0) shl 8) or c.getLong(1)) { ArrayList() }.add(c.getInt(2))
        }
        val memo = HashMap<Long, Int>()
        fun durationOf(v: V, days: Int): Int {
            durs[(v.id.toLong() shl 8) or days.toLong()]?.let { return it.sorted()[it.size / 2] }
            val all = durs.entries.filter { (it.key shr 8).toInt() == v.id }.flatMap { it.value }
            if (all.isNotEmpty()) return all.sorted()[all.size / 2]
            return (v.length / 5.0).toInt() // about 18 km/h
        }
        fun duration(v: V, days: Int): Int = memo.getOrPut((v.id.toLong() shl 8) or days.toLong()) { durationOf(v, days) }

        val doneN = AtomicInteger(0)
        var changed = 0
        val lock = Any()
        val pool = Executors.newFixedThreadPool(if (City.ankara) 3 else 4)
        val failures = AtomicInteger(0)
        val ins = db.compileStatement("INSERT INTO trip VALUES(?,?,?,?)")
        val futures = refs.map { ref ->
            pool.submit {
                if (cancel.get()) return@submit
                val plan = try {
                    plannedFor(ref)
                } catch (_: Exception) {
                    failures.incrementAndGet(); null
                }
                synchronized(lock) {
                    if (plan != null && plan.isNotEmpty() && !cancel.get()) {
                        db.beginTransaction()
                        try {
                            for ((code, list) in plan) {
                                val vs = byCode[code] ?: continue
                                val main = vs.maxBy { it.ntrips }
                                db.execSQL("DELETE FROM trip WHERE variant IN (${vs.joinToString(",") { it.id.toString() }})")
                                val uniq = list.map { (it[0].toLong() shl 20) or it[1].toLong() }.distinct()
                                for (u in uniq) {
                                    val days = (u shr 20).toInt(); val t = (u and 0xFFFFF).toInt()
                                    ins.bindLong(1, main.id.toLong()); ins.bindLong(2, days.toLong()); ins.bindLong(3, t.toLong())
                                    ins.bindLong(4, duration(main, days).toLong())
                                    ins.executeInsert()
                                }
                                for (v in vs) db.execSQL("UPDATE variant SET ntrips=? WHERE id=?", arrayOf<Any>(if (v === main) uniq.size else 0, v.id))
                                changed++
                            }
                            db.setTransactionSuccessful()
                        } finally {
                            db.endTransaction()
                        }
                    }
                    val n = doneN.incrementAndGet()
                    if (n % 10 == 0 || n == refs.size) report(0.06f + 0.92f * n / refs.size, tr("Bus times: line $n of ${refs.size}", "Otobüs saatleri: hat $n / ${refs.size}"))
                }
            }
        }
        try {
            futures.forEach { it.get() }
        } finally {
            // also when a line's write failed (disk full): the workers stop before the caller closes the database
            pool.shutdownNow()
            try { pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) {}
        }
        checkCancel()
        if (failures.get() > refs.size / 2) throw IOException(tr("${City.operator}'s service did not answer for most lines (${failures.get()} of ${refs.size}). Try again later.",
            "${City.operator} servisi hatların çoğu için yanıt vermedi (${failures.get()} / ${refs.size}). Daha sonra yeniden deneyin."))
        return doneN.get() - failures.get() to changed
    }
}
