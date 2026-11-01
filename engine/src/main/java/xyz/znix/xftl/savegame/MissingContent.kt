package xyz.znix.xftl.savegame

import org.jdom2.Document
import org.jdom2.Element

/**
 * Loads a run save that names content the loaded game files lack (a weapon, drone, augment, crew race or event of a
 * mod that is no longer in the save's mod list): each missing item is dropped and listed in [dropped], so the
 * launcher can tell the player, instead of the load failing. Docs: docs/SAVES.md, "Engine requirements".
 *
 * The engine reports a missing name as "Unknown blueprint X" or "Missing event X"; every element
 * that names X (in an attribute or as its whole text) is removed from a copy of the save and the load is tried again.
 */
object MissingContent {
    private val MISSING = Regex("^(?:Unknown blueprint|Missing event) (\\S+)$")
    private const val MAX_DROPS = 64

    /** What the last load dropped, in order; empty when nothing was missing. */
    @JvmStatic
    var dropped: List<String> = emptyList()
        private set

    /** A name the load went on without, for a place that cannot simply lose its element (a beacon's event). */
    @JvmStatic
    fun note(name: String) {
        if (name !in noted) noted.add(name)
    }

    private val noted = LinkedHashSet<String>()

    @JvmStatic
    fun <T> load(save: Document, load: java.util.function.Function<Document, T>): T {
        val names = ArrayList<String>()
        var doc = save
        while (true) {
            try {
                noted.clear()
                val result = load.apply(doc)
                dropped = names + noted.filter { it !in names }
                return result
            } catch (e: RuntimeException) {
                val name = missingName(e) ?: throw e
                if (names.size >= MAX_DROPS || name in names) throw e
                val copy = doc.clone()
                if (!drop(copy.rootElement, name)) throw e
                names.add(name)
                doc = copy
            }
        }
    }

    private fun missingName(e: Throwable): String? {
        var t: Throwable? = e
        while (t != null) {
            MISSING.find(t.message ?: "")?.let { return it.groupValues[1] }
            t = t.cause
        }
        return null
    }

    /** Removes every element below `root` that names `name`; false when there was none (or only the root). */
    private fun drop(root: Element, name: String): Boolean {
        var removed = false
        val iterator = root.children.iterator()
        while (iterator.hasNext()) {
            val child = iterator.next()
            if (child.attributes.any { it.value == name } || (child.children.isEmpty() && child.textTrim == name)) {
                iterator.remove()
                removed = true
            } else if (drop(child, name)) removed = true
        }
        return removed
    }
}
