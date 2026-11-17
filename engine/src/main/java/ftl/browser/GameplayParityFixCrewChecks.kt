package ftl.browser

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.CrewBlueprint
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.MainGame
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.sys.GameContainer
import xyz.znix.xftl.systems.MindControl

/**
 * Crew rules behind four player reports on release 2.1.1 (HS105, 107/110, 109, 111), each against the original:
 * - CrewMember::OnLoop (1.6.14 0x482a80, 1.6.9 0x4747f0) clears bMindControlled when the death animation is over, and
 *   CrewMember::Kill (0x47ba00) clears it too: a crew member that died mind controlled comes back from the clone bay
 *   on its own side, and its save never holds the flag.
 * - CrewMember::GetNewGoal (Hyperspace's rewrite of the original, CustomCrew.cpp): a sealed door stops a crew member
 *   only while `!bMindControlled || door->lockedDown.running`; the path search asks Door::IsSealed with the crew
 *   member's own ship (CrewMember::SetRoomPath 1.6.9 0x470fd0). Door::IsSealed (1.6.14 0x4705c0) is false for a door
 *   that is forced open, shut or not.
 * - CrewMember::OnLoop drops its crew target after every hit that dealt damage (SetCurrentTarget(0, 0) for a target
 *   without MultiShots), and ShipManager's crew loop (1.6.14 0x4aa670) draws the next one among the room's valid
 *   targets; whoever shares the slot is the target whatever was drawn.
 * - The same loop on a ship whose hostile flag is clear (ShipManager +0x10: WorldManager::OnLoop 0x61b930 clears it at
 *   the crew kill and the surrender): no crew targets and CrewMember::StopRepairing for every intruder, so no
 *   boarder goes on breaking a system and nobody fights; the player's ship always has the flag.
 */
internal object GameplayParityFixCrewChecks {
    private const val DT = 0.05f
    /** Where a case is, for the message of an exception that is not one of its own checks. */
    private var stage = ""

    fun run(main: MainGame, container: GameContainer): String {
        val rng = BrowserRandom.snapshot()
        val nativeRng = NativeRandom.snapshot()
        val results = ArrayList<String>()
        val failures = ArrayList<String>()
        fun case(name: String, block: () -> String) {
            stage = ""
            try {
                results += block()
            } catch (e: Throwable) {
                failures += "$name" + (if (stage.isEmpty()) "" else " ($stage)") + ": " + (e.message ?: e::class.simpleName)
            }
        }
        try {
            case("mindControlledDiesCloned") { mindControlledDiesCloned(main, container) }
            case("mindSystemCrewDiesCloned") { mindSystemCrewDiesCloned(main, container) }
            case("mindControlledDoors") { mindControlledDoors(main, container) }
            case("forcedOpenDoor") { forcedOpenDoor(main, container) }
            case("targetsSpread") { targetsSpread(main, container) }
            case("boardersAfterTheFight") { boardersAfterTheFight(main, container) }
            case("crewKillStopsSabotage") { crewKillStopsSabotage(main, container) }
        } finally {
            BrowserRandom.restore(rng)
            NativeRandom.restore(nativeRng)
        }
        check(failures.isEmpty()) { failures.joinToString(" | ") }
        return "fix-crew ok: " + results.joinToString(", ")
    }

    private fun human(state: InGameState): CrewBlueprint = state.blueprintManager["human"] as CrewBlueprint

    /** The ship's mind control system, added in the layout's own room when it has none. */
    private fun mindOf(ship: Ship): MindControl? {
        ship.mindControl?.let { return it }
        if (HS.active) {
            xyz.znix.xftl.hs.lua.LuaAdaptersWP5Ship.luaAddSystem(ship, 14)
        } else {
            val slot = ship.systemSlots.firstOrNull { it.system.name == "mind" && it.room.system == null } ?: return null
            slot.room.setSystem(slot)
            slot.room.system?.let { ship.browserPowerNewSystem(it) }
        }
        return ship.mindControl
    }

