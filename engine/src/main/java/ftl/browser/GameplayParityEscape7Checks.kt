package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Playtest C5 (enemies charging their FTL too often): the stalemate that makes any ship escape needs the player's
 * engines or piloting to have no power cap (ShipSystem::GetPowerCap 0x63b790: the level or an event limit) or no
 * fuel, so damage and ion never start it (WorldManager::OnLoop 0x61b930). A ship whose event has no escape never
 * flees on its hull; a jumping ship shows no surrender event; a ship that got away counts as defeated (0x61c7c1).
 */
internal object GameplayParityEscape7Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val stalemate = noStalemateFromDamage(main, container)
            val noEvent = noEscapeWithoutEvent(main, container)
            val jumping = surrenderWhileJumping(main, container)
            val defeated = escapeCountsDefeated(main, container)
            return "escape7 ok: $stalemate, $noEvent, $jumping, $defeated"
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
    }

    private fun spawn(state: InGameState, name: String, sector: Int, seed: Int): Ship {
        state.debugSpawnShip(state.eventManager.getShip(name), Difficulty.NORMAL, sector, seed)
        return state.enemy!!
    }

    private fun noStalemateFromDamage(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, "PIRATE", 4, 71)
        enemy.surrenderHealth = 0
        enemy.escapeHealth = 0
        enemy.escapeTimer = null
        val ai = ShipAI(enemy, state.player)
        val player = state.player
        val engines = player.engines!!
        val piloting = player.piloting!!

        // The playtest game: a one-bar cockpit knocked out, an ionised cockpit, wrecked engines. The player can still jump
        // once they are repaired, so the original sees no stalemate.
        piloting.dealDamage(piloting.energyLevels, 0)
        engines.dealDamage(engines.energyLevels, 0)
        check(piloting.undamagedEnergy == 0 && engines.undamagedEnergy == 0) { "Fixture systems not knocked out" }
        repeat(3) { ai.update(1f) }
        check(enemy.aiStalemateTimer == 0f && enemy.escapeTimer == null && enemy.escapeHealth == 0) {
            "Damaged engines and piloting started a stalemate escape (timer ${enemy.escapeTimer})"
        }
        piloting.dealDamage(0, 2)
        ai.update(1f)
        check(enemy.escapeTimer == null) { "Ionised piloting started a stalemate escape" }
        check(ShipAI.nativePowerCap(piloting) == piloting.energyLevels) { "Power cap follows damage" }

        // An event that limits the engines to nothing does: the player can't charge the drive at all.
        engines.scriptedPowerLimit = 0
        ai.update(1f)
        check(enemy.aiStalemateTimer == 1f && enemy.escapeHealth == ShipAI.STALEMATE_ESCAPE_HEALTH) {
            "An engine limit of 0 did not start the stalemate"
        }
        check((enemy.escapeTimer ?: 0f) >= ShipAI.STALEMATE_ESCAPE_TIMER - 1.01f) { "Stalemate timer ${enemy.escapeTimer}" }
        return "damage and ion start no stalemate, a 0 power limit does"
    }

    /** REBEL_AUTO has no `<escape>`: its threshold is 0, so nothing happens however low the hull goes. */
    private fun noEscapeWithoutEvent(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        for (seed in 1..6) {
            val enemy = spawn(state, "REBEL_AUTO", 6, seed)
            check(enemy.spec!!.escape == null) { "REBEL_AUTO gained an escape event" }
            check(enemy.escapeHealth == 0) { "REBEL_AUTO escape threshold ${enemy.escapeHealth}" }
            val ai = ShipAI(enemy, state.player)
            enemy.health = 1
            ai.update(1f)
            check(enemy.escapeTimer == null) { "REBEL_AUTO started to escape at 1 hull" }
        }
        return "no escape event, no escape"
    }

    private fun surrenderWhileJumping(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, "PIRATE", 4, 71)
        check(enemy.spec!!.surrender != null) { "PIRATE has no surrender event" }
        enemy.escapeTimer = 3f
        enemy.surrenderHealth = enemy.health
        enemy.browserJumping = true
        check(!state.shipUI.isWindowOpen) { "A window is open before the check" }
        ShipAI(enemy, state.player).update(0f)
        check(enemy.aiSurrendered) { "A jumping ship did not mark itself surrendered" }
        check(!state.shipUI.isWindowOpen) { "A jumping ship offered its surrender" }
        return "no surrender event while jumping"
    }

    private fun escapeCountsDefeated(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, "PIRATE", 4, 71)
        enemy.surrenderHealth = 0
        check(enemy.canChargeFTL) { "The fixture enemy can't charge its FTL" }
        val before = state.browserProgress.shipsDefeated
        enemy.escapeTimer = 0.01f
        var frames = 0
        while (state.enemy === enemy && frames < 200) {
            main.update(container, .05f)
            frames++
        }
        check(state.enemy !== enemy) { "The escaping ship never left (timer ${enemy.escapeTimer})" }
        check(state.browserProgress.shipsDefeated == before + 1) {
            "Ships defeated ${state.browserProgress.shipsDefeated}, expected ${before + 1}"
        }
        return "an escaped ship counts as defeated"
    }
}
