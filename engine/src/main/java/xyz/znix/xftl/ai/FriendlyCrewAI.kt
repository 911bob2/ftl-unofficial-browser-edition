package xyz.znix.xftl.ai

import xyz.znix.xftl.AbstractSystem
import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.Difficulty
import xyz.znix.xftl.layout.BreachInstance
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.*

/**
 * This AI controls the crew that are friendly to the ship.
 *
 * Friendly doesn't mean they're friendly to the player, this is used for
 * enemy crew aboard the enemy ship.
 *
 * Note this fairly closely matches the structure of vanilla's AI system,
 * as it's important that we match the subtle behaviour properly since edge
 * cases are important for tactics.
 */
class FriendlyCrewAI(private val ship: Ship) {
    // Set by ShipGenerator
    var initialCrewCount = 0

    // The boarders the last update wanted to send, and how many fit on the teleporter: a send wave
    // ends when the whole budget goes out at once (ShipAI 0x6257d0).
    private var sendBudget = 0
    private var sendCount = 0

    // ShipAI 0x6257d0 has decided once: on the first frame it runs before ShipAI::OnLoop, with CrewAI +0x5c still
    // zero and the budget ShipAI::OnInit (0x625410) gave. The idle half of ShipAI::OnLoop clears that budget.
    private var decided = false

    /**
     * CrewAI +0x8: nothing is being fought, repaired or extinguished, so even slightly hurt crew head for the
     * medbay. The ship AI reads this to decide whether the medbay needs power.
     */
    var isCalm = false
        private set

    /**
     * CrewAI +0x6: a hurt crewmember couldn't be sent to the medbay (or is healing there already) the last time
     * CrewAI 0x6270f0 looked. It raises the medbay's priority, and is only cleared when a hurt crewmember is sent.
     */
    var medbayBusy = false
        private set

    /** CrewAI +0x5c is zero: no boarders are being sent, so the teleporter's priority is the lower one. */
    val teleporterIdle: Boolean get() = sendCount == 0

    private val aiCrew = ArrayList<AbstractCrew>()

    /** The problems the last update found, in the order CheckForProblems lists them and before anyone took one. */
    internal var lastTasks: List<AITask> = emptyList()
        private set

    /**
     * CrewAI +0x24: the breaches of the ship in the order they appeared. The engine doesn't keep their order, so it's
     * rebuilt here from what each update sees (a new breach goes on the end, a repaired one drops out), and saved
     * with the ship ([breachOrderForSave]).
     */
    private val breachList = ArrayList<BreachInstance>()

    /** The saved breach order (room number, cell) that [updateBreachList] applies once the rooms are loaded. */
    private var pendingBreachOrder: List<Pair<Int, Int>>? = null

    private var assignments = HashMap<AbstractCrew, AITask>()
    private var newAssignments = HashMap<AbstractCrew, AITask>()

    /**
     * CrewAI+0x68: with a mixed crew (Engi or Zoltan alongside other
     * races), crew that are bad at combat get the lowest priority for
     * fighting and boarding. CrewAI::OnLoop recomputes it every update
     * unless it has been dropped for good ([badFighterPenaltyDropped],
     * CrewAI+0x69), which happens the first time a fight or boarding task
     * couldn't be staffed because of it.
     */
    var avoidBadFighters = false
        private set
    private var badFighterPenaltyDropped = false

