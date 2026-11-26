package app.plain.media

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.TextView
import app.plain.hub.Hub
import app.plain.lang.PlainLang
import app.plain.lang.tr

/** The Spotify tab: empty for now (Plain Spotify is planned). Nothing runs here. */
class SpotifyActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        PlainLang.init(this)
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
            val pad = (32 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@SpotifyActivity).apply {
                text = "Plain Spotify"; textSize = 22f; setTextColor(0xFFE4E4E4.toInt()); gravity = Gravity.CENTER
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SpotifyActivity).apply {
                text = tr("Coming later. This tab is empty for now.", "Daha sonra gelecek. Bu sekme şimdilik boş.")
                textSize = 15f; setTextColor(0xFFA6A6A6.toInt()); gravity = Gravity.CENTER
                setPadding(0, (8 * dp).toInt(), 0, 0)
            })
        }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            val b = if (Build.VERSION.SDK_INT >= 30) ins.getInsets(WindowInsets.Type.systemBars()).bottom else ins.systemWindowInsetBottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, b + (32 * dp).toInt()); ins
        }
        setContentView(Hub.wrap(this, "spotify", root))
    }
}
