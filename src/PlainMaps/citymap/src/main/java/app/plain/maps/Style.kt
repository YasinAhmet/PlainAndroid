package app.plain.maps

import android.content.SharedPreferences

/** Line looks the user can tune in Settings (multipliers of the defaults). */
object Style {
    /** The faint bus web baked into the map. */
    @Volatile var webWidth = 1f
    @Volatile var webAlpha = 1f
    /** Metro, tram and ferry lines in the map. */
    @Volatile var railWidth = 1f
    /** Lines drawn over the map for a selection (nearby lines, a route). */
    @Volatile var lineWidth = 1f

    fun load(p: SharedPreferences) {
        webWidth = p.getFloat("st_webW", 1f); webAlpha = p.getFloat("st_webA", 1f)
        railWidth = p.getFloat("st_railW", 1f); lineWidth = p.getFloat("st_lineW", 1f)
    }

    fun save(p: SharedPreferences) {
        p.edit().putFloat("st_webW", webWidth).putFloat("st_webA", webAlpha).putFloat("st_railW", railWidth).putFloat("st_lineW", lineWidth).apply()
    }
}
