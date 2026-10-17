package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.LootPool
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.StoreData
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.vanillasave.VanillaSaveFormat
import xyz.znix.xftl.vanillasave.VanillaSaveWriter
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Checks for store generation (see docs/ORIGINAL_BEHAVIOR.md):
 * the sections, items, crew, systems and resources of a store for a known
 * stream state and ship, the drone control rules, and the saves.
 *
 * The known answers (at the bottom) come from the draw-exact Python model of
 * the original's Store creation (.build/gameplay-1to1/model/stores), which
 * reproduces the original's own stores for the same stream draws: a failure
 * means this engine's draws or rules differ from the original's.
 */
internal object GameplayParityStoresChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val streamBefore = NativeRandom.snapshot()
        val parts = ArrayList<String>()
        try {
            parts += knownStores(main, container)
            parts += invariants(main, container)
            parts += droneControl(main, container)
            parts += repairPrices()
            parts += overflowCargo(main, container)
            parts += saves(main, container)
        } finally {
            NativeRandom.trace = null
            NativeRandom.restore(streamBefore)
        }
        return "stores: " + parts.joinToString(", ")
    }

    private fun prepare(main: MainGame, container: GameContainer, ship: String): InGameState {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, ship)
        state.setDifficulty(Difficulty.NORMAL)
        // The base rarities (no sector list), and no crew names in use: what the known answers assume.
        state.setLootPool(LootPool(state.blueprintManager, null))
        state.eventCreator.usedNamesFixture = listOf<String>()
        return state
    }

    private fun sectionId(section: StoreData.Section): Int = when (section) {
        StoreData.Section.WEAPONS -> 0
        StoreData.Section.DRONES -> 1
        StoreData.Section.AUGMENTS -> 2
        StoreData.Section.CREW -> 3
        StoreData.Section.SYSTEMS -> 4
    }

    /**
     * A store in the model's signature form: the section ids, then each section's
     * boxes (items by name, crew as race/name/skills, systems as name@price#variant),
     * the resources and the repair price.
     */
    private fun describe(state: InGameState, store: StoreData, level: Int): String {
        val parts = ArrayList<String>()
        parts += "slots=" + store.sections.joinToString("") { sectionId(it).toString() }
        for (section in store.sections) {
            parts += when (section) {
                StoreData.Section.WEAPONS -> "W:" + store.weapons.joinToString(",") { it?.name ?: "-" }
                StoreData.Section.DRONES -> "D:" + store.drones.joinToString(",") { it?.name ?: "-" }
                StoreData.Section.AUGMENTS -> "A:" + store.augments.joinToString(",") { it?.name ?: "-" }
                StoreData.Section.CREW -> "C:" + store.crew.joinToString(",") { member ->
                    if (member == null) "-" else {
                        val skills = Skill.values().joinToString("") { (((member.skills[it] ?: 0f) * 2f).toInt()).toString() }
                        "${member.race.name}/${member.name}/$skills"
                    }
                }
                StoreData.Section.SYSTEMS -> "S:" + store.systems.withIndex().joinToString(",") { (i, system) ->
                    if (system == null) "-" else "${system.name}@${store.systemPrice(state, i)}#${store.systemVariants[i]}"
                }
            }
        }
        val res = store.availableResources
        parts += "res=${res.fuel},${res.missiles},${res.droneParts}"
        parts += "repair=${StoreData.repairPrice(level)}"
        return parts.joinToString("|")
    }

    /**
     * Give the ship the systems of [names] it lacks, in the slot its blueprint has for them (AddSystem 0x4a8370 takes
     * no room); a clone bay's slot is the medbay's room, so it takes the medbay's place.
     */
    private fun installSystems(state: InGameState, names: String) {
        val owned = state.player.systems.map { it.blueprint.name }.toSet()
        for (name in names.split(',')) {
            if (name in owned)
                continue
            val blueprint = state.blueprintManager[name] as SystemBlueprint
            val slot = checkNotNull(state.player.systemSlots.firstOrNull { it.system == blueprint }) { "No $name slot" }
            slot.room.setSystem(slot)
        }
    }

    private fun ownedSystems(state: InGameState): String =
        state.player.systems.map { it.blueprint.name }.sorted().joinToString(",")

    // ---- Known answers

    /**
     * Store::OnInit (0x4c3b20) -> 0x4bed80 per section -> 0x4c2710 for a seed, on ships
     * with different systems (forced shields, owned drones, a clone bay and no medbay).
     */
    private fun knownStores(main: MainGame, container: GameContainer): String {
        val ae = BrowserGameplayParityDiagnostics.fresh(main, container).content.enableAdvancedEdition
        val cases = if (ae) Expected.STORES_AE + Expected.STORES_EXTRA_AE else Expected.STORES_VANILLA + Expected.STORES_EXTRA_VANILLA
        var state: InGameState? = null
        var ship = ""
        var lastSystems = ""
        for (known in cases) {
            // The extra-system cases change the ship, so each distinct system list starts from a fresh one.
            if (state == null || known.ship != ship || known.systems != lastSystems) {
                state = prepare(main, container, known.ship)
                ship = known.ship
                lastSystems = known.systems
            }
            installSystems(state, known.systems)
            val owned = ownedSystems(state)
            check(owned == known.systems.split(',').sorted().joinToString(",")) {
                "${known.ship} has the systems $owned, the known answers assume ${known.systems}"
            }

            NativeRandom.seed(known.seed)
            val store = StoreData()
            val (_, draws) = counted { store.generateRandomContents(state, NativeRandom, known.level) }
            val got = describe(state, store, known.level)
            check(got == known.sig) {
                "${known.ship} store for seed ${known.seed} level ${known.level} is\n  $got\nbut the original makes\n  ${known.sig}"
            }
            check(draws == known.draws) { "${known.ship} store for seed ${known.seed} drew $draws times, expected ${known.draws}" }
        }
        return "${cases.size} known stores (${if (ae) "AE" else "vanilla"}: sections, items, crew, systems, drone control prices, resources)"
    }

    // ---- Rules that hold for every store

    private fun invariants(main: MainGame, container: GameContainer): String {
        var checked = 0
        val aeOnly = setOf("battery", "clonebay", "mind", "hacking")
        for (shipName in listOf("PLAYER_SHIP_HARD", "PLAYER_SHIP_STEALTH", "PLAYER_SHIP_CIRCLE")) {
            val state = prepare(main, container, shipName)
            val ae = state.content.enableAdvancedEdition
            val owned = state.player.systems.map { it.blueprint.name }.toSet()
            for (seed in 1..120) {
                NativeRandom.seed(seed * 7919)
                val level = seed % 9
                val store = StoreData()
                store.generateRandomContents(state, NativeRandom, level)
                val what = "$shipName seed ${seed * 7919}"

                check(store.sections.size in 2..4 && store.sections.toSet().size == store.sections.size) { "$what sections ${store.sections}" }
                val hasSystems = StoreData.Section.SYSTEMS in store.sections
                val hasDrones = StoreData.Section.DRONES in store.sections
                check(!hasDrones || "drones" in owned || hasSystems) { "$what offers drones to a ship without a drone system" }

                for (items in listOf(store.weapons, store.drones, store.augments)) {
                    val stocked = items.filterNotNull()
                    check(stocked.toSet().size == stocked.size) { "$what repeats an item" }
                    if (items.isNotEmpty()) {
                        check(items.size == 3) { "$what has ${items.size} item boxes" }
                        check(if (ae) stocked.size == 3 else stocked.size in 2..3) { "$what has ${stocked.size} items" }
                    }
                }
                if (store.crew.isNotEmpty()) {
                    val stocked = store.crew.count { it != null }
                    check(store.crew.size == 3 && if (ae) stocked == 3 else stocked in 2..3) { "$what has $stocked crew" }
                    if (level == 0)
                        check(store.crew.filterNotNull().all { member -> Skill.values().all { (member.skills[it] ?: 0f) == 0f } }) { "$what: level 0 crew have skills" }
                }

                if (hasSystems) {
                    val names = store.systems.filterNotNull().map { it.name }
                    check(store.systems.size == 3 && names.toSet().size == names.size) { "$what systems $names" }
                    check(names.none { it in owned } && "artillery" !in names) { "$what offers $names" }
                    check(ae || names.none { it in aeOnly }) { "$what offers an AE system without AE: $names" }
                    if (hasDrones && "drones" !in owned)
                        check(names.firstOrNull() == "drones") { "$what: drone control is not first in $names" }
                    if ("shields" !in owned)
                        check("shields" in names) { "$what does not offer the missing shields" }
                    if ("medbay" !in owned && !(ae && "clonebay" in owned))
                        check("medbay" in names || (ae && "clonebay" in names)) { "$what does not offer a medical system: $names" }
                    for (i in names.indices)
                        check(store.systemVariants[i] in 0..2) { "$what has variant ${store.systemVariants[i]}" }
                }
                checked++
            }
        }
        return "$checked generated stores keep the section rules (distinct sections, forced systems, item counts)"
    }

    // ---- Drone control

    /** SystemStoreBox (0x51ce20 / 0x519070): drone control carries a free drone and half its cost in the price. */
    private fun droneControl(main: MainGame, container: GameContainer): String {
        val state = prepare(main, container, "PLAYER_SHIP_STEALTH")
        val control = state.blueprintManager["drones"] as SystemBlueprint
        val expected = listOf("DEFENSE_1", "REPAIR", "COMBAT_1")
        check(StoreData.DRONE_CONTROL_DRONES == expected) { "GameplayParityStoresChecks.kt:210" }
        val store = StoreData()
        store.sections += StoreData.Section.SYSTEMS
        for (variant in 0..2) {
            store.systems += control
            store.systemVariants += variant
        }
        store.systems += state.blueprintManager["shields"] as SystemBlueprint
        store.systemVariants += 1
        store.systems += null
        store.systemVariants += -1
        for (variant in 0..2) {
            val drone = state.blueprintManager[expected[variant]] as DroneBlueprint
            check(store.freeDrone(state, variant) === drone) { "Variant $variant gives ${store.freeDrone(state, variant)?.name}" }
            check(store.systemPrice(state, variant) == control.cost + (drone.cost ?: 0) / 2) { "Variant $variant costs ${store.systemPrice(state, variant)}" }
        }
        check(store.freeDrone(state, 3) == null && store.systemPrice(state, 3) == store.systems[3]!!.cost) { "Another system carries no drone" }
        check(store.freeDrone(state, 4) == null && store.systemPrice(state, 4) == 0) { "A sold-out box has no price" }
        return "drone control (free drone, half its cost in the price)"
    }

    // ---- Repair price

    private fun repairPrices(): String {
        val prices = (0..8).map { StoreData.repairPrice(it) }
        check(prices == listOf(2, 2, 2, 3, 3, 3, 4, 4, 4)) { "Repair prices by level: $prices" }
        return "repair prices by level"
    }

    // ---- Saves

    private fun saves(main: MainGame, container: GameContainer): String {
        var state = prepare(main, container, "PLAYER_SHIP_CIRCLE")
        val ae = state.content.enableAdvancedEdition

        // A store with systems and crew sections (variants on every system box).
        var found: StoreData? = null
        var seed = 100
        while (found == null) {
            NativeRandom.seed(seed++)
            val candidate = StoreData()
            candidate.generateRandomContents(state, NativeRandom, 3)
            if (StoreData.Section.SYSTEMS in candidate.sections && StoreData.Section.CREW in candidate.sections)
                found = candidate
        }
        val store: StoreData = found
        // Sell one box out, as a purchase leaves a null.
        store.systems[0] = null

        val level = 3
        val before = describe(state, store, level)

        // The engine's save: the variants come back.
        val elem = Element("store")
        store.saveToXML(elem)
        val loaded = StoreData()
        loaded.loadFromXML(state, elem)
        check(describe(state, loaded, level) == before) { "A store changed in the save:\n  ${describe(state, loaded, level)}\n  $before" }

        // An older save has no variants: no free drone, the base price.
        val old = Element("store")
        store.saveToXML(old)
        for (systemElem in old.getChildren("system"))
            systemElem.removeAttribute("variant")
        val legacy = StoreData()
        legacy.loadFromXML(state, old)
        for ((i, system) in legacy.systems.withIndex()) {
            if (system == null)
                continue
            check(legacy.freeDrone(state, i) == null && legacy.systemPrice(state, i) == system.cost) { "An old store's ${system.name} box changed price" }
        }
        check(legacy.sections == store.sections && legacy.availableResources.fuel == store.availableResources.fuel) { "Old store lost its sections" }

        // The whole game: the store of the current beacon survives the run save.
        val beacon = state.currentBeacon
        beacon.hasStore = true
        beacon.setStoreData(store)
        val saved = BrowserXml.parse(BrowserXml.write(state.saveGameState()))
        main.loadSavedGame(saved)
        state = main.currentState as InGameState
        val after = state.currentBeacon.getStore(state)
        checkNotNull(after) { "The beacon's store is gone after loading" }
        check(describe(state, after, level) == before) { "The beacon's store changed in the run save" }

        // The original's save: a shelf per section in section order, three items each, the variant in the extra.
        val bytes = VanillaSaveWriter.writeToByteArray(state)
        val parsed = main.parseVanillaSaveForParity(bytes)
        val index = state.currentBeacon.sector.beacons.indexOf(state.currentBeacon)
        val vanilla = checkNotNull(parsed.beacons[index].store) { "The vanilla save has no store" }
        check(vanilla.shelves.size == after.sections.size) { "Vanilla shelves ${vanilla.shelves.size} for ${after.sections}" }
        val shelfTypes = listOf(
            VanillaSaveFormat.SHELF_WEAPON, VanillaSaveFormat.SHELF_DRONE, VanillaSaveFormat.SHELF_AUGMENT,
            VanillaSaveFormat.SHELF_CREW, VanillaSaveFormat.SHELF_SYSTEM
        )
        for ((shelf, section) in vanilla.shelves.zip(after.sections)) {
            check(shelf.type == shelfTypes[sectionId(section)] && shelf.items.size == 3) { "Vanilla shelf of $section: type ${shelf.type}, ${shelf.items.size} items" }
            if (section == StoreData.Section.SYSTEMS) {
                for ((i, item) in shelf.items.withIndex()) {
                    check(item.available == (after.systems[i] != null)) { "Vanilla system box $i availability" }
                    check(item.extra == maxOf(after.systemVariants[i], 0)) { "Vanilla system box $i extra ${item.extra}" }
                }
            }
        }
        check(vanilla.fuel == after.availableResources.fuel && vanilla.missiles == after.availableResources.missiles && vanilla.droneParts == after.availableResources.droneParts) {
            "Vanilla resources ${vanilla.fuel}/${vanilla.missiles}/${vanilla.droneParts}"
        }
        return "saves (${if (ae) "AE" else "vanilla"} run save, older save without variants, original's save)"
    }

    // ---- Cargo

    /**
     * WeaponStoreBox::CanHold / DroneStoreBox::CanHold (0x518ef0 -> 0x53d2d0, 0x518e10 -> 0x53d470) count the weapon,
     * drone and four cargo boxes but not the overflow boxes, so a bought item never goes to the overflow box;
     * an event reward (Equipment::IsCompletelyFull 0x53d360) does.
     */
    private fun overflowCargo(main: MainGame, container: GameContainer): String {
        val state = prepare(main, container, "PLAYER_SHIP_HARD")
        val ship = state.player
        val weapon = state.blueprintManager["LASER_BURST_1"] as xyz.znix.xftl.weapons.AbstractWeaponBlueprint
        val weaponSlots = ship.weaponSlots ?: ship.hardpoints.size
        val free = (0 until weaponSlots).count { ship.hardpoints[it].weapon == null } +
                ship.cargoBlueprints.take(Ship.CARGO_SLOTS).count { it == null }
        var added = 0
        while (ship.addBlueprint(weapon, false))
            added++
        check(added == free) { "A ship with $free free boxes took $added bought weapons" }
        check(ship.cargoBlueprints.take(Ship.CARGO_SLOTS).all { it != null }) { "The four cargo boxes are not all full" }
        check(ship.cargoBlueprints[4] == null && ship.cargoBlueprints[5] == null) { "A purchase used the overflow box" }
        check(ship.addBlueprint(weapon, true)) { "A reward found no place" }
        check(ship.cargoBlueprints[4] === weapon) { "A reward did not go to the overflow box" }
        return "bought items never use the overflow box, rewards do"
    }

    // ---- Helpers

    /** Run [block] and count the draws it makes from the stream (seeding is not a draw). */
    private fun <T> counted(block: () -> T): Pair<T, Int> {
        val trace = ArrayList<Int>()
        NativeRandom.trace = trace
        val result = try {
            block()
        } finally {
            NativeRandom.trace = null
        }
        var draws = 0
        var i = 0
        while (i < trace.size) {
            if (trace[i] == Int.MIN_VALUE) i += 2 else {
                draws++
                i++
            }
        }
        return result to draws
    }

    private class KnownStore(val ship: String, val systems: String, val seed: Int, val level: Int, val draws: Int, val sig: String)

    private object Expected {
    // ---- Known answers. Everything below was computed with the draw-exact Python model of the original's
    // Store creation (.build/gameplay-1to1/model/stores/model_stores.py), which reproduces the original's own
    // stores (sections, items, system boxes and prices, crew, resources and the draws that made them).
    // Each case is seeded from the stream, with the ship's systems and the level given.

    val STORES_AE: List<KnownStore> = listOf(
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:drones@85#2,cloaking@150#2,battery@35#2|D:BATTLE,COMBAT_1,DEFENSE_1|A:DRONE_RECOVERY,ADV_SCANNERS,REPAIR_ARM|C:human/Shelton/000000,rock/Hector/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 23, 2, 25,
            "slots=402|S:cloaking@150#2,clonebay@50#2,hacking@80#2|W:SHOTGUN_2,BEAM_2,LASER_HEAVY_1|A:EXPLOSIVE_REPLICATOR,WEAPON_PREIGNITE,LIFE_SCANNER|res=5,2,2|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 37, 4, 53,
            "slots=4031|S:drones@75#1,battery@35#1,clonebay@50#0|W:BEAM_3,BEAM_HULL,LASER_HEAVY_1|C:energy/Nikmi/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 51, 7, 48,
            "slots=4213|S:drones@75#1,battery@35#1,mind@75#1|A:LIFE_SCANNER,FLEET_DISTRACTION,SCRAP_COLLECTOR|D:DEFENSE_1,COMBAT_BEAM_2,DEFENSE_2|C:mantis/Roper/200000,mantis/Aisha/100100,engi/Alex Rigopulos/010100|res=5,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 64, 1, 46,
            "slots=2043|A:O2_MASKS,BATTERY_BOOSTER,FTL_BOOSTER|W:MISSILES_HULL,LASER_HEAVY_2,ION_CHARGEGUN|S:cloaking@150#1,battery@35#1,mind@75#1|C:rock/Steven/000000,mantis/Kusy/000000,rock/Tai Yi/000000|res=5,4,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 78, 3, 46,
            "slots=234|A:EXPLOSIVE_REPLICATOR,DRONE_RECOVERY,FTL_BOOSTER|C:engi/Joel/000100,human/Elnubnub/001000,mantis/Swampson/000200|S:drones@75#1,mind@75#0,hacking@80#0|res=3,4,4|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 92, 5, 41,
            "slots=023|W:ION_CHARGEGUN,LASER_CHAINGUN,BOMB_HEAL_SYSTEM|A:AUTO_COOLDOWN,FLEET_DISTRACTION,ZOLTAN_BYPASS|C:human/Markus/000020,engi/Jose/000011,energy/Jack Shirai/000002|res=3,2,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 105, 8, 48,
            "slots=4031|S:drones@85#0,battery@35#0,clonebay@50#2|W:ION_STUN,BOMB_FIRE,BOMB_HEAL|C:mantis/Firedihm/000000,rock/Mullett/000000,energy/Remo/000000|D:BOARDER,ANTI_DRONE,BATTLE|res=4,6,2|repair=4"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 11, 0, 43,
            "slots=4123|S:drones@85#2,shields@125#2,hacking@80#2|D:ANTI_DRONE,BOARDER,REPAIR|A:SCRAP_COLLECTOR,ADV_SCANNERS,FTL_BOOSTER|C:mantis/Mullett/000000,engi/Julian Rafn/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 23, 2, 23,
            "slots=402|S:shields@125#1,teleporter@90#2,mind@75#1|W:MISSILES_BREACH,LASER_CHARGEGUN_2,ION_CHARGEGUN|A:CLOAK_FIRE,DRONE_RECOVERY,ADV_SCANNERS|res=7,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 37, 4, 46,
            "slots=4031|S:drones@75#1,shields@125#2,battery@35#1|W:BEAM_FIRE,BOMB_FIRE,MISSILES_BURST|C:rock/Turing/010100,engi/Ariel/000010,mantis/Josh Cubbin/001100|D:DEFENSE_1,REPAIR,SHIP_REPAIR|res=5,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 51, 7, 48,
            "slots=4213|S:drones@75#1,shields@125#1,battery@35#1|A:SHIELD_RECHARGE,O2_MASKS,AUTO_COOLDOWN|D:ANTI_DRONE,REPAIR,DRONE_SHIELD|C:human/Bryan/100100,human/Ihlay Caris/201000,engi/Alex Rigopulos/010100|res=5,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:teleporter@90#2,cloaking@150#2,battery@35#2|D:BATTLE,COMBAT_1,DEFENSE_1|A:DRONE_RECOVERY,ADV_SCANNERS,REPAIR_ARM|C:human/Shelton/000000,rock/Hector/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 23, 2, 23,
            "slots=402|S:teleporter@90#1,cloaking@150#2,mind@75#1|W:MISSILES_BREACH,LASER_CHARGEGUN_2,ION_CHARGEGUN|A:CLOAK_FIRE,DRONE_RECOVERY,ADV_SCANNERS|res=7,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 37, 4, 53,
            "slots=4031|S:teleporter@90#2,battery@35#1,clonebay@50#0|W:BEAM_3,BEAM_HULL,LASER_HEAVY_1|C:energy/Nikmi/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 51, 7, 48,
            "slots=4213|S:teleporter@90#1,battery@35#1,mind@75#1|A:LIFE_SCANNER,FLEET_DISTRACTION,SCRAP_COLLECTOR|D:DEFENSE_1,COMBAT_BEAM_2,DEFENSE_2|C:mantis/Roper/200000,mantis/Aisha/100100,engi/Alex Rigopulos/010100|res=5,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_ANAEROBIC", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay,hacking", 11, 0, 43,
            "slots=4123|S:drones@85#2,teleporter@90#2,mind@75#2|D:ANTI_DRONE,BOARDER,REPAIR|A:SCRAP_COLLECTOR,ADV_SCANNERS,FTL_BOOSTER|C:mantis/Mullett/000000,engi/Julian Rafn/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_ANAEROBIC", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay,hacking", 23, 2, 23,
            "slots=402|S:drones@75#1,medbay@50#2,battery@35#1|W:MISSILES_BREACH,LASER_CHARGEGUN_2,ION_CHARGEGUN|A:CLOAK_FIRE,DRONE_RECOVERY,ADV_SCANNERS|res=7,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_ANAEROBIC", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay,hacking", 37, 4, 52,
            "slots=4031|S:drones@75#1,teleporter@90#1,mind@75#1|W:MISSILES_3,LASER_CHARGEGUN_2,BEAM_2|C:rock/Ken/002000,mantis/Jeff Baars/001100,mantis/Ma Fan/001000|D:BOARDER_ION,DEFENSE_1,BOARDER|res=4,2,3|repair=3"),
        KnownStore("PLAYER_SHIP_ANAEROBIC", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay,hacking", 51, 7, 48,
            "slots=4213|S:drones@75#1,teleporter@90#1,mind@75#1|A:LIFE_SCANNER,FLEET_DISTRACTION,SCRAP_COLLECTOR|D:DEFENSE_1,COMBAT_BEAM_2,DEFENSE_2|C:mantis/Roper/200000,mantis/Aisha/100100,engi/Alex Rigopulos/010100|res=5,6,3|repair=4")
    )

    val STORES_VANILLA: List<KnownStore> = listOf(
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:drones@85#2,cloaking@150#2,-|D:SHIP_REPAIR,BOARDER,-|A:FTL_JUMPER,ADV_SCANNERS,SCRAP_COLLECTOR|C:human/Kapalka/000000,human/Jax/000000,mantis/Nikola/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 23, 2, 20,
            "slots=402|S:drones@75#1,teleporter@90#2,cloaking@150#1|W:BOMB_ION,MISSILES_BURST,-|A:FTL_JAMMER,AUTO_COOLDOWN,FTL_JUMPER|res=7,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 37, 4, 35,
            "slots=4031|S:drones@75#1,cloaking@150#1,-|W:ION_4,LASER_HULL_1,LASER_HEAVY_1|C:engi/Stelly/000001,engi/Ryan Maniscalco/000001,-|D:DEFENSE_2,COMBAT_BEAM,REPAIR|res=3,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 51, 7, 36,
            "slots=4213|S:drones@75#1,teleporter@90#1,-|A:WEAPON_PREIGNITE,FTL_JUMPER,-|D:BOARDER,DEFENSE_2,-|C:rock/Josh Cubbin/000002,human/Ohm/002000,-|res=4,3,2|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 64, 1, 42,
            "slots=2043|A:ION_ARMOR,AUTO_COOLDOWN,FTL_JAMMER|W:LASER_BURST_3,MISSILES_BREACH,MISSILES_HULL|S:drones@75#1,teleporter@90#1,cloaking@150#1|C:rock/Stephen Eckman/000000,engi/Elizabeth/000000,engi/Ohm/000000|res=4,5,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 78, 3, 34,
            "slots=234|A:SCRAP_COLLECTOR,ADV_SCANNERS,-|C:rock/Arries/000100,human/Elnubnub/001000,-|S:drones@75#1,teleporter@90#2,cloaking@150#0|res=7,3,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 92, 5, 33,
            "slots=023|W:LASER_HULL_2,BEAM_3,MISSILES_HULL|A:DRONE_RECOVERY,ADV_SCANNERS,-|C:mantis/Starger/010001,human/Davion/000011,-|res=5,4,4|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,medbay,pilot,sensors,doors", 105, 8, 38,
            "slots=4031|S:drones@85#0,cloaking@150#2,-|W:BOMB_1,BOMB_HEAL,-|C:human/Joel/000000,rock/Shelton/000000,-|D:COMBAT_BEAM,COMBAT_1,-|res=7,6,2|repair=4"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 11, 0, 38,
            "slots=4123|S:drones@85#2,shields@125#2,-|D:REPAIR,DEFENSE_1,-|A:ADV_SCANNERS,ION_ARMOR,CLOAK_FIRE|C:rock/Chris Phillips/000000,mantis/Ferry/000000,rock/Hector/000000|res=6,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 23, 2, 18,
            "slots=402|S:shields@125#1,drones@85#0,-|W:BEAM_LONG,MISSILES_3,LASER_HULL_1|A:SHIELD_RECHARGE,FTL_BOOSTER,WEAPON_PREIGNITE|res=3,4,3|repair=2"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 37, 4, 35,
            "slots=4031|S:drones@75#1,shields@125#2,-|W:BOMB_BREACH_2,LASER_BURST_2_A,BEAM_BIO|C:mantis/Remo/000001,engi/Ryan Maniscalco/000001,-|D:DEFENSE_2,COMBAT_BEAM,REPAIR|res=3,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_STEALTH", "engines,oxygen,weapons,medbay,pilot,sensors,doors,cloaking", 51, 7, 36,
            "slots=4213|S:drones@75#1,shields@125#1,-|A:SHIELD_RECHARGE,FTL_JUMPER,-|D:BATTLE,DEFENSE_1,REPAIR|C:rock/Josh Cubbin/000002,human/Ohm/002000,-|res=4,3,2|repair=4"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:teleporter@90#2,cloaking@150#2,-|D:SHIP_REPAIR,BOARDER,-|A:FTL_JUMPER,ADV_SCANNERS,SCRAP_COLLECTOR|C:human/Kapalka/000000,human/Jax/000000,mantis/Nikola/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 23, 2, 19,
            "slots=402|S:teleporter@90#1,cloaking@150#2,-|W:BOMB_HEAL,MISSILES_3,LASER_BURST_3|A:FTL_JAMMER,FTL_BOOSTER,ION_ARMOR|res=5,6,3|repair=2"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 37, 4, 35,
            "slots=4031|S:teleporter@90#2,cloaking@150#1,-|W:ION_4,LASER_HULL_1,LASER_HEAVY_1|C:engi/Stelly/000001,engi/Ryan Maniscalco/000001,-|D:DEFENSE_2,COMBAT_BEAM,REPAIR|res=3,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_CIRCLE", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 51, 7, 46,
            "slots=4213|S:teleporter@90#1,cloaking@150#1,-|A:FTL_JUMPER,FTL_BOOSTER,ION_ARMOR|D:COMBAT_BEAM,REPAIR,COMBAT_2|C:rock/Elnubnub/001010,engi/Butters/012000,energy/Emily/001200|res=5,4,3|repair=4")
    )

    // Ships with more systems than the stock ones: HARD with the systems the native console's SYS command added
    // (model/stores/known_stores2.py; native samples stores2-*): owned drone control, eleven and twelve systems
    // (non-AE with eleven has no systems section), and a clone bay in place of the medbay.
    val STORES_EXTRA_AE: List<KnownStore> = listOf(
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:teleporter@90#2,cloaking@150#2,battery@35#2|D:BATTLE,COMBAT_1,DEFENSE_1|A:DRONE_RECOVERY,ADV_SCANNERS,REPAIR_ARM|C:human/Shelton/000000,rock/Hector/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 23, 2, 23,
            "slots=402|S:teleporter@90#1,cloaking@150#2,mind@75#1|W:MISSILES_BREACH,LASER_CHARGEGUN_2,ION_CHARGEGUN|A:CLOAK_FIRE,DRONE_RECOVERY,ADV_SCANNERS|res=7,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 37, 4, 53,
            "slots=4031|S:teleporter@90#2,battery@35#1,clonebay@50#0|W:BEAM_3,BEAM_HULL,LASER_HEAVY_1|C:energy/Nikmi/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 51, 7, 48,
            "slots=4213|S:teleporter@90#1,battery@35#1,mind@75#1|A:LIFE_SCANNER,FLEET_DISTRACTION,SCRAP_COLLECTOR|D:DEFENSE_1,COMBAT_BEAM_2,DEFENSE_2|C:mantis/Roper/200000,mantis/Aisha/100100,engi/Alex Rigopulos/010100|res=5,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 64, 1, 42,
            "slots=2043|A:O2_MASKS,BATTERY_BOOSTER,FTL_BOOSTER|W:MISSILES_HULL,LASER_HEAVY_2,ION_CHARGEGUN|S:teleporter@90#1,cloaking@150#1,battery@35#1|C:rock/Stephen Eckman/000000,engi/Elizabeth/000000,engi/Ohm/000000|res=4,5,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 78, 3, 45,
            "slots=234|A:EXPLOSIVE_REPLICATOR,DRONE_RECOVERY,FTL_BOOSTER|C:engi/Joel/000100,human/Elnubnub/001000,mantis/Swampson/000200|S:battery@35#0,clonebay@50#2,hacking@80#2|res=7,2,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 11, 0, 44,
            "slots=3142|C:mantis/Roper/000000,energy/Enzo Martin/000000,human/Kapalka/000000|D:BATTLE,SHIP_REPAIR,COMBAT_1|S:battery@35#2,mind@75#1,hacking@80#1|A:LIFE_SCANNER,ADV_SCANNERS,HACKING_STUN|res=6,6,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 23, 2, 19,
            "slots=102|D:ANTI_DRONE,REPAIR,DEFENSE_2|W:LASER_HEAVY_1,ION_4,LASER_HEAVY_2|A:SHIELD_RECHARGE,BATTERY_BOOSTER,EXPLOSIVE_REPLICATOR|res=5,6,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 37, 4, 53,
            "slots=4031|S:battery@35#2,clonebay@50#1,hacking@80#0|W:BEAM_3,BEAM_HULL,LASER_HEAVY_1|C:energy/Nikmi/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 51, 7, 51,
            "slots=2134|A:REPAIR_ARM,FTL_JUMPER,TELEPORT_HEAL|D:DEFENSE_1,COMBAT_1,ANTI_DRONE|C:rock/Josh Cubbin/000002,human/Ohm/002000,mantis/Teldarin/011000|S:battery@35#0,clonebay@50#2,hacking@80#1|res=4,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 64, 1, 43,
            "slots=4203|S:clonebay@50#2,mind@75#2,hacking@80#0|A:SCRAP_COLLECTOR,REPAIR_ARM,ION_ARMOR|W:LASER_BURST_3,MISSILES_3,LASER_HULL_2|C:mantis/Markand/000000,energy/Kevin Fish/000000,engi/Steven/000000|res=6,2,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 78, 3, 43,
            "slots=234|A:EXPLOSIVE_REPLICATOR,DRONE_RECOVERY,FTL_BOOSTER|C:engi/Joel/000100,human/Elnubnub/001000,mantis/Swampson/000200|S:battery@35#1,mind@75#0,hacking@80#2|res=6,5,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 11, 0, 43,
            "slots=3142|C:mantis/Roper/000000,energy/Enzo Martin/000000,human/Kapalka/000000|D:BATTLE,SHIP_REPAIR,COMBAT_1|S:clonebay@50#2,mind@75#1,hacking@80#2|A:REPAIR_ARM,ADV_SCANNERS,ION_ARMOR|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 23, 2, 19,
            "slots=102|D:ANTI_DRONE,REPAIR,DEFENSE_2|W:LASER_HEAVY_1,ION_4,LASER_HEAVY_2|A:SHIELD_RECHARGE,BATTERY_BOOSTER,EXPLOSIVE_REPLICATOR|res=5,6,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 37, 4, 53,
            "slots=4031|S:clonebay@50#2,mind@75#1,hacking@80#0|W:BOMB_ION,LASER_CHARGEGUN,BOMB_1|C:energy/Teldarin/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 51, 7, 50,
            "slots=2134|A:REPAIR_ARM,FTL_JUMPER,TELEPORT_HEAL|D:DEFENSE_1,COMBAT_1,ANTI_DRONE|C:rock/Josh Cubbin/000002,human/Ohm/002000,mantis/Teldarin/011000|S:clonebay@50#0,mind@75#2,hacking@80#2|res=7,3,2|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 64, 1, 42,
            "slots=4203|S:clonebay@50#0,mind@75#2,hacking@80#1|A:BACKUP_DNA,WEAPON_PREIGNITE,FTL_BOOSTER|W:LASER_HEAVY_1,LASER_CHARGEGUN,BOMB_STUN|C:rock/Stephen Eckman/000000,engi/Elizabeth/000000,engi/Ohm/000000|res=4,5,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 78, 3, 42,
            "slots=234|A:EXPLOSIVE_REPLICATOR,DRONE_RECOVERY,FTL_BOOSTER|C:engi/Joel/000100,human/Elnubnub/001000,mantis/Swampson/000200|S:clonebay@50#1,mind@75#0,hacking@80#2|res=7,5,4|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 11, 0, 43,
            "slots=4123|S:drones@85#2,teleporter@90#2,cloaking@150#2|D:BATTLE,COMBAT_1,DEFENSE_1|A:DRONE_RECOVERY,ADV_SCANNERS,REPAIR_ARM|C:human/Shelton/000000,rock/Hector/000000,engi/Rynhart/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 23, 2, 25,
            "slots=402|S:teleporter@90#2,battery@35#2,hacking@80#2|W:SHOTGUN_2,BEAM_2,LASER_HEAVY_1|A:EXPLOSIVE_REPLICATOR,WEAPON_PREIGNITE,LIFE_SCANNER|res=5,2,2|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 37, 4, 53,
            "slots=4031|S:drones@75#1,cloaking@150#1,battery@35#0|W:BEAM_3,BEAM_HULL,LASER_HEAVY_1|C:energy/Nikmi/001100,energy/Yevon Si/011000,human/Alex Rigopulos/101000|D:BOARDER,DEFENSE_2,BATTLE|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 51, 7, 48,
            "slots=4213|S:drones@75#1,cloaking@150#1,mind@75#1|A:LIFE_SCANNER,FLEET_DISTRACTION,SCRAP_COLLECTOR|D:DEFENSE_1,COMBAT_BEAM_2,DEFENSE_2|C:mantis/Roper/200000,mantis/Aisha/100100,engi/Alex Rigopulos/010100|res=5,6,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 64, 1, 46,
            "slots=2043|A:O2_MASKS,BATTERY_BOOSTER,FTL_BOOSTER|W:MISSILES_HULL,LASER_HEAVY_2,ION_CHARGEGUN|S:teleporter@90#1,cloaking@150#1,mind@75#1|C:rock/Steven/000000,mantis/Kusy/000000,rock/Tai Yi/000000|res=5,4,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,pilot,sensors,doors,clonebay", 78, 3, 46,
            "slots=234|A:EXPLOSIVE_REPLICATOR,DRONE_RECOVERY,FTL_BOOSTER|C:engi/Joel/000100,human/Elnubnub/001000,mantis/Swampson/000200|S:drones@75#1,mind@75#0,hacking@80#0|res=3,4,4|repair=3")
    )

    val STORES_EXTRA_VANILLA: List<KnownStore> = listOf(
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 11, 0, 43,
            "slots=4123|S:teleporter@90#2,cloaking@150#2,-|D:SHIP_REPAIR,BOARDER,-|A:FTL_JUMPER,ADV_SCANNERS,SCRAP_COLLECTOR|C:human/Kapalka/000000,human/Jax/000000,mantis/Nikola/000000|res=4,5,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 23, 2, 19,
            "slots=402|S:teleporter@90#1,cloaking@150#2,-|W:BOMB_HEAL,MISSILES_3,LASER_BURST_3|A:FTL_JAMMER,FTL_BOOSTER,ION_ARMOR|res=5,6,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 37, 4, 35,
            "slots=4031|S:teleporter@90#2,cloaking@150#1,-|W:ION_4,LASER_HULL_1,LASER_HEAVY_1|C:engi/Stelly/000001,engi/Ryan Maniscalco/000001,-|D:DEFENSE_2,COMBAT_BEAM,REPAIR|res=3,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 51, 7, 46,
            "slots=4213|S:teleporter@90#1,cloaking@150#1,-|A:FTL_JUMPER,FTL_BOOSTER,ION_ARMOR|D:COMBAT_BEAM,REPAIR,COMBAT_2|C:rock/Elnubnub/001010,engi/Butters/012000,energy/Emily/001200|res=5,4,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 64, 1, 34,
            "slots=2043|A:ION_ARMOR,AUTO_COOLDOWN,FTL_JAMMER|W:LASER_BURST_3,MISSILES_BREACH,MISSILES_HULL|S:teleporter@90#1,cloaking@150#1,-|C:mantis/Nick Lanng/000000,engi/Vitas Varnas/000000,-|res=7,4,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors", 78, 3, 32,
            "slots=234|A:SCRAP_COLLECTOR,ADV_SCANNERS,-|C:rock/Arries/000100,human/Elnubnub/001000,-|S:teleporter@90#1,cloaking@150#2,-|res=3,4,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 11, 0, 41,
            "slots=1230|D:COMBAT_2,COMBAT_1,DEFENSE_1|A:ADV_SCANNERS,ION_ARMOR,CLOAK_FIRE|C:rock/Chris Phillips/000000,mantis/Ferry/000000,rock/Hector/000000|W:BOMB_FIRE,BOMB_ION,-|res=4,3,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 23, 2, 36,
            "slots=130|D:REPAIR,COMBAT_1,COMBAT_BEAM|C:mantis/Kusy/000001,mantis/Xin Xin/000000,engi/Triko/000001|W:BEAM_LONG,MISSILES_HULL,MISSILES_3|res=3,6,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 37, 4, 44,
            "slots=3012|C:rock/Turing/010100,engi/Ariel/000010,-|W:BEAM_LONG,BOMB_ION,BOMB_FIRE|D:COMBAT_1,DEFENSE_1,-|A:AUTO_COOLDOWN,DRONE_RECOVERY,-|res=7,5,4|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 51, 7, 46,
            "slots=1023|D:REPAIR,BOARDER,-|W:BOMB_HEAL,MISSILES_BURST,BEAM_3|A:AUTO_COOLDOWN,CLOAK_FIRE,SHIELD_RECHARGE|C:rock/Elnubnub/001010,engi/Butters/012000,energy/Emily/001200|res=5,4,3|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 64, 1, 34,
            "slots=2130|A:FTL_BOOSTER,ADV_SCANNERS,-|D:DEFENSE_2,BATTLE,-|C:human/Nick Lanng/000000,mantis/Grozoly/000000,-|W:LASER_BURST_2_A,ION_2,ION_4|res=7,4,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking", 78, 3, 22,
            "slots=021|W:LASER_HULL_2,LASER_HEAVY_1,-|A:CLOAK_FIRE,ADV_SCANNERS,SHIELD_RECHARGE|D:BATTLE,COMBAT_2,COMBAT_1|res=7,2,3|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 11, 0, 36,
            "slots=3142|C:mantis/Roper/000000,energy/Enzo Martin/000000,human/Kapalka/000000|D:BATTLE,DEFENSE_2,-|S:-,-,-|A:DRONE_RECOVERY,ION_ARMOR,SCRAP_COLLECTOR|res=7,3,4|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 23, 2, 19,
            "slots=102|D:REPAIR,COMBAT_1,COMBAT_BEAM|W:BOMB_HEAL,MISSILES_3,LASER_BURST_3|A:FTL_JAMMER,FTL_BOOSTER,ION_ARMOR|res=5,6,3|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 37, 4, 46,
            "slots=4031|S:-,-,-|W:LASER_HEAVY_1,BEAM_2,BOMB_BREACH_2|C:engi/Lauren/000002,energy/Tomas/000010,human/Layla/001100|D:REPAIR,COMBAT_2,COMBAT_BEAM|res=5,4,2|repair=3"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 51, 7, 44,
            "slots=2134|A:ION_ARMOR,ADV_SCANNERS,-|D:REPAIR,BOARDER,BATTLE|C:human/Matthieu/000002,human/Ohm/002000,mantis/Teldarin/011000|S:-,-,-|res=5,3,2|repair=4"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 64, 1, 38,
            "slots=4203|S:-,-,-|A:ION_ARMOR,AUTO_COOLDOWN,FTL_JAMMER|W:LASER_BURST_3,MISSILES_BREACH,MISSILES_HULL|C:mantis/Adnan/000000,mantis/Markand/000000,energy/Kevin Fish/000000|res=6,6,2|repair=2"),
        KnownStore("PLAYER_SHIP_HARD", "shields,engines,oxygen,weapons,drones,medbay,pilot,sensors,doors,teleporter,cloaking,battery", 78, 3, 28,
            "slots=234|A:SCRAP_COLLECTOR,ADV_SCANNERS,-|C:rock/Arries/000100,human/Elnubnub/001000,-|S:-,-,-|res=3,5,4|repair=3")
    )
    }
}
