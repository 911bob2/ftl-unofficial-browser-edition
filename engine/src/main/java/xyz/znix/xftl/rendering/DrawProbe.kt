package xyz.znix.xftl.rendering

/**
 * The rectangles a stretch of drawing covered, one per draw, in framebuffer pixels: set it as [DrawQueue.probe], draw
 * (flushing the batches first and last), then read [rects]. Vertices without alpha cover nothing.
 */
class DrawProbe {
    /** minX, minY, maxX, maxY of each draw that left something visible. */
    val rects = ArrayList<FloatArray>()
    private var current: FloatArray? = null

    fun reset() {
        rects.clear()
        current = null
    }

    fun newDraw() {
        current = null
    }

    fun point(x: Float, y: Float, a: Float) {
        if (a <= 0.004f) return
        val c = current
        if (c == null) {
            val r = floatArrayOf(x, y, x, y)
            rects += r
            current = r
            return
        }
        if (x < c[0]) c[0] = x
        if (y < c[1]) c[1] = y
        if (x > c[2]) c[2] = x
        if (y > c[3]) c[3] = y
    }
}
