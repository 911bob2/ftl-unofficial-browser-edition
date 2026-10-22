package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewGhost
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.*
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.*
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.Skill
import xyz.znix.xftl.game.StoreData.Section
import xyz.znix.xftl.systems.Weapons
import xyz.znix.xftl.weapons.Damage
import kotlin.math.abs
import kotlin.math.min
import kotlin.random.Random

/**
 * Focused regressions for the gameplay parity port (native rules recorded
 * in docs/ORIGINAL_BEHAVIOR.md). Controlled fixtures, not played campaigns. Checks for
 * the 1:1 pass live in GameplayParity*Checks.kt files and are run from [run].
 */
object BrowserGameplayParityDiagnostics {
    internal fun fresh(main: MainGame, container: GameContainer, ship: String = "PLAYER_SHIP_HARD", difficulty: Difficulty = Difficulty.NORMAL): InGameState {
        main.startDiagnosticGame(ship, difficulty)
        repeat(90) {
            main.update(container, .05f)
            (main.currentState as InGameState).shipUI.diagnosticDialogue?.selectOption(0)
        }
        val state = main.currentState as InGameState
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        state.debugFlags.noEnemyFire.set = true
        // Debug flags outlive a game; earlier fixtures may have set these.
        state.debugFlags.noDmg.set = false
        state.debugFlags.noCrewDamage.set = false
        state.debugFlags.showEverything.set = false
        return state
    }

