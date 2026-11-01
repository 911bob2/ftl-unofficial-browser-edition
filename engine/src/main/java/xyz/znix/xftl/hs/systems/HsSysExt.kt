// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/System_Extend.h (ShipSystem_Extend)
// and src/game/Ship/CustomCrew.cpp (ShipManager::OnLoop resetting additionalPowerLoss; ShipSystem::CheckMaxPower /
// GetMaxPower adding it to iTempPowerLoss). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.lua.LuaApi
import xyz.znix.xftl.hs.lua.LuaTyped

/**
 * ShipSystem_Extend: `additionalPowerLoss` (power drain of HS crew and Lua scripts, restarted at zero every ship loop)
 * and the system box's `xOffset`. The engine takes [appliedLoss] off the system's levels next to the event loss
 * (AbstractSystem.undamagedEnergy); it follows [additionalPowerLoss] at each CheckMaxPower ([commit]).
 */
class HsSysExt(@JvmField val system: AbstractSystem) : LuaTyped {
    override val luaType: Int get() = LuaApi.T_ShipSystem_Extend

    @JvmField var additionalPowerLoss = 0
    @JvmField var xOffset = 0
    /** The loss in effect (iTempPowerLoss's additional part at the last CheckMaxPower). */
    @JvmField var appliedLoss = 0

    /** ShipSystem::CheckMaxPower (CustomCrew.cpp hook): the loss takes effect, capped at the undamaged levels. */
    fun checkMaxPower() {
        val s = system
        val cap = (s.energyLevels - s.damagedEnergyLevels - s.scriptedPowerLoss).coerceAtLeast(0)
        val loss = additionalPowerLoss.coerceIn(0, cap)
        if (loss != appliedLoss) {
            appliedLoss = loss
            s.powerLimitChanged()
        }
    }

    /** ShipSystem_Extend::usingDefaultOffset: false once a script wrote xOffset (the bar then skips the default wires). */
    @JvmField var usingDefaultOffset = true

    companion object {
        @JvmStatic
        fun of(s: AbstractSystem): HsSysExt = s.hsExt ?: HsSysExt(s).also { s.hsExt = it; it.xOffset = defaultOffset(xyz.znix.xftl.ai.nativeSystemId(s)) }

        /** System_Extend.cpp: the system box offset from the previous box, by system id. */
        @JvmStatic
        fun defaultOffset(id: Int): Int = when (id) {
            3, 4 -> 48
            14, 15, 10, 9, HsSystems.SYS_TEMPORAL, 12 -> 54
            8 -> 51
            else -> 36
        }

        /**
         * ShipManager::OnLoop (CustomCrew.cpp), after the original: every system's additionalPowerLoss restarts at
         * the crew's POWER_DRAIN in its room (hs.crew's HsPowerDrain, computed earlier in the same ship loop).
         */
        @JvmStatic
        fun resetLoop(ship: Ship) {
            for (s in ship.systems) {
                val drain = xyz.znix.xftl.hs.crew.HsPowerDrain.of(s)
                if (drain != 0) of(s).additionalPowerLoss = drain else s.hsExt?.additionalPowerLoss = 0
            }
        }

        /** The OnLoop's CheckMaxPower over every system, after SHIP_LOOP (so the scripts' additions count). */
        @JvmStatic
        fun commit(ship: Ship) {
            for (s in ship.systems) s.hsExt?.checkMaxPower()
        }
    }
}
