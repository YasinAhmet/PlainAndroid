package app.trekmap.trail

import java.util.Locale
import kotlin.math.roundToInt

/**
 * Raw recording log format (one row per line, comma separated, '#' = comment):
 *
 *  F,wall,fixTime,ageMs,lat,lon,acc,alt,mslAlt,vAcc,speed,sAcc,bearing,bAcc,sats,meanCn0,maxCn0,batch,decision,mode
 *     every GPS fix the phone delivered. decision: S stored · A accuracy · W warm-up · J jump ·
 *     M min-step · P standing (Doppler) · R ignored while resting. mode: L live · P pocket · R rest-poll
 *  E,wall,event,detail            start/stop/mode/motion/ui/provider/restore/… events
 *  B,wall,pct,charging,tempC,currentMa,chargeMah,powerSave,idle,screenOn[,air,bt,btAudio,music,out,vol,wifi,net,
 *     cellLvl,cellDbm,bright,autoBright,cpuMs]   battery snapshot (+ environment since 2026-11-24, see Env)
 *  Environment events: screen on|off · airplane on|off · music on <out>|off · audio-out bt|wired|speaker ·
 *     env <initial state> · photo "camera opened" (camera until the app is visible again)
 *
 * This class digests such a file into the numbers that matter when tuning accuracy vs battery.
 */
class DevSummary {
    var startWall = 0L; var endWall = 0L
    var fixes = 0; var stored = 0
    val decisions = sortedMapOf<Char, Int>()
    private var accSum = 0.0; private var accN = 0
    private var satsSum = 0.0; private var satsN = 0
    private var cn0Sum = 0.0; private var cn0N = 0
    private var ageSum = 0.0; private var ageN = 0; var maxAgeMs = 0L
    var batches = 0; var batchedFixes = 0
    val modeMs = sortedMapOf<String, Long>()
    private var mode: String? = null; private var modeSince = 0L
    var rests = 0; var motionWakes = 0; var restores = 0; var providerOff = 0; var maxGapMs = 0L
    var battStart: Int? = null; var battEnd: Int? = null; var maxTemp = Float.NaN; var charged = false
    private var lastFixWall = 0L

    // --- where the battery went: time-weighted states and drain per B interval
    /** Total time each state was on: screen, camera, airplane, music, btAudio, appVisible. */
    val stateMs = sortedMapOf<String, Long>()
    private val stateOn = HashMap<String, Boolean>()
    private val stateSince = HashMap<String, Long>()
    private val fromEvents = HashSet<String>()
    private var screenSeenOn = false
    private var lastB: List<String>? = null
    private var lastBWall = 0L
    private var lastBKey = ""
    /** Screen-off intervals: drained mAh and hours, per "airplane · music · bt audio" combination. */
    val offDrain = sortedMapOf<String, DoubleArray>()
    var onDrainMah = 0.0; var onDrainH = 0.0
    private var cellSum = 0; private var cellN = 0
    var appCpuMs = 0L

    private fun setState(k: String, on: Boolean, wall: Long, event: Boolean = true) {
        if (event) fromEvents += k
        if (k == "screen" && on) screenSeenOn = true
        val was = stateOn[k]
        if (was == on) return
        if (was == true) stateMs[k] = (stateMs[k] ?: 0L) + (wall - (stateSince[k] ?: wall)).coerceAtLeast(0)
        stateOn[k] = on
        stateSince[k] = wall
    }

    private fun on(k: String) = stateOn[k] == true

    /** "on" / "off", or "?" for logs from before this was recorded. */
    private fun st(k: String) = when (stateOn[k]) { true -> "on"; false -> "off"; null -> "?" }

    private fun offKey() = "airplane ${st("airplane")} · music ${st("music")} · bt audio ${st("btAudio")}"

