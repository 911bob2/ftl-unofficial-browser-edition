package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.SystemBlueprint
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BeamBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.LaserBlueprint

/**
 * B3, found on the way of user bug B2 (O-B2-2): the blueprint defaults of a tag left out, in every mode.
 * - ProcessWeaponBlueprint (1.6.14 0x607df0, 1.6.9 0x5e41d0) keeps the WeaponBlueprint constructor's values
 *   (0x763dd0 / 0x72de30): power 0, cooldown 0, shots 0 and length -1; every BEAM gets shots 2 after the loop. HS oracle
 *   ps-b3-arm: PSB3_W_BARE 0/0/0/-1, PSB3_W_BEAM_BARE shots 2 length -1, a beam with `<shots>3` shots 2; MV's drone,
 *   artillery and barrage weapons power 0.
 * - ProcessDroneBlueprint (1.6.9 0x5de790): level stays the constructor's 1; `<level>` 2 sets targetType 4, in file order
 *   with `<target>` (PSB3_D_LEVEL2 4, PSB3_D_TARGET_LEVEL2 4, PSB3_D_LEVEL2_TARGET 3).
 * - A blueprint without `<short>` / `<title>` keeps Description's empty texts (0x7239e0; 1.6.14 frames ps-b3-van).
 * - A weapon of power 0 on the player's ship (HS oracle ps-b3-arm, PSB3_W_LASER): powered with no bar of the weapon
 *   system (requiredPower 0), it charges with the system at 0 bars; under Hyperspace its volley stays queued until the
 *   system gets a bar, as WeaponSystem::GetPreparedProjectile (1.6.14 0x636a10) takes no shot while the system is not
 *   Functioning.
 * - ProcessAugmentBlueprint (1.6.14 0x602430), ProcessSystemBlueprint (1.6.9 0x5e5470), Description (0x7239e0): cost 0,
 *   stacking false, start and max power 0, no upgrade costs, where we threw on the missing tag.
 */
