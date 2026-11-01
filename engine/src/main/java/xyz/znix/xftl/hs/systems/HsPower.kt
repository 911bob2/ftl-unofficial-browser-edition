package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.Ship
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.sector.EventStatus
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A ship's PowerManager temporary status (FTL keeps one PowerManager per ship id): iTempPowerCap (+0x14, 1000 when
 * unset), iTempPowerLoss (+0x18) and iTempDividePower (+0x1c, 1 when unset). Hyperspace's Lua writes these fields
 * (MV re-applies the plasma storm's halving every tick after a `clear reactor` status), and `<status system="reactor">`
 * sets them (ShipManager 0x49e3f0 / 0x49e4d0, system id 17). The engine applies them only while HS is active; the
 * vanilla engine keeps its own plasma storm halving in the environment and ignores reactor statuses.
 */
class HsPower {
    @JvmField var cap = CAP_NONE
    @JvmField var loss = 0
    @JvmField var divide = 1
    /** PowerManager::iHacked (SetHacked: 2 while hacked, else 0): taken off the reactor like the loss. */
    @JvmField var hacked = 0
    /** PowerManager::over_powered (15) and fFuel (100), constructor values nothing in FTL 1.6.9 changes. */
    @JvmField var overPowered = 15
    @JvmField var fuel = 100f
    /** PowerManager::failedPowerup: set when an increase found too little power (GetFailedPowerup reads and clears). */
    @JvmField var failedPowerup = false

    /** PowerManager::ClearStatus (0x647850). */
    fun clear() { cap = CAP_NONE; loss = 0; divide = 1 }

    /** PowerManager::SetDividePower (0x647940): the divisor, and the cap becomes ceil(max / divisor). */
    fun setDivide(d: Int, max: Int) {
        divide = d
        if (d != 0) cap = ceil(max.toFloat() / d).toInt()
    }

    companion object {
        const val CAP_NONE = 1000

        /** Beacon keys of the player's reactor statuses (the beacon keeps the player's statuses for its save). */
        const val KEY = "reactor"
        const val KEY_DIVIDE = "reactor/divide"

        /** The ship's PowerManager status; the player's starts from the statuses stored on the current beacon. */
        @JvmStatic
        fun of(ship: Ship): HsPower {
            ship.hsPower?.let { return it }
            val p = HsPower()
            ship.hsPower = p
            if (ship.sys.player === ship) fromBeacon(ship, p)
            return p
        }

        private fun fromBeacon(ship: Ship, p: HsPower) {
            p.clear()
            val beacon = ship.sys.currentBeacon
            beacon.powerLimitEffects[KEY]?.let { p.cap = it }
            beacon.powerLossEffects[KEY]?.let { p.loss = it }
            beacon.powerLimitEffects[KEY_DIVIDE]?.let { p.setDivide(it, ship.reactorPower) }
        }

        /**
         * Jump arrival (ShipManager::JumpArrive clears the statuses, WorldManager applies the new beacon's): the
         * player's status comes back from the arrival beacon, any other ship's is cleared.
         */
        @JvmStatic
        fun arrive(ship: Ship) {
            val p = ship.hsPower ?: return
            if (ship.sys.player === ship) fromBeacon(ship, p) else p.clear()
        }

        /**
         * ShipManager::UpdateReactor (0x49c6d0, every OnLoop): with a divisor the cap is recomputed from the reactor
         * level (0x6478a0), then the reactor gives at most min(cap, max - hacked - loss) (PowerManager 0x647760; the
         * engine has no reactor hacking). Applied after the environment (whose plasma storm halving equals the
         * storm's divide-by-two cap, so the two do not stack).
         */
        @JvmStatic
        fun apply(ship: Ship, power: HashMap<EnergySource, Int>) {
            val p = ship.hsPower ?: if (ship.sys.player === ship) of(ship) else return
            val max = ship.reactorPower
            if (p.divide != 1 && p.divide != 0) p.cap = ceil(max.toFloat() / p.divide).toInt()
            if (p.cap >= CAP_NONE && p.loss == 0 && p.hacked == 0) return
            val limit = max(0, min(p.cap, max - p.loss - p.hacked))
            val reactor = power[ReactorEnergySource] ?: return
            if (reactor > limit) power[ReactorEnergySource] = limit
        }

        /**
         * A `<status system="reactor">` effect (WorldManager 0x613a10 / 0x613760 -> ShipManager): limit = SetPowerCap,
         * loss = SetPowerLoss, divide = SetDividePower, clear = ClearStatus. The player's also go on the beacon, as
         * the engine keeps the player's system statuses there.
         */
        @JvmStatic
        fun status(ship: Ship, player: Boolean, op: EventStatus.Operation, amount: Int) {
            val p = of(ship)
            val beacon = if (player) ship.sys.currentBeacon else null
            when (op) {
                EventStatus.Operation.CLEAR -> {
                    p.clear()
                    beacon?.powerLimitEffects?.remove(KEY); beacon?.powerLimitEffects?.remove(KEY_DIVIDE)
                    beacon?.powerLossEffects?.remove(KEY)
                }
                // a divisor stays and recomputes the cap on the next UpdateReactor, as natively
                EventStatus.Operation.LIMIT -> { p.cap = amount; beacon?.powerLimitEffects?.set(KEY, amount) }
                EventStatus.Operation.LOSS -> { p.loss = amount; beacon?.powerLossEffects?.set(KEY, amount) }
                EventStatus.Operation.DIVIDE -> { p.setDivide(amount, ship.reactorPower); beacon?.powerLimitEffects?.set(KEY_DIVIDE, amount) }
            }
            ship.updateAvailablePower()
        }
    }
}
