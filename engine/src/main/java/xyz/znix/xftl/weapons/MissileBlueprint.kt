package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.f
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil

class MissileBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_random"

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return MissileInstance(ship)
    }

    override fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        SaveUtil.getRoomRef(elem, "target", refs) { target ->
            val projectile = MissileProjectile(target)
            projectile.loadPropertiesFromXML(elem, refs)
            callback(projectile)
        }
    }

    inner class MissileInstance(ship: Ship) : AbstractProjectileWeaponInstance(this, ship) {
        override fun buildProjectile(target: Room) = MissileProjectile(target)

        override val launchAdvance: Int get() = 16
    }

    inner class MissileProjectile(room: Room) : MissileBody(this@MissileBlueprint, room)
}

/**
 * The native Missile body (1.6.14 constructor 0x451900): Targetable type 1, speed 35, the base Projectile OnUpdate,
 * rendered at heading + 90 with the hit point at the front. SpaceManager::CreateMissile (HS CustomDamage.cpp) uses
 * it with any weapon blueprint and calls Initialize itself ([initialize] false).
 */
open class MissileBody(type: AbstractWeaponBlueprint, room: Room, initialize: Boolean = true) :
    AbstractWeaponProjectile(type, room) {
    private val spr = type.projectile?.let { target.ship.sys.animations.getOrNull(it) }?.spriteAt(ship.sys, 0)

    override val defaultSpeed: Int get() = 35

    override val isMissileForDD: Boolean get() = targetableType == 1 || targetableType == 2
    override val isLaserForDD: Boolean get() = targetableType == 4
    override val nativeType: Int get() = 3

    init {
        targetableType = 1
        if (initialize) { if (xyz.znix.xftl.hs.HS.active) hsInitialize(type, defaultSpeed) else nativeApplyBlueprint(type) }
    }

    override fun renderPreTranslated(g: Graphics) {
        val spr = hsFlightSpec?.let { s -> s.spriteAt(ship.sys, (flightTrackerOrNull?.currentFrame ?: 0).coerceIn(0, s.length - 1)) } ?: spr ?: return
        g.rotate(0f, 0f, 90f)

        // Hitbox for missiles is at the front of the projectile
        spr.draw(-spr.width.f / 2, 0f)
    }

    override fun saveToXML(elem: Element, refs: ObjectRefs) {
        super.saveToXML(elem, refs)
        SaveUtil.addRoomRef(elem, "target", refs, target)
    }
}
