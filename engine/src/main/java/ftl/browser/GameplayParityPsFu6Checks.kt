package ftl.browser

import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewStuck
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint

/**
 * Parity sweep follow-up FU6 (crew and combat leftovers). Every mode (vanilla, F2, F3):
 * - the enemy AI's volley keeps its aim as ProjectileFactory::lastTargets: Fire copies the aim, reversed, into
 *   lastTargets and CombatAI never calls ClearAiming, so after the volley the Burst Laser II still holds its three points
 *   (one per shot); they go when the weapon loses its power (ProjectileFactory::Update, HS CustomWeapons.cpp). HS oracle
 *   ps-m1 `lt` rows: 1 and 3 points after the Basic Laser's and the Burst Laser II's volleys.
 * - CREW_STUCK (WorldManager::OnLoop) brings home a boarder in its death hold too (it is not bDead) and it dies at home;
 *   ShipManager::CountCrew counts it until bDead. HS oracle ps-fu6-stuck: boarder B at health 0 is in the cockpit on the
 *   first loop after the deadCrew box, bDead 1.5 s later.
 * - CrewAI::CheckForProblems (1.6.14 0x628900) counts a room's boarders with 0x49b930, which skips a mind-controlled
 *   crew member (+0x53d) that is not fighting (+0x99): the enemy's own pilot taken over at its station makes no combat
 *   task (HS oracle ps-fu6-mc: the second crew member sets off only when the pilot starts fighting).
 */
internal object GameplayParityPsFu6Checks {
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
            case("aiLastTargets") { aiLastTargets(main, container) }
            case("crewStuckDying") { crewStuckDying(main, container) }
            case("mindControlledNotFighting") { mindControlledNotFighting(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "ps-fu6 ok: " + results.joinToString(", ")
    }

    private fun aiLastTargets(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var seed = 31
        do {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 4, seed++)
            check(seed < 200) { "no generated pirate with a weapons system" }
        } while (state.enemy?.weapons == null || state.enemy!!.hardpoints.isEmpty() || state.enemy!!.isAutomated)
        val enemy = state.enemy!!
        val player = state.player
        val weapons = enemy.weapons!!
        for (hp in enemy.hardpoints) hp.weapon?.let { weapons.setWeaponPower(it, false) }
        for (hp in enemy.hardpoints) hp.weapon = null
        val burst = (state.blueprintManager["LASER_BURST_3"] as AbstractWeaponBlueprint).buildInstance(enemy)
        enemy.hardpoints[0].weapon = burst
        weapons.energyLevels = 8
        enemy.purchasedReactorPower = 25
        enemy.updateAvailablePower()
        burst.bindToWeaponsSystem(weapons)
        state.debugFlags.noEnemyFire.set = false

        // The AI powers the weapon; once charged it aims and fires its three shots.
        var fired = false
        for (i in 0 until 600) {
            player.health = player.maxHealth
            if (burst.isPowered && !burst.isFiring && !fired) burst.timeCharged = burst.chargeTime
            state.updateGameState(.05f)
            if (burst.isFiring) fired = true
            if (fired && !burst.isFiring) break
        }
        check(fired) { "the enemy's Burst Laser II never fired (powered ${burst.isPowered})" }
        val aim = burst.hsLastAim
        check(aim is List<*> && aim.size == 3) { "after the AI's volley lastTargets is $aim" }
        check(aim.all { it is xyz.znix.xftl.layout.Room && it.ship === player }) { "lastTargets point outside the player's ship: $aim" }

        // No reactor power left, so the AI cannot power it again.
        enemy.purchasedReactorPower = 0
        enemy.updateAvailablePower()
        weapons.setWeaponPower(burst, false)
        state.updateGameState(.05f)
        check(!burst.isPowered) { "the weapon was powered again" }
        check(burst.hsLastAim == null) { "an unpowered weapon kept lastTargets ${burst.hsLastAim}" }
        return "AI lastTargets kept after the volley"
    }

    private fun crewStuckDying(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS")
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        val player = state.player
        val enemy = state.enemy!!
        val teleporter = player.teleporter ?: error("PLAYER_SHIP_MANTIS without a teleporter")
        teleporter.damagedEnergyLevels = teleporter.energyLevels
        enemy.crew.toList().forEach { it.removeFromShip() }
        val target = enemy.rooms.first()
        val boarders = player.crew.filterIsInstance<LivingCrew>()
        check(boarders.size >= 2) { "PLAYER_SHIP_MANTIS starts with ${boarders.size} crew" }
        for (crew in boarders) {
            crew.removeFromShip()
            enemy.crew.add(crew)
            crew.jumpTo(enemy.findSpaceForCrew(target, AbstractCrew.SlotType.INTRUDER))
        }
        state.updatePlayerCrew()
        val dying = boarders[0]
        dying.health = 0f
        state.updateGameState(.05f)
        check(dying.currentAction == AbstractCrew.Action.DYING) { "the boarder at health 0 is ${dying.currentAction}" }
        check(CrewStuck.countCrew(enemy, true) == boarders.size) { "CountCrew left out the dying boarder" }
        val ending = state.shipUI.diagnosticDialogue
        var frames = 0
        while (dying.room.ship !== player && frames < 20) {
            if (state.shipUI.diagnosticDialogue === ending) ending?.selectOption(0)
            main.update(container, .05f)
            frames++
        }
        check(dying.room.ship === player && player.crew.contains(dying)) { "the dying boarder was left aboard the enemy" }
        check(dying.health <= 0f) { "the dying boarder came home alive" }
        check(boarders.drop(1).all { it.room.ship === player }) { "a living boarder was left aboard" }
        // The CREW_STUCK box pauses the game; once it is closed the death hold runs on at home.
        repeat(80) {
            state.shipUI.diagnosticDialogue?.selectOption(0)
            if (player.crew.contains(dying)) main.update(container, .05f)
        }
        check(!player.crew.contains(dying)) { "the dying boarder did not die at home" }
        return "CREW_STUCK brings a dying boarder home"
    }

    private fun mindControlledNotFighting(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 2, 71)
        val enemy = state.enemy!!
        state.debugFlags.noEnemyFire.set = true
        repeat(3) { state.updateGameState(.05f) }
        val crew = enemy.crew.filterIsInstance<LivingCrew>().firstOrNull { it.ownerShip === enemy && it.room.system != null }
            ?: return "mind-controlled crew (no enemy crew at a station)"
        xyz.znix.xftl.systems.MindControl.setMindControl(crew, true)
        state.updateGameState(.05f)
        check(crew.isMindControlled && crew.mode == AbstractCrew.SlotType.INTRUDER) { "the taken-over crew member is not an intruder" }
        check(crew.currentAction != AbstractCrew.Action.FIGHTING) { "the taken-over crew member fights at once" }
        val combat = enemy.crewAI.lastTasks.count { it is xyz.znix.xftl.ai.CombatTask && it.room === crew.room }
        check(combat == 0) { "a mind-controlled crew member that does not fight made $combat combat tasks" }
        xyz.znix.xftl.systems.MindControl.setMindControl(crew, false)
        return "a mind-controlled crew member counts as a boarder only while it fights"
    }
}