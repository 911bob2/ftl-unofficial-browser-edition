// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07: lua/modules/hyperspace.i (ShipSystem, Repairable,
// PowerManager, Shields, Shields::Shield, OxygenSystem, CloakingSystem, BatterySystem, ArtillerySystem,
// AugmentSuperShield, TimerHelper members), src/game/Ship/ShipSystems/CustomSuperShield.cpp (Shields::AddSuperShield /
// InstantCharge hooks), src/game/Ship/ShipSystems/OxygenSystem.cpp (GetRefillSpeed), src/game/Ship/CustomCrew.cpp
// (OxygenSystem::EmptyOxygen hook), src/game/Ship/ShipSystems/TemporalSystem.cpp (ModifyRoomOxygen dilation) and the
// FTL 1.6.9 functions those members belong to (named at each adapter). Not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.systems.HsOxygen
import xyz.znix.xftl.hs.systems.HsPower
import xyz.znix.xftl.hs.systems.HsSuperShield
import xyz.znix.xftl.hs.systems.HsSysExt
import xyz.znix.xftl.hs.systems.HsSystemState
import xyz.znix.xftl.hs.systems.HsSystemsLua
import xyz.znix.xftl.hs.systems.HsTemporal
import xyz.znix.xftl.layout.BreachInstance
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.systems.*
import xyz.znix.xftl.replaceArg
import kotlin.math.max
import kotlin.math.min
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** A native `Point` member (pLoc, center, superUpLoc): reads and writes go to the owner. */
class LuaPointMember(private val gx: IGet, private val sx: ISet?, private val gy: IGet, private val sy: ISet?) : LuaTyped {
    override val luaType get() = A.T_Point
    var x: Int get() = gx.get(); set(v) { (sx ?: throw LuaError("This variable is immutable")).set(v) }
    var y: Int get() = gy.get(); set(v) { (sy ?: throw LuaError("This variable is immutable")).set(v) }
}

/** A native TimerHelper member the engine keeps in its own terms (the lock timer, the cloak timer). */
abstract class LuaTimerView : LuaTyped {
    override val luaType get() = A.T_TimerHelper
    abstract var maxTime: Int
    abstract var minTime: Int
    abstract var currTime: Float
    abstract var currGoal: Float
    abstract var loop: Boolean
    abstract var running: Boolean
}

/** A pointer of a type SWIG has no wrapper for (no members or methods). */

/** A `std::string` object (SWIG's std_string userdata): AugmentSuperShield::shieldTexture points at the first one. */
class LuaStdString(private val get: () -> String, private val set: ((String) -> Unit)?) : LuaTyped {
    override val luaType get() = A.T_string
    var value: String get() = get(); set(v) { (set ?: throw LuaError("This variable is immutable")).invoke(v) }
    /** std::string::assign: writes the native string the object points at. */
    fun assign(v: String?) { value = v ?: "" }
}

/** Per Shields object: the native Shields fields the engine has no state for (1.6.9 Shields::constructor values). */
class LuaShieldsState(@JvmField val sys: Shields) {
    @JvmField var iHighlightedSide = -1
    @JvmField var shieldsShutdown = false
    @JvmField var superShieldDown = false
    @JvmField var downX = 0f
    @JvmField var downY = 0f
    @JvmField var barrierMode = false
    @JvmField var lastHitTimer = 0f
    @JvmField var chargeTime = 2f
    @JvmField var lastHitShieldLevel = 0
    @JvmField var superUpX = Int.MIN_VALUE
    @JvmField var superUpY = Int.MIN_VALUE
    @JvmField var excessChargeHack = false
    @JvmField var superTimer = -1f
    @JvmField var enemyPresent: Boolean? = null
    @JvmField var shieldImageName: String? = null
    /** SetBaseEllipse / baseShield writes (the engine's collision ellipse stays the ship's). */
    @JvmField var ellipse: DoubleArray? = null
    @JvmField var ellipseRatio: Float? = null
    @JvmField var centerX: Int? = null
    @JvmField var centerY: Int? = null

    private fun tracker(time: Double, running: Boolean, current: Double) = LuaStructs.create(A.T_AnimationTracker).also {
        it.values[A.A_AnimationTracker_time] = time
        it.values[A.A_AnimationTracker_loop] = false
        it.values[A.A_AnimationTracker_current_time] = current
        it.values[A.A_AnimationTracker_running] = running
        it.values[A.A_AnimationTracker_reverse] = false
        it.values[A.A_AnimationTracker_done] = true
        it.values[A.A_AnimationTracker_loopDelay] = 0.0
        it.values[A.A_AnimationTracker_currentDelay] = -1.0
    }
    @JvmField val shieldsDown = tracker(0.5, false, 0.0)
    /** Started when the constructor finds charged layers (AnimationTracker::Start then SetProgress 1). */
    @JvmField val shieldsUp = tracker(0.15, true, 0.15)
    @JvmField val superShieldUp = tracker(0.5, false, 0.0)

    fun startSuperUp() {
        superShieldUp.values[A.A_AnimationTracker_running] = true
        superShieldUp.values[A.A_AnimationTracker_current_time] = 0.0
        superShieldUp.values[A.A_AnimationTracker_done] = false
    }
}

object LuaAdaptersWP5Systems {
    private fun sys(o: Any?): AbstractSystem = o as? AbstractSystem ?: throw LuaError("expected a ShipSystem")
    private fun st(o: Any?): HsSystemState = HsSystemState.of(sys(o))
    private fun shipOf(s: AbstractSystem): Ship? = s.room?.ship ?: s.hsState?.detachedShip
    private fun d(b: Boolean) = if (b) 1.0 else 0.0

    /** CFPS::GetSpeedFactor as the scripts see it (Hyperspace.FPS.SpeedFactor). */
    private fun speedFactor(): Float = LuaFps.speedFactor

