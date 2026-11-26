package app.plain.download

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.widget.CheckBox
import android.widget.LinearLayout

/**
 * Unfollow (2.1, bulk + Undo in 2.2). Signed out / expired: no checkbox, local unfollow only. Signed in: the
 * confirmation has "Also unsubscribe on YouTube" (remembered, default on). The local unfollow happens at once, with
 * an 8 s Undo bar that restores the follows (never re-subscribes on YouTube). The unsubscribe requests then run on a
 * plain thread of this action, one after another with a short pause; never without the confirmation, never from a
 * service.
 */
object UnfollowUi {
    private val main = Handler(Looper.getMainLooper())
    private const val GAP_MS = 800L

    /** [done] runs after the local unfollow and again after an Undo. */
    fun unfollow(act: Activity, channels: List<Pair<String, String>>, done: () -> Unit) {
        if (channels.isEmpty()) return
        val signed = Account.usable
        if (!signed && channels.size == 1) { apply(act, channels, false, done); return } // local only, Undo bar instead of a dialog
        val box = CheckBox(act).apply { text = "Also unsubscribe on YouTube"; isChecked = Store.unsubDefault; setTextColor(C.TEXT) }
        val members = channels.filter { Store.isMemberChannel(it.first) }
        val msg = UnfollowText.confirm(channels.map { it.second }) +
            (if (!signed || members.isEmpty()) "" else "\n\nYou're a member of " + members.joinToString { it.second } + ". Unsubscribing does NOT cancel a paid " +
                "membership: memberships are managed on YouTube (youtube.com/paid_memberships).")
        val b = AlertDialog.Builder(act).setTitle("Unfollow").setMessage(msg)
        if (signed) b.setView(LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(act.dp(20), act.dp(8), act.dp(20), 0); addView(box) })
        b.setPositiveButton(if (channels.size == 1) "Unfollow" else "Unfollow ${channels.size}") { _, _ ->
            if (signed) Store.unsubDefault = box.isChecked
            apply(act, channels, signed && box.isChecked, done)
        }.setNegativeButton("Cancel", null).show()
    }

    private fun apply(act: Activity, channels: List<Pair<String, String>>, youtube: Boolean, done: () -> Unit) {
        run {
            val ids = channels.map { it.first }.toSet()
            val removed = Store.follows().filter { it.id in ids }
            for (id in ids) Store.unfollow(id)
            done()
            val text = UnfollowText.undoBar(channels.size, youtube)
            if (act is MainActivity) act.showUndo(text) {
                Store.setFollows(Follows.merge(Store.follows(), removed).first)
                done()
                act.toast("Follows restored" + if (youtube) " (still unsubscribed on YouTube)" else "")
            } else act.toast(text)
            if (youtube) unsubscribe(act, channels)
        }
    }

    /** Sequential requests; result as a toast, failures with Retry (a toast can't hold a button, so a small dialog). */
    private fun unsubscribe(act: Activity, channels: List<Pair<String, String>>) {
        val app = act.applicationContext
        Thread {
            val failed = ArrayList<Pair<Pair<String, String>, String>>()
            for ((i, c) in channels.withIndex()) {
                if (i > 0) Thread.sleep(GAP_MS)
                val why = try { AuthHttp.unsubscribe(app, c.first) } catch (e: AccountExpired) { "account expired — import new cookies" }
                    catch (e: NoWifi) { "not on Wi-Fi" } catch (e: Exception) { e.message ?: e.javaClass.simpleName }
                if (why != null) failed += c to why
                if (why == "account expired — import new cookies" || why == "not on Wi-Fi") {
                    for (rest in channels.drop(i + 1)) failed += rest to why
                    break
                }
            }
            main.post {
                val ok = channels.size - failed.size
                if (failed.isEmpty()) { app.toast(if (channels.size == 1) "Unsubscribed on YouTube" else "Unsubscribed $ok of ${channels.size}"); return@post }
                val text = if (channels.size == 1) "Couldn't unsubscribe on YouTube: ${failed[0].second}"
                    else "Unsubscribed $ok of ${channels.size}. Not done:\n" + failed.take(10).joinToString("\n") { "• ${it.first.second}: ${it.second}" } +
                        (if (failed.size > 10) "\n+${failed.size - 10} more" else "")
                app.toast(text.lineSequence().first())
                if (act.isDestroyed || act.isFinishing) return@post
                AlertDialog.Builder(act).setTitle("Unsubscribe on YouTube").setMessage(text)
                    .setPositiveButton("Retry") { _, _ -> unsubscribe(act, failed.map { it.first }) }
                    .setNegativeButton("Close", null)
                    .show()
            }
        }.start()
    }
}
