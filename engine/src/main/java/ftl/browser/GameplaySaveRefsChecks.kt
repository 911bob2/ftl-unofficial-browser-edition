package ftl.browser

import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.systems.HsSystemInstall
import xyz.znix.xftl.hs.systems.HsTemporal
import xyz.znix.xftl.hs.systems.Temporal
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IRoomTargetingWeapon

/**
 * Release 2.2.5 (Android, Multiverse): the game stopped ("Unregistered object", Temporal.saveSystem) when the player
 * destroyed an enemy ship while the player's temporal system was dilating one of its rooms: the destroyed ship is not
 * in the save (WorldManager::SaveGame 1.6.14 0x61a3a0 writes no destroyed ship), and the system named its room.
 *
 * Every fight here ends three ways, with the run saved on every frame from the end's start until 40 frames after the
 * ship is gone (the event box opening autosaves, and Android saves whenever the app goes to the background):
 * - destroyed;
 * - escaped (its jump-out, Hyperspace's ShipManager::JumpLeave);
 * - crew killed (no longer hostile, saved as the defeated enemy), then destroyed: while it explodes it is still the
 *   engine's enemy, but no longer in the save.
 * The player's kit aims at the enemy as it ends:
 * - temporal (where a player ship lists the system): a dilation on an enemy room. Hyperspace 1.23.2
 *   TemporalSystem_Wrapper::OnLoop stops it only when G_->GetShipManager(currentShipId) is null, which for the enemy
 *   (playerShip->enemyShip) stays set after the kill until the next location: the dilation runs on to its timer's end.
 *   A run saved meanwhile loads with the system on and no room (ImportBattleState finds no current_target), so its
 *   first loop stops it with the cooldown lock (StopTimeDilation, AddLock). An escape ends it at the enemy's JumpLeave
 *   (the hook clears the rooms' dilation; OnLoop: "cancel if overwritten").
 * - each in its own fight (Lanius B): hacking (probe landed and pulsing), mind control on an enemy crew member, a
 *   teleport ordered to an enemy room every frame while the enemy is there (crew on the way), a boarding pod launched
 *   as the end starts, a bomb fired at its rooms every frame (Hyperspace holds it in the weapon's queue through the fire
 *   animation; release 2.2.3 stopped on such a bomb, BombProjectile.saveToXML).
 * Each save must succeed; the save after the ship went and the last one must load without repairs and step.
 *
 * And a crew member still leaving for the enemy when the location changes at once (the save and close of a jump in
 * progress settles it, InGameState.browserSettleForSave; a jump during an animation too): WorldManager::ClearLocation
 * (1.6.14 0x614d00) ends with CompleteShip::Jump (0x40a270), which empties the teleport parties, so the crew member
 * plays the departure out and is lost between ships (CheckForTeleport 0x47d3e0: currentShipId -1; CompleteShip::OnLoop
 * 0x409ee0 places only party members). Release 2.2.5 put it aboard the ship left behind and its save named that ship.
 */
internal object GameplaySaveRefsChecks {
    private const val DT = .05f

    private enum class End(val label: String) { DESTROYED("destroyed"), ESCAPED("escaped"), CREW_KILLED("crew killed, then destroyed") }

    /** One thing of the player's aimed at the enemy, each in its own fight so a failure names its writer. */
    private enum class Kit(val label: String) { HACKING("hacking"), MIND("mind control"), TELEPORT("teleport"), POD("boarding pod"), BOMB("bomb") }

