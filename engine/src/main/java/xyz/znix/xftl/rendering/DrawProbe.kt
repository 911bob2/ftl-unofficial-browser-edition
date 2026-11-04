package xyz.znix.xftl.rendering

/**
 * The rectangles a stretch of drawing covered, one per draw, in framebuffer pixels: set it as [DrawQueue.probe], draw
 * (flushing the batches first and last), call [end], then read [rects] and [solids]. Vertices without alpha cover nothing.
 *
 * [rects] are the draws' whole quads; [solids] what of them shows solid: a quad of an image whose [Texture.solid] box is
 * known covers only that box (its transparent margins and glow left out), any other draw its whole quad. A draw that shows
 * nothing solid has no entry there.
 */
class DrawProbe {
    /** minX, minY, maxX, maxY of each draw that left something visible. */
    val rects = ArrayList<FloatArray>()

    /** The same for the solid part of each draw. */
    val solids = ArrayList<FloatArray>()

    private var current: FloatArray? = null
    private var currentSolid: FloatArray? = null
    private var texture: Texture? = null

    /** The vertices of the quad being drawn (x, y, u, v, a each), for an image with a solid box. */
    private val quad = FloatArray(6 * 5)
    private var quadVerts = 0

    fun reset() {
        rects.clear()
        solids.clear()
        current = null
        currentSolid = null
        texture = null
        quadVerts = 0
    }

    /** A new draw starts, of [texture] (null for a colour draw). */
    fun newDraw(texture: Texture? = null) {
        current = null
        currentSolid = null
        quadVerts = 0
        this.texture = texture?.takeIf { it.solid != null }
    }

    /** The last draw is over. */
    fun end() = newDraw(null)

    fun point(x: Float, y: Float, u: Float, v: Float, a: Float) {
        if (texture != null) {
            // Triangle lists: a quad is six vertices.
            val i = quadVerts * 5
            quad[i] = x; quad[i + 1] = y; quad[i + 2] = u; quad[i + 3] = v; quad[i + 4] = a
            if (++quadVerts == 6) {
                quadVerts = 0
                solidQuad()
            }
        }
        if (a <= ALPHA_NONE) return
        current = grow(current, rects, x, y, x, y)
        if (texture == null) currentSolid = grow(currentSolid, solids, x, y, x, y)
    }

    /** The part of the six [quad] vertices that shows the texture's solid box, when the quad is upright and unrotated. */
    private fun solidQuad() {
        var visible = false
        var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE; var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
        for (k in 0 until 6) {
            val i = k * 5
            if (quad[i + 4] > ALPHA_NONE) visible = true
            u0 = minOf(u0, quad[i + 2]); u1 = maxOf(u1, quad[i + 2]); v0 = minOf(v0, quad[i + 3]); v1 = maxOf(v1, quad[i + 3])
        }
        if (!visible) return
        // Screen x at the quad's smallest and largest u, y at its smallest and largest v.
        var xu0 = Float.NaN; var xu1 = Float.NaN; var yv0 = Float.NaN; var yv1 = Float.NaN
        for (k in 0 until 6) {
            val i = k * 5
            if (quad[i + 2] == u0) xu0 = quad[i]
            if (quad[i + 2] == u1) xu1 = quad[i]
            if (quad[i + 3] == v0) yv0 = quad[i + 1]
            if (quad[i + 3] == v1) yv1 = quad[i + 1]
        }
        var upright = u1 > u0 && v1 > v0
        if (upright) for (k in 0 until 6) {
            val i = k * 5
            val x = xu0 + (quad[i + 2] - u0) / (u1 - u0) * (xu1 - xu0)
            val y = yv0 + (quad[i + 3] - v0) / (v1 - v0) * (yv1 - yv0)
            if (Math.abs(x - quad[i]) > 0.05f || Math.abs(y - quad[i + 1]) > 0.05f) upright = false
        }
        if (!upright) {
            for (k in 0 until 6) currentSolid = grow(currentSolid, solids, quad[k * 5], quad[k * 5 + 1], quad[k * 5], quad[k * 5 + 1])
            return
        }
        val t = texture!!
        val box = t.solid!!
        val su0 = maxOf(u0, box[0].toFloat() / t.rawTextureWidth)
        val su1 = minOf(u1, box[2].toFloat() / t.rawTextureWidth)
        val sv0 = maxOf(v0, box[1].toFloat() / t.rawTextureHeight)
        val sv1 = minOf(v1, box[3].toFloat() / t.rawTextureHeight)
        if (su1 <= su0 || sv1 <= sv0) return
        val ax = xu0 + (su0 - u0) / (u1 - u0) * (xu1 - xu0)
        val bx = xu0 + (su1 - u0) / (u1 - u0) * (xu1 - xu0)
        val ay = yv0 + (sv0 - v0) / (v1 - v0) * (yv1 - yv0)
        val by = yv0 + (sv1 - v0) / (v1 - v0) * (yv1 - yv0)
        currentSolid = grow(currentSolid, solids, minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
    }

    private fun grow(r: FloatArray?, list: ArrayList<FloatArray>, x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        if (r == null) return floatArrayOf(x0, y0, x1, y1).also { list += it }
        if (x0 < r[0]) r[0] = x0
        if (y0 < r[1]) r[1] = y0
        if (x1 > r[2]) r[2] = x1
        if (y1 > r[3]) r[3] = y1
        return r
    }

    private companion object {
        const val ALPHA_NONE = 0.004f
    }
}
