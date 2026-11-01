// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/CustomDamage.cpp
// (ShipManager::DamageArea, DamageBeam, DamageCrew hooks; CustomDamageManager), src/game/Ship/ShipSystems/ErosionEffect.cpp
// (Shields::CollisionReal hook)
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.crew.CrewSpawn
import xyz.znix.xftl.hs.crew.spawnCrew
import xyz.znix.xftl.hs.stat.StatBoostDefinition
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.weapons.IProjectile

/**
 * Hyperspace's custom damage effects of a hit (CustomDamageDefinition): crew spawns, crew and room stat boosts,
 * erosion. HS keeps the damage being resolved in CustomDamageManager::currentWeaponDmg / currentProjectile while a
 * projectile updates or collides; [current] / [currentProjectile] are the same, set around the engine's hit code.
 * The effects belong to other feature families and are reached through [handlers].
 */
object HsDamage {
    /** CustomDamageManager::currentWeaponDmg: the extend of the projectile being resolved, or null. */
    @JvmField var current: HsProjectileExt? = null
    /** CustomDamageManager::currentProjectile. */
    @JvmField var currentProjectile: Any? = null

    /** The effects; [Default] calls the owning families. Tests may replace it. */
    interface Handlers {
        fun spawnCrew(spawn: CrewSpawn, ship: Ship, intruder: Boolean, location: IPoint, tile: Boolean)
        fun crewStatBoost(def: StatBoostDefinition, crew: AbstractCrew, sourceShipId: Int)
        fun roomStatBoost(def: StatBoostDefinition, ship: Ship, room: Room, sourceShipId: Int)
        fun erosion(effect: HsErosion, room: Room)
    }

    object Default : Handlers {
        /**
         * CrewSpawn::SpawnCrew(spawn, ship, intruder, location[, tile]) through hs.crew. The tile variant (beams)
         * walks the crew to the free slot nearest the point.
         */
        override fun spawnCrew(spawn: CrewSpawn, ship: Ship, intruder: Boolean, location: IPoint, tile: Boolean) {
            xyz.znix.xftl.hs.crew.HsCrew.spawnCrew(spawn, ship, intruder, location.x.toFloat(), location.y.toFloat(), tile)
        }

        /** `StatBoost statBoost(def); statBoost.sourceShipId = ...; CreateTimedAugmentBoost(statBoost, crew)`. */
        override fun crewStatBoost(def: StatBoostDefinition, crew: AbstractCrew, sourceShipId: Int) {
            val b = xyz.znix.xftl.hs.stat.StatBoost(def)
            b.sourceShipId = sourceShipId
            xyz.znix.xftl.hs.stat.StatBoostManager.createTimedAugmentBoost(b, crew)
        }

        /** ShipManager_Extend::CreateRoomStatBoost(def, room, 1, sourceShipId). */
        override fun roomStatBoost(def: StatBoostDefinition, ship: Ship, room: Room, sourceShipId: Int) {
            xyz.znix.xftl.hs.stat.StatBoostManager.createRoomStatBoost(def, room, 1, sourceShipId)
        }

        /** Room_Extend::StartErosion (hs.systems). */
        override fun erosion(effect: HsErosion, room: Room) {
            xyz.znix.xftl.hs.systems.HsErosionRoom.start(room, effect)
        }
    }

    @JvmField var handlers: Handlers = Default

    /** Run [block] with [ext]/[proj] as the damage being resolved (HS_CollisionCheck / UpdateProjectile). */
    inline fun <T> with(ext: HsProjectileExt?, proj: Any?, block: () -> T): T {
        val prevExt = current
        val prevProj = currentProjectile
        current = ext
        currentProjectile = proj
        try {
            return block()
        } finally {
            current = prevExt
            currentProjectile = prevProj
        }
    }

