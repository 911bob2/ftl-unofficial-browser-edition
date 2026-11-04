package xyz.znix.xftl.game

import xyz.znix.xftl.Constants
import xyz.znix.xftl.rendering.Graphics

/**
 * The player's shield layer squares beside the hull bar, as ShipStatus::RenderShields draws them.
 *
 * The status box has four squares. The game keeps each square kind (charged, uncharged, hacked, hacked and charged)
 * as a row of four images, the rows next to each other, followed by an unused entry and the five super-shield bar
 * segments. It draws layer `i` from the charged or uncharged row's entry `i` without checking the row's end, so a
 * shield past four layers reads the next rows: layers 5 to 8 draw over squares 1 to 4 again, charged ones with the
 * uncharged image, uncharged ones with the hacked image, hacked uncharged ones with the hacked-and-charged image, and
 * hacked charged ones (from the sixth) as the super-shield bar segments under the box. Hyperspace leaves this as it is.
 */
object ShieldSquares {
    private const val ROW = 4
    private val ROWS = arrayOf("on", "off", "hacked", "hacked_charged")

    /** One draw: [square] 0-3 with [image], or super-shield bar [segment] 0-4. */
    data class Draw(val square: Int, val image: String?, val segment: Int)

    /** The draws for [charged] of [layers] layers, in the game's order: the charged layers first. */
    fun draws(charged: Int, layers: Int, hacked: Boolean): List<Draw> {
        val out = ArrayList<Draw>()
        if (layers <= 0) return out
        val lit = charged.coerceIn(0, layers)
        for (i in 0 until lit) entry((if (hacked) 3 else 0) * ROW + i)?.let { out += it }
        for (i in lit until layers) entry((if (hacked) 2 else 1) * ROW + i)?.let { out += it }
        return out
    }

    /** Table entry [n]: four rows of squares, one empty entry, then the five bar segments (ten layers' worth). */
    private fun entry(n: Int): Draw? = when {
        n < ROWS.size * ROW -> Draw(n % ROW, "img/statusUI/top_shieldsquare1_${ROWS[n / ROW]}.png", -1)
        n == ROWS.size * ROW -> null
        n <= ROWS.size * ROW + 5 -> Draw(-1, null, n - ROWS.size * ROW - 1)
        else -> null
    }

    /** Super-shield bar segment [i]'s offset from the first; the middle one is a pixel narrower. */
    fun segmentX(i: Int): Int = i * 19 - (if (i >= 3) 1 else 0)
    fun segmentWidth(i: Int): Int = if (i == 2) 16 else 17

    /**
     * Draw the squares with square 0 at ([squareX], [squareY]) and the bar's first segment at ([barX], [barY]).
     */
    fun render(game: InGameState, g: Graphics, charged: Int, layers: Int, hacked: Boolean,
               squareX: Float, squareY: Float, barX: Float, barY: Float) {
        for (d in draws(charged, layers, hacked)) {
            if (d.image != null) {
                game.getImg(d.image).draw(squareX + 23 * d.square, squareY)
            } else {
                g.colour = Constants.SYS_ENERGY_ACTIVE
                g.fillRect(barX + segmentX(d.segment), barY, segmentWidth(d.segment).toFloat(), 6f)
            }
        }
    }
}
