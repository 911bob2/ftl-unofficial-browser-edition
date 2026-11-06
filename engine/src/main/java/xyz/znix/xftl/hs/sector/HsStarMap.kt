// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1.23.2 / 1b05a07 src/game/Gameplay/World/CustomMap.cpp (StarMap::OnLoop
// rewrite: forceWait, showAllConnections), src/game/Gameplay/World/StarMap.cpp (the split all-node view's superGrid
// button) and lua/modules/hyperspace.i (StarMap::ForceWaitMessage); the browser engine is AGPL-3.0, see
// docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.sector

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.lua.LuaPrimitive
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.FixedDelayedTooltip
import xyz.znix.xftl.rendering.Image

/**
 * Hyperspace's StarMap state that is not part of the sector (process-wide in HS, as its statics are):
 * - `forceWait` and the replaced `fuelMessage` (Lua `StarMap:ForceWaitMessage(prim)`): StarMap::OnLoop's rewrite
 *   (CustomMap.cpp, priority 9999) sets `outOfFuel = fuel_count < 1 || forceWait`, so a script can hold the player
 *   on the wait/distress controls with fuel left; the script's primitive replaces the NO FUEL text.
 * - showAllConnections.currentValue: with `splitAllNodeView` it follows the superGrid button (on until clicked off,
 *   then the hyperspace.xml default), without it the More Info mode as last applied or the default (MoreInfoButton.cpp).
 */
object HsStarMap {
    /** CustomMap.cpp `bool forceWait`: never saved, kept across runs until a script clears it. */
    @JvmField var forceWait = false

    /** StarMap::fuelMessage as a script set it; null while it is the game's own (our NO FUEL image). */
    @JvmField var waitMessage: LuaPrimitive? = null

    /** StarMap::hoverLoc: the beacon MouseMove last found under the mouse; it stays when the map closes. */
    @JvmField var hoverLoc: xyz.znix.xftl.sector.Beacon? = null

    /** CustomOptionsManager::showAllNodeConnections / showAllNodeConnectionsChanged (StarMap.cpp). */
    @JvmField var showAllNodeConnections = true
    @JvmField var showAllNodeConnectionsChanged = true

    /** HS reload (scripts restart; the C++ statics keep their values in HS, but a new session starts clean here). */
    fun reset() {
        forceWait = false; waitMessage = null; hoverLoc = null
        showAllNodeConnections = true; showAllNodeConnectionsChanged = true
    }

    /**
     * `StarMap:ForceWaitMessage(waitMessage = nullptr)`: a primitive replaces fuelMessage and forces the wait; nil
     * restores the default message and lifts it.
     */
    fun forceWaitMessage(prim: LuaPrimitive?) {
        if (prim != null) { waitMessage = prim; forceWait = true } else { waitMessage = null; forceWait = false }
    }

    /** StarMap::OnLoop rewrite: `outOfFuel = shipManager->fuel_count < 1 || forceWait`. */
    @JvmStatic
    fun outOfFuel(fuel: Int): Boolean = fuel < 1 || (HS.active && forceWait)

    /** CustomOptionsManager::showAllConnections.currentValue. */
    @JvmStatic
    fun showAllConnections(): Boolean {
        val cfg = HS.cfg()
        return if (cfg.splitAllNodeView) (showAllNodeConnections || cfg.showAllConnections)
        else xyz.znix.xftl.hs.render.HsMoreInfo.applied || cfg.showAllConnections
    }

    // ---- the superGrid button (StarMap constructor: Button "map/button_supergridOff" at (344, 550), active) ----
    private const val GRID_X = 344
    private const val GRID_Y = 550
    private var gridHover = false
    private var gridImages: Array<Image>? = null
    private var gridImagesOn = false
    private var gridGame: InGameState? = null
    private var gridTooltip: FixedDelayedTooltip? = null

    /** StarMap::OnLoop (StarMap.cpp): the toggle's change is applied (the image base follows it). */
    private fun applyToggle() {
        if (showAllNodeConnectionsChanged) showAllNodeConnectionsChanged = false
    }

    private fun images(game: InGameState): Array<Image> {
        val on = showAllNodeConnections
        gridImages?.let { if (gridImagesOn == on && gridGame === game) return it }
        val base = if (on) "img/map/button_supergridOn" else "img/map/button_supergridOff"
        val imgs = arrayOf(game.getImg("${base}_on.png"), game.getImg("${base}_select2.png"))
        gridImages = imgs; gridImagesOn = on; gridGame = game
        return imgs
    }

    /** StarMap::OnRender hook: the button (splitAllNodeView, not while choosing a sector). [x], [y]: the mouse. */
    @JvmStatic
    fun renderGrid(game: InGameState, g: Graphics, choosingSector: Boolean) {
        if (!HS.active || !HS.cfg().splitAllNodeView || choosingSector) return
        applyToggle()
        val imgs = images(game)
        val img = if (gridHover) imgs[1] else imgs[0]
        img.draw(GRID_X.toFloat(), GRID_Y.toFloat())
        if (gridHover) {
            val t = gridTooltip?.takeIf { gridGame === game } ?: FixedDelayedTooltip(game, "tooltip_supergrid").also { gridTooltip = it }
            g.tooltip = t
        }
    }

    /** StarMap::MouseMove hook: Button::MouseMove over the button's image rectangle. */
    @JvmStatic
    fun mouseMoveGrid(game: InGameState, x: Int, y: Int, choosingSector: Boolean) {
        if (!HS.active || !HS.cfg().splitAllNodeView || choosingSector) { gridHover = false; return }
        val img = images(game)[0]
        gridHover = x >= GRID_X && x < GRID_X + img.width && y >= GRID_Y && y < GRID_Y + img.height
    }

    /** StarMap::MouseClick hook: a click on the hovered button toggles the view, with the More Info sounds. */
    @JvmStatic
    fun clickGrid(game: InGameState, choosingSector: Boolean): Boolean {
        if (!HS.active || !HS.cfg().splitAllNodeView || choosingSector || !gridHover) return false
        showAllNodeConnections = !showAllNodeConnections
        showAllNodeConnectionsChanged = true
        game.sounds.getSampleOrWarn(if (showAllNodeConnections) "moreInfoOn" else "moreInfoOff")?.play()
        return true
    }
}
