// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (NameToSystemId /
// SystemIdToName), src/game/Ship/ShipSystems/System_Extend.h, src/game/Ship/Room_Extend.h and lua/InternalEvents.cpp
// (SET_BONUS_POWER). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.hs.lua.LuaStored
import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.crew.SkillLevel
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.*
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret
import kotlin.math.ceil
import kotlin.math.max

/**
 * The Lua side of the systems family: ShipSystem members and methods Multiverse's scripts use, the subtype members
 * (CloakingSystem.bTurnedOn, CloneSystem.fTimeToClone/fTimeGoal, MindSystem.controlledCrew, OxygenSystem.oxygenLevels),
 * ShipSystem.extend (ShipSystem_Extend), Room.extend.timeDilation and the SET_BONUS_POWER event.
 * The receiver is the engine system; member names and semantics are FTL's ShipSystem fields.
 */
object HsSystemsLua {
    /** ShipSystem::bUnderAttack (only scripts set it here; the engine's hacking drone shows its own state). */
    private val underAttack = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<AbstractSystem, Boolean>())

    private fun sys(o: Any?) = o as AbstractSystem

    /** powerState.first: the reactor power the system holds (iBonusPower and iBatteryPower are apart). */
    @JvmStatic
    fun reactorPower(s: AbstractSystem): Int = when (s) {
        is MainSystem -> max(0, s.powerSupplied - s.sharedPowerInUse - s.forcedPower)
        is HsCustomSubSystem -> s.effectivePower
        is SubSystem -> s.undamagedEnergy + s.hsPowerWrite
        else -> 0
    }

    /** powerState.first written by a script: the system runs on it while the reactor's tally stays as it was. */
    private fun writePower(s: AbstractSystem, v: Int) {
        when (s) {
            is MainSystem -> {
                val delta = v - reactorPower(s)
                s.hsWritePowerSupplied(s.powerSupplied + delta)
                s.room?.ship?.let { it.hsReactorUsedOffset -= delta }
                // The bar count is its own field natively: the powered weapons and drones do not change it.
                (s as? xyz.znix.xftl.systems.Weapons)?.powerManager?.hsWriteBars(v)
                (s as? xyz.znix.xftl.systems.Drones)?.powerManager?.hsWriteBars(v)
            }
            is SubSystem -> s.hsWritePower(v)
        }
    }

    /** ShipSystem::GetEffectivePower: every bar the system runs on (reactor, battery, bonus; manned subsystems +1). */
    @JvmStatic
    fun effectivePower(s: AbstractSystem): Int = when (s) {
        // ShipSystem::GetEffectivePower adds the battery field, which a Lua ForceBatteryPower writes directly.
        is MainSystem -> s.powerSupplied + (s.hsLuaBatteryPower?.let { it - s.sharedPowerInUse } ?: 0) + luaBonus(s)
        is SubSystem -> s.effectivePower + luaBonus(s)
        else -> 0
    }

    private fun luaBonus(s: AbstractSystem): Int =
        if (LuaStored.active[A.A_ShipSystem_iBonusPower]) ((LuaStored.get(s, A.A_ShipSystem_iBonusPower) as? Double) ?: 0.0).toInt() else 0

    /** Damage a raw healthState write put on the system: the pair changes, the damage GetDamage reports does not. */
    private val rawHealthDamage = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<AbstractSystem, Int>())

    /** iLockCount: -1 for a held lock (cloak, temporal, mind control), else the ion lock in five-second units. */
    @JvmStatic
    fun lockCount(s: AbstractSystem): Int =
        if (s.hasPermanentLock) -1 else ceil(s.ionTimer / AbstractSystem.TIME_PER_ION).toInt()

    /** iActiveManned: 0 unmanned, else the manning level 1..3. */
    @JvmStatic fun activeManned(s: AbstractSystem): Int {
        val skill = when (s) {
            is Shields -> xyz.znix.xftl.crew.Skill.SHIELDS
            is Engines -> xyz.znix.xftl.crew.Skill.ENGINES
            is Piloting -> xyz.znix.xftl.crew.Skill.PILOTING
            is Weapons -> xyz.znix.xftl.crew.Skill.WEAPONS
            else -> null
        }
        val level: SkillLevel? = if (skill != null) s.getSkillLevel(skill) else if (s.manningCrew != null) SkillLevel.BASE else null
        return if (level == null) 0 else level.ordinal + 1
    }

    /** ShipSystem::GetMaxPower: the levels left after damage and the event loss/limit (the ion lock not counted). */
    /** ShipSystem::GetMaxPower with HS's hook (CustomCrew.cpp): iTempPowerLoss from the current additionalPowerLoss. */
    private fun maxPower(s: AbstractSystem): Int {
        var m = s.energyLevels - s.damagedEnergyLevels - (s.hsExt?.liveLoss() ?: s.scriptedPowerLoss)
        s.scriptedPowerLimit?.let { m = minOf(m, it) }
        return max(0, m)
    }

    /**
     * SET_BONUS_POWER(system, amount) -> (Chain, amount) at ShipSystem::SetBonusPower: the scripts change the bonus
     * power [amount] (the Zoltan bars); a PREEMPT keeps the bonus the system had ([previous]).
     */
    /** The system a Lua SetBonusPower call is applying (its power update runs SET_BONUS_POWER for that one only). */
    @JvmField var luaBonusCall: MainSystem? = null

    @JvmStatic
    fun setBonusPower(s: MainSystem, amount: Int, previous: Int): Int {
        if (!HsLua.hasInt[A.EV_SET_BONUS_POWER]) return amount
        // a script's SetBonusPower is ShipSystem::SetBonusPower on that system alone: the others keep their bonus
        // (oracle ps-m1-ev sys|rb: the weapons keep their bar)
        luaBonusCall?.let { if (it !== s) return previous }
        ret.obj(s, A.T_ShipSystem)
        LuaJs.pushinteger(amount.toDouble())
        val preempt = LuaJs.fireChain(A.EV_SET_BONUS_POWER, 2, 1)
        var r = amount
        if (LuaJs.isnumber(-1)) r = LuaJs.tonumber(-1).toInt()
        LuaJs.pop(2)
        return if (preempt) previous else max(0, r)
    }

    /** OxygenSystem::oxygenLevels: the rooms' oxygen in percent, writable (unclamped). */
    private class OxygenLevels(private val ship: Ship) : java.util.AbstractList<Any?>() {
        override val size: Int get() = ship.rooms.size
        override fun get(index: Int): Any? = (ship.rooms[index].oxygen * 100f).toDouble()
        override fun set(index: Int, element: Any?): Any? {
            val room = ship.rooms[index]
            val old = get(index)
            // a vector<float> element write: unclamped, as natively (the next OxygenSystem::OnLoop clamps it)
            room.hsSetOxygenUnclamped(((element as? Number)?.toFloat() ?: 0f).div(100f))
            return old
        }
    }

    private fun shipOf(s: AbstractSystem): Ship? = s.room?.ship ?: s.hsState?.detachedShip

    fun install() {
        // ShipSystem fields
        LuaReg.obj(A.A_ShipSystem_powerState, ObjGet {
            LuaViews.cached(it!!, A.A_ShipSystem_powerState) { o ->
                val s = o as AbstractSystem
                IntPairView(A.T_pair_int_int, IGet { s.hsState?.ctorPower ?: reactorPower(s) }, ISet { writePower(s, it) },
                    IGet { s.hsState?.ctorLevel ?: s.energyLevels }, ISet { s.energyLevels = it })
            }
        })
        LuaReg.obj(A.A_ShipSystem_healthState, ObjGet {
            LuaViews.cached(it!!, A.A_ShipSystem_healthState) { o ->
                val s = o as AbstractSystem
                // SWIG returns a pointer to the native health pair; a raw write to first does not call AddDamage.
                IntPairView(A.T_pair_int_int, IGet { s.hsState?.ctorLevel ?: (s.energyLevels - s.damagedEnergyLevels) },
                    ISet {
                        val damaged = s.energyLevels - it
                        rawHealthDamage[s] = (rawHealthDamage[s] ?: 0) + damaged - s.damagedEnergyLevels
                        s.damagedEnergyLevels = damaged
                    }, IGet { s.hsState?.ctorLevel ?: s.energyLevels }, null)
            }
        })
        LuaReg.num(A.A_ShipSystem_iLockCount, NumGet { lockCount(sys(it)).toDouble() }, NumSet { o, v ->
            val n = v.toInt()
            if (n < 0) HS.unsupported("lua", "ShipSystem.iLockCount", "permanent lock") else sys(o).lockUnits(n)
        })
        LuaReg.num(A.A_ShipSystem_lockTimer, NumGet { (sys(it).ionTimer % AbstractSystem.TIME_PER_ION).toDouble() })
        LuaReg.bool(A.A_ShipSystem_bUnderAttack, { underAttack[sys(it)] == true }, { o, v -> if (v) underAttack[sys(o)] = true else underAttack.remove(sys(o)) })
        LuaReg.num(A.A_ShipSystem_roomId, NumGet { (sys(it).room?.id ?: -1).toDouble() })
        LuaReg.bool(A.A_ShipSystem_bBreached, { sys(it).room?.breaches?.any { b -> b != null } == true })
        LuaReg.bool(A.A_ShipSystem_bOnFire, { sys(it).room?.fires?.any { f -> f != null } == true })
        LuaReg.num(A.A_ShipSystem_iActiveManned, NumGet { activeManned(sys(it)).toDouble() }, NumSet { _, _ ->
            HS.unsupported("lua", "ShipSystem.iActiveManned", "write")
        })
        LuaReg.bool(A.A_ShipSystem_bManned, { activeManned(sys(it)) > 0 }, { _, _ -> HS.unsupported("lua", "ShipSystem.bManned", "write") })
        LuaReg.bool(A.A_ShipSystem_bBoostable, { sys(it).info.canBeManned }, { _, _ -> HS.unsupported("lua", "ShipSystem.bBoostable", "write") })
        LuaReg.bool(A.A_ShipSystem_bNeedsPower, { it is MainSystem })
        LuaReg.bool(A.A_ShipSystem_bNeedsManned, { false })
        LuaReg.num(A.A_ShipSystem_iSystemType, NumGet { nativeSystemId(sys(it)).toDouble() })
        LuaReg.num(A.A_ShipSystem_maxLevel, NumGet { sys(it).maxLevel.toDouble() }, NumSet { o, v -> sys(o).hsMaxLevel = v.toInt() })
        LuaReg.num(A.A_ShipSystem_iBonusPower, NumGet { ((it as? MainSystem)?.forcedPower ?: 0).toDouble() })
        LuaReg.num(A.A_ShipSystem_iBatteryPower, NumGet { ((it as? MainSystem)?.let { s -> s.hsLuaBatteryPower ?: s.sharedPowerInUse } ?: 0).toDouble() })
        LuaReg.num(A.A_ShipSystem_iTempPowerCap, NumGet { (sys(it).scriptedPowerLimit ?: HsPower.CAP_NONE).toDouble() }, NumSet { o, v ->
            val n = v.toInt()
            sys(o).scriptedPowerLimit = if (n > 7) null else max(0, n)
        })
        LuaReg.num(A.A_ShipSystem_iTempPowerLoss, NumGet { val s = sys(it); (s.hsExt?.liveLoss() ?: s.scriptedPowerLoss).toDouble() }, NumSet { o, v ->
            val s = sys(o)
            s.scriptedPowerLoss = max(0, v.toInt() - (s.hsExt?.appliedLoss ?: 0))
        })
        LuaReg.num(A.A_ShipSystem_iTempDividePower, NumGet { 1.0 }, NumSet { _, v -> if (v.toInt() != 1) HS.unsupported("lua", "ShipSystem.iTempDividePower", "write") })
        LuaReg.num(A.A_ShipSystem_iHackEffect, NumGet {
            val s = sys(it)
            if (s.hsHackLevel > 0) s.hsHackLevel.toDouble() else if (s.hackedBy == null) 0.0 else if (s.isHackActive) 2.0 else 1.0
        })
        LuaReg.obj(A.A_ShipSystem__shipObj, ObjGet { shipOf(sys(it)) })
        LuaReg.str(A.A_ShipSystem_name, StrGet { sys(it).codename })
        LuaReg.obj(A.A_ShipSystem_extend, ObjGet { HsSysExt.of(sys(it)) })
        LuaReg.num(A.A_ShipSystem_Extend_additionalPowerLoss, NumGet { (it as HsSysExt).additionalPowerLoss.toDouble() }, NumSet { o, v -> (o as HsSysExt).additionalPowerLoss = v.toInt() })
        LuaReg.num(A.A_ShipSystem_Extend_xOffset, NumGet { (it as HsSysExt).xOffset.toDouble() }, NumSet { o, v -> (o as HsSysExt).xOffset = v.toInt() })

        // ShipSystem methods (1 = self)
        LuaReg.method(A.M_ShipSystem_GetEffectivePower, LuaMethod { ret.num(effectivePower(sys(arg.obj(1)))) })
        LuaReg.method(A.M_ShipSystem_GetLocked, LuaMethod { val s = sys(arg.obj(1)); ret.bool(s.isIonised || s.hasPermanentLock) })
        LuaReg.method(A.M_ShipSystem_Functioning, LuaMethod { ret.bool(effectivePower(sys(arg.obj(1))) > 0) })
        LuaReg.method(A.M_ShipSystem_CompletelyDestroyed, LuaMethod { ret.bool(sys(arg.obj(1)).broken) })
        LuaReg.method(A.M_ShipSystem_NeedsRepairing, LuaMethod { ret.bool(sys(arg.obj(1)).damaged) })
        LuaReg.method(A.M_ShipSystem_GetId, LuaMethod { ret.num(nativeSystemId(sys(arg.obj(1)))) })
        LuaReg.method(A.M_ShipSystem_GetRoomId, LuaMethod { ret.num(sys(arg.obj(1)).room?.id ?: -1) })
        // static bool ShipSystem::IsSubsystem(int systemId) (HS hook: custom ids by their `subSystem`)
        LuaReg.method(A.M_ShipSystem_IsSubsystem, LuaMethod {
            if (LuaJs.type(1) == 3) ret.bool(HsSystems.isSubsystemId(arg.int(1))) else ret.bool(arg.obj(1) is SubSystem)
        })
        LuaReg.method(A.M_ShipSystem_GetMaxPower, LuaMethod { ret.num(maxPower(sys(arg.obj(1)))) })
        LuaReg.method(A.M_ShipSystem_GetPowerCap, LuaMethod { ret.num(sys(arg.obj(1)).scriptedPowerLimit ?: HsPower.CAP_NONE) })
        LuaReg.method(A.M_ShipSystem_GetDamage, LuaMethod {
            val s = sys(arg.obj(1))
            ret.num(maxOf(0, s.damagedEnergyLevels - (rawHealthDamage[s] ?: 0)))
        })
        LuaReg.method(A.M_ShipSystem_LockSystem, LuaMethod {
            val n = arg.int(2)
            if (n < 0) HS.unsupported("lua", "ShipSystem:LockSystem", "permanent lock") else sys(arg.obj(1)).lockUnits(n)
            0
        })
        LuaReg.method(A.M_ShipSystem_IonDamage, LuaMethod { val n = arg.int(2); if (n > 0) sys(arg.obj(1)).dealDamage(0, n); 0 })
        LuaReg.method(A.M_ShipSystem_AddDamage, LuaMethod {
            sys(arg.obj(1)).addDamage(arg.int(2))
            0
        })
        LuaReg.method(A.M_ShipSystem_CheckMaxPower, LuaMethod { val s = sys(arg.obj(1)); s.hsExt?.checkMaxPower(); s.powerLimitChanged(); 0 })
        LuaReg.method(A.M_ShipSystem_CheckForRepower, LuaMethod { sys(arg.obj(1)).powerLimitChanged(); 0 })
        LuaReg.method(A.M_ShipSystem_SetHackingLevel, LuaMethod { sys(arg.obj(1)).hsHackLevel = arg.int(2).coerceIn(0, 2); 0 })
        LuaReg.method(A.M_ShipSystem_SetPowerCap, LuaMethod { val n = arg.int(2); sys(arg.obj(1)).scriptedPowerLimit = if (n > 7) null else max(0, n); 0 })
        LuaReg.method(A.M_ShipSystem_SetPowerLoss, LuaMethod { sys(arg.obj(1)).scriptedPowerLoss = max(0, arg.int(2)); 0 })
        LuaReg.method(A.M_ShipSystem_ClearStatus, LuaMethod { val s = sys(arg.obj(1)); s.scriptedPowerLimit = null; s.scriptedPowerLoss = 0; 0 })
        // static ShipSystem.NameToSystemId(name) / SystemIdToName(id) (CustomSystems.cpp's tables: vanilla, temporal, custom)
        LuaReg.method(A.M_ShipSystem_NameToSystemId, LuaMethod { ret.num(HsSystems.nameToId(arg.str(1) ?: "")) })
        LuaReg.method(A.M_ShipSystem_SystemIdToName, LuaMethod { ret.str(HsSystems.idToName(arg.int(1))) })

        // subtype members
        LuaReg.obj(A.A_ArtillerySystem_projectileFactory, ObjGet { (it as Artillery).hsWeapon })
        LuaReg.bool(A.A_CloakingSystem_bTurnedOn, { (it as Cloaking).active })
        LuaReg.num(A.A_CloneSystem_fTimeGoal, NumGet { (it as Clonebay).cloneDuration.toDouble() })
        LuaReg.num(A.A_CloneSystem_fTimeToClone, NumGet { val c = it as Clonebay; (c.cloneProgress * c.cloneDuration).toDouble() }, NumSet { o, v ->
            val c = o as Clonebay
            c.cloneProgress = (v.toFloat() / c.cloneDuration).coerceAtLeast(0f)
        })
        LuaReg.obj(A.A_MindSystem_controlledCrew, ObjGet {
            LuaViews.cached(it!!, A.A_MindSystem_controlledCrew) { o ->
                val m = o as MindControl
                ListView(A.T_vector_CrewMember, A.T_CrewMember) { m.controlledCrew?.let { c -> listOf(c) } ?: emptyList() }
            }
        })
        LuaReg.obj(A.A_OxygenSystem_oxygenLevels, ObjGet { o ->
            val ship = shipOf(sys(o)) ?: return@ObjGet null
            LuaViews.cached(ship, A.A_OxygenSystem_oxygenLevels) { s -> ListView(A.T_vector_float, 0, OxygenLevels(s as Ship)) }
        })

        // Room.extend (Room_Extend): the temporal dilation (gravastars set it from Lua)
        LuaReg.obj(A.A_Room_extend, ObjGet { HsTemporal.roomState(it as Room) })
        LuaReg.num(A.A_Room_Extend_hullDamageResistChance, NumGet { (it as HsRoomSys).hullDamageResistChance.toDouble() }, NumSet { o, v -> (o as HsRoomSys).hullDamageResistChance = v.toFloat() })
        LuaReg.num(A.A_Room_Extend_sysDamageResistChance, NumGet { (it as HsRoomSys).sysDamageResistChance.toDouble() }, NumSet { o, v -> (o as HsRoomSys).sysDamageResistChance = v.toFloat() })
        LuaReg.num(A.A_Room_Extend_ionDamageResistChance, NumGet { (it as HsRoomSys).ionDamageResistChance.toDouble() }, NumSet { o, v -> (o as HsRoomSys).ionDamageResistChance = v.toFloat() })
        LuaReg.num(A.A_Room_Extend_timeDilation, NumGet { (it as HsRoomSys).timeDilation.toDouble() }, NumSet { o, v -> (o as HsRoomSys).timeDilation = v.toInt() })

        // Shields::CollisionReal(x, y, damage, force) (MV tags/pop.lua: a forced zero-damage hit for the flash):
        // a raised shield or super shield takes the hit (flash at the point, super damage, a layer for hull damage,
        // ion) -> collision type 2; otherwise 0. Not checked against the original (no fixture).
        LuaReg.method(A.M_Shields_CollisionReal, LuaMethod {
            val shields = arg.obj(1) as Shields
            val ship = shields.room!!.ship
            val damage = arg.obj(4) as? xyz.znix.xftl.weapons.Damage ?: xyz.znix.xftl.weapons.Damage()
            val point = xyz.znix.xftl.math.ConstPoint(arg.float(2).toInt(), arg.float(3).toInt())
            val up = shields.activeShields > 0 || ship.superShield > 0
            if (up) ship.attackShields(damage, point)
            val response = LuaStructs.create(A.T_CollisionResponse)
            response.values[A.A_CollisionResponse_collision_type] = if (up) 2.0 else 0.0
            response.values[A.A_CollisionResponse_point] = PointfValue(arg.float(2), arg.float(3))
            ret.obj(response, A.T_CollisionResponse)
        })

        // SystemBox (the player's systems bar) and its events
        HsSystemBox.install()
    }
}
