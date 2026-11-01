package xyz.znix.xftl.hs.ship

import ftl.browser.NativeRandom
import xyz.znix.xftl.BlueprintList
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint

/**
 * The static ShipGenerator functions Hyperspace exposes to Lua (`Hyperspace.ShipGenerator`), on any ship. They follow
 * FTL 1.6.9 (GetPossibleWeaponList 0x5fb240, GetPossibleDroneList 0x5fb6c0, GetPossibleCrewList, GetPossibleSystemUpgrades
 * 0x5fc060, GenerateSystemMaxes 0x5fbe90), the rules [xyz.znix.xftl.shipgen.ShipGenerator] uses while it builds an enemy;
 * random draws (a list entry that is itself a list, crew names, the maxima) come from the native stream in that order.
 */
object HsShipGeneratorLua {
    private const val DEFAULT_SLOTS = 4
    private const val MAX_CREW = 10

    private fun names(game: InGameState, list: String): List<String> =
        (game.blueprintManager.getOrNull(list) as? BlueprintList)?.names ?: emptyList()

    /** GetWeaponBlueprint / GetDroneBlueprint: a list name picks a random member. */
    private fun resolve(game: InGameState, name: String): Any? {
        val ref = game.blueprintManager.getOrNull(name)
        return if (ref is BlueprintList) game.blueprintManager.getOrNull(ref.names[NativeRandom.nextInt(ref.names.size)]) else ref
    }

    /** GetWeaponFlags: bit 0 hull damage, bit 1 laser-like (a laser, or a missile piercing at most 3). */
    private fun provides(w: AbstractWeaponBlueprint): Int {
        val damage = if (w.damage != 0) 1 else 0
        return when (w) {
            is LaserBlueprint -> 2 or damage
            is MissileBlueprint -> (if (w.shieldPiercing < 4) 2 else 0) or damage
            else -> damage
        }
    }

    /**
     * GetPossibleWeaponList(ship, list, scrap, flags): the list's weapons that fit the weapon system's spare power
     * (more than a quarter of it, never the whole system unless that is one bar) and give a missing capability
     * (flags 0: any). Empty without a weapon system, spare power or a free slot. The scrap argument is unused.
     */
    fun possibleWeapons(game: InGameState, ship: Ship, list: String, flags: Int): List<AbstractWeaponBlueprint> {
        val system = ship.weapons ?: return emptyList()
        val powerMax = system.energyLevels
        val current = ship.hardpoints.mapNotNull { it.weapon }
        val remaining = powerMax - current.sumOf { it.type.power }
        val slots = minOf(ship.weaponSlots ?: DEFAULT_SLOTS, ship.hardpoints.size)
        if (remaining < 1 || current.size >= slots) return emptyList()
        val out = ArrayList<AbstractWeaponBlueprint>()
        for (name in names(game, list)) {
            val w = resolve(game, name) as? AbstractWeaponBlueprint ?: continue
            if (powerMax >= 3 && current.isEmpty() && w.power == 1) continue
            if ((flags and provides(w)) == 0 && flags != 0) continue
            if ((remaining shr 2) < w.power && w.power <= remaining && (powerMax < 2 || w.power != powerMax)) out += w
        }
        return out
    }

    /**
     * GetPossibleDroneList(ship, list, scrap, flags, repeat): the list's drones that fit the drone system's spare power
     * (not the whole system from four bars); while flags are set only combat drones, and no drone the ship has unless
     * repeat.
     */
    fun possibleDrones(game: InGameState, ship: Ship, list: String, flags: Int, repeat: Boolean): List<DroneBlueprint> {
        val system = ship.drones ?: return emptyList()
        val powerMax = system.energyLevels
        val current = system.drones.filterNotNull()
        val remaining = powerMax - current.sumOf { it.type.power }
        if (remaining < 1 || current.size >= (ship.droneSlots ?: DEFAULT_SLOTS)) return emptyList()
        val out = ArrayList<DroneBlueprint>()
        for (name in names(game, list)) {
            val d = resolve(game, name) as? DroneBlueprint ?: continue
            val passes = (d.type == DroneBlueprint.DroneType.COMBAT && (flags and 3) != 0) || (if (flags != 0) 1 else 0) <= (if (repeat) 1 else 0)
            if (!passes) continue
            if (!repeat && current.any { it.type == d }) continue
            if (d.power == 0) continue
            if (d.power <= remaining && (powerMax < 4 || d.power != powerMax)) out += d
        }
        return out
    }

