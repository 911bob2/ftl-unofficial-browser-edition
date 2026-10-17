package xyz.znix.xftl.drones

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.systems.Drones
import xyz.znix.xftl.weapons.DroneBlueprint

/** An autonomous friendly battle pawn; it cannot repair, man systems or be ordered by the player. */
class AntiPersonnelDrone(type: DroneBlueprint) : AbstractIndoorsDrone(type) {
    override val occupancySlotType get() = AbstractCrew.SlotType.CREW
    override val pawnCodename get() = "battle"

    override fun init(ownerShip: Ship) {
        super.init(ownerShip)
        if (pawn == null) spawn(requireNotNull(ownerShip.rooms.firstOrNull { it.system is Drones }))
    }

    private fun hostile(room: Room) = room.crew.any {
        it.mode != occupancySlotType && it.health > 0f && it.currentAction != AbstractCrew.Action.DYING
    }

    override fun updatePawn(dt: Float) {
        val pawn = pawn ?: return
        // CrewAI's drone loop (0x6295e0), fighting drones: of the rooms with a boarder (problem type 7) that aren't
        // full of crew - or are the drone's own room - the one whose centre (ShipGraph 0x464c00) is nearest to the
        // drone by squared pixel distance (0x5a4b00, truncated to int), the first of equals in room order. Re-picked
        // every frame, so a drone walking past a boarder's room turns into it.
        val position = pawn.getPixelPositionCentre()
        var target: Room? = null
        var best = Int.MAX_VALUE
        for (room in ship.rooms) {
            if (!hostile(room))
                continue
            if (room != pawn.room && !room.anySlotsFree(occupancySlotType, pawn))
                continue
            val dx = (position.x - room.pixelCentre.x).toFloat()
            val dy = (position.y - room.pixelCentre.y).toFloat()
            val distance = (dx * dx + dy * dy).toInt()
            if (distance < best) {
                best = distance
                target = room
            }
        }

        if (target != null) {
            if ((pawn.pathingTarget?.room ?: pawn.room) != target)
                pawn.setTargetRoom(target)
            return
        }

        // Nothing to fight: back to the drone system's room (task 4).
        if (pawn.pathingTarget != null)
            return
        val home = ship.rooms.firstOrNull { it.system is Drones } ?: return
        if (pawn.room != home)
            pawn.setTargetRoom(home)
    }

    override fun drawPawn() {}
    override fun makePawn(room: Room): Pawn = BattlePawn(room)

    private inner class BattlePawn(room: Room) : Pawn(room) {
        // Verified BattleDrone virtuals in the unmodified 1.6.14 executable.
        override val baseMaxHealth get() = 150f
        override val attackDamageMult get() = 1.2f
        override val canFight get() = true
        override val canPunch get() = false
        override val canSabotage get() = false
    }
}
