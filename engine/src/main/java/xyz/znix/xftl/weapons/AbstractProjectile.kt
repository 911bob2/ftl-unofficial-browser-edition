package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Constants
import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.AbstractDrone
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

abstract class AbstractProjectile(targetShip: Ship?, val hsNativeProjectile: Boolean = true) : IProjectile {
    /**
     * If non-null, the ship this projectile is aimed at.
     *
     * If this is set and the projectile leaves one ship's
     * space, it jumps to this ship's space. Only Hyperspace scripts change it ([hsSetTargetShip]).
     */
    var targetShip: Ship? = targetShip
        private set

    /**
     * The projectile's speed, in pixels per second.
     */
    abstract val speed: Int

    /** Hyperspace Projectile_Extend (set while Hyperspace is active; see [xyz.znix.xftl.hs.weapons.HsProjectileExt]). */
    final override var hsExt: xyz.znix.xftl.hs.weapons.HsProjectileExt? =
        if (hsNativeProjectile) xyz.znix.xftl.hs.weapons.HsProjectileExt.of(null) else null

    /** Native owner/current-space are independent of the destination ship and of the world's projectile lists. */
    var hsCurrentSpace: Ship? = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.current?.currentSpace
        ?: targetShip?.let { it.sys.getEnemyOf(it) }
        set(value) { field = value; hsCurrentSpaceId = xyz.znix.xftl.hs.weapons.HsWeapons.shipId(value) }
    var hsCurrentSpaceId: Int = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.current?.currentSpaceId
        ?: xyz.znix.xftl.hs.weapons.HsWeapons.shipId(hsCurrentSpace)
    var hsDestinationSpaceId: Int = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.current?.destinationSpaceId
        ?: xyz.znix.xftl.hs.weapons.HsWeapons.shipId(targetShip)
    var hsTargetId: Int = hsDestinationSpaceId
    var hsOwnerId: Int = hsCurrentSpaceId

    // The angle we are approaching the target at, in radians.
    // This is only used if and when we exit the space of the
    // ship that fired us, and enter the target's space.
    // Projectile::entryAngle starts at -1 (unset); EnterDestinationSpace (1.6.14 0x456650) draws
    // random32() % 360 degrees only then, so a body that never changes space draws nothing.
    private var entryAngleState: Float? = null
    var entryAngle: Float
        get() = entryAngleState ?: Math.toRadians((ftl.browser.BrowserRandom.nextBits(31) % 360).toDouble()).toFloat()
            .also { entryAngleState = it }
        set(value) { entryAngleState = value; hsExt?.entryAngle = Math.toDegrees(value.toDouble()).toFloat() }

    private var hasReachedTarget: Boolean = false
    private var hasPassedShields: Boolean = false

    /**
     * If true, this projectile will be removed on the next update.
     */
    protected var dead: Boolean = false
    /** Creation order is independent of the writable Lua selfId. */
    val worldOrder: Int = nextWorldOrder++
    /** Native last_position is needed for a shield boundary crossing in vanilla as well as HS. */
    val nativeLastPosition = MutFPoint(0f, 0f)
    /** Native Projectile's GL_Color member exists before CONSTRUCT_PROJECTILE runs. */
    private var nativeColorState: xyz.znix.xftl.hs.lua.GlColorValue? = null
    val nativeColor get() = nativeColorState ?: xyz.znix.xftl.hs.lua.GlColorValue(255f, 50f, 50f, 1f).also { nativeColorState = it }

    private var deathAnimationState: ProjectileDeathAnimation? = null
    /**
     * The animation GetAnimation(explosion) returned for death_animation, kept: `explosion_random` picks one of
     * explosion_big2-4 in that call (0x5a6450), and the body draws and plays that one, not a new pick.
     */
    private var deathSpec: xyz.znix.xftl.AnimationSpec? = null
    val deathAnimation: ProjectileDeathAnimation get() = deathAnimationState ?: ProjectileDeathAnimation().also {
        deathAnimationState = it
        it.scaleSource = { deathAnimationScale }
        // death_animation = GetAnimation(explosion): its tracker time and frame count are the animation's own.
        val found = targetShip?.sys?.animations?.getOrNull(hsExplosionName ?: deathAnimationName)
        found?.let { spec ->
            deathSpec = spec
            it.spec = spec
            if (spec.length > 0 && spec.totalTime > 0f) { it.time = spec.totalTime; it.frameCount = spec.length }
        }
        // a written explosion no animation has that name: GetAnimation gives an empty animation
        if (found == null && hsExplosionName != null && targetShip != null) { it.frameCount = 0; it.time = 0f }
    }

    /** The death animation's resolved animation ([deathSpec]), resolving it once in [space] for a body without a target. */
    protected fun deathAnimationSpec(space: Ship): xyz.znix.xftl.AnimationSpec {
        deathAnimation
        return deathSpec ?: (space.sys.animations.getOrNull(hsExplosionName ?: deathAnimationName) ?: space.sys.animations.orEmpty(deathAnimationName)).also { deathSpec = it }
    }
    val deathAnimationRunning: Boolean get() = deathAnimationState?.running == true

    /** Projectile::GetType (1.6.14 vtable slot 31): 0 base, 1 LaserBlast, 2 Asteroid, 3 Missile, 4 BombProjectile,
     * 5 BeamWeapon, 6 PDSFire. */
    open val nativeType: Int get() = if (this is BeamProjectile) 5 else 0
    /** Targetable::type from the constructors: Projectile 5 (0x4598e0), LaserBlast::OnInit 4, Missile 1, Asteroid 2. */
    @JvmField var targetableType = 5
    /** The weapon blueprint the body was initialised from, if any. */
    open val nativeBlueprint: AbstractWeaponBlueprint? get() = (this as? BeamProjectile)?.blueprint
    private var nativeFieldsState: ProjectileNativeFields? = null
    val nativeFields: ProjectileNativeFields
        get() = nativeFieldsState ?: ProjectileNativeFields().also { nativeFieldsState = it }

