package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.environment.AsbEnvironment
import xyz.znix.xftl.environment.AsbProjectile
import xyz.znix.xftl.environment.NebulaEnvironment
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.JumpWindow
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sector.Sector
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.sys.Input

/**
 * Round 7 (C4): when a beacon the rebel fleet takes gets the ASB.
 *
 * StarMap::OvertakeLocation (0x5f4ca0) swaps the beacon's event for a fresh fleet event, and that event's own
 * environment is the hazard on arrival (WorldManager::CreateLocation 0x6183a0 -> ModifyEnvironment 0x613640; only
 * type 6 calls SpaceManager::SetPlanetaryDefense 0x418ca0). The exit beacon gets FLEET_EASY_BEACON, with "_DLC" (and
 * so the ASB) only when the difficulty (0x916d34) is not easy: a playtester's ASB "that did not fire" was an easy
 * run's exit beacon, which has no ASB natively either (native run: easy exit pds 0 / env 0, normal exit pds 1 / env
 * 6). A beacon in a cloud gets FLEET_EASY_NEBULA, an ion storm (native: nebula and storm flags set, no ASB); an exit
 * in a cloud a plain nebula. An ASB without the fleet puts a PLANET_POPULATED planet at (600, 247) and fires from
 * its centre; a reload restarts the timer with a fresh 20-25 s goal (SpaceManager::LoadSpace 0x41d070).
 */
