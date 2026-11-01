// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 src/ files named below (lua/InternalEvents.cpp,
// game/Gameplay/World/CustomEvents.cpp, game/Ship/CustomAugments.cpp), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.events.HsReq
import xyz.znix.xftl.hs.ship.HsAugments
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.sector.Beacon
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * WP1: the internal events whose hooks sit on engine rules that MV scripts reach: the equipment and augment chains
 * (HAS_EQUIPMENT, HAS_AUGMENTATION, GET_AUGMENTATION_VALUE), the hazard events (DANGEROUS_ENVIRONMENT,
 * GET_BEACON_HAZARD, GET_HAZARD_FLASH) and SpaceManager::SwitchPlanet's result. Each fire site is gated on
 * `HS.luaActive` and on a registered callback, so nothing runs without scripts.
 */
object LuaAdaptersWP1 {
    @JvmStatic
    fun install() {
        // The chains run on the result of the engine's own rule (HsReq.hasEquipment, HsAugments.count / value).
        HsReq.luaHasEquipment = { ship, name, value -> hasEquipment(ship, name, value) }
        HsAugments.hasAugmentationHook = { ship, name, count -> hasAugmentation(ship, name, count) }
        HsAugments.augmentationValueHook = { ship, name, value -> augmentationValue(ship, name, value) }

        // ShipObject::HasAugmentation(name) is the hooked rule (hidden augments, augment functions, HAS_AUGMENTATION).
        LuaReg.method(A.M_ShipObject_HasAugmentation, LuaMethod {
            val ship = arg.obj(1) as Ship
            val name = arg.str(2) ?: ""
            ret.num(if (HS.active) HsAugments.count(ship, name) else ship.augments.count { a -> a.name == name })
        })

        // SpaceManager::DangerousEnvironment(): the current scene's rule through the DANGEROUS_ENVIRONMENT chain.
        LuaReg.method(A.M_SpaceManager_DangerousEnvironment, LuaMethod {
            val game = LuaAdapters.inGame()
            ret.bool(game != null && dangerousEnvironment(game.currentBeacon.isEnvironmentDangerous))
        })

        // SpaceManager::SwitchPlanet(name) (0x418280): the current beacon's planet (AbstractEnvironment.hsSwitchPlanet;
        // MV's base-star-like.lua hides it with "NONE"). Returns the new planet's ImageDesc (zeros for none).
        LuaReg.method(A.M_SpaceManager_SwitchPlanet, LuaMethod {
            val game = LuaAdapters.inGame()
            val d = LuaStructs.create(A.T_ImageDesc)
            val v = if (game != null) game.currentBeacon.getEnvironment(game).hsSwitchPlanet(arg.str(2) ?: "") else IntArray(5)
            d.values[A.A_ImageDesc_w] = v[0].toDouble()
            d.values[A.A_ImageDesc_h] = v[1].toDouble()
            d.values[A.A_ImageDesc_x] = v[2].toDouble()
            d.values[A.A_ImageDesc_y] = v[3].toDouble()
            d.values[A.A_ImageDesc_rot] = v[4].toDouble()
            ret.obj(d, A.T_ImageDesc)
        })
    }

    private fun pushShip(ship: Ship?) { ret.obj(ship, A.T_ShipManager) }

