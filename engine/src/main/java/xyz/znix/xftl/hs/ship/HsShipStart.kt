package xyz.znix.xftl.hs.ship

import ftl.browser.NativeRandom
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.BlueprintManager
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.random.Random

/**
 * A modded ship's starting weapons and drones, by the original's rules (vanilla data never reaches the differences,
 * so vanilla play keeps the engine's old path; [xyz.znix.xftl.hs.HS.modded] selects this one):
 *
 * - ShipManager::OnInit (0x4b1bf0) adds the blueprint's equipment in FUN_004b12c0 after CreateSystems: the weapons
 *   only when the ship has a weapon system, the drones only when it has a drone system (a Multiverse ship such as the
 *   Guardian C lists drones but starts without the system: it starts with none). Each name goes through
 *   BlueprintManager::GetWeaponBlueprint 0x60a9a0 / GetDroneBlueprint 0x60afc0: a blueprint list picks one member
 *   (`random32() % size`, one level, then looked up by name); an unknown name is the inert default blueprint (an
 *   unknown drone is then not added).
 * - The weapon loop stops at the blueprint's weapon count, the drone loop at its drone count (`<droneList count>`).
 *
 * In the original the hangar's ShipManager makes these draws when the ship is shown (ShipBuilder::SwitchShip), from
 * the global stream; a run started without the hangar (diagnostics) draws them here, before the crew and the sector
 * tree, from [NativeRandom].
 */
object HsShipStart {
    /** Picked names: a weapon of null = the empty default blueprint; drone names are real drones only. */
    class Equipment(@JvmField val weapons: List<String?>, @JvmField val drones: List<String>)

    private fun startsWith(bp: ShipBlueprint, system: String): Boolean =
        bp.systems.any { it.systemName == system && it.availableByDefault }

    /** GetWeaponBlueprint/GetDroneBlueprint list handling: one level, `random32() % n`. */
    private fun pick(manager: BlueprintManager, name: String, rand: Random): String? {
        val ref = manager.getOrNull(name)
        if (ref is BlueprintList) {
            if (ref.names.isEmpty()) return null
            return ref.names[rand.nextInt(ref.names.size)]
        }
        return name
    }

    /** The draws of FUN_004b12c0 for [bp] as it starts (its default systems), in its order (weapons, then drones). */
    @JvmStatic
    @JvmOverloads
    fun resolve(bp: ShipBlueprint, manager: BlueprintManager, rand: Random = NativeRandom): Equipment =
        resolve(bp, manager, startsWith(bp, "weapons"), startsWith(bp, "drones"), rand)

    @JvmStatic
    fun resolve(bp: ShipBlueprint, manager: BlueprintManager, hasWeapons: Boolean, hasDrones: Boolean, rand: Random): Equipment {
        val weapons = ArrayList<String?>()
        if (hasWeapons) {
            for (name in bp.initialWeapons) {
                val picked = pick(manager, name, rand)
                weapons += picked?.takeIf { manager.getOrNull(it) is AbstractWeaponBlueprint }
            }
        }
        val drones = ArrayList<String>()
        if (hasDrones) {
            val names = bp.initialDrones
            val count = bp.droneListCount ?: names.size
            for (i in 0 until minOf(count, names.size)) {
                val picked = pick(manager, names[i], rand) ?: continue
                if (manager.getOrNull(picked) is DroneBlueprint) drones += picked
            }
        }
        return Equipment(weapons, drones)
    }

    /**
     * The race of a blueprint `<crewCount class>` entry: a blueprint list picks one member (`random32() % size`, the
     * BlueprintManager::GetCrewBlueprint hook of CustomCrew.cpp, one level), anything else is itself. Used by the run
     * start and the hangar; the statcrew workstream owns the race itself.
     */
    @JvmStatic
    fun crewRace(manager: BlueprintManager, race: String, rand: Random): String {
        val ref = manager.getOrNull(race)
        if (ref is BlueprintList && ref.names.isNotEmpty()) return ref.names[rand.nextInt(ref.names.size)]
        return race
    }

    /**
     * Hyperspace's ProjectileFactory constructor hook (CustomWeapons.cpp): a weapon made from the inert default
     * blueprint (type -1: GetWeaponBlueprint found no such name) is a Basic Laser (LASER_BURST_1) instead. Every weapon
     * a ship gets goes through it: its starting weapons and an event's weaponOverride picks alike. Null outside
     * Hyperspace (the original keeps the empty weapon; no vanilla data names a missing weapon).
     */
    @JvmStatic
    fun missingWeapon(manager: BlueprintManager): AbstractWeaponBlueprint? =
        if (xyz.znix.xftl.hs.HS.active) manager.getOrNull("LASER_BURST_1") as? AbstractWeaponBlueprint else null

    /** Ship.loadDefaultContents in modded sessions: install [preset] (or resolve now) into the ship's systems. */
    @JvmStatic
    fun loadEquipment(ship: Ship, preset: Equipment?) {
        val manager = ship.sys.blueprintManager
        val eq = preset ?: resolve(ship.type, manager, ship.weapons != null, ship.drones != null, NativeRandom)
        if (ship.weapons != null) {
            for ((index, name) in eq.weapons.withIndex()) {
                if (index >= ship.hardpoints.size) break
                val weapon = (if (name == null) null else manager.getOrNull(name) as? AbstractWeaponBlueprint)
                    ?: missingWeapon(manager) ?: manager.getOrNull("")
                if (weapon !is AbstractWeaponBlueprint) continue
                ship.hardpoints[index].weapon = weapon.buildInstance(ship)
            }
        }
        val drones = ship.drones ?: return
        for ((idx, name) in eq.drones.withIndex()) {
            val drone = manager.getOrNull(name) as? DroneBlueprint ?: continue
            // As the engine's vanilla path: a fixed drone list gets the slots it needs.
            if (drones.drones.size <= idx) drones.drones.add(null)
            drones.drones[idx] = Drones.DroneInfo(drone, null)
        }
    }
}