    internal fun reload(main: MainGame, state: InGameState): InGameState {
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(state.saveGameState())))
        return main.currentState as InGameState
    }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        // Every step runs even after a failure, so one run lists them all.
        fun step(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            step("events") { events(main, container, graphics) }
            step("maps") { maps(main, container) }
            step("rewards") { rewards(main, container) }
            step("systems") { systems(main, container) }
            step("hackingSystems") { hackingSystems(main, container) }
            step("crewRules") { crewRules(main, container) }
            step("enemyGeneration") { enemyGeneration(main, container) }
            step("GameplayParityEnemyGenChecks.run") { GameplayParityEnemyGenChecks.run(main, container) }
            step("GameplayParityEnemyAiChecks.run") { GameplayParityEnemyAiChecks.run(main, container) }
            step("GameplayParityAi2Checks.run") { GameplayParityAi2Checks.run(main, container) }
            step("aeAugments") { aeAugments(main, container) }
            step("stores") { stores(main, container) }
            step("flagshipSurges") { flagshipSurges(main, container) }
            step("GameplayParityMapEventsChecks.run") { GameplayParityMapEventsChecks.run(main, container) }
            step("GameplayParityEvents2Checks.run") { GameplayParityEvents2Checks.run(main, container) }
            step("GameplayParityStoresChecks.run") { GameplayParityStoresChecks.run(main, container) }
            step("GameplayParityCombatRulesChecks.run") { GameplayParityCombatRulesChecks.run(main, container) }
            step("GameplayParityShipRulesChecks.run") { GameplayParityShipRulesChecks.run(main, container) }
            step("GameplayParityDronesCrewChecks.run") { GameplayParityDronesCrewChecks.run(main, container) }
            step("GameplayParityCombat2Checks.run") { GameplayParityCombat2Checks.run(main, container) }
            step("GameplayParityCombat3Checks.run") { GameplayParityCombat3Checks.run(main, container) }
            step("GameplayParityWeapons6Checks.run") { GameplayParityWeapons6Checks.run(main, container) }
            step("GameplayParityGen3Checks.run") { GameplayParityGen3Checks.run(main, container) }
            step("GameplayParityAudio3Checks.run") { GameplayParityAudio3Checks.run(main, container) }
            step("GameplayParityFlow3Checks.run") { GameplayParityFlow3Checks.run(main, container) }
            step("GameplayParityFlow5Checks.run") { GameplayParityFlow5Checks.run(main, container) }
            step("GameplayParityUi3Checks.run") { GameplayParityUi3Checks.run(main, container) }
            step("GameplayParityAsb6Checks.run") { GameplayParityAsb6Checks.run(main, container) }
            step("GameplayParityAsb7Checks.run") { GameplayParityAsb7Checks.run(main, container) }
            step("GameplayParityShell6Checks.run") { GameplayParityShell6Checks.run(main, container) }
            step("GameplayParityJump6Checks.run") { GameplayParityJump6Checks.run(main, container, graphics) }
            step("GameplayParityCursor7Checks.run") { GameplayParityCursor7Checks.run(main, container) }
            step("GameplayParityEscape7Checks.run") { GameplayParityEscape7Checks.run(main, container) }
            step("GameplayParityAssets7Checks.run") { GameplayParityAssets7Checks.run(main, container, graphics) }
            step("GameplayParityCrew7Checks.run") { GameplayParityCrew7Checks.run(main, container) }
            step("GameplaySave8Checks.run") { GameplaySave8Checks.run(main, container) }
            step("GameplayParityCloak7Checks.run") { GameplayParityCloak7Checks.run(main, container, graphics) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "PASS " + results.joinToString("; ")
    }

    private fun events(main: MainGame, container: GameContainer, graphics: Graphics): String {
        var state = fresh(main, container)
        val events = state.eventManager

        // Ghost boarders: a default blueprint, human art, 50 health, no suffocation.
        val ghostBp = state.blueprintManager["ghost"] as CrewBlueprint
        check(ghostBp.rarity == null && state.lootPool.pool.none { it === ghostBp }) { "Ghost blueprint entered the loot pool" }
        val ghostEvent = events["GHOST_BOARDED"].resolve()
        val ghostResources = ghostEvent.resolveResources(state, Random(3))
        // The boarders are made when the event is applied (WorldManager 0x613440 -> 0x408260), so the resources
        // only carry their number (2..4) and class.
        check(ghostResources.boarderCount in 2..4 && ghostResources.boarderRace == "ghost") { "GHOST_BOARDED did not roll ghost boarders" }
        state.givePlayerResources(ghostResources)
        val ghosts = state.player.crew.filterIsInstance<CrewGhost>()
        check(ghosts.size == ghostResources.boarderCount) { "Ghosts not boarded: ${ghosts.size}" }
        check(ghosts.all { it.maxHealth == 50f && it.suffocationMultiplier == 0f && it.mode == AbstractCrew.SlotType.INTRUDER }) { "BrowserGameplayParityDiagnostics.kt:92" }
        repeat(20) { main.update(container, .05f) }
        main.render(container, graphics)
        state = reload(main, state)
        check(state.player.crew.count { it is CrewGhost } == ghosts.size) { "Ghosts lost across save/load" }

        // A list name shadows an event with the same name; self-entries roll again.
        val nebulaRebel = events["NEBULA_REBEL"]
        check(nebulaRebel is EventList && events["NEBULA_PIRATE"] is EventList && events["BOARDERS_PIRATE"] is EventList) { "BrowserGameplayParityDiagnostics.kt:100" }
        val seen = HashSet<String>()
        repeat(200) { seen += nebulaRebel.resolve().deserialisationId }
        check(seen.none { it == "rootEvent::NEBULA_REBEL" } && seen.size >= 5) { "NEBULA_REBEL resolved to $seen" }
        check(events.getOrNull("BIG_NAME_TEST") == null) { "nameEvents.xml should not be loaded" }

        // Unique events are produced once until the next map generation
        // (EventGenerator 0x5db020 returns them), and consumed by creation.
        val warning = events["NEBULA_AUTO_WARNING"] as Event
        check(warning.unique) { "unique attribute not parsed" }
        check(!warning.isConsumedUnique && state.eventCreator.create(warning, 3) != null) { "BrowserGameplayParityDiagnostics.kt:110" }
        check(warning.isConsumedUnique && state.eventCreator.create(warning, 3) == null) { "BrowserGameplayParityDiagnostics.kt:111" }
        repeat(400) { check(nebulaRebel.resolve() !== warning) { "Consumed unique event produced again" } }
        events.restoreEventState()
        check(!warning.isConsumedUnique) { "Unique event not returned by the map generation" }

        // Text lists give each entry once until only the last one remains.
        val texts = TextList("parity", listOf(EventText("a"), EventText("b"), EventText("c")))
        val rand = Random(9)
        val first = listOf(texts.resolve(rand), texts.resolve(rand))
        check(first.distinct().size == 2) { "Text list repeated an entry: $first" }
        val last = setOf("a", "b", "c").minus(first.toSet()).single()
        repeat(5) { check(texts.resolve(rand) == last) { "BrowserGameplayParityDiagnostics.kt:122" } }

        // Event damage: a named system the ship lacks costs only hull.
        state = fresh(main, container)
        val player = state.player
        fun fires() = player.rooms.sumOf { r -> r.fires.count { it != null } }
        fun breaches() = player.rooms.sumOf { r -> r.breaches.count { it != null } }
        check(player.systems.none { it.codename == "cloaking" }) { "BrowserGameplayParityDiagnostics.kt:129" }
        val hull = player.health
        state.givePlayerResources(ResourceSet().also { it.damage += EventHullDamage(2, "cloaking", true, true, false) })
        check(player.health == hull - 2 && fires() == 0 && breaches() == 0) { "Missing-system damage spread effects" }
        // effect="random" is a fire or a breach.
        state.givePlayerResources(ResourceSet().also { it.damage += EventHullDamage(0, "room", false, false, true) })
        check((fires() > 0) != (breaches() > 0)) { "Random effect gave fires=${fires()} breaches=${breaches()}" }

        // Breaching boarders: one breach, one hull, no teleport; others teleport in.
        state = fresh(main, container)
        val human = state.blueprintManager["human"] as CrewBlueprint
        val breachHull = state.player.health
        state.givePlayerResources(ResourceSet().also {
            it.intruders += LivingCrewInfo.generateRandom(human, state)
            it.intruders += LivingCrewInfo.generateRandom(human, state)
            it.intrudersBreach = true
        })
        val breachers = state.player.crew.filter { it.mode == AbstractCrew.SlotType.INTRUDER }
        check(breachers.size == 2 && state.player.health == breachHull - 1) { "Breach boarding: ${breachers.size} intruders, hull ${state.player.health}" }
        check(state.player.rooms.any { r -> r.breaches.any { it != null } && breachers.any { c -> c.room === r } }) { "No breach where the boarders arrived" }
        check(breachers.none { it.currentAction == AbstractCrew.Action.TELEPORTING }) { "BrowserGameplayParityDiagnostics.kt:149" }
        state = fresh(main, container)
        state.givePlayerResources(ResourceSet().also { it.intruders += LivingCrewInfo.generateRandom(human, state) })
        val arriving = state.player.crew.single { it.mode == AbstractCrew.SlotType.INTRUDER }
        main.update(container, 0f)
        check(arriving.currentAction == AbstractCrew.Action.TELEPORTING) { "Event boarders should teleport in" }
        // No more boarders once 15 intruders are aboard.
        state = fresh(main, container)
        repeat(15) { state.givePlayerResources(ResourceSet().also { r -> r.intruders += LivingCrewInfo.generateRandom(human, state) }) }
        val before = state.player.crew.count { it.mode == AbstractCrew.SlotType.INTRUDER }
        state.givePlayerResources(ResourceSet().also { it.intruders += LivingCrewInfo.generateRandom(human, state) })
        check(before == 15 && state.player.crew.count { it.mode == AbstractCrew.SlotType.INTRUDER } == 15) { "Intruder cap: $before" }

        // Waiting without fuel drifts out of a hazard and replaces the event.
        state = fresh(main, container)
        val beacon = state.currentBeacon
        beacon.event = events["AUTO_ASTEROID"].resolve()
        beacon.clearEnvironment()
        check(beacon.environmentType == xyz.znix.xftl.sector.Beacon.EnvironmentType.ASTEROID) { "Asteroid event did not make an asteroid environment: env=${beacon.environmentType} cleared=${beacon.eventCleared} forcedNebula=${beacon.forcedNebula} overtaken=${beacon.isOvertaken} exit=${beacon.isExit} sector=${beacon.sector.sectorNumber}" }
        state.player.fuelCount = 0
        JumpWindow(state, {}, {}).waitOutOfFuel()
        check(beacon.environmentType == xyz.znix.xftl.sector.Beacon.EnvironmentType.NORMAL) { "Waiting left the hazard: ${beacon.environmentType}" }
        val noFuel = events["NO_FUEL"] as EventList
        check(noFuel.events.any { it is Event && it.deserialisationId == beacon.event.deserialisationId }) { "Wait event ${beacon.event.deserialisationId}" }

        return "events: ghosts, list precedence, unique events, text lists, event damage, boarders, hazard waiting"
    }

    private fun maps(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val map = state.gameMap
        var generated = 0
        var clouds = 0
        for (seed in 1..12) {
            for (column in 1..7) {
                NativeRandom.seed(seed * 1000 + column)
                val info = map.sectors[column][0]
                val sector = map.generateSector(info, state)
                generated++
                checkLayout(sector)
                clouds += sector.nebulaClouds.size
            }
        }

        // Nebula sectors grow clouds; every beacon in a cloud is a nebula.
        val nebulaType = checkNotNull(map.sectorType("NEBULA_SECTOR"))
        val info = map.sectors[3][0]
        val oldType = info.type
        try {
            info.type = nebulaType
            for (seed in 1..6) {
                NativeRandom.seed(seed * 77)
                xyz.znix.xftl.sector.NativeSectorGenerator.lastNebulaCap = null
                val sector = map.generateSector(info, state)
                checkLayout(sector)
                check(sector.nebulaClouds.isNotEmpty() && sector.beacons.count { it.inNebula } >= 1) { "Nebula sector without clouds" }
                // StarMap::GenerateEvents (0x5f6540) caps only the named nebula events: names are dropped at random
                // until the free beacons minus the names is at least four (placeNebulas). Filler NEBULA events
                // share the ids of the named ones, so the cap is read from the generator itself: never fewer than
                // four free beacons left over, a dropped name only when the cap forced it, every kept name placed.
                val cap = checkNotNull(xyz.znix.xftl.sector.NativeSectorGenerator.lastNebulaCap) { "Nebula sector made no nebula placement" }
                check(cap.kept == 0 || cap.free - cap.kept >= 4) { "Nebula names exceed the cap: $cap free=${cap.free} kept=${cap.kept}" }
                check(cap.kept == cap.requested || cap.free - cap.kept == 4 || cap.kept == 0 && cap.free < 4 + 1) { "Nebula names dropped without the cap: requested=${cap.requested} free=${cap.free} kept=${cap.kept}" }
                check(cap.placed == cap.kept) { "Kept nebula names not all placed: kept=${cap.kept} placed=${cap.placed}" }
                check(sector.beacons.filter { it.forcedNebula }.all { it.environmentType == xyz.znix.xftl.sector.Beacon.EnvironmentType.NEBULA }) { "BrowserGameplayParityDiagnostics.kt:207" }
                clouds += sector.nebulaClouds.size
            }
        } finally {
            info.type = oldType
        }

        // The class split is 20/40/40.
        val counts = IntArray(3)
        val rand = Random(5)
        repeat(10000) { counts[GameMap.SectorClass.random(rand).ordinal]++ }
        check(counts[GameMap.SectorClass.NEBULA.ordinal] in 1800..2200 && counts[GameMap.SectorClass.HOSTILE.ordinal] in 3700..4300) { counts.toList().toString() }

        // Fleet stops at x = 60.
        val sector = state.currentBeacon.sector
        val oldX = sector.dangerZoneCentre.x
        sector.dangerZoneCentre.x = 60
        check(sector.getFleetAdvanceFor(state.currentBeacon) == 0) { "BrowserGameplayParityDiagnostics.kt:224" }
        sector.dangerZoneCentre.x = oldX

        return "maps: $generated sectors (connected, exits, paths, flagship), $clouds nebula clouds, class weights, fleet stop"
    }

    private fun checkLayout(sector: Sector) {
        val beacons = sector.beacons
        check(beacons.size in 19..24) { "Beacon count ${beacons.size}" }
        val seen = HashSet<xyz.znix.xftl.sector.Beacon>()
        val stack = arrayListOf(sector.startBeacon)
        while (stack.isNotEmpty()) {
            val b = stack.removeAt(stack.size - 1)
            if (seen.add(b)) stack.addAll(b.neighbours)
        }
        check(seen.size == beacons.size) { "Disconnected map" }
        for (b in beacons) for (n in b.neighbours) {
            check(n.neighbours.contains(b)) { "Asymmetric link" }
            check(b.pos.distToSq(n.pos) <= 165 * 165 && kotlin.math.abs(b.gridPos.x - n.gridPos.x) <= 1 && kotlin.math.abs(b.gridPos.y - n.gridPos.y) <= 1) { "Link too long" }
        }
        check(sector.startBeacon.gridPos.x == 0) { "Start column ${sector.startBeacon.gridPos.x}" }
        val path = checkNotNull(sector.findShortestPath(sector.startBeacon, sector.finishBeacon))
        if (sector.isLastStand) {
            check(sector.finishBeacon.isBase && sector.finishBeacon.gridPos.x in 2..4) { "Base column ${sector.finishBeacon.gridPos.x}" }
            val boss = sector.bosses.single() as FlagshipBoss
            check(boss.beacon.gridPos.x in 4..5) { "BrowserGameplayParityDiagnostics.kt:249" }
            check(sector.findShortestPath(boss.beacon, sector.finishBeacon)!!.size + 1 in 4..6) { "Flagship path" }
        } else {
            check(sector.finishBeacon.isExit && sector.finishBeacon.gridPos.x in 4..5) { "Exit column ${sector.finishBeacon.gridPos.x}" }
            check(path.size + 1 >= 5) { "Exit path ${path.size + 1}" }
        }
    }

    private fun rewards(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        check(LootDropGenerator.rewardLevel(3, Difficulty.EASY) == 4 && LootDropGenerator.rewardLevel(3, Difficulty.NORMAL) == 3 &&
            LootDropGenerator.rewardLevel(0, Difficulty.HARD) == 0 && LootDropGenerator.rewardLevel(3, Difficulty.HARD) == 2) { "BrowserGameplayParityDiagnostics.kt:259" }
        // Scrap = trunc(roll/1000 * (level*6 + 15)) with the roll in [500, 699] for LOW.
        repeat(200) { seed ->
            val low = LootDropGenerator.generateScrap(Random(seed), 0, 0)
            check(low in 7..10) { "LOW level 0 scrap $low" }
            val high = LootDropGenerator.generateScrap(Random(seed), 2, 4)
            check(high in 50..60) { "HIGH level 4 scrap $high" }
        }
        // A standard reward that becomes an item keeps one scrap roll.
        var itemSeeds = 0
        for (seed in 0 until 3000) {
            val reward = LootDropGenerator.generateRewards(state, Random(seed), RewardTier.MEDIUM, RewardType.STANDARD, 0, Difficulty.NORMAL, false)
            check(reward.scrap in 12..19) { "STANDARD MEDIUM level 0 scrap ${reward.scrap}" }
            if (reward.items.isNotEmpty()) itemSeeds++
        }
        check(itemSeeds in 40..140) { "3% item chance gave $itemSeeds/3000" }
        val explicit = LootDropGenerator.generateRewards(state, Random(1), RewardTier.LOW, RewardType.WEAPON, 0, Difficulty.NORMAL, true)
        check(explicit.items.isEmpty()) { "Explicit item still got an auto item" }
        return "rewards: difficulty levels, scrap rolls, item chance, explicit items"
    }

    private fun spawnCrewed(state: InGameState): xyz.znix.xftl.Ship {
        var seed = 71
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            check(seed < 150) { "No crewed fixture ship" }
        } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated)
        state.debugFlags.noEnemyFire.set = true
        return state.enemy!!
    }

    private fun systems(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val player = state.player

        // EngineSystem (0x63f580): ((power - 1) * 0.35 + 1.25) / 16 per unit,
        // manning 1.1/1.17/1.25, tripled without an enemy, at most 1.2.
        val engines = player.engines!!
        val skill = engines.getSkillLevel(Skill.ENGINES)
        var perUnit = ((engines.powerSupplied - 1) * 0.35f + 1.25f) * 0.0625f
        if (skill != null) perUnit *= listOf(1.1f, 1.17f, 1.25f)[skill.ordinal]
        check(abs(engines.ftlChargePerUnit(false) - perUnit) < 1e-5f) { "FTL charge ${engines.ftlChargePerUnit(false)} vs $perUnit" }
        check(abs(engines.ftlChargePerUnit(true) - min(perUnit * 3f, 1.2f)) < 1e-5f) { "Boosted FTL charge" }

        // AddLock adds units up to five; LockSystem replaces the lock.
        val shields = player.shields!!
        shields.lockUnits(0)
        shields.addLockUnits(2); check(shields.ionTimer == 10f) { "AddLock ${shields.ionTimer}" }
        shields.addLockUnits(4); check(shields.ionTimer == 25f) { "AddLock cap ${shields.ionTimer}" }
        shields.lockUnits(1); check(shields.ionTimer == 5f) { "BrowserGameplayParityDiagnostics.kt:309" }
        shields.lockUnits(0); check(shields.ionTimer == 0f) { "BrowserGameplayParityDiagnostics.kt:310" }

        // ProjectileFactory: an unpowered weapon drains six charge seconds
        // per second, scaled like the cooldown by the manning bonus.
        val weapons = player.weapons!!
        val weapon = player.hardpoints.mapNotNull { it.weapon }.first()
        if (weapon.isPowered) weapons.setWeaponPower(weapon, false)
        weapon.timeCharged = weapon.chargeTime
        val manning = weapons.getSkillLevel(Skill.WEAPONS)?.let { 1f / (1f - Weapons.SKILL_BONUSES[it.ordinal] / 100f) } ?: 1f
        weapons.update(.25f)
        val drained = weapon.chargeTime - weapon.timeCharged
        check(abs(drained - .25f * 6f * manning) < 1e-3f) { "Unpowered drain $drained" }

        // AE manning bonus: only a powered, undamaged subsystem gains a level.
        val sensors = player.sensors!!
        player.isAutomated = true
        check(sensors.effectivePower == sensors.undamagedEnergy + 1) { "Automated sensors not manned" }
        sensors.energyLevels = 2; sensors.damagedEnergyLevels = 1
        check(sensors.effectivePower == 1) { "Damaged manned sensors kept the bonus: ${sensors.effectivePower}" }
        player.isAutomated = false; sensors.damagedEnergyLevels = 0

        // DamageArea (0x4a6100): a Rock Plating proc also stops a breach
        // unless the breach is certain.
        val room = player.rooms.first { it.system == null }
        fun hits(chance: Int, trials: Int): Pair<Int, Int> {
            var breaches = 0
            var hull = 0
            repeat(trials) {
                room.breaches.indices.forEach { room.breaches[it] = null }
                player.health = player.maxHealth
                player.damage(room, Damage().also { it.hullDamage = 1; it.breachChance = chance; it.noCrewDamage = true })
                if (room.breaches.any { it != null }) breaches++
                if (player.health < player.maxHealth) hull++
            }
            room.breaches.indices.forEach { room.breaches[it] = null }
            player.health = player.maxHealth
            return Pair(breaches, hull)
        }
        val plain = hits(50, 4000)
        check(plain.first in 1850..2150 && plain.second == 4000) { "Plain hits $plain" }
        player.augments += state.blueprintManager["ROCK_ARMOR"] as xyz.znix.xftl.augments.AugmentBlueprint
        player.cargoUpdated()
        val armoured = hits(50, 4000)
        check(armoured.first in 1580..1820 && armoured.second in 3300..3500) { "Rock armour hits $armoured" }
        check(hits(100, 300).first == 300) { "Rock armour stopped a certain breach" }

        // Crystal Vengeance launches the real CRYSTAL_1 shard.
        val enemy = spawnCrewed(state)
        val shard = xyz.znix.xftl.weapons.VengeanceShardProjectile(enemy.rooms.first())
        check(shard.type.name == "CRYSTAL_1" && shard.type.shieldPiercing == 5) { "Shard blueprint ${shard.type.name}" }
        val shardDamage = Damage(shard.type)
        check(shardDamage.breachChance == 10 && shardDamage.stunChance == 20 && shard.speed == 35 * 16) { "Shard stats" }

        return "systems: FTL charge, locks, weapon drain, AE manning, rock armour, crystal shard"
    }

    private fun hackingSystems(main: MainGame, container: GameContainer): String {
        var state = fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.debugFlags.showEverything.set = true
        state.player.purchasedReactorPower = 25
        state.player.systemSlots.first { it.system.type == "hacking" }.let { it.room.setSystem(it) }
        var enemy = spawnCrewed(state)
        state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true

        // HackingSystem (0x62dc80): a finished pulse locks for four units;
        // Jump unlocks it.
        state.player.dronesCount = 3
        val hacking = state.player.hacking!!
        hacking.increasePower(); state.player.update(0f)
        hacking.selectTarget(enemy.weapons!!.room!!)
        repeat(300) { state.updateGameState(.05f) }
        check(hacking.droneLanded) { "Hacking probe did not land" }
        hacking.startHackingPulse()
        check(hacking.active) { "BrowserGameplayParityDiagnostics.kt:383" }
        repeat(100) { state.updateGameState(.05f) }
        check(!hacking.active && hacking.ionTimer in 14f..20f) { "Pulse end lock ${hacking.ionTimer}" }
        hacking.onJump()
        check(hacking.ionTimer == 0f) { "Jump kept the hacking lock" }

        // MindSystem: +30 health and double damage at three power, released
        // and unlocked by a hacking pulse, locked again when it ends.
        state = fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.debugFlags.showEverything.set = true
        state.player.purchasedReactorPower = 25
        enemy = spawnCrewed(state)
        val mind = state.player.mindControl!!
        mind.energyLevels = 3
        repeat(3) { mind.increasePower(); state.player.update(0f) }
        check(mind.powerSupplied == 3) { "Mind control power ${mind.powerSupplied}" }
        check(mind.duration == 28f && mind.healthBoost == 30f && mind.damageBoost == 2f) { "BrowserGameplayParityDiagnostics.kt:399" }
        enemy.rooms.forEach { it.update(0f) }
        val target = enemy.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant }
        val baseDamage = target.attackDamageMult
        val health = target.health
        // CombatControl::SelectRoom only queues the request (QueueMindControl 0x62fa60); MindSystem::OnLoop starts it.
        mind.selectRoom(target.room); mind.update(0f)
        val controlled = mind.controlledCrew!!
        val controlledBase = if (controlled === target) baseDamage else controlled.attackDamageMult
        val controlledHealth = if (controlled === target) health else controlled.health
        controlled.update(0f)
        check(controlled.maxHealth == controlled.baseMaxHealth + 30f && controlled.health == controlledHealth + 30f) { "Health boost ${controlled.maxHealth}/${controlled.health}" }
        check(abs(controlled.attackDamageMult - controlledBase * 2f) < 1e-4f) { "Damage boost ${controlled.attackDamageMult}" }
        state = reload(main, state)
        val loadedMind = state.player.mindControl!!
        val loaded = loadedMind.controlledCrew!!
        loaded.update(0f)
        check(loaded.maxHealth == loaded.baseMaxHealth + 30f && loaded.health == controlledHealth + 30f) { "Boost changed across save/load" }
        loadedMind.hackPulseChanged(true)
        check(loaded.mindControlledBy == null && loadedMind.ionTimer == 0f && !loadedMind.active) { "Pulse did not release" }
        loaded.update(0f)
        check(loaded.maxHealth == loaded.baseMaxHealth && loaded.health <= loaded.baseMaxHealth) { "Boost not removed" }
        loadedMind.hackPulseChanged(false)
        check(loadedMind.ionTimer == 20f) { "Pulse end lock ${loadedMind.ionTimer}" }
        loadedMind.onJump()
        check(loadedMind.ionTimer == 0f) { "BrowserGameplayParityDiagnostics.kt:423" }

        // TeleportSystem::SetHackingLevel: a pulse unlocks the teleporter.
        val teleporter = state.player.teleporter!!
        teleporter.lockUnits(3)
        teleporter.hackPulseChanged(true)
        check(teleporter.ionTimer == 0f) { "Teleporter pulse kept its lock" }

        return "hacking: pulse lock/jump, mind control boosts/pulse/save, teleporter pulse"
    }

    private fun crewRules(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val player = state.player
        fun recruit(race: String, room: xyz.znix.xftl.layout.Room, intruder: Boolean): LivingCrew {
            val info = LivingCrewInfo.generateRandom(state.blueprintManager[race] as CrewBlueprint, state)
            if (!intruder) return player.addCrewMemberInRoom(info, room, false)
            return player.addCrewMember(info, false, true).also { it.jumpTo(player.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER)) }
        }
        val rooms = player.rooms.sortedByDescending { it.cellCount }

        // GetFireRepairMultiplier: 1.2 by default, Rock 2.0, Crystal 1.0.
        check(recruit("human", rooms[1], false).fireFightingSpeed == 1.2f) { "Default fire repair" }
        check(recruit("rock", rooms[1], false).fireFightingSpeed == 2f && recruit("crystal", rooms[2], false).fireFightingSpeed == 1f) { "Race fire repair" }

        // EnergyAlien death damage: 15 HP to crew of the other ship only.
        val room = rooms[0]
        room.crew.toList().forEach { it.jumpTo(player.findSpaceForCrew(rooms.last(), it.mode)) }
        val own = recruit("human", room, false)
        val boarder = recruit("human", room, true)
        val zoltan = recruit("energy", room, true)
        // Pin all three into the room (spawning respects slot reservations).
        listOf(own, boarder, zoltan).forEachIndexed { slot, crew -> crew.jumpTo(room, room.slotToPoint(slot)) }
        player.rooms.forEach { it.updateCrewInRoom() }
        check(room.crew.containsAll(listOf(own, boarder, zoltan))) { "Blast fixture crew not together" }
        val ownHealth = own.health
        val boarderHealth = boarder.health
        zoltan.onStartedDying()
        check(own.health == ownHealth - 15f && boarder.health == boarderHealth) { "Zoltan blast own=${own.health} boarder=${boarder.health}" }

        // CrewMember::IncreaseSkill: only the player's crew learn.
        val before = boarder.info.skills.getValue(Skill.COMBAT)
        boarder.addSkillPoint(Skill.COMBAT)
        check(boarder.info.skills.getValue(Skill.COMBAT) == before) { "Boarder learned a skill" }
        val ownBefore = own.info.skills.getValue(Skill.PILOTING)
        own.addSkillPoint(Skill.PILOTING)
        check(own.info.skills.getValue(Skill.PILOTING) > ownBefore) { "BrowserGameplayParityDiagnostics.kt:469" }

        // CrewAI: a mixed crew keeps Engi/Zoltan out of fights.
        val enemy = spawnCrewed(state)
        val enemyRoom = enemy.rooms.first { r -> r.crew.isEmpty() && r.fires.all { it == null } }
        val engi = enemy.addCrewMemberInRoom(LivingCrewInfo.generateRandom(state.blueprintManager["engi"] as CrewBlueprint, state), enemyRoom, false)
        enemy.crewAI.update()
        val mixed = enemy.crew.filterIsInstance<LivingCrew>().any { it.codename != "engi" && it.codename != "energy" }
        check(enemy.crewAI.avoidBadFighters == mixed) { "Bad-at-combat flag ${enemy.crewAI.avoidBadFighters}" }
        if (mixed) {
            val task = xyz.znix.xftl.ai.CombatTask(enemy.crewAI, enemyRoom)
            check(task.priorityFor(engi) == 100) { "Engi fight priority ${task.priorityFor(engi)}" }
        }

        return "crew: fire repair races, Zoltan blast ownership, player-only skills, bad-at-combat"
    }

    private fun enemyGeneration(main: MainGame, container: GameContainer): String {
        var state = fresh(main, container)
        var atCeiling = 0
        var checked = 0
        for (difficulty in Difficulty.values()) for (sector in 0..3) for (seed in 0 until 25) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), difficulty, sector, 4000 + seed)
            val enemy = state.enemy!!
            val level = sector + difficulty.sectorOffset

            // CreateShip: blueprint hull plus the effective level, 20 at most.
            check(enemy.maxHealth == xyz.znix.xftl.shipgen.ShipGenerator.nativeMaxHull(enemy.type.maxHealth, level) &&
                    enemy.health == enemy.maxHealth) { "Hull ${enemy.health}/${enemy.maxHealth} for ${enemy.name} at $level" }

            // GenerateSystemMaxes: start + level/8 of the range + rand%1/2/3.
            val shields = enemy.shields ?: continue
            val spec = shields.configuration.spec
            val max = spec.aiMaxPower ?: continue
            val start = spec.startingPower
            val bonus = when {
                level < 0 -> 0
                difficulty == Difficulty.EASY -> 1
                level <= 1 -> 1
                else -> 2
            }
            val ceiling = min(maxOf(start + (level / 8f * (max - start)).toInt() + bonus, start), max)
            check(shields.energyLevels <= ceiling) { "${enemy.name} shields ${shields.energyLevels} > $ceiling ($difficulty, sector $sector)" }

            // ShipManager::InstantPowerShields: enemies appear with every bubble up.
            check(shields.activeShields == min(shields.selectedShieldBars, shields.suppliedShieldBars) &&
                    (shields.energyLevels < 2 || shields.activeShields > 0) && shields.rechargeTimer == 0f) {
                "${enemy.name} shields not charged: ${shields.activeShields}/${shields.selectedShieldBars} ($difficulty, sector $sector)"
            }
            checked++
            if (shields.energyLevels == ceiling) atCeiling++
        }
        check(checked > 100 && atCeiling > 0) { "Generation sample $checked/$atCeiling" }

        // The scaled hull survives a save.
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 5, 77)
        val hull = state.enemy!!.maxHealth
        check(hull == xyz.znix.xftl.shipgen.ShipGenerator.nativeMaxHull(state.enemy!!.type.maxHealth, 5)) { "BrowserGameplayParityDiagnostics.kt:526" }
        state = reload(main, state)
        check(state.enemy!!.maxHealth == hull && state.enemy!!.health == hull) { "Scaled hull lost on reload" }

        return "generation: level hull, shield ceilings across difficulties, hull save"
    }

    private fun aeAugments(main: MainGame, container: GameContainer): String {
        var state = fresh(main, container)
        fun give(name: String) {
            state.player.augments += state.blueprintManager[name] as xyz.znix.xftl.augments.AugmentBlueprint
            state.player.cargoUpdated()
        }

        // Fire Suppression: every burning square loses 0.08 health a second.
        give("FIRE_EXTINGUISHERS")
        val room = state.player.rooms.first { it.system == null }
        room.spawnFireAt(0)
        val fire = room.fires[0]!!
        state.player.update(0.5f)
        check(abs(fire.health - 0.96f) < 1e-3f || room.fires[0] == null) { "Fire suppression health ${fire.health}" }

        // Distraction Buoys: entering sectors 1-7 delays the fleet a jump.
        give("FLEET_DISTRACTION")
        val next = state.gameMap.generateSector(state.gameMap.sectors[1].first(), state)
        state.currentBeacon = next.startBeacon
        check(next.fleetAdvanceModifier == -1 && next.getFleetAdvanceFor(next.startBeacon) == 0) { "Distraction modifier ${next.fleetAdvanceModifier}" }

        // Zoltan Shield Bypass: mind control works through a super shield.
        state = fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        state.debugFlags.showEverything.set = true
        val enemy = spawnCrewed(state)
        enemy.superShield = 5
        enemy.rooms.forEach { it.update(0f) }
        val mind = state.player.mindControl!!
        mind.increasePower(); state.player.update(0f)
        val target = enemy.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant }
        mind.selectRoom(target.room); mind.update(0f)
        check(!mind.active) { "Mind control went through a super shield" }
        state.player.augments += state.blueprintManager["ZOLTAN_BYPASS"] as xyz.znix.xftl.augments.AugmentBlueprint
        state.player.cargoUpdated()
        mind.selectRoom(target.room); mind.update(0f)
        check(mind.active) { "Zoltan bypass did not let mind control through" }

        return "AE augments: fire suppression, distraction buoys, Zoltan bypass"
    }

    private fun stores(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        check(state.player.systems.none { it is xyz.znix.xftl.systems.Drones }) { "Store fixture ship has drones" }

        // Store::OnInit: 2-4 sections in all; a drones section only with systems.
        for (seed in 0 until 300) {
            val store = StoreData()
            store.generateRandomContents(state, Random(seed))
            check(store.sections.size in 2..4 && store.sections.distinct().size == store.sections.size) { "Store sections ${store.sections}" }
            check(Section.DRONES !in store.sections || Section.SYSTEMS in store.sections) { "Drones without systems: ${store.sections}" }
            if (Section.CREW in store.sections)
                check(store.crew.all { c -> c == null || c.skills.values.all { it == 0f } }) { "Sector-1 store crew had skills" }
        }

        // Random skills by world level (0x6058a0): level 7 gives 2-3 boosts.
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        state.currentBeacon = sector.startBeacon
        var skilled = 0
        for (seed in 0 until 60) {
            val store = StoreData()
            store.generateRandomContents(state, Random(seed))
            for (c in store.crew) {
                if (c == null) continue
                val levels = c.skills.values.sumOf { (it / 0.5f).toDouble() }.toInt()
                check(levels in 2..3 && c.skills.values.all { it <= 1f }) { "Level-7 crew skill levels $levels" }
                skilled++
            }
        }
        check(skilled > 0) { "No store crew sampled" }

        return "stores: section count, drones rule, store crew skills"
    }

    private fun flagshipSurges(main: MainGame, container: GameContainer): String {
        val state = fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = 2
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        val flagship = state.enemy!!
        state.shipUI.diagnosticDialogue?.selectOption(0)

        // Stage 2 (0x4a5b00): six surge drones on normal, two passes each. The surge timer loops
        // between 20 and 30 s (BossShip +0x138), so the first surge lands somewhere in that window.
        val before = state.player.externalDrones.size
        var waited = 0f
        while (state.player.externalDrones.size == before && waited < 31f) {
            boss.updateShip(flagship, 0.5f)
            waited += 0.5f
        }
        check(waited in 20f..30.5f) { "First surge after $waited s" }
        val surge = state.player.externalDrones.filterIsInstance<xyz.znix.xftl.drones.CombatDrone>().filter { it.browserPassesLeft != null }
        check(surge.size == 6 && surge.all { it.browserPassesLeft == 2 }) { "Drone surge ${surge.size}" }

        // Stage 3 (0x4a6a90/0x49cb50): three barrages of seven heavy lasers,
        // then a 12-point super shield.
        boss.stage = 3
        val shots = flagship.projectiles.size
        repeat(3) { boss.updateShip(flagship, 31f) }
        check(flagship.projectiles.size - shots == 21) { "Laser surge ${flagship.projectiles.size - shots}" }
        boss.updateShip(flagship, 31f)
        check(flagship.maxSuperShield == 12 && flagship.superShield == 12) { "Shield surge ${flagship.superShield}/${flagship.maxSuperShield}" }

        return "flagship: drone surge, laser barrages, super shield surge"
    }
}
