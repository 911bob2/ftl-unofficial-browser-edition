package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewAim
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.CrewShot
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.Direction
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.sys.GameContainer
import kotlin.math.abs

/**
 * Round 7 crew fights (C2, "rockman was not throwing rocks"): every race's ranged attack is a CrewLaser projectile
 * drawn from the race's own sheet (CrewAnimation::SetupStrips 0x480dc0, UpdateShooting 0x481de0). Rock, Crystal and
 * Mantis throw on frame 3 of their looping 1 s strip (FireShot 0x478870 / 0x478a00); the other races fire on a
 * 1.0-1.3 s timer (0x478b90) holding one aim frame (GetFiringFrame 0x477c30). Damage is rolled on arrival
 * (0x4881c0), the projectile then plays a half-size explosion_missile1 for 0.5 s, and doors take one hit per shot.
 */
internal object GameplayParityCrew7Checks {
    private const val DT = 0.05f

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val aim = aimTable()
            val looks = projectileLooks(main, container)
            val laser = laserTimer(main, container)
            val throws = throwStrip(main, container)
            val punch = punchCycle(main, container)
            val door = doorShots(main, container)
            return "crew7 ok: $aim, $looks, $laser, $throws, $punch, $door"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /** SetDirection 0x47e550 and GetFiringFrame 0x477c30, checked against what each strip frame shows. */
    private fun aimTable(): String {
        val cases = listOf(
            Triple(10f, 0f, Direction.RIGHT to 0), Triple(10f, 3f, Direction.RIGHT to 1),
            Triple(10f, -3f, Direction.RIGHT to 2), Triple(-10f, 3f, Direction.LEFT to 1),
            Triple(-10f, -3f, Direction.LEFT to 2), Triple(3f, -10f, Direction.UP to 1),
            Triple(-3f, -10f, Direction.UP to 2), Triple(3f, 10f, Direction.DOWN to 2),
            Triple(-3f, 10f, Direction.DOWN to 1), Triple(35f, 35f, Direction.DOWN to 2),
            Triple(0f, -10f, Direction.UP to 0), Triple(0f, 0f, Direction.DOWN to 0),
        )
        for ((dx, dy, expected) in cases) {
            val aim = CrewAim.aim(dx, dy)
            val got = aim.direction to CrewAim.firingFrame(aim.direction, aim.lean)
            check(got == expected) { "Aim ($dx, $dy): $got, native $expected" }
        }
        return "aim frames"
    }

    private fun race(state: InGameState, name: String): CrewBlueprint? =
        state.blueprintManager.getOrNull(name) as? CrewBlueprint

    /** A shooter in one corner of a 2x2 room and a stunned target in the opposite corner (or the same cell). */
    private fun duel(
        state: InGameState, shooterRace: CrewBlueprint, shooterIsPlayer: Boolean, sameCell: Boolean = false
    ): Pair<LivingCrew, LivingCrew> {
        val ship = state.player
        val room = ship.rooms.first { it.width >= 2 && it.height >= 2 }
        ship.crew.toList().forEach { it.removeFromShip() }
        ship.crewShots.clear()
        val shooter = ship.addCrewMember(LivingCrewInfo.generateRandom(shooterRace, state), false, !shooterIsPlayer)
        val human = state.blueprintManager["human"] as CrewBlueprint
        val victim = ship.addCrewMember(LivingCrewInfo.generateRandom(human, state), false, shooterIsPlayer)
        shooter.jumpTo(room, ConstPoint(0, 0))
        victim.jumpTo(room, if (sameCell) ConstPoint(0, 0) else ConstPoint(1, 1))
        shooter.nextTargetPos = null; victim.nextTargetPos = null
        room.updateCrewInRoom(); ship.updateCrewReservedSlots()
        state.debugFlags.noCrewDamage.set = false
        // A stunned victim doesn't fight back, so every shot is the shooter's.
        victim.stun(600f)
        return shooter to victim
    }

    private class Log {
        val spawned = ArrayList<Pair<Float, CrewShot>>()
        val landed = ArrayList<Float>()
        val removed = ArrayList<Float>()
        val hits = ArrayList<Pair<Float, Float>>()
        val frames = ArrayList<Int>()
    }

