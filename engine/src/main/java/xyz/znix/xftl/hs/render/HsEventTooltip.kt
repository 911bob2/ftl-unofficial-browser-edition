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
 * Event tooltips: while a choice is hovered, the InfoBox for its
 * reward (ChoiceBox::MouseMove: a weapon without `hideEventTooltip`, else a drone, else an augment, else a crew member when the choice removes no
 * item) is drawn after the choice box (ChoiceBox::OnRender) with its corner at (946, 73), or (796, 73) when the box
 * is not centred. Always on: EventTooltip.cpp tests its own `g_eventTooltips`, which is true and never written; the
 * `<eventTooltips>` option and the More Info mode that sets it change nothing.
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

    private val hold = xyz.znix.xftl.game.InfoStack.HoldScroll()

    /** The lowest y the box may reach, in the coordinates the event window draws in (set by the window; null: the screen). */
    @JvmStatic
    var screenBottom: (() -> Int)? = null

    /** The wheel: the reward box's text, when the box is lower than it (InfoStack). True if it moved. */
    @JvmStatic
    fun wheel(game: InGameState, change: Int): Boolean = panel?.takeIf { panelGame === game }?.scrollWheel(change, -1, -1) ?: false

    /**
     * The touch layout shows the box while a finger holds its choice: the finger's way up or down moves the text
     * (InfoStack.HoldScroll). True while it does: the window keeps the choice that is held.
     */
    @JvmStatic
    fun hold(down: Boolean, y: Int): Boolean {
        val p = panel ?: return false
        return hold.update(down, y, p.scrolls) { p.scrollBy(it) }
    }

    /** The finger moved the text since it came down: its lift picks no choice. */
    @JvmStatic
    val holdScrolled: Boolean get() = hold.scrolled

    /** For the checks: lets go of the panel and with it of the game it was made for. */
    @JvmStatic
    fun diagnosticReset() { panel = null; panelGame = null; screenBottom = null }

    /** For the checks: the panel the reward box is drawn with. */
    @JvmStatic
    fun diagnosticPanel(game: InGameState): InfoPanel = panel?.takeIf { panelGame === game } ?: InfoPanel(game).also { panel = it; panelGame = game }

    private fun hideEventTooltip(w: AbstractWeaponBlueprint): Boolean = w.hs?.hideEventTooltip == true

    /** ChoiceBox::OnRender hook, after the box. */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, reward: Any?, centered: Boolean) {
        if (!xyz.znix.xftl.game.TouchRouter.fingerDown) hold.update(false, 0, false) {}
        if (reward == null) {
            // not shown: its texts stop scrolling
            panel?.takeIf { panelGame === game }?.frame()
            return
        }
        val p = panel?.takeIf { panelGame === game } ?: InfoPanel(game).also { panel = it; panelGame = game }
        p.frame()
        // the canvas' height here; the event window may be drawn scaled (the website's touch layout)
        p.screenBottom = screenBottom
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
