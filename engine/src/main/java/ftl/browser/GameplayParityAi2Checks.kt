package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.AIDoorControl
import xyz.znix.xftl.ai.AITask
import xyz.znix.xftl.ai.AIUtils
import xyz.znix.xftl.ai.CombatTask
import xyz.znix.xftl.ai.ExtinguishFireTask
import xyz.znix.xftl.ai.ManningTask
import xyz.znix.xftl.ai.RepairBreachTask
import xyz.znix.xftl.ai.RepairTask
import xyz.znix.xftl.ai.ShipAI
import xyz.znix.xftl.ai.nativeSystemId
import xyz.znix.xftl.ai.systemOrderKey
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.layout.BreachInstance
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.game.FlagshipBoss
import xyz.znix.xftl.sys.GameContainer

/**
 * Round two of the enemy AI port (BRIEF "Round 2", ai2): the residues of ShipAI::OnLoop (0x625c50), CombatAI
 * (0x62b200 targeting) and CrewAI (0x629d80) that have a known answer without playing a fight.
 *
 * The burst hand-out, the hack pulse gate and the mind control pool are checked through the pure helpers the AI
 * uses, since a scripted weapon draw, a landed probe and a mind control ship aren't available as fixtures.
 */
internal object GameplayParityAi2Checks {
    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        try {
            val targeting = targeting(main, container)
            val idle = idle(main, container)
            val stalemate = flagshipLeaves(main, container)
            val doors = doorControl(main, container)
            val save = badFighterSave(main, container)
            val helpers = aimHackAndMind(main, container)
            val problems = crewProblems(main, container)
            val gate = hostileGate(main, container)
            val latch = sabotageLatch(main, container)
            val breachOrder = breachOrderSave(main, container)
            return "ai2 ok: $targeting, $idle, $stalemate, $doors, $save, $helpers, $problems, $gate, $latch, " +
                    breachOrder
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
     * The Hard-mode candidate list: Functioning (0x63a960) goes through Powered, and the shields override of that
     * (0x630850) wants two bars; the oxygen test rounds the percentage (FUN_0049f420 / FUN_007458d0), so it flips
     * at 49.5% not 50%; the clonebay entry needs the clone to be progressing (CloneSystem::GetTimeToClone 0x644e20).
     */
    private fun targeting(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        val player = state.player
        val shields = player.shields!!

        check(!BrowserEnemyTargeting.shieldBarsFunction(0) && !BrowserEnemyTargeting.shieldBarsFunction(1)) {
            "Fewer than two bars of shields are functioning"
        }
        check(BrowserEnemyTargeting.shieldBarsFunction(2) && BrowserEnemyTargeting.shieldBarsFunction(3)) {
            "Two or more bars of shields aren't functioning"
        }
        repeat(12) { shields.decreasePower() }
        player.update(0f)
        check(shields.powerSupplied == 0) { "Fixture shields kept ${shields.powerSupplied} bars" }
        check(0 !in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Unpowered shields are functioning" }
        repeat(2) { shields.increasePower() }
        player.update(0f)
        check(shields.powerSupplied >= 2) { "Fixture shields didn't power up" }
        check(0 in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Powered shields aren't functioning" }

        player.rooms.forEach { it.oxygen = .4945f }
        player.update(0f)
        check(2 in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Oxygen at 49.45% is not low" }
        player.rooms.forEach { it.oxygen = .4955f }
        player.update(0f)
        check(2 !in BrowserEnemyTargeting.hardCandidates(enemy, player)) { "Oxygen at 49.55% is low" }

        var clone = "no clonebay fixture"
        for (seed in 100..200) {
            val candidate = spawn(state, seed)
            val clonebay = candidate.clonebay ?: continue
            clonebay.cloneProgress = 0f
            check(13 !in BrowserEnemyTargeting.hardCandidates(player, candidate)) { "Idle clonebay is a candidate" }
            clonebay.cloneProgress = .3f
            check(13 in BrowserEnemyTargeting.hardCandidates(player, candidate)) { "Cloning clonebay isn't a candidate" }
            clone = "clonebay progress"
            break
        }
        return "candidates: shields 2 bars, oxygen 49.5 rounding, $clone"
    }

    /**
     * The non-hostile half of ShipAI::OnLoop: the teleporter is made ready (TeleportSystem::ForceReady 0x648390
     * unlocks it), the super shield is drained (Shields::DrainSuperShield 0x631770) and no boarders are being sent.
     */
    private fun idle(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        var enemy = spawn(state, 71)
        var teleporter = "no teleporter fixture"
        for (seed in 100..200) {
            val candidate = spawn(state, seed)
            val pad = candidate.teleporter ?: continue
            pad.lockUnits(3)
            check(pad.isIonised) { "Fixture teleporter did not lock" }
            ShipAI(candidate, state.player).updateIdle()
            check(!pad.isIonised) { "An idle ship's teleporter stayed locked" }
            teleporter = "teleporter ready"
            enemy = candidate
            break
        }

        if (enemy.shields != null) {
            enemy.superShield = 3
            check(enemy.superShield == 3) { "Fixture super shield did not stick" }
            ShipAI(enemy, state.player).updateIdle()
            check(enemy.superShield == 0) { "An idle ship kept its super shield" }
        }

        enemy.crewAI.resetTeleportBudget()
        check(enemy.crewAI.teleporterIdle) { "The teleporter still has boarders to send" }
        return "idle: $teleporter, super shield drained, budget cleared"
    }

    /**
     * ShipAI::OnLoop (0x625c50) calls ShipManager::JumpLeave directly once the flagship's stalemate timer reaches
     * two minutes, without the engines and pilot a normal escape needs to charge the FTL.
     */
    private fun flagshipLeaves(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val sector = state.gameMap.generateSector(state.gameMap.sectors[7].first(), state)
        val boss = sector.bosses.filterIsInstance<FlagshipBoss>().first()
        boss.stage = 2
        state.currentBeacon = boss.beacon!!
        if (state.enemy == null) check(state.browserSpawnBossHere()) { "Flagship did not spawn" }
        val flagship = state.enemy!!
        state.shipUI.diagnosticDialogue?.selectOption(0)
        check(flagship.boss != null) { "The fixture enemy is not the flagship" }

        state.player.fuelCount = 0
        val ai = ShipAI(flagship, state.player)
        ai.update(1f)
        check(flagship.aiStalemateTimer == 1f) { "Flagship stalemate timer is ${flagship.aiStalemateTimer}" }

        flagship.aiStalemateTimer = 125f
        // Take the engines down so the FTL can't charge. The AI may power them again while it escapes, in which case
        // this only checks the timer.
        repeat(12) { flagship.engines!!.decreasePower() }
        ai.update(0f)
        check(flagship.escapeTimer == 0f) { "The flagship waits for its FTL after two minutes: ${flagship.escapeTimer}" }
        return "flagship leaves at 120 s without FTL"
    }

    /**
     * The enemy AI's door use: CrewAI::CloseAirlocks (0x626af0), the room seal of CheckForProblems (0x628900,
     * ShipManager::ManipulateDoors 0x49cd60), and the vent hook 0x626be0, which is handed the problem's position and
     * treats it as a room id.
     */
    private fun doorControl(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        check(ship.areDoorsOperable) { "Fixture doors aren't operable" }
        val doors = ship.doorsSystem!!

        val airlockRoom = ship.rooms.first { room -> room.doors.any { it.isAirlock } }
        val airlocks = airlockRoom.doors.filter { it.isAirlock }

        // Airlocks shut again while the doors work, and stay put while they don't.
        airlocks.forEach { it.open = true }
        doors.lockUnits(3)
        AIDoorControl.closeAirlocks(ship)
        check(airlocks.all { it.open }) { "Airlocks were closed with the doors system ionised" }
        doors.lockUnits(0)
        AIDoorControl.closeAirlocks(ship)
        check(airlocks.none { it.open }) { "Airlocks stayed open" }

        // A breached room is sealed, again only with working doors.
        val sealed = ship.rooms.first { it.doors.size >= 2 }
        sealed.doors.forEach { it.open = true }
        doors.lockUnits(3)
        AIDoorControl.sealRoom(ship, sealed)
        check(sealed.doors.all { it.open }) { "A room was sealed with the doors system ionised" }
        doors.lockUnits(0)
        AIDoorControl.sealRoom(ship, sealed)
        check(sealed.doors.none { it.open }) { "A breached room wasn't sealed" }

        // The hook: the problem's list position is the room number.
        val vented = ship.rooms.first { room ->
            room.doors.any { it.isAirlock } && ship.crew.none { it.room == room }
        }
        val index = ship.rooms.indexOf(vented)
        val ventDoors = vented.doors.filter { it.isAirlock }
        ship.rooms.forEach { it.oxygen = 1f }
        ship.doors.forEach { it.open = false }

        check(AIDoorControl.tryVent(ship, index, emptyMap())) { "An empty room with air wasn't vented" }
        check(ventDoors.all { it.open }) { "Venting left airlocks shut" }
        check(ship.doors.filter { it.isAirlock && it !in ventDoors }.none { it.open }) {
            "Venting opened the airlocks of another room"
        }

        // Crew standing in the room, or going there to work, stop it.
        ship.doors.forEach { it.open = false }
        val crew = ship.crew.first { it.mode == AbstractCrew.SlotType.CREW }
        val tasks = HashMap<AbstractCrew, AITask>()
        tasks[crew] = CombatTask(ship.crewAI, vented)
        check(!AIDoorControl.tryVent(ship, index, tasks)) { "A room with crew heading to it was vented" }
        check(ventDoors.none { it.open }) { "A refused vent opened the doors" }

        // So does a room with its air already gone.
        vented.oxygen = .05f
        check(!AIDoorControl.tryVent(ship, index, emptyMap())) { "A room with 5% air was vented" }

        // And a position that is no room, or a room without airlocks, gives false.
        val inner = ship.rooms.firstOrNull { room -> room.doors.none { it.isAirlock } }
        if (inner != null)
            check(!AIDoorControl.tryVent(ship, ship.rooms.indexOf(inner), emptyMap())) { "A room without airlocks was vented" }
        check(!AIDoorControl.tryVent(ship, ship.rooms.size + 3, emptyMap())) { "A position past the last room was vented" }
        return "doors: airlocks close, breach seal, vent hook index"
    }

    /**
     * CrewAI +0x68 and +0x69 survive a save, and an absent tag means both are clear. +0x68 is recomputed by every
     * CrewAI update (a mixed crew with an Engi or Energy member and no dropped penalty), so each case is one that an
     * update right after the load would leave as it is: the avoid flag is saved on a ship that really has a mixed crew.
     */
    private fun badFighterSave(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = crewedEnemy(state)
        for (race in listOf("engi", "human")) {
            val info = LivingCrewInfo.generateRandom(state.blueprintManager[race] as CrewBlueprint, state)
            enemy.addCrewMember(info, false)
        }

        enemy.crewAI.restoreBadFighterState(true, false)
        val avoiding = BrowserGameplayParityDiagnostics.reload(main, state).enemy!!.crewAI
        check(avoiding.avoidBadFighters && !avoiding.badFighterPenaltyIsDropped) {
            "The avoid flag changed in the save: ${avoiding.avoidBadFighters}/${avoiding.badFighterPenaltyIsDropped}"
        }

        // Dropped for good: it also clears the avoid flag, which a mixed crew can't set again.
        avoiding.restoreBadFighterState(false, true)
        val dropped = BrowserGameplayParityDiagnostics.reload(main, main.currentState as InGameState).enemy!!.crewAI
        check(!dropped.avoidBadFighters && dropped.badFighterPenaltyIsDropped) {
            "The dropped penalty changed in the save: ${dropped.avoidBadFighters}/${dropped.badFighterPenaltyIsDropped}"
        }

        dropped.restoreBadFighterState(false, false)
        val clear = BrowserGameplayParityDiagnostics.reload(main, main.currentState as InGameState).enemy!!.crewAI
        check(!clear.badFighterPenaltyIsDropped) { "A clear penalty flag came back set" }
        return "bad fighter flags save"
    }

    /**
     * The weapon pre-aim hands the targets out back to front (SetTargets 0x40d9a0 reverses the aim list, the weapon
     * fires its last target first); the hack pulse (0x62d710) needs an intact target system and a powered hacking
     * system; the mind control candidates (CrewMemberFactory 0x467250) are everyone aboard the target ship, or
     * aboard the AI's own ship while the system is hacked.
     */
    private fun aimHackAndMind(main: MainGame, container: GameContainer): String {
        // Single shots: three aim points, one each, handed out last first.
        check(ShipAI.handOutAimPoints(listOf("A", "B", "C"), 3, 1) == listOf("C", "B", "A")) { "Single hand-out" }
        // Two charges of a two-shot burst: one point per charge, each used twice.
        check(ShipAI.handOutAimPoints(listOf("A", "B"), 4, 2) == listOf("B", "B", "A", "A")) { "Burst hand-out" }
        // The weapon fires the last target it was given first, so the first aim point goes first.
        check(ShipAI.handOutAimPoints(listOf("A", "B", "C"), 3, 1).asReversed() == listOf("A", "B", "C")) {
            "The first aim point isn't fired first"
        }

        check(ShipAI.canStartPulse(false, 1) && !ShipAI.canStartPulse(false, 0) && !ShipAI.canStartPulse(true, 2)) {
            "Hack pulse gate"
        }

        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = spawn(state, 71)
        check(ShipAI.mindControlPool(false, enemy, state.player) === state.player.crew) { "Mind control pool" }
        check(ShipAI.mindControlPool(true, enemy, state.player) === enemy.crew) { "Hacked mind control pool" }
        return "burst hand-out, hack gate, mind pool"
    }

    private fun crewedEnemy(state: InGameState): Ship {
        var seed = 71
        while (true) {
            check(seed < 150) { "No crewed fixture ship" }
            val ship = spawn(state, seed++)
            if (!ship.isAutoScout && !ship.isAutomated && ship.crew.any { it.mode == AbstractCrew.SlotType.CREW } && ship.rooms.size >= 6)
                return ship
        }
    }

    /**
     * CrewAI::CheckForProblems (0x628900): rooms ascending with boarders then fires, breaches, then each system's
     * repair before its manning, systems in blueprint order; a manned system has no manning problem; the system id
     * of a breach problem is the one of the room numbered like the breach's position in the list.
     */
    private fun crewProblems(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = crewedEnemy(state)
        // The rooms' crew lists are rebuilt by the ship's update.
        enemy.update(0f)
        val rooms = enemy.rooms.filter { it.doors.isNotEmpty() }

        val boardedRoom = rooms.first { it.crew.isEmpty() }
        val info = LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state)
        enemy.addCrewMember(info, false, true)
            .also { it.jumpTo(enemy.findSpaceForCrew(boardedRoom, AbstractCrew.SlotType.INTRUDER)) }

        val fireRoom = rooms.last { it.crew.isEmpty() && it !== boardedRoom }
        fireRoom.oxygen = 1f
        fireRoom.fires[0] = FireInstance(fireRoom, 0)

        val own = nativeSystemId(enemy.rooms[0].system)
        val breachRoom = rooms.first { room ->
            room !== enemy.rooms[0] && room.system != null && nativeSystemId(room.system) != own
        }
        breachRoom.breaches[0] = BreachInstance(breachRoom, 0)

        val damaged = rooms.first { it.system != null && !it.system!!.damaged && it.system!!.undamagedEnergy > 1 }
        damaged.system!!.dealDamage(1, 0)

        enemy.isAutomated = false
        enemy.update(0f)
        enemy.crewAI.update()
        val tasks = enemy.crewAI.lastTasks
        check(tasks.any { it is ManningTask }) { "No manning problems at all" }

        fun firstIndex(test: (AITask) -> Boolean): Int = tasks.indexOfFirst(test)
        val boarders = firstIndex { it is CombatTask }
        val fires = firstIndex { it is ExtinguishFireTask }
        val breaches = firstIndex { it is RepairBreachTask }
        val repairs = firstIndex { it is RepairTask }
        val manning = firstIndex { it is ManningTask }
        check(boarders >= 0 && fires > boarders) { "Boarders ($boarders) don't come before fires ($fires)" }
        check(breaches > fires) { "Breaches ($breaches) don't come after fires ($fires)" }
        check(repairs > breaches) { "Repairs ($repairs) don't come after breaches ($breaches)" }
        check(manning > breaches) { "Manning ($manning) doesn't come after breaches ($breaches)" }

        // The systems in blueprint order, each one's repair before its manning.
        val ordered = tasks.filter { it is RepairTask || it is ManningTask }
        var lastKey = Int.MIN_VALUE
        for (task in ordered) {
            val key = systemOrderKey(task.room.system)
            check(key >= lastKey) { "System order broken at $task" }
            lastKey = key
        }
        for (task in tasks.filterIsInstance<RepairTask>()) {
            val repair = tasks.indexOf(task)
            val sameRoomManning = tasks.indexOfFirst { it is ManningTask && it.room === task.room }
            check(sameRoomManning == -1 || sameRoomManning > repair) { "Manning before its repair" }
        }

        // The breach problem names the system of room number 0, the breach's list position.
        val breach = tasks.filterIsInstance<RepairBreachTask>().single()
        check(breach.room === breachRoom) { "Breach problem in the wrong room" }
        check(breach.systemKey == own) { "Breach system id ${breach.systemKey}, expected the id of room 0's system $own" }

        // A manned system has no manning problem; an automated ship has all its systems manned.
        enemy.isAutomated = true
        enemy.crewAI.update()
        check(enemy.crewAI.lastTasks.none { it is ManningTask }) { "A manned system got a manning problem" }
        enemy.isAutomated = false
        return "problems: order, manned skip, breach system id"
    }

    /**
     * ShipAI::OnLoop (0x625c50) only runs CrewAI::OnLoop for a ship whose hostile flag is set: the player's ship
     * always, an enemy only while it is the one being fought. A ship that left the encounter leaves its crew alone.
     */
    private fun hostileGate(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val first = crewedEnemy(state)
        check(AIUtils.crewAiRuns(first) && AIUtils.crewAiRuns(state.player)) { "Hostile ships don't run their crew AI" }
        first.crewAI.update()
        val before = first.crewAI.lastTasks

        // The next ship replaces the first one, which is no longer in the fight.
        val second = spawn(state, 100)
        check(second !== first) { "Fixture ship wasn't replaced" }
        check(!AIUtils.crewAiRuns(first) && AIUtils.crewAiRuns(second)) { "Gate doesn't follow the encounter" }
        first.crewAI.update()
        check(first.crewAI.lastTasks === before) { "A ship out of the fight still ran its crew AI" }
        return "crew AI gate"
    }

    /**
     * A boarder keeps sabotaging the system it latched onto (CrewMember +0x68, set only while the crew AI runs)
     * after the fight is over, but one that never latched on doesn't start; the latch survives a save.
     */
    private fun sabotageLatch(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val player = state.player
        val enemy = spawn(state, 71)
        val spots = player.rooms.filter { room ->
            room.system != null && !room.system!!.broken && room.crew.isEmpty() && room.doors.isNotEmpty()
        }
        check(spots.size >= 2) { "Fixture ship has too few empty system rooms" }

        fun boarder(room: Room): LivingCrew {
            val info = LivingCrewInfo.generateRandom(state.blueprintManager["human"] as CrewBlueprint, state)
            return player.addCrewMember(info, false, true)
                .also { it.jumpTo(player.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER)) }
        }

        // An event boarder has no owner ship, so it is always at war with the ship it stands on.
        val latched = boarder(spots[0])
        repeat(5) { latched.update(0.1f) }
        check(latched.currentAction == AbstractCrew.Action.SABOTAGE) { "The boarder didn't start sabotaging" }
        check(latched.sabotagingSystem === spots[0].system) { "The boarder didn't latch onto the system" }

        // The fight ends and the boarder now belongs to the (present, but peaceful) other ship.
        state.browserEndHostility()
        latched.ownerShip = enemy
        check(latched.mode == AbstractCrew.SlotType.INTRUDER) { "Fixture boarder isn't an intruder" }
        check(!latched.standingShipHostile) { "Fixture boarder still counts as hostile" }
        repeat(5) { latched.update(0.1f) }
        check(latched.currentAction == AbstractCrew.Action.SABOTAGE) { "A latched boarder stopped with the fight" }

        val idle = boarder(spots[1])
        idle.ownerShip = enemy
        repeat(5) { idle.update(0.1f) }
        check(idle.sabotagingSystem == null && idle.currentAction != AbstractCrew.Action.SABOTAGE) {
            "A boarder with no fight started sabotaging"
        }

        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        // The loaded game is hostile again, so only the boarder that had latched on is looked at.
        val latchRoom = player.rooms.indexOf(spots[0])
        val inRoom = loaded.player.crew.filter { loaded.player.rooms.indexOf(it.room) == latchRoom }
        check(inRoom.any { it.sabotagingSystem != null }) {
            "The sabotage latch didn't survive the save: ${inRoom.map { "${it.mode}/${it.currentAction}" }}"
        }
        return "sabotage latch kept, not started, saved"
    }

    /** CrewAI +0x24 keeps the breaches in the order they were made; a save restores that instead of room order. */
    private fun breachOrderSave(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val enemy = crewedEnemy(state)
        val rooms = enemy.rooms.filter { it.doors.isNotEmpty() }
        val late = rooms.last()
        val early = rooms.first()
        check(late !== early) { "Fixture ship has one room" }

        // The later room is breached first, so creation order is the opposite of room order.
        late.breaches[0] = BreachInstance(late, 0)
        enemy.crewAI.update()
        early.breaches[0] = BreachInstance(early, 0)
        enemy.crewAI.update()

        val expected = "${enemy.rooms.indexOf(late)}:0,${enemy.rooms.indexOf(early)}:0"
        check(enemy.crewAI.breachOrderForSave() == expected) { "Breach order ${enemy.crewAI.breachOrderForSave()}" }

        val loaded = BrowserGameplayParityDiagnostics.reload(main, state).enemy!!
        check(loaded.crewAI.breachOrderForSave() == expected) {
            "The breach order changed in the save: ${loaded.crewAI.breachOrderForSave()}"
        }
        return "breach order saved"
    }
}
