package ftl.browser

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.environment.AsteroidEnvironment
import xyz.znix.xftl.environment.NebulaEnvironment
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.EnergySource
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.ReactorEnergySource
import xyz.znix.xftl.game.ResourceSet
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MainSystem

/**
 * Ship-level rules (doors Door::SetBlastDoor 0x470470 / ApplyDamage 0x46e0f0, ShipManager::ModifyScrapCount 0x49ba70,
 * the asteroid generator's RandomAmount 0x5caf40, the ION_ARMOR roll 0x6329c0 and the plasma storm's reactor limit with
 * ShipManager::UpdateReactor 0x49c6d0): algorithmic known answers on controlled fixtures, plus save round trips.
 */
internal object GameplayParityShipRulesChecks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val doors = doors(main, container)
            val scrap = scrapModifiers(main, container)
            val asteroids = asteroidRanges()
            val ion = ionArmour(main, container)
            val storm = stormPower(main, container)
            return "shiprules ok: $doors, $scrap, $asteroids, $ion, $storm"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    /**
     * Door health follows the doors system level through the per-difficulty tables (12/8/6, 16/12/10, 20/18/15 for
     * levels 2/3/4, four below that, nothing past level 4), one point of damage per hit, and an open, restored door
     * once the health runs out.
     */
    private fun doors(main: MainGame, container: GameContainer): String {
        val tables = mapOf(
            Difficulty.EASY to listOf(12, 16, 20),
            Difficulty.NORMAL to listOf(8, 12, 18),
            Difficulty.HARD to listOf(6, 10, 15)
        )

        var state: InGameState? = null
        for ((difficulty, table) in tables) {
            state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_HARD", difficulty)
            val ship = state.player
            val door = ship.doors.first { !it.isAirlock }
            val level = ship.doorsSystem?.effectivePower ?: 0
            val expected = if (level < 2) 4 else table[(level - 2).coerceAtMost(2)]
            check(door.maxHealth == expected) {
                "Door health $difficulty level $level: ${door.maxHealth}, expected $expected"
            }
        }

        // Hits (the last difficulty's fixture): max - 1 hits leave it standing, the next one forces it open and
        // restores the health, and the damage survives a save.
        state!!
        val door = state.player.doors.first { !it.isAirlock }
        door.resetHealth()
        door.open = false
        val max = door.maxHealth
        repeat(max - 1) { door.attackDoor() }
        check(!door.open && door.damage == max - 1) { "Door after ${max - 1} hits: open=${door.open} damage=${door.damage}" }

        val index = state.player.doors.indexOf(door)
        val reloaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val reloadedDoor = reloaded.player.doors[index]
        check(reloadedDoor.damage == max - 1) { "Door damage after reload: ${reloadedDoor.damage}" }

        reloadedDoor.attackDoor()
        check(reloadedDoor.open && reloadedDoor.damage == 0) {
            "Door after the breaking hit: open=${reloadedDoor.open} damage=${reloadedDoor.damage}"
        }
        return "doors"
    }

    /**
     * ShipManager::ModifyScrapCount: one truncation of scrap * (1 + collector - repair arm while the hull is below
     * the maximum), the repair arm's fraction applied only to a hull that is not full.
     */
    private fun scrapModifiers(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        fun give(name: String) {
            state.player.addBlueprint(state.blueprintManager[name] as AugmentBlueprint, true)
        }

        fun gain(amount: Int): Int {
            val before = state.player.scrap
            state.givePlayerResources(ResourceSet().also { it.scrap = amount })
            return state.player.scrap - before
        }

        check(gain(20) == 20) { "Scrap without augments" }

        give("SCRAP_COLLECTOR")
        val collector = state.player.getAugmentValue(AugmentBlueprint.SCRAP_COLLECTOR)
        check(gain(20) == 22) { "Scrap with a collector (value $collector)" }

        give("REPAIR_ARM")
        val arm = state.player.getAugmentValue(AugmentBlueprint.REPAIR_ARM)
        // At full hull the arm takes nothing off.
        state.player.health = state.player.maxHealth
        check(gain(20) == 22) { "Scrap with a collector and a repaired hull" }

        state.player.health = state.player.maxHealth - 5
        val bonus = collector - arm
        val expected = (bonus.toDouble() * 20.0 + 20.0).toInt()
        val got = gain(20)
        check(got == expected && got < 20) { "Scrap with the arm below full hull: $got, expected $expected" }
        // The arm also repairs two hull on every positive reward.
        check(state.player.health == state.player.maxHealth - 3) { "Repair arm healing: ${state.player.health}" }

        val reloaded = BrowserGameplayParityDiagnostics.reload(main, state)
        check(reloaded.player.scrap == state.player.scrap) { "Scrap after reload" }
        return "scrap"
    }

    /** RandomAmount::Get: the range widens by one on every draw, an unset range returns its minimum. */
    private fun asteroidRanges(): String {
        val range = AsteroidEnvironment.Range(5, 10)
        repeat(50) {
            val before = range.max
            val value = range.next()
            check(range.max == before + 1) { "Range max ${range.max}" }
            check(value in 5 until range.max) { "Range value $value in 5..${range.max}" }
        }
        check(range.max == 60) { "GameplayParityShipRulesChecks.kt:133" }

        check(AsteroidEnvironment.Range(3, 0).next() == 3) { "Empty range" }
        return "asteroid ranges"
    }

    /** int(value * 10) >= rand % 10 + 1: nothing without the augment, always at 1.0, sometimes at 0.5. */
    private fun ionArmour(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player

        check((0 until 200).none { ship.ionArmourNegates() }) { "Ion armour proc without the augment" }

        ship.addBlueprint(state.blueprintManager["ION_ARMOR"] as AugmentBlueprint, true)
        val single = (0 until 400).count { ship.ionArmourNegates() }
        check(single in 1 until 400) { "One reverse ion field: $single of 400" }

        ship.addBlueprint(state.blueprintManager["ION_ARMOR"] as AugmentBlueprint, true)
        check((0 until 200).all { ship.ionArmourNegates() }) { "Two reverse ion fields did not block every ion" }
        return "ion armour"
    }

    /**
     * Plasma storm: the reactor is cut to half rounded up, and while the systems demand more than that
     * ShipManager::UpdateReactor takes a step of power from a random system for good, even one that is ionised.
     */
    private fun stormPower(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player

        val storm = NebulaEnvironment(state, state.currentBeacon, true)
        for ((reactor, half) in listOf(8 to 4, 7 to 4, 1 to 1, 0 to 0)) {
            val available = HashMap<EnergySource, Int>()
            available[ReactorEnergySource] = reactor
            storm.adjustShipPower(ship, available)
            check(available[ReactorEnergySource] == half) { "Storm reactor $reactor -> ${available[ReactorEnergySource]}" }
        }

        val plain = NebulaEnvironment(state, state.currentBeacon, false)
        val untouched = HashMap<EnergySource, Int>()
        untouched[ReactorEnergySource] = 8
        plain.adjustShipPower(ship, untouched)
        check(untouched[ReactorEnergySource] == 8) { "A plain nebula changed the reactor" }

        // A forced decrease works on a locked (ionised) system, which refuses the player's own decrease.
        val system = ship.systems.filterIsInstance<MainSystem>().first { it.reactorPowerDemand > 0 }
        system.addLockUnits(1)
        check(system.isIonised && system.isPowerLocked) { "${system.javaClass.simpleName} is not locked" }
        val before = system.reactorPowerDemand
        system.forceDecreasePower()
        check(system.reactorPowerDemand < before) { "Forced decrease: $before -> ${system.reactorPowerDemand}" }
        check(system.isPowerLocked) { "The forced decrease left the locked system unlocked" }
        return "storm power"
    }
}
