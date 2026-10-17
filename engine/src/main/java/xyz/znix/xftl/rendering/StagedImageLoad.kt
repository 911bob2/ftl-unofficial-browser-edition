package xyz.znix.xftl.rendering

import ftl.browser.BrowserClock
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.newdawn.slick.opengl.InternalTextureLoader
import org.newdawn.slick.opengl.PNGDecoder
import xyz.znix.xftl.sys.ResourceContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

/**
 * Loads one PNG into a texture a slice at a time, so a big image (a 2048x1024 background decodes and uploads in about
 * a tenth of a second) can be spread over several frames instead of stalling one. [step] does about a given number
 * of milliseconds of work and says whether the image is finished; [finish] does all that is left at once, which is
 * how an image that is needed right now is loaded.
 *
 * The pixels are exactly what a one-shot load gives: decoded into a power-of-two buffer, the right column and the
 * bottom row replicated into the padding (see [TextureLoader.loadTexture] for why) and uploaded as RGBA. Nothing
 * here draws, so it can run at any point of a frame.
 */
class StagedImageLoad(
    private val context: ResourceContext,
    val path: String,
    bytes: ByteArray,
    /** Keep the padded pixels after the upload, for the checks to compare. */
    private val keepPixels: Boolean = false
) {
    private enum class Phase { START, DECODE, PAD_COLUMNS, PAD_ROWS, CREATE, UPLOAD, DONE }

    private val decoder = PNGDecoder(ByteArrayInputStream(bytes))
    private val width = decoder.width
    private val height = decoder.height
    private val texWidth = InternalTextureLoader.get2Fold(width)
    private val texHeight = InternalTextureLoader.get2Fold(height)

    private var phase = Phase.START
    private var pixels: ByteBuffer? = null
    private var padRow = 0
    private var textureId = 0
    private var uploadedRows = 0

    /** The finished image, once [step] has returned true. */
    var image: Image? = null
        private set

    val isDone: Boolean get() = phase == Phase.DONE

    /** The padded RGBA pixels as uploaded: only for the checks, and only when asked for with `keepPixels`. */
    internal val paddedPixels: ByteBuffer? get() = pixels

    /** How many texture rows the upload has sent, for the checks. */
    internal val uploadedRowCount: Int get() = uploadedRows

    internal val textureWidth: Int get() = texWidth
    internal val textureHeight: Int get() = texHeight

    /**
     * Works for about [budgetMs], never stopping in the middle of a unit of work (a few rows, or one band of the
     * upload), and returns true when the image is complete. An infinite budget loads it in one go.
     */
    fun step(budgetMs: Double): Boolean {
        val oneShot = budgetMs == Double.POSITIVE_INFINITY
        val end = if (oneShot) 0.0 else BrowserClock.now() + budgetMs
        while (phase != Phase.DONE) {
            when (phase) {
                Phase.START -> {
                    val buffer = ByteBuffer.allocate(texWidth * texHeight * 4)
                    pixels = buffer
                    decoder.beginDecode(buffer, texWidth * 4, PNGDecoder.RGBA)
                    phase = Phase.DECODE
                }

                Phase.DECODE -> {
                    if (decoder.decodeRows(if (oneShot) Int.MAX_VALUE else DECODE_ROWS)) {
                        decoder.endDecode()
                        padRow = 0
                        phase = if (width < texWidth) Phase.PAD_COLUMNS else Phase.PAD_ROWS
                        if (phase == Phase.PAD_ROWS) padRow = height
                    }
                }

                Phase.PAD_COLUMNS -> {
                    padColumns(if (oneShot) height else PAD_ROWS_PER_UNIT)
                    if (padRow >= height) {
                        padRow = height
                        phase = Phase.PAD_ROWS
                    }
                }

                Phase.PAD_ROWS -> {
                    padRows(if (oneShot) texHeight else PAD_ROWS_PER_UNIT)
                    if (padRow >= texHeight) phase = Phase.CREATE
                }

                Phase.CREATE -> {
                    create(oneShot)
                    if (oneShot) finishUpload() else phase = Phase.UPLOAD
                }

                Phase.UPLOAD -> {
                    uploadBand()
                    if (uploadedRows >= texHeight) finishUpload()
                }

                Phase.DONE -> {}
            }
            if (!oneShot && phase != Phase.DONE && BrowserClock.now() >= end) return false
        }
        return true
    }

    /** Does everything that is left now, and returns the image. */
    fun finish(): Image {
        step(Double.POSITIVE_INFINITY)
        return image!!
    }

    /** Gives up on an unfinished load, freeing the texture it has started. */
    fun cancel() {
        if (phase == Phase.DONE) return
        if (textureId != 0) {
            GL11.glDeleteTextures(textureId)
            textureId = 0
        }
        decoder.endDecode()
        pixels = null
        phase = Phase.DONE
    }

    /** The pixels of the right padding take the last pixel of their row, as the one-shot loader copies them. */
    private fun padColumns(rows: Int) {
        val buffer = pixels!!
        val array = buffer.array()
        val base = buffer.arrayOffset()
        val last = minOf(height, padRow + rows)
        while (padRow < last) {
            val rowStart = base + padRow * texWidth * 4
            val from = rowStart + (width - 1) * 4
            val r = array[from]
            val g = array[from + 1]
            val b = array[from + 2]
            val a = array[from + 3]
            var at = rowStart + width * 4
            val rowEnd = rowStart + texWidth * 4
            while (at < rowEnd) {
                array[at] = r
                array[at + 1] = g
                array[at + 2] = b
                array[at + 3] = a
                at += 4
            }
            padRow++
        }
    }

    /** The bottom padding repeats the last row of the image, which already holds its own right padding. */
    private fun padRows(rows: Int) {
        if (height >= texHeight) {
            padRow = texHeight
            return
        }
        val buffer = pixels!!
        val array = buffer.array()
        val base = buffer.arrayOffset()
        val rowBytes = texWidth * 4
        val source = base + (height - 1) * rowBytes
        val last = minOf(texHeight, padRow + rows)
        while (padRow < last) {
            System.arraycopy(array, source, array, base + padRow * rowBytes, rowBytes)
            padRow++
        }
    }

    private fun create(oneShot: Boolean) {
        val target = GL11.GL_TEXTURE_2D
        textureId = GL11.glGenTextures()
        GL11.glBindTexture(target, textureId)

        // Clamped, not repeated: see TextureLoader.loadTexture.
        GL11.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(target, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)

        val max = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE)
        if (texWidth > max || texHeight > max)
            throw java.io.IOException("Attempt to allocate a texture to big for the current hardware")

        val buffer = pixels!!
        buffer.clear()
        GL11.glTexImage2D(
            target, 0, GL11.GL_RGBA8, texWidth, texHeight, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
            if (oneShot) buffer else null
        )
        if (oneShot) uploadedRows = texHeight
    }

    private fun uploadBand() {
        val buffer = pixels!!
        val rowBytes = texWidth * 4
        val rows = minOf(texHeight - uploadedRows, maxOf(1, UPLOAD_BAND_BYTES / rowBytes))
        buffer.limit(buffer.capacity())
        buffer.position(uploadedRows * rowBytes)
        buffer.limit((uploadedRows + rows) * rowBytes)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, textureId)
        GL11.glTexSubImage2D(
            GL11.GL_TEXTURE_2D, 0, 0, uploadedRows, texWidth, rows, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer
        )
        uploadedRows += rows
    }

    private fun finishUpload() {
        val texture = Texture(texWidth, texHeight, width, height, textureId)
        context.register(texture)
        image = Image(0, 0, texture.imageWidth, texture.imageHeight, texture)
        if (!keepPixels) pixels = null
        phase = Phase.DONE
    }

    companion object {
        /** Rows decoded between looks at the clock: about half a millisecond at most. */
        private const val DECODE_ROWS = 8

        /** Rows of padding written between looks at the clock. */
        private const val PAD_ROWS_PER_UNIT = 64

        /** How much of the texture one upload call sends (a quarter of a megabyte is a fraction of a millisecond). */
        private const val UPLOAD_BAND_BYTES = 256 * 1024
    }
}
