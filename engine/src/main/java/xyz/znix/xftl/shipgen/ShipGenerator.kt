package xyz.znix.xftl.shipgen

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewCrystal
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.crew.NativeCrewFactory
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.sector.EventManager
import xyz.znix.xftl.sector.IEvent
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint
import ftl.browser.NativeRandom
import java.nio.ByteBuffer
import java.util.*
import kotlin.math.max
import kotlin.math.min

/**
 * Enemy ship generation, a port of ShipGenerator::CreateShip (FTLGame.exe 1.6.14, 0x620fd0) and the parts of
 * WorldManager::CreateShip (0x6149c0) around it. Every random draw comes from [NativeRandom], in the native order,
 * so the same ship-event seed gives the same ship. See docs/ENEMY_GENERATION.md.
 */
class ShipGenerator(val df: Datafile, val bp: BlueprintManager) {
    /**
     * @param sector WorldManager+0x834, the world level (StarMap::worldLevel): 0 in the first sector. It is the
     * level argument of CreateShip; easy subtracts one inside.
     * @param seed the native ship-event seed (ShipEvent+0x428): CreateShip reseeds the global stream from it when it
     * is not zero. Null or 0 continues the global stream.
     */
    fun buildShip(sys: InGameState, spec: EnemyShipSpec, sector: Int, difficulty: Difficulty, seed: Int? = null): Ship {
        val rand = NativeRandom
        val easy = difficulty == Difficulty.EASY

        // CreateShip: the level is the argument minus one on easy (uVar9, -1 in easy's first sector); most budgets
        // use it clamped at zero (uVar13).
        val level = sector + difficulty.sectorOffset
        val levelClamped = level.coerceAtLeast(0)

        // WorldManager::CreateShip builds the CompleteShip before calling CreateShip. Its ShipAI constructor
        // (0x625070) draws a surrender default 1..5 and an escape default; only the first can matter below.
        val initialSurrender = rand.nextInt(5) + 1
        rand.nextInt(5)

        // CreateShip reseeds from the ship event when the event carries a seed.
        val reseed = seed != null && seed != 0 && !spec.fixedBlueprint
        if (reseed)
            NativeRandom.seed(seed!!)

        // BlueprintManager::GetShipBlueprint (0x60ac50)
        val shipBlueprint = try {
            selectShipBlueprint(spec.autoBlueprint, levelClamped).real
        } catch (invalid: NoSuchElementException) {
            throw IllegalStateException("Empty ship blueprint list in encounter " + spec.name, invalid)
        }

        val ship = Ship(shipBlueprint, sys, null, spec, null)
        ship.generationSeed = if (reseed) seed else null
        ship.eventSeed = if (seed != null && seed != 0) seed else null

        // The Zoltan shield is dropped from the blueprint before OnInit on easy's first sector.
        ship.loadDefaultContents(omitAugment = if (!spec.fixedBlueprint && easy && level == -1) "ENERGY_SHIELD" else null)

        // ShipManager::Restart (0x4b1760) builds the ship from the same stream: Ship::OnInit, the starting systems,
        // then the blueprint's weapons and drones (Equipment::AddDrone 0x53d500 makes a crew drone's body now).
        ShipConstructionDraws.onInit(ship)
        ShipConstructionDraws.startingSystems(ship)
        repeat(ship.hardpoints.count { it.weapon != null }) { ShipConstructionDraws.weapon() }
        ship.drones?.drones?.forEach { info -> info?.let { ShipConstructionDraws.drone(sys, it.type) } }
        // The fires come after the blueprint crew, which only a ship that skips CreateShip has (below).
        if (!spec.fixedBlueprint)
            ShipConstructionDraws.fires(ship)

        // ShipManager::AddSystem (0x4a8370) never starts a system below one bar, and caps it at the system's maximum.
        for (slot in ship.systemSlots) {
            val system = slot.room.system ?: continue
            if (!slot.isInstalled) continue
            system.energyLevels = system.energyLevels.coerceAtLeast(1).coerceAtMost(slot.system.maxPower)
        }
        // CreateSystems (0x4a96f0) builds them in the blueprint's order, each powered as it is made.
        for (system in ship.mainSystems.sortedBy { xyz.znix.xftl.ai.systemOrderKey(it) })
            ship.browserPowerAtCreation(system)

        // The blueprint's hull plus the level (not clamped), 20 at most.
        if (!spec.fixedBlueprint) {
            ship.browserMaxHealth = nativeMaxHull(ship.type.maxHealth, level)
            ship.health = ship.maxHealth
        }

        if (!spec.fixedBlueprint) {
            Build(sys, ship, spec, level, levelClamped, difficulty).generate()
        } else {
            // A direct blueprint skips CreateShip: WorldManager::CreateShip only runs OnInit, whose crew comes
            // from the blueprint's crewCount classes (ShipManager 0x4ae2f0 with an empty list).
            val picked = ArrayList<CrewPick>()
            for (crewSpec in ship.type.initialCrew) {
                repeat(crewSpec.amount) { picked += CrewPick(crewBlueprintFor(sys, crewSpec.race), null) }
            }
            spawnCrew(sys, ship, difficulty, picked)
            ShipConstructionDraws.fires(ship)
            ship.instantPowerShields()
        }
        ship.crewAI.initialCrewCount = ship.crew.size

        // WorldManager::CreateShip: the escape threshold is rolled first (ShipAI 0x6255d0), then the timer, then the
        // surrender threshold (0x6255b0); both get worldLevel/2 added, and the earlier one is lowered when they
        // collide. The native thresholds exist for every ship; the engine's AI only acts on those whose event exists.
        val half = sector / 2
        var escape = (spec.escapeHealth?.pick(rand) ?: 0) + half
        if (escape == initialSurrender)
            escape -= 1
        val surrender = (spec.surrenderHealth?.pick(rand) ?: 0) + half
        if (escape == surrender)
            escape -= 1
        ship.escapeHealth = if (spec.escape != null) escape else 0
        ship.surrenderHealth = if (spec.surrender != null) surrender else 0

        return ship
    }

