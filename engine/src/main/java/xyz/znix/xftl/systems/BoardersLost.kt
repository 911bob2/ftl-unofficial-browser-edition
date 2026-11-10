package xyz.znix.xftl.systems

import xyz.znix.xftl.Ship
import xyz.znix.xftl.crew.CrewStuck
import xyz.znix.xftl.crew.LivingCrew
import xyz.znix.xftl.drones.AbstractIndoorsDrone
import xyz.znix.xftl.game.InGameState

/**
 * The player's boarders on an enemy ship that leaves the fight for good (1.6.14 and 1.6.9 the same):
 * - WorldManager::OnLoop (1.6.14 0x61b930, 1.6.9 0x5f6520), once ShipAI::Destroyed (0x6256d0: bDestroyed and
 *   Ship::DestroyedDone, once) holds for the enemy: intruders aboard it (ShipManager::CountCrew(true) 0x49c180 > 0)
 *   set the "lost_crew" flag; then ShipAI::Escaped (0x625670: the ship jumped and its jump animation is over) picks
 *   ShipManager::KillEveryone (0x4ab990) with true, a destroyed ship's crew get false.
 * - WorldManager::ClearLocation (1.6.14 0x614d00, 1.6.9 0x5ef130), when the player's ship leaves the beacon or the
 *   console's LOAD replaces the ship: the same flag, then KillEveryone(true). The enemy's own state was saved into its
 *   event before that (ShipManager::SaveToBlueprint skips intruders and drones), so the boarders are not part of it.
 * KillEveryone calls CrewMember::Kill (0x47ba00) on every crew member aboard: dead at once (bDead, slot emptied, no
 * dying animation); Kill(true) clears clone_ready (+0x53c), so only a destroyed ship's boarders reach the player's
 * clone bay. The enemy's own crew go with their ship, so only the player's crew are handled here.
 */
object BoardersLost {
    /** The enemy's ship is gone: destroyed ([escaped] false) or jumped away. */
    @JvmStatic
    fun shipGone(game: InGameState, ship: Ship, escaped: Boolean) = lose(game, ship, noClone = escaped)

    /** WorldManager::ClearLocation with [ship] still at the beacon. */
    @JvmStatic
    fun clearLocation(game: InGameState, ship: Ship) = lose(game, ship, noClone = true)

    private fun lose(game: InGameState, ship: Ship, noClone: Boolean) {
        val player = game.player ?: return
        if (ship === player) return
        if (CrewStuck.countCrew(ship, boarders = true) > 0)
            game.browserAchievements.record(ftl.browser.BrowserAchievementProgress.Action.LOST_CREW)
        for (crew in ship.crew.toList()) {
            if (crew !is LivingCrew || crew is AbstractIndoorsDrone.Pawn || crew.ownerShip !== player) continue
            // A crew member already dead (bDead) is in the clone queue or gone; Kill on it changes nothing.
            if (!ship.crew.contains(crew)) continue
            crew.health = 0f
            crew.mindControlledBy = null
            // Kill ends its fight: the queued body is saved without a target on the ship that is gone.
            crew.hsClearFight()
            if (noClone) {
                if (xyz.znix.xftl.hs.HS.active) xyz.znix.xftl.hs.crew.HsCrew.ex(crew).noClone = true
                crew.removeFromShip()
            } else {
                // Kill(false): the clone bay takes it as for any death (LivingCrew.onFinishedDying, Hyperspace's
                // NO_CLONE included).
                crew.onFinishedDying()
            }
        }
        game.updatePlayerCrew()
    }
}
