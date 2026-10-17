package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.TWO_PI
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.MutFPoint
import xyz.znix.xftl.math.Point
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.*
import kotlin.math.*

class DefenceDrone(type: DroneBlueprint) : AbstractExternalDrone(type, false) {
    override val flightController = OrbitFlightController(this)

    override val useCustomStunRotation: Boolean get() = true

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var engine: Image

    private lateinit var gunOffImage: Image
    private lateinit var gunOnImage: Image
    private lateinit var gunChargedImage: Image

    private val typeCooldown = type.cooldown!! / 1000f

    private lateinit var weapon: LaserBlueprint
    private var weaponSpeed: Int = 0

    // The gun angle (in the drone images' frame, 0 is straight up) and the
    // angle it is turning to (negative for none), SpaceDrone+0x170/+0x178.
    private var aimAngle: Float = 0f
    private var goalAngle: Float = -1f

    private var cooldown: Float = 0f
    private var fireReady: Boolean = false
    private var justDeployed: Boolean = false

    // The point we're aiming at (null when there is none, the original's
    // -FLT_MAX), and the velocity of what it was taken from.
    private var aimPoint: MutFPoint? = null
    private var aimVelocity: FPoint = ConstFPoint.ZERO

    // The projectile or drone the aim point came from, and the last one we
    // locked onto. Neither is saved, as in the original.
    private var currentTarget: Any? = null
    private var lockedTarget: Any? = null
    private var restoredFromSave = false

    override fun onInit() {
        offImage = game.getImg("img/ship/drones/${type.droneImage}_base.png")
        onImage = game.getImg("img/ship/drones/${type.droneImage}_charged.png")
        engine = game.getImg("img/ship/drones/drone_engine.png")

        gunOffImage = game.getImg("img/ship/drones/${type.droneImage}_gun.png")
        gunOnImage = game.getImg("img/ship/drones/${type.droneImage}_gun_on.png")
        gunChargedImage = game.getImg("img/ship/drones/${type.droneImage}_gun_charged.png")

        // Just assume we're using a laser, it could get a bit silly otherwise.
        weapon = type.weaponBlueprint!! as LaserBlueprint

        weaponSpeed = speedFor(weapon)

        // SetDeployed (0x4272e0) starts the weapon cooldown at one second,
        // whatever the blueprint says.
        // A drone loaded from a save keeps its saved cooldown (init runs after loadFromXML).
        if (!restoredFromSave) {
            cooldown = DEPLOY_COOLDOWN
            justDeployed = true
        }
    }

    override fun onRender(g: Graphics) {
        val baseImage = when {
            isRunning -> onImage
            else -> offImage
        }

        // Try to keep everything as an integer to make the sprites sharper.
        drawCentred(baseImage)

        // Draw the little thruster flame.
        if (isRunning) {
            val movementAngle = atan2(flightController.velocity.xf, flightController.velocity.yf)
            g.pushTransform()
            g.rotate(0f, 0f, Math.toDegrees(movementAngle.toDouble()).toFloat() + 90)
            drawCentred(engine)
            g.popTransform()
        }

        // Draw the gun.
        // It points up with no rotation, so we need to rotate it clockwise
        // to make 0 match up with sin/cos space.
        val gunImage = when {
            !isRunning -> gunOffImage
            else -> gunOnImage
        }

        g.pushTransform()
        g.rotate(0f, 0f, Math.toDegrees((aimAngle + stunRotationAnimation).toDouble()).toFloat())

        // Draw the base image
        drawCentred(gunImage)

        // And draw the charged image with an increasing opacity as we charge up
        if (isRunning) {
            val timeCharging = typeCooldown - cooldown
            val chargeProgress = max(0f, timeCharging / typeCooldown)
            val colour = Colour(1f, 1f, 1f, chargeProgress)
            drawCentred(gunChargedImage, colour)
        }

        g.popTransform()
    }