    /**
     * The blueprint part of Projectile::Initialize (1.6.14 0x45a430), in both engines: one name from each of the
     * hit, shield and miss sound lists, the Targetable type from `<drone_targetable>` (0 -> 4, other -> 2, absent
     * keeps it), then SetSpin(blueprint spin).
     */
    fun nativeApplyBlueprint(bp: AbstractWeaponBlueprint) {
        val fields = nativeFields
        bp.hsExplosion?.takeUnless { it.isEmpty() }?.let { hsExplosionName = it }
        hsFlightName = bp.projectile
        fields.hitSolidSound = bp.hitShipSounds?.randomName() ?: ""
        fields.hitShieldSound = bp.hitShieldSounds?.randomName() ?: ""
        fields.missSound = bp.missSounds?.randomName() ?: ""
        if (bp.droneTargetable != -1) targetableType = if (bp.droneTargetable == 0) 4 else 2
        // death_animation = GetAnimation(explosion): a fresh, finished tracker (the HS oracle reads done true on an
        // initialised body that never died; the bare constructor's, as the 1.6.14 lifetime capture shows, is not).
        if (!deathAnimationRunning) deathAnimation.done = true
        nativeSetSpin(bp.spin.toFloat())
    }
    private var flightState: ProjectileDeathAnimation? = null
    /** Hyperspace: Projectile::flight_animation assigned by a script: the shot flies with this animation. */
    @JvmField var hsFlightSpec: xyz.znix.xftl.AnimationSpec? = null
    /** The blueprint's projectile image: flight_animation's name. */
    @JvmField var hsFlightName: String? = null
    /** Projectile::flight_animation's tracker: Initialize starts it looping (SetLoop(true, 0)) on the blueprint image. */
    val flightTracker: ProjectileDeathAnimation
        get() = flightState ?: ProjectileDeathAnimation().also {
            if (flightStartsRunning) { it.loop = true; it.running = true }
            flightState = it
        }
    /** False for a body never Initialized (Asteroid), whose flight_animation stays the stopped default. */
    protected open val flightStartsRunning: Boolean get() = true
    val flightTrackerOrNull: ProjectileDeathAnimation? get() = flightState

    /** Projectile::SetSpin is empty; LaserBlast overrides it. */
    open fun nativeSetSpin(spin: Float) = Unit
    /** Projectile::SetMovingTarget is empty (1.6.14 0x755eb0); BeamWeapon keeps its own moving target. */
    open fun nativeSetMovingTarget(target: Any?) = Unit
    /** Projectile::ForceRenderLayer returns -1 (0x755ec0); HS ProjectileVTable.cpp replaces only this base body. */
    open fun nativeForceRenderLayer(): Int =
        if (xyz.znix.xftl.hs.HS.active && hsExt?.damage?.hsCrystalShard == true) 1 else -1
    /** Collideable::GetOwnerId as other bodies see it: BombProjectile returns -1 (0x761ca0). */
    open val nativeCollisionOwnerId: Int get() = hsOwnerId

    /** Projectile::StartedDeath (0x45baf0): reports startedDeath and clears it. */
    fun nativeStartedDeath(): Boolean {
        val ext = hsExt ?: return false
        val started = ext.startedDeath
        ext.startedDeath = false
        return started
    }

    /** Unlike OnUpdate, native Dead advances the impact animation. Its return is independent of startedDeath. */
    open fun nativeDead(dt: Float): Boolean {
        if (dead) return true
        val animation = deathAnimationState ?: return false
        if (!animation.running) return false
        animation.advance(dt)
        return animation.done
    }

    fun startDeathAnimation() {
        deathAnimation.start(true)
        hsExt?.startedDeath = true
    }

    private var deathImage: xyz.znix.xftl.FTLAnimation? = null
    /** death_animation.fScale (1, a fake burst piece 0.25). */
    protected open val deathAnimationScale: Float get() = 1f
    /** Bodies updated by the shared native SpaceManager order and CollisionCheck (ProjectileRuntime). */
    open val nativeBody: Boolean get() = false

    /**
     * A body that keeps its own update but, as a native Projectile, moves in SpaceManager::OnLoop's projectile loop
     * (in world order, before the ships) rather than in its ship's update ([ProjectileRuntime.advanceFrame]).
     */
    open val updatesInProjectileLoop: Boolean get() = false
    protected open val deathAnimationName: String get() = "explosion_missile1"

    /** Hyperspace: WeaponBlueprint::explosion a script wrote before this shot was made. */
    private var hsExplosionName: String? = null

    /** Bounds are tested by SpaceManager before the virtual update, including the frame of Kill/EnterSpace. */
    open fun nativeCheckBounds(space: Ship): Ship {
        if (!isOutOfBounds(space)) return space
        if (xyz.znix.xftl.hs.HS.active) {
            if (hsDestinationSpaceId == hsCurrentSpaceId) hsKill()
            else nativeEnterDestination()
            return hsCurrentSpace ?: space
        }
        if (targetShip != null && targetShip !== space) {
            space.projectiles.remove(this)
            switchToTarget()
            hsExt?.lastPosition?.set(pos)
            return targetShip!!
        }
        hsKill()
        return space
    }

    /** Native 1.6.9 EnterDestinationSpace 0x44edc0. SetDestinationSpace does not change targetId or target. */
    open fun nativeEnterDestination() {
        val game = targetShip?.sys ?: hsCurrentSpace?.sys ?: return
        val destination = when (hsDestinationSpaceId) { 0 -> game.player; 1 -> game.enemy; else -> null }
        game.player.projectiles.remove(this); game.enemy?.projectiles?.remove(this)
        // The lists are storage only; the numeric currentSpace still retains an unknown scripted space id.
        (destination ?: game.player).projectiles.add(this)
        hsCurrentSpace = destination; hsCurrentSpaceId = hsDestinationSpaceId
        val angle = hsExt?.entryAngle ?: Math.toDegrees(entryAngle.toDouble()).toFloat()
        val degrees = if (angle == -1f) (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 360).toFloat() else angle
        val radians = Math.toRadians(degrees.toDouble()).toFloat()
        pos.set(200f + cos(radians) * 600f, 200f + sin(radians) * 600f)
        if (destination != null) {
            targetShip = destination
            if (this is AbstractWeaponProjectile)
                target = destination.rooms.firstOrNull { it.containsShipSpace(targetPos) } ?: destination.rooms.first()
        }
        hasSwitched = true
        hsComputeHeading()
        hsExt?.lastPosition?.set(pos)
        onSwitchedToTarget()
    }

