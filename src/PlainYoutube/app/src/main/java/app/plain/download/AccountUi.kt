package app.plain.download

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract

/**
 * ⋮ → Account (2.0). The account is used only for members-only videos and the subscriptions sync; see [AuthRule].
 * Three ways in: (A) sign in inside the app (plain WebView; Google may refuse it), (B) Firefox for Android + the
 * "cookies.txt" add-on, (C) a PC browser + a cookies.txt exporter. B and C end with "Import cookies.txt…".
 */
class AccountUi(private val act: MainActivity) {
    private val main = Handler(Looper.getMainLooper())

    companion object {
        const val REQ_COOKIES = 30
        const val REQ_SIGNIN = 31
        const val ADDON = "https://addons.mozilla.org/en-US/firefox/addon/cookies-txt/"

        val HOW_PHONE = """
            Phone only (Firefox):
            1. Install Firefox for Android (Play Store / F-Droid).
            2. In Firefox: ⋮ → Extensions → find "cookies.txt" (by Lennon Hill) and add it. ($ADDON)
            3. Open a PRIVATE tab, go to youtube.com and sign in.
            4. ⋮ → Extensions → cookies.txt → export the cookies of the current site (youtube.com) → it saves a cookies.txt to Downloads.
            5. Close the private tab (that ends this sign-in session in Firefox, so YouTube doesn't rotate the cookies away).
            6. Here: ⋮ → Account → Import cookies.txt… and pick that file. Then delete the file.
        """.trimIndent()

        val HOW_PC = """
            With a PC:
            1. Open a private / incognito window and sign in to youtube.com.
            2. Export with a local-only extension, e.g. "Get cookies.txt LOCALLY" (Chrome) or "cookies.txt" (Firefox). Export youtube.com only.
            3. Close the private window.
            4. Copy the file to the phone (cable), then ⋮ → Account → Import cookies.txt…, and delete the file on both devices.
        """.trimIndent()

        const val WHAT = "The account is used ONLY for members-only videos (blue MEMBERS badge), a channel's Members list and " +
            "\"Sync subscriptions\". Search, feeds, channels and every other video stay anonymous. Only YouTube's sign-in " +
            "cookies are kept, encrypted with a key that never leaves this phone; they're sent only to youtube.com. " +
            "Sign out deletes them."
    }

    fun show() {
        Thread {
            val j = Account.jar() // decrypt off the main thread
            main.post { if (!act.isDestroyed) menu(j != null && j.signedIn) }
        }.start()
    }

    private fun menu(signed: Boolean) {
        val items = ArrayList<Pair<String, () -> Unit>>()
        val title = when {
            !signed -> "Account: not signed in"
            Account.expired -> "Account expired — import new cookies"
            else -> "Signed in" + (Account.name?.let { " as $it" } ?: "")
        }
        if (signed && !Account.expired) {
            items += "Sync subscriptions" to { syncSubs() }
            items += "Test the account" to { test() }
        }
        items += "Sign in to YouTube (on this phone)…" to { act.startActivityForResult(Intent(act, SignInActivity::class.java), REQ_SIGNIN) }
        items += "Import cookies.txt…" to { pickFile() }
        items += "How to get cookies.txt…" to { howTo() }
        if (signed) items += "Sign out (delete the account data)" to { signOut() }
        items += "What is the account used for?" to { act.message("Account", WHAT) }
        act.menu(title, items)
    }