    /**
     * BlueprintManager::GetShipBlueprint (0x60ac50). A direct blueprint is returned as is; a list collects its
     * members (nested lists pick recursively, unconditionally, and may draw), keeps those whose
     * minSector <= level <= maxSector, and picks `list[random32() % n]`. When none passes the pick is over all members.
     */
    private fun selectShipBlueprint(ref: IBlueprint, level: Int): LazyShipBlueprint {
        if (ref is LazyShipBlueprint)
            return ref
        if (ref !is BlueprintList)
            throw NoSuchElementException("Not a ship blueprint or list: $ref")

        val entries = ref.names
        if (entries.isEmpty())
            throw NoSuchElementException("Empty ship blueprint list")

        val candidates = ArrayList<LazyShipBlueprint>()
        for (name in entries) {
            val member = bp.getOrNull(name) ?: continue
            val ship = selectShipBlueprint(member, level)
            if (ship.minSector <= level && level <= ship.maxSector)
                candidates += ship
        }

        if (candidates.isEmpty()) {
            val any = bp.getOrNull(entries[NativeRandom.nextInt(entries.size)])
                ?: throw NoSuchElementException("Missing ship blueprint in list")
            return selectFlat(any)
        }
        return candidates[NativeRandom.nextInt(candidates.size)]
    }

    // The fallback pick resolves with the level filter off (-1): lists inside pick unconditionally.
    private fun selectFlat(ref: IBlueprint): LazyShipBlueprint {
        if (ref is LazyShipBlueprint)
            return ref
        val list = ref as? BlueprintList ?: throw NoSuchElementException("Not a ship blueprint or list: $ref")
        val members = list.names.mapNotNull { bp.getOrNull(it) }.map { selectFlat(it) }
        return members[NativeRandom.nextInt(members.size)]
    }

