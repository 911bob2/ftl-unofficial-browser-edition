package ftl.browser

import xyz.znix.xftl.environment.AsbProjectile
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.BombProjectile
import xyz.znix.xftl.weapons.LaserBlastProjectile

/**
 * WP4 projectiles: the browser half of the native 1.6.14 bodies capture (.build/wp4-proj/native-bodies). The same
 * inputs as the authored probe, with the gameplay stream drawing from [NativeRandom] seeded like random32, so a
 * native draw order replays exactly: LaserBlast SetSpin/OnUpdate, the Asteroid constructor/OnUpdate, the bomb's
 * arrival and fuse, a PDSFire flight from (-100, 300) to the capture's room centre and Projectile::CollisionCheck at
 * the target (room / no room). Positions that depend on the native ship's layout are compared by rule, not value.
 * Argument: "<pdsTargetX> <pdsTargetY>".
 */
internal object BrowserWp4BodiesDiagnostics {
    fun run(main: MainGame, container: GameContainer, args: String): String {
        val parts = args.trim().split(' ')
        val random = BrowserRandom.snapshot(); val native = NativeRandom.snapshot()
        try {
            main.startDiagnosticGame("PLAYER_SHIP_HARD", Difficulty.NORMAL)
            main.update(container, 0f)
            val game = main.currentState as InGameState
            val player = game.player
            player.browserJumping = false
            val room = player.rooms.first()
            fun bp(name: String) = game.blueprintManager[name] as AbstractWeaponBlueprint
            BrowserRandom.nativeStream = true
            val out = StringBuilder("{")

            // 1. LaserBlast::SetSpin(30) after srand(1234), four OnUpdates at SpeedFactor .25, then SetSpin(0).
            val laser = LaserBlastProjectile(bp("LASER_BURST_1"), room)
            laser.setInitialPath(ConstFPoint(20f, 20f), ConstFPoint(600f, 20f))
            NativeRandom.seed(1234)
            laser.nativeSetSpin(30f)
            out.append("\"spin\":{\"angle\":${laser.spinAngle},\"speed\":${laser.spinSpeed},\"state\":\"${NativeRandom.snapshot()}\",\"frames\":[")
            for (k in 0 until 4) {
                laser.nativeUpdate(.015625f, player)
                if (k > 0) out.append(',')
                out.append("{\"angle\":${laser.spinAngle},\"x\":${laser.position.xf},\"y\":${laser.position.yf}}")
            }
            val still = LaserBlastProjectile(bp("LASER_BURST_1"), room)
            NativeRandom.seed(1234)
            still.nativeSetSpin(0f)
            out.append("],\"zero\":{\"angle\":${still.spinAngle},\"speed\":${still.spinSpeed},\"state\":\"${NativeRandom.snapshot()}\"},")
            out.append("\"layer\":${laser.nativeForceRenderLayer()}},")

            // 2. Asteroid: the constructor's draws after srand(2345), three OnUpdates, the wrap past 360 and death.
            NativeRandom.seed(2345)
            val asteroid = AsteroidProjectile(room)
            out.append("\"asteroid\":{\"angle\":${asteroid.angle},\"speed\":${asteroid.defaultSpeed},\"state\":\"${NativeRandom.snapshot()}\",\"frames\":[")
            asteroid.setInitialPath(ConstFPoint(100f, 100f), ConstFPoint(400f, 100f))
            for (k in 0 until 3) {
                asteroid.nativeUpdate(.015625f, player)
                if (k > 0) out.append(',')
                out.append("{\"angle\":${asteroid.angle},\"x\":${asteroid.position.xf},\"y\":${asteroid.position.yf}}")
            }
            asteroid.angle = 359f; asteroid.nativeUpdate(.015625f, player)
            out.append("],\"wrap\":${asteroid.angle},")
            asteroid.deathAnimation.start(true); asteroid.nativeUpdate(.015625f, player)
            out.append("\"dying\":${asteroid.angle},\"layer\":${asteroid.nativeForceRenderLayer()}},")

            // 3. Bomb: srand(3456), the room draw, Initialize; then OnUpdate + CollisionCheck at SpeedFactor 1.
            NativeRandom.seed(3456); NativeRandom.next31()
            val bomb = BombProjectile(bp("BOMB_1"), room, false)
            bomb.hsOwnerId = 1
            val flight = bomb.flightTracker
            out.append("\"bomb\":{\"state\":\"${NativeRandom.snapshot()}\",\"flightTime\":${flight.time},\"flightFrames\":${flight.frameCount},")
            out.append("\"layer\":${bomb.nativeForceRenderLayer()},\"frames\":[")
            for (k in 0 until 40) {
                bomb.nativeUpdate(.0625f, player)
                if (k > 0) out.append(',')
                out.append("{\"reverse\":${flight.reverse},\"current\":${flight.currentTime},\"done\":${flight.done},\"delay\":${bomb.explosiveDelay}," +
                    "\"dying\":${bomb.deathAnimationRunning},\"missed\":${bomb.bMissed}}")
            }
            out.append("]},")

            // 4. PDSFire from (-100, 300) to the capture's room centre, at SpeedFactor .5: srand(4567), the room-centre
            // draw, the constructor (heading, explosionAnimation pick), Initialize("PDS_SHOT") (death_animation pick).
            val aim = ConstFPoint(parts[0].toFloat(), parts[1].toFloat())
            NativeRandom.seed(4567); NativeRandom.next31()
            val pds = AsbProjectile.probe(room, ConstFPoint(-100f, 300f), aim, constructed = true)
            val ex = pds.explosionTracker
            pds.deathAnimation
            out.append("\"pds\":{\"speed\":${pds.pdsSpeed},\"scale\":${pds.scale},\"layer\":${pds.nativeForceRenderLayer()}," +
                "\"explosion\":{\"state\":\"${NativeRandom.snapshot()}\",\"frames\":${ex.frameCount},\"time\":${ex.time}," +
                "\"name\":\"${pds.explosionSpec?.name}\",\"deathFrames\":${pds.deathAnimation.frameCount},\"deathTime\":${pds.deathAnimation.time}," +
                "\"running\":${ex.running},\"done\":${ex.done},\"deathRunning\":${pds.deathAnimationRunning}},\"frames\":[")
            player.projectiles.add(pds)
            for (k in 0 until 120) {
                if (pds in player.projectiles) pds.update(.03125f, player)
                if (k > 0) out.append(',')
                out.append("{\"x\":${pds.position.xf},\"y\":${pds.position.yf},\"scale\":${pds.scale},\"speed\":${pds.pdsSpeed}," +
                    "\"passed\":${pds.pdsPassedTarget},\"layer\":${pds.nativeForceRenderLayer()},\"live\":${pds in player.projectiles}," +
                    "\"exRunning\":${ex.running},\"exCurrent\":${ex.currentTime},\"exDone\":${ex.done},\"exX\":${pds.explosionPosition.xf}," +
                    "\"exY\":${pds.explosionPosition.yf},\"deathRunning\":${pds.deathAnimationRunning}}")
            }
            player.projectiles.remove(pds)
            out.append("]},")

            // 5. Projectile::CollisionCheck at the target: forced at a room centre, unforced far outside every room.
            out.append("\"reach\":[")
            for ((i, forced) in listOf(true, false).withIndex()) {
                val shot = LaserBlastProjectile(bp("LASER_BURST_1"), room)
                shot.hsOwnerId = 1
                shot.hsCurrentSpace = player
                val at = if (forced) ConstFPoint(room.pixelCentre) else ConstFPoint(-1000f, -1000f)
                shot.setInitialPath(at, at)
                if (forced) shot.diagnosticForceHit()
                NativeRandom.seed(5678)
                val before = NativeRandom.snapshot()
                shot.nativeUpdate(.015625f, player)
                if (i > 0) out.append(',')
                out.append("{\"missed\":${shot.diagnosticMissed()},\"forced\":$forced,\"startedDeath\":${shot.deathAnimationRunning}," +
                    "\"dying\":${shot.deathAnimationRunning},\"drew\":${before != NativeRandom.snapshot()}}")
            }
            out.append("],")

            // 6. AsteroidGenerator: shield level 4 (two bars), one ship, srand(6789), 200 frames at SpeedFactor 4.
            player.shields?.energyLevels = 4
            player.projectiles.clear()
            NativeRandom.seed(6789)
            val field = xyz.znix.xftl.environment.AsteroidEnvironment(game, game.currentBeacon)
            val start = field.diagnosticTimers()
            out.append("\"generator\":{\"state\":${start[0].toInt()},\"phase\":${start[1]},\"firing\":${start[2]},\"target\":${start[3].toInt()}," +
                "\"rng\":\"${NativeRandom.snapshot()}\",\"frames\":[")
            for (k in 0 until 200) {
                val before = player.projectiles.size
                field.update(.25f)
                val new = player.projectiles.drop(before).filterIsInstance<AsteroidProjectile>().firstOrNull()
                val t = field.diagnosticTimers()
                if (k > 0) out.append(',')
                out.append("{\"state\":${t[0].toInt()},\"phase\":${t[1]},\"firing\":${t[2]},\"target\":${t[3].toInt()},\"asteroid\":${new != null}," +
                    "\"angle\":${new?.angle ?: 0f},\"x\":${new?.position?.xf ?: 0f},\"y\":${new?.position?.yf ?: 0f},\"rng\":\"${NativeRandom.snapshot()}\"}")
            }
            player.projectiles.clear()
            out.append("]},")

            // 7. CrewLaser from (100, 100) to (180, 160) at SpeedFactor .5 (CrewShot's flight and startedDeath).
            val crew = xyz.znix.xftl.crew.CrewShot(room, xyz.znix.xftl.math.ConstPoint(100, 100),
                xyz.znix.xftl.math.ConstPoint(180, 160), null, xyz.znix.xftl.crew.CrewShot.PLAYER_LASER, null, null)
            out.append("\"crew\":{\"speed\":${xyz.znix.xftl.crew.CrewShot.SPEED / 16f},\"scale\":${xyz.znix.xftl.crew.CrewShot.IMPACT_SCALE}," +
                "\"heading\":${Math.toDegrees(kotlin.math.atan2(60.0, 80.0))},\"frames\":[")
            for (k in 0 until 24) {
                val landed = crew.landed
                crew.update(.03125f)
                val at = crew.diagnosticPosition
                if (k > 0) out.append(',')
                out.append("{\"x\":${at.xf},\"y\":${at.yf},\"death\":${crew.landed},\"wasLanded\":$landed}")
            }
            out.append("]},")

            // 8. OnRenderProjectiles' layer choice for the capture's eight bodies and calls (0,2), (0,1), (1,2), (1,1):
            // the native rule, and the browser's own rendersInLayer for the unforced bodies in their current space.
            val bodies = listOf(intArrayOf(0, 1, 0, -1), intArrayOf(0, 1, 1, -1), intArrayOf(1, 0, 0, -1), intArrayOf(1, 0, 1, -1),
                intArrayOf(0, 0, 0, -1), intArrayOf(-1, 0, 0, -1), intArrayOf(0, 1, 0, 2), intArrayOf(1, 0, 0, 1))
            val calls = listOf(0 to 2, 0 to 1, 1 to 2, 1 to 1)
            out.append("\"layers\":[")
            for ((i, b) in bodies.withIndex()) {
                var mask = 0; var actual = 0
                val shot = LaserBlastProjectile(bp("LASER_BURST_1"), room)
                shot.hsOwnerId = b[0]; shot.hsDestinationSpaceId = b[1]; shot.hsCurrentSpaceId = b[2]
                // The browser's launch state: under its owner's ship until it switches to the target's space.
                shot.drawUnderShip = b[0] == b[2]
                for ((bit, call) in calls.withIndex()) {
                    if (xyz.znix.xftl.weapons.ProjectileRuntime.nativeLayer(b[0], b[1], b[3], call.first, call.second)) mask = mask or (1 shl bit)
                    if (b[2] == call.first && xyz.znix.xftl.weapons.ProjectileRuntime.rendersInLayer(shot, call.second)) actual = actual or (1 shl bit)
                }
                if (i > 0) out.append(',')
                out.append("{\"mask\":$mask,\"actual\":$actual}")
            }
            out.append("],")

            // 9. UpdatePDSFire, player-only, the volley due, SpeedFactor 1: live after srand(7000), idle after srand(7100).
            // The native ship's pilot and engines give it a dodge factor, so GetDodged draws its roll (95 here, no
            // dodge); the diagnostic ship stands in with one of 5.
            player.browserDiagnosticDodge = 5
            val asb = xyz.znix.xftl.environment.AsbEnvironment(game, game.currentBeacon, 0)
            out.append("\"asb\":{\"evasion\":${player.evasion},")
            for ((i, case) in listOf(Triple("live", 7000, 30f), Triple("idle", 7100, 0f)).withIndex()) {
                player.projectiles.clear()
                NativeRandom.seed(case.second)
                asb.diagnosticFire(case.third, 25f, .0625f)
                val shot = player.projectiles.lastOrNull() as? AsbProjectile
                val timer = asb.diagnosticTimer
                val smoke = asb.smokeCorner(asb.diagnosticOrigin)
                if (i > 0) out.append(',')
                out.append("\"${case.first}\":{\"after\":\"${NativeRandom.snapshot()}\",\"volley\":${asb.diagnosticVolleyTimer}," +
                    "\"current\":${timer[0]},\"goal\":${timer[1]},\"projectiles\":${player.projectiles.size},\"queue\":${asb.diagnosticQueue.size}," +
                    "\"x\":${shot?.position?.xf},\"y\":${shot?.position?.yf},\"tx\":${shot?.aimPoint?.xf},\"ty\":${shot?.aimPoint?.yf}," +
                    "\"heading\":${shot?.drawnHeading},\"owner\":${shot?.hsOwnerId},\"hit\":${shot?.isHit},\"missed\":${shot?.showsMiss}," +
                    "\"smokeX\":${smoke.x},\"smokeY\":${smoke.y}}")
            }
            player.projectiles.clear()
            player.browserDiagnosticDodge = null
            out.append("}}")
            return out.toString()
        } finally {
            BrowserRandom.nativeStream = false
            BrowserRandom.restore(random); NativeRandom.restore(native); main.switchToMainMenu()
        }
    }
}