    private fun howTo() = act.message("Get cookies.txt", HOW_PHONE + "\n\n" + HOW_PC +
        "\n\nThe cookies.txt is a key to your Google account: export it in a private window, import it, then delete it.")

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/octet-stream"))
        try { act.startActivityForResult(i, REQ_COOKIES) } catch (_: Exception) { act.toast("No file picker available") }
    }

    /** From MainActivity.onActivityResult. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            REQ_COOKIES -> if (resultCode == android.app.Activity.RESULT_OK) data?.data?.let { import(it) }
            REQ_SIGNIN -> when (resultCode) {
                android.app.Activity.RESULT_OK -> signedIn(data?.getStringExtra("name"))
                SignInActivity.RESULT_REFUSED -> refused(data?.getStringExtra("why").orEmpty())
            }
        }
    }

    private fun refused(why: String) {
        AlertDialog.Builder(act)
            .setTitle("Google refused the in-app sign-in")
            .setMessage(why.ifEmpty { "Google said this browser or app may not be secure." } +
                "\n\nGoogle blocks sign-in inside apps' embedded browsers, and this app doesn't disguise itself to get around " +
                "that. Use a real browser instead (nothing was saved):\n\n" + HOW_PHONE + "\n\n" + HOW_PC)
            .setPositiveButton("Import cookies.txt…") { _, _ -> pickFile() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun signedIn(name: String?) {
        act.message("Signed in", "Signed in" + (name?.let { " as $it" } ?: "") + ". The app kept only YouTube's sign-in cookies " +
            "(encrypted) and cleared everything else the sign-in page stored.\n\nMembers-only videos (blue MEMBERS badge) now play and download. " +
            "⋮ → Account → Sync subscriptions brings your subscriptions into Follows.")
    }

    private fun import(uri: Uri) {
        act.toast("Reading cookies.txt…")
        Thread {
            val r = try {
                val text = act.contentResolver.openInputStream(uri)!!.use { String(Net.readAll(it, 4 shl 20), Charsets.UTF_8) }
                Result.success(CookieFile.parse(text, System.currentTimeMillis() / 1000))
            } catch (e: Throwable) { Result.failure(e) }
            val imp = r.getOrNull()
            if (imp == null || !CookiePolicy.signedIn(imp.cookies)) {
                main.post {
                    if (act.isDestroyed) return@post
                    act.message("Import cookies.txt", if (imp == null) "Couldn't read that file." else
                        "No YouTube sign-in cookies in that file (it needs LOGIN_INFO and SAPISID on youtube.com). " +
                            "Export while signed in to youtube.com, in Netscape cookies.txt format.\n\nNothing was saved.")
                    if (imp != null) offerDelete(uri, imported = false)
                }
                return@Thread
            }
            val jar = CookieJar(imp.cookies)
            var name: String? = null
            var note = ""
            try {
                Account.save(jar, null)
                name = AuthHttp.accountName(act)
                Account.save(jar, name)
            } catch (e: AccountExpired) {
                Account.markExpired(); note = "\n\nYouTube says these cookies are already signed out (rotated). Export them again in a fresh private window and close it right after exporting."
            } catch (e: Exception) {
                note = "\n\nCouldn't check them now (${e.message?.take(80)}); they'll be checked on first use."
            }
            main.post {
                if (act.isDestroyed) return@post
                act.message("Account", "Imported ${jar.size} YouTube sign-in cookies" + (name?.let { " · signed in as $it" } ?: "") +
                    " (${imp.dropped} other lines dropped)." + note)
                offerDelete(uri, imported = true)
            }
        }.start()
    }

    /** The imported file can sign in to the Google account: ask to delete it, if the provider can. */
    private fun offerDelete(uri: Uri, imported: Boolean) {
        val canDelete = try {
            act.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c ->
                c.moveToFirst() && (c.getInt(0) and DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
            } == true
        } catch (_: Exception) { false }
        val msg = (if (imported) "The app has its own encrypted copy now. " else "") +
            "Delete the cookies.txt file: anyone who gets it can use your Google account."
        val b = AlertDialog.Builder(act).setTitle("Delete cookies.txt?").setMessage(
            msg + if (canDelete) "" else "\n\nThis file's app doesn't allow deleting from here: delete it in Files (Downloads)."
        )
        if (canDelete) {
            b.setPositiveButton("Delete it") { _, _ ->
                val ok = try { DocumentsContract.deleteDocument(act.contentResolver, uri) } catch (_: Exception) { false }
                act.toast(if (ok) "cookies.txt deleted" else "Couldn't delete it: delete it in Files")
            }.setNegativeButton("Keep", null)
        } else b.setPositiveButton("OK", null)
        b.show()
    }

    private fun test() {
        act.toast("Checking the account…")
        Thread {
            val r = try { Result.success(AuthHttp.accountName(act)) } catch (e: Throwable) { Result.failure(e) }
            r.getOrNull()?.let { n -> Account.jar()?.let { Account.save(it, n) } }
            main.post {
                if (act.isDestroyed) return@post
                r.fold({ act.message("Account", "Signed in as $it. The account works.") }, { e ->
                    act.message("Account", when (e) {
                        is AccountExpired -> "Account expired — import new cookies (or sign in again)."
                        is NoWifi -> e.message ?: "Not on Wi-Fi"
                        else -> "Couldn't check: ${e.message ?: e.javaClass.simpleName}"
                    })
                })
            }
        }.start()
    }

    private fun syncSubs() {
        val dlg = AlertDialog.Builder(act).setTitle("Sync subscriptions").setMessage("Reading your subscriptions…")
            .setNegativeButton("Cancel", null).create()
        val calls = Calls()
        dlg.setOnDismissListener { calls.cancelAll() }
        dlg.show()
        val g = calls.gen
        Thread {
            val r = try {
                Result.success(AuthHttp.subscriptions(act, calls, g) { n -> main.post { if (dlg.isShowing) dlg.setMessage("Reading your subscriptions… $n") } })
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (act.isDestroyed || g != calls.gen) return@post
                dlg.setOnDismissListener(null); dlg.dismiss()
                r.fold({ subs -> merge(subs) }, { e ->
                    act.message("Sync subscriptions", when (e) {
                        is AccountExpired -> "Account expired — import new cookies (or sign in again)."
                        is NoWifi -> e.message ?: "Not on Wi-Fi"
                        is Cancelled -> return@fold
                        else -> "Couldn't sync: ${e.message ?: e.javaClass.simpleName}"
                    })
                })
            }
        }.start()
    }

    private fun merge(subs: List<AccountParse.Sub>) {
        if (subs.isEmpty()) { act.message("Sync subscriptions", "YouTube listed no subscriptions for this account."); return }
        val followed = Store.follows()
        val plan = SyncPlan.of(subs, followed.map { it.id }.toSet())
        val now = System.currentTimeMillis()
        val (merged, _) = Follows.merge(followed, plan.add.map { Follow(it.id, it.name.ifEmpty { it.id }, now) })
        Store.setFollows(merged)
        act.search.followsChanged()
        val summary = plan.summary() + "."
        if (plan.gone.isEmpty()) { act.message("Sync subscriptions", summary); return }
        // followed here but not subscribed on YouTube: kept unless the user says otherwise
        val names = merged.filter { it.id in plan.gone }.map { it.title }
        AlertDialog.Builder(act).setTitle("Sync subscriptions")
            .setMessage("$summary\n\n${names.size} followed channels aren't in your YouTube subscriptions:\n" +
                names.take(12).joinToString("\n") { "• $it" } + (if (names.size > 12) "\n… and ${names.size - 12} more" else "") +
                "\n\nKeep following them? (Unfollowing them only changes this app: they aren't subscribed on YouTube anyway.)")
            .setPositiveButton("Keep") { _, _ -> }
            .setNegativeButton("Unfollow ${names.size}") { _, _ ->
                val gone = plan.gone.toSet()
                Store.setFollows(Store.follows().filter { it.id !in gone })
                act.search.followsChanged()
                act.toast("Unfollowed ${gone.size}")
            }
            .show()
    }

    private fun signOut() {
        AlertDialog.Builder(act).setTitle("Sign out?")
            .setMessage("Deletes the stored cookies and their encryption key from this phone. Members-only videos stop playing; " +
                "Follows stay. (To end the session on Google's side too: Google Account → Security → Your devices.)")
            .setPositiveButton("Sign out") { _, _ -> Account.signOut(); act.toast("Signed out: account data deleted") }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
