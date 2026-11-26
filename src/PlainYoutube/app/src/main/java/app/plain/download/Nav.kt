package app.plain.download

/*
 * 2.3 pure logic: the app's Back history, the order of Back, the player overlay's state machine and the mini
 * window's corners. Unit-tested (NavTest).
 */

/** One undoable navigation step: Back restores what was there before it. */
sealed class NavStep {
    data class Tab(val from: Int) : NavStep() // Download <-> Search
    data class Base(val from: Int) : NavStep() // Search: Feed / Search / Lists
    object Page : NavStep() { override fun toString() = "Page" } // a drilled page (channel, playlist, Following, …)
    object CachedOpen : NavStep() { override fun toString() = "CachedOpen" } // the Download tab's Cached folder was opened
}

class NavHistory(private val max: Int = 64) {
    private val steps = ArrayList<NavStep>()
    val size get() = steps.size
    val isEmpty get() = steps.isEmpty()
    val top: NavStep? get() = steps.lastOrNull()

    /** Records a step. Switching back and forth between two tabs/bases doesn't grow the history forever. */
    fun push(s: NavStep) {
        val t = steps.lastOrNull()
        if (s is NavStep.Tab && t is NavStep.Tab && t.from != s.from) { steps.removeAt(steps.size - 1); return } // A->B->A = nothing to undo
        if (s is NavStep.Base && t is NavStep.Base && t.from != s.from) { steps.removeAt(steps.size - 1); return }
        if ((s is NavStep.Tab || s is NavStep.Base) && t == s) return
        steps += s
        while (steps.size > max) steps.removeAt(0)
    }

    fun pop(): NavStep? = if (steps.isEmpty()) null else steps.removeAt(steps.size - 1)

    /** A page was closed some other way (its own ✕ / Back inside the Search tab): forget the newest Page step. */
    fun dropPage() { val i = steps.indexOfLast { it == NavStep.Page }; if (i >= 0) steps.removeAt(i) }

    /** The Cached folder closed by tapping its header. */
    fun dropCached() { val i = steps.indexOfLast { it == NavStep.CachedOpen }; if (i >= 0) steps.removeAt(i) }

    fun clear() = steps.clear()
}

/** What one press of Back does (dialogs and menus are their own windows and take Back before the activity). */
object BackRule {
    enum class Action { END_SELECTION, MINIMIZE_PLAYER, LEAVE_FULLSCREEN, POP, EXIT_HINT, EXIT }

    const val EXIT_WINDOW_MS = 2000L

    fun decide(selecting: Boolean, playerFull: Boolean, playerFullscreen: Boolean, hasHistory: Boolean, lastBackAt: Long, now: Long): Action = when {
        selecting -> Action.END_SELECTION
        playerFullscreen -> Action.LEAVE_FULLSCREEN
        playerFull -> Action.MINIMIZE_PLAYER
        hasHistory -> Action.POP
        now - lastBackAt in 0..EXIT_WINDOW_MS -> Action.EXIT
        else -> Action.EXIT_HINT
    }
}

/** The player overlay. PIP = the activity is in picture-in-picture showing only the video. */
enum class PState { CLOSED, FULL, MINI, PIP }

object PlayerUi {
    enum class Ev { OPEN, MINIMIZE, EXPAND, CLOSE, ENTER_PIP, LEAVE_PIP, PIP_DISMISSED }

    /** [beforePip]: the state PiP was entered from (restored when PiP is expanded back into the app). */
    fun next(s: PState, e: Ev, beforePip: PState = PState.FULL): PState = when (e) {
        Ev.OPEN -> if (s == PState.PIP) PState.PIP else PState.FULL
        Ev.MINIMIZE -> if (s == PState.FULL) PState.MINI else s
        Ev.EXPAND -> if (s == PState.MINI) PState.FULL else s
        Ev.CLOSE -> PState.CLOSED
        Ev.ENTER_PIP -> if (s == PState.FULL || s == PState.MINI) PState.PIP else s
        Ev.LEAVE_PIP -> if (s == PState.PIP) (if (beforePip == PState.MINI) PState.MINI else PState.FULL) else s
        Ev.PIP_DISMISSED -> if (s == PState.PIP) PState.CLOSED else s
    }

