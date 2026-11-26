package app.plain.lang

import android.app.Activity
import android.content.Context
import android.content.res.Resources
import android.view.View
import android.widget.PopupMenu
import java.util.Locale

/**
 * English / Turkish for every Plain app. Strings live next to the code as pairs:
 *
 *     tr("Settings", "Ayarlar")
 *     tr("$n songs", "$n şarkı")
 *
 * The choice is "system" (default), "en" or "tr". "system" means Turkish when Turkish is anywhere
 * in the phone's language list, English otherwise. Call [init] at the top of every Activity,
 * Service and Receiver onCreate/onReceive that shows text, so the saved choice is read before any
 * string is built. The settings row opens [choose], a dropdown under the row.
 *
 * Canonical copy: PlainAndroid/shared/PlainLang.kt. Every app keeps an identical copy at
 * app/src/main/java/app/plain/lang/PlainLang.kt — change them together.
 */
object PlainLang {
    const val SYSTEM = "system"
    const val EN = "en"
    const val TR = "tr"
    private const val PREFS = "plain_lang"
    private const val KEY = "lang"

    /** True while the UI is in Turkish. Before [init] it follows the system languages. */
    @Volatile
    var turkish: Boolean = systemTurkish()
        private set

    /** The saved choice: [SYSTEM], [EN] or [TR]. */
    @Volatile
    var choice: String = SYSTEM
        private set

    val locale: Locale get() = if (turkish) TURKISH else Locale.ENGLISH
    private val TURKISH: Locale = Locale.forLanguageTag("tr-TR")

    fun init(c: Context) {
        val v = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM) ?: SYSTEM
        choice = v
        turkish = when (v) { TR -> true; EN -> false; else -> systemTurkish() }
    }

    fun set(c: Context, v: String) {
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
        init(c)
    }

    /** Turkish when the phone lists Turkish among its languages (Settings > System > Languages). */
    fun systemTurkish(): Boolean = try {
        val ls = Resources.getSystem().configuration.locales
        (0 until ls.size()).any { ls[it].language == "tr" }
    } catch (_: Throwable) {
        false   // JVM unit tests: android.jar stubs throw; tests see English
    }

    /** Name of a choice, written in its own language. */
    fun name(v: String): String = when (v) {
        EN -> "English"
        TR -> "Türkçe"
        else -> tr("System default", "Sistem varsayılanı") + " (" + (if (systemTurkish()) "Türkçe" else "English") + ")"
    }

    /** Settings row text, e.g. "Language: English" / "Dil: Türkçe". */
    fun label(): String = tr("Language", "Dil") + ": " + name(choice)

    /**
     * The language dropdown: a menu under [anchor] (or a dialog list when there is none). After a
     * change [onChanged] runs; the default recreates the activity so every string is rebuilt.
     */
    fun choose(a: Activity, anchor: View?, onChanged: () -> Unit = { a.recreate() }) {
        val values = listOf(SYSTEM, EN, TR)
        val pick = { v: String -> if (v != choice) { set(a, v); onChanged() } }
        if (anchor != null) {
            val m = PopupMenu(a, anchor)
            values.forEachIndexed { i, v -> m.menu.add(0, i, i, name(v)).isCheckable = true }
            m.menu.setGroupCheckable(0, true, true)
            m.menu.getItem(values.indexOf(choice).coerceAtLeast(0)).isChecked = true
            m.setOnMenuItemClickListener { pick(values[it.itemId]); true }
            m.show()
        } else {
            android.app.AlertDialog.Builder(a)
                .setTitle(tr("Language", "Dil"))
                .setSingleChoiceItems(values.map { name(it) }.toTypedArray(), values.indexOf(choice).coerceAtLeast(0)) { d, i ->
                    d.dismiss(); pick(values[i])
                }
                .setNegativeButton(tr("Cancel", "İptal"), null)
                .show()
        }
    }

    /** Upper case that gets Turkish dotted/dotless i right ("liste" -> "LİSTE"). */
    fun upper(s: String): String = s.uppercase(locale)

    fun lower(s: String): String = s.lowercase(locale)
}

/** The English or Turkish text, by the current language. */
fun tr(en: String, tr: String): String = if (PlainLang.turkish) tr else en