    /** Runs the game until [done], at most [seconds]. */
    private fun runUntil(state: InGameState, seconds: Float, done: () -> Boolean): Boolean {
        var steps = (seconds / DT).toInt()
        while (!done() && steps-- > 0) state.updateGameState(DT)
        return done()
    }

    /**
     * HS105 with the crew member's own flag (what a mind system and Multiverse's mind-control weapons both set,
     * CrewMember::SetMindControl): it dies mind controlled, waits in the clone bay and comes back.
     */
    private fun mindControlledDiesCloned(main: MainGame, container: GameContainer): String {
        var state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS_3")
        val bay = state.player.clonebay ?: return "clone of a mind-controlled crew member: no clone bay under these blueprints"
        // In a fight: out of combat Multiverse's instant clone (mv_scripts/instant-clone-heal.lua) finishes the clone
        // at once, and the save of the waiting body below would hold nobody.
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        repeat(3) { state.updateGameState(DT) }
        val crew = state.player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === state.player }
        stage = "taking over"
        MindControl.setMindControl(crew, true)
        state.updateGameState(DT)
        check(crew.isMindControlled && crew.mode == AbstractCrew.SlotType.INTRUDER && !crew.playerControllable) {
            "Set-up: the crew member is not under control"
        }
        // The control ends while it is dying: Hyperspace's SetMindControl hook leaves a dying crew member's flag alone.
        stage = "dying"
        crew.health = 0f
        state.updateGameState(DT)
        MindControl.setMindControl(crew, false)
        check(runUntil(state, 5f) { bay.queue.contains(crew) }) { "The dead crew member never reached the clone bay" }
        check(!crew.isMindControlled) { "Dead and waiting for its clone, the crew member is still mind controlled" }

