// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp
// (ShipManager::SystemWillReplace, CanFitSystem/CanFitSubsystem, the AddSystem hook with getTrueSystemMaxPower,
// ShipManager::RemoveSystem). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.SystemInstallConfiguration
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.systems.*

/**
 * Installing a system under Hyperspace: exclusivity groups (the global `<customSystems>` ones or a custom ship's
 * `<exclusivityOverride>`) make a new system replace an installed one, which hands over its level (at most the new
 * system's maximum); the per-ship system limits count from the custom ship definition. Only used while HS is active.
 */
object HsSystemInstall {
    /** ShipManager::SystemWillReplace: the installed system [newName] would replace, or null. */
    @JvmStatic
    fun willReplace(ship: Ship, newName: String): AbstractSystem? {
        val def = HsShips.def(ship.type.name)
        val newId = HsSystems.nameToId(newName)
        for (s in ship.systems) {
            val id = nativeSystemId(s)
            val exclusive = if (def.hasExclusivityOverride) overrideExclusive(def.exclusivityGroups, newId, id)
            else HsSystems.areExclusive(newId, id)
            if (exclusive) return s
        }
        if (!HS.cfg().dualMedical && !def.hasExclusivityOverride) {
            if (newName == "medbay") ship.clonebay?.let { return it }
            if (newName == "clonebay") ship.medbay?.let { return it }
        }
        return null
    }

    /** SystemExclusivityManager::AreSystemsExclusive over a custom ship's own groups. */
    private fun overrideExclusive(groups: List<List<String>>, a: Int, b: Int): Boolean {
        if (a == b) return false
        var ga = -1
        var gb = -1
        groups.forEachIndexed { i, g ->
            for (name in g) {
                val id = HsSystems.nameToId(name)
                if (id == a) ga = i
                if (id == b) gb = i
            }
        }
        return ga >= 0 && gb >= 0 && ga == gb
    }

    /**
     * ShipManager::CanFitSystem / CanFitSubsystem(systemId): a system that replaces another always fits, otherwise the
     * ship holds fewer than its systemLimit main systems (subsystemLimit subsystems).
     */
    @JvmStatic
    fun canFit(ship: Ship, slot: SystemInstallConfiguration): Boolean {
        val name = slot.system.name
        if (willReplace(ship, name) != null) return true
        val def = HsShips.def(ship.type.name)
        return if (slot.system.info?.isSubSystem == true) ship.subSystems.size < def.subsystemLimit
        else ship.mainSystems.size < def.systemLimit
    }

    /** getTrueSystemMaxPower: the ship blueprint's `max`, else the system blueprint's maxPower, at least 1. */
    private fun trueMax(slot: SystemInstallConfiguration): Int {
        val max = slot.spec.aiMaxPower
        if (max != null && max > 0) return max
        return slot.system.maxPower.takeIf { it > 0 } ?: 1
    }

    /**
     * The ShipManager::AddSystem hook for a bought system: the replaced system (if any) is removed and its level, capped at
     * the new system's maximum, becomes the new system's level; otherwise the new system starts at its start power.
     * Returns the installed system.
     */
    @JvmStatic
    fun install(ship: Ship, slot: SystemInstallConfiguration): AbstractSystem? {
        val replaced = willReplace(ship, slot.system.name)
        var level = -1
        if (replaced != null) {
            level = minOf(replaced.energyLevels, trueMax(slot))
            removeSystem(ship, replaced)
        }
        // CONSTRUCT_SHIP_SYSTEM waits for the power the native constructor gives (browserPowerNewSystem fires it)
        HsSystemState.deferConstruct { slot.room.setSystem(slot) }
        val system = slot.room.system ?: return null.also { HsSystemState.firePending() }
        // A new oxygen system starts with every room full: the rooms' levels live in the system, and AddSystem
        // drops the dummy one oxygenWithoutSystem kept (OxygenWithoutSystem.cpp; oracle hg-o2 o2c: 20% rooms read
        // 100 right after the call).
        if (system is xyz.znix.xftl.systems.Oxygen) ship.hsFillOxygen()
        HsSystemBox.systemAdded(ship)
        if (level > 0) {
            // UpgradeSystem(systemId, 1) until GetSystemPowerMax reaches the carried level
            if (system.energyLevels < level) system.energyLevels = level
            system.powerLimitChanged()
        }
        return system
    }

