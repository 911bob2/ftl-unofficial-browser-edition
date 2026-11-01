// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i members of ShipManager, Ship,
// ShipGraph, Door, Room, Fire, ShipObject, ShipButton(List), ShipGenerator, ShipInfo, Blueprint, the augment
// definition classes and TemporalSystemParser; HS hooks named per member), not from the 1.23.2 tag. The native
// functions behind them follow FTL 1.6.9 (the HS oracle); member values were checked against the oracle (wp5-ship).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.hs.crew.HsCrew
import xyz.znix.xftl.hs.crew.addCrewMemberFromString
import xyz.znix.xftl.hs.systems.HsSystemsLua
import xyz.znix.xftl.layout.Door
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.MainSystem
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** A SWIG pointer of a type without Lua members (crewCounts, doorState, a Path, GL_Primitive...): only its type shows. */
class LuaOpaque(override val luaType: Int, @JvmField val owner: Any?) : LuaTyped

/** One cell of ShipManager::fireSpreader.grid (a Fire), by ship grid coordinates. */
/** A LockdownShard for Lua (one wrapper per shard, so the userdata stays the same object). */
class LuaLockdownShard private constructor(@JvmField val shard: xyz.znix.xftl.layout.LockdownShard, @JvmField val isCopy: Boolean = false) : LuaTyped {
    override val luaType get() = A.T_LockdownShard
    companion object {
        /** An element of Ship::lockdowns (a vector of values): a copy, so what a script writes to it stays there. */
        fun copy(s: xyz.znix.xftl.layout.LockdownShard) = LuaLockdownShard(s, true)
        private val cache = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.layout.LockdownShard, LuaLockdownShard>())
        fun of(s: xyz.znix.xftl.layout.LockdownShard): LuaLockdownShard = cache.getOrPut(s) { LuaLockdownShard(s) }
    }
}

class LuaFire(@JvmField val ship: Ship, @JvmField val gx: Int, @JvmField val gy: Int) : LuaTyped {
    override val luaType get() = A.T_Fire
    /** The room the cell belongs to and its slot, or null outside every room. */
    val room: Room? get() = ship.rooms.firstOrNull { gx - ship.offset.x in it.x until it.x + it.width && gy - ship.offset.y in it.y until it.y + it.height }
    val slot: Int get() = room?.let { r -> (gy - ship.offset.y - r.y) * r.width + (gx - ship.offset.x - r.x) } ?: -1
    /** Raw Fire fields Lua wrote (fDeathTimer, fStartTimer, fOxygen, bWasOnFire): the engine has no such per-cell state. */
    @JvmField var deathTimer = -1f
    @JvmField var startTimer = 0f
    @JvmField var oxygen = 100f
    @JvmField var wasOnFire = false
    @JvmField var selectedState = 0
    @JvmField var repairCount: Double = 0.0
}

/** Ship::weaponMounts[i] (WeaponMount): the layout's `<mount>` values. */
class LuaWeaponMount(@JvmField val ship: Ship, @JvmField val index: Int) : LuaTyped { override val luaType get() = A.T_WeaponMount }

/** One Ship::vOuterWalls entry (OuterHull: a Repairable per room cell, damaged while that cell is breached). */
class LuaOuterHull(@JvmField val room: Room, @JvmField val slot: Int) : LuaTyped { override val luaType get() = A.T_OuterHull }

object LuaAdaptersWP5Ship {
    private const val T_PATH = 289
    private const val T_DOOR_STATE = 320
    private const val T_CREW_COUNTS = 321

    private fun sm(o: Any?) = o as Ship
    private fun self(): Ship = arg.obj(1) as Ship
    private fun geom(o: Any?) = (o as LuaShipGeom).ship
    private fun system(ship: Ship, id: Int): AbstractSystem? = LuaShipApi.system(ship, id)
    private fun graphShip(o: Any?): Ship? = (o as LuaShipGraph).ship

    /** systemKey index [id] is installed (artillery: the first one). */
    private fun has(ship: Ship, id: Int): Boolean = system(ship, id) != null

    private fun opaque(owner: Any, key: Int, type: Int): LuaOpaque = LuaViews.cached(owner, key) { o -> LuaOpaque(type, o) }

    /** boost::format's %f (the HS error texts print floats with it). */
    private fun fmtG(v: Float): String = String.format(java.util.Locale.ROOT, "%f", v.toDouble())

    private fun pointf(x: Float, y: Float): Int = ret.obj(PointfValue(x, y), A.T_Pointf)
    private fun point(x: Int, y: Int): Int = ret.obj(PointValue(x, y), A.T_Point)

    @JvmStatic
    fun install() {
        manager()
        managerMethods()
        crewMethods()
        geometry()
        graph()
        doors()
        rooms()
        fires()
        shipObject()
        blueprints()
        hangar()
        generator()
        vectorConstructors()
        oxygen()
    }

    /**
     * OxygenSystem.oxygenLevels (vector<float>, 0..100 per room; MV's orchid-oxygen-start.lua writes
     * `ship.oxygenSystem.oxygenLevels[room] = 100`) on the rooms' oxygen, and HS's dummy OxygenSystem
     * (OxygenWithoutSystem.cpp, 1b05a07): with oxygenWithoutSystem a ship without the system still has an
     * `oxygenSystem` (installed on the first ShipManager call that needs it, keeping the levels RemoveSystem moved into
     * it; oracle wp5-ship2 o2-removed: true), unpowered: GetRefillSpeed -0.075 per speed factor, not Functioning.
     */
    private fun oxygen() {
        LuaReg.obj(A.A_ShipManager_oxygenSystem, ObjGet { o ->
            val s = o as Ship
            s.oxygen ?: if (HS.active && HS.cfg().oxygenWithoutSystem) dummyOxygen.getOrPut(s) { LuaDummyOxygen(s) } else null
        })
        LuaReg.obj(A.A_OxygenSystem_oxygenLevels, ObjGet { o ->
            LuaViews.cached(o!!, A.A_OxygenSystem_oxygenLevels) { x -> ListView(A.T_vector_float, 0, OxygenLevels(oxygenShip(x))) }
        })
        fun wrap(m: Int, dummy: (LuaDummyOxygen) -> Int) {
            val prev = LuaReg.methods[m]
            LuaReg.method(m, LuaMethod { n -> (arg.obj(1) as? LuaDummyOxygen)?.let(dummy) ?: (prev?.call(n) ?: 0) })
        }
        fun room(d: LuaDummyOxygen): Room {
            val id = arg.int(2)
            if (id < 0 || id >= d.ship.rooms.size) throw LuaError("Invalid roomId: $id")
            return d.ship.rooms[id]
        }
        wrap(A.M_OxygenSystem_ModifyRoomOxygen) { d ->
            val r = room(d); r.hsSetOxygenUnclamped(r.oxygen + arg.float(3) * xyz.znix.xftl.hs.systems.HsTemporal.factor(r) / 100f); 0
        }
        wrap(A.M_OxygenSystem_EmptyOxygen) { d ->
            val r = room(d)
            if (HsLua.game?.currentState is xyz.znix.xftl.hangar.SelectShipState) r.hsSetOxygenUnclamped(0f)
            0
        }
        wrap(A.M_OxygenSystem_GetRefillSpeed) { _ -> ret.num((LuaFps.speedFactor * -0.075f).toDouble()) }
        wrap(A.M_OxygenSystem_Functioning) { _ -> ret.bool(false) }
    }

    /** A ship's rooms' oxygen as the 0..100 floats of OxygenSystem::oxygenLevels (fixed size, writable). */
    private class OxygenLevels(private val ship: Ship) : java.util.AbstractList<Any?>() {
        override val size: Int get() = ship.rooms.size
        override fun get(index: Int): Any? = (ship.rooms[index].oxygen * 100f).toDouble()
        override fun set(index: Int, element: Any?): Any? {
            val old = get(index)
            ship.rooms[index].hsSetOxygenUnclamped(((element as? Number)?.toFloat() ?: 0f) / 100f)
            return old
        }
    }

