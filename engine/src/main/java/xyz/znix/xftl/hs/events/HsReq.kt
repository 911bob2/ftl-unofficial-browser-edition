// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (ShipObject::HasEquipment hooks,
// CheckCargo, HasItem) and CustomEvents.h (CustomReq) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState

/**
 * ShipObject::HasEquipment as Hyperspace chains it, outermost first:
 *
 * - -1000: the Lua HAS_EQUIPMENT chain ([luaHasEquipment], set by the lua workstream), on advanced checks only;
 * - -100: the advanced names (ANY/ALL/SUM, SEC, SHIP, ACH, ITEM, difficulty, dlc, hull), `<req>` definitions, run and
 *   meta variables, on advanced checks only ([HsEvents.advancedCheck] non-zero);
 * - 0: cargo ([HsEvents.checkCargo]) and the highest artillery level;
 * - 2000: hidden augments ("HIDDEN name") and augment functions ([augmentHook], hs.ship);
 * - the original (equipList).
 *
 * The ship can be null (the player's ship before it exists): only the names that need no ship answer then.
 */
object HsReq {
    /** HAS_EQUIPMENT callbacks: (ship, name, value) -> new value. Null while Lua is not loaded. */
    @JvmField var luaHasEquipment: ((Ship?, String, Int) -> Int)? = null

    /** CustomAugments.cpp HasEquipment (augment functions); hidden augments are counted here. Null until hs.ship sets it. */
    @JvmField var augmentHook: ((Ship, String) -> Int)? = null

    /** CustomAchievementTracker::GetAchievementStatus for "ACH x" (-1 when locked); null until achievements exist. */
    @JvmField var achievementStatus: ((String) -> Int)? = null

    /** Diagnostics: each HasEquipment that reaches the HAS_EQUIPMENT hook (advanced checks): name, result. */
    @JvmField var trace: ((String, Int) -> Unit)? = null

    fun hasEquipment(game: InGameState, ship: Ship?, name: String): Int {
        var ret = advanced(game, ship, name)
        if (HsEvents.advancedCheck != 0) {
            luaHasEquipment?.let { ret = it(ship, name, ret) }
            trace?.invoke(name, ret)
        }
        return ret
    }

    private fun advanced(game: InGameState, ship: Ship?, name: String): Int {
        if (HsEvents.advancedCheck != 0) {
            fun list(child: String): List<String> = blueprintList(game, child).ifEmpty { listOf(child) }
            when {
                name.startsWith("ANY ") -> return list(name.substring(4)).fold(0) { a, b -> maxOf(a, hasEquipment(game, ship, b)) }
                name.startsWith("ALL ") -> return list(name.substring(4)).fold(Int.MAX_VALUE) { a, b -> minOf(a, hasEquipment(game, ship, b)) }
                name.startsWith("SUM ") -> return list(name.substring(4)).sumOf { hasEquipment(game, ship, it) }
                name.startsWith("SEC ") -> return if (sectorType(game) == name.substring(4)) 1 else 0
                name.startsWith("SHIP ") -> return if (ship != null && ship.type.name == name.substring(5)) 1 else 0
                name.startsWith("ACH ") -> return (achievementStatus?.invoke(name.substring(4)) ?: -1) + 1
                name.startsWith("ITEM ") -> return hasItem(game, ship, name.substring(5))
                name == "difficulty" -> return game.difficulty.ordinal
                name == "dlc" -> return if (game.isAdvancedEdition) 1 else 0
                name == "hull" -> return ship?.health ?: 0
            }
            game.eventManager.hs?.getCustomReq(name)?.let { return customReq(game, ship, it) }
            HsEvents.playerVariables[name]?.let { return it }
            HsEvents.metaVariables[name]?.let { return it }
        }
        return checkCargoLevel(game, ship, name)
    }

    /** Priority 0: the original (via augments), then cargo, then the artillery fix. */
    private fun checkCargoLevel(game: InGameState, ship: Ship?, name: String): Int {
        if (ship == null) return 0
        var ret = augments(game, ship, name)
        if (HsEvents.checkCargo) ret = checkCargo(game, ship, name, ret)
        if (name == "artillery") {
            for (sys in ship.systems) if (sys.codename == "artillery" && sys.energyLevels > ret) ret = sys.energyLevels
        }
        return ret
    }

