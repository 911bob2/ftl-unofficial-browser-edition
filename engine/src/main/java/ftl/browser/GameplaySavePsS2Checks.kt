package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.savegame.MissingContent
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractProjectileWeaponInstance
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Parity sweep S2 (saves, Continue and run determinism): states a save and Continue keep as the original's load does
 * (WorldManager::LoadGame 1.6.9 0x5f8180, ShipManager::ImportShip 0x4a7530, CrewAnimation::LoadState 0x46dfa0), the
 * original's autosave points (CommandGui::OnLoop's event box edge) and a save naming content the game files lack.
 * GameplayParityShell6Checks has the earlier state matrix (combat, hazards, drones, hacking, mind control, cloak, ASB,
 * the jump in progress).
 */
internal object GameplaySavePsS2Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            // Every scenario runs even after a failure, so one run lists them all.
            val rows = ArrayList<String>()
            val failures = ArrayList<String>()
            fun scenario(name: String, block: () -> String) {
                try {
                    rows += block()
                } catch (e: Throwable) {
                    failures += name + ": " + (e.message ?: e::class.simpleName)
                }
            }
            scenario("escape") { escapeMidJump(main, container, instant = false) }
            scenario("instantEscape") { escapeMidJump(main, container, instant = true) }
            scenario("eventOpenSave") { eventOpenSave(main, container) }
            scenario("teleport") { teleportMidway(main, container) }
            scenario("pausedShot") { pausedQueuedShot(main, container) }
            scenario("artilleryBurst") { artilleryMidBurst(main, container) }
            scenario("twoSaves") { twoQuickSaves(main, container) }
            scenario("gameOverPending") { gameOverPending(main, container) }
            scenario("missingContent") { missingContent(main, container) }
            scenario("customDamage") { customDamageOwner() }
            scenario("hazardTimers") { hazardTimers(main, container) }
            check(failures.isEmpty()) { failures.joinToString(" || ") }
            return "ps-s2 saves: " + rows.joinToString("; ")
        } finally {
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun pirate(state: InGameState): Ship {
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, 71)
        return state.enemy!!
    }

    private fun unpause(state: InGameState) {
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
    }

    /**
     * An enemy jumping away at the save: ShipManager::ExportShip writes its jump flag and the jump tracker's time, and
     * ImportShip (1.6.9 0x4a7530) runs JumpLeave again for it and puts the tracker time back (+0x4ac), so after
     * Continue it goes on leaving and is gone when its 2 s are over. Before ps-s2 an escape started its 2 s again and
     * Hyperspace's <instantEscape> (no escape timer) stopped leaving.
     */
    private fun escapeMidJump(main: MainGame, container: GameContainer, instant: Boolean): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = pirate(state)
        enemy.surrenderHealth = 0
        if (instant) {
            state.hsEnemyJumpLeave()
        } else {
            check(enemy.canChargeFTL) { "The fixture enemy can't charge its FTL" }
            enemy.escapeTimer = 0.01f
            var frames = 0
            while (!state.isEnemyJumping && frames++ < 20) main.update(container, .05f)
        }
        check(state.isEnemyJumping) { "The enemy did not start its jump" }
        repeat(10) { main.update(container, .05f) }
        check(state.enemy === enemy && state.isEnemyJumping) { "The fixture enemy left before the save" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val leaving = loaded.enemy ?: error("The enemy jumping away was gone after the load")
        check(loaded.isEnemyJumping) { "The load stopped the enemy's jump" }
        var frames = 0
        while (loaded.enemy === leaving && frames++ < 80) main.update(container, .05f)
        check(loaded.enemy !== leaving) { "The enemy jumping away at the save never left" }
        // Half a second of the 2 s was over at the save.
        check(frames in 27..32) { "The loaded enemy left ${frames * 0.05f} s after the load; about 1.5 s of its jump was left" }
        return (if (instant) "an <instantEscape> enemy" else "an escaping enemy") + " saved mid-jump goes on leaving (gone after ${frames * 5 / 100f} s)"
    }

    /**
     * CommandGui::OnLoop (1.6.9 0x5220b0, 1.6.14 0x536770) queues the autosave event whenever the event box opens or
     * closes while the ship is not jumping. A box opening at the beacon (here an event the console could load) is in the
     * run save by the next frame, and the box a load brings back is no new save.
     */
    private fun eventOpenSave(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        repeat(4) { main.update(container, .05f) }
        val before = BrowserFiles.getRun()
        state.shipUI.showEventDialogue(state.eventManager["ASTEROID_EXPLORE"].resolve(), 7)
        check(state.shipUI.diagnosticDialogue != null) { "The fixture box did not open" }
        main.update(container, .05f)
        val saved = BrowserFiles.getRun()
        check(saved != null && saved !== before) { "The box opening did not autosave" }
        main.loadSavedGame(BrowserXml.parse(saved!!))
        val loaded = main.currentState as InGameState
        check(loaded.shipUI.diagnosticDialogue != null) { "The autosave does not hold the open box" }
        val reloaded = BrowserFiles.getRun()
        repeat(3) { main.update(container, .05f) }
        check(BrowserFiles.getRun() === reloaded) { "The box a load brought back saved again" }
        return "an event box opening autosaves with the box, its reappearing after a load does not"
    }

    /**
     * A crew member mid-teleport: CrewAnimation::SaveState (1.6.9 0x46df30) keeps the teleport state and LoadState
     * restarts it, so after Continue the crew member still arrives on the other ship.
     */
    private fun teleportMidway(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        val enemy = pirate(state)
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first()
        val name = crew.info.name
        crew.teleportAnimatedTo(enemy.rooms.first())
        repeat(4) { main.update(container, .05f) }
        check(state.player.crew.contains(crew)) { "The fixture crew member arrived before the save" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        unpause(loaded)
        fun aboard() = loaded.enemy!!.crew.any { (it as? LivingCrew)?.info?.name == name }
        var frames = 0
        while (!aboard() && frames++ < 40) main.update(container, .05f)
        check(aboard()) { "The crew member teleporting at the save never arrived on the enemy ship" }
        return "a crew member saved mid-teleport arrives after the load"
    }

    /**
     * A sun's flare and a pulsar's pulse timers: SpaceManager::SaveSpace (1.6.9 0x4166a0) writes the hazard's timers
     * and LoadSpace puts them back, so after Continue the next flare or pulse comes when it would have.
     */
    private fun hazardTimers(main: MainGame, container: GameContainer): String {
        val out = org.jdom2.output.XMLOutputter()
        val rows = ArrayList<String>()
        for (sun in listOf(true, false)) {
            val state = BrowserGameplayParityDiagnostics.fresh(main, container)
            val beacon = state.currentBeacon
            val env = if (sun) xyz.znix.xftl.environment.SunEnvironment(state, beacon)
            else xyz.znix.xftl.environment.PulsarEnvironment(state, beacon)
            beacon.debugSetEnvironment(env)
            unpause(state)
            repeat(60) { main.update(container, .05f) }
            val before = Element("env").also { env.saveToXML(it) }
            val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
            val loadedEnv = loaded.currentBeacon.getEnvironment(loaded)
            check(loadedEnv.javaClass == env.javaClass) { "The ${env.type} came back as ${loadedEnv.type}" }
            val after = Element("env").also { loadedEnv.saveToXML(it) }
            check(out.outputString(before) == out.outputString(after)) {
                "The ${env.type} timers changed: ${out.outputString(before)} -> ${out.outputString(after)}"
            }
            rows += env.type.name
        }
        return "hazard timers kept (" + rows.joinToString(", ") + ")"
    }

    /** Paused with a charged, aimed weapon: ProjectileFactory::SaveState keeps the charge and the targets. */
    private fun pausedQueuedShot(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = pirate(state)
        val player = state.player
        val weapons = player.weapons!!
        weapons.energyLevels = 8
        player.purchasedReactorPower = 25
        val laser = (state.blueprintManager["LASER_BURST_1"] as AbstractWeaponBlueprint).buildInstance(player) as AbstractProjectileWeaponInstance
        player.hardpoints[0].weapon = laser
        player.cargoUpdated()
        check(weapons.setWeaponPower(laser, true)) { "Fixture weapon did not power" }
        if (!state.isPaused) state.toggleQuickPause()
        laser.timeCharged = laser.chargeTime
        weapons.selectedTargets.targetRoom(0, enemy.rooms.first())
        main.update(container, .05f)
        check(state.isPaused && player.projectiles.isEmpty() && !laser.isFiring) { "The shot left while paused" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val restored = loaded.player.hardpoints[0].weapon as AbstractProjectileWeaponInstance
        check(restored.timeCharged >= restored.chargeTime) { "The charge was lost: ${restored.timeCharged}/${restored.chargeTime}" }
        unpause(loaded)
        var frames = 0
        while (!restored.isFiring && loaded.player.projectiles.isEmpty() && frames++ < 20) main.update(container, .05f)
        check(restored.isFiring || loaded.player.projectiles.isNotEmpty()) { "The aimed shot did not leave after the load" }
        return "a shot charged and aimed while paused leaves after the load"
    }

    /**
     * An artillery burst saved between two shots (the flagship's ARTILLERY_BOSS_1 fires three): ArtillerySystem's
     * ProjectileFactory::LoadState (1.6.9 0x40eab0, from ShipManager::ImportBattleState) restores the volley and the
     * original takes every shot's fire location from the mount, so the rest of the burst leaves after Continue. Ours did
     * not save the mount the burst left from, and the next shot after the load had no spawn position.
     */
    private fun artilleryMidBurst(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_FED")
        pirate(state)
        val player = state.player
        player.purchasedReactorPower = 25
        val artillery = player.systems.filterIsInstance<xyz.znix.xftl.systems.Artillery>().first()
        val laser = (state.blueprintManager["ARTILLERY_BOSS_1"] as AbstractWeaponBlueprint).buildInstance(player) as AbstractProjectileWeaponInstance
        laser.bindToArtillery(artillery)
        artillery.hsSetWeapon(laser)
        player.update(0f)
        check(artillery.powerSupplied > 0) { "The fixture artillery is not powered" }
        state.debugFlags.fastWeaponCharge.set = true
        val shots = player.projectiles.size
        var frames = 0
        while (!(laser.isFiring && player.projectiles.size > shots) && frames++ < 600) main.update(container, .02f)
        state.debugFlags.fastWeaponCharge.set = false
        check(laser.isFiring && player.projectiles.size > shots) { "No burst under way after $frames frames (firing ${laser.isFiring})" }
        val released = player.projectiles.size - shots
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val restored = loaded.player.systems.filterIsInstance<xyz.znix.xftl.systems.Artillery>().first().browserWeapon as AbstractProjectileWeaponInstance
        check(restored.isFiring) { "The burst was not in the save" }
        unpause(loaded)
        frames = 0
        while (restored.isFiring && frames++ < 200) main.update(container, .02f)
        check(!restored.isFiring) { "The rest of the burst did not leave after the load" }
        return "an artillery burst saved after $released of its shots fires the rest after the load"
    }

    /** Two saves in a row write the same run and draw nothing (WorldManager::SaveGame only writes). */
    private fun twoQuickSaves(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        pirate(state)
        state.debugFlags.noEnemyFire.set = false
        repeat(40) { main.update(container, .05f) }
        val streams = BrowserRandom.snapshot() + "|" + NativeRandom.snapshot()
        main.writeRunSave()
        val first = BrowserFiles.getRun()
        main.writeRunSave()
        val second = BrowserFiles.getRun()
        check(first != null && first == second) { "Two saves in a row differ" }
        check(streams == BrowserRandom.snapshot() + "|" + NativeRandom.snapshot()) { "Saving drew from the random streams" }
        main.loadSavedGame(BrowserXml.parse(second!!))
        check((main.currentState as InGameState).enemy != null) { "The fight was not in the save" }
        return "two saves in a row are one save and draw nothing"
    }

    /**
     * A save while the player's ship breaks up (hull 0, before the game over screen): the run comes back and ends the
     * same way. The original's SaveGame refuses only after CommandGui::IsGameOver.
     */
    private fun gameOverPending(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.player.health = 0
        main.update(container, .05f)
        check(!state.browserRunEnded) { "The run ended before the ship broke up" }
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        unpause(loaded)
        var frames = 0
        while (!loaded.browserRunEnded && frames++ < 300) main.update(container, .05f)
        check(loaded.browserRunEnded) { "A save made while the ship broke up came back as a living run" }
        return "a save made while the ship breaks up ends in the game over after the load"
    }

    /**
     * docs/SAVES.md "Engine requirements": a save naming content the game files lack (a mod no longer in the save's
     * mods) loads with each such item dropped and listed for the notice (MissingContent), never failing.
     */
    private fun missingContent(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val doc = state.saveGameState()
        val ship = doc.rootElement.getChild("playerShip")
        ship.addContent(Element("augment").setAttribute("name", "PS_S2_GONE_AUGMENT"))
        ship.addContent(Element("cargoBlueprint").setAttribute("name", "PS_S2_GONE_WEAPON").setAttribute("index", "3"))
        val crew = ship.getChild("crew").getChildren("crewMember")
        val crewBefore = crew.size
        crew.last().setAttribute("type", "ps_s2_gone_race")
        main.loadSavedGame(doc)
        val loaded = main.currentState as InGameState
        val dropped = MissingContent.dropped
        check(dropped.containsAll(listOf("PS_S2_GONE_AUGMENT", "PS_S2_GONE_WEAPON", "ps_s2_gone_race"))) { "Dropped $dropped" }
        check(loaded.player.crew.count { it is LivingCrew } == crewBefore - 1) { "Crew ${loaded.player.crew.size} of $crewBefore" }
        check(loaded.player.augments.none { it.name.startsWith("PS_S2") }) { "The missing augment came back" }
        return "a save naming a missing augment, weapon and crew race loads without them and lists them"
    }

    /**
     * O-C3-6: a projectile's CustomDamageDefinition is saved by index (ProjectileFactory::SaveProjectile hook,
     * CustomDamage.cpp), which follows the order the blueprints were parsed in. Our own format also names the weapon
     * whose definition it is, so a save keeps its definition when that order changes (another mod order, an engine
     * that parses in another order: ps-c3 changed ours).
     */
    private fun customDamageOwner(): String {
        if (!xyz.znix.xftl.hs.HS.active) return "custom damage: vanilla"
        val defs = xyz.znix.xftl.hs.weapons.HsDamageDef.defs
        val def = defs.lastOrNull { it.owner != null } ?: return "custom damage: none in these mods"
        val ext = xyz.znix.xftl.hs.weapons.HsProjectileExt()
        ext.def = def
        val elem = Element("projectile")
        xyz.znix.xftl.hs.weapons.HsProjectileExt.save(ext, elem)
        val node = elem.getChild("hsExt")
        check(node.getAttributeValue("def") == def.idx.toString() && node.getAttributeValue("defOwner") == def.owner) { "Saved ${node.attributes}" }
        // As a save made under another parse order: its index names another definition.
        node.setAttribute("def", ((def.idx + 1) % defs.size).toString())
        val back = xyz.znix.xftl.hs.weapons.HsProjectileExt()
        xyz.znix.xftl.hs.weapons.HsProjectileExt.load(back, elem)
        check(back.def === def) { "The saved definition of ${def.owner} came back as ${back.def.owner}" }
        // A save without the name (before ps-s2, or a script's definition): the index, as HS reads it.
        node.removeAttribute("defOwner")
        node.setAttribute("def", def.idx.toString())
        val old = xyz.znix.xftl.hs.weapons.HsProjectileExt()
        xyz.znix.xftl.hs.weapons.HsProjectileExt.load(old, elem)
        check(old.def === def) { "An index-only save lost its definition" }
        return "a projectile's custom damage comes back by its weapon's name (${def.owner}), by index without one"
    }
}
