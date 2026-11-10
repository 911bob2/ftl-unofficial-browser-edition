package ftl.browser

import org.jdom2.Element
import xyz.znix.xftl.environment.AbstractEnvironment
import xyz.znix.xftl.environment.AsteroidEnvironment
import xyz.znix.xftl.environment.BeaconScene
import xyz.znix.xftl.environment.NebulaEnvironment
import xyz.znix.xftl.environment.PulsarEnvironment
import xyz.znix.xftl.environment.SunEnvironment
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep W5 (hazards, environments and the scene). Every mode (vanilla, F2, F3):
 * - pulsar timer: SpaceManager::SetPulsarLevel (1.6.14 0x418f70) runs TimerHelper::ResetMinMax(11000, 18000) and
 *   Start(-1) (0x5cb0b0): the first goal is `(random32() % 7000 + 11000) / 1000` seconds on the global stream, and every
 *   goal TimerHelper::Done (0x5cb200) draws when it loops is a whole number of milliseconds in 11..18 s.
 * - a jumping ship: ShipManager::SunDamage (0x4a4e80) and the pulsar hit (0x4a36b0) return at once while the ship's
 *   bJumping is set (GetIsJumping); a flare or a pulse during the jump-out leaves the jumping ship alone.
 * - the scene under a hazard: SpaceManager::OnRenderBackground (0x4194c0) draws the beacon's backdrop under the sun and
 *   the asteroids (only SetPulsarLevel switches it to the dull star field); OnRenderForeground (0x419e30) draws the
 *   planet at an asteroid field (HS oracle wp6-map-env e03-stack), the sun or the pulsar in its place, and the fleet
 *   under every one; while a nebula is up only its clouds are drawn (Hyperspace's stacked hazards).
 * - a return to a beacon: WorldManager::ClearLocation (0x614d00) clears the ship's statuses (0x49e2b0) and
 *   LocationEvent::ClearEvent (0x6119f0) empties the event's, giving a nebula its GetNebulaEffect back (exe data
 *   0x8848f0: sensors limited to 0, both ships). Native ps-w5-van w5-rev / w5-rev2: back at a cleared nebula the sensors
 *   are capped again; back where an event limited systems, the limits are gone.
 */
