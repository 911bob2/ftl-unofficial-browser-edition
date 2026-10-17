package xyz.znix.xftl.crew

import xyz.znix.xftl.Ship

/**
 * ShipManager::ContainsMindControlledEnemy (0x49b570), used by WorldManager::HostileEnvironment (0x6135f0).
 *
 * The native hostile flag is "dangerous environment || ship+0x7f0 != 0 || this". +0x7f0 counts the
 * crew that CrewMember::GetIntruder (0x477b90) reports, and that call flips under mind control, so an
 * enemy crewmember that the player mind controls is not counted there. This test keeps the ship hostile
 * (upgrades and equipment locked, combat music) while such a crewmember is aboard: a mind controlled
 * crewmember whose owner is not this ship.
 */
fun Ship.containsMindControlledEnemy(): Boolean {
    return crew.any { it is LivingCrew && it.mindControlledBy != null && it.ownerShip != this }
}
