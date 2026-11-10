package ftl.browser

import xyz.znix.xftl.environment.AbstractEnvironment
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.JumpWindow
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.ship.HsShipStart
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep follow-up FU5 (events, world and saves). Every mode (vanilla, F2, F3):
 * - a location whose hostile ship was left behind: WorldManager::ClearLocation (1.6.14 0x614d00) saves the ship into the
 *   event's ShipEvent::actualBlueprint, and LocationEvent::ClearEvent (0x6119f0, 1.6.9 0x5ec800) branches on that name
 *   (+0x1d0, the length of actualBlueprint.blueprintName at +0x1cc; WorldManager::CreateShip 0x6149c0 reads the same
 *   string, +0x1b4 of the ShipEvent, to rebuild the ship): with a name the environment stays (asteroids, a storm),
 *   without one only a nebula is left. Nothing empties the name while the event stays, so a ship destroyed on a later
 *   visit does not bring the clear back, nor does StarMap::LoadGame's ClearEvent on Continue. The event's flags
 *   (distress, repair) go in both branches.
 * - the asteroid field during the jump zoom: CommandGui::RenderStatic (1.6.14 0x535070) draws SpaceManager::
 *   OnRenderAsteroids(3) before the player's ship and OnRenderAsteroids(1), the front layer, over it while the zoom is
 *   above zero, both at 1 - 0.3 zoom (exe data 0x87d5b4) about the hull centre.
 * - the enemy's boarding drone aboard the player's ship when the fight ends with the enemy still there: its crew drone
 *   dies with its explosion (HS oracle ps-fu5-edrone2), no longer taken away silently.
 * - Hyperspace's CreateLocation / UpdateLocation hooks (CustomEvents.cpp, priority 100): an event that takes crew while
 *   the player's ship holds none of its own crew takes none (stuff.crew 0, as Lua reads it; HS oracle ps-fu5-nocrew).
 * - Hyperspace's ProjectileFactory constructor hook (CustomWeapons.cpp): a weapon built from a blueprint that does not
 *   exist is a Basic Laser (LASER_BURST_1); the original keeps the empty weapon (no vanilla data meets it).
 */
internal object GameplayParityPsFu5Checks {
    fun run(main: MainGame, container: GameContainer, graphics: Graphics): String {
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
            case("savedShip") { savedShip(main, container) }
            case("missingWeapon") { missingWeapon(main, container) }
            case("asteroidFront") { asteroidFront(main, container, graphics) }
            case("crewLossWithoutCrew") { crewLossWithoutCrew(main, container) }
            case("enemyDroneFightEnd") { enemyDroneFightEnd(main, container) }
        } finally {
            AbstractEnvironment.browserDrawLog = null
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-fu5 ok: " + results.joinToString(", ")
    }

