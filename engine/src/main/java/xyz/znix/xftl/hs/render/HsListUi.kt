package xyz.znix.xftl.hs.render

import xyz.znix.xftl.GameText
import xyz.znix.xftl.hangar.SelectShipState
import xyz.znix.xftl.hs.ship.HsVictories
import xyz.znix.xftl.rendering.Colour
import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.NativeFonts

// Ported from FTL-Hyperspace (GPL-3.0-or-later) 1b05a07 src/game/UserInterface/CustomShipSelect.cpp
// (CustomShipSelect::OnRender / MouseClick / MouseMove victory-filter box, UpdateFilteredAchievements, the altLockedMiniships
// image choice). This engine is AGPL-3.0-or-later.

/**
 * The Hyperspace additions to the ship list: the victory-filter box under the cards (up to three victory types replace the
 * cards' own ship achievements), and the `altLockedMiniships` base image of a locked card.
 */
class HsListUi(private val state: SelectShipState, touch: Boolean = false) {
    /** The touch list has the box right of its lower cards, under the type tabs. */
    private val dx = if (touch) 95 else 0
    private val dy = if (touch) 8 else 0
    private val titleFont: NativeFonts.NativeFont get() = NativeFonts.font(state::getFont, 13)
    private var selected = -1

    private fun types() = HsVictories.shown()

    private fun pos(i: Int, n: Int): Pair<Int, Int> {
        val maxA = n % 6
        val maxB = n / 6
        return Pair(640 + dx - 17 * (if (i / 6 == maxB) maxA else 6) + 34 * (i % 6), 607 + dy - 17 * ((n + 5) / 6) + 34 * (i / 6))
    }

    val hasFilters get() = types().isNotEmpty()

    /** ShipSelect::infoBox is empty (no card hovered): the filter box is drawn and reacts. */
    fun draw(g: Graphics, infoBoxShown: Boolean) {
        if (infoBoxShown) return
        val types = types()
        if (types.isEmpty()) return
        state.getImgOrNull("img/customizeUI/box_shipach2.png")?.draw(515 + dx, 525 + dy)
        titleFont.printCenter(640f + dx, 544f + dy, state.translator["hangar_achievements_title_filter"], Colour.white)
        val enabled = HsVictories.shownFilters()
        types.forEachIndexed { i, t ->
            val (x, y) = pos(i, types.size)
            val icon = HsVictories.iconName(t)
            if (icon.isNotEmpty()) state.getImgOrNull("img/achievements/${icon}_${if (t in enabled) "on" else "off"}.png")?.draw(x, y)
            if (i == selected) {
                g.colour = Colour(255, 230, 94)
                g.drawRect(x, y, 31, 31); g.drawRect(x + 1, y + 1, 29, 29)
            }
        }
    }

    /** MouseMove: the filter under the mouse, with its name as the tooltip (null when none). */
    fun hover(infoBoxShown: Boolean): String? {
        selected = -1
        if (infoBoxShown) return null
        val types = types()
        val m = state.mousePos
        for (i in types.indices) {
            val (x, y) = pos(i, types.size)
            if (m.x > x && m.x < x + 32 && m.y > y && m.y < y + 32) {
                selected = i
                return HsVictories.filterName(types[i]) { it.get(state.translator) }.replace("\\1", "").trim()
            }
        }
        return null
    }

    /** MouseClick: toggle the filter under the mouse. */
    fun click(infoBoxShown: Boolean) {
        if (infoBoxShown || selected == -1) return
        val t = types().getOrNull(selected) ?: return
        val on = HsVictories.toggleFilter(t)
        state.sounds.getSample(if (on) "powerUpSystem" else "powerDownSystem").play()
    }

    /** A filtered card shows these victory views (at most three) instead of its ship achievements; empty = no filter. */
    fun filtered(): List<String> = HsVictories.shownFilters().take(3)

    /** `altLockedMiniships`: the `_base` image of a locked card when the file exists. */
    fun miniship(img: String, locked: Boolean): String {
        if (locked && xyz.znix.xftl.hs.HS.cfg().altLockedMiniships && state.getImgOrNull("img/customizeUI/miniship_${img}_base.png") != null)
            return "img/customizeUI/miniship_${img}_base.png"
        return "img/customizeUI/miniship_$img.png"
    }
}
