// Native BeamWeapon 1.6.9 4357c0/435c90/436040/434f60, 1.6.14 constructor 4383b0, OnUpdate 438880,
// Dead 437b10, OnRenderSpecific 437b50, CollisionCheck 438c30, SaveProjectile 4376a0; HS GPL-3.0-or-later
// 1b05a07 CustomDamage.cpp (CreateBeam, update hooks, ionBeamFix) and CustomWeapons.cpp (invisibleBeam).
package xyz.znix.xftl.weapons

import ftl.browser.BrowserRandom
import org.jdom2.Element
import xyz.znix.xftl.FTLAnimation
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.math.*
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.pow
import kotlin.math.sqrt

/** A real world projectile, independently of the weapon factory that launched it. */
class BeamProjectile(val game: InGameState, target: Ship?, end: FPoint, length: Int, heading: Float) :
    AbstractProjectile(target) {
    /** The weapon animation a launched beam drives (BeamWeapon::weapAnimation, SetWeaponAnimation). */
    interface WeaponAnimationLink {
        /** WeaponAnimation::SetFireLevel. */
        fun setFireLevel(level: Float)
        /** WeaponAnimation::bPowered: the beam kills itself once its weapon is unpowered. */
        val weaponPowered: Boolean
    }

    val motion = BeamMotion(targetPos as MutFPoint, position, end, length, heading)
    var blueprint: AbstractWeaponBlueprint? = null
        private set
    var movingTarget: Ship? = target
    var weaponAnimation: WeaponAnimationLink? = null
    /** Projectile::flight_animation (the factory's blueprint image): copied as the contact animation. */
    var flightAnimation: String? = null
    /** BeamWeapon::contactAnimations / smokeAnims; neither is saved natively. */
    val contactAnimations = ArrayList<FTLAnimation>()
    val smokeAnimations = ArrayList<SmokeAnimation>()
    /** ProjectileFactory::lastProjectileId: the launcher reconnects to its beam after a load. */
    var launchId: Int = nextLaunchId++
        private set
    private val vanillaDamage = Damage.regular(3).also { it.fireChance = 80 }
    private var nativeSpeed = 5f
    var nativeHeading = heading
        private set
    /** Projectile::damage: the HS extend's live Damage, or the engine's own copy without HS. */
    val damage: Damage get() = hsExt?.damage ?: vanillaDamage
    /** The last WeaponAnimation::SetFireLevel argument (the swipe progress). */
    var fireLevel = 0f
        private set
    override val speed: Int get() = (nativeSpeed * 16f).toInt()
    /** Projectile::speed_magnitude (the HS extend's live value). */
    val speedMagnitude: Float get() = hsExt?.speedMagnitude ?: nativeSpeed
    override val antiDroneBP: AbstractWeaponBlueprint? get() = null
    override val antiDroneExemption: Ship? get() = when (hsOwnerId) { 0 -> game.player; 1 -> game.enemy; else -> null }
    override val serialisationType get() = SERIALISATION_TYPE

    /** A trail animation: explosion1 ([animation]), or effects/fire_smoke.png's 7 frames over 0.5 s by [age]. */
    class SmokeAnimation(val animation: FTLAnimation?, val x: Float, val y: Float, val scale: Float) {
        var age = 0f
        private var finished = false
        val done: Boolean get() = finished

        /** AnimationTracker::Update (0x5ba8e0): done only once the time is past the length, then held at it. */
        fun update(dt: Float) {
            if (animation != null) { finished = finished || stepNative(animation, dt); return }
            if (finished) return
            age += dt
            if (age > .5f) { age = .5f; finished = true }
        }
    }


    init {
        hsExt?.damage?.copyFrom(vanillaDamage)
        hsExt?.speedMagnitude = nativeSpeed
        hsExt?.damage?.hsSelfId = hsExt?.selfId ?: -1
        motion.oneSpace = hsCurrentSpaceId == hsDestinationSpaceId
        motion.movingTargetId = HsWeapons.shipId(movingTarget)
        motion.dh = if (BrowserRandom.nextInt().ushr(1) % 2 == 0) -.125f else .125f
        if (!motion.oneSpace) {
            motion.subStart.set(BeamMotion.entryPoint(nextEntry, BrowserRandom.nextInt().ushr(1)))
            nextEntry = (nextEntry + 1) % 4
        }
        setBeamHeading(heading)
        if (motion.oneSpace) computeBeamHeading()
    }

    fun initialize(bp: AbstractWeaponBlueprint) {
        blueprint = bp
        // Projectile::Initialize: flight_animation is the blueprint's image, also for a beam made by CreateBeam.
        flightAnimation = bp.projectile
        vanillaDamage.copyFrom(Damage(bp))
        bp.speed?.takeIf { it != 0 }?.let { nativeSpeed = it.toFloat() }
        hsInitialize(bp, 5)
    }

    fun computeBeamHeading() {
        val tip = motion.tip
        val x = tip.xf - position.xf; val y = tip.yf - position.yf
        val length = sqrt(x * x + y * y)
        val normalizedX = if (length == 0f) 0f else x / length
        var degrees = Math.toDegrees(acos(normalizedX).toDouble()).toFloat()
        if (y < 0f) degrees = -degrees
        setBeamHeading(degrees)
    }

    fun setBeamHeading(value: Float) { nativeHeading = value; hsSetHeading(value) }
    internal fun setRawSpeed(value: Float) { nativeSpeed = value; hsExt?.speedMagnitude = value }

    override fun nativeDead(dt: Float): Boolean = motion.dead(hsDead)
    // Native BeamWeapon's EnterDestinationSpace override deliberately has no effect.
    override fun nativeEnterDestination() = Unit
    // A BeamWeapon is not a projectile-collision receiver (native 71f870).
    override fun nativeCollisionMoving(finish: FPoint, damage: Damage) = ProjectileCollisionResponse()

    override fun nativeStep(dt: Float) {
        if (!motion.checkedCollision) return
        // OnUpdate (0x438880) first advances the contact and smoke animations: each one is updated and erased at
        // once when done, and the index still moves on, so the one that slid into its place waits a frame.
        var i = 0
        while (i < contactAnimations.size) {
            if (stepNative(contactAnimations[i], dt)) contactAnimations.removeAt(i)
            i++
        }
        i = 0
        while (i < smokeAnimations.size) {
            val s = smokeAnimations[i]; s.update(dt)
            if (s.done) smokeAnimations.removeAt(i)
            i++
        }
        val link = weaponAnimation
        setBeamHeading(motion.advance(dt, hsExt?.speedMagnitude ?: nativeSpeed, nativeHeading) { level ->
            fireLevel = level
            if (link != null) {
                link.setFireLevel(level)
                if (!link.weaponPowered) hsKill()
            }
        })
    }

    override fun nativeUpdate(dt: Float, space: Ship) {
        nativeStep(dt)
        hsResolveShipCollision(space, dt)
    }

    override fun hsResolveShipCollision(currentSpace: Ship, dt: Float) {
        // BeamWeapon checks destinationSpace (native +70), independently of Projectile.currentSpace (+74).
        val receiver = movingTarget ?: return
        if (hsDestinationSpaceId != HsWeapons.shipId(receiver) || hsOwnerId == HsWeapons.shipId(receiver)) return
        motion.checkedCollision = true
        val damage = damage.copy()
        damage.hsOwnerId = hsOwnerId; damage.hsSelfId = hsExt?.selfId ?: worldOrder
        damage.ownerShip = antiDroneExemption
        val shieldDamage = damage.copy()
        if (HS.active && blueprint?.hs?.damage?.ionBeamFix == true && !motion.damageSuperShield)
            shieldDamage.ionDamage = 0
        val response = ShieldCollisionRules.moving(receiver, motion.subStart, motion.tip, shieldDamage, true) { false }
        motion.piercedShield = true
        if (response.type == 2 && (response.damage > 0 || response.superDamage > 0)) {
            var blocked = response.damage - damage.hsShieldPiercing
            if (blocked >= damage.hullDamage) motion.piercedShield = false
            if (response.superDamage > 0) {
                motion.piercedShield = false
                blocked = damage.hullDamage
                if (motion.damageSuperShield) {
                    val zap = damage.copy(); zap.hullDamage = maxOf(1, zap.hullDamage)
                    ShieldCollisionRules.real(receiver, response.point, zap, true)
                }
            }
            damage.hullDamage -= maxOf(0, blocked)
            motion.shieldEnd.set(response.point)
        } else motion.shieldEnd.set(motion.finalEnd)
        motion.damageSuperShield = false
        motion.lastDamage = maxOf(0, damage.hullDamage)
        if (motion.piercedShield) {
            contactAndSmoke()
            BeamDamageRules.damageBeam(receiver, motion.tip, motion.lastCollision.point, damage)
        }
        motion.lastCollision.point.set(motion.tip)
    }

    /**
     * CollisionCheck's effects of a beam that got through: a copy of the flight animation every 0.25-0.34 s
     * ((r%10+25)/100), and a trail at the previous contact point once it moved more than 5 pixels from the last
     * one: effects/fire_smoke.png (7 frames, 0.5 s, scale 0.4, or 0.5 at lastDamage 2) below 3 damage, else
     * explosion1 at 0.75, offset by half the 34-pixel frame and r%7-3 pixels.
     */
    private fun contactAndSmoke() {
        if (motion.animationTimer < 0f) {
            flightAnimation?.let { name -> game.animations.getOrNull(name)?.let { contactAnimations += it.startSingle(game) } }
            motion.animationTimer = (BrowserRandom.nextInt(10) + 25) / 100f
        }
        val last = motion.lastCollision.point
        val dx = motion.lastSmokeAnim.xf - last.xf; val dy = motion.lastSmokeAnim.yf - last.yf
        if (sqrt(dx * dx + dy * dy) > 5f) {
            val small = motion.lastDamage < 3
            val scale = if (!small) .75f else if (motion.lastDamage == 2) .5f else .4f
            val animation = if (small) null else game.animations.getOrNull("explosion1")?.startSingle(game)
            val offset = 17f * scale
            // CollisionCheck (1.6.14 0x438c30) builds the position as a Point: both coordinates truncate to integers.
            val x = ((BrowserRandom.nextInt(7) - 3) + (motion.lastSmokeAnim.xf - offset)).toInt().toFloat()
            val y = ((BrowserRandom.nextInt(7) - 3) + (motion.lastSmokeAnim.yf - offset)).toInt().toFloat()
            smokeAnimations += SmokeAnimation(animation, x, y, scale)
            motion.lastSmokeAnim.set(last)
        }
    }

    override fun reachedTarget() { error("BeamWeapon resolves its ray through CollisionCheck") }
    override fun hitOtherProjectile(currentSpace: Ship) = Unit // Native beam collision response is always zero.

    /** OnRenderSpecific(currentSpace): the part from the weapon to sub_end (also the target part of a one-space beam). */
    override fun render(g: Graphics, currentSpace: Ship) {
        if (HsWeapons.shipId(currentSpace) == hsDestinationSpaceId && motion.oneSpace) renderSpecific(g, currentSpace)
        else if (HsWeapons.shipId(currentSpace) == hsCurrentSpaceId) renderSpecific(g, currentSpace)
    }

    /**
     * Native OnRenderSpecific(spaceId): nothing before the first CollisionCheck, without a moving target or once
     * the lifespan is negative. In the current space a cross-space beam draws position -> sub_end at max(1, damage)
     * strength. In the destination space it draws sub_start -> final_end at the strength left after the shields
     * (1 for a zero-damage beam that got through), the contact animations at final_end - (24, 33), and, where the
     * shields took strength away, the full-strength part outside the shield (native: a shield-ellipse stencil;
     * here the segment up to shield_end). Colour: the projectile's GL_Color / 255 at alpha 1.
     */
    fun renderSpecific(g: Graphics, space: Ship) {
        if (!motion.checkedCollision || movingTarget == null || motion.lifespan < 0f) return
        if (HS.active && blueprint?.hs?.invisibleBeam == true) return
        val beam = blueprint as? BeamBlueprint
        val colour = Colour(nativeColor.r / 255f, nativeColor.g / 255f, nativeColor.b / 255f, 1f)
        val strength = maxOf(1, damage.hullDamage)
        val spaceId = HsWeapons.shipId(space)
        if (spaceId == hsCurrentSpaceId && !motion.oneSpace) {
            drawSegment(g, beam, strength, position, motion.subEnd, null, colour)
            return
        }
        if (spaceId != hsDestinationSpaceId) return
        val target = movingTarget!!
        val inner = if (motion.piercedShield) (if (damage.hullDamage == 0) 1 else motion.lastDamage) else motion.lastDamage
        if (motion.piercedShield) for (s in smokeAnimations) {
            val animation = s.animation
            if (animation != null) {
                val image = animation.currentFrame
                image.draw(s.x, s.y, image.width * s.scale, image.height * s.scale)
                continue
            }
            val sheet = game.getImg("img/effects/fire_smoke.png")
            val size = sheet.width / 7
            val frame = minOf(6, (s.age / .5f * 7).toInt())
            sheet.draw(s.x, s.y, s.x + size * s.scale, s.y + sheet.height * s.scale,
                (frame * size).toFloat(), 0f, ((frame + 1) * size).toFloat(), sheet.height.toFloat())
        }
        val split = motion.lastDamage < damage.hullDamage || (!motion.piercedShield && damage.hullDamage == 0)
        if (split) {
            drawSegment(g, beam, strength, motion.subStart, motion.shieldEnd, target, colour)
            if (inner > 0 && !samePoint(motion.shieldEnd, motion.finalEnd))
                drawSegment(g, beam, inner, motion.finalEnd, motion.shieldEnd, target, colour)
        } else if (inner > 0) drawSegment(g, beam, inner, motion.subStart, motion.finalEnd, null, colour)
        if (motion.piercedShield) for (a in contactAnimations) a.draw(motion.finalEnd.xf - 24f, motion.finalEnd.yf - 33f, colour)
    }

    private fun samePoint(a: FPoint, b: FPoint) = a.xf == b.xf && a.yf == b.yf

    /** One beam segment; ending on [shield] cuts the end along that ship's shield ellipse tangent. */
    private fun drawSegment(g: Graphics, beam: BeamBlueprint?, power: Int, from: FPoint, to: FPoint, shield: Ship?,
                            colour: Colour) {
        if (samePoint(from, to)) return
        // OnRenderSpecific (1.6.14 0x437b50): translate to the float start, rotate by the float vector's angle,
        // translate back, then a rect from the start truncated to integers, (int) length long. So the centre line
        // runs from the integer start turned about the float start, along the float direction.
        val dx = to.xf - from.xf; val dy = to.yf - from.yf
        val length = sqrt(dx * dx + dy * dy)
        val cos = dx / length; val sin = dy / length
        val ox = from.xf.toInt() - from.xf; val oy = from.yf.toInt() - from.yf
        val sx = from.xf + ox * cos - oy * sin; val sy = from.yf + ox * sin + oy * cos
        val run = length.toInt().toFloat()
        val src = ConstFPoint(sx, sy)
        val dst = ConstFPoint(sx + run * cos, sy + run * sin)
        if (beam == null) {
            g.colour = colour
            g.drawLine(from.xf, from.yf, to.xf, to.yf)
            return
        }
        val angle = if (shield == null) atan2(to.yf - from.yf, to.xf - from.xf) else {
            val size = shield.shieldHalfSize; val origin = shield.shieldOrigin
            atan2((to.yf - origin.y) * size.x.toFloat().pow(2), (to.xf - origin.x) * size.y.toFloat().pow(2))
        }
        beam.drawBeam(g, game, power, src, dst, angle, colour)
    }

    override fun renderPreTranslated(g: Graphics) = Unit

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        blueprint?.let { elem.setAttribute("blueprint", it.name) }
        flightAnimation?.let { elem.setAttribute("flightAnimation", it) }
        elem.setAttribute("launchId", launchId.toString())
        elem.setAttribute("owner", hsOwnerId.toString()); elem.setAttribute("space", hsCurrentSpaceId.toString())
        elem.setAttribute("destination", hsDestinationSpaceId.toString())
        elem.setAttribute("targetId", hsTargetId.toString())
        elem.setAttribute("movingTarget", HsWeapons.shipId(movingTarget).toString())
        elem.setAttribute("movingId", motion.movingTargetId.toString())
        // The ships themselves, by reference: the enemy is not the game's enemy yet while the player ship loads.
        elem.setAttribute("spaceRef", hsCurrentSpace?.takeIf { refs.isRegistered(it) }?.let { refs[it] } ?: "null")
        elem.setAttribute("targetRef", movingTarget?.takeIf { refs.isRegistered(it) }?.let { refs[it] } ?: "null")
        SaveUtil.addAttrFloat(elem, "speedValue", hsExt?.speedMagnitude ?: nativeSpeed)
        SaveUtil.addAttrFloat(elem, "headingValue", nativeHeading)
        for ((name, value) in listOf("r" to nativeColor.r, "g" to nativeColor.g, "b" to nativeColor.b, "a" to nativeColor.a))
            SaveUtil.addAttrFloat(elem, "color" + name, value)
        for ((name, p) in vectors()) {
            SaveUtil.addAttrFloat(elem, name + "X", p.xf); SaveUtil.addAttrFloat(elem, name + "Y", p.yf)
        }
        for ((name, value) in listOf("life" to motion.lifespan, "length" to motion.length, "dh" to motion.dh,
            "animationTimer" to motion.animationTimer, "startHeading" to motion.startHeading, "timer" to motion.timer))
            SaveUtil.addAttrFloat(elem, name, value)
        // Native SaveProjectile writes neither checkedCollision nor the contact/smoke animations.
        for ((name, value) in listOf("pierced" to motion.piercedShield, "oneSpace" to motion.oneSpace,
            "damageSuper" to motion.damageSuperShield)) SaveUtil.addTagBool(elem, name, value)
        elem.setAttribute("lastDamage", motion.lastDamage.toString())
        // The browser keeps the crew this beam killed (killedByBeam, ACH_SLUG_BIO) with the beam across a save.
        movingTarget?.nativeBeamKills?.get(killKey())?.let { elem.setAttribute("beamKills", it.toString()) }
        val d = damage
        for ((name, value) in listOf("hull" to d.hullDamage, "ion" to d.ionDamage, "sys" to d.pureSysDamage,
            "crew" to d.pureCrewDamage, "fire" to d.fireChance, "breach" to d.breachChance, "stun" to d.stunChance,
            "buster" to d.emptyRoomBonus, "pierce" to d.hsShieldPiercing))
            elem.setAttribute("damage-$name", value.toString())
    }

    private fun vectors() = listOf("tip" to motion.tip, "subEnd" to motion.subEnd, "subStart" to motion.subStart,
        "shieldEnd" to motion.shieldEnd, "finalEnd" to motion.finalEnd, "target1" to motion.target1,
        "target2" to motion.target2, "lastCollision" to motion.lastCollision.point, "lastSmoke" to motion.lastSmokeAnim)

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)
        flightAnimation = elem.getAttributeValue("flightAnimation")
        elem.getAttributeValue("launchId")?.toInt()?.let { launchId = it; nextLaunchId = maxOf(nextLaunchId, it + 1) }
        hsOwnerId = elem.getAttributeValue("owner").toInt()
        val space = elem.getAttributeValue("space").toInt()
        hsCurrentSpace = when (space) { 0 -> game.player; 1 -> game.enemy; else -> null }; hsCurrentSpaceId = space
        hsDestinationSpaceId = elem.getAttributeValue("destination").toInt()
        hsTargetId = elem.getAttributeValue("targetId").toInt()
        movingTarget = when (elem.getAttributeValue("movingTarget").toInt()) { 0 -> game.player; 1 -> game.enemy; else -> null }
        motion.movingTargetId = elem.getAttributeValue("movingId").toInt()
        nativeSpeed = SaveUtil.getAttrFloat(elem, "speedValue"); hsExt?.speedMagnitude = nativeSpeed
        setBeamHeading(SaveUtil.getAttrFloat(elem, "headingValue"))
        elem.getAttributeValue("colorr")?.let { nativeColor.r = it.toFloat() }
        elem.getAttributeValue("colorg")?.let { nativeColor.g = it.toFloat() }
        elem.getAttributeValue("colorb")?.let { nativeColor.b = it.toFloat() }
        elem.getAttributeValue("colora")?.let { nativeColor.a = it.toFloat() }
        for ((name, p) in vectors()) p.set(SaveUtil.getAttrFloat(elem, name + "X"), SaveUtil.getAttrFloat(elem, name + "Y"))
        motion.lifespan = SaveUtil.getAttrFloat(elem, "life"); motion.length = SaveUtil.getAttrFloat(elem, "length")
        motion.dh = SaveUtil.getAttrFloat(elem, "dh"); motion.animationTimer = SaveUtil.getAttrFloat(elem, "animationTimer")
        motion.startHeading = SaveUtil.getAttrFloat(elem, "startHeading"); motion.timer = SaveUtil.getAttrFloat(elem, "timer")
        motion.piercedShield = SaveUtil.getTagBool(elem, "pierced"); motion.oneSpace = SaveUtil.getTagBool(elem, "oneSpace")
        motion.damageSuperShield = SaveUtil.getTagBool(elem, "damageSuper")
        motion.checkedCollision = false
        motion.lastDamage = elem.getAttributeValue("lastDamage").toInt()
        val d = damage
        fun int(name: String) = elem.getAttributeValue("damage-$name")?.toInt()
        int("hull")?.let { d.hullDamage = it }; int("ion")?.let { d.ionDamage = it }; int("sys")?.let { d.pureSysDamage = it }
        int("crew")?.let { d.pureCrewDamage = it }; int("fire")?.let { d.fireChance = it }
        int("breach")?.let { d.breachChance = it }; int("stun")?.let { d.stunChance = it }
        int("buster")?.let { d.emptyRoomBonus = it }; int("pierce")?.let { d.hsShieldPiercing = it }
    }

    /** The key BeamDamageRules counts this beam's kills under (Damage.selfId as CollisionCheck passes it). */
    internal fun killKey() = hsExt?.selfId ?: worldOrder

    companion object {
        /** One Animation::Update of a non-looping animation; true once Animation::Done holds (strictly past the end). */
        internal fun stepNative(a: FTLAnimation, dt: Float): Boolean {
            val past = !a.loop && a.timer + dt * a.speed > a.duration
            a.update(dt)
            return past
        }
        const val SERIALISATION_TYPE = "beamBody"
        private var nextEntry = 0
        private var nextLaunchId = 1
        internal fun entryIndex() = nextEntry
        internal fun restoreEntryIndex(index: Int) { nextEntry = index }

        /** OnRenderSpecific(destination) of the cross-space beams stored in another ship's list, drawn in [ship]. */
        @JvmStatic
        fun renderInbound(g: Graphics, ship: Ship) {
            val game = ship.sys
            val id = HsWeapons.shipId(ship)
            for (list in listOf(game.player.projectiles, game.enemy?.projectiles ?: emptyList<IProjectile>())) {
                if (list === ship.projectiles) continue
                for (p in list) if (p is BeamProjectile && !p.motion.oneSpace && p.hsDestinationSpaceId == id)
                    p.renderSpecific(g, ship)
            }
        }

        /** The live beam with [launchId] (ProjectileFactory::ReconnectProjectile after a load). */
        fun find(game: InGameState, launchId: Int): BeamProjectile? =
            (game.player.projectiles + (game.enemy?.projectiles ?: emptyList()))
                .firstOrNull { it is BeamProjectile && it.launchId == launchId } as BeamProjectile?

        fun load(game: InGameState, elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            val spaceRef = elem.getAttributeValue("spaceRef"); val targetRef = elem.getAttributeValue("targetRef")
            if (spaceRef == null || targetRef == null) {
                // A body saved without ship references: the ships by id once references resolve.
                refs.asyncResolve(Ship::class.java, "null") { _ -> build(game, elem, refs, null, null, false, callback) }
                return
            }
            refs.asyncResolve(Ship::class.java, spaceRef) { space ->
                refs.asyncResolve(Ship::class.java, targetRef) { target -> build(game, elem, refs, space, target, true, callback) }
            }
        }

        private fun build(game: InGameState, elem: Element, refs: RefLoader, spaceShip: Ship?, targetShip: Ship?,
                          byRef: Boolean, callback: ProjectileLoadCallback) {
            val spaceId = elem.getAttributeValue("space").toInt()
            val destination = elem.getAttributeValue("destination").toInt()
            val storage = spaceShip ?: (if (spaceId == 1) game.enemy else null) ?: game.player
            val target = if (byRef) targetShip else when (destination) { 0 -> game.player; 1 -> game.enemy; else -> null }
            val origin = ConstFPoint(SaveUtil.getAttrFloat(elem, "x"), SaveUtil.getAttrFloat(elem, "y"))
            val tip = ConstFPoint(SaveUtil.getAttrFloat(elem, "tipX"), SaveUtil.getAttrFloat(elem, "tipY"))
            val end = ConstFPoint(SaveUtil.getAttrFloat(elem, "target2X"), SaveUtil.getAttrFloat(elem, "target2Y"))
            val context = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.Context(origin, tip, storage,
                elem.getAttributeValue("owner").toInt(), spaceId, destination)
            val p = xyz.znix.xftl.hs.weapons.HsProjectileConstruction.with(context) {
                BeamProjectile(game, target, end, SaveUtil.getAttrFloat(elem, "length").toInt(), 0f).also {
                    val name = elem.getAttributeValue("blueprint")
                    (game.blueprintManager.getOrNull(name ?: "") as? AbstractWeaponBlueprint)?.let(it::initialize)
                }
            }
            p.loadPropertiesFromXML(elem, refs)
            if (byRef) { p.movingTarget = targetShip; if (spaceShip != null) p.hsCurrentSpace = spaceShip }
            // The crew this beam killed, under its new selfId on the ship it hits.
            elem.getAttributeValue("beamKills")?.toInt()?.let { kills -> p.movingTarget?.nativeBeamKills?.set(p.killKey(), kills) }
            callback(p)
        }
    }
}