internal object GameplayParityPsW5Checks {
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
            case("pulsarTimer") { pulsarTimer(main, container) }
            case("jumpingShip") { jumpingShip(main, container) }
            case("scene") { scene(main, container, graphics) }
            case("revisit") { revisit(main, container) }
        } finally {
            AbstractEnvironment.browserDrawLog = null
            BrowserEngine.resetInput()
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-w5 ok: " + results.joinToString(", ")
    }

    private fun pulsarTimer(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val beacon = state.currentBeacon
        BeaconScene.arrive(state, beacon)
        NativeRandom.seed(0x5eed)
        val r = NativeRandom.next31()
        NativeRandom.seed(0x5eed)
        val pulsar = PulsarEnvironment(state, beacon)
        val want = (11000 + r % 7000) / 1000f
        check(pulsar.diagnosticTimeToPulse == want) { "first pulse in ${pulsar.diagnosticTimeToPulse} s, want $want (draw $r)" }
        // The later goals: whole milliseconds in 11..18 s.
        val goals = ArrayList<Float>()
        var last = pulsar.diagnosticTimeToPulse
        repeat(3000) {
            pulsar.update(0.05f)
            val now = pulsar.diagnosticTimeToPulse
            if (now > last + 1f) goals += now
            last = now
        }
        check(goals.size >= 5) { "only ${goals.size} pulses in 150 s" }
        for (goal in goals) {
            val ms = goal * 1000f
            check(ms >= 11000f && ms < 18000f && kotlin.math.abs(ms - kotlin.math.round(ms)) < 0.05f) { "pulse goal $goal s" }
        }
        return "pulsar goals (first $want s, ${goals.size} later)"
    }

    private fun fires(state: InGameState): Int = state.player.rooms.sumOf { room -> room.fires.count { it != null } }
    private fun ionised(state: InGameState): Int = state.player.systems.count { it.isIonised || it.ionPowerLimit != null }

    private fun jumpingShip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val beacon = state.currentBeacon
        state.debugFlags.noDmg.set = true
        val sun = SunEnvironment(state, beacon)
        fun flare(jumping: Boolean): Int {
            val e = Element("beacon")
            e.setAttribute("sunTimer", "10.0")
            e.setAttribute("nextFlare", "10.01")
            e.setAttribute("lastFlare", "-100.0")
            sun.loadFromXML(e)
            for (room in state.player.rooms) for (i in room.fires.indices) room.fires[i] = null
            state.player.browserJumping = jumping
            sun.update(0.05f)
            return fires(state)
        }
        check(flare(true) == 0) { "a flare set fires on the jumping ship" }
        check(flare(false) > 0) { "a flare set no fire on the ship at rest" }

        val pulsar = PulsarEnvironment(state, beacon)
        fun pulse(jumping: Boolean): Int {
            val e = Element("beacon")
            e.setAttribute("nextPulse", "0.01")
            pulsar.loadFromXML(e)
            state.player.superShield = 0
            state.player.browserJumping = jumping
            pulsar.update(0.05f)
            return ionised(state)
        }
        check(ionised(state) == 0) { "the fixture starts ionised" }
        check(pulse(true) == 0) { "a pulse ionised the jumping ship" }
        check(pulse(false) > 0) { "a pulse ionised nothing on the ship at rest" }
        state.player.browserJumping = false
        return "no flare or pulse on a jumping ship"
    }

    private fun draws(env: AbstractEnvironment, container: GameContainer, graphics: Graphics): List<Pair<String, Any?>> {
        val log = ArrayList<Pair<String, Any?>>()
        AbstractEnvironment.browserDrawLog = log
        try {
            env.renderStack(container, graphics)
        } finally {
            AbstractEnvironment.browserDrawLog = null
        }
        return log
    }

    private fun scene(main: MainGame, container: GameContainer, graphics: Graphics): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val beacon = state.currentBeacon
        // A scene with a backdrop and a planet.
        val scene = BeaconScene()
        scene.backdrop = "img/stars/bg_lonelystar.png"
        scene.planet = "img/stars/planet_red.png"
        scene.planetW = 200; scene.planetH = 200; scene.planetX = 300; scene.planetY = 200
        val saved = beacon.scene
        beacon.scene = scene
        try {
            val back = state.getImg("img/stars/bg_lonelystar.png")
            val dull = state.getImg("img/stars/bg_dullstars.png")
            val planet = state.getImg("img/stars/planet_red.png")
            fun kinds(log: List<Pair<String, Any?>>) = log.map { it.first }

            val asteroids = NativeRandom.isolated { AsteroidEnvironment(state, beacon) }
            val a = draws(asteroids, container, graphics)
            check(kinds(a) == listOf("back", "planet", "asteroids") && a[0].second === back && a[1].second === planet) {
                "asteroid field draws ${kinds(a)} (backdrop ${a.firstOrNull()?.second === back})"
            }
            val sun = NativeRandom.isolated { SunEnvironment(state, beacon) }
            val s = draws(sun, container, graphics)
            check(kinds(s) == listOf("back", "sun") && s[0].second === back) { "sun draws ${kinds(s)} (backdrop ${s.firstOrNull()?.second === back})" }
            val pulsar = NativeRandom.isolated { PulsarEnvironment(state, beacon) }
            val p = draws(pulsar, container, graphics)
            check(kinds(p).take(2) == listOf("back", "pulsar") && p[0].second === dull && "planet" !in kinds(p)) { "pulsar draws ${kinds(p)}" }

            // Hyperspace stacks: asteroids on a sun keep the beacon's backdrop; a nebula hides everything but its clouds
            // and the asteroid layer.
            val sunBase = NativeRandom.isolated { SunEnvironment(state, beacon) }
            sunBase.stack(NativeRandom.isolated { AsteroidEnvironment(state, beacon) })
            val sa = draws(sunBase, container, graphics)
            check(kinds(sa) == listOf("back", "sun", "asteroids") && sa[0].second === back) { "sun with asteroids draws ${kinds(sa)}" }
            val nebula = NativeRandom.isolated { NebulaEnvironment(state, beacon, false).also { it.setNebula(0.05f) } }
            nebula.stack(NativeRandom.isolated { AsteroidEnvironment(state, beacon) })
            nebula.stack(NativeRandom.isolated { SunEnvironment(state, beacon) })
            val n = draws(nebula, container, graphics)
            check(kinds(n) == listOf("clouds", "asteroids")) { "nebula with asteroids and a sun draws ${kinds(n)}" }
        } finally {
            beacon.scene = saved
        }
        return "scene under asteroids, sun, pulsar and a nebula stack"
    }

    private fun revisit(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val beacon = state.currentBeacon
        // An event's limits (TEST_EVENT2: sensors, doors, engines, weapons limited to 1) go when the location is left.
        beacon.powerLimitEffects["weapons"] = 1
        beacon.powerLimitEffects["sensors"] = 1
        beacon.powerLossEffects["shields"] = 1
        beacon.clearEvent(false, true)
        val nebulaBeacon = beacon.nebulaLocation
        if (!nebulaBeacon) {
            check(beacon.powerLimitEffects.isEmpty() && beacon.powerLossEffects.isEmpty()) {
                "statuses kept by a cleared beacon: ${beacon.powerLimitEffects} ${beacon.powerLossEffects}"
            }
        }
        // A nebula location keeps (or gets back) the nebula's sensors limit, and nothing else (a start event's Hyperspace
        // removeNebula / removeHazards would leave the scene plain, so they are off here).
        beacon.forcedNebula = true
        beacon.eventCleared = false
        beacon.hsNebulaRemoved = false
        beacon.hsHazardsRemoved = false
        beacon.powerLimitEffects["weapons"] = 1
        beacon.clearEvent(false, true)
        check(beacon.environmentType == Beacon.EnvironmentType.NEBULA) { "a cleared nebula beacon is ${beacon.environmentType}" }
        check(beacon.powerLimitEffects == mapOf("sensors" to 0) && beacon.powerLossEffects.isEmpty()) {
            "statuses of a cleared nebula beacon: ${beacon.powerLimitEffects} ${beacon.powerLossEffects}"
        }
        state.player.updateScriptedPowerLimits()
        val sensors = state.player.systems.firstOrNull { it.codename == "sensors" }
        check(sensors == null || sensors.scriptedPowerLimit == 0) { "the sensors on the return: limit ${sensors?.scriptedPowerLimit}" }
        val weapons = state.player.systems.firstOrNull { it.codename == "weapons" }
        check(weapons == null || weapons.scriptedPowerLimit == null) { "the weapons on the return: limit ${weapons?.scriptedPowerLimit}" }
        return "revisit statuses"
    }
}
