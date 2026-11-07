package xyz.znix.xftl.sector

import xyz.znix.xftl.Ship
import kotlin.math.abs

/**
 * WorldManager::CheckRequirements (1.6.14 0x6130e0, 1.6.9 0x5ede50), the original's rule for whether a choice's event
 * can be picked (CreateChoiceBox greys the choice when it fails). Hyperspace calls it first and adds its own rules
 * ([xyz.znix.xftl.hs.events.HsFlow.checkRequirements]).
 *
 * - An event that steals (`<item_modify steal="true">`, ResourceEvent::steal) always passes: it takes what there is.
 * - Otherwise the ship must hold the fuel, missiles, drone parts and scrap the event costs.
 * - An event whose name starts with REPAIR_STATION (the first 14 characters of LocationEvent::eventName, so the repair
 *   station's choices, which ProcessEvent names "REPAIR_STATION n") with hull damage d and a repair r (the sum of its
 *   `<damage amount>` sizes): while d < 11 and (d < 6 or r > 19), only r == 0 or (r < 10 and d > 0) passes. So with a
 *   whole hull only the "no repairs" choice is open, 1-5 damage also opens the 5 point repair, 6-10 damage every
 *   repair but the 20 point one.
 */
object NativeRequirements {
    fun check(ship: Ship, fuel: Int, missiles: Int, droneParts: Int, scrap: Int, event: Event): Boolean {
        if (event.itemsModifySteal) return true
        if (fuel < 0 && ship.fuelCount < -fuel) return false
        if (missiles < 0 && ship.missilesCount < -missiles) return false
        if (droneParts < 0 && ship.dronesCount < -droneParts) return false
        if (scrap < 0 && ship.scrap < -scrap) return false
        return repairStation(ship, event)
    }

    fun repairStation(ship: Ship, event: Event): Boolean {
        if (event.nativeName?.startsWith(REPAIR_STATION) != true) return true
        val damage = ship.maxHealth - ship.health
        val repair = event.hullDamage.sumOf { abs(it.amount) }
        if (damage < 11 && (damage < 6 || repair > 19)) return repair == 0 || (repair < 10 && damage > 0)
        return true
    }

    private const val REPAIR_STATION = "REPAIR_STATION"
}
