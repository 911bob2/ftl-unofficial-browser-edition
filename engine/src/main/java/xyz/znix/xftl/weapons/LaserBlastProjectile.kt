// Native 1.6.14 LaserBlast: OnInit 0x45dd40, OnUpdate 0x458660, ComputeHeading 0x4589a0, SetSpin 0x456760,
// OnRenderSpecific 0x456980, intercept helper 0x5ca6f0 (same bodies in 1.6.9, which HS hooks). LaserBlast members
// from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 ProjectileVTable.cpp / hyperspace.i.
package xyz.znix.xftl.weapons

import xyz.znix.xftl.AnimationSpec
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.rendering.Graphics
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The native LaserBlast body. SpaceManager::CreateLaserBlast and the burst (flak) factory use it with any weapon
 * blueprint; [flightImage] is the flight animation (the blueprint image, or a burst piece's own image).
 */
open class LaserBlastProjectile(type: AbstractWeaponBlueprint, room: Room, flightImage: String? = type.projectile) :
    AbstractWeaponProjectile(type, room), NativeLaserBlastFields {
    private val flightSpec: AnimationSpec? = flightImage?.let { ship.sys.animations.getOrNull(it) }
    override val defaultSpeed: Int get() = 60
    // Defence drones pick by Targetable type: 4 (LaserBlast::OnInit) is a laser, 2 (`<drone_targetable>`) a missile.
    override val isLaserForDD: Boolean get() = targetableType == 4
    override val isMissileForDD: Boolean get() = targetableType == 1 || targetableType == 2
    override val nativeType: Int get() = 1

    /** LaserBlast::spinAngle (degrees) and spinSpeed; the body only spins after SetSpin(spin > 0). */
    override var spinAngle = 0f
    override var spinSpeed = 0f
    /** LaserBlast::movingTarget: a Targetable the heading leads (a projectile or drone body). */
    override var movingTarget: Any? = null

    init {
        targetableType = 4
        flightSpec?.let { flightTracker.time = it.totalTime; flightTracker.frameCount = it.length }
        if (xyz.znix.xftl.hs.HS.active) hsInitialize(type, defaultSpeed) else nativeApplyBlueprint(type)
    }

    /**
     * HS SpaceManager::CreateBurstProjectile(fake): every damage field zero, ownerId/selfId -1, friendly fire on,
     * and death_animation.fScale 0.25.
     */
    var fakeBurst = false
        set(value) { field = value; if (value) hsExt?.damage?.copyFrom(fakeBurstDamage()) }
    override val deathAnimationScale: Float get() = if (fakeBurst) .25f else 1f
    override fun computeDamage(): Damage =
        if (fakeBurst) fakeBurstDamage().also { it.ownerShip = null } else super.computeDamage()

    /** A burst piece's flight_animation is a fresh GetAnimation(image) (HS CreateBurstProjectile): never started. */
    fun nativeBurstFlight() {
        val flight = flightTracker
        flight.running = false; flight.loop = false; flight.currentTime = 0f; flight.done = false
        flight.advance(0f)
    }

    /** SetSpin: a positive spin speed also picks a start angle of random32() % 360. */
    override fun nativeSetSpin(spin: Float) {
        if (spin > 0f) {
            spinSpeed = spin
            spinAngle = (ftl.browser.BrowserRandom.nextBits(31) % 360).toFloat()
        }
    }

    /** OnUpdate after the shared motion: the flight animation advances, then the spin (spinSpeed * SpeedFactor/16). */
    override fun nativeAfterMotion(dt: Float) {
        flightTrackerOrNull?.advance(dt)
        if (spinSpeed > 0f) spinAngle += spinSpeed * dt
    }

    /**
     * ComputeHeading: without a moving target, the direction to the target point. With one, the target point becomes
     * the target's centre, led by its per-frame speed over the intercept time for a fixed 60 * SpeedFactor speed.
     */
    fun nativeLaserComputeHeading(frameDt: Float) {
        val moving = movingTarget
        if (moving == null) { hsComputeHeading(); return }
        val centre = NativeTargetable.centre(moving) ?: return hsComputeHeading()
        val speed = NativeTargetable.speed(moving)
        var tx = centre.xf; var ty = centre.yf
        val dx = tx - position.xf; val dy = ty - position.yf
        val t = interceptTime(dx, dy, speed.xf, speed.yf, frameDt * 16f * 60f)
        if (t > 0f) { tx += speed.xf * t; ty += speed.yf * t }
        targetPos = xyz.znix.xftl.math.ConstFPoint(tx, ty)
        val x = tx - position.xf; val y = ty - position.yf
        val length = sqrt(x * x + y * y)
        var degrees = Math.toDegrees(acos(if (length == 0f) 0f else x / length).toDouble()).toFloat()
        if (y < 0f) degrees = -degrees
        hsSetHeading(degrees)
    }

    override fun renderPreTranslated(g: Graphics) {
        val spec = hsFlightSpec ?: flightSpec ?: return
        val frame = (flightTrackerOrNull?.currentFrame ?: 0).coerceIn(0, spec.length - 1)
        val sprite = spec.spriteAt(ship.sys, frame)
        if (spinSpeed > 0f) {
            // OnRenderSpecific rotates by spinAngle instead of the heading while the body spins.
            g.rotate(0f, 0f, spinAngle - Math.toDegrees(rotation.toDouble()).toFloat())
        }
        sprite.draw(-sprite.width / 2f, -sprite.height / 2f)
    }

    companion object {
        fun fakeBurstDamage(): Damage = Damage.regular(0).also {
            it.hsOwnerId = -1; it.hsSelfId = -1; it.hsFriendlyFire = true; it.friendlyFire = true
        }

        /** 0x5ca6f0: smallest positive time to meet a target at (dx, dy) moving (vx, vy) per frame with speed [s]. */
        fun interceptTime(dx: Float, dy: Float, vx: Float, vy: Float, s: Float): Float {
            val a = dx * dx + dy * dy
            var b = dx * vx + dy * vy
            b += b
            val disc = b * b - ((vx * vx + vy * vy) - s * s) * 4f * a
            if (disc > 0f) return (a + a) / (sqrt(disc) - b)
            return -1f
        }
    }
}

/** LaserBlast members (LaserBlast and its PDSFire subclass). */
interface NativeLaserBlastFields {
    var spinAngle: Float
    var spinSpeed: Float
    var movingTarget: Any?
}

/** Targetable virtuals of the bodies scripts can link (center and per-frame speed). */
object NativeTargetable {
    fun centre(target: Any): FPoint? = when (target) {
        is AbstractProjectile -> target.position
        is xyz.znix.xftl.drones.AbstractExternalDrone -> target.flightController.position
        else -> null
    }

    fun speed(target: Any): FPoint = when (target) {
        is AbstractProjectile -> target.nativeFrameSpeed
        // SpaceDrone::GetSpeed belongs to the drone bodies (agent B's surface); not modelled here.
        else -> xyz.znix.xftl.math.ConstFPoint.ZERO
    }
}