    private val dummyOxygen = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, LuaDummyOxygen>())

    private fun oxygenShip(o: Any?): Ship = when (o) {
        is xyz.znix.xftl.systems.Oxygen -> o.room!!.ship
        is LuaDummyOxygen -> o.ship
        else -> throw LuaError("expected OxygenSystem")
    }

    /**
     * Hyperspace.vector_int / vector_float constructors (std::vector's: empty, n zeros, a copy, n copies of a value),
     * where no other adapter registered them.
     */
    private fun vectorConstructors() {
        fun ctor(type: Int, ids: IntArray, element: Int = 0) {
            val f = LuaMethod {
                val first = if (arg.isNil(1)) null else arg.obj(1)
                val list = ArrayList<Any?>()
                when {
                    arg.isNil(1) -> {}
                    first is ListView -> for (i in 0 until first.size()) list += first.get(i)
                    else -> { val n = arg.int(1); val v = if (arg.isNil(2)) 0.0 else arg.num(2); repeat(n) { list += v } }
                }
                ret.obj(ListView(type, element, list), type)
            }
            for (m in ids) if (LuaReg.methods[m] == null) LuaReg.method(m, f)
        }
        ctor(A.T_vector_int, intArrayOf(A.M_new_vector_int, A.M_new_vector_int__SWIG_0, A.M_new_vector_int__SWIG_1, A.M_new_vector_int__SWIG_2, A.M_new_vector_int__SWIG_3))
        ctor(A.T_vector_float, intArrayOf(A.M_new_vector_float, A.M_new_vector_float__SWIG_0, A.M_new_vector_float__SWIG_1, A.M_new_vector_float__SWIG_2, A.M_new_vector_float__SWIG_3))
        // vector_CrewBlueprint(): what ShipManager:AddInitialCrew takes (empty, or a copy of another vector)
        ctor(A.T_vector_CrewBlueprint, intArrayOf(A.M_new_vector_CrewBlueprint, A.M_new_vector_CrewBlueprint__SWIG_0,
            A.M_new_vector_CrewBlueprint__SWIG_1, A.M_new_vector_CrewBlueprint__SWIG_2, A.M_new_vector_CrewBlueprint__SWIG_3), A.T_CrewBlueprint)
        // Hyperspace.string() / Hyperspace.string(const char*) (SWIG std_string.i, once per module): an owned std::string
        // object, what ShipSystem:SetName(std::string&) takes. A non-string argument fails SWIG's overload check.
        val newString = LuaMethod { n ->
            val value = when {
                n == 0 -> ""
                n == 1 && LuaJs.isstring(1) -> arg.str(1) ?: ""
                else -> throw LuaError("Wrong arguments for overloaded function 'new_string'\n  Possible C/C++ prototypes are:\n" +
                    "    std::string::string()\n    std::string::string(char const *)\n")
            }
            var v = value
            ret.obj(LuaStdString({ v }, { v = it }), A.T_string)
        }
        for (m in intArrayOf(A.M_new_string, A.M_new_string__SWIG_0, A.M_new_string__SWIG_1, A.M_new_string_, A.M_new_string__SWIG_0_,
            A.M_new_string__SWIG_1_, A.M_new_string__, A.M_new_string__SWIG_0__, A.M_new_string__SWIG_1__, A.M_new_string___,
            A.M_new_string__SWIG_0___, A.M_new_string__SWIG_1___)) if (LuaReg.methods[m] == null) LuaReg.method(m, newString)
    }

    // ---------------------------------------------------------------- ShipGenerator statics (HsShipGeneratorLua)
    private fun generator() {
        val G = xyz.znix.xftl.hs.ship.HsShipGeneratorLua
        fun game() = LuaAdapters.inGame() ?: throw LuaError("ShipGenerator needs a running game")
        fun ints(o: Any?): List<Int> = (o as? ListView)?.let { v -> (0 until v.size()).map { (v.get(it) as Number).toInt() } } ?: emptyList()
        LuaReg.method(A.M_ShipGenerator_GetPossibleWeaponList, LuaMethod {
            val l = G.possibleWeapons(game(), arg.obj(1) as Ship, arg.str(2) ?: "", arg.int(4))
            ret.obj(ListView(A.T_vector_WeaponBlueprint, A.T_WeaponBlueprint, ArrayList<Any?>(l)), A.T_vector_WeaponBlueprint)
        })
        LuaReg.method(A.M_ShipGenerator_GetPossibleDroneList, LuaMethod {
            val l = G.possibleDrones(game(), arg.obj(1) as Ship, arg.str(2) ?: "", arg.int(4), arg.bool(5))
            ret.obj(ListView(A.T_vector_DroneBlueprint, A.T_DroneBlueprint, ArrayList<Any?>(l)), A.T_vector_DroneBlueprint)
        })
        LuaReg.method(A.M_ShipGenerator_GetPossibleCrewList, LuaMethod {
            val l = G.possibleCrew(game(), arg.obj(1) as Ship, arg.str(2) ?: "")
            ret.obj(ListView(A.T_vector_CrewBlueprint, A.T_CrewBlueprint, ArrayList<Any?>(l)), A.T_vector_CrewBlueprint)
        })
        LuaReg.method(A.M_ShipGenerator_GetPossibleSystemUpgrades, LuaMethod {
            val l = G.possibleUpgrades(arg.obj(1) as Ship, ints(arg.obj(2)), arg.int(4))
            ret.obj(ListView(A.T_vector_int, 0, ArrayList<Any?>(l.map { it.toDouble() })), A.T_vector_int)
        })
        // UpgradeSystem(ship, maxes, id) (1.6.9 0x5f92b0): one level more, the system installed first when missing
        LuaReg.method(A.M_ShipGenerator_UpgradeSystem, LuaMethod {
            ret.bool(G.upgradeSystem(arg.obj(1) as Ship, ints(arg.obj(2)), arg.int(3)))
        })
        // CreateShip(name, sector, ShipEvent&): no Lua binding hands out a ShipEvent, so SWIG's argument check
        // (hs-lua check_arg: "expected 'ShipEvent &'") rejects every call before it gets here
        LuaReg.method(A.M_ShipGenerator_CreateShip, LuaMethod {
            throw LuaError("Error in ShipGenerator::CreateShip (arg 3), expected 'ShipEvent &' got 'userdata'")
        })
        LuaReg.method(A.M_ShipGenerator_GenerateSystemMaxes, LuaMethod {
            val bp = arg.obj(1) as? xyz.znix.xftl.game.ShipBlueprint ?: throw LuaError("GenerateSystemMaxes: expected a ShipBlueprint")
            val l = G.generateSystemMaxes(game(), bp, arg.int(2))
            ret.obj(ListView(A.T_vector_int, 0, ArrayList<Any?>(l.map { it.toDouble() })), A.T_vector_int)
        })
    }

    // ---------------------------------------------------------------- ShipManager members
    private fun manager() {
        // current_target: the ship this one fights (CompleteShip::SetEnemyShip links both); a destroyed enemy stays
        // the player's target until the beacon changes (InGameState.getBrowserRetainedEnemy).
        LuaReg.obj(A.A_ShipManager_current_target, ObjGet { o ->
            val s = sm(o); val g = LuaAdapters.inGame() ?: return@ObjGet null
            when {
                s === g.player -> g.browserRetainedEnemy
                s === g.enemy || s === g.browserRetainedEnemy -> g.player
                else -> null
            }
        })
        // jump_timer: (charge, 85); the FTL drive is ready when first reaches second
        LuaReg.obj(A.A_ShipManager_jump_timer, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ShipManager_jump_timer) { x ->
                val s = x as Ship
                FloatPairView(A.T_pair_float_float, FGet { s.ftlChargeProgress * s.hsJumpGoal }, FSet { s.ftlChargeProgress = it / s.hsJumpGoal },
                    FGet { s.hsJumpGoal }, FSet { if (it > 0f) { s.ftlChargeProgress = s.ftlChargeProgress * s.hsJumpGoal / it; s.hsJumpGoal = it } })
            }
        })
        LuaReg.num(A.A_ShipManager_iLastDamage, NumGet { sm(it).nativeLastDamage.toDouble() })
        LuaReg.bool(A.A_ShipManager_bAutomated, { sm(it).isAutomated }, { o, v -> sm(o).isAutomated = v })
        LuaReg.num(A.A_ShipManager_shipLevel, NumGet { HsShipLuaState.of(sm(it)).shipLevel.toDouble() })
        // lastEngineStatus: the status the ship's last unpaused loop saw (oracle wp5-ship: still true after a paused
        // ForceDecreaseSystemPower took the engines to 0); a script's write stands until the next loop
        LuaReg.bool(A.A_ShipManager_lastEngineStatus, { s -> HsShipLuaState.of(sm(s)).lastEngineStatus ?: engineStatus(sm(s)) },
            { s, v -> HsShipLuaState.of(sm(s)).lastEngineStatus = v })
        LuaReg.bool(A.A_ShipManager_lastJumpReady, { sm(it).isFtlCharged })
        LuaReg.bool(A.A_ShipManager_bContainsPlayerCrew, { o -> sm(o).crew.any { c -> LuaAdapters.crewShipId(c) == 0 && c is LivingCrew } })
        LuaReg.num(A.A_ShipManager_iIntruderCount, NumGet { o -> sm(o).crew.count { c -> c.mode == AbstractCrew.SlotType.INTRUDER }.toDouble() })
        LuaReg.obj(A.A_ShipManager_crewCounts, ObjGet { opaque(it!!, A.A_ShipManager_crewCounts, T_CREW_COUNTS) })
        // tempDroneCount / tempMissileCount hold the parts while the ship has no drone / weapon system
        // (ShipManager::ModifyDroneCount / ModifyMissileCount 1.6.9: systemKey[4] / [3] == -1)
        LuaReg.num(A.A_ShipManager_tempDroneCount, NumGet { val s = sm(it); (if (s.drones == null) s.dronesCount else 0).toDouble() })
        LuaReg.num(A.A_ShipManager_tempMissileCount, NumGet { val s = sm(it); (if (s.weapons == null) s.missilesCount else 0).toDouble() })
        // tempVision: one flag per room a script (or a scan) sets; the next CheckVision reveals the room and clears it
        LuaReg.obj(A.A_ShipManager_tempVision, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ShipManager_tempVision) { x -> val list = TempVisionList(x as Ship); ListView(A.T_vector_bool, 0) { list } }
        })
        flag(A.A_ShipManager_bHighlightCrew, false)
        flag(A.A_ShipManager_bShowRoom, true)
        flag(A.A_ShipManager_bInvincible, false)
        flag(A.A_ShipManager_enemyDamagedUncloaked, false)
        intField(A.A_ShipManager_failedDodgeCounter, 0)
        intField(A.A_ShipManager_damageCloaked, 0)
        LuaReg.num(A.A_ShipManager_iCustomizeMode, NumGet { 0.0 })
        // superBarrage: the barrage projectiles a PrepareSuperBarrage made (HsBoss)
        LuaReg.obj(A.A_ShipManager_superBarrage, ObjGet { o ->
            LuaViews.cached(o!!, A.A_ShipManager_superBarrage) { x ->
                ListView(A.T_vector_Projectile, A.T_Projectile) { xyz.znix.xftl.hs.ship.HsBoss.superBarrageOf(x as Ship) }
            }
        })
        LuaReg.obj(A.A_ShipManager_extend, ObjGet { o -> LuaViews.cached(o!!, A.A_ShipManager_extend) { LuaShipManagerExtend(it as Ship) } })
    }

    private const val JUMP_GOAL = 85f

    /** ShipManager::lastEngineStatus: the engines and the piloting work (the "engines offline" warning). */
    /** Lua ShipManager::AddSystem: the installed system, or null when the ship has no free room for it or already has it. */
    @JvmStatic
    fun luaAddSystem(s: Ship, id: Int): AbstractSystem? {
        val name = xyz.znix.xftl.hs.systems.HsSystems.idToName(id)
        // AddSystem has no check for a system the ship has already: it makes a second one (HsSystemInstall.duplicate)
        if (id != 11) s.systems.firstOrNull { it.blueprint.name == name }?.let { return xyz.znix.xftl.hs.systems.HsSystemInstall.duplicate(s, it) }
        val slot = s.systemSlots.firstOrNull { it.system.name == name && it.room.system == null } ?: return null
        // vSystemList takes the new system at its end, in the order of the calls (oracle wp5-ship sm-after: 9, 14, 15)
        HsShipLuaState.systemList(s)
        val sys = xyz.znix.xftl.hs.systems.HsSystemInstall.install(s, slot) ?: return null
        HsShipLuaState.of(s).systemOrder.let { if (it.none { o -> o === sys }) it += sys }
        s.browserPowerNewSystem(sys)
        return sys
    }

    /** ShipManager::OnLoop: lastEngineStatus = GetDodgeFactor > 0 (the "engines offline" warning). */
    @JvmStatic fun engineStatus(s: Ship): Boolean = s.dodgeFactor > 0

    /** ShipManager::tempVision (vector<bool>, one per room) over [HsShipLuaState.tempVision]; writable by index. */
    private class TempVisionList(private val s: Ship) : java.util.AbstractList<Any?>() {
        override val size: Int get() = s.rooms.size
        override fun get(index: Int): Any? = HsShipLuaState.of(s).tempVision.contains(index)
        override fun set(index: Int, element: Any?): Any? {
            val old = get(index)
            val on = element == true || (element is Number && element.toDouble() != 0.0)
            if (on) HsShipLuaState.of(s).tempVision += index else HsShipLuaState.of(s).tempVision -= index
            return old
        }
    }

    /** A plain ShipManager field the engine does not model: kept per ship for Lua (read back as written). */
    private fun flag(a: Int, default: Boolean) {
        LuaReg.bool(a, { o -> HsShipLuaState.of(sm(o)).flags[a] ?: default }, { o, v -> HsShipLuaState.of(sm(o)).flags[a] = v })
    }

    private fun intField(a: Int, default: Int) {
        LuaReg.num(a, NumGet { o -> (HsShipLuaState.of(sm(o)).ints[a] ?: default).toDouble() },
            NumSet { o, v -> HsShipLuaState.of(sm(o)).ints[a] = v.toInt() })
    }

    // ---------------------------------------------------------------- ShipManager methods
    private fun managerMethods() {
        // GetRandomRoomCenter: GetRoomCenter(random32() % RoomCount())
        LuaReg.method(A.M_ShipManager_GetRandomRoomCenter, LuaMethod {
            val s = self()
            if (s.rooms.isEmpty()) return@LuaMethod pointf(-1f, -1f)
            val r = s.rooms[ftl.browser.BrowserRandom.nextInt(s.rooms.size)]
            pointf((r.offsetX + r.pixelWidth / 2).toFloat(), (r.offsetY + r.pixelHeight / 2).toFloat())
        })
        // GetAvailablePower: PowerManager::GetAvailablePower = (reactor, unused)
        LuaReg.method(A.M_ShipManager_GetAvailablePower, LuaMethod {
            val s = self()
            ret.obj(IntPairView(A.T_pair_int_int, constI(s.reactorPower), null, constI(s.powerAvailable), null), A.T_pair_int_int)
        })
        // CanFitSystem / CanFitSubsystem(id) (CustomSystems.cpp): a mutually exclusive replacement always fits, else
        // the ship's systemLimit / subsystemLimit against the systems that need / do not need power
        LuaReg.method(A.M_ShipManager_CanFitSystem, LuaMethod {
            val s = self(); val id = arg.int(2)
            if (replaces(s, id)) return@LuaMethod ret.bool(true)
            ret.bool(s.systems.count { it is MainSystem } < xyz.znix.xftl.hs.ship.HsShips.def(s.type.name).systemLimit)
        })
        LuaReg.method(A.M_ShipManager_CanFitSubsystem, LuaMethod {
            val s = self(); val id = arg.int(2)
            if (replaces(s, id)) return@LuaMethod ret.bool(true)
            ret.bool(s.systems.count { it !is MainSystem } < xyz.znix.xftl.hs.ship.HsShips.def(s.type.name).subsystemLimit)
        })
        // SystemFunctions(id): the system's Functioning(); DoorsFunction: doors functioning and not hacked
        LuaReg.method(A.M_ShipManager_SystemFunctions, LuaMethod { val s = system(self(), arg.int(2)); ret.bool(s != null && HsSystemsLua.effectivePower(s) > 0) })
        LuaReg.method(A.M_ShipManager_DoorsFunction, LuaMethod {
            val d = self().doorsSystem
            ret.bool(d != null && HsSystemsLua.effectivePower(d) > 0 && !d.isHackActive)
        })
        // GetSystemPower: -1 without the system, else GetEffectivePower(); GetSystemPowerMax: the level (0 without)
        LuaReg.method(A.M_ShipManager_GetSystemPower, LuaMethod { val s = system(self(), arg.int(2)); ret.num(if (s == null) -1 else HsSystemsLua.effectivePower(s)) })
        LuaReg.method(A.M_ShipManager_GetSystemPowerMax, LuaMethod { ret.num(system(self(), arg.int(2))?.energyLevels ?: 0) })
        // CanUpgrade(id, amount): min(amount, maxLevel - level); 17 = the reactor (CustomReactor: maxReactorLevel)
        LuaReg.method(A.M_ShipManager_CanUpgrade, LuaMethod {
            val ship = self(); val id = arg.int(2); val amount = arg.int(3)
            if (id == 17) return@LuaMethod ret.num(minOf(amount, ship.maxReactorPower - ship.reactorPower))
            val s = system(ship, id) ?: return@LuaMethod ret.num(0)
            ret.num(minOf(amount, s.maxLevel - s.energyLevels))
        })
        // IsSystemHacked(id): 0 without the system, else its iHackEffect
        LuaReg.method(A.M_ShipManager_IsSystemHacked, LuaMethod {
            val s = system(self(), arg.int(2)) ?: return@LuaMethod ret.num(0)
            ret.num(if (s.hackedBy == null && s.hsHackLevel == 0) 0 else if (s.isHackActive) 2 else 1)
        })
        LuaReg.method(A.M_ShipManager_GetSystemInRoom, LuaMethod {
            val id = arg.int(2)
            ret.obj(self().systems.firstOrNull { it.room?.id == id }, A.T_ShipSystem)
        })
        // DoSensorsProvide(vision): never while the ship's own sensors are hacked; 1/2/3/4 need sensor power 1/2/4/3
        LuaReg.method(A.M_ShipManager_DoSensorsProvide, LuaMethod {
            val ship = self(); val v = arg.int(2)
            val sensors = ship.sensors ?: return@LuaMethod ret.bool(false)
            if (sensors.isHackActive) return@LuaMethod ret.bool(false)
            val p = HsSystemsLua.effectivePower(sensors)
            ret.bool(when (v) { 1 -> p > 0; 2 -> p > 1; 3 -> p > 3; 4 -> p > 2; else -> false })
        })
        // GetShieldPower: the shield system's (first, second, super) or zeros
        LuaReg.method(A.M_ShipManager_GetShieldPower, LuaMethod {
            val s = self()
            val p = LuaStructs.create(A.T_ShieldPower)
            val sh = s.shields
            if (sh != null) {
                p.values[A.A_ShieldPower_first] = sh.activeShields.toDouble()
                p.values[A.A_ShieldPower_second] = sh.suppliedShieldBars.toDouble()
                LuaStructs.setFields(LuaStructs.member(p, A.A_ShieldPower_super) as LuaStruct, s.superShield.toDouble(), (if (s.hsSuperShieldSet != 0) s.maxSuperShield else 0).toDouble())
            }
            ret.obj(p, A.T_ShieldPower)
        })
        LuaReg.method(A.M_ShipManager_GetWeaponList, LuaMethod {
            val s = self()
            ret.obj(ListView(A.T_vector_ProjectileFactory, A.T_ProjectileFactory, ArrayList<Any?>(s.hardpoints.mapNotNull { it.weapon })), A.T_vector_ProjectileFactory)
        })
        // GetFireCount(room) / GetFireCount(): burning cells
        LuaReg.method(A.M_ShipManager_GetFireCount__SWIG_1, LuaMethod { ret.num(self().rooms.firstOrNull { it.id == arg.int(2) }?.fires?.count { it != null } ?: 0) })
        LuaReg.method(A.M_ShipManager_GetFireCount__SWIG_0, LuaMethod { ret.num(self().rooms.sumOf { r -> r.fires.count { it != null } }) })
        // ModifyScrapCount(amount, income) (CustomEvents.cpp hook: the scrap counter and the income-only stats)
        LuaReg.method(A.M_ShipManager_ModifyScrapCount, LuaMethod {
            val s = self(); val n = arg.int(2)
            s.scrap = maxOf(0, s.scrap + n)
            0
        })
        // ResetScrapLevel: 30/10/0 by difficulty, then the HS definition's startingScrap/startingFuel (CustomShips.cpp)
        LuaReg.method(A.M_ShipManager_ResetScrapLevel, LuaMethod {
            val s = self()
            s.scrap = when (s.sys.difficulty) {
                xyz.znix.xftl.game.Difficulty.EASY -> 30
                xyz.znix.xftl.game.Difficulty.NORMAL -> 10
                xyz.znix.xftl.game.Difficulty.HARD -> 0
            }
            xyz.znix.xftl.hs.ship.HsShipState.applyStartingResources(s)
            0
        })
        // SetSystemPowerLoss(id, loss): ShipSystem::SetPowerLoss re-checks the power before it stores the new loss;
        // 17 = the reactor; artillery: every artillery system (CustomEvents.cpp)
        LuaReg.method(A.M_ShipManager_SetSystemPowerLoss, LuaMethod {
            val ship = self(); val id = arg.int(2); val loss = arg.int(3)
            if (id == 17) { xyz.znix.xftl.hs.systems.HsPower.of(ship).loss = loss; return@LuaMethod 0 }
            val targets = if (id == 11) ship.artillery else listOfNotNull(system(ship, id))
            for (s in targets) setPowerLoss(s, loss)
            0
        })
        // ForceDecreaseSystemPower(id): ForceDecreasePower(1); false when nothing was removed
        LuaReg.method(A.M_ShipManager_ForceDecreaseSystemPower, LuaMethod {
            // ShipSystem::ForceDecreasePower(1) (1.6.9 0x614120): one bar back to the reactor through
            // PowerManager::DecreasePower, whatever the system's step (a shield bar too) and even while locked
            val s = system(self(), arg.int(2)) as? MainSystem ?: return@LuaMethod ret.bool(false)
            // (true when a bar went: the effective power, which a stored power loss already counts, dropped)
            val before = HsSystemsLua.effectivePower(s)
            if (s.powerSelected - s.forcedPower > 0) s.hsSetPower(s.powerSelected - 1, false)
            s.room?.ship?.updateAvailablePower()
            ret.bool(HsSystemsLua.effectivePower(s) < before)
        })
        // ClearStatusSystem(id) / ClearStatusAll: ShipSystem::ClearStatus (17 / all: the reactor too)
        LuaReg.method(A.M_ShipManager_ClearStatusSystem, LuaMethod {
            val ship = self(); val id = arg.int(2)
            if (id == 17) { clearReactor(ship); return@LuaMethod 0 }
            val targets = if (id == 11) ship.artillery else listOfNotNull(system(ship, id))
            for (s in targets) clearStatus(s)
            0
        })
        LuaReg.method(A.M_ShipManager_ClearStatusAll, LuaMethod {
            val ship = self()
            for (s in ship.systems) clearStatus(s)
            clearReactor(ship)
            0
        })
        LuaReg.method(A.M_ShipManager_InstantPowerShields, LuaMethod { self().instantPowerShields(); 0 })
        // DamageHull(dmg, force): nothing while jumping unless forced; iLastDamage = Ship::DamageHull's result
        LuaReg.method(A.M_ShipManager_DamageHull, LuaMethod {
            val s = self(); val dmg = arg.int(2); val force = arg.bool(3)
            if (!force && s.browserJumping) return@LuaMethod ret.num(0)
            // Ship::DamageHull (1.6.9 0x480b50): damage capped at the hull, healing at the missing hull
            val d = if (dmg < 1) -minOf(-dmg, s.maxHealth - s.health) else minOf(dmg, s.health)
            s.health = (s.health - d).coerceIn(0, s.maxHealth)
            s.nativeLastDamage = d
            ret.num(d)
        })
        // DamageSystem(id, Damage): CustomDamage.cpp / CustomShips.cpp hooks over the native room damage
        LuaReg.method(A.M_ShipManager_DamageSystem, LuaMethod {
            val ship = self(); val s = system(ship, arg.int(2)) ?: return@LuaMethod 0
            val d = arg.obj(3) as? LuaStruct ?: return@LuaMethod 0
            val sysDamage = (d.values[A.A_Damage_iSystemDamage] as? Double ?: 0.0).toInt() + (d.values[A.A_Damage_iDamage] as? Double ?: 0.0).toInt()
            val ion = (d.values[A.A_Damage_iIonDamage] as? Double ?: 0.0).toInt()
            s.dealDamage(sysDamage, ion)
            0
        })
        LuaReg.method(A.M_ShipManager_StartFire, LuaMethod {
            // Spreader_Fire::StartFires(room, 2) (1.6.9 0x735470): two random cells of the room catch fire (one may repeat)
            val r = self().rooms.firstOrNull { it.id == arg.int(2) } ?: return@LuaMethod 0
            repeat(minOf(2, r.cellCount)) {
                val x = ftl.browser.BrowserRandom.nextInt(r.width); val y = ftl.browser.BrowserRandom.nextInt(r.height)
                r.spawnFireAt(y * r.width + x)
            }
            0
        })
        // SetDestroyed: bDestroyed, every system destroyed, then Ship::SetDestroyed (the hull breaks apart)
        LuaReg.method(A.M_ShipManager_SetDestroyed, LuaMethod {
            val s = self()
            s.nativeDestroyed = true
            for (sys in s.systems) sys.addDamage(sys.energyLevels)
            s.health = 0
            0
        })
        // RemoveItem(name[, checkCargo]) (CustomEvents.cpp hook): an augment, then the first weapon and drone of
        // that name, then cargo when checkCargo
        val removeItem = LuaMethod {
            val s = self(); val name = arg.str(2) ?: ""
            val cargo = !arg.isNil(3) && arg.bool(3)
            removeItem(s, name, cargo)
            0
        }
        LuaReg.method(A.M_ShipManager_RemoveItem__SWIG_0, removeItem)
        LuaReg.method(A.M_ShipManager_RemoveItem__SWIG_1, removeItem)
        // RemoveSystem(id) (HS's CustomSystems.cpp): the system leaves its room
        LuaReg.method(A.M_ShipManager_RemoveSystem, LuaMethod {
            val s = self(); val sys = system(s, arg.int(2)) ?: return@LuaMethod 0
            xyz.znix.xftl.hs.systems.HsSystemInstall.removeSystem(s, sys)
            0
        })
        // AddSystem(id): the HS hook's install (CustomSystems.cpp: an exclusive system replaced, its level carried) into
        // the room the blueprint has for it, then the native power-up a store purchase also gets (oracle wp5-sys: an added
        // cloak or teleporter takes one bar; artillery with a full reactor gets none and PowerManager::IncreasePower
        // 0x620040 sets failedPowerup). The int result is 0 whatever happened (oracle wp5-ship AddSystem-ret: a present
        // system, an invalid id and two installs all return 0).
        LuaReg.method(A.M_ShipManager_AddSystem, LuaMethod {
            val s = self()
            luaAddSystem(s, arg.int(2))
            s.systems.filterIsInstance<xyz.znix.xftl.systems.Artillery>().lastOrNull()?.let { if (it.hsAddedAgainst == null) it.hsAddedAgainst = s.sys.getEnemyOf(s) }
            ret.num(0)
        })
        // PrepareSuperBarrage: the <boss> barrage at current_target (HsBoss); without a target nothing happens
        LuaReg.method(A.M_ShipManager_PrepareSuperBarrage, LuaMethod {
            val s = self(); val g = LuaAdapters.inGame() ?: return@LuaMethod 0
            xyz.znix.xftl.hs.ship.HsBoss.prepareSuperBarrageLua(g, s)
            0
        })
        // GetTooltip(x, y) (CustomCrew.cpp hook): the crew tooltip of the point (empty when none)
        LuaReg.method(A.M_ShipManager_GetTooltip, LuaMethod {
            val s = self(); val g = LuaAdapters.inGame() ?: return@LuaMethod ret.str("")
            ret.str(g.shipUI?.diagnosticCrewTooltip(s, xyz.znix.xftl.math.ConstPoint(arg.int(2), arg.int(3))) ?: "")
        })
        // OnInit(bp, level): FTL rebuilds the ship from a blueprint in place; the engine builds ships whole
        LuaReg.method(A.M_ShipManager_OnInit, LuaMethod {
            HS.unsupported("lua", "method", "ShipManager.OnInit (rebuilding a live ship)")
            throw LuaError("ShipManager::OnInit is not supported on a live ship in the browser engine")
        })
    }

    private fun constI(v: Int) = IGet { v }

    private fun replaces(s: Ship, id: Int): Boolean {
        val name = HsSystems_idToName(id) ?: return false
        return xyz.znix.xftl.hs.systems.HsSystemInstall.willReplace(s, name) != null
    }

    private fun HsSystems_idToName(id: Int): String? = xyz.znix.xftl.hs.systems.HsSystems.idToName(id).takeIf { it.isNotEmpty() && it != "unknown" && it != "invalid system id" }

    /** ShipSystem::SetPowerLoss (1.6.9): CheckMaxPower with the old loss, then the new loss is stored. */
    // The loss stored natively takes effect at the system's next CheckMaxPower (its next loop, or the next
    // SetPowerLoss): the engine applies a loss as it is set, so the new one waits in HsShipLuaState.pendingLoss.
    private fun setPowerLoss(s: AbstractSystem, loss: Int) {
        val ship = s.room?.ship
        val pending = ship?.let { HsShipLuaState.of(it).pendingLoss }
        val old = pending?.remove(s)
        if (old != null) s.scriptedPowerLoss = old else s.powerLimitChanged()
        if (pending != null) pending[s] = maxOf(0, loss) else s.scriptedPowerLoss = maxOf(0, loss)
    }

    private fun clearStatus(s: AbstractSystem) {
        s.room?.ship?.let { HsShipLuaState.of(it).pendingLoss.remove(s) }
        s.scriptedPowerLimit = null
        s.scriptedPowerLoss = 0
    }

    private fun clearReactor(ship: Ship) {
        val p = xyz.znix.xftl.hs.systems.HsPower.of(ship)
        p.loss = 0; p.cap = xyz.znix.xftl.hs.systems.HsPower.CAP_NONE; p.divide = 1
    }

    private fun removeItem(s: Ship, name: String, cargo: Boolean) {
        val g = LuaAdapters.inGame()
        if (g != null && s === g.player) {
            val old = xyz.znix.xftl.hs.events.HsEvents.checkCargo
            xyz.znix.xftl.hs.events.HsEvents.checkCargo = cargo
            try { xyz.znix.xftl.hs.events.HsEventEffects.removeItem(g, name) } finally { xyz.znix.xftl.hs.events.HsEvents.checkCargo = old }
            return
        }
        val bm = s.sys.blueprintManager
        if (xyz.znix.xftl.hs.ship.HsAugments.count(s, name) > 0 || name.startsWith("HIDDEN ")) removeAugmentation(s, name)
        s.hardpoints.firstOrNull { it.weapon?.type?.name == name }?.let { h -> s.removeBlueprint(h.weapon!!.type) }
        s.drones?.drones?.firstOrNull { it?.type?.name == name }?.let { d -> s.removeBlueprint(d.type) }
        if (cargo) {
            val bp = bm.getOrNull(name) as? xyz.znix.xftl.Blueprint
            if (bp != null && s.allCargo().contains(bp)) s.removeBlueprint(bp)
        }
    }

    // ---------------------------------------------------------------- crew methods
    private fun crewMethods() {
        // SelectRandomCrew(seed, race) (1.6.9 0x498520): seed != -1 reseeds random32; one of the ship's player-side,
        // non-drone crew of that species (any species when none match) by random32() % n; its blueprint (a copy)
        LuaReg.method(A.M_ShipManager_SelectRandomCrew, LuaMethod {
            val s = self(); val seed = arg.int(2); var race = arg.str(3) ?: ""
            if (seed != -1) ftl.browser.NativeRandom.seed(seed)
            fun pool() = s.crew.filter { c -> LuaAdapters.crewShipId(c) == 0 && !isDrone(c) && (race.isEmpty() || c.hsSpecies == race) }
            var list = pool()
            if (list.isEmpty() && race.isNotEmpty()) { race = ""; list = pool() }
            if (list.isEmpty()) return@LuaMethod ret.obj(LuaStructs.create(A.T_CrewBlueprint), A.T_CrewBlueprint)
            val original = list[ftl.browser.NativeRandom.next31() % list.size]
            // HS's hook (CustomEvents.cpp, priority 100): HS_GetRandomCrewList for the race, reseeded, shuffled
            // (HS_ShuffleSortCrewList), the first one (oracle wp5-ship: seeds 1..12 over four crew)
            val g = LuaAdapters.inGame()
            val c = if (g == null || original !is LivingCrew) original
                else xyz.znix.xftl.hs.events.HsCrew.selectRandomCrew(g, original, seed, arg.str(3) ?: "", null) ?: original
            ret.obj(LuaCrewBlueprint(c), A.T_CrewBlueprint)
        })
        // CountCrew(boarders): living crew aboard whose intruder flag is [boarders] (drones not counted)
        LuaReg.method(A.M_ShipManager_CountCrew, LuaMethod {
            val s = self(); val boarders = arg.bool(2)
            ret.num(s.crew.count { c -> c is LivingCrew && !isDrone(c) && !(LuaAdaptersWrites.movedAway && (LuaStored.get(c, A.A_CrewMember_bDead) == true || LuaWritable.readNum(c, A.A_CrewMember_currentShipId)?.toInt() != LuaAdapters.shipId(s))) && (c.mode == AbstractCrew.SlotType.INTRUDER) == boarders && c.health > 0f })
        })
        // CountCrewShipId(room, shipId): living, non-drone crew in that room owned by that ship
        LuaReg.method(A.M_ShipManager_CountCrewShipId, LuaMethod {
            val s = self(); val room = arg.int(2); val id = arg.int(3)
            ret.num(s.crew.count { c -> c.room.id == room && LuaAdapters.crewShipId(c) == id && c is LivingCrew && !isDrone(c) && c.health > 0f })
        })
        // IsCrewFull / IsCrewOverFull (CustomShips.cpp): the crewLimit; the enemy has none
        LuaReg.method(A.M_ShipManager_IsCrewFull, LuaMethod {
            val s = self(); val g = LuaAdapters.inGame()
            ret.bool(g != null && s === g.player && g.isPlayerCrewFull)
        })
        LuaReg.method(A.M_ShipManager_IsCrewOverFull, LuaMethod {
            val s = self(); val g = LuaAdapters.inGame()
            ret.bool(g != null && s === g.player && g.playerHasTooManyCrew())
        })
        // GetCrewmember(slot, present) (CustomCrew.cpp hook): the slot-th own, non-drone crew member with a slot
        LuaReg.method(A.M_ShipManager_GetCrewmember, LuaMethod {
            val s = self(); val slot = arg.int(2)
            ret.obj(s.crew.filter { c -> c is LivingCrew && !isDrone(c) && LuaAdapters.crewShipId(c) != 1 && !noSlot(c) }.getOrNull(slot), A.T_CrewMember)
        })
        // FindCrew(bp) (CustomEvents.cpp hook): the player crew member with that blueprint (name, colours, sex, race)
        LuaReg.method(A.M_ShipManager_FindCrew, LuaMethod {
            val b = arg.obj(2) as? LuaCrewBlueprint ?: return@LuaMethod ret.nil()
            val g = LuaAdapters.inGame() ?: return@LuaMethod ret.nil()
            ret.obj(g.playerCrew.firstOrNull { c -> c === b.crew || (c is LivingCrew && b.crew is LivingCrew && c.info.name == (b.crew as LivingCrew).info.name && c.codename == b.crew.codename) }, A.T_CrewMember)
        })
        // GetSelectedCrewPoint(x, y, intruder) (CustomCrew.cpp hook): within 14 px of the crew member's centre
        LuaReg.method(A.M_ShipManager_GetSelectedCrewPoint, LuaMethod {
            val s = self(); val x = arg.int(2); val y = arg.int(3); val intruder = arg.bool(4)
            ret.obj(s.crew.firstOrNull { c ->
                c.health > 0f && (c.mode == AbstractCrew.SlotType.INTRUDER) == intruder &&
                    (intruder || c.playerControllable) && run {
                        val dx = (c.hsCentreX - x).toDouble(); val dy = (c.hsCentreY - y).toDouble()
                        Math.sqrt(dx * dx + dy * dy) <= 14.0
                    }
            }, A.T_CrewMember)
        })
        // AddCrewMemberFromBlueprint(bp, slot, init, roomId, intruder): a crew member of that race in the room
        LuaReg.method(A.M_ShipManager_AddCrewMemberFromBlueprint, LuaMethod {
            val s = self()
            val race = when (val b = arg.obj(2)) {
                is LuaCrewBlueprint -> b.crew.codename
                is xyz.znix.xftl.crew.CrewBlueprint -> b.name
                else -> return@LuaMethod ret.nil()
            }
            ret.obj(HsCrew.addCrewMemberFromString(s, "", race, arg.bool(6), arg.int(5), arg.bool(4), true), A.T_CrewMember)
        })
        // AddInitialCrew(vector<CrewBlueprint>&): HsShipGeneratorLua.addInitialCrew (1.6.9 0x4a3610)
        LuaReg.method(A.M_ShipManager_AddInitialCrew, LuaMethod {
            val s = self()
            val v = arg.obj(2) as? ListView ?: return@LuaMethod 0
            val races = (0 until v.size()).mapNotNull { i ->
                when (val b = v.get(i)) {
                    is LuaCrewBlueprint -> b.crew.codename
                    is xyz.znix.xftl.crew.CrewBlueprint -> b.name
                    else -> null
                }
            }
            xyz.znix.xftl.hs.ship.HsShipGeneratorLua.addInitialCrew(s, races)
            0
        })
        // TeleportCrew(room, intruders) (TeleporterBugFixes.cpp): the crew of that side leaving the room
        LuaReg.method(A.M_ShipManager_TeleportCrew, LuaMethod {
            val s = self(); val room = arg.int(2); val intruders = arg.bool(3)
            val list = s.crew.filter { c -> c.room.id == room && (c.mode == AbstractCrew.SlotType.INTRUDER) == intruders && c is LivingCrew && c.health > 0f }
            ret.obj(ListView(A.T_vector_CrewMember, A.T_CrewMember, ArrayList<Any?>(list)), A.T_vector_CrewMember)
        })
        // RestoreCrewPositions: the saved stations (true when every crew member went back)
        LuaReg.method(A.M_ShipManager_RestoreCrewPositions, LuaMethod { ret.bool(self().loadCrewPositions()) })
    }

    private fun isDrone(c: AbstractCrew): Boolean = c !is LivingCrew

    private fun noSlot(c: AbstractCrew)