    /**
     * ShipManager::AddSystem for a type [ship] has already ( and HS's hooks check only that the blueprint
     * lists it): the shield system pushes the one Shields object into vSystemList again; any other type gets a second
     * object, made at the blueprint's start level (CONSTRUCT_SHIP_SYSTEM, powered as a new system), which systemKey
     * then names (GetSystem, the bar's box); the first stays in vSystemList with its level and power (oracle
     * sweep-world-ys2 y-dup: shields twice 2/2, sw_main 1/3 then the new 1/2, GetSystemPower 1, max 2). Returns the
     * system GetSystem now gives.
     */
    @JvmStatic
    fun duplicate(ship: Ship, existing: AbstractSystem): AbstractSystem {
        val st = xyz.znix.xftl.hs.lua.HsShipLuaState.of(ship)
        val order = xyz.znix.xftl.hs.lua.HsShipLuaState.systemList(ship) as MutableList<AbstractSystem>
        if (existing is Shields) {
            order += existing
            return existing
        }
        val room = existing.room ?: return existing
        val slot = existing.configuration
        val dup = slot.system.createInstance() ?: return existing
        st.ghosts += existing
        HsSystemState.deferConstruct {
            room.hsReplaceSystem(dup)
            dup.configuration = slot
            dup.energyLevels = slot.spec.startingPower
            dup.room = room
            dup.initialise(ship)
            ship.updateAvailableSystems()
        }
        HsSystemBox.systemAdded(ship)
        ship.browserPowerNewSystem(dup)
        ship.updateAvailablePower()
        return dup
    }

    /** The reactor bars the systems [duplicate] displaced still hold (they stay in vSystemList, powered). */
    @JvmStatic
    fun ghostPower(ship: Ship): Int {
        val ghosts = ship.hs?.lua?.ghosts ?: return 0
        if (ghosts.isEmpty()) return 0
        return ghosts.sumOf { (it as? MainSystem)?.powerSelected ?: 0 }
    }

    /**
     * ShipManager::RemoveSystem (HS CustomSystems.cpp): the system is powered down and leaves its room; the crew stop
     * working there, the clonebay's queue dies, mind control releases its crew. The player's weapons and drones go to the
     * cargo (Equipment::AddToCargo) and the system's slots empty; hacking drops its probe. Every system of the type goes
     * (the native loop runs while the ship still has one, for artillery). Returns whether it was removed.
     */
    @JvmStatic
    fun removeSystem(ship: Ship, system: AbstractSystem): Boolean {
        val removed = removeOne(ship, system)
        if (removed) for (other in ship.systems.filter { it.codename == system.codename && it !== system }) removeOne(ship, other)
        // the native loop also takes the other objects of the type a second AddSystem left in vSystemList
        if (removed) ship.hs?.lua?.ghosts?.removeAll { it.codename == system.codename }
        // oxygenWithoutSystem: the dummy oxygen system that takes over reads 0% until its first update (oracle hg-o2 o2a)
        if (removed && system is xyz.znix.xftl.systems.Oxygen && xyz.znix.xftl.hs.HS.cfg().oxygenWithoutSystem) ship.hsEmptyOxygenTotal()
        // gui->sysControl.CreateSystemBoxes() at the end, whichever ship lost the system (the player's boxes are rebuilt)
        if (removed) xyz.znix.xftl.hs.lua.LuaAdapters.inGame()?.player?.let { HsSystemBox.rebuild(it) }
        return removed
    }

    private fun removeOne(ship: Ship, system: AbstractSystem): Boolean {
        val room = system.room ?: return false
        if (room.system !== system) return false
        when (system) {
            is Weapons -> {
                if (ship.isPlayerShip) for (hp in ship.hardpoints) hp.weapon?.let { ship.addBlueprint(it.type, true, true) }
                // the native loop RemoveWeapon(slot) for every slot; a powered weapon is depowered first
                for (hp in ship.hardpoints) hp.weapon?.let { w ->
                    if (w.isPowered) system.setWeaponPower(w, false)
                    hp.weapon = null
                }
                ship.cargoUpdated()
            }
            is Drones -> {
                if (ship.isPlayerShip) for (info in system.drones) info?.let { ship.addBlueprint(it.type, true, true) }
                while (system.luaRemoveDrone(0) != null) continue
            }
            is Hacking -> system.stopHacking()
            else -> {}
        }
        if (system is MainSystem) {
            var guard = system.energyLevels + 4
            while (system.powerSelected > 0 && guard-- > 0) system.forceDecreasePower()
        }
        when (system) {
            is Clonebay -> system.queue.clear()
            is MindControl -> system.hsRelease()
            is Temporal -> system.stopTimeDilation()
            else -> {}
        }
        room.hsClearSystem()
        ship.updateAvailableSystems()
        ship.updateAvailablePower()
        return true
    }
}