    fun update() {
        // CrewAI::OnLoop (0x629d80) only runs in the hostile half of ShipAI::OnLoop (0x625c50): an enemy ship that
        // isn't fighting its opponent leaves its crew where they are.
        if (!AIUtils.crewAiRuns(ship))
            return
        val enemyAi = !ship.isPlayerShip

        if (!decided) {
            decided = true
            if (enemyAi)
                sendAtArrival()
        }

        // The breach list is kept up to date even when nobody is left to work on it.
        updateBreachList()

        // CrewAI::CloseAirlocks (0x626af0) is the first thing the enemy flag adds to CrewAI::OnLoop.
        if (enemyAi)
            AIDoorControl.closeAirlocks(ship)

        // Update the list of AI-controllable crew
        aiCrew.clear()
        for (crew in ship.crew) {
            // CrewAI::CheckForProblems and its callers skip anything with a true CrewMember::IsDrone (vtable +0x30),
            // so drones never count as crew, take tasks or fill a task slot. A drone body only picks what to work
            // on by itself (CrewAI::UpdateCrewMember 0x627df0 reads it like any other crewmember).
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            if (crew.mode != AbstractCrew.SlotType.CREW)
                continue

            // Don't set tasks for dying crew. Vanilla has a separate task
            // for dying, so ignoring them does the same thing.
            if (crew.currentAction == AbstractCrew.Action.DYING)
                continue

            // We have to check playerControllable for mind-controlled crew.
            if (crew.playerControllable)
                continue

            // Ignore crew in the flagship's artillery rooms
            // We assume that any crew can path to any room, and these crew
            // violate that assumption, which can lead to tasks getting locked up.
            if (isRoomDisabled(crew.room))
                continue

            aiCrew += crew
        }

        // Stop here if there's no crew to control, to save a tiny bit of CPU.
        if (aiCrew.isEmpty())
            return

        val hasBadRace = aiCrew.any { it.codename == "engi" || it.codename == "energy" }
        val hasOtherRace = aiCrew.any { it.codename != "engi" && it.codename != "energy" }
        avoidBadFighters = hasBadRace && hasOtherRace && !badFighterPenaltyDropped

        // Build a new list of tasks each update, rather than having one persistent list.
        // This means that an arbitrary number of crew can be assigned to the same task,
        // if you can find a way to get them into that state.
        // See doc/crew-ai for more information.
        //
        // CrewAI::CheckForProblems (0x628900) builds the list in a fixed order, and tasks are handed out in that
        // order: for each room in order the boarders (type 7) and then the fires (type 2), the teleporter (8), the
        // breaches (3), and finally for each system, in system id order, its repair (1) and then its manning (0).

        val tasks = ArrayList<AITask>()

        // CrewAI +8: CheckForProblems clears it as soon as a room has boarders, any fire, a breach or a damaged
        // system, whoever is already working on it.
        var problemFound = false

        for (room in ship.rooms) {
            if (isRoomDisabled(room))
                continue

            // One task for each intruder
            for (crew in room.crew) {
                if (crew.mode != AbstractCrew.SlotType.INTRUDER)
                    continue

                problemFound = true
                tasks += CombatTask(this, room)
            }

            if (room.fires.any { it != null }) {
                problemFound = true

                if (room.oxygen > FireInstance.OXYGEN_CUTOFF) {
                    tasks += ExtinguishFireTask(this, room)
                    tasks += ExtinguishFireTask(this, room)
                }
            }
        }

        // Add tasks for teleporting, but don't teleport mind-controlled enemy
        // crew out in an attempt to board the enemy ship.
        val teleporter = ship.teleporter
        var teleportSend = false
        var teleportForceRecv = false
        if (teleporter != null && !ship.isPlayerShip && ship.sys.getEnemyOf(ship)?.superShield == 0) {
            // ShipAI::OnLoop (0x625c50): the budget is the crew above the ones to keep, and it drops to zero once
            // two send waves have completed.
            // Don't create more tasks than the teleporter can fit (CrewAI +0x5c).
            var budget = getNumMoreBoarders().coerceAtLeast(0)
            if (ship.aiTeleportWaves >= 2 && !ship.sys.debugFlags.noTeleportLimit.set)
                budget = 0
            val toSend = budget.coerceAtMost(teleporter.room!!.cellCount)
            sendBudget = budget
            sendCount = toSend

            for (i in 1..toSend) {
                tasks += TeleportingTask(this, teleporter.room!!)
            }
            if (toSend > 0)
                teleportSend = true

            if (shouldEvacuate(teleporter))
                teleportForceRecv = true
        }

        // One problem for each breach, in the order the breaches were made (CrewAI +0x24, the list CheckForProblems
        // walks). A breach somebody is already on only goes to the secondary list, so it's only added while nobody is
        // on it. Native quirk, ported literally: the system id of a breach problem is the id of the system in the room
        // whose NUMBER is the breach's position in that list, not the breach's own room; crew remember it as their
        // task's system, so the problem counts as unattended whenever the list shifts under a working crewmember.
        // CheckForProblems also shuts every door of a breached room on an enemy ship (ShipManager::ManipulateDoors).
        for ((index, breach) in breachList.withIndex()) {
            val room = breach.room
            if (isRoomDisabled(room))
                continue

            problemFound = true
            if (enemyAi)
                AIDoorControl.sealRoom(ship, room)

            val key = nativeSystemId(ship.rooms.getOrNull(index)?.system).let { if (it == Int.MAX_VALUE) -1 else it }
            if (aiCrew.any { val task = assignments[it]; task is RepairBreachTask && task.room == room && task.systemKey == key })
                continue

            tasks += RepairBreachTask(this, room, key)
        }

        // The systems go in the order ShipManager::CreateSystems (0x4a96f0) added them: the blueprint's systemList in
        // file order (the vector at ShipBlueprint +0x238, pushed by the parser 0x60c7a0), then any the ship got later
        // in system id order.
        for (room in ship.rooms.sortedBy { systemOrderKey(it.system) }) {
            if (isRoomDisabled(room))
                continue

            val sys = room.system ?: continue

            if (sys.damaged) {
                problemFound = true
                tasks += RepairTask(this, room)
            }

            // Create manning tasks for all systems, even those where crew won't
            // do anything - those just have very low priorities, and serve
            // to disperse thee crew throughout the ship.
            // CheckForProblems (0x628900) skips a system that is already manned (ShipSystem +0x45, set by a crewmember
            // manning it, and for every system of an automated ship).
            if (!AIUtils.isManned(sys))
                tasks += ManningTask(this, room)
        }

        // Figure out if the ship is calm, used for healing crew
        // at nearly-full health.
        val isShipCalm = !problemFound

        isCalm = isShipCalm

        lastTasks = ArrayList(tasks)

        // Remove the tasks currently being performed, so we don't
        // assign the same task to multiple crew (unless multiple
        // of it are added to the tasks list).
        // At the same time, this removes any missing crew from
        // the assignments map.
        // CrewAI::OnLoop's last step (0x6270f0) then sends hurt crew to the medbay: below a quarter of their
        // health, or to full while nothing is going on. It only looks at crew that aren't already healing.
        val healingThreshold = if (isShipCalm) 0.99f else 0.25f
        val medbay = ship.medbay
        for (crew in aiCrew) {
            // Tasks can automatically reassign their crew, usually back to
            // idle. This matches the CrewAI::UpdateCrewMember logic.
            var task = assignments[crew]?.nextTask(crew)

            if (crew is LivingCrew && crew.health / crew.maxHealth < healingThreshold) {
                if (medbay != null) {
                    if (task !is HealingTask && medbay.powerSupplied > 0 &&
                        !AIUtils.isDangerous(crew, medbay.room!!)
                    ) {
                        // Try to start pathfinding there immediately, and if there's
                        // no space, keep our current task so we can continue
                        // doing something useful while we wait for whoever is in
                        // there now to heal up and leave.
                        val setPath = crew.setTargetRoom(medbay.room!!)
                        if (setPath)
                            task = HealingTask(this, medbay.room!!)
                        medbayBusy = false
                    } else {
                        medbayBusy = true
                    }
                }
            }

            if (task == null)
                continue

            tasks.remove(task)
            newAssignments[crew] = task
        }
        // Re-use the previous assignments hashmap, being paranoid about the GC.
        val oldAssignments = assignments
        assignments = newAssignments
        oldAssignments.clear()
        newAssignments = oldAssignments

        // Assign each task, if possible.
        // CrewAI::OnLoop (0x629d80) offers every fire and boarder problem to the airlock vent hook (0x626be0) on an
        // enemy ship first, giving it the problem's position in the list.
        for ((index, task) in tasks.withIndex()) {
            if (enemyAi && (task is CombatTask || task is ExtinguishFireTask) &&
                AIDoorControl.tryVent(ship, index, assignments)
            ) {
                continue
            }

            assignTask(task)
        }

        // Make sure all the crew are pathfinding to their target room.
        for ((crew, task) in assignments) {
            if (crew.room == task.room)
                continue

            if (crew.pathingTarget?.room == task.room)
                continue

            crew.setTargetRoom(task.room)
        }

        // If our boarders on the enemy ship are low on health, teleport them back.
        // Do this first, so receiving low-health crew takes priority over
        // sending more when the teleporter comes off cooldown.
        doTeleportRecv(teleportForceRecv)

        // If the crew are ready, activate the teleporter.
        if (teleportSend) {
            doTeleportSend()
        }
    }