internal object GameplayParityAsb7Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val easy = exitArrival(main, container, Difficulty.EASY)
            val normal = exitArrival(main, container, Difficulty.NORMAL)
            val events = overtakenEvents(main, container)
            val planet = populatedPlanet(main, container)
            val reload = reloadTimer(main, container)
            return "asb7 ok: $easy, $normal, $events, $planet, $reload"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun flashing(sector: Sector, from: Beacon, beacon: Beacon): Boolean {
        val next = Point(sector.dangerZoneCentre.x + sector.getFleetAdvanceFor(from), sector.dangerZoneCentre.y)
        return beacon.pos.distToSq(next) < Sector.DANGER_ZONE_RADIUS_SQUARED
    }

    /**
     * The playtest jump: the ship sits next to the exit, the map flashes the exit, and the click into it hands it to the
     * fleet. Easy: FLEET_EASY_BEACON, the fleet's ships behind an ordinary scene, no ASB, no warning, no shot in 40 s,
     * nor after a reload. Normal: FLEET_EASY_BEACON_DLC, the ASB on the player, warned and fired within 25 s.
     */
    private fun exitArrival(main: MainGame, container: GameContainer, difficulty: Difficulty): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, difficulty = difficulty)
        state.debugFlags.noDmg.set = true
        val sector = state.currentBeacon.sector
        val exit = sector.beacons.first { it.isExit }
        val from = exit.neighbours.first { !it.isExit }
        // A quiet beacon next to the exit: already visited, so arriving there starts no event.
        from.visited = true
        state.currentBeacon = from
        repeat(60) { main.update(container, .05f) }
        check(state.currentBeacon === from && state.enemy == null) { "Could not park next to the exit" }

        val start = sector.dangerZoneCentre.x
        sector.dangerZoneCentre.x = (-1100..40).firstOrNull { x ->
            sector.dangerZoneCentre.x = x
            flashing(sector, from, exit)
        } ?: start
        check(flashing(sector, from, exit) && !exit.isOvertaken) { "No zone position flashes the exit" }

        state.player.ftlChargeProgress = 1f
        if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
        val window = JumpWindow(state, {}, {})
        window.updateUI(exit.pos.x, exit.pos.y)
        check(window.hovered === exit) { "The map click missed the exit" }
        window.mouseClick(Input.MOUSE_LEFT_BUTTON, exit.pos.x, exit.pos.y)
        check(state.isPlayerJumping) { "The click didn't start a jump" }

        val wanted = "FLEET_EASY_BEACON" + if (difficulty == Difficulty.EASY) "" else "_DLC"
        val eventId = exit.createdEvent.template.deserialisationId
        check(exit.isOvertaken && exit.hasFleetEvent && eventId == "rootEvent::$wanted") { "The exit got $eventId" }

        var frames = 0
        while (state.currentBeacon !== exit && frames++ < 200) main.update(container, .05f)
        check(state.currentBeacon === exit) { "No arrival at the exit" }

        // 40 s at the exit, the dialogue answered: count warnings and ASB shots.
        fun watch(seconds: Float): Pair<Int, Int> {
            var warned = 0
            var shots = 0
            var t = 0f
            while (t < seconds) {
                state.shipUI.diagnosticDialogue?.selectOption(0)
                if (state.isPaused && !state.shipUI.isWindowOpen) state.toggleQuickPause()
                main.update(container, .05f)
                t += .05f
                val env = state.currentBeacon.getEnvironment(state)
                if (env is AsbEnvironment && env.showWarning) warned++
                shots += state.player.projectiles.count { it is AsbProjectile }
                state.player.projectiles.removeAll { it is AsbProjectile }
            }
            return warned to shots
        }

        val env = exit.getEnvironment(state)
        if (difficulty == Difficulty.EASY) {
            check(env !is AsbEnvironment && exit.environmentType == Beacon.EnvironmentType.NORMAL) {
                "The easy exit has the ${env::class.simpleName} scene"
            }
            check(env.diagnosticHasFleet && !env.isDangerous) { "The easy exit: fleet ${env.diagnosticHasFleet}, dangerous ${env.isDangerous}" }
            val (warned, shots) = watch(40f)
            check(warned == 0 && shots == 0) { "The easy exit warned $warned frames and fired $shots shots" }
            val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
            val again = loaded.currentBeacon.getEnvironment(loaded)
            check(loaded.currentBeacon.isExit && again !is AsbEnvironment) { "After a reload the easy exit has ${again::class.simpleName}" }
            return "easy exit: $eventId, fleet scene, no ASB in 40 s"
        }
        check(env is AsbEnvironment && env.diagnosticHasFleet && env.pdsTarget == 0 && env.isDangerous) {
            "The $difficulty exit has the ${env::class.simpleName} scene"
        }
        val (warned, shots) = watch(30f)
        check(warned > 0 && shots > 0) { "The $difficulty exit warned $warned frames and fired $shots shots in 30 s" }
        return "$difficulty exit: $eventId, ASB warned and fired"
    }

    /**
     * The event OvertakeLocation gives and the hazard it brings: FLEET_EASY_DLC (ASB) also on easy and over a beacon
     * whose old event the player had cleared, FLEET_EASY_NEBULA (ion storm) in a cloud even where the old event had a
     * forced nebula, and the exit in a cloud a plain nebula.
     */
    private fun overtakenEvents(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, difficulty = Difficulty.EASY)
        val sector = state.currentBeacon.sector
        val others = sector.beacons.filter { !it.isExit && it !== state.currentBeacon && !it.isOvertaken }
        check(others.size >= 3) { "Too few beacons" }
        fun take(beacon: Beacon): String {
            sector.overtake(state, beacon)
            return beacon.createdEvent.template.deserialisationId.substringAfter("::")
        }

        val cleared = others[0]
        cleared.visited = true
        cleared.clearEvent(false, true)
        val clearedEvent = take(cleared)
        check(clearedEvent == "FLEET_EASY_DLC" && cleared.environmentType == Beacon.EnvironmentType.PDS) {
            "A cleared beacon taken on easy: $clearedEvent, ${cleared.environmentType}"
        }

        val cloud = others[1]
        cloud.inNebula = true
        cloud.forcedNebula = true
        val cloudEvent = take(cloud)
        check(cloudEvent == "FLEET_EASY_NEBULA" && cloud.environmentType == Beacon.EnvironmentType.ION_STORM) {
            "A beacon in a cloud taken: $cloudEvent, ${cloud.environmentType}"
        }
        val storm = cloud.getEnvironment(state)
        check(storm is NebulaEnvironment && storm.ionStorm && !cloud.forcedNebula) { "The taken cloud has the ${storm::class.simpleName} scene" }

        val exit = sector.beacons.first { it.isExit }
        exit.inNebula = true
        val exitEvent = take(exit)
        check(exitEvent == "FLEET_EASY_BEACON" && exit.environmentType == Beacon.EnvironmentType.NEBULA) {
            "The exit in a cloud taken: $exitEvent, ${exit.environmentType}"
        }

        // A save keeps all three.
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val types = listOf(cleared, cloud, exit).map { b ->
            loaded.currentBeacon.sector.beacons.first { it.pos.x == b.pos.x && it.pos.y == b.pos.y }.environmentType
        }
        check(types == listOf(Beacon.EnvironmentType.PDS, Beacon.EnvironmentType.ION_STORM, Beacon.EnvironmentType.NEBULA)) {
            "Reloaded hazards $types"
        }
        return "taken beacons: cleared -> ASB, cloud -> storm, exit in a cloud -> nebula"
    }

    /**
     * SetPlanetaryDefense swaps in a PLANET_POPULATED planet (480 x 460) at (600, 247) when no fleet is there, so the
     * shots leave from (840, 477) less the player ship offset: (640, 307) with an enemy, as the native capture c5-ASB-0
     * showed. With the fleet the planet stays where SwitchPlanet put it.
     */
    private fun populatedPlanet(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        val beacon = state.currentBeacon
        check(beacon.event.fleetBackground == null) { "The start beacon has a fleet" }
        val populated = state.eventManager.getImageList("PLANET_POPULATED").images.map { it.path }
        val asb = AsbEnvironment(state, beacon, 2)
        val planet = asb.diagnosticPlanet
        check(planet != null && planet.subList(0, 4) == listOf(600, 247, 480, 460) && asb.diagnosticPlanetImage!!.path in populated) {
            "The ASB planet is $planet ${asb.diagnosticPlanetImage?.path}"
        }
        check(asb.diagnosticOrigin.x == 840 - 350 && asb.diagnosticOrigin.y == 477 - 170) { "Origin without an enemy ${asb.diagnosticOrigin}" }
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        check(asb.diagnosticOrigin.x == 640 && asb.diagnosticOrigin.y == 307) { "Origin with an enemy ${asb.diagnosticOrigin}" }
        // The same beacon keeps the same picture when the event changes the ASB's target.
        check(AsbEnvironment(state, beacon, 1).diagnosticPlanet == planet) { "A new target changed the planet" }

        val sector = beacon.sector
        val taken = sector.beacons.first { !it.isExit && it !== beacon }
        sector.overtake(state, taken)
        val fleet = AsbEnvironment(state, taken)
        val fleetPlanet = fleet.diagnosticPlanet
        check(fleet.diagnosticHasFleet && (fleetPlanet == null || fleet.diagnosticPlanetImage!!.path !in populated || fleetPlanet[0] != 600 || fleetPlanet[1] != 247)) {
            "The fleet's ASB moved the planet: $fleetPlanet"
        }
        return "populated planet at (600, 247)"
    }

    /** LoadSpace: elapsed time back to the millisecond, a fresh goal in 20-25 s, the warning from the new goal. */
    private fun reloadTimer(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val asb = AsbEnvironment(state, state.currentBeacon, 0)
        val step = 1f / 64f
        var t = 0f
        while (t < 17.3f) {
            asb.update(step)
            t += step
            state.player.projectiles.clear()
        }
        val (time, goal) = asb.diagnosticTimer.toList()
        val goals = HashSet<Float>()
        repeat(6) {
            val elem = Element("environment")
            asb.saveToXML(elem)
            val loaded = AsbEnvironment(state, state.currentBeacon, 0)
            loaded.loadFromXML(elem)
            val (lt, lg) = loaded.diagnosticTimer.toList()
            // Whole milliseconds (compared loosely: the JS build keeps float arithmetic in double precision).
            check(kotlin.math.abs(lt - (time * 1000f).toInt() / 1000f) < 1e-4f && lt <= time) { "Loaded elapsed $lt from $time" }
            check(lg in 20f..25f) { "Loaded goal $lg" }
            check(loaded.showWarning == (lt >= lg - 5f)) { "Loaded warning ${loaded.showWarning} at $lt of $lg" }
            goals += lg
        }
        check(goals.size > 1) { "Every reload kept the goal ${goals.first()} (saved $goal)" }
        return "reload: ${goals.size} goals in 20-25 s"
    }
}