    /**
     * The per-frame defence drone logic, in the order of the original:
     * DefenseDrone::OnLoop (0x428010) counts the cooldown down, the space
     * manager offers every projectile and drone to SetTarget (0x4210d0), and
     * the drone's GetNextProjectile (0x429ee0) aims (0x4277b0) and fires.
     */
    override fun update(dt: Float) {
        super.update(dt)

        // An exploding drone stops here (0x428010 returns before the cooldown).
        if (isBlowingUp)
            return

        if (!isPowered) {
            // A drone that is switched off and back on starts the cooldown
            // again (0x428010), except right after it was deployed.
            if (!justDeployed)
                cooldown = typeCooldown
            aimPoint = null
            return
        }
        justDeployed = false

        // A stunned drone is skipped (0x428010 goes to UpdatePoweredDown
        // without touching the cooldown or the target).
        if (isStunned)
            return

        cooldown -= dt
        if (cooldown < 0f) {
            fireReady = true
            cooldown = -1f
        }

        // Defense Scrambler (DEFENSE_SCRAMBLER): when the ship we're
        // defending against has it, ShipManager::SetTargetShip (0x4a2b30)
        // flags our drones and DefenseDrone's aiming (0x4277b0) just keeps
        // turning to random angles without targeting anything.
        if (ownerShip.sys.getEnemyOf(ownerShip)?.hasAugment(AugmentBlueprint.DEFENSE_SCRAMBLER) == true) {
            if (goalAngle < 0f || goalAngle == aimAngle) {
                goalAngle = Math.toRadians(ftl.browser.BrowserRandom.nextInt(360).toDouble()).toFloat()
            }
            turnGun(goalAngle, dt)
            return
        }

        offerTargets()

        if (aimPoint != null)
            aim(dt)

        if (fireReady && aimPoint != null)
            fire()
    }

    /**
     * SpaceManager::OnLoop (0x41f3c0) offers each projectile, in list order,
     * to every drone through SetTarget (0x4210d0), and then every other drone.
     *
     * A candidate is only taken if the current class (reset to 'other' each
     * frame) allows it: a missile is only replaced by another missile, an
     * asteroid by a missile or asteroid, and anything else by anything.
     * So the drone ends up with the newest missile, else the newest
     * asteroid, else the newest of the rest. The target it last locked onto
     * is not offered again (except to the anti-drone).
     */
    private fun offerTargets() {
        var currentClass = CLASS_OTHER

        for (projectile in ownerShip.projectiles) {
            if (projectile !is AbstractProjectile)
                continue

            // Ignore departing shots, or those shooting
            // at something other than the ship.
            if (projectile.targetShip != ownerShip)
                continue

            val candidateClass = classOf(projectile) ?: continue
            if (!isValidClass(candidateClass))
                continue

            if (!canTakeLocked(projectile))
                continue

            if (!replaces(currentClass, candidateClass))
                continue

            currentClass = candidateClass
            setTarget(projectile, projectile.position, projectile.velocity)
        }

        // Drones are offered after the projectiles, and the class rule applies
        // to them too. They are always class 3 ('drone').
        if (isValidClass(CLASS_DRONE)) {
            for (drone in ownerShip.externalDrones) {
                // Only the other ship's drones, which are powered, out and not stunned
                // (SpaceDrone::ValidTarget 0x422bb0).
                if (!drone.onEnemy || !drone.isRunning || drone.isBlowingUp)
                    continue

                if (!canTakeLocked(drone))
                    continue

                if (!replaces(currentClass, CLASS_DRONE))
                    continue

                currentClass = CLASS_DRONE
                setTarget(drone, drone.flightController.position, drone.measuredVelocity)
            }
        }
    }

    private fun canTakeLocked(candidate: Any): Boolean {
        // 0x4210d0: the target we last locked onto is skipped,
        // unless we are an anti-drone (blueprint target type 3).
        return candidate !== lockedTarget || type.defenceTarget == DRONES_TARGET
    }

    private fun replaces(currentClass: Int, candidateClass: Int): Boolean {
        return when (currentClass) {
            CLASS_MISSILE -> candidateClass == CLASS_MISSILE
            CLASS_ASTEROID -> candidateClass == CLASS_MISSILE || candidateClass == CLASS_ASTEROID
            else -> true
        }
    }

    private fun setTarget(target: Any, position: FPoint, velocity: FPoint) {
        currentTarget = target
        aimPoint = MutFPoint(position)
        aimVelocity = ConstFPoint(velocity)
    }

    /**
     * The target class of a projectile, the Targetable type that the
     * original's constructors store (Missile 1, Asteroid 2, drone pods 6,
     * LaserBlast 4; beams and bombs keep the default and are never valid).
     */
    private fun classOf(projectile: IProjectile): Int? {
        return when {
            projectile is AsteroidProjectile -> CLASS_ASTEROID
            projectile is AbstractWeaponProjectile && projectile.isMissileForDD -> CLASS_MISSILE
            projectile.isLaserForDD -> CLASS_LASER
            // Boarding and hacking pods
            projectile.isMissileForDD -> CLASS_POD
            else -> null
        }
    }

