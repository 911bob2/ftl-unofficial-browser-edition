// Native 1.6.14 BombProjectile: constructor 0x451060, OnUpdate 0x44fd30, CollisionCheck 0x450080 (vtable slot 15),
// CollisionMoving 0x44fc30, OnRenderSpecific 0x44fde0, GetOwnerId 0x761ca0; ShipManager::GetSuperShield 0x49af80.
// The same bodies run under HS 1.6.9, which hooks OnUpdate (PROJECTILE_UPDATE_PRE/POST) and the base constructor /
// Initialize (FTL-Hyperspace GPL-3.0-or-later 1b05a07 CustomDamage.cpp, Projectile_Extend.cpp; CreateBomb).
package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.weapons.HsDamage
import xyz.znix.xftl.hs.weapons.HsWeapons
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.math.MutFPoint
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil
import kotlin.math.cos
import kotlin.math.sin

/**
 * A teleported bomb. It is created off-screen in the shooter's space and enters the target's space on its first
 * frame. Its first CollisionCheck against the target ship (selfId == targetId) puts it at the target point, plays
 * the flight animation backwards and decides a miss (GetDodged, unless fired at the owner's own ship: it then walks
 * 117 px in a random direction until outside every room) or a super shield contact (a random point on the shield
 * ellipse, unless the Zoltan Shield Bypass was installed). Once the reversed animation is done, explosiveDelay
 * (0.4 s) runs down; when it is below zero the bomb deals DamageArea(forceHit) or DamageShield(forceHit, hull +
 * system damage) unless it missed, plays hitSolidSound and its death animation. A bomb is never a collision
 * receiver and reports owner -1 to other bodies.
 */
