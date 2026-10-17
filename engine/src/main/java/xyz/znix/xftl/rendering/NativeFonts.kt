package xyz.znix.xftl.rendering

import xyz.znix.xftl.SILFontLoader
import xyz.znix.xftl.f
import kotlin.math.roundToInt

/**
 * The desktop game's numeric font IDs, as registered by ResourceControl's font
 * loader (0x005c38d0) and read back from its live font map. IDs 6-8 resolve to
 * 9 and 11 to 12.
 *
 * freetype's printers (0x00596150) take the top of the first line: its baseline
 * is [Spec.baseline] below that, and each further line advances [Spec.lineHeight].
 * Glyphs then hang from the baseline by their own ascent, as SILFontLoader draws.
 */
object NativeFonts {
    class Spec(val name: String, val scale: Float, val baseline: Int, val lineHeight: Int)

    private val specs = mapOf(
        0 to Spec("num_font", 1f, 12, 15),
        5 to Spec("c&c", 1f, 10, 11),
        9 to Spec("JustinFont8", 1f, 12, 15),
        10 to Spec("JustinFont10", 1f, 12, 17),
        12 to Spec("JustinFont11Bold", 1f, 12, 20),
        13 to Spec("JustinFont12Bold", 1f, 12, 22),
        14 to Spec("c&cnew", 2f, 24, 34),
        16 to Spec("c&cnew", 2f, 24, 20),
        18 to Spec("JustinFont11Bold", 2f, 24, 34),
        20 to Spec("JustinFont11Bold", 3f, 36, 53),
        24 to Spec("c&cnew", 3f, 36, 53),
        51 to Spec("HL1", 1f, 8, 10),
        52 to Spec("HL1", 2f, 16, 24),
        53 to Spec("HL1", 3f, 24, 38),
        59 to Spec("HL1", 2f, 16, 18),
        61 to Spec("HL2", 1f, 8, 10),
        62 to Spec("HL2", 2f, 16, 24),
        63 to Spec("HL2", 3f, 24, 38),
        64 to Spec("HL2", 4f, 32, 52),
    )

    fun spec(id: Int): Spec {
        val resolved = when (id) {
            6, 7, 8 -> 9
            11 -> 12
            else -> id
        }
        return specs[resolved] ?: specs.getValue(12)
    }

    /**
     * Where printCenter hands a line's pen to drawString, which rounds half pixels up: native roundf rounds them
     * away from zero in the frame at [frameX], so left of the frame's origin they go left instead.
     */
    fun centredStart(pen: Float, frameX: Float) = if (pen < frameX) frameX - (frameX - pen).roundToInt() else pen

    /** TextButton::SetFont's label offset for the font (0x005905c0). */
    fun buttonTextOffset(id: Int) = when (id) {
        52, 62 -> 2
        63 -> 4
        64 -> 6
        12 -> 3
        else -> 0
    }

    /** A scaled copy of the font behind a native ID. */
    fun font(fonts: (String) -> SILFontLoader, id: Int): NativeFont {
        val spec = spec(id)
        return NativeFont(id, SILFontLoader(fonts(spec.name)).also { it.scale = spec.scale }, spec)
    }

    class NativeFont(val id: Int, val font: SILFontLoader, val spec: Spec) {
        val lineHeight get() = spec.lineHeight

        fun width(text: String) = font.getWidth(text)

        /** freetype::easy_measurePrintLines width: includes the trailing advance. */
        fun advance(text: String) = font.getAdvanceWidth(text)

        /** freetype::measurePrintLines' width, as TextButton auto-width and most layouts use. */
        fun measure(text: String) = font.getExtentWidth(text)

        /** freetype::easy_print. */
        fun print(x: Float, y: Float, text: String, colour: Colour) {
            text.split('\n').forEachIndexed { i, line ->
                font.drawString(x, y + spec.baseline + i * spec.lineHeight, line, colour)
            }
        }

        /**
         * freetype::easy_printCenter: each line centred on [x]. The line layout (0x00649db0) starts the pen
         * half the line's extent left of [x]; the bitmap font's layout (0x00648ba0) then rounds each glyph's
         * pen with roundf (0x007458d0), half away from zero, in the caller's GL frame. An odd width so lands
         * on the pixel to the right where the frame's coordinates are positive, which is what drawString's
         * rounding does, and on the pixel to the left of a translated frame's origin ([frameX], screen
         * coordinates of the native GL_Translate), as with Equipment's over-capacity boxes.
         */
        fun printCenter(x: Float, y: Float, text: String, colour: Colour, frameX: Float = 0f) {
            text.split('\n').forEachIndexed { i, line ->
                val start = centredStart(x - font.getWidth(line) / 2f, frameX)
                font.drawString(start, y + spec.baseline + i * spec.lineHeight, line, colour)
            }
        }

        /** freetype::easy_printRightAlign: right edge at [x]. */
        fun printRight(x: Float, y: Float, text: String, colour: Colour) {
            text.split('\n').forEachIndexed { i, line ->
                font.drawString(x - font.getWidth(line), y + spec.baseline + i * spec.lineHeight, line, colour)
            }
        }

        /**
         * TextButton label placement (TextButton::OnRender 0x00591f00): lines are
         * centred on the button, starting at h/2 - lines*lineHeight/2 + textYOffset.
         */
        fun printButtonLabel(x: Int, y: Int, width: Int, height: Int, label: String, colour: Colour) {
            val lines = label.split('\n')
            // Multi-line labels step by the button's measured line height (that of
            // " "), 26px for font 63 (OptionsScreen's DELETE PROFILE).
            val step = if (lines.size > 1 && id == 63) 26 else spec.lineHeight
            var top = if (step != spec.lineHeight) height / 2 + y - lines.size * step / 2 + buttonTextOffset(id) - 6
            else height / 2 + y - lines.size * spec.lineHeight / 2 + buttonTextOffset(id)
            for (line in lines) {
                printCenter((width / 2 + x).f, top.f, line, colour)
                top += step
            }
        }
    }
}