: Boolean = xyz.znix.xftl.hs.ship.HsShipState.crewNoSlot?.invoke(c.codename) == true

    // ---------------------------------------------------------------- Ship (geometry)
    private fun geometry() {
        LuaReg.obj(A.A_Ship_vOuterWalls, ObjGet { o ->
            LuaViews.cached(geom(o), A.A_Ship_vOuterWalls) { x -> ListView(A.T_vector_OuterHull, A.T_OuterHull) { outerWalls(x as Ship) } }
        })
        LuaReg.obj(A.A_Ship_vOuterAirlocks, ObjGet { o ->
            LuaViews.cached(geom(o), A.A_Ship_vOuterAirlocks) { x -> ListView(A.T_vector_Door, A.T_Door) { (x as Ship).doors.filter { it.isAirlock } } }
        })
        LuaReg.obj(A.A_Ship_weaponMounts, ObjGet { o ->
            LuaViews.cached(geom(o), A.A_Ship_weaponMounts) { x ->
                val s = x as Ship
                ListView(A.T_vector_WeaponMount, A.T_WeaponMount) { s.type.hardpoints.indices.map { i -> LuaViews.cached(s.type.hardpoints[i], A.T_WeaponMount) { LuaWeaponMount(s, i) } } }
            }
        })
        LuaReg.str(A.A_Ship_floorImageName, StrGet { o -> geom(o).type.floorImage?.removePrefix("img/") ?: "" })
        LuaReg.str(A.A_Ship_shipImageName, StrGet { o -> geom(o).type.hullImage.first().removePrefix("img/") })
        LuaReg.str(A.A_Ship_cloakImageName, StrGet { o -> geom(o).type.cloakImage?.removePrefix("img/") ?: "ship/${geom(o).type.img}_cloak.png" })
        LuaReg.str(A.A_Ship_shipName, StrGet { o -> geom(o).type.layout })
        // ImageDesc of the floor, hull and cloak images: hull (w, h, x, y) = the layout's <img>; floor and cloak are at
        // <img> + their <offsets> with no size; the cloak texture is loaded when the cloak first shows
        LuaReg.obj(A.A_Ship_shipFloor, ObjGet { o -> imageDesc(geom(o), A.A_Ship_shipFloor) })
        LuaReg.obj(A.A_Ship_shipImage, ObjGet { o -> imageDesc(geom(o), A.A_Ship_shipImage) })
        LuaReg.obj(A.A_Ship_shipImageCloak, ObjGet { o -> imageDesc(geom(o), A.A_Ship_shipImageCloak) })
        LuaReg.obj(A.A_Ship_glowOffset, ObjGet { o -> LuaViews.cached(geom(o), A.A_Ship_glowOffset) { PointValue(0, 0) } })
        // GL primitives: built by the first render (floor, hull, grid, walls); the cloak and door primitives stay null
        for (a in intArrayOf(A.A_Ship_floorPrimitive, A.A_Ship_shipImagePrimitive, A.A_Ship_gridPrimitive, A.A_Ship_wallsPrimitive))
            LuaReg.obj(a, ObjGet { o -> opaque(geom(o), a, A.T_GL_Primitive) }, ObjSet { _, _ -> })
        for (a in intArrayOf(A.A_Ship_cloakPrimitive, A.A_Ship_doorsPrimitive))
            LuaReg.obj(a, ObjGet { null }, ObjSet { _, _ -> })
        LuaReg.obj(A.A_Ship_thrustersImage, ObjGet { null }, ObjSet { _, _ -> })
        LuaReg.obj(A.A_Ship_jumpGlare, ObjGet { LuaGraphics.texture("ship/jump_glare.png") }, ObjSet { _, _ -> })
        LuaReg.obj(A.A_Ship_doorState, ObjGet { o -> opaque(geom(o), A.A_Ship_doorState, T_DOOR_STATE) }, ObjSet { _, _ -> })
        LuaReg.bool(A.A_Ship_lastDoorControlMode, { false }, { _, _ -> })
        LuaReg.num(A.A_Ship_vertical_shift, NumGet { o -> geom(o).type.layoutShift.y.toDouble() }, NumSet { _, _ -> })
        LuaReg.num(A.A_Ship_horizontal_shift, NumGet { o -> geom(o).type.layoutShift.x.toDouble() }, NumSet { _, _ -> })
        LuaReg.bool(A.A_Ship_bDestroyed, { o -> geom(o).isDead }, { _, _ -> })
        LuaReg.bool(A.A_Ship_bCloaked, { o -> geom(o).isCloakActive }, { _, _ -> })
        LuaReg.bool(A.A_Ship_bExperiment, { o -> geom(o).type.isPlayerShip }, { _, _ -> })
        LuaReg.bool(A.A_Ship_bShowEngines, { true }, { _, _ -> })
        LuaReg.obj(A.A_Ship_baseEllipse, ObjGet { o -> ellipse(geom(o), false) })
        LuaReg.method(A.M_Ship_GetBaseEllipse, LuaMethod { ret.obj(ellipse(geom(arg.obj(1)), true), A.T_Ellipse) })
        LuaReg.obj(A.A_Ship_engineAnim, ObjGet { o -> LuaViews.cached(geom(o), A.A_Ship_engineAnim) { s -> LuaAdaptersWritesPictures.engineAnim(s as Ship) } })
        LuaReg.obj(A.A_Ship_extraEngineAnim, ObjGet { o -> LuaViews.cached(geom(o), A.A_Ship_extraEngineAnim) { ListView(A.T_vector_pair_Animation_int8_t, A.T_pair_Animation_int8_t, ArrayList<Any?>()) } })
        LuaReg.obj(A.A_Ship_cloakingTracker, ObjGet { o ->
            LuaViews.cached(geom(o), A.A_Ship_cloakingTracker) {
                val t = LuaStructs.create(A.T_AnimationTracker); t.values[A.A_AnimationTracker_time] = 0.5
                t.values[A.A_AnimationTracker_done] = true; t
            }.also { t ->
                // Ship::OnLoop updates it and stops it when it is done, so a script's start runs out.
                if ((t as LuaStruct).values[A.A_AnimationTracker_running] == true) LuaStored.onNextFrame {
                    t.values[A.A_AnimationTracker_running] = false; t.values[A.A_AnimationTracker_done] = true
                }
            }
        })
        // lockdowns / GetShards: the ship's LockdownShards (Ship.lockdownShards)
        LuaReg.obj(A.A_Ship_lockdowns, ObjGet { o -> LuaViews.cached(geom(o), A.A_Ship_lockdowns) { x -> ListView(A.T_vector_LockdownShard, A.T_LockdownShard) { (x as Ship).lockdownShards.map { LuaLockdownShard.copy(it) } } } })
        LuaReg.method(A.M_Ship_GetShards, LuaMethod { ret.obj(ListView(A.T_vector_p_LockdownShard, A.T_LockdownShard, ArrayList<Any?>(geom(arg.obj(1)).lockdownShards.map { LuaLockdownShard.of(it) })), A.T_vector_p_LockdownShard) })
        lockdownShards()

        // WeaponMount members: the raw <mount> attributes; slide 0 down, 1 right, 2 up, 3 left, 4 none
        LuaReg.obj(A.A_WeaponMount_position, ObjGet { o ->
            val m = o as LuaWeaponMount; val h = m.ship.type.hardpoints[m.index]
            val raw = h.position - m.ship.type.hullOffset - m.ship.type.roomOffset * xyz.znix.xftl.Constants.ROOM_SIZE
            LuaViews.cached(m, A.A_WeaponMount_position) { PointValue(raw.x, raw.y) }
        })
        LuaReg.bool(A.A_WeaponMount_mirror, { o -> (o as LuaWeaponMount).let { it.ship.type.hardpoints[it.index].mirror } })
        LuaReg.bool(A.A_WeaponMount_rotate, { o -> (o as LuaWeaponMount).let { it.ship.type.hardpoints[it.index].rotate } })
        LuaReg.num(A.A_WeaponMount_slide, NumGet { o ->
            val m = o as LuaWeaponMount
            when (m.ship.type.hardpoints[m.index].slide) {
                xyz.znix.xftl.math.Direction.DOWN -> 0.0; xyz.znix.xftl.math.Direction.RIGHT -> 1.0
                xyz.znix.xftl.math.Direction.UP -> 2.0; xyz.znix.xftl.math.Direction.LEFT -> 3.0
                else -> 4.0
            }
        })
        LuaReg.num(A.A_WeaponMount_gib, NumGet { o -> (o as LuaWeaponMount).let { it.ship.type.hardpoints[it.index].gib.toDouble() } })

        // room functions
        // EmptySlots(room) (1.6.9 0x485a20): frees every slot of both sides; void, the wrapper returns the loop count
        LuaReg.method(A.M_Ship_EmptySlots, LuaMethod {
            val r = room(geom(arg.obj(1)), arg.int(2)) ?: return@LuaMethod ret.num(0)
            ret.num(r.hsEmptySlots())
        })
        LuaReg.method(A.M_Ship_FullRoom, LuaMethod {
            val r = room(geom(arg.obj(1)), arg.int(2)) ?: return@LuaMethod ret.bool(false)
            ret.bool(!r.anySlotsFree(if (arg.bool(3)) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW))
        })
        LuaReg.method(A.M_Ship_GetAvailableRoomSlot, LuaMethod {
            val r = room(geom(arg.obj(1)), arg.int(2)) ?: return@LuaMethod ret.num(-1)
            val p = r.firstFreeSlot(if (arg.bool(3)) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW)
            ret.num(if (p == null) -1 else r.pointToSlot(p))
        })
        LuaReg.method(A.M_Ship_RoomLocked, LuaMethod { ret.bool(room(geom(arg.obj(1)), arg.int(2))?.isLockedDown == true) })
        LuaReg.method(A.M_Ship_SetRoomBlackout, LuaMethod {
            room(geom(arg.obj(1)), arg.int(2))?.let { HsShipLuaState.of(it.ship).blackout[it.id] = arg.bool(3) }
            0
        })
        LuaReg.method(A.M_Ship_SetSelectedRoom, LuaMethod { HsShipLuaState.of(geom(arg.obj(1))).selectedRoom = arg.int(2); 0 })
        // GetSelectedRoomId(x, y, includeWalls): ShipGraph::GetSelectedRoom
        LuaReg.method(A.M_Ship_GetSelectedRoomId__SWIG_0, LuaMethod {
            val p = arg.obj(2) as PointValue
            ret.num(selectedRoom(geom(arg.obj(1)), p.x, p.y, arg.bool(3)))
        })
        LuaReg.method(A.M_Ship_GetSelectedRoomId__SWIG_1, LuaMethod { ret.num(selectedRoom(geom(arg.obj(1)), arg.int(2), arg.int(3), arg.bool(4))) })
        // LockdownRoom(roomId, pos): Ship::LockdownRoom from that ship pixel (both overloads: Pointf or Point)
        val lockdownRoom = LuaMethod {
            val r = room(geom(arg.obj(1)), arg.int(2)) ?: return@LuaMethod 0
            when (val p = arg.obj(3)) {
                is PointfValue -> r.lockdown(p.x, p.y)
                is LuaPointfMember -> r.lockdown(p.x, p.y)
                is PointValue -> r.lockdown(p.x.toFloat(), p.y.toFloat())
                else -> r.lockdown()
            }
            0
        }
        LuaReg.method(A.M_Ship_LockdownRoom__SWIG_0, lockdownRoom)
        LuaReg.method(A.M_Ship_LockdownRoom__SWIG_1, lockdownRoom)
        // BreachRandomHull(room): a breach at a random cell of the room; BreachSpecificHull(x, y): the cell at grid x, y
        LuaReg.method(A.M_Ship_BreachRandomHull, LuaMethod { room(geom(arg.obj(1)), arg.int(2))?.spawnBreach(); 0 })
        LuaReg.method(A.M_Ship_BreachSpecificHull, LuaMethod {
            val s = geom(arg.obj(1)); val gx = arg.int(2) - s.offset.x; val gy = arg.int(3) - s.offset.y
            val r = s.rooms.firstOrNull { gx in it.x until it.x + it.width && gy in it.y until it.y + it.height }
            r?.spawnBreachAt((gy - r.y) * r.width + (gx - r.x))
            0
        })
        // GetHullBreaches(onlyDamaged): the outer hull pieces (one per room cell), only the breached ones when asked
        LuaReg.method(A.M_Ship_GetHullBreaches, LuaMethod {
            val s = geom(arg.obj(1)); val damaged = arg.bool(2)
            val list = outerWalls(s).filter { !damaged || it.room.breaches[it.slot] != null }
            ret.obj(ListView(A.T_vector_Repairable, A.T_Repairable, ArrayList<Any?>(list)), A.T_vector_Repairable)
        })

        // OuterHull (a Repairable per cell): roomId, pLoc (the cell's corner), fDamage 0 / 100 when breached
        repairable(LuaOuterHull::class.java,
            // fDamage: what is left of the breach (a partly repaired breach is below 100)
            damage = { (it as LuaOuterHull).let { h -> (h.room.breaches[h.slot]?.health ?: 0f) * 100f } },
            setDamage = { o, v ->
                val h = o as LuaOuterHull
                if (v <= 0f) h.room.breaches[h.slot] = null
                else {
                    if (h.room.breaches[h.slot] == null) h.room.spawnBreachAt(h.slot)
                    h.room.breaches[h.slot]?.health = minOf(1f, v / 100f)
                }
            },
            roomId = { (it as LuaOuterHull).room.id },
            loc = { val h = it as LuaOuterHull; val p = h.room.slotToPoint(h.slot); PointValue(h.room.offsetX + p.x * 35, h.room.offsetY + p.y * 35) },
            name = { "" }, ship = { (it as LuaOuterHull).room.ship })
        LuaReg.obj(A.A_OuterHull_breach, ObjGet { o -> LuaViews.cached(o!!, A.A_OuterHull_breach) { LuaStructs.create(A.T_Animation) } })
        LuaReg.obj(A.A_OuterHull_heal, ObjGet { o -> LuaViews.cached(o!!, A.A_OuterHull_heal) { LuaStructs.create(A.T_Animation) } })
    }

    private fun outerWalls(s: Ship): List<LuaOuterHull> =
        // Ship::OnInit fills vOuterWalls room by room, each room column by column
        s.rooms.flatMap { r -> (0 until r.width).flatMap { x -> (0 until r.height).map { y -> y * r.width + x } }.map { slot -> LuaViews.cached(r.slotKey(slot), A.T_OuterHull) { LuaOuterHull(r, slot) } } }

    /** A stable identity object per (room, slot) for view caching. */
    private val slotKeys = java.util.IdentityHashMap<Room, Array<Any>>()
    private fun Room.slotKey(slot: Int): Any = slotKeys.getOrPut(this) { Array(cellCount) { Any() } }[slot]

    private fun room(s: Ship, id: Int): Room? = s.rooms.firstOrNull { it.id == id }

    /** ShipGraph::GetSelectedRoom (0x45aed0): the first room containing the point (edges included with walls). */
    @JvmStatic
    fun selectedRoom(s: Ship, x: Int, y: Int, walls: Boolean): Int = s.rooms.firstOrNull { r ->
        val x0 = r.offsetX; val y0 = r.offsetY; val x1 = x0 + r.pixelWidth; val y1 = y0 + r.pixelHeight
        if (walls) x in x0..x1 && y in y0..y1 else x > x0 && x < x1 && y > y0 && y < y1
    }?.id ?: -1

    private fun imageDesc(s: Ship, a: Int): LuaStruct = LuaViews.cached(s, a) {
        val d = LuaStructs.create(A.T_ImageDesc)
        val t = s.type
        // the <img> node is the hull offset minus the room offset (blueprint hullOffset is relative to the rooms)
        val img = t.hullOffset
        when (a) {
            A.A_Ship_shipImage -> {
                d.values[A.A_ImageDesc_w] = s.hullWidth.toDouble(); d.values[A.A_ImageDesc_h] = s.hullHeight.toDouble()
                d.values[A.A_ImageDesc_x] = img.x.toDouble(); d.values[A.A_ImageDesc_y] = img.y.toDouble()
                d.values[A.A_ImageDesc_tex] = LuaGraphics.texture(t.hullImage.first().removePrefix("img/"))
                d.onWrite = {
                    s.hsHullShiftX = ((d.values[A.A_ImageDesc_x] as? Double ?: 0.0).toInt()) - img.x
                    s.hsHullShiftY = ((d.values[A.A_ImageDesc_y] as? Double ?: 0.0).toInt()) - img.y
                }
            }
            A.A_Ship_shipFloor -> {
                d.values[A.A_ImageDesc_x] = (img.x + t.floorOffset.x).toDouble(); d.values[A.A_ImageDesc_y] = (img.y + t.floorOffset.y).toDouble()
                t.floorImage?.let { d.values[A.A_ImageDesc_tex] = LuaGraphics.texture(it.removePrefix("img/")) }
            }
            else -> {
                d.values[A.A_ImageDesc_x] = (img.x + t.cloakOffset.x).toDouble(); d.values[A.A_ImageDesc_y] = (img.y + t.cloakOffset.y).toDouble()
            }
        }
        d.values[A.A_ImageDesc_rot] = 0.0
        d
    }

    /** Ship::baseEllipse is the layout's ELLIPSE (centre = its offset); GetBaseEllipse moves it to the graph centre. */
    private fun ellipse(s: Ship, centred: Boolean): LuaStruct {
        val e = LuaStructs.create(A.T_Ellipse)
        val r = s.type.shieldEllipse
        val c = LuaStructs.member(e, A.A_Ellipse_center) as LuaStruct
        var cx = r.pos.x; var cy = r.pos.y
        if (centred) { val b = LuaShipGraphApi.box(s); cx += b[0] + b[2] / 2; cy += b[1] + b[3] / 2 }
        LuaStructs.setFields(c, cx.toDouble(), cy.toDouble())
        e.values[A.A_Ellipse_a] = r.size.x.toDouble(); e.values[A.A_Ellipse_b] = r.size.y.toDouble()
        return e
    }

    // ---------------------------------------------------------------- Repairable (shared by systems, fires, hull pieces)
    /**
     * Repairable members for one engine class: the ids are shared with the systems' Repairable adapters (ShipSystem
     * derives from it), so this wraps whatever was registered before and handles only [cls].
     */
    private fun repairable(cls: Class<*>, damage: (Any) -> Float, setDamage: (Any, Float) -> Unit, roomId: (Any) -> Int,
                           loc: (Any) -> PointValue, name: (Any) -> String, ship: (Any) -> Ship) {
        fun wrapNum(a: Int, get: (Any) -> Double, set: ((Any, Double) -> Unit)?) {
            val pg = LuaReg.numGet[a]; val ps = LuaReg.numSet[a]
            LuaReg.num(a, NumGet { o -> if (o != null && cls.isInstance(o)) get(o) else (pg ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o) },
                NumSet { o, v -> if (o != null && cls.isInstance(o)) set?.invoke(o, v) else (ps ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).set(o, v) })
        }
        wrapNum(A.A_Repairable_fDamage, { damage(it).toDouble() }, { o, v -> setDamage(o, v.toFloat()) })
        wrapNum(A.A_Repairable_fMaxDamage, { 100.0 }, null)
        wrapNum(A.A_Repairable_roomId, { roomId(it).toDouble() }, null)
        wrapNum(A.A_Repairable_iRepairCount, { o -> if (o is LuaFire) o.repairCount else 0.0 }, { o, v -> if (o is LuaFire) o.repairCount = v })
        val pso = LuaReg.strGet[A.A_Repairable_name]; val pss = LuaReg.strSet[A.A_Repairable_name]
        LuaReg.str(A.A_Repairable_name, StrGet { o -> if (o != null && cls.isInstance(o)) name(o) else (pso ?: throw LuaError("Hyperspace Lua API not available in the browser engine: Repairable.name")).get(o) },
            StrSet { o, v -> if (o != null && cls.isInstance(o)) Unit else pss?.set(o, v) })
        val pl = LuaReg.objGet[A.A_Repairable_pLoc]
        LuaReg.obj(A.A_Repairable_pLoc, ObjGet { o -> if (o != null && cls.isInstance(o)) loc(o) else (pl ?: throw LuaError("Hyperspace Lua API not available in the browser engine: Repairable.pLoc")).get(o) })
        val pobj = LuaReg.objGet[A.A_Repairable_shipObj]
        LuaReg.obj(A.A_Repairable_shipObj, ObjGet { o -> if (o != null && cls.isInstance(o)) ship(o) else (pobj ?: throw LuaError("Hyperspace Lua API not available in the browser engine: Repairable.shipObj")).get(o) })
    }

    // ---------------------------------------------------------------- ShipGraph
    private fun graph() {
        LuaReg.obj(A.A_ShipGraph_doors, ObjGet { o ->
            val s = graphShip(o) ?: return@ObjGet ListView(A.T_vector_Door, A.T_Door) { emptyList<Any?>() }
            LuaViews.cached(s, A.A_ShipGraph_doors) { x -> ListView(A.T_vector_Door, A.T_Door) { graphDoors(x as Ship) } }
        })
        LuaReg.obj(A.A_ShipGraph_doorCounts, ObjGet { o ->
            val s = graphShip(o) ?: return@ObjGet ListView(A.T_vector_int, 0) { emptyList<Any?>() }
            LuaViews.cached(s, A.A_ShipGraph_doorCounts) { x -> ListView(A.T_vector_int, 0) { (x as Ship).rooms.map { r -> doorsOf(r).size.toDouble() } } }
        })
        LuaReg.obj(A.A_ShipGraph_center, ObjGet { o ->
            val s = graphShip(o) ?: return@ObjGet PointValue(0, 0)
            LuaViews.cached(o!!, A.A_ShipGraph_center) { val b = LuaShipGraphApi.box(s); PointValue(b[0] + b[2] / 2, b[1] + b[3] / 2) }
        })
        // world position/heading: FTL 1.6 ships never move or turn in space (-1,-1 / 0 until set)
        LuaReg.obj(A.A_ShipGraph_worldPosition, ObjGet { o -> HsShipLuaState.graph(o as LuaShipGraph).position })
        LuaReg.obj(A.A_ShipGraph_lastWorldPosition, ObjGet { o -> HsShipLuaState.graph(o as LuaShipGraph).lastPosition })
        LuaReg.num(A.A_ShipGraph_worldHeading, NumGet { o -> HsShipLuaState.graph(o as LuaShipGraph).heading.toDouble() },
            NumSet { o, v -> HsShipLuaState.graph(o as LuaShipGraph).heading = v.toFloat() })
        LuaReg.num(A.A_ShipGraph_lastWorldHeading, NumGet { o -> HsShipLuaState.graph(o as LuaShipGraph).lastHeading.toDouble() },
            NumSet { o, v -> HsShipLuaState.graph(o as LuaShipGraph).lastHeading = v.toFloat() })
        LuaReg.str(A.A_ShipGraph_shipName, StrGet { o -> graphShip(o)?.type?.layout ?: "" })

        fun g(): LuaShipGraph = arg.obj(1) as LuaShipGraph
        fun rooms(): List<Room> = g().rooms
        LuaReg.method(A.M_ShipGraph_DoorCount, LuaMethod { ret.num(rooms().firstOrNull { it.id == arg.int(2) }?.let { doorsOf(it).size } ?: 0) })
        LuaReg.method(A.M_ShipGraph_GetDoors, LuaMethod {
            val r = rooms().firstOrNull { it.id == arg.int(2) }
            ret.obj(ListView(A.T_vector_Door, A.T_Door, ArrayList<Any?>(if (r == null) emptyList() else doorsOf(r))), A.T_vector_Door)
        })
        LuaReg.method(A.M_ShipGraph_GetNumSlots, LuaMethod { ret.num(rooms().firstOrNull { it.id == arg.int(2) }?.cellCount ?: 0) })
        LuaReg.method(A.M_ShipGraph_GetRoomOxygen, LuaMethod { ret.num(((rooms().firstOrNull { it.id == arg.int(2) }?.oxygen ?: 0f) * 100f).toDouble()) })
        LuaReg.method(A.M_ShipGraph_GetRoomBlackedOut, LuaMethod {
            val r = rooms().firstOrNull { it.id == arg.int(2) }
            ret.bool(r != null && HsShipLuaState.of(r.ship).blackout[r.id] == true)
        })
        LuaReg.method(A.M_ShipGraph_IsRoomConnected, LuaMethod {
            val a = arg.int(2); val b = arg.int(3)
            val r = rooms().firstOrNull { it.id == a }
            ret.bool(a == b || (r != null && doorsOf(r).any { d -> d.other(r)?.id == b }))
        })
        // slot positions: the cell's centre (17 px in), FTL's slot order (row major)
        // GetSlotWorldPosition (0x458f80): the slot's cell centre, slot = row * width + column (not bounds checked)
        LuaReg.method(A.M_ShipGraph_GetSlotWorldPosition, LuaMethod {
            val r = rooms().getOrNull(arg.int(3)) ?: return@LuaMethod point(0, 0)
            val slot = arg.int(2); val w = r.pixelWidth / 35
            point(r.offsetX + (slot % w) * 35 + 17, r.offsetY + (slot / w) * 35 + 17)
        })
        // GetSlotRenderPosition (0x459000): the same, moved 7 px (back for an intruder) when both sides use the slot
        LuaReg.method(A.M_ShipGraph_GetSlotRenderPosition, LuaMethod {
            val r = rooms().getOrNull(arg.int(3)) ?: return@LuaMethod point(0, 0)
            val slot = arg.int(2); val intruder = arg.bool(4); val w = r.pixelWidth / 35
            var x = r.offsetX + (slot % w) * 35 + 17; var y = r.offsetY + (slot / w) * 35 + 17
            if (slot in 0 until r.cellCount) {
                val p = r.slotToPoint(slot)
                if (r.slotOccupier(p, AbstractCrew.SlotType.CREW) != null && r.slotOccupier(p, AbstractCrew.SlotType.INTRUDER) != null) {
                    if (intruder) { x -= 7; y -= 7 } else { x += 7; y += 7 }
                }
            }
            point(x, y)
        })
        LuaReg.method(A.M_ShipGraph_ConnectivityDFS, LuaMethod {
            val start = rooms().firstOrNull { it.id == arg.int(2) }
            val out = ArrayList<Any?>()
            if (start != null) {
                val seen = HashSet<Room>(); val stack = ArrayDeque<Room>(); stack.addLast(start)
                while (stack.isNotEmpty()) {
                    val r = stack.removeLast(); if (!seen.add(r)) continue
                    out += r.id
                    for (d in doorsOf(r)) d.other(r)?.let { if (it !in seen) stack.addLast(it) }
                }
            }
            ret.obj(ListView(A.T_vector_int, 0, out), A.T_vector_int)
        })
        LuaReg.method(A.M_ShipGraph_ContainsPoint, LuaMethod {
            val x = arg.int(2); val y = arg.int(3)
            val s = g().ship ?: return@LuaMethod ret.bool(false)
            ret.bool(selectedRoom(s, x, y, true) != -1)
        })
        // TranslateToGrid(x, y) = (x / 35, y / 35) (integer division toward zero); TranslateFromGrid = (x * 35, y * 35)
        // (both overloads, Point or x and y, told apart by the argument)
        val toGrid = LuaMethod { val p = arg.obj(1) as? PointValue; if (p != null && arg.isNil(2)) point(p.x / 35, p.y / 35) else point(arg.int(1) / 35, arg.int(2) / 35) }
        val fromGrid = LuaMethod { val p = arg.obj(1) as? PointValue; if (p != null && arg.isNil(2)) point(p.x * 35, p.y * 35) else point(arg.int(1) * 35, arg.int(2) * 35) }
        for (m in intArrayOf(A.M_ShipGraph_TranslateToGrid__SWIG_0, A.M_ShipGraph_TranslateToGrid__SWIG_1)) LuaReg.method(m, toGrid)
        for (m in intArrayOf(A.M_ShipGraph_TranslateFromGrid__SWIG_0, A.M_ShipGraph_TranslateFromGrid__SWIG_1)) LuaReg.method(m, fromGrid)
        // ConnectedGridSquares(x1, y1, x2, y2): 0 not adjacent/not connected, 2 same room, 3 through a wall, else the door
        // ConnectedGridSquares (1.6.9 0x459760): 0 not connected, 3 the same or a neighbouring cell of one room or an
        // open door, 2 a closed door, 4 a closed blast door, 6 a locked-down door (the lockedDown tracker)
        fun connected(s: Ship?, x1: Int, y1: Int, x2: Int, y2: Int): Int {
            s ?: return 0
            val a = cellRoom(s, x1, y1) ?: return 0; val b = cellRoom(s, x2, y2) ?: return 0
            if (a === b) {
                val near = (x1 == x2 && y1 == y2) || (x1 == x2 && Math.abs(y1 - y2) == 1) || (y1 == y2 && Math.abs(x1 - x2) == 1)
                return if (near) 3 else 0
            }
            val d = connectingDoor(s, x1, y1, x2, y2) ?: return 0
            if (d.open) return 3
            if (d.lockdownTimer > 0f) return 6
            return if ((HsShipLuaState.door(d).blast) != 0 || d.level >= 2) 4 else 2
        }
        LuaReg.method(A.M_ShipGraph_ConnectedGridSquares, LuaMethod { ret.num(connected(g().ship, arg.int(2), arg.int(3), arg.int(4), arg.int(5))) })
        LuaReg.method(A.M_ShipGraph_ConnectedGridSquaresPoint, LuaMethod {
            val a = arg.obj(2) as PointValue; val b = arg.obj(3) as PointValue
            ret.num(connected(g().ship, a.x, a.y, b.x, b.y))
        })
        // ConnectingDoor(x1, y1, x2, y2) or (Point, Point), on grid cells (told apart by the argument)
        val connecting = LuaMethod {
            val s = g().ship ?: return@LuaMethod ret.nil()
            val a = arg.obj(2) as? PointValue; val b = arg.obj(3) as? PointValue
            // the Point form takes grid cells too (oracle wp5-ship: pixel points find no door)
            if (a != null && b != null && arg.isNil(4))
                ret.obj(connectingDoor(s, a.x, a.y, b.x, b.y), A.T_Door)
            else ret.obj(connectingDoor(s, arg.int(2), arg.int(3), arg.int(4), arg.int(5)), A.T_Door)
        }
        for (m in intArrayOf(A.M_ShipGraph_ConnectingDoor__SWIG_0, A.M_ShipGraph_ConnectingDoor__SWIG_1)) LuaReg.method(m, connecting)
        LuaReg.method(A.M_ShipGraph_Dijkstra, LuaMethod { ret.obj(LuaOpaque(T_PATH, null), T_PATH) })
        LuaReg.method(A.M_ShipGraph_FindPath, LuaMethod { ret.obj(LuaOpaque(T_PATH, LuaAdaptersSweepCrew.pathOf(arg.obj(2), arg.obj(3), arg.int(4))), T_PATH) }) // start/finish for CrewMember::SetPath (sweep A)
        // GetClosestSlot(point, shipId, intruder): the slot (room, slot, world location) a crew member would take
        LuaReg.method(A.M_ShipGraph_GetClosestSlot, LuaMethod {
            val p = arg.obj(2) as PointValue
            val s = g().ship
            val sl = LuaStructs.create(A.T_Slot)
            val r = if (s == null) null else s.rooms.firstOrNull { it.id == selectedRoom(s, p.x, p.y, true) }
            sl.values[A.A_Slot_roomId] = (r?.id ?: -1).toDouble()
            sl.values[A.A_Slot_slotId] = if (r == null) -1.0 else 1.0
            LuaStructs.setFields(LuaStructs.member(sl, A.A_Slot_worldLocation) as LuaStruct, -1.0, -1.0)
            ret.obj(sl, A.T_Slot)
        })
        // GetIntoRoom(room, p) -> Room::GetIntoRoom (0x46ae10): a point within 16 px of an edge (or outside) moves to
        // the centre of the cell one pixel further in; a point well inside gives (-1, -1); a bad room id (-1, -1)
        LuaReg.method(A.M_ShipGraph_GetIntoRoom, LuaMethod {
            val r = rooms().getOrNull(arg.int(2)) ?: return@LuaMethod point(-1, -1)
            val p = arg.obj(3) as PointValue
            val x0 = r.offsetX; val y0 = r.offsetY; val w = r.pixelWidth; val h = r.pixelHeight
            val nearY = (y0 + h) - p.y < 16 || p.y - y0 < 16
            if (!nearY && (x0 + w) - p.x > 15 && p.x - x0 > 15) return@LuaMethod point(-1, -1)
            val cx = w / 2 + x0; val cy = h / 2 + y0
            val gx = (p.x - 1 + (if (p.x < cx) 2 else 0)) / 35; val gy = (p.y - 1 + (if (p.y < cy) 2 else 0)) / 35
            point(gx * 35 + 17, gy * 35 + 17)
        })
        LuaReg.method(A.M_ShipGraph_ConvertToWorldAngle, LuaMethod { ret.num(arg.num(2)) })
        LuaReg.method(A.M_ShipGraph_ConvertToLocalAngle, LuaMethod { ret.num(arg.num(2)) })
        // ConvertToWorldPosition (0x45ab00): worldPosition + 0.2 * (rotate(local, worldHeading, centre) - centre);
        // ConvertToLocalPosition (0x45abc0) inverts it with the current or the last (past) heading and position
        LuaReg.method(A.M_ShipGraph_ConvertToWorldPosition, LuaMethod {
            val gr = g(); val p = arg.obj(2) as PointfValue; val c = centreOf(gr); val st = HsShipLuaState.graph(gr)
            val (rx, ry) = rotate(p.x, p.y, st.heading, c.first, c.second)
            pointf(st.position.x + 0.2f * (rx - c.first), st.position.y + 0.2f * (ry - c.second))
        })
        LuaReg.method(A.M_ShipGraph_ConvertToLocalPosition, LuaMethod {
            val gr = g(); val p = arg.obj(2) as PointfValue; val past = arg.bool(3); val c = centreOf(gr); val st = HsShipLuaState.graph(gr)
            val pos = if (past) st.lastPosition else st.position
            val heading = if (past) st.lastHeading else st.heading
            val lx = (p.x - pos.x) * 5f + c.first; val ly = (p.y - pos.y) * 5f + c.second
            val (rx, ry) = rotate(lx, ly, -heading, c.first, c.second)
            pointf(rx, ry)
        })
        // PopClosestDoor(doors, distances) (1.6.9 0x459ab0): distances are indexed by door id; the first door whose
        // distance is below the best so far (kept truncated to int) wins and leaves `doors`; `distances` is unchanged
        LuaReg.method(A.M_ShipGraph_PopClosestDoor, LuaMethod {
            val doors = arg.obj(2) as ListView; val dist = arg.obj(3) as ListView
            if (doors.size() == 0) throw LuaError("PopClosestDoor: no doors")
            fun distOf(door: Int): Float = (dist.get(door) as Number).toFloat()
            var bestIndex = 0; var best = (doors.get(0) as Number).toInt(); var bestDist = distOf(best).toInt()
            for (i in 1 until doors.size()) {
                val door = (doors.get(i) as Number).toInt(); val d = distOf(door)
                if (d < bestDist.toFloat()) { bestIndex = i; bestDist = d.toInt(); best = door }
            }
            val rest = (0 until doors.size()).filter { it != bestIndex }.map { doors.get(it) }
            doors.clear(); rest.forEach { doors.add(it) }
            ret.num(best)
        })
        LuaReg.method(A.M_ShipGraph_ComputeCenter, LuaMethod { 0 })
    }

    private fun centreOf(g: LuaShipGraph): Pair<Float, Float> {
        val s = g.ship ?: return Pair(0f, 0f)
        val b = LuaShipGraphApi.box(s)
        return Pair((b[0] + b[2] / 2).toFloat(), (b[1] + b[3] / 2).toFloat())
    }

    private fun rotate(x: Float, y: Float, degrees: Float, cx: Float, cy: Float): Pair<Float, Float> {
        if (degrees == 0f) return Pair(x, y)
        val a = Math.toRadians(degrees.toDouble()); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat()
        val dx = x - cx; val dy = y - cy
        return Pair(cx + dx * c - dy * s, cy + dx * s + dy * c)
    }

    /** ShipGraph::doors: the inner doors then the airlocks, by door id (the layout order). */
    /** ShipGraph::doors: the doors between rooms (the airlocks are Ship::vOuterAirlocks only). */
    private fun graphDoors(s: Ship): List<Door> = s.doors.filter { !it.isAirlock }

    private fun doorsOf(r: Room): List<Door> = r.ship.doors.filter { !it.isAirlock && (it.left === r || it.right === r) }

    private fun cellRoom(s: Ship, gx: Int, gy: Int): Room? {
        val x = gx - s.offset.x; val y = gy - s.offset.y
        return s.rooms.firstOrNull { x in it.x until it.x + it.width && y in it.y until it.y + it.height }
    }

    private fun connectingDoor(s: Ship, x1: Int, y1: Int, x2: Int, y2: Int): Door? {
        if (Math.abs(x1 - x2) + Math.abs(y1 - y2) != 1) return null
        val a = cellRoom(s, x1, y1) ?: return null; val b = cellRoom(s, x2, y2) ?: return null
        if (a === b) return null
        val vertical = x1 != x2
        val dx = maxOf(x1, x2) - s.offset.x; val dy = maxOf(y1, y2) - s.offset.y
        return s.doors.firstOrNull { d -> !d.isAirlock && d.isVertical == vertical && d.position.x == (if (vertical) dx else x1 - s.offset.x) && d.position.y == (if (vertical) y1 - s.offset.y else dy) }
    }

    // ---------------------------------------------------------------- doors
    private fun doors() {
        fun d(o: Any?) = o as Door
        LuaReg.num(A.A_Door_iDoorId, NumGet { o -> if (d(o).isAirlock) -1.0 else d(o).ship.doors.filter { !it.isAirlock }.indexOf(d(o)).toDouble() })
        LuaReg.bool(A.A_Door_bOpen, { d(it).open }, { o, v -> d(o).open = v })
        LuaReg.num(A.A_Door_iBlast, NumGet { o -> (HsShipLuaState.door(d(o)).blast).toDouble() }, NumSet { o, v -> HsShipLuaState.door(d(o)).blast = v.toInt() })
        LuaReg.bool(A.A_Door_bFakeOpen, { false }, { _, _ -> })
        LuaReg.num(A.A_Door_width, NumGet { o -> if (d(o).isVertical) 6.0 else 17.0 })
        LuaReg.num(A.A_Door_height, NumGet { o -> if (d(o).isVertical) 17.0 else 6.0 })
        // outline / highlight GL_Primitive: built by Door::OnInit (drawn by the engine's own renderer)
        for (a in intArrayOf(A.A_Door_outlinePrimitive, A.A_Door_highlightPrimitive))
            LuaReg.obj(a, ObjGet { o -> opaque(o!!, a, A.T_GL_Primitive) }, ObjSet { _, _ -> })
        // baseHealth / health: INT_MAX while locked down (Door::SetLockdown), the engine counts its own lockdown hits
        LuaReg.num(A.A_Door_baseHealth, NumGet { o -> val r = d(o); if (r.lockdownTimer > 0f) Int.MAX_VALUE.toDouble() else (HsShipLuaState.door(r).baseHealth ?: r.maxHealth).toDouble() }, NumSet { o, v -> HsShipLuaState.door(d(o)).baseHealth = v.toInt() })
        LuaReg.num(A.A_Door_health, NumGet { o -> val r = d(o); if (r.lockdownTimer > 0f) Int.MAX_VALUE.toDouble() else (r.maxHealth - r.damage).toDouble() },
            NumSet { o, v -> d(o).luaSetHealth(v.toInt()) })
        LuaReg.num(A.A_Door_doorLevel, NumGet { o -> (HsShipLuaState.door(d(o)).level ?: d(o).level).toDouble() }, NumSet { o, v -> HsShipLuaState.door(d(o)).level = v.toInt(); d(o).hsLevel = v.toInt() })
        LuaReg.bool(A.A_Door_bIoned, { o -> HsShipLuaState.door(d(o)).ioned }, { o, v -> HsShipLuaState.door(d(o)).ioned = v })
        LuaReg.num(A.A_Door_fakeOpenTimer, NumGet { 0.0 }, NumSet { _, _ -> })
        // lastbase: the base health before a lockdown (restored after it), the door's own maximum
        LuaReg.num(A.A_Door_lastbase, NumGet { o -> (HsShipLuaState.door(d(o)).lastbase ?: d(o).maxHealth).toDouble() }, NumSet { o, v -> HsShipLuaState.door(d(o)).lastbase = v.toInt() })
        LuaReg.num(A.A_Door_iHacked, NumGet { o -> HsShipLuaState.door(d(o)).hacked.toDouble() }, NumSet { o, v -> HsShipLuaState.door(d(o)).hacked = v.toInt() })
        // x, y: the door's centre on the room grid edge (pixels); bVertical
        LuaReg.num(A.A_Door_x, NumGet { o -> val r = d(o); (r.offsetX + if (r.isVertical) 0 else 17).toDouble() })
        LuaReg.num(A.A_Door_y, NumGet { o -> val r = d(o); (r.offsetY + if (r.isVertical) 17 else 0).toDouble() })
        LuaReg.bool(A.A_Door_bVertical, { d(it).isVertical })
        LuaReg.obj(A.A_Door__selectable, ObjGet { o -> LuaViews.cached(o!!, A.A_Door__selectable) { LuaStructs.create(A.T_Selectable) } })
        LuaReg.obj(A.A_Door_gotHit, ObjGet { o -> LuaViews.cached(o!!, A.A_Door_gotHit) { x -> LuaAdaptersWritesPictures.doorGotHit(x as Door) } })
        LuaReg.obj(A.A_Door_forcedOpen, ObjGet { o -> LuaViews.cached(o!!, A.A_Door_forcedOpen) { x -> LuaAdaptersWritesPictures.doorForced(x as Door) } })
        // lockedDown: the lockdown crystal animation, running while the door is locked down
        LuaReg.obj(A.A_Door_lockedDown, ObjGet { o ->
            val t = LuaViews.cached(o!!, A.A_Door_lockedDown) { LuaStructs.create(A.T_AnimationTracker).also { it.values[A.A_AnimationTracker_done] = true } } as LuaStruct
            t.values[A.A_AnimationTracker_running] = d(o).lockdownTimer > 0f
            t.values[A.A_AnimationTracker_time] = 12.0 // the lockdown animation's length (oracle: time 12; sweep A)
            t
        })
        for (a in intArrayOf(A.A_Door_doorAnim, A.A_Door_doorAnimLarge))
            LuaReg.obj(a, ObjGet { o -> LuaViews.cached(o!!, a) { x -> LuaAdaptersWritesPictures.doorAnim(x as Door, a == A.A_Door_doorAnim) } })
        // ApplyDamage(amount) (1.6.9 0x463f30): one hit whatever the amount, nothing when open; always false
        LuaReg.method(A.M_Door_ApplyDamage, LuaMethod {
            val door = d(arg.obj(1))
            ret.bool(door.luaApplyDamage())
        })
    }

    // ---------------------------------------------------------------- rooms
    private fun rooms() {
        fun r(o: Any?) = o as Room
        LuaReg.bool(A.A_Room_bBlackedOut, { o -> HsShipLuaState.of(r(o).ship).blackout[r(o).id] == true }, { o, v -> HsShipLuaState.of(r(o).ship).blackout[r(o).id] = v })
        // highlight GL_Primitives: built by Room::Room (drawn by the engine's own renderer)
        for (a in intArrayOf(A.A_Room_highlightPrimitive, A.A_Room_highlightPrimitive2))
            LuaReg.obj(a, ObjGet { o -> opaque(o!!, a, A.T_GL_Primitive) }, ObjSet { _, _ -> })
        // FillSlot(slot, intruder) (1.6.9 0x46a1d0): the slot of that side is taken
        LuaReg.method(A.M_Room_FillSlot, LuaMethod { r(arg.obj(1)).hsFillSlot(arg.int(2), arg.bool(3)); 0 })
    }

    // ---------------------------------------------------------------- fires
    private fun fires() {
        fun fire(s: Ship, x: Int, y: Int): LuaFire {
            val grid = HsShipLuaState.of(s).fires
            return grid.getOrPut(x * 1000 + y) { LuaFire(s, x, y) }
        }
        fun valid(x: Int, y: Int) = x in 0 until GRID && y in 0 until GRID
        LuaReg.method(A.M_ShipManager_GetFire, LuaMethod {
            val x = arg.int(2); val y = arg.int(3)
            if (!valid(x, y)) throw LuaError("Invalid fire coordinates: ($x, $y)")
            ret.obj(fire(self(), x, y), A.T_Fire)
        })
        fun atPoint(x: Float, y: Float, label: String): Int {
            // ShipGraph::TranslateToGrid(int, int): the coordinates truncated, then divided by 35
            val gx = x.toInt() / 35; val gy = y.toInt() / 35
            if (!valid(gx, gy)) throw LuaError("Invalid fire coordinates: $label")
            return ret.obj(fire(self(), gx, gy), A.T_Fire)
        }
        // the three overloads (Point, Pointf, x and y), told apart by the argument
        val atAny = LuaMethod {
            when (val p = if (arg.isNil(3)) arg.obj(2) else null) {
                is PointValue -> atPoint(p.x.toFloat(), p.y.toFloat(), "Point(${p.x}, ${p.y})")
                is PointfValue -> atPoint(p.x, p.y, "Pointf(${fmtG(p.x)}, ${fmtG(p.y)})")
                else -> atPoint(arg.float(2), arg.float(3), "(${fmtG(arg.float(2))}, ${fmtG(arg.float(3))})")
            }
        }
        for (m in intArrayOf(A.M_ShipManager_GetFireAtPoint__SWIG_0, A.M_ShipManager_GetFireAtPoint__SWIG_1, A.M_ShipManager_GetFireAtPoint__SWIG_2))
            LuaReg.method(m, atAny)
        LuaReg.obj(A.A_Spreader_Fire_grid, ObjGet { o ->
            val s = (o as LuaFireSpreader).ship
            LuaViews.cached(s, A.A_Spreader_Fire_grid) { x ->
                val ship = x as Ship
                ListView(A.T_vector_vector_Fire, A.T_vector_Fire) { (0 until GRID).map { gx -> LuaViews.cached(fire(ship, gx, 0), A.T_vector_Fire) { ListView(A.T_vector_Fire, A.T_Fire) { (0 until GRID).map { gy -> fire(ship, gx, gy) } } } } }
            }
        })
        fun f(o: Any?) = o as LuaFire
        repairable(LuaFire::class.java,
            damage = { o -> val c = f(o); val r = c.room; val fi = if (r == null) null else r.fires[c.slot]; (fi?.health ?: 0f) * 100f },
            setDamage = { o, v ->
                val c = f(o); val r = c.room
                if (r != null) { if (v <= 0f) r.fires[c.slot] = null else { if (r.fires[c.slot] == null) r.spawnFireAt(c.slot); r.fires[c.slot]?.health = v / 100f } }
            },
            roomId = { o -> f(o).room?.id ?: -1 },
            loc = { o -> val c = f(o); if (c.room == null) PointValue(-1, -1) else PointValue(c.gx * 35, c.gy * 35) },
            name = { "Fire" }, ship = { o -> f(o).ship })
        LuaReg.num(A.A_Fire_fDeathTimer, NumGet { f(it).deathTimer.toDouble() }, NumSet { o, v -> f(o).deathTimer = v.toFloat() })
        LuaReg.num(A.A_Fire_fStartTimer, NumGet { f(it).startTimer.toDouble() }, NumSet { o, v -> f(o).startTimer = v.toFloat() })
        LuaReg.num(A.A_Fire_fOxygen, NumGet { f(it).oxygen.toDouble() }, NumSet { o, v -> f(o).oxygen = v.toFloat() })
        LuaReg.bool(A.A_Fire_bWasOnFire, { f(it).wasOnFire }, { o, v -> f(o).wasOnFire = v })
        // fireAnimation: Fire::OnInit starts it looping for every cell of a room (oracle wp5-ship: running on burning and
        // unburnt room cells, not on a cell outside the rooms)
        LuaReg.obj(A.A_Fire_fireAnimation, ObjGet { o ->
            val a = LuaViews.cached(o!!, A.A_Fire_fireAnimation) { x ->
                LuaStructs.create(A.T_Animation).also { s ->
                    LuaAdaptersWritesPictures.setPos(s, -1.0, -1.0)
                    // The room's fire draws from a strip a script set (Animation::OnRender reads it).
                    s.onWrite = {
                        val c = x as LuaFire; val r = c.room
                        if (r != null && c.slot >= 0) {
                            val strips = r.hsFireStrips ?: arrayOfNulls<LuaTexture>(r.fires.size).also { r.hsFireStrips = it }
                            strips[c.slot] = s.values[A.A_Animation_animationStrip] as? LuaTexture
                        }
                    }
                }
            }
            (LuaStructs.member(a, A.A_Animation_tracker) as LuaStruct).values[A.A_AnimationTracker_running] = f(o).room != null
            a
        })
        LuaReg.obj(A.A_Fire_smokeAnimation, ObjGet { o -> LuaViews.cached(o!!, A.A_Fire_smokeAnimation) { LuaStructs.create(A.T_Animation).also { s -> LuaAdaptersWritesPictures.setPos(s, -1.0, -1.0) } } })
        val ps = LuaReg.strGet[A.A_Spreadable_soundName]
        LuaReg.str(A.A_Spreadable_soundName, StrGet { o -> if (o is LuaFire) "" else ps?.get(o) ?: "" }, StrSet { _, _ -> })
        val pss = LuaReg.numGet[A.A_Selectable_selectedState]; val psset = LuaReg.numSet[A.A_Selectable_selectedState]
        LuaReg.num(A.A_Selectable_selectedState, NumGet { o -> if (o is LuaFire) o.selectedState.toDouble() else pss?.get(o) ?: 0.0 },
            NumSet { o, v -> if (o is LuaFire) o.selectedState = v.toInt() else psset?.set(o, v) })
        // Fire::UpdateStartTimer(doorLevel), UpdateDeathTimer(connectedFires), OnLoop: the engine's fire timers
        // Fire::UpdateStartTimer(doorLevel) (1.6.9 0x48ea60): a cell not burning draws fStartTimer = random32() % 40 + 10
        // when it is 0, then counts down by SpeedFactor * 0.0571429 / 0.08 / 0.01 / 0.1 (door level 1 / 2 / 3 / other);
        // below 0 the timer resets and the cell ignites (fDamage 100)
        LuaReg.method(A.M_Fire_UpdateStartTimer, LuaMethod {
            val c = f(arg.obj(1)); val level = arg.int(2)
            val r = c.room; val burning = r != null && (r.fires[c.slot]?.health ?: 0f) * 100f > 0f
            if (burning) return@LuaMethod 0
            if (c.startTimer == 0f) c.startTimer = (xyz.znix.xftl.hs.weapons.HsWeapons.r32() % 40 + 10).toFloat()
            val rate = when (level) { 1 -> Float.fromBits(0x3d6a0ea1); 2 -> 0.08f; 3 -> 0.01f; else -> 0.1f }
            val t = c.startTimer - LuaFps.speedFactor * rate
            if (t < 0f) {
                c.startTimer = 0f
                if (r != null) { if (r.fires[c.slot] == null) r.spawnFireAt(c.slot); r.fires[c.slot]?.health = 1f }
            } else c.startTimer = t
            0
        })
        LuaReg.method(A.M_Fire_UpdateDeathTimer, LuaMethod { 0 })
        LuaReg.method(A.M_Fire_OnLoop, LuaMethod { 0 })
    }

    /** LockdownShard members (1.6.14 layout: position 0xcc, goal 0xd4, speed 0xdc, bArrived 0xe0, bDone 0xe1, lifeTime 0xe4, superFreeze 0xe8, lockingRoom 0xec). */
    private fun lockdownShards() {
        fun sh(o: Any?) = (o as LuaLockdownShard).shard
        LuaReg.obj(A.A_LockdownShard_position, ObjGet { o -> LuaViews.cached(o!!, A.A_LockdownShard_position) { x ->
            val s = sh(x); if ((x as LuaLockdownShard).isCopy) PointfValue(s.x, s.y) else LuaPointfMember({ s.x }, { v -> s.x = v }, { s.y }, { v -> s.y = v }) } })
        LuaReg.obj(A.A_LockdownShard_goal, ObjGet { o -> LuaViews.cached(o!!, A.A_LockdownShard_goal) { x -> PointfValue(sh(x).goalX, sh(x).goalY) } })
        LuaReg.num(A.A_LockdownShard_speed, NumGet { sh(it).speed.toDouble() })
        LuaReg.bool(A.A_LockdownShard_bArrived, { sh(it).arrived }, { o, v -> sh(o).arrived = v })
        LuaReg.bool(A.A_LockdownShard_bDone, { sh(it).done }, { o, v -> sh(o).done = v })
        LuaReg.num(A.A_LockdownShard_lifeTime, NumGet { sh(it).lifeTime.toDouble() }, NumSet { o, v -> sh(o).lifeTime = v.toFloat() })
        LuaReg.bool(A.A_LockdownShard_superFreeze, { sh(it).superFreeze })
        LuaReg.num(A.A_LockdownShard_lockingRoom, NumGet { sh(it).lockingRoom.toDouble() })
        LuaReg.obj(A.A_LockdownShard_shard, ObjGet { o -> LuaViews.cached(o!!, A.A_LockdownShard_shard) { x ->
            val s = sh(x); HsLua.game?.gameContent?.animations?.getOrNull(s.animName)?.let { spec -> LuaAnim.create(spec) } ?: LuaOpaque(A.T_Animation, x) } })
        LuaReg.method(A.M_LockdownShard_Update, LuaMethod { sh(arg.obj(1)).update(1f / 60f); 0 })
    }

    private const val GRID = 40

    // ---------------------------------------------------------------- ShipObject augments, ShipInfo
    private fun shipObject() {
        LuaReg.method(A.M_ShipObject_GetAugmentationCount, LuaMethod { ret.num(self().augments.size) })
        LuaReg.method(A.M_ShipObject_GetAugmentationList, LuaMethod {
            ret.obj(ListView(A.T_vector_string, 0, ArrayList<Any?>(self().augments.map { it.name }.sorted())), A.T_vector_string) // std::map order
        })
        val add = LuaMethod {
            val s = self(); val name = arg.str(2) ?: ""
            addAugmentation(s, name)
            0
        }
        LuaReg.method(A.M_ShipObject_AddAugmentation__SWIG_0, add)
        LuaReg.method(A.M_ShipObject_AddAugmentation__SWIG_1, add)
        LuaReg.method(A.M_ShipObject_RemoveAugmentation, LuaMethod { removeAugmentation(self(), arg.str(2) ?: ""); 0 })
        LuaReg.method(A.M_ShipObject_ClearShipInfo, LuaMethod {
            val s = self()
            for (a in ArrayList(s.augments)) s.removeBlueprint(a)
            s.hs?.hiddenAugs?.clear(); s.cargoUpdated()
            0
        })
        // Global::GetShipInfo(enemy): the ShipInfo (augment list) of ship 0 / 1
        LuaReg.method(A.M_Global_GetShipInfo, LuaMethod {
            // G_->shipInfoList[id] always exists (an empty augList while there is no such ship)
            ret.obj(LuaShipInfo.of(if (arg.bool(2)) 1 else 0), A.T_ShipInfo)
        })
        LuaReg.obj(A.A_ShipInfo_augList, ObjGet { o -> LuaViews.cached(o!!, A.A_ShipInfo_augList) { LuaShipAugList(it as LuaShipInfo) } })
        // std::map<std::string,int> for ShipInfo::augList (keys sorted); other maps keep their adapters
        fun mapMethod(mid: Int, f: (java.util.TreeMap<String, Int>) -> Int) {
            val prev = LuaReg.methods[mid]
            LuaReg.method(mid, LuaMethod { n ->
                val o = arg.obj(1)
                if (o is LuaShipAugList) f(o.map()) else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.methodName(mid))).call(n)
            })
        }
        mapMethod(A.M_map_string_int_size) { ret.num(it.size) }
        mapMethod(A.M_map_string_int_empty) { ret.bool(it.isEmpty()) }
        mapMethod(A.M_map_string_int_has_key) { ret.bool(it.containsKey(arg.str(2) ?: "")) }
        mapMethod(A.M_map_string_int_keys) { ret.obj(ListView(A.T_vector_string, 0, ArrayList<Any?>(it.keys)), A.T_vector_string) }
        mapMethod(A.M_map_string_int_get) { m -> m[arg.str(2) ?: ""]?.let { ret.num(it) } ?: throw LuaError("SWIG_IndexError:key not found") }
        mapMethod(A.M_map_string_int___getitem) { m -> m[arg.str(2) ?: ""]?.let { ret.num(it) } ?: throw LuaError("SWIG_IndexError:key not found") }
    }

    // ---------------------------------------------------------------- blueprints, augment definitions
    private fun blueprints() {
        fun bp(o: Any?) = o as xyz.znix.xftl.Blueprint
        // CustomAugmentManager:GetAugmentDefinition (lua/modules/hyperspace.i %extend, 1b05a07): an unknown augment throws
        // "No definition found for augment: <name>" instead of returning nil
        LuaReg.method(A.M_CustomAugmentManager_GetAugmentDefinition, LuaMethod {
            val name = arg.str(2) ?: ""
            val d = xyz.znix.xftl.hs.ship.HsAugments.definition(name) ?: throw LuaError("No definition found for augment: $name")
            ret.obj(d, A.T_AugmentDefinition)
        })
        LuaReg.method(A.M_Blueprint_GetNameLong,LuaMethod { ret.str(nameLong(bp(arg.obj(1)))) })
        LuaReg.method(A.M_Blueprint_GetNameShort, LuaMethod { ret.str(nameShort(bp(arg.obj(1)))) })
        LuaReg.method(A.M_Blueprint_GetType, LuaMethod { ret.num(blueprintType(bp(arg.obj(1)))) })
        // a raw CrewBlueprint (Blueprints:GetCrewBlueprint) answers as a Blueprint: GetType 2, GetNameShort the race's
        val crewTypeBefore = LuaReg.methods[A.M_CrewBlueprint_GetType]
        LuaReg.method(A.M_CrewBlueprint_GetType, LuaMethod { n ->
            if (arg.obj(1) is xyz.znix.xftl.crew.CrewBlueprint) ret.num(2) else (crewTypeBefore ?: throw LuaError("CrewBlueprint.GetType")).call(n)
        })
        val crewShortBefore = LuaReg.methods[A.M_CrewBlueprint_GetNameShort]
        LuaReg.method(A.M_CrewBlueprint_GetNameShort, LuaMethod { n ->
            val o = arg.obj(1)
            if (o is xyz.znix.xftl.crew.CrewBlueprint) ret.str(nameShort(o)) else (crewShortBefore ?: throw LuaError("CrewBlueprint.GetNameShort")).call(n)
        })
        // Blueprint::type: a weapon's kind (laser 0, missile 1, beam 2, bomb 3, burst 4); -1 for augments, crew,
        // ships (the Blueprint constructor's value); drones keep their adapter
        val typeBefore = LuaReg.numGet[A.A_Blueprint_type]; val typeSetBefore = LuaReg.numSet[A.A_Blueprint_type]
        LuaReg.num(A.A_Blueprint_type, NumGet { o ->
            when (o) {
                is xyz.znix.xftl.weapons.LaserBlueprint -> 0.0
                is xyz.znix.xftl.weapons.MissileBlueprint -> 1.0
                is xyz.znix.xftl.weapons.BeamBlueprint -> 2.0
                is xyz.znix.xftl.weapons.BombBlueprint -> 3.0
                is xyz.znix.xftl.weapons.FlakBlueprint -> 4.0
                is xyz.znix.xftl.augments.AugmentBlueprint, is xyz.znix.xftl.crew.CrewBlueprint, is xyz.znix.xftl.game.ShipBlueprint -> -1.0
                else -> typeBefore?.get(o) ?: 0.0
            }
        }, NumSet { o, v -> typeSetBefore?.set(o, v) })
        // TemporalSystemParser::GetDilationStrength(level): HS's dilation table
        LuaReg.method(A.M_TemporalSystemParser_GetDilationStrength, LuaMethod { ret.num(dilationStrength(arg.int(1)).toDouble()) })
        // AugmentDefinition.functions / superShield / crystalShard
        fun ad(o: Any?) = o as xyz.znix.xftl.hs.ship.HsAugments.AugmentDefinition
        LuaReg.obj(A.A_AugmentDefinition_functions, ObjGet { o -> LuaViews.cached(o!!, A.A_AugmentDefinition_functions) { LuaOpaque(A.T_unordered_multimap_string_AugmentFunction, it) } })
        if (LuaReg.objGet[A.A_AugmentDefinition_superShield] == null) LuaReg.obj(A.A_AugmentDefinition_superShield, ObjGet { o -> LuaViews.cached(o!!, A.A_AugmentDefinition_superShield) { LuaOpaque(A.T_AugmentSuperShield, it) } })
        LuaReg.obj(A.A_AugmentDefinition_crystalShard, ObjGet { o ->
            LuaViews.cached(o!!, A.A_AugmentDefinition_crystalShard) { x ->
                ListView(A.T_vector_AugmentCrystalShard, A.T_AugmentCrystalShard) { ad(x).crystalShards.map { e -> LuaViews.cached(e, A.T_AugmentCrystalShard) { LuaCrystalShard(it as org.jdom2.Element) } } }
            }
        })
        fun cs(o: Any?) = (o as LuaCrystalShard).e
        // CustomAugments.cpp ParseCustomAugmentNode: weapon, chance from `value` then `chance`, stacking from `stackable`
        // ("independent" 2, else ParseBoolean 1 / 0)
        LuaReg.str(A.A_AugmentCrystalShard_weapon, StrGet { cs(it).getAttributeValue("weapon") ?: "" })
        LuaReg.num(A.A_AugmentCrystalShard_chance, NumGet { e ->
            val x = cs(e); ((x.getAttributeValue("chance") ?: x.getAttributeValue("value"))?.toFloatOrNull() ?: 0f).toDouble()
        })
        LuaReg.num(A.A_AugmentCrystalShard_stacking, NumGet { e ->
            when (val m = cs(e).getAttributeValue("stackable")) { null -> 0.0; "independent" -> 2.0; else -> if (HsConfig.bool(m)) 1.0 else 0.0 }
        })
        fun af(o: Any?) = o as xyz.znix.xftl.hs.ship.HsAugments.AugmentFunction
        LuaReg.num(A.A_AugmentFunction_value, NumGet { af(it).value.toDouble() })
        LuaReg.bool(A.A_AugmentFunction_preferHigher, { af(it).preferHigher })
        LuaReg.bool(A.A_AugmentFunction_useForReqs, { af(it).useForReqs })
        LuaReg.bool(A.A_AugmentFunction_warning, { af(it).warning })
        LuaReg.num(A.A_AugmentFunction_sys, NumGet { (af(it).sys?.let { s -> xyz.znix.xftl.hs.systems.HsSystems.nameToId(s) } ?: -1).toDouble() })
        LuaReg.bool(A.A_AugmentFunction_modifyChoiceTextScrap, { af(it).modifyChoiceTextScrap })
        LuaReg.method(A.M_AugmentFunction_Functional, LuaMethod {
            val f = af(arg.obj(1)); val s = LuaAdapters.shipManager(arg.int(2)) ?: return@LuaMethod ret.bool(false)
            ret.bool(xyz.znix.xftl.hs.ship.HsAugments.functional(s, f))
        })
        fun aug(o: Any?) = o as xyz.znix.xftl.augments.AugmentBlueprint
        LuaReg.num(A.A_AugmentBlueprint_value, NumGet { aug(it).nativeValue.toDouble() }, NumSet { _, _ -> })
        LuaReg.bool(A.A_AugmentBlueprint_stacking, { aug(it).stackable }, { _, _ -> })
    }

    /** Blueprint::GetNameLong: the title's text; ship blueprints have none. */
    private fun nameLong(b: xyz.znix.xftl.Blueprint): String {
        if (b is xyz.znix.xftl.game.ShipBlueprint) return ""
        val t = HsLua.game?.gameContent?.translator ?: return b.name
        return b.title?.let { t[it] } ?: ""
    }

    private fun nameShort(b: xyz.znix.xftl.Blueprint): String {
        if (b is xyz.znix.xftl.game.ShipBlueprint) return ""
        val t = HsLua.game?.gameContent?.translator ?: return b.name
        // Blueprint::GetNameShort: the short name, the long one when there is none
        return b.short?.let { t[it] } ?: b.title?.let { t[it] } ?: ""
    }

    /** Blueprint::GetType: 0 weapon, 1 drone, 2 crew, 3 augment, 4 system, 5 ship. */
    private fun blueprintType(b: xyz.znix.xftl.Blueprint): Int = when (b) {
        is xyz.znix.xftl.weapons.AbstractWeaponBlueprint -> 0
        is xyz.znix.xftl.weapons.DroneBlueprint -> 1
        is xyz.znix.xftl.crew.CrewBlueprint -> 2
        is xyz.znix.xftl.augments.AugmentBlueprint -> 3
        is xyz.znix.xftl.systems.SystemBlueprint -> 4
        is xyz.znix.xftl.game.ShipBlueprint -> 5
        else -> -1
    }

    // ---------------------------------------------------------------- hangar ShipButton
    private fun hangar() {
        LuaReg.method(A.M_ShipButtonList_GetButton, LuaMethod {
            val l = arg.obj(1) as LuaShipButtonList; val v = arg.int(2)
            if (v !in 0..2) return@LuaMethod ret.nil()
            ret.obj(LuaViews.cached(l, 1000 + v) { LuaShipButton(l.index, v) }, A.T_ShipButton)
        })
        fun b(o: Any?) = o as LuaShipButton
        // iShipImage: the ship's hangar picture (GL_Texture, loaded by ShipButton::OnInit; drawn by the engine)
        LuaReg.obj(A.A_ShipButton_iShipImage, ObjGet { o -> opaque(o!!, A.A_ShipButton_iShipImage, A.T_GL_Texture) }, ObjSet { _, _ -> })
        LuaReg.bool(A.A_ShipButton_bShipLocked, { b(it).shipLocked() }, { o, v -> b(o).overrides[A.A_ShipButton_bShipLocked] = v })
        LuaReg.bool(A.A_ShipButton_bLayoutLocked, { b(it).layoutLocked() }, { o, v -> b(o).overrides[A.A_ShipButton_bLayoutLocked] = v })
        LuaReg.bool(A.A_ShipButton_bNoExist, { b(it).noExist() }, { o, v -> b(o).overrides[A.A_ShipButton_bNoExist] = v })
        LuaReg.num(A.A_ShipButton_iSelectedAch, NumGet { b(it).selectedAch.toDouble() }, NumSet { o, v -> b(o).selectedAch = v.toInt() })
        LuaReg.bool(A.A_ShipButton_bSelected, { b(it).selected }, { o, v -> b(o).selected = v })
        LuaReg.method(A.M_ShipButton_MouseMove, LuaMethod { b(arg.obj(1)).mouseMove(arg.int(2), arg.int(3)); 0 })
        LuaReg.method(A.M_ShipButton_OnInit, LuaMethod { 0 })
        LuaReg.method(A.M_ShipButton_OnRender, LuaMethod { 0 })
        // GenericButton members of a ShipButton: CustomShipSelect::OnInit's position / hitbox, bActive = it exists,
        // bHover from MouseMove; other buttons keep their adapters
        fun wrapNum(a: Int, get: (LuaShipButton) -> Double, set: ((LuaShipButton, Double) -> Unit)?) {
            val pg = LuaReg.numGet[a]; val ps = LuaReg.numSet[a]
            LuaReg.num(a, NumGet { o -> if (o is LuaShipButton) get(o) else (pg ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o) },
                NumSet { o, v -> if (o is LuaShipButton) set?.invoke(o, v) else (ps ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).set(o, v) })
        }
        fun wrapObj(a: Int, get: (LuaShipButton) -> Any?) {
            val pg = LuaReg.objGet[a]; val ps = LuaReg.objSet[a]
            LuaReg.obj(a, ObjGet { o -> if (o is LuaShipButton) get(o) else (pg ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o) }, ps)
        }
        wrapNum(A.A_GenericButton_bActive, { b -> if (b.overrides[A.A_GenericButton_bActive] ?: !b.noExist()) 1.0 else 0.0 }, { b, v -> b.overrides[A.A_GenericButton_bActive] = v != 0.0 })
        wrapNum(A.A_GenericButton_bHover, { b -> if (b.hover) 1.0 else 0.0 }, { b, v -> b.hover = v != 0.0 })
        wrapObj(A.A_GenericButton_position) { b -> val r = b.hitbox(); PointValue(r[0], r[1]) }
        wrapObj(A.A_GenericButton_hitbox) { b ->
            val r = b.hitbox(); LuaStructs.create(A.T_Rect).also { LuaStructs.setFields(it, r[0].toDouble(), r[1].toDouble(), r[2].toDouble(), r[3].toDouble()) }
        }
    }
}

