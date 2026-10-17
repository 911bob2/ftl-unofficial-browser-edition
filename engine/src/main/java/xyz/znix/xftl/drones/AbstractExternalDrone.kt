package xyz.znix.xftl.drones

import org.jdom2.Element
import xyz.znix.xftl.*
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.math.MutFPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import xyz.znix.xftl.weapons.DroneBlueprint
import xyz.znix.xftl.weapons.IProjectile
import kotlin.math.*
import kotlin.random.Random

abstract class AbstractExternalDrone(
    type: DroneBlueprint,

    /**
     * True if this drone should fly around the enemy ship, rather than
     * it's owner's ship.
     */
    val onEnemy: Boolean

) : AbstractDrone(type) {

    /**
     * The [DroneFlightController] that defines this drone's flight path.
     *
     * This is used to orbit the shield bubble (like defence drones) or
     * pick positions around but behind it (like combat drones).
     */
    abstract val flightController: DroneFlightController

    /**
     * If true, the drone isn't automatically rotated by hacking.
     *
     * The drone must then apply [stunRotationAnimation] itself.
     */
    protected open val useCustomStunRotation: Boolean get() = false

    /**
     * The ship this drone is flying around.
     */
    lateinit var targetShip: Ship
        private set

    /**
     * The amount of time this drone has been active (powered on and
     * not stunned) for.
     *
     * This is used by the anti-drone to avoid shooting at drones
     * that have been turned on very shortly prior and will likely
     * change direction.
     */
    var timeActive: Float = 0f
        private set

    /**
     * The radius in pixels of the circular 'hitbox' around this drone.
     *
     * This is used for drone/projectile collisions. SpaceDrone::CollisionMoving
     * (0x420c90) hits when the projectile is within ten pixels (squared
     * distance below 100, 0x8771b8) of the drone, and a projectile's own
     * radius is three, leaving seven for the drone.
     */
    open val hitboxRadius: Int get() = 7

    /**
     * True from the moment the drone is hit by something lethal until its
     * explosion animation ends (SpaceDrone::BlowingUp, 0x427aa0).
     *
     * The drone is frozen in place, cannot be hit again, is drawn as the
     * explosion instead of its sprite (SpaceDrone::OnRender 0x422040) and
     * is not a valid target for defence drones (SpaceDrone::ValidTarget
     * 0x422bb0). Only when the animation finishes is it destroyed.
     */
    val isBlowingUp: Boolean get() = explosion != null
    private var explosion: FTLAnimation? = null

    override val isStunned: Boolean get() = super.isStunned || ionStunTimer > 0f

    // An exploding drone does nothing (see isBlowingUp).
    override val isRunning: Boolean get() = super.isRunning && !isBlowingUp
    private var ionStunTimer: Float = 0f

    /**
     * The speed multiplier applied to the orbit around the shields, used
     * by the shield drone to stop while it charges (0x428300).
     */
    open val orbitSpeedScale: Float get() = 1f

    /**
     * The drone's actual velocity over the last update, in pixels per second.
     *
     * SpaceDrone::GetSpeed (0x4212b0) reports the displacement since the
     * previous frame (zero unless powered and deployed), and that is what
     * the defence drones lead their shots with.
     */
    var measuredVelocity: FPoint = ConstFPoint.ZERO
        private set

    protected var stunRotationAnimation: Float = 0f
    private var stunSparksAnimation: FTLAnimation? = null
    private var stunSparksMirror: Boolean = false

    protected val game: InGameState get() = ownerShip.sys

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)

        // If we're being deserialised, targetShip will already be set.
        // This is required since we don't know what the player and enemy
        // ship are during deserialisation.
        if (!this::targetShip.isInitialized) {
            targetShip = when {
                onEnemy -> ownerShip.sys.getEnemyOf(ownerShip)
                    ?: error("Cannot deploy drone '${type.name}' with no enemy present!")

                else -> ownerShip
            }
        }

        targetShip.externalDrones.add(this)

        flightController.init()

        onInit()
    }

    protected abstract fun onInit()

    override fun update(dt: Float) {
        super.update(dt)

        // If the drone disappears (for example, when jumping) then don't
        // leave it as being active, as that prevents it from being used again.
        if (!targetShip.externalDrones.contains(this)) {
            recall()
        }

        if (isRunning) {
            timeActive += dt
        } else {
            timeActive = 0f
        }

        ionStunTimer = max(0f, ionStunTimer - dt)

        // SpaceDrone OnLoop (0x428010): while exploding the drone only advances
        // its explosion animation, and is destroyed when that finishes.
        val explosion = explosion
        if (explosion != null) {
            measuredVelocity = ConstFPoint.ZERO
            explosion.update(dt)
            if (explosion.isStopped) {
                this.explosion = null
                destroy()
            }
            return
        }

        if (isStunned) {
            stunRotationAnimation += Math.toRadians(480.0).toFloat() * dt
            stunSparksAnimation?.update(dt)
            measuredVelocity = ConstFPoint.ZERO
            return
        }
        stunRotationAnimation = 0f

        val lastX = flightController.position.xf
        val lastY = flightController.position.yf

        flightController.update(dt)

        measuredVelocity = when {
            !isPowered || dt <= 0f -> ConstFPoint.ZERO
            else -> ConstFPoint(
                (flightController.position.xf - lastX) / dt,
                (flightController.position.yf - lastY) / dt
            )
        }
    }

    /**
     * SpaceDrone::BlowUp (0x4215f0): start the explosion animation (and its
     * sound, unless [silent]). Does nothing if the drone is already
     * exploding. The drone is destroyed when the animation ends, see [update].
     */
    override fun blowUp(silent: Boolean) {
        if (explosion != null)
            return

        explosion = explodeAnimation.startSingle(game)
        if (!silent)
            explodeSound.play()
    }

    /**
     * Drone::SetDestroyed(true, true) (0x462110), reached when the explosion
     * animation has finished (0x428010): the drone is gone and the rebuild
     * timer starts at ten seconds.
     */
    override fun destroy() {
        explosion = null
        super.destroy()
    }

    override fun removeInstance() {
        super.removeInstance()

        targetShip.externalDrones.remove(this)
    }

    override fun onEnemyShipUpdated() {
        super.onEnemyShipUpdated()

        if (!onEnemy)
            return

        // If we're deployed to an enemy ship, check if it's gone.
        if (ownerShip.sys.getEnemyOf(ownerShip) == targetShip)
            return

        // DroneSystem::OnLoop (0x639a50) recovers the drone rather than
        // destroying it, so there is no explosion when its target leaves.
        recall()
        targetShip.externalDrones.remove(this)
    }

    /**
     * DroneSystem::OnLoop (0x639a50, target gone) and the jump recall
     * (0x637d40, vtable slot 35): a deployed, undestroyed space drone
     * taken out of play - its fight is over or the ship jumped - gives
     * its drone part back with the Drone Recovery Arm. Destroyed drones
     * never do, and boarder pods and hacking drones are not recoverable.
     */
    private fun recall() {
        if (ownerShip.hasAugment(AugmentBlueprint.DRONE_RECOVERY))
            ownerShip.dronesCount++
        removeInstance()
    }

    /**
     * Draw this ship onto the target.
     *
     * This is called by the ship this drone is flying around.
     */
    open fun renderExternal(g: Graphics) {
        val explosion = explosion
        if (explosion != null) {
            renderExplosion(g, explosion)
            return
        }

        g.pushTransform()

        var x = flightController.position.xf
        var y = flightController.position.yf

        if (flightController.pixelAligned) {
            x = x.roundToInt().f
            y = y.roundToInt().f
        }

        var rotation = flightController.rotation
        if (!useCustomStunRotation)
            rotation += stunRotationAnimation
        g.translate(x, y)
        g.rotate(0f, 0f, rotation / TWO_PI * 360f)

        onRender(g)

        g.popTransform()

        // If we're ion-stunned or being hacked, draw sparks on top
        renderStunSparks()
    }

    private fun renderExplosion(g: Graphics, explosion: FTLAnimation) {
        // SpaceDrone::OnRender (0x422040) draws the explosion animation centred
        // on the drone instead of the drone itself.
        val frame = explosion.currentFrame
        frame.draw(
            flightController.position.xf - frame.width / 2,
            flightController.position.yf - frame.height / 2
        )
    }

    private fun renderStunSparks() {
        if (!isStunned) {
            stunSparksAnimation = null
            return
        }

        if (stunSparksAnimation == null || stunSparksAnimation?.isStopped == true) {
            stunSparksAnimation = game.animations["stun_spark_big"].startSingle(game)

            // See doc/hacking for details about this
            stunSparksMirror = VisualRandom.nextBoolean()
        }

        val sparks = stunSparksAnimation!!.currentFrame
        val pos = flightController.position

        // Use the top-right corner rather than the top-left
        // as FTL appears to, since this looks much better.
        var x1 = sparks.width - 32
        var x2 = sparks.width - 0

        if (stunSparksMirror) {
            val tmp = x1
            x1 = x2
            x2 = tmp
        }

        sparks.draw(
            pos.x - 16f, pos.y - 16f, pos.x + 16f, pos.y + 16f,
            x1.f, 0f, x2.f, 32f
        )
    }

    protected abstract fun onRender(g: Graphics)

    protected fun drawCentred(image: Image, filter: Colour = Colour.white) {
        image.draw(-image.width / 2.f, -image.height / 2f, filter)
    }

    /**
     * SpaceDrone::CollisionMoving (0x420c90), as called for each projectile
     * that is not owned by this drone's ship (0x459550).
     *
     * A drone that is exploding cannot be hit. Otherwise a dodge roll
     * (rand%10 below the blueprint's dodge value) is made for every projectile
     * in range, whether or not the drone is powered; only a powered drone
     * actually avoids the shot (the projectile flies on, with a 'miss' label),
     * so an unpowered drone is always hit.
     */
    open fun canCollideWith(proj: IProjectile): Boolean {
        if (isBlowingUp)
            return false

        // Check if we're exempt from this projectile due to being
        // owned by the same ship.
        if (proj.antiDroneExemption == ownerShip)
            return false

        // The projectile must have a weapon blueprint to represent
        // the damage it can do to us.
        if (proj.antiDroneBP == null)
            return false

        val dodged = ftl.browser.BrowserRandom.nextInt(10) < (type.dodge ?: 0)
        if (dodged && isPowered) {
            targetShip.showDamageTextAt(flightController.position, "text_miss", Colour.white)
            return false
        }

        return true
    }

    /**
     * The rest of SpaceDrone::CollisionMoving (0x420c90) once a hit is
     * confirmed: ion damage stuns a powered drone for five seconds per point
     * (SpaceDrone::Ionize 0x427b30 ignores unpowered drones) and nothing else
     * happens; otherwise hull damage blows the drone up (BlowUp 0x4215f0).
     * Projectiles that only damage systems do nothing to a drone.
     */
    open fun hitProjectile(other: IProjectile) {
        // We checked this is non-null in canCollideWith.
        val type = other.antiDroneBP
        requireNotNull(type) { "Can't hit projectile without an anti-drone blueprint!" }

        if (type.ionDamage > 0) {
            if (isPowered) {
                ionStunTimer = 5f * type.ionDamage
            }
            return
        }

        if (type.damage > 0) {
            blowUp()
            return
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrRef(elem, "targetShip", refs, targetShip)

        val elemFC = Element("flightControl")
        flightController.saveToXML(elemFC, refs)
        elem.addContent(elemFC)

        // These are optional, so older saves load with neither.
        SaveUtil.addTagFloat(elem, "ionStunTimer", ionStunTimer, 0f)
        explosion?.let { SaveUtil.addTagFloat(elem, "explosionTimer", it.timer) }
    }

    override fun loadFromXML(elem: Element, refs: RefLoader, containingShip: Ship) {
        // We're always saved as part of the target ship.
        // This is to deal with cases like an enemy drone after that enemy was destroyed.
        targetShip = containingShip

        super.loadFromXML(elem, refs, containingShip)

        ionStunTimer = SaveUtil.getOptionalTagFloat(elem, "ionStunTimer") ?: 0f
        val explosionTimer = SaveUtil.getOptionalTagFloat(elem, "explosionTimer")

        // Don't deserialise the flight controller until after init() is called.
        // Thus we have to delay it until reference resolution time.
        refs.addOnResolveFunction {
            flightController.loadFromXML(elem.getChild("flightControl"), refs)

            if (explosionTimer != null) {
                explosion = explodeAnimation.startSingle(game).also { it.timer = explosionTimer }
            }
        }
    }
}

