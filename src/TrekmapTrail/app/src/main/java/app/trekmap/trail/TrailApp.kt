package app.trekmap.trail

import android.app.Application

class TrailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ShareProvider.AUTH = "$packageName.share"
        Net.offline = packageName.endsWith(".stress") // stress-test build: random taps must never download
        Store.init(this)
        Packs.init(this)
        Notif.channels(this)
        Dev.init(this)
    }
}
