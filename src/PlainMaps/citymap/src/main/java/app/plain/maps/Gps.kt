package app.plain.maps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper

/**
 * Location only while the user has switched it on and the app is on screen.
 * It is never on at start, and the activity stops it whenever it leaves the screen.
 */
class Gps(ctx: Context, private val onFix: (Location) -> Unit) {
    private val lm = ctx.getSystemService(LocationManager::class.java)
    var on = false
        private set

    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) = onFix(l)
        @Deprecated("needed on API < 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    /** Starts updates (permission must already be granted). False if no location provider is on. */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (on) return true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (_: Exception) { false }
        }
        if (providers.isEmpty()) return false
        // show the last known position at once, the fresh fix follows
        providers.mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Exception) { null } }
            .filter { System.currentTimeMillis() - it.time < 10 * 60_000 }
            .minByOrNull { it.accuracy }?.let { onFix(it) }
        for (p in providers) {
            try { lm.requestLocationUpdates(p, 1000L, 0f, listener, Looper.getMainLooper()) } catch (_: Exception) {}
        }
        on = true
        return true
    }

    fun stop() {
        if (!on) return
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        on = false
    }
}
