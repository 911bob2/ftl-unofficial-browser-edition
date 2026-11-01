// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomWeapons.cpp (ProjectileFactory::Update
// rewrite, ProjectileFactory::GetProjectile hooks) and FTL 1.6.14 ProjectileFactory::GetProjectile 0x40d120.
package xyz.znix.xftl.weapons

/**
 * Hyperspace ProjectileFactory::queuedProjectiles: a non-beam launcher constructs the whole volley when it fires
 * (Update), and each GetProjectile hands out the back of the queue. The engine calls [hsGetProjectile] at the fire
 * frames the way ShipManager drains the factory; Lua reaches the same queue and the same method.
 */
interface HsProjectileQueue {
    val hsQueue: MutableList<AbstractProjectile>

    /** ProjectileFactory::bFiredOnce (0x610): set by every GetProjectile pop. */
    var hsFiredOnce: Boolean

    /** ProjectileFactory::GetProjectile with all Hyperspace hooks; null when nothing leaves. */
    fun hsGetProjectile(): AbstractProjectile?

    companion object {
        /** CustomWeapons.cpp `simultaneousFireWeapon`: the factory releasing its whole queue this frame. */
        @JvmStatic var simultaneousFireWeapon: Any? = null
    }
}
