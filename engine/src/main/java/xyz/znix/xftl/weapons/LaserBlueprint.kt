package xyz.znix.xftl.weapons

import org.jdom2.Element
import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.savegame.ObjectRefs
import xyz.znix.xftl.savegame.RefLoader
import xyz.znix.xftl.savegame.SaveUtil

class LaserBlueprint(xml: Element) : AbstractWeaponBlueprint(xml) {
    override val explosion: String = super.explosion ?: "explosion_missile1"

    override fun buildInstance(ship: Ship): AbstractWeaponInstance {
        return LaserInstance(ship)
    }

    override fun loadProjectileFromXML(
        game: InGameState,
        elem: Element, refs: RefLoader,
        callback: ProjectileLoadCallback
    ) {
        SaveUtil.getRoomRef(elem, "target", refs) { target ->
            val projectile = LaserProjectile(target)
            projectile.loadPropertiesFromXML(elem, refs)
            callback(projectile)
        }
    }

    inner class LaserInstance(ship: Ship) : AbstractProjectileWeaponInstance(this, ship) {
        override fun buildProjectile(target: Room) = LaserProjectile(target)
    }

    inner class LaserProjectile(room: Room) : LaserBlastProjectile(this@LaserBlueprint, room) {
        override fun saveToXML(elem: Element, refs: ObjectRefs) {
            super.saveToXML(elem, refs)
            SaveUtil.addRoomRef(elem, "target", refs, target)
        }
    }
}