internal object GameplayParityPsB3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        case("weapon") { weaponDefaults() }
        case("drone") { droneDefaults() }
        case("others") { otherDefaults() }
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            case("weaponWithoutPower") { weaponWithoutPower(main, container) }
            case("zoltanRegistry") { zoltanRegistry(main, container) }
            case("wreck") { wreck(main, container) }
            case("flagshipWreck") { flagshipWreck(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-b3 ok: " + results.joinToString(", ")
    }

    private fun bp(kind: String, name: String, vararg tags: Pair<String, String>): Element {
        val e = Element(kind).setAttribute("name", name)
        for ((tag, text) in tags) e.addContent(Element(tag).setText(text))
        return e
    }

    private fun weaponDefaults(): String {
        val laser = LaserBlueprint(bp("weaponBlueprint", "PSB3_W_BARE", "type" to "LASER"))
        check(laser.power == 0) { "a weapon without <power> needs ${laser.power} power, native 0" }
        check(laser.chargeTime == 0f) { "a weapon without <cooldown> has cooldown ${laser.chargeTime}, native 0" }
        check(laser.nativeShots == 0) { "a weapon without <shots> has WeaponBlueprint::shots ${laser.nativeShots}, native 0" }
        val beam = BeamBlueprint(bp("weaponBlueprint", "PSB3_W_BEAM_BARE", "type" to "BEAM"))
        check(beam.nativeShots == 2 && beam.nativeLength == -1) { "a bare beam: shots ${beam.nativeShots} length ${beam.nativeLength}, native 2 and -1" }
        val beam3 = BeamBlueprint(bp("weaponBlueprint", "PSB3_W_BEAM_SHOTS", "type" to "BEAM", "shots" to "3", "length" to "40", "power" to "2"))
        check(beam3.nativeShots == 2 && beam3.nativeLength == 40 && beam3.power == 2) {
            "a beam with <shots>3: shots ${beam3.nativeShots} length ${beam3.nativeLength} power ${beam3.power}, native 2, 40, 2"
        }
        return "weapon defaults"
    }

    private fun droneDefaults(): String {
        fun drone(name: String, vararg tags: Pair<String, String>) = DroneBlueprint(bp("droneBlueprint", name, "type" to "DEFENSE", *tags))
        val cases = listOf(
            drone("PSB3_D_BARE") to 1,
            drone("PSB3_D_LEVEL2", "level" to "2") to 4,
            drone("PSB3_D_TARGET_LEVEL2", "target" to "DRONES", "level" to "2") to 4,
            drone("PSB3_D_LEVEL2_TARGET", "level" to "2", "target" to "DRONES") to 3,
            drone("PSB3_D_MISSILES", "target" to "LASERS", "target" to "MISSILES") to 1,
        )
        for ((d, type) in cases) {
            check(d.level == 1) { "${d.name}: level ${d.level}, native 1" }
            check(d.targetType == type) { "${d.name}: targetType ${d.targetType}, native $type" }
            check(d.power == 0) { "${d.name}: power ${d.power}, native 0" }
        }
        return "drone defaults"
    }

    private fun weaponWithoutPower(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        // No `<short>` / `<title>`: the Description constructor's empty texts (the weapon box shows no name; 1.6.14
        // capture ps-b3-van b3v-arm, DRONE_LASER)
        val bare = LaserBlueprint(bp("weaponBlueprint", "PSB3_W_BARE", "type" to "LASER"))
        check(bare.translateShort(state) == "" && bare.translateTitle(state) == "") {
            "a weapon without <short>/<title> is named '${bare.translateShort(state)}'/'${bare.translateTitle(state)}', native empty"
        }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        val enemy = checkNotNull(state.enemy) { "no enemy" }
        val player = state.player
        val weapons = checkNotNull(player.weapons) { "the player has no weapon system" }
        val bp = state.blueprintManager["LASER_BURST_1"] as AbstractWeaponBlueprint
        val oldPower = bp.power
        bp.power = 0
        try {
            for (hp in player.hardpoints) hp.weapon?.let { if (it.isPowered) weapons.setWeaponPower(it, false) }
            for (hp in player.hardpoints) hp.weapon = null
            val laser = bp.buildInstance(player)
            player.hardpoints[0].weapon = laser
            player.cargoUpdated()
            check(weapons.setWeaponPower(laser, true)) { "the laser without power did not power" }
            main.update(container, 1f / 60f)
            check(laser.isPowered && weapons.powerSupplied == 0) {
                "the laser without power: powered ${laser.isPowered}, weapon system bars ${weapons.powerSupplied}, native powered with 0"
            }
            var frames = 0
            while (laser.timeCharged < laser.chargeTime && frames++ < 60 * 30) main.update(container, 1f / 60f)
            check(laser.timeCharged >= laser.chargeTime) { "the laser without power did not charge with the weapon system at 0 bars" }
            // Every shot that appears in the player's projectiles, sampled each frame (a laser leaves that list once it
            // crosses to the enemy's space)
            val before = HashSet(player.projectiles)
            val seen = HashSet<Any>()
            fun frames(n: Int) = repeat(n) {
                main.update(container, 1f / 60f)
                for (p in player.projectiles) if (p !in before) seen += p
            }
            val queued = laser as AbstractProjectileWeaponInstance
            (laser as IRoomTargetingWeapon).fire { enemy.rooms.first() }
            frames(180)
            val left = seen.size
            val hs = xyz.znix.xftl.hs.HS.active
            if (hs) check(left == 0 && queued.hsQueuedShots > 0) {
                "the volley at 0 bars: $left shots left, ${queued.hsQueuedShots} queued; native held (0 left, queued)"
            }
            // Another weapon powered in the second slot gives the weapon system a bar, as the oracle's
            // ForceIncreasePower(1) did: the held volley leaves. (Its own blueprint: the first laser keeps power 0.)
            if (hs) check(laser.isPowered && queued.hsQueuedShots > 0) { "the held laser lost its power or its volley" }
            val second = (state.blueprintManager["LASER_BURST_2"] as AbstractWeaponBlueprint).buildInstance(player)
            player.hardpoints[1].weapon = second
            player.cargoUpdated()
            check(weapons.setWeaponPower(second, true)) { "the second laser did not power" }
            frames(60)
            check(weapons.powerSupplied > 0) { "the weapon system has no bar with a second laser" }
            val after = seen.size
            if (hs) check(after == 1 && queued.hsQueuedShots == 0) {
                "with a bar the held volley gave $after shots (${queued.hsQueuedShots} still queued), native 1"
            }
            return "power-0 laser powered with 0 bars, charged" + if (hs) ", volley held until a bar ($after)" else ""
        } finally {
            bp.power = oldPower
        }
    }

    /**
     * B3b (O-B3-1): under Hyperspace a race hyperspace.xml leaves out (Multiverse: energy) gets a default definition the
     * first time CustomCrewManager::GetDefinition is asked for it, which CrewMemberFactory::OnLoop does right after a new
     * crew member (CustomCrew.cpp:6454-6463). So only the first such Zoltan is an EnergyAlien (70 health, a bonus bar);
     * every later one is a plain crew member of the default definition (100 health, no power). HS oracle ps-b3-zw/zx:
     * ZOLTAN_BOMBER's three Zoltans 70/P, 100/p, 100/p and its shields and weapons on reactor bars only.
     */
    private fun zoltanRegistry(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val bp = state.blueprintManager["energy"] as xyz.znix.xftl.crew.CrewBlueprint
        val explicit = xyz.znix.xftl.hs.crew.CustomCrewManager.definitionOrNull("energy")?.implicit == false
        if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.CustomCrewManager.forgetImplicit("energy")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        val enemy = checkNotNull(state.enemy) { "no enemy" }
        val shields = checkNotNull(enemy.shields) { "the enemy has no shields" }
        val room = checkNotNull(shields.room)
        // a room away from the shields (Hyperspace's PIRATE has no room without a system)
        val free = enemy.rooms.firstOrNull { it.system == null } ?: enemy.rooms.first { it !== room }
        val first = enemy.addCrewMemberInRoom(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(bp, state), free, false)
        val second = enemy.addCrewMemberInRoom(xyz.znix.xftl.crew.LivingCrewInfo.generateRandom(bp, state), room, false)
        repeat(30) { main.update(container, 1f / 60f) }
        val hsQuirk = xyz.znix.xftl.hs.HS.active && !explicit
        if (hsQuirk) {
            check(first is xyz.znix.xftl.crew.CrewZoltan && first.maxHealth == 70f) {
                "the first Zoltan of a race hyperspace.xml leaves out: ${first::class.simpleName} ${first.maxHealth}, native EnergyAlien 70"
            }
            check(second !is xyz.znix.xftl.crew.CrewZoltan && second.maxHealth == 100f && second.bonusSystemPower == 0) {
                "a later Zoltan: ${second::class.simpleName} health ${second.maxHealth} bonus ${second.bonusSystemPower}, native plain, 100, 0"
            }
            check(shields.forcedPower == 0) { "the shields got ${shields.forcedPower} bonus bars from the plain Zoltan, native 0" }
        } else {
            check(first::class == second::class && first.bonusSystemPower == second.bonusSystemPower) {
                "two Zoltans differ: ${first::class.simpleName}/${second::class.simpleName}"
            }
        }
        return if (hsQuirk) "first Zoltan EnergyAlien, the next plain (no bonus)" else "Zoltans alike"
    }

    /**
     * B3b: a destroyed ship's weapons and drones go off but their systems keep the bars (WeaponSystem::ShipDestroyed
     * 1.6.9 0x60f670, DroneSystem::ShipDestroyed 0x611590, from ShipManager::OnLoop 0x4a05b0 every frame the hull is
     * below 1). HS oracle ps-b3-wk: AUTO_ASSAULT's weapons keep 4 bars and its drones 4, every weapon and drone unpowered,
     * a deployed drone stays out.
     */
    private fun wreck(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val spec = xyz.znix.xftl.shipgen.EnemyShipSpec(Element("ship").setAttribute("name", "PSB3_WRECK").setAttribute("blueprint", "AUTO_ASSAULT"),
            state.blueprintManager, state.eventManager)
        state.debugSpawnShip(spec, Difficulty.NORMAL, 4, 71)
        val enemy = checkNotNull(state.enemy) { "no enemy" }
        val weapons = checkNotNull(enemy.weapons) { "AUTO_ASSAULT has no weapons" }
        val drones = checkNotNull(enemy.drones) { "AUTO_ASSAULT has no drones" }
        // A known armament, powered here (the generated one and the AI's choice are not the point): two lasers and a
        // defence drone, with room in the reactor and the systems.
        enemy.purchasedReactorPower = 25
        weapons.energyLevels = maxOf(weapons.energyLevels, 4)
        drones.energyLevels = maxOf(drones.energyLevels, 4)
        enemy.dronesCount = 10
        for (hp in enemy.hardpoints) hp.weapon?.let { if (it.isPowered) weapons.setWeaponPower(it, false) }
        for (hp in enemy.hardpoints) hp.weapon = null
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        val laser = (state.blueprintManager["LASER_BURST_1"] as AbstractWeaponBlueprint)
        enemy.hardpoints[0].weapon = laser.buildInstance(enemy)
        if (enemy.hardpoints.size > 1) enemy.hardpoints[1].weapon = laser.buildInstance(enemy)
        enemy.cargoUpdated()
        check(enemy.addBlueprint(state.blueprintManager["DEFENSE_1"] as DroneBlueprint, true)) { "DEFENSE_1 not equipped" }
        enemy.updateAvailablePower()
        for (hp in enemy.hardpoints) hp.weapon?.let { check(weapons.setWeaponPower(it, true)) { "the enemy's laser did not power" } }
        val slot = drones.drones.indexOfFirst { it != null }
        check(drones.setDronePower(slot, true)) { "DEFENSE_1 did not launch" }
        repeat(30) { main.update(container, 1f / 60f) }
        val w = weapons.powerSupplied
        val d = drones.powerSupplied
        val out = drones.drones.mapNotNull { it?.instance }
        check(w > 0 && d > 0 && out.isNotEmpty()) { "the enemy did not stay powered: weapons $w drones $d, ${out.size} drones out" }
        enemy.health = 0
        repeat(30) { main.update(container, 1f / 60f) }
        check(enemy.isDead) { "the enemy is not dead (hull ${enemy.health})" }
        check(weapons.powerSupplied == w && drones.powerSupplied == d) {
            "the wreck's bars: weapons ${weapons.powerSupplied} drones ${drones.powerSupplied}, native kept ($w, $d)"
        }
        check(enemy.hardpoints.none { it.weapon?.isPowered == true }) { "a wreck's weapon is still powered" }
        check(out.none { it.isPowered }) { "a wreck's drone is still powered" }
        check(drones.drones.mapNotNull { it?.instance }.size == out.size) { "a wreck's drone was recalled, native stays out" }
        return "wreck keeps weapons $w and drones $d bars, all off"
    }

    /**
     * B3b (b1k): a destroyed flagship's artillery loses its power. ShipAI::OnLoop (1.6.9 0x6012e0) treats a ship whose
     * hull is below 1 as not hostile and calls CombatAI::DeactiveWeapons (0x605f40), which takes every artillery
     * system's bars with ForceDecreasePower(1) until it fails. HS oracle ps-b3-flag: each stage's four (stage 3 two)
     * artillery go from 3 bars to 0, the reactor 12 (6) bars freer; the other systems keep theirs.
     */
    private fun flagshipWreck(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<xyz.znix.xftl.game.FlagshipBoss>().first()
        boss.stage = 1
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "the flagship did not spawn" }
        val flagship = checkNotNull(state.enemy)
        // Every page of the arrival event (Multiverse's flagship event has more than one): the fight runs only then.
        var pages = 0
        while (pages++ < 10) {
            val dialogue = state.shipUI.diagnosticDialogue ?: break
            dialogue.selectOption(0)
            main.update(container, 1f / 60f)
        }
        check(state.shipUI.diagnosticDialogue == null) { "the flagship's arrival event stays open" }
        repeat(60 * 3) { main.update(container, 1f / 60f) }
        val artillery = flagship.artillery
        check(artillery.isNotEmpty() && artillery.all { it.powerSupplied > 0 }) { "the flagship's artillery is not powered: ${artillery.map { it.powerSupplied }}" }
        val others = flagship.mainSystems.filter { it !is xyz.znix.xftl.systems.Artillery }.associateWith { it.powerSupplied }
        flagship.health = 0
        repeat(30) { main.update(container, 1f / 60f) }
        check(artillery.all { it.powerSupplied == 0 }) {
            "the wreck's artillery bars ${artillery.map { it.powerSupplied }} (forced ${artillery.map { it.forcedPower }}, dead ${flagship.isDead} " +
                "hull ${flagship.health}, still the enemy ${state.enemy === flagship}, hostile ${state.isEnemyHostile}), native 0"
        }
        val changed = others.filter { (s, p) -> s.powerSupplied != p }
        check(changed.isEmpty()) { "the wreck's other systems changed: ${changed.map { (s, p) -> "${s.blueprint.name} $p->${s.powerSupplied}" }}" }
        return "flagship wreck: artillery ${artillery.size} to 0, other systems kept"
    }

    private fun otherDefaults(): String {
        val aug = AugmentBlueprint(bp("augBlueprint", "PSB3_A_BARE"))
        check(aug.cost == 0 && !aug.stackable && aug.nativeValue == 0f) { "a bare augment: cost ${aug.cost} stackable ${aug.stackable}, native 0 and false" }
        val sys = SystemBlueprint(bp("systemBlueprint", "PSB3_S_BARE", "type" to "pilot"))
        check(sys.startPower == 0 && sys.maxPower == 0 && sys.cost == 0 && sys.upgradeCost.isEmpty()) {
            "a bare system: ${sys.startPower}/${sys.maxPower} cost ${sys.cost} upgrades ${sys.upgradeCost}, native 0/0 0 none"
        }
        return "augment and system defaults"
    }
}