    private fun battRow(p: List<String>, wall: Long) {
        // states from the snapshot, for whatever no event has reported yet (and for older logs)
        fun col(i: Int) = p.getOrNull(i)
        fun fromB(k: String, v: String?) { if (k !in fromEvents && (v == "1" || v == "0")) setState(k, v == "1", wall, event = false) }
        fromB("screen", col(9)); fromB("airplane", col(10)); fromB("btAudio", col(12)); fromB("music", col(13))
        col(18)?.toIntOrNull()?.let { if (it >= 0 && !on("airplane")) { cellSum += it; cellN++ } }
        col(22)?.toLongOrNull()?.let { if (it > 0) appCpuMs += it }
        val prev = lastB
        val mah = col(6)?.toIntOrNull()
        val prevMah = prev?.getOrNull(6)?.toIntOrNull()
        if (prev != null && mah != null && prevMah != null && col(3) != "1" && prev.getOrNull(3) != "1") {
            val h = (wall - lastBWall) / 3_600_000.0
            val used = (prevMah - mah).toDouble()
            if (h > 0 && used >= 0) {
                if (screenSeenOn || on("screen")) { onDrainMah += used; onDrainH += h }
                else offDrain.getOrPut(lastBKey) { DoubleArray(2) }.let { it[0] += used; it[1] += h }
            }
        }
        lastB = p; lastBWall = wall; lastBKey = offKey()
        screenSeenOn = on("screen")
    }
    private var storedDist = 0.0
    private var sLat = Double.NaN; private var sLon = Double.NaN

    val durationMs get() = endWall - startWall
    val storedDistanceM get() = storedDist

    fun line(l: String) {
        if (l.isEmpty() || l[0] == '#') return
        val p = l.split(',')
        val wall = p.getOrNull(1)?.toLongOrNull() ?: return
        if (startWall == 0L) startWall = wall
        endWall = maxOf(endWall, wall)
        when (p[0]) {
            "F" -> fix(p, wall)
            "E" -> event(p.getOrNull(2) ?: "", p.getOrNull(3) ?: "", wall)
            "B" -> {
                val pct = p.getOrNull(2)?.toIntOrNull() ?: return
                if (battStart == null) battStart = pct
                battEnd = pct
                if (p.getOrNull(3) == "1") charged = true
                p.getOrNull(4)?.toFloatOrNull()?.let { if (maxTemp.isNaN() || it > maxTemp) maxTemp = it }
                battRow(p, wall)
            }
        }
    }

    private fun fix(p: List<String>, wall: Long) {
        if (p.size < 20) return
        fixes++
        val dec = p[18].firstOrNull() ?: '?'
        decisions[dec] = (decisions[dec] ?: 0) + 1
        if (dec == 'S') {
            stored++
            val la = p[4].toDoubleOrNull(); val lo = p[5].toDoubleOrNull()
            if (la != null && lo != null) {
                if (!sLat.isNaN()) storedDist += Geo.dist(sLat, sLon, la, lo)
                sLat = la; sLon = lo
            }
        }
        p[6].toDoubleOrNull()?.let { if (it < 900) { accSum += it; accN++ } }
        p[14].toIntOrNull()?.let { if (it >= 0) { satsSum += it; satsN++ } }
        p[15].toDoubleOrNull()?.let { if (it > 0) { cn0Sum += it; cn0N++ } }
        p[3].toLongOrNull()?.let { if (it >= 0) { ageSum += it; ageN++; maxAgeMs = maxOf(maxAgeMs, it) } }
        val batch = p[17].toIntOrNull() ?: 1
        if (batch > 1) batchedFixes++
        if (lastFixWall > 0) maxGapMs = maxOf(maxGapMs, wall - lastFixWall)
        lastFixWall = wall
    }

    private fun event(e: String, detail: String, wall: Long) {
        when (e) {
            "mode" -> {
                closeMode(wall)
                mode = detail.substringBefore(' ')
                modeSince = wall
                if (mode == "rest" || mode == "rest-poll") rests++
            }
            "batch" -> batches++
            "motion" -> motionWakes++
            "restore" -> { restores++; closeMode(wall); mode = null }
            "provider-off" -> providerOff++
            "stop" -> closeMode(wall)
            "screen" -> setState("screen", detail == "on", wall)
            "airplane" -> setState("airplane", detail == "on", wall)
            "music" -> setState("music", detail.startsWith("on"), wall)
            "audio-out" -> setState("btAudio", detail == "bt", wall)
            "env" -> {
                setState("airplane", detail.contains("airplane on"), wall)
                setState("btAudio", detail.contains("audio-out bt"), wall)
                setState("music", detail.contains("music on"), wall)
            }
            "photo" -> if (detail.startsWith("camera opened")) setState("camera", true, wall)
            "ui" -> {
                if (detail.startsWith("visible")) { setState("camera", false, wall); setState("appVisible", true, wall) }
                else if (detail.startsWith("hidden")) setState("appVisible", false, wall)
            }
        }
    }

