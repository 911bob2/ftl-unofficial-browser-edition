package xyz.znix.xftl.game

import kotlin.math.abs

/**
 * The power bars in a touch HUD system box (TouchHud) and the vertical drag that sets them.
 *
 * The bars are the desktop's (MainSystem.drawPowerBars: 6 px a bar and a 2 px gap; shields in pairs with a 6 px gap
 * between pairs), drawn twice as tall. Up to eight levels the drag is the iPad's: a level per 16 px, a bar's step at
 * that height, with a dead band at each step's ends. A Hyperspace system with more levels (Multiverse's shields go to
 * 16) keeps the height of eight, so its bars shrink. The iPad has no such system; past eight levels the drag step is
 * the drawn bars' own spacing, which keeps the finger on the bar it sets.
 */
object TouchPowerDrag {
    /** One desktop bar and the gap above it. */
    const val DESK_BAR_STEP = 8f

    /** What the shields' gap between two pairs adds to [DESK_BAR_STEP] (Shields.getPowerBarSpacing: 6 instead of 2). */
    const val PAIR_GAP = 4f

    /** Up to this many levels a box draws its bars at the full doubled height. */
    const val MAX_TALL_BARS = 8

    /** The iPad's power drag: a level per 16 px. */
    const val IPAD_STEP = 16f

    /** The bars' vertical scale on the canvas: the desktop's doubled up to eight levels, the height of eight past that. */
    fun barScale(rows: Int): Float = if (rows > MAX_TALL_BARS) 2f * MAX_TALL_BARS / rows else 2f

    /** From the bottom bar's top to the top bar's, on the canvas; [paired]: the shields' pairs. */
    fun span(rows: Int, paired: Boolean): Float {
        val gaps = (rows - 1).coerceAtLeast(0)
        val pairGaps = if (paired) gaps / 2 * PAIR_GAP else 0f
        return (DESK_BAR_STEP * gaps + pairGaps) * barScale(rows)
    }

    /** One level's drag step on the canvas. */
    fun step(rows: Int, paired: Boolean): Float =
        if (rows <= MAX_TALL_BARS) IPAD_STEP else span(rows, paired) / (rows - 1)

    /**
     * The level a drag of [dy] (down is positive) from [start] asks for, or null in the dead band at each step's ends, so
     * a finger on the line does not flicker. Not clamped: the box clamps it to the system's levels.
     */
    fun level(start: Int, dy: Int, step: Float): Int? {
        val rest = abs(dy) % step
        return if (rest >= step * 2 / 16 && rest <= step * 13 / 16) start - (dy / step).toInt() else null
    }
}