    /** Playback may run only while something of it is visible: full, mini, or PiP (not with the app in the background). */
    fun mayPlay(s: PState, appVisible: Boolean, inPip: Boolean) = s != PState.CLOSED && (appVisible || inPip)
}

enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

object Corners {
    /** Corner nearest to the window's centre ([cx],[cy]) inside a [w] x [h] area. */
    fun nearest(cx: Float, cy: Float, w: Int, h: Int): Corner {
        val left = cx < w / 2f
        val top = cy < h / 2f
        return when {
            top && left -> Corner.TOP_LEFT
            top -> Corner.TOP_RIGHT
            left -> Corner.BOTTOM_LEFT
            else -> Corner.BOTTOM_RIGHT
        }
    }

    fun parse(s: String?) = Corner.values().firstOrNull { it.name == s } ?: Corner.TOP_RIGHT
}

/** Volume bar <-> stream volume, and mute that remembers the level. */
class VolumeModel(val max: Int) {
    var level = 0
        private set
    private var beforeMute = 0

    val muted get() = level == 0

    fun sync(systemLevel: Int) { level = systemLevel.coerceIn(0, max) }

    fun set(v: Int): Int { level = v.coerceIn(0, max); return level }

    /** Speaker icon: mute (remembering the level) or restore it (at least 1 step). */
    fun toggleMute(): Int {
        if (level > 0) { beforeMute = level; level = 0 } else level = beforeMute.coerceIn(1, max).takeIf { max > 0 } ?: 0
        return level
    }
}

/** Playback speeds offered (PlaybackParams). */
object Speeds {
    val ALL = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun label(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"
}

/*
 * 2.4 background playback: what happens to Watch when the app is hidden (Home, another app, screen off; also screen
 * off in PiP). Listen and saved/cached files just lose the surface. A streamed video (HLS / members MP4) first loses
 * the surface, then after [BgVideo.DELAY_MS] switches to the audio-only stream at the same position (no more video
 * segments downloaded), and switches back to video when the app is shown again.
 */
enum class BgSrc { LOCAL, STREAM }
enum class BgState { SHOWN, HIDDEN, HIDDEN_PENDING, HIDDEN_AUDIO }
enum class BgAct { DETACH, ATTACH, SCHEDULE_SWITCH, CANCEL_TIMER, SWITCH_TO_AUDIO, SWITCH_TO_VIDEO }

class BgVideo {
    companion object { const val DELAY_MS = 5000L }

    var state = BgState.SHOWN
        private set
    val hidden get() = state != BgState.SHOWN
    /** Playing audio-only in place of a Watch stream (switch back to video when shown). */
    val audioForVideo get() = state == BgState.HIDDEN_AUDIO

    fun hide(watch: Boolean, src: BgSrc): List<BgAct> {
        if (state != BgState.SHOWN) return emptyList()
        return when {
            watch && src == BgSrc.STREAM -> { state = BgState.HIDDEN_PENDING; listOf(BgAct.DETACH, BgAct.SCHEDULE_SWITCH) }
            watch -> { state = BgState.HIDDEN; listOf(BgAct.DETACH) }
            else -> { state = BgState.HIDDEN; emptyList() }
        }
    }

    /** The 5 s delay ran out: still hidden and still a streamed video? */
    fun timer(watchStream: Boolean): List<BgAct> {
        if (state != BgState.HIDDEN_PENDING) return emptyList()
        if (!watchStream) { state = BgState.HIDDEN; return emptyList() }
        state = BgState.HIDDEN_AUDIO
        return listOf(BgAct.SWITCH_TO_AUDIO)
    }

