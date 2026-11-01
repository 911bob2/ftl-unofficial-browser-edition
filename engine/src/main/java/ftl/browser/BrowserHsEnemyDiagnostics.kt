package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.LazyShipBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.ship.HsHangar
import xyz.znix.xftl.hs.ship.HsShipGenerator
import xyz.znix.xftl.hs.ship.HsShips
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer

/**
 * Every enemy ship blueprint built the way the game builds it (`hsShipsProbe("enemies <difficulty> [from] [count]")`):
 * each non-player `<shipBlueprint>` as an event ship (`auto_blueprint`), through ShipGenerator.buildShip at world
 * levels 0-7 (Hyperspace: the CustomShipGenerator path when `<shipGenerators>` exists), with a seed per build. One
 * build per blueprint becomes the current enemy (a frame is updated, every 25th rendered); every 100th an event ship is spawned, saved and
 * loaded. JSON: counts, the failures (blueprint, level, error) and a few checks of the built ships.
 */
object BrowserHsEnemyDiagnostics {
    private val q = BrowserDebug::quote

    fun enemyBlueprints(main: MainGame): List<String> = main.gameContent.blueprintManager.blueprints.values
        .filterIsInstance<LazyShipBlueprint>().map { it.name }
        .filter { !it.startsWith("PLAYER_SHIP_") && (!HS.active || HsHangar.variantOf(it) == null) }.sorted()

    fun run(main: MainGame, container: GameContainer, graphics: Graphics, difficulty: Difficulty, from: Int, count: Int): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val failures = ArrayList<String>()
        var builds = 0; var spawned = 0; var saves = 0
        var maxHull = 0; var maxCrew = 0; var automated = 0; var defCrew = 0; var temporal = 0; var overMax = 0
        val all = enemyBlueprints(main)
        val names = all.drop(from).take(count)
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", difficulty)
            main.update(container, 0f)
            var game = main.currentState as InGameState
            for ((i, name) in names.withIndex()) {
                val elem = Element("ship")
                elem.setAttribute("name", "PROBE_$name")
                elem.setAttribute("auto_blueprint", name)
                val spec = try {
                    EnemyShipSpec(elem, game.blueprintManager, game.eventManager)
                } catch (e: Exception) {
                    failures += "{\"bp\":${q(name)},\"level\":-1,\"error\":${q("spec: " + (e.message ?: e.toString()))}}"
                    continue
                }
                for (level in 0..7) {
                    val seed = 1000003 * (from + i + 1) + level * 7919
                    try {
                        val ship = game.content.generator.buildShip(game, spec, level, difficulty, seed)
                        builds++
                        maxHull = maxOf(maxHull, ship.maxHealth)
                        maxCrew = maxOf(maxCrew, ship.crew.size)
                        if (ship.isAutomated) automated++
                        if (HS.active && HsShips.def(name).crewList.isNotEmpty()) defCrew++
                        if (ship.systems.any { it.blueprint.type == "temporal" }) temporal++
                        // An AI maximum may never be passed (the generator clamps every system to it).
                        for (slot in ship.systemSlots) {
                            val sys = slot.room.system ?: continue
                            if (!slot.isInstalled) continue
                            val max = slot.spec.aiMaxPower ?: continue
                            if (sys.energyLevels > maxOf(max, slot.spec.startingPower, 1) && slot.spec.systemName != "weapons" && slot.spec.systemName != "drones") overMax++
                        }
                    } catch (e: Throwable) {
                        failures += "{\"bp\":${q(name)},\"level\":$level,\"error\":${q(e.toString() + " @ " + BrowserJsStack.of(e).take(1500))}}"
                    }
                }
                // One of the builds as the current enemy: a frame, and every 100th a save/load round trip.
                val level = (from + i) % 8
                var stage = "spawn"
                try {
                    game.debugSpawnShip(spec, difficulty, level, 77 + i)
                    stage = "update"
                    main.update(container, 1f / 30f)
                    stage = "render"
                    // A frame for every 25th (every hull image drawn stays loaded: all of them exhaust the page).
                    if ((from + i) % 25 == 0) main.render(container, graphics)
                    spawned++
                    stage = "save"
                    if ((from + i) % 100 == 0) {
                        // A save names the event ship it came from, so the round trip uses a real one (in name order).
                        val specs = game.eventManager.getShips().sortedBy { it.name }
                        val real = specs[((from + i) / 100) % specs.size]
                        game.debugSpawnShip(real, difficulty, level, 91 + i)
                        val bp = game.enemy?.type?.name
                        val xml = BrowserXml.write(game.saveGameState())
                        main.loadSavedGame(BrowserXml.parse(xml))
                        game = main.currentState as InGameState
                        check(game.enemy?.type?.name == bp) { "${real.name}: enemy ${game.enemy?.type?.name} after load, $bp before" }
                        main.update(container, 1f / 30f)
                        main.render(container, graphics)
                        saves++
                    }
                } catch (e: Throwable) {
                    failures += "{\"bp\":${q(name)},\"level\":${q("$stage/${xyz.znix.xftl.shipgen.ShipGenerator.diagnosticStep} $level")},\"error\":${q(e.toString() + " @ " + BrowserJsStack.of(e).take(1500))}}"
                    // Start over so a broken state does not fail the rest.
                    main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", difficulty)
                    main.update(container, 0f)
                    game = main.currentState as InGameState
                }
            }
        } finally {
            BrowserRandom.restore(rng); NativeRandom.restore(native)
        }
        return "{\"difficulty\":${difficulty.ordinal},\"total\":${all.size},\"from\":$from,\"blueprints\":${names.size},\"builds\":$builds," +
            "\"spawned\":$spawned,\"saves\":$saves,\"hsGenerator\":${HS.active && HsShipGenerator.enabled},\"maxHull\":$maxHull," +
            "\"maxCrew\":$maxCrew,\"automated\":$automated,\"defCrewBuilds\":$defCrew,\"temporal\":$temporal,\"overMax\":$overMax," +
            "\"failures\":[${failures.joinToString(",")}]}"
    }
}
