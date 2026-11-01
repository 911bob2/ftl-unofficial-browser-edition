package ftl.browser

import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsBoss
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.GameContainer

/**
 * Hyperspace `<boss>` (`hsShipsProbe("boss <difficulty>")`): a run of the MV Kestrel, the flagship of the last sector
 * spawned at stage 1 (its crew), the crew list it records, the stage 2 ship built from that list after a member died,
 * the drone surge (pool built once, reused by a named event surge until cleared), and the barrage's projectiles.
 * tests/hs-ships.mjs checks the numbers against its own reading of `<boss>`.
 */
object BrowserHsBossDiagnostics {
    private val q = BrowserDebug::quote

    private fun crewJson(ship: xyz.znix.xftl.Ship) =
        ship.crew.filterIsInstance<LivingCrew>().joinToString(",", "[", "]") { "[${q(it.codename)},${it.room.id}]" }

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, difficulty: Difficulty): String {
        check(HS.active) { "Hyperspace is not active" }
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        var stage = "start"
        try {
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", difficulty)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            stage = "sector"
            val sector = game.gameMap.generateSector(game.gameMap.sectors.last().first(), game)
            val boss = sector.bosses.filterIsInstance<FlagshipBoss>().firstOrNull() ?: error("no flagship in the last sector")
            stage = "spawn"
            boss.stage = 1
            game.currentBeacon = boss.beacon
            check(game.browserSpawnBossHere()) { "the flagship did not spawn" }
            val flagship = game.enemy ?: error("no flagship ship")
            game.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, 0f); main.render(container, graphics)
            val stage1 = crewJson(flagship)
            val rooms1 = flagship.rooms.size

            // The boss frame records the crew; one member dies; the next stage is built from the record.
            stage = "record"
            HsBoss.recordCrew(flagship)
            val killed = flagship.crew.filterIsInstance<LivingCrew>().first()
            killed.health = 0f
            HsBoss.recordCrew(flagship)
            val recorded = HsBoss.currentCrew.joinToString(",", "[", "]") { "[${q(it.first)},${it.second}]" }

            // Surges at stage 1's ship (the current enemy): the unnamed pool, a named event surge (pool reused),
            // the pool cleared, then the named one.
            stage = "drones"
            val def = HsBoss.droneDef("") ?: error("no surge drones")
            HsBoss.prepareSuperDrones(game, flagship, def)
            val pool = game.player.externalDrones.size
            stage = "drones update"
            repeat(10) { HsBoss.update(game, 1f / 32f) }
            stage = "drones event"
            val flying = game.player.externalDrones.size
            HsBoss.applyEvent(game, "probe", 1, "MFK_DRONE_SURGE", -1, -1, "")
            val reused = game.player.externalDrones.size
            HsBoss.applyEvent(game, "probe", -1, "", 1, -1, "")
            HsBoss.applyEvent(game, "probe", 1, "MFK_DRONE_SURGE", -1, -1, "")
            val named = game.player.externalDrones.size
            HsBoss.clearSuperDrones(flagship, true)
            val cleared = game.player.externalDrones.size

            stage = "barrage"
            // Shots in flight from the flagship, beams sweeping (HsBoss), bombs on the player ship.
            fun shots() = flagship.projectiles.size + HsBoss.barrageBeamCount + game.player.projectiles.size
            val before = shots()
            HsBoss.prepareSuperBarrage(game, flagship, HsBoss.barrageDef("") ?: error("no barrage"))
            val barrage = shots() - before
            val before2 = shots()
            HsBoss.applyEvent(game, "probe", -1, "", -1, 1, "MFK_LASER_SURGE")
            val namedBarrage = shots() - before2
            // A named surge with a beam (MV's BARRAGE_MELTDOWN), then frames until its sweep ends.
            val beamSurge = HsBoss.namedBarrages.entries.firstOrNull { e -> e.value.byDifficulty.any { l -> l.any { game.blueprintManager.getOrNull(it.name) is xyz.znix.xftl.weapons.BeamBlueprint } } }?.key
            var beams = 0
            if (beamSurge != null) {
                stage = "beam barrage"
                val b0 = HsBoss.barrageBeamCount
                HsBoss.applyEvent(game, "probe", -1, "", -1, 1, beamSurge)
                beams = HsBoss.barrageBeamCount - b0
                // The beam bodies live in the ships' spaces, so the whole game runs (its frame updates HsBoss too).
                // Those frames refresh the boss's crew record; stage 2 below is built from the one taken above.
                val record = ArrayList(HsBoss.currentCrew)
                var frames = 0
                while (HsBoss.barrageBeamCount > 0 && frames++ < 300 && game.player.health > 0) game.updateGameState(1f / 30f)
                check(HsBoss.barrageBeamCount == 0) { "barrage beams still sweeping after 10 s (hull ${game.player.health})" }
                HsBoss.currentCrew.clear(); HsBoss.currentCrew.addAll(record)
            }
            main.render(container, graphics)

            stage = "stage 2"
            boss.stage = 2
            val stage2Ship = boss.createShip()
            val stage2 = crewJson(stage2Ship)
            return "{\"difficulty\":${difficulty.ordinal},\"rooms1\":$rooms1,\"rooms2\":${stage2Ship.rooms.size},\"stage1\":$stage1," +
                "\"recorded\":$recorded,\"stage2\":$stage2,\"drones\":{\"pool\":$pool,\"flying\":$flying,\"reused\":$reused," +
                "\"named\":$named,\"cleared\":$cleared},\"barrage\":$barrage,\"namedBarrage\":$namedBarrage,\"beamSurge\":${beamSurge?.let(q) ?: "null"},\"beams\":$beams}"
        } catch (e: Throwable) {
            throw IllegalStateException("boss probe failed at $stage (${HsBoss.diagnosticStep}): $e", e)
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(native)
            main.switchToMainMenu()
        }
    }
}