    /**
     * The CreateShip body after OnInit: system maxima, optional systems, the three upgrade budgets, scripted and
     * procedural weapons and drones, crew, drone parts.
     */
    private inner class Build(
        val sys: InGameState, val ship: Ship, val spec: EnemyShipSpec,
        val level: Int, val levelClamped: Int, val difficulty: Difficulty
    ) {
        val rand = NativeRandom
        val easy = difficulty == Difficulty.EASY
        val hard = difficulty == Difficulty.HARD

        // The blueprint's system templates by native system id; a system listed twice keeps its last element
        // (the original stores them in a map keyed by id).
        val templates = arrayOfNulls<ShipBlueprint.ParsedSystem>(SYSTEM_COUNT)

        // Weapon/drone slot counts default to four when the blueprint has none (ShipBlueprint 0x760db0).
        val weaponSlots: Int = min(ship.weaponSlots ?: DEFAULT_SLOTS, ship.hardpoints.size)
        val droneSlots: Int = ship.droneSlots ?: DEFAULT_SLOTS

        lateinit var maxes: IntArray

        init {
            for (parsed in ship.type.systems) {
                val id = SYSTEM_IDS[parsed.systemName] ?: continue
                templates[id] = parsed
            }
        }

        fun generate() {
            // A ship with a drone list but fewer than four slots gets the default four.
            ship.drones?.let { drones ->
                while (drones.drones.size < droneSlots)
                    drones.drones.add(null)
            }

            // GenerateSystemMaxes (0x61f640)
            maxes = systemMaxes()

            // ModifyScrapCount(pool - current): (int)(25 * (level+1)^1.8 + 150) with the exe's 1.8f as a double.
            ship.scrap = (Math.pow((levelClamped + 1).toDouble(), 1.7999999523162842) * 25.0 + 150.0).toInt()

            // Budgets: offensive level+1, defensive level+2 (signed), general level/3+1 (+1 on hard).
            var offensive = levelClamped + 1
            var defensive = level + 2
            var general = levelClamped / 3 + 1 + (if (hard) 1 else 0)

            // Optional systems, in system-id order, for each id the blueprint knows and the ship lacks:
            // random32() % 10 < level + 1/2/3 installs it.
            val chance = levelClamped + (if (easy) 1 else if (hard) 3 else 2)
            for (id in 0 until SYSTEM_COUNT) {
                if (hasSystem(id) || maxes[id] <= 0)
                    continue
                if (rand.nextInt(10) < chance) {
                    upgrade(id)
                    if (id == 3 || id == 4 || id == 9)
                        offensive--
                    else
                        general -= if (hard) 1 else 2
                }
            }

            // Offensive (weapons, drones, teleporter, artillery), then defensive (shields, engines, cloaking),
            // then everything with the leftovers; each pick is list[random32() % n] over a rebuilt list.
            var list = candidates(OFFENSIVE)
            while (list.isNotEmpty() && offensive >= 1) {
                upgrade(list[rand.nextInt(list.size)])
                list = candidates(OFFENSIVE)
                offensive--
            }
            list = candidates(DEFENSIVE)
            while (list.isNotEmpty() && defensive > 0) {
                upgrade(list[rand.nextInt(list.size)])
                list = candidates(DEFENSIVE)
                defensive--
            }
            general += offensive + defensive
            list = candidates(ALL_SYSTEMS)
            while (list.isNotEmpty() && general > 0) {
                upgrade(list[rand.nextInt(list.size)])
                list = candidates(ALL_SYSTEMS)
                general--
            }

            addScriptedEquipment()
            val flags = addProceduralWeapons()
            addProceduralDrones(flags)

            // Crew: ShipEvent crew entries, or one entry of the blueprint's class.
            addCrew()

            // End of CreateShip: shields up (InstantPowerShields 0x49d710), drone parts.
            ship.instantPowerShields()
            val droneCount = ship.drones?.drones?.count { it != null } ?: 0
            if (ship.dronesCount < droneCount * 2)
                ship.dronesCount = droneCount * 2
            if (ship.dronesCount < 1 && (ship.drones != null || ship.hacking != null))
                ship.dronesCount = 5
        }

        // ---- system access by native id ----

        private fun slotsFor(id: Int) = ship.systemSlots.filter { SYSTEM_IDS[it.spec.systemName] == id }

        fun hasSystem(id: Int): Boolean = slotsFor(id).any { it.isInstalled }

        fun systemOf(id: Int): AbstractSystem? {
            // GetSystem(11) is the last artillery added; for everything else there is one.
            return slotsFor(id).filter { it.isInstalled }.lastOrNull()?.room?.system
        }

        fun levelOf(id: Int): Int = systemOf(id)?.energyLevels ?: 0

        private fun needsPower(system: AbstractSystem) = system is MainSystem

        /** ShipManager::AddSystem (0x4a8370): install the next unused slot of this system at max(1, power). */
        private fun addSystem(id: Int): AbstractSystem? {
            val slot = slotsFor(id).filter { !it.isInstalled }.minByOrNull { it.spec.systemIndex } ?: return null
            slot.room.setSystem(slot)
            val system = slot.room.system ?: return null
            system.energyLevels = max(1, slot.spec.startingPower).coerceAtMost(slot.system.maxPower)
            ship.browserPowerAtCreation(system)
            ShipConstructionDraws.addSystem(ship, slot)
            return system
        }

        /** GenerateSystemMaxes: one draw per template, in id order. */
        private fun systemMaxes(): IntArray {
            val out = IntArray(SYSTEM_COUNT)
            for (id in 0 until SYSTEM_COUNT) {
                val template = templates[id] ?: continue
                val start = template.startingPower
                val max = template.aiMaxPower ?: -1
                var m = 1
                if (level > -1) {
                    m = if (level > 1) 3 else 2
                    if (easy)
                        m = 2
                }
                val scaled = level.toFloat() * 0.125f
                var value = rand.nextInt(m) + (scaled * (max - start).toFloat() + start.toFloat()).toInt()
                if (value < start)
                    value = start
                if (value > max)
                    value = max
                out[id] = value
            }
            return out
        }

        /** The 0x620630 / 0x620970 / 0x620ca0 lists: every installed system below its maximum. */
        private fun candidates(ids: IntArray): List<Int> {
            val out = ArrayList<Int>()
            for (id in ids) {
                for (slot in slotsFor(id)) {
                    val system = slot.room.system ?: continue
                    if (!slot.isInstalled)
                        continue
                    if (system.energyLevels < maxes[id])
                        out += id
                }
            }
            return out
        }

        /**
         * ShipGenerator 0x61eb60: add a bar to a system, installing it first when the ship lacks it (the reactor pays
         * the installed level). Artillery picks one of its systems below the maximum with a draw and, being
         * assigned the pick afterwards, also gets the extra bar when it was just installed.
         */
        fun upgrade(id: Int) {
            var system = systemOf(id)
            if (id == ARTILLERY) {
                if (system == null)
                    addSystem(id)
                val open = slotsFor(id).filter { it.isInstalled && (it.room.system?.energyLevels ?: 0) < maxes[id] }
                    .sortedBy { it.spec.systemIndex }
                if (open.isEmpty())
                    return
                system = open[rand.nextInt(open.size)].room.system
            }

            if (system == null) {
                val added = addSystem(id) ?: return
                if (needsPower(added))
                    ship.purchasedReactorPower += added.energyLevels
            } else {
                if (needsPower(system))
                    ship.purchasedReactorPower++
                system.energyLevels++
            }
        }

        // ---- weapons and drones ----

        private fun resolveWeapon(name: String): AbstractWeaponBlueprint? {
            // GetWeaponBlueprint (0x60a9a0): a list name picks a random member; anything else that is unknown
            // is the inert default blueprint.
            val ref = bp.getOrNull(name)
            val picked = if (ref is BlueprintList) bp.getOrNull(ref.names[rand.nextInt(ref.names.size)]) else ref
            return picked as? AbstractWeaponBlueprint
        }

        private fun resolveDrone(name: String): DroneBlueprint? {
            val ref = bp.getOrNull(name)
            val picked = if (ref is BlueprintList) bp.getOrNull(ref.names[rand.nextInt(ref.names.size)]) else ref
            return picked as? DroneBlueprint
        }

        private fun listNames(listName: String?): List<String> {
            if (listName == null)
                return emptyList()
            return (bp.getOrNull(listName) as? BlueprintList)?.names ?: emptyList()
        }

        private fun addWeapon(weapon: AbstractWeaponBlueprint): Boolean {
            if (ship.weapons == null)
                return false
            for (slot in 0 until weaponSlots) {
                if (ship.hardpoints[slot].weapon != null)
                    continue
                ship.hardpoints[slot].weapon = weapon.buildInstance(ship)
                ShipConstructionDraws.weapon()
                return true
            }
            return false
        }

        private fun addDrone(drone: DroneBlueprint): Boolean {
            val drones = ship.drones ?: return false
            for (slot in drones.drones.indices) {
                if (drones.drones[slot] != null)
                    continue
                drones.drones[slot] = Drones.DroneInfo(drone, null)
                ShipConstructionDraws.drone(sys, drone)
                return true
            }
            return false
        }

        /**
         * The ship event's weaponOverride/droneOverride: pick names without replacement (`names[random32() % n]`),
         * count times, then raise the system to the total power the picks need - installing it when missing.
         */
        private fun addScriptedEquipment() {
            val weaponOverride = spec.weaponOverride
            if (weaponOverride != null) {
                val names = ArrayList(weaponOverride.names)
                var total = 0
                for (i in 0 until weaponOverride.count) {
                    if (names.isEmpty())
                        continue
                    val index = rand.nextInt(names.size)
                    val weapon = resolveWeapon(names[index])
                    if (weapon != null) {
                        total += weapon.power
                        addWeapon(weapon)
                    }
                    names.removeAt(index)
                }
                raiseTo(3, total)
            }

            val droneOverride = spec.droneOverride
            if (droneOverride != null) {
                val names = ArrayList(droneOverride.names)
                var total = 0
                for (i in 0 until droneOverride.count) {
                    if (names.isEmpty())
                        continue
                    val index = rand.nextInt(names.size)
                    val drone = resolveDrone(names[index])
                    if (drone != null) {
                        total += drone.power
                        addDrone(drone)
                    }
                    names.removeAt(index)
                }
                raiseTo(4, total)
            }
        }

        private fun raiseTo(id: Int, total: Int) {
            val missing = total - levelOf(id)
            if (missing <= 0)
                return
            for (i in 0 until missing) {
                val system = systemOf(id)
                if (system == null) {
                    val added = addSystem(id) ?: return
                    if (needsPower(added))
                        ship.purchasedReactorPower += added.energyLevels
                } else {
                    if (needsPower(system))
                        ship.purchasedReactorPower++
                    system.energyLevels++
                }
            }
        }

        /**
         * GetWeaponFlags (0x61ee80): the capabilities a weapon provides, as bit 0 (hull damage) and bit 1
         * (laser-like: a laser, or a missile that does not pierce shields past 3).
         */
        private fun provides(weapon: AbstractWeaponBlueprint): Int {
            val damage = if (weapon.damage != 0) 1 else 0
            return when (weapon) {
                is LaserBlueprint -> 2 or damage
                is MissileBlueprint -> (if (weapon.shieldPiercing < 4) 2 else 0) or damage
                else -> damage
            }
        }

        /** GetPossibleWeaponList (0x61f8f0). */
        private fun weaponCandidates(listName: String?, flags: Int): List<AbstractWeaponBlueprint> {
            val weaponSystem = ship.weapons ?: return emptyList()
            val powerMax = weaponSystem.energyLevels
            val current = ship.hardpoints.mapNotNull { it.weapon }
            val remaining = powerMax - current.sumOf { it.type.power }
            if (remaining < 1)
                return emptyList()
            if (current.size >= weaponSlots)
                return emptyList()

            val out = ArrayList<AbstractWeaponBlueprint>()
            for (name in listNames(listName)) {
                val weapon = resolveWeapon(name) ?: continue

                // The first weapon of a system with three or more bars may not be a one-bar weapon.
                if (powerMax >= 3 && current.isEmpty() && weapon.power == 1)
                    continue

                // It must provide a capability still missing (or none is missing), use more than a quarter of the
                // remaining power and fit, and never take the whole system unless that is a single bar.
                if ((flags and provides(weapon)) == 0 && flags != 0)
                    continue
                if ((remaining shr 2) < weapon.power && weapon.power <= remaining && (powerMax == 1 || weapon.power != powerMax))
                    out += weapon
            }
            return out
        }

        private fun addProceduralWeapons(): Int {
            val listName = loadName("weaponList")
            var flags = 3
            var list = weaponCandidates(listName, flags)
            while (list.isNotEmpty()) {
                val weapon = list[rand.nextInt(list.size)]
                // Clear the capabilities this weapon provides
                val damage = weapon.damage != 0
                flags = flags and when (weapon) {
                    is LaserBlueprint -> if (damage) 0xfc else 0xfd
                    is MissileBlueprint -> {
                        val pierces = weapon.shieldPiercing > 3
                        if (damage) (if (pierces) 0xfe else 0xfc) else (if (pierces) 0xff else 0xfd)
                    }
                    else -> if (damage) 0xfe else 0xff
                }
                addWeapon(weapon)
                list = weaponCandidates(listName, flags)
            }
            return flags
        }

        /** GetPossibleDroneList (0x61fda0). */
        private fun droneCandidates(listName: String?, flags: Int, fallback: Boolean): List<DroneBlueprint> {
            val droneSystem = ship.drones ?: return emptyList()
            val powerMax = droneSystem.energyLevels
            val current = droneSystem.drones.filterNotNull()
            val remaining = powerMax - current.sumOf { it.type.power }
            if (remaining < 1)
                return emptyList()
            if (current.size >= droneSlots)
                return emptyList()

            val out = ArrayList<DroneBlueprint>()
            for (name in listNames(listName)) {
                val drone = resolveDrone(name) ?: continue

                // While a capability is missing (flags set) only combat drones pass, unless this is the fallback pass.
                val passes = (drone.type == DroneBlueprint.DroneType.COMBAT && flags != 0) || !(flags != 0 && !fallback)
                if (!passes)
                    continue

                // No duplicates, except on the fallback pass.
                if (!fallback && current.any { it.type == drone })
                    continue

                // A zero-power entry is a bad blueprint in the list (inert).
                if (drone.power == 0)
                    continue
                if (drone.power <= remaining && (drone.power != powerMax || powerMax < 4))
                    out += drone
            }
            return out
        }

        private fun addProceduralDrones(initialFlags: Int) {
            val listName = loadName("droneList")
            var flags = initialFlags
            var list = droneCandidates(listName, flags, false)
            while (list.isNotEmpty()) {
                while (true) {
                    val drone = list[rand.nextInt(list.size)]
                    if (drone.type == DroneBlueprint.DroneType.COMBAT)
                        flags = 0
                    addDrone(drone)
                    list = droneCandidates(listName, flags, false)
                    if (list.isNotEmpty())
                        break
                    list = droneCandidates(listName, flags, true)
                    if (list.isEmpty())
                        return
                }
            }
        }

        private fun loadName(tag: String): String? =
            ship.type.loadElem().getChild(tag)?.getAttributeValue("load")

        // ---- crew ----

        private fun addCrew() {
            val type = ship.type
            val minCrew = type.initialCrew.sumOf { it.amount }
            var baseCrew = 0
            var defaultRace = "human"
            val overrides = spec.crewOverride?.specifiers ?: emptyList()
            val entries = ArrayList<CrewEntry>()
            for (o in overrides)
                entries += CrewEntry(o.race, o.prop, o.amount)

            if (minCrew == 0) {
                // No crew: an automated ship unless the event lists crew.
                ship.isAutomated = overrides.isEmpty()
            } else {
                defaultRace = type.initialCrew.first { it.amount > 0 }.race
                val maxCrew = type.initialCrew.lastOrNull { it.max != null }?.max ?: DEFAULT_MAX_CREW

                // (int)((max - min) * min(level/8, 1) + min), with the level clamped.
                var scaled = (maxCrew - minCrew).toFloat()
                if (levelClamped.toFloat() * 0.125f < 1f)
                    scaled = scaled * levelClamped.toFloat() * 0.125f
                baseCrew = (scaled + minCrew.toFloat()).toInt()
                ship.isAutomated = false

                if (entries.isEmpty())
                    entries += CrewEntry(defaultRace, 1f, 0)
            }

            for (entry in entries) {
                // The member count of an entry: its amount, or prop of the crew count (at least one).
                val n: Int
                if (entry.prop <= 0f) {
                    n = entry.amount
                } else {
                    val share = entry.prop * baseCrew.toFloat()
                    n = if (share <= 1f) 1 else share.toInt()
                }

                // ShipGenerator 0x61ef00 builds the candidates, then one pick draw per member with the candidates
                // rebuilt after each; ShipManager 0x4ae2f0 creates the picked crew.
                var candidates = crewCandidates(entry.race)
                val picked = ArrayList<CrewPick>()
                if (candidates.isNotEmpty() && n > 0) {
                    var remaining = n
                    do {
                        picked += candidates[rand.nextInt(candidates.size)]
                        remaining--
                        candidates = crewCandidates(entry.race)
                    } while (remaining > 0 && candidates.isNotEmpty())
                }
                spawnCrew(sys, ship, difficulty, picked)
            }

            // 0x4ae2f0 marks the ship automated when it ended up without crew.
            if (ship.crew.isEmpty())
                ship.isAutomated = true
        }

        /**
         * ShipGenerator 0x61ef00: nothing when the ship's crew is full (ten for an enemy) or automated; a race name
         * gives its blueprint (a crew list gives one per member); "random" gives one weighted random blueprint.
         *
         * Every candidate of a named race comes from GetCrewBlueprint (0x60e020), which draws the member's name
         * (0x605750, one draw per attempt, up to 21) and keeps it in the blueprint; "random" draws the weighted
         * sampler (0x764260) and the blueprint has no name yet, so the name is drawn when the member is created.
         */
        private fun crewCandidates(race: String): List<CrewPick> = crewCandidatesFor(sys, ship, race)
    }

