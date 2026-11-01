// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/EventTooltip.cpp
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.augments.AugmentBlueprint
import xyz.znix.xftl.crew.LivingCrewInfo
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.game.InfoPanel
import xyz.znix.xftl.game.TouchMode
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.weapons.AbstractWeaponBlueprint
import xyz.znix.xftl.weapons.DroneBlueprint

/**
 * Event tooltips (`<eventTooltips>`, on under the More Info alt mode): while a choice is hovered, the InfoBox for its
 * reward (ChoiceBox::MouseMove: a weapon without `hideEventTooltip`, else a drone, else an augment, else a crew member when the choice removes no
 * item) is drawn after the choice box (ChoiceBox::OnRender) with its corner at (946, 73), or (796, 73) when the box
 * is not centred.
 */
object HsEventTooltip {
    private var panel: InfoPanel? = null
    private var panelGame: InGameState? = null

    /** The reward the tooltip describes, or null (ChoiceBox::MouseMove's InfoBox::SetBlueprint). */
    @JvmStatic
    fun reward(items: List<Any>, crew: List<LivingCrewInfo>, removesItem: Boolean): Any? {
        // weapon && !hideEventTooltip, else the drone, the augment, the crew member
        (items.firstOrNull { it is AbstractWeaponBlueprint } as AbstractWeaponBlueprint?)?.let { if (!hideEventTooltip(it)) return it }
        items.firstOrNull { it is DroneBlueprint }?.let { return it }
        items.firstOrNull { it is AugmentBlueprint }?.let { return it }
        if (crew.isNotEmpty() && !removesItem) return crew[0]
        return null
    }

    private fun hideEventTooltip(w: AbstractWeaponBlueprint): Boolean = w.hs?.hideEventTooltip == true

    /** ChoiceBox::OnRender hook, after the box. */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, reward: Any?, centered: Boolean) {
        if (reward == null || !HsMoreInfo.eventTooltips()) return
        val p = panel?.takeIf { panelGame === game } ?: InfoPanel(game).also { panel = it; panelGame = game }
        p.position = if (TouchMode.enabled) {
            // Right of the box when the canvas has the room, else against the canvas' right edge.
            val right = TouchMode.hudX + if (centered) 818 else 668
            ConstPoint(minOf(right, TouchMode.canvasW - 327), 73)
        } else {
            ConstPoint(if (centered) 953 else 803, 147).also { p.customWidth = 323 }
        }
        when (reward) {
            is AbstractWeaponBlueprint -> p.drawWeapon(reward)
            is DroneBlueprint -> p.drawDrone(reward)
            is AugmentBlueprint -> p.drawAugment(reward)
            is LivingCrewInfo -> p.drawCrew(g, reward, false)
        }
    }
}