    /**
     * Returns true if a given crewmember will be assigned to a manning task
     * in the next update. This is used to detect if two crew are both manning
     * a system.
     *
     * Since it's called while [newAssignments] is being written, it needs to
     * use that to check, otherwise all crew would have their manning tasks
     * cancelled.
     */
    fun hasAlreadyAssignedManning(crew: AbstractCrew): Boolean {
        return newAssignments[crew] is ManningTask
    }

    private fun assignTask(task: AITask) {
        // Matches up to CrewAI::AttemptToAssign in FTL.
        // I really don't like copying the structure of FTL's executable, but
        // it's hard not to if we're going to get the exact same behaviour.

        // Find the most suitable crewmember for this task.
        // This is based on the priority of the crewmember's current task,
        // so we pick the crewmember who is currently doing the least
        // important task.
        // This also accounts for the danger a crewmember faces in a room.
        var bestCrew: AbstractCrew? = null
        var bestCrewPriority = -1

        for (crew in aiCrew) {
            // Exclude crew that can't do a task
            if (!task.isSuitable(crew))
                continue

            val currentTask = assignments[crew]

            // If a crewmember is already performing this task, don't
            // re-assign them. This lets multiple crew perform identical
            // tasks, for example fighting fires has two tasks.
            val currentPriority: Int
            if (currentTask != null) {
                currentPriority = currentTask.priorityFor(crew)
            } else if (AIUtils.isDangerous(crew, crew.room)) {
                // The room danger does affect idle crew
                currentPriority = 100
            } else {
                currentPriority = 1000
            }

            val newPriority = task.priorityFor(crew)

            // Skip crew who are doing something more important.
            // Don't reject crew with an equal priority here, as that'd
            // change the instant assignment stuff.
            if (currentPriority < newPriority)
                continue

            // CrewAI::AttemptToAssign (0x627880): crew already repairing,
            // fighting a fire or breach, or fighting in the task's room
            // take the task over at once.
            if (currentTask != null && currentTask != task && currentTask.room == task.room &&
                (currentTask is RepairTask || currentTask is ExtinguishFireTask ||
                        currentTask is RepairBreachTask || currentTask is CombatTask)
            ) {
                assignments[crew] = task
                return
            }

            // At this point, ignore crew whose current task is the same
            // priority as the new task, to avoid crew ping-ponging between
            // equally important tasks.
            if (currentPriority == newPriority)
                continue

            // Keep track of the most suitable crew, picking the first
            // one if there's a tie.
            if (currentPriority > bestCrewPriority) {
                bestCrewPriority = currentPriority
                bestCrew = crew
            }
        }

        // Couldn't find a suitable crewmember? A fight or boarding left
        // unstaffed by the bad-at-combat penalty drops it and retries
        // (CrewAI 0x627880).
        if (bestCrew == null) {
            if (avoidBadFighters && (task is CombatTask || task is TeleportingTask)) {
                avoidBadFighters = false
                badFighterPenaltyDropped = true
                assignTask(task)
            }
            return
        }

        // Assign this task to the chosen crew.
        assignments[bestCrew] = task
    }

