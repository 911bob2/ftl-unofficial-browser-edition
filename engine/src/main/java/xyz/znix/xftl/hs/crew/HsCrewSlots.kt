// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/CustomCrew.cpp (CrewMemberFactory::OnLoop: noSlot
// crew and crewCapacityUsed), src/game/Ship/CustomShips.cpp (ShipManager::CanFitCrew), src/game/Gameplay/World/
// CustomEvents.cpp (HS_GetEligibleCrewList, HS_ShuffleSortCrewList), src/game/Ship/CustomCrewManifest.cpp
package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.stat.CrewStat

/**
 * Hyperspace's crew slots: what a crew member takes of the ship's crewLimit (CREW_SLOTS, so 0.5 for a race with
 * `<crewSlots>0.5</crewSlots>`), the noSlot crew that take none and are left out of the crew counts, the manifest and
 * the event crew picks (NO_SLOT), and ESSENTIAL for the event crew-loss order. [install] connects them to the ship
 * rules ([xyz.znix.xftl.hs.ship.HsShipState]) and the event picks ([xyz.znix.xftl.hs.events.HsCrew]) once the races are
 * parsed; without it every crew member counted one slot and none were noSlot or essential.
 */
object HsCrewSlots {
    /**
     * CalculateStat(NO_SLOT): the crew member's own noSlot (its race's, or a noSlot spawn's) with its boosts; a species
     * without a race definition keeps its own noSlot (HS_GetEligibleCrewList).
     */
    @JvmStatic
    fun noSlot(crew: LivingCrew): Boolean {
        val ex = HsCrew.ex(crew)
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return ex.noSlot
        return ex.calculateBool(CrewStat.NO_SLOT, def)
    }

    private fun stat(crew: LivingCrew, stat: CrewStat, none: Float): Float {
        val def = CustomCrewManager.definitionOrNull(crew.hsSpecies) ?: return none
        return HsCrew.ex(crew).calculateStat(stat, def)
    }

    /**
     * CustomCrewManager::crewCapacityUsed as CrewMemberFactory::OnLoop sums it: CREW_SLOTS of every crew member of the
     * player (iShipId 0, wherever it stands) that is in the game or waiting in the clone bay and counts for victory,
     * noSlot crew left out (the hook marks them out of the game for the sum).
     */
    @JvmStatic
    fun capacityUsed(ship: Ship): Float {
        val queue = ship.clonebay?.queue
        var used = 0f
        for (crew in ship.sys.playerCrew) {
            if (crew.ownerShip !== ship) continue
            if (crew.health <= 0f && queue?.contains(crew) != true) continue
            if (noSlot(crew)) continue
            used += stat(crew, CrewStat.CREW_SLOTS, 1f)
        }
        return used
    }

    /**
     * Connects the crew-slot rules. CanFitCrew's race slots are StatBoostManager::CalculateStatDummy(CREW_SLOTS) of the
     * definition, which is the definition's crewSlots unless a boost changes CREW_SLOTS (Multiverse 5.5.1 has none);
     * its noSlot test reads the definition's noSlot only (CustomShips.cpp: "noSlot does not seem to be calculated
     * dynamically").
     */
    @JvmStatic
    fun install() {
        xyz.znix.xftl.hs.ship.HsShipState.crewCapacityUsed = ::capacityUsed
        xyz.znix.xftl.hs.ship.HsShipState.crewSlots = { race -> CustomCrewManager.definitionOrNull(race)?.crewSlots }
        xyz.znix.xftl.hs.ship.HsShipState.crewNoSlot = { race -> CustomCrewManager.definitionOrNull(race)?.noSlot == true }
        xyz.znix.xftl.hs.events.HsCrew.noSlot = ::noSlot
        // HS_ShuffleSortCrewList: ESSENTIAL of a race's crew member, 0 for a species without a definition
        xyz.znix.xftl.hs.events.HsCrew.essential = { crew -> stat(crew, CrewStat.ESSENTIAL, 0f) }
    }
}
