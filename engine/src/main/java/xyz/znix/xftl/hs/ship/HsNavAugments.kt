package xyz.znix.xftl.hs.ship

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.sector.Beacon

/**
 * Hyperspace's built-in augment rules of src/game/Ship/Augments.cpp that are ship or star map rules:
 *
 * - NANOBOT_DEFENSE_SYSTEM (ShipManager::UpdateCrewmembers): every intruder aboard that is not mind controlled and not
 *   dying loses `value / 500` health per speed-factor tick.
 * - FTL_JUMPER_GOOD (StarMap::MouseMove, StarMap::OnRender, ShipManager::JumpLeave): any beacon of the sector is a jump
 *   target while the fuel lasts; a jump past the neighbours costs `floor(value)` fuel (every jump of the path when the
 *   value is 0), a visited beacon one with FTL_JUMPER; the map shows the cost as "-N".
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/Augments.cpp
object HsNavAugments {
    const val NANOBOTS = "NANOBOT_DEFENSE_SYSTEM"
    const val JUMPER_GOOD = "FTL_JUMPER_GOOD"

    /** UpdateCrewmembers hook: [dt] seconds of the ship's loop (speed factor = 16 * dt, as the engine's timers). */
    @JvmStatic
    fun nanobots(ship: Ship, dt: Float) {
        if (!HS.active || dt <= 0f) return
        val value = ship.getAugmentValue(NANOBOTS)
        if (value == 0f) return
        val amount = 16f * dt * (value / 500f)
        for (c in ArrayList(ship.crew)) {
            if (c !is LivingCrew || c.mode != AbstractCrew.SlotType.INTRUDER) continue
            if (c.mindControlledBy != null || c.health <= 0f) continue
            (c as? xyz.znix.xftl.hs.crew.HsCrewMember)?.directModifyHealth(-amount)
        }
    }

    /** `fuelReq`: the fuel the hovered beacon costs (a static in HS: the last hover counts at the jump). */
    @JvmStatic
    var fuelReq = 0
        private set

    /**
     * StarMap::MouseMove hook. Returns true when [hovered] becomes the jump target (`potentialLoc`) because of the
     * augment; the vanilla rule (a neighbour, a visited beacon with FTL_JUMPER) still applies besides it.
     */
    @JvmStatic
    fun mouseMove(ship: Ship, current: Beacon, hovered: Beacon?, beacons: List<Beacon>): Boolean {
        if (!HS.active || !ship.hasAugment(JUMPER_GOOD)) return false
        val path = path(current, hovered, beacons)
        val value = kotlin.math.floor(ship.getAugmentValue(JUMPER_GOOD)).toInt()
        fuelReq = when {
            value == 0 -> path.size - 1
            path.size > 2 -> value
            else -> 0
        }
        if (hovered != null && hovered.visited && ship.hasAugment(xyz.znix.xftl.augments.AugmentBlueprint.ADVANCED_FTL_NAVIGATION)) fuelReq = 1
        return hovered != null && ship.fuelCount >= fuelReq && hovered !== current
    }

    /** StarMap::Dijkstra(current, hovered): the beacons from [from] to [to] (both included), fewest jumps; empty without one. */
    private fun path(from: Beacon, to: Beacon?, beacons: List<Beacon>): List<Beacon> {
        to ?: return emptyList()
        val prev = HashMap<Beacon, Beacon?>()
        prev[from] = null
        val queue = ArrayDeque<Beacon>()
        queue += from
        while (queue.isNotEmpty()) {
            val b = queue.removeFirst()
            if (b === to) break
            for (n in b.neighbours) if (n in beacons && n !in prev) { prev[n] = b; queue += n }
        }
        if (to !in prev) return emptyList()
        val out = ArrayList<Beacon>()
        var b: Beacon? = to
        while (b != null) { out += b; b = prev[b] }
        out.reverse()
        return out
    }

    /** StarMap::OnRender hook: the "-N" fuel text at (173, 83) of the map, red when the fuel does not reach. */
    @JvmStatic
    fun costText(ship: Ship): String? = if (HS.active && ship.hasAugment(JUMPER_GOOD) && fuelReq > 0) "-$fuelReq" else null

    /** ShipManager::JumpLeave hook (player): a jump the augment allowed costs `fuelReq` instead of one fuel. */
    @JvmStatic
    fun jumpLeave(ship: Ship, oldFuel: Int) {
        if (!HS.active || !ship.hasAugment(JUMPER_GOOD) || fuelReq <= 1) return
        ship.fuelCount = oldFuel - fuelReq
        fuelReq = 0
    }
}