    /** A picked crew blueprint, with the name GetCrewBlueprint already drew for it (null: drawn at creation). */
    private class CrewPick(val blueprint: CrewBlueprint, val name: NativeCrewFactory.NewName?)

    private class CrewEntry(val race: String, val prop: Float, val amount: Int)

    private fun crewCandidatesFor(sys: InGameState, ship: Ship, race: String): List<CrewPick> {
        if (ship.crew.size >= MAX_ENEMY_CREW || ship.isAutomated)
            return emptyList()
        if (race == "random")
            return listOf(CrewPick(sys.lootPool.getCrewOrRandom(NativeRandom, "random"), null))

        // GetCrewBlueprint: an unknown name gives the human blueprint; each blueprint draws a name.
        val ref = bp.getOrNull(race) ?: bp.getOrNull("human")
        val blueprints = if (ref is BlueprintList)
            ref.names.mapNotNull { bp.getOrNull(it) as? CrewBlueprint }
        else
            listOfNotNull(ref as? CrewBlueprint)
        return blueprints.map { CrewPick(it, sys.crewFactory.newName()) }
    }

    private fun crewBlueprintFor(sys: InGameState, race: String): CrewBlueprint {
        if (race == "random")
            return sys.lootPool.getCrewOrRandom(NativeRandom, "random")
        return bp[race].resolve(NativeRandom) as CrewBlueprint
    }

