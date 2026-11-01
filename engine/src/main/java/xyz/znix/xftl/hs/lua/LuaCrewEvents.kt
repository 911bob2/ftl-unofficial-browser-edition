// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (src/game/Ship/StatBoost.cpp CalculateStat,
// src/game/Gameplay/World/CrewAbilities.cpp ActivatedPower / ActivatedPowerResource, src/game/Ship/CustomCrew.cpp
// CrewBox::GetSelected, CrewMember::Clone; CrewMember_Extend.cpp; lua/RenderEvents.cpp), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * The crew stat and crew power internal events. The stat code (hs.crew, hs.stat) calls these only while scripts
 * run (`HS.luaActive`); each returns at once when no callback is registered. Arguments are pushed as HS pushes
 * them (SWIG_NewPointerObj with the static type, lua_pushinteger for enums/states, lua_pushnumber for floats) and
 * rewritten arguments are read back from the same stack slots.
 */
object LuaCrewEvents {
    /** CALCULATE_STAT_PRE/POST results: the value and boolean after the callbacks (rewritten or not). */
    @JvmField var value = 0f
    @JvmField var bool = false

    /**
     * CrewMember_Extend::CalculateStat: CALCULATE_STAT_PRE / _POST(crew, stat, def, value, boolValue). Leaves the
     * results in [value] / [bool] (`lua_isnumber(-2)`, `lua_isboolean(-1) && isBool`); returns the chain's preempt
     * (PRE: a PREEMPT skips the XML boosts and CALCULATE_STAT_POST; POST ignores it).
     */
    @JvmStatic
    fun calculateStat(post: Boolean, crew: Any, stat: Int, def: Any?, finalStat: Float, boolValue: Boolean, isBool: Boolean): Boolean {
        value = finalStat; bool = boolValue
        val ev = if (post) A.EV_CALCULATE_STAT_POST else A.EV_CALCULATE_STAT_PRE
        if (!HsLua.hasInt[ev]) return false
        ret.obj(crew, A.T_CrewMember)
        LuaJs.pushinteger(stat.toDouble())
        ret.obj(def, A.T_CrewDefinition)
        LuaJs.pushnumber(finalStat.toDouble())
        LuaJs.pushboolean(isBool && boolValue)
        val preempt = LuaJs.fireChain(ev, 5, 2)
        if (LuaJs.isnumber(-2)) value = LuaJs.tonumber(-2).toFloat()
        if (LuaJs.type(-1) == LUA_TBOOLEAN && isBool) bool = LuaJs.toboolean(-1)
        LuaJs.pop(5)
        return preempt
    }

    /** ActivatedPower(Resource)::PowerReq: POWER_REQ(power, req, retValue) -> the state (only an integer result counts). */
    @JvmStatic
    fun powerReq(power: Any, resource: Boolean, req: Any, state: Int): Int {
        if (!HsLua.hasInt[A.EV_POWER_REQ]) return state
        ret.obj(power, if (resource) A.T_ActivatedPowerResource else A.T_ActivatedPower)
        ret.obj(req, A.T_ActivatedPowerRequirements)
        LuaJs.pushinteger(state.toDouble())
        LuaJs.fireChain(A.EV_POWER_REQ, 3, 1)
        val r = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else state
        LuaJs.pop(3)
        return r
    }

    /** ActivatedPower::PowerReady: POWER_READY(power, retValue) -> the state (only an integer result counts). */
    @JvmStatic
    fun powerReady(power: Any, state: Int): Int {
        if (!HsLua.hasInt[A.EV_POWER_READY]) return state
        ret.obj(power, A.T_ActivatedPower)
        LuaJs.pushinteger(state.toDouble())
        LuaJs.fireChain(A.EV_POWER_READY, 2, 1)
        val r = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else state
        LuaJs.pop(2)
        return r
    }

    /**
     * The one-argument power events: PREPARE_POWER, POWER_ON_UPDATE, POWER_ENABLE_INIT (ActivatedPower) and
     * POWER_RESOURCE_ON_UPDATE, POWER_RESOURCE_ENABLE_INIT (ActivatedPowerResource). True = pre-empted.
     */
    @JvmStatic
    fun power(ev: Int, power: Any, resource: Boolean): Boolean {
        if (!HsLua.hasInt[ev]) return false
        ret.obj(power, if (resource) A.T_ActivatedPowerResource else A.T_ActivatedPower)
        val preempt = LuaJs.fireChain(ev, 1, 0)
        LuaJs.pop(1)
        return preempt
    }

