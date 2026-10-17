package xyz.znix.xftl.ai

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.layout.Door
import xyz.znix.xftl.layout.Room

/**
 * The enemy AI's use of its own doors, which the player ship's crew AI never does (CrewAI +4, the enemy flag, is
 * only set for the ships ShipAI drives).
 *
 * All of these only work while the doors system does ([Ship.areDoorsOperable], ShipManager::DoorsFunction), and they
 * only open or close the door flag: the door animations are driven by [Door.update].
 */
internal object AIDoorControl {
    /**
     * CrewAI::CloseAirlocks (0x626af0), run every update for an enemy ship: every open outer airlock door is shut
     * again, unless the room whose index equals the door's position in its room's airlock list is on fire.
     *
     * That fire check is a native quirk and is ported literally: it passes the index of the door in the room's
     * airlock list to ShipManager::GetFireCount as if it were a room id, rather than the id of the room the airlock
     * belongs to. A room without that many rooms has no fires.
     */
    fun closeAirlocks(ship: Ship) {
        if (!ship.areDoorsOperable)
            return

        for (room in ship.rooms) {
            var index = 0
            for (door in room.doors) {
                if (!door.isAirlock)
                    continue

                val fireCount = ship.rooms.getOrNull(index)?.let { fireCount(it) } ?: 0
                index++

                if (fireCount != 0)
                    continue

                if (door.open)
                    door.open = false
            }
        }
    }

    /**
     * ShipManager::ManipulateDoors (0x49cd60) with the open flag cleared, as called by CrewAI::CheckForProblems
     * (0x628900) for each breach of an enemy ship: every door of the room is shut to stop the air escaping into the
     * rest of the ship. CrewAI::SealRoom (0x6263b0) wraps the same call but has no caller.
     */
    fun sealRoom(ship: Ship, room: Room) {
        if (!ship.areDoorsOperable)
            return

        for (door in room.doors) {
            door.open = false
        }
    }

    /**
     * The airlock vent hook (0x626be0), tried by CrewAI::OnLoop for every fire (type 2) and intruder (type 7)
     * problem before the problem is assigned to a crewmember. If it returns true the problem is left alone: the
     * room's airlock doors are open to flush the air (and the fire or boarders) out.
     *
     * It's refused when any crewmember of the ship, bar boarders and drones, is in the room or on their way to do
     * something there, or the room's air has already dropped below 10%.
     *
     * Native quirk, ported literally: the hook is given the problem's position in the problem list and uses it as a
     * room id, so for the Nth fire or boarder problem the room whose id is N is the one that gets vented. A room
     * with no airlocks gives false.
     *
     * @param index The position of the problem in the problem list.
     * @param assignments The task each crewmember is currently on (CrewMember +0x1a8 is the room of that task).
     */
    fun tryVent(ship: Ship, index: Int, assignments: Map<AbstractCrew, AITask>): Boolean {
        val room = ship.rooms.getOrNull(index) ?: return false
        val airlocks = room.doors.filter { it.isAirlock }
        if (airlocks.isEmpty())
            return false

        for (crew in ship.crew) {
            if (crew.mode == AbstractCrew.SlotType.INTRUDER)
                continue
            if (crew is AbstractIndoorsDrone.Pawn)
                continue

            if (crew.room == room || assignments[crew]?.room == room)
                return false
        }

        // FUN_0049f470 is the room's oxygen as a percentage.
        if (room.oxygen * 100f < 10f)
            return false

        val operable = ship.areDoorsOperable
        for (door in airlocks) {
            if (operable && !door.open)
                door.open = true
        }
        return true
    }

    private fun fireCount(room: Room): Int = room.fires.count { it != null }
}
