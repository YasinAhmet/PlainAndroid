package app.plain.download

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout

/**
 * Path A: sign in to YouTube inside the app.
 *
 * HARD RULE: this is Android's WebView exactly as it comes. The user agent is NOT changed, X-Requested-With is NOT
 * removed, no script hides or disguises the WebView. The only settings are the two a sign-in page can't work without
 * (JavaScript, DOM storage). Google may answer "This browser or app may not be secure" / "Couldn't sign you in": that
 * is a security control and we stop there and point to the browser routes (Firefox add-on, PC).
 *
 * On success (back on youtube.com with LOGIN_INFO + SAPISID): CookieManager's youtube.com cookies are read, only the
 * kept sign-in cookies are encrypted into the account store, then ALL WebView cookies, storage, cache, history and
 * form data are wiped. The only script run here is a read-only check of the page text for the refusal message.
 */
class SignInActivity : Activity() {
    companion object {
        const val RESULT_REFUSED = 2
        private const val START = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&hl=en&continue=" +
            "https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"
        private val REFUSAL_TEXT = listOf("browser or app may not be secure", "couldn't sign you in", "couldn’t sign you in", "disallowed_useragent")
        private val REFUSAL_URL = listOf("/signin/rejected", "deniedsigninrejected", "disallowed_useragent", "/v3/signin/rejected", "signin/v2/rejected")

        fun refusedUrl(url: String) = REFUSAL_URL.any { url.contains(it, ignoreCase = true) }
        fun refusedText(text: String) = text.lowercase().let { t -> REFUSAL_TEXT.any { it in t } }
    }

    private var web: WebView? = null
    private lateinit var statusV: android.widget.TextView
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this); Account.init(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(C.BG) }
        root.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom); ins
        }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(12), dp(4)) }
        bar.addView(icon(R.drawable.ic_close, desc = "Cancel") { cancel() })
        statusV = text("Sign in to YouTube · only YouTube's sign-in cookies are kept", 13f, C.DIM, lines = 2)
        bar.addView(statusV, lpL(0, WRAP, 1f))
        root.addView(bar, lpL(MATCH, WRAP))
        root.addView(View(this).apply { setBackgroundColor(C.LINE) }, lpL(MATCH, dp(1)))

        // start clean: nothing from an earlier attempt
        CookieManager.getInstance().removeAllCookies(null)
        WebStorage.getInstance().deleteAllData()

        val w = WebView(this)
        web = w
        w.settings.javaScriptEnabled = true // Google's sign-in page needs it
        w.settings.domStorageEnabled = true // and this
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                // stay on https Google / YouTube pages; never hand intents or other schemes to other apps
                return u.scheme != "https"
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (refusedUrl(url)) refuse("Google's page: ${Uri.parse(url).path}")
            }

            override fun onPageFinished(view: WebView, url: String) = check(view, url)
        }
        root.addView(w, lpL(MATCH, 0, 1f))
        setContentView(root)
        w.loadUrl(START)
    }

    private fun check(view: WebView, url: String) {
        if (finishing) return
        if (refusedUrl(url)) { refuse("Google's page: ${Uri.parse(url).path}"); return }
        val host = Uri.parse(url).host.orEmpty()
        if (host == "youtube.com" || host.endsWith(".youtube.com")) {
            val cm = CookieManager.getInstance()
            val cookies = CookieFile.fromHeader(cm.getCookie("https://www.youtube.com"))
            if (CookiePolicy.signedIn(cookies)) { success(cookies); return }
            if (cookies.any { it.name.endsWith("SAPISID") } && cookies.none { it.name == "LOGIN_INFO" }) {
                // signed in, but the WebView didn't hand over the HttpOnly cookies
                refuse("Signed in, but this phone's WebView didn't give the app YouTube's HttpOnly sign-in cookies.")
            }
            return
        }
        if (host == "accounts.google.com") {
            // read-only: look for Google's refusal message in the page text (changes nothing on the page)
            view.evaluateJavascript("(document.body && document.body.innerText || '').slice(0, 4000)") { t ->
                if (!finishing && t != null && refusedText(t)) refuse("Google said: This browser or app may not be secure / Couldn't sign you in.")
            }
        }
    }

    private fun success(cookies: List<Cookie>) {
        finishing = true
        statusV.text = "Signed in · saving and clearing the sign-in page's data…"
        wipe()
        Thread {
            val jar = CookieJar(cookies)
            var name: String? = null
            try {
                Account.save(jar, null)
                name = try { AuthHttp.accountName(this) } catch (_: Exception) { null }
                Account.save(jar, name)
            } catch (_: Exception) {}
            runOnUiThread {
                setResult(RESULT_OK, Intent().apply { name?.let { putExtra("name", it) } })
                finish()
            }
        }.start()
    }

    private fun refuse(why: String) {
        if (finishing) return
        finishing = true
        wipe()
        setResult(RESULT_REFUSED, Intent().putExtra("why", why))
        finish()
    }

    private fun cancel() {
        finishing = true
        wipe()
        setResult(RESULT_CANCELED)
        finish()
    }

    /** Everything the sign-in pages stored goes: cookies (all sites), DOM storage, cache, history, form data. */
    private fun wipe() {
        web?.let { w ->
            w.stopLoading()
            w.clearCache(true)
            w.clearHistory()
            w.clearFormData()
        }
        CookieManager.getInstance().apply { removeAllCookies(null); flush() }
        WebStorage.getInstance().deleteAllData()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val w = web
        if (w != null && w.canGoBack() && !finishing) w.goBack() else cancel()
    }

    override fun onDestroy() {
        if (!finishing) wipe()
        web?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() }
        web = null
        super.onDestroy()
    }
}
