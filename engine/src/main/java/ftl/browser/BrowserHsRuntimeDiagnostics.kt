package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.math.RoomPoint
import xyz.znix.xftl.shipgen.EnemyShipSpec
import xyz.znix.xftl.sys.GameContainer

/**
 * fix-runtime checks over every enemy ship blueprint (`hsRuntimeProbe("ships [from] [count]")`): each one spawned as
 * the enemy, then its evasion read and a dodge roll made (fullrun D2: no piloting or engines), a path computed to
 * every room of its layout (D3: doors with the same room on both sides) and a few frames updated so its crew AI
 * paths. JSON: counts, the layouts with such doors, the ships without piloting or engines and every failure.
 * Diagnostics only; BrowserRandom and NativeRandom are restored afterwards.
 */
object BrowserHsRuntimeDiagnostics {
    private val q = BrowserDebug::quote

    fun run(main: MainGame, container: GameContainer, what: String): String {
        val parts = what.trim().split(' ')
        return when (parts[0]) {
            "mn" -> BrowserMissingNamesDiagnostics.probe(main, container, parts.drop(1))
            "mn-show" -> BrowserMissingNamesDiagnostics.show(main, parts[1])
            "ma" -> BrowserMissingAnimDiagnostics.trace(main, container, parts[1])
            "arty" -> BrowserDiagnostics.artilleryTrace(main, container, parts[1], parts.getOrNull(2)?.toInt() ?: 120, parts.getOrNull(3) == "stay")
            "shield-drones" -> BrowserHsWp4Diagnostics.shieldDrones(main, container, what.substringAfter(' '))
            "drone-controls" -> BrowserHsWp4Diagnostics.droneControls(main, container)
            "ion-control" -> BrowserHsWp4Diagnostics.ionControl(main, container)
            "pod-collision" -> BrowserHsWp4Diagnostics.podCollisions(main, container, what.substringAfter(' '))
            "shield-collision" -> BrowserHsWp4Diagnostics.shieldCollisions(main, container, what.substringAfter(' '))
            "shield-native" -> BrowserHsWp4Diagnostics.nativeShields(main, container, what.substringAfter(' '))
            "dodge-native" -> BrowserHsWp4Diagnostics.nativeDodge(main, container)
            "beam-native" -> BrowserHsWp4Diagnostics.nativeBeam(main, container, what.substringAfter(' '))
            "beam-rays" -> BrowserHsWp4Diagnostics.beamRays(main, container, what.substringAfter(' '))
            "drone-collision" -> BrowserHsWp4Diagnostics.droneCollisions(main, container, what.substringAfter(' '))
            "drone-body" -> BrowserHsWp4DronesDiagnostics.run(main, container, what.substringAfter(' '))
            "wp5-crew" -> BrowserHsWp5CrewDiagnostics.run(main, container, what.substringAfter(' '))
            "drone-order" -> BrowserWp4DroneOrderDiagnostics.run(main, container, what.substringAfter(' ', ""))
            "crew-stations" -> BrowserHsWp4Diagnostics.crewStations(main, container, what.substringAfter(' '))
            "weapon-hooks" -> BrowserHsWp4Diagnostics.weaponHooks(main, container, what.substringAfter(' '))
            "wp5-sys" -> BrowserHsWp5Diagnostics.systems(main, container, what.substringAfter(' '), "WP5Sys.run()")
            "sweep-crew" -> BrowserHsWp5Diagnostics.systems(main, container, what.substringAfter(' '), "SweepCrew.run()")
            "sweep-crew2" -> BrowserHsWp5Diagnostics.systems(main, container, what.substringAfter(' '), "SweepCrew2.run()")
            "sweep-crew4" -> BrowserHsWp5Diagnostics.systems(main, container, what.substringAfter(' '), "SweepCrew4.run()")
            "sweep-crew-native" -> BrowserSweepCrewNativeDiagnostics.run(main, container, what.substringAfter(' ', ""))
            "wp5-ship" -> BrowserHsWp5ShipDiagnostics.run(main, container, what.substringAfter(' '))
            "wp5-ship2" -> BrowserHsWp5Ship2Diagnostics.run(main, container, what.substringAfter(' '))
            "sweep-cov" -> BrowserHsSweepWorldDiagnostics.coverage()
            "setter-cov" -> BrowserHsSweepWorldDiagnostics.setterCoverage()
            "sweep-world" -> BrowserHsSweepWorldDiagnostics.run(main, container, what.substringAfter(' '))
            "wp6-save" -> BrowserHsWp6SaveDiagnostics.run(main, container, what.substringAfter(' '))
            "wp6-save-tutorial" -> BrowserHsWp6SaveDiagnostics.tutorial(main, container, what.substringAfter(' '))
            "projectile-hooks" -> BrowserHsWp4Diagnostics.projectileHooks(main, container, what.substringAfter(' '))
            "projectile-native" -> BrowserHsWp4Diagnostics.nativeProjectiles(main, container, what.substringAfter(' '))
            "bodies-native" -> BrowserWp4BodiesDiagnostics.run(main, container, what.substringAfter(' '))
            "flak-native" -> BrowserWp4FlakDiagnostics.run(main, container, what.substringAfter(' '))
            in BrowserHsWp4BeamDiagnostics.commands -> BrowserHsWp4BeamDiagnostics.run(main, container, parts[0], what.substringAfter(' '))
            "campaign" -> BrowserEngine.campaignProbe(parts[1].toInt(), parts[2].toInt())
            "ships" -> ships(main, container, parts.getOrNull(1)?.toInt() ?: 0, parts.getOrNull(2)?.toInt() ?: Int.MAX_VALUE)
            "native-ui" -> "{\"resources\":${resourceMessages(main, container, parts[1])},\"sun\":${sunTimer(main, container, parts[2])}}"
            "sun-timer" -> sunTimer(main, container, parts[1])
            "reload-handles" -> reloadHandles(main, container, parts.getOrNull(1)?.toInt() ?: 20)
            "resource-messages" -> resourceMessages(main, container, parts[1])
            "reward-event" -> rewardEvent(main, container, parts[1], parts[2].toInt(), parts.drop(3).map { it.toInt() })
            else -> error("unknown hsRuntimeProbe command '$what'")
        }
    }