abstract class DroneFlightController(val drone: AbstractExternalDrone) {
    protected val ship: Ship get() = drone.targetShip

    /**
     * If true, the drone is always snapped to a rounded position.
     *
     * This should be used when there's no rotation, as it makes
     * the sprite sharper.
     */
    abstract val pixelAligned: Boolean

    val position = MutFPoint(0f, 0f)

    var rotation: Float = 0f

    // Veloctiy in pixels per second, as used by the anti-drone
    abstract val velocity: FPoint

    abstract fun update(dt: Float)

    /**
     * Called when the owner drone has finished seting itself up.
     */
    open fun init() {
    }

    open fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "x", position.xf)
        SaveUtil.addAttrFloat(elem, "y", position.yf)
        SaveUtil.addAttrFloat(elem, "rotation", rotation)
    }

    open fun loadFromXML(elem: Element, refs: RefLoader) {
        position.xf = SaveUtil.getAttrFloat(elem, "x")
        position.yf = SaveUtil.getAttrFloat(elem, "y")
        rotation = SaveUtil.getAttrFloat(elem, "rotation")
    }

    companion object {
        /**
         * Get the angle the drone needs to face in to point at a given
         * location when it's floating at the set coordinates.
         */
        fun getAngleFrom(at: IPoint, to: IPoint): Float {
            val base = atan2(to.y.f - at.y.f, to.x.f - at.x.f)

            // With atan2 we get an angle where zero means 'to the right'.
            // However our drones are facing up in the images, so we need
            // to rotate 90° to correct for that.
            var corrected = base + TWO_PI / 4

            // Fix the value up to be in the 0..2pi range.
            if (corrected < 0)
                corrected += TWO_PI
            if (corrected > TWO_PI)
                corrected -= TWO_PI

            return corrected
        }
    }
}