    /**
     * ShipManager 0x4ae2f0 / AddCrewMemberFromBlueprint 0x4a24a0: the single place enemy crew members are created.
     * Native per member, in this order: a name draw when the blueprint has none ([NativeCrewFactory.newName]),
     * CrewMemberFactory 0x4685a0 (an animation timer draw, then the colour layer draws 0x602e30,
     * [NativeCrewFactory.drawColours]), the room from the priority list below (no draw for the first 15 members).
     * Enemy crew get no random skills.
     */
    private fun spawnCrew(sys: InGameState, ship: Ship, difficulty: Difficulty, picked: List<CrewPick>) {
        val factory = sys.crewFactory
        // Member i goes to the room of the i-th system of this list that the ship has (restarting for each crew
        // entry): the pilot, up to two artillery rooms, then shields/weapons/engines (engines/weapons/shields on hard),
        // oxygen, sensors, doors, cloaking, medbay, drones, hacking, teleporter and a last artillery entry. Members
        // beyond the list go to a random room (`random32() % room count`).
        val priority = if (difficulty == Difficulty.HARD)
            intArrayOf(6, 11, 11, 1, 3, 0, 2, 7, 8, 10, 5, 4, 15, 9, 11)
        else
            intArrayOf(6, 11, 11, 0, 3, 1, 2, 7, 8, 10, 5, 4, 15, 9, 11)

        val artillery = ship.artillery.sortedBy { it.configuration.spec.systemIndex }.toMutableList()
        val present = priority.filter { if (it == ARTILLERY) artillery.isNotEmpty() else systemRoom(ship, it) != null }

        for ((i, pick) in picked.withIndex()) {
            // 0x4ae2f0 chooses the room first: a member beyond the priority list draws `random32() % room count`
            // before 0x4a24a0 draws the name, the timer and the colours (native traces, 14 of 14 members).
            val room: Room? = if (i < present.size) {
                val id = present[i]
                if (id == ARTILLERY) {
                    (if (artillery.isNotEmpty()) artillery.removeAt(0).room else null) ?: ship.piloting?.room
                } else {
                    systemRoom(ship, id)
                }
            } else {
                ship.rooms[NativeRandom.nextInt(ship.rooms.size)]
            }

            val name = pick.name ?: factory.newName()
            NativeRandom.next31() // the animation timer's start delay (TimerHelper::Start)
            val colours = factory.drawColours(pick.blueprint)
            // CrystalAlien's constructor (0x487e70) then draws whether its lockdown ever gets ready.
            val crystalNeverReady = if (pick.blueprint.name == "crystal") NativeRandom.next31() and 1 == 0 else false
            val info = LivingCrewInfo(sys, pick.blueprint, name.name, factory.colourIndex(pick.blueprint, colours, name.male))

            val member = if (room != null)
                ship.addCrewMemberInRoom(info, room, false)
            else
                ship.addCrewMember(info, true)
            (member as? CrewCrystal)?.initEnemyPower(crystalNeverReady)
            // CrewMemberFactory 0x4685a0 ends by resetting the names in use to the player's crew (0x467dd0), so the
            // names this ship drew so far no longer block later draws.
            sys.eventCreator.refreshUsedNames()
        }
    }