    private fun closeMode(wall: Long) {
        val m = mode ?: return
        modeMs[m] = (modeMs[m] ?: 0L) + (wall - modeSince).coerceAtLeast(0)
        mode = null
    }

    fun finish(): DevSummary {
        closeMode(endWall)
        for (k in stateOn.keys.toList()) if (stateOn[k] == true) setState(k, false, endWall, event = k in fromEvents)
        return this
    }

    /** The "where did the battery go" lines (empty for logs without battery rows). */
    fun energyText(): String = buildString {
        val total = durationMs.coerceAtLeast(1)
        fun share(k: String, label: String) = stateMs[k]?.let { "$label ${Fmt.dur(it / 1000)} (${it * 100 / total}%)" }
        val times = listOfNotNull(
            share("screen", "screen on"), share("appVisible", "app open"), share("camera", "camera"),
            share("airplane", "airplane mode"), share("music", "music"), share("btAudio", "Bluetooth audio"),
        )
        if (times.isNotEmpty()) appendLine("Time: " + times.joinToString(" · "))
        if (onDrainH > 0) appendLine(String.format(Locale.US, "Drain with screen use: %.0f mA (%.1f h)", onDrainMah / onDrainH, onDrainH))
        for ((k, v) in offDrain) if (v[1] > 0.05) appendLine(String.format(Locale.US, "Drain screen off, %s: %.0f mA (%.1f h)", k, v[0] / v[1], v[1]))
        if (cellN > 0) appendLine(String.format(Locale.US, "Phone signal (airplane off) avg %.1f of 4 bars", cellSum.toDouble() / cellN))
        if (appCpuMs > 0) appendLine(String.format(Locale.US, "Plain Trekking CPU time %.1f min (%.2f%% of the hike)", appCpuMs / 60000.0, appCpuMs * 100.0 / total))
    }.trimEnd()

    fun text(): String {
        fun avg(s: Double, n: Int, d: Int = 1) = if (n == 0) "–" else String.format(Locale.US, "%.${d}f", s / n)
        val names = mapOf('S' to "stored", 'A' to "bad accuracy", 'W' to "warm-up", 'J' to "jump", 'M' to "min-step",
            'P' to "standing", 'R' to "while resting")
        val hours = durationMs / 3_600_000.0
        val drain = if (battStart != null && battEnd != null && !charged && hours > 0.05)
            String.format(Locale.US, " (%.1f %%/h)", (battStart!! - battEnd!!) / hours) else ""
        return buildString {
            appendLine("Duration ${Fmt.dur(durationMs / 1000)} · ${fixes} GPS fixes · ${stored} stored · ${Fmt.dist(storedDist)}")
            appendLine("Filter: " + decisions.entries.joinToString(" · ") { "${names[it.key] ?: it.key} ${it.value}" })
            appendLine("Accuracy avg ${avg(accSum, accN)} m · satellites avg ${avg(satsSum, satsN)} · mean C/N0 avg ${avg(cn0Sum, cn0N)} dB-Hz")
            appendLine("Delivery delay avg ${avg(ageSum / 1000, ageN)} s · max ${maxAgeMs / 1000} s · batches ${batches} (${batchedFixes} fixes arrived batched) · longest gap ${maxGapMs / 1000} s")
            appendLine("Modes: " + modeMs.entries.joinToString(" · ") { "${it.key} ${Fmt.dur(it.value / 1000)}" } +
                " · rests $rests · motion wake-ups $motionWakes")
            appendLine("Battery ${battStart ?: "?"}% → ${battEnd ?: "?"}%$drain · max ${if (maxTemp.isNaN()) "?" else maxTemp.roundToInt()}°C" +
                (if (charged) " · was charging (drain not meaningful)" else ""))
            append("Process restored after being killed: $restores · GPS turned off by user: $providerOff")
            energyText().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
        }
    }

    companion object {
        fun of(lines: Sequence<String>): DevSummary = DevSummary().apply { lines.forEach { line(it) } }.finish()
    }
}