    /** Native endpoint-only test (1.6.9 0x44ebf0, 1.6.14 0x4564e0); the receiver starts dying before
     * PROJECTILE_COLLISION. Preemption preserves the receiver; a changed response preserves the caller. */
    open fun nativeCollisionMoving(finish: FPoint, damage: Damage): ProjectileCollisionResponse {
        val response = ProjectileCollisionResponse()
        if (!deathAnimationRunning && IProjectile.endpointContact(finish, position)) {
            response.type = 1
            startDeathAnimation()
            if (xyz.znix.xftl.hs.HS.active &&
                xyz.znix.xftl.hs.weapons.HsWeaponHooks.projectileCollision(this, damage, response)) {
                deathAnimation.running = false
                hsExt?.startedDeath = false
            }
        }
        return response
    }

    /** Native CollisionCheck consumes the mutable result after the receiver's CollisionMoving hook. */
    fun nativeApplyCollision(response: ProjectileCollisionResponse, damage: Damage) {
        when (response.type) {
            1 -> { startDeathAnimation(); hsExt?.missed = true }
            2 -> {
                hsExt?.hitTarget = true
                if (damage.hsShieldPiercing < response.damage || response.superDamage > 0) {
                    startDeathAnimation(); hsExt?.missed = true
                }
            }
            3 -> hsExt?.missed = true
        }
    }

    // Helper for maths
    val Float.squared get() = this * this

    // The position this projectile is flying towards.
    private val targetPoint = MutFPoint(0f, 0f)
    var targetPos: IPoint
        get() = targetPoint
        set(value) { if (value is FPoint) targetPoint.set(value) else targetPoint.set(value) }

    // The rotation of this projectile.
    // In radians, where 0 is pointing right.
    // Note this can't be trivially computed from the current
    // and target positions - if we miss, for example, that
    // would point in the wrong direction.
    var rotation: Float = 0f
        private set

    // The position of this projectile, whether it's inside
    // the player or enemy ship space.
    protected val pos = MutFPoint(0f, 0f)

    override val position: FPoint get() = pos

    // This is continually updated from the speed variable.
    protected val vel = MutFPoint(0f, 0f)
    override val velocity: FPoint get() = vel

    init {
        val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.current
        if (context != null) {
            pos.set(context.position); targetPos = context.target
            if (hsCurrentSpaceId != xyz.znix.xftl.hs.weapons.HsWeapons.shipId(hsCurrentSpace)) {
                val id = hsCurrentSpaceId; hsCurrentSpace = null; hsCurrentSpaceId = id
            }
        }
        if (xyz.znix.xftl.hs.HS.active && hsNativeProjectile) {
            hsExt!!.spaceKnown = context != null
            hsExt!!.orig = this
            hsExt!!.lastPosition.set(pos)
            hsExt!!.constructing = true
            try { xyz.znix.xftl.hs.weapons.HsWeaponHooks.constructProjectile(this) }
            finally { hsExt!!.constructing = false }
        }
    }

    /** Called after derived construction, matching SpaceManager's OnInit/Initialize sequence. */
    fun hsInitialize(blueprint: AbstractWeaponBlueprint, defaultSpeed: Int) {
        val color = blueprint.nativeColor
        nativeColor.r = color.r; nativeColor.g = color.g; nativeColor.b = color.b; nativeColor.a = color.a
        nativeApplyBlueprint(blueprint)
        val state = hsExt ?: return
        hsOwnerId = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.current?.ownerId ?: hsOwnerId
        state.damage.copyFrom(Damage(blueprint))
        // Initialize only overwrites a nonzero blueprint speed. The derived OnInit supplies the initial default;
        // calling Initialize again preserves a scripted speed when the blueprint's native speed is zero.
        state.speedMagnitude = blueprint.speed?.takeIf { it != 0 }?.toFloat()
            ?: if (state.initialized) state.speedMagnitude else defaultSpeed.toFloat()
        state.initialized = true
        xyz.znix.xftl.hs.weapons.HsWeaponHooks.projectileInitialize(this, blueprint)
    }

