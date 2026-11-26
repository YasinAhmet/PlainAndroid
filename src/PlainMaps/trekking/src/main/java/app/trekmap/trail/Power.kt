package app.trekmap.trail

import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import app.plain.lang.tr
import kotlin.math.abs

/** Battery-related helpers: airplane mode, battery-saver GPS limits, 60 Hz display. */
object Power {
    fun airplaneOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) != 0

    /**
     * If Android's battery saver is on and set to limit location with the screen off, says how (else null).
     * That silently stops a recording in the pocket, so the app warns about it.
     */
    fun saverLimitsGps(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 28) return null
        val pm = ctx.getSystemService(PowerManager::class.java)
        if (!pm.isPowerSaveMode) return null
        return when (pm.locationPowerSaveMode) {
            1 -> tr("turns GPS off whenever the screen is off", "ekran kapanınca GPS'i kapatıyor")              // LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF
            2 -> tr("turns location off whenever the screen is off", "ekran kapanınca konumu kapatıyor")         // LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF
            4 -> tr("throttles location while the screen is off", "ekran kapalıyken konumu kısıtlıyor")          // LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF
            else -> null
        }
    }

    /** Asks for a 60 Hz display mode for this app's window: a mostly still map doesn't need 90/120 Hz. */
    fun prefer60Hz(a: Activity) {
        try {
            val d = (if (Build.VERSION.SDK_INT >= 30) a.display else @Suppress("DEPRECATION") a.windowManager.defaultDisplay) ?: return
            val cur = d.mode
            val m = d.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .minByOrNull { abs(it.refreshRate - 60f) } ?: return
            if (m.refreshRate > 61f) return
            a.window.attributes = a.window.attributes.apply {
                preferredDisplayModeId = m.modeId
                preferredRefreshRate = m.refreshRate
            }
            Dev.event("display", "refresh ${cur.refreshRate.toInt()} Hz → asked for ${m.refreshRate.toInt()} Hz " +
                "(modes: ${d.supportedModes.joinToString { "${it.refreshRate.toInt()}" }})")
        } catch (e: Exception) {
            Dev.event("display", "60 Hz request failed: $e")
        }
    }
}
