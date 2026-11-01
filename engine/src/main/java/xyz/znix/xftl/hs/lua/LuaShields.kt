// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07 (lua/modules/hyperspace.i Shields::Shield and
// ShieldPower members), not from the 1.23.2 tag.
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.systems.Shields
import xyz.znix.xftl.hs.lua.LuaApi as A

/** Shields::shields (Shields::Shield): charger and the ShieldPower layers of the engine's shield system. */
class LuaShield(@JvmField val sys: Shields) : LuaTyped { override val luaType get() = A.T_Shield }

/** Shield::power (ShieldPower): first = layers up, second = layers the power allows, super = (super shield, max). */
class LuaShieldPower(@JvmField val sys: Shields) : LuaTyped { override val luaType get() = A.T_ShieldPower }

object LuaShieldsApi {
    fun install() {
        LuaReg.obj(A.A_Shields_shields, ObjGet { LuaViews.cached(it as Shields, A.A_Shields_shields) { s -> LuaShield(s as Shields) } })
        LuaReg.num(A.A_Shield_charger, NumGet { (it as LuaShield).sys.rechargeTimer.toDouble() })
        LuaReg.obj(A.A_Shield_power, ObjGet { o -> val s = (o as LuaShield).sys; LuaViews.cached(s, A.A_Shield_power) { x -> LuaShieldPower(x as Shields) } })
        LuaReg.num(A.A_ShieldPower_first, NumGet { (it as LuaShieldPower).sys.activeShields.toDouble() }, NumSet { o, v ->
            val s = (o as LuaShieldPower).sys
            s.hsSilent = true
            try { s.activeShields = v.toInt() } finally { s.hsSilent = false }
        })
        LuaReg.num(A.A_ShieldPower_second, NumGet { val s = (it as LuaShieldPower).sys; minOf(s.selectedShieldBars, s.suppliedShieldBars).toDouble() })
        LuaReg.obj(A.A_ShieldPower_super, ObjGet { o ->
            val s = (o as LuaShieldPower).sys
            LuaViews.cached(s, A.A_ShieldPower_super) { x ->
                // the Shields object of a ship without the system (HsDetachedShields) belongs to that ship as well
                val ship = (x as Shields).room?.ship ?: x.hsState?.detachedShip ?: throw LuaError("shields without a ship")
                // super.second is 0 until the ship gets a super shield (Ship.hsSuperShieldSet), then the engine's maximum
                IntPairView(A.T_pair_int_int, IGet { ship.superShield }, ISet { ship.nativeSetSuperShield(it) },
                    IGet { if (ship.hsSuperShieldSet != 0) ship.maxSuperShield else 0 },
                    ISet { ship.maxSuperShield = it; ship.hsSuperShieldSet = if (it != 0) 1 else 0 })
            }
        })
    }
}
