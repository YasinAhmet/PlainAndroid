package app.plain.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.3: Back history, Back order, player overlay states, mini corners, volume/mute, speeds. */
class NavTest {
    @Test fun historyPopsInReverse() {
        val h = NavHistory()
        h.push(NavStep.Tab(0)) // Download -> Search
        h.push(NavStep.Base(0)) // Feed -> Search chip
        h.push(NavStep.Page) // channel
        h.push(NavStep.Page) // playlist
        assertEquals(NavStep.Page, h.pop())
        assertEquals(NavStep.Page, h.pop())
        assertEquals(NavStep.Base(0), h.pop())
        assertEquals(NavStep.Tab(0), h.pop())
        assertNull(h.pop())
        assertTrue(h.isEmpty)
    }

    @Test fun backAndForthDoesNotGrow() {
        val h = NavHistory()
        h.push(NavStep.Tab(0)) // 0 -> 1
        h.push(NavStep.Tab(1)) // 1 -> 0: cancels out
        assertTrue(h.isEmpty)
        h.push(NavStep.Base(0)); h.push(NavStep.Base(1))
        assertTrue(h.isEmpty)
        h.push(NavStep.Tab(0)); h.push(NavStep.Tab(0)) // same step twice = once
        assertEquals(1, h.size)
        h.push(NavStep.Page); h.push(NavStep.Page) // pages do stack
        assertEquals(3, h.size)
    }

    @Test fun historyDropsAndBound() {
        val h = NavHistory(max = 5)
        h.push(NavStep.Page); h.push(NavStep.CachedOpen); h.push(NavStep.Tab(1))
        h.dropPage() // closed with the page's own ←
        assertEquals(2, h.size)
        h.dropCached()
        assertEquals(listOf(NavStep.Tab(1)), listOfNotNull(h.pop()))
        repeat(9) { h.push(NavStep.Page) }
        assertEquals(5, h.size)
    }

    @Test fun backOrder() {
        val now = 100_000L
        fun d(sel: Boolean = false, full: Boolean = false, fs: Boolean = false, hist: Boolean = false, last: Long = -10_000L) =
            BackRule.decide(sel, full, fs, hist, last, now)
        assertEquals(BackRule.Action.END_SELECTION, d(sel = true, full = true, hist = true))
        assertEquals(BackRule.Action.LEAVE_FULLSCREEN, d(full = true, fs = true, hist = true))
        assertEquals(BackRule.Action.MINIMIZE_PLAYER, d(full = true, hist = true))
        assertEquals(BackRule.Action.POP, d(hist = true))
        assertEquals(BackRule.Action.EXIT_HINT, d())
        assertEquals(BackRule.Action.EXIT, d(last = now - 1500))
        assertEquals(BackRule.Action.EXIT_HINT, d(last = now - 2500)) // too late: hint again
    }

    @Test fun overlayStates() {
        var s = PState.CLOSED
        fun ev(e: PlayerUi.Ev, before: PState = PState.FULL) { s = PlayerUi.next(s, e, before) }
        ev(PlayerUi.Ev.MINIMIZE); assertEquals(PState.CLOSED, s)
        ev(PlayerUi.Ev.ENTER_PIP); assertEquals(PState.CLOSED, s)
        ev(PlayerUi.Ev.OPEN); assertEquals(PState.FULL, s)
        ev(PlayerUi.Ev.MINIMIZE); assertEquals(PState.MINI, s)
        ev(PlayerUi.Ev.MINIMIZE); assertEquals(PState.MINI, s)
        ev(PlayerUi.Ev.OPEN); assertEquals(PState.FULL, s) // a new video opens full
        ev(PlayerUi.Ev.MINIMIZE); ev(PlayerUi.Ev.EXPAND); assertEquals(PState.FULL, s)
        ev(PlayerUi.Ev.MINIMIZE)
        ev(PlayerUi.Ev.ENTER_PIP); assertEquals(PState.PIP, s)
        ev(PlayerUi.Ev.OPEN); assertEquals(PState.PIP, s)
        ev(PlayerUi.Ev.LEAVE_PIP, before = PState.MINI); assertEquals(PState.MINI, s) // back into the app as it was
        ev(PlayerUi.Ev.EXPAND); ev(PlayerUi.Ev.ENTER_PIP)
        ev(PlayerUi.Ev.LEAVE_PIP, before = PState.FULL); assertEquals(PState.FULL, s)
        ev(PlayerUi.Ev.ENTER_PIP); ev(PlayerUi.Ev.PIP_DISMISSED); assertEquals(PState.CLOSED, s)
        ev(PlayerUi.Ev.OPEN); ev(PlayerUi.Ev.CLOSE); assertEquals(PState.CLOSED, s)
        ev(PlayerUi.Ev.LEAVE_PIP); assertEquals(PState.CLOSED, s)
    }

