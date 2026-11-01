package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL11
import org.newdawn.slick.opengl.ImageData
import xyz.znix.xftl.sys.ResourceContext
import java.nio.ByteBuffer

/**
 * Texture pages that glyphs are copied into the first time a string draws them: the language fonts (the Japanese
 * bitmap fonts are 4096-pixel textures, the TrueType fonts have no texture at all) keep only the glyphs in use on
 * the GPU. Glyphs are white with their coverage in alpha, like the bitmap fonts' textures.
 */
class GlyphAtlas(private val context: ResourceContext, private val pageSize: Int = 1024) {
    val pages = ArrayList<Image>()
    private var shelfX = 0
    private var shelfY = 0
    private var shelfHeight = 0

    class Slot(val page: Int, val x: Int, val y: Int)

    /** Copies [w] x [h] alpha values (row-major, [stride] per row, from [offset]) into a page. */
    fun add(w: Int, h: Int, alpha: ByteArray, offset: Int = 0, stride: Int = w): Slot {
        if (w <= 0 || h <= 0) return Slot(0, 0, 0).also { if (pages.isEmpty()) newPage() }
        require(w + 2 <= pageSize && h + 2 <= pageSize) { "glyph ${w}x$h too large for the atlas" }
        if (pages.isEmpty()) newPage()
        if (shelfX + w + 1 > pageSize) {
            shelfX = 0
            shelfY += shelfHeight + 1
            shelfHeight = 0
        }
        if (shelfY + h + 1 > pageSize) {
            newPage()
        }
        val x = shelfX + 1
        val y = shelfY + 1
        shelfX += w + 1
        shelfHeight = maxOf(shelfHeight, h + 1)

        val rgba = ByteArray(w * h * 4)
        for (row in 0 until h) for (col in 0 until w) {
            val i = (row * w + col) * 4
            rgba[i] = -1; rgba[i + 1] = -1; rgba[i + 2] = -1
            rgba[i + 3] = alpha[offset + row * stride + col]
        }
        val page = pages.last()
        page.texture.bind(GL11.GL_NEAREST)
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, ByteBuffer.wrap(rgba))
        return Slot(pages.size - 1, x, y)
    }

    private fun newPage() {
        pages.add(TextureLoader.loadImage(context, BlankPage(pageSize)))
        shelfX = 0
        shelfY = 0
        shelfHeight = 0
    }

    /** A transparent white page. */
    private class BlankPage(val size: Int) : ImageData {
        private val data: ByteBuffer = ByteBuffer.allocateDirect(size * size * 4).also {
            for (i in 0 until size * size) { it.put(-1); it.put(-1); it.put(-1); it.put(0) }
            it.position(0)
        }

        override fun getHeight(): Int = size
        override fun getTexWidth(): Int = size
        override fun getDepth(): Int = 32
        override fun getImageBufferData(): ByteBuffer = data
        override fun getWidth(): Int = size
        override fun getTexHeight(): Int = size
    }
}
