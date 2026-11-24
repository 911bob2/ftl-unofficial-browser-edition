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
 * - CrewMember::SetMindControl (1.6.9 0x4728c0, 1.6.14 0x480c80): taken over or let go, a crew member leaves its slot and
 *   walks to the first free slot of its room for its new side (FindSlot(iRoomId, -1, true), MoveToRoom).
 * - CrewAI::UpdateCrewMember (1.6.9 0x603410): a fight (task 7) whose slot's room has no boarders left turns idle at
 *   once, walking or not.
 * - MindSystem::ShipDestroyed (1.6.14 0x62f860, the release 0x62f740 / Hyperspace's ReleaseCrew): the enemy's mind
 *   control lets go of the player's crew member it holds on the frame its ship's hull is below 1 (ShipManager::OnLoop
 *   0x4abd40 calls every system's ShipDestroyed; here ShipAI.updateIdle's release on that frame), and when the player's ship leaves it behind (WorldManager::
 *   ClearLocation 0x614d00 -> ShipManager 0x49c8c0); the crew member is the player's again at the next beacon.
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
            case("mindControlReslot") { mindControlReslot(main, container) }
            case("combatEndsWithoutBoarders") { combatEndsWithoutBoarders(main, container) }
            case("combatKeptForMindControlled") { combatKeptForMindControlled(main, container) }
            case("mindControlEndsOnLeaving") { mindControlEndsOnLeaving(main, container) }
            case("mindControlEndsOnDestruction") { mindControlEndsOnDestruction(main, container) }
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
    private fun mindControlReslot(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 2, 71)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        repeat(3) { state.updateGameState(.05f) }
        // A crew member standing where its side's first free slot is not (a station off slot 0, or a second crew
        // member of the room): staying put would then miss the slot SetMindControl's FindSlot gives.
        fun off(c: LivingCrew): Boolean {
            val free = c.room.firstFreeSlot(AbstractCrew.SlotType.INTRUDER) ?: return false
            val at = c.currentSlotPoint
            return at.room === c.room && (at.x != free.x || at.y != free.y)
        }
        val own = enemy.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === enemy }
        // Else one sent to its room's second cell (its slot is where it walks to; its station pulls it back later).
        val crew = own.firstOrNull { off(it) } ?: own.first { it.room.cellCount > 1 }.also { c ->
            check(c.setTargetRoom(c.room, c.room.slotToPoint(1))) { "no second slot to stand in" }
        }
        check(off(crew)) { "the crew member stands on the boarders' first free slot already" }
        val room = crew.room
        val intruderSlot = room.firstFreeSlot(AbstractCrew.SlotType.INTRUDER)
            ?: return "mind control re-slot (no free boarder slot)"
        xyz.znix.xftl.systems.MindControl.setMindControl(crew, true)
        check(crew.mode == AbstractCrew.SlotType.INTRUDER) { "the taken-over crew member is not on the boarders' side" }
        val taken = crew.currentSlotPoint
        check(taken.room === room && taken.x == intruderSlot.x && taken.y == intruderSlot.y) {
            "taken over, the crew member walks to $taken, not to the first free boarder slot $intruderSlot of its room"
        }
        repeat(60) { state.updateGameState(.05f) }
        val here = crew.room
        val crewSlot = here.firstFreeSlot(AbstractCrew.SlotType.CREW) ?: return "mind control re-slot (room full)"
        xyz.znix.xftl.systems.MindControl.setMindControl(crew, false)
        check(crew.mode == AbstractCrew.SlotType.CREW) { "the released crew member is not on the crew's side" }
        val freed = crew.currentSlotPoint
        check(freed.room === here && freed.x == crewSlot.x && freed.y == crewSlot.y) {
            "released, the crew member walks to $freed, not to the first free crew slot $crewSlot of its room"
        }
        return "mind control moves the crew member to its new side's first free slot"
    }

    private fun combatEndsWithoutBoarders(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 2, 71)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        repeat(3) { state.updateGameState(.05f) }
        val crew = enemy.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === enemy }
        val other = enemy.rooms.first { it !== crew.room && it.crew.isEmpty() && it.firstFreeSlot(AbstractCrew.SlotType.CREW) != null }
        check(other.crew.none { it.mode == AbstractCrew.SlotType.INTRUDER }) { "boarders in the empty room" }
        check(crew.setTargetRoom(other)) { "no path to room ${other.id}" }
        // Walking (an idle crew member's task ends anyway): the fight must end because its room has no boarders.
        repeat(2) { state.updateGameState(.05f) }
        check(crew.setTargetRoom(other)) { "no path to room ${other.id}" }
        enemy.crewAI.debugAssign(crew, xyz.znix.xftl.ai.CombatTask(enemy.crewAI, other))
        check(!crew.currentAction.isIdle && crew.room !== other) { "the crew member is not on its way to room ${other.id}" }
        state.updateGameState(.05f)
        val task = enemy.crewAI.taskOf(crew)
        check(task !is xyz.znix.xftl.ai.CombatTask) { "a crew member kept walking to fight in room ${other.id}, which has no boarders" }
        return "a fight ends once its room has no boarders"
    }

    // ShipManager::UpdateCrewmembers (0x49ee10) counts a mind-controlled crew member as an intruder by GetIntruder
    // (0x46c3e0), fighting or not, so a fight in its room goes on (HS oracle ps-c6-en mc: the resisting Zoltan stays).
    private fun combatKeptForMindControlled(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 2, 71)
        state.debugFlags.noEnemyFire.set = true
        val enemy = state.enemy!!
        repeat(3) { state.updateGameState(.05f) }
        val own = enemy.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === enemy }
        // Alone in its room, so it has nobody to fight there.
        val taken = own.firstOrNull { c -> own.none { it !== c && it.room === c.room } } ?: return "mind-controlled fight (no lone crew member)"
        val fighter = own.firstOrNull { it !== taken && it.room !== taken.room } ?: return "mind-controlled fight (one room of crew)"
        val room = taken.room
        xyz.znix.xftl.systems.MindControl.setMindControl(taken, true)
        check(taken.mode == AbstractCrew.SlotType.INTRUDER && taken.currentAction != AbstractCrew.Action.FIGHTING) {
            "the taken-over crew member is not a boarder yet, or fights at once"
        }
        check(fighter.setTargetRoom(room)) { "no path to room ${room.id}" }
        repeat(2) { state.updateGameState(.05f) }
        check(fighter.setTargetRoom(room)) { "no path to room ${room.id}" }
        enemy.crewAI.debugAssign(fighter, xyz.znix.xftl.ai.CombatTask(enemy.crewAI, room))
        check(!fighter.currentAction.isIdle && fighter.room !== room) { "the crew member is not on its way to room ${room.id}" }
        check(taken.room === room && taken.currentAction != AbstractCrew.Action.FIGHTING) { "the taken-over crew member left or fights" }
        state.updateGameState(.05f)
        check(enemy.crewAI.taskOf(fighter) is xyz.znix.xftl.ai.CombatTask) {
            "a fight in room ${room.id} ended although a mind-controlled crew member stands there"
        }
        return "a mind-controlled crew member keeps a fight in its room going, fighting or not"
    }

    private fun checkReleased(state: xyz.znix.xftl.game.InGameState, crew: LivingCrew, mind: xyz.znix.xftl.systems.MindControl, what: String, settled: Boolean = true) {
        check(!crew.isMindControlled && !mind.holds(crew)) { "$what, the player's crew member is still mind controlled" }
        check(crew.mode == AbstractCrew.SlotType.CREW && crew.playerControllable) { "$what, the player's crew member is on side ${crew.mode}" }
        // The ship's crew lists follow in its next loop.
        if (settled) check(state.player.intruders.none { it === crew }) { "$what, the player's crew member counts as a boarder on its own ship" }
    }

    /** The player's ship jumps away while the enemy's mind control holds one of its crew: free at the next beacon. */
    private fun mindControlEndsOnLeaving(main: MainGame, container: GameContainer): String {
        val (state, mind, crew) = GameplayParitySaveMigrationChecks.enemyHoldsOne(main, container)
            ?: return "mind control over a jump (no fixture pirate has room for a mind system)"
        val player = state.player
        player.fuelCount = 3
        player.ftlChargeProgress = 1f
        val from = state.currentBeacon
        val dest = from.neighbours.first()
        state.beginJumpOut(dest)
        // Under control all the way out: the original lets go only when the location is cleared.
        check(crew.isMindControlled) { "the crew member was let go as the jump started" }
        var frames = 0
        while (state.currentBeacon !== dest && frames++ < 200) main.update(container, .05f)
        check(state.currentBeacon === dest) { "no arrival after 10 s" }
        checkReleased(state, crew, mind, "at the next beacon", settled = false)
        repeat(20) {
            state.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, .05f)
        }
        checkReleased(state, crew, mind, "a second after the arrival")
        return "the enemy's mind control ends when the player's ship leaves it behind"
    }

    /** The enemy's ship is destroyed while its mind control holds a crew member of the player's: free at once. */
    private fun mindControlEndsOnDestruction(main: MainGame, container: GameContainer): String {
        val (state, mind, crew) = GameplayParitySaveMigrationChecks.enemyHoldsOne(main, container)
            ?: return "mind control over the enemy's destruction (no fixture pirate has room for a mind system)"
        val enemy = state.enemy!!
        enemy.health = 0
        state.updateGameState(.05f)
        check(state.enemy === enemy && !enemy.isGone) { "Set-up: the destroyed ship is gone at once" }
        checkReleased(state, crew, mind, "on the frame the enemy's hull reached 0", settled = false)
        var frames = 0
        while (state.enemy != null && frames++ < 200) {
            state.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, .05f)
        }
        check(state.enemy == null) { "the destroyed ship never went" }
        checkReleased(state, crew, mind, "once the destroyed ship has gone")
        return "the enemy's mind control ends with its ship"
    }
}