    /**
     * DefenseDrone::ValidTargetObject (0x421020): the blueprint's target type
     * against the target's class. The default type takes missiles, asteroids
     * and pods, LASERS adds lasers, DRONES takes drones and pods.
     */
    private fun isValidClass(targetClass: Int): Boolean {
        return when (type.defenceTarget) {
            DRONES_TARGET -> targetClass == CLASS_DRONE || targetClass == CLASS_POD
            OMNI_TARGET -> true
            LASERS_TARGET -> targetClass != CLASS_DRONE
            else -> targetClass == CLASS_MISSILE || targetClass == CLASS_ASTEROID || targetClass == CLASS_POD
        }
    }

    /**
     * The aiming part of DefenseDrone's loop (0x4277b0).
     *
     * The target is led (intercept time from FUN_005ca6f0) and the point is
     * stored back, so a target that is not refreshed keeps being extrapolated.
     * If the lead point leaves the -150..450 box on either axis, or the gun
     * has not finished turning to it, the aim point is dropped; otherwise the
     * target counts as locked.
     */
    private fun aim(dt: Float) {
        val point = aimPoint ?: return
        val dronePos = flightController.position

        // Relative position of the target to the drone, and the intercept time.
        val dx = point.xf - dronePos.xf
        val dy = point.yf - dronePos.yf
        val vx = aimVelocity.xf
        val vy = aimVelocity.yf
        val shotSpeed = weaponSpeed.f

        // FUN_005ca6f0: solves |p + v*t| = s*t, returning the earlier root,
        // or a negative time with no solution.
        val c = dx * dx + dy * dy
        val b = 2 * (dx * vx + dy * vy)
        val discriminant = b * b - 4 * ((vx * vx + vy * vy) - shotSpeed * shotSpeed) * c
        var time = -1f
        if (discriminant > 0f) {
            val denominator = sqrt(discriminant) - b
            if (denominator != 0f)
                time = 2 * c / denominator
        }

        if (time > 0f) {
            point.xf += vx * time
            point.yf += vy * time
        }

        goalAngle = angleTo(dronePos.xf, dronePos.yf, point.xf, point.yf)
        val aligned = turnGun(goalAngle, dt)

        val outOfRange = point.xf <= RANGE_MIN || point.yf <= RANGE_MIN ||
                point.xf >= RANGE_MAX || point.yf >= RANGE_MAX
        if (outOfRange || !aligned) {
            aimPoint = null
            return
        }

        lockedTarget = currentTarget
    }

    /**
     * FUN_00427460: turn the gun towards the goal at 30 degrees per speed
     * factor unit (so 480 degrees per second), the short way round, and snap
     * onto it when within a step. Returns true when the gun is on the goal.
     */
    private fun turnGun(goal: Float, dt: Float): Boolean {
        var diff = goal - aimAngle
        while (diff > PI.toFloat()) diff -= TWO_PI
        while (diff < -PI.toFloat()) diff += TWO_PI

        val step = GUN_TURN_RATE * dt
        if (abs(diff) <= step) {
            aimAngle = goal
            return true
        }

        aimAngle += if (diff > 0f) step else -step
        if (aimAngle < 0f) aimAngle += TWO_PI
        if (aimAngle >= TWO_PI) aimAngle -= TWO_PI
        return false
    }

    private fun angleTo(fromX: Float, fromY: Float, toX: Float, toY: Float): Float {
        // See DroneFlightController.getAngleFrom: our images face up.
        var angle = atan2(toY - fromY, toX - fromX) + TWO_PI / 4
        if (angle < 0f) angle += TWO_PI
        if (angle > TWO_PI) angle -= TWO_PI
        return angle
    }

    /**
     * GetNextProjectile (0x429ee0): one laser at the aim point, then the aim
     * point is dropped and the weapon cooldown starts. There is no burst: a
     * drone fires once per cooldown.
     */
    private fun fire() {
        val point = aimPoint ?: return

        val shot = InterceptorLaser(ownerShip, weapon)
        targetShip.projectiles += shot

        shot.setInitialPath(
            flightController.position,
            ConstPoint(point.xf.roundToInt(), point.yf.roundToInt())
        )

        weapon.launchSounds?.get()?.play()

        fireReady = false
        aimPoint = null
        cooldown = typeCooldown
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "aimAngle", aimAngle)
        SaveUtil.addAttrFloat(elem, "cooldown", cooldown)

        // SpaceDrone::SaveState (0x4203c0) writes the aim point, its velocity,
        // the gun angle and the cooldown, but not the target ids: the locked
        // target is forgotten across a save, like in the original.
        val point = aimPoint
        if (point != null) {
            SaveUtil.addAttrFloat(elem, "aimPointX", point.xf)
            SaveUtil.addAttrFloat(elem, "aimPointY", point.yf)
            SaveUtil.addAttrFloat(elem, "aimVelX", aimVelocity.xf)
            SaveUtil.addAttrFloat(elem, "aimVelY", aimVelocity.yf)
        }
        if (fireReady)
            SaveUtil.addAttrBool(elem, "fireReady", true)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        super.loadFromXML(elem, refs, containingShip)

