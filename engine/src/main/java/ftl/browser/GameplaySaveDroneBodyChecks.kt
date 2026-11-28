package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.drones.BoardingDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.savegame.SaveMigrations
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Release 2.2.4 (Android, Multiverse): a run could not be resumed, "Missing object for ID: 'drone_battle2:7'". The
 * enemy's Battle Mk II boarding drones were aboard the player's ship when that ship was destroyed; their bodies stayed
 * in the player's crew list until their next update, the destroyed event's box (and its autosave) came first, and the
 * save gave each body an ID without writing it while a crew member fighting one named it as its target.
 *
 * - a body left over from a ship that was destroyed, or that jumped away, is not in the save, and its fighter has no
 *   target after the load, as in the game going on (the body's next update takes it off and clears the fight), and as
 *   in the original, which writes a crew drone's body only with its pod in a saved ship and no fight target at all;
 * - a body whose ship is still in the fight is saved with its drone, and its fighter's target is that body again;
 * - a run of revision 2 (release 2.2.4) that names a fight target it did not write loads: the target is dropped and
 *   the migration line says which.
 * Multiverse uses its Battle Mk II boarder (BATTLETOG2_BOARDER), vanilla and Hyperspace the vanilla BOARDER.
 */
internal object GameplaySaveDroneBodyChecks {
    private const val DT = .05f

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
            case("destroyedShipsBody") { shipGone(main, container, escaped = false) }
            case("escapedShipsBody") { shipGone(main, container, escaped = true) }
            case("liveBodyKeepsFight") { liveBodyKeepsFight(main, container) }
            case("oldRunWithDanglingTarget") { oldRunWithDanglingTarget(main, container) }
        } finally {
            main.currentState.let { if (it is InGameState) it.debugFlags.noCrewDamage.set = false }
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "drone body saves ok: " + results.joinToString(", ")
    }

    private class Fight(val state: InGameState, val drone: BoardingDrone, val body: AbstractIndoorsDrone.Pawn, val fighter: LivingCrew)

    /** A fresh run, an enemy whose boarding drone has landed on the player's ship, and a crew member fighting its body. */
    private fun fight(main: MainGame, container: GameContainer): Fight {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val specs = listOf("PIRATE", "REBEL").mapNotNull { runCatching { state.eventManager.getShip(it) }.getOrNull() }
        found@ for (seed in 71 until 171) for (spec in specs) {
            state.debugSpawnShip(spec, Difficulty.NORMAL, 6, seed)
            if (state.enemy?.drones != null) break@found
        }
        val enemy = checkNotNull(state.enemy) { "No enemy" }
        val player = state.player
        val drones = checkNotNull(enemy.drones) { "No generated enemy with a drone system" }
        // the enemy's own drones leave the list: its drone AI would launch them again (a second boarder's body)
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance(); drones.drones[it] = null }
        enemy.purchasedReactorPower = 25
        drones.energyLevels = 8
        enemy.updateAvailablePower()
        enemy.dronesCount = 5
        if (drones.drones.isEmpty()) drones.drones.add(null)
        val type = (state.blueprintManager.getOrNull("BATTLETOG2_BOARDER") as? DroneBlueprint)
            ?: state.blueprintManager["BOARDER"] as DroneBlueprint
        val info = Drones.DroneInfo(type)
        drones.drones[0] = info
        check(drones.setDronePower(0, true)) { "The enemy's ${type.name} did not launch" }
        val drone = info.instance as BoardingDrone
        state.debugFlags.noCrewDamage.set = true
        state.debugFlags.noEnemyFire.set = true
        repeat(600) { if (drone.pawn == null) state.updateGameState(DT) }
        val body = checkNotNull(drone.pawn) { "The enemy's ${type.name} did not land" }
        check(body.room.ship === player) { "The enemy's drone landed on its own ship" }

        val fighter = player.crew.filterIsInstance<LivingCrew>().first()
        val room = body.room
        check(fighter.setTargetRoom(room, ConstPoint.ZERO)) { "The fighter cannot go to the body's room" }
        fighter.jumpTo(room, ConstPoint.ZERO)
        fighter.nextTargetPos = null
        room.updateCrewInRoom(); player.updateCrewReservedSlots()
        repeat(80) { if (!(fighter.currentAction.isAttackAction && fighter.enemyToAttack === body)) state.updateGameState(DT) }
        check(fighter.currentAction.isAttackAction && fighter.enemyToAttack === body) {
            "Fixture: ${fighter.info.name} does not fight the body (${fighter.currentAction}, target ${fighter.enemyToAttack?.javaClass?.simpleName})"
        }
        return Fight(state, drone, body, fighter)
    }

    /**
     * The user's run: the drone's ship is destroyed ([escaped] false) or jumps away while its body is aboard and
     * fought. The frame the ship goes, the body is still in the crew list (its update comes first in the frame), and
     * the event box that opens then saves the run.
     */
    private fun shipGone(main: MainGame, container: GameContainer, escaped: Boolean): String {
        val f = fight(main, container)
        val state = f.state
        val enemy = checkNotNull(state.enemy)
        if (escaped) state.hsEnemyJumpLeave() else enemy.health = 0
        val jumpingAtStart = state.isEnemyJumping
        var frames = 0
        // A destroyed ship goes in the game's own update, an escaping one at the end of its 2 s jump-out, which the
        // frame advances after it (InGameState.update), so the frame loop runs whole frames.
        while (state.enemy === enemy && frames++ < 400) {
            if (escaped) main.update(container, DT) else state.updateGameState(DT)
        }
        check(state.enemy !== enemy) {
            "Fixture: the enemy did not ${if (escaped) "leave" else "go"} in $frames frames (jumping at the start $jumpingAtStart, " +
                "now ${state.isEnemyJumping}, hostile ${state.isEnemyHostile}, dead ${enemy.isDead}, state current ${main.currentState === state})"
        }
        val how = if (escaped) "jumped away" else "destroyed"
        if (!state.player.crew.contains(f.body))
            return "$how: the body went with its ship (nothing left to save)"
        check(f.fighter.enemyToAttack === f.body) { "Fixture: the fight ended before the save" }

        val xml = BrowserXml.write(state.saveGameState())
        // The game going on: the body's next update takes it off and the fighter has no target (Pawn.update).
        f.body.update(DT)
        check(!state.player.crew.contains(f.body) && f.fighter.enemyToAttack == null) {
            "Going on, the body of the ship that $how stayed or was still fought"
        }

        main.loadSavedGame(BrowserXml.parse(xml))
        val loaded = main.currentState as InGameState
        val bodies = loaded.player.crew.filterIsInstance<AbstractIndoorsDrone.Pawn>().filter { it.drone.ownerShip !== loaded.player }
        check(bodies.isEmpty()) { "After the load the player's ship holds ${bodies.size} body/bodies of a ship that $how" }
        val fighter = loaded.player.crew.filterIsInstance<LivingCrew>().firstOrNull { it.info.name == f.fighter.info.name }
        checkNotNull(fighter) { "${f.fighter.info.name} is not aboard after the load" }
        check(fighter.enemyToAttack == null) { "${fighter.info.name} fights ${fighter.enemyToAttack} after the load" }
        check(loaded.loadDroppedRefs.isEmpty() && loaded.saveMigrationLines.isEmpty()) {
            "A run of this build needed repairs: ${loaded.loadDroppedRefs} ${loaded.saveMigrationLines}"
        }
        return "$how ship's body not saved, fighter without target"
    }

    /** A body whose ship is still here is saved with its drone; its fighter fights the loaded body again. */
    private fun liveBodyKeepsFight(main: MainGame, container: GameContainer): String {
        val f = fight(main, container)
        fun bodiesOf(state: InGameState) =
            state.player.crew.filterIsInstance<AbstractIndoorsDrone.Pawn>().filter { it.drone.ownerShip === state.enemy }
        val before = bodiesOf(f.state).size
        val loaded = BrowserGameplayParityDiagnostics.reload(main, f.state)
        val bodies = bodiesOf(loaded)
        check(bodies.size == before && bodies.all { it.drone.pawn === it }) { "The live ship's bodies: $before before the load, ${bodies.size} after" }
        val fighter = checkNotNull(loaded.player.crew.filterIsInstance<LivingCrew>().firstOrNull { it.info.name == f.fighter.info.name }) {
            "${f.fighter.info.name} is not aboard after the load"
        }
        check(fighter.currentAction.isAttackAction && fighter.enemyToAttack in bodies) {
            "${fighter.info.name} does not fight the loaded body (${fighter.currentAction}, ${fighter.enemyToAttack})"
        }
        return "live ship's body saved, fight kept"
    }

    /**
     * A run of release 2.2.4 (revision 2) whose crew member names, as its fight target, an ID the run does not hold
     * (how 2.2.4 wrote a left-over body): it loads, the target is dropped and migration 3 names it.
     */
    private fun oldRunWithDanglingTarget(main: MainGame, container: GameContainer): String {
        val f = fight(main, container)
        val doc = f.state.saveGameState()
        val root = doc.rootElement
        root.setAttribute(SaveMigrations.ATTRIBUTE, "2")
        val combat = root.getChild("playerShip").getChild("crew").getChildren("crewMember")
            .first { it.getAttributeValue("selectedName") == f.fighter.info.name }.getChild("combat")
        checkNotNull(combat) { "Fixture: the fighter's save has no fight" }
        combat.setAttribute("enemy", "drone_battle2:9999")
        main.loadSavedGame(BrowserXml.parse(BrowserXml.write(doc)))
        val loaded = main.currentState as InGameState
        val fighter = checkNotNull(loaded.player.crew.filterIsInstance<LivingCrew>().firstOrNull { it.info.name == f.fighter.info.name }) {
            "${f.fighter.info.name} is not aboard after the load"
        }
        // No phantom: the target is none, or one the fighter picked again aboard in the load's first update.
        check(fighter.enemyToAttack.let { it == null || it in loaded.player.crew }) { "The dangling target resolved to an object not aboard" }
        check(loaded.loadDroppedRefs.size == 1 && loaded.loadDroppedRefs[0].endsWith("-> drone_battle2:9999")) {
            "Dropped references: ${loaded.loadDroppedRefs}"
        }
        val line = loaded.saveMigrationLines.firstOrNull { it.startsWith("3 ") }
        check(line != null && line.contains("dropped 1 fight target") && line.contains("drone_battle2:9999")) {
            "Migration lines: ${loaded.saveMigrationLines}"
        }
        // The body that was in the run (its ship is here) is still aboard, and the run goes on.
        check(loaded.player.crew.any { it is AbstractIndoorsDrone.Pawn && it.drone.ownerShip === loaded.enemy }) { "The saved body did not load" }
        repeat(20) { loaded.updateGameState(DT) }
        return "2.2.4 run with a dangling fight target loads (target dropped)"
    }
}