    private fun savedShip(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val events = state.eventManager
        val asteroid = events.getOrNull("AUTO_ASTEROID")?.resolve()
            ?: return "saved ship (no AUTO_ASTEROID in this data)"
        val sector = state.currentBeacon.sector
        val others = sector.beacons.filter { !it.isExit && !it.isBase && !it.isOvertaken && it !== state.currentBeacon }

        // Without a saved ship the hazard goes; with one it stays, also once the ship is gone and on every later clear.
        val plain = others[0]
        plain.event = asteroid
        plain.clearEnvironment()
        plain.clearEvent(false, true)
        check(plain.environmentType == Beacon.EnvironmentType.NORMAL) { "a left asteroid beacon is ${plain.environmentType}" }

        val kept = others[1]
        kept.event = asteroid
        kept.clearEnvironment()
        kept.shipSaved = true
        kept.clearEvent(false, true)
        check(kept.environmentType == Beacon.EnvironmentType.ASTEROID) { "a ship saved here: the asteroids went (${kept.environmentType})" }
        check(!JumpWindow.eventFlagsLive(kept)) { "a ship saved here: the event's flags stay" }
        kept.clearEvent(false, true)
        check(kept.environmentType == Beacon.EnvironmentType.ASTEROID) { "the second clear took the asteroids (${kept.environmentType})" }
        kept.visited = true
        sector.clearVisitedEvents(state.currentBeacon)
        check(kept.environmentType == Beacon.EnvironmentType.ASTEROID) { "StarMap::LoadGame's clear took the asteroids" }

        // A hazard already cleared stays cleared when a ship is saved there later.
        plain.shipSaved = true
        plain.clearEvent(false, true)
        check(plain.environmentType == Beacon.EnvironmentType.NORMAL) { "a later saved ship brought the asteroids back" }

        // A new event at the beacon has no saved ship.
        kept.event = asteroid
        check(!kept.shipSaved && !kept.hazardKept) { "a new event kept the saved ship" }

        // An ion storm with a saved ship stays a storm without the nebula's sensors cap; without one it is a nebula.
        var storm = "storm: no STORM_AUTO"
        val stormEvent = events.getOrNull("STORM_AUTO")?.resolve()
        if (stormEvent != null && stormEvent.environment == Beacon.EnvironmentType.ION_STORM) {
            val s1 = others[2]
            s1.event = stormEvent
            s1.clearEnvironment()
            s1.shipSaved = true
            s1.clearEvent(false, true)
            check(s1.environmentType == Beacon.EnvironmentType.ION_STORM) { "a storm with a saved ship is ${s1.environmentType}" }
            check(s1.powerLimitEffects.isEmpty()) { "a storm with a saved ship: ${s1.powerLimitEffects}" }
            val s2 = others[3]
            s2.event = stormEvent
            s2.clearEnvironment()
            s2.clearEvent(false, true)
            check(s2.environmentType == Beacon.EnvironmentType.NEBULA) { "a storm left without a ship is ${s2.environmentType}" }
            check(s2.powerLimitEffects == mapOf("sensors" to 0)) { "a storm left without a ship: ${s2.powerLimitEffects}" }
            storm = "storm kept"
        }

        // ClearLocation with the enemy still here, then again after it was destroyed on the return; and Continue.
        val here = state.currentBeacon
        here.event = asteroid
        here.clearEnvironment()
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 1, 7)
        check(here.ship != null) { "the spawned enemy is not at the beacon" }
        state.clearLocation()
        check(here.shipSaved && here.environmentType == Beacon.EnvironmentType.ASTEROID) {
            "left with the enemy here: saved ${here.shipSaved}, ${here.environmentType}"
        }
        here.ship = null
        state.clearLocation()
        check(here.environmentType == Beacon.EnvironmentType.ASTEROID) { "left after the enemy was destroyed: ${here.environmentType}" }
        val index = sector.beacons.indexOf(here)
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val loaded = state.currentBeacon.sector.beacons[index]
        check(loaded.shipSaved && loaded.hazardKept && loaded.environmentType == Beacon.EnvironmentType.ASTEROID) {
            "after a reload: saved ${loaded.shipSaved}, kept ${loaded.hazardKept}, ${loaded.environmentType}"
        }
        return "saved ship keeps the hazard ($storm)"
    }

    private fun missingWeapon(main: MainGame, container: GameContainer): String {
        val state: InGameState = BrowserGameplayParityDiagnostics.fresh(main, container)
        val manager = state.blueprintManager
        val fallback = HsShipStart.missingWeapon(manager)
        if (!xyz.znix.xftl.hs.HS.active) {
            check(fallback == null) { "vanilla gives a missing weapon ${fallback?.name}" }
            return "missing weapon (vanilla: none)"
        }
        check(fallback?.name == "LASER_BURST_1") { "Hyperspace's missing weapon is ${fallback?.name}" }
        val ship = state.player
        if (ship.weapons == null || ship.hardpoints.isEmpty()) return "missing weapon (no weapon system)"
        ship.hardpoints.forEach { it.weapon = null }
        HsShipStart.loadEquipment(ship, HsShipStart.Equipment(listOf(null, "PSFU5_NO_WEAPON"), emptyList()))
        val names = ship.hardpoints.take(2).map { it.weapon?.type?.name }
        val want = if (ship.hardpoints.size > 1) listOf("LASER_BURST_1", "LASER_BURST_1") else listOf("LASER_BURST_1")
        check(names.take(want.size) == want) { "a ship naming missing weapons starts with $names" }
        return "missing weapon is a Basic Laser"
    }

    private fun asteroidFront(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val beacon = state.currentBeacon
        beacon.debugSetEnvironment(Beacon.EnvironmentType.ASTEROID.create(state, beacon))
        fun frame(): List<String> {
            val log = ArrayList<Pair<String, Any?>>()
            AbstractEnvironment.browserDrawLog = log
            try {
                main.render(container, graphics)
            } finally {
                AbstractEnvironment.browserDrawLog = null
            }
            return log.map { it.first }.filter { it.startsWith("asteroids") }
        }
        val still = frame()
        check(still == listOf("asteroids")) { "an asteroid field without a jump draws $still" }
        state.beginJumpOut(beacon.neighbours.first())
        repeat(6) { main.update(container, .05f) }
        val jumping = frame()
        check(jumping == listOf("asteroids", "asteroidsFront")) { "the jump zoom draws $jumping" }
        return "asteroid front layer in the jump zoom"
    }

    private fun crewLossWithoutCrew(main: MainGame, container: GameContainer): String {
        if (!xyz.znix.xftl.hs.HS.active) return "crew loss without crew (Hyperspace only)"
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val template = state.eventManager.getOrNull("CREW_DEAD_TEST")?.resolve() ?: return "crew loss without crew (no CREW_DEAD_TEST)"
        check(template.removedCrew.isNotEmpty()) { "CREW_DEAD_TEST takes no crew" }
        val withCrew = xyz.znix.xftl.sector.CreatedEvent.fromTemplate(template)
        xyz.znix.xftl.hs.events.HsFlow.clearCrewLossWithoutCrew(state, withCrew)
        check(!withCrew.hsCrewLossCleared) { "the crew loss was cleared with the crew aboard" }
        // Dead (bDead), not only at health 0: ShipManager::CountCrew still counts a crew member in its death hold.
        for (c in state.player.crew) if (c is xyz.znix.xftl.crew.LivingCrew) c.hsKillNow()
        val without = xyz.znix.xftl.sector.CreatedEvent.fromTemplate(template)
        xyz.znix.xftl.hs.events.HsFlow.clearCrewLossWithoutCrew(state, without)
        check(without.hsCrewLossCleared) { "the crew loss stayed with no own crew aboard" }
        return "crew loss without crew aboard"
    }

    private fun enemyDroneFightEnd(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        // A generated enemy with a drone system: the first seed that gives the pirate or rebel one.
        val specs = listOf("PIRATE", "REBEL").mapNotNull { runCatching { state.eventManager.getShip(it) }.getOrNull() }
        found@ for (seed in 71 until 171) for (spec in specs) {
            state.debugSpawnShip(spec, Difficulty.NORMAL, 6, seed)
            if (state.enemy?.drones != null) break@found
        }
        val enemy = state.enemy!!
        val player = state.player
        checkNotNull(enemy.drones) { "No generated enemy with a drone system" }
        val drones = enemy.drones!!
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        enemy.purchasedReactorPower = 25
        drones.energyLevels = 8
        enemy.updateAvailablePower()
        enemy.dronesCount = 5
        if (drones.drones.isEmpty()) drones.drones.add(null)
        val info = xyz.znix.xftl.systems.Drones.DroneInfo(state.blueprintManager["BOARDER"] as xyz.znix.xftl.weapons.DroneBlueprint)
        drones.drones[0] = info
        check(drones.setDronePower(0, true)) { "The enemy's boarding drone did not launch" }
        val instance = info.instance as xyz.znix.xftl.drones.BoardingDrone
        state.debugFlags.noCrewDamage.set = true
        repeat(600) { if (instance.pawn == null) state.updateGameState(.05f) }
        state.debugFlags.noCrewDamage.set = false
        val pawn = checkNotNull(instance.pawn) { "The enemy's boarding drone did not land" }
        check(pawn.room.ship === player) { "The enemy's drone landed on its own ship" }
        val boom = checkNotNull(instance.explosionSpec) { "The boarding drone has no explosion" }
        fun booms() = player.animations.count { it.spec === boom }
        val before = booms()
        state.browserEndHostility()
        repeat(2) { state.updateGameState(.05f) }
        check(instance.pawn == null && !player.crew.contains(pawn)) { "The enemy's crew drone stayed aboard" }
        check(booms() == before + 1) { "No explosion where the enemy's crew drone was (${booms() - before})" }
        return "enemy drone at a fight's end explodes"
    }
}