        restoredFromSave = true
        aimAngle = SaveUtil.getAttrFloat(elem, "aimAngle")
        cooldown = SaveUtil.getAttrFloat(elem, "cooldown")
        justDeployed = false

        val x = elem.getAttributeValue("aimPointX")?.toFloat()
        val y = elem.getAttributeValue("aimPointY")?.toFloat()
        if (x != null && y != null) {
            aimPoint = MutFPoint(x, y)
            aimVelocity = ConstFPoint(
                elem.getAttributeValue("aimVelX")?.toFloat() ?: 0f,
                elem.getAttributeValue("aimVelY")?.toFloat() ?: 0f
            )
        }
        fireReady = elem.getAttributeValue("fireReady")?.toBoolean() ?: (cooldown < 0f)
    }

    private class InterceptorLaser(
        val ownerShip: Ship,
        val weapon: LaserBlueprint
    ) : AbstractProjectile(null) {

        private val sprite = ownerShip.sys.animations[weapon.projectile!!].spriteAt(ownerShip.sys, 0)

        private val hitAnimation = ownerShip.sys.animations[weapon.explosion]

        override val speed: Int = speedFor(weapon)

        override val antiDroneBP: AbstractWeaponBlueprint get() = weapon
        override val antiDroneExemption: Ship get() = ownerShip

        override val serialisationType: String get() = LASER_SERIALISATION_TYPE

        override fun renderPreTranslated(g: Graphics) {
            sprite.draw(-sprite.width / 2f, -sprite.height / 2f)
        }

        override fun reachedTarget() {
            // Do nothing and fly off into space.
        }

        // Since we don't override crossedShieldLine, we completely ignore shields.

        override fun hitOtherProjectile(currentSpace: Ship) {
            currentSpace.playCentredAnimation(hitAnimation, position)
        }

        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)

            SaveUtil.addAttrRef(elem, "ownerShip", refs, ownerShip)
            SaveUtil.addAttr(elem, "weapon", weapon.name)
        }
    }


    companion object {
        const val LASER_SERIALISATION_TYPE = "defenceDroneLaser"

        // The target type used by defence 2 drones, this also shoots
        // at asteroids/missiles.
        private const val LASERS_TARGET = "LASERS"

        // Used by anti-drones, this only fires at drones.
        private const val DRONES_TARGET = "DRONES"

        // Used by certain drones in Multiverse that can target solid projectiles, lasers and drones
        private const val OMNI_TARGET = "ALL"

        // The Targetable classes the original stores in its projectiles and drones
        // (Missile ctor 0x451900, LaserBlast::OnInit, SpaceDrone ctor 0x426930,
        // boarder pod ctor 0x4296c0, Projectile ctor 0x4598e0).
        private const val CLASS_MISSILE = 1
        private const val CLASS_ASTEROID = 2
        private const val CLASS_DRONE = 3
        private const val CLASS_LASER = 4
        private const val CLASS_OTHER = 5
        private const val CLASS_POD = 6

        // SetDeployed (0x4272e0) sets the weapon cooldown to one second.
        private const val DEPLOY_COOLDOWN = 1f

        // The aim point must be inside these bounds on both axes
        // (the floats at 0x877250 and 0x877254), which is what stops the
        // drone defending the right-hand side of some larger ships.
        private const val RANGE_MIN = -150f
        private const val RANGE_MAX = 450f

        // 30 degrees per speed factor unit, which is 16 per second (0x877248).
        private val GUN_TURN_RATE = Math.toRadians(30.0 * 16.0).toFloat()

        fun loadProjectileFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: (IProjectile) -> Unit) {
            val weaponName = SaveUtil.getAttr(elem, "weapon")
            val weapon = game.blueprintManager[weaponName] as LaserBlueprint

            SaveUtil.getAttrRef(elem, "ownerShip", refs, Ship::class.java) { owner ->
                val laser = InterceptorLaser(owner!!, weapon)
                laser.loadPropertiesFromXML(elem, refs)
                callback(laser)
            }
        }

        private fun speedFor(type: LaserBlueprint): Int {
            // Lasers default to a speed of 60, DAT 0x87724c (x16 converts the
            // speed into pixels-per-second).
            return (type.speed ?: 60) * 16
        }
    }
}
