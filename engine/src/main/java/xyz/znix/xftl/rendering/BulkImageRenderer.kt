package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30.*
import xyz.znix.xftl.f

/**
 * Instances of this class accumulate a bunch of stuff
 * to render, and then send it to the graphics API when flushed.
 *
 * This can be used to improve rendering performance for
 * stuff like text where there's otherwise a silly number
 * of draw calls.
 */
class BulkImageRenderer : BulkRenderer() {
    var imageFiltering: Int = Image.DEFAULT_TEXTURE_FILTERING

    fun pushImage(
        x1: Float, y1: Float, // Corner A on-screen
        x2: Float, y2: Float, // Corner B on-screen
        u1: Float, v1: Float, // Corner A in-texture
        u2: Float, v2: Float, // Corner B in-texture
        colour: Colour
    ) {
        pushImage(
            x1, y1, x2, y2,
            u1, v1, u2, v2,
            colour.r, colour.g, colour.b, colour.a
        )
    }

    fun pushImage(
        x1: Float, y1: Float, // Corner A on-screen
        x2: Float, y2: Float, // Corner B on-screen
        u1: Float, v1: Float, // Corner A in-texture
        u2: Float, v2: Float, // Corner B in-texture
        r: Float, g: Float, b: Float, a: Float // Modulate colour
    ) {
        pushVert(x1, y1, u1, v1, r, g, b, a)
        pushVert(x2, y2, u2, v2, r, g, b, a)
        pushVert(x2, y1, u2, v1, r, g, b, a)

        pushVert(x1, y1, u1, v1, r, g, b, a)
        pushVert(x1, y2, u1, v2, r, g, b, a)
        pushVert(x2, y2, u2, v2, r, g, b, a)
    }

    fun pushVert(x: Float, y: Float, u: Float, v: Float) {
        pushVert(x, y, u, v, 1f, 1f, 1f, 1f)
    }

    fun pushVert(
        x: Float, y: Float,
        u: Float, v: Float,
        r: Float, g: Float, b: Float, a: Float // Modulate colour
    ) {
        checkSize(8 * 4) // 8 32-bit floats

        // Transform our position to reflect the Graphics translate calls.
        // 3x3 matrix multiply, with (baseX,baseY,1)
        val m = Graphics.getTextureTransformMatrix()
        val transformedX = x * m.m00 + y * m.m01 + m.m02
        val transformedY = x * m.m10 + y * m.m11 + m.m12
        // Don't need to calculate a W value

        data.putFloat(transformedX)
        data.putFloat(transformedY)
        data.putFloat(u)
        data.putFloat(v)

        val tint = Graphics.tint
        data.putFloat(r * tint.r)
        data.putFloat(g * tint.g)
        data.putFloat(b * tint.b)
        data.putFloat(a * tint.a)

        numVerts++
    }

    fun flush(image: Image) {
        if (numVerts == 0)
            return

        if (image.texture.freed) {
            // Its game content was replaced and the texture deleted. WebGL refuses to bind it and the quad would
            // sample whatever texture was bound before, so draw nothing and say so.
            Texture.reportFreedDraw(image)
            data.clear()
            numVerts = 0
            return
        }

        val texture = image.texture
        val scaleU = 1f / texture.rawTextureWidth
        val scaleV = 1f / texture.rawTextureHeight
        val offsetU = image.textureOffsetX.f / texture.rawTextureWidth
        val offsetV = image.textureOffsetY.f / texture.rawTextureHeight

        data.flip()
        DrawQueue.begin(GL_TRIANGLES, texture, imageFiltering, numVerts)
        for (i in 0 until numVerts) {
            val at = i * 32
            DrawQueue.vert(
                data.getFloat(at), data.getFloat(at + 4),
                // UVs are 0-1 in both axes.
                data.getFloat(at + 8) * scaleU + offsetU, data.getFloat(at + 12) * scaleV + offsetV,
                data.getFloat(at + 16), data.getFloat(at + 20), data.getFloat(at + 24), data.getFloat(at + 28)
            )
        }

        // Clear out the buffer for subsequent renders
        data.clear()
        numVerts = 0
    }
}
