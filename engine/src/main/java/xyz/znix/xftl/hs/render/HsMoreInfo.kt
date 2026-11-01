// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/MoreInfoButton.cpp and
// src/game/Utilities/CustomOptions.h (the options altMode switches)
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HS
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.HotkeyDelayedTooltip
import xyz.znix.xftl.rendering.Image

/**
 * The More Info button (CommandGui::OnInit: `statusUI/moreInfoButtonOff` at 1207, 656), drawn after the ship status
 * (ShipStatus::OnRender), hovered with the `tooltip_more_info` tooltip (\1 = the `info` hotkey) and toggled by a
 * left click (or the `info` hotkey) with the `moreInfoOn`/`moreInfoOff` sound. [HsOverlay.altMode] (default on)
 * switches the options below on; off, they return to hyperspace.xml's values (CommandGui::OnLoop).
 */
object HsMoreInfo {
    private const val X = 1207
    private const val Y = 656

    private var hovered = false
    private var tooltip: HotkeyDelayedTooltip? = null
    private var tooltipGame: InGameState? = null
    private var images: Array<Image>? = null
    private var imagesOn = false
    private var imagesGame: InGameState? = null

    // ---- effective option values (CustomOptionsManager::*.currentValue) ----
    private fun cfg(): HsConfig = HS.cfg()
    @JvmStatic fun showWeaponCooldown() = HsOverlay.altMode || cfg().showWeaponCooldown
    @JvmStatic fun eventTooltips() = HsOverlay.altMode || cfg().eventTooltips
    @JvmStatic fun showReactor() = HsOverlay.altMode || cfg().showReactor
    @JvmStatic fun showAllConnections() = if (cfg().splitAllNodeView) cfg().showAllConnections else HsOverlay.altMode || cfg().showAllConnections
    @JvmStatic fun alternateOxygenRendering() = !HsOverlay.altMode && cfg().alternateOxygenRendering
    @JvmStatic fun redesignedWeaponTooltips() = HsOverlay.altMode || cfg().redesignedWeaponTooltips
    @JvmStatic fun redesignedDroneTooltips() = HsOverlay.altMode || cfg().redesignedDroneTooltips
    @JvmStatic fun redesignedAugmentTooltips() = HsOverlay.altMode || cfg().redesignedAugmentTooltips
    @JvmStatic fun showAllyPowers() = HsOverlay.altMode || cfg().showAllyPowers
    @JvmStatic fun showEnemyPowers() = HsOverlay.altMode || cfg().showEnemyPowers
    /** advancedCrewTooltipRounding: 2 under altMode when the default is 0 or 1. */
    @JvmStatic fun advancedCrewTooltipRounding(): Int {
        val d = cfg().advancedCrewTooltipRounding
        return if (HsOverlay.altMode && (d == 0 || d == 1)) 2 else d
    }

    private fun images(game: InGameState): Array<Image> {
        val on = HsOverlay.altMode
        val cached = images
        if (cached != null && imagesOn == on && imagesGame === game) return cached
        val base = if (on) "img/statusUI/moreInfoButtonOn" else "img/statusUI/moreInfoButtonOff"
        // Button::OnInit: _on (idle, active), _off (inactive), _select2 (hover)
        val imgs = arrayOf(game.getImg("${base}_on.png"), game.getImg("${base}_select2.png"))
        images = imgs; imagesOn = on; imagesGame = game
        return imgs
    }

    /** ShipStatus::OnRender hook, after the status: Button::OnRender. */
    @JvmStatic
    fun render(game: InGameState, g: Graphics) {
        val imgs = images(game)
        (if (hovered) imgs[1] else imgs[0]).draw(X, Y)
        if (hovered) tooltip(game)?.let { g.tooltip = it }
    }

    /** CommandGui::MouseMove hook (no pause menu, options or game over open): hover and tooltip. */
    @JvmStatic
    fun mouseMove(game: InGameState, g: Graphics?, x: Int, y: Int, blocked: Boolean): Boolean {
        if (blocked) { hovered = false; return false }
        val img = images(game)[0]
        // Button::MouseMove: inside the hit box (the image)
        hovered = x >= X && x < X + img.width && y >= Y && y < Y + img.height
        return hovered
    }

    /** The tooltip while hovered (MouseControl::SetTooltip). */
    @JvmStatic
    fun tooltip(game: InGameState): HotkeyDelayedTooltip? {
        if (!hovered) return null
        val t = tooltip?.takeIf { tooltipGame === game } ?: HotkeyDelayedTooltip(game, "tooltip_more_info", "info").also { tooltip = it; tooltipGame = game }
        return t
    }

    /** CommandGui::LButtonDown hook (after the original): toggles altMode. */
    @JvmStatic
    fun click(game: InGameState): Boolean {
        if (!hovered) return false
        toggle(game)
        return true
    }

    /** The click and the `info` hotkey. */
    @JvmStatic
    fun toggle(game: InGameState) {
        HsOverlay.altMode = !HsOverlay.altMode
        HsOverlay.altModeChanged = true
        game.sounds.getSampleOrWarn(if (HsOverlay.altMode) "moreInfoOn" else "moreInfoOff")?.play()
    }
}
