// Ported from FTL-Hyperspace (GPL-3.0-or-later) commit 1b05a07: lua/modules/hyperspace.i (ActivatedPowerDefinition,
// ProjectileFactory), src/game/Ship/CustomCrew.h (ActivatedPowerDefinition's constructor). Round 15 "Hyperspace gaps".
package xyz.znix.xftl.hs.lua

import xyz.znix.xftl.hs.lua.LuaApi as A
import xyz.znix.xftl.hs.lua.LuaPush as ret

/** Lua members other Hyperspace mods use that no earlier slice registered (round 15, mod-compat G4, G6). */
object LuaAdaptersGaps {
    fun install() {
        // Hyperspace.ActivatedPowerDefinition(): a new definition with CustomCrew.h's constructor defaults (no damage,
        // owner/self -1, the cooldown colour 133/231/237, a white temporary-power colour, player/enemy requirements);
        // like HS it is not in the definition list until AssignIndex (Universal Weapon Modularity's runtime powers).
        LuaReg.method(A.M_new_ActivatedPowerDefinition, LuaMethod {
            ret.obj(xyz.znix.xftl.hs.crew.ActivatedPowerDefinition(), A.T_ActivatedPowerDefinition)
        })
    }
}
