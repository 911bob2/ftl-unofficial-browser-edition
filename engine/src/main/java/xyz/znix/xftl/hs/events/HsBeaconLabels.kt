// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Gameplay/World/CustomEvents.cpp (StarMap::RenderLabels,
// StarMap::GetLocationText) (GPL-3.0-or-later; the browser engine is AGPL-3.0, see docs/HYPERSPACE.md).
package xyz.znix.xftl.hs.events

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.sector.Beacon

/**
 * `<beaconType>` on the star map: the custom label (RenderLabels) and the tooltip text (GetLocationText) of a beacon
 * whose event carries one. [Label.hideVanilla] hides the original's quest, exit, repair, store and distress labels.
 */
object HsBeaconLabels {
    class Label(@JvmField val text: String?, @JvmField val colour: Colour, @JvmField val white: Boolean, @JvmField val hideVanilla: Boolean)

    private fun type(game: InGameState, beacon: Beacon): BeaconType? = HsFlow.customFor(game, beacon.createdEvent)?.beacon

    private fun text(game: InGameState, t: HsTextString): String = t.text { game.translator[it] }

    /**
     * RenderLabels' part for one beacon, or null when the beacon's event has no beacon type. [known] is
     * Location::known (visited or next to a visited beacon), [revealed] the revealed map.
     */
    @JvmStatic
    fun label(game: InGameState, beacon: Beacon, known: Boolean, revealed: Boolean): Label? {
        val t = type(game, beacon) ?: return null
        val shown = !beacon.visited || t.persistent
        var hide = t.hideVanillaLabel && shown
        var text: String? = null
        if (shown && (revealed || t.global || known)) {
            var ok = t.equipmentReq.isEmpty()
            if (!ok) HsEvents.withCheck(1) { ok = HsReq.hasEquipment(game, game.player, t.equipmentReq) > 0 }
            if (ok) {
                hide = true
                text = text(game, t.beaconText)
            }
        }
        val white = t.colorR == 255f && t.colorG == 255f && t.colorB == 255f
        return Label(text, Colour(t.colorR / 255f, t.colorG / 255f, t.colorB / 255f, t.colorA), white, hide)
    }

    /** What GetLocationText shows instead of the original text. */
    sealed class Tooltip {
        /** The original text. */
        object Original : Tooltip()
        /** The original text with the quest, exit, repair, distress (and, unvisited, store) facts hidden. */
        object OriginalHidden : Tooltip()
        /** The beacon type's text; the nebula and hazard lines still follow. */
        class Custom(@JvmField val text: String) : Tooltip()
    }

    /** GetLocationText's choice for [beacon]; [overtaken] is Location::fleetChanging. */
    @JvmStatic
    fun tooltip(game: InGameState, beacon: Beacon, known: Boolean, revealed: Boolean, overtaken: Boolean): Tooltip {
        val t = type(game, beacon) ?: return Tooltip.Original
        if (overtaken || beacon === game.currentBeacon) return Tooltip.Original
        var hide = true
        val tip: HsTextString
        if ((!t.global && !known && !revealed) ||
            (t.equipmentReq.isNotEmpty() && HsReq.hasEquipment(game, game.player, t.equipmentReq) <= 0)) {
            if (beacon.visited) return Tooltip.Original
            tip = t.undiscoveredTooltip
            hide = t.hideVanillaLabel
        } else if (beacon.visited) {
            tip = t.visitedTooltip
        } else {
            tip = t.unvisitedTooltip
        }
        if (tip.isEmpty) return if (hide) Tooltip.OriginalHidden else Tooltip.Original
        return Tooltip.Custom(text(game, tip))
    }
}