    override fun update(dt: Float, currentSpace: Ship) {
        if (!xyz.znix.xftl.hs.HS.active || hsExt?.spaceKnown != true) {
            hsCurrentSpace = currentSpace
            hsExt?.spaceKnown = true
        }
        if (xyz.znix.xftl.hs.HS.active && hsNativeProjectile) {
            if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this) && nativeDead(dt)) {
                currentSpace.projectiles.remove(this)
                return
            }
            xyz.znix.xftl.weapons.ProjectileRuntime.update(this, dt, currentSpace)
            return
        }
        if (deathAnimationRunning) {
            if (nativeDead(dt)) currentSpace.projectiles.remove(this)
            return
        }
        if (dead) {
            currentSpace.projectiles.remove(this)
            return
        }

        // Save restoration performs zero-time ship updates. A projectile at
        // its target must not divide a zero displacement by zero or resolve
        // an impact before simulation resumes.
        if (dt <= 0f) return

        updateMovement(dt)
        resolveAfterMovement(nativeAtTarget(dt), currentSpace)
    }

    var hsDead: Boolean get() = dead; set(value) { dead = value }

    /** Native Projectile/LaserBlast::OnUpdate: movement only, separate from CollisionCheck. */
    fun hsOnUpdate(dt: Float) {
        val state = hsExt ?: return
        state.sourceShipId = hsOwnerId
        xyz.znix.xftl.hs.weapons.HsDamage.with(state, this) {
            val preempt = xyz.znix.xftl.hs.weapons.HsWeaponHooks.projectileBefore(
                xyz.znix.xftl.hs.lua.LuaApi.EV_PROJECTILE_UPDATE_PRE, this)
            if (!preempt) nativeStep(dt)
            xyz.znix.xftl.hs.weapons.HsWeaponHooks.projectileAfter(
                xyz.znix.xftl.hs.lua.LuaApi.EV_PROJECTILE_UPDATE_POST, this, preempt)
        }
    }

    protected open fun nativeStep(dt: Float) {
        if (!deathAnimationRunning) {
            val state = hsExt
            state?.lastPosition?.set(pos)
            updateMovement(dt)
            if (state != null) {
                state.frameSpeed.set(pos.xf - state.lastPosition.xf, pos.yf - state.lastPosition.yf)
                state.passedTarget = hasReachedTarget
            }
        }
        nativeAfterMotion(dt)
    }

    /** The derived part of a virtual OnUpdate after the shared Projectile motion (runs while dying too). */
    protected open fun nativeAfterMotion(dt: Float) = Unit

    /** Projectile::speed: the last frame's displacement (Targetable::GetSpeed). */
    val nativeFrameSpeed: FPoint
        get() = hsExt?.frameSpeed ?: ConstFPoint(pos.xf - nativeLastPosition.xf, pos.yf - nativeLastPosition.yf)

    open fun hsResolveShipCollision(currentSpace: Ship, dt: Float) = resolveAfterMovement(nativeAtTarget(dt), currentSpace)

    /** Shared native movement/collision operation without HS callbacks or compatibility allocations. */
    open fun nativeUpdate(dt: Float, space: Ship) {
        if (!deathAnimationRunning) {
            updateMovement(dt)
            nativeAfterMotion(dt)
            resolveAfterMovement(nativeAtTarget(dt), space)
        } else nativeAfterMotion(dt)
    }

    private fun resolveAfterMovement(hitRoom: Boolean, currentSpace: Ship) {
        val isDeparting = targetShip != null && targetShip != currentSpace
        // Native SpaceManager::UpdateProjectile does not call CollisionCheck against the owner's ship.
        val checkShip = if (xyz.znix.xftl.hs.HS.active)
            hsOwnerId != xyz.znix.xftl.hs.weapons.HsWeapons.shipId(currentSpace)
        else !xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this) || antiDroneExemption !== currentSpace

        // If we're in the target ship's space, check if we're now inside its shields.
        // This must happen after updateMovement, to prevent the projectile
        // from bypassing shields with high frametimes.
        if (checkShip && !isDeparting && this is AbstractWeaponProjectile && ProjectileRuntime.nativeMotion(this)) {
            ProjectileRuntime.collisionCheck(this, currentSpace)
        } else if (checkShip && !hasPassedShields && !isDeparting) {
            val rel = Point(position)
            rel -= currentSpace.shieldOrigin

            val shieldSize = currentSpace.shieldHalfSize

            if (rel.x.f.squared / shieldSize.x.f.squared + rel.y.f.squared / shieldSize.y.f.squared < 1) {
                hasPassedShields = true
                crossedShieldLine()
            }
        }

        if (checkShip && hitRoom) {
            reachedTarget()
        }

        // Check if we're out-of-bounds. If so, either switch to the target
        // ship (if we're a departing projectile), or destroy ourselves.
        // See doc/projectiles
        if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this) && isOutOfBounds(currentSpace)) {
            currentSpace.projectiles.remove(this)

            // Are we a departing projectile? If so, switch ourselves to
            // the ship we're aimed at.
            if (targetShip != null && currentSpace != targetShip) {
                switchToTarget()
            }
        }
    }

    override fun render(g: Graphics, currentSpace: Ship) {
        if (deathAnimationRunning) {
            val image = deathImage ?: deathAnimationSpec(currentSpace).startSingle(currentSpace.sys)
                .also { deathImage = it }
            image.duration = deathAnimation.time
            image.timer = deathAnimation.currentTime
            val scale = deathAnimation.scale
            // Animation::OnRender at the body's position less half the scaled frame, truncated to whole pixels.
            val w = image.width * scale; val h = image.height * scale
            image.draw(position.xf - (w.toInt() / 2), position.yf - (h.toInt() / 2), w, h)
            return
        }
        g.pushTransform()
        g.translate(position.x.f, position.y.f)
        g.rotate(0f, 0f, Math.toDegrees(rotation.toDouble()).toFloat())

        renderPreTranslated(g)

        if (currentSpace.sys.debugFlags.showProjectileHitboxes.set) {
            val r = hitboxRadius.f
            g.colour = Colour.red
            g.drawOval(-r, -r, r * 2, r * 2)
        }

        g.popTransform()
    }

    protected abstract fun renderPreTranslated(g: Graphics)

    /**
     * Update the position, and return true if we hit
     * the target room (this isn't set if missed=true).
     */
    private fun updateMovement(dt: Float): Boolean {
        nativeLastPosition.set(pos)
        // Make sure we're consistent if a subclass does something silly
        // with their speed, so we don't use different speeds for x/y.
        val currentSpeed = nativeSpeedPerSecond

        // Update our position. If we've missed, use the rotation to
        // find the correct path. Otherwise use the difference in
        // position to make sure we're always properly aligned.
        if (hasReachedTarget || (xyz.znix.xftl.hs.HS.active && hsCurrentSpaceId != hsDestinationSpaceId)) {
            vel.xf = cos(rotation) * currentSpeed
            vel.yf = sin(rotation) * currentSpeed

            pos.xf += vel.xf * dt
            pos.yf += vel.yf * dt
            return false
        }

        val deltaX = (if (targetPos is FPoint) (targetPos as FPoint).xf else targetPos.x.toFloat()) - pos.xf
        val deltaY = (if (targetPos is FPoint) (targetPos as FPoint).yf else targetPos.y.toFloat()) - pos.yf

        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)

        // Would we overshoot the target position?
        if (distance == 0f || distance <= currentSpeed * dt) {
            if (targetPos is FPoint) pos.set(targetPos as FPoint) else pos.set(targetPos)
            hasReachedTarget = true
            return true
        }

        // Find the unit-vector of the direction we have to travel in.
        val unitX = deltaX / distance
        val unitY = deltaY / distance

        vel.xf = unitX * currentSpeed
        vel.yf = unitY * currentSpeed

        pos.xf += vel.xf * dt
        pos.yf += vel.yf * dt

        return false
    }

    /** Projectile::speed (the float at +0x20) in px/s: the HS state's, else the 16x integer [speed]. */
    protected open val nativeSpeedPerSecond: Float
        get() = hsExt?.takeIf { it.initialized }?.speedMagnitude?.times(16f) ?: speed.toFloat()

    /** Native OnUpdate's motion seam, also used by the 1.6.14 capture replay (no collision or world effects). */
    internal fun nativeMotionStep(dt: Float) { if (!deathAnimationRunning) updateMovement(dt) }
    internal var nativePassedTarget: Boolean
        get() = hasReachedTarget
        set(value) { hasReachedTarget = value; hsExt?.passedTarget = value }

    fun nativeSetTarget(point: FPoint) {
        targetPos = point
        if (hsCurrentSpaceId == hsDestinationSpaceId) hsComputeHeading()
    }

    fun nativeAtTarget(dt: Float): Boolean {
        if (hsCurrentSpaceId != hsDestinationSpaceId || hsExt?.missed == true) return false
        val x = targetPoint.xf - pos.xf; val y = targetPoint.yf - pos.yf
        val step = nativeSpeedPerSecond * dt
        return x * x + y * y <= step * step
    }

    /**
     * Called once when the projectile reaches the target room.
     */
    protected abstract fun reachedTarget()

    /**
     * Called once when the projectile crosses the enemy shields,
     * regardless of whether they're up (or even turned on) or not.
     */
    protected open fun crossedShieldLine() {
    }

    /**
     * Called when this projectile exits the space of one ship
     * and enters that of its target.
     */
    /** Whether the projectile has flown out of [currentSpace]'s area (see doc/projectiles). */
    protected open fun isOutOfBounds(currentSpace: Ship): Boolean {
        return pos.xf < -800 || pos.xf > 800 || pos.yf < -800 || pos.yf > 800
    }

    protected open fun onSwitchedToTarget() {
    }

    /**
     * Calculate the position on the enemy ship this projectile
     * should fly towards.
     *
     * This is called after the projectile has swapped from
     * the shooter's ship to the target's ship.
     */
    open fun calculateTargetPosition(): IPoint {
        error("Cannot cross ship spaces without implementing calculateTargetPosition")
    }

    private fun switchToTarget() {
        targetShip!!.projectiles.add(this)
        hsCurrentSpace = targetShip
        hasSwitched = true

        // Jump into the outside of the enemy ship space.
        // See doc/projectiles for this logic.

        val radius = (800 * 0.75).toInt()

        pos.xf = 200 + cos(entryAngle) * radius
        pos.yf = 200 + sin(entryAngle) * radius

        targetPos = calculateTargetPosition()

        setRotationFromTarget()

        onSwitchedToTarget()
    }

    /** [AbstractWeaponProjectile.hsDamageArea]'s body for any projectile, with its damage given (PDSFire's CollisionCheck). */
    fun hsDamageAreaWith(receiver: Ship, room: Room?, location: IPoint, forceHit: Boolean, damage: Damage,
                         missDeadEnemies: Boolean, appliesAreaDamage: Boolean): Boolean {
        return xyz.znix.xftl.hs.weapons.HsDamage.with(hsExt, this) {
            var force = forceHit
            var result: Boolean? = null
            if (xyz.znix.xftl.hs.HS.luaActive) {
                val event = xyz.znix.xftl.hs.weapons.HsWeaponsLua.damageAreaEvent(receiver, this, location, damage, forceHit)
                val evasion = event and 3
                if (event and xyz.znix.xftl.hs.weapons.HsWeaponsLua.PREEMPTED != 0) result = when (evasion) {
                    1 -> true
                    2 -> false
                    else -> receiver.pickMissed(0, missDeadEnemies)
                } else if (evasion == 2) {
                    if (!receiver.browserJumping) receiver.showDamageTextAt(location, "text_miss", Colour.white)
                    result = false
                } else force = evasion == 1
            }
            // CustomShips.cpp ShipManager::DamageArea: the room's hull resist (A's BeamDamageRules.hsHullResist).
            var dmg = damage
            var resist = false
            if (result == null && room != null && damage.hullDamage > 0 && xyz.znix.xftl.hs.HS.active) {
                val copy = damage.copy()
                resist = xyz.znix.xftl.weapons.BeamDamageRules.hsHullResist(room, copy)
                if (resist) dmg = copy
            }
            if (result == null && room == null) result = false
            if (result == null && (receiver.browserJumping || !force) && receiver.pickMissed(0, missDeadEnemies)) {
                receiver.sys.browserAchievements.damageDodged(receiver, damage.hullDamage)
                if (!receiver.browserJumping) receiver.showDamageTextAt(location, "text_miss", Colour.white)
                result = false
            }
            if (result == null) {
                if (appliesAreaDamage) receiver.damage(room!!, dmg)
                // custom->sourceShipId is the projectile's ownerId (UpdateProjectile hook), with or without that ship
                xyz.znix.xftl.hs.weapons.HsDamage.areaHit(receiver, room, location, hsOwnerId)
                result = !(xyz.znix.xftl.hs.HS.luaActive &&
                    xyz.znix.xftl.hs.weapons.HsWeaponsLua.damageAreaHit(receiver, this, location, dmg))
            }
            if (resist && result == true) receiver.showDamageTextAt(room!!.pixelCentre, "text_resist", Colour.white)
            result
        }
    }

    /** Hyperspace Projectile::Kill from a script: the projectile is removed on its next update. */
    fun hsKill() {
        dead = true
    }

    /**
     * Hyperspace `projectile.destinationSpace = id` / `targetId = id` before the projectile reached its target
     * space (FTL keeps both on the Projectile): aim at [ship]. When that is the space the projectile is in, it is
     * in its destination space already and heads for its target there.
     */
    fun hsSetTargetShip(ship: Ship, currentSpace: Ship?) {
        targetShip = ship
        if (ship === currentSpace) {
            hasSwitched = true
            // Its own ship's shields are not crossed (assumed, not checked against FTL's shield collision).
            hasPassedShields = true
            targetPos = calculateTargetPosition()
            setRotationFromTarget()
        }
    }

    /** Hyperspace: has the projectile entered the space of the ship it is aimed at (or never had to)? */
    val hsInTargetSpace: Boolean get() = hasSwitched || targetShip == null

    private var hasSwitched = false

    fun setInitialPath(initialPos: IPoint, targetPos: IPoint) {
        if (initialPos is FPoint) pos.set(initialPos) else pos.set(initialPos)

        this.targetPos = targetPos

        setRotationFromTarget()
    }

    private fun setRotationFromTarget() {
        val tx = if (targetPos is FPoint) (targetPos as FPoint).xf else targetPos.x.toFloat()
        val ty = if (targetPos is FPoint) (targetPos as FPoint).yf else targetPos.y.toFloat()
        rotation = atan2(ty - pos.yf, tx - pos.xf)
    }

    fun hsSetHeading(degrees: Float) { rotation = Math.toRadians(degrees.toDouble()).toFloat() }
    fun hsComputeHeading() = setRotationFromTarget()

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        SaveUtil.addAttrFloat(elem, "x", pos.xf)
        SaveUtil.addAttrFloat(elem, "y", pos.yf)

        entryAngleState?.let { SaveUtil.addAttrFloat(elem, "entryAngle", it) }
        SaveUtil.addAttrFloat(elem, "rotation", rotation)

        SaveUtil.addTagBoolIfTrue(elem, "reachedTarget", hasReachedTarget)
        SaveUtil.addTagBoolIfTrue(elem, "passedShields", hasPassedShields)

        SaveUtil.addPoint(elem, "targetPos", targetPos)

        // Just in case this gets set on the last update before serialisation,
        // after our update function was run.
        SaveUtil.addTagBoolIfTrue(elem, "dead", dead)
        deathAnimationState?.let { animation ->
            val node = Element("deathAnimation")
            SaveUtil.addAttrFloat(node, "time", animation.time)
            SaveUtil.addAttrFloat(node, "current", animation.currentTime)
            SaveUtil.addTagBool(node, "running", animation.running)
            SaveUtil.addTagBool(node, "done", animation.done)
            SaveUtil.addTagBool(node, "loop", animation.loop)
            SaveUtil.addTagBool(node, "reverse", animation.reverse)
            elem.addContent(node)
        }
        hsExt?.let {
            xyz.znix.xftl.hs.weapons.HsProjectileExt.save(it, elem)
            // Projectile::ownerId, destinationSpace and targetId survive a save (oracle hs-gaps proj1 / proj2); the
            // current space is the ship whose list the projectile is loaded into
            elem.getChild("hsExt")?.let { n ->
                n.setAttribute("owner", hsOwnerId.toString()); n.setAttribute("dest", hsDestinationSpaceId.toString())
                n.setAttribute("targetId", hsTargetId.toString())
            }
        }
    }

    /**
     * Load back in all the parameters we saved in [saveToXML].
     *
     * Note this is different to [IProjectile.loadFromXML], as it only updates
     * the properties of a previously-created instance, rather than creating
     * a new one.
     */
    open fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        pos.xf = SaveUtil.getAttrFloat(elem, "x")
        pos.yf = SaveUtil.getAttrFloat(elem, "y")

        elem.getAttributeValue("entryAngle")?.toFloatOrNull()?.let { entryAngle = it }
        rotation = SaveUtil.getAttrFloat(elem, "rotation")

        hasReachedTarget = SaveUtil.getOptionalTagBool(elem, "reachedTarget") ?: false
        hasPassedShields = SaveUtil.getOptionalTagBool(elem, "passedShields") ?: false

        targetPos = SaveUtil.getPoint(elem, "targetPos")

        // Just in case this gets set on the last update before serialisation,
        // after our update function was run.
        dead = SaveUtil.getOptionalTagBool(elem, "dead") ?: false
        elem.getChild("deathAnimation")?.let { node ->
            deathAnimation.time = SaveUtil.getAttrFloat(node, "time")
            deathAnimation.currentTime = SaveUtil.getAttrFloat(node, "current")
            deathAnimation.running = SaveUtil.getOptionalTagBool(node, "running") ?: false
            deathAnimation.done = SaveUtil.getOptionalTagBool(node, "done") ?: false
            deathAnimation.loop = SaveUtil.getOptionalTagBool(node, "loop") ?: false
            deathAnimation.reverse = SaveUtil.getOptionalTagBool(node, "reverse") ?: false
        }
        hsExt?.let {
            xyz.znix.xftl.hs.weapons.HsProjectileExt.load(it, elem)
            elem.getChild("hsExt")?.let { n ->
                n.getAttributeValue("owner")?.toIntOrNull()?.let { v -> hsOwnerId = v }
                n.getAttributeValue("dest")?.toIntOrNull()?.let { v -> hsDestinationSpaceId = v }
                n.getAttributeValue("targetId")?.toIntOrNull()?.let { v -> hsTargetId = v }
            }
        }
    }

    companion object { private var nextWorldOrder = 0 }
}

