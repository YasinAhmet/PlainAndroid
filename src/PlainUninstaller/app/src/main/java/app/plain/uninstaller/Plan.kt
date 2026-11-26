package app.plain.uninstaller

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
        val tags = listOfNotNull(if (t.system) "system" else null, if (t.risky) "RISKY" else null)
        "• ${t.label}" + (if (tags.isEmpty()) "" else "  [${tags.joinToString(", ")}]") + "\n   ${t.pkg}"
    }
}
