package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30.*

class BulkColourRenderer(val drawType: Int) : BulkRenderer() {
    fun pushVert(
        x: Float, y: Float,
        r: Float, g: Float, b: Float, a: Float
    ) {
        checkSize(6 * 4) // 32-bit floats

        // Transform our position to reflect the Graphics translate calls.
        // 3x3 matrix multiply, with (baseX,baseY,1)
        val m = Graphics.getTextureTransformMatrix()
        val transformedX = x * m.m00 + y * m.m01 + m.m02
        val transformedY = x * m.m10 + y * m.m11 + m.m12
        // Don't need to calculate a W value

        data.putFloat(transformedX)
        data.putFloat(transformedY)

        val tint = Graphics.tint
        data.putFloat(r * tint.r)
        data.putFloat(g * tint.g)
        data.putFloat(b * tint.b)
        data.putFloat(a * tint.a)

        numVerts++
    }

    fun pushVert(x: Float, y: Float, colour: Colour) {
        pushVert(
            x, y,
            colour.r, colour.g, colour.b, colour.a
        )
    }

    fun flush() {
        if (numVerts == 0)
            return

        // Packed as position then colour
        data.flip()
        val u = DrawQueue.NO_TEXTURE_U
        if (drawType == GL_QUADS) {
            // Quads isn't supported in OpenGL core, so each becomes two triangles.
            // Assume the vertices are added clockwise, as we build our CCW face.
            val quads = numVerts / 4
            DrawQueue.begin(GL_TRIANGLES, null, 0, quads * 6)
            for (q in 0 until quads) {
                val first = q * 4
                for (corner in QUAD_CORNERS) {
                    val at = (first + corner) * STRIDE
                    DrawQueue.vert(
                        data.getFloat(at), data.getFloat(at + 4), u, 0f,
                        data.getFloat(at + 8), data.getFloat(at + 12), data.getFloat(at + 16), data.getFloat(at + 20)
                    )
                }
            }
        } else {
            DrawQueue.begin(drawType, null, 0, numVerts)
            for (i in 0 until numVerts) {
                val at = i * STRIDE
                DrawQueue.vert(
                    data.getFloat(at), data.getFloat(at + 4), u, 0f,
                    data.getFloat(at + 8), data.getFloat(at + 12), data.getFloat(at + 16), data.getFloat(at + 20)
                )
            }
        }

        // Clear out the buffer for subsequent renders
        data.clear()
        numVerts = 0
    }

    private companion object {
        const val STRIDE = 4 * (2 + 4)
        val QUAD_CORNERS = intArrayOf(0, 2, 1, 2, 0, 3)
    }
}