abstract class AbstractWeaponProjectile(val type: AbstractWeaponBlueprint, target: Room) :
    AbstractProjectile(target.ship) {

    /** The room aimed at. Hyperspace scripts may move it within the target ship (`projectile.target`). */
    var target: Room = target

    /**
     * See [Ship.pickMissed] for details.
     */
    protected open val missDeadEnemies: Boolean get() = true

    override val serialisationType: String get() = SERIALISATION_TYPE

    /**
     * The default speed for this projectile, if it's not set in the blueprint.
     *
     * Note this speed is given in pixels-per-second, divided by 16.
     * We have to multiply it by 16, since it's based on the SpeedFactor thing.
     * See doc/reveng-general.md for notes on this.
     */
    abstract val defaultSpeed: Int

    @Suppress("LeakingThis")
    override val speed = (type.speed ?: defaultSpeed) * 16

    // When we're leaving the ship that fired this weapon, draw underneath it.
    override var drawUnderShip: Boolean = true
        get() = if (xyz.znix.xftl.hs.HS.active && hsExt?.damage?.hsCrystalShard == true) false else field

    override val deathAnimationName: String get() = type.hsExplosion?.takeUnless { it.isEmpty() } ?: type.explosion ?: "explosion_missile1"

    private fun finishImpact() {
        if (xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) startDeathAnimation() else dead = true
    }

    override val antiDroneBP: AbstractWeaponBlueprint get() = type
    override val nativeBlueprint: AbstractWeaponBlueprint get() = type
    override val antiDroneExemption: Ship? get() = if (hsNeutral) null else hsOwner ?: ship.sys.getEnemyOf(ship)

    /** Hyperspace: the firing ship, kept once a script aimed the projectile at another ship (else the target's enemy). */
    var hsOwner: Ship? = null
    /** HS: Projectile::ownerId = -1 (a crystal shard without crystalShardFix): every defence drone may shoot it. */
    @JvmField var hsNeutral = false
    /** HS: Damage::crystalShard of the shots this projectile deals (custom crystal shards). */
    @JvmField var hsCrystalShard = false

    private val defaultMissSound = target.ship.sys.sounds.getSample("miss")

    init {
        // Hyperspace Projectile::Initialize(bp): the projectile's extend name, custom damage and accuracy.
        if (xyz.znix.xftl.hs.HS.active) {
            if (hsInitializesInConstructor) hsExt!!.initialize(type)
            hsExt!!.damage.copyFrom(Damage(type))
        }
    }

    /** False for a body native code never Initializes (Asteroid: extend name "" and the default definition). */
    protected open val hsInitializesInConstructor: Boolean get() = true

    private var missed: Boolean? = null
    /** Diagnostics (native 1.6.14 replay): a forced hit (DamageArea forceHit), and whether the shot missed. */
    internal fun diagnosticForceHit() { missed = false }
    internal fun diagnosticMissed(): Boolean = missed == true

    // Used by defence drones.
    var firedByDrone: Boolean = false

    // Used by the chain ion
    var chainDamage: Int = 0

    val ship: Ship get() = target.ship

    internal fun nativeShieldDodged(space: Ship): Boolean =
        space.pickMissed(if (xyz.znix.xftl.hs.HS.active) 0 else -type.accuracyModifier, missDeadEnemies)

    /** Consume the real native response, separately from the receiver's damage copy and callbacks. */
    internal fun applyNativeShieldResponse(response: ProjectileCollisionResponse, damage: Damage) {
        nativeApplyCollision(response, damage)
        if (response.type == 2) {
            missed = false // Native hitTarget forces the later hull contact without another dodge roll.
            if (deathAnimationRunning) type.hitShieldSounds?.get()?.play()
        } else if (response.type == 3) {
            missed = true
            ship.sys.browserAchievements.damageDodged(ship, damage.hullDamage)
            (type.missSounds?.get() ?: defaultMissSound).play()
        }
    }

    override fun reachedTarget() {
        // First check if we're already dead. With large enough
        // timesteps, the projectile can go from outside the
        // shields to it's target in a single frame. This prevents that
        // from happening, since dead is set when we hit the shields.
        if (dead || deathAnimationRunning) {
            return
        }

        // ShipManager::DamageArea (1.6.14 0x4a6100) looks the room up before its dodge roll and returns false
        // without one when the point is in no room; Projectile::CollisionCheck (0x459550) then asks
        // ValidTargetLocation, which a ship never overrides (Collideable: true): the shot misses and flies on.
        val space = if (xyz.znix.xftl.hs.HS.active) hsCurrentSpace ?: return else ship
        val room = space.rooms.firstOrNull { it.containsShipSpace(targetPos) }
        if (xyz.znix.xftl.hs.HS.active) {
            hsReachTarget(space, room)
            return
        }
        if (room == null) {
            missed = true
            (type.missSounds?.get() ?: defaultMissSound).play()
            return
        }

        resolveMissed()

        if (missed == true)
            return

        hitHull()

        finishImpact()
    }

    override fun crossedShieldLine() {
        // We're inside the shield!

        val activeShields = ship.shields?.activeShields ?: 0
        if (activeShields == 0 && ship.superShield == 0)
            return

        resolveMissed()

        if (missed == true)
            return

        hsExt?.hitTarget = true

        val damage = computeDamage()

        // Shields::CollisionReal (0x6336f0) + Projectile::CollisionCheck (0x459550): a super shield takes hull damage
        // plus twice the ion damage (Reverse Ion Field rolled first); the shot is stopped only when the super shield
        // absorbed something, so an ion shot resisted by ION_ARMOR or a hit with no hull/ion damage still passes when
        // the normal layers don't stop it.
        superShieldIonRoll = null
        if (ship.superShield > 0) {
            val ionNegated = damage.ionDamage > 0 && ship.ionArmourNegates()
            val absorbed = (if (ionNegated) 0 else max(damage.ionDamage, 0)) * 2 + max(damage.hullDamage, 0)
            if (absorbed == 0 && type.shieldPiercing >= activeShields) {
                if (ionNegated)
                    ship.showDamageTextAt(position, "text_resist", Constants.DAMAGE_COLOUR_ION)
                return
            }
            superShieldIonRoll = ionNegated
            hitShields()
            superShieldIonRoll = null
            finishImpact()
            return
        }

        // Check for shield piercing, which seems to work the same
        // way across all weapons. Missiles for example just have
        // a very high shieldPiercing of 5.
        if (type.shieldPiercing >= activeShields) {
            // Projectiles with ion and shield piercing do their ion damage twice:
            // once as they pass the shields layer (ionising the shields system),
            // and again when they hit their target room (ionising that).
            // The same thing applies for system damage, surprisingly
            // enough (though it's only applied when there's ion damage).
            ship.attackShieldsIon(damage)
            return
        }

        hitShields()

        finishImpact()
    }

    override fun onSwitchedToTarget() {
        super.onSwitchedToTarget()

        // Now we're in the target ship's space, draw on top of the ship.
        drawUnderShip = false
    }

    override fun calculateTargetPosition(): IPoint {
        // Aim for the centre of the target room.
        return target.pixelCentre
    }

    /** The Reverse Ion Field roll already made against a super shield for the shot now hitting it. */
    protected var superShieldIonRoll: Boolean? = null

    protected open fun hitShields() {
        if (xyz.znix.xftl.hs.HS.active) {
            xyz.znix.xftl.hs.weapons.HsDamage.with(hsExt, this) {
                val damage = computeDamage()
                ship.attackShields(damage, position, superShieldIonRoll)
                // Hyperspace Shields::CollisionReal (ErosionEffect.cpp): erodeShields erosion of the shield room.
                xyz.znix.xftl.hs.weapons.HsDamage.shieldHit(ship, false)
                // Hyperspace ShipManager::CollisionShield: SHIELD_COLLISION after a shield (type 2) collision.
                if (xyz.znix.xftl.hs.HS.luaActive) xyz.znix.xftl.hs.weapons.HsWeaponsLua.shieldCollision(ship, this, damage, Point(position))
            }
            if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) ship.playCentredAnimation(deathAnimationSpec(ship), position)
            type.hitShieldSounds?.get()?.play()
            return
        }
        ship.attackShields(computeDamage(), position, superShieldIonRoll)

        if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) ship.playCentredAnimation(deathAnimationSpec(ship), position)
        type.hitShieldSounds?.get()?.play()
    }

    override fun hitOtherProjectile(currentSpace: Ship) {
        currentSpace.playCentredAnimation(deathAnimationSpec(currentSpace), position)
    }

    open fun computeDamage(): Damage {
        val damage = if (xyz.znix.xftl.hs.HS.active) hsExt!!.damage.copy() else Damage(type)
        damage.applyWeaponChaining(chainDamage)

        // Damage.ownerId: the ship that fired this - the other one in the fight.
        damage.ownerShip = hsOwner ?: ship.sys.getEnemyOf(ship)
        if (hsCrystalShard) damage.hsCrystalShard = true
        return damage
    }

    /**
     * HS Projectile::CollisionCheck at the target (1.6.14 0x459550): DamageArea(target, damage, forceHit = hitTarget).
     * A hit plays hitSolidSound and starts the death; otherwise ValidTargetLocation, which ShipManager inherits from
     * Collideable (always true; the HS oracle's shot at a point outside every room misses), so the shot plays
     * missSound and flies on, marked missed.
     */
    private fun hsReachTarget(space: Ship, room: Room?) {
        if (room != null) target = room
        val hullBefore = space.health
        if (hsDamageArea(space, room, targetPos, missed == false)) {
            hsHullHit(space, hullBefore)
            if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) space.playCentredAnimation(deathAnimationSpec(space), target.pixelCentre)
            type.hitShipSounds?.get()?.play()
            finishImpact()
            return
        }
        missed = true
        hsExt?.missed = true
        (type.missSounds?.get() ?: defaultMissSound).play()
    }

    /** Hyperspace reached the target without [hitHull]: what a body does with the hit besides the damage. */
    protected open fun hsHullHit(space: Ship, hullBefore: Int) {}

    /** Whether ShipManager::DamageArea applies this body's damage to the room (a fake burst piece's is all zero). */
    protected open val hsAppliesAreaDamage: Boolean get() = true

    protected open fun hitHull() {
        if (xyz.znix.xftl.hs.HS.active) {
            hsDamageArea(ship, target, targetPos, false)
            if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) ship.playCentredAnimation(deathAnimationSpec(ship), target.pixelCentre)
            type.hitShipSounds?.get()?.play()
            return
        }
        ship.damage(target, computeDamage(), hitPos = targetPos)
        if (!xyz.znix.xftl.weapons.ProjectileRuntime.nativeMotion(this)) ship.playCentredAnimation(deathAnimationSpec(ship), target.pixelCentre)
        type.hitShipSounds?.get()?.play()
    }

    /**
     * Hyperspace ShipManager::DamageArea(location, damage, forceHit) for this projectile, with its damage current
     * (HS CustomDamage.cpp hook, native 1.6.14 0x4a6100). DAMAGE_AREA runs first: a pre-empt returns HIT -> true,
     * MISS -> false, otherwise GetDodged(), all without damage; a MISS without pre-empt shows the miss and returns
     * false; HIT forces the hit. Natively the point must be in a room (else false, no roll), then a jumping ship or
     * an unforced hit rolls the dodge (a miss shows MISS unless jumping). The damage is applied, then the custom
     * damage effects and DAMAGE_AREA_HIT, whose pre-empt reports a miss.
     */
    protected fun hsDamageArea(receiver: Ship, room: Room?, location: IPoint, forceHit: Boolean): Boolean =
        hsDamageAreaWith(receiver, room, location, forceHit, computeDamage(), missDeadEnemies, hsAppliesAreaDamage)

    private fun resolveMissed() {
        // DamageArea's forceHit (hitTarget, after a shield hit) skips the dodge roll, except on a jumping ship.
        if (missed == false && target.ship.browserJumping) missed = null
        if (missed != null)
            return

        // Native GetDodgeFactor owns the HS modifier. Scope legacy bodies too; the shared native loop
        // already supplies this context, and a nested call restores its caller when it returns.
        missed = if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.weapons.HsDamage.with(hsExt, this) {
            target.ship.pickMissed(0, missDeadEnemies)
        } else target.ship.pickMissed(-type.accuracyModifier, missDeadEnemies)

        if (missed == true) {
            target.ship.sys.browserAchievements.damageDodged(target.ship, computeDamage().hullDamage)
            val missSound = type.missSounds?.get() ?: defaultMissSound
            missSound.play()

            target.ship.showDamageTextAt(position, "text_miss", Colour.white)

            return
        }
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)

        // Used to figure out what blueprint creates the projectile instance
        // during deserialisation.
        SaveUtil.addAttr(elem, "launcher", type.name)

        SaveUtil.addTagBoolIfTrue(elem, "drawUnderShip", drawUnderShip)
        SaveUtil.addTagBoolIfTrue(elem, "firedByDrone", firedByDrone)
        SaveUtil.addTagInt(elem, "chainDamage", chainDamage, 0)

        if (missed != null) {
            SaveUtil.addTagBool(elem, "missed", missed!!)
        }
    }

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)

        drawUnderShip = SaveUtil.getOptionalTagBool(elem, "drawUnderShip") ?: false
        firedByDrone = SaveUtil.getOptionalTagBool(elem, "firedByDrone") ?: false
        chainDamage = SaveUtil.getOptionalTagInt(elem, "chainDamage") ?: 0

        missed = SaveUtil.getOptionalTagBool(elem, "missed")
    }

    companion object {
        fun loadFromXML(game: InGameState, elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            val launcher = SaveUtil.getAttr(elem, "launcher")
            val type = game.blueprintManager[launcher] as AbstractWeaponBlueprint
            type.loadProjectileFromXML(game, elem, refs, callback)
        }

        const val SERIALISATION_TYPE = "weaponProjectile"
    }
}

/**
 * This represents a drone flying on its way to its destination, such as a boarding drone.
 *
 * (This class exists to make adding new types of flying drones easier, Vanilla only has
 *  the boarding drone. Note that we don't count the hacking probe as a drone at all.)
 */
abstract class FlyingDroneProjectile(targetShip: Ship) : AbstractProjectile(targetShip, false) {
    abstract val drone: AbstractDrone

    override val serialisationType: String get() = SERIALISATION_TYPE

    companion object {
        const val SERIALISATION_TYPE = "flyingDrone"
    }
}