    private fun systemRoom(ship: Ship, id: Int): Room? {
        val name = SYSTEM_NAMES[id]
        return ship.systems.firstOrNull { it.blueprint.type == name }?.room
    }

    companion object {
        private const val SYSTEM_COUNT = 16
        private const val DEFAULT_SLOTS = 4
        private const val DEFAULT_MAX_CREW = 8
        private const val MAX_ENEMY_CREW = 10
        private const val ARTILLERY = 11

        /** Native system ids (ShipSystem::NameToSystemId) by the name used in the ship XML. */
        private val SYSTEM_IDS = mapOf(
            "shields" to 0, "engines" to 1, "oxygen" to 2, "weapons" to 3, "drones" to 4, "medbay" to 5,
            "pilot" to 6, "sensors" to 7, "doors" to 8, "teleporter" to 9, "cloaking" to 10, "artillery" to 11,
            "battery" to 12, "clonebay" to 13, "mind" to 14, "hacking" to 15
        )

        private val SYSTEM_NAMES = SYSTEM_IDS.entries.associate { it.value to it.key }

        // 0x620630: weapons, drones, teleporter, artillery. 0x620970: shields, engines, cloaking. 0x620ca0: all.
        private val OFFENSIVE = intArrayOf(3, 4, 9, 11)
        private val DEFENSIVE = intArrayOf(0, 1, 10)
        private val ALL_SYSTEMS = IntArray(SYSTEM_COUNT) { it }

        /** CreateShip's hull: blueprint hull plus the level, 20 at most. */
        fun nativeMaxHull(blueprintHull: Int, effectiveSector: Int): Int {
            val hull = blueprintHull + effectiveSector
            return if (hull > 19) 20 else hull
        }

        fun seedToString(sector: Int, difficulty: Difficulty, seed: Int): String {
            // Base64-encode the seed to make it less painful to type in
            val bytes = ByteBuffer.allocate(6)
            bytes.put(sector.toByte())
            bytes.put(difficulty.ordinal.toByte())
            bytes.putInt(seed)
            return Base64.getEncoder().encodeToString(bytes.array()).trim('=')
        }
    }
}

