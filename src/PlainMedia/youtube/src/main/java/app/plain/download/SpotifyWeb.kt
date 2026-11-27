package app.plain.download

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import app.plain.lang.tr

/**
 * Reads a PUBLIC open.spotify.com page (a profile, its playlists, a playlist) the way a browser shows it — no account,
 * no token of ours, nothing sent but the page's own requests. Spotify serves phones a cut-down page (20 playlists,
 * 30 songs), so this one reader reports a desktop Chrome user agent (the WebView's own Chrome version; the user
 * chose this on 2026-11-27, for these public pages only) and is ~1000 CSS px wide. The page must be on screen to
 * render its lists, so it is shown in a small "Reading Spotify…" card at the bottom of the Youtube tab while it
 * reads. A script of ours scrolls it and collects the links into window.__plainOut; cookies and storage are wiped
 * afterwards. Only on the user's Find / Sync; never in the background.
 */
object SpotifyWeb {
    private val main = Handler(Looper.getMainLooper())

    /** "…Chrome/131.0.6778.135…" of this WebView, as a desktop Chrome would send it. */
    private fun desktopUa(act: MainActivity): String {
        val v = try { Regex("Chrome/([0-9.]+)").find(WebSettings.getDefaultUserAgent(act))?.groupValues?.get(1) } catch (_: Exception) { null } ?: "130.0.0.0"
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$v Safari/537.36"
    }