    @JvmStatic fun preparePower(power: Any) = power(A.EV_PREPARE_POWER, power, false)
    @JvmStatic fun powerOnUpdate(power: Any) = power(A.EV_POWER_ON_UPDATE, power, false)
    @JvmStatic fun powerEnableInit(power: Any) = power(A.EV_POWER_ENABLE_INIT, power, false)
    @JvmStatic fun resourceOnUpdate(power: Any) = power(A.EV_POWER_RESOURCE_ON_UPDATE, power, true)
    @JvmStatic fun resourceEnableInit(power: Any) = power(A.EV_POWER_RESOURCE_ENABLE_INIT, power, true)

    /** ActivatedPower::ActivatePower: ACTIVATE_POWER(power, ship); ship = the power's ship (nil without an enemy). */
    @JvmStatic
    fun activatePower(power: Any, ship: Any?): Boolean {
        if (!HsLua.hasInt[A.EV_ACTIVATE_POWER]) return false
        ret.obj(power, A.T_ActivatedPower)
        ret.obj(ship, A.T_ShipManager)
        val preempt = LuaJs.fireChain(A.EV_ACTIVATE_POWER, 2, 0)
        LuaJs.pop(2)
        return preempt
    }

    /** ActivatedPower::CancelPower: CANCEL_POWER(power, clearAnim). */
    @JvmStatic
    fun cancelPower(power: Any, clearAnim: Boolean): Boolean {
        if (!HsLua.hasInt[A.EV_CANCEL_POWER]) return false
        ret.obj(power, A.T_ActivatedPower)
        LuaJs.pushboolean(clearAnim)
        val preempt = LuaJs.fireChain(A.EV_CANCEL_POWER, 2, 0)
        LuaJs.pop(2)
        return preempt
    }

    /**
     * CrewBox::GetSelected: POWER_TOOLTIP(power, state), a plain event with two results; returns the tooltip
     * (`lua_tostring` of the first result) when a callback returned two values, else null (the default tooltip).
     * HS pushes the CrewBox itself typed as ActivatedPower (`this`), so a script reading fields of that argument
     * reads the crew box's memory; we pass the power the button belongs to. The second result (skipAppend) is
     * assigned to an inner variable in HS and has no effect.
     */
    @JvmStatic
    fun powerTooltip(power: Any, state: Int): String? {
        if (!HsLua.hasInt[A.EV_POWER_TOOLTIP]) return null
        ret.obj(power, A.T_ActivatedPower)
        LuaJs.pushinteger(state.toDouble())
        val n = LuaJs.fire(A.EV_POWER_TOOLTIP, 2, 2)
        if (n == 2) {
            val tip = LuaJs.tostring(-2)
            LuaJs.pop(4)
            return tip ?: ""
        }
        LuaJs.pop(2 + n)
        return null
    }

    /**
     * CONSTRUCT_CREWMEMBER(crew) (CrewMember_Extend.cpp, CrewMember::constructor priority 900, after the vanilla
     * constructor): every crew member and crew drone body when it is made, in a run or loaded from a save.
     */
    @JvmStatic
    fun constructCrewMember(crew: Any) {
        if (!HsLua.hasInt[A.EV_CONSTRUCT_CREWMEMBER]) return
        ret.obj(crew, A.T_CrewMember)
        LuaJs.fire(A.EV_CONSTRUCT_CREWMEMBER, 1, 0)
        LuaJs.pop(1)
    }

    /** CREW_CLONE(crew) (CustomCrew.cpp, CrewMember::Clone priority -9999): before the clone's skill loss and the clone. */
    @JvmStatic
    fun crewClone(crew: Any) {
        if (!HsLua.hasInt[A.EV_CREW_CLONE]) return
        ret.obj(crew, A.T_CrewMember)
        LuaJs.fire(A.EV_CREW_CLONE, 1, 0)
        LuaJs.pop(1)
    }

    /**
     * Render event CREW_MEMBER_HEALTH(crew) (RenderEvents.cpp, CrewMember::OnRenderHealth): around the health bar of
     * every crew member drawn, shown or not; a pre callback returning >= 0 lets the bar draw. Pair with [crewHealthPost].
     */
    @JvmStatic
    fun crewHealthPre(crew: Any): Int =
        xyz.znix.xftl.hs.render.HsRenderEvents.preObj(A.RE_CREW_MEMBER_HEALTH, crew, A.T_CrewMember)

    @JvmStatic
    fun crewHealthPost(idx: Int) = xyz.znix.xftl.hs.render.HsRenderEvents.postObj(A.RE_CREW_MEMBER_HEALTH, idx)

    private const val LUA_TBOOLEAN = 1
}
