package app.plain.uninstaller

import app.plain.lang.tr

/** One selected app, as the uninstall plan sees it. */
data class Target(val pkg: String, val label: String, val system: Boolean, val risky: Boolean)

/** How a selection is split between the system uninstall dialog and Shizuku. Plain Kotlin, no Android. */
object Plan {
    /** Apps that only Shizuku can remove. */
    fun needShizuku(targets: List<Target>): List<Target> = targets.filter { it.system }

    /** Apps the system uninstall dialog can remove, one at a time, in selection order. */
    fun dialogQueue(targets: List<Target>): List<Target> = targets.filterNot { it.system }

    fun risky(targets: List<Target>): List<Target> = targets.filter { it.risky }

    /** The command Shizuku runs for each target, in selection order. */
    fun shizukuCommands(targets: List<Target>): List<Pair<Target, String>> =
        targets.map { it to Commands.uninstall(it.pkg, it.system) }

    /** Text for the confirm dialog: one line per app, flagged when it's a system app or risky. */
    fun describe(targets: List<Target>): String = targets.joinToString("\n") { t ->
        val tags = listOfNotNull(if (t.system) tr("system", "sistem") else null, if (t.risky) tr("RISKY", "RİSKLİ") else null)
        "• ${t.label}" + (if (tags.isEmpty()) "" else "  [${tags.joinToString(", ")}]") + "\n   ${t.pkg}"
    }
}

/** Search matching that works for Turkish: "indir" finds "İndirilenler", "ınstagram" finds "Instagram". */
object Search {
    /**
     * Lowercase without the locale's quirks: Kotlin's lowercase() (root locale) turns "İ" into "i" plus a combining dot,
     * so "İndir" never matched "indir"; the dotless "ı" is folded to "i" as well.
     */
    fun fold(s: String): String = s.lowercase().replace(COMBINING_DOT.toString(), "").replace(DOTLESS_I, 'i')

    private val COMBINING_DOT = Char(0x0307) // what lowercase() leaves after "i" from "İ"
    private val DOTLESS_I = Char(0x0131)     // Turkish "ı"

    fun matches(query: String, vararg fields: String): Boolean {
        val q = fold(query.trim())
        return q.isEmpty() || fields.any { q in fold(it) }
    }
}

/** "Select all visible" leaves out risky apps: those are ticked one by one, after reading why they're risky. */
fun Plan.bulkPick(items: List<AppItem>): List<String> = items.filter { it.level != Level.RISKY }.map { it.pkg }
