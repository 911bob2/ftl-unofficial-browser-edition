// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.cpp (the MenuScreen::Open
// and MenuScreen::OnRender hooks) and src/game/Gameplay/ShipUnlocks.cpp (the victory and quest achievements of a custom
// ship); the browser engine is AGPL-3.0, see docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.Ship
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.sector.HsSeeds
import xyz.znix.xftl.hs.ship.HsAchievements
import xyz.znix.xftl.hs.ship.HsHangar
import xyz.znix.xftl.hs.ship.HsShipUnlocks

/**
 * What Hyperspace's MenuScreen hooks change in the pause menu, for the run's player ship. MenuScreen::OnRender (priority
 * 1000) draws the native menu with its achievements panel ([panel]) when the ship is not a Hyperspace ship, or
 * `<showShipAchievements enabled/toggle>` is set and the ship's entry shows achievements; otherwise it draws the menu
 * without the panel and with the seed box under the difficulty and AE boxes. MenuScreen::Open lays out a Hyperspace
 * ship's own achievements (up to three, centred on x = 727.5) in place of the family's.
 */
class HsPauseMenu(ship: Ship) {
    /** The ship's entry and layout (0..2), null for a vanilla ship. */
    private val entry = if (ship.hs?.isNewShip == true) HsHangar.variantOf(ship.type.name) else null

    /** The native achievements panel is drawn (the other branch has the difficulty and AE boxes and the seed box only). */
    val panel: Boolean

    /** A Hyperspace ship's achievements replace the family's. */
    val custom: Boolean get() = entry != null

    /** The ship's own achievements, at most three (GetShipAchievementsCustom(id, layout, false)). */
    val achievements: List<HsAchievements.CustomAchievement>

    /** CustomShipUnlocks::GetVictoryAchievement: on when won; the layouts' wins for the dots, null for one layout. */
    val victoryOn: Boolean
    val victoryLayouts: List<Boolean>?
    val hasQuest: Boolean
    val questOn: Boolean

    init {
        val cfg = HS.cfg()
        val button = entry?.first
        val specific = button?.showShipAchievements ?: true
        panel = (button == null || cfg.showShipAchievements || cfg.shipAchievementsToggle) && specific
        achievements = if (button != null) HsAchievements.shipAchievements(button.name, entry!!.second, false).take(3) else emptyList()
        if (button != null) {
            val name = ship.type.name
            fun won(n: String) = HsShipUnlocks.checkCustomShipVictory(n) > -1
            val layouts = (0..2).filter { button.variantExists(it) }.map { won(xyz.znix.xftl.hs.ship.HsShips.variantName(button.name, it)) }
            if (button.splitVictoryAchievement) {
                victoryOn = won(name)
                victoryLayouts = null
            } else {
                victoryOn = (0..2).any { won(xyz.znix.xftl.hs.ship.HsShips.variantName(button.name, it)) }
                victoryLayouts = if (layouts.size > 1) layouts else null
            }
            fun quest(n: String) = HsShipUnlocks.unlocksOf(n).any { it.type == HsShipUnlocks.Type.EVENT }
            if (button.splitUnlockQuestAchievement) {
                hasQuest = quest(name)
                questOn = HsShipUnlocks.checkCustomShipUnlockQuest(name) > -1
            } else {
                hasQuest = quest(button.name) || quest(button.name + "_2") || quest(button.name + "_3")
                questOn = HsShipUnlocks.checkCustomShipUnlockQuest(button.name) > -1
            }
        } else {
            victoryOn = false; victoryLayouts = null; hasQuest = false; questOn = false
        }
    }

    companion object {
        /** `snprintf("%u", Global::currentSeed)`, or "-" with seeds disabled. */
        fun seedText(): String = if (HsSeeds.enabled) Integer.toUnsignedString(HsSeeds.currentSeed) else "-"
    }
}