/**
 * A flight controller that constantly flies around the ship's
 * shields line. Used on defence and shield overcharger drones.
 *
 * This follows the original: the drone always heads for a destination on
 * the shield ellipse, and each time it gets there the destination moves one
 * degree further round the ellipse's parametric angle (DefenseDrone::OnLoop
 * 0x428010 with PickDestination 0x426f00 and SpaceDrone::SetDestination
 * 0x426e30), flying at the blueprint speed times sixteen pixels per second
 * (the speed factor is sixteen times the frame time, 0x5ca360 snaps to the
 * destination when it is closer than one step).
 */
class OrbitFlightController(drone: AbstractExternalDrone) : DroneFlightController(drone) {
    override val pixelAligned: Boolean get() = true

    private lateinit var shieldBounds: IPoint

    // The speed in pixels per second, before any slowing down.
    private var typeSpeed: Float = 0f

    // The parametric angle, in radians, of the next destination.
    // This is the original's SpaceDrone+0x16c, kept in radians. The ellipse
    // is x = a*cos(theta), y = b*sin(theta).
    var theta = 0f

    private val destination = MutFPoint(0f, 0f)

    private val vel = MutFPoint(0f, 0f)
    override val velocity: FPoint get() = vel

    private var poweredLastFrame = false

    override fun init() {
        super.init()

        shieldBounds = drone.targetShip.shieldHalfSize

        // The original speed is the blueprint value, in units of sixteen
        // pixels per second. DRONE_SPEED is only read by CrewMember::GetMoveSpeed
        // (0x47b450), so it doesn't touch space drones.
        typeSpeed = drone.type.speed!! * SPEED_FACTOR_PER_SECOND

        // SpaceDrone's constructor (0x426930) starts the parametric angle at
        // rand%360 degrees, and SetDeployed (0x4272e0) puts the drone on the
        // first destination and then picks the one after it.
        theta = Math.toRadians(ftl.browser.BrowserRandom.nextInt(360).toDouble()).toFloat()
        pickDestination()
        position.set(destination)
        pickDestination()
        rotation = 0f
    }

