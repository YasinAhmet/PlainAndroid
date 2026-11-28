package app.localmusic

/** Playback speeds offered (PlaybackParams, pitch kept). The same list as Plain Youtube's player. */
object Speeds {
    val ALL = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    fun label(s: Float) = (if (s == s.toInt().toFloat()) s.toInt().toString() else s.toString()) + "×"
    /** A stored value that isn't on the list (or garbage) falls back to normal speed. */
    fun valid(s: Float) = if (s in ALL) s else 1f
}

/** Volume bar <-> media stream volume, and mute that remembers the level (as in Plain Youtube). */
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

/** Seek by a step (±10 s): clamped to the start; past the end it stops just short of it so the song isn't cut off. */
object SeekStep {
    const val MS = 10_000L
    fun target(pos: Long, delta: Long, dur: Long): Long {
        val t = (pos + delta).coerceAtLeast(0)
        return if (dur > 0) t.coerceAtMost((dur - 1000).coerceAtLeast(0)) else t
    }
}

/** Sleep timer fade-out: the volume over the last [MS] before it pauses (squared: closer to how loudness is heard). */
object SleepFade {
    const val MS = 30_000L
    fun volume(leftMs: Long): Float = when {
        leftMs >= MS -> 1f
        leftMs <= 0 -> 0f
        else -> (leftMs.toFloat() / MS).let { it * it }
    }
}

/** Picture-in-picture aspect ratio: Android accepts only 1:2.39 … 2.39:1. */
object PipRatio {
    fun of(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 16 to 9
        val r = w.toDouble() / h
        return when {
            r > 2.39 -> 239 to 100
            r < 1 / 2.39 -> 100 to 239
            else -> w to h
        }
    }
}
