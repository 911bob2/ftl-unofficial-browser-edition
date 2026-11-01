// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 Projectile_Extend.cpp / CustomDamage.cpp.
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.ConstFPoint
import xyz.znix.xftl.math.IPoint

/** Constructor arguments known by the spawning factory before the derived projectile exists. Nested Lua spawns
 * restore the preceding context; callbacks run from the real base constructor, before derived initialization. */
object HsProjectileConstruction {
    data class Context(val position: ConstFPoint, val target: IPoint, val currentSpace: Ship, val ownerId: Int,
                       val currentSpaceId: Int = HsWeapons.shipId(currentSpace), val destinationSpaceId: Int? = null)
    @JvmField var current: Context? = null

    inline fun <T> with(context: Context, create: () -> T): T {
        val before = current
        current = context
        try { return create() } finally { current = before }
    }
}
