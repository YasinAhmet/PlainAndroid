package app.trekmap.trail

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * All network access goes through here, and every request is opened *on the Wi-Fi Network object*.
 * Even if Android would route traffic over mobile data, these sockets can only use Wi-Fi.
 */
object Net {
    const val UA = "TrekmapTrail/1.0 (personal offline hiking map; Android)"

    class Http(val code: Int) : IOException("HTTP $code")

    /** True in the stress-test build: every request fails as if offline. */
    @Volatile var offline = false

    /** GET over Wi-Fi. A "Range" header is honoured (206 Partial Content counts as success). */
    fun get(net: Network, url: String, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 30_000): ByteArray {
        if (offline) throw IOException("network disabled (stress-test build)")
        val c = net.openConnection(URL(url)) as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = readTimeoutMs
        c.setRequestProperty("User-Agent", UA)
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            val code = c.responseCode
            if (code != 200 && !(code == 206 && headers.containsKey("Range"))) throw Http(code)
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** Tracks the current Wi-Fi network with internet. Callback runs on a binder thread. */
    class WifiWatcher(ctx: Context, private val onChange: (Network?) -> Unit) {
        private val cm = ctx.getSystemService(ConnectivityManager::class.java)
        private val nets = LinkedHashSet<Network>()
        private var registered = false

        @Volatile
        var current: Network? = null
            private set

        private val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(n: Network) {
                synchronized(nets) { nets.add(n) }
                update()
            }

            override fun onLost(n: Network) {
                synchronized(nets) { nets.remove(n) }
                update()
            }
        }

        private fun update() {
            val n = synchronized(nets) { nets.lastOrNull() }
            if (n != current) {
                current = n
                onChange(n)
            }
        }

        fun start() {
            if (registered) return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            try {
                cm.registerNetworkCallback(req, cb)
                registered = true
            } catch (e: Exception) {
            }
        }

        fun stop() {
            if (registered) try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) {}
            registered = false
            synchronized(nets) { nets.clear() }
            current = null
        }
    }
}
