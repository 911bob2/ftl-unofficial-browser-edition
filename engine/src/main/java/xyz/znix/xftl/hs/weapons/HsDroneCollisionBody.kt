// HS GPL-3.0-or-later 1b05a07 CustomDamage.cpp SpaceDrone/BoarderPodDrone/HackingDrone collision hooks.
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.FPoint

/** The same native SpaceDrone collision state is represented by orbiting actors and by flying pod actors.
 * Every property delegates to the gameplay body; the Lua receiver is its native drone, not a Projectile proxy. */
interface HsDroneCollisionBody {
    val collisionReceiver: Any
    val collisionOwner: Ship
    val collisionSpaceId: Int
    val collisionSelfId: Int
    val collisionPosition: FPoint
    val collisionDodge: Int
    val collisionPowered: Boolean
    val collisionEnabled: Boolean
    val collisionExploding: Boolean
    var collisionIonStun: Float
    fun collisionBlowUp()
}

interface HsHackingDroneCollisionBody : HsDroneCollisionBody {
    /** The hacking system that owns this drone (its Lua body is DroneRuntime.hackingBody of it). */
    val hackingSystem: xyz.znix.xftl.systems.Hacking
    var arrived: Boolean
    val collisionDeployed: Boolean
    val blueprint: xyz.znix.xftl.weapons.DroneBlueprint
    fun resetCollisionDeath()
}