    /**
     * Save and load the same run [times] times, with frames between: the engine objects Lua holds through handles must
     * not grow with the loads. A game left behind stays alive while a handle names any part of it, so this is what keeps
     * a Continue from costing a whole game until Lua's collector happens to run. "FAILED" when they grow.
     */
    private fun reloadHandles(main: MainGame, container: GameContainer, times: Int): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            if (!HS.luaActive) return "{\"result\":\"no Lua in this session\"}"
            main.startDiagnosticGame("PLAYER_SHIP_MVKESTREL", Difficulty.NORMAL)
            repeat(5) { main.update(container, 1f / 60) }
            val save = BrowserXml.write((main.currentState as InGameState).saveGameState())
            val live = ArrayList<Int>()
            repeat(times) {
                main.loadSavedGame(BrowserXml.parse(save))
                repeat(3) { main.update(container, 1f / 60) }
                live.add(xyz.znix.xftl.hs.lua.Handles.live)
            }
            // Against the highest point: the collector does run now and then, which makes the count a saw.
            val grew = live.max() - live.first()
            return "{\"result\":\"${if (grew <= RELOAD_HANDLES_SLACK) "PASS" else "FAILED"}\",\"grew\":$grew,\"live\":$live}"
        } finally {
            main.switchToMainMenu()
            BrowserRandom.restore(rng); NativeRandom.restore(native)
        }
    }

    private const val RELOAD_HANDLES_SLACK = 64

    /** Replay SpaceManager::SetSun from the stream state captured immediately before its timer draw. */
    private fun sunTimer(main: MainGame, container: GameContainer, state: String): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot(); val oldTrace = NativeRandom.trace
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            NativeRandom.restore(state)
            val trace = ArrayList<Int>(); NativeRandom.trace = trace
            val sun = xyz.znix.xftl.environment.SunEnvironment(game, game.currentBeacon)
            val saved = Element("environment"); sun.saveToXML(saved)
            return "{\"hs\":${HS.active},\"draws\":${trace.joinToString(prefix = "[", postfix = "]")}," +
                "\"state\":${q(NativeRandom.snapshot())},\"delay\":${saved.getAttributeValue("nextFlare")}}"
        } finally {
            NativeRandom.trace = oldTrace; BrowserRandom.restore(rng); NativeRandom.restore(native)
        }
    }

    /** Replay a paused +1 fuel/-3 scrap purchase from a captured native stream state. */
    private fun resourceMessages(main: MainGame, container: GameContainer, state: String): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot(); val oldTrace = NativeRandom.trace
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            game.shipUI.prepareResourceMessages()
            NativeRandom.restore(state)
            val trace = ArrayList<Int>(); NativeRandom.trace = trace
            game.player.fuelCount++
            game.player.scrap -= 3
            game.shipUI.prepareResourceMessages()
            val afterPurchase = NativeRandom.snapshot()
            val draws = trace.toList()
            game.shipUI.prepareResourceMessages()
            return "{\"hs\":${HS.active},\"draws\":${draws.joinToString(prefix = "[", postfix = "]")}," +
                "\"state\":${q(afterPurchase)},\"unchangedDraws\":${trace.size - draws.size}}"
        } finally {
            NativeRandom.trace = oldTrace; BrowserRandom.restore(rng); NativeRandom.restore(native)
        }
    }

    /** Controlled GetBaseEvent probe: rewards are generated before the first dialogue-box stream sync. */
    private fun rewardEvent(main: MainGame, container: GameContainer, name: String, level: Int, seeds: List<Int>): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val results = ArrayList<String>()
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            for (seed in seeds) {
                var event = game.eventCreator.createSeeded(game.eventManager[name], level, true, seed)!!
                val next = NativeRandom.next31()
                while (true) {
                    val child = event.choices.firstOrNull()?.event ?: break
                    event = child
                }
                results += "{\"seed\":$seed,\"level\":$level,\"fuel\":${event.fuel},\"missiles\":${event.missiles},\"drones\":${event.droneParts},\"scrap\":${event.scrap},\"weapon\":${q(event.weapon?.name ?: "")},\"next\":$next}"
            }
        } finally {
            BrowserRandom.restore(rng); NativeRandom.restore(native)
            main.switchToMainMenu()
        }
        return "[${results.joinToString(",")}]"
    }

    private fun ships(main: MainGame, container: GameContainer, from: Int, count: Int): String {
        val rng = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        val failures = ArrayList<String>()
        val selfDoors = ArrayList<String>()
        val noDodgeSystems = ArrayList<String>()
        var spawned = 0; var paths = 0
        val names = BrowserHsEnemyDiagnostics.enemyBlueprints(main).drop(from).take(count)
        val difficulty = Difficulty.NORMAL
        try {
            main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", difficulty)
            main.update(container, 0f)
            var game = main.currentState as InGameState
            for ((i, name) in names.withIndex()) {
                var stage = "spec"
                try {
                    val elem = Element("ship")
                    elem.setAttribute("name", "PROBE_$name")
                    elem.setAttribute("auto_blueprint", name)
                    val spec = EnemyShipSpec(elem, game.blueprintManager, game.eventManager)
                    stage = "spawn"
                    game.debugSpawnShip(spec, difficulty, (from + i) % 8, 131 + i)
                    val ship = game.enemy ?: continue
                    spawned++
                    if (ship.doors.any { it.left != null && it.left == it.right }) selfDoors += name
                    stage = "evasion"
                    if (ship.piloting == null || ship.engines == null) noDodgeSystems += "$name=${ship.evasion}"
                    ship.evasion
                    stage = "dodge"
                    ship.pickMissed(0, false)
                    stage = "path"
                    for (room in ship.rooms) { ship.pathFinder.path(RoomPoint(room, 0, 0)); paths++ }
                    stage = "frames"
                    repeat(3) { main.update(container, 1f / 30f) }
                } catch (e: Throwable) {
                    failures += "{\"bp\":${q(name)},\"stage\":${q(stage)},\"error\":${q(e.toString() + " @ " + BrowserJsStack.of(e).take(1200))}}"
                    main.startDiagnosticGame(if (HS.active) "PLAYER_SHIP_MVKESTREL" else "PLAYER_SHIP_HARD", difficulty)
                    main.update(container, 0f)
                    game = main.currentState as InGameState
                }
            }
        } finally {
            BrowserRandom.restore(rng); NativeRandom.restore(native)
            main.switchToMainMenu()
        }
        return "{\"blueprints\":${names.size},\"spawned\":$spawned,\"paths\":$paths," +
            "\"selfDoorLayouts\":[${selfDoors.joinToString(",") { q(it) }}]," +
            "\"withoutPilotingOrEngines\":[${noDodgeSystems.joinToString(",") { q(it) }}]," +
            "\"failures\":[${failures.joinToString(",")}]}"
    }
}