    /**
     * Background thread: load [url], run [script] once the page has loaded, wait for its result (a JSON string).
     * Throws [Cancelled] on [cancelled], IOException on a timeout / no window.
     */
    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    fun read(act: MainActivity, url: String, script: String, cancelled: () -> Boolean, timeoutMs: Long = 120_000): String {
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        var wv: WebView? = null
        var card: View? = null
        var addedKeepOn = false
        val poll = object : Runnable {
            override fun run() {
                val w = wv ?: return
                if (cancelled()) { latch.countDown(); return }
                w.evaluateJavascript("window.__plainOut||''") { r ->
                    val s = try { JSONArray("[$r]").optString(0) } catch (_: Exception) { "" }
                    if (s.isNotEmpty() && s != "null") { out.set(s); latch.countDown() } else main.postDelayed(this, 600)
                }
            }
        }
        main.post {
            try {
                if (act.isDestroyed || act.isFinishing) { latch.countDown(); return@post }
                val root = act.findViewById<FrameLayout>(android.R.id.content)
                val box = LinearLayout(act).apply {
                    orientation = LinearLayout.VERTICAL
                    background = act.outline(act.dpf(12))
                    setPadding(act.dp(10), act.dp(8), act.dp(10), act.dp(10))
                    clipChildren = true
                    isClickable = true // touches stay in the card
                }
                box.addView(act.text(tr("Reading Spotify… (keep this screen open)", "Spotify okunuyor… (bu ekranı açık tutun)"), 12f, C.DIM),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                val clip = FrameLayout(act).apply { clipChildren = true }
                val w = WebView(act)
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.userAgentString = desktopUa(act)
                w.setOnTouchListener { _, _ -> true } // a view of the page, not something to browse
                w.webViewClient = object : WebViewClient() {
                    private var started = false
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val h = request.url.host.orEmpty()
                        return request.url.scheme != "https" || !(h == "open.spotify.com" || h.endsWith(".spotify.com"))
                    }
                    override fun onPageFinished(view: WebView, u: String?) {
                        if (started) return
                        started = true
                        view.evaluateJavascript(script, null)
                        main.postDelayed(poll, 1500)
                    }
                }
                // ~1000 CSS px wide: Spotify's desktop layout (the card shows its left part)
                clip.addView(w, FrameLayout.LayoutParams(act.dp(1000), act.dp(220)))
                box.addView(clip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, act.dp(220)).apply { topMargin = act.dp(6) })
                root.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                    setMargins(act.dp(12), 0, act.dp(12), act.dp(84))
                })
                wv = w; card = box
                // the page stops when the screen sleeps: keep it on while reading (only if the player hasn't already)
                val flags = act.window.attributes.flags
                if (flags and android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                    act.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); addedKeepOn = true
                }
                w.loadUrl(url)
            } catch (_: Throwable) { latch.countDown() }
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!latch.await(500, TimeUnit.MILLISECONDS)) {
            if (cancelled() || System.currentTimeMillis() > deadline) break
        }
        val gone = CountDownLatch(1)
        main.post {
            main.removeCallbacks(poll)
            try {
                wv?.let { it.stopLoading(); (card?.parent as? ViewGroup)?.removeView(card); it.destroy() }
                if (addedKeepOn && !act.isDestroyed) act.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                android.webkit.CookieManager.getInstance().apply { removeAllCookies(null); flush() }
                android.webkit.WebStorage.getInstance().deleteAllData()
            } catch (_: Throwable) {}
            gone.countDown()
        }
        gone.await(5, TimeUnit.SECONDS)
        if (cancelled()) throw Cancelled()
        return out.get() ?: throw IOException(
            if (!act.visibleNow) tr("Spotify reading stopped: keep Plain Media open on this screen while it reads", "Spotify okuması durdu: okurken Plain Media'yı bu ekranda açık tutun")
            else tr("Spotify's page didn't load (check the connection)", "Spotify sayfası yüklenmedi (bağlantıyı kontrol edin)"))
    }

    private const val WAIT_H1 = """
  const wait = ms => new Promise(r => setTimeout(r, ms));
  const out = o => { window.__plainOut = JSON.stringify(o); };
  let h = null;
  for (let i = 0; i < 60; i++) { h = document.querySelector('main h1'); if (h && h.textContent.trim()) break; h = null; await wait(400); }
  if (!h) { out({found: false}); return; }
"""

    /** A profile page or its /playlists page: {found, name, showAll, lists:[{id,name}]}. */
    const val PROFILE_JS = """(async () => { try {""" + WAIT_H1 + """
  await wait(800);
  const lists = new Map(); let lastA = null;
  const grab = () => { for (const a of document.querySelectorAll('main a[href*="/playlist/"]')) {
    if (a.closest('nav, footer')) continue;
    lastA = a;
    const id = (a.getAttribute('href').split('/playlist/')[1] || '').split(/[?#\/]/)[0]; if (!id) continue;
    const n = (a.getAttribute('title') || a.textContent || '').trim();
    if (!lists.has(id) || (!lists.get(id) && n)) lists.set(id, n); } };
  let last = -1, idle = 0;
  while (idle < 12) { grab(); if (lists.size === last) idle++; else { idle = 0; last = lists.size; }
    if (lastA) lastA.scrollIntoView({block: 'start'}); await wait(300); }
  grab();
  const showAll = [...document.querySelectorAll('main a')].some(a => /\/user\/[^\/]+\/playlists$/.test(a.getAttribute('href') || ''));
  out({found: true, name: h.textContent.trim(), showAll, lists: [...lists].map(([id, name]) => ({id, name}))});
} catch (e) { window.__plainOut = JSON.stringify({found: false, error: String(e)}); } })();"""

    /** A playlist page: {found, name, owner, total, tracks:[{id,t,a,d}]} (rows are only in the page near the view: step row by row). */
    const val PLAYLIST_JS = """(async () => { try {""" + WAIT_H1 + """
  for (let i = 0; i < 25 && !document.querySelector('[data-testid="tracklist-row"]'); i++) await wait(400);
  const head = (document.querySelector('main') || document.body).innerText;
  const m = head.match(/([0-9][0-9.,]*)\s+(songs?|şarkı)/i);
  const total = m ? parseInt(m[1].replace(/[.,]/g, '')) : -1;
  const owner = ((document.querySelector('main a[href^="/user/"]') || {}).textContent || '').trim();
  const seen = new Map(); let lastRow = null;
  const grab = () => { for (const r of document.querySelectorAll('[data-testid="tracklist-row"]')) {
    if (r.closest('nav, footer')) continue;
    lastRow = r;
    const t = r.querySelector('a[href*="/track/"]'); if (!t) continue;
    const id = (t.getAttribute('href').split('/track/')[1] || '').split(/[?#\/]/)[0]; if (!id || seen.has(id)) continue;
    const a = [...r.querySelectorAll('a[href*="/artist/"]')].map(x => x.textContent.trim()).filter(x => x);
    const d = (r.innerText.match(/\b\d{1,2}:\d{2}(:\d{2})?\b/g) || []).pop() || '';
    seen.set(id, {id, t: t.textContent.trim(), a, d}); } };
  let last = -1, idle = 0;
  while (!(total > 0 && seen.size >= total) && idle < (total > 0 ? 40 : 12)) {
    grab(); if (seen.size === last) idle++; else { idle = 0; last = seen.size; }
    if (lastRow) lastRow.scrollIntoView({block: 'start'}); await wait(250); }
  grab();
  out({found: true, name: h.textContent.trim(), owner, total, tracks: [...seen.values()]});
} catch (e) { window.__plainOut = JSON.stringify({found: false, error: String(e)}); } })();"""
}