    /** The breach list as "room:cell" pairs in creation order, or null when there are no breaches (optional tag). */
    fun breachOrderForSave(): String? {
        updateBreachList()
        if (breachList.isEmpty())
            return null
        return breachList.joinToString(",") { "${ship.rooms.indexOf(it.room)}:${it.slot}" }
    }

    /** Restores the order [breachOrderForSave] wrote; a missing or damaged tag leaves the room order. */
    fun restoreBreachOrder(text: String?) {
        pendingBreachOrder = text?.split(",")?.mapNotNull { entry ->
            val parts = entry.split(":")
            val room = parts.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null
            val slot = parts.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            room to slot
        }
    }

    private fun updateBreachList() {
        breachList.removeAll { breach -> breach.room.breaches[breach.slot] !== breach }

        val saved = pendingBreachOrder
        if (saved != null) {
            pendingBreachOrder = null
            for ((roomNumber, slot) in saved) {
                val breach = ship.rooms.getOrNull(roomNumber)?.breaches?.getOrNull(slot)
                if (breach != null && breach !in breachList)
                    breachList += breach
            }
        }
        for (room in ship.rooms) {
            for (breach in room.breaches) {
                if (breach != null && breach !in breachList)
                    breachList += breach
            }
        }
    }

    private fun isRoomDisabled(room: Room): Boolean {
        // Don't set any tasks in inaccessible rooms, since crew can't go there.
        // This prevents flagship crew from getting stuck when sent to the artillery rooms.
        return room.doors.isEmpty()
    }