        // The save of the waiting body, and the body after its clone.
        stage = "saving the waiting body"
        val queued = bay.queue.size
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        val loadedBay = state.player.clonebay!!
        stage = "loaded"
        val waiting = loadedBay.queue.singleOrNull() ?: error(
            "The loaded clone bay holds ${loadedBay.queue.size} bodies, the saved one held $queued " +
                "(crew aboard ${state.player.crew.size}, dead aboard ${state.player.crew.count { it.health <= 0f }})"
        )
        stage = "cloning"
        check(!waiting.isMindControlled) { "The saved body in the clone bay is mind controlled" }
        check(runUntil(state, 90f) { state.player.crew.contains(waiting) }) { "The crew member was never cloned" }
        repeat(20) { state.updateGameState(DT) }
        check(!waiting.isMindControlled && waiting.mode == AbstractCrew.SlotType.CREW && waiting.playerControllable) {
            "The clone is still mind controlled (mode ${waiting.mode})"
        }
        check(state.player.intruders.isEmpty()) { "The clone counts as a boarder on its own ship" }
        stage = "saving the clone"
        state = BrowserGameplayParityDiagnostics.reload(main, state)
        check(state.player.crew.none { (it as? LivingCrew)?.isMindControlled == true }) { "A saved clone is mind controlled" }
        return "a crew member that dies mind controlled is cloned free"
    }

    /** HS105 as the player met it: the enemy's mind system takes a crew member of the player's, it dies and is cloned. */
    private fun mindSystemCrewDiesCloned(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container, "PLAYER_SHIP_MANTIS_3")
        val bay = state.player.clonebay ?: return "enemy mind system and the clone bay: no clone bay under these blueprints"
        // A crewed pirate whose layout has a room for a mind system (or that came with one).
        var found: MindControl? = null
        var seed = 71
        while (found == null && seed < 131) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, seed++)
            val candidate = state.enemy!!
            if (candidate.isAutomated || candidate.isAutoScout || candidate.crew.isEmpty()) continue
            found = mindOf(candidate)
        }
        val mind: MindControl = found ?: return "enemy mind system and the clone bay: no fixture pirate has room for a mind system"
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        enemy.purchasedReactorPower = 25
        enemy.updateAvailablePower()
        if (mind.powerSelected == 0) mind.increasePower()
        enemy.update(0f)
        state.updateGameState(DT)
        if (mind.controlledCrew == null) {
            val pick = player.crew.filterIsInstance<LivingCrew>().first { !it.isMindControlResistant }
            mind.selectCrew(pick)
            mind.update(0f)
        }
        val crew = mind.controlledCrew ?: error("The enemy's mind system took nobody (power ${mind.powerSelected}, ready ${mind.ready})")
        check(crew.ownerShip === player && crew.isMindControlled && crew.mode == AbstractCrew.SlotType.INTRUDER) {
            "Set-up: the enemy's mind system does not hold a crew member of the player's"
        }
        crew.health = 0f
        check(runUntil(state, 5f) { bay.queue.contains(crew) }) { "The dead crew member never reached the clone bay" }
        check(!crew.isMindControlled) { "Waiting for its clone, the crew member is still mind controlled" }
        check(mind.controlledCrew !== crew && !mind.hsControlled.contains(crew)) { "The mind system still lists the dead crew member" }
        // Nobody is taken again while the clone grows.
        mind.damagedEnergyLevels = mind.energyLevels
        check(runUntil(state, 90f) { player.crew.contains(crew) }) { "The crew member was never cloned" }
        repeat(20) { state.updateGameState(DT) }
        check(!crew.isMindControlled && crew.mode == AbstractCrew.SlotType.CREW && crew.playerControllable) {
            "The clone is still under enemy control (mode ${crew.mode})"
        }
        val index = player.crew.indexOf(crew)
        val loaded = BrowserGameplayParityDiagnostics.reload(main, state)
        val again = loaded.player.crew[index] as LivingCrew
        check(!again.isMindControlled && again.playerControllable) { "The saved clone is under enemy control" }
        return "the enemy's mind-controlled victim is cloned free"
    }

    /** 107/110: every door but a locked-down one lets a mind-controlled crew member through, on either ship. */
    private fun mindControlledDoors(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        state.debugFlags.noCrewDamage.set = true
        val ship = state.player
        val enemy = state.enemy!!
        val doors = ship.doorsSystem ?: return "mind-controlled crew and doors: no doors system under these blueprints"
        doors.energyLevels = maxOf(doors.energyLevels, 2)
        doors.damagedEnergyLevels = 0
        ship.doors.forEach { it.open = false; it.update(0f) }
        check(doors.effectivePower >= 2) { "Set-up: the doors are level ${doors.effectivePower}" }

        // The player's own crew member on its own ship, mind controlled by the enemy.
        val own = ship.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === ship }
        ship.crew.filter { it !== own }.toList().forEach { it.removeFromShip() }
        val inner = ship.doors.filter { !it.isAirlock }
        check(inner.none { it.isLockedFor(own) }) { "Set-up: a door is locked for the ship's own crew" }
        MindControl.setMindControl(own, true)
        check(own.mode == AbstractCrew.SlotType.INTRUDER) { "Set-up: the crew member is not under control" }
        check(inner.none { it.isLockedFor(own) }) { "A blast door of its own ship is locked for a mind-controlled crew member" }
        check(inner.none { it.isSealedFor(own) }) { "The path search counts the ship's own doors as sealed for it" }

        // It walks the longest door route of the ship without stopping at a door.
        val start = own.room
        val far = ship.rooms.filter { it !== start }.maxByOrNull {
            ship.pathFinder.doorRoute(own, own.getPixelPositionCentre(), start, it)?.size ?: -1
        }!!
        check(own.setTargetRoom(far)) { "Set-up: no way to room ${far.id}" }
        var steps = 0
        while ((own.room !== far || own.pathingTarget != null) && steps++ < 1200) {
            own.update(DT)
            check(own.currentAction != AbstractCrew.Action.ATTACKING_DOOR) {
                "The mind-controlled crew member attacks a door of its own ship (room ${own.room.id})"
            }
        }
        check(own.room === far) { "The mind-controlled crew member was held up in room ${own.room.id} on its way to ${far.id}" }

        // A locked-down door holds it like anyone.
        val locked = inner.first()
        locked.lockdown()
        check(locked.isLockedFor(own) && locked.isSealedFor(own)) { "A locked-down door lets a mind-controlled crew member through" }
        MindControl.setMindControl(own, false)
        check(locked.isLockedFor(own)) { "A locked-down door lets the ship's crew through" }

        // A boarder is held by the blast doors, and passes once the player's mind control has it; the path search
        // goes on counting those doors as sealed for it (Door::IsSealed with its own ship).
        val shut = inner.first { it !== locked }
        val boarder = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, true)
        boarder.ownerShip = enemy
        check(shut.isLockedFor(boarder) && shut.isSealedFor(boarder)) { "Set-up: a blast door is open to a boarder" }
        MindControl.setMindControl(boarder, true)
        check(!shut.isLockedFor(boarder)) { "A blast door holds a boarder under the player's mind control" }
        check(shut.isSealedFor(boarder)) { "The path search stopped counting the door as sealed for the controlled boarder" }
        MindControl.setMindControl(boarder, false)
        check(shut.isLockedFor(boarder)) { "The released boarder walks through a blast door" }

        // The other ship: its own crew member under the player's mind control, behind its own blast doors. A crewed
        // pirate with a doors system is looked for among the fixture seeds.
        boarder.removeFromShip()
        var other = "the enemy's doors not checked (no crewed pirate with a doors system)"
        var seed = 72
        while (seed < 132) {
            state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, seed++)
            val candidate = state.enemy!!
            val theirs = candidate.doorsSystem ?: continue
            val enemyCrew = candidate.crew.filterIsInstance<LivingCrew>().firstOrNull { it.ownerShip === candidate } ?: continue
            theirs.energyLevels = maxOf(theirs.energyLevels, 2)
            theirs.damagedEnergyLevels = 0
            candidate.doors.forEach { it.open = false; it.update(0f) }
            val enemyInner = candidate.doors.filter { !it.isAirlock }
            if (theirs.effectivePower < 2 || enemyInner.isEmpty()) continue
            check(enemyInner.none { it.isLockedFor(enemyCrew) }) { "Set-up: an enemy door is locked for the enemy's own crew" }
            MindControl.setMindControl(enemyCrew, true)
            check(enemyCrew.mode == AbstractCrew.SlotType.INTRUDER) { "Set-up: the enemy crew member is not under control" }
            check(enemyInner.none { it.isLockedFor(enemyCrew) }) { "The enemy's blast doors hold its own mind-controlled crew member" }
            check(enemyInner.none { it.isSealedFor(enemyCrew) }) { "The path search counts the enemy's doors as sealed for its own crew member" }
            MindControl.setMindControl(enemyCrew, false)
            other = "the enemy's own doors too"
            break
        }
        return "mind-controlled crew walk through blast doors, not through a lockdown; $other"
    }

    /**
     * 107/110, the rest of Door::IsSealed (1.6.14 0x4705c0): a blast door broken down is forced open. Shut again at
     * once (a hacked door is, in every loop), it lets the boarder through until forcedOpen has run out.
     */
    private fun forcedOpenDoor(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val ship = state.player
        val doors = ship.doorsSystem ?: return "forced-open doors: no doors system under these blueprints"
        doors.energyLevels = maxOf(doors.energyLevels, 2)
        doors.damagedEnergyLevels = 0
        ship.doors.forEach { it.open = false; it.update(0f) }
        check(doors.effectivePower >= 2) { "Set-up: the doors are level ${doors.effectivePower}" }
        val shut = ship.doors.first { !it.isAirlock }
        val boarder = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, true)
        boarder.ownerShip = state.enemy!!
        check(shut.isLockedFor(boarder) && shut.isSealedFor(boarder)) { "Set-up: a blast door is open to a boarder" }
        var hits = 0
        while (!shut.open && hits++ < 500) shut.attackDoor()
        check(shut.open && shut.forcedOpen.running) { "Set-up: the blast door did not break after $hits hits" }
        shut.open = false
        check(!shut.isLockedFor(boarder) && !shut.isSealedFor(boarder)) { "A door forced open and shut again holds the boarder that broke it" }
        var forced = 0
        while (shut.forcedOpen.running && forced++ < 400) shut.update(DT)
        check(!shut.forcedOpen.running && forced > 20) { "Set-up: forcedOpen ran $forced steps" }
        shut.open = false
        check(shut.isLockedFor(boarder)) { "The blast door does not hold the boarder once forcedOpen is over" }
        return "a door broken down stays passable while forced open ($hits hits, $forced steps)"
    }

    /** A 2x2 room of the player's ship with nobody else aboard. */
    private fun emptyRoom(state: InGameState): Room {
        val ship = state.player
        ship.crew.toList().forEach { it.removeFromShip() }
        ship.crewShots.clear()
        return ship.rooms.first { it.width >= 2 && it.height >= 2 }
    }

    /**
     * 109: a crew member shooting at two hostile crew in its room spreads its shots over both; one sharing its slot
     * takes every punch.
     */
    private fun targetsSpread(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        val ship = state.player
        val out = ArrayList<String>()
        for (shooterIsPlayer in listOf(true, false)) {
            val room = emptyRoom(state)
            val shooter = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, !shooterIsPlayer)
            val a = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, shooterIsPlayer)
            val b = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, shooterIsPlayer)
            shooter.jumpTo(room, ConstPoint(0, 0))
            a.jumpTo(room, ConstPoint(1, 0))
            b.jumpTo(room, ConstPoint(1, 1))
            for (c in listOf(shooter, a, b)) c.nextTargetPos = null
            room.updateCrewInRoom(); ship.updateCrewReservedSlots()
            state.debugFlags.noCrewDamage.set = false
            a.stun(600f); b.stun(600f)

            val hits = ArrayList<Char>()
            for (i in 1..(60f / DT).toInt()) {
                shooter.update(DT); a.update(DT); b.update(DT)
                for (shot in ship.crewShots.toList()) if (!shot.update(DT)) ship.crewShots.remove(shot)
                if (a.health < a.maxHealth) { hits += 'a'; a.health = a.maxHealth }
                if (b.health < b.maxHealth) { hits += 'b'; b.health = b.maxHealth }
            }
            val onA = hits.count { it == 'a' }
            val onB = hits.size - onA
            val changes = hits.zipWithNext().count { it.first != it.second }
            check(hits.size >= 30) { "The shooter landed ${hits.size} shots in 60 s" }
            // 30 or more independent draws between two targets: all on one has a chance of 2^-29, fewer than 5 on one
            // of 3 in 100000.
            check(onA >= 5 && onB >= 5 && changes >= 5) {
                "Shots of a ${if (shooterIsPlayer) "player" else "boarder"} crew member: $onA on one, $onB on the other, " +
                    "$changes changes of target (${hits.joinToString("")})"
            }
            out += "$onA/$onB"
        }

        // Sharing a slot with one of them: every punch is that one's.
        val room = emptyRoom(state)
        val puncher = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, false)
        val near = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, true)
        val farOne = ship.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, true)
        puncher.jumpTo(room, ConstPoint(0, 0))
        near.jumpTo(room, ConstPoint(0, 0))
        farOne.jumpTo(room, ConstPoint(1, 1))
        for (c in listOf(puncher, near, farOne)) c.nextTargetPos = null
        room.updateCrewInRoom(); ship.updateCrewReservedSlots()
        near.stun(600f); farOne.stun(600f)
        var punches = 0
        for (i in 1..(20f / DT).toInt()) {
            puncher.update(DT); near.update(DT); farOne.update(DT)
            for (shot in ship.crewShots.toList()) if (!shot.update(DT)) ship.crewShots.remove(shot)
            if (near.health < near.maxHealth) { punches++; near.health = near.maxHealth }
            check(farOne.health == farOne.maxHealth) { "A punching crew member hit the crew member across the room" }
        }
        check(punches >= 10) { "$punches punches in 20 s" }
        state.debugFlags.noCrewDamage.set = false
        return "shots spread over the room's hostile crew (${out.joinToString(" and ")}), punches stay on the shared slot"
    }

    /**
     * 111, the rule: on an enemy that stopped being hostile a boarder does not go on with the system it was breaking,
     * starts none in another room and fights nobody; on the player's ship boarders go on whatever the other ship is.
     */
    private fun boardersAfterTheFight(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        check(state.isEnemyHostile) { "Set-up: the fixture enemy is not hostile" }
        val spots = enemy.rooms.filter { it.system != null && !it.system!!.broken }
        check(spots.size >= 2) { "Set-up: the fixture enemy has ${spots.size} working system rooms" }
        val defenders = enemy.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === enemy }
        enemy.crew.toList().forEach { it.removeFromShip() }
        val boarder = player.crew.filterIsInstance<LivingCrew>().first { it.ownerShip === player }
        boarder.removeFromShip()
        enemy.crew.add(boarder)
        boarder.jumpTo(enemy.findSpaceForCrew(spots[0], AbstractCrew.SlotType.INTRUDER))
        boarder.nextTargetPos = null
        // Rooms recount their occupants in their own update: without it the removed crew still stand in their lists.
        enemy.rooms.forEach { it.update(0f) }
        check(boarder.room === spots[0] && boarder.mode == AbstractCrew.SlotType.INTRUDER) { "Set-up: the boarder is not in the system room" }
        val first = spots[0].system!!
        val firstStart = first.damageProgress
        repeat(20) { boarder.update(DT) }
        check(boarder.currentAction == AbstractCrew.Action.SABOTAGE && first.damageProgress > firstStart) {
            "Set-up: the boarder does not sabotage a hostile ship's ${first.blueprint.name} (${boarder.currentAction})"
        }

        // The fight ends with the ship still there (a crew kill, a surrender taken).
        state.browserEndHostility()
        val progress = first.damageProgress
        val bars = first.damagedEnergyLevels
        repeat(100) { boarder.update(DT) }
        check(boarder.currentAction != AbstractCrew.Action.SABOTAGE) { "The boarder still sabotages after the fight" }
        check(first.damageProgress == progress && first.damagedEnergyLevels == bars) {
            "The ${first.blueprint.name} went on taking damage after the fight (${first.damageProgress} from $progress)"
        }
        // The player's order into another system room starts nothing either.
        boarder.jumpTo(enemy.findSpaceForCrew(spots[1], AbstractCrew.SlotType.INTRUDER))
        boarder.nextTargetPos = null
        enemy.rooms.forEach { it.update(0f) }
        val second = spots[1].system!!
        val secondStart = second.damageProgress
        repeat(40) { boarder.update(DT) }
        check(boarder.currentAction != AbstractCrew.Action.SABOTAGE && second.damageProgress == secondStart) {
            "A boarder ordered into another room of a ship that is not hostile breaks its ${second.blueprint.name}"
        }
        // Nor does anyone fight there.
        val defender = defenders.firstOrNull()
        if (defender != null) {
            enemy.crew.add(defender)
            val spot = if (spots[1].cellCount >= 2) spots[1] else enemy.rooms.first { it.cellCount >= 2 }
            boarder.jumpTo(spot, ConstPoint(0, 0))
            defender.jumpTo(spot, if (spot.width >= 2) ConstPoint(1, 0) else ConstPoint(0, 1))
            boarder.nextTargetPos = null; defender.nextTargetPos = null
            enemy.rooms.forEach { it.update(0f) }
            spot.updateCrewInRoom(); enemy.updateCrewReservedSlots()
            check(spot.crew.contains(boarder) && spot.crew.contains(defender)) { "Set-up: the two are not in one room" }
            val health = defender.health
            repeat(60) { boarder.update(DT); defender.update(DT) }
            check(boarder.currentAction != AbstractCrew.Action.FIGHTING && defender.currentAction != AbstractCrew.Action.FIGHTING &&
                defender.health == health) { "Crew fight aboard a ship that is not hostile (${boarder.currentAction}, ${defender.currentAction})" }
        }

        // The player's ship: boarders of a ship that stays at the beacon in peace go on (the flag of the player's
        // ship is always set, and its crew AI starts their sabotage).
        val home = player.rooms.first { it.system != null && !it.system!!.broken && it.crew.isEmpty() }
        val intruder = player.addCrewMember(LivingCrewInfo.generateRandom(human(state), state), false, true)
        intruder.ownerShip = enemy
        intruder.jumpTo(player.findSpaceForCrew(home, AbstractCrew.SlotType.INTRUDER))
        intruder.nextTargetPos = null
        player.rooms.forEach { it.update(0f) }
        val homeStart = home.system!!.damageProgress
        repeat(20) { intruder.update(DT) }
        check(intruder.currentAction == AbstractCrew.Action.SABOTAGE && home.system!!.damageProgress > homeStart) {
            "A boarder aboard the player's ship stopped with the other ship's hostility (${intruder.currentAction})"
        }
        return "no sabotage or fight aboard a ship that is not hostile; boarders aboard the player's ship go on"
    }

    /**
     * 111 as the player met it: the last enemy crew member dies with the player's boarders in a system room; the
     * system takes no more damage once the crew kill has ended the fight.
     */
    private fun crewKillStopsSabotage(main: MainGame, container: GameContainer): String {
        val state = BrowserGameplayParityDiagnostics.fresh(main, container)
        state.debugSpawnShip(state.eventManager.getShip("PIRATE"), Difficulty.NORMAL, 0, 71)
        state.debugFlags.noEnemyFire.set = true
        val player = state.player
        val enemy = state.enemy!!
        if (enemy.isAutomated || enemy.isAutoScout || enemy.boss != null) return "crew kill: the fixture enemy has no crew to kill"
        val room = enemy.rooms.filter { it.system != null && !it.system!!.broken }.maxByOrNull { it.system!!.energyLevels }
            ?: error("Set-up: no working system aboard the fixture enemy")
        val system = room.system!!
        val boarders = player.crew.filterIsInstance<LivingCrew>().filter { it.ownerShip === player }.take(2)
        for (crew in boarders) {
            crew.removeFromShip()
            enemy.crew.add(crew)
            crew.jumpTo(enemy.findSpaceForCrew(room, AbstractCrew.SlotType.INTRUDER))
            crew.nextTargetPos = null
        }
        state.updatePlayerCrew()
        val inRoom = boarders.filter { it.room === room }
        check(inRoom.isNotEmpty()) { "Set-up: no boarder fits the system room" }
        // The enemy crew die elsewhere, so the boarders are already at the system when the last one goes.
        for (crew in enemy.crew.toList()) if (crew !in boarders) crew.health = 0f
        var frames = 0
        while (state.isEnemyHostile && frames++ < 200) {
            main.update(container, DT)
        }
        check(!state.isEnemyHostile && state.enemy === enemy) { "The crew kill never ended the fight (${enemy.crew.size} aboard)" }
        // Close the crew-kill box and whatever follows, then let ten seconds pass.
        repeat(20) {
            state.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, DT)
        }
        val bars = system.damagedEnergyLevels
        val progress = system.damageProgress
        repeat(200) {
            state.shipUI.diagnosticDialogue?.selectOption(0)
            main.update(container, DT)
        }
        val still = inRoom.filter { it.room === room }
        check(still.none { it.currentAction == AbstractCrew.Action.SABOTAGE }) {
            "Boarders still sabotage the ${system.blueprint.name} after the crew kill"
        }
        check(system.damagedEnergyLevels == bars && system.damageProgress == progress) {
            "The ${system.blueprint.name} went on taking damage after the crew kill: bars $bars -> ${system.damagedEnergyLevels}, " +
                "progress $progress -> ${system.damageProgress}"
        }
        return "boarders stop breaking the ${system.blueprint.name} at the crew kill"
    }
}
