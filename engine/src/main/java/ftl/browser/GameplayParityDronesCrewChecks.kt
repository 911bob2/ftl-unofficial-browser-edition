package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewCrystal
import xyz.znix.xftl.crew.CrewHuman
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.drones.CrewDroneBodies
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.IonPulseClock
import xyz.znix.xftl.drones.PodHealth
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.IProjectile
import xyz.znix.xftl.weapons.ProjectileContact
import org.jdom2.Element
import xyz.znix.xftl.systems.Oxygen
import xyz.znix.xftl.weapons.DroneBlueprint
import kotlin.math.abs

/**
 * Drones and crew (CrewMember overrides per race, the ShipManager crew loop 0x4aa670, the Crystal lockdown charge
 * 0x483cc0 / 0x487e70, crew-drone bodies 0x4b0fc0): race stat tables, the suffocation threshold, the crystal charge
 * and its save round trip, and the draws a generated crew drone takes from the native stream.
 */
internal object GameplayParityDronesCrewChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val races = raceStats(main, container)
            val air = suffocation(main, container)
            val crystal = crystalCharge(main, container)
            val bodies = droneBodies(main, container)
            val pods = podRules(main, container)
            val cooldown = droneCooldown(main, container)
            val natives = nativeConstants()
            val fallback = humanFallback(main, container)
            val ion = ionPulseCadence()
            val lockdown = lockdownDoors(main, container)
            return "dronescrew ok: $races, $air, $crystal, $bodies, $pods, $cooldown, $natives, $fallback, $ion, $lockdown"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun recruit(state: InGameState, race: String, room: Room): LivingCrew {
        val info = LivingCrewInfo.generateRandom(state.blueprintManager[race] as CrewBlueprint, state)
        return state.player.addCrewMemberInRoom(info, room, false)
    }

    /** The per-race overrides: speed multiplier on the 80 pixel/s base (5 pixels per frame factor), health, fire. */
    private fun raceStats(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val room = state.player.rooms.maxByOrNull { it.cellCount }!!
        val base = AbstractCrew.BASE_MOVEMENT_SPEED
        check(base == 80f) { "Base speed $base" }

        // race -> (speed multiplier, max health)
        val table = mapOf(
            "human" to (1f to 100f),
            "mantis" to (1.2f to 100f),
            "rock" to (0.5f to 150f),
            "crystal" to (0.8f to 125f),
            "energy" to (1f to 70f),
        )
        for ((race, expected) in table) {
            val crew = recruit(state, race, room)
            // The multiplier is a float constant (1.2f is not exact), and the check's product is
            // taken at runtime in the browser's number type while the race's is folded at
            // compile time, so compare to a thousandth of a pixel per second.
            check(abs(crew.movementSpeed - base * expected.first) < 0.001f) { "$race speed ${crew.movementSpeed}" }
            check(crew.baseMaxHealth == expected.second) { "$race health ${crew.baseMaxHealth}" }
        }
        check(recruit(state, "rock", room).fireDamageMult == 0f) { "Rock crew burn" }
        return "race speed and health table"
    }

    /** The crew loop suffocates a crewmember when the room has 5% oxygen or less. */
    private fun suffocation(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val room = state.player.rooms.maxByOrNull { it.cellCount }!!
        val crew = recruit(state, "human", room)

        room.oxygen = Oxygen.OXYGEN_CRITICAL_LEVEL + 0.001f
        val safe = crew.health
        crew.update(0.1f)
        check(crew.health >= safe) { "Crew took suffocation damage above 5% oxygen" }

        room.oxygen = Oxygen.OXYGEN_CRITICAL_LEVEL
        val before = crew.health
        crew.update(0.1f)
        check(crew.health < before) { "Crew did not suffocate at exactly 5% oxygen" }
        return "suffocation at <= 5%"
    }

    /** The Crystal lockdown power: spent in full, regrows at one second per second, survives a save. */
    private fun crystalCharge(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val room = state.player.rooms.maxByOrNull { it.cellCount }!!
        val crystal = recruit(state, "crystal", room) as CrewCrystal

        check(CrewCrystal.LOCKDOWN_COOLDOWN == 50f) { "Charge target" }
        crystal.resetLockdown()
        check(crystal.lockdownReady && crystal.lockdownCooldown == 0f) { "Fully charged crystal is not ready" }
        check(crystal.activateLockdown() && room.isLockedDown) { "Lockdown did not fire" }
        check(crystal.lockdownCharge == 0f && !crystal.lockdownReady) { "Lockdown was not spent in full" }

        crystal.update(10f)
        check(crystal.lockdownCharge in 9.9f..10.1f) { "Charge after 10 s is ${crystal.lockdownCharge}" }

        // An enemy crystal starts with 10 s of charge; a coin flip decides whether it can ever be ready.
        crystal.initEnemyPower(false)
        check(crystal.lockdownCharge == 10f && crystal.lockdownChargeMax == 50f && !crystal.lockdownReady) { "GameplayParityDronesCrewChecks.kt:101" }
        crystal.initEnemyPower(true)
        check(crystal.lockdownCharge == 10f && crystal.lockdownChargeMax == Float.MAX_VALUE && !crystal.lockdownReady) { "GameplayParityDronesCrewChecks.kt:103" }

        crystal.initEnemyPower(false)
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val loaded = state.player.crew.filterIsInstance<CrewCrystal>().singleOrNull()
        check(loaded != null && loaded.lockdownCharge == 10f && loaded.lockdownChargeMax == 50f) {
            "Crystal charge did not survive a save"
        }
        return "crystal charge model and save"
    }

    /** A repair or anti-personnel drone body draws from the native stream when it is added; other drones do not. */
    private fun droneBodies(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        fun draws(name: String, seed: Int): Int {
            NativeRandom.seed(seed)
            CrewDroneBodies.drawFor(state, state.blueprintManager[name] as DroneBlueprint)
            val after = NativeRandom.next31()
            NativeRandom.seed(seed)
            var count = 0
            while (NativeRandom.next31() != after && count < 16) count++
            return count
        }
        for (name in listOf("DEFENSE_1", "COMBAT_1", "BOARDER")) {
            check(draws(name, 77) == 0) { "$name drew from the native stream" }
        }
        for (name in listOf("REPAIR", "BATTLE")) {
            check(draws(name, 77) >= 2) { "$name drew no timer and name" }
        }
        return "crew drone body draws"
    }

    /** A shot that only carries the weapon a drone pod's collision rules look at. */
    private class FakeShot(private val weapon: AbstractWeaponBlueprint?, override val antiDroneExemption: Ship?) : IProjectile {
        override val position = ConstFPoint(0f, 0f)
        override val velocity = ConstFPoint(0f, 0f)
        override val antiDroneBP: AbstractWeaponBlueprint? get() = weapon
        override val serialisationType: String get() = "fake"
        override fun update(dt: Float, currentSpace: Ship) {}
        override fun render(g: Graphics, currentSpace: Ship) {}
        override fun hitOtherProjectile(currentSpace: Ship) {}
        override fun saveToXML(elem: Element, refs: ObjectRefs) {}
    }

    /**
     * SpaceDrone::CollisionMoving (0x420c90) for the boarder and hacking pods (PodHealth): the dodge roll is made for
     * every shot but only a powered drone makes use of it; ion damage stuns a powered drone for five seconds per point;
     * hull damage blows the pod up, and it is only gone once its explosion animation has finished (0x428010);
     * Drone::OnLoop (0x462220): a stunned drone rolls rand%20 < 3 for each whole second beyond two.
     */
    private fun podRules(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val laser = state.blueprintManager["LASER_BURST_1"] as AbstractWeaponBlueprint
        val ion = state.blueprintManager["ION_1"] as AbstractWeaponBlueprint
        check(laser.damage > 0 && ion.ionDamage > 0) { "Fixture weapons" }
        val origin = ConstFPoint(0f, 0f)

        // Dodge 10 always rolls under the dodge value.
        var pod = PodHealth(state, 10)
        check(pod.contact(FakeShot(laser, null), ship, ship, origin, true) == ProjectileContact.IGNORED && !pod.isBlowingUp) {
            "A powered pod with certain dodge was hit"
        }
        check(pod.contact(FakeShot(laser, null), ship, ship, origin, false) == ProjectileContact.SURVIVED && pod.isBlowingUp) {
            "An unpowered pod dodged"
        }
        check(pod.contact(FakeShot(laser, null), ship, ship, origin, true) == ProjectileContact.IGNORED) {
            "An exploding pod was hit again"
        }

        // No dodge: ion stuns a powered pod, hull damage explodes it, and a shot with no weapon passes through.
        pod = PodHealth(state, 0)
        check(pod.contact(FakeShot(null, null), ship, ship, origin, true) == ProjectileContact.IGNORED) { "A weaponless shot hit the pod" }
        check(pod.contact(FakeShot(laser, ship), ship, ship, origin, true) == ProjectileContact.IGNORED) { "The owner's own shot hit the pod" }
        check(pod.contact(FakeShot(ion, null), ship, ship, origin, true) == ProjectileContact.SURVIVED &&
            pod.ionTimer == 5f * ion.ionDamage && !pod.isBlowingUp) { "Ion stun was ${pod.ionTimer}" }
        pod = PodHealth(state, 0)
        check(pod.contact(FakeShot(ion, null), ship, ship, origin, false) == ProjectileContact.SURVIVED && pod.ionTimer == 0f) {
            "An unpowered pod was stunned"
        }
        check(pod.contact(FakeShot(laser, null), ship, ship, origin, true) == ProjectileContact.SURVIVED && pod.isBlowingUp) {
            "Hull damage did not explode the pod"
        }

        // The explosion plays out, then the pod is finished. The hacking pod is never rebuilt, so this is final.
        var steps = 0
        while (!pod.update(0.05f, false) && steps < 400) steps++
        check(steps in 1..399 && !pod.isBlowingUp) { "Pod explosion ended after $steps steps" }

        // A stunned pod rolls to explode; one that is not stunned never does.
        // (The pod is finished once its explosion ends, so watch for the explosion rather than its state at the end.)
        fun explodesWithin(seconds: Int, stunned: Boolean): Boolean {
            val subject = PodHealth(state, 0)
            var exploded = false
            repeat(seconds * 2) { if (subject.update(0.5f, stunned) || subject.isBlowingUp) exploded = true }
            return exploded
        }
        check(!explodesWithin(200, false)) { "An unstunned pod exploded" }
        check(explodesWithin(200, true)) { "A pod stunned for 200 s never rolled to explode" }
        check(!explodesWithin(2, true)) { "A pod rolled to explode before two seconds of stun" }
        return "pod collision, stun and explosion"
    }

    /** DroneSystem power on (0x638b80): a drone on its destruction cooldown is not launched and costs no part. */
    private fun droneCooldown(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_CIRCLE")
        state.player.purchasedReactorPower = 25
        val drones = state.player.drones!!
        drones.energyLevels = 8
        drones.drones.indices.forEach { drones.setDronePower(it, false); drones.drones[it]?.instance?.removeInstance() }
        val info = Drones.DroneInfo(state.blueprintManager["DRONE_SHIELD_PLAYER"] as DroneBlueprint)
        drones.drones[0] = info
        val parts = state.player.dronesCount
        check(parts > 0) { "No drone parts in the fixture" }

        info.cooldown = 5f
        check(!drones.setDronePower(0, true) && info.instance == null && state.player.dronesCount == parts) {
            "A drone on its rebuild cooldown was launched"
        }
        info.cooldown = null
        check(drones.setDronePower(0, true) && info.instance?.isPowered == true && state.player.dronesCount == parts - 1) {
            "Launch did not take exactly one part"
        }
        check(drones.setDronePower(0, false) && drones.setDronePower(0, true) && state.player.dronesCount == parts - 1) {
            "Repowering a deployed drone took another part"
        }
        return "drone rebuild cooldown and part use"
    }

    /** Native constants the crew and intruder code was ported from. */
    private fun nativeConstants(): String {
        // The crew laser flies at speed 10 per frame factor (constructor 0x45cab0): 160 pixels per second.
        check(xyz.znix.xftl.crew.CrewShot.SPEED == 160f) { "Crew laser speed" }
        // The drone destroyed cooldown is ten seconds (0x462110 / 0x462470).
        check(xyz.znix.xftl.drones.AbstractDrone.DRONE_DESTROYED_COOLDOWN == 10f) { "Drone destroyed cooldown" }
        return "crew laser speed and drone rebuild time"
    }

    /**
     * CrewMemberFactory::CreateCrewmember (0x4685a0): a race name that isn't one of the nine builds a plain CrewMember
     * from the human blueprint.
     */
    private fun humanFallback(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val room = state.player.rooms.maxByOrNull { it.cellCount }!!
        val elem = Element("crewBlueprint")
        elem.setAttribute("name", "unimplementedrace")
        elem.addContent(Element("cost").setText("1"))
        val crew = CrewBlueprint(elem).spawn(room, xyz.znix.xftl.crew.AbstractCrew.SlotType.CREW)
        check(crew is CrewHuman && crew.blueprint.name == "human") { "Unknown race became ${crew.blueprint.name}" }
        return "unknown race falls back to human"
    }

    /**
     * IonDroneAnimation (0x43c040, 0x440490, 0x43a4c0): a 6 s repeating timer, a 1 s charge animation, a 1.25 s glow,
     * then the pulse fires and the timer restarts, so pulses come 8.25 s apart; nothing charges unless sabotaging.
     */
    private fun ionPulseCadence(): String {
        check(IonPulseClock.TIMER_LENGTH == 6f && IonPulseClock.CHARGE_TIME == 1f && IonPulseClock.GLOW_TIME == 1.25f) { "Ion constants" }
        check(IonPulseClock.PERIOD == 8.25f) { "Ion period ${IonPulseClock.PERIOD}" }

        fun firstReady(clock: IonPulseClock, sabotaging: Boolean, limit: Int): Float? {
            var time = 0f
            repeat(limit) {
                clock.tick(0.05f, sabotaging)
                time += 0.05f
                if (clock.isReady) return time
            }
            return null
        }

        val clock = IonPulseClock()
        val first = firstReady(clock, true, 400)
        check(first != null && abs(first - 8.25f) < 0.11f) { "First pulse after $first s" }
        clock.fired()
        val second = firstReady(clock, true, 400)
        check(second != null && abs(second - 8.25f) < 0.11f) { "Second pulse after $second s" }

        check(firstReady(IonPulseClock(), false, 1000) == null) { "An idle intruder pulsed" }
        return "ion pulse every 8.25 s while sabotaging"
    }

    /**
     * Door::SetLockdown (0x4708a0): a locked-down door has 50 hit points, worn down one per hit by Door::ApplyDamage
     * (0x46e0f0); at zero it opens and the lockdown ends for that door.
     */
    private fun lockdownDoors(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val door = state.player.doors.first { !it.isAirlock }
        door.lockdown()
        repeat(49) { door.attackDoor() }
        check(door.lockdownTimer > 0f && !door.open) { "A lockdown door broke before 50 hits" }
        door.attackDoor()
        check(door.lockdownTimer == 0f && door.open) { "A lockdown door survived 50 hits" }
        return "lockdown door health"
    }
}