    @Test fun playbackOnlyWhileVisible() {
        assertTrue(PlayerUi.mayPlay(PState.MINI, appVisible = true, inPip = false))
        assertTrue(PlayerUi.mayPlay(PState.PIP, appVisible = false, inPip = true))
        assertFalse(PlayerUi.mayPlay(PState.FULL, appVisible = false, inPip = false)) // Home without PiP
        assertFalse(PlayerUi.mayPlay(PState.CLOSED, appVisible = true, inPip = false))
    }

    @Test fun corners() {
        assertEquals(Corner.TOP_LEFT, Corners.nearest(10f, 10f, 1000, 2000))
        assertEquals(Corner.TOP_RIGHT, Corners.nearest(900f, 300f, 1000, 2000))
        assertEquals(Corner.BOTTOM_LEFT, Corners.nearest(100f, 1900f, 1000, 2000))
        assertEquals(Corner.BOTTOM_RIGHT, Corners.nearest(600f, 1100f, 1000, 2000))
        assertEquals(Corner.TOP_RIGHT, Corners.parse(null))
        assertEquals(Corner.BOTTOM_LEFT, Corners.parse("BOTTOM_LEFT"))
    }

    @Test fun volumeMute() {
        val v = VolumeModel(15)
        v.sync(9)
        assertEquals(0, v.toggleMute()); assertTrue(v.muted)
        assertEquals(9, v.toggleMute()) // back to the remembered level
        v.sync(0) // muted with the hardware keys later: the speaker brings back the remembered level
        assertEquals(9, v.toggleMute())
        val fresh = VolumeModel(15).apply { sync(0) }
        assertEquals(1, fresh.toggleMute()) // nothing remembered: at least one step
        assertEquals(15, v.set(99)); assertEquals(0, v.set(-3))
        v.sync(40); assertEquals(15, v.level)
    }

    @Test fun speeds() {
        assertEquals("1×", Speeds.label(1f))
        assertEquals("1.25×", Speeds.label(1.25f))
        assertEquals("0.5×", Speeds.label(0.5f))
        assertTrue(Speeds.ALL.first() == 0.5f && Speeds.ALL.last() == 2f)
    }

    // ---- 2.4 background playback ----

    @Test fun watchStreamDropsVideoInBackground() {
        val b = BgVideo()
        assertEquals(listOf(BgAct.DETACH, BgAct.SCHEDULE_SWITCH), b.hide(watch = true, src = BgSrc.STREAM))
        assertTrue(b.hidden)
        assertEquals(emptyList<BgAct>(), b.hide(true, BgSrc.STREAM)) // already hidden
        assertEquals(listOf(BgAct.SWITCH_TO_AUDIO), b.timer(watchStream = true))
        assertTrue(b.audioForVideo)
        assertEquals(emptyList<BgAct>(), b.timer(true)) // fires once
        assertEquals(listOf(BgAct.SWITCH_TO_VIDEO), b.show())
        assertFalse(b.hidden)
    }

    @Test fun backWithinFiveSeconds() {
        val b = BgVideo()
        b.hide(true, BgSrc.STREAM)
        assertEquals(listOf(BgAct.CANCEL_TIMER, BgAct.ATTACH), b.show())
        assertEquals(emptyList<BgAct>(), b.timer(true)) // a late timer does nothing
    }

    @Test fun localFilesAndListenJustLoseTheSurface() {
        val b = BgVideo()
        assertEquals(listOf(BgAct.DETACH), b.hide(watch = true, src = BgSrc.LOCAL))
        assertEquals(emptyList<BgAct>(), b.timer(true))
        assertEquals(listOf(BgAct.ATTACH), b.show())
        assertEquals(emptyList<BgAct>(), b.hide(watch = false, src = BgSrc.STREAM)) // Listen: nothing to drop
        assertTrue(b.hidden)
        assertEquals(listOf(BgAct.ATTACH), b.show())
        // the user switched to Listen meanwhile: the timer finds no video stream
        b.hide(true, BgSrc.STREAM)
        assertEquals(emptyList<BgAct>(), b.timer(watchStream = false))
        assertEquals(listOf(BgAct.ATTACH), b.show())
    }

    @Test fun queueAdvanceWhileHidden() {
        val b = BgVideo()
        assertFalse(b.startHidden(true)) // shown: video as usual
        b.hide(false, BgSrc.STREAM)
        assertTrue(b.startHidden(watchStream = true)) // next item is a Watch stream: audio-only now
        assertTrue(b.audioForVideo)
        assertFalse(b.startHidden(watchStream = false)) // a Listen / saved item next
        assertFalse(b.audioForVideo)
        assertTrue(b.hidden)
        b.startHidden(true)
        assertEquals(listOf(BgAct.SWITCH_TO_VIDEO), b.show()) // back in the app: video again
        b.hide(true, BgSrc.STREAM); b.reset()
        assertEquals(BgState.HIDDEN, b.state) // closed while hidden: stays "hidden", no switch pending
    }

