// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomShipGenerator.cpp (the
// ShipGenerator::CreateShip hook, priority -100: GENERATOR_CREATE_SHIP and GENERATOR_CREATE_SHIP_POST)
// (the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.ShipBlueprint
import xyz.znix.xftl.hs.lua.HsLua
import xyz.znix.xftl.hs.lua.LuaEvents
import xyz.znix.xftl.hs.lua.LuaJs
import xyz.znix.xftl.hs.lua.LuaOpaque
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret
import xyz.znix.xftl.shipgen.EnemyShipSpec

/**
 * `ShipGenerator::CreateShip` (HS hook, only while the `<shipGenerators>` node enables the custom generator): the
 * chain event GENERATOR_CREATE_SHIP(name, sector, ShipEvent, ShipBlueprint, ShipManager = nil) can replace the
 * sector, the ShipEvent or the blueprint, or hand back a whole ShipManager (then the generator does not run); then
 * GENERATOR_CREATE_SHIP_POST(name, sector, ShipEvent, ShipBlueprint, ShipManager) after the ship exists. The ShipEvent
 * is an opaque pointer (HS exposes no members of it); the blueprint is the picked one (a pointer into the blueprint
 * manager, not a copy).
 */
object HsGeneratorEvents {
    private const val T_SHIP_EVENT_PTR = 291

    class Result(@JvmField val sector: Int, @JvmField val blueprint: ShipBlueprint, @JvmField val ship: Ship?)

    private fun pushArgs(spec: EnemyShipSpec, sector: Int, event: Any, bp: ShipBlueprint, ship: Ship?) {
        ret.str(spec.autoBlueprintName)
        LuaJs.pushinteger(sector.toDouble())
        ret.obj(LuaOpaque(T_SHIP_EVENT_PTR, event), T_SHIP_EVENT_PTR)
        ret.obj(bp, A.T_ShipBlueprint)
        ret.obj(ship, A.T_ShipManager)
    }

    /** The ShipEvent pointer of the ship being generated (the same one for both events). */
    private var event: Any = Any()

    /** Whether the five values of [pre] are still on the Lua stack for [post] (HS reuses the one stack for both). */
    private var pending = false

    /**
     * The pre event. The five arguments stay on the stack for [post], as in HS: its chain-callback helper expects
     * `nRet + 1` results (the last is the chain value) and replaces the last `nRet` arguments from the top, so a callback that
     * returns just the four values it was handed leaves them one place off (`sector, ShipEvent, ShipBlueprint` are
     * shifted down and the sector is not replaced); the post callback sees that shifted stack with the ship at the end.
     */
    @JvmStatic
    fun pre(spec: EnemyShipSpec, sector: Int, bp: ShipBlueprint, seed: Int): Result {
        event = Any()
        pending = false
        if (!HsLua.hasInt[A.EV_GENERATOR_CREATE_SHIP] && !HsLua.hasInt[A.EV_GENERATOR_CREATE_SHIP_POST]) return Result(sector, bp, null)
        val prev = LuaEvents.generation
        LuaEvents.generation = true
        try {
            pushArgs(spec, sector, event, bp, null)
            pending = true
            if (!HsLua.hasInt[A.EV_GENERATOR_CREATE_SHIP]) return Result(sector, bp, null)
            LuaJs.fireChain(A.EV_GENERATOR_CREATE_SHIP, 5, 4)
            // "pre-empt" is whether the last result is a ShipManager; otherwise the other three results are read
            val returned = if (LuaJs.type(-1) > 0 && arg.type(-1) == A.T_ShipManager) arg.obj(-1) as? Ship else null
            var outSector = sector
            var outBp = bp
            if (returned == null) {
                if (LuaJs.isinteger(-4)) outSector = LuaJs.tointeger(-4).toInt()
                (arg.obj(-2) as? ShipBlueprint)?.let { outBp = it }
            }
            return Result(outSector, outBp, returned)
        } finally {
            LuaEvents.generation = prev
        }
    }

    /** The post event: the ship replaces the last stack value, the callbacks run, and the five values go. */
    @JvmStatic
    fun post(spec: EnemyShipSpec, sector: Int, bp: ShipBlueprint, seed: Int, ship: Ship) {
        if (!pending) return
        pending = false
        val prev = LuaEvents.generation
        LuaEvents.generation = true
        try {
            LuaJs.pop(1)
            ret.obj(ship, A.T_ShipManager)
            if (HsLua.hasInt[A.EV_GENERATOR_CREATE_SHIP_POST]) LuaJs.fireChain(A.EV_GENERATOR_CREATE_SHIP_POST, 5, 0)
            LuaJs.pop(5)
        } finally {
            LuaEvents.generation = prev
        }
    }
}