    /** ShipObject::HasEquipment at priority -1000 (CustomEvents.cpp): HAS_EQUIPMENT(ship, name, value) -> value. */
    private fun hasEquipment(ship: Ship?, name: String, value: Int): Int {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_HAS_EQUIPMENT]) return value
        pushShip(ship)
        LuaJs.pushstring(name)
        LuaJs.pushinteger(value.toDouble())
        LuaJs.fireChain(A.EV_HAS_EQUIPMENT, 3, 1)
        // lua_isinteger: a float result (even 2.0) leaves the value alone
        val r = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else value
        LuaJs.pop(3)
        return r
    }

    /** ShipObject::HasAugmentation (CustomAugments.cpp): HAS_AUGMENTATION(ship, name, count) -> count. */
    private fun hasAugmentation(ship: Ship, name: String, count: Int): Int {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_HAS_AUGMENTATION]) return count
        pushShip(ship)
        LuaJs.pushstring(name)
        LuaJs.pushinteger(count.toDouble())
        LuaJs.fireChain(A.EV_HAS_AUGMENTATION, 3, 1)
        val r = if (LuaJs.isinteger(-1)) LuaJs.tointeger(-1).toInt() else count
        LuaJs.pop(3)
        return r
    }

    /** ShipObject::GetAugmentationValue at priority 0: GET_AUGMENTATION_VALUE(ship, name, value) -> value (any number). */
    private fun augmentationValue(ship: Ship, name: String, value: Float): Float {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_AUGMENTATION_VALUE]) return value
        pushShip(ship)
        LuaJs.pushstring(name)
        LuaJs.pushnumber(value.toDouble())
        LuaJs.fireChain(A.EV_GET_AUGMENTATION_VALUE, 3, 1)
        val r = if (LuaJs.isnumber(-1)) LuaJs.tonumber(-1).toFloat() else value
        LuaJs.pop(3)
        return r
    }

    /**
     * SpaceManager::DangerousEnvironment (InternalEvents.cpp): DANGEROUS_ENVIRONMENT(res) -> res. The first callback
     * that returns a value decides (false included); the engine folds the three callers (PauseLoop, CheckMusic and
     * HostileEnvironment) into InGameState.isInDanger.
     */
    @JvmStatic
    fun dangerousEnvironment(res: Boolean): Boolean {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_DANGEROUS_ENVIRONMENT]) return res
        LuaJs.pushboolean(res)
        val n = LuaJs.fire(A.EV_DANGEROUS_ENVIRONMENT, 1, 1)
        if (n == 1) {
            val r = LuaJs.toboolean(-1)
            LuaJs.pop(2)
            return r
        }
        LuaJs.pop(1)
        return res
    }

    /**
     * StarMap::GetLocationText (priority -100): GET_BEACON_HAZARD(location) -> label. A callback that returns a string
     * makes the beacon's hazard line (the one the map shows for a revealed or scanned beacon) that text: HS sets
     * `location.event.environment = 1` and answers `map_asteroid_loc` with it. Null when no callback returned one.
     */
    @JvmStatic
    fun beaconHazardText(beacon: Beacon): String? {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_BEACON_HAZARD]) return null
        ret.obj(beacon, A.T_Location)
        val n = LuaJs.fire(A.EV_GET_BEACON_HAZARD, 1, 1)
        if (n == 1) {
            val s = if (LuaJs.isstring(-1)) LuaJs.tostring(-1) else null
            LuaJs.pop(2)
            return s
        }
        LuaJs.pop(1)
        return null
    }

    /**
     * StarMap::OnRender: GET_BEACON_HAZARD(location) for every beacon; one whose first callback returns anything (nil
     * excepted) is drawn as an asteroid beacon (`environment = 1`): the map's hazard marker.
     */
    @JvmStatic
    fun beaconHazardMarker(beacon: Beacon): Boolean {
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_BEACON_HAZARD]) return false
        ret.obj(beacon, A.T_Location)
        val n = LuaJs.fire(A.EV_GET_BEACON_HAZARD, 1, 1)
        LuaJs.pop(if (n == 1) 2 else 1)
        return n == 1
    }

    /** The red, green, blue and alpha the last [flashOpacity] callback returned (null: the flash keeps its colour). */
    @JvmField var flashColour: Colour? = null

    /**
     * SpaceManager::GetFlashOpacity: GET_HAZARD_FLASH(opacity) -> (r, g, b, a). A callback that returns four values
     * replaces the opacity with its alpha and, when that is above zero, the flare's colour with its red, green and
     * blue ([flashColour]); anything else leaves the opacity.
     */
    @JvmStatic
    fun flashOpacity(opacity: Float): Float {
        flashColour = null
        if (!HS.luaActive || !HsLua.hasInt[A.EV_GET_HAZARD_FLASH]) return opacity
        LuaJs.pushnumber(opacity.toDouble())
        val n = LuaJs.fire(A.EV_GET_HAZARD_FLASH, 1, 4)
        if (n == 4) {
            val r = LuaJs.tonumber(-4).toFloat()
            val g = LuaJs.tonumber(-3).toFloat()
            val b = LuaJs.tonumber(-2).toFloat()
            val a = LuaJs.tonumber(-1).toFloat()
            LuaJs.pop(5)
            if (a > 0f) flashColour = Colour(r, g, b, a)
            return a
        }
        LuaJs.pop(1)
        return opacity
    }
}