    /**
     * SpaceDrone::SetDestination (0x426e30) followed by the one degree
     * advance of PickDestination (0x426f00).
     */
    private fun pickDestination() {
        destination.xf = ship.shieldOrigin.x.f + shieldBounds.x * cos(theta)
        destination.yf = ship.shieldOrigin.y.f + shieldBounds.y * sin(theta)
        theta += Math.toRadians(1.0).toFloat()
    }

    override fun update(dt: Float) {
        val wasStartedUp = !poweredLastFrame && drone.isPowered
        poweredLastFrame = drone.isPowered

        if (!drone.isPowered) {
            // Copied from CombatFlightController
            position.xf += vel.xf * dt
            position.yf += vel.yf * dt

            val slowdownFactor = 0.1f * dt * 16
            vel *= 1 - slowdownFactor

            if (vel.fLength < 16) {
                vel.set(0f, 0f)
            }
            return
        }

        // When we're turned on, snap back to the closest point on the shields
        // (SetPowered 0x426f60 takes the parametric angle of the drone's
        // current position and heads for that point first).
        if (wasStartedUp) {
            val circleX = (position.xf - ship.shieldOrigin.x.f) / shieldBounds.x
            val circleY = (position.yf - ship.shieldOrigin.y.f) / shieldBounds.y
            theta = atan2(circleY, circleX)
            pickDestination()
            theta -= Math.toRadians(1.0).toFloat()
        }

        updateMovement(dt)
    }