    private val shieldStates = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Shields, LuaShieldsState>())
    @JvmStatic
    fun shields(s: Shields): LuaShieldsState = shieldStates.getOrPut(s) { LuaShieldsState(s) }
    fun shieldsOrNull(s: Shields): LuaShieldsState? = shieldStates[s]

    // ------------------------------------------------------------------ native field rules

    /** healthState.first / second. */
    private fun health(s: AbstractSystem) = s.energyLevels - s.damagedEnergyLevels

    /** powerState.first (reactor bars), as HsSystemsLua reads it. */
    private fun power(s: AbstractSystem) = HsSystemsLua.reactorPower(s)

    /** ShipSystem::iLockCount (-1 for a held lock, else the lock units). */
    private fun lockCount(s: AbstractSystem) = HsSystemsLua.lockCount(s)

    /** ShipSystem::iHackEffect as HsSystemsLua answers it. */
    private fun hackEffect(s: AbstractSystem): Int =
        if (s.hsHackLevel > 0) s.hsHackLevel else if (s.hackedBy == null) 0 else if (s.isHackActive) 2 else 1

    /** ShipSystem::bUnderAttack: a hacking drone is attached (SaveState pairs it with iHackEffect), or a script's value. */
    @JvmStatic
    fun underAttack(s: AbstractSystem): Boolean = s.hsState?.underAttack ?: (s.hackedBy != null)

    /**
     * ShipSystem::GetMaxPower (iTempPowerCap clamps at powerState.second when above 7). HS's hook (CustomCrew.cpp) first
     * sets iTempPowerLoss from the current additionalPowerLoss, so a SHIP_LOOP addition counts here at once.
     */
    private fun maxPower(s: AbstractSystem): Int {
        var m = s.energyLevels - s.damagedEnergyLevels - (s.hsExt?.liveLoss() ?: s.scriptedPowerLoss)
        s.scriptedPowerLimit?.let { m = min(m, it) }
        return max(0, m)
    }

    private fun bonus(s: AbstractSystem) = (s as? MainSystem)?.forcedPower ?: 0
    private fun battery(s: AbstractSystem) = (s as? MainSystem)?.let { it.hsLuaBatteryPower ?: it.sharedPowerInUse } ?: 0

    private fun crewIn(s: AbstractSystem, mode: AbstractCrew.SlotType): Boolean =
        s.room?.crew?.any { it.mode == mode } == true

    /**
     * ShipSystem::bManned: a crew member of the ship stands at the station (CrewMember::OnLoop sets it whether or not
     * the system has power; iActiveManned, the skill boost, needs power). The engine's MANNING action needs power.
     */
    private fun manned(s: AbstractSystem): Boolean {
        if (s.manningCrew != null) return true
        // a Lua-defined system: any crew member of the ship standing in its room, at a console or not, bBoostable or not
        // (oracle sweep-world-y1 y-man: sw_slotless manned by crew at slot 0 of a room without a console)
        if (s.info is xyz.znix.xftl.hs.systems.HsCustomSystemInfo)
            return s.room?.crew?.any { it.mode == AbstractCrew.SlotType.CREW && it.canManSystem && it.pathingTarget == null } == true
        val cp = s.configuration.computerPoint ?: return false
        if (!s.info.canBeManned) return false
        return s.room?.crew?.any {
            it.mode == AbstractCrew.SlotType.CREW && it.canManSystem && it.standingPosition?.let { p -> cp posEq p } == true
        } == true
    }

    /** ShipSystem::bOccupied (enemies in the room: sabotage keeps fDamageOverTime) and bFriendlies (crew: repair). */
    private fun occupied(s: AbstractSystem) = crewIn(s, AbstractCrew.SlotType.INTRUDER)
    private fun friendlies(s: AbstractSystem) = crewIn(s, AbstractCrew.SlotType.CREW)

    /** The room's top-left corner in the ShipGraph frame (Repairable::pLoc, set by ShipManager through SetLocation). */
    private fun pLocX(s: AbstractSystem) = s.hsState?.pLocX ?: (s.room?.offsetX ?: 0)
    private fun pLocY(s: AbstractSystem) = s.hsState?.pLocY ?: (s.room?.offsetY ?: 0)

    /** ShipSystem::roomId (SetRoomId recomputes it from pLoc). */
    private fun roomId(s: AbstractSystem) = s.hsState?.roomId ?: (s.room?.id ?: -1)

    /** ShipSystem::IsMannedBoost (1.6.9 0x6158f0). */
    private fun mannedBoost(s: AbstractSystem): Int {
        val am = activeManned(s)
        return if (am > 0 && st(s).bBoostable && s.damagedEnergyLevels == 0) am else 0
    }

    private fun activeManned(s: AbstractSystem): Int =
        st(s).frame[A.A_ShipSystem_iActiveManned]?.toInt() ?: LuaReg.numGet[A.A_ShipSystem_iActiveManned]!!.get(s).toInt()

    /** ShipSystem::BlockedBoosted(countLimit) (1.6.9 0x6155f0). */
    private fun blockedBoosted(s: AbstractSystem, countLimit: Boolean): Boolean {
        val state = st(s)
        if (countLimit) {
            val lb = if (state.bBoostable && state.bLevelBoostable) 1 else 0
            val cap = s.scriptedPowerLimit ?: HsPower.CAP_NONE
            if (cap < lb + s.energyLevels) return true
        }
        val lock = lockCount(s)
        val onFire = s.room?.fires?.any { it != null } == true
        if (!occupied(s) && !onFire && lock != -1 && lock < 1 && hackEffect(s) < 2 && s.damagedEnergyLevels == 0 &&
            HsSystemsLua.effectivePower(s) > 0 && (hackEffect(s) < 1 || !underAttack(s)) && s.scriptedPowerLoss < 1)
            return false
        return true
    }

    /** ShipSystem::computerLevel: the interior glow ShipSystem::OnRenderFloor (1.6.9 0x6156d0) picks. */
    private fun computerLevel(s: AbstractSystem): Int {
        val state = st(s)
        state.frame[A.A_ShipSystem_computerLevel]?.let { return it.toInt() }
        val id = nativeSystemId(s)
        var level = -1
        if (state.bBoostable || id == 6) {
            val am = activeManned(s)
            val full = s.damagedEnergyLevels == 0
            level = if (state.bBoostable && full && !blockedBoosted(s, true)) (if (am in 2..3) am else 1) else 0
        }
        if (state.bLevelBoostable) level = 0
        return level
    }

    // ------------------------------------------------------------------ methods with native bodies

    /** ShipSystem::PartialRepair(speed, autoRepair) (1.6.9 0x614a80, HS ErosionEffect.cpp multiplier). */
    @JvmStatic
    fun partialRepair(s: AbstractSystem, speed: Float, auto: Boolean): Boolean {
        if (s.damagedEnergyLevels <= 0) return false
        var amount = speed * 0.5f * speedFactor()
        if (auto) amount /= 3f
        if (underAttack(s)) amount *= 0.5f
        if (HS.active) amount *= xyz.znix.xftl.hs.systems.HsErosionRoom.repairMultiplier(s.room)
        amount += s.repairProgress * 100f
        val state = st(s)
        state.repairedLastFrame = true
        if (amount >= 100f) {
            s.hsSetRepairProgress(0f)
            s.damagedEnergyLevels = max(0, s.damagedEnergyLevels - 1)
            val id = nativeSystemId(s)
            if (id != 3 && id != 4) s.powerLimitChanged() else s.powerStateChanged()
            return true
        }
        s.hsSetRepairProgress(amount / 100f)
        return false
    }

    /** ShipSystem::DamageOverTime(amount) (1.6.9 0x617640); PartialDamage calls it. */
    @JvmStatic
    fun damageOverTime(s: AbstractSystem, amount: Float): Boolean {
        val state = st(s)
        val f = speedFactor() * amount + s.damageProgress * 100f
        var result = false
        if (f >= 100f) {
            s.hsSetDamageProgress(0f)
            val was = s.broken
            s.addDamage(1)
            state.bExploded = !was && s.broken
            result = true
        } else s.hsSetDamageProgress(f / 100f)
        state.damagedLastFrame = true
        return result
    }

    /** ShipSystem::AddLock(lock) (1.6.9 0x615aa0). */
    @JvmStatic
    fun addLock(s: AbstractSystem, lock: Int) {
        val count = lockCount(s)
        if ((count < 1 && count != -1) || count == -1 || hackEffect(s) > 1) {
            if (count == 0 || lock != 0) setLockCount(s, lock)
            else setLockCount(s, 0)
        } else {
            setLockCount(s, min(count + lock, 5))
        }
    }

    /** iLockCount written (LockSystem, AddLock, the attribute): -1 holds, 0 unlocks and repowers, n starts n units. */
    private fun setLockCount(s: AbstractSystem, n: Int) {
        if (n < 0) {
            s.hsLuaPermanentLock = true
            s.lockUnits(0)
        } else {
            s.hsLuaPermanentLock = false
            if (n == 0) {
                val was = s.ionTimer > 0f
                s.lockUnits(0)
                s.ionPowerLimit = null
                if (was) s.powerLimitChanged()
            } else s.lockUnits(n)
        }
        s.powerStateChanged()
    }

    /** ShipSystem::Ioned(num) (1.6.9 0x614ec0): true while locked (or pulsed) if num bars still fit, or none are on. */
    private fun ioned(s: AbstractSystem, num: Int): Boolean {
        val lock = lockCount(s)
        if (!(lock > 0 || lock == -1) && hackEffect(s) <= 1) return false
        val m = maxPower(s)
        val free = m - power(s)
        return num <= free || m <= free
    }

    /** ShipSystem::IncreasePower(amount, force) (1.6.9 0x617280). */
    private fun increasePower(s: AbstractSystem, amount: Int, force: Boolean): Boolean {
        val lock = lockCount(s)
        if (lock == -1 || lock > 0) return false
        if (hackEffect(s) >= 2 || !st(s).bNeedsPower) return false
        val m = s as? MainSystem ?: return false
        return forceIncrease(m, amount, force)
    }

    /** ShipSystem::ForceIncreasePower(amount): all [amount] bars or none, within GetMaxPower. */
    private fun forceIncrease(m: MainSystem, amount: Int, user: Boolean): Boolean {
        if (power(m) + amount + bonus(m) + battery(m) > maxPower(m)) return false
        if (amount <= 0) return true
        return m.hsSetPower(m.powerSelected + amount, user)
    }

    /** ShipSystem::DecreasePower(force) (1.6.9 0x6173e0): one bar off (PowerManager::DecreasePower). */
    private fun decreasePower(s: AbstractSystem, force: Boolean): Boolean {
        if (!st(s).bNeedsPower) return false
        val lock = lockCount(s)
        if (lock == -1 || lock > 0) return false
        if (hackEffect(s) >= 2) return false
        val m = s as? MainSystem ?: return false
        if (m.powerSelected - m.forcedPower <= 0) return false
        return m.hsSetPower(m.powerSelected - 1, force)
    }

    /** ShipSystem::ForceDecreasePower(n) (1.6.9 0x614120): n single bars; a subsystem's power state just drops. */
    private fun forceDecrease(s: AbstractSystem, n: Int): Boolean {
        val m = s as? MainSystem ?: return power(s) > 0
        if (n < 1) return true
        var ok = true
        repeat(n) {
            // Shields give up a whole layer at a time.
            if (ok) ok = m.powerSelected - m.forcedPower > 0 && m.hsSetPower(m.powerSelected - if (m is Shields) 2 else 1, false)
        }
        return ok
    }

    /** ShipSystem::UpgradeSystem(amount) (1.6.9 0x615b80): levels and health grow; a destroyed system stays at 0. */
    private fun upgrade(s: AbstractSystem, amount: Int): Boolean {
        val destroyed = s.broken
        s.energyLevels = max(0, s.energyLevels + amount)
        if (destroyed) s.damagedEnergyLevels = s.energyLevels
        else s.damagedEnergyLevels = s.damagedEnergyLevels.coerceIn(0, s.energyLevels)
        s.powerLimitChanged()
        return destroyed
    }

    /** ShipSystem::SetRoomId (1.6.9 0x466eb0): ShipGraph::GetSelectedRoom(pLoc + 17, false). */
    private fun recomputeRoom(s: AbstractSystem) {
        val ship = shipOf(s) ?: return
        val x = pLocX(s) + 17; val y = pLocY(s) + 17
        val r = ship.rooms.firstOrNull { x > it.offsetX && x < it.offsetX + it.pixelWidth && y > it.offsetY && y < it.offsetY + it.pixelHeight }
        val id = r?.id ?: -1
        st(s).roomId = if (id == s.room?.id) null else id
    }

    // ------------------------------------------------------------------ install

    fun install() {
        points()
        timers()
        shipSystem()
        shipSystemMethods()
        repairable()
        powerManager()
        shieldsMembers()
        oxygen()
        subtypes()
        superShield()
    }

    private fun points() {
        // Globals::Ellipse() (SWIG new): a value-initialised struct
        if (LuaReg.methods[A.M_new_Ellipse] == null) LuaReg.method(A.M_new_Ellipse, LuaMethod { ret.obj(LuaStructs.create(A.T_Ellipse), A.T_Ellipse) })
        val xg = LuaReg.numGet[A.A_Point_x]; val xs = LuaReg.numSet[A.A_Point_x]
        val yg = LuaReg.numGet[A.A_Point_y]; val ys = LuaReg.numSet[A.A_Point_y]
        LuaReg.num(A.A_Point_x, NumGet { if (it is LuaPointMember) it.x.toDouble() else xg!!.get(it) },
            NumSet { o, v -> if (o is LuaPointMember) o.x = v.toInt() else xs!!.set(o, v) })
        LuaReg.num(A.A_Point_y, NumGet { if (it is LuaPointMember) it.y.toDouble() else yg!!.get(it) },
            NumSet { o, v -> if (o is LuaPointMember) o.y = v.toInt() else ys!!.set(o, v) })
    }

    private fun timers() {
        fun timerAttr(a: Int, get: (LuaTimerView) -> Double, set: (LuaTimerView, Double) -> Unit) {
            val g = LuaReg.numGet[a]; val s = LuaReg.numSet[a]
            LuaReg.num(a, NumGet { o -> if (o is LuaTimerView) get(o) else (g ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).get(o) },
                NumSet { o, v -> if (o is LuaTimerView) set(o, v) else (s ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.attrName(a))).set(o, v) })
        }
        // SWIG: int fields take (int) lua_tonumber, bool fields lua_toboolean
        timerAttr(A.A_TimerHelper_maxTime, { it.maxTime.toDouble() }, { t, v -> t.maxTime = v.toInt() })
        timerAttr(A.A_TimerHelper_minTime, { it.minTime.toDouble() }, { t, v -> t.minTime = v.toInt() })
        timerAttr(A.A_TimerHelper_currTime, { it.currTime.toDouble() }, { t, v -> t.currTime = v.toFloat() })
        timerAttr(A.A_TimerHelper_currGoal, { it.currGoal.toDouble() }, { t, v -> t.currGoal = v.toFloat() })
        timerAttr(A.A_TimerHelper_loop, { d(it.loop) }, { t, v -> t.loop = v != 0.0 })
        timerAttr(A.A_TimerHelper_running, { d(it.running) }, { t, v -> t.running = v != 0.0 })
        fun timerMethod(m: Int, f: (LuaTimerView) -> Int) {
            val prev = LuaReg.methods[m]
            LuaReg.method(m, LuaMethod { n ->
                val t = arg.obj(1)
                if (t is LuaTimerView) f(t) else (prev ?: throw LuaError("Hyperspace Lua API not available in the browser engine: " + A.methodName(m))).call(n)
            })
        }
        timerMethod(A.M_TimerHelper_Running) { ret.bool(it.running) }
        timerMethod(A.M_TimerHelper_Stop) { it.running = false; 0 }
        timerMethod(A.M_TimerHelper_Start_Float) { it.currGoal = arg.float(2); it.currTime = 0f; it.running = true; 0 }
        timerMethod(A.M_TimerHelper_Done) {
            // TimerHelper::Done: a running timer past its goal stops (or restarts when looping) and reports once
            val done = it.running && it.currTime >= it.currGoal
            if (done) { if (it.loop) it.currTime = 0f else it.running = false }
            ret.bool(done)
        }
    }

    /** ShipSystem::lockTimer: one lock unit (the engine's ion timer, TIME_PER_ION per unit). */
    private class LockTimer(val s: AbstractSystem) : LuaTimerView() {
        private val state get() = HsSystemState.of(s)
        private val unit get() = AbstractSystem.TIME_PER_ION
        override var maxTime: Int get() = state.lockMax; set(v) { state.lockMax = v }
        override var minTime: Int get() = state.lockMin; set(v) { state.lockMin = v }
        override var currTime: Float
            get() { val r = s.ionTimer % unit; return if (s.ionTimer <= 0f || r == 0f) 0f else unit - r }
            set(v) {
                val units = kotlin.math.ceil(s.ionTimer / unit)
                if (units > 0f) s.ionTimer = units * unit - v.coerceIn(0f, unit)
            }
        override var currGoal: Float get() = state.lockGoal ?: unit; set(v) { state.lockGoal = v }
        override var loop: Boolean get() = state.lockLoop; set(v) { state.lockLoop = v }
        override var running: Boolean get() = s.ionTimer > 0f; set(v) { if (!v) s.lockUnits(0) }
    }

    /** CloakingSystem::timer: the cloak's length (currGoal) and elapsed time (currTime) while it is on. */
    private class CloakTimer(val c: Cloaking) : LuaTimerView() {
        // CloakingSystem::constructor: TimerHelper{maxTime 500, minTime 500, currGoal 0.5}; SetTurnedOn starts it
        // with the cloak's length (TimerHelper::Start keeps min/max), so currGoal is 0.5 until the first cloak.
        override var maxTime: Int = 500
        override var minTime: Int = 500
        override var currTime: Float
            get() { val left = c.timeRemaining ?: return 0f; return c.timerMax - left }
            set(v) { if (c.timeRemaining != null) c.timeRemaining = c.timerMax - v }
        override var currGoal: Float
            get() = if (c.timerMax > 0f) c.timerMax else 0.5f
            set(v) { val elapsed = currTime; c.timerMax = v; if (c.timeRemaining != null) c.timeRemaining = v - elapsed }
        override var loop: Boolean = false
        override var running: Boolean get() = c.active; set(v) { if (!v && c.active) c.timeRemaining = 0f }
    }

    private fun frameNum(a: Int, live: (AbstractSystem) -> Double) {
        LuaReg.num(a, NumGet { o -> st(o).frame[a] ?: live(sys(o)) }, NumSet { o, v -> st(o).frame[a] = v })
    }

    private fun frameBool(a: Int, live: (AbstractSystem) -> Boolean) {
        LuaReg.num(a, NumGet { o -> st(o).frame[a] ?: d(live(sys(o))) }, NumSet { o, v -> st(o).frame[a] = d(v != 0.0) })
    }

    private fun shipSystem() {
        // Repairable fields
        LuaReg.num(A.A_ShipSystem_fDamage, NumGet { st(it).fDamage.toDouble() }, NumSet { o, v -> st(o).fDamage = v.toFloat() })
        LuaReg.num(A.A_ShipSystem_fMaxDamage, NumGet { st(it).fMaxDamage.toDouble() }, NumSet { o, v -> st(o).fMaxDamage = v.toFloat() })
        LuaReg.num(A.A_ShipSystem_iRepairCount, NumGet { st(it).iRepairCount.toDouble() }, NumSet { o, v -> st(o).iRepairCount = v.toInt() })
        LuaReg.obj(A.A_ShipSystem_pLoc, ObjGet { o ->
            LuaViews.cached(sys(o), A.A_ShipSystem_pLoc) { s ->
                val x = s as AbstractSystem
                LuaPointMember(IGet { pLocX(x) }, ISet { HsSystemState.of(x).pLocX = it }, IGet { pLocY(x) }, ISet { HsSystemState.of(x).pLocY = it })
            }
        })
        LuaReg.num(A.A_ShipSystem_roomId, NumGet { roomId(sys(it)).toDouble() }, NumSet { o, v ->
            val s = sys(o); val id = v.toInt(); st(s).roomId = if (id == s.room?.id) null else id
        })
        LuaReg.str(A.A_ShipSystem_name, StrGet { st(it).name ?: sys(it).codename }, StrSet { o, v -> st(o).name = v })

        // constructor fields (persistent)
        LuaReg.bool(A.A_ShipSystem_bNeedsManned, { st(it).bNeedsManned }, { o, v -> st(o).bNeedsManned = v })
        LuaReg.bool(A.A_ShipSystem_bBoostable, { st(it).bBoostable }, { o, v -> st(o).bBoostable = v })
        LuaReg.bool(A.A_ShipSystem_bLevelBoostable, { st(it).bLevelBoostable }, { o, v -> st(o).bLevelBoostable = v })
        LuaReg.num(A.A_ShipSystem_iRequiredPower, NumGet { st(it).iRequiredPower.toDouble() }, NumSet { o, v -> st(o).iRequiredPower = v.toInt() })
        LuaReg.bool(A.A_ShipSystem_bNeedsPower, { st(it).bNeedsPower }, { o, v -> st(o).bNeedsPower = v })
        LuaReg.num(A.A_ShipSystem_originalPower, NumGet { st(it).originalPower.toDouble() }, NumSet { o, v -> st(o).originalPower = v.toInt() })
        LuaReg.num(A.A_ShipSystem_bpCost, NumGet { st(it).bpCost.toDouble() }, NumSet { o, v -> st(o).bpCost = v.toInt() })
        LuaReg.bool(A.A_ShipSystem_bTriggerIon, { st(it).bTriggerIon }, { o, v -> st(o).bTriggerIon = v })
        LuaReg.bool(A.A_ShipSystem_bExploded, { st(it).bExploded }, { o, v -> st(o).bExploded = v })
        LuaReg.str(A.A_ShipSystem_interiorImageName, StrGet { o ->
            st(o).interiorImageName ?: sys(o).configuration.spec.interiorImage?.substringAfterLast('/')?.removeSuffix(".png")
                ?: "room_" + sys(o).codename
        }, StrSet { o, v -> st(o).interiorImageName = v })

        // per-frame fields: a script's write holds until the system's next loop
        frameBool(A.A_ShipSystem_bOccupied) { occupied(it) }
        frameBool(A.A_ShipSystem_bFriendlies) { friendlies(it) }
        frameBool(A.A_ShipSystem_bOnFire) { it.room?.fires?.any { f -> f != null } == true }
        frameBool(A.A_ShipSystem_bBreached) { it.room?.breaches?.any { b -> b != null } == true }
        val mannedLevel = LuaReg.numGet[A.A_ShipSystem_iActiveManned]!!
        frameNum(A.A_ShipSystem_iActiveManned) { mannedLevel.get(it) }
        frameBool(A.A_ShipSystem_bManned) { manned(it) }
        LuaReg.num(A.A_ShipSystem_damagedLastFrame, NumGet { d(st(it).damagedLastFrame) }, NumSet { o, v -> st(o).damagedLastFrame = v != 0.0 })
        LuaReg.num(A.A_ShipSystem_repairedLastFrame, NumGet { d(st(it).repairedLastFrame) }, NumSet { o, v -> st(o).repairedLastFrame = v != 0.0 })
        frameNum(A.A_ShipSystem_iLastBonusPower) { bonus(it).toDouble() }
        frameNum(A.A_ShipSystem_computerLevel) { computerLevel(it).toDouble() }

        // the engine's progress bars in FTL's 0..100 units
        LuaReg.num(A.A_ShipSystem_fRepairOverTime, NumGet { (sys(it).repairProgress * 100f).toDouble() }, NumSet { o, v -> sys(o).hsSetRepairProgress(v.toFloat() / 100f) })
        LuaReg.num(A.A_ShipSystem_fDamageOverTime, NumGet { (sys(it).damageProgress * 100f).toDouble() }, NumSet { o, v -> sys(o).hsSetDamageProgress(v.toFloat() / 100f) })

        // lastUserPower: the bars the player last chose (the engine's target, CheckForRepower restores it)
        LuaReg.num(A.A_ShipSystem_lastUserPower, NumGet { ((it as? MainSystem)?.targetPower ?: power(sys(it))).toDouble() }, NumSet { o, v ->
            (o as? MainSystem)?.targetPower = v.toInt()
        })

        LuaReg.num(A.A_ShipSystem_iLockCount, NumGet { lockCount(sys(it)).toDouble() }, NumSet { o, v -> setLockCount(sys(o), v.toInt()) })
        LuaReg.obj(A.A_ShipSystem_lockTimer, ObjGet { o -> LuaViews.cached(sys(o), A.A_ShipSystem_lockTimer) { s -> LockTimer(s as AbstractSystem) } })
        LuaReg.obj(A.A_ShipSystem_flashTracker, ObjGet { st(it).flashTracker })
        LuaReg.bool(A.A_ShipSystem_bUnderAttack, { underAttack(sys(it)) }, { o, v -> st(o).underAttack = v })

        // ShipSystem::location: the room centre (Pointf of the integer halves, ShipSystem::constructor)
        LuaReg.obj(A.A_ShipSystem_location, ObjGet { o ->
            LuaViews.cached(sys(o), A.A_ShipSystem_location) { s ->
                val x = s as AbstractSystem
                var lx: Float? = null; var ly: Float? = null
                LuaPointfMember(FGet { lx ?: x.room?.let { r -> (r.offsetX + r.pixelWidth / 2).toFloat() } ?: 0f }, FSet { lx = it },
                    FGet { ly ?: x.room?.let { r -> (r.offsetY + r.pixelHeight / 2).toFloat() } ?: 0f }, FSet { ly = it })
            }
        })

        // the interior primitives: the floor image (when the layout has one) and, for the systems with a computer
        // glow (ShipSystem::SetFloorImage), the three glow primitives. The engine draws no glow: they render nothing.
        // A system the layout gives no image (ShipManager::AddSystem after the ship was built) loads its
        // interiorImageName under ship/interior/ (ShipSystem::SetFloorImage), as ShipManager names it room_<name>.
        fun interiorPath(s: AbstractSystem): String? = s.configuration.spec.interiorImage?.removePrefix("img/")
            ?: LuaReg.strGet[A.A_ShipSystem_interiorImageName]!!.get(s)?.takeIf { it.isNotEmpty() }?.let { "ship/interior/$it.png" }
        fun interior(a: Int, glow: Boolean) = LuaReg.obj(a, ObjGet { o ->
            val s = sys(o)
            val path = interiorPath(s)
            if (path == null || (glow && (s.configuration.spec.interiorImage == null || nativeSystemId(s) !in intArrayOf(0, 1, 3, 6)))) null
            else LuaViews.cached(s, a) { x ->
                LuaPrimitive(if (glow) null else LuaGraphics.texture(interiorPath(x as AbstractSystem)!!), 0f, 0f, 0f, 0f, 0f, false, GlColorValue(1f, 1f, 1f, 1f))
            }
        })
        interior(A.A_ShipSystem_interiorImage, false)
        interior(A.A_ShipSystem_interiorImageOn, true)
        interior(A.A_ShipSystem_interiorImageManned, true)
        interior(A.A_ShipSystem_interiorImageMannedFancy, true)
    }

    private fun shipSystemMethods() {
        fun m(id: Int, f: (AbstractSystem) -> Int) = LuaReg.method(id, LuaMethod { f(sys(arg.obj(1))) })
        m(A.M_ShipSystem_SetSelected) { st(it).selected = arg.int(2); 0 }
        m(A.M_ShipSystem_GetSelected) { ret.num(st(it).selected) }
        // SetName(std::string&): SWIG only takes a std::string object (a Lua string fails its type check)
        m(A.M_ShipSystem_SetName) { s ->
            val v = arg.obj(2) as? LuaStdString ?: throw LuaError("Error in ShipSystem::SetName (arg 2), expected 'std::string &'")
            st(s).name = v.value; 0
        }
        m(A.M_ShipSystem_Repair) { st(it).fDamage = 0f; 0 }
        m(A.M_ShipSystem_PartialRepair) { ret.bool(partialRepair(it, arg.float(2), arg.bool(3))) }
        m(A.M_ShipSystem_PartialDamage) { ret.bool(damageOverTime(it, arg.float(2))) }
        m(A.M_ShipSystem_DamageOverTime) { ret.bool(damageOverTime(it, arg.float(2))) }
        m(A.M_ShipSystem_CanBeSabotaged) { ret.bool(health(it) != 0) }
        m(A.M_ShipSystem_SetDamage) { val s = st(it); s.fDamage = min(arg.float(2), s.fMaxDamage); 0 }
        m(A.M_ShipSystem_SetMaxDamage) { st(it).fMaxDamage = arg.float(2); 0 }
        // SetLocation(Point): pLoc, then SetRoomId (the engine keeps the system in its room)
        m(A.M_ShipSystem_SetLocation) { s ->
            val p = arg.obj(2)
            val x = when (p) { is PointValue -> p.x; is LuaPointMember -> p.x; is LuaStruct -> (p.values[A.A_Point_x] as? Double ?: 0.0).toInt(); else -> 0 }
            val y = when (p) { is PointValue -> p.y; is LuaPointMember -> p.y; is LuaStruct -> (p.values[A.A_Point_y] as? Double ?: 0.0).toInt(); else -> 0 }
            val state = st(s)
            state.pLocX = if (x == s.room?.offsetX) null else x
            state.pLocY = if (y == s.room?.offsetY) null else y
            recomputeRoom(s); 0
        }
        m(A.M_ShipSystem_SetRoomId) { recomputeRoom(it); 0 }
        m(A.M_ShipSystem_IsRoomBased) { ret.bool(true) }
        m(A.M_ShipSystem_Ioned) { ret.bool(ioned(it, arg.int(2))) }
        m(A.M_ShipSystem_GetNeedsPower) { ret.bool(st(it).bNeedsPower) }
        m(A.M_ShipSystem_Clickable) { ret.bool(st(it).bNeedsPower) }
        m(A.M_ShipSystem_AddLock) { addLock(it, arg.int(2)); 0 }
        m(A.M_ShipSystem_LockSystem) { s -> val n = arg.int(2); if (n == 0) { if (lockCount(s) != 0) setLockCount(s, 0) } else setLockCount(s, n); 0 }
        m(A.M_ShipSystem_DecreasePower) { ret.bool(decreasePower(it, arg.bool(2))) }
        m(A.M_ShipSystem_IncreasePower) { ret.bool(increasePower(it, arg.int(2), arg.bool(3))) }
        m(A.M_ShipSystem_ForceIncreasePower) { s -> val mm = s as? MainSystem; ret.bool(mm != null && forceIncrease(mm, arg.int(2), false)) }
        m(A.M_ShipSystem_ForceDecreasePower) { ret.bool(forceDecrease(it, arg.int(2))) }
        m(A.M_ShipSystem_IsMannedBoost) { ret.num(mannedBoost(it)) }
        // GetPowerCap (1.6.9 0x6154e0): iTempPowerCap below 8, else powerState.second
        m(A.M_ShipSystem_GetPowerCap) { s -> val cap = s.scriptedPowerLimit ?: HsPower.CAP_NONE; ret.num(if (cap < 8) max(0, cap) else max(0, s.energyLevels)) }
        // GetLocked (1.6.9 0x615930): iLockCount == -1 || iLockCount > 0 || iHackEffect > 1
        m(A.M_ShipSystem_GetLocked) { s -> val l = lockCount(s); ret.bool(l == -1 || l > 0 || hackEffect(s) > 1) }
        m(A.M_ShipSystem_StopHacking) { s -> st(s).underAttack = false; s.hsHackLevel = 0; 0 }
        // ShipSystem::UpgradeSystem returns what its CompletelyDestroyed check said before the
        // upgrade (oracle sweep-world-ys1 y-upgrade: false for a working system)
        m(A.M_ShipSystem_UpgradeSystem) { ret.bool(upgrade(it, arg.int(2))) }
        // static ShipSystem.IsSubsystem(systemId) (HS's hook for custom ids)
        LuaReg.method(A.M_ShipSystem_IsSubsystem, LuaMethod { ret.bool(xyz.znix.xftl.hs.systems.HsSystems.isSubsystemId(arg.int(1))) })
        // static ShipSystem.GetLevelDescription(systemId, level, tooltip)
        // the static goes through HS's GetLevelDescription hook too: GET_LEVEL_DESCRIPTION's string replaces the text (oracle
        // sweep-world-ex1 ex-desc: the example absorber's callback answers ShipSystem.GetLevelDescription(21, level, tooltip))
        LuaReg.method(A.M_ShipSystem_GetLevelDescription, LuaMethod {
            val id = arg.int(1); val level = arg.int(2); val tooltip = arg.bool(3)
            ret.str(LuaShipEvents.levelDescription(id, level, tooltip) { levelDescription(id, level, tooltip) })
        })
        // Shields' own overrides of the base methods
        LuaReg.methods[A.M_ShipSystem_ForceIncreasePower]?.let { LuaReg.method(A.M_Shields_ForceIncreasePower, it) }
        LuaReg.methods[A.M_ShipSystem_ForceDecreasePower]?.let { LuaReg.method(A.M_Shields_ForceDecreasePower, it) }
    }

    /**
     * ShipSystem::GetLevelDescription(systemId, level, tooltip) (1.6.9 0x6192b0) with HS's hooks
     * (SystemDescriptionFixes.cpp: medbay, teleporter and clonebay level 4; CustomSystems.cpp: the clone levels).
     * The text-key systems read `<prefix><level + 1>` for every level (pilot_4, mind_4, ...); the clonebay's tooltip
     * reads `clone` with the time only; hacking's duration table (4, 7, 10) gives 0 past the third level. The others
     * use the engine's level texts, which follow the same formulas.
     */
    private fun levelDescription(id: Int, level: Int, tooltip: Boolean): String {
        val game = HsLua.game ?: return ""
        val tr = game.gameContent?.translator ?: return ""
        fun text(key: String) = tr.translations[key] ?: tr[key]
        if (level >= 0) when (id) {
            0 -> return if (level and 1 == 0) "" else text("shields_${level shr 1}")
            6 -> return text("pilot_${level + 1}")
            7 -> if (level < 2) return text("sensor_${level + 1}")
            8 -> return text("door_${level + 1}")
            11 -> return text("artillery_${level + 1}")
            13 -> return when {
                level == 3 -> text("clonebay_instant_fullheal")
                tooltip -> text("clone").replaceArg(xyz.znix.xftl.hs.systems.HsClone.cloneTime(level + 1))
                else -> xyz.znix.xftl.hs.systems.HsClone.levelName(level, text("clone_full"))
            }
            14 -> return text("mind_${level + 1}")
            15 -> return text("hacking_duration").replaceArg(if (level < 3) 4 + 3 * level else 0)
        }
        val name = xyz.znix.xftl.hs.systems.HsSystems.idToName(id)
        val bp = game.gameContent?.blueprintManager?.getOrNull(name) as? SystemBlueprint ?: return ""
        val info = bp.info ?: return ""
        return try { info.getLevelName(level, tr) } catch (e: Exception) { "" }
    }

    // ------------------------------------------------------------------ Repairable (systems, breaches, fires)

    private fun repairable() {
        fun rNum(a: Int, get: (Any?) -> Double, set: ((Any?, Double) -> Unit)?) {
            LuaReg.num(a, NumGet { o ->
                if (o is AbstractSystem) LuaReg.numGet[systemTwin(a)]!!.get(o) else get(o)
            }, NumSet { o, v ->
                if (o is AbstractSystem) LuaReg.numSet[systemTwin(a)]!!.set(o, v)
                else (set ?: throw LuaError("This variable is immutable"))(o, v)
            })
        }
        // breaches and fires: fDamage is the remaining strength out of fMaxDamage 100 (Fire / OuterHull)
        rNum(A.A_Repairable_fDamage, { o -> health(o) * 100.0 }, { o, v -> setHealth(o, (v / 100.0).toFloat()) })
        rNum(A.A_Repairable_fMaxDamage, { 100.0 }, null)
        rNum(A.A_Repairable_roomId, { o -> roomOf(o)?.id?.toDouble() ?: -1.0 }, null)
        rNum(A.A_Repairable_iRepairCount, { 0.0 }, null)
        LuaReg.obj(A.A_Repairable_pLoc, ObjGet { o ->
            if (o is AbstractSystem) LuaReg.objGet[A.A_ShipSystem_pLoc]!!.get(o)
            else {
                val r = roomOf(o) ?: return@ObjGet PointValue(0, 0)
                val slot = slotOf(o)
                PointValue(r.offsetX + (slot % r.width) * 35, r.offsetY + (slot / r.width) * 35)
            }
        })
        LuaReg.str(A.A_Repairable_name, StrGet { o -> if (o is AbstractSystem) LuaReg.strGet[A.A_ShipSystem_name]!!.get(o) else "" })
        LuaReg.obj(A.A_Repairable_shipObj, ObjGet { o -> if (o is AbstractSystem) shipOf(o) else roomOf(o)?.ship })
    }

    private fun systemTwin(a: Int): Int = when (a) {
        A.A_Repairable_fDamage -> A.A_ShipSystem_fDamage
        A.A_Repairable_fMaxDamage -> A.A_ShipSystem_fMaxDamage
        A.A_Repairable_roomId -> A.A_ShipSystem_roomId
        A.A_Repairable_iRepairCount -> A.A_ShipSystem_iRepairCount
        else -> a
    }

    private fun roomOf(o: Any?) = when (o) { is BreachInstance -> o.room; is FireInstance -> o.room; else -> null }
    private fun slotOf(o: Any?) = when (o) { is BreachInstance -> o.slot; is FireInstance -> o.slot; else -> 0 }
    private fun health(o: Any?): Float = when (o) { is BreachInstance -> o.health; is FireInstance -> o.health; else -> 0f }
    private fun setHealth(o: Any?, v: Float) { when (o) { is BreachInstance -> o.health = v; is FireInstance -> o.health = v } }

    // ------------------------------------------------------------------ PowerManager

    private fun powerManager() {
        fun p(o: Any?) = HsPower.of((o as LuaPower).ship)
        LuaReg.num(A.A_PowerManager_over_powered, NumGet { p(it).overPowered.toDouble() }, NumSet { o, v -> p(o).overPowered = v.toInt() })
        LuaReg.num(A.A_PowerManager_fFuel, NumGet { p(it).fuel.toDouble() }, NumSet { o, v -> p(o).fuel = v.toFloat() })
        LuaReg.bool(A.A_PowerManager_failedPowerup, { p(it).failedPowerup }, { o, v -> p(o).failedPowerup = v })
        LuaReg.num(A.A_PowerManager_iHacked, NumGet { p(it).hacked.toDouble() }, NumSet { o, v ->
            val pm = p(o); pm.hacked = v.toInt(); (o as LuaPower).ship.updateAvailablePower()
        })
        // batteryPower = (battery bars in use, the bars an active battery gives)
        LuaReg.obj(A.A_PowerManager_batteryPower, ObjGet { o ->
            val ship = (o as LuaPower).ship
            LuaViews.cached(ship, A.A_PowerManager_batteryPower) { s ->
                val sh = s as Ship
                IntPairView(A.T_pair_int_int, IGet {
                    val given = sh.backupBattery?.contributedPower ?: 0
                    (given - (sh.powerAvailableTypes[BatteryEnergySource] ?: 0)).coerceIn(0, given)
                }, null, IGet { sh.backupBattery?.contributedPower ?: 0 }, null)
            }
        })
    }

    // ------------------------------------------------------------------ Shields

    private fun shieldsMembers() {
        fun sh(o: Any?) = o as? Shields ?: throw LuaError("expected Shields")
        fun ss(o: Any?) = shields(sh(o))
        fun ship(o: Any?) = sh(o).room!!.ship
        fun shipCenterX(s: Ship): Int = s.shieldOrigin.x - (s.type.shieldEllipse.pos.x)
        fun shipCenterY(s: Ship): Int = s.shieldOrigin.y - (s.type.shieldEllipse.pos.y)

        LuaReg.num(A.A_Shields_ellipseRatio, NumGet { o -> (ss(o).ellipseRatio ?: ship(o).shieldHalfSize.let { it.y.toFloat() / it.x }).toDouble() },
            NumSet { o, v -> ss(o).ellipseRatio = v.toFloat() })
        LuaReg.obj(A.A_Shields_center, ObjGet { o ->
            LuaViews.cached(sh(o), A.A_Shields_center) { x ->
                val s = x as Shields; val state = shields(s)
                LuaPointMember(IGet { state.centerX ?: shipCenterX(s.room!!.ship) }, ISet { state.centerX = it },
                    IGet { state.centerY ?: shipCenterY(s.room!!.ship) }, ISet { state.centerY = it })
            }
        })
        // baseShield (Globals::Ellipse): centre = the shield origin, a/b = the shield's half size
        LuaReg.obj(A.A_Shields_baseShield, ObjGet { o ->
            val s = sh(o); val state = shields(s); val shipO = s.room!!.ship
            val e = LuaViews.cached(s, A.A_Shields_baseShield) { LuaStructs.create(A.T_Ellipse) }
            val v = state.ellipse ?: doubleArrayOf(shipO.shieldOrigin.x.toDouble(), shipO.shieldOrigin.y.toDouble(),
                shipO.shieldHalfSize.x.toDouble(), shipO.shieldHalfSize.y.toDouble())
            (LuaStructs.member(e, A.A_Ellipse_center) as? LuaStruct)?.let { LuaStructs.setFields(it, v[0], v[1]) }
            e.values[A.A_Ellipse_a] = v[2]; e.values[A.A_Ellipse_b] = v[3]
            e
        })
        LuaReg.num(A.A_Shields_iHighlightedSide, NumGet { ss(it).iHighlightedSide.toDouble() }, NumSet { o, v -> ss(o).iHighlightedSide = v.toInt() })
        LuaReg.bool(A.A_Shields_shields_shutdown, { ss(it).shieldsShutdown }, { o, v -> ss(o).shieldsShutdown = v })
        // the hit animations are the ship's (drawn by the engine); Lua sees an empty list
        // std::vector<Shields::ShieldAnimation> has no SWIG wrapper: an opaque userdata, as natively
        LuaReg.obj(A.A_Shields_shieldHits, ObjGet { o -> LuaViews.cached(sh(o), A.A_Shields_shieldHits) { LuaOpaque(A.attrType(A.A_Shields_shieldHits), null) } })
        LuaReg.obj(A.A_Shields_shieldsDown, ObjGet { ss(it).shieldsDown })
        LuaReg.bool(A.A_Shields_superShieldDown, { ss(it).superShieldDown }, { o, v -> ss(o).superShieldDown = v })
        LuaReg.obj(A.A_Shields_shieldsDownPoint, ObjGet { o ->
            LuaViews.cached(sh(o), A.A_Shields_shieldsDownPoint) { x ->
                val state = shields(x as Shields)
                LuaPointfMember(FGet { state.downX }, FSet { state.downX = it }, FGet { state.downY }, FSet { state.downY = it })
            }
        })
        LuaReg.obj(A.A_Shields_shieldsUp, ObjGet { ss(it).shieldsUp })
        LuaReg.obj(A.A_Shields_shieldImage, ObjGet { o -> LuaGraphics.texture(shieldImageName(sh(o)).removePrefix("img/")) })
        LuaReg.obj(A.A_Shields_shieldPrimitive, ObjGet { o ->
            LuaViews.cached(sh(o), A.A_Shields_shieldPrimitive) { x ->
                LuaPrimitive(LuaGraphics.texture(shieldImageName(x as Shields).removePrefix("img/")), 0f, 0f, 0f, 0f, 0f, false, GlColorValue(1f, 1f, 1f, 1f))
            }
        })
        LuaReg.str(A.A_Shields_shieldImageName, StrGet { shieldImageName(sh(it)) }, StrSet { o, v -> ss(o).shieldImageName = v })
        LuaReg.bool(A.A_Shields_bEnemyPresent, { o -> ss(o).enemyPresent ?: (ship(o).sys.getEnemyOf(ship(o)) != null) }, { o, v -> ss(o).enemyPresent = v })
        LuaReg.bool(A.A_Shields_bBarrierMode, { ss(it).barrierMode }, { o, v -> ss(o).barrierMode = v })
        LuaReg.num(A.A_Shields_lastHitTimer, NumGet { ss(it).lastHitTimer.toDouble() }, NumSet { o, v -> ss(o).lastHitTimer = v.toFloat() })
        LuaReg.num(A.A_Shields_chargeTime, NumGet { ss(it).chargeTime.toDouble() }, NumSet { o, v -> ss(o).chargeTime = v.toFloat() })
        LuaReg.num(A.A_Shields_lastHitShieldLevel, NumGet { ss(it).lastHitShieldLevel.toDouble() }, NumSet { o, v -> ss(o).lastHitShieldLevel = v.toInt() })
        LuaReg.obj(A.A_Shields_superShieldUp, ObjGet { ss(it).superShieldUp })
        LuaReg.obj(A.A_Shields_superUpLoc, ObjGet { o ->
            LuaViews.cached(sh(o), A.A_Shields_superUpLoc) { x ->
                val state = shields(x as Shields)
                LuaPointMember(IGet { state.superUpX }, ISet { state.superUpX = it }, IGet { state.superUpY }, ISet { state.superUpY = it })
            }
        })
        LuaReg.bool(A.A_Shields_bExcessChargeHack, { ss(it).excessChargeHack }, { o, v -> ss(o).excessChargeHack = v })
        LuaReg.num(A.A_Shield_superTimer, NumGet { shields((it as LuaShield).sys).superTimer.toDouble() }, NumSet { o, v -> shields((o as LuaShield).sys).superTimer = v.toFloat() })
        // Shield::charger is writable natively (the engine's recharge progress)
        val chargerGet = LuaReg.numGet[A.A_Shield_charger]!!
        LuaReg.num(A.A_Shield_charger, chargerGet, NumSet { o, v -> (o as LuaShield).sys.hsSetRechargeTimer(v.toFloat()) })

        // Shields::CollisionReal(x, y, damage, force): the hit on the bubble, as a shot's (Ship.attackShields).
        LuaReg.method(A.M_Shields_CollisionReal, LuaMethod {
            val s = sh(arg.obj(1)); val ship = s.room!!.ship
            val off = LuaAdaptersWritesPictures.luaOffset(s)
            ship.attackShields(LuaAdaptersWritableDamage.of(arg.obj(4)), xyz.znix.xftl.math.ConstPoint(arg.float(2).toInt() + off.first, arg.float(3).toInt() + off.second))
            // collision_type 2: the shot met the shields (oracle hw-f-h).
            val hit = LuaStructs.create(A.T_CollisionResponse)
            hit.values[A.A_CollisionResponse_collision_type] = 2.0
            ret.obj(hit, A.T_CollisionResponse)
        })
        // Shields::AddSuperShield(Point) (HS CustomSuperShield.cpp replaces the native body)
        LuaReg.method(A.M_Shields_AddSuperShield, LuaMethod {
            val s = sh(arg.obj(1)); val ship = s.room!!.ship
            val custom = max(HsSuperShield.value(ship), 5)
            ship.maxSuperShield = max(if (ship.hsSuperShieldSet != 0) ship.maxSuperShield else 0, custom)
            ship.hsSuperShieldSet = 1
            ship.superShield = min(ship.superShield + 1, custom)
            val p = arg.obj(2)
            val px = when (p) { is PointValue -> p.x; is LuaPointMember -> p.x; else -> 0 }
            val py = when (p) { is PointValue -> p.y; is LuaPointMember -> p.y; else -> 0 }
            if (px != 0x7FFFFFFF) {
                val state = shields(s)
                state.startSuperUp(); state.superUpX = px; state.superUpY = py
            }
            0
        })
        // Shields::InstantCharge: every layer the power runs, ENERGY_SHIELD's 5 points, an augment super shield full
        // (1.6.9 0x60bcd0 sets shields.power.first/second only: the charger keeps its value)
        LuaReg.method(A.M_Shields_InstantCharge, LuaMethod {
            val s = sh(arg.obj(1)); val charger = s.rechargeTimer
            s.instantCharge(); s.hsSetRechargeTimer(charger); 0
        })
        LuaReg.method(A.M_Shields_Powered, LuaMethod { ret.bool(HsSystemsLua.effectivePower(sh(arg.obj(1))) > 1) })
        // Shields::SetBaseEllipse(Ellipse): baseShield and ellipseRatio = b / a (Lua reads; the ship's ellipse stays)
        LuaReg.method(A.M_Shields_SetBaseEllipse, LuaMethod {
            val s = sh(arg.obj(1)); val e = arg.obj(2) as? LuaStruct ?: throw LuaError("expected an Ellipse")
            val c = LuaStructs.member(e, A.A_Ellipse_center) as? LuaStruct
            val cx = (c?.values?.get(A.A_Point_x) as? Double) ?: 0.0
            val cy = (c?.values?.get(A.A_Point_y) as? Double) ?: 0.0
            val a = (e.values[A.A_Ellipse_a] as? Double) ?: 0.0
            val b = (e.values[A.A_Ellipse_b] as? Double) ?: 0.0
            val state = shields(s)
            state.ellipse = doubleArrayOf(cx.toInt().toDouble(), cy.toInt().toDouble(), a.toFloat().toDouble(), b.toFloat().toDouble())
            state.ellipseRatio = (b / a).toFloat()
            0
        })
        // Shields::SetHackingLevel (1.6.9 0x60ae60): the hack starts with bExcessChargeHack if a layer was charging
        LuaReg.method(A.M_Shields_SetHackingLevel, LuaMethod {
            val s = sh(arg.obj(1)); val level = arg.int(2)
            if (level > 1 && hackEffect(s) < 2) {
                shields(s).excessChargeHack = s.activeShields < min(s.selectedShieldBars, s.suppliedShieldBars) && s.rechargeTimer > 0f
            }
            s.hsHackLevel = level.coerceIn(0, 2)
            0
        })
    }

    /** Shields::shieldImageName: the ship blueprint's shield image (the path below img/). */
    private fun shieldImageName(s: Shields): String =
        shields(s).shieldImageName ?: s.room!!.ship.type.shieldImage.removePrefix("img/")

    // ------------------------------------------------------------------ OxygenSystem

    private fun oxygen() {
        fun ox(o: Any?) = o as? Oxygen ?: throw LuaError("expected OxygenSystem")
        fun ship(o: Any?) = ox(o).room!!.ship
        LuaReg.num(A.A_OxygenSystem_max_oxygen, NumGet { ox(it).hsMaxOxygen.toDouble() }, NumSet { o, v -> ox(o).hsMaxOxygen = v.toFloat() })
        LuaReg.num(A.A_OxygenSystem_fTotalOxygen, NumGet { ox(it).hsTotalOxygen.toDouble() }, NumSet { o, v -> ox(o).hsTotalOxygen = v.toFloat() })
        LuaReg.bool(A.A_OxygenSystem_bLeakingO2, { HsOxygen.leaking(ship(it)) })
        LuaReg.num(A.A_OxygenSystem_leakModifier, NumGet { HsOxygen.leakModifier(ship(it)).toDouble() })
        LuaReg.method(A.M_OxygenSystem_Functioning, LuaMethod { ret.bool(HsSystemsLua.effectivePower(ox(arg.obj(1))) > 0) })
        // OxygenSystem::GetRefillSpeed (HS OxygenSystem.cpp): the oxygen a room gains this frame, FTL's 0..100 units
        LuaReg.method(A.M_OxygenSystem_GetRefillSpeed, LuaMethod {
            val o = ox(arg.obj(1))
            val sf = speedFactor()
            val r = when {
                underAttack(o) && hackEffect(o) > 1 -> sf * -0.375f
                HsSystemsLua.effectivePower(o) <= 0 -> sf * -0.075f
                else -> {
                    val p = HsSystemsLua.effectivePower(o)
                    val mult = if (HS.cfg().oxygenRefillFix) (if (p == 1) 1 else (p - 1) * 3) else (p - 1) * 3 + 1
                    mult * 0.075f * sf
                }
            }
            ret.num(r)
        })
        fun checkRoom(o: Oxygen, room: Int) {
            val n = o.room!!.ship.rooms.size
            if (room < 0 || room >= n) throw LuaError("Invalid roomId: $room")
        }
        // OxygenSystem::ModifyRoomOxygen(room, amount): no clamp (OnLoop clamps), times the room's environment dilation
        LuaReg.method(A.M_OxygenSystem_ModifyRoomOxygen, LuaMethod {
            val o = ox(arg.obj(1)); val id = arg.int(2); checkRoom(o, id)
            val room = o.room!!.ship.rooms[id]
            val amount = arg.float(3) * HsTemporal.factor(room)
            room.hsSetOxygenUnclamped(room.oxygen + amount / 100f)
            0
        })
        // OxygenSystem::EmptyOxygen(room): HS (CustomCrew.cpp) only lets it run in the hangar
        LuaReg.method(A.M_OxygenSystem_EmptyOxygen, LuaMethod {
            val o = ox(arg.obj(1)); val id = arg.int(2); checkRoom(o, id)
            if (HsLua.game?.currentState is xyz.znix.xftl.hangar.SelectShipState) o.room!!.ship.rooms[id].hsSetOxygenUnclamped(0f)
            0
        })
    }

    // ------------------------------------------------------------------ Cloaking, Battery, Artillery

    private fun subtypes() {
        LuaReg.obj(A.A_CloakingSystem_timer, ObjGet { o -> LuaViews.cached(o as Cloaking, A.A_CloakingSystem_timer) { c -> CloakTimer(c as Cloaking) } })
        // bTurnedOn: writing false ends the cloak; writing true turns it on for the timer's goal (no power, cooldown or
        // sound: the field alone), which a TimerHelper Start then replaces
        LuaReg.bool(A.A_CloakingSystem_bTurnedOn, { (it as Cloaking).active }, { o, v ->
            val c = o as Cloaking
            if (!v) { if (c.active) c.timeRemaining = 0f }
            else if (!c.active) { val goal = if (c.timerMax > 0f) c.timerMax else 0.5f; c.timerMax = goal; c.timeRemaining = goal }
        })
        LuaReg.str(A.A_CloakingSystem_soundeffect, StrGet { "" })
        LuaReg.bool(A.A_BatterySystem_bTurnedOn, { (it as BackupBattery).timeRemaining != null })
        LuaReg.str(A.A_BatterySystem_soundeffect, StrGet { "" })
        LuaReg.bool(A.A_ArtillerySystem_bCloaked, { o -> (o as Artillery).room?.ship?.cloaking?.active == true })
        LuaReg.obj(A.A_ArtillerySystem_target, ObjGet { o ->
            val a = o as Artillery; val ship = a.room?.ship ?: return@ObjGet null
            // A system added during a fight has no target until the next ship is set (oracle hw-g-ar).
            ship.sys.getEnemyOf(ship)?.takeIf { it !== a.hsAddedAgainst }
        })
    }

    // ------------------------------------------------------------------ AugmentSuperShield

    private fun superShield() {
        fun def(o: Any?): HsSuperShield.LuaDef = when (o) {
            is HsSuperShield.LuaDef -> o
            // slice C's AugmentDefinition.superShield view (an opaque member of its AugmentDefinition)
            is LuaOpaque -> HsSuperShield.luaDef((o.owner as? xyz.znix.xftl.hs.ship.HsAugments.AugmentDefinition
                ?: throw LuaError("expected AugmentSuperShield")).name)
            else -> throw LuaError("expected AugmentSuperShield")
        }
        if (LuaReg.objGet[A.A_AugmentDefinition_superShield] == null)
            LuaReg.obj(A.A_AugmentDefinition_superShield, ObjGet { o ->
                HsSuperShield.luaDef((o as xyz.znix.xftl.hs.ship.HsAugments.AugmentDefinition).name)
            })
        LuaReg.num(A.A_AugmentSuperShield_value, NumGet { def(it).value.toDouble() })
        LuaReg.num(A.A_AugmentSuperShield_add, NumGet { def(it).add.toDouble() })
        LuaReg.bool(A.A_AugmentSuperShield_customRender, { def(it).customRender })
        LuaReg.bool(A.A_AugmentSuperShield_present, { def(it).present })
        LuaReg.obj(A.A_AugmentSuperShield_shieldColor, ObjGet { def(it).color })
        LuaReg.obj(A.A_AugmentSuperShield_shieldTexture, ObjGet { o ->
            val dd = def(o)
            LuaViews.cached(dd, A.A_AugmentSuperShield_shieldTexture) { x -> LuaStdString({ (x as HsSuperShield.LuaDef).texture[0] }, { v -> HsSuperShield.assignTexture(x as HsSuperShield.LuaDef, v) }) }
        })
        // std::string methods on LuaStdString. SWIG emits the std::string class once per module (ids 2..6, 2073..,
        // 2151.., 2163..); the T_string metatable dispatches to the last copy (hs_members: size 2163 .. assign 2168).
        fun str(o: Any?) = o as? LuaStdString ?: throw LuaError("expected std::string")
        val cStr = LuaMethod { ret.str(str(arg.obj(1)).value) }
        val size = LuaMethod { ret.num(str(arg.obj(1)).value.encodeToByteArray().size) }
        val empty = LuaMethod { ret.bool(str(arg.obj(1)).value.isEmpty()) }
        val assign = LuaMethod { str(arg.obj(1)).assign(arg.str(2)); 0 }
        for (id in intArrayOf(A.M_string_c_str, A.M_string_c_str_, A.M_string_c_str__, A.M_string_c_str___,
            A.M_string_data, A.M_string_data_, A.M_string_data__, A.M_string_data___)) LuaReg.method(id, cStr)
        for (id in intArrayOf(A.M_string_size, A.M_string_size_, A.M_string_size__, A.M_string_size___,
            A.M_string_length, A.M_string_length_, A.M_string_length__, A.M_string_length___)) LuaReg.method(id, size)
        for (id in intArrayOf(A.M_string_empty, A.M_string_empty_, A.M_string_empty__, A.M_string_empty___)) LuaReg.method(id, empty)
        for (id in intArrayOf(A.M_string_assign, A.M_string_assign_, A.M_string_assign__, A.M_string_assign___)) LuaReg.method(id, assign)
    }
}
