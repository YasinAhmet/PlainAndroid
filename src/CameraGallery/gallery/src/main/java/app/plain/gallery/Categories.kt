package app.plain.gallery

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.util.AtomicFile
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File

/** The categories, kept in the app's own private storage (a small text file). Main thread only. */
object Categories {
    private var tags: Tags? = null

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, "categories.txt"))

    fun get(ctx: Context): Tags = tags ?: run {
        val t = try { Tags.parse(String(file(ctx).readFully(), Charsets.UTF_8)) } catch (_: Exception) { Tags() }
        tags = t
        t
    }

    fun save(ctx: Context) {
        val t = tags ?: return
        val f = file(ctx)
        val out = f.startWrite()
        try { out.write(t.serialize().toByteArray(Charsets.UTF_8)); f.finishWrite(out) } catch (e: Exception) { f.failWrite(out) }
    }

    /** Asks for a category name (OK only with a non-blank name). */
    fun askName(a: Activity, title: String, initial: String, done: (String) -> Unit) {
        val e = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            setText(initial)
            setSelection(text.length)
            hint = "Name"
        }
        val box = FrameLayout(a).apply { setPadding(a.dp(22), a.dp(8), a.dp(22), 0); addView(e) }
        AlertDialog.Builder(a).setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ -> Tags.clean(e.text.toString()).takeIf { it.isNotEmpty() }?.let(done) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * The categorize dialog for one or many items: a checkbox per category ("(some)" when only
     * part of the selection is in it) and "New…" to add a category on the spot.
     */
    fun choose(a: Activity, ids: Collection<Long>, done: () -> Unit, preChecked: String? = null) {
        val t = get(a)
        if (t.names.isEmpty()) {
            askName(a, "New category", "") { n -> t.add(n, ids); save(a); done() }
            return
        }
        val names = t.names
        val states = names.map { t.state(it, ids) }
        val checked = BooleanArray(names.size) { states[it] == Tags.ALL || names[it] == preChecked }
        val touched = BooleanArray(names.size) { names[it] == preChecked }
        val labels = names.mapIndexed { i, n -> if (states[i] == Tags.SOME) "$n  (some)" else n }.toTypedArray()
        val apply = { names.forEachIndexed { i, n -> t.applyChoice(n, ids, checked[i], touched[i]) }; save(a) }
        AlertDialog.Builder(a)
            .setTitle(if (ids.size == 1) "Categories" else "Categories for ${Paging.count(ids.size)} items")
            .setMultiChoiceItems(labels, checked) { _, w, on -> checked[w] = on; touched[w] = true }
            .setPositiveButton("OK") { _, _ -> apply(); done() }
            .setNeutralButton("New…") { _, _ ->
                apply()
                askName(a, "New category", "") { n -> val c = t.create(n) ?: return@askName; save(a); choose(a, ids, done, c) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
