// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/ script API and .i members, src/ files named below),
// not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.drones.BoardingDrone
import xyz.znix.xftl.drones.CombatDrone
import xyz.znix.xftl.drones.DefenceDrone
import xyz.znix.xftl.drones.HullRepairDrone
import xyz.znix.xftl.drones.ShieldDrone
import xyz.znix.xftl.environment.AsteroidProjectile
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.weapons.LaserBlueprint
import xyz.znix.xftl.weapons.MissileBlueprint

/**
 * The most derived Lua type HS pushes for an object (SWIG's DYNAMIC typemaps and LuaLibScript::GetShipSystemType):
 * SpaceDrone/Drone by drone type, Projectile by Projectile::GetType, ShipSystem by system id.
 */
object LuaTypes {
    @JvmStatic
    fun dynamic(o: Any, staticType: Int): Int {
        if (staticType == A.T_Projectile && o is xyz.znix.xftl.weapons.AbstractProjectile && o.hsExt?.constructing == true)
            return A.T_Projectile
        if (o is xyz.znix.xftl.drones.NativeDrone || o is xyz.znix.xftl.drones.AbstractDrone) return LuaAdaptersDrones.dynamicType(o, staticType)
        if (o is LuaTyped) return o.luaType
        return when (staticType) {
            A.T_SpaceDrone, A.T_Drone -> when (o) {
                is DefenceDrone -> A.T_DefenseDrone
                is CombatDrone -> A.T_CombatDrone
                is BoardingDrone -> A.T_BoarderPodDrone
                is HullRepairDrone -> A.T_ShipRepairDrone
                is ShieldDrone -> A.T_SuperShieldDrone
                else -> staticType   // repair and battle drones: HS has no Lua type (nullptr), SWIG keeps the static one
            }
            // HS Projectile_dynamic_cast (hyperspace.i): types.pProjectile[GetType()].
            A.T_Projectile -> if (o is xyz.znix.xftl.weapons.AbstractProjectile) when (o.nativeType) {
                1 -> A.T_LaserBlast
                2 -> A.T_Asteroid
                3 -> A.T_Missile
                4 -> A.T_BombProjectile
                5 -> A.T_BeamWeapon
                6 -> A.T_PDSFire
                else -> staticType
            } else staticType
            // ShipSystem* has no DYNAMIC typemap in HS: GetSystem, vSystemList and the events push "ShipSystem *"
            // (MV's instant-clone-heal reads swig_type(ship:GetSystem(5)) as ShipSystem). Only ShipSystem::AddDamage
            // (SYSTEM_ADD_DAMAGE, CustomDamage.cpp) pushes GetShipSystemType's subtype: use [systemType] there.
            A.T_ShipSystem -> staticType
            0 -> staticType
            else -> staticType
        }
    }

    /** LuaLibScript::GetShipSystemType: engines, piloting, sensors, doors and the custom ids stay ShipSystem. */
    @JvmStatic
    fun systemType(o: Any): Int = when (o) {
        is Shields -> A.T_Shields
        is Oxygen -> A.T_OxygenSystem
        is Weapons -> A.T_WeaponSystem
        is Drones -> A.T_DroneSystem
        is Medbay -> A.T_MedbaySystem
        is Teleporter -> A.T_TeleportSystem
        is Cloaking -> A.T_CloakingSystem
        is Artillery -> A.T_ArtillerySystem
        is BackupBattery -> A.T_BatterySystem
        is Clonebay -> A.T_CloneSystem
        is MindControl -> A.T_MindSystem
        is Hacking -> A.T_HackingSystem
        else -> A.T_ShipSystem
    }
}
