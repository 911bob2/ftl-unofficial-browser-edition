package xyz.znix.xftl.rendering

import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * What the bulk renderers draw, in the order they flushed it, held back until something needs it on the framebuffer.
 *
 * A GL call per draw is what costs most frames: a frame has several hundred draws, most of them next to each other with
 * nothing different but the vertices. Here a frame's vertices go up as one buffer, and neighbouring draws that need the
 * same state become one GL draw. Only neighbours join, so the order on screen does not change.
 *
 * Anything that could change what a held draw looks like has to [drain] first. The GL wrappers (GL11) do that for every
 * state change, texture upload and delete; code calling GLES directly (the Android frame, pixel reads) calls it itself.
 */
object DrawQueue {
    private const val STRIDE = 8 * 4

    /** The u of a vertex with no picture: the shader leaves its colour alone. */
    const val NO_TEXTURE_U = -1_000_000f

    // In the engine, not its resource pack: both ends of the vertex format change together.
    private const val VERTEX_SHADER = """
        #version 330 core

        in vec2 pos;
        in vec2 uv;
        in vec4 filterColour;

        out vec2 varying_uv;
        out vec4 varying_colour;

        uniform mat3 posTransform;

        void main() {
            vec2 transformedPos = (posTransform * vec3(pos, 1)).xy;
            gl_Position = vec4(transformedPos, 0, 1);
            varying_uv = uv;
            varying_colour = filterColour;
        }
    """

    private const val FRAGMENT_SHADER = """
        #version 330 core

        in vec2 varying_uv;
        in vec4 varying_colour;

        out vec4 colour;

        uniform sampler2D tex;

        void main() {
            vec4 texColour = texture(tex, varying_uv);

            // A plain colour: its u is far outside any picture (DrawQueue.NO_TEXTURE_U).
            if (varying_uv.x < -1000.0) {
                colour = varying_colour;
                return;
            }

            colour = texColour * varying_colour;

            // This is required for stencil writing, since alpha testing is gone in OpenGL core.
            if (colour.a == 0) {
                discard;
            }
        }
    """

    private var stream: ByteBuffer = allocate(1 shl 18)
    private var verts = 0

    private var runCount = 0
    private var modes = IntArray(256)
    private var firsts = IntArray(256)
    private var counts = IntArray(256)
    private var filters = IntArray(256)
    private var textures = arrayOfNulls<Texture>(256)

    private var shader: ShaderProgramme? = null
    private var vao = 0
    private var vbo = 0
    private var posAttrib = 0
    private var uvAttrib = 0
    private var colourAttrib = 0
    private var posTransformLoc = 0
    private val transform = allocate(4 * 9).asFloatBuffer()

    // What a frame cost, for the debug tools: draws asked for, GL draws made, vertex uploads.
    private var flushes = 0
    private var draws = 0
    private var uploads = 0
    var lastFrameFlushes = 0
        private set
    var lastFrameDraws = 0
        private set
    var lastFrameUploads = 0
        private set

    /** For the checks: no joining and no holding back, one upload and one GL draw per draw asked for. */
    @JvmField var unbatched = false

    /** While set, it is told every draw and vertex position too (the touch layout measures what a desktop element drew). */
    @JvmField var probe: DrawProbe? = null

    /**
     * Start a draw of [vertCount] vertices. A draw without a picture passes a null [texture]: its shader path ignores
     * whatever is bound, so it can share a run with any picture.
     */
    fun begin(mode: Int, texture: Texture?, filter: Int, vertCount: Int) {
        flushes++
        probe?.newDraw()
        if (unbatched) drain()
        val needed = (verts + vertCount) * STRIDE
        if (needed > stream.capacity()) {
            val grown = allocate(max(needed, stream.capacity() * 2))
            stream.flip()
            grown.put(stream)
            stream = grown
        }
        val last = runCount - 1
        val joins = last >= 0 && modes[last] == mode && (
            texture == null || textures[last] == null || (textures[last] === texture && filters[last] == filter))
        if (joins) {
            if (texture != null && textures[last] == null) {
                textures[last] = texture
                filters[last] = filter
            }
            counts[last] += vertCount
        } else {
            if (runCount == modes.size) growRuns()
            modes[runCount] = mode
            firsts[runCount] = verts
            counts[runCount] = vertCount
            textures[runCount] = texture
            filters[runCount] = filter
            runCount++
        }
        verts += vertCount
    }