    private fun updateMovement(dt: Float) {
        // FUN_00427fb0: reaching the destination picks the next one.
        if (position.xf == destination.xf && position.yf == destination.yf)
            pickDestination()

        val lastX = position.xf
        val lastY = position.yf

        val step = typeSpeed * drone.orbitSpeedScale * dt
        val deltaX = destination.xf - position.xf
        val deltaY = destination.yf - position.yf
        val distSq = deltaX * deltaX + deltaY * deltaY

        // A zero step (a zero-length frame) on top of the destination must snap, not divide zero by zero.
        if (distSq <= step * step) {
            // FUN_005ca360 returns the destination when it is within a step.
            position.xf = destination.xf
            position.yf = destination.yf
        } else {
            val dist = sqrt(distSq)
            position.xf += deltaX / dist * step
            position.yf += deltaY / dist * step
        }

        rotation = 0f

        // Keep the coasting velocity up to date for when we power down.
        if (dt > 0f) {
            vel.xf = (position.xf - lastX) / dt
            vel.yf = (position.yf - lastY) / dt
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addAttrFloat(elem, "angleAboutShip", theta)
        SaveUtil.addAttrFloat(elem, "destX", destination.xf)
        SaveUtil.addAttrFloat(elem, "destY", destination.yf)
        SaveUtil.addAttrFloat(elem, "speedX", vel.xf)
        SaveUtil.addAttrFloat(elem, "speedY", vel.yf)
        SaveUtil.addAttrBool(elem, "poweredLastFrame", poweredLastFrame)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)

        theta = SaveUtil.getAttrFloat(elem, "angleAboutShip")
        vel.xf = SaveUtil.getAttrFloat(elem, "speedX")
        vel.yf = SaveUtil.getAttrFloat(elem, "speedY")
        poweredLastFrame = SaveUtil.getAttrBool(elem, "poweredLastFrame")

        // Saves from before the hopping orbit only have the angle: the
        // destination is then the point on the ellipse at that angle.
        val savedX = elem.getAttributeValue("destX")?.toFloat()
        val savedY = elem.getAttributeValue("destY")?.toFloat()
        if (savedX != null && savedY != null) {
            destination.xf = savedX
            destination.yf = savedY
        } else {
            pickDestination()
        }
    }

    companion object {
        // CFPS::OnLoop (0x5ba020): the speed factor is the frame time in
        // seconds times sixteen.
        private const val SPEED_FACTOR_PER_SECOND = 16f
    }
}