    private fun fight(state: InGameState, shooter: AbstractCrew, victim: AbstractCrew, seconds: Float): Log {
        val log = Log()
        val seen = HashSet<CrewShot>()
        val wasLanded = HashSet<CrewShot>()
        var health = victim.health
        val steps = (seconds / DT).toInt()
        for (i in 1..steps) {
            val t = i * DT
            shooter.update(DT); victim.update(DT)
            val shots = state.player.crewShots
            for (shot in shots) if (seen.add(shot)) log.spawned += t to shot
            for (shot in shots.toList()) {
                if (!shot.update(DT)) {
                    shots.remove(shot)
                    log.removed += t
                }
                if (shot.landed && wasLanded.add(shot)) log.landed += t
            }
            if (victim.health < health) log.hits += t to (health - victim.health)
            health = victim.health
            log.frames += shooter.icon.frame
        }
        return log
    }

    /** Each race's projectile is the "crewLaser" cell (x=1, y=0) of its own base sheet, tinted by owner. */
    private fun projectileLooks(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val races = listOf("human", "engi", "energy", "slug", "anaerobic", "rock", "crystal", "mantis")
        val checked = ArrayList<String>()
        for (name in races) {
            val race = race(state, name) ?: continue
            for (player in listOf(true, false)) {
                val (shooter, victim) = duel(state, race, player)
                val log = fight(state, shooter, victim, 1.5f)
                val shot = log.spawned.firstOrNull()?.second ?: error("$name (player $player) fired nothing in 1.5 s")
                val sheet = state.getImg("img/people/${name}_base.png")
                val image = shot.image ?: error("$name shot has no image")
                check(image.texture === sheet.texture && image.width == 35 && image.height == 35 &&
                    image.textureOffsetX == sheet.textureOffsetX + 35 &&
                    image.textureOffsetY == sheet.textureOffsetY + sheet.height - 35) {
                    "$name shot image is not its crewLaser cell"
                }
                val tinted = name in CrewAim.TINTED_RACES
                val expected = when {
                    !tinted -> Colour.white
                    player -> CrewShot.PLAYER_LASER
                    else -> CrewShot.ENEMY_LASER
                }
                check(shot.colour.r == expected.r && shot.colour.g == expected.g && shot.colour.b == expected.b) {
                    "$name (player $player) shot colour ${shot.colour.r},${shot.colour.g},${shot.colour.b}"
                }
            }
            checked += name
        }
        check("rock" in checked && "mantis" in checked && "human" in checked) { "Races checked: $checked" }
        return "projectile sprites ${checked.size} races"
    }

    /**
     * Laser races: the first shot of a new crewmember's first fight comes after its 1.0-1.3 s timer, then one per
     * 1.0-1.3 s; the shoot strip holds the aim frame; damage 3-7 lands on arrival, then a 0.5 s impact puff.
     */
    private fun laserTimer(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val (shooter, victim) = duel(state, state.blueprintManager["human"] as CrewBlueprint, true)
        val log = fight(state, shooter, victim, 8f)
        val times = log.spawned.map { it.first }
        check(times.size >= 5) { "Human fired ${times.size} shots in 8 s" }
        check(times.first() in 0.99f..1.36f) { "First human shot at ${times.first()} s" }
        val gaps = times.zipWithNext { a, b -> b - a }
        check(gaps.all { it in 0.99f..1.36f }) { "Human shot gaps $gaps" }
        // Shooter at (0,0), target at (1,1): aim straight down-right on the diagonal -> down strip, frame 2.
        check(shooter.icon.spec.name == "human_shoot_down") { "Human aims with ${shooter.icon.spec.name}" }
        check(log.frames.drop(2).all { it == 2 }) { "Human shoot frames ${log.frames.toSet()}" }
        check(log.hits.isNotEmpty() && log.hits.all { it.second in 2.99f..7.01f }) { "Human hits ${log.hits}" }
        check(log.landed.first() - times.first() >= 0.2f) { "Shot landed ${log.landed.first()} after ${times.first()}" }
        check(log.hits.first().first >= log.landed.first()) { "Damage ${log.hits.first()} before arrival ${log.landed.first()}" }
        val puff = log.removed.first() - log.landed.first()
        check(puff in 0.45f..0.6f) { "Impact puff lasted $puff s" }
        return "laser 1.0-1.3 s, aim frame, puff ${"%.2f".format(puff)} s"
    }

