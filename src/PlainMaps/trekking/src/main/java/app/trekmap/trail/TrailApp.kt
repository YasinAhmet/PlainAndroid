package app.trekmap.trail

import android.app.Application
import app.plain.lang.PlainLang

/**
 * What Plain Trekking's Application did at process start. In Plain Maps the Trekking tab runs in its own ":trail"
 * process (a map tab running out of memory can't take a recording down with it), and Plain Maps' Application calls
 * [init] there. [shareAuthority] is set even while old Plain Trekking data still has to be copied (then [init] waits,
 * so nothing reads the empty state first).
 */
object TrailInit {
    /** SharedPreferences name (it was "app" in Plain Trekking; Plain Maps' Istanbul tab uses that one). */
    const val PREFS = "trail"

    private var done = false

    fun shareAuthority(app: Application) {
        ShareProvider.AUTH = "${app.packageName}.share"
    }

    fun init(app: Application) {
        if (done) return
        done = true
        PlainLang.init(app) // before the notification channels get their names
        shareAuthority(app)
        Net.offline = app.packageName.endsWith(".stress") // stress-test build: random taps must never download
        Store.init(app)
        Packs.init(app)
        Notif.channels(app)
        Dev.init(app)
    }
}
