package app.localmusic

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import app.plain.lang.tr

/**
 * The playback engine (one per process). It owns the MediaPlayer(s), the queue and the MediaSession.
 *
 * Power rules: nothing here polls. The session's PlaybackState carries position + timestamp, so the lock
 * screen, notification and Bluetooth headset extrapolate progress themselves. MediaPlayer holds its partial
 * wake lock only while audio is playing. The next song is pre-prepared for gapless playback.
 *
 * Videos (Plain Youtube) play through the same MediaPlayer/queue/session. A MediaPlayer gets a surface only while the
 * now-playing screen shows the picture of the current video ([showVideo]); without one the framework never starts a
 * video decoder, so a video plays as audio. Audio items never touch any of this.
 *
 * When playback is paused (not a transient focus loss) the service leaves the foreground and stops; when additionally no
 * screen of ours is visible, the MediaPlayers are released too. Nothing stays alive for a paused player.
 */
object Player {
    private lateinit var app: Context
    val queue = PlayQueue()
    private var mp: MediaPlayer? = null
    private var nextMp: MediaPlayer? = null
    private var nextPos = -1
    var playing = false
        private set
    private var restorePosition = 0L
    lateinit var session: MediaSession
        private set
    private val listeners = ArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var resumeOnFocusGain = false
    /** A screen of ours is visible (MainActivity started). */
    var uiVisible = false
        private set
    private var hasFocus = false
    private var noisyRegistered = false
    private var metaFor = Long.MIN_VALUE
    var art: Bitmap? = null
        private set

    /** Sleep timer: elapsedRealtime when playback pauses (0 = off), or pause at the end of the current song. */
    var sleepAt = 0L
        private set
    var sleepEndOfSong = false
        private set

    /** Playback speed (0.5–2×, pitch kept), remembered. Not 1×: the next song isn't pre-started gaplessly (see prepareNext). */
    var speed = 1f
        private set

    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()

