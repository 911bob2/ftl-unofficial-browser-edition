// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i: WorldManager::AddBoarders,
// ClearLocation). The behaviour is the 1.6.14 executable's (WorldManager::AddBoarders 0x613440, ClearLocation 0x614d00).
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaArgs as arg
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** WorldManager methods WP6 left open: AddBoarders(BoardingEvent&) and ClearLocation(). Called by LuaAdaptersMore.install. */
object LuaAdaptersWP6Left {
    fun install() {
        // Hyperspace.BoardingEvent(): a free-standing struct (type, min, max, amount, breach) a script fills in.
        LuaReg.method(A.M_new_BoardingEvent, LuaMethod { ret.obj(LuaStructs.create(A.T_BoardingEvent), A.T_BoardingEvent) })
        // bool AddBoarders(BoardingEvent&): the event's race, amount and breach flag (min and max are the generator's
        // and play no part); nothing comes aboard at 15 intruders or with a non-positive amount.
        LuaReg.method(A.M_WorldManager_AddBoarders, LuaMethod {
            val g = LuaAdapters.inGame()
            val ev = arg.obj(2) ?: throw LuaError("expected BoardingEvent")
            val race = LuaReg.strGet[A.A_BoardingEvent_type]?.get(ev) ?: "random"
            val amount = LuaReg.numGet[A.A_BoardingEvent_amount]?.get(ev)?.toInt() ?: 0
            val breach = (LuaReg.numGet[A.A_BoardingEvent_breach]?.get(ev) ?: 0.0) != 0.0
            ret.bool(g?.addBoarders(race, amount, breach) ?: false)
        })
        // void ClearLocation(): the location is wiped (its event and hazard), with the Hyperspace hooks that run with it.
        LuaReg.method(A.M_WorldManager_ClearLocation, LuaMethod {
            val g = LuaAdapters.inGame() ?: return@LuaMethod 0
            xyz.znix.xftl.hs.events.HsFlow.beforeCreateLocation(null)
            g.clearLocation()
            0
        })
    }
}