/**
 * A flight controller that flies to different parts
 * of the enemy ship, and lines behind (but pointing towards)
 * the shield.
 */
class CombatFlightController(drone: AbstractExternalDrone) : DroneFlightController(drone) {
    override val pixelAligned: Boolean get() = false

    private var nextDestination: IPoint = ConstPoint.ZERO
    private var currentDestAngle: Float = 0f // In radians

    // We'll use this quite a bit
    private lateinit var shieldSize: IPoint

    // The speed in pixels per second
    private var typeSpeed: Float = 0f

    // The drone's current velocity
    private val vel = MutFPoint(0f, 0f)
    override val velocity: FPoint get() = vel

    // Times how long it's been since we started slowing down to fire
    private var pauseTimer: Float = 0f

    // The angle the drone needs to point in at the last and next
    // destination, which we then interpolate between.
    private var lastDestRotation: Float = 0f
    private var nextDestRotation: Float = 0f

    /**
     * Set to true when the drone should slow down and stop, ready
     * to take a shot.
     */
    var paused: Boolean = false

    /**
     * How long it should take the drone to stop once paused.
     */
    var pauseStopTime: Float = 0f

    /**
     * A callback that's run when the drone reaches a destination point.
     */
    var onReachedDestination: (() -> Unit)? = null

    /**
     * The point the drone will face when it next stops.
     */
    var nextStopTarget: IPoint = ConstPoint.ZERO

    override fun init() {
        shieldSize = ship.shieldHalfSize

        // See doc/combat-drone for the speed information.
        val shieldSemiMajor = max(shieldSize.x, shieldSize.y)
        // (DRONE_SPEED only affects crew drones, see CrewMember::GetMoveSpeed 0x47b450.)
        typeSpeed = shieldSemiMajor * drone.type.speed!! / 21.875f

        // Face in the direction of the centre of the ship, since
        // we don't know our actual target yet.
        nextStopTarget = ship.shieldOrigin

        // Pick a fully random initial destination, and jump to it
        currentDestAngle = ftl.browser.BrowserRandom.nextInt(360) * PIf / 180f
        pickNextDestination()

        position.xf = nextDestination.x.f
        position.yf = nextDestination.y.f

        // Pick another destination to fly to, as the first 'real' point.
        // This will avoid us firing a shot on the first update.
        pickNextDestination()
    }

    override fun update(dt: Float) {
        // Check if the drone is paused before applying the power-off
        // momentum movement, as otherwise if you turned off a drone when
        // it was coming to a stop it's jolt a bit since we're not updating
        // the speed here.
        if (paused) {
            pauseTimer += dt

            // If we're still slowing down, apply that
            val remaining = pauseStopTime - pauseTimer
            if (remaining > 0f) {
                val newSpeedMult = remaining / 1.5f

                position.xf += vel.xf * dt * newSpeedMult
                position.yf += vel.yf * dt * newSpeedMult
            }

            return
        }

        if (!drone.isPowered) {
            position.xf += vel.xf * dt
            position.yf += vel.yf * dt

            val slowdownFactor = 0.1f * dt * 16
            vel *= 1 - slowdownFactor

            if (vel.fLength < 16) {
                vel.set(0f, 0f)
            }

            return
        }

        pauseTimer = 0f

        val inPosition = updateMovement(dt)
        if (inPosition) {
            onReachedDestination?.let { it() }

            pickNextDestination()
        }
    }

