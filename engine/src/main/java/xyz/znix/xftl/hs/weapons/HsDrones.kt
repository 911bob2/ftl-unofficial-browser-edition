// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDamage.cpp (SpaceDrone::CollisionMoving)
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.drones.AbstractExternalDrone
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.weapons.IProjectile

/** Hyperspace's drone rules (CustomDrones.cpp / the drone parts of CustomDamage.cpp). */
object HsDrones {
    fun collisionBody(value: Any?): HsDroneCollisionBody? = when (value) {
        is xyz.znix.xftl.drones.HackingNativeDrone -> value.hacking.hsCollisionBody
        // A deployed boarder's collision state is its flying pod; any other body answers for its actor.
        is xyz.znix.xftl.drones.NativeDrone -> (value.instance as? xyz.znix.xftl.drones.BoardingDrone)?.hsCollisionBody ?: value
        is HsDroneCollisionBody -> value
        is xyz.znix.xftl.drones.BoardingDrone -> value.hsCollisionBody
        is xyz.znix.xftl.systems.Hacking -> value.hsCollisionBody
        else -> null
    }

    /** HS 1b05a07 CustomDamage.cpp:720-798. Damage is passed by value; Lua edits affect this collision only.
     * Receiver effects are independent of response.collision_type, which CollisionCheck consumes on the caller. */
    fun collisionMoving(drone: HsDroneCollisionBody, finish: xyz.znix.xftl.math.FPoint,
                        incoming: xyz.znix.xftl.weapons.Damage): xyz.znix.xftl.weapons.ProjectileCollisionResponse {
        if (drone is HsHackingDroneCollisionBody) {
            if (!drone.collisionDeployed || drone.arrived) return xyz.znix.xftl.weapons.ProjectileCollisionResponse()
            // CustomDamage.cpp HackingDrone::CollisionMoving clears bDead after calling the base,
            // including when the base returns without hitting or a callback preempts the effect.
            try { return collisionMovingBase(drone, finish, incoming) }
            finally { drone.resetCollisionDeath() }
        }
        return collisionMovingBase(drone, finish, incoming)
    }

    private fun collisionMovingBase(drone: HsDroneCollisionBody, finish: xyz.znix.xftl.math.FPoint,
                                    incoming: xyz.znix.xftl.weapons.Damage): xyz.znix.xftl.weapons.ProjectileCollisionResponse {
        val response = xyz.znix.xftl.weapons.ProjectileCollisionResponse()
        if (!drone.collisionEnabled || !IProjectile.endpointContact(finish, drone.collisionPosition)) return response
        val ext = HsWeaponsLua.extOf(HsDamage.currentProjectile)
        if (ext != null && drone.collisionSelfId in ext.missedDrones) return response
        val dodge = drone.collisionDodge * 10 - (HsDamage.current?.droneAccuracyMod ?: 0)
        val roll = HsWeapons.r32() % 100
        // random32 is unsigned: C++ promotes a negative signed threshold to a large unsigned value.
        // Thus accuracy above dodge causes a guaranteed miss, not a clamp to zero (oracle pod probe).
        if (dodge < 0 || roll < dodge) {
            val owner = drone.collisionOwner
            val space = if (HsWeapons.shipId(owner) == drone.collisionSpaceId) owner else owner.sys.getEnemyOf(owner)
            space?.showDamageTextAt(drone.collisionPosition, "text_miss", Colour.white)
            ext?.missedDrones?.add(drone.collisionSelfId)
            return response
        }
        response.type = 1
        val damage = incoming.copy()
        if (HsWeaponHooks.droneCollision(drone.collisionReceiver, damage, response)) return response
        if (damage.hullDamage > 0) drone.collisionBlowUp()
        else if (damage.ionDamage > 0 && (drone.collisionPowered || xyz.znix.xftl.hs.HS.cfg().droneIonDodgeFix))
            drone.collisionIonStun = maxOf(drone.collisionIonStun, damage.ionDamage * 5f)
        return response
    }

