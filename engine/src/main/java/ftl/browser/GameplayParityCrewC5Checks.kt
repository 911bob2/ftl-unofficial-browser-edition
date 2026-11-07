package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.crew.CrewStuck
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep C5 (crew simulation and crew AI): rule checks for what the sweep fixed.
 * - CREW_STUCK (WorldManager::OnLoop 1.6.14 0x61bd78): boarders left on a ship that is no longer hostile come home
 *   to the piloting room with the CREW_STUCK box when the player's ship holds none of its own crew and the player's
 *   teleporter is installed with no health; not while the enemy is hostile, not while the teleporter has a bar.
 */
internal object GameplayParityCrewC5Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            return "crewC5 ok: ${crewStuck(main, container)}"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** The whole crew aboard a pirate whose crew is dead; [teleporterHealth] bars of the teleporter left. */
    private fun stranded(
        main: MainGame, container: GameContainer, teleporterHealth: Int, enemyCrewLeft: Int = 0
    ): Pair<InGameState, List<LivingCrew>> {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        val player = state.player
        val enemy = state.enemy!!
        val teleporter = player.teleporter ?: error("PLAYER_SHIP_MANTIS without a teleporter")
        teleporter.damagedEnergyLevels = teleporter.energyLevels - teleporterHealth
        state.debugFlags.noCrewDamage.set = true

        enemy.crew.drop(enemyCrewLeft).forEach { it.removeFromShip() }
        val target = enemy.rooms.first()
        val boarders = player.crew.filterIsInstance<LivingCrew>()
        for (crew in boarders) {
            crew.removeFromShip()
            enemy.crew.add(crew)
            crew.jumpTo(enemy.findSpaceForCrew(target, xyz.znix.xftl.crew.AbstractCrew.SlotType.INTRUDER))
        }
        state.updatePlayerCrew()
        check(CrewStuck.countCrew(player, false) == 0 && CrewStuck.countCrew(enemy, true) == boarders.size) {
            "Set-up: home ${CrewStuck.countCrew(player, false)}, aboard ${CrewStuck.countCrew(enemy, true)}"
        }
        return state to boarders
    }

    private fun crewStuck(main: MainGame, container: GameContainer): String {
        // Hostile ship (one of its crew still alive): nobody comes home.
        run {
            val (state, boarders) = stranded(main, container, 0, enemyCrewLeft = 1)
            state.updateGameState(DT)
            check(state.isEnemyHostile) { "The fight ended with an enemy crew member alive" }
            check(boarders.all { it.room.ship === state.enemy }) { "Recalled during a fight" }
        }
        // Fight over, teleporter with a bar left: the crew can teleport, nobody comes home.
        run {
            val (state, boarders) = stranded(main, container, 1)
            state.browserEndHostility()
            state.updateGameState(DT)
            check(boarders.all { it.room.ship === state.enemy }) { "Recalled with a working teleporter" }
            check(state.shipUI.diagnosticDialogue == null) { "A box opened with a working teleporter" }
        }
        // Teleporter destroyed: the loop that kills the last enemy shows the deadCrew ending and recalls nobody; the
        // first loop after that box closes brings the crew home to the piloting room and opens CREW_STUCK.
        val (state, boarders) = stranded(main, container, 0)
        state.updateGameState(DT)
        check(!state.isEnemyHostile) { "No crew-kill ending" }
        check(boarders.all { it.room.ship === state.enemy }) { "Recalled in the loop of the ending" }
        val ending = state.shipUI.diagnosticDialogue
        var frames = 0
        while (boarders.any { it.room.ship !== state.player } && frames < 60) {
            if (state.shipUI.diagnosticDialogue === ending) ending?.selectOption(0)
            main.update(container, DT)
            frames++
        }
        val player = state.player
        check(boarders.all { it.room.ship === player && it.ownerShip === player }) {
            "Not recalled: ${boarders.map { "${it.info.name}@${it.room.ship === player}" }}"
        }
        check(CrewStuck.countCrew(state.enemy!!, true) == 0) { "Boarders left aboard" }
        val pilot = player.piloting!!.room!!
        val inPilot = boarders.count { it.room === pilot }
        check(inPilot == minOf(boarders.size, pilot.width * pilot.height)) { "In the piloting room: $inPilot of ${boarders.size}" }
        check(state.shipUI.diagnosticDialogue != null) { "No CREW_STUCK box" }
        return "crew stuck (${boarders.size} home, $inPilot in the cockpit)"
    }
}
