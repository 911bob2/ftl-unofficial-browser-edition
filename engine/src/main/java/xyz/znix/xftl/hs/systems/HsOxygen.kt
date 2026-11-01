// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/CustomSystems.cpp (CALCULATE_LEAK_MODIFIER,
// OxygenSystem::UpdateAirlock / UpdateBreach / ComputeAirLoss rewrites, the ShipManager::UpdateEnvironment rewrite) and
// src/game/Ship/CustomCrew.cpp (UpdateEnvironment: OXYGEN_CHANGE_SPEED). FTL-Hyperspace is GPL-3.0-or-later; this
// engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.game.LoopHandle
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.stat.CrewStat
import xyz.znix.xftl.hs.crew.HsCrewMember
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaPush
import xyz.znix.xftl.layout.Room
import kotlin.math.pow
import xyz.znix.xftl.hs.lua.LuaApi as A

/**
 * The air loss of a ship's environment as Hyperspace runs it (ShipManager::UpdateEnvironment), in the engine's units:
 * FTL's 0..100 oxygen per speed factor unit (16 per second) is 0.16 of the engine's 0..1 oxygen per second.
 *
 * - each crewmember's OXYGEN_CHANGE_SPEED (CustomCrew.cpp, before the rest): ComputeAirLoss(room, -speed, silent);
 * - per room: open airlocks (a room above 10 is emptied at once, or with a negative leak modifier one below 90 filled),
 *   ComputeAirLoss(airlocks x modifier); breaches ComputeAirLoss(breaches x 0.5 x modifier); IS_ANAEROBIC crew
 *   ComputeAirLoss(count x 0.5), not scaled by the modifier;
 * - CALCULATE_LEAK_MODIFIER(ship, 1) -> modifier, fired once per frame at OxygenSystem::OnLoop (MV's atmosphere.lua
 *   returns -1 at beacons in an atmosphere: breaches and airlocks then add air).
 *
 * ComputeAirLoss leaves out rooms already at 0 (a loss) or 100 (a gain) and does not clamp, so a draining room ends
 * the frame up to one frame's loss below 0 (FTL shows e.g. -1.05); the next refill (OxygenSystem::OnLoop) clamps it.
 */
object HsOxygen {
    /** Units: FTL oxygen per speed factor -> engine oxygen per second. */
    private const val PER_SECOND = 0.16f