    /** Priority 2000: hidden augments and augment functions, plus the original. */
    private fun augments(game: InGameState, ship: Ship, name: String): Int {
        var count = HsEvents.hiddenAugs["HIDDEN $name"] ?: 0
        augmentHook?.let { count += it(ship, name) }
        return count + equipList(game, ship, name)
    }

    /**
     * The original ShipObject::HasEquipment (0x46d350): a blueprint list is 1 when any entry is present, otherwise
     * the equipList count. equipList is modelled from the ship: systems and the reactor by level, crew by species
     * (the player's living crew), weapons, drones and augments by count; the cargo hold is not part of it.
     */
    fun equipList(game: InGameState, ship: Ship, name: String): Int {
        val list = blueprintList(game, name)
        if (list.isNotEmpty()) {
            for (entry in list) if (equipCount(game, ship, entry) > 0) return 1
            return 0
        }
        return equipCount(game, ship, name)
    }

    private fun equipCount(game: InGameState, ship: Ship, name: String): Int {
        ship.systems.firstOrNull { it.codename == name }?.let { return it.energyLevels }
        if (name == "reactor") return ship.purchasedReactorPower
        var n = ship.hsExtraEquipment[name] ?: 0
        if (ship === game.player) for (crew in game.playerCrew) if (crew.health > 0f && crew.codename == name) n++
        for (hp in ship.hardpoints) if (hp.weapon?.type?.name == name) n++
        ship.drones?.drones?.forEach { if (it?.type?.name == name) n++ }
        for (aug in ship.augments) if (aug.name == name) n++
        return n
    }

    /** ShipObject::CheckCargo: a list counts once when any cargo item is in it; a name counts every match. */
    fun checkCargo(game: InGameState, ship: Ship, name: String, current: Int): Int {
        val list = blueprintList(game, name)
        if (current != 0 && list.isNotEmpty()) return current
        var ret = current
        if (ship !== game.player) return ret // the equipment screen is the player's
        for (item in ship.allCargo()) {
            if (list.isNotEmpty()) {
                if (item.name in list) return 1
            } else if (item.name == name) ret++
        }
        return ret
    }

    /** ShipObject::HasItem ("ITEM x"): the exact equipList entry (no reqs, no augment functions), plus cargo. */
    fun hasItem(game: InGameState, ship: Ship?, name: String): Int {
        if (ship == null) return 0
        var ret = equipList(game, ship, name)
        if (HsEvents.checkCargo) ret = checkCargo(game, ship, name, ret)
        return ret
    }

    /** CustomReq::HasEquipment. */
    fun customReq(game: InGameState, ship: Ship?, req: CustomReq): Int {
        var ret: Int
        when (req.type) {
            CustomReq.TYPE_ALL -> {
                ret = Int.MAX_VALUE
                for (bp in req.blueprints) ret = minOf(ret, hasEquipment(game, ship, bp))
                for (child in req.children) ret = minOf(ret, customReq(game, ship, child))
            }
            CustomReq.TYPE_SUM -> {
                ret = 0
                for (bp in req.blueprints) ret += hasEquipment(game, ship, bp)
                for (child in req.children) ret += customReq(game, ship, child)
            }
            else -> {
                ret = 0
                for (bp in req.blueprints) ret = maxOf(ret, hasEquipment(game, ship, bp))
                for (child in req.children) ret = maxOf(ret, customReq(game, ship, child))
            }
        }
        if (req.lvl > -1 || req.maxLvl > -1) {
            val counter = ret
            ret = if (counter >= req.lvl) 1 else 0
            if (req.maxLvl > -1) ret = ret and (if (counter <= req.maxLvl) 1 else 0)
        }
        return ret * req.mult + req.constant
    }

    /** BlueprintManager::GetBlueprintList: the names of a `<blueprintList>`, empty for anything else. */
    fun blueprintList(game: InGameState, name: String): List<String> =
        (game.blueprintManager.getOrNull(name) as? BlueprintList)?.names ?: emptyList()

    /**
     * The current sector's description type ("SEC x"); the first sector is CIVILIAN_SECTOR, as in the original. While a
     * map is generated the star map's current sector is already the new one.
     */
    fun sectorType(game: InGameState): String? {
        val info = HsEvents.generatingSector ?: game.currentBeacon?.sector?.info ?: return null
        return HsEvents.descriptionType(info)
    }
}