/** ShipManager_Extend as Lua sees it (no members): one per ship. */
class LuaShipManagerExtend(@JvmField val ship: Ship) : LuaTyped { override val luaType get() = A.T_ShipManager_Extend }

/** Global::GetShipInfo(enemy): ShipInfo [id] (augList) of the ship that currently has that id. */
class LuaShipInfo private constructor(@JvmField val id: Int) : LuaTyped {
    override val luaType get() = A.T_ShipInfo
    val ship: Ship? get() = LuaAdapters.shipManager(id)

    companion object {
        private val infos = arrayOf(LuaShipInfo(0), LuaShipInfo(1))
        @JvmStatic fun of(id: Int): LuaShipInfo = infos[id]
    }
}

/**
 * ShipInfo::augList (std::map<std::string,int>): the augments by count, the hidden ones as "HIDDEN name"; an augment
 * removed to zero keeps its entry (RemoveAugmentation decrements, oracle wp5-ship).
 */
class LuaShipAugList(@JvmField val info: LuaShipInfo) : LuaTyped {
    override val luaType get() = A.T_map_string_int
    fun map(): java.util.TreeMap<String, Int> {
        val m = java.util.TreeMap<String, Int>()
        val ship = info.ship ?: return m
        for (z in HsShipLuaState.of(ship).zeroAugs) m[z] = 0
        for (a in ship.augments) m[a.name] = (m[a.name] ?: 0) + 1
        ship.hs?.hiddenAugs?.forEach { (n, c) -> m["HIDDEN $n"] = c }
        return m
    }
}

