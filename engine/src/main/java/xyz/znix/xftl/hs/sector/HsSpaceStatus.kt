// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1.23.2 src/game/UserInterface/CustomSpaceStatus.cpp (SpaceStatus::OnLoop,
// OnRender, RenderWarningText and MouseMove rewrites); the browser engine is AGPL-3.0, see docs/HYPERSPACE.md.
package xyz.znix.xftl.hs.sector

import xyz.znix.xftl.environment.AbstractEnvironment
import xyz.znix.xftl.game.InGameState
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.FixedDelayedTooltip
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts
import xyz.znix.xftl.rendering.ITooltipProvider

/**
 * Hyperspace's SpaceStatus: every hazard the scene has (g_hazards: asteroids, sun, pulsar, ASB, nebula, storm in that
 * order) gets an icon, 72 px apart and shifted left by 72 px per icon past two, the warning text under them for the
 * first one (36 px right with two icons, then 36 px left per extra icon), and a tooltip per icon. An ASB that targets
 * both ships has its neutral icon (danger_pds_neutral.png) and the yellow DANGER - ALL text.
 */
object HsSpaceStatus {
    // EnvId (Environments.h)
    const val ASTEROIDS = 1
    const val SUN = 2
    const val NEBULA = 3
    const val STORM = 4
    const val PULSAR = 5
    const val PDS = 6
    const val PDS_FRIENDLY = 9

    /** SpaceStatus position (690, 72); the icons at position.x - 30 (OnInit's primitives), the hit box per icon. */
    private const val X = 690
    private const val Y = 72

    private val tooltips = HashMap<String, FixedDelayedTooltip>()
    private var tooltipGame: InGameState? = null
    private var font: NativeFonts.NativeFont? = null
    private var fontGame: InGameState? = null

    /** SpaceStatus::OnLoop rewrite: the hazards of [env] in g_hazards' order. */
    @JvmStatic
    fun hazards(env: AbstractEnvironment): List<Int> = env.hsStackHazards

    /** The icon image of [hazard] (warningImages[hazard], warningPdsAll for an ASB on both ships). */
    private fun icon(hazard: Int, target: Int): String = when {
        (hazard == PDS || hazard == PDS_FRIENDLY) && target == 2 -> "danger_pds_neutral"
        hazard == ASTEROIDS -> "danger_asteroids"
        hazard == SUN -> "danger_star"
        hazard == NEBULA -> "danger_nebula"
        hazard == STORM -> "danger_storm"
        hazard == PULSAR -> "danger_pulsar"
        hazard == PDS_FRIENDLY -> "danger_pds_green"
        else -> "danger_pds"
    }

    /** SpaceStatus::MouseMove rewrite: the tooltip name of [hazard]. */
    private fun tip(hazard: Int, target: Int, inFleet: Boolean): String = when (hazard) {
        ASTEROIDS -> "asteroids"
        SUN -> "sun"
        NEBULA -> "nebula"
        STORM -> "storm"
        PULSAR -> "pulsar"
        else -> when (target) {
            0 -> if (inFleet) "PDS_FLEET" else "PDS_PLAYER"
            1 -> if (inFleet) "PDS_FLEET_ENEMY" else "PDS_ENEMY"
            else -> if (inFleet) "PDS_FLEET_ALL" else "PDS_ALL"
        }
    }

    /**
     * SpaceStatus::OnRender rewrite (after the incoming-fire message): the icons and the warning text, then the
     * hover test of MouseMove (the icons' hit boxes: the original's 70x73 box from (655, 69), moved per icon).
     * Returns the tooltip to show, or null.
     */
    @JvmStatic
    fun render(game: InGameState, g: Graphics, env: AbstractEnvironment, target: Int, inFleet: Boolean, mouseX: Int, mouseY: Int): ITooltipProvider? {
        val hazards = hazards(env)
        if (hazards.isEmpty()) return null
        val left = if (hazards.size > 2) (2 - hazards.size) * 72 else 0
        for ((i, h) in hazards.withIndex()) game.getImg("img/warnings/${icon(h, target)}.png").draw(X - 30 + left + 72 * i, Y)
        renderWarningText(game, hazards[0], target, if (hazards.size > 1) (3 - hazards.size) * 36 else 0)

        if (tooltipGame !== game) { tooltips.clear(); tooltipGame = game }
        for ((i, h) in hazards.withIndex()) {
            val x = 655 + i * 72 + left
            if (mouseX > x && mouseX < x + 70 && mouseY > 69 && mouseY < 69 + 73) {
                val name = tip(h, target, inFleet)
                return tooltips.getOrPut(name) { FixedDelayedTooltip(game, "tooltip_$name") }
            }
        }
        return null
    }

    /**
     * SpaceStatus::RenderWarningText rewrite: an ASB on both ships gives warning_pds_all in (255, 255, 90) on the
     * yellow glow, the allied one warning_pds_allies in (120, 255, 120) on the green, anything else
     * warning_environment_danger in (255, 50, 50) on the red; the glow is stretched over the measured text (font 52)
     * at (position.x + offset - w / 2, position.y + 50), the text centred on position.x + offset at position.y + 51.
     */
    @JvmStatic
    fun renderWarningText(game: InGameState, effect: Int, target: Int, textOffset: Int) {
        val (colour, glow, key) = when {
            (effect == PDS || effect == PDS_FRIENDLY) && target == 2 -> Triple(Colour(255, 255, 90), "yellow", "warning_pds_all")
            effect == PDS_FRIENDLY -> Triple(Colour(120, 255, 120), "green", "warning_pds_allies")
            else -> Triple(Colour(255, 50, 50), "red", "warning_environment_danger")
        }
        val text = game.translator[key]
        // MV empties warning_environment_danger; nothing is drawn then (oracle r2-ships).
        if (text.isEmpty()) return
        val f = font?.takeIf { fontGame === game } ?: NativeFonts.font(game::getFont, 52).also { font = it; fontGame = game }
        val x = X + textOffset
        val y = Y + 51
        val w = f.measure(text)
        val h = f.lineHeight
        game.getImg("img/warnings/backglow_warning_$glow.png").draw((x - w.toInt() / 2).toFloat(), (y - 1).toFloat(), w.toInt().toFloat(), h.toFloat())
        f.printCenter(x.toFloat(), y.toFloat(), text, colour)
    }
}