    /**
     * ShipManager::DamageArea after super, when the hit landed (ret true) and the damage has a source ship:
     * `random32() % 10 < crewSpawnChance` spawns every crew spawn at the location (intruders unless the source is
     * this ship); then, on a room, `random32() % 10 < roomStatBoostChance` adds every room stat boost and
     * `random32() % 10 < erosionChance` starts erosion. [sourceShipId] is the projectile owner's id.
     */
    @JvmStatic
    fun areaHit(ship: Ship, room: Room?, location: IPoint, sourceShipId: Int) {
        val custom = current ?: return
        custom.sourceShipId = sourceShipId
        if (sourceShipId == -1) return
        val def = custom.def
        val shipId = if (ship.isPlayerShip) 0 else 1
        if (HsWeapons.r32() % 10 < def.crewSpawnChance) {
            for (spawn in def.crewSpawnDefs) handlers.spawnCrew(spawn, ship, sourceShipId != shipId, location, false)
        }
        if (room != null) {
            if (HsWeapons.r32() % 10 < def.roomStatBoostChance) {
                for (boost in def.roomStatBoostDefs) handlers.roomStatBoost(boost, ship, room, sourceShipId)
            }
            if (HsWeapons.r32() % 10 < def.erosionChance) handlers.erosion(def.erosion, room)
        }
    }

    /**
     * ShipManager::DamageBeam after super: on a new tile or room, if crewSpawnChance > 0 and the damage has a
     * source, `random32() % 10` against it spawns the crew on the tile's centre; on a new room, the room stat boost
     * and erosion rolls as for DamageArea. [newRoom] = BeamHit::NEW_ROOM, otherwise NEW_TILE.
     */
    @JvmStatic
    fun beamHit(ship: Ship, room: Room?, tileCentre: IPoint, newRoom: Boolean, sourceShipId: Int) {
        val custom = current ?: return
        custom.sourceShipId = sourceShipId
        val def = custom.def
        val shipId = if (ship.isPlayerShip) 0 else 1
        if (sourceShipId != -1 && def.crewSpawnChance > 0) {
            if (HsWeapons.r32() % 10 < def.crewSpawnChance) {
                for (spawn in def.crewSpawnDefs) handlers.spawnCrew(spawn, ship, sourceShipId != shipId, tileCentre, true)
            }
        }
        if (room != null && newRoom) {
            if (HsWeapons.r32() % 10 < def.roomStatBoostChance) {
                for (boost in def.roomStatBoostDefs) handlers.roomStatBoost(boost, ship, room, sourceShipId)
            }
            if (HsWeapons.r32() % 10 < def.erosionChance) handlers.erosion(def.erosion, room)
        }
    }

    /**
     * ShipManager::DamageCrew (priority -100, before super), for every crew member handed to it while a
     * projectile's damage is current: `random32() % 10 < statBoostChance` gives the crew member every stat boost.
     * (noPersDamage is applied by the engine's Damage.effectiveCrewDamage.)
     */
    @JvmStatic
    fun crewDamaged(crew: AbstractCrew) {
        val custom = current ?: return
        val def = custom.def
        if (HsWeapons.r32() % 10 < def.statBoostChance) {
            for (boost in def.statBoostDefs) handlers.crewStatBoost(boost, crew, custom.sourceShipId)
        }
    }

    /**
     * Shields::CollisionReal (ErosionEffect.cpp), after a shield hit (collision type 2) while a projectile's damage
     * is current and its effect erodes shields: unless an ion-beam-fix beam has not reached the super shield
     * ([beamOnSuperShield] false for such a beam), `random32() % 10 < erosionChance` erodes the shield room.
     */
    @JvmStatic
    fun shieldHit(ship: Ship, isIonFixBeamOffSuperShield: Boolean) {
        val custom = current ?: return
        val def = custom.def
        if (!def.erosion.erodeShields) return
        if (def.ionBeamFix && isIonFixBeamOffSuperShield) return
        if (HsWeapons.r32() % 10 < def.erosionChance) {
            val room = ship.shields?.room ?: return
            handlers.erosion(def.erosion, room)
        }
    }
}
