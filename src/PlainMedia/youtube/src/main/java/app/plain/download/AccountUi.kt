package app.plain.download

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import app.plain.lang.tr

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

        val HOW_PHONE get() = tr("""
            Phone only (Firefox):
            1. Install Firefox for Android (Play Store / F-Droid).
            2. In Firefox: ⋮ → Extensions → find "cookies.txt" (by Lennon Hill) and add it. ($ADDON)
            3. Open a PRIVATE tab, go to youtube.com and sign in.
            4. ⋮ → Extensions → cookies.txt → export the cookies of the current site (youtube.com) → it saves a cookies.txt to Downloads.
            5. Close the private tab (that ends this sign-in session in Firefox, so YouTube doesn't rotate the cookies away).
            6. Here: ⋮ → Account → Import cookies.txt… and pick that file. Then delete the file.
        """.trimIndent(), """
            Yalnızca telefon (Firefox):
            1. Android için Firefox'u yükleyin (Play Store / F-Droid).
            2. Firefox'ta: ⋮ → Uzantılar → "cookies.txt" (Lennon Hill) uzantısını bulup ekleyin. ($ADDON)
            3. GİZLİ bir sekme açın, youtube.com'a gidip oturum açın.
            4. ⋮ → Uzantılar → cookies.txt → geçerli sitenin (youtube.com) çerezlerini dışa aktarın → İndirilenler'e bir cookies.txt kaydedilir.
            5. Gizli sekmeyi kapatın (bu, Firefox'taki oturumu bitirir; böylece YouTube çerezleri yenileyip geçersiz kılmaz).
            6. Burada: ⋮ → Hesap → cookies.txt içe aktar… ile o dosyayı seçin. Sonra dosyayı silin.
        """.trimIndent())

        val HOW_PC get() = tr("""
            With a PC:
            1. Open a private / incognito window and sign in to youtube.com.
            2. Export with a local-only extension, e.g. "Get cookies.txt LOCALLY" (Chrome) or "cookies.txt" (Firefox). Export youtube.com only.
            3. Close the private window.
            4. Copy the file to the phone (cable), then ⋮ → Account → Import cookies.txt…, and delete the file on both devices.
        """.trimIndent(), """
            Bilgisayarla:
            1. Gizli bir pencere açıp youtube.com'da oturum açın.
            2. Yalnızca yerel çalışan bir uzantıyla dışa aktarın, ör. "Get cookies.txt LOCALLY" (Chrome) ya da "cookies.txt" (Firefox). Yalnızca youtube.com'u dışa aktarın.
            3. Gizli pencereyi kapatın.
            4. Dosyayı telefona kopyalayın (kabloyla), sonra ⋮ → Hesap → cookies.txt içe aktar… yapın ve dosyayı iki cihazdan da silin.
        """.trimIndent())

        val WHAT get() = tr("The account is used ONLY for members-only videos (blue MEMBERS badge), a channel's Members list and " +
            "\"Sync subscriptions\". Search, feeds, channels and every other video stay anonymous. Only YouTube's sign-in " +
            "cookies are kept, encrypted with a key that never leaves this phone; they're sent only to youtube.com. " +
            "Sign out deletes them.",
            "Hesap YALNIZCA üyelere özel videolar (mavi ÜYELER rozeti), bir kanalın Üyeler listesi ve " +
            "\"Abonelikleri eşitle\" için kullanılır. Arama, akışlar, kanallar ve diğer tüm videolar anonim kalır. Yalnızca YouTube'un " +
            "oturum çerezleri saklanır; bu telefondan hiç çıkmayan bir anahtarla şifrelenir ve yalnızca youtube.com'a gönderilir. " +
            "Oturumu kapat bunları siler.")
    }

    fun show() {
        Thread {
            val j = Account.jar() // decrypt off the main thread
            main.post { if (!act.isDestroyed) menu(j != null && j.signedIn) }
        }.start()
    }

    /**
     * Feed Refresh with no subscriptions / follows at all: a sheet that leads to the sign-in (signed in already: to
     * Sync subscriptions), with the Takeout import and a channel search as other ways.
     */
    fun noSubscriptions(onImport: () -> Unit, onSearch: () -> Unit) {
        Thread {
            val j = try { Account.jar() } catch (_: Exception) { null } // decrypt off the main thread
            main.post { if (!act.isFinishing && !act.isDestroyed) noSubsSheet(j != null && j.signedIn && !Account.expired, onImport, onSearch) }
        }.start()
    }

    private fun noSubsSheet(signed: Boolean, onImport: () -> Unit, onSearch: () -> Unit) {
        val sheet = app.plain.ui.Sheet(act, C.ACCENT)
        if (signed) {
            sheet.header(tr("You have no subscriptions here yet", "Burada henüz aboneliğiniz yok"),
                tr("You're signed in: bring your YouTube subscriptions in.", "Oturumunuz açık: YouTube aboneliklerinizi getirin."))
                .item(app.plain.ui.Ic.SYNC, tr("Sync subscriptions", "Abonelikleri eşitle"), more = true) { syncSubs() }
        } else {
            sheet.header(tr("You have no subscriptions. Log in to sync them.", "Aboneliğiniz yok. Eşitlemek için giriş yapın."),
                tr("After signing in: ⋮ → YouTube account → Sync subscriptions.", "Oturum açtıktan sonra: ⋮ → YouTube hesabı → Abonelikleri eşitle."))
                .item(app.plain.ui.Ic.ACCOUNT, tr("Sign in to YouTube", "YouTube'da oturum aç"), sub = tr("On this phone", "Bu telefonda"), more = true) {
                    act.startActivityForResult(Intent(act, SignInActivity::class.java), REQ_SIGNIN)
                }
                .item(app.plain.ui.Ic.UPLOAD, tr("Import cookies.txt", "cookies.txt içe aktar"), more = true) { pickFile() }
        }
        sheet.section(tr("Other ways", "Diğer yollar"))
            .item(app.plain.ui.Ic.FOLDER, tr("Import from Google Takeout", "Google Takeout'tan içe aktar"), sub = tr("No sign-in needed", "Oturum açmak gerekmez"), more = true) { onImport() }
            .item(app.plain.ui.Ic.SEARCH, tr("Search channels to follow", "Takip edilecek kanalları ara"), more = true) { onSearch() }
            .item(app.plain.ui.Ic.INFO, tr("What is the account used for?", "Hesap ne için kullanılır?")) { act.message(tr("Account", "Hesap"), WHAT) }
            .show()
    }

    private fun menu(signed: Boolean) {
        val title = when {
            !signed -> tr("Not signed in", "Oturum açılmadı")
            Account.expired -> tr("Account expired", "Hesabın süresi doldu")
            else -> tr("Signed in", "Oturum açık") + (Account.name?.let { tr(" as $it", ": $it") } ?: "")
        }
        val sheet = app.plain.ui.Sheet(act, C.ACCENT).header(tr("YouTube account", "YouTube hesabı"), title +
            if (Account.expired) tr(" — import new cookies", " — yeni çerezleri içe aktarın") else "")
        if (signed && !Account.expired) sheet
            .item(app.plain.ui.Ic.SYNC, tr("Sync subscriptions", "Abonelikleri eşitle")) { syncSubs() }
            .item(app.plain.ui.Ic.CHECK, tr("Test the account", "Hesabı test et")) { test() }
        sheet.section(tr("Sign in", "Oturum aç"))
            .item(app.plain.ui.Ic.ACCOUNT, tr("Sign in to YouTube", "YouTube'da oturum aç"), sub = tr("On this phone", "Bu telefonda"), more = true) { act.startActivityForResult(Intent(act, SignInActivity::class.java), REQ_SIGNIN) }
            .item(app.plain.ui.Ic.UPLOAD, tr("Import cookies.txt", "cookies.txt içe aktar"), more = true) { pickFile() }
            .item(app.plain.ui.Ic.HELP, tr("How to get cookies.txt", "cookies.txt nasıl alınır")) { howTo() }
            .item(app.plain.ui.Ic.INFO, tr("What is the account used for?", "Hesap ne için kullanılır?")) { act.message(tr("Account", "Hesap"), WHAT) }
        if (signed) sheet.item(app.plain.ui.Ic.LOGOUT, tr("Sign out", "Oturumu kapat"), sub = tr("Deletes the account data from this phone", "Hesap verilerini bu telefondan siler"), danger = true) { signOut() }
        sheet.show()
    }

    private fun howTo() = act.message(tr("Get cookies.txt", "cookies.txt edinme"), HOW_PHONE + "\n\n" + HOW_PC +
        tr("\n\nThe cookies.txt is a key to your Google account: export it in a private window, import it, then delete it.",
            "\n\ncookies.txt, Google hesabınızın anahtarıdır: gizli bir pencerede dışa aktarın, içe aktarın, sonra silin."))

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/*", "application/octet-stream"))
        try { act.startActivityForResult(i, REQ_COOKIES) } catch (_: Exception) { act.toast(tr("No file picker available", "Dosya seçici yok")) }
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
            .setTitle(tr("Google refused the in-app sign-in", "Google uygulama içi oturum açmayı reddetti"))
            .setMessage(why.ifEmpty { tr("Google said this browser or app may not be secure.", "Google bu tarayıcının ya da uygulamanın güvenli olmayabileceğini bildirdi.") } +
                tr("\n\nGoogle blocks sign-in inside apps' embedded browsers, and this app doesn't disguise itself to get around " +
                "that. Use a real browser instead (nothing was saved):\n\n",
                    "\n\nGoogle, uygulamaların gömülü tarayıcılarında oturum açmayı engeller ve bu uygulama bunu aşmak için kendini gizlemez. " +
                    "Bunun yerine gerçek bir tarayıcı kullanın (hiçbir şey kaydedilmedi):\n\n") + HOW_PHONE + "\n\n" + HOW_PC)
            .setPositiveButton(tr("Import cookies.txt…", "cookies.txt içe aktar…")) { _, _ -> pickFile() }
            .setNegativeButton(tr("Close", "Kapat"), null)
            .show()
    }

    private fun signedIn(name: String?) {
        act.message(tr("Signed in", "Oturum açıldı"), tr("Signed in", "Oturum açıldı") + (name?.let { tr(" as $it", ": $it") } ?: "") +
            tr(". The app kept only YouTube's sign-in cookies " +
            "(encrypted) and cleared everything else the sign-in page stored.\n\nMembers-only videos (blue MEMBERS badge) now play and download. " +
            "⋮ → Account → Sync subscriptions brings your subscriptions into Follows.",
                ". Uygulama yalnızca YouTube'un oturum çerezlerini (şifreli) sakladı ve oturum açma sayfasının kaydettiği diğer her şeyi temizledi." +
                "\n\nÜyelere özel videolar (mavi ÜYELER rozeti) artık oynatılır ve indirilir. " +
                "⋮ → Hesap → Abonelikleri eşitle, aboneliklerinizi takiplere ekler."))
    }

    private fun import(uri: Uri) {
        act.toast(tr("Reading cookies.txt…", "cookies.txt okunuyor…"))
        Thread {
            val r = try {
                val text = act.contentResolver.openInputStream(uri)!!.use { String(Net.readAll(it, 4 shl 20), Charsets.UTF_8) }
                Result.success(CookieFile.parse(text, System.currentTimeMillis() / 1000))
            } catch (e: Throwable) { Result.failure(e) }
            val imp = r.getOrNull()
            if (imp == null || !CookiePolicy.signedIn(imp.cookies)) {
                main.post {
                    if (act.isDestroyed) return@post
                    act.message(tr("Import cookies.txt", "cookies.txt içe aktar"), if (imp == null) tr("Couldn't read that file.", "Bu dosya okunamadı.") else
                        tr("No YouTube sign-in cookies in that file (it needs LOGIN_INFO and SAPISID on youtube.com). " +
                            "Export while signed in to youtube.com, in Netscape cookies.txt format.\n\nNothing was saved.",
                            "Bu dosyada YouTube oturum çerezi yok (youtube.com için LOGIN_INFO ve SAPISID gerekir). " +
                            "youtube.com'da oturum açıkken Netscape cookies.txt biçiminde dışa aktarın.\n\nHiçbir şey kaydedilmedi."))
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
                Account.markExpired(); note = tr("\n\nYouTube says these cookies are already signed out (rotated). Export them again in a fresh private window and close it right after exporting.", "\n\nYouTube bu çerezlerin oturumunun zaten kapandığını bildiriyor (yenilenmiş). Yeni bir gizli pencerede yeniden dışa aktarın ve hemen ardından pencereyi kapatın.")
            } catch (e: Exception) {
                note = tr("\n\nCouldn't check them now (${e.message?.take(80)}); they'll be checked on first use.", "\n\nŞu anda denetlenemedi (${e.message?.take(80)}); ilk kullanımda denetlenecek.")
            }
            main.post {
                if (act.isDestroyed) return@post
                act.message(tr("Account", "Hesap"), tr("Imported ${jar.size} YouTube sign-in cookies", "${jar.size} YouTube oturum çerezi içe aktarıldı") +
                    (name?.let { tr(" · signed in as $it", " · oturum: $it") } ?: "") +
                    tr(" (${imp.dropped} other lines dropped).", " (diğer ${imp.dropped} satır atıldı).") + note)
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
        val msg = (if (imported) tr("The app has its own encrypted copy now. ", "Uygulamanın artık kendi şifreli kopyası var. ") else "") +
            tr("Delete the cookies.txt file: anyone who gets it can use your Google account.", "cookies.txt dosyasını silin: onu ele geçiren herkes Google hesabınızı kullanabilir.")
        val b = AlertDialog.Builder(act).setTitle(tr("Delete cookies.txt?", "cookies.txt silinsin mi?")).setMessage(
            msg + if (canDelete) "" else tr("\n\nThis file's app doesn't allow deleting from here: delete it in Files (Downloads).", "\n\nBu dosyanın uygulaması buradan silmeye izin vermiyor: Dosyalar'da (İndirilenler) silin.")
        )
        if (canDelete) {
            b.setPositiveButton(tr("Delete it", "Sil")) { _, _ ->
                val ok = try { DocumentsContract.deleteDocument(act.contentResolver, uri) } catch (_: Exception) { false }
                act.toast(if (ok) tr("cookies.txt deleted", "cookies.txt silindi") else tr("Couldn't delete it: delete it in Files", "Silinemedi: Dosyalar'da silin"))
            }.setNegativeButton(tr("Keep", "Sakla"), null)
        } else b.setPositiveButton(tr("OK", "Tamam"), null)
        b.show()
    }

    private fun test() {
        act.toast(tr("Checking the account…", "Hesap denetleniyor…"))
        Thread {
            val r = try { Result.success(AuthHttp.accountName(act)) } catch (e: Throwable) { Result.failure(e) }
            r.getOrNull()?.let { n -> Account.jar()?.let { Account.save(it, n) } }
            main.post {
                if (act.isDestroyed) return@post
                r.fold({ act.message(tr("Account", "Hesap"), tr("Signed in as $it. The account works.", "Oturum: $it. Hesap çalışıyor.")) }, { e ->
                    act.message(tr("Account", "Hesap"), when (e) {
                        is AccountExpired -> tr("Account expired — import new cookies (or sign in again).", "Hesabın süresi doldu — yeni çerezleri içe aktarın (ya da yeniden oturum açın).")
                        is NoWifi -> e.message ?: tr("Not on Wi-Fi", "Wi-Fi bağlı değil")
                        else -> tr("Couldn't check: ${e.message ?: e.javaClass.simpleName}", "Denetlenemedi: ${e.message ?: e.javaClass.simpleName}")
                    })
                })
            }
        }.start()
    }

    private fun syncSubs() {
        val dlg = AlertDialog.Builder(act).setTitle(tr("Sync subscriptions", "Abonelikleri eşitle")).setMessage(tr("Reading your subscriptions…", "Abonelikleriniz okunuyor…"))
            .setNegativeButton(tr("Cancel", "İptal"), null).create()
        val calls = Calls()
        dlg.setOnDismissListener { calls.cancelAll() }
        dlg.show()
        val g = calls.gen
        Thread {
            val r = try {
                Result.success(AuthHttp.subscriptions(act, calls, g) { n -> main.post { if (dlg.isShowing) dlg.setMessage(tr("Reading your subscriptions… $n", "Abonelikleriniz okunuyor… $n")) } })
            } catch (e: Throwable) { Result.failure(e) }
            main.post {
                if (act.isDestroyed || g != calls.gen) return@post
                dlg.setOnDismissListener(null); dlg.dismiss()
                r.fold({ subs -> merge(subs) }, { e ->
                    act.message(tr("Sync subscriptions", "Abonelikleri eşitle"), when (e) {
                        is AccountExpired -> tr("Account expired — import new cookies (or sign in again).", "Hesabın süresi doldu — yeni çerezleri içe aktarın (ya da yeniden oturum açın).")
                        is NoWifi -> e.message ?: tr("Not on Wi-Fi", "Wi-Fi bağlı değil")
                        is Cancelled -> return@fold
                        else -> tr("Couldn't sync: ${e.message ?: e.javaClass.simpleName}", "Eşitlenemedi: ${e.message ?: e.javaClass.simpleName}")
                    })
                })
            }
        }.start()
    }

    private fun merge(subs: List<AccountParse.Sub>) {
        if (subs.isEmpty()) { act.message(tr("Sync subscriptions", "Abonelikleri eşitle"), tr("YouTube listed no subscriptions for this account.", "YouTube bu hesap için abonelik listelemedi.")); return }
        val followed = Store.follows()
        val plan = SyncPlan.of(subs, followed.map { it.id }.toSet())
        val now = System.currentTimeMillis()
        val (merged, _) = Follows.merge(followed, plan.add.map { Follow(it.id, it.name.ifEmpty { it.id }, now) })
        Store.setFollows(merged)
        act.search.followsChanged()
        val summary = plan.summary() + "."
        if (plan.gone.isEmpty()) { act.message(tr("Sync subscriptions", "Abonelikleri eşitle"), summary); return }
        // followed here but not subscribed on YouTube: kept unless the user says otherwise
        val names = merged.filter { it.id in plan.gone }.map { it.title }
        AlertDialog.Builder(act).setTitle(tr("Sync subscriptions", "Abonelikleri eşitle"))
            .setMessage(tr("$summary\n\n${names.size} followed channels aren't in your YouTube subscriptions:\n", "$summary\n\nTakip edilen ${names.size} kanal YouTube aboneliklerinizde yok:\n") +
                names.take(12).joinToString("\n") { "• $it" } + (if (names.size > 12) tr("\n… and ${names.size - 12} more", "\n… ve ${names.size - 12} tane daha") else "") +
                tr("\n\nKeep following them? (Unfollowing them only changes this app: they aren't subscribed on YouTube anyway.)",
                    "\n\nTakip etmeye devam edilsin mi? (Takibi bırakmak yalnızca bu uygulamayı değiştirir: YouTube'da zaten abone değilsiniz.)"))
            .setPositiveButton(tr("Keep", "Sakla")) { _, _ -> }
            .setNegativeButton(tr("Unfollow ${names.size}", "Takibi bırak (${names.size})")) { _, _ ->
                val gone = plan.gone.toSet()
                Store.setFollows(Store.follows().filter { it.id !in gone })
                act.search.followsChanged()
                act.toast(tr("Unfollowed ${gone.size}", "${gone.size} kanalın takibi bırakıldı"))
            }
            .show()
    }

    private fun signOut() {
        AlertDialog.Builder(act).setTitle(tr("Sign out?", "Oturum kapatılsın mı?"))
            .setMessage(tr("Deletes the stored cookies and their encryption key from this phone. Members-only videos stop playing; " +
                "Follows stay. (To end the session on Google's side too: Google Account → Security → Your devices.)",
                "Saklanan çerezleri ve şifreleme anahtarlarını bu telefondan siler. Üyelere özel videolar artık oynatılmaz; " +
                "takipler kalır. (Oturumu Google tarafında da bitirmek için: Google Hesabı → Güvenlik → Cihazlarınız.)"))
            .setPositiveButton(tr("Sign out", "Oturumu kapat")) { _, _ -> Account.signOut(); act.toast(tr("Signed out: account data deleted", "Oturum kapatıldı: hesap verileri silindi")) }
            .setNegativeButton(tr("Cancel", "İptal"), null)
            .show()
    }
}
