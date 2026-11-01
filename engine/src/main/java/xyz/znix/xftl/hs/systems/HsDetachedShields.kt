// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (the
// ShipManager::CreateSystems hook with shieldWithoutSystem), with FTL 1.6.9's ShipManager::CreateSystems and
// AddSystem (which takes the existing Shields object for id 0). FTL-Hyperspace is GPL-3.0-or-later; this
// engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInstallConfiguration
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.systems.SystemBlueprint

/**
 * ShipManager::shieldSystem while the ship has no shield system installed: CreateSystems makes the Shields object when the
 * blueprint lists shields (an unbought slot: `Shields(location[0])`) and, with HS's `shieldWithoutSystem`, also without
 * (`Shields(-1)`). It is not in vSystemList (systemKey[0] stays -1) and sits at level 0, unpowered (oracle sweep-world-y1
 * y-genshield: 'Shields *', room -1, power 0, super 0); CONSTRUCT_SHIP_SYSTEM runs for it before the other systems.
 * Buying the shields later (AddSystem(0)) installs this same object.
 */
object HsDetachedShields {
    private val detached = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, Shields>())

    /** CreateSystems: the Shields object of [ship] when it gets no shield system, or null. */
    @JvmStatic
    fun create(ship: Ship): Shields? {
        detached.remove(ship)
        if (!HS.active || ship.shields != null) return null
        val slot = ship.systemSlots.firstOrNull { it.system.name == "shields" }
        if (slot == null && !HS.cfg().shieldWithoutSystem) return null
        val blueprint = slot?.system ?: (ship.sys.blueprintManager.getOrNull("shields") as? SystemBlueprint) ?: return null
        val shields = blueprint.createInstance() as? Shields ?: return null
        shields.energyLevels = 0
        if (slot != null) shields.configuration = slot
        val st = HsSystemState(shields)
        shields.hsState = st
        st.detachedShip = ship
        st.roomId = slot?.room?.id ?: -1
        HsSysExt.of(shields).xOffset = HsSysExt.defaultOffset(0)
        detached[ship] = shields
        return shields
    }

    /** ShipManager::shieldSystem when [ship] has no installed shields. */
    @JvmStatic
    fun of(ship: Ship): Shields? = if (ship.shields != null) null else detached[ship]

    /** AddSystem(0): the Shields object CreateSystems made, for [config]'s room, or null (a new one is made). */
    @JvmStatic
    fun take(ship: Ship, config: SystemInstallConfiguration): Shields? {
        if (!HS.active || config.system.name != "shields") return null
        val shields = detached.remove(ship) ?: return null
        shields.hsState?.let { it.detachedShip = null; it.roomId = null; it.adopted = true }
        return shields
    }
}
