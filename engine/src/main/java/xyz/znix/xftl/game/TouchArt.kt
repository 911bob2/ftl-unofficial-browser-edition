package xyz.znix.xftl.game

import xyz.znix.xftl.rendering.Graphics
import xyz.znix.xftl.rendering.Image

/** Drawing helpers for the screens of the touch layout (TouchMode). */
object TouchArt {
    /**
     * Runs [draw] in the coordinates of a [artW]×[artH] picture scaled to cover the canvas, so the screen has no black
     * bars: centred across, and centred or from its top edge down when the top of the picture must stay on screen.
     */
    inline fun cover(g: Graphics, artW: Int, artH: Int, canvasW: Int, canvasH: Int, fromTop: Boolean = false, draw: () -> Unit) {
        val scale = maxOf(canvasW / artW.toFloat(), canvasH / artH.toFloat())
        g.pushTransform()
        g.translate((canvasW - artW * scale) / 2, if (fromTop) 0f else (canvasH - artH * scale) / 2)
        g.scale(scale, scale)
        draw()
        g.popTransform()
    }

    /** A picture stretched to [w]×[h] with its [cap]-pixel edges and corners kept at their size. */
    fun frame(img: Image, x: Int, y: Int, w: Int, h: Int, cap: Int) {
        val c = cap.toFloat()
        val xs = floatArrayOf(x.toFloat(), x + c, x + w - c, (x + w).toFloat())
        val ys = floatArrayOf(y.toFloat(), y + c, y + h - c, (y + h).toFloat())
        val us = floatArrayOf(0f, c, img.width - c, img.width.toFloat())
        val vs = floatArrayOf(0f, c, img.height - c, img.height.toFloat())
        for (j in 0..2) for (i in 0..2) img.draw(xs[i], ys[j], xs[i + 1], ys[j + 1], us[i], vs[j], us[i + 1], vs[j + 1])
    }
}
