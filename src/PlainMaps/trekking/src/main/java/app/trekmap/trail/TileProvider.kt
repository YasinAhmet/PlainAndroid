package app.trekmap.trail

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Loads tiles in the background into a bounded memory cache.
 * Only tiles wanted by the most recent frame are loaded, so panning never builds a backlog.
 * [fetch] returns bytes, EMPTY for "does not exist" (draw a parent instead), or null for "try later".
 */
class TileProvider(
    threads: Int,
    cacheBytes: Int,
    private val fetch: (Layer, Int, Int, Int) -> ByteArray?,
) {
    companion object {
        val EMPTY = ByteArray(0)

        fun key(l: Layer, z: Int, x: Int, y: Int): Long =
            (l.ordinal.toLong() shl 58) or (z.toLong() shl 52) or (x.toLong() shl 26) or y.toLong()
    }

    @Volatile var onLoaded: (() -> Unit)? = null

    private val cache = object : LruCache<Long, Bitmap>(cacheBytes) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private val wanted = LinkedHashSet<Long>()
    private val inflight = HashSet<Long>()
    private val missing = HashSet<Long>()
    @Volatile private var stopped = false

    // development stats
    private val nLoaded = java.util.concurrent.atomic.AtomicLong()
    private val nMissing = java.util.concurrent.atomic.AtomicLong()
    private val nRetry = java.util.concurrent.atomic.AtomicLong()
    private val fetchNs = java.util.concurrent.atomic.AtomicLong()
    private val maxFetchNs = java.util.concurrent.atomic.AtomicLong()

    fun stats(): String {
        val n = nLoaded.get() + nMissing.get() + nRetry.get()
        val avg = if (n > 0) fetchNs.get() / n / 1_000_000.0 else 0.0
        return "tiles loaded ${nLoaded.get()} · missing ${nMissing.get()} · unavailable ${nRetry.get()} · " +
            "load avg ${"%.1f".format(java.util.Locale.US, avg)} ms max ${maxFetchNs.get() / 1_000_000} ms · cache ${cache.size() / 1024} KB"
    }

    init {
        repeat(threads) { thread(isDaemon = true, name = "tiles-$it") { work() } }
    }

    fun get(k: Long): Bitmap? = cache.get(k)
    fun isMissing(k: Long) = lock.withLock { k in missing }
    fun beginFrame() = lock.withLock { wanted.clear() }
    fun want(k: Long) = lock.withLock { if (k !in inflight) wanted.add(k) }
    fun endFrame() = lock.withLock { if (wanted.isNotEmpty()) cond.signalAll() }
    fun clearMissing() = lock.withLock { missing.clear() }

    fun clearAll() {
        cache.evictAll()
        clearMissing()
    }

    fun shutdown() {
        stopped = true
        onLoaded = null
        lock.withLock {
            wanted.clear()
            cond.signalAll()
        }
        cache.evictAll()
    }

    private fun work() {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }
        val optsAlpha = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 } // overlay layers
        while (!stopped) {
            val k: Long = lock.withLock {
                while (!stopped && wanted.isEmpty()) cond.await()
                if (stopped) return
                val it = wanted.iterator()
                val k = it.next()
                it.remove()
                inflight.add(k)
                k
            }
            val layer = Layer.entries[(k ushr 58).toInt()]
            val z = ((k ushr 52) and 63L).toInt()
            val x = ((k ushr 26) and 0x3FFFFFFL).toInt()
            val y = (k and 0x3FFFFFFL).toInt()
            var bmp: Bitmap? = null
            var miss = false
            val t0 = System.nanoTime()
            try {
                val data = fetch(layer, z, x, y)
                if (data != null) {
                    if (data.isEmpty()) miss = true
                    else {
                        bmp = BitmapFactory.decodeByteArray(data, 0, data.size, if (layer.minOverlayZ > 0) optsAlpha else opts)
                        if (bmp == null) miss = true
                    }
                }
            } catch (_: Throwable) {
            }
            val dt = System.nanoTime() - t0
            fetchNs.addAndGet(dt)
            if (dt > maxFetchNs.get()) maxFetchNs.set(dt)
            when {
                bmp != null -> nLoaded.incrementAndGet()
                miss -> nMissing.incrementAndGet()
                else -> nRetry.incrementAndGet()
            }
            lock.withLock {
                inflight.remove(k)
                if (miss) missing.add(k)
            }
            if (stopped) return
            if (bmp != null) cache.put(k, bmp)
            if (bmp != null || miss) onLoaded?.invoke()
        }
    }
}