    /** One of the vertices promised to [begin]. */
    fun vert(x: Float, y: Float, u: Float, v: Float, r: Float, g: Float, b: Float, a: Float) {
        probe?.point(x, y, a)
        val s = stream
        s.putFloat(x)
        s.putFloat(y)
        s.putFloat(u)
        s.putFloat(v)
        s.putFloat(r)
        s.putFloat(g)
        s.putFloat(b)
        s.putFloat(a)
    }

    /** Draw everything held, now. */
    @JvmStatic
    fun drain() {
        val runs = runCount
        if (runs == 0) return
        // First, as the GL calls below come back here through the wrappers.
        runCount = 0

        val s = getShader()
        GL30.glBindVertexArray(getVao())
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, vbo)
        stream.flip()
        GL30.glBufferData(GL30.GL_ARRAY_BUFFER, stream, GL30.GL_STREAM_DRAW)
        stream.clear()
        verts = 0
        uploads++

        GL30.glVertexAttribPointer(posAttrib, 2, GL30.GL_FLOAT, false, STRIDE, 0)
        GL30.glVertexAttribPointer(uvAttrib, 2, GL30.GL_FLOAT, false, STRIDE, 8)
        GL30.glVertexAttribPointer(colourAttrib, 4, GL30.GL_FLOAT, false, STRIDE, 16)
        GL30.glUseProgram(s.handle)
        setTransform()

        var bound: Texture? = null
        var boundFilter = -1
        for (i in 0 until runs) {
            val texture = textures[i]
            if (texture != null && (texture !== bound || filters[i] != boundFilter)) {
                texture.bind(filters[i])
                bound = texture
                boundFilter = filters[i]
            }
            GL30.glDrawArrays(modes[i], firsts[i], counts[i])
            textures[i] = null
        }
        draws += runs

        GL30.glUseProgram(0)
        GL30.glBindBuffer(GL30.GL_ARRAY_BUFFER, 0)
        GL30.glBindVertexArray(0)
    }

    /** The frame is drawn: everything goes to the framebuffer. */
    @JvmStatic
    fun endFrame() {
        drain()
        lastFrameFlushes = flushes
        lastFrameDraws = draws
        lastFrameUploads = uploads
        flushes = 0
        draws = 0
        uploads = 0
    }

    /** The GL context was (re)created: handles from the old one mean nothing, and what was held is gone. */
    fun onContextRecreated() {
        shader = null
        vao = 0
        vbo = 0
        runCount = 0
        verts = 0
        stream.clear()
        textures.fill(null)
    }

    private fun setTransform() {
        // From pixels to normalised device coordinates, which run -1 to 1 with positive y up.
        val size = ShaderProgramme.SHADER_SCREEN_SIZE
        transform.clear()
        transform.put(2f / size.x)
        transform.put(0f)
        transform.put(0f)

        transform.put(0f)
        transform.put(-2f / size.y)
        transform.put(0f)

        transform.put(-1f)
        transform.put(1f)
        transform.put(0f)
        transform.flip()
        GL30.glUniformMatrix3fv(posTransformLoc, false, transform)
    }

    private fun getShader(): ShaderProgramme {
        var s = shader
        if (s == null) {
            s = ShaderProgramme(VERTEX_SHADER.trimIndent(), FRAGMENT_SHADER.trimIndent())
            posAttrib = s.getAttributeLocation("pos")
            uvAttrib = s.getAttributeLocation("uv")
            colourAttrib = s.getAttributeLocation("filterColour")
            posTransformLoc = s.getUniformLocation("posTransform")
            shader = s
        }
        return s
    }

    private fun getVao(): Int {
        if (vao == 0) {
            vao = GL30.glGenVertexArrays()
            vbo = GL30.glGenBuffers()
            GL30.glBindVertexArray(vao)
            GL30.glEnableVertexAttribArray(posAttrib)
            GL30.glEnableVertexAttribArray(uvAttrib)
            GL30.glEnableVertexAttribArray(colourAttrib)
        }
        return vao
    }

    private fun growRuns() {
        val size = modes.size * 2
        modes = modes.copyOf(size)
        firsts = firsts.copyOf(size)
        counts = counts.copyOf(size)
        filters = filters.copyOf(size)
        textures = textures.copyOf(size)
    }

    private fun allocate(size: Int): ByteBuffer = ByteBuffer.allocateDirect(size).apply { order(ByteOrder.nativeOrder()) }
}
