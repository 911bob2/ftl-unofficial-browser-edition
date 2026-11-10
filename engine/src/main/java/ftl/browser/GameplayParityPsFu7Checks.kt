package ftl.browser

import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer

/**
 * Parity sweep follow-up FU7. Every mode (vanilla, F2, F3):
 * - ShipAI::OnLoop (1.6.14 0x625c50) asks for CountCrew(false) (0x49c180) minus the crew it keeps: CountCrew skips a
 *   crew member only once it is bDead (+0x1fc), so one in its death hold still counts and one a script killed (Lua
 *   CrewMember::Kill: bDead at once) no longer does. HS oracle ps-fu7-mt / -en: the moment C6's mind control scenario
 *   kills its crew the request drops to zero, the crew member walking to the teleporter turns back and fights the new
 *   boarder, and the new enemy crew member is sent instead (teleporter used 1.5 s later, as in ps-c6-en).
 */
internal object GameplayParityPsFu7Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name: " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("boardingCountUntilDead") { boardingCountUntilDead(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-fu7 ok: " + results.joinToString(", ")
    }

    private fun boardingCountUntilDead(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 2, 71)
        val enemy = state.enemy!!
        state.debugFlags.noEnemyFire.set = true
        repeat(3) { state.updateGameState(.05f) }
        val crew = enemy.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === enemy && it.mode == AbstractCrew.SlotType.CREW }
        check(crew.size >= 2) { "the generated pirate has ${crew.size} crew" }
        val before = enemy.crewAI.getNumMoreBoarders()
        // in its death hold (health 0, not bDead): still counted
        val dying = crew[0]
        dying.health = 0f
        state.updateGameState(.05f)
        check(dying.currentAction == AbstractCrew.Action.DYING && enemy.crew.contains(dying)) { "the crew member at health 0 is ${dying.currentAction}" }
        val whileDying = enemy.crewAI.getNumMoreBoarders()
        check(whileDying == before) { "a dying crew member left the boarding count ($before -> $whileDying)" }
        // killed by a script (Lua Kill: bDead at once): out of the count before its own loop takes it off the ship
        val killed = crew[1]
        killed.hsKillNow()
        val afterKill = enemy.crewAI.getNumMoreBoarders()
        check(enemy.crew.contains(killed) && afterKill == before - 1) { "a crew member killed by a script still counts ($before -> $afterKill)" }
        return "the boarding count keeps the dying and drops the killed"
    }
}
