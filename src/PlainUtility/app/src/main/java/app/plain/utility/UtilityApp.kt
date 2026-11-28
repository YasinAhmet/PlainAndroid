package app.plain.utility

import android.app.Application
import app.plain.hub.Hub
import app.plain.hub.Tab

/**
 * Plain Utility: Clock (default: pomodoro, timer, stopwatch), Notes (quick text/voice notes with reminders) and
 * Routines (daily repeating reminders; lives in the notes module).
 */
class UtilityApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.setup(this, "Plain Utility", listOf(
            Tab("clock", "Clock", "Saat", "app.plain.clock.ClockActivity"),
            Tab("notes", "Notes", "Notlar", "app.plain.notes.NotesActivity"),
            Tab("routines", "Routines", "Rutinler", "app.plain.notes.RoutinesActivity"),
        ), accent = ACCENT)
    }

    companion object {
        /** Plain Utility's colour: violet. */
        const val ACCENT = 0xFFA594F9.toInt()
    }
}
