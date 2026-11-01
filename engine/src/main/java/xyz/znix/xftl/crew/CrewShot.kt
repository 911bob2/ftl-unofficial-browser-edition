package xyz.znix.xftl.crew

import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.f
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A crew projectile (CrewLaser, constructor 0x45cab0) fired between two fighting crewmembers or at a door: a laser
 * bolt, a thrown rock, a crystal shard or a Mantis spit, depending on the shooter's race sheet (see [image]).
 *
 * It cannot be shot down and ignores shields. [onLanded] runs when it arrives, which is when the shooter rolls the
 * shot's damage (0x4881c0); the projectile then plays its impact puff until it's removed. It isn't serialised.
 * Positions are ship-relative pixel coordinates, like [AbstractCrew.getPixelPositionCentre].
 */
class CrewShot(
    val room: Room,
    start: IPoint,
    end: IPoint,
    /** The race's "crewLaser" frame (sheet cell x=1, y=0 of its base sheet, CrewAnimation::SetupStrips 0x480dc0). */
    internal val image: Image?,
    /** CrewLaser colour (CrewAnimation +0x9dc): owner green/red for laser races, white for the rest. */
    internal val colour: Colour,
    /** The impact puff (Projectile death animation: explosion_missile1, 9 frames over 0.5 s, CrewLaser scale 0.5). */
    private val impact: FTLAnimation?,
    val onLanded: (() -> Unit)? = null
) {
    // Snapshot both endpoints at spawn time. The target may keep moving for
    // a fraction of a second, but over a ~0.2s flight the error is a few
    // pixels - not worth tracking live positions (which would need care
    // around dying/dead crew). The original also flies to a fixed point.
    /** The point the projectile flies to (ship pixels). */
    internal val endX = end.x
    internal val endY = end.y
    private val startX = start.x.f
    private val startY = start.y.f
    private val distX = end.x.f - startX
    private val distY = end.y.f - startY
    private val totalDist = hypot(distX, distY)

    // CrewLaser::OnRender (0x45cc00) turns the image by the heading plus 90 degrees.
    private val angleDeg = Math.toDegrees(atan2(distY, distX).toDouble()).toFloat() + 90f

    private var travelled = 0f

    /**
     * With Hyperspace, the CrewLaser as a projectile body: HS's Projectile constructor hook fires
     * CONSTRUCT_PROJECTILE with it (the base part only). It never joins a space's projectiles (CrewAnimation owns
     * and updates it), so scripts only meet it there; its position follows the flight.
     */
    val body: CrewLaserBody? = if (xyz.znix.xftl.hs.HS.active) CrewLaserBody.create(room.ship, start, end) else null

    /** True once the projectile has reached its target point and plays its impact puff. */
    var landed: Boolean = false
        private set

    /** Advance the projectile; returns false once it can be removed (after the impact puff). */
    fun update(dt: Float): Boolean {
        if (!landed) {
            // Projectile::OnUpdate (0x458460): a step of speed * SpeedFactor towards the target, stopping on it; the
            // death animation starts (startedDeath) once the target is within one step, squared distances compared -
            // a frame before the body would reach it (native 1.6.14 bodies capture, crew laser rows).
            val step = SPEED * dt
            travelled = minOf(totalDist, travelled + step)
            val left = totalDist - travelled
            if (left * left <= step * step) landed = true
            body?.moveTo(startX + distX * progress(), startY + distY * progress())
            if (landed) onLanded?.invoke()
            return true
        }

        val puff = impact ?: return false
        puff.update(dt)
        return !puff.isStopped
    }

    /** The flight position (ship pixels), for the native replay. */
    internal val diagnosticPosition: xyz.znix.xftl.math.ConstFPoint
        get() = xyz.znix.xftl.math.ConstFPoint(startX + distX * progress(), startY + distY * progress())

    private fun progress() = if (totalDist <= 0f) 1f else (travelled / totalDist).coerceIn(0f, 1f)

    fun render(g: Graphics) {
        val progress = progress()
        val x = startX + distX * progress
        val y = startY + distY * progress

        if (landed) {
            // The death animation is drawn unrotated, centred on the point and half size.
            val puff = impact ?: return
            val w = puff.width * IMPACT_SCALE
            val h = puff.height * IMPACT_SCALE
            puff.draw(x - (w.toInt() / 2), y - (h.toInt() / 2), w, h)
            return
        }

        val img = image ?: return
        g.pushTransform()
        g.translate(x, y)
        g.rotate(0f, 0f, angleDeg)
        img.draw(-img.width / 2f, -img.height / 2f, colour)
        g.popTransform()
    }

    companion object {
        // The crew laser is a LaserBlast (constructor 0x45cab0), which sets the
        // projectile speed field to 10; projectiles move speed * 16 px/s.
        const val SPEED = 10f * 16f // px/s

        /** Projectile constructor 0x4598e0: the death animation's 9 frames last 0.5 s. */
        const val IMPACT_TIME = 0.5f

        /** CrewLaser constructor 0x45cab0 (+0xf8): the death animation is drawn at half size. */
        const val IMPACT_SCALE = 0.5f

        /** CrewAnimation::SetupStrips 0x480dc0: player crew (ship 0) green, enemy red (colours from 0x8792b0/0x8792a0). */
        val PLAYER_LASER = Colour(100f / 255f, 1f, 100f / 255f)
        val ENEMY_LASER = Colour(1f, 50f / 255f, 50f / 255f)
    }
}

/**
 * CrewLaser (constructor 0x45cab0) as a Projectile: Projectile(x, y, space 1, destination 1, target) - both spaces
 * are 1 whichever ship it is on - then speed 10 and the heading towards the target in degrees. HS's constructor hook
 * (Projectile_Extend.cpp) gives it an extend and fires CONSTRUCT_PROJECTILE before the CrewLaser part runs.
 */
class CrewLaserBody private constructor() : xyz.znix.xftl.weapons.AbstractProjectile(null) {
    override val speed: Int get() = (CrewShot.SPEED).toInt()
    override val antiDroneBP: xyz.znix.xftl.weapons.AbstractWeaponBlueprint? get() = null
    override val antiDroneExemption: xyz.znix.xftl.Ship? get() = null
    override val collisionsEnabled: Boolean get() = false
    override val serialisationType: String get() = "browserCrewLaser"
    // CrewLaser constructor +0xf8: death_animation.fScale 0.5, set after the base constructor (and so after HS's
    // CONSTRUCT_PROJECTILE, which still reads 1).
    private var constructed = false
    override val deathAnimationScale: Float get() = if (constructed) CrewShot.IMPACT_SCALE else 1f
    override fun renderPreTranslated(g: Graphics) {}
    override fun reachedTarget() {}
    override fun hitOtherProjectile(currentSpace: xyz.znix.xftl.Ship) {}

    internal fun moveTo(x: Float, y: Float) = pos.set(x, y)

    companion object {
        fun create(ship: xyz.znix.xftl.Ship, start: IPoint, end: IPoint): CrewLaserBody {
            val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(
                ConstFPoint(start.x.toFloat(), start.y.toFloat()), ConstFPoint(end.x.toFloat(), end.y.toFloat()), ship, 1, 1, 1)
            val body = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) { CrewLaserBody() }
            body.constructed = true
            body.hsExt?.speedMagnitude = 10f
            body.hsSetHeading(Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat())
            return body
        }
    }
}