    private fun getNumMoreBoarders(): Int {
        // See doc/ship-ai for information about this.

        // Find the number of boarders to send, from our initial crew.
        val sendTotal = when (ship.type.boardingStrategy) {
            BoardingStrategy.NONE -> return 0
            BoardingStrategy.SABOTAGE -> initialCrewCount / 2
            BoardingStrategy.INVASION -> initialCrewCount * 3 / 4
        }

        // Use this to figure out how many crew to keep behind.
        val toKeep = initialCrewCount - sendTotal

        // Now use this to calculate how many crew we can safely send.
        // This can be negative if we should recall some boarders, due to
        // crew on our ship dying.
        // ShipAI::OnLoop counts the crew with ShipManager 0x49c180(false): everyone aboard who isn't an intruder,
        // dead or a drone, so the flagship's crew shut in its artillery rooms count too (they only stay out of the
        // task list).
        val aboard = ship.crew.count {
            it !is AbstractIndoorsDrone.Pawn && it.mode == AbstractCrew.SlotType.CREW &&
                it.currentAction != AbstractCrew.Action.DYING && !it.playerControllable
        }
        return aboard - toKeep
    }

    /**
     * The first frame of a fight (CompleteShip::OnLoop 0x409ee0 decides before ShipAI::OnLoop): nothing is wanted yet,
     * so CrewAI::ReadyForTeleport (0x626340) holds and whoever already stands on the teleporter is sent to a random
     * room (CompleteShip 0x409ae0) against the OnInit budget, before any task moves them.
     */
    private fun sendAtArrival() {
        val teleporter = ship.teleporter ?: return
        val enemy = ship.sys.getEnemyOf(ship) ?: return
        val budget = when (ship.type.boardingStrategy) {
            BoardingStrategy.NONE -> 0
            BoardingStrategy.SABOTAGE -> initialCrewCount / 2
            BoardingStrategy.INVASION -> initialCrewCount * 3 / 4
        }
        if (budget <= 0 || ship.aiTeleportWaves >= 2 && !ship.sys.debugFlags.noTeleportLimit.set)
            return
        val sent = teleporter.sendAtArrival(enemy.rooms.random(ftl.browser.BrowserRandom))
        // 0x6257d0: the send takes the crew on the pad off the budget; using it all up completes a wave.
        if (sent > 0 && budget - sent < 1)
            ship.aiTeleportWaves++
    }

    private fun doTeleportSend() {
        val teleporter = ship.teleporter!!
        val enemy = ship.sys.getEnemyOf(ship) ?: return

        // Check that there aren't any crew still pathing to the teleporter
        for (crew in aiCrew) {
            // Stop if any crew are currently being teleported
            if (crew.currentAction == AbstractCrew.Action.TELEPORTING)
                return

            if (assignments[crew] !is TeleportingTask)
                continue

            // Don't teleport until all the crew are ready
            if (crew.standingPosition?.room != teleporter.room)
                return
        }

        teleporter.selectTeleportAction(true, enemy.rooms.random(ftl.browser.BrowserRandom))
    }

    private fun doTeleportRecv(teleportForceRecv: Boolean) {
        // See doc/ship-ai for this logic

        val teleporter = ship.teleporter ?: return

        val enemy = ship.sys.getEnemyOf(ship) ?: return
        val boarders = enemy.crew.filterIsInstance<LivingCrew>().filter { it.mode == AbstractCrew.SlotType.INTRUDER }

        val minHealthFraction = when (ship.type.boardingStrategy) {
            BoardingStrategy.INVASION -> -1f // Never teleport back
            else -> 0.25f
        }

        for (crew in boarders) {
            val healthFraction = crew.health / crew.maxHealth

            if (healthFraction > minHealthFraction && !teleportForceRecv)
                continue

            // Teleport the crew from this room
            teleporter.selectTeleportAction(false, crew.room)
            return
        }
    }

