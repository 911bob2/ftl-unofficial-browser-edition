// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/Debug/Debugging.cpp (MouseControl::OnRender hook)
// and src/game/Utilities/CustomOptions.h (altMode).
package xyz.znix.xftl.hs.render

import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.hs.HsConfig
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.NativeFonts

/**
 * Hyperspace's screen-wide overlay: while CustomOptionsManager::altMode is on (the default; the More Info button
 * and the console's INFO command toggle it), MouseControl::OnRender prints `HS-<version>` in white, font 51,
 * right-aligned at x 1280, y 0, before the cursor and tooltip are drawn. It shows on every screen (menu, hangar,
 * game), as on the oracle frames.
 */
object HsOverlay {
    /** CustomOptionsManager::altMode (default true) and altModeChanged. */
    @JvmField var altMode = true
    @JvmField var altModeChanged = true

    private var font: NativeFonts.NativeFont? = null
    private var fontContent: InGameState.GameContent? = null
    /** "HS-" + HS_Version.toIdentifierString(): "%s%s %s" of version, target system and identifier, both empty in
     *  the release build, so it ends in a space (FTL_HS.log: "Hyperspace: v1.23.2  Compiled"). */
    private const val LABEL = "HS-" + HsConfig.HS_VERSION + " "

    @JvmStatic
    fun reset() {
        altMode = true; altModeChanged = true
    }

    /** Call only while HS.active, where MouseControl::OnRender runs (before the tooltip). */
    @JvmStatic
    fun versionLabel(content: InGameState.GameContent) {
        if (!altMode) return
        // Rebuilt when the content (and its font textures) is reloaded.
        val f = font.takeIf { content === fontContent } ?: NativeFonts.font(content::getFont, 51).also { font = it; fontContent = content }
        f.printRight(1280f, 0f, LABEL, Colour.white)
    }
}