class EnemyShipSpec(elem: Element, bp: BlueprintManager, ev: EventManager) {
    val name = elem.requireAttributeValue("name")
    val fixedBlueprint: Boolean = elem.getAttributeValue("blueprint") != null

    val escape: IEvent? by loadEvent(elem, ev, name, "escape")
    val surrender: IEvent? by loadEvent(elem, ev, name, "surrender")
    val destroyed: IEvent? by loadEvent(elem, ev, name, "destroyed")
    val deadCrew: IEvent? by loadEvent(elem, ev, name, "deadCrew")
    val gotaway: IEvent? by loadEvent(elem, ev, name, "gotaway")

    // Two ships (TUTORIAL_PIRATE and IMPOSSIBLE_PIRATE) have 'blueprint' attributes instead. While it's
    // unlikely we'll care about them for a long time (if ever), it's nice to load all the ships without
    // having to carry around a list of exceptions.
    val autoBlueprint = bp[elem.getAttributeValue("blueprint") ?: elem.requireAttributeValue("auto_blueprint")]

    val escapeTimer: Float = elem.getChild("escape")?.getAttributeValue("timer")?.toFloat() ?: 15f
    val escapeHealth: RandomWithChance? = elem.getChild("escape")?.let(::RandomWithChance)
    val surrenderHealth: RandomWithChance? = elem.getChild("surrender")?.let(::RandomWithChance)

