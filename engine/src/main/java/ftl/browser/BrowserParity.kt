package ftl.browser

import xyz.znix.xftl.game.InGameState
import kotlin.random.Random

/**
 * Diagnostics-only helpers for parity captures against the original game.
 * Nothing here is reachable from player input.
 */
object BrowserParity {
    /** A short JSON summary of a parsed original save: beacon, enemy event and crew owners. */
    fun describeSave(data: xyz.znix.xftl.vanillasave.VanillaSaveData): String {
        val beacon = data.beacons.getOrNull(data.currentBeaconId)
        val crew = data.ship.crew.joinToString(",") {
            "{\"name\":${BrowserDebug.quote(it.name)},\"race\":${BrowserDebug.quote(it.race)},\"player\":${it.isPlayer},\"room\":${it.roomId},\"square\":${it.roomSquare}}"
        }
        return "{\"beacon\":${data.currentBeaconId},\"beacons\":${data.beacons.size}," +
            "\"enemy\":${beacon?.enemyPresent},\"event\":${BrowserDebug.quote(beacon?.shipEventId ?: "")}," +
            "\"seed\":${beacon?.shipEventSeed},\"nearby\":${BrowserDebug.quote(data.enemyShip?.blueprint ?: "")}," +
            "\"nearbyHull\":${data.enemyShip?.hull},\"nearbyCrew\":${data.enemyShip?.crew?.size},\"crew\":[$crew]}"
    }

    /**
     * Open the named event's dialogue with the first seed whose resolved text
     * contains [needle], so a capture shows the same text the original chose.
     */
    fun showEvent(game: InGameState, name: String, needle: String): Boolean {
        // Event lists resolve to a random member, so retry until one can match.
        repeat(200) {
            val event = game.eventManager[name].resolve()
            val text = event.text ?: return@repeat
            for (seed in 0 until 2_000) {
                // DialogueWindow resolves the text first from Random(seed).
                if (needle in text.resolve(Random(seed))) {
                    game.shipUI.showEventDialogue(event, seed)
                    return true
                }
            }
        }
        return false
    }
}
