package xyz.znix.xftl.hs.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.AbstractCrew
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.layout.Room
import xyz.znix.xftl.math.IPoint

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/TileBasedCrewMovement.cpp
/**
 * Hyperspace's right-click options (CrewControl::RButton, priority 1000, before the vanilla room order):
 * `rightClickDoorOpening` (default on) keeps vanilla's door toggle on a right click, and `alternateCrewMovement`
 * sends one selected crew member to the clicked tile, swapping with whoever holds it.
 */
object HsTileMovement {
    /** rightClickDoorOpening: off drops the selected door, so the click orders crew instead. Vanilla: on. */
    @JvmStatic
    fun rightClickOpensDoors(): Boolean = !HS.active || HS.config?.rightClickDoorOpening != false

    @JvmStatic
    fun enabled(): Boolean = HS.active && HS.config?.alternateCrewMovement == true

    /**
     * The crew may take part: on [ship], GetControllable (requiresFullControl is still 0 at priority 1000: selection
     * control), Functional, not stunned at its goal, not dying (CrewAnimation status 3).
     */
    private fun canAct(crew: AbstractCrew, ship: Ship): Boolean =
        crew.room.ship === ship && crew.playerControllable && HsCrew.ex(crew).functional &&
            (crew.stunTime <= 0f || !atFinalGoal(crew)) && crew.currentAction != AbstractCrew.Action.DYING

    private fun atFinalGoal(crew: AbstractCrew) = crew.pathingTarget == null && crew.standingPosition != null

    /**
     * The tile step for a right click on [tile] (room-relative cell; HS: (wX - rect.x) / 35 + w * ((wY - rect.y) / 35))
     * of [room] on [ship]. The first selected crew member that can move there does (the loop stops at the first
     * successful move); a crew member holding the tile is swapped: into the mover's old slot when the mover is in
     * the same room, else to another free slot of the room if there is one. Vanilla's room order follows for the
     * whole selection; it leaves everyone whose slot is already in this room (MoveToRoom(room, -1, false)).
     */
    @JvmStatic
    fun rButton(ship: Ship, room: Room, tile: IPoint, selected: List<AbstractCrew>) {
        if (selected.isEmpty() || !room.containsRelative(tile)) return
        // room->slots[ship->iShipId]: the player's crew on its own ship, its boarders on the enemy ship.
        val type = if (ship.isPlayerShip) AbstractCrew.SlotType.CREW else AbstractCrew.SlotType.INTRUDER
        val swapCrew = room.slotOccupier(tile, type)
        val roomIsFull = !room.anySlotsFree(type)
        val tileAvailable = if (swapCrew != null) canAct(swapCrew, ship) else true
        if (!tileAvailable) return

        for (crew in selected.toList()) {
            if (!canAct(crew, ship)) continue
            if (swapCrew == null) {
                if (crew.setTargetRoom(room, tile)) break
            } else if (swapCrew !== crew) {
                val old = crew.currentSlotPoint
                if (old.room === room) {
                    // Same room: swapCrew->EmptySlot(), the mover takes the tile and swapCrew the mover's old slot.
                    var moved: Boolean
                    ship.deferCrewSlotUpdates++
                    try {
                        moved = crew.setTargetSlot(room, tile, swapCrew)
                        swapCrew.setTargetSlot(room, old, crew)
                    } finally {
                        ship.deferCrewSlotUpdates--
                    }
                    ship.updateCrewReservedSlots()
                    if (moved) break
                } else if (!roomIsFull) {
                    // swapCrew->MoveToRoom(room, -1, true): another free slot of the room, then the mover takes the tile.
                    val free = room.firstFreeSlot(type)
                    var moved = free != null && swapCrew.setTargetRoom(room, free)
                    if (moved) moved = crew.setTargetRoom(room, tile)
                    if (moved) break
                }
            }
        }
    }
}
