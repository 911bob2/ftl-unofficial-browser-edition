package xyz.znix.xftl.weapons

import xyz.znix.xftl.math.MutFPoint

/** CollisionMoving returns a value; callbacks may mutate it before CollisionCheck consumes the result. */
class ProjectileCollisionResponse {
    var type = 0
    val point = MutFPoint(-2147483648f, -2147483648f)
    var damage = 0
    var superDamage = 0
}
