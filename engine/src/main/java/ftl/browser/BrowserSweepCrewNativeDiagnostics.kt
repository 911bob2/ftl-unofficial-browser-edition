package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Sweep A round 3: the engine's twin of the native 1.6.14 crew probe (authored hook on the player ShipManager's crew
 * update, kept in the ignored .build/sweep-crew/native folder). On the start ship after 120 frames: crew 0 gets
 * health 0, crew 1 starts the departure teleport in place, crew 2 the arrival; then one row after each of 180
 * updates at 1/60 s (the native records hold the state after the same number of updates). Diagnostics only;
 * BrowserRandom and NativeRandom are restored afterwards.
 */
object BrowserSweepCrewNativeDiagnostics {
    fun run(main: MainGame, container: GameContainer, argument: String): String {
        if (argument.trim() == "drone") return drone(main, container)
        val ship0 = argument.trim().ifEmpty { "PLAYER_SHIP_HARD" }
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame(ship0, Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false
            repeat(120) { main.update(container, 1f / 60f) }
            val subjects = ship.crew.take(3)
            subjects[0].health = 0f
            subjects[1].hsStartTeleportInPlace()
            subjects[2].hsStartTeleportArriveNow()
            val rows = StringBuilder()
            for (f in 1..180) {
                main.update(container, 1f / 60f)
                if (f > 1) rows.append(',')
                rows.append("{\"f\":").append(f).append(",\"n\":").append(ship.crew.size).append(",\"s\":[")
                subjects.forEachIndexed { i, c ->
                    if (i > 0) rows.append(',')
                    if (c !in ship.crew) rows.append("null")
                    else rows.append("{\"hp\":").append(c.health).append(",\"a\":\"").append(c.currentAction).append("\"}")
                }
                rows.append("]}")
            }
            return "{\"ship\":\"$ship0\",\"rows\":[$rows]}"
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }

    /**
     * Twin of the native 1.6.14 crew drone probe: PLAYER_SHIP_CIRCLE_2 after 120 frames, the shields lose a bar and
     * drone slot 1 (a system repair drone) is powered; one row after each of 150 updates: the drone body's centre,
     * whether its power-up animation is still running, and its goal pixel.
     */
    private fun drone(main: MainGame, container: GameContainer): String {
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_CIRCLE_2", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.diagnosticCloseWindow()
            val ship = game.player
            ship.browserJumping = false
            repeat(120) { main.update(container, 1f / 60f) }
            ship.shields!!.dealDamage(1, 0)
            val drones = ship.drones!!
            val powered = drones.setDronePower(1, true)
            val rows = StringBuilder()
            for (f in 1..150) {
                main.update(container, 1f / 60f)
                val pawn = (drones.drones[1]?.instance as? xyz.znix.xftl.drones.AbstractIndoorsDrone)?.pawn
                if (f > 1) rows.append(',')
                if (pawn == null) { rows.append("null"); continue }
                val p = pawn.getPixelPositionCentre()
                val goal = pawn.pathingTarget?.let { "\"${it.room.id}:${it.x},${it.y}\"" } ?: "null"
                rows.append("{\"f\":").append(f).append(",\"x\":").append(p.x).append(",\"y\":").append(p.y)
                    .append(",\"up\":").append(pawn.powerUpDuration > 0f).append(",\"goal\":").append(goal).append('}')
            }
            return "{\"powered\":$powered,\"rows\":[$rows]}"
        } finally {
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