    /** An error with its causes and its first engine stack frames (frame numbers collapsed by the caller). */
    private fun describe(e: Throwable): String {
        val parts = ArrayList<String>()
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth++ < 4) {
            parts += "${c::class.simpleName}: ${c.message}"
            c = c.cause?.takeIf { it !== c }
        }
        val root = generateSequence(e) { it.cause?.takeIf { n -> n !== it } }.last()
        val at = root.stackTrace.filter { it.className.startsWith("xyz.znix") }.take(4).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        return parts.joinToString(" <- ") + (if (at.isEmpty()) "" else " at $at")
    }

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            val temporalShip = temporalShip(main)
            for (end in End.values()) {
                if (temporalShip != null) case("temporal ${end.label}") { temporal(main, container, temporalShip, end) }
                for (kit in Kit.values()) case("${kit.label} ${end.label}") { systems(main, container, end, kit) }
            }
            if (temporalShip == null) results += "temporal: no player ship lists the system"
            case("teleport departure at a location change") { departure(main, container) }
        } finally {
            main.currentState.let { if (it is InGameState) { it.debugFlags.noCrewDamage.set = false; it.debugFlags.noDmg.set = false } }
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "save refs ok: " + results.joinToString(", ")
    }

    private fun temporalShip(main: MainGame): String? = main.gameContent.blueprintManager.blueprints.values
        .filterIsInstance<LazyShipBlueprint>().filter { it.name.startsWith("PLAYER_SHIP_") && it.listsSystem("temporal") }
        .map { it.name }.sorted().firstOrNull()

    /** A crewed enemy; for a crew kill one without a clonebay (its dead would come back). */
    private fun spawnCrewed(state: InGameState, end: End): Ship {
        var seed = 71
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            check(seed < 250) { "Fixture: no crewed enemy" }
        } while (state.enemy!!.isAutoScout || state.enemy!!.isAutomated || (end == End.CREW_KILLED && state.enemy!!.clonebay != null))
        state.debugFlags.noEnemyFire.set = true
        return state.enemy!!
    }

    private fun temporalOf(ship: Ship): Temporal? = ship.systems.firstOrNull { it is Temporal } as Temporal?

    /** What a run of saves met: the errors (by text, first frame and count), the save after the ship went, the last one. */
    private class Saves(var goneXml: String?, var lastXml: String?, var goneFrame: Int, var frames: Int) {
        private val seen = LinkedHashMap<String, IntArray>()
        fun error(kind: String, frame: Int, e: String) { seen.getOrPut("$kind: $e") { intArrayOf(frame, 0) }[1]++ }
        val errors: List<String> get() = seen.map { (k, v) -> "$k (from frame ${v[0]}, ${v[1]}x)" }
    }

    /**
     * Ends the fight with [end] and steps whole frames (the event box's autosave is in InGameState.update), saving
     * before each; [onFrame] runs before the save, [afterGone] once on the frame after the ship went.
     */
    private fun endFight(main: MainGame, container: GameContainer, state: InGameState, end: End,
                         onFrame: (Int) -> Unit = {}, afterGone: () -> Unit = {}): Saves {
        val enemy = checkNotNull(state.enemy)
        val saves = Saves(null, null, -1, 0)
        fun save(frame: Int) {
            try {
                saves.lastXml = BrowserXml.write(state.saveGameState())
            } catch (e: Throwable) {
                saves.error("save", frame, describe(e))
            }
        }
        fun step(frame: Int) {
            try {
                main.update(container, DT)
            } catch (e: Throwable) {
                saves.error("update", frame, describe(e))
            }
        }
        when (end) {
            End.DESTROYED -> enemy.health = 0
            End.ESCAPED -> state.hsEnemyJumpLeave()
            End.CREW_KILLED -> {
                for (c in enemy.crew.filterIsInstance<LivingCrew>().toList()) c.health = 0f
                var n = 0
                while (state.isEnemyHostile && state.enemy === enemy && n++ < 600) { onFrame(-n); save(-n); step(-n) }
                check(!state.isEnemyHostile && state.enemy === enemy) { "Fixture: the crew-killed enemy is hostile ${state.isEnemyHostile}, still here ${state.enemy === enemy}" }
                // The event box of the crew kill (its autosave ran in the frame loop above) is answered.
                BrowserDiagnostics.closeBoxes(main, container)
                enemy.health = 0
            }
        }
        var frame = 0
        while (frame < 400) {
            onFrame(frame)
            save(frame)
            if (saves.goneFrame >= 0 && saves.goneXml == null) saves.goneXml = saves.lastXml
            step(frame)
            if (state.enemy !== enemy && saves.goneFrame < 0) { saves.goneFrame = frame; afterGone() }
            if (saves.goneFrame >= 0 && frame > saves.goneFrame + 40) break
            frame++
        }
        saves.frames = frame
        check(saves.goneFrame >= 0) { "Fixture: the enemy did not go (${end.label}) in $frame frames" }
        return saves
    }

    private fun load(main: MainGame, xml: String, what: String): InGameState {
        try {
            main.loadSavedGame(BrowserXml.parse(xml))
        } catch (e: Throwable) {
            throw IllegalStateException("$what does not load: ${describe(e)}")
        }
        val loaded = main.currentState as InGameState
        repeat(5) { loaded.updateGameState(DT) }
        check(loaded.loadDroppedRefs.isEmpty() && loaded.saveMigrationLines.isEmpty()) {
            "$what needed repairs: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}"
        }
        return loaded
    }

    private fun temporal(main: MainGame, container: GameContainer, shipName: String, end: End): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, shipName)
        val player = state.player
        if (temporalOf(player) == null) {
            val slot = player.systemSlots.firstOrNull { it.system.name == "temporal" && !it.isInstalled } ?: error("Fixture: no temporal slot")
            HsSystemInstall.install(player, slot)
            state.shipUI.updateButtons()
        }
        val t = checkNotNull(temporalOf(player)) { "Fixture: no temporal system" }
        player.purchasedReactorPower = 25
        repeat(t.energyLevels) { if (t.powerSelected < t.energyLevels) t.increasePower() }
        val enemy = spawnCrewed(state, end)
        state.debugFlags.noDmg.set = true
        state.updateGameState(DT)
        // A room without crew: a slowed room would slow its crew's deaths in the crew kill (x0.12 at full power).
        val room = enemy.rooms.firstOrNull { r -> r !== enemy.weapons?.room && enemy.crew.none { it.room === r } }
            ?: enemy.rooms.first { it !== enemy.weapons?.room }
        // Slow down: the longer dilation (HsSystems.dilationDuration), so it outlasts the explosion.
        t.startTimeDilation(room, false)
        check(t.turnedOn && t.currentRoom === room && HsTemporal.dilation(room) < 0) { "Fixture: the dilation did not start (on ${t.turnedOn})" }
        val goal = t.timerGoal

        // Read 5 frames after the ship went (the frame it goes may run the system's loop before the ship is dropped),
        // against what was left of the timer on the frame before it went.
        var onAfterGone = false
        var leftBeforeGone = 0f
        var leftAfterGone = 0f
        var sinceGone = -1
        var onAfterEscape: Boolean? = null
        val saves = endFight(main, container, state, end,
            onFrame = { f ->
                if (end == End.ESCAPED && f == 3) onAfterEscape = t.turnedOn
                if (sinceGone < 0) leftBeforeGone = if (t.turnedOn) t.timerGoal - t.timerTime else 0f
                else if (++sinceGone == 5) { onAfterGone = t.turnedOn; leftAfterGone = t.timerGoal - t.timerTime }
            },
            afterGone = { sinceGone = 0 })
        check(saves.errors.isEmpty()) { saves.errors.joinToString("; ") }

        // In play: a destroyed ship's room keeps the dilation until its timer ends; an escape ends it at JumpLeave.
        val play = when (end) {
            End.ESCAPED -> {
                check(onAfterEscape == false) { "The dilation outlived the enemy's JumpLeave (on ${onAfterEscape})" }
                "ended at the escape"
            }
            else -> {
                check(leftBeforeGone > 0.5f) { "Fixture: the dilation had ${leftBeforeGone}s left when the ship went" }
                check(onAfterGone) { "The dilation ended when the destroyed ship went, ${leftBeforeGone}s before its timer (goal $goal)" }
                "kept after the ship went (${Math.round(leftAfterGone * 10) / 10f}s left)"
            }
        }

        // A run saved while it still runs on the gone ship loads with the system stopping in its first loop, locked
        // for the cooldown.
        val xml = checkNotNull(saves.goneXml)
        val loaded = load(main, xml, "The save after the ship went")
        val lt = checkNotNull(temporalOf(loaded.player)) { "No temporal system after the load" }
        check(!lt.turnedOn && lt.currentRoom == null && lt.isLocked) {
            "After the load: on ${lt.turnedOn}, room ${lt.currentRoom?.id}, locked ${lt.isLocked}"
        }
        load(main, checkNotNull(saves.lastXml), "The last save")
        return "temporal ${end.label}: ${saves.frames} saves, $play, loads off and locked"
    }

    private fun systems(main: MainGame, container: GameContainer, end: End, kit: Kit): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        val player = state.player
        player.purchasedReactorPower = 25
        val type = when (kit) { Kit.HACKING -> "hacking"; Kit.MIND -> "mind"; Kit.TELEPORT -> "teleporter"; Kit.POD -> "drones"; Kit.BOMB -> "weapons" }
        if (player.systems.none { it.blueprint.type == type })
            player.systemSlots.firstOrNull { it.system.type == type }?.let { it.room.setSystem(it) }
        val enemy = spawnCrewed(state, end)
        state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true
        player.dronesCount = 5
        var aimed = ""
        var bomb: IRoomTargetingWeapon? = null

        val teleporter = player.teleporter
        val drones = player.drones
        val pod = state.blueprintManager.getOrNull("BOARDER") as? DroneBlueprint
        when (kit) {
            Kit.HACKING -> {
                val hacking = checkNotNull(player.hacking) { "Fixture: no hacking system" }
                hacking.increasePower(); player.update(0f)
                hacking.selectTarget(enemy.rooms.first { it.system != null })
                repeat(300) { if (!hacking.droneLanded) state.updateGameState(DT) }
                check(hacking.droneLanded) { "Fixture: the hacking probe did not land" }
                hacking.startHackingPulse()
                aimed = "probe landed, pulsing"
            }
            Kit.MIND -> {
                val mind = checkNotNull(player.mindControl) { "Fixture: no mind control" }
                repeat(mind.energyLevels) { mind.increasePower(); player.update(0f) }
                enemy.rooms.forEach { it.update(0f) }
                val target = enemy.crew.filterIsInstance<LivingCrew>().firstOrNull { !it.isMindControlResistant && it.room.ship === enemy }
                checkNotNull(target) { "Fixture: no enemy crew member to control" }
                mind.selectRoom(target.room); mind.update(0f)
                check(mind.controlledCrew != null) { "Fixture: mind control took no one" }
                aimed = "a crew member controlled"
            }
            Kit.TELEPORT -> {
                val t = checkNotNull(teleporter) { "Fixture: no teleporter" }
                repeat(t.energyLevels) { t.increasePower(); player.update(0f) }
                aimed = "an order to its rooms every frame"
            }
            Kit.POD -> {
                val d = checkNotNull(drones) { "Fixture: no drone system" }
                checkNotNull(pod) { "Fixture: no BOARDER drone" }
                d.energyLevels = maxOf(d.energyLevels, 8)
                player.updateAvailablePower()
                d.drones.indices.forEach { d.setDronePower(it, false) }
                if (d.drones.isEmpty()) d.drones.add(null)
                d.drones[0] = Drones.DroneInfo(pod)
            }
            Kit.BOMB -> {
                val w = checkNotNull(player.weapons) { "Fixture: no weapons system" }
                player.hardpoints[0].weapon?.let { w.setWeaponPower(it, false) }
                val weapon = (state.blueprintManager["BOMB_1"] as AbstractWeaponBlueprint).buildInstance(player)
                player.hardpoints[0].weapon = weapon
                w.energyLevels = 8
                player.updateAvailablePower()
                weapon.bindToWeaponsSystem(w)
                w.setWeaponPower(weapon, true)
                player.missilesCount = 99
                bomb = weapon as IRoomTargetingWeapon
                aimed = "fired at its rooms every frame (Hyperspace queues the bomb through the fire animation)"
            }
        }

        var podLaunched = false
        val saves = endFight(main, container, state, end, onFrame = { f ->
            if (state.enemy === enemy && f >= 0) {
                if (kit == Kit.POD && f == 0) podLaunched = drones!!.setDronePower(0, true)
                if (kit == Kit.TELEPORT) teleporter!!.selectTeleportAction(true, enemy.rooms[f % enemy.rooms.size])
                if (kit == Kit.BOMB) bomb!!.fire { enemy.rooms[f % enemy.rooms.size] }
            }
        })
        if (kit == Kit.POD) aimed = if (podLaunched) "launched as the end starts" else "did not launch"
        check(saves.errors.isEmpty()) { saves.errors.joinToString("; ") }
        load(main, checkNotNull(saves.goneXml), "The save after the ship went")
        load(main, checkNotNull(saves.lastXml), "The last save")
        return "${kit.label} ${end.label} ($aimed): ${saves.frames} saves load"
    }

    private fun ownCrew(ship: Ship): List<LivingCrew> = ship.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === ship }

    private fun departure(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_ANAEROBIC_2")
        val player = state.player
        val enemy = spawnCrewed(state, End.DESTROYED)
        state.debugFlags.noDmg.set = true; state.debugFlags.noCrewDamage.set = true
        state.updateGameState(DT)
        val before = ownCrew(player).size
        val crew = ownCrew(player).first { it.room.ship === player && !it.isTeleporting }
        crew.teleportAnimatedTo(enemy.rooms.first())
        state.updateGameState(DT)
        check(crew.isTeleportLeaving && crew in player.crew) { "Fixture: the departure did not start" }
        state.beginJumpOut(state.currentBeacon.neighbours.first())
        check(state.browserSettleForSave()) { "Fixture: no jump to settle" }
        check(state.enemy !== enemy && crew in player.crew) { "Fixture: the location did not change during the departure" }
        val xml = try {
            BrowserXml.write(state.saveGameState())
        } catch (e: Throwable) {
            throw IllegalStateException("The save during the departure: ${describe(e)}")
        }

        // In play: the departure plays out and the crew member is on no ship.
        repeat(40) { state.updateGameState(DT) }
        val aboard = listOfNotNull(player, enemy, state.enemy).filter { crew in it.crew }
        check(aboard.isEmpty() && ownCrew(player).size == before - 1) {
            "In play the crew member is aboard ${aboard.map { if (it === player) "the player ship" else if (it === enemy) "the ship left behind" else "the new enemy" }}, own crew ${ownCrew(player).size} of $before"
        }

        // After a reload of the run saved meanwhile: the same.
        val loaded = load(main, xml, "The save during the departure")
        repeat(40) { loaded.updateGameState(DT) }
        val ships = (listOfNotNull(loaded.enemy) + loaded.currentBeacon.sector.beacons.mapNotNull { it.ship }).distinct()
        val boarders = ships.sumOf { s -> s.crew.count { it is LivingCrew && it.ownerShip === loaded.player } }
        check(ownCrew(loaded.player).size == before - 1 && boarders == 0) {
            "After the reload: own crew ${ownCrew(loaded.player).size} of $before, ${boarders} aboard another ship"
        }
        return "teleport departure at a location change: lost between ships in play and after a reload"
    }
}