/** One AugmentDefinition::crystalShard entry (an AugmentCrystalShard): its `<crystalShard>` node. */
class LuaCrystalShard(@JvmField val e: org.jdom2.Element) : LuaTyped { override val luaType get() = A.T_AugmentCrystalShard }

/**
 * A hangar ShipButton: variant [variant] (0 A, 1 B, 2 C) of ShipButtonList [list] (index into the custom ship buttons).
 * Locked/exists flags come from the unlocks; position and hitbox from CustomShipSelect::OnInit's grid.
 */
class LuaShipButton(@JvmField val list: Int, @JvmField val variant: Int) : LuaTyped {
    override val luaType get() = A.T_ShipButton
    @JvmField val overrides = HashMap<Int, Boolean>()
    @JvmField var selectedAch = -1
    @JvmField var selected = false
    @JvmField var hover = false

    private val def get() = xyz.znix.xftl.hs.ship.HsShips.buttons[list]
    private fun layoutName(v: Int) = def.name + when (v) { 1 -> "_2"; 2 -> "_3"; else -> "" }
    fun shipLocked(): Boolean = overrides[A.A_ShipButton_bShipLocked] ?: !xyz.znix.xftl.hs.ship.HsHangar.isCustomLayoutUnlocked(layoutName(0))
    fun noExist(): Boolean = overrides[A.A_ShipButton_bNoExist] ?: !def.variantExists(variant)
    fun layoutLocked(): Boolean = overrides[A.A_ShipButton_bLayoutLocked] ?: (!noExist() && !xyz.znix.xftl.hs.ship.HsHangar.isCustomLayoutUnlocked(layoutName(variant)))

