package xyz.znix.xftl.devutil

import xyz.znix.xftl.Constants
import xyz.znix.xftl.f
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics

/**
 * The command reference (`help`, docs/CONSOLE.md): every command grouped ([ConsoleRegistry.Groups]), each with its usage,
 * what it does and examples, all from the command definitions themselves. It is the completer of help's argument: what
 * is typed after `help` searches it. A tap or click on a command fills it into the input line, on an example that line.
 * [markdown] writes the same reference for docs/CONSOLE.md (ConsoleTest keeps the two the same).
 */
class ConsoleReference(console: DebugConsole) : AutoCompleter(console), ConsolePanel {
    private var search = ""
    private var start = 0
    private var shown: List<DebugConsole.Cmd> = matching(console.commands, "")
    /** Each entry's wrapped text and example rows, by name and width. */
    private val layout = HashMap<String, Pair<List<String>, List<List<String>>>>()

    override fun update(currentToken: String, positionInInput: Int) {
        start = positionInInput
        if (currentToken == search) return
        search = currentToken
        selected = null
        shown = matching(console.commands, currentToken)
    }

    override val contentKey: Any? get() = search

    override fun applyAutoCompletion() {
        shown.firstOrNull()?.let { fill(it.name + " ") }
    }

    private fun fill(line: String) {
        console.input = line
    }

    override val big: Boolean get() = true

    override fun title(): String = "HELP"

    // ---- the selection (the keys): a command, by name; each entry's place in the content as last drawn
    private var selected: String? = null
    private val entryAt = LinkedHashMap<String, IntRange>()

    override val hasSelection: Boolean get() = selected != null && shown.any { it.name == selected }

    override fun clearSelection(): Boolean {
        val had = hasSelection
        selected = null
        return had
    }

    override fun navigate(key: Int): IntRange? {
        val names = entryAt.keys.toList()
        if (names.isEmpty()) return null
        val i = names.indexOf(selected).takeIf { hasSelection } ?: -1
        val next = when (key) {
            Input.KEY_DOWN -> if (i < 0) names.indexOfFirst { (entryAt[it]?.first ?: 0) >= lastScroll }.coerceAtLeast(0) else (i + 1).coerceAtMost(names.size - 1)
            Input.KEY_UP -> when {
                i < 0 -> return null
                i == 0 -> { selected = null; return IntRange.EMPTY }
                else -> i - 1
            }
            else -> return null
        }
        selected = names[next]
        return entryAt[names[next]]
    }

    override fun activate(): Boolean {
        val cmd = shown.firstOrNull { it.name == selected } ?: return false
        fill(cmd.name + " ")
        return true
    }

    private var lastScroll = 0

    /** The fixed header beside the tab: how many commands, the search, what a tap does. */
    override fun drawHeader(g: Graphics, ui: ConsoleUi, area: Area): Int {
        val rowH = if (ui.touch) 52 else 30
        val title = "${shown.size} commands" + (if (search.isNotBlank()) " matching '$search'" else "")
        ui.font.drawString(area.x.f, ui.baseline(area.y, rowH).f, title, Colour.white)
        val x = area.x + ui.font.getWidth(title) + ui.pad * 3
        val hint = if (ui.touch) "Type after 'help' to search; tap a command or an example to fill it in"
            else "Type after 'help' to search; click a command or an example to fill it in, or use the arrows and Enter"
        ui.small.drawString(x.f, ui.baseline(area.y, rowH, ui.small).f, ui.fit(ui.small, hint, area.right - x), ConsoleUi.DIM)
        return rowH
    }

    override fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int {
        val wrapW = area.w - ui.pad * 6
        lastScroll = scroll
        entryAt.clear()
        var y = area.y - scroll + ui.pad / 2
        for ((group, cmds) in shown.groupBy { it.group }.entries.sortedBy { ConsoleRegistry.Groups.ORDER.indexOf(it.key).let { i -> if (i < 0) 99 else i } }) {
            val headH = ui.lineH + ui.pad
            if (y + headH > area.y && y < area.bottom) {
                g.colour = Constants.SECTOR_CUTOUT
                g.fillRect(area.x.f, y.f, area.w.f, headH.f)
                g.colour = ConsoleUi.HEADING
                g.fillRect(area.x.f, y.f, (if (ui.touch) 6f else 3f), headH.f)
                val label = group.ifEmpty { "Other" }
                if (group == ConsoleRegistry.Groups.GET) ConsoleUi.rainbow(ui.font, area.x + ui.pad * 2f, ui.baseline(y, headH).f, label, ui.time)
                else ui.font.drawString(area.x + ui.pad * 2f, ui.baseline(y, headH).f, label, ConsoleUi.HEADING)
            }
            y += headH + ui.pad / 2
            for (cmd in cmds) {
                val (desc, exampleRows) = layout.getOrPut(cmd.name + "@" + wrapW) {
                    Pair(ui.small.wrapPlain(cmd.helpText + if (cmd.needsHs) " (needs Hyperspace)" else "", wrapW), examplesRows(ui, cmd, wrapW))
                }
                val h = ui.lineH + desc.size * (ui.smallH + ui.pad) + exampleRows.size * (ui.smallH + ui.pad * 2) + ui.pad
                entryAt[cmd.name] = (y + scroll - area.y)..(y + scroll - area.y + h)
                if (y + h > area.y && y < area.bottom) drawEntry(g, ui, cmd, area, y, h, desc, exampleRows)
                y += h
            }
        }
        return y + scroll - area.y + ui.pad
    }