    /**
     * ShipAI::ShouldEvacuate (0x6254f0): the boarders are called back when a sabotage ship is about to escape or has
     * lost more than two systems entirely, when it surrendered, or when its teleporter is hacked.
     */
    private fun shouldEvacuate(teleporter: Teleporter): Boolean {
        if (ship.isDead)
            return false

        if (ship.type.boardingStrategy == BoardingStrategy.SABOTAGE) {
            val escapeTimer = ship.escapeTimer
            if (escapeTimer != null && escapeTimer < 15f)
                return true
            if (ship.aiDestroyedSystems > 2)
                return true
        }

        return ship.aiSurrendered || teleporter.isHackActive
    }

    fun onCrewTeleportedOut() {
        // Only called when someone has actually been teleported (anything that blocked teleporting, eg cloaking or
        // a super shield, doesn't get here). Like ShipAI 0x6257d0, a send that takes the whole budget completes a
        // wave, and two waves are all the AI sends.
        if (sendBudget - sendCount < 1)
            ship.aiTeleportWaves++
    }

    /**
     * CrewAI::OnLoop's (0x629d80) last step for a ship that isn't hostile any more, and the ShipAI::OnLoop idle half
     * (0x625c50): no boarders are being sent, so the teleporter budget and the count of boarders going over are
     * cleared (FUN_00626360(0), CrewAI +0x5c).
     */
    fun resetTeleportBudget() {
        sendBudget = 0
        sendCount = 0
        decided = true
    }

    /**
     * Restores the two flags CrewAI keeps between updates (+0x68 and +0x69, see [avoidBadFighters]), so a reloaded
     * game doesn't hand out fights differently for the first update.
     */
    fun restoreBadFighterState(avoidBadFighters: Boolean, penaltyDropped: Boolean) {
        this.avoidBadFighters = avoidBadFighters
        badFighterPenaltyDropped = penaltyDropped
    }

    /** CrewAI +0x69: the penalty for bad fighters has been given up for good. */
    val badFighterPenaltyIsDropped: Boolean get() = badFighterPenaltyDropped

    enum class BoardingStrategy {
        NONE,
        SABOTAGE, // Most ships
        INVASION, // Flagship
        ;
    }
}

abstract class AITask(val ai: FriendlyCrewAI, val room: Room) {
    /**
     * Find this task's priority.
     */
    fun priorityFor(crew: AbstractCrew): Int {
        if (AIUtils.isDangerous(crew, room))
            return 100

        return priorityWithoutDanger(crew)
    }

    // Doesn't include the danger check
    protected abstract fun priorityWithoutDanger(crew: AbstractCrew): Int

    /** CrewAI::PrioritizeTask (0x627450) without a crew member (-1): what the drone-body AI compares. */
    fun basePriority(crew: AbstractCrew): Int = priorityWithoutDanger(crew)

    open fun nextTask(crew: AbstractCrew): AITask? {
        if (crew.currentAction.isIdle) {
            return null
        }

        return this
    }

    // Have one equals/hashCode for all our subclasses

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AITask) return false
        if (other.javaClass != javaClass) return false

        return room == other.room
    }

    override fun hashCode(): Int {
        return room.hashCode() xor javaClass.hashCode()
    }

    abstract fun isSuitable(crew: AbstractCrew): Boolean

    override fun toString(): String {
        return "${javaClass.simpleName}(id=${room.id},sys=${room.system?.codename})"
    }
}

class RepairTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // Oxygen gets a very high priority when it's low
        if (room.system is Oxygen && room.ship.averageOxygen < 0.25f)
            return 0

        // Shields is special
        if (room.system is Shields)
            return 1

        return AIUtils.systemPriority(room.system!!, ai) + 48
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class RepairBreachTask(ai: FriendlyCrewAI, room: Room, val systemKey: Int = -1) : AITask(ai, room) {
    // The system id is part of the problem (CrewMember +0x1ac of whoever is on it), see FriendlyCrewAI.update.
    override fun equals(other: Any?): Boolean = super.equals(other) && (other as RepairBreachTask).systemKey == systemKey

    override fun hashCode(): Int = super.hashCode() * 31 + systemKey

    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return AIUtils.systemPriority(room.system, ai) + 32
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class ExtinguishFireTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return AIUtils.systemPriority(room.system, ai) + 16
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

class ManningTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        val isHard = room.ship.sys.difficulty == Difficulty.HARD

        // See doc/crew-ai
        val systemPriority = when (room.system) {
            is Piloting -> 64
            is Shields -> if (isHard) 67 else 65
            is Engines -> if (isHard) 65 else 67
            is Weapons, is Artillery -> 66
            is Oxygen -> 68
            is Sensors -> 69
            is Doors, is Cloaking -> 70
            is Teleporter -> 71
            is Medbay -> 72
            else -> 1000
        }

        return systemPriority
    }

    override fun nextTask(crew: AbstractCrew): AITask? {
        if (!crew.currentAction.isIdle)
            return this

        // Check if any other crew in the room are assigned to manning.
        for (otherCrew in room.crew) {
            if (ai.hasAlreadyAssignedManning(otherCrew)) {
                return null
            }
        }

        return this
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canRepair
    }
}

/**
 * The system ids ShipManager adds its systems in, which is the order CrewAI::CheckForProblems (0x628900) walks them
 * in. Rooms with no system go last.
 */
internal fun systemOrderKey(system: AbstractSystem?): Int {
    if (system == null)
        return Int.MAX_VALUE
    val spec = system.configuration.spec
    return if (spec.availableByDefault) spec.systemIndex else 1000 + nativeSystemId(system)
}

internal fun nativeSystemId(system: AbstractSystem?): Int {
    return when (system) {
        is Shields -> 0
        is Engines -> 1
        is Oxygen -> 2
        is Weapons -> 3
        is Drones -> 4
        is Medbay -> 5
        is Piloting -> 6
        is Sensors -> 7
        is Doors -> 8
        is Teleporter -> 9
        is Cloaking -> 10
        is Artillery -> 11
        is BackupBattery -> 12
        is Clonebay -> 13
        is MindControl -> 14
        is Hacking -> 15
        null -> Int.MAX_VALUE
        else -> 100
    }
}

/**
 * CrewMember::BadAtCombat (0x47a100): a damage multiplier below one or
 * less than 100 maximum health.
 */
private val AbstractCrew.isBadAtCombat: Boolean
    get() = attackDamageMult < 1f || maxHealth < 100f

class CombatTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // CrewAI::PrioritizeTask (0x627450)
        if (ai.avoidBadFighters && crew.isBadAtCombat)
            return 100
        return AIUtils.systemPriority(room.system, ai) + 16
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew.canFight
    }
}

class HealingTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        return 0
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew is LivingCrew
    }

    override fun nextTask(crew: AbstractCrew): AITask? {
        val medbay = room.system as Medbay

        // CrewAI::UpdateCrewMember (0x627df0): healing ends at 99% health, or when the medbay is hacked.
        if (medbay.isHackActive || crew.health / crew.maxHealth >= 0.99f)
            return null

        return this
    }
}

class TeleportingTask(ai: FriendlyCrewAI, room: Room) : AITask(ai, room) {
    override fun priorityWithoutDanger(crew: AbstractCrew): Int {
        // CrewAI::PrioritizeTask (0x627450). There's an urgent teleporting
        // flag in vanilla which forces a priority of 0, but it seems to
        // never be set.
        if (ai.avoidBadFighters && crew.isBadAtCombat)
            return 100
        // TeleportSystem::Charged (0x648070) picks the base priority: ready (not ionised or hacked, and
        // powered) is 32, otherwise 65.
        val teleporter = room.ship.teleporter!!
        return when {
            crew.health / crew.maxHealth < 0.5f -> 100
            teleporter.isPowerLocked || teleporter.powerSupplied <= 0 -> 65
            else -> 32
        }
    }

    override fun isSuitable(crew: AbstractCrew): Boolean {
        return crew is LivingCrew && crew.canFight
    }
}