    /**
     * SpaceDrone::CollisionMoving, HS rewrite (priority 9999, in place of the original): a drone that is exploding
     * is not hit; a projectile that already missed this drone passes it ([HsProjectileExt.missedDrones]);
     * otherwise one dodge roll, `random32() % 100 < dodge * 10 - droneAccuracyMod`, whether or not the drone is
     * powered (vanilla: the roll counts only for a powered drone, and is made again on every frame of contact).
     * A miss shows "miss" on the drone and is remembered. The engine's ownership and anti-drone checks come
     * first, as in its vanilla path (the projectile's CollisionCheck decides which objects it can touch).
     */
    @JvmStatic
    fun canCollide(drone: AbstractExternalDrone, proj: IProjectile): Boolean {
        if (drone.isBlowingUp) return false
        if (proj.antiDroneExemption == drone.ownerShip) return false
        if (proj.antiDroneBP == null) return false
        val ext = proj.hsExt
        if (ext != null && ext.missed(drone)) return false
        val dodgeFactor = (drone.type.dodge ?: 0) * 10 - (ext?.droneAccuracyMod ?: 0)
        val roll = HsWeapons.r32() % 100
        if (dodgeFactor < 0 || roll < dodgeFactor) {
            drone.targetShip.showDamageTextAt(drone.flightController.position, "text_miss", Colour.white)
            ext?.addMissed(drone)
            return false
        }
        return true
    }
    /**
     * DefenseDrone::PickTarget with `<defenseDroneFix>` (CustomDrones.cpp), once the gun is on the led aim point:
     * a drone target is always shot when combatDroneAlwaysTargetable (per owner), otherwise the point must lie in
     * the ship's room bounding box grown by boxRange, or inside the shield ellipse grown by ellipseRange (both per
     * owner: player 0, enemy 1). Vanilla uses the fixed -150..450 box instead.
     */
    @JvmStatic
    fun defenceInRange(id: Int, space: xyz.znix.xftl.Ship, target: Any?, point: xyz.znix.xftl.math.FPoint): Boolean {
        val cfg = xyz.znix.xftl.hs.HS.cfg()
        if (cfg.combatDroneAlwaysTargetable[id] && target is xyz.znix.xftl.drones.AbstractExternalDrone) return true
        val rooms = space.rooms
        val x0: Float; val y0: Float; val x1: Float; val y1: Float
        if (rooms.isNotEmpty()) {
            val r = cfg.defenseDroneBoxRange[id]
            x0 = rooms.minOf { it.offsetX }.toFloat() - r
            y0 = rooms.minOf { it.offsetY }.toFloat() - r
            x1 = rooms.maxOf { it.offsetX + it.width * 35 }.toFloat() + r
            y1 = rooms.maxOf { it.offsetY + it.height * 35 }.toFloat() + r
        } else {
            x0 = -150f; y0 = -150f; x1 = 450f; y1 = 450f
        }
        if (point.xf > x0 && point.yf > y0 && point.xf < x1 && point.yf < y1) return true
        val e = cfg.defenseDroneEllipseRange[id]
        val a = space.shieldHalfSize.x + e
        val b = space.shieldHalfSize.y + e
        val rx = point.xf - space.shieldOrigin.x
        val ry = point.yf - space.shieldOrigin.y
        return rx * rx / (a * a) + ry * ry / (b * b) < 1f
    }
    // ------------------------------------------------------------------ custom drones (CustomDroneManager)

    class CustomDrone(val name: String, val crewBlueprint: String, val tooltipName: String)

    private var customDrones: Map<String, CustomDrone>? = null

    /** CustomDroneManager::ParseDroneNode: hyperspace.xml `<drones><drone name><crewBlueprint><tooltipName>`. */
    @JvmStatic
    fun customDrones(): Map<String, CustomDrone> {
        customDrones?.let { return it }
        val map = LinkedHashMap<String, CustomDrone>()
        if (xyz.znix.xftl.hs.HS.active) for (node in xyz.znix.xftl.hs.HS.cfg().nodesOf("drones")) for (d in node.getChildren("drone")) {
            val name = d.getAttributeValue("name") ?: continue
            // GetDefinition returns the first definition of a name (droneDefs is searched in order)
            if (name !in map) map[name] = CustomDrone(name, d.getChildText("crewBlueprint") ?: "", d.getChildText("tooltipName") ?: "")
        }
        customDrones = map
        return map
    }

    @JvmStatic
    fun reset() { customDrones = null; droneRaces = null }

    /** CustomDrones.cpp tests for a definition even when its crewBlueprint is empty. */
    fun hasCustomDefinition(droneName: String): Boolean =
        xyz.znix.xftl.hs.HS.active && customDrones().containsKey(droneName)

    private var droneRaces: Set<String>? = null

    /**
     * A race that animates as a drone: any custom drone's crew blueprint and the anti-personnel drone's race. It shoots
     * in melee rather than punches, whichever strips it has, unless its own canPunch says otherwise.
     */
    @JvmStatic
    fun shootsInMelee(race: String): Boolean {
        val races = droneRaces ?: (customDrones().values.mapTo(HashSet()) { it.crewBlueprint } + xyz.znix.xftl.drones.AntiPersonnelDrone.PAWN_RACE).also { droneRaces = it }
        return race in races
    }

    /** The race of a custom drone, or null (no definition, or an empty crewBlueprint). */
    @JvmStatic
    fun customRace(droneName: String): String? = customDrones()[droneName]?.crewBlueprint?.takeIf { it.isNotEmpty() }
}
