package xyz.znix.xftl.rendering

import kotlin.math.roundToInt

/**
 * A clip (scissor) rectangle in framebuffer pixels, GL's way round: [x], [y] its lower left corner, rows counted from the
 * bottom. [Graphics.pushClip] makes them from game-unit rectangles; nested ones are the intersection.
 */
data class ClipRect(val x: Int, val y: Int, val w: Int, val h: Int) {
    /** The part both cover (empty: zero width or height). */
    fun intersect(o: ClipRect): ClipRect {
        val l = maxOf(x, o.x)
        val b = maxOf(y, o.y)
        val r = minOf(x + w, o.x + o.w)
        val t = minOf(y + h, o.y + o.h)
        return ClipRect(l, b, maxOf(0, r - l), maxOf(0, t - b))
    }

    companion object {
        /**
         * The screen-unit rectangle [left]..[right], [top]..[bottom] (units of the [screenW] x [screenH] screen the
         * shaders map, y down) on a viewport at [vpX], [vpY] of [vpW] x [vpH] pixels. Edges are rounded, so clips side by
         * side meet without a gap or an overlap.
         */
        @JvmStatic
        fun fromScreen(left: Float, top: Float, right: Float, bottom: Float, screenW: Int, screenH: Int,
                       vpX: Int, vpY: Int, vpW: Int, vpH: Int): ClipRect {
            val sx = vpW.toFloat() / screenW
            val sy = vpH.toFloat() / screenH
            val l = (vpX + left * sx).roundToInt()
            val r = (vpX + right * sx).roundToInt()
            val b = (vpY + (screenH - bottom) * sy).roundToInt()
            val t = (vpY + (screenH - top) * sy).roundToInt()
            return ClipRect(l, b, maxOf(0, r - l), maxOf(0, t - b))
        }
    }
}
