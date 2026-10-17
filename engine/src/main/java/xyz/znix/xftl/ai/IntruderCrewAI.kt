package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.layout.Room

class IntruderCrewAI(private val ship: Ship) {
    private val aiCrew = ArrayList<AbstractCrew>()
    private var hasWorkingDoors: Boolean = false

    fun update() {
        // The boarders on a ship are driven by that ship's CrewAI::OnLoop (0x629d80), which only runs while the ship
        // is hostile (see [AIUtils.crewAiRuns]).
        if (!AIUtils.crewAiRuns(ship))
            return

        // Update the list of AI-controllable crew
        aiCrew.clear()
        for (crew in ship.crew) {
            // CrewAI skips anything with a true CrewMember::IsDrone (vtable +0x30) when it counts boarders and
            // hands out work, so drone bodies are never directed from here.
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            if (crew.mode != AbstractCrew.SlotType.INTRUDER)
                continue

            if (crew.playerControllable)
                continue

            aiCrew += crew
        }

        if (aiCrew.isEmpty()) {
            return
        }

        // PrioritizeIntruderRoom (0x626570) reads ShipManager::GetSystemPower(8),
        // the doors' effective power, so any powered level of doors (not just
        // blast doors) stops crew from fighting in place; a manned or
        // auto-manned doors system counts one level higher.
        val doors = ship.doorsSystem
        hasWorkingDoors = doors?.let { it.effectivePower > 0 } ?: false

        // CrewAI 0x628690: the first boarder (whose room has a door) picks a room for itself (0x628490), and every
        // boarder after it in the list is ordered to that same room, whatever it would choose itself, until one of
        // them can't be moved there (the room is full). That one then picks for itself and leads the rest. A failure
        // on the leader itself ends the pass.
        var leader = 0
        while (leader < aiCrew.size) {
            if (!hasDoor(aiCrew[leader].room)) {
                leader++
                continue
            }

            val target = pickRoom(aiCrew[leader])
            var next = leader
            var failedAt = -1
            while (next < aiCrew.size) {
                val crew = aiCrew[next]
                if (currentTarget(crew) != target && !crew.setTargetRoom(target)) {
                    failedAt = next
                    break
                }
                next++
            }

            if (failedAt == -1 || failedAt == leader)
                return
            leader = failedAt
        }
    }

    /** The room this boarder is working on: where it's walking to, or where it stands. */
    private fun currentTarget(crew: AbstractCrew): Room {
        return crew.pathingTarget?.room ?: crew.room
    }

    private fun hasDoor(room: Room): Boolean = ship.doors.any { it.left == room || it.right == room }

    /**
     * CrewAI 0x628490: of the rooms with a door and a free boarder slot, the ones ranked lowest by
     * PrioritizeIntruderRoom - but only when that beats the room the boarder is already working on - one of them at
     * random; otherwise the current room.
     */
    private fun pickRoom(crew: AbstractCrew): Room {
        val current = currentTarget(crew)
        val currentPriority = roomPriority(crew, current)

        val best = ArrayList<Room>()
        var bestPriority = currentPriority
        for (room in ship.rooms) {
            if (!room.anySlotsFree(AbstractCrew.SlotType.INTRUDER, crew))
                continue
            if (!hasDoor(room))
                continue

            val priority = roomPriority(crew, room)
            if (priority < bestPriority) {
                best.clear()
                best += room
                bestPriority = priority
            } else if (priority == bestPriority && priority != currentPriority) {
                best += room
            }
        }

        if (best.isEmpty())
            return current
        return best[ftl.browser.BrowserRandom.nextInt(best.size)]
    }

    private fun roomPriority(crew: AbstractCrew, room: Room): Int {
        // CrewAI::PrioritizeIntruderRoom (0x626570): lower is more important.
        // Its ion-intruder branch (crew flag +0x19c) and the mode that selects it (+0x198) have no writer anywhere
        // in the executable (+0x198 is always 1 and +0x19c always 0), so only the normal branch is ever taken.

        val hasEnemies = room.crew.any { it.mode == AbstractCrew.SlotType.CREW }
        val numBoarders = room.crew.count { it.mode == AbstractCrew.SlotType.INTRUDER }

        // -10: fight enemies in our own room while the doors are powered.
        if (crew.canFight && room == crew.room && hasWorkingDoors && hasEnemies) {
            return -10
        }

        val hasFireDanger = crew.fireDamageMult > 0f && room.fires.any { it != null }
        val hasAirDanger = crew.suffocationMultiplier > 0f && room.oxygen < 0.1f

        var priority = 0

        if (hasFireDanger)
            priority += 100
        if (hasAirDanger)
            priority += 70

        // Add 5 points for completely full rooms, as this prevents the crew
        // from having a numerical advantage.
        // Note this only applies to the crew currently in the room, not the
        // crew pathing towards it.
        if (numBoarders != room.width * room.height) {
            priority += 5
        }

        // Prefer adjacent rooms
        if (crew.room != room && !crew.room.connectedTo(room)) {
            priority += 10
        }

        // Strongly prefer rooms with a system we can attack
        if (room.system?.broken != false) {
            priority += 20
        }

        return priority
    }
}
