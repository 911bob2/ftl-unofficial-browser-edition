package xyz.znix.xftl.hs.ship

import org.jdom2.Document
import org.jdom2.Element
import xyz.znix.xftl.game.ShipFamily

/**
 * The Hyperspace hangar order and availability (`CustomShipSelect` OnInit, CycleShipNext/Previous, CycleType,
 * SwitchShip's type buttons, GetShipUnlocked) for the engine's hangar, which keeps its own drawing.
 *
 * Every hangar entry is a [ShipFamily] whose `ships` are always `[name, name_2, name_3]` (HS's variant names); a
 * variant exists when the entry's `a`/`b`/`c` says so and the blueprint is there. The vanilla families come first
 * unless `<ships hideFirstShipPage="true">` (Multiverse) hides HS's first page.
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.cpp (OnInit order and pages, CycleShipNext,
// CycleShipPrevious, CycleType, SwitchShip type buttons) and Gameplay/ShipUnlocks.cpp (ScoreKeeper::GetShipUnlocked).
object HsHangar {
    /** `customShipOrder` after OnInit: the `<shipOrder>` names, then every entry not listed and not `noAppend`. */
    @JvmStatic
    fun order(): List<String> {
        val order = ArrayList(HsShips.customShipOrder)
        for (b in HsShips.buttons) if (!b.noAppend && b.name !in order) order += b.name
        return order
    }

    @JvmStatic
    fun button(name: String): ShipButtonDefinition? {
        val base = if (name.endsWith("_2") || name.endsWith("_3")) name.substring(0, name.length - 2) else name
        return HsShips.buttons.firstOrNull { it.name == base } ?: HsShips.buttons.firstOrNull { it.name == name }
    }

    /** (entry, variant) of a layout name, or null when it is not a Hyperspace entry. */
    @JvmStatic
    fun variantOf(name: String): Pair<ShipButtonDefinition, Int>? {
        HsShips.buttons.firstOrNull { it.name == name }?.let { return Pair(it, 0) }
        if (name.length > 2) {
            val base = name.substring(0, name.length - 2)
            val v = when { name.endsWith("_2") -> 1; name.endsWith("_3") -> 2; else -> return null }
            HsShips.buttons.firstOrNull { it.name == base }?.let { return Pair(it, v) }
        }
        return null
    }

    /** The hangar families in hangar order: vanilla first (unless hidden), then the custom order (no "empty"). */
    @JvmStatic
    fun familyTable(vanilla: ShipFamily.FamilyTable, vanillaOrder: List<String>): ShipFamily.FamilyTable {
        val root = Element("ship_data")
        if (!HsShips.hideFirstPage) {
            for (first in vanillaOrder) {
                val f = vanilla.byShipId[first] ?: continue
                val e = Element("ship_family")
                f.unlockId?.let { e.setAttribute("unlockId", it) }
                e.setAttribute("hasQuest", f.hasQuest.toString())
                for (s in f.ships) e.addContent(Element("ship").setAttribute("id", s))
                for (a in f.achievements) e.addContent(Element("ach").setAttribute("id", a))
                root.addContent(e)
            }
        }
        for (name in order()) {
            if (name == "empty") continue
            val b = HsShips.buttons.firstOrNull { it.name == name } ?: continue
            val e = Element("ship_family").setAttribute("hasQuest", "false")
            for (v in 0..2) e.addContent(Element("ship").setAttribute("id", HsShips.variantName(b.name, v)))
            root.addContent(e)
        }
        return ShipFamily.FamilyTable(Document(root))
    }

    /** A custom layout is offered when its entry has the variant and the unlock state allows it. */
    @JvmStatic
    fun isCustomLayoutUnlocked(name: String): Boolean {
        val (b, v) = variantOf(name) ?: return false
        return b.variantExists(v) && HsShipUnlocks.isUnlocked(b.name, v)
    }

    /** Whether [name] is a layout of a custom entry (as opposed to a vanilla ship). */
    @JvmStatic
    fun isCustomLayout(name: String): Boolean = variantOf(name) != null

    /** The variant buttons: a custom entry's variant exists only with its `a`/`b`/`c` flag. */
    @JvmStatic
    fun variantExists(name: String): Boolean {
        val (b, v) = variantOf(name) ?: return true
        return b.variantExists(v)
    }

    /** ScoreKeeper::GetShipUnlocked for a vanilla ship: vanilla's answer, or a Hyperspace unlock of that layout. */
    @JvmStatic
    fun vanillaUnlocked(name: String, vanilla: Boolean): Boolean =
        vanilla || (HsShipUnlocks.customShipHasUnlock(name) && HsShipUnlocks.isUnlocked(name))

    /** Page of a custom entry (HS shows the vanilla page as page 0, custom entries from page 1, ten per page). */
    @JvmStatic
    fun pageOf(name: String): Int {
        val i = order().indexOf(name)
        return if (i < 0) -1 else i / 10 + 1
    }

    /** `maxShipPage` = ceil(order size / 10). */
    @JvmStatic
    fun maxPage(): Int = (order().size + 9) / 10
}