    /**
     * GetPossibleCrewList(ship, race, ...): nothing for a full (ten) or automated crew; "random" one random race;
     * else GetCrewBlueprint(race) (an unknown race is human; a list gives every member), each drawing a name.
     */
    fun possibleCrew(game: InGameState, ship: Ship, race: String): List<CrewBlueprint> {
        if (ship.crew.size >= MAX_CREW || ship.isAutomated) return emptyList()
        if (race == "random") return listOf(game.lootPool.getCrewOrRandom(NativeRandom, "random"))
        val bm = game.blueprintManager
        val ref = bm.getOrNull(race)?.takeIf { it is CrewBlueprint || it is BlueprintList } ?: bm.getOrNull("human")
        val list = if (ref is BlueprintList) ref.names.mapNotNull { bm.getOrNull(it) as? CrewBlueprint } else listOfNotNull(ref as? CrewBlueprint)
        for (unused in list) game.crewFactory.newName()
        return list
    }

    /**
     * GetPossibleSystemUpgrades(ship, maxes, scrap, type): every installed system (each artillery) below maxes[id];
     * type 1 only shields, engines and cloaking, type 2 only weapons, drones, teleporter and artillery.
     */
    fun possibleUpgrades(ship: Ship, maxes: List<Int>, type: Int): List<Int> {
        val out = ArrayList<Int>()
        for (id in 0..15) {
            val take = when (type) {
                1 -> id == 0 || id == 1 || id == 10
                2 -> id == 3 || id == 4 || id == 9 || id == 11
                else -> true
            }
            if (!take) continue
            val systems = if (id == 11) ship.artillery else listOfNotNull(xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, id))
            for (s in systems) if (s.energyLevels < (maxes.getOrNull(id) ?: 0)) out += id
        }
        return out
    }

    /**
     * UpgradeSystem(ship, maxes, id) (1.6.9 0x5f92b0; the engine's enemy generator has the 1.6.14 twin): artillery picks
     * one of its systems below maxes[11] with `random32() % n` (installing one first when it has none); a missing
     * system is installed through AddSystem and a reactor system's level is added to the reactor; an installed one
     * gets a reactor bar (reactor systems) and ShipSystem::UpgradeSystem(1). Always true.
     */
    fun upgradeSystem(ship: Ship, maxes: List<Int>, id: Int): Boolean {
        var system = xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, id)
        if (id == 11) {
            if (ship.artillery.isEmpty()) xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, 11)
            val open = ship.artillery.filter { it.energyLevels < (maxes.getOrNull(11) ?: 0) }
            // native: random32() % 0 when none is below the maximum (an integer division fault); nothing here
            if (open.isEmpty()) return true
            system = open[NativeRandom.next31() % open.size]
        }
        if (system == null) {
            val added = xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, id) ?: return true
            if (added is xyz.znix.xftl.systems.MainSystem) ship.purchasedReactorPower += added.energyLevels
        } else {
            if (system is xyz.znix.xftl.systems.MainSystem) ship.purchasedReactorPower++
            // ShipSystem::UpgradeSystem(1) (1.6.9 0x615b80)
            val destroyed = system.broken
            system.energyLevels = maxOf(0, system.energyLevels + 1)
            if (destroyed) system.damagedEnergyLevels = system.energyLevels
            else system.damagedEnergyLevels = system.damagedEnergyLevels.coerceIn(0, system.energyLevels)
            system.powerLimitChanged()
        }
        ship.updateAvailablePower()
        return true
    }

    /**
     * ShipManager::AddInitialCrew(vector<CrewBlueprint>&) (FTL 1.6.9 0x4a3610; an empty vector falls back to the
     * blueprint's crew, which Lua cannot reach here). Member i goes to the room of the i-th system of a priority list,
     * not filtered by the systems the ship has: the player's pilot, engines, weapons, shields, oxygen, sensors, doors,
     * cloaking, medbay, drones, hacking, teleporter, artillery; an enemy's pilot, two artillery, shields/weapons/engines
     * (engines/weapons/shields on hard), then the same tail. A missing system gives room -1 (the pilot's room), an
     * artillery entry takes the next artillery room (the computed room when none is left), and a member beyond the
     * list a random room (`random32() % room count`, drawn first). Each one is ShipManager::AddCrewMemberFromBlueprint
     * (0x49c4f0) with slot i and init: the slot-th own non-drone crew member (iShipId 0) of vCrewList is replaced in
     * place (CrewEmptySlot, SetOutOfGame), so the list only grows when there is no such member (oracle wp5-ship
     * AddInitialCrew: two members on the player ship, none added).
     */
    fun addInitialCrew(ship: Ship, races: List<String>) {
        val game = ship.sys
        val player = ship === game.player
        val tail = intArrayOf(2, 7, 8, 10, 5, 4, 15, 9, 11)
        val head = when {
            player -> intArrayOf(6, 1, 3, 0)
            game.difficulty == Difficulty.HARD -> intArrayOf(6, 11, 11, 1, 3, 0)
            else -> intArrayOf(6, 11, 11, 0, 3, 1)
        }
        val priority = head + tail
        val artillery = ship.artillery.toMutableList()
        for ((i, race) in races.withIndex()) {
            var roomId: Int
            if (i < priority.size) {
                val id = priority[i]
                val sys = xyz.znix.xftl.hs.lua.LuaShipApi.system(ship, id)
                roomId = sys?.room?.id ?: -1
                if (id == 11 && artillery.isNotEmpty()) roomId = artillery.removeAt(0).room?.id ?: roomId
            } else {
                roomId = NativeRandom.next31() % ship.rooms.size
            }
            // AddCrewMemberFromBlueprint: room -1 is the pilot's room
            if (roomId == -1) roomId = ship.piloting?.room?.id ?: -1
            val slot = ship.crew.filter { it is xyz.znix.xftl.crew.LivingCrew && it.ownerShip === game.player }.getOrNull(i)
            val made = xyz.znix.xftl.hs.crew.HsCrew.addCrewMemberFromString(ship, "", race, false, roomId, true, true)
            if (slot != null) {
                ship.crew.remove(made)
                val at = ship.crew.indexOf(slot)
                ship.clonebay?.queue?.remove(slot)
                slot.removeFromShip()
                ship.crew.add(at.coerceIn(0, ship.crew.size), made)
                ship.updateCrewReservedSlots()
                game.updatePlayerCrew()
            }
        }
    }

    /**
     * GenerateSystemMaxes(blueprint, level): per system id 0-15 the blueprint lists, `random32() % m + level / 8 *
     * (max - start) + start` clamped to [start, max] (m 1 below level 0, else 2, 3 above level 1 when not easy);
     * 0 for a system it does not list.
     */
    fun generateSystemMaxes(game: InGameState, bp: xyz.znix.xftl.game.ShipBlueprint, level: Int): List<Int> {
        val templates = HashMap<Int, xyz.znix.xftl.game.ShipBlueprint.ParsedSystem>()
        for (p in bp.systems) {
            val id = xyz.znix.xftl.hs.systems.HsSystems.nameToId(p.systemName)
            if (id in 0..15) templates[id] = p
        }
        val easy = game.difficulty == Difficulty.EASY
        return (0..15).map { id ->
            val t = templates[id] ?: return@map 0
            val start = t.startingPower
            val max = t.aiMaxPower ?: -1
            val m = if (level < 0) 1 else if (easy) 2 else if (level > 1) 3 else 2
            val v = NativeRandom.nextInt(m) + (level.toFloat() * 0.125f * (max - start).toFloat() + start.toFloat()).toInt()
            minOf(max, maxOf(v, start))
        }
    }
}