    // Moves, and returns true if we're in position.
    private fun updateMovement(dt: Float): Boolean {
        val deltaX = nextDestination.x - position.xf
        val deltaY = nextDestination.y - position.yf
        val distanceToDest = sqrt(deltaX * deltaX + deltaY * deltaY)

        // Update our rotation, to face towards the new target.
        val finalRotation = getAngleFrom(nextDestination, nextStopTarget)

        // If we'd move past the target on this update, snap to it.
        if (distanceToDest <= dt * typeSpeed) {
            position.set(nextDestination)
            rotation = finalRotation
            return true
        }

        // Figure out the direction we have to rotate in, since we might
        // need to wrap around the 2pi/0 transition.
        val deltaRotation: Float = when {
            // No wrap-around
            abs(finalRotation - rotation) < PI -> finalRotation - rotation

            // Wrap-around, rotating in the positive direction
            finalRotation < rotation -> finalRotation + (TWO_PI - rotation)

            // Wrap-around, rotating in the negative direction
            else -> -(rotation + (TWO_PI - finalRotation))
        }

        // Figure out the rate of rotation required to get a smooth
        // rotation over the whole movement.
        val rotationRate = deltaRotation / distanceToDest

        // Apply an amount of rotation consistent with that, based
        // on the distance we're about to travel.
        rotation += rotationRate * (typeSpeed * dt)

        // Update our position to move closer towards the target
        val unitX = deltaX / distanceToDest
        val unitY = deltaY / distanceToDest

        vel.xf = unitX * typeSpeed
        vel.yf = unitY * typeSpeed

        position.xf += vel.xf * dt
        position.yf += vel.yf * dt

        return false
    }

    private fun pickNextDestination() {
        // 0x4270e0: the destination angle is a whole number of degrees
        // (rand % 360), re-rolled while it is less than 90 degrees from
        // the previous one. The difference is a plain subtraction, with no
        // wrap-around, so a roll across the 0/360 seam is always accepted.
        val oldDegrees = Math.round(currentDestAngle * 180f / PIf)
        var newDegrees: Int
        do {
            newDegrees = ftl.browser.BrowserRandom.nextInt(360)
        } while (abs(newDegrees - oldDegrees) < 90)
        val newAngle = newDegrees * PIf / 180f

        // Save the new angle for next time
        currentDestAngle = newAngle

        // Calculate the new destination, relative to the centre of the shields
        val baseX = shieldSize.x * cos(newAngle) * 1.15
        val baseY = shieldSize.y * sin(newAngle) * 1.15

        nextDestination = ConstPoint(
            ship.shieldOrigin.x + baseX.toInt(),
            ship.shieldOrigin.y + baseY.toInt()
        )

        // Pick the new rotation, and save the old one so we can
        // interpolate between them.
        lastDestRotation = nextDestRotation
        nextDestRotation = getAngleFrom(nextDestination, nextStopTarget)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        SaveUtil.addPoint(elem, "nextDestination", nextDestination)
        SaveUtil.addPoint(elem, "nextStopTarget", nextStopTarget)
        SaveUtil.addAttrFloat(elem, "currentDestAngle", currentDestAngle)
        SaveUtil.addAttrFloat(elem, "speedX", vel.xf)
        SaveUtil.addAttrFloat(elem, "speedY", vel.yf)
        SaveUtil.addAttrFloat(elem, "pauseTimer", pauseTimer)
        SaveUtil.addAttrFloat(elem, "lastDestRotation", lastDestRotation)
        SaveUtil.addAttrFloat(elem, "nextDestRotation", nextDestRotation)
        SaveUtil.addAttrBool(elem, "paused", paused)
        SaveUtil.addAttrFloat(elem, "pauseStopTime", pauseStopTime)
    }

    override fun loadFromXML(elem: Element, refs: RefLoader) {
        super.loadFromXML(elem, refs)

        nextDestination = SaveUtil.getPoint(elem, "nextDestination")
        nextStopTarget = SaveUtil.getPoint(elem, "nextStopTarget")
        currentDestAngle = SaveUtil.getAttrFloat(elem, "currentDestAngle")
        vel.xf = SaveUtil.getAttrFloat(elem, "speedX")
        vel.yf = SaveUtil.getAttrFloat(elem, "speedY")
        pauseTimer = SaveUtil.getAttrFloat(elem, "pauseTimer")
        lastDestRotation = SaveUtil.getAttrFloat(elem, "lastDestRotation")
        nextDestRotation = SaveUtil.getAttrFloat(elem, "nextDestRotation")
        paused = SaveUtil.getAttrBool(elem, "paused")
        pauseStopTime = SaveUtil.getAttrFloat(elem, "pauseStopTime")
    }
}
