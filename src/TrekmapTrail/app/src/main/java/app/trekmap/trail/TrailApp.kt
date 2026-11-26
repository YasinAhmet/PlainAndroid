package app.trekmap.trail

import android.app.Application
import app.plain.lang.PlainLang

class TrailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PlainLang.init(this) // before the notification channels get their names
        ShareProvider.AUTH = "$packageName.share"
        Net.offline = packageName.endsWith(".stress") // stress-test build: random taps must never download
        Store.init(this)
        Packs.init(this)
        Notif.channels(this)
        Dev.init(this)
    }
}
