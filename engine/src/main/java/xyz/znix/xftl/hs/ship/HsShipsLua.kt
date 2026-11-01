package xyz.znix.xftl.hs.ship

import xyz.znix.xftl.hs.lua.LuaError
import xyz.znix.xftl.hs.lua.LuaMethod
import xyz.znix.xftl.hs.lua.ListView
import xyz.znix.xftl.hs.lua.LuaReg
import xyz.znix.xftl.hs.lua.LuaTyped
import xyz.znix.xftl.hs.lua.LuaViews
import xyz.znix.xftl.hs.lua.NumGet
import xyz.znix.xftl.hs.lua.NumSet
import xyz.znix.xftl.hs.lua.ObjGet
import xyz.znix.xftl.hs.lua.StrGet
import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/**
 * Lua members of the ships workstream (lua.md adapter convention; installed from LuaAdaptersMore.install):
 * `Hyperspace.CustomShipSelect.GetInstance()` with GetDefinition / GetDefaultDefinition and the definition's fields,
 * its `roomDefs` map (`std::unordered_map<int, RoomDefinition*>`), and `Hyperspace.App.menu.shipBuilder.bOpen`.
 */
object HsShipsLua {
    object Instance : LuaTyped { override val luaType get() = A.T_CustomShipSelect }
    object ShipBuilder : LuaTyped { override val luaType get() = A.T_ShipBuilder }

    class RoomDefs(@JvmField val def: CustomShipDefinition) : LuaTyped { override val luaType get() = A.T_unordered_map_int_RoomDefinition }

    @JvmStatic
    fun install() {
        LuaReg.method(A.M_CustomShipSelect_GetInstance, LuaMethod { ret.obj(Instance, A.T_CustomShipSelect) })
        // GetDefinition(name): the ship's definition, or the default one (CustomShipSelect::GetDefinition).
        LuaReg.method(A.M_CustomShipSelect_GetDefinition, LuaMethod { ret.obj(HsShips.def(arg.str(2) ?: ""), A.T_CustomShipDefinition) })
        LuaReg.method(A.M_CustomShipSelect_GetDefaultDefinition, LuaMethod { ret.obj(HsShips.defaultDef, A.T_CustomShipDefinition) })
        LuaReg.method(A.M_CustomShipSelect_GetMaxPages, LuaMethod { ret.num(HsHangar.maxPage()) })
        LuaReg.method(A.M_CustomShipSelect_IsOpen, LuaMethod {
            ret.bool(xyz.znix.xftl.hs.lua.HsLua.game?.currentState is xyz.znix.xftl.hangar.SelectShipState)
        })

        fun d(o: Any?) = o as CustomShipDefinition
        LuaReg.str(A.A_CustomShipDefinition_name, StrGet { d(it).name })
        LuaReg.str(A.A_CustomShipDefinition_shipGenerator, StrGet { d(it).shipGenerator })
        LuaReg.bool(A.A_CustomShipDefinition_noJump, { d(it).noJump })
        LuaReg.bool(A.A_CustomShipDefinition_noFuelStalemate, { d(it).noFuelStalemate })
        LuaReg.num(A.A_CustomShipDefinition_hpCap, NumGet { d(it).hpCap.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_startingFuel, NumGet { d(it).startingFuel.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_startingScrap, NumGet { d(it).startingScrap.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_crewLimit, NumGet { d(it).crewLimit.toDouble() }, xyz.znix.xftl.hs.lua.NumSet { o, v -> d(o).crewLimit = v.toInt() }) // writable as in HS (round 14 UI)
        LuaReg.num(A.A_CustomShipDefinition_systemLimit, NumGet { d(it).systemLimit.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_subsystemLimit, NumGet { d(it).subsystemLimit.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_maxReactorLevel, NumGet { d(it).maxReactorLevel.toDouble() })
        LuaReg.num(A.A_CustomShipDefinition_reactorPriceIncrement, NumGet { d(it).reactorPriceIncrement.toDouble() })
        LuaReg.obj(A.A_CustomShipDefinition_roomDefs, ObjGet { LuaViews.cached(it!!, A.A_CustomShipDefinition_roomDefs) { o -> RoomDefs(o as CustomShipDefinition) } })

        fun m(): Map<Int, RoomDefinition> = (arg.obj(1) as RoomDefs).def.roomDefs
        LuaReg.method(A.M_unordered_map_int_RoomDefinition_has_key, LuaMethod { ret.bool(m().containsKey(arg.int(2))) })
        LuaReg.method(A.M_unordered_map_int_RoomDefinition___getitem, LuaMethod { ret.obj(m()[arg.int(2)] ?: throw LuaError("SWIG_IndexError:key not found"), A.T_RoomDefinition) })
        LuaReg.method(A.M_unordered_map_int_RoomDefinition_get, LuaMethod { ret.obj(m()[arg.int(2)] ?: throw LuaError("SWIG_IndexError:key not found"), A.T_RoomDefinition) })
        LuaReg.method(A.M_unordered_map_int_RoomDefinition_keys, LuaMethod {
            ret.obj(ListView(A.T_vector_int, 0, ArrayList<Any?>(m().keys.map { it.toDouble() })), A.T_vector_int)
        })
        LuaReg.method(A.M_unordered_map_int_RoomDefinition_size, LuaMethod { ret.num(m().size) })
        LuaReg.method(A.M_unordered_map_int_RoomDefinition_empty, LuaMethod { ret.bool(m().isEmpty()) })

        fun r(o: Any?) = o as RoomDefinition
        LuaReg.num(A.A_RoomDefinition_roomId, NumGet { r(it).roomId.toDouble() })
        LuaReg.bool(A.A_RoomDefinition_sensorBlind, { r(it).sensorBlind }, { o, v -> r(o).sensorBlind = v })
        LuaReg.num(A.A_RoomDefinition_hullDamageResistChance, NumGet { r(it).hullDamageResistChance.toDouble() },
            NumSet { o, v -> r(o).hullDamageResistChance = v.toFloat() })
        LuaReg.num(A.A_RoomDefinition_sysDamageResistChance, NumGet { r(it).sysDamageResistChance.toDouble() },
            NumSet { o, v -> r(o).sysDamageResistChance = v.toFloat() })
        LuaReg.num(A.A_RoomDefinition_ionDamageResistChance, NumGet { r(it).ionDamageResistChance.toDouble() },
            NumSet { o, v -> r(o).ionDamageResistChance = v.toFloat() })

        // MainMenu::shipBuilder (the hangar): open while the hangar shows.
        LuaReg.obj(A.A_MainMenu_shipBuilder, ObjGet { ShipBuilder })
        LuaReg.bool(A.A_ShipBuilder_bOpen, { xyz.znix.xftl.hs.lua.HsLua.game?.currentState is xyz.znix.xftl.hangar.SelectShipState })
    }
}
