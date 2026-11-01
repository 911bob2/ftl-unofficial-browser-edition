// Native 1.6.14 Projectile constructor 0x4598e0 / Initialize 0x45a430, Targetable thunks 0x85d210-0x85d280 and
// Targetable/Collideable defaults 0x756ce0-0x756ef0 / 0x757900-0x757990 (same rules in 1.6.9, which HS hooks).
// Member names follow FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i.
package xyz.znix.xftl.weapons

/**
 * Projectile members that only scripts, rendering and saves read: allocated on first use, so vanilla projectiles
 * that nobody inspects carry no extra state.
 */
class ProjectileNativeFields {
    /** Projectile::hitSolidSound / hitShieldSound / missSound: one entry of each blueprint list, drawn by Initialize. */
    var hitSolidSound = ""
    var hitShieldSound = ""
    var missSound = ""
    /** Projectile::bBroadcastTarget (HS CreateBurstProjectile sets it for player-owned real pieces). */
    var broadcastTarget = false
    /**
     * Projectile::flashTracker: the constructor (0x4598e0) makes it a 2 s tracker, SetLoop(true, 0) and Start(0)
     * (the HS oracle reads time 2, loop and running on a fresh laser). Projectile::OnRender advances it natively;
     * here only a script's OnRender does.
     */
    val flashTracker = ProjectileDeathAnimation().also { it.time = 2f; it.loop = true; it.running = true; it.frameCount = 0 }
    /** Targetable::hostile / targeted. */
    var hostile = true  // the HS oracle reads true for every family right after construction
    var targeted = false
}

/** Native Collideable / Targetable values of a body that does not override them. */
object NativeCollideableDefaults {
    /** Collideable::GetSuperShield default (0x757940): both coordinates are -1 (float at 0x87719c). */
    const val NO_SUPER_SHIELD = -1f
}
