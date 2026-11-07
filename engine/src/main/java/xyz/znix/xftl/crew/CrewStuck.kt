package xyz.znix.xftl.crew

import xyz.znix.xftl.Ship
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.InGameState

/**
 * WorldManager::OnLoop's stranded boarders (1.6.14 0x61bd78..0x61be52 and 0x61cfb9; 1.6.9 WorldManager::OnLoop the
 * same). Every loop in which the enemy ship is there but no longer hostile (its crew killed, a surrender taken, a
 * neutral ship) and no ending event was made that loop, the original checks, in this order:
 *  - the player's ship holds none of its own crew (ShipManager::CountCrew(false) 0x49c180 < 1: living, non-drone
 *    crew that are not intruders there),
 *  - the enemy ship holds the player's boarders (its CountCrew(true) > 0),
 *  - the player's teleporter is installed and has no health left (GetSystemHealth(9) 0x49e5c0 == 0; a ship without
 *    one answers -1).
 * Then every crew member aboard the enemy that is not dead, belongs to the player (iShipId 0) and is not a drone
 * leaves its slot (EmptySlot) and is added to the player's ship in the piloting room (GetSystemRoom(6),
 * CompleteShip::AddCrewMember 0x409950), and the CREW_STUCK event is made with the sector's level from the global
 * stream (CreateEvent seed -1) and shown (CreateChoiceBox).
 */
object CrewStuck {
    /** The rule above; returns true when it brought crew home. [endingShown]: this loop made a ship ending event. */
    @JvmStatic
    fun check(game: InGameState, endingShown: Boolean): Boolean {
        val player = game.player ?: return false
        val enemy = game.enemy ?: return false
        if (game.isEnemyHostile || endingShown) return false
        if (countCrew(player, boarders = false) >= 1) return false
        if (countCrew(enemy, boarders = true) <= 0) return false
        val teleporter = player.teleporter ?: return false
        if (!teleporter.broken) return false

        bringHome(enemy, player)

        val event = game.eventManager["CREW_STUCK"]
        game.eventCreator.create(event, game.eventCreator.currentLevel())?.let { game.shipUI?.showEventDialogue(it) }
        return true
    }

    /** ShipManager::CountCrew(boarders): living, non-drone crew aboard whose intruder flag is [boarders]. */
    @JvmStatic
    fun countCrew(ship: Ship, boarders: Boolean): Int = ship.crew.count {
        it is LivingCrew && it !is AbstractIndoorsDrone.Pawn && it.health > 0f &&
            (it.mode == AbstractCrew.SlotType.INTRUDER) == boarders
    }

    private fun bringHome(enemy: Ship, player: Ship) {
        val pilotRoom = player.piloting?.room
        for (crew in enemy.crew.toList()) {
            // The original takes crew that are not yet dead (bDead); one in its death hold would die at home.
            if (crew !is LivingCrew || crew is AbstractIndoorsDrone.Pawn || crew.health <= 0f) continue
            if (crew.ownerShip !== player) continue
            crew.removeFromShip()
            player.crew.add(crew)
            val mode = if (crew.isMindControlled) AbstractCrew.SlotType.INTRUDER else AbstractCrew.SlotType.CREW
            crew.jumpTo(player.findSpaceForCrew(pilotRoom, mode))
        }
        player.sys.updatePlayerCrew()
    }
}
