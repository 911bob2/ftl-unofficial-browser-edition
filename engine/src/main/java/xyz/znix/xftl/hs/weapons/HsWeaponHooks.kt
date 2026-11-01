// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 lua/InternalEvents.cpp and
// src/game/Gameplay/CustomDamage.cpp. Keep each callback at the native operation it wraps.
package xyz.znix.xftl.hs.weapons

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaPush as ret
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

object HsWeaponHooks {
    class CollisionView(val response: xyz.znix.xftl.weapons.ProjectileCollisionResponse) : LuaTyped {
        override val luaType get() = A.T_CollisionResponse
    }

    fun projectileCollision(target: Any, damage: xyz.znix.xftl.weapons.Damage,
                            response: xyz.znix.xftl.weapons.ProjectileCollisionResponse): Boolean =
        collision(A.EV_PROJECTILE_COLLISION, target, A.T_Projectile, damage, response)

    fun droneCollision(target: Any, damage: xyz.znix.xftl.weapons.Damage,
                       response: xyz.znix.xftl.weapons.ProjectileCollisionResponse): Boolean =
        collision(A.EV_DRONE_COLLISION, target, A.T_SpaceDrone, damage, response)

    fun shieldCollision(event: Int, ship: Ship, damage: xyz.znix.xftl.weapons.Damage,
                        view: CollisionView): Boolean = collision(event, ship, A.T_ShipManager, damage, view.response, view)

    private fun collision(event: Int, target: Any, targetType: Int, damage: xyz.znix.xftl.weapons.Damage,
                          response: xyz.znix.xftl.weapons.ProjectileCollisionResponse,
                          sharedView: CollisionView? = null): Boolean {
        if (!HS.luaActive || !HsLua.hasInt[event]) return false
        val value = sharedView ?: collisionView(response)
        ret.obj(target, targetType); ret.obj(HsDamage.currentProjectile, A.T_Projectile)
        ret.obj(damage, A.T_Damage); ret.obj(value, A.T_CollisionResponse)
        val preempt = LuaJs.fireChain(event, 4, 0)
        LuaJs.pop(4)
        return preempt
    }

    fun collisionView(response: xyz.znix.xftl.weapons.ProjectileCollisionResponse) = CollisionView(response)

    /** Initialize is after the native blueprint copy and the HS damage definition/accuracy assignment. */
    fun projectileInitialize(projectile: Any, blueprint: AbstractWeaponBlueprint) {
        HsWeaponsLua.extOf(projectile)?.initialize(blueprint)
        if (!HS.luaActive || !HsLua.hasInt[A.EV_PROJECTILE_INITIALIZE]) return
        ret.obj(projectile, A.T_Projectile); ret.obj(blueprint, A.T_WeaponBlueprint)
        LuaJs.fireChain(A.EV_PROJECTILE_INITIALIZE, 2, 0); LuaJs.pop(2)
    }

    fun projectileBefore(event: Int, projectile: Any): Boolean {
        if (!HS.luaActive || !HsLua.hasInt[event]) return false
        ret.obj(projectile, A.T_Projectile)
        val preempt = LuaJs.fireChain(event, 1, 0)
        LuaJs.pop(1)
        return preempt
    }

    fun projectileAfter(event: Int, projectile: Any, preempt: Boolean) {
        if (!HS.luaActive || !HsLua.hasInt[event]) return
        ret.obj(projectile, A.T_Projectile); LuaJs.pushboolean(preempt)
        LuaJs.fireChain(event, 2, 0); LuaJs.pop(2)
    }

    /** Constructors push exactly the base userdata type; derived virtuals and members are not ready yet. */
    fun constructProjectile(projectile: Any) = construct(A.EV_CONSTRUCT_PROJECTILE, projectile, A.T_Projectile)
    fun constructSpaceDrone(drone: Any) = construct(A.EV_CONSTRUCT_SPACEDRONE, drone, A.T_SpaceDrone)

    private fun construct(event: Int, value: Any, baseType: Int) {
        if (!HS.luaActive || !HsLua.hasInt[event]) return
        LuaJs.pushobj(Handles.expose(value), baseType)
        LuaJs.fire(event, 1, 0); LuaJs.pop(1)
    }

    fun dodgeFactor(ship: Ship, value: Int): Int {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_DODGE_FACTOR]) return value
        ret.obj(ship, A.T_ShipManager); LuaJs.pushinteger(value.toDouble())
        // HS ignores the preemption result here, but still observes chain halt and a numeric replacement.
        LuaJs.fireChain(A.EV_GET_DODGE_FACTOR, 2, 1)
        val result = if (LuaJs.isnumber(-1)) LuaJs.tonumber(-1).toInt() else value
        LuaJs.pop(2)
        return result
    }

    /** null prevents AddDamage only; IonDamage is a separate native operation. */
    fun systemAddDamage(system: AbstractSystem, amount: Int): Int? {
        if (!HS.luaActive || system.room?.ship?.sys?.isCurrentlyLoadingSave == true || !HsLua.hasInt[A.EV_SYSTEM_ADD_DAMAGE]) return amount
        ret.obj(system, LuaTypes.systemType(system))
        ret.obj(HsDamage.currentProjectile, A.T_Projectile); LuaJs.pushinteger(amount.toDouble())
        val preempt = LuaJs.fireChain(A.EV_SYSTEM_ADD_DAMAGE, 3, 1)
        // Unlike GET_DODGE_FACTOR, this hook accepts integer replacements only.
        val result = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else amount
        LuaJs.pop(3)
        return if (preempt) null else result
    }

    fun selectArmamentPre(slot: Int): Int? {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_SELECT_ARMAMENT_PRE]) return slot
        LuaJs.pushinteger(slot.toDouble())
        val preempt = LuaJs.fireChain(A.EV_SELECT_ARMAMENT_PRE, 1, 1)
        val selected = if (LuaJs.isnumber(-1)) LuaJs.tonumber(-1).toInt() else slot
        LuaJs.pop(1)
        return if (preempt) null else selected
    }

    fun selectArmamentPost(slot: Int) {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_SELECT_ARMAMENT_POST]) return
        LuaJs.pushinteger(slot.toDouble()); LuaJs.fireChain(A.EV_SELECT_ARMAMENT_POST, 1, 0); LuaJs.pop(1)
    }
}