    /** The examples, as many to a row as fit. */
    private fun examplesRows(ui: ConsoleUi, cmd: DebugConsole.Cmd, width: Int): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        var w = ui.small.getWidth("e.g.") + ui.pad * 2
        for (e in cmd.examples) {
            val ew = ui.small.getWidth(e) + ui.pad * 3
            if (row.isNotEmpty() && w + ew > width) { rows += row; row = ArrayList(); w = ui.small.getWidth("e.g.") + ui.pad * 2 }
            row += e; w += ew
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    private fun drawEntry(g: Graphics, ui: ConsoleUi, cmd: DebugConsole.Cmd, area: Area, top: Int, h: Int, desc: List<String>, examples: List<List<String>>) {
        val entry = Area(area.x, top, area.w, h - ui.pad / 2)
        val nameRow = Area(area.x, top, area.w, ui.lineH)
        val hot = ui.hovered(nameRow) || ui.pressed(nameRow)
        g.colour = if (hot) ConsoleUi.ROW_HOT else ENTRY
        g.fillRect(entry.x.f, entry.y.f, entry.w.f, entry.h.f)
        if (hot) { g.colour = Constants.UI_BUTTON_HOVER; g.fillRect(entry.x.f, entry.y.f, (if (ui.touch) 4f else 2f), ui.lineH.f) }
        if (cmd.name == selected) ui.outline(g, entry, ConsoleUi.SELECTED, if (ui.touch) 3 else 2)
        val base = ui.baseline(top, ui.lineH).f
        if (cmd.featured) ConsoleUi.rainbow(ui.font, area.x + ui.pad.f, base, cmd.name, ui.time)
        else ui.font.drawString(area.x + ui.pad.f, base, cmd.name, Colour.white)
        ui.font.drawString(area.x + ui.pad * 3f + ui.font.getWidth(cmd.name), base, cmd.args, ARGS)
        ui.hit(nameRow) { selected = cmd.name; fill(cmd.name + " ") }
        var y = top + ui.lineH
        for (line in desc) {
            ui.small.drawString(area.x + ui.pad * 3f, y + ui.smallH.f, line, ConsoleUi.TEXT)
            y += ui.smallH + ui.pad
        }
        for (row in examples) {
            var x = area.x + ui.pad * 3
            ui.small.drawString(x.f, y + ui.smallH + ui.pad.f, "e.g.", ConsoleUi.DIM)
            x += ui.small.getWidth("e.g.") + ui.pad * 2
            for (e in row) {
                val a = Area(x - ui.pad, y, ui.small.getWidth(e) + ui.pad * 2, ui.smallH + ui.pad * 2)
                ui.rowFill(g, a, ui.hovered(a) || ui.pressed(a))
                if (cmd.featured) ConsoleUi.rainbow(ui.small, x.f, y + ui.smallH + ui.pad.f, e, ui.time, 0f)
                else ui.small.drawString(x.f, y + ui.smallH + ui.pad.f, e, Colour(150, 220, 255))
                ui.hit(a) { fill(e) }
                x += a.w + ui.pad
            }
            y += ui.smallH + ui.pad * 2
        }
    }

    companion object {
        private val ENTRY = Colour(18, 26, 32, 215)
        private val ARGS = Colour(150, 205, 255)

        /** The commands [search] finds in their name, arguments, text, group or examples (every one for an empty search). */
        @JvmStatic
        fun matching(commands: List<DebugConsole.Cmd>, search: String): List<DebugConsole.Cmd> {
            val words = search.lowercase().split(' ').filter { it.isNotEmpty() }
            val ordered = commands.sortedBy { c -> ConsoleRegistry.Groups.ORDER.indexOf(c.group).let { if (it < 0) 99 else it } }
            if (words.isEmpty()) return ordered
            val byName = ordered.filter { c -> words.any { c.name.equals(it, true) } }
            val rest = ordered.filter { c ->
                c !in byName && words.all { w -> (c.name + " " + c.args + " " + c.helpText + " " + c.group + " " + c.examples.joinToString(" ")).lowercase().contains(w) }
            }
            return byName + rest
        }

        private fun md(s: String) = s.replace("|", "\\|")

        /** The reference as docs/CONSOLE.md shows it, between its generated markers. */
        @JvmStatic
        fun markdown(commands: List<DebugConsole.Cmd>): String {
            val sb = StringBuilder()
            for ((group, cmds) in matching(commands, "").groupBy { it.group }) {
                sb.append("### ").append(group.ifEmpty { "Other" }).append("\n\n")
                sb.append("| Command | What it does | Examples |\n| --- | --- | --- |\n")
                for (c in cmds) {
                    sb.append("| `").append(md(c.usage)).append("` | ").append(md(c.helpText))
                    if (c.needsHs) sb.append(" (needs Hyperspace)")
                    sb.append(" | ").append(c.examples.joinToString(", ") { "`" + md(it) + "`" }).append(" |\n")
                }
                sb.append("\n")
            }
            return sb.toString().trimEnd() + "\n"
        }
    }
}
