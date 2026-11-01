// Hyperspace (GPL-3.0-or-later) 1b05a07 lua/modules/hyperspace.i HackingSystem::drone: the HackingDrone the hacking
// system embeds (HackingSystem constructor 0x62d9a0, 1.6.14), one object for the system's whole life.
package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.math.FPoint
import xyz.znix.xftl.systems.Hacking
import xyz.znix.xftl.weapons.DroneBlueprint

/** The hacking system's HackingDrone as a native Drone/SpaceDrone body; its flight is the system's probe actor. */
class HackingNativeDrone(val hacking: Hacking, bp: DroneBlueprint) : NativeDrone(bp, null, hacking.hsDroneSelfId) {
    val body get() = hacking.hsCollisionBody

    init {
        // HackingSystem constructor (0x62d9a0, call at 0x62daca): HackingDrone::SetCurrentLocation (0x4520f0) puts
        // the drone (currentLocation and startingPosition) at the centre of the system's room shape, so it reads a
        // real point before its first launch (oracle wp4-drone-body hacking-drone: 420,175).
        hacking.room?.pixelCentre?.let { storedLocation.set(it.x.toFloat(), it.y.toFloat()) }
    }

    override val luaDeployed: Boolean get() = body?.collisionDeployed == true
    override var luaPowered: Boolean
        get() = body?.collisionPowered == true
        set(value) { storedPowered = value }
    override val luaOwner: Ship? get() = hacking.room?.ship
    override val luaSelfId: Int get() = hacking.hsDroneSelfId
    override fun luaLocation(): FPoint = if (hacking.droneLaunched) body?.collisionPosition ?: storedLocation else storedLocation
    override fun luaSetLocation(x: Float, y: Float) { storedLocation.set(x, y) }
    override val luaCurrentSpace: Int get() = body?.collisionSpaceId ?: shipId
    override val luaExploding: Boolean get() = body?.collisionExploding == true
    override var luaIonStun: Float
        get() = if (hacking.droneLaunched) body?.collisionIonStun ?: 0f else 0f
        set(value) { if (hacking.droneLaunched) body?.collisionIonStun = value }
}
