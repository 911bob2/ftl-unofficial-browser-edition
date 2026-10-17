package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.ai.AITask
import xyz.znix.xftl.ai.ExtinguishFireTask
import xyz.znix.xftl.ai.RepairBreachTask
import xyz.znix.xftl.ai.RepairTask
import xyz.znix.xftl.ai.systemOrderKey
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.layout.FireInstance
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

class RepairDrone(type: DroneBlueprint) : AbstractIndoorsDrone(type) {
    override val occupancySlotType get() = AbstractCrew.SlotType.CREW

    override val pawnCodename: String get() = "repair"

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)
        val dronesRoom = ownerShip.rooms.find { it.system is Drones }
        requireNotNull(dronesRoom) { "Owner ship '${ownerShip.name}' spawned a ship repair drone without a drones system" }

        // Don't spawn in a pawn if that's already been done by deserialisation.
        if (pawn == null) {
            spawn(dronesRoom)
        }
    }

    override fun updatePawn(dt: Float) {
        val pawn = pawn!!

        // CrewAI's drone loop (0x6295e0) only picks a new job when the drone is idle (0x47b110: not walking, at its
        // spot, not working); standing in a room with a fire, a breach or a damaged system it is working there.
        if (pawn.pathingTarget != null)
            return
        val here = pawn.room
        if (here.system?.damaged == true || here.fires.any { it != null } || here.breaches.any { it != null })
            return

        // Every problem of type 1..3 - system repair, fire, breach - is a candidate, in CheckForProblems order
        // (0x628900: per room its fires, then the breaches, then the systems in blueprint order); the drone takes the
        // one CrewAI::PrioritizeTask (0x627450) ranks lowest, the first of equals, as long as it can walk there.
        // Fires (0x10..) come before breaches (0x20..) and repairs (0x30..), except a low-oxygen oxygen system or
        // the shields.
        val ai = ship.crewAI
        val candidates = ArrayList<AITask>()
        for (room in ship.rooms) {
            if (room.fires.any { it != null } && room.oxygen > FireInstance.OXYGEN_CUTOFF)
                candidates += ExtinguishFireTask(ai, room)
        }
        for (room in ship.rooms) {
            if (room.breaches.any { it != null })
                candidates += RepairBreachTask(ai, room)
        }
        for (room in ship.rooms.sortedBy { systemOrderKey(it.system) }) {
            if (room.system?.damaged == true)
                candidates += RepairTask(ai, room)
        }

        var best = Int.MAX_VALUE
        for (task in candidates) {
            val priority = task.basePriority(pawn)
            if (priority >= best)
                continue
            if (task.room == here || pawn.setTargetRoom(task.room))
                best = priority
        }
        if (best != Int.MAX_VALUE)
            return

        // Nothing to do: back to the drone system's room (task 4, ShipManager system room 0x49e760).
        val home = ship.rooms.firstOrNull { it.system is Drones } ?: return
        if (here != home)
            pawn.setTargetRoom(home)
    }

    override fun drawPawn() {
        // TODO the green glow over the drone when it's powered on - that comes from the layer1 image
    }

    override fun makePawn(room: Room): Pawn = RepairPawn(room)

    private inner class RepairPawn(room: Room) : AbstractIndoorsDrone.Pawn(room) {
        override val baseMaxHealth: Float get() = 25f
        override val canRepair: Boolean get() = true
        override val repairSpeed: Float get() = 2f
    }
}
