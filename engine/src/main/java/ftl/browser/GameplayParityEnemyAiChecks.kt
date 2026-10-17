package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import kotlin.random.Random

/**
 * Enemy ship AI (ShipAI::OnLoop 0x625c50, power management 0x6259b0, CombatAI::ChooseSystem 0x62b200, the stalemate
 * rule 0x6251f0 / 0x625220, WorldManager's surrender / escape order): scripted draws against the native percentages,
 * a power allocation whose answer follows from the native order, the stalemate timers and the save round trip.
 */
internal object GameplayParityEnemyAiChecks {
    /** A random source that returns the scripted values (modulo the bound), cycling when it runs out. */
    private class Script(vararg val values: Int) : Random() {
        private var next = 0

        override fun nextBits(bitCount: Int): Int = 0

        override fun nextInt(until: Int): Int {
            val value = values[next++ % values.size]
            return ((value % until) + until) % until
        }
    }

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val targeting = targeting(main, container)
            val power = powerAllocation(main, container)
            val stalemate = stalemate(main, container)
            val order = surrenderAndEscape(main, container)
            val save = saveRoundTrip(main, container)
            return "enemyai ok: $targeting, $power, $stalemate, $order, $save"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun spawn(state: InGameState, seed: Int): Ship {
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed)
        return state.enemy!!
    }

    /**
     * ChooseSystem: Easy / Normal aim at a system for 20 / 33 of 100 rolls, from the list shields, engines,
     * weapons, oxygen, drones; Hard uses its candidate list for 25, a random system for 25 and a random room for 50.
     */
    private fun targeting(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        val player = state.player
        check(player.shields != null && player.engines != null && player.weapons != null && player.oxygen != null) {
            "The fixture ship is missing a priority system"
        }

        for ((difficulty, expected) in listOf(Difficulty.EASY to 20, Difficulty.NORMAL to 33)) {
            var chosen = 0
            for (roll in 0..99) {
                val system = BrowserEnemyTargeting.chooseSystem(enemy, player, difficulty, Script(roll, 0))
                if (system != null) {
                    check(system === player.shields) { "$difficulty roll $roll did not aim at the first priority system" }
                    chosen++
                }
            }
            check(chosen == expected) { "$difficulty aims at a system $chosen times in 100, expected $expected" }
        }

        val priority = listOf(player.shields, player.engines, player.weapons, player.oxygen, player.drones)
        for ((index, expected) in priority.withIndex()) {
            val system = BrowserEnemyTargeting.chooseSystem(enemy, player, Difficulty.NORMAL, Script(0, index))
            check(system === expected) { "Priority entry $index is wrong" }
        }

        // Hard: the candidate list follows the target's state.
        player.rooms.forEach { it.oxygen = .49f }
        player.update(0f)
        check(2 in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Oxygen at 49% is not a candidate" }
        player.rooms.forEach { it.oxygen = .5f }
        player.update(0f)
        check(2 !in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Oxygen at 50% is a candidate" }
        player.rooms.forEach { it.oxygen = .49f }
        player.update(0f)

        val candidates = BrowserEnemyTargeting.hardCandidates(enemy, player)
        val systems = player.systems
        check(candidates.isNotEmpty() && systems.isNotEmpty()) { "GameplayParityEnemyAiChecks.kt:92" }
        val pick = (0..15).firstOrNull { v ->
            BrowserEnemyTargeting.system(player, candidates[v % candidates.size]) !== systems[v % systems.size]
        }
        check(pick != null) { "Candidate and random system draws can't be told apart" }
        for (roll in 0..99) {
            val system = BrowserEnemyTargeting.chooseSystem(enemy, player, Difficulty.HARD, Script(roll, pick!!))
            val expected = when {
                roll < 25 -> BrowserEnemyTargeting.system(player, candidates[pick % candidates.size])
                roll < 50 -> systems[pick % systems.size]
                else -> null
            }
            check(system === expected) { "Hard roll $roll chose the wrong target" }
        }

        // A room is the centre of the chosen system's room, or the room drawn from the ship.
        val room = BrowserEnemyTargeting.pickRoom(enemy, player, Script(99, 2))
        check(room === player.rooms[2 % player.rooms.size]) { "Random room draw is wrong" }
        return "ChooseSystem 20/33/25-25-50 percent and priority list"
    }

    /**
     * Power management: with exactly enough spare power for one weapon after the one-bar systems, the weapon with
     * the most charge gets it (and the power follows the charge); while escaping, shields and engines come first.
     */
    private fun powerAllocation(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var found: Ship? = null
        for (seed in 100..160) {
            val candidate = spawn(state, seed)
            val weapons = candidate.weapons ?: continue
            val usable = candidate.hardpoints.mapNotNull { it.weapon }
                .filter { it.hasEnoughMissiles && it.type.power <= weapons.undamagedEnergy }
            if (usable.size >= 2 && candidate.oxygen != null && candidate.engines != null && candidate.piloting != null) {
                found = candidate
                break
            }
        }
        val enemy: Ship = found ?: error("No fixture enemy with two usable weapons")

        val all = enemy.hardpoints.mapNotNull { it.weapon }
        val weaponsSystem = enemy.weapons!!
        val usable = all.filter { it.hasEnoughMissiles && it.type.power <= weaponsSystem.undamagedEnergy }
        val fixed = listOf(enemy.oxygen, enemy.teleporter, enemy.clonebay).count { it != null && it.undamagedEnergy >= 1 }
        val ai = ShipAI(enemy, state.player)

        fun powerFor(best: xyz.znix.xftl.weapons.AbstractWeaponInstance) {
            all.forEach { it.timeCharged = 0f }
            best.timeCharged = 5f
            enemy.purchasedReactorPower = fixed + best.type.power
            enemy.updateAvailablePower()
            ai.update(0f)
        }

        val first = usable.last()
        powerFor(first)
        check(all.filter { it.isPowered } == listOf(first)) { "The charged weapon did not get the power" }

        val second = usable.first { it !== first }
        powerFor(second)
        check(all.filter { it.isPowered } == listOf(second)) { "Power did not follow the charge" }

        // Escaping: shields, cloaking and engines come before the weapons.
        enemy.escapeTimer = 10f
        ai.update(0f)
        val shields = enemy.shields?.undamagedEnergy?.let { it / 2 * 2 } ?: 0
        val capacity = shields + (enemy.engines?.undamagedEnergy ?: 0) + (enemy.cloaking?.undamagedEnergy ?: 0)
        if (capacity >= second.type.power)
            check(!second.isPowered) { "An escaping ship still powers its weapon first" }
        check(enemy.escapeTimer == 10f) { "The escape timer moved with no time passing" }
        return "power order follows the charge"
    }

    /**
     * The stalemate rule: the player can't jump (no fuel) and nobody is boarding, so the ship starts escaping with a
     * timer of at least 90 seconds and the escape threshold 300. A hull change caps the stalemate timer at 60,
     * two minutes end it.
     */
    private fun stalemate(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        enemy.surrenderHealth = 0
        enemy.escapeHealth = 0
        enemy.escapeTimer = null
        val ai = ShipAI(enemy, state.player)

        // With fuel there is no stalemate.
        ai.update(1f)
        check(enemy.aiStalemateTimer == 0f && enemy.escapeTimer == null) { "Stalemate started with fuel" }

        state.player.fuelCount = 0
        ai.update(1f)
        check(enemy.aiStalemateTimer == 1f) { "Stalemate timer is ${enemy.aiStalemateTimer}" }
        check(enemy.escapeHealth == ShipAI.STALEMATE_ESCAPE_HEALTH) { "Stalemate escape threshold" }
        val timer = enemy.escapeTimer
        check(timer != null && timer >= 88.99f) { "Stalemate escape timer is $timer" }

        enemy.aiStalemateTimer = 100f
        enemy.health = enemy.health - 1
        ai.update(0f)
        check(enemy.aiStalemateTimer == 60f) { "A hull change leaves the stalemate timer at ${enemy.aiStalemateTimer}" }
        ai.update(1f)
        check(enemy.aiStalemateTimer == 61f) { "The stalemate timer stopped running" }

        enemy.aiStalemateTimer = 120.5f
        ai.update(0f)
        check(enemy.aiStalemateTimer < 1f) { "Two minutes of stalemate did not end it" }
        return "stalemate timers"
    }

    /** Surrender is checked before escape, and a ship that surrendered never starts to escape. */
    private fun surrenderAndEscape(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        enemy.escapeTimer = null
        enemy.surrenderHealth = enemy.health
        enemy.escapeHealth = enemy.health
        val ai = ShipAI(enemy, state.player)
        ai.update(0f)
        check(enemy.aiSurrendered) { "The ship did not surrender" }
        check(enemy.escapeTimer == null) { "Surrender and escape in the same update" }
        ai.update(0f)
        check(enemy.escapeTimer == null) { "A surrendered ship started to escape" }

        val other = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = spawn(other, 71)
        ship.escapeTimer = null
        ship.surrenderHealth = 0
        ship.escapeHealth = ship.health
        ShipAI(ship, other.player).update(0f)
        val spec = ship.spec!!
        if (spec.escape != null) {
            val expected = if (spec.escapeTimer > 0f) spec.escapeTimer else ShipAI.DEFAULT_ESCAPE_TIMER
            check(ship.escapeTimer == expected) { "Escape timer ${ship.escapeTimer}, expected $expected" }
        } else {
            check(ship.escapeTimer == null && ship.escapeHealth == -100) { "A missing escape event did not cancel the escape" }
        }
        return "surrender before escape"
    }

    /** The AI's own state survives a save. */
    private fun saveRoundTrip(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        enemy.aiSurrendered = true
        enemy.aiStalemateTimer = 42.5f
        enemy.aiTeleportWaves = 2
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state).enemy!!
        check(loaded.aiSurrendered && loaded.aiStalemateTimer == 42.5f && loaded.aiTeleportWaves == 2) {
            "AI state changed in the save"
        }
        return "save round trip"
    }
}