    /** CustomShipSelect::OnInit: top row left to right, bottom row right to left, then the right column. */
    fun hitbox(): IntArray {
        val order = xyz.znix.xftl.hs.ship.HsHangar.order()
        val onPage = maxOf(0, order.indexOf(def.name)) % 10
        val (x, y) = when {
            onPage < 4 -> Pair(136 + 205 * onPage, 161)
            onPage < 8 -> Pair(136 + 205 * 3 - 205 * (onPage - 4), 161 + 177)
            onPage == 8 -> Pair(136 + 205 * 3 + 225, 161)
            else -> Pair(136 + 205 * 3 + 225, 161 + 177)
        }
        val locked = shipLocked()
        return intArrayOf(x, y + if (locked || noExist()) 20 else 0, 191, if (locked) 121 else 161)
    }

    fun mouseMove(x: Int, y: Int) {
        val r = hitbox()
        hover = !noExist() && x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]
    }
}

/** Per-ship values Lua reads and writes that the engine does not model (kept with the ship, not saved). */
class HsShipLuaState {
    @JvmField val flags = HashMap<Int, Boolean>()
    @JvmField val ints = HashMap<Int, Int>()
    @JvmField val tempVision = HashSet<Int>()
    @JvmField val blackout = HashMap<Int, Boolean>()
    @JvmField var selectedRoom = -1
    @JvmField var lastEngineStatus: Boolean? = null
    @JvmField var shipLevel = 0
    @JvmField val fires = HashMap<Int, LuaFire>()
    @JvmField val doors = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Door, DoorState>())
    @JvmField val zeroAugs = java.util.TreeSet<String>()
    @JvmField val graph = GraphState()

    class DoorState {
        @JvmField var blast = 0
        @JvmField var baseHealth: Int? = null
        @JvmField var level: Int? = null
        @JvmField var ioned = false
        @JvmField var hacked = 0
        @JvmField var lastbase: Int? = null
    }

    /** ShipGraph::worldPosition/worldHeading and their last values (FTL 1.6 ships stay at (-1, -1), heading 0). */
    class GraphState {
        @JvmField val position = PointfValue(-1f, -1f)
        @JvmField val lastPosition = PointfValue(-1f, -1f)
        @JvmField var heading = 0f
        @JvmField var lastHeading = 0f
    }

    /** SetSystemPowerLoss values not applied yet (see LuaAdaptersWP5Ship.setPowerLoss): set at the ship's next loop. */
    @JvmField val pendingLoss = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<xyz.znix.xftl.AbstractSystem, Int>())

    /** ShipManager::vSystemList order: see [systemList]. */
    @JvmField val systemOrder = ArrayList<xyz.znix.xftl.AbstractSystem>()

    /** Systems a second AddSystem of their type displaced from the room (still in vSystemList, still drawing power). */
    @JvmField val ghosts = ArrayList<xyz.znix.xftl.AbstractSystem>()

    companion object {
        /**
         * ShipManager::vSystemList: OnInit adds the starting systems in the order of the blueprint's `<systemList>`
         * (ShipBlueprint::systems); a system installed later (AddSystem, a store) goes to the end.
         */
        @JvmStatic
        fun systemList(ship: Ship): List<xyz.znix.xftl.AbstractSystem> {
            val st = of(ship)
            val order = st.systemOrder
            // a system a second AddSystem displaced stays in the list while its type is installed
            st.ghosts.retainAll { g -> ship.systems.any { it.blueprint === g.blueprint } }
            val keep = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<xyz.znix.xftl.AbstractSystem, Boolean>())
            keep += ship.systems; keep += st.ghosts
            order.retainAll { it in keep }
            val fresh = ship.systems.filter { s -> order.none { it === s } }
            if (fresh.isNotEmpty()) {
                val names = ship.type.systems.map { it.systemName }
                order += fresh.sortedBy { s -> names.indexOf(s.blueprint.name).let { if (it < 0) Int.MAX_VALUE else it } }
            }
            return order
        }

        /** AddSystem's vSystemList.push_back([s]) (the order the list had so far is settled first). */
        @JvmStatic
        fun noteAdded(ship: Ship, s: xyz.znix.xftl.AbstractSystem) {
            val order = systemList(ship) as MutableList<xyz.znix.xftl.AbstractSystem>
            if (order.none { it === s }) order += s
        }

        private val fallback = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, HsShipLuaState>())
        private val emptyGraph = GraphState()

        @JvmStatic
        fun of(ship: Ship): HsShipLuaState {
            val hs = ship.hs ?: return fallback.getOrPut(ship) { HsShipLuaState() }
            return hs.lua ?: HsShipLuaState().also { hs.lua = it }
        }

        @JvmStatic
        fun door(d: Door): DoorState = of(d.ship).doors.getOrPut(d) { DoorState() }

        @JvmStatic
        fun graph(g: LuaShipGraph): GraphState = g.ship?.let { of(it).graph } ?: emptyGraph
    }
}

