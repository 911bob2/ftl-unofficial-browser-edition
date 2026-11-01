// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Ship/ShipSystems/ArtillerySystem.cpp (ArtilleryBox
// constructor / OnRender / MouseMove / MouseClick). FTL-Hyperspace is GPL-3.0-or-later; this engine is AGPL-3.0-or-later.
package xyz.znix.xftl.hs.systems

import xyz.znix.xftl.game.Button
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.math.ConstPoint
import xyz.znix.xftl.math.IPoint
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.sys.Input
import xyz.znix.xftl.systems.Artillery

/**
 * The targetable artillery's aim button (systemUI/artilleryButton, hitbox 17 x 19) at the box corner plus the
 * option's offset, 8 px higher per system level; a click arms the artillery like a weapon (CombatControl::ArmArtillery).
 * It shows only while the artillery is targetable (augment or option), so the bar need not be rebuilt for it.
 */
class HsArtilleryButton(game: InGameState, private val artillery: Artillery, private val powerPos: IPoint) :
    Button(game, ConstPoint(powerPos.x - 19, powerPos.y - 19), ConstPoint(17, 19)) {

    private val off = game.getImgIfExists("img/systemUI/artilleryButton_off.png")
    private val on = game.getImgIfExists("img/systemUI/artilleryButton_on.png")
    private val select = game.getImgIfExists("img/systemUI/artilleryButton_select2.png")

    private val shown: Boolean get() = HsArtillery.targetable(game.player)

    /** ArtilleryBox::OnRender: offset.y follows the system's level (healthState.second) unless fixedYPos. */
    private fun corner(): ConstPoint {
        val o = HsArtillery.buttonOffset(artillery)
        return ConstPoint(powerPos.x - 19 + o.x, powerPos.y - 19 + o.y)
    }

    /** Button::bActive = ArtillerySystem::Functioning. */
    override val disabled: Boolean get() = !shown || artillery.powerSupplied <= 0

    override fun contains(x: Int, y: Int): Boolean {
        if (!shown) return false
        val c = corner()
        return x >= c.x && x < c.x + size.x && y >= c.y && y < c.y + size.y
    }

    override fun draw(g: Graphics) {
        if (!shown) return
        val armed = game.shipUI?.hsArmedArtillery() === artillery
        // an armed artillery shows the selected button even while it is not functioning (oracle sweep-world-y1 y-art: the
        // unpowered second artillery armed by its hotkey)
        val img = when {
            armed -> select
            disabled -> off
            hovered -> select
            else -> on
        } ?: return
        val c = corner()
        img.draw(c.x, c.y)
        if (rawHovered) g.tooltip = artillery.browserWeapon.weaponBarTooltip
    }

    override fun click(button: Int) {
        if (button == Input.MOUSE_LEFT_BUTTON) arm()
    }

    /** The button's action, for a layout that places it elsewhere. */
    fun arm() {
        if (!disabled) game.shipUI?.hsArmArtillery(artillery)
    }
}