    /** Rock, Crystal and Mantis animate their 4-frame strip and throw on frame 3, once a second. */
    private fun throwStrip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val out = ArrayList<String>()
        for (name in listOf("rock", "crystal", "mantis")) {
            val race = race(state, name) ?: continue
            val (shooter, victim) = duel(state, race, name != "mantis")
            check(shooter.throwsOnShootFrame) { "$name doesn't throw on its strip" }
            val log = fight(state, shooter, victim, 6f)
            val times = log.spawned.map { it.first }
            check(times.size >= 5) { "$name threw ${times.size} times in 6 s" }
            check(times.first() in 0.75f..0.86f) { "$name first throw at ${times.first()} s" }
            val gaps = times.zipWithNext { a, b -> b - a }
            check(gaps.all { abs(it - 1f) < 0.06f }) { "$name throw gaps $gaps" }
            check(log.frames.toSet().containsAll(listOf(0, 1, 2, 3))) { "$name strip frames ${log.frames.toSet()}" }
            check(log.hits.isNotEmpty() && log.hits.all { it.second > 2.99f * 0.99f }) { "$name hits ${log.hits}" }
            out += name
        }
        check("rock" in out) { "No Rock throw checked" }
        return "throws ${out.joinToString("/")} every 1.0 s"
    }

    /** Punches: the punch lasts twice the 500-649 ms punch timer, which lands the 3-7 damage; no projectile. */
    private fun punchCycle(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val (shooter, victim) = duel(state, state.blueprintManager["human"] as CrewBlueprint, true, sameCell = true)
        val log = fight(state, shooter, victim, 6f)
        check(log.spawned.isEmpty()) { "A punch fired ${log.spawned.size} projectiles" }
        check(log.hits.size >= 4) { "Punch hits ${log.hits}" }
        val gaps = log.hits.map { it.first }.zipWithNext { a, b -> b - a }
        check(gaps.all { it in 0.94f..1.36f }) { "Punch gaps $gaps" }
        check(log.hits.all { it.second in 2.99f..7.01f }) { "Punch damage ${log.hits}" }
        check(log.hits.first().first in 0.49f..0.7f) { "First punch landed at ${log.hits.first().first} s" }
        return "punch 1.0-1.3 s"
    }

    /** A boarder bashing a door shoots it: no scatter, one door hit per arriving shot. */
    private fun doorShots(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        ship.crew.toList().forEach { it.removeFromShip() }
        ship.crewShots.clear()
        val door = ship.doors.first { !it.isAirlock && it.left != null && it.right != null }
        val from = door.left!!
        val to = door.right!!
        ship.doors.forEach { it.lockdown() }
        val human = state.blueprintManager["human"] as CrewBlueprint
        val boarder = ship.addCrewMember(LivingCrewInfo.generateRandom(human, state), false, true)
        boarder.jumpTo(from, ConstPoint(0, 0))
        boarder.nextTargetPos = null
        from.updateCrewInRoom(); ship.updateCrewReservedSlots()
        check(boarder.setTargetRoom(to)) { "Boarder can't head for room ${to.id}" }
        val seen = HashSet<CrewShot>()
        var shots = 0
        var landed = 0
        var hits = 0
        val before = ship.doors.associateWith { it.lockdownHealth }.toMutableMap()
        for (i in 1..200) {
            boarder.update(DT)
            for (shot in ship.crewShots) if (seen.add(shot)) {
                shots++
                check(ship.doors.any { it.pixelCentre.x == shot.endX && it.pixelCentre.y == shot.endY }) {
                    "Door shot aimed at ${shot.endX},${shot.endY}, not a door centre"
                }
            }
            for (shot in ship.crewShots.toList()) {
                val was = shot.landed
                if (!shot.update(DT)) ship.crewShots.remove(shot)
                if (!was && shot.landed) landed++
            }
            for (d in ship.doors) {
                hits += before[d]!! - d.lockdownHealth
                before[d] = d.lockdownHealth
            }
            check(hits <= landed) { "Door took $hits hits from $landed landed shots" }
        }
        check(shots >= 3 && hits >= 3) { "Door attack: $shots shots, $hits hits" }
        return "door shots ($hits hits)"
    }
}
