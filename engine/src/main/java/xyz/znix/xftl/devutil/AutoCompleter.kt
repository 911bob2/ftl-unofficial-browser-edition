package xyz.znix.xftl.devutil

import xyz.znix.xftl.Blueprint
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.f
import xyz.znix.xftl.game.Achievement
import xyz.znix.xftl.game.ButtonImageSet
import xyz.znix.xftl.game.Buttons
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * An auto-completion engine for the debug console.
 */
abstract class AutoCompleter(val console: DebugConsole) : DebugConsole.Overlay {
    abstract fun update(currentToken: String, positionInInput: Int)
    abstract fun applyAutoCompletion()
}

abstract class BasicCompletionEngine<T>(console: DebugConsole, val owner: ArgumentTypeProcessor?) :
    AutoCompleter(console), ConsolePanel {

    abstract val items: List<T>

    // A little caching for the search
    private var lastInput: String? = null
    private val sortedEntries = ArrayList<T>()
    private var positionInInput: Int = 0

    /** The suggestions in their order now. */
    val entries: List<T> get() = sortedEntries

    /** What is being completed now. */
    protected val lastToken: String? get() = lastInput

    /** The next [update] searches again (the items changed). */
    fun invalidate() { lastInput = null }

    protected abstract fun getItemName(item: T): String
    protected open fun getCompletionString(item: T): String = getItemName(item)

    /** Shown after the name, dimmer. */
    protected open fun note(item: T): String? = null

    /** The new commands' suggestions glow (ConsoleUi.rainbow). */
    protected open fun isFeatured(item: T): Boolean = false

    override val autoCompleteSuggestion: String? get() = bestSuggestion?.let { priorCommandText + getItemName(it) }
    protected val bestSuggestion: T? get() = sortedEntries.firstOrNull()
    protected val priorCommandText: String
        get() {
            if (console.input.length <= positionInInput)
                return console.input
            return console.input.substring(0, positionInInput)
        }

    override val contentKey: Any? get() = lastInput

    /** One row a suggestion, scrolled with the wheel or a drag; a tap or click completes it. */
    override fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int {
        val rowH = ui.rowH
        for ((i, item) in sortedEntries.withIndex()) {
            val y = area.y + i * rowH - scroll
            if (y < area.y) continue
            if (y + rowH > area.bottom) break
            val row = Area(area.x, y, area.w, rowH - 1)
            val shade = if (ui.hovered(row)) 140 else 100
            g.colour = Colour(shade, shade, shade, 180)
            g.fillRect(row.x.f, row.y.f, row.w.f, row.h.f)
            val name = getItemName(item)
            val base = ui.baseline(row.y, row.h).f
            if (isFeatured(item)) ConsoleUi.rainbow(ui.font, row.x + ui.pad.f, base, name, ui.time)
            else ui.font.drawString(row.x + ui.pad.f, base, name, Colour.white)
            note(item)?.takeIf { it.isNotEmpty() }?.let { n ->
                val nx = row.x + ui.pad * 3f + ui.font.getWidth(name)
                ui.small.drawStringTruncated(nx, ui.baseline(row.y, row.h, ui.small).f, row.right - nx - ui.pad, n, Colour(210, 210, 210))
            }
            ui.hit(row) { itemSelected(item) }
        }
        return sortedEntries.size * rowH
    }

    override fun update(currentToken: String, positionInInput: Int) {
        this.positionInInput = positionInInput

        // Only update when something changes
        if (currentToken == lastInput)
            return
        lastInput = currentToken

        sortedEntries.clear()
        sortedEntries.addAll(items)

        if (currentToken.isBlank()) {
            // If there's no search term, leave the items in their original order.
            itemUpdateFinished(sortedEntries)
            return
        }

        sortEntries(sortedEntries, currentToken)

        itemUpdateFinished(sortedEntries)
    }

    protected open fun sortEntries(entries: ArrayList<T>, currentToken: String) {
        // Display all the entries - sorting only orders them
        val searcher = DebugConsole.FuzzySearcher(currentToken)
        sortedEntries.sortByDescending { searcher.rank(getItemName(it)) }
    }

    protected open fun itemUpdateFinished(sortedEntries: List<T>) = Unit

    protected open fun itemSelected(item: T) {
        console.input = priorCommandText + getCompletionString(item) + " "
    }

    override fun applyAutoCompletion() {
        val first = sortedEntries.firstOrNull() ?: return
        itemSelected(first)
    }
}

class CommandCompleter(console: DebugConsole) : BasicCompletionEngine<DebugConsole.Cmd>(console, null) {

    override val items: List<DebugConsole.Cmd> get() = console.commands

    override fun getItemName(item: DebugConsole.Cmd): String {
        return item.name
    }

    override fun note(item: DebugConsole.Cmd) = (if (item.args.isNotEmpty()) item.args + "   " else "") + item.helpText

    override fun isFeatured(item: DebugConsole.Cmd) = item.featured

    override fun sortEntries(entries: ArrayList<DebugConsole.Cmd>, currentToken: String) {
        // Use fuzzy-search as a backup
        super.sortEntries(entries, currentToken)

        // Pull all the prefix matches to the start
        val matches = entries.filter { it.name.startsWith(currentToken, ignoreCase = true) }.toList()

        // Order the matching commands by name, so they're stable
        matches.sortedBy { it.name }

        // Move them to the front
        entries.removeAll(matches.toSet())
        entries.addAll(0, matches)
    }
}

class BlueprintCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?, val type: Class<out Blueprint>) :
    BasicCompletionEngine<Blueprint>(console, owner) {

    private val isWeapons = AbstractWeaponBlueprint::class.java.isAssignableFrom(type)
    private val isDrones = DroneBlueprint::class.java.isAssignableFrom(type)

    private var buttons: List<CompletionButton> = emptyList()

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(Blueprint::class.java)
        .filter { type.isAssignableFrom(it.javaClass) }

    override fun getItemName(item: Blueprint): String {
        return item.translateTitle(console.game)
    }

    override fun getCompletionString(item: Blueprint): String {
        return item.name
    }

    override fun itemUpdateFinished(sortedEntries: List<Blueprint>) {
        if (isWeapons) {
            val images = ButtonImageSet.select2(console.game, "img/storeUI/store_buy_weapons")
            buttons = sortedEntries.map { CompletionButton(it, images) }
        }
        if (isDrones) {
            val images = ButtonImageSet.select2(console.game, "img/storeUI/store_buy_drones")
            buttons = sortedEntries.map { CompletionButton(it, images) }
        }
    }

    /** Weapons and drones as the store's boxes, scrolled; the rest as rows. */
    override fun draw(g: Graphics, ui: ConsoleUi, area: Area, scroll: Int): Int {
        if (!isWeapons && !isDrones) return super.draw(g, ui, area, scroll)
        val first = buttons.firstOrNull() ?: return 0
        val w = first.image.normal.width
        val h = first.image.normal.height
        val gap = 5
        val columns = maxOf(1, (area.w + gap) / (w + gap))
        for ((i, button) in buttons.withIndex()) {
            val x = area.x + (i % columns) * (w + gap)
            val y = area.y + (i / columns) * (h + gap) - scroll
            if (y < area.y) continue
            if (y + h > area.bottom) break
            button.windowOffset = ConstPoint(x, y)
            button.update(if (ui.touch) -1 else ui.mouseX, if (ui.touch) -1 else ui.mouseY)
            button.draw(g)
            ui.hit(Area(x, y, w, h)) { itemSelected(button.blueprint) }
        }
        return (buttons.size + columns - 1) / columns * (h + gap)
    }

    // Use BlueprintButton's rendering for weapons/drones
    private inner class CompletionButton(override val blueprint: Blueprint, images: ButtonImageSet) :
        Buttons.BlueprintButton(ConstPoint.ZERO, console.game, images) {
        override val makesHoverNoise: Boolean get() = false

        override fun click(button: Int) {
            itemSelected(blueprint)
        }
    }
}

/**
 * An auto-completer that shows matching blueprints, along with a selection
 * of additional items.
 *
 * The blueprints are always drawn in a list, as the extra values can't cleanly
 * be shown in drone/weapon button tiles.
 */
class BlueprintAndExtrasCompleter(
    console: DebugConsole,
    owner: ArgumentTypeProcessor?,
    val type: Class<out Blueprint>,
    extras: List<String>
) :
    BasicCompletionEngine<Pair<String, Blueprint?>>(console, owner) {

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(Blueprint::class.java)
        .filter { type.isAssignableFrom(it.javaClass) }
        .map { Pair(it.name, it) } + extras.map { Pair(it, null) }

    override fun getItemName(item: Pair<String, Blueprint?>): String {
        return item.second?.translateTitle(console.game) ?: item.first
    }

    override fun getCompletionString(item: Pair<String, Blueprint?>): String {
        return item.first
    }
}

class EnemyShipSpecCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<EnemyShipSpec>(console, owner) {

    override val items = console.game.eventManager.getShips().toList()

    override fun getItemName(item: EnemyShipSpec): String {
        return item.name
    }
}

class AchievementCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<Achievement>(console, owner) {

    override val items = console.game.content.achievements.achievements.values.sortedBy { it.id }

    override fun getItemName(item: Achievement): String {
        return console.game.translator[item.name]
    }

    override fun getCompletionString(item: Achievement): String {
        return item.id
    }
}

class ShipFamilyCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?) :
    BasicCompletionEngine<LazyShipBlueprint>(console, owner) {

    override val items = console.game.blueprintManager.blueprints.values
        .filterIsInstance(LazyShipBlueprint::class.java)
        .sortedBy { it.name } // Stable ordering
        // Only use one ship per class
        .associateBy { console.game.content.shipFamilies.byShipId[it.name] }
        .filterKeys { it != null } // Exclude the ship representing a null family
        .values
        .toList()

    override fun getItemName(item: LazyShipBlueprint): String {
        return item.shipClass?.get(console.game.translator) ?: item.name
    }

    override fun getCompletionString(item: LazyShipBlueprint): String {
        return item.name
    }
}

class EnumCompleter(console: DebugConsole, owner: ArgumentTypeProcessor?, override val items: List<Enum<*>>) :
    BasicCompletionEngine<Any>(console, owner) {

    override fun getItemName(item: Any): String {
        check(item is Enum<*>)
        return item.name
    }
}