class BombProjectile(type: AbstractWeaponBlueprint, room: Room, superShieldBypass: Boolean) :
    AbstractWeaponProjectile(type, room) {
    private val flightSpec = type.projectile?.let { ship.sys.animations.getOrNull(it) }

    override val defaultSpeed: Int get() = 0
    override val nativeType: Int get() = 4
    override val nativeBody: Boolean get() = true
    override val collisionsEnabled: Boolean get() = false
    override val nativeCollisionOwnerId: Int get() = -1
    override val serialisationType: String get() = SERIALISATION_TYPE

    /** BombProjectile::bMissed, missMessage (its remaining time), explosiveDelay, bSuperShield, superShieldBypass. */
    var bMissed = false
    var missMessageTime: Float? = null
    var explosiveDelay = .4f
    var bSuperShield = false
    var superShieldBypass = superShieldBypass

    /** The reversed flight animation marks the bomb as having arrived at its target. */
    val arrived: Boolean get() = flightTrackerOrNull?.reverse == true

    init {
        drawUnderShip = false
        flightSpec?.let { flightTracker.time = it.totalTime; flightTracker.frameCount = it.length }
        // HS CreateBomb (and the factory it mirrors): the flight animation does not loop.
        flightTracker.loop = false
        if (HS.active) hsInitialize(type, defaultSpeed) else nativeApplyBlueprint(type)
    }

    /** OnUpdate: the flight animation, then the fuse once the reversed animation is done, then the miss text. */
    private fun bombOnUpdate(dt: Float) {
        val flight = flightTracker
        flight.advance(dt)
        if (flight.done && flight.reverse) explosiveDelay -= dt
        missMessageTime?.let { left -> missMessageTime = (left - dt).takeIf { it > 0f } }
    }

    override fun nativeStep(dt: Float) {
        hsExt?.lastPosition?.set(position)
        bombOnUpdate(dt)
    }

    override fun nativeUpdate(dt: Float, space: Ship) {
        bombOnUpdate(dt)
        hsResolveShipCollision(space, dt)
    }

    /** SpaceManager's bounds test sends the off-screen bomb into its destination space before its first update. */
    override fun nativeCheckBounds(space: Ship): Ship {
        if (hsCurrentSpaceId == hsDestinationSpaceId) return hsCurrentSpace ?: space
        if (HS.active) nativeEnterDestination()
        else { hsCurrentSpace = targetShip; hsCurrentSpaceId = hsDestinationSpaceId }
        return hsCurrentSpace ?: space
    }

    /** CollisionCheck against a ship: only the ship whose id is the bomb's targetId. */
    override fun hsResolveShipCollision(currentSpace: Ship, dt: Float) {
        val receiver = targetShip ?: return
        if (HsWeapons.shipId(receiver) != hsTargetId) return
        hsExt?.damage?.hsOwnerId = hsOwnerId
        if (!deathAnimationRunning) {
            if (!arrived) arrive(receiver)
            else if (explosiveDelay < 0f) explode(receiver)
        }
        // SetTempVision for a player-owned bomb that did not miss: providesPlayerVision, read by the room vision pass.
    }

    private fun arrive(receiver: Ship) {
        hsCurrentSpaceId = hsDestinationSpaceId
        hsCurrentSpace = receiver
        flightTracker.startReverse(true)
        pos.set(targetPos.let { if (it is FPoint) ConstFPoint(it.xf, it.yf) else ConstFPoint(it.x.toFloat(), it.y.toFloat()) })
        val dodged = if (HS.active) HsDamage.with(hsExt, this) { nativeShieldDodged(receiver) } else nativeShieldDodged(receiver)
        if (dodged && HsWeapons.shipId(receiver) != hsOwnerId) {
            bMissed = true
            receiver.sys.browserAchievements.damageDodged(receiver, computeDamage().hullDamage)
            pos.set(missLanding(receiver, pos, ftl.browser.BrowserRandom.nextBits(31) % 4))
            missMessageTime = 1f
            receiver.showDamageTextAt(xyz.znix.xftl.math.ConstPoint(pos.xf.toInt(), pos.yf.toInt() + 10), "text_miss", Colour.white)
            return
        }
        val point = receiver.nativeSuperShieldPoint()
        if (point.xf != NativeCollideableDefaults.NO_SUPER_SHIELD && point.yf != NativeCollideableDefaults.NO_SUPER_SHIELD &&
            !superShieldBypass && HsWeapons.shipId(receiver) != hsOwnerId) {
            bSuperShield = true
            pos.set(point)
        }
    }

    private fun explode(receiver: Ship) {
        if (!bMissed) {
            if (!bSuperShield) {
                val room = receiver.rooms.firstOrNull { it.containsShipSpace(xyz.znix.xftl.math.ConstPoint(pos.xf.toInt(), pos.yf.toInt())) }
                // ShipManager::DamageArea (0x4a6100) in both engines: forced, but a jumping ship still rolls.
                hsDamageArea(receiver, room, xyz.znix.xftl.math.ConstFPoint(pos.xf, pos.yf), true)
            } else {
                // The member damage itself gains the system damage before DamageShield (visible to scripts).
                hsExt?.damage?.let { it.hullDamage += it.pureSysDamage }
                val damage = computeDamage()
                if (!HS.active) damage.hullDamage += damage.pureSysDamage
                HsDamage.with(hsExt, this) { ShieldCollisionRules.real(receiver, position, damage, true) }
            }
        }
        type.hitShipSounds?.get()?.play()
        deathAnimation.start(true)
    }

    /** A bomb of an older save that had already been placed at [point]. */
    fun nativeRestoreArrived(point: xyz.znix.xftl.math.IPoint, missed: Boolean, superShield: Boolean, elapsed: Float) {
        hsCurrentSpace = targetShip; hsCurrentSpaceId = hsDestinationSpaceId
        pos.set(point.x.toFloat(), point.y.toFloat())
        flightTracker.startReverse(true)
        flightTracker.advance(elapsed)
        bMissed = missed; bSuperShield = superShield
    }

    override fun reachedTarget() = Unit
    override fun hitOtherProjectile(currentSpace: Ship) = Unit
    override fun nativeCollisionMoving(finish: FPoint, damage: Damage) = ProjectileCollisionResponse()

    override fun providesPlayerVision(room: Room): Boolean =
        room == target && arrived && !bMissed && !bSuperShield && hsOwnerId == 0

    /** OnRenderSpecific: the reversed flight animation while the fuse burns, then the death animation. */
    override fun render(g: Graphics, currentSpace: Ship) {
        if ((explosiveDelay <= 0f || !arrived)) {
            if (deathAnimationRunning) super.render(g, currentSpace)
        } else {
            val spec = flightSpec
            if (spec != null) {
                val frame = flightTracker.currentFrame.coerceIn(0, spec.length - 1)
                val sprite = spec.spriteAt(ship.sys, frame)
                sprite.draw(position.xf - (sprite.width / 2), position.yf - (sprite.height / 2))
            }
        }
    }

    override fun renderPreTranslated(g: Graphics) {}

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
        elem.setAttribute("owner", hsOwnerId.toString())
        elem.setAttribute("space", hsCurrentSpaceId.toString())
        SaveUtil.addTagBoolIfTrue(elem, "bMissed", bMissed)
        SaveUtil.addTagBoolIfTrue(elem, "bSuperShield", bSuperShield)
        SaveUtil.addTagBoolIfTrue(elem, "superShieldBypass", superShieldBypass)
        SaveUtil.addAttrFloat(elem, "explosiveDelay", explosiveDelay)
        missMessageTime?.let { SaveUtil.addAttrFloat(elem, "missMessage", it) }
        val flight = flightTracker
        val node = Element("flightTracker")
        SaveUtil.addAttrFloat(node, "current", flight.currentTime)
        SaveUtil.addTagBool(node, "running", flight.running)
        SaveUtil.addTagBool(node, "done", flight.done)
        SaveUtil.addTagBool(node, "reverse", flight.reverse)
        elem.addContent(node)
    }

    override fun loadPropertiesFromXML(elem: Element, refs: RefLoader) {
        super.loadPropertiesFromXML(elem, refs)
        hsOwnerId = elem.getAttributeValue("owner")?.toInt() ?: hsOwnerId
        elem.getAttributeValue("space")?.toInt()?.let { id ->
            hsCurrentSpaceId = id
            hsCurrentSpace = when (id) { 0 -> ship.sys.player; 1 -> ship.sys.enemy; else -> null }
            hsCurrentSpaceId = id
        }
        bMissed = SaveUtil.getOptionalTagBool(elem, "bMissed") ?: false
        bSuperShield = SaveUtil.getOptionalTagBool(elem, "bSuperShield") ?: false
        superShieldBypass = SaveUtil.getOptionalTagBool(elem, "superShieldBypass") ?: false
        explosiveDelay = elem.getAttributeValue("explosiveDelay")?.toFloat() ?: explosiveDelay
        missMessageTime = elem.getAttributeValue("missMessage")?.toFloat()
        elem.getChild("flightTracker")?.let { node ->
            val flight = flightTracker
            flight.currentTime = SaveUtil.getAttrFloat(node, "current")
            flight.running = SaveUtil.getOptionalTagBool(node, "running") ?: false
            flight.done = SaveUtil.getOptionalTagBool(node, "done") ?: false
            flight.reverse = SaveUtil.getOptionalTagBool(node, "reverse") ?: false
            flight.advance(0f)
        }
    }

    companion object {
        const val SERIALISATION_TYPE = "bombBody"
        /** The distance a missed bomb moves per step (float at 0x878370). */
        private const val MISS_STEP = 117f

        /** Native start position of a bomb before it enters its destination space (HS CreateBomb). */
        val OFF_SCREEN = ConstFPoint(-2147483648f, -2147483648f)

        /** A missed bomb walks MISS_STEP pixels at a time in [direction] (0 up, 1 down, 2 left, 3 right) from [start]
         * until it is outside every room of [receiver]. */
        fun missLanding(receiver: Ship, start: FPoint, direction: Int): ConstFPoint {
            var x = start.xf; var y = start.yf
            while (receiver.rooms.any { it.containsPixelInclusive(x.toInt(), y.toInt()) }) {
                when (direction) {
                    0 -> y -= MISS_STEP
                    1 -> y += MISS_STEP
                    2 -> x -= MISS_STEP
                    else -> x += MISS_STEP
                }
            }
            return ConstFPoint(x, y)
        }

        fun load(game: InGameState, elem: Element, refs: RefLoader, callback: ProjectileLoadCallback) {
            val type = game.blueprintManager[SaveUtil.getAttr(elem, "launcher")] as AbstractWeaponBlueprint
            SaveUtil.getRoomRef(elem, "target", refs) { room ->
                val bomb = BombProjectile(type, room, false)
                bomb.loadPropertiesFromXML(elem, refs)
                callback(bomb)
            }
        }
    }
}

/** ShipManager::GetSuperShield (0x49af80): a random point (random32() % 360 degrees) on the shield ellipse while a
 * super shield is up, else (-1, -1). */
fun Ship.nativeSuperShieldPoint(): FPoint {
    if (shields == null || superShield <= 0) return ConstFPoint(NativeCollideableDefaults.NO_SUPER_SHIELD, NativeCollideableDefaults.NO_SUPER_SHIELD)
    val theta = (ftl.browser.BrowserRandom.nextBits(31) % 360) * 3.1415927f / 180f
    return ConstFPoint(cos(theta) * shieldHalfSize.x + shieldOrigin.x, sin(theta) * shieldHalfSize.y + shieldOrigin.y)
}
