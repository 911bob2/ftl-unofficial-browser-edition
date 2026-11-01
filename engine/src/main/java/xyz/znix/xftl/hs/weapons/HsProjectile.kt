// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/Projectile_Extend.h,
// src/game/Gameplay/CustomDamage.cpp (Projectile::Initialize, SpaceDrone::CollisionMoving)
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.Damage
import xyz.znix.xftl.math.MutFPoint

/**
 * Projectile_Extend: what Hyperspace adds to every projectile. `name` is the weapon blueprint the projectile was
 * initialised from (MV's Lua reads `projectile.extend.name` to find its weapon tags), `customDamage` its
 * CustomDamage (definition plus per-projectile accuracy, which scripts may change), `missedDrones` the drones that
 * dodged it (HS rolls a drone's dodge once per projectile, vanilla on every frame of contact).
 * Only created while Hyperspace is active.
 */
class HsProjectileExt(detached: Boolean = false) {
    /** Native Projectile fields live with the optional HS state, so vanilla allocates no Lua compatibility state. */
    @JvmField var orig: Any? = null
    // A detached one (a script's `new CustomDamage`) is no projectile and takes no space id.
    @JvmField var selfId = if (detached) -1 else HsWeapons.nextSpaceId()
    @JvmField val damage = Damage.regular(1)
    @JvmField var speedMagnitude = 0f
    @JvmField var lifespan = 2f
    @JvmField val lastPosition = MutFPoint(0f, 0f)
    @JvmField val frameSpeed = MutFPoint(0f, 0f)
    @JvmField var startedDeath = false
    @JvmField var passedTarget = false
    @JvmField var hitTarget = false
    @JvmField var missed = false
    @JvmField var entryAngle = -1f
    @JvmField var constructing = false
    @JvmField var initialized = false
    @JvmField var spaceKnown = false
    @JvmField var name: String = ""
    @JvmField var def: HsDamageDef = HsDamageDef.DEFAULT
    @JvmField var accuracyMod = 0
    @JvmField var droneAccuracyMod = 0
    /** CustomDamage::sourceShipId: the owner's ship id (set from Projectile::ownerId before each use). */
    @JvmField var sourceShipId = -1
    /** Native Projectile_Extend::missedDrones stores SpaceIds; Lua vector writes affect the collision cache. */
    @JvmField val missedDrones = ArrayList<Int>()
    /** The Lua view of this extend (HsWeaponsLua.ExtendView), made on first use. */
    @JvmField var luaView: Any? = null

    /** Projectile::Initialize(bp) hook (after super): name, damage definition and accuracies of [bp]. */
    fun initialize(bp: AbstractWeaponBlueprint) {
        name = bp.name
        val d = bp.hs?.damage ?: HsDamageDef.DEFAULT
        def = d
        accuracyMod = d.accuracyMod
        droneAccuracyMod = d.droneAccuracyMod
    }

    fun missed(drone: xyz.znix.xftl.drones.AbstractDrone): Boolean = drone.hsSelfId in missedDrones

    fun addMissed(drone: xyz.znix.xftl.drones.AbstractDrone) {
        missedDrones.add(drone.hsSelfId)
    }

    companion object {
        /**
         * ProjectileFactory::SaveProjectile hook (CustomDamage.cpp): after the projectile's own state, the extend's
         * name, the CustomDamageDefinition index, accuracyMod, droneAccuracyMod and the SpaceIds of the drones it
         * already missed (the `hsExt` child; absent in vanilla saves).
         */
        @JvmStatic
        fun save(ext: HsProjectileExt, elem: org.jdom2.Element) {
            val node = org.jdom2.Element("hsExt")
            node.setAttribute("name", ext.name)
            node.setAttribute("def", ext.def.idx.toString())
            node.setAttribute("accuracyMod", ext.accuracyMod.toString())
            node.setAttribute("droneAccuracyMod", ext.droneAccuracyMod.toString())
            if (ext.missedDrones.isNotEmpty()) node.setAttribute("missedDrones", ext.missedDrones.joinToString(","))
            elem.addContent(node)
        }

        /**
         * ProjectileFactory::LoadProjectile hook: sourceShipId from the owner, then the saved name, definition (by
         * index, `customDamageDefs.at`), accuracies and missed drones replace what the reloaded projectile's
         * blueprint gave it.
         */
        @JvmStatic
        fun load(ext: HsProjectileExt, elem: org.jdom2.Element) {
            val node = elem.getChild("hsExt") ?: return
            node.getAttributeValue("name")?.let { ext.name = it }
            node.getAttributeValue("def")?.toIntOrNull()?.let { HsDamageDef.defs.getOrNull(it)?.let { d -> ext.def = d } }
            node.getAttributeValue("accuracyMod")?.toIntOrNull()?.let { ext.accuracyMod = it }
            node.getAttributeValue("droneAccuracyMod")?.toIntOrNull()?.let { ext.droneAccuracyMod = it }
            ext.missedDrones.clear()
            node.getAttributeValue("missedDrones")?.split(',')?.mapNotNullTo(ext.missedDrones) { it.trim().toIntOrNull() }
        }

        @JvmStatic
        fun of(bp: AbstractWeaponBlueprint?): HsProjectileExt? {
            if (!HS.active) return null
            val e = HsProjectileExt()
            if (bp != null) {
                e.initialize(bp)
                e.damage.copyFrom(Damage(bp))
                e.speedMagnitude = (bp.speed ?: 0).toFloat()
            }
            return e
        }
    }
}
