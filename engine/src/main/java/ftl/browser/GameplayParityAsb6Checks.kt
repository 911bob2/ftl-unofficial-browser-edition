package ftl.browser

import xyz.znix.xftl.environment.AsbEnvironment
import xyz.znix.xftl.environment.AsbProjectile
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
 * Round 6 (F4, F6): the rebel zone's ambience (the background fleet and the ASB) and the ASB shot's look.
 *
 * The scene of a location is decided when the ship arrives (WorldManager::CreateLocation 0x6183a0 -> SetDangerZone
 * 0x41a560 with the event's fleet type, then ModifyEnvironment 0x613640), and is destroyed when the jump-out ends
 * (ClearLocation 0x614d00). The click's StarMap::UpdateDangerZone (0x5f5280) overtakes the beacons the map flashed
 * (OvertakeLocation 0x5f4ca0 swaps their event for the fleet's) but never touches the scene the ship is in, so a
 * beacon that is only taken on the next jump has the ordinary scene, and the one being left keeps its own through
 * the whole jump-out, even when this very jump hands it to the fleet. PDSFire's render (0x458be0) draws its quad with
 * the original's nearest-neighbour filtering and a half size cut to whole pixels.
 */
internal object GameplayParityAsb6Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val flashed = flashedDestination(main, container)
            val later = laterDestination(main, container)
            val departing = departingScene(main, container)
            val visuals = volleyVisuals(main, container)
            return "asb6 ok: $flashed, $later, $departing, $visuals"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** JumpWindow.willBeOvertaken: the beacon lies strictly within 767 px of the zone one step on. */
    private fun flashing(sector: Sector, from: Beacon, beacon: Beacon): Boolean {
        val next = Point(sector.dangerZoneCentre.x + sector.getFleetAdvanceFor(from), sector.dangerZoneCentre.y)
        return beacon.pos.distToSq(next) < Sector.DANGER_ZONE_RADIUS_SQUARED
    }

    /** Move the zone along its row until [wanted] holds; false (zone unchanged) when it never does. */
    private fun placeZone(sector: Sector, wanted: () -> Boolean): Boolean {
        val start = sector.dangerZoneCentre.x
        for (x in -1100..40) {
            sector.dangerZoneCentre.x = x
            if (wanted()) return true
        }
        sector.dangerZoneCentre.x = start
        return false
    }

    /** A click on [dest] in the star map, as the player makes it. */
    private fun clickBeacon(state: InGameState, dest: Beacon) {
        val window = JumpWindow(state, {}, {})
        // The window's beacon origin is only set by its first render, so a new window maps beacons to their own position.
        val x = dest.pos.x
        val y = dest.pos.y
        window.updateUI(x, y)
        check(window.hovered === dest) { "The map click missed the beacon" }
        window.mouseClick(Input.MOUSE_LEFT_BUTTON, x, y)
        check(state.isPlayerJumping && state.currentBeacon !== dest) { "The click didn't start a jump" }
    }

    /** Play on until the ship has switched to [dest] and the first frames of its scene have run. */
    private fun land(main: MainGame, container: GameContainer, state: InGameState, dest: Beacon) {
        var frames = 0
        while (state.currentBeacon !== dest && frames++ < 200)
            main.update(container, .05f)
        check(state.currentBeacon === dest) { "No arrival after 10 s" }
        repeat(5) { main.update(container, .05f) }
    }

    private fun start(main: MainGame, container: GameContainer): InGameState =
        BrowserGameplayParityDiagnostics.fresh(main, container)

    /**
     * A beacon the map flashes is taken by the very jump into it: the fleet is there on arrival, the fleet's
     * event is its event, and the ASB is on the player. The flashing set is the set that jump overtakes.
     */
    private fun flashedDestination(main: MainGame, container: GameContainer): String {
        val state = start(main, container)
        val from = state.currentBeacon
        val sector = from.sector
        val dest = from.neighbours.firstOrNull { d -> placeZone(sector) { flashing(sector, from, d) } }
        checkNotNull(dest) { "No zone position flashes a neighbour" }

        val before = sector.beacons.associateWith { it.isOvertaken }
        val flashSet = sector.beacons.filter { !it.isOvertaken && flashing(sector, from, it) }.toSet()
        check(dest in flashSet && !dest.isOvertaken) { "The destination isn't flashing" }

        clickBeacon(state, dest)
        val taken = sector.beacons.filter { it.isOvertaken && before[it] == false }.toSet()
        check(taken == flashSet) { "The jump took ${taken.size} beacons, the map flashed ${flashSet.size}" }
        land(main, container, state, dest)

        val env = dest.getEnvironment(state)
        check(dest.isOvertaken && dest.hasFleetEvent) { "The flashed destination wasn't taken" }
        check(env is AsbEnvironment && env.diagnosticHasFleet && env.pdsTarget == 0 && env.isDangerous) {
            "The flashed destination arrived in ${env::class.simpleName}, fleet ${env.diagnosticHasFleet}"
        }
        return "flashed destination has the fleet scene"
    }

    /**
     * A beacon that will only be taken on the next jump (the zone is still out of reach this jump) is an ordinary
     * location on arrival: no fleet, no ASB, nothing dangerous - though the map flashes it from then on.
     */
    private fun laterDestination(main: MainGame, container: GameContainer): String {
        val state = start(main, container)
        val from = state.currentBeacon
        val sector = from.sector
        fun flashingAfterArrival(d: Beacon): Boolean {
            val x = sector.dangerZoneCentre.x + sector.getFleetAdvanceFor(from)
            val next = Point(x + sector.getFleetAdvanceFor(d), sector.dangerZoneCentre.y)
            return d.pos.distToSq(next) < Sector.DANGER_ZONE_RADIUS_SQUARED
        }
        // A neighbour whose own event brings a sun, asteroids or a pulsar is dangerous for its own sake; pick an
        // ordinary one (which neighbour that is depends on the fixture seed).
        val dest = from.neighbours.firstOrNull { d ->
            !d.environmentType.isDangerous && placeZone(sector) { !flashing(sector, from, d) && flashingAfterArrival(d) }
        }
        checkNotNull(dest) { "No zone position leaves a neighbour for the next jump" }

        clickBeacon(state, dest)
        land(main, container, state, dest)

        check(!dest.isOvertaken && !dest.hasFleetEvent) { "The destination was taken by the jump into it" }
        check(flashing(sector, dest, dest)) { "The map doesn't flash the beacon the ship is at" }
        val env = dest.getEnvironment(state)
        check(env !is AsbEnvironment && !env.diagnosticHasFleet && !env.isDangerous) {
            "A beacon taken only next jump has the ${env::class.simpleName} scene, fleet ${env.diagnosticHasFleet}"
        }
        return "later destination is ordinary"
    }

    /**
     * The beacon being left, taken by this very jump, keeps its own scene through the jump-out (the same object, no
     * fleet, no ASB) and loses it with the jump; coming back finds the fleet scene, freshly built.
     */
    private fun departingScene(main: MainGame, container: GameContainer): String {
        val state = start(main, container)
        val from = state.currentBeacon
        val sector = from.sector
        val dest = from.neighbours.firstOrNull { d ->
            placeZone(sector) { !from.isExit && flashing(sector, from, from) && !flashing(sector, from, d) }
        }
        checkNotNull(dest) { "No zone position flashes only the beacon being left" }

        val scene = from.getEnvironment(state)
        check(scene !is AsbEnvironment && !scene.diagnosticHasFleet) { "The start beacon has the fleet scene" }
        clickBeacon(state, dest)
        check(from.isOvertaken && from.hasFleetEvent) { "The jump didn't take the beacon being left" }
        check(!dest.isOvertaken) { "The jump took the destination" }

        var frames = 0
        var checked = 0
        while (state.currentBeacon === from && frames++ < 200) {
            if (frames % 5 == 1) {
                val live = from.getEnvironment(state)
                check(live === scene && !live.diagnosticHasFleet && live !is AsbEnvironment) {
                    "The departing scene changed after $frames frames: ${live::class.simpleName}, fleet ${live.diagnosticHasFleet}"
                }
                check(from.environmentType == scene.type) { "The departing hazard changed to ${from.environmentType}" }
                checked++
            }
            main.update(container, .05f)
        }
        check(checked >= 6 && state.currentBeacon === dest) { "The jump-out ran $checked checks, arrived ${state.currentBeacon === dest}" }
        repeat(5) { main.update(container, .05f) }
        val arrival = dest.getEnvironment(state)
        check(arrival !is AsbEnvironment && !arrival.diagnosticHasFleet) { "The destination has the ${arrival::class.simpleName} scene" }
        check(from.getEnvironment(state) !== scene && from.environmentType == Beacon.EnvironmentType.PDS) {
            "The scene outlived the jump: ${from.environmentType}"
        }

        // Back at the beacon the fleet took: a new scene, with the fleet and the ASB.
        state.beginJumpOut(from)
        land(main, container, state, from)
        val back = from.getEnvironment(state)
        check(back !== scene && back is AsbEnvironment && back.diagnosticHasFleet && back.pdsTarget == 0) {
            "The returning ship found ${back::class.simpleName}, fleet ${back.diagnosticHasFleet}"
        }
        return "departing scene kept ($checked frames), fresh fleet scene on return"
    }

    /**
     * PDSFire's quad (0x458be0) is the image at its scale, its top left half the scaled size (cut to whole
     * pixels) up and left of the shot; drawing it doesn't touch the flight, the scale or the damage.
     */
    private fun volleyVisuals(main: MainGame, container: GameContainer): String {
        // Native c6 frames: scale 1.032 -> 44.4 x 25.8 at (-22, -12); scale 8 -> 344 x 200 at (-172, -100).
        val small = AsbProjectile.drawBox(43, 25, 1.032f)
        check(small[0] == -22f && small[1] == -12f && small[2] == 43 * 1.032f && small[3] == 25 * 1.032f) { "Box at 1.032 ${small.toList()}" }
        val eight = AsbProjectile.drawBox(43, 25, 8f)
        check(eight.toList() == listOf(-172f, -100f, 344f, 200f)) { "Box at 8 ${eight.toList()}" }
        val unit = AsbProjectile.drawBox(43, 25, 1f)
        check(unit.toList() == listOf(-21f, -12f, 43f, 25f)) { "Box at 1 ${unit.toList()}" }

        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        val origin = AsbEnvironment(state, state.currentBeacon, 2).diagnosticOrigin
        val step = 1f / 32f

        fun fly(drawn: Boolean): List<Float> {
            player.projectiles.clear()
            enemy.projectiles.clear()
            enemy.health = enemy.maxHealth
            val shot = AsbProjectile.hit(enemy.rooms.first(), player, origin)
            player.projectiles.add(shot)
            val trace = ArrayList<Float>()
            var frames = 0
            while (frames++ < 2000) {
                val space = listOf(player, enemy).firstOrNull { shot in it.projectiles } ?: break
                shot.update(step, space)
                if (drawn) AsbProjectile.drawBox(43, 25, shot.scale)
                trace += shot.position.xf
                trace += shot.position.yf
                trace += shot.scale
            }
            trace += (enemy.maxHealth - enemy.health).toFloat()
            return trace
        }

        val rng = BrowserRandom.snapshot()
        val plain = fly(false)
        BrowserRandom.restore(rng)
        val drawn = fly(true)
        check(plain.size > 100 && plain == drawn) {
            val at = plain.indices.firstOrNull { it >= drawn.size || plain[it] != drawn[it] }
            "Drawing the shot changed its flight (${plain.size} vs ${drawn.size} samples, first difference at $at: ${at?.let { plain[it] }} vs ${at?.let { drawn.getOrNull(it) }})"
        }
        // The volley's damage itself is GameplayParityCombat3Checks.asbVolley's: the flights agree down to the hull.
        return "volley visuals (${plain.size / 3} frames)"
    }
}
