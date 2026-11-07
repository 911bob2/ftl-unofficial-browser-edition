package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.weapons.Damage

/**
 * Parity sweep C3 (weapons and damage), vanilla and modded: what a hit does and which numbers it shows,
 * ShipManager::DamageArea (1.6.14 0x4a6100, 1.6.9 0x499fb0) with DamageSystem (1.6.14 0x4a4ab0), and the chain boost
 * of a personnel-damage weapon (ProjectileFactory::Update, 1.6.9 0x40ccd0). Leaves both random streams as it found
 * them, so the later steps see the same draws.
 */
internal object GameplayParityPsC3Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val native = NativeRandom.snapshot()
        try {
            val area = damageArea(main, container)
            val chain = chainPersonnel()
            return "ps-c3 rules ok: $area, $chain"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(native)
        }
    }

    private class Shown(val image: String, val colour: xyz.znix.xftl.rendering.Colour)

    /** The damage numbers [block] adds to [ship], by image name (Text_<n>_L, text_resist). */
    private fun shown(state: InGameState, ship: Ship, block: () -> Unit): List<Shown> {
        ship.damageNumbers.clear()
        block()
        val names = HashMap<xyz.znix.xftl.rendering.Image, String>()
        for (n in 0..9) names[state.getImg("img/numbers/Text_${n}_L.png")] = "$n"
        names[state.getImg("img/numbers/text_resist.png")] = "resist"
        return ship.damageNumbers.map { Shown(names[it.image] ?: "?", it.colour) }
    }

    private fun Room.clearBreaches() = breaches.indices.forEach { breaches[it] = null }

    /**
     * A certain breach (breachChance 10: Heavy Crystal II, the Anti-Ship Battery's shot) always breaches. In 1.6.14 it
     * also ends a Rock Plating proc: the hull damage goes through, no RESIST. Hyperspace's DamageArea (CustomCrew.cpp)
     * keeps the proc: no hull damage, RESIST. Any other breach a proc stops, with RESIST. A System Casing proc takes
     * the hull and system damage out of DamageSystem and shows RESIST in place of the hull number (Hyperspace only for
     * a hit with hull damage, and also on a completely destroyed system). DamageSystem shows the system number
     * (iSystemDamage) and DamageArea the hull number; Rock Plating does not hide the system number.
     */
    private fun damageArea(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        state.debugFlags.noDmg.set = false
        val empty = player.rooms.first { it.system == null }
        val sysRoom = player.shields!!.room!!
        val full = player.maxHealth
        fun hit(hull: Int, sys: Int, breach: Int) = Damage().also {
            it.hullDamage = hull; it.pureSysDamage = sys; it.breachChance = breach; it.noCrewDamage = true
        }

        // Rock Plating proc, certain breach: the breach, and in 1.6.14 the hull damage and number, in Hyperspace RESIST.
        val hs = xyz.znix.xftl.hs.HS.active
        player.health = full; empty.clearBreaches()
        val certain = shown(state, player) { player.damage(empty, hit(4, 0, 100), rockArmourRoll = true) }
        check(player.health == if (hs) full else full - 4) { "Rock Plating and a certain breach: hull ${player.health} of $full" }
        check(empty.breaches.any { it != null }) { "certain breach under Rock Plating did not breach" }
        check(certain.map { it.image } == listOf(if (hs) "resist" else "4")) {
            "certain breach under Rock Plating shows ${certain.map { it.image }}"
        }

        // Rock Plating proc, a breach short of certain: no hull damage, no breach, RESIST.
        player.health = full; empty.clearBreaches()
        val stopped = shown(state, player) { player.damage(empty, hit(4, 0, 90), rockArmourRoll = true) }
        check(player.health == full && empty.breaches.none { it != null }) {
            "Rock Plating with breachChance 9: hull ${player.health} of $full, breach ${empty.breaches.any { it != null }}"
        }
        check(stopped.map { it.image } == listOf("resist")) { "Rock Plating with breachChance 9 shows ${stopped.map { it.image }}" }

        // Rock Plating proc on a system room: the system number still shows (DamageSystem), then RESIST.
        val shields = player.shields!!
        player.health = full; shields.damagedEnergyLevels = 0
        val rockSys = shown(state, player) { player.damage(sysRoom, hit(2, 1, 0), rockArmourRoll = true) }
        check(rockSys.map { it.image } == listOf("1", "resist") && rockSys[0].colour == xyz.znix.xftl.Constants.DAMAGE_COLOUR_SYSTEM) {
            "Rock Plating on a system room shows ${rockSys.map { it.image }}"
        }

        // No proc: the system number (iSystemDamage, not the hull part), then the hull number.
        player.health = full; shields.damagedEnergyLevels = 0
        val plain = shown(state, player) { player.damage(sysRoom, hit(2, 1, 0), rockArmourRoll = false) }
        check(plain.map { it.image } == listOf("1", "2") && player.health == full - 2) {
            "plain hit on a system room shows ${plain.map { it.image }}, hull ${player.health} of $full"
        }

        // System Casing proc: the system keeps its bars, the hull is hit, RESIST in place of the hull number.
        val casing = state.blueprintManager.getOrNull(AugmentBlueprint.SYSTEM_CASING) as? AugmentBlueprint
        val casingResult = if (casing == null) "no SYSTEM_CASING" else {
            player.augments += casing
            player.cargoUpdated()
            try {
                // A seed whose casing roll procs: the system keeps its bars (the roll's place among the draws is
                // DamageArea's business, so find it by the outcome).
                fun proc(hull: Int, sys: Int): Pair<String, List<Shown>> {
                    for (seed in (1..2000).map { it.toString() }) {
                        shields.damagedEnergyLevels = 0; player.health = full
                        BrowserRandom.restore(seed)
                        val s = shown(state, player) { player.damage(sysRoom, hit(hull, sys, 0), rockArmourRoll = false) }
                        if (shields.damagedEnergyLevels == 0) return Pair(seed, s)
                    }
                    error("no System Casing proc in 2000 seeds")
                }
                val (seed, cased) = proc(2, 1)
                check(player.health == full - 2) { "System Casing proc: hull ${player.health} of $full" }
                check(cased.map { it.image } == listOf("resist")) { "System Casing proc shows ${cased.map { it.image }}" }
                // System damage only: 1.6.14 shows RESIST, Hyperspace nothing.
                val (_, sysOnly) = proc(0, 1)
                check(sysOnly.map { it.image } == if (hs) emptyList() else listOf("resist")) {
                    "System Casing proc on system damage only shows ${sysOnly.map { it.image }}"
                }
                // A completely destroyed system: 1.6.14 ignores the proc (the hull number), Hyperspace still RESISTs.
                shields.damagedEnergyLevels = shields.energyLevels; player.health = full
                BrowserRandom.restore(seed)
                val destroyed = shown(state, player) { player.damage(sysRoom, hit(2, 1, 0), rockArmourRoll = false) }
                check(destroyed.any { it.image == "resist" } == hs && destroyed.any { it.image == "2" } == !hs) {
                    "System Casing proc on a destroyed system shows ${destroyed.map { it.image }}"
                }
                "System Casing RESIST"
            } finally {
                player.augments.remove(casing)
                player.cargoUpdated()
                shields.damagedEnergyLevels = 0
            }
        }
        player.health = full; empty.clearBreaches(); shields.damagedEnergyLevels = 0
        return "rock plating and certain breach, damage numbers, $casingResult"
    }

    /**
     * The chain boost adds (boostLevel - 1) x amount to the first of iDamage, iIonDamage, iPersDamage, iSystemDamage
     * that is above zero; iPersDamage counts in crew units of 15 health (DamageCrew's x15).
     */
    private fun chainPersonnel(): String {
        val d = Damage().also { it.pureCrewDamage = 2 * 15 }
        d.applyWeaponChaining(2)
        check(d.pureCrewDamage == 4 * 15) { "chain boost on personnel damage: ${d.pureCrewDamage} health, native 60" }
        val h = Damage().also { it.hullDamage = 1; it.pureCrewDamage = 15 }
        h.applyWeaponChaining(1)
        check(h.hullDamage == 2 && h.pureCrewDamage == 15) { "chain boost with hull damage: ${h.hullDamage}/${h.pureCrewDamage}" }
        return "chain boost on personnel damage"
    }
}