    private val modifiers = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, Float>())

    /** OxygenSystem::OnLoop (priority -100): CALCULATE_LEAK_MODIFIER(ship, 1); ships without an oxygen system keep 1. */
    @JvmStatic
    fun beginFrame(ship: Ship) {
        var mod = 1f
        if (HS.luaActive && ship.oxygen != null && HsLua.hasInt[A.EV_CALCULATE_LEAK_MODIFIER]) {
            LuaPush.obj(ship, A.T_ShipManager)
            LuaJs.pushnumber(mod.toDouble())
            LuaJs.fireChain(A.EV_CALCULATE_LEAK_MODIFIER, 2, 1)
            if (LuaJs.isnumber(-1)) mod = LuaJs.tonumber(-1).toFloat()
            LuaJs.pop(2)
        }
        if (mod == 1f) modifiers.remove(ship) else modifiers[ship] = mod
    }

    @JvmStatic
    fun leakModifier(ship: Ship): Float = modifiers[ship] ?: 1f

    private val leakingShips = xyz.znix.xftl.hs.lua.HsRunMaps.run(java.util.IdentityHashMap<Ship, Boolean>())

    /** OxygenSystem::bLeakingO2: a room was leaking in the last UpdateEnvironment (OnLoop clears it). */
    @JvmStatic
    fun leaking(ship: Ship): Boolean = leakingShips[ship] == true

    /** The UpdateEnvironment drains (see the class comment); [distances] gives the open-door depths from a room. */
    @JvmStatic
    fun update(ship: Ship, dt: Float, distances: (Room) -> Map<Room, Int>) {
        if (ship.oxygen == null && !HS.cfg().oxygenWithoutSystem) return
        var leaking = false

        // CustomCrew.cpp: every living crewmember of a race with a non-zero OXYGEN_CHANGE_SPEED; the dummy oxygen system
        // of oxygenWithoutSystem passes the hook's system check too (oracle hg-o2: the orchid's room drains slower)
        run {
            for (crew in ship.crew.toList()) {
                if (crew.health <= 0f) continue
                val def = raceDef(crew) ?: continue
                val speed = xyz.znix.xftl.hs.crew.HsCrew.ex(crew).calculateStat(CrewStat.OXYGEN_CHANGE_SPEED, def)
                if (speed != 0f) computeAirLoss(crew.room, -speed, dt, distances)
            }
        }

        val mod = leakModifier(ship)
        for (room in ship.rooms) {
            val airlocks = room.doors.count { it.isAirlock && it.open }
            val breaches = room.breaches.count { it != null }
            if (airlocks > 0) {
                val drain = mod > 0f && room.oxygen > 0.1f
                val gain = mod < 0f && room.oxygen < 0.9f
                if (drain || gain) {
                    room.hsSetOxygenUnclamped(if (mod > 0f) 0f else 1f)
                    ship.airLossSound.play()
                }
                if (computeAirLoss(room, airlocks * mod, dt, distances)) leaking = true
            }
            if (breaches > 0 && computeAirLoss(room, breaches * 0.5f * mod, dt, distances)) leaking = true
            val anaerobic = room.crew.count { isAnaerobic(it) }
            if (anaerobic > 0) computeAirLoss(room, anaerobic * 0.5f, dt, distances)
        }

        if (leaking) leakingShips[ship] = true else leakingShips.remove(ship)
        if (leaking) {
            val sound: LoopHandle = ship.sys.sounds.getLoop("airLeak")
            sound.continueLoopPlayerOnly(ship)
        }
    }

    /**
     * The race definition HS reads a crew member's stats from: an HS crew member's, or a crew drone body's
     * (CrewDrone::_HS_IsAnaerobic and the UpdateEnvironment loop take drones too: oracle sweep-crew4-droneai, MV's
     * roomba empties the room it stands in); null for the engine's own crew classes.
     */
    private fun raceDef(crew: AbstractCrew): xyz.znix.xftl.hs.crew.CrewDefinition? = when (crew) {
        is HsCrewMember -> crew.def
        is xyz.znix.xftl.drones.AbstractIndoorsDrone.Pawn -> xyz.znix.xftl.hs.crew.HsCrew.ex(crew).definition()
        else -> null
    }

    /** CrewMember::IsAnaerobic as HS answers it (IS_ANAEROBIC); the engine's own crew classes otherwise. */
    private fun isAnaerobic(crew: AbstractCrew): Boolean {
        val def = raceDef(crew) ?: return crew is xyz.znix.xftl.crew.CrewAnaerobic
        return xyz.znix.xftl.hs.crew.HsCrew.ex(crew).calculateBool(CrewStat.IS_ANAEROBIC, def)
    }

    /**
     * OxygenSystem::ComputeAirLoss(room, base): every room connected through open doors at depth d loses
     * 0.75^d x base x speed factor (x the start room's dilation); rooms at 0 (base > 0) or 100 (base < 0) are left out.
     * True if a room was leaking (the air leak sound; above 1 for a loss, below 99 for a gain).
     */
    private fun computeAirLoss(start: Room, base: Float, dt: Float, distances: (Room) -> Map<Room, Int>): Boolean {
        if (base == 0f) return false
        val rate = base * PER_SECOND * HsTemporal.factor(start)
        var leaking = false
        for ((room, depth) in distances(start)) {
            val o = room.oxygen
            if (base > 0f && o <= 0f) continue
            if (base < 0f && o >= 1f) continue
            if (if (base > 0f) o > 0.01f else o < 0.99f) leaking = true
            room.hsSetOxygenUnclamped(o - 0.75f.pow(depth) * rate * dt)
        }
        return leaking
    }
}
