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

    /** Hyperspace Lua: DefenseDrone::drone_image, gun_image_off, charging, on, engine_image written by a script (0 to 4). */
    fun hsSetImage(slot: Int, image: Image) {
        when (slot) { 0 -> { offImage = image; onImage = image }; 1 -> gunOffImage = image; 2 -> gunChargedImage = image; 3 -> gunOnImage = image; 4 -> engine = image }
    }

    private lateinit var offImage: Image
    private lateinit var onImage: Image
    private lateinit var engine: Image

    private lateinit var gunOffImage: Image
    private lateinit var gunOnImage: Image
    private lateinit var gunChargedImage: Image

    internal val typeCooldown = type.cooldown!! / 1000f

    // A laser in vanilla. Hyperspace content also gives defence drones missile and flak weapons
    // (SpaceDrone::GetNextProjectile builds the shot from the blueprint's type).
    private lateinit var weapon: AbstractWeaponBlueprint
    private var weaponSpeed: Int = 0

    // The gun angle (in the drone images' frame, 0 is straight up) and the
    // angle it is turning to (negative for none), SpaceDrone+0x170/+0x178.
    internal var aimAngle: Float = 0f
    internal var goalAngle: Float = -1f

    internal var cooldown: Float = 0f
    internal var fireReady: Boolean = false
    private var justDeployed: Boolean = false

    // The point we're aiming at (null when there is none, the original's
    // -FLT_MAX), and the velocity of what it was taken from.
    internal var aimPoint: MutFPoint? = null
    internal var aimVelocity: FPoint = ConstFPoint.ZERO

    // The projectile or drone the aim point came from, and the last one we
    // locked onto. Neither is saved, as in the original.
    internal var currentTarget: Any? = null
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
        // (Hyperspace sessions accept the other projectile weapons MV gives defence drones.)
        weapon = type.weaponBlueprint ?: ownerShip.sys.blueprintManager.inertWeapon

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
        updateLastFrameFlags(dt)

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

        // The shot itself comes from GetNextProjectile (0x429ee0), which SpaceManager runs for every drone after the
        // projectile loop ([nativeNextProjectile]; native 1.6.14 drone-order probe 3: defence lasers are made there).
        if (fireReady && aimPoint != null) {
            val b = body
            if (b != null && DroneRuntime.loopsInSpace(b)) firePending = true else fire()
        }
    }

    private var firePending = false

    /**
     * SpaceDrone::GetNextProjectile (0x429ee0) for a ready defence drone: no shot while unpowered or hacked; a target
     * that is cloaked, dying or jumping (Targetable vtable +0x2c/+0x34/+0x38; a projectile keeps the defaults, a drone
     * dies while it explodes) drops the aim point and restarts the cooldown without a shot; otherwise the laser.
     */
    internal fun nativeNextProjectile() {
        if (!firePending) return
        firePending = false
        if (!isRunning || !fireReady || aimPoint == null) return
        val target = currentTarget
        if (target is AbstractExternalDrone && target.isBlowingUp) {
            fireReady = false
            aimPoint = null
            cooldown = typeCooldown
            return
        }
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

            // DefenseDrone::ValidTargetObject (0x421020): a shot whose owner is the drone's own ship is never a target
            // (a script's drone firing at its own ship). Only Hyperspace scripts can make one; vanilla has none.
            if (xyz.znix.xftl.hs.HS.active && projectile.hsOwnerId == xyz.znix.xftl.hs.weapons.HsWeapons.shipId(ownerShip))
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
        // unless we are an anti-drone (blueprint target type 3). Hyperspace's
        // DefenseDrone::SetWeaponTarget (CustomDrones.cpp) also lets any drone
        // target (class 3) be taken again: ALL and SOLID drones keep firing at it.
        if (candidate !== lockedTarget || type.targetType == 3) return true
        return xyz.znix.xftl.hs.HS.active && candidate is AbstractExternalDrone
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
     * and pods, LASERS adds lasers, DRONES takes drones and pods. Hyperspace's
     * hook (CustomDrones.cpp) adds ASTEROIDS (asteroids only), SOLID (anything
     * but lasers: its case falls through to ALL's) and ALL (any target but a
     * ship or class 5).
     */
    private fun isValidClass(targetClass: Int): Boolean = validTargetClass(type.targetType, targetClass)

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

        if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().defenseDroneFix) {
            if (!aligned || !xyz.znix.xftl.hs.weapons.HsDrones.defenceInRange(if (ownerShip.isPlayerShip) 0 else 1, targetShip, currentTarget, point)) {
                aimPoint = null
                return
            }
            lockedTarget = currentTarget
            return
        }

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
        // GetNextProjectile 0x429ee0 starts the shot at Point((int) currentLocation.x, (int) currentLocation.y): the
        // drone's position cut to whole pixels (toward zero), aimed at the aim point rounded to whole pixels.
        val start = ConstFPoint(flightController.position.xf.toInt().toFloat(), flightController.position.yf.toInt().toFloat())
        val aim = ConstPoint(point.xf.roundToInt(), point.yf.roundToInt())

        val flak = weapon as? FlakBlueprint
        if (flak != null && xyz.znix.xftl.hs.HS.active) {
            // Hyperspace SpaceDrone::GetNextProjectile (CustomDrones.cpp): a flak weapon sends one LaserBlast per
            // mini projectile to points spread around the aim point, r = sqrt(rand/2^31) * radius (angularRadius
            // when given: angularRadius degrees at the target's distance), theta = rand % 360 degrees. The centre and
            // the distance are those of the shot super() made (ret->target, ret->position: [aim], [start]); the pieces
            // start at the drone's unrounded currentLocation.
            val angular = flak.hs?.angularRadius ?: -1f
            val dist = kotlin.math.sqrt((aim.x - start.xf).pow(2) + (aim.y - start.yf).pow(2))
            val radius = if (angular == -1f) flak.radius.toFloat() else angular * dist * 0.01745329f
            for ((image, fake) in flak.miniProjectiles) {
                val r = kotlin.math.sqrt(xyz.znix.xftl.hs.weapons.HsWeapons.r32() / 2147483648f) * radius
                val theta = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360) * 0.01745329f
                val shot = construct(point) { InterceptorLaser(ownerShip, weapon, image, fake) }
                shot.setInitialPath(flightController.position,
                    ConstPoint((aim.x + r * cos(theta)).roundToInt(), (aim.y + r * sin(theta)).roundToInt()))
                // HS queues the pieces and hands out one per GetNextProjectile, each through DRONE_FIRE.
                if (xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.weapons.HsWeaponsLua.droneFire(shot, this)) continue
                targetShip.projectiles += shot
            }
        } else {
            val shot = construct(point) { InterceptorLaser(ownerShip, weapon) }

            shot.setInitialPath(start, aim)
            // Hyperspace SpaceDrone::GetNextProjectile (InternalEvents.cpp, priority -100): DRONE_FIRE, pre-empt
            // deletes the shot.
            if (!(xyz.znix.xftl.hs.HS.luaActive && xyz.znix.xftl.hs.weapons.HsWeaponsLua.droneFire(shot, this)))
                targetShip.projectiles += shot
        }

        weapon.launchSounds?.get()?.play()

        fireReady = false
        aimPoint = null
        cooldown = typeCooldown
    }

    /**
     * SpaceDrone::GetNextProjectile (1.6.14 0x429ee0): new LaserBlast(Point(currentLocation), currentSpace,
     * currentSpace, target), so CONSTRUCT_PROJECTILE sees the drone's space twice and the owner is the drone's ship.
     */
    private inline fun <T> construct(target: xyz.znix.xftl.math.FPoint, create: () -> T): T {
        val at = flightController.position
        val space = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(targetShip)
        val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(
            ConstFPoint(at.xf.toInt().toFloat(), at.yf.toInt().toFloat()), ConstFPoint(target.xf, target.yf), targetShip,
            xyz.znix.xftl.hs.weapons.HsWeapons.shipId(ownerShip), space, space)
        return xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context, create)
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
        val weapon: AbstractWeaponBlueprint,
        /** The flight image: the weapon's own, or a flak mini projectile's (Hyperspace). */
        val image: String = weapon.projectile ?: "",
        /** A fake flak piece (Hyperspace): drawn only, it hits nothing. */
        val fake: Boolean = false
    ) : AbstractProjectile(null) {

        private val sprite = ownerShip.sys.animations.getOrNull(image)?.spriteAt(ownerShip.sys, 0)

        private val hitAnimation = ownerShip.sys.animations.orEmpty(weapon.explosion ?: "explosion_missile1")

        override val collisionsEnabled: Boolean get() = !fake

        // A LaserBlast: SpaceManager's projectile loop moves it, so the laser GetNextProjectile hands over after that
        // loop first moves in the next frame's loop (native 1.6.14 drone-order probe 5), not in this frame's ship update.
        override val updatesInProjectileLoop: Boolean get() = true

        init {
            // Hyperspace Projectile::Initialize(*weaponBlueprint) for drone shots.
            if (xyz.znix.xftl.hs.HS.active) hsInitialize(weapon, speedFor(weapon) / 16)
        }

        override val speed: Int = speedFor(weapon)

        override val antiDroneBP: AbstractWeaponBlueprint get() = weapon
        override val antiDroneExemption: Ship get() = ownerShip

        override val serialisationType: String get() = LASER_SERIALISATION_TYPE

        override fun renderPreTranslated(g: Graphics) {
            sprite?.draw(-sprite.width / 2f, -sprite.height / 2f)
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
            if (image != weapon.projectile) SaveUtil.addAttr(elem, "image", image)
            if (fake) SaveUtil.addAttrBool(elem, "fake", true)
        }
    }


    companion object {
        const val LASER_SERIALISATION_TYPE = "defenceDroneLaser"

        /** [isValidClass] for a blueprint target type (DroneBlueprint.targetType) and a Targetable class. */
        internal fun validTargetClass(targetType: Int, targetClass: Int): Boolean = when (targetType) {
            3 -> targetClass == CLASS_DRONE || targetClass == CLASS_POD
            4 -> targetClass != CLASS_DRONE && targetClass != CLASS_OTHER
            2 -> targetClass == CLASS_ASTEROID
            5 -> targetClass != CLASS_LASER && targetClass != CLASS_OTHER
            6 -> targetClass != CLASS_OTHER
            else -> targetClass == CLASS_MISSILE || targetClass == CLASS_ASTEROID || targetClass == CLASS_POD
        }

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
            val weapon = game.blueprintManager.getOrNull(weaponName) as? AbstractWeaponBlueprint ?: game.blueprintManager.inertWeapon
            val image = elem.getAttributeValue("image") ?: weapon.projectile ?: ""
            val fake = elem.getAttributeValue("fake")?.toBoolean() ?: false

            SaveUtil.getAttrRef(elem, "ownerShip", refs, Ship::class.java) { owner ->
                val laser = InterceptorLaser(owner!!, weapon, image, fake)
                laser.loadPropertiesFromXML(elem, refs)
                callback(laser)
            }
        }

        private fun speedFor(type: AbstractWeaponBlueprint): Int {
            // Hyperspace missile defence drones: the missile default speed (35).
            if (type is MissileBlueprint) return (type.speed ?: 35) * 16
            // Hyperspace <defenseDroneFix> leads with 60 when the blueprint speed is not positive.
            if (xyz.znix.xftl.hs.HS.active && xyz.znix.xftl.hs.HS.cfg().defenseDroneFix && (type.speed ?: 60) <= 0) return 60 * 16
            // Lasers default to a speed of 60, DAT 0x87724c (x16 converts the
            // speed into pixels-per-second).
            return (type.speed ?: 60) * 16
        }
    }
}
