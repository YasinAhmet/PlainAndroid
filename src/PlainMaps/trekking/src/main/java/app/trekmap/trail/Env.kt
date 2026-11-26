package app.trekmap.trail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.telephony.TelephonyManager

/**
 * What else is going on in the phone while recording, for working out where the battery goes:
 * airplane mode, radios, Bluetooth audio, music, screen, and the app's own CPU time.
 *
 * Costs nothing extra: [snapshot] is read inside the 5-minute battery row (piggy-backing on a GPS
 * wake-up), and [Watcher] only reacts to changes Android announces anyway (screen on/off, airplane
 * toggled, headphones connected, music started/stopped). No permission beyond the existing ones.
 */
object Env {
    /** Column names appended to the B row (see DevSummary). */
    const val COLUMNS = "air,bt,btAudio,music,out,vol,wifi,net,cellLvl,cellDbm,bright,autoBright,cpuMs"

    private var lastCpuMs = -1L

    private fun global(ctx: Context, key: String) =
        try { Settings.Global.getInt(ctx.contentResolver, key, -1) } catch (_: Exception) { -1 }

    fun airplane(ctx: Context) = global(ctx, Settings.Global.AIRPLANE_MODE_ON) == 1

    /** Where music would play now: bt / wired / speaker. */
    fun output(am: AudioManager): String {
        val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { emptyArray() }
        return when {
            outs.any { it.type in BT_TYPES } -> "bt"
            outs.any { it.type in WIRED_TYPES } -> "wired"
            else -> "speaker"
        }
    }

    private val BT_TYPES = buildSet {
        add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP); add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        if (Build.VERSION.SDK_INT >= 31) { add(AudioDeviceInfo.TYPE_BLE_HEADSET); add(AudioDeviceInfo.TYPE_BLE_SPEAKER) }
    }
    private val WIRED_TYPES = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)

    /** The values for one B row, in [COLUMNS] order. */
    fun snapshot(ctx: Context): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val air = airplane(ctx)
        val bt = global(ctx, Settings.Global.BLUETOOTH_ON)
        val outs = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) } catch (_: Exception) { emptyArray() }
        val btAudio = outs.any { it.type in BT_TYPES }
        val music = try { am.isMusicActive } catch (_: Exception) { false }
        val vol = try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max else -1
        } catch (_: Exception) { -1 }
        val wifi = global(ctx, "wifi_on").let { if (it > 0) 1 else it } // 1 on, 0 off, -1 unknown
        val net = try {
            val cm = ctx.getSystemService(ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            when {
                caps == null -> "none"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                else -> "other"
            }
        } catch (_: Exception) { "?" }
        var lvl = -1
        var dbm = ""
        if (!air && Build.VERSION.SDK_INT >= 28) try {
            val ss = ctx.getSystemService(TelephonyManager::class.java).signalStrength
            if (ss != null) {
                lvl = ss.level
                val d = ss.cellSignalStrengths.map { it.dbm }.filter { it in -150..-20 }
                if (d.isNotEmpty()) dbm = d.max().toString()
            }
        } catch (_: Exception) {}
        val bright = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS) } catch (_: Exception) { -1 }
        val auto = try { Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) } catch (_: Exception) { -1 }
        val cpu = Process.getElapsedCpuTime()
        val dCpu = if (lastCpuMs >= 0) cpu - lastCpuMs else -1
        lastCpuMs = cpu
        val o = if (btAudio) "bt" else if (outs.any { it.type in WIRED_TYPES }) "wired" else "speaker"
        return "${b(air)},$bt,${b(btAudio)},${b(music)},$o,$vol,$wifi,$net,$lvl,$dbm,$bright,$auto,$dCpu"
    }

    private fun b(v: Boolean) = if (v) 1 else 0

    /**
     * Logs state changes as raw-log events while recording: screen on/off, airplane on/off,
     * Bluetooth/wired audio connected or gone, music playing or stopped.
     */
    class Watcher(private val ctx: Context, private val log: (String, String) -> Unit) {
        private val am = ctx.getSystemService(AudioManager::class.java)
        private val main = Handler(Looper.getMainLooper())
        private var music = false
        private var out = ""

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> log("screen", "on")
                    Intent.ACTION_SCREEN_OFF -> log("screen", "off")
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> log("airplane", if (i.getBooleanExtra("state", airplane(c))) "on" else "off")
                }
            }
        }

        private val devices = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = checkOutput()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = checkOutput()
        }

        private val playback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) = checkMusic()
        }

        private fun checkOutput() {
            val o = output(am)
            if (o != out) { out = o; log("audio-out", o) }
        }

        private fun checkMusic() {
            val m = try { am.isMusicActive } catch (_: Exception) { false }
            if (m != music) { music = m; log("music", if (m) "on $out" else "off") }
        }

        fun start() {
            out = output(am)
            music = try { am.isMusicActive } catch (_: Exception) { false }
            log("env", "airplane ${if (airplane(ctx)) "on" else "off"} · audio-out $out · music ${if (music) "on" else "off"}")
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            }
            try {
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(receiver, f)
            } catch (_: Exception) {}
            try { am.registerAudioDeviceCallback(devices, main) } catch (_: Exception) {}
            try { am.registerAudioPlaybackCallback(playback, main) } catch (_: Exception) {}
        }

        fun stop() {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
            try { am.unregisterAudioDeviceCallback(devices) } catch (_: Exception) {}
            try { am.unregisterAudioPlaybackCallback(playback) } catch (_: Exception) {}
        }
    }
}