    val crewOverride: ShipCrewOverride? = elem.getChild("crew")?.let { ShipCrewOverride(it, bp) }
    val weaponOverride: ShipBlueprintOverride? = elem.getChild("weaponOverride")?.let { ShipBlueprintOverride(it) }
    val droneOverride: ShipBlueprintOverride? = elem.getChild("droneOverride")?.let { ShipBlueprintOverride(it) }

    companion object {
        private fun loadEvent(root: Element, ev: EventManager, name: String, evName: String): Lazy<IEvent?> {
            // No default: the exe never names DESTROYED_DEFAULT and friends.
            val elem = root.getChild(evName) ?: return lazyOf(null)
            return ev.loadEmbeddedEvent(elem, name)
        }
    }
}

class ShipCrewOverride(elem: Element, bp: BlueprintManager) {
    val specifiers: List<Spec> = elem.getChildren("crewMember").map { Spec(it) }

    class Spec(elem: Element) {
        // The ship parser (0x5e3c10) defaults to "random" and only reads
        // "type", so GHOST_SHIP's class="ghost" crew are random races.
        val race: String = elem.getAttributeValue("type") ?: "random"

        val prop: Float = elem.getAttributeValue("prop")?.toFloat() ?: 0f
        val amount: Int = elem.getAttributeValue("amount")?.toInt() ?: 0
    }
}

/**
 * For <weaponOverride> and <droneOverride> in <ship>: a name list and how many to take (the parser 0x5e3c10 defaults
 * the count to 1).
 */
class ShipBlueprintOverride(elem: Element) {
    val names: List<String>
    val count: Int

    init {
        names = ArrayList()

        count = elem.getAttributeValue("count")?.toInt() ?: 1
        for (nameElem in elem.children) {
            require(nameElem.name == "name")
            names += nameElem.textTrim
        }
    }
}