    @Test fun audioFocusWithPlainMusic() {
        val am = android.media.AudioManager::class.java
        fun c(n: String) = am.getField(n).getInt(null)
        assertEquals(Focus.Do.PAUSE_FOR_GOOD, Focus.onChange(c("AUDIOFOCUS_LOSS"), playing = true, resumeOnGain = false)) // Plain Music started
        assertEquals(Focus.Do.PAUSE_RESUME_LATER, Focus.onChange(c("AUDIOFOCUS_LOSS_TRANSIENT"), true, false)) // a call
        assertEquals(Focus.Do.NOTHING, Focus.onChange(c("AUDIOFOCUS_LOSS_TRANSIENT"), false, false))
        assertEquals(Focus.Do.DUCK, Focus.onChange(c("AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"), true, false))
        assertEquals(Focus.Do.RESUME, Focus.onChange(c("AUDIOFOCUS_GAIN"), false, resumeOnGain = true))
        assertEquals(Focus.Do.UNDUCK, Focus.onChange(c("AUDIOFOCUS_GAIN"), true, false))
    }

    // ---- 2.5 the player outlives the activity ----

    @Test fun attachAndDetachRules() {
        assertEquals(listOf(EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.MINI, stored = true)) // alive: shown as it is
        assertEquals(listOf(EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.FULL, stored = false))
        assertEquals(listOf(EngineRule.AttachStep.LEAVE_PIP, EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.PIP, true))
        assertEquals(listOf(EngineRule.AttachStep.RESTORE_PAUSED, EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.CLOSED, stored = true)) // process restarted
        assertEquals(listOf(EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.CLOSED, stored = false))
        assertTrue(EngineRule.keepOnDetach(wantsPlay = true)) // destroyed while playing: goes on
        assertFalse(EngineRule.keepOnDetach(wantsPlay = false)) // destroyed while paused: closes
    }

    @Test fun pausedWithoutWindowReleases() {
        assertTrue(EngineRule.releaseOnPause(hidden = true, windowAttached = true))
        assertTrue(EngineRule.releaseOnPause(hidden = false, windowAttached = false))
        assertFalse(EngineRule.releaseOnPause(hidden = false, windowAttached = true)) // in the app: keep caching
        assertTrue(EngineRule.canControl(PState.MINI, stored = false))
        assertTrue(EngineRule.canControl(PState.CLOSED, stored = true)) // notification after process death
        assertFalse(EngineRule.canControl(PState.CLOSED, stored = false))
    }

    @Test fun destroyWhilePlayingThenReattach() {
        // the activity stops (background rules), then is destroyed while playing, then a new one attaches
        val bg = BgVideo()
        assertEquals(listOf(BgAct.DETACH, BgAct.SCHEDULE_SWITCH), bg.hide(watch = true, src = BgSrc.STREAM))
        assertTrue(EngineRule.keepOnDetach(true))
        assertEquals(listOf(BgAct.SWITCH_TO_AUDIO), bg.timer(true)) // audio-only with no window
        assertEquals(listOf(EngineRule.AttachStep.SHOW), EngineRule.onAttach(PState.MINI, true))
        assertEquals(listOf(BgAct.SWITCH_TO_VIDEO), bg.show()) // the new window gets the video back
    }

    @Test fun resumeFromStoredStateWithoutWindow() {
        val l = LastPlay("jNQXAC9IVRw", "Me at the zoo", "jawed", PlayMode.WATCH, null, false, queue = false, album = null, pos = 12_345, view = "FULL")
        val back = LastPlay.decode(l.encode())!!
        assertEquals(l, back)
        assertNull(LastPlay.decode(null))
        assertNull(LastPlay.decode("{}"))
        assertNull(LastPlay.decode("not json"))
        val saved = LastPlay("", "file", "", PlayMode.LISTEN, "content://media/external/audio/media/5", true, false, null, 0, "MINI")
        assertEquals(saved, LastPlay.decode(saved.encode()))
        // rebuilt from it with no window: hidden, so the Watch item starts audio-only
        val bg = BgVideo()
        bg.hide(watch = true, src = BgSrc.STREAM)
        assertTrue(bg.startHidden(watchStream = back.mode == PlayMode.WATCH && back.uri == null))
        assertTrue(bg.startHidden(true)) // asked again (resume after a pause): still audio for video
        assertEquals(listOf(BgAct.SWITCH_TO_VIDEO), bg.show())
    }
}