    fun show(): List<BgAct> {
        val s = state
        state = BgState.SHOWN
        return when (s) {
            BgState.SHOWN -> emptyList()
            BgState.HIDDEN -> listOf(BgAct.ATTACH)
            BgState.HIDDEN_PENDING -> listOf(BgAct.CANCEL_TIMER, BgAct.ATTACH)
            BgState.HIDDEN_AUDIO -> listOf(BgAct.SWITCH_TO_VIDEO)
        }
    }

    /** A queue item starts while hidden: a streamed Watch item plays audio-only from the start. True = start as audio. */
    fun startHidden(watchStream: Boolean): Boolean {
        if (!hidden) return false
        if (!watchStream) { if (state == BgState.HIDDEN_AUDIO || state == BgState.HIDDEN_PENDING) state = BgState.HIDDEN; return false }
        state = BgState.HIDDEN_AUDIO
        return true
    }

    /** Player closed or opened anew: keep only "hidden or not". */
    fun reset() { if (hidden) state = BgState.HIDDEN }
}

/** Audio focus decisions (shared with Plain Music: whoever starts playing takes the focus, the other pauses). */
object Focus {
    enum class Do { PAUSE_FOR_GOOD, PAUSE_RESUME_LATER, DUCK, RESUME, UNDUCK, NOTHING }

    fun onChange(change: Int, playing: Boolean, resumeOnGain: Boolean): Do = when (change) {
        android.media.AudioManager.AUDIOFOCUS_LOSS -> Do.PAUSE_FOR_GOOD
        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (playing) Do.PAUSE_RESUME_LATER else Do.NOTHING
        android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> if (playing) Do.DUCK else Do.NOTHING
        android.media.AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) Do.RESUME else Do.UNDUCK
        else -> Do.NOTHING
    }
}

/*
 * 2.5: the player outlives MainActivity. Rules for attaching / detaching the window and for resuming after the
 * process was gone (unit-tested; used by PlayerPanel).
 */
object EngineRule {
    enum class AttachStep { LEAVE_PIP, RESTORE_PAUSED, SHOW }

    /** A window attaches: PiP can't survive a new activity; a closed player comes back paused from saved state. */
    fun onAttach(state: PState, stored: Boolean): List<AttachStep> = buildList {
        if (state == PState.PIP) add(AttachStep.LEAVE_PIP)
        if (state == PState.CLOSED && stored) add(AttachStep.RESTORE_PAUSED)
        add(AttachStep.SHOW)
    }

    /** The window is destroyed (swipe from recents, system): playing goes on audio-only; paused closes. */
    fun keepOnDetach(wantsPlay: Boolean) = wantsPlay

    /** Paused with no window, or in the background: release player, proxy and read-ahead (state is saved). */
    fun releaseOnPause(hidden: Boolean, windowAttached: Boolean) = hidden || !windowAttached

    /** A notification / headset command: something to act on (a loaded player, or saved state to rebuild it from). */
    fun canControl(state: PState, stored: Boolean) = state != PState.CLOSED || stored
}

/** The saved player state (Store.lastPlay). */
data class LastPlay(
    val id: String, val title: String, val channel: String, val mode: PlayMode, val uri: String?, val audio: Boolean,
    val queue: Boolean, val album: String?, val pos: Int, val view: String,
) {
    fun encode(): String = org.json.JSONObject().put("id", id).put("t", title).put("c", channel).put("m", mode.name)
        .put("u", uri ?: "").put("a", audio).put("q", queue).put("al", album ?: "").put("p", pos).put("v", view).toString()

    companion object {
        fun decode(s: String?): LastPlay? = try {
            val o = org.json.JSONObject(s ?: return null)
            val id = o.optString("id"); val uri = o.optString("u").ifEmpty { null }
            if (id.isEmpty() && uri == null) null else LastPlay(id, o.optString("t"), o.optString("c"),
                enumOr(o.optString("m"), PlayMode.WATCH), uri, o.optBoolean("a"), o.optBoolean("q"),
                o.optString("al").ifEmpty { null }, o.optInt("p"), o.optString("v", "MINI"))
        } catch (_: Exception) { null }
    }
}