/** TemporalSystemParser::GetDilationStrength (HS's `<timeDilation>` table, default 2^level). */
private fun dilationStrength(level: Int): Float = xyz.znix.xftl.hs.systems.HsSystems.dilationStrength(level)

/**
 * ShipObject::AddAugmentation(name): `augList[name]++`; a "HIDDEN x" name is a hidden augment, anything else a cargo
 * augment (no slot limit, as natively: Lua may add a fourth).
 */
private fun addAugmentation(s: Ship, name: String) {
    if (name.startsWith("HIDDEN ")) { s.hs?.addHidden(name.removePrefix("HIDDEN ")); return }
    val game = LuaAdapters.inGame() ?: return
    val bp = game.blueprintManager.getOrNull(name) as? xyz.znix.xftl.augments.AugmentBlueprint ?: return
    HsShipLuaState.of(s).zeroAugs.remove(name)
    s.augments.add(bp)
    s.cargoUpdated()
}

/** ShipObject::RemoveAugmentation(name): `augList[name]--` (the entry stays, at 0, as std::map's does). */
private fun removeAugmentation(s: Ship, name: String) {
    if (name.startsWith("HIDDEN ")) { s.hs?.removeHidden(name.removePrefix("HIDDEN ")); return }
    val a = s.augments.firstOrNull { it.name == name } ?: return
    s.augments.remove(a)
    if (s.augments.none { it.name == name }) HsShipLuaState.of(s).zeroAugs.add(name)
    s.cargoUpdated()
}