    private val audio by lazy { app.getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(::onFocusChange, main)
            .build()
    }

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        Store.init(app)
        SongResume.init(app)
        session = MediaSession(app, "LocalMusic").apply {
            setCallback(callback, main)
            setSessionActivity(openAppIntent(app))
            if (Build.VERSION.SDK_INT >= 31) setMediaButtonBroadcastReceiver(ComponentName(app, MediaButtonReceiver::class.java))
            else @Suppress("DEPRECATION") setMediaButtonReceiver(
                PendingIntent.getBroadcast(app, 0, Intent(Intent.ACTION_MEDIA_BUTTON).setClass(app, MediaButtonReceiver::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
        }
        val p = Store.prefs
        queue.restore(listOf("items", "order", "pos", "shuffle", "repeat").associateWith { p.getString("q_$it", null) })
        restorePosition = p.getLong("position", 0)
        speed = Speeds.valid(p.getFloat("speed", 1f))
    }

    fun openAppIntent(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    // --- state for the UI --------------------------------------------------------------------

    /** Debug builds only. */
    fun dlog(msg: String) {
        if (::app.isInitialized && app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("LocalMusic", msg)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    val current: Song? get() = queue.current()?.let { songOf(it) }

    /** Files opened from other apps (File Manager, WhatsApp...): negative ids, never saved or put in the library. */
    private val external = HashMap<Long, Song>()
    private var nextExternalId = -2L
    private var resumeAfterExternal: Pair<Long, Long>? = null // (song id, position) to return to when it ends

    /** The playing file vanished from MediaStore (trashed or deleted, e.g. by Plain Youtube): it plays on while open, then is skipped. */
    private val vanished = HashMap<Long, Song>()
    private var lastSong: Song? = null

    private fun songOf(id: Long): Song? = Library.index.byId[id] ?: external[id] ?: vanished[id]
    private fun known(id: Long) = id in Library.index.byId || id in external || id in vanished

    /** Drop vanished entries other than the one playing now. */
    private fun dropVanished() {
        if (vanished.isEmpty()) return
        val cur = queue.current()
        val gone = vanished.keys.filter { it != cur }
        if (gone.isEmpty()) return
        gone.forEach { vanished.remove(it) }
        queue.retain { known(it) }
    }

    /** LocalMusic itself erased [ids]: if the current song is among them, go on to the next one (stop if nothing is left). */
    fun skipErased(ids: Set<Long>) {
        val cur = queue.current() ?: return
        if (cur !in ids) return
        queue.retain { it !in ids || it == cur }
        if (queue.size <= 1) { stop(); queue.retain { it != cur }; changed(saveQueue = true); return }
        next()
    }

    /**
     * Play a file handed to us by another app. It plays once, inserted after the current song; when it ends the
     * player goes back to the song that was playing before (paused), so the user's queue is untouched.
     */
    fun playExternal(song: Song): Boolean {
        // Unplayable or unreadable? Leave whatever is playing alone.
        try { createPlayer(song).release() } catch (e: Exception) { dlog("can't open ${song.externalUri}: $e"); return false }
        val s = if (song.id >= 0 && song.id in Library.index.byId) song else {
            val id = nextExternalId--
            Song(id, song.title, song.artist, song.album, "", -1, "", 0, 1, 0, song.durationMs, "", song.name, song.size, 0, song.externalUri)
                .also { external[id] = it }
        }
        queue.current()?.let { cur -> if (songOf(cur)?.externalUri == null) resumeAfterExternal = cur to position() }
        dlog("playExternal ${s.title} resumeAfter=$resumeAfterExternal playing=$playing")
        if (queue.isEmpty()) queue.set(listOf(s.id), 0, queue.shuffle)
        else { queue.playNext(s.id); queue.jump(queue.peekNext(auto = false) ?: queue.pos) }
        prepareCurrent(0, play = true)
        return true
    }

    /** While paused the remembered position is the truth (MediaPlayer reports 0 until a pending seek completes). */
    fun position(): Long =
        if (playing) mp?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: restorePosition else restorePosition

    fun duration(): Long = current?.durationMs?.takeIf { it > 0 } ?: mp?.let { runCatching { it.duration.toLong() }.getOrNull() } ?: 0

    /** Library (re)loaded: drop queue entries whose files are gone and refresh the session. */
    fun onLibraryChanged() {
        val lib = Library.index
        if (lib.byId.isEmpty()) return
        val before = queue.current()
        if (playing && before != null && before !in lib.byId && before !in external) lastSong?.takeIf { it.id == before }?.let { vanished[before] = it }
        queue.retain { known(it) }
        if (queue.current() != before && mp != null) prepareCurrent(0, playing) else { prepareNext(); changed(saveQueue = true) }
    }

    private fun ensureLibrary() {
        if (Library.index.byId.isEmpty()) Library.load(app)
        if (!queue.isEmpty()) queue.retain { known(it) }
    }

    // --- transport ---------------------------------------------------------------------------

    fun play() {
        ensureLibrary()
        if (queue.isEmpty()) {
            val all = Library.index.songs.map { it.id }
            if (all.isEmpty()) return
            queue.setShuffled(all)
            restorePosition = 0
        }
        if (mp == null && !prepareCurrent(restorePosition, play = false)) return
        val p = mp ?: return
        if (!requestFocus()) return
        p.setVolume(1f, 1f)
        p.start()
        applySpeed(p) // after start(): setting PlaybackParams on a paused player would start it
        playing = true
        listenStart()
        registerNoisy()
        session.isActive = true
        changed() // starts the foreground service
    }

    fun pause(keepFocus: Boolean = false) {
        if (playing) mp?.let { p -> runCatching { restorePosition = p.currentPosition.toLong() } }
        mp?.let { if (it.isPlaying) it.pause() }
        val wasPlaying = playing
        playing = false
        listenStop()
        unregisterNoisy()
        if (!keepFocus) { abandonFocus(); resumeOnFocusGain = false } // a real pause: no auto-resume after a call
        if (wasPlaying) savePosition()
        releaseIfIdle()
        changed()
    }

    fun toggle() = if (playing) pause() else play()

    /** Playing, or paused for a moment by a phone call / navigation prompt: the service stays in the foreground. */
    val holdsForeground: Boolean get() = playing || resumeOnFocusGain

    // --- play history: no timer. Listening time is summed from elapsedRealtime at play/pause/track change, and the
    // song is counted (once per time it is played) when it stops or changes, if it ran 30 s or half its length.
    private var listenId = Long.MIN_VALUE
    private var listenMs = 0L
    private var listenFrom = 0L // elapsedRealtime when the current stretch of playing began (0 = not playing)
    private var listenCounted = false

    private fun listenStart() {
        val id = queue.current() ?: return
        if (id != listenId) { listenId = id; listenMs = 0; listenCounted = false }
        listenFrom = SystemClock.elapsedRealtime()
    }

    private fun listenStop() {
        if (listenFrom > 0) { listenMs += SystemClock.elapsedRealtime() - listenFrom; listenFrom = 0 }
        if (listenCounted) return
        val s = songOf(listenId) ?: return
        if (PlayHistory.counts(listenMs, s.durationMs)) { listenCounted = true; Store.recordPlay(s) }
    }

    /** The same song starts over (repeat one, or played again): it can count again. */
    private fun listenRestart() { listenStop(); listenId = Long.MIN_VALUE }

    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        if (!visible) releaseIfIdle()
    }

    /** Paused with no screen of ours visible: free the decoders. play() prepares again at the remembered position. */
    private fun releaseIfIdle() {
        if (playing || resumeOnFocusGain || uiVisible || mp == null) return
        savePosition(restorePosition)
        releasePlayers()
    }

    fun next() {
        val n = queue.peekNext(auto = false)
        if (n == null) { // end of the queue (repeat off): back to the first song, paused
            queue.jump(0); prepareCurrent(0, play = false); pause(); return
        }
        queue.jump(n)
        prepareCurrent(autoStart(), playing)
        releaseIfIdle()
    }

    fun prev() {
        if (position() > 3000) { seekTo(0); return }
        queue.peekPrev()?.let { queue.jump(it) }
        prepareCurrent(autoStart(), playing)
        releaseIfIdle()
    }

    /** Long items (videos, podcasts: 10 min or more) the queue moves on to continue where they were left; songs start over. */
    private fun autoStart(id: Long? = queue.current()): Long {
        val s = id?.let { songOf(it) } ?: return 0
        return if (s.isVideo || s.durationMs >= LONG_MS) SongResume.get(s) else 0
    }
    private const val LONG_MS = 10 * 60_000L

    fun seekTo(ms: Long) {
        val p = mp
        restorePosition = ms.coerceIn(0, duration().takeIf { it > 0 } ?: Long.MAX_VALUE)
        if (p != null) runCatching { p.seekTo(restorePosition.toInt()) }
        savePosition(ms)
        changed()
    }

    /** ±10 s (buttons, double-tap, notification, lock screen, headset fast-forward/rewind). */
    fun seekBy(delta: Long) {
        if (current == null) ensureLibrary() // a notification button after the process was reclaimed
        if (current == null) return
        seekTo(SeekStep.target(position(), delta, duration()))
    }

    fun setSpeed(s: Float) {
        speed = Speeds.valid(s)
        Store.prefs.edit().putFloat("speed", speed).apply()
        if (playing) mp?.let { applySpeed(it) } // a paused player gets it in play()
        prepareNext(); changed()
    }

    private fun applySpeed(p: MediaPlayer) {
        runCatching { if (p.playbackParams.speed != speed) p.playbackParams = (p.playbackParams ?: PlaybackParams()).setSpeed(speed) }
    }

    fun stop() {
        pause()
        releasePlayers()
        session.isActive = false // sync() then removes the notification and stops the service
        changed()
    }

    /** Play [ids] starting at [index] (keeps the current shuffle mode). */
    /** Play [ids] from [index]; [startAt] = where that song was left ("Continue from last session?", 3.3). */
    fun playList(ids: List<Long>, index: Int, startAt: Long = 0) {
        if (ids.isEmpty()) return
        queue.set(ids, index, queue.shuffle)
        prepareCurrent(startAt, play = true)
    }

    fun shufflePlay(ids: List<Long>) {
        if (ids.isEmpty()) return
        queue.setShuffled(ids)
        prepareCurrent(0, play = true)
    }

    /** A song tapped in the queue: continues where it was left. */
    fun jump(playPos: Int) {
        queue.jump(playPos)
        prepareCurrent(queue.current()?.let { songOf(it) }?.let { SongResume.get(it) } ?: 0, play = true)
    }

    fun toggleShuffle() {
        queue.setShuffle(!queue.shuffle)
        prepareNext(); changed(saveQueue = true)
    }

    fun cycleRepeat() {
        queue.repeat = queue.repeat.next()
        prepareNext(); changed(saveQueue = true)
    }

    fun playNext(songs: List<Song>) {
        val wasEmpty = queue.isEmpty()
        for (s in songs.reversed()) queue.playNext(s.id)
        if (wasEmpty) prepareCurrent(0, play = false) else { prepareNext(); changed(saveQueue = true) }
    }

    fun addToQueue(songs: List<Song>) {
        val wasEmpty = queue.isEmpty()
        queue.addToEnd(songs.map { it.id })
        if (wasEmpty) prepareCurrent(0, play = false) else { prepareNext(); changed(saveQueue = true) }
    }

    fun moveInQueue(from: Int, to: Int) {
        queue.move(from, to)
        prepareNext(); changed(saveQueue = true)
    }

    fun removeFromQueue(playPos: Int) {
        val wasCurrent = playPos == queue.pos
        queue.removeAt(playPos)
        when {
            queue.isEmpty() -> stop()
            wasCurrent -> prepareCurrent(0, playing)
            else -> { prepareNext(); changed(saveQueue = true) }
        }
    }

    fun clearUpcoming() {
        for (p in queue.size - 1 downTo queue.pos + 1) queue.removeAt(p)
        prepareNext(); changed(saveQueue = true)
    }

    // --- sleep timer -------------------------------------------------------------------------

    private val sleepRunnable = Runnable { sleepAt = 0; pause() }

    fun setSleepMinutes(min: Int) {
        main.removeCallbacks(sleepRunnable)
        sleepEndOfSong = false
        sleepAt = if (min > 0) SystemClock.elapsedRealtime() + min * 60_000L else 0
        if (min > 0) main.postDelayed(sleepRunnable, min * 60_000L)
        prepareNext(); changed()
    }

    fun setSleepEndOfSong() {
        main.removeCallbacks(sleepRunnable)
        sleepAt = 0
        sleepEndOfSong = true
        prepareNext(); changed()
    }

    // --- MediaPlayer plumbing ----------------------------------------------------------------

    private fun createPlayer(s: Song): MediaPlayer {
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(attrs)
            p.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(app, s.externalUri?.let { Uri.parse(it) } ?: Library.uri(s))
            p.prepare() // local file: a few ms
        } catch (e: Exception) {
            p.release(); throw e
        }
        p.setOnCompletionListener(::onCompletion)
        p.setOnErrorListener { which, _, _ -> onError(which); true }
        return p
    }

    private fun releasePlayers() {
        videoOn = null // a released player lets go of its surface
        nextMp?.release(); nextMp = null; nextPos = -1
        mp?.release(); mp = null
    }

    /** Load the queue's current song. Unplayable files are skipped (and dropped from the queue). */
    private fun prepareCurrent(startAt: Long, play: Boolean): Boolean {
        if (startAt == 0L) listenRestart() else listenStop() // the previous song (or this one from the start again)
        rememberLoaded()
        dropVanished()
        releasePlayers()
        var tries = queue.size
        while (tries-- > 0) {
            val id = queue.current() ?: break
            val s = songOf(id)
            if (s != null) {
                try {
                    val p = createPlayer(s)
                    if (startAt > 0 && startAt < s.durationMs) p.seekTo(startAt.toInt())
                    mp = p
                    loadedId = id
                    attachVideo(p)
                    restorePosition = startAt
                    break
                } catch (_: Exception) {}
            }
            queue.removeAt(queue.pos) // missing or broken file
        }
        if (mp == null) {
            playing = false; unregisterNoisy(); abandonFocus()
            changed(saveQueue = true)
            return false
        }
        prepareNext()
        savePosition(startAt)
        if (play) play() else { if (playing) { playing = false; unregisterNoisy() }; changed(saveQueue = true) }
        if (play) saveQueue()
        return true
    }

    /** Gapless: hand the following song to MediaPlayer.setNextMediaPlayer. */
    private fun prepareNext() {
        nextMp?.release(); nextMp = null; nextPos = -1
        val p = mp ?: return
        val n = queue.peekNext(auto = true)
        val ext = current?.externalUri != null
        val loop = !ext && !sleepEndOfSong && n != null && n == queue.pos
        runCatching { p.isLooping = loop }
        // Not at 1×: a pre-started next player would begin at 1× (setting its speed would start it early), so the next
        // song is prepared when it starts and gets the speed in play().
        if (ext || loop || n == null || sleepEndOfSong || speed != 1f) { runCatching { p.setNextMediaPlayer(null) }; return }
        val s = songOf(queue.idAt(n)) ?: return
        // An item that continues where it was left is prepared when it starts (at that place), not gaplessly.
        if (autoStart(s.id) > 0) { runCatching { p.setNextMediaPlayer(null) }; return }
        // A video while its picture is on screen is prepared when it starts (with the surface), not gaplessly:
        // the prepared-next player never gets a surface.
        if (s.isVideo && videoHolder != null) { runCatching { p.setNextMediaPlayer(null) }; return }
        try {
            val np = createPlayer(s)
            p.setNextMediaPlayer(np)
            nextMp = np; nextPos = n
        } catch (_: Exception) {
            runCatching { p.setNextMediaPlayer(null) }
        }
    }

    private fun onCompletion(done: MediaPlayer) {
        if (done !== mp) return
        if (loadedId != Long.MIN_VALUE) SongResume.clear(loadedId) // played to the end: next time from the start
        loadedId = Long.MIN_VALUE
        listenRestart()
        val np = nextMp
        if (np != null && nextPos >= 0) { // the framework already started the next player
            queue.jump(nextPos)
            dropVanished()
            mp = np; nextMp = null; nextPos = -1
            loadedId = queue.current() ?: Long.MIN_VALUE
            if (videoOn === done) videoOn = null
            done.release()
            attachVideo(np)
            restorePosition = 0
            prepareNext()
            listenStart()
            changed(saveQueue = true)
            savePosition(0)
            return
        }
        if (current?.externalUri != null) { // an opened file ends: back to what was playing before, paused
            val back = resumeAfterExternal
            resumeAfterExternal = null
            dlog("external ended, back to $back")
            val endedId = queue.current()
            queue.removeAt(queue.pos)
            external.remove(endedId)
            if (back != null) queue.playOrder().indexOf(back.first).takeIf { it >= 0 }?.let { queue.jump(it) }
            if (queue.isEmpty()) { releasePlayers(); pause(); return }
            prepareCurrent(back?.second ?: 0, play = false); pause(); return
        }
        val n = queue.peekNext(auto = true)
        if (sleepEndOfSong) {
            sleepEndOfSong = false
            if (n != null) queue.jump(n)
            prepareCurrent(0, play = false); pause(); return
        }
        if (n == null) { queue.jump(0); prepareCurrent(0, play = false); pause(); return }
        queue.jump(n)
        prepareCurrent(autoStart(), play = true)
    }

    private fun onError(p: MediaPlayer) {
        if (p === nextMp) { nextMp?.release(); nextMp = null; nextPos = -1; return }
        if (p !== mp) return
        val wasPlaying = playing
        val n = queue.peekNext(auto = false)
        if (n == null || queue.size <= 1) { releasePlayers(); pause(); return }
        queue.jump(n)
        prepareCurrent(0, wasPlaying)
    }

    // --- video picture -----------------------------------------------------------------------
    // Only the now-playing screen calls showVideo(), and only for a video item with the Video toggle on while the
    // activity is visible. Audio playback reads videoHolder (null) and nothing else.

    private var videoHolder: SurfaceHolder? = null
    private var videoOn: MediaPlayer? = null // the player that has the surface

    /** Show the current video's picture on [holder], or stop drawing it (null): the video decoder is then shut down. */
    fun showVideo(holder: SurfaceHolder?) {
        if (holder === videoHolder) return
        videoHolder = holder
        videoOn?.let { runCatching { it.setDisplay(null) } }
        videoOn = null
        mp?.let { attachVideo(it) }
        // A following video is prepared gaplessly only while no picture is shown (see prepareNext).
        val n = queue.peekNext(auto = true)
        if (mp != null && n != null && songOf(queue.idAt(n))?.isVideo == true) prepareNext()
    }

    private fun attachVideo(p: MediaPlayer) {
        val h = videoHolder ?: return
        if (current?.isVideo != true) return
        runCatching {
            p.setDisplay(h)
            p.setScreenOnWhilePlaying(true) // the screen stays on only while the picture plays
            videoOn = p
        }
    }

    // --- audio focus & noisy -----------------------------------------------------------------

    private fun requestFocus(): Boolean {
        if (hasFocus) return true
        hasFocus = audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasFocus
    }

    private fun abandonFocus() {
        if (!hasFocus) return
        audio.abandonAudioFocusRequest(focusRequest)
        hasFocus = false
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumeOnFocusGain = false; hasFocus = false; pause() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { if (playing) { resumeOnFocusGain = true; pause(keepFocus = true) } }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.25f, 0.25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp?.setVolume(1f, 1f)
                if (resumeOnFocusGain) { resumeOnFocusGain = false; play() }
            }
        }
    }

    /** Headphones unplugged / Bluetooth disconnected: pause. Registered only while playing. */
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (playing) pause() }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val f = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisy, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(noisy, f)
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { app.unregisterReceiver(noisy) }
        noisyRegistered = false
    }

    // --- persistence -------------------------------------------------------------------------

    fun savePosition(pos: Long = position()) {
        if (!::app.isInitialized) return
        Store.prefs.edit().putLong("position", pos).apply()
        current?.let { if (it.id == loadedId) SongResume.put(it.id, pos, it.durationMs) }
    }

    /** The song the player holds now ([mp]); its position is kept per song when another one is loaded (3.3). */
    private var loadedId = Long.MIN_VALUE

    private fun rememberLoaded() {
        val id = loadedId
        if (id == Long.MIN_VALUE || mp == null) return
        val s = songOf(id) ?: return
        val pos = if (playing) mp?.let { runCatching { it.currentPosition.toLong() }.getOrNull() } ?: restorePosition else restorePosition
        SongResume.put(id, pos, s.durationMs)
    }

    private fun saveQueue() {
        val e = Store.prefs.edit()
        val saved = PlayQueue().apply { restore(queue.save()); repeat = queue.repeat; retain { it >= 0 } } // opened files aren't kept
        for ((k, v) in saved.save()) e.putString("q_$k", v)
        e.apply()
    }

    // --- session / notification --------------------------------------------------------------

    private fun changed(saveQueue: Boolean = false) {
        lastSong = current
        if (saveQueue) saveQueue()
        updateSession()
        PlaybackService.sync(app)
        for (l in listeners.toList()) l()
    }

    private fun updateSession() {
        val s = current
        val state = when {
            s == null -> PlaybackState.STATE_NONE
            playing -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND,
                )
                // Android 13+ media controls (lock screen, quick settings) show these next to prev/play/next
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_BACK, tr("Back 10 s", "10 sn geri"), R.drawable.mus_ic_back10).build())
                .addCustomAction(PlaybackState.CustomAction.Builder(CUSTOM_FWD, tr("Forward 10 s", "10 sn ileri"), R.drawable.mus_ic_fwd10).build())
                .setState(state, position(), if (playing) speed else 0f, SystemClock.elapsedRealtime())
                .build(),
        )
        val id = s?.id ?: Long.MIN_VALUE + 1 // "no song" (external ids are negative)
        if (id != metaFor) {
            metaFor = id
            art = null
            setMetadata(s, null)
            if (s != null) Art.async(app, s.id, 512) { b ->
                if (metaFor == s.id && b != null) { art = b; setMetadata(s, b); PlaybackService.sync(app); for (l in listeners.toList()) l() }
            }
        }
    }

    /** Cover art of [ids] changed on disk: reload the notification / lock-screen art if the current song is one of them. */
    fun onArtChanged(ids: Set<Long>) {
        if (!::app.isInitialized) return
        val cur = queue.current() ?: return
        if (cur in ids) main.post { metaFor = Long.MIN_VALUE; updateSession(); PlaybackService.sync(app) }
    }

    private fun setMetadata(s: Song?, bmp: Bitmap?) {
        if (s == null) { session.setMetadata(null); return }
        val b = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, s.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.shownArtist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, s.album)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, s.mainArtist)
            .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, s.id.toString())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, s.durationMs)
            .putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, queue.pos + 1L)
            .putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, queue.size.toLong())
        if (bmp != null) b.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bmp)
        session.setMetadata(b.build())
    }

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = play()
        override fun onPause() = pause()
        override fun onSkipToNext() = next()
        override fun onSkipToPrevious() = prev()
        override fun onSeekTo(pos: Long) = seekTo(pos)
        override fun onFastForward() = seekBy(SeekStep.MS)
        override fun onRewind() = seekBy(-SeekStep.MS)
        override fun onCustomAction(action: String, extras: android.os.Bundle?) {
            when (action) { CUSTOM_BACK -> seekBy(-SeekStep.MS); CUSTOM_FWD -> seekBy(SeekStep.MS) }
        }
        override fun onStop() = stop()
        // the headset / Bluetooth button: 1× play/pause, 2× next, 3× previous, hold = stop after this song
        override fun onMediaButtonEvent(i: android.content.Intent): Boolean {
            @Suppress("DEPRECATION")
            val k = (if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(android.content.Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else i.getParcelableExtra<KeyEvent>(android.content.Intent.EXTRA_KEY_EVENT)) ?: return super.onMediaButtonEvent(i)
            if (k.keyCode != KeyEvent.KEYCODE_HEADSETHOOK && k.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) return super.onMediaButtonEvent(i)
            buttonKey(k)
            return true
        }
    }

    private var presses = 0
    private var held = false
    private val flushPresses = Runnable {
        val n = presses
        presses = 0
        when { n == 1 -> toggle(); n == 2 -> next(); n >= 3 -> prev() }
    }

    /** Counts presses of the one headset button (a short wait tells 1, 2 and 3 apart). */
    private fun buttonKey(k: KeyEvent) {
        when (k.action) {
            KeyEvent.ACTION_DOWN -> if (k.repeatCount == 0) held = false
                else if (!held) { held = true; main.removeCallbacks(flushPresses); presses = 0; toggleStopAfterSong() }
            KeyEvent.ACTION_UP -> if (!held) { presses++; main.removeCallbacks(flushPresses); main.postDelayed(flushPresses, 450) }
        }
    }

    /** Pause after the current song, or cancel that (hold play/pause, hold the headset button). */
    fun toggleStopAfterSong(): Boolean {
        if (sleepEndOfSong) setSleepMinutes(0) else setSleepEndOfSong()
        return sleepEndOfSong
    }
    private const val CUSTOM_BACK = "back10"
    private const val CUSTOM_FWD = "fwd10"


    /** Keys from MediaButtonReceiver (when no session was active). */
    fun handleKey(code: Int) {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> toggle()
            KeyEvent.KEYCODE_MEDIA_NEXT -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            KeyEvent.KEYCODE_MEDIA_STOP -> stop()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekBy(SeekStep.MS)
            KeyEvent.KEYCODE_MEDIA_REWIND -> seekBy(-SeekStep.MS)
        }
    }
}
