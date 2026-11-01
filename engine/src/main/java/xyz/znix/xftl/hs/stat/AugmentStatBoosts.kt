// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomAugments.cpp (the <statBoosts> part of
// ParseCustomAugmentNode), src/game/Ship/StatBoost.cpp (StatBoostManager::OnLoop augment boosts)
package xyz.znix.xftl.hs.stat

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS

/**
 * The stat boosts of `<augments><aug><statBoosts>` (CustomAugmentManager's AugmentDefinition::statBoosts, parsed with
 * BoostSource AUGMENT) and the augment counts StatBoostManager::OnLoop reads (CustomAugmentManager::GetShipAugments,
 * the augment list with hidden augments).
 */
object AugmentStatBoosts {
    /** Augment name -> its stat boost definitions, in parse order. */
    @JvmField val defs = LinkedHashMap<String, ArrayList<StatBoostDefinition>>()

    /**
     * The ship's augments with hidden ones (augListWithHidden). The ships workstream owns hidden augments
     * (HsAugments.augListWithHidden) and should set this; until then the fallback counts the slotted augments plus
     * the `<hiddenAug>` entries of the ship's `<customShip>` (start of run only: events that add or remove hidden
     * augments are not seen).
     */
    @JvmField var augmentCounts: (Ship) -> Map<String, Int> = ::fallbackCounts

    fun reset() {
        defs.clear()
    }

    /** ParseCustomAugmentNode, `<statBoosts>` children only (the rest is the ships workstream's). */
    fun parseAugmentsNode(node: Element) {
        for (aug in node.children) {
            if (aug.name != "aug") continue
            val name = aug.getAttributeValue("name") ?: continue
            for (f in aug.children) {
                if (f.name != "statBoosts") continue
                for (b in f.children) {
                    if (b.name != "statBoost") continue
                    defs.getOrPut(name) { ArrayList() } += StatBoostDefs.parse(b, StatBoostDefinition.BoostSource.AUGMENT, false)
                }
            }
        }
    }

    /** (definitions, count) for StatBoostManager.augmentBoosts: every augment the ship has with stat boosts. */
    @JvmStatic
    fun boosts(ship: Ship): List<Pair<List<StatBoostDefinition>, Int>> {
        if (defs.isEmpty()) return emptyList()
        val out = ArrayList<Pair<List<StatBoostDefinition>, Int>>()
        for ((name, count) in augmentCounts(ship)) {
            val d = defs[name] ?: continue
            if (count > 0) out += d to count
        }
        return out
    }

    private val hiddenByShip = HashMap<String, Map<String, Int>>()

    private fun fallbackCounts(ship: Ship): Map<String, Int> {
        val list = LinkedHashMap<String, Int>()
        for (a in ship.augments) list[a.name] = (list[a.name] ?: 0) + 1
        val hidden = hiddenByShip.getOrPut(ship.type.name) {
            val m = LinkedHashMap<String, Int>()
            // CustomShipSelect: the last <customShip> of a name wins.
            val node = HS.cfg().nodesOf("ships").flatMap { it.getChildren("customShip") }
                .lastOrNull { it.getAttributeValue("name") == ship.type.name }
            if (node != null) for (h in node.getChildren("hiddenAug")) {
                val n = h.textTrim
                if (n.isNotEmpty()) m[n] = (m[n] ?: 0) + 1
            }
            m
        }
        for ((n, c) in hidden) list[n] = (list[n] ?: 0) + c
        return list
    }

    fun clearCaches() {
        hiddenByShip.clear()
    }
}