/**
 * The WP5 ship Lua events: CONSTRUCT_SHIP_MANAGER (ShipManager_Extend.cpp, the ShipManager constructor at priority 900,
 * after the vanilla one: the ship has no blueprint loaded yet), CONSTRUCT_ROOM (Room_Extend.cpp, Room::constructor
 * priority 900) and GET_LEVEL_DESCRIPTION (InternalEvents.cpp, ShipSystem::GetLevelDescription: a callback returning a
 * string replaces the level's description).
 */
// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipManager_Extend.cpp, Room_Extend.cpp,
// lua/InternalEvents.cpp (GetLevelDescription hook).
object LuaShipEvents {
    @JvmStatic
    fun constructShipManager(ship: Ship) {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_CONSTRUCT_SHIP_MANAGER]) return
        ret.obj(ship, A.T_ShipManager)
        LuaJs.fire(A.EV_CONSTRUCT_SHIP_MANAGER, 1, 0)
        LuaJs.pop(1)
    }

    @JvmStatic
    fun constructRoom(room: Room) {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_CONSTRUCT_ROOM]) return
        ret.obj(room, A.T_Room)
        LuaJs.fire(A.EV_CONSTRUCT_ROOM, 1, 0)
        LuaJs.pop(1)
    }

    /** GetLevelDescription(systemId, level, tooltip): the callbacks get (systemId, level + 1, tooltip). */
    @JvmStatic
    fun levelDescription(systemId: Int, level: Int, tooltip: Boolean, original: () -> String): String {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_LEVEL_DESCRIPTION]) return original()
        // lua_pushinteger (InternalEvents.cpp): integers in Lua 5.3 (`id .. ''` is "0", not "0.0"; oracle wp5-ship2)
        LuaJs.pushinteger(systemId.toDouble()); LuaJs.pushinteger((level + 1).toDouble()); ret.bool(tooltip)
        val n = LuaJs.fire(A.EV_GET_LEVEL_DESCRIPTION, 3, 1)
        if (n == 1 && LuaJs.isstring(-1)) {
            val s = LuaJs.tostring(-1) ?: ""
            LuaJs.pop(4)
            return s
        }
        LuaJs.pop(if (n == 1) 4 else 3)
        return original()
    }

    /** GetLevelDescription for a system blueprint (its HS system id by name). */
    @JvmStatic
    fun levelName(system: xyz.znix.xftl.systems.SystemBlueprint, level: Int, tooltip: Boolean, translator: xyz.znix.xftl.Translator): String =
        levelDescription(xyz.znix.xftl.hs.systems.HsSystems.nameToId(system.name), level, tooltip) { system.info!!.getLevelName(level, translator) }

}

/** HS's dummy OxygenSystem of a ship without one (OxygenWithoutSystem.cpp): what Lua's `oxygenSystem` then is. */
class LuaDummyOxygen(@JvmField val ship: Ship)
